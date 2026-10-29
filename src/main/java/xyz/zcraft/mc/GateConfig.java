package xyz.zcraft.mc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Immutable, strictly validated configuration; a bad reload never changes live state.
 */
record GateConfig(Map<String, Rule> servers, int transferTimeoutSeconds, int retrySeconds, LimboSettings limbo) {
    static GateConfig load(Path file, Collection<String> names) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }
        boolean changed = false;
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("limbo.forwarding-mode", "AUTO");
        defaults.put("limbo.velocity-config", "velocity.toml");
        defaults.put("limbo.forwarding-secret-file", "");
        defaults.put("transfer-timeout-seconds", "30");
        defaults.put("backend-retry-seconds", "10");
        for (String name : new TreeSet<>(names)) {
            String prefix = "server." + name + ".";
            defaults.put(prefix + "enabled", "false");
            defaults.put(prefix + "minimum-players", "4");
            defaults.put(prefix + "close-below", "2");
            defaults.put(prefix + "close-delay-seconds", "30");
            defaults.put(prefix + "return-to-waiting", "true");
        }
        for (var e : defaults.entrySet()) {
            if (!p.containsKey(e.getKey())) {
                p.setProperty(e.getKey(), e.getValue());
                changed = true;
            }
        }
        // Retain entries for temporarily unregistered servers, but validate their fields too.
        Set<String> configured = new TreeSet<>(names);
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("server.") && key.endsWith(".enabled")) {
                configured.add(key.substring(7, key.length() - 8));
            }
        }
        Map<String, Rule> rules = new LinkedHashMap<>();
        Set<String> valid = new HashSet<>(List.of("transfer-timeout-seconds", "backend-retry-seconds", "limbo.forwarding-mode", "limbo.velocity-config", "limbo.forwarding-secret-file"));
        for (String name : configured) {
            String k = "server." + name + ".";
            for (String field : List.of("enabled", "minimum-players", "close-below", "close-delay-seconds", "return-to-waiting"))
                valid.add(k + field);
            rules.put(name, new Rule(bool(p, k + "enabled"), number(p, k + "minimum-players"),
                    number(p, k + "close-below"), number(p, k + "close-delay-seconds"), bool(p, k + "return-to-waiting")));
        }
        for (String key : p.stringPropertyNames())
            if (!valid.contains(key)) throw new IllegalArgumentException("Unknown setting: " + key);
        int timeout = number(p, "transfer-timeout-seconds"), retry = number(p, "backend-retry-seconds");
        if (timeout < 5 || timeout > 300 || retry < 1 || retry > 3600)
            throw new IllegalArgumentException("Timeout must be 5..300; retry must be 1..3600 seconds");
        String mode = p.getProperty("limbo.forwarding-mode").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "NONE", "LEGACY", "MODERN", "BUNGEEGUARD").contains(mode))
            throw new IllegalArgumentException("Invalid limbo.forwarding-mode");
        LimboSettings limbo = new LimboSettings(mode, p.getProperty("limbo.velocity-config").trim(),
                p.getProperty("limbo.forwarding-secret-file").trim());
        GateConfig result = new GateConfig(Map.copyOf(rules), timeout, retry, limbo);
        if (changed) {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "gate-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    p.store(writer, "MinimumPlayerGate: exact Velocity server names. Opening >= minimum; closing < close-below.");
                }
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return result;
    }

    private static boolean bool(Properties p, String key) {
        String value = p.getProperty(key, "").trim();
        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }

    private static int number(Properties p, String key) {
        try {
            return Integer.parseInt(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    record LimboSettings(String mode, String velocityConfig, String secretFile) { }

    record Rule(boolean enabled, int minimumPlayers, int closeBelow,
                int closeDelaySeconds, boolean returnToWaiting) {
        Rule {
            if (closeBelow < 1 || closeBelow > minimumPlayers
                    || closeDelaySeconds < 0 || closeDelaySeconds > 86400) {
                throw new IllegalArgumentException("Require minimum-players >= close-below >= 1 and close-delay-seconds 0..86400");
            }
        }
    }
}
