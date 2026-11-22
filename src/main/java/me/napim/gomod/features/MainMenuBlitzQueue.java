package me.napim.gomod.features;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main-menu quick-queue buttons for Hypixel Blitz Survival Games (Solo and Teams).
 * Shared by both the Forge build (GuiMainMenu) and the Lunar build (WebOSR Home + GuiMainMenu).
 */
public final class MainMenuBlitzQueue {
    public enum Mode {
        SOLO("solo", "/play blitz_solo_normal"),
        TEAMS("teams", "/play blitz_teams_normal");

        public final String id;
        public final String command;

        Mode(String id, String command) {
            this.id = id;
            this.command = command;
        }

        public static Mode fromName(String raw) {
            if (raw == null) {
                return null;
            }
            String lower = raw.trim().toLowerCase(Locale.ROOT);
            if ("solo".equals(lower) || "blitz_solo_normal".equals(lower)) {
                return SOLO;
            }
            if ("teams".equals(lower) || "team".equals(lower) || "blitz_teams_normal".equals(lower)) {
                return TEAMS;
            }
            return null;
        }
    }

    public static final class CountSnapshot {
        public final int soloPlayers;
        public final int teamsPlayers;
        public final boolean loading;
        public final boolean missingApiKey;
        public final boolean invalidApiKey;
        public final long lastUpdatedMs;

        public CountSnapshot(int soloPlayers, int teamsPlayers, boolean loading,
                             boolean missingApiKey, boolean invalidApiKey, long lastUpdatedMs) {
            this.soloPlayers = soloPlayers;
            this.teamsPlayers = teamsPlayers;
            this.loading = loading;
            this.missingApiKey = missingApiKey;
            this.invalidApiKey = invalidApiKey;
            this.lastUpdatedMs = lastUpdatedMs;
        }

        public String soloLabel() {
            return formatButtonLabel("Queue Solo", soloPlayers, loading);
        }

        public String teamsLabel() {
            return formatButtonLabel("Queue Teams", teamsPlayers, loading);
        }
    }

    private static final String GAME_COUNTS_URL = "https://api.hypixel.net/gameCounts?key=";
    private static final String DEFAULT_HYPIXEL_HOST = "mc.hypixel.net";
    private static final long REFRESH_INTERVAL_MS = 20_000L;
    private static final long PENDING_TIMEOUT_MS = 45_000L;
    private static final int JOIN_SETTLE_TICKS = 20;

    private static final Object STATE_LOCK = new Object();
    private static final AtomicBoolean FETCH_IN_FLIGHT = new AtomicBoolean(false);

    private static volatile int cachedSoloPlayers = -1;
    private static volatile int cachedTeamsPlayers = -1;
    private static volatile boolean missingApiKey = false;
    private static volatile boolean invalidApiKey = false;
    private static volatile long lastAttemptMs = 0L;
    private static volatile long lastUpdatedMs = 0L;

    private static volatile Mode pendingMode = null;
    private static volatile long pendingArmedAtMs = 0L;
    private static volatile boolean pendingSawConnecting = false;

    private MainMenuBlitzQueue() {
    }

    /** Formats a button label such as {@code Queue Solo (10)} or plain {@code Queue Solo} when unavailable. */
    public static String formatButtonLabel(String prefix, int count, boolean loading) {
        if (count >= 0) {
            return prefix + " (" + count + ")";
        }
        return prefix;
    }

    public static boolean isEnabled() {
        try {
            return ConfigManager.isMainMenuQueueButtonsEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns the current live count snapshot without performing any blocking I/O. */
    public static CountSnapshot getSnapshot() {
        return new CountSnapshot(
                cachedSoloPlayers,
                cachedTeamsPlayers,
                FETCH_IN_FLIGHT.get(),
                missingApiKey,
                invalidApiKey,
                lastUpdatedMs
        );
    }

    /** Triggers a background refresh if the cached counts are stale (older than 20 seconds). */
    public static void ensureFreshCounts() {
        if (!isEnabled()) {
            return;
        }
        String rawKey = ApiKeyManager.getApiKey("hypixel");
        final String apiKey = rawKey == null ? "" : rawKey.trim();
        if (apiKey.isEmpty()) {
            missingApiKey = true;
            invalidApiKey = false;
            return;
        }
        missingApiKey = false;
        if (GameModeStatusFetcher.shouldSkipKnownBadHypixelRequests(apiKey)) {
            invalidApiKey = true;
            return;
        }
        invalidApiKey = false;

        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < REFRESH_INTERVAL_MS) {
            return;
        }
        if (!FETCH_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        lastAttemptMs = now;

        Thread worker = new Thread(() -> {
            try {
                String url = GAME_COUNTS_URL + URLEncoder.encode(apiKey, "UTF-8");
                JsonObject response = HypixelApiHelper.fetchJson(url);
                int[] parsed = parseBlitzModeCounts(response);
                if (parsed != null) {
                    cachedSoloPlayers = parsed[0];
                    cachedTeamsPlayers = parsed[1];
                    lastUpdatedMs = System.currentTimeMillis();
                    invalidApiKey = false;
                    GameModeStatusFetcher.noteHypixelApiSuccess(apiKey);
                }
            } catch (Throwable error) {
                String detail = error.getMessage();
                if (GameModeStatusFetcher.handleHypixelInvalidKeyFailure(apiKey, detail, false)) {
                    invalidApiKey = true;
                }
            } finally {
                FETCH_IN_FLIGHT.set(false);
            }
        }, "gomod-mainmenu-blitz-counts");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Parses {@code games.SURVIVAL_GAMES.modes.{solo_normal, teams_normal}} from a Hypixel
     * {@code /gameCounts} response. Returns {@code [solo, teams]} or {@code null} if invalid.
     */
    public static int[] parseBlitzModeCounts(JsonObject response) {
        if (response == null || !response.has("success") || !response.get("success").getAsBoolean()) {
            return null;
        }
        JsonObject games = getObject(response, "games");
        if (games == null) {
            return null;
        }
        JsonObject survival = getObject(games, "SURVIVAL_GAMES");
        if (survival == null) {
            return null;
        }
        JsonObject modes = getObject(survival, "modes");
        int solo = readNonNegativeInt(modes, "solo_normal", 0);
        int teams = readNonNegativeInt(modes, "teams_normal", 0);
        return new int[] { solo, teams };
    }

    /** JSON payload consumed by Lunar's WebOSR main menu buttons. */
    public static String getUiSnapshotJson() {
        CountSnapshot snap = getSnapshot();
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", isEnabled());
        obj.addProperty("solo", snap.soloPlayers);
        obj.addProperty("teams", snap.teamsPlayers);
        obj.addProperty("soloLabel", snap.soloLabel());
        obj.addProperty("teamsLabel", snap.teamsLabel());
        obj.addProperty("loading", snap.loading);
        obj.addProperty("missingKey", snap.missingApiKey);
        obj.addProperty("invalidKey", snap.invalidApiKey);
        obj.addProperty("lastUpdatedMs", snap.lastUpdatedMs);
        return obj.toString();
    }

    /** Entry point for WebOSR JS callback ({@code "solo"} or {@code "teams"}). */
    public static boolean triggerQuickQueueByName(String rawMode) {
        Mode mode = Mode.fromName(rawMode);
        if (mode == null) {
            return false;
        }
        triggerQuickQueue(mode);
        return true;
    }

    /**
     * Connects the client directly to Hypixel and arms the automatic {@code /play} command
     * once the player lands in a Hypixel world. Safe to call from any thread.
     */
    public static void triggerQuickQueue(final Mode mode) {
        if (mode == null) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> connectAndArm(mc, mode));
    }

    private static void connectAndArm(Minecraft mc, Mode mode) {
        synchronized (STATE_LOCK) {
            pendingMode = mode;
            pendingArmedAtMs = System.currentTimeMillis();
            pendingSawConnecting = false;
        }

        // If somehow already in a Hypixel world, just send the /play command immediately.
        if (mc.theWorld != null && mc.thePlayer != null && isHypixelServerData(mc.getCurrentServerData())) {
            sendAndClearPending(mc, mode);
            return;
        }

        ServerData serverData = resolveHypixelServerData(mc);
        if (mc.theWorld != null) {
            try {
                mc.theWorld.sendQuittingDisconnectingPacket();
            } catch (Throwable ignored) {
            }
            mc.loadWorld(null);
        }
        mc.setServerData(serverData);
        GuiScreen parent = mc.currentScreen != null ? mc.currentScreen : new GuiMainMenu();
        mc.displayGuiScreen(new GuiConnecting(parent, mc, serverData));
    }

    /** Called every client tick from {@code Minecraft.runTick}. */
    public static void onClientTick(Minecraft mc) {
        Mode active = pendingMode;
        if (active == null || mc == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - pendingArmedAtMs > PENDING_TIMEOUT_MS) {
            clearPending();
            return;
        }

        GuiScreen screen = mc.currentScreen;
        if (screen instanceof GuiConnecting) {
            pendingSawConnecting = true;
            return;
        }
        if (screen instanceof GuiDisconnected) {
            clearPending();
            return;
        }

        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            // User cancelled out of GuiConnecting back to the menu before joining.
            if (pendingSawConnecting) {
                clearPending();
            }
            return;
        }

        // Connected to a world: wait ~1 second for Hypixel's initial lobby join to settle.
        if (mc.thePlayer.ticksExisted < JOIN_SETTLE_TICKS) {
            return;
        }

        if (!isHypixelServerData(mc.getCurrentServerData())) {
            clearPending();
            return;
        }

        sendAndClearPending(mc, active);
    }

    private static void sendAndClearPending(Minecraft mc, Mode mode) {
        clearPending();
        try {
            if (mc != null && mc.thePlayer != null) {
                mc.thePlayer.sendChatMessage(mode.command);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearPending() {
        synchronized (STATE_LOCK) {
            pendingMode = null;
            pendingArmedAtMs = 0L;
            pendingSawConnecting = false;
        }
    }

    static Mode getPendingModeForTest() {
        return pendingMode;
    }

    static void armPendingForTest(Mode mode, long armedAtMs) {
        synchronized (STATE_LOCK) {
            pendingMode = mode;
            pendingArmedAtMs = armedAtMs;
            pendingSawConnecting = false;
        }
    }

    static void resetForTest() {
        cachedSoloPlayers = -1;
        cachedTeamsPlayers = -1;
        missingApiKey = false;
        invalidApiKey = false;
        lastAttemptMs = 0L;
        lastUpdatedMs = 0L;
        FETCH_IN_FLIGHT.set(false);
        clearPending();
    }

    static ServerData resolveHypixelServerData(Minecraft mc) {
        try {
            if (mc != null) {
                ServerList list = new ServerList(mc);
                list.loadServerList();
                int count = list.countServers();
                for (int i = 0; i < count; i++) {
                    ServerData entry = list.getServerData(i);
                    if (entry != null && isMainHypixelAddress(entry.serverIP)) {
                        return entry;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ServerData("Hypixel", DEFAULT_HYPIXEL_HOST, false);
    }

    static boolean isMainHypixelAddress(String rawIp) {
        if (rawIp == null) {
            return false;
        }
        String host = rawIp.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return "mc.hypixel.net".equals(host)
                || "play.hypixel.net".equals(host)
                || "proxy.hypixel.net".equals(host)
                || "hypixel.net".equals(host);
    }

    private static boolean isHypixelServerData(ServerData data) {
        if (data == null || data.serverIP == null) {
            return false;
        }
        String host = data.serverIP.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }

    private static JsonObject getObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static int readNonNegativeInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) {
            return fallback;
        }
        try {
            JsonElement el = obj.get(key);
            if (el == null || el.isJsonNull()) {
                return fallback;
            }
            return Math.max(0, el.getAsInt());
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
