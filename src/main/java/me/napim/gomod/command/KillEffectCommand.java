package me.napim.gomod.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import net.minecraft.client.network.NetworkPlayerInfo;
import me.napim.gomod.Main;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.ApiKeyManager;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.FeatureProfiler;
import me.napim.gomod.features.HypixelApiRequestDebug;
import me.napim.gomod.features.HypixelTabStatsRankCache;
import me.napim.gomod.features.TabDuplicateNickResolver;
import me.napim.gomod.features.TabStatsBlitzMetrics;
import me.napim.gomod.features.TabStatsTrafficDebug;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.weavemc.loader.api.event.PacketEvent.Receive;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.network.play.server.S01PacketJoinGame;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.Event;
import net.weavemc.loader.api.event.TickEvent;

public class KillEffectCommand extends Command {
    private static final String DEFAULT_BLITZ_KILL_EFFECT = "SPEED";
    private static final java.util.concurrent.ConcurrentHashMap<String, String> playerKillEffects = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Lowercase stats lookup name → kill-effect API value was learned from the PLAYERS_JOIN pregame scan and may be stale.
     * The post-cages refresh removes this marker when it confirms the value after cages open.
     */
    private static final java.util.Set<String> uncertainJoinTimeKillEffects =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /** Minimum delay between Hypixel {@code /player} request starts across bulk and late-join scans. */
    private static final int TABSTATS_SCAN_PACE_MS = 1000;
    private static final long AUTO_TABSTATS_FAILURE_NOTICE_COOLDOWN_MS = 5 * 60_000L;
    /**
     * Bulk scans and late-player scans use different worker threads. A plain {@code Thread.sleep(1000)} in every
     * worker lets all players that joined together wake up and hit Hypixel together, which defeats the intended
     * pacing and creates a burst of large JSON parses/cache updates. Hold this worker-only lock while waiting so
     * request starts are globally spaced even when many TAB rows arrive in the same second.
     */
    private static final Object TABSTATS_REQUEST_PACE_LOCK = new Object();
    private static long lastTabstatsRequestStartMs;
    private static volatile long lastAutomaticTabstatsFailureNoticeMs;
    /** Auto scans are deliberately single-flight and are invalidated when Hypixel replaces the client world. */
    private static final java.util.concurrent.atomic.AtomicInteger TABSTATS_SCAN_GENERATION =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger TABSTATS_SCAN_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE_AUTO_SCAN =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int HYPIXEL_TABSTATS_MAX_429_RETRIES = 10;
    private static final java.util.concurrent.ConcurrentHashMap<String, String> playerStats = new java.util.concurrent.ConcurrentHashMap<>();
    /** Lowercase name → parsed Blitz metrics (TAB suffix is formatted from this + KillCounter toggles). */
    private static final java.util.concurrent.ConcurrentHashMap<String, TabStatsBlitzMetrics> playerBlitzMetrics =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> statsObservedAtMs = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Lowercase tab profile name → {@code [0]=W/L ratio, [1]=total Blitz wins, [2]=overall Blitz kills} for TAB list sorting.
     * Filled alongside {@link #playerStats} from the same Hypixel HungerGames payload.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, double[]> tabSortMetricsByLowerName = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean enabled = true; // Add toggle state
    private static boolean debugEnabled = false;
    /** Per-player chat + console spam during {@code /tabstats all} only; toggled by {@code /tabstatsfulldebug}. */
    private static volatile boolean tabstatsFullDebugChat = false;

    public static boolean isTabstatsFullDebugChat() {
        return tabstatsFullDebugChat;
    }

    /** @return new enabled state */
    public static boolean toggleTabstatsFullDebugChat() {
        tabstatsFullDebugChat = !tabstatsFullDebugChat;
        return tabstatsFullDebugChat;
    }

    private static boolean shouldEmitTabstatsBulkDetail() {
        return me.napim.gomod.features.KillCounter.isShowKeAllChat() && tabstatsFullDebugChat;
    }

    private static boolean noteAutomaticTabstatsFailure(Throwable failure) {
        boolean transientFailure = me.napim.gomod.features.HypixelApiHelper.isTransientUpstreamFailure(failure);
        boolean freshnessDeferred = isAutomaticTabstatsFreshnessDeferred(failure);
        if (!transientFailure && !freshnessDeferred) return false;
        boolean notify = false;
        if (transientFailure) {
            long now = System.currentTimeMillis();
            synchronized (TABSTATS_REQUEST_PACE_LOCK) {
                notify = now - lastAutomaticTabstatsFailureNoticeMs >= AUTO_TABSTATS_FAILURE_NOTICE_COOLDOWN_MS;
                if (notify) {
                    lastAutomaticTabstatsFailureNoticeMs = now;
                }
            }
        }
        if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
            HypixelApiRequestDebug.record("tab.automatic_refresh_delayed",
                    "reason=" + (freshnessDeferred ? "freshness-deferred" : "temporary-upstream-failure")
                            + " chatNotice=" + notify);
        }
        if (notify && !freshnessDeferred) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Hypixel API temporarily timed out; some automatic TAB stats may be unavailable.");
        }
        return true;
    }

    private static boolean isAutomaticTabstatsFreshnessDeferred(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("hypixel api key is rate limited")
                    || lower.contains("fresh player data is still cooling down")
                    || lower.contains("already looked up")
                    || lower.contains("too recently")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayersJoinPregameAutoTrigger(String trigger) {
        return trigger != null && trigger.toLowerCase(Locale.ROOT).contains("blitz pregame");
    }

    private static boolean isCagesOpenAutoTrigger(String trigger) {
        return trigger != null && trigger.toLowerCase(Locale.ROOT).contains("cages-open");
    }

    private static boolean hasCachedStatsForKeys(String primaryKey, String aliasKey) {
        long now = System.currentTimeMillis();
        return (primaryKey != null && playerBlitzMetrics.containsKey(primaryKey.toLowerCase(Locale.ROOT))
                    && now - statsObservedAtMs.getOrDefault(primaryKey.toLowerCase(Locale.ROOT), 0L)
                        <= me.napim.gomod.features.HypixelPlayerDataCache.FRESH_FOR_MS)
                || (aliasKey != null && playerBlitzMetrics.containsKey(aliasKey.toLowerCase(Locale.ROOT))
                    && now - statsObservedAtMs.getOrDefault(aliasKey.toLowerCase(Locale.ROOT), 0L)
                        <= me.napim.gomod.features.HypixelPlayerDataCache.FRESH_FOR_MS);
    }

    private static boolean hasCachedEffectForKeys(String primaryKey, String aliasKey) {
        return (primaryKey != null && playerKillEffects.containsKey(primaryKey.toLowerCase(Locale.ROOT)))
                || (aliasKey != null && playerKillEffects.containsKey(aliasKey.toLowerCase(Locale.ROOT)));
    }

    private static boolean isKillEffectDisplayEnabled() {
        return me.napim.gomod.features.KillCounter.getKillEffectTabDisplayMode()
                != me.napim.gomod.features.KillCounter.KillEffectTabDisplayMode.OFF;
    }

    /**
     * Automatic scans should not re-fetch a row whose stats are already cached. The one intentional exception is
     * the cages-open scan while kill-effect display is enabled: the API's afterkill value may have changed during
     * pregame, so that scan is allowed to refresh it once the cages are open.
     */
    private static boolean shouldSkipAutomaticCachedBulkRow(String trigger, String statsKey, String tabKey) {
        if (isManualTabstatsTrigger(trigger) || !hasCachedStatsForKeys(statsKey, tabKey)) {
            return false;
        }
        return !(isCagesOpenAutoTrigger(trigger) && isKillEffectDisplayEnabled());
    }

    private static boolean isPregameNow() {
        try {
            return GameContext.isBlitzPregameLobby(Minecraft.getMinecraft());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPendingCagesOpenKillEffectRefresh() {
        return !pendingCagesOpenKillEffectRefresh.isEmpty();
    }

    public static boolean isPendingCagesOpenKillEffectRefresh(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return false;
        }
        String rawKey = playerName.trim().toLowerCase(Locale.ROOT);
        String lookupKey = tabstatsCooldownKey(playerName);
        return pendingCagesOpenKillEffectRefresh.contains(rawKey)
                || (!lookupKey.isEmpty() && pendingCagesOpenKillEffectRefresh.contains(lookupKey));
    }

    /** Queues only a row that was skipped by a cages-open scan because its API cooldown was still active. */
    public static boolean tryQueuePendingCagesOpenKillEffectRefresh(NetworkPlayerInfo info) {
        if (info == null || info.getGameProfile() == null) {
            return false;
        }
        String tabName = info.getGameProfile().getName();
        if (!isPendingCagesOpenKillEffectRefresh(tabName)) {
            return false;
        }
        String statsLookup = resolveStatsLookupForTabRow(tabName, info.getGameProfile().getId());
        boolean queued = tryQueuePlayerScanFromTab(info, false, true);
        // Keep pending until a fresh response succeeds, including cooldown deferrals.
        return queued;
    }

    private void sendTabstatsBulkDetail(String message) {
        if (shouldEmitTabstatsBulkDetail()) {
            sendMessage(message);
        }
    }
    private static Path tabstatsDebugLogPath() {
        return GomodPaths.getGomod123Dir().toPath().resolve("tabstats-debug.log");
    }
    // Per-player cooldown tracking for 429 handling (ms timestamp)
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> cooldownUntilMs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> pendingUntilMs = new java.util.concurrent.ConcurrentHashMap<>();
    /** Cached rows whose cages-open kill-effect refresh was deferred by the per-player cooldown. */
    private static final java.util.Set<String> pendingCagesOpenKillEffectRefresh =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /**
     * Hypixel often returns HTTP 429 / "already looked up this player too recently" when two TAB rows
     * (nick vs real profile) resolve to the same UUID and both trigger {@link #fetchPlayerKillEffect}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> hypixelPlayerUuidCooldownUntilMs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> hypixelPlayerUuidFetchActiveUntilMs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long HYPIXEL_UUID_POST_SUCCESS_COOLDOWN_MS = 25_000L;
    private static final long HYPIXEL_UUID_RATE_LIMIT_COOLDOWN_MS = 120_000L;
    private static final long HYPIXEL_UUID_IN_FLIGHT_GUARD_MS = 90_000L;

    public KillEffectCommand() {
        super("tabstats");
    }

    /** Cancel HTTP work/results that were captured from the previous Hypixel world. */
    public static void invalidateAutomaticScans() {
        TABSTATS_SCAN_GENERATION.incrementAndGet();
        ACTIVE_AUTO_SCAN.set(0);
        pendingCagesOpenKillEffectRefresh.clear();
    }

    @Override
    public void handle(String[] args) {
        // Check if no arguments provided
        if (args.length == 0) {
            sendMessage(EnumChatFormatting.RED + (GomodInternalFeatures.isEnabled()
                    ? "Usage: /tabstats <player|all|toggle|debug|dump|inspect>"
                    : "Usage: /tabstats <player|all|toggle>"));
            return;
        }
        
        // Debug: Test guild tag for specific player
        if (args[0].equalsIgnoreCase("testguildplayer")) {
            if (!GomodInternalFeatures.requireInternalCommand("tabstats testguildplayer")) return;
            if (args.length <= 1) {
                sendMessage(EnumChatFormatting.RED + "Usage: /tabstats testguildplayer <player>");
                return;
            }
            sendMessage(EnumChatFormatting.YELLOW + "Testing guild tag for player: " + args[1]);
            me.napim.gomod.features.KillCounter.testGuildTagFetch(args[1]);
            return;
        }
        
        // Debug: Test guild tag functionality
        if (args[0].equalsIgnoreCase("testguild")) {
            if (!GomodInternalFeatures.requireInternalCommand("tabstats testguild")) return;
            sendMessage(EnumChatFormatting.YELLOW + "Testing guild tag functionality...");
            me.napim.gomod.features.KillCounter.testGuildTagFetch(Minecraft.getMinecraft().thePlayer.getName());
            return;
        }

        if (args[0].equalsIgnoreCase("debug")) {
            if (!GomodInternalFeatures.requireInternalCommand("tabstats debug")) return;
            debugEnabled = !debugEnabled;
            sendMessage(EnumChatFormatting.YELLOW + "Tabstats debug " +
                (debugEnabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
            sendMessage(EnumChatFormatting.GRAY + "Debug log: " + tabstatsDebugLogPath().toString());
            logDebug("Debug mode toggled to " + debugEnabled);
            return;
        }

        if (args[0].equalsIgnoreCase("dump")) {
            if (!GomodInternalFeatures.requireInternalCommand("tabstats dump")) return;
            dumpTabEntries();
            return;
        }

        if (args[0].equalsIgnoreCase("inspect")) {
            if (!GomodInternalFeatures.requireInternalCommand("tabstats inspect")) return;
            if (args.length != 2) {
                sendMessage(EnumChatFormatting.RED + "Usage: /tabstats inspect <player>");
                return;
            }
            inspectTabEntry(args[1]);
            return;
        }

        if (args.length != 1) {
            sendMessage(EnumChatFormatting.RED + (GomodInternalFeatures.isEnabled()
                    ? "Usage: /tabstats <player|all|toggle|debug|dump|inspect>"
                    : "Usage: /tabstats <player|all|toggle>"));
            return;
        }

        if (args[0].equalsIgnoreCase("toggle")) {
            enabled = !enabled;
            sendMessage(EnumChatFormatting.YELLOW + "Kill effects display " + 
                      (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
            
            // Reset all player names in tab if disabled
            if (!enabled) {
                resetAllPlayerNames();
            } else {
                // Reapply effects if enabled
                updateAllPlayerNames();
            }
            return;
        }

        if (args[0].equalsIgnoreCase("ALL")) {
            runTabStatsAllScanAsync(true, "command /tabstats all");
        } else {
            fetchPlayerKillEffect(args[0], null, true);
        }
    }

    /**
     * Immutable row captured on the client thread for safe background API work.
     * <p>
     * When gomod has denicked a player (TAB {@code nick (real)}), {@link #statsLookupName} is the real Mojang IGN
     * and {@link #tabProfileName} stays the tab {@link net.minecraft.client.network.NetworkPlayerInfo} profile name
     * (Hypixel nick). Hypixel {@code displayname} is matched against the nick; Mojang + Hypixel UUID use the real IGN
     * when they differ so a wrong profile UUID from the nick row does not fetch the wrong account.
     */
    public static final class PlayerTabScanRow {
        /** {@link NetworkPlayerInfo} GameProfile name (Hypixel nick on the row). */
        public final String tabProfileName;
        /** Mojang/Hypixel identity for API + caches ({@link me.napim.gomod.features.KillCounter#resolveTabLookupName}). */
        public final String statsLookupName;
        /** Client snapshot UUID without dashes (fallback if Mojang lookup fails). */
        public final String uuidNoDashSnapshot;

        public PlayerTabScanRow(String tabProfileName, String statsLookupName, String uuidNoDashSnapshot) {
            this.tabProfileName = tabProfileName;
            this.statsLookupName = statsLookupName;
            this.uuidNoDashSnapshot = uuidNoDashSnapshot;
        }
    }

    /**
     * Runs the same work as {@code /tabstats all} without sending a chat command (no server round-trip).
     * Snapshots the tab list on the client thread, then runs HTTP on a worker (Lunar-safe).
     * @param sendColorLegend if true, show the kill-effect color legend when {@link me.napim.gomod.features.KillCounter#isShowKeAllChat()} is on
     */
    public static void runTabStatsAllScanAsync(boolean sendColorLegend) {
        runTabStatsAllScanAsync(sendColorLegend, "unspecified");
    }

    /**
     * @param trigger short reason for debug chat (e.g. {@code auto: Blitz cages-open message})
     */
    public static void runTabStatsAllScanAsync(boolean sendColorLegend, String trigger) {
        if (me.napim.gomod.features.TabStatsRuntimeMute.isMuted()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        final String trig = trigger == null || trigger.isEmpty() ? "unspecified" : trigger;
        if (getUsableHypixelApiKey(isManualTabstatsTrigger(trig)) == null) {
            return;
        }
        final boolean autoScan = isPlayersJoinPregameAutoTrigger(trig);
        mc.addScheduledTask(() -> {
            if (autoScan && !GameContext.isBlitzPregameLobby(mc)) {
                return;
            }
            final int generation = TABSTATS_SCAN_GENERATION.get();
            final int scanToken = autoScan ? TABSTATS_SCAN_SEQUENCE.incrementAndGet() : 0;
            if (autoScan && !ACTIVE_AUTO_SCAN.compareAndSet(0, scanToken)) {
                return;
            }
            final Object capturedWorld = mc.theWorld;
            java.util.List<PlayerTabScanRow> rows = new java.util.ArrayList<>();
            java.util.HashSet<String> tabstatsUuidSeen = new java.util.HashSet<>();
            try {
                if (mc.getNetHandler() != null) {
                    for (NetworkPlayerInfo p : new java.util.ArrayList<>(mc.getNetHandler().getPlayerInfoMap())) {
                        if (p == null || p.getGameProfile() == null || p.getGameProfile().getId() == null) {
                            continue;
                        }
                        String tabName = p.getGameProfile().getName();
                        String uuid = p.getGameProfile().getId().toString().replace("-", "");
                        if (tabName == null || tabName.isEmpty() || uuid.isEmpty()) {
                            continue;
                        }
                        if (!tabstatsUuidSeen.add(uuid)) {
                            continue;
                        }
                        String lookup = me.napim.gomod.features.KillCounter.resolveTabLookupName(tabName);
                        if (lookup == null || lookup.trim().isEmpty()) {
                            lookup = tabName;
                        } else {
                            lookup = lookup.trim();
                        }
                        rows.add(new PlayerTabScanRow(tabName, lookup, uuid));
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
            final java.util.List<PlayerTabScanRow> frozen = rows;
            new Thread(() -> {
                try {
                    new KillEffectCommand().runTabStatsAllScan(sendColorLegend, frozen, trig,
                            generation, scanToken, capturedWorld);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }, "gomod-tabstats-all").start();
        });
    }

    /**
     * Sequential tab scan (guild + Hypixel Blitz stats) for the given tab rows (from {@link #runTabStatsAllScanAsync}).
     */
    private void runTabStatsAllScan(boolean sendColorLegend, java.util.List<PlayerTabScanRow> rows, String trigger,
                                    int generation, int scanToken, Object capturedWorld) {
        try {
            runTabStatsAllScanImpl(sendColorLegend, rows, trigger, generation, capturedWorld);
        } finally {
            if (scanToken != 0) {
                ACTIVE_AUTO_SCAN.compareAndSet(scanToken, 0);
            }
        }
    }

    private void runTabStatsAllScanImpl(boolean sendColorLegend, java.util.List<PlayerTabScanRow> rows,
                                        String trigger, int generation, Object capturedWorld) {
        final String trig = trigger == null || trigger.isEmpty() ? "unspecified" : trigger;
        if (me.napim.gomod.features.TabStatsRuntimeMute.isMuted()) {
            return;
        }
        if (generation != TABSTATS_SCAN_GENERATION.get()) {
            return;
        }
        final boolean uncertainKillEffectWrites = isPlayersJoinPregameAutoTrigger(trig);
        if (getUsableHypixelApiKey(isManualTabstatsTrigger(trig)) == null) {
            return;
        }
        if (sendColorLegend) {
            sendColorLegend();
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        TabStatsTrafficDebug.onBulkTabstatsScanStart(rows.size(), trig);
        if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
            HypixelApiRequestDebug.record("tab.bulk_scan_start",
                    "rows=" + rows.size() + " generation=" + generation + " pregame=" + uncertainKillEffectWrites
                            + " cagesOpen=" + isCagesOpenAutoTrigger(trig) + " manual=" + isManualTabstatsTrigger(trig));
        }
        if (me.napim.gomod.features.KillCounter.isShowKeAllChat()) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[Tabstats] Scan: " + rows.size()
                    + " player(s), trigger: " + EnumChatFormatting.GRAY + trig);
        }
        for (PlayerTabScanRow row : rows) {
            if (generation != TABSTATS_SCAN_GENERATION.get()) {
                return;
            }
            try {
                if (row == null || row.tabProfileName == null || row.statsLookupName == null
                        || row.uuidNoDashSnapshot == null || row.uuidNoDashSnapshot.isEmpty()) {
                    continue;
                }
                String statsKeyLower = row.statsLookupName.toLowerCase(Locale.ROOT);
                String tabKeyLower = row.tabProfileName.toLowerCase(Locale.ROOT);
                String pnLower = statsKeyLower;
                String bulkLabel = row.tabProfileName.equalsIgnoreCase(row.statsLookupName)
                        ? row.tabProfileName
                        : row.tabProfileName + " → " + row.statsLookupName;
                boolean cagesOpenKillEffectRefresh = isCagesOpenAutoTrigger(trig) && isKillEffectDisplayEnabled();
                boolean cachedStats = hasCachedStatsForKeys(statsKeyLower, tabKeyLower);
                if (cagesOpenKillEffectRefresh && cachedStats) {
                    pendingCagesOpenKillEffectRefresh.add(statsKeyLower);
                }
                if (shouldSkipAutomaticCachedBulkRow(trig, statsKeyLower, tabKeyLower)) {
                    if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
                        HypixelApiRequestDebug.record("tab.bulk_row_skipped",
                                "tabUuid=" + row.uuidNoDashSnapshot + " reason=cached-tab-stats");
                    }
                    sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "[Tabstats] Reusing cached stats for "
                            + EnumChatFormatting.WHITE + bulkLabel + EnumChatFormatting.GRAY
                            + " (automatic scan; kill-effect refresh not needed here)");
                    continue;
                }
                String uuid = resolveHypixelUuidNoDashForTabstatsRow(row);
                if (uuid == null || uuid.isEmpty()) {
                    continue;
                }
                long nowTs = System.currentTimeMillis();
                long untilTs = cooldownUntilMs.getOrDefault(pnLower, 0L);
                if (untilTs > nowTs) {
                    long rem = untilTs - nowTs;
                    if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
                        HypixelApiRequestDebug.record("tab.bulk_row_skipped",
                                "tabUuid=" + row.uuidNoDashSnapshot + " reason=tab-player-cooldown remainingMs=" + rem);
                    }
                    long secs = Math.max(1L, rem / 1000L);
                    sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "[Tabstats] Skipping "
                            + EnumChatFormatting.WHITE + bulkLabel + EnumChatFormatting.GRAY
                            + " — gomod 60s per-player API cooldown (" + secs + "s left). Trigger: "
                            + EnumChatFormatting.DARK_GRAY + trig + EnumChatFormatting.GRAY
                            + " (not Hypixel HTTP 429)");
                    continue;
                }

                me.napim.gomod.features.KillCounter.fetchGuildTagWithKnownUuid(row.statsLookupName.trim(), uuid);

                JsonObject response = null;
                boolean skippedAfter429 = false;
                sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[Tabstats] Hypixel request: "
                        + EnumChatFormatting.WHITE + bulkLabel + EnumChatFormatting.DARK_GRAY
                        + " (trigger: " + trig + ")");
                for (int attempt = 1; attempt <= HYPIXEL_TABSTATS_MAX_429_RETRIES; attempt++) {
                    String apiKey = getUsableHypixelApiKey(false);
                    if (apiKey == null) {
                        return;
                    }
                    try {
                        awaitGlobalTabstatsRequestSlot();
                        boolean manualKillEffectRefresh = isManualTabstatsTrigger(trig)
                                && isKillEffectDisplayEnabled() && !isPregameNow();
                        response = me.napim.gomod.features.HypixelApiHelper.fetchBlitzJson(
                                "https://api.hypixel.net/player?key=" + apiKey + "&uuid=" + uuid,
                                cagesOpenKillEffectRefresh || manualKillEffectRefresh,
                                !isManualTabstatsTrigger(trig));
                        me.napim.gomod.features.GameModeStatusFetcher.noteHypixelApiSuccess(apiKey);
                        break;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ex) {
                        if (!isHypixelHttp429(ex)) {
                            if (me.napim.gomod.features.GameModeStatusFetcher
                                    .handleHypixelInvalidKeyFailure(apiKey, ex.getMessage(), true)) {
                                return;
                            }
                            throw ex;
                        }
                        if (ex instanceof me.napim.gomod.features.HypixelApiHelper.HttpFailure) {
                            cooldownUntilMs.put(pnLower, System.currentTimeMillis() + 60_000L);
                            skippedAfter429 = true;
                            break;
                        }
                        if (attempt >= HYPIXEL_TABSTATS_MAX_429_RETRIES) {
                            long now = System.currentTimeMillis();
                            cooldownUntilMs.put(pnLower, now + 60_000L);
                            if (me.napim.gomod.features.KillCounter.isShowKeAllChat()) {
                                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "[Tabstats] Hypixel HTTP 429 for "
                                        + EnumChatFormatting.YELLOW + bulkLabel + EnumChatFormatting.RED
                                        + " — still throttled after " + HYPIXEL_TABSTATS_MAX_429_RETRIES
                                        + " retries; try again in ~60s");
                            }
                            skippedAfter429 = true;
                            break;
                        }
                        long backoffMs = Math.min(15_000L, 1_000L * attempt);
                        sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "[Tabstats] HTTP 429 for "
                                + EnumChatFormatting.WHITE + bulkLabel + EnumChatFormatting.YELLOW
                                + " — retry " + (attempt + 1) + "/" + HYPIXEL_TABSTATS_MAX_429_RETRIES
                                + " in " + Math.max(1L, backoffMs / 1000L) + "s");
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        }
                    }

                if (generation != TABSTATS_SCAN_GENERATION.get()) {
                    return;
                }
                if (skippedAfter429) {
                    continue;
                }

                String pnKey = statsKeyLower;
                long rowNow = System.currentTimeMillis();

                if (response != null && response.has("success") && response.get("success").getAsBoolean()
                        && response.has("player") && !response.get("player").isJsonNull()) {
                    pendingCagesOpenKillEffectRefresh.remove(pnKey);
                    pendingCagesOpenKillEffectRefresh.remove(tabKeyLower);
                    JsonObject playerData = response.get("player").getAsJsonObject();
                    if (isManualTabstatsTrigger(trig) && me.napim.gomod.features.HypixelPlayerRequests.isCached(playerData))
                        sendMessage(EnumChatFormatting.GRAY + bulkLabel + ": "
                                + me.napim.gomod.features.HypixelPlayerRequests.cacheLabel(playerData));
                    if (!hypixelDisplaynameMatchesTabProfile(playerData, row.tabProfileName, row.statsLookupName, true)) {
                        hypixelRankRemoveAliases(pnKey, tabKeyLower);
                        putPlayerKillEffectUnlessLiveChatDefers(pnKey, "none", false);
                        clearBlitzTabDataLower(pnKey);
                        cooldownUntilMs.put(pnLower, rowNow + 60_000L);
                        String dn = playerData.has("displayname") && !playerData.get("displayname").isJsonNull()
                                ? playerData.get("displayname").getAsString() : "?";
                        sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + bulkLabel
                                + ": Hypixel API profile is " + EnumChatFormatting.WHITE + dn
                                + EnumChatFormatting.GRAY + " (tab nick mismatch). No TAB wl/w.");
                        continue;
                    }
                    hypixelRankIngestAliases(pnKey, tabKeyLower, playerData);
                    if (playerData.has("stats") && !playerData.get("stats").isJsonNull()) {
                        JsonObject stats = playerData.get("stats").getAsJsonObject();
                        if (stats.has("HungerGames") && !stats.get("HungerGames").isJsonNull()) {
                            JsonObject blitzStats = stats.get("HungerGames").getAsJsonObject();
                            String killEffect = getKillEffect(blitzStats);

                            if (tabstatsFullDebugChat) {
                                System.out.println("[KillEffectCommand] tabstats all: Storing data for " + bulkLabel);
                            }

                            putPlayerKillEffectUnlessLiveChatDefers(pnKey, killEffect, uncertainKillEffectWrites);
                            putBlitzTabDataLower(pnKey, blitzStats);
                            cooldownUntilMs.put(pnLower, rowNow + 60_000L);
                            if (!shouldSkipApiKillEffectPutForLocalPlayer(pnKey)) {
                                maybeSyncFlameKillHudFromBlitzRow(row.tabProfileName, blitzStats, killEffect);
                            }

                            sendTabstatsBulkDetail(EnumChatFormatting.GREEN + row.statsLookupName + "'s kill effect: "
                                    + getKillEffectColor(killEffect) + formatKillEffectName(killEffect));
                        } else {
                            // Hypixel omits HungerGames until the player has played Blitz at least once.
                            putPlayerKillEffectUnlessLiveChatDefers(pnKey, DEFAULT_BLITZ_KILL_EFFECT, uncertainKillEffectWrites);
                            putBlitzTabDataLower(pnKey, new JsonObject());
                            cooldownUntilMs.put(pnLower, rowNow + 60_000L);
                            sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + bulkLabel
                                    + ": No HungerGames in API (never played Blitz) — TAB stats shown as "
                                    + EnumChatFormatting.WHITE + "0"
                                    + EnumChatFormatting.GRAY + " and default kill effect "
                                    + getKillEffectColor(DEFAULT_BLITZ_KILL_EFFECT) + formatKillEffectName(DEFAULT_BLITZ_KILL_EFFECT)
                                    + EnumChatFormatting.GRAY + ".");
                        }
                    } else {
                        putPlayerKillEffectUnlessLiveChatDefers(pnKey, DEFAULT_BLITZ_KILL_EFFECT, uncertainKillEffectWrites);
                        putBlitzTabDataLower(pnKey, new JsonObject());
                        cooldownUntilMs.put(pnLower, rowNow + 60_000L);
                        sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + bulkLabel
                                + ": No stats object in API — TAB Blitz stats shown as "
                                + EnumChatFormatting.WHITE + "0"
                                + EnumChatFormatting.GRAY + " and default kill effect "
                                + getKillEffectColor(DEFAULT_BLITZ_KILL_EFFECT) + formatKillEffectName(DEFAULT_BLITZ_KILL_EFFECT)
                                + EnumChatFormatting.GRAY + ".");
                    }
                } else {
                    pendingCagesOpenKillEffectRefresh.remove(pnKey);
                    pendingCagesOpenKillEffectRefresh.remove(tabKeyLower);
                    hypixelRankRemoveAliases(pnKey, tabKeyLower);
                    // One Hypixel attempt done (or unusable response): mark row so scanMissing won't re-fetch the same player.
                    putPlayerKillEffectUnlessLiveChatDefers(pnKey, "none", false);
                    playerStats.put(pnKey, "No Blitz stats available");
                    playerBlitzMetrics.remove(pnKey);
                    removeTabSortMetricsLower(pnKey);
                    cooldownUntilMs.put(pnLower, rowNow + 60_000L);
                    sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + bulkLabel
                            + EnumChatFormatting.RED + ": No Blitz stats available"
                            + EnumChatFormatting.GRAY + " (" + describeApiFailureReason(response) + ")");
                }
            } catch (Exception e) {
                if (!isManualTabstatsTrigger(trig) && noteAutomaticTabstatsFailure(e)) {
                    String failedName = row == null ? null : row.statsLookupName;
                    if (failedName != null && !failedName.trim().isEmpty()) {
                        long retryDelay = isAutomaticTabstatsFreshnessDeferred(e)
                                ? HYPIXEL_UUID_RATE_LIMIT_COOLDOWN_MS : 60_000L;
                        cooldownUntilMs.put(failedName.trim().toLowerCase(Locale.ROOT),
                                System.currentTimeMillis() + retryDelay);
                    }
                    continue;
                }
                e.printStackTrace();
                String msg = e.getMessage() == null ? "Unknown error" : e.getMessage();
                String errRow = row != null && row.tabProfileName != null ? row.tabProfileName : "?";
                sendTabstatsBulkDetail(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + errRow
                        + EnumChatFormatting.RED + ": API request failed"
                        + EnumChatFormatting.GRAY + " (" + msg + ")");
            }
        }
        if (generation != TABSTATS_SCAN_GENERATION.get()) {
            return;
        }
        me.napim.gomod.features.KillCounter.markTabCompositionDirty();
        // Worker thread: debounced tick compose can lose to tab packets — force one client-thread apply
        // so WL/stats show immediately after /tabstats all (same as toggling Kill Counter in settings).
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(() -> {
                    if (generation != TABSTATS_SCAN_GENERATION.get() || mc.theWorld != capturedWorld) {
                        return;
                    }
                    me.napim.gomod.features.KillCounter.forceTabRefresh();
                    me.napim.gomod.features.KillCounter.schedulePostTabStatsAllScans();
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private void sendMessage(String message) {
        Main.sendMessage(message);
    }

    private void markTabstatsLookupUnavailable(String tab, String statsLookup, String statsKeyLower, String tabKeyLower) {
        String key = statsKeyLower == null ? "" : statsKeyLower.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return;
        }
        hypixelRankRemoveAliases(key, tabKeyLower);
        putPlayerKillEffectUnlessLiveChatDefers(key, "none", false);
        clearBlitzTabDataLower(key);
        playerStats.put(key, "No Blitz stats available");
        playerBlitzMetrics.remove(key);
        removeTabSortMetricsLower(key);
        updatePlayerInTabForStats(tab, statsLookup, "none");
        cooldownUntilMs.put(key, System.currentTimeMillis() + 60_000L);
    }

    private static boolean isSilentNickLookupFailure(String message) {
        if (message == null) {
            return true;
        }
        String lower = message.trim().toLowerCase(Locale.ROOT);
        return lower.isEmpty()
                || lower.contains("failed to get player data")
                || lower.contains("player not found")
                || lower.contains("no player")
                || lower.contains("player is null");
    }

    private String describeApiFailureReason(JsonObject response) {
        if (response == null) {
            return "no response from API";
        }
        try {
            if (response.has("cause") && !response.get("cause").isJsonNull()) {
                String cause = response.get("cause").getAsString();
                if (cause != null && !cause.trim().isEmpty()) {
                    return "API cause: " + cause.trim();
                }
            }
            if (response.has("success") && !response.get("success").isJsonNull() && !response.get("success").getAsBoolean()) {
                return "success=false";
            }
            if (!response.has("player") || response.get("player").isJsonNull()) {
                return "player data missing (nick/not found/private)";
            }
            return "unexpected API payload";
        } catch (Exception ignored) {
            return "unexpected API payload";
        }
    }

    /**
     * Cooldown + tabstats cache key: real Mojang IGN when gomod has denicked this tab name, else the raw name.
     */
    public static String tabstatsCooldownKey(String rawTabOrCommandName) {
        if (rawTabOrCommandName == null || rawTabOrCommandName.trim().isEmpty()) {
            return "";
        }
        String t = rawTabOrCommandName.trim();
        String lookup = me.napim.gomod.features.KillCounter.resolveTabLookupName(t);
        if (lookup == null || lookup.trim().isEmpty()) {
            return t.toLowerCase(Locale.ROOT);
        }
        return lookup.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isOnCooldown(String playerName) {
        if (playerName == null) return false;
        String key = tabstatsCooldownKey(playerName);
        if (key.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long until = cooldownUntilMs.getOrDefault(key, 0L);
        if (until > now) return true;
        long pending = pendingUntilMs.getOrDefault(key, 0L);
        return pending > now;
    }

    public static boolean tryQueuePlayerScan(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) return false;
        NetworkPlayerInfo tabInfo = findTabInfoForScan(playerName.trim());
        if (tabInfo != null) {
            return tryQueuePlayerScanFromTab(tabInfo);
        }
        return tryQueuePlayerScanByNameOnly(playerName.trim());
    }

    /** Uses the tab row UUID (nick-safe) instead of a Mojang name lookup. */
    public static boolean tryQueuePlayerScanFromTab(NetworkPlayerInfo info) {
        return tryQueuePlayerScanFromTab(info, false);
    }

    /** Uses the tab row UUID (nick-safe) instead of a Mojang name lookup. */
    public static boolean tryQueuePlayerScanFromTab(NetworkPlayerInfo info, boolean uncertainKillEffectUntilRefresh) {
        return tryQueuePlayerScanFromTab(info, uncertainKillEffectUntilRefresh, false);
    }

    /**
     * Uses the tab row UUID (nick-safe). The post-cages path may force a refresh because only the kill effect is
     * expected to change between the pregame scan and cage opening; cached stats themselves remain reusable.
     */
    public static boolean tryQueuePlayerScanFromTab(NetworkPlayerInfo info, boolean uncertainKillEffectUntilRefresh,
                                                     boolean forceKillEffectRefresh) {
        if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null) {
            return false;
        }
        if (getUsableHypixelApiKey(false) == null) {
            return false;
        }
        String tabName = info.getGameProfile().getName();
        if (tabName == null || tabName.trim().isEmpty()) {
            return false;
        }
        String statsLookup = resolveStatsLookupForTabRow(tabName.trim(), info.getGameProfile().getId());
        String key = statsLookup.toLowerCase(Locale.ROOT);
        String tabKey = tabName.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return false;
        }
        if (!forceKillEffectRefresh && hasCachedStatsForKeys(key, tabKey)
                && (!isKillEffectDisplayEnabled() || hasCachedEffectForKeys(key, tabKey) || isPregameNow())) {
            return false;
        }
        long now = System.currentTimeMillis();
        long until = cooldownUntilMs.getOrDefault(key, 0L);
        if (until > now) return false;
        long pending = pendingUntilMs.getOrDefault(key, 0L);
        if (pending > now) return false;
        pendingUntilMs.put(key, now + 5000L);
        TabStatsTrafficDebug.onQueuedSinglePlayerScan(tabName.trim());
        String uuidNoDash = info.getGameProfile().getId().toString().replace("-", "");
        if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
            HypixelApiRequestDebug.record("tab.player_queued",
                    "tabUuid=" + uuidNoDash + " pregame=" + uncertainKillEffectUntilRefresh
                            + " postCagesRefresh=" + forceKillEffectRefresh);
        }
        new KillEffectCommand().fetchPlayerKillEffect(tabName.trim(), uuidNoDash, false,
                uncertainKillEffectUntilRefresh, true);
        cooldownUntilMs.put(key, now + 60_000L);
        return true;
    }

    private static boolean tryQueuePlayerScanByNameOnly(String playerName) {
        if (getUsableHypixelApiKey(false) == null) {
            return false;
        }
        String key = tabstatsCooldownKey(playerName);
        if (key.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long until = cooldownUntilMs.getOrDefault(key, 0L);
        if (until > now) return false;
        long pending = pendingUntilMs.getOrDefault(key, 0L);
        if (pending > now) return false;
        if (hasCachedStatsForKeys(key, playerName)
                && (!isKillEffectDisplayEnabled() || hasCachedEffectForKeys(key, playerName) || isPregameNow())) {
            return false;
        }
        pendingUntilMs.put(key, now + 5000L);
        TabStatsTrafficDebug.onQueuedSinglePlayerScan(playerName);
        new KillEffectCommand().fetchPlayerKillEffect(playerName, null, false, false, true);
        cooldownUntilMs.put(key, now + 60_000L);
        return true;
    }

    private static NetworkPlayerInfo findTabInfoForScan(String playerName) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null || playerName == null || playerName.trim().isEmpty()) {
            return null;
        }
        String want = playerName.trim();
        NetworkPlayerInfo direct = null;
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            String profileName = info.getGameProfile().getName();
            if (profileName != null && profileName.equalsIgnoreCase(want)) {
                direct = info;
                break;
            }
        }
        if (direct != null) {
            return direct;
        }
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            String profileName = info.getGameProfile().getName();
            if (profileName == null || profileName.trim().isEmpty()) {
                continue;
            }
            if (want.equalsIgnoreCase(me.napim.gomod.features.KillCounter.resolveTabLookupName(profileName))) {
                return info;
            }
            UUID id = info.getGameProfile().getId();
            if (id != null) {
                String cachedReal = TabDuplicateNickResolver.getCachedMojangUsernameForUuid(id);
                if (cachedReal != null && cachedReal.equalsIgnoreCase(want)) {
                    return info;
                }
            }
        }
        return null;
    }

    private static String resolveStatsLookupForTabRow(String tabName, UUID profileUuid) {
        String statsLookup = me.napim.gomod.features.KillCounter.resolveTabLookupName(tabName);
        if (profileUuid != null) {
            String cachedReal = TabDuplicateNickResolver.getCachedMojangUsernameForUuid(profileUuid);
            if (cachedReal != null && !cachedReal.trim().isEmpty()) {
                return cachedReal.trim();
            }
        }
        if (statsLookup == null || statsLookup.trim().isEmpty()) {
            return tabName;
        }
        return statsLookup.trim();
    }

    private void logDebug(String message) {
        String line = "[tabstats-debug] " + message;
        System.out.println(line);
        appendDebugLog(line);
    }

    private void appendDebugLog(String line) {
        try {
            Path logPath = tabstatsDebugLogPath();
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(
                logPath,
                (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            System.out.println("[tabstats-debug] Failed to write debug file: " + e.getMessage());
        }
    }

    private void dumpTabEntries() {
        List<NetworkPlayerInfo> players = getPlayersInTabOrder();
        sendMessage(EnumChatFormatting.YELLOW + "Dumping " + players.size() + " tab entries to latest.log");
        logDebug("==== TAB DUMP START (" + players.size() + " entries) ====");
        for (int i = 0; i < players.size(); i++) {
            debugTabPlayer("dump#" + i, players.get(i));
        }
        logDebug("==== TAB DUMP END ====");
    }

    private void inspectTabEntry(String query) {
        List<NetworkPlayerInfo> matches = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (NetworkPlayerInfo player : getPlayersInTabOrder()) {
            if (matchesQuery(player, lowerQuery)) {
                matches.add(player);
            }
        }

        if (matches.isEmpty()) {
            sendMessage(EnumChatFormatting.RED + "No tab entry matched: " + query);
            logDebug("inspect: no match for '" + query + "'");
            return;
        }

        sendMessage(EnumChatFormatting.YELLOW + "Inspecting " + matches.size() + " matching tab entr" +
            (matches.size() == 1 ? "y" : "ies") + " for " + query + ". See latest.log");
        logDebug("==== INSPECT START query='" + query + "' matches=" + matches.size() + " ====");
        for (int i = 0; i < matches.size(); i++) {
            debugTabPlayer("inspect#" + i, matches.get(i));
        }
        logDebug("==== INSPECT END ====");
    }

    private boolean matchesQuery(NetworkPlayerInfo player, String lowerQuery) {
        String profileName = safeString(player.getGameProfile().getName());
        if (profileName.toLowerCase().contains(lowerQuery)) {
            return true;
        }

        String displayFormatted = getDisplayFormatted(player);
        if (displayFormatted.toLowerCase().contains(lowerQuery)) {
            return true;
        }

        String displayPlain = getDisplayPlain(player);
        if (displayPlain.toLowerCase().contains(lowerQuery)) {
            return true;
        }

        String teamFormatted = getTeamFormatted(player);
        if (teamFormatted.toLowerCase().contains(lowerQuery)) {
            return true;
        }

        String teamPlain = getTeamPlain(player);
        return teamPlain.toLowerCase().contains(lowerQuery);
    }

    private void debugTabPlayer(String context, NetworkPlayerInfo player) {
        if (!debugEnabled && !context.startsWith("dump") && !context.startsWith("inspect")) {
            return;
        }

        String profileName = safeString(player.getGameProfile().getName());
        String uuid = player.getGameProfile().getId() == null ? "<null>" : player.getGameProfile().getId().toString();
        ScorePlayerTeam team = player.getPlayerTeam();
        String teamName = team == null ? "<null>" : safeString(team.getRegisteredName());
        String teamFormatted = getTeamFormatted(player);
        String teamPlain = getTeamPlain(player);
        String displayFormatted = getDisplayFormatted(player);
        String displayPlain = getDisplayPlain(player);

        logDebug(context
            + " profileName='" + profileName + '\''
            + " uuid='" + uuid + '\''
            + " teamName='" + teamName + '\''
            + " teamFormatted='" + teamFormatted + '\''
            + " teamPlain='" + teamPlain + '\''
            + " displayFormatted='" + displayFormatted + '\''
            + " displayPlain='" + displayPlain + '\'');
    }

    private String getTeamFormatted(NetworkPlayerInfo player) {
        ScorePlayerTeam team = player.getPlayerTeam();
        if (team == null) {
            return "<null>";
        }
        return sanitizeForLog(ScorePlayerTeam.formatPlayerName(team, safeString(player.getGameProfile().getName())));
    }

    private String getTeamPlain(NetworkPlayerInfo player) {
        String formatted = getTeamFormatted(player);
        if ("<null>".equals(formatted)) {
            return "<null>";
        }
        return sanitizeForLog(stripFormatting(formatted));
    }

    private String getDisplayFormatted(NetworkPlayerInfo player) {
        IChatComponent displayName = player.getDisplayName();
        if (displayName == null) {
            return "<null>";
        }
        return sanitizeForLog(displayName.getFormattedText());
    }

    private String getDisplayPlain(NetworkPlayerInfo player) {
        IChatComponent displayName = player.getDisplayName();
        if (displayName == null) {
            return "<null>";
        }
        return sanitizeForLog(displayName.getUnformattedText());
    }

    private String stripFormatting(String value) {
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(value);
        return stripped == null ? value : stripped;
    }

    private String safeString(String value) {
        return value == null ? "<null>" : sanitizeForLog(value);
    }

    private String sanitizeForLog(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    private JsonObject makeRequest(String urlString) throws Exception {
        return makeRequest(urlString, false);
    }

    private JsonObject makeRequest(String urlString, boolean suppressErrorMessage) throws Exception {
        TabStatsTrafficDebug.onHttpUrl(urlString);
        try {
            return me.napim.gomod.features.HypixelApiHelper.fetchJson(urlString);
        } catch (Exception e) {
            if (!suppressErrorMessage) {
                sendMessage(EnumChatFormatting.RED + "API Error: " + e.getMessage());
            }
            throw e;
        }
    }

    private static boolean isManualTabstatsTrigger(String trigger) {
        if (trigger == null) {
            return false;
        }
        return trigger.toLowerCase(Locale.ROOT).startsWith("command /tabstats");
    }

    private static String getUsableHypixelApiKey(boolean notifyIfKnownBad) {
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            if (notifyIfKnownBad) {
                me.napim.gomod.Main.sendMessage(EnumChatFormatting.RED + "Hypixel API key not set! Use /gomod api to set it.");
            }
            return null;
        }
        apiKey = apiKey.trim();
        if (me.napim.gomod.features.GameModeStatusFetcher.shouldSkipKnownBadHypixelRequests(apiKey)) {
            if (notifyIfKnownBad) {
                me.napim.gomod.features.GameModeStatusFetcher.sendKnownHypixelKeyProblem(apiKey);
            }
            return null;
        }
        return apiKey;
    }

    private static boolean isHypixelHttp429(Throwable ex) {
        if (ex == null) {
            return false;
        }
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }
        String lower = m.toLowerCase(Locale.ROOT);
        return lower.contains("http 429") || lower.contains("response code: 429");
    }

    private static boolean isHypixelUuidRateLimitMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("already looked up") || lower.contains("too recently");
    }

    private static void awaitGlobalTabstatsRequestSlot() throws InterruptedException {
        synchronized (TABSTATS_REQUEST_PACE_LOCK) {
            long now = System.currentTimeMillis();
            long waitMs = lastTabstatsRequestStartMs + TABSTATS_SCAN_PACE_MS - now;
            if (waitMs > 0L) {
                Thread.sleep(waitMs);
            }
            lastTabstatsRequestStartMs = System.currentTimeMillis();
        }
    }

    /**
     * When denicked, resolve Mojang UUID from the real IGN so Hypixel sees the correct account even if the tab row's
     * GameProfile UUID is wrong/out of sync for a nick.
     */
    private String resolveHypixelUuidNoDashForTabstatsRow(PlayerTabScanRow row) {
        if (row == null || row.uuidNoDashSnapshot == null || row.uuidNoDashSnapshot.isEmpty()) {
            return "";
        }
        String snap = row.uuidNoDashSnapshot.replace("-", "").trim();
        if (row.statsLookupName == null || row.tabProfileName == null) {
            return snap;
        }
        String lookup = row.statsLookupName.trim();
        String tab = row.tabProfileName.trim();
        if (lookup.isEmpty() || lookup.equalsIgnoreCase(tab)) {
            return snap;
        }
        try {
            String url = "https://api.mojang.com/users/profiles/minecraft/" + lookup;
            JsonObject mojang = makeRequest(url, true);
            if (mojang != null && mojang.has("id") && !mojang.get("id").isJsonNull()) {
                String id = mojang.get("id").getAsString();
                if (id != null && !id.isEmpty()) {
                    return id.replace("-", "");
                }
            }
        } catch (Throwable ignored) {
        }
        return snap;
    }

    private static void hypixelRankIngestAliases(String statsKeyLower, String tabKeyLower, JsonObject playerData) {
        if (statsKeyLower == null || playerData == null) {
            return;
        }
        HypixelTabStatsRankCache.ingest(statsKeyLower, playerData);
        long observedAt = me.napim.gomod.features.HypixelPlayerRequests.observedAt(playerData);
        if (observedAt <= 0L) observedAt = System.currentTimeMillis();
        statsObservedAtMs.put(statsKeyLower, observedAt);
        if (tabKeyLower != null) statsObservedAtMs.put(tabKeyLower, observedAt);
        me.napim.gomod.features.HypixelPlayerDataCache.ingest(statsKeyLower, playerData);
        if (tabKeyLower != null && !tabKeyLower.isEmpty() && !tabKeyLower.equals(statsKeyLower)) {
            HypixelTabStatsRankCache.ingest(tabKeyLower, playerData);
            me.napim.gomod.features.HypixelPlayerDataCache.ingest(tabKeyLower, playerData);
        }
        try {
            if (playerData.has("displayname") && !playerData.get("displayname").isJsonNull()) {
                me.napim.gomod.features.HypixelPlayerDataCache.ingest(
                        playerData.get("displayname").getAsString(), playerData);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hypixelRankRemoveAliases(String statsKeyLower, String tabKeyLower) {
        if (statsKeyLower != null) {
            HypixelTabStatsRankCache.remove(statsKeyLower);
        }
        if (tabKeyLower != null && !tabKeyLower.isEmpty() && !tabKeyLower.equals(statsKeyLower)) {
            HypixelTabStatsRankCache.remove(tabKeyLower);
        }
    }

    /** Refresh tab row after tabstats: match GameProfile nick first, then any row whose denick resolves to {@code statsLookupIgn}. */
    private void updatePlayerInTabForStats(String tabProfileNameGuess, String statsLookupIgn, String effect) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (!mc.isCallingFromMinecraftThread()) {
            mc.addScheduledTask(() -> updatePlayerInTabForStats(tabProfileNameGuess, statsLookupIgn, effect));
            return;
        }
        if (mc.getNetHandler() == null) {
            return;
        }
        String tabGuess = tabProfileNameGuess == null ? "" : tabProfileNameGuess.trim();
        String statsL = statsLookupIgn == null ? "" : statsLookupIgn.trim();
        for (NetworkPlayerInfo playerInfo : getPlayersInTabOrder()) {
            if (playerInfo == null || playerInfo.getGameProfile() == null) {
                continue;
            }
            String gn = playerInfo.getGameProfile().getName();
            if (gn != null && !tabGuess.isEmpty() && gn.equalsIgnoreCase(tabGuess)) {
                updatePlayerTabName(playerInfo, gn, effect);
                return;
            }
        }
        if (!statsL.isEmpty()) {
            for (NetworkPlayerInfo playerInfo : getPlayersInTabOrder()) {
                if (playerInfo == null || playerInfo.getGameProfile() == null) {
                    continue;
                }
                String gn = playerInfo.getGameProfile().getName();
                if (gn == null) {
                    continue;
                }
                String rowLookup = me.napim.gomod.features.KillCounter.resolveTabLookupName(gn);
                if (rowLookup != null && rowLookup.trim().equalsIgnoreCase(statsL)) {
                    updatePlayerTabName(playerInfo, gn, effect);
                    return;
                }
            }
        }
    }

    private EnumChatFormatting getKillEffectColor(String effect) {
        switch (effect.toUpperCase()) {
            case "REGENERATION": return EnumChatFormatting.LIGHT_PURPLE;
            case "RESISTANCE": return EnumChatFormatting.GOLD;
            case "GRAVEDIGGER": return EnumChatFormatting.GREEN;
            case "RANDOM": return EnumChatFormatting.DARK_BLUE;
            case "LEVEL_UP": return EnumChatFormatting.YELLOW;
            case "LEVELUP": return EnumChatFormatting.YELLOW;
            case "RAPID_FIRE": return EnumChatFormatting.RED;
            case "FLAME": return EnumChatFormatting.DARK_RED;
            case "SPEED": return EnumChatFormatting.AQUA;
            default: return EnumChatFormatting.AQUA;
        }
    }

    private void updatePlayerTabName(NetworkPlayerInfo player, String playerName, String effect) {
        try {
            // Don't update if disabled
            if (!enabled) {
                return;
            }
            // Do not directly set displayName to avoid conflicts with KillCounter.
            // Just ensure our stats cache is up-to-date and let KillCounter compose Tab.
        } catch (Exception e) {
            // Silently handle any errors
        }
    }

    private void resetAllPlayerNames() {
        if (Minecraft.getMinecraft().getNetHandler() == null) return;
        if (me.napim.gomod.features.GomodFixes.isWeaveRuntime()) return;
        
        for (NetworkPlayerInfo player : getPlayersInTabOrder()) {
            try {
                // Clear override to restore server-provided formatting
                player.setDisplayName(null);
            } catch (Exception e) {
                // Silently handle errors
            }
        }
    }

    private void updateAllPlayerNames() {
        if (Minecraft.getMinecraft().getNetHandler() == null) return;
        
        for (NetworkPlayerInfo player : getPlayersInTabOrder()) {
            String playerName = player.getGameProfile().getName();
            if (playerKillEffects.containsKey(playerName)) {
                updatePlayerTabName(player, playerName, playerKillEffects.get(playerName));
            }
        }
    }

    // Removed: onPlayerListUpdate(Event) was subscribing to ALL events (thousands/sec)
    // and is redundant with onPackets(Receive) + onTick(TickEvent) which already cover tab refreshes.

    // Re-apply on server tab/teams updates to prevent disappearance
    @SubscribeEvent
    public void onPackets(Receive event) {
        if (event != null && event.getPacket() instanceof S01PacketJoinGame) {
            invalidateAutomaticScans();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) return;
        if (me.napim.gomod.features.TabStatsRuntimeMute.isMuted()) {
            return;
        }
        if (!me.napim.gomod.features.KillCounter.shouldHandleTabQolPackets(mc)) {
            return;
        }
        if (event.getPacket() instanceof S38PacketPlayerListItem) {
            TabStatsTrafficDebug.onInboundTabPacket("S38");
            me.napim.gomod.features.KillCounter.markTabCompositionDirty();
        } else if (event.getPacket() instanceof S3EPacketTeams) {
            TabStatsTrafficDebug.onInboundTabPacket("S3E");
            me.napim.gomod.features.KillCounter.markTabCompositionDirtyFromTeamsPacketBurst(mc);
        }
    }

    private String formatKillEffectName(String effect) {
        switch (effect.toUpperCase()) {
            case "REGENERATION": return "Regeneration";
            case "RESISTANCE": return "Resistance";
            case "GRAVEDIGGER": return "Grave digger";
            case "RANDOM": return "Random";
            case "LEVEL_UP": return "Level Up";
            case "LEVELUP": return "Level Up";
            case "RAPID_FIRE": return "Flaming Arrows";
            case "FLAME": return "Flaming Arrows";
            case "SPEED": return "Speed";
            default: return "Speed";
        }
    }

    private void sendColorLegend() {
        if (me.napim.gomod.features.KillCounter.isShowKeAllChat()) {
            sendMessage(EnumChatFormatting.WHITE + "Kill Effect Colors:");
            sendMessage(EnumChatFormatting.AQUA + "> " + EnumChatFormatting.AQUA + "Speed");
            sendMessage(EnumChatFormatting.GOLD + "> " + EnumChatFormatting.GOLD + "Resistance");
            sendMessage(EnumChatFormatting.LIGHT_PURPLE + "> " + EnumChatFormatting.LIGHT_PURPLE + "Regeneration");
            sendMessage(EnumChatFormatting.DARK_RED + "> " + EnumChatFormatting.DARK_RED + "Flaming Arrows");
            sendMessage(EnumChatFormatting.YELLOW + "> " + EnumChatFormatting.YELLOW + "Level Up");
            sendMessage(EnumChatFormatting.GREEN + "> " + EnumChatFormatting.GREEN + "Grave digger");
            sendMessage(EnumChatFormatting.DARK_BLUE + "> " + EnumChatFormatting.DARK_BLUE + "Random");
        }
    }

    private String getKillEffect(JsonObject blitzStats) {
        if (blitzStats.has("afterkill") && !blitzStats.get("afterkill").isJsonNull()) {
            String effect = blitzStats.get("afterkill").getAsString().toUpperCase();
            
            // Handle special cases
            if (effect.equals("NONE")) {
                return DEFAULT_BLITZ_KILL_EFFECT;
            } else if (effect.equals("LEVEL_UP") || effect.equals("LEVELUP")) {
                return "LEVEL_UP";
            } else if (effect.equals("RAPID_FIRE")) {
                return "FLAME";
            }
            
            return effect;
        }
        return DEFAULT_BLITZ_KILL_EFFECT;
    }

    private String calculateStats(JsonObject blitzStats) {
        try {
            return TabStatsBlitzMetrics.fromHypixel(blitzStats).formatFull();
        } catch (Exception e) {
            return "";
        }
    }

    private static void putBlitzTabDataLower(String pnKeyLower, JsonObject blitzStats) {
        if (pnKeyLower == null) {
            return;
        }
        String k = pnKeyLower.toLowerCase(Locale.ROOT);
        TabStatsBlitzMetrics metrics = TabStatsBlitzMetrics.fromHypixel(blitzStats);
        playerBlitzMetrics.put(k, metrics);
        playerStats.put(k, metrics.formatFull());
        putTabSortMetricsFromBlitzLower(k, blitzStats);
        me.napim.gomod.features.KillCounter.markTabCompositionDirty();
    }

    private static void clearBlitzTabDataLower(String pnKeyLower) {
        if (pnKeyLower == null) {
            return;
        }
        String k = pnKeyLower.toLowerCase(Locale.ROOT);
        playerBlitzMetrics.remove(k);
        playerStats.remove(k);
        uncertainJoinTimeKillEffects.remove(k);
        removeTabSortMetricsLower(k);
        me.napim.gomod.features.KillCounter.markTabCompositionDirty();
    }

    /**
     * Same values as an empty HungerGames object: 0 wins, WL 0 (Hypixel omits the whole section until first Blitz game).
     */
    private String statsStringForNoHungerGames() {
        return calculateStats(new JsonObject());
    }

    /**
     * Hypixel {@code displayname} must align with the tab row we are coloring: normally it equals the tab
     * {@link net.minecraft.client.network.NetworkPlayerInfo} profile name. When gomod/TAB has denicked the row,
     * that profile name is still the Hypixel <i>nick</i> (e.g. {@code MiaHalles2008}) while the API profile we
     * fetched (via Mojang UUID from {@code statsLookupName}) reports the real IGN (e.g. {@code Jurce}) — accept
     * that match so wl/w, kill effect, and rank cache apply to the correct row.
     */
    private static boolean hypixelDisplaynameMatchesTabProfile(JsonObject playerData, String tabProfileName,
            String statsLookupName) {
        return hypixelDisplaynameMatchesTabProfile(playerData, tabProfileName, statsLookupName, false);
    }

    private static boolean hypixelDisplaynameMatchesTabProfile(JsonObject playerData, String tabProfileName,
            String statsLookupName, boolean trustedTabListUuid) {
        if (tabProfileName == null || tabProfileName.trim().isEmpty()) {
            return false;
        }
        if (playerData == null || !playerData.has("displayname") || playerData.get("displayname").isJsonNull()) {
            return true;
        }
        try {
            String dnRaw = playerData.get("displayname").getAsString();
            String dn = dnRaw == null ? "" : dnRaw.trim();
            if (dn.isEmpty()) {
                return true;
            }
            String tab = tabProfileName.trim();
            if (dn.equalsIgnoreCase(tab)) {
                return true;
            }
            if (statsLookupName != null) {
                String look = statsLookupName.trim();
                if (!look.isEmpty() && !look.equalsIgnoreCase(tab) && dn.equalsIgnoreCase(look)) {
                    return true;
                }
            }
            if (trustedTabListUuid && !dn.equalsIgnoreCase(tab)) {
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void maybeSyncFlameKillHudFromBlitzRow(String tabProfileName, JsonObject blitzStats, String resolvedEffect) {
        if (tabProfileName == null || blitzStats == null || resolvedEffect == null) {
            return;
        }
        if (!blitzStats.has("afterkill") || blitzStats.get("afterkill").isJsonNull()) {
            return;
        }
        me.napim.gomod.features.BlitzFlameKillEffectTracker.onHypixelAfterkillForProfile(tabProfileName, resolvedEffect);
    }

    public static String getPlayerEffect(String playerName) {
        if (playerName == null) {
            return null;
        }
        // All keys are stored lowercase — single O(1) lookup is sufficient.
        return playerKillEffects.get(playerName.toLowerCase(Locale.ROOT));
    }

    public static boolean isPlayerEffectUncertain(String playerName) {
        if (playerName == null) {
            return false;
        }
        return uncertainJoinTimeKillEffects.contains(playerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Keeps a pregame kill-effect result available when denicker links the TAB nick to its real Mojang IGN.
     * Without this, cage-time rendering switches its lookup key from nick to real name and the half-opacity icon
     * disappears until the post-cages API refresh completes.
     */
    public static void syncPlayerEffectAliases(String primaryName, String aliasName) {
        if (primaryName == null || aliasName == null) {
            return;
        }
        String primaryKey = primaryName.trim().toLowerCase(Locale.ROOT);
        String aliasKey = aliasName.trim().toLowerCase(Locale.ROOT);
        if (primaryKey.isEmpty() || aliasKey.isEmpty() || primaryKey.equals(aliasKey)) {
            return;
        }

        String primaryEffect = playerKillEffects.get(primaryKey);
        String aliasEffect = playerKillEffects.get(aliasKey);
        String effect = primaryEffect != null ? primaryEffect : aliasEffect;
        if (effect == null || effect.isEmpty()) {
            return;
        }

        boolean uncertain = primaryEffect != null
                ? uncertainJoinTimeKillEffects.contains(primaryKey)
                : uncertainJoinTimeKillEffects.contains(aliasKey);
        boolean changed = !effect.equals(playerKillEffects.put(primaryKey, effect));
        changed |= !effect.equals(playerKillEffects.put(aliasKey, effect));
        if (uncertain) {
            changed |= uncertainJoinTimeKillEffects.add(primaryKey);
            changed |= uncertainJoinTimeKillEffects.add(aliasKey);
        } else {
            changed |= uncertainJoinTimeKillEffects.remove(primaryKey);
            changed |= uncertainJoinTimeKillEffects.remove(aliasKey);
        }
        if (changed) {
            me.napim.gomod.features.KillCounter.markTabCompositionDirty();
        }
    }
    
    public static boolean hasPlayerEffect(String playerName) {
        if (playerName == null) {
            return false;
        }
        // All keys are stored lowercase — single O(1) lookup is sufficient.
        return playerKillEffects.containsKey(playerName.toLowerCase(Locale.ROOT));
    }

    /** True only when this exact resolved IGN has received Blitz stats (aliases do not count). */
    public static boolean hasPlayerStats(String playerName) {
        if (playerName == null) {
            return false;
        }
        return playerBlitzMetrics.containsKey(playerName.trim().toLowerCase(Locale.ROOT));
    }
    
    /**
     * Clears only kill effect colors (not stats or guild tags).
     * Stats and guild tags should persist across games.
     * Should be called when starting a new game to reset kill effect colors.
     */
    public static void clearAllKillEffects() {
        playerKillEffects.clear();
        uncertainJoinTimeKillEffects.clear();
        // DON'T clear playerStats - stats should persist across games
        // System.out.println("[KillEffectCommand] Cleared kill effects (stats and guild tags preserved)");
    }

    /**
     * In-match kill effect from reliable Hypixel chat (e.g. {@code You gained Speed for killing a player!}); updates
     * the same {@link #playerKillEffects} map as tabstats so TAB KE coloring matches the sidebar / HUD.
     */
    public static void applySelfKillEffectFromLiveGameChat(String effectCodeUpper) {
        if (effectCodeUpper == null || effectCodeUpper.trim().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        String ign = mc.thePlayer.getName();
        if (ign == null || ign.trim().isEmpty()) {
            return;
        }
        String statsLookup = me.napim.gomod.features.KillCounter.resolveTabLookupName(ign);
        if (statsLookup == null || statsLookup.trim().isEmpty()) {
            statsLookup = ign;
        }
        String uc = effectCodeUpper.trim().toUpperCase(Locale.ROOT);
        if ("RAPID_FIRE".equals(uc)) {
            uc = "FLAME";
        }
        String statsKeyLower = statsLookup.trim().toLowerCase(Locale.ROOT);
        String ignLower = ign.trim().toLowerCase(Locale.ROOT);
        playerKillEffects.put(statsKeyLower, uc);
        uncertainJoinTimeKillEffects.remove(statsKeyLower);
        if (!statsKeyLower.equals(ignLower)) {
            playerKillEffects.put(ignLower, uc);
            uncertainJoinTimeKillEffects.remove(ignLower);
        }
        me.napim.gomod.features.KillCounter.markTabCompositionDirty();
    }

    private static boolean isLocalPlayerLinkedStatsKey(String pnKeyLower) {
        if (pnKeyLower == null || pnKeyLower.isEmpty()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        String ign = mc.thePlayer.getName();
        if (ign == null || ign.trim().isEmpty()) {
            return false;
        }
        String lookup = me.napim.gomod.features.KillCounter.resolveTabLookupName(ign);
        if (lookup == null || lookup.trim().isEmpty()) {
            lookup = ign;
        }
        String lk = lookup.trim().toLowerCase(Locale.ROOT);
        String ig = ign.trim().toLowerCase(Locale.ROOT);
        return pnKeyLower.equalsIgnoreCase(lk) || pnKeyLower.equalsIgnoreCase(ig);
    }

    /**
     * Skip API/tabstats kill-effect writes for the local client while {@link me.napim.gomod.features.BlitzFlameKillEffectTracker}
     * has fresher in-game chat (e.g. {@code You gained Regeneration for killing a player!}).
     */
    private static boolean shouldSkipApiKillEffectPutForLocalPlayer(String pnKeyLower) {
        return isLocalPlayerLinkedStatsKey(pnKeyLower)
                && me.napim.gomod.features.BlitzFlameKillEffectTracker.shouldDeferHypixelApiKillEffectForLocalPlayer();
    }

    private static void putPlayerKillEffectUnlessLiveChatDefers(String pnKeyLower, String effect) {
        putPlayerKillEffectUnlessLiveChatDefers(pnKeyLower, effect, false);
    }

    private static void putPlayerKillEffectUnlessLiveChatDefers(String pnKeyLower, String effect, boolean uncertainUntilPostCagesRefresh) {
        if (shouldSkipApiKillEffectPutForLocalPlayer(pnKeyLower)) {
            return;
        }
        if (pnKeyLower == null) {
            return;
        }
        String k = pnKeyLower.toLowerCase(Locale.ROOT);
        playerKillEffects.put(k, effect);
        boolean changed;
        if (uncertainUntilPostCagesRefresh) {
            changed = uncertainJoinTimeKillEffects.add(k);
        } else {
            changed = uncertainJoinTimeKillEffects.remove(k);
        }
        if (changed) {
            me.napim.gomod.features.KillCounter.markTabCompositionDirty();
        }
    }

    /**
     * Clears cached WL/stats strings, kill-effect colors, and tabstats scan throttles — used by
     * {@link me.napim.gomod.features.KillCounter#resetTabStatsOverlayNow()} so TAB returns to vanilla until rescanned.
     */
    public static void clearAllTabStatsVisualCaches() {
        playerStats.clear();
        playerBlitzMetrics.clear();
        statsObservedAtMs.clear();
        playerKillEffects.clear();
        uncertainJoinTimeKillEffects.clear();
        tabSortMetricsByLowerName.clear();
        cooldownUntilMs.clear();
        pendingUntilMs.clear();
        pendingCagesOpenKillEffectRefresh.clear();
        HypixelTabStatsRankCache.clear();
        me.napim.gomod.features.BlitzFlameKillEffectTracker.onTabstatsCachesCleared();
    }

    // Expose stats suffix for KillCounter composition
    /** Immutable cached payload; display consumers must not start network work while rendering. */
    public static TabStatsBlitzMetrics getCachedBlitzMetrics(String playerName) {
        return playerName == null ? null : playerBlitzMetrics.get(playerName.trim().toLowerCase(Locale.ROOT));
    }

    public static String getStatsSuffixFor(String playerName) {
        if (playerName == null || !me.napim.gomod.features.KillCounter.isStatsDisplay()) {
            return "";
        }
        String key = playerName.toLowerCase(Locale.ROOT);
        TabStatsBlitzMetrics metrics = playerBlitzMetrics.get(key);
        if (metrics == null) {
            return "";
        }
        return metrics.formatSuffix(
                me.napim.gomod.features.KillCounter.isStatsShowKd(),
                me.napim.gomod.features.KillCounter.isStatsShowWl(),
                me.napim.gomod.features.KillCounter.isStatsShowW());
    }

    private static void removeTabSortMetricsLower(String pnKeyLower) {
        if (pnKeyLower != null) {
            tabSortMetricsByLowerName.remove(pnKeyLower.toLowerCase(Locale.ROOT));
        }
    }

    private static void putTabSortMetricsFromBlitzLower(String pnKeyLower, JsonObject blitzStats) {
        if (pnKeyLower == null) {
            return;
        }
        String k = pnKeyLower.toLowerCase(Locale.ROOT);
        if (blitzStats == null) {
            tabSortMetricsByLowerName.remove(k);
            return;
        }
        try {
            double[] m = computeTabSortMetrics(blitzStats);
            tabSortMetricsByLowerName.put(k, m);
        } catch (Exception e) {
            tabSortMetricsByLowerName.remove(k);
        }
    }

    /** @return {@code [0]=wl ratio, [1]=total wins, [2]=overall Blitz kills as doubles for storage} */
    private static double[] computeTabSortMetrics(JsonObject blitzStats) {
        int soloWins = blitzStats.has("wins") ? blitzStats.get("wins").getAsInt() : 0;
        int teamWins = blitzStats.has("wins_teams_normal") ? blitzStats.get("wins_teams_normal").getAsInt() : 0;
        int totalWins = soloWins + teamWins;
        int overallKills = blitzStats.has("kills") ? blitzStats.get("kills").getAsInt() : 0;
        int deaths = blitzStats.has("deaths") ? blitzStats.get("deaths").getAsInt() : 1;
        double wl = deaths == 0 ? totalWins : (double) totalWins / (double) deaths;
        return new double[]{wl, totalWins, overallKills};
    }

    private static double[] lookupTabSortMetrics(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        String k = playerName.toLowerCase(Locale.ROOT);
        double[] m = tabSortMetricsByLowerName.get(k);
        if (m != null) {
            return m;
        }
        for (Map.Entry<String, double[]> e : tabSortMetricsByLowerName.entrySet()) {
            if (e.getKey().equalsIgnoreCase(playerName)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** W/L ratio from last tabstats Blitz payload; {@link Double#NaN} if unknown. */
    public static double getCachedTabSortWl(String playerName) {
        double[] m = lookupTabSortMetrics(playerName);
        return m == null ? Double.NaN : m[0];
    }

    /** Total Blitz wins from last tabstats payload; {@code 0} if unknown. */
    public static int getCachedTabSortWins(String playerName) {
        double[] m = lookupTabSortMetrics(playerName);
        return m == null ? 0 : (int) Math.round(m[1]);
    }

    /** Overall Blitz kills from the last tabstats payload; {@code 0} if unknown. */
    public static int getCachedTabSortOverallKills(String playerName) {
        double[] m = lookupTabSortMetrics(playerName);
        return m == null || m.length < 3 ? 0 : (int) Math.round(m[2]);
    }

    /** Distinguishes a real zero-stat profile from a nick/failed lookup with no stats payload. */
    public static boolean hasCachedTabSortMetrics(String playerName) {
        return lookupTabSortMetrics(playerName) != null;
    }

    private List<NetworkPlayerInfo> getPlayersInTabOrder() {
        if (Minecraft.getMinecraft().getNetHandler() == null) return new ArrayList<>();
        return new ArrayList<>(Minecraft.getMinecraft().getNetHandler().getPlayerInfoMap());
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("KillEffectCommand")) return;
        long _profT = FeatureProfiler.begin("KillEffectCommand");
        try {
        if (Minecraft.getMinecraft().theWorld == null) return;
        // Debounced + periodic tab compose inside KillCounter (was forceTabRefresh every 1.5s → heavy + duplicated packet work).
        me.napim.gomod.features.KillCounter.onClientTickForTabComposition();
        TabStatsTrafficDebug.flushPacketBatchIfDue();
        } finally { FeatureProfiler.end("KillEffectCommand", _profT); }
    }

    private void fetchPlayerKillEffect(String playerName) {
        fetchPlayerKillEffect(playerName, null, false);
    }

    private void fetchPlayerKillEffect(String tabOrCommandName, String tabUuidNoDashSnapshot) {
        fetchPlayerKillEffect(tabOrCommandName, tabUuidNoDashSnapshot, false);
    }

    private void fetchPlayerKillEffect(String tabOrCommandName, String tabUuidNoDashSnapshot, boolean notifyIfKnownBad) {
        fetchPlayerKillEffect(tabOrCommandName, tabUuidNoDashSnapshot, notifyIfKnownBad, false);
    }

    private void fetchPlayerKillEffect(String tabOrCommandName, String tabUuidNoDashSnapshot, boolean notifyIfKnownBad,
                                       boolean uncertainKillEffectUntilRefresh) {
        fetchPlayerKillEffect(tabOrCommandName, tabUuidNoDashSnapshot, notifyIfKnownBad,
                uncertainKillEffectUntilRefresh, false);
    }

    private void fetchPlayerKillEffect(String tabOrCommandName, String tabUuidNoDashSnapshot, boolean notifyIfKnownBad,
                                       boolean uncertainKillEffectUntilRefresh,
                                       boolean automaticTabStatsContributionGate) {
        new Thread(() -> {
            try {
                if (getUsableHypixelApiKey(notifyIfKnownBad) == null) {
                    return;
                }
                String tab = tabOrCommandName == null ? "" : tabOrCommandName.trim();
                boolean usedTabListUuid = tabUuidNoDashSnapshot != null && !tabUuidNoDashSnapshot.trim().isEmpty();
                UUID profileUuid = usedTabListUuid ? uuidFromNoDash(tabUuidNoDashSnapshot) : null;
                String statsLookup = usedTabListUuid && profileUuid != null
                        ? resolveStatsLookupForTabRow(tab, profileUuid)
                        : me.napim.gomod.features.KillCounter.resolveTabLookupName(tab);
                if (statsLookup == null || statsLookup.trim().isEmpty()) {
                    statsLookup = tab;
                } else {
                    statsLookup = statsLookup.trim();
                }
                String statsKeyLower = statsLookup.toLowerCase(Locale.ROOT);
                String tabKeyLower = tab.toLowerCase(Locale.ROOT);

                if (me.napim.gomod.features.KillCounter.isShowKeAllChat()) {
                    sendMessage(EnumChatFormatting.GRAY + "Fetching data for " + tab + "...");
                }

                String uuid;
                String uuidKey;
                final int requestedGeneration = TABSTATS_SCAN_GENERATION.get();
                final boolean requireFreshEffect = isKillEffectDisplayEnabled() && !uncertainKillEffectUntilRefresh && !isPregameNow();
                if (usedTabListUuid) {
                    me.napim.gomod.features.KillCounter.fetchGuildTagWithKnownUuid(statsLookup, tabUuidNoDashSnapshot);
                    PlayerTabScanRow row = new PlayerTabScanRow(tab, statsLookup, tabUuidNoDashSnapshot);
                    uuid = resolveHypixelUuidNoDashForTabstatsRow(row);
                    if (uuid == null || uuid.isEmpty()) {
                        if (notifyIfKnownBad) {
                            sendMessage(EnumChatFormatting.RED + "Player not found: " + statsLookup);
                        } else {
                            markTabstatsLookupUnavailable(tab, statsLookup, statsKeyLower, tabKeyLower);
                        }
                        return;
                    }
                } else {
                    me.napim.gomod.features.KillCounter.fetchGuildTag(statsLookup);
                    String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + statsLookup;
                    JsonObject mojangResponse = makeRequest(uuidUrl, !notifyIfKnownBad);

                    if (mojangResponse == null || !mojangResponse.has("id")) {
                        if (notifyIfKnownBad) {
                            sendMessage(EnumChatFormatting.RED + "Player not found: " + statsLookup);
                        } else {
                            markTabstatsLookupUnavailable(tab, statsLookup, statsKeyLower, tabKeyLower);
                        }
                        return;
                    }

                    uuid = mojangResponse.get("id").getAsString();
                }
                uuidKey = uuid.replace("-", "").toLowerCase(Locale.ROOT);
                long nowMs = System.currentTimeMillis();
                Long uuidCool = hypixelPlayerUuidCooldownUntilMs.get(uuidKey);
                if (uuidCool != null && uuidCool > nowMs && requireFreshEffect) {
                    return;
                }
                Long inflight = hypixelPlayerUuidFetchActiveUntilMs.putIfAbsent(uuidKey, nowMs + HYPIXEL_UUID_IN_FLIGHT_GUARD_MS);
                if (inflight != null && inflight > nowMs) {
                    return;
                }
                try {
                    // Then get player data from Hypixel API (retry on HTTP 429 like bulk tabstats)
                    JsonObject hypixelResponse = null;
                    String pnLower = statsKeyLower;
                    for (int attempt = 1; attempt <= HYPIXEL_TABSTATS_MAX_429_RETRIES; attempt++) {
                        String apiKey = getUsableHypixelApiKey(notifyIfKnownBad);
                        if (apiKey == null) {
                            return;
                        }
                        String hypixelUrl = "https://api.hypixel.net/player?key=" + apiKey + "&uuid=" + uuid.replace("-", "");
                        try {
                            awaitGlobalTabstatsRequestSlot();
                            hypixelResponse = me.napim.gomod.features.HypixelApiHelper.fetchBlitzJson(
                                    hypixelUrl, requireFreshEffect, automaticTabStatsContributionGate);
                            me.napim.gomod.features.GameModeStatusFetcher.noteHypixelApiSuccess(apiKey);
                            break;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Exception ex) {
                            if (!isHypixelHttp429(ex)) {
                                String em = ex.getMessage();
                                if (isHypixelUuidRateLimitMessage(em)) {
                                    hypixelPlayerUuidCooldownUntilMs.put(uuidKey, System.currentTimeMillis() + HYPIXEL_UUID_RATE_LIMIT_COOLDOWN_MS);
                                }
                                if (me.napim.gomod.features.GameModeStatusFetcher
                                        .handleHypixelInvalidKeyFailure(apiKey, em, true)) {
                                    return;
                                }
                                if (!notifyIfKnownBad && isAutomaticTabstatsFreshnessDeferred(ex)) {
                                    long retryAt = System.currentTimeMillis() + HYPIXEL_UUID_RATE_LIMIT_COOLDOWN_MS;
                                    cooldownUntilMs.put(pnLower, retryAt);
                                    hypixelPlayerUuidCooldownUntilMs.put(uuidKey, retryAt);
                                }
                                throw ex;
                            }
                            if (ex instanceof me.napim.gomod.features.HypixelApiHelper.HttpFailure) {
                                cooldownUntilMs.put(pnLower, System.currentTimeMillis() + 60_000L);
                                if (requireFreshEffect) pendingCagesOpenKillEffectRefresh.add(statsKeyLower);
                                if (notifyIfKnownBad) sendMessage(EnumChatFormatting.GRAY
                                        + "Waiting for fresh player data; an older kill-effect selection was not used.");
                                return;
                            }
                            if (attempt >= HYPIXEL_TABSTATS_MAX_429_RETRIES) {
                                long t = System.currentTimeMillis();
                                cooldownUntilMs.put(pnLower, t + 60_000L);
                                hypixelPlayerUuidCooldownUntilMs.put(uuidKey, t + HYPIXEL_UUID_RATE_LIMIT_COOLDOWN_MS);
                                sendMessage(EnumChatFormatting.RED + "Hypixel HTTP 429 — still throttled after "
                                        + HYPIXEL_TABSTATS_MAX_429_RETRIES + " retries. Try again in ~60s.");
                                return;
                            }
                            long backoffMs = Math.min(15_000L, 1_000L * attempt);
                            try {
                                Thread.sleep(backoffMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }

                    if (hypixelResponse != null && hypixelResponse.has("success") && hypixelResponse.get("success").getAsBoolean()) {
                        JsonElement playerEl = hypixelResponse.get("player");
                        if (playerEl == null || playerEl.isJsonNull() || !playerEl.isJsonObject()) {
                            if (notifyIfKnownBad) {
                                sendMessage(EnumChatFormatting.RED + "Failed to get player data!");
                            } else {
                                markTabstatsLookupUnavailable(tab, statsLookup, statsKeyLower, tabKeyLower);
                                cooldownUntilMs.put(pnLower, System.currentTimeMillis() + 60_000L);
                            }
                            return;
                        }
                        JsonObject player = playerEl.getAsJsonObject();
                        if (!notifyIfKnownBad && requestedGeneration != TABSTATS_SCAN_GENERATION.get()) return;
                        if (notifyIfKnownBad && me.napim.gomod.features.HypixelPlayerRequests.isCached(player))
                            sendMessage(EnumChatFormatting.GRAY + me.napim.gomod.features.HypixelPlayerRequests.cacheLabel(player));
                        if (requireFreshEffect) {
                            pendingCagesOpenKillEffectRefresh.remove(statsKeyLower);
                            pendingCagesOpenKillEffectRefresh.remove(tabKeyLower);
                        }
                        if (!hypixelDisplaynameMatchesTabProfile(player, tab, statsLookup, usedTabListUuid)) {
                            hypixelRankRemoveAliases(statsKeyLower, tabKeyLower);
                            String dn = player.has("displayname") && !player.get("displayname").isJsonNull()
                                    ? player.get("displayname").getAsString() : "?";
                            putPlayerKillEffectUnlessLiveChatDefers(statsKeyLower, "none", false);
                            clearBlitzTabDataLower(statsKeyLower);
                            cooldownUntilMs.put(statsKeyLower, System.currentTimeMillis() + 60_000L);
                            updatePlayerInTabForStats(tab, statsLookup, "none");
                            if (me.napim.gomod.features.KillCounter.isShowKeAllChat()) {
                                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Hypixel profile is "
                                        + EnumChatFormatting.WHITE + dn + EnumChatFormatting.GRAY
                                        + " but tab/command name " + EnumChatFormatting.WHITE + tab
                                        + EnumChatFormatting.GRAY + " — no TAB wl/w (nick / wrong account).");
                            }
                            return;
                        }

                        hypixelRankIngestAliases(statsKeyLower, tabKeyLower, player);

                        if (player.has("stats") && player.getAsJsonObject("stats").has("HungerGames")) {
                            JsonObject blitzStats = player.getAsJsonObject("stats").getAsJsonObject("HungerGames");
                            String effect = getKillEffect(blitzStats);
                            putPlayerKillEffectUnlessLiveChatDefers(statsKeyLower, effect, uncertainKillEffectUntilRefresh);
                            putBlitzTabDataLower(statsKeyLower, blitzStats);
                            String stats = playerStats.get(statsKeyLower);
                            if (!shouldSkipApiKillEffectPutForLocalPlayer(statsKeyLower)) {
                                maybeSyncFlameKillHudFromBlitzRow(tab, blitzStats, effect);
                            }

                            long now = System.currentTimeMillis();
                            cooldownUntilMs.put(pnLower, now + 60_000L);
                            hypixelPlayerUuidCooldownUntilMs.put(uuidKey, now + HYPIXEL_UUID_POST_SUCCESS_COOLDOWN_MS);

                            updatePlayerInTabForStats(tab, statsLookup, effect);

                            if (me.napim.gomod.features.KillCounter.isShowKeAllChat()) {
                                sendMessage(EnumChatFormatting.GREEN + "Kill effect for " + statsLookup + ": "
                                        + getKillEffectColor(effect) + formatKillEffectName(effect));
                                sendMessage(EnumChatFormatting.YELLOW + "Stats: " + EnumChatFormatting.AQUA + stats);
                            }
                        } else {
                            putPlayerKillEffectUnlessLiveChatDefers(statsKeyLower, DEFAULT_BLITZ_KILL_EFFECT, uncertainKillEffectUntilRefresh);
                            putBlitzTabDataLower(statsKeyLower, new JsonObject());
                            long now = System.currentTimeMillis();
                            cooldownUntilMs.put(pnLower, now + 60_000L);
                            hypixelPlayerUuidCooldownUntilMs.put(uuidKey, now + HYPIXEL_UUID_POST_SUCCESS_COOLDOWN_MS);
                            updatePlayerInTabForStats(tab, statsLookup, DEFAULT_BLITZ_KILL_EFFECT);
                            if (me.napim.gomod.features.KillCounter.isShowKeAllChat()) {
                                sendMessage(EnumChatFormatting.GRAY + "Player " + statsLookup
                                        + " has no HungerGames data (never played Blitz). TAB stats: "
                                        + EnumChatFormatting.WHITE + "0"
                                        + EnumChatFormatting.GRAY + " / default kill effect "
                                        + getKillEffectColor(DEFAULT_BLITZ_KILL_EFFECT) + formatKillEffectName(DEFAULT_BLITZ_KILL_EFFECT)
                                        + EnumChatFormatting.GRAY + ".");
                            }
                        }
                    } else {
                        hypixelRankRemoveAliases(statsKeyLower, tabKeyLower);
                        String errorMsg = (hypixelResponse != null && hypixelResponse.has("cause"))
                                ? hypixelResponse.get("cause").getAsString() : "Failed to get player data!";
                        if (me.napim.gomod.features.GameModeStatusFetcher
                                .handleHypixelInvalidKeyFailure(me.napim.gomod.features.ApiKeyManager.getApiKey("hypixel"),
                                        errorMsg, true)) {
                            return;
                        }
                        if (isHypixelUuidRateLimitMessage(errorMsg)) {
                            hypixelPlayerUuidCooldownUntilMs.put(uuidKey, System.currentTimeMillis() + HYPIXEL_UUID_RATE_LIMIT_COOLDOWN_MS);
                        }
                        if (notifyIfKnownBad || !isSilentNickLookupFailure(errorMsg)) {
                            sendMessage(EnumChatFormatting.RED + errorMsg);
                        } else {
                            markTabstatsLookupUnavailable(tab, statsLookup, statsKeyLower, tabKeyLower);
                        }
                    }
                } finally {
                    hypixelPlayerUuidFetchActiveUntilMs.remove(uuidKey);
                }
            } catch (Exception e) {
                if (notifyIfKnownBad || !noteAutomaticTabstatsFailure(e)) {
                    sendMessage(EnumChatFormatting.RED + "Error: " + e.getMessage());
                }
            }
        }).start();
    }

    private static UUID uuidFromNoDash(String undashed) {
        if (undashed == null) {
            return null;
        }
        String s = undashed.replace("-", "").trim();
        if (s.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20, 32));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void updatePlayerInTab(String playerName, String effect) {
        updatePlayerInTabForStats(playerName, playerName, effect);
    }
}
