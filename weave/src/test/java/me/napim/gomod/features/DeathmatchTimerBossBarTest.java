package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Unit tests for Blitz deathmatch {@code Game End:} sidebar parsing, smooth countdown
 * interpolation, threshold gating, and boss-bar ownership/cleanup.
 */
public final class DeathmatchTimerBossBarTest {

    private static final class RecordingSink implements DeathmatchTimerBossBar.BossBarSink {
        String bossName;
        float healthScale;
        int statusBarTime;
        boolean hasColorModifier;

        @Override
        public void apply(String bossName, float healthScale, int statusBarTime, boolean hasColorModifier) {
            this.bossName = bossName;
            this.healthScale = healthScale;
            this.statusBarTime = statusBarTime;
            this.hasColorModifier = hasColorModifier;
        }

        @Override
        public void clear() {
            this.bossName = null;
            this.healthScale = 0.0F;
            this.statusBarTime = 0;
            this.hasColorModifier = false;
        }
    }

    private RecordingSink sink;

    @Before
    public void setUp() {
        sink = new RecordingSink();
        DeathmatchTimerBossBar.resetForTests(sink);
    }

    @After
    public void tearDown() {
        DeathmatchTimerBossBar.resetForTests(sink);
    }

    @Test
    public void parsesBlitzDeathmatchSidebarAndRejectsNonBlitzOrGameOver() {
        assertEquals(60_000L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("next event:", "game end: 01:00", "kills: 3", "players: 2", "kit: horseman"),
                false));

        assertEquals(30_000L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("game end: 00:30", "kills: 1", "players: 2"),
                false));

        assertEquals(15_000L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("game end: 0:15", "kills: 1", "players: 2"),
                false));

        assertEquals(22_000L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("game end: 22s", "kills: 1", "players: 2"),
                false));

        assertEquals(25_000L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "replay", "replay",
                Arrays.asList("replay from xyz", "game end: 00:25", "kills: 2", "kit: knight"),
                true));

        assertEquals(-1L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("game over!", "game end: 00:05", "kills: 2", "kit: knight"),
                false));

        assertEquals(-1L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "skywars", "skywars",
                Arrays.asList("game end: 00:25", "kills: 2", "players left: 2"),
                false));

        assertEquals(-1L, GameContext.parseBlitzGameEndRemainingMsFromSidebar(
                "blitz lobby", "lobby",
                Collections.singletonList("game end: 00:25"),
                false));
    }

    @Test
    public void activatesAtThresholdSmoothlyCountsDownAndClearsCleanly() {
        Object world = new Object();

        // Above 30s threshold: must stay inactive and leave any existing server boss bar untouched.
        sink.bossName = "Lobby Dragon";
        sink.healthScale = 0.75F;
        sink.statusBarTime = 80;
        assertFalse(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 60_000L, 30, 1_000L, sink));
        assertFalse(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 31_000L, 30, 2_000L, sink));
        assertEquals("Lobby Dragon", sink.bossName);
        assertEquals(80, sink.statusBarTime);

        // At 00:30: takes ownership of the Ender Dragon boss bar.
        assertTrue(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 30_000L, 30, 3_000L, sink));
        assertEquals(1.0F, sink.healthScale, 0.0001F);
        assertTrue(sink.statusBarTime > 0);
        assertTrue(sink.hasColorModifier);
        assertEquals("\u00a76Game End: \u00a7e0:30", sink.bossName);

        // 500ms later while sidebar still says 00:30: bar drains smoothly, text stays 0:30.
        assertTrue(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 30_000L, 30, 3_500L, sink));
        assertEquals(29_500.0F / 30_000.0F, sink.healthScale, 0.001F);
        assertEquals("\u00a76Game End: \u00a7e0:30", sink.bossName);

        // If server lags past 1s on 00:30, clamp before 0:29 rather than drifting ahead.
        assertTrue(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 30_000L, 30, 4_400L, sink));
        assertEquals(29_050.0F / 30_000.0F, sink.healthScale, 0.001F);
        assertEquals("\u00a76Game End: \u00a7e0:30", sink.bossName);

        // At 10s or below: urgent bold formatting with tenths precision.
        assertTrue(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 10_000L, 30, 23_000L, sink));
        assertTrue(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 10_000L, 30, 23_500L, sink));
        assertEquals("\u00a7c\u00a7lGame End: \u00a7f\u00a7l9.5s", sink.bossName);
        assertEquals(9_500.0F / 30_000.0F, sink.healthScale, 0.001F);

        // At 00:00: bar reaches empty and displays 0.0s.
        assertTrue(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 0L, 30, 33_000L, sink));
        assertEquals(0.0F, sink.healthScale, 0.0001F);
        assertEquals("\u00a7c\u00a7lGame End: \u00a7f\u00a7l0.0s", sink.bossName);

        // When game ends / sidebar disappears: owned boss bar is cleared immediately.
        assertFalse(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, -1L, 30, 33_500L, sink));
        assertNull(sink.bossName);
        assertEquals(0, sink.statusBarTime);
        assertEquals(0.0F, sink.healthScale, 0.0001F);
        assertFalse(sink.hasColorModifier);
    }

    @Test
    public void respectsCustomThresholdAndDisablingMidCountdown() {
        Object world = new Object();
        assertFalse(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 20_000L, 15, 1_000L, sink));
        assertTrue(DeathmatchTimerBossBar.updateFromParsedSidebar(world, true, 15_000L, 15, 6_000L, sink));
        assertEquals(1.0F, sink.healthScale, 0.0001F);

        // Turning the module off clears our owned boss bar immediately.
        assertFalse(DeathmatchTimerBossBar.updateFromParsedSidebar(world, false, 14_000L, 15, 7_000L, sink));
        assertNull(sink.bossName);
        assertEquals(0, sink.statusBarTime);
    }
}
