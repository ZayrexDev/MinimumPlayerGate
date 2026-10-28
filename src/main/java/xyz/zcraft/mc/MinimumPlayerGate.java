package xyz.zcraft.mc;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * All mutable gate state is confined to actor. Never block a Velocity/Netty thread.
 */
public final class MinimumPlayerGate {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final ScheduledExecutorService actor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "minimum-player-gate");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Backend> backends = new LinkedHashMap<>();
    private final Map<UUID, Visit> visits = new HashMap<>();
    private final Set<Player> retired = Collections.newSetFromMap(new IdentityHashMap<>());
    private GateConfig config;
    private LimboFactory factory;
    private Limbo room;
    private volatile boolean ready;
    private long revision;

    @Inject
    public MinimumPlayerGate(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) throws Exception {
        factory = (LimboFactory) proxy
                .getPluginManager()
                .getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .orElseThrow(() -> new IllegalStateException("LimboAPI required"));

        reload();

        room = factory.createLimbo(factory.createVirtualWorld(Dimension.OVERWORLD, 0, 64, 0, 0, 0))
                .setName("MinimumPlayerGate")
                .setGameMode(GameMode.ADVENTURE);

        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("gate").plugin(this).build(), new GateCommand());

        ready = true;


        actor.scheduleAtFixedRate(() -> guarded(this::tick), 1, 1, TimeUnit.SECONDS);
        logger.info("MinimumPlayerGate ready; {} backends configured", backends.size());
    }

    private void reload() throws Exception {
        config = GateConfig.load(
                directory.resolve("config.properties"),
                proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).toList()
        );

        revision++;

        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            Backend b = backends.get(name);
            if (b == null || b.server != server) {
                b = new Backend(server, config.servers().get(name));
                backends.put(name, b);
            }
            b.rule = config.servers().get(name);
            b.state.resetGrace();
            b.probing = false;
            b.nextProbe = 0;
        }

        // Unregistered/replaced targets cannot retain live queues.
        for (Visit v : List.copyOf(visits.values())) {
            if (backends.get(name(v.target)) != v.target
                    || proxy.getServer(name(v.target)).orElse(null) != v.target.server)
                fail(v, "Your destination was removed. Please reconnect.");
        }

        backends.entrySet().removeIf(e -> proxy.getServer(e.getKey()).orElse(null) != e.getValue().server);
    }

    @Subscribe(priority = -100)
    public void login(LoginLimboRegisterEvent event) {
        if (!ready) return;
        event.addOnJoinCallback(() -> submit(() -> {
            Player p = event.getPlayer();
            Backend b = initialTarget(p);
            if (b == null || !b.rule.enabled()) {
                factory.passLoginLimbo(p);
                return;
            }
            enqueue(p, b);
            tick();
        }));
    }

    private Backend initialTarget(Player player) {
        List<String> order = player.getVirtualHost().map(h -> proxy.getConfiguration().getForcedHosts()
                .get(h.getHostString().toLowerCase(Locale.ROOT))).orElse(null);
        if (order == null || order.isEmpty()) order = proxy.getConfiguration().getAttemptConnectionOrder();
        for (String name : order) {
            Backend b = backends.get(name);
            if (b != null) return b;
        }
        return null;
    }

    @Subscribe(priority = -100)
    public EventTask preConnect(ServerPreConnectEvent event) {
        if (!ready) return null;
        return onActor(() -> {
            Optional<RegisteredServer> result = event.getResult().getServer();
            if (result.isEmpty()) return; // Respect earlier access-control denials.
            Player p = event.getPlayer();
            if (retired.contains(p) || !p.isActive()) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                return;
            }
            RegisteredServer destination = result.get();
            Backend b = backends.get(destination.getServerInfo().getName());
            Visit v = current(p);
            if (v != null && v.phase == Phase.RELEASING) {
                if (v.target.server == destination && !v.permitUsed) {
                    v.permitUsed = true;
                    return;
                }
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                return;
            }
            if (v != null) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                if (b != null && v.phase == Phase.WAITING) {
                    v.target = b;
                    tick();
                }
                return;
            }
            if (b != null && b.rule.enabled() && !b.state.open) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                // Initial login should have been intercepted by the login callback. A routing
                // plugin changing its destination here cannot safely restart the login pipeline.
                if (p.getCurrentServer().isEmpty()) {
                    p.disconnect(Component.text("Destination gate is closed. Reconnect using its configured host."));
                } else {
                    // Run after this awaited event completes, avoiding nested connection handling.
                    submit(() -> {
                        if (p.isActive() && current(p) == null) {
                            enqueue(p, b);
                            tick();
                        }
                    });
                }
            }
        });
    }

    @Subscribe
    public void connected(ServerPostConnectEvent event) {
        submit(() -> {
            Visit v = current(event.getPlayer());
            if (v != null && v.phase == Phase.RELEASING) {
                if (event.getPlayer().getCurrentServer().map(s -> s.getServer() == v.target.server).orElse(false))
                    visits.remove(v.player.getUniqueId(), v);
                else fail(v, "Your gate transfer was redirected. Please reconnect.");
            }
            tick();
        });
    }

    @Subscribe
    public void disconnected(DisconnectEvent event) {
        submit(() -> {
            Visit v = current(event.getPlayer());
            if (v != null) visits.remove(v.player.getUniqueId(), v);
            tick();
        });
    }

    @Subscribe(priority = -100)
    public EventTask kicked(KickedFromServerEvent event) {
        if (!ready) return null;
        return onActor(() -> {
            Visit v = current(event.getPlayer());
            if (v != null && v.phase == Phase.RELEASING && event.getServer() == v.target.server) {
                // Preserve backend kick/ban reason and prohibit automatic fallback bypass.
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(event.getServerKickReason()
                        .orElse(Component.text("Backend connection failed. Please reconnect to queue again."))));
                visits.remove(v.player.getUniqueId(), v);
            }
        });
    }

    private void enqueue(Player player, Backend target) {
        if (retired.contains(player) || !player.isActive()) return;
        Visit old = visits.get(player.getUniqueId());
        if (old != null) {
            if (old.player == player) return;
            fail(old, "A newer connection replaced this waiting session.");
        }
        if (!player.isActive()) return;
        Visit v = new Visit(player, target, deadline());
        visits.put(player.getUniqueId(), v);
        try {
            room.spawnPlayer(player, new LimboSessionHandler() {
                @Override
                public void onSpawn(Limbo server, LimboPlayer lp) {
                    lp.disableFalling();
                    submit(() -> {
                        if (!owns(v) || v.phase != Phase.SPAWNING) return;
                        v.limbo = lp;
                        v.phase = Phase.WAITING;
                        player.sendMessage(Component.text("Waiting for " + name(v.target) + ". Use /gate join <server> to change queue."));
                        tick();
                    });
                }

                @Override
                public void onDisconnect() {
                    submit(() -> {
                        // Limbo exit also fires this callback during a successful transfer.
                        if (owns(v) && v.phase != Phase.RELEASING) {
                            visits.remove(player.getUniqueId(), v);
                            if (player.isActive())
                                player.disconnect(Component.text("Waiting session ended. Please reconnect."));
                        }
                    });
                }

                @Override
                public void onChat(String chat) {
                    if (chat.startsWith("/gate")) submit(() -> waitingCommand(v, chat));
                }
            });
        } catch (RuntimeException e) {
            logger.error("Unable to spawn waiting player", e);
            fail(v, "Unable to enter waiting room. Please reconnect.");
        }
    }

    private void tick() {
        if (!ready) return;
        long now = System.nanoTime();
        retired.removeIf(p -> !p.isActive());
        for (Visit v : List.copyOf(visits.values())) {
            if (!v.player.isActive()) visits.remove(v.player.getUniqueId(), v);
            else if (v.phase != Phase.WAITING && now >= v.deadline)
                fail(v, "Gate transfer timed out. Please reconnect to try again.");
        }
        for (Backend b : backends.values()) {
            if (proxy.getServer(name(b)).orElse(null) != b.server) {
                for (Visit v : queue(b)) fail(v, "Destination was unregistered. Please reconnect.");
                continue;
            }
            List<Player> residents = residents(b);
            List<Visit> queue = queue(b);
            int count = residents.size() + (int) queue.stream().filter(v -> v.phase != Phase.SPAWNING).count();
            boolean transferring = queue.stream().anyMatch(v -> v.phase != Phase.WAITING);
            boolean changed = b.state.evaluate(b.rule, count, transferring, now);
            if (changed) logger.info("Gate {} {} (population {})", name(b), b.state.open ? "opened" : "closed", count);
            if (!b.state.open && b.rule.enabled() && b.rule.returnToWaiting()) {
                // Also enforce closed state after reload and initial startup.
                for (Player p : residents) enqueue(p, b);
            }
            if (b.state.open && queue.stream().anyMatch(v -> v.phase == Phase.WAITING) && !b.probing && now >= b.nextProbe)
                probe(b);
        }
    }

    private List<Player> residents(Backend b) {
        // Ignore stale getPlayersConnected snapshots after LimboAPI detaches a backend.
        return b.server.getPlayersConnected().stream().filter(Player::isActive)
                .filter(p -> !visits.containsKey(p.getUniqueId()) && !retired.contains(p))
                .filter(p -> p.getCurrentServer().map(s -> s.getServer() == b.server).orElse(false)).toList();
    }

    private List<Visit> queue(Backend b) {
        return visits.values().stream().filter(v -> v.target == b).toList();
    }

    private void probe(Backend b) {
        b.probing = true;
        long generation = revision;
        CompletableFuture<ServerPing> request;
        try {
            request = b.server.ping();
        } catch (RuntimeException e) {
            request = CompletableFuture.failedFuture(e);
        }
        request.orTimeout(5, TimeUnit.SECONDS).whenComplete((ping, error) -> submit(() -> {
            if (generation != revision || backends.get(name(b)) != b) return;
            b.probing = false;
            b.nextProbe = System.nanoTime() + config.retrySeconds() * 1_000_000_000L;
            tick(); // Recheck threshold after asynchronous network work.
            if (error != null || !b.state.open) return;
            for (Visit v : queue(b)) {
                if (v.phase != Phase.WAITING || !v.player.isActive()) continue;
                v.phase = Phase.RELEASING;
                v.deadline = deadline();
                v.permitUsed = false;
                try {
                    v.limbo.disconnect(b.server);
                } catch (RuntimeException e) {
                    logger.warn("Gate release failed", e);
                    fail(v, "Backend transfer failed. Please reconnect.");
                }
            }
        }));
    }

    private void waitingCommand(Visit v, String chat) {
        if (!owns(v) || v.phase != Phase.WAITING) return;
        String[] args = chat.trim().split("\\s+");
        if (args.length == 3 && args[0].equals("/gate") && args[1].equalsIgnoreCase("join")) join(v.player, args[2]);
        else if (args.length == 1 && args[0].equals("/gate")) status(v.player);
    }

    private void join(Player player, String name) {
        Backend b = backends.get(name);
        if (b == null) {
            player.sendMessage(Component.text("Unknown server: " + name));
            return;
        }
        Visit v = current(player);
        if (v != null) {
            if (v.phase != Phase.WAITING) {
                player.sendMessage(Component.text("Transfer in progress; wait before switching."));
                return;
            }
            v.target = b;
            tick();
        } else {
            player.createConnectionRequest(b.server).connect().whenComplete((result, error) -> {
                if (error != null) player.sendMessage(Component.text("Connection failed; please try again."));
            });
        }
    }

    private void status(com.velocitypowered.api.command.CommandSource source) {
        for (Backend b : backends.values())
            source.sendMessage(Component.text(name(b) + ": " + (b.state.open ? "open" : "closed")
                    + ", backend=" + residents(b).size() + ", waiting/transferring=" + queue(b).size() + ", minimum=" + b.rule.minimumPlayers()));
        source.sendMessage(Component.text("/gate join <server>; /gate reload (admin)"));
    }

    private Visit current(Player p) {
        Visit v = visits.get(p.getUniqueId());
        return v != null && v.player == p ? v : null;
    }

    private boolean owns(Visit v) {
        return current(v.player) == v;
    }

    private String name(Backend b) {
        return b.server.getServerInfo().getName();
    }

    private long deadline() {
        return System.nanoTime() + config.transferTimeoutSeconds() * 1_000_000_000L;
    }

    private void fail(Visit v, String message) {
        if (visits.remove(v.player.getUniqueId(), v) && v.player.isActive()) {
            retired.add(v.player);
            v.player.disconnect(Component.text(message));
        }
    }

    private void guarded(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            logger.error("Gate operation failed", e);
        }
    }

    private void submit(Runnable task) {
        try {
            actor.execute(() -> guarded(task));
        } catch (RejectedExecutionException ignored) { /* shutdown */ }
    }

    private EventTask onActor(Runnable task) {
        return EventTask.resumeWhenComplete(CompletableFuture.runAsync(task, actor));
    }

    @Subscribe
    public EventTask shutdown(ProxyShutdownEvent event) {
        ready = false;
        return onActor(() -> {
            for (Visit v : List.copyOf(visits.values())) fail(v, "Proxy shutting down.");
            if (room != null) room.dispose();
            actor.shutdown();
        });
    }

    private enum Phase {SPAWNING, WAITING, RELEASING}

    private static final class Backend {
        final RegisteredServer server;
        final GateState state = new GateState();
        GateConfig.Rule rule;
        boolean probing;
        long nextProbe;

        Backend(RegisteredServer server, GateConfig.Rule rule) {
            this.server = server;
            this.rule = rule;
        }
    }

    private static final class Visit {
        final Player player;
        Backend target;
        Phase phase = Phase.SPAWNING;
        LimboPlayer limbo;
        long deadline;
        boolean permitUsed;

        Visit(Player player, Backend target, long deadline) {
            this.player = player;
            this.target = target;
            this.deadline = deadline;
        }
    }

    private final class GateCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            submit(() -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload") && invocation.source().hasPermission("minimumplayergate.admin")) {
                    try {
                        reload();
                        tick();
                        invocation.source().sendMessage(Component.text("Gate configuration reloaded."));
                    } catch (Exception e) {
                        logger.warn("Rejected gate reload", e);
                        invocation.source().sendMessage(Component.text("Reload rejected: " + e.getMessage()));
                    }
                } else if (args.length == 2 && args[0].equalsIgnoreCase("join") && invocation.source() instanceof Player player) {
                    join(player, args[1]);
                } else status(invocation.source());
            });
        }
    }
}
