package me.napim.gomod.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.napim.gomod.features.GoStatsBlitzClient;
import org.junit.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.Assert.*;

/** Verifies interval mapping, lane packing, window filtering, and hit testing for ActivityTimelineLayout. */
public final class ActivityTimelineLayoutTest {

    private static final long NOW_MS = Instant.parse("2026-11-23T18:00:00Z").toEpochMilli();

    private static JsonObject kitEntry(String name, String olderIso, String latestIso, long deltaGames) {
        JsonObject deltas = new JsonObject();
        deltas.addProperty("games", deltaGames);
        deltas.addProperty("kills", deltaGames * 3);
        deltas.addProperty("wins", Math.max(0, deltaGames / 2));

        JsonObject stats = new JsonObject();
        stats.addProperty("owned", true);
        stats.addProperty("level", 10);
        stats.addProperty("prestige", 0);
        stats.addProperty("kills", 1000);
        stats.addProperty("deaths", 100);
        stats.addProperty("wins", 50);
        stats.addProperty("games", 200);
        stats.addProperty("kd", 10.0);
        stats.addProperty("wlr", 0.5);
        stats.addProperty("last_activity_at", Instant.parse(latestIso).toEpochMilli() / 1000.0);
        stats.addProperty("activity_after", olderIso);
        stats.addProperty("activity_before", latestIso);
        stats.add("activity_deltas", deltas);

        JsonObject entry = new JsonObject();
        entry.addProperty("kit", name);
        entry.add("stats", stats);
        return entry;
    }

    private static GoStatsBlitzClient.Snapshot parse(JsonArray kits) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("username", "TimelineUser");
        root.addProperty("uuid", "0123456789abcdef0123456789abcdef");
        root.add("stats", new JsonObject());
        root.add("kits", kits);
        Method m = GoStatsBlitzClient.class.getDeclaredMethod("parseSnapshot", JsonObject.class, String.class);
        m.setAccessible(true);
        return (GoStatsBlitzClient.Snapshot) m.invoke(null, root, "0123456789abcdef0123456789abcdef");
    }

    @Test
    public void mapsIntervalsFiltersOlderKitsAndStaggersOverlaps() throws Exception {
        JsonArray kits = new JsonArray();
        // Two kits played in the same 20-minute window today -> should stagger into lane 0 and lane 1
        kits.add(kitEntry("Archer", "2026-11-23T17:20:00Z", "2026-11-23T17:40:00Z", 3));
        kits.add(kitEntry("Scout", "2026-11-23T17:20:00Z", "2026-11-23T17:40:00Z", 1));
        // One kit with a wide 6-day window inside 30d (but outside 7d)
        kits.add(kitEntry("Warrior", "2026-11-05T12:00:00Z", "2026-11-11T12:00:00Z", 12));
        // One kit older than 30d -> should be excluded and counted in olderOutsideCount
        kits.add(kitEntry("Baker", "2026-10-01T12:00:00Z", "2026-10-05T12:00:00Z", 5));

        GoStatsBlitzClient.Snapshot snap = parse(kits);

        ActivityTimelineLayout.Result month = ActivityTimelineLayout.build(snap.kits, NOW_MS, 30, 10, 300);
        assertEquals(3, month.entries.size());
        assertEquals(1, month.olderOutsideCount);

        // Warrior (leftmost) spans 6 days out of 30d (~60px)
        ActivityTimelineLayout.Entry warrior = month.entries.get(0);
        assertEquals("Warrior", warrior.kit.name);
        assertTrue("Expected wide bracket for 6-day span, got " + (warrior.spanRight - warrior.spanLeft),
                warrior.spanRight - warrior.spanLeft >= 50);

        // Archer and Scout share the same timestamp -> placed in different lanes
        ActivityTimelineLayout.Entry firstToday = month.entries.get(1);
        ActivityTimelineLayout.Entry secondToday = month.entries.get(2);
        assertNotEquals(firstToday.lane, secondToday.lane);

        // Hit-testing on secondToday's chip returns secondToday
        int laneBaseY = 100;
        int lanePitch = 16;
        ActivityTimelineLayout.Entry hit = ActivityTimelineLayout.hitTest(
                month,
                secondToday.chipX + 5,
                laneBaseY + secondToday.lane * lanePitch + 5,
                laneBaseY,
                lanePitch);
        assertNotNull(hit);
        assertEquals(secondToday.kit.name, hit.kit.name);

        // Switching to 7d excludes Warrior and Baker (2 older)
        ActivityTimelineLayout.Result week = ActivityTimelineLayout.build(snap.kits, NOW_MS, 7, 10, 300);
        assertEquals(2, week.entries.size());
        assertEquals(2, week.olderOutsideCount);
    }
}
