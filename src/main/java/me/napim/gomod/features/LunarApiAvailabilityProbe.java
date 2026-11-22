package me.napim.gomod.features;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Small, payload-free readiness check used only by the background route watchdog. */
public final class LunarApiAvailabilityProbe {
    private LunarApiAvailabilityProbe() {}
    private static volatile String lastReason = "not-probed";
    private static volatile int lastHttpStatus;
    private static volatile long lastElapsedMs;
    private static volatile long retryAfterMs;
    private static final long NORMAL_RETRY_MS = 20_000L;
    private static final long RATE_LIMIT_RETRY_MS = 60_000L;
    private static volatile long nextDirectWakeProbeMs;
    private static volatile long directReadyUntilMs;
    private static volatile String directWakeDiagnostic = "not-probed";

    /** A public metadata request from the active player's network can wake a stalled host. */
    public static boolean directWakeProbeDue(long nowMs) {
        return nowMs >= nextDirectWakeProbeMs;
    }

    public static boolean hasFreshDirectReadiness() {
        return System.currentTimeMillis() < directReadyUntilMs;
    }

    public static String lastDirectWakeDiagnostic() {
        return directWakeDiagnostic;
    }

    /** Keep the direct host's retry policy separate from the relay's cached cooldown. */
    public static boolean probeDirectWake(URL endpoint, int timeoutMs) {
        String relayReason = lastReason;
        int relayStatus = lastHttpStatus;
        long relayElapsed = lastElapsedMs, relayRetry = retryAfterMs;
        try {
            boolean ready = isReady(endpoint, timeoutMs);
            directWakeDiagnostic = lastDiagnostic();
            nextDirectWakeProbeMs = System.currentTimeMillis()
                    + Math.max(RATE_LIMIT_RETRY_MS, retryAfterMs);
            directReadyUntilMs = ready ? System.currentTimeMillis() + 90_000L : 0L;
            return ready;
        } finally {
            lastReason = relayReason;
            lastHttpStatus = relayStatus;
            lastElapsedMs = relayElapsed;
            retryAfterMs = relayRetry;
        }
    }

    /** Poll startup more promptly; a host's bounded Retry-After hint takes precedence. */
    public static long nextRetryDelayMs() {
        return Math.max(lastHttpStatus == 429 ? RATE_LIMIT_RETRY_MS : NORMAL_RETRY_MS, retryAfterMs);
    }

    public static boolean isHostRateLimited() {
        return lastHttpStatus == 429;
    }

    private static long parseRetryAfterMs(String header, long nowMs) {
        if (header == null || header.trim().isEmpty()) return 0L;
        try {
            long delay;
            String value = header.trim();
            if (value.matches("[0-9]+")) {
                delay = Math.multiplyExact(Long.parseLong(value), 1_000L);
            } else {
                delay = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - nowMs;
            }
            // Reject malformed/past hints and bound extreme values rather than disabling retries.
            return Math.max(0L, Math.min(TimeUnit.HOURS.toMillis(1L), delay));
        } catch (RuntimeException invalidHint) {
            return 0L;
        }
    }

    public static String lastDiagnostic() {
        return "httpStatus=" + lastHttpStatus + " reason=" + lastReason + " elapsedMs=" + lastElapsedMs;
    }

    private static boolean result(boolean ready, String reason) {
        lastReason = reason;
        return ready;
    }

    public static boolean isReady(URL endpoint, int timeoutMs) {
        lastHttpStatus = 0;
        lastElapsedMs = 0L;
        retryAfterMs = 0L;
        if (endpoint == null || timeoutMs <= 0) return result(false, "invalid-probe");
        HttpURLConnection connection = null;
        final long started = System.nanoTime();
        final long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");
            int status = connection.getResponseCode();
            lastHttpStatus = status;
            retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"), System.currentTimeMillis());
            String type = connection.getContentType();
            if (status < 200 || status >= 300) return result(false,
                    status == 429 ? "host-rate-limited" : "http-unavailable");
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("application/json")) {
                return result(false, "not-json-loading-or-error-page");
            }
            long length = connection.getContentLengthLong();
            if (length > 64 * 1024) return result(false, "body-too-large");
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                int bytes = 0;
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) return result(false, "deadline-exceeded");
                    connection.setReadTimeout((int) Math.max(1L,
                            TimeUnit.NANOSECONDS.toMillis(remaining)));
                    int read = input.read(buffer);
                    if (read < 0) {
                        boolean ready = bytes > 0 && (length < 0 || bytes == length);
                        if (!ready) return result(false, "empty-or-truncated-body");
                        try {
                            JsonElement metadata = new JsonParser().parse(
                                    new String(body.toByteArray(), StandardCharsets.UTF_8));
                            if (!metadata.isJsonObject() || metadata.getAsJsonObject().has("error")) {
                                return result(false, "invalid-metadata-json");
                            }
                        } catch (RuntimeException malformed) {
                            return result(false, "invalid-metadata-json");
                        }
                        return result(true, "ready");
                    }
                    bytes += read;
                    if (bytes > 64 * 1024) return result(false, "body-too-large");
                    body.write(buffer, 0, read);
                }
            }
        } catch (Exception unavailable) {
            // Class only: exception text/URLs and response bodies can contain credentials.
            return result(false, unavailable.getClass().getSimpleName());
        } finally {
            lastElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (connection != null) connection.disconnect();
        }
    }
}
