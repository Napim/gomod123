package me.napim.gomod.features;

/**
 * Caps combat click rate (attacks per second).
 * A value <= 0 means disabled (no cap).
 */
public final class CpsLimiter {
    private static double cpsLimit = 0.0D;
    private static long lastAllowedAttackNanos = 0L;
    private static int allowedAttackCount = 0;
    private static int blockedAttackCount = 0;
    private static long lastAllowedAttackAtMs = 0L;
    private static long lastBlockedAttackAtMs = 0L;
    private static long lastBlockedRemainingNanos = 0L;

    private CpsLimiter() {
    }

    public static synchronized void loadSetting() {
        cpsLimit = Math.max(0.0D, ConfigManager.getCpsLimit());
        resetDebugState();
    }

    public static synchronized double getCpsLimit() {
        return cpsLimit;
    }

    public static synchronized boolean isEnabled() {
        return cpsLimit > 0.0D;
    }

    public static synchronized void setCpsLimit(double value) {
        cpsLimit = Math.max(0.0D, value);
        ConfigManager.setCpsLimit(cpsLimit);
        resetDebugState();
    }

    /**
     * Returns true if this attack is allowed by the current CPS cap.
     */
    public static synchronized boolean tryConsumeAttack() {
        if (cpsLimit <= 0.0D) {
            allowedAttackCount++;
            lastAllowedAttackAtMs = System.currentTimeMillis();
            return true;
        }

        long minIntervalNanos = Math.max(1L, (long) (1_000_000_000.0D / cpsLimit));
        long now = System.nanoTime();

        if (lastAllowedAttackNanos != 0L && now - lastAllowedAttackNanos < minIntervalNanos) {
            blockedAttackCount++;
            lastBlockedAttackAtMs = System.currentTimeMillis();
            lastBlockedRemainingNanos = minIntervalNanos - (now - lastAllowedAttackNanos);
            return false;
        }

        lastAllowedAttackNanos = now;
        allowedAttackCount++;
        lastAllowedAttackAtMs = System.currentTimeMillis();
        lastBlockedRemainingNanos = 0L;
        return true;
    }

    public static synchronized int getAllowedAttackCount() {
        return allowedAttackCount;
    }

    public static synchronized int getBlockedAttackCount() {
        return blockedAttackCount;
    }

    public static synchronized long getLastAllowedAttackAtMs() {
        return lastAllowedAttackAtMs;
    }

    public static synchronized long getLastBlockedAttackAtMs() {
        return lastBlockedAttackAtMs;
    }

    public static synchronized double getMinimumIntervalMs() {
        if (cpsLimit <= 0.0D) {
            return 0.0D;
        }
        return 1000.0D / cpsLimit;
    }

    public static synchronized double getLastBlockedRemainingMs() {
        return Math.max(0L, lastBlockedRemainingNanos) / 1_000_000.0D;
    }

    private static void resetDebugState() {
        lastAllowedAttackNanos = 0L;
        allowedAttackCount = 0;
        blockedAttackCount = 0;
        lastAllowedAttackAtMs = 0L;
        lastBlockedAttackAtMs = 0L;
        lastBlockedRemainingNanos = 0L;
    }
}
