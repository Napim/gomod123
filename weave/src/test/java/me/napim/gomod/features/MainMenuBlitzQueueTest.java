package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MainMenuBlitzQueueTest {
    @Before
    public void setUp() {
        MainMenuBlitzQueue.resetForTest();
    }

    @After
    public void tearDown() {
        MainMenuBlitzQueue.resetForTest();
    }

    @Test
    public void parsesSoloAndTeamsCountsFromGameCountsJson() {
        JsonObject payload = new JsonParser().parse(
                "{\"success\":true,\"games\":{\"SURVIVAL_GAMES\":{\"players\":21,"
                        + "\"modes\":{\"solo_normal\":10,\"teams_normal\":4}}}}"
        ).getAsJsonObject();

        assertArrayEquals(new int[] { 10, 4 }, MainMenuBlitzQueue.parseBlitzModeCounts(payload));
    }

    @Test
    public void defaultsMissingModeKeyToZeroWhenSurvivalGamesPresent() {
        JsonObject payload = new JsonParser().parse(
                "{\"success\":true,\"games\":{\"SURVIVAL_GAMES\":{\"players\":10,"
                        + "\"modes\":{\"solo_normal\":10}}}}"
        ).getAsJsonObject();

        assertArrayEquals(new int[] { 10, 0 }, MainMenuBlitzQueue.parseBlitzModeCounts(payload));
    }

    @Test
    public void rejectsUnsuccessfulOrMissingSurvivalGamesPayload() {
        JsonObject failed = new JsonParser().parse("{\"success\":false}").getAsJsonObject();
        assertNull(MainMenuBlitzQueue.parseBlitzModeCounts(failed));

        JsonObject missingGame = new JsonParser().parse("{\"success\":true,\"games\":{}}").getAsJsonObject();
        assertNull(MainMenuBlitzQueue.parseBlitzModeCounts(missingGame));
    }

    @Test
    public void formatsButtonLabelsForLoadedLoadingAndUnknownStates() {
        assertEquals("Queue Solo (10)", MainMenuBlitzQueue.formatButtonLabel("Queue Solo", 10, false));
        assertEquals("Queue Teams (4)", MainMenuBlitzQueue.formatButtonLabel("Queue Teams", 4, false));
        assertEquals("Queue Solo (0)", MainMenuBlitzQueue.formatButtonLabel("Queue Solo", 0, true));
        assertEquals("Queue Solo", MainMenuBlitzQueue.formatButtonLabel("Queue Solo", -1, true));
        assertEquals("Queue Teams", MainMenuBlitzQueue.formatButtonLabel("Queue Teams", -1, false));
    }

    @Test
    public void parsesModesAndHypixelHosts() {
        assertEquals(MainMenuBlitzQueue.Mode.SOLO, MainMenuBlitzQueue.Mode.fromName("solo"));
        assertEquals(MainMenuBlitzQueue.Mode.TEAMS, MainMenuBlitzQueue.Mode.fromName("teams"));
        assertEquals(MainMenuBlitzQueue.Mode.TEAMS, MainMenuBlitzQueue.Mode.fromName("team"));
        assertEquals("/play blitz_solo_normal", MainMenuBlitzQueue.Mode.SOLO.command);
        assertEquals("/play blitz_teams_normal", MainMenuBlitzQueue.Mode.TEAMS.command);
        assertNull(MainMenuBlitzQueue.Mode.fromName("bedwars"));

        assertTrue(MainMenuBlitzQueue.isMainHypixelAddress("mc.hypixel.net"));
        assertTrue(MainMenuBlitzQueue.isMainHypixelAddress("play.hypixel.net:25565"));
        assertTrue(MainMenuBlitzQueue.isMainHypixelAddress("proxy.hypixel.net"));
        assertTrue(MainMenuBlitzQueue.isMainHypixelAddress("hypixel.net"));
        assertFalse(MainMenuBlitzQueue.isMainHypixelAddress("forums.hypixel.net"));
        assertFalse(MainMenuBlitzQueue.isMainHypixelAddress("verify.hypixel.net"));
        assertFalse(MainMenuBlitzQueue.isMainHypixelAddress("alpha.hypixel.net"));
        assertFalse(MainMenuBlitzQueue.isMainHypixelAddress("stuck.hypixel.net"));
        assertFalse(MainMenuBlitzQueue.isMainHypixelAddress("example.com"));
    }

    @Test
    public void exportsUiSnapshotJsonWithExpectedFields() {
        JsonObject obj = new JsonParser().parse(MainMenuBlitzQueue.getUiSnapshotJson()).getAsJsonObject();
        assertTrue(obj.has("enabled"));
        assertEquals(-1, obj.get("solo").getAsInt());
        assertEquals(-1, obj.get("teams").getAsInt());
        assertEquals("Queue Solo", obj.get("soloLabel").getAsString());
        assertEquals("Queue Teams", obj.get("teamsLabel").getAsString());
    }
}
