package xyz.zcraft.mc;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GateLifecycleTest {
    ProxyServer proxy;
    MinimumPlayerGate plugin;
    ScheduledExecutorService actor;
    RegisteredServer a, b, waiting;
    CompletableFuture<ServerPing> ping;
    Map<Player, List<RegisteredServer>> attempts;
    Map<Player, CompletableFuture<ConnectionRequestBuilder.Result>> connections;

    @BeforeEach
    void setup() throws Exception {
        proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        plugin = new MinimumPlayerGate(proxy, mock(Logger.class), Path.of("unused"));
        set("config", new GateConfig(Map.of(), 30, 10, new GateConfig.LimboSettings("NONE", "velocity.toml", "")));
        set("ready", true);
        actor = (ScheduledExecutorService) get("actor");
        attempts = new IdentityHashMap<>();
        connections = new IdentityHashMap<>();
        ping = new CompletableFuture<>();
        a = backend("a");
        b = backend("b");
        waiting = mock(RegisteredServer.class);
        set("waitingServer", waiting);
        when(waiting.getServerInfo()).thenReturn(new ServerInfo(MinimumPlayerGate.WAITING_SERVER, new InetSocketAddress("localhost", 12345)));
    }

    @AfterEach
    void close() {
        actor.shutdownNow();
    }

    RegisteredServer backend(String name) throws Exception {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("localhost", 25565)));
        when(server.getPlayersConnected()).thenReturn(List.of());
        when(server.ping()).thenAnswer(inv -> ping);
        when(proxy.getServer(name)).thenReturn(Optional.of(server));
        Class<?> type = Class.forName("xyz.zcraft.mc.MinimumPlayerGate$Backend");
        Constructor<?> c = type.getDeclaredConstructors()[0];
        c.setAccessible(true);
        Object state = c.newInstance(server, new GateConfig.Rule(true, 2, 1, 0, true));
        ((Map<String, Object>) get("backends")).put(name, state);
        return server;
    }

    Player player(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.isActive()).thenReturn(true);
        when(p.getCurrentServer()).thenReturn(Optional.empty());
        attempts.put(p, new ArrayList<>());
        when(p.createConnectionRequest(any())).thenAnswer(inv -> {
            RegisteredServer destination = inv.getArgument(0);
            ConnectionRequestBuilder builder = mock(ConnectionRequestBuilder.class);
            when(builder.connect()).thenAnswer(call -> {
                attempts.get(p).add(destination);
                CompletableFuture<ConnectionRequestBuilder.Result> future = new CompletableFuture<>();
                connections.put(p, future);
                return future;
            });
            return builder;
        });
        return p;
    }

    void location(Player p, RegisteredServer server) {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServer()).thenReturn(server);
        when(p.getCurrentServer()).thenReturn(Optional.of(connection));
    }

    Player login() throws Exception {
        Player p = player(UUID.randomUUID());
        login(p, a);
        return p;
    }

    void login(Player p, RegisteredServer server) throws Exception {
        ServerPreConnectEvent e = new ServerPreConnectEvent(p, server);
        plugin.preConnect(e);
        drain();
        assertEquals(Optional.of(waiting), e.getResult().getServer());
        location(p, waiting);
        post(p);
    }

    void post(Player p) throws Exception {
        ServerPostConnectEvent e = mock(ServerPostConnectEvent.class);
        when(e.getPlayer()).thenReturn(p);
        plugin.connected(e);
        drain();
    }

    void drain() throws Exception {
        for (int i = 0; i < 8; i++)
            actor.submit(() -> {
            }).get(3, TimeUnit.SECONDS);
    }

    Object get(String name) throws Exception {
        Field f = MinimumPlayerGate.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(plugin);
    }

    void set(String name, Object value) throws Exception {
        Field f = MinimumPlayerGate.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(plugin, value);
    }

    int visits() throws Exception {
        drain();
        return ((Map<?, ?>) get("visits")).size();
    }

    void tick() throws Exception {
        Method m = MinimumPlayerGate.class.getDeclaredMethod("tick");
        m.setAccessible(true);
        actor.submit(() -> {
            try {
                m.invoke(plugin);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get();
        drain();
    }

    void rule(RegisteredServer server, boolean returnPlayers) throws Exception {
        Object backend = ((Map<?, ?>) get("backends")).get(server.getServerInfo().getName());
        Field f = backend.getClass().getDeclaredField("rule");
        f.setAccessible(true);
        f.set(backend, new GateConfig.Rule(true, 3, 2, 0, returnPlayers));
    }

    Player resident() {
        Player p = player(UUID.randomUUID());
        location(p, a);
        when(a.getPlayersConnected()).thenReturn(List.of(p));
        return p;
    }

    @Test
    void releaseBatchExactlyOnceAndWaitForArrival() throws Exception {
        Player first = login();
        assertTrue(attempts.get(first).isEmpty());
        Player second = login();
        ping.complete(mock(ServerPing.class));
        drain();
        assertEquals(List.of(a), attempts.get(first));
        assertEquals(List.of(a), attempts.get(second));
        tick();
        assertEquals(List.of(a), attempts.get(first));
        assertEquals(2, visits());
        location(first, a);
        post(first);
        assertEquals(1, visits());
    }

    @Test
    void backendFailureKeepsPlayersWaiting() throws Exception {
        Player first = login();
        login();
        ping.completeExceptionally(new IllegalStateException("offline"));
        drain();
        assertEquals(2, visits());
        assertTrue(attempts.get(first).isEmpty());
        verify(first, never()).disconnect(any());
    }

    @Test
    void oldDisconnectCannotDeleteReconnectedPlayer() throws Exception {
        Player old = login();
        Player replacement = player(old.getUniqueId());
        login(replacement, a);
        DisconnectEvent event = mock(DisconnectEvent.class);
        when(event.getPlayer()).thenReturn(old);
        plugin.disconnected(event);
        drain();
        assertEquals(1, visits());
        verify(replacement, never()).disconnect(any());
    }

    @Test
    void queuesAreIndependentAndSwitchDoesNotReconnectRoom() throws Exception {
        Player first = login();
        ServerPreConnectEvent switchEvent = new ServerPreConnectEvent(first, b);
        plugin.preConnect(switchEvent);
        drain();
        assertTrue(switchEvent.getResult().getServer().isEmpty());
        Player second = login();
        ping.complete(mock(ServerPing.class));
        drain();
        assertTrue(attempts.get(first).isEmpty());
        assertTrue(attempts.get(second).isEmpty());
        plugin.preConnect(new ServerPreConnectEvent(first, a));
        drain();
        assertEquals(List.of(a), attempts.get(first));
        assertEquals(List.of(a), attempts.get(second));
    }

    @Test
    void releasePermitIsSingleUseAndTargetBound() throws Exception {
        Player p = login();
        login();
        ping.complete(mock(ServerPing.class));
        drain();
        ServerPreConnectEvent wrong = new ServerPreConnectEvent(p, b);
        plugin.preConnect(wrong);
        drain();
        assertTrue(wrong.getResult().getServer().isEmpty());
        ServerPreConnectEvent right = new ServerPreConnectEvent(p, a);
        plugin.preConnect(right);
        drain();
        assertEquals(Optional.of(a), right.getResult().getServer());
        ServerPreConnectEvent duplicate = new ServerPreConnectEvent(p, a);
        plugin.preConnect(duplicate);
        drain();
        assertTrue(duplicate.getResult().getServer().isEmpty());
    }

    @Test
    void respectsEarlierAccessDenial() throws Exception {
        Player p = player(UUID.randomUUID());
        ServerPreConnectEvent event = new ServerPreConnectEvent(p, a);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        plugin.preConnect(event);
        drain();
        assertEquals(0, visits());
    }

    @Test
    void stalePingAfterRevisionChangeCannotRelease() throws Exception {
        Player p = login();
        login();
        set("revision", 1L);
        ping.complete(mock(ServerPing.class));
        drain();
        assertTrue(attempts.get(p).isEmpty());
    }

    @Test
    void closedGateReturnsResidentsWithoutDoubleCountingStaleBackendSnapshot() throws Exception {
        rule(a, true);
        Player p = resident();
        tick();
        assertEquals(1, visits());
        assertEquals(List.of(waiting), attempts.get(p));
        location(p, waiting);
        post(p);
        tick();
        assertEquals(List.of(waiting), attempts.get(p));
    }

    @Test
    void closedGateCanLeaveExistingResidentsInPlace() throws Exception {
        rule(a, false);
        resident();
        tick();
        assertEquals(0, visits());
    }

    @Test
    void timedOutTransferRemovesReservationAndDisconnects() throws Exception {
        Player p = login();
        login();
        ping.complete(mock(ServerPing.class));
        drain();
        Object visit = ((Map<?, ?>) get("visits")).get(p.getUniqueId());
        Field deadline = visit.getClass().getDeclaredField("deadline");
        deadline.setAccessible(true);
        actor.submit(() -> {
            try {
                deadline.setLong(visit, System.nanoTime() - 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get();
        tick();
        verify(p).disconnect(any());
        assertEquals(1, visits());
    }

    @Test
    void usesActualSelectedInitialDestination() throws Exception {
        Player p = player(UUID.randomUUID());
        login(p, b);
        Player other = login();
        ping.complete(mock(ServerPing.class));
        drain();
        assertTrue(attempts.get(p).isEmpty());
        assertTrue(attempts.get(other).isEmpty());
    }

    @Test
    void failedReleaseRemainsQueuedOnNetworkException() throws Exception {
        Player p = login();
        login();
        ping.complete(mock(ServerPing.class));
        drain();
        connections.get(p).completeExceptionally(new java.io.IOException("backend down"));
        drain();
        assertEquals(2, visits());
        verify(p, never()).disconnect(any());
        tick();
        assertEquals(List.of(a), attempts.get(p));
    }

    @Test
    void backendRejectionRemovesQueueWithoutRetry() throws Exception {
        Player p = login();
        login();
        ping.complete(mock(ServerPing.class));
        drain();
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED);
        when(result.getReasonComponent()).thenReturn(Optional.empty());
        connections.get(p).complete(result);
        drain();
        assertEquals(1, visits());
        tick();
        assertEquals(List.of(a), attempts.get(p));
        verify(p, never()).disconnect(any());
    }

    @Test
    void failedWaitingRoomTransferFailsClosed() throws Exception {
        rule(a, true);
        Player p = resident();
        tick();
        connections.get(p).completeExceptionally(new java.io.IOException("room down"));
        drain();
        verify(p).disconnect(any());
        assertEquals(0, visits());
    }

    @Test
    void synchronousPingFailureDoesNotLeaveProbeInFlight() throws Exception {
        when(a.ping()).thenThrow(new IllegalStateException("failed immediately"));
        login();
        login();
        drain();
        Object backend = ((Map<?, ?>) get("backends")).get("a");
        Field probing = backend.getClass().getDeclaredField("probing");
        probing.setAccessible(true);
        assertFalse(probing.getBoolean(backend));
        assertEquals(2, visits());
    }

    @Test
    void unreadyPluginDoesNotSilentlyBypassGates() throws Exception {
        set("ready", false);
        Player p = player(UUID.randomUUID());
        ServerPreConnectEvent event = new ServerPreConnectEvent(p, a);
        plugin.preConnect(event);
        assertTrue(event.getResult().getServer().isEmpty());
        verify(p).disconnect(any());
    }

    @Test
    void requestingSameBackendDoesNotEvictResident() throws Exception {
        rule(a, false);
        Player p = resident();
        ServerPreConnectEvent event = new ServerPreConnectEvent(p, a);
        plugin.preConnect(event);
        drain();
        assertEquals(Optional.of(a), event.getResult().getServer());
        assertEquals(0, visits());
    }

    @Test
    void overlappingConnectionDoesNotLoseWaitingAssignment() throws Exception {
        Player p = login();
        login();
        ping.complete(mock(ServerPing.class));
        drain();
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS);
        connections.get(p).complete(result);
        drain();
        assertEquals(2, visits());
        tick();
        assertEquals(List.of(a), attempts.get(p));
        verify(p, never()).disconnect(any());
    }
}
