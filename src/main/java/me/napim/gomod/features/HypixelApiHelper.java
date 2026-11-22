package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class HypixelApiHelper {
    private static final String GAME_COUNTS_URL = "https://api.hypixel.net/gameCounts?key=";
    private static final int JSON_FETCH_ATTEMPTS = 2;
    private static final String INCOMPLETE_JSON_MESSAGE =
            "The API returned an incomplete response twice. Please try again in a moment.";

    private HypixelApiHelper() {
    }

    /**
     * Result of probing Hypixel with {@code /gameCounts} (same endpoint as {@code /qstatus}).
     */
    public static final class HypixelKeyCheck {
        public final boolean checked;
        public final boolean valid;
        /** {@code true} when the request failed before a definitive key rejection. */
        public final boolean unreachable;
        /** Non-null when {@link #checked} and not {@link #valid}. */
        public final String failureDetail;

        private HypixelKeyCheck(boolean checked, boolean valid, boolean unreachable, String failureDetail) {
            this.checked = checked;
            this.valid = valid;
            this.unreachable = unreachable;
            this.failureDetail = failureDetail;
        }

        public static HypixelKeyCheck skippedEmpty() {
            return new HypixelKeyCheck(false, false, false, null);
        }

        public static HypixelKeyCheck valid() {
            return new HypixelKeyCheck(true, true, false, null);
        }

        public static HypixelKeyCheck invalid(String detail) {
            return new HypixelKeyCheck(true, false, false, detail == null ? "Invalid API key" : detail);
        }

        public static HypixelKeyCheck unreachable(String detail) {
            return new HypixelKeyCheck(true, false, true,
                    detail == null ? "Could not reach Hypixel API" : detail);
        }
    }

    /**
     * Validates a Hypixel developer API key using {@code GET /gameCounts} (used by {@code /qstatus}).
     */
    public static HypixelKeyCheck validateHypixelApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return HypixelKeyCheck.skippedEmpty();
        }
        try {
            String url = GAME_COUNTS_URL + URLEncoder.encode(apiKey.trim(), "UTF-8");
            JsonObject response = fetchJson(url);
            if (response != null && response.has("success") && response.get("success").getAsBoolean()) {
                return HypixelKeyCheck.valid();
            }
            String cause = "Hypixel API rejected the key";
            if (response != null && response.has("cause") && !response.get("cause").isJsonNull()) {
                cause = response.get("cause").getAsString();
            }
            return HypixelKeyCheck.invalid(cause);
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail != null && isLikelyInvalidKeyMessage(detail)) {
                return HypixelKeyCheck.invalid(detail);
            }
            return HypixelKeyCheck.unreachable(detail);
        }
    }

    public static boolean isLikelyInvalidKeyMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("invalid api key")
                || lower.contains("invalid key")
                || lower.contains("api key invalid")
                || lower.contains("forbidden")
                || lower.contains("http 403");
    }

    /**
     * True for temporary Hypixel/network failures that should not be treated as bad credentials or spammed to chat.
     * HTTP 429 is intentionally excluded because tab stats already has its own rate-limit handling.
     */
    public static boolean isTransientUpstreamFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException) {
                return true;
            }
            if (isTransientUpstreamFailure(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTransientUpstreamFailure(String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("http 408")
                || lower.contains("http 500")
                || lower.contains("http 502")
                || lower.contains("http 503")
                || lower.contains("http 504")
                || lower.contains("gateway timeout")
                || lower.contains("timed out");
    }

    public static JsonObject fetchJson(String urlString) throws Exception {
        return HypixelPlayerRequests.fetch(urlString, false, false, HypixelApiHelper::fetchJsonNetwork);
    }

    /** Critical selections bypass stored profiles and respect upstream cooldowns. */
    public static JsonObject fetchJsonFresh(String urlString) throws Exception {
        return HypixelPlayerRequests.fetch(urlString, true, false, HypixelApiHelper::fetchJsonNetwork);
    }

    /** Normal Blitz reads may reuse an attributed recent GoStats observation. */
    public static JsonObject fetchBlitzJson(String urlString, boolean freshRequired) throws Exception {
        return fetchBlitzJson(urlString, freshRequired, false);
    }

    /** Automatic TAB QoL profile reads keep their normal cache/freshness behavior but submit only 100+ kill profiles. */
    public static JsonObject fetchBlitzJson(String urlString, boolean freshRequired,
                                            boolean automaticTabStatsContributionGate) throws Exception {
        return HypixelPlayerRequests.fetch(urlString, freshRequired, true,
                requestUrl -> fetchJsonNetwork(requestUrl, automaticTabStatsContributionGate));
    }

    /**
     * Performs an explicit fresh Blitz lookup without queueing a best-effort duplicate contribution.
     * The caller must submit the response through the acknowledged contribution path.
     */
    public static JsonObject fetchBlitzJsonForExplicitRefresh(String urlString) throws Exception {
        return HypixelPlayerRequests.fetch(urlString, true, true,
                requestUrl -> fetchJsonNetwork(requestUrl, false, false));
    }

    private static JsonObject fetchJsonNetwork(String urlString) throws Exception {
        return fetchJsonNetwork(urlString, false);
    }

    private static JsonObject fetchJsonNetwork(String urlString, boolean automaticTabStatsContributionGate) throws Exception {
        return fetchJsonNetwork(urlString, automaticTabStatsContributionGate, true);
    }

    private static JsonObject fetchJsonNetwork(String urlString, boolean automaticTabStatsContributionGate,
                                              boolean observeContribution) throws Exception {
        Exception lastIncompleteResponse = null;
        for (int attempt = 0; attempt < JSON_FETCH_ATTEMPTS; attempt++) {
            try {
                JsonObject response = new JsonParser().parse(fetchText(urlString)).getAsJsonObject();
                if (observeContribution) {
                    if (automaticTabStatsContributionGate) {
                        GoStatsContributions.observeAutomaticTabStats(urlString, response);
                    } else {
                        GoStatsContributions.observe(urlString, response);
                    }
                }
                return response;
            } catch (Exception e) {
                if (!isIncompleteJsonResponse(e)) {
                    throw e;
                }
                if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
                    HypixelApiRequestDebug.recordForUrl("hypixel.json_incomplete", urlString,
                            "attempt=" + (attempt + 1) + " willRetry=" + (attempt + 1 < JSON_FETCH_ATTEMPTS));
                }
                lastIncompleteResponse = e;
            }
        }

        // Do not expose Gson's huge JSON path (which can include megabytes into a player
        // response) in chat. The cause remains available to debug logs/callers.
        throw new Exception(INCOMPLETE_JSON_MESSAGE, lastIncompleteResponse);
    }

    private static boolean isIncompleteJsonResponse(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof EOFException) {
                return true;
            }
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("end of input")
                    || lower.contains("end of file")
                    || lower.contains("unexpected end")
                    || lower.contains("unexpected eof")) {
                return true;
            }
        }
        return false;
    }

    public static String fetchText(String urlString) throws Exception {
        // Account for every real HTTP attempt (including JSON retry attempts) in the key-wide quota.
        HypixelPlayerRequests.awaitNetworkRequestSlot(urlString);
        try {
            return fetchTextViaJava(urlString);
        } catch (HttpFailure failure) {
            if (failure.status == 429) {
                HypixelPlayerRequests.noteApiKeyRateLimit(urlString, failure.retryAfterSeconds * 1000L,
                        failure.getMessage());
            }
            throw failure;
        }
    }

    /** Downloads a binary resource (e.g. cape PNG). */
    public static byte[] fetchBytes(String urlString) throws Exception {
        return fetchBytes(urlString, "gomod123/1.0");
    }

    /** Cape/CDN fetch with a browser-like user agent (capes.dev, Mojang textures). */
    public static byte[] fetchCapeImageBytes(String urlString) throws Exception {
        return fetchBytes(urlString,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    }

    private static byte[] fetchBytes(String urlString, String userAgent) throws Exception {
        HypixelApiRequestDebug.Request trace = HypixelApiRequestDebug.begin("GET", urlString);
        HttpURLConnection conn = null;
        int code = -1;
        String retryAfter = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept", "image/png,image/webp,image/apng,image/*,*/*;q=0.8");

            code = conn.getResponseCode();
            retryAfter = conn.getHeaderField("Retry-After");
            byte[] body = readBinaryStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code != 200) {
                throw new Exception(summarizeHttpFailure(code,
                        body == null ? "" : new String(body, StandardCharsets.UTF_8)));
            }
            if (body == null || body.length == 0) {
                throw new Exception("Empty response body");
            }
            HypixelApiRequestDebug.finish(trace, code, retryAfter, conn, null);
            return body;
        } catch (Exception failure) {
            HypixelApiRequestDebug.finish(trace, code, retryAfter, conn, failure);
            throw failure;
        } finally {
            if (conn != null) conn.disconnect();
        }

    }

    private static byte[] readBinaryStream(InputStream stream) throws Exception {
        if (stream == null) {
            return new byte[0];
        }
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = stream.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String fetchTextViaJava(String urlString) throws Exception {
        HypixelApiRequestDebug.Request trace = HypixelApiRequestDebug.begin("GET", urlString);
        LeaderboardRefreshDebugCapture.HttpRequest leaderboardTrace =
                LeaderboardRefreshDebugCapture.beginCurrentHttp("hypixel", "GET", urlString);
        HttpURLConnection conn = null;
        int code = -1;
        String retryAfter = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "gomod123/1.0");

            code = conn.getResponseCode();
            retryAfter = conn.getHeaderField("Retry-After");
            String body = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code != 200) {
                throw new HttpFailure(code, retryAfter, summarizeHttpFailure(code, body));
            }
            if (body == null || body.trim().isEmpty()) {
                throw new Exception("Empty response body");
            }

            HypixelApiRequestDebug.finish(trace, code, retryAfter, conn, null);
            LeaderboardRefreshDebugCapture.finishHttp(leaderboardTrace, code, retryAfter, null);
            return body;
        } catch (Exception failure) {
            HypixelApiRequestDebug.finish(trace, code, retryAfter, conn, failure);
            LeaderboardRefreshDebugCapture.finishHttp(leaderboardTrace, code, retryAfter, failure);
            throw failure;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString().trim();
        }
    }

    public static final class HttpFailure extends Exception {
        public final int status;
        public final long retryAfterSeconds;
        HttpFailure(int status, String retryAfter, String message) {
            super(message);
            this.status = status;
            long seconds = 60;
            try { seconds = Long.parseLong(retryAfter); } catch (Exception ignored) { }
            this.retryAfterSeconds = Math.max(1L, seconds);
        }
    }

    private static String summarizeHttpFailure(int code, String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return "HTTP " + code;
        }

        try {
            JsonObject root = new JsonParser().parse(trimmed).getAsJsonObject();
            String detail = jsonString(root, "cause");
            if (detail.isEmpty()) {
                detail = jsonString(root, "error");
            }
            if (detail.isEmpty()) {
                detail = jsonString(root, "message");
            }
            if (!detail.isEmpty()) {
                return "HTTP " + code + " (" + compactDetail(detail) + ")";
            }
        } catch (Exception ignored) {
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") || lower.contains("<html")) {
            if (code == 429) {
                return "HTTP 429 (request blocked or rate limited)";
            }
            if (code == 403) {
                return "HTTP 403 (request blocked)";
            }
            return "HTTP " + code + " (server returned an HTML error page)";
        }

        return "HTTP " + code + " (" + compactDetail(trimmed) + ")";
    }

    private static String jsonString(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return "";
        }
        return root.get(key).getAsString();
    }

    private static String compactDetail(String detail) {
        if (detail == null) {
            return "";
        }
        String compact = detail.replaceAll("\\s+", " ").trim();
        if (compact.length() > 160) {
            return compact.substring(0, 157) + "...";
        }
        return compact;
    }
}
