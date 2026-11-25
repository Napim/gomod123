package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

public class GoStatsBlitzProfileExtrasTest {

    @Test
    public void parsesAuraToggleSettingsPlayerInfoTournamentsAndKitExtras() throws Exception {
        String json = "{"
                + "\"success\":true,"
                + "\"player\":{"
                + "  \"displayname\":\"TestPlayer\","
                + "  \"userLanguage\":\"ENGLISH\","
                + "  \"clock\":true,"
                + "  \"questSettings\":{\"autoActivate\":true},"
                + "  \"socialMedia\":{\"links\":{\"DISCORD\":\"test#1234\",\"YOUTUBE\":\"https://youtube.com/@test\"}},"
                + "  \"parkourCompletions\":{\"BlitzLobby\":[{\"timeStart\":1,\"timeTook\":19800},{\"timeStart\":2,\"timeTook\":4501}]},"
                + "  \"stats\":{\"HungerGames\":{"
                + "    \"kills\":5000,\"deaths\":1000,\"wins\":400,\"games_played\":1400,\"coins\":123456,"
                + "    \"aura\":\"DRAGON_EGG_PARTICLE\",\"auratoggle\":false,"
                + "    \"autoarmor\":true,\"togglekillcounter\":3,\"alternative_kill_message_enabled\":true,\"disableprestigefinisher\":true,"
                + "    \"privategames\":{\"enable_max_kits_and_kill_effects\":true,\"extra_blitz_stars\":\"5 Stars\",\"low_gravity\":false},"
                + "    \"tourney_blitz_duo_0_kills\":14,\"tourney_blitz_duo_0_deaths\":5,\"tourney_blitz_duo_0_wins_teams\":3,\"tourney_blitz_duo_0_games_played\":8,\"tourney_blitz_duo_0_kills_scout\":9,"
                + "    \"tourney_blitz_duo_1_kills\":22,\"tourney_blitz_duo_1_deaths\":7,\"tourney_blitz_duo_1_wins_teams\":2,\"tourney_blitz_duo_1_games_played\":9,\"tourney_blitz_duo_1_kills_archer\":11,\"tourney_blitz_duo_1_kills_teams_normal\":22,"
                + "    \"tourney_blitz_duo_2_kills\":96,\"tourney_blitz_duo_2_deaths\":51,\"tourney_blitz_duo_2_wins_teams\":24,\"tourney_blitz_duo_2_games_played\":69,\"tourney_blitz_duo_2_kills_warlock\":58,\"tourney_blitz_duo_2_blitz_uses\":12,"
                + "    \"tnt_placed_creepertamer\":408,\"explosive_kills_creepertamer\":12,\"fall_kills_rogue\":51,\"eggs_collected_farmer\":321,\"rails_placed_hype train\":74"
                + "  }}"
                + "}}";
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        GoStatsBlitzClient.Snapshot snap = GoStatsBlitzClient.parseHypixelPlayerForTest("0123456789abcdef0123456789abcdef", root);

        // Aura keeps existing behavior: raw Hypixel auratoggle is ignored so auraEnabled stays null
        assertEquals("DRAGON_EGG_PARTICLE", snap.cosmetics.aura);
        assertNull(snap.cosmetics.auraEnabled);

        // Blitz settings
        assertTrue(snap.blitzSettings.isAvailable());
        assertEquals(Boolean.TRUE, snap.blitzSettings.autoArmor);
        assertEquals(Integer.valueOf(3), snap.blitzSettings.toggleKillCounter);
        assertEquals(Boolean.TRUE, snap.blitzSettings.altKillMessages);
        assertEquals(Boolean.TRUE, snap.blitzSettings.altKillMessagesUnlocked);
        assertEquals(Boolean.TRUE, snap.blitzSettings.disablePrestigeFinisher);
        assertEquals("true", snap.blitzSettings.privateGames.get("enable_max_kits_and_kill_effects"));
        assertEquals("5 Stars", snap.blitzSettings.privateGames.get("extra_blitz_stars"));

        // Player info
        assertTrue(snap.playerInfo.isAvailable());
        assertEquals("ENGLISH", snap.playerInfo.userLanguage);
        assertEquals(Boolean.TRUE, snap.playerInfo.lobbyPlayersVisible);
        assertEquals(Boolean.TRUE, snap.playerInfo.autoActivateQuests);
        assertEquals(4501L, snap.playerInfo.blitzParkourBestMs);
        assertEquals("test#1234", snap.playerInfo.socialLinks.get("DISCORD"));
        assertEquals("https://youtube.com/@test", snap.playerInfo.socialLinks.get("YOUTUBE"));

        // Tournaments (Hypixel tourney_blitz_duo_<n> is 0-indexed: 0 = Jan 2019, 1 = Oct 2020, 2 = Mar 2023)
        assertEquals(3, snap.tournaments.size());
        assertEquals("Blitz Duo", snap.tournaments.get(0).name);
        assertEquals("Jan 4-6, 2019", snap.tournaments.get(0).date());
        assertEquals(14L, snap.tournaments.get(0).kills);
        assertEquals(3.0 / 5.0, snap.tournaments.get(0).wlr(), 0.0001);
        assertEquals("Scout", snap.tournaments.get(0).topKit);

        assertEquals("Blitz Duo", snap.tournaments.get(1).name);
        assertEquals("Oct 16-18, 2020", snap.tournaments.get(1).date());
        assertEquals(22L, snap.tournaments.get(1).kills);
        assertEquals(2.0 / 7.0, snap.tournaments.get(1).wlr(), 0.0001);
        assertEquals("Archer", snap.tournaments.get(1).topKit);

        assertEquals("Blitz Duo", snap.tournaments.get(2).name);
        assertEquals("Mar 24-26, 2023", snap.tournaments.get(2).date());
        assertEquals(96L, snap.tournaments.get(2).kills);
        assertEquals(24.0 / 51.0, snap.tournaments.get(2).wlr(), 0.0001);
        assertEquals(12L, snap.tournaments.get(2).blitzUses);
        assertEquals("Warlock", snap.tournaments.get(2).topKit);

        // Kit-specific stats
        assertEquals(5, snap.kitExtraStats.size());
        assertEquals("Creepertamer", snap.kitExtraStats.get(0).kit);
        assertEquals("TNT Placed", snap.kitExtraStats.get(0).label);
        assertEquals(408L, snap.kitExtraStats.get(0).value);
    }

    @Test
    public void leavesAuraEnabledNullWhenOnlyAuraPresentOnRawHypixelPayload() throws Exception {
        String json = "{\"success\":true,\"player\":{\"displayname\":\"AuraOn\","
                + "\"stats\":{\"HungerGames\":{\"kills\":10,\"aura\":\"DUST_PARTICLE\"}}}}";
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        GoStatsBlitzClient.Snapshot snap = GoStatsBlitzClient.parseHypixelPlayerForTest("0123456789abcdef0123456789abcdef", root);
        assertEquals("DUST_PARTICLE", snap.cosmetics.aura);
        assertNull(snap.cosmetics.auraEnabled);
        assertEquals(Integer.valueOf(1), snap.blitzSettings.toggleKillCounter);
        assertEquals(Boolean.FALSE, snap.blitzSettings.altKillMessagesUnlocked);
        assertNull(snap.blitzSettings.altKillMessages);

        java.util.List<GoStatsBlitzClient.TournamentStats> all =
                GoStatsBlitzClient.TournamentStats.withKnownPlaceholders(snap.tournaments);
        assertEquals(3, all.size());
        assertEquals("Jan 4-6, 2019", all.get(0).date());
        assertFalse(all.get(0).played());
        assertEquals("Oct 16-18, 2020", all.get(1).date());
        assertFalse(all.get(1).played());
        assertEquals("Mar 24-26, 2023", all.get(2).date());
        assertFalse(all.get(2).played());
    }
}
