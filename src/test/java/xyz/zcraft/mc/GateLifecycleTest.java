package xyz.zcraft.mc;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import net.elytrium.limboapi.api.*;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.LimboPlayer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GateLifecycleTest {
    ProxyServer proxy;
    MinimumPlayerGate plugin;
    Limbo room;
    ScheduledExecutorService actor;
    Map<Player, LimboSessionHandler> handlers;
    Map<Player, LimboPlayer> limboPlayers;
    RegisteredServer a, b;
    CompletableFuture<ServerPing> ping;

    @BeforeEach void setup() throws Exception {
        proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        plugin = new MinimumPlayerGate(proxy, mock(Logger.class), Path.of("unused"));
        room = mock(Limbo.class);
        set("room", room); set("factory", mock(LimboFactory.class));
        set("config", new GateConfig(Map.of(), 30, 10)); set("ready", true);
        actor = (ScheduledExecutorService) get("actor");
        handlers = new IdentityHashMap<>(); limboPlayers = new IdentityHashMap<>();
        doAnswer(inv -> {
            Player p = inv.getArgument(0); LimboSessionHandler handler = inv.getArgument(1);
            LimboPlayer lp = mock(LimboPlayer.class);
            handlers.put(p, handler); limboPlayers.put(p, lp); handler.onSpawn(room, lp); return null;
        }).when(room).spawnPlayer(any(), any());
        ping = new CompletableFuture<>();
        a = backend("a"); b = backend("b");
        when(proxy.getConfiguration().getAttemptConnectionOrder()).thenReturn(List.of("a"));
    }
    @AfterEach void close() { actor.shutdownNow(); }

    RegisteredServer backend(String name) throws Exception {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("localhost", 25565)));
        when(server.getPlayersConnected()).thenReturn(List.of());
        when(server.ping()).thenAnswer(inv -> ping);
        when(proxy.getServer(name)).thenReturn(Optional.of(server));
        Class<?> type = Class.forName("xyz.zcraft.mc.MinimumPlayerGate$Backend");
        Constructor<?> c = type.getDeclaredConstructors()[0]; c.setAccessible(true);
        Object state = c.newInstance(server, new GateConfig.Rule(true, 2, 1, 0, true));
        ((Map<String, Object>) get("backends")).put(name, state);
        return server;
    }
    Player player(UUID id) {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(id); when(p.isActive()).thenReturn(true);
        when(p.getVirtualHost()).thenReturn(Optional.empty()); when(p.getCurrentServer()).thenReturn(Optional.empty());
        return p;
    }
    Player login() throws Exception { Player p = player(UUID.randomUUID()); login(p); return p; }
    void login(Player p) throws Exception {
        LoginLimboRegisterEvent e = new LoginLimboRegisterEvent(p); plugin.login(e);
        for (Runnable r : e.getOnJoinCallbacks()) r.run(); drain();
    }
    void drain() throws Exception { for (int i = 0; i < 8; i++) actor.submit(() -> {}).get(3, TimeUnit.SECONDS); }
    Object get(String name) throws Exception { Field f = MinimumPlayerGate.class.getDeclaredField(name); f.setAccessible(true); return f.get(plugin); }
    void set(String name, Object value) throws Exception { Field f = MinimumPlayerGate.class.getDeclaredField(name); f.setAccessible(true); f.set(plugin, value); }
    int visits() throws Exception { drain(); return ((Map<?, ?>) get("visits")).size(); }
    void tick() throws Exception {
        Method m = MinimumPlayerGate.class.getDeclaredMethod("tick"); m.setAccessible(true);
        actor.submit(() -> { try { m.invoke(plugin); } catch (Exception e) { throw new RuntimeException(e); } }).get(); drain();
    }

    @Test void releaseBatchExactlyOnceAndLimboExitDoesNotRemoveTransfer() throws Exception {
        Player first = login(); verify(limboPlayers.get(first), never()).disconnect(any(RegisteredServer.class));
        Player second = login(); ping.complete(mock(ServerPing.class)); drain();
        verify(limboPlayers.get(first), times(1)).disconnect(a); verify(limboPlayers.get(second), times(1)).disconnect(a);
        handlers.get(first).onDisconnect(); drain(); assertEquals(2, visits());
        tick(); verify(limboPlayers.get(first), times(1)).disconnect(a);
    }
    @Test void backendFailureKeepsPlayersWaiting() throws Exception {
        Player first = login(); login(); ping.completeExceptionally(new IllegalStateException("offline")); drain();
        assertEquals(2, visits()); verify(limboPlayers.get(first), never()).disconnect(any(RegisteredServer.class));
        verify(first, never()).disconnect(any());
    }
    @Test void oldDisconnectCannotDeleteReconnectedPlayer() throws Exception {
        Player old = login(); LimboSessionHandler oldHandler = handlers.get(old);
        Player replacement = player(old.getUniqueId()); login(replacement);
        oldHandler.onDisconnect();
        DisconnectEvent event = mock(DisconnectEvent.class); when(event.getPlayer()).thenReturn(old); plugin.disconnected(event);
        drain(); assertEquals(1, visits()); verify(replacement, never()).disconnect(any());
    }
    @Test void queuesAreIndependentAndSwitchDoesNotDuplicateSpawn() throws Exception {
        Player first = login(); handlers.get(first).onChat("/gate join b"); drain();
        Player second = login(); ping.complete(mock(ServerPing.class)); drain();
        verify(limboPlayers.get(first), never()).disconnect(any(RegisteredServer.class));
        verify(limboPlayers.get(second), never()).disconnect(any(RegisteredServer.class));
        verify(room, times(1)).spawnPlayer(eq(first), any());
        handlers.get(first).onChat("/gate join a"); drain();
        verify(limboPlayers.get(first)).disconnect(a); verify(limboPlayers.get(second)).disconnect(a);
    }
    @Test void releasePermitIsSingleUseAndTargetBound() throws Exception {
        Player p = login(); login(); ping.complete(mock(ServerPing.class)); drain();
        ServerPreConnectEvent wrong = new ServerPreConnectEvent(p, b); plugin.preConnect(wrong); drain();
        assertTrue(wrong.getResult().getServer().isEmpty());
        ServerPreConnectEvent right = new ServerPreConnectEvent(p, a); plugin.preConnect(right); drain();
        assertEquals(Optional.of(a), right.getResult().getServer());
        ServerPreConnectEvent duplicate = new ServerPreConnectEvent(p, a); plugin.preConnect(duplicate); drain();
        assertTrue(duplicate.getResult().getServer().isEmpty());
    }
    @Test void respectsEarlierAccessDenial() throws Exception {
        Player p = player(UUID.randomUUID());
        ServerPreConnectEvent event = new ServerPreConnectEvent(p, a);
        event.setResult(ServerPreConnectEvent.ServerResult.denied()); plugin.preConnect(event); drain();
        assertTrue(event.getResult().getServer().isEmpty()); verifyNoInteractions(room);
    }
    @Test void pendingPingCannotReleasePlayerWhoLeftQueue() throws Exception {
        Player first = login(); Player second = login();
        when(second.isActive()).thenReturn(false);
        DisconnectEvent event = mock(DisconnectEvent.class); when(event.getPlayer()).thenReturn(second); plugin.disconnected(event); drain();
        // Close threshold is one, so gate remains open for first; departed player is never released.
        ping.complete(mock(ServerPing.class)); drain();
        verify(limboPlayers.get(second), never()).disconnect(any(RegisteredServer.class));
        verify(limboPlayers.get(first)).disconnect(a);
    }
    @Test void stalePingAfterRevisionChangeCannotRelease() throws Exception {
        Player p = login(); login(); set("revision", 1L); ping.complete(mock(ServerPing.class)); drain();
        verify(limboPlayers.get(p), never()).disconnect(any(RegisteredServer.class));
    }
    void rule(RegisteredServer server, boolean returnPlayers) throws Exception {
        Object backend = ((Map<?, ?>) get("backends")).get(server.getServerInfo().getName());
        Field f = backend.getClass().getDeclaredField("rule"); f.setAccessible(true);
        f.set(backend, new GateConfig.Rule(true, 3, 2, 0, returnPlayers));
    }
    Player resident() {
        Player p = player(UUID.randomUUID()); ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServer()).thenReturn(a); when(p.getCurrentServer()).thenReturn(Optional.of(connection));
        when(a.getPlayersConnected()).thenReturn(List.of(p)); return p;
    }
    @Test void closedGateReturnsResidentsWithoutDoubleCountingStaleBackendSnapshot() throws Exception {
        rule(a, true); Player p = resident(); tick();
        assertEquals(1, visits()); verify(room).spawnPlayer(eq(p), any());
        tick(); verify(room, times(1)).spawnPlayer(eq(p), any());
        verify(limboPlayers.get(p), never()).disconnect(any(RegisteredServer.class));
    }
    @Test void closedGateCanLeaveExistingResidentsInPlace() throws Exception {
        rule(a, false); resident(); tick(); assertEquals(0, visits()); verifyNoInteractions(room);
    }
    @Test void timedOutTransferRemovesReservationAndDisconnects() throws Exception {
        Player p = login(); login(); ping.complete(mock(ServerPing.class)); drain();
        Object visit = ((Map<?, ?>) get("visits")).get(p.getUniqueId());
        Field deadline = visit.getClass().getDeclaredField("deadline"); deadline.setAccessible(true);
        actor.submit(() -> { try { deadline.setLong(visit, System.nanoTime() - 1); } catch (Exception e) { throw new RuntimeException(e); } }).get();
        tick(); verify(p).disconnect(any()); assertEquals(1, visits());
    }
    @Test void forcedHostChoosesIndependentBackend() throws Exception {
        Player p = player(UUID.randomUUID()); when(p.getVirtualHost()).thenReturn(Optional.of(new InetSocketAddress("game.example", 25565)));
        when(proxy.getConfiguration().getForcedHosts()).thenReturn(Map.of("game.example", List.of("b")));
        login(p); Player other = login(); ping.complete(mock(ServerPing.class)); drain();
        verify(limboPlayers.get(p), never()).disconnect(any(RegisteredServer.class));
        verify(limboPlayers.get(other), never()).disconnect(any(RegisteredServer.class));
    }
    @Test void explicitSwitchIntoClosedBackendEntersLimbo() throws Exception {
        Player p = resident(); ServerPreConnectEvent event = new ServerPreConnectEvent(p, b);
        plugin.preConnect(event); drain(); assertTrue(event.getResult().getServer().isEmpty());
        verify(room).spawnPlayer(eq(p), any()); assertEquals(1, visits());
    }
    @Test void synchronousPingFailureDoesNotLeaveProbeInFlight() throws Exception {
        when(a.ping()).thenThrow(new IllegalStateException("failed immediately"));
        login(); login(); drain();
        Object backend = ((Map<?, ?>) get("backends")).get("a");
        Field probing = backend.getClass().getDeclaredField("probing"); probing.setAccessible(true);
        assertFalse(probing.getBoolean(backend)); assertEquals(2, visits());
    }}

