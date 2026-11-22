package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small in-memory cache of successful Hypixel player profiles, shared by TAB stats scans and /infop. */
public final class HypixelPlayerDataCache {
    /** TAB stats and /infop commonly run seconds apart; avoid a duplicate /player lookup during this window. */
    public static final long FRESH_FOR_MS = 120_000L;
    private static final int MAX_KEYS = 256;
    private static final Map<String, Entry> BY_NAME = new LinkedHashMap<String, Entry>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_KEYS;
        }
    };
    private static final Map<String, Entry> BY_UUID = new LinkedHashMap<String, Entry>(64, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) { return size() > MAX_KEYS; }
    };

    private HypixelPlayerDataCache() {
    }

    public static synchronized void ingest(String playerName, JsonObject playerData) {
        if (playerName == null || playerName.trim().isEmpty() || playerData == null) {
            return;
        }
        String key = playerName.trim().toLowerCase(Locale.ROOT);
        if (HypixelPlayerRequests.blitzOnly(playerData)) return;
        JsonObject saved = copy(playerData);
        long observedAt = HypixelPlayerRequests.observedAt(saved);
        if (observedAt <= 0L) observedAt = System.currentTimeMillis();
        if (!saved.has(HypixelPlayerRequests.META))
            HypixelPlayerRequests.tag(saved, false, "Hypixel", observedAt, false);
        Entry entry = new Entry(saved, observedAt);
        BY_NAME.put(key, entry);
        if (saved.has("uuid") && !saved.get("uuid").isJsonNull())
            BY_UUID.put(saved.get("uuid").getAsString().replace("-", "").toLowerCase(Locale.ROOT), entry);
    }

    public static synchronized JsonObject getFresh(String playerName) {
        Entry entry = get(playerName);
        if (entry == null || System.currentTimeMillis() - entry.savedAtMs > FRESH_FOR_MS) {
            return null;
        }
        return cachedCopy(entry);
    }

    /** Last successful profile, used only as a fallback when a refresh is rejected or unreachable. */
    public static synchronized JsonObject getAny(String playerName) {
        Entry entry = get(playerName);
        return entry == null ? null : cachedCopy(entry);
    }

    public static synchronized JsonObject getFreshByUuid(String uuid) {
        Entry entry = BY_UUID.get(uuid.replace("-", "").toLowerCase(Locale.ROOT));
        return entry == null || System.currentTimeMillis() - entry.savedAtMs > FRESH_FOR_MS ? null : cachedCopy(entry);
    }
    public static synchronized JsonObject getAnyByUuid(String uuid) {
        Entry entry = BY_UUID.get(uuid.replace("-", "").toLowerCase(Locale.ROOT));
        return entry == null ? null : cachedCopy(entry);
    }
    private static JsonObject cachedCopy(Entry entry) {
        JsonObject result = copy(entry.playerData);
        HypixelPlayerRequests.tag(result, true, HypixelPlayerRequests.source(result), entry.savedAtMs, false);
        return result;
    }

    public static synchronized long getAgeMs(String playerName) {
        Entry entry = get(playerName);
        return entry == null ? -1L : Math.max(0L, System.currentTimeMillis() - entry.savedAtMs);
    }

    private static Entry get(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return null;
        }
        return BY_NAME.get(playerName.trim().toLowerCase(Locale.ROOT));
    }

    private static JsonObject copy(JsonObject object) {
        return new JsonParser().parse(object.toString()).getAsJsonObject();
    }

    private static final class Entry {
        final JsonObject playerData;
        final long savedAtMs;

        Entry(JsonObject playerData, long savedAtMs) {
            this.playerData = playerData;
            this.savedAtMs = savedAtMs;
        }
    }
}
