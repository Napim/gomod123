package me.napim.gomod.features;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/** Best-effort upload of successful, already-requested Blitz responses.
 * No extra Hypixel requests, credentials, other-game data or social links.
 */
public final class GoStatsContributions {
    private static final int MAX_BYTES = 240 * 1024;
    private static final long MIN_AUTOMATIC_TABSTATS_KILLS = 100L;
    private static final long LEADERBOARD_UPLOAD_SPACING_MS = 250L;
    private static final long MAX_FRESH_UPLOAD_AGE_MS = 110_000L;
    private static final Object LEADERBOARD_UPLOAD_LOCK = new Object();
    private static long nextLeaderboardUploadAt;
    private static long leaderboardRetryUntil;
    private static final Map<String, Long> RECENT = new LinkedHashMap<String, Long>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64), runnable -> {
                Thread thread = new Thread(runnable, "gomod-stats-contributions");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());
    private static volatile long retryAt;
    private static final GoStatsLeaderboardSyncQueue LEADERBOARD_SYNC = new GoStatsLeaderboardSyncQueue(4, 512);
    private GoStatsContributions() { }

    public static final class SubmissionResult {
        public final String status;
        public final boolean refreshQueued;
        public final String reason;

        private SubmissionResult(String status, boolean refreshQueued) {
            this(status, refreshQueued, "");
        }

        private SubmissionResult(String status, boolean refreshQueued, String reason) {
            this.status = status == null ? "unknown" : status;
            this.refreshQueued = refreshQueued;
            this.reason = reason == null ? "" : reason;
        }

        public boolean isUsableByLeaderboard() {
            return "accepted".equalsIgnoreCase(status) || "duplicate".equalsIgnoreCase(status);
        }

        /** A user-facing explanation that distinguishes data rejection from a disconnected relay. */
        public String failureDescription() {
            if ("rejected".equalsIgnoreCase(status)) {
                String detail = reason.isEmpty() ? "" : " (" + reason + ")";
                return "gostats123 rejected the fresh profile data" + detail
                        + ". The request reached gostats123, so the gomod Service connection is working; this is a data validation issue.";
            }
            if ("blocked".equalsIgnoreCase(status)) {
                return "gostats123 refused this account's upload. The request reached gostats123, so the gomod Service connection is working.";
            }
            if ("rate_limited".equalsIgnoreCase(status)) {
                return "gostats123's refresh limit was reached for this account. Try again later.";
            }
            if ("unavailable".equalsIgnoreCase(status)) {
                return "gostats123 did not receive this refresh. Check the gomod Service connection, then try again.";
            }
            return "gostats123 did not accept this refresh (status: " + status + "). Try again.";
        }
    }

    public static void observe(String requestUrl, JsonObject response) {
        observe(requestUrl, response, false);
    }

    /**
     * Observes a profile fetched by TAB QoL's automatic stats display. These automatic submissions
     * contribute to gostats123 only once the player's total Blitz kill count reaches the configured floor.
     * This does not change the Hypixel request, local TAB stats, or critical kill-effect refresh behavior.
     */
    public static void observeAutomaticTabStats(String requestUrl, JsonObject response) {
        observe(requestUrl, response, true);
    }

    /** Lets an explicit /infop lookup contribute a fresh local Hypixel-cache profile as well. */
    public static void observeExplicitPlayerInfo(JsonObject player) {
        if (player == null || HypixelPlayerRequests.blitzOnly(player)
                || !"Hypixel".equalsIgnoreCase(HypixelPlayerRequests.source(player))) {
            return;
        }
        if (HypixelPlayerRequests.isCached(player)
                && HypixelPlayerRequests.ageMs(player) > HypixelPlayerDataCache.FRESH_FOR_MS) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.add("player", player);
        observe("https://api.hypixel.net/player", response, false);
    }

    private static void observe(String requestUrl, JsonObject response, boolean automaticTabStats) {
        if (Boolean.getBoolean("gomod123.disableStatsContributions") || System.currentTimeMillis() < retryAt) return;
        try {
            URI requested = new URI(requestUrl);
            if (!"https".equalsIgnoreCase(requested.getScheme())
                    || !"api.hypixel.net".equalsIgnoreCase(requested.getHost())
                    || !("/player".equals(requested.getPath()) || "/v2/player".equals(requested.getPath()))
                    ) return;
            PreparedContribution prepared = prepare(response, automaticTabStats);
            if (prepared == null) return;
            String key = prepared.uuid + ":" + prepared.snapshot.hashCode();
            synchronized (RECENT) {
                Long previous = RECENT.get(key);
                if (previous != null && prepared.observedAt - previous < 180_000L) return;
                RECENT.put(key, prepared.observedAt);
                while (RECENT.size() > 512) RECENT.remove(RECENT.keySet().iterator().next());
            }
            WORKER.execute(() -> {
                try {
                    upload(prepared.owner, prepared.snapshot, prepared.observedAt,
                            !automaticTabStats, false, -1L, prepared.uuid);
                } catch (Exception ignored) {
                    // Best-effort observations never hold up the Hypixel stats request.
                }
            });
        } catch (Exception ignored) {
            // Stats lookup success must never depend on this optional service.
        }
    }

    /** Sends a single explicit refresh and waits for gostats123 to accept or reject the observation. */
    public static SubmissionResult submitFreshHypixelResponse(JsonObject response) throws Exception {
        if (Boolean.getBoolean("gomod123.disableStatsContributions")) {
            throw new Exception("gostats123 contributions are disabled in this client.");
        }
        PreparedContribution prepared = prepare(response, false);
        if (prepared == null) {
            throw new Exception("Hypixel returned no fresh Blitz profile to send to gostats123.");
        }
        return upload(prepared.owner, prepared.snapshot, prepared.observedAt, true, true, -1L, prepared.uuid);
    }

    /** Queue only the compact Blitz payload; the GUI can display the Hypixel result immediately. */
    public static void queueLeaderboardRefresh(JsonObject player) throws Exception {
        queueLeaderboardRefresh(player, -1L);
    }

    /** Queue a refresh contribution and include its asynchronous relay uploads in the originating batch log. */
    public static void queueLeaderboardRefresh(JsonObject player, final long debugJobId) throws Exception {
        if (Boolean.getBoolean("gomod123.disableStatsContributions")) {
            throw new Exception("gostats123 contributions are disabled in this client.");
        }
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.add("player", player);
        final PreparedContribution prepared = prepare(response, false);
        if (prepared == null) throw new Exception("No recent full Hypixel profile is available for database sync.");
        String key = prepared.owner + ":" + prepared.uuid + ":" + prepared.observedAt + ":" + prepared.snapshot.hashCode();
        final String debugPlayerFields = debugJobId >= 0L
                ? LeaderboardRefreshDebugCapture.playerContextFields(debugJobId, prepared.uuid)
                : "uuid=" + prepared.uuid;
        final boolean captured = debugJobId >= 0L && LeaderboardRefreshDebugCapture.retainAsync(debugJobId,
                debugPlayerFields + " observedAtMs=" + prepared.observedAt);
        boolean submitted = LEADERBOARD_SYNC.submit(prepared.uuid, key, () -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                SubmissionResult receipt;
                try {
                    awaitLeaderboardUploadSlot(prepared.observedAt, debugJobId,
                            prepared.uuid, debugPlayerFields);
                    LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload.attempt",
                            debugPlayerFields + " attempt=" + (attempt + 1) + "/3");
                    receipt = upload(prepared.owner, prepared.snapshot, prepared.observedAt, true, true,
                            debugJobId, prepared.uuid, debugPlayerFields);
                } catch (Exception networkFailure) {
                    LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload.failed", debugPlayerFields
                            + " attempt=" + (attempt + 1) + " type="
                            + networkFailure.getClass().getName() + " reason=" + networkFailure.getMessage());
                    if (networkFailure instanceof ContributionRetryWindowException
                            || attempt == 2 || System.currentTimeMillis() - prepared.observedAt > MAX_FRESH_UPLOAD_AGE_MS)
                        throw networkFailure;
                    long backoffMs = networkFailure instanceof RelayRateLimitException
                            ? Math.max(5000L, ((RelayRateLimitException) networkFailure).retryAfterMs)
                            : Math.min(60_000L, 5000L * (1L << attempt));
                    long remainingMs = MAX_FRESH_UPLOAD_AGE_MS - (System.currentTimeMillis() - prepared.observedAt);
                    if (backoffMs >= remainingMs) throw networkFailure;
                    LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload.retry_wait",
                            debugPlayerFields + " nextAttempt=" + (attempt + 2) + " delayMs=" + backoffMs);
                    Thread.sleep(backoffMs);
                    continue;
                }
                LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload.receipt", debugPlayerFields
                        + " attempt=" + (attempt + 1) + " status=" + receipt.status
                        + " refreshQueued=" + receipt.refreshQueued + " reason=" + receipt.reason);
                if (receipt.isUsableByLeaderboard() || "stale".equalsIgnoreCase(receipt.status)) return;
                if ("quarantined".equalsIgnoreCase(receipt.status)) {
                    throw new Exception("gostats123 held this profile for review.");
                }
                throw new Exception(receipt.failureDescription());
            }
        }, captured ? () -> LeaderboardRefreshDebugCapture.releaseAsync(debugJobId,
                debugPlayerFields + " syncError=" + safeQueueError(prepared.uuid)) : null);
        LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.queue.submit", debugPlayerFields
                + " accepted=" + submitted + " captured=" + captured);
    }

    private static String safeQueueError(String uuid) {
        String value = LEADERBOARD_SYNC.error(uuid);
        return value == null || value.trim().isEmpty() ? "none" : value;
    }

    /** Captures any existing shared cooldown before a Top-N refresh queues its database uploads. */
    public static void recordLeaderboardUploadState(long debugJobId) {
        if (debugJobId < 0L) return;
        long now = System.currentTimeMillis();
        synchronized (LEADERBOARD_UPLOAD_LOCK) {
            LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.rate_limit_state",
                    "retryAfterRemainingMs=" + Math.max(0L, leaderboardRetryUntil - now)
                            + " nextUploadSlotInMs=" + Math.max(0L, nextLeaderboardUploadAt - now)
                            + " spacingMs=" + LEADERBOARD_UPLOAD_SPACING_MS
                            + " maxFreshAgeMs=" + MAX_FRESH_UPLOAD_AGE_MS);
        }
    }

    private static void awaitLeaderboardUploadSlot(long observedAt, long debugJobId,
                                                   String uuid, String playerFields) throws Exception {
        long waitStartedNanos = 0L;
        try {
            while (true) {
                long waitMs;
                long pacingWaitMs;
                long retryWaitMs;
                long ageMs;
                synchronized (LEADERBOARD_UPLOAD_LOCK) {
                    long now = System.currentTimeMillis();
                    pacingWaitMs = Math.max(0L, nextLeaderboardUploadAt - now);
                    retryWaitMs = Math.max(0L, leaderboardRetryUntil - now);
                    waitMs = Math.max(pacingWaitMs, retryWaitMs);
                    ageMs = Math.max(0L, now - observedAt);
                    if (waitMs <= 0L) {
                        nextLeaderboardUploadAt = now + LEADERBOARD_UPLOAD_SPACING_MS;
                        return;
                    }
                    if (now + waitMs - observedAt >= MAX_FRESH_UPLOAD_AGE_MS) {
                        String blocker = retryWaitMs > pacingWaitMs ? "relay-retry-after" : "upload-queue-pacing";
                        LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload_window_blocked",
                                playerFields + " blocker=" + blocker + " profileAgeMs=" + ageMs
                                        + " maxFreshAgeMs=" + MAX_FRESH_UPLOAD_AGE_MS
                                        + " remainingFreshMs=" + Math.max(0L, MAX_FRESH_UPLOAD_AGE_MS - ageMs)
                                        + " retryAfterRemainingMs=" + retryWaitMs
                                        + " pacingWaitMs=" + pacingWaitMs + " projectedWaitMs=" + waitMs);
                        throw new ContributionRetryWindowException(
                                "The fresh profile aged out while waiting in the gostats123 sync queue.");
                    }
                }
                if (waitStartedNanos == 0L) {
                    waitStartedNanos = System.nanoTime();
                    LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.rate_limit_wait.start",
                            playerFields + " delayMs=" + waitMs + " retryAfterRemainingMs=" + retryWaitMs
                                    + " pacingWaitMs=" + pacingWaitMs);
                }
                Thread.sleep(Math.min(1000L, Math.max(1L, waitMs)));
            }
        } finally {
            if (waitStartedNanos != 0L) {
                LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.rate_limit_wait.end",
                        playerFields + " waitedMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - waitStartedNanos));
            }
        }
    }

    private static void noteLeaderboardRetryAfter(long retryAfterMs, long debugJobId,
                                                  String debugPlayerFields) {
        long clampedRetryMs = Math.max(5000L, retryAfterMs);
        long until = System.currentTimeMillis() + clampedRetryMs;
        synchronized (LEADERBOARD_UPLOAD_LOCK) {
            leaderboardRetryUntil = Math.max(leaderboardRetryUntil, until);
        }
        String detail = debugPlayerFields + " retryAfterMs=" + clampedRetryMs
                + " sharedCooldownUntilMs=" + until;
        if (debugJobId >= 0L) {
            LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.relay_rate_limited", detail);
        } else {
            LeaderboardRefreshDebugCapture.recordActiveSessions("contribution.relay_rate_limited", detail);
        }
    }

    private static long parseRetryAfterMs(String header) {
        if (header != null) {
            try {
                long seconds = Long.parseLong(header.trim());
                return Math.max(5000L, Math.min(86_400_000L, seconds * 1000L));
            } catch (Exception ignored) {
            }
            try {
                long delay = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis();
                return Math.max(5000L, Math.min(86_400_000L, delay));
            } catch (Exception ignored) {
            }
        }
        return 5000L;
    }

    public static GoStatsLeaderboardSyncQueue.Status leaderboardSyncStatus() { return LEADERBOARD_SYNC.status(); }
    public static boolean isLeaderboardSyncPending(String uuid) { return LEADERBOARD_SYNC.isPending(uuid); }
    public static String leaderboardSyncError(String uuid) { return LEADERBOARD_SYNC.error(uuid); }

    private static PreparedContribution prepare(JsonObject response, boolean automaticTabStats) {
        if (response == null || !response.has("success") || !response.get("success").getAsBoolean()
                || !response.has("player") || !response.get("player").isJsonObject()) return null;
        JsonObject raw = response.getAsJsonObject("player");
        if (!raw.has("uuid") || !raw.has("displayname") || !raw.has("stats")
                || !raw.get("stats").isJsonObject()
                || !raw.getAsJsonObject("stats").has("HungerGames")
                || !raw.getAsJsonObject("stats").get("HungerGames").isJsonObject()) return null;
        JsonObject blitz = raw.getAsJsonObject("stats").getAsJsonObject("HungerGames");
        if (automaticTabStats && !hasMinimumAutomaticTabstatsKills(blitz)) return null;
        if (HypixelPlayerRequests.isCached(raw)
                && (!"Hypixel".equalsIgnoreCase(HypixelPlayerRequests.source(raw))
                || HypixelPlayerRequests.blitzOnly(raw)
                || HypixelPlayerRequests.ageMs(raw) > HypixelPlayerDataCache.FRESH_FOR_MS)) return null;
        JsonObject player = new JsonObject();
        player.add("uuid", raw.get("uuid"));
        player.add("displayname", raw.get("displayname"));
        JsonObject stats = new JsonObject();
        stats.add("HungerGames", blitz);
        player.add("stats", stats);
        JsonObject achievements = new JsonObject();
        if (raw.has("achievements") && raw.get("achievements").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject("achievements").entrySet()) {
                if (entry.getKey().startsWith("blitz_") && entry.getValue().isJsonPrimitive()) {
                    achievements.add(entry.getKey(), entry.getValue());
                }
            }
        }
        player.add("achievements", achievements);
        String snapshot = player.toString();
        if (snapshot.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return null;
        long cachedObservedAt = HypixelPlayerRequests.observedAt(raw);
        long observedAt = cachedObservedAt > 0L ? cachedObservedAt : System.currentTimeMillis();
        String owner = accountUuid();
        if (owner.isEmpty()) return null;
        return new PreparedContribution(raw.get("uuid").getAsString(), owner, snapshot, observedAt);
    }

    private static boolean hasMinimumAutomaticTabstatsKills(JsonObject blitzStats) {
        try {
            JsonElement kills = blitzStats.get("kills");
            return kills != null && !kills.isJsonNull() && kills.isJsonPrimitive()
                    && kills.getAsLong() >= MIN_AUTOMATIC_TABSTATS_KILLS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String accountUuid() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.getSession() == null ? "" : GomodSocialIdentity.profileUuidUndashed(mc);
    }

    private static SubmissionResult upload(String owner, String snapshot, long observedAt,
                                           boolean refreshPriority, boolean force,
                                           long debugJobId, String playerUuid) throws Exception {
        return upload(owner, snapshot, observedAt, refreshPriority, force, debugJobId,
                playerUuid, "uuid=" + playerUuid);
    }

    private static SubmissionResult upload(String owner, String snapshot, long observedAt,
                                           boolean refreshPriority, boolean force,
                                           long debugJobId, String playerUuid,
                                           String debugPlayerFields) throws Exception {
        if (!owner.equals(accountUuid())) {
            LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload.skipped",
                    debugPlayerFields + " reason=minecraft-identity-changed");
            if (force) throw new Exception("Minecraft identity changed before gostats123 could receive the refresh.");
            return new SubmissionResult("identity_changed", false);
        }
        if (System.currentTimeMillis() - observedAt > 120_000L) {
            LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload.skipped",
                    debugPlayerFields + " reason=hypixel-profile-expired ageMs=" + (System.currentTimeMillis() - observedAt));
            if (force) throw new Exception("The Hypixel response expired before gostats123 could receive it. Refresh again.");
            return new SubmissionResult("expired", false);
        }
        if (!force && System.currentTimeMillis() < retryAt) {
            LeaderboardRefreshDebugCapture.record(debugJobId, "contribution.upload.skipped", "uuid="
                    + playerUuid + " reason=global-retry-cooldown remainingMs=" + (retryAt - System.currentTimeMillis()));
            return new SubmissionResult("deferred", false);
        }
        HttpURLConnection connection = null;
        LeaderboardRefreshDebugCapture.HttpRequest debugRequest = null;
        int responseStatus = -1;
        String retryAfterHeader = null;
        Throwable requestFailure = null;
        try {
            URI base = new URI(ConfigManager.getGlobalChatEndpoint());
            if (!"https".equalsIgnoreCase(base.getScheme())) {
                if (force) throw new Exception("The gostats123 contribution URL must use HTTPS.");
                return new SubmissionResult("unavailable", false);
            }
            String endpoint = new URI(base.getScheme(), base.getRawAuthority(), "/gostats123/contribute", null, null).toString();
            JsonObject payload = new JsonObject();
            payload.addProperty("observed_at", observedAt / 1000.0);
            payload.addProperty("refresh_priority", refreshPriority);
            payload.add("player", new JsonParser().parse(snapshot));
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            debugRequest = debugJobId < 0L ? null : LeaderboardRefreshDebugCapture.beginHttp(debugJobId,
                    "gostats123-relay", "POST", endpoint, debugPlayerFields);
            connection = (HttpURLConnection) new URI(endpoint).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            GomodRelayAuth.authorize(connection, endpoint);
            if (!owner.equals(accountUuid())) {
                if (force) throw new Exception("Minecraft identity changed before gostats123 could receive the refresh.");
                return new SubmissionResult("identity_changed", false);
            }
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            responseStatus = status;
            retryAfterHeader = connection.getHeaderField("Retry-After");
            GomodRelayAuth.noteResponseCode(endpoint, status);
            if (status == 429) {
                long retryAfterMs = parseRetryAfterMs(retryAfterHeader);
                noteLeaderboardRetryAfter(retryAfterMs, debugJobId, debugPlayerFields);
                throw new RelayRateLimitException(retryAfterMs);
            }
            if (status != 200) throw new Exception("gostats123 relay returned HTTP " + status + ".");
            String responseText;
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                responseText = readBounded(input);
            }
            JsonElement parsed = new JsonParser().parse(responseText);
            if (parsed == null || !parsed.isJsonObject()) throw new Exception("gostats123 relay returned an invalid refresh receipt.");
            JsonObject result = parsed.getAsJsonObject();
            String resultStatus = result.has("status") && !result.get("status").isJsonNull()
                    ? result.get("status").getAsString() : "unknown";
            boolean queued = result.has("refresh_queued") && result.get("refresh_queued").getAsBoolean();
            String reason = result.has("reason") && !result.get("reason").isJsonNull()
                    ? result.get("reason").getAsString().replaceAll("[^A-Za-z0-9_,:.-]", "") : "";
            if (reason.length() > 80) reason = reason.substring(0, 80);
            return new SubmissionResult(resultStatus, queued, reason);
        } catch (Exception ignored) {
            requestFailure = ignored;
            long failureBackoff = ignored instanceof RelayRateLimitException
                    ? ((RelayRateLimitException) ignored).retryAfterMs : 60_000L;
            retryAt = Math.max(retryAt, System.currentTimeMillis() + failureBackoff);
            if (force) throw ignored;
            System.err.println("[gostats123Contributions] Best-effort upload failed: "
                    + (ignored.getMessage() == null ? ignored.getClass().getSimpleName() : ignored.getMessage()));
            return new SubmissionResult("unavailable", false);
        } finally {
            LeaderboardRefreshDebugCapture.finishHttp(debugRequest, responseStatus, retryAfterHeader, requestFailure);
            if (connection != null) connection.disconnect();
        }
    }

    private static final class RelayRateLimitException extends Exception {
        private final long retryAfterMs;
        private RelayRateLimitException(long retryAfterMs) {
            super("gostats123 relay returned HTTP 429; retry after " + retryAfterMs + "ms.");
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final class ContributionRetryWindowException extends Exception {
        private ContributionRetryWindowException(String message) { super(message); }
    }

    private static String readBounded(InputStream input) throws Exception {
        if (input == null) throw new Exception("gostats123 relay returned an empty refresh receipt.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 4096) throw new Exception("gostats123 relay returned an oversized refresh receipt.");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class PreparedContribution {
        private final String uuid;
        private final String owner;
        private final String snapshot;
        private final long observedAt;

        private PreparedContribution(String uuid, String owner, String snapshot, long observedAt) {
            this.uuid = uuid;
            this.owner = owner;
            this.snapshot = snapshot;
            this.observedAt = observedAt;
        }
    }
}
