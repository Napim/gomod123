package me.napim.gomod.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.features.ApiKeyManager;
import me.napim.gomod.features.HypixelPlayerDataCache;
import me.napim.gomod.features.HypixelTabStatsRankCache;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.TeamSpamManager;
import me.napim.gomod.gui.PlayerInfoKitsGUI;
import me.napim.gomod.gui.TeamPlayerProfileGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.command.Command;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerInfoCommand extends Command {
    private static final SimpleDateFormat startDateFormat = new SimpleDateFormat("MM/dd/yy HH:mm");
    private static final SimpleDateFormat endTimeFormat = new SimpleDateFormat("HH:mm");
    private static final SimpleDateFormat loginDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);
    private static final Map<String, KitsViewData> KIT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ProfileViewData> PROFILE_CACHE = new ConcurrentHashMap<>();

    private static final String[] KNOWN_KITS = {
            "arachnologist", "archer", "armorer", "astronaut", "baker", "blaze", "creepertamer",
            "diver", "donkeytamer", "farmer", "fisherman", "florist", "golem", "guardian",
            "horsetamer", "hunter", "jockey", "knight", "meatmaster", "milkman", "necromancer",
            "paladin", "phoenix", "pigman", "rambo", "ranger", "reaper", "reddragon", "rogue",
            "scout", "shadowknight", "shark", "slimeyslime", "snowman", "speleologist",
            "tim", "toxicologist", "troll", "viking", "warlock", "warrior", "wolftamer"
    };

    private static final Map<String, String> KIT_DISPLAY_NAMES = new HashMap<>();
    private static final Map<String, Boolean> EXP_KITS = new HashMap<>();

    static {
        putKitDisplay("arachnologist", "Arachnologist");
        putKitDisplay("archer", "Archer");
        putKitDisplay("armorer", "Armorer");
        putKitDisplay("astronaut", "Astronaut");
        putKitDisplay("baker", "Baker");
        putKitDisplay("blaze", "Blaze");
        putKitDisplay("creepertamer", "Creepertamer");
        putKitDisplay("diver", "Diver");
        putKitDisplay("donkeytamer", "Donkeytamer");
        putKitDisplay("farmer", "Farmer");
        putKitDisplay("fisherman", "Fisherman");
        putKitDisplay("florist", "Florist");
        putKitDisplay("golem", "Golem");
        putKitDisplay("guardian", "Guardian");
        putKitDisplay("horsetamer", "Horsetamer");
        putKitDisplay("hunter", "Hunter");
        putKitDisplay("jockey", "Jockey");
        putKitDisplay("knight", "Knight");
        putKitDisplay("meatmaster", "Meatmaster");
        putKitDisplay("milkman", "Milkman");
        putKitDisplay("necromancer", "Necromancer");
        putKitDisplay("paladin", "Paladin");
        putKitDisplay("phoenix", "Phoenix");
        putKitDisplay("pigman", "Pigman");
        putKitDisplay("ranger", "Ranger");
        putKitDisplay("reaper", "Reaper");
        putKitDisplay("reddragon", "RedDragon");
        putKitDisplay("rogue", "Rogue");
        putKitDisplay("scout", "Scout");
        putKitDisplay("shadowknight", "Shadow Knight");
        putKitDisplay("shark", "Shark");
        putKitDisplay("slimeyslime", "SlimeySlime");
        putKitDisplay("snowman", "Snowman");
        putKitDisplay("speleologist", "Speleologist");
        putKitDisplay("tim", "Tim");
        putKitDisplay("toxicologist", "Toxicologist");
        putKitDisplay("troll", "Troll");
        putKitDisplay("viking", "Viking");
        putKitDisplay("warlock", "Warlock");
        putKitDisplay("warrior", "Warrior");
        putKitDisplay("wolftamer", "Wolftamer");

        EXP_KITS.put("ranger", true);
        EXP_KITS.put("donkeytamer", true);
        EXP_KITS.put("warrior", true);
        EXP_KITS.put("phoenix", true);
        EXP_KITS.put("milkman", true);
    }

    public PlayerInfoCommand() {
        super("infop");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("kits")) {
            openCachedKitsGui(args[1]);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gui")) {
            openCachedProfileGui(args[1]);
            return;
        }
        if (args.length != 1) {
            sendChat(new ChatComponentText(EnumChatFormatting.RED + "Usage: /infop <player>"));
            return;
        }

        String playerName = args[0];
        new Thread(() -> fetchAndPrintPlayerInfo(playerName), "gomod-infop-" + playerName).start();
    }

    private void fetchAndPrintPlayerInfo(String playerName) {
        try {
            JsonObject freshCachedPlayer = HypixelPlayerDataCache.getFresh(playerName);
            JsonObject lastCachedPlayer = HypixelPlayerDataCache.getAny(playerName);
            boolean usedPlayerCache = freshCachedPlayer != null;
            long cacheAgeMs = usedPlayerCache ? HypixelPlayerDataCache.getAgeMs(playerName) : -1L;
            JsonObject cachedUuidSource = freshCachedPlayer != null ? freshCachedPlayer : lastCachedPlayer;
            String uuid = cachedUuidSource == null ? null : getString(cachedUuidSource, "uuid", null);
            if (uuid == null || uuid.trim().isEmpty()) {
                uuid = fetchMojangUuid(playerName);
            }
            String apiKey = ApiKeyManager.getApiKey("hypixel");
            JsonObject player = freshCachedPlayer;
            if (player == null && apiKey != null && !apiKey.trim().isEmpty()
                    ) {
                try {
                    me.napim.gomod.features.GoStatsBlitzClient.noteHypixelPlayerRequest(uuid);
                    JsonObject playerResponse = me.napim.gomod.features.HypixelApiHelper.fetchBlitzJson(
                            "https://api.hypixel.net/player?key=" + apiKey + "&uuid=" + uuid, false);
                    if (isSuccess(playerResponse)) {
                        player = getObject(playerResponse, "player");
                    }
                } catch (Exception ignored) {
                    // If Hypixel rejects a repeat lookup, keep /infop useful with the last successful TAB scan.
                }
            }
            if (player == null) {
                player = lastCachedPlayer;
                usedPlayerCache = player != null;
                cacheAgeMs = usedPlayerCache ? HypixelPlayerDataCache.getAgeMs(playerName) : -1L;
            }
            if (player == null) {
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    sendChat(new ChatComponentText(EnumChatFormatting.RED + "Hypixel API key not set! Use /gomod api hypixel <your-api-key>"));
                    return;
                }
                sendChat(new ChatComponentText(EnumChatFormatting.RED + "No Hypixel player data found for " + playerName + "."));
                return;
            }

            // /infop is an explicit request and must remain eligible even if TAB's automatic scan
            // previously cached a sub-100-kill profile and correctly withheld its contribution.
            me.napim.gomod.features.GoStatsContributions.observeExplicitPlayerInfo(player);

            String displayName = getString(player, "displayname", playerName);
            usedPlayerCache = me.napim.gomod.features.HypixelPlayerRequests.isCached(player);
            String profileUuid = getString(player, "uuid", uuid);
            HypixelTabStatsRankCache.ingest(displayName.toLowerCase(Locale.ROOT), player);
            HypixelTabStatsRankCache.ingest(playerName.toLowerCase(Locale.ROOT), player);
            ProfileViewData profileData = new ProfileViewData(displayName, profileUuid);
            PROFILE_CACHE.put(displayName.toLowerCase(Locale.ROOT), profileData);
            PROFILE_CACHE.put(playerName.toLowerCase(Locale.ROOT), profileData);
            if (!usedPlayerCache) {
                HypixelPlayerDataCache.ingest(displayName, player);
                HypixelPlayerDataCache.ingest(playerName, player);
            }
            me.napim.gomod.features.GoStatsBlitzClient.cacheHypixelPlayerData(profileUuid, player);

            // Status and recent-games are useful extras, but their rate limits or an outage
            // must never discard a usable cached player profile.
            JsonObject statusResponse = null;
            JsonObject recentGamesResponse = null;
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                try {
                    JsonObject response = me.napim.gomod.features.HypixelApiHelper.fetchJson(
                            "https://api.hypixel.net/v2/status?key=" + apiKey + "&uuid=" + uuid);
                    if (isSuccess(response)) statusResponse = response;
                } catch (Exception ignored) { }
                try {
                    JsonObject response = me.napim.gomod.features.HypixelApiHelper.fetchJson(
                            "https://api.hypixel.net/v2/recentgames?key=" + apiKey + "&uuid=" + uuid);
                    if (isSuccess(response)) recentGamesResponse = response;
                } catch (Exception ignored) { }
            }

            JsonObject blitz = getObject(getObject(player, "stats"), "HungerGames");
            int totalKills = getInt(blitz, "kills", 0);
            cacheKits(displayName, blitz);

            StringBuilder message = new StringBuilder();
            appendHeader(message, displayName, totalKills);
            if (usedPlayerCache) {
                message.append(EnumChatFormatting.DARK_GRAY).append(
                        me.napim.gomod.features.HypixelPlayerRequests.cacheLabel(player));
                message.append(".\n");
            }
            appendStatus(message, player, getObject(statusResponse, "session"), statusResponse != null);
            appendBlitzStats(message, player, blitz);
            appendCosmetics(message, blitz);
            appendRecentGames(message, recentGamesResponse);

            sendChat(new ChatComponentText(message.toString()));
            sendChat(buildProfileClickComponent(displayName));
        } catch (Exception e) {
            sendChat(new ChatComponentText(EnumChatFormatting.RED + "Error fetching player information: " + e.getMessage()));
        }
    }

    private static String fetchMojangUuid(String playerName) throws Exception {
        URL mojangUrl = new URL("https://api.mojang.com/users/profiles/minecraft/" + playerName);
        HttpURLConnection mojangConn = (HttpURLConnection) mojangUrl.openConnection();
        mojangConn.setRequestMethod("GET");
        JsonObject mojangResponse = new JsonParser().parse(new InputStreamReader(mojangConn.getInputStream())).getAsJsonObject();
        return mojangResponse.get("id").getAsString();
    }

    private static void appendHeader(StringBuilder message, String displayName, int kills) {
        message.append(EnumChatFormatting.DARK_GRAY).append("[")
                .append(EnumChatFormatting.DARK_GREEN).append("gomod")
                .append(EnumChatFormatting.DARK_GRAY).append("] ")
                .append(EnumChatFormatting.GREEN).append("Player Info for ")
                .append(formatProfileIdentity(displayName, kills, false));
        message.append(EnumChatFormatting.GREEN).append(":\n");
    }

    private static String formatProfileIdentity(String displayName, int kills, boolean underlined) {
        String rankTag = HypixelTabStatsRankCache.getFormattedRankTag(displayName);
        String nameColor = HypixelTabStatsRankCache.getUsernameColorFormatting(displayName);
        if (nameColor == null || nameColor.isEmpty()) {
            nameColor = EnumChatFormatting.GRAY.toString();
        }

        StringBuilder identity = new StringBuilder(LocalDisguise.formatBsg2KillBracketForDisplay(kills));
        if (underlined) {
            // The kill bracket ends with RESET; reapply underline for this clickable label.
            identity.append(EnumChatFormatting.UNDERLINE);
        }
        identity.append(" ");
        if (rankTag != null && !rankTag.isEmpty()) {
            identity.append(rankTag);
            if (underlined) {
                identity.append(EnumChatFormatting.UNDERLINE);
            }
            identity.append(" ");
        }
        identity.append(nameColor);
        if (underlined) {
            identity.append(EnumChatFormatting.UNDERLINE);
        }
        return identity.append(displayName)
                .append(EnumChatFormatting.RESET).toString();
    }

    private static void appendStatus(StringBuilder message, JsonObject player, JsonObject session,
                                     boolean statusAvailable) {
        boolean online = statusAvailable && session != null && getBoolean(session, "online", false);
        String status = me.napim.gomod.features.HypixelPlayerRequests.statusLabel(player, session, statusAvailable);
        EnumChatFormatting statusColor = online ? EnumChatFormatting.GREEN
                : status.startsWith("Hidden") ? EnumChatFormatting.DARK_RED
                : "Offline".equals(status) ? EnumChatFormatting.RED : EnumChatFormatting.GRAY;
        message.append(EnumChatFormatting.AQUA).append("Status: ").append(statusColor).append(status).append("\n");

        if (online && session != null) {
            message.append(EnumChatFormatting.AQUA).append("Game Type: ").append(EnumChatFormatting.DARK_GREEN)
                    .append(getString(session, "gameType", "N/A")).append("\n");
            message.append(EnumChatFormatting.AQUA).append("Mode: ").append(EnumChatFormatting.DARK_GREEN)
                    .append(getString(session, "mode", "N/A")).append("\n");
            message.append(EnumChatFormatting.AQUA).append("Map: ").append(EnumChatFormatting.DARK_GREEN)
                    .append(getString(session, "map", "N/A")).append("\n");
        }
    }

    private static void appendBlitzStats(StringBuilder message, JsonObject player, JsonObject blitz) {
        if (blitz == null) {
            message.append(EnumChatFormatting.RED).append("No Blitz stats data found for this player\n");
            return;
        }

        int soloWins = getInt(blitz, "wins_solo_normal", getInt(blitz, "wins", 0));
        int teamWins = getInt(blitz, "wins_teams", 0);
        int totalWins = soloWins + teamWins;
        int totalKills = getInt(blitz, "kills", 0);
        int teamKills = getInt(blitz, "kills_teams_normal", getInt(blitz, "kills_teams", 0));
        int soloKills = getInt(blitz, "kills_solo", Math.max(0, totalKills - teamKills));
        int deaths = getInt(blitz, "deaths", 0);
        int coins = getInt(blitz, "coins", 0);
        double wlr = deaths > 0 ? (double) totalWins / deaths : totalWins;
        double kdr = deaths > 0 ? (double) totalKills / deaths : totalKills;

        message.append(EnumChatFormatting.YELLOW).append("Blitz Stats:\n");
        message.append(EnumChatFormatting.AQUA).append("Wins: ").append(EnumChatFormatting.WHITE)
                .append(fmt(totalWins)).append(EnumChatFormatting.GRAY)
                .append(" (Solo: ").append(fmt(soloWins)).append(", Teams: ").append(fmt(teamWins)).append(")\n");
        message.append(EnumChatFormatting.AQUA).append("Kills: ").append(EnumChatFormatting.WHITE)
                .append(fmt(totalKills)).append(EnumChatFormatting.GRAY)
                .append(" (Solo: ").append(fmt(soloKills)).append(", Teams: ").append(fmt(teamKills)).append(")\n");
        message.append(EnumChatFormatting.AQUA).append("Deaths: ").append(EnumChatFormatting.WHITE)
                .append(fmt(deaths)).append("\n");
        message.append(EnumChatFormatting.AQUA).append("WLR: ").append(EnumChatFormatting.WHITE)
                .append(String.format(Locale.US, "%.2f", wlr)).append("\n");
        message.append(EnumChatFormatting.AQUA).append("KDR: ").append(EnumChatFormatting.WHITE)
                .append(String.format(Locale.US, "%.2f", kdr)).append("\n");
        message.append(EnumChatFormatting.AQUA).append("Coins: ").append(EnumChatFormatting.GOLD)
                .append(fmt(coins)).append("\n");

        String firstLogin = has(player, "firstLogin")
                ? loginDateFormat.format(new Date(player.get("firstLogin").getAsLong()))
                : "Unknown";
        message.append(EnumChatFormatting.AQUA).append("First Login: ").append(EnumChatFormatting.WHITE)
                .append(firstLogin).append("\n");

        String defaultKit = getString(blitz, "defaultkit", "None");
        message.append(EnumChatFormatting.AQUA).append("Default Kit: ")
                .append(formatKitForChat(defaultKit, blitz)).append("\n");
    }

    private static void appendCosmetics(StringBuilder message, JsonObject blitz) {
        if (blitz == null) {
            return;
        }
        String taunt = displayTauntName(getString(blitz, "chosen_taunt", "DEFAULT"));
        String victoryDance = getString(blitz, "chosen_victorydance", "None");
        String finisher = getString(blitz, "chosen_finisher", "None");
        String afterkill = getString(blitz, "afterkill", "None");
        if (afterkill.equals("None")) afterkill = "SPEED";
        String aura = getString(blitz, "aura", "None");

        message.append(EnumChatFormatting.YELLOW).append("Cosmetics:\n");
        message.append(EnumChatFormatting.AQUA).append("Taunt: ").append(EnumChatFormatting.WHITE).append(taunt).append("\n");
        message.append(EnumChatFormatting.AQUA).append("Victory Dance: ").append(EnumChatFormatting.WHITE).append(victoryDance).append("\n");
        message.append(EnumChatFormatting.AQUA).append("Finisher: ").append(EnumChatFormatting.WHITE).append(finisher).append("\n");
        message.append(EnumChatFormatting.AQUA).append("Kill Effect: ").append(getKillEffectColor(afterkill)).append(formatKillEffectName(afterkill)).append("\n");
        message.append(EnumChatFormatting.AQUA).append("Aura: ").append(EnumChatFormatting.WHITE).append(aura).append("\n");
    }

    private static String displayTauntName(String value) {
        if (value == null || value.trim().isEmpty() || "DEFAULT".equalsIgnoreCase(value.trim())
                || "NONE".equalsIgnoreCase(value.trim()) || "UNKNOWN".equalsIgnoreCase(value.trim())) {
            return "Default";
        }
        return value;
    }

    private static void appendRecentGames(StringBuilder message, JsonObject recentGamesResponse) {
        if (recentGamesResponse == null || !recentGamesResponse.has("games") || recentGamesResponse.get("games").isJsonNull()) {
            return;
        }
        JsonArray games = recentGamesResponse.get("games").getAsJsonArray();
        if (games.size() <= 0) {
            message.append(EnumChatFormatting.YELLOW).append("\nRecent Games: ").append(EnumChatFormatting.RED).append("No recent games found");
            return;
        }
        message.append(EnumChatFormatting.YELLOW).append("\nRecent Games:\n");
        int gamesShown = 0;
        for (int i = 0; i < games.size() && gamesShown < 5; i++) {
            JsonObject game = games.get(i).getAsJsonObject();
            if (!game.has("gameType")) {
                continue;
            }
            long startDate = game.get("date").getAsLong();
            long endDate = game.has("ended") ? game.get("ended").getAsLong() : startDate;
            message.append(EnumChatFormatting.AQUA)
                    .append(startDateFormat.format(new Date(startDate)))
                    .append(EnumChatFormatting.WHITE).append(" > ")
                    .append(EnumChatFormatting.AQUA)
                    .append(endTimeFormat.format(new Date(endDate)))
                    .append(EnumChatFormatting.WHITE).append(" - ")
                    .append(EnumChatFormatting.DARK_GREEN).append(getString(game, "gameType", "N/A"))
                    .append(EnumChatFormatting.WHITE).append(" (")
                    .append(EnumChatFormatting.DARK_GREEN).append(getString(game, "mode", "N/A"))
                    .append(EnumChatFormatting.WHITE).append(" on ")
                    .append(EnumChatFormatting.DARK_GREEN).append(getString(game, "map", "N/A"))
                    .append(EnumChatFormatting.WHITE).append(")\n");
            gamesShown++;
        }
    }

    private static IChatComponent buildKitsClickComponent(String displayName) {
        ChatComponentText component = new ChatComponentText(
                EnumChatFormatting.DARK_GRAY + "[" + EnumChatFormatting.DARK_GREEN + "gomod" + EnumChatFormatting.DARK_GRAY + "] "
                        + EnumChatFormatting.AQUA + EnumChatFormatting.UNDERLINE + "Click to view all Blitz kits for "
                        + EnumChatFormatting.RESET + EnumChatFormatting.GRAY + displayName);
        ChatStyle style = new ChatStyle();
        style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/infop kits " + displayName));
        style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Open a local kits GUI")));
        component.setChatStyle(style);
        return component;
    }

    private static IChatComponent buildProfileClickComponent(String displayName) {
        ChatComponentText component = new ChatComponentText(
                EnumChatFormatting.AQUA.toString() + EnumChatFormatting.UNDERLINE + "[DETAILED STATS]");
        ChatStyle style = new ChatStyle();
        style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/infop gui " + displayName));
        style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Open the interactive Blitz player profile")));
        component.setChatStyle(style);
        return component;
    }

    private static void openCachedProfileGui(String playerName) {
        ProfileViewData data = PROFILE_CACHE.get(playerName.toLowerCase(Locale.ROOT));
        if (data == null) {
            sendChat(new ChatComponentText(EnumChatFormatting.RED + "No cached profile for " + playerName
                    + ". Run /infop " + playerName + " first."));
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            GuiScreen parent = mc.currentScreen;
            mc.addScheduledTask(() -> mc.displayGuiScreen(new TeamPlayerProfileGUI(parent,
                    new TeamSpamManager.Player(data.username, data.uuid), null, true)));
        }
    }

    private static void openCachedKitsGui(String playerName) {
        KitsViewData data = KIT_CACHE.get(playerName.toLowerCase(Locale.ROOT));
        if (data == null) {
            sendChat(new ChatComponentText(EnumChatFormatting.RED + "No cached kits for " + playerName + ". Run /infop " + playerName + " first."));
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.addScheduledTask(() -> mc.displayGuiScreen(new PlayerInfoKitsGUI(mc.currentScreen, data.title, data.lines)));
        }
    }

    private static void cacheKits(String displayName, JsonObject blitz) {
        List<KitLine> kits = new ArrayList<>();
        if (blitz != null) {
            for (String kit : KNOWN_KITS) {
                KitLevel level = getKitLevel(blitz, kit);
                if (level.isKnown()) {
                    kits.add(new KitLine(displayKitName(kit), level));
                }
            }
        }
        Collections.sort(kits, Comparator
                .comparingInt((KitLine k) -> k.level.prestige).reversed()
                .thenComparing(Comparator.comparingInt((KitLine k) -> k.level.level).reversed())
                .thenComparing(k -> k.name));

        List<String> lines = new ArrayList<>();
        if (kits.isEmpty()) {
            lines.add(EnumChatFormatting.RED + "No kit level data found.");
        } else {
            for (KitLine kit : kits) {
                lines.add(formatKitLineForGui(kit));
            }
        }
        KIT_CACHE.put(displayName.toLowerCase(Locale.ROOT), new KitsViewData(
                EnumChatFormatting.YELLOW + "Blitz kits: " + EnumChatFormatting.WHITE + displayName,
                lines));
    }

    private static String formatKitLineForGui(KitLine kit) {
        EnumChatFormatting nameColor = kit.level.prestige > 0 ? EnumChatFormatting.GOLD : EnumChatFormatting.GREEN;
        return nameColor + kit.name + EnumChatFormatting.GRAY + " - " + formatKitLevelSuffix(kit.level);
    }

    private static String formatKitForChat(String kitName, JsonObject blitz) {
        if (kitName == null || kitName.trim().isEmpty() || kitName.equalsIgnoreCase("none")) {
            return EnumChatFormatting.WHITE + "None";
        }
        String normalized = normalizeKitKey(kitName);
        KitLevel level = getKitLevel(blitz, normalized);
        EnumChatFormatting kitColor = getDefaultKitLevelColor(level);
        String suffix = level.isKnown() ? " " + plainKitLevelSuffix(level) : "";
        return kitColor + displayKitName(normalized) + suffix;
    }

    private static EnumChatFormatting getDefaultKitLevelColor(KitLevel level) {
        if (level == null || !level.isKnown()) {
            return EnumChatFormatting.WHITE;
        }
        if (level.prestige > 0) {
            return EnumChatFormatting.GOLD;
        }
        switch (Math.max(1, Math.min(10, level.level))) {
            case 1:
            case 2:
                return EnumChatFormatting.GREEN;
            case 3:
            case 4:
                return EnumChatFormatting.DARK_GREEN;
            case 5:
            case 6:
                return EnumChatFormatting.YELLOW;
            case 7:
            case 8:
                return EnumChatFormatting.GOLD;
            case 9:
                return EnumChatFormatting.RED;
            case 10:
                return EnumChatFormatting.DARK_RED;
            default:
                return EnumChatFormatting.GREEN;
        }
    }

    private static KitLevel getKitLevel(JsonObject blitz, String kitKey) {
        if (blitz == null || kitKey == null || kitKey.trim().isEmpty()) {
            return KitLevel.unknown();
        }
        String rawKey = kitKey.trim().toLowerCase(Locale.ROOT);
        String normalized = normalizeKitKey(kitKey);
        if ("rambo".equals(normalized)) return KitLevel.unknown();
        String spaced = rawKey.replace('_', ' ').replaceAll("\\s+", " ");
        String underscored = spaced.replace(' ', '_');
        int prestige = firstInt(blitz, -1, "p" + normalized, "p" + underscored, "p" + spaced);
        if (prestige > 0) {
            return new KitLevel(10, prestige);
        }

        if (EXP_KITS.containsKey(normalized)) {
            int exp = firstInt(blitz, -1, "exp_" + normalized, "exp_" + underscored,
                    "exp_" + spaced);
            if (exp >= 0) {
                return new KitLevel(levelFromExp(exp), 0);
            }
            return KitLevel.unknown();
        }

        int raw = firstInt(blitz, -1, spaced, underscored, rawKey, normalized);
        if (raw >= 0) {
            return new KitLevel(Math.max(1, Math.min(10, raw + 1)), 0);
        }
        return KitLevel.unknown();
    }

    private static int levelFromExp(int exp) {
        int[] thresholds = {100, 250, 500, 1000, 1500, 2000, 2500, 5000, 10000};
        for (int i = 0; i < thresholds.length; i++) {
            if (exp < thresholds[i]) {
                return i + 1;
            }
        }
        return 10;
    }

    private static String formatKitLevelSuffix(KitLevel level) {
        EnumChatFormatting color = level.prestige > 0 ? EnumChatFormatting.GOLD : EnumChatFormatting.GRAY;
        return color + plainKitLevelSuffix(level);
    }

    private static String plainKitLevelSuffix(KitLevel level) {
        if (level.prestige == 2) {
            return "✫✫";
        }
        if (level.prestige == 1) {
            return "✫";
        }
        return roman(level.level);
    }

    private static String displayKitName(String kitKey) {
        String normalized = normalizeKitKey(kitKey);
        String mapped = KIT_DISPLAY_NAMES.get(normalized);
        if (mapped != null) {
            return mapped;
        }
        if (normalized.isEmpty()) {
            return "Unknown";
        }
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private static String normalizeKitKey(String kitKey) {
        return kitKey == null ? "" : kitKey.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
    }

    private static void putKitDisplay(String key, String display) {
        KIT_DISPLAY_NAMES.put(normalizeKitKey(key), display);
    }

    private static String roman(int level) {
        switch (level) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            case 9: return "IX";
            case 10: return "X";
            default: return Integer.toString(level);
        }
    }

    private static int firstInt(JsonObject object, int fallback, String... keys) {
        for (String key : keys) {
            if (has(object, key)) {
                try {
                    JsonElement value = object.get(key);
                    if (value != null && value.isJsonPrimitive()) {
                        return (int) Math.floor(value.getAsDouble());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return fallback;
    }

    private static String fmt(int value) {
        return NUMBER_FORMAT.format(value);
    }

    private static boolean isSuccess(JsonObject object) {
        return object != null && getBoolean(object, "success", false);
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        if (!has(object, key)) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        if (!has(object, key)) {
            return fallback;
        }
        try {
            return (int) Math.floor(object.get(key).getAsDouble());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        if (!has(object, key)) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static JsonObject getObject(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    private static boolean has(JsonObject object, String key) {
        return object != null && key != null && object.has(key) && !object.get(key).isJsonNull();
    }

    private static void sendChat(IChatComponent component) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || component == null) {
            return;
        }
        mc.addScheduledTask(() -> {
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(component);
            }
        });
    }

    private static EnumChatFormatting getKillEffectColor(String effect) {
        switch (effect.toUpperCase(Locale.ROOT)) {
            case "REGENERATION": return EnumChatFormatting.LIGHT_PURPLE;
            case "RESISTANCE": return EnumChatFormatting.GOLD;
            case "GRAVEDIGGER": return EnumChatFormatting.GREEN;
            case "RANDOM": return EnumChatFormatting.DARK_BLUE;
            case "LEVELUP": return EnumChatFormatting.YELLOW;
            case "FLAME": return EnumChatFormatting.DARK_RED;
            case "SPEED": return EnumChatFormatting.AQUA;
            default: return EnumChatFormatting.AQUA;
        }
    }

    private static String formatKillEffectName(String effect) {
        switch (effect.toUpperCase(Locale.ROOT)) {
            case "REGENERATION": return "Regeneration";
            case "RESISTANCE": return "Resistance";
            case "GRAVEDIGGER": return "Grave digger";
            case "RANDOM": return "Random";
            case "LEVELUP": return "Level Up";
            case "FLAME": return "Flaming Arrows";
            case "SPEED": return "Speed";
            default: return "Speed";
        }
    }

    private static final class KitLevel {
        final int level;
        final int prestige;

        KitLevel(int level, int prestige) {
            this.level = level;
            this.prestige = prestige;
        }

        static KitLevel unknown() {
            return new KitLevel(0, 0);
        }

        boolean isKnown() {
            return prestige > 0 || level > 0;
        }
    }

    private static final class KitLine {
        final String name;
        final KitLevel level;

        KitLine(String name, KitLevel level) {
            this.name = name;
            this.level = level;
        }
    }

    private static final class KitsViewData {
        final String title;
        final List<String> lines;

        KitsViewData(String title, List<String> lines) {
            this.title = title;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    private static final class ProfileViewData {
        final String username;
        final String uuid;

        ProfileViewData(String username, String uuid) {
            this.username = username;
            this.uuid = uuid;
        }
    }
}
