package xyz.zcraft.mc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GateStateTest {
    final GateConfig.Rule rule = new GateConfig.Rule(true, 4, 2, 10, true);
    @Test void opensAtExactThresholdAndClosesStrictlyBelowAfterGrace() {
        GateState s = new GateState();
        assertFalse(s.evaluate(rule, 3, false, 0));
        assertTrue(s.evaluate(rule, 4, false, 0));
        assertTrue(s.open);
        assertFalse(s.evaluate(rule, 2, false, 1));
        assertFalse(s.evaluate(rule, 1, false, 2));
        assertFalse(s.evaluate(rule, 1, false, 9_000_000_002L));
        assertTrue(s.evaluate(rule, 1, false, 10_000_000_002L));
        assertFalse(s.open);
        assertFalse(s.evaluate(rule, 3, false, 11_000_000_002L));
        assertTrue(s.evaluate(rule, 4, false, 12_000_000_002L));
    }
    @Test void recoveryRestartsGrace() {
        GateState s = new GateState(); s.open = true;
        s.evaluate(rule, 1, false, 0);
        s.evaluate(rule, 2, false, 5_000_000_000L);
        s.evaluate(rule, 1, false, 11_000_000_000L);
        assertTrue(s.open);
        s.evaluate(rule, 1, false, 21_000_000_000L); assertFalse(s.open);
    }
    @Test void transfersDoNotCauseFalseClosure() {
        GateState s = new GateState(); s.open = true;
        s.evaluate(rule, 0, true, 0);
        s.evaluate(rule, 0, true, 60_000_000_000L); assertTrue(s.open);
        s.evaluate(rule, 0, false, 61_000_000_000L); assertTrue(s.open);
        s.evaluate(rule, 0, false, 71_000_000_000L); assertFalse(s.open);
    }
    @Test void zeroDelayAndIndependentStates() {
        GateState a = new GateState(), b = new GateState();
        a.evaluate(rule, 4, false, 0); assertTrue(a.open); assertFalse(b.open);
        a.evaluate(new GateConfig.Rule(true, 4, 2, 0, false), 1, false, 0); assertFalse(a.open);
    }
    @Test void disabledAlwaysOpens() {
        GateState s = new GateState(); s.evaluate(new GateConfig.Rule(false, 4, 2, 0, true), 0, false, 0); assertTrue(s.open);
    }
    @Test void validatesThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new GateConfig.Rule(true, 0, 1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new GateConfig.Rule(true, 4, 5, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new GateConfig.Rule(true, 4, 1, -1, true));
    }
    @Test void generatesAndPersistsAllRegisteredServers(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.properties");
        GateConfig c = GateConfig.load(file, List.of("forge", "game.two"));
        assertEquals(2, c.servers().size()); assertFalse(c.servers().get("forge").enabled());
        assertEquals(c, GateConfig.load(file, List.of("forge", "game.two")));
        GateConfig next = GateConfig.load(file, List.of("forge", "game.two", "new"));
        assertEquals(3, next.servers().size());
    }
    @Test void badReloadDoesNotOverwriteFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.properties"); GateConfig.load(file, List.of("forge"));
        String bad = Files.readString(file).replace("minimum-players=4", "minimum-players=0");
        Files.writeString(file, bad);
        assertThrows(IllegalArgumentException.class, () -> GateConfig.load(file, List.of("forge", "new")));
        assertEquals(bad, Files.readString(file));
    }
    @Test void rejectsTypos(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.properties"); GateConfig.load(file, List.of("forge"));
        Files.writeString(file, "\nserver.forge.enabeld=true\n", StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> GateConfig.load(file, List.of("forge")));
    }
}
