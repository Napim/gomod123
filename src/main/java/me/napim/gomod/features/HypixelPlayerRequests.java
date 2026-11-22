package me.napim.gomod.features;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One UUID-based player cache and cooldown coordinator for all mod features. */
public final class HypixelPlayerRequests {
    public static final String META = "_gomod_cache";
    private static final long REQUEST_COOLDOWN_MS = 65_000L;
    private static final long MAX_FALLBACK_AGE_MS = 5 * 60_000L;
    private static final Object[] LOCKS = new Object[64];
    private static final Map<String, Long> NEXT_REQUEST = new LinkedHashMap<String, Long>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 512; }
    };
    /** Hypixel allows at most 300 API requests per key in a rolling five-minute window. */
    private static final int MAX_API_KEY_REQUESTS_PER_WINDOW = 300;
    private static final long API_KEY_WINDOW_NANOS = 300_000_000_000L;
    /** Small boundary margin accounts for the gap between opening a request and Hypixel receiving it. */
    private static final long API_KEY_WINDOW_MARGIN_NANOS = 1_000_000_000L;
    private static final long RATE_LIMIT_CHAT_MIN_DELAY_MS = 3_000L;
    private static final long RATE_LIMIT_FOREGROUND_REMINDER_MS = 30_000L;
    private static final Object API_KEY_LOCK = new Object();
    private static final Map<String, ApiKeyState> API_KEY_STATES = new LinkedHashMap<String, ApiKeyState>(8, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, ApiKeyState> entry) { return size() > 16; }
    };
    private static long lastCacheNotice;
    private static void noteCache(JsonObject player, boolean quiet) {
        if (quiet) return;
        long now = System.currentTimeMillis();
        synchronized (LOCKS) {
            if (now - lastCacheNotice < 3000L) return;
            lastCacheNotice = now;
        }
        try {
            String name = player.has("displayname") ? player.get("displayname").getAsString() : "player";
            me.napim.gomod.Main.sendMessage("\u00a77" + name + ": " + cacheLabel(player));
        } catch (Throwable ignored) { }
    }
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    public interface Loader { JsonObject load(String url) throws Exception; }
    private HypixelPlayerRequests() { }

    /** Receivers of a real API response can seed the same cooldown without another request. */
    public static void noteNetworkSuccess(String uuid, long observedAt) {
        synchronized (NEXT_REQUEST) {
            NEXT_REQUEST.put(uuid, Math.max(NEXT_REQUEST.getOrDefault(uuid, 0L), observedAt + REQUEST_COOLDOWN_MS));
        }
    }

    /** Milliseconds until this UUID may be requested again without hitting Hypixel's lookup cooldown. */
    public static long cooldownRemainingMs(String uuid) {
        String normalized = uuid == null ? "" : uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{32}")) return 0L;
        synchronized (NEXT_REQUEST) {
            Long deadline = NEXT_REQUEST.get(normalized);
            return deadline == null ? 0L : Math.max(0L, deadline.longValue() - System.currentTimeMillis());
        }
    }

    public static String playerUuid(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.hypixel.net".equalsIgnoreCase(uri.getHost())
                    || !("/player".equals(uri.getPath()) || "/v2/player".equals(uri.getPath()))) return "";
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "uuid".equals(URLDecoder.decode(parts[0], "UTF-8"))) {
                    String id = URLDecoder.decode(parts[1], "UTF-8").replace("-", "").toLowerCase(Locale.ROOT);
                    return id.matches("[a-f0-9]{32}") ? id : "";
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    public static JsonObject fetch(String url, boolean freshRequired, boolean sharedBlitz, Loader loader) throws Exception {
        String uuid = playerUuid(url);
        if (uuid.isEmpty()) {
            LeaderboardRefreshDebugCapture.recordCurrent("hypixel.lookup", "uuid=unavailable cacheable=false");
            return loader.load(url);
        }
        traceDecision("hypixel.lookup", "uuid=" + uuid
                + " freshRequired=" + freshRequired + " sharedBlitz=" + sharedBlitz);
        if (!freshRequired) {
            JsonObject cached = HypixelPlayerDataCache.getFreshByUuid(uuid);
            if (cached != null) {
                traceCacheUse("hypixel.cache_hit", uuid, cached, "location=pre-lock");
                noteCache(cached, sharedBlitz);
                return envelope(cached);
            }
        }
        synchronized (LOCKS[(uuid.hashCode() & Integer.MAX_VALUE) % LOCKS.length]) {
            JsonObject cached = HypixelPlayerDataCache.getFreshByUuid(uuid);
            if (cached != null && !freshRequired) {
                traceCacheUse("hypixel.cache_hit", uuid, cached, "location=post-lock");
                noteCache(cached, sharedBlitz || freshRequired); return envelope(cached);
            }
            if (!freshRequired && sharedBlitz) {
                JsonObject shared = GoStatsBlitzClient.fetchSharedPlayer(uuid, HypixelPlayerDataCache.FRESH_FOR_MS);
                if (shared != null) {
                    traceCacheUse("hypixel.cache_hit", uuid, shared, "location=gostats-shared");
                    return envelope(shared);
                }
            }
            long deadline = System.currentTimeMillis()
                    + (LeaderboardRefreshDebugCapture.hasCurrentPlayerContext() ? 600_000L : 130_000L);
            for (int attempt = 0; attempt < 2; attempt++) {
                long next;
                synchronized (NEXT_REQUEST) { next = NEXT_REQUEST.getOrDefault(uuid, 0L); }
                long delay = next - System.currentTimeMillis();
                if (delay > 0) {
                    LeaderboardRefreshDebugCapture.recordCurrent("hypixel.cooldown_wait.start", "uuid=" + uuid
                            + " attempt=" + (attempt + 1) + " delayMs=" + delay + " freshRequired=" + freshRequired);
                    if (!freshRequired) {
                        JsonObject fallback = fallback(uuid);
                        if (fallback != null) {
                            traceCacheUse("hypixel.fallback_cache", uuid, fallback, "reason=request-cooldown scope=player");
                            noteCache(fallback, sharedBlitz); return envelope(fallback);
                        }
                    }
                    if (System.currentTimeMillis() + delay > deadline) {
                        traceDecision("hypixel.cooldown_wait.skipped", "uuid=" + uuid
                                + " reason=player-cooldown-would-exceed-deadline delayMs=" + delay);
                        break;
                    }
                    if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
                        HypixelApiRequestDebug.record("hypixel.cooldown_wait.start", "uuid=" + uuid
                                + " reason=player-cooldown attempt=" + (attempt + 1)
                                + " delayMs=" + delay + " freshRequired=" + freshRequired);
                    }
                    long waitStarted = System.nanoTime();
                    try {
                        Thread.sleep(delay);
                        traceDecision("hypixel.cooldown_wait.end", "uuid=" + uuid
                                + " waitedMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - waitStarted));
                    } catch (InterruptedException interrupted) {
                        traceDecision("hypixel.cooldown_wait.interrupted", "uuid=" + uuid
                                + " waitedMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - waitStarted));
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
                long started = System.currentTimeMillis();
                try {
                    traceDecision("hypixel.attempt", "uuid=" + uuid
                            + " attempt=" + (attempt + 1) + "/2");
                    JsonObject result = loader.load(url);
                    if (result != null && result.has("success") && !result.get("success").getAsBoolean()
                            && result.has("cause")) {
                        String cause = result.get("cause").getAsString();
                        if (cause.toLowerCase(Locale.ROOT).contains("looked up")
                                || cause.toLowerCase(Locale.ROOT).contains("too recently")) {
                            LeaderboardRefreshDebugCapture.recordCurrent("hypixel.recent-lookup-rejected", "uuid=" + uuid
                                    + " attempt=" + (attempt + 1) + " cause=" + cause);
                            HypixelApiRequestDebug.record("hypixel.recent-lookup-rejected", "uuid=" + uuid
                                    + " scope=player source=response-envelope attempt=" + (attempt + 1));
                            throw new HypixelApiHelper.HttpFailure(429, "60", "HTTP 429 (" + cause + ")");
                        }
                    }
                    if (result != null && result.has("success") && result.get("success").getAsBoolean()
                            && result.has("player") && result.get("player").isJsonObject()) {
                        JsonObject player = result.getAsJsonObject("player");
                        if (!player.has("uuid")) player.addProperty("uuid", uuid);
                        if (!uuid.equals(player.get("uuid").getAsString().replace("-", "").toLowerCase(Locale.ROOT)))
                            throw new Exception("Hypixel returned a different player profile");
                        tag(player, false, "Hypixel", started, false);
                        HypixelPlayerDataCache.ingest(player.has("displayname") ? player.get("displayname").getAsString() : uuid, player);
                        noteNetworkSuccess(uuid, started);
                        LeaderboardRefreshDebugCapture.recordCurrent("hypixel.profile.accepted", "uuid=" + uuid
                                + " name=" + (player.has("displayname") ? player.get("displayname").getAsString() : "unknown")
                                + " observedAtMs=" + started);
                    } else {
                        LeaderboardRefreshDebugCapture.recordCurrent("hypixel.response.unusable", "uuid=" + uuid
                                + " success=" + (result != null && result.has("success")
                                && result.get("success").getAsBoolean()));
                    }
                    return result;
                } catch (Exception error) {
                    boolean rateLimit = error instanceof HypixelApiHelper.HttpFailure
                            && ((HypixelApiHelper.HttpFailure) error).status == 429;
                    if (!rateLimit) {
                        if (!freshRequired && !HypixelApiHelper.isLikelyInvalidKeyMessage(error.getMessage())) {
                            JsonObject fallback = fallback(uuid);
                            if (fallback != null) {
                                traceCacheUse("hypixel.fallback_cache", uuid, fallback,
                                        "reason=" + error.getClass().getSimpleName());
                                noteCache(fallback, sharedBlitz); return envelope(fallback);
                            }
                        }
                        LeaderboardRefreshDebugCapture.recordCurrent("hypixel.attempt.failed", "uuid=" + uuid
                                + " attempt=" + (attempt + 1) + " rateLimit=false type=" + error.getClass().getName()
                                + " reason=" + error.getMessage());
                        throw error;
                    }
                    long retry = Math.max(1L, ((HypixelApiHelper.HttpFailure) error).retryAfterSeconds) * 1000L;
                    synchronized (NEXT_REQUEST) { NEXT_REQUEST.put(uuid, System.currentTimeMillis() + retry); }
                    traceDecision("hypixel.rate_limited", "uuid=" + uuid
                            + " attempt=" + (attempt + 1) + " retryAfterMs=" + retry
                            + " willRetryIfNoFallback=" + (attempt == 0));
                    if (!freshRequired) {
                        JsonObject fallback = fallback(uuid);
                        if (fallback != null) {
                            traceCacheUse("hypixel.fallback_cache", uuid, fallback, "reason=rate-limit");
                            noteCache(fallback, sharedBlitz); return envelope(fallback);
                        }
                    }
                    if (attempt == 1) throw error;
                }
            }
            traceDecision("hypixel.lookup.failed", "uuid=" + uuid + " reason=player-cooldown-deadline");
            throw new Exception("Fresh player data is still cooling down. No older kill-effect selection was used; retry shortly.");
        }
    }

    private static void traceDecision(String event, String fields) {
        LeaderboardRefreshDebugCapture.recordCurrent(event, fields);
        HypixelApiRequestDebug.record(event, fields);
    }

    private static void traceCacheUse(String event, String uuid, JsonObject player, String detail) {
        String fields = "uuid=" + uuid + " ageMs=" + ageMs(player) + " " + detail;
        LeaderboardRefreshDebugCapture.recordCurrent(event, fields + " source=" + source(player));
        HypixelApiRequestDebug.record(event, fields);
    }

    private static JsonObject fallback(String uuid) {
        JsonObject player = HypixelPlayerDataCache.getAnyByUuid(uuid);
        return player != null && ageMs(player) <= MAX_FALLBACK_AGE_MS ? player : null;
    }

    /** Called immediately before each actual Hypixel HTTP request, including retries. */
    public static void awaitNetworkRequestSlot(String url) throws Exception {
        long maximumWaitMs = LeaderboardRefreshDebugCapture.hasCurrentPlayerContext()
                ? 600_000L : 130_000L;
        awaitApiKeySlot(url, System.currentTimeMillis() + maximumWaitMs);
    }

    private static void awaitApiKeySlot(String url, long deadline) throws Exception {
        String keyFingerprint = apiKeyFingerprint(url);
        if (keyFingerprint.isEmpty()) return;

        boolean backgroundRefresh = LeaderboardRefreshDebugCapture.hasCurrentPlayerContext();
        boolean registeredForegroundWaiter = false;
        long waitStartedNanos = 0L;
        try {
            while (true) {
                long delayMs;
                long cooldownMs;
                long quotaDelayMs;
                boolean foregroundHasPriority;
                synchronized (API_KEY_LOCK) {
                    ApiKeyState state = apiKeyState(keyFingerprint);
                    long nowMs = System.currentTimeMillis();
                    long nowNanos = System.nanoTime();
                    while (!state.requestStartsNanos.isEmpty()
                            && nowNanos - state.requestStartsNanos.peekFirst()
                            >= API_KEY_WINDOW_NANOS + API_KEY_WINDOW_MARGIN_NANOS) {
                        state.requestStartsNanos.removeFirst();
                    }
                    cooldownMs = Math.max(0L, state.blockedUntil - nowMs);
                    if (state.requestStartsNanos.size() >= MAX_API_KEY_REQUESTS_PER_WINDOW) {
                        long releaseAtNanos = state.requestStartsNanos.peekFirst()
                                + API_KEY_WINDOW_NANOS + API_KEY_WINDOW_MARGIN_NANOS;
                        long remainingNanos = Math.max(0L, releaseAtNanos - nowNanos);
                        quotaDelayMs = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                remainingNanos + 999_999L));
                    } else {
                        quotaDelayMs = 0L;
                    }
                    foregroundHasPriority = backgroundRefresh && state.foregroundWaiters > 0;
                    delayMs = Math.max(quotaDelayMs, cooldownMs);
                    if (!foregroundHasPriority && delayMs <= 0L) {
                        state.requestStartsNanos.addLast(nowNanos);
                        boolean announceCleared = state.rateLimitNotified;
                        state.rateLimitNotified = false;
                        LeaderboardRefreshDebugCapture.recordCurrent("hypixel.api_request_slot", ""
                                + "quotaUsed=" + state.requestStartsNanos.size() + "/"
                                + MAX_API_KEY_REQUESTS_PER_WINDOW + " windowMs=300000");
                        if (announceCleared) {
                            sendRateLimitChat(net.minecraft.util.EnumChatFormatting.GREEN
                                    + "Hypixel API rate limit cleared — requests resumed.");
                        }
                        return;
                    }
                    if (delayMs >= RATE_LIMIT_CHAT_MIN_DELAY_MS) {
                        boolean retryAfter = cooldownMs > 0L && cooldownMs >= quotaDelayMs;
                        maybeNotifyRateLimitLocked(state, nowMs, delayMs, retryAfter, backgroundRefresh);
                    }
                    if (nowMs + delayMs > deadline) {
                        LeaderboardRefreshDebugCapture.recordCurrent("hypixel.api_slot_wait.skipped",
                                "reason=quota-or-cooldown-window delayMs=" + delayMs);
                        if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
                            HypixelApiRequestDebug.recordForUrl("hypixel.api_slot_wait.skipped", url,
                                    "reason=key-wait-would-exceed-deadline quotaWaitMs=" + quotaDelayMs
                                            + " keyBackoffMs=" + cooldownMs);
                        }
                        throw new Exception("Hypixel API key is rate limited. Try this fresh lookup again after the cooldown.");
                    }
                    if (!backgroundRefresh && !registeredForegroundWaiter) {
                        state.foregroundWaiters++;
                        registeredForegroundWaiter = true;
                    }
                    if (foregroundHasPriority && delayMs <= 0L) delayMs = 50L;
                }

                if (waitStartedNanos == 0L) {
                    waitStartedNanos = System.nanoTime();
                    String reason = cooldownMs > 0 && cooldownMs >= quotaDelayMs ? "retry-after"
                            : quotaDelayMs > 0 ? "300-per-5m-quota" : "foreground-priority";
                    LeaderboardRefreshDebugCapture.recordCurrent("hypixel.api_slot_wait.start", "reason="
                            + reason + " delayMs=" + delayMs + " priority="
                            + (backgroundRefresh ? "background" : "foreground"));
                    if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
                        HypixelApiRequestDebug.recordForUrl("hypixel.api_slot_wait.start", url,
                                "reason=" + reason + " delayMs=" + delayMs + " quotaWaitMs=" + quotaDelayMs
                                        + " keyBackoffMs=" + cooldownMs);
                    }
                }
                Thread.sleep(Math.min(1000L, Math.max(1L, delayMs)));
            }
        } finally {
            if (registeredForegroundWaiter) {
                synchronized (API_KEY_LOCK) {
                    ApiKeyState state = API_KEY_STATES.get(keyFingerprint);
                    if (state != null && state.foregroundWaiters > 0) state.foregroundWaiters--;
                }
            }
            if (waitStartedNanos != 0L) {
                String fields = "waitedMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - waitStartedNanos);
                LeaderboardRefreshDebugCapture.recordCurrent("hypixel.api_slot_wait.end", fields);
                HypixelApiRequestDebug.recordForUrl("hypixel.api_slot_wait.end", url, fields);
            }
        }
    }

    /**
     * Recent-lookup 429s belong to one UUID and must not stop the other workers on the same API key.
     * Keep the key-wide cooldown for actual API-key rate limits and other unclassified 429 responses.
     */
    public static void noteApiKeyRateLimit(String url, long retryMs, String failureMessage) {
        boolean playerCooldown = isPlayerLookupCooldown(failureMessage);
        if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
            HypixelApiRequestDebug.recordForUrl("hypixel.upstream_429", url,
                    "reason=" + (playerCooldown ? "recent-player-lookup" : "unclassified")
                            + " backoffScope=" + (playerCooldown ? "player" : "api-key")
                            + " retryAfterMs=" + Math.max(1L, retryMs));
        }
        if (playerCooldown) {
            LeaderboardRefreshDebugCapture.recordCurrent("hypixel.api_key_cooldown.skipped",
                    "scope=player retryAfterMs=" + Math.max(1L, retryMs)
                            + " reason=player-already-looked-up-recently");
            return;
        }
        String keyFingerprint = apiKeyFingerprint(url);
        if (keyFingerprint.isEmpty()) return;
        long blockedUntil = System.currentTimeMillis() + Math.max(1L, retryMs);
        synchronized (API_KEY_LOCK) {
            ApiKeyState state = apiKeyState(keyFingerprint);
            state.blockedUntil = Math.max(state.blockedUntil, blockedUntil);
        }
        LeaderboardRefreshDebugCapture.recordCurrent("hypixel.api_key_cooldown", "retryAfterMs="
                + Math.max(1L, retryMs));
    }

    private static boolean isPlayerLookupCooldown(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("already looked up this player too recently")
                || lower.contains("looked up this player too recently");
    }

    private static ApiKeyState apiKeyState(String keyFingerprint) {
        ApiKeyState state = API_KEY_STATES.get(keyFingerprint);
        if (state == null) {
            state = new ApiKeyState();
            API_KEY_STATES.put(keyFingerprint, state);
        }
        return state;
    }

    /** Live snapshot of the configured Hypixel API key's rate-limit state for UI indicators. */
    public static ApiKeyRateLimitStatus getCurrentApiKeyRateLimitStatus() {
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) return ApiKeyRateLimitStatus.CLEAR;
        String fingerprint = fingerprintKey(apiKey.trim());
        if (fingerprint.isEmpty()) return ApiKeyRateLimitStatus.CLEAR;
        synchronized (API_KEY_LOCK) {
            ApiKeyState state = API_KEY_STATES.get(fingerprint);
            if (state == null) return ApiKeyRateLimitStatus.CLEAR;
            long nowMs = System.currentTimeMillis();
            long nowNanos = System.nanoTime();
            while (!state.requestStartsNanos.isEmpty()
                    && nowNanos - state.requestStartsNanos.peekFirst()
                    >= API_KEY_WINDOW_NANOS + API_KEY_WINDOW_MARGIN_NANOS) {
                state.requestStartsNanos.removeFirst();
            }
            long cooldownMs = Math.max(0L, state.blockedUntil - nowMs);
            long quotaDelayMs = 0L;
            if (state.requestStartsNanos.size() >= MAX_API_KEY_REQUESTS_PER_WINDOW) {
                long releaseAtNanos = state.requestStartsNanos.peekFirst()
                        + API_KEY_WINDOW_NANOS + API_KEY_WINDOW_MARGIN_NANOS;
                long remainingNanos = Math.max(0L, releaseAtNanos - nowNanos);
                quotaDelayMs = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        remainingNanos + 999_999L));
            }
            long delayMs = Math.max(quotaDelayMs, cooldownMs);
            boolean retryAfter = cooldownMs > 0L && cooldownMs >= quotaDelayMs;
            return new ApiKeyRateLimitStatus(delayMs > 0L, retryAfter, delayMs,
                    state.requestStartsNanos.size(), MAX_API_KEY_REQUESTS_PER_WINDOW);
        }
    }

    private static void maybeNotifyRateLimitLocked(ApiKeyState state, long nowMs, long delayMs,
                                                   boolean retryAfter, boolean backgroundRefresh) {
        if (!state.rateLimitNotified) {
            state.rateLimitNotified = true;
            state.lastRateLimitChatMs = nowMs;
            String kind = retryAfter ? "Hypixel API key rate-limited (429)"
                    : "Hypixel API rate limit reached (300/5m)";
            sendRateLimitChat(net.minecraft.util.EnumChatFormatting.YELLOW + kind
                    + net.minecraft.util.EnumChatFormatting.GRAY + " — waiting "
                    + net.minecraft.util.EnumChatFormatting.WHITE + formatWaitDuration(delayMs)
                    + net.minecraft.util.EnumChatFormatting.GRAY
                    + " (fresh stats lookups paused until slots clear).");
            return;
        }
        if (!backgroundRefresh
                && nowMs - state.lastRateLimitChatMs >= RATE_LIMIT_FOREGROUND_REMINDER_MS
                && nowMs - state.lastForegroundNoticeMs >= RATE_LIMIT_FOREGROUND_REMINDER_MS) {
            state.lastForegroundNoticeMs = nowMs;
            state.lastRateLimitChatMs = nowMs;
            sendRateLimitChat(net.minecraft.util.EnumChatFormatting.YELLOW
                    + "Hypixel API rate limit still active "
                    + net.minecraft.util.EnumChatFormatting.GRAY + "("
                    + net.minecraft.util.EnumChatFormatting.WHITE + formatWaitDuration(delayMs)
                    + net.minecraft.util.EnumChatFormatting.GRAY
                    + " left) — stats lookups are paused/using cache.");
        }
    }

    private static void sendRateLimitChat(String body) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return;
            me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX + body);
        } catch (Throwable ignored) {
            // Safe no-op in headless unit tests or before the Minecraft client initializes.
        }
    }

    private static String formatWaitDuration(long ms) {
        long seconds = Math.max(1L, (ms + 999L) / 1000L);
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        long rem = seconds % 60L;
        return rem == 0L ? minutes + "m" : minutes + "m " + rem + "s";
    }

    private static String fingerprintKey(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Store only a one-way fingerprint, never the API key itself. */
    private static String apiKeyFingerprint(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"api.hypixel.net".equalsIgnoreCase(uri.getHost())) return "";
            String query = uri.getRawQuery();
            if (query == null) return "";
            String key = "";
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "key".equals(URLDecoder.decode(parts[0], "UTF-8"))) {
                    key = URLDecoder.decode(parts[1], "UTF-8");
                    break;
                }
            }
            return fingerprintKey(key);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static final class ApiKeyRateLimitStatus {
        public static final ApiKeyRateLimitStatus CLEAR =
                new ApiKeyRateLimitStatus(false, false, 0L, 0, MAX_API_KEY_REQUESTS_PER_WINDOW);
        public final boolean active;
        public final boolean retryAfter;
        public final long remainingMs;
        public final int quotaUsed;
        public final int quotaLimit;

        private ApiKeyRateLimitStatus(boolean active, boolean retryAfter, long remainingMs,
                                      int quotaUsed, int quotaLimit) {
            this.active = active;
            this.retryAfter = retryAfter;
            this.remainingMs = remainingMs;
            this.quotaUsed = quotaUsed;
            this.quotaLimit = quotaLimit;
        }
    }

    private static final class ApiKeyState {
        private long blockedUntil;
        private int foregroundWaiters;
        private boolean rateLimitNotified;
        private long lastRateLimitChatMs;
        private long lastForegroundNoticeMs;
        private final Deque<Long> requestStartsNanos = new ArrayDeque<Long>();
    }

    private static JsonObject envelope(JsonObject player) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.add("player", player);
        return result;
    }
    public static void tag(JsonObject player, boolean cached, String source, long at, boolean blitzOnly) {
        JsonObject meta = new JsonObject();
        meta.addProperty("cached", cached);
        meta.addProperty("source", source);
        meta.addProperty("observed_at_ms", at);
        meta.addProperty("blitz_only", blitzOnly);
        player.add(META, meta);
    }
    public static long observedAt(JsonObject player) {
        try { return player.getAsJsonObject(META).get("observed_at_ms").getAsLong(); }
        catch (Exception ignored) { return 0L; }
    }
    public static boolean isCached(JsonObject player) {
        try { return player.getAsJsonObject(META).get("cached").getAsBoolean(); }
        catch (Exception ignored) { return false; }
    }
    public static boolean blitzOnly(JsonObject player) {
        try { return player.getAsJsonObject(META).get("blitz_only").getAsBoolean(); }
        catch (Exception ignored) { return false; }
    }
    public static long ageMs(JsonObject player) { return Math.max(0L, System.currentTimeMillis() - observedAt(player)); }
    public static String source(JsonObject player) {
        try { return player.getAsJsonObject(META).get("source").getAsString(); }
        catch (Exception ignored) { return "local player cache"; }
    }
    public static String cacheLabel(JsonObject player) {
        return isCached(player) ? "Cached " + source(player) + " data (" + Math.max(1L, ageMs(player) / 1000L) + "s old)" : "Fresh Hypixel response";
    }

    /** Privacy in a saved profile is distinct from a successful live presence check. */
    public static String statusLabel(JsonObject player, JsonObject session, boolean liveAvailable) {
        boolean hasLogin = player != null && player.has("lastLogin") && !player.get("lastLogin").isJsonNull();
        if (!liveAvailable) {
            if (hasLogin) return "Unavailable";
            return isCached(player) ? "Hidden (cached profile; live status unavailable)"
                    : "Hidden (live status unavailable)";
        }
        boolean online = session != null && session.has("online") && !session.get("online").isJsonNull()
                && session.get("online").getAsBoolean();
        return online ? "Online" : hasLogin ? "Offline" : "Hidden";
    }
}
