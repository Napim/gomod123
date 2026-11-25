package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Verifies recency-first and gain-first Activity kit ordering. */
public final class GoStatsActivitySortingTest {

    private static JsonObject kitEntry(String name, long lifetimeKills, double lastActivityEpochSec,
                                       long deltaGames, long deltaKills, long deltaWins) {
        JsonObject deltas = new JsonObject();
        deltas.addProperty("games", deltaGames);
        deltas.addProperty("kills", deltaKills);
        deltas.addProperty("wins", deltaWins);

        JsonObject stats = new JsonObject();
        stats.addProperty("owned", true);
        stats.addProperty("level", 10);
        stats.addProperty("prestige", 0);
        stats.addProperty("kills", lifetimeKills);
        stats.addProperty("deaths", 100);
        stats.addProperty("wins", 50);
        stats.addProperty("games", 200);
        stats.addProperty("kd", 2.0);
        stats.addProperty("wlr", 0.5);
        stats.addProperty("last_activity_at", lastActivityEpochSec);
        stats.addProperty("activity_after", "2026-11-23T17:00:00Z");
        stats.addProperty("activity_before", "2026-11-23T17:30:00Z");
        stats.add("activity_deltas", deltas);

        JsonObject entry = new JsonObject();
        entry.addProperty("kit", name);
        entry.add("stats", stats);
        return entry;
    }

    private static GoStatsBlitzClient.Snapshot parse(JsonArray kits) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("username", "TestPlayer");
        root.addProperty("uuid", "0123456789abcdef0123456789abcdef");
        root.add("stats", new JsonObject());
        root.add("kits", kits);
        Method m = GoStatsBlitzClient.class.getDeclaredMethod("parseSnapshot", JsonObject.class, String.class);
        m.setAccessible(true);
        return (GoStatsBlitzClient.Snapshot) m.invoke(null, root, "0123456789abcdef0123456789abcdef");
    }

    @Test
    public void recencyFirstOrdersByLastActivityThenGains() throws Exception {
        JsonArray kits = new JsonArray();
        // Played 3 weeks ago with huge volume
        kits.add(kitEntry("Warrior", 25000, 1793000000.0, 40, 120, 18));
        // Played 10 minutes ago (same window as Archer, fewer games than Archer)
        kits.add(kitEntry("Scout", 5000, 1795450000.0, 1, 3, 0));
        // Played 10 minutes ago (same window as Scout, more games)
        kits.add(kitEntry("Archer", 3000, 1795450000.0, 4, 15, 2));
        // Played 2 days ago
        kits.add(kitEntry("Meatmaster", 8000, 1795280000.0, 10, 35, 5));

        GoStatsBlitzClient.Snapshot snap = parse(kits);
        List<GoStatsBlitzClient.Kit> byRecency = GoStatsBlitzClient.sortedKits(snap.kits, true, true);

        assertEquals("Archer", byRecency.get(0).name);
        assertEquals("Scout", byRecency.get(1).name);
        assertEquals("Meatmaster", byRecency.get(2).name);
        assertEquals("Warrior", byRecency.get(3).name);

        // Default sortedKits(kits, true) matches recency-first
        List<GoStatsBlitzClient.Kit> defaultRecent = GoStatsBlitzClient.sortedKits(snap.kits, true);
        assertEquals("Archer", defaultRecent.get(0).name);
        assertEquals("Warrior", defaultRecent.get(3).name);
    }

    @Test
    public void gainedFirstOrdersByGamesThenKillsThenRecency() throws Exception {
        JsonArray kits = new JsonArray();
        kits.add(kitEntry("Warrior", 25000, 1793000000.0, 40, 120, 18));
        kits.add(kitEntry("Scout", 5000, 1795450000.0, 1, 3, 0));
        kits.add(kitEntry("Archer", 3000, 1795450000.0, 4, 15, 2));
        kits.add(kitEntry("Meatmaster", 8000, 1795280000.0, 10, 35, 5));

        GoStatsBlitzClient.Snapshot snap = parse(kits);
        List<GoStatsBlitzClient.Kit> byGained = GoStatsBlitzClient.sortedKits(snap.kits, true, false);

        assertEquals("Warrior", byGained.get(0).name);
        assertEquals("Meatmaster", byGained.get(1).name);
        assertEquals("Archer", byGained.get(2).name);
        assertEquals("Scout", byGained.get(3).name);
    }

    @Test
    public void lifetimeModeIgnoresActivityAndSortsByLifetimeKills() throws Exception {
        JsonArray kits = new JsonArray();
        kits.add(kitEntry("Archer", 3000, 1795450000.0, 4, 15, 2));
        kits.add(kitEntry("Warrior", 25000, 1793000000.0, 40, 120, 18));
        kits.add(kitEntry("Meatmaster", 8000, 1795280000.0, 10, 35, 5));

        GoStatsBlitzClient.Snapshot snap = parse(kits);
        List<GoStatsBlitzClient.Kit> lifetime = GoStatsBlitzClient.sortedKits(snap.kits, false, true);

        assertEquals("Warrior", lifetime.get(0).name);
        assertEquals("Meatmaster", lifetime.get(1).name);
        assertEquals("Archer", lifetime.get(2).name);
    }
}
