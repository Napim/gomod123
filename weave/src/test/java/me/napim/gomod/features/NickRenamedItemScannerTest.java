package me.napim.gomod.features;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link NickRenamedItemScanner} kit-delivery gate, {@code Owner:} lore requirement,
 * default-name filtering, clickable chat spec generation, and GoStats HTTP lookup parsing.
 */
public final class NickRenamedItemScannerTest {

    private String originalGostatsUrl;

    @Before
    public void saveProps() {
        originalGostatsUrl = System.getProperty("gomod123.gostatsUrl");
        NickRenamedItemScanner.resetMatchCaches();
    }

    @After
    public void restoreProps() {
        if (originalGostatsUrl == null) {
            System.clearProperty("gomod123.gostatsUrl");
        } else {
            System.setProperty("gomod123.gostatsUrl", originalGostatsUrl);
        }
    }

    @Test
    public void normalizesFormattingAndUnicodeEquivalents() {
        assertEquals(
                "\u272BBOLDITALIC\u272B\u272BALL\u272B",
                NickRenamedItemScanner.stripFormattingExact(
                        "\u00a7l\u00a7o\u272BBOLDITALIC\u272B\u00a7r\u00a7m\u00a7l\u00a7n\u00a7o\u272BALL\u272B"));
        assertEquals("excalibur",
                NickRenamedItemScanner.normalizeSearchQuery("&6Excalibur\u200B  "));
        String mathBold = Normalizer.normalize("\uD835\uDC04xcal", Normalizer.Form.NFKC);
        assertEquals("excal", NickRenamedItemScanner.normalizeSearchQuery(mathBold));

        // Canonical exact formatting matches Python GoStats logic (handles order/case/&/leading §f§r while separating bold/italic/colors).
        assertEquals(
                "\u00a7l\u00a7o\u272Bbolditalic\u272B\u00a7m\u00a7n\u272Ball\u272B",
                NickRenamedItemScanner.canonicalizeFormattingExact(
                        "\u00a7l\u00a7o\u272BBOLDITALIC\u272B\u00a7r\u00a7m\u00a7l\u00a7n\u00a7o\u272BALL\u272B"));
        assertEquals(
                NickRenamedItemScanner.canonicalizeFormattingExact("&O&L  Excal  \u200B"),
                NickRenamedItemScanner.canonicalizeFormattingExact("\u00a7f\u00a7l\u00a7oExcal\u00a7r"));
        assertEquals(
                "\u00a7ca \u00a7ab \u00a7ec",
                NickRenamedItemScanner.canonicalizeFormattingExact("\u00a7cA \u00a7aB \u00a7eC"));
        assertNotEquals(
                NickRenamedItemScanner.canonicalizeFormattingExact("\u00a7l\u00a7oExcal"),
                NickRenamedItemScanner.canonicalizeFormattingExact("\u00a7lExcal"));
        assertNotEquals(
                NickRenamedItemScanner.canonicalizeFormattingExact("\u00a7c\u00a7lExcal"),
                NickRenamedItemScanner.canonicalizeFormattingExact("\u00a7e\u00a7lExcal"));
    }

    @Test
    public void requiresOwnerLoreAndFiltersDefaultKitSpecialAndChestItems() {
        Set<String> chestDefaults = NickRenamedItemScanner.loadChestDefaultNames();
        assertTrue("Expected bundled blitz_chest_items.json defaults to load",
                chestDefaults.contains("glamorous leather chestplate"));
        assertTrue(chestDefaults.contains("breadro"));
        assertTrue(chestDefaults.contains("one pound fish"));

        // Any item WITHOUT an Owner: lore line must be rejected even if it has a custom name.
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a7cExcal", "Excal", null, false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a7cExcal", "Excal", "", false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a7fUnknown Random Chest Adjective Sword",
                "Unknown Random Chest Adjective Sword", null, false, chestDefaults));

        // Default kit items WITH Owner: lore (levels I-X and any Prestige star glyph) must be suppressed.
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a76Fisherman's Fishing Rod (\u272B\u272B)",
                "Fisherman's Fishing Rod (\u272B\u272B)",
                "CoolNick", false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a77Knight's Stone Sword (X)",
                "Knight's Stone Sword (X)",
                "CoolNick", false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a76Armorer's Leather Boots (\u2605)",
                "Armorer's Leather Boots (\u2605)",
                "CoolNick", false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a76Shark's Chainmail Chestplate (\u272B)",
                "Shark's Chainmail Chestplate (\u272B)",
                "CoolNick", false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a76Random's Wooden Sword (X)",
                "Random's Wooden Sword (X)",
                "CoolNick", false, chestDefaults));

        // Special Blitz / UI / Skull / Chest items must be suppressed even if lore were present.
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a76Blitz Star", "Blitz Star", "Someone", false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a7aTeleporter (Right Click)", "Teleporter (Right Click)", "Someone", false, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a7eVictimPlayer's Head", "VictimPlayer's Head", "VictimPlayer", true, chestDefaults));
        assertNull(NickRenamedItemScanner.filterNonCustomName(
                "\u00a7fGlamorous Leather Chestplate", "Glamorous Leather Chestplate", "Someone", false, chestDefaults));

        // Custom renamed player kit items WITH Owner: lore must be detected and retain owner metadata.
        NickRenamedItemScanner.DetectedItem custom = NickRenamedItemScanner.filterNonCustomName(
                "\u00a7cExcal", "Excal", "OriginalOwner", false, chestDefaults);
        assertNotNull(custom);
        assertEquals("\u00a7cExcal", custom.rawDisplayName);
        assertEquals("Excal", custom.strippedDisplayName);
        assertEquals("excal", custom.normalizedQuery);
        assertEquals("OriginalOwner", custom.itemOwner);

        // Player-renamed item containing apostrophe + (X) for a non-kit word must still pass.
        NickRenamedItemScanner.DetectedItem customApostrophe = NickRenamedItemScanner.filterNonCustomName(
                "\u00a7cBob's Sword (X)", "Bob's Sword (X)", "CoolNick", false, chestDefaults);
        assertNotNull(customApostrophe);
        assertEquals("bob's sword (x)", customApostrophe.normalizedQuery);
    }

    @Test
    public void gatesScanningUntilKitsAreDeliveredInMatch() {
        long t0 = 1_000_000L;
        // Cages open & Get kit countdown active -> must NOT scan before kits arrive (~1m15).
        NickRenamedItemScanner.updateKitDeliveryState(true, false, false, false, false, t0);
        NickRenamedItemScanner.onChatMessageAt("The Blitz Star will be released in 3 minutes!", t0 + 100);
        assertFalse(NickRenamedItemScanner.isKitsDeliveredThisMatch());

        NickRenamedItemScanner.updateKitDeliveryState(false, false, true, true, false, t0 + 5_000);
        assertFalse(NickRenamedItemScanner.isKitsDeliveredThisMatch());
        assertFalse(NickRenamedItemScanner.isPostKitScanContextFlags(
                false, false, true, true, false, NickRenamedItemScanner.isKitsDeliveredThisMatch()));

        // As soon as "Get kit:" disappears cleanly while live match runs, kits have been delivered.
        NickRenamedItemScanner.updateKitDeliveryState(false, false, false, true, false, t0 + 75_000);
        assertTrue(NickRenamedItemScanner.isKitsDeliveredThisMatch());
        assertTrue(NickRenamedItemScanner.isPostKitScanContextFlags(
                false, false, false, true, false, NickRenamedItemScanner.isKitsDeliveredThisMatch()));

        // Explicit chat kit delivery line also unlocks immediately after reset.
        NickRenamedItemScanner.resetMatchCaches();
        NickRenamedItemScanner.onChatMessageAt("You will receive your Pigman kit in 75 seconds!", t0);
        NickRenamedItemScanner.updateKitDeliveryState(false, false, true, true, false, t0 + 1_000);
        assertFalse(NickRenamedItemScanner.isKitsDeliveredThisMatch());
        NickRenamedItemScanner.onChatMessageAt("You have received your Pigman kit!", t0 + 75_000);
        assertTrue(NickRenamedItemScanner.isKitsDeliveredThisMatch());

        // Deathmatch unlocks immediately even after reset.
        NickRenamedItemScanner.resetMatchCaches();
        NickRenamedItemScanner.updateKitDeliveryState(false, false, false, true, true, t0);
        assertTrue(NickRenamedItemScanner.isKitsDeliveredThisMatch());
    }

    @Test
    public void recognizesNonV4NickUuidsAndBuildsClickableChatLinks() {
        assertTrue(NickRenamedItemScanner.isNickedProfileUuid(
                UUID.nameUUIDFromBytes("nick".getBytes(StandardCharsets.UTF_8))));
        assertFalse(NickRenamedItemScanner.isNickedProfileUuid(UUID.randomUUID()));
        assertFalse(NickRenamedItemScanner.isNickedProfileUuid(null));

        NickRenamedItemScanner.NickIdentity nick = new NickRenamedItemScanner.NickIdentity(
                "CoolNick99", "RealPlayer", "dedupe-key");
        NickRenamedItemScanner.DetectedItem detected = new NickRenamedItemScanner.DetectedItem(
                "\u00a7bMySword", "MySword", "mysword", "LootedOwner");
        NickRenamedItemScanner.ChatActionSpec spec =
                NickRenamedItemScanner.buildDetectedChatSpec(nick, "hand", detected, 42);
        assertTrue(spec.prefixText.contains("CoolNick99"));
        assertTrue(spec.prefixText.contains("RealPlayer"));
        assertTrue(spec.prefixText.contains("holding renamed hand"));
        assertTrue(spec.prefixText.contains("LootedOwner"));
        assertTrue(spec.linkText.contains("[Search item owner]"));
        assertEquals("/nickitemdb 42", spec.clickCommand);
        assertEquals("\u00a7aClick to search the owner of this item\n"
                + "\u00a78Note: nick may hold/wear another player's looted item", spec.hoverText);
    }

    @Test
    public void queriesGoStatsRenamedItemsEndpointOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] capturedPath = new String[1];
        server.createContext("/blitz/data/renamed-items", (HttpExchange exchange) -> {
            URI uri = exchange.getRequestURI();
            capturedPath[0] = uri.getPath() + "?" + uri.getRawQuery();
            byte[] body = ("{"
                    + "\"query\":\"Excal\","
                    + "\"normalized_query\":\"excal\","
                    + "\"exact_total\":1,"
                    + "\"substring_total\":1,"
                    + "\"total\":2,"
                    + "\"scanned_players\":59436,"
                    + "\"matches\":["
                    + "{\"uuid\":\"0123\",\"username\":\"ExactOwner\",\"rank_info\":{\"display\":\"MVP+\",\"rank_plus_color\":\"DARK_RED\",\"monthly_rank_color\":\"GOLD\"},\"item_label\":\"Knight stone sword\",\"display_name\":\"Excal\",\"exact\":true,\"kills\":12000,\"wins\":900},"
                    + "{\"uuid\":\"4567\",\"username\":\"PartialOwner\",\"rank_info\":{\"display\":\"Default\"},\"item_label\":\"Pigman golden sword\",\"display_name\":\"Super Excalibur\",\"exact\":false,\"kills\":300,\"wins\":15}"
                    + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty("gomod123.gostatsUrl", "http://127.0.0.1:" + port);
            NickRenamedItemScanner.SearchResponse res =
                    NickRenamedItemScanner.fetchRenamedItemMatches("Excal", 12);
            assertEquals("/blitz/data/renamed-items?query=Excal&limit=12", capturedPath[0]);
            assertEquals(1, res.exactTotal);
            assertEquals(1, res.substringTotal);
            assertEquals(59436, res.scannedPlayers);
            assertEquals(2, res.matches.size());
            assertTrue(res.matches.get(0).exact);
            assertEquals("ExactOwner", res.matches.get(0).username);
            assertEquals("Knight stone sword", res.matches.get(0).itemLabel);
            assertFalse(res.matches.get(1).exact);

            List<NickRenamedItemScanner.ChatActionSpec> chatSpecs =
                    NickRenamedItemScanner.buildSearchResultSpecs(res, "NickOne");
            assertEquals(3, chatSpecs.size());
            assertEquals("/infop ExactOwner", chatSpecs.get(1).clickCommand);
            assertTrue(NickRenamedItemScanner.stripFormattingExact(chatSpecs.get(1).linkText).contains("[MVP+]"));
            assertTrue(chatSpecs.get(1).linkText.contains("ExactOwner"));
            assertTrue(chatSpecs.get(1).prefixText.contains("[Exact]"));
            assertTrue(chatSpecs.get(2).prefixText.contains("[Part]"));

            // Strict exact + formatting check sends exact=1&exact_format=1&raw=... and formats exact hits cleanly.
            NickRenamedItemScanner.SearchResponse strictRes =
                    NickRenamedItemScanner.fetchRenamedItemMatches(
                            "Excal", "\u00a7c\u00a7l\u00a7oExcal", 12, true, true);
            assertEquals(
                    "/blitz/data/renamed-items?query=Excal&limit=12&exact=1&exact_format=1&raw=%C2%A7c%C2%A7l%C2%A7oExcal",
                    capturedPath[0]);

            // Empty strict result when other-style matches exist exposes [Show Same Name] fallback button.
            NickRenamedItemScanner.SearchResponse missWithOtherStyles =
                    NickRenamedItemScanner.parseSearchResponse("{"
                            + "\"query\":\"Excal\","
                            + "\"raw_query\":\"\u00a7e\u00a7l\u00a7oExcal\","
                            + "\"normalized_query\":\"excal\","
                            + "\"exact_only\":true,"
                            + "\"exact_format_only\":true,"
                            + "\"format_exact_total\":0,"
                            + "\"exact_total\":3,"
                            + "\"substring_total\":0,"
                            + "\"total\":0,"
                            + "\"scanned_players\":60206,"
                            + "\"matches\":[]}");
            List<NickRenamedItemScanner.ChatActionSpec> missSpecs =
                    NickRenamedItemScanner.buildSearchResultSpecs(missWithOtherStyles, "NickOne");
            assertEquals(1, missSpecs.size());
            assertTrue(missSpecs.get(0).linkText.contains("[Show Same Name]"));
            assertEquals("/nickitemdb --plain Excal", missSpecs.get(0).clickCommand);
        } finally {
            server.stop(0);
        }
    }
}
