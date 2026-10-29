package xyz.zcraft.mc.embedded;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import ua.nanit.limbo.configuration.LimboConfig;
import ua.nanit.limbo.server.*;
import ua.nanit.limbo.server.data.*;
import ua.nanit.limbo.world.DimensionType;

/** Pure JDK boundary: shading never changes a Velocity API method signature. */
public final class EmbeddedWaitingServer implements AutoCloseable {
    private final LimboServer server;

    public EmbeddedWaitingServer(String forwardingMode, byte[] secret) throws Exception {
        InfoForwarding forwarding = new InfoForwarding();
        forwarding.setType(InfoForwarding.Type.valueOf(forwardingMode));
        forwarding.setSecretKey(secret.clone());
        forwarding.setTokens(List.of(new String(secret, java.nio.charset.StandardCharsets.UTF_8)));
        server = new LimboServer(new Settings(forwarding), new CommandHandler<Command>() {
            public Collection<Command> getCommands() { return List.of(); }
            public void register(Command command) { }
            public boolean executeCommand(String command) { return false; }
        }, EmbeddedWaitingServer.class.getClassLoader());
        try { server.start(); }
        catch (Exception | LinkageError e) { server.stop(); throw e; }
    }

    public static EmbeddedWaitingServer create(String mode, java.nio.file.Path velocityConfig,
                                               String secretOverride) throws Exception {
        String secretFile = secretOverride;
        if (mode.equals("AUTO")) {
            var parsed = org.tomlj.Toml.parse(velocityConfig);
            if (parsed.hasErrors()) throw new IllegalArgumentException("Cannot parse " + velocityConfig);
            mode = parsed.getString("player-info-forwarding-mode", () -> "none").toUpperCase(java.util.Locale.ROOT);
            if (secretFile.isBlank()) secretFile = parsed.getString("forwarding-secret-file", () -> "forwarding.secret");
        }
        if (secretFile.isBlank()) secretFile = "forwarding.secret";
        byte[] secret = new byte[0];
        if (mode.equals("MODERN") || mode.equals("BUNGEEGUARD")) {
            secret = java.nio.file.Files.readString(java.nio.file.Path.of(secretFile)).trim()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (secret.length == 0) throw new IllegalArgumentException("Forwarding secret file is empty");
        }
        return new EmbeddedWaitingServer(mode.equals("BUNGEEGUARD") ? "BUNGEE_GUARD" : mode, secret);
    }

    public InetSocketAddress address() { return (InetSocketAddress) server.getBoundChannel().localAddress(); }
    @Override public void close() { server.stop(); }

    private record Settings(InfoForwarding forwarding) implements LimboConfig {
        public SocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        public int getMaxPlayers() { return -1; }
        public PingData getPingData() {
            PingData p = new PingData(); p.setDescription(Component.text("MinimumPlayerGate waiting room"));
            p.setVersion(Component.text("MinimumPlayerGate")); p.setProtocol(-1); return p;
        }
        public DimensionType getDimensionType() { return DimensionType.THE_END; }
        public int getGameMode() { return 3; }
        public boolean isSecureProfile() { return false; }
        public InfoForwarding getInfoForwarding() { return forwarding; }
        public long getReadTimeout() { return 30000; }
        public int getDebugLevel() { return 0; }
        public boolean isLogPlayersIp() { return false; }
        public boolean isUseBrandName() { return true; }
        public boolean isUseJoinMessage() { return false; }
        public boolean isUseBossBar() { return false; }
        public boolean isUseTitle() { return false; }
        public boolean isUsePlayerList() { return false; }
        public boolean isUseHeaderAndFooter() { return false; }
        public Component getBrandName() { return Component.text("MinimumPlayerGate"); }
        public Component getJoinMessage() { return Component.empty(); }
        public BossBar getBossBar() { return null; }
        public Title getTitle() { return null; }
        public String getPlayerListUsername() { return "Waiting"; }
        public Component getPlayerListHeader() { return Component.empty(); }
        public Component getPlayerListFooter() { return Component.empty(); }
        public TransportType getTransportType() { return TransportType.NIO; }
        public int getBossGroupSize() { return 1; }
        public int getWorkerGroupSize() { return 2; }
        public double getInterval() { return 1; }
        public double getMaxPacketRate() { return -1; }
        public double getMaxPacketBytesRate() { return -1; }
    }
}

