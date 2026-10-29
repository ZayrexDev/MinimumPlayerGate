package xyz.zcraft.mc;

import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

/** Load ONLY the packaged plugin and JDK, not Maven's dependencies or Velocity classes. */
class PackagedWaitingServerIT {
    static URLClassLoader loader;
    static Class<?> type;
    Object room;
    InetSocketAddress address;
    @BeforeAll static void load() throws Exception {
        loader = new URLClassLoader(new URL[]{Path.of(System.getProperty("gate.jar")).toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        type = loader.loadClass("xyz.zcraft.mc.embedded.EmbeddedWaitingServer");
    }
    @AfterAll static void unload() throws Exception { loader.close(); }
    void start(String mode, byte[] secret) throws Exception {
        room = type.getConstructor(String.class, byte[].class).newInstance(mode, secret);
        address = (InetSocketAddress) type.getMethod("address").invoke(room);
        assertTrue(address.getAddress().isLoopbackAddress()); assertTrue(address.getPort() > 0);
    }
    @AfterEach void stop() throws Exception { if (room != null) ((AutoCloseable) room).close(); }
    @Test void packagedRuntimeAnswersMultipleProtocolVersionsAndReleasesPort() throws Exception {
        start("NONE", new byte[0]);
        for (int protocol : new int[]{47, 340, 763, 764, 767, 776}) {
            try (Socket socket = socket()) {
                handshake(socket, protocol, 1); send(socket, new byte[]{0});
                DataInputStream response = packet(socket); assertEquals(0, varint(response));
                String json = string(response); assertTrue(json.contains("MinimumPlayerGate")); assertTrue(json.contains(Integer.toString(protocol)));
            }
        }
        ((AutoCloseable) room).close(); room = null;
        try (ServerSocket rebound = new ServerSocket()) { rebound.bind(address); }
    }
    @Test void minecraft1201ReachesLoginSuccessAndPlayPackets() throws Exception {
        start("NONE", new byte[0]);
        try (Socket socket = socket()) {
            handshake(socket, 763, 2); login(socket);
            assertEquals(2, varint(packet(socket))); // Login success, not a disconnect.
            assertTrue(packet(socket).available() > 0); // First play packet contains virtual-world data.
        }
    }
    @Test void modernForwardingAcceptsSignedProxyIdentity() throws Exception {
        byte[] secret = "test-only-forwarding-key".getBytes(StandardCharsets.UTF_8); start("MODERN", secret);
        try (Socket socket = socket()) {
            handshake(socket, 763, 2); login(socket);
            DataInputStream request = packet(socket); assertEquals(4, varint(request));
            int id = varint(request); assertEquals("velocity:player_info", string(request));
            ByteArrayOutputStream data = new ByteArrayOutputStream(); varint(data, 1); string(data, "127.0.0.1");
            DataOutputStream out = new DataOutputStream(data); UUID uuid = UUID.randomUUID(); out.writeLong(uuid.getMostSignificantBits()); out.writeLong(uuid.getLeastSignificantBits());
            string(data, "GateTest"); varint(data, 0);
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            ByteArrayOutputStream reply = new ByteArrayOutputStream(); varint(reply, 2); varint(reply, id); reply.write(1);
            reply.write(mac.doFinal(data.toByteArray())); reply.write(data.toByteArray()); send(socket, reply.toByteArray());
            assertEquals(2, varint(packet(socket))); assertTrue(packet(socket).available() > 0);
        }
    }
    @Test void modernForwardingRejectsInvalidSignature() throws Exception {
        start("MODERN", "real-secret".getBytes(StandardCharsets.UTF_8));
        try (Socket socket = socket()) {
            handshake(socket, 763, 2); login(socket);
            DataInputStream request = packet(socket); assertEquals(4, varint(request)); int id = varint(request);
            ByteArrayOutputStream reply = new ByteArrayOutputStream(); varint(reply, 2); varint(reply, id); reply.write(1); reply.write(new byte[40]);
            send(socket, reply.toByteArray()); assertEquals(0, varint(packet(socket))); // Login disconnect.
        }
    }
    @Test void autoForwardingReadsProxyConfigAndExternalSecret(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path secret = dir.resolve("forwarding.secret"); Files.writeString(secret, "test-secret");
        Path config = dir.resolve("velocity.toml");
        Files.writeString(config, "player-info-forwarding-mode = \"modern\"\nforwarding-secret-file = \"" + secret.toString().replace((char) 92, '/') + "\"\n");
        room = type.getMethod("create", String.class, Path.class, String.class).invoke(null, "AUTO", config, "");
        address = (InetSocketAddress) type.getMethod("address").invoke(room);
        try (Socket socket = socket()) {
            handshake(socket, 763, 2); login(socket); DataInputStream request = packet(socket);
            assertEquals(4, varint(request)); varint(request); assertEquals("velocity:player_info", string(request));
        }
    }
    Socket socket() throws IOException { Socket s = new Socket(); s.connect(address, 5000); s.setSoTimeout(5000); return s; }
    void handshake(Socket s, int protocol, int state) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); varint(b, 0); varint(b, protocol); string(b, "localhost");
        new DataOutputStream(b).writeShort(address.getPort()); varint(b, state); send(s, b.toByteArray());
    }
    void login(Socket s) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); varint(b, 0); string(b, "GateTest"); b.write(0); send(s, b.toByteArray());
    }
    static void send(Socket s, byte[] packet) throws IOException { varint(s.getOutputStream(), packet.length); s.getOutputStream().write(packet); s.getOutputStream().flush(); }
    static DataInputStream packet(Socket s) throws IOException {
        int length = varint(s.getInputStream()); if (length < 0 || length > 2_000_000) throw new IOException("Invalid packet size");
        byte[] bytes = s.getInputStream().readNBytes(length); if (bytes.length != length) throw new EOFException(); return new DataInputStream(new ByteArrayInputStream(bytes));
    }
    static int varint(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) { int b = in.read(); if (b < 0) throw new EOFException(); value |= (b & 127) << shift; if ((b & 128) == 0) return value; }
        throw new IOException("Oversized varint");
    }
    static void varint(OutputStream out, int value) throws IOException { while ((value & ~127) != 0) { out.write((value & 127) | 128); value >>>= 7; } out.write(value); }
    static void string(OutputStream out, String s) throws IOException { byte[] bytes = s.getBytes(StandardCharsets.UTF_8); varint(out, bytes.length); out.write(bytes); }
    static String string(InputStream in) throws IOException { return new String(in.readNBytes(varint(in)), StandardCharsets.UTF_8); }
}
