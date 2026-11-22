package me.napim.gomod.features;

import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.OldServerPinger;

/**
 * Keeps a dedicated status-ping cache fresh using the same BetterHud-style network path without trusting
 * {@link Minecraft#getCurrentServerData()}'s mutable {@link ServerData#pingToServer} field directly.
 * Some servers / clients briefly stomp that live field with bogus values (for example ~1ms on Hypixel in-game),
 * so More HUD stores its own recent successful probe result and reads that instead.
 */
public final class MoreHudServerPingRefresher {

    private static final OldServerPinger PINGER = new OldServerPinger();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gomod-morehud-server-ping");
        t.setDaemon(true);
        return t;
    });

    private static final long REQUEST_INTERVAL_MS = 2500L;
    private static final long FRESH_CACHE_MS = 15000L;
    private static final long REQUEST_TIMEOUT_MS = 10000L;
    private static final int HISTORY_SIZE = 3;

    private static long lastRequestMs;
    private static long activeRequestStartedMs;
    private static volatile boolean requestInFlight;
    private static volatile ServerData activeProbeData;
    private static volatile String activeServerKey = "";
    private static volatile int lastStatusPingMs = -1;
    private static volatile long lastStatusPingWallMs;

    private static final int[] recentStatusPings = new int[HISTORY_SIZE];
    private static int recentStatusPingCount;
    private static int recentStatusPingWriteIndex;

    private MoreHudServerPingRefresher() {}

    /**
     * Call once per client tick (main thread). Flushes completed pings; occasionally queues a new status ping.
     */
    public static void clientTick(Minecraft mc) {
        try {
            PINGER.pingPendingNetworks();
        } catch (Throwable ignored) {
        }
        captureCompletedProbeIfReady();
        if (!ConfigManager.isMoreHudEnabled() || !ConfigManager.isMoreHudPingEnabled()) {
            return;
        }
        if (mc == null || mc.getCurrentServerData() == null) {
            resetStateForServer("");
            return;
        }
        String serverKey = buildServerKey(mc.getCurrentServerData());
        if (!serverKey.equals(activeServerKey)) {
            resetStateForServer(serverKey);
        }
        long now = System.currentTimeMillis();
        if (requestInFlight && (now - activeRequestStartedMs) > REQUEST_TIMEOUT_MS) {
            activeProbeData = null;
            requestInFlight = false;
        }
        if (requestInFlight || now - lastRequestMs < REQUEST_INTERVAL_MS) {
            return;
        }
        lastRequestMs = now;
        activeRequestStartedMs = now;
        requestInFlight = true;
        final String requestServerKey = serverKey;
        final ServerData probe = createProbeCopy(mc.getCurrentServerData());
        activeProbeData = probe;
        POOL.execute(() -> {
            try {
                PINGER.ping(probe);
            } catch (UnknownHostException ignored) {
                clearActiveProbeIfMatches(probe, requestServerKey);
            } catch (Throwable ignored) {
                clearActiveProbeIfMatches(probe, requestServerKey);
            }
        });
    }

    public static int getFreshPingMs() {
        long now = System.currentTimeMillis();
        if (lastStatusPingMs <= 0 || (now - lastStatusPingWallMs) > FRESH_CACHE_MS) {
            return -1;
        }
        return lastStatusPingMs;
    }

    public static int getRecentPingMs(long maxAgeMs) {
        long now = System.currentTimeMillis();
        if (lastStatusPingMs <= 0 || (now - lastStatusPingWallMs) > maxAgeMs) {
            return -1;
        }
        return lastStatusPingMs;
    }

    private static void captureCompletedProbeIfReady() {
        ServerData probe = activeProbeData;
        if (probe == null) {
            return;
        }
        long ping = probe.pingToServer;
        if (ping > 0L && ping < 60000L) {
            recordSuccessfulPing((int) ping);
            activeProbeData = null;
            requestInFlight = false;
        }
    }

    private static synchronized void recordSuccessfulPing(int pingMs) {
        recentStatusPings[recentStatusPingWriteIndex] = pingMs;
        recentStatusPingWriteIndex = (recentStatusPingWriteIndex + 1) % HISTORY_SIZE;
        if (recentStatusPingCount < HISTORY_SIZE) {
            recentStatusPingCount++;
        }
        lastStatusPingMs = medianRecentStatusPing();
        lastStatusPingWallMs = System.currentTimeMillis();
    }

    private static synchronized int medianRecentStatusPing() {
        if (recentStatusPingCount <= 0) {
            return -1;
        }
        int[] copy = new int[recentStatusPingCount];
        System.arraycopy(recentStatusPings, 0, copy, 0, recentStatusPingCount);
        java.util.Arrays.sort(copy);
        return copy[recentStatusPingCount / 2];
    }

    private static void clearActiveProbeIfMatches(ServerData probe, String requestServerKey) {
        if (activeProbeData == probe && activeServerKey.equals(requestServerKey)) {
            activeProbeData = null;
            requestInFlight = false;
        }
    }

    private static void resetStateForServer(String serverKey) {
        activeServerKey = serverKey == null ? "" : serverKey;
        activeProbeData = null;
        requestInFlight = false;
        activeRequestStartedMs = 0L;
        lastRequestMs = 0L;
        lastStatusPingMs = -1;
        lastStatusPingWallMs = 0L;
        synchronized (MoreHudServerPingRefresher.class) {
            recentStatusPingCount = 0;
            recentStatusPingWriteIndex = 0;
        }
    }

    private static String buildServerKey(ServerData data) {
        if (data == null || data.serverIP == null) {
            return "";
        }
        return data.serverIP.trim().toLowerCase();
    }

    private static ServerData createProbeCopy(ServerData data) {
        String name = data != null && data.serverName != null ? data.serverName : "More HUD Ping";
        String ip = data != null && data.serverIP != null ? data.serverIP : "";
        return new ServerData(name, ip, false);
    }
}
