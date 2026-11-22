package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import me.napim.gomod.util.GomodPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small client for the read-only Blitz details route exposed by GoStats. */
public final class GoStatsBlitzClient {
    /** Hosted GoStats API; can be overridden for a private/proxy deployment. */
    private static final String DEFAULT_BASE_URL = "https://gostats-discord-bot.onrender.com";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 12000;
    /** Hypixel's player endpoint is rate-limited per player for roughly one minute. */
    private static final long PLAYER_REQUEST_CACHE_WINDOW_MS = 60_000L;
    /** A successful profile payload remains useful well beyond Hypixel's request cooldown. */
    private static final long PLAYER_SNAPSHOT_CACHE_WINDOW_MS = 5L * 60L * 1000L;
    private static final Map<String, CacheEntry> PLAYER_CACHE = new ConcurrentHashMap<String, CacheEntry>();
    private static final Map<String, Object> PLAYER_FETCH_LOCKS = new ConcurrentHashMap<String, Object>();
    private static final Map<String, Long> HYPIXEL_PLAYER_REQUESTS = new ConcurrentHashMap<String, Long>();
    private static volatile long sharedUnavailableUntil;

    private GoStatsBlitzClient() {
    }

    public static Snapshot fetch(String uuid) throws Exception {
        String normalized = normalizeUuid(uuid);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Missing player UUID");
        }

        Object fetchLock = PLAYER_FETCH_LOCKS.get(normalized);
        if (fetchLock == null) {
            Object created = new Object();
            Object existing = PLAYER_FETCH_LOCKS.putIfAbsent(normalized, created);
            fetchLock = existing == null ? created : existing;
        }
        synchronized (fetchLock) {
            long now = System.currentTimeMillis();
            CacheEntry cached = PLAYER_CACHE.get(normalized);
            if (cached == null) {
                cached = loadPersistedCache(normalized);
                if (cached != null) {
                    CacheEntry previous = PLAYER_CACHE.putIfAbsent(normalized, cached);
                    if (previous != null) cached = previous;
                }
            }
            if (cached != null && cached.snapshot != null
                    && now - cached.snapshotCachedAt < PLAYER_SNAPSHOT_CACHE_WINDOW_MS) {
                return cachedSnapshot(cached.snapshot);
            }
            if (cached != null && now - cached.failureAt < PLAYER_REQUEST_CACHE_WINDOW_MS) {
                if (cached.snapshot != null) {
                    return cachedSnapshot(cached.snapshot);
                }
                throw new Exception(cached.failureMessage == null
                        ? "Player stats request is cooling down; try again shortly."
                        : cached.failureMessage);
            }
            Snapshot lastGoodSnapshot = cached == null ? null : cached.snapshot;
            long lastGoodSnapshotAt = cached == null ? 0L : cached.snapshotCachedAt;

            // A recent shared Blitz profile can satisfy this read without a
            // second /player request immediately after a pregame or bot lookup.
            if (System.currentTimeMillis() >= sharedUnavailableUntil) {
                try {
                    Snapshot shared = fetchFromGoStats(normalized, 2000);
                    long at = java.time.OffsetDateTime.parse(shared.fetchedAt).toInstant().toEpochMilli();
                    if (System.currentTimeMillis() - at <= HypixelPlayerDataCache.FRESH_FOR_MS) {
                        cacheSuccess(normalized, shared);
                        return shared;
                    }
                } catch (Exception ignored) { }
            }
            String hypixelKey = ApiKeyManager.getApiKey("hypixel");
            Exception hypixelFailure = null;
            if (hypixelKey != null && !hypixelKey.trim().isEmpty()) {
                try {
                    if (isHypixelPlayerRequestCoolingDown(normalized)) {
                        throw new Exception("Hypixel player lookup is cooling down; using cached/public stats if available.");
                    }
                    Snapshot live = fetchFromHypixel(normalized, hypixelKey.trim());
                    try {
                        Snapshot saved = fetchFromGoStats(normalized, 4000);
                        Snapshot merged = withKits(live, mergeKitRows(live.kits, saved.kits));
                        merged.overallActivity = saved.overallActivity;
                        cacheSuccess(normalized, merged);
                        return merged;
                    } catch (Exception ignored) {
                        // The live Hypixel profile remains usable when the optional public GoStats cache is offline.
                        cacheSuccess(normalized, live);
                        return live;
                    }
                } catch (Exception error) {
                    hypixelFailure = error;
                }
            }

            try {
                Snapshot result = fetchFromGoStats(normalized);
                cacheSuccess(normalized, result);
                return result;
            } catch (Exception goStatsFailure) {
                String message = hypixelFailure == null
                        ? "gostats123 public cache failed: " + safeMessage(goStatsFailure)
                        : "Hypixel request failed: " + safeMessage(hypixelFailure)
                        + "; gostats123 public cache failed: " + safeMessage(goStatsFailure);
                if (lastGoodSnapshot != null) {
                    // Keep showing the last known stats during a Hypixel cooldown or GoStats outage.
                    cacheFailure(normalized, message, lastGoodSnapshot, lastGoodSnapshotAt);
                    return cachedSnapshot(lastGoodSnapshot);
                }
                cacheFailure(normalized, message, null, 0L);
                throw new Exception(message);
            }
        }
    }

    /** Fast GUI lookup: reuse the full response during Hypixel's per-player cooldown, otherwise fetch live. */
    public static JsonObject fetchLeaderboardRefreshProfile(String uuid) throws Exception {
        String normalized = normalizeUuid(uuid);
        if (normalized.isEmpty()) {
            LeaderboardRefreshDebugCapture.recordCurrent("profile.blocked", "reason=invalid-or-missing-uuid");
            throw new IllegalArgumentException("Missing player UUID");
        }
        JsonObject cached = HypixelPlayerDataCache.getFreshByUuid(normalized);
        if (cached != null && "Hypixel".equalsIgnoreCase(HypixelPlayerRequests.source(cached))
                && !HypixelPlayerRequests.blitzOnly(cached) && HypixelPlayerRequests.ageMs(cached) < 65_000L) {
            LeaderboardRefreshDebugCapture.recordCurrent("profile.cache_hit", "source=full-hypixel-profile ageMs="
                    + HypixelPlayerRequests.ageMs(cached) + " networkRequest=false");
            return cached;
        }
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            LeaderboardRefreshDebugCapture.recordCurrent("profile.blocked", "reason=missing-hypixel-api-key");
            throw new IllegalStateException("Set a Hypixel API key first.");
        }
        if (isHypixelPlayerRequestCoolingDown(normalized)) {
            LeaderboardRefreshDebugCapture.recordCurrent("profile.blocked", "reason=local-player-cooldown remainingMs="
                    + hypixelPlayerRequestCooldownRemainingMs(normalized) + " fullRecentProfileAvailable=false");
            throw new IllegalStateException("This player's Hypixel request is cooling down and no full recent profile is available.");
        }
        LeaderboardRefreshDebugCapture.recordCurrent("profile.request_decision", "source=network forceFresh=true");
        noteHypixelPlayerRequest(normalized);
        String url = "https://api.hypixel.net/v2/player?key="
                + URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8.name()) + "&uuid=" + normalized;
        JsonObject response = HypixelApiHelper.fetchBlitzJsonForExplicitRefresh(url);
        if (!bool(response, "success")) throw new Exception(string(response, "cause", "Hypixel rejected the player refresh"));
        if (!cacheSuccessfulHypixelPlayerResponse(normalized, response)) {
            throw new Exception("Hypixel did not return a valid Blitz profile for this player.");
        }
        return object(response, "player");
    }

    /** Force a fresh Hypixel player lookup and submit its Blitz profile through the normal GoStats contribution path. */
    public static Snapshot refreshFromHypixel(String uuid) throws Exception {
        String normalized = normalizeUuid(uuid);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Missing player UUID");
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Set a Hypixel API key first.");
        }
        if (isHypixelPlayerRequestCoolingDown(normalized)) {
            throw new IllegalStateException("Hypixel recently checked this player. Try again after the one-minute cooldown.");
        }
        noteHypixelPlayerRequest(normalized);
        String url = "https://api.hypixel.net/v2/player?key=" + URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8.name())
                + "&uuid=" + normalized;
        JsonObject response = HypixelApiHelper.fetchBlitzJsonForExplicitRefresh(url);
        if (!bool(response, "success")) {
            throw new Exception(string(response, "cause", "Hypixel rejected the player refresh"));
        }
        if (!cacheSuccessfulHypixelPlayerResponse(normalized, response)) {
            throw new Exception("Hypixel did not return a valid Blitz profile for this player.");
        }
        GoStatsContributions.SubmissionResult contribution =
                GoStatsContributions.submitFreshHypixelResponse(response);
        if (!contribution.isUsableByLeaderboard() && !"stale".equalsIgnoreCase(contribution.status)) {
            if ("quarantined".equalsIgnoreCase(contribution.status)) {
                throw new Exception("gostats123 received this refresh but held it for review; the leaderboard keeps official data.");
            }
            throw new Exception(contribution.failureDescription());
        }
        Snapshot snapshot = parseHypixelPlayerResponse(normalized, response);
        cacheSuccess(normalized, snapshot);
        return snapshot;
    }

    private static void cacheSuccess(String normalized, Snapshot snapshot) {
        long cachedAt = System.currentTimeMillis();
        CacheEntry entry = new CacheEntry(snapshot, null, cachedAt, 0L);
        PLAYER_CACHE.put(normalized, entry);
        savePersistedCache(normalized, entry);
    }

    private static void cacheFailure(String normalized, String message, Snapshot snapshot, long snapshotCachedAt) {
        PLAYER_CACHE.put(normalized, new CacheEntry(snapshot, message, snapshotCachedAt, System.currentTimeMillis()));
    }

    private static File cacheDirectory() {
        String configured = System.getProperty("gomod123.playerProfileCacheDir");
        return configured == null || configured.trim().isEmpty()
                ? new File(GomodPaths.getGomod123Dir(), "player-profile-cache")
                : new File(configured.trim());
    }

    private static File cacheFile(String normalized) {
        return new File(cacheDirectory(), normalized + ".json");
    }

    private static CacheEntry loadPersistedCache(String normalized) {
        File file = cacheFile(normalized);
        if (!file.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            long cachedAt = number(root, "cached_at");
            JsonObject data = object(root, "snapshot");
            List<Kit> kits = new ArrayList<Kit>();
            for (JsonElement element : array(data, "kits")) {
                if (!element.isJsonObject()) continue;
                JsonObject kit = element.getAsJsonObject();
                kits.add(new Kit(string(kit, "name", "Unknown kit"), bool(kit, "owned"),
                        number(kit, "level"), number(kit, "prestige"), number(kit, "kills"),
                        number(kit, "deaths"), number(kit, "wins"), number(kit, "games"),
                        decimal(kit, "kd"), decimal(kit, "wlr"), bool(kit, "ratios_available"),
                        number(kit, "last_activity_at"), string(kit, "activity_after", ""),
                        string(kit, "activity_before", ""), Activity.parse(kit)));
            }
            Snapshot snapshot = new Snapshot(string(data, "username", "Unknown"),
                    string(data, "uuid", normalized), string(data, "fetched_at", "unknown"),
                    string(data, "last_checked", "never"), number(data, "kills"),
                    number(data, "solo_kills"), number(data, "team_kills"), number(data, "deaths"),
                    number(data, "solo_wins"), number(data, "team_wins"), number(data, "wins"),
                    number(data, "games"), number(data, "coins"), number(data, "time_played"),
                    decimal(data, "kd"), decimal(data, "wlr"), decimal(data, "win_rate"), kits,
                    Cosmetics.parse(object(data, "cosmetics")));
            snapshot.overallActivity = ActivitySummary.parse(object(data, "activity"));
            if (cachedAt <= 0L || snapshot.kits.isEmpty() && snapshot.kills == 0L
                    && snapshot.wins == 0L && snapshot.games == 0L) return null;
            return new CacheEntry(snapshot, null, cachedAt, 0L);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void savePersistedCache(String normalized, CacheEntry entry) {
        if (entry == null || entry.snapshot == null) return;
        File directory = cacheDirectory();
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) return;
        File destination = cacheFile(normalized);
        File temporary = new File(directory, normalized + ".json.tmp");
        try {
            JsonObject root = new JsonObject();
            root.addProperty("cached_at", entry.snapshotCachedAt);
            root.add("snapshot", snapshotToJson(entry.snapshot));
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception unsupportedAtomicMove) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            trimPersistedCache(directory, 512);
        } catch (Exception ignored) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (Exception ignoredAgain) { }
        }
    }

    private static JsonObject snapshotToJson(Snapshot snapshot) {
        JsonObject data = new JsonObject();
        data.addProperty("username", snapshot.username);
        data.addProperty("uuid", snapshot.uuid);
        data.addProperty("fetched_at", snapshot.fetchedAt);
        data.addProperty("last_checked", snapshot.lastChecked);
        data.addProperty("kills", snapshot.kills);
        data.addProperty("solo_kills", snapshot.soloKills);
        data.addProperty("team_kills", snapshot.teamKills);
        data.addProperty("deaths", snapshot.deaths);
        data.addProperty("solo_wins", snapshot.soloWins);
        data.addProperty("team_wins", snapshot.teamWins);
        data.addProperty("wins", snapshot.wins);
        data.addProperty("games", snapshot.games);
        data.addProperty("coins", snapshot.coins);
        data.addProperty("time_played", snapshot.timePlayedSeconds);
        data.addProperty("kd", snapshot.kd);
        data.addProperty("wlr", snapshot.wlr);
        data.addProperty("win_rate", snapshot.winRate);
        data.add("cosmetics", snapshot.cosmetics.toJson());
        data.add("activity", snapshot.overallActivity.toJson());
        JsonArray kits = new JsonArray();
        for (Kit kit : snapshot.kits) {
            JsonObject row = new JsonObject();
            row.addProperty("name", kit.name);
            row.addProperty("owned", kit.owned);
            row.addProperty("level", kit.level);
            row.addProperty("prestige", kit.prestige);
            row.addProperty("kills", kit.kills);
            row.addProperty("deaths", kit.deaths);
            row.addProperty("wins", kit.wins);
            row.addProperty("games", kit.games);
            row.addProperty("kd", kit.kd);
            row.addProperty("wlr", kit.wlr);
            row.addProperty("ratios_available", kit.ratiosAvailable);
            row.addProperty("last_activity_at", kit.lastActivityAt);
            row.addProperty("activity_after", kit.activityAfter);
            row.addProperty("activity_before", kit.activityBefore);
            row.add("activity_deltas", kit.activity.toJson());
            row.addProperty("activity_source", kit.activity.source);
            row.addProperty("activity_baseline_source", kit.activity.baselineSource);
            if (kit.activity.levelBefore >= 0L) row.addProperty("activity_level_before", kit.activity.levelBefore);
            if (kit.activity.levelAfter >= 0L) row.addProperty("activity_level_after", kit.activity.levelAfter);
            if (kit.activity.prestigeBefore >= 0L) row.addProperty("activity_prestige_before", kit.activity.prestigeBefore);
            if (kit.activity.prestigeAfter >= 0L) row.addProperty("activity_prestige_after", kit.activity.prestigeAfter);
            kits.add(row);
        }
        data.add("kits", kits);
        return data;
    }

    private static void trimPersistedCache(File directory, int maximumEntries) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length <= maximumEntries) return;
        java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = maximumEntries; i < files.length; i++) {
            try { Files.deleteIfExists(files[i].toPath()); } catch (Exception ignored) { }
        }
    }

    private static final class CacheEntry {
        private final Snapshot snapshot;
        private final String failureMessage;
        private final long snapshotCachedAt;
        private final long failureAt;

        private CacheEntry(Snapshot snapshot, String failureMessage, long snapshotCachedAt, long failureAt) {
            this.snapshot = snapshot;
            this.failureMessage = failureMessage;
            this.snapshotCachedAt = snapshotCachedAt;
            this.failureAt = failureAt;
        }
    }

    private static Snapshot fetchFromGoStats(String normalized) throws Exception {
        return fetchFromGoStats(normalized, READ_TIMEOUT_MS);
    }

    private static Snapshot fetchFromGoStats(String normalized, int readTimeoutMs) throws Exception {
        return parseSnapshot(fetchGoStatsJson(normalized, readTimeoutMs), normalized);
    }

    private static JsonObject fetchGoStatsJson(String normalized, int readTimeoutMs) throws Exception {
        String base = System.getProperty("gomod123.gostatsUrl", DEFAULT_BASE_URL);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URL url = new URL(base + "/blitz/data/player/" + normalized);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, readTimeoutMs));
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "gomod123-team-nick-finder");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new Exception("gostats123 returned HTTP " + status);
            }
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                JsonElement root = new JsonParser().parse(reader);
                if (root == null || !root.isJsonObject()) {
                    throw new Exception("gostats123 returned invalid JSON");
                }
                return root.getAsJsonObject();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Blitz-only shared cache. Never used to confirm a current kill-effect selection. */
    public static JsonObject fetchSharedPlayer(String uuid, long maxAgeMs) {
        if (System.currentTimeMillis() < sharedUnavailableUntil) return null;
        try {
            JsonObject data = fetchGoStatsJson(uuid, 2000);
            long at = java.time.OffsetDateTime.parse(string(data, "fetched_at", "")).toInstant().toEpochMilli();
            long age = System.currentTimeMillis() - at;
            if (age < -15_000L || age > maxAgeMs || !uuid.equals(normalizeUuid(string(data, "uuid", "")))) return null;
            JsonObject stats = object(data, "raw_stats");
            if (stats.entrySet().isEmpty()) return null;
            JsonObject player = object(data, "profile");
            player.addProperty("uuid", uuid);
            player.addProperty("displayname", string(data, "username", uuid));
            JsonObject games = new JsonObject(); games.add("HungerGames", stats);
            player.add("stats", games);
            player.add("achievements", object(data, "achievements"));
            boolean community = "community".equals(string(data, "source", ""));
            HypixelPlayerRequests.tag(player, true, community ? "gostats123 / gomod observation" : "gostats123 official", at, true);
            if (community) player.addProperty("_gomod_community", true);
            return player;
        } catch (Exception ignored) {
            sharedUnavailableUntil = System.currentTimeMillis() + 30_000L;
            return null;
        }
    }

    private static Snapshot cachedSnapshot(Snapshot base) {
        Snapshot result = withKits(base, base.kits);
        result.cached = true;
        return result;
    }

    private static Snapshot withKits(Snapshot base, List<Kit> kits) {
        Snapshot result = new Snapshot(base.username, base.uuid, base.fetchedAt, base.lastChecked,
                base.kills, base.soloKills, base.teamKills, base.deaths, base.soloWins,
                base.teamWins, base.wins, base.games, base.coins, base.timePlayedSeconds,
                base.kd, base.wlr, base.winRate, kits, base.cosmetics);
        result.overallActivity = base.overallActivity;
        result.cached = base.cached;
        result.cacheSource = base.cacheSource;
        return result;
    }

    private static List<Kit> mergeKitRows(List<Kit> liveKits, List<Kit> savedKits) {
        List<Kit> merged = new ArrayList<Kit>();
        for (Kit live : liveKits) {
            Kit saved = findKit(savedKits, live.name);
            merged.add(saved == null ? live : saved);
        }
        Collections.sort(merged, Comparator.comparingLong(Kit::getKills).reversed());
        return merged;
    }

    /** Fetch only shared activity metadata; preserve the already loaded stats. */
    public static Snapshot withSharedActivity(Snapshot base) throws Exception {
        Snapshot shared = fetchFromGoStats(normalizeUuid(base.uuid), 4000);
        List<Kit> kits = new ArrayList<Kit>();
        for (Kit kit : base.kits) {
            Kit activity = findKit(shared.kits, kit.name);
            kits.add(new Kit(kit.name, kit.owned, kit.level, kit.prestige,
                    kit.kills, kit.deaths, kit.wins, kit.games, kit.kd, kit.wlr, kit.ratiosAvailable,
                    activity == null ? 0L : activity.lastActivityAt,
                    activity == null ? "" : activity.activityAfter,
                    activity == null ? "" : activity.activityBefore,
                    activity == null ? Activity.UNKNOWN : activity.activity));
        }
        Snapshot enriched = withKits(base, kits);
        enriched.overallActivity = shared.overallActivity;
        return enriched;
    }

    public static List<Kit> sortedKits(List<Kit> source, boolean recent) {
        List<Kit> result = new ArrayList<Kit>(source);
        Comparator<Kit> order = Comparator.comparingLong(Kit::getKills).reversed()
                .thenComparing(kit -> kit.name, String.CASE_INSENSITIVE_ORDER);
        if (recent) {
            order = Comparator.comparingLong((Kit kit) -> kit.activity.games).reversed()
                    .thenComparing(Comparator.comparingLong((Kit kit) -> kit.activity.kills).reversed())
                    .thenComparing(order);
        }
        Collections.sort(result, order);
        return result;
    }

    private static Kit findKit(List<Kit> kits, String name) {
        String target = normalizeKitName(name);
        for (Kit kit : kits) {
            if (target.equals(normalizeKitName(kit.name))) {
                return kit;
            }
        }
        return null;
    }

    private static String normalizeKitName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /** Fetches live Blitz data with the Hypixel key configured in the mod's API Keys GUI. */
    private static Snapshot fetchFromHypixel(String uuid, String apiKey) throws Exception {
        String url = "https://api.hypixel.net/v2/player?key="
                + URLEncoder.encode(apiKey, "UTF-8") + "&uuid=" + uuid;
        JsonObject root = HypixelApiHelper.fetchJson(url);
        return parseHypixelPlayerResponse(uuid, root);
    }

    /** Records a player-profile request made by another feature, such as Teams Nick Finder refresh. */
    public static void noteHypixelPlayerRequest(String uuid) {
        String normalized = normalizeUuid(uuid);
        if (!normalized.isEmpty()) {
            HYPIXEL_PLAYER_REQUESTS.put(normalized, System.currentTimeMillis());
        }
    }

    /** Seeds the profile overview from a successful Hypixel {@code /player} response already fetched elsewhere. */
    public static boolean cacheSuccessfulHypixelPlayerResponse(String uuid, JsonObject root) {
        String normalized = normalizeUuid(uuid);
        if (normalized.isEmpty()) {
            return false;
        }
        JsonObject payload = object(root, "player");
        if (!bool(root, "success")) return false;
        if (payload.has("uuid") && !normalized.equals(normalizeUuid(string(payload, "uuid", "")))) return false;
        payload.addProperty("uuid", normalized);
        if (!payload.has(HypixelPlayerRequests.META))
            HypixelPlayerRequests.tag(payload, false, "Hypixel", System.currentTimeMillis(), false);
        if (!HypixelPlayerRequests.isCached(payload)) {
            HYPIXEL_PLAYER_REQUESTS.put(normalized, HypixelPlayerRequests.observedAt(payload));
            HypixelPlayerRequests.noteNetworkSuccess(normalized, HypixelPlayerRequests.observedAt(payload));
        }
        HypixelPlayerDataCache.ingest(string(payload, "displayname", normalized), payload);
        try {
            Snapshot snapshot = parseHypixelPlayerResponse(normalized, root);
            long originalAt = HypixelPlayerRequests.observedAt(payload);
            CacheEntry entry = new CacheEntry(snapshot, null,
                    originalAt > 0 ? originalAt : System.currentTimeMillis(), 0L);
            CacheEntry previous = PLAYER_CACHE.get(normalized);
            if (previous == null || previous.snapshotCachedAt <= entry.snapshotCachedAt) {
                PLAYER_CACHE.put(normalized, entry);
                savePersistedCache(normalized, entry);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Seeds the profile cache from a player object already present in the local TAB/infop cache. */
    public static boolean cacheHypixelPlayerData(String uuid, JsonObject player) {
        if (player == null) return false;
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.add("player", player);
        return cacheSuccessfulHypixelPlayerResponse(uuid, root);
    }

    private static Snapshot parseHypixelPlayerResponse(String uuid, JsonObject root) throws Exception {
        if (!bool(root, "success")) {
            throw new Exception(string(root, "cause", "Hypixel API rejected the request"));
        }

        JsonObject player = object(root, "player");
        JsonObject statsRoot = object(player, "stats");
        JsonObject blitz = object(statsRoot, "HungerGames");
        if (blitz.entrySet().isEmpty()) {
            throw new Exception("No Blitz statistics were returned by Hypixel");
        }

        long kills = numberAny(blitz, "kills");
        long soloKills = numberAny(blitz, "kills_solo", "kills_solo_normal");
        long teamKills = numberAny(blitz, "kills_teams_normal", "kills_teams");
        if (soloKills == 0L && teamKills == 0L) {
            soloKills = kills;
        } else if (soloKills == 0L) {
            soloKills = Math.max(0L, kills - teamKills);
        }

        long soloWins = numberAny(blitz, "wins_solo_normal", "wins_solo");
        long teamWins = numberAny(blitz, "wins_teams", "wins_teams_normal");
        long wins = numberAny(blitz, "wins");
        if (wins == 0L) {
            wins = soloWins + teamWins;
        }
        if (soloWins == 0L && teamWins == 0L) {
            soloWins = wins;
        }

        long deaths = numberAny(blitz, "deaths");
        long games = numberAny(blitz, "games_played", "games");
        long coins = numberAny(blitz, "coins");
        long timePlayed = numberAny(blitz, "time_played");
        double kd = deaths > 0L ? (double) kills / (double) deaths : kills;
        double wlr = deaths > 0L ? (double) wins / (double) deaths : wins;
        double winRate = games > 0L ? (double) wins / (double) games : 0.0;
        String username = string(player, "displayname", "Unknown");
        long observed = HypixelPlayerRequests.observedAt(player);
        String observedTime = java.time.Instant.ofEpochMilli(observed > 0L ? observed : System.currentTimeMillis()).toString();
        Snapshot result = new Snapshot(username, uuid, observedTime, observedTime,
                kills, soloKills, teamKills, deaths, soloWins, teamWins, wins, games,
                coins, timePlayed, kd, wlr, winRate, parseHypixelKits(blitz),
                Cosmetics.fromBlitz(blitz));
        result.cached = HypixelPlayerRequests.isCached(player);
        result.cacheSource = HypixelPlayerRequests.source(player);
        return result;
    }

    /** True while the same player's live Hypixel lookup is inside its one-minute cooldown. */
    public static boolean isHypixelPlayerRequestCoolingDown(String uuid) {
        return hypixelPlayerRequestCooldownRemainingMs(uuid) > 0L;
    }

    /** Milliseconds remaining in this client's per-player cooldown, for refresh diagnostics. */
    public static long hypixelPlayerRequestCooldownRemainingMs(String uuid) {
        String normalized = normalizeUuid(uuid);
        if (normalized.isEmpty()) {
            return 0L;
        }
        Long requestedAt = HYPIXEL_PLAYER_REQUESTS.get(normalized);
        if (requestedAt == null) return 0L;
        return Math.max(0L, PLAYER_REQUEST_CACHE_WINDOW_MS
                - (System.currentTimeMillis() - requestedAt.longValue()));
    }

    private static List<Kit> parseHypixelKits(JsonObject blitz) {
        String[] kits = new String[]{
                "arachnologist", "archer", "armorer", "astronaut", "baker", "blaze", "creepertamer",
                "diver", "donkeytamer", "farmer", "fisherman", "florist", "golem", "guardian",
                "horsetamer", "hunter", "jockey", "knight", "meatmaster", "milkman", "necromancer",
                "paladin", "phoenix", "pigman", "ranger", "reaper", "reddragon", "rogue", "scout",
                "shadow knight", "shark", "slimeyslime", "snowman", "speleologist", "tim", "toxicologist",
                "troll", "viking", "warlock", "warrior", "wolftamer", "rambo"};
        List<Kit> result = new ArrayList<Kit>();
        for (String kit : kits) {
            long kills = numberAny(blitz, "kills_" + kit);
            long deaths = numberAny(blitz, "deaths_" + kit);
            long soloWins = numberAny(blitz, "wins_solo_" + kit);
            if (soloWins <= 0L) {
                // Hypixel stores per-kit Solo wins as wins_<kit>; Teams are separate.
                soloWins = numberAny(blitz, "wins_" + kit);
            }
            long teamWins = numberAny(blitz, "wins_teams_" + kit);
            long wins = Math.max(numberAny(blitz, "total_wins_" + kit, "wins_total_" + kit),
                    soloWins + teamWins);
            long games = numberAny(blitz, "games_played_" + kit);
            long prestige = numberAny(blitz, "p" + kit);
            long level;
            if (isExperienceBasedKit(kit)) {
                level = kitLevelFromExperience(numberAny(blitz, "exp_" + kit));
            } else {
                // Hypixel stores the normal kit level as zero-based progress. GoStats displays it + 1.
                level = hasKey(blitz, kit) ? numberAny(blitz, kit) + 1L : 0L;
            }
            if (prestige > 0L && level <= 0L) {
                level = 10L;
            }
            if (level <= 0L && games > 0L) {
                level = 1L;
            }
            if (isBasicKit(kit) && level <= 0L) {
                level = 1L;
            }
            if (kills == 0L && deaths == 0L && wins == 0L && games == 0L && level == 0L) {
                continue;
            }
            // GoStats normalizes kit deaths as games played minus kit wins.
            // Hypixel commonly omits deaths_<kit>, so using kills/wins here produces bogus K/D values.
            deaths = Math.max(0L, games - wins);
            double kd = (double) kills / (double) Math.max(1L, deaths);
            double wlr = (double) wins / (double) Math.max(1L, deaths);
            result.add(new Kit(displayKitName(kit), level > 0L || games > 0L, level, prestige,
                    kills, deaths, wins, games, kd, wlr, true));
        }
        Collections.sort(result, Comparator.comparingLong(Kit::getKills).reversed());
        return result;
    }

    /** Same EXP-based kit set and thresholds used by GoStats' /kits command. */
    private static boolean isExperienceBasedKit(String kit) {
        return "ranger".equals(kit) || "donkeytamer".equals(kit) || "warrior".equals(kit)
                || "phoenix".equals(kit) || "milkman".equals(kit);
    }

    private static boolean isBasicKit(String kit) {
        return "archer".equals(kit) || "meatmaster".equals(kit) || "speleologist".equals(kit)
                || "baker".equals(kit) || "knight".equals(kit) || "guardian".equals(kit)
                || "scout".equals(kit) || "hunter".equals(kit) || "hype train".equals(kit)
                || "fisherman".equals(kit) || "armorer".equals(kit) || "rambo".equals(kit);
    }

    private static long kitLevelFromExperience(long experience) {
        if (experience < 100L) return 1L;
        if (experience < 250L) return 2L;
        if (experience < 500L) return 3L;
        if (experience < 1000L) return 4L;
        if (experience < 1500L) return 5L;
        if (experience < 2000L) return 6L;
        if (experience < 2500L) return 7L;
        if (experience < 5000L) return 8L;
        if (experience < 10000L) return 9L;
        return 10L;
    }

    public static String displayKitName(String kit) {
        if (kit == null || kit.trim().isEmpty()) return "Unknown kit";
        String normalized = kit.trim().toLowerCase(Locale.ROOT);
        if ("shadow knight".equals(normalized)) return "Shadow Knight";
        if ("slimeyslime".equals(normalized)) return "SlimeySlime";
        if ("reddragon".equals(normalized)) return "RedDragon";
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static long numberAny(JsonObject object, String... keys) {
        if (object == null || keys == null) return 0L;
        for (String key : keys) {
            try {
                JsonElement value = object.get(key);
                if (value != null && !value.isJsonNull()) return value.getAsLong();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private static boolean hasKey(JsonObject object, String key) {
        return object != null && key != null && object.has(key) && !object.get(key).isJsonNull();
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "request failed" : message;
    }

    private static Snapshot parseSnapshot(JsonObject root, String fallbackUuid) {
        JsonObject stats = object(root, "stats");
        List<Kit> kits = new ArrayList<Kit>();
        JsonArray kitArray = array(root, "kits");
        for (JsonElement element : kitArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject kitObject = element.getAsJsonObject();
            JsonObject kitStats = object(kitObject, "stats");
            kits.add(new Kit(
                    string(kitObject, "kit", "Unknown kit"),
                    bool(kitStats, "owned"),
                    number(kitStats, "level"),
                    number(kitStats, "prestige"),
                    number(kitStats, "kills"),
                    number(kitStats, "deaths"),
                    number(kitStats, "wins"),
                    number(kitStats, "games"),
                    decimal(kitStats, "kd"),
                    decimal(kitStats, "wlr"),
                    true, (long) (decimal(kitStats, "last_activity_at") * 1000.0),
                    string(kitStats, "activity_after", ""), string(kitStats, "activity_before", ""),
                    Activity.parse(kitStats)));
        }
        Collections.sort(kits, Comparator.comparingLong(Kit::getKills).reversed());
        Snapshot snapshot = new Snapshot(
                string(root, "username", "Unknown"),
                string(root, "uuid", fallbackUuid),
                string(root, "fetched_at", "unknown"),
                string(root, "last_checked", "never"),
                number(stats, "kills"),
                number(stats, "solo_kills"),
                number(stats, "team_kills"),
                number(stats, "deaths"),
                number(stats, "solo_wins"),
                number(stats, "team_wins"),
                number(stats, "wins"),
                number(stats, "games"),
                number(stats, "coins"),
                number(stats, "time_played"),
                decimal(stats, "kd"),
                decimal(stats, "wlr"),
                decimal(stats, "win_rate"),
                kits,
                Cosmetics.fromBlitz(object(root, "raw_stats"), object(root, "cosmetics")));
        snapshot.overallActivity = ActivitySummary.parse(object(root, "activity"));
        return snapshot;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static long number(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0L : value.getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static double decimal(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0.0 : value.getAsDouble();
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value != null && !value.isJsonNull() && value.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Boolean optionalBoolean(JsonObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            try {
                JsonElement value = object.get(key);
                if (value == null || value.isJsonNull()) continue;
                String text = value.getAsString();
                if ("1".equals(text) || "true".equalsIgnoreCase(text)) return Boolean.TRUE;
                if ("0".equals(text) || "false".equalsIgnoreCase(text)) return Boolean.FALSE;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) {
                String result = value.getAsString();
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String normalizeUuid(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("-", "").trim().toLowerCase();
        return normalized.matches("[0-9a-f]{32}") ? normalized : "";
    }

    public static final class Cosmetics {
        public static final Cosmetics UNKNOWN = new Cosmetics("", "", "", "", "", "", null);
        public final String defaultKit;
        public final String taunt;
        public final String victoryDance;
        public final String finisher;
        public final String killEffect;
        public final String aura;
        /** Null means the source profile did not provide an aura enabled flag. */
        public final Boolean auraEnabled;

        private Cosmetics(String defaultKit, String taunt, String victoryDance,
                          String finisher, String killEffect, String aura, Boolean auraEnabled) {
            this.defaultKit = clean(defaultKit);
            this.taunt = clean(taunt);
            this.victoryDance = clean(victoryDance);
            this.finisher = clean(finisher);
            this.killEffect = clean(killEffect);
            this.aura = clean(aura);
            this.auraEnabled = auraEnabled;
        }

        private static Cosmetics fromBlitz(JsonObject blitz) {
            return fromBlitz(blitz, null);
        }

        private static Cosmetics fromBlitz(JsonObject blitz, JsonObject fallback) {
            if (blitz == null || blitz.entrySet().isEmpty()) return parse(fallback);
            String killEffect = string(blitz, "afterkill", "Speed");
            Boolean auraEnabled = optionalBoolean(blitz, "aura_enabled", "auraEnabled");
            if (auraEnabled == null) auraEnabled = optionalBoolean(fallback, "aura_enabled", "auraEnabled");
            return new Cosmetics(string(blitz, "defaultkit", ""),
                    string(blitz, "chosen_taunt", ""),
                    string(blitz, "chosen_victorydance", ""),
                    string(blitz, "chosen_finisher", ""),
                    killEffect, string(blitz, "aura", ""), auraEnabled);
        }

        private static Cosmetics parse(JsonObject data) {
            return data == null || data.entrySet().isEmpty() ? UNKNOWN
                    : new Cosmetics(string(data, "default_kit", ""), string(data, "taunt", ""),
                    string(data, "victory_dance", ""), string(data, "finisher", ""),
                    string(data, "kill_effect", ""), string(data, "aura", ""),
                    optionalBoolean(data, "aura_enabled", "auraEnabled"));
        }

        private JsonObject toJson() {
            JsonObject data = new JsonObject();
            data.addProperty("default_kit", defaultKit);
            data.addProperty("taunt", taunt);
            data.addProperty("victory_dance", victoryDance);
            data.addProperty("finisher", finisher);
            data.addProperty("kill_effect", killEffect);
            data.addProperty("aura", aura);
            if (auraEnabled != null) data.addProperty("aura_enabled", auraEnabled.booleanValue());
            return data;
        }

        public boolean isAvailable() {
            return !defaultKit.isEmpty() || !taunt.isEmpty() || !victoryDance.isEmpty()
                    || !finisher.isEmpty() || !killEffect.isEmpty() || !aura.isEmpty()
                    || auraEnabled != null;
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public static final class Snapshot {
        public boolean cached = true;
        public String cacheSource = "gostats123 database";
        public String cacheLabel() {
            try {
                long age = Math.max(1L, (System.currentTimeMillis() - java.time.OffsetDateTime.parse(fetchedAt).toInstant().toEpochMilli()) / 1000L);
                if (!cached && age < 5) return "Fresh Hypixel";
                String ageLabel = age < 60 ? age + "s"
                        : age < 3600 ? age / 60 + "m"
                        : age < 86400 ? age / 3600 + "h"
                        : age / 86400 + "d";
                return "From " + ageLabel + " ago";
            } catch (Exception ignored) { return "From an unknown time"; }
        }
        public final String username;
        public final String uuid;
        public final String fetchedAt;
        public final String lastChecked;
        public final long kills;
        public final long soloKills;
        public final long teamKills;
        public final long deaths;
        public final long soloWins;
        public final long teamWins;
        public final long wins;
        public final long games;
        public final long coins;
        public final long timePlayedSeconds;
        public final double kd;
        public final double wlr;
        public final double winRate;
        public final List<Kit> kits;
        public final Cosmetics cosmetics;
        public ActivitySummary overallActivity = ActivitySummary.UNKNOWN;

        private Snapshot(String username, String uuid, String fetchedAt, String lastChecked,
                         long kills, long soloKills, long teamKills, long deaths,
                         long soloWins, long teamWins, long wins, long games, long coins,
                         long timePlayedSeconds, double kd, double wlr, double winRate, List<Kit> kits) {
            this(username, uuid, fetchedAt, lastChecked, kills, soloKills, teamKills, deaths,
                    soloWins, teamWins, wins, games, coins, timePlayedSeconds, kd, wlr,
                    winRate, kits, Cosmetics.UNKNOWN);
        }

        private Snapshot(String username, String uuid, String fetchedAt, String lastChecked,
                         long kills, long soloKills, long teamKills, long deaths,
                         long soloWins, long teamWins, long wins, long games, long coins,
                         long timePlayedSeconds, double kd, double wlr, double winRate,
                         List<Kit> kits, Cosmetics cosmetics) {
            this.username = username;
            this.uuid = uuid;
            this.fetchedAt = fetchedAt;
            this.lastChecked = lastChecked;
            this.kills = kills;
            this.soloKills = soloKills;
            this.teamKills = teamKills;
            this.deaths = deaths;
            this.soloWins = soloWins;
            this.teamWins = teamWins;
            this.wins = wins;
            this.games = games;
            this.coins = coins;
            this.timePlayedSeconds = timePlayedSeconds;
            this.kd = kd;
            this.wlr = wlr;
            this.winRate = winRate;
            this.kits = Collections.unmodifiableList(new ArrayList<Kit>(kits));
            this.cosmetics = cosmetics == null ? Cosmetics.UNKNOWN : cosmetics;
        }
    }

    /** Gains between two saved observations. -1 means the counter was unavailable, never zero-fill it. */
    public static final class Activity {
        public static final Activity UNKNOWN = new Activity(-1L, -1L, -1L, -1L,
                -1L, -1L, -1L, -1L, "", "");
        public final long kills, games, wins, timePlayedSeconds;
        public final long levelBefore, levelAfter, prestigeBefore, prestigeAfter;
        public final String source, baselineSource;

        private Activity(long kills, long games, long wins, long timePlayedSeconds,
                         long levelBefore, long levelAfter, long prestigeBefore, long prestigeAfter,
                         String source, String baselineSource) {
            this.kills = kills; this.games = games; this.wins = wins;
            this.timePlayedSeconds = timePlayedSeconds;
            this.levelBefore = levelBefore; this.levelAfter = levelAfter;
            this.prestigeBefore = prestigeBefore; this.prestigeAfter = prestigeAfter;
            this.source = source; this.baselineSource = baselineSource;
        }

        private static long gain(JsonObject data, String key) {
            try {
                JsonElement value = data.get(key);
                return value != null && value.isJsonPrimitive() && value.getAsLong() >= 0L
                        ? value.getAsLong() : -1L;
            } catch (Exception ignored) { return -1L; }
        }

        private static Activity parse(JsonObject data) {
            JsonObject delta = object(data, "activity_deltas");
            return new Activity(gain(delta, "kills"), gain(delta, "games"), gain(delta, "wins"),
                    gain(delta, "time_played"), gain(data, "activity_level_before"),
                    gain(data, "activity_level_after"), gain(data, "activity_prestige_before"),
                    gain(data, "activity_prestige_after"), string(data, "activity_source", ""),
                    string(data, "activity_baseline_source", ""));
        }

        private JsonObject toJson() {
            JsonObject result = new JsonObject();
            if (kills >= 0) result.addProperty("kills", kills);
            if (games >= 0) result.addProperty("games", games);
            if (wins >= 0) result.addProperty("wins", wins);
            if (timePlayedSeconds >= 0) result.addProperty("time_played", timePlayedSeconds);
            return result;
        }
    }

    /** Overall profile counter gains between the two latest comparable GoStats observations. */
    public static final class ActivitySummary {
        public static final ActivitySummary UNKNOWN = new ActivitySummary(-1L, -1L, -1L, -1L,
                -1L, -1L, -1L, -1L, -1L, -1L, "", "");
        public final long kills;
        public final long soloKills;
        public final long teamKills;
        public final long deaths;
        public final long soloWins;
        public final long teamWins;
        public final long wins;
        public final long games;
        public final long coins;
        public final long timePlayedSeconds;
        public final String before;
        public final String after;

        private ActivitySummary(long kills, long soloKills, long teamKills, long deaths,
                                long soloWins, long teamWins, long wins, long games,
                                long coins, long timePlayedSeconds, String before, String after) {
            this.kills = kills;
            this.soloKills = soloKills;
            this.teamKills = teamKills;
            this.deaths = deaths;
            this.soloWins = soloWins;
            this.teamWins = teamWins;
            this.wins = wins;
            this.games = games;
            this.coins = coins;
            this.timePlayedSeconds = timePlayedSeconds;
            this.before = before == null ? "" : before;
            this.after = after == null ? "" : after;
        }

        public boolean isAvailable() {
            return !before.isEmpty() && !after.isEmpty()
                    && (kills >= 0L || soloKills >= 0L || teamKills >= 0L || deaths >= 0L
                    || soloWins >= 0L || teamWins >= 0L || wins >= 0L || games >= 0L
                    || coins >= 0L || timePlayedSeconds >= 0L);
        }

        private static ActivitySummary parse(JsonObject data) {
            JsonObject deltas = object(data, "deltas");
            if (deltas.entrySet().isEmpty()) return UNKNOWN;
            return new ActivitySummary(gain(deltas, "kills"), gain(deltas, "solo_kills"),
                    gain(deltas, "team_kills"), gain(deltas, "deaths"),
                    gain(deltas, "solo_wins"), gain(deltas, "team_wins"), gain(deltas, "wins"),
                    gain(deltas, "games"), gain(deltas, "coins"), gain(deltas, "time_played"),
                    string(data, "before", ""), string(data, "after", ""));
        }

        private static long gain(JsonObject data, String key) {
            try {
                JsonElement value = data.get(key);
                return value == null || value.isJsonNull() ? -1L : value.getAsLong();
            } catch (Exception ignored) {
                return -1L;
            }
        }

        private JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("before", before);
            result.addProperty("after", after);
            JsonObject deltas = new JsonObject();
            if (kills >= 0L) deltas.addProperty("kills", kills);
            if (soloKills >= 0L) deltas.addProperty("solo_kills", soloKills);
            if (teamKills >= 0L) deltas.addProperty("team_kills", teamKills);
            if (deaths >= 0L) deltas.addProperty("deaths", deaths);
            if (soloWins >= 0L) deltas.addProperty("solo_wins", soloWins);
            if (teamWins >= 0L) deltas.addProperty("team_wins", teamWins);
            if (wins >= 0L) deltas.addProperty("wins", wins);
            if (games >= 0L) deltas.addProperty("games", games);
            if (coins >= 0L) deltas.addProperty("coins", coins);
            if (timePlayedSeconds >= 0L) deltas.addProperty("time_played", timePlayedSeconds);
            result.add("deltas", deltas);
            return result;
        }
    }

    public static final class Kit {
        public final String name;
        public final boolean owned;
        public final long level;
        public final long prestige;
        public final long kills;
        public final long deaths;
        public final long wins;
        public final long games;
        public final double kd;
        public final double wlr;
        /** True when the source had enough per-kit data to calculate trustworthy ratios. */
        public final boolean ratiosAvailable;
        /** Upper bound of an observed activity interval, not an exact match time. */
        public final long lastActivityAt;
        public final String activityAfter;
        public final String activityBefore;
        public final Activity activity;

        private Kit(String name, boolean owned, long level, long prestige, long kills, long deaths,
                    long wins, long games, double kd, double wlr, boolean ratiosAvailable) {
            this(name, owned, level, prestige, kills, deaths, wins, games, kd, wlr,
                    ratiosAvailable, 0L, "", "");
        }

        private Kit(String name, boolean owned, long level, long prestige, long kills, long deaths,
                    long wins, long games, double kd, double wlr, boolean ratiosAvailable,
                    long lastActivityAt, String activityAfter, String activityBefore) {
            this(name, owned, level, prestige, kills, deaths, wins, games, kd, wlr, ratiosAvailable,
                    lastActivityAt, activityAfter, activityBefore, Activity.UNKNOWN);
        }

        private Kit(String name, boolean owned, long level, long prestige, long kills, long deaths,
                    long wins, long games, double kd, double wlr, boolean ratiosAvailable,
                    long lastActivityAt, String activityAfter, String activityBefore, Activity activity) {
            this.name = name;
            this.owned = owned;
            this.level = level;
            this.prestige = prestige;
            this.kills = kills;
            this.deaths = deaths;
            this.wins = wins;
            this.games = games;
            this.kd = kd;
            this.wlr = wlr;
            this.ratiosAvailable = ratiosAvailable;
            this.lastActivityAt = lastActivityAt;
            this.activityAfter = activityAfter;
            this.activityBefore = activityBefore;
            this.activity = activity;
        }

        public long getKills() {
            return kills;
        }

        public long getLastActivityAt() { return lastActivityAt; }
    }
}
