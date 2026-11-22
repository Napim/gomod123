package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fetches live Hypixel presence and the same latest-action timestamp shown by gostats123 /bsg. */
public final class GoStatsPlayerPresence {
    private static final long SESSION_CACHE_TTL_MS = 60_000L;
    private static final long SESSION_FAILURE_TTL_MS = 15_000L;
    /** A years-old login timestamp with no logout timestamp is stale profile data, not proof of a live session. */
    private static final long MAX_PROFILE_ONLINE_HINT_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final Map<String, SessionCacheEntry> SESSION_DETAILS =
            new ConcurrentHashMap<String, SessionCacheEntry>();
    private static final Set<String> SESSION_REQUESTS =
            ConcurrentHashMap.newKeySet();

    private GoStatsPlayerPresence() {
    }

    /** Returns recent status and game details, if a hover-triggered lookup has completed. */
    public static SessionDetails cachedSessionDetails(String uuid) {
        String normalized = normalizeUuid(uuid);
        SessionCacheEntry entry = normalized.isEmpty() ? null : SESSION_DETAILS.get(normalized);
        if (entry == null) return null;
        long ttl = entry.details.available || entry.details.recentGamesAvailable
                ? SESSION_CACHE_TTL_MS : SESSION_FAILURE_TTL_MS;
        return System.currentTimeMillis() - entry.fetchedAtMs <= ttl ? entry.details : null;
    }

    /** Starts at most one fresh Hypixel status/recent-games lookup per player at a time. */
    public static void requestSessionDetails(String uuid) {
        if (GoStatsLeaderboardBatchRefresh.isRunning()) return;
        final String normalized = normalizeUuid(uuid);
        if (normalized.isEmpty() || cachedSessionDetails(normalized) != null
                || !SESSION_REQUESTS.add(normalized)) return;
        Thread worker = new Thread(() -> {
            SessionDetails result;
            try {
                String key = ApiKeyManager.getApiKey("hypixel");
                if (key == null || key.trim().isEmpty())
                    throw new IllegalStateException("Hypixel API key is not configured");
                String query = "?key=" + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8.name())
                        + "&uuid=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8.name());
                boolean statusAvailable = false;
                JsonObject session = new JsonObject();
                try {
                    JsonObject response = HypixelApiHelper.fetchJsonFresh(
                            "https://api.hypixel.net/v2/status" + query);
                    if (bool(response, "success")) {
                        statusAvailable = true;
                        session = object(response, "session");
                    }
                } catch (Exception ignored) {
                    // Recent games remain useful even if live status is unavailable.
                }
                boolean recentGamesAvailable = false;
                List<RecentGame> recentGames = Collections.emptyList();
                try {
                    JsonObject recentGamesResponse = HypixelApiHelper.fetchJsonFresh(
                            "https://api.hypixel.net/v2/recentgames" + query);
                    if (bool(recentGamesResponse, "success")) {
                        recentGamesAvailable = true;
                        recentGames = parseRecentGames(recentGamesResponse);
                    }
                } catch (Exception ignored) {
                    // Keep status details even when the independent recent-games request fails.
                }
                result = new SessionDetails(statusAvailable, bool(session, "online"),
                        text(session, "gameType"), text(session, "mode"), text(session, "map"),
                        recentGamesAvailable, recentGames);
            } catch (Exception ignored) {
                result = new SessionDetails(false, false, "", "", "", false,
                        Collections.<RecentGame>emptyList());
            }
            SESSION_DETAILS.put(normalized, new SessionCacheEntry(result, System.currentTimeMillis()));
            SESSION_REQUESTS.remove(normalized);
        }, "gomod-player-session-status");
        worker.setDaemon(true);
        worker.start();
    }

    private static List<RecentGame> parseRecentGames(JsonObject response) {
        List<RecentGame> games = new ArrayList<RecentGame>();
        for (JsonElement element : array(response, "games")) {
            JsonObject game = asObject(element);
            long startedAtMs = number(game, "date");
            String gameType = text(game, "gameType");
            if (startedAtMs <= 0L || gameType.isEmpty()) continue;
            games.add(new RecentGame(gameType, text(game, "mode"), text(game, "map"),
                    startedAtMs, number(game, "ended")));
        }
        Collections.sort(games, (left, right) -> Long.compare(right.startedAtMs, left.startedAtMs));
        if (games.size() > 3) games = new ArrayList<RecentGame>(games.subList(0, 3));
        return Collections.unmodifiableList(games);
    }

    /** Use a fresh status response for the bubble once available; otherwise retain profile-derived presence. */
    public static String displayedStatus(String uuid, String fallback) {
        if ("hidden".equalsIgnoreCase(fallback)) return fallback;
        SessionDetails session = cachedSessionDetails(uuid);
        return session == null || !session.available ? fallback : session.online ? "Online" : "Offline";
    }

    private static String normalizeUuid(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean requiresStatusRequest(JsonObject player) {
        return player != null && number(player, "lastLogin") > 0L
                && number(player, "lastLogin") >= number(player, "lastLogout");
    }

    public static Presence fetch(String uuid, JsonObject player) {
        JsonObject data = player == null ? new JsonObject() : player;
        long lastLoginMs = number(data, "lastLogin");
        long lastLogoutMs = number(data, "lastLogout");
        Action action = findLastAction(data);
        String lastGameType = text(data, "mostRecentGameType");
        String status = "Unavailable";
        if (lastLoginMs <= 0L) {
            status = "Hidden";
        } else if (lastLogoutMs > lastLoginMs) {
            status = "Offline";
        } else {
            try {
                String key = ApiKeyManager.getApiKey("hypixel");
                if (key != null && !key.trim().isEmpty()) {
                    String url = "https://api.hypixel.net/v2/status?key="
                            + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8.name())
                            + "&uuid=" + URLEncoder.encode(uuid == null ? "" : uuid.replace("-", ""),
                            StandardCharsets.UTF_8.name());
                    JsonObject response = HypixelApiHelper.fetchJsonFresh(url);
                    if (bool(response, "success")) {
                        status = bool(object(response, "session"), "online") ? "Online" : "Offline";
                    }
                }
            } catch (Exception ignored) {
                status = "Unavailable";
            }
        }
        return new Presence(status, action.name, lastLoginMs, lastLogoutMs,
                action.timeSeconds, lastGameType);
    }

    /** Derives status from a just-fetched profile without a separate rate-limited /status request. */
    public static Presence fromPlayerProfile(JsonObject player) {
        JsonObject data = player == null ? new JsonObject() : player;
        long lastLoginMs = number(data, "lastLogin");
        long lastLogoutMs = number(data, "lastLogout");
        Action action = findLastAction(data);
        String lastGameType = text(data, "mostRecentGameType");
        long loginAgeMs = System.currentTimeMillis() - lastLoginMs;
        String status;
        if (lastLoginMs <= 0L) status = "Hidden";
        else if (lastLogoutMs > lastLoginMs || loginAgeMs > MAX_PROFILE_ONLINE_HINT_AGE_MS) status = "Offline";
        else if (loginAgeMs < -5L * 60L * 1000L) status = "Unavailable";
        else status = "Online";
        return new Presence(status, action.name, lastLoginMs, lastLogoutMs,
                action.timeSeconds, lastGameType);
    }

    private static Action findLastAction(JsonObject data) {
        Action latest = new Action("FIRST_LOGIN", number(data, "firstLogin") / 1000L);
        JsonObject quests = object(data, "quests");
        long questEnd = 0L;
        long questStart = 0L;
        for (Map.Entry<String, JsonElement> entry : quests.entrySet()) {
            JsonObject quest = asObject(entry.getValue());
            questStart = Math.max(questStart, number(object(quest, "active"), "started"));
            JsonArray completions = array(quest, "completions");
            if (completions.size() > 0) questEnd = Math.max(questEnd,
                    number(asObject(completions.get(completions.size() - 1)), "time"));
        }
        latest = later(latest, "QUEST_START", questStart / 1000L);
        latest = later(latest, "QUEST_COMPLETED", questEnd / 1000L);

        long petTime = 0L;
        for (Map.Entry<String, JsonElement> entry : object(data, "petStats").entrySet()) {
            JsonObject pet = asObject(entry.getValue());
            petTime = Math.max(petTime, number(object(pet, "THIRST"), "timestamp"));
            petTime = Math.max(petTime, number(object(pet, "EXERCISE"), "timestamp"));
            petTime = Math.max(petTime, number(object(pet, "HUNGER"), "timestamp"));
        }
        latest = later(latest, "PET", petTime / 1000L);
        latest = later(latest, "PET_JOURNEY", number(data, "petJourneyTimestamp") / 1000L);

        JsonObject stats = object(data, "stats");
        JsonObject skywars = object(stats, "SkyWars");
        long explanation = 0L;
        for (Map.Entry<String, JsonElement> entry : skywars.entrySet()) {
            if (entry.getKey().endsWith("explained_last"))
                explanation = Math.max(explanation, longValue(entry.getValue()));
        }
        latest = later(latest, "SW_LAB_MODE_EXPLANATION", explanation / 1000L);
        JsonObject heads = object(skywars, "head_collection");
        JsonArray recent = array(heads, "recent");
        if (recent.size() > 0)
            latest = later(latest, "SW_HEAD_RECENT", number(asObject(recent.get(recent.size() - 1)), "time") / 1000L);
        JsonArray prestigious = array(heads, "prestigious");
        if (prestigious.size() > 0)
            latest = later(latest, "SW_HEAD_PRESTIGIOUS", number(asObject(prestigious.get(prestigious.size() - 1)), "time") / 1000L);

        JsonObject pit = object(object(stats, "Pit"), "profile");
        latest = later(latest, "PIT_PROFILE_SAVE", number(pit, "last_save") / 1000L);
        latest = later(latest, "PIT_MIDFIGHT_DISCONNECT", number(pit, "last_midfight_disconnect") / 1000L);
        latest = later(latest, "PIT_CONTRACT", number(pit, "last_contract") / 1000L);
        long trade = 0L;
        for (JsonElement value : array(pit, "trade_timestamps")) trade = Math.max(trade, longValue(value));
        latest = later(latest, "PIT_TRADE", trade / 1000L);

        latest = later(latest, "CLAIM_DAILY_EXP", number(object(data, "eugene"), "dailyTwoKExp") / 1000L);
        latest = later(latest, "CLAIM_REWARD", number(data, "lastClaimedReward") / 1000L);
        latest = later(latest, "LOGIN", number(data, "lastLogin") / 1000L);
        latest = later(latest, "LOGOUT", number(data, "lastLogout") / 1000L);
        long achievement = 0L;
        for (Map.Entry<String, JsonElement> entry : object(data, "achievementRewardsNew").entrySet())
            achievement = Math.max(achievement, longValue(entry.getValue()));
        latest = later(latest, "ACHIEVEMENT_REWARD", achievement / 1000L);

        long parkour = 0L;
        for (Map.Entry<String, JsonElement> entry : object(data, "parkourCompletions").entrySet()) {
            for (JsonElement completion : asArray(entry.getValue()))
                parkour = Math.max(parkour, number(asObject(completion), "timeStart"));
        }
        latest = later(latest, "LOBBY_PARKOUR", parkour / 1000L);
        long tournamentAd = 0L;
        for (Map.Entry<String, JsonElement> entry : stats.entrySet())
            tournamentAd = Math.max(tournamentAd, number(asObject(entry.getValue()), "lastTourneyAd"));
        latest = later(latest, "TOURNAMENT_ADVERTISEMENT", tournamentAd / 1000L);
        return latest;
    }

    private static Action later(Action current, String name, long timeSeconds) {
        return timeSeconds > current.timeSeconds ? new Action(name, timeSeconds) : current;
    }

    private static JsonObject object(JsonObject object, String key) {
        return object == null ? new JsonObject() : asObject(object.get(key));
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        return object == null ? new JsonArray() : asArray(object.get(key));
    }

    private static JsonArray asArray(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static long number(JsonObject object, String key) {
        return object == null ? 0L : longValue(object.get(key));
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try { return object.get(key).getAsString().trim(); }
        catch (Exception ignored) { return ""; }
    }

    private static long longValue(JsonElement element) {
        try {
            return element == null || element.isJsonNull() ? 0L : element.getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    && object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class Action {
        final String name;
        final long timeSeconds;

        Action(String name, long timeSeconds) {
            this.name = name;
            this.timeSeconds = timeSeconds;
        }
    }

    public static final class Presence {
        public final String status;
        public final String lastAction;
        public final long lastLoginMs;
        public final long lastLogoutMs;
        public final long lastActionSeconds;
        public final String lastGameType;

        private Presence(String status, String lastAction, long lastLoginMs,
                         long lastLogoutMs, long lastActionSeconds, String lastGameType) {
            this.status = status;
            this.lastAction = lastAction;
            this.lastLoginMs = lastLoginMs;
            this.lastLogoutMs = lastLogoutMs;
            this.lastActionSeconds = lastActionSeconds;
            this.lastGameType = lastGameType == null ? "" : lastGameType;
        }
    }

    private static final class SessionCacheEntry {
        final SessionDetails details;
        final long fetchedAtMs;

        SessionCacheEntry(SessionDetails details, long fetchedAtMs) {
            this.details = details;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    public static final class SessionDetails {
        public final boolean available;
        public final boolean online;
        public final String gameType;
        public final String mode;
        public final String map;
        public final boolean recentGamesAvailable;
        public final List<RecentGame> recentGames;

        private SessionDetails(boolean available, boolean online, String gameType, String mode, String map,
                               boolean recentGamesAvailable, List<RecentGame> recentGames) {
            this.available = available;
            this.online = online;
            this.gameType = gameType == null ? "" : gameType;
            this.mode = mode == null ? "" : mode;
            this.map = map == null ? "" : map;
            this.recentGamesAvailable = recentGamesAvailable;
            this.recentGames = recentGames == null ? Collections.<RecentGame>emptyList() : recentGames;
        }
    }

    public static final class RecentGame {
        public final String gameType;
        public final String mode;
        public final String map;
        public final long startedAtMs;
        public final long endedAtMs;

        private RecentGame(String gameType, String mode, String map, long startedAtMs, long endedAtMs) {
            this.gameType = gameType == null ? "" : gameType;
            this.mode = mode == null ? "" : mode;
            this.map = map == null ? "" : map;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
        }
    }
}
