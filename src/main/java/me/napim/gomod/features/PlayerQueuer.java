package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.command.KillEffectCommand;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ChatComponentText;
import net.minecraft.entity.player.EntityPlayer;
import net.weavemc.loader.api.event.PacketEvent.Receive;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlayerQueuer feature queues games aiming to join lobbies containing one of the target players.
 *
 * Behavior:
 * - Maintain a list of target player names.
 * - When Hypixel sends {@code The game starts in 3 seconds!}, wait a configurable delay (0–3s) for TAB check;
 *   longer delay pushes the check closer to actual start so high-ping clients can compensate.
 * - Chooses /play blitz_solo_normal or /play blitz_teams_normal based on scoreboard containing the word "Team".
 * - After /limbo, waits long enough for the dodged game to actually start (Map Queuer-style: remaining countdown + buffer,
 *   at least the slider delay) before {@code /play}.
 * - If Hypixel returns "Something went wrong trying to send you to that server!" after /limbo or /play, retries after
 *   {@link #TRANSFER_FAIL_RETRY_DELAY_MS} while the requeue session is still active.
 *
 * Persistence:
 * - Saves enabled flag and target players to {@code <gameDir>/gomod123/playerqueuer.json} (Forge).
 */
public class PlayerQueuer {
    /** Same idea as {@link TabDuplicateNickResolver}: pull a bare IGN from decorated TAB-style names. */
    private static final Pattern QUEUER_USERNAME_TOKEN = Pattern.compile("(?i)\\b[A-Za-z0-9_]{3,16}\\b");

    private static File playerQueuerConfigFile() {
        return new File(GomodPaths.getGomod123Dir(), "playerqueuer.json");
    }

    private static volatile boolean enabled = false;
    private static final Set<String> targetPlayers = new HashSet<>();
    private static final Set<String> excludePlayers = new HashSet<>();
    private static final Set<String> deathTrackPlayers = new HashSet<>(); // Players to track for death requeue
    /** Include/exclude names with list-row glow turned off (default is on). */
    private static final Set<String> glowDisabledPlayers = new HashSet<>();
    private static volatile boolean requireAllInclude = false; // false = any include, true = all include
    private static volatile boolean requireAllExclude = false; // false = any exclude triggers, true = all exclude triggers
    /** Treat every nick identified by the denicker/TAB resolver as an include target. */
    private static volatile boolean includeAllNicks = false;
    /** Treat players whose cached Blitz WLR is at or above this threshold as include targets. */
    private static volatile boolean includeWlrRange = false;
    private static volatile double includeWlrThreshold = 0.85D;
    /** Dynamic exclude rules mirror the include rules; exclude still wins when both sides match. */
    private static volatile boolean excludeAllNicks = false;
    private static volatile boolean excludeWlrRange = false;
    private static volatile double excludeWlrThreshold = 0.85D;
    /** Seconds to wait after {@code The game starts in 3 seconds!} before TAB check (0 = right when line appears locally; up to {@value #TAB_CHECK_AFTER_3S_DELAY_MAX}s ≈ horn). */
    public static final float TAB_CHECK_AFTER_3S_DELAY_DEFAULT = 1.5f;
    public static final float TAB_CHECK_AFTER_3S_DELAY_MIN = 0.0f;
    public static final float TAB_CHECK_AFTER_3S_DELAY_MAX = 3.0f;
    private static volatile float tabCheckDelayAfter3Seconds = TAB_CHECK_AFTER_3S_DELAY_DEFAULT;
    /** Bumped on each 3-second countdown packet so superseded sleeps never run TAB check. */
    private static final AtomicInteger tabCheckScheduleGeneration = new AtomicInteger(0);

    /** Lowercase stored IGN → Mojang-canonical capitalization for chat (see {@link #pqMojangCaseForChat}). */
    private static final ConcurrentHashMap<String, String> PQ_MOJANG_CASE_CACHE = new ConcurrentHashMap<>();

    private static void pqRememberMojangCase(String storedLower, String mojangCanonicalName) {
        if (storedLower == null || mojangCanonicalName == null || mojangCanonicalName.trim().isEmpty()) {
            return;
        }
        PQ_MOJANG_CASE_CACHE.put(storedLower.trim().toLowerCase(Locale.ROOT), mojangCanonicalName.trim());
    }

    private static void pqForgetMojangCase(String storedLower) {
        if (storedLower == null) {
            return;
        }
        PQ_MOJANG_CASE_CACHE.remove(storedLower.trim().toLowerCase(Locale.ROOT));
    }

    /** Mojang API casing for HUD/chat; caches and skips repeated resolves. */
    private static String pqMojangCaseForChat(String storedLowercaseIgn) {
        if (storedLowercaseIgn == null) {
            return "";
        }
        String key = storedLowercaseIgn.trim().toLowerCase(Locale.ROOT);
        String cached = PQ_MOJANG_CASE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String resolved = correctUsername(storedLowercaseIgn);
        PQ_MOJANG_CASE_CACHE.put(key, resolved);
        return resolved;
    }

    private static long millisQueueSpacingMatchingSlider() {
        long ms = Math.round(tabCheckDelayAfter3Seconds * 1000.0);
        return Math.max(0L, Math.min(3000L, ms));
    }

    /** Bumped on each new requeue sequence or when disabling; stale retry threads bail out. */
    private static final AtomicInteger requeueTransferSession = new AtomicInteger(0);
    private static final Object requeueTransferLock = new Object();
    /** Commands sent by this feature are allowed to pass through the outgoing-chat cancellation hook. */
    private static final ThreadLocal<Boolean> AUTOMATED_COMMAND_SEND = new ThreadLocal<>();
    /** Fallback for chat hooks that run on a different thread than the automated send caller. */
    private static final Object AUTOMATED_COMMAND_LOCK = new Object();
    private static volatile String expectedAutomatedCommand;
    private static volatile long expectedAutomatedCommandUntilMs;
    /** Last /play blitz_* sent by PlayerQueuer for this session (after /limbo). */
    private static volatile String pendingRequeuePlayCommand = null;
    private static volatile long lastRequeueMainSentAtMs = 0L;
    private static volatile long lastRequeuePlaySentAtMs = 0L;
    private static volatile long requeuePlayNotBeforeAtMs = 0L;
    private static volatile long lastRequeueLimboPresenceAtMs = 0L;
    private static volatile long lastRequeuePlayAcceptedAtMs = 0L;
    private static int requeueLimboBounceRetries = 0;
    private static volatile boolean transferFailRetryPending = false;
    private static volatile int transferFailRetryPendingSession = -1;
    private static final int TRANSFER_FAIL_RETRY_DELAY_MS = 6000;
    private static final int LIMBO_BOUNCE_RETRY_DELAY_MS = 2000;
    private static final int PLAY_DISPATCH_READY_RETRIES = 8;
    private static final int MAX_LIMBO_BOUNCE_RETRIES = 3;
    private static final long REQUEUE_SESSION_MAX_AGE_MS = 10L * 60L * 1000L;
    private static final long REQUEUE_FAILURE_CORRELATION_WINDOW_MS = 90_000L;
    /** Extra limbo wait after countdown hits 0 so Hypixel does not pull us back into the dodged game (matches Map Queuer). */
    private static final int POST_MAIN_START_BUFFER_SEC = 2;

    private static final String[] TRANSFER_COMMANDS = {
            "/l", "/lobby", "/hub", "/requeue", "/play", "/limbo"
    };

    /**
     * Cancels delayed TAB checks and transfer retries when the user starts another server transfer.
     * This is called from the outgoing chat hook and from connection lifecycle events.
     */
    public static void onOutgoingChatCommand(String message) {
        if (consumeExpectedAutomatedCommand(message) || Boolean.TRUE.equals(AUTOMATED_COMMAND_SEND.get())
                || !isTransferCommand(message)) {
            return;
        }
        invalidatePendingRequeueTransfers("outgoing transfer command");
        tabCheckScheduleGeneration.incrementAndGet();
    }

    /** Invalidates work that belongs to the previous Hypixel connection/world. */
    public static void onConnectionTransition() {
        // /limbo deliberately changes the active Hypixel world/connection. Keep that expected
        // transition attached to its queue session until /play either succeeds or expires.
        synchronized (requeueTransferLock) {
            long lastTransfer = Math.max(lastRequeueMainSentAtMs, lastRequeuePlaySentAtMs);
            if (enabled && pendingRequeuePlayCommand != null && lastTransfer > 0L
                    && System.currentTimeMillis() - lastTransfer <= REQUEUE_SESSION_MAX_AGE_MS) {
                tabCheckScheduleGeneration.incrementAndGet();
                return;
            }
        }
        invalidatePendingRequeueTransfers("connection transition outside an active queue transfer");
        tabCheckScheduleGeneration.incrementAndGet();
    }

    private static boolean isTransferCommand(String message) {
        if (message == null) {
            return false;
        }
        String command = message.trim().toLowerCase(Locale.ROOT);
        for (String prefix : TRANSFER_COMMANDS) {
            if (command.equals(prefix) || command.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    private static void runOnClientThread(Runnable task) {
        if (task == null) {
            return;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            if (mc.isCallingFromMinecraftThread()) {
                task.run();
            } else {
                mc.addScheduledTask(task);
            }
        } catch (Throwable t) {
            System.err.println("[PlayerQueuer] Failed to schedule client-thread task: " + t.getMessage());
        }
    }

    private static void sendAutomatedChat(Minecraft mc, String command) {
        if (mc == null || mc.thePlayer == null || command == null || command.isEmpty()) {
            return;
        }
        synchronized (AUTOMATED_COMMAND_LOCK) {
            expectedAutomatedCommand = normalizeTransferCommand(command);
            expectedAutomatedCommandUntilMs = System.currentTimeMillis() + 1500L;
        }
        AUTOMATED_COMMAND_SEND.set(Boolean.TRUE);
        try {
            mc.thePlayer.sendChatMessage(command);
        } finally {
            AUTOMATED_COMMAND_SEND.remove();
        }
    }

    private static boolean consumeExpectedAutomatedCommand(String message) {
        String normalized = normalizeTransferCommand(message);
        if (normalized == null) return false;
        synchronized (AUTOMATED_COMMAND_LOCK) {
            if (expectedAutomatedCommand == null
                    || !expectedAutomatedCommand.equals(normalized)
                    || System.currentTimeMillis() > expectedAutomatedCommandUntilMs) {
                return false;
            }
            expectedAutomatedCommand = null;
            expectedAutomatedCommandUntilMs = 0L;
            return true;
        }
    }

    private static String normalizeTransferCommand(String message) {
        if (message == null) return null;
        String normalized = message.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return isTransferCommand(normalized) ? normalized : null;
    }

    /** Loads configuration from disk. */
    public static void loadConfig() {
        try {
            File dir = GomodPaths.getGomod123Dir();
            if (!dir.exists()) dir.mkdirs();
            File file = playerQueuerConfigFile();
            if (!file.exists()) {
                saveConfig();
                return;
            }
            try (FileReader reader = new FileReader(file)) {
                com.google.gson.JsonObject obj = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
                enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
                targetPlayers.clear();
                if (obj.has("players") && obj.get("players").isJsonArray()) {
                    com.google.gson.JsonArray arr = obj.getAsJsonArray("players");
                    for (int i = 0; i < arr.size(); i++) {
                        String name = arr.get(i).getAsString();
                        if (name != null && !name.trim().isEmpty()) {
                            targetPlayers.add(name.trim().toLowerCase());
                        }
                    }
                }
                excludePlayers.clear();
                if (obj.has("excludePlayers") && obj.get("excludePlayers").isJsonArray()) {
                    com.google.gson.JsonArray arr = obj.getAsJsonArray("excludePlayers");
                    for (int i = 0; i < arr.size(); i++) {
                        String name = arr.get(i).getAsString();
                        if (name != null && !name.trim().isEmpty()) {
                            excludePlayers.add(name.trim().toLowerCase());
                        }
                    }
                }
                deathTrackPlayers.clear();
                if (obj.has("deathTrackPlayers") && obj.get("deathTrackPlayers").isJsonArray()) {
                    com.google.gson.JsonArray arr = obj.getAsJsonArray("deathTrackPlayers");
                    for (int i = 0; i < arr.size(); i++) {
                        String name = arr.get(i).getAsString();
                        if (isValidDeathTrackName(name)) {
                            deathTrackPlayers.add(normalizeDeathTrackName(name));
                        }
                    }
                }
                glowDisabledPlayers.clear();
                if (obj.has("glowDisabledPlayers") && obj.get("glowDisabledPlayers").isJsonArray()) {
                    com.google.gson.JsonArray arr = obj.getAsJsonArray("glowDisabledPlayers");
                    for (int i = 0; i < arr.size(); i++) {
                        String name = arr.get(i).getAsString();
                        if (name != null && !name.trim().isEmpty()) {
                            glowDisabledPlayers.add(name.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                // Backward compatibility for older config key
                if (obj.has("requireAllPlayers")) {
                    requireAllInclude = obj.get("requireAllPlayers").getAsBoolean();
                }
                if (obj.has("requireAllInclude")) {
                    requireAllInclude = obj.get("requireAllInclude").getAsBoolean();
                }
                if (obj.has("requireAllExclude")) {
                    requireAllExclude = obj.get("requireAllExclude").getAsBoolean();
                }
                includeAllNicks = obj.has("includeAllNicks") && obj.get("includeAllNicks").getAsBoolean();
                includeWlrRange = obj.has("includeWlrRange") && obj.get("includeWlrRange").getAsBoolean();
                includeWlrThreshold = readWlrThreshold(obj, "includeWlrThreshold",
                        "includeWlrMin", "includeWlrMax", 0.85D);
                excludeAllNicks = obj.has("excludeAllNicks") && obj.get("excludeAllNicks").getAsBoolean();
                excludeWlrRange = obj.has("excludeWlrRange") && obj.get("excludeWlrRange").getAsBoolean();
                excludeWlrThreshold = readWlrThreshold(obj, "excludeWlrThreshold",
                        "excludeWlrMin", "excludeWlrMax", 0.85D);
                enforceDynamicRuleExclusivityAfterLoad();
                if (obj.has("tabCheckDelayAfter3sSeconds")) {
                    tabCheckDelayAfter3Seconds = clampTabCheckDelayAfter3(obj.get("tabCheckDelayAfter3sSeconds").getAsFloat());
                } else if (obj.has("requeueSecondsBeforeStart")) {
                    // Legacy: discrete line index 1–4 → crude delay-after-3s (4 = fast, 1 = late near start)
                    int oldN = obj.get("requeueSecondsBeforeStart").getAsInt();
                    oldN = Math.max(1, Math.min(4, oldN));
                    tabCheckDelayAfter3Seconds = clampTabCheckDelayAfter3((4 - oldN) * 1.0f);
                }
            }
        } catch (Exception e) {
            System.err.println("[PlayerQueuer] Failed to load config: " + e.getMessage());
        }
    }

    /** Saves configuration to disk. */
    private static void saveConfig() {
        try {
            File dir = GomodPaths.getGomod123Dir();
            if (!dir.exists()) dir.mkdirs();
            File file = playerQueuerConfigFile();
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("requireAllInclude", requireAllInclude);
            obj.addProperty("requireAllExclude", requireAllExclude);
            obj.addProperty("includeAllNicks", includeAllNicks);
            obj.addProperty("includeWlrRange", includeWlrRange);
            obj.addProperty("includeWlrThreshold", includeWlrThreshold);
            obj.addProperty("includeWlrMin", includeWlrThreshold);
            obj.addProperty("includeWlrMax", includeWlrThreshold);
            obj.addProperty("excludeAllNicks", excludeAllNicks);
            obj.addProperty("excludeWlrRange", excludeWlrRange);
            obj.addProperty("excludeWlrThreshold", excludeWlrThreshold);
            obj.addProperty("excludeWlrMin", excludeWlrThreshold);
            obj.addProperty("excludeWlrMax", excludeWlrThreshold);
            obj.addProperty("tabCheckDelayAfter3sSeconds", tabCheckDelayAfter3Seconds);
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (String p : new ArrayList<>(targetPlayers)) {
                arr.add(new com.google.gson.JsonPrimitive(p));
            }
            obj.add("players", arr);
            com.google.gson.JsonArray arrEx = new com.google.gson.JsonArray();
            for (String p : new ArrayList<>(excludePlayers)) {
                arrEx.add(new com.google.gson.JsonPrimitive(p));
            }
            obj.add("excludePlayers", arrEx);
            com.google.gson.JsonArray arrDeath = new com.google.gson.JsonArray();
            for (String p : new ArrayList<>(deathTrackPlayers)) {
                arrDeath.add(new com.google.gson.JsonPrimitive(p));
            }
            obj.add("deathTrackPlayers", arrDeath);
            com.google.gson.JsonArray arrGlowOff = new com.google.gson.JsonArray();
            for (String p : new ArrayList<>(glowDisabledPlayers)) {
                arrGlowOff.add(new com.google.gson.JsonPrimitive(p));
            }
            obj.add("glowDisabledPlayers", arrGlowOff);
            try (FileWriter writer = new FileWriter(file)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj, writer);
            }
        } catch (Exception e) {
            System.err.println("[PlayerQueuer] Failed to save config: " + e.getMessage());
        }
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        if (!value) {
            invalidatePendingRequeueTransfers("Player Queuer disabled");
            tabCheckScheduleGeneration.incrementAndGet();
        }
        enabled = value;
        saveConfig();
        Main.sendMessage(Main.CHAT_PREFIX + (value ? EnumChatFormatting.GREEN + "Player Queuer enabled" : EnumChatFormatting.RED + "Player Queuer disabled"));
    }

    /** Silent enable/disable for GUI usage: no chat messages. */
    public static void setEnabledSilent(boolean value) {
        if (!value) {
            invalidatePendingRequeueTransfers("Player Queuer disabled");
            tabCheckScheduleGeneration.incrementAndGet();
        }
        enabled = value;
        saveConfig();
    }

    public static List<String> getTargets() {
        return new ArrayList<>(targetPlayers);
    }

    public static boolean isIncludeAllNicks() { return includeAllNicks; }

    public static void setIncludeAllNicksSilent(boolean value) {
        includeAllNicks = value;
        if (value) excludeAllNicks = false;
        saveConfig();
    }

    public static boolean isIncludeWlrRange() { return includeWlrRange; }
    public static double getIncludeWlrMin() { return includeWlrThreshold; }
    public static double getIncludeWlrMax() { return includeWlrThreshold; }
    public static double getIncludeWlrThreshold() { return includeWlrThreshold; }

    public static void setIncludeWlrRangeSilent(boolean enabledValue, double min, double max) {
        setIncludeWlrThresholdSilent(enabledValue, min);
    }

    public static void setIncludeWlrThresholdSilent(boolean enabledValue, double threshold) {
        includeWlrRange = enabledValue;
        includeWlrThreshold = sanitizeWlr(threshold, 0.85D);
        if (enabledValue) excludeWlrRange = false;
        saveConfig();
    }

    public static boolean isExcludeAllNicks() { return excludeAllNicks; }

    public static void setExcludeAllNicksSilent(boolean value) {
        excludeAllNicks = value;
        if (value) includeAllNicks = false;
        saveConfig();
    }

    public static boolean isExcludeWlrRange() { return excludeWlrRange; }
    public static double getExcludeWlrMin() { return excludeWlrThreshold; }
    public static double getExcludeWlrMax() { return excludeWlrThreshold; }
    public static double getExcludeWlrThreshold() { return excludeWlrThreshold; }

    public static void setExcludeWlrRangeSilent(boolean enabledValue, double min, double max) {
        setExcludeWlrThresholdSilent(enabledValue, min);
    }

    public static void setExcludeWlrThresholdSilent(boolean enabledValue, double threshold) {
        excludeWlrRange = enabledValue;
        excludeWlrThreshold = sanitizeWlr(threshold, 0.85D);
        if (enabledValue) includeWlrRange = false;
        saveConfig();
    }

    /** WLR matching deliberately consumes join-scan cache only; it never launches API work at the 3-second check. */
    public static boolean areWlrIncludePrerequisitesMet() {
        return ApiKeyManager.hasApiKey("hypixel")
                && KillCounter.getAutoScanMode() == KillCounter.AutoScanMode.PLAYERS_JOIN;
    }

    private static double sanitizeWlr(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        return Math.max(0.0D, Math.min(999.0D, value));
    }

    private static double readWlrThreshold(com.google.gson.JsonObject obj, String thresholdKey,
            String oldMinKey, String oldMaxKey, double fallback) {
        if (obj.has(thresholdKey)) {
            return sanitizeWlr(obj.get(thresholdKey).getAsDouble(), fallback);
        }
        boolean hasMin = obj.has(oldMinKey);
        boolean hasMax = obj.has(oldMaxKey);
        double oldMin = hasMin ? sanitizeWlr(obj.get(oldMinKey).getAsDouble(), fallback) : fallback;
        double oldMax = hasMax ? sanitizeWlr(obj.get(oldMaxKey).getAsDouble(), fallback) : fallback;
        if (hasMin && hasMax && Math.abs(oldMin) < 0.000001D && Math.abs(oldMax - 0.85D) < 0.000001D) {
            return oldMax;
        }
        return hasMin ? oldMin : oldMax;
    }

    private static void enforceDynamicRuleExclusivityAfterLoad() {
        if (includeAllNicks && excludeAllNicks) includeAllNicks = false;
        if (includeWlrRange && excludeWlrRange) includeWlrRange = false;
    }

    public static String addTarget(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Provide a player name.";
        }
        final String requested = name.trim();

        // Resolve and correct username off-thread to avoid UI hitch
        new Thread(() -> {
            String correctedName = correctUsername(requested);
            String norm = correctedName.toLowerCase();
            pqRememberMojangCase(norm, correctedName);
            synchronized (targetPlayers) {
                if (!targetPlayers.contains(norm)) {
                    targetPlayers.add(norm);
                    saveConfig();
                }
            }
            // Notify on client thread
            try {
                Minecraft.getMinecraft().addScheduledTask(() -> Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Added target: " + correctedName));
            } catch (Throwable ignored) {}
        }, "PQ-AddInclude").start();

        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Adding target...";
    }

    /** Silent variant for GUI usage: no chat messages. */
    public static void addTargetSilent(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        final String requested = name.trim();
        
        // Add immediately with original name, then correct in background
        String norm = requested.toLowerCase();
        synchronized (targetPlayers) {
            if (!targetPlayers.contains(norm)) {
                targetPlayers.add(norm);
                saveConfig();
            }
        }
        
        // Correct username in background and update if different
        new Thread(() -> {
            String correctedName = correctUsername(requested);
            String correctedNorm = correctedName.toLowerCase();
            pqRememberMojangCase(correctedNorm, correctedName);
            synchronized (targetPlayers) {
                if (!correctedNorm.equals(norm) && !targetPlayers.contains(correctedNorm)) {
                    targetPlayers.remove(norm); // Remove original
                    targetPlayers.add(correctedNorm); // Add corrected
                    pqForgetMojangCase(norm);
                    saveConfig();
                }
            }
        }, "PQ-AddInclude-Silent").start();
    }

    public static String removeTarget(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Provide a player name.";
        }
        String norm = name.trim().toLowerCase();
        if (targetPlayers.remove(norm)) {
            pqForgetMojangCase(norm);
            saveConfig();
            return Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Removed target: " + name;
        }
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + name + " was not in the list.";
    }

    /** Silent remove for GUI. */
    public static void removeTargetSilent(String name) {
        if (name == null || name.trim().isEmpty()) return;
        String norm = name.trim().toLowerCase();
        if (targetPlayers.remove(norm)) {
            pqForgetMojangCase(norm);
            clearPlayerGlowDisabled(norm);
            saveConfig();
        }
    }

    public static void clearTargets() {
        synchronized (targetPlayers) {
            for (String p : new ArrayList<>(targetPlayers)) {
                pqForgetMojangCase(p);
            }
            targetPlayers.clear();
        }
        saveConfig();
    }

    /** Silent clear for GUI. */
    public static void clearTargetsSilent() {
        synchronized (targetPlayers) {
            for (String p : new ArrayList<>(targetPlayers)) {
                pqForgetMojangCase(p);
                glowDisabledPlayers.remove(p);
            }
            targetPlayers.clear();
        }
        saveConfig();
    }

    public static List<String> getExcludeTargets() {
        return new ArrayList<>(excludePlayers);
    }

    /** True if {@code name} is explicitly on the Dodge list (nick/real alias aware). */
    public static boolean isExcludeTargetListed(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        synchronized (excludePlayers) {
            for (String stored : excludePlayers) {
                if (matchesSingleQueuerEntry(name, stored)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Light blue chameleon outline for include targets ({@link me.napim.gomod.mixins.RenderGlobalMixin}). */
    public static final int QUEUER_INCLUDE_OUTLINE_RGB = 0x55CCFF;
    /** Red chameleon outline for exclude targets (same pipeline as include). */
    public static final int QUEUER_EXCLUDE_OUTLINE_RGB = 0xFF4040;
    /** @deprecated Use {@link #QUEUER_INCLUDE_OUTLINE_RGB}. */
    public static final int QUEUER_TARGET_OUTLINE_RGB = QUEUER_INCLUDE_OUTLINE_RGB;

    /**
     * True when Player Queuer is on and this player matches an include target by profile or visible name
     * (same idea as {@link GlowPlayer#isGlowingPlayer2(EntityPlayer)} for nicked clients).
     */
    public static boolean isQueuedTargetPlayer(EntityPlayer player) {
        if (!enabled) {
            return false;
        }
        String matched = resolveMatchedQueuerTargetName(player);
        if (matched == null) {
            return false;
        }
        synchronized (targetPlayers) {
            return isGlowEnabledForListMatch(matched, targetPlayers);
        }
    }

    /**
     * True when Player Queuer is on and this player matches an exclude target (same name matching as include).
     */
    public static boolean isQueuedExcludePlayer(EntityPlayer player) {
        if (!enabled) {
            return false;
        }
        String matched = resolveMatchedQueuerExcludeName(player);
        if (matched == null) {
            return false;
        }
        synchronized (excludePlayers) {
            return isGlowEnabledForListMatch(matched, excludePlayers);
        }
    }

    /** Resolves glow toggle using the stored include/exclude row name (nick-safe). */
    private static boolean isGlowEnabledForListMatch(String matchedRawName, Set<String> listSnap) {
        if (matchedRawName == null || listSnap.isEmpty()) {
            return true;
        }
        for (String stored : listSnap) {
            if (matchesQueuerTarget(matchedRawName, Collections.singleton(stored))) {
                return isPlayerGlowEnabled(stored);
            }
        }
        return isPlayerGlowEnabled(matchedRawName);
    }

    /** Whether include/exclude list aura is enabled for this stored or display name (default on). */
    public static boolean isPlayerGlowEnabled(String name) {
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        return !glowDisabledPlayers.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Toggles list-row glow for a player. Returns the new enabled state ({@code true} = glow on).
     */
    public static boolean togglePlayerGlow(String name) {
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        String norm = name.trim().toLowerCase(Locale.ROOT);
        boolean nowEnabled;
        if (glowDisabledPlayers.remove(norm)) {
            nowEnabled = true;
        } else {
            glowDisabledPlayers.add(norm);
            nowEnabled = false;
        }
        saveConfig();
        return nowEnabled;
    }

    private static void clearPlayerGlowDisabled(String name) {
        if (name == null) {
            return;
        }
        String norm = name.trim().toLowerCase(Locale.ROOT);
        if (glowDisabledPlayers.remove(norm)) {
            saveConfig();
        }
    }

    private static String stripColorCodesForQueuer(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("(?i)[\\u00A7&][0-9a-fk-or]", "");
    }

    private static String stripLeadingBracketPrefixes(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        int closeBracket;
        while (s.startsWith("[") && (closeBracket = s.indexOf(']')) > 0) {
            s = s.substring(closeBracket + 1).trim();
        }
        return s;
    }

    private static String lastUsernameTokenLower(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = QUEUER_USERNAME_TOKEN.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group().toLowerCase(Locale.ROOT);
        }
        return last;
    }

    /**
     * Match stored targets (already lowercased) against a raw profile or display name:
     * exact trim+lower, normalized strip (colors + rank brackets), last username token,
     * plus denick nick/real aliases when {@link Denicker} is on.
     */
    private static boolean matchesQueuerTarget(String raw, Set<String> targetsLower) {
        if (raw == null || targetsLower.isEmpty()) {
            return false;
        }
        for (String stored : targetsLower) {
            if (matchesSingleQueuerEntry(raw, stored)) {
                return true;
            }
        }
        return false;
    }

    /** Stored list row (lowercase) plus known nick/real aliases from {@link TabDuplicateNickResolver}. */
    static Set<String> queuerAliasesForStored(String storedLower) {
        Set<String> aliases = new HashSet<>();
        if (storedLower == null || storedLower.trim().isEmpty()) {
            return aliases;
        }
        String key = storedLower.trim().toLowerCase(Locale.ROOT);
        aliases.add(key);
        if (Denicker.isEnabled()) {
            String real = TabDuplicateNickResolver.getKnownRealName(key);
            if (real != null && !real.trim().isEmpty()) {
                aliases.add(real.trim().toLowerCase(Locale.ROOT));
            }
            String nick = TabDuplicateNickResolver.getKnownDuplicateNick(key);
            if (nick != null && !nick.trim().isEmpty()) {
                aliases.add(nick.trim().toLowerCase(Locale.ROOT));
            }
        }
        return aliases;
    }

    /**
     * Other nick/real identity for a stored queuer row (for GUI). Returns {@code null} when unknown or same as stored.
     */
    public static String getDenickAliasForDisplay(String storedLower) {
        if (storedLower == null || !Denicker.isEnabled()) {
            return null;
        }
        String key = storedLower.trim().toLowerCase(Locale.ROOT);
        String nick = TabDuplicateNickResolver.getKnownDuplicateNick(key);
        if (nick != null && !nick.trim().isEmpty() && !nick.equalsIgnoreCase(key)) {
            return nick.trim();
        }
        String real = TabDuplicateNickResolver.getKnownRealName(key);
        if (real != null && !real.trim().isEmpty() && !real.equalsIgnoreCase(key)) {
            return real.trim();
        }
        return null;
    }

    private static boolean matchesSingleQueuerEntry(String raw, String storedLower) {
        if (raw == null || storedLower == null) {
            return false;
        }
        if (matchesQueuerTargetAgainstAliasSet(raw, queuerAliasesForStored(storedLower))) {
            return true;
        }
        if (!Denicker.isEnabled()) {
            return false;
        }
        for (String token : extractNameTokensForQueuer(raw)) {
            String real = TabDuplicateNickResolver.getKnownRealName(token);
            if (real != null && real.trim().equalsIgnoreCase(storedLower.trim())) {
                return true;
            }
            String nick = TabDuplicateNickResolver.getKnownDuplicateNick(token);
            if (nick != null && nick.trim().equalsIgnoreCase(storedLower.trim())) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<String> extractNameTokensForQueuer(String raw) {
        java.util.List<String> tokens = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return tokens;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (!t.isEmpty()) {
            tokens.add(t);
        }
        String plain = stripColorCodesForQueuer(raw).trim();
        String decoStripped = stripLeadingBracketPrefixes(plain).trim().toLowerCase(Locale.ROOT);
        if (!decoStripped.isEmpty() && !tokens.contains(decoStripped)) {
            tokens.add(decoStripped);
        }
        String tok = lastUsernameTokenLower(plain);
        if (tok != null && !tokens.contains(tok)) {
            tokens.add(tok);
        }
        tok = lastUsernameTokenLower(decoStripped);
        if (tok != null && !tokens.contains(tok)) {
            tokens.add(tok);
        }
        return tokens;
    }

    private static boolean matchesQueuerTargetAgainstAliasSet(String raw, Set<String> aliasesLower) {
        if (raw == null || aliasesLower == null || aliasesLower.isEmpty()) {
            return false;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (aliasesLower.contains(t)) {
            return true;
        }
        String plain = stripColorCodesForQueuer(raw).trim();
        String decoStripped = stripLeadingBracketPrefixes(plain).trim().toLowerCase(Locale.ROOT);
        if (!decoStripped.isEmpty() && aliasesLower.contains(decoStripped)) {
            return true;
        }
        String tok = lastUsernameTokenLower(plain);
        if (tok != null && aliasesLower.contains(tok)) {
            return true;
        }
        tok = lastUsernameTokenLower(decoStripped);
        return tok != null && aliasesLower.contains(tok);
    }

    static String resolveMatchedQueuerTargetName(EntityPlayer player) {
        if (!enabled || player == null) {
            return null;
        }
        final Set<String> snap;
        synchronized (targetPlayers) {
            if (targetPlayers.isEmpty()) {
                return null;
            }
            snap = new HashSet<>(targetPlayers);
        }
        try {
            com.mojang.authlib.GameProfile gp = player.getGameProfile();
            if (gp != null && gp.getName() != null && matchesQueuerTarget(gp.getName(), snap)) {
                return gp.getName();
            }
        } catch (Throwable ignored) {
        }
        String vis = player.getName();
        if (vis != null && matchesQueuerTarget(vis, snap)) {
            return vis;
        }
        return null;
    }

    static String resolveMatchedQueuerExcludeName(EntityPlayer player) {
        if (!enabled || player == null) {
            return null;
        }
        final Set<String> snap;
        synchronized (excludePlayers) {
            if (excludePlayers.isEmpty()) {
                return null;
            }
            snap = new HashSet<>(excludePlayers);
        }
        try {
            com.mojang.authlib.GameProfile gp = player.getGameProfile();
            if (gp != null && gp.getName() != null && matchesQueuerTarget(gp.getName(), snap)) {
                return gp.getName();
            }
        } catch (Throwable ignored) {
        }
        String vis = player.getName();
        if (vis != null && matchesQueuerTarget(vis, snap)) {
            return vis;
        }
        return null;
    }

    public static String addExcludeTarget(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Provide a player name.";
        }
        final String requested = name.trim();

        new Thread(() -> {
            String correctedName = correctUsername(requested);
            String norm = correctedName.toLowerCase();
            pqRememberMojangCase(norm, correctedName);
            synchronized (excludePlayers) {
                if (!excludePlayers.contains(norm)) {
                    excludePlayers.add(norm);
                    removeDeathTrackMatches(correctedName);
                    saveConfig();
                }
            }
            try {
                Minecraft.getMinecraft().addScheduledTask(() -> Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Excluded: " + correctedName));
            } catch (Throwable ignored) {}
        }, "PQ-AddExclude").start();

        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Adding to exclude...";
    }

    /** Silent exclude add for GUI. */
    public static void addExcludeTargetSilent(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        final String requested = name.trim();
        
        // Add immediately with original name, then correct in background
        String norm = requested.toLowerCase();
        synchronized (excludePlayers) {
            if (!excludePlayers.contains(norm)) {
                excludePlayers.add(norm);
                removeDeathTrackMatches(requested);
                saveConfig();
            }
        }
        
        // Correct username in background and update if different
        new Thread(() -> {
            String correctedName = correctUsername(requested);
            String correctedNorm = correctedName.toLowerCase();
            pqRememberMojangCase(correctedNorm, correctedName);
            synchronized (excludePlayers) {
                if (!correctedNorm.equals(norm) && !excludePlayers.contains(correctedNorm)) {
                    excludePlayers.remove(norm); // Remove original
                    excludePlayers.add(correctedNorm); // Add corrected
                    pqForgetMojangCase(norm);
                    saveConfig();
                }
            }
        }, "PQ-AddExclude-Silent").start();
    }

    public static String removeExcludeTarget(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Provide a player name.";
        }
        String norm = name.trim().toLowerCase();
        if (excludePlayers.remove(norm)) {
            pqForgetMojangCase(norm);
            saveConfig();
            return Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Removed from exclude: " + name;
        }
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + name + " was not in the exclude list.";
    }

    /** Silent exclude remove for GUI. */
    public static void removeExcludeTargetSilent(String name) {
        if (name == null || name.trim().isEmpty()) return;
        String norm = name.trim().toLowerCase();
        if (excludePlayers.remove(norm)) {
            pqForgetMojangCase(norm);
            clearPlayerGlowDisabled(norm);
            saveConfig();
        }
    }

    public static void clearExcludeTargets() {
        synchronized (excludePlayers) {
            for (String p : new ArrayList<>(excludePlayers)) {
                pqForgetMojangCase(p);
            }
            excludePlayers.clear();
        }
        saveConfig();
    }

    /** Silent exclude clear for GUI. */
    public static void clearExcludeTargetsSilent() {
        synchronized (excludePlayers) {
            for (String p : new ArrayList<>(excludePlayers)) {
                pqForgetMojangCase(p);
                glowDisabledPlayers.remove(p);
            }
            excludePlayers.clear();
        }
        saveConfig();
    }

    public static boolean isRequireAllInclude() { return requireAllInclude; }
    public static boolean isRequireAllExclude() { return requireAllExclude; }

    public static void setRequireAllInclude(boolean value) {
        requireAllInclude = value;
        saveConfig();
        Main.sendMessage(Main.CHAT_PREFIX + (value ? EnumChatFormatting.GREEN + "Include mode: ALL required" : EnumChatFormatting.YELLOW + "Include mode: ANY allowed"));
    }

    /** Silent include mode set for GUI. */
    public static void setRequireAllIncludeSilent(boolean value) {
        requireAllInclude = value;
        saveConfig();
    }

    public static void setRequireAllExclude(boolean value) {
        requireAllExclude = value;
        saveConfig();
        Main.sendMessage(Main.CHAT_PREFIX + (value ? EnumChatFormatting.GREEN + "Exclude mode: ALL required" : EnumChatFormatting.YELLOW + "Exclude mode: ANY allowed"));
    }

    /** Silent exclude mode set for GUI. */
    public static void setRequireAllExcludeSilent(boolean value) {
        requireAllExclude = value;
        saveConfig();
    }

    public static float getTabCheckDelayAfter3Seconds() {
        return tabCheckDelayAfter3Seconds;
    }

    public static void setTabCheckDelayAfter3Silent(float secondsAfter3sLine) {
        float clamped = clampTabCheckDelayAfter3(secondsAfter3sLine);
        if (Math.abs(clamped - tabCheckDelayAfter3Seconds) < 1e-4f) {
            return;
        }
        tabCheckDelayAfter3Seconds = clamped;
        saveConfig();
    }

    private static float clampTabCheckDelayAfter3(float seconds) {
        if (seconds != seconds || seconds < TAB_CHECK_AFTER_3S_DELAY_MIN) {
            return TAB_CHECK_AFTER_3S_DELAY_MIN;
        }
        return Math.min(TAB_CHECK_AFTER_3S_DELAY_MAX, seconds);
    }

    private static boolean isGameStartsInThreeSecondsMessage(String message) {
        return message != null
                && (message.contains("The game starts in 3 seconds!")
                || message.contains("The game starts in 3 second!"));
    }

    /** Schedule TAB/requeue logic after configured delay once the fixed 3s countdown line fires. */
    private static void scheduleTabCheckAfter3SecondLine() {
        int generation = tabCheckScheduleGeneration.incrementAndGet();
        final long sleepMs = millisQueueSpacingMatchingSlider();
        new Thread(() -> {
            try {
                Thread.sleep(sleepMs);
                if (!enabled || generation != tabCheckScheduleGeneration.get()) {
                    return;
                }
                runOnClientThread(() -> {
                    if (enabled && generation == tabCheckScheduleGeneration.get()) {
                        checkPlayersAndRequeue();
                    }
                });
            } catch (InterruptedException ignored) {
            }
        }, "PlayerQueuer-After3sDelay").start();
    }
    
    // ==================== Death Tracking Methods ====================
    
    /**
     * Gets the list of players being tracked for death requeue
     */
    public static List<String> getDeathTrackPlayers() {
        return new ArrayList<>(deathTrackPlayers);
    }

    /**
     * True if {@code name} is in the saved death-track set (GUI / status display).
     * Ignores {@link #isEnabled()} — persists in {@code playerqueuer.json}.
     */
    public static boolean isDeathTrackListed(String name) {
        if (!isValidDeathTrackName(name)) {
            return false;
        }
        if (isExcludeTargetListed(name)) {
            removeDeathTrackMatches(name);
            return false;
        }
        return deathTrackPlayers.contains(normalizeDeathTrackName(name));
    }
    
    /**
     * True if death-track should affect {@link AutoQueue} (requires Player Queuer enabled).
     * When the queuer is off, persisted names must not trigger mid-game /play commands.
     */
    public static boolean isDeathTracked(String name) {
        if (!enabled) return false;
        return isDeathTrackListed(name);
    }
    
    /**
     * Toggles death tracking for a player
     * @param name Player name to toggle
     * @return true if now tracked, false if untracked
     */
    public static boolean toggleDeathTracking(String name) {
        if (!isValidDeathTrackName(name)) return false;
        if (isExcludeTargetListed(name)) {
            removeDeathTrackMatches(name);
            return false;
        }
        String norm = normalizeDeathTrackName(name);
        boolean nowTracked;
        if (deathTrackPlayers.contains(norm)) {
            deathTrackPlayers.remove(norm);
            nowTracked = false;
        } else {
            deathTrackPlayers.add(norm);
            nowTracked = true;
        }
        saveConfig();
        return nowTracked;
    }
    
    /**
     * Adds a player to death tracking
     */
    public static void addDeathTrack(String name) {
        if (!isValidDeathTrackName(name)) return;
        if (isExcludeTargetListed(name)) {
            removeDeathTrackMatches(name);
            return;
        }
        String norm = normalizeDeathTrackName(name);
        if (deathTrackPlayers.add(norm)) {
            saveConfig();
        }
    }
    
    /**
     * Removes a player from death tracking
     */
    public static void removeDeathTrack(String name) {
        if (!isValidDeathTrackName(name)) return;
        String norm = normalizeDeathTrackName(name);
        if (deathTrackPlayers.remove(norm)) {
            saveConfig();
        }
    }

    private static void removeDeathTrackMatches(String name) {
        if (name == null || name.trim().isEmpty() || deathTrackPlayers.isEmpty()) {
            return;
        }
        String norm = isValidDeathTrackName(name) ? normalizeDeathTrackName(name) : null;
        boolean changed = false;
        for (String stored : new ArrayList<>(deathTrackPlayers)) {
            if (!isValidDeathTrackName(stored) || (norm != null && stored.equals(norm))) {
                deathTrackPlayers.remove(stored);
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
        }
    }

    private static boolean isValidDeathTrackName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        return trimmed.matches("[A-Za-z0-9_]{1,16}");
    }

    private static String normalizeDeathTrackName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /** Clears persisted death-requeue names (see {@code deathTrackPlayers} in playerqueuer.json). */
    public static void clearDeathTrackSilent() {
        if (deathTrackPlayers.isEmpty()) {
            return;
        }
        deathTrackPlayers.clear();
        saveConfig();
    }

    /**
     * Corrects username using Mojang API to handle case sensitivity
     */
    private static String correctUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return username;
        }
        try {
            MojangUsernameResolver.MojangProfile profile = MojangUsernameResolver.resolveProfile(username);
            if (profile != null && profile.name != null && !profile.name.isEmpty()) {
                if (!profile.name.equals(username)) {
                    System.out.println("[PlayerQueuer] Corrected username: " + username + " -> " + profile.name);
                }
                return profile.name;
            }
        } catch (Throwable e) {
            System.err.println("[PlayerQueuer] Failed to correct username " + username + ": " + e.getMessage());
        }
        return username; // Return original if API fails
    }

    /**
     * Check if target players are in the TAB list based on current mode (any/all).
     */
    private static boolean areTargetsInTab() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) return false;
            boolean hasNamedTargets = !targetPlayers.isEmpty();
            boolean usableWlrRule = includeWlrRange && areWlrIncludePrerequisitesMet();
            boolean hasDynamicTargets = includeAllNicks || usableWlrRule;
            if (!hasNamedTargets && !hasDynamicTargets) return true;

            Set<String> foundPlayers = new HashSet<>();
            boolean foundNickRule = false;
            boolean foundWlrRule = false;
            synchronized (targetPlayers) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    String base = info.getGameProfile() != null ? info.getGameProfile().getName() : null;
                    String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
                    boolean isSelf = info.getGameProfile() != null && info.getGameProfile().getId() != null
                            && mc.thePlayer != null
                            && info.getGameProfile().getId().equals(mc.thePlayer.getUniqueID());
                    if (!isSelf && includeAllNicks && isKnownNickedTabEntry(base, visible)) {
                        foundNickRule = true;
                    }
                    if (!isSelf && usableWlrRule
                            && isTabEntryAtOrAboveWlrThreshold(base, visible, includeWlrThreshold)) {
                        foundWlrRule = true;
                    }
                    for (String target : targetPlayers) {
                        if (foundPlayers.contains(target)) {
                            continue;
                        }
                        if ((base != null && matchesSingleQueuerEntry(base, target))
                                || (visible != null && matchesSingleQueuerEntry(visible, target))) {
                            foundPlayers.add(target);
                        }
                    }
                }
            }

            if (requireAllInclude) {
                boolean namedSatisfied = !hasNamedTargets || foundPlayers.size() == targetPlayers.size();
                return namedSatisfied
                        && (!includeAllNicks || foundNickRule)
                        && (!usableWlrRule || foundWlrRule);
            } else {
                return !foundPlayers.isEmpty() || foundNickRule || foundWlrRule;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isExcludedConditionMet() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) return false;
            boolean hasNamedExcludes = !excludePlayers.isEmpty();
            boolean usableWlrRule = excludeWlrRange && areWlrIncludePrerequisitesMet();
            if (!hasNamedExcludes && !excludeAllNicks && !usableWlrRule) return false;
            Set<String> foundExcludes = new HashSet<>();
            boolean foundNickRule = false;
            boolean foundWlrRule = false;
            synchronized (excludePlayers) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    String base = info.getGameProfile() != null ? info.getGameProfile().getName() : null;
                    String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
                    boolean isSelf = info.getGameProfile() != null && info.getGameProfile().getId() != null
                            && mc.thePlayer != null
                            && info.getGameProfile().getId().equals(mc.thePlayer.getUniqueID());
                    if (!isSelf && excludeAllNicks && isKnownNickedTabEntry(base, visible)) {
                        foundNickRule = true;
                    }
                    if (!isSelf && usableWlrRule
                            && isTabEntryAtOrAboveWlrThreshold(base, visible, excludeWlrThreshold)) {
                        foundWlrRule = true;
                    }
                    for (String excluded : excludePlayers) {
                        if (foundExcludes.contains(excluded)) {
                            continue;
                        }
                        if ((base != null && matchesSingleQueuerEntry(base, excluded))
                                || (visible != null && matchesSingleQueuerEntry(visible, excluded))) {
                            foundExcludes.add(excluded);
                        }
                    }
                }
            }
            if (requireAllExclude) {
                boolean namedSatisfied = !hasNamedExcludes || foundExcludes.size() == excludePlayers.size();
                return namedSatisfied
                        && (!excludeAllNicks || foundNickRule)
                        && (!usableWlrRule || foundWlrRule);
            }
            return !foundExcludes.isEmpty() || foundNickRule || foundWlrRule;
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isKnownNickedTabEntry(String base, String visible) {
        String[] candidates = new String[] { visible, base };
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            String real = TabDuplicateNickResolver.getKnownRealName(candidate.trim());
            if (real != null && !real.trim().isEmpty() && !real.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTabEntryAtOrAboveWlrThreshold(String base, String visible, double threshold) {
        Set<String> candidates = new HashSet<>();
        if (base != null) candidates.add(base.trim());
        if (visible != null) candidates.add(visible.trim());
        for (String candidate : new ArrayList<>(candidates)) {
            String lookup = KillCounter.resolveTabLookupName(candidate);
            if (lookup != null) candidates.add(lookup.trim());
            String real = TabDuplicateNickResolver.getKnownRealName(candidate);
            if (real != null) candidates.add(real.trim());
        }
        for (String candidate : candidates) {
            if (candidate.isEmpty()) continue;
            double wl = KillEffectCommand.getCachedTabSortWl(candidate);
            if (!Double.isNaN(wl) && wl >= threshold) return true;
        }
        return false;
    }

    private static boolean hasIncludeTargetsConfigured() {
        synchronized (targetPlayers) {
            return !targetPlayers.isEmpty() || includeAllNicks
                    || (includeWlrRange && areWlrIncludePrerequisitesMet());
        }
    }

    private static boolean hasExcludeTargetsConfigured() {
        synchronized (excludePlayers) {
            return !excludePlayers.isEmpty() || excludeAllNicks
                    || (excludeWlrRange && areWlrIncludePrerequisitesMet());
        }
    }

    /**
     * Determine if current mode is teams by scanning scoreboard lines for the word "Team".
     */
    private static boolean isTeamGameMode() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) return false;
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) return false;
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) return false;
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String raw = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (raw == null) continue;
                String clean = raw.replaceAll("§[0-9A-FK-ORa-fk-or]", "").toLowerCase();
                if (clean.contains("team")) return true;
            }
            String objName = objective.getDisplayName();
            if (objName != null && objName.replaceAll("§[0-9A-FK-ORa-fk-or]", "").toLowerCase().contains("team")) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Colors the username in a chat message based on whether it's included or excluded
     */
    private static String colorUsernameInMessage(String formattedMessage, String username, boolean isJoin) {
        // Check if this is an included player or excluded player
        Set<String> currentTargets;
        Set<String> currentExcludes;
        synchronized (targetPlayers) {
            currentTargets = new HashSet<>(targetPlayers);
        }
        synchronized (excludePlayers) {
            currentExcludes = new HashSet<>(excludePlayers);
        }
        
        String playerLower = username.toLowerCase(Locale.ROOT);
        boolean isIncluded = false;
        boolean isExcluded = false;
        for (String stored : currentTargets) {
            if (matchesSingleQueuerEntry(username, stored)) {
                isIncluded = true;
                break;
            }
        }
        if (!isIncluded) {
            for (String stored : currentExcludes) {
                if (matchesSingleQueuerEntry(username, stored)) {
                    isExcluded = true;
                    break;
                }
            }
        }
        
        // Determine color: light blue for included, dark red for excluded
        String colorCode;
        if (isIncluded) {
            colorCode = EnumChatFormatting.AQUA.toString(); // §b
        } else if (isExcluded) {
            colorCode = EnumChatFormatting.DARK_RED.toString(); // §4
        } else {
            // This shouldn't happen since we only process tracked players
            colorCode = EnumChatFormatting.WHITE.toString();
        }
        
        String result = formattedMessage;
        String replacement = Matcher.quoteReplacement(colorCode + username + EnumChatFormatting.RESET);
        String usernamePattern = Pattern.quote(username);

        if (isJoin) {
            result = result.replaceFirst(
                    "^(?:§[0-9A-FK-ORa-fk-or])*\\[[^\\]]+\\]\\s+(?:§[0-9A-FK-ORa-fk-or])*" + usernamePattern,
                    replacement
            );
            if (!result.equals(formattedMessage)) {
                return result;
            }
        }

        return result.replaceFirst("(?:§[0-9A-FK-ORa-fk-or])*" + usernamePattern, replacement);
    }

    /**
     * Detects when tracked players join or leave the lobby and plays sounds.
     * Returns true if the message should be canceled (to replace with colored version).
     * Join format: "PlayerName has joined (X/Y)!"
     * Leave format: "PlayerName has quit!"
     */
    private static boolean detectPlayerJoinLeave(String message, S02PacketChat chatPacket) {
        // Create copies of both targetPlayers and excludePlayers to avoid concurrent modification
        Set<String> currentTargets;
        Set<String> currentExcludes;
        synchronized (targetPlayers) {
            currentTargets = new HashSet<>(targetPlayers);
        }
        synchronized (excludePlayers) {
            currentExcludes = new HashSet<>(excludePlayers);
        }
        
        // If both lists are empty, nothing to track
        if (currentTargets.isEmpty() && currentExcludes.isEmpty()) return false;
        
        try {
            // Remove color codes for clean matching
            String cleanMessage = message.replaceAll("§[0-9A-FK-ORa-fk-or]", "").trim();
            
            // Check for join pattern: "PlayerName has joined (X/Y)!"
            if (cleanMessage.contains(" has joined (") && cleanMessage.contains(")!")) {
                int joinIndex = cleanMessage.indexOf(" has joined (");
                if (joinIndex > 0) {
                    String playerName = cleanMessage.substring(0, joinIndex).trim();
                    
                     // Check if this player is in our target list OR exclude list (denick-aware)
                     boolean tracked = false;
                     for (String stored : currentTargets) {
                         if (matchesSingleQueuerEntry(playerName, stored)) {
                             tracked = true;
                             break;
                         }
                     }
                     if (!tracked) {
                         for (String stored : currentExcludes) {
                             if (matchesSingleQueuerEntry(playerName, stored)) {
                                 tracked = true;
                                 break;
                             }
                         }
                     }
                     if (tracked) {
                         // Play join sound: /playsound mob.guardian.hit 0.7 1.6
                         try {
                             Minecraft.getMinecraft().thePlayer.playSound("mob.guardian.hit", 0.7f, 1.6f);
                         } catch (Exception e) {
                             System.err.println("[PlayerQueuer] Error playing join sound: " + e.getMessage());
                         }
                         
                         // Display message with colored username
                         String formattedMessage = chatPacket.getChatComponent().getFormattedText();
                         String coloredMessage = colorUsernameInMessage(formattedMessage, playerName, true);
                         ChatComponentText chatComponent = new ChatComponentText(coloredMessage);
                         Minecraft.getMinecraft().thePlayer.addChatMessage(chatComponent);
                         
                         System.out.println("[PlayerQueuer] Tracked player joined: " + playerName);
                         return true; // Cancel original message
                     }
                }
            }
            // Check for leave pattern: "PlayerName has quit!"
            else if (cleanMessage.contains(" has quit!")) {
                int quitIndex = cleanMessage.indexOf(" has quit!");
                if (quitIndex > 0) {
                    String playerName = cleanMessage.substring(0, quitIndex).trim();
                    
                     boolean tracked = false;
                     for (String stored : currentTargets) {
                         if (matchesSingleQueuerEntry(playerName, stored)) {
                             tracked = true;
                             break;
                         }
                     }
                     if (!tracked) {
                         for (String stored : currentExcludes) {
                             if (matchesSingleQueuerEntry(playerName, stored)) {
                                 tracked = true;
                                 break;
                             }
                         }
                     }
                     if (tracked) {
                         // Play leave sound: /playsound mob.guardian.hit 0.7 0.6
                         try {
                             Minecraft.getMinecraft().thePlayer.playSound("mob.guardian.hit", 0.7f, 0.6f);
                         } catch (Exception e) {
                             System.err.println("[PlayerQueuer] Error playing leave sound: " + e.getMessage());
                         }
                         
                         // Display message with colored username
                         String formattedMessage = chatPacket.getChatComponent().getFormattedText();
                         String coloredMessage = colorUsernameInMessage(formattedMessage, playerName, false);
                         ChatComponentText chatComponent = new ChatComponentText(coloredMessage);
                         Minecraft.getMinecraft().thePlayer.addChatMessage(chatComponent);
                         
                         System.out.println("[PlayerQueuer] Tracked player left: " + playerName);
                         return true; // Cancel original message
                     }
                }
            }
        } catch (Exception e) {
            System.err.println("[PlayerQueuer] detectPlayerJoinLeave error: " + e.getMessage());
        }
        return false; // Don't cancel message
    }

    /**
     * Subscribe to chat to detect the 3-second start line (plus configured delay), then TAB check / requeue.
     * Also detects when tracked players join/leave the lobby.
     */
    @SubscribeEvent
    public void onChat(Receive event) {
        if (!enabled) return;
        if (!(event.getPacket() instanceof S02PacketChat)) return;
        try {
            S02PacketChat chat = (S02PacketChat) event.getPacket();
            String message = chat.getChatComponent().getUnformattedText();
            if (message == null || message.isEmpty()) return;

            onLimboPresenceDuringPendingRequeue(message);
            onRequeuePlayAccepted(message);
            
            // Get chat type: 0 = chat, 1 = system message, 2 = above hotbar
            byte chatType = chat.getType();
            
            // Detect join/leave messages (only for system messages)
            if (chatType == 1 || chatType == 0) { // Allow both system and chat messages, we'll filter by content
                boolean shouldCancel = detectPlayerJoinLeave(message, chat);
                if (shouldCancel) {
                    event.setCancelled(true); // Cancel the original message
                    return;
                }
            }
            
            // Notify user when game starts at 10 seconds
            if (message.contains("The game starts in 10 seconds!")) {
                sendPlayerQueuerStatusMessage();
            }
            
            if (isGameStartsInThreeSecondsMessage(message)) {
                scheduleTabCheckAfter3SecondLine();
            }

            // Hypixel can fail /play right after /limbo ("Something went wrong trying to send you to that server! (mini...)");
            // Retry the same /play after a short delay; repeat on each failure while this requeue session is active.
            onPossibleHypixelTransferFailure(message);
        } catch (Exception e) {
            System.err.println("[PlayerQueuer] onChat error: " + e.getMessage());
        }
    }
    
    /**
     * Performs the player check and requeue logic (extracted from onChat for delayed execution)
     */
    private static void checkPlayersAndRequeue() {
        try {
            // Check if we're in a Blitz SG game before requeuing
            if (!AutoQueue.isBlitzGame()) {
                System.out.println("[PlayerQueuer] Not in a Blitz SG game, skipping requeue");
                return;
            }

            boolean hasIncludeTargets = hasIncludeTargetsConfigured();
            boolean hasExcludeTargets = hasExcludeTargetsConfigured();
            
            // Exclusion has priority
            if (isExcludedConditionMet()) {
                boolean teams = isTeamGameMode();
                String exMode = requireAllExclude ? "ALL excluded present" : "Some excluded present";
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + exMode + ". Requeuing " + (teams ? "teams" : "solo") + "...");
                sendRequeueCommands(teams);
                return;
            }

            if (!hasIncludeTargets) {
                if (hasExcludeTargets) {
                    String mode = requireAllExclude ? "Not all excluded targets found" : "Excluded target not found";
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + mode + " in TAB. Staying in this game.");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "No Player Queuer targets configured. Staying in this game.");
                }
                return;
            }

            if (!areTargetsInTab()) {
                boolean teams = isTeamGameMode();
                String mode = requireAllInclude ? "all included targets" : "any included target";
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + mode + " not found in TAB. Requeuing " + (teams ? "teams" : "solo") + "...");
                sendRequeueCommands(teams);
            } else {
                String mode = requireAllInclude ? "All included targets" : "Target";
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + mode + " found in TAB. Staying in this game.");
                
                // Play success sound when target players are found
                try {
                    Minecraft.getMinecraft().thePlayer.playSound("random.levelup", 1.0f, 0.9f);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("[PlayerQueuer] checkPlayersAndRequeue error: " + e.getMessage());
        }
    }
    
    /**
     * Sends requeue commands: {@code /limbo}, then {@code /play} after the dodged game's countdown has elapsed.
     */
    private static void sendRequeueCommands(boolean teams) {
        try {
            if (Minecraft.getMinecraft().thePlayer == null) {
                return;
            }

            final String playCmd = teams ? "/play blitz_teams_normal" : "/play blitz_solo_normal";
            final int session = requeueTransferSession.incrementAndGet();
            synchronized (requeueTransferLock) {
                pendingRequeuePlayCommand = playCmd;
                lastRequeueMainSentAtMs = 0L;
                lastRequeuePlaySentAtMs = 0L;
                transferFailRetryPending = false;
                transferFailRetryPendingSession = -1;
                requeuePlayNotBeforeAtMs = 0L;
                lastRequeueLimboPresenceAtMs = 0L;
                lastRequeuePlayAcceptedAtMs = 0L;
                requeueLimboBounceRetries = 0;
            }

            int countdownSec = readLobbyCountdownSeconds();
            if (countdownSec < 0) {
                countdownSec = 3;
            }
            beginMainThenPlaySequence(session, playCmd, countdownSec);

        } catch (Exception e) {
            System.err.println("[PlayerQueuer] sendRequeueCommands error: " + e.getMessage());
        }
    }

    private static void beginMainThenPlaySequence(int session, String playCmd, int countdownSecondsAtMain) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || !enabled || session != requeueTransferSession.get()) {
            return;
        }
        final long pauseAfterMainMs = computePostMainWaitMs(countdownSecondsAtMain);
        synchronized (requeueTransferLock) {
            if (session != requeueTransferSession.get()) return;
            lastRequeueMainSentAtMs = System.currentTimeMillis();
            requeuePlayNotBeforeAtMs = lastRequeueMainSentAtMs + pauseAfterMainMs;
        }
        // Publish the pending session first: /limbo can synchronously start a connection change.
        sendAutomatedChat(mc, "/limbo");

        if (pauseAfterMainMs > millisQueueSpacingMatchingSlider()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Waiting "
                    + (pauseAfterMainMs / 1000L) + "s in limbo before requeueing...");
        }

        new Thread(() -> {
            try {
                Thread.sleep(pauseAfterMainMs);
                dispatchPendingPlay(session, playCmd, "timer", false, 0);
            } catch (InterruptedException ignored) {
            }
        }, "PlayerQueuer-RequeueDelay").start();
    }

    /**
     * Wait at least the slider delay, and if the dodged lobby was still counting down, until countdown + buffer
     * (same idea as Map Queuer's /limbo pause).
     */
    private static long computePostMainWaitMs(int countdownSecondsAtMain) {
        long sliderMs = millisQueueSpacingMatchingSlider();
        if (countdownSecondsAtMain <= 0) {
            return Math.max(sliderMs, POST_MAIN_START_BUFFER_SEC * 1000L);
        }
        if (countdownSecondsAtMain < 10) {
            long untilStartMs = (countdownSecondsAtMain + POST_MAIN_START_BUFFER_SEC) * 1000L;
            return Math.max(sliderMs, untilStartMs);
        }
        return sliderMs;
    }

    /** Sidebar countdown seconds, or -1 if not shown. */
    private static int readLobbyCountdownSeconds() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return -1;
            }
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return -1;
            }
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (line == null) {
                    continue;
                }
                String timeStr = null;
                if (line.contains("Starting in")) {
                    timeStr = line.substring(line.indexOf("Starting in") + 12).trim();
                } else if (line.contains("starts in")) {
                    timeStr = line.substring(line.indexOf("starts in") + 9).trim();
                } else if (line.contains("The game starts in")) {
                    timeStr = line.substring(line.indexOf("The game starts in") + 18).trim();
                } else if (line.contains("Game starts in")) {
                    timeStr = line.substring(line.indexOf("Game starts in") + 14).trim();
                } else if (line.contains(" to") && line.contains(":")) {
                    timeStr = line.substring(0, line.indexOf(" to")).trim();
                }
                if (timeStr == null) {
                    continue;
                }
                timeStr = EnumChatFormatting.getTextWithoutFormattingCodes(timeStr);
                if (timeStr == null) {
                    continue;
                }
                timeStr = timeStr.replaceAll("[^\\p{Print}]", "").trim();
                StringBuilder timeOnly = new StringBuilder();
                for (int i = 0; i < timeStr.length(); i++) {
                    char c = timeStr.charAt(i);
                    if (Character.isDigit(c) || c == ':') {
                        timeOnly.append(c);
                    } else {
                        break;
                    }
                }
                String parsed = timeOnly.toString();
                if (parsed.contains(":")) {
                    String[] parts = parsed.split(":");
                    if (parts.length == 2) {
                        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                    }
                } else if (!parsed.isEmpty()) {
                    return Integer.parseInt(parsed);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void invalidatePendingRequeueTransfers(String reason) {
        boolean hadPending;
        synchronized (requeueTransferLock) {
            hadPending = pendingRequeuePlayCommand != null;
        }
        requeueTransferSession.incrementAndGet();
        synchronized (requeueTransferLock) {
            if (hadPending) {
                System.out.println("[PlayerQueuer] Cleared pending /play: " + reason);
            }
            pendingRequeuePlayCommand = null;
            lastRequeueMainSentAtMs = 0L;
            lastRequeuePlaySentAtMs = 0L;
            requeuePlayNotBeforeAtMs = 0L;
            lastRequeueLimboPresenceAtMs = 0L;
            lastRequeuePlayAcceptedAtMs = 0L;
            requeueLimboBounceRetries = 0;
            transferFailRetryPending = false;
            transferFailRetryPendingSession = -1;
        }
    }

    private static boolean sendRequeuePlayIfSessionValid(int session, String playCmd) {
        return sendRequeuePlayIfSessionValid(session, playCmd, false);
    }

    private static boolean sendRequeuePlayIfSessionValid(int session, String playCmd, boolean retry) {
        if (!enabled || session != requeueTransferSession.get()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (!mc.isCallingFromMinecraftThread()) {
            runOnClientThread(() -> sendRequeuePlayIfSessionValid(session, playCmd, retry));
            return true;
        }
        synchronized (requeueTransferLock) {
            if (session != requeueTransferSession.get() || pendingRequeuePlayCommand == null
                    || (!retry && lastRequeuePlaySentAtMs > 0L)) {
                return false;
            }
            lastRequeuePlaySentAtMs = System.currentTimeMillis();
        }
        sendAutomatedChat(mc, playCmd);
        return true;
    }

    /** Makes the delayed command resilient to the short world/player gap during Limbo transfers. */
    private static void dispatchPendingPlay(final int session, final String playCmd, final String reason,
                                           final boolean retry, final int attempt) {
        if (!enabled) {
            if (attempt == 0) System.out.println("[PlayerQueuer] Pending /play cancelled: Player Queuer is disabled");
            return;
        }
        if (session != requeueTransferSession.get()) {
            if (attempt == 0) System.out.println("[PlayerQueuer] Pending /play cancelled: transfer session changed");
            return;
        }
        runOnClientThread(() -> {
            if (!enabled || session != requeueTransferSession.get()) {
                if (attempt == 0) System.out.println("[PlayerQueuer] Pending /play cancelled before client dispatch");
                return;
            }
            final long notBefore;
            synchronized (requeueTransferLock) {
                if (pendingRequeuePlayCommand == null) {
                    System.out.println("[PlayerQueuer] Pending /play cancelled: transfer command was cleared");
                    return;
                }
                if (!retry && lastRequeuePlaySentAtMs > 0L) {
                    if (attempt == 0) System.out.println("[PlayerQueuer] Pending /play already sent for this queue");
                    return;
                }
                notBefore = requeuePlayNotBeforeAtMs;
            }
            Minecraft mc = Minecraft.getMinecraft();
            long waitMs = Math.max(0L, notBefore - System.currentTimeMillis());
            if (mc == null || mc.thePlayer == null || waitMs > 0L) {
                if (attempt >= PLAY_DISPATCH_READY_RETRIES) {
                    System.out.println("[PlayerQueuer] Could not send pending /play: client/player not ready after Limbo");
                    return;
                }
                retryPendingPlayDispatch(session, playCmd, reason, retry, attempt + 1,
                        Math.max(250L, Math.min(1000L, waitMs)));
                return;
            }
            if (sendRequeuePlayIfSessionValid(session, playCmd, retry)) {
                System.out.println("[PlayerQueuer] Sent " + playCmd + " after Limbo (" + reason + "); waiting for Hypixel transfer");
            } else {
                System.out.println("[PlayerQueuer] Pending /play dispatch was rejected (" + reason + ")");
            }
        });
    }

    private static void retryPendingPlayDispatch(int session, String playCmd, String reason,
                                                boolean retry, int nextAttempt, long delayMs) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                dispatchPendingPlay(session, playCmd, reason, retry, nextAttempt);
            } catch (InterruptedException ignored) {
            }
        }, "PlayerQueuer-PlayReadyRetry");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Player Requeuer (the death-track icon) has its own immediate queue path. It intentionally does not use the
     * Player Queuer pre-start slider or the AutoQueue solo/teams death-delay sliders. Registering the command as a
     * transfer session also lets {@link #onPossibleHypixelTransferFailure(String)} retry it when Hypixel returns
     * "Something went wrong trying to send you to that server".
     */
    public static boolean sendDeathTrackedPlayerRequeueNow(boolean teams) {
        if (!enabled) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }

        final String playCmd = teams ? "/play blitz_teams_normal" : "/play blitz_solo_normal";
        final int session = requeueTransferSession.incrementAndGet();
        synchronized (requeueTransferLock) {
            pendingRequeuePlayCommand = playCmd;
            lastRequeueMainSentAtMs = System.currentTimeMillis();
            lastRequeuePlaySentAtMs = 0L;
            requeuePlayNotBeforeAtMs = 0L;
            lastRequeueLimboPresenceAtMs = 0L;
            lastRequeuePlayAcceptedAtMs = 0L;
            requeueLimboBounceRetries = 0;
            transferFailRetryPending = false;
            transferFailRetryPendingSession = -1;
        }
        runOnClientThread(() -> sendRequeuePlayIfSessionValid(session, playCmd, true));
        return true;
    }

    private static boolean isHypixelServerTransferFailureMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String clean = message.replaceAll("§[0-9A-FK-ORa-fk-or]", "").trim();
        return clean.contains("Something went wrong trying to send you to that server");
    }

    /**
     * When Hypixel reports a transfer failure during /limbo or /play, retry after
     * {@link #TRANSFER_FAIL_RETRY_DELAY_MS}. Debounced so duplicate chat lines do not stack retries.
     */
    private static void onPossibleHypixelTransferFailure(String unformatted) {
        if (!enabled || !isHypixelServerTransferFailureMessage(unformatted)) {
            return;
        }
        final int sessionAtFailure = requeueTransferSession.get();
        final String playCmd;
        final long lastMainSent;
        final long lastPlaySent;
        synchronized (requeueTransferLock) {
            playCmd = pendingRequeuePlayCommand;
            lastMainSent = lastRequeueMainSentAtMs;
            lastPlaySent = lastRequeuePlaySentAtMs;
        }
        if (playCmd == null || playCmd.isEmpty()) {
            return;
        }

        final long now = System.currentTimeMillis();
        final boolean duringMain = lastPlaySent == 0L && lastMainSent > 0L;
        final boolean duringPlay = lastPlaySent > 0L;
        if (!duringMain && !duringPlay) {
            return;
        }
        long anchorMs = duringPlay ? lastPlaySent : lastMainSent;
        long age = now - anchorMs;
        if (age < 0L || age > REQUEUE_FAILURE_CORRELATION_WINDOW_MS) {
            return;
        }

        synchronized (requeueTransferLock) {
            if (transferFailRetryPending && transferFailRetryPendingSession == sessionAtFailure) {
                return;
            }
            transferFailRetryPending = true;
            transferFailRetryPendingSession = sessionAtFailure;
        }

        final boolean retryMain = duringMain;
        System.out.println("[PlayerQueuer] Transfer failed during "
                + (retryMain ? "/limbo" : "/play") + "; retrying in " + (TRANSFER_FAIL_RETRY_DELAY_MS / 1000) + "s");

        new Thread(() -> {
            try {
                Thread.sleep(TRANSFER_FAIL_RETRY_DELAY_MS);
                if (!enabled || sessionAtFailure != requeueTransferSession.get()) {
                    return;
                }
                if (retryMain) {
                    runOnClientThread(() -> {
                        if (!enabled || sessionAtFailure != requeueTransferSession.get()) {
                            return;
                        }
                        int countdownSec = readLobbyCountdownSeconds();
                        if (countdownSec < 0) {
                            countdownSec = 3;
                        }
                        beginMainThenPlaySequence(sessionAtFailure, playCmd, countdownSec);
                    });
                } else {
                    runOnClientThread(() -> sendRequeuePlayIfSessionValid(sessionAtFailure, playCmd, true));
                }
            } catch (InterruptedException ignored) {
            } finally {
                synchronized (requeueTransferLock) {
                    if (transferFailRetryPendingSession == sessionAtFailure) {
                        transferFailRetryPending = false;
                        transferFailRetryPendingSession = -1;
                    }
                }
            }
        }, "PlayerQueuer-TransferFailRetry").start();
    }

    private static void onLimboPresenceDuringPendingRequeue(String message) {
        if (message == null) return;
        String clean = message.replaceAll("(?i)§[0-9a-fk-or]", "").trim();
        boolean inLimbo = clean.equalsIgnoreCase("You were spawned in Limbo.")
                || clean.matches("(?i)^\\{\\s*\"server\"\\s*:\\s*\"limbo\"\\s*}$");
        if (!inLimbo || !enabled) return;

        final int session = requeueTransferSession.get();
        final String playCmd;
        final long notBefore;
        final boolean bouncedBackAfterPlay;
        final long now = System.currentTimeMillis();
        synchronized (requeueTransferLock) {
            playCmd = pendingRequeuePlayCommand;
            notBefore = requeuePlayNotBeforeAtMs;
            long previousPresence = lastRequeueLimboPresenceAtMs;
            lastRequeueLimboPresenceAtMs = now;
            if (playCmd == null || lastRequeueMainSentAtMs <= 0L
                    || now - lastRequeueMainSentAtMs > REQUEUE_SESSION_MAX_AGE_MS
                    || (transferFailRetryPending && transferFailRetryPendingSession == session)) return;
            bouncedBackAfterPlay = lastRequeuePlaySentAtMs > 0L
                    && (lastRequeuePlayAcceptedAtMs >= lastRequeuePlaySentAtMs
                            || now - lastRequeuePlaySentAtMs > 10_000L)
                    && (previousPresence == 0L || now - previousPresence > 10_000L);
            if (bouncedBackAfterPlay) {
                if (requeueLimboBounceRetries >= MAX_LIMBO_BOUNCE_RETRIES) return;
                requeueLimboBounceRetries++;
                transferFailRetryPending = true;
                transferFailRetryPendingSession = session;
            } else if (lastRequeuePlaySentAtMs > 0L || now < notBefore) {
                return;
            }
        }

        if (bouncedBackAfterPlay) {
            new Thread(() -> {
                try {
                    Thread.sleep(LIMBO_BOUNCE_RETRY_DELAY_MS);
                    runOnClientThread(() -> {
                        try {
                            if (!enabled || session != requeueTransferSession.get()) return;
                            Minecraft mc = Minecraft.getMinecraft();
                            if (mc == null) return;
                            dispatchPendingPlay(session, playCmd, "returned to Limbo", true, 0);
                        } finally {
                            clearTransferFailRetryPending(session);
                        }
                    });
                } catch (InterruptedException ignored) {
                    clearTransferFailRetryPending(session);
                }
            }, "PlayerQueuer-LimboBounceRetry").start();
            return;
        }

        // The scheduled timer can race a Hypixel limbo handoff. Once limbo confirms the new
        // world is ready, retry the one pending /play on the client thread if it has not fired.
        dispatchPendingPlay(session, playCmd, "Limbo confirmation", false, 0);
    }

    private static void onRequeuePlayAccepted(String message) {
        if (message == null || !message.trim().matches("(?i)^Sending you to mini[A-Z0-9]+[!.]?$")) return;
        synchronized (requeueTransferLock) {
            if (pendingRequeuePlayCommand == null || lastRequeuePlaySentAtMs <= 0L
                    || System.currentTimeMillis() - lastRequeueMainSentAtMs > REQUEUE_SESSION_MAX_AGE_MS) return;
            lastRequeuePlayAcceptedAtMs = System.currentTimeMillis();
        }
    }

    private static void clearTransferFailRetryPending(int session) {
        synchronized (requeueTransferLock) {
            if (transferFailRetryPendingSession == session) {
                transferFailRetryPending = false;
                transferFailRetryPendingSession = -1;
            }
        }
    }
    
    /**
     * Sends a status message showing Player Queuer is enabled with player info and a clickable disable button
     */
    private static void sendPlayerQueuerStatusMessage() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) return;
            
            // [gomod] prefix + green status + clickable disable (same pattern as MapQueuer)
            ChatComponentText prefixComponent = new ChatComponentText(Main.CHAT_PREFIX);
            ChatComponentText statusComponent = new ChatComponentText("Player Queuer is enabled!");
            statusComponent.getChatStyle().setColor(EnumChatFormatting.GREEN);

            ChatComponentText disableButton = new ChatComponentText(
                EnumChatFormatting.DARK_RED + " [" +
                EnumChatFormatting.DARK_RED + "" + EnumChatFormatting.BOLD + "Click to disable" +
                EnumChatFormatting.RESET + EnumChatFormatting.DARK_RED + "]"
            );
            disableButton.getChatStyle().setChatClickEvent(new net.minecraft.event.ClickEvent(
                net.minecraft.event.ClickEvent.Action.RUN_COMMAND,
                "/playerqueuer off"
            ));
            disableButton.getChatStyle().setChatHoverEvent(new net.minecraft.event.HoverEvent(
                net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.RED + "Disable Player Queuer")
            ));
            statusComponent.appendSibling(disableButton);
            
            // Build player lists info
            StringBuilder playerInfo = new StringBuilder();
            
            // Add target players info
            if (!targetPlayers.isEmpty()) {
                playerInfo.append(EnumChatFormatting.YELLOW).append("\n  Targeting: ");
                playerInfo.append(EnumChatFormatting.GREEN);
                int count = 0;
                for (String player : targetPlayers) {
                    if (count > 0) playerInfo.append(", ");
                    playerInfo.append(pqMojangCaseForChat(player));
                    // Add death track indicator
                    if (deathTrackPlayers.contains(player)) {
                        playerInfo.append(EnumChatFormatting.DARK_RED).append("[D]").append(EnumChatFormatting.GREEN);
                    }
                    count++;
                    if (count >= 5) {
                        playerInfo.append(" and ").append(targetPlayers.size() - 5).append(" more...");
                        break;
                    }
                }
            }
            
            // Add excluded players info
            if (!excludePlayers.isEmpty()) {
                playerInfo.append(EnumChatFormatting.YELLOW).append("\n  Dodging: ");
                playerInfo.append(EnumChatFormatting.DARK_RED);
                int count = 0;
                for (String player : excludePlayers) {
                    if (count > 0) playerInfo.append(", ");
                    playerInfo.append(pqMojangCaseForChat(player));
                    // Add death track indicator
                    if (deathTrackPlayers.contains(player)) {
                        playerInfo.append(EnumChatFormatting.GOLD).append("[D]").append(EnumChatFormatting.DARK_RED);
                    }
                    count++;
                    if (count >= 5) {
                        playerInfo.append(" and ").append(excludePlayers.size() - 5).append(" more...");
                        break;
                    }
                }
            }
            
            if (playerInfo.length() > 0) {
                statusComponent.appendSibling(new ChatComponentText(playerInfo.toString()));
            }

            prefixComponent.appendSibling(statusComponent);
            mc.thePlayer.addChatMessage(prefixComponent);
            
        } catch (Exception e) {
            System.err.println("[PlayerQueuer] sendPlayerQueuerStatusMessage error: " + e.getMessage());
        }
    }
}

