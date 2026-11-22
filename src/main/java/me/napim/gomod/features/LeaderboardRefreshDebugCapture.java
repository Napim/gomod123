package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.GomodPaths;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, automatic diagnostics for one Top 50–950 leaderboard refresh in dev builds. */
public final class LeaderboardRefreshDebugCapture {
    private static final int MAX_EVENTS = 20000;
    private static final int MAX_QUEUED_LINES = 8192;
    private static final long HEARTBEAT_SECONDS = 15L;
    private static final long MAX_BACKGROUND_CAPTURE_MINUTES = 15L;
    private static final ThreadLocal<PlayerContext> CURRENT_PLAYER = new ThreadLocal<PlayerContext>();
    private static final Map<Long, Session> SESSIONS = new ConcurrentHashMap<Long, Session>();
    private static final Map<Long, File> OUTPUT_FILES = new LinkedHashMap<Long, File>();
    private static volatile String HYPixelSecretForRedaction = "";

    private LeaderboardRefreshDebugCapture() { }

    /** Starts capture before the refresh can issue any network requests. Failure is non-fatal. */
    public static File start(long jobId, String scope, String kit, String metric, int requestedCount,
                             boolean loadedRowsAvailable, boolean filtersActive, boolean apiKeyPresent) {
        if (GomodReleaseBuild.PUBLIC_RELEASE) return null;
        try {
            HYPixelSecretForRedaction = ApiKeyManager.getApiKey("hypixel");
            File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("Could not create debugall folder");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
            File output = new File(directory, "leaderboard_refresh_" + stamp + "_job-" + jobId + ".log");
            try (BufferedWriter header = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(output, false), StandardCharsets.UTF_8))) {
                header.write("gomod123 leaderboard refresh diagnostics v1; timestamps are UTC; response bodies and credentials are excluded; compact error reasons may be recorded.");
                header.newLine();
                header.flush();
            }
            Session session = new Session(jobId, output);
            SESSIONS.put(jobId, session);
            synchronized (OUTPUT_FILES) {
                OUTPUT_FILES.put(jobId, output.getAbsoluteFile());
                while (OUTPUT_FILES.size() > 32) {
                    Long oldest = OUTPUT_FILES.keySet().iterator().next();
                    OUTPUT_FILES.remove(oldest);
                }
            }
            session.start();
            session.record("capture.start", "version=1 jobId=" + jobId + " scope=" + scope
                    + " kit=" + emptyAsNone(kit) + " metric=" + metric + " requestedCount=" + requestedCount
                    + " targetSource=" + (loadedRowsAvailable ? "loaded-gui-rows" : "gostats123-api")
                    + " filtersActive=" + filtersActive + " hypixelApiKeyPresent=" + apiKeyPresent
                    + " workerCount=" + GoStatsLeaderboardBatchRefresh.getWorkerCount()
                    + " output=" + output.getAbsolutePath());
            session.record("capture.privacy", "API keys, auth headers, and response bodies are never recorded; URL credentials are redacted; queue is bounded to "
                    + MAX_QUEUED_LINES + " lines; flush interval=1s");
            session.startHeartbeat();
            return output.getAbsoluteFile();
        } catch (Throwable failure) {
            HYPixelSecretForRedaction = "";
            System.err.println("[gomod] Leaderboard refresh debug could not start: "
                    + failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()));
            return null;
        }
    }

    public static File getOutputFile(long jobId) {
        synchronized (OUTPUT_FILES) { return OUTPUT_FILES.get(jobId); }
    }

    public static void record(long jobId, String event, String details) {
        Session session = SESSIONS.get(jobId);
        if (session != null) session.record(event, details);
    }

    /** Includes shared contribution cooldown changes from other GoMod features in an active refresh log. */
    public static void recordActiveSessions(String event, String details) {
        for (Session session : SESSIONS.values()) {
            if (session != null && !session.finalized.get()) {
                session.record(event, "source=shared-contribution-queue " + details);
            }
        }
    }

    /** Resolves asynchronous upload events back to the leaderboard row that queued them. */
    public static String playerContextFields(long jobId, String uuid) {
        Session session = SESSIONS.get(jobId);
        String normalized = normalizeUuid(uuid);
        PlayerContext context = session == null || normalized.isEmpty()
                ? null : session.playersByUuid.get(normalized);
        return context == null ? "uuid=" + safe(uuid) : context.fields();
    }

    /** Sets the per-player context used by the low-level HTTP and cooldown traces. */
    public static PlayerScope enterPlayer(long jobId, int index, int total, int workerIndex,
                                          String uuid, String username) {
        Session session = SESSIONS.get(jobId);
        if (session == null) return PlayerScope.NOOP;
        PlayerContext previous = CURRENT_PLAYER.get();
        PlayerContext context = new PlayerContext(session, index, total, workerIndex,
                safe(uuid), safe(username), System.nanoTime());
        CURRENT_PLAYER.set(context);
        session.inFlight.put(Thread.currentThread().getName(), context);
        String normalizedUuid = normalizeUuid(uuid);
        if (!normalizedUuid.isEmpty()) session.playersByUuid.put(normalizedUuid, context);
        session.record("player.start", context.fields() + " thread=" + Thread.currentThread().getName());
        return new PlayerScope(previous, context);
    }

    public static void recordCurrent(String event, String details) {
        PlayerContext context = CURRENT_PLAYER.get();
        if (context != null) context.session.record(event, context.fields() + " " + details);
    }

    /** True while a worker is fetching one player for a leaderboard batch. */
    public static boolean hasCurrentPlayerContext() {
        return CURRENT_PLAYER.get() != null;
    }

    public static HttpRequest beginCurrentHttp(String service, String method, String url) {
        PlayerContext context = CURRENT_PLAYER.get();
        return context == null ? null : beginHttp(context.session, service, method, url, context);
    }

    /** Used by the initial gostats123 leaderboard page fetch, which runs on its own executor. */
    public static HttpRequest beginHttp(long jobId, String service, String method, String url) {
        Session session = SESSIONS.get(jobId);
        return session == null ? null : beginHttp(session, service, method, url, null, "");
    }

    public static HttpRequest beginHttp(long jobId, String service, String method, String url,
                                        String contextDetails) {
        Session session = SESSIONS.get(jobId);
        return session == null ? null : beginHttp(session, service, method, url, null, contextDetails);
    }

    private static HttpRequest beginHttp(Session session, String service, String method, String url,
                                         PlayerContext context) {
        return beginHttp(session, service, method, url, context, "");
    }

    private static HttpRequest beginHttp(Session session, String service, String method, String url,
                                         PlayerContext context, String contextDetails) {
        long nowNanos = System.nanoTime();
        AtomicLong previousStart = session.lastHttpStart.computeIfAbsent(service, key -> new AtomicLong());
        long previous = previousStart.getAndSet(nowNanos);
        long gapMicros = previous == 0L ? -1L
                : Math.max(0L, TimeUnit.NANOSECONDS.toMicros(nowNanos - previous));
        long id = session.nextRequestId.incrementAndGet();
        HttpRequest request = new HttpRequest(session, id, service, nowNanos, context, contextDetails);
        session.record("request.start", (context == null ? "" : context.fields() + " ") + safe(contextDetails) + " "
                + "requestId=" + id + " service=" + safe(service) + " method=" + safe(method)
                + " url=" + safeUrl(url) + " gapSincePreviousStartMicros=" + gapMicros
                + " thread=" + Thread.currentThread().getName());
        return request;
    }

    public static void finishHttp(HttpRequest request, int status, String retryAfter, Throwable failure) {
        if (request == null) return;
        long elapsed = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - request.startedNanos));
        String outcome = failure == null ? "response" : "error=" + failure.getClass().getSimpleName()
                + " reason=" + safe(failure.getMessage());
        String retry = retryAfter == null || retryAfter.trim().isEmpty() ? "" : " retryAfter=" + safe(retryAfter.trim());
        PlayerContext context = request.context;
        request.session.record("request.end", (context == null ? "" : context.fields() + " ")
                + safe(request.contextDetails) + " "
                + "requestId=" + request.id + " service=" + safe(request.service) + " status=" + status
                + " durationMs=" + elapsed + retry + " outcome=" + outcome
                + " thread=" + Thread.currentThread().getName());
    }

    public static void progress(long jobId, int started, int completed, int updated, int failed,
                                int total, boolean cancelled) {
        Session session = SESSIONS.get(jobId);
        if (session == null) return;
        session.started = started;
        session.completed = completed;
        session.updated = updated;
        session.failed = failed;
        session.total = total;
        session.cancelled = cancelled;
    }

    /** Writes the final summary and drains the asynchronous writer before returning. */
    public static File finish(long jobId, String reason, int started, int completed, int updated,
                              int failed, int total, boolean cancelled) {
        Session session = SESSIONS.get(jobId);
        if (session == null) return getOutputFile(jobId);
        session.started = started;
        session.completed = completed;
        session.updated = updated;
        session.failed = failed;
        session.total = total;
        session.cancelled = cancelled;
        session.batchFinished.set(true);
        session.record("capture.finish", "reason=" + safe(reason) + " started=" + started
                + " completed=" + completed + " updated=" + updated + " failed=" + failed
                + " total=" + total + " cancelled=" + cancelled
                + " backgroundUploadsPending=" + session.pendingAsync.get());
        if (session.pendingAsync.get() > 0) {
            session.hadBackgroundUploads = true;
            session.record("capture.waiting_for_background_uploads", "pending=" + session.pendingAsync.get()
                    + " maxWaitMinutes=" + MAX_BACKGROUND_CAPTURE_MINUTES);
            session.scheduleBackgroundCaptureDeadline();
        } else {
            finalizeSession(session, "refresh-finished");
        }
        return session.output.getAbsoluteFile();
    }

    /** Keeps the capture alive until the corresponding background database upload completes. */
    public static boolean retainAsync(long jobId, String detail) {
        Session session = SESSIONS.get(jobId);
        if (session == null || session.finalized.get()) return false;
        session.pendingAsync.incrementAndGet();
        session.hadBackgroundUploads = true;
        session.record("background_upload.queued", safe(detail) + " pending=" + session.pendingAsync.get());
        return true;
    }

    public static void releaseAsync(long jobId, String detail) {
        Session session = SESSIONS.get(jobId);
        if (session == null || session.finalized.get()) return;
        session.recordBackgroundUploadResult(detail);
        int remaining = Math.max(0, session.pendingAsync.decrementAndGet());
        session.record("background_upload.finished", safe(detail) + " pending=" + remaining);
        if (remaining == 0 && session.batchFinished.get()) finalizeSession(session, "background-uploads-finished");
    }

    public static boolean isFinalized(long jobId) {
        Session session = SESSIONS.get(jobId);
        return session == null || session.finalized.get();
    }

    private static void finalizeSession(Session session, String reason) {
        if (!session.finalized.compareAndSet(false, true)) return;
        session.record("capture.saved", "reason=" + safe(reason) + " pendingBackgroundUploads="
                + session.pendingAsync.get() + " " + session.backgroundUploadSummary()
                + " output=" + session.output.getAbsolutePath());
        session.close();
        SESSIONS.remove(session.jobId, session);
        if (SESSIONS.isEmpty()) HYPixelSecretForRedaction = "";
        if (session.hadBackgroundUploads && !"refresh-finished".equals(reason)) {
            String prefix = "background-capture-time-limit".equals(reason)
                    ? "Leaderboard refresh debug saved with background uploads still pending: "
                    : "Leaderboard refresh debug saved after database uploads: ";
            me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX
                    + net.minecraft.util.EnumChatFormatting.GRAY + prefix
                    + net.minecraft.util.EnumChatFormatting.WHITE + session.output.getAbsolutePath());
        }
    }

    private static String safeUrl(String raw) {
        try {
            URL url = new URL(raw);
            StringBuilder result = new StringBuilder(url.getProtocol()).append("://")
                    .append(url.getHost()).append(url.getPath());
            String query = url.getQuery();
            if (query == null || query.isEmpty()) return result.toString();
            List<String> allowed = new ArrayList<String>();
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = parts[0];
                String lower = key.toLowerCase(Locale.ROOT);
                if ("key".equals(lower) || "token".equals(lower) || "secret".equals(lower)
                        || "authorization".equals(lower) || "api_key".equals(lower)) continue;
                String value = parts.length > 1 ? parts[1] : "";
                allowed.add(key + "=" + value);
            }
            if (!allowed.isEmpty()) result.append('?').append(join(allowed, "&"));
            return safe(result.toString());
        } catch (Exception ignored) {
            return "<unavailable-url>";
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static String emptyAsNone(String value) { return value == null || value.trim().isEmpty() ? "none" : safe(value); }

    private static String normalizeUuid(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String backgroundUploadFailureCategory(String error) {
        String lower = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (lower.contains("upload window") || lower.contains("upload queue")) return "fresh-window";
        if (lower.contains("expired")) return "profile-expired";
        if (lower.contains("timed out") || lower.contains("timeout")) return "network-timeout";
        if (lower.contains("429") || lower.contains("rate limit")) return "rate-limited";
        if (lower.contains("reject") || lower.contains("invalid_stat")) return "service-rejected";
        return "other";
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "-";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
                .replaceAll("(?i)(key|token|secret|authorization|api_key)=([^&\\s]+)", "$1=<redacted>");
        String hypixelKey = HYPixelSecretForRedaction;
        if (hypixelKey != null && hypixelKey.length() >= 8) cleaned = cleaned.replace(hypixelKey, "<redacted>");
        return cleaned.length() > 1200 ? cleaned.substring(0, 1200) + "...[truncated]" : cleaned;
    }

    private static final class Session {
        private final long jobId;
        private final File output;
        private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<String>(MAX_QUEUED_LINES);
        private final AtomicInteger eventCount = new AtomicInteger();
        private final AtomicLong droppedEvents = new AtomicLong();
        private final AtomicLong nextRequestId = new AtomicLong();
        private final AtomicInteger pendingAsync = new AtomicInteger();
        private final AtomicInteger backgroundUploadSuccesses = new AtomicInteger();
        private final AtomicInteger backgroundUploadFailures = new AtomicInteger();
        private final AtomicBoolean batchFinished = new AtomicBoolean();
        private final AtomicBoolean finalized = new AtomicBoolean();
        private final Map<String, AtomicLong> lastHttpStart = new ConcurrentHashMap<String, AtomicLong>();
        private final Map<String, PlayerContext> inFlight = new ConcurrentHashMap<String, PlayerContext>();
        private final Map<String, PlayerContext> playersByUuid = new ConcurrentHashMap<String, PlayerContext>();
        private final Map<String, AtomicInteger> backgroundUploadFailureReasons =
                new ConcurrentHashMap<String, AtomicInteger>();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final Thread writer;
        private ScheduledExecutorService heartbeat;
        private volatile int started;
        private volatile int completed;
        private volatile int updated;
        private volatile int failed;
        private volatile int total;
        private volatile boolean cancelled;
        private volatile boolean hadBackgroundUploads;
        private volatile String writerFailure;

        private Session(long jobId, File output) {
            this.jobId = jobId;
            this.output = output;
            this.writer = new Thread(this::writeLoop, "gomod-leaderboard-debug-writer-" + jobId);
            this.writer.setDaemon(true);
        }

        private void start() { writer.start(); }

        private void startHeartbeat() {
            heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gomod-leaderboard-debug-heartbeat-" + jobId);
                thread.setDaemon(true);
                return thread;
            });
            heartbeat.scheduleAtFixedRate(() -> {
                StringBuilder active = new StringBuilder();
                for (Map.Entry<String, PlayerContext> entry : inFlight.entrySet()) {
                    PlayerContext context = entry.getValue();
                    long age = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - context.startedNanos);
                    if (active.length() > 0) active.append("; ");
                    active.append(entry.getKey()).append("{").append(context.fields())
                            .append(" ageMs=").append(age).append('}');
                }
                record("heartbeat", "started=" + started + " completed=" + completed + " updated=" + updated
                        + " failed=" + failed + " remaining=" + Math.max(0, total - completed)
                        + " cancelled=" + cancelled + " pendingUploads=" + pendingAsync.get()
                        + " inFlight=" + (active.length() == 0 ? "none" : active));
            }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }

        private void scheduleBackgroundCaptureDeadline() {
            if (heartbeat == null) return;
            heartbeat.schedule(() -> finalizeSession(this, "background-capture-time-limit"),
                    MAX_BACKGROUND_CAPTURE_MINUTES, TimeUnit.MINUTES);
        }

        private void record(String event, String details) {
            if (closing.get()) return;
            if (eventCount.incrementAndGet() > MAX_EVENTS) {
                droppedEvents.incrementAndGet();
                return;
            }
            String line = Instant.now().toString() + " elapsedMs=" + elapsedMs()
                    + " event=" + safe(event) + " " + safe(details);
            if (!queue.offer(line)) droppedEvents.incrementAndGet();
        }

        private void recordBackgroundUploadResult(String detail) {
            String error = "";
            if (detail != null) {
                int start = detail.indexOf("syncError=");
                if (start >= 0) {
                    start += "syncError=".length();
                    int end = detail.indexOf(" pending=", start);
                    error = (end >= 0 ? detail.substring(start, end) : detail.substring(start)).trim();
                }
            }
            if (error.isEmpty() || "none".equalsIgnoreCase(error)) {
                backgroundUploadSuccesses.incrementAndGet();
                return;
            }
            backgroundUploadFailures.incrementAndGet();
            String category = backgroundUploadFailureCategory(error);
            backgroundUploadFailureReasons.computeIfAbsent(category, ignored -> new AtomicInteger())
                    .incrementAndGet();
        }

        private String backgroundUploadSummary() {
            StringBuilder result = new StringBuilder("backgroundUploadsSucceeded=")
                    .append(backgroundUploadSuccesses.get())
                    .append(" backgroundUploadsFailed=").append(backgroundUploadFailures.get());
            if (!backgroundUploadFailureReasons.isEmpty()) {
                result.append(" backgroundUploadFailureReasons=");
                boolean first = true;
                for (Map.Entry<String, AtomicInteger> entry : backgroundUploadFailureReasons.entrySet()) {
                    if (!first) result.append(',');
                    first = false;
                    result.append(entry.getKey()).append(':').append(entry.getValue().get());
                }
            }
            return result.toString();
        }

        private long createdNanos = System.nanoTime();
        private long elapsedMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdNanos); }

        private void writeLoop() {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(output, true), StandardCharsets.UTF_8))) {
                long lastFlush = System.currentTimeMillis();
                while (!closing.get() || !queue.isEmpty()) {
                    String line = queue.poll(250L, TimeUnit.MILLISECONDS);
                    if (line != null) {
                        writer.write(line);
                        writer.newLine();
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= 1000L) {
                        writer.flush();
                        lastFlush = now;
                    }
                }
                writer.write(Instant.now().toString() + " event=capture.writer_summary droppedEvents="
                        + droppedEvents.get() + (writerFailure == null ? "" : " writerFailure=" + safe(writerFailure)));
                writer.newLine();
                writer.flush();
            } catch (Throwable failure) {
                writerFailure = failure.getClass().getSimpleName() + ": " + safe(failure.getMessage());
                System.err.println("[gomod] Leaderboard refresh debug writer failed: " + writerFailure);
            }
        }

        private void close() {
            if (heartbeat != null) heartbeat.shutdownNow();
            closing.set(true);
            try { writer.join(10000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static final class PlayerContext {
        private final Session session;
        private final int index;
        private final int total;
        private final int workerIndex;
        private final String uuid;
        private final String username;
        private final long startedNanos;

        private PlayerContext(Session session, int index, int total, int workerIndex,
                              String uuid, String username, long startedNanos) {
            this.session = session;
            this.index = index;
            this.total = total;
            this.workerIndex = workerIndex;
            this.uuid = uuid;
            this.username = username;
            this.startedNanos = startedNanos;
        }

        private String fields() {
            return "playerIndex=" + index + "/" + total + " worker=" + workerIndex
                    + " username=" + username + " uuid=" + uuid;
        }
    }

    public static final class PlayerScope implements AutoCloseable {
        private static final PlayerScope NOOP = new PlayerScope(null, null);
        private final PlayerContext previous;
        private final PlayerContext context;
        private boolean finished;
        private boolean closed;

        private PlayerScope(PlayerContext previous, PlayerContext context) {
            this.previous = previous;
            this.context = context;
        }

        public void finish(String result, String details) {
            if (context == null || finished) return;
            context.session.record("player.finish", context.fields() + " result=" + safe(result)
                    + " durationMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - context.startedNanos)
                    + " " + details);
            finished = true;
        }

        @Override public void close() {
            if (context == null || closed) return;
            if (!finished) finish("left-without-result", "reason=worker-exited-before-player-result");
            context.session.inFlight.remove(Thread.currentThread().getName(), context);
            if (previous == null) CURRENT_PLAYER.remove(); else CURRENT_PLAYER.set(previous);
            closed = true;
        }
    }

    public static final class HttpRequest {
        private final Session session;
        private final long id;
        private final String service;
        private final long startedNanos;
        private final PlayerContext context;
        private final String contextDetails;

        private HttpRequest(Session session, long id, String service, long startedNanos, PlayerContext context,
                            String contextDetails) {
            this.session = session;
            this.id = id;
            this.service = service;
            this.startedNanos = startedNanos;
            this.context = context;
            this.contextDetails = safe(contextDetails);
        }
    }
}
