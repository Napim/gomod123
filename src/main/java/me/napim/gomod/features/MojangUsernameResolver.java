package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Looks up the current Mojang profile name so friend requests and UI use official capitalization.
 */
public final class MojangUsernameResolver {
    private static final int CONNECT_MS = 2500;
    private static final int READ_MS = 2500;
    private static final long RATE_LIMIT_BACKOFF_MS = 120_000L;
    private static final long ERROR_BACKOFF_MS = 30_000L;
    private static final long NOT_FOUND_BACKOFF_MS = 300_000L;
    private static final Map<String, MojangProfile> PROFILE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> BACKOFF_UNTIL_MS = new ConcurrentHashMap<>();
    private static final Map<String, List<Consumer<MojangProfile>>> ASYNC_WAITERS = new ConcurrentHashMap<>();

    private MojangUsernameResolver() {}

    public static final class MojangProfile {
        public final String name;
        public final String id;

        private MojangProfile(String name, String id) {
            this.name = name;
            this.id = id;
        }
    }

    /**
     * @return Mojang canonical name for this account, or {@code null} if not found or on error.
     */
    public static String resolveCanonicalUsername(String tentativeName) {
        MojangProfile profile = resolveProfile(tentativeName);
        return profile == null ? null : profile.name;
    }

    /**
     * Returns an already-resolved canonical name without performing network I/O.
     * Use this from render/client-thread notification and matching paths.
     */
    public static String getCachedCanonicalUsername(String tentativeName) {
        if (tentativeName == null) {
            return null;
        }
        String trimmed = tentativeName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        MojangProfile profile = PROFILE_CACHE.get(trimmed.toLowerCase(Locale.ROOT));
        return profile == null ? null : profile.name;
    }

    /** Resolves canonical casing away from the render thread and coalesces duplicate requests. */
    public static void resolveCanonicalUsernameAsync(final String tentativeName, final Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        resolveProfileAsync(tentativeName, profile -> {
            if (profile != null) {
                callback.accept(profile.name);
            }
        });
    }

    /** Resolves a full profile once and delivers the result, including failures, to every waiting caller. */
    public static void resolveProfileAsync(final String tentativeName, final Consumer<MojangProfile> callback) {
        if (tentativeName == null || callback == null) {
            return;
        }
        final String trimmed = tentativeName.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        MojangProfile cached = PROFILE_CACHE.get(trimmed.toLowerCase(Locale.ROOT));
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        final String key = trimmed.toLowerCase(Locale.ROOT);
        synchronized (ASYNC_WAITERS) {
            List<Consumer<MojangProfile>> existing = ASYNC_WAITERS.get(key);
            if (existing != null) {
                existing.add(callback);
                return;
            }
            List<Consumer<MojangProfile>> callbacks = new ArrayList<>();
            callbacks.add(callback);
            ASYNC_WAITERS.put(key, callbacks);
        }
        Thread worker = new Thread(() -> {
            MojangProfile profile = null;
            try {
                profile = resolveProfile(trimmed);
            } finally {
                List<Consumer<MojangProfile>> callbacks;
                synchronized (ASYNC_WAITERS) {
                    callbacks = ASYNC_WAITERS.remove(key);
                }
                if (callbacks != null) {
                    for (Consumer<MojangProfile> waiting : callbacks) {
                        try {
                            waiting.accept(profile);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "gomod-mojang-profile-" + key);
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * @return Mojang canonical profile for this account, or {@code null} if not found / rate limited / on error.
     */
    public static MojangProfile resolveProfile(String tentativeName) {
        if (tentativeName == null) {
            return null;
        }
        String trimmed = tentativeName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 16) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_') {
                return null;
            }
        }

        String key = trimmed.toLowerCase(Locale.ROOT);
        MojangProfile cached = PROFILE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Long backoffUntil = BACKOFF_UNTIL_MS.get(key);
        long now = System.currentTimeMillis();
        if (backoffUntil != null && backoffUntil > now) {
            return null;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + trimmed);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setRequestProperty("User-Agent", "gomod123-mojang");
            int code = conn.getResponseCode();
            if (code == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
                    if (root == null || !root.has("name")) {
                        BACKOFF_UNTIL_MS.put(key, now + ERROR_BACKOFF_MS);
                        return null;
                    }
                    String canonical = root.get("name").getAsString();
                    if (canonical == null || canonical.trim().isEmpty()) {
                        BACKOFF_UNTIL_MS.put(key, now + ERROR_BACKOFF_MS);
                        return null;
                    }
                    String id = root.has("id") && !root.get("id").isJsonNull() ? root.get("id").getAsString() : "";
                    MojangProfile profile = new MojangProfile(canonical, id);
                    PROFILE_CACHE.put(key, profile);
                    PROFILE_CACHE.put(canonical.toLowerCase(Locale.ROOT), profile);
                    BACKOFF_UNTIL_MS.remove(key);
                    return profile;
                }
            }
            if (code == 204 || code == 404) {
                BACKOFF_UNTIL_MS.put(key, now + NOT_FOUND_BACKOFF_MS);
                return null;
            }
            if (code == 429) {
                BACKOFF_UNTIL_MS.put(key, now + RATE_LIMIT_BACKOFF_MS);
                return null;
            }
            BACKOFF_UNTIL_MS.put(key, now + ERROR_BACKOFF_MS);
            return null;
        } catch (Exception ignored) {
            BACKOFF_UNTIL_MS.put(key, now + ERROR_BACKOFF_MS);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
