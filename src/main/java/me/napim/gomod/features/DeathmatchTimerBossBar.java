package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.boss.BossStatus;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.util.Locale;

/**
 * Displays the Blitz Deathmatch {@code Game End: MM:SS} sidebar countdown on Minecraft's
 * Ender Dragon boss bar once remaining time reaches the configured threshold (default 30s).
 */
public final class DeathmatchTimerBossBar {

    public static final int MIN_THRESHOLD_SECONDS = 5;
    public static final int MAX_THRESHOLD_SECONDS = 60;
    public static final int DEFAULT_THRESHOLD_SECONDS = 30;
    private static final long URGENT_DECIMAL_THRESHOLD_MS = 10_000L;
    private static final long SAME_SECOND_LAG_CLAMP_MS = 950L;
    private static final int OWNED_STATUS_BAR_TIME = 5;

    private static final String COLOR_GOLD = "\u00a76";
    private static final String COLOR_YELLOW = "\u00a7e";
    private static final String COLOR_RED = "\u00a7c";
    private static final String COLOR_WHITE = "\u00a7f";
    private static final String FORMAT_BOLD = "\u00a7l";

    interface BossBarSink {
        void apply(String bossName, float healthScale, int statusBarTime, boolean hasColorModifier);
        void clear();
    }

    private static final BossBarSink VANILLA_SINK = new BossBarSink() {
        @Override
        public void apply(String bossName, float healthScale, int statusBarTime, boolean hasColorModifier) {
            BossStatus.bossName = bossName;
            BossStatus.healthScale = healthScale;
            BossStatus.statusBarTime = statusBarTime;
            BossStatus.hasColorModifier = hasColorModifier;
        }

        @Override
        public void clear() {
            BossStatus.statusBarTime = 0;
            BossStatus.bossName = null;
            BossStatus.healthScale = 0.0F;
            BossStatus.hasColorModifier = false;
        }
    };

    private static final Tracker TRACKER = new Tracker();
    private static boolean owningBossBar = false;

    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        updateBossStatus(Minecraft.getMinecraft(), System.currentTimeMillis());
    }

    /** Keeps BossStatus clean on world unload, disconnect, or when overlay rendering is paused. */
    public static void clientTick(Minecraft mc) {
        updateBossStatus(mc, System.currentTimeMillis());
    }

    /** Clears an owned boss bar immediately when the module is disabled or its threshold changes. */
    public static void onSettingChanged() {
        updateBossStatus(Minecraft.getMinecraft(), System.currentTimeMillis());
    }

    static void resetForTests(BossBarSink sink) {
        TRACKER.reset();
        clearOwnedBossBar(sink);
    }

    private static void updateBossStatus(Minecraft mc, long nowMs) {
        if (mc == null || mc.theWorld == null || !ConfigManager.isDeathmatchTimerBossBarEnabled()) {
            TRACKER.reset();
            clearOwnedBossBar(VANILLA_SINK);
            return;
        }
        long parsedMs = GameContext.parseBlitzSidebarGameEndRemainingMs(mc);
        int thresholdSeconds = ConfigManager.getDeathmatchTimerBossBarThresholdSeconds();
        updateFromParsedSidebar(mc.theWorld, true, parsedMs, thresholdSeconds, nowMs, VANILLA_SINK);
    }

    /**
     * Evaluates a parsed sidebar countdown and updates the boss bar sink when active.
     * Package-private for deterministic unit tests without a live Minecraft world.
     */
    static boolean updateFromParsedSidebar(Object world, boolean enabled, long parsedSidebarMs,
                                           int thresholdSeconds, long nowMs, BossBarSink sink) {
        DisplayState state = TRACKER.step(world, enabled, parsedSidebarMs, thresholdSeconds, nowMs);
        if (state == null) {
            clearOwnedBossBar(sink);
            return false;
        }
        sink.apply(state.bossName, state.healthScale, OWNED_STATUS_BAR_TIME, true);
        owningBossBar = true;
        return true;
    }

    private static void clearOwnedBossBar(BossBarSink sink) {
        if (!owningBossBar) {
            return;
        }
        if (sink != null) {
            sink.clear();
        }
        owningBossBar = false;
    }

    public static String formatBossBarText(long remainingMs) {
        long clampedMs = Math.max(0L, remainingMs);
        String timeText = formatCountdownDisplay(clampedMs);
        if (clampedMs <= URGENT_DECIMAL_THRESHOLD_MS) {
            return COLOR_RED + FORMAT_BOLD + "Game End: "
                    + COLOR_WHITE + FORMAT_BOLD + timeText;
        }
        return COLOR_GOLD + "Game End: " + COLOR_YELLOW + timeText;
    }

    public static float computeHealthScale(long remainingMs, int thresholdSeconds) {
        long thresholdMs = Math.max(1L, thresholdSeconds * 1000L);
        float raw = (float) Math.max(0L, remainingMs) / (float) thresholdMs;
        if (raw < 0.0F) {
            return 0.0F;
        }
        return Math.min(1.0F, raw);
    }

    static String formatCountdownDisplay(long remainingMs) {
        if (remainingMs > URGENT_DECIMAL_THRESHOLD_MS) {
            return formatMmSs(remainingMs);
        }
        return formatTenthsSeconds(remainingMs);
    }

    private static String formatMmSs(long millis) {
        long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private static String formatTenthsSeconds(long remainingMs) {
        double secs = Math.max(0.0D, remainingMs / 1000.0D);
        secs = Math.round(secs * 10.0D) / 10.0D;
        return String.format(Locale.ROOT, "%.1fs", secs);
    }

    static final class DisplayState {
        final String bossName;
        final float healthScale;
        final long remainingMs;

        DisplayState(String bossName, float healthScale, long remainingMs) {
            this.bossName = bossName;
            this.healthScale = healthScale;
            this.remainingMs = remainingMs;
        }
    }

    static final class Tracker {
        private Object lastWorld;
        private long lastSidebarMs = -1L;
        private long lastStepWallMs = 0L;

        void reset() {
            lastWorld = null;
            lastSidebarMs = -1L;
            lastStepWallMs = 0L;
        }

        DisplayState step(Object world, boolean enabled, long parsedSidebarMs, int thresholdSeconds, long nowMs) {
            if (world == null || !enabled) {
                reset();
                return null;
            }
            if (world != lastWorld) {
                lastWorld = world;
                lastSidebarMs = -1L;
                lastStepWallMs = 0L;
            }
            if (parsedSidebarMs < 0L) {
                lastSidebarMs = -1L;
                lastStepWallMs = 0L;
                return null;
            }
            if (parsedSidebarMs != lastSidebarMs) {
                lastSidebarMs = parsedSidebarMs;
                lastStepWallMs = nowMs;
            }
            int clampedThreshold = Math.max(MIN_THRESHOLD_SECONDS, Math.min(MAX_THRESHOLD_SECONDS, thresholdSeconds));
            long thresholdMs = clampedThreshold * 1000L;
            if (parsedSidebarMs > thresholdMs) {
                return null;
            }
            long remainingMs;
            if (parsedSidebarMs <= 0L) {
                remainingMs = 0L;
            } else {
                long elapsed = Math.max(0L, nowMs - lastStepWallMs);
                long floorMs = Math.max(0L, parsedSidebarMs - SAME_SECOND_LAG_CLAMP_MS);
                remainingMs = Math.max(floorMs, parsedSidebarMs - elapsed);
            }
            float healthScale = computeHealthScale(remainingMs, clampedThreshold);
            String text = formatBossBarText(remainingMs);
            return new DisplayState(text, healthScale, remainingMs);
        }
    }
}
