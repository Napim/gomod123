package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only, paginated client for the public GoStats Blitz leaderboard. */
public final class GoStatsLeaderboardClient {
    private static final String DEFAULT_BASE_URL = "https://gostats-discord-bot.onrender.com";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    // Unindexed kit metrics and busy Render Free instances can need more than one
    // short network window to finish a leaderboard sort. Keep this off the render thread.
    private static final int READ_TIMEOUT_MS = 60000;
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "gomod-gostats-leaderboard");
            thread.setDaemon(true);
            return thread;
        }
    });
    // Name-to-rank searches must not sit behind a large leaderboard page or Top-N batch.
    private static final ExecutorService PLAYER_SEARCH_REQUESTS = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger threadNumber = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "gomod-gostats-player-search-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final AtomicInteger PLAYER_SEARCH_REQUEST_ID = new AtomicInteger();
    // Fast player-name autocomplete must not wait behind a slow leaderboard sort/page request.
    private static final ExecutorService SUGGESTION_REQUESTS = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "gomod-gostats-player-suggestions");
            thread.setDaemon(true);
            return thread;
        }
    });
    // Cosmetic filter choices are small and independent of leaderboard queries. Keep them on
    // their own worker so a slow leaderboard response cannot leave the filter slider empty.
    private static final ExecutorService FILTER_CATALOG_REQUESTS = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "gomod-gostats-filter-catalog");
            thread.setDaemon(true);
            return thread;
        }
    });
    private static volatile KitFilterCatalog FILTER_CATALOG_CACHE = bundledFilterCatalog();
    private static final AtomicInteger SUGGESTION_REQUEST_ID = new AtomicInteger();

    private GoStatsLeaderboardClient() {
    }

    /** Network work runs away from the render thread; results are returned to the supplied callback. */
    public static void fetchPage(final int offset, final String scope, final String metric,
                                 final String kit, final String search, final int limit,
                                 final Callback callback) {
        fetchPage(offset, scope, metric, kit, search, limit, null, callback);
    }

    public static void fetchPage(final int offset, final String scope, final String metric,
                                 final String kit, final String search, final int limit,
                                 final KitFilters filters, final Callback callback) {
        fetchPageInternal(offset, scope, metric, kit, search, limit, filters, -1L, callback);
    }

    /** Batch target pages are tagged so every gostats123 request is included in the refresh log. */
    public static void fetchPageForBatchRefresh(final int offset, final String scope, final String metric,
                                                final String kit, final String search, final int limit,
                                                final KitFilters filters, final long debugJobId,
                                                final Callback callback) {
        fetchPageInternal(offset, scope, metric, kit, search, limit, filters, debugJobId, callback);
    }

    /**
     * Loads a large top-N selection in bounded leaderboard pages. The public API caps
     * each page at 500 rows, so Top 700–950 needs a second page. Rows already loaded
     * at offset zero are reused to avoid fetching the first page twice.
     */
    public static void fetchTopPlayersForBatchRefresh(final String scope, final String metric,
                                                      final String kit, final int requestedCount,
                                                      final KitFilters filters, final long debugJobId,
                                                      final List<Player> initialRows, final int initialOffset,
                                                      final int initialTotal, final Callback callback) {
        if (callback == null) return;
        final int targetCount = Math.max(1, Math.min(950, requestedCount));
        final String safeScope = normalizeScope(scope);
        final String safeMetric = normalizeMetric(metric);
        final String safeKit = safeText(kit, 64);
        final List<Player> preloaded = initialRows == null
                ? Collections.<Player>emptyList() : new ArrayList<Player>(initialRows);
        final int preloadedOffset = preloaded.isEmpty() ? 0 : Math.max(preloaded.size(), initialOffset);
        final int preloadedTotal = Math.max(0, initialTotal);
        REQUESTS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Player> players = new ArrayList<Player>(targetCount);
                    Set<String> seen = new HashSet<String>();
                    appendUniquePlayers(players, seen, preloaded, targetCount);
                    int offset = preloadedOffset;
                    int total = preloadedTotal;
                    String generatedAt = "";
                    while (players.size() < targetCount && (total <= 0 || offset < total)) {
                        int limit = Math.min(MAX_PAGE_SIZE, targetCount - players.size());
                        Page page = readPage(offset, safeScope, safeMetric, safeKit, "", limit,
                                filters, debugJobId);
                        if (page == null) throw new Exception("gostats123 returned no leaderboard page");
                        if (page.total > 0 || total <= 0) total = page.total;
                        if (generatedAt.isEmpty()) generatedAt = page.generatedAt;
                        int received = page.players.size();
                        if (received == 0) break;
                        appendUniquePlayers(players, seen, page.players, targetCount);
                        int nextOffset = page.offset + received;
                        if (nextOffset <= offset) break;
                        offset = nextOffset;
                    }
                    callback.onComplete(new Page(0, total, generatedAt, players), null);
                } catch (Exception error) {
                    callback.onComplete(null, safeMessage(error));
                }
            }
        });
    }

    private static void appendUniquePlayers(List<Player> target, Set<String> seen,
                                            List<Player> source, int limit) {
        if (source == null) return;
        for (Player player : source) {
            if (player == null || player.uuid == null || player.uuid.trim().isEmpty()) continue;
            String uuid = player.uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
            if (uuid.matches("[a-f0-9]{32}") && seen.add(uuid)) target.add(player);
            if (target.size() >= limit) return;
        }
    }

    private static void fetchPageInternal(final int offset, final String scope, final String metric,
                                          final String kit, final String search, final int limit,
                                          final KitFilters filters, final long debugJobId,
                                          final Callback callback) {
        if (callback == null) return;
        final int safeOffset = Math.max(0, offset);
        final int safeLimit = Math.max(1, Math.min(MAX_PAGE_SIZE,
                limit <= 0 ? DEFAULT_PAGE_SIZE : limit));
        final String safeScope = normalizeScope(scope);
        final String safeMetric = normalizeMetric(metric);
        final String safeKit = safeText(kit, 64);
        final String safeSearch = safeText(search, 64);
        REQUESTS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Page page = readPage(safeOffset, safeScope, safeMetric, safeKit, safeSearch,
                            safeLimit, filters, debugJobId);
                    callback.onComplete(page, null);
                } catch (Exception error) {
                    callback.onComplete(null, safeMessage(error));
                }
            }
        });
    }

    /** Finds the closest !value or #rank match and returns a normal page centered around it. */
    public static void fetchPageAroundSearch(final String scope, final String metric,
                                             final String kit, final String search, final int limit,
                                             final Callback callback) {
        fetchPageAroundSearch(scope, metric, kit, search, limit, null, callback);
    }

    public static void fetchPageAroundSearch(final String scope, final String metric,
                                             final String kit, final String search, final int limit,
                                             final KitFilters filters, final Callback callback) {
        if (callback == null) return;
        final String safeScope = normalizeScope(scope);
        final String safeMetric = normalizeMetric(metric);
        final String safeKit = safeText(kit, 64);
        final String safeSearch = safeText(search, 64);
        final int safeLimit = Math.max(1, Math.min(MAX_PAGE_SIZE,
                limit <= 0 ? DEFAULT_PAGE_SIZE : limit));
        if (!(safeSearch.startsWith("!") || safeSearch.startsWith("#"))) {
            fetchPage(0, safeScope, safeMetric, safeKit, safeSearch, safeLimit, filters, callback);
            return;
        }
        REQUESTS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Page matchPage = readPage(0, safeScope, safeMetric, safeKit, safeSearch, 1, filters);
                    if (matchPage.players.isEmpty()) {
                        callback.onComplete(matchPage, null);
                        return;
                    }
                    Player match = matchPage.players.get(0);
                    Page totals = readPage(0, safeScope, safeMetric, safeKit, "", 1, filters);
                    int total = Math.max(0, totals.total);
                    int windowSize = Math.min(safeLimit, total);
                    int offset = Math.max(0, match.rank - 1 - windowSize / 2);
                    offset = Math.min(Math.max(0, total - windowSize), offset);
                    Page window = readPage(offset, safeScope, safeMetric, safeKit, "", safeLimit, filters);
                    callback.onComplete(new Page(window.offset, window.total, window.generatedAt,
                            window.players, match.uuid), null);
                } catch (Exception error) {
                    callback.onComplete(null, safeMessage(error));
                }
            }
        });
    }

    /** Resolves an exact IGN and returns the normal leaderboard page around its actual rank. */
    public static void fetchPageAroundName(final String scope, final String metric,
                                           final String kit, final String username, final int limit,
                                           final KitFilters filters, final Callback callback) {
        if (callback == null) return;
        final String safeName = safeText(username, 16);
        if (!safeName.matches("[A-Za-z0-9_]{2,16}")) {
            fetchPage(0, scope, metric, kit, username, limit, filters, callback);
            return;
        }
        final int safeLimit = Math.max(1, Math.min(MAX_PAGE_SIZE,
                limit <= 0 ? DEFAULT_PAGE_SIZE : limit));
        final int requestId = PLAYER_SEARCH_REQUEST_ID.incrementAndGet();
        PLAYER_SEARCH_REQUESTS.execute(new Runnable() {
            @Override
            public void run() {
                if (requestId != PLAYER_SEARCH_REQUEST_ID.get()) return;
                try {
                    Page page = readPage(0, normalizeScope(scope), normalizeMetric(metric),
                            safeText(kit, 64), "", safeLimit, filters, safeName, -1L);
                    if (requestId == PLAYER_SEARCH_REQUEST_ID.get()) callback.onComplete(page, null);
                } catch (Exception error) {
                    if (requestId == PLAYER_SEARCH_REQUEST_ID.get()) {
                        callback.onComplete(null, safeMessage(error));
                    }
                }
            }
        });
    }

    /** Fetches a small, cancelable set of IGN matches for the leaderboard search box. */
    public static void fetchSuggestions(final String scope, final String metric, final String kit,
                                        final String search, final Callback callback) {
        fetchSuggestions(scope, metric, kit, search, null, callback);
    }

    public static void fetchSuggestions(final String scope, final String metric, final String kit,
                                        final String search, final KitFilters filters,
                                        final Callback callback) {
        if (callback == null) return;
        final String safeSearch = safeText(search, 16);
        if (!safeSearch.matches("[A-Za-z0-9_]{2,16}")) return;
        final int requestId = SUGGESTION_REQUEST_ID.incrementAndGet();
        SUGGESTION_REQUESTS.execute(new Runnable() {
            @Override
            public void run() {
                if (requestId != SUGGESTION_REQUEST_ID.get()) return;
                try {
                    // Suggestions are global, prefix-indexed lookups, independent of the
                    // active scope and filters. They do not queue behind leaderboard pages.
                    Page page = readSuggestionsPage(safeSearch, 5);
                    if (requestId == SUGGESTION_REQUEST_ID.get()) callback.onComplete(page, null);
                } catch (Exception error) {
                    if (requestId == SUGGESTION_REQUEST_ID.get()) {
                        callback.onComplete(null, safeMessage(error));
                    }
                }
            }
        });
    }

    /** Invalidates any queued or in-flight search-box suggestion response. */
    public static void cancelSuggestions() {
        SUGGESTION_REQUEST_ID.incrementAndGet();
    }

    public static void fetchKitFilterCatalog(final KitFilterCatalogCallback callback) {
        if (callback == null) return;
        FILTER_CATALOG_REQUESTS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    KitFilterCatalog catalog = readKitFilterCatalog();
                    FILTER_CATALOG_CACHE = catalog;
                    callback.onComplete(catalog, null);
                } catch (Exception error) {
                    // The bundled catalog remains usable if gostats123 is slow or unavailable.
                    callback.onComplete(FILTER_CATALOG_CACHE, safeMessage(error));
                }
            }
        });
    }

    public static KitFilterCatalog cachedKitFilterCatalog() {
        return FILTER_CATALOG_CACHE;
    }

    private static KitFilterCatalog bundledFilterCatalog() {
        return new KitFilterCatalog(
                java.util.Arrays.asList("CREEPER_FIREWORK", "EXPLOSIONS", "GRAVESTONE", "INFESTATION",
                        "LIGHTNING_STRIKE", "RABBIT_GIFT", "REKT_HOLOGRAM", "SQUID_MISSILE"),
                java.util.Arrays.asList("BLAZING_SPEED", "COLORFUL_CHICKEN_RAIN", "CREEPER", "DRAGON_RIDER",
                        "EARTH", "LEAPING_BUNNY", "METEOR_SHOWER", "PLAYER_FIREWORK"),
                java.util.Arrays.asList("BLUE_PARTICLE", "DAYLIGHT_PARTICLE", "DRAGON_EGG_PARTICLE",
                        "DUST_PARTICLE", "ENCHANTING_PARTICLE", "FLOWER_TRAIL", "GREEN_PARTICLE",
                        "LAVA_PARTICLE", "MYCELIUM_TRAIL", "NETHERRACK_TRAIL", "PORTAL_PARTICLE",
                        "RAINBOW_PARTICLE", "RED_PARTICLE", "SLIME_PARTICLE", "SNOW_PARTICLE",
                        "TRON_BLUE_TRAIL", "TRON_GREEN_TRAIL", "TRON_RAINBOW_TRAIL", "TRON_RED_TRAIL",
                        "WATER_PARTICLE", "WHEAT_TRAIL"),
                java.util.Arrays.asList("BAT_DUDE", "CHICKEN_DANCE", "COOKIE", "FIREWORK", "I_REFUSE",
                        "KILLER_BUNNY", "PIG_DANCE", "PRESTIGE", "RICH_JAMES", "SHEEP_PARADE",
                        "VILLAGER_MUSIC_BAND", "WOLF_PACK"),
                java.util.Arrays.asList("SPEED", "RESISTANCE", "REGENERATION", "GRAVEDIGGER",
                        "FLAMING_ARROWS", "LEVEL_UP", "RANDOM"), false);
    }

    private static KitFilterCatalog readKitFilterCatalog() throws Exception {
        String base = System.getProperty("gomod123.gostatsUrl", DEFAULT_BASE_URL);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/blitz/data/kit-filters").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "gomod123-leaderboard");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new Exception(httpFailure("kit filters", status, readError(connection.getErrorStream())));
            }
            try (InputStream input = connection.getInputStream();
                 InputStreamReader inputReader = new InputStreamReader(input, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(inputReader)) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (parsed == null || !parsed.isJsonObject())
                    throw new Exception("gostats123 returned invalid kit filters");
                JsonObject filters = object(parsed.getAsJsonObject(), "filters");
                List<String> killEffects = strings(filters, "kill_effect");
                return new KitFilterCatalog(strings(filters, "finisher"), strings(filters, "victory"),
                        strings(filters, "aura"), strings(filters, "taunt"),
                        killEffects, !killEffects.isEmpty());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<String> strings(JsonObject root, String key) {
        JsonArray values = array(root, key);
        List<String> result = new ArrayList<String>(values.size());
        for (JsonElement value : values) {
            if (value == null || value.isJsonNull()) continue;
            String text = value.getAsString();
            if (!text.trim().isEmpty() && !result.contains(text)) result.add(text);
        }
        return result;
    }

    /**
     * Reads one refreshed player from the public detail endpoint. Call from a worker thread; this avoids
     * reloading every leaderboard page when a single row is updated.
     */
    public static Player fetchPlayer(String uuid, String scope, String metric, String kit,
                                    int fallbackRank) throws Exception {
        String normalizedUuid = uuid == null ? "" : uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
        if (!normalizedUuid.matches("[a-f0-9]{32}")) throw new Exception("Invalid player UUID");
        String base = System.getProperty("gomod123.gostatsUrl", DEFAULT_BASE_URL);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + "/blitz/data/player/" + encode(normalizedUuid));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "gomod123-leaderboard");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                String detail = readError(connection.getErrorStream());
                throw new Exception(httpFailure("player data", status, detail));
            }
            try (InputStream input = connection.getInputStream();
                 InputStreamReader inputReader = new InputStreamReader(input, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(inputReader)) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (parsed == null || !parsed.isJsonObject())
                    throw new Exception("gostats123 returned invalid player data");
                return parsePlayerDetail(parsed.getAsJsonObject(), normalizedUuid,
                        normalizeScope(scope), normalizeMetric(metric), safeText(kit, 64), fallbackRank);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Player parsePlayerDetail(JsonObject root, String uuid, String scope, String metric,
                                            String kit, int fallbackRank) throws Exception {
        JsonObject selected = root;
        if ("kit".equals(scope)) {
            String wantedKit = normalizeKitKey(kit);
            selected = null;
            for (JsonElement element : array(root, "kits")) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject candidate = element.getAsJsonObject();
                if (wantedKit.equals(normalizeKitKey(string(candidate, "kit", "")))) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) throw new Exception("gostats123 has no saved " + kit + " profile for this player");
        }
        JsonObject rank = object(selected, "rank_info");
        JsonObject stats = object(selected, "stats");
        String valueKey = metric;
        if ("solo".equals(scope)) {
            if ("kills".equals(metric)) valueKey = "solo_kills";
            if ("wins".equals(metric)) valueKey = "solo_wins";
        } else if ("teams".equals(scope)) {
            if ("kills".equals(metric)) valueKey = "team_kills";
            if ("wins".equals(metric)) valueKey = "team_wins";
        }
        double metricValue = decimal(stats, valueKey);
        long kills = stats.has("overall_kills") ? number(stats, "overall_kills") : number(stats, "kills");
        long wins = stats.has("overall_wins") ? number(stats, "overall_wins") : number(stats, "wins");
        Player player = new Player(Math.max(1, fallbackRank), uuid,
                string(selected, "username", string(root, "username", "Unknown")),
                string(rank, "display", ""), string(rank, "tag", ""),
                parseColor(string(rank, "color", "")), string(rank, "rank_plus_color", ""),
                string(rank, "monthly_rank_color", ""), string(selected, "last_checked", "never"),
                string(selected, "fetched_at", ""),
                string(selected, "community_observed_at", string(root, "community_observed_at", "")),
                "kit".equals(scope) ? string(selected, "kit", kit) : "",
                number(stats, "kill_counter_enabled") != 0L,
                kills,
                number(stats, "solo_kills"), number(stats, "team_kills"),
                wins, number(stats, "solo_wins"), number(stats, "team_wins"),
                number(stats, "deaths"), number(stats, "games"), number(stats, "coins"),
                number(stats, "time_played"), metricValue,
                "kit".equals(scope) ? number(stats, "level") : 0L,
                "kit".equals(scope) ? number(stats, "prestige") : 0L);
        return withSupplementalStats(player, supplementalStatsFromApi(stats, scope));
    }

    private static String normalizeKitKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static Player withRank(Player player, int rank) {
        if (player == null) return null;
        return withSupplementalStats(new Player(Math.max(1, rank), player.uuid, player.username, player.rankDisplay,
                player.rankTag, player.rankColor, player.rankPlusColor, player.monthlyRankColor,
                player.lastChecked, player.fetchedAt, player.communityObservedAt, player.kit,
                player.killCounterEnabled, player.kills, player.soloKills,
                player.teamKills, player.wins, player.soloWins, player.teamWins, player.deaths,
                player.games, player.coins, player.timePlayed, player.metricValue,
                player.kitLevel, player.kitPrestige, player.lastLoginMs, player.lastLogoutMs,
                player.presenceStatus, player.lastAction, player.lastActionSeconds,
                player.refreshCacheLabel, player.lastGameType), player.supplementalStats);
    }

    /** Keeps the existing rank decoration when a just-submitted profile lacks rank metadata. */
    public static Player withRankInfoFallback(Player player, Player fallback) {
        if (player == null || fallback == null) return player;
        String rankDisplay = firstNonEmpty(player.rankDisplay, fallback.rankDisplay);
        String rankTag = firstNonEmpty(player.rankTag, fallback.rankTag);
        String rankPlusColor = firstNonEmpty(player.rankPlusColor, fallback.rankPlusColor);
        String monthlyRankColor = firstNonEmpty(player.monthlyRankColor, fallback.monthlyRankColor);
        int rankColor = player.rankColor == 0xFFFFFFFF && fallback.rankColor != 0xFFFFFFFF
                ? fallback.rankColor : player.rankColor;
        if (rankDisplay.equals(player.rankDisplay) && rankTag.equals(player.rankTag)
                && rankPlusColor.equals(player.rankPlusColor)
                && monthlyRankColor.equals(player.monthlyRankColor) && rankColor == player.rankColor) {
            return player;
        }
        return withSupplementalStats(new Player(player.rank, player.uuid, player.username, rankDisplay,
                rankTag, rankColor, rankPlusColor, monthlyRankColor, player.lastChecked, player.fetchedAt,
                player.communityObservedAt, player.kit, player.killCounterEnabled, player.kills,
                player.soloKills, player.teamKills, player.wins, player.soloWins,
                player.teamWins, player.deaths, player.games, player.coins,
                player.timePlayed, player.metricValue, player.kitLevel, player.kitPrestige,
                player.lastLoginMs, player.lastLogoutMs, player.presenceStatus,
                player.lastAction, player.lastActionSeconds, player.refreshCacheLabel,
                player.lastGameType), player.supplementalStats);
    }

    public static Player withPresence(Player player, String status, String action,
                                      long lastLoginMs, long lastLogoutMs, long actionSeconds,
                                      String lastGameType) {
        if (player == null) return null;
        return withSupplementalStats(new Player(player.rank, player.uuid, player.username, player.rankDisplay,
                player.rankTag, player.rankColor, player.rankPlusColor, player.monthlyRankColor,
                player.lastChecked, player.fetchedAt, player.communityObservedAt, player.kit,
                player.killCounterEnabled, player.kills, player.soloKills, player.teamKills,
                player.wins, player.soloWins, player.teamWins, player.deaths, player.games,
                player.coins, player.timePlayed, player.metricValue, player.kitLevel,
                player.kitPrestige, lastLoginMs, lastLogoutMs, status, action, actionSeconds,
                player.refreshCacheLabel, lastGameType), player.supplementalStats);
    }

    /** Keeps a recent profile usable for the leaderboard without presenting it as a new refresh. */
    public static Player withoutRefreshCacheLabel(Player player) {
        if (player == null || player.refreshCacheLabel.isEmpty()) return player;
        return withSupplementalStats(new Player(player.rank, player.uuid, player.username, player.rankDisplay,
                player.rankTag, player.rankColor, player.rankPlusColor, player.monthlyRankColor,
                player.lastChecked, player.fetchedAt, player.communityObservedAt, player.kit,
                player.killCounterEnabled, player.kills, player.soloKills, player.teamKills,
                player.wins, player.soloWins, player.teamWins, player.deaths, player.games,
                player.coins, player.timePlayed, player.metricValue, player.kitLevel,
                player.kitPrestige, player.lastLoginMs, player.lastLogoutMs,
                player.presenceStatus, player.lastAction, player.lastActionSeconds, "",
                player.lastGameType), player.supplementalStats);
    }

    /** Builds the updated row from the fresh Hypixel profile already fetched for this refresh. */
    public static Player fromFreshHypixelProfile(Player previous, JsonObject profile,
                                                 String scope, String metric, String kit) throws Exception {
        if (previous == null || profile == null) throw new Exception("Missing player profile for leaderboard update");
        JsonObject stats = object(object(profile, "stats"), "HungerGames");
        if (stats.entrySet().isEmpty()) throw new Exception("Hypixel returned no Blitz stats for this player");

        String normalizedScope = normalizeScope(scope);
        String normalizedMetric = normalizeMetric(metric);
        String uuid = string(profile, "uuid", previous.uuid).replace("-", "");
        String username = string(profile, "displayname", previous.username);
        long kills = number(stats, "kills");
        long teamKills = number(stats, "kills_teams_normal");
        long soloKills = Math.max(0L, kills - teamKills);
        long soloWins = number(stats, "wins");
        long teamWins = number(stats, "wins_teams");
        long wins = soloWins + teamWins;
        long deaths = number(stats, "deaths");
        long games = number(stats, "games_played");
        long coins = number(stats, "coins");
        long timePlayed = number(stats, "time_played");
        boolean killCounterEnabled = freshKillCounterEnabled(stats);

        long level = 0L;
        long prestige = 0L;
        double metricValue;
        String selectedKit = "kit".equals(normalizedScope) ? safeText(kit, 64) : previous.kit;
        if ("kit".equals(normalizedScope)) {
            if (selectedKit.trim().isEmpty()) throw new Exception("No kit is selected for this update");
            String fieldKit = selectedKit.toLowerCase(Locale.ROOT).trim();
            long kitKills = kitNumber(stats, "kills_", fieldKit, 0L);
            long kitSoloWins = kitNumber(stats, "wins_", fieldKit, 0L);
            long kitTeamWins = kitNumber(stats, "wins_teams_", fieldKit, 0L);
            long kitWins = kitSoloWins + kitTeamWins;
            long kitGames = kitNumber(stats, "games_played_", fieldKit, 0L);
            long kitDeaths = Math.max(0L, kitGames - kitWins);
            long kitExperience = kitNumber(stats, "exp_", fieldKit, 0L);
            prestige = kitNumber(stats, "p", fieldKit, 0L);
            level = freshKitLevel(stats, fieldKit, kitExperience, kitGames, prestige);
            metricValue = freshKitMetric(stats, fieldKit, normalizedMetric,
                    kitKills, kitSoloWins, kitTeamWins, kitWins, kitDeaths,
                    kitGames, kitExperience, level, prestige);
        } else if ("solo".equals(normalizedScope)) {
            if ("wins".equals(normalizedMetric)) metricValue = soloWins;
            else if ("solo_chaos_kills".equals(normalizedMetric))
                metricValue = number(stats, "kills_solo_chaos");
            else if ("solo_chaos_wins".equals(normalizedMetric))
                metricValue = number(stats, "wins_solo_chaos");
            else metricValue = soloKills;
        } else if ("teams".equals(normalizedScope)) {
            metricValue = "wins".equals(normalizedMetric) ? teamWins : teamKills;
        } else {
            metricValue = freshOverallMetric(normalizedMetric, stats, kills, wins, deaths,
                    games, coins, timePlayed);
        }

        long observedAt = HypixelPlayerRequests.observedAt(profile);
        String fetchedAt = Instant.ofEpochMilli(observedAt > 0L ? observedAt : System.currentTimeMillis()).toString();
        Player refreshed = new Player(Math.max(1, previous.rank), uuid, username,
                previous.rankDisplay, previous.rankTag, previous.rankColor,
                previous.rankPlusColor, previous.monthlyRankColor,
                fetchedAt, fetchedAt, fetchedAt, selectedKit, killCounterEnabled,
                kills, soloKills, teamKills, wins, soloWins, teamWins, deaths, games,
                coins, timePlayed, metricValue, level, prestige,
                number(profile, "lastLogin"), number(profile, "lastLogout"),
                previous.presenceStatus, previous.lastAction, previous.lastActionSeconds,
                HypixelPlayerRequests.isCached(profile) ? HypixelPlayerRequests.cacheLabel(profile) : "",
                string(profile, "mostRecentGameType", ""));
        return withSupplementalStats(refreshed,
                freshSupplementalStats(stats, games, normalizedScope, selectedKit));
    }

    private static double freshOverallMetric(String metric, JsonObject stats, long kills, long wins,
                                             long deaths, long games, long coins, long timePlayed) {
        long blitzStarsUsed = number(stats, "blitz_uses");
        if ("kills".equals(metric)) return kills;
        if ("wins".equals(metric)) return wins;
        if ("deaths".equals(metric)) return deaths;
        if ("kdr".equals(metric)) return kills / (double) Math.max(1L, deaths);
        if ("wlr".equals(metric)) return wins / (double) Math.max(1L, deaths);
        if ("games".equals(metric)) return games;
        if ("coins".equals(metric)) return coins;
        if ("time_played".equals(metric)) return timePlayed;
        if ("playtime_per_game".equals(metric)) return games > 0L ? timePlayed / (double) games : 0.0D;
        if ("blitz_stars_used".equals(metric)) return blitzStarsUsed;
        if ("blitz_stars_per_game".equals(metric))
            return games > 0L ? blitzStarsUsed / (double) games : 0.0D;
        if ("prestige_2".equals(metric)) return freshOverallPrestigeTwoCount(stats);
        if ("items_enchanted_tim".equals(metric)) return number(stats, "items_enchanted_tim");
        if ("blocks_traveled_pig".equals(metric)) return number(stats, "blocks_traveled_pig_meatmaster");
        if ("blocks_traveled_boat".equals(metric)) return number(stats, "blocks_traveled_boat_viking");
        if ("blocks_traveled_minecart".equals(metric))
            return number(stats, "blocks_traveled_minecart_hype train");
        if ("blocks_traveled_horse".equals(metric)) return number(stats, "blocks_traveled_horse_horsetamer");
        if ("chests_opened".equals(metric)) return number(stats, "chests_opened");
        if ("experience".equals(metric)) return freshOverallKitTotal(stats, "exp_");
        if ("exp_per_game".equals(metric)) {
            long kitExperience = freshOverallKitTotal(stats, "exp_");
            return games > 0L ? kitExperience / (double) games : 0.0D;
        }
        if ("damage_ratio".equals(metric)) {
            double damageDealt = decimal(stats, "damage");
            double damageTaken = decimal(stats, "damage_taken");
            return damageTaken > 0.0D ? damageDealt / damageTaken * 100.0D
                    : damageDealt > 0.0D ? 100.0D : 0.0D;
        }
        if ("taunt_kills".equals(metric)) return number(stats, "taunt_kills");
        if ("mobs_spawned".equals(metric)) return number(stats, "mobs_spawned");
        if ("potions_drunk".equals(metric)) return number(stats, "potions_drunk");
        if ("potions_thrown".equals(metric)) return number(stats, "potions_thrown");
        return 0.0D;
    }

    private static long freshOverallKitTotal(JsonObject stats, String prefix) {
        long total = 0L;
        for (String kit : freshOverallKitNames(stats)) {
            total += kitNumber(stats, prefix, kit, 0L);
        }
        return total;
    }

    private static long freshOverallPrestigeTwoCount(JsonObject stats) {
        long count = 0L;
        for (String kit : freshOverallKitNames(stats)) {
            if (kitNumber(stats, "p", kit, 0L) >= 2L) count++;
        }
        return count;
    }

    private static List<String> freshOverallKitNames(JsonObject stats) {
        List<String> kits = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (Map.Entry<String, JsonElement> entry : stats.entrySet()) {
            String key = entry.getKey();
            String prefix = key.startsWith("exp_") ? "exp_"
                    : key.startsWith("games_played_") ? "games_played_" : "";
            if (prefix.isEmpty()) continue;
            String kit = key.substring(prefix.length());
            String normalized = normalizeKitKey(kit);
            if (!normalized.isEmpty() && seen.add(normalized)) kits.add(kit);
        }
        return kits;
    }

    private static Map<String, Double> supplementalStatsFromApi(JsonObject stats, String scope) {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        String[] keys = {
                "blitz_stars_used", "blitz_stars_per_game",
                "blocks_traveled_pig", "blocks_traveled_boat",
                "blocks_traveled_minecart", "blocks_traveled_horse",
                "prestige_1_count", "prestige_2_count", "level_x_count",
                "solo_chaos_kills", "solo_chaos_wins"
        };
        if (stats == null) return values;
        for (String key : keys) {
            if (stats.has(key) && !stats.get(key).isJsonNull()) {
                values.put(key, decimal(stats, key));
            }
        }
        if ("kit".equals(scope)) {
            String[] kitKeys = {
                    "kills", "solo_wins", "team_wins", "wins", "deaths", "games",
                    "kdr", "wlr", "experience", "exp_per_game", "time_played",
                    "playtime_per_game", "arrows_hit", "arrows_fired", "arrow_accuracy",
                    "damage_dealt", "damage_taken", "damage_ratio", "damage_per_kill",
                    "taunt_kills", "taunt_ratio", "mobs_spawned", "mobs_spawned_per_game",
                    "chests_opened", "chests_per_game", "potions_drunk",
                    "potions_drunk_per_game", "potions_thrown", "potions_thrown_per_game"
            };
            for (String key : kitKeys) {
                if (stats.has(key) && !stats.get(key).isJsonNull()) {
                    values.put("kit_" + key, decimal(stats, key));
                }
            }
            if (stats.has("total_wins") && !stats.get("total_wins").isJsonNull()) {
                values.put("kit_total_wins", decimal(stats, "total_wins"));
            } else if (stats.has("wins") && !stats.get("wins").isJsonNull()) {
                values.put("kit_total_wins", decimal(stats, "wins"));
            }
        }
        return values;
    }

    private static Map<String, Double> freshSupplementalStats(JsonObject stats, long games,
                                                              String scope, String kit) {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        long stars = number(stats, "blitz_uses");
        values.put("blitz_stars_used", (double) stars);
        values.put("blitz_stars_per_game", games > 0L ? stars / (double) games : 0.0D);
        values.put("blocks_traveled_pig", (double) number(stats, "blocks_traveled_pig_meatmaster"));
        values.put("blocks_traveled_boat", (double) number(stats, "blocks_traveled_boat_viking"));
        values.put("blocks_traveled_minecart", (double) number(stats, "blocks_traveled_minecart_hype train"));
        values.put("blocks_traveled_horse", (double) number(stats, "blocks_traveled_horse_horsetamer"));
        values.put("solo_chaos_kills", (double) number(stats, "kills_solo_chaos"));
        values.put("solo_chaos_wins", (double) number(stats, "wins_solo_chaos"));
        values.put("prestige_1_count", (double) freshOverallPrestigeOneCount(stats));
        values.put("prestige_2_count", (double) freshOverallPrestigeTwoCount(stats));
        values.put("level_x_count", (double) freshOverallLevelXCount(stats));
        if ("kit".equals(scope)) values.putAll(freshKitSupplementalStats(stats, kit));
        return values;
    }

    private static Map<String, Double> freshKitSupplementalStats(JsonObject stats, String kit) {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        String selectedKit = safeText(kit, 64).toLowerCase(Locale.ROOT).trim();
        if (selectedKit.isEmpty()) return values;
        long kills = kitNumber(stats, "kills_", selectedKit, 0L);
        long soloWins = kitNumber(stats, "wins_", selectedKit, 0L);
        long teamWins = kitNumber(stats, "wins_teams_", selectedKit, 0L);
        long wins = soloWins + teamWins;
        long games = kitNumber(stats, "games_played_", selectedKit, 0L);
        long deaths = Math.max(0L, games - wins);
        long experience = kitNumber(stats, "exp_", selectedKit, 0L);
        long prestige = kitNumber(stats, "p", selectedKit, 0L);
        long level = freshKitLevel(stats, selectedKit, experience, games, prestige);
        String[] metrics = {
                "kills", "solo_wins", "team_wins", "wins", "deaths", "games",
                "kdr", "wlr", "experience", "exp_per_game", "time_played",
                "playtime_per_game", "arrows_hit", "arrows_fired", "arrow_accuracy",
                "damage_dealt", "damage_taken", "damage_ratio", "damage_per_kill",
                "taunt_kills", "taunt_ratio", "mobs_spawned", "mobs_spawned_per_game",
                "chests_opened", "chests_per_game", "potions_drunk",
                "potions_drunk_per_game", "potions_thrown", "potions_thrown_per_game"
        };
        for (String metric : metrics) {
            values.put("kit_" + metric, freshKitMetric(stats, selectedKit, metric,
                    kills, soloWins, teamWins, wins, deaths, games, experience, level, prestige));
        }
        values.put("kit_total_wins", (double) wins);
        return values;
    }

    private static long freshOverallPrestigeOneCount(JsonObject stats) {
        long count = 0L;
        for (String kit : freshOverallKitNames(stats)) {
            if (kitNumber(stats, "p", kit, 0L) == 1L) count++;
        }
        return count;
    }

    private static long freshOverallLevelXCount(JsonObject stats) {
        long count = 0L;
        for (String kit : freshOverallKitNames(stats)) {
            long prestige = kitNumber(stats, "p", kit, 0L);
            if (prestige != 0L) continue;
            long experience = kitNumber(stats, "exp_", kit, 0L);
            long games = kitNumber(stats, "games_played_", kit, 0L);
            if (freshKitLevel(stats, kit, experience, games, prestige) >= 10L) count++;
        }
        return count;
    }

    private static Player withSupplementalStats(Player player, Map<String, Double> stats) {
        if (player == null) return null;
        Map<String, Double> values = stats == null
                ? Collections.<String, Double>emptyMap() : stats;
        player.supplementalStats = Collections.unmodifiableMap(
                new LinkedHashMap<String, Double>(values));
        player.prestige1Count = Math.max(0L, Math.round(
                statValue(values, "prestige_1_count", player.prestige1Count)));
        player.prestige2Count = Math.max(0L, Math.round(
                statValue(values, "prestige_2_count", player.prestige2Count)));
        player.levelXCount = Math.max(0L, Math.round(
                statValue(values, "level_x_count", player.levelXCount)));
        return player;
    }

    private static double statValue(Map<String, Double> stats, String key, double fallback) {
        Double value = stats.get(key);
        return value == null ? fallback : value.doubleValue();
    }

    private static double freshKitMetric(JsonObject stats, String kit, String metric,
                                         long kills, long soloWins, long teamWins, long wins,
                                         long deaths, long games, long experience,
                                         long level, long prestige) {
        double damage = kitDecimal(stats, "damage_", kit);
        double damageTaken = kitDecimal(stats, "damage_taken_", kit);
        long arrowsHit = kitNumber(stats, "arrows_hit_", kit, 0L);
        long arrowsFired = kitNumber(stats, "arrows_fired_", kit, 0L);
        long tauntKills = kitNumber(stats, "taunt_kills_", kit, 0L);
        long mobsSpawned = kitNumber(stats, "mobs_spawned_", kit, 0L);
        long chestsOpened = kitNumber(stats, "chests_opened_", kit, 0L);
        long potionsDrunk = kitNumber(stats, "potions_drunk_", kit, 0L);
        long potionsThrown = kitNumber(stats, "potions_thrown_", kit, 0L);
        long timePlayed = kitNumber(stats, "time_played_", kit, 0L);
        switch (metric) {
            case "wins": return wins;
            case "solo_wins": return soloWins;
            case "team_wins": return teamWins;
            case "kills": return kills;
            case "deaths": return deaths;
            case "wlr": return wins / (double) Math.max(1L, deaths);
            case "kdr": return kills / (double) Math.max(1L, deaths);
            case "games": return games;
            case "prestige": return prestige;
            case "level": return level;
            case "experience": return experience;
            case "exp_per_game": return experience / (double) Math.max(1L, games);
            case "time_played": return timePlayed;
            case "playtime_per_game": return timePlayed / (double) Math.max(1L, games);
            case "arrows_hit": return arrowsHit;
            case "arrows_fired": return arrowsFired;
            case "arrow_accuracy": return arrowsFired > 0L ? arrowsHit / (double) arrowsFired * 100.0D : 0.0D;
            case "damage_dealt": return damage;
            case "damage_taken": return damageTaken;
            case "damage_ratio": return damageTaken > 0.0D ? damage / damageTaken * 100.0D : damage > 0.0D ? 100.0D : 0.0D;
            case "damage_per_kill": return kills > 0L ? damage / kills : 0.0D;
            case "taunt_kills": return tauntKills;
            case "taunt_ratio": return kills > 0L ? tauntKills / (double) kills * 100.0D : 0.0D;
            case "mobs_spawned": return mobsSpawned;
            case "mobs_spawned_per_game": return mobsSpawned / (double) Math.max(1L, games);
            case "chests_opened": return chestsOpened;
            case "chests_per_game": return chestsOpened / (double) Math.max(1L, games);
            case "potions_drunk": return potionsDrunk;
            case "potions_drunk_per_game": return potionsDrunk / (double) Math.max(1L, games);
            case "potions_thrown": return potionsThrown;
            case "potions_thrown_per_game": return potionsThrown / (double) Math.max(1L, games);
            default: return 0.0D;
        }
    }

    private static long freshKitLevel(JsonObject stats, String kit, long experience, long games, long prestige) {
        String normalizedKit = normalizeKitKey(kit);
        long level;
        if ("ranger".equals(normalizedKit) || "donkeytamer".equals(normalizedKit)
                || "warrior".equals(normalizedKit) || "phoenix".equals(normalizedKit)
                || "milkman".equals(normalizedKit)) {
            level = experience < 100L ? 1L : experience < 250L ? 2L : experience < 500L ? 3L
                    : experience < 1000L ? 4L : experience < 1500L ? 5L : experience < 2000L ? 6L
                    : experience < 2500L ? 7L : experience < 5000L ? 8L : experience < 10000L ? 9L : 10L;
        } else {
            long rawLevel = kitNumber(stats, "", kit, -1L);
            level = rawLevel >= 0L ? rawLevel + 1L : 0L;
        }
        if (prestige > 0L && level <= 0L) level = 10L;
        if (level <= 0L && games > 0L) level = 1L;
        if (isBasicKit(normalizedKit) && level <= 0L) level = 1L;
        return level;
    }

    private static boolean isBasicKit(String kit) {
        String normalizedKit = normalizeKitKey(kit);
        return "archer".equals(normalizedKit) || "meatmaster".equals(normalizedKit)
                || "speleologist".equals(normalizedKit) || "baker".equals(normalizedKit)
                || "knight".equals(normalizedKit) || "guardian".equals(normalizedKit)
                || "scout".equals(normalizedKit) || "hunter".equals(normalizedKit)
                || "hypetrain".equals(normalizedKit) || "fisherman".equals(normalizedKit)
                || "armorer".equals(normalizedKit) || "rambo".equals(normalizedKit);
    }

    private static long kitNumber(JsonObject stats, String prefix, String kit, long fallback) {
        for (String alias : kitAliases(kit)) {
            String key = prefix + alias;
            if (stats.has(key) && !stats.get(key).isJsonNull()) return number(stats, key);
        }
        return fallback;
    }

    private static double kitDecimal(JsonObject stats, String prefix, String kit) {
        for (String alias : kitAliases(kit)) {
            String key = prefix + alias;
            if (stats.has(key) && !stats.get(key).isJsonNull()) return decimal(stats, key);
        }
        return 0.0D;
    }

    private static List<String> kitAliases(String kit) {
        List<String> aliases = new ArrayList<String>(3);
        String normalized = kit == null ? "" : kit.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) aliases.add(normalized);
        String compact = normalized.replace(" ", "");
        if (!compact.isEmpty() && !aliases.contains(compact)) aliases.add(compact);
        String underscored = normalized.replace(' ', '_');
        if (!underscored.isEmpty() && !aliases.contains(underscored)) aliases.add(underscored);
        return aliases;
    }

    private static boolean freshKillCounterEnabled(JsonObject stats) {
        String[] keys = {"togglekillcounter", "displaykills", "display_kills", "displayKills",
                "showkills", "show_kills", "showKills"};
        for (String key : keys) {
            if (!stats.has(key) || stats.get(key).isJsonNull()) continue;
            JsonElement value = stats.get(key);
            try {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
                String text = value.getAsString().trim().toLowerCase(Locale.ROOT);
                return "1".equals(text) || "true".equals(text) || "yes".equals(text)
                        || "on".equals(text) || "enabled".equals(text);
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static String firstNonEmpty(String value, String fallback) {
        if (value != null && !value.trim().isEmpty()) return value;
        return fallback == null ? "" : fallback;
    }

    private static Page readPage(int offset, String scope, String metric,
                                 String kit, String search, int limit, KitFilters filters) throws Exception {
        return readPage(offset, scope, metric, kit, search, limit, filters, -1L);
    }

    private static Page readPage(int offset, String scope, String metric,
                                 String kit, String search, int limit, KitFilters filters,
                                 long debugJobId) throws Exception {
        return readPage(offset, scope, metric, kit, search, limit, filters, "", debugJobId);
    }

    private static Page readPage(int offset, String scope, String metric,
                                 String kit, String search, int limit, KitFilters filters,
                                 String anchorName, long debugJobId) throws Exception {
        String base = System.getProperty("gomod123.gostatsUrl", DEFAULT_BASE_URL);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        StringBuilder query = new StringBuilder(base).append("/blitz/data?scope=")
                .append(encode(scope)).append("&metric=").append(encode(metric))
                .append("&limit=").append(limit).append("&offset=").append(offset);
        if (!kit.isEmpty()) query.append("&kit=").append(encode(kit));
        if (!search.isEmpty()) query.append("&search=").append(encode(search));
        if (anchorName != null && !anchorName.isEmpty()) {
            query.append("&anchor=").append(encode(anchorName));
        }
        if (filters != null) {
            appendFilter(query, "level", filters.level);
            appendFilter(query, "finisher", filters.finisher);
            appendFilter(query, "victory", filters.victory);
            appendFilter(query, "aura", filters.aura);
            appendFilter(query, "taunt", filters.taunt);
            appendFilter(query, "kill_effect", filters.killEffect);
            appendFilter(query, "kill_counter", filters.killCounter);
            appendFilter(query, "ranks", filters.ranks);
            appendFilter(query, "level_kit", filters.levelKit);
        }
        return readPageUrl(new URL(query.toString()), scope, metric, kit, debugJobId);
    }

    private static Page readSuggestionsPage(String query, int limit) throws Exception {
        String base = System.getProperty("gomod123.gostatsUrl", DEFAULT_BASE_URL);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + "/blitz/data/suggestions?query=" + encode(query)
                + "&limit=" + Math.max(1, Math.min(25, limit)));
        return readPageUrl(url, "overall", "kills", "", -1L);
    }

    private static Page readPageUrl(URL url, String scope, String metric, String kit,
                                    long debugJobId) throws Exception {
        LeaderboardRefreshDebugCapture.HttpRequest debugRequest = debugJobId < 0L ? null
                : LeaderboardRefreshDebugCapture.beginHttp(debugJobId, "gostats123", "GET", url.toString());
        HttpURLConnection connection = null;
        int responseStatus = -1;
        Throwable requestFailure = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "gomod123-leaderboard");
            int status = connection.getResponseCode();
            responseStatus = status;
            if (status != HttpURLConnection.HTTP_OK) {
                String detail = readError(connection.getErrorStream());
                throw new Exception(httpFailure("leaderboard", status, detail));
            }
            try (InputStream input = connection.getInputStream();
                 InputStreamReader inputReader = new InputStreamReader(input, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(inputReader)) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (parsed == null || !parsed.isJsonObject()) {
                    throw new Exception("gostats123 returned invalid leaderboard data");
                }
                JsonObject root = parsed.getAsJsonObject();
                JsonArray rows = array(root, "players");
                List<Player> players = new ArrayList<Player>(rows.size());
                for (JsonElement element : rows) {
                    if (element == null || !element.isJsonObject()) continue;
                    JsonObject row = element.getAsJsonObject();
                    JsonObject rank = object(row, "rank_info");
                    JsonObject stats = object(row, "stats");
                    String uuid = string(row, "uuid", "").replace("-", "");
                    String username = string(row, "username", "Unknown");
                    if (username.trim().isEmpty()) continue;
                    double metricValue = decimal(row, "metric_value");
                    if ("overall".equals(scope) && "prestige_2".equals(metric)) {
                        // The P2 leaderboard's value is the number of owned Prestige II kits.
                        // Prefer the explicit count in either API location over a generic sort
                        // value, so a stale/mis-shaped row can never turn this into kills.
                        if (row.has("prestige_2_count")) {
                            metricValue = number(row, "prestige_2_count");
                        } else if (stats.has("prestige_2_count")) {
                            metricValue = number(stats, "prestige_2_count");
                        }
                    }
                    long lastLoginMs = number(row, "last_login_ms");
                    long lastLogoutMs = number(row, "last_logout_ms");
                    Player player = new Player(
                            (int) number(row, "rank"), uuid, username,
                            string(rank, "display", ""), string(rank, "tag", ""),
                            parseColor(string(rank, "color", "")),
                            string(rank, "rank_plus_color", ""),
                            string(rank, "monthly_rank_color", ""),
                            string(row, "last_checked", "never"), string(row, "fetched_at", ""),
                            string(row, "community_observed_at", ""),
                            string(row, "kit", ""),
                            number(stats, "kill_counter_enabled") != 0L,
                            stats.has("total_kills") ? number(stats, "total_kills")
                                    : stats.has("overall_kills") ? number(stats, "overall_kills") : number(stats, "kills"),
                            number(stats, "solo_kills"),
                            number(stats, "team_kills"),
                            stats.has("total_wins") ? number(stats, "total_wins")
                                    : stats.has("overall_wins") ? number(stats, "overall_wins") : number(stats, "wins"),
                            number(stats, "solo_wins"), number(stats, "team_wins"),
                            number(stats, "deaths"), number(stats, "games"), number(stats, "coins"),
                            number(stats, "time_played"), metricValue,
                            "kit".equals(scope) ? number(stats, "level") : 0L,
                            "kit".equals(scope) ? number(stats, "prestige") : 0L,
                            lastLoginMs, lastLogoutMs, "",
                            lastLoginMs > 0L ? "Last login" : "", lastLoginMs / 1000L);
                    player = withSupplementalStats(player, supplementalStatsFromApi(stats, scope));
                    // Keep these leaderboard counts directly on the row as well as in the
                    // general supplemental-stat map. This makes the P2/P1/X columns resilient
                    // to row copies and cached-page transitions.
                    player.prestige1Count = number(stats, "prestige_1_count");
                    player.prestige2Count = row.has("prestige_2_count")
                            ? number(row, "prestige_2_count") : number(stats, "prestige_2_count");
                    player.levelXCount = number(stats, "level_x_count");
                    players.add(player);
                }
                String searchAnchorUuid = string(root, "search_anchor_uuid", "").trim();
                return new Page((int) number(root, "offset"), (int) number(root, "total"),
                        string(root, "generated_at", ""), players,
                        searchAnchorUuid.isEmpty() ? null : searchAnchorUuid);
            }
        } catch (Exception failure) {
            requestFailure = failure;
            throw failure;
        } finally {
            LeaderboardRefreshDebugCapture.finishHttp(debugRequest, responseStatus, null, requestFailure);
            if (connection != null) connection.disconnect();
        }
    }

    private static void appendFilter(StringBuilder query, String name, String value) throws Exception {
        if (value != null && !value.trim().isEmpty()) query.append('&').append(name).append('=').append(encode(value));
    }

    private static String readError(InputStream input) {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            final int maxResponseChars = 4096;
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[256];
            int count;
            while (result.length() < maxResponseChars && (count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, Math.min(count, maxResponseChars - result.length()));
            }
            boolean truncated = result.length() >= maxResponseChars;
            String body = result.toString().replace('\n', ' ').replace('\r', ' ').trim();
            if (body.startsWith("<")) {
                body = body.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                        .replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ")
                        .replaceAll("(?s)<[^>]*>", " ")
                        .replace("&nbsp;", " ").replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replaceAll("\\s+", " ").trim();
            }
            String truncationNote = truncated ? " … [error response truncated]" : "";
            try {
                JsonElement parsed = new JsonParser().parse(body);
                if (parsed != null && parsed.isJsonObject()) {
                    JsonObject object = parsed.getAsJsonObject();
                    String message = string(object, "error", string(object, "message", ""));
                    if (!message.isEmpty()) return message + truncationNote;
                }
            } catch (Exception ignored) { }
            return body + truncationNote;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String httpFailure(String resource, int status, String detail) {
        if (status >= 500) {
            return "gostats123 had a server error while loading " + resource + " (HTTP " + status + ")"
                    + (detail.isEmpty() ? ". Try again shortly." : ": " + detail);
        }
        return "gostats123 could not load " + resource + " (HTTP " + status + ")"
                + (detail.isEmpty() ? "." : ": " + detail);
    }

    private static int parseColor(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return 0xFFFFFFFF;
        try {
            return (int) Long.parseLong(value.substring(1), 16);
        } catch (Exception ignored) {
            return 0xFFFFFFFF;
        }
    }

    private static String normalizeMetric(String metric) {
        String value = metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z_]{1,40}") ? value : "kills";
    }

    private static String normalizeScope(String scope) {
        String value = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        return "solo".equals(value) || "teams".equals(value) || "kit".equals(value)
                ? value : "overall";
    }

    private static String safeText(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    private static JsonArray array(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
        } catch (Exception ignored) {
            return new JsonArray();
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
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

    private static String safeMessage(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "request failed" : message.trim();
    }

    public interface Callback {
        void onComplete(Page page, String error);
    }

    public interface KitFilterCatalogCallback {
        void onComplete(KitFilterCatalog catalog, String error);
    }

    public static final class KitFilters {
        public final String level;
        public final String finisher;
        public final String victory;
        public final String aura;
        public final String taunt;
        public final String killCounter;
        public final String killEffect;
        public final String ranks;
        public final String levelKit;

        public KitFilters(String level, String finisher, String victory, String aura, String taunt,
                          String killCounter) {
            this(level, finisher, victory, aura, taunt, killCounter, "");
        }

        public KitFilters(String level, String finisher, String victory, String aura, String taunt,
                          String killCounter, String ranks) {
            this(level, finisher, victory, aura, taunt, killCounter, ranks, "");
        }

        public KitFilters(String level, String finisher, String victory, String aura, String taunt,
                          String killCounter, String ranks, String levelKit) {
            this(level, finisher, victory, aura, taunt, killCounter, "", ranks, levelKit);
        }

        public KitFilters(String level, String finisher, String victory, String aura, String taunt,
                          String killCounter, String killEffect, String ranks, String levelKit) {
            this.level = safeText(level, 32);
            this.finisher = safeText(finisher, 80);
            this.victory = safeText(victory, 80);
            this.aura = safeText(aura, 80);
            this.taunt = safeText(taunt, 80);
            this.killCounter = safeText(killCounter, 16);
            this.killEffect = safeText(killEffect, 80);
            this.ranks = safeText(ranks, 512);
            this.levelKit = safeText(levelKit, 64);
        }

        public boolean isActive() {
            return !level.isEmpty() || !finisher.isEmpty() || !victory.isEmpty()
                    || !aura.isEmpty() || !taunt.isEmpty() || !killCounter.isEmpty()
                    || !killEffect.isEmpty() || !ranks.isEmpty();
        }
    }

    public static final class KitFilterCatalog {
        public final List<String> finishers;
        public final List<String> victories;
        public final List<String> auras;
        public final List<String> taunts;
        public final List<String> killEffects;
        public final boolean killEffectFilteringSupported;

        private KitFilterCatalog(List<String> finishers, List<String> victories, List<String> auras,
                                 List<String> taunts, List<String> killEffects,
                                 boolean killEffectFilteringSupported) {
            this.finishers = Collections.unmodifiableList(new ArrayList<String>(finishers));
            this.victories = Collections.unmodifiableList(new ArrayList<String>(victories));
            this.auras = Collections.unmodifiableList(new ArrayList<String>(auras));
            this.taunts = Collections.unmodifiableList(new ArrayList<String>(taunts));
            this.killEffects = Collections.unmodifiableList(new ArrayList<String>(
                    killEffects == null || killEffects.isEmpty()
                            ? java.util.Arrays.asList("SPEED", "RESISTANCE", "REGENERATION", "GRAVEDIGGER",
                                    "FLAMING_ARROWS", "LEVEL_UP", "RANDOM")
                            : killEffects));
            this.killEffectFilteringSupported = killEffectFilteringSupported;
        }
    }

    public static final class Page {
        public final int offset;
        public final int total;
        public final String generatedAt;
        public final List<Player> players;
        public final String searchAnchorUuid;

        private Page(int offset, int total, String generatedAt, List<Player> players) {
            this(offset, total, generatedAt, players, null);
        }

        private Page(int offset, int total, String generatedAt, List<Player> players,
                     String searchAnchorUuid) {
            this.offset = offset;
            this.total = total;
            this.generatedAt = generatedAt;
            this.players = Collections.unmodifiableList(new ArrayList<Player>(players));
            this.searchAnchorUuid = searchAnchorUuid;
        }
    }

    public static final class Player {
        public final int rank;
        public final String uuid;
        public final String username;
        public final String rankDisplay;
        public final String rankTag;
        public final int rankColor;
        public final String rankPlusColor;
        public final String monthlyRankColor;
        public final String lastChecked;
        public final String fetchedAt;
        public final String communityObservedAt;
        public final String kit;
        public final boolean killCounterEnabled;
        public final long kills;
        public final long soloKills;
        public final long teamKills;
        public final long wins;
        public final long soloWins;
        public final long teamWins;
        public final long deaths;
        public final long games;
        public final long coins;
        public final long timePlayed;
        public final double metricValue;
        public volatile long prestige1Count;
        public volatile long prestige2Count;
        public volatile long levelXCount;
        public final long kitLevel;
        public final long kitPrestige;
        public final long lastLoginMs;
        public final long lastLogoutMs;
        public final String presenceStatus;
        public final String lastAction;
        public final long lastActionSeconds;
        public final String lastGameType;
        public final String refreshCacheLabel;
        private Map<String, Double> supplementalStats = Collections.emptyMap();

        public double supplementalStat(String key, double fallback) {
            Double value = supplementalStats.get(key);
            return value == null ? fallback : value.doubleValue();
        }

        private Player(int rank, String uuid, String username, String rankDisplay, String rankTag, int rankColor,
                       String rankPlusColor, String monthlyRankColor, String lastChecked,
                       String fetchedAt, String communityObservedAt, String kit,
                       boolean killCounterEnabled, long kills, long soloKills, long teamKills, long wins,
                       long soloWins, long teamWins, long deaths, long games,
                       long coins, long timePlayed, double metricValue) {
            this(rank, uuid, username, rankDisplay, rankTag, rankColor, rankPlusColor,
                    monthlyRankColor, lastChecked, fetchedAt, communityObservedAt, kit,
                    killCounterEnabled, kills, soloKills, teamKills, wins, soloWins,
                    teamWins, deaths, games, coins, timePlayed, metricValue, 0L, 0L);
        }

        private Player(int rank, String uuid, String username, String rankDisplay, String rankTag, int rankColor,
                       String rankPlusColor, String monthlyRankColor, String lastChecked,
                       String fetchedAt, String communityObservedAt, String kit,
                       boolean killCounterEnabled, long kills, long soloKills, long teamKills, long wins,
                       long soloWins, long teamWins, long deaths, long games,
                       long coins, long timePlayed, double metricValue, long kitLevel, long kitPrestige) {
            this(rank, uuid, username, rankDisplay, rankTag, rankColor, rankPlusColor, monthlyRankColor,
                    lastChecked, fetchedAt, communityObservedAt, kit, killCounterEnabled, kills, soloKills,
                    teamKills, wins, soloWins, teamWins, deaths, games, coins, timePlayed, metricValue,
                    kitLevel, kitPrestige, 0L, 0L, "", "", 0L);
        }

        private Player(int rank, String uuid, String username, String rankDisplay, String rankTag, int rankColor,
                       String rankPlusColor, String monthlyRankColor, String lastChecked,
                       String fetchedAt, String communityObservedAt, String kit,
                       boolean killCounterEnabled, long kills, long soloKills, long teamKills, long wins,
                       long soloWins, long teamWins, long deaths, long games,
                       long coins, long timePlayed, double metricValue, long kitLevel, long kitPrestige,
                       long lastLoginMs, long lastLogoutMs, String presenceStatus,
                       String lastAction, long lastActionSeconds) {
            this(rank, uuid, username, rankDisplay, rankTag, rankColor, rankPlusColor, monthlyRankColor,
                    lastChecked, fetchedAt, communityObservedAt, kit, killCounterEnabled, kills, soloKills,
                    teamKills, wins, soloWins, teamWins, deaths, games, coins, timePlayed, metricValue,
                    kitLevel, kitPrestige, lastLoginMs, lastLogoutMs, presenceStatus, lastAction, lastActionSeconds, "");
        }

        private Player(int rank, String uuid, String username, String rankDisplay, String rankTag, int rankColor,
                       String rankPlusColor, String monthlyRankColor, String lastChecked,
                       String fetchedAt, String communityObservedAt, String kit,
                       boolean killCounterEnabled, long kills, long soloKills, long teamKills, long wins,
                       long soloWins, long teamWins, long deaths, long games,
                       long coins, long timePlayed, double metricValue, long kitLevel, long kitPrestige,
                       long lastLoginMs, long lastLogoutMs, String presenceStatus,
                       String lastAction, long lastActionSeconds, String refreshCacheLabel) {
            this(rank, uuid, username, rankDisplay, rankTag, rankColor, rankPlusColor,
                    monthlyRankColor, lastChecked, fetchedAt, communityObservedAt, kit,
                    killCounterEnabled, kills, soloKills, teamKills, wins, soloWins,
                    teamWins, deaths, games, coins, timePlayed, metricValue, kitLevel,
                    kitPrestige, lastLoginMs, lastLogoutMs, presenceStatus, lastAction,
                    lastActionSeconds, refreshCacheLabel, "");
        }

        private Player(int rank, String uuid, String username, String rankDisplay, String rankTag, int rankColor,
                       String rankPlusColor, String monthlyRankColor, String lastChecked,
                       String fetchedAt, String communityObservedAt, String kit,
                       boolean killCounterEnabled, long kills, long soloKills, long teamKills, long wins,
                       long soloWins, long teamWins, long deaths, long games,
                       long coins, long timePlayed, double metricValue, long kitLevel, long kitPrestige,
                       long lastLoginMs, long lastLogoutMs, String presenceStatus,
                       String lastAction, long lastActionSeconds, String refreshCacheLabel,
                       String lastGameType) {
            this.rank = rank;
            this.uuid = uuid;
            this.username = username;
            this.rankDisplay = rankDisplay;
            this.rankTag = rankTag;
            this.rankColor = rankColor;
            this.rankPlusColor = rankPlusColor;
            this.monthlyRankColor = monthlyRankColor;
            this.lastChecked = lastChecked;
            this.fetchedAt = fetchedAt;
            this.communityObservedAt = communityObservedAt;
            this.kit = kit;
            this.killCounterEnabled = killCounterEnabled;
            this.kills = kills;
            this.soloKills = soloKills;
            this.teamKills = teamKills;
            this.wins = wins;
            this.soloWins = soloWins;
            this.teamWins = teamWins;
            this.deaths = deaths;
            this.games = games;
            this.coins = coins;
            this.timePlayed = timePlayed;
            this.metricValue = metricValue;
            this.kitLevel = kitLevel;
            this.kitPrestige = kitPrestige;
            this.lastLoginMs = lastLoginMs;
            this.lastLogoutMs = lastLogoutMs;
            this.presenceStatus = presenceStatus == null ? "" : presenceStatus;
            this.lastAction = lastAction == null ? "" : lastAction;
            this.lastActionSeconds = Math.max(0L, lastActionSeconds);
            this.lastGameType = lastGameType == null ? "" : lastGameType;
            this.refreshCacheLabel = refreshCacheLabel == null ? "" : refreshCacheLabel;
        }
    }

}
