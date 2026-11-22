package me.napim.gomod.features;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates Prestige I enemy kits detected from in-game item names against Hypixel's Blitz API.
 *
 * <p>Hypixel Blitz has a known bug where upgrading a kit from Prestige I to Prestige II does not
 * always update the item NBT star count, leaving items labeled with a single star ({@code ✫})
 * even though the player owns Prestige II ({@code ✫✫}). This validator reuses
 * {@link HypixelPlayerDataCache} (populated by Stats Display / {@code /infop}) when available,
 * and falls back to a rate-limited, deduplicated background API lookup when no fresh cache exists.</p>
 */
public final class EnemyKitPrestigeValidator {
    public static final String PRESTIGE_ONE_STAR = "\u272B";
    public static final String PRESTIGE_TWO_STARS = "\u272B\u272B";

    private static final String PRESTIGE_ONE_SUFFIX = " " + PRESTIGE_ONE_STAR;
    private static final String PRESTIGE_TWO_SUFFIX = " " + PRESTIGE_TWO_STARS;

    private static final AtomicLong MATCH_GENERATION = new AtomicLong(0L);
    private static final Set<String> CHECKED_IN_MATCH = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger WORKER_ID = new AtomicInteger(1);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "gomod-enemy-kit-prestige-" + WORKER_ID.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public enum CacheDecision {
        UPGRADE_TO_P2,
        CONFIRMED_BELOW_P2,
        NEEDS_API_FETCH
    }

    public interface UpgradeCallback {
        void onUpgradedToPrestigeTwo(String playerName, String originalKit, String upgradedKit);
    }

    private EnemyKitPrestigeValidator() {
    }

    /** Clears per-match deduplication state and invalidates any in-flight lookups from the prior match. */
    public static void resetForNewMatch() {
        MATCH_GENERATION.incrementAndGet();
        CHECKED_IN_MATCH.clear();
    }

    /**
     * Returns the base kit name if {@code detectedKit} represents a Prestige I kit
     * (e.g. {@code "Pigman ✫"} -> {@code "Pigman"}), or {@code null} otherwise.
     */
    public static String extractPrestigeOneKitName(String detectedKit) {
        if (detectedKit == null) return null;
        String trimmed = detectedKit.trim();
        if (trimmed.endsWith(PRESTIGE_TWO_SUFFIX) || !trimmed.endsWith(PRESTIGE_ONE_SUFFIX)) {
            return null;
        }
        String kitName = trimmed.substring(0, trimmed.length() - PRESTIGE_ONE_SUFFIX.length()).trim();
        return kitName.isEmpty() ? null : kitName;
    }

    /** Upgrades a Prestige I display string ({@code "Pigman ✫"}) to Prestige II ({@code "Pigman ✫✫"}). */
    public static String upgradePrestigeOneToTwo(String detectedKit) {
        String kitName = extractPrestigeOneKitName(detectedKit);
        if (kitName == null) return detectedKit;
        return kitName + PRESTIGE_TWO_SUFFIX;
    }

    /**
     * Reads the Hypixel Blitz kit prestige level ({@code stats.HungerGames.p<kit>}) from a
     * Hypixel {@code player} JSON object. Returns {@code -1} if Blitz stats are absent.
     */
    public static int readBlitzKitPrestige(JsonObject playerData, String kitName) {
        if (playerData == null || kitName == null || kitName.trim().isEmpty()) {
            return -1;
        }
        try {
            if (!playerData.has("stats") || !playerData.get("stats").isJsonObject()) {
                return -1;
            }
            JsonObject stats = playerData.getAsJsonObject("stats");
            if (!stats.has("HungerGames") || !stats.get("HungerGames").isJsonObject()) {
                return -1;
            }
            JsonObject hg = stats.getAsJsonObject("HungerGames");
            String apiKey = kitName.trim().toLowerCase(Locale.ROOT);
            String primaryKey = "p" + apiKey;
            if (hg.has(primaryKey) && !hg.get(primaryKey).isJsonNull()) {
                return parseIntSafe(hg.get(primaryKey), 0);
            }
            String compactKey = "p" + apiKey.replace(" ", "");
            if (!compactKey.equals(primaryKey) && hg.has(compactKey) && !hg.get(compactKey).isJsonNull()) {
                return parseIntSafe(hg.get(compactKey), 0);
            }
            return 0;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Evaluates cached Hypixel player profiles for a detected Prestige I kit:
     * <ul>
     *   <li>If any cached profile (even older than 2 minutes) reports prestige &ge; 2, returns
     *       {@link CacheDecision#UPGRADE_TO_P2} since Blitz prestiges are permanent.</li>
     *   <li>Else if any fresh cached profile reports prestige &lt; 2, returns
     *       {@link CacheDecision#CONFIRMED_BELOW_P2} to avoid a redundant API call.</li>
     *   <li>Otherwise returns {@link CacheDecision#NEEDS_API_FETCH}.</li>
     * </ul>
     */
    public static CacheDecision evaluateCachedProfiles(String kitName,
                                                       Iterable<JsonObject> anyCachedProfiles,
                                                       Iterable<JsonObject> freshCachedProfiles) {
        if (anyCachedProfiles != null) {
            for (JsonObject profile : anyCachedProfiles) {
                if (readBlitzKitPrestige(profile, kitName) >= 2) {
                    return CacheDecision.UPGRADE_TO_P2;
                }
            }
        }
        if (freshCachedProfiles != null) {
            for (JsonObject profile : freshCachedProfiles) {
                if (readBlitzKitPrestige(profile, kitName) >= 0) {
                    return CacheDecision.CONFIRMED_BELOW_P2;
                }
            }
        }
        return CacheDecision.NEEDS_API_FETCH;
    }

    /**
     * Synchronously checks {@link HypixelPlayerDataCache} for a Prestige I enemy kit and returns
     * the upgraded Prestige II string immediately on a P2 cache hit. If no fresh cache confirms
     * the prestige level, schedules a single deduplicated background Hypixel API lookup that
     * invokes {@code onUpgrade} if the API reports Prestige II.
     */
    public static String validateAndUpgradeOrSchedule(final String playerName,
                                                      final String resolvedLookupIgn,
                                                      final Collection<String> identityAliases,
                                                      final UUID entityUuid,
                                                      final String detectedKit,
                                                      final UpgradeCallback onUpgrade) {
        final String kitName = extractPrestigeOneKitName(detectedKit);
        if (kitName == null || playerName == null || playerName.trim().isEmpty()) {
            return detectedKit;
        }

        final String uuidNoDash = entityUuid == null
                ? "" : entityUuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
        List<JsonObject> anyProfiles = new ArrayList<JsonObject>();
        List<JsonObject> freshProfiles = new ArrayList<JsonObject>();
        collectCachedProfiles(playerName, resolvedLookupIgn, identityAliases, uuidNoDash, anyProfiles, freshProfiles);

        CacheDecision decision = evaluateCachedProfiles(kitName, anyProfiles, freshProfiles);
        if (decision == CacheDecision.UPGRADE_TO_P2) {
            return upgradePrestigeOneToTwo(detectedKit);
        }
        if (decision == CacheDecision.CONFIRMED_BELOW_P2) {
            return detectedKit;
        }

        // Needs an API request to verify Prestige I vs Prestige II.
        final String apiKey = getUsableHypixelApiKey();
        if (apiKey == null) {
            return detectedKit;
        }

        final String dedupKey = playerName.trim().toLowerCase(Locale.ROOT) + "|" + kitName.toLowerCase(Locale.ROOT);
        if (!CHECKED_IN_MATCH.add(dedupKey)) {
            return detectedKit;
        }

        final long generation = MATCH_GENERATION.get();
        final String lookupName = (resolvedLookupIgn != null && !resolvedLookupIgn.trim().isEmpty())
                ? resolvedLookupIgn.trim() : playerName.trim();

        EXECUTOR.submit(new Runnable() {
            @Override
            public void run() {
                if (MATCH_GENERATION.get() != generation) {
                    return;
                }
                try {
                    String targetUuid = resolveTargetUuidNoDash(playerName.trim(), lookupName, uuidNoDash);
                    if (targetUuid == null || targetUuid.isEmpty() || MATCH_GENERATION.get() != generation) {
                        return;
                    }

                    // Re-check cache after UUID resolution (Stats Display may have finished in the meantime).
                    JsonObject playerData = HypixelPlayerDataCache.getFreshByUuid(targetUuid);
                    if (playerData == null) {
                        JsonObject anyByUuid = HypixelPlayerDataCache.getAnyByUuid(targetUuid);
                        if (readBlitzKitPrestige(anyByUuid, kitName) >= 2) {
                            playerData = anyByUuid;
                        }
                    }

                    if (playerData == null) {
                        String url = "https://api.hypixel.net/player?key=" + apiKey + "&uuid=" + targetUuid;
                        JsonObject response = HypixelApiHelper.fetchBlitzJson(url, false, true);
                        GameModeStatusFetcher.noteHypixelApiSuccess(apiKey);
                        if (response != null && response.has("success") && response.get("success").getAsBoolean()
                                && response.has("player") && response.get("player").isJsonObject()) {
                            playerData = response.getAsJsonObject("player");
                            HypixelPlayerDataCache.ingest(playerName.trim(), playerData);
                            if (!lookupName.equalsIgnoreCase(playerName.trim())) {
                                HypixelPlayerDataCache.ingest(lookupName, playerData);
                            }
                        }
                    }

                    if (MATCH_GENERATION.get() != generation) {
                        return;
                    }
                    if (readBlitzKitPrestige(playerData, kitName) >= 2 && onUpgrade != null) {
                        onUpgrade.onUpgradedToPrestigeTwo(playerName, detectedKit, upgradePrestigeOneToTwo(detectedKit));
                    }
                } catch (Throwable error) {
                    GameModeStatusFetcher.handleHypixelInvalidKeyFailure(apiKey, error.getMessage(), true);
                }
            }
        });

        return detectedKit;
    }

    private static void collectCachedProfiles(String playerName,
                                              String resolvedLookupIgn,
                                              Collection<String> aliases,
                                              String uuidNoDash,
                                              List<JsonObject> anyOut,
                                              List<JsonObject> freshOut) {
        if (uuidNoDash != null && uuidNoDash.matches("[a-f0-9]{32}")) {
            JsonObject any = HypixelPlayerDataCache.getAnyByUuid(uuidNoDash);
            if (any != null) anyOut.add(any);
            JsonObject fresh = HypixelPlayerDataCache.getFreshByUuid(uuidNoDash);
            if (fresh != null) freshOut.add(fresh);
        }
        addCachedByName(playerName, anyOut, freshOut);
        if (resolvedLookupIgn != null && !resolvedLookupIgn.equalsIgnoreCase(playerName)) {
            addCachedByName(resolvedLookupIgn, anyOut, freshOut);
        }
        if (aliases != null) {
            for (String alias : aliases) {
                addCachedByName(alias, anyOut, freshOut);
            }
        }
    }

    private static void addCachedByName(String name, List<JsonObject> anyOut, List<JsonObject> freshOut) {
        if (name == null || name.trim().isEmpty()) return;
        JsonObject any = HypixelPlayerDataCache.getAny(name);
        if (any != null) anyOut.add(any);
        JsonObject fresh = HypixelPlayerDataCache.getFresh(name);
        if (fresh != null) freshOut.add(fresh);
    }

    private static String getUsableHypixelApiKey() {
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return null;
        }
        apiKey = apiKey.trim();
        if (GameModeStatusFetcher.shouldSkipKnownBadHypixelRequests(apiKey)) {
            return null;
        }
        return apiKey;
    }

    private static String resolveTargetUuidNoDash(String playerName, String lookupName, String entityUuidNoDash) {
        // When denicked to a different real IGN, resolve Mojang UUID for the real IGN instead of the nick UUID.
        if (lookupName != null && !lookupName.isEmpty() && !lookupName.equalsIgnoreCase(playerName)) {
            String mojangUuid = fetchMojangUuidNoDash(lookupName);
            if (mojangUuid != null && !mojangUuid.isEmpty()) {
                return mojangUuid;
            }
        }
        if (entityUuidNoDash != null && entityUuidNoDash.matches("[a-f0-9]{32}")) {
            return entityUuidNoDash;
        }
        return fetchMojangUuidNoDash(lookupName);
    }

    private static String fetchMojangUuidNoDash(String ign) {
        if (ign == null || ign.trim().isEmpty()) return null;
        try {
            String text = HypixelApiHelper.fetchText("https://api.mojang.com/users/profiles/minecraft/" + ign.trim());
            if (text == null || text.trim().isEmpty()) return null;
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("id") && !obj.get("id").isJsonNull()) {
                    String id = obj.get("id").getAsString().replace("-", "").trim().toLowerCase(Locale.ROOT);
                    if (id.matches("[a-f0-9]{32}")) {
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Nicked or unreachable Mojang profile; keep scanned kit as-is.
        }
        return null;
    }

    private static int parseIntSafe(JsonElement element, int fallback) {
        try {
            return element.getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
