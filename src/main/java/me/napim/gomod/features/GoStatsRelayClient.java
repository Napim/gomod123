package me.napim.gomod.features;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Account-authenticated client for GoStats AI and its relay-enforced daily quota. */
public final class GoStatsRelayClient {
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 35_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final long USAGE_REFRESH_INTERVAL_MS = 30_000L;
    private static final Object REFRESH_LOCK = new Object();
    private static volatile Usage usage;
    private static volatile String lastUsageError = "";
    private static volatile long lastRefreshAt;
    private static volatile boolean refreshInFlight;

    private GoStatsRelayClient() {
    }

    public static Usage getUsage() {
        return usage;
    }

    public static String getLastUsageError() {
        return lastUsageError;
    }

    public static boolean isUsageRefreshInFlight() {
        return refreshInFlight;
    }

    public static void refreshUsageAsync(boolean force) {
        final long now = System.currentTimeMillis();
        synchronized (REFRESH_LOCK) {
            if (refreshInFlight || (!force && now - lastRefreshAt < USAGE_REFRESH_INTERVAL_MS)) return;
            refreshInFlight = true;
            lastRefreshAt = now;
            lastUsageError = "";
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject response = request("GET", "/gostats123/usage", null);
                    usage = parseUsage(response);
                    lastUsageError = usage.available ? "" : friendlyError(stringField(response, "error"));
                } catch (Exception error) {
                    lastUsageError = error instanceof UsageException
                            ? error.getMessage() : friendlyError(error.getMessage());
                } finally {
                    synchronized (REFRESH_LOCK) {
                        refreshInFlight = false;
                    }
                }
            }
        }, "gostats123 quota refresh");
        thread.setDaemon(true);
        thread.start();
    }

    public static JsonObject ask(String question, String gameInfo) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("question", question == null ? "" : question);
        body.addProperty("gameInfo", gameInfo == null ? "" : gameInfo);
        JsonObject response = request("POST", "/gostats123/chat", body);
        if (!boolField(response, "ok")) {
            String code = stringField(response, "error");
            Quota quota = response.has("quota") && response.get("quota").isJsonObject()
                    ? parseQuota(response.getAsJsonObject("quota")) : null;
            if (quota != null) usage = new Usage(true, stringField(response, "model"), quota);
            throw new UsageException(code, friendlyError(code), quota);
        }
        if (response.has("quota") && response.get("quota").isJsonObject()) {
            usage = new Usage(true, stringField(response, "model"), parseQuota(response.getAsJsonObject("quota")));
        }
        return response;
    }

    private static JsonObject request(String method, String route, JsonObject payload) throws Exception {
        String configured = ConfigManager.getGlobalChatEndpoint();
        if (configured == null || configured.trim().isEmpty()) throw new IOException("relay_unavailable");
        URI base = new URI(configured.trim());
        String endpoint = new URI(base.getScheme(), base.getRawAuthority(), route, null, null).toString();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (payload != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
            }
            GomodRelayAuth.authorize(connection, endpoint);
            if (payload != null) {
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            GomodRelayAuth.noteResponseCode(endpoint, code);
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder responseBody = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (responseBody.length() + line.length() > MAX_RESPONSE_BYTES) {
                            throw new IOException("relay_response_too_large");
                        }
                        responseBody.append(line);
                    }
                }
            }
            JsonElement parsed;
            try {
                parsed = new JsonParser().parse(responseBody.toString());
            } catch (RuntimeException parseError) {
                throw new IOException("invalid_relay_response", parseError);
            }
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("invalid_relay_response");
            }
            JsonObject object = parsed.getAsJsonObject();
            if (code < 200 || code >= 300) {
                String errorCode = stringField(object, "error");
                Quota quota = object.has("quota") && object.get("quota").isJsonObject()
                        ? parseQuota(object.getAsJsonObject("quota")) : null;
                String message = friendlyError(errorCode);
                if (quota != null) usage = new Usage(true, stringField(object, "model"), quota);
                lastUsageError = message;
                throw new UsageException(errorCode, message, quota);
            }
            return object;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Usage parseUsage(JsonObject response) {
        Quota quota = response.has("quota") && response.get("quota").isJsonObject()
                ? parseQuota(response.getAsJsonObject("quota")) : Quota.empty();
        return new Usage(boolField(response, "available"), stringField(response, "model"), quota);
    }

    private static Quota parseQuota(JsonObject object) {
        return new Quota(longField(object, "dailyLimitTokens"), longField(object, "usedTokens"),
                longField(object, "reservedTokens"), longField(object, "remainingTokens"),
                longField(object, "sharedDailyLimitTokens"), longField(object, "sharedUsedTokens"),
                longField(object, "sharedReservedTokens"), longField(object, "sharedRemainingTokens"),
                longField(object, "resetAt"));
    }

    private static String friendlyError(String code) {
        if (code == null) return "gostats123 relay unavailable";
        switch (code) {
            case "service_not_configured": return "gostats123 is unavailable: the relay's OpenAI key isn't configured";
            case "usage_storage_unavailable": return "gostats123 quota storage is unavailable; requests are paused";
            case "authentication_required": return "Sign in to gomod Social to use gostats123";
            case "daily_token_limit_reached": return "Daily token allowance reached; it resets at 00:00 UTC";
            case "rate_limited": return "gostats123 is being used too quickly; try again shortly";
            case "openai_rate_limited": return "OpenAI is temporarily rate limited; try again shortly";
            case "openai_unavailable": return "OpenAI is temporarily unavailable";
            case "openai_invalid_response": return "OpenAI returned an invalid response";
            case "openai_empty_response": return "OpenAI returned an empty response";
            case "invalid_relay_response": return "gomod relay needs an update to support gostats123";
            case "question_required": return "Add a question after @gostats123";
            case "relay_unavailable": return "gomod relay is unavailable";
            default:
                if (code.startsWith("HTTP ")) return "gostats123 relay unavailable (" + code + ")";
                return "gostats123 relay unavailable";
        }
    }

    private static String stringField(JsonObject object, String name) {
        return object != null && object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString() : "";
    }

    private static long longField(JsonObject object, String name) {
        try {
            return object != null && object.has(name) ? Math.max(0L, object.get(name).getAsLong()) : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static boolean boolField(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && object.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static final class Usage {
        public final boolean available;
        public final String model;
        public final Quota quota;

        private Usage(boolean available, String model, Quota quota) {
            this.available = available;
            this.model = model == null ? "" : model;
            this.quota = quota == null ? Quota.empty() : quota;
        }
    }

    public static final class Quota {
        public final long dailyLimitTokens;
        public final long usedTokens;
        public final long reservedTokens;
        public final long remainingTokens;
        public final long sharedDailyLimitTokens;
        public final long sharedUsedTokens;
        public final long sharedReservedTokens;
        public final long sharedRemainingTokens;
        public final long resetAt;

        private Quota(long dailyLimitTokens, long usedTokens, long reservedTokens, long remainingTokens,
                      long sharedDailyLimitTokens, long sharedUsedTokens, long sharedReservedTokens,
                      long sharedRemainingTokens, long resetAt) {
            this.dailyLimitTokens = dailyLimitTokens;
            this.usedTokens = usedTokens;
            this.reservedTokens = reservedTokens;
            this.remainingTokens = remainingTokens;
            this.sharedDailyLimitTokens = sharedDailyLimitTokens;
            this.sharedUsedTokens = sharedUsedTokens;
            this.sharedReservedTokens = sharedReservedTokens;
            this.sharedRemainingTokens = sharedRemainingTokens;
            this.resetAt = resetAt;
        }

        private static Quota empty() {
            return new Quota(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    public static final class UsageException extends IOException {
        public final String code;
        public final Quota quota;

        private UsageException(String code, String message, Quota quota) {
            super(message);
            this.code = code == null ? "" : code;
            this.quota = quota;
        }
    }
}
