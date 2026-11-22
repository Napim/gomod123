package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** Event-triggered peer appearance refresh; HTTP long-poll avoids waiting for the normal social poll interval. */
public final class GomodAppearanceLongPoll {
    private static final Gson GSON = new Gson();
    private static final JsonParser JSON = new JsonParser();
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Gomod-PeerAppearanceLongPoll");
            thread.setDaemon(true);
            return thread;
        }
    });

    private static volatile long nextRequestAtMs;
    private static volatile long nextRouteCheckAtMs;
    private static volatile long lastRevision;
    private static volatile String lastServer = "";
    private static volatile int routeGeneration;
    private static Object lastWorld;
    private static volatile boolean initialPeerSnapshotPending;
    private static volatile HttpURLConnection activeConnection;

    private GomodAppearanceLongPoll() {
    }

    /** Called from the existing relay tick; normal HTTP polling remains the fallback. */
    public static void tick(Minecraft mc) {
        GomodCustomEmotes.tick();
        long now = System.currentTimeMillis();
        boolean worldChanged = mc != null && mc.theWorld != lastWorld;
        if (!worldChanged && now < nextRouteCheckAtMs) return;
        lastWorld = mc == null ? null : mc.theWorld;
        nextRouteCheckAtMs = now + 100L;
        if (!enabled() || mc == null || !GomodSocialPresence.shouldRelayPoll(mc)) {
            resetRoute();
            return;
        }
        String server = GomodSocialPresence.resolveLocalServer(mc);
        if (server == null || server.trim().isEmpty()
                || GomodSocialPresence.SERVER_MAIN_MENU.equalsIgnoreCase(server)
                || GomodSocialPresence.SERVER_SINGLEPLAYER.equalsIgnoreCase(server)) {
            resetRoute();
            return;
        }

        if (worldChanged || !server.equalsIgnoreCase(lastServer)) {
            lastServer = server;
            lastRevision = 0L;
            nextRequestAtMs = 0L;
            routeGeneration++;
            initialPeerSnapshotPending = true;
            cancelPreviousRouteRequest();
        }
        if (now < nextRequestAtMs || !IN_FLIGHT.compareAndSet(false, true)) return;
        final String requestedServer = server;
        final long requestedRevision = lastRevision;
        final int generation = routeGeneration;
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                poll(requestedServer, requestedRevision, generation);
            }
        });
    }

    private static boolean enabled() {
        return GomodFixes.isWeaveRuntime();
    }

    private static void resetRoute() {
        if (!lastServer.isEmpty()) {
            lastServer = "";
            lastRevision = 0L;
            nextRequestAtMs = 0L;
            routeGeneration++;
            cancelPreviousRouteRequest();
        }
    }

    private static void cancelPreviousRouteRequest() {
        final HttpURLConnection previous = activeConnection;
        if (previous == null) return;
        Thread cancel = new Thread(() -> previous.disconnect(), "Gomod-PeerAppearanceRouteCancel");
        cancel.setDaemon(true);
        cancel.start();
    }

    private static void poll(String server, long revision, int generation) {
        HttpURLConnection connection = null;
        String endpoint = ConfigManager.getGlobalChatEndpoint();
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "appearance_watch");
            payload.addProperty("server", server);
            payload.addProperty("appearanceRevision", revision);
            payload.addProperty("initialPeerSnapshot", initialPeerSnapshotPending);
            GlobalChatClient.attachVisiblePlayers(payload, Minecraft.getMinecraft());
            byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = connection;
            if (generation != routeGeneration) return;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
            GomodRelayAuth.authorize(connection, endpoint);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) GomodRelayAuth.noteResponseCode(endpoint, code);
            if (code < 200 || code >= 300) throw new IllegalStateException("relay_http_" + code);
            JsonObject response = JSON.parse(readBounded(connection.getInputStream())).getAsJsonObject();
            if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
                throw new IllegalStateException("relay_watch_rejected");
            }

            long returnedRevision = response.has("appearanceRevision")
                    ? response.get("appearanceRevision").getAsLong() : revision;
            synchronized (GomodAppearanceLongPoll.class) {
                if (generation != routeGeneration || !server.equalsIgnoreCase(lastServer)) return;
                lastRevision = Math.max(0L, returnedRevision);
                // A completed long-poll immediately waits again; server-side waiters wake only on appearance changes.
                nextRequestAtMs = System.currentTimeMillis() + 75L;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (generation == routeGeneration && enabled()) {
                            GomodPeerLunarFullAccess.onPollResponse(response);
                            GomodPeerLunarPlus.onPollResponse(response);
                            if (response.has("gomodPeersOnServer")) {
                                GomodPeerDisguise.onPollResponse(response);
                                initialPeerSnapshotPending = false;
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
            synchronized (GomodAppearanceLongPoll.class) {
                if (generation == routeGeneration) nextRequestAtMs = System.currentTimeMillis() + 2000L;
            }
        } finally {
            if (connection != null) connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
            IN_FLIGHT.set(false);
        }
    }

    private static String readBounded(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("relay_watch_response_too_large");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
