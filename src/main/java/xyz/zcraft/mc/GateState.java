package xyz.zcraft.mc;

/**
 * Pure threshold state machine, evaluated using monotonic time.
 */
final class GateState {
    boolean open;
    private long lowSince = Long.MIN_VALUE;

    boolean evaluate(GateConfig.Rule rule, int readyPopulation, boolean transferring, long now) {
        boolean before = open;
        if (!rule.enabled()) {
            open = true;
            lowSince = Long.MIN_VALUE;
        } else if (!open) {
            lowSince = Long.MIN_VALUE;
            if (readyPopulation >= rule.minimumPlayers()) open = true;
        } else if (transferring || readyPopulation >= rule.closeBelow()) {
            lowSince = Long.MIN_VALUE;
        } else {
            if (lowSince == Long.MIN_VALUE) lowSince = now;
            if (now - lowSince >= rule.closeDelaySeconds() * 1_000_000_000L) {
                open = false;
                lowSince = Long.MIN_VALUE;
            }
        }
        return before != open;
    }

    void resetGrace() {
        lowSince = Long.MIN_VALUE;
    }
}

