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
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import xyz.zcraft.mc.embedded.EmbeddedWaitingServer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * State is confined to actor; the embedded server uses its own relocated network stack.
 */
public final class MinimumPlayerGate {
    static final String WAITING_SERVER = "minimum-player-gate-waiting";
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
    private EmbeddedWaitingServer room;
    private RegisteredServer waitingServer;
    private volatile boolean ready;
    private long revision;

    @Inject
    public MinimumPlayerGate(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try {
            reload();
            if (proxy.getServer(WAITING_SERVER).isPresent())
                throw new IllegalStateException("Reserved backend name already registered: " + WAITING_SERVER);
            var limbo = config.limbo();
            room = EmbeddedWaitingServer.create(limbo.mode(), Path.of(limbo.velocityConfig()), limbo.secretFile());
            waitingServer = proxy.registerServer(new ServerInfo(WAITING_SERVER, room.address()));
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("gate").plugin(this).build(), new GateCommand());
            ready = true;
            actor.scheduleAtFixedRate(() -> guarded(this::tick), 1, 1, TimeUnit.SECONDS);
            logger.info("Virtual waiting server created at {}; no external lobby or LimboAPI needed", room.address());
        } catch (Exception | LinkageError e) {
            if (room != null) room.close();
            logger.error("Gate startup failed. Connections are blocked until configuration is fixed and proxy restarted.", e);
        }
    }

    private void reload() throws Exception {
        GateConfig next = GateConfig.load(directory.resolve("config.properties"), proxy.getAllServers().stream()
                .map(s -> s.getServerInfo().getName()).filter(n -> !n.equals(WAITING_SERVER)).toList());
        if (ready && !config.limbo().equals(next.limbo()))
            throw new IllegalArgumentException("Limbo connection settings require a proxy restart");
        config = next;
        revision++;
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            if (name.equals(WAITING_SERVER)) continue;
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
        for (Visit v : List.copyOf(visits.values())) {
            if (backends.get(name(v.target)) != v.target || proxy.getServer(name(v.target)).orElse(null) != v.target.server)
                fail(v, "Your destination was removed. Please reconnect.");
        }
        backends.entrySet().removeIf(e -> proxy.getServer(e.getKey()).orElse(null) != e.getValue().server);
    }

    // Velocity resolves forced hosts and initial-server selection first. Redirect the actual
    // permitted destination here, rather than duplicating login or authentication internals.
    @Subscribe(priority = -100)
    public EventTask preConnect(ServerPreConnectEvent event) {
        if (!ready) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().disconnect(Component.text("MinimumPlayerGate is unavailable. Please contact the administrator."));
            return null;
        }
        return onActor(() -> {
            var allowed = event.getResult().getServer();
            if (allowed.isEmpty()) return;
            Player p = event.getPlayer();
            if (!p.isActive() || retired.contains(p)) {
                deny(event);
                return;
            }
            RegisteredServer destination = allowed.get();
            Visit v = current(p);
            if (v != null && v.phase != Phase.WAITING) {
                RegisteredServer expected = v.phase == Phase.ENTERING ? waitingServer : v.target.server;
                if (destination == expected && !v.permitUsed) {
                    v.permitUsed = true;
                    return;
                }
                deny(event);
                return;
            }
            if (v == null && on(p, destination)) return; // Already a resident, not a new arrival.
            Backend target = backends.get(destination.getServerInfo().getName());
            if (destination == waitingServer) {
                if (v != null) visits.remove(p.getUniqueId(), v); // Explicit lobby visit leaves the queue.
                return;
            }
            if (target == null || !target.rule.enabled()) {
                if (v != null) visits.remove(p.getUniqueId(), v);
                return;
            }
            if (v != null) {
                v.target = target;
                v.nextAttempt = 0;
                deny(event);
                tick(); // Already in waiting: reassign without reconnecting the room.
            } else if (!target.state.open) {
                v = enqueue(p, target);
                if (v == null) {
                    deny(event);
                    return;
                }
                if (on(p, waitingServer)) {
                    v.phase = Phase.WAITING;
                    deny(event);
                    tick();
                } else {
                    v.permitUsed = true;
                    event.setResult(ServerPreConnectEvent.ServerResult.allowed(waitingServer));
                }
            } else {
                // Count direct admissions while they are connecting, too.
                v = enqueue(p, target);
                if (v == null) {
                    deny(event);
                    return;
                }
                v.phase = Phase.RELEASING;
                v.permitUsed = true;
            }
        });
    }

    @Subscribe
    public void connected(ServerPostConnectEvent event) {
        submit(() -> {
            Visit v = current(event.getPlayer());
            if (v != null) settle(v);
            tick();
        });
    }

    @Subscribe
    public void disconnected(DisconnectEvent event) {
        submit(() -> {
            Visit v = current(event.getPlayer());
            if (v != null) visits.remove(v.player.getUniqueId(), v);
            retired.remove(event.getPlayer());
            tick();
        });
    }

    @Subscribe(priority = -100)
    public EventTask kicked(KickedFromServerEvent event) {
        if (!ready) return null;
        return onActor(() -> {
            Visit v = current(event.getPlayer());
            if (v == null) return;
            if (event.getServer() == waitingServer || (v.phase == Phase.RELEASING && event.getServer() == v.target.server)) {
                visits.remove(v.player.getUniqueId(), v);
                // Do not automatically retry a backend kick/ban or bypass its reason.
                Component reason = event.getServerKickReason().orElse(Component.text("Gate connection failed. Please try /gate join again."));
                if (event.kickedDuringServerConnect() && on(v.player, waitingServer) && event.getServer() != waitingServer)
                    event.setResult(KickedFromServerEvent.Notify.create(reason));
                else {
                    retired.add(v.player);
                    event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
                }
            }
        });
    }

    private Visit enqueue(Player player, Backend target) {
        if (!player.isActive() || retired.contains(player)) return null;
        Visit old = visits.get(player.getUniqueId());
        if (old != null) {
            if (old.player == player) return old;
            fail(old, "A newer connection replaced this waiting session.");
        }
        Visit v = new Visit(player, target, deadline());
        visits.put(player.getUniqueId(), v);
        return v;
    }

    private void settle(Visit v) {
        if (!owns(v)) return;
        if (v.phase == Phase.ENTERING && on(v.player, waitingServer)) {
            v.phase = Phase.WAITING;
            v.player.sendMessage(Component.text("Waiting for " + name(v.target) + ". Use /gate or /gate join <server>."));
        } else if (v.phase == Phase.RELEASING && on(v.player, v.target.server)) {
            visits.remove(v.player.getUniqueId(), v);
        }
    }

    private void tick() {
        if (!ready) return;
        long now = System.nanoTime();
        retired.removeIf(p -> !p.isActive());
        for (Visit v : List.copyOf(visits.values())) {
            if (!v.player.isActive()) {
                visits.remove(v.player.getUniqueId(), v);
                continue;
            }
            settle(v);
            if (!owns(v)) continue;
            if (v.phase != Phase.WAITING && now >= v.deadline) {
                // A timed-out future can still connect later. Disconnect rather than issuing
                // overlapping requests whose late completion could override a new choice.
                fail(v, "Gate transfer timed out. Please reconnect.");
            } else if (v.phase == Phase.WAITING && !on(v.player, waitingServer)) {
                visits.remove(v.player.getUniqueId(), v);
            }
        }
        for (Backend b : backends.values()) {
            if (proxy.getServer(name(b)).orElse(null) != b.server) {
                for (Visit v : queue(b)) fail(v, "Destination was unregistered. Please reconnect.");
                continue;
            }
            List<Player> residents = residents(b);
            List<Visit> queue = queue(b);
            int count = residents.size() + (int) queue.stream().filter(v -> v.phase != Phase.ENTERING).count();
            boolean transferring = queue.stream().anyMatch(v -> v.phase != Phase.WAITING);
            if (b.state.evaluate(b.rule, count, transferring, now))
                logger.info("Gate {} {} (population {})", name(b), b.state.open ? "opened" : "closed", count);
            if (!b.state.open && b.rule.enabled() && b.rule.returnToWaiting()) {
                for (Player p : residents) {
                    Visit v = enqueue(p, b);
                    if (v != null) transfer(v, waitingServer);
                }
            }
            if (b.state.open && !b.probing && now >= b.nextProbe && queue.stream().anyMatch(v -> v.phase == Phase.WAITING && now >= v.nextAttempt))
                probe(b);
        }
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
            b.nextProbe = retryAt();
            tick();
            if (error != null || !b.state.open) return;
            for (Visit v : queue(b)) {
                if (v.phase != Phase.WAITING || !v.player.isActive() || System.nanoTime() < v.nextAttempt) continue;
                v.phase = Phase.RELEASING;
                transfer(v, b.server);
            }
        }));
    }

    private void transfer(Visit v, RegisteredServer destination) {
        v.deadline = deadline();
        v.permitUsed = false;
        long attempt = ++v.attempt;
        CompletableFuture<ConnectionRequestBuilder.Result> request;
        try {
            request = v.player.createConnectionRequest(destination).connect();
        } catch (RuntimeException e) {
            request = CompletableFuture.failedFuture(e);
        }
        request.whenComplete((result, error) -> submit(() -> {
            if (!owns(v) || attempt != v.attempt) return;
            if (error == null && result != null && (result.isSuccessful()
                    || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED)) {
                settle(v);
                return;
            }
            if (v.phase == Phase.ENTERING) {
                fail(v, "Unable to enter virtual waiting room. Please reconnect.");
                return;
            }
            if (on(v.player, waitingServer) && (error != null || (result != null
                    && result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS))) {
                v.phase = Phase.WAITING;
                v.nextAttempt = retryAt();
                v.player.sendMessage(Component.text("Backend unavailable; you remain queued. Retrying shortly."));
            } else {
                // Cancellation / server rejection must not be retried automatically.
                visits.remove(v.player.getUniqueId(), v);
                Component message = result == null ? Component.text("Transfer failed. Use /gate join to retry.")
                        : result.getReasonComponent().orElse(Component.text("Transfer refused (" + result.getStatus() + "). Use /gate join to retry."));
                v.player.sendMessage(message);
                if (v.player.getCurrentServer().isEmpty()) v.player.disconnect(message);
            }
        }));
    }

    private List<Player> residents(Backend b) {
        return b.server.getPlayersConnected().stream().filter(Player::isActive)
                .filter(p -> !visits.containsKey(p.getUniqueId()) && !retired.contains(p))
                .filter(p -> on(p, b.server)).toList();
    }

    private List<Visit> queue(Backend b) {
        return visits.values().stream().filter(v -> v.target == b).toList();
    }

    private boolean on(Player p, RegisteredServer s) {
        return p.getCurrentServer().map(c -> c.getServer() == s).orElse(false);
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

    private long retryAt() {
        return System.nanoTime() + config.retrySeconds() * 1_000_000_000L;
    }

    private void deny(ServerPreConnectEvent e) {
        e.setResult(ServerPreConnectEvent.ServerResult.denied());
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
        } catch (RejectedExecutionException ignored) {
        }
    }

    private EventTask onActor(Runnable task) {
        return EventTask.resumeWhenComplete(CompletableFuture.runAsync(task, actor));
    }

    @Subscribe
    public EventTask shutdown(ProxyShutdownEvent event) {
        ready = false;
        return onActor(() -> {
            for (Visit v : List.copyOf(visits.values())) fail(v, "Proxy shutting down.");
            if (waitingServer != null) proxy.unregisterServer(waitingServer.getServerInfo());
            if (room != null) room.close();
            actor.shutdown();
        });
    }

    private enum Phase {ENTERING, WAITING, RELEASING}

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
        Phase phase = Phase.ENTERING;
        long deadline, nextAttempt, attempt;
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
            submit(() -> {
                String[] args = invocation.arguments();
                if (args.length == 1 && args[0].equalsIgnoreCase("reload") && invocation.source().hasPermission("minimumplayergate.admin")) {
                    try {
                        reload();
                        tick();
                        invocation.source().sendMessage(Component.text("Gate configuration reloaded."));
                    } catch (Exception e) {
                        logger.warn("Rejected gate reload", e);
                        invocation.source().sendMessage(Component.text("Reload rejected: " + e.getMessage()));
                    }
                } else if (args.length == 2 && args[0].equalsIgnoreCase("join") && invocation.source() instanceof Player p) {
                    Backend b = backends.get(args[1]);
                    if (b == null) {
                        p.sendMessage(Component.text("Unknown server: " + args[1]));
                        return;
                    }
                    p.createConnectionRequest(b.server).connect().whenComplete((result, error) -> {
                        if (error != null) p.sendMessage(Component.text("Connection failed. Please try again."));
                    });
                } else if (args.length == 1 && args[0].equalsIgnoreCase("leave") && invocation.source() instanceof Player p) {
                    Visit v = current(p);
                    if (v != null && v.phase == Phase.WAITING) {
                        visits.remove(p.getUniqueId(), v);
                        p.sendMessage(Component.text("You left the queue."));
                        tick();
                    } else p.sendMessage(Component.text("No waiting queue, or a transfer is in progress."));
                } else {
                    for (Backend b : backends.values())
                        invocation.source().sendMessage(Component.text(name(b) + ": "
                                + (b.state.open ? "open" : "closed") + ", backend=" + residents(b).size()
                                + ", queue/transfers=" + queue(b).size() + ", minimum=" + b.rule.minimumPlayers()));
                    invocation.source().sendMessage(Component.text("/gate join <server>; /gate leave; /gate reload (admin)"));
                }
            });
        }
    }
}
