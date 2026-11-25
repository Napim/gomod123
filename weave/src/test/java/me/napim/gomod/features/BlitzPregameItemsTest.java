package me.napim.gomod.features;

import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BlitzPregameItemsTest {

    @Test
    public void parsesSurvivalGamesCountsLikeQStatus() {
        String json = "{\"success\":true,\"games\":{\"SURVIVAL_GAMES\":{\"players\":42,\"modes\":{\"solo_normal\":19,\"teams_normal\":14}}}}";
        BlitzPregamePlayerToggle.QueueStatusSnapshot snap =
                BlitzPregamePlayerToggle.parseQueueStatusResponse(new JsonParser().parse(json).getAsJsonObject(), 12345L);
        assertNotNull(snap);
        assertEquals(42, snap.totalPlayers);
        assertEquals(19, snap.soloPlayers);
        assertEquals(14, snap.teamsPlayers);
        assertEquals(9, snap.lobbyPlayers);
        assertEquals(12345L, snap.fetchedAtMs);

        String line = BlitzPregamePlayerToggle.formatQueueStatusHudLine(snap);
        assertTrue(line.contains("Solo:"));
        assertTrue(line.contains("19"));
        assertTrue(line.contains("Teams:"));
        assertTrue(line.contains("14"));
        assertTrue(line.contains("Lobby:"));
        assertTrue(line.contains("9"));
    }

    @Test
    public void clampsNegativeLobbyCountAndRejectsFailedResponse() {
        String negativeLobby = "{\"success\":true,\"games\":{\"SURVIVAL_GAMES\":{\"players\":10,\"modes\":{\"solo_normal\":7,\"teams_normal\":6}}}}";
        BlitzPregamePlayerToggle.QueueStatusSnapshot snap =
                BlitzPregamePlayerToggle.parseQueueStatusResponse(new JsonParser().parse(negativeLobby).getAsJsonObject(), 1L);
        assertNotNull(snap);
        assertEquals(0, snap.lobbyPlayers);

        String failed = "{\"success\":false}";
        assertNull(BlitzPregamePlayerToggle.parseQueueStatusResponse(new JsonParser().parse(failed).getAsJsonObject(), 1L));
    }

    @Test
    public void selectsExpectedRequeueCommandForSoloAndTeams() {
        assertEquals("/play blitz_solo_normal", BlitzPregamePlayerToggle.requeueCommandForMode(false));
        assertEquals("/play blitz_teams_normal", BlitzPregamePlayerToggle.requeueCommandForMode(true));
        assertEquals(3, ConfigManager.BLITZ_PREGAME_MIN_SLOT);
        assertEquals(8, ConfigManager.BLITZ_PREGAME_MAX_SLOT);
    }

    @Test
    public void leftToRightShinePreservesPlainTextAndHighlightsMiddle() {
        BlitzPregamePlayerToggle.QueueStatusSnapshot snap =
                new BlitzPregamePlayerToggle.QueueStatusSnapshot(42, 19, 14, 9, 1L);
        String base = BlitzPregamePlayerToggle.formatQueueStatusHudLine(snap);
        String mid = BlitzPregamePlayerToggle.applyLeftToRightShine(base, 0.5f);
        assertTrue(mid.contains("\u00a7l"));
        assertEquals(base.replaceAll("\u00a7.", ""), mid.replaceAll("\u00a7.", ""));
        assertEquals(base, BlitzPregamePlayerToggle.applyLeftToRightShine(base, 1.0f));
    }

    @Test
    public void hypixelOnlineStatusCyclesColorsAndCommands() {
        BlitzPregamePlayerToggle.HypixelOnlineStatus online = BlitzPregamePlayerToggle.HypixelOnlineStatus.ONLINE;
        BlitzPregamePlayerToggle.HypixelOnlineStatus away = online.next();
        BlitzPregamePlayerToggle.HypixelOnlineStatus busy = away.next();
        BlitzPregamePlayerToggle.HypixelOnlineStatus offline = busy.next();

        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.AWAY, away);
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.BUSY, busy);
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.APPEAR_OFFLINE, offline);
        assertEquals(online, offline.next());

        assertEquals("\u00a7a", online.colorCode);
        assertEquals("\u00a7e", away.colorCode);
        assertEquals("\u00a75", busy.colorCode);
        assertEquals("\u00a74", offline.colorCode);

        assertEquals("/status online", online.command);
        assertEquals("/status away", away.command);
        assertEquals("/status busy", busy.command);
        assertEquals("/status offline", offline.command);

        assertEquals("\u00a7fStatus: \u00a7aOnline\u00a77 (Right Click)",
                BlitzPregamePlayerToggle.formatStatusItemDisplayName(online));
        assertEquals("\u00a7fStatus: \u00a7eAway\u00a77 (Right Click)",
                BlitzPregamePlayerToggle.formatStatusItemDisplayName(away));
        assertEquals("\u00a7fStatus: \u00a75Busy\u00a77 (Right Click)",
                BlitzPregamePlayerToggle.formatStatusItemDisplayName(busy));
        assertEquals("\u00a7fStatus: \u00a74Appear Offline\u00a77 (Right Click)",
                BlitzPregamePlayerToggle.formatStatusItemDisplayName(offline));
    }

    @Test
    public void parsesHypixelStatusFromActionBarChatAndCommands() {
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.BUSY,
                BlitzPregamePlayerToggle.parseStatusFromActionBar("\u00a7fYou are currently \u00a7c\u00a7lBUSY"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.AWAY,
                BlitzPregamePlayerToggle.parseStatusFromActionBar("\u00a7fYou are currently \u00a7e\u00a7lAWAY"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.APPEAR_OFFLINE,
                BlitzPregamePlayerToggle.parseStatusFromActionBar("\u00a7fYou are currently \u00a7c\u00a7lAPPEARING OFFLINE"));
        assertNull(BlitzPregamePlayerToggle.parseStatusFromActionBar("You are currently NICKED"));
        assertTrue(BlitzPregamePlayerToggle.isOtherLobbyStatusActionBar("\u00a7fYou are currently \u00a7cNICKED"));
        assertFalse(BlitzPregamePlayerToggle.isOtherLobbyStatusActionBar("Solo: 10 | Teams: 4"));

        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.BUSY,
                BlitzPregamePlayerToggle.parseStatusFromChatConfirmation("\u00a7eYour online status has been set to Busy!"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.APPEAR_OFFLINE,
                BlitzPregamePlayerToggle.parseStatusFromChatConfirmation("\u00a7eYour online status has been set to Appear Offline!"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.ONLINE,
                BlitzPregamePlayerToggle.parseStatusFromChatConfirmation("\u00a7eYour online status is now Online"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.APPEAR_OFFLINE,
                BlitzPregamePlayerToggle.parseStatusFromChatConfirmation(
                        "\u00a7eREMINDER: Your Online Status is currently set to Appear Offline"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.AWAY,
                BlitzPregamePlayerToggle.parseStatusFromChatConfirmation(
                        "\u00a7eREMINDER: Your Online Status is currently set to Away"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.BUSY,
                BlitzPregamePlayerToggle.parseStatusFromChatConfirmation(
                        "\u00a7eREMINDER: Your Online Status is currently set to Busy"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.AWAY,
                BlitzPregamePlayerToggle.parseAlreadySetStatusFromChat("\u00a7cYour online status is already set to Away!"));

        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.APPEAR_OFFLINE,
                BlitzPregamePlayerToggle.parseStatusFromOutgoingCommand("/status offline"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.APPEAR_OFFLINE,
                BlitzPregamePlayerToggle.parseStatusFromOutgoingCommand("/status appear offline"));
        assertEquals(BlitzPregamePlayerToggle.HypixelOnlineStatus.BUSY,
                BlitzPregamePlayerToggle.parseStatusFromOutgoingCommand("/status busy"));
        assertNull(BlitzPregamePlayerToggle.parseStatusFromOutgoingCommand("/status"));
    }
}
