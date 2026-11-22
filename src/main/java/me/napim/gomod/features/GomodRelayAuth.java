package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Account-bound authentication for the gomod Social relay.
 *
 * <p>The relay supplies a one-time server id, the logged-in Minecraft session joins that id through
 * Mojang's session service, and the relay verifies it before issuing a short-lived bearer token.
 * Player names, UUIDs and gomod Social ids in normal relay payloads are therefore labels only; the
 * server derives the authoritative identity from this token.</p>
 */
public final class GomodRelayAuth {
    private static final Object LOCK = new Object();
    private static final JsonParser JSON = new JsonParser();
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_AUTH_RESPONSE_BYTES = 64 * 1024;
    private static final long EXPIRY_SAFETY_MS = 60_000L;
    /** Keep one failing client below the relay's ten-challenges-per-five-minutes limit. */
    private static final long AUTH_RETRY_BASE_MS = 30_000L;
    private static final long AUTH_RETRY_MAX_MS = 5L * 60_000L;
    private static final long AUTH_RATE_LIMIT_RETRY_MS = 5L * 60_000L + 5_000L;

    private static volatile String cachedOrigin = "";
    private static volatile String cachedToken = "";
    private static volatile String cachedUuid = "";
    private static volatile String cachedSocialId = "";
    private static volatile long cachedExpiresAt;
    private static String retryOrigin = "";
    private static String retryUuid = "";
    private static String retrySocialId = "";
    private static long retryNotBeforeMs;
    private static int consecutiveAuthFailures;

    private GomodRelayAuth() {}

    public static void authorize(HttpURLConnection connection, String relayEndpoint) throws Exception {
        if (connection == null) throw new IllegalArgumentException("connection");
        String token = ensureToken(relayEndpoint);
        connection.setRequestProperty("Authorization", "Bearer " + token);
    }

    public static String tokenForWebSocket(String relayEndpoint) throws Exception {
        return ensureToken(relayEndpoint);
    }

    /**
     * Return the already-issued token only. This method never performs HTTP or Mojang session
     * verification, so it is safe to use while building a relay hello on Minecraft's client thread.
     */
    public static String cachedTokenForWebSocket(String relayEndpoint) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null) {
            return "";
        }
        String uuid = GomodSocialIdentity.profileUuidUndashed(mc);
        String socialId = GomodSocialIdentity.socialId();
        if (uuid.isEmpty() || socialId.isEmpty()) {
            return "";
        }
        String origin;
        try {
            origin = relayOrigin(relayEndpoint);
        } catch (Exception ignored) {
            return "";
        }
        return tokenIsValid(origin, uuid, socialId, System.currentTimeMillis()) ? cachedToken : "";
    }

    public static boolean hasValidToken(String relayEndpoint) {
        return !cachedTokenForWebSocket(relayEndpoint).isEmpty();
    }

    public static void noteResponseCode(String relayEndpoint, int responseCode) {
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            synchronized (LOCK) {
                try {
                    String origin = relayOrigin(relayEndpoint);
                    if (!origin.equals(cachedOrigin) || cachedUuid.isEmpty() || cachedSocialId.isEmpty()) {
                        return;
                    }
                    String uuid = cachedUuid;
                    String socialId = cachedSocialId;
                    clearLocked();
                    noteAuthenticationFailureLocked(origin, uuid, socialId, null);
                } catch (Exception ignored) {
                    clearLocked();
                }
            }
        }
    }

    /** Record a server rejection of the token presented in a relay WebSocket hello. */
    public static long noteWebSocketAuthenticationRejected(String relayEndpoint) {
        synchronized (LOCK) {
            try {
                String origin = relayOrigin(relayEndpoint);
                if (!origin.equals(cachedOrigin) || cachedUuid.isEmpty() || cachedSocialId.isEmpty()) {
                    return 0L;
                }
                String uuid = cachedUuid;
                String socialId = cachedSocialId;
                clearLocked();
                return noteAuthenticationFailureLocked(origin, uuid, socialId, null);
            } catch (Exception ignored) {
                clearLocked();
                return 0L;
            }
        }
    }

    /** Remaining local authentication cooldown, used by the relay reconnect loop. */
    public static long retryAfterMs() {
        synchronized (LOCK) {
            return Math.max(0L, retryNotBeforeMs - System.currentTimeMillis());
        }
    }

    public static void invalidate(String relayEndpoint) {
        synchronized (LOCK) {
            if (relayEndpoint == null || relayEndpoint.trim().isEmpty()) {
                clearLocked();
                return;
            }
            try {
                if (relayOrigin(relayEndpoint).equals(cachedOrigin)) clearLocked();
            } catch (Exception ignored) {
                clearLocked();
            }
        }
    }

    private static String ensureToken(String relayEndpoint) throws Exception {
        final String origin = relayOrigin(relayEndpoint);
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null) {
            throw new Exception("Minecraft session is unavailable");
        }
        final String uuid = GomodSocialIdentity.profileUuidUndashed(mc);
        final String socialId = GomodSocialIdentity.socialId();
        final String player = mc.getSession().getUsername() == null ? "" : mc.getSession().getUsername().trim();
        if (uuid.isEmpty() || socialId.isEmpty() || player.isEmpty()) {
            throw new Exception("Minecraft identity is unavailable");
        }

        long now = System.currentTimeMillis();
        if (tokenIsValid(origin, uuid, socialId, now)) return cachedToken;

        synchronized (LOCK) {
            now = System.currentTimeMillis();
            resetAuthenticationRetryIdentityLocked(origin, uuid, socialId);
            if (tokenIsValid(origin, uuid, socialId, now)) return cachedToken;
            long retryAfterMs = retryNotBeforeMs - now;
            if (retryAfterMs > 0L) {
                throw new AuthRetryDeferredException(retryAfterMs);
            }

            try {
                JsonObject challengeRequest = new JsonObject();
                challengeRequest.addProperty("player", player);
                challengeRequest.addProperty("playerUuid", uuid);
                challengeRequest.addProperty("gomodSocialId", socialId);
                JsonObject challenge = postAuth(origin + "/auth/challenge", challengeRequest);
                String challengeId = stringField(challenge, "challengeId");
                String serverId = stringField(challenge, "serverId");
                if (challengeId.isEmpty() || serverId.isEmpty()) {
                    throw new Exception("Relay returned an invalid authentication challenge");
                }

                try {
                    mc.getSessionService().joinServer(mc.getSession().getProfile(), mc.getSession().getToken(), serverId);
                } catch (Throwable error) {
                    throw new Exception("Minecraft session verification failed: " + safeMessage(error), error);
                }

                JsonObject verifyRequest = new JsonObject();
                verifyRequest.addProperty("challengeId", challengeId);
                JsonObject verified = postAuth(origin + "/auth/verify", verifyRequest);
                String token = stringField(verified, "token");
                long expiresAt = verified.has("expiresAt") ? verified.get("expiresAt").getAsLong() : 0L;
                String verifiedUuid = normalizeUuid(stringField(verified, "playerUuid"));
                String verifiedSocialId = stringField(verified, "gomodSocialId").replace("-", "").toLowerCase();
                if (token.isEmpty() || expiresAt <= System.currentTimeMillis() || !uuid.equals(verifiedUuid)
                        || !socialId.equals(verifiedSocialId)) {
                    throw new Exception("Relay returned an invalid authenticated session");
                }

                cachedOrigin = origin;
                cachedToken = token;
                cachedUuid = uuid;
                cachedSocialId = socialId;
                cachedExpiresAt = expiresAt;
                resetAuthenticationRetryStateLocked(origin, uuid, socialId);
                return token;
            } catch (Exception failure) {
                noteAuthenticationFailureLocked(origin, uuid, socialId, failure);
                throw failure;
            }
        }
    }

    private static void resetAuthenticationRetryIdentityLocked(String origin, String uuid, String socialId) {
        if (!origin.equals(retryOrigin) || !uuid.equals(retryUuid) || !socialId.equals(retrySocialId)) {
            retryOrigin = origin;
            retryUuid = uuid;
            retrySocialId = socialId;
            retryNotBeforeMs = 0L;
            consecutiveAuthFailures = 0;
        }
    }

    private static void resetAuthenticationRetryStateLocked(String origin, String uuid, String socialId) {
        retryOrigin = origin;
        retryUuid = uuid;
        retrySocialId = socialId;
        retryNotBeforeMs = 0L;
        consecutiveAuthFailures = 0;
    }

    private static long noteAuthenticationFailureLocked(String origin, String uuid, String socialId,
                                                        Throwable failure) {
        resetAuthenticationRetryIdentityLocked(origin, uuid, socialId);
        long now = System.currentTimeMillis();
        int status = authHttpStatusCode(failure);
        long existingRetryMs = retryNotBeforeMs - now;
        if (existingRetryMs > 0L && status != 429) {
            return existingRetryMs;
        }
        long requestedBackoff;
        if (status == 429) {
            requestedBackoff = AUTH_RATE_LIMIT_RETRY_MS;
        } else {
            int shift = Math.min(4, consecutiveAuthFailures);
            requestedBackoff = Math.min(AUTH_RETRY_MAX_MS, AUTH_RETRY_BASE_MS << shift);
            consecutiveAuthFailures = Math.min(5, consecutiveAuthFailures + 1);
        }
        retryNotBeforeMs = Math.max(retryNotBeforeMs, now + requestedBackoff);
        return Math.max(0L, retryNotBeforeMs - now);
    }

    private static int authHttpStatusCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpException) return ((HttpException) current).getStatusCode();
        }
        return 0;
    }

    private static boolean tokenIsValid(String origin, String uuid, String socialId, long now) {
        return !cachedToken.isEmpty()
                && origin.equals(cachedOrigin)
                && uuid.equals(cachedUuid)
                && socialId.equals(cachedSocialId)
                && cachedExpiresAt - EXPIRY_SAFETY_MS > now;
    }

    private static void clearLocked() {
        cachedOrigin = "";
        cachedToken = "";
        cachedUuid = "";
        cachedSocialId = "";
        cachedExpiresAt = 0L;
    }

    private static JsonObject postAuth(String endpoint, JsonObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int code = connection.getResponseCode();
        byte[] responseBytes;
        try {
            responseBytes = readLimited(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        } finally {
            connection.disconnect();
        }
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) {
            throw new HttpException(code, responseErrorDetail(response, code));
        }
        JsonObject root;
        try {
            root = JSON.parse(response).getAsJsonObject();
        } catch (Exception parseError) {
            throw new Exception("gomod Service returned an invalid authentication response (HTTP " + code + ")", parseError);
        }
        if (!root.has("ok") || !root.get("ok").getAsBoolean()) {
            String error = stringField(root, "error");
            throw new Exception("gomod Service authentication failed: " + (error.isEmpty() ? "request rejected" : error));
        }
        return root;
    }

    private static String responseErrorDetail(String response, int code) {
        String detail = response == null ? "" : response.trim();
        try {
            JsonObject root = JSON.parse(detail).getAsJsonObject();
            String error = stringField(root, "error");
            String message = stringField(root, "message");
            String messageLower = message.toLowerCase(java.util.Locale.ROOT);
            if (messageLower.contains("free usage limit") || messageLower.contains("usage limit reached")) {
                detail = message;
            } else if (!error.isEmpty()) {
                detail = error;
            } else if (!message.isEmpty()) {
                detail = message;
            }
        } catch (Exception ignored) {
            // Some hosting layers return plain text instead of the relay's JSON error shape.
        }
        detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
        while (detail.contains("  ")) detail = detail.replace("  ", " ");
        if (detail.isEmpty()) detail = "HTTP " + code;
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("free usage limit") || lower.contains("usage limit reached")) {
            detail = "Free usage limit reached";
        } else if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<head")) {
            detail = "hosting service returned an HTTP error page";
        }
        if (detail.length() > 180) detail = detail.substring(0, 179) + "…";
        return detail;
    }

    /** HTTP response metadata lets the reconnect loop back off on host-side 429/5xx errors. */
    public static final class HttpException extends Exception {
        private final int statusCode;
        private final String responseDetail;

        private HttpException(int statusCode, String responseDetail) {
            super("gomod Service authentication returned HTTP " + statusCode + ": " + responseDetail);
            this.statusCode = statusCode;
            this.responseDetail = responseDetail == null ? "" : responseDetail;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseDetail() {
            return responseDetail;
        }
    }

    /** A retry was suppressed locally so a transient failure cannot flood auth endpoints. */
    public static final class AuthRetryDeferredException extends Exception {
        private final long retryAfterMs;

        private AuthRetryDeferredException(long retryAfterMs) {
            super("Authentication retry is cooling down to avoid repeated requests");
            this.retryAfterMs = Math.max(0L, retryAfterMs);
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }

    private static byte[] readLimited(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_AUTH_RESPONSE_BYTES) throw new Exception("Relay authentication response is too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String relayOrigin(String endpoint) throws Exception {
        if (endpoint == null || endpoint.trim().isEmpty()) throw new Exception("Relay URL is empty");
        URI input = new URI(endpoint.trim());
        String scheme = input.getScheme() == null ? "" : input.getScheme().toLowerCase();
        if ("wss".equals(scheme)) scheme = "https";
        if ("ws".equals(scheme)) scheme = "http";
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new Exception("Unsupported relay URL");
        if (input.getHost() == null || input.getHost().trim().isEmpty()) throw new Exception("Invalid relay URL");
        String host = input.getHost().trim().toLowerCase();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if ("http".equals(scheme) && !loopback) {
            throw new Exception("Relay authentication requires HTTPS (HTTP is allowed only on localhost)");
        }
        return new URI(scheme, null, input.getHost(), input.getPort(), null, null, null).toString();
    }

    private static String stringField(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try {
            return object.get(name).getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeUuid(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace("-", "").toLowerCase();
        return value.matches("[a-f0-9]{32}") ? value : "";
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message.trim();
    }
}
