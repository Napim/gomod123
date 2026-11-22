package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.PacketEvent.Receive;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.client.network.NetworkPlayerInfo;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import me.napim.gomod.mixins.GuiNewChatAccessor;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Score;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.Main;
import org.lwjgl.input.Keyboard;

import java.util.*;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TAB QoL feature that tracks and displays player kills during a game.
 * Shows a GUI in the top-left corner with kill counts for each player.
 * 
 * Features:
 * - Kill tracking and display in tab list
 * - Teammate kit detection from chat messages
 * - Enemy kit detection from gear items (5 seconds after players receive kits)
 * - Guild tag display
 * - Kill effect colors
 * - Stats display (WL/Wins)
 * 
 * Enemy Kit Detection:
 * - Activates 5 seconds after the Blitz Star ~2-minute release chat line (loose match; see isBlitzStarTwoMinuteReleaseMessage)
 * - Scans player gear for 1 second only to avoid false positives from looted items
 * - Scans player gear for kit items matching pattern: "KitName's Item (Level)"
 * - Detects all Blitz Survival Games kits with levels I-X or \u272B/\u272B\u272B
 * - Displays detected enemy kits in yellow parentheses in tab list
 */
public class KillCounter {
    private static final java.util.concurrent.atomic.AtomicInteger GUILD_TAG_THREAD_ID =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private static final java.util.concurrent.ExecutorService GUILD_TAG_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "gomod-guild-tag-fetch-" + GUILD_TAG_THREAD_ID.getAndIncrement());
                t.setDaemon(true);
                return t;
            });

    private static void startDaemonThread(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    public enum KillEffectTabDisplayMode {
        OFF,
        COLORED_NAME_TAB,
        COLORED_NAME_TAGS,
        /** Legacy value: color both TAB names and nametags. */
        COLORED_NAME,
        ICON;

        public static KillEffectTabDisplayMode fromConfig(String s) {
            if (s == null) {
                return COLORED_NAME;
            }
            switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "COLORED_NAME_TAB":
                    return COLORED_NAME_TAB;
                case "COLORED_NAME_TAGS":
                    return COLORED_NAME_TAGS;
                case "ICON":
                    return ICON;
                case "OFF":
                case "DISABLED":
                case "NONE":
                    return OFF;
                default:
                    return COLORED_NAME;
            }
        }

        public KillEffectTabDisplayMode next() {
            switch (this) {
                case OFF:
                    return COLORED_NAME_TAB;
                case COLORED_NAME_TAB:
                    return COLORED_NAME_TAGS;
                case COLORED_NAME_TAGS:
                    return COLORED_NAME;
                case COLORED_NAME:
                    return ICON;
                default:
                    return OFF;
            }
        }

        public String toConfigValue() {
            switch (this) {
                case OFF:
                    return "OFF";
                case ICON:
                    return "ICON";
                case COLORED_NAME_TAB:
                    return "COLORED_NAME_TAB";
                case COLORED_NAME_TAGS:
                    return "COLORED_NAME_TAGS";
                default:
                    return "COLORED_NAME";
            }
        }

        public boolean colorsTab() {
            return this == COLORED_NAME_TAB || this == COLORED_NAME;
        }

        public boolean colorsNametags() {
            return this == COLORED_NAME_TAGS || this == COLORED_NAME;
        }
    }

    public enum TabKillCounterDisplayMode {
        AFTER_NAME,
        BEFORE_NAME;

        public static TabKillCounterDisplayMode fromConfig(String raw) {
            if (raw == null) {
                return AFTER_NAME;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if ("BEFORE_NAME".equals(normalized) || "BEFORE".equals(normalized) || "LEFT_OF_HEAD".equals(normalized)) {
                return BEFORE_NAME;
            }
            return AFTER_NAME;
        }

        public String toConfigValue() {
            return name();
        }
    }

    private static boolean enabled = true; // Default to enabled
    private static boolean statsDisplay = true; // Default to showing stats display
    private static TabKillCounterDisplayMode tabKillCounterDisplayMode = TabKillCounterDisplayMode.AFTER_NAME;
    private static KillEffectTabDisplayMode killEffectTabMode = KillEffectTabDisplayMode.COLORED_NAME;
    private static boolean statsShowKd = false;
    private static boolean statsShowWl = true;
    private static boolean statsShowW = true;
    private static final Map<String, Integer> killCounts = new ConcurrentHashMap<>();
    private static final Map<String, String> playerColors = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> playerVictims = new ConcurrentHashMap<>();
    private static final int TAB_VICTIMS_DEBUG_EVENT_LIMIT = 80;
    private static final java.util.ArrayDeque<String> tabVictimsDebugEvents = new java.util.ArrayDeque<String>();
    private static final Object pendingKillFeedLock = new Object();
    private static final java.util.ArrayDeque<PendingKillFeedLine> pendingKillFeedLines =
            new java.util.ArrayDeque<PendingKillFeedLine>();
    private static final int PENDING_KILL_FEED_LIMIT = 32;
    private static final long PENDING_KILL_FEED_TTL_MS = 30_000L;
    private static final long PENDING_KILL_FEED_DUPLICATE_WINDOW_MS = 2_000L;
    private static volatile KillCounter chatObserverInstance;
    /** Replay-only victim alias (lowercase) -> killer. Kept separate so replay chat never changes live kill stats. */
    private static final Map<String, String> replayKillerByVictim = new ConcurrentHashMap<>();
    private static long lastReplayChatHistoryScanMs;
    private static final Map<String, Integer> tabKillersIndentDepthByLower = new ConcurrentHashMap<>();
    /** Rendered TAB child identity -> the row it is nested under, used to mark reciprocal kill edges. */
    private static final Map<String, String> tabKillersParentIdentityByChildIdentity = new ConcurrentHashMap<>();
    /** Cached identities that participate in reciprocal kill edges, refreshed once per grouping pass. */
    private static final Set<String> tabKillersMutualKillIdentitySet = ConcurrentHashMap.newKeySet();
    private static final String TAB_KILLERS_MUTUAL_KILL_MARKER = "↔";
    /** Lower lookup (real/nick) → star display name from chat (e.g. "Vault Hunter") for TAB icon. */
    private static final Map<String, String> playerBlitzStarDisplayByLookupLower = new ConcurrentHashMap<>();
    /** Best-known skin state for rows that later disappear from live TAB so synthetic dead rows can keep the real head. */
    private static final Map<String, CachedTabAppearance> cachedTabAppearanceByLookupLower = new ConcurrentHashMap<>();
    /** Slugs matching {@code assets/gomod123/textures/blitzstars/star_<slug>.png}. */
    private static final Set<String> KNOWN_BLITZ_STAR_TEXTURE_SLUGS = new HashSet<>(Arrays.asList(
            "acid_rain", "apocalypse", "assassin", "gladiator", "gremlin", "imprison", "infection", "invoker",
            "jedi_knight", "koolmove", "lockdown", "ninja", "nocturne", "nuke", "pickpocket", "ragnarok", "robinhood",
            "roulette", "shotgun", "supplies", "switcheroo", "time_warp", "vampire", "vaulthunter", "witherwarrior",
            "wobbuffet", "zookeeper"));
    private static final Map<String, String> playerKits = new ConcurrentHashMap<>(); // Store player kits
    private static final Map<String, String> enemyPlayerKits = new ConcurrentHashMap<>(); // Store enemy player kits detected from gear
    private static long lastRefresh = 0L;
    private static final Set<String> deadPlayers = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Long> deathOrderByPlayer = new ConcurrentHashMap<>();
    private static final AtomicLong deathOrderCounter = new AtomicLong(0L);
    private static String lastWorldName = null; // Track world changes
    private static final Map<String, String> playerGuildTags = new ConcurrentHashMap<>(); // Cache guild tags
    private static final Map<String, String> playerGuildTagColors = new ConcurrentHashMap<>(); // Cache guild tag colors
    /** Prevents overlapping {@link #fetchGuildTag} threads for the same player (e.g. full scan + missing pass). */
    private static final java.util.Set<String> guildTagFetchInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static long cagesOpenTime = 0L; // Track when cages opened for kit detection timing
    private static long lastJoinScanMs = 0L;
    private static final java.util.Set<String> lastTabPlayerUuids =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final AtomicInteger playersJoinPostCagesRefreshGeneration = new AtomicInteger(0);
    private static final long PLAYERS_JOIN_POST_CAGES_REFRESH_WINDOW_MS = 100_000L;
    private static final long PLAYERS_JOIN_POST_CAGES_REFRESH_POLL_MS = 1_500L;

    public enum AutoScanMode {
        OFF("OFF"),
        CAGES_OPEN("CAGES_OPEN"),
        PLAYERS_JOIN("PLAYERS_JOIN");

        private final String configValue;
        AutoScanMode(String configValue) { this.configValue = configValue; }
        public String getConfigValue() { return configValue; }
        public static AutoScanMode fromConfig(String value) {
            if (value == null) return OFF;
            String v = value.trim().toUpperCase(java.util.Locale.ROOT);
            if ("CAGES_OPEN".equals(v)) return CAGES_OPEN;
            if ("PLAYERS_JOIN".equals(v)) return PLAYERS_JOIN;
            return OFF;
        }
        public AutoScanMode next() {
            switch (this) {
                case OFF: return CAGES_OPEN;
                case CAGES_OPEN: return PLAYERS_JOIN;
                default: return OFF;
            }
        }
    }
    /** Written from worker threads; read from client tick — must be volatile for visibility. */
    private static volatile boolean kitDetectionActive = false; // Track if we should be detecting kits from gear
    private static long lastGameEndCheck = 0L; // Track when we last checked for game end
    private static final long GAME_END_CHECK_INTERVAL = 3500L;
    /** Full TAB recomposition is expensive; keep independent from {@link #GAME_END_CHECK_INTERVAL} to reduce periodic hitches. */
    private static final long TAB_PERIODIC_COMPOSE_INTERVAL_MS = 9000L;
    private static boolean gameHasEnded = false; // Flag to track if game has actually ended
    private static boolean blitzHourActive = false; // Track if BLITZ HOUR is active from scoreboard
    private static long blitzHourDetectionTime = 0L; // Track when BLITZ HOUR was detected
    private static final long BLITZ_HOUR_TIMEOUT = 30000L; // 30 seconds timeout for BLITZ HOUR detection
    private static boolean sawTaunt = false; // Track if we've seen "Taunt" in scoreboard this game
    
    // Rate limiting for API calls
    private static long lastApiCall = 0;
    private static final long API_CALL_DELAY = 1000; // 1 second between calls

    /**
     * Tab list composition is expensive; never call {@link #updateAllTabNames()} from API/background threads.
     * Coalesce refreshes on the client tick instead (see {@link #onClientTickForTabComposition()}).
     */
    private static volatile boolean tabCompositionDirty = false;
    private static long lastTabCompositionApplyMs = 0L;
    private static long lastPeriodicTabComposeMs = 0L;
    private static final long TAB_COMPOSITION_DEBOUNCE_MS = 2200L;
    private static boolean wasTabKillersToggleKeyDown = false;
    /** Tracks entry/exit so Blitz-only TAB state cannot leak into another Hypixel game mode. */
    private static net.minecraft.world.World lastTabQolContextWorld;
    private static boolean lastTabQolContextActive;

    /**
     * Last Hypixel/server-formatted tab prefix per player (rank colors, etc.). Vanilla often sends richer
     * formatting via {@code NetworkPlayerInfo.displayName} than {@link ScorePlayerTeam#formatPlayerName};
     * we must not rebuild from team alone or VIP+ can appear as plain white once we add stats.
     */
    private static final Map<String, String> tabDisplayFormatCache = new ConcurrentHashMap<>();
    /**
     * Last composed input fingerprint per TAB row. When unchanged, we skip recomputing that row
     * so Hypixel's frequent {@code S3EPacketTeams} churn does not re-run the full string pipeline every debounced tick.
     */
    private static final Map<String, Long> tabRowComposeFingerprintByRowKey = new ConcurrentHashMap<>();
    /** Weave/Lunar compatibility: render TAB edits without mutating NetworkPlayerInfo displayName. */
    private static final Map<String, String> tabRenderedLineByRowKey = new ConcurrentHashMap<>();
    /** Clean composed line before Lunar-only alignment padding is added. Never read padding input from the output cache. */
    private static final Map<String, String> tabUnpaddedLineByRowKey = new ConcurrentHashMap<>();
    /** Exact formatted stats chunk for render-time right alignment (no space-padding in display names). */
    private static final Map<String, String> tabStatsSegmentByRowKey = new ConcurrentHashMap<>();
    /** When Blitz pregame / active-game phase flips, Hypixel re-sends TAB team colors — drop cached lines so we don't keep the previous match's blue/green/red. */
    private static Boolean lastBlitzPregameTabPhase = null;
    private static Boolean lastBlitzGameActiveTabPhase = null;
    /** Hub Blitz queue (lobby sidebar) vs in-match cage pre-start: same pregame/active flags but TAB colors differ — clear cache on transition. */
    private static Boolean lastBlitzHubQueueTabPhase = null;
    /** BLITZ SG + Cages open fingerprint toggles — clear TAB format cache when entering/leaving cage phase. */
    private static Boolean lastBlitzCageTabPhase = null;
    /** Timestamp when the sidebar stopped showing cage phase (cages opened). */
    private static volatile long lastBlitzCageReleasedMs = 0L;
    /** Replay TAB can legitimately contain two rows for the local IGN; leave server rows untouched there. */
    private static Boolean lastHypixelReplayTabPhase = null;
    /**
     * Post-game results vs in-world: {@code pregame=false} and {@code active=false} for both, so we must still
     * invalidate when leaving GAME OVER or when Hypixel main-lobby detection flips — otherwise TAB cache keeps
     * match colors (e.g. §a teammate tint or server rank colors from the ended game).
     */
    private static Boolean lastBlitzGameOverTabPhase = null;
    private static Boolean lastHypixelLobbyTabPhase = null;
    /**
     * Hypixel lobby→lobby hops often keep the same Blitz phase flags but resend TAB formatting; while disguised we must
     * drop {@link #tabDisplayFormatCache} when the sidebar fingerprint changes so rank colors are recomposed.
     */
    private static String lastHypixelLobbySidebarFingerprint = null;
    /**
     * Limbo (and similar) has no scoreboard in display slot 1 — vanilla TAB is often plain §f.
     * Cached lines from that state must be dropped when a sidebar reappears (e.g. returning to Blitz).
     */
    private static Boolean lastHypixelHadSidebarObjective = null;
    private static final Pattern TAB_LINE_HAS_WINS_SUFFIX = Pattern.compile("\\[[0-9]+w\\]");
    /** TAB stats from gomod /tabstats: {@code [0.50wl]} without {@code /Nw} (wl-only display mode). */
    private static final Pattern TAB_LINE_HAS_WL_ONLY_SUFFIX = Pattern.compile("\\[[\\d.]+wl\\]");
    /** Any gomod Blitz stats bracket ({@code [4.6kd]}, {@code [0.50wl]}, {@code [4.6kd/0.52wl/700w]}, etc.). */
    private static final Pattern TAB_LINE_HAS_STATS_SUFFIX = Pattern.compile(
            "\\[[^\\]]*(?:\\d+\\.?\\d*kd|[\\d.]+wl|\\d+w)[^\\]]*\\]");
    /** Kill suffix may use bold etc. ({@code §l}) — match any §-code run before {@code [n]}. */
    private static final Pattern TAB_LINE_HAS_KILL_COUNTER = Pattern.compile("(?:§.)*\\[[0-9]{1,4}\\]");
    private static final Pattern TAB_LINE_HAS_RESOLVED_ALIAS_PARENS = Pattern.compile("(?i)(?:§[0-9a-fk-or])*\\([a-z0-9_]{3,16}\\)");
    private static final Pattern TAB_LINE_HAS_RESOLVED_ALIAS_EQUALS = Pattern.compile("(?i)=\\s*(?:§.)*[a-z0-9_]{3,16}");

    private static void clearTabFormattingCaches() {
        tabDisplayFormatCache.clear();
        tabRowComposeFingerprintByRowKey.clear();
        tabRenderedLineByRowKey.clear();
        tabUnpaddedLineByRowKey.clear();
        tabStatsSegmentByRowKey.clear();
        tabRightAlignedStatsColumnWidth = -1;
        lookupNamesCache.clear();
        LocalDisguise.invalidateTabDisplayRewriteCache();
    }

    /** Per-compose-cycle cache for {@link #getLookupNames}. Cleared at the start of each compose pass. */
    private static final Map<String, List<String>> lookupNamesCache = new HashMap<>();

    public static void markTabCompositionDirty() {
        tabCompositionDirty = true;
        // The shared right-aligned column depends on the composed row strings.
        // Recompute it on the next TAB render, not on every frame while TAB is held.
        tabRightAlignedStatsColumnWidth = -1;
    }

    private static net.minecraft.world.World tabTeamsPacketDirtyCoalesceWorld;
    private static long tabTeamsPacketDirtyCoalesceWorldTime = Long.MIN_VALUE;

    /**
     * {@link #finalizeTabListForOverlayRender} runs every render frame while TAB is held; skin/appearance caching
     * only needs client-tick cadence (same data as last frame within a tick).
     */
    private static net.minecraft.world.World tabOverlayFinalizeCacheWorld;
    private static long tabOverlayFinalizeCacheWorldTick = Long.MIN_VALUE;

    /**
     * Sort result cache for {@link #finalizeTabListForOverlayRender}: the sorted order only changes when the
     * underlying data or config changes, which happens at most once per world tick.  Caching prevents the
     * expensive per-comparison {@link #tabSortPlainMergedForDistrict} chain from running every render frame
     * (60+ fps × O(n log n) × 6-pass string ops = extreme GC pressure).
     */
    private static Collection<NetworkPlayerInfo> tabOverlaySortResultCache = null;
    private static long tabOverlaySortResultCacheTick = Long.MIN_VALUE;
    private static net.minecraft.world.World tabOverlaySortResultCacheWorld = null;
    private static TabListSortMode tabOverlaySortResultCacheMode = TabListSortMode.OFF;
    private static boolean tabOverlaySortResultCacheDistrict;

    /**
     * Hypixel teams can burst many {@code S3EPacketTeams} in one client tick; one dirty mark per world tick is enough.
     */
    public static void markTabCompositionDirtyFromTeamsPacketBurst(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            markTabCompositionDirty();
            return;
        }
        net.minecraft.world.World w = mc.theWorld;
        long t = w.getTotalWorldTime();
        if (tabTeamsPacketDirtyCoalesceWorld == w && tabTeamsPacketDirtyCoalesceWorldTime == t) {
            return;
        }
        tabTeamsPacketDirtyCoalesceWorld = w;
        tabTeamsPacketDirtyCoalesceWorldTime = t;
        markTabCompositionDirty();
    }

    /**
     * Rate-limited game-end detection; cheap interval gate plus heavy work only when {@link #isGameEnded()} is true.
     * Previously folded into {@link #updateAllTabNames()}, which ran on the same 3.5s cadence as periodic TAB compose
     * and contributed to noticeable stalls on large Hypixel TAB lists.
     */
    private static void tickGameEndDetection() {
        // Replay timelines can temporarily expose a GAME OVER/no-Taunt sidebar while the viewer seeks. That is not
        // a real world transition and must not erase victim -> killer relations already observed in replay chat.
        if (shouldIgnoreKillTrackingInReplay()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return;
        }
        if (TabStatsRuntimeMute.isMuted()) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastGameEndCheck <= GAME_END_CHECK_INTERVAL) {
            return;
        }
        lastGameEndCheck = currentTime;
        if (!isGameEnded()) {
            return;
        }
        gameHasEnded = true;
        killCounts.clear();
        playerColors.clear();
        playerVictims.clear();
        replayKillerByVictim.clear();
        playerBlitzStarDisplayByLookupLower.clear();
        BlitzStarMatchSummary.clear();
        BlitzStarEffectsHudTracker.clearAll();
        cachedTabAppearanceByLookupLower.clear();
        playerKits.clear();
        enemyPlayerKits.clear();
        EnemyKitPrestigeValidator.resetForNewMatch();
        deadPlayers.clear();
        deathOrderByPlayer.clear();
        deathOrderCounter.set(0L);
        cagesOpenTime = 0L;
        lastBlitzCageReleasedMs = 0L;
        kitDetectionActive = false;
        sawTaunt = false;
        clearTabFormattingCaches();
        me.napim.gomod.features.BlitzFlameKillEffectTracker.clearActiveProcTimer();
        me.napim.gomod.command.KillEffectCommand.clearAllKillEffects();
        composeAllPlayerTabDisplayNames(false);
    }

    /**
     * Run from the client tick only. Applies debounced updates when packets/API threads mark dirty,
     * plus a periodic full TAB compose (see {@link #TAB_PERIODIC_COMPOSE_INTERVAL_MS}). Game-end detection runs on
     * {@link #GAME_END_CHECK_INTERVAL} via {@link #tickGameEndDetection()}.
     */
    public static void onClientTickForTabComposition() {
        Minecraft mc = Minecraft.getMinecraft();
        handleTabQolContextTransition(mc);
        if (!isTabQolRuntimeContext(mc)) {
            return;
        }
        handleTabKillersToggleKeybind();
        tickGameEndDetection();
        long now = System.currentTimeMillis();
        boolean duePeriodic = (now - lastPeriodicTabComposeMs >= TAB_PERIODIC_COMPOSE_INTERVAL_MS);
        boolean dueDirty = tabCompositionDirty && (now - lastTabCompositionApplyMs >= TAB_COMPOSITION_DEBOUNCE_MS);
        if (!duePeriodic && !dueDirty) {
            return;
        }
        tabCompositionDirty = false;
        lastTabCompositionApplyMs = now;
        if (duePeriodic) {
            lastPeriodicTabComposeMs = now;
        }
        String composeReason = (duePeriodic && dueDirty) ? "periodic+dirty" : (duePeriodic ? "periodic" : "dirty");
        TabStatsTrafficDebug.onTabCompose(composeReason);
        updateAllTabNames();
    }

    private static void handleTabKillersToggleKeybind() {
        int keyCode = ConfigManager.getKillCounterTabKillersKeyCode();
        if (keyCode <= 0) {
            wasTabKillersToggleKeyDown = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) {
            wasTabKillersToggleKeyDown = false;
            return;
        }
        boolean isDown = Keyboard.isKeyDown(keyCode);
        if (!isDown) {
            wasTabKillersToggleKeyDown = false;
            return;
        }
        if (wasTabKillersToggleKeyDown) {
            return;
        }
        wasTabKillersToggleKeyDown = true;
        toggleTabKillersEnabled(true);
    }

    public static boolean toggleTabKillersEnabled(boolean showChatFeedback) {
        boolean next = !ConfigManager.isKillCounterTabKillersEnabled();
        setTabKillersEnabled(next, showChatFeedback);
        return next;
    }

    public static void setTabKillersEnabled(boolean enabled, boolean showChatFeedback) {
        ConfigManager.setKillCounterTabKillersEnabled(enabled);
        forceTabRefresh();
        if (!showChatFeedback) {
            return;
        }
        if (enabled) {
            if (!ConfigManager.isKillCounterEnabled()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "TAB QoL is off in settings — turn it on (gomod menu \u2192 TAB QoL) or kills/deaths are not tracked, so grouping stays empty.");
            } else if (!KillCounter.isEnabled()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Kill tracking is paused — run " + EnumChatFormatting.WHITE + "/startkc"
                        + EnumChatFormatting.YELLOW + " (or get a kill chat line to auto-start) so victims are recorded.");
            }
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "TAB players' victims: "
                + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
    }

    private static boolean looksLikeGomodComposedTabLine(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return false;
        }
        String withoutPing = stripTrailingPingSuffixFromTabLine(formatted);
        if (!withoutPing.equals(formatted)) {
            formatted = withoutPing;
        }
        if (formatted.contains("= ")) {
            return true;
        }
        if (TAB_LINE_HAS_RESOLVED_ALIAS_PARENS.matcher(formatted).find()
                || TAB_LINE_HAS_RESOLVED_ALIAS_EQUALS.matcher(formatted).find()) {
            return true;
        }
        if (formatted.contains("wl/")) {
            return true;
        }
        if (TAB_LINE_HAS_STATS_SUFFIX.matcher(formatted).find()) {
            return true;
        }
        if (TAB_LINE_HAS_WL_ONLY_SUFFIX.matcher(formatted).find()) {
            return true;
        }
        if (formatted.matches(".*\\([^)]{2,48}\\)\\s*$")) {
            return true;
        }
        if (TAB_LINE_HAS_WINS_SUFFIX.matcher(formatted).find()) {
            return true;
        }
        return TAB_LINE_HAS_KILL_COUNTER.matcher(tabLineAfterLeadingBracketChain(formatted)).find();
    }

    /**
     * When {@link #looksLikeGomodComposedTabLine} misses (e.g. bold codes in kill brackets), still salvage the base
     * so we do not append a second kill suffix / stats block.
     */
    private static boolean composedTabLineNeedsBaseRecovery(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return false;
        }
        if (looksLikeGomodComposedTabLine(formatted)) {
            return true;
        }
        String noPing = stripTrailingPingSuffixFromTabLine(formatted);
        if (TAB_LINE_HAS_KILL_COUNTER.matcher(tabLineAfterLeadingBracketChain(noPing)).find()) {
            return true;
        }
        // Kit suffix may sit before ping/stats; if we miss it, each compose stacks another "(Kit)" and TAB gets huge → lag.
        String kitProbe = noPing;
        String shrunk = kitProbe;
        for (int i = 0; i < 8; i++) {
            String next = stripTrailingKitSuffixFromTabLine(shrunk);
            if (next.equals(shrunk)) {
                break;
            }
            shrunk = next;
        }
        return !shrunk.equals(kitProbe);
    }

    private static int skipFormattingWsAndCodes(int p, String s) {
        int n = s.length();
        while (p < n) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t') {
                p++;
                continue;
            }
            if (p < n - 1 && c == '\u00A7') {
                p += 2;
                continue;
            }
            break;
        }
        return p;
    }

    /** @return index after {@code ]}, or {@code -1} */
    private static int indexAfterMatchingFormattedBracket(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '[') {
            return -1;
        }
        int p = openIdx + 1;
        int depth = 1;
        int n = s.length();
        while (p < n) {
            if (p < n - 1 && s.charAt(p) == '\u00A7') {
                p += 2;
                continue;
            }
            char c = s.charAt(p);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return p + 1;
                }
            }
            p++;
        }
        return -1;
    }

    /**
     * Hypixel TAB lines often start with one or more {@code […]} tags (rank, then {@code [DIST n]} with § between
     * letters). Returns the index after the last leading bracket + following whitespace, or 0 if none.
     */
    private static int endOfAllLeadingFormattedBrackets(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int p = skipFormattingWsAndCodes(0, s);
        int n = s.length();
        int lastEnd = 0;
        while (p < n) {
            if (s.charAt(p) != '[') {
                break;
            }
            int after = indexAfterMatchingFormattedBracket(s, p);
            if (after < 0) {
                break;
            }
            lastEnd = after;
            p = skipFormattingWsAndCodes(after, s);
        }
        return lastEnd;
    }

    /** Remainder after Hypixel's leading {@code [MVP+]} / {@code [TEAM]} / {@code [DIST n]} chain for kill-counter heuristics. */
    private static String tabLineAfterLeadingBracketChain(String formatted) {
        int e = endOfAllLeadingFormattedBrackets(formatted);
        return e > 0 ? formatted.substring(e) : formatted;
    }

    /**
     * After prepending a leading {@code […]} chunk from the scoreboard team line, ensure one ASCII space
     * before the player body when the body does not continue with another {@code [} tag (fixes {@code [DIST 1]Name}).
     */
    private static String joinMergedBracketPrefixToBody(String prefix, String body) {
        if (body == null || body.isEmpty()) {
            return prefix;
        }
        if (prefix == null || prefix.isEmpty()) {
            return body;
        }
        int p = skipFormattingWsAndCodes(0, body);
        if (p < body.length() && body.charAt(p) == '[') {
            return prefix + body;
        }
        int endPrefix = prefix.length();
        while (endPrefix > 0 && Character.isWhitespace(prefix.charAt(endPrefix - 1))) {
            endPrefix--;
        }
        String trimmedPrefix = prefix.substring(0, endPrefix);
        return trimmedPrefix + " " + body.substring(p);
    }

    /**
     * Map a plain-text character index (formatting codes stripped) to the index in {@code formatted}.
     */
    private static int formattedIndexForPlainIndex(String formatted, int plainIndex) {
        if (formatted == null || plainIndex <= 0) {
            return 0;
        }
        int fi = 0;
        int pi = 0;
        int n = formatted.length();
        while (fi < n) {
            if (fi < n - 1 && formatted.charAt(fi) == '\u00A7') {
                fi += 2;
                continue;
            }
            if (pi >= plainIndex) {
                return fi;
            }
            fi++;
            pi++;
        }
        return fi;
    }

    /**
     * Inside a single {@code […]} chunk, inserts a space between {@code DIST} and a digit when Hypixel omits it
     * (plain like {@code DIST1}), preserving § codes.
     */
    private static String fixCompactDistInteriorInBracketChunk(String chunk) {
        if (chunk == null || chunk.length() < 3 || chunk.charAt(0) != '[' || chunk.charAt(chunk.length() - 1) != ']') {
            return chunk;
        }
        String interior = chunk.substring(1, chunk.length() - 1);
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(interior);
        if (plain == null) {
            plain = "";
        }
        Matcher dm = Pattern.compile("(?i)DIST(\\d+)").matcher(plain);
        if (!dm.find()) {
            return chunk;
        }
        int digitPlainStart = dm.start(1);
        if (digitPlainStart > 0 && Character.isWhitespace(plain.charAt(digitPlainStart - 1))) {
            return chunk;
        }
        int fi = formattedIndexForPlainIndex(interior, digitPlainStart);
        String newInterior = interior.substring(0, fi) + " " + interior.substring(fi);
        return "[" + newInterior + "]";
    }

    /**
     * Normalizes leading {@code [DIST n]} chunks so {@code DIST} and the number are not glued (e.g. {@code [DIST1]} →
     * {@code [DIST 1]}), preserving other leading tags and formatting.
     */
    private static String normalizeBlitzTabDistBracketChunks(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        String probe = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (probe == null || !probe.toUpperCase(Locale.ROOT).contains("DIST")) {
            return formatted;
        }
        int n = formatted.length();
        int p = skipFormattingWsAndCodes(0, formatted);
        if (p >= n || formatted.charAt(p) != '[') {
            return formatted;
        }
        int chainStart = p;
        StringBuilder sb = new StringBuilder(formatted.substring(0, chainStart));
        while (p < n && formatted.charAt(p) == '[') {
            int open = p;
            int after = indexAfterMatchingFormattedBracket(formatted, open);
            if (after < 0) {
                sb.append(formatted.substring(open));
                return sb.toString();
            }
            String chunk = formatted.substring(open, after);
            sb.append(fixCompactDistInteriorInBracketChunk(chunk));
            int next = skipFormattingWsAndCodes(after, formatted);
            if (next >= n || formatted.charAt(next) != '[') {
                sb.append(formatted.substring(after));
                return sb.toString();
            }
            sb.append(formatted, after, next);
            p = next;
        }
        return sb.toString();
    }

    /**
     * Ensures one ASCII space after the leading {@code […]} tag chain before the player name when the next visible
     * character is not another tag — fixes {@code ]Josh} so IGN is not stuck in the same §7 run as {@code [DIST n]}.
     */
    private static String ensureSpaceAfterLeadingBracketChainBeforeBody(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        int leadEnd = endOfAllLeadingFormattedBrackets(formatted);
        if (leadEnd <= 0) {
            return formatted;
        }
        int p = skipFormattingWsAndCodes(leadEnd, formatted);
        if (p >= formatted.length()) {
            return formatted;
        }
        char c = formatted.charAt(p);
        if (c == '[') {
            return formatted;
        }
        // Do not use skipFormattingWsAndCodes for the "needs space?" check: it skips spaces after ']', so a line like
        // "[DIST 1] §7Player" lands on '§' or the IGN and we kept inserting another space every TAB refresh (growing gap).
        if (c == ' ' || c == '\t') {
            return formatted;
        }
        for (int i = leadEnd; i < p; i++) {
            char ch = formatted.charAt(i);
            if (ch == ' ' || ch == '\t') {
                return formatted;
            }
        }
        return formatted.substring(0, leadEnd) + " " + formatted.substring(leadEnd);
    }

    private static boolean tabPlainIndicatesHypixelTeamTag(String formattedTabLine) {
        if (formattedTabLine == null || formattedTabLine.isEmpty()) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(formattedTabLine);
        if (plain == null) {
            return false;
        }
        return plain.toUpperCase(Locale.ROOT).contains("[TEAM]");
    }

    /**
     * Hypixel Teams Blitz: your duo's TAB row actually contains {@code [TEAM]} in the <b>network packet</b> display.
     * Do <b>not</b> use {@link #mergeTeamDistBracketPrefixIfMissing} here: Hypixel often puts {@code [TEAM]} on the
     * scoreboard {@link ScorePlayerTeam} for a whole district, then merges would inject that tag into every player's
     * row and teammate glow would outline everyone.
     */
    public static boolean tabRowShowsHypixelTeamTag(NetworkPlayerInfo info) {
        if (info == null) {
            return false;
        }
        String baseName = RemoteDisguiseManager.getSourceProfile(info).getName();
        if (baseName == null || baseName.isEmpty()) {
            return false;
        }
        try {
            String packetFt = getNetworkPlayerInfoFormattedText(info);
            if (packetFt != null && !packetFt.trim().isEmpty()) {
                if (tabPlainIndicatesHypixelTeamTag(packetFt)) {
                    return true;
                }
                if (looksLikeGomodComposedTabLine(packetFt)) {
                    String recovered = recoverServerTabBaseFromComposedLine(packetFt);
                    if (recovered != null && tabPlainIndicatesHypixelTeamTag(recovered)) {
                        return true;
                    }
                }
            }
            // Packet missing or still no [TEAM]: avoid team-format merge (see javadoc). Optional raw team line only
            // when the server never sent a custom tab display — rare on Hypixel.
            if (packetFt == null || packetFt.trim().isEmpty()) {
                String teamFmt = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), baseName);
                return tabPlainIndicatesHypixelTeamTag(teamFmt);
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Hypixel teams Blitz TAB tags: {@code [DIST n]} and {@code [TEAM]} (you / teammates). Packet
     * {@code displayName} may omit them while the scoreboard team string still has them.
     */
    private static String mergeTeamDistBracketPrefixIfMissing(String primary, String teamFormatted) {
        if (primary == null || primary.isEmpty() || teamFormatted == null || teamFormatted.isEmpty()) {
            return primary;
        }
        String plainP = EnumChatFormatting.getTextWithoutFormattingCodes(primary);
        if (plainP == null) {
            plainP = "";
        }
        String up = plainP.toUpperCase(Locale.ROOT);
        boolean needTeam = !up.contains("TEAM");
        boolean needDist = !up.contains("DIST");
        if (!needTeam && !needDist) {
            return primary;
        }

        boolean primaryStartsBracket = plainP.trim().startsWith("[");
        if (!primaryStartsBracket) {
            int end = endOfAllLeadingFormattedBrackets(teamFormatted);
            if (end > 0) {
                String lead = teamFormatted.substring(0, end);
                String upl = EnumChatFormatting.getTextWithoutFormattingCodes(lead);
                if (upl != null) {
                    String ul = upl.toUpperCase(Locale.ROOT);
                    if ((needTeam && ul.contains("TEAM")) || (needDist && ul.contains("DIST"))) {
                        return joinMergedBracketPrefixToBody(lead, primary);
                    }
                }
            }
            return primary;
        }

        String extra = collectMissingTeamOrDistBracketChunksFromTeam(teamFormatted, needTeam, needDist);
        if (extra.isEmpty()) {
            return primary;
        }
        return joinMergedBracketPrefixToBody(extra, primary);
    }

    /**
     * Walk {@code teamFormatted}'s leading {@code […]} blocks; append those whose plain text contains TEAM or DIST
     * when that tag is still missing (preserves Hypixel order, e.g. {@code [TEAM]} then {@code [DIST 1]}).
     */
    private static String collectMissingTeamOrDistBracketChunksFromTeam(String teamFormatted, boolean needTeam,
            boolean needDist) {
        if (teamFormatted == null || (!needTeam && !needDist)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int p = skipFormattingWsAndCodes(0, teamFormatted);
        int n = teamFormatted.length();
        while (p < n && (needTeam || needDist)) {
            if (p < n - 1 && teamFormatted.charAt(p) == '\u00A7') {
                p += 2;
                continue;
            }
            if (teamFormatted.charAt(p) != '[') {
                break;
            }
            int open = p;
            int after = indexAfterMatchingFormattedBracket(teamFormatted, p);
            if (after < 0) {
                break;
            }
            String seg = teamFormatted.substring(open, after);
            String pl = EnumChatFormatting.getTextWithoutFormattingCodes(seg);
            if (pl != null) {
                String ub = pl.toUpperCase(Locale.ROOT);
                boolean isTeam = ub.contains("TEAM");
                boolean isDist = ub.contains("DIST");
                if ((isTeam && needTeam) || (isDist && needDist)) {
                    int end = after;
                    while (end < n && Character.isWhitespace(teamFormatted.charAt(end))) {
                        end++;
                    }
                    out.append(teamFormatted, open, end);
                    if (isTeam) {
                        needTeam = false;
                    }
                    if (isDist) {
                        needDist = false;
                    }
                }
            }
            p = skipFormattingWsAndCodes(after, teamFormatted);
        }
        return out.toString();
    }

    /**
     * True once we have /tabstats (or guild) data for this row. Used to avoid treating our own
     * {@link NetworkPlayerInfo#setDisplayName} output as a fresh Hypixel base line — that overwrote
     * {@link #tabDisplayFormatCache} whenever the line lacked stats/kill brackets (e.g. kill-effect
     * colors only), which broke stats and coloring after the next tab packet or periodic compose.
     */
    private static boolean hasPersistedBlitzOrGuildTabData(String baseName) {
        if (baseName == null || baseName.isEmpty()) {
            return false;
        }
        String lookupName = resolveTabLookupName(baseName);
        if (!me.napim.gomod.command.KillEffectCommand.getStatsSuffixFor(lookupName).isEmpty()) {
            return true;
        }
        if (me.napim.gomod.command.KillEffectCommand.hasPlayerEffect(lookupName)) {
            return true;
        }
        return isShowGuildTag() && hasGuildTagCached(lookupName);
    }

    /**
     * First case-insensitive literal match of {@code ign} in {@code formattedServerName}, then replace with {@code coloredIgn}.
     * Vanilla {@link String#replace} is case-sensitive; Hypixel team formatting can differ in casing from {@link GameProfile} name.
     */
    /**
     * Match IGN in tab text even when Hypixel inserts {@code §} color codes between letters
     * (literal substring match would fail).
     */
    private static String killEffectIgnSearchPattern(String ign) {
        if (ign == null || ign.isEmpty()) {
            return "";
        }
        StringBuilder rx = new StringBuilder("(?i)");
        for (int i = 0; i < ign.length(); i++) {
            if (i > 0) {
                // Hypixel may insert any § code between letters (e.g. §l bold); [0-9a-fk-or] missed §l/§m/§n.
                rx.append("(?:§.)*");
            }
            rx.append(Pattern.quote(String.valueOf(ign.charAt(i))));
        }
        return rx.toString();
    }

    static String applyKillEffectColorToIgn(String formattedServerName, String ign, String coloredIgn) {
        if (formattedServerName == null || ign == null || ign.isEmpty() || coloredIgn == null) {
            return formattedServerName;
        }
        try {
            int leadEnd = endOfAllLeadingFormattedBrackets(formattedServerName);
            if (leadEnd > 0) {
                String prefix = formattedServerName.substring(0, leadEnd);
                String rest = formattedServerName.substring(leadEnd);
                String recoloredRest = applyKillEffectColorToIgnCore(rest, ign, coloredIgn);
                if (!recoloredRest.equals(rest)) {
                    return prefix + recoloredRest;
                }
            }
            return applyKillEffectColorToIgnCore(formattedServerName, ign, coloredIgn);
        } catch (Throwable t) {
            return formattedServerName;
        }
    }

    private static String applyKillEffectColorToIgnCore(String formattedServerName, String ign, String coloredIgn) {
        if (formattedServerName == null || ign == null || ign.isEmpty() || coloredIgn == null) {
            return formattedServerName;
        }
        try {
            String rx = killEffectIgnSearchPattern(ign);
            if (rx.isEmpty()) {
                return formattedServerName;
            }
            java.util.regex.Matcher m = Pattern.compile(rx).matcher(formattedServerName);
            if (!m.find()) {
                return formattedServerName;
            }
            StringBuffer sb = new StringBuffer();
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(coloredIgn));
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            return formattedServerName;
        }
    }

    /** Raw TAB display from the network layer (may be null). */
    private static String getNetworkPlayerInfoFormattedText(NetworkPlayerInfo info) {
        if (info == null) {
            return null;
        }
        try {
            IChatComponent raw = ((NetworkPlayerInfoAccessor) (Object) info).gomod$getDisplayName();
            if (raw == null) {
                return null;
            }
            String ft = raw.getFormattedText();
            if (ft == null || ft.trim().isEmpty()) {
                return null;
            }
            return ft;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * When IGN regex recolor fails, avoid {@link ScorePlayerTeam#formatPlayerName} alone — it drops Hypixel TAB
     * prefixes like {@code [DIST n]} that live in the packet display name, not the team.
     *
     * @param preferPacketOverNeutralTeamFallback when true (Blitz alive default §c pass), if the scoreboard fallback
     *                                            would flatten the IGN to §7/§8 but the packet line already had a
     *                                            richer color (e.g. §c), keep the packet — common in teams modes.
     */
    private static String applyKillEffectColorToIgnWithSmartFallback(NetworkPlayerInfo info, String serverName,
            String baseName, String coloredIgn, boolean preferPacketOverNeutralTeamFallback) {
        String first = applyKillEffectColorToIgn(serverName, baseName, coloredIgn);
        if (!first.equals(serverName)) {
            return first;
        }
        String raw = getNetworkPlayerInfoFormattedText(info);
        if (raw != null && !raw.equals(serverName) && !looksLikeGomodComposedTabLine(raw)) {
            String second = applyKillEffectColorToIgn(raw, baseName, coloredIgn);
            if (!second.equals(raw)) {
                return second;
            }
        }
        if (raw != null && looksLikeGomodComposedTabLine(raw)) {
            String recovered = recoverServerTabBaseFromComposedLine(raw);
            if (recovered != null && !recovered.isEmpty()) {
                String third = applyKillEffectColorToIgn(recovered, baseName, coloredIgn);
                if (!third.equals(recovered)) {
                    return third;
                }
            }
        }
        String teamFmt = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), baseName);
        String fromTeam = applyKillEffectColorToIgn(teamFmt, baseName, coloredIgn);
        if (preferPacketOverNeutralTeamFallback) {
            if (packetTabIgnColorRicherThanNeutralTeam(serverName, teamFmt, baseName)) {
                return serverName;
            }
            if (raw != null && packetTabIgnColorRicherThanNeutralTeam(raw, teamFmt, baseName)) {
                return raw;
            }
        }
        return fromTeam;
    }

    /**
     * Packet TAB and scoreboard team format sometimes match in plain text but the team path uses §7 for the body
     * while the network row already applied §c (or another non-neutral) at the IGN — don't replace with the gray line.
     */
    private static boolean packetTabIgnColorRicherThanNeutralTeam(String formattedPacket, String formattedTeam,
            String ign) {
        if (formattedPacket == null || formattedPacket.isEmpty() || ign == null || ign.isEmpty()) {
            return false;
        }
        String plainP = EnumChatFormatting.getTextWithoutFormattingCodes(formattedPacket);
        String plainT = formattedTeam == null ? null : EnumChatFormatting.getTextWithoutFormattingCodes(formattedTeam);
        if (plainP == null || plainT == null || !plainP.equals(plainT)) {
            return false;
        }
        int idx = indexOfIgnoreCaseVisible(plainP, ign);
        if (idx < 0) {
            return false;
        }
        int fmtP = formattedIndexForVisibleIndex(formattedPacket, idx);
        String teamStr = formattedTeam == null ? "" : formattedTeam;
        int fmtT = formattedIndexForVisibleIndex(teamStr, idx);
        char cP = effectiveTextColorBefore(formattedPacket, fmtP);
        char cT = effectiveTextColorBefore(teamStr, fmtT);
        boolean packetNonNeutral = cP != 0 && cP != '7' && cP != '8';
        boolean teamIsNeutral = cT == 0 || cT == '7' || cT == '8';
        return packetNonNeutral && teamIsNeutral;
    }

    /**
     * Updates {@link #tabDisplayFormatCache} from the current NetworkPlayerInfo display field when it still
     * looks like Hypixel's line (not our composed suffixes).
     */
    private static void maybeCacheServerTabFormatting(NetworkPlayerInfo info, String baseName) {
        if (info == null || baseName == null || baseName.isEmpty()) {
            return;
        }
        if (hasPersistedBlitzOrGuildTabData(baseName)) {
            return;
        }
        try {
            IChatComponent raw = ((NetworkPlayerInfoAccessor) (Object) info).gomod$getDisplayName();
            if (raw == null) {
                return;
            }
            String ft = raw.getFormattedText();
            if (ft == null || ft.trim().isEmpty()) {
                return;
            }
            if (!looksLikeGomodComposedTabLine(ft)) {
                tabDisplayFormatCache.put(tabRowKey(info), ft);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * After we've composed a line with stats/kills, recover the left-hand Hypixel prefix (rank + colored name).
     */
    private static String recoverServerTabBaseFromComposedLine(String composed) {
        if (composed == null || composed.isEmpty()) {
            return null;
        }
        String s = composed;
        // Max realistic suffix stack depth is ~9 layers; 10 iterations is safe.
        for (int i = 0; i < 10; i++) {
            String before = s;
            // Order matches suffix stack from the right: ping, guild, kits×n, stats, kill, (realName), legacy
            s = stripTrailingPingSuffixFromTabLine(s);
            s = stripTrailingGuildTagSuffixFromTabLine(s);
            for (int k = 0; k < 4; k++) {
                s = stripTrailingKitSuffixFromTabLine(s);
            }
            s = stripTrailingStatsSuffixFromTabLine(s);
            s = stripTrailingKillCounterSuffixFromTabLine(s);
            s = stripTrailingResolvedAliasSuffixFromTabLine(s);
            s = stripTrailingLegacyAliasSuffixFromTabLine(s);
            if (s.equals(before)) {
                break;
            }
        }
        return s.isEmpty() ? null : s;
    }

    private static final Pattern STRIP_TRAILING_KILL_COUNT = Pattern.compile("(?:§.)*\\[[0-9]{1,4}\\](?:§.)*\\s*$");

    /**
     * Removes trailing {@code §…[n]} kill-counter chunks so we never stack a second {@code [kills]} when the
     * server TAB line still carries our previous composition (e.g. Blitz sidebar switches to GAME OVER).
     */
    private static String stripTrailingKillCountBracketsFromFormattedTab(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        String s = formatted;
        for (int k = 0; k < 12; k++) {
            String afterKill = STRIP_TRAILING_KILL_COUNT.matcher(s).replaceAll("").trim();
            if (afterKill.equals(s)) {
                break;
            }
            s = afterKill;
        }
        return s;
    }

    private static void invalidateTabFormatCacheOnBlitzPhaseChange(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (!GameContext.isOnHypixel(mc)) {
            clearCurrentTabDisplayOverrides(mc);
            lastBlitzPregameTabPhase = null;
            lastBlitzGameActiveTabPhase = null;
            lastBlitzHubQueueTabPhase = null;
            lastBlitzCageTabPhase = null;
            lastBlitzCageReleasedMs = 0L;
            lastHypixelReplayTabPhase = null;
            lastBlitzGameOverTabPhase = null;
            lastHypixelLobbyTabPhase = null;
            lastHypixelLobbySidebarFingerprint = null;
            lastHypixelHadSidebarObjective = null;
            return;
        }
        boolean hasSidebarObjective = false;
        try {
            if (mc.theWorld != null && mc.theWorld.getScoreboard() != null) {
                hasSidebarObjective = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1) != null;
            }
        } catch (Throwable ignored) {
        }
        if (lastHypixelHadSidebarObjective != null
                && !lastHypixelHadSidebarObjective.booleanValue()
                && hasSidebarObjective) {
            clearTabFormattingCaches();
            clearCurrentTabDisplayOverrides(mc);
            lastHypixelLobbySidebarFingerprint = null;
            markTabCompositionDirty();
        }
        lastHypixelHadSidebarObjective = hasSidebarObjective;

        boolean pre = GameContext.isBlitzPregameLobby(mc);
        boolean act = GameContext.isBlitzGameActive(mc);
        boolean hubQueue = GameContext.isBlitzHypixelHubQueueSidebar(mc);
        boolean cage = GameContext.isBlitzCagePhaseSidebar(mc);
        boolean gameOver = GameContext.isBlitzGameOverSidebar(mc);
        boolean hypLobby = GameContext.isHypixelLobby(mc);
        boolean hypReplay = GameContext.isHypixelReplayWatching(mc);
        if (lastBlitzPregameTabPhase == null && lastBlitzGameActiveTabPhase == null) {
            lastBlitzPregameTabPhase = pre;
            lastBlitzGameActiveTabPhase = act;
            lastBlitzHubQueueTabPhase = hubQueue;
            lastBlitzCageTabPhase = cage;
            lastHypixelReplayTabPhase = hypReplay;
            lastBlitzGameOverTabPhase = gameOver;
            lastHypixelLobbyTabPhase = hypLobby;
            return;
        }
        if (lastHypixelReplayTabPhase == null || lastHypixelReplayTabPhase.booleanValue() != hypReplay) {
            clearTabFormattingCaches();
            lastHypixelReplayTabPhase = hypReplay;
            markTabCompositionDirty();
        }
        if (lastBlitzPregameTabPhase != pre || lastBlitzGameActiveTabPhase != act) {
            clearTabFormattingCaches();
            clearCurrentTabDisplayOverrides(mc);
            lastBlitzPregameTabPhase = pre;
            lastBlitzGameActiveTabPhase = act;
            lastBlitzHubQueueTabPhase = hubQueue;
            lastBlitzCageTabPhase = cage;
            lastHypixelReplayTabPhase = hypReplay;
            lastBlitzGameOverTabPhase = gameOver;
            lastHypixelLobbyTabPhase = hypLobby;
            return;
        }
        if (lastBlitzHubQueueTabPhase != null && lastBlitzHubQueueTabPhase.booleanValue() != hubQueue) {
            clearTabFormattingCaches();
            clearCurrentTabDisplayOverrides(mc);
        }
        if (lastBlitzCageTabPhase != null && lastBlitzCageTabPhase.booleanValue() != cage) {
            clearTabFormattingCaches();
            clearCurrentTabDisplayOverrides(mc);
            if (lastBlitzCageTabPhase.booleanValue() && !cage) {
                lastBlitzCageReleasedMs = System.currentTimeMillis();
            }
        }
        lastBlitzHubQueueTabPhase = hubQueue;
        lastBlitzCageTabPhase = cage;

        if (lastBlitzGameOverTabPhase == null || lastBlitzGameOverTabPhase.booleanValue() != gameOver) {
            clearTabFormattingCaches();
            clearCurrentTabDisplayOverrides(mc);
            lastBlitzGameOverTabPhase = gameOver;
            markTabCompositionDirty();
        }
        if (lastHypixelLobbyTabPhase == null || lastHypixelLobbyTabPhase.booleanValue() != hypLobby) {
            clearTabFormattingCaches();
            clearCurrentTabDisplayOverrides(mc);
            lastHypixelLobbyTabPhase = hypLobby;
            markTabCompositionDirty();
        }

        if (LocalDisguise.isEnabled() && GameContext.isHypixelLobby(mc)) {
            String fp = computeHypixelSidebarFingerprint(mc);
            if (lastHypixelLobbySidebarFingerprint == null) {
                lastHypixelLobbySidebarFingerprint = fp;
            } else if (fp != null && !fp.equals(lastHypixelLobbySidebarFingerprint)) {
                lastHypixelLobbySidebarFingerprint = fp;
                clearTabFormattingCaches();
                clearCurrentTabDisplayOverrides(mc);
                markTabCompositionDirty();
            }
        } else if (!LocalDisguise.isEnabled()) {
            lastHypixelLobbySidebarFingerprint = null;
        }
    }

    /** Blitz Hour countdown and similar rows tick every second — ignore score digits in lobby fingerprint. */
    private static boolean isVolatileHypixelSidebarFingerprintEntry(Scoreboard board, ScoreObjective objective, Score score) {
        if (score == null || score.getPlayerName() == null) {
            return false;
        }
        try {
            ScorePlayerTeam team = board == null ? null : board.getPlayersTeam(score.getPlayerName());
            String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            if (line == null) {
                line = score.getPlayerName();
            }
            String visible = EnumChatFormatting.getTextWithoutFormattingCodes(line);
            if (visible == null) {
                visible = line;
            }
            String lower = visible.toLowerCase(Locale.ROOT);
            if (lower.contains("random for") && lower.contains(":")) {
                return true;
            }
            if (me.napim.gomod.features.SidebarBlitzHourReminder.isBlitzHourSidebarText(line)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String computeHypixelSidebarFingerprint(Minecraft mc) {
        try {
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return "";
            }
            ScoreObjective obj = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
            if (obj == null) {
                return "";
            }
            String title = EnumChatFormatting.getTextWithoutFormattingCodes(obj.getDisplayName());
            if (title == null) {
                title = "";
            }
            StringBuilder sb = new StringBuilder(title.length() + 64);
            sb.append(title).append('|');
            int n = 0;
            for (Score s : mc.theWorld.getScoreboard().getSortedScores(obj)) {
                if (s == null) {
                    continue;
                }
                String pn = s.getPlayerName();
                if (pn == null || pn.startsWith("#")) {
                    continue;
                }
                if (isVolatileHypixelSidebarFingerprintEntry(mc.theWorld.getScoreboard(), obj, s)) {
                    sb.append(pn).append("=timer;");
                } else {
                    sb.append(pn).append('=').append(s.getScorePoints()).append(';');
                }
                if (++n >= 16) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void clearCurrentTabDisplayOverrides(Minecraft mc) {
        if (mc == null || mc.getNetHandler() == null) {
            return;
        }
        tabStatsSegmentByRowKey.clear();
        if (GomodFixes.isWeaveRuntime()) {
            tabRowComposeFingerprintByRowKey.clear();
            tabRenderedLineByRowKey.clear();
            tabUnpaddedLineByRowKey.clear();
            return;
        }
        try {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info != null) {
                    info.setDisplayName(null);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String resolveTabServerFormattedBase(NetworkPlayerInfo info, String baseName) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean blitzMatchPregameNoHub = mc != null && GameContext.isBlitzMatchMapPregameSidebar(mc);
        if (!blitzMatchPregameNoHub) {
            maybeCacheServerTabFormatting(info, baseName);
            String rowKey = tabRowKey(info);
            String cached = tabDisplayFormatCache.get(rowKey);
            if (cached != null && !cached.isEmpty()) {
                if (!looksLikeGomodComposedTabLine(cached)) {
                    return cached;
                }
                String recoveredCached = recoverServerTabBaseFromComposedLine(cached);
                if (recoveredCached != null && !recoveredCached.isEmpty()) {
                    tabDisplayFormatCache.put(rowKey, recoveredCached);
                    return recoveredCached;
                }
                tabDisplayFormatCache.remove(rowKey);
            }
        }
        try {
            IChatComponent raw = ((NetworkPlayerInfoAccessor) (Object) info).gomod$getDisplayName();
            if (raw != null) {
                String ft = raw.getFormattedText();
                if (ft != null && looksLikeGomodComposedTabLine(ft)) {
                    String recovered = recoverServerTabBaseFromComposedLine(ft);
                    if (recovered != null && !recovered.isEmpty()) {
                        if (!blitzMatchPregameNoHub) {
                            tabDisplayFormatCache.put(tabRowKey(info), recovered);
                        }
                        return recovered;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String live = getNetworkPlayerInfoFormattedText(info);
        if (live != null && !looksLikeGomodComposedTabLine(live)) {
            return live;
        }
        return ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), baseName);
    }

    /**
     * Initializes KillCounter settings from ConfigManager
     */
    public static void initializeSettings() {
        // Keep runtime tracking active regardless of the display toggle so
        // turning the setting on mid-game can immediately show the true count.
        enabled = true;
        syncStatsPartFlagsFromConfig();
        tabKillCounterDisplayMode = TabKillCounterDisplayMode.fromConfig(ConfigManager.getKillCounterTabDisplayMode());
        killEffectTabMode = KillEffectTabDisplayMode.fromConfig(ConfigManager.getKillCounterKillEffectTabMode());
        markTabCompositionDirty();
        // System.out.println("[KillCounter] Initialized - enabled: " + enabled + ", statsDisplay: " + statsDisplay);
    }

    private static void syncStatsPartFlagsFromConfig() {
        statsDisplay = ConfigManager.isKillCounterStatsDisplay();
        statsShowKd = ConfigManager.isKillCounterStatsShowKd();
        statsShowWl = ConfigManager.isKillCounterStatsShowWl();
        statsShowW = ConfigManager.isKillCounterStatsShowW();
    }

    /** Keeps in-memory tab formatting flags aligned with config (single source of truth). */
    private static void syncTabStatsSettingsFromConfig() {
        syncStatsPartFlagsFromConfig();
        tabKillCounterDisplayMode = TabKillCounterDisplayMode.fromConfig(ConfigManager.getKillCounterTabDisplayMode());
        killEffectTabMode = KillEffectTabDisplayMode.fromConfig(ConfigManager.getKillCounterKillEffectTabMode());
    }
    
    // Patterns to detect kill messages
    private static final String[] KILL_PATTERNS = {
        "was killed by",
        "were killed by",
        "was slain by",
        "were slain by",
        "was shot by",
        "were shot by",
        "was blown up by",
        "were blown up by",
        "was burned to death by",
        "were burned to death by",
        "was frozen to death by",
        "were frozen to death by",
        "was electrocuted by",
        "were electrocuted by",
        "was killed by",
        "was eliminated by",
        "were eliminated by",
        "was defeated by"
    };

    // Phrases used when the kill feed is in second-person ("You killed X")
    private static final String[] SELF_KILL_PATTERNS = {
        "killed ",
        "shot ",
        "slew ",
        "slain ",
        "blew up ",
        "burned to death ",
        "frozen to death ",
        "electrocuted ",
        "eliminated ",
        "defeated "
    };

    private static final String[] SIMPLE_DEATH_PATTERNS = {
        "was killed!",
        "were killed!"
    };
    
    // Pattern to detect kit selection messages
    private static final String KIT_SELECTION_PATTERN = "has picked their";
    private static final Pattern LOCAL_KIT_RECEIVE_PATTERN = Pattern.compile(
            "^You will receive your\\s+(.+?)\\s+kit in\\s+\\d+\\s+second(?:s)?!$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_KIT_GRANTED_PATTERN = Pattern.compile(
            "^You(?:\\s+have)?\\s+received your\\s+(.+?)\\s+kit!?$",
            Pattern.CASE_INSENSITIVE);
    
    // Kit names for Blitz Survival Games
    private static final String[] KIT_NAMES = {
        "Archer", "Meatmaster", "Speleologist", "Baker", "Knight", "Guardian",
        "Scout", "Hunter", "Hype Train", "Fisherman", "Armorer", "Horsetamer",
        "Astronaut", "Troll", "Reaper", "Shark", "RedDragon", "Toxicologist",
        "Rogue", "Warlock", "SlimeySlime", "Jockey", "Golem", "Viking",
        "Shadow Knight", "Pigman", "Paladin", "Necromancer", "Florist", "Diver",
        "Arachnologist", "Blaze", "Wolftamer", "Tim", "Farmer", "Creepertamer",
        "Snowman", "Rambo", "Ranger", "Donkeytamer", "Phoenix", "Warrior", "Milkman"
    };
    
    // Regex pattern to detect kit items from gear (e.g., "Guardian's Stone Axe (VI)" or "Armorer's Leather Boots (?)")
    // Handles multi-word kit names like "Shadow Knight" and "Hype Train"
    private static final java.util.regex.Pattern KIT_ITEM_PATTERN = 
        java.util.regex.Pattern.compile("^([A-Za-z]+(?:\\s+[A-Za-z]+)*)'s\\s+.*?\\(([IVX\\?G\\u272B\\u2B50\\u2605\\u2726\\u2606\\u2B51]+)\\)$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final Pattern TELEPORTER_KIT_LABEL_PATTERN = Pattern.compile(
            "(?i)^.*\\bkit\\b(?:\\s*[:=\\-]\\s*|\\s+)(.+?)\\s*$");
    private static final Pattern TELEPORTER_KIT_LEVEL_PATTERN = Pattern.compile(
            "(?i)^(.*?)\\s*(?:\\(\\s*|\\[\\s*|\\s+|\\s*[-:]\\s*)"
                    + "([IVX]+|10|[1-9]|\\?{1,2}|[\\u272B\\u2B50\\u2605\\u2726\\u2606\\u2B51]{1,2})"
                    + "\\s*(?:\\)|\\])?\\s*$");
    // Precompiled patterns for hot-path methods
    /**
     * Trailing {@code [TAG]} that is not TAB stats. Inner text may include § (we render {@code [§xTAG]}).
     * Must stay in sync with {@link #stripTrailingGuildTagSuffixFromTabLine}.
     */
    private static final java.util.regex.Pattern GUILD_TAG_PATTERN = java.util.regex.Pattern.compile(
            ".*\\[(?!\\d+w\\])(?![\\d.]+wl\\])(?![\\d.]+wl/\\d+w\\])(?:(?:§.)|[^\\]]){1,32}\\]\\s*$");
    private static final java.util.regex.Pattern COLOR_CODE_PATTERN = java.util.regex.Pattern.compile("§[0-9a-fk-or]");

    private static final class CachedTabAppearance {
        private UUID profileId;
        private ResourceLocation skin;
        private String skinType;
    }

    private static final class PendingKillFeedLine {
        final long queuedAtMs;
        final String plain;
        final String formatted;
        final String pattern;
        final String normalized;

        PendingKillFeedLine(long queuedAtMs, String plain, String formatted, String pattern) {
            this.queuedAtMs = queuedAtMs;
            this.plain = plain;
            this.formatted = formatted;
            this.pattern = pattern;
            this.normalized = plain == null ? "" : plain.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        }
    }

    public KillCounter() {
        chatObserverInstance = this;
    }
    
    /**
     * Begins a fresh kill-tracking session (HUD + in-tab [k] when enabled). Clears kills/kits for the new match;
     * does not clear {@link me.napim.gomod.command.KillEffectCommand} tabstats caches — use game-end / world-leave for that.
     */
    public static void start() {
        enabled = true;
        // Sync with ConfigManager
        ConfigManager.setKillCounterEnabled(true);
        killCounts.clear();
        playerColors.clear();
        playerKits.clear();
        enemyPlayerKits.clear();
        EnemyKitPrestigeValidator.resetForNewMatch();
        playerBlitzStarDisplayByLookupLower.clear();
        BlitzStarMatchSummary.clear();
        BlitzStarEffectsHudTracker.clearAll();
        cachedTabAppearanceByLookupLower.clear();
        deadPlayers.clear();
        deathOrderByPlayer.clear();
        deathOrderCounter.set(0L);
        cagesOpenTime = 0L;
        lastBlitzCageReleasedMs = 0L;
        kitDetectionActive = false;
        DisguiseRenamedItemMasker.clearLocalReceivedKitTracking();
        gameHasEnded = false; // Reset game end flag for new game
        blitzHourActive = false; // Reset BLITZ HOUR flag for new game
        blitzHourDetectionTime = 0L; // Reset BLITZ HOUR detection time
        sawTaunt = false; // Reset taunt detection for new game
        PlayerTarget.clearTauntStateForNewMatch();
        // Do not clear KillEffectCommand here — /tabstats all caches are separate from per-match kill tracking;
        // clearing would strip TAB icons/colors until another scan. Game end / world leave still clear effects.
        // New Blitz match: Hypixel reapplies TAB team colors — stale cache kept the previous game's dead/alive tint.
        clearTabFormattingCaches();
        // Initialize world name tracking
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld != null) {
                lastWorldName = mc.theWorld.getWorldInfo().getWorldName();
            }
        } catch (Exception e) {
            lastWorldName = null;
        }
        // System.out.println("[KillCounter] TAB Stats started!");
        runOnClientThread(KillCounter::updateAllTabNames);
    }
    
    /**
     * Stops kill tracking and the in-tab kill counter; other TAB decorations (stats, guild, kill-effect color) stay
     * if their settings are on — use {@link #resetAllTabNames()} only when you need vanilla TAB.
     */
    public static void stop() {
        enabled = false;
        ConfigManager.setKillCounterEnabled(false);
        clearPendingKillFeedLines();
        playerBlitzStarDisplayByLookupLower.clear();
        BlitzStarMatchSummary.clear();
        BlitzStarEffectsHudTracker.clearAll();
        deadPlayers.clear();
        deathOrderByPlayer.clear();
        deathOrderCounter.set(0L);
        sawTaunt = false;
        PlayerTarget.clearTauntStateForNewMatch();
        runOnClientThread(KillCounter::updateAllTabNames);
    }
    
    /**
     * Stops TAB Stats temporarily (for world changes) without changing user settings.
     */
    public static void stopTemporarily() {
        enabled = false;
        clearPendingKillFeedLines();
        // DON'T change ConfigManager settings - keep user's preference
        // Clear ALL game-specific data so stale kill counts are never re-applied
        // by KillEffectCommand.onTick() → onClientTickForTabComposition() / forceTabRefresh().
        killCounts.clear();
        playerColors.clear();
        playerVictims.clear();
        synchronized (tabVictimsDebugEvents) {
            tabVictimsDebugEvents.clear();
        }
        replayKillerByVictim.clear();
        playerBlitzStarDisplayByLookupLower.clear();
        BlitzStarMatchSummary.clear();
        BlitzStarEffectsHudTracker.clearAll();
        cachedTabAppearanceByLookupLower.clear();
        playerKits.clear();
        enemyPlayerKits.clear();
        EnemyKitPrestigeValidator.resetForNewMatch();
        deadPlayers.clear();
        deathOrderByPlayer.clear();
        deathOrderCounter.set(0L);
        cagesOpenTime = 0L;
        lastBlitzCageReleasedMs = 0L;
        kitDetectionActive = false;
        DisguiseRenamedItemMasker.clearLocalReceivedKitTracking();
        gameHasEnded = false; // Reset game end flag for new world
        blitzHourActive = false; // Reset BLITZ HOUR flag for new world
        blitzHourDetectionTime = 0L; // Reset BLITZ HOUR detection time
        sawTaunt = false; // Reset taunt detection for new world
        PlayerTarget.clearTauntStateForNewMatch();
        me.napim.gomod.features.BlitzFlameKillEffectTracker.clearActiveProcTimer();
        // Keep TAB-stat kill effects across S01PacketJoinGame. Hypixel uses that packet for the pregame-lobby ->
        // match-map/cages handoff; clearing here made every half-opacity PLAYERS_JOIN icon disappear until the
        // post-cages API refresh. True game-end and explicit TAB-stat reset paths still clear these caches.
        // System.out.println("[KillCounter] TAB Stats stopped temporarily for world change!");
        runOnClientThread(KillCounter::resetAllTabNames);
    }

    /**
     * Invalidates state that belongs to the old server/world. Packet callbacks can arrive on Netty's thread, so
     * only thread-safe caches and generation counters are touched here; Minecraft/TAB objects are reset on the
     * client thread by the caller that owns them.
     */
    public static void onWorldTransition() {
        clearPendingKillFeedLines();
        playersJoinPostCagesRefreshGeneration.incrementAndGet();
        lastTabPlayerUuids.clear();
        lastJoinScanMs = 0L;
        kitDetectionActive = false;
        me.napim.gomod.command.KillEffectCommand.invalidateAutomaticScans();
    }

    private static void runOnClientThread(Runnable task) {
        if (task == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            if (mc.isCallingFromMinecraftThread()) {
                task.run();
            } else {
                mc.addScheduledTask(task);
            }
        } catch (Throwable ignored) {
        }
    }
    
    /**
     * Resets TAB Stats.
     */
    public static void reset() {
        killCounts.clear();
        playerColors.clear();
        playerVictims.clear();
        replayKillerByVictim.clear();
        playerBlitzStarDisplayByLookupLower.clear();
        BlitzStarMatchSummary.clear();
        BlitzStarEffectsHudTracker.clearAll();
        cachedTabAppearanceByLookupLower.clear();
        playerKits.clear();
        enemyPlayerKits.clear();
        EnemyKitPrestigeValidator.resetForNewMatch();
        deadPlayers.clear();
        deathOrderByPlayer.clear();
        deathOrderCounter.set(0L);
        cagesOpenTime = 0L;
        lastBlitzCageReleasedMs = 0L;
        kitDetectionActive = false;
        DisguiseRenamedItemMasker.clearLocalReceivedKitTracking();
        gameHasEnded = false; // Reset game end flag
        blitzHourActive = false; // Reset BLITZ HOUR flag
        blitzHourDetectionTime = 0L; // Reset BLITZ HOUR detection time
        sawTaunt = false;
        PlayerTarget.clearTauntStateForNewMatch();
        clearTabFormattingCaches();
        // System.out.println("[KillCounter] TAB Stats reset!");
        updateAllTabNames();
    }
    
    /**
     * Checks if the kill counter is enabled.
     */
    public static boolean isEnabled() {
        return ConfigManager.isKillCounterEnabled();
    }

    /**
     * True while in-game TAB kill tracking is active ({@link #start()} …). Distinct from {@link #isEnabled()}
     * (config). Used so vanilla-tab kill suffix injection does not run when tracking has not started yet.
     */
    public static boolean isRuntimeTrackingEnabled() {
        return enabled;
    }
    
    public static void setEnabled(boolean enabled) {
        ConfigManager.setKillCounterEnabled(enabled);
        // Setting toggle controls display, not whether runtime keeps counting.
        // If user turns it ON while runtime was paused, resume tracking without reset.
        if (enabled && !KillCounter.enabled) {
            KillCounter.enabled = true;
        }
        // Kill Counter toggle changes the active TAB suffix set; drop any stale cached composed lines first.
        clearTabFormattingCaches();
        try {
            // Never call start() here — start() resets kills/kits/game flags and was intended for /startkc, not settings.
            // Toggling must preserve /tabstats kill-effect data so ICON / colored-name modes keep working.
            updateAllTabNames();
        } catch (Throwable ignored) {}
    }
    
    public static boolean isStatsDisplay() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return statsDisplay;
    }
    
    public static void setStatsDisplay(boolean statsDisplay) {
        KillCounter.statsDisplay = statsDisplay;
        ConfigManager.setKillCounterStatsDisplay(statsDisplay);
        syncStatsPartFlagsFromConfig();
    }
    
    public static boolean isShowColors() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return getKillEffectTabDisplayMode().colorsTab();
    }

    public static TabKillCounterDisplayMode getTabKillCounterDisplayMode() {
        return tabKillCounterDisplayMode;
    }

    public static void setTabKillCounterDisplayMode(TabKillCounterDisplayMode mode) {
        if (mode == null) {
            mode = TabKillCounterDisplayMode.AFTER_NAME;
        }
        tabKillCounterDisplayMode = mode;
        ConfigManager.setKillCounterTabDisplayMode(mode.toConfigValue());
    }

    public static boolean shouldRenderKillCounterAfterName() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return isTabQolRuntimeContext(mc)
                && ConfigManager.isKillCounterEnabled()
                && enabled
                && tabKillCounterDisplayMode == TabKillCounterDisplayMode.AFTER_NAME;
    }

    public static boolean shouldRenderKillCounterBeforeHead() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return isTabQolRuntimeContext(mc)
                && ConfigManager.isKillCounterEnabled()
                && enabled
                && tabKillCounterDisplayMode == TabKillCounterDisplayMode.BEFORE_NAME;
    }

    public static void setShowColors(boolean showColors) {
        setKillEffectTabDisplayMode(showColors ? KillEffectTabDisplayMode.COLORED_NAME : KillEffectTabDisplayMode.OFF);
    }

    public static KillEffectTabDisplayMode getKillEffectTabDisplayMode() {
        if (TabStatsRuntimeMute.isMuted()) {
            return KillEffectTabDisplayMode.OFF;
        }
        return killEffectTabMode = KillEffectTabDisplayMode.fromConfig(ConfigManager.getKillCounterKillEffectTabMode());
    }

    public static void setKillEffectTabDisplayMode(KillEffectTabDisplayMode mode) {
        if (mode == null) {
            mode = KillEffectTabDisplayMode.COLORED_NAME;
        }
        killEffectTabMode = mode;
        ConfigManager.setKillCounterKillEffectTabMode(killEffectTabMode.toConfigValue());
    }

    public static KillEffectTabDisplayMode cycleKillEffectTabDisplayMode() {
        setKillEffectTabDisplayMode(getKillEffectTabDisplayMode().next());
        return killEffectTabMode;
    }

    /**
     * TAB overlay: draw kill-effect icon immediately left of the player head (ICON mode).
     */
    /** PNGs under {@code assets/gomod123/textures/killeffects/} are drawn with this source size (square). */
    private static final float KILL_EFFECT_ICON_TEX_SIZE = 16.0f;
    private static final float BLITZ_STAR_TAB_ICON_TEX_SIZE = 16.0f;

    /**
     * Records Blitz Star usage from chat for TAB icons (all known nick/real aliases).
     */
    public static void registerPlayerBlitzStarUse(String ign, String starDisplayName) {
        if (ign == null || ign.isEmpty() || starDisplayName == null) {
            return;
        }
        String s = starDisplayName.trim();
        if (s.isEmpty()) {
            return;
        }
        for (String ln : getLookupNames(ign.trim())) {
            playerBlitzStarDisplayByLookupLower.put(ln.toLowerCase(Locale.ROOT), s);
        }
        markTabCompositionDirty();
    }

    private static String resolveBlitzStarTextureSlug(String starDisplayName) {
        String k = starDisplayName.toLowerCase(Locale.ROOT).trim();
        String underscored = k.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "").replaceAll("_+", "_");
        String compact = k.replaceAll("[^a-z0-9]", "");
        if (KNOWN_BLITZ_STAR_TEXTURE_SLUGS.contains(underscored)) {
            return underscored;
        }
        if (KNOWN_BLITZ_STAR_TEXTURE_SLUGS.contains(compact)) {
            return compact;
        }
        return underscored.isEmpty() ? compact : underscored;
    }

    /**
     * Resource for {@code star_<slug>.png} under {@code textures/blitzstars/}, or null if disabled / unknown player.
     */
    public static ResourceLocation getBlitzStarTabIconResource(String profileOrLookupName) {
        if (TabStatsRuntimeMute.isMuted()
                || !ConfigManager.isKillCounterBlitzStarTabIconEnabled()
                || profileOrLookupName == null) {
            return null;
        }
        String display = null;
        for (String ln : getLookupNames(profileOrLookupName.trim())) {
            String v = playerBlitzStarDisplayByLookupLower.get(ln.toLowerCase(Locale.ROOT));
            if (v != null && !v.isEmpty()) {
                display = v;
                break;
            }
        }
        if (display == null) {
            return null;
        }
        String slug = resolveBlitzStarTextureSlug(display);
        if (slug == null || slug.isEmpty()) {
            return null;
        }
        return new ResourceLocation("gomod123", "textures/blitzstars/star_" + slug + ".png");
    }

    /**
     * Sorted {@code star_<slug>.png} slugs for settings preview random pick (matches {@link #KNOWN_BLITZ_STAR_TEXTURE_SLUGS}).
     */
    public static String[] getKnownBlitzStarTextureSlugsSorted() {
        ArrayList<String> list = new ArrayList<>(KNOWN_BLITZ_STAR_TEXTURE_SLUGS);
        Collections.sort(list);
        return list.toArray(new String[list.size()]);
    }

    public static void renderTabPrefixDecorations(Minecraft mc, NetworkPlayerInfo rowPlayer, int headX, int headY, int headW,
            int headH) {
        try {
            if (mc == null || rowPlayer == null) {
                return;
            }
            String name = RemoteDisguiseManager.getSourceProfile(rowPlayer).getName();
            if (name == null || name.isEmpty()) {
                return;
            }
            String lookupName = resolveTabLookupName(name);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

            int cursorX = headX;
            if (shouldRenderKillCounterBeforeHead()) {
                String counterText = getTabKillCounterPrefixText(rowPlayer);
                if (counterText != null && !counterText.isEmpty()) {
                    int counterWidth = mc.fontRendererObj.getStringWidth(counterText);
                    int tx = cursorX - counterWidth - 2 + getTabKillersVictimKillCountOffsetPixels(rowPlayer);
                    int ty = headY + (headH - mc.fontRendererObj.FONT_HEIGHT + 1) / 2;
                    mc.fontRendererObj.drawStringWithShadow(counterText, tx, ty, 0xFFFFFF);
                    cursorX = tx;
                }
            }

            int iconSize = Math.max(6, Math.min(Math.max(headW, headH), 12));

            if (getKillEffectTabDisplayMode() == KillEffectTabDisplayMode.ICON && !isPlayerDead(name)) {
                String effect = me.napim.gomod.command.KillEffectCommand.getPlayerEffect(lookupName);
                if (effect != null && !effect.isEmpty() && !effect.equalsIgnoreCase("none")) {
                    Object prefix = getKillEffectIconPrefix(lookupName);
                    if (prefix != null) {
                        int ix = cursorX - iconSize - 2;
                        int iy = headY + (headH - iconSize) / 2;
                        boolean uncertainJoinTimeEffect =
                                me.napim.gomod.command.KillEffectCommand.isPlayerEffectUncertain(lookupName);
                        float effectAlpha = uncertainJoinTimeEffect ? 0.5F : 1.0F;
                        int rgb = ((uncertainJoinTimeEffect ? 0x80 : 0xFF) << 24)
                                | (getKillEffectColor(lookupName) & 0xFFFFFF);
                        if (prefix instanceof ResourceLocation) {
                            ResourceLocation tex = (ResourceLocation) prefix;
                            me.napim.gomod.util.GomodClasspathTextures.bindTexture(mc, tex);
                            int ts = (int) KILL_EFFECT_ICON_TEX_SIZE;
                            GlStateManager.color(1.0F, 1.0F, 1.0F, effectAlpha);
                            Gui.drawScaledCustomSizeModalRect(ix, iy, 0.0f, 0.0f, ts, ts, iconSize, iconSize,
                                    KILL_EFFECT_ICON_TEX_SIZE, KILL_EFFECT_ICON_TEX_SIZE);
                            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        } else if (prefix instanceof String) {
                            String letter = (String) prefix;
                            mc.fontRendererObj.drawStringWithShadow(letter, ix, iy + (iconSize - 8) / 2, rgb);
                        }
                        cursorX = ix;
                    }
                }
            }

            ResourceLocation starRl = getBlitzStarTabIconResource(lookupName);
            if (starRl != null) {
                int sx = cursorX - iconSize - 2;
                int sy = headY + (headH - iconSize) / 2;
                me.napim.gomod.util.GomodClasspathTextures.bindTexture(mc, starRl);
                int sts = (int) BLITZ_STAR_TAB_ICON_TEX_SIZE;
                Gui.drawScaledCustomSizeModalRect(sx, sy, 0.0f, 0.0f, sts, sts, iconSize, iconSize,
                        BLITZ_STAR_TAB_ICON_TEX_SIZE, BLITZ_STAR_TAB_ICON_TEX_SIZE);
            }
        } catch (Throwable ignored) {}
    }

    public static String getTabKillCounterPrefixText(NetworkPlayerInfo rowPlayer) {
        if (rowPlayer == null || !shouldRenderKillCounterBeforeHead()) {
            return null;
        }
        String playerName = RemoteDisguiseManager.getSourceProfile(rowPlayer) != null ? RemoteDisguiseManager.getSourceProfile(rowPlayer).getName() : null;
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        int kills = getKillCount(playerName);
        if (kills < 1) {
            return null;
        }
        boolean isDead = isPlayerDead(playerName);
        int maxKillsAlive = getMaxKillsAlive();
        boolean isTop = !isDead && kills == maxKillsAlive && maxKillsAlive > 0;
        String style = isDead
                ? EnumChatFormatting.BLUE.toString()
                : (EnumChatFormatting.DARK_RED + (isTop ? EnumChatFormatting.BOLD.toString() : ""));
        return style + "[" + kills + "]" + EnumChatFormatting.RESET;
    }

    public static boolean isStatsShowKd() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return statsShowKd;
    }

    public static void setStatsShowKd(boolean show) {
        statsShowKd = show;
        ConfigManager.setKillCounterStatsShowKd(show);
        statsDisplay = ConfigManager.isKillCounterStatsDisplay();
    }

    public static boolean isStatsShowWl() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return statsShowWl;
    }

    public static void setStatsShowWl(boolean show) {
        statsShowWl = show;
        ConfigManager.setKillCounterStatsShowWl(show);
        statsDisplay = ConfigManager.isKillCounterStatsDisplay();
    }

    public static boolean isStatsShowW() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return statsShowW;
    }

    public static void setStatsShowW(boolean show) {
        statsShowW = show;
        ConfigManager.setKillCounterStatsShowW(show);
        statsDisplay = ConfigManager.isKillCounterStatsDisplay();
    }

    /** @deprecated Legacy WL mode; use {@link #setStatsShowWl} / {@link #setStatsShowW}. */
    @Deprecated
    public static int getWlMode() {
        if (!statsDisplay) {
            return 3;
        }
        if (statsShowWl && statsShowW) {
            return 1;
        }
        if (statsShowWl) {
            return 0;
        }
        if (statsShowW) {
            return 2;
        }
        return 3;
    }

    /** @deprecated Legacy WL mode; use per-part toggles. */
    @Deprecated
    public static void setWlMode(int wlMode) {
        int m = Math.max(0, Math.min(3, wlMode));
        switch (m) {
            case 0:
                setStatsShowKd(false);
                setStatsShowWl(true);
                setStatsShowW(false);
                break;
            case 2:
                setStatsShowKd(false);
                setStatsShowWl(false);
                setStatsShowW(true);
                break;
            case 3:
                setStatsDisplay(false);
                break;
            case 1:
            default:
                setStatsShowKd(false);
                setStatsShowWl(true);
                setStatsShowW(true);
                setStatsDisplay(true);
                break;
        }
        ConfigManager.setKillCounterWlMode(m);
    }
    
    public static boolean isShowTeammateKits() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return me.napim.gomod.features.ConfigManager.isKillCounterShowTeammateKits();
    }
    
    public static void setShowTeammateKits(boolean showTeammateKits) {
        me.napim.gomod.features.ConfigManager.setKillCounterShowTeammateKits(showTeammateKits);
    }
    
    public static boolean isShowEnemyKits() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return me.napim.gomod.features.ConfigManager.isKillCounterShowEnemyKits();
    }
    
    public static void setShowEnemyKits(boolean showEnemyKits) {
        me.napim.gomod.features.ConfigManager.setKillCounterShowEnemyKits(showEnemyKits);
    }

    /**
     * Enemy kit observation also feeds detailed end-game rows. Keep collecting for those rows even when the
     * independent TAB option is off; {@link #getEnemyPlayerKit(String)} still owns the TAB visibility gate.
     */
    private static boolean shouldCollectEnemyKits() {
        return isShowEnemyKits()
                || ConfigManager.isBlitzExtendedResultsEnabled()
                && ConfigManager.isBlitzExtendedResultsMorePositionsEnabled();
    }

    /** Prestige II kit marker in TAB ({@link #formatKitLevel} maps {@code ??} → this). */
    public static final String PRESTIGE_TWO_STAR_MARKER = "\u272B\u272B";

    /**
     * Yellow {@code (Kit …)} suffix for TAB QoL settings preview. Enemy kit wins over teammate kit when both are on.
     */
    public static String buildTabStatsSettingsPreviewKitSuffix(boolean showTeammateKits, boolean showEnemyKits) {
        if (showEnemyKits) {
            return EnumChatFormatting.YELLOW + " (Guardian X)";
        }
        if (showTeammateKits) {
            return EnumChatFormatting.YELLOW + " (Speleologist " + PRESTIGE_TWO_STAR_MARKER + ")";
        }
        return "";
    }
    
    public static boolean isShowKeAllChat() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return me.napim.gomod.features.ConfigManager.isKillCounterShowKeAllChat();
    }
    
    public static void setShowKeAllChat(boolean showKeAllChat) {
        me.napim.gomod.features.ConfigManager.setKillCounterShowKeAllChat(showKeAllChat);
    }

    /** Hypixel teams Blitz: {@code [DIST n]} in TAB; sort only when {@link TabListSortGameScope#TEAMS}. */
    private static final Pattern TAB_DISTRICT_NUMBER_PATTERN =
            Pattern.compile("\\[\\s*DIST\\s*(\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    public enum TabListSortGameScope {
        /** Stats sorts for solo Blitz; district sort disabled (no team districts). */
        SOLO,
        /** Teams Blitz only; district TAB sort allowed. */
        TEAMS,
        /** Both queues; district sort disabled (would be wrong in solo). */
        BOTH;

        public static TabListSortGameScope parseConfig(String v) {
            if (v == null || v.trim().isEmpty()) {
                return BOTH;
            }
            switch (v.trim().toUpperCase(Locale.ROOT)) {
                case "SOLO":
                    return SOLO;
                case "TEAMS":
                case "TEAM":
                    return TEAMS;
                case "BOTH":
                case "SOLO_AND_TEAMS":
                case "SOLO+TEAMS":
                default:
                    return BOTH;
            }
        }

        public TabListSortGameScope next() {
            switch (this) {
                case SOLO:
                    return TEAMS;
                case TEAMS:
                    return BOTH;
                default:
                    return SOLO;
            }
        }

        public String toConfigValue() {
            switch (this) {
                case SOLO:
                    return "SOLO";
                case TEAMS:
                    return "TEAMS";
                default:
                    return "BOTH";
            }
        }
    }

    public enum TabListSortMode {
        OFF,
        KILLS_THEN_WLR,
        WLR_THEN_KILLS,
        KILLS_THEN_WINS,
        WINS_THEN_KILLS,
        KILLS_THEN_OVERALL_KILLS,
        DISTRICT;

        public static TabListSortMode parseConfig(String v) {
            if (v == null || v.trim().isEmpty()) {
                return OFF;
            }
            switch (v.trim().toUpperCase(Locale.ROOT)) {
                case "KILLS":
                case "KILL":
                    return KILLS_THEN_WLR;
                case "WLR":
                case "WL":
                    return WLR_THEN_KILLS;
                case "WINS":
                case "WIN":
                    return WINS_THEN_KILLS;
                case "KILLS_THEN_WLR":
                case "KILLS_WLR":
                    return KILLS_THEN_WLR;
                case "WLR_THEN_KILLS":
                case "WLR_KILLS":
                    return WLR_THEN_KILLS;
                case "KILLS_THEN_WINS":
                case "KILLS_WINS":
                    return KILLS_THEN_WINS;
                case "WINS_THEN_KILLS":
                case "WINS_KILLS":
                    return WINS_THEN_KILLS;
                case "KILLS_THEN_OVERALL_KILLS":
                case "KILLS_OVERALL_KILLS":
                case "KILLS_OVERALL":
                    return KILLS_THEN_OVERALL_KILLS;
                case "DISTRICT":
                case "DIST":
                case "DISTRICTS":
                    return DISTRICT;
                default:
                    return OFF;
            }
        }

        /** GUI cycle of paired stat modes; district sorting has its own toggle. */
        public TabListSortMode nextInCycle() {
            switch (this) {
                case OFF:
                    return KILLS_THEN_WLR;
                case KILLS_THEN_WLR:
                    return WLR_THEN_KILLS;
                case WLR_THEN_KILLS:
                    return KILLS_THEN_WINS;
                case KILLS_THEN_WINS:
                    return WINS_THEN_KILLS;
                case WINS_THEN_KILLS:
                    return KILLS_THEN_OVERALL_KILLS;
                case KILLS_THEN_OVERALL_KILLS:
                    return OFF;
                case DISTRICT:
                default:
                    return OFF;
            }
        }

        public String toConfigValue() {
            switch (this) {
                case KILLS_THEN_WLR:
                    return "KILLS_THEN_WLR";
                case WLR_THEN_KILLS:
                    return "WLR_THEN_KILLS";
                case KILLS_THEN_WINS:
                    return "KILLS_THEN_WINS";
                case WINS_THEN_KILLS:
                    return "WINS_THEN_KILLS";
                case KILLS_THEN_OVERALL_KILLS:
                    return "KILLS_THEN_OVERALL_KILLS";
                case DISTRICT:
                    return "DISTRICT";
                default:
                    return "OFF";
            }
        }
    }

    public static boolean isDistrictSortAllowedForTabGameScope() {
        // Your request: district sorting should be disabled in Teams and Solo+Teams.
        return getTabListSortGameScope() == TabListSortGameScope.SOLO;
    }

    public static TabListSortGameScope getTabListSortGameScope() {
        // TAB list sort is now SOLO-only for stats ordering; spectators-under-all still
        // applies in teams via deathmatch tab logic, independent of this scope.
        return TabListSortGameScope.SOLO;
    }

    public static void setTabListSortGameScope(TabListSortGameScope scope) {
        if (scope == null) {
            scope = TabListSortGameScope.BOTH;
        }
        ConfigManager.setKillCounterTabListSortGameScope(scope.toConfigValue());
    }

    /**
     * Cycles through the paired TAB sort modes. District grouping is controlled by its own toggle.
     */
    public static TabListSortMode advanceTabListSortMode() {
        TabListSortMode next = getTabListSortMode().nextInCycle();
        setTabListSortMode(next);
        return next;
    }

    public static TabListSortMode getTabListSortMode() {
        if (TabStatsRuntimeMute.isMuted()) {
            return TabListSortMode.OFF;
        }
        TabListSortMode m = TabListSortMode.parseConfig(ConfigManager.getKillCounterTabListSortMode());
        if (m == TabListSortMode.DISTRICT) {
            // DIST is now controlled by the dedicated toggle button, not the sort-cycle mode.
            return TabListSortMode.OFF;
        }
        return m;
    }

    /** Apply the selected stats ordering only in Solo Blitz; keep the saved GUI selection independent. */
    public static TabListSortMode getTabListSortModeForCurrentGame() {
        TabListSortMode selected = getTabListSortMode();
        if (selected == TabListSortMode.OFF) return selected;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null
                || !GameContext.isBlitzTabListCustomSortContext(mc)
                || GameContext.isHypixelReplayWatching(mc)
                || BlitzTeamsDetector.isBlitzTeamsTabContext(mc)) {
            return TabListSortMode.OFF;
        }
        return selected;
    }

    public static void setTabListSortMode(TabListSortMode mode) {
        if (mode == null) {
            mode = TabListSortMode.OFF;
        }
        if (mode == TabListSortMode.DISTRICT) {
            mode = TabListSortMode.OFF;
        }
        ConfigManager.setKillCounterTabListSortMode(mode.toConfigValue());
    }

    public static boolean isDistrictTabListSortEnabled() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return ConfigManager.isKillCounterTabListSortDistrictEnabled() && isDistrictSortAllowedForTabGameScope();
    }

    /**
     * Quick O(n·prefix-len) scan: returns {@code true} if the current scoreboard contains any team whose
     * plain prefix includes {@code [DIST}.  Used to skip the expensive district sort entirely in solo Blitz
     * (where no district brackets exist) without having to call the full
     * {@link #tabSortPlainMergedForDistrict} chain per player.
     */
    private static boolean hasDistrictBracketsInCurrentScoreboard() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return false;
        net.minecraft.scoreboard.Scoreboard sb = mc.theWorld.getScoreboard();
        if (sb == null) return false;
        for (net.minecraft.scoreboard.ScorePlayerTeam team : sb.getTeams()) {
            String prefix = team.getColorPrefix();
            if (prefix == null) continue;
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(prefix);
            if (plain != null && plain.contains("[DIST")) return true;
        }
        return false;
    }

    private static String tabSortPlainMergedForDistrict(NetworkPlayerInfo info) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null) {
            return "";
        }
        String base = RemoteDisguiseManager.getSourceProfile(info).getName();
        String resolved = resolveTabServerFormattedBase(info, base);
        String teamFmt = "";
        try {
            teamFmt = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), base);
        } catch (Throwable ignored) {
        }
        String merged = mergeTeamDistBracketPrefixIfMissing(resolved, teamFmt);
        merged = normalizeBlitzTabDistBracketChunks(merged);
        merged = ensureSpaceAfterLeadingBracketChainBeforeBody(merged);
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(merged);
        return plain != null ? plain : "";
    }

    private static int tabSortDistrictIndex(NetworkPlayerInfo info) {
        String plain = tabSortPlainMergedForDistrict(info);
        Matcher m = TAB_DISTRICT_NUMBER_PATTERN.matcher(plain);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }

    public static boolean isSortTabByKills() {
        TabListSortMode mode = getTabListSortMode();
        return mode == TabListSortMode.KILLS_THEN_WLR
                || mode == TabListSortMode.KILLS_THEN_WINS
                || mode == TabListSortMode.KILLS_THEN_OVERALL_KILLS;
    }

    public static void setSortTabByKills(boolean enabled) {
        setTabListSortMode(enabled ? TabListSortMode.KILLS_THEN_WLR : TabListSortMode.OFF);
    }

    /**
     * Deathmatch TAB row filter + optional sort for the player list overlay. Called from a {@code renderPlayerlist}
     * redirect on {@code getPlayerInfoMap} so ordering matches vanilla’s TAB iteration.
     * During an active Blitz match, {@link #applyBlitzTabAliveBeforeDeadOrdering} runs after custom sorts so alive
     * players stay above eliminated / spectator rows; vanilla sort OFF is handled in {@code PlayerListMixin}.
     */
    public static boolean isBlitzTabListCustomSortContext() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && GameContext.isBlitzTabListCustomSortContext(mc);
    }

    /**
     * TAB QoL runtime (compose, sort, kill suffix). The data and parsers are Blitz-specific;
     * running them in SkyWars/UHC turns their much larger team/TAB packet streams into needless
     * recomposition and can retain the previous Blitz match's state.
     */
    private static boolean isTabQolRuntimeContext(Minecraft mc) {
        return mc != null
                && GameContext.isOnHypixel(mc)
                && (GameContext.isHypixelLobby(mc) || GameContext.isBlitzTabListCustomSortContext(mc));
    }

    /** Public cheap gate for packet listeners that otherwise dirty the Blitz composer in every mode. */
    public static boolean shouldHandleTabQolPackets(Minecraft mc) {
        return isTabQolRuntimeContext(mc);
    }

    private static void handleTabQolContextTransition(Minecraft mc) {
        net.minecraft.world.World world = mc == null ? null : mc.theWorld;
        boolean active = isTabQolRuntimeContext(mc);
        if (world == lastTabQolContextWorld && active == lastTabQolContextActive) {
            return;
        }

        boolean worldChanged = world != lastTabQolContextWorld;
        boolean leftTabQolContext = lastTabQolContextActive && !active;
        lastTabQolContextWorld = world;
        lastTabQolContextActive = active;

        if (worldChanged || leftTabQolContext) {
            // This method intentionally works even when runtime enabled is already false.
            // It clears match maps, synthetic/dead rows, composed lines, and sort caches.
            stopTemporarily();
            clearTabOverlayWorldCaches();
        }
        if (active) {
            clearTabFormattingCaches();
            markTabCompositionDirty();
        }
    }

    private static void clearTabOverlayWorldCaches() {
        tabTeamsPacketDirtyCoalesceWorld = null;
        tabTeamsPacketDirtyCoalesceWorldTime = Long.MIN_VALUE;
        tabOverlayFinalizeCacheWorld = null;
        tabOverlayFinalizeCacheWorldTick = Long.MIN_VALUE;
        tabOverlaySortResultCache = null;
        tabOverlaySortResultCacheWorld = null;
        tabOverlaySortResultCacheTick = Long.MIN_VALUE;
        tabOverlaySortResultCacheMode = TabListSortMode.OFF;
        tabOverlaySortResultCacheDistrict = false;
        tabOverlayRenderDepth = 0;
        tabOverlayRenderThread = null;
        tabOverlayProfileAliases.clear();
        tabRightAlignedStatsColumnWidth = -1;
    }

    public static Collection<NetworkPlayerInfo> finalizeTabListForOverlayRender(Collection<NetworkPlayerInfo> raw) {
        long timer = TabPerformanceDebug.begin();
        long renderTimer = RenderProfiler.begin("TAB.finalizeRows");
        try { return finalizeTabListForOverlayRenderTimed(raw); }
        finally {
            TabPerformanceDebug.end(TabPerformanceDebug.Metric.FINALIZE_ROWS, timer);
            RenderProfiler.end("TAB.finalizeRows", renderTimer);
        }
    }

    private static Collection<NetworkPlayerInfo> finalizeTabListForOverlayRenderTimed(Collection<NetworkPlayerInfo> raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        raw = me.napim.gomod.gui.MountPreviewHelper.filterGuiPreviewSyntheticFromTabList(raw);
        Minecraft mcFin = Minecraft.getMinecraft();
        if (!isTabQolRuntimeContext(mcFin)) {
            return raw;
        }
        applyPendingTabCompositionBeforeOverlayRender();
        Collection<NetworkPlayerInfo> base = me.napim.gomod.features.DeathmatchTabCleaner.filterPlayerInfoCollection(raw);
        base = suppressBlitzPregameDeadGhostRows(base);
        net.minecraft.world.World wFin = mcFin != null ? mcFin.theWorld : null;
        if (wFin != null) {
            long tick = wFin.getTotalWorldTime();
            if (wFin != tabOverlayFinalizeCacheWorld || tick != tabOverlayFinalizeCacheWorldTick) {
                tabOverlayFinalizeCacheWorld = wFin;
                tabOverlayFinalizeCacheWorldTick = tick;
                tabRightAlignedStatsColumnWidth = -1;
                cacheObservedTabAppearances(base);
            }
        } else {
            tabOverlayFinalizeCacheWorld = null;
            cacheObservedTabAppearances(base);
        }
        base = augmentTabListWithSyntheticDeadRowsIfNeeded(base);
        base = suppressBlitzDeadSpectatorRowsForLiveLocal(base, mcFin);
        if (!isBlitzTabListCustomSortContext()) {
            return base;
        }
        TabListSortMode mode = getTabListSortModeForCurrentGame();
        boolean districtEnabled = isDistrictTabListSortEnabled();
        final boolean requestedDistrictEnabled = districtEnabled;
        if (!districtEnabled && mode == TabListSortMode.OFF) {
            tabOverlaySortResultCache = null; // invalidate so next enable gets a fresh sort
            return base;
        }
        // Per-tick sort cache: sorting is O(n log n) × expensive string ops; re-use within same tick.
        if (wFin != null) {
            long sortTick = wFin.getTotalWorldTime();
            if (wFin == tabOverlaySortResultCacheWorld && sortTick == tabOverlaySortResultCacheTick
                    && mode == tabOverlaySortResultCacheMode && districtEnabled == tabOverlaySortResultCacheDistrict
                    && tabOverlaySortResultCache != null) {
                return tabOverlaySortResultCache;
            }
        }
        // If district sort is enabled but there are no [DIST n] brackets in the current scoreboard
        // (e.g. solo Blitz), disable district sorting for this computation — avoids O(n log n)
        // string-chain calls that return MAX_VALUE for every player and accomplish nothing.
        if (districtEnabled && !hasDistrictBracketsInCurrentScoreboard()) {
            districtEnabled = false;
            if (mode == TabListSortMode.OFF) {
                tabOverlaySortResultCache = null;
                return base;
            }
        }
        ArrayList<NetworkPlayerInfo> list = new ArrayList<>(base.size());
        for (NetworkPlayerInfo info : base) {
            list.add(info);
        }
        if (list.size() <= 1) {
            return base;
        }
        if (districtEnabled) {
            list.sort((a, b) -> {
                int da = tabSortDistrictIndex(a);
                int db = tabSortDistrictIndex(b);
                int cmp = Integer.compare(da, db);
                if (cmp != 0) {
                    return cmp;
                }
                return compareByTabListSortMode(a, b, mode);
            });
        } else {
            switch (mode) {
                case KILLS_THEN_WLR:
                    list.sort((a, b) -> compareByTabListSortMode(a, b, mode));
                    break;
                case WLR_THEN_KILLS:
                    list.sort((a, b) -> compareByTabListSortMode(a, b, mode));
                    break;
                case KILLS_THEN_WINS:
                    list.sort((a, b) -> compareByTabListSortMode(a, b, mode));
                    break;
                case WINS_THEN_KILLS:
                    list.sort((a, b) -> compareByTabListSortMode(a, b, mode));
                    break;
                case KILLS_THEN_OVERALL_KILLS:
                    list.sort((a, b) -> compareByTabListSortMode(a, b, mode));
                    break;
                default:
                    return base;
            }
        }
        // Missing-stat/nick priority belongs inside the comparator, after match kills in kills-first modes.
        // A final global partition here would incorrectly lift zero-kill nicks above players with kills.
        Collection<NetworkPlayerInfo> sortResult = applyBlitzTabAliveBeforeDeadOrdering(list);
        // Store in per-tick cache so subsequent render frames within the same tick skip the sort.
        if (wFin != null) {
            tabOverlaySortResultCacheWorld = wFin;
            tabOverlaySortResultCacheTick = wFin.getTotalWorldTime();
            tabOverlaySortResultCacheMode = mode;
            tabOverlaySortResultCacheDistrict = requestedDistrictEnabled;
            tabOverlaySortResultCache = sortResult;
        }
        return sortResult;
    }

    public static List<NetworkPlayerInfo> applyTabOverlayRendererListSortFallback(List<NetworkPlayerInfo> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        if (tabOverlayRenderDepth > 0) {
            List<NetworkPlayerInfo> cached = tabOverlayRendererRows.get(rows);
            if (cached != null) return cached;
        }
        try {
            Collection<NetworkPlayerInfo> ordered = finalizeTabListForOverlayRender(rows);
            List<NetworkPlayerInfo> rendererRows;
            if (ordered == null) {
                rendererRows = rows;
            } else if (ordered == rows) {
                rendererRows = rows;
            } else if (ordered instanceof List) {
                rendererRows = (List<NetworkPlayerInfo>) ordered;
            } else {
                rendererRows = new ArrayList<NetworkPlayerInfo>(ordered);
            }
            // Lunar can sort/rebuild replay rows outside vanilla's comparator hook; preserve the viewer's
            // first-row priority at this shared fallback boundary too.
            rendererRows = LocalDisguise.prioritizeReplayViewerTabRow(rendererRows);
            // Lunar can rebuild its TAB row list without passing through Ordering.sortedCopy,
            // where the normal renderer path applies /tabvictims grouping. Apply it at this
            // fallback boundary as well so the feature survives Lunar's custom list rebuild.
            List<NetworkPlayerInfo> result = applyTabKillersGroupingIfEnabled(rendererRows);
            if (tabOverlayRenderDepth > 0 && result != null) {
                tabOverlayRendererRows.put(rows, result);
                tabOverlayRendererRows.put(result, result);
            }
            return result;
        } catch (Throwable ignored) {
            return rows;
        }
    }

    @SuppressWarnings("rawtypes")
    public static Object getTabOverlayRenderedRowFallback(List rows, int index) {
        try {
            if (rows != null && !rows.isEmpty()) {
                Object first = rows.get(0);
                if (first instanceof NetworkPlayerInfo) {
                    @SuppressWarnings("unchecked")
                    List<NetworkPlayerInfo> typed = (List<NetworkPlayerInfo>) rows;
                    List<NetworkPlayerInfo> ordered = applyTabOverlayRendererListSortFallback(typed);
                    if (ordered != null && index >= 0 && index < ordered.size()) {
                        return ordered.get(index);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return rows.get(index);
    }

    /** Lunar shares this clipped list with its row backgrounds; use the same snapshot as head/name lookup. */
    public static List<NetworkPlayerInfo> getTabOverlayRenderedSubList(List<NetworkPlayerInfo> rows,
            int from, int to) {
        List<NetworkPlayerInfo> ordered = applyTabOverlayRendererListSortFallback(rows);
        int size = ordered.size();
        int start = Math.max(0, Math.min(from, size));
        List<NetworkPlayerInfo> clipped = ordered.subList(start, Math.max(start, Math.min(to, size)));
        // Do not regroup this clipped view: a killer outside the viewport must not change a victim's depth.
        if (tabOverlayRenderDepth > 0) tabOverlayRendererRows.put(clipped, clipped);
        return clipped;
    }

    /**
     * The Fixes toggle is user-facing as "Force-clean dead players": while the local player is still alive in
     * a live Blitz match, remove eliminated/spectator rows instead of merely dimming or sorting them.
     */
    public static boolean shouldSuppressDeadSpectatorsForLiveLocal(Minecraft mc) {
        if (!ConfigManager.isHypixelTabQuitGhostHideEnabled()) {
            return false;
        }
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return false;
        }
        if (!(GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))) {
            return false;
        }
        return !isLocalBlitzEliminatedOrSpectatingTabSignals(mc);
    }

    private static Collection<NetworkPlayerInfo> suppressBlitzDeadSpectatorRowsForLiveLocal(
            Collection<NetworkPlayerInfo> base, Minecraft mc) {
        if (base == null || base.isEmpty() || !shouldSuppressDeadSpectatorsForLiveLocal(mc)) {
            return base;
        }
        ArrayList<NetworkPlayerInfo> filtered = null;
        int index = 0;
        for (NetworkPlayerInfo info : base) {
            boolean suppress = shouldSuppressBlitzDeadSpectatorTabRowForLiveLocal(info, mc);
            if (suppress) {
                if (filtered == null) {
                    filtered = new ArrayList<NetworkPlayerInfo>(Math.max(0, base.size() - 1));
                    int copyIndex = 0;
                    for (NetworkPlayerInfo previous : base) {
                        if (copyIndex++ >= index) {
                            break;
                        }
                        filtered.add(previous);
                    }
                }
            } else if (filtered != null) {
                filtered.add(info);
            }
            index++;
        }
        return filtered == null ? base : filtered;
    }

    private static boolean shouldSuppressBlitzDeadSpectatorTabRowForLiveLocal(NetworkPlayerInfo info, Minecraft mc) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null) {
            return false;
        }
        String base = tabSortProfileName(info);
        if (base.isEmpty() || isLocalPlayerTabRow(base)) {
            return false;
        }
        // TAB victims intentionally keeps eliminated rows. Force-clean must not erase those rows
        // while the viewer is alive; its world-entity suppression still uses the separate public gate.
        if (isTabKillersStyleVictim(info, mc)) {
            return false;
        }
        return tabSortTreatAsDeadSpectator(info, mc);
    }

    /**
     * API workers can finish between the periodic compose ticks. Apply their pending stats/guild changes on the
     * client render thread as soon as TAB is actually drawn, so a settings-button click is never needed to refresh it.
     */
    private static void applyPendingTabCompositionBeforeOverlayRender() {
        if (!tabCompositionDirty) {
            return;
        }
        tabCompositionDirty = false;
        long now = System.currentTimeMillis();
        lastTabCompositionApplyMs = now;
        runOnClientThread(KillCounter::updateAllTabNames);
    }

    private static final long TAB_OVERLAY_COMPOSE_INTERVAL_MS = 1000L;
    private static long lastOverlayTriggeredTabComposeMs = 0L;
    private static int tabOverlayRenderDepth = 0;
    private static Thread tabOverlayRenderThread;
    private static final Map<GameProfile, String> tabOverlayProfileAliases = new IdentityHashMap<>();
    private static final ThreadLocal<NetworkPlayerInfo> tabOverlayCurrentRow = new ThreadLocal<>();
    /** Cache victim classification and the derived layout values while one TAB row is being rendered. */
    private static final ThreadLocal<TabVictimRenderState> tabVictimRenderState = new ThreadLocal<>();
    private static final Map<List<NetworkPlayerInfo>, List<NetworkPlayerInfo>> tabOverlayRendererRows = new IdentityHashMap<>();
    private static final AtomicLong tabDebugOverlayPasses = new AtomicLong();
    private static final AtomicLong tabDebugRowCaptures = new AtomicLong();
    private static final AtomicLong tabDebugLunarDrawCalls = new AtomicLong();
    private static final AtomicLong tabDebugLunarVictimDrawCalls = new AtomicLong();
    private static final AtomicLong tabDebugLunarLogoDrawCalls = new AtomicLong();
    private static final AtomicLong tabDebugLunarLogoVictimOffsetsApplied = new AtomicLong();
    private static final AtomicLong tabDebugFontTabTextCalls = new AtomicLong();
    private static final AtomicLong tabDebugFontTabRowMatches = new AtomicLong();
    private static final AtomicLong tabDebugNativeStatsRowMatches = new AtomicLong();
    private static final AtomicLong tabDebugNativeStatsReplacements = new AtomicLong();
    private static final AtomicLong tabDebugTextOffsetCalls = new AtomicLong();
    private static final AtomicLong tabDebugTextOffsetsApplied = new AtomicLong();
    private static final AtomicLong tabDebugColorCalls = new AtomicLong();
    private static final AtomicLong tabDebugVictimColorsApplied = new AtomicLong();
    private static final AtomicLong tabDebugWidthCalls = new AtomicLong();
    private static final AtomicLong tabDebugVictimWidthsReserved = new AtomicLong();
    private static final AtomicLong tabDebugVictimHeadsDrawn = new AtomicLong();
    private static volatile int tabDebugLastHeadSourceX = Integer.MIN_VALUE;
    private static volatile int tabDebugLastHeadRenderedX = Integer.MIN_VALUE;
    private static volatile String tabDebugLastHeadIdentity;
    private static volatile float tabDebugLastTextSourceX = Float.NaN;
    private static volatile float tabDebugLastTextRenderedX = Float.NaN;
    private static volatile String tabDebugLastTextIdentity;
    private static volatile String tabDebugLastPreparedVictimText;
    private static volatile int tabDebugLastVictimColor;
    private static int tabRightAlignedStatsColumnWidth = -1;
    private static final ThreadLocal<Boolean> suppressTabRenderedTextDecoration = new ThreadLocal<>();

    private static final class TabVictimRenderState {
        private NetworkPlayerInfo row;
        private boolean victimResolved;
        private boolean victim;
        private boolean profileNameResolved;
        private String profileName;
        private boolean disguiseNameResolved;
        private String disguiseName;
        private boolean headOffsetResolved;
        private int headOffset;
        private boolean retainedResolved;
        private boolean retained;
        private boolean dimResolved;
        private boolean dim;

        private void reset(NetworkPlayerInfo nextRow) {
            row = nextRow;
            victimResolved = false;
            victim = false;
            profileNameResolved = false;
            profileName = null;
            disguiseNameResolved = false;
            disguiseName = null;
            headOffsetResolved = false;
            headOffset = 0;
            retainedResolved = false;
            retained = false;
            dimResolved = false;
            dim = false;
        }
    }
    /**
     * GuiNewChat wraps a newly inserted component by repeatedly querying FontRenderer widths. Lunar can keep the
     * vanilla TAB render scope open around other overlay work, so those chat width calls must never enter the TAB
     * row scanner/right-alignment fallback.
     */
    private static final ThreadLocal<Integer> chatFontWorkDepth = new ThreadLocal<>();

    public static void beginChatFontWork() {
        Integer depth = chatFontWorkDepth.get();
        chatFontWorkDepth.set(depth == null ? 1 : depth + 1);
    }

    public static void endChatFontWork() {
        Integer depth = chatFontWorkDepth.get();
        if (depth == null || depth <= 1) {
            chatFontWorkDepth.remove();
        } else {
            chatFontWorkDepth.set(depth - 1);
        }
    }

    private static boolean isChatFontWorkActive() {
        Integer depth = chatFontWorkDepth.get();
        return depth != null && depth > 0;
    }

    public static void beginTabOverlayRender() {
        tabDebugOverlayPasses.incrementAndGet();
        if (tabOverlayRenderDepth == 0) {
            tabOverlayRenderThread = Thread.currentThread();
            tabOverlayProfileAliases.clear();
            tabOverlayRendererRows.clear();
            clearCurrentTabOverlayRow();
        }
        tabOverlayRenderDepth++;
    }

    public static void endTabOverlayRender() {
        if (tabOverlayRenderDepth > 0) {
            tabOverlayRenderDepth--;
        }
        if (tabOverlayRenderDepth == 0) {
            tabOverlayRenderThread = null;
            tabOverlayProfileAliases.clear();
            tabOverlayRendererRows.clear();
            clearCurrentTabOverlayRow();
        }
    }

    /** Lunar repeatedly reads the same profiles while sorting, measuring and drawing the TAB rows. */
    public static String getLunarTabProfileAlias(GameProfile profile) {
        if (profile == null || profile.getId() == null) return null;
        boolean scoped = tabOverlayRenderThread == Thread.currentThread();
        if (!scoped) return resolveLunarTabProfileAlias(profile);
        // Cache both hits and misses until the overlay returns. The next draw sees new profiles and expirations.
        if (!tabOverlayProfileAliases.containsKey(profile)) {
            tabOverlayProfileAliases.put(profile, resolveLunarTabProfileAlias(profile));
        }
        return tabOverlayProfileAliases.get(profile);
    }

    private static String resolveLunarTabProfileAlias(GameProfile profile) {
        if (LocalDisguise.shouldApplyTabDisplayOverride() && LocalDisguise.matchesLocalProfileFast(profile)) {
            return LocalDisguise.fastTargetNameText;
        }
        LocalDisguise.RemoteDisguiseProfile manual = RemoteDisguiseManager.getManualDisguiseTarget(profile);
        if (manual != null) return manual.name;
        GomodPeerDisguise.PeerName peer = GomodPeerDisguise.getPeerName(profile);
        return peer == null ? null : peer.targetName;
    }

    public static void setCurrentTabOverlayRow(NetworkPlayerInfo row) {
        if (row == null) {
            clearCurrentTabOverlayRow();
            return;
        }
        tabDebugRowCaptures.incrementAndGet();
        // Lunar can invoke its merged TAB row helper outside the vanilla renderPlayerlist scope. The
        // PlayerListMixin calls this only while resolving/drawing a TAB row, so keep that row available
        // to the merged Lunar draw/width hooks even when tabOverlayRenderDepth is zero.
        tabOverlayCurrentRow.set(row);
        TabVictimRenderState state = tabVictimRenderState.get();
        if (state == null) {
            state = new TabVictimRenderState();
            tabVictimRenderState.set(state);
        }
        state.reset(row);
    }

    public static void clearCurrentTabOverlayRow() {
        tabOverlayCurrentRow.remove();
        TabVictimRenderState state = tabVictimRenderState.get();
        if (state != null) state.reset(null);
    }

    public static NetworkPlayerInfo currentTabOverlayRow() {
        return tabOverlayCurrentRow.get();
    }

    public static boolean isChatFontRenderActive() {
        return isChatFontWorkActive();
    }

    private static TabVictimRenderState currentTabVictimRenderState(NetworkPlayerInfo row) {
        TabVictimRenderState state = tabVictimRenderState.get();
        return state != null && state.row == row ? state : null;
    }

    public static boolean isTabVictimsDisplayRow(NetworkPlayerInfo row) {
        return isRenderedTabVictim(row, Minecraft.getMinecraft());
    }

    private static boolean isRenderedTabVictim(NetworkPlayerInfo row, Minecraft mc) {
        TabVictimRenderState state = currentTabVictimRenderState(row);
        if (state == null) return isTabKillersStyleVictim(row, mc);
        if (!state.victimResolved) {
            state.victim = isTabKillersStyleVictim(row, mc);
            state.victimResolved = true;
        }
        return state.victim;
    }

    private static String renderedTabProfileName(NetworkPlayerInfo row) {
        TabVictimRenderState state = currentTabVictimRenderState(row);
        if (state == null) return tabSortProfileName(row);
        if (!state.profileNameResolved) {
            state.profileName = tabSortProfileName(row);
            state.profileNameResolved = true;
        }
        return state.profileName == null ? "" : state.profileName;
    }

    /**
     * Name Lunar may draw for this row after a display-only disguise. Keep it separate from the source profile name:
     * TAB Victims ownership/order must continue to use the real player identity, while text-layout matching must
     * recognize the visible alias so the name stays aligned with its shifted head and Lunar logo.
     */
    private static String renderedTabDisguiseName(NetworkPlayerInfo row) {
        if (row == null) return null;
        TabVictimRenderState state = currentTabVictimRenderState(row);
        if (state != null && state.disguiseNameResolved) return state.disguiseName;

        String targetName = null;
        GameProfile source = RemoteDisguiseManager.getSourceProfile(row);
        if (source != null) {
            if (LocalDisguise.shouldApplyTabDisplayOverride()
                    && LocalDisguise.matchesLocalProfileFast(source)) {
                targetName = LocalDisguise.fastTargetNameText;
            }
            if (targetName == null || targetName.trim().isEmpty()) {
                LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getDisguisedTargetProfile(source);
                targetName = target == null ? null : target.name;
            }
        }
        if (targetName != null) {
            targetName = targetName.trim();
            if (targetName.isEmpty() || targetName.equalsIgnoreCase(tabSortProfileName(row))) {
                targetName = null;
            }
        }
        if (state != null) {
            state.disguiseName = targetName;
            state.disguiseNameResolved = true;
        }
        return targetName;
    }

    private static boolean tabRenderedTextLooksLikeRow(NetworkPlayerInfo row, String plain) {
        String sourceName = renderedTabProfileName(row);
        if (tabRenderedTextLooksLikeRowForBase(plain, sourceName)) return true;
        String disguiseName = renderedTabDisguiseName(row);
        return disguiseName != null && tabRenderedTextLooksLikeRowForBase(plain, disguiseName);
    }

    private static int renderedTabVictimHeadOffsetPixels(NetworkPlayerInfo row, Minecraft mc) {
        TabVictimRenderState state = currentTabVictimRenderState(row);
        if (state != null && state.headOffsetResolved) return state.headOffset;
        int offset = 0;
        if (row != null && isRenderedTabVictim(row, mc)) {
            String base = renderedTabProfileName(row);
            if (!base.isEmpty()) {
                Integer depth = tabKillersIndentDepthByLower.get(base.toLowerCase(Locale.ROOT));
                if (depth != null && depth.intValue() > 0) {
                    offset = depth.intValue() * ConfigManager.getKillCounterTabKillersVictimOffsetPx();
                }
            }
        }
        if (state != null) {
            state.headOffset = offset;
            state.headOffsetResolved = true;
        }
        return offset;
    }

    /** Called inside Lunar's merged row-draw helper, before its logo and name draw. */
    public static String prepareCurrentTabVictimText(String text) {
        TabPerformanceDebug.onNativeTabRow();
        long timer = TabPerformanceDebug.beginTab();
        try { return prepareCurrentTabVictimTextTimed(text); }
        finally { TabPerformanceDebug.end(TabPerformanceDebug.Metric.TAB_VICTIM_TEXT, timer); }
    }

    private static String prepareCurrentTabVictimTextTimed(String text) {
        NetworkPlayerInfo row = tabOverlayCurrentRow.get();
        tabDebugLunarDrawCalls.incrementAndGet();
        String nativeText = prepareNativeTabRowText(row, text);
        nativeText = BlitzChatNameDisplay.decorateTab(row, nativeText);
        String prepared = applyTabKillersStyleToRenderedName(row, nativeText);
        if (isRenderedTabVictim(row, Minecraft.getMinecraft())) {
            tabDebugLunarVictimDrawCalls.incrementAndGet();
            tabDebugLastPreparedVictimText = prepared;
        }
        return prepared;
    }

    /**
     * The public Lunar TAB renderer draws from its merged native helper. FontRenderer's common hooks can see the
     * row for width/layout work without being the final painted text on every Lunar version, so make sure this
     * actual draw callback receives the composed cached line whenever its stats suffix is missing.
     */
    private static String prepareNativeTabRowText(NetworkPlayerInfo row, String text) {
        if (!GomodFixes.isWeaveRuntime() || row == null || text == null || text.isEmpty()) {
            return text;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isTabQolRuntimeContext(mc) || TabStatsRuntimeMute.isMuted()) {
            return text;
        }
        String stats = tabStatsSegmentByRowKey.get(tabRowKey(row));
        if (stats == null || stats.isEmpty()) {
            return text;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (!tabRenderedTextLooksLikeRow(row, plain)) {
            return text;
        }
        tabDebugNativeStatsRowMatches.incrementAndGet();

        String composed = getRenderedTabLineForInfo(row);
        if (composed == null || composed.isEmpty() || splitTabStatsForDraw(composed, stats) == null) {
            return text;
        }
        String plainStats = EnumChatFormatting.getTextWithoutFormattingCodes(stats);
        boolean alreadyHasStats = plain != null && plainStats != null && !plainStats.isEmpty()
                && plain.contains(plainStats);
        String selected = TabStatsLayoutMode.chooseNativeRowText(
                text, composed, true, true, alreadyHasStats);
        if (selected != text && !selected.equals(text)) {
            tabDebugNativeStatsReplacements.incrementAndGet();
        }
        return selected;
    }

    public static float offsetCurrentTabVictimTextX(float x) {
        return x + getTabKillersVictimHeadOffsetPixels(tabOverlayCurrentRow.get());
    }

    /** Lunar draws its logo in a separate native renderer from the merged name row; align it with the moved head/name. */
    public static float offsetCurrentTabVictimLogoX(float x) {
        tabDebugLunarLogoDrawCalls.incrementAndGet();
        NetworkPlayerInfo row = tabOverlayCurrentRow.get();
        int offset = getTabKillersVictimHeadOffsetPixels(row);
        if (offset > 0) tabDebugLunarLogoVictimOffsetsApplied.incrementAndGet();
        return x + offset;
    }

    /** Only move the actual player label; Lunar may call its merged text helper for row decorations too. */
    public static float offsetCurrentTabVictimTextX(float x, String text) {
        long timer = TabPerformanceDebug.beginTab();
        try { return offsetCurrentTabVictimTextXTimed(x, text); }
        finally { TabPerformanceDebug.end(TabPerformanceDebug.Metric.TAB_VICTIM_OFFSET, timer); }
    }

    private static float offsetCurrentTabVictimTextXTimed(float x, String text) {
        NetworkPlayerInfo row = tabOverlayCurrentRow.get();
        tabDebugTextOffsetCalls.incrementAndGet();
        Minecraft mc = Minecraft.getMinecraft();
        if (text == null || !isRenderedTabVictim(row, mc)) {
            return x;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (!tabRenderedTextLooksLikeRow(row, plain)) {
            return x;
        }
        tabDebugTextOffsetsApplied.incrementAndGet();
        float renderedX = x + renderedTabVictimHeadOffsetPixels(row, mc);
        tabDebugLastTextIdentity = renderedTabProfileName(row);
        tabDebugLastTextSourceX = x;
        tabDebugLastTextRenderedX = renderedX;
        return renderedX;
    }

    public static int reserveCurrentTabVictimWidth(int width, String text) {
        long timer = TabPerformanceDebug.beginTab();
        try { return reserveCurrentTabVictimWidthTimed(width, text); }
        finally { TabPerformanceDebug.end(TabPerformanceDebug.Metric.TAB_VICTIM_WIDTH, timer); }
    }

    private static int reserveCurrentTabVictimWidthTimed(int width, String text) {
        NetworkPlayerInfo row = tabOverlayCurrentRow.get();
        tabDebugWidthCalls.incrementAndGet();
        if (row == null || text == null) return width;
        Minecraft mc = Minecraft.getMinecraft();
        if (!isRenderedTabVictim(row, mc)) return width;
        int offset = renderedTabVictimHeadOffsetPixels(row, mc);
        if (offset <= 0) return width;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (!tabRenderedTextLooksLikeRow(row, plain)) return width;
        tabDebugVictimWidthsReserved.incrementAndGet();
        return width + offset;
    }

    /** A retained victim is a dead TAB row whose player has left the server; connected spectators remain opaque. */
    private static boolean isRetainedRenderedTabVictim(NetworkPlayerInfo row, Minecraft mc) {
        if (row == null) return false;
        TabVictimRenderState state = currentTabVictimRenderState(row);
        if (state != null && state.retainedResolved) return state.retained;
        boolean retained = isRenderedTabVictim(row, mc) && isTabVictimAbsentFromServer(row, mc);
        if (state != null) {
            state.retained = retained;
            state.retainedResolved = true;
        }
        return retained;
    }

    /** Only absent, retained victim rows fade; connected spectators keep opaque, non-italic spectator blue. */
    private static boolean shouldDimRenderedTabVictim(NetworkPlayerInfo row, Minecraft mc) {
        TabVictimRenderState state = currentTabVictimRenderState(row);
        if (state != null && state.dimResolved) return state.dim;
        boolean dim = ConfigManager.isKillCounterTabKillersVictimDimmed()
                && isRetainedRenderedTabVictim(row, mc);
        if (state != null) {
            state.dim = dim;
            state.dimResolved = true;
        }
        return dim;
    }

    private static boolean isTabVictimAbsentFromServer(NetworkPlayerInfo row, Minecraft mc) {
        if(row!=null){
            int sharedPresence=SpectatorRelay.tabPresence(row);
            if(sharedPresence!=SpectatorRosterState.UNKNOWN)return sharedPresence==SpectatorRosterState.ABSENT;
        }
        return row != null && (mc == null || mc.getNetHandler() == null || RemoteDisguiseManager.getSourceProfile(row) == null
                || RemoteDisguiseManager.getSourceProfile(row).getId() == null
                || mc.getNetHandler().getPlayerInfo(RemoteDisguiseManager.getSourceProfile(row).getId()) == null);
    }

    /** Retained victims use the dim preference; current spectators use opaque §9 blue. */
    public static int colorCurrentTabVictimText(int color) {
        NetworkPlayerInfo row = tabOverlayCurrentRow.get();
        tabDebugColorCalls.incrementAndGet();
        Minecraft mc = Minecraft.getMinecraft();
        if (!isRenderedTabVictim(row, mc)) return color;
        tabDebugVictimColorsApplied.incrementAndGet();
        int alpha = 255;
        if (shouldDimRenderedTabVictim(row, mc)) {
            int nativeAlpha = color >>> 24;
            alpha = Math.min(nativeAlpha == 0 ? 255 : nativeAlpha, 0x90);
        }
        int victimColor = (alpha << 24) | 0x5555FF;
        tabDebugLastVictimColor = victimColor;
        return victimColor;
    }

    /** Match retained victim text opacity while keeping connected spectators' heads opaque. */
    public static float getTabKillersVictimHeadAlpha(NetworkPlayerInfo row) {
        if (shouldDimRenderedTabVictim(row, Minecraft.getMinecraft())) {
            return 0x90 / 255.0F;
        }
        return 1.0F;
    }

    /** Records the actual TAB skin-head coordinates after Lunar/vanilla row hooks choose the draw position. */
    public static void recordTabVictimHeadDraw(NetworkPlayerInfo row, int sourceX, int renderedX) {
        if (!isRenderedTabVictim(row, Minecraft.getMinecraft())) return;
        tabDebugVictimHeadsDrawn.incrementAndGet();
        tabDebugLastHeadIdentity = renderedTabProfileName(row);
        tabDebugLastHeadSourceX = sourceX;
        tabDebugLastHeadRenderedX = renderedX;
    }

    public static boolean isTabOverlayRenderActive() {
        boolean weaveRuntime = GomodFixes.isWeaveRuntime();
        boolean chatFontActive = isChatFontWorkActive();
        boolean tabKeyDown = false;
        // Lunar can render its custom TAB rows outside GuiPlayerTabOverlay.renderPlayerlist. In that path
        // PlayerListMixin's row callbacks may not open tabOverlayRenderDepth, even though TAB is visibly held.
        // The held key is the safe render-window signal for FontRenderer's name/stats fallback.
        if (weaveRuntime && tabOverlayRenderDepth <= 0 && !chatFontActive) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                tabKeyDown = mc != null && mc.gameSettings != null
                        && mc.gameSettings.keyBindPlayerList != null
                        && mc.gameSettings.keyBindPlayerList.isKeyDown();
            } catch (Throwable ignored) {
            }
        }
        return TabOverlayRenderScope.isActive(
                weaveRuntime, tabOverlayRenderDepth, tabKeyDown, chatFontActive);
    }

    /**
     * Some Weave/Lunar tab overlays render before the normal dirty/periodic tick has composed row display names.
     * Run a small, rate-limited compose at TAB render time so denick, stats, and colored names are present.
     */
    public static void applyTabCompositionForOverlayRender() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isTabQolRuntimeContext(mc) || TabStatsRuntimeMute.isMuted()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (tabCompositionDirty) {
            applyPendingTabCompositionBeforeOverlayRender();
            lastOverlayTriggeredTabComposeMs = now;
            return;
        }
        if (now - lastOverlayTriggeredTabComposeMs < TAB_OVERLAY_COMPOSE_INTERVAL_MS) {
            return;
        }
        if (now - lastTabCompositionApplyMs < TAB_OVERLAY_COMPOSE_INTERVAL_MS) {
            return;
        }
        lastOverlayTriggeredTabComposeMs = now;
        lastTabCompositionApplyMs = now;
        runOnClientThread(KillCounter::updateAllTabNames);
    }

    /**
     * Render-only fallback for Lunar/Weave TAB paths that bypass GuiPlayerTabOverlay#getPlayerName and draw
     * ScorePlayerTeam.formatPlayerName(...) directly.
     */
    public static String decorateTabScoreboardFormattedName(String playerName, String originalName) {
        // This is a render-only fallback for Lunar/Weave's alternate TAB pipeline. Forge's
        // GuiPlayerTabOverlay path is already handled by PlayerListMixin; running this scan
        // from FontRenderer on Forge makes every TAB text draw walk the whole player list.
        if (!GomodFixes.isWeaveRuntime() || !isTabOverlayRenderActive()
                || playerName == null || playerName.isEmpty()) {
            return originalName;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isTabQolRuntimeContext(mc) || TabStatsRuntimeMute.isMuted()
                || mc.getNetHandler() == null) {
            return originalName;
        }
        try {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                GameProfile profile = RemoteDisguiseManager.getSourceProfile(row);
                if (profile != null && playerName.equalsIgnoreCase(profile.getName())) {
                    return decorateTabScoreboardFormattedName(row, profile, originalName);
                }
            }
        } catch (Throwable ignored) {
        }
        return originalName;
    }

    private static String decorateTabScoreboardFormattedName(NetworkPlayerInfo info, GameProfile profile,
            String originalName) {
        String baseUsername = profile.getName();
        if (baseUsername == null || baseUsername.isEmpty()) return originalName;
        String cachedRendered = getRenderedTabLineForInfo(info);
        if (cachedRendered != null && !cachedRendered.isEmpty()) {
            return cachedRendered;
        }
        String updated = originalName;
        IChatComponent display = null;
        try {
            display = info.getDisplayName();
            String displayText = display == null ? null : display.getFormattedText();
            if (displayText != null && !displayText.isEmpty()) {
                updated = displayText;
            }
        } catch (Throwable ignored) {
        }
        if (updated == null || updated.isEmpty()) {
            return originalName;
        }
        if (display == null && shouldRenderKillCounterAfterName()) {
            int kills = getKillCount(baseUsername);
            if (kills >= 1) {
                boolean isTop = getPlayerRank(baseUsername) == 1;
                String style = (isTop ? EnumChatFormatting.BOLD.toString() : "") + EnumChatFormatting.DARK_RED;
                updated = updated + " " + style + "[" + kills + "]" + EnumChatFormatting.RESET;
            }
        }

        updated = applyTabKillersStyleToRenderedName(info, updated);
        if (LocalDisguise.matchesLocalProfileFast(profile)) {
            updated = LocalDisguise.buildTabDisplayName(updated, baseUsername);
        }
        updated = GomodPeerDisguiseDisplay.rewriteTabName(profile, updated);
        return AntiShuffleCompat.cleanTabTextIfExternalAntiShuffleEnabled(
                LobbyPingDisplay.appendTabPingIfEnabled(info, updated));
    }

    public static String getRenderedTabLineForInfo(NetworkPlayerInfo info) {
        long timer = TabPerformanceDebug.beginTab();
        long renderTimer = RenderProfiler.begin("TAB.renderedName");
        try { return getRenderedTabLineForInfoTimed(info); }
        finally {
            TabPerformanceDebug.end(TabPerformanceDebug.Metric.TAB_RENDERED_NAME, timer);
            RenderProfiler.end("TAB.renderedName", renderTimer);
        }
    }

    private static String getRenderedTabLineForInfoTimed(NetworkPlayerInfo info) {
        if (info == null) {
            return null;
        }
        GameProfile source = RemoteDisguiseManager.getSourceProfile(info);
        if (source == null || source.getName() == null) return null;
        String key = tabRowKey(info);
        String rendered = AntiShuffleCompat.cleanTabTextIfExternalAntiShuffleEnabled(
                tabRenderedLineByRowKey.get(key));
        if (rendered == null) return null;
        // Once the current Lunar row hook has been observed, its icon callback draws the stats with Lunar's own
        // font at an explicit x coordinate. Return only the left group here so the original inline stat is not
        // drawn underneath that native overlay.
        if (LunarTabLogoCompatibility.isNativeRowDrawObserved() && TabStatsLayoutMode.shouldSeparateStats()) {
            String stats = tabStatsSegmentByRowKey.get(key);
            TabStatsDrawParts parts = splitTabStatsForDraw(rendered, stats);
            if (parts != null) {
                return applyTabKillersStyleToRenderedName(info,
                        GomodPeerDisguiseDisplay.rewriteTabName(source, parts.left));
            }
        }
        return applyTabKillersStyleToRenderedName(info, GomodPeerDisguiseDisplay.rewriteTabName(source, rendered));
    }

    /** Current separately rendered TAB stats segment, used by the bounded disguise debug command. */
    public static String getTabStatsSegmentForDebug(NetworkPlayerInfo info) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null) {
            return "";
        }
        String stats = tabStatsSegmentByRowKey.get(tabRowKey(info));
        return stats == null ? "" : stats;
    }

    /** Clean left/stat pair used by Lunar's exact-coordinate row callback. */
    public static String[] getLunarNativeTabDrawParts(UUID playerId) {
        if (playerId == null || !TabStatsLayoutMode.shouldSeparateStats()) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return null;
        }
        try {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null
                        || !playerId.equals(RemoteDisguiseManager.getSourceProfile(row).getId())) {
                    continue;
                }
                String key = tabRowKey(row);
                String clean = tabUnpaddedLineByRowKey.get(key);
                String stats = tabStatsSegmentByRowKey.get(key);
                TabStatsDrawParts parts = splitTabStatsForDraw(clean, stats);
                if (parts == null) {
                    return null;
                }
                String left = GomodPeerDisguiseDisplay.rewriteTabName(
                        RemoteDisguiseManager.getSourceProfile(row), parts.left);
                left = BlitzChatNameDisplay.decorateTab(row, left);
                return new String[] {left, parts.stats};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Every clean row pair, used to compute one native-font column width for the current TAB. */
    public static java.util.List<String[]> getAllLunarNativeTabDrawParts() {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        if (!TabStatsLayoutMode.shouldSeparateStats()) {
            return result;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return result;
        }
        try {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                    continue;
                }
                String key = tabRowKey(row);
                TabStatsDrawParts parts = splitTabStatsForDraw(
                        tabUnpaddedLineByRowKey.get(key), tabStatsSegmentByRowKey.get(key));
                if (parts != null) {
                    String left = GomodPeerDisguiseDisplay.rewriteTabName(
                            RemoteDisguiseManager.getSourceProfile(row), parts.left);
                    left = BlitzChatNameDisplay.decorateTab(row, left);
                    Float nativeRowX = LunarTabLogoCompatibility.getObservedNativeRowX(
                            RemoteDisguiseManager.getSourceProfile(row).getId());
                    result.add(new String[] {left, parts.stats,
                            String.valueOf(LunarTabLogoCompatibility.getLogoWidth(RemoteDisguiseManager.getSourceProfile(row).getId())),
                            nativeRowX == null ? "" : String.valueOf(nativeRowX.floatValue())});
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static String tabRowKey(NetworkPlayerInfo info) {
        if (info == null) {
            return "";
        }
        if (info instanceof TabRowKeyAccess) {
            return ((TabRowKeyAccess) info).gomod$getTabRowKey();
        }
        return buildTabRowKey(info, RemoteDisguiseManager.getSourceProfile(info));
    }

    /** Access to a key cached on the player-list row for its lifetime. */
    public interface TabRowKeyAccess {
        String gomod$getTabRowKey();
    }

    public static String buildTabRowKey(NetworkPlayerInfo info, GameProfile profile) {
        // NetworkPlayerInfoMixin can return a render-only profile copy with the disguised name while Lunar
        // is building TAB. Cache writes happen against the real profile outside that render, so keying from
        // getGameProfile() here made the same row switch keys just for TAB and dropped its target stats.
        if (profile == null) {
            return "";
        }
        String name = profile.getName();
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        UUID id = profile.getId();
        String rowIdentity = Integer.toHexString(System.identityHashCode(info));
        if (id == null) {
            return "row:" + rowIdentity + "|name:" + lowerName;
        }
        return "row:" + rowIdentity + "|uuid:" + id.toString().replace("-", "").toLowerCase(Locale.ROOT)
                + "|name:" + lowerName;
    }

    public static String decorateTabRenderedText(String originalName) {
        if (originalName == null || originalName.isEmpty() || !GomodFixes.isWeaveRuntime()
                || !isTabOverlayRenderActive() || Boolean.TRUE.equals(suppressTabRenderedTextDecoration.get())) {
            return originalName;
        }
        // Lunar can render a world nametag while the TAB key is held. Resolve the scope before inspecting
        // the sidebar or formatting text. Only the actual overlay may match a row from text alone.
        NetworkPlayerInfo currentRow = currentTabOverlayRow();
        if (currentRow == null && !TabOverlayRenderScope.canUseUnscopedTextFallback(
                true, tabOverlayRenderDepth, isChatFontWorkActive())) return originalName;
        Minecraft mc = Minecraft.getMinecraft();
        if (!isTabQolRuntimeContext(mc) || TabStatsRuntimeMute.isMuted()
                || mc.getNetHandler() == null) {
            return originalName;
        }
        tabDebugFontTabTextCalls.incrementAndGet();
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(originalName);
        if (plain == null || plain.trim().isEmpty()) {
            return originalName;
        }
        // The native renderer supplies the actual row. Text cannot identify it once multiple players share a disguise.
        if (currentRow != null) {
            GameProfile sourceProfile = RemoteDisguiseManager.getSourceProfile(currentRow);
            if (sourceProfile != null) {
                String sourceName = sourceProfile.getName();
                LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getDisguisedTargetProfile(sourceProfile);
                boolean matches = tabRenderedTextLooksLikeRowForBase(plain, sourceName)
                        || target != null && tabRenderedTextLooksLikeRowForBase(plain, target.name)
                        || LocalDisguise.matchesLocalProfileFast(sourceProfile)
                        && tabRenderedTextLooksLikeRowForBase(plain, LocalDisguise.fastTargetNameText);
                if (matches) {
                    tabDebugFontTabRowMatches.incrementAndGet();
                    return decorateTabScoreboardFormattedName(currentRow, sourceProfile, originalName);
                }
            }
            return originalName;
        }
        try {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                GameProfile profile = RemoteDisguiseManager.getSourceProfile(row);
                if (profile != null && tabRenderedTextLooksLikeRowForBase(plain, profile.getName())) {
                    tabDebugFontTabRowMatches.incrementAndGet();
                    return decorateTabScoreboardFormattedName(row, profile, originalName);
                }
            }
        } catch (Throwable ignored) {
        }
        return AntiShuffleCompat.cleanTabTextIfExternalAntiShuffleEnabled(originalName);
    }

    /**
     * Draws the cached TAB stats as a true right-aligned column. The player name, guild, kit, etc. are drawn as
     * the left group and no padding characters are inserted into the display name.
     *
     * @return a draw result when this row had an alignable stats segment, otherwise {@code null}
     */
    public static Integer drawTabRowWithRightAlignedStats(FontRenderer font, NetworkPlayerInfo info,
            String renderedText, float x, float y, int color, boolean dropShadow) {
        if (!TabStatsLayoutMode.shouldSeparateStats() || font == null || info == null
                || renderedText == null || !isTabOverlayRenderActive()) {
            return null;
        }
        String stats = tabStatsSegmentByRowKey.get(tabRowKey(info));
        TabStatsDrawParts parts = splitTabStatsForDraw(renderedText, stats);
        if (parts == null) {
            return null;
        }
        suppressTabRenderedTextDecoration.set(Boolean.TRUE);
        try {
            int columnWidth = getRightAlignedTabStatsColumnWidth(font);
            int statsWidth = font.getStringWidth(parts.stats);
            float statsX = x + Math.max(font.getStringWidth(parts.left) + font.getCharWidth(' '),
                    columnWidth - statsWidth);
            int leftEnd = font.drawString(parts.left, x, y, color, dropShadow);
            int statsEnd = font.drawString(parts.stats, statsX, y, color, dropShadow);
            return Math.max(leftEnd, statsEnd);
        } finally {
            suppressTabRenderedTextDecoration.remove();
        }
    }

    /**
     * Font-renderer fallback for Lunar's enhanced TAB renderer. Lunar still enters the vanilla TAB overlay, but
     * routes row text through its own drawing helper instead of one of GuiPlayerTabOverlay's direct draw calls.
     * Match the already-composed stats suffix instead of relying on the current NetworkPlayerInfo row.
     */
    public static Integer drawTabTextWithRightAlignedStatsFallback(FontRenderer font, String renderedText,
            float x, float y, int color, boolean dropShadow) {
        if (font == null || renderedText == null || renderedText.indexOf('[') < 0 || renderedText.indexOf(']') < 0
                || !GomodFixes.isWeaveRuntime() || !isTabOverlayRenderActive() || !TabStatsLayoutMode.shouldSeparateStats()
                || Boolean.TRUE.equals(suppressTabRenderedTextDecoration.get())) {
            return null;
        }
        NetworkPlayerInfo row = currentTabOverlayRow();
        TabStatsDrawParts parts = row == null ? null
                : splitTabStatsForDraw(renderedText, tabStatsSegmentByRowKey.get(tabRowKey(row)));
        if (parts == null) {
            for (String stats : tabStatsSegmentByRowKey.values()) {
                parts = splitTabStatsForDraw(renderedText, stats);
                if (parts != null) break;
            }
        }
        if (parts == null) {
            return null;
        }
        suppressTabRenderedTextDecoration.set(Boolean.TRUE);
        try {
            int columnWidth = getRightAlignedTabStatsColumnWidth(font);
            int statsWidth = font.getStringWidth(parts.stats);
            float statsX = x + Math.max(font.getStringWidth(parts.left) + font.getCharWidth(' '),
                    columnWidth - statsWidth);
            int leftEnd = font.drawString(parts.left, x, y, color, dropShadow);
            int statsEnd = font.drawString(parts.stats, statsX, y, color, dropShadow);
            return Math.max(leftEnd, statsEnd);
        } finally {
            suppressTabRenderedTextDecoration.remove();
        }
    }

    private static int getRightAlignedTabStatsColumnWidth(FontRenderer font) {
        if (tabRightAlignedStatsColumnWidth >= 0) {
            return tabRightAlignedStatsColumnWidth;
        }
        int max = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getNetHandler() != null) {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                    continue;
                }
                String line = tabRenderedLineByRowKey.get(tabRowKey(row));
                if (line == null || line.isEmpty()) {
                    try {
                        IChatComponent display = row.getDisplayName();
                        line = display == null
                                ? ScorePlayerTeam.formatPlayerName(row.getPlayerTeam(), RemoteDisguiseManager.getSourceProfile(row).getName())
                                : display.getFormattedText();
                    } catch (Throwable ignored) {
                        line = RemoteDisguiseManager.getSourceProfile(row).getName();
                    }
                }
                String stats = tabStatsSegmentByRowKey.get(tabRowKey(row));
                TabStatsDrawParts parts = splitTabStatsForDraw(line, stats);
                String left = parts == null ? null : BlitzChatNameDisplay.decorateTab(row, parts.left);
                int width = parts == null
                        ? font.getStringWidth(line)
                        : font.getStringWidth(left) + font.getCharWidth(' ') + font.getStringWidth(parts.stats);
                max = Math.max(max, width);
            }
        }
        tabRightAlignedStatsColumnWidth = max;
        return max;
    }

    private static TabStatsDrawParts splitTabStatsForDraw(String line, String stats) {
        if (line == null || stats == null || stats.isEmpty()) {
            return null;
        }
        int at = line.lastIndexOf(stats);
        if (at < 0) {
            return null;
        }
        String before = trimTrailingAsciiSpaces(line.substring(0, at));
        String after = trimLeadingDecoratedSpaces(line.substring(at + stats.length()));
        String left = before;
        if (after != null && !after.isEmpty()) {
            StringBuilder joined = new StringBuilder(before);
            appendTabPart(joined, after);
            left = joined.toString();
        }
        return new TabStatsDrawParts(left, stats);
    }

    private static final class TabStatsDrawParts {
        final String left;
        final String stats;

        TabStatsDrawParts(String left, String stats) {
            this.left = left;
            this.stats = stats;
        }
    }

    private static boolean tabRenderedTextLooksLikeRowForBase(String plain, String baseName) {
        if (plain == null || baseName == null || baseName.isEmpty()) {
            return false;
        }
        String antiShuffleBase = AntiShuffleCompat.cleanTabTextIfExternalAntiShuffleEnabled(baseName);
        if (antiShuffleBase != null && !antiShuffleBase.equals(baseName)
                && tabRenderedTextLooksLikeRowForBaseRaw(plain, antiShuffleBase)) {
            return true;
        }
        return tabRenderedTextLooksLikeRowForBaseRaw(plain, baseName);
    }

    private static boolean tabRenderedTextLooksLikeRowForBaseRaw(String plain, String baseName) {
        if (plain == null || baseName == null || baseName.isEmpty()) {
            return false;
        }
        String p = plain.trim();
        if (p.equalsIgnoreCase(baseName)) {
            return true;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        String base = baseName.toLowerCase(Locale.ROOT);
        return lower.endsWith(" " + base)
                || lower.startsWith(base + " ")
                || lower.contains(" " + base + " ");
    }

    /**
     * Match-map pregame / cages can briefly carry dead-looking gray/blue TAB rows across the server handoff.
     * Hide those rows entirely until the live game grouping logic is active, otherwise we leak spectators before
     * the match has really started.
     */
    private static Collection<NetworkPlayerInfo> suppressBlitzPregameDeadGhostRows(Collection<NetworkPlayerInfo> base) {
        if (base == null || base.isEmpty()) {
            return base;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // Include the cage phase itself: deaths during the teleport/cages window can leave stale "was killed" rows
        // that are not yet server-styled as dead (blue). Strip them to avoid leaking dead/spectator rows before
        // the live game grouping logic is active.
        if (mc == null || !(GameContext.isBlitzMatchMapPregameSidebar(mc)
                || GameContext.isBlitzCagePhaseSidebar(mc)
                || isBlitzJustLeftCagePhase(mc))) {
            return base;
        }
        ArrayList<NetworkPlayerInfo> filtered = new ArrayList<NetworkPlayerInfo>(base.size());
        boolean changed = false;
        for (NetworkPlayerInfo info : base) {
            if (isBlitzPregameDeadGhostRow(info, mc)) {
                changed = true;
                continue;
            }
            filtered.add(info);
        }
        return changed ? filtered : base;
    }

    /**
     * Safe extension of pregame ghost stripping: for a short window right after cages open, Hypixel can keep stale
     * "was killed!" rows in TAB. Only strips rows that are already tracked as dead (or server-dead formatted).
     */
    /** Milliseconds when the Blitz sidebar stopped showing cage phase (cages opened), or {@code 0} if unknown. */
    public static long getLastBlitzCageReleasedMs() {
        return lastBlitzCageReleasedMs;
    }

    private static boolean isBlitzJustLeftCagePhase(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        if (!GameContext.isOnHypixel(mc)) {
            return false;
        }
        if (!GameContext.isBlitzGameActive(mc)) {
            return false;
        }
        long t = lastBlitzCageReleasedMs;
        if (t <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        return now >= t && (now - t) <= 30_000L;
    }

    private static boolean isBlitzPregameDeadGhostRow(NetworkPlayerInfo info, Minecraft mc) {
        if (info == null || mc == null) {
            return false;
        }
        String baseName = tabSortProfileName(info);
        if (baseName.isEmpty()) {
            return false;
        }
        if (isPlayerDead(baseName)) {
            return true;
        }
        try {
            String teamFmt = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), baseName);
            return isBlitzTabServerDeadBlue(teamFmt, baseName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cacheObservedTabAppearances(Collection<NetworkPlayerInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            return;
        }
        for (NetworkPlayerInfo info : infos) {
            cacheObservedTabAppearance(info);
        }
    }

    private static void cacheObservedTabAppearance(NetworkPlayerInfo info) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null) {
            return;
        }
        String baseName = RemoteDisguiseManager.getSourceProfile(info).getName();
        if (baseName == null || baseName.trim().isEmpty()) {
            return;
        }
        CachedTabAppearance incoming = new CachedTabAppearance();
        incoming.profileId = RemoteDisguiseManager.getSourceProfile(info).getId();
        incoming.skin = info.getLocationSkin();
        incoming.skinType = info.getSkinType();
        if (incoming.profileId == null && incoming.skin == null && (incoming.skinType == null || incoming.skinType.isEmpty())) {
            return;
        }
        boolean incomingDefaultSkin = isDefaultPlayerSkinResource(incoming.skin);
        for (String alias : getLookupNames(baseName)) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            String lower = alias.toLowerCase(Locale.ROOT);
            cachedTabAppearanceByLookupLower.compute(lower, (k, existing) -> mergeCachedTabAppearances(existing, incoming, incomingDefaultSkin));
        }
    }

    private static CachedTabAppearance mergeCachedTabAppearances(CachedTabAppearance existing, CachedTabAppearance incoming,
            boolean incomingDefaultSkin) {
        CachedTabAppearance merged = existing == null ? new CachedTabAppearance() : existing;
        if (incoming.profileId != null) {
            merged.profileId = incoming.profileId;
        }
        boolean existingDefaultSkin = isDefaultPlayerSkinResource(merged.skin);
        if (incoming.skin != null && (merged.skin == null || (!incomingDefaultSkin || existingDefaultSkin))) {
            merged.skin = incoming.skin;
        }
        if (incoming.skinType != null && !incoming.skinType.isEmpty()) {
            if (merged.skinType == null || merged.skinType.isEmpty() || !incomingDefaultSkin || existingDefaultSkin) {
                merged.skinType = incoming.skinType;
            }
        }
        return merged;
    }

    private static boolean isDefaultPlayerSkinResource(ResourceLocation skin) {
        if (skin == null) {
            return false;
        }
        String domain = skin.getResourceDomain();
        String path = skin.getResourcePath();
        if (domain == null || path == null || !"minecraft".equalsIgnoreCase(domain)) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        return lowerPath.endsWith("steve.png") || lowerPath.endsWith("alex.png");
    }

    private static CachedTabAppearance resolveCachedTabAppearance(String baseName) {
        if (baseName == null || baseName.trim().isEmpty()) {
            return null;
        }
        for (String alias : getLookupNames(baseName)) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            CachedTabAppearance cached = cachedTabAppearanceByLookupLower.get(alias.toLowerCase(Locale.ROOT));
            if (cached != null && cached.skin != null && !isDefaultPlayerSkinResource(cached.skin)) {
                return cached;
            }
        }
        CachedTabAppearance fallback = null;
        for (String alias : getLookupNames(baseName)) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            CachedTabAppearance cached = cachedTabAppearanceByLookupLower.get(alias.toLowerCase(Locale.ROOT));
            if (cached != null) {
                fallback = cached;
                break;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof AbstractClientPlayer)) {
                continue;
            }
            AbstractClientPlayer player = (AbstractClientPlayer) obj;
            if (player.getName() == null || !player.getName().equalsIgnoreCase(baseName)) {
                continue;
            }
            CachedTabAppearance observed = new CachedTabAppearance();
            observed.profileId = player.getGameProfile() != null ? player.getGameProfile().getId() : null;
            observed.skin = player.getLocationSkin();
            observed.skinType = player.getSkinType();
            cacheObservedTabAppearanceFromSnapshot(baseName, observed);
            return observed;
        }
        return null;
    }

    private static void cacheObservedTabAppearanceFromSnapshot(String baseName, CachedTabAppearance snapshot) {
        if (baseName == null || baseName.trim().isEmpty() || snapshot == null) {
            return;
        }
        boolean incomingDefaultSkin = isDefaultPlayerSkinResource(snapshot.skin);
        for (String alias : getLookupNames(baseName)) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            String lower = alias.toLowerCase(Locale.ROOT);
            cachedTabAppearanceByLookupLower.compute(lower, (k, existing) -> mergeCachedTabAppearances(existing, snapshot, incomingDefaultSkin));
        }
    }

    private static Collection<NetworkPlayerInfo> augmentTabListWithSyntheticDeadRowsIfNeeded(Collection<NetworkPlayerInfo> base) {
        if (base == null || base.isEmpty() || deadPlayers.isEmpty()) {
            return base;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return base;
        }
        if (TabStatsRuntimeMute.isMuted()
                || !ConfigManager.isKillCounterTabKillersEnabled()
                || !(GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))) {
            return base;
        }

        ArrayList<NetworkPlayerInfo> out = new ArrayList<NetworkPlayerInfo>(base.size() + deadPlayers.size());
        out.addAll(base);
        // Pre-build a set of existing lowercase profile names to avoid O(n) findTabRowForProfileName per dead player.
        HashSet<String> existingProfileNamesLower = new HashSet<>(base.size());
        for (NetworkPlayerInfo info : base) {
            if (info != null) {
                String pn = tabSortProfileName(info);
                if (!pn.isEmpty()) {
                    existingProfileNamesLower.add(pn.toLowerCase(Locale.ROOT));
                    // Also add aliases so we don't create duplicates for nicked players
                    for (String alias : getLookupNames(pn)) {
                        if (alias != null && !alias.isEmpty()) {
                            existingProfileNamesLower.add(alias.toLowerCase(Locale.ROOT));
                        }
                    }
                    // A live source row can already render as a tracked victim's name while /disguiser is
                    // active. Count that display alias too, or we inject a second synthetic row for the target.
                    String disguiseTarget = RemoteDisguiseManager.getDisguiseTargetNameForSourceName(pn);
                    if (disguiseTarget != null && !disguiseTarget.trim().isEmpty()) {
                        existingProfileNamesLower.add(disguiseTarget.trim().toLowerCase(Locale.ROOT));
                        for (String alias : getLookupNames(disguiseTarget)) {
                            if (alias != null && !alias.isEmpty()) {
                                existingProfileNamesLower.add(alias.toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            }
        }
        boolean changed = false;

        for (String deadLower : deadPlayers) {
            String baseName = resolveTrackedDeadPlayerName(deadLower);
            if (baseName == null || baseName.trim().isEmpty()) {
                continue;
            }
            if (existingProfileNamesLower.contains(baseName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            NetworkPlayerInfo synthetic = buildSyntheticDeadTabRow(baseName);
            if (synthetic == null) {
                continue;
            }
            out.add(synthetic);
            existingProfileNamesLower.add(baseName.toLowerCase(Locale.ROOT));
            changed = true;
        }

        return changed ? out : base;
    }

    private static String resolveTrackedDeadPlayerName(String deadLower) {
        if (deadLower == null || deadLower.trim().isEmpty()) {
            return deadLower;
        }
        String target = deadLower.trim();
        String targetLower = target.toLowerCase(Locale.ROOT);
        // O(1) lookups using lowercase keys. Maps use mixed-case keys, so scan only as fallback.
        for (String key : killCounts.keySet()) {
            if (key != null && key.toLowerCase(Locale.ROOT).equals(targetLower)) {
                return key;
            }
        }
        for (Map.Entry<String, List<String>> entry : playerVictims.entrySet()) {
            if (entry == null) {
                continue;
            }
            String killer = entry.getKey();
            if (killer != null && killer.toLowerCase(Locale.ROOT).equals(targetLower)) {
                return killer;
            }
            List<String> victims = entry.getValue();
            if (victims != null) {
                for (String victim : victims) {
                    if (victim != null && victim.toLowerCase(Locale.ROOT).equals(targetLower)) {
                        return victim;
                    }
                }
            }
        }
        // Guild/kit/color maps — fast containsKey since we now store lowercase keys for guild maps.
        if (playerGuildTags.containsKey(targetLower)) {
            return target;
        }
        if (playerKits.containsKey(target)) {
            return target;
        }
        if (enemyPlayerKits.containsKey(target)) {
            return target;
        }
        if (playerColors.containsKey(target)) {
            return target;
        }
        return target;
    }

    private static NetworkPlayerInfo buildSyntheticDeadTabRow(String baseName) {
        if (baseName == null || baseName.trim().isEmpty()) {
            return null;
        }
        try {
            String trimmed = baseName.trim();
            CachedTabAppearance cachedAppearance = resolveCachedTabAppearance(trimmed);
            UUID uuid = cachedAppearance != null && cachedAppearance.profileId != null
                    ? cachedAppearance.profileId
                    : UUID.nameUUIDFromBytes(("gomod:dead-tab:" + trimmed.toLowerCase(Locale.ROOT))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            NetworkPlayerInfo info = new NetworkPlayerInfo(new GameProfile(uuid, trimmed));
            if (info instanceof NetworkPlayerInfoAccessor) {
                NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
                ResourceLocation skin = cachedAppearance != null && cachedAppearance.skin != null
                        ? cachedAppearance.skin
                        : DefaultPlayerSkin.getDefaultSkin(uuid);
                String skinType = cachedAppearance != null && cachedAppearance.skinType != null
                        && !cachedAppearance.skinType.isEmpty()
                        ? cachedAppearance.skinType
                        : DefaultPlayerSkin.getSkinType(uuid);
                accessor.gomod$setPlayerTexturesLoaded(true);
                accessor.gomod$setLocationSkin(skin);
                accessor.gomod$setSkinType(skinType);
                accessor.gomod$setLocationCape(null);
            }
            info.setDisplayName(new ChatComponentText(buildSyntheticDeadTabDisplayLine(trimmed)));
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildSyntheticDeadTabDisplayLine(String baseName) {
        StringBuilder display = new StringBuilder(baseName);
        boolean includeGameScopedDecorations = enabled && !gameHasEnded;
        int kills = getKillCount(baseName);
        boolean showKillSuffix = includeGameScopedDecorations
                && ConfigManager.isKillCounterEnabled()
                && tabKillCounterDisplayMode == TabKillCounterDisplayMode.AFTER_NAME
                && kills >= 1;

        if (showKillSuffix) {
            appendTabPart(display, EnumChatFormatting.RESET + EnumChatFormatting.DARK_RED.toString() + "[" + kills + "]");
        }

        return applyTabKillersVictimStylePlainLine(display.toString(), baseName);
    }

    private static int compareByTabListSortMode(NetworkPlayerInfo a, NetworkPlayerInfo b, TabListSortMode mode) {
        String na = tabSortProfileName(a);
        String nb = tabSortProfileName(b);
        if (na == null) na = "";
        if (nb == null) nb = "";
        boolean killsFirst = mode == TabListSortMode.KILLS_THEN_WLR
                || mode == TabListSortMode.KILLS_THEN_WINS
                || mode == TabListSortMode.KILLS_THEN_OVERALL_KILLS;
        if (killsFirst) {
            int cmp = Integer.compare(getKillCount(nb), getKillCount(na));
            if (cmp != 0) return cmp;
        }
        if (mode != TabListSortMode.OFF && mode != TabListSortMode.DISTRICT) {
            // Unknown lifetime stats (usually a nick) rank ahead of available lifetime metrics. This is
            // only a tie-break after live kills above, never an override of the primary kill count.
            int cmp = Boolean.compare(tabSortHasCachedMetrics(a), tabSortHasCachedMetrics(b));
            if (cmp != 0) return cmp;
        }
        switch (mode) {
            case KILLS_THEN_WLR: {
                int cmp = compareWlDesc(tabSortResolvedWl(a), tabSortResolvedWl(b));
                if (cmp != 0) return cmp;
                return na.compareToIgnoreCase(nb);
            }
            case KILLS_THEN_WINS: {
                int cmp = Integer.compare(tabSortResolvedWins(b), tabSortResolvedWins(a));
                if (cmp != 0) return cmp;
                return na.compareToIgnoreCase(nb);
            }
            case WLR_THEN_KILLS: {
                int cmp = compareWlDesc(tabSortResolvedWl(a), tabSortResolvedWl(b));
                if (cmp != 0) return cmp;
                cmp = Integer.compare(getKillCount(nb), getKillCount(na));
                if (cmp != 0) return cmp;
                return na.compareToIgnoreCase(nb);
            }
            case KILLS_THEN_OVERALL_KILLS: {
                int cmp = Integer.compare(tabSortResolvedOverallKills(b), tabSortResolvedOverallKills(a));
                if (cmp != 0) return cmp;
                return na.compareToIgnoreCase(nb);
            }
            case WINS_THEN_KILLS: {
                int cmp = Integer.compare(tabSortResolvedWins(b), tabSortResolvedWins(a));
                if (cmp != 0) return cmp;
                cmp = Integer.compare(getKillCount(nb), getKillCount(na));
                if (cmp != 0) return cmp;
                return na.compareToIgnoreCase(nb);
            }
            case OFF:
            case DISTRICT:
            default:
                return na.compareToIgnoreCase(nb);
        }
    }

    /**
     * During an active Blitz game, keep eliminated / spectator TAB rows after everyone still alive. Uses
     * {@link #isPlayerDead} plus Hypixel blue TAB styling when the kill tracker has not caught up yet.
     * Returns {@code list} when unchanged so callers can pass immutable lists from Guava {@code sortedCopy}.
     */
    public static List<NetworkPlayerInfo> applyBlitzTabAliveBeforeDeadOrdering(List<NetworkPlayerInfo> list) {
        if (list == null || list.size() <= 1) {
            return list;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !GameContext.isBlitzTabListCustomSortContext(mc)) {
            return list;
        }
        ArrayList<NetworkPlayerInfo> alive = new ArrayList<>(list.size());
        ArrayList<NetworkPlayerInfo> dead = new ArrayList<>();
        for (NetworkPlayerInfo info : list) {
            if (tabSortTreatAsDeadSpectator(info, mc)) {
                dead.add(info);
            } else {
                alive.add(info);
            }
        }
        if (dead.isEmpty() || alive.isEmpty()) {
            return list;
        }
        ArrayList<NetworkPlayerInfo> out = new ArrayList<>(list.size());
        out.addAll(alive);
        out.addAll(dead);
        return out;
    }

    /**
     * After vanilla/custom TAB order, groups each killer's dead victims directly under them (newest kill first).
     * No-op when disabled. Victim rows use spectator blue with reduced opacity at draw time.
     */
    public static List<NetworkPlayerInfo> applyTabKillersGroupingIfEnabled(List<NetworkPlayerInfo> list) {
        if (list == null || list.isEmpty()) {
            tabKillersIndentDepthByLower.clear();
            tabKillersParentIdentityByChildIdentity.clear();
            tabKillersMutualKillIdentitySet.clear();
            return list;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (TabStatsRuntimeMute.isMuted()
                || !ConfigManager.isKillCounterTabKillersEnabled()
                || mc == null
                || !isTabQolRuntimeContext(mc)
                || !(GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))) {
            tabKillersIndentDepthByLower.clear();
            tabKillersParentIdentityByChildIdentity.clear();
            tabKillersMutualKillIdentitySet.clear();
            return list;
        }
        tabKillersIndentDepthByLower.clear();
        tabKillersParentIdentityByChildIdentity.clear();
        rebuildTabKillersMutualKillIdentitySet();
        Map<String, NetworkPlayerInfo> tabIndex = buildTabProfileIndex(list);
        ArrayList<NetworkPlayerInfo> out = new ArrayList<NetworkPlayerInfo>(list.size());
        ArrayList<NetworkPlayerInfo> orphanDead = new ArrayList<NetworkPlayerInfo>();
        HashSet<String> emitted = new HashSet<String>();
        for (NetworkPlayerInfo info : list) {
            if (info == null) {
                continue;
            }
            String bn = tabSortProfileName(info);
            if (bn.isEmpty()) {
                continue;
            }
            String identity = getTabKillersIdentityKey(bn);
            if (!identity.isEmpty() && emitted.contains(identity)) {
                continue;
            }
            boolean deadRow = tabSortTreatAsDeadSpectator(info, mc);
            if (deadRow) {
                String parentKiller = findTabKillersParentNameOnTab(bn, list, tabIndex);
                if (parentKiller == null || parentKiller.isEmpty()) {
                    orphanDead.add(info);
                    continue;
                }
                NetworkPlayerInfo parentInfo = findTabRowForProfileNameIndexed(tabIndex, list, parentKiller);
                if (parentInfo != null) {
                    String parentIdentity = getTabKillersIdentityKey(tabSortProfileName(parentInfo));
                    if (!parentIdentity.isEmpty() && !emitted.contains(parentIdentity)) {
                        continue;
                    }
                }
            }
            tabKillersEmitRecursive(info, list, tabIndex, emitted, out, mc, 0, null);
        }
        if (!orphanDead.isEmpty()) {
            orphanDead.sort((a, b) -> Long.compare(getDeathSequence(tabSortProfileName(b)), getDeathSequence(tabSortProfileName(a))));
            for (NetworkPlayerInfo info : orphanDead) {
                tabKillersEmitRecursive(info, list, tabIndex, emitted, out, mc, 0, null);
            }
        }
        // Reciprocal kills can form a cycle (A killed B, then B received delayed credit for killing A).
        // In that case every row has a parent and the pass above skips all of them while waiting for a parent
        // that can never be emitted first. Start each still-unemitted component at its highest-kill row; Java's
        // stable sort preserves the incoming TAB order for ties, and the recursive emitted set breaks the cycle.
        ArrayList<NetworkPlayerInfo> unresolved = new ArrayList<NetworkPlayerInfo>();
        for (NetworkPlayerInfo info : list) {
            if (info == null) {
                continue;
            }
            String baseName = tabSortProfileName(info);
            String identity = getTabKillersIdentityKey(baseName);
            if (!identity.isEmpty() && !emitted.contains(identity)) {
                unresolved.add(info);
            }
        }
        unresolved.sort((a, b) -> Integer.compare(
                getKillCount(tabSortProfileName(b)), getKillCount(tabSortProfileName(a))));
        for (NetworkPlayerInfo info : unresolved) {
            String identity = getTabKillersIdentityKey(tabSortProfileName(info));
            if (identity.isEmpty() || emitted.contains(identity)) {
                continue;
            }
            tabKillersEmitRecursive(info, list, tabIndex, emitted, out, mc, 0, null);
        }
        // The incoming roots already have the requested stats order. Partitioning again here splits kill chains.
        return out;
    }

    /** Precomputes reciprocal edges so the TAB name renderer does not scan the full kill graph per character row. */
    private static void rebuildTabKillersMutualKillIdentitySet() {
        tabKillersMutualKillIdentitySet.clear();
        Map<String, Set<String>> edgesByIdentity = new HashMap<String, Set<String>>();
        for (Map.Entry<String, List<String>> entry : playerVictims.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().trim().isEmpty()
                    || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String killerIdentity = getTabKillersIdentityKey(entry.getKey());
            if (killerIdentity.isEmpty()) {
                continue;
            }
            Set<String> victims = edgesByIdentity.computeIfAbsent(killerIdentity, key -> new HashSet<String>());
            for (String victim : entry.getValue()) {
                String victimIdentity = getTabKillersIdentityKey(victim);
                if (!victimIdentity.isEmpty() && !victimIdentity.equals(killerIdentity)) {
                    victims.add(victimIdentity);
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : edgesByIdentity.entrySet()) {
            String killerIdentity = entry.getKey();
            for (String victimIdentity : entry.getValue()) {
                Set<String> returnVictims = edgesByIdentity.get(victimIdentity);
                if (returnVictims != null && returnVictims.contains(killerIdentity)) {
                    tabKillersMutualKillIdentitySet.add(killerIdentity);
                    tabKillersMutualKillIdentitySet.add(victimIdentity);
                }
            }
        }
    }

    private static String findTabKillersParentNameOnTab(String victimProfileName, List<NetworkPlayerInfo> fullTab,
            Map<String, NetworkPlayerInfo> tabIndex) {
        if (victimProfileName == null || victimProfileName.trim().isEmpty() || fullTab == null || fullTab.isEmpty()) {
            return null;
        }
        List<String> victimAliases = getTabVictimIdentityAliases(victimProfileName);
        if (victimAliases.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : playerVictims.entrySet()) {
            if (entry == null) {
                continue;
            }
            String killerName = entry.getKey();
            List<String> victims = entry.getValue();
            if (killerName == null || killerName.trim().isEmpty() || victims == null || victims.isEmpty()) {
                continue;
            }
            if (!victimListContainsAlias(victims, victimAliases)) {
                continue;
            }
            NetworkPlayerInfo killerInfo = findTabRowForProfileNameIndexed(tabIndex, fullTab, killerName);
            if (killerInfo != null) {
                String base = tabSortProfileName(killerInfo);
                if (!base.isEmpty()) {
                    return base;
                }
            }
        }
        return null;
    }

    private static boolean victimListContainsAlias(List<String> victims, List<String> victimAliases) {
        if (victims == null || victims.isEmpty() || victimAliases == null || victimAliases.isEmpty()) {
            return false;
        }
        // Build a lowercase set of aliases for O(n) total instead of O(n×m).
        HashSet<String> aliasLowerSet = new HashSet<>(victimAliases.size());
        for (String alias : victimAliases) {
            if (alias != null && !alias.trim().isEmpty()) {
                aliasLowerSet.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (aliasLowerSet.isEmpty()) {
            return false;
        }
        for (String victim : victims) {
            if (victim != null && !victim.trim().isEmpty()
                    && aliasLowerSet.contains(victim.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void tabKillersEmitRecursive(NetworkPlayerInfo info, List<NetworkPlayerInfo> fullTab,
            Map<String, NetworkPlayerInfo> tabIndex,
            Set<String> emitted, List<NetworkPlayerInfo> out, Minecraft mc, int depth, String parentProfileName) {
        String base = tabSortProfileName(info);
        if (base.isEmpty()) {
            return;
        }
        String identity = getTabKillersIdentityKey(base);
        if (identity.isEmpty() || emitted.contains(identity)) {
            return;
        }
        emitted.add(identity);
        rememberTabKillersIndentDepth(base, depth);
        if (parentProfileName != null && !parentProfileName.trim().isEmpty()) {
            String parentIdentity = getTabKillersIdentityKey(parentProfileName);
            if (!parentIdentity.isEmpty()) {
                tabKillersParentIdentityByChildIdentity.put(identity, parentIdentity);
            }
        }
        out.add(info);
        List<String> victims = orderVictimsNewestFirstForTabKillers(base);
        for (String vName : victims) {
            NetworkPlayerInfo vInfo = findTabRowForProfileNameIndexed(tabIndex, fullTab, vName);
            if (vInfo == null) {
                continue;
            }
            if (!tabSortTreatAsDeadSpectator(vInfo, mc)) {
                continue;
            }
            String victimIdentity = getTabKillersIdentityKey(tabSortProfileName(vInfo));
            if (!victimIdentity.isEmpty() && emitted.contains(victimIdentity)) {
                continue;
            }
            tabKillersEmitRecursive(vInfo, fullTab, tabIndex, emitted, out, mc, depth + 1, base);
        }
    }

    private static void rememberTabKillersIndentDepth(String profileName, int depth) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }
        int safeDepth = Math.max(0, depth);
        String identity = getTabKillersIdentityKey(profileName);
        if (!identity.isEmpty()) {
            tabKillersIndentDepthByLower.put(identity, safeDepth);
        }
        for (String alias : getTabVictimIdentityAliases(profileName)) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            tabKillersIndentDepthByLower.put(alias.toLowerCase(Locale.ROOT), safeDepth);
        }
    }

    private static String getTabKillersIdentityKey(String profileOrAlias) {
        if (profileOrAlias == null || profileOrAlias.trim().isEmpty()) {
            return "";
        }
        String trimmed = profileOrAlias.trim();
        String resolved = resolveTabLookupName(trimmed);
        if (resolved != null && !resolved.trim().isEmpty()) {
            return resolved.trim().toLowerCase(Locale.ROOT);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static boolean isSyntheticDeadTabRow(NetworkPlayerInfo info) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null
                || RemoteDisguiseManager.getSourceProfile(info).getId() == null) {
            return false;
        }
        String base = RemoteDisguiseManager.getSourceProfile(info).getName().trim().toLowerCase(Locale.ROOT);
        UUID expected = UUID.nameUUIDFromBytes(("gomod:dead-tab:" + base).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return expected.equals(RemoteDisguiseManager.getSourceProfile(info).getId());
    }

    private static NetworkPlayerInfo preferTabKillersRow(NetworkPlayerInfo current, NetworkPlayerInfo candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        boolean currentSynthetic = isSyntheticDeadTabRow(current);
        boolean candidateSynthetic = isSyntheticDeadTabRow(candidate);
        if (currentSynthetic != candidateSynthetic) {
            return currentSynthetic ? candidate : current;
        }
        return current;
    }

    /**
     * True when the local player is out of the match for tabkillers purposes: kill-tracked dead and/or TAB row
     * reads as eliminated (same signals as {@link #tabSortTreatAsDeadSpectator} for the local row).
     */
    private static boolean isLocalPlayerBlitzEliminatedForTabKillersBold(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return false;
        }
        if (!(GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))) {
            return false;
        }
        String localName = mc.thePlayer.getGameProfile() != null ? mc.thePlayer.getGameProfile().getName() : null;
        if (localName != null && !localName.isEmpty() && isPlayerDead(localName)) {
            return true;
        }
        NetworkPlayerInfo localInfo = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return localInfo != null && tabSortTreatAsDeadSpectator(localInfo, mc);
    }

    /**
     * Bold victim rows only when we have a kill-tracked death for that player. Hypixel TAB colors while you spectate
     * are not reliable (§9 often appears on alive rows), so we do not use {@link #isBlitzTabHypixelSpectatorBlue} here.
     */
    private static boolean shouldBoldTabKillersVictim(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return false;
        }
        String trim = profileName.trim();
        Minecraft mc = Minecraft.getMinecraft();
        if (!isLocalPlayerBlitzEliminatedForTabKillersBold(mc)) {
            return false;
        }
        NetworkPlayerInfo liveInfo = resolveNetworkPlayerInfoForTabName(trim, mc);
        if (liveInfo == null || isSyntheticDeadTabRow(liveInfo)) {
            return false;
        }
        try {
            String base = tabSortProfileName(liveInfo);
            if (base == null || base.isEmpty()) {
                return false;
            }
            String teamFmt = ScorePlayerTeam.formatPlayerName(liveInfo.getPlayerTeam(), base);
            if (isBlitzTabHypixelSpectatorBlue(teamFmt, base)) {
                return true;
            }
            IChatComponent display = liveInfo.getDisplayName();
            return display != null && isBlitzTabHypixelSpectatorBlue(display.getFormattedText(), base);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static NetworkPlayerInfo resolveNetworkPlayerInfoForTabName(String profileName, Minecraft mc) {
        if (mc == null || mc.getNetHandler() == null || profileName == null || profileName.isEmpty()) {
            return null;
        }
        for (NetworkPlayerInfo i : mc.getNetHandler().getPlayerInfoMap()) {
            if (i != null && tabSortProfileName(i).equalsIgnoreCase(profileName)) {
                return i;
            }
        }
        return null;
    }

    private static NetworkPlayerInfo findTabRowForProfileName(List<NetworkPlayerInfo> tab, String profileOrAlias) {
        if (profileOrAlias == null || profileOrAlias.trim().isEmpty() || tab == null) {
            return null;
        }
        String trim = profileOrAlias.trim();
        NetworkPlayerInfo best = null;
        for (NetworkPlayerInfo i : tab) {
            if (i == null) {
                continue;
            }
            if (tabSortProfileName(i).equalsIgnoreCase(trim)) {
                best = preferTabKillersRow(best, i);
            }
        }
        if (best != null) {
            return best;
        }
        for (String alt : getTabVictimIdentityAliases(trim)) {
            for (NetworkPlayerInfo i : tab) {
                if (i == null) {
                    continue;
                }
                if (tabSortProfileName(i).equalsIgnoreCase(alt)) {
                    best = preferTabKillersRow(best, i);
                }
            }
        }
        if (best != null) {
            return best;
        }
        String targetIdentity = getTabKillersIdentityKey(trim);
        if (targetIdentity.isEmpty()) {
            return null;
        }
        for (NetworkPlayerInfo i : tab) {
            if (i == null) {
                continue;
            }
            if (targetIdentity.equals(getTabKillersIdentityKey(tabSortProfileName(i)))) {
                best = preferTabKillersRow(best, i);
            }
        }
        return best;
    }

    /** Pre-built lowercase profile name → NetworkPlayerInfo index for O(1) lookups during killers grouping. */
    private static Map<String, NetworkPlayerInfo> buildTabProfileIndex(List<NetworkPlayerInfo> tab) {
        Map<String, NetworkPlayerInfo> index = new HashMap<>(tab.size() * 2);
        for (NetworkPlayerInfo i : tab) {
            if (i == null) continue;
            String pn = tabSortProfileName(i);
            if (pn.isEmpty()) continue;
            String lower = pn.toLowerCase(Locale.ROOT);
            NetworkPlayerInfo existing = index.get(lower);
            index.put(lower, preferTabKillersRow(existing, i));
            // Also index by identity key and aliases
            String identity = getTabKillersIdentityKey(pn);
            if (!identity.isEmpty()) {
                existing = index.get(identity);
                index.put(identity, preferTabKillersRow(existing, i));
            }
            for (String alias : getTabVictimIdentityAliases(pn)) {
                if (alias != null && !alias.isEmpty()) {
                    String al = alias.toLowerCase(Locale.ROOT);
                    existing = index.get(al);
                    index.put(al, preferTabKillersRow(existing, i));
                }
            }
        }
        return index;
    }

    /** O(1) indexed lookup for killers grouping, falling back to full scan only when needed. */
    private static NetworkPlayerInfo findTabRowForProfileNameIndexed(Map<String, NetworkPlayerInfo> index,
            List<NetworkPlayerInfo> tab, String profileOrAlias) {
        if (profileOrAlias == null || profileOrAlias.trim().isEmpty()) {
            return null;
        }
        String lower = profileOrAlias.trim().toLowerCase(Locale.ROOT);
        NetworkPlayerInfo found = index.get(lower);
        if (found != null) {
            return found;
        }
        // Check aliases
        for (String alt : getTabVictimIdentityAliases(profileOrAlias.trim())) {
            if (alt != null && !alt.isEmpty()) {
                found = index.get(alt.toLowerCase(Locale.ROOT));
                if (found != null) return found;
            }
        }
        // Check identity key
        String identity = getTabKillersIdentityKey(profileOrAlias.trim());
        if (!identity.isEmpty()) {
            found = index.get(identity);
            if (found != null) return found;
        }
        return null;
    }

    private static List<String> orderVictimsNewestFirstForTabKillers(String killerProfileName) {
        List<String> raw = getPlayerVictims(killerProfileName);
        if (raw.isEmpty()) {
            return raw;
        }
        ArrayList<String> v = new ArrayList<String>(raw);
        v.sort((a, b) -> Long.compare(getDeathSequence(b), getDeathSequence(a)));
        return v;
    }

    /**
     * True when /tabkillers is on and this row reads as dead/spectator on TAB (kill-tracked dead, or server §9/§1).
     * Dead spectator styling no longer requires a parsed killer→victim link. Victim names stay normal-weight blue;
     * retained rows use the existing shadow and optional opacity dimming instead of italics.
     */
    private static boolean isTabKillersStyleVictim(NetworkPlayerInfo info, Minecraft mc) {
        if (TabStatsRuntimeMute.isMuted()
                || !ConfigManager.isKillCounterTabKillersEnabled()
                || mc == null
                || !isTabQolRuntimeContext(mc)
                || info == null
                || !(GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))) {
            return false;
        }
        return tabSortTreatAsDeadSpectator(info, mc);
    }

    public static String applyTabKillersStyleToRenderedName(NetworkPlayerInfo info, String renderedName) {
        if (renderedName == null || renderedName.isEmpty()) {
            return renderedName;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isRenderedTabVictim(info, mc)) {
            return BlitzChatNameDisplay.decorateTab(info, renderedName);
        }
        String base = renderedTabProfileName(info);
        // Spaces vary with Lunar's font and double up when it asks for an already-decorated name again.
        // The draw hooks move heads, logos and names by the same pixel offset instead.
        String styled = applyTabKillersVictimStylePlainLine(renderedName, base);
        if (!isRenderedTabMutualKill(info, base)) {
            return styled;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(styled);
        if (plain != null && plain.trim().endsWith(TAB_KILLERS_MUTUAL_KILL_MARKER)) {
            return styled;
        }
        return styled + EnumChatFormatting.GRAY + " " + TAB_KILLERS_MUTUAL_KILL_MARKER + EnumChatFormatting.RESET;
    }

    private static boolean isRenderedTabMutualKill(NetworkPlayerInfo info, String profileName) {
        if (info == null || profileName == null || profileName.trim().isEmpty()) {
            return false;
        }
        String identity = getTabKillersIdentityKey(profileName);
        String parentIdentity = tabKillersParentIdentityByChildIdentity.get(identity);
        if (identity.isEmpty()) {
            return false;
        }
        // Normally the grouping pass records the row's parent. A cyclic reciprocal-kill component can be
        // encountered through a renderer path that asks for the row before that parent snapshot is available;
        // determine the marker from the two recorded edges as a fallback so render order cannot hide the return kill.
        if (parentIdentity != null && !parentIdentity.isEmpty()
                && areReciprocalKillIdentities(identity, parentIdentity)) {
            return true;
        }
        return tabKillersMutualKillIdentitySet.contains(identity);
    }

    private static boolean areReciprocalKillIdentities(String first, String second) {
        if (first == null || second == null || first.trim().isEmpty() || second.trim().isEmpty()
                || first.equalsIgnoreCase(second)) {
            return false;
        }
        return victimListContainsAlias(getPlayerVictims(first), getTabVictimIdentityAliases(second))
                && victimListContainsAlias(getPlayerVictims(second), getTabVictimIdentityAliases(first));
    }

    public static int getTabKillersVictimHeadOffsetPixels(NetworkPlayerInfo info) {
        if (info == null) {
            return 0;
        }
        return renderedTabVictimHeadOffsetPixels(info, Minecraft.getMinecraft());
    }

    public static int getTabKillersVictimKillCountOffsetPixels(NetworkPlayerInfo info) {
        if (info == null) {
            return 0;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isRenderedTabVictim(info, mc)) {
            return 0;
        }
        String base = renderedTabProfileName(info);
        if (base.isEmpty()) {
            return 0;
        }
        Integer depth = tabKillersIndentDepthByLower.get(base.toLowerCase(Locale.ROOT));
        if (depth == null || depth.intValue() <= 0) {
            return ConfigManager.getKillCounterTabKillersVictimKillCountOffsetPx();
        }
        int offset = depth.intValue() * ConfigManager.getKillCounterTabKillersVictimKillCountOffsetPx();
        if (ConfigManager.isKillCounterTabKillersVictimKillCountAligned()) {
            offset -= depth.intValue() * ConfigManager.getKillCounterTabKillersVictimOffsetPx();
        }
        return offset;
    }

    /**
     * After {@link EnumChatFormatting#getTextWithoutFormattingCodes}, Hypixel teams TAB lines still begin with
     * {@code [MVP+]} / {@code [TEAM]} / {@code [DIST n]} — victim rows should show only the IGN (and any trailing
     * nick hints), not those prefixes.
     */
    private static String stripLeadingPlainBracketChainForTabVictimDisplay(String plainNoCodes) {
        if (plainNoCodes == null) {
            return "";
        }
        String s = plainNoCodes.trim();
        while (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                break;
            }
            s = s.substring(close + 1).trim();
        }
        return s;
    }

    private static String applyTabKillersVictimStylePlainLine(String tabLine, String profileName) {
        String plain = tabLine == null ? "" : EnumChatFormatting.getTextWithoutFormattingCodes(tabLine);
        if (plain == null) {
            plain = "";
        }
        String namePart = stripLeadingPlainBracketChainForTabVictimDisplay(plain);
        if (namePart.isEmpty() && profileName != null && !profileName.trim().isEmpty()) {
            namePart = profileName.trim();
        }
        // All victim rows use the same normal-weight spectator-blue text. The TAB renderer already draws names
        // with a shadow; retained rows can additionally use the separate opacity-dimming preference.
        StringBuilder styled = new StringBuilder();
        styled.append(EnumChatFormatting.BLUE);
        styled.append(namePart).append(EnumChatFormatting.RESET);
        return styled.toString();
    }

    private static boolean tabSortTreatAsDeadSpectator(NetworkPlayerInfo info, Minecraft mc) {
        if (info == null || mc == null) {
            return false;
        }
        String base = tabSortProfileName(info);
        if (base.isEmpty()) {
            return false;
        }
        if(SpectatorRelay.isTabSpectator(info))return true;
        if (isPlayerDead(base)) {
            return true;
        }
        try {
            String teamFmt = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), base);
            return isBlitzTabServerDeadBlue(teamFmt, base);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int compareWlDesc(double wa, double wb) {
        boolean na = Double.isNaN(wa);
        boolean nb = Double.isNaN(wb);
        if (na && nb) {
            return 0;
        }
        if (na) {
            return 1;
        }
        if (nb) {
            return -1;
        }
        return Double.compare(wb, wa);
    }

    private static double tabSortResolvedWl(NetworkPlayerInfo info) {
        String n = tabSortProfileName(info);
        if (n.isEmpty()) {
            return Double.NaN;
        }
        for (String key : getLookupNames(n)) {
            double w = me.napim.gomod.command.KillEffectCommand.getCachedTabSortWl(key);
            if (!Double.isNaN(w)) {
                return w;
            }
        }
        return Double.NaN;
    }

    private static boolean tabSortHasCachedMetrics(NetworkPlayerInfo info) {
        String name = tabSortProfileName(info);
        if (name.isEmpty()) return false;
        for (String key : getLookupNames(name)) {
            if (me.napim.gomod.command.KillEffectCommand.hasCachedTabSortMetrics(key)) return true;
        }
        return false;
    }

    private static int tabSortResolvedWins(NetworkPlayerInfo info) {
        String n = tabSortProfileName(info);
        if (n.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (String key : getLookupNames(n)) {
            best = Math.max(best, me.napim.gomod.command.KillEffectCommand.getCachedTabSortWins(key));
        }
        return best;
    }

    private static int tabSortResolvedOverallKills(NetworkPlayerInfo info) {
        String n = tabSortProfileName(info);
        if (n.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (String key : getLookupNames(n)) {
            best = Math.max(best, me.napim.gomod.command.KillEffectCommand.getCachedTabSortOverallKills(key));
        }
        return best;
    }

    private static String tabSortProfileName(NetworkPlayerInfo info) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null) {
            return "";
        }
        // getGameProfile() is aliased during Lunar TAB rendering; only the backing source profile is match identity.
        return RemoteDisguiseManager.getSourceProfile(info).getName();
    }
    
    public static void setAutoKeAll(boolean autoKeAll) {
        me.napim.gomod.features.ConfigManager.setKillCounterAutoKeAll(autoKeAll);
    }

    public static AutoScanMode getAutoScanMode() {
        if (TabStatsRuntimeMute.isMuted()) {
            return AutoScanMode.OFF;
        }
        return AutoScanMode.fromConfig(me.napim.gomod.features.ConfigManager.getKillCounterAutoScanMode());
    }

    public static void setAutoScanMode(AutoScanMode mode) {
        if (mode == null) mode = AutoScanMode.OFF;
        me.napim.gomod.features.ConfigManager.setKillCounterAutoScanMode(mode.getConfigValue());
    }

    public static AutoScanMode cycleAutoScanMode() {
        AutoScanMode next = getAutoScanMode().next();
        setAutoScanMode(next);
        return next;
    }

    public static boolean isAutoKeAll() {
        return getAutoScanMode() == AutoScanMode.CAGES_OPEN;
    }

    /**
     * When false, automatic bulk tabstats scans (cages-open chat, first pregame tab snapshot) are skipped so we do not
     * run dozens of sequential Hypixel calls when no TAB/HUD feature would use the results.
     */
    public static boolean needsHypixelTabstatsNetworkBulk() {
        syncTabStatsSettingsFromConfig();
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        String apiKey = me.napim.gomod.features.ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()
                || me.napim.gomod.features.GameModeStatusFetcher.shouldSkipKnownBadHypixelRequests(apiKey)) {
            return false;
        }
        if (isStatsDisplay()) {
            return true;
        }
        if (ConfigManager.isKillCounterChatStyleTab() || ConfigManager.isKillCounterChatStyleNametag()) {
            return true;
        }
        if (isShowGuildTag()) {
            return true;
        }
        if (getKillEffectTabDisplayMode() != KillEffectTabDisplayMode.OFF) {
            return true;
        }
        if (getTabListSortModeForCurrentGame() != TabListSortMode.OFF) {
            return true;
        }
        if (isDistrictTabListSortEnabled()) {
            return true;
        }
        if (ConfigManager.isKillCounterBlitzStarTabIconEnabled()) {
            return true;
        }
        if (ConfigManager.isNickRealNametagRankPlateEnabled()) {
            return true;
        }
        // Player Queuer's WLR rule consumes this same join-scan cache even when TAB stats are hidden.
        if (PlayerQueuer.isIncludeWlrRange() || PlayerQueuer.isExcludeWlrRange()) {
            return true;
        }
        return false;
    }
    
    public static boolean isShowGuildTag() {
        if (TabStatsRuntimeMute.isMuted()) {
            return false;
        }
        return me.napim.gomod.features.ConfigManager.isKillCounterShowGuildTag();
    }
    
    public static void setShowGuildTag(boolean showGuildTag) {
        me.napim.gomod.features.ConfigManager.setKillCounterShowGuildTag(showGuildTag);
        // System.out.println("[KillCounter] Guild tag display set to: " + showGuildTag);
    }
    
    /**
     * Fetches guild tags for all players currently in the tab list
     */
    private static void fetchGuildTagsForAllPlayers() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) {
                return;
            }
            
            // System.out.println("[KillCounter] Fetching guild tags for all players in tab list...");
            
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                String playerName = RemoteDisguiseManager.getSourceProfile(info).getName();
                if (playerName != null && !playerName.isEmpty() && !playerGuildTags.containsKey(playerName)) {
                    fetchGuildTag(playerName);
                }
            }
        } catch (Exception e) {
            System.err.println("[KillCounter] Error fetching guild tags for all players: " + e.getMessage());
        }
    }
    
    /**
     * Test method to manually fetch guild tag for debugging
     */
    public static void testGuildTagFetch(String playerName) {
        // System.out.println("[KillCounter] Testing guild tag fetch for " + playerName);
        // System.out.println("[KillCounter] Show guild tag setting: " + isShowGuildTag());
        fetchGuildTag(playerName);
    }
    
    /**
     * Gets the kill count for a specific player.
     */
    public static int getKillCount(String playerName) {
        if (playerName == null) {
            return 0;
        }

        List<String> lookupNames = getTabVictimIdentityAliases(playerName);
        for (String lookupName : lookupNames) {
            Integer exact = killCounts.get(lookupName);
            if (exact != null) {
                return exact;
            }
        }

        for (Map.Entry<String, Integer> entry : killCounts.entrySet()) {
            for (String lookupName : lookupNames) {
                if (entry.getKey().equalsIgnoreCase(lookupName)) {
                    return entry.getValue();
                }
            }
        }
        return 0;
    }
    
    /**
     * Gets all kill counts.
     */
    public static Map<String, Integer> getAllKillCounts() {
        return new HashMap<>(killCounts);
    }

    /**
     * Public alias resolver for consumers that need to compare result/TAB/chat names without exposing the mutable
     * kill-counter maps. Includes local-disguise, duplicate-nick, and team-invite known aliases.
     */
    public static List<String> getLookupNamesForResults(String playerName) {
        return new ArrayList<String>(getLookupNames(playerName));
    }

    /**
     * Returns a match-scoped kit name for Blitz result rows when gomod actually tracked it during the game.
     * Uses trusted kit-selection/local/teammate kit data first, then the enemy-kit cache populated only from
     * actual kit item names seen on player gear during the early detection window.
     */
    public static String getTrackedKitForResults(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return "";
        }
        for (String lookupName : getLookupNames(playerName)) {
            String kit = getCaseInsensitiveValue(playerKits, lookupName);
            if (kit != null && !kit.trim().isEmpty()) {
                return kit.trim();
            }
            kit = getCaseInsensitiveValue(enemyPlayerKits, lookupName);
            if (kit != null && !kit.trim().isEmpty()) {
                return kit.trim();
            }
        }
        return "";
    }

    /**
     * Gets the server-owned TAB rank/name formatting for a player before post-game TAB packets replace it.
     * Used by the extended Blitz results rows; returns {@code null} when the player is no longer known to TAB.
     */
    public static String getServerFormattedNameForResults(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return null;
        }
        for (String lookupName : getLookupNames(playerName)) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null
                        || !lookupName.equalsIgnoreCase(RemoteDisguiseManager.getSourceProfile(info).getName())) {
                    continue;
                }
                String formatted = resolveTabServerFormattedBase(info, RemoteDisguiseManager.getSourceProfile(info).getName());
                if (formatted != null && !formatted.trim().isEmpty()) {
                    return formatted;
                }
            }
        }
        return null;
    }
    
    /**
     * Checks if a player is marked as dead.
     */
    public static boolean isPlayerDead(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return false;
        }
        for (String lookupName : getTabVictimIdentityAliases(playerName)) {
            if (deadPlayers.contains(lookupName.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code name} is this client's profile, entity, or session IGN (nick-safe).
     */
    public static boolean isLocalClientIgn(String name) {
        return isLocalClientAnyKnownIgn(name);
    }

    /**
     * True when the local player reads as eliminated/spectating on TAB during Blitz or deathmatch,
     * including Hypixel gray/blue spectator styling before {@link #isPlayerDead(String)} catches up.
     * Used by VisibleSpecs so spectator opacity still applies when kill-chat sync lags (matches TAB sort signals).
     */
    public static boolean isLocalBlitzEliminatedOrSpectatingTabSignals(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return false;
        }
        if (!(GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))) {
            return false;
        }
        String localName = mc.thePlayer.getGameProfile() != null ? mc.thePlayer.getGameProfile().getName() : null;
        if (localName != null && !localName.isEmpty() && isPlayerDead(localName)) {
            return true;
        }
        NetworkPlayerInfo localInfo = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return localInfo != null && tabSortTreatAsDeadSpectator(localInfo, mc);
    }

    /**
     * Chat debug: runtime TAB/death state and a few sample rows (see /gomodlag tab).
     */
    public static void dumpTabListDebugToChat() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not connected.");
            return;
        }
        syncTabStatsSettingsFromConfig();
        boolean blitzActive = GameContext.isBlitzGameActive(mc);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "KillCounter runtime="
                + (enabled ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off")
                + EnumChatFormatting.GRAY + "  config="
                + (ConfigManager.isKillCounterEnabled() ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off")
                + EnumChatFormatting.GRAY + "  blitzGameActive="
                + (blitzActive ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                + EnumChatFormatting.GRAY + "  gameHasEnded="
                + gameHasEnded
                + EnumChatFormatting.GRAY + "  deadTracked="
                + deadPlayers.size());
        if (!deadPlayers.isEmpty()) {
            int shown = 0;
            StringBuilder sb = new StringBuilder();
            for (String d : deadPlayers) {
                if (shown++ >= 8) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append(EnumChatFormatting.DARK_GRAY).append(", ");
                }
                sb.append(EnumChatFormatting.YELLOW).append(d);
            }
            if (deadPlayers.size() > 8) {
                sb.append(EnumChatFormatting.DARK_GRAY).append(" … +" + (deadPlayers.size() - 8));
            }
            Main.sendMessage(EnumChatFormatting.GRAY + "dead set: " + sb);
        } else {
            Main.sendMessage(EnumChatFormatting.GRAY + "dead set: (empty — no kill/death chat parsed this match)");
        }
        Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
        int n = 0;
        for (NetworkPlayerInfo inf : infos) {
            if (n++ >= 10) {
                break;
            }
            if (inf == null || RemoteDisguiseManager.getSourceProfile(inf) == null) {
                continue;
            }
            String bn = RemoteDisguiseManager.getSourceProfile(inf).getName();
            String teamLine = ScorePlayerTeam.formatPlayerName(inf.getPlayerTeam(), bn);
            boolean tracked = isPlayerDead(bn);
            boolean serverDead = blitzActive && isBlitzTabServerDeadBlue(teamLine, bn);
            Main.sendMessage(EnumChatFormatting.GRAY + bn + ": kills=" + getKillCount(bn)
                    + "  trackedDead=" + tracked
                    + "  serverGrayDead=" + serverDead);
        }
        if (infos.size() > 10) {
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "First 10 TAB rows only.");
        }
    }

    /**
     * Debug: how TAB rows change inside {@link #finalizeTabListForOverlayRender} vs raw {@code getPlayerInfoMap}.
     * Share output when dead/duplicate rows are visible when you expected them hidden (or the opposite).
     */
    public static void dumpTabOverlayPipelineDebugToChat() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "tablistdebug: not connected.");
            return;
        }
        syncTabStatsSettingsFromConfig();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "=== tablistdebug (copy all) ===");
        Main.sendMessage(EnumChatFormatting.GRAY + "We do not remove every dead player from TAB: we sort, group (/tabkillers), hide dupes/quit rows, and strip some pregame ghosts.");
        Main.sendMessage(EnumChatFormatting.GRAY + "tabkillers="
                + (ConfigManager.isKillCounterTabKillersEnabled() ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off")
                + EnumChatFormatting.GRAY + "  tabSortMode=" + getTabListSortMode()
                + "  effectiveTabSortMode=" + getTabListSortModeForCurrentGame()
                + "  customSortCtx=" + isBlitzTabListCustomSortContext()
                + "  blitzGameActive=" + GameContext.isBlitzGameActive(mc)
                + "  blitzPregameSidebar=" + GameContext.isBlitzMatchMapPregameSidebar(mc));

        Collection<NetworkPlayerInfo> live = mc.getNetHandler().getPlayerInfoMap();
        ArrayList<NetworkPlayerInfo> raw = new ArrayList<>(live);
        Collection<NetworkPlayerInfo> piped = finalizeTabListForOverlayRender(raw);
        ArrayList<NetworkPlayerInfo> fin = new ArrayList<>(piped);

        java.util.HashMap<String, NetworkPlayerInfo> rawByUuid = new java.util.HashMap<String, NetworkPlayerInfo>();
        java.util.HashSet<String> rawU = new java.util.HashSet<String>();
        for (NetworkPlayerInfo inf : raw) {
            if (inf == null || RemoteDisguiseManager.getSourceProfile(inf) == null || RemoteDisguiseManager.getSourceProfile(inf).getId() == null) {
                continue;
            }
            String k = RemoteDisguiseManager.getSourceProfile(inf).getId().toString().replace("-", "").toLowerCase(Locale.ROOT);
            rawU.add(k);
            rawByUuid.put(k, inf);
        }
        java.util.HashSet<String> finU = new java.util.HashSet<String>();
        for (NetworkPlayerInfo inf : fin) {
            if (inf == null || RemoteDisguiseManager.getSourceProfile(inf) == null || RemoteDisguiseManager.getSourceProfile(inf).getId() == null) {
                continue;
            }
            finU.add(RemoteDisguiseManager.getSourceProfile(inf).getId().toString().replace("-", "").toLowerCase(Locale.ROOT));
        }
        Main.sendMessage(EnumChatFormatting.GRAY + "raw TAB rows=" + raw.size()
                + "  after pipeline=" + fin.size()
                + "  dc_hiddenQuitKeys=" + DeathmatchTabCleaner.debugHiddenQuitNameCount()
                + "  dc_dupUuids=" + DeathmatchTabCleaner.debugHiddenDupUuidCount());

        String sample = DeathmatchTabCleaner.formatHiddenTabNamesSample(8);
        if (sample != null && !sample.isEmpty()) {
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "quit-hide sample: " + sample);
        }

        int rm = 0;
        for (String uuid : rawU) {
            if (finU.contains(uuid)) {
                continue;
            }
            if (rm++ >= 14) {
                break;
            }
            NetworkPlayerInfo row = rawByUuid.get(uuid);
            String name = row != null && RemoteDisguiseManager.getSourceProfile(row) != null ? RemoteDisguiseManager.getSourceProfile(row).getName() : uuid;
            String fr = DeathmatchTabCleaner.tabRowFilterReason(row);
            if (fr != null) {
                Main.sendMessage(EnumChatFormatting.YELLOW + "-" + name + EnumChatFormatting.GRAY + "  removed: " + fr);
            } else if (row != null && isBlitzPregameDeadGhostRow(row, mc)) {
                Main.sendMessage(EnumChatFormatting.YELLOW + "-" + name + EnumChatFormatting.GRAY + "  removed: pregame ghost row strip");
            } else {
                Main.sendMessage(EnumChatFormatting.YELLOW + "-" + name + EnumChatFormatting.GRAY + "  removed: (unexpected — include in report)");
            }
        }
        int totalRemoved = 0;
        for (String uuid : rawU) {
            if (!finU.contains(uuid)) {
                totalRemoved++;
            }
        }
        if (totalRemoved > rm) {
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "(+" + (totalRemoved - rm) + " more removed row(s) not listed)");
        }

        java.util.HashMap<String, NetworkPlayerInfo> finByUuid = new java.util.HashMap<String, NetworkPlayerInfo>();
        for (NetworkPlayerInfo inf : fin) {
            if (inf == null || RemoteDisguiseManager.getSourceProfile(inf) == null || RemoteDisguiseManager.getSourceProfile(inf).getId() == null) {
                continue;
            }
            String k = RemoteDisguiseManager.getSourceProfile(inf).getId().toString().replace("-", "").toLowerCase(Locale.ROOT);
            finByUuid.put(k, inf);
        }
        int ad = 0;
        for (String uuid : finU) {
            if (rawU.contains(uuid)) {
                continue;
            }
            if (ad++ >= 10) {
                break;
            }
            NetworkPlayerInfo addRow = finByUuid.get(uuid);
            String addName = addRow != null && RemoteDisguiseManager.getSourceProfile(addRow) != null
                    ? RemoteDisguiseManager.getSourceProfile(addRow).getName()
                    : "?";
            Main.sendMessage(EnumChatFormatting.GREEN + "+added " + addName + EnumChatFormatting.GRAY + "  (synthetic dead row or not in raw map)");
        }
        int totalAdded = 0;
        for (String uuid : finU) {
            if (!rawU.contains(uuid)) {
                totalAdded++;
            }
        }
        if (totalAdded > ad) {
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "(+" + (totalAdded - ad) + " more added row(s) not listed)");
        }

        Main.sendMessage(EnumChatFormatting.DARK_GRAY + "Also attach: /gomodlag tab — and the exact TAB line text for any wrong player.");
    }

    public static long getDeathSequence(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return 0L;
        }
        long best = 0L;
        for (String lookupName : getTabVictimIdentityAliases(playerName)) {
            Long sequence = deathOrderByPlayer.get(lookupName.toLowerCase(Locale.ROOT));
            if (sequence != null && sequence.longValue() > best) {
                best = sequence.longValue();
            }
        }
        return best;
    }

    /**
     * Gets the maximum kill count among all players.
     */
    private static int getMaxKills() {
        int max = 0;
        for (int value : killCounts.values()) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    /**
     * Gets the maximum kill count among alive players only.
     */
    private static int getMaxKillsAlive() {
        int max = 0;
        for (Map.Entry<String, Integer> entry : killCounts.entrySet()) {
            String name = entry.getKey();
            int value = entry.getValue();
            if (!deadPlayers.contains(name.toLowerCase(Locale.ROOT)) && value > max) {
                max = value;
            }
        }
        return max;
    }
    
    /**
     * Gets the victims for a specific player.
     */
    public static List<String> getPlayerVictims(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> lookupNames = getTabVictimIdentityAliases(playerName);
        if (lookupNames.isEmpty()) {
            return new ArrayList<>();
        }

        // Kill messages can arrive under either a player's real IGN or their Hypixel nick.
        // applyKill stores the original name from each line, so returning the first alias list
        // silently drops victims recorded under the other spelling.
        List<String> mergedVictims = new ArrayList<>();
        Set<String> seenVictimAliases = new HashSet<>();
        for (String lookupName : lookupNames) {
            List<String> victims = getCaseInsensitiveValue(playerVictims, lookupName);
            if (victims == null) {
                continue;
            }
            for (String victim : victims) {
                if (victim == null || victim.trim().isEmpty()) {
                    continue;
                }
                List<String> victimAliases = getTabVictimIdentityAliases(victim);
                boolean alreadySeen = false;
                for (String victimAlias : victimAliases) {
                    if (victimAlias != null && seenVictimAliases.contains(victimAlias.toLowerCase(Locale.ROOT))) {
                        alreadySeen = true;
                        break;
                    }
                }
                if (alreadySeen) {
                    continue;
                }
                mergedVictims.add(victim);
                for (String victimAlias : victimAliases) {
                    if (victimAlias != null && !victimAlias.trim().isEmpty()) {
                        seenVictimAliases.add(victimAlias.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return mergedVictims;
    }

    /**
     * Killer recorded from chat kill lines ({@code /tabkillers} victim map), or {@code null} if unknown.
     */
    public static String getKillerForVictim(String victimName) {
        if (victimName == null || victimName.trim().isEmpty()) {
            return null;
        }
        List<String> victimAliases = getTabVictimIdentityAliases(victimName);
        if (victimAliases.isEmpty()) {
            return null;
        }
        if (shouldIgnoreKillTrackingInReplay()) {
            for (String alias : victimAliases) {
                if (alias == null) {
                    continue;
                }
                String replayKiller = replayKillerByVictim.get(alias.trim().toLowerCase(Locale.ROOT));
                if (replayKiller != null && !replayKiller.trim().isEmpty()) {
                    return replayKiller;
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : playerVictims.entrySet()) {
            if (entry == null) {
                continue;
            }
            String killerName = entry.getKey();
            List<String> victims = entry.getValue();
            if (killerName == null || killerName.trim().isEmpty() || victims == null || victims.isEmpty()) {
                continue;
            }
            if (victimListContainsAlias(victims, victimAliases)) {
                return killerName;
            }
        }
        return null;
    }

    /** True when the current world's replay/live kill feed has recorded this player as a victim. */
    public static boolean isReplayRecordedDeadPlayer(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return false;
        }
        List<String> aliases = getLookupNames(playerName);
        if (aliases.isEmpty()) {
            aliases = Collections.singletonList(playerName);
        }
        for (String alias : aliases) {
            if (alias != null && replayKillerByVictim.containsKey(alias.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Raw replay relation lookup for diagnostics, independent of current replay detection. */
    public static String getReplayRecordedKillerForVictim(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return null;
        }
        List<String> aliases = getLookupNames(playerName);
        if (aliases.isEmpty()) {
            aliases = Collections.singletonList(playerName);
        }
        for (String alias : aliases) {
            if (alias == null) {
                continue;
            }
            String killer = replayKillerByVictim.get(alias.trim().toLowerCase(Locale.ROOT));
            if (killer != null && !killer.trim().isEmpty()) {
                return killer;
            }
        }
        return null;
    }

    /**
     * Server-owned TAB/scoreboard signal used by the existing Forge dead-row sorting path. This also works when
     * Lunar leaves replay spectators in survival gamemode but colors their scoreboard-team name as eliminated.
     */
    public static boolean isPlayerDeadOrSpectatorFromTab(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return false;
        }
        if (isPlayerDead(playerName)) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return false;
        }
        List<String> aliases = getTabVictimIdentityAliases(playerName);
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null) {
                continue;
            }
            String tabName = RemoteDisguiseManager.getSourceProfile(info).getName();
            for (String alias : aliases) {
                if (alias != null && tabName.equalsIgnoreCase(alias)) {
                    try {
                        String teamFormatted = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), tabName);
                        // Use only Hypixel's definite spectator blues here. The broader TAB sorter also treats
                        // gray (§7/§8) as eliminated, but replay can use those colors for living participants.
                        if (isBlitzTabHypixelSpectatorBlue(teamFormatted, tabName)) {
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    /** Source-only native evidence: no relay-derived state or composed display names may feed back into a roster. */
    public static boolean isSpectatorRosterTabRow(NetworkPlayerInfo info) {
        if(info==null)return false;
        com.mojang.authlib.GameProfile profile=RemoteDisguiseManager.getSourceProfile(info);
        if(profile==null||profile.getName()==null)return false;
        if(info.getGameType()==net.minecraft.world.WorldSettings.GameType.SPECTATOR||isPlayerDead(profile.getName()))return true;
        return isBlitzTabHypixelSpectatorBlue(ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(),profile.getName()),profile.getName());
    }
    
    /**
     * Gets the top players sorted by kill count.
     */
    public static List<Map.Entry<String, Integer>> getTopPlayers() {
        List<Map.Entry<String, Integer>> sortedKills = new ArrayList<>(killCounts.entrySet());
        sortedKills.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return sortedKills;
    }
    
    /**
     * Gets the rank of a player (1-based, 0 if not found).
     */
    public static int getPlayerRank(String playerName) {
        List<String> lookupNames = getLookupNames(playerName);
        List<Map.Entry<String, Integer>> topPlayers = getTopPlayers();
        for (int i = 0; i < topPlayers.size(); i++) {
            for (String lookupName : lookupNames) {
                if (topPlayers.get(i).getKey().equalsIgnoreCase(lookupName)) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    public static void syncKnownAliases(String primaryName, String aliasName) {
        if (primaryName == null || aliasName == null) {
            return;
        }

        String first = primaryName.trim();
        String second = aliasName.trim();
        if (first.isEmpty() || second.isEmpty() || first.equalsIgnoreCase(second)) {
            return;
        }

        syncKillCounts(first, second);
        syncVictims(first, second);
        syncStringValue(playerColors, first, second);
        syncStringValue(playerKits, first, second);
        syncStringValue(enemyPlayerKits, first, second);
        syncStringValue(playerGuildTags, first, second);
        syncStringValue(playerGuildTagColors, first, second);
        syncDeadState(first, second);
        me.napim.gomod.command.KillEffectCommand.syncPlayerEffectAliases(first, second);

        markTabCompositionDirty();
        queueTabStatsScansForNewlyLinkedAliases(first, second);
    }

    /**
     * After tab duplicate resolution links a visible nick to a real Mojang IGN, queue tabstats if the
     * earlier join scan only saw the nick (invalid / offline Mojang profile).
     */
    private static void queueTabStatsScansForNewlyLinkedAliases(String realName, String nickName) {
        if (TabStatsRuntimeMute.isMuted() || getAutoScanMode() != AutoScanMode.PLAYERS_JOIN) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return;
            }
            lookupNamesCache.clear();
            queueTabStatsScanIfMissing(realName);
            if (nickName != null && !nickName.equalsIgnoreCase(realName)) {
                queueTabStatsScanIfMissing(nickName);
            }
        } catch (Throwable t) {
            System.err.println("[KillCounter] nick-resolved tabstats queue: " + t.getMessage());
        }
    }

    private static void queueTabStatsScanIfMissing(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return;
        }
        String lookupName = resolveTabLookupName(playerName.trim());
        if (lookupName.isEmpty()) {
            return;
        }
        if (isAnyTabstatsAliasOnCooldown(lookupName)) {
            return;
        }
        // A join scan may have cached a result under the visible nick just before denicker resolved it.
        // That alias result must not suppress the first scan of the actual Mojang account.
        boolean hasStats = me.napim.gomod.command.KillEffectCommand.hasPlayerStats(lookupName);
        boolean hasEffect = me.napim.gomod.command.KillEffectCommand.hasPlayerEffect(lookupName);
        boolean killEffectNeeded = getKillEffectTabDisplayMode() != KillEffectTabDisplayMode.OFF;
        if (hasStats && (!killEffectNeeded || hasEffect || GameContext.isBlitzPregameLobby(Minecraft.getMinecraft()))) {
            return;
        }
        me.napim.gomod.command.KillEffectCommand.tryQueuePlayerScan(lookupName);
    }

    private static List<String> getLookupNames(String playerName) {
        return getLookupNames(playerName, true);
    }

    /**
     * Names that refer to one in-match player identity. A local disguise target is cosmetic and may be a real
     * player in the same TAB list, so it must not alias that player's kill record to the disguised player's row.
     */
    private static List<String> getTabVictimIdentityAliases(String playerName) {
        return getLookupNames(playerName, false);
    }

    private static List<String> getLookupNames(String playerName, boolean includeLocalDisguiseAlias) {
        if (playerName == null) {
            return Collections.emptyList();
        }
        String rawInput = playerName.trim();
        String cleanInput = stripColors(rawInput).trim();
        String normalizedInput = cleanInput.isEmpty() ? rawInput : cleanInput;
        String cacheKey = (includeLocalDisguiseAlias ? "all:" : "match:") + normalizedInput;
        List<String> cached = lookupNamesCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        LinkedHashSet<String> lookupNames = new LinkedHashSet<>();

        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(normalizedInput);
        // Keep cosmetic disguise aliases out of the match identity lookup used by Tab Victims.
        if (includeLocalDisguiseAlias && LocalDisguise.isEnabled()) {
            String rn = LocalDisguise.getRealName();
            String tn = LocalDisguise.getTargetName();
            if (rn != null && tn != null && !rn.isEmpty() && !tn.isEmpty() && !rn.equalsIgnoreCase(tn)) {
                String p = normalizedInput;
                if (p.equalsIgnoreCase(rn)) {
                    pending.add(tn);
                } else if (p.equalsIgnoreCase(tn)) {
                    pending.add(rn);
                }
            }
            String tabDisp = LocalDisguise.getTargetTabChatDisplayNamePlain();
            if (tabDisp != null && !tabDisp.isEmpty()) {
                String p = normalizedInput;
                if (p.equalsIgnoreCase(tabDisp)) {
                    if (rn != null && !rn.isEmpty()) {
                        pending.add(rn);
                    }
                    if (tn != null && !tn.isEmpty()) {
                        pending.add(tn);
                    }
                }
            }
        }
        while (!pending.isEmpty() && lookupNames.size() < 8) {
            String current = pending.removeFirst();
            if (current == null) {
                continue;
            }

            String normalized = stripColors(current.trim()).trim();
            if (normalized.isEmpty() || !lookupNames.add(normalized)) {
                continue;
            }

            String lower = normalized.toLowerCase(Locale.ROOT);
            String mappedNick = TeamInviteResponseHandler.REAL_TO_NICK.get(lower);
            if (mappedNick != null && !mappedNick.isEmpty()) {
                pending.add(mappedNick);
            }

            String duplicateNick = TabDuplicateNickResolver.getKnownDuplicateNick(normalized);
            if (duplicateNick != null && !duplicateNick.isEmpty()) {
                pending.add(duplicateNick);
            }

            String realName = TabDuplicateNickResolver.getKnownRealName(normalized);
            if (realName != null && !realName.isEmpty()) {
                pending.add(realName);
            }

            for (Map.Entry<String, String> entry : TeamInviteResponseHandler.REAL_TO_NICK.entrySet()) {
                if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(normalized)) {
                    pending.add(entry.getKey());
                }
            }
        }
        List<String> result = new ArrayList<>(lookupNames);
        lookupNamesCache.put(cacheKey, result);
        return result;
    }

    private static void syncKillCounts(String first, String second) {
        Integer firstKills = getCaseInsensitiveValue(killCounts, first);
        Integer secondKills = getCaseInsensitiveValue(killCounts, second);
        if (firstKills == null && secondKills == null) {
            return;
        }

        int merged = Math.max(firstKills == null ? 0 : firstKills, secondKills == null ? 0 : secondKills);
        killCounts.put(first, merged);
        killCounts.put(second, merged);
    }

    private static void syncVictims(String first, String second) {
        List<String> firstVictims = getCaseInsensitiveValue(playerVictims, first);
        List<String> secondVictims = getCaseInsensitiveValue(playerVictims, second);
        if (firstVictims == null && secondVictims == null) {
            return;
        }

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (firstVictims != null) {
            merged.addAll(firstVictims);
        }
        if (secondVictims != null) {
            merged.addAll(secondVictims);
        }

        ArrayList<String> mergedList = new ArrayList<>(merged);
        playerVictims.put(first, mergedList);
        playerVictims.put(second, mergedList);
    }

    private static void syncDeadState(String first, String second) {
        boolean dead = deadPlayers.contains(first.toLowerCase(Locale.ROOT)) || deadPlayers.contains(second.toLowerCase(Locale.ROOT));
        if (dead) {
            long sequence = Math.max(getDeathSequence(first), getDeathSequence(second));
            if (sequence <= 0L) {
                sequence = deathOrderCounter.incrementAndGet();
            }
            markPlayerDead(first, sequence);
            markPlayerDead(second, sequence);
        }
    }

    /** Records the match kill under the name parsed from the server feed, without merging a cosmetic disguise target. */
    private static void incrementKillCountResolvingDisguise(String killer) {
        if (killer == null) {
            return;
        }
        Integer current = getCaseInsensitiveValue(killCounts, killer);
        killCounts.put(killer, (current == null ? 0 : current.intValue()) + 1);
    }

    private static void syncStringValue(Map<String, String> map, String first, String second) {
        String firstValue = getCaseInsensitiveValue(map, first);
        String secondValue = getCaseInsensitiveValue(map, second);
        String merged = firstValue != null && !firstValue.isEmpty() ? firstValue : secondValue;
        if (merged == null || merged.isEmpty()) {
            return;
        }

        map.put(first, merged);
        map.put(second, merged);
    }

    private static <T> T getCaseInsensitiveValue(Map<String, T> map, String key) {
        if (map.containsKey(key)) {
            return map.get(key);
        }

        for (Map.Entry<String, T> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    /**
     * Gets the color for a player based on their rank.
     */
    public static String getPlayerColorByRank(String playerName) {
        int rank = getPlayerRank(playerName);
        if (rank == 1) {
            return EnumChatFormatting.GOLD.toString(); // Gold for 1st only
        }
        // All others (2nd, 3rd, etc.) use default color
        return playerColors.getOrDefault(playerName, EnumChatFormatting.WHITE.toString());
    }
    
    /**
     * Gets the color for a player in the tab list (only top killer gets orange).
     */
    public static String getPlayerTabColor(String playerName) {
        if (!enabled) {
            return null;
        }
        
        int rank = getPlayerRank(playerName);
        if (rank == 1) {
            // System.out.println("[KillCounter] Top killer detected for tab coloring: " + playerName);
            return EnumChatFormatting.GOLD.toString(); // Orange/Gold for top killer only
        }
        return null; // No color change for others
    }
    
    private static boolean isLocalPlayerTrackedDead(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) {
            return false;
        }
        String localName = mc.thePlayer.getGameProfile().getName();
        return localName != null && !localName.trim().isEmpty() && isPlayerDead(localName);
    }

    /**
     * Checks whether a tracked Blitz match ended. A missing "Taunt" line alone is not enough: Hypixel's spectator
     * sidebar can omit it while the match is still running.
     */
    private static boolean isGameEnded() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return false;
            }
            
            // Only check for game end if we are actively tracking a game.
            // Using 'enabled' instead of killCounts.isEmpty() to avoid a race
            // condition where kills are cleared before the end-check fires.
            if (!enabled) {
                return false;
            }

            // Pregame sidebar has no "Taunt:" but sawTaunt may still be true from the last game until
            // start()/stopTemporarily reset it — do not treat that as match end or TAB composition breaks.
            if (GameContext.isBlitzPregameLobby(mc)) {
                return false;
            }
            // "GAME OVER" replaces Blitz sidebar Taunt — avoid end-game wipe / double [kills] until hub/pregame.
            if (GameContext.isBlitzGameOverSidebar(mc)) {
                return false;
            }

            // Spectator sidebars can omit the Taunt line even though the Blitz match continues. Keep match
            // kill/victim data while the broader sidebar still signals a live match, and while our local death
            // is tracked outside the Hypixel lobby (including a brief sidebar/objective transition).
            if (GameContext.isBlitzGameActive(mc)
                    || GameContext.isBlitzDeathmatchPhase(mc)
                    || (isLocalPlayerTrackedDead(mc) && !GameContext.isHypixelLobby(mc))) {
                return false;
            }

            net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
            net.minecraft.scoreboard.ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1); // Sidebar
            
            // Sidebar went away while we had active kill tracking — the game ended
            // (Hypixel replaces / removes the sidebar on the end-game results screen)
            if (objective == null) {
                return sawTaunt;
            }
            
            // Look for "Taunt:" in the scoreboard - if it's missing, game has ended.
            // Strip colour codes first so that colour codes injected inside "Taunt:" don't fool the check.
            boolean foundTaunt = false;
            for (net.minecraft.scoreboard.Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                String cleanLine = stripColors(line);
                
                if (cleanLine.toLowerCase(Locale.ROOT).contains("taunt")) {
                    foundTaunt = true;
                    break;
                }
            }
            
            if (foundTaunt) {
                sawTaunt = true;
                return false; // Game is still active
            }
            
            // Only treat missing "Taunt" as game end if we've seen it earlier this game.
            return sawTaunt;
        } catch (Exception e) {
            // System.out.println("[KillCounter] Exception in isGameEnded: " + e.getMessage());
            return false;
        }
    }

           /**
           * Detects teleportation/respawn packets to auto-stop TAB Stats.
            */
           @SubscribeEvent
           public void onTeleport(Receive event) {
               // S38/S3E dirty marking is handled by KillEffectCommand.onPackets() with burst coalescing.
               // Do NOT mark dirty here — it bypasses the S3E burst suppression and causes redundant recomposes.

                // Stop on server-wide changes (join game, respawn, dimension changes)
                // Only stop on actual server joins, not respawns (which happen during normal gameplay)
                if (event.getPacket() instanceof S01PacketJoinGame) {
                    onWorldTransition();
                    // Packet handlers run on Netty's thread. Defer all world/TAB access until Minecraft's client
                    // thread so a /l, /requeue, or pregame->match handoff cannot race NetworkPlayerInfo teardown.
                    runOnClientThread(() -> {
                        PlayerTarget.clearTauntStateForNewMatch();
                        stopTemporarily(); // Don't change user settings, just clear current game data
                        clearTabOverlayWorldCaches();
                        lastTabQolContextWorld = null;
                        lastTabQolContextActive = false;
                        markTabCompositionDirty();
                        schedulePostJoinTabRefreshes();
                    });
                    return;
                }

               if (!enabled) {
                   return;
               }
               
               // Update world name tracking for debugging (but don't auto-stop)
               try {
                   Minecraft mc = Minecraft.getMinecraft();
                   if (mc.theWorld != null) {
                       String currentWorldName = mc.theWorld.getWorldInfo().getWorldName();
                       if (lastWorldName != null && !lastWorldName.equals(currentWorldName)) {
                           // System.out.println("[KillCounter] World changed: " + lastWorldName + " -> " + currentWorldName + " (not auto-stopping)");
                       }
                       lastWorldName = currentWorldName;
                   }
               } catch (Exception e) {
                   // Ignore world change detection errors
               }
           }

    /**
     * Plain chat text for triggers (kills, Blitz timers). Hypixel often sends JSON chat where
     * {@link IChatComponent#getUnformattedText()} is empty but {@link IChatComponent#getFormattedText()} is not.
     */
    /**
     * When watching a Hypixel replay, chat still contains kill/death lines; they must not advance local kill counts.
     */
    private static boolean shouldIgnoreKillTrackingInReplay() {
        try {
            if (ReplaySettings.isReplayActive()) {
                return true;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && GameContext.isHypixelReplayWatching(mc)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String resolveChatPlainText(IChatComponent chatComponent) {
        if (chatComponent == null) {
            return "";
        }
        try {
            String u = chatComponent.getUnformattedText();
            if (u != null && !u.trim().isEmpty()) {
                return u;
            }
            String f = chatComponent.getFormattedText();
            if (f != null && !f.isEmpty()) {
                return stripColors(f);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

           /**
            * Processes chat messages to detect kills and auto-start.
            */
           @SubscribeEvent
           public void onChat(Receive event) {
        if (!(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        
        S02PacketChat chatPacket = (S02PacketChat) event.getPacket();
        IChatComponent chatComponent = chatPacket.getChatComponent();
        // Taunt / coin lines: run before resolveChatPlainText — Hypixel JSON often yields empty "message" but
        // formatted IChatComponent still contains "performed the" / "Taunt Bonus".
        PlayerTarget.processTauntChatFromPacket(chatComponent);
        String message = resolveChatPlainText(chatComponent);
        if (message == null || message.isEmpty()) {
            return;
        }
        GoStatsLeaderboardSearchLink.appendSearchLink(chatComponent, message);

        // Hypixel sends genuine kill/effect announcements using more than one S02 chat type.
        // The tracker validates the message shape and rejects player-prefixed chat itself.
        me.napim.gomod.features.BlitzFlameKillEffectTracker.onChatMessage(message, chatPacket.getType());
        BarrierVisibility.onChatMessage(message);

        tryHideStaleBlitzPregameCageTabRows(message, chatComponent);

        BlitzStarTabTracker.onChatUnformatted(message);

        // Keep a lightweight victim -> killer relation for nametag overlays in every context. Replay detection can
        // lag behind its first chat packets while the scoreboard is still settling, so recording only after a
        // positive replay check loses exactly the early kills that replays commonly start with.
        recordReplayKillerRelation(chatComponent, message);

        // Replay viewer replays the same kill-feed lines as a live game — do not increment TAB Stats / kill HUD.
        if (shouldIgnoreKillTrackingInReplay()) {
            return;
           }

        Minecraft mcChat = Minecraft.getMinecraft();
        if (!isTabQolRuntimeContext(mcChat)) {
            observeDisplayedLiveKillFeed(chatComponent, message, "packet-context-miss");
            return;
        }

        boolean userEnabled = ConfigManager.isKillCounterEnabled();
        
        // Check for auto-start message (strict: only whitespace allowed around exact phrase)
        if (!enabled && userEnabled && message.replaceAll("\\s+", " ").trim().equals("Survive while eliminating")) {
            start();
            // System.out.println("[KillCounter] Auto-started TAB Stats from game message!");
            return;
        }
        
        // Check for cages-open / pre-release message and auto-run tabstats all if enabled
        if (isBlitzStarThreeMinuteReleaseMessage(message)) {
            if (HypixelApiRequestDebug.isDetailedCaptureEnabled()) {
                HypixelApiRequestDebug.record("tab.cages_open", "autoScanMode=" + getAutoScanMode());
            }
            if (getAutoScanMode() == AutoScanMode.CAGES_OPEN && needsHypixelTabstatsNetworkBulk()) {
                // Delay on a worker thread so Hypixel TAB/team packets can settle before we snapshot UUIDs and run HTTP.
                // (Also avoids stacking with the first-second client hitch when cages open.)
                startDaemonThread("gomod-cages-tabstats", () -> {
                    try {
                        Thread.sleep(2500);
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc != null) {
                            mc.addScheduledTask(() -> me.napim.gomod.command.KillEffectCommand.runTabStatsAllScanAsync(true,
                                    "auto: Blitz cages-open chat message"));
                        }
                    } catch (InterruptedException e) {
                        System.err.println("[KillCounter] Failed to auto-run tabstats all scan: " + e.getMessage());
                    }
                });
                // Missing-tab passes are scheduled when the full scan finishes (see KillEffectCommand.runTabStatsAllScan)
                // so we never race the sequential scan and double-request the same players.
            } else if (getAutoScanMode() == AutoScanMode.PLAYERS_JOIN) {
                schedulePlayersJoinPostCagesRefresh();
            }
            return;
        }
        
        // Check for the 2-minute message when players receive their kits (loose match — Hypixel wording varies)
        if (isBlitzStarTwoMinuteReleaseMessage(message)) {
            cagesOpenTime = System.currentTimeMillis();
            // Schedule kit detection to start 5 seconds after players get their kits
            // Note: This will only detect enemy kits for players who don't already have teammate kits displayed
            startDaemonThread("gomod-enemy-kit-delay", () -> {
                try {
                    Thread.sleep(5000); // 5 second delay
                    startBriefKitScanning();
                } catch (InterruptedException e) {
                    System.err.println("[KillCounter] Kit detection timer interrupted: " + e.getMessage());
                }
            });
            return;
        }
        
        // Check for "The game starts in 1 second!" message to detect BLITZ HOUR
        if (message.contains("The game starts in 1 second!")) {
            // System.out.println("[KillCounter] Game starting soon - checking for BLITZ HOUR in scoreboard...");
            // Check scoreboard for BLITZ HOUR text
            blitzHourActive = checkScoreboardForBlitzHour();
            if (blitzHourActive) {
                blitzHourDetectionTime = System.currentTimeMillis();
                // System.out.println("[KillCounter] BLITZ HOUR detected in scoreboard!");
            } else {
                blitzHourDetectionTime = 0L;
                // System.out.println("[KillCounter] No BLITZ HOUR detected in scoreboard - normal mode");
            }
            return;
        }
        
        if (!enabled) {
            if (!userEnabled) {
                return;
            }

            if (isLocalKitReceiveMessage(message)) {
                start();
                tryProcessLocalKitReceiveMessage(message);
                return;
            }

            // If settings are enabled but runtime isn't active yet, bootstrap on kit or kill message.
            if (message.contains(KIT_SELECTION_PATTERN)) {
                if (isKitSelectionServerMessage(message)) {
                    start();
                    processKitSelectionMessage(message);
                }
                return;
            }

            if (tryProcessSelfKillFromChat(chatComponent, message, true)) {
                return;
            }

            if (tryProcessSimpleDeathFromChat(chatComponent, message, true)) {
                return;
            }

            String pattern = findKillPattern(message);
            if (pattern != null) {
                start();
                tryProcessKillFromChat(chatComponent, message, pattern);
                return;
            }

            return;
        }

        if (tryProcessLocalKitReceiveMessage(message)) {
            return;
        }
        
        // Check for kit selection messages
        if (message.contains(KIT_SELECTION_PATTERN)) {
            // Only process if the message starts with a player name (server message)
            // This prevents false positives when players type messages containing kit selection text
            if (isKitSelectionServerMessage(message)) {
                processKitSelectionMessage(message);
            }
            return;
        }
        
        // Check for kill patterns
        if (tryProcessSelfKillFromChat(chatComponent, message)) {
            return;
        }
        if (tryProcessSimpleDeathFromChat(chatComponent, message)) {
            return;
        }
        String pattern = findKillPattern(message);
        if (pattern != null) {
            tryProcessKillFromChat(chatComponent, message, pattern);
        }
    }

    /**
     * Weave/Lunar Replay may inject playback chat after packet handling, so no {@link S02PacketChat} exists for
     * KillCounter's packet listener. Observe the shared loader chat event as a second, idempotent source.
     */
    @SubscribeEvent
    public void onDisplayedChatForReplay(ChatReceivedEvent event) {
        if (event != null) {
            observeDisplayedReplayChat(event.getMessage());
        }
    }

    /** Also called by GuiNewChatMixin for replay playback and as a live kill-feed fallback. */
    public static void observeDisplayedReplayChat(IChatComponent chatComponent) {
        if (chatComponent == null) return;
        String message = resolveChatPlainText(chatComponent);
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (shouldIgnoreKillTrackingInReplay()) {
            recordReplayKillerRelation(chatComponent, message);
            return;
        }
        KillCounter observer = chatObserverInstance;
        if (observer != null) observer.observeDisplayedLiveKillFeed(chatComponent, message, "chat-display");
    }

    /** Live kill-feed fallback for cases where Hypixel's Blitz sidebar is recognized after the packet arrives. */
    private void observeDisplayedLiveKillFeed(IChatComponent component, String message, String source) {
        String pattern = findKillPattern(message);
        if (pattern == null || !ConfigManager.isKillCounterEnabled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (isLiveBlitzKillFeedContext(mc)) {
            removePendingKillFeedLine(message);
            if (!enabled) start();
            recordTabVictimsKillDebugEvent("display-observer source=" + source + " context=blitz-active line="
                    + compactKillDebugLine(message));
            tryProcessKillFromChat(component, message, pattern);
            return;
        }
        enqueuePendingKillFeedLine(mc, component, message, pattern, source);
    }

    private static String findKillPattern(String message) {
        if (message == null || message.isEmpty() || !isAuthoritativeDeathMessage(message)) return null;
        for (String pattern : KILL_PATTERNS) {
            if (message.contains(pattern)) return pattern;
        }
        return null;
    }

    /**
     * Hypixel relays player speech and server notices through chat packets. Only its strict whole-line death
     * formats are eligible for kill/death state; a chat line such as "Name: Victim was killed by Killer" is not.
     */
    private static boolean isAuthoritativeDeathMessage(String message) {
        return ArmorDisguiseDeathSignal.victim(message) != null;
    }

    private static boolean isLiveBlitzKillFeedContext(Minecraft mc) {
        return mc != null && GameContext.isOnHypixel(mc)
                && (GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc));
    }

    private static void enqueuePendingKillFeedLine(Minecraft mc, IChatComponent component,
            String message, String pattern, String source) {
        if (mc == null || !ConfigManager.isKillCounterEnabled() || !GameContext.isOnHypixel(mc)
                || GameContext.isHypixelLobby(mc)) return;
        long now = System.currentTimeMillis();
        String formatted = component == null ? message : component.getFormattedText();
        PendingKillFeedLine pending = new PendingKillFeedLine(now, message, formatted, pattern);
        synchronized (pendingKillFeedLock) {
            prunePendingKillFeedLinesLocked(now);
            for (PendingKillFeedLine queued : pendingKillFeedLines) {
                if (queued.normalized.equals(pending.normalized)
                        && now - queued.queuedAtMs <= PENDING_KILL_FEED_DUPLICATE_WINDOW_MS) return;
            }
            while (pendingKillFeedLines.size() >= PENDING_KILL_FEED_LIMIT) pendingKillFeedLines.removeFirst();
            pendingKillFeedLines.addLast(pending);
        }
        recordTabVictimsKillDebugEvent("queued-kill-feed source=" + source + " reason=blitz-context-not-ready line="
                + compactKillDebugLine(message));
    }

    private static void prunePendingKillFeedLinesLocked(long now) {
        while (!pendingKillFeedLines.isEmpty()
                && now - pendingKillFeedLines.peekFirst().queuedAtMs > PENDING_KILL_FEED_TTL_MS) {
            PendingKillFeedLine expired = pendingKillFeedLines.removeFirst();
            recordTabVictimsKillDebugEvent("expired-pending-kill-feed line=" + compactKillDebugLine(expired.plain));
        }
    }

    private static void removePendingKillFeedLine(String message) {
        if (message == null) return;
        String normalized = message.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        synchronized (pendingKillFeedLock) {
            java.util.Iterator<PendingKillFeedLine> iterator = pendingKillFeedLines.iterator();
            while (iterator.hasNext()) {
                if (normalized.equals(iterator.next().normalized)) iterator.remove();
            }
        }
    }

    private static void clearPendingKillFeedLines() {
        synchronized (pendingKillFeedLock) {
            pendingKillFeedLines.clear();
        }
    }

    private void flushPendingLiveKillFeed() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isLiveBlitzKillFeedContext(mc) || !ConfigManager.isKillCounterEnabled()) {
            synchronized (pendingKillFeedLock) {
                prunePendingKillFeedLinesLocked(System.currentTimeMillis());
            }
            return;
        }
        ArrayList<PendingKillFeedLine> pending = new ArrayList<PendingKillFeedLine>();
        synchronized (pendingKillFeedLock) {
            long now = System.currentTimeMillis();
            prunePendingKillFeedLinesLocked(now);
            while (!pendingKillFeedLines.isEmpty()) pending.add(pendingKillFeedLines.removeFirst());
        }
        if (pending.isEmpty()) return;
        if (!enabled) start();
        for (PendingKillFeedLine line : pending) {
            if (line == null || line.pattern == null) continue;
            recordTabVictimsKillDebugEvent("flushed-kill-feed source=client-tick context=blitz-active line="
                    + compactKillDebugLine(line.plain));
            IChatComponent component = line.formatted == null ? null : new ChatComponentText(line.formatted);
            tryProcessKillFromChat(component, line.plain, line.pattern);
        }
    }

    /**
     * Seeking directly past a death can populate Lunar's chat history without emitting a fresh packet/event. Scan
     * that history only while a currently dead replay actor has no known killer, and at most once per second.
     */
    public static void scanReplayChatHistoryForMissingKillerRelations() {
        if (!shouldIgnoreKillTrackingInReplay()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplayChatHistoryScanMs < 1000L) {
            return;
        }
        lastReplayChatHistoryScanMs = now;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.ingameGUI == null || mc.ingameGUI.getChatGUI() == null) {
                return;
            }
            boolean missing = false;
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player != mc.thePlayer && VisibleSpecs.isReplaySpectatorSettingsTarget(player)
                        && getReplayRecordedKillerForVictim(player.getName()) == null) {
                    missing = true;
                    break;
                }
            }
            if (!missing) {
                return;
            }
            List<ChatLine> lines = ((GuiNewChatAccessor) (Object) mc.ingameGUI.getChatGUI()).gomod$getDrawnChatLines();
            if (lines == null || lines.isEmpty()) {
                return;
            }
            int scanned = 0;
            for (ChatLine line : lines) {
                if (line != null && line.getChatComponent() != null) {
                    observeDisplayedReplayChat(line.getChatComponent());
                }
                if (++scanned >= 200) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Parses kill lines from plain text; if names can't be extracted (common with some Hypixel / disguise
     * component layouts), retry using formatted chat with color codes stripped.
     */
    private void tryProcessKillFromChat(IChatComponent chatComponent, String primaryPlain, String pattern) {
        if (processKillMessage(primaryPlain, pattern)) {
            return;
        }
        tryMarkDeadVictimFromBroadcastKillLine(primaryPlain, pattern);
        if (chatComponent == null) {
            return;
        }
        String alt = stripColors(chatComponent.getFormattedText());
        if (alt != null && !alt.isEmpty() && !alt.equals(primaryPlain)) {
            if (!processKillMessage(alt, pattern)) {
                tryMarkDeadVictimFromBroadcastKillLine(alt, pattern);
            }
        }
    }

    /**
     * Records only the victim -> killer relationship from replay kill feed. This deliberately does not call
     * {@link #applyKill}, so replay playback cannot increment TAB stats, mark players dead, or fire challenges.
     */
    private static void recordReplayKillerRelation(IChatComponent chatComponent, String primaryPlain) {
        if (tryRecordReplayKillerRelation(primaryPlain)) {
            return;
        }
        if (chatComponent == null) {
            return;
        }
        try {
            String alternate = stripColors(chatComponent.getFormattedText());
            if (alternate != null && !alternate.isEmpty() && !alternate.equals(primaryPlain)) {
                tryRecordReplayKillerRelation(alternate);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean tryRecordReplayKillerRelation(String message) {
        if (message == null || message.trim().isEmpty() || !isAuthoritativeDeathMessage(message)) {
            return false;
        }
        String clean = message.replaceAll("\\s+", " ").trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        for (String pattern : KILL_PATTERNS) {
            int patternIndex = lower.indexOf(pattern.toLowerCase(Locale.ROOT));
            if (patternIndex < 0) {
                continue;
            }
            try {
                String victimPart = clean.substring(0, patternIndex);
                String killerPart = clean.substring(patternIndex + pattern.length());
                String victim = resolveYouToLocalPlayer(extractPlayerName(victimPart));
                String killer = extractPlayerNameFromStart(killerPart);
                if (killer == null) {
                    killer = extractPlayerName(killerPart);
                }
                killer = resolveYouToLocalPlayer(killer);
                if (victim == null || killer == null || victim.equalsIgnoreCase(killer)) {
                    continue;
                }
                Set<String> aliases = new LinkedHashSet<>(getLookupNames(victim));
                aliases.add(victim);
                boolean changed = false;
                for (String alias : aliases) {
                    if (alias != null && !alias.trim().isEmpty()) {
                        String previous = replayKillerByVictim.put(alias.trim().toLowerCase(Locale.ROOT), killer);
                        changed |= previous == null || !previous.equalsIgnoreCase(killer);
                    }
                }
                if (changed && shouldIgnoreKillTrackingInReplay()) {
                    System.out.println("[gomod][ReplayKill] victim=" + victim + " killer=" + killer);
                }
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * When {@link #processKillMessage} fails (e.g. killer name extraction oddities), still mark the victim dead from
     * the left side of the kill phrase so TAB styling updates (Hypixel can leave §f until the scoreboard team updates).
     */
    private void tryMarkDeadVictimFromBroadcastKillLine(String message, String pattern) {
        if (message == null || gameHasEnded || pattern == null || pattern.isEmpty()
                || !isAuthoritativeDeathMessage(message)) {
            return;
        }
        if (!message.contains(pattern)) {
            return;
        }
        String[] parts = message.split(java.util.regex.Pattern.quote(pattern), 2);
        if (parts.length < 2) {
            return;
        }
        String victim = extractPlayerName(parts[0].trim());
        victim = resolveYouToLocalPlayer(victim);
        if (victim == null || victim.isEmpty()) {
            return;
        }
        markPlayerDead(victim);
        markTabCompositionDirty();
    }

    private boolean tryProcessSelfKillFromChat(IChatComponent chatComponent, String primaryPlain) {
        return tryProcessSelfKillFromChat(chatComponent, primaryPlain, false);
    }

    /** Starts a cold tracker before applying the first self-kill so {@link #start()} cannot erase that event. */
    private boolean tryProcessSelfKillFromChat(IChatComponent chatComponent, String primaryPlain,
            boolean startRuntimeBeforeApplying) {
        if (!isAuthoritativeDeathMessage(primaryPlain)) {
            return false;
        }
        if (tryProcessSelfKillMessage(primaryPlain, startRuntimeBeforeApplying)) {
            return true;
        }
        if (chatComponent == null) {
            return false;
        }
        String alt = stripColors(chatComponent.getFormattedText());
        if (alt != null && !alt.isEmpty() && !alt.equals(primaryPlain)) {
            return isAuthoritativeDeathMessage(alt)
                    && tryProcessSelfKillMessage(alt, startRuntimeBeforeApplying);
        }
        return false;
    }

    private boolean tryProcessSimpleDeathFromChat(IChatComponent chatComponent, String primaryPlain) {
        return tryProcessSimpleDeathFromChat(chatComponent, primaryPlain, false);
    }

    /** Starts a cold tracker before recording local elimination, preserving the dead-state for TAB/end detection. */
    private boolean tryProcessSimpleDeathFromChat(IChatComponent chatComponent, String primaryPlain,
            boolean startRuntimeBeforeApplying) {
        if (!isAuthoritativeDeathMessage(primaryPlain)) {
            return false;
        }
        if (tryProcessSimpleDeathMessage(primaryPlain, startRuntimeBeforeApplying)) {
            return true;
        }
        if (chatComponent == null) {
            return false;
        }
        String alt = stripColors(chatComponent.getFormattedText());
        if (alt != null && !alt.isEmpty() && !alt.equals(primaryPlain)) {
            return isAuthoritativeDeathMessage(alt)
                    && tryProcessSimpleDeathMessage(alt, startRuntimeBeforeApplying);
        }
        return false;
    }

    private boolean tryProcessSelfKillMessage(String message) {
        return tryProcessSelfKillMessage(message, false);
    }

    private boolean tryProcessSelfKillMessage(String message, boolean startRuntimeBeforeApplying) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String clean = message.replaceAll("\\s+", " ").trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("you ")) {
            return false;
        }
        for (String pattern : SELF_KILL_PATTERNS) {
            String needle = "you " + pattern;
            if (!lower.startsWith(needle)) {
                continue;
            }
            String rest = clean.substring(needle.length());
            String victim = extractPlayerNameFromStart(rest);
            if (victim == null) {
                victim = extractPlayerName(rest);
            }
            String killer = resolveYouToLocalPlayer("you");
            if (killer == null || victim == null || victim.equalsIgnoreCase(killer)) {
                return false;
            }
            if (startRuntimeBeforeApplying && !enabled) {
                start();
            }
            return applyKill(killer, victim, message);
        }
        return false;
    }

    private boolean tryProcessSimpleDeathMessage(String message) {
        return tryProcessSimpleDeathMessage(message, false);
    }

    private boolean tryProcessSimpleDeathMessage(String message, boolean startRuntimeBeforeApplying) {
        if (message == null || message.trim().isEmpty() || gameHasEnded) {
            return false;
        }
        String clean = message.replaceAll("\\s+", " ").trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        for (String pattern : SIMPLE_DEATH_PATTERNS) {
            int index = lower.indexOf(pattern);
            if (index <= 0) {
                continue;
            }
            // Victim is always before "was killed!" / "were killed!". extractPlayerName() scans from the last token and
            // would wrongly pick "killed" (valid [A-Za-z0-9_]{3,16}) instead of the real IGN (e.g. SaturnAndJupiter).
            String victim = extractPlayerNameFromStart(clean.substring(0, index).trim());
            victim = resolveYouToLocalPlayer(victim);
            if (victim == null || victim.isEmpty()) {
                continue;
            }
            if (startRuntimeBeforeApplying && !enabled) {
                start();
            }
            markPlayerDead(victim);
            markTabCompositionDirty();
            return true;
        }
        return false;
    }

    /**
     * Blitz pregame / cages: Hypixel can leave a ghost TAB row (often §f) after {@code has quit!} or env {@code was killed!}.
     * Hides those names client-side (same mechanism as duplicate-row cleanup) and logs one debug line per player.
     */
    private void tryHideStaleBlitzPregameCageTabRows(String message, IChatComponent chatComponent) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
            if (!GameContext.isBlitzPregameLobby(mc) && !GameContext.isBlitzCagePhaseSidebar(mc)) {
                return;
            }
            LinkedHashSet<String> targets = new LinkedHashSet<String>();
            collectStaleBlitzTabHideTargetsFromLine(this, message, targets);
            if (chatComponent != null) {
                String alt = stripColors(chatComponent.getFormattedText());
                if (alt != null && !alt.isEmpty() && !alt.equals(message)) {
                    collectStaleBlitzTabHideTargetsFromLine(this, alt, targets);
                }
            }
            if (targets.isEmpty()) {
                return;
            }
            for (String victim : targets) {
                for (String alias : getLookupNames(victim)) {
                    DeathmatchTabCleaner.hideTabName(alias);
                }
                markTabCompositionDirty();
                if (ConfigManager.isDebugMessagesToChatEnabled()) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "[debug] hid stale Blitz TAB row (quit/env death, Hypixel ghost): "
                            + EnumChatFormatting.YELLOW + victim);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectStaleBlitzTabHideTargetsFromLine(KillCounter k, String message, Set<String> sink) {
        if (message == null || k == null) {
            return;
        }
        String clean = message.replaceAll("\\s+", " ").trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        int qi = lower.lastIndexOf(" has quit!");
        if (qi >= 0 && lower.endsWith(" has quit!")) {
            String n = k.extractPlayerName(clean.substring(0, qi).trim());
            if (n != null && !n.isEmpty()) {
                sink.add(n);
            }
            return;
        }
        qi = lower.lastIndexOf(" have quit!");
        if (qi >= 0 && lower.endsWith(" have quit!")) {
            String n = k.extractPlayerName(clean.substring(0, qi).trim());
            if (n != null && !n.isEmpty()) {
                sink.add(n);
            }
            return;
        }
        if (lower.contains(" was killed by ")) {
            return;
        }
        for (String pat : SIMPLE_DEATH_PATTERNS) {
            int idx = lower.indexOf(pat);
            if (idx <= 0) {
                continue;
            }
            String n = k.extractPlayerNameFromStart(clean.substring(0, idx).trim());
            n = k.resolveYouToLocalPlayer(n);
            if (n != null && !n.isEmpty()) {
                sink.add(n);
            }
            return;
        }
    }

    /**
     * PLAYERS_JOIN scans in pregame for WLR/stats, but Hypixel kill-effect choices can still change before cages open.
     * After the cages-open signal, slowly retry TAB rows whose 60s per-player Hypixel cooldown has expired.
     */
    private static void schedulePlayersJoinPostCagesRefresh() {
        if (getKillEffectTabDisplayMode() == KillEffectTabDisplayMode.OFF) {
            return;
        }
        final int generation = playersJoinPostCagesRefreshGeneration.incrementAndGet();
        startDaemonThread("gomod-players-join-post-cages-refresh", () -> {
            long endAt = System.currentTimeMillis() + PLAYERS_JOIN_POST_CAGES_REFRESH_WINDOW_MS;
            java.util.Set<String> queuedUuids = new java.util.HashSet<>();
            while (generation == playersJoinPostCagesRefreshGeneration.get() && System.currentTimeMillis() < endAt) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc == null) {
                    break;
                }

                final int[] result = new int[] {0}; // 0 none this pass, 1 queued one, 2 complete/stop
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                try {
                    mc.addScheduledTask(() -> {
                        try {
                            result[0] = tryQueueOnePlayersJoinPostCagesRefresh(queuedUuids);
                        } finally {
                            latch.countDown();
                        }
                    });
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    System.err.println("[KillCounter] PLAYERS_JOIN post-cages refresh failed: " + t.getMessage());
                }

                if (result[0] == 2) {
                    break;
                }
                try {
                    Thread.sleep(PLAYERS_JOIN_POST_CAGES_REFRESH_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * @return 0 = nothing queued this pass, 1 = queued one player, 2 = done/stop
     */
    private static int tryQueueOnePlayersJoinPostCagesRefresh(java.util.Set<String> queuedUuids) {
        if (getAutoScanMode() != AutoScanMode.PLAYERS_JOIN
                || getKillEffectTabDisplayMode() == KillEffectTabDisplayMode.OFF) {
            return 2;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return 2;
        }
        if (!GameContext.isBlitzGameActive(mc)
                || GameContext.isBlitzPregameLobby(mc)
                || GameContext.isBlitzCagePhaseSidebar(mc)) {
            return 0;
        }

        boolean hasUnqueuedTabPlayer = false;
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getId() == null) {
                continue;
            }
            String name = RemoteDisguiseManager.getSourceProfile(info).getName();
            if (name == null || name.trim().isEmpty() || name.equalsIgnoreCase(mc.thePlayer.getName())) {
                continue;
            }
            String uuid = RemoteDisguiseManager.getSourceProfile(info).getId().toString().replace("-", "").toLowerCase(Locale.ROOT);
            if (uuid.isEmpty() || queuedUuids.contains(uuid)) {
                continue;
            }
            hasUnqueuedTabPlayer = true;
            if (me.napim.gomod.command.KillEffectCommand.tryQueuePlayerScanFromTab(info, false, true)) {
                queuedUuids.add(uuid);
                return 1;
            }
        }
        return hasUnqueuedTabPlayer ? 0 : 2;
    }

    /**
     * Retries only cages-open kill-effect refreshes that the bulk scan could not perform because Hypixel's 60-second
     * per-player cooldown was still active. Rows successfully refreshed by the bulk scan are never queued here.
     */
    private static void scheduleCagesOpenDeferredKillEffectRefresh() {
        if (getAutoScanMode() != AutoScanMode.CAGES_OPEN
                || getKillEffectTabDisplayMode() == KillEffectTabDisplayMode.OFF
                || !me.napim.gomod.command.KillEffectCommand.hasPendingCagesOpenKillEffectRefresh()) {
            return;
        }
        final int generation = playersJoinPostCagesRefreshGeneration.incrementAndGet();
        startDaemonThread("gomod-cages-kill-effect-refresh", () -> {
            long endAt = System.currentTimeMillis() + PLAYERS_JOIN_POST_CAGES_REFRESH_WINDOW_MS;
            java.util.Set<String> queuedUuids = new java.util.HashSet<>();
            while (generation == playersJoinPostCagesRefreshGeneration.get()
                    && System.currentTimeMillis() < endAt
                    && me.napim.gomod.command.KillEffectCommand.hasPendingCagesOpenKillEffectRefresh()) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc == null) {
                    break;
                }
                final int[] result = new int[] {0};
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                try {
                    mc.addScheduledTask(() -> {
                        try {
                            result[0] = tryQueueOneCagesOpenDeferredKillEffectRefresh(queuedUuids);
                        } finally {
                            latch.countDown();
                        }
                    });
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    System.err.println("[KillCounter] CAGES_OPEN deferred kill-effect refresh failed: " + t.getMessage());
                }
                if (result[0] == 2) {
                    break;
                }
                try {
                    Thread.sleep(PLAYERS_JOIN_POST_CAGES_REFRESH_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /** @return 0 = waiting, 1 = queued one row, 2 = no pending rows/stop */
    private static int tryQueueOneCagesOpenDeferredKillEffectRefresh(java.util.Set<String> queuedUuids) {
        if (getAutoScanMode() != AutoScanMode.CAGES_OPEN
                || getKillEffectTabDisplayMode() == KillEffectTabDisplayMode.OFF) {
            return 2;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return 2;
        }
        if (!GameContext.isBlitzGameActive(mc)
                || GameContext.isBlitzPregameLobby(mc)
                || GameContext.isBlitzCagePhaseSidebar(mc)) {
            return 0;
        }

        boolean hasPendingTabRow = false;
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getId() == null) {
                continue;
            }
            String name = RemoteDisguiseManager.getSourceProfile(info).getName();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String uuid = RemoteDisguiseManager.getSourceProfile(info).getId().toString().replace("-", "").toLowerCase(Locale.ROOT);
            if (uuid.isEmpty() || queuedUuids.contains(uuid)
                    || !me.napim.gomod.command.KillEffectCommand.isPendingCagesOpenKillEffectRefresh(name)) {
                continue;
            }
            hasPendingTabRow = true;
            if (me.napim.gomod.command.KillEffectCommand.tryQueuePendingCagesOpenKillEffectRefresh(info)) {
                queuedUuids.add(uuid);
                return 1;
            }
        }
        return hasPendingTabRow
                || me.napim.gomod.command.KillEffectCommand.hasPendingCagesOpenKillEffectRefresh() ? 0 : 2;
    }

    /**
     * Detect new players joining pregame lobbies and scan their stats if configured.
     */
    @SubscribeEvent
    public void onTick(net.weavemc.loader.api.event.TickEvent event) {
        flushPendingLiveKillFeed();
        if (!ClientTickHub.isFeatureEnabled("KillCounter")) return;
        long _profT = FeatureProfiler.begin("KillCounter");
        try {
        try {
            if (getAutoScanMode() != AutoScanMode.PLAYERS_JOIN) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.getNetHandler() == null) return;
            if (!me.napim.gomod.util.GameContext.isBlitzPregameLobby(mc)) {
                lastTabPlayerUuids.clear();
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastJoinScanMs < 750L) return;
            lastJoinScanMs = now;

            java.util.Set<String> currentUuids = new java.util.HashSet<>();
            java.util.Map<String, net.minecraft.client.network.NetworkPlayerInfo> infoByUuid = new java.util.HashMap<>();
            for (net.minecraft.client.network.NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getId() == null) {
                    continue;
                }
                String uuid = RemoteDisguiseManager.getSourceProfile(info).getId().toString().replace("-", "");
                if (uuid.isEmpty()) {
                    continue;
                }
                currentUuids.add(uuid);
                infoByUuid.put(uuid, info);
            }

            if (lastTabPlayerUuids.isEmpty()) {
                // First snapshot in pregame: everyone was already on tab before we started tracking,
                // so there is no "join" delta — run a full scan once (same as /tabstats all, no chat).
                if (!currentUuids.isEmpty() && needsHypixelTabstatsNetworkBulk()) {
                    me.napim.gomod.command.KillEffectCommand.runTabStatsAllScanAsync(false,
                            "auto: Blitz pregame first tab snapshot");
                }
                lastTabPlayerUuids.clear();
                lastTabPlayerUuids.addAll(currentUuids);
                return;
            }

            for (String uuid : currentUuids) {
                if (!lastTabPlayerUuids.contains(uuid)) {
                    if (mc.thePlayer == null) continue;
                    net.minecraft.client.network.NetworkPlayerInfo info = infoByUuid.get(uuid);
                    if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null) {
                        continue;
                    }
                    String name = RemoteDisguiseManager.getSourceProfile(info).getName();
                    if (name == null || name.isEmpty() || name.equalsIgnoreCase(mc.thePlayer.getName())) {
                        continue;
                    }
                    if (!me.napim.gomod.command.KillEffectCommand.isOnCooldown(name)) {
                        me.napim.gomod.command.KillEffectCommand.tryQueuePlayerScanFromTab(info, true);
                    }
                }
            }

            lastTabPlayerUuids.clear();
            lastTabPlayerUuids.addAll(currentUuids);
        } catch (Throwable t) {
            System.err.println("[KillCounter] join-scan error: " + t.getMessage());
        }
        } finally { FeatureProfiler.end("KillCounter", _profT); }
    }

    /**
     * Hypixel sometimes tweaks wording/punctuation; keep triggers loose but Blitz-specific.
     */
    private static boolean isBlitzStarThreeMinuteReleaseMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String m = message.replaceAll("\\s+", " ").trim();
        if (m.contains("The Blitz Star will be released in 3 minutes!")) {
            return true;
        }
        if (!m.toLowerCase(Locale.ROOT).contains("blitz star")) {
            return false;
        }
        String lower = m.toLowerCase(Locale.ROOT);
        return lower.contains("3 minutes") && (lower.contains("released") || lower.contains("release"));
    }

    /**
     * Same idea as {@link #isBlitzStarThreeMinuteReleaseMessage} — enemy kit scan must still arm if Hypixel tweaks text.
     */
    private static boolean isBlitzStarTwoMinuteReleaseMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String m = message.replaceAll("\\s+", " ").trim();
        if (m.contains("The Blitz Star will be released in 2 minutes!")) {
            return true;
        }
        if (!m.toLowerCase(Locale.ROOT).contains("blitz star")) {
            return false;
        }
        String lower = m.toLowerCase(Locale.ROOT);
        if (!lower.contains("2 minute")) {
            return false;
        }
        return lower.contains("released") || lower.contains("release") || lower.contains("releas");
    }

    private static boolean containsAny(java.util.List<String> lines, String needle) {
        if (lines == null) return false;
        for (String line : lines) {
            if (line != null && line.contains(needle)) return true;
        }
        return false;
    }

    /**
     * After an auto /tabstats all scan, some late-loading tab rows can miss stats/guild tags.
     * This pass only queues missing players to avoid re-hitting the full rate limit.
     */
    private static void scanMissingTabStats() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) {
                return;
            }
            String apiKey = me.napim.gomod.features.ApiKeyManager.getApiKey("hypixel");
            if (apiKey == null || apiKey.trim().isEmpty()
                    || me.napim.gomod.features.GameModeStatusFetcher.shouldSkipKnownBadHypixelRequests(apiKey)) {
                return;
            }
            java.util.HashSet<String> dedupeTabstatsLookup = new java.util.HashSet<>();
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null) {
                    continue;
                }
                String name = RemoteDisguiseManager.getSourceProfile(info).getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String lookupName = resolveTabLookupName(name);
                String dedupeKey = lookupName.toLowerCase(Locale.ROOT);
                if (!dedupeTabstatsLookup.add(dedupeKey)) {
                    continue;
                }

                boolean hasStats = hasTabstatsStatsForAnyAlias(lookupName);
                boolean hasEffect = hasTabstatsEffectForAnyAlias(lookupName);
                boolean aliasCooldown = isAnyTabstatsAliasOnCooldown(lookupName);
                boolean killEffectNeeded = getKillEffectTabDisplayMode() != KillEffectTabDisplayMode.OFF;
                boolean inPregame = GameContext.isBlitzPregameLobby(mc);
                if ((!hasStats || (killEffectNeeded && !hasEffect && !inPregame)) && !aliasCooldown) {
                    me.napim.gomod.command.KillEffectCommand.tryQueuePlayerScanFromTab(info);
                }

                if (isShowGuildTag() && !hasGuildTagCached(lookupName)) {
                    fetchGuildTag(lookupName);
                }
            }
        } catch (Throwable t) {
            System.err.println("[KillCounter] Missing tabstats scan failed: " + t.getMessage());
        }
    }

    private static boolean hasTabstatsStatsForAnyAlias(String playerName) {
        for (String lookupName : getLookupNames(playerName)) {
            if (!me.napim.gomod.command.KillEffectCommand.getStatsSuffixFor(lookupName).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTabstatsEffectForAnyAlias(String playerName) {
        for (String lookupName : getLookupNames(playerName)) {
            if (me.napim.gomod.command.KillEffectCommand.hasPlayerEffect(lookupName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyTabstatsAliasOnCooldown(String playerName) {
        for (String lookupName : getLookupNames(playerName)) {
            if (me.napim.gomod.command.KillEffectCommand.isOnCooldown(lookupName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called on the client thread after {@link me.napim.gomod.command.KillEffectCommand#runTabStatsAllScan} completes.
     * Runs one immediate missing pass, then one delayed pass for tab rows that appear late.
     */
    public static void schedulePostTabStatsAllScans() {
        scheduleCagesOpenDeferredKillEffectRefresh();
        scanMissingTabStats();
        startDaemonThread("gomod-tabstats-late-missing", () -> {
            try {
                Thread.sleep(2500L);
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null) {
                    mc.addScheduledTask(KillCounter::scanMissingTabStats);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static boolean hasGuildTagCached(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return false;
        }
        if (playerGuildTags.containsKey(playerName)) {
            return true;
        }
        for (String key : playerGuildTags.keySet()) {
            if (key != null && key.equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Checks if BLITZ HOUR is currently active
     * @return true if BLITZ HOUR was detected in scoreboard and within timeout period
     */
    private static boolean isBlitzHourActive() {
        if (!blitzHourActive || blitzHourDetectionTime == 0L) {
            return false;
        }
        
        // Check if BLITZ HOUR detection has timed out (30 seconds)
        long currentTime = System.currentTimeMillis();
        if (currentTime - blitzHourDetectionTime > BLITZ_HOUR_TIMEOUT) {
            // System.out.println("[KillCounter] BLITZ HOUR detection timed out - reverting to normal mode");
            blitzHourActive = false;
            blitzHourDetectionTime = 0L;
            return false;
        }
        
        return true;
    }
    
    /**
     * Checks the scoreboard for BLITZ HOUR text
     * @return true if BLITZ HOUR is found in scoreboard
     */
    private static boolean checkScoreboardForBlitzHour() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                // System.out.println("[KillCounter] No scoreboard available for BLITZ HOUR check");
                return false;
            }
            
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1); // Sidebar
            
            if (objective == null) {
                // System.out.println("[KillCounter] No sidebar objective found for BLITZ HOUR check");
                return false;
            }
            
            Collection<Score> scores = scoreboard.getSortedScores(objective);
            // System.out.println("[KillCounter] Checking " + scores.size() + " scoreboard entries for BLITZ HOUR...");
            
            // Check all scoreboard entries for BLITZ HOUR
            for (Score score : scores) {
                // Use the same method as other scoreboard reading to properly handle formatting
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String scoreText = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (scoreText != null) {
                    // Strip formatting codes before checking for BLITZ HOUR
                    String cleanText = stripColors(scoreText);
                    // System.out.println("[KillCounter] Scoreboard entry: '" + cleanText + "'");
                    
                    // Check for BLITZ HOUR (case insensitive)
                    if (cleanText.toLowerCase().contains("blitz hour")) {
                        // System.out.println("[KillCounter] Found BLITZ HOUR in scoreboard: '" + cleanText + "'");
                        return true;
                    }
                }
            }
            
            // Also check the objective name itself
            String objectiveName = objective.getDisplayName();
            if (objectiveName != null) {
                String cleanObjectiveName = stripColors(objectiveName);
                // System.out.println("[KillCounter] Objective name: '" + cleanObjectiveName + "'");
                
                if (cleanObjectiveName.toLowerCase().contains("blitz hour")) {
                    // System.out.println("[KillCounter] Found BLITZ HOUR in objective name: '" + cleanObjectiveName + "'");
                    return true;
                }
            }
            
            // System.out.println("[KillCounter] No BLITZ HOUR found in scoreboard");
            return false;
            
        } catch (Exception e) {
            System.err.println("[KillCounter] Error checking scoreboard for BLITZ HOUR: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Validates that a kit selection message is a server message (starts with player name)
     * and not a player chat message containing kit selection text.
     */
    private boolean isKitSelectionServerMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        
        // Remove formatting codes to get clean text
        String cleanMessage = message.replaceAll("(?i)\\u00A7[0-9a-fk-or]", "").trim();
        
        // Split by the kit selection pattern
        String[] parts = cleanMessage.split(KIT_SELECTION_PATTERN);
        if (parts.length < 2) {
            return false;
        }
        
        // The first part should contain only the player name (and possibly some whitespace)
        String playerNamePart = parts[0].trim();
        
        // Check if there's any text before the player name that would indicate it's a chat message
        // Server messages typically start directly with the player name
        // Chat messages might have prefixes like "<Player>", "[Guild]", etc.
        String[] words = playerNamePart.split("\\s+");
        if (words.length == 0) {
            return false;
        }
        
        // If there's only one word and it looks like a player name, it's likely a server message
        if (words.length == 1) {
            String potentialPlayerName = words[0];
            // Player names typically don't contain special characters like <, >, [, ], etc.
            if (potentialPlayerName.matches("^[a-zA-Z0-9_]+$") && potentialPlayerName.length() >= 3 && potentialPlayerName.length() <= 16) {
                // System.out.println("[KillCounter] Detected server kit selection message for player: " + potentialPlayerName);
                return true;
            }
        }
        
        // If there are multiple words or the first word contains special characters,
        // it's likely a chat message, not a server message
        // System.out.println("[KillCounter] Skipping kit selection message - appears to be chat: " + cleanMessage);
        return false;
    }
    
    /**
     * Processes a kit selection message to extract player and kit.
     */
    private void processKitSelectionMessage(String message) {
        // Don't track kit selections after the game has ended
        if (gameHasEnded) return;
        try {
            // Extract player name and kit from message like "LegitTooMike has picked their Warrior X kit"
            String[] parts = message.split(KIT_SELECTION_PATTERN);
            if (parts.length >= 2) {
                String playerName = extractPlayerName(parts[0]);
                String kitPart = parts[1].trim();
                
                if (playerName != null && kitPart.contains("kit")) {
                    // Extract kit name from "Warrior X kit" -> "Warrior X"
                    String kitName = normalizeTrackedKitName(kitPart.replace("kit", "").trim());
                    if (!kitName.isEmpty()) {
                        String previousKit = playerKits.put(playerName, kitName);
                        if (previousKit != null && previousKit.equals(kitName)) {
                            return;
                        }
                        // Update tab list to show the kit (avoid spamming compose when the same kit is re-parsed)
                        markTabCompositionDirty();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[KillCounter] Error processing kit selection message: " + e.getMessage());
        }
    }

    private boolean tryProcessLocalKitReceiveMessage(String message) {
        if (!isLocalKitReceiveMessage(message) || gameHasEnded) {
            return false;
        }
        String normalizedMessage = message.replaceAll("\\s+", " ").trim();
        Matcher grantedMatcher = LOCAL_KIT_GRANTED_PATTERN.matcher(normalizedMessage);
        Matcher willReceiveMatcher = LOCAL_KIT_RECEIVE_PATTERN.matcher(normalizedMessage);
        Matcher matcher;
        boolean itemsGranted;
        if (grantedMatcher.matches()) {
            matcher = grantedMatcher;
            itemsGranted = true;
        } else if (willReceiveMatcher.matches()) {
            matcher = willReceiveMatcher;
            itemsGranted = false;
        } else {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return true;
        }
        String playerName = mc.thePlayer.getName();
        if (playerName == null || playerName.trim().isEmpty()) {
            return true;
        }
        String kitName = normalizeTrackedKitName(matcher.group(1));
        if (kitName == null || kitName.isEmpty()) {
            return true;
        }
        String previousKit = playerKits.put(playerName, kitName);
        if (previousKit == null || !previousKit.equals(kitName)) {
            markTabCompositionDirty();
        }
        if (itemsGranted) {
            DisguiseRenamedItemMasker.startTrackingLocalReceivedKitItems(kitName);
        }
        return true;
    }

    private boolean isLocalKitReceiveMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String normalizedMessage = message.replaceAll("\\s+", " ").trim();
        return LOCAL_KIT_RECEIVE_PATTERN.matcher(normalizedMessage).matches()
                || LOCAL_KIT_GRANTED_PATTERN.matcher(normalizedMessage).matches();
    }

    private String normalizeTrackedKitName(String rawKitName) {
        if (rawKitName == null) {
            return "";
        }
        String kitName = rawKitName.trim();
        if (kitName.isEmpty()) {
            return "";
        }
        if (kitName.equalsIgnoreCase("Random")) {
            return isBlitzHourActive() ? "Random X" : "Random";
        }
        if (containsPrestigeStar(kitName)) {
            return kitName.replaceAll("\\s+[IVX]+$", "").trim();
        }
        if (!kitName.matches(".*\\b([IVX]+|\\?+)\\b.*")) {
            return kitName + " I";
        }
        return kitName;
    }

    private boolean containsPrestigeStar(String kitName) {
        if (kitName == null || kitName.isEmpty()) {
            return false;
        }
        for (char starChar : new char[] { '\u2B50', '\u2605', '\u272B', '\u2726', '\u2606', '\u2B51' }) {
            if (kitName.indexOf(starChar) >= 0) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Processes a kill message to extract killer and victim.
     * @return true if a kill was recorded
     */
    private boolean processKillMessage(String message, String pattern) {
        if (!isAuthoritativeDeathMessage(message)) {
            return false;
        }
        // Don't track kills after the game has ended
        if (gameHasEnded) {
            recordTabVictimsKillDebugEvent("ignored-after-game-end line=" + compactKillDebugLine(message));
            return false;
        }
        try {
            // Extract player names from the message
            String[] parts = message.split(java.util.regex.Pattern.quote(pattern), 2);
            if (parts.length >= 2) {
                String victim = extractPlayerName(parts[0]);
                String killer = extractPlayerNameFromStart(parts[1]);
                if (killer == null) {
                    killer = extractPlayerName(parts[1]);
                }
                victim = resolveYouToLocalPlayer(victim);
                killer = resolveYouToLocalPlayer(killer);
                
                boolean accepted = applyKill(killer, victim, message);
                recordTabVictimsKillDebugEvent("parsed killer=" + debugKillName(killer)
                        + " victim=" + debugKillName(victim) + " accepted=" + accepted
                        + " line=" + compactKillDebugLine(message));
                return accepted;
            }
        } catch (Exception e) {
            System.err.println("[KillCounter] Error processing kill message: " + e.getMessage());
        }
        recordTabVictimsKillDebugEvent("parse-failed pattern=" + pattern
                + " line=" + compactKillDebugLine(message));
        return false;
    }

    private boolean applyKill(String killer, String victim, String message) {
        if (killer == null || victim == null || victim.equalsIgnoreCase(killer)) {
            return false;
        }

        // Blitz gives each player one life. The public death broadcast and the local
        // "You were killed by ..." detail describe the same credit, even with different text.
        // Check the edge, not dead state: a dead killer can still receive delayed credit.
        List<String> victimAliases = getTabVictimIdentityAliases(victim);
        for (String killerAlias : getTabVictimIdentityAliases(killer)) {
            if (victimListContainsAlias(getCaseInsensitiveValue(playerVictims, killerAlias), victimAliases)) {
                recordTabVictimsKillDebugEvent("duplicate-edge killer=" + debugKillName(killer)
                        + " victim=" + debugKillName(victim));
                return true;
            }
        }

        incrementKillCountResolvingDisguise(killer);

        // Add victim to killer's victim list
        playerVictims.computeIfAbsent(killer, k -> new ArrayList<>()).add(victim);
        recordTabVictimsKillDebugEvent("recorded-edge killer=" + debugKillName(killer)
                + " victim=" + debugKillName(victim));

        // A kill-credit line is not an alive/respawn signal. Hypixel can deliver delayed credit for a killer
        // after that player has already died, so only add death facts here and never clear a prior death.
        markPlayerDead(victim);

        // Store player color if we can extract it
        String killerColor = extractPlayerColor(message, killer);
        if (killerColor != null) {
            playerColors.put(killer, killerColor);
        }

        markTabCompositionDirty();
        notifyBlitzChallengesKillDeath(killer, victim);
        return true;
    }

    private static void notifyBlitzChallengesKillDeath(String killer, String victim) {
        if (isLocalClientAnyKnownIgn(killer)) {
            Minecraft mc = Minecraft.getMinecraft();
            BlitzChallenges.onLocalKill(mc);
            GoparkyBlitzReactions.onLocalKill(mc, victim);
        }
        if (isLocalClientAnyKnownIgn(victim)) {
            BlitzChallenges.onLocalDeath();
        }
    }


    /**
     * Icon or letter for a kill-effect API name (e.g. TAB settings preview). {@code null}/empty → null.
     */
    public static Object getKillEffectIconPrefixForEffect(String effect) {
        if (effect == null || effect.trim().isEmpty()) {
            return null;
        }
        switch (effect.toUpperCase(Locale.ROOT)) {
            case "FLAME":
            case "RAPID_FIRE":
                return new net.minecraft.util.ResourceLocation("gomod123", "textures/killeffects/flame.png");
            case "GRAVEDIGGER":
                return new net.minecraft.util.ResourceLocation("gomod123", "textures/killeffects/gravedigger.png");
            case "LEVEL_UP":
            case "LEVELUP":
                return new net.minecraft.util.ResourceLocation("gomod123", "textures/killeffects/levelup.png");
            case "REGENERATION":
                return new net.minecraft.util.ResourceLocation("gomod123", "textures/killeffects/regeneration.png");
            case "RESISTANCE":
                return new net.minecraft.util.ResourceLocation("gomod123", "textures/killeffects/resistance.png");
            case "SPEED":
                return new net.minecraft.util.ResourceLocation("gomod123", "textures/killeffects/speed.png");
            case "RANDOM":
                return new ResourceLocation("gomod123", "textures/killeffects/killeffectrandom.png");
            default:
                return null;
        }
    }

    /**
     * Gets the kill effect icon/letter prefix for a player (from /tabstats cache).
     */
    public static Object getKillEffectIconPrefix(String playerName) {
        return getKillEffectIconPrefixForEffect(me.napim.gomod.command.KillEffectCommand.getPlayerEffect(resolveTabLookupName(playerName)));
    }

    /** RGB (no alpha) for letter/icon tint from API effect name. */
    public static int getKillEffectColorForEffect(String effect) {
        if (effect == null || effect.trim().isEmpty()) {
            return 0xFFFFFF;
        }
        switch (effect.toUpperCase(Locale.ROOT)) {
            case "REGENERATION":
                return 0xFF55FF;
            case "RESISTANCE":
                return 0xFFAA00;
            case "GRAVEDIGGER":
                return 0x55FF55;
            case "RANDOM":
                return 0x0000AA;
            case "LEVEL_UP":
            case "LEVELUP":
                return 0xFFFF55;
            case "RAPID_FIRE":
            case "FLAME":
                return 0xAA0000;
            case "SPEED":
                return 0x55FFFF;
            default:
                return 0xFFFFFF;
        }
    }

    /**
     * Gets the kill effect color for icon/letter rendering
     */
    public static int getKillEffectColor(String playerName) {
        return getKillEffectColorForEffect(me.napim.gomod.command.KillEffectCommand.getPlayerEffect(resolveTabLookupName(playerName)));
    }
    /** Avoids resetting {@link NetworkPlayerInfo#setDisplayName} when the formatted line is unchanged (reduces GC + vanilla churn). */
    private static void applyComposedTabLineIfChanged(NetworkPlayerInfo info, String formattedLine) {
        if (info == null || formattedLine == null) {
            return;
        }
        if (GomodFixes.isWeaveRuntime()) {
            cacheRenderedTabLine(info, formattedLine);
            return;
        }
        try {
            IChatComponent cur = ((NetworkPlayerInfoAccessor) (Object) info).gomod$getDisplayName();
            if (cur != null && formattedLine.equals(cur.getFormattedText())) {
                return;
            }
        } catch (Throwable ignored) {
        }
        info.setDisplayName(new ChatComponentText(formattedLine));
    }

    private static void clearTabDisplayOverrideIfSet(NetworkPlayerInfo info) {
        if (info == null) {
            return;
        }
        tabStatsSegmentByRowKey.remove(tabRowKey(info));
        if (GomodFixes.isWeaveRuntime()) {
            clearRenderedTabLine(info);
            return;
        }
        try {
            if (info.getDisplayName() != null) {
                info.setDisplayName(null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cacheRenderedTabLine(NetworkPlayerInfo info, String formattedLine) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null
                || formattedLine == null) {
            return;
        }
        String key = tabRowKey(info);
        tabUnpaddedLineByRowKey.put(key, formattedLine);
        tabRenderedLineByRowKey.put(key, formattedLine);
    }

    private static void clearRenderedTabLine(NetworkPlayerInfo info) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null) {
            return;
        }
        String key = tabRowKey(info);
        tabRenderedLineByRowKey.remove(key);
        tabUnpaddedLineByRowKey.remove(key);
        tabStatsSegmentByRowKey.remove(key);
    }

    /** Invalidates every cached composed value for a row restored after a timed disguise expires. */
    public static void invalidateTabRowAfterDisguise(NetworkPlayerInfo info) {
        if (info == null || RemoteDisguiseManager.getSourceProfile(info) == null || RemoteDisguiseManager.getSourceProfile(info).getName() == null) return;
        String key = tabRowKey(info);
        tabDisplayFormatCache.remove(key);
        tabRowComposeFingerprintByRowKey.remove(key);
        clearRenderedTabLine(info);
        markTabCompositionDirty();
    }

    /**
     * Compact fingerprint of everything that feeds TAB line composition for one row. If it matches the last pass,
     * we skip recomputing that player (still cheap: {@link #cacheObservedTabAppearance} runs every tick).
     */
    private static long computeTabComposeRowFingerprintKey(
            NetworkPlayerInfo info,
            Minecraft mc,
            String baseName,
            String lookupName,
            String resolvedRealName,
            String ignMiddleForTeamFormat,
            boolean tabKillersDeadRow,
            int kills,
            boolean showKillSuffix,
            boolean wantsDecorations,
            String teamFormattedBase,
            String teamFormattedDisplay,
            String originalServerName,
            String serverNameAfterIgnTint,
            boolean effectiveDead,
            boolean blitzPregame,
            boolean blitzGameActive,
            String formattedStats,
            String guildTag,
            String playerKit,
            String enemyPlayerKit,
            boolean killEffectTabTintRow,
            boolean coloredKillEffectName,
            boolean applyBlitzTeamGreenIgn,
            boolean applyLightRedBlitzIgn,
            String killEffectToken,
            int maxKillsAlive,
            boolean includeGameScopedDecorations,
            String vanillaDisplayFormatted) {
        // Use a running long hash instead of a 400-char String to avoid allocation and expensive .equals().
        long h = 17L;
        h = h * 31 + (includeGameScopedDecorations ? 1 : 0);
        h = h * 31 + (ConfigManager.isKillCounterEnabled() ? 1 : 0);
        h = h * 31 + (enabled ? 1 : 0);
        h = h * 31 + tabKillCounterDisplayMode.ordinal();
        h = h * 31 + killEffectTabMode.ordinal();
        h = h * 31 + (statsShowKd ? 1 : 0);
        h = h * 31 + (statsShowWl ? 1 : 0);
        h = h * 31 + (statsShowW ? 1 : 0);
        h = h * 31 + (statsDisplay ? 1 : 0);
        h = h * 31 + (ConfigManager.isKillCounterTabKillersEnabled() ? 1 : 0);
        h = h * 31 + hashStr(baseName);
        h = h * 31 + hashStr(lookupName);
        h = h * 31 + hashStr(resolvedRealName);
        h = h * 31 + hashStr(ignMiddleForTeamFormat);
        h = h * 31 + (tabKillersDeadRow ? 1 : 0);
        h = h * 31 + kills;
        h = h * 31 + (showKillSuffix ? 1 : 0);
        h = h * 31 + (wantsDecorations ? 1 : 0);
        h = h * 31 + hashStr(teamFormattedBase);
        h = h * 31 + hashStr(teamFormattedDisplay);
        h = h * 31 + hashStr(originalServerName);
        h = h * 31 + hashStr(serverNameAfterIgnTint);
        h = h * 31 + (effectiveDead ? 1 : 0);
        h = h * 31 + (blitzPregame ? 1 : 0);
        h = h * 31 + (blitzGameActive ? 1 : 0);
        h = h * 31 + hashStr(formattedStats);
        h = h * 31 + hashStr(guildTag);
        if (guildTag != null && !guildTag.isEmpty()) {
            h = h * 31 + hashStr(resolveTabGuildColorForRow(baseName));
        }
        h = h * 31 + hashStr(playerKit);
        h = h * 31 + hashStr(enemyPlayerKit);
        h = h * 31 + (killEffectTabTintRow ? 1 : 0);
        h = h * 31 + (coloredKillEffectName ? 1 : 0);
        h = h * 31 + (applyBlitzTeamGreenIgn ? 1 : 0);
        h = h * 31 + (applyLightRedBlitzIgn ? 1 : 0);
        h = h * 31 + hashStr(killEffectToken);
        h = h * 31 + maxKillsAlive;
        h = h * 31 + hashStr(vanillaDisplayFormatted);
        int ping = -999;
        try {
            if (info != null) {
                ping = info.getResponseTime();
            }
        } catch (Throwable ignored) {
        }
        h = h * 31 + ping;
        h = h * 31 + (LobbyPingDisplay.isEnabled() ? 1 : 0);
        boolean pingRender = false;
        try {
            Minecraft mcx = Minecraft.getMinecraft();
            pingRender = LobbyPingDisplay.isEnabled() && mcx != null && !mcx.isSingleplayer() && mcx.theWorld != null
                    && mcx.getNetHandler() != null && !ReplaySettings.isReplayActive();
        } catch (Throwable ignored) {
        }
        h = h * 31 + (pingRender ? 1 : 0);
        h = h * 31 + (GameContext.isBlitzCagePhaseSidebar(mc) ? 1 : 0);
        String normIgn = serverNameAfterIgnTint == null ? "" : normalizeTabDisplayBase(serverNameAfterIgnTint);
        h = h * 31 + (hasServerGuildTag(normIgn) ? 1 : 0);
        String sn = serverNameAfterIgnTint == null ? "" : serverNameAfterIgnTint;
        h = h * 31 + (composedTabLineNeedsBaseRecovery(sn) ? 1 : 0);
        h = h * 31 + (LobbyPingDisplay.lineAlreadyShowsPingMs(sn) ? 1 : 0);
        h = h * 31 + hashStr(HypixelTabStatsRankCache.tabStatsRankFingerprintForRow(lookupName, baseName));
        h = h * 31 + (ConfigManager.isNickRealNametagRankPlateEnabled() ? 1 : 0);
        return h;
    }

    private static long hashStr(String s) {
        return s == null ? 0L : (long) s.hashCode();
    }

    /**
     * Single TAB composition path for both mid-game and post-game. Stats/guild always use the same rules;
     * {@code includeGameScopedDecorations} adds kill suffix, kits, and kill-effect name coloring only while a game is active.
     */
    private static void composeAllPlayerTabDisplayNames(boolean includeGameScopedDecorations) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null || !isTabQolRuntimeContext(mc)) {
            return;
        }
        invalidateTabFormatCacheOnBlitzPhaseChange(mc);
        boolean inReplay = GameContext.isHypixelReplayWatching(mc);
        boolean blitzPregame = GameContext.isBlitzPregameLobby(mc);
        boolean blitzGameActive = GameContext.isBlitzGameActive(mc);
        boolean blitzAliveIgnLightRedContext = (blitzGameActive && !blitzPregame)
                || GameContext.isBlitzCagePhaseSidebar(mc);
        int maxKillsAlive = getMaxKillsAlive();
        java.util.HashSet<String> tabProfilesThisPass = new java.util.HashSet<>();
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            cacheObservedTabAppearance(info);
            GameProfile source = RemoteDisguiseManager.getSourceProfile(info);
            if (source == null) continue;
            String baseName = source.getName();
            if (baseName == null || baseName.isEmpty()) {
                continue;
            }
            boolean localDisguiseRow = isLocalPlayerTabRow(baseName) && LocalDisguise.isEnabled();
            LocalDisguise.RemoteDisguiseProfile remoteDisguiseTarget =
                    RemoteDisguiseManager.getDisguisedTargetProfile(source);
            boolean remoteDisguiseRow = remoteDisguiseTarget != null;

            int kills = getKillCount(baseName);
            String lookupName = resolveTabLookupName(baseName);
            boolean tabKillersDeadRow = isTabKillersStyleVictim(info, mc);
            String resolvedRealName = resolveTabResolvedRealName(baseName, localDisguiseRow || remoteDisguiseRow);
            String ignMiddleForTeamFormat = baseName;
            if (tabKillersDeadRow && resolvedRealName.isEmpty()) {
                String knownNick = resolveKnownNickForRealProfileIgn(baseName, localDisguiseRow || remoteDisguiseRow);
                if (knownNick != null && !knownNick.isEmpty()) {
                    ignMiddleForTeamFormat = knownNick;
                    resolvedRealName = baseName.trim();
                }
            }
            // Kill Counter config + runtime only gate the [n] suffix; stats/guild are independent.
            boolean showKillSuffix = includeGameScopedDecorations
                    && ConfigManager.isKillCounterEnabled()
                    && enabled
                    && tabKillCounterDisplayMode == TabKillCounterDisplayMode.AFTER_NAME
                    && kills >= 1;

            String teamFormattedBase = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), baseName);
            String teamFormattedDisplay = ignMiddleForTeamFormat.equalsIgnoreCase(baseName)
                    ? teamFormattedBase
                    : ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), ignMiddleForTeamFormat);
            String serverName = resolveTabServerFormattedBase(info, baseName);
            String originalServerName = serverName;
            if (inReplay) {
                clearRenderedTabLine(info);
                continue;
            }
            boolean isDead = isPlayerDead(baseName);
            // Use raw team formatting for blue-dead detection — serverName may already carry our §c TAB tint from
            // a previous compose tick, which makes isBlitzTabServerDeadBlue miss Hypixel's §9 and leaves the row red.
            boolean serverDeadBlue = blitzGameActive && isBlitzTabServerDeadBlue(teamFormattedBase, baseName);
            boolean effectiveDead = isDead || serverDeadBlue || SpectatorRelay.isTabSpectator(info);
            if (effectiveDead) {
                // Drop kill-effect-tinted IGN from cached/recovered TAB lines; use scoreboard team formatting (e.g. Hypixel dead color).
                serverName = teamFormattedDisplay;
                if (isDead && blitzGameActive) {
                    serverName = dimBlitzDeadTabLineIfStillBrightWhite(serverName, ignMiddleForTeamFormat);
                }
                if (blitzGameActive) {
                    serverName = forceBlitzDeadBlueBeforeIgnAfterLeadingBrackets(serverName, ignMiddleForTeamFormat);
                    if (!lookupName.isEmpty() && !lookupName.equalsIgnoreCase(ignMiddleForTeamFormat)) {
                        serverName = forceBlitzDeadBlueBeforeIgnAfterLeadingBrackets(serverName, lookupName);
                    }
                    if (!resolvedRealName.isEmpty()
                            && !resolvedRealName.equalsIgnoreCase(ignMiddleForTeamFormat)
                            && !resolvedRealName.equalsIgnoreCase(lookupName)) {
                        serverName = forceBlitzDeadBlueBeforeIgnAfterLeadingBrackets(serverName, resolvedRealName);
                    }
                }
            } else {
                // Packet displayName may omit [DIST n] while the scoreboard team line still has it.
                serverName = mergeTeamDistBracketPrefixIfMissing(serverName, teamFormattedBase);
                serverName = normalizeBlitzTabDistBracketChunks(serverName);
                serverName = ensureSpaceAfterLeadingBracketChainBeforeBody(serverName);
                // Teams TAB: Hypixel often puts §7/§8 on the body after [DIST n]/[TEAM] so the IGN reads gray; that
                // prevents our §c/§a recolor from taking. Reset to §f before the IGN when the visible text still starts with it.
                if (blitzAliveIgnLightRedContext) {
                    String plainMerged = EnumChatFormatting.getTextWithoutFormattingCodes(serverName);
                    if (plainMerged != null) {
                        String um = plainMerged.toUpperCase(Locale.ROOT);
                        if (um.contains("DIST") || um.contains("TEAM")) {
                            serverName = stripNeutralGrayBeforeIgnAfterLeadingBrackets(serverName, baseName);
                            // DIST rows often show the nick as the visible IGN; stripping §7 only against profile name misses.
                            if (!lookupName.isEmpty() && !lookupName.equalsIgnoreCase(baseName)) {
                                serverName = stripNeutralGrayBeforeIgnAfterLeadingBrackets(serverName, lookupName);
                            }
                            if (!resolvedRealName.isEmpty()
                                    && !resolvedRealName.equalsIgnoreCase(baseName)
                                    && !resolvedRealName.equalsIgnoreCase(lookupName)) {
                                serverName = stripNeutralGrayBeforeIgnAfterLeadingBrackets(serverName, resolvedRealName);
                            }
                        }
                    }
                }
            }
            String killEffect = me.napim.gomod.command.KillEffectCommand.getPlayerEffect(lookupName);
            boolean meaningfulEffect = killEffect != null && !killEffect.isEmpty()
                    && !killEffect.equalsIgnoreCase("none");

            // Colored name: keep server §f in Blitz waiting lobby; in-match use API tint after tabstats, else §c until then.
            boolean preserveRemoteDisguiseTeamColor = remoteDisguiseRow && blitzAliveIgnLightRedContext;
            boolean coloredKillEffectName = !effectiveDead && meaningfulEffect && killEffectTabMode.colorsTab()
                    && blitzAliveIgnLightRedContext && !preserveRemoteDisguiseTeamColor;
            boolean applyBlitzTeamGreenIgn = !effectiveDead && blitzAliveIgnLightRedContext && !coloredKillEffectName
                    && tabPlainIndicatesHypixelTeamTag(serverName);
            if (coloredKillEffectName) {
                // Match KillEffectCommand chat colors: unknown API effect names still get a tab tint (was null → no color).
                String effectColor = mapKillEffectToColor(killEffect);
                String coloredBase = effectColor + baseName + EnumChatFormatting.RESET;
                serverName = applyKillEffectColorToIgnWithSmartFallback(info, serverName, baseName, coloredBase, false);
            } else if (applyBlitzTeamGreenIgn) {
                String greenBase = EnumChatFormatting.GREEN + baseName + EnumChatFormatting.RESET;
                serverName = applyKillEffectColorToIgnWithSmartFallback(info, serverName, baseName, greenBase, false);
            } else if (!effectiveDead && !meaningfulEffect && blitzAliveIgnLightRedContext
                    && (killEffectTabMode.colorsTab()
                    || killEffectTabMode == KillEffectTabDisplayMode.ICON)) {
                String lightRedBase = EnumChatFormatting.RED.toString() + baseName + EnumChatFormatting.RESET;
                serverName = applyKillEffectColorToIgnWithSmartFallback(info, serverName, baseName, lightRedBase, true);
            }
            if (remoteDisguiseRow && blitzAliveIgnLightRedContext) {
                // Keep Hypixel's current team tint (alive §c / eliminated §9) on remote disguise rows. Kill-effect
                // icons still render; optional KillEffectColor must not turn the disguised IGN white or another tint.
                String sourceTeamName = RemoteDisguiseManager.liveBlitzSourceTabName(
                        info, baseName, serverName, effectiveDead);
                String aliased = RemoteDisguiseManager.replaceNameInFormatted(
                        sourceTeamName, baseName, remoteDisguiseTarget.name);
                if (aliased != null && !aliased.isEmpty()) serverName = aliased;
            }
            // Kill-effect / §c / green substitution may swap in scoreboard team text that glues "]" to the nick (Hypixel
            // omits the gap — especially visible next to a nick when denick adds "(real)" later).
            serverName = normalizeBlitzTabDistBracketChunks(serverName);
            serverName = ensureSpaceAfterLeadingBracketChainBeforeBody(serverName);
            // Persist the disguise directly in our composed local row. Otherwise Hypixel's periodic teams/TAB
            // packets can make the composer restore the real [rank] IGN until LocalDisguise rewrites it again.
            if (localDisguiseRow) {
                serverName = LocalDisguise.buildTabDisplayName(serverName, baseName);
                serverName = normalizeBlitzTabDistBracketChunks(serverName);
                serverName = ensureSpaceAfterLeadingBracketChainBeforeBody(serverName);
            }

            boolean applyLightRedBlitzIgn = !effectiveDead && !meaningfulEffect && blitzAliveIgnLightRedContext
                    && (killEffectTabMode.colorsTab()
                    || killEffectTabMode == KillEffectTabDisplayMode.ICON)
                    && !coloredKillEffectName
                    && !applyBlitzTeamGreenIgn;

            String formattedStats = tabKillersDeadRow ? ""
                    : resolveTabStatsSuffixForRow(baseName, remoteDisguiseTarget);
            String guildTag = tabKillersDeadRow ? "" : resolveTabGuildTagForRow(baseName, remoteDisguiseTarget);
            String playerKit = tabKillersDeadRow ? "" : (includeGameScopedDecorations ? getPlayerKit(baseName) : "");
            String enemyPlayerKit = tabKillersDeadRow ? "" : (includeGameScopedDecorations ? getEnemyPlayerKit(baseName) : "");

            boolean killEffectTabTintRow = !tabKillersDeadRow && ((killEffectTabMode.colorsTab()
                    && !effectiveDead
                    && (coloredKillEffectName || (!meaningfulEffect && blitzAliveIgnLightRedContext)))
                    || applyLightRedBlitzIgn
                    || applyBlitzTeamGreenIgn);

            boolean wantsDecorations = localDisguiseRow
                    || showKillSuffix
                    || !resolvedRealName.isEmpty()
                    || !formattedStats.isEmpty()
                    || !guildTag.isEmpty()
                    || !playerKit.isEmpty()
                    || !enemyPlayerKit.isEmpty()
                    || killEffectTabTintRow;

            String vanillaTabFmt = "";
            if (GomodFixes.isWeaveRuntime()) {
                // The display getter can return our own composed row in Weave. The fingerprint needs the
                // packet's input, so composing a row cannot itself invalidate the next compose pass.
                vanillaTabFmt = getNetworkPlayerInfoFormattedText(info);
            } else {
                try {
                    IChatComponent display = info.getDisplayName();
                    if (display != null) vanillaTabFmt = display.getFormattedText();
                } catch (Throwable ignored) {
                }
            }
            String profileKey = tabRowKey(info);
            tabProfilesThisPass.add(profileKey);
            long rowFp = computeTabComposeRowFingerprintKey(
                    info, mc, baseName, lookupName, resolvedRealName, ignMiddleForTeamFormat,
                    tabKillersDeadRow, kills, showKillSuffix, wantsDecorations,
                    teamFormattedBase, teamFormattedDisplay, originalServerName, serverName,
                    effectiveDead, blitzPregame, blitzGameActive,
                    formattedStats, guildTag, playerKit, enemyPlayerKit,
                    killEffectTabTintRow, coloredKillEffectName, applyBlitzTeamGreenIgn, applyLightRedBlitzIgn,
                    killEffect, maxKillsAlive, includeGameScopedDecorations, vanillaTabFmt);
            Long prevFp = tabRowComposeFingerprintByRowKey.get(profileKey);
            if (prevFp != null && prevFp == rowFp) {
                continue;
            }

            if (!wantsDecorations) {
                tabStatsSegmentByRowKey.remove(profileKey);
                if (remoteDisguiseRow) {
                    serverName = normalizeTabDisplayBase(stripTrailingGuildTagSuffixFromTabLine(serverName));
                }
                // Ping was only appended on the decorated branch, so lobby ping disappeared for plain rows
                // until /tabstats all added stats/guild and forced decorations — always attach ping when enabled.
                serverName = normalizeBlitzTabDistBracketChunks(serverName);
                serverName = ensureSpaceAfterLeadingBracketChainBeforeBody(serverName);
                String baseForPing = normalizeTabDisplayBase(serverName);
                String withPing = LobbyPingDisplay.appendTabPingIfEnabled(info, baseForPing);
                if (isTabKillersStyleVictim(info, mc)) {
                    withPing = applyTabKillersVictimStylePlainLine(withPing, baseName);
                }
                if (withPing.equals(baseForPing)) {
                    String originalBase = normalizeTabDisplayBase(originalServerName);
                    if (baseForPing.equals(originalBase)) {
                        clearTabDisplayOverrideIfSet(info);
                    } else {
                        applyComposedTabLineIfChanged(info, baseForPing);
                    }
                } else {
                    applyComposedTabLineIfChanged(info, withPing);
                }
                tabRowComposeFingerprintByRowKey.put(profileKey, rowFp);
                continue;
            }

            if (composedTabLineNeedsBaseRecovery(serverName) || LobbyPingDisplay.lineAlreadyShowsPingMs(serverName)) {
                String recoveredBase = recoverServerTabBaseFromComposedLine(serverName);
                if (recoveredBase != null && !recoveredBase.isEmpty()) {
                    serverName = recoveredBase;
                }
            }
            if (showKillSuffix) {
                serverName = stripTrailingKillCountBracketsFromFormattedTab(serverName);
            }
            serverName = normalizeTabDisplayBase(serverName);
            if (!resolvedRealName.isEmpty()) {
                serverName = stripTrailingParentheticalRealIgn(serverName, resolvedRealName);
                serverName = normalizeTabDisplayBase(serverName);
            }
            if (composedTabLineNeedsBaseRecovery(serverName)) {
                String salvage = recoverServerTabBaseFromComposedLine(serverName);
                if (salvage != null && !salvage.isEmpty()) {
                    serverName = normalizeTabDisplayBase(salvage);
                }
            }
            if (!resolvedRealName.isEmpty()) {
                serverName = ensureTabNickNotGrayBeforeDuplicateRealParen(serverName, baseName);
                serverName = stripLeadingHypixelRankBracketsKeepGameTags(serverName);
                serverName = normalizeTabDisplayBase(serverName);
            }
            // Rank-strip for nicks rebuilds the leading bracket chain; DIST may sit flush against the visible nick again.
            serverName = normalizeBlitzTabDistBracketChunks(serverName);
            serverName = ensureSpaceAfterLeadingBracketChainBeforeBody(serverName);
            // Always drop a previous stats tail before rebuilding KD/WL/W toggles (kd-only was not detected before).
            serverName = stripTrailingStatsSuffixFromTabLine(serverName);
            serverName = normalizeTabDisplayBase(serverName);
            // Hypixel sends the source player's guild on the TAB row; use the disguised target's tag instead.
            if (localDisguiseRow || remoteDisguiseRow) {
                serverName = stripTrailingGuildTagSuffixFromTabLine(serverName);
                serverName = normalizeTabDisplayBase(serverName);
            }
            if (localDisguiseRow) {
                // Base recovery above may have restored Hypixel's raw local IGN; make the final persisted line
                // disguise-aware as well, after stripping our real account's stats/guild decorations.
                serverName = LocalDisguise.buildTabDisplayName(serverName, baseName);
                serverName = normalizeBlitzTabDistBracketChunks(serverName);
                serverName = ensureSpaceAfterLeadingBracketChainBeforeBody(serverName);
            }
            StringBuilder displayName = new StringBuilder(serverName);

            if (!resolvedRealName.isEmpty()
                    && ConfigManager.isDenickerEnabled()
                    && ConfigManager.isDenickerShowRealIgnInTabEnabled()
                    && !plainTextContainsParentheticalIgn(serverName, resolvedRealName)) {
                appendTabPart(displayName, Denicker.formatTabRealIgnSuffix(resolvedRealName));
            }

            if (showKillSuffix) {
                boolean isTop = !effectiveDead && (kills == maxKillsAlive && maxKillsAlive > 0);
                String style = effectiveDead
                        ? EnumChatFormatting.BLUE.toString()
                        : (EnumChatFormatting.DARK_RED + (isTop ? EnumChatFormatting.BOLD.toString() : ""));
                appendTabPart(displayName, EnumChatFormatting.RESET + style + "[" + kills + "]");
            }

            if (!playerKit.isEmpty()) {
                appendTabPart(displayName, EnumChatFormatting.RESET.toString() + EnumChatFormatting.YELLOW + "(" + playerKit + ")");
            }

            if (!guildTag.isEmpty() && !hasServerGuildTag(serverName)
                    && !plainTextContainsBracketedGuild(displayName.toString(), guildTag)) {
                String guildTagColor = resolveTabGuildColorForRow(baseName, remoteDisguiseTarget);
                if (guildTagColor == null) {
                    guildTagColor = "";
                }
                appendTabPart(displayName, EnumChatFormatting.RESET + guildTagColor + "[" + guildTag + "]");
            }

            if (!enemyPlayerKit.isEmpty()) {
                appendTabPart(displayName, TabRowDecorationFormatter.enemyKitSuffix(enemyPlayerKit));
            }

            // Stats are deliberately the final composed text segment. The TAB renderer separates this exact chunk
            // and draws it at a shared right edge, without adding long runs of spaces to every player name.
            String statsSegment = "";
            if (!formattedStats.isEmpty()) {
                String statsTrim = trimLeadingDecoratedSpaces(trimTrailingAsciiSpaces(formattedStats));
                statsSegment = EnumChatFormatting.RESET + statsTrim + EnumChatFormatting.RESET;
                appendTabPart(displayName, statsSegment);
            }

            String tabLine = displayName.toString();
            if (isTabKillersStyleVictim(info, mc)) {
                tabLine = applyTabKillersVictimStylePlainLine(tabLine, baseName);
            }
            tabLine = LobbyPingDisplay.appendTabPingIfEnabled(info, tabLine);
            if (statsSegment.isEmpty()) {
                tabStatsSegmentByRowKey.remove(profileKey);
            } else {
                tabStatsSegmentByRowKey.put(profileKey, statsSegment);
            }
            applyComposedTabLineIfChanged(info, tabLine);
            tabRowComposeFingerprintByRowKey.put(profileKey, rowFp);
        }
        tabRowComposeFingerprintByRowKey.keySet().removeIf(k -> !tabProfilesThisPass.contains(k));
        tabRenderedLineByRowKey.keySet().removeIf(k -> !tabProfilesThisPass.contains(k));
        tabUnpaddedLineByRowKey.keySet().removeIf(k -> !tabProfilesThisPass.contains(k));
        tabStatsSegmentByRowKey.keySet().removeIf(k -> !tabProfilesThisPass.contains(k));
        applyWeaveTabStatsTextPadding(mc);
    }

    /**
     * Lunar/Genesis renders TAB components with its own font pipeline, after all vanilla and Weave font hooks.
     * For that runtime only, bake a short run of spaces into the render-only display-name cache. This is computed
     * when rows are composed (not every frame), and therefore does not have the performance problem that prompted
     * avoiding a large fixed-width spacer in the first place.
     */
    private static void applyWeaveTabStatsTextPadding(Minecraft mc) {
        if (!GomodFixes.isWeaveRuntime() || mc == null || mc.fontRendererObj == null
                || mc.getNetHandler() == null || tabStatsSegmentByRowKey.isEmpty()) {
            return;
        }
        if (!TabStatsLayoutMode.shouldSeparateStats()) {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                    continue;
                }
                String key = tabRowKey(row);
                String clean = tabUnpaddedLineByRowKey.get(key);
                if (clean != null) {
                    tabRenderedLineByRowKey.put(key, clean);
                }
            }
            return;
        }
        FontRenderer font = mc.fontRendererObj;
        if (LunarTabLogoCompatibility.isNativeRowDrawObserved()) {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                    continue;
                }
                String key = tabRowKey(row);
                String clean = tabUnpaddedLineByRowKey.get(key);
                TabStatsDrawParts parts = splitTabStatsForDraw(clean, tabStatsSegmentByRowKey.get(key));
                if (parts != null) {
                    tabRenderedLineByRowKey.put(key, parts.left);
                }
            }
            return;
        }
        int targetWidth = 0;
        try {
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                    continue;
                }
                String line = tabUnpaddedLineByRowKey.get(tabRowKey(row));
                if (line == null || line.isEmpty()) {
                    IChatComponent display = row.getDisplayName();
                    line = display == null
                            ? ScorePlayerTeam.formatPlayerName(row.getPlayerTeam(), RemoteDisguiseManager.getSourceProfile(row).getName())
                            : display.getFormattedText();
                }
                String stats = tabStatsSegmentByRowKey.get(tabRowKey(row));
                TabStatsDrawParts parts = splitTabStatsForDraw(line, stats);
                int logoWidth = LunarTabLogoCompatibility.getLogoWidth(RemoteDisguiseManager.getSourceProfile(row).getId());
                int width = parts == null
                        ? logoWidth + font.getStringWidth(line)
                        : logoWidth + font.getStringWidth(parts.left)
                                + font.getCharWidth(' ') + font.getStringWidth(parts.stats);
                targetWidth = Math.max(targetWidth, width);
            }

            int spaceWidth = Math.max(1, font.getCharWidth(' '));
            for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
                if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                    continue;
                }
                String key = tabRowKey(row);
                String line = tabUnpaddedLineByRowKey.get(key);
                String stats = tabStatsSegmentByRowKey.get(key);
                TabStatsDrawParts parts = splitTabStatsForDraw(line, stats);
                if (parts == null) {
                    continue;
                }
                int logoWidth = LunarTabLogoCompatibility.getLogoWidth(RemoteDisguiseManager.getSourceProfile(row).getId());
                int occupied = logoWidth + font.getStringWidth(parts.left) + font.getStringWidth(parts.stats);
                int paddingWidth = Math.max(spaceWidth, targetWidth - occupied);
                String spacer = buildExactInvisibleTabSpacer(font, paddingWidth);
                StringBuilder padded = new StringBuilder(parts.left.length() + parts.stats.length()
                        + spacer.length() + 4);
                padded.append(parts.left).append(spacer).append(parts.stats);
                tabRenderedLineByRowKey.put(key, padded.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    /** Point-in-time dump of the exact clean and Lunar-padded strings used by TAB alignment. */
    public static String buildTabAlignmentDebugReport() {
        StringBuilder out = new StringBuilder(24 * 1024);
        Minecraft mc = Minecraft.getMinecraft();
        out.append("gomod123 TAB alignment debug\n");
        out.append("generated_epoch_ms=").append(System.currentTimeMillis()).append('\n');
        out.append("weave_runtime=").append(GomodFixes.isWeaveRuntime()).append('\n');
        out.append("stats_layout=").append(TabStatsLayoutMode.describe()).append('\n');
        out.append("tab_victims=").append(ConfigManager.isKillCounterTabKillersEnabled())
                .append(" dimmed=").append(ConfigManager.isKillCounterTabKillersVictimDimmed())
                .append(" indent=pixels color=5555FF alpha=90\n");
        out.append("runtime_state: kill_counter_enabled=").append(enabled)
                .append(" tab_qol_runtime=").append(isTabQolRuntimeContext(mc))
                .append(" blitz_active=").append(mc != null && GameContext.isBlitzGameActive(mc))
                .append(" blitz_deathmatch=").append(mc != null && GameContext.isBlitzDeathmatchPhase(mc))
                .append(" game_has_ended=").append(gameHasEnded)
                .append(" tab_render_depth=").append(tabOverlayRenderDepth)
                .append(" stats_muted=").append(TabStatsRuntimeMute.isMuted())
                .append(" force_clean_dead=").append(ConfigManager.isHypixelTabQuitGhostHideEnabled())
                .append(" live_local_dead_filter=").append(shouldSuppressDeadSpectatorsForLiveLocal(mc))
                .append(" tracked_dead_aliases=").append(deadPlayers.size()).append('\n');
        out.append("font_tab_fallback_since_launch: text_calls=").append(tabDebugFontTabTextCalls.get())
                .append(" row_matches=").append(tabDebugFontTabRowMatches.get())
                .append(" font_renderer_active=").append(MixinStatus.fontRendererActive).append('\n');
        out.append("native_tab_stats_draw_since_launch: row_matches=").append(tabDebugNativeStatsRowMatches.get())
                .append(" replacements=").append(tabDebugNativeStatsReplacements.get()).append('\n');
        if (!deadPlayers.isEmpty()) {
            TreeSet<String> trackedDead = new TreeSet<String>(deadPlayers);
            out.append("tracked_dead_sample=");
            int shown = 0;
            for (String name : trackedDead) {
                if (shown >= 20) {
                    out.append("...(+").append(trackedDead.size() - shown).append(')');
                    break;
                }
                if (shown++ > 0) out.append(',');
                out.append(name);
            }
            out.append('\n');
        }
        appendTabVictimRenderDiagnostics(out);
        out.append("lunar_reflection=").append(LunarTabLogoCompatibility.describeState()).append('\n');
        if (mc == null || mc.fontRendererObj == null || mc.getNetHandler() == null) {
            out.append("tab=<unavailable>\n");
            return out.toString();
        }

        FontRenderer font = mc.fontRendererObj;
        ArrayList<NetworkPlayerInfo> debugRows = new ArrayList<NetworkPlayerInfo>(
                mc.getNetHandler().getPlayerInfoMap());
        Map<String, NetworkPlayerInfo> debugTabIndex = buildTabProfileIndex(debugRows);
        int rowIndex = 0;
        for (NetworkPlayerInfo row : debugRows) {
            if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                continue;
            }
            String profileName = RemoteDisguiseManager.getSourceProfile(row).getName();
            String teamFormatted;
            try {
                teamFormatted = ScorePlayerTeam.formatPlayerName(row.getPlayerTeam(), profileName);
            } catch (Throwable ignored) {
                teamFormatted = profileName;
            }
            boolean trackedDead = isPlayerDead(profileName);
            boolean serverDead = mc != null && GameContext.isBlitzGameActive(mc)
                    && isBlitzTabServerDeadBlue(teamFormatted, profileName);
            Integer victimDepth = tabKillersIndentDepthByLower.get(profileName.toLowerCase(Locale.ROOT));
            String parentKiller = trackedDead || serverDead
                    ? findTabKillersParentNameOnTab(profileName, debugRows, debugTabIndex) : null;
            String key = tabRowKey(row);
            String clean = tabUnpaddedLineByRowKey.get(key);
            String rendered = tabRenderedLineByRowKey.get(key);
            String stats = tabStatsSegmentByRowKey.get(key);
            if (clean == null) {
                IChatComponent display = row.getDisplayName();
                clean = display == null
                        ? ScorePlayerTeam.formatPlayerName(row.getPlayerTeam(), RemoteDisguiseManager.getSourceProfile(row).getName())
                        : display.getFormattedText();
            }
            if (rendered == null) {
                rendered = clean;
            }
            TabStatsDrawParts cleanParts = splitTabStatsForDraw(clean, stats);
            TabStatsDrawParts renderedParts = splitTabStatsForDraw(rendered, stats);
            int logoWidth = LunarTabLogoCompatibility.getLogoWidth(RemoteDisguiseManager.getSourceProfile(row).getId());
            int cleanLeftWidth = cleanParts == null ? -1 : font.getStringWidth(cleanParts.left);
            int renderedLeftWidth = renderedParts == null ? -1 : font.getStringWidth(renderedParts.left);
            int statsWidth = cleanParts == null ? 0 : font.getStringWidth(cleanParts.stats);
            int predictedStatsStart = renderedParts == null ? -1 : logoWidth + renderedLeftWidth;
            int predictedStatsEnd = predictedStatsStart < 0 ? -1 : predictedStatsStart + statsWidth;

            out.append("\nrow=").append(rowIndex++)
                    .append(" name=").append(RemoteDisguiseManager.getSourceProfile(row).getName())
                    .append(" uuid=").append(RemoteDisguiseManager.getSourceProfile(row).getId()).append('\n');
            out.append("logo_width=").append(logoWidth)
                    .append(" victim=").append(isTabKillersStyleVictim(row, mc))
                    .append(" victim_offset_px=").append(getTabKillersVictimHeadOffsetPixels(row))
                    .append(" victim_depth=").append(victimDepth == null ? "<none>" : victimDepth)
                    .append(' ').append(LunarTabLogoCompatibility.describeNativeRow(RemoteDisguiseManager.getSourceProfile(row).getId()))
                    .append(" clean_width=").append(font.getStringWidth(clean))
                    .append(" rendered_width=").append(font.getStringWidth(rendered))
                    .append(" clean_left_width=").append(cleanLeftWidth)
                    .append(" rendered_left_width=").append(renderedLeftWidth)
                    .append(" inserted_width=").append(cleanLeftWidth < 0 ? -1 : renderedLeftWidth - cleanLeftWidth)
                    .append(" stats_width=").append(statsWidth)
                    .append(" predicted_stats_start=").append(predictedStatsStart)
                    .append(" predicted_stats_end=").append(predictedStatsEnd).append('\n');
            out.append("signals tracked_dead=").append(trackedDead)
                    .append(" server_dead_blue_or_gray=").append(serverDead)
                    .append(" parent_killer=").append(parentKiller == null ? "<none>" : parentKiller)
                    .append(" team=").append(debugVisibleTabText(teamFormatted)).append('\n');
            out.append("stats=").append(debugVisibleTabText(stats)).append('\n');
            out.append("clean=").append(debugVisibleTabText(clean)).append('\n');
            out.append("lunar_input=").append(debugVisibleTabText(rendered)).append('\n');
        }
        return out.toString();
    }

    private static void appendTabVictimRenderDiagnostics(StringBuilder out) {
        out.append("runtime_hook_counts_since_launch: overlay_passes=").append(tabDebugOverlayPasses.get())
                .append(" row_captures=").append(tabDebugRowCaptures.get())
                .append(" lunar_draw_calls=").append(tabDebugLunarDrawCalls.get())
                .append(" lunar_victim_draw_calls=").append(tabDebugLunarVictimDrawCalls.get())
                .append(" lunar_logo_draw_calls=").append(tabDebugLunarLogoDrawCalls.get())
                .append(" lunar_logo_victim_offsets_applied=").append(tabDebugLunarLogoVictimOffsetsApplied.get())
                .append(" text_offset_calls=").append(tabDebugTextOffsetCalls.get())
                .append(" victim_text_offsets_applied=").append(tabDebugTextOffsetsApplied.get())
                .append(" color_calls=").append(tabDebugColorCalls.get())
                .append(" victim_colors_applied=").append(tabDebugVictimColorsApplied.get())
                .append(" width_calls=").append(tabDebugWidthCalls.get())
                .append(" victim_widths_reserved=").append(tabDebugVictimWidthsReserved.get())
                .append(" victim_head_draws=").append(tabDebugVictimHeadsDrawn.get()).append('\n');
        out.append("last_victim_head_hook: row=").append(tabDebugLastHeadIdentity)
                .append(" x=").append(tabDebugLastHeadSourceX == Integer.MIN_VALUE
                        ? "<none>" : tabDebugLastHeadSourceX + "->" + tabDebugLastHeadRenderedX).append('\n');
        out.append("last_victim_text_hook: row=").append(tabDebugLastTextIdentity)
                .append(" x=").append(tabDebugLastTextSourceX).append("->").append(tabDebugLastTextRenderedX)
                .append(" color_argb=").append(Integer.toHexString(tabDebugLastVictimColor))
                .append(" prepared=").append(debugVisibleTabText(tabDebugLastPreparedVictimText)).append('\n');
        out.append("renderer_discovery=")
                .append(System.getProperty("gomod.qa.tab_victims.renderer_discovery", "<no candidate observed>"))
                .append('\n');
    }

    /**
     * Saved point-in-time diagnostic for Tab Victims, including the chat-derived kill graph and the actual row order
     * the current renderer pipeline produces. This is intentionally opt-in because it includes the current TAB names.
     */
    public static String buildTabVictimsDebugReport() {
        StringBuilder out = new StringBuilder(16 * 1024);
        Minecraft mc = Minecraft.getMinecraft();
        out.append("gomod123 Tab Victims debug\n");
        out.append("report_version=2 row_rendered_field=expected_legacy_text_not_pixels\n");
        out.append("generated_epoch_ms=").append(System.currentTimeMillis()).append('\n');
        out.append("victim_style=blue_shadow indent_per_depth_px=")
                .append(ConfigManager.getKillCounterTabKillersVictimOffsetPx())
                .append(" kill_count_offset_px=").append(ConfigManager.getKillCounterTabKillersVictimKillCountOffsetPx())
                .append(" kill_count_aligned=").append(ConfigManager.isKillCounterTabKillersVictimKillCountAligned())
                .append('\n');
        appendTabVictimRenderDiagnostics(out);
        out.append("tab_victims_enabled=").append(ConfigManager.isKillCounterTabKillersEnabled())
                .append(" kill_counter_config=").append(ConfigManager.isKillCounterEnabled())
                .append(" kill_counter_runtime=").append(enabled)
                .append(" kill_display_mode=").append(tabKillCounterDisplayMode)
                .append(" kill_effect_display_mode=").append(killEffectTabMode)
                .append(" game_has_ended=").append(gameHasEnded)
                .append(" saw_taunt=").append(sawTaunt)
                .append(" stats_muted=").append(TabStatsRuntimeMute.isMuted()).append('\n');
        if (mc == null) {
            out.append("minecraft=<unavailable>\n");
            return out.toString();
        }
        out.append("context: tab_qol_runtime=").append(isTabQolRuntimeContext(mc))
                .append(" blitz_active=").append(GameContext.isBlitzGameActive(mc))
                .append(" blitz_deathmatch=").append(GameContext.isBlitzDeathmatchPhase(mc))
                .append(" blitz_pregame=").append(GameContext.isBlitzPregameLobby(mc))
                .append(" blitz_game_over=").append(GameContext.isBlitzGameOverSidebar(mc))
                .append(" replay=").append(GameContext.isHypixelReplayWatching(mc));
        String localName = mc.thePlayer != null && mc.thePlayer.getGameProfile() != null
                ? mc.thePlayer.getGameProfile().getName() : null;
        out.append(" local_name=").append(localName == null ? "<unavailable>" : localName)
                .append(" local_tracked_dead=").append(localName != null && isPlayerDead(localName)).append('\n');

        TreeSet<String> deadSnapshot = new TreeSet<String>(deadPlayers);
        out.append("tracked_dead_aliases=").append(deadSnapshot.isEmpty() ? "<none>" : String.join(",", deadSnapshot))
                .append('\n');
        TreeMap<String, Integer> sortedKills = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        sortedKills.putAll(killCounts);
        out.append("kill_counts:\n");
        if (sortedKills.isEmpty()) {
            out.append("  <none>\n");
        } else {
            for (Map.Entry<String, Integer> entry : sortedKills.entrySet()) {
                out.append("  ").append(entry.getKey()).append("=").append(entry.getValue()).append('\n');
            }
        }
        TreeMap<String, List<String>> sortedVictims = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        sortedVictims.putAll(playerVictims);
        out.append("kill_edges (killer -> victims):\n");
        if (sortedVictims.isEmpty()) {
            out.append("  <none>\n");
        } else {
            for (Map.Entry<String, List<String>> entry : sortedVictims.entrySet()) {
                out.append("  ").append(entry.getKey()).append(" kills=").append(getKillCount(entry.getKey()))
                        .append(" victims=").append(entry.getValue() == null ? "<null>" : entry.getValue())
                        .append('\n');
            }
        }
        out.append("recent_kill_parse_events (newest last):\n");
        java.util.ArrayList<String> recentKillEvents;
        synchronized (tabVictimsDebugEvents) {
            recentKillEvents = new java.util.ArrayList<String>(tabVictimsDebugEvents);
        }
        if (recentKillEvents.isEmpty()) {
            out.append("  <none captured>\n");
        } else {
            for (String event : recentKillEvents) out.append("  ").append(event).append('\n');
        }
        synchronized (pendingKillFeedLock) {
            prunePendingKillFeedLinesLocked(System.currentTimeMillis());
            out.append("pending_live_kill_feed_lines=").append(pendingKillFeedLines.size()).append('\n');
            for (PendingKillFeedLine pending : pendingKillFeedLines) {
                out.append("  age_ms=").append(Math.max(0L, System.currentTimeMillis() - pending.queuedAtMs))
                        .append(" line=").append(compactKillDebugLine(pending.plain)).append('\n');
            }
        }

        if (mc.getNetHandler() == null) {
            out.append("tab=<not connected>\n");
            return out.toString();
        }
        ArrayList<NetworkPlayerInfo> rawRows = new ArrayList<NetworkPlayerInfo>(mc.getNetHandler().getPlayerInfoMap());
        Collection<NetworkPlayerInfo> finalizedRows;
        try {
            finalizedRows = finalizeTabListForOverlayRender(rawRows);
        } catch (Throwable error) {
            out.append("row_pipeline_error=").append(error.getClass().getSimpleName()).append(": ")
                    .append(String.valueOf(error.getMessage())).append('\n');
            finalizedRows = rawRows;
        }
        ArrayList<NetworkPlayerInfo> rendererInput = finalizedRows == null
                ? new ArrayList<NetworkPlayerInfo>() : new ArrayList<NetworkPlayerInfo>(finalizedRows);
        List<NetworkPlayerInfo> rendererRows;
        try {
            rendererRows = applyTabKillersGroupingIfEnabled(new ArrayList<NetworkPlayerInfo>(rendererInput));
        } catch (Throwable error) {
            out.append("grouping_error=").append(error.getClass().getSimpleName()).append(": ")
                    .append(String.valueOf(error.getMessage())).append('\n');
            rendererRows = rendererInput;
        }
        out.append("tab_rows: raw=").append(rawRows.size())
                .append(" finalized=").append(rendererInput.size())
                .append(" grouped=").append(rendererRows == null ? 0 : rendererRows.size()).append('\n');
        if (rendererRows == null || rendererRows.isEmpty()) {
            out.append("  <no rows>\n");
            return out.toString();
        }
        int index = 0;
        for (NetworkPlayerInfo row : rendererRows) {
            if (row == null || RemoteDisguiseManager.getSourceProfile(row) == null) {
                continue;
            }
            String name = RemoteDisguiseManager.getSourceProfile(row).getName();
            String identity = getTabKillersIdentityKey(name);
            String teamLine;
            try {
                teamLine = ScorePlayerTeam.formatPlayerName(row.getPlayerTeam(), name);
            } catch (Throwable ignored) {
                teamLine = name;
            }
            IChatComponent display = row.getDisplayName();
            String displayLine = display == null ? teamLine : display.getFormattedText();
            String finalLine = applyTabKillersStyleToRenderedName(row, displayLine);
            Integer depth = tabKillersIndentDepthByLower.get(identity);
            String parent = tabKillersParentIdentityByChildIdentity.get(identity);
            boolean serverDead = GameContext.isBlitzGameActive(mc) && isBlitzTabServerDeadBlue(teamLine, name);
            boolean retainedVictim = isRetainedRenderedTabVictim(row, mc);
            out.append("row[").append(index++).append("] name=").append(name)
                    .append(" uuid=").append(RemoteDisguiseManager.getSourceProfile(row).getId())
                    .append(" synthetic=").append(isSyntheticDeadTabRow(row))
                    .append(" kills=").append(getKillCount(name))
                    .append(" tracked_dead=").append(isPlayerDead(name))
                    .append(" server_dead=").append(serverDead)
                    .append(" treated_dead=").append(tabSortTreatAsDeadSpectator(row, mc))
                    .append(" retained_victim=").append(retainedVictim)
                    .append(" opacity_dimmed=").append(shouldDimRenderedTabVictim(row, mc))
                    .append(" expected_italic=false")
                    .append(" depth=").append(depth == null ? "<none>" : depth)
                    .append(" expected_indent_px=").append(getTabKillersVictimHeadOffsetPixels(row))
                    .append(" parent=").append(parent == null ? "<none>" : parent)
                    .append(" mutual=").append(isRenderedTabMutualKill(row, name)).append('\n');
            out.append("  team=").append(debugVisibleTabText(teamLine)).append('\n');
            out.append("  display=").append(debugVisibleTabText(displayLine)).append('\n');
            out.append("  rendered=").append(debugVisibleTabText(finalLine)).append('\n');
        }
        return out.toString();
    }

    private static void recordTabVictimsKillDebugEvent(String event) {
        if (event == null || event.isEmpty()) return;
        synchronized (tabVictimsDebugEvents) {
            tabVictimsDebugEvents.addLast(System.currentTimeMillis() + " " + event);
            while (tabVictimsDebugEvents.size() > TAB_VICTIMS_DEBUG_EVENT_LIMIT) {
                tabVictimsDebugEvents.removeFirst();
            }
        }
    }

    private static String debugKillName(String name) {
        return name == null || name.trim().isEmpty() ? "<unparsed>" : name.trim();
    }

    private static String compactKillDebugLine(String line) {
        if (line == null) return "<null>";
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
        if (plain == null) plain = line;
        plain = plain.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return plain.length() <= 180 ? plain : plain.substring(0, 180) + "…";
    }

    private static String debugVisibleTabText(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.replace('\u00a7', '&').replace(" ", "\u00b7");
    }

    /** Builds the closest non-short invisible width using normal and bold spaces (normally 4 px and 5 px). */
    private static String buildExactInvisibleTabSpacer(FontRenderer font, int requiredWidth) {
        int normalWidth = Math.max(1, font.getStringWidth(EnumChatFormatting.RESET + " "));
        int boldWidth = Math.max(normalWidth,
                font.getStringWidth(EnumChatFormatting.RESET.toString() + EnumChatFormatting.BOLD + " "));
        int bestNormal = Math.max(1, (requiredWidth + normalWidth - 1) / normalWidth);
        int bestBold = 0;
        int bestWidth = bestNormal * normalWidth;
        for (int normal = 0; normal <= 64; normal++) {
            for (int bold = 0; bold <= 64; bold++) {
                if (normal + bold == 0) {
                    continue;
                }
                int width = normal * normalWidth + bold * boldWidth;
                if (width >= requiredWidth
                        && (width < bestWidth || (width == bestWidth && normal + bold < bestNormal + bestBold))) {
                    bestWidth = width;
                    bestNormal = normal;
                    bestBold = bold;
                }
            }
        }
        StringBuilder spacer = new StringBuilder(bestNormal + bestBold + 8);
        spacer.append(EnumChatFormatting.RESET);
        for (int i = 0; i < bestNormal; i++) {
            spacer.append(' ');
        }
        if (bestBold > 0) {
            spacer.append(EnumChatFormatting.BOLD);
            for (int i = 0; i < bestBold; i++) {
                spacer.append(' ');
            }
        }
        spacer.append(EnumChatFormatting.RESET);
        return spacer.toString();
    }

    /**
     * Updates all player names in the tab list to include kill counts as a suffix.
     * Preserves original team colors/prefixes/suffixes.
     */
    private static void updateAllTabNames() {
        long tabTimer = TabPerformanceDebug.begin();
        long renderTimer = RenderProfiler.begin("TAB.compose");
        boolean sample = me.napim.gomod.command.GomodLagCommand.isTabComposeProfiling();
        long t0 = sample ? System.nanoTime() : 0L;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) {
                return;
            }
            if (!isTabQolRuntimeContext(mc)) {
                resetAllTabNames();
                return;
            }
            if (TabStatsRuntimeMute.isMuted()) {
                resetAllTabNames();
                return;
            }
            lookupNamesCache.clear(); // Clear per-compose lookup cache before each recomposition.
            syncTabStatsSettingsFromConfig();

            Minecraft mcEarly = Minecraft.getMinecraft();
            if (mcEarly != null && GameContext.isBlitzPregameLobby(mcEarly) && gameHasEnded) {
                gameHasEnded = false;
            }

            // If game has ended, don't add kill counters to tab list but keep other functionality
            if (gameHasEnded) {
                // System.out.println("[KillCounter] Game has ended - not displaying kill counters in tab list");
                composeAllPlayerTabDisplayNames(false);
                return;
            }
            composeAllPlayerTabDisplayNames(true);
        } catch (Exception ignored) {
        } finally {
            if (sample) {
                me.napim.gomod.command.GomodLagCommand.onTabComposeSample(System.nanoTime() - t0);
            }
            TabPerformanceDebug.end(TabPerformanceDebug.Metric.COMPOSE, tabTimer);
            RenderProfiler.end("TAB.compose", renderTimer);
        }
    }

    // Map kill effect name to color, matching KillEffectCommand
    private static String mapKillEffectToColor(String effect) {
        if (effect == null) return EnumChatFormatting.AQUA.toString();
        String e = effect.toUpperCase(Locale.ROOT);
        switch (e) {
            case "REGENERATION": return EnumChatFormatting.LIGHT_PURPLE.toString();
            case "RESISTANCE": return EnumChatFormatting.GOLD.toString();
            case "GRAVEDIGGER": return EnumChatFormatting.GREEN.toString();
            case "RANDOM": return EnumChatFormatting.DARK_BLUE.toString();
            case "LEVEL_UP":
            case "LEVELUP": return EnumChatFormatting.YELLOW.toString();
            case "RAPID_FIRE":
            case "FLAME": return EnumChatFormatting.DARK_RED.toString();
            case "SPEED": return EnumChatFormatting.AQUA.toString();
            default: return EnumChatFormatting.AQUA.toString();
        }
    }

    /**
     * Updates tab names without kill counters (for when game has ended).
     * Shows ONLY stats and guild tags - NO kill counters, NO kill effect colors, NO kits.
     */
    private static void updateTabNamesWithoutKillCounters() {
        try {
            composeAllPlayerTabDisplayNames(false);
        } catch (Exception ignored) {
        }
    }

    private static boolean isLocalPlayerTabRow(String baseName) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) {
                return false;
            }
            return baseName != null && baseName.equalsIgnoreCase(mc.thePlayer.getGameProfile().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when {@code name} refers to this client (profile, entity, or session IGN). Used so TAB kit decorations
     * (teammate + enemy) never attach to our own row when Hypixel nicking makes profile vs. visible name differ.
     */
    public static boolean isLocalClientAnyKnownIgn(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) {
                return false;
            }
            if (mc.thePlayer.getGameProfile() != null) {
                String gp = mc.thePlayer.getGameProfile().getName();
                if (gp != null && n.equalsIgnoreCase(gp.trim())) {
                    return true;
                }
            }
            String entityName = mc.thePlayer.getName();
            if (entityName != null && n.equalsIgnoreCase(entityName.trim())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * When disguised, TAB stats use the disguise target's Blitz API data; kill effect color still uses {@code getPlayerEffect(realName)}.
     */
    private static String resolveTabStatsSuffixForRow(String baseName,
            LocalDisguise.RemoteDisguiseProfile remoteDisguiseTarget) {
        if (isLocalPlayerTabRow(baseName) && LocalDisguise.isEnabled()) {
            String disguised = LocalDisguise.getFormattedTabStatsSuffixForTarget();
            if (disguised != null && !disguised.isEmpty()) {
                return disguised;
            }
        }
        if (remoteDisguiseTarget != null) {
            // TAB QoL stats are cached by scanned player name. A remote /disguiser row
            // keeps the source profile name, so resolve its suffix from the disguise
            // target instead of leaking the source player's scanned stats. If the target
            // has not been scanned in this TAB session, use its already-fetched Hypixel data.
            String scannedTargetStats = me.napim.gomod.command.KillEffectCommand
                    .getStatsSuffixFor(remoteDisguiseTarget.name);
            if (scannedTargetStats != null && !scannedTargetStats.isEmpty()) {
                return scannedTargetStats;
            }
            return remoteDisguiseTarget.getFormattedTabStatsSuffix();
        }
        return me.napim.gomod.command.KillEffectCommand.getStatsSuffixFor(resolveTabLookupName(baseName));
    }

    private static String resolveTabGuildTagForRow(String baseName) {
        return resolveTabGuildTagForRow(baseName,
                RemoteDisguiseManager.getDisguisedTargetProfileForSourceName(baseName));
    }

    private static String resolveTabGuildTagForRow(String baseName,
            LocalDisguise.RemoteDisguiseProfile remoteDisguiseTarget) {
        if (!isShowGuildTag()) {
            return "";
        }
        if (isLocalPlayerTabRow(baseName) && LocalDisguise.isEnabled()) {
            String t = LocalDisguise.getTargetGuildTagForTab();
            return t == null ? "" : plainGuildTagLettersOnly(t);
        }
        if (remoteDisguiseTarget != null) {
            Minecraft mc = Minecraft.getMinecraft();
            boolean blitzPregame = GameContext.isBlitzPregameLobby(mc);
            if (!blitzPregame && (GameContext.isHypixelPregameLobby(mc)
                    || GameContext.isBlitzTabDisguiseRankSuppressed(mc))) {
                return "";
            }
            String targetGuildTag = remoteDisguiseTarget.guildTag;
            if (targetGuildTag == null || targetGuildTag.isEmpty()) {
                targetGuildTag = getGuildTag(remoteDisguiseTarget.name);
            }
            return targetGuildTag == null ? "" : plainGuildTagLettersOnly(targetGuildTag);
        }
        return getGuildTag(resolveTabLookupName(baseName));
    }

    /**
     * Visible nick for a row whose profile name is the real IGN (Teams finder / duplicate-TAB resolution).
     */
    private static String resolveKnownNickForRealProfileIgn(String realProfileName, boolean disguiseRow) {
        // A disguise owns the visible identity. Do not restore the source's nick or expose its real IGN.
        if (disguiseRow || realProfileName == null || realProfileName.trim().isEmpty()) {
            return null;
        }
        String fromTab = TabDuplicateNickResolver.getKnownDuplicateNick(realProfileName);
        if (fromTab != null && !fromTab.trim().isEmpty()
                && !LunarDisguiseDisplayCompatibility.containsObfuscatedHypixelName(fromTab, realProfileName)) {
            return fromTab.trim();
        }
        String fromTeams = TeamInviteResponseHandler.REAL_TO_NICK.get(realProfileName.toLowerCase(Locale.ROOT));
        if (fromTeams != null && !fromTeams.trim().isEmpty()
                && !LunarDisguiseDisplayCompatibility.containsObfuscatedHypixelName(fromTeams, realProfileName)) {
            return fromTeams.trim();
        }
        return null;
    }

    private static String resolveTabGuildColorForRow(String baseName) {
        return resolveTabGuildColorForRow(baseName,
                RemoteDisguiseManager.getDisguisedTargetProfileForSourceName(baseName));
    }

    private static String resolveTabGuildColorForRow(String baseName,
            LocalDisguise.RemoteDisguiseProfile remoteDisguiseTarget) {
        if (isLocalPlayerTabRow(baseName) && LocalDisguise.isEnabled()) {
            String c = LocalDisguise.getTargetGuildTagColorForTab();
            return c == null ? "" : c;
        }
        if (remoteDisguiseTarget != null) {
            String c = remoteDisguiseTarget.guildTagColor;
            if (c == null || c.isEmpty()) {
                String targetName = remoteDisguiseTarget.name;
                c = targetName == null ? null : playerGuildTagColors.get(targetName.toLowerCase(Locale.ROOT));
            }
            return c == null ? "" : c;
        }
        String lookupName = resolveTabLookupName(baseName);
        // Keys are normalized to lowercase at insertion — single O(1) lookup.
        String c = playerGuildTagColors.get(lookupName.toLowerCase(Locale.ROOT));
        return c == null ? "" : c;
    }

    private static String resolveTabResolvedRealName(String baseName, boolean disguiseRow) {
        return disguiseRow ? "" : resolveTabResolvedRealName(baseName);
    }

    private static String resolveTabResolvedRealName(String baseName) {
        if (baseName == null || baseName.trim().isEmpty()) {
            return "";
        }
        String resolved = TabDuplicateNickResolver.getKnownRealName(baseName);
        if (resolved == null) {
            return "";
        }
        resolved = resolved.trim();
        if (resolved.isEmpty() || resolved.equalsIgnoreCase(baseName.trim())) {
            return "";
        }
        return resolved;
    }

    /** Real profile name for /tabstats caches when nicked (otherwise {@code baseName}). */
    public static String resolveTabLookupName(String baseName) {
        String resolved = resolveTabResolvedRealName(baseName);
        return resolved.isEmpty() ? baseName : resolved;
    }

    /**
     * True when chat-tracked kit for the local player is Horsetamer or Donkeytamer (any prestige suffix).
     * Used by {@link UselessItemOverlay} so apples and wheat are not marked useless (mount healing).
     */
    public static boolean isLocalPlayerHorsetamerOrDonkeytamerKit() {
        String kit = getLocalPlayerTrackedKit();
        if (kit == null || kit.isEmpty()) {
            return false;
        }
        String lower = kit.toLowerCase(Locale.ROOT);
        return lower.startsWith("horsetamer") || lower.startsWith("donkeytamer");
    }

    public static String getLocalPlayerTrackedKit() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return "";
        }
        String self = mc.thePlayer.getName();
        if (self == null || self.isEmpty()) {
            return "";
        }
        String kit = playerKits.get(self);
        if (kit != null) {
            return kit;
        }
        for (Map.Entry<String, String> entry : playerKits.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(self)) {
                return entry.getValue() == null ? "" : entry.getValue();
            }
        }
        return "";
    }

    /**
     * Checks if the server has already provided a guild tag in the player name.
     * Guild tags typically appear as [TAG] in the player name.
     */
    private static boolean hasServerGuildTag(String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return false;
        }
        // Remove color codes to check for guild tag pattern (using precompiled pattern)
        String cleanName = COLOR_CODE_PATTERN.matcher(serverName).replaceAll("");
        // Check if there's already a [TAG] pattern (guild tag) using precompiled pattern
        return GUILD_TAG_PATTERN.matcher(cleanName).matches();
    }
    
    /**
     * Resets all player names in the tab list back to server defaults.
     */
    private static void resetAllTabNames() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) {
                return;
            }
            if (!GomodFixes.isWeaveRuntime()) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    // Null clears the override so Minecraft uses the default formatting
                    info.setDisplayName(null);
                }
            }
            clearTabFormattingCaches();
            lastBlitzPregameTabPhase = null;
            lastBlitzGameActiveTabPhase = null;
            lastHypixelReplayTabPhase = null;
            lastBlitzGameOverTabPhase = null;
            lastHypixelLobbyTabPhase = null;
        } catch (Exception ignored) {
        }
    }
    
    /**
     * Extracts player name from a message part.
     */
    private static String extractPlayerName(String text) {
        return extractPlayerNameInternal(text, false);
    }

    /**
     * Extracts the first player-like name from a message part.
     */
    private static String extractPlayerNameFromStart(String text) {
        return extractPlayerNameInternal(text, true);
    }

    private static void markPlayerDead(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return;
        }
        markPlayerDead(playerName, deathOrderCounter.incrementAndGet());
    }

    private static void markPlayerDead(String playerName, long sequence) {
        if (playerName == null || playerName.isEmpty()) {
            return;
        }
        ArmorDisguise.onConfirmedDeath(playerName);
        if (isLocalClientAnyKnownIgn(playerName)) {
            BlitzStarEffectsHudTracker.clearAll();
        }
        long safeSequence = sequence > 0L ? sequence : deathOrderCounter.incrementAndGet();
        for (String lookupName : getTabVictimIdentityAliases(playerName)) {
            String lower = lookupName.toLowerCase(Locale.ROOT);
            deadPlayers.add(lower);
            deathOrderByPlayer.put(lower, safeSequence);
        }
    }

    private static String resolveYouToLocalPlayer(String name) {
        if (name == null) {
            return null;
        }
        if (!name.equalsIgnoreCase("you")) {
            return name;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
                String local = mc.thePlayer.getGameProfile().getName();
                if (local != null && !local.isEmpty()) {
                    return local;
                }
            }
        } catch (Throwable ignored) {
        }
        return name;
    }

    /**
     * If Hypixel still sends plain §f for a player we already marked dead from chat, dim the row so they do not look alive.
     */
    private static String dimBlitzDeadTabLineIfStillBrightWhite(String formatted, String baseName) {
        if (formatted == null || baseName == null || baseName.isEmpty()) {
            return formatted;
        }
        String plain = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (plain == null) {
            return formatted;
        }
        String trimmedPlain = plain.trim();
        String bn = baseName.trim();
        if (bn.isEmpty()) {
            return formatted;
        }
        String pl = trimmedPlain.toLowerCase(Locale.ROOT);
        String bl = bn.toLowerCase(Locale.ROOT);
        if (!pl.endsWith(bl)) {
            return formatted;
        }
        int boundary = pl.length() - bl.length();
        if (boundary > 0 && !Character.isWhitespace(pl.charAt(boundary - 1))) {
            return formatted;
        }
        if (formatted.contains("\u00a79") || formatted.contains("\u00a77") || formatted.contains("\u00a78")) {
            return formatted;
        }
        return net.minecraft.util.EnumChatFormatting.GRAY + formatted;
    }

    private static String extractPlayerNameInternal(String text, boolean fromStart) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String cleanText = stripColors(text).trim();
        if (cleanText.isEmpty()) {
            return null;
        }

        String[] words = cleanText.split("\\s+");
        int start = fromStart ? 0 : words.length - 1;
        int end = fromStart ? words.length : -1;
        int step = fromStart ? 1 : -1;

        for (int i = start; i != end; i += step) {
            String raw = words[i].replaceAll("[.,!?]", "");
            if (raw.isEmpty()) {
                continue;
            }

            // Handle possessive kills (e.g., "Name's Wolf") by extracting the owner name.
            if (raw.endsWith("'s") || raw.endsWith("’s")) {
                String owner = raw.substring(0, raw.length() - 2);
                if (!owner.isEmpty()
                        && owner.matches("^[A-Za-z0-9_]{3,16}$")
                        && !isKillCounterOrNonPlayer(owner)) {
                    return owner.trim();
                }
            }

            if (isKillCounterOrNonPlayer(raw)) {
                continue;
            }

            if (!raw.matches("^[A-Za-z0-9_]{3,16}$")) {
                continue;
            }

            return raw.trim();
        }

        return null;
    }
    
    /**
     * Checks if a string is a kill counter or other non-player text.
     */
    private static boolean isKillCounterOrNonPlayer(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        
        // Check for kill counter patterns
        if (text.matches("\\(Kill #\\d+[,\\d]*\\)")) {
            return true;
        }
        
        // Check for other common non-player patterns
        if (text.matches("\\(\\d+\\)") || // (123)
            text.matches("\\[\\d+\\]") || // [123]
            text.matches("#\\d+") || // #123
            text.matches("\\d+") || // Pure numbers
            text.matches(".*\\(.*\\).*") || // Contains parentheses
            text.matches(".*\\[.*\\].*") || // Contains brackets
            text.matches(".*#.*")) { // Contains hash
            return true;
        }
        
        return false;
    }
    
    /**
     * Extracts player color from the message.
     */
    private String extractPlayerColor(String message, String playerName) {
        try {
            // Look for color codes before the player name
            int nameIndex = message.indexOf(playerName);
            if (nameIndex > 0) {
                String beforeName = message.substring(0, nameIndex);
                // Find the last color code
                int lastColorIndex = beforeName.lastIndexOf('\u00A7');
                if (lastColorIndex >= 0 && lastColorIndex + 1 < beforeName.length()) {
                    return "\u00A7" + beforeName.charAt(lastColorIndex + 1);
                }
            }
        } catch (Exception e) {
            // Ignore color extraction errors
        }
        return null;
    }

    // Exposed for other features (e.g., settings GUI) to apply tab composition immediately on the client thread.
    public static void forceTabRefresh() {
        tabCompositionDirty = false;
        long now = System.currentTimeMillis();
        lastTabCompositionApplyMs = now;
        lastPeriodicTabComposeMs = now;
        clearTabFormattingCaches();
        updateAllTabNames();
    }

    /**
     * Drops Tab Stats runtime data (WL/guild/kill-effect caches), clears tab format cache, and sets every
     * {@link NetworkPlayerInfo} display name back to vanilla. Config stays as-is; use {@code /tabstats all} to refill.
     * Must run on the client thread.
     */
    public static void resetTabStatsOverlayNow() {
        clearTabFormattingCaches();
        tabCompositionDirty = false;
        me.napim.gomod.command.KillEffectCommand.clearAllTabStatsVisualCaches();
        playerGuildTags.clear();
        playerGuildTagColors.clear();
        guildTagFetchInFlight.clear();
        resetAllTabNames();
    }

    /**
     * After {@link #stopTemporarily()} on join, vanilla tab formatting may arrive a few ticks later;
     * chained refreshes repopulate {@link #tabDisplayFormatCache} so stats/guild show without toggling Kill Counter.
     */
    private static void schedulePostJoinTabRefreshes() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            mc.addScheduledTask(() -> {
                markTabCompositionDirty();
                forceTabRefresh();
                mc.addScheduledTask(() -> {
                    markTabCompositionDirty();
                    forceTabRefresh();
                    mc.addScheduledTask(() -> {
                        markTabCompositionDirty();
                        forceTabRefresh();
                    });
                });
            });
        } catch (Throwable ignored) {
        }
    }
    
    
    /** Strip § from cached guild tag text (color is applied from {@link #playerGuildTagColors} at compose time). */
    private static String plainGuildTagLettersOnly(String cached) {
        if (cached == null || cached.isEmpty()) {
            return "";
        }
        String p = EnumChatFormatting.getTextWithoutFormattingCodes(cached);
        return (p != null ? p : cached).trim();
    }

    /**
     * Gets guild tag for a player with proper color formatting
     */
    private static String getGuildTag(String playerName) {
        // System.out.println("[KillCounter] getGuildTag called for: " + playerName + ", showGuildTag setting: " + isShowGuildTag());
        
        if (!isShowGuildTag()) {
            // System.out.println("[KillCounter] Guild tag display is DISABLED for " + playerName);
            return "";
        }
        
        // Check cache first (letters only; § color lives in playerGuildTagColors)
        // Keys are normalized to lowercase at insertion.
        String guildKeyLower = playerName.toLowerCase(Locale.ROOT);
        if (playerGuildTags.containsKey(guildKeyLower)) {
            String cachedTag = playerGuildTags.get(guildKeyLower);
            cachedTag = decodeJsonEscapes(cachedTag);
            cachedTag = fixGuildTagUnicode(cachedTag);
            return plainGuildTagLettersOnly(cachedTag);
        }
        
        // System.out.println("[KillCounter] NO cached guild tag for " + playerName + " - returning empty");
        // Return empty if not cached - guild tags are fetched when /tabstats all is run
        return "";
    }
    
    /**
     * Gets kit for a player if teammate kit display is enabled
     */
    private static String getPlayerKit(String playerName) {
        if (!isShowTeammateKits()) {
            return "";
        }
        if (isLocalClientAnyKnownIgn(playerName)) {
            return "";
        }

        // Check if player has a kit
        if (playerKits.containsKey(playerName)) {
            String kit = playerKits.get(playerName);
            // System.out.println("[KillCounter] Using cached kit for " + playerName + ": " + kit);
            return kit;
        }
        
        return "";
    }
    
    /**
     * Gets enemy kit for a player if enemy kit display is enabled
     */
    private static String getEnemyPlayerKit(String playerName) {
        if (!isShowEnemyKits()) {
            return "";
        }
        if (isLocalClientAnyKnownIgn(playerName)) {
            return "";
        }

        // Teleporter heads can identify a nick while TAB's profile retains its linked real IGN, or vice versa.
        // Resolve only match identity aliases here; local disguise targets must remain separate players.
        for (String lookupName : getTabVictimIdentityAliases(playerName)) {
            String kit = getCaseInsensitiveValue(enemyPlayerKits, lookupName);
            if (kit != null && !kit.isEmpty()) return kit;
        }
        return "";
    }
    
    /**
     * Detects kit from item name using regex patterns
     */
    /** Returns true when plain display text matches an unrenamed Blitz kit item ({@code Kit's Item (Level)}). */
    public static boolean isDefaultKitItemDisplayName(String itemName) {
        return detectKitFromItemName(itemName) != null;
    }

    private static String detectKitFromItemName(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }
        // Hypixel / font may use curly apostrophes in display names; pattern expects ASCII '
        String normalized = itemName.replace('\u2019', '\'').replace('\u2018', '\'');
        
        java.util.regex.Matcher matcher = KIT_ITEM_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String kitName = matcher.group(1).trim();
            String level = matcher.group(2).trim();
            String compactKit = kitName.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            
            // Check if the kit name is in our known kit list (items may use "Red Dragon" vs list entry "RedDragon")
            for (String knownKit : KIT_NAMES) {
                String compactKnown = knownKit.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
                if (compactKit.equals(compactKnown)) {
                    String formattedLevel = formatKitLevel(level);
                    if (formattedLevel == null) {
                        return null;
                    }
                    return knownKit + " " + formattedLevel;
                }
            }
        }
        
        return null;
    }

    /**
     * Captures the kit text attached to a player skull in the live spectator Teleporter. This menu remains
     * available after the enemy entities unload, so its server-provided row is authoritative for TAB fallback.
     */
    public static boolean observeEnemyKitFromTeleporter(String playerName, String[] rowText) {
        if (!isShowEnemyKits() || playerName == null || playerName.trim().isEmpty()
                || isLocalClientAnyKnownIgn(playerName) || getCaseInsensitiveValue(playerKits, playerName) != null) {
            return false;
        }
        String detected = detectKitFromTeleporterText(rowText);
        if (detected == null || detected.isEmpty()) return false;
        String previous = getCaseInsensitiveValue(enemyPlayerKits, playerName);
        if (detected.equalsIgnoreCase(previous)) return true;
        if (previous != null && previous.equalsIgnoreCase(EnemyKitPrestigeValidator.upgradePrestigeOneToTwo(detected))) {
            return true;
        }

        for (String existing : enemyPlayerKits.keySet()) {
            if (existing != null && existing.equalsIgnoreCase(playerName) && !existing.equals(playerName)) {
                enemyPlayerKits.remove(existing);
            }
        }
        recordDetectedEnemyKit(playerName, null, detected);
        return true;
    }

    private static String detectKitFromTeleporterText(String[] rowText) {
        if (rowText == null || rowText.length == 0) return null;
        for (String raw : rowText) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String plain = stripColors(raw).trim();
            String itemKit = detectKitFromItemName(plain);
            if (itemKit != null) return itemKit;

            String value = plain;
            Matcher label = TELEPORTER_KIT_LABEL_PATTERN.matcher(value);
            if (label.matches()) value = label.group(1).trim();
            value = value.replaceFirst("(?i)\\s+kit\\s*$", "").trim();
            if (value.isEmpty()) continue;

            String level = null;
            Matcher levelMatch = TELEPORTER_KIT_LEVEL_PATTERN.matcher(value);
            if (levelMatch.matches()) {
                value = levelMatch.group(1).trim();
                level = levelMatch.group(2);
            }
            String compactValue = value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (compactValue.isEmpty()) continue;
            for (String knownKit : KIT_NAMES) {
                String compactKit = knownKit.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                String spacedKit = knownKit.replaceAll("([a-z])([A-Z])", "$1 $2")
                        .replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (!compactValue.equals(compactKit) && !compactValue.equals(spacedKit)) continue;
                if (level == null || level.trim().isEmpty()) return knownKit;
                String formattedLevel = formatTeleporterKitLevel(level);
                return formattedLevel == null ? knownKit : knownKit + " " + formattedLevel;
            }
        }
        return null;
    }

    private static String formatTeleporterKitLevel(String level) {
        if (level == null || level.isEmpty()) return null;
        int stars = 0;
        for (int i = 0; i < level.length(); i++) {
            char c = level.charAt(i);
            if (c == '\u272B' || c == '\u2B50' || c == '\u2605' || c == '\u2726'
                    || c == '\u2606' || c == '\u2B51') stars++;
        }
        if (stars == 1) return "\u272B";
        if (stars >= 2) return "\u272B\u272B";
        return formatKitLevel(level);
    }
    
    /**
     * Formats kit level to standard format, handling ?? characters and star symbols
     */
    private static String formatKitLevel(String level) {
        if (level == null || level.isEmpty()) {
            return null;
        }
        
        // Handle ?? characters (which represent ✫ in logs due to Unicode issues)
        if (level.equals("?")) {
            return "\u272B"; // Prestige I - single star (✫)
        } else if (level.equals("??")) {
            return "\u272B\u272B"; // Prestige II - double star (✫✫)
        }
        
        // Handle actual ✫ symbols (direct Unicode characters)
        if (level.equals("\u272B")) {
            return "\u272B"; // Prestige I - single star (✫)
        } else if (level.equals("\u272B\u272B")) {
            return "\u272B\u272B"; // Prestige II - double star (✫✫)
        }
        
        // Handle prestige levels with ✫ symbols (if they come through correctly)
        if (level.contains("\u272B")) {
            int starCount = level.length() - level.replace("\u272B", "").length();
            if (starCount == 1) {
                return "\u272B"; // Prestige I - single star (✫)
            } else if (starCount == 2) {
                return "\u272B\u272B"; // Prestige II - double star (✫✫)
            }
        }
        
        // Handle Roman numerals (I-X)
        if (level.matches("^[IVX]+$")) {
            return level; // Already in correct format
        }
        
        // Handle numeric levels (1-10)
        try {
            int numericLevel = Integer.parseInt(level);
            if (numericLevel >= 1 && numericLevel <= 10) {
                return romanNumeral(numericLevel);
            }
        } catch (NumberFormatException e) {
            // Not a number, continue checking
        }
        
        // If we can't parse it, return null (invalid level)
        return null;
    }
    
    /**
     * Helper method to count occurrences of a string in another string
     */
    private static int countOccurrencesInString(String text, String search) {
        if (text == null || search == null || text.isEmpty() || search.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }
    
    /**
     * Checks if a kit name contains a level indicator (Roman numerals, stars, or question marks)
     */
    private static boolean hasLevelIndicator(String kitName) {
        if (kitName == null || kitName.isEmpty()) {
            return false;
        }
        // Check for Roman numerals (I-X)
        if (kitName.matches(".*\\b([IVX]+)\\b.*")) {
            return true;
        }
        // Check for star symbols
        if (kitName.contains("\u272B") || kitName.contains("\u2B50") || 
            kitName.contains("\u2605") || kitName.contains("\u2726") || 
            kitName.contains("\u2606") || kitName.contains("\u2B51")) {
            return true;
        }
        // Check for question marks
        if (kitName.contains("?")) {
            return true;
        }
        // Check for corrupted Unicode star symbols (non-ASCII, non-alphanumeric characters)
        for (int i = 0; i < kitName.length(); i++) {
            char c = kitName.charAt(i);
            if (c > 127 && c != ' ' && !Character.isLetterOrDigit(c)) {
                return true; // Found unusual Unicode character (likely corrupted star)
            }
        }
        return false;
    }
    
    /**
     * Converts numeric level to Roman numeral
     */
    private static String romanNumeral(int number) {
        if (number >= 1 && number <= 10) {
            String[] romanNumerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
            return romanNumerals[number - 1];
        }
        return String.valueOf(number);
    }
    
    /**
     * Processes player gear to detect kit items
     */
    public static void processPlayerGear(String playerName, String[] gearItems) {
        if (!shouldCollectEnemyKits() || !kitDetectionActive) {
            return;
        }
        if (isLocalClientAnyKnownIgn(playerName)) {
            return;
        }

        // Skip if we already detected a kit for this player
        if (enemyPlayerKits.containsKey(playerName)) {
            return;
        }
        
        // Skip if this player already has a teammate kit displayed
        // This prevents duplicate kit display when 2-minute message appears
        if (playerKits.containsKey(playerName)) {
            // System.out.println("[KillCounter] Skipping enemy kit detection for " + playerName + " - already has teammate kit: " + playerKits.get(playerName));
            return;
        }
        
        // Check each gear item for kit patterns
        for (String item : gearItems) {
            if (item != null && !item.isEmpty()) {
                String detectedKit = detectKitFromItemName(item);
                if (detectedKit != null) {
                    recordDetectedEnemyKit(playerName, null, detectedKit);
                    // System.out.println("[KillCounter] Detected enemy kit for " + playerName + ": " + detectedKit + " from item: " + item);
                    break; // Stop after first kit detection
                }
            }
        }
    }
    
    /**
     * Scans for kit items over several client ticks; equipment NBT often lands slightly after the chat line.
     * World access only on the client thread; pacing uses a worker thread + {@link Minecraft#addScheduledTask}.
     */
    private static void startBriefKitScanning() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            kitDetectionActive = false;
            return;
        }
        mc.addScheduledTask(() -> {
            if (!shouldCollectEnemyKits()) {
                return;
            }
            kitDetectionActive = true;
            scanPlayersForKits();
        });
        startDaemonThread("gomod-enemy-kit-window", () -> {
            try {
                int[] sleepMsBetweenPasses = {350, 350, 500, 800, 400};
                for (int i = 0; i < sleepMsBetweenPasses.length - 1; i++) {
                    Thread.sleep(sleepMsBetweenPasses[i]);
                    Minecraft mcc = Minecraft.getMinecraft();
                    if (mcc != null) {
                        mcc.addScheduledTask(() -> {
                            if (!shouldCollectEnemyKits() || !kitDetectionActive) {
                                return;
                            }
                            scanPlayersForKits();
                        });
                    }
                }
                Thread.sleep(sleepMsBetweenPasses[sleepMsBetweenPasses.length - 1]);
                Minecraft mcc2 = Minecraft.getMinecraft();
                if (mcc2 != null) {
                    mcc2.addScheduledTask(() -> kitDetectionActive = false);
                } else {
                    kitDetectionActive = false;
                }
            } catch (InterruptedException e) {
                System.err.println("[KillCounter] Brief kit scanning interrupted: " + e.getMessage());
                kitDetectionActive = false;
            }
        });
    }
    
    /**
     * Scans all nearby players for kit items when detection is active
     */
    private static void scanPlayersForKits() {
        // System.out.println("[KillCounter] DEBUG: scanPlayersForKits called - showEnemyKits: " + isShowEnemyKits() + ", kitDetectionActive: " + kitDetectionActive);
        
        if (!shouldCollectEnemyKits() || !kitDetectionActive) {
            // System.out.println("[KillCounter] DEBUG: Skipping scan - showEnemyKits: " + isShowEnemyKits() + ", kitDetectionActive: " + kitDetectionActive);
            return;
        }
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
                return;
            }
            
            int totalPlayers = 0;
            int scannedPlayers = 0;
            int foundKits = 0;
            
            // Scan players within maximum visibility range (200 blocks)
            for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
                totalPlayers++;
                if (player == mc.thePlayer) {
                    continue; // Skip the current player
                }

                // Calculate distance to player
                double distanceSq = mc.thePlayer.getDistanceSqToEntity(player);
                double distance = Math.sqrt(distanceSq);
                
                // Only scan players within 200 blocks (maximum reasonable visibility for equipment)
                if (distance > 200.0) {
                    continue; // Skip players too far away
                }
                
                scannedPlayers++;
                String playerName = player.getName();
                if (isLocalClientAnyKnownIgn(playerName)) {
                    continue;
                }

                // Skip if we already detected a kit for this player
                if (enemyPlayerKits.containsKey(playerName)) {
                    continue;
                }
                
                // Skip if this player already has a teammate kit displayed
                // This prevents duplicate kit display when scanning for enemy kits
                if (playerKits.containsKey(playerName)) {
                    continue;
                }
                
                // Collect gear items and detect kit
                boolean kitFound = false;
                
                // Check held item
                try {
                    if (player.getCurrentEquippedItem() != null) {
                        net.minecraft.item.ItemStack itemStack = player.getCurrentEquippedItem();
                        String rawItemName = getOriginalItemName(itemStack);
                        if (rawItemName != null) {
                            String itemName = stripColors(rawItemName);
                            String detectedKit = detectKitFromItemName(itemName);
                            if (detectedKit != null) {
                                recordDetectedEnemyKit(playerName, player.getUniqueID(), detectedKit);
                                foundKits++;
                                kitFound = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Silently handle
                }
                
                // Check armor slots (only if kit not found yet)
                if (!kitFound) {
                    String[] armorNames = {"Boots", "Leggings", "Chestplate", "Helmet"};
                    for (int i = 0; i < 4; i++) {
                        try {
                            if (player.inventory != null && player.inventory.armorInventory != null && player.inventory.armorInventory[i] != null) {
                                net.minecraft.item.ItemStack itemStack = player.inventory.armorInventory[i];
                                String rawItemName = getOriginalItemName(itemStack);
                                if (rawItemName != null) {
                                    String itemName = stripColors(rawItemName);
                                    String detectedKit = detectKitFromItemName(itemName);
                                    if (detectedKit != null && !kitFound) {
                                        recordDetectedEnemyKit(playerName, player.getUniqueID(), detectedKit);
                                        foundKits++;
                                        kitFound = true;
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Silently handle
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[KillCounter] Error scanning players for kits: " + e.getMessage());
        }
    }

    /**
     * Stores a detected enemy kit and validates Prestige I ({@code ✫}) detections against the
     * Hypixel API cache (with a rate-limited background API fallback when uncached) to catch
     * Hypixel's Prestige I -> Prestige II stale item-name bug.
     */
    private static void recordDetectedEnemyKit(String playerName, UUID entityUuid, String detectedKit) {
        if (playerName == null || playerName.isEmpty() || detectedKit == null || detectedKit.isEmpty()) {
            return;
        }
        UUID resolvedUuid = entityUuid != null ? entityUuid : lookupTabProfileUuid(playerName);
        String validatedKit = EnemyKitPrestigeValidator.validateAndUpgradeOrSchedule(
                playerName,
                resolveTabLookupName(playerName),
                getTabVictimIdentityAliases(playerName),
                resolvedUuid,
                detectedKit,
                (targetPlayer, originalKit, upgradedKit) -> {
                    if (enemyPlayerKits.replace(targetPlayer, originalKit, upgradedKit)) {
                        markTabCompositionDirty();
                        return;
                    }
                    for (Map.Entry<String, String> entry : enemyPlayerKits.entrySet()) {
                        if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(targetPlayer)
                                && originalKit.equalsIgnoreCase(entry.getValue())) {
                            if (enemyPlayerKits.replace(entry.getKey(), entry.getValue(), upgradedKit)) {
                                markTabCompositionDirty();
                            }
                            break;
                        }
                    }
                }
        );
        enemyPlayerKits.put(playerName, validatedKit);
        markTabCompositionDirty();
    }

    private static UUID lookupTabProfileUuid(String playerName) {
        if (playerName == null || playerName.isEmpty()) return null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) return null;
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info != null && info.getGameProfile() != null
                        && playerName.equalsIgnoreCase(info.getGameProfile().getName())) {
                    return info.getGameProfile().getId();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    
    /**
     * Display name used for kit regex matching. Hypixel puts {@code Kit's … (tier)} on items via NBT {@code display.Name};
     * that string is returned by {@link net.minecraft.item.ItemStack#getDisplayName()}, while
     * {@link net.minecraft.item.Item#getItemStackDisplayName(net.minecraft.item.ItemStack)} is usually the vanilla
     * material name (e.g. {@code Iron Sword}) and does not include the kit prefix.
     */
    private static String getOriginalItemName(net.minecraft.item.ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        
        try {
            String display = itemStack.getDisplayName();
            if (display != null && !display.isEmpty()) {
                return display;
            }
            String vanilla = itemStack.getItem().getItemStackDisplayName(itemStack);
            if (vanilla != null && !vanilla.isEmpty()) {
                return vanilla;
            }
            return null;
        } catch (Exception e) {
            System.err.println("[KillCounter] Error getting original item name: " + e.getMessage());
            try {
                return itemStack.getDisplayName();
            } catch (Exception e2) {
                return null;
            }
        }
    }
    
    /**
     * Strips Minecraft formatting codes (section sign and &) from text
     * This prevents issues with formatting codes in item names
     */
    private static String stripColors(String text) {
        if (text == null) return "";
        return me.napim.gomod.util.MinecraftFormatting.stripSectionAndAmpersandCodes(text);
    }

    /** TAB compose: stats strings from API sometimes include a leading space — avoid a double gap after the name. */
    private static String trimLeadingSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return s.substring(i);
    }

    /**
     * Trim leading ASCII spaces even when the segment starts with formatting codes such as {@code §4 }.
     */
    private static String trimLeadingDecoratedSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder prefix = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                prefix.append(c).append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == ' ' || c == '	') {
                i++;
                continue;
            }
            break;
        }
        return prefix.append(s.substring(i)).toString();
    }
    private static String trimTrailingAsciiSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c != ' ' && c != '\t') {
                break;
            }
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    private static String trimTrailingUnicodeWhitespace(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /** Hypixel team TAB rows often pad with long ASCII space runs before brackets. */
    private static String collapseHypixelTabPaddingSpaces(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.replaceAll(" {2,}", " ");
    }

    // Pre-compiled patterns for stripTrailing* methods (called hundreds of times per compose cycle).
    private static final Pattern STRIP_PING_SUFFIX = Pattern.compile("\\s+§[0-9a-fk-or]*\\d{1,4}ms(?:§[0-9a-fk-or]*)?\\s*$");
    private static final Pattern STRIP_GUILD_TAG_SUFFIX = Pattern.compile(
            "\\s*(?:§.)*\\[(?!\\d+w\\])(?![\\d.]+wl\\])(?![\\d.]+wl/\\d+w\\])(?:(?:§.)|[^\\]]){1,32}\\](?:§.)*\\s*$");
    private static final Pattern STRIP_KIT_SUFFIX = Pattern.compile(
            "\\s*(?:§.)*\\((?![A-Za-z0-9_]{3,16}\\))[^)]{2,48}\\)(?:§.)*\\s*$");
    private static final Pattern STRIP_STATS_WL_SUFFIX = Pattern.compile("\\s*(?:§.)*\\[[^\\]]*wl/[^\\]]+\\](?:§.)*\\s*$");
    private static final Pattern STRIP_STATS_W_SUFFIX = Pattern.compile("(?:§.)*\\[[0-9]+w\\](?:§.)*\\s*$");
    private static final Pattern STRIP_STATS_WLONLY_SUFFIX = Pattern.compile("(?:§.)*\\[[\\d.]+wl\\](?:§.)*\\s*$");
    private static final Pattern STRIP_STATS_KDONLY_SUFFIX = Pattern.compile("(?:§.)*\\[[\\d.]+kd\\](?:§.)*\\s*$");
    private static final Pattern STRIP_STATS_COMBINED_SUFFIX = Pattern.compile(
            "\\s*(?:§.)*\\[[^\\]]*(?:\\d+\\.?\\d*kd|[\\d.]+wl|\\d+w)[^\\]]*\\](?:§.)*\\s*$");
    private static final Pattern STRIP_KILL_COUNTER_SUFFIX = Pattern.compile("(?:§.)*\\[[0-9]{1,4}\\](?:§.)*\\s*$");
    private static final Pattern STRIP_RESOLVED_ALIAS_SUFFIX = Pattern.compile(
            "\\s*(?:§.)*\\(\\s*(?:§.)*[A-Za-z0-9_]{3,16}\\s*(?:§.)*\\)(?:§.)*\\s*$");
    private static final Pattern STRIP_LEGACY_ALIAS_SUFFIX = Pattern.compile(
            "\\s*(?:§.)*=\\s*(?:§.)*[A-Za-z0-9_]{3,16}(?:§.)*\\s*$");

    private static String stripTrailingPingSuffixFromTabLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return STRIP_PING_SUFFIX.matcher(s).replaceAll("").trim();
    }

    private static String stripTrailingGuildTagSuffixFromTabLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String cur = s;
        for (int k = 0; k < 12; k++) {
            String n = STRIP_GUILD_TAG_SUFFIX.matcher(cur).replaceAll("").trim();
            if (n.equals(cur)) {
                break;
            }
            cur = n;
        }
        return cur;
    }

    /** Remove the source player's trailing guild tag before composing a manual disguise display line. */
    public static String stripGuildTagFromDisguiseDisplay(String formatted) {
        return stripTrailingGuildTagSuffixFromTabLine(formatted);
    }

    /**
     * Teammate/enemy kit suffix {@code (Kit I)} — must not match nick-resolution {@code (realname)} (plain IGN only).
     */
    private static String stripTrailingKitSuffixFromTabLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return STRIP_KIT_SUFFIX.matcher(s).replaceAll("").trim();
    }

    /**
     * Strip TAB stats tails. Must remove the whole decorated chunk (space + § codes + bracket), not only from {@code [},
     * or {@code §r§4} prefixes from stats suffix formatting stay on the base and each refresh adds another space + stats.
     */
    private static String stripTrailingStatsSuffixFromTabLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String cur = s;
        for (int pass = 0; pass < 12; pass++) {
            String before = cur;
            cur = STRIP_STATS_COMBINED_SUFFIX.matcher(cur).replaceAll("").trim();
            cur = STRIP_STATS_WL_SUFFIX.matcher(cur).replaceAll("").trim();
            cur = STRIP_STATS_W_SUFFIX.matcher(cur).replaceAll("").trim();
            cur = STRIP_STATS_WLONLY_SUFFIX.matcher(cur).replaceAll("").trim();
            cur = STRIP_STATS_KDONLY_SUFFIX.matcher(cur).replaceAll("").trim();
            if (cur.equals(before)) {
                break;
            }
        }
        return cur;
    }

    private static String stripTrailingKillCounterSuffixFromTabLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String cur = s;
        for (int k = 0; k < 12; k++) {
            String next = STRIP_KILL_COUNTER_SUFFIX.matcher(cur).replaceAll("").trim();
            if (next.equals(cur)) {
                break;
            }
            cur = next;
        }
        return cur;
    }

    /** Trailing {@code (realIgn)} from duplicate-nick resolution (may include §l/§o etc.). */
    private static String stripTrailingResolvedAliasSuffixFromTabLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String cur = s;
        for (int k = 0; k < 8; k++) {
            String n = STRIP_RESOLVED_ALIAS_SUFFIX.matcher(cur).replaceAll("").trim();
            if (n.equals(cur)) {
                break;
            }
            cur = n;
        }
        return cur;
    }

    private static String stripTrailingLegacyAliasSuffixFromTabLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return STRIP_LEGACY_ALIAS_SUFFIX.matcher(s).replaceAll("").trim();
    }

    private static String stripTrailingParentheticalRealIgn(String formatted, String ign) {
        if (formatted == null || ign == null || ign.length() < 3) {
            return formatted;
        }
        String cur = formatted;
        for (int k = 0; k < 8; k++) {
            String n = cur.replaceAll(
                    "(?i)(?:\\s)*(?:§.)*\\(\\s*(?:§.)*" + Pattern.quote(ign) + "(?:§.)*\\s*\\)(?:§.)*\\s*$",
                    "").trim();
            if (n.equals(cur)) {
                break;
            }
            cur = n;
        }
        return cur;
    }

    private static boolean plainTextContainsParentheticalIgn(String formatted, String ign) {
        if (formatted == null || ign == null || ign.isEmpty()) {
            return false;
        }
        String p = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (p == null) {
            return false;
        }
        return p.toLowerCase(Locale.ROOT).contains("(" + ign.toLowerCase(Locale.ROOT) + ")");
    }

    /**
     * After Hypixel's leading {@code […]} tags (rank, {@code [DIST n]}, {@code [TEAM]}), the next color is often
     * neutral gray ({@code §7}/{@code §8}) so the IGN matches spectator styling. If the visible text after that
     * still begins with {@code baseName}, replace that gray code with {@code §f} so Blitz TAB recolor (§c enemies,
     * §a teammates) and kill-effect tint can apply. Also used before appending gold {@code (realIgn)} for nick rows.
     */
    private static String stripNeutralGrayBeforeIgnAfterLeadingBrackets(String formatted, String baseName) {
        if (formatted == null || formatted.isEmpty() || baseName == null) {
            return formatted;
        }
        String ign = baseName.trim();
        if (ign.isEmpty()) {
            return formatted;
        }
        int leadEnd = endOfAllLeadingFormattedBrackets(formatted);
        int p = skipFormattingWsAndCodes(leadEnd, formatted);
        if (p >= formatted.length()) {
            return formatted;
        }
        String plainHead = EnumChatFormatting.getTextWithoutFormattingCodes(formatted.substring(p));
        if (plainHead == null) {
            return formatted;
        }
        plainHead = plainHead.trim();
        if (!plainHead.toLowerCase(Locale.ROOT).startsWith(ign.toLowerCase(Locale.ROOT))) {
            return formatted;
        }
        char activeColor = effectiveTextColorBefore(formatted, p);
        if (activeColor != '7' && activeColor != '8') {
            return formatted;
        }
        int lastColorStart = lastColorCodeStartBetween(formatted, leadEnd, p);
        if (lastColorStart >= 0) {
            return formatted.substring(0, lastColorStart + 1) + 'f' + formatted.substring(lastColorStart + 2);
        }
        return formatted.substring(0, p) + EnumChatFormatting.WHITE + formatted.substring(p);
    }

    /**
     * Lunar/Weave can expose eliminated Blitz rows as neutral gray while the local player is spectating.
     * Keep using gray as a dead-row signal for sorting, but render tracked dead rows with the normal spectator blue.
     */
    private static String forceBlitzDeadBlueBeforeIgnAfterLeadingBrackets(String formatted, String baseName) {
        if (formatted == null || formatted.isEmpty() || baseName == null) {
            return formatted;
        }
        String ign = baseName.trim();
        if (ign.isEmpty()) {
            return formatted;
        }
        int leadEnd = endOfAllLeadingFormattedBrackets(formatted);
        int p = skipFormattingWsAndCodes(leadEnd, formatted);
        if (p >= formatted.length()) {
            return formatted;
        }
        String plainHead = EnumChatFormatting.getTextWithoutFormattingCodes(formatted.substring(p));
        if (plainHead == null) {
            return formatted;
        }
        plainHead = plainHead.trim();
        if (!plainHead.toLowerCase(Locale.ROOT).startsWith(ign.toLowerCase(Locale.ROOT))) {
            return formatted;
        }
        char activeColor = effectiveTextColorBefore(formatted, p);
        if (activeColor == '9' || activeColor == '1') {
            return formatted;
        }
        int lastColorStart = lastColorCodeStartBetween(formatted, leadEnd, p);
        if (lastColorStart >= 0) {
            return formatted.substring(0, lastColorStart + 1) + '9' + formatted.substring(lastColorStart + 2);
        }
        return formatted.substring(0, p) + EnumChatFormatting.BLUE + formatted.substring(p);
    }

    private static String ensureTabNickNotGrayBeforeDuplicateRealParen(String formatted, String baseName) {
        return stripNeutralGrayBeforeIgnAfterLeadingBrackets(formatted, baseName);
    }

    /**
     * Hypixel lobby rank tags ({@code [VIP+]}) on a nicked TAB row reflect the nick account, not the real player.
     * Removes only rank-like leading {@code […]} blocks; keeps {@code [DIST …]}, {@code [TEAM]}, etc.
     */
    private static String stripLeadingHypixelRankBracketsKeepGameTags(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        int n = formatted.length();
        int firstBracket = -1;
        for (int i = 0; i < n; ) {
            char c = formatted.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (i < n - 1 && c == '\u00A7') {
                i += 2;
                continue;
            }
            firstBracket = i;
            break;
        }
        if (firstBracket < 0 || firstBracket >= n || formatted.charAt(firstBracket) != '[') {
            return formatted;
        }
        StringBuilder kept = new StringBuilder(formatted.substring(0, firstBracket));
        int p = firstBracket;
        while (p < n) {
            p = skipFormattingWsAndCodes(p, formatted);
            if (p >= n || formatted.charAt(p) != '[') {
                kept.append(formatted.substring(p));
                break;
            }
            int open = p;
            int after = indexAfterMatchingFormattedBracket(formatted, open);
            if (after < 0) {
                kept.append(formatted.substring(open));
                break;
            }
            String chunk = formatted.substring(open, after);
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(chunk);
            if (isHypixelLobbyRankBracketPlain(plain)) {
                p = after;
                continue;
            }
            kept.append(formatted, open, after);
            p = after;
        }
        return kept.toString();
    }

    private static boolean isHypixelLobbyRankBracketPlain(String plain) {
        if (plain == null) {
            return false;
        }
        String t = plain.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return false;
        }
        if (t.contains("DIST") || t.contains("TEAM")) {
            return false;
        }
        if (t.matches(".*\\d+\\s*W.*")) {
            return false;
        }
        return t.startsWith("VIP") || t.startsWith("MVP") || t.startsWith("YOUTUBE")
                || t.equals("HELPER") || t.equals("MOD") || t.equals("ADMIN") || t.equals("OWNER")
                || t.equals("GM") || t.equals("MOJANG") || t.equals("EVENTS") || t.equals("INNIT")
                || t.startsWith("PIG");
    }

    private static int lastColorCodeStartBetween(String formatted, int start, int endExclusive) {
        if (formatted == null || formatted.isEmpty()) {
            return -1;
        }
        int startIndex = Math.max(0, start);
        int endIndex = Math.min(endExclusive, formatted.length());
        int last = -1;
        for (int i = startIndex; i < endIndex - 1; i++) {
            if (formatted.charAt(i) != '\u00A7') {
                continue;
            }
            char code = Character.toLowerCase(formatted.charAt(i + 1));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                last = i;
            } else if (code == 'r') {
                last = -1;
            }
            i++;
        }
        return last;
    }

    private static boolean plainTextContainsBracketedGuild(String formatted, String tag) {
        if (formatted == null || tag == null || tag.isEmpty()) {
            return false;
        }
        String p = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        String plainTag = EnumChatFormatting.getTextWithoutFormattingCodes(tag);
        if (p == null || plainTag == null) {
            return false;
        }
        plainTag = plainTag.trim();
        if (plainTag.isEmpty()) {
            return false;
        }
        return p.toLowerCase(Locale.ROOT).contains("[" + plainTag.toLowerCase(Locale.ROOT) + "]");
    }

    private static boolean plainTabAlreadyShowsStatsChunk(String plain) {
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        if (plain.contains("wl/")) {
            return true;
        }
        if (TAB_LINE_HAS_STATS_SUFFIX.matcher(plain).find()) {
            return true;
        }
        if (TAB_LINE_HAS_WINS_SUFFIX.matcher(plain).find()) {
            return true;
        }
        return TAB_LINE_HAS_WL_ONLY_SUFFIX.matcher(plain).find();
    }

    private static String normalizeTabDisplayBase(String s) {
        if (s == null) {
            return "";
        }
        String t = collapseHypixelTabPaddingSpaces(s);
        t = trimTrailingAsciiSpaces(trimTrailingUnicodeWhitespace(t));
        return t;
    }

    /** One ASCII space between TAB segments; trims duplicate gaps from trailing name / leading stats. */
    private static void appendTabPart(StringBuilder sb, String segment) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        String seg = trimLeadingDecoratedSpaces(trimTrailingAsciiSpaces(segment));
        if (seg.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(seg);
    }

    private static int indexOfIgnoreCaseVisible(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return -1;
        }
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    /** Formatted-string index of the character that starts visible index {@code visibleIndex}. */
    private static int formattedIndexForVisibleIndex(String formatted, int visibleIndex) {
        if (formatted == null || visibleIndex < 0) {
            return 0;
        }
        int v = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) == '\u00A7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            if (v == visibleIndex) {
                return i;
            }
            v++;
        }
        return formatted.length();
    }

    /**
     * Last color code (0-9, a-f) active at {@code formatIndex}, after RESET. Hypixel dead/spectator TAB uses blue (9);
     * when {@link #deadPlayers} misses a row, still respect server coloring for Colored name mode.
     */
    private static char effectiveTextColorBefore(String formatted, int formatIndex) {
        char color = 0;
        if (formatted == null) {
            return color;
        }
        int end = Math.min(formatIndex, formatted.length());
        for (int i = 0; i < end; i++) {
            if (formatted.charAt(i) == '\u00A7' && i + 1 < formatted.length()) {
                char c = Character.toLowerCase(formatted.charAt(i + 1));
                i++;
                if (c == 'r') {
                    color = 0;
                } else if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                    color = c;
                }
            }
        }
        return color;
    }

    private static boolean isBlitzTabServerDeadBlue(String formattedName, String baseName) {
        if (formattedName == null || baseName == null || baseName.isEmpty()) {
            return false;
        }
        String visible = stripColors(formattedName);
        int nameStart = indexOfIgnoreCaseVisible(visible, baseName);
        if (nameStart < 0) {
            return false;
        }
        int fmtAtName = formattedIndexForVisibleIndex(formattedName, nameStart);
        char col = effectiveTextColorBefore(formattedName, fmtAtName);
        // §9/§1 = Hypixel spectator-style blue; §7/§8 = some Blitz TAB rows for eliminated before full spec update.
        return col == '9' || col == '1' || col == '7' || col == '8';
    }

    /**
     * Hypixel spectator TAB for eliminated players (dark/aqua blue). §7/§8 are often still-alive rows while the local
     * player is spectating, so they must not drive /tabkillers bold-only logic — see {@link #shouldBoldTabKillersVictim}.
     */
    private static boolean isBlitzTabHypixelSpectatorBlue(String formattedName, String baseName) {
        if (formattedName == null || baseName == null || baseName.isEmpty()) {
            return false;
        }
        String visible = stripColors(formattedName);
        int nameStart = indexOfIgnoreCaseVisible(visible, baseName);
        if (nameStart < 0) {
            return false;
        }
        int fmtAtName = formattedIndexForVisibleIndex(formattedName, nameStart);
        char col = effectiveTextColorBefore(formattedName, fmtAtName);
        return col == '9' || col == '1';
    }

    
    /**
     * Strips Minecraft formatting codes (§ and &) from text
     * This prevents issues with formatting codes in item names
     */
    private static String stripColorsBroken(String text) {
        if (text == null) return "";
        
        // Remove both § and & formatting codes
        // The pattern is § or & followed by one of the Minecraft format codes (0-9, a-f, k-o, r)
        return text.replaceAll("(?i)[\\u00A7&][0-9a-fk-or]", "");
    }
    
    /**
     * Test method to manually check if game has ended
     */
    public static void testGameEndDetection() {
        // System.out.println("[KillCounter] Testing game end detection...");
        boolean gameEnded = isGameEnded();
        // System.out.println("[KillCounter] Game end detection result: " + gameEnded);
        
        if (gameEnded) {
            // System.out.println("[KillCounter] Game appears to have ended - removing kill counters from tab list");
            resetAllTabNames();
        } else {
            // System.out.println("[KillCounter] Game appears to still be active - keeping kill counters");
        }
    }

    /**
     * Test method to manually test kit detection functionality
     */
    public static void testKitDetection() {
        // System.out.println("[KillCounter] Testing kit detection functionality...");
        
        // Test kit detection with sample items
        String[] testItems = {
            "Guardian's Stone Axe (VI)",
            "Meatmaster's Iron Sword (?)", // Prestige I (shows as ? in logs) -> P1
            "Archer's Bow (??)", // Prestige II (shows as ?? in logs) -> P2
            "Donkeytamer's Leather Helmet (X)",
            "Archer's Bow (\u272B)", // Prestige I -> P1
            "Knight's Iron Sword (\u272B\u272B)", // Prestige II -> P2
            "Knight's Iron Sword (VII)",
            "Regular Iron Sword", // Should not match
            "Baker's Bread (III)"
        };
        
        for (String item : testItems) {
            String detectedKit = detectKitFromItemName(item);
            if (detectedKit != null) {
                // System.out.println("[KillCounter] Test: '" + item + "' -> Detected kit: " + detectedKit);
            } else {
                // System.out.println("[KillCounter] Test: '" + item + "' -> No kit detected");
            }
        }
        
        // Test with sample player gear
        // System.out.println("[KillCounter] Testing player gear processing...");
        String testPlayer = "TestPlayer";
        processPlayerGear(testPlayer, new String[]{"Guardian's Stone Axe (VI)", "Regular Leather Boots"});
        
        if (enemyPlayerKits.containsKey(testPlayer)) {
            // System.out.println("[KillCounter] Test: Successfully detected kit for " + testPlayer + ": " + enemyPlayerKits.get(testPlayer));
        } else {
            // System.out.println("[KillCounter] Test: Failed to detect kit for " + testPlayer);
        }
        
        // Clean up test data
        enemyPlayerKits.remove(testPlayer);
        
        // Test manual scanning of real players
        // System.out.println("[KillCounter] Testing manual scan of real players...");
        scanPlayersForKits();
        // System.out.println("[KillCounter] Manual scan completed.");
        
        // Test the regex pattern with more examples
        // System.out.println("[KillCounter] Testing regex pattern with additional examples...");
        String[] additionalTests = {
            "Guardian's Leather Helmet (VI)",
            "Meatmaster's Iron Sword (?)", // Prestige I (shows as ? in logs) -> P1
            "Archer's Bow (??)", // Prestige II (shows as ?? in logs) -> P2
            "Donkeytamer's Diamond Sword (X)",
            "Archer's Bow (\u272B)", // Prestige I -> P1
            "Phoenix's Gold Helmet (\u272B\u272B)", // Prestige II -> P2
            "Knight's Iron Chestplate (VII)",
            "Knight's Leather Boots (I)",
            "Knight's Iron Helmet (I)",
            "Baker's Bread (III)",
            "Regular Iron Sword",
            "Diamond Helmet",
            "Guardian's Stone Axe (V)",
            "Florist's Leather Helmet (X)"
        };
        
        for (String testItem : additionalTests) {
            String detectedKit = detectKitFromItemName(testItem);
            // System.out.println("[KillCounter] Test: '" + testItem + "' -> " + (detectedKit != null ? "Detected: " + detectedKit : "No kit detected"));
        }
        
        // Test the actual items from the logs
        // System.out.println("[KillCounter] Testing actual items from logs...");
        String[] realItems = {
            "Armorer's Leather Boots (?)",
            "Troll's Leather Boots (??)",
            "Wolftamer's Diamond Boots (?)"
        };
        
        for (String realItem : realItems) {
            String detectedKit = detectKitFromItemName(realItem);
            // System.out.println("[KillCounter] Real item test: '" + realItem + "' -> " + (detectedKit != null ? "Detected: " + detectedKit : "No kit detected"));
        }
    }
    
    /**
     * Pasteable multi-line report: settings, {@link #enemyPlayerKits} cache, and per-player sample item display names
     * (held + armor) with regex kit parse result. Run in-game near others; use {@code /startkc kitdebug}.
     */
    public static java.util.List<String> buildEnemyKitDebugLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String g = EnumChatFormatting.GRAY.toString();
        String y = EnumChatFormatting.YELLOW.toString();
        String r = EnumChatFormatting.RED.toString();
        String a = EnumChatFormatting.GREEN.toString();
        String w = EnumChatFormatting.WHITE.toString();
        lines.add(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Enemy kit debug " + g + "(copy this block)");
        lines.add(g + "showEnemyKits=" + isShowEnemyKits()
                + " configKillCounter=" + ConfigManager.isKillCounterEnabled()
                + " runtimeEnabled=" + enabled
                + " gameHasEnded=" + gameHasEnded
                + " kitDetectionActive=" + kitDetectionActive);
        lines.add(g + "enemyPlayerKits cache size=" + enemyPlayerKits.size());
        lines.add(g + "Teleporter kit scan: " + ReplayPlayerSelectorDisplay.describeLastTeleporterKitScan());
        if (enemyPlayerKits.isEmpty()) {
            lines.add(g + "  (no cached enemy kits)");
        } else {
            for (java.util.Map.Entry<String, String> e : enemyPlayerKits.entrySet()) {
                lines.add(y + "  " + e.getKey() + g + " -> " + w + e.getValue());
            }
        }
        lines.add(g + "Auto-scan arms ~5s after Blitz Star \"~2 min\" chat; manual: /startkc scankits");
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            lines.add(r + "No client world — join Hypixel / a game first.");
            return lines;
        }
        int listed = 0;
        final int maxPlayers = 16;
        for (net.minecraft.entity.player.EntityPlayer p : mc.theWorld.playerEntities) {
            if (p == mc.thePlayer) {
                continue;
            }
            double dSq = mc.thePlayer.getDistanceSqToEntity(p);
            if (dSq > 200.0 * 200.0) {
                continue;
            }
            listed++;
            if (listed > maxPlayers) {
                lines.add(g + "... more players in range (cap " + maxPlayers + ")");
                break;
            }
            String pname = p.getName();
            StringBuilder row = new StringBuilder();
            row.append(w).append(pname).append(g).append(" dist=").append((int) Math.sqrt(dSq));
            try {
                String bestPlain = null;
                String bestKit = null;
                String source = "";
                net.minecraft.item.ItemStack held = p.getCurrentEquippedItem();
                if (held != null) {
                    String raw = getOriginalItemName(held);
                    if (raw != null) {
                        String plain = stripColors(raw);
                        String kit = detectKitFromItemName(plain);
                        bestPlain = plain;
                        bestKit = kit;
                        source = "held";
                    }
                }
                if (bestKit == null && p.inventory != null && p.inventory.armorInventory != null) {
                    String[] armorNames = {"boots", "legs", "chest", "head"};
                    for (int i = 0; i < 4; i++) {
                        net.minecraft.item.ItemStack st = p.inventory.armorInventory[i];
                        if (st == null) {
                            continue;
                        }
                        String raw = getOriginalItemName(st);
                        if (raw == null) {
                            continue;
                        }
                        String plain = stripColors(raw);
                        String kit = detectKitFromItemName(plain);
                        if (bestPlain == null) {
                            bestPlain = plain;
                            source = armorNames[i];
                        }
                        if (kit != null) {
                            bestPlain = plain;
                            bestKit = kit;
                            source = armorNames[i];
                            break;
                        }
                    }
                }
                row.append(g).append(" | ").append(source.isEmpty() ? "no item" : source).append(": ");
                if (bestPlain == null || bestPlain.isEmpty()) {
                    row.append(r).append("(empty display name)");
                } else {
                    String show = bestPlain.length() > 56 ? bestPlain.substring(0, 56) + "…" : bestPlain;
                    row.append(w).append(show);
                    if (bestKit != null) {
                        row.append(a).append(" => ").append(bestKit);
                    } else {
                        row.append(g).append(" => no kit regex match");
                    }
                }
            } catch (Throwable t) {
                row.append(r).append(" err ").append(t.getClass().getSimpleName());
            }
            lines.add(row.toString());
        }
        if (listed == 0) {
            lines.add(g + "No other players within 200 blocks.");
        }
        return lines;
    }

    /**
     * Manually triggers a scan for kit detection (for testing or manual use)
     */
    public static void manualKitScan() {
        // System.out.println("[KillCounter] Manual kit scan triggered...");
        // System.out.println("[KillCounter] Current settings - showEnemyKits: " + isShowEnemyKits() + ", kitDetectionActive: " + kitDetectionActive);
        
        // Force enable kit detection for manual scanning
        boolean wasActive = kitDetectionActive;
        kitDetectionActive = true;
        
        scanPlayersForKits();
        
        // Restore original state
        kitDetectionActive = wasActive;
        
        // System.out.println("[KillCounter] Manual kit scan completed.");
    }
    
    /**
     * Fetches and caches guild tag for a player from Hypixel API
     */
    public static void fetchGuildTag(String playerName) {
        startGuildTagFetchThread(playerName, false, null);
    }

    /**
     * Like {@link #fetchGuildTag(String)} but uses the tab-list UUID (no dashes), skipping a Mojang profile lookup.
     * Use during {@code /tabstats all} / cages-open bulk scans so dozens of players do not hammer Mojang at once.
     */
    public static void fetchGuildTagWithKnownUuid(String playerName, String uuidNoDash) {
        if (playerName == null || playerName.isEmpty() || uuidNoDash == null || uuidNoDash.isEmpty()) {
            return;
        }
        startGuildTagFetchThread(playerName, false, uuidNoDash);
    }

    /**
     * Clears cached guild data for {@code playerName} (any casing) and queues a Hypixel guild fetch even when
     * "show guild tag in TAB" is off — for {@code /guildtagdebug api}.
     */
    public static void fetchGuildTagForDebug(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return;
        }
        removeGuildTagCacheKeysFor(playerName.trim());
        startGuildTagFetchThread(playerName.trim(), true, null);
    }

    private static void removeGuildTagCacheKeysFor(String playerName) {
        // Keys are now normalized to lowercase at insertion.
        String lower = playerName.toLowerCase(Locale.ROOT);
        playerGuildTags.remove(lower);
        playerGuildTagColors.remove(lower);
    }

    /**
     * @param knownUuidNoDash optional profile UUID without hyphens from TAB; if null/empty, resolves via Mojang
     */
    private static void startGuildTagFetchThread(final String playerName, final boolean bypassShowGuildTagCheck,
            final String knownUuidNoDash) {
        if (!bypassShowGuildTagCheck && !isShowGuildTag()) {
            return;
        }
        if (playerGuildTags.containsKey(playerName.toLowerCase(Locale.ROOT))) {
            return;
        }

        final String flightKey = playerName.toLowerCase(Locale.ROOT);
        if (!guildTagFetchInFlight.add(flightKey)) {
            return;
        }

        GUILD_TAG_EXECUTOR.execute(() -> {
            try {
                long waitTime;
                synchronized (KillCounter.class) {
                    long currentTime = System.currentTimeMillis();
                    long nextAllowed = Math.max(currentTime, lastApiCall + API_CALL_DELAY);
                    waitTime = nextAllowed - currentTime;
                    lastApiCall = nextAllowed;
                }
                if (waitTime > 0L) {
                    Thread.sleep(waitTime);
                }

                String apiKey = me.napim.gomod.features.ApiKeyManager.getApiKey("hypixel");
                if (apiKey == null || apiKey.isEmpty()) {
                    return;
                }
                apiKey = apiKey.trim();
                if (me.napim.gomod.features.GameModeStatusFetcher.shouldSkipKnownBadHypixelRequests(apiKey)) {
                    return;
                }

                String uuid = (knownUuidNoDash != null && !knownUuidNoDash.isEmpty())
                        ? knownUuidNoDash
                        : getPlayerUUID(playerName);
                if (uuid == null || uuid.isEmpty()) {
                    return;
                }

                String guildUrl = "https://api.hypixel.net/guild?key=" + apiKey + "&player=" + uuid;
                String response = makeHttpRequest(guildUrl, apiKey, true);

                if (response != null && response.contains("\"success\":true")) {
                    String guildTag = extractGuildTag(response);
                    String tagColor = extractGuildTagColor(response);

                    String guildKeyLower = playerName.toLowerCase(Locale.ROOT);
                    if (guildTag != null && !guildTag.isEmpty()) {
                        String fixedGuildTag = fixGuildTagUnicode(guildTag);
                        playerGuildTags.put(guildKeyLower, fixedGuildTag);
                        playerGuildTagColors.put(guildKeyLower, getGuildTagColor(tagColor));
                        markTabCompositionDirty();
                    } else {
                        playerGuildTags.put(guildKeyLower, "");
                        playerGuildTagColors.put(guildKeyLower, "");
                    }
                }
            } catch (Exception e) {
                System.err.println("[KillCounter] Error fetching guild tag for " + playerName + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                guildTagFetchInFlight.remove(flightKey);
            }
        });
    }

    /**
     * Chat lines for {@code /guildtagdebug}: cache, denick lookup, and why TAB might skip appending {@code [TAG]}.
     */
    public static java.util.List<String> buildGuildTagDebugLines(String ign) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            lines.add(EnumChatFormatting.RED + "Not connected.");
            return lines;
        }

        invalidateTabFormatCacheOnBlitzPhaseChange(mc);

        String search = (ign == null || ign.trim().isEmpty())
                ? (mc.thePlayer.getGameProfile() != null ? mc.thePlayer.getGameProfile().getName() : "")
                : ign.trim();
        if (search.isEmpty()) {
            lines.add(EnumChatFormatting.RED + "Usage: /guildtagdebug [player]  — optional api: /guildtagdebug <player> api");
            return lines;
        }

        NetworkPlayerInfo info = null;
        for (NetworkPlayerInfo ni : mc.getNetHandler().getPlayerInfoMap()) {
            if (ni != null && RemoteDisguiseManager.getSourceProfile(ni) != null && RemoteDisguiseManager.getSourceProfile(ni).getName() != null
                    && RemoteDisguiseManager.getSourceProfile(ni).getName().equalsIgnoreCase(search)) {
                info = ni;
                break;
            }
        }
        if (info == null) {
            lines.add(EnumChatFormatting.RED + "No TAB row for \"" + search + "\".");
            lines.add(EnumChatFormatting.GRAY + "You can still run " + EnumChatFormatting.YELLOW + "/guildtagdebug " + search + " api"
                    + EnumChatFormatting.GRAY + " to hit the guild endpoint using that IGN.");
            return lines;
        }

        String baseName = RemoteDisguiseManager.getSourceProfile(info).getName();
        String lookup = resolveTabLookupName(baseName);
        String g = EnumChatFormatting.GRAY.toString();
        String y = EnumChatFormatting.YELLOW.toString();

        lines.add(EnumChatFormatting.GOLD + "[gomod guild tag] " + EnumChatFormatting.YELLOW + baseName);

        lines.add(g + "\"Show guild tag\" (killcounter settings): "
                + (isShowGuildTag() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + g + " — when OFF, cache stays empty unless you use " + EnumChatFormatting.WHITE + "api" + g + ".");

        lines.add(g + "Denick /tab lookup: " + EnumChatFormatting.WHITE + lookup
                + (lookup.equalsIgnoreCase(baseName) ? "" : g + " (TAB IGN: " + baseName + ")"));

        String cacheLetters = rawGuildTagLettersFromCache(baseName, lookup);
        String cacheColor = rawGuildTagColorFromCache(baseName, lookup);
        lines.add(g + "API cache (raw letters): "
                + (cacheLetters == null ? y + "(not cached yet)" : (cacheLetters.isEmpty() ? y + "\"\" (empty — no tag in API parse, or not in a guild)" : EnumChatFormatting.WHITE + cacheLetters)));
        lines.add(g + "API cache (color field): " + EnumChatFormatting.WHITE + (cacheColor == null ? "(none)" : cacheColor));

        String resolvedForTab = resolveTabGuildTagForRow(baseName);
        lines.add(g + "Resolved tag for TAB row (empty if setting off / no cache): " + EnumChatFormatting.WHITE
                + (resolvedForTab.isEmpty() ? "(none)" : resolvedForTab));

        String teamLine = net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), baseName);
        String serverBase = resolveTabServerFormattedBase(info, baseName);
        boolean teamBracket = hasServerGuildTag(teamLine);
        boolean serverBracket = hasServerGuildTag(serverBase);
        lines.add(g + "hasServerGuildTag(team formatting): " + (teamBracket ? EnumChatFormatting.GOLD + "YES" : EnumChatFormatting.GREEN + "no")
                + g + " — if YES, gomod assumes Hypixel already ended the line with [something] and skips appending your API tag.");
        lines.add(g + "hasServerGuildTag(resolveTabServerFormattedBase): " + (serverBracket ? EnumChatFormatting.GOLD + "YES" : EnumChatFormatting.GREEN + "no"));

        String elideTeam = elideTabColorWhy(teamLine, 180);
        String elideServer = elideTabColorWhy(serverBase, 180);
        lines.add(g + "Team-formatted base: " + EnumChatFormatting.DARK_GRAY + elideTeam);
        lines.add(g + "Resolved server base: " + EnumChatFormatting.DARK_GRAY + elideServer);

        if (resolvedForTab.isEmpty()) {
            lines.add(y + "No tag to append — enable guild tag + run /tabstats all, or " + EnumChatFormatting.WHITE + "/guildtagdebug " + baseName + " api");
        } else if (teamBracket || serverBracket) {
            lines.add(y + "Tag is cached but TAB line may be skipped: server base matches trailing-[bracket] detection.");
        } else {
            net.minecraft.util.IChatComponent display = info.getDisplayName();
            String composed = display != null ? display.getFormattedText() : "";
            boolean already = plainTextContainsBracketedGuild(composed, resolvedForTab);
            lines.add(g + "Current TAB getDisplayName already contains [tag] text: " + (already ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
        }

        lines.add(EnumChatFormatting.DARK_GRAY + "Hypixel's /guild API uses guild.tag (short tag). tabstats \"guild\" can differ from what TAB shows.");
        return lines;
    }

    private static String rawGuildTagLettersFromCache(String baseName, String lookup) {
        String v = playerGuildTags.get(lookup);
        if (v == null && !lookup.equalsIgnoreCase(baseName)) {
            v = playerGuildTags.get(baseName);
        }
        if (v == null) {
            return null;
        }
        return v;
    }

    private static String rawGuildTagColorFromCache(String baseName, String lookup) {
        String v = playerGuildTagColors.get(lookup);
        if (v == null && !lookup.equalsIgnoreCase(baseName)) {
            v = playerGuildTagColors.get(baseName);
        }
        if (v == null) {
            return null;
        }
        return v;
    }
    
    /**
     * Gets player UUID from Mojang API
     */
    private static String getPlayerUUID(String playerName) {
        try {
            String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
            String response = makeHttpRequest(url);
            
            if (response != null && response.contains("\"id\"")) {
                // System.out.println("[KillCounter] Mojang API response: " + response);
                
                // Extract UUID from JSON response - handle both "id":" and "id" : " formats
                int start = response.indexOf("\"id\":\"");
                if (start == -1) {
                    // Try with spaces: "id" : "
                    start = response.indexOf("\"id\" : \"");
                    if (start == -1) {
                        // System.out.println("[KillCounter] No 'id' field found in Mojang response");
                        return null;
                    }
                    start += 8; // Skip past "id" : "
                } else {
                    start += 6; // Skip past "id":"
                }
                
                int end = response.indexOf("\"", start);
                if (end > start) {
                    String uuid = response.substring(start, end);
                    // System.out.println("[KillCounter] Extracted UUID: " + uuid);
                    return uuid;
                }
                
                // System.out.println("[KillCounter] Could not parse UUID from Mojang response");
                return null;
            }
        } catch (Exception e) {
            System.err.println("[KillCounter] Error getting UUID for " + playerName + ": " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Makes HTTP request and returns response
     */
    private static String makeHttpRequest(String url) {
        return makeHttpRequest(url, null, false);
    }

    private static String makeHttpRequest(String url, String hypixelApiKey, boolean notifyInvalidKey) {
        TabStatsTrafficDebug.onHttpUrl(url);
        try {
            return me.napim.gomod.features.HypixelApiHelper.fetchText(url);
        } catch (Exception e) {
            if (hypixelApiKey != null) {
                me.napim.gomod.features.GameModeStatusFetcher.handleHypixelInvalidKeyFailure(
                        hypixelApiKey, e.getMessage(), notifyInvalidKey);
            }
            System.err.println("[KillCounter] HTTP request failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** Closing {@code ]} index for a {@code [} at {@code openBracketIdx}, respecting JSON strings. */
    private static int gomodEndInclusiveOfJsonArray(String s, int openBracketIdx) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openBracketIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Next unescaped {@code "} at or after {@code from}. */
    private static int gomodIndexOfUnescapedDoubleQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) != '"') {
                continue;
            }
            int bs = 0;
            for (int j = i - 1; j >= from && s.charAt(j) == '\\'; j--) {
                bs++;
            }
            if ((bs & 1) == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reads string value after {@code "tag":"} (or spaced variant); {@code toExclusive} bounds the search
     * (e.g. guild object slice). Returns the last match in range — Hypixel lists {@code tag} before {@code ranks}
     * for some guilds (e.g. RuGaming); members rarely include string {@code tag} keys before {@code ranks}.
     */
    private static String gomodLastQuotedTagFieldInRange(String response, int from, int toExclusive) {
        String best = null;
        int pos = from;
        final String strict = "\"tag\":\"";
        final String spaced = "\"tag\" : \"";
        while (pos < toExclusive) {
            int i1 = response.indexOf(strict, pos);
            int i2 = response.indexOf(spaced, pos);
            int hit = -1;
            int valueStart = -1;
            if (i1 >= 0 && i1 < toExclusive && (i2 < 0 || i2 >= toExclusive || i1 <= i2)) {
                hit = i1;
                valueStart = i1 + strict.length();
            } else if (i2 >= 0 && i2 < toExclusive) {
                hit = i2;
                valueStart = i2 + spaced.length();
            } else {
                break;
            }
            int qEnd = gomodIndexOfUnescapedDoubleQuote(response, valueStart);
            if (qEnd > valueStart) {
                best = response.substring(valueStart, qEnd);
            }
            pos = hit + 1;
        }
        return best;
    }

    private static String gomodFirstQuotedTagFieldFrom(String response, int from) {
        int i1 = response.indexOf("\"tag\":\"", from);
        int i2 = response.indexOf("\"tag\" : \"", from);
        int hit = -1;
        int valueStart = -1;
        if (i1 >= 0 && (i2 < 0 || i1 <= i2)) {
            hit = i1;
            valueStart = i1 + 7;
        } else if (i2 >= 0) {
            hit = i2;
            valueStart = i2 + 8;
        }
        if (hit < 0) {
            return null;
        }
        int qEnd = gomodIndexOfUnescapedDoubleQuote(response, valueStart);
        if (qEnd <= valueStart) {
            return null;
        }
        return response.substring(valueStart, qEnd);
    }
    
    /**
     * Extracts guild tag from API response (Hypixel {@code guild.tag}). Field order varies: some payloads put
     * {@code tag} before {@code ranks}, others after — handle both.
     */
    private static String extractGuildTag(String response) {
        try {
            int guildStart = response.indexOf("\"guild\":{");
            if (guildStart == -1) {
                return null;
            }
            int ranksKey = response.indexOf("\"ranks\"", guildStart);
            if (ranksKey != -1) {
                String beforeRanks = gomodLastQuotedTagFieldInRange(response, guildStart, ranksKey);
                if (beforeRanks != null && !beforeRanks.isEmpty()) {
                    return beforeRanks;
                }
            }
            int searchAfterRanks = guildStart;
            if (ranksKey != -1) {
                int ranksColon = response.indexOf(':', ranksKey);
                if (ranksColon != -1) {
                    int arrOpen = response.indexOf('[', ranksColon);
                    if (arrOpen != -1) {
                        int arrClose = gomodEndInclusiveOfJsonArray(response, arrOpen);
                        if (arrClose != -1) {
                            searchAfterRanks = arrClose + 1;
                        }
                    }
                }
            }
            return gomodFirstQuotedTagFieldFrom(response, searchAfterRanks);
        } catch (Exception e) {
            System.err.println("[KillCounter] Error extracting guild tag: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extracts guild tag color from API response
     */
    private static String extractGuildTagColor(String response) {
        try {
            // System.out.println("[KillCounter] Parsing guild tag color from response...");
            
            // Look for tagColor in the guild object
            int guildStart = response.indexOf("\"guild\":{");
            if (guildStart == -1) {
                // System.out.println("[KillCounter] No 'guild' object found for color parsing");
                return "WHITE";
            }
            
            // Find the tagColor field within the guild object
            int colorStart = response.indexOf("\"tagColor\":\"", guildStart);
            if (colorStart == -1) {
                // Try with spaces
                colorStart = response.indexOf("\"tagColor\" : \"", guildStart);
                if (colorStart == -1) {
                    // System.out.println("[KillCounter] No 'tagColor' field found in guild object");
                    return "WHITE";
                }
                colorStart += 13; // Skip past "tagColor" : "
            } else {
                colorStart += 12; // Skip past "tagColor":"
            }
            
            int colorEnd = response.indexOf("\"", colorStart);
            if (colorEnd > colorStart) {
                String color = response.substring(colorStart, colorEnd);
                // System.out.println("[KillCounter] Extracted guild tag color: " + color);
                return color;
            }
            
            // System.out.println("[KillCounter] Could not parse guild tag color, using WHITE");
            return "WHITE";
        } catch (Exception e) {
            System.err.println("[KillCounter] Error extracting guild tag color: " + e.getMessage());
            return "WHITE";
        }
    }
    
    /**
     * Decodes JSON escape sequences like \u2764 to actual Unicode characters
     * Handles both regular escapes and surrogate pairs
     */
    private static String decodeJsonEscapes(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (i < input.length() - 5 && input.charAt(i) == '\\' && input.charAt(i + 1) == 'u') {
                // Found Unicode escape sequence (backslash-u-XXXX)
                try {
                    String hex = input.substring(i + 2, i + 6);
                    int codePoint = Integer.parseInt(hex, 16);
                    result.append((char) codePoint);
                    i += 6;
                    continue;
                } catch (NumberFormatException e) {
                    // Not a valid hex, just append the backslash
                }
            }
            result.append(input.charAt(i));
            i++;
        }
        
        // Handle double-escaped inputs by running again if we still have escapes
        String decoded = result.toString();
        if (decoded.contains("\\u") && !decoded.equals(input)) {
            return decodeJsonEscapes(decoded);
        }
        
        return decoded;
    }
    
    /**
     * Fixes problematic Unicode characters in guild tags for Minecraft 1.8.9 compatibility
     * Normalizes hearts, strips variation selectors, and handles emoji
     */
    private static String fixGuildTagUnicode(String guildTag) {
        if (guildTag == null || guildTag.isEmpty()) {
            return guildTag;
        }
        
        // First decode any JSON escapes
        guildTag = decodeJsonEscapes(guildTag);
        
        // Strip variation selectors (U+FE0F emoji presentation, U+FE0E text presentation)
        guildTag = guildTag.replace("\uFE0F", "").replace("\uFE0E", "");
        
        // Normalize all heart variants to U+2764 (heart) which MC 1.8.9 can render
        guildTag = guildTag
            // Other heart symbols
            .replace("\u2665", "\u2764")  // U+2665 Black heart suit
            .replace("\u2661", "\u2764")  // U+2661 White heart suit
            .replace("\u2765", "\u2764")  // U+2765 Rotated heavy heart bullet
            .replace("\u2763", "\u2764")  // U+2763 Heavy heart exclamation
            
            // Emoji hearts (MC 1.8.9 doesn't support these, normalize to heart)
            .replace("\uD83D\uDC9A", "\u2764")  // Green heart emoji
            .replace("\uD83D\uDC9B", "\u2764")  // Yellow heart emoji
            .replace("\uD83D\uDC99", "\u2764")  // Blue heart emoji
            .replace("\uD83D\uDC9C", "\u2764")  // Purple heart emoji
            .replace("\uD83D\uDDA4", "\u2764")  // Black heart emoji
            .replace("\uD83E\uDDE1", "\u2764")  // Orange heart emoji
            .replace("\uD83E\uDD0D", "\u2764")  // White heart emoji
            .replace("\uD83E\uDD0E", "\u2764")  // Brown heart emoji
            .replace("\uD83E\uDE77", "\u2764")  // Pink heart emoji
            .replace("\uD83E\uDE75", "\u2764")  // Light blue heart emoji
            .replace("\uD83E\uDE76", "\u2764")  // Grey heart emoji
            
            // HTML entities and ASCII art
            .replace("&hearts;", "\u2764")
            .replace("&lt;3", "\u2764")
            .replace("<3", "\u2764");
        
        return guildTag;
    }
    
    /**
     * Converts Hypixel guild tag color to Minecraft formatting
     */
    private static String getGuildTagColor(String tagColor) {
        if (tagColor == null) return "";
        
        switch (tagColor.toUpperCase()) {
            case "GRAY":
                return EnumChatFormatting.GRAY.toString();
            case "DARK_AQUA":
                return EnumChatFormatting.DARK_AQUA.toString();
            case "DARK_GREEN":
                return EnumChatFormatting.DARK_GREEN.toString();
            case "YELLOW":
                return EnumChatFormatting.YELLOW.toString();
            case "GOLD":
                return EnumChatFormatting.GOLD.toString();
            default:
                return EnumChatFormatting.WHITE.toString();
        }
    }

    /**
     * Explanatory chat lines for {@code /tabcolorwhy} (Hypixel context, cache, kill-effect rules, final display).
     */
    public static List<String> buildTabColorWhyLines(String ign) {
        List<String> lines = new ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            lines.add(EnumChatFormatting.RED + "Not connected.");
            return lines;
        }

        invalidateTabFormatCacheOnBlitzPhaseChange(mc);

        String search = (ign == null || ign.trim().isEmpty())
                ? (mc.thePlayer.getGameProfile() != null ? mc.thePlayer.getGameProfile().getName() : "")
                : ign.trim();
        if (search.isEmpty()) {
            lines.add(EnumChatFormatting.RED + "Usage: /tabcolorwhy [player] (defaults to you)");
            return lines;
        }

        NetworkPlayerInfo info = null;
        for (NetworkPlayerInfo ni : mc.getNetHandler().getPlayerInfoMap()) {
            if (ni != null && RemoteDisguiseManager.getSourceProfile(ni) != null && RemoteDisguiseManager.getSourceProfile(ni).getName() != null
                    && RemoteDisguiseManager.getSourceProfile(ni).getName().equalsIgnoreCase(search)) {
                info = ni;
                break;
            }
        }
        if (info == null) {
            lines.add(EnumChatFormatting.RED + "No TAB row for \"" + search + "\".");
            return lines;
        }

        String baseName = RemoteDisguiseManager.getSourceProfile(info).getName();
        String g = EnumChatFormatting.GRAY.toString();
        String y = EnumChatFormatting.YELLOW.toString();

        // Use yellow for the label name — WHITE looked like "your TAB color is white" in chat.
        lines.add(EnumChatFormatting.GOLD + "[gomod TAB color] " + EnumChatFormatting.YELLOW + baseName);
        lines.add(g + "Hypixel Blitz: pregame TAB (hub + match-map waiting) is usually plain white (f); "
                + "match-map join/quit lines use light red (c); TAB/light red from cages open through the live match.");

        lines.add(g + "Detection: " + GameContext.explainDetection(mc));

        if (GameContext.isBlitzCagePhaseSidebar(mc)) {
            lines.add(y + "Cage phase: sidebar title looks like BLITZ SG and a line contains \"Cages open\".");
        }

        boolean bypassCache = GameContext.isBlitzMatchMapPregameSidebar(mc);
        if (bypassCache) {
            lines.add(EnumChatFormatting.AQUA + "TAB format cache bypassed for base-name resolution (cage or match-map pregame, not hub queue).");
        } else {
            String cached = tabDisplayFormatCache.get(tabRowKey(info));
            if (cached != null && !cached.isEmpty()) {
                lines.add(y + "TAB format cache has a stored line for this player (may keep older §f from the hub).");
            } else {
                lines.add(g + "TAB format cache: no entry for this player.");
            }
        }

        String serverBase = resolveTabServerFormattedBase(info, baseName);
        lines.add(g + "Resolved base (actual § codes from gomod; not recolored for this chat line):");
        lines.add(elideTabColorWhy(serverBase, 200));
        appendTabColorProbe(lines, serverBase, baseName, g, y);

        boolean isDead = isPlayerDead(baseName);
        boolean serverDead = GameContext.isBlitzGameActive(mc) && isBlitzTabServerDeadBlue(serverBase, baseName);
        boolean effectiveDead = isDead || serverDead;
        if (effectiveDead) {
            lines.add(y + "Dead row handling: IGN reset to scoreboard team format ("
                    + (isDead ? "kill-tracked " : "")
                    + (serverDead ? "server blue/gray " : "")
                    + ").");
        }

        syncTabStatsSettingsFromConfig();
        String killEffect = me.napim.gomod.command.KillEffectCommand.getPlayerEffect(resolveTabLookupName(baseName));
        boolean meaningfulEffect = killEffect != null && !killEffect.isEmpty() && !killEffect.equalsIgnoreCase("none");
        boolean blitzPregame = GameContext.isBlitzPregameLobby(mc);
        boolean blitzGameActive = GameContext.isBlitzGameActive(mc);
        boolean blitzAliveIgnLightRedContext = (blitzGameActive && !blitzPregame)
                || GameContext.isBlitzCagePhaseSidebar(mc);
        KillEffectTabDisplayMode keMode = killEffectTabMode;

        lines.add(g + "Kill-effect TAB mode: " + keMode + "  blitzPregameSidebar=" + blitzPregame
                + "  blitzMatchActive(taunt)=" + blitzGameActive);

        if (!effectiveDead && meaningfulEffect && keMode.colorsTab() && blitzAliveIgnLightRedContext) {
            lines.add(y + "Rule: apply kill-effect color to IGN (API: " + killEffect + ").");
        } else if (!effectiveDead && !meaningfulEffect
                && ((blitzGameActive && !blitzPregame) || GameContext.isBlitzCagePhaseSidebar(mc))
                && (keMode.colorsTab() || keMode == KillEffectTabDisplayMode.ICON)) {
            lines.add(y + "Rule: apply §c light red to IGN (cages open or live match; COLORED_NAME or ICON).");
        } else if (keMode.colorsTab() && !blitzAliveIgnLightRedContext) {
            lines.add(y + "Rule: colored-name TAB tint is disabled in lobbies and pregame; it applies only during live Blitz or cages.");
        }

        if (keMode == KillEffectTabDisplayMode.ICON) {
            lines.add(g + "Icon mode: texture/letter by head; IGN still gets §c in cage/active Blitz when there is no API effect.");
        } else if (keMode == KillEffectTabDisplayMode.OFF) {
            lines.add(g + "Kill-effect TAB coloring OFF.");
        }

        boolean includeScoped = !gameHasEnded && enabled;
        lines.add(g + "Game-scoped TAB extras ([kills], kits): " + (includeScoped ? "eligible" : "off")
                + " (gameHasEnded=" + gameHasEnded + ", killCounterRuntime=" + enabled + ")");

        IChatComponent display = info.getDisplayName();
        if (display != null) {
            String full = display.getFormattedText();
            lines.add(g + "Final TAB line (displayName), raw § codes:");
            lines.add(elideTabColorWhy(full, 200));
            appendTabColorProbe(lines, full, baseName, g, y);
        } else {
            lines.add(y + "displayName is null — vanilla uses scoreboard formatting; mixin may append [kills].");
            if (shouldRenderKillCounterAfterName()) {
                int k = getKillCount(baseName);
                if (k >= 1) {
                    lines.add(g + "PlayerList mixin can append [" + k + "] when the list renders.");
                }
            }
            appendTabColorProbe(lines, serverBase, baseName, g, y);
        }

        if (isLocalPlayerTabRow(baseName) && LocalDisguise.isEnabled()) {
            lines.add(y + "You are disguised: rank/name TAB text may be rewritten (LocalDisguise / tab mixins).");
        }

        return lines;
    }

    private static String elideTabColorWhy(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static void appendTabColorProbe(List<String> lines, String formatted, String ign, String g, String y) {
        if (formatted == null || ign == null || ign.isEmpty()) {
            return;
        }
        String vis = stripColors(formatted);
        int idx = indexOfIgnoreCaseVisible(vis, ign);
        if (idx < 0 && LocalDisguise.isEnabled()) {
            String disguised = LocalDisguise.getTargetName();
            if (disguised != null && !disguised.isEmpty()) {
                int disguisedIdx = indexOfIgnoreCaseVisible(vis, disguised);
                if (disguisedIdx >= 0) {
                    idx = disguisedIdx;
                    ign = disguised;
                }
            }
        }
        if (idx < 0) {
            lines.add(g + "Could not find IGN in visible text to read color codes.");
            return;
        }
        int fi = formattedIndexForVisibleIndex(formatted, idx);
        char c = effectiveTextColorBefore(formatted, fi);
        String label = tabColorCodeLabel(c);
        lines.add(y + "Color at start of IGN: "
                + (c == 0 ? g + "(none / reset before name)"
                : EnumChatFormatting.WHITE + "§" + c + g + " — " + label));
    }

    private static String tabColorCodeLabel(char c) {
        switch (c) {
            case '0': return "black";
            case '1': return "dark_blue";
            case '2': return "dark_green";
            case '3': return "dark_aqua";
            case '4': return "dark_red";
            case '5': return "dark_purple";
            case '6': return "gold";
            case '7': return "gray";
            case '8': return "dark_gray";
            case '9': return "blue";
            case 'a': return "green";
            case 'b': return "aqua";
            case 'c': return "red (light red)";
            case 'd': return "light_purple";
            case 'e': return "yellow";
            case 'f': return "white";
            default: return "other";
        }
    }
}
