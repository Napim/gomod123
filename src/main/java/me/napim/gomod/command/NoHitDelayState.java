/*
 * Decompiled with CFR 0.152.
 */
package me.napim.gomod.command;

public class NoHitDelayState {
    public static volatile int resetCount = 0;
    public static volatile int overwriteCount = 0;
    public static volatile int lastBefore = -1;
    public static volatile int lastAfter = -1;
    public static volatile int hitDelayDetectedCount = 0;
    public static volatile int guiGuardResetCount = 0;
    public static volatile int otherResetCount = 0;
    public static volatile int lingeringCooldownCount = 0;
    public static volatile int lastHitDelayBefore = -1;
    public static volatile long lastHitDelayAtMs = 0L;
    public static volatile int lastLingeringBefore = -1;
    public static volatile long lastLingeringAtMs = 0L;
    public static volatile String lastCategory = "none";
    public static volatile long lastEventAtMs = 0L;
    private static volatile long dedupeLastMs = -1L;
    private static volatile int dedupeLastBefore = Integer.MIN_VALUE;

    public static synchronized void recordReset(int before, int after) {
        long now = System.currentTimeMillis();
        if (now == dedupeLastMs && before == dedupeLastBefore) {
            return;
        }
        dedupeLastMs = now;
        dedupeLastBefore = before;
        ++resetCount;
        lastBefore = before;
        lastAfter = after;
        lastEventAtMs = now;
        if (before >= 1 && before <= 10) {
            ++hitDelayDetectedCount;
            lastHitDelayBefore = before;
            lastHitDelayAtMs = now;
            lastCategory = "hitdelay";
            if (before <= 9) {
                ++lingeringCooldownCount;
                lastLingeringBefore = before;
                lastLingeringAtMs = now;
            }
        } else if (before >= 1000) {
            ++guiGuardResetCount;
            lastCategory = "gui";
        } else {
            ++otherResetCount;
            lastCategory = "other";
        }
    }

    public static synchronized void resetDebugCounters() {
        resetCount = 0;
        overwriteCount = 0;
        lastBefore = -1;
        lastAfter = -1;
        hitDelayDetectedCount = 0;
        guiGuardResetCount = 0;
        otherResetCount = 0;
        lingeringCooldownCount = 0;
        lastHitDelayBefore = -1;
        lastHitDelayAtMs = 0L;
        lastLingeringBefore = -1;
        lastLingeringAtMs = 0L;
        lastCategory = "none";
        lastEventAtMs = 0L;
        dedupeLastMs = -1L;
        dedupeLastBefore = Integer.MIN_VALUE;
    }
}

