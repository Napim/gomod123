package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.gui.GuiHeader;
import me.napim.gomod.gui.HudOpacitySliderBoxInsets;
import me.napim.gomod.shareinv.ShareInvConstants;
import me.napim.gomod.util.ClientPlayerIdentity;
import me.napim.gomod.util.DcStringUtil;
import me.napim.gomod.util.GomodPaths;
import me.napim.gomod.util.RelayDevEndpoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Manages persistent configuration settings for the gomod mod.
 * Saves and loads user preferences between game sessions.
 */
public class ConfigManager {
    /** Shared data dir: {@code <.minecraft>/gomod123} (Forge). */
    public static File getGomodDataDirectory() {
        return GomodPaths.getGomod123Dir();
    }

    /** {@code gomod123/skins} — created if missing (nick disguise PNG skins). */
    public static File getGomodSkinsDirectory() {
        File dir = new File(getGomodDataDirectory(), "skins");
        if (!dir.isDirectory()) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        return dir;
    }

    private static File getConfigFile() {
        return new File(getGomodDataDirectory(), "gomodConfig.json");
    }

    /** Live {@code gomodConfig.json} path (Forge {@code gomod123} folder). */
    public static File getGomodConfigFile() {
        return getConfigFile();
    }

    /** Optional user-tuned defaults captured from live config (used for first-time installs / defaults-based profiles). */
    private static File getUserDefaultsFile() {
        return new File(getGomodDataDirectory(), "gomodUserDefaults.json");
    }

    /** Reload {@code gomodConfig.json} from disk after an external file replace (e.g. profile switch). */
    public static synchronized void reloadConfigFromDisk() {
        if (pendingSave != null && !pendingSave.isDone()) {
            pendingSave.cancel(false);
            pendingSave = null;
        }
        loadedFromCorruptConfig = false;
        loaded = false;
        loadConfig();
        // An explicit profile/config import is a choice, unlike another client's stale exit save.
        if (!loadedFromCorruptConfig) {
            betterPlayerFormatDirty = true;
            saveConfig();
        }
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private static JsonObject config = new JsonObject();
    private static volatile boolean loaded = false;
    /** Prevent config-backed identity helpers from recursively re-entering loadConfig during startup. */
    private static volatile boolean loading = false;
    /** True when startup encountered a malformed config file; prevents automatic saves from wiping the user's file. */
    private static volatile boolean loadedFromCorruptConfig = false;
    
    // Debounced save: instead of writing to disk on every setter call,
    // we schedule a single write 2 seconds after the last change.
    private static final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ConfigManager-Save");
        t.setDaemon(true);
        return t;
    });
    private static volatile ScheduledFuture<?> pendingSave = null;
    private static final long SAVE_DELAY_MS = 2000; // 2 second debounce
    private static String lastPersistedConfigJson = null;
    private static String lastPersistedConfigPath = null;
    private static long lastPersistedConfigModified = Long.MIN_VALUE;
    private static volatile boolean lastSaveDeferred;
    
    // Default settings
    /** Legacy {@code autoQueueEnabled} is derived as solo OR teams when saving for older builds. */
    private static boolean autoQueueSoloEnabled = false;
    private static boolean autoQueueTeamsEnabled = false;
    private static float autoQueueSoloDelay = 0.4109589f; // Solo game delay in seconds (0.0-5.0)
    private static float autoQueueTeamsDelay = 0.4109589f; // Teams game delay in seconds (0.0-5.0)
    private static boolean deathRequeueSoloEnabled = false;
    private static boolean deathRequeueTeamsEnabled = false;
    private static float deathRequeueSoloDelay = 0.0f; // Death requeue solo delay in seconds (0.0-5.0)
    private static float deathRequeueTeamsDelay = 0.0f; // Death requeue teams delay in seconds (0.0-5.0)
    private static boolean inventoryHUDEnabled = true;
    private static boolean inventoryHUDShowEmpty = true; // Show HUD even when inventory is empty
    private static int inventoryHUDCustomX = 362; // Custom X position
    private static int inventoryHUDCustomY = 331; // Custom Y position
	private static float inventoryHUDScale = 0.8125f; // Scale factor (0.5-4.0, supports 1.5)
    private static int inventoryHUDBackgroundOpacity = 115; // Background opacity (0-255)
    private static int inventoryHUDBackgroundColor = 0x000000; // Background color (RGB)
    private static boolean inventoryHUDForce = false; // Force show HUD even when in GUI
    /** ShareInv teammate (remote) inventory HUD — same options as inventory HUD, separate position/scale. */
    private static boolean friendInvHudEnabled = true;
    private static boolean friendInvHudShowEmpty = true;
    private static int friendInvHudCustomX = 4;
    private static int friendInvHudCustomY = 160;
    private static float friendInvHudScale = 0.7f;
    private static int friendInvHudBackgroundOpacity = 92;
    private static int friendInvHudBackgroundColor = 0x9E5252;
    private static boolean friendInvHudForce = false;
    private static boolean friendInvHudShowRemoteHealth = true;
    private static boolean friendInvHudShowRemoteFood = false;
    private static boolean friendInvHudShowRemoteXp = true;
    private static boolean friendInvHudShowHeldHighlight = true;
    private static boolean friendInvHudShowDirection = true;
    private static boolean friendInvHudDirectionVertical = true;
    private static boolean friendInvHudTextShadow = true;
    private static float perspectiveDistance = 4.0f; // Third-person camera distance in blocks
    private static boolean perspectiveEnabled = true; // Enable perspective adjustment feature
    private static float perspectiveScrollStep = 1.3f; // Mouse wheel step size in blocks
    private static boolean perspectiveResetOnExit = true; // Reset to default when leaving third-person
    private static int perspectiveResetKeyCode = 0; // Unbound by default
    private static boolean perspectiveRequireHold = false; // Require holding perspective key to scroll
    private static boolean perspectiveSmoothZoom = true; // Enable smooth zoom transitions
    private static float perspectiveSmoothSpeed = 1.00f; // Smooth transition speed (0.01-1.0)
    private static int perspectiveZoomKeyCode = 0; // Unbound by default
    private static float perspectiveFOV = 110.0f; // Perspective FOV setting (10-170)
    private static boolean perspectiveFOVEnabled = false; // Enable custom perspective FOV (default disabled)
    private static boolean perspectiveLockScroll = false; // Lock hotbar scroll while in perspective (default disabled)
    private static int perspectiveAdvancedRenderDistanceKeyCode = 0; // Hold + scroll for block-precise render distance
    private static int perspectiveAdvancedRenderDistanceBlocks = 0; // 0 = derive from vanilla chunks until adjusted
    private static boolean goStatsEnabled = false; // GoStats AI functionality
    private static boolean killCounterEnabled = true; // KillCounter main enable/disable
    private static boolean killCounterAutoKeAll = true; // Legacy: auto /tabstats all when cages open
    private static String killCounterAutoScanMode = "CAGES_OPEN"; // OFF | CAGES_OPEN | PLAYERS_JOIN
    private static boolean killCounterShowGuildTag = true; // Show guild tags in TAB stats
    private static volatile String killCounterBetterPlayerFormat = "OFF";
    private static boolean betterPlayerFormatDirty;
    private static boolean killCounterShowTeammateKits = true; // Show teammate kits in TAB stats
    private static boolean killCounterShowEnemyKits = false; // Show enemy players' kit in TAB stats
    private static boolean killCounterShowKeAllChat = false; // Show /tabstats all info in chat
    private static boolean killCounterStatsDisplay = true; // Show stats display (KD / WL / W) in TAB
    /** Legacy WL mode (migrated to {@link #killCounterStatsShowKd/Wl/W}). */
    private static int killCounterWlMode = 1;
    private static boolean killCounterStatsShowKd = false;
    private static boolean killCounterStatsShowWl = true;
    private static boolean killCounterStatsShowW = false;
    /** AFTER_NAME | BEFORE_NAME — where TAB kill count is rendered when enabled. */
    private static String killCounterTabDisplayMode = "BEFORE_NAME";
    /** COLORED_NAME_TAB | COLORED_NAME (TAB+TAGS) | ICON | OFF. */
    private static String killCounterKillEffectTabMode = "ICON";
    /** OFF | KILLS_THEN_WLR | WLR_THEN_KILLS | KILLS_THEN_WINS | WINS_THEN_KILLS | KILLS_THEN_OVERALL_KILLS — TAB stats sort (see KillCounter). */
    private static String killCounterTabListSortMode = "KILLS_THEN_WLR";
    /** SOLO | TEAMS | BOTH — which Blitz queue TAB sorting targets. */
    private static String killCounterTabListSortGameScope = "BOTH";
    /** When true, TAB listing gets primary grouped by Hypixel's [DIST n] bracket. */
    private static boolean killCounterTabListSortDistrictEnabled = false;
    /** When true, dead players are listed under their killer on TAB (Blitz in-game); see /tabkillers. */
    private static boolean killCounterTabKillersEnabled = false;
    /** Keybind to toggle /tabkillers behavior on/off. */
    private static int killCounterTabKillersKeyCode = 0;
    /** Extra horizontal shift, per victim depth, for TAB head/left-prefix placement in /tabkillers chains. */
    private static int killCounterTabKillersVictimOffsetPx = 8;
    /** Keep /tabkillers kill-count prefixes in one shared X column even when victim rows are indented. */
    private static boolean killCounterTabKillersVictimKillCountAligned = true;
    /** Extra horizontal shift, per victim depth, for TAB kill-count prefix placement in /tabkillers chains. */
    private static int killCounterTabKillersVictimKillCountOffsetPx = 0;
    /** When true, victim rows retained after leaving use vanilla spectator opacity. */
    private static boolean killCounterTabKillersVictimDimmed = true;
    /** When true, show Blitz Star type icon left of kill-effect icon / kill count on TAB. */
    private static boolean killCounterBlitzStarTabIconEnabled = true;
    // Global chat defaults
    private static boolean globalChatEnabled = false;
    private static String globalChatEndpoint = "";
    private static String globalChatChannel = "global";
    private static int globalChatPollSeconds = 3;
    /** When true, gomod sends verbose TAB/nick duplicate debug lines to chat (default off). See /debugmessages. */
    private static boolean debugMessagesToChatEnabled = false;
    /** Adds tracked killers below Hypixel's Solo Blitz top-three result rows. */
    private static boolean blitzExtendedResultsEnabled = true;
    /** Adds the right-click player-visibility dye to hotbar slot five during Blitz pregames. */
    private static boolean blitzPregamePlayerToggleEnabled = true;
    /** Shows the Blitz deathmatch Game End countdown on the Ender Dragon boss bar when time is low. */
    private static boolean deathmatchTimerBossBarEnabled = true;
    /** Remaining seconds threshold at which the Deathmatch Timer boss bar appears (5..60, default 30). */
    private static int deathmatchTimerBossBarThresholdSeconds = 30;
    /** Mutes stepping sounds of local-player-spawned Ocelots & Witches during Hypixel Blitz matches. */
    private static boolean silentSpawnedMobStepsEnabled = false;
    private static boolean silentSpawnedOcelotSteps = true;
    private static boolean silentSpawnedWitchSteps = true;
    private static boolean blitzExtendedResultsMorePositionsEnabled = true;
    private static boolean blitzExtendedResultsStarInfoEnabled = true;
    private static boolean blitzExtendedResultsDeathmatchPlayersEnabled = true;
    private static boolean blitzExtendedResultsMessageCountEnabled = true;
    private static boolean blitzExtendedResultsGameDurationEnabled = true;
    private static boolean blitzExtendedResultsMapPlayerCountEnabled = true;
    /** When true, chat reports melee hit damage and crit status. See /debughits. */
    private static boolean debugHitsEnabled = false;
    /**
     * When true, fetch crowd duplicate-nick→real mappings from {@link #getDenickCrowdSyncEffectiveBaseUrl()}
     * (see {@link me.napim.gomod.features.DenickCrowdSync}): on Hypixel join, after auto-denick reports, on gomod
     * relay polls, when duplicate TAB nicks appear, and every ~45s fallback. Manual {@code /nametagnick} never uploaded.
     * Default on; players can turn this off in saved config.
     */
    private static boolean denickCrowdSyncEnabled = true;
    /**
     * Optional HTTPS (or HTTP) origin override, no path. When empty, {@link #getDenickCrowdSyncEffectiveBaseUrl()} uses
     * {@link DenickCrowdSyncConstants#PUBLIC_DENICK_CROWD_BASE_URL}. Endpoints: {@code GET {base}/gomod-denick-crowd.json},
     * {@code POST {base}/gomod-denick-report}.
     */
    private static String denickCrowdSyncBaseUrl = "";

    /**
     * Optional override for ShareInv relay (ws:// or wss://). Empty = use {@link ShareInvConstants#PUBLIC_RELAY_WSS}
     * if set, else local dev fallback (internal builds only).
     */
    private static String shareInvRelayUrl = "wss://gomod-service.onrender.com";
    /** Optional shared secret; must match relay SHAREINV_SECRET if set. */
    private static String shareInvSecret = "";
    /** When true, connect to relay while in-game if shareInvRelayUrl is set. */
    private static boolean shareInvAutoConnect = true;
    /** Latest mod version we already showed an in-game update notice for (see {@link GomodUpdateChecker}). */
    private static String lastNotifiedModVersion = "";
    /** gomod friend usernames allowed for screenshot/shareinv actions; display casing is preserved. */
    private static java.util.List<String> gomodFriends = new java.util.ArrayList<String>();
    /** Stable install seed used to derive one gomod Social id per Minecraft account. */
    private static String gomodSocialInstallId = "";
    /** Active account's gomod Social id. */
    private static String gomodSocialId = "";
    /** Friend display name (lowercase) -> their gomodSocialId on the relay. */
    private static final java.util.LinkedHashMap<String, String> gomodFriendSocialIds =
            new java.util.LinkedHashMap<String, String>();
    /** Per gomod Social id — friend lists for this install. */
    private static final java.util.LinkedHashMap<String, java.util.List<String>> gomodFriendsByAccount =
            new java.util.LinkedHashMap<String, java.util.List<String>>();
    private static String activeGomodFriendsAccount;
    /** One-time migration from flat {@code gomodFriends} array into the first account that loads Social. */
    private static java.util.List<String> gomodFriendsLegacyPending = new java.util.ArrayList<String>();
    /** True after gomod Service returned a friends snapshot for the active profile (authoritative list). */
    private static boolean gomodFriendsServiceAuthoritative;
    private static final java.util.HashSet<String> gomodFriendsBootstrapDoneKeys = new java.util.HashSet<String>();
    /** Incoming friend requests waiting for accept/decline (active gomod Social id). */
    private static java.util.List<String> gomodIncomingFriendRequests = new java.util.ArrayList<String>();
    /** Outgoing friend requests we sent and are still waiting on (active gomod Social id). */
    private static java.util.List<String> gomodOutgoingFriendRequests = new java.util.ArrayList<String>();
    /** Incoming requests whose notification has already been opened. */
    private static java.util.List<String> gomodSeenIncomingFriendRequests = new java.util.ArrayList<String>();
    private static final java.util.LinkedHashMap<String, java.util.List<String>> gomodIncomingFriendRequestsByAccount =
            new java.util.LinkedHashMap<String, java.util.List<String>>();
    private static final java.util.LinkedHashMap<String, java.util.List<String>> gomodOutgoingFriendRequestsByAccount =
            new java.util.LinkedHashMap<String, java.util.List<String>>();
    private static final java.util.LinkedHashMap<String, java.util.List<String>> gomodSeenIncomingFriendRequestsByAccount =
            new java.util.LinkedHashMap<String, java.util.List<String>>();
    private static String activeGomodFriendRequestsAccount;
    private static java.util.List<String> gomodIncomingFriendRequestsLegacyPending = new java.util.ArrayList<String>();
    private static java.util.List<String> gomodOutgoingFriendRequestsLegacyPending = new java.util.ArrayList<String>();
    /** Latest relay-issued one-time reset applied to locally cached outgoing friend requests. */
    private static long gomodFriendRequestResetGeneration;
    /** When false, incoming friend screenshots are ignored (no toast / viewer). */
    private static boolean acceptIncomingFriendScreenshots = true;
    /** When true, direct messages from players outside the gomod friends list are accepted. */
    private static boolean acceptIncomingNonFriendMessages = false;
    /** When false, incoming ShareInv invites are auto-declined. */
    private static boolean acceptIncomingShareInv = true;
    /** When true, incoming gomod cape designs are saved immediately instead of asking first. */
    private static boolean autoAcceptIncomingCapes = false;
    /** Top-right gomod Social message icon with unread badge. */
    private static boolean socialMsgIconHudEnabled = true;
    /** Transient top-right toast for ordinary, non-mention Public chat messages. */
    private static boolean publicMessagePopupsEnabled = true;

	// Health Indicator defaults
	// -------------------- Replay Settings --------------------
	private static boolean replayHealthDisplayEnabled = false; // Show sci-fi health UI next to players in replay
	private static String replayHealthDisplayStyle = "SIMPLE"; // Health indicator display style
	private static float replayHealthDisplayYOffset = 0.0f; // Vertical offset for health UI position (0.0 to 0.8)
	private static boolean replayShowInvisibleEnabled = false; // Show invisible players in replay mode
	
	// Player Target defaults
	private static boolean playerTargetEnabled = true; // Teammate tracker enabled
	/** Stored string: DISABLED, GREEN (flat), or CHAMELEON (vanilla outline). */
	private static String playerTargetGlowTeammateMode = "DISABLED";
	private static boolean playerTargetTeammateTrailEnabled = true; // Trail ribbon for teammate
	private static boolean playerTargetShowVerticalDistance = true; // Show vertical distance
	private static boolean playerTargetShowBackground = true; // Show semi-transparent background
	private static int playerTargetBackgroundOpacity = 119; // Background opacity (0-255)
	private static int playerTargetBackgroundColor = 0;
	private static String playerTargetPosition = "CUSTOM"; // Position: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CUSTOM
	private static int playerTargetCustomX = 3; // Custom X position
	private static int playerTargetCustomY = 83; // Custom Y position
	private static boolean playerTargetShowEnchantments = false; // Show enchantments on equipment
	private static boolean playerTargetShowTauntInfo = true; // Show taunt usage info
	private static boolean playerTargetTextShadow = true;
	private static boolean playerTargetTeamInviteNotifierEnabled = true;
	private static float playerTargetScale = 0.76250005f; // UI scale (0.5-4.0)
	
	// MobHealthDisplay defaults
	private static boolean mobHealthDisplayEnabled = true; // Show health above mobs with heart icons
	private static boolean mobHealthDisplayShowBackground = false; // Show background for mob health display
	private static boolean mobHealthDisplayShowEffects = true; // Show potion effects on the mob health panel
	private static boolean mobHealthDisplayTextShadow = true;
	private static int mobHealthDisplayBackgroundOpacity = 0; // Background opacity (0-255)
	private static int mobHealthDisplayBackgroundColor = 0;
	private static double mobHealthDisplayMaxDistance = 48.0; // Max distance to detect mobs (1-48 blocks)
	private static double mobHealthDisplayPersistenceTime = 3.035714328289032; // Time to keep showing UI after looking away (0-10 seconds)
	private static int mobHealthDisplayPanelX = 213; // Panel X position (default)
	private static int mobHealthDisplayPanelY = 142; // Panel Y position (default)
	private static float mobHealthDisplayScale = 1.0f; // Panel scale (0.5-4.0)
	/** PANEL | NAMETAG | BOTH | BOTH2 — HUD panel vs in-world health label above the mob */
	private static String mobHealthDisplayMode = "BOTH";
	/** Entity type ids (spawn-egg meta) with health tags disabled. Default: pig, chicken, squid, ocelot, rabbit, villager. */
	private static final java.util.HashSet<Integer> mobHealthDisplayHealthTagDisabledIds =
			new java.util.HashSet<Integer>(java.util.Arrays.asList(90, 93, 94, 98, 101, 120));
	private static boolean horseDonkeyDistanceHudEnabled = true; // Show distance to tracked horse/donkey
	private static boolean horseDonkeyShowBackground = false; // Background for horse/donkey distance HUD
	private static boolean horseDonkeyTeleportSoundEnabled = true; // Play a sound when the tracked mount snaps back
	private static int horseDonkeyBackgroundOpacity = 121; // Background opacity (0-255)
	private static int horseDonkeyBackgroundColor = 0x000000;
	private static int horseDonkeyPanelX = 583; // HUD X position
	private static int horseDonkeyPanelY = 453; // HUD Y position
	private static float horseDonkeyScale = 0.82530123f; // HUD scale (0.5-4.0)
	private static int horseDonkeyHudTitleColor = 0xFFAA00; // "Horse distance" title line
	private static int horseDonkeyHudValueColor = 0x55FF55; // Distance number (e.g. 17.4)
	private static int horseDonkeyHudBlocksColor = 0xFFFFFF; // " blocks" suffix
	private static boolean horseFoodNametagEnabled = true; // Show food nametag above tracked horse/donkey
	private static boolean horseDonkeyTextShadow = true;
	private static boolean gomodMenuMoveEnabled = false; // Allow movement while gomod menus are open
	private static boolean gomodMenuDragLookEnabled = false;
	private static boolean minecraftMenuControlsEnabled = false;
	private static boolean lunarMenuControlsEnabled = false;
	/** -1 follows Minecraft; 0=Auto; 1=Small; 2=Normal; 3=Large; 4/5 explicit factors. */
	private static int gomodMenuGuiScale = -1;
	/** Hide extra Hypixel TAB rows when both nick profile and real IGN are listed. */
	private static boolean hypixelTabDupeHideEnabled = true;
	/** Hide stale Hypixel TAB rows after quit / environment deaths. */
	private static boolean hypixelTabQuitGhostHideEnabled = true;
	/** Lunar replay viewer omits the vanilla player health objective/nameplate row. */
	private static boolean forceReplayPlayerHealthTagsEnabled = true;
	/** Draw the entity hitbox for Blitz #rekt finisher armor-stand holograms. */
	private static boolean blitzRektHitboxOverlayEnabled = false;
	/** Extra client-side calibration padding around the Blitz #rekt collision outline, in blocks. */
	private static double blitzRektHitboxPreviewLeft = 0.0D;
	private static double blitzRektHitboxPreviewRight = 0.0D;
	private static double blitzRektHitboxPreviewUp = 0.0D;
	private static double blitzRektHitboxPreviewDown = 0.0D;
	/** Highest one-time replay health-tag default migration applied to the saved config. */
	private static int forceReplayPlayerHealthTagsDefaultsVersion =
			ReplayHealthTagsDefaultsMigration.CURRENT_VERSION;
	/** Highest one-time migration that disables the retired /tabstats all chat output option. */
	private static int killCounterShowKeAllChatDefaultsVersion =
			KillCounterKeAllChatDefaultsMigration.CURRENT_VERSION;
	private static boolean mobNametagsEnabled = true; // Show nametags above non-player mobs
	private static boolean friendlyMobNametagColorEnabled = true;
	private static boolean mobTargetDisplayEnabled = true; // Highlight mobs targeting players and draw target arrows
	/** Client-only duel movement predictor (HUD + ghost; no automation). */
	private static boolean duelPredictorEnabled = false;
	private static boolean duelPredictorGhostEnabled = true;
	private static boolean duelPredictorHudEnabled = true;
	private static int duelPredictorLeadTicks = 3;
	private static int duelPredictorHudPosX = -1;
	private static int duelPredictorHudPosY = 8;
	private static float duelPredictorHudScale = 1.0f;
	private static int duelPredictorGhostColorArgb = 0x44FFFF;
	private static boolean blitzChallengesHudEnabled = true;
	private static int blitzChallengesHudPosX = 4;
	private static int blitzChallengesHudPosY = 4;
	private static float blitzChallengesHudScale = 1.0f;
	private static boolean blitzChallengesHudTextShadow = true;
	/** Background alpha for {@link me.napim.gomod.features.BlitzChallenges} HUD (0 = off). */
	private static int blitzChallengesHudBg = 144;
	private static int blitzChallengesHudBgColor = 0x000000;
	private static final Set<String> blitzChallengesCompletedTiers = new HashSet<>();
	private static boolean blitzChallengeBadgeEnabled = false;
	private static String blitzChallengeBadgeEquippedKey = "";
	private static float blitzChallengeBadgeOffsetX = 0.0f;
	private static float blitzChallengeBadgeOffsetY = 0.12f;
	private static float blitzChallengeBadgeOffsetZ = 0.08f;
	private static float blitzChallengeBadgeScale = 1.0f;
	private static String blitzChallengeBadgeAttachPart = "body";
	/** Multi-badge placement records; the legacy fields above remain as a one-time migration source. */
	private static JsonArray blitzChallengeBadgePlacements = new JsonArray();
	/** Shown above targeted players (supports § / & color codes). Default "!" */
	private static String mobTargetMarkerText = "\u00a7c!";
	/** Local third-person mob model for disguise menu: OFF, ZOMBIE, CREEPER, ... */
	private static String disguiseMobVisualKind = "OFF";
	/** Last mob entity id used in LocalMobDisguise (persisted for convenience restore). */
	private static String disguiseMobVisualLastUsed = "ghast";

	// BlitzKitTimerHUD defaults (HUD editor author layout)
	private static boolean blitzKitTimerHudEnabled = true;
	private static float blitzKitTimerHudScale = 0.9f;
	private static int blitzKitTimerHudOffsetCenterX = 0;
	private static int blitzKitTimerHudPosY = 412;
	private static boolean blitzKitTimerHudTextShadow = false;
	/** Background alpha for {@link me.napim.gomod.features.BlitzKitTimerHUD} row (0 = off). */
	private static int blitzKitTimerHudBg = 0;
	private static int blitzKitTimerHudBgColor = 0x000000;
	/** Top-right toast for incoming global-chat screenshots; when false, legacy full-screen prompt is used. */
	private static boolean incomingScreenshotOverlayEnabled = true;
	private static boolean incomingScreenshotTextShadow = true;
	/** Top-left X of toast; {@code < 0} = anchor from screen right (auto). */
	private static int incomingScreenshotHudPosX = 722;
	private static int incomingScreenshotHudPosY = 17;
	private static float incomingScreenshotHudScale = 0.8373135f;
    private static boolean ptracersCagesEnabled = false;
    private static boolean cageChestOverlayEnabled = true;
    private static boolean cageMiddleLootEstimateEnabled = false;
    // When true, play a cage opening sound when cages open (enderdragon growl by default)
    private static boolean cageOpeningSoundEnabled = true;
    private static boolean gameTeleporterSoundEnabled = true;
    // When true, plays an alternative cage opening sound during Blitz Hour
    private static boolean blitzHourCageSfxEnabled = true;
    private static boolean ptracersTargetNametagEnabled = true;
    private static boolean tabStatsRuntimeMuteEnabled = false;
    /** Weave dev-only experimental far-right TAB stats column. Default is the normal inline layout. */
    private static boolean weaveDevTabStatsSeparated = false;
	/** Local account UUID (lowercase, dashes removed) -> equipped Blitz kill effect code. */
	private static final Map<String, String> blitzKillEffectByAccountUuid = new HashMap<String, String>();
	
	// Selfie defaults (note: intentionally not persisted, always starts disabled)
	private static boolean selfieEnabled = false; // Freeze camera at current position while player moves
	
	// FakeBreak defaults
	private static boolean fakeBreakEnabled = false; // Client-side block breaking with visual effects
	
	// BestArmorDisplay defaults
	private static boolean bestArmorDisplayEnabled = false; // Show recommended armor pieces in inventory
	/** Above paper doll in survival inventory: 0 = hidden, 1 = percent only, 2 = percent + min/max roll range. */
	public static final int BEST_ARMOR_PERCENTAGE_OFF = 0;
	public static final int BEST_ARMOR_PERCENTAGE_MINIMAL = 1;
	public static final int BEST_ARMOR_PERCENTAGE_DETAILED = 2;
	private static int bestArmorPercentageMode = 1;
	/** 1-8: best-armor slot highlight / panel path; {@code /fixbestarmor}; default 5 = after {@code GuiScreen.drawScreen}. */
	private static int bestArmorContainerRenderStrategy = 1;
	private static boolean bestArmorDebugOverlayPaint = false;
	/** Thin red slot tint for disposable Blitz loot heuristics (Misc → Inventory QoL). */
	private static boolean uselessItemOverlayEnabled = false;
	/** When true, useless-item tint runs on Hypixel outside Blitz sidebar contexts (debug). See /uselessitemsforce. */
	private static boolean uselessItemOverlayForceEverywhere = false;
	/** In chest GUIs, tint slots blue (pick) / yellow (skip) from simulated pickup heuristics. */
	private static boolean uselessItemOverlayChestPickHintsEnabled = false;
	/** ARGB as 8 hex chars (AARRGGBB), e.g. {@code 404488FF}. See /chestpickcolors. */
	private static String uselessChestHintBlueFillHex = "404488FF";
	private static String uselessChestHintBlueEdgeHex = "D066AAFF";
	private static String uselessChestHintYellowFillHex = "40FFCC44";
	private static String uselessChestHintYellowEdgeHex = "D0FFDD66";
    private static boolean uselessAlwaysRedBowl = true;
    private static boolean uselessAlwaysRedCake = false;
    private static boolean uselessAlwaysRedChestMinecart = false;
    private static boolean uselessAlwaysRedMinecart = false;
    private static boolean uselessAlwaysRedRail = false;
    private static boolean uselessAlwaysRedRose = false;
    private static boolean uselessAlwaysRedWheat = true;
    private static boolean uselessAlwaysRedSharpnessFish = true;
    private static boolean uselessAlwaysRedApple = false;
    private static boolean uselessAlwaysRedStick = false;
    /** STONE | IRON — which sword to tint when iron (no sharp) vs stone Sharp I are both present. */
    private static String uselessMeleeIronVsStoneSharpRed = "STONE";
    /** IRON | DIAMOND — which to tint when diamond (no sharp) vs iron Sharp I are both present. */
    private static String uselessMeleeDiamondVsIronSharpRed = "IRON";
    /** GOLD_WOOD | STONE — which to tint when stone (no sharp) vs gold/wood Sharp I are both present. */
    private static String uselessMeleeStoneVsGoldWoodSharpRed = "GOLD_WOOD";

	/** Ghost preview for Hypixel-style TNT placement (northwest corner of target block). */
	private static boolean tntPlacementPreviewEnabled = true;
	private static boolean chickenPlacementPreviewEnabled = true;
	private static boolean pigPlacementPreviewEnabled = true;
	private static boolean horsePlacementPreviewEnabled = true;
	private static boolean donkeyPlacementPreviewEnabled = true;

	/** Built-in crosshair block overlay (Miscellaneous); replaces external Block Overlay mods. */
	private static boolean gomodBlockOverlayEnabled = true;
	private static String gomodBlockOverlayMode = "FULL";
	private static int gomodBlockOverlayColorArgb = 0xFFDBB8B8;
	private static float gomodBlockOverlayLineWidth = 3.0f;
	private static String gomodBlockOverlayLinePlacement = "OUTSIDE";
	private static boolean gomodBlockOverlayFillEnabled = true;
	private static float gomodBlockOverlayFillOpacity = 0.20f;
    /** Side mode: match stairs/slabs collision shape instead of full 1×1×1 face. */
    private static boolean gomodBlockOverlaySideShapeAware = true;
    /** When true, skip overlay on barrier blocks during Blitz cage phase. */
    private static boolean gomodBlockOverlayHideBarriersInCages = true;
    /** Dashed outline around crosshair-targeted players within melee reach. */
    private static boolean gomodBlockOverlayPlayerOverlayEnabled = false;
    /** Saved sort mode for the Blitz Vault Items GUI. */
    private static String blitzVaultItemsSortMode = "DEFAULT";
    /** Saved custom sort-key chain for the Blitz Vault Items GUI (comma-separated enum names). */
    private static String blitzVaultItemsSortKeys = "";
    /** Saved tooltip toggle for the Blitz Vault Items GUI. */
    private static boolean blitzVaultItemsShowSeenInTooltip = false;
    /** Saved observed-chances category pill for the Blitz Vault Items GUI; empty = Auto. */
    private static String blitzVaultItemsObservedCategory = "";
    /** Saved chest-browser sort mode for the Blitz Vault Items GUI (Chests tab). */
    private static String blitzVaultItemsChestSortMode = "CHEST_NUMBER";
    /** Saved sort mode for the Blitz Chest Items GUI. */
    private static String blitzChestItemsSortMode = "MOST_OBSERVED";
    /** Saved tooltip toggle for the Blitz Chest Items GUI. */
    private static boolean blitzChestItemsShowSeenInTooltip = true;
    /** When true, Blitz Chest Items combines id+meta variants for % stats (renamed gear, XP bottle stacks). */
    private static boolean blitzChestItemsAggregateVariants = false;
    /** When true, Blitz Chest Items combines armor/swords with the same vanilla protection or damage tier. */
    private static boolean blitzChestItemsAggregateCombatTier = false;
    /** Saved chest-browser sort mode for the Blitz Chest Items GUI (Chests tab). */
    private static String blitzChestItemsChestSortMode = "CHEST_NUMBER";
    /** Exact vault item variant keys pinned to the top (green slots). */
    private static java.util.List<String> blitzVaultItemsPreferredKeys = new java.util.ArrayList<String>();
    /** Exact vault item variant keys pushed to the bottom (red slots). */
    private static java.util.List<String> blitzVaultItemsBlacklistedKeys = new java.util.ArrayList<String>();
    /** When false (default), enchant chance rows use at-least level matching (FA I includes FA II + other enchants). */
    private static boolean blitzVaultEnchantChanceExact = false;
    /** Enabled {@link BlitzVaultEnchantChanceRules} keys; empty = built-in default list. */
    private static java.util.List<String> blitzVaultEnchantChanceEnabledKeys = new java.util.ArrayList<String>();
    /** Combo rows: {@code Label|rule_key,rule_key} (AND of rules). */
    private static java.util.List<String> blitzVaultEnchantChanceCombos = new java.util.ArrayList<String>();

	/** Client-only: clip bottom of lightning bolt rendering (see /lightningnerf). */
	private static boolean lightningNerfEnabled = true;
	/** Client-only: stop lightning depth writes so entities stay visible through bolts (MC-44244). */
	private static boolean lightningFixRenderEnabled = true;
	/** How many blocks of bolt height to hide from the bottom (strike anchor). */
	private static int lightningNerfBottomBlocks = 2;
	/** Custom bolt tint instead of vanilla white-blue. */
	private static boolean lightningCustomColorEnabled = false;
	/** Choose and retain a fresh tint for each individual lightning bolt. */
	private static boolean lightningRandomColorEnabled = false;
	/** Bolt tint RGB (alpha ignored; vanilla draw alpha kept). */
	private static int lightningBoltColorArgb = 0xFFE6E6FF;
	/** Thunder bolt opacity multiplier (100 = vanilla alpha). */
	private static float lightningOpacityPercent = 100.0f;
	/** Vertical stretch of bolt segments (100 = vanilla). */
	private static float lightningSpeedPercent = 100.0f;
	/** Thunder volume multiplier (100 = vanilla). */
	private static float lightningSoundVolumePercent = 100.0f;
	/** After Blitz cages open, use alternate thunder sound settings for a short window. */
	private static boolean lightningPostCageSoundEnabled = false;
	/** Seconds after cages open to use post-cage thunder sound (1–60). */
	private static int lightningPostCageSoundDurationSec = 30;
	private static float lightningPostCageSpeedPercent = 100.0f;
	private static float lightningPostCageVolumePercent = 45.0f;

	/** Master toggle for automatic witch SFX + packet-boosted drink/throw/death. */
	private static boolean witchSoundsEnabled = true;
	/** Show Blitz Solo/Teams quick-queue buttons on the main menu (Forge + Lunar). */
	private static boolean mainMenuQueueButtonsEnabled = false;
	/** Per-category volume multipliers (0–1) for automatic witch SFX; idle covers all idle variants. */
	private static float witchSfxVolIdle = 1.0f;
	private static float witchSfxVolHurt = 1.0f;
	private static float witchSfxVolDeath = 1.0f;
	private static float witchSfxVolThrow = 1.0f;
	private static float witchSfxVolDrink = 1.0f;
	private static float witchSfxVolCelebrate = 1.0f;
	/** Client-side Resistance particles while effect is active (/forceres). */
	private static boolean forceResistanceParticlesEnabled = false;
	/** Extra SPELL_MOB spawns per client tick while Resistance is active (0 = none). */
	private static int forceResistanceSpawnsPerTick = 1;
	/** Multiplies {@link #forceResistanceSpawnsPerTick} — “opacity” / strength of the extra layer (0–1.5). */
	private static float forceResistanceOpacityScale = 1.5f;
	/** Multiplies RGB of spawned resistance-tint particles (≈1.0 matches vanilla tint strength). */
	private static float forceResistanceSaturation = 0.75f;
	
	// RadialMenu defaults
	private static boolean radialMenuEnabled = true;
	private static int radialMenuKeyCode = 0; // unbound by default
	private static int chatMovementKeyCode = 0; // unbound by default
	private static boolean chatMovementDragLookEnabled = true;
	/** While held in Chat movement, gameplay binds pass through instead of being typed. */
	private static int chatMovementPassthroughKeyCode = org.lwjgl.input.Keyboard.KEY_LCONTROL;
	/** Key to open gomod Social GUI (set in gomod Service → Social hotkey; not vanilla Controls). 0 = unbound. */
	private static int gomodFriendsListKeyCode = 0;
	/** Optional second key which must be held with {@link #gomodFriendsListKeyCode}. */
	private static int gomodFriendsListSecondKeyCode = 0;
	/** When true, Social key only fires while Ctrl is held (default Ctrl+Tab). */
	private static boolean gomodSocialRequireCtrl = false;

	/** Teams Nick Finder: {@code true} = relative ("18h ago"); {@code false} = date/time in {@link #teamNickFinderZoneId}. */
	private static boolean teamNickFinderLastFoundRelative = true;
	private static boolean teamNickFinder24HourClock = false;
	/** {@code SYSTEM} = JVM default zone; else IANA id (e.g. UTC, Europe/Paris). */
	private static String teamNickFinderZoneId = "UTC";

	// NoHitDelay defaults
	private static boolean noHitDelayEnabled = true; // Remove 1.8 hit delay between attacks

	// CPS cap defaults (0 = disabled)
	private static double cpsLimit = 0.0f; // Maximum attacks per second
	
	// VisibleSpecs defaults
	private static boolean visibleSpecsEnabled = false; // Opt-in: show dead players as visible spectators
	private static boolean seeSpectatorsWhileAlive = true;
	private static boolean forceGameChats = true;
	private static boolean unbotheredTaunt = true;
	/** Highest one-time relay/game-feature default migration applied to the saved config. */
	private static int relayGameFeaturesDefaultsVersion = RelayFeatureDefaultsMigration.CURRENT_VERSION;
	private static boolean spectatorBypassAllInvisible = false; // /spectatorsbypass: show invisible players in active Blitz
	private static String visibleSpecsNametagMode = "ON"; // ON | ONLY_IGN | OFF for spectators revealed by VisibleSpecs
	private static boolean visibleSpecsKillerNametagEnabled = true; // "Killer:" row above spectator nametags
	private static boolean visibleSpecsApplyToReplaysEnabled = true;
	private static float visibleSpecsOpacity = 0.7526041f; // 0.05-1.0

	// SelfOpacity defaults
	private static boolean selfOpacityEnabled = false; // Opt-in: local player model semi-transparency (/opacityme)
	private static float selfOpacityAlpha = 0.75f; // 0.05–1.0 (75% opacity)
	private static float selfOpacityFirstPersonHandAlpha = 1.0f; // First-person hand and held-item opacity
	private static boolean selfOpacityDisableDuringBlindness = true;
	private static int selfOpacityKeyCode = 0;

	// Schizo defaults
	private static boolean schizoEnabled = false; // Force players to face local player when rendered
	
    // BorderIndication defaults
    private static boolean borderIndicationEnabled = false; // Show red overlay on blocks outside border
    private static float borderIndicationOpacity = 0.3f; // Overlay opacity (0.0-1.0)
    private static int borderIndicationRenderDistance = 64; // Render distance in blocks (16-128)

    // StarText defaults
    private static boolean starTextEnabled = true; // Show big title when someone uses Blitz Star
    private static boolean starPredictEnabled;
    /** Held item info (enchantments / potion durations) above the hotbar item-name popup. */
    private static boolean hotbarEnchantTextEnabled = false;
    /** Whether held-item info text uses a shadow. */
    private static boolean hotbarEnchantTextShadow = true;
    /** Enchantment-line RGB for held-item info. Potion rows keep using the Effects HUD styling rules. */
    private static int hotbarEnchantTextColor = 0x00AAAA;
    /** 0 = icon + text, 1 = text only. */
    private static int hotbarEnchantPotionTextStyle = 0;
    /** Screen pixels between the bottom enchant/potion line and the vanilla item-name popup. */
    private static int hotbarEnchantTextGapAboveName = 2;
    /** Mirror non-command chat to Discord DM (Miscellaneous); needs token + recipient in API Keys. */
    private static boolean dcChatMirrorEnabled = false;
    /** Numeric Discord snowflakes for DM recipients; active selection is {@link #dcMirrorSelectedRecipientId}. */
    private static final List<String> dcMirrorRecipientIds = new ArrayList<String>();
    private static String dcMirrorSelectedRecipientId = "";
    /**
     * When {@code true}, Minecraft chat only shows incoming Discord DMs from the currently selected recipient.
     * When {@code false}, shows DMs from everyone (still requires mirror enabled + valid token).
     */
    private static boolean dcMirrorIncomingOnlySelectedRecipient = false;
    private static float starTextDuration = 3.0f; // Duration in seconds
    private static int starTextYOffset = 25; // Vertical offset from center
    private static float starTextScale = 2.3f; // Text scale
    private static boolean lobbyPingDisplayEnabled = true; // Show live lobby ping in nametags and tab
    /** XP bar + level over mount jump bar while riding horse/donkey/mule/pig. */
    private static boolean mountXpOverlayEnabled = true;
    /** 0–255 alpha for mount XP bar overlay (background + fill). */
    private static int mountXpOverlayOpacity = 153;
    /**
     * Orange real-IGN line above world nametags for nicked players we resolved (e.g. TAB {@code nick (real)}).
     * Stays {@code false} for legacy configs that omit the key; set to {@code true} when creating a brand-new config file.
     */
    private static boolean nickRealNametagOverlayEnabled = true;
    /**
     * When the real-IGN nametag overlay is on: show Hypixel {@code [MVP+]} + rank-colored real name (from tabstats API)
     * instead of plain gold text. Requires {@code /tabstats} (or scan) for that player.
     */
    private static boolean nickRealNametagRankPlateEnabled = false;
    /** Master toggle for TAB duplicate denick + real-IGN nametag overlay. */
    private static boolean denickerEnabled = true;
    /** {@code PARENTHESES} = {@code (BuyBrett)}; {@code RANK} = {@code [MVP++] BuyBrett} from Hypixel API cache. */
    private static String denickerTagDisplayMode = "PARENTHESES";
    /** When true, denicked players render their real Mojang skin instead of Hypixel's generated nick skin. */
    private static boolean denickerRevealRealSkinEnabled = false;
    /** When true, append {@code (realIgn)} / rank-style denick tag next to the nick on TAB. */
    private static boolean denickerShowRealIgnInTabEnabled = true;
    /** Alert in chat with a clickable GoStats DB check when a nicked player holds or wears a custom-renamed item. */
    private static boolean nickRenamedItemScannerEnabled = true;
    /** Append clickable [SEARCH NICK] link to self-death kill-count chat lines for nicked killers. */
    private static boolean nickDeathSearchLinkEnabled = true;
    /** Recent Opponents: merge denicked sessions into their resolved real-IGN row. */
    private static boolean recentOpponentsGroupDenickedPlayers = true;
    /** Recent Opponents sort: 0=last seen, 1=most games, 2=best K/D, 3=worst K/D, 4=most fought. */
    private static int recentOpponentsSortMode = 0;
    /**
     * GUI fullscreen dim behind screens. {@code VANILLA} = disabled (vanilla gradient);
     * {@code ALL} = enabled (custom dim/blur; split sliders for gomod vs other GUIs).
     */
    private static String guiBackdropMode = "ALL";
    /** 0–100 dim for {@code me.napim.gomod.gui.*} when custom backdrop applies. */
    private static int guiBackdropGomodDimPercent = 75;
    /** 0–100 blur for gomod GUIs. */
    private static int guiBackdropGomodBlurPercent = 0;
    /** 0–100 dim for vanilla / non-gomod screens when backdrop is enabled. */
    private static int guiBackdropOtherDimPercent = 100;
    /** 0–100 blur for other GUIs when backdrop is enabled. */
    private static int guiBackdropOtherBlurPercent = 0;
    /**
     * When true (and backdrop enabled), blur/dim skip the bottom chat strip for gomod menus in-world
     * and for {@link net.minecraft.client.gui.GuiChat} under “other” rules.
     */
    private static boolean guiBackdropGomodExcludeChatStrip = false;
    /** When true, blur/dim skip the bottom strip on non-gomod GUIs (chat GUI + in-world HUD chat area). */
    private static boolean guiBackdropOtherExcludeChatStrip = false;
    /** When true, HUD editor center crosshair guides show in-game and on all GUIs ({@code /hudeditorlines}). */
    private static boolean hudEditorCenterGuidesAlways = false;
    /** Master switch: when false, date/kit sub-toggles have no effect on the scoreboard. */
    private static boolean hideScoreboardValuesEnabled = true;
    private static boolean hideDateGameIdEnabled = true; // Hide date/gameID lines (requires master on)
    private static boolean hideKitScoreboardEnabled = false; // Hide "Kit: ..." sidebar line (requires master on)
    private static boolean hideHypixelFooterEnabled = true; // Hide "www.hypixel.net" sidebar footer (requires master on)
    private static boolean sidebarBlitzHourLineEnabled = true; // Show remembered Blitz Hour line during the match
    /** When true, draw Blitz {@code KE: Effect} under Kit on the Hypixel sidebar (independent of hide-scoreboard master). */
    private static boolean sidebarKillEffectLineEnabled = true;
    /** When true (and sidebar tweaks master on), rewrite {@code Taunt: USED} → {@code Taunt: WASTED} after a failed taunt kill window. */
    private static boolean sidebarTauntWastedEnabled = true;
    private static boolean mountHeadDimEnabled = true; // Lower opacity of horse/donkey mount in first person
    /** Per-mount-type opacity when transparent mounts are ON (0.05–1.0). Legacy {@code mountHeadDimAlpha} = horse. */
    private static float mountAlphaHorse = 0.45078126f;
    private static float mountAlphaDonkey = 0.48046875f;
    private static float mountAlphaPig = 0.33203125f;
    private static float mountAlphaBoat = 0.33203125f;
    private static float mountAlphaWagon = 0.33203125f;
    private static boolean waterClarityEnabled = false;
    private static boolean nametagThroughBlocksEnabled = true; // Defer player nametags to render after tile entities (fix invisible through chest)
    /** Thinner water tint when the camera is in air (see into lakes from above). */
    private static boolean waterClaritySurfaceFromAir = true;
    /** Legacy: reduce underwater fog + skip vanilla water overlay when submerged. */
    private static boolean waterClarityUnderwaterFog = true;
    private static float waterClarityAmount = 0.19339623f; // 0.0-1.0, higher = clearer (surface + underwater modes)
    /** 1–8: mount dim render path (8 = RenderManager + GlStateManager, default) */
    private static int mountHeadDimStrategy = 8;
    /** 1–6: how the transparent mount interacts with depth (see config / gomod GUI; 6 = hybrid) */
    private static int mountDimDepthMode = 2;
    /**
     * 1–4: when to draw the deferred mount (strategy 8). {@code mountFlushMode} in config.
     * 1 = after entity pass, 2 = after translucent blocks, 3 = no defer, 4 = after full renderWorld.
     */
    private static int mountFlushMode = 1; // default: flush after entity pass (pairs with depth 2)


    // LootCall defaults
    private static boolean lootCallEnabled = false;
    private static boolean lootCallEverywhere = false;
    private static boolean lootCallAnnounceDiamond = true;
    private static boolean lootCallAnnounceIron = true;
    private static boolean lootCallAnnounceGold = true;
    private static boolean teamLootEnabled = false;
    /** Extra blocks above the item anchor for ItemESP / teammate loot labels (nametag height tuning). */
    private static float itemEspLabelExtraYOffset = 0.26f;
    private static boolean askForLootEnabled = true;
    private static boolean askForLootAllTime = false;
    private static int askForLootKeyCode = 0;
    private static boolean playerCallerEnabled = true;
    private static int playerCallerKeyCode = 0;
    private static boolean chestRoutesRenderEnabled = false;
    private static int chestRoutesPlaceFeetKeyCode = 0;
    private static int chestRoutesToggleKeyCode = 0;
    private static int chestRoutesUndoKeyCode = 0;

    /** When true, barrier blocks render with the barrier item texture (builder / map walls). */
    private static boolean barrierVisibilityEnabled = true;
    private static int barrierVisibilityKeyCode = 0;
    /** Barrier block texture style — see {@link me.napim.gomod.features.BarrierVisibilityStyles}. */
    public static final String BARRIER_STYLE_FULL = BarrierVisibilityStyles.FULL;
    private static String barrierVisibilityStyle = BARRIER_STYLE_FULL;
    /** OUTLINE-style perimeter thickness (in source pixels at 16×16 base). */
    private static int barrierOutlineThickness = 1;
    /** OUTLINE-style perimeter color (full ARGB, alpha pre-multiplied into the high byte). */
    private static int barrierOutlineColorArgb = -16736001;
    /** When true, barriers are only meshed in the per-block proximity check below. */
    private static boolean barrierVisibilityRangeEnabled = true;
    /** Block radius (Euclidean) within which barriers stay visible when range mode is on. */
    private static int barrierVisibilityRange = 13;
    public static final int BARRIER_RANGE_MIN = 1;
    public static final int BARRIER_RANGE_MAX = 150;
    /** Barrier texture opacity (10–100%). 100 = fully opaque texels; lower values scale alpha for the cutout pass. */
    public static final int BARRIER_OPACITY_MIN = 10;
    public static final int BARRIER_OPACITY_MAX = 100;
    private static int barrierVisibilityOpacityPercent = 100;
    /** When true, the barrier the player is currently aiming at (and a small ring around it)
     *  stays visible even if "nearby only" would otherwise hide it. */
    private static boolean barrierVisibilityShowAtCursor = true;
    /** Fourth show-mode for barriers: only render the barrier(s) directly under the crosshair.
     *  Mutually exclusive with the regular ON / Nearby only modes from the GUI cycle. */
    private static boolean barrierVisibilityAimedOnly = false;
    /** When false, barrier visibility is suppressed during Blitz cage phase (cage walls stay vanilla). */
    private static boolean barrierBlocksShownInCages = false;

    // ── Blocks Finder ──────────────────────────────────────────────────────
    private static boolean blocksFinderEnabled       = false;
    private static int     blocksFinderKeyCode       = 0;
    private static String  blocksFinderEspMode       = "OUTLINE_FILLED";
    private static int     blocksFinderColorR        = 0;
    private static int     blocksFinderColorG        = 255;
    private static int     blocksFinderColorB        = 255;
    private static int     blocksFinderFillAlpha     = 70;
    /** 0–100: opacity of non-target blocks while Block Finder is on (100 = normal). */
    private static int     blocksFinderWorldOpacity  = 100;
    /** Hold + mouse wheel to adjust {@link #blocksFinderWorldOpacity} in-game (default G). */
    private static int     blocksFinderWorldOpacityKeyCode = org.lwjgl.input.Keyboard.KEY_G;
    /** @deprecated kept for config migration only; scan uses render distance */
    private static int     blocksFinderRange         = 32;
    private static List<String> blocksFinderBlocks   = new ArrayList<String>();
    /** Per-block ESP color overrides (registry id → 0xRRGGBB). */
    private static final Map<String, Integer> blocksFinderBlockColors = new HashMap<String, Integer>();
    /** Shipped defaults for common Block Finder targets (used when no override is saved). */
    private static final Map<String, Integer> BLOCKS_FINDER_BUILTIN_BLOCK_COLORS = new HashMap<String, Integer>();

    static {
        BLOCKS_FINDER_BUILTIN_BLOCK_COLORS.put("minecraft:trapped_chest", 0x008CD2);
        BLOCKS_FINDER_BUILTIN_BLOCK_COLORS.put("minecraft:crafting_table", 0x1CE085);
        BLOCKS_FINDER_BUILTIN_BLOCK_COLORS.put("minecraft:enchanting_table", 0xFF0000);
        BLOCKS_FINDER_BUILTIN_BLOCK_COLORS.put("minecraft:chest", 0x00FFFF);
        blocksFinderBlocks.add("minecraft:trapped_chest");
        blocksFinderBlocks.add("minecraft:crafting_table");
        blocksFinderBlocks.add("minecraft:enchanting_table");
        blocksFinderBlocks.add("minecraft:chest");
        for (java.util.Map.Entry<String, Integer> e : BLOCKS_FINDER_BUILTIN_BLOCK_COLORS.entrySet()) {
            blocksFinderBlockColors.put(e.getKey(), e.getValue());
        }
    }

    /** More HUD (FPS / CPS / ping / effects / arrows) — anchored from screen center. */
    /** Default ON so first-run / missing {@code moreHud} JSON still shows the overlay (rows default on). */
    private static boolean moreHudEnabled = true;
    /** Author default layout matches shipped first-run / {@code default} profile snapshot (More HUD block). */
    private static int moreHudOffsetCenterX = 0;
    private static int moreHudPosY = 22;
    private static int moreHudRowGap = 2;
    private static float moreHudScale = 1.0f;
    private static boolean moreHudFps = true;
    private static boolean moreHudFpsShadow = true;
    private static int moreHudFpsBg = 0;
    private static int moreHudFpsBgColor = 0;
    private static int moreHudFpsPrefixColor = 0xFFFFFF;
    private static int moreHudFpsColor = 0xFFFFFF;
    /** 0 = {@code FPS: N}, 1 = {@code FPS N}, 2 = {@code N FPS}, 3 = custom ({@link #moreHudFpsCustomLabel}), 4 = {@code N f/s}. */
    private static int moreHudFpsDisplayStyle = 0;
    /** Use {@code {value}} for the live FPS number. */
    private static String moreHudFpsCustomLabel = "FPS: {value}";
    private static boolean moreHudCps = true;
    private static boolean moreHudCpsShadow = true;
    private static int moreHudCpsBg = 0;
    private static int moreHudCpsBgColor = 0;
    private static int moreHudCpsColor = 0xFFFFFF;
    private static int moreHudCpsPrefixColor = 0xFFFFFF;
    /** Color of the {@code |} between left and right CPS (both mode). */
    private static int moreHudCpsSeparatorColor = 0xFFFFFF;
    /** 0 = LMB only, 1 = RMB only, 2 = both */
    private static int moreHudCpsMode = 2;
    private static boolean moreHudCpsSquareBrackets = true;
    private static boolean moreHudPing = true;
    private static boolean moreHudPingShadow = true;
    private static int moreHudPingBg = 0;
    private static int moreHudPingBgColor = 0;
    private static int moreHudPingColor = 0xFFFFFF;
    private static int moreHudPingPrefixColor = 0xFFFFFF;
    /** 0 = {@code Nms}, 1 = {@code Ping: Nms}, 2 = {@code Ping: N}, 3 = custom ({@link #moreHudPingCustomLabel}). */
    private static int moreHudPingDisplayStyle = 0;
    /** Use {@code {value}} for the live ping number. */
    private static String moreHudPingCustomLabel = "Ping: {value}";
    private static boolean moreHudPotions = true;
    private static boolean moreHudPotionsShadow = true;
    private static int moreHudPotionsBg = 0;
    private static int moreHudPotionsBgColor = 0;
    private static int moreHudPotionsColor = 0xFFFFFF;
    /**
     * When true, effect names use {@link #moreHudPotionsEffectColorById} (editable in Effects HUD color settings);
     * when false, all effect names use {@link #moreHudPotionsColor}.
     */
    private static boolean moreHudPotionsEffectColors = true;
    /** Duration / timer line color for Effects HUD rows (0xRRGGBB). */
    private static int moreHudPotionsDurationColor = 0xFFFFFF;
    /** Per-effect name colors by potion id (index = id, 0 unused). Length {@link #MORE_HUD_POTION_COLOR_SLOTS}. */
    private static int[] moreHudPotionsEffectColorById;
    /** Vanilla potion ids 1–23 plus {@link BlitzStarEffectsHudTracker#COLOR_SLOT_COUNT} Blitz star effect rows. */
    public static final int MORE_HUD_POTION_COLOR_SLOTS = BlitzStarEffectsHudTracker.COLOR_SLOT_BASE
            + BlitzStarEffectsHudTracker.COLOR_SLOT_COUNT;
    private static final int DEFAULT_MORE_HUD_POTIONS_DURATION_COLOR = 0xFFFFFF;
    /** Vanilla 1.8.9 status effect name colors by id (index 0 unused). */
    private static final int[] VANILLA_POTION_EFFECT_NAME_RGB = new int[] {
            0,
            0x70DEFF, 0x5A6C81, 0xD9C043, 0x4A4219, 0x932423, 0xF82423, 0x430A09, 0x22FF4C,
            0xB35300, 0xFA5CFA, 0x99453A, 0xE49A3A, 0x2E5299, 0x7F8392, 0x1F1F23, 0x1F1FA1,
            0x587653, 0x484D48, 0x4E9331, 0x352A27, 0xF87D23, 0x2552A5, 0x875200,
    };
    /**
     * Kill-effect Flaming Arrows row: when true, proc is merged into the Effects HUD ({@linkplain #moreHudFlameCompact}
     * stays off). When false, Flaming Arrows uses the separate compact HUD ({@code moreHudFlameCompact} on) and is not
     * drawn inside the Effects HUD block.
     */
    private static boolean moreHudPotionsShowFlamingArrows = true;
    /**
     * When true ("Detailed"), Effects HUD durations use tenths (e.g. {@code 19.9}). When false ("Vanilla"), use
     * {@code M:SS} (e.g. {@code 0:20}) for potions and merged Flaming Arrows where applicable.
     */
    private static boolean moreHudPotionsDecimalEffectTimer = false;
    /** Effects HUD always uses one shared background behind all rows (separated per-row style removed). */
    private static final boolean moreHudPotionsBgAttached = true;
    /** Powns-style coordinates HUD (X/Y/Z, biome, direction). */
    private static boolean moreHudCoords = true;
    private static boolean moreHudCoordsShadow = true;
    private static int moreHudCoordsBg = 0;
    private static int moreHudCoordsBgColor = 0;
    private static int moreHudCoordsPrefixColor = 0xFFFFFF;
    private static int moreHudCoordsTextColor = 0xFFFFFF;
    private static int moreHudCoordsBiomeColor = 0xFFFFFF;
    /** Vertical facing label (NE, …) and compass N/S/E/W letters. */
    private static int moreHudCoordsDirectionColor = 0xFFFFFF;
    private static int moreHudCoordsCompassRingColor = 0xAAAAAA;
    private static int moreHudCoordsCompassDotColor = GuiHeader.GOMOD_BRAND_RGB;
    public static final int COORDS_LAYOUT_VERTICAL = 0;
    public static final int COORDS_LAYOUT_COMPASS = 1;
    public static final int COORDS_LAYOUT_COMPASS_ONLY = 2;
    /** {@link #COORDS_LAYOUT_VERTICAL}, {@link #COORDS_LAYOUT_COMPASS}, or {@link #COORDS_LAYOUT_COMPASS_ONLY}. */
    private static int moreHudCoordsLayout = COORDS_LAYOUT_COMPASS;
    private static boolean moreHudCoordsBiome = false;
    private static boolean moreHudCoordsDirection = true;
    /** When false, the Coords HUD hides while the vanilla F3 debug screen is open. */
    private static boolean moreHudCoordsShowDuringF3 = false;
    private static boolean moreHudCoordsShowFace = true;
    /** 0=Regular, 1=Regular no-colon, 2=Parentheses, 3=Square brackets, 4=None, 5=Arrow — {@link MoreHudCoordsFeature.PrefixStyle}. */
    private static int moreHudCoordsPrefixStyle = 0;
    private static final int COORDS_PREFIX_STYLE_SCHEMA = 2;
    private static int moreHudCoordsDecimals = 0;
    private static int moreHudCoordsPosY = -2;
    private static int moreHudCoordsOffsetCenterX = -427;
    private static float moreHudCoordsScale = 0.75f;
    /** Unscaled offset for compass ring (horizontal layout); applied inside row scale. */
    private static int moreHudCoordsCompassNudgeX = 6;
    private static int moreHudCoordsCompassNudgeY = 0;
    /** Pixels added to compass ring radius (debug / tune via /coordscompassring). */
    private static int moreHudCoordsCompassRingRadiusOffset = MoreHudCoordsFeature.DEFAULT_COMPASS_RING_RADIUS_OFFSET;
    /** Pixels added to heading-dot orbit radius (/coordscompassdot). */
    private static int moreHudCoordsCompassDotOrbitOffset = MoreHudCoordsFeature.DEFAULT_COMPASS_DOT_ORBIT_OFFSET;
    /** Pixels cardinals sit farther from center (/coordscompasscard). */
    private static int moreHudCoordsCompassCardinalOutset = MoreHudCoordsFeature.DEFAULT_COMPASS_CARDINAL_OUTSET;
    private static boolean moreHudArrows = true;
    private static boolean moreHudArrowsShadow = true;
    private static int moreHudArrowsBg = 0;
    private static int moreHudArrowsBgColor = 0;
    private static int moreHudArrowsColor = 0xFFFFFF;
    /** Overlay color when arrow count is zero. */
    private static int moreHudArrowsZeroColor = 0xFF5555;
    /** Unscaled overlay offset for arrow stack count (e.g. "0"); applied inside row scale. */
    private static int moreHudArrowsOverlayDx = 3;
    private static int moreHudArrowsOverlayDy = 4;
    private static boolean moreHudArmorStatus = true;
    private static boolean moreHudArmorStatusShadow = true;
    private static int moreHudArmorStatusBg = 0;
    private static int moreHudArmorStatusBgColor = 0;
    private static int moreHudArmorStatusPosY = 374;
    private static int moreHudArmorStatusOffsetCenterX = 418;
    private static float moreHudArmorStatusScale = 1.0f;
    private static float moreHudArmorStatusTextScale = MoreHudArmorStatusFeature.ARMOR_TEXT_SCALE_DEFAULT;
    /** 0–3: {@link MoreHudArmorStatusFeature.DurabilityMode}. */
    private static int moreHudArmorStatusDuraMode = 1;
    private static boolean moreHudArmorStatusDuraRight = true;
    private static boolean moreHudArmorStatusDurabilityBar = true;
    /** 0–3: {@link MoreHudArmorStatusFeature.LayoutMode}. */
    private static int moreHudArmorStatusLayout = MoreHudArmorStatusFeature.LayoutMode.VERTICAL.getIndex();
    /** True = stacked vertically, false = laid out horizontally. */
    private static boolean moreHudArmorStatusVertical = true;
    private static boolean moreHudArmorStatusChroma = false;
    private static boolean moreHudArmorStatusShowHelmet = true;
    private static boolean moreHudArmorStatusShowChest = true;
    private static boolean moreHudArmorStatusShowLegs = true;
    private static boolean moreHudArmorStatusShowBoots = true;
    private static boolean moreHudArmorStatusShowHeld = true;
    /** 0 = below armor, 1 = above armor, 2 = hidden. */
    private static int moreHudArmorStatusHeldPlacement = 0;
    /** Legacy active armor gap for config compatibility; source of truth is the per-layout fields below. */
    private static int moreHudArmorStatusArmorSlotGap = MoreHudArmorStatusFeature.ARMOR_SLOT_GAP_DEFAULT;
    private static int moreHudArmorStatusArmorSlotGapVertical = MoreHudArmorStatusFeature.ARMOR_SLOT_GAP_DEFAULT;
    private static int moreHudArmorStatusArmorSlotGapHorizontal = MoreHudArmorStatusFeature.ARMOR_SLOT_GAP_HORIZONTAL_MAX;
    public static final int MORE_HUD_ARMOR_HELD_DOWN = 0;
    public static final int MORE_HUD_ARMOR_HELD_ABOVE = 1;
    public static final int MORE_HUD_ARMOR_HELD_OFF = 2;
    /** Tier 1–4 durability text colors (RGB). */
    private static int moreHudArmorStatusDuraColor1 = 0x00A000;
    private static int moreHudArmorStatusDuraColor2 = 0xDCD200;
    private static int moreHudArmorStatusDuraColor3 = 0xBE7800;
    private static int moreHudArmorStatusDuraColor4 = 0xA00000;
    private static boolean moreHudFlame = true;
    private static boolean moreHudKillEffects = true;
    /** Row background alpha (0–255) for non–Flaming Arrows kill effects HUD (icon + timer). */
    private static int moreHudKillEffectsBg = 0;
    private static int moreHudKillEffectsBgColor = 0;
    /** Text shadow for non–Flaming Arrows kill effects timer text. */
    private static boolean moreHudKillEffectsShadow = true;
    private static boolean moreHudFlameShadow = true;
    private static int moreHudFlameBg = 0;
    private static int moreHudFlameBgColor = 0;
    private static int moreHudFlameColor = 0xAA0000;
    private static int moreHudKillEffectSpeedColor = 0x55FFFF;
    private static int moreHudKillEffectResistanceColor = 0xFFAA00;
    private static int moreHudKillEffectRegenerationColor = 0xFF55FF;
    /** When true, Flaming Arrows HUD shows a depleting bar instead of seconds text. */
    private static boolean moreHudFlameTimerBar = true;
    /** Per-row anchor Y / horizontal offset from screen center (HUD Editor). */
    private static int moreHudFpsPosY = 453;
    private static int moreHudCpsPosY = 472;
    private static int moreHudPingPosY = 462;
    private static int moreHudPotionsPosY = 271;
    private static int moreHudArrowsPosY = 458;
    private static int moreHudFlamePosY = 197;
    private static int moreHudKillEffectsPosY = 269;
    private static int moreHudFpsOffsetCenterX = 113;
    private static int moreHudCpsOffsetCenterX = 113;
    private static int moreHudPingOffsetCenterX = 113;
    private static int moreHudPotionsOffsetCenterX = -416;
    private static int moreHudArrowsOffsetCenterX = -120;
    private static int moreHudFlameOffsetCenterX = 0;
    private static int moreHudKillEffectsOffsetCenterX = -16;
    /** Per-row scale (HUD Editor resize); defaults to legacy {@link #moreHudScale} when missing in JSON. */
    private static float moreHudFpsScale = 0.7826087f;
    private static float moreHudCpsScale = 0.7794118f;
    private static float moreHudPingScale = 0.7786458f;
    private static float moreHudPotionsScale = 1.0f;
    private static float moreHudArrowsScale = 0.85f;
    private static float moreHudFlameScale = 1.0f;
    private static float moreHudKillEffectsScale = 1.0f;
    private static float moreHudKillEffectsIconScale = 0.6003686f;
    private static float moreHudKillEffectsTimerScale = 0.8333333f;
    private static int moreHudKillEffectsIconNudgeX = 16;
    private static int moreHudKillEffectsIconNudgeY = -5;
    private static int moreHudKillEffectsTimerNudgeX = -1;
    private static int moreHudKillEffectsTimerNudgeY = 8;
    private static boolean moreHudFlameCompact = true;
    private static int moreHudFlameCompactPosY = 195;
    private static int moreHudFlameCompactOffsetCenterX = 0;
    private static float moreHudFlameCompactScale = 1.9f;
    /** Per-part scale inside compact row (HUD Editor corners on icon / timer). */
    private static float moreHudFlameCompactIconScale = 0.35f;
    private static float moreHudFlameCompactTimerScale = 0.35f;
    /** Pixel nudges for compact flame icon vs timer (free placement inside the compact group). */
    private static int moreHudFlameCompactIconNudgeX = 12;
    private static int moreHudFlameCompactIconNudgeY = 3;
    private static int moreHudFlameCompactTimerNudgeX = -5;
    private static int moreHudFlameCompactTimerNudgeY = 8;
    /** When true: {@code Ping: 42ms}; when false: {@code 42ms} only. */
    /** @deprecated migrated to {@link #moreHudPingDisplayStyle} */
    private static boolean moreHudPingPrefix = false;

    // Disguise defaults
    private static String disguiseKillCounterMode = "FOLLOW";
    private static String disguiseTournamentKillCounterMode = "FOLLOW";
    /** Last value from Disguise GUI "Custom Kill Count" (BSG2-style display). */
    private static int disguiseCustomKillCount = 0;
    /** When true, TAB/chat use {@link #disguiseCustomKillCount} for your row (works without disguise). */
    private static boolean disguiseCustomKillCountEnabled = false;
    private static boolean disguiseBlitzKitSelectorEnabled = false;
    private static String disguiseRenamedItemsMode = "UNCHANGED";
    private static String disguiseHypixelPrefixMode = "SHOW";
    /**
     * Rank tag when Hypixel prefix is shown: TARGET (API), NONE, VIP, VIP_PLUS, MVP, MVP_PLUS, MVP_PLUS_PLUS,
     * YOUTUBER, OWNER, ADMIN, GM, MOJANG, EVENTS, INNIT, PIG_PLUS_PLUS_PLUS, CUSTOM.
     */
    private static String disguiseHypixelRankPreset = "TARGET";
    /** Hypixel API-style color name for MVP+ {@code +} sign (e.g. GOLD, RED). */
    private static String disguiseHypixelMvpPlusColor = "GOLD";
    /** Official {@code [MVP]} bracket color ({@code getMVPPlusPlusColor} keys). */
    private static String disguiseHypixelMvpBracketColor = "AQUA";
    /** Official MVP+ bracket/base color (letters around the {@code +}; same key set as MVP++). */
    private static String disguiseHypixelMvpPlusBracketBaseColor = "AQUA";
    /** MVP++ bracket/base color (monthlyRankColor mapping). */
    private static String disguiseHypixelMvpPpBaseColor = "GOLD";
    /** MVP++ {@code ++} sign color (rankPlusColor mapping). */
    private static String disguiseHypixelMvpPpSignColor = "GOLD";
    /** When preset is CUSTOM: literal tag with & color codes, e.g. {@code &c[&6VIP&c+]} */
    private static String disguiseHypixelRankCustom = "&7[Custom]";
    /** Last nick disguise setup (draft / reuse). */
    private static String disguiseNickSetupLastNick = "";
    private static String disguiseNickSetupLastSkinMode = "STEVE";
    private static String disguiseNickSetupLastSkinRef = "";
    /** When true, re-apply last saved disguise after joining a world (e.g. after game restart). */
    private static boolean disguiseRestoreOnReconnect = false;
    /** Lunar/Weave only: hide the local account's Lunar cosmetic layers while a disguise is active. */
    private static boolean disguiseHideLunarCosmeticsOnWeave = true;
    /** Tracks the one-time migration from the former Shown default. */
    private static boolean disguiseHideLunarCosmeticsDefaultMigrated = false;
    /** Wear a chosen Mojang cape without an active disguise. */
    private static boolean playerStandaloneCapeEnabled = false;
    private static String playerStandaloneCapeOptionKey = "";
    private static final java.util.LinkedHashMap<String, String> playerStandaloneCapeOptionKeyByAccount =
            new java.util.LinkedHashMap<String, String>();
    /** One-time migration for configs written before standalone cape selection was per-account. */
    private static boolean playerStandaloneCapeLegacySelectionPending = false;
    /** When true, other gomod users on the same server see your standalone cape wear via relay. */
    private static boolean playerStandaloneCapeBroadcastToPeers = true;
    /** Weave/Lunar only: prefer gomod's selected cape over Lunar's separately rendered cloak cosmetic. */
    /** True when the standalone cape picker should show its Gomod selection instead of the native/default cape. */
    private static boolean playerStandaloneCapePreferGomodOnWeave = true;
    /** Master switch for the Lunar/Weave cosmetic integration. */
    private static boolean lunarTweaksEnabled = true;
    /** Whether this client applies Lunar tweak state received from other gomod players. */
    private static boolean lunarTweaksSeeGomodPlayers = true;
    /** Whether this client advertises its Lunar tweak state to other gomod players. */
    private static boolean lunarTweaksShowToOtherPlayers = true;
    /** Force the native Cloth cloak "Adds additional physics" setting on or off. */
    private static boolean lunarClothCloakEnabled = true;
    /** Weave/Lunar only: keep already-equipped items when equipping another item of the same category. */
    private static boolean lunarTweaksAllowMultipleSameTypeCosmetics = false;
    /** Internal Weave/Lunar dev tool: add Limited IDs and clear expired rows in the local Locker response. */
    private static boolean lunarLimitedCosmeticsUnlockerEnabled = false;
    /** Weave/Lunar only: show local source badges on Locker cosmetic preview cards. */
    private static boolean lunarCosmeticSourceBadgesEnabled = false;
    /** Weave/Lunar only: usernames whose remote Lunar cosmetic layers/companions are hidden locally. */
    private static final ArrayList<String> lunarTweaksHiddenCosmeticPlayers = new ArrayList<String>();
    /** Weave/Lunar only: hide the local Lunar+ plus glyph without changing account entitlement. */
    private static boolean lunarPlusHideBadge = false;
    /** OFF | PLAYER | NICK — last disguise to restore (see disguiseReconnect* fields). */
    private static String disguiseReconnectKind = "OFF";
    private static String disguiseReconnectPlayerName = "";
    /** LOCAL | GOMOD_NAME | FULL_LOCAL | GOMOD_FULL_LUNAR. */
    private static String disguiseReconnectShareMode = "LOCAL";
    /** UUID for the player whose full Lunar outfit should be restored, when applicable. */
    private static String disguiseReconnectFullLunarUuid = "";
    private static String disguiseReconnectNick = "";
    /** Exact nick field text (may include & codes); restore uses this when non-empty. */
    private static String disguiseReconnectNickRaw = "";
    /** STEVE | PLAYER_SKIN | SKIN_FILE */
    private static String disguiseReconnectNickSkinMode = "STEVE";
    private static String disguiseReconnectNickSkinRef = "";

    /**
     * Loads the configuration from the config file.
     * If the file doesn't exist, default values are used.
     */
    public static synchronized void loadConfig() {
        if (loading) {
            return;
        }
        loading = true;
        try {
        boolean migrateLunarCosmeticsDefault = false;
        boolean migrateRelayGameFeatureDefaults = false;
        boolean migrateReplayHealthTagsDefaults = false;
        boolean migrateKillCounterKeAllChatDefaults = false;
        boolean normalizePlayerNameDisplayModes = false;
        File configDirFile = getGomodDataDirectory();
        if (!configDirFile.exists()) {
            boolean dirCreated = configDirFile.mkdirs();
            if (dirCreated) {
                System.out.println("[ConfigManager] Created config directory: " + configDirFile.getAbsolutePath());
            } else {
                System.out.println("[ConfigManager] Failed to create config directory: " + configDirFile.getAbsolutePath());
            }
        }

        File configFile = getConfigFile();
        if (!configFile.exists()) {
            File weaveGomod123 = new File(GomodPaths.getWeaveLegacyGomod123Dir(), "gomodConfig.json");
            if (weaveGomod123.exists()) {
                try {
                    if (configFile.getParentFile() != null) {
                        Files.createDirectories(configFile.getParentFile().toPath());
                    }
                    Files.copy(weaveGomod123.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[ConfigManager] Migrated config from .weave/gomod123 to " + configFile.getAbsolutePath());
                } catch (IOException migrationError) {
                    System.err.println("[ConfigManager] Failed to migrate from .weave/gomod123: " + migrationError.getMessage());
                }
            }
        }
        if (!configFile.exists()) {
            File legacyWeaveGomod = new File(GomodPaths.getWeaveLegacyGomodDir(), "gomodConfig.json");
            if (legacyWeaveGomod.exists()) {
                try {
                    if (configFile.getParentFile() != null) {
                        Files.createDirectories(configFile.getParentFile().toPath());
                    }
                    Files.copy(legacyWeaveGomod.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[ConfigManager] Migrated legacy config from .weave/gomod to " + configFile.getAbsolutePath());
                } catch (IOException migrationError) {
                    System.err.println("[ConfigManager] Failed to migrate legacy .weave/gomod config: " + migrationError.getMessage());
                }
            }
        }
        if (!configFile.exists()) {
            // First-time install: no config file yet (after optional Weave migration attempts).
            relayGameFeaturesDefaultsVersion = RelayFeatureDefaultsMigration.CURRENT_VERSION;
            forceReplayPlayerHealthTagsDefaultsVersion = ReplayHealthTagsDefaultsMigration.CURRENT_VERSION;
            killCounterShowKeAllChatDefaultsVersion = KillCounterKeAllChatDefaultsMigration.CURRENT_VERSION;
            nickRealNametagOverlayEnabled = true;
            applyDefaultMoreHudRowPositions();
            applyAuthorDefaultMoreHudScalesOnly();
            applyUserHudDefaultsIfPresent();
            disguiseHideLunarCosmeticsDefaultMigrated = true;
            loadedFromCorruptConfig = false;
            loaded = true;
            saveConfig();
            return;
        }
        
        try (BufferedReader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            JsonParser parser = new JsonParser();
            config = parser.parse(reader).getAsJsonObject();
            migrateRelayGameFeatureDefaults = RelayFeatureDefaultsMigration.migrate(config);
            relayGameFeaturesDefaultsVersion = RelayFeatureDefaultsMigration.readVersion(config);
            migrateReplayHealthTagsDefaults = ReplayHealthTagsDefaultsMigration.migrate(config);
            forceReplayPlayerHealthTagsDefaultsVersion = ReplayHealthTagsDefaultsMigration.readVersion(config);
            migrateKillCounterKeAllChatDefaults = KillCounterKeAllChatDefaultsMigration.migrate(config);
            killCounterShowKeAllChatDefaultsVersion = KillCounterKeAllChatDefaultsMigration.readVersion(config);
            
            // Load auto-queue settings (per-mode enable + migrate legacy single flag)
            boolean legacyAutoQueue = config.has("autoQueueEnabled") && config.get("autoQueueEnabled").getAsBoolean();
            if (config.has("autoQueueSoloEnabled")) {
                autoQueueSoloEnabled = config.get("autoQueueSoloEnabled").getAsBoolean();
            } else {
                autoQueueSoloEnabled = legacyAutoQueue;
            }
            if (config.has("autoQueueTeamsEnabled")) {
                autoQueueTeamsEnabled = config.get("autoQueueTeamsEnabled").getAsBoolean();
            } else {
                autoQueueTeamsEnabled = legacyAutoQueue;
            }
            if (config.has("autoQueueSoloDelay")) {
                autoQueueSoloDelay = config.get("autoQueueSoloDelay").getAsFloat();
            }
            if (config.has("autoQueueTeamsDelay")) {
                autoQueueTeamsDelay = config.get("autoQueueTeamsDelay").getAsFloat();
            }
            
            // Load death requeue settings
            boolean legacyDeathRequeue = config.has("deathRequeueEnabled") && config.get("deathRequeueEnabled").getAsBoolean();
            if (config.has("deathRequeueSoloEnabled")) {
                deathRequeueSoloEnabled = config.get("deathRequeueSoloEnabled").getAsBoolean();
            } else {
                deathRequeueSoloEnabled = legacyDeathRequeue;
            }
            if (config.has("deathRequeueTeamsEnabled")) {
                deathRequeueTeamsEnabled = config.get("deathRequeueTeamsEnabled").getAsBoolean();
            } else {
                deathRequeueTeamsEnabled = legacyDeathRequeue;
            }
            if (config.has("deathRequeueSoloDelay")) {
                deathRequeueSoloDelay = config.get("deathRequeueSoloDelay").getAsFloat();
            }
            if (config.has("deathRequeueTeamsDelay")) {
                deathRequeueTeamsDelay = config.get("deathRequeueTeamsDelay").getAsFloat();
            }
            
            // Load inventory HUD settings
            if (config.has("inventoryHUDEnabled")) {
                inventoryHUDEnabled = config.get("inventoryHUDEnabled").getAsBoolean();
            }
            if (config.has("inventoryHUDShowEmpty")) {
                inventoryHUDShowEmpty = config.get("inventoryHUDShowEmpty").getAsBoolean();
            }
            if (config.has("inventoryHUDCustomX")) {
                inventoryHUDCustomX = config.get("inventoryHUDCustomX").getAsInt();
            }
            if (config.has("inventoryHUDCustomY")) {
                inventoryHUDCustomY = config.get("inventoryHUDCustomY").getAsInt();
            }
            if (config.has("inventoryHUDScale")) {
                inventoryHUDScale = config.get("inventoryHUDScale").getAsFloat();
            }
            if (config.has("inventoryHUDBackgroundOpacity")) {
                inventoryHUDBackgroundOpacity = config.get("inventoryHUDBackgroundOpacity").getAsInt();
            }
            if (config.has("inventoryHUDForce")) {
                inventoryHUDForce = config.get("inventoryHUDForce").getAsBoolean();
            }
            friendInvHudEnabled = readJsonBoolPreferKey(config, "friendInvHudEnabled", "teammateInvHudEnabled", friendInvHudEnabled);
            friendInvHudShowEmpty = readJsonBoolPreferKey(config, "friendInvHudShowEmpty", "teammateInvHudShowEmpty", friendInvHudShowEmpty);
            friendInvHudCustomX = readJsonIntPreferKey(config, "friendInvHudCustomX", "teammateInvHudCustomX", friendInvHudCustomX);
            friendInvHudCustomY = readJsonIntPreferKey(config, "friendInvHudCustomY", "teammateInvHudCustomY", friendInvHudCustomY);
            friendInvHudScale = readJsonFloatPreferKey(config, "friendInvHudScale", "teammateInvHudScale", friendInvHudScale);
            friendInvHudBackgroundOpacity = readJsonIntPreferKey(config, "friendInvHudBackgroundOpacity", "teammateInvHudBackgroundOpacity", friendInvHudBackgroundOpacity);
            friendInvHudBackgroundColor = readJsonIntPreferKey(config, "friendInvHudBackgroundColor", "teammateInvHudBackgroundColor", friendInvHudBackgroundColor);
            friendInvHudForce = readJsonBoolPreferKey(config, "friendInvHudForce", "teammateInvHudForce", friendInvHudForce);
            friendInvHudShowRemoteHealth = readJsonBoolPreferKey(config, "friendInvHudShowRemoteHealth", "teammateInvHudShowRemoteHealth", friendInvHudShowRemoteHealth);
            friendInvHudShowRemoteFood = readJsonBoolPreferKey(config, "friendInvHudShowRemoteFood", "teammateInvHudShowRemoteFood", friendInvHudShowRemoteFood);
            friendInvHudShowRemoteXp = readJsonBoolPreferKey(config, "friendInvHudShowRemoteXp", "teammateInvHudShowRemoteXp", friendInvHudShowRemoteXp);
            friendInvHudShowHeldHighlight = readJsonBoolPreferKey(config, "friendInvHudShowHeldHighlight", "teammateInvHudShowHeldHighlight", friendInvHudShowHeldHighlight);
            friendInvHudShowDirection = readJsonBoolPreferKey(config, "friendInvHudShowDirection", "teammateInvHudShowDirection", friendInvHudShowDirection);
            friendInvHudDirectionVertical = readJsonBoolPreferKey(config, "friendInvHudDirectionVertical", "teammateInvHudDirectionVertical", friendInvHudDirectionVertical);
            friendInvHudTextShadow = readJsonBoolPreferKey(config, "friendInvHudTextShadow", "teammateInvHudTextShadow", friendInvHudTextShadow);

            // Load perspective distance
            if (config.has("perspectiveDistance")) {
                perspectiveDistance = config.get("perspectiveDistance").getAsFloat();
            }

            // Load perspective feature toggles
            if (config.has("perspectiveEnabled")) {
                perspectiveEnabled = config.get("perspectiveEnabled").getAsBoolean();
            }
            if (config.has("perspectiveScrollStep")) {
                perspectiveScrollStep = config.get("perspectiveScrollStep").getAsFloat();
            }
            if (config.has("perspectiveResetOnExit")) {
                perspectiveResetOnExit = config.get("perspectiveResetOnExit").getAsBoolean();
            }
            if (config.has("perspectiveResetKeyCode")) {
                perspectiveResetKeyCode = config.get("perspectiveResetKeyCode").getAsInt();
            }
            if (config.has("perspectiveRequireHold")) {
                perspectiveRequireHold = config.get("perspectiveRequireHold").getAsBoolean();
            }
            if (config.has("perspectiveSmoothZoom")) {
                perspectiveSmoothZoom = config.get("perspectiveSmoothZoom").getAsBoolean();
            }
            if (config.has("perspectiveSmoothSpeed")) {
                perspectiveSmoothSpeed = config.get("perspectiveSmoothSpeed").getAsFloat();
            }
            if (config.has("perspectiveZoomKeyCode")) {
                perspectiveZoomKeyCode = config.get("perspectiveZoomKeyCode").getAsInt();
            }
            if (config.has("perspectiveFOV")) {
                perspectiveFOV = config.get("perspectiveFOV").getAsFloat();
            }
            if (config.has("perspectiveFOVEnabled")) {
                perspectiveFOVEnabled = config.get("perspectiveFOVEnabled").getAsBoolean();
            }
            if (config.has("perspectiveLockScroll")) {
                perspectiveLockScroll = config.get("perspectiveLockScroll").getAsBoolean();
            }
            if (config.has("perspectiveAdvancedRenderDistanceKeyCode")) {
                perspectiveAdvancedRenderDistanceKeyCode = config.get("perspectiveAdvancedRenderDistanceKeyCode").getAsInt();
            }
            if (config.has("perspectiveAdvancedRenderDistanceBlocks")) {
                perspectiveAdvancedRenderDistanceBlocks = config.get("perspectiveAdvancedRenderDistanceBlocks").getAsInt();
            }
            
            // Load GoStats setting
            if (config.has("goStatsEnabled")) {
                goStatsEnabled = config.get("goStatsEnabled").getAsBoolean();
            }
            
            // Load KillCounter enabled setting
            if (config.has("killCounterEnabled")) {
                killCounterEnabled = config.get("killCounterEnabled").getAsBoolean();
            }
            
            // Load KillCounter auto /tabstats all setting
            if (config.has("killCounterAutoKeAll")) {
                killCounterAutoKeAll = config.get("killCounterAutoKeAll").getAsBoolean();
            }
            if (config.has("killCounterAutoScanMode")) {
                killCounterAutoScanMode = config.get("killCounterAutoScanMode").getAsString();
            } else {
                killCounterAutoScanMode = killCounterAutoKeAll ? "CAGES_OPEN" : "OFF";
            }
            
            // Load KillCounter guild tag setting
            if (config.has("killCounterShowGuildTag")) {
                killCounterShowGuildTag = config.get("killCounterShowGuildTag").getAsBoolean();
            }
            killCounterBetterPlayerFormat = readBetterPlayerFormat(config);
            
            // Load KillCounter teammate kit setting
            if (config.has("killCounterShowTeammateKits")) {
                killCounterShowTeammateKits = config.get("killCounterShowTeammateKits").getAsBoolean();
            }
            
            // Load KillCounter enemy kit setting
            if (config.has("killCounterShowEnemyKits")) {
                killCounterShowEnemyKits = config.get("killCounterShowEnemyKits").getAsBoolean();
            }
            
            // Load KillCounter /tabstats all chat setting
            if (config.has("killCounterShowKeAllChat")) {
                killCounterShowKeAllChat = config.get("killCounterShowKeAllChat").getAsBoolean();
            }
            
            // Load KillCounter stats display setting
            if (config.has("killCounterStatsDisplay")) {
                killCounterStatsDisplay = config.get("killCounterStatsDisplay").getAsBoolean();
            }
            if (config.has("killCounterStatsShowKd")) {
                killCounterStatsShowKd = config.get("killCounterStatsShowKd").getAsBoolean();
                killCounterStatsShowWl = config.get("killCounterStatsShowWl").getAsBoolean();
                killCounterStatsShowW = config.get("killCounterStatsShowW").getAsBoolean();
            } else if (config.has("killCounterWlMode")) {
                killCounterWlMode = Math.max(0, Math.min(3, config.get("killCounterWlMode").getAsInt()));
                applyKillCounterStatsPartsFromLegacyWlMode(killCounterWlMode);
            }
            syncKillCounterStatsDisplayFromParts();
            if (config.has("killCounterTabDisplayMode")) {
                killCounterTabDisplayMode = normalizeKillCounterTabDisplayMode(config.get("killCounterTabDisplayMode").getAsString());
            }
            if (config.has("killCounterKillEffectTabMode")) {
                killCounterKillEffectTabMode = normalizeKillEffectTabMode(config.get("killCounterKillEffectTabMode").getAsString());
            }
            normalizePlayerNameDisplayModes = enforceBetterPlayerFormatCompatibility();
            if (config.has("killCounterTabListSortMode")) {
                killCounterTabListSortMode = normalizeKillCounterTabListSortMode(
                        config.get("killCounterTabListSortMode").getAsString());
            } else if (config.has("killCounterSortTabByKills") && config.get("killCounterSortTabByKills").getAsBoolean()) {
                killCounterTabListSortMode = "KILLS_THEN_WLR";
            }
            if (config.has("killCounterTabListSortGameScope")) {
                killCounterTabListSortGameScope = normalizeKillCounterTabListSortGameScope(
                        config.get("killCounterTabListSortGameScope").getAsString());
            }
            if (config.has("killCounterTabListSortDistrictEnabled")) {
                killCounterTabListSortDistrictEnabled = config.get("killCounterTabListSortDistrictEnabled").getAsBoolean();
            }
            if (config.has("killCounterTabKillersEnabled")) {
                killCounterTabKillersEnabled = config.get("killCounterTabKillersEnabled").getAsBoolean();
            }
            if (config.has("killCounterTabKillersKeyCode")) {
                killCounterTabKillersKeyCode = config.get("killCounterTabKillersKeyCode").getAsInt();
            }
            killCounterTabKillersVictimOffsetPx = 8;
            if (config.has("killCounterTabKillersVictimKillCountAligned")) {
                killCounterTabKillersVictimKillCountAligned = config.get("killCounterTabKillersVictimKillCountAligned").getAsBoolean();
            }
            if (config.has("killCounterTabKillersVictimKillCountOffsetPx")) {
                killCounterTabKillersVictimKillCountOffsetPx = config.get("killCounterTabKillersVictimKillCountOffsetPx").getAsInt();
            }
            if (config.has("killCounterTabKillersVictimDimmed")) {
                killCounterTabKillersVictimDimmed = config.get("killCounterTabKillersVictimDimmed").getAsBoolean();
            }
            if (config.has("killCounterBlitzStarTabIconEnabled")) {
                killCounterBlitzStarTabIconEnabled = config.get("killCounterBlitzStarTabIconEnabled").getAsBoolean();
            }
            killCounterTabListSortMode = coerceKillCounterTabSortModeForScope(
                    killCounterTabListSortMode, killCounterTabListSortGameScope);
            if (config.has("mobNametagsEnabled")) {
                mobNametagsEnabled = config.get("mobNametagsEnabled").getAsBoolean();
            }
            if (config.has("friendlyMobNametagColorEnabled")) {
                friendlyMobNametagColorEnabled = config.get("friendlyMobNametagColorEnabled").getAsBoolean();
            }

			// Load global chat settings
            if (config.has("globalChatEnabled")) {
                globalChatEnabled = config.get("globalChatEnabled").getAsBoolean();
            }
            if (config.has("globalChatEndpoint")) {
                globalChatEndpoint = config.get("globalChatEndpoint").getAsString();
            }
            if (config.has("globalChatChannel")) {
                globalChatChannel = config.get("globalChatChannel").getAsString();
            }
            if (config.has("globalChatPollSeconds")) {
                globalChatPollSeconds = Math.max(1, config.get("globalChatPollSeconds").getAsInt());
            }
            if (config.has("debugMessagesToChatEnabled")) {
                debugMessagesToChatEnabled = config.get("debugMessagesToChatEnabled").getAsBoolean();
            }
            if (config.has("blitzExtendedResultsEnabled")) {
                blitzExtendedResultsEnabled = config.get("blitzExtendedResultsEnabled").getAsBoolean();
            }
            if (config.has("blitzPregamePlayerToggleEnabled")) {
                blitzPregamePlayerToggleEnabled = config.get("blitzPregamePlayerToggleEnabled").getAsBoolean();
            }
            if (config.has("deathmatchTimerBossBarEnabled")) {
                deathmatchTimerBossBarEnabled = config.get("deathmatchTimerBossBarEnabled").getAsBoolean();
            }
            if (config.has("deathmatchTimerBossBarThresholdSeconds")) {
                deathmatchTimerBossBarThresholdSeconds = Math.max(5, Math.min(60,
                        config.get("deathmatchTimerBossBarThresholdSeconds").getAsInt()));
            }
            if (config.has("silentSpawnedMobStepsEnabled")) {
                silentSpawnedMobStepsEnabled = config.get("silentSpawnedMobStepsEnabled").getAsBoolean();
            }
            if (config.has("silentSpawnedOcelotSteps")) {
                silentSpawnedOcelotSteps = config.get("silentSpawnedOcelotSteps").getAsBoolean();
            }
            if (config.has("silentSpawnedWitchSteps")) {
                silentSpawnedWitchSteps = config.get("silentSpawnedWitchSteps").getAsBoolean();
            }
            if (config.has("blitzExtendedResultsMorePositionsEnabled")) {
                blitzExtendedResultsMorePositionsEnabled = config.get("blitzExtendedResultsMorePositionsEnabled").getAsBoolean();
            }
            if (config.has("blitzExtendedResultsStarInfoEnabled")) {
                blitzExtendedResultsStarInfoEnabled = config.get("blitzExtendedResultsStarInfoEnabled").getAsBoolean();
            }
            if (config.has("blitzExtendedResultsDeathmatchPlayersEnabled")) {
                blitzExtendedResultsDeathmatchPlayersEnabled = config.get("blitzExtendedResultsDeathmatchPlayersEnabled").getAsBoolean();
            }
            if (config.has("blitzExtendedResultsMessageCountEnabled")) {
                blitzExtendedResultsMessageCountEnabled = config.get("blitzExtendedResultsMessageCountEnabled").getAsBoolean();
            }
            if (config.has("blitzExtendedResultsGameDurationEnabled")) {
                blitzExtendedResultsGameDurationEnabled = config.get("blitzExtendedResultsGameDurationEnabled").getAsBoolean();
            }
            if (config.has("blitzExtendedResultsMapPlayerCountEnabled")) {
                blitzExtendedResultsMapPlayerCountEnabled = config.get("blitzExtendedResultsMapPlayerCountEnabled").getAsBoolean();
            }
            if (config.has("debugHitsEnabled")) {
                debugHitsEnabled = config.get("debugHitsEnabled").getAsBoolean();
            }
            if (config.has("denickCrowdSyncEnabled")) {
                denickCrowdSyncEnabled = config.get("denickCrowdSyncEnabled").getAsBoolean();
            } else {
                denickCrowdSyncEnabled = true;
            }
            if (config.has("denickCrowdSyncBaseUrl")) {
                denickCrowdSyncBaseUrl = config.get("denickCrowdSyncBaseUrl").getAsString();
            }

            if (config.has("shareInvRelayUrl")) {
                shareInvRelayUrl = config.get("shareInvRelayUrl").getAsString();
                if ("wss://gomod-shareinv-relay.onrender.com".equalsIgnoreCase(shareInvRelayUrl.trim())) {
                    shareInvRelayUrl = "wss://gomod-service.onrender.com";
                }
            }
            if (config.has("shareInvSecret")) {
                shareInvSecret = config.get("shareInvSecret").getAsString();
            }
            if (config.has("shareInvAutoConnect")) {
                shareInvAutoConnect = config.get("shareInvAutoConnect").getAsBoolean();
            }
            if (config.has("lastNotifiedModVersion")) {
                lastNotifiedModVersion = config.get("lastNotifiedModVersion").getAsString();
            }
            gomodSocialInstallId = "";
            gomodSocialId = "";
            if (config.has("gomodSocialInstallId")) {
                gomodSocialInstallId = normalizeGomodSocialId(config.get("gomodSocialInstallId").getAsString());
            }
            if (config.has("gomodSocialId")) {
                gomodSocialId = normalizeGomodSocialId(config.get("gomodSocialId").getAsString());
                if (gomodSocialInstallId.isEmpty()) {
                    gomodSocialInstallId = gomodSocialId;
                }
            }
            gomodFriendSocialIds.clear();
            if (config.has("gomodFriendSocialIds") && config.get("gomodFriendSocialIds").isJsonObject()) {
                com.google.gson.JsonObject sidMap = config.getAsJsonObject("gomodFriendSocialIds");
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : sidMap.entrySet()) {
                    if (e.getValue() == null || !e.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String id = normalizeGomodSocialId(e.getValue().getAsString());
                    if (!id.isEmpty()) {
                        gomodFriendSocialIds.put(e.getKey().toLowerCase(java.util.Locale.ROOT), id);
                    }
                }
            }
            gomodFriendsByAccount.clear();
            activeGomodFriendsAccount = null;
            if (config.has("gomodFriendsByAccount") && config.get("gomodFriendsByAccount").isJsonObject()) {
                com.google.gson.JsonObject byAcc = config.getAsJsonObject("gomodFriendsByAccount");
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : byAcc.entrySet()) {
                    if (e.getValue() == null || !e.getValue().isJsonArray()) {
                        continue;
                    }
                    java.util.List<String> list = new java.util.ArrayList<String>();
                    com.google.gson.JsonArray arr = e.getValue().getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        com.google.gson.JsonElement el = arr.get(i);
                        if (el != null && el.isJsonPrimitive()) {
                            String s = el.getAsString();
                            if (s != null && !s.trim().isEmpty()) {
                                list.add(s.trim());
                            }
                        }
                    }
                    gomodFriendsByAccount.put(e.getKey(), list);
                }
            }
            gomodFriends.clear();
            gomodFriendsLegacyPending.clear();
            if (config.has("gomodFriends") && config.get("gomodFriends").isJsonArray()) {
                com.google.gson.JsonArray legacyArr = config.getAsJsonArray("gomodFriends");
                for (int i = 0; i < legacyArr.size(); i++) {
                    com.google.gson.JsonElement el = legacyArr.get(i);
                    if (el != null && el.isJsonPrimitive()) {
                        String s = el.getAsString();
                        if (s != null && !s.trim().isEmpty()) {
                            gomodFriendsLegacyPending.add(s.trim());
                        }
                    }
                }
            }
            gomodIncomingFriendRequestsByAccount.clear();
            gomodOutgoingFriendRequestsByAccount.clear();
            gomodSeenIncomingFriendRequestsByAccount.clear();
            activeGomodFriendRequestsAccount = null;
            loadGomodFriendRequestMap(config, "gomodIncomingFriendRequestsByAccount", gomodIncomingFriendRequestsByAccount);
            loadGomodFriendRequestMap(config, "gomodOutgoingFriendRequestsByAccount", gomodOutgoingFriendRequestsByAccount);
            loadGomodFriendRequestMap(config, "gomodSeenIncomingFriendRequestsByAccount", gomodSeenIncomingFriendRequestsByAccount);
            gomodIncomingFriendRequests.clear();
            gomodOutgoingFriendRequests.clear();
            gomodSeenIncomingFriendRequests.clear();
            gomodIncomingFriendRequestsLegacyPending.clear();
            gomodOutgoingFriendRequestsLegacyPending.clear();
            loadGomodFriendRequestArray(config, "gomodIncomingFriendRequests", gomodIncomingFriendRequestsLegacyPending);
            loadGomodFriendRequestArray(config, "gomodOutgoingFriendRequests", gomodOutgoingFriendRequestsLegacyPending);
            gomodFriendRequestResetGeneration = config.has("gomodFriendRequestResetGeneration")
                    ? Math.max(0L, config.get("gomodFriendRequestResetGeneration").getAsLong()) : 0L;
            
		// Load health indicator settings
		// Load replay settings (support old healthIndicator keys for backwards compatibility)
		if (config.has("replayHealthDisplayEnabled")) {
			replayHealthDisplayEnabled = config.get("replayHealthDisplayEnabled").getAsBoolean();
		} else if (config.has("healthIndicatorEnabled")) {
			replayHealthDisplayEnabled = config.get("healthIndicatorEnabled").getAsBoolean();
		}
		if (config.has("replayHealthDisplayStyle")) {
			replayHealthDisplayStyle = config.get("replayHealthDisplayStyle").getAsString();
		} else if (config.has("healthIndicatorStyle")) {
			replayHealthDisplayStyle = config.get("healthIndicatorStyle").getAsString();
		}
		if (config.has("replayHealthDisplayYOffset")) {
			replayHealthDisplayYOffset = config.get("replayHealthDisplayYOffset").getAsFloat();
		} else if (config.has("healthIndicatorYOffset")) {
			replayHealthDisplayYOffset = config.get("healthIndicatorYOffset").getAsFloat();
		}
		if (config.has("replayShowInvisibleEnabled")) {
			replayShowInvisibleEnabled = config.get("replayShowInvisibleEnabled").getAsBoolean();
		}
		
		// Load player target settings
		if (config.has("playerTargetEnabled")) {
			playerTargetEnabled = config.get("playerTargetEnabled").getAsBoolean();
		}
		if (config.has("playerTargetGlowTeammateMode")) {
			playerTargetGlowTeammateMode = config.get("playerTargetGlowTeammateMode").getAsString();
		} else if (config.has("playerTargetGlowTeammateEnabled")) {
			playerTargetGlowTeammateMode = config.get("playerTargetGlowTeammateEnabled").getAsBoolean() ? "GREEN" : "DISABLED";
		}
		if (config.has("playerTargetTeammateTrailEnabled")) {
			playerTargetTeammateTrailEnabled = config.get("playerTargetTeammateTrailEnabled").getAsBoolean();
		}
		if (config.has("playerTargetShowVerticalDistance")) {
			playerTargetShowVerticalDistance = config.get("playerTargetShowVerticalDistance").getAsBoolean();
		}
		if (config.has("playerTargetShowBackground")) {
			playerTargetShowBackground = config.get("playerTargetShowBackground").getAsBoolean();
		}
		if (config.has("playerTargetBackgroundOpacity")) {
			playerTargetBackgroundOpacity = config.get("playerTargetBackgroundOpacity").getAsInt();
		}
		if (config.has("playerTargetBackgroundColor")) {
			playerTargetBackgroundColor = config.get("playerTargetBackgroundColor").getAsInt() & 0xFFFFFF;
		}
		if (config.has("playerTargetPosition")) {
			playerTargetPosition = config.get("playerTargetPosition").getAsString();
		}
		if (config.has("playerTargetCustomX")) {
			playerTargetCustomX = config.get("playerTargetCustomX").getAsInt();
		}
		if (config.has("playerTargetCustomY")) {
			playerTargetCustomY = config.get("playerTargetCustomY").getAsInt();
		}
		if (config.has("playerTargetShowEnchantments")) {
			playerTargetShowEnchantments = config.get("playerTargetShowEnchantments").getAsBoolean();
		}
		if (config.has("playerTargetShowTauntInfo")) {
			playerTargetShowTauntInfo = config.get("playerTargetShowTauntInfo").getAsBoolean();
		}
		if (config.has("playerTargetTextShadow")) {
			playerTargetTextShadow = config.get("playerTargetTextShadow").getAsBoolean();
		}
		if (config.has("playerTargetTeamInviteNotifierEnabled")) {
			playerTargetTeamInviteNotifierEnabled = config.get("playerTargetTeamInviteNotifierEnabled").getAsBoolean();
		}
		if (config.has("playerTargetScale")) {
			playerTargetScale = config.get("playerTargetScale").getAsFloat();
		}
		
		// Load MobHealthDisplay settings
		if (config.has("mobHealthDisplayEnabled")) {
			mobHealthDisplayEnabled = config.get("mobHealthDisplayEnabled").getAsBoolean();
		}
		if (config.has("mobHealthDisplayShowBackground")) {
			mobHealthDisplayShowBackground = config.get("mobHealthDisplayShowBackground").getAsBoolean();
		}
		if (config.has("mobHealthDisplayShowEffects")) {
			mobHealthDisplayShowEffects = config.get("mobHealthDisplayShowEffects").getAsBoolean();
		}
		if (config.has("mobHealthDisplayTextShadow")) {
			mobHealthDisplayTextShadow = config.get("mobHealthDisplayTextShadow").getAsBoolean();
		}
		if (config.has("mobHealthDisplayBackgroundOpacity")) {
			mobHealthDisplayBackgroundOpacity = config.get("mobHealthDisplayBackgroundOpacity").getAsInt();
		}
		if (config.has("mobHealthDisplayBackgroundColor")) {
			mobHealthDisplayBackgroundColor = config.get("mobHealthDisplayBackgroundColor").getAsInt() & 0xFFFFFF;
		}
		if (config.has("mobHealthDisplayMaxDistance")) {
			mobHealthDisplayMaxDistance = config.get("mobHealthDisplayMaxDistance").getAsDouble();
		}
		if (config.has("mobHealthDisplayPersistenceTime")) {
			mobHealthDisplayPersistenceTime = config.get("mobHealthDisplayPersistenceTime").getAsDouble();
		}
		if (config.has("mobHealthDisplayPanelX")) {
			mobHealthDisplayPanelX = config.get("mobHealthDisplayPanelX").getAsInt();
		}
		if (config.has("mobHealthDisplayPanelY")) {
			mobHealthDisplayPanelY = config.get("mobHealthDisplayPanelY").getAsInt();
		}
		if (config.has("mobHealthDisplayScale")) {
			mobHealthDisplayScale = config.get("mobHealthDisplayScale").getAsFloat();
		}
		if (config.has("mobHealthDisplayMode")) {
			mobHealthDisplayMode = normalizeMobHealthDisplayMode(config.get("mobHealthDisplayMode").getAsString());
		}
		if (config.has("mobHealthDisplayHealthTagDisabledIds")) {
			loadMobHealthDisplayHealthTagDisabledIds(config.get("mobHealthDisplayHealthTagDisabledIds").getAsString());
			migrateMobHealthDisplayHealthTagDefaultDisabledIds();
		} else {
			migrateMobHealthDisplayHealthTagLegacyBooleans(config);
		}
		if (config.has("horseDonkeyDistanceHudEnabled")) {
			horseDonkeyDistanceHudEnabled = config.get("horseDonkeyDistanceHudEnabled").getAsBoolean();
		}
		if (config.has("horseDonkeyShowBackground")) {
			horseDonkeyShowBackground = config.get("horseDonkeyShowBackground").getAsBoolean();
		}
		if (config.has("horseDonkeyTeleportSoundEnabled")) {
			horseDonkeyTeleportSoundEnabled = config.get("horseDonkeyTeleportSoundEnabled").getAsBoolean();
		}
		if (config.has("horseDonkeyBackgroundOpacity")) {
			horseDonkeyBackgroundOpacity = config.get("horseDonkeyBackgroundOpacity").getAsInt();
		}
		if (config.has("horseDonkeyBackgroundColor")) {
			horseDonkeyBackgroundColor = config.get("horseDonkeyBackgroundColor").getAsInt() & 0xFFFFFF;
		}
		if (config.has("horseDonkeyPanelX")) {
			horseDonkeyPanelX = config.get("horseDonkeyPanelX").getAsInt();
		}
		if (config.has("horseDonkeyPanelY")) {
			horseDonkeyPanelY = config.get("horseDonkeyPanelY").getAsInt();
		}
		if (config.has("horseDonkeyScale")) {
			horseDonkeyScale = config.get("horseDonkeyScale").getAsFloat();
		}
		if (config.has("horseDonkeyHudTitleColor")) {
			horseDonkeyHudTitleColor = config.get("horseDonkeyHudTitleColor").getAsInt();
		}
		if (config.has("horseDonkeyHudValueColor")) {
			horseDonkeyHudValueColor = config.get("horseDonkeyHudValueColor").getAsInt();
		}
		if (config.has("horseDonkeyHudBlocksColor")) {
			horseDonkeyHudBlocksColor = config.get("horseDonkeyHudBlocksColor").getAsInt();
		}
		if (config.has("horseFoodNametagEnabled")) {
			horseFoodNametagEnabled = config.get("horseFoodNametagEnabled").getAsBoolean();
		}
		if (config.has("horseDonkeyTextShadow")) {
			horseDonkeyTextShadow = config.get("horseDonkeyTextShadow").getAsBoolean();
		}
		if (config.has("gomodMenuDragLookEnabled")) { gomodMenuDragLookEnabled = config.get("gomodMenuDragLookEnabled").getAsBoolean(); } if (config.has("gomodMenuMoveEnabled")) {
			gomodMenuMoveEnabled = config.get("gomodMenuMoveEnabled").getAsBoolean();
		}
		if (config.has("minecraftMenuControlsEnabled")) {
			minecraftMenuControlsEnabled = config.get("minecraftMenuControlsEnabled").getAsBoolean();
		}
		// Preserve the former shared setting once for existing profiles, then save independently.
		lunarMenuControlsEnabled = config.has("lunarMenuControlsEnabled")
				? config.get("lunarMenuControlsEnabled").getAsBoolean() : minecraftMenuControlsEnabled;
		if (config.has("gomodMenuGuiScale")) {
			gomodMenuGuiScale = Math.max(-1, Math.min(5, config.get("gomodMenuGuiScale").getAsInt()));
		} else if (config.has("gomodMenuCompactScaleEnabled")) {
			// One-build migration from the temporary compact-menu toggle.
			gomodMenuGuiScale = config.get("gomodMenuCompactScaleEnabled").getAsBoolean() ? 2 : -1;
		}
		if (config.has("hypixelTabDupeHideEnabled")) {
			hypixelTabDupeHideEnabled = config.get("hypixelTabDupeHideEnabled").getAsBoolean();
		}
		if (config.has("hypixelTabQuitGhostHideEnabled")) {
			hypixelTabQuitGhostHideEnabled = config.get("hypixelTabQuitGhostHideEnabled").getAsBoolean();
		}
		if (config.has("forceReplayPlayerHealthTagsEnabled")) {
			forceReplayPlayerHealthTagsEnabled = config.get("forceReplayPlayerHealthTagsEnabled").getAsBoolean();
		}
		if (config.has("blitzRektHitboxOverlayEnabled")) {
			blitzRektHitboxOverlayEnabled = config.get("blitzRektHitboxOverlayEnabled").getAsBoolean();
		}
		blitzRektHitboxPreviewLeft = readBlitzRektPreviewPadding(config, "blitzRektHitboxPreviewLeft");
		blitzRektHitboxPreviewRight = readBlitzRektPreviewPadding(config, "blitzRektHitboxPreviewRight");
		blitzRektHitboxPreviewUp = readBlitzRektPreviewPadding(config, "blitzRektHitboxPreviewUp");
		blitzRektHitboxPreviewDown = readBlitzRektPreviewPadding(config, "blitzRektHitboxPreviewDown");
        if (config.has("mobTargetDisplayEnabled")) {
			mobTargetDisplayEnabled = config.get("mobTargetDisplayEnabled").getAsBoolean();
		}
		if (config.has("duelPredictorEnabled")) {
			duelPredictorEnabled = config.get("duelPredictorEnabled").getAsBoolean();
		}
		if (config.has("duelPredictorGhostEnabled")) {
			duelPredictorGhostEnabled = config.get("duelPredictorGhostEnabled").getAsBoolean();
		}
		if (config.has("duelPredictorHudEnabled")) {
			duelPredictorHudEnabled = config.get("duelPredictorHudEnabled").getAsBoolean();
		}
		if (config.has("duelPredictorLeadTicks")) {
			duelPredictorLeadTicks = Math.max(1, Math.min(8, config.get("duelPredictorLeadTicks").getAsInt()));
		}
		if (config.has("duelPredictorHudPosX")) {
			duelPredictorHudPosX = config.get("duelPredictorHudPosX").getAsInt();
		}
		if (config.has("duelPredictorHudPosY")) {
			duelPredictorHudPosY = config.get("duelPredictorHudPosY").getAsInt();
		}
		if (config.has("duelPredictorHudScale")) {
			duelPredictorHudScale = config.get("duelPredictorHudScale").getAsFloat();
		}
		if (config.has("duelPredictorGhostColorArgb")) {
			duelPredictorGhostColorArgb = config.get("duelPredictorGhostColorArgb").getAsInt();
		}
		blitzChallengesCompletedTiers.clear();
		if (config.has("blitzChallengesCompletedTiers") && config.get("blitzChallengesCompletedTiers").isJsonArray()) {
			JsonArray arr = config.getAsJsonArray("blitzChallengesCompletedTiers");
			for (JsonElement el : arr) {
				if (el != null && el.isJsonPrimitive()) {
					String s = el.getAsString();
					if (s != null && !s.isEmpty()) {
						blitzChallengesCompletedTiers.add(s);
					}
				}
			}
		}
		if (config.has("blitzChallengesHudEnabled")) {
			blitzChallengesHudEnabled = config.get("blitzChallengesHudEnabled").getAsBoolean();
		}
		if (config.has("blitzChallengesHudPosX")) {
			blitzChallengesHudPosX = config.get("blitzChallengesHudPosX").getAsInt();
		}
		if (config.has("blitzChallengesHudPosY")) {
			blitzChallengesHudPosY = config.get("blitzChallengesHudPosY").getAsInt();
		}
		if (config.has("blitzChallengesHudScale")) {
			blitzChallengesHudScale = Math.max(0.5f, Math.min(4.0f, config.get("blitzChallengesHudScale").getAsFloat()));
		}
		if (config.has("blitzChallengesHudTextShadow")) {
			blitzChallengesHudTextShadow = config.get("blitzChallengesHudTextShadow").getAsBoolean();
		}
		if (config.has("blitzChallengesHudBg")) {
			blitzChallengesHudBg = clamp255(config.get("blitzChallengesHudBg").getAsInt());
		}
		if (config.has("blitzChallengesHudBgColor")) {
			blitzChallengesHudBgColor = config.get("blitzChallengesHudBgColor").getAsInt() & 0xFFFFFF;
		}
		if (config.has("blitzChallengeBadgeEnabled")) {
			blitzChallengeBadgeEnabled = config.get("blitzChallengeBadgeEnabled").getAsBoolean();
		}
		if (config.has("blitzChallengeBadgeEquippedKey")) {
			blitzChallengeBadgeEquippedKey = config.get("blitzChallengeBadgeEquippedKey").getAsString();
		}
		if (config.has("blitzChallengeBadgeOffsetX")) {
			blitzChallengeBadgeOffsetX = config.get("blitzChallengeBadgeOffsetX").getAsFloat();
		}
		if (config.has("blitzChallengeBadgeOffsetY")) {
			blitzChallengeBadgeOffsetY = config.get("blitzChallengeBadgeOffsetY").getAsFloat();
		}
		if (config.has("blitzChallengeBadgeOffsetZ")) {
			blitzChallengeBadgeOffsetZ = config.get("blitzChallengeBadgeOffsetZ").getAsFloat();
		}
		if (config.has("blitzChallengeBadgeScale")) {
			blitzChallengeBadgeScale = Math.max(0.35f, Math.min(1.85f, config.get("blitzChallengeBadgeScale").getAsFloat()));
		}
		if (config.has("blitzChallengeBadgeAttachPart")) {
			blitzChallengeBadgeAttachPart = config.get("blitzChallengeBadgeAttachPart").getAsString();
		}
		blitzChallengeBadgePlacements = new JsonArray();
		if (config.has("blitzChallengeBadgePlacements") && config.get("blitzChallengeBadgePlacements").isJsonArray()) {
			blitzChallengeBadgePlacements = cloneJsonArray(config.getAsJsonArray("blitzChallengeBadgePlacements"));
		}
		if (config.has("mobTargetMarkerText")) {
			mobTargetMarkerText = clampMobTargetMarker(config.get("mobTargetMarkerText").getAsString());
		}
		if (config.has("disguiseMobVisualKind")) {
			disguiseMobVisualKind = normalizeDisguiseMobVisualKind(config.get("disguiseMobVisualKind").getAsString());
		}
		if (config.has("disguiseMobVisualLastUsed")) {
			disguiseMobVisualLastUsed = config.get("disguiseMobVisualLastUsed").getAsString();
		}
		// Load BlitzKitTimerHUD settings
		if (config.has("blitzKitTimerHudEnabled")) {
			blitzKitTimerHudEnabled = config.get("blitzKitTimerHudEnabled").getAsBoolean();
		}
		if (config.has("blitzKitTimerHudScale")) {
			blitzKitTimerHudScale = Math.max(0.5f, Math.min(5.0f, config.get("blitzKitTimerHudScale").getAsFloat()));
		}
		if (config.has("blitzKitTimerHudOffsetCenterX")) {
			blitzKitTimerHudOffsetCenterX = config.get("blitzKitTimerHudOffsetCenterX").getAsInt();
		}
		if (config.has("blitzKitTimerHudPosY")) {
			blitzKitTimerHudPosY = config.get("blitzKitTimerHudPosY").getAsInt();
		}
		if (config.has("blitzKitTimerHudTextShadow")) {
			blitzKitTimerHudTextShadow = config.get("blitzKitTimerHudTextShadow").getAsBoolean();
		}
		if (config.has("blitzKitTimerHudBg")) {
			blitzKitTimerHudBg = clamp255(config.get("blitzKitTimerHudBg").getAsInt());
		}
		if (config.has("blitzKitTimerHudBgColor")) {
			blitzKitTimerHudBgColor = config.get("blitzKitTimerHudBgColor").getAsInt() & 0xFFFFFF;
		}
		if (config.has("acceptIncomingFriendScreenshots")) {
			acceptIncomingFriendScreenshots = config.get("acceptIncomingFriendScreenshots").getAsBoolean();
		}
		if (config.has("acceptIncomingNonFriendMessages")) {
			acceptIncomingNonFriendMessages = config.get("acceptIncomingNonFriendMessages").getAsBoolean();
		}
		if (config.has("acceptIncomingShareInv")) {
			acceptIncomingShareInv = config.get("acceptIncomingShareInv").getAsBoolean();
		}
		if (config.has("socialMsgIconHudEnabled")) {
			socialMsgIconHudEnabled = config.get("socialMsgIconHudEnabled").getAsBoolean();
		}
		if (config.has("publicMessagePopupsEnabled")) {
			publicMessagePopupsEnabled = config.get("publicMessagePopupsEnabled").getAsBoolean();
		}
		if (config.has("incomingScreenshotOverlayEnabled")) {
			incomingScreenshotOverlayEnabled = config.get("incomingScreenshotOverlayEnabled").getAsBoolean();
		}
		if (config.has("incomingScreenshotTextShadow")) {
			incomingScreenshotTextShadow = config.get("incomingScreenshotTextShadow").getAsBoolean();
		}
		if (config.has("incomingScreenshotHudPosX")) {
			incomingScreenshotHudPosX = config.get("incomingScreenshotHudPosX").getAsInt();
		}
		if (config.has("incomingScreenshotHudPosY")) {
			incomingScreenshotHudPosY = config.get("incomingScreenshotHudPosY").getAsInt();
		}
		if (config.has("incomingScreenshotHudScale")) {
			incomingScreenshotHudScale = Math.max(0.35f, Math.min(2.5f, config.get("incomingScreenshotHudScale").getAsFloat()));
		}
        if (config.has("ptracersCagesEnabled")) {
            ptracersCagesEnabled = config.get("ptracersCagesEnabled").getAsBoolean();
        }
        if (config.has("cageOpeningSoundEnabled")) {
            cageOpeningSoundEnabled = config.get("cageOpeningSoundEnabled").getAsBoolean();
        }
        if (config.has("gameTeleporterSoundEnabled")) {
            gameTeleporterSoundEnabled = config.get("gameTeleporterSoundEnabled").getAsBoolean();
        }
        if (config.has("blitzHourCageSfxEnabled")) {
            blitzHourCageSfxEnabled = config.get("blitzHourCageSfxEnabled").getAsBoolean();
        }
        if (config.has("cageChestOverlayEnabled")) {
            cageChestOverlayEnabled = config.get("cageChestOverlayEnabled").getAsBoolean();
        }
        if (config.has("cageMiddleLootEstimateEnabled")) {
            cageMiddleLootEstimateEnabled = config.get("cageMiddleLootEstimateEnabled").getAsBoolean();
        }
        if (config.has("ptracersTargetNametagEnabled")) {
            ptracersTargetNametagEnabled = config.get("ptracersTargetNametagEnabled").getAsBoolean();
        }
        if (config.has("tabStatsRuntimeMuteEnabled")) {
            tabStatsRuntimeMuteEnabled = config.get("tabStatsRuntimeMuteEnabled").getAsBoolean();
        }
        if (config.has("weaveDevTabStatsSeparated")) {
            weaveDevTabStatsSeparated = config.get("weaveDevTabStatsSeparated").getAsBoolean();
        }
		blitzKillEffectByAccountUuid.clear();
		if (config.has("blitzKillEffectByAccountUuid") && config.get("blitzKillEffectByAccountUuid").isJsonObject()) {
			JsonObject byAccount = config.getAsJsonObject("blitzKillEffectByAccountUuid");
			for (Map.Entry<String, JsonElement> e : byAccount.entrySet()) {
				if (e == null || e.getKey() == null || e.getValue() == null || e.getValue().isJsonNull()) {
					continue;
				}
				String key = normalizeAccountUuidKey(e.getKey());
				String code = normalizePersistentKillEffectCode(e.getValue().getAsString());
				if (!key.isEmpty() && code != null) {
					blitzKillEffectByAccountUuid.put(key, code);
				}
			}
		}
		
		// Load FakeBreak setting
		if (config.has("fakeBreakEnabled")) {
			fakeBreakEnabled = config.get("fakeBreakEnabled").getAsBoolean();
		}
		
		// Load BestArmorDisplay setting
		if (config.has("bestArmorDisplayEnabled")) {
			bestArmorDisplayEnabled = config.get("bestArmorDisplayEnabled").getAsBoolean();
		}
		if (config.has("bestArmorPercentageMode")) {
			int v = config.get("bestArmorPercentageMode").getAsInt();
			bestArmorPercentageMode = (v >= BEST_ARMOR_PERCENTAGE_OFF && v <= BEST_ARMOR_PERCENTAGE_DETAILED)
					? v : BEST_ARMOR_PERCENTAGE_MINIMAL;
		} else if (config.has("bestArmorPercentageDetailsEnabled")) {
			// Legacy boolean: false was "percent only", true added roll-range text beside the %
			bestArmorPercentageMode = config.get("bestArmorPercentageDetailsEnabled").getAsBoolean()
					? BEST_ARMOR_PERCENTAGE_DETAILED
					: BEST_ARMOR_PERCENTAGE_MINIMAL;
		}
		if (config.has("bestArmorContainerRenderStrategy")) {
			int v = config.get("bestArmorContainerRenderStrategy").getAsInt();
			bestArmorContainerRenderStrategy = (v >= 1 && v <= 8) ? v : 6;
		}
		if (config.has("bestArmorDebugOverlayPaint")) {
			bestArmorDebugOverlayPaint = config.get("bestArmorDebugOverlayPaint").getAsBoolean();
		}
		if (config.has("uselessItemOverlayEnabled")) {
			uselessItemOverlayEnabled = config.get("uselessItemOverlayEnabled").getAsBoolean();
		}
		if (config.has("uselessItemOverlayForceEverywhere")) {
			uselessItemOverlayForceEverywhere = config.get("uselessItemOverlayForceEverywhere").getAsBoolean();
		}
		if (config.has("uselessItemOverlayChestPickHintsEnabled")) {
			uselessItemOverlayChestPickHintsEnabled = config.get("uselessItemOverlayChestPickHintsEnabled").getAsBoolean();
		}
		if (config.has("uselessChestHintBlueFillHex")) {
			uselessChestHintBlueFillHex = expandLoadedChestHintHex(config.get("uselessChestHintBlueFillHex").getAsString(), uselessChestHintBlueFillHex, 0x40);
		}
		if (config.has("uselessChestHintBlueEdgeHex")) {
			uselessChestHintBlueEdgeHex = expandLoadedChestHintHex(config.get("uselessChestHintBlueEdgeHex").getAsString(), uselessChestHintBlueEdgeHex, 0xD0);
		}
		if (config.has("uselessChestHintYellowFillHex")) {
			uselessChestHintYellowFillHex = expandLoadedChestHintHex(config.get("uselessChestHintYellowFillHex").getAsString(), uselessChestHintYellowFillHex, 0x40);
		}
		if (config.has("uselessChestHintYellowEdgeHex")) {
			uselessChestHintYellowEdgeHex = expandLoadedChestHintHex(config.get("uselessChestHintYellowEdgeHex").getAsString(), uselessChestHintYellowEdgeHex, 0xD0);
		}
        if (config.has("uselessAlwaysRedBowl")) {
            uselessAlwaysRedBowl = config.get("uselessAlwaysRedBowl").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedCake")) {
            uselessAlwaysRedCake = config.get("uselessAlwaysRedCake").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedChestMinecart")) {
            uselessAlwaysRedChestMinecart = config.get("uselessAlwaysRedChestMinecart").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedMinecart")) {
            uselessAlwaysRedMinecart = config.get("uselessAlwaysRedMinecart").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedRail")) {
            uselessAlwaysRedRail = config.get("uselessAlwaysRedRail").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedRose")) {
            uselessAlwaysRedRose = config.get("uselessAlwaysRedRose").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedWheat")) {
            uselessAlwaysRedWheat = config.get("uselessAlwaysRedWheat").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedSharpnessFish")) {
            uselessAlwaysRedSharpnessFish = config.get("uselessAlwaysRedSharpnessFish").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedApple")) {
            uselessAlwaysRedApple = config.get("uselessAlwaysRedApple").getAsBoolean();
        }
        if (config.has("uselessAlwaysRedStick")) {
            uselessAlwaysRedStick = config.get("uselessAlwaysRedStick").getAsBoolean();
        }
        if (config.has("uselessMeleeIronVsStoneSharpRed")) {
            uselessMeleeIronVsStoneSharpRed = normalizeUselessMeleeIronStone(config.get("uselessMeleeIronVsStoneSharpRed").getAsString());
        }
        if (config.has("uselessMeleeDiamondVsIronSharpRed")) {
            uselessMeleeDiamondVsIronSharpRed = normalizeUselessMeleeDiamondIron(config.get("uselessMeleeDiamondVsIronSharpRed").getAsString());
        }
        if (config.has("uselessMeleeStoneVsGoldWoodSharpRed")) {
            uselessMeleeStoneVsGoldWoodSharpRed = normalizeUselessMeleeStoneGoldWood(config.get("uselessMeleeStoneVsGoldWoodSharpRed").getAsString());
        }
		if (config.has("tntPlacementPreviewEnabled")) {
			tntPlacementPreviewEnabled = config.get("tntPlacementPreviewEnabled").getAsBoolean();
		}
		if (config.has("chickenPlacementPreviewEnabled")) {
			chickenPlacementPreviewEnabled = config.get("chickenPlacementPreviewEnabled").getAsBoolean();
		}
		if (config.has("pigPlacementPreviewEnabled")) {
			pigPlacementPreviewEnabled = config.get("pigPlacementPreviewEnabled").getAsBoolean();
		}
		if (config.has("horsePlacementPreviewEnabled")) {
			horsePlacementPreviewEnabled = config.get("horsePlacementPreviewEnabled").getAsBoolean();
		}
		if (config.has("donkeyPlacementPreviewEnabled")) {
			donkeyPlacementPreviewEnabled = config.get("donkeyPlacementPreviewEnabled").getAsBoolean();
		}
		if (config.has("gomodBlockOverlayEnabled")) {
			gomodBlockOverlayEnabled = config.get("gomodBlockOverlayEnabled").getAsBoolean();
		}
		if (config.has("gomodBlockOverlayMode")) {
			gomodBlockOverlayMode = normalizeGomodBlockOverlayMode(config.get("gomodBlockOverlayMode").getAsString());
		}
		if (config.has("gomodBlockOverlayColorArgb")) {
			gomodBlockOverlayColorArgb = config.get("gomodBlockOverlayColorArgb").getAsInt();
		}
		if (config.has("gomodBlockOverlayLineWidth")) {
			gomodBlockOverlayLineWidth = config.get("gomodBlockOverlayLineWidth").getAsFloat();
		}
		if (config.has("gomodBlockOverlayLinePlacement")) {
			gomodBlockOverlayLinePlacement = config.get("gomodBlockOverlayLinePlacement").getAsString();
		}
		if (config.has("gomodBlockOverlayFillEnabled")) {
			gomodBlockOverlayFillEnabled = config.get("gomodBlockOverlayFillEnabled").getAsBoolean();
		}
		if (config.has("gomodBlockOverlayFillOpacity")) {
			gomodBlockOverlayFillOpacity = config.get("gomodBlockOverlayFillOpacity").getAsFloat();
		}
		if (config.has("gomodBlockOverlaySideShapeAware")) {
			gomodBlockOverlaySideShapeAware = config.get("gomodBlockOverlaySideShapeAware").getAsBoolean();
		}
		if (config.has("gomodBlockOverlayHideBarriersInCages")) {
			gomodBlockOverlayHideBarriersInCages = config.get("gomodBlockOverlayHideBarriersInCages").getAsBoolean();
		}
		if (config.has("gomodBlockOverlayPlayerOverlayEnabled")) {
			gomodBlockOverlayPlayerOverlayEnabled = config.get("gomodBlockOverlayPlayerOverlayEnabled").getAsBoolean();
		}
		if (config.has("lightningNerfEnabled")) {
			lightningNerfEnabled = config.get("lightningNerfEnabled").getAsBoolean();
		}
		if (config.has("lightningFixRenderEnabled")) {
			lightningFixRenderEnabled = config.get("lightningFixRenderEnabled").getAsBoolean();
		}
		if (config.has("lightningNerfBottomBlocks")) {
			int lb = config.get("lightningNerfBottomBlocks").getAsInt();
			lightningNerfBottomBlocks = Math.max(1, Math.min(64, lb));
		}
		if (config.has("lightningCustomColorEnabled")) {
			lightningCustomColorEnabled = config.get("lightningCustomColorEnabled").getAsBoolean();
		}
		if (config.has("lightningRandomColorEnabled")) {
			lightningRandomColorEnabled = config.get("lightningRandomColorEnabled").getAsBoolean();
		}
		if (config.has("lightningBoltColorArgb")) {
			lightningBoltColorArgb = config.get("lightningBoltColorArgb").getAsInt();
		}
		// Older builds saved the selected picker color without turning on custom tint.
		if (!lightningCustomColorEnabled
				&& (lightningBoltColorArgb & 0xFFFFFF) != (0xFFE6E6FF & 0xFFFFFF)) {
			lightningCustomColorEnabled = true;
		}
		if (config.has("lightningOpacityPercent")) {
			lightningOpacityPercent = clampLightningVolume(config.get("lightningOpacityPercent").getAsFloat());
		}
		if (config.has("lightningSpeedPercent")) {
			lightningSpeedPercent = clampLightningSpeed(config.get("lightningSpeedPercent").getAsFloat());
		}
		if (config.has("lightningSoundVolumePercent")) {
			lightningSoundVolumePercent = clampLightningVolume(config.get("lightningSoundVolumePercent").getAsFloat());
		}
		if (config.has("lightningPostCageSoundEnabled")) {
			lightningPostCageSoundEnabled = config.get("lightningPostCageSoundEnabled").getAsBoolean();
		}
		if (config.has("lightningPostCageSoundDurationSec")) {
			lightningPostCageSoundDurationSec = clampLightningPostCageDurationSec(
					config.get("lightningPostCageSoundDurationSec").getAsInt());
		}
		if (config.has("lightningPostCageSpeedPercent")) {
			lightningPostCageSpeedPercent = clampLightningSpeed(config.get("lightningPostCageSpeedPercent").getAsFloat());
		}
		if (config.has("lightningPostCageVolumePercent")) {
			lightningPostCageVolumePercent = clampLightningVolume(config.get("lightningPostCageVolumePercent").getAsFloat());
		}
		if (config.has("witchSoundsEnabled")) {
			witchSoundsEnabled = config.get("witchSoundsEnabled").getAsBoolean();
		}
		if (config.has("mainMenuQueueButtonsEnabled")) {
			mainMenuQueueButtonsEnabled = config.get("mainMenuQueueButtonsEnabled").getAsBoolean();
		}
		if (config.has("witchSfxVolIdle")) {
			witchSfxVolIdle = Math.max(0f, Math.min(1f, config.get("witchSfxVolIdle").getAsFloat()));
		}
		if (config.has("witchSfxVolHurt")) {
			witchSfxVolHurt = Math.max(0f, Math.min(1f, config.get("witchSfxVolHurt").getAsFloat()));
		}
		if (config.has("witchSfxVolDeath")) {
			witchSfxVolDeath = Math.max(0f, Math.min(1f, config.get("witchSfxVolDeath").getAsFloat()));
		}
		if (config.has("witchSfxVolThrow")) {
			witchSfxVolThrow = Math.max(0f, Math.min(1f, config.get("witchSfxVolThrow").getAsFloat()));
		}
		if (config.has("witchSfxVolDrink")) {
			witchSfxVolDrink = Math.max(0f, Math.min(1f, config.get("witchSfxVolDrink").getAsFloat()));
		}
		if (config.has("witchSfxVolCelebrate")) {
			witchSfxVolCelebrate = Math.max(0f, Math.min(1f, config.get("witchSfxVolCelebrate").getAsFloat()));
		}
		if (config.has("forceResistanceParticlesEnabled")) {
			forceResistanceParticlesEnabled = config.get("forceResistanceParticlesEnabled").getAsBoolean();
		}
		if (config.has("forceResistanceSpawnsPerTick")) {
			forceResistanceSpawnsPerTick = Math.max(0, Math.min(12, config.get("forceResistanceSpawnsPerTick").getAsInt()));
		}
		if (config.has("forceResistanceOpacityScale")) {
			forceResistanceOpacityScale = Math.max(0.0f, Math.min(1.5f, config.get("forceResistanceOpacityScale").getAsFloat()));
		}
		if (config.has("forceResistanceSaturation")) {
			forceResistanceSaturation = Math.max(0.25f, Math.min(2.5f, config.get("forceResistanceSaturation").getAsFloat()));
		}
		
		// Load RadialMenu settings
		if (config.has("radialMenuEnabled")) {
			radialMenuEnabled = config.get("radialMenuEnabled").getAsBoolean();
		}
		if (config.has("radialMenuKeyCode")) {
			radialMenuKeyCode = config.get("radialMenuKeyCode").getAsInt();
		}
		if (config.has("chatMovementKeyCode")) {
			chatMovementKeyCode = config.get("chatMovementKeyCode").getAsInt();
		}
		if (config.has("chatMovementDragLookEnabled")) {
			chatMovementDragLookEnabled = config.get("chatMovementDragLookEnabled").getAsBoolean();
		}
		if (config.has("chatMovementPassthroughKeyCode")) {
			chatMovementPassthroughKeyCode = config.get("chatMovementPassthroughKeyCode").getAsInt();
		}
		if (config.has("teamNickFinderLastFoundRelative")) {
			teamNickFinderLastFoundRelative = config.get("teamNickFinderLastFoundRelative").getAsBoolean();
		}
		if (config.has("teamNickFinder24HourClock")) {
			teamNickFinder24HourClock = config.get("teamNickFinder24HourClock").getAsBoolean();
		}
		if (config.has("teamNickFinderZoneId")) {
			teamNickFinderZoneId = normalizeTeamNickFinderZoneId(config.get("teamNickFinderZoneId").getAsString());
		}
		if (config.has("gomodFriendsListKeyCode")) {
			gomodFriendsListKeyCode = config.get("gomodFriendsListKeyCode").getAsInt();
		}
		if (config.has("gomodSocialRequireCtrl")) {
			gomodSocialRequireCtrl = config.get("gomodSocialRequireCtrl").getAsBoolean();
		}
		if (config.has("gomodFriendsListSecondKeyCode")) {
			gomodFriendsListSecondKeyCode = config.get("gomodFriendsListSecondKeyCode").getAsInt();
		} else if (gomodSocialRequireCtrl) {
			// Migrate the old hard-coded Ctrl modifier to the generic second-key chord.
			gomodFriendsListSecondKeyCode = org.lwjgl.input.Keyboard.KEY_LCONTROL;
		}
		if (config.has("autoAcceptIncomingCapes")) {
			autoAcceptIncomingCapes = config.get("autoAcceptIncomingCapes").getAsBoolean();
		}
		if (config.has("barrierVisibilityEnabled")) {
			barrierVisibilityEnabled = config.get("barrierVisibilityEnabled").getAsBoolean();
		}
		if (config.has("barrierVisibilityKeyCode")) {
			barrierVisibilityKeyCode = config.get("barrierVisibilityKeyCode").getAsInt();
		}
		if (config.has("barrierVisibilityStyle")) {
			barrierVisibilityStyle = BarrierVisibilityStyles.normalize(config.get("barrierVisibilityStyle").getAsString());
		}
		if (config.has("barrierOutlineThickness")) {
			barrierOutlineThickness = BarrierVisibilityStyles.clampThickness(
					config.get("barrierOutlineThickness").getAsInt());
		}
		if (config.has("barrierOutlineColorArgb")) {
			barrierOutlineColorArgb = config.get("barrierOutlineColorArgb").getAsInt();
		}
		if (config.has("barrierVisibilityRangeEnabled")) {
			barrierVisibilityRangeEnabled = config.get("barrierVisibilityRangeEnabled").getAsBoolean();
		}
		if (config.has("barrierVisibilityRange")) {
			barrierVisibilityRange = clampBarrierRange(config.get("barrierVisibilityRange").getAsInt());
		}
		if (config.has("barrierVisibilityShowAtCursor")) {
			barrierVisibilityShowAtCursor = config.get("barrierVisibilityShowAtCursor").getAsBoolean();
		}
		if (config.has("barrierVisibilityAimedOnly")) {
			barrierVisibilityAimedOnly = config.get("barrierVisibilityAimedOnly").getAsBoolean();
		}
		if (config.has("barrierVisibilityOpacityPercent")) {
			barrierVisibilityOpacityPercent = clampBarrierOpacityPercent(
					config.get("barrierVisibilityOpacityPercent").getAsInt());
		}
		if (config.has("barrierBlocksShownInCages")) {
			barrierBlocksShownInCages = config.get("barrierBlocksShownInCages").getAsBoolean();
		}
		if (config.has("blocksFinderEnabled")) {
			blocksFinderEnabled = config.get("blocksFinderEnabled").getAsBoolean();
		}
		if (config.has("blocksFinderKeyCode")) {
			blocksFinderKeyCode = config.get("blocksFinderKeyCode").getAsInt();
		}
		if (config.has("blocksFinderEspMode")) {
			blocksFinderEspMode = config.get("blocksFinderEspMode").getAsString();
		}
		if (config.has("blocksFinderColorR")) {
			blocksFinderColorR = config.get("blocksFinderColorR").getAsInt();
		}
		if (config.has("blocksFinderColorG")) {
			blocksFinderColorG = config.get("blocksFinderColorG").getAsInt();
		}
		if (config.has("blocksFinderColorB")) {
			blocksFinderColorB = config.get("blocksFinderColorB").getAsInt();
		}
		if (config.has("blocksFinderFillAlpha")) {
			blocksFinderFillAlpha = config.get("blocksFinderFillAlpha").getAsInt();
		}
		if (config.has("blocksFinderWorldOpacity")) {
			blocksFinderWorldOpacity = config.get("blocksFinderWorldOpacity").getAsInt();
		}
		if (config.has("blocksFinderWorldOpacityKeyCode")) {
			blocksFinderWorldOpacityKeyCode = config.get("blocksFinderWorldOpacityKeyCode").getAsInt();
		}
		if (config.has("blocksFinderBlockColors") && config.get("blocksFinderBlockColors").isJsonObject()) {
			blocksFinderBlockColors.clear();
			JsonObject colors = config.getAsJsonObject("blocksFinderBlockColors");
			for (Map.Entry<String, JsonElement> e : colors.entrySet()) {
				blocksFinderBlockColors.put(e.getKey(), e.getValue().getAsInt() & 0x00FFFFFF);
			}
		}
		if (config.has("blocksFinderBlocks")) {
			JsonArray arr = config.getAsJsonArray("blocksFinderBlocks");
			blocksFinderBlocks = new ArrayList<String>();
			for (JsonElement el : arr) {
				String s = el.getAsString();
				if (s != null && !s.trim().isEmpty()) {
					blocksFinderBlocks.add(s.trim());
				}
			}
		}

		// Load NoHitDelay setting
		if (config.has("noHitDelayEnabled")) {
			noHitDelayEnabled = config.get("noHitDelayEnabled").getAsBoolean();
		}

		// Load CPS cap
		if (config.has("cpsLimit")) {
			cpsLimit = Math.max(0.0, config.get("cpsLimit").getAsDouble());
		}
		
		// Load VisibleSpecs setting
		if (config.has("forceGameChats")) forceGameChats = config.get("forceGameChats").getAsBoolean();
		if (config.has("unbotheredTaunt")) unbotheredTaunt = config.get("unbotheredTaunt").getAsBoolean();
		if (config.has("seeSpectatorsWhileAlive")) {
			seeSpectatorsWhileAlive = config.get("seeSpectatorsWhileAlive").getAsBoolean();
		}
		if (config.has("visibleSpecsEnabled")) {
			visibleSpecsEnabled = config.get("visibleSpecsEnabled").getAsBoolean();
		}
		if (config.has("spectatorBypassAllInvisible")) {
			spectatorBypassAllInvisible = config.get("spectatorBypassAllInvisible").getAsBoolean();
		}
		if (config.has("visibleSpecsNametagMode")) {
			visibleSpecsNametagMode = normalizeVisibleSpecsNametagMode(config.get("visibleSpecsNametagMode").getAsString());
		} else if (config.has("visibleSpecsNametagsEnabled")) {
			visibleSpecsNametagMode = config.get("visibleSpecsNametagsEnabled").getAsBoolean() ? "ON" : "OFF";
		}
		if (config.has("visibleSpecsOpacity")) {
			visibleSpecsOpacity = clampMountAlpha(config.get("visibleSpecsOpacity").getAsFloat());
		}
		if (config.has("visibleSpecsKillerNametagEnabled")) {
			visibleSpecsKillerNametagEnabled = config.get("visibleSpecsKillerNametagEnabled").getAsBoolean();
		}
		if (config.has("visibleSpecsApplyToReplaysEnabled")) {
			visibleSpecsApplyToReplaysEnabled = config.get("visibleSpecsApplyToReplaysEnabled").getAsBoolean();
		}

		// Load SelfOpacity settings
		if (config.has("selfOpacityEnabled")) {
			selfOpacityEnabled = config.get("selfOpacityEnabled").getAsBoolean();
		}
		if (config.has("selfOpacityAlpha")) {
			selfOpacityAlpha = clampMountAlpha(config.get("selfOpacityAlpha").getAsFloat());
		}
		if (config.has("selfOpacityFirstPersonHandAlpha")) {
			selfOpacityFirstPersonHandAlpha = clampMountAlpha(config.get("selfOpacityFirstPersonHandAlpha").getAsFloat());
		} else if (config.has("selfOpacityFirstPersonItemAlpha")) {
			// Migrate the prior held-item slider into the combined first-person hand + item control.
			selfOpacityFirstPersonHandAlpha = clampMountAlpha(config.get("selfOpacityFirstPersonItemAlpha").getAsFloat());
		}
		if (config.has("selfOpacityDisableDuringBlindness")) {
			selfOpacityDisableDuringBlindness = config.get("selfOpacityDisableDuringBlindness").getAsBoolean();
		}
		if (config.has("selfOpacityKeyCode")) {
			selfOpacityKeyCode = config.get("selfOpacityKeyCode").getAsInt();
		}

		// Load Schizo setting
		if (config.has("schizoEnabled")) {
			schizoEnabled = config.get("schizoEnabled").getAsBoolean();
		}
		
        // Load BorderIndication settings
        if (config.has("borderIndicationEnabled")) {
            borderIndicationEnabled = config.get("borderIndicationEnabled").getAsBoolean();
        }
        if (config.has("borderIndicationOpacity")) {
            borderIndicationOpacity = config.get("borderIndicationOpacity").getAsFloat();
        }
        if (config.has("borderIndicationRenderDistance")) {
            borderIndicationRenderDistance = config.get("borderIndicationRenderDistance").getAsInt();
        }

        // Load StarText settings
        if (config.has("dcChatMirrorEnabled")) {
            dcChatMirrorEnabled = config.get("dcChatMirrorEnabled").getAsBoolean();
        }
        dcMirrorRecipientIds.clear();
        if (config.has("dcMirrorRecipientIds") && config.get("dcMirrorRecipientIds").isJsonArray()) {
            JsonArray arr = config.getAsJsonArray("dcMirrorRecipientIds");
            for (JsonElement e : arr) {
                if (e != null && e.isJsonPrimitive()) {
                    String s = e.getAsString();
                    if (s != null && s.matches("\\d{5,22}")) {
                        dcMirrorRecipientIds.add(s.trim());
                    }
                }
            }
        }
        if (config.has("dcMirrorSelectedRecipientId")) {
            dcMirrorSelectedRecipientId = config.get("dcMirrorSelectedRecipientId").getAsString();
        }
        if (config.has("dcMirrorIncomingOnlySelectedRecipient")) {
            dcMirrorIncomingOnlySelectedRecipient = config.get("dcMirrorIncomingOnlySelectedRecipient").getAsBoolean();
        }
        if (!GomodReleaseBuild.PUBLIC_RELEASE) {
            loadLegacyDcMirrorConfigKeys(config);
        }
        if (config.has("starTextEnabled")) {
            starTextEnabled = config.get("starTextEnabled").getAsBoolean();
        }
        if (config.has("starPredictEnabled")) {
            starPredictEnabled = config.get("starPredictEnabled").getAsBoolean();
        }
        if (config.has("hotbarEnchantTextEnabled")) {
            hotbarEnchantTextEnabled = config.get("hotbarEnchantTextEnabled").getAsBoolean();
        }
        if (config.has("hotbarEnchantTextShadow")) {
            hotbarEnchantTextShadow = config.get("hotbarEnchantTextShadow").getAsBoolean();
        }
        if (config.has("hotbarEnchantTextColor")) {
            hotbarEnchantTextColor = config.get("hotbarEnchantTextColor").getAsInt() & 0xFFFFFF;
        }
        if (config.has("hotbarEnchantPotionTextStyle")) {
            hotbarEnchantPotionTextStyle = Math.max(0, Math.min(1, config.get("hotbarEnchantPotionTextStyle").getAsInt()));
        }
        if (config.has("hotbarEnchantTextGapAboveName")) {
            hotbarEnchantTextGapAboveName = clampHotbarEnchantTextGapAboveName(config.get("hotbarEnchantTextGapAboveName").getAsInt());
        }
        if (config.has("blitzVaultItemsSortMode")) {
            try {
                String raw = config.get("blitzVaultItemsSortMode").getAsString();
                blitzVaultItemsSortMode = raw == null || raw.trim().isEmpty()
                        ? "DEFAULT"
                        : raw.trim().toUpperCase(Locale.US);
            } catch (Throwable ignored) {
                blitzVaultItemsSortMode = "DEFAULT";
            }
        }
        if (config.has("blitzVaultItemsSortKeys")) {
            try {
                String raw = config.get("blitzVaultItemsSortKeys").getAsString();
                blitzVaultItemsSortKeys = raw == null ? "" : raw.trim();
            } catch (Throwable ignored) {
                blitzVaultItemsSortKeys = "";
            }
        }
        if (config.has("blitzVaultItemsShowSeenInTooltip")) {
            blitzVaultItemsShowSeenInTooltip = config.get("blitzVaultItemsShowSeenInTooltip").getAsBoolean();
        }
        if (config.has("blitzVaultItemsObservedCategory")) {
            try {
                String raw = config.get("blitzVaultItemsObservedCategory").getAsString();
                blitzVaultItemsObservedCategory = raw == null ? "" : raw.trim();
            } catch (Throwable ignored) {
                blitzVaultItemsObservedCategory = "";
            }
        }
        if (config.has("blitzVaultItemsChestSortMode")) {
            try {
                String raw = config.get("blitzVaultItemsChestSortMode").getAsString();
                blitzVaultItemsChestSortMode = raw == null || raw.trim().isEmpty()
                        ? "CHEST_NUMBER"
                        : raw.trim().toUpperCase(java.util.Locale.US);
            } catch (Throwable ignored) {
                blitzVaultItemsChestSortMode = "CHEST_NUMBER";
            }
        }
        if (config.has("blitzChestItemsSortMode")) {
            try {
                String raw = config.get("blitzChestItemsSortMode").getAsString();
                blitzChestItemsSortMode = raw == null || raw.trim().isEmpty()
                        ? "MOST_OBSERVED"
                        : raw.trim().toUpperCase(java.util.Locale.US);
            } catch (Throwable ignored) {
                blitzChestItemsSortMode = "MOST_OBSERVED";
            }
        }
        if (config.has("blitzChestItemsShowSeenInTooltip")) {
            blitzChestItemsShowSeenInTooltip = config.get("blitzChestItemsShowSeenInTooltip").getAsBoolean();
        }
        // Omit key on fresh installs — static default false (Combine variants: Off).
        if (config.has("blitzChestItemsAggregateVariants")) {
            blitzChestItemsAggregateVariants = config.get("blitzChestItemsAggregateVariants").getAsBoolean();
        } else {
            blitzChestItemsAggregateVariants = false;
        }
        if (config.has("blitzChestItemsAggregateCombatTier")) {
            blitzChestItemsAggregateCombatTier = config.get("blitzChestItemsAggregateCombatTier").getAsBoolean();
        } else {
            blitzChestItemsAggregateCombatTier = false;
        }
        if (config.has("blitzChestItemsChestSortMode")) {
            try {
                String raw = config.get("blitzChestItemsChestSortMode").getAsString();
                blitzChestItemsChestSortMode = raw == null || raw.trim().isEmpty()
                        ? "CHEST_NUMBER"
                        : raw.trim().toUpperCase(java.util.Locale.US);
            } catch (Throwable ignored) {
                blitzChestItemsChestSortMode = "CHEST_NUMBER";
            }
        }
        blitzVaultItemsPreferredKeys.clear();
        if (config.has("blitzVaultItemsPreferredKeys") && config.get("blitzVaultItemsPreferredKeys").isJsonArray()) {
            JsonArray arr = config.getAsJsonArray("blitzVaultItemsPreferredKeys");
            for (int i = 0; i < arr.size(); i++) {
                try {
                    String raw = arr.get(i).getAsString();
                    if (raw != null) {
                        raw = raw.trim();
                        if (!raw.isEmpty() && !blitzVaultItemsPreferredKeys.contains(raw)) {
                            blitzVaultItemsPreferredKeys.add(raw);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        blitzVaultItemsBlacklistedKeys.clear();
        if (config.has("blitzVaultItemsBlacklistedKeys") && config.get("blitzVaultItemsBlacklistedKeys").isJsonArray()) {
            JsonArray arr = config.getAsJsonArray("blitzVaultItemsBlacklistedKeys");
            for (int i = 0; i < arr.size(); i++) {
                try {
                    String raw = arr.get(i).getAsString();
                    if (raw != null) {
                        raw = raw.trim();
                        if (!raw.isEmpty() && !blitzVaultItemsBlacklistedKeys.contains(raw)
                                && !blitzVaultItemsPreferredKeys.contains(raw)) {
                            blitzVaultItemsBlacklistedKeys.add(raw);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (config.has("blitzVaultEnchantChanceExact")) {
            blitzVaultEnchantChanceExact = config.get("blitzVaultEnchantChanceExact").getAsBoolean();
        }
        blitzVaultEnchantChanceEnabledKeys.clear();
        if (config.has("blitzVaultEnchantChanceEnabledKeys")
                && config.get("blitzVaultEnchantChanceEnabledKeys").isJsonArray()) {
            JsonArray arr = config.getAsJsonArray("blitzVaultEnchantChanceEnabledKeys");
            for (int i = 0; i < arr.size(); i++) {
                try {
                    String raw = arr.get(i).getAsString();
                    if (raw != null) {
                        raw = raw.trim();
                        if (!raw.isEmpty() && !blitzVaultEnchantChanceEnabledKeys.contains(raw)) {
                            blitzVaultEnchantChanceEnabledKeys.add(raw);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        blitzVaultEnchantChanceCombos.clear();
        if (config.has("blitzVaultEnchantChanceCombos")
                && config.get("blitzVaultEnchantChanceCombos").isJsonArray()) {
            JsonArray arr = config.getAsJsonArray("blitzVaultEnchantChanceCombos");
            for (int i = 0; i < arr.size(); i++) {
                try {
                    String raw = arr.get(i).getAsString();
                    if (raw != null) {
                        raw = raw.trim();
                        if (!raw.isEmpty() && !blitzVaultEnchantChanceCombos.contains(raw)) {
                            blitzVaultEnchantChanceCombos.add(raw);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (config.has("starTextDuration")) {
            starTextDuration = config.get("starTextDuration").getAsFloat();
        }
        if (config.has("starTextYOffset")) {
            starTextYOffset = config.get("starTextYOffset").getAsInt();
        }
        if (config.has("starTextScale")) {
            starTextScale = config.get("starTextScale").getAsFloat();
        }
        if (config.has("lobbyPingDisplayEnabled")) {
            lobbyPingDisplayEnabled = config.get("lobbyPingDisplayEnabled").getAsBoolean();
        }
        if (config.has("mountXpOverlayEnabled")) {
            mountXpOverlayEnabled = config.get("mountXpOverlayEnabled").getAsBoolean();
        }
        if (config.has("mountXpOverlayOpacity")) {
            mountXpOverlayOpacity = Math.max(0, Math.min(255, config.get("mountXpOverlayOpacity").getAsInt()));
        }
        if (config.has("nickRealNametagOverlayEnabled")) {
            nickRealNametagOverlayEnabled = config.get("nickRealNametagOverlayEnabled").getAsBoolean();
        }
        if (config.has("nickRealNametagRankPlateEnabled")) {
            nickRealNametagRankPlateEnabled = config.get("nickRealNametagRankPlateEnabled").getAsBoolean();
        }
        if (config.has("denickerEnabled")) {
            denickerEnabled = config.get("denickerEnabled").getAsBoolean();
        } else {
            denickerEnabled = nickRealNametagOverlayEnabled;
        }
        if (config.has("denickerTagDisplayMode")) {
            denickerTagDisplayMode = normalizeDenickerTagDisplayMode(config.get("denickerTagDisplayMode").getAsString());
        } else {
            denickerTagDisplayMode = nickRealNametagRankPlateEnabled ? "RANK" : "PARENTHESES";
        }
        if (config.has("denickerRevealRealSkinEnabled")) {
            denickerRevealRealSkinEnabled = config.get("denickerRevealRealSkinEnabled").getAsBoolean();
        }
        if (config.has("denickerShowRealIgnInTabEnabled")) {
            denickerShowRealIgnInTabEnabled = config.get("denickerShowRealIgnInTabEnabled").getAsBoolean();
        }
        if (config.has("nickRenamedItemScannerEnabled")) {
            nickRenamedItemScannerEnabled = config.get("nickRenamedItemScannerEnabled").getAsBoolean();
        }
        if (config.has("nickDeathSearchLinkEnabled")) {
            nickDeathSearchLinkEnabled = config.get("nickDeathSearchLinkEnabled").getAsBoolean();
        }
        if (config.has("recentOpponentsGroupDenickedPlayersDefaultOn")
                && config.get("recentOpponentsGroupDenickedPlayersDefaultOn").getAsBoolean()) {
            if (config.has("recentOpponentsGroupDenickedPlayers")) {
                recentOpponentsGroupDenickedPlayers = config.get("recentOpponentsGroupDenickedPlayers").getAsBoolean();
            }
        } else {
            // The old default was OFF; adopt the new default once, then retain later user choices.
            recentOpponentsGroupDenickedPlayers = true;
        }
        if (config.has("recentOpponentsSortMode")) {
            recentOpponentsSortMode = Math.max(0, Math.min(4, config.get("recentOpponentsSortMode").getAsInt()));
        }
        if (config.has("guiBackdropMode")) {
            guiBackdropMode = normalizeGuiBackdropMode(config.get("guiBackdropMode").getAsString());
        }
        boolean hasSplitBackdrop = config.has("guiBackdropGomodDimPercent")
                || config.has("guiBackdropOtherDimPercent")
                || config.has("guiBackdropGomodBlurPercent")
                || config.has("guiBackdropOtherBlurPercent");
        if (hasSplitBackdrop) {
            if (config.has("guiBackdropGomodDimPercent")) {
                guiBackdropGomodDimPercent = clampGuiBackdropDim(config.get("guiBackdropGomodDimPercent").getAsInt());
            }
            if (config.has("guiBackdropOtherDimPercent")) {
                guiBackdropOtherDimPercent = clampGuiBackdropDim(config.get("guiBackdropOtherDimPercent").getAsInt());
            }
            if (config.has("guiBackdropGomodBlurPercent")) {
                guiBackdropGomodBlurPercent = clampGuiBackdropDim(config.get("guiBackdropGomodBlurPercent").getAsInt());
            }
            if (config.has("guiBackdropOtherBlurPercent")) {
                guiBackdropOtherBlurPercent = clampGuiBackdropDim(config.get("guiBackdropOtherBlurPercent").getAsInt());
            }
        } else if (config.has("guiBackdropDimPercent") || config.has("guiBackdropBlurPercent")) {
            int legacyDim = config.has("guiBackdropDimPercent")
                    ? clampGuiBackdropDim(config.get("guiBackdropDimPercent").getAsInt()) : 100;
            int legacyBlur = config.has("guiBackdropBlurPercent")
                    ? clampGuiBackdropDim(config.get("guiBackdropBlurPercent").getAsInt()) : 0;
            guiBackdropGomodDimPercent = legacyDim;
            guiBackdropOtherDimPercent = legacyDim;
            guiBackdropGomodBlurPercent = legacyBlur;
            guiBackdropOtherBlurPercent = legacyBlur;
        }
        if (config.has("guiBackdropGomodExcludeChatStrip")) {
            guiBackdropGomodExcludeChatStrip = config.get("guiBackdropGomodExcludeChatStrip").getAsBoolean();
        }
        if (config.has("guiBackdropOtherExcludeChatStrip")) {
            guiBackdropOtherExcludeChatStrip = config.get("guiBackdropOtherExcludeChatStrip").getAsBoolean();
        }
        if (config.has("hudEditorCenterGuidesAlways")) {
            hudEditorCenterGuidesAlways = config.get("hudEditorCenterGuidesAlways").getAsBoolean();
        }
        if (config.has("hideDateGameIdEnabled")) {
            hideDateGameIdEnabled = config.get("hideDateGameIdEnabled").getAsBoolean();
        }
        boolean hasScoreboardMasterKey = config.has("hideScoreboardValuesEnabled");
        boolean hasKitScoreboardKey = config.has("hideKitScoreboardEnabled");
        if (hasScoreboardMasterKey) {
            hideScoreboardValuesEnabled = config.get("hideScoreboardValuesEnabled").getAsBoolean();
        } else {
            // Legacy configs only had hideDateGameIdEnabled (which also hid kit via the same pass).
            hideScoreboardValuesEnabled = hideDateGameIdEnabled;
        }
        if (hasKitScoreboardKey) {
            hideKitScoreboardEnabled = config.get("hideKitScoreboardEnabled").getAsBoolean();
        } else {
            hideKitScoreboardEnabled = hideDateGameIdEnabled && !hasScoreboardMasterKey;
        }
        if (config.has("hideHypixelFooterEnabled")) {
            hideHypixelFooterEnabled = config.get("hideHypixelFooterEnabled").getAsBoolean();
        }
        if (config.has("sidebarBlitzHourLineEnabled")) {
            sidebarBlitzHourLineEnabled = config.get("sidebarBlitzHourLineEnabled").getAsBoolean();
        }
        if (config.has("sidebarKillEffectLineEnabled")) {
            sidebarKillEffectLineEnabled = config.get("sidebarKillEffectLineEnabled").getAsBoolean();
        }
        if (config.has("sidebarTauntWastedEnabled")) {
            sidebarTauntWastedEnabled = config.get("sidebarTauntWastedEnabled").getAsBoolean();
        }
        if (config.has("mountHeadDimEnabled")) {
            mountHeadDimEnabled = config.get("mountHeadDimEnabled").getAsBoolean();
        }
        if (config.has("mountAlphaHorse")) {
            mountAlphaHorse = clampMountAlpha(config.get("mountAlphaHorse").getAsFloat());
        }
        if (config.has("mountAlphaDonkey")) {
            mountAlphaDonkey = clampMountAlpha(config.get("mountAlphaDonkey").getAsFloat());
        }
        if (config.has("mountAlphaPig")) {
            mountAlphaPig = clampMountAlpha(config.get("mountAlphaPig").getAsFloat());
        }
        if (config.has("mountAlphaBoat")) {
            mountAlphaBoat = clampMountAlpha(config.get("mountAlphaBoat").getAsFloat());
        }
        if (config.has("mountAlphaWagon")) {
            mountAlphaWagon = clampMountAlpha(config.get("mountAlphaWagon").getAsFloat());
        }
        if (config.has("waterClarityEnabled")) {
            waterClarityEnabled = config.get("waterClarityEnabled").getAsBoolean();
        }
        if (config.has("nametagThroughBlocksEnabled")) {
            nametagThroughBlocksEnabled = config.get("nametagThroughBlocksEnabled").getAsBoolean();
        }
        if (config.has("waterClarityAmount")) {
            waterClarityAmount = clampUnitFloat(config.get("waterClarityAmount").getAsFloat());
        }
        boolean hasWaterSurfaceKey = config.has("waterClaritySurfaceFromAir");
        boolean hasWaterUnderFogKey = config.has("waterClarityUnderwaterFog");
        boolean legacyWaterClarityOn = config.has("waterClarityEnabled") && config.get("waterClarityEnabled").getAsBoolean();
        if (hasWaterUnderFogKey) {
            waterClarityUnderwaterFog = config.get("waterClarityUnderwaterFog").getAsBoolean();
        } else if (!hasWaterSurfaceKey && legacyWaterClarityOn) {
            waterClarityUnderwaterFog = true;
        } else {
            waterClarityUnderwaterFog = false;
        }
        if (hasWaterSurfaceKey) {
            waterClaritySurfaceFromAir = config.get("waterClaritySurfaceFromAir").getAsBoolean();
        } else if (!hasWaterUnderFogKey && legacyWaterClarityOn) {
            waterClaritySurfaceFromAir = false;
        } else {
            waterClaritySurfaceFromAir = true;
        }
        if (!config.has("mountAlphaHorse") && config.has("mountHeadDimAlpha")) {
            float legacy = clampMountAlpha(config.get("mountHeadDimAlpha").getAsFloat());
            mountAlphaHorse = legacy;
            mountAlphaDonkey = legacy;
            mountAlphaPig = legacy;
            mountAlphaBoat = legacy;
            mountAlphaWagon = legacy;
        }
        if (config.has("mountHeadDimStrategy")) {
            mountHeadDimStrategy = config.get("mountHeadDimStrategy").getAsInt();
            mountHeadDimStrategy = Math.max(1, Math.min(8, mountHeadDimStrategy));
        }
        if (config.has("mountDimDepthMode")) {
            mountDimDepthMode = config.get("mountDimDepthMode").getAsInt();
            mountDimDepthMode = Math.max(1, Math.min(6, mountDimDepthMode));
        }
        if (config.has("mountFlushMode")) {
            mountFlushMode = config.get("mountFlushMode").getAsInt();
            mountFlushMode = Math.max(1, Math.min(4, mountFlushMode));
            if (mountFlushMode == 3) {
                mountFlushMode = 1;
            }
        }

        // Load LootCall settings
        if (config.has("lootCallEnabled")) {
            lootCallEnabled = config.get("lootCallEnabled").getAsBoolean();
        }
        if (config.has("lootCallEverywhere")) {
            lootCallEverywhere = config.get("lootCallEverywhere").getAsBoolean();
        }
        if (config.has("lootCallAnnounceDiamond")) {
            lootCallAnnounceDiamond = config.get("lootCallAnnounceDiamond").getAsBoolean();
        }
        if (config.has("lootCallAnnounceIron")) {
            lootCallAnnounceIron = config.get("lootCallAnnounceIron").getAsBoolean();
        }
        if (config.has("lootCallAnnounceGold")) {
            lootCallAnnounceGold = config.get("lootCallAnnounceGold").getAsBoolean();
        }
        if (config.has("teamLootEnabled")) {
            teamLootEnabled = config.get("teamLootEnabled").getAsBoolean();
        }
        if (config.has("itemEspLabelExtraYOffset")) {
            itemEspLabelExtraYOffset = clampItemEspLabelExtraYOffset(config.get("itemEspLabelExtraYOffset").getAsFloat());
        }
        if (config.has("askForLootEnabled")) {
            askForLootEnabled = config.get("askForLootEnabled").getAsBoolean();
        }
        if (config.has("askForLootAllTime")) {
            askForLootAllTime = config.get("askForLootAllTime").getAsBoolean();
        }
        if (config.has("askForLootKeyCode")) {
            askForLootKeyCode = config.get("askForLootKeyCode").getAsInt();
        }
        if (config.has("playerCallerEnabled")) {
            playerCallerEnabled = config.get("playerCallerEnabled").getAsBoolean();
        }
        if (config.has("playerCallerKeyCode")) {
            playerCallerKeyCode = config.get("playerCallerKeyCode").getAsInt();
        }
        if (config.has("chestRoutesRenderEnabled")) {
            chestRoutesRenderEnabled = config.get("chestRoutesRenderEnabled").getAsBoolean();
        }
        if (config.has("chestRoutesPlaceFeetKeyCode")) {
            chestRoutesPlaceFeetKeyCode = config.get("chestRoutesPlaceFeetKeyCode").getAsInt();
        }
        if (config.has("chestRoutesToggleKeyCode")) {
            chestRoutesToggleKeyCode = config.get("chestRoutesToggleKeyCode").getAsInt();
        }
        if (config.has("chestRoutesUndoKeyCode")) {
            chestRoutesUndoKeyCode = config.get("chestRoutesUndoKeyCode").getAsInt();
        }
        loadMoreHudFromConfig(config);
        if (config.has("disguiseKillCounterMode")) {
            disguiseKillCounterMode = normalizeDisguiseToggleMode(
                    config.get("disguiseKillCounterMode").getAsString()
            );
        }
        if (config.has("disguiseTournamentKillCounterMode")) {
            disguiseTournamentKillCounterMode = normalizeDisguiseTournamentKillCounterMode(
                    config.get("disguiseTournamentKillCounterMode").getAsString()
            );
        }
        if (config.has("disguiseCustomKillCount")) {
            try {
                disguiseCustomKillCount = Math.max(0, config.get("disguiseCustomKillCount").getAsInt());
            } catch (Throwable ignored) {
                disguiseCustomKillCount = 0;
            }
        }
        if (config.has("disguiseCustomKillCountEnabled")) {
            try {
                disguiseCustomKillCountEnabled = config.get("disguiseCustomKillCountEnabled").getAsBoolean();
            } catch (Throwable ignored) {
                disguiseCustomKillCountEnabled = false;
            }
        } else if (disguiseCustomKillCount > 0) {
            // Older configs only stored the value — treat as enabled so behavior stays the same.
            disguiseCustomKillCountEnabled = true;
        }
        if (config.has("disguiseBlitzKitSelectorEnabled")) {
            disguiseBlitzKitSelectorEnabled = config.get("disguiseBlitzKitSelectorEnabled").getAsBoolean();
        }
        if (config.has("disguiseRenamedItemsMode")) {
            disguiseRenamedItemsMode = normalizeDisguiseRenamedItemsMode(
                    config.get("disguiseRenamedItemsMode").getAsString()
            );
        } else if (config.has("disguiseMaskRenamedItemsEnabled")) {
            disguiseRenamedItemsMode = config.get("disguiseMaskRenamedItemsEnabled").getAsBoolean()
                    ? "RESETED"
                    : "UNCHANGED";
        }
        if (config.has("disguiseHypixelPrefixMode")) {
            disguiseHypixelPrefixMode = normalizeDisguiseHypixelPrefixMode(
                    config.get("disguiseHypixelPrefixMode").getAsString()
            );
        } else if (config.has("disguiseHypixelPrefixEnabled")) {
            disguiseHypixelPrefixMode = config.get("disguiseHypixelPrefixEnabled").getAsBoolean()
                    ? "SHOW"
                    : "HIDE";
        }
        if (config.has("disguiseHypixelRankPreset")) {
            disguiseHypixelRankPreset = normalizeDisguiseHypixelRankPreset(
                    config.get("disguiseHypixelRankPreset").getAsString());
        }
        if (config.has("disguiseHypixelMvpPlusColor")) {
            disguiseHypixelMvpPlusColor = normalizeHypixelRankColorName(
                    config.get("disguiseHypixelMvpPlusColor").getAsString());
        }
        if (config.has("disguiseHypixelMvpBracketColor")) {
            disguiseHypixelMvpBracketColor = normalizeHypixelMvpPlusPlusColorName(
                    config.get("disguiseHypixelMvpBracketColor").getAsString());
        }
        if (config.has("disguiseHypixelMvpPlusBracketBaseColor")) {
            disguiseHypixelMvpPlusBracketBaseColor = normalizeHypixelMvpPlusPlusColorName(
                    config.get("disguiseHypixelMvpPlusBracketBaseColor").getAsString());
        }
        if (config.has("disguiseHypixelMvpPpBaseColor")) {
            disguiseHypixelMvpPpBaseColor = normalizeHypixelMvpPlusPlusColorName(
                    config.get("disguiseHypixelMvpPpBaseColor").getAsString());
        }
        if (config.has("disguiseHypixelMvpPpSignColor")) {
            disguiseHypixelMvpPpSignColor = normalizeHypixelMvpPlusPlusColorName(
                    config.get("disguiseHypixelMvpPpSignColor").getAsString());
        }
        if (config.has("disguiseHypixelRankCustom")) {
            try {
                disguiseHypixelRankCustom = config.get("disguiseHypixelRankCustom").getAsString();
            } catch (Throwable ignored) {
                disguiseHypixelRankCustom = "&7[Custom]";
            }
        }
        if (config.has("disguiseNickSetupLastNick")) {
            try {
                disguiseNickSetupLastNick = config.get("disguiseNickSetupLastNick").getAsString();
            } catch (Throwable ignored) {
                disguiseNickSetupLastNick = "";
            }
        }
        if (config.has("disguiseNickSetupLastSkinMode")) {
            disguiseNickSetupLastSkinMode = normalizeNickSkinMode(config.get("disguiseNickSetupLastSkinMode").getAsString());
        }
        if (config.has("disguiseNickSetupLastSkinRef")) {
            try {
                disguiseNickSetupLastSkinRef = config.get("disguiseNickSetupLastSkinRef").getAsString();
            } catch (Throwable ignored) {
                disguiseNickSetupLastSkinRef = "";
            }
        }
        if (config.has("disguiseRestoreOnReconnect")) {
            try {
                disguiseRestoreOnReconnect = config.get("disguiseRestoreOnReconnect").getAsBoolean();
            } catch (Throwable ignored) {
                disguiseRestoreOnReconnect = false;
            }
        }
        if (config.has("disguiseHideLunarCosmeticsOnWeave")) {
            try {
                disguiseHideLunarCosmeticsOnWeave = config.get("disguiseHideLunarCosmeticsOnWeave").getAsBoolean();
            } catch (Throwable ignored) {
                disguiseHideLunarCosmeticsOnWeave = true;
            }
        }
        if (config.has("disguiseHideLunarCosmeticsDefaultMigrated")) {
            try {
                disguiseHideLunarCosmeticsDefaultMigrated =
                        config.get("disguiseHideLunarCosmeticsDefaultMigrated").getAsBoolean();
            } catch (Throwable ignored) {
                disguiseHideLunarCosmeticsDefaultMigrated = false;
            }
        }
        if (!disguiseHideLunarCosmeticsDefaultMigrated) {
            // The previous release wrote false as the default. Migrate that old default once,
            // while allowing future user toggles to remain persistent.
            disguiseHideLunarCosmeticsOnWeave = true;
            disguiseHideLunarCosmeticsDefaultMigrated = true;
            migrateLunarCosmeticsDefault = true;
        }
        if (config.has("playerStandaloneCapeEnabled")) {
            try {
                playerStandaloneCapeEnabled = config.get("playerStandaloneCapeEnabled").getAsBoolean();
            } catch (Throwable ignored) {
                playerStandaloneCapeEnabled = false;
            }
        }
        if (config.has("playerStandaloneCapeOptionKey")) {
            try {
                playerStandaloneCapeOptionKey = config.get("playerStandaloneCapeOptionKey").getAsString();
            } catch (Throwable ignored) {
                playerStandaloneCapeOptionKey = "";
            }
        }
        playerStandaloneCapeOptionKey = normalizeStandaloneCapeOptionKey(playerStandaloneCapeOptionKey);
        if (playerStandaloneCapeOptionKey.isEmpty()) {
            playerStandaloneCapeEnabled = false;
        }
        playerStandaloneCapeOptionKeyByAccount.clear();
        if (config.has("playerStandaloneCapeOptionKeyByAccount")
                && config.get("playerStandaloneCapeOptionKeyByAccount").isJsonObject()) {
            JsonObject byAccount = config.getAsJsonObject("playerStandaloneCapeOptionKeyByAccount");
            for (Map.Entry<String, JsonElement> e : byAccount.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null || e.getValue().isJsonNull()) {
                    continue;
                }
                String accountKey = normalizeStandaloneCapeAccountKey(e.getKey());
                String optionKey = readStandaloneCapeOptionKeyFromJson(e.getValue());
                if (!accountKey.isEmpty() && !optionKey.isEmpty()) {
                    playerStandaloneCapeOptionKeyByAccount.put(accountKey, optionKey);
                }
            }
        }
        playerStandaloneCapeLegacySelectionPending = playerStandaloneCapeOptionKeyByAccount.isEmpty()
                && playerStandaloneCapeEnabled
                && !playerStandaloneCapeOptionKey.isEmpty();
        if (config.has("playerStandaloneCapeBroadcastToPeers")) {
            try {
                playerStandaloneCapeBroadcastToPeers = config.get("playerStandaloneCapeBroadcastToPeers").getAsBoolean();
            } catch (Throwable ignored) {
                playerStandaloneCapeBroadcastToPeers = true;
            }
        }
        if (config.has("playerStandaloneCapePreferGomodOnWeave")) {
            try {
                playerStandaloneCapePreferGomodOnWeave = config.get("playerStandaloneCapePreferGomodOnWeave").getAsBoolean();
            } catch (Throwable ignored) {
                playerStandaloneCapePreferGomodOnWeave = true;
            }
        }
        if (config.has("lunarTweaksEnabled")) {
            try {
                lunarTweaksEnabled = config.get("lunarTweaksEnabled").getAsBoolean();
            } catch (Throwable ignored) {
                lunarTweaksEnabled = true;
            }
        }
        if (config.has("lunarTweaksSeeGomodPlayers")) {
            try {
                lunarTweaksSeeGomodPlayers = config.get("lunarTweaksSeeGomodPlayers").getAsBoolean();
            } catch (Throwable ignored) {
                lunarTweaksSeeGomodPlayers = true;
            }
        }
        if (config.has("lunarTweaksShowToOtherPlayers")) {
            try {
                lunarTweaksShowToOtherPlayers = config.get("lunarTweaksShowToOtherPlayers").getAsBoolean();
            } catch (Throwable ignored) {
                lunarTweaksShowToOtherPlayers = true;
            }
        }
        if (config.has("lunarClothCloakEnabled")) {
            try {
                lunarClothCloakEnabled = config.get("lunarClothCloakEnabled").getAsBoolean();
            } catch (Throwable ignored) {
                lunarClothCloakEnabled = true;
            }
        }
        if (config.has("lunarTweaksAllowMultipleSameTypeCosmetics")) {
            try {
                lunarTweaksAllowMultipleSameTypeCosmetics =
                        config.get("lunarTweaksAllowMultipleSameTypeCosmetics").getAsBoolean();
            } catch (Throwable ignored) {
                lunarTweaksAllowMultipleSameTypeCosmetics = false;
            }
        }
        if (config.has("lunarLimitedCosmeticsUnlockerEnabled")) {
            try {
                lunarLimitedCosmeticsUnlockerEnabled =
                        config.get("lunarLimitedCosmeticsUnlockerEnabled").getAsBoolean();
            } catch (Throwable ignored) {
                lunarLimitedCosmeticsUnlockerEnabled = false;
            }
        }
        if (config.has("lunarCosmeticSourceBadgesEnabled")) {
            try {
                lunarCosmeticSourceBadgesEnabled = config.get("lunarCosmeticSourceBadgesEnabled").getAsBoolean();
            } catch (Throwable ignored) {
                lunarCosmeticSourceBadgesEnabled = false;
            }
        }
        lunarTweaksHiddenCosmeticPlayers.clear();
        if (config.has("lunarTweaksHiddenCosmeticPlayers")
                && config.get("lunarTweaksHiddenCosmeticPlayers").isJsonArray()) {
            for (JsonElement element : config.getAsJsonArray("lunarTweaksHiddenCosmeticPlayers")) {
                if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String name = normalizeLunarTweaksIgnoredCosmeticPlayer(element.getAsString());
                if (!name.isEmpty() && !containsLunarTweaksIgnoredCosmeticPlayer(name)
                        && lunarTweaksHiddenCosmeticPlayers.size() < 50) {
                    lunarTweaksHiddenCosmeticPlayers.add(name);
                }
            }
        }
        if (config.has("lunarPlusHideBadge")) {
            try {
                lunarPlusHideBadge = config.get("lunarPlusHideBadge").getAsBoolean();
            } catch (Throwable ignored) {
                lunarPlusHideBadge = false;
            }
        }
        if (config.has("disguiseReconnectKind")) {
            disguiseReconnectKind = normalizeDisguiseReconnectKind(config.get("disguiseReconnectKind").getAsString());
        }
        if (config.has("disguiseReconnectPlayerName")) {
            disguiseReconnectPlayerName = config.get("disguiseReconnectPlayerName").getAsString();
        }
        if (config.has("disguiseReconnectShareMode")) {
            disguiseReconnectShareMode = normalizeDisguiseReconnectShareMode(
                    config.get("disguiseReconnectShareMode").getAsString());
        }
        if (config.has("disguiseReconnectFullLunarUuid")) {
            disguiseReconnectFullLunarUuid = normalizeUndashedUuidToken(
                    config.get("disguiseReconnectFullLunarUuid").getAsString());
        }
        if (config.has("disguiseReconnectNick")) {
            disguiseReconnectNick = config.get("disguiseReconnectNick").getAsString();
        }
        if (config.has("disguiseReconnectNickRaw")) {
            disguiseReconnectNickRaw = config.get("disguiseReconnectNickRaw").getAsString();
        }
        if (config.has("disguiseReconnectNickSkinMode")) {
            disguiseReconnectNickSkinMode = normalizeNickSkinMode(config.get("disguiseReconnectNickSkinMode").getAsString());
        }
        if (config.has("disguiseReconnectNickSkinRef")) {
            disguiseReconnectNickSkinRef = config.get("disguiseReconnectNickSkinRef").getAsString();
        }

            boolean publicLunarDefaultsChanged = applyPublicLunarDefaults();
            HudOpacitySliderBoxInsets.loadFromConfig(config);

			if (migrateLunarCosmeticsDefault || migrateRelayGameFeatureDefaults
					|| migrateReplayHealthTagsDefaults || migrateKillCounterKeAllChatDefaults
					|| normalizePlayerNameDisplayModes
					|| publicLunarDefaultsChanged) {
                // Save only after the complete configuration has been loaded so the migration
                // cannot overwrite settings that appear later in this method.
                saveConfigNow();
            }

		System.out.println("[ConfigManager] Loaded configuration from " + getConfigFile().getAbsolutePath());
            loadedFromCorruptConfig = false;
            loaded = true;
        } catch (Exception e) {
            System.out.println("[ConfigManager] Error loading config: " + e.getMessage());
            e.printStackTrace();
            // Backup the corrupt file before doing anything else so the user can inspect it.
            try {
                File corrupt = configFile;
                if (corrupt.isFile()) {
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    File backup = new File(corrupt.getParent(),
                            corrupt.getName() + ".corrupt." + timestamp);
                    Files.copy(corrupt.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                    System.out.println("[ConfigManager] Backed up corrupt config to " + backup.getAbsolutePath());
                }
            } catch (Exception backupEx) {
                System.err.println("[ConfigManager] Failed to backup corrupt config: " + backupEx.getMessage());
            }
            // Load defaults in memory but do NOT overwrite the file — the corrupt copy is preserved on disk.
            loadedFromCorruptConfig = true;
            loaded = true;
            relayGameFeaturesDefaultsVersion = RelayFeatureDefaultsMigration.CURRENT_VERSION;
            forceReplayPlayerHealthTagsDefaultsVersion = ReplayHealthTagsDefaultsMigration.CURRENT_VERSION;
            killCounterShowKeAllChat = false;
            killCounterShowKeAllChatDefaultsVersion = KillCounterKeAllChatDefaultsMigration.CURRENT_VERSION;
            enforceBetterPlayerFormatCompatibility();
            applyPublicLunarDefaults();
        }
        } finally {
            loading = false;
        }
    }

    /**
     * Schedules a debounced save. The actual disk write happens 2 seconds after 
     * the last call to this method, preventing disk hammering when multiple
     * settings are changed in quick succession.
     */
    public static synchronized void saveConfig() {
        if (loadedFromCorruptConfig) {
            return;
        }
        // Keep one pending write. Repeated setters used from render/tick paths must not continuously cancel and
        // recreate ScheduledFuture instances (or postpone persistence forever while the game is busy).
        if (pendingSave != null && !pendingSave.isDone()) {
            return;
        }
        pendingSave = saveScheduler.schedule(() -> {
            synchronized (ConfigManager.class) {
                pendingSave = null;
            }
            saveConfigNow();
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static JsonObject buildConfigJsonFromCurrentState() {
        JsonObject config = new JsonObject();
        
        // Save auto-queue settings
        config.addProperty("autoQueueSoloEnabled", autoQueueSoloEnabled);
        config.addProperty("autoQueueTeamsEnabled", autoQueueTeamsEnabled);
        config.addProperty("autoQueueEnabled", autoQueueSoloEnabled || autoQueueTeamsEnabled);
        config.addProperty("autoQueueSoloDelay", autoQueueSoloDelay);
        config.addProperty("autoQueueTeamsDelay", autoQueueTeamsDelay);
        config.addProperty("mainMenuQueueButtonsEnabled", mainMenuQueueButtonsEnabled);
        
        // Save death requeue settings
        config.addProperty("deathRequeueSoloEnabled", deathRequeueSoloEnabled);
        config.addProperty("deathRequeueTeamsEnabled", deathRequeueTeamsEnabled);
        config.addProperty("deathRequeueEnabled", deathRequeueSoloEnabled || deathRequeueTeamsEnabled);
        config.addProperty("deathRequeueSoloDelay", deathRequeueSoloDelay);
        config.addProperty("deathRequeueTeamsDelay", deathRequeueTeamsDelay);
        
        // Save inventory HUD settings
        config.addProperty("inventoryHUDEnabled", inventoryHUDEnabled);
        config.addProperty("inventoryHUDShowEmpty", inventoryHUDShowEmpty);
        config.addProperty("inventoryHUDCustomX", inventoryHUDCustomX);
        config.addProperty("inventoryHUDCustomY", inventoryHUDCustomY);
        config.addProperty("inventoryHUDScale", inventoryHUDScale);
        config.addProperty("inventoryHUDBackgroundOpacity", inventoryHUDBackgroundOpacity);
        config.addProperty("inventoryHUDBackgroundColor", inventoryHUDBackgroundColor);
        config.addProperty("inventoryHUDForce", inventoryHUDForce);
        config.addProperty("friendInvHudEnabled", friendInvHudEnabled);
        config.addProperty("friendInvHudShowEmpty", friendInvHudShowEmpty);
        config.addProperty("friendInvHudCustomX", friendInvHudCustomX);
        config.addProperty("friendInvHudCustomY", friendInvHudCustomY);
        config.addProperty("friendInvHudScale", friendInvHudScale);
        config.addProperty("friendInvHudBackgroundOpacity", friendInvHudBackgroundOpacity);
        config.addProperty("friendInvHudBackgroundColor", friendInvHudBackgroundColor);
        config.addProperty("friendInvHudForce", friendInvHudForce);
        config.addProperty("friendInvHudShowRemoteHealth", friendInvHudShowRemoteHealth);
        config.addProperty("friendInvHudShowRemoteFood", friendInvHudShowRemoteFood);
        config.addProperty("friendInvHudShowRemoteXp", friendInvHudShowRemoteXp);
        config.addProperty("friendInvHudShowHeldHighlight", friendInvHudShowHeldHighlight);
        config.addProperty("friendInvHudShowDirection", friendInvHudShowDirection);
        config.addProperty("friendInvHudDirectionVertical", friendInvHudDirectionVertical);
        config.addProperty("friendInvHudTextShadow", friendInvHudTextShadow);
        config.addProperty("killCounterTabDisplayMode", killCounterTabDisplayMode);
        config.addProperty("killCounterKillEffectTabMode", killCounterKillEffectTabMode);
        config.addProperty("perspectiveDistance", perspectiveDistance);
        config.addProperty("perspectiveEnabled", perspectiveEnabled);
        config.addProperty("perspectiveScrollStep", perspectiveScrollStep);
        config.addProperty("perspectiveResetOnExit", perspectiveResetOnExit);
        config.addProperty("perspectiveResetKeyCode", perspectiveResetKeyCode);
        config.addProperty("perspectiveRequireHold", perspectiveRequireHold);
        config.addProperty("perspectiveSmoothZoom", perspectiveSmoothZoom);
        config.addProperty("perspectiveSmoothSpeed", perspectiveSmoothSpeed);
        config.addProperty("perspectiveZoomKeyCode", perspectiveZoomKeyCode);
        config.addProperty("perspectiveFOV", perspectiveFOV);
        config.addProperty("perspectiveFOVEnabled", perspectiveFOVEnabled);
        config.addProperty("perspectiveLockScroll", perspectiveLockScroll);
        config.addProperty("perspectiveAdvancedRenderDistanceKeyCode", perspectiveAdvancedRenderDistanceKeyCode);
        config.addProperty("perspectiveAdvancedRenderDistanceBlocks", perspectiveAdvancedRenderDistanceBlocks);
        config.addProperty("goStatsEnabled", goStatsEnabled);
        config.addProperty("killCounterEnabled", killCounterEnabled);
        config.addProperty("killCounterAutoKeAll", killCounterAutoKeAll);
        config.addProperty("killCounterAutoScanMode", killCounterAutoScanMode);
        config.addProperty("killCounterShowGuildTag", killCounterShowGuildTag);
        config.addProperty("killCounterBetterPlayerFormat", killCounterBetterPlayerFormat);
        config.addProperty("killCounterShowTeammateKits", killCounterShowTeammateKits);
        config.addProperty("killCounterShowEnemyKits", killCounterShowEnemyKits);
        config.addProperty("killCounterShowKeAllChat", killCounterShowKeAllChat);
        config.addProperty("killCounterStatsDisplay", killCounterStatsDisplay);
        config.addProperty("killCounterStatsShowKd", killCounterStatsShowKd);
        config.addProperty("killCounterStatsShowWl", killCounterStatsShowWl);
        config.addProperty("killCounterStatsShowW", killCounterStatsShowW);
        config.addProperty("killCounterWlMode", killCounterWlMode);
        config.addProperty("killCounterTabListSortMode", killCounterTabListSortMode);
        config.addProperty("killCounterTabListSortGameScope", killCounterTabListSortGameScope);
        config.addProperty("killCounterTabListSortDistrictEnabled", killCounterTabListSortDistrictEnabled);
        config.addProperty("killCounterTabKillersEnabled", killCounterTabKillersEnabled);
        config.addProperty("killCounterTabKillersKeyCode", killCounterTabKillersKeyCode);
        config.addProperty("killCounterTabKillersVictimOffsetPx", killCounterTabKillersVictimOffsetPx);
        config.addProperty("killCounterTabKillersVictimKillCountAligned", killCounterTabKillersVictimKillCountAligned);
        config.addProperty("killCounterTabKillersVictimKillCountOffsetPx", killCounterTabKillersVictimKillCountOffsetPx);
        config.addProperty("killCounterTabKillersVictimDimmed", killCounterTabKillersVictimDimmed);
        config.addProperty("killCounterBlitzStarTabIconEnabled", killCounterBlitzStarTabIconEnabled);
        config.addProperty("killCounterSortTabByKills",
                "KILLS_THEN_WLR".equalsIgnoreCase(killCounterTabListSortMode)
                        || "KILLS_THEN_WINS".equalsIgnoreCase(killCounterTabListSortMode)
                        || "KILLS_THEN_OVERALL_KILLS".equalsIgnoreCase(killCounterTabListSortMode));
			// Save global chat settings
        config.addProperty("globalChatEnabled", globalChatEnabled);
        config.addProperty("globalChatEndpoint", globalChatEndpoint);
        config.addProperty("globalChatChannel", globalChatChannel);
        config.addProperty("globalChatPollSeconds", globalChatPollSeconds);
        config.addProperty("debugMessagesToChatEnabled", debugMessagesToChatEnabled);
        config.addProperty("blitzExtendedResultsEnabled", blitzExtendedResultsEnabled);
        config.addProperty("blitzPregamePlayerToggleEnabled", blitzPregamePlayerToggleEnabled);
        config.addProperty("deathmatchTimerBossBarEnabled", deathmatchTimerBossBarEnabled);
        config.addProperty("deathmatchTimerBossBarThresholdSeconds", deathmatchTimerBossBarThresholdSeconds);
        config.addProperty("silentSpawnedMobStepsEnabled", silentSpawnedMobStepsEnabled);
        config.addProperty("silentSpawnedOcelotSteps", silentSpawnedOcelotSteps);
        config.addProperty("silentSpawnedWitchSteps", silentSpawnedWitchSteps);
        config.addProperty("blitzExtendedResultsMorePositionsEnabled", blitzExtendedResultsMorePositionsEnabled);
        config.addProperty("blitzExtendedResultsStarInfoEnabled", blitzExtendedResultsStarInfoEnabled);
        config.addProperty("blitzExtendedResultsDeathmatchPlayersEnabled", blitzExtendedResultsDeathmatchPlayersEnabled);
        config.addProperty("blitzExtendedResultsMessageCountEnabled", blitzExtendedResultsMessageCountEnabled);
        config.addProperty("blitzExtendedResultsGameDurationEnabled", blitzExtendedResultsGameDurationEnabled);
        config.addProperty("blitzExtendedResultsMapPlayerCountEnabled", blitzExtendedResultsMapPlayerCountEnabled);
        config.addProperty("debugHitsEnabled", debugHitsEnabled);
        config.addProperty("denickCrowdSyncEnabled", denickCrowdSyncEnabled);
        config.addProperty("denickCrowdSyncBaseUrl", denickCrowdSyncBaseUrl == null ? "" : denickCrowdSyncBaseUrl);
        config.addProperty("shareInvRelayUrl", shareInvRelayUrl);
        config.addProperty("shareInvSecret", shareInvSecret);
        config.addProperty("shareInvAutoConnect", shareInvAutoConnect);
        config.addProperty("lastNotifiedModVersion",
                lastNotifiedModVersion == null ? "" : lastNotifiedModVersion);
        syncGomodFriendsForCurrentAccount();
        com.google.gson.JsonObject friendsByAcc = new com.google.gson.JsonObject();
        for (java.util.Map.Entry<String, java.util.List<String>> e : gomodFriendsByAccount.entrySet()) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            if (e.getValue() != null) {
                for (String f : e.getValue()) {
                    if (f != null && !f.trim().isEmpty()) {
                        arr.add(new com.google.gson.JsonPrimitive(f.trim()));
                    }
                }
            }
            friendsByAcc.add(e.getKey(), arr);
        }
        config.add("gomodFriendsByAccount", friendsByAcc);
        config.addProperty("gomodSocialInstallId", getOrCreateGomodSocialInstallId());
        config.addProperty("gomodSocialId", getOrCreateGomodSocialId());
        com.google.gson.JsonObject friendSid = new com.google.gson.JsonObject();
        for (java.util.Map.Entry<String, String> e : gomodFriendSocialIds.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                friendSid.addProperty(e.getKey(), e.getValue());
            }
        }
        config.add("gomodFriendSocialIds", friendSid);
        syncGomodFriendRequestsForCurrentAccount();
        config.add("gomodIncomingFriendRequestsByAccount",
                buildGomodFriendRequestMapJson(gomodIncomingFriendRequestsByAccount));
        config.add("gomodOutgoingFriendRequestsByAccount",
                buildGomodFriendRequestMapJson(gomodOutgoingFriendRequestsByAccount));
        config.add("gomodSeenIncomingFriendRequestsByAccount",
                buildGomodFriendRequestMapJson(gomodSeenIncomingFriendRequestsByAccount));
        config.addProperty("gomodFriendRequestResetGeneration", gomodFriendRequestResetGeneration);

		// Save health indicator settings
		// Replay settings
		config.addProperty("replayHealthDisplayEnabled", replayHealthDisplayEnabled);
		config.addProperty("replayHealthDisplayStyle", replayHealthDisplayStyle);
		config.addProperty("replayHealthDisplayYOffset", replayHealthDisplayYOffset);
		config.addProperty("replayShowInvisibleEnabled", replayShowInvisibleEnabled);
		
		// Save player target settings
		config.addProperty("playerTargetEnabled", playerTargetEnabled);
		config.addProperty("playerTargetGlowTeammateMode", playerTargetGlowTeammateMode);
		config.addProperty("playerTargetTeammateTrailEnabled", playerTargetTeammateTrailEnabled);
		config.addProperty("playerTargetShowVerticalDistance", playerTargetShowVerticalDistance);
		config.addProperty("playerTargetShowBackground", playerTargetShowBackground);
		config.addProperty("playerTargetBackgroundOpacity", playerTargetBackgroundOpacity);
		config.addProperty("playerTargetBackgroundColor", playerTargetBackgroundColor);
		config.addProperty("playerTargetPosition", playerTargetPosition);
		config.addProperty("playerTargetCustomX", playerTargetCustomX);
		config.addProperty("playerTargetCustomY", playerTargetCustomY);
		config.addProperty("playerTargetShowEnchantments", playerTargetShowEnchantments);
		config.addProperty("playerTargetShowTauntInfo", playerTargetShowTauntInfo);
		config.addProperty("playerTargetTextShadow", playerTargetTextShadow);
		config.addProperty("playerTargetTeamInviteNotifierEnabled", playerTargetTeamInviteNotifierEnabled);
		config.addProperty("playerTargetScale", playerTargetScale);
		
		// Save MobHealthDisplay settings
		config.addProperty("mobHealthDisplayEnabled", mobHealthDisplayEnabled);
		config.addProperty("mobHealthDisplayShowBackground", mobHealthDisplayShowBackground);
		config.addProperty("mobHealthDisplayShowEffects", mobHealthDisplayShowEffects);
		config.addProperty("mobHealthDisplayTextShadow", mobHealthDisplayTextShadow);
		config.addProperty("mobHealthDisplayBackgroundOpacity", mobHealthDisplayBackgroundOpacity);
		config.addProperty("mobHealthDisplayBackgroundColor", mobHealthDisplayBackgroundColor);
		config.addProperty("mobHealthDisplayMaxDistance", mobHealthDisplayMaxDistance);
		config.addProperty("mobHealthDisplayPersistenceTime", mobHealthDisplayPersistenceTime);
		config.addProperty("mobHealthDisplayPanelX", mobHealthDisplayPanelX);
		config.addProperty("mobHealthDisplayPanelY", mobHealthDisplayPanelY);
		config.addProperty("mobHealthDisplayScale", mobHealthDisplayScale);
		config.addProperty("mobHealthDisplayMode", mobHealthDisplayMode);
		config.addProperty("mobHealthDisplayHealthTagDisabledIds",
				formatMobHealthDisplayHealthTagDisabledIds());
		config.addProperty("mobHealthDisplayHealthTagChicken",
				isMobHealthDisplayHealthTagEnabledForType(93));
		config.addProperty("mobHealthDisplayHealthTagOcelot",
				isMobHealthDisplayHealthTagEnabledForType(98));
		config.addProperty("mobHealthDisplayHealthTagRabbit",
				isMobHealthDisplayHealthTagEnabledForType(101));
		config.addProperty("horseDonkeyDistanceHudEnabled", horseDonkeyDistanceHudEnabled);
		config.addProperty("horseDonkeyShowBackground", horseDonkeyShowBackground);
		config.addProperty("horseDonkeyTeleportSoundEnabled", horseDonkeyTeleportSoundEnabled);
		config.addProperty("horseDonkeyBackgroundOpacity", horseDonkeyBackgroundOpacity);
		config.addProperty("horseDonkeyBackgroundColor", horseDonkeyBackgroundColor);
		config.addProperty("horseDonkeyPanelX", horseDonkeyPanelX);
		config.addProperty("horseDonkeyPanelY", horseDonkeyPanelY);
		config.addProperty("horseDonkeyScale", horseDonkeyScale);
		config.addProperty("horseDonkeyHudTitleColor", horseDonkeyHudTitleColor);
		config.addProperty("horseDonkeyHudValueColor", horseDonkeyHudValueColor);
		config.addProperty("horseDonkeyHudBlocksColor", horseDonkeyHudBlocksColor);
		config.addProperty("horseFoodNametagEnabled", horseFoodNametagEnabled);
		config.addProperty("horseDonkeyTextShadow", horseDonkeyTextShadow);
		config.addProperty("gomodMenuMoveEnabled", gomodMenuMoveEnabled);
		config.addProperty("gomodMenuDragLookEnabled", gomodMenuDragLookEnabled);
		config.addProperty("minecraftMenuControlsEnabled", minecraftMenuControlsEnabled);
		config.addProperty("lunarMenuControlsEnabled", lunarMenuControlsEnabled);
		config.addProperty("gomodMenuGuiScale", gomodMenuGuiScale);
		config.addProperty("hypixelTabDupeHideEnabled", hypixelTabDupeHideEnabled);
		config.addProperty("hypixelTabQuitGhostHideEnabled", hypixelTabQuitGhostHideEnabled);
		config.addProperty("forceReplayPlayerHealthTagsEnabled", forceReplayPlayerHealthTagsEnabled);
		config.addProperty("blitzRektHitboxOverlayEnabled", blitzRektHitboxOverlayEnabled);
		config.addProperty("blitzRektHitboxPreviewLeft", blitzRektHitboxPreviewLeft);
		config.addProperty("blitzRektHitboxPreviewRight", blitzRektHitboxPreviewRight);
		config.addProperty("blitzRektHitboxPreviewUp", blitzRektHitboxPreviewUp);
		config.addProperty("blitzRektHitboxPreviewDown", blitzRektHitboxPreviewDown);
		config.addProperty("forceReplayPlayerHealthTagsDefaultsVersion",
				forceReplayPlayerHealthTagsDefaultsVersion);
		config.addProperty("killCounterShowKeAllChatDefaultsVersion",
				killCounterShowKeAllChatDefaultsVersion);
		config.addProperty("mobNametagsEnabled", mobNametagsEnabled);
		config.addProperty("friendlyMobNametagColorEnabled", friendlyMobNametagColorEnabled);
        config.addProperty("mobTargetDisplayEnabled", mobTargetDisplayEnabled);
		config.addProperty("duelPredictorEnabled", duelPredictorEnabled);
		config.addProperty("duelPredictorGhostEnabled", duelPredictorGhostEnabled);
		config.addProperty("duelPredictorHudEnabled", duelPredictorHudEnabled);
		config.addProperty("duelPredictorLeadTicks", duelPredictorLeadTicks);
		config.addProperty("duelPredictorHudPosX", duelPredictorHudPosX);
		config.addProperty("duelPredictorHudPosY", duelPredictorHudPosY);
		config.addProperty("duelPredictorHudScale", duelPredictorHudScale);
		config.addProperty("duelPredictorGhostColorArgb", duelPredictorGhostColorArgb);
		JsonArray blitzChallengeTiersArr = new JsonArray();
		for (String tierKey : blitzChallengesCompletedTiers) {
			blitzChallengeTiersArr.add(new JsonPrimitive(tierKey));
		}
		config.add("blitzChallengesCompletedTiers", blitzChallengeTiersArr);
		config.addProperty("blitzChallengesHudEnabled", blitzChallengesHudEnabled);
		config.addProperty("blitzChallengesHudPosX", blitzChallengesHudPosX);
		config.addProperty("blitzChallengesHudPosY", blitzChallengesHudPosY);
		config.addProperty("blitzChallengesHudScale", blitzChallengesHudScale);
		config.addProperty("blitzChallengesHudTextShadow", blitzChallengesHudTextShadow);
		config.addProperty("blitzChallengesHudBg", blitzChallengesHudBg);
		config.addProperty("blitzChallengesHudBgColor", blitzChallengesHudBgColor);
		config.addProperty("blitzChallengeBadgeEnabled", blitzChallengeBadgeEnabled);
		config.addProperty("blitzChallengeBadgeEquippedKey",
				blitzChallengeBadgeEquippedKey == null ? "" : blitzChallengeBadgeEquippedKey);
		config.addProperty("blitzChallengeBadgeOffsetX", blitzChallengeBadgeOffsetX);
		config.addProperty("blitzChallengeBadgeOffsetY", blitzChallengeBadgeOffsetY);
		config.addProperty("blitzChallengeBadgeOffsetZ", blitzChallengeBadgeOffsetZ);
		config.addProperty("blitzChallengeBadgeScale", blitzChallengeBadgeScale);
		config.addProperty("blitzChallengeBadgeAttachPart",
				blitzChallengeBadgeAttachPart == null ? "body" : blitzChallengeBadgeAttachPart);
		config.add("blitzChallengeBadgePlacements", cloneJsonArray(blitzChallengeBadgePlacements));
		config.addProperty("mobTargetMarkerText", mobTargetMarkerText);
		config.addProperty("disguiseMobVisualKind", disguiseMobVisualKind);
		config.addProperty("disguiseMobVisualLastUsed", disguiseMobVisualLastUsed);
		config.addProperty("blitzKitTimerHudEnabled", blitzKitTimerHudEnabled);
		config.addProperty("blitzKitTimerHudScale", blitzKitTimerHudScale);
		config.addProperty("blitzKitTimerHudOffsetCenterX", blitzKitTimerHudOffsetCenterX);
		config.addProperty("blitzKitTimerHudPosY", blitzKitTimerHudPosY);
		config.addProperty("blitzKitTimerHudTextShadow", blitzKitTimerHudTextShadow);
		config.addProperty("blitzKitTimerHudBg", blitzKitTimerHudBg);
		config.addProperty("blitzKitTimerHudBgColor", blitzKitTimerHudBgColor);
		config.addProperty("acceptIncomingFriendScreenshots", acceptIncomingFriendScreenshots);
		config.addProperty("acceptIncomingNonFriendMessages", acceptIncomingNonFriendMessages);
		config.addProperty("acceptIncomingShareInv", acceptIncomingShareInv);
		config.addProperty("socialMsgIconHudEnabled", socialMsgIconHudEnabled);
		config.addProperty("publicMessagePopupsEnabled", publicMessagePopupsEnabled);
		config.addProperty("incomingScreenshotOverlayEnabled", incomingScreenshotOverlayEnabled);
		config.addProperty("incomingScreenshotTextShadow", incomingScreenshotTextShadow);
		config.addProperty("incomingScreenshotHudPosX", incomingScreenshotHudPosX);
		config.addProperty("incomingScreenshotHudPosY", incomingScreenshotHudPosY);
		config.addProperty("incomingScreenshotHudScale", incomingScreenshotHudScale);
        config.addProperty("ptracersCagesEnabled", ptracersCagesEnabled);
        config.addProperty("cageOpeningSoundEnabled", cageOpeningSoundEnabled);
        config.addProperty("gameTeleporterSoundEnabled", gameTeleporterSoundEnabled);
        config.addProperty("blitzHourCageSfxEnabled", blitzHourCageSfxEnabled);
        config.addProperty("cageChestOverlayEnabled", cageChestOverlayEnabled);
        config.addProperty("cageMiddleLootEstimateEnabled", cageMiddleLootEstimateEnabled);
        config.addProperty("ptracersTargetNametagEnabled", ptracersTargetNametagEnabled);
        config.addProperty("tabStatsRuntimeMuteEnabled", tabStatsRuntimeMuteEnabled);
        config.addProperty("weaveDevTabStatsSeparated", weaveDevTabStatsSeparated);
		JsonObject byAccount = new JsonObject();
		for (Map.Entry<String, String> e : blitzKillEffectByAccountUuid.entrySet()) {
			if (e == null || e.getKey() == null || e.getValue() == null) {
				continue;
			}
			String key = normalizeAccountUuidKey(e.getKey());
			String code = normalizePersistentKillEffectCode(e.getValue());
			if (!key.isEmpty() && code != null) {
				byAccount.addProperty(key, code);
			}
		}
		config.add("blitzKillEffectByAccountUuid", byAccount);
		
		// Save FakeBreak setting
		config.addProperty("fakeBreakEnabled", fakeBreakEnabled);
		
		// Save BestArmorDisplay setting
		config.addProperty("bestArmorDisplayEnabled", bestArmorDisplayEnabled);
		config.addProperty("bestArmorPercentageMode", bestArmorPercentageMode);
		config.addProperty("bestArmorContainerRenderStrategy", bestArmorContainerRenderStrategy);
		config.addProperty("bestArmorDebugOverlayPaint", bestArmorDebugOverlayPaint);
		config.addProperty("uselessItemOverlayEnabled", uselessItemOverlayEnabled);
        config.addProperty("uselessItemOverlayForceEverywhere", uselessItemOverlayForceEverywhere);
        config.addProperty("uselessItemOverlayChestPickHintsEnabled", uselessItemOverlayChestPickHintsEnabled);
        config.addProperty("uselessChestHintBlueFillHex", uselessChestHintBlueFillHex);
        config.addProperty("uselessChestHintBlueEdgeHex", uselessChestHintBlueEdgeHex);
        config.addProperty("uselessChestHintYellowFillHex", uselessChestHintYellowFillHex);
        config.addProperty("uselessChestHintYellowEdgeHex", uselessChestHintYellowEdgeHex);
        config.addProperty("uselessAlwaysRedBowl", uselessAlwaysRedBowl);
        config.addProperty("uselessAlwaysRedCake", uselessAlwaysRedCake);
        config.addProperty("uselessAlwaysRedChestMinecart", uselessAlwaysRedChestMinecart);
        config.addProperty("uselessAlwaysRedMinecart", uselessAlwaysRedMinecart);
        config.addProperty("uselessAlwaysRedRail", uselessAlwaysRedRail);
        config.addProperty("uselessAlwaysRedRose", uselessAlwaysRedRose);
        config.addProperty("uselessAlwaysRedWheat", uselessAlwaysRedWheat);
        config.addProperty("uselessAlwaysRedSharpnessFish", uselessAlwaysRedSharpnessFish);
        config.addProperty("uselessAlwaysRedApple", uselessAlwaysRedApple);
        config.addProperty("uselessAlwaysRedStick", uselessAlwaysRedStick);
        config.addProperty("uselessMeleeIronVsStoneSharpRed", uselessMeleeIronVsStoneSharpRed);
        config.addProperty("uselessMeleeDiamondVsIronSharpRed", uselessMeleeDiamondVsIronSharpRed);
        config.addProperty("uselessMeleeStoneVsGoldWoodSharpRed", uselessMeleeStoneVsGoldWoodSharpRed);
		config.addProperty("tntPlacementPreviewEnabled", tntPlacementPreviewEnabled);
		config.addProperty("chickenPlacementPreviewEnabled", chickenPlacementPreviewEnabled);
		config.addProperty("pigPlacementPreviewEnabled", pigPlacementPreviewEnabled);
		config.addProperty("horsePlacementPreviewEnabled", horsePlacementPreviewEnabled);
		config.addProperty("donkeyPlacementPreviewEnabled", donkeyPlacementPreviewEnabled);
		config.addProperty("gomodBlockOverlayEnabled", gomodBlockOverlayEnabled);
		config.addProperty("gomodBlockOverlayMode", gomodBlockOverlayMode);
		config.addProperty("gomodBlockOverlayColorArgb", gomodBlockOverlayColorArgb);
		config.addProperty("gomodBlockOverlayLineWidth", gomodBlockOverlayLineWidth);
		config.addProperty("gomodBlockOverlayLinePlacement", gomodBlockOverlayLinePlacement);
		config.addProperty("gomodBlockOverlayFillEnabled", gomodBlockOverlayFillEnabled);
		config.addProperty("gomodBlockOverlayFillOpacity", gomodBlockOverlayFillOpacity);
		config.addProperty("gomodBlockOverlaySideShapeAware", gomodBlockOverlaySideShapeAware);
		config.addProperty("gomodBlockOverlayHideBarriersInCages", gomodBlockOverlayHideBarriersInCages);
		config.addProperty("gomodBlockOverlayPlayerOverlayEnabled", gomodBlockOverlayPlayerOverlayEnabled);
		config.addProperty("lightningNerfEnabled", lightningNerfEnabled);
		config.addProperty("lightningFixRenderEnabled", lightningFixRenderEnabled);
		config.addProperty("lightningNerfBottomBlocks", lightningNerfBottomBlocks);
		config.addProperty("lightningCustomColorEnabled", lightningCustomColorEnabled);
		config.addProperty("lightningRandomColorEnabled", lightningRandomColorEnabled);
		config.addProperty("lightningBoltColorArgb", lightningBoltColorArgb);
		config.addProperty("lightningOpacityPercent", lightningOpacityPercent);
		config.addProperty("lightningSpeedPercent", lightningSpeedPercent);
		config.addProperty("lightningSoundVolumePercent", lightningSoundVolumePercent);
		config.addProperty("lightningPostCageSoundEnabled", lightningPostCageSoundEnabled);
		config.addProperty("lightningPostCageSoundDurationSec", lightningPostCageSoundDurationSec);
		config.addProperty("lightningPostCageSpeedPercent", lightningPostCageSpeedPercent);
		config.addProperty("lightningPostCageVolumePercent", lightningPostCageVolumePercent);
		config.addProperty("witchSoundsEnabled", witchSoundsEnabled);
		config.addProperty("witchSfxVolIdle", witchSfxVolIdle);
		config.addProperty("witchSfxVolHurt", witchSfxVolHurt);
		config.addProperty("witchSfxVolDeath", witchSfxVolDeath);
		config.addProperty("witchSfxVolThrow", witchSfxVolThrow);
		config.addProperty("witchSfxVolDrink", witchSfxVolDrink);
		config.addProperty("witchSfxVolCelebrate", witchSfxVolCelebrate);
		config.addProperty("forceResistanceParticlesEnabled", forceResistanceParticlesEnabled);
		config.addProperty("forceResistanceSpawnsPerTick", forceResistanceSpawnsPerTick);
		config.addProperty("forceResistanceOpacityScale", forceResistanceOpacityScale);
		config.addProperty("forceResistanceSaturation", forceResistanceSaturation);
		
		// Save RadialMenu settings
		config.addProperty("radialMenuEnabled", radialMenuEnabled);
		config.addProperty("radialMenuKeyCode", radialMenuKeyCode);
		config.addProperty("chatMovementKeyCode", chatMovementKeyCode);
		config.addProperty("chatMovementDragLookEnabled", chatMovementDragLookEnabled);
		config.addProperty("chatMovementPassthroughKeyCode", chatMovementPassthroughKeyCode);
		config.addProperty("teamNickFinderLastFoundRelative", teamNickFinderLastFoundRelative);
		config.addProperty("teamNickFinder24HourClock", teamNickFinder24HourClock);
		config.addProperty("teamNickFinderZoneId", teamNickFinderZoneId);
		config.addProperty("gomodFriendsListKeyCode", gomodFriendsListKeyCode);
		config.addProperty("gomodFriendsListSecondKeyCode", gomodFriendsListSecondKeyCode);
		config.addProperty("gomodSocialRequireCtrl", gomodSocialRequireCtrl);
		config.addProperty("autoAcceptIncomingCapes", autoAcceptIncomingCapes);
		config.addProperty("barrierVisibilityEnabled", barrierVisibilityEnabled);
		config.addProperty("barrierVisibilityKeyCode", barrierVisibilityKeyCode);
		config.addProperty("barrierVisibilityStyle", barrierVisibilityStyle);
		config.addProperty("barrierOutlineThickness", barrierOutlineThickness);
		config.addProperty("barrierOutlineColorArgb", barrierOutlineColorArgb);
		config.addProperty("barrierVisibilityRangeEnabled", barrierVisibilityRangeEnabled);
		config.addProperty("barrierVisibilityRange", barrierVisibilityRange);
		config.addProperty("barrierVisibilityShowAtCursor", barrierVisibilityShowAtCursor);
		config.addProperty("barrierVisibilityAimedOnly", barrierVisibilityAimedOnly);
		config.addProperty("barrierVisibilityOpacityPercent", barrierVisibilityOpacityPercent);
		config.addProperty("barrierBlocksShownInCages", barrierBlocksShownInCages);
		config.addProperty("blocksFinderEnabled", blocksFinderEnabled);
		config.addProperty("blocksFinderKeyCode", blocksFinderKeyCode);
		config.addProperty("blocksFinderEspMode", blocksFinderEspMode);
		config.addProperty("blocksFinderColorR", blocksFinderColorR);
		config.addProperty("blocksFinderColorG", blocksFinderColorG);
		config.addProperty("blocksFinderColorB", blocksFinderColorB);
		config.addProperty("blocksFinderFillAlpha", blocksFinderFillAlpha);
		config.addProperty("blocksFinderWorldOpacity", blocksFinderWorldOpacity);
		config.addProperty("blocksFinderWorldOpacityKeyCode", blocksFinderWorldOpacityKeyCode);
		JsonObject blockColorsJson = new JsonObject();
		for (Map.Entry<String, Integer> colorEntry : blocksFinderBlockColors.entrySet()) {
			blockColorsJson.addProperty(colorEntry.getKey(), colorEntry.getValue());
		}
		config.add("blocksFinderBlockColors", blockColorsJson);
		JsonArray blocksFinderArr = new JsonArray();
		for (String s : blocksFinderBlocks) {
			if (s != null && !s.trim().isEmpty()) blocksFinderArr.add(new JsonPrimitive(s));
		}
		config.add("blocksFinderBlocks", blocksFinderArr);

		// Save NoHitDelay setting
		config.addProperty("noHitDelayEnabled", noHitDelayEnabled);

		// Save CPS cap
		config.addProperty("cpsLimit", cpsLimit);
		
		// Save VisibleSpecs setting
		config.addProperty("seeSpectatorsWhileAlive", seeSpectatorsWhileAlive);
		config.addProperty("forceGameChats", forceGameChats);
		config.addProperty("unbotheredTaunt", unbotheredTaunt);
		config.addProperty("relayGameFeaturesDefaultsVersion", relayGameFeaturesDefaultsVersion);
		config.addProperty("visibleSpecsEnabled", visibleSpecsEnabled);
		config.addProperty("spectatorBypassAllInvisible", spectatorBypassAllInvisible);
		config.addProperty("visibleSpecsNametagMode", visibleSpecsNametagMode);
		config.addProperty("visibleSpecsKillerNametagEnabled", visibleSpecsKillerNametagEnabled);
		config.addProperty("visibleSpecsApplyToReplaysEnabled", visibleSpecsApplyToReplaysEnabled);
		config.addProperty("visibleSpecsOpacity", visibleSpecsOpacity);

		// Save SelfOpacity settings
		config.addProperty("selfOpacityEnabled", selfOpacityEnabled);
		config.addProperty("selfOpacityAlpha", selfOpacityAlpha);
		config.addProperty("selfOpacityFirstPersonHandAlpha", selfOpacityFirstPersonHandAlpha);
		config.addProperty("selfOpacityDisableDuringBlindness", selfOpacityDisableDuringBlindness);
		config.addProperty("selfOpacityKeyCode", selfOpacityKeyCode);

		// Save Schizo setting
		config.addProperty("schizoEnabled", schizoEnabled);
		
        // Save BorderIndication settings
        config.addProperty("borderIndicationEnabled", borderIndicationEnabled);
        config.addProperty("borderIndicationOpacity", borderIndicationOpacity);
        config.addProperty("borderIndicationRenderDistance", borderIndicationRenderDistance);

        // Save StarText settings
        config.addProperty("starTextEnabled", starTextEnabled);
        config.addProperty("starPredictEnabled", starPredictEnabled);
        config.addProperty("hotbarEnchantTextEnabled", hotbarEnchantTextEnabled);
        config.addProperty("hotbarEnchantTextShadow", hotbarEnchantTextShadow);
        config.addProperty("hotbarEnchantTextColor", hotbarEnchantTextColor & 0xFFFFFF);
        config.addProperty("hotbarEnchantPotionTextStyle", hotbarEnchantPotionTextStyle);
        config.addProperty("hotbarEnchantTextGapAboveName", hotbarEnchantTextGapAboveName);
        config.addProperty("blitzVaultItemsSortMode", blitzVaultItemsSortMode);
        config.addProperty("blitzVaultItemsSortKeys", blitzVaultItemsSortKeys == null ? "" : blitzVaultItemsSortKeys);
        config.addProperty("blitzVaultItemsShowSeenInTooltip", blitzVaultItemsShowSeenInTooltip);
        config.addProperty("blitzVaultItemsObservedCategory", blitzVaultItemsObservedCategory == null ? "" : blitzVaultItemsObservedCategory);
        config.addProperty("blitzVaultItemsChestSortMode",
                blitzVaultItemsChestSortMode == null ? "CHEST_NUMBER" : blitzVaultItemsChestSortMode);
        config.addProperty("blitzChestItemsSortMode",
                blitzChestItemsSortMode == null ? "MOST_OBSERVED" : blitzChestItemsSortMode);
        config.addProperty("blitzChestItemsShowSeenInTooltip", blitzChestItemsShowSeenInTooltip);
        config.addProperty("blitzChestItemsAggregateVariants", blitzChestItemsAggregateVariants);
        config.addProperty("blitzChestItemsAggregateCombatTier", blitzChestItemsAggregateCombatTier);
        config.addProperty("blitzChestItemsChestSortMode",
                blitzChestItemsChestSortMode == null ? "CHEST_NUMBER" : blitzChestItemsChestSortMode);
        JsonArray blitzVaultItemsPreferredArr = new JsonArray();
        for (String key : blitzVaultItemsPreferredKeys) {
            if (key != null && !key.trim().isEmpty()) {
                blitzVaultItemsPreferredArr.add(new JsonPrimitive(key.trim()));
            }
        }
        config.add("blitzVaultItemsPreferredKeys", blitzVaultItemsPreferredArr);
        JsonArray blitzVaultItemsBlacklistedArr = new JsonArray();
        for (String key : blitzVaultItemsBlacklistedKeys) {
            if (key != null && !key.trim().isEmpty()) {
                blitzVaultItemsBlacklistedArr.add(new JsonPrimitive(key.trim()));
            }
        }
        config.add("blitzVaultItemsBlacklistedKeys", blitzVaultItemsBlacklistedArr);
        config.addProperty("blitzVaultEnchantChanceExact", blitzVaultEnchantChanceExact);
        JsonArray blitzVaultEnchantChanceKeysArr = new JsonArray();
        for (String key : blitzVaultEnchantChanceEnabledKeys) {
            if (key != null && !key.trim().isEmpty()) {
                blitzVaultEnchantChanceKeysArr.add(new JsonPrimitive(key.trim()));
            }
        }
        config.add("blitzVaultEnchantChanceEnabledKeys", blitzVaultEnchantChanceKeysArr);
        JsonArray blitzVaultEnchantChanceCombosArr = new JsonArray();
        for (String combo : blitzVaultEnchantChanceCombos) {
            if (combo != null && !combo.trim().isEmpty()) {
                blitzVaultEnchantChanceCombosArr.add(new JsonPrimitive(combo.trim()));
            }
        }
        config.add("blitzVaultEnchantChanceCombos", blitzVaultEnchantChanceCombosArr);
        config.addProperty("dcChatMirrorEnabled", dcChatMirrorEnabled);
        JsonArray dcRecipientsArr = new JsonArray();
        for (String id : dcMirrorRecipientIds) {
            if (id != null && !id.trim().isEmpty()) {
            dcRecipientsArr.add(new JsonPrimitive(id.trim()));
            }
        }
        config.add("dcMirrorRecipientIds", dcRecipientsArr);
        config.addProperty("dcMirrorSelectedRecipientId",
            dcMirrorSelectedRecipientId == null ? "" : dcMirrorSelectedRecipientId);
        config.addProperty("dcMirrorIncomingOnlySelectedRecipient", dcMirrorIncomingOnlySelectedRecipient);
        config.addProperty("starTextDuration", starTextDuration);
        config.addProperty("starTextYOffset", starTextYOffset);
        config.addProperty("starTextScale", starTextScale);
        config.addProperty("lobbyPingDisplayEnabled", lobbyPingDisplayEnabled);
        config.addProperty("mountXpOverlayEnabled", mountXpOverlayEnabled);
        config.addProperty("mountXpOverlayOpacity", mountXpOverlayOpacity);
        config.addProperty("nickRealNametagOverlayEnabled", nickRealNametagOverlayEnabled);
        config.addProperty("nickRealNametagRankPlateEnabled", nickRealNametagRankPlateEnabled);
        config.addProperty("denickerEnabled", denickerEnabled);
        config.addProperty("denickerTagDisplayMode", denickerTagDisplayMode == null ? "PARENTHESES" : denickerTagDisplayMode);
        config.addProperty("denickerRevealRealSkinEnabled", denickerRevealRealSkinEnabled);
        config.addProperty("denickerShowRealIgnInTabEnabled", denickerShowRealIgnInTabEnabled);
        config.addProperty("nickRenamedItemScannerEnabled", nickRenamedItemScannerEnabled);
        config.addProperty("nickDeathSearchLinkEnabled", nickDeathSearchLinkEnabled);
        config.addProperty("recentOpponentsGroupDenickedPlayers", recentOpponentsGroupDenickedPlayers);
        config.addProperty("recentOpponentsGroupDenickedPlayersDefaultOn", true);
        config.addProperty("recentOpponentsSortMode", recentOpponentsSortMode);
        config.addProperty("guiBackdropMode", guiBackdropMode);
        config.addProperty("guiBackdropGomodDimPercent", guiBackdropGomodDimPercent);
        config.addProperty("guiBackdropGomodBlurPercent", guiBackdropGomodBlurPercent);
        config.addProperty("guiBackdropOtherDimPercent", guiBackdropOtherDimPercent);
        config.addProperty("guiBackdropOtherBlurPercent", guiBackdropOtherBlurPercent);
        config.addProperty("guiBackdropGomodExcludeChatStrip", guiBackdropGomodExcludeChatStrip);
        config.addProperty("guiBackdropOtherExcludeChatStrip", guiBackdropOtherExcludeChatStrip);
        config.addProperty("hudEditorCenterGuidesAlways", hudEditorCenterGuidesAlways);
        config.addProperty("hideScoreboardValuesEnabled", hideScoreboardValuesEnabled);
        config.addProperty("hideDateGameIdEnabled", hideDateGameIdEnabled);
        config.addProperty("hideKitScoreboardEnabled", hideKitScoreboardEnabled);
        config.addProperty("hideHypixelFooterEnabled", hideHypixelFooterEnabled);
        config.addProperty("sidebarBlitzHourLineEnabled", sidebarBlitzHourLineEnabled);
        config.addProperty("sidebarKillEffectLineEnabled", sidebarKillEffectLineEnabled);
        config.addProperty("sidebarTauntWastedEnabled", sidebarTauntWastedEnabled);
        config.addProperty("mountHeadDimEnabled", mountHeadDimEnabled);
        config.addProperty("mountHeadDimAlpha", mountAlphaHorse);
        config.addProperty("mountAlphaHorse", mountAlphaHorse);
        config.addProperty("mountAlphaDonkey", mountAlphaDonkey);
        config.addProperty("mountAlphaPig", mountAlphaPig);
        config.addProperty("mountAlphaBoat", mountAlphaBoat);
        config.addProperty("mountAlphaWagon", mountAlphaWagon);
        config.addProperty("waterClarityEnabled", waterClarityEnabled);
        config.addProperty("nametagThroughBlocksEnabled", nametagThroughBlocksEnabled);
        config.addProperty("waterClarityAmount", waterClarityAmount);
        config.addProperty("waterClaritySurfaceFromAir", waterClaritySurfaceFromAir);
        config.addProperty("waterClarityUnderwaterFog", waterClarityUnderwaterFog);
        config.addProperty("mountHeadDimStrategy", mountHeadDimStrategy);
        config.addProperty("mountDimDepthMode", mountDimDepthMode);
        config.addProperty("mountFlushMode", mountFlushMode);

        // Save LootCall settings
        config.addProperty("lootCallEnabled", lootCallEnabled);
        config.addProperty("lootCallEverywhere", lootCallEverywhere);
        config.addProperty("lootCallAnnounceDiamond", lootCallAnnounceDiamond);
        config.addProperty("lootCallAnnounceIron", lootCallAnnounceIron);
        config.addProperty("lootCallAnnounceGold", lootCallAnnounceGold);
        config.addProperty("teamLootEnabled", teamLootEnabled);
        config.addProperty("itemEspLabelExtraYOffset", itemEspLabelExtraYOffset);
        config.addProperty("askForLootEnabled", askForLootEnabled);
        config.addProperty("askForLootAllTime", askForLootAllTime);
        config.addProperty("askForLootKeyCode", askForLootKeyCode);
        config.addProperty("playerCallerEnabled", playerCallerEnabled);
        config.addProperty("playerCallerKeyCode", playerCallerKeyCode);
        config.addProperty("chestRoutesRenderEnabled", chestRoutesRenderEnabled);
        config.addProperty("chestRoutesPlaceFeetKeyCode", chestRoutesPlaceFeetKeyCode);
        config.addProperty("chestRoutesToggleKeyCode", chestRoutesToggleKeyCode);
        config.addProperty("chestRoutesUndoKeyCode", chestRoutesUndoKeyCode);
        saveMoreHudToConfig(config);
        config.addProperty("disguiseKillCounterMode", disguiseKillCounterMode);
        config.addProperty("disguiseTournamentKillCounterMode", disguiseTournamentKillCounterMode);
        config.addProperty("disguiseCustomKillCount", disguiseCustomKillCount);
        config.addProperty("disguiseCustomKillCountEnabled", disguiseCustomKillCountEnabled);
        config.addProperty("disguiseBlitzKitSelectorEnabled", disguiseBlitzKitSelectorEnabled);
        config.addProperty("disguiseRenamedItemsMode", disguiseRenamedItemsMode);
        config.addProperty("disguiseMaskRenamedItemsEnabled", "RESETED".equals(disguiseRenamedItemsMode));
        config.addProperty("disguiseHypixelPrefixMode", disguiseHypixelPrefixMode);
        config.addProperty("disguiseHypixelRankPreset", disguiseHypixelRankPreset);
		config.addProperty("disguiseHypixelMvpPlusColor", disguiseHypixelMvpPlusColor);
        config.addProperty("disguiseHypixelMvpBracketColor", disguiseHypixelMvpBracketColor);
        config.addProperty("disguiseHypixelMvpPlusBracketBaseColor", disguiseHypixelMvpPlusBracketBaseColor);
        config.addProperty("disguiseHypixelMvpPpBaseColor", disguiseHypixelMvpPpBaseColor);
        config.addProperty("disguiseHypixelMvpPpSignColor", disguiseHypixelMvpPpSignColor);
        config.addProperty("disguiseHypixelRankCustom", disguiseHypixelRankCustom == null ? "" : disguiseHypixelRankCustom);
        config.addProperty("disguiseNickSetupLastNick", disguiseNickSetupLastNick == null ? "" : disguiseNickSetupLastNick);
        config.addProperty("disguiseNickSetupLastSkinMode", disguiseNickSetupLastSkinMode);
        config.addProperty("disguiseNickSetupLastSkinRef", disguiseNickSetupLastSkinRef == null ? "" : disguiseNickSetupLastSkinRef);
        config.addProperty("disguiseRestoreOnReconnect", disguiseRestoreOnReconnect);
        config.addProperty("disguiseHideLunarCosmeticsOnWeave", disguiseHideLunarCosmeticsOnWeave);
        config.addProperty("disguiseHideLunarCosmeticsDefaultMigrated", disguiseHideLunarCosmeticsDefaultMigrated);
        config.addProperty("playerStandaloneCapeEnabled", playerStandaloneCapeEnabled);
        config.addProperty("playerStandaloneCapeOptionKey", playerStandaloneCapeOptionKey == null ? "" : playerStandaloneCapeOptionKey);
        JsonObject standaloneCapeByAccount = new JsonObject();
        for (Map.Entry<String, String> e : playerStandaloneCapeOptionKeyByAccount.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String accountKey = normalizeStandaloneCapeAccountKey(e.getKey());
            String optionKey = normalizeStandaloneCapeOptionKey(e.getValue());
            if (!accountKey.isEmpty() && !optionKey.isEmpty()) {
                standaloneCapeByAccount.addProperty(accountKey, optionKey);
            }
        }
        config.add("playerStandaloneCapeOptionKeyByAccount", standaloneCapeByAccount);
        config.addProperty("playerStandaloneCapeBroadcastToPeers", playerStandaloneCapeBroadcastToPeers);
        config.addProperty("playerStandaloneCapePreferGomodOnWeave", playerStandaloneCapePreferGomodOnWeave);
        config.addProperty("lunarTweaksEnabled", lunarTweaksEnabled);
        config.addProperty("lunarTweaksSeeGomodPlayers", lunarTweaksSeeGomodPlayers);
        config.addProperty("lunarTweaksShowToOtherPlayers", lunarTweaksShowToOtherPlayers);
        config.addProperty("lunarClothCloakEnabled", lunarClothCloakEnabled);
        config.addProperty("lunarTweaksAllowMultipleSameTypeCosmetics",
                lunarTweaksAllowMultipleSameTypeCosmetics);
        config.addProperty("lunarLimitedCosmeticsUnlockerEnabled", lunarLimitedCosmeticsUnlockerEnabled);
        config.addProperty("lunarCosmeticSourceBadgesEnabled", lunarCosmeticSourceBadgesEnabled);
        JsonArray hiddenCosmeticPlayers = new JsonArray();
        for (String playerName : lunarTweaksHiddenCosmeticPlayers) {
            hiddenCosmeticPlayers.add(new JsonPrimitive(playerName));
        }
        config.add("lunarTweaksHiddenCosmeticPlayers", hiddenCosmeticPlayers);
        config.addProperty("lunarPlusHideBadge", lunarPlusHideBadge);
        config.addProperty("disguiseReconnectKind", disguiseReconnectKind);
        config.addProperty("disguiseReconnectPlayerName", disguiseReconnectPlayerName == null ? "" : disguiseReconnectPlayerName);
        config.addProperty("disguiseReconnectShareMode", normalizeDisguiseReconnectShareMode(disguiseReconnectShareMode));
        config.addProperty("disguiseReconnectFullLunarUuid", disguiseReconnectFullLunarUuid == null ? "" : disguiseReconnectFullLunarUuid);
        config.addProperty("disguiseReconnectNick", disguiseReconnectNick == null ? "" : disguiseReconnectNick);
        config.addProperty("disguiseReconnectNickRaw", disguiseReconnectNickRaw == null ? "" : disguiseReconnectNickRaw);
        config.addProperty("disguiseReconnectNickSkinMode", disguiseReconnectNickSkinMode);
        config.addProperty("disguiseReconnectNickSkinRef", disguiseReconnectNickSkinRef == null ? "" : disguiseReconnectNickSkinRef);
        HudOpacitySliderBoxInsets.addToConfig(config);
        return config;
    }

    /**
     * Returns a fresh snapshot of the effective in-memory settings for diagnostic reports.
     * Callers must redact identity and credential fields before writing or displaying it.
     * Unlike reading {@code gomodConfig.json}, this also includes changes still waiting for
     * the debounced config save and does not force an extra disk write during a lag spike.
     */
    public static synchronized JsonObject buildDebugConfigSnapshot() {
        if (!loaded) {
            loadConfig();
        }
        return buildConfigJsonFromCurrentState();
    }

    /**
     * Immediately saves the current configuration to disk.
     * Use this for critical saves (e.g., during shutdown). 
     * For normal setter calls, use saveConfig() which debounces.
     */
    public static synchronized void saveConfigNow() {
        lastSaveDeferred = false;
        if (loadedFromCorruptConfig) {
            System.err.println("[ConfigManager] Skipping save to preserve unreadable gomodConfig.json (backup saved as .corrupt.*).");
            return;
        }
        try {
            // Ensure directory exists
            File configDirFile = getGomodDataDirectory();
            if (!configDirFile.exists()) {
                boolean dirCreated = configDirFile.mkdirs();
                if (!dirCreated) {
                    System.out.println("[ConfigManager] Failed to create config directory: " + configDirFile.getAbsolutePath());
                }
            }

            File configFile = getConfigFile();
            // Cross-process locking is nonblocking on the game thread. A busy writer gets a retry.
            try (FileChannel channel = FileChannel.open(new File(configDirFile, "gomodConfig.lock").toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = tryConfigFileLock(channel)) {
            if (lock == null) {
                lastSaveDeferred = true;
                saveConfig();
                return;
            }
            preserveSavedBetterPlayerFormat(configFile);
            JsonObject config = buildConfigJsonFromCurrentState();
            String serialized = GSON.toJson(config);
            String absolutePath = configFile.getAbsolutePath();

            // Some live features call their setters with the current value every tick. Do not rewrite and flush a
            // large JSON file unless its serialized state actually changed. The timestamp check still permits an
            // externally replaced profile/config to be restored even when our in-memory state is unchanged.
            if (serialized.equals(lastPersistedConfigJson)
                    && absolutePath.equals(lastPersistedConfigPath)
                    && configFile.exists()
                    && configFile.lastModified() == lastPersistedConfigModified) {
                loaded = true;
                betterPlayerFormatDirty = false;
                return;
            }

            // Readers must see a complete JSON document, including during simultaneous client exits.
            Path staged = Files.createTempFile(configDirFile.toPath(), "gomodConfig-", ".tmp");
            try {
                Files.write(staged, serialized.getBytes(StandardCharsets.UTF_8));
                try {
                    try {
                        Files.move(staged, configFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(staged, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (AccessDeniedException busyReader) {
                    // Windows can deny replacement while a reader has the old file open.
                    // Retain pending choices and retry asynchronously instead of truncating it.
                    lastSaveDeferred = true;
                    saveConfig();
                    return;
                }
            } finally {
                Files.deleteIfExists(staged);
            }
            lastPersistedConfigJson = serialized;
            lastPersistedConfigPath = absolutePath;
            lastPersistedConfigModified = configFile.lastModified();
            betterPlayerFormatDirty = false;

            // Persist a tiny "user defaults" snapshot for HUD layout so fresh installs / defaults-based profiles
            // can start with the user's preferred placement.
            persistUserHudDefaultsQuietly();
            
            System.out.println("[ConfigManager] Saved configuration to " + getConfigFile().getAbsolutePath());
            loaded = true;
            }
        } catch (IOException e) {
            System.out.println("[ConfigManager] Error saving config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static FileLock tryConfigFileLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException busy) { return null; }
    }

    /** An untouched stale client must not turn another client's newer format choice back OFF. */
    private static void preserveSavedBetterPlayerFormat(File file) {
        if (betterPlayerFormatDirty || !file.isFile()) return;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonObject saved = new JsonParser().parse(reader).getAsJsonObject();
            if (!saved.has("killCounterBetterPlayerFormat") && !saved.has("killCounterChatStyleTab")
                    && !saved.has("killCounterChatStyleNametag")) return;
            String mode = readBetterPlayerFormat(saved);
            if (!mode.equals(killCounterBetterPlayerFormat)) {
                killCounterBetterPlayerFormat = mode;
                enforceBetterPlayerFormatCompatibility();
                KillCounter.markTabCompositionDirty();
            }
        } catch (Exception unreadable) {
            // Keep the loaded/user-selected value if an older process left an incomplete document.
        }
    }

    /** Bounded waiting is reserved for JVM shutdown, never a render/input callback. */
    public static void flushConfigForShutdown() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            saveConfigNow();
            if (!lastSaveDeferred) return;
            try { Thread.sleep(20L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        } while (System.nanoTime() < deadline);
        System.err.println("[gomod] Settings save remains pending because the configuration file is busy.");
    }


    /**
     * Gets the desired third-person perspective distance.
     */
    public static float getPerspectiveDistance() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveDistance;
    }

    /**
     * Sets and persists the desired third-person perspective distance.
     */
    public static void setPerspectiveDistance(float distance) {
        perspectiveDistance = distance;
        saveConfig();
    }

    public static boolean isPerspectiveEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveEnabled;
    }

    public static void setPerspectiveEnabled(boolean enabled) {
        perspectiveEnabled = enabled;
        saveConfig();
    }

    public static float getPerspectiveScrollStep() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveScrollStep;
    }

    public static void setPerspectiveScrollStep(float step) {
        perspectiveScrollStep = step;
        saveConfig();
    }

    public static boolean isPerspectiveResetOnExit() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveResetOnExit;
    }

    public static void setPerspectiveResetOnExit(boolean value) {
        perspectiveResetOnExit = value;
        saveConfig();
    }

    public static int getPerspectiveResetKeyCode() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveResetKeyCode;
    }

    public static void setPerspectiveResetKeyCode(int keyCode) {
        perspectiveResetKeyCode = keyCode;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static boolean isPerspectiveRequireHold() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveRequireHold;
    }

    public static void setPerspectiveRequireHold(boolean value) {
        perspectiveRequireHold = value;
        saveConfig();
    }

    public static boolean isPerspectiveSmoothZoom() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveSmoothZoom;
    }

    public static void setPerspectiveSmoothZoom(boolean value) {
        perspectiveSmoothZoom = value;
        saveConfig();
    }

    public static float getPerspectiveSmoothSpeed() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveSmoothSpeed;
    }

    public static void setPerspectiveSmoothSpeed(float speed) {
        perspectiveSmoothSpeed = Math.max(0.01f, Math.min(1.0f, speed));
        saveConfig();
    }

    public static int getPerspectiveZoomKeyCode() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveZoomKeyCode;
    }

    public static void setPerspectiveZoomKeyCode(int keyCode) {
        perspectiveZoomKeyCode = keyCode;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static float getPerspectiveFOV() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveFOV;
    }

    public static void setPerspectiveFOV(float fov) {
        perspectiveFOV = Math.max(10.0f, Math.min(170.0f, fov));
        saveConfig();
    }

    public static boolean isPerspectiveFOVEnabled() {
        return perspectiveFOVEnabled;
    }

    public static void setPerspectiveFOVEnabled(boolean enabled) {
        perspectiveFOVEnabled = enabled;
        saveConfig();
    }

    // Lock scroll while in perspective
    public static boolean isPerspectiveLockScroll() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveLockScroll;
    }

    public static void setPerspectiveLockScroll(boolean value) {
        perspectiveLockScroll = value;
        saveConfig();
    }

    public static int getPerspectiveAdvancedRenderDistanceKeyCode() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveAdvancedRenderDistanceKeyCode;
    }

    public static void setPerspectiveAdvancedRenderDistanceKeyCode(int keyCode) {
        perspectiveAdvancedRenderDistanceKeyCode = keyCode;
        saveConfig();
    }

    public static int getPerspectiveAdvancedRenderDistanceBlocks() {
        if (!loaded) {
            loadConfig();
        }
        return perspectiveAdvancedRenderDistanceBlocks;
    }

    public static void setPerspectiveAdvancedRenderDistanceBlocks(int blocks) {
        perspectiveAdvancedRenderDistanceBlocks = blocks;
        saveConfig();
    }

    /** Used when applying saved distance during startup without re-saving. */
    public static void setPerspectiveAdvancedRenderDistanceBlocksSilent(int blocks) {
        perspectiveAdvancedRenderDistanceBlocks = blocks;
    }
    
    /**
     * True if any win (post-game bonus) auto-queue mode is on.
     */
    public static boolean isAutoQueueEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return autoQueueSoloEnabled || autoQueueTeamsEnabled;
    }

    /**
     * Sets both solo and teams win auto-queue together (legacy / bulk toggle).
     */
    public static void setAutoQueueEnabled(boolean enabled) {
        autoQueueSoloEnabled = enabled;
        autoQueueTeamsEnabled = enabled;
        saveConfig();
    }

    public static boolean isAutoQueueSoloEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return autoQueueSoloEnabled;
    }

    public static void setAutoQueueSoloEnabled(boolean enabled) {
        autoQueueSoloEnabled = enabled;
        saveConfig();
    }

    public static boolean isAutoQueueTeamsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return autoQueueTeamsEnabled;
    }

    public static void setAutoQueueTeamsEnabled(boolean enabled) {
        autoQueueTeamsEnabled = enabled;
        saveConfig();
    }
    
    /**
     * Gets the auto-queue delay for solo games.
     * @return delay in seconds
     */
    public static float getAutoQueueSoloDelay() {
        if (!loaded) {
            loadConfig();
        }
        return autoQueueSoloDelay;
    }
    
    /**
     * Sets the auto-queue delay for solo games.
     * @param delay delay in seconds
     */
    public static void setAutoQueueSoloDelay(float delay) {
        autoQueueSoloDelay = delay;
        saveConfig();
    }
    
    /**
     * Gets the auto-queue delay for teams games.
     * @return delay in seconds
     */
    public static float getAutoQueueTeamsDelay() {
        if (!loaded) {
            loadConfig();
        }
        return autoQueueTeamsDelay;
    }
    
    /**
     * Sets the auto-queue delay for teams games.
     * @param delay delay in seconds
     */
    public static void setAutoQueueTeamsDelay(float delay) {
        autoQueueTeamsDelay = delay;
        saveConfig();
    }
    
    /**
     * True if any death-requeue mode is on.
     */
    public static boolean isDeathRequeueEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return deathRequeueSoloEnabled || deathRequeueTeamsEnabled;
    }

    /**
     * Sets both solo and teams death requeue together (legacy / bulk toggle).
     */
    public static void setDeathRequeueEnabled(boolean enabled) {
        deathRequeueSoloEnabled = enabled;
        deathRequeueTeamsEnabled = enabled;
        saveConfig();
    }

    public static boolean isDeathRequeueSoloEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return deathRequeueSoloEnabled;
    }

    public static void setDeathRequeueSoloEnabled(boolean enabled) {
        deathRequeueSoloEnabled = enabled;
        saveConfig();
    }

    public static boolean isDeathRequeueTeamsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return deathRequeueTeamsEnabled;
    }

    public static void setDeathRequeueTeamsEnabled(boolean enabled) {
        deathRequeueTeamsEnabled = enabled;
        saveConfig();
    }
    
    /**
     * Gets the death requeue delay for solo games.
     * @return delay in seconds
     */
    public static float getDeathRequeueSoloDelay() {
        if (!loaded) {
            loadConfig();
        }
        return deathRequeueSoloDelay;
    }
    
    /**
     * Sets the death requeue delay for solo games.
     * @param delay delay in seconds
     */
    public static void setDeathRequeueSoloDelay(float delay) {
        deathRequeueSoloDelay = delay;
        saveConfig();
    }
    
    /**
     * Gets the death requeue delay for teams games.
     * @return delay in seconds
     */
    public static float getDeathRequeueTeamsDelay() {
        if (!loaded) {
            loadConfig();
        }
        return deathRequeueTeamsDelay;
    }
    
    /**
     * Sets the death requeue delay for teams games.
     * @param delay delay in seconds
     */
    public static void setDeathRequeueTeamsDelay(float delay) {
        deathRequeueTeamsDelay = delay;
        saveConfig();
    }
    
    /**
     * Checks if inventory HUD is enabled.
     * @return true if inventory HUD is enabled, false otherwise
     */
    public static boolean isInventoryHUDEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDEnabled;
    }

    /**
     * Sets whether inventory HUD is enabled.
     * @param enabled true to enable inventory HUD, false to disable
     */
    public static void setInventoryHUDEnabled(boolean enabled) {
        inventoryHUDEnabled = enabled;
        saveConfig();
    }
    
    /**
     * Checks if inventory HUD should show when empty.
     * @return true if inventory HUD should show when empty, false otherwise
     */
    public static boolean isInventoryHUDShowEmpty() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDShowEmpty;
    }

    /**
     * Sets whether inventory HUD should show when empty.
     * @param showEmpty true to show HUD when empty, false to hide when empty
     */
    public static void setInventoryHUDShowEmpty(boolean showEmpty) {
        inventoryHUDShowEmpty = showEmpty;
        saveConfig();
    }
    

    
    /**
     * Gets the inventory HUD custom X position.
     * @return custom X position
     */
    public static int getInventoryHUDCustomX() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDCustomX;
    }

    /**
     * Sets the inventory HUD custom X position.
     * @param x custom X position
     */
    public static void setInventoryHUDCustomX(int x) {
        inventoryHUDCustomX = x;
        saveConfig();
    }
    
    /**
     * Gets the inventory HUD custom Y position.
     * @return custom Y position
     */
    public static int getInventoryHUDCustomY() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDCustomY;
    }

    /**
     * Sets the inventory HUD custom Y position.
     * @param y custom Y position
     */
    public static void setInventoryHUDCustomY(int y) {
        inventoryHUDCustomY = y;
        saveConfig();
    }
    
    /**
     * Gets the inventory HUD scale.
     * @return scale factor (1.0-4.0, supports 1.5)
     */
    public static float getInventoryHUDScale() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDScale;
    }

    /**
     * Sets the inventory HUD scale.
     * @param scale scale factor (1.0-4.0, supports 1.5)
     */
    public static void setInventoryHUDScale(float scale) {
        inventoryHUDScale = scale;
        saveConfig();
    }
    
    /**
     * Gets the inventory HUD background opacity.
     * @return opacity value (0-255)
     */
    public static int getInventoryHUDBackgroundOpacity() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDBackgroundOpacity;
    }

    /**
     * Sets the inventory HUD background opacity.
     * @param opacity opacity value (0-255)
     */
    public static void setInventoryHUDBackgroundOpacity(int opacity) {
        inventoryHUDBackgroundOpacity = opacity;
        saveConfig();
    }
    
    /**
     * Gets the inventory HUD background color.
     * @return background color (RGB)
     */
    public static int getInventoryHUDBackgroundColor() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDBackgroundColor;
    }

    /**
     * Sets the inventory HUD background color.
     * @param color background color (RGB)
     */
    public static void setInventoryHUDBackgroundColor(int color) {
        inventoryHUDBackgroundColor = color;
        saveConfig();
    }
    
    /**
     * Checks if inventory HUD force mode is enabled.
     * @return true if force mode is enabled, false otherwise
     */
    public static boolean isInventoryHUDForce() {
        if (!loaded) {
            loadConfig();
        }
        return inventoryHUDForce;
    }

    /**
     * Sets whether inventory HUD force mode is enabled.
     * @param force true to enable force mode, false to disable
     */
    public static void setInventoryHUDForce(boolean force) {
        inventoryHUDForce = force;
        saveConfig();
    }

    public static boolean isFriendInvHudEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudEnabled;
    }

    public static void setFriendInvHudEnabled(boolean v) {
        friendInvHudEnabled = v;
        saveConfig();
    }

    public static boolean isFriendInvHudShowEmpty() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudShowEmpty;
    }

    public static void setFriendInvHudShowEmpty(boolean v) {
        friendInvHudShowEmpty = v;
        saveConfig();
    }

    public static int getFriendInvHudCustomX() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudCustomX;
    }

    public static void setFriendInvHudCustomX(int x) {
        friendInvHudCustomX = x;
        saveConfig();
    }

    public static int getFriendInvHudCustomY() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudCustomY;
    }

    public static void setFriendInvHudCustomY(int y) {
        friendInvHudCustomY = y;
        saveConfig();
    }

    public static float getFriendInvHudScale() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudScale;
    }

    public static void setFriendInvHudScale(float scale) {
        friendInvHudScale = scale;
        saveConfig();
    }

    public static int getFriendInvHudBackgroundOpacity() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudBackgroundOpacity;
    }

    public static void setFriendInvHudBackgroundOpacity(int opacity) {
        friendInvHudBackgroundOpacity = opacity;
        saveConfig();
    }

    public static int getFriendInvHudBackgroundColor() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudBackgroundColor;
    }

    public static void setFriendInvHudBackgroundColor(int color) {
        friendInvHudBackgroundColor = color;
        saveConfig();
    }

    public static boolean isFriendInvHudForce() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudForce;
    }

    public static void setFriendInvHudForce(boolean v) {
        friendInvHudForce = v;
        saveConfig();
    }

    public static boolean isFriendInvHudShowRemoteHealth() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudShowRemoteHealth;
    }

    public static void setFriendInvHudShowRemoteHealth(boolean v) {
        friendInvHudShowRemoteHealth = v;
        saveConfig();
    }

    public static boolean isFriendInvHudShowRemoteFood() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudShowRemoteFood;
    }

    public static void setFriendInvHudShowRemoteFood(boolean v) {
        friendInvHudShowRemoteFood = v;
        saveConfig();
    }

    public static boolean isFriendInvHudShowRemoteXp() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudShowRemoteXp;
    }

    public static void setFriendInvHudShowRemoteXp(boolean v) {
        friendInvHudShowRemoteXp = v;
        saveConfig();
    }

    public static boolean isFriendInvHudShowHeldHighlight() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudShowHeldHighlight;
    }

    public static void setFriendInvHudShowHeldHighlight(boolean v) {
        friendInvHudShowHeldHighlight = v;
        saveConfig();
    }

    public static boolean isFriendInvHudShowDirection() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudShowDirection;
    }

    public static void setFriendInvHudShowDirection(boolean v) {
        friendInvHudShowDirection = v;
        saveConfig();
    }

    public static boolean isFriendInvHudDirectionVertical() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudDirectionVertical;
    }

    public static void setFriendInvHudDirectionVertical(boolean v) {
        friendInvHudDirectionVertical = v;
        saveConfig();
    }

    public static boolean isFriendInvHudTextShadow() {
        if (!loaded) {
            loadConfig();
        }
        return friendInvHudTextShadow;
    }

    public static void setFriendInvHudTextShadow(boolean shadow) {
        friendInvHudTextShadow = shadow;
        saveConfig();
    }

    public static String getKillCounterKillEffectTabMode() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterKillEffectTabMode == null ? "COLORED_NAME" : killCounterKillEffectTabMode;
    }

    public static String getKillCounterTabDisplayMode() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterTabDisplayMode == null ? "AFTER_NAME" : killCounterTabDisplayMode;
    }

    public static void setKillCounterTabDisplayMode(String mode) {
        killCounterTabDisplayMode = normalizeKillCounterTabDisplayMode(mode);
        saveConfig();
    }

    public static synchronized void setKillCounterKillEffectTabMode(String mode) {
        if (!loaded) loadConfig();
        killCounterKillEffectTabMode = normalizeKillEffectTabMode(mode);
        String compatibleFormat = removeKillEffectOverlapsFromBetterPlayerFormat(
                killCounterBetterPlayerFormat, killCounterKillEffectTabMode);
        if (!compatibleFormat.equals(killCounterBetterPlayerFormat)) {
            killCounterBetterPlayerFormat = compatibleFormat;
            betterPlayerFormatDirty = true;
        }
        // An explicit color-target choice must not be overwritten by an older saved format on the next write.
        if (killEffectUsesTab(killCounterKillEffectTabMode)
                || killEffectUsesTags(killCounterKillEffectTabMode)) betterPlayerFormatDirty = true;
        saveConfig();
        KillCounter.markTabCompositionDirty();
    }
    
    /**
     * Checks if GoStats AI functionality is enabled.
     * @return true if GoStats is enabled, false otherwise
     */
    public static boolean isGoStatsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return goStatsEnabled;
    }

    /**
     * Sets whether GoStats AI functionality is enabled.
     * @param enabled true to enable GoStats, false to disable
     */
    public static void setGoStatsEnabled(boolean enabled) {
        goStatsEnabled = enabled;
        saveConfig();
    }

    public static String getBlitzVaultItemsSortMode() {
        if (!loaded) {
            loadConfig();
        }
        return blitzVaultItemsSortMode == null || blitzVaultItemsSortMode.trim().isEmpty()
                ? "DEFAULT"
                : blitzVaultItemsSortMode;
    }

    public static void setBlitzVaultItemsSortMode(String mode) {
        if (mode == null) mode = "DEFAULT";
        String normalized = mode.trim().toUpperCase(Locale.US);
        if (normalized.isEmpty()) {
            normalized = "DEFAULT";
        }
        blitzVaultItemsSortMode = normalized;
        saveConfig();
    }

    public static String getBlitzVaultItemsSortKeys() {
        if (!loaded) {
            loadConfig();
        }
        return blitzVaultItemsSortKeys == null ? "" : blitzVaultItemsSortKeys;
    }

    public static void setBlitzVaultItemsSortKeys(String keys) {
        blitzVaultItemsSortKeys = keys == null ? "" : keys.trim();
        saveConfig();
    }

    public static boolean isBlitzVaultItemsShowSeenInTooltip() {
        if (!loaded) {
            loadConfig();
        }
        return blitzVaultItemsShowSeenInTooltip;
    }

    public static void setBlitzVaultItemsShowSeenInTooltip(boolean enabled) {
        blitzVaultItemsShowSeenInTooltip = enabled;
        saveConfig();
    }

    public static String getBlitzVaultItemsObservedCategory() {
        if (!loaded) {
            loadConfig();
        }
        return blitzVaultItemsObservedCategory == null ? "" : blitzVaultItemsObservedCategory;
    }

    public static void setBlitzVaultItemsObservedCategory(String category) {
        blitzVaultItemsObservedCategory = category == null ? "" : category.trim();
        saveConfig();
    }

    public static String getBlitzVaultItemsChestSortMode() {
        if (!loaded) {
            loadConfig();
        }
        return blitzVaultItemsChestSortMode == null || blitzVaultItemsChestSortMode.trim().isEmpty()
                ? "CHEST_NUMBER"
                : blitzVaultItemsChestSortMode;
    }

    public static void setBlitzVaultItemsChestSortMode(String mode) {
        if (mode == null) {
            mode = "CHEST_NUMBER";
        }
        String normalized = mode.trim().toUpperCase(java.util.Locale.US);
        if (normalized.isEmpty()) {
            normalized = "CHEST_NUMBER";
        }
        blitzVaultItemsChestSortMode = normalized;
        saveConfig();
    }

    public static String getBlitzChestItemsSortMode() {
        if (!loaded) {
            loadConfig();
        }
        return blitzChestItemsSortMode == null || blitzChestItemsSortMode.trim().isEmpty()
                ? "MOST_OBSERVED"
                : blitzChestItemsSortMode;
    }

    public static void setBlitzChestItemsSortMode(String mode) {
        if (mode == null) {
            mode = "MOST_OBSERVED";
        }
        String normalized = mode.trim().toUpperCase(java.util.Locale.US);
        if (normalized.isEmpty()) {
            normalized = "MOST_OBSERVED";
        }
        if (!"DEFAULT".equals(normalized) && !"MOST_OBSERVED".equals(normalized) && !"CATEGORY".equals(normalized)) {
            normalized = "DEFAULT";
        }
        blitzChestItemsSortMode = normalized;
        saveConfig();
    }

    public static boolean isBlitzChestItemsAggregateVariants() {
        if (!loaded) {
            loadConfig();
        }
        return blitzChestItemsAggregateVariants;
    }

    public static void setBlitzChestItemsAggregateVariants(boolean enabled) {
        blitzChestItemsAggregateVariants = enabled;
        saveConfig();
    }

    public static boolean isBlitzChestItemsAggregateCombatTier() {
        if (!loaded) {
            loadConfig();
        }
        return blitzChestItemsAggregateCombatTier;
    }

    public static void setBlitzChestItemsAggregateCombatTier(boolean enabled) {
        blitzChestItemsAggregateCombatTier = enabled;
        saveConfig();
    }

    public static boolean isBlitzChestItemsShowSeenInTooltip() {
        if (!loaded) {
            loadConfig();
        }
        return blitzChestItemsShowSeenInTooltip;
    }

    public static void setBlitzChestItemsShowSeenInTooltip(boolean enabled) {
        blitzChestItemsShowSeenInTooltip = enabled;
        saveConfig();
    }

    public static String getBlitzChestItemsChestSortMode() {
        if (!loaded) {
            loadConfig();
        }
        return blitzChestItemsChestSortMode == null || blitzChestItemsChestSortMode.trim().isEmpty()
                ? "CHEST_NUMBER"
                : blitzChestItemsChestSortMode;
    }

    public static void setBlitzChestItemsChestSortMode(String mode) {
        if (mode == null) {
            mode = "CHEST_NUMBER";
        }
        String normalized = mode.trim().toUpperCase(java.util.Locale.US);
        if (normalized.isEmpty()) {
            normalized = "CHEST_NUMBER";
        }
        blitzChestItemsChestSortMode = normalized;
        saveConfig();
    }

    public static java.util.List<String> getBlitzVaultItemsPreferredKeys() {
        if (!loaded) {
            loadConfig();
        }
        return new java.util.ArrayList<String>(blitzVaultItemsPreferredKeys);
    }

    public static void setBlitzVaultItemsPreferredKeys(java.util.List<String> keys) {
        blitzVaultItemsPreferredKeys.clear();
        if (keys != null) {
            for (String key : keys) {
                if (key == null) continue;
                key = key.trim();
                if (!key.isEmpty() && !blitzVaultItemsPreferredKeys.contains(key)) {
                    blitzVaultItemsPreferredKeys.add(key);
                }
            }
        }
        saveConfig();
    }

    public static java.util.List<String> getBlitzVaultItemsBlacklistedKeys() {
        if (!loaded) {
            loadConfig();
        }
        return new java.util.ArrayList<String>(blitzVaultItemsBlacklistedKeys);
    }

    public static void setBlitzVaultItemsBlacklistedKeys(java.util.List<String> keys) {
        blitzVaultItemsBlacklistedKeys.clear();
        if (keys != null) {
            for (String key : keys) {
                if (key == null) continue;
                key = key.trim();
                if (!key.isEmpty() && !blitzVaultItemsBlacklistedKeys.contains(key)
                        && !blitzVaultItemsPreferredKeys.contains(key)) {
                    blitzVaultItemsBlacklistedKeys.add(key);
                }
            }
        }
        saveConfig();
    }

    public static boolean isBlitzVaultEnchantChanceExact() {
        if (!loaded) {
            loadConfig();
        }
        return blitzVaultEnchantChanceExact;
    }

    public static void setBlitzVaultEnchantChanceExact(boolean exact) {
        blitzVaultEnchantChanceExact = exact;
        saveConfig();
        BlitzVaultItemsData.rebuildVaultEnchantChances();
    }

    public static java.util.List<String> getBlitzVaultEnchantChanceEnabledKeys() {
        if (!loaded) {
            loadConfig();
        }
        return new java.util.ArrayList<String>(blitzVaultEnchantChanceEnabledKeys);
    }

    public static void setBlitzVaultEnchantChanceEnabledKeys(java.util.List<String> keys) {
        blitzVaultEnchantChanceEnabledKeys.clear();
        if (keys != null) {
            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                key = key.trim();
                if (!key.isEmpty() && !blitzVaultEnchantChanceEnabledKeys.contains(key)) {
                    blitzVaultEnchantChanceEnabledKeys.add(key);
                }
            }
        }
        saveConfig();
        BlitzVaultItemsData.rebuildVaultEnchantChances();
    }

    public static java.util.List<String> getBlitzVaultEnchantChanceCombos() {
        if (!loaded) {
            loadConfig();
        }
        return new java.util.ArrayList<String>(blitzVaultEnchantChanceCombos);
    }

    public static void setBlitzVaultEnchantChanceCombos(java.util.List<String> combos) {
        blitzVaultEnchantChanceCombos.clear();
        if (combos != null) {
            for (String combo : combos) {
                if (combo == null) {
                    continue;
                }
                combo = combo.trim();
                if (!combo.isEmpty() && !blitzVaultEnchantChanceCombos.contains(combo)) {
                    blitzVaultEnchantChanceCombos.add(combo);
                }
            }
        }
        saveConfig();
        BlitzVaultItemsData.rebuildVaultEnchantChances();
    }
    
    /**
     * Checks if KillCounter is enabled.
     * @return true if KillCounter is enabled, false otherwise
     */
    public static boolean isKillCounterEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterEnabled;
    }
    
    /**
     * Sets whether KillCounter is enabled.
     * @param enabled true to enable KillCounter, false to disable
     */
    public static void setKillCounterEnabled(boolean enabled) {
        killCounterEnabled = enabled;
        saveConfig();
    }
    
    /**
     * Checks if auto /tabstats all when cages open is enabled.
     * @return true if auto /tabstats all is enabled, false otherwise
     */
    public static boolean isKillCounterAutoKeAll() {
        if (!loaded) {
            loadConfig();
        }
        return "CAGES_OPEN".equalsIgnoreCase(killCounterAutoScanMode);
    }
    
    /**
     * Sets whether auto /tabstats all when cages open is enabled.
     * @param enabled true to enable auto /tabstats all, false to disable
     */
    public static void setKillCounterAutoKeAll(boolean enabled) {
        killCounterAutoKeAll = enabled;
        killCounterAutoScanMode = enabled ? "CAGES_OPEN" : "OFF";
        saveConfig();
    }

    public static String getKillCounterAutoScanMode() {
        if (!loaded) {
            loadConfig();
        }
        if (killCounterAutoScanMode == null || killCounterAutoScanMode.trim().isEmpty()) {
            return "OFF";
        }
        return killCounterAutoScanMode;
    }

    public static void setKillCounterAutoScanMode(String mode) {
        if (mode == null) mode = "OFF";
        String normalized = mode.trim().toUpperCase();
        if (!"OFF".equals(normalized) && !"CAGES_OPEN".equals(normalized) && !"PLAYERS_JOIN".equals(normalized)) {
            normalized = "OFF";
        }
        killCounterAutoScanMode = normalized;
        killCounterAutoKeAll = "CAGES_OPEN".equals(normalized);
        saveConfig();
    }
    
    /**
     * Checks if guild tag display is enabled.
     * @return true if guild tag display is enabled, false otherwise
     */
    public static String getKillCounterBetterPlayerFormat() {
        if (!loaded) loadConfig();
        return normalizeBetterPlayerFormat(killCounterBetterPlayerFormat);
    }

    public static boolean isKillCounterChatStyleTab() {
        String mode = getKillCounterBetterPlayerFormat();
        return "TAB".equals(mode) || "TAB_TAGS".equals(mode);
    }

    public static boolean isKillCounterChatStyleNametag() {
        String mode = getKillCounterBetterPlayerFormat();
        return "TAGS".equals(mode) || "TAB_TAGS".equals(mode);
    }

    public static synchronized void setKillCounterBetterPlayerFormat(String mode) {
        if (!loaded) loadConfig();
        killCounterBetterPlayerFormat = normalizeBetterPlayerFormat(mode);
        enforceBetterPlayerFormatCompatibility();
        betterPlayerFormatDirty = true;
        saveConfig();
        KillCounter.markTabCompositionDirty();
    }

    public static void cycleKillCounterBetterPlayerFormat() {
        String current = getKillCounterBetterPlayerFormat();
        setKillCounterBetterPlayerFormat("OFF".equals(current) ? "TAB"
                : ("TAB".equals(current) ? "TAB_TAGS"
                : ("TAB_TAGS".equals(current) ? "TAGS" : "OFF")));
    }

    private static String normalizeBetterPlayerFormat(String mode) {
        if (mode == null) return "OFF";
        String normalized = mode.trim().toUpperCase(java.util.Locale.ROOT);
        if ("TAB".equals(normalized) || "TAB_TAGS".equals(normalized) || "TAGS".equals(normalized)) return normalized;
        return "OFF";
    }

    /** Both features may coexist when they target different name surfaces. */
    public static boolean arePlayerNameDisplaysCompatible(String killEffectMode, String betterFormatMode) {
        String effect = normalizeKillEffectTabMode(killEffectMode);
        String format = normalizeBetterPlayerFormat(betterFormatMode);
        return !(killEffectUsesTab(effect) && betterFormatUsesTab(format)
                || killEffectUsesTags(effect) && betterFormatUsesTags(format));
    }

    /** Saved/imported conflicts favor Better Player Format, clearing only overlapping kill-effect targets. */
    private static boolean enforceBetterPlayerFormatCompatibility() {
        String compatibleEffect = removeBetterFormatOverlapsFromKillEffect(
                killCounterKillEffectTabMode, killCounterBetterPlayerFormat);
        if (!compatibleEffect.equals(killCounterKillEffectTabMode)) {
            killCounterKillEffectTabMode = compatibleEffect;
            return true;
        }
        return false;
    }

    /** Explicit kill-effect selections win only on overlapping targets; non-overlapping format targets remain. */
    private static String removeKillEffectOverlapsFromBetterPlayerFormat(String format, String effect) {
        String normalizedFormat = normalizeBetterPlayerFormat(format);
        boolean tab = betterFormatUsesTab(normalizedFormat) && !killEffectUsesTab(effect);
        boolean tags = betterFormatUsesTags(normalizedFormat) && !killEffectUsesTags(effect);
        return betterPlayerFormatFromTargets(tab, tags);
    }

    /** Better Player Format selections win only on overlapping targets; other kill-effect targets remain. */
    private static String removeBetterFormatOverlapsFromKillEffect(String effect, String format) {
        String normalizedEffect = normalizeKillEffectTabMode(effect);
        if ("ICON".equals(normalizedEffect) || "OFF".equals(normalizedEffect)) {
            return normalizedEffect;
        }
        boolean tab = killEffectUsesTab(normalizedEffect) && !betterFormatUsesTab(format);
        boolean tags = killEffectUsesTags(normalizedEffect) && !betterFormatUsesTags(format);
        return killEffectModeFromTargets(tab, tags);
    }

    private static boolean killEffectUsesTab(String mode) {
        String normalized = normalizeKillEffectTabMode(mode);
        return "COLORED_NAME".equals(normalized) || "COLORED_NAME_TAB".equals(normalized);
    }

    private static boolean killEffectUsesTags(String mode) {
        String normalized = normalizeKillEffectTabMode(mode);
        return "COLORED_NAME".equals(normalized) || "COLORED_NAME_TAGS".equals(normalized);
    }

    private static boolean betterFormatUsesTab(String mode) {
        String normalized = normalizeBetterPlayerFormat(mode);
        return "TAB".equals(normalized) || "TAB_TAGS".equals(normalized);
    }

    private static boolean betterFormatUsesTags(String mode) {
        String normalized = normalizeBetterPlayerFormat(mode);
        return "TAGS".equals(normalized) || "TAB_TAGS".equals(normalized);
    }

    private static String betterPlayerFormatFromTargets(boolean tab, boolean tags) {
        return tab ? (tags ? "TAB_TAGS" : "TAB") : (tags ? "TAGS" : "OFF");
    }

    private static String killEffectModeFromTargets(boolean tab, boolean tags) {
        return tab ? (tags ? "COLORED_NAME" : "COLORED_NAME_TAB")
                : (tags ? "COLORED_NAME_TAGS" : "OFF");
    }

    private static String readBetterPlayerFormat(JsonObject config) {
        if (config.has("killCounterBetterPlayerFormat"))
            return normalizeBetterPlayerFormat(config.get("killCounterBetterPlayerFormat").getAsString());
        boolean tab = config.has("killCounterChatStyleTab") && config.get("killCounterChatStyleTab").getAsBoolean();
        boolean tags = config.has("killCounterChatStyleNametag") && config.get("killCounterChatStyleNametag").getAsBoolean();
        return tags ? (tab ? "TAB_TAGS" : "TAGS") : (tab ? "TAB" : "OFF");
    }

    public static boolean isKillCounterShowGuildTag() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterShowGuildTag;
    }
    
    /**
     * Sets whether guild tag display is enabled.
     * @param enabled true to enable guild tag display, false to disable
     */
    public static void setKillCounterShowGuildTag(boolean enabled) {
        killCounterShowGuildTag = enabled;
        saveConfig();
    }
    
    /**
     * Checks if teammate kit display is enabled.
     * @return true if teammate kit display is enabled, false otherwise
     */
    public static boolean isKillCounterShowTeammateKits() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterShowTeammateKits;
    }
    
    /**
     * Sets whether teammate kit display is enabled.
     * @param enabled true to enable teammate kit display, false to disable
     */
    public static void setKillCounterShowTeammateKits(boolean enabled) {
        killCounterShowTeammateKits = enabled;
        saveConfig();
    }
    
    /**
     * Checks if enemy kit display is enabled.
     * @return true if enemy kit display is enabled, false otherwise
     */
    public static boolean isKillCounterShowEnemyKits() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterShowEnemyKits;
    }
    
    /**
     * Sets whether enemy kit display is enabled.
     * @param enabled true to enable enemy kit display, false to disable
     */
    public static void setKillCounterShowEnemyKits(boolean enabled) {
        killCounterShowEnemyKits = enabled;
        saveConfig();
    }
    
    /**
     * Checks if stats display (K/D, Win Streak) is enabled.
     * @return true if stats display is enabled, false otherwise
     */
    public static boolean isKillCounterStatsDisplay() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterStatsDisplay;
    }
    
    /**
     * Sets whether stats display (K/D, Win Streak) is enabled.
     * @param enabled true to enable stats display, false to disable
     */
    public static void setKillCounterStatsDisplay(boolean enabled) {
        killCounterStatsDisplay = enabled;
        if (!enabled) {
            killCounterStatsShowKd = false;
            killCounterStatsShowWl = false;
            killCounterStatsShowW = false;
        }
        saveConfig();
    }

    private static void applyKillCounterStatsPartsFromLegacyWlMode(int mode) {
        switch (mode) {
            case 0:
                killCounterStatsShowKd = false;
                killCounterStatsShowWl = true;
                killCounterStatsShowW = false;
                break;
            case 2:
                killCounterStatsShowKd = false;
                killCounterStatsShowWl = false;
                killCounterStatsShowW = true;
                break;
            case 3:
                killCounterStatsShowKd = false;
                killCounterStatsShowWl = false;
                killCounterStatsShowW = false;
                break;
            case 1:
            default:
                killCounterStatsShowKd = false;
                killCounterStatsShowWl = true;
                killCounterStatsShowW = true;
                break;
        }
    }

    private static void syncKillCounterStatsDisplayFromParts() {
        killCounterStatsDisplay = killCounterStatsShowKd || killCounterStatsShowWl || killCounterStatsShowW;
    }

    public static boolean isKillCounterStatsShowKd() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterStatsShowKd;
    }

    public static void setKillCounterStatsShowKd(boolean v) {
        killCounterStatsShowKd = v;
        syncKillCounterStatsDisplayFromParts();
        saveConfig();
    }

    public static boolean isKillCounterStatsShowWl() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterStatsShowWl;
    }

    public static void setKillCounterStatsShowWl(boolean v) {
        killCounterStatsShowWl = v;
        syncKillCounterStatsDisplayFromParts();
        saveConfig();
    }

    public static boolean isKillCounterStatsShowW() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterStatsShowW;
    }

    public static void setKillCounterStatsShowW(boolean v) {
        killCounterStatsShowW = v;
        syncKillCounterStatsDisplayFromParts();
        saveConfig();
    }

    public static int getKillCounterWlMode() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterWlMode;
    }

    public static void setKillCounterWlMode(int mode) {
        killCounterWlMode = Math.max(0, Math.min(3, mode));
        saveConfig();
    }
    
    /**
     * Checks if /tabstats all chat display is enabled.
     * @return true if /tabstats all chat display is enabled, false otherwise
     */
    public static boolean isKillCounterShowKeAllChat() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterShowKeAllChat;
    }
    
    /**
     * Sets whether /tabstats all chat display is enabled.
     * @param enabled true to enable /tabstats all chat display, false to disable
     */
    public static void setKillCounterShowKeAllChat(boolean enabled) {
        killCounterShowKeAllChat = enabled;
        saveConfig();
    }

    public static String getKillCounterTabListSortMode() {
        if (!loaded) {
            loadConfig();
        }
        return coerceKillCounterTabSortModeForScope(killCounterTabListSortMode, killCounterTabListSortGameScope);
    }

    public static void setKillCounterTabListSortMode(String mode) {
        killCounterTabListSortMode = coerceKillCounterTabSortModeForScope(
                normalizeKillCounterTabListSortMode(mode), killCounterTabListSortGameScope);
        saveConfig();
    }

    public static String getKillCounterTabListSortGameScope() {
        if (!loaded) {
            loadConfig();
        }
        return normalizeKillCounterTabListSortGameScope(killCounterTabListSortGameScope);
    }

    public static void setKillCounterTabListSortGameScope(String scope) {
        killCounterTabListSortGameScope = normalizeKillCounterTabListSortGameScope(scope);
        killCounterTabListSortMode = coerceKillCounterTabSortModeForScope(
                killCounterTabListSortMode, killCounterTabListSortGameScope);
        saveConfig();
    }

    public static boolean isKillCounterTabListSortDistrictEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterTabListSortDistrictEnabled;
    }

    public static void setKillCounterTabListSortDistrictEnabled(boolean enabled) {
        killCounterTabListSortDistrictEnabled = enabled;
        saveConfig();
    }

    public static boolean isKillCounterTabKillersEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterTabKillersEnabled;
    }

    public static void setKillCounterTabKillersEnabled(boolean enabled) {
        killCounterTabKillersEnabled = enabled;
        saveConfig();
    }

    public static int getKillCounterTabKillersKeyCode() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterTabKillersKeyCode;
    }

    public static void setKillCounterTabKillersKeyCode(int keyCode) {
        killCounterTabKillersKeyCode = keyCode;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static int getKillCounterTabKillersVictimOffsetPx() {
        if (!loaded) {
            loadConfig();
        }
        return 8;
    }

    public static void setKillCounterTabKillersVictimOffsetPx(int px) {
        killCounterTabKillersVictimOffsetPx = 8;
        saveConfig();
    }

    public static boolean isKillCounterTabKillersVictimKillCountAligned() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterTabKillersVictimKillCountAligned;
    }

    public static void setKillCounterTabKillersVictimKillCountAligned(boolean enabled) {
        killCounterTabKillersVictimKillCountAligned = enabled;
        saveConfig();
    }

    public static int getKillCounterTabKillersVictimKillCountOffsetPx() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterTabKillersVictimKillCountOffsetPx;
    }

    public static void setKillCounterTabKillersVictimKillCountOffsetPx(int px) {
        killCounterTabKillersVictimKillCountOffsetPx = Math.max(-40, Math.min(40, px));
        saveConfig();
    }

    public static boolean isKillCounterTabKillersVictimDimmed() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterTabKillersVictimDimmed;
    }

    public static void setKillCounterTabKillersVictimDimmed(boolean enabled) {
        killCounterTabKillersVictimDimmed = enabled;
        saveConfig();
    }

    public static boolean isKillCounterBlitzStarTabIconEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return killCounterBlitzStarTabIconEnabled;
    }

    public static void setKillCounterBlitzStarTabIconEnabled(boolean enabled) {
        killCounterBlitzStarTabIconEnabled = enabled;
        saveConfig();
    }

    /** @deprecated Prefer {@link #getKillCounterTabListSortMode()}. */
    public static boolean isKillCounterSortTabByKills() {
        return getKillCounterTabListSortMode().startsWith("KILLS_THEN_");
    }

    /** @deprecated Prefer {@link #setKillCounterTabListSortMode(String)}. */
    public static void setKillCounterSortTabByKills(boolean enabled) {
        killCounterTabListSortMode = enabled ? "KILLS_THEN_WLR" : "OFF";
        saveConfig();
    }

    private static String normalizeKillCounterTabListSortMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return "OFF";
        }
        switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "KILLS":
            case "KILL":
                return "KILLS_THEN_WLR";
            case "WLR":
            case "WL":
            case "WINLOSS":
            case "WIN_LOSS":
                return "WLR_THEN_KILLS";
            case "WINS":
            case "WIN":
                return "WINS_THEN_KILLS";
            case "KILLS_THEN_WLR":
            case "KILLS_WLR":
                return "KILLS_THEN_WLR";
            case "WLR_THEN_KILLS":
            case "WLR_KILLS":
                return "WLR_THEN_KILLS";
            case "KILLS_THEN_WINS":
            case "KILLS_WINS":
                return "KILLS_THEN_WINS";
            case "WINS_THEN_KILLS":
            case "WINS_KILLS":
                return "WINS_THEN_KILLS";
            case "KILLS_THEN_OVERALL_KILLS":
            case "KILLS_OVERALL_KILLS":
            case "KILLS_OVERALL":
                return "KILLS_THEN_OVERALL_KILLS";
            case "DISTRICT":
            case "DIST":
            case "DISTRICTS":
                return "DISTRICT";
            default:
                return "OFF";
        }
    }

    private static String normalizeKillCounterTabListSortGameScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return "BOTH";
        }
        switch (scope.trim().toUpperCase(Locale.ROOT)) {
            case "SOLO":
                return "SOLO";
            case "TEAMS":
            case "TEAM":
                return "TEAMS";
            case "BOTH":
            case "SOLO_AND_TEAMS":
            case "SOLO+TEAMS":
            default:
                return "BOTH";
        }
    }

    private static String coerceKillCounterTabSortModeForScope(String mode, String scopeRaw) {
        String m = normalizeKillCounterTabListSortMode(mode);
        String sc = normalizeKillCounterTabListSortGameScope(scopeRaw);
        if ("DISTRICT".equals(m) && !"TEAMS".equals(sc)) {
            return "OFF";
        }
        return m;
    }

    // -------------------- Global Chat Settings --------------------
    public static boolean isGlobalChatEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return globalChatEnabled;
    }

    public static void setGlobalChatEnabled(boolean enabled) {
        globalChatEnabled = enabled;
        saveConfig();
    }

    public static boolean isDebugMessagesToChatEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return debugMessagesToChatEnabled;
    }

    public static void setDebugMessagesToChatEnabled(boolean enabled) {
        debugMessagesToChatEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzExtendedResultsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return blitzExtendedResultsEnabled;
    }

    public static void setBlitzExtendedResultsEnabled(boolean enabled) {
        blitzExtendedResultsEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzPregamePlayerToggleEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return blitzPregamePlayerToggleEnabled;
    }

    public static void setBlitzPregamePlayerToggleEnabled(boolean enabled) {
        blitzPregamePlayerToggleEnabled = enabled;
        saveConfig();
    }

    public static boolean isDeathmatchTimerBossBarEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return deathmatchTimerBossBarEnabled;
    }

    public static void setDeathmatchTimerBossBarEnabled(boolean enabled) {
        deathmatchTimerBossBarEnabled = enabled;
        saveConfig();
    }

    public static int getDeathmatchTimerBossBarThresholdSeconds() {
        if (!loaded) {
            loadConfig();
        }
        return deathmatchTimerBossBarThresholdSeconds;
    }

    public static void setDeathmatchTimerBossBarThresholdSeconds(int seconds) {
        deathmatchTimerBossBarThresholdSeconds = Math.max(5, Math.min(60, seconds));
        saveConfig();
    }

    public static boolean isSilentSpawnedMobStepsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return silentSpawnedMobStepsEnabled;
    }

    public static void setSilentSpawnedMobStepsEnabled(boolean enabled) {
        silentSpawnedMobStepsEnabled = enabled;
        saveConfig();
    }

    public static boolean isSilentSpawnedOcelotSteps() {
        if (!loaded) {
            loadConfig();
        }
        return silentSpawnedOcelotSteps;
    }

    public static void setSilentSpawnedOcelotSteps(boolean enabled) {
        silentSpawnedOcelotSteps = enabled;
        saveConfig();
    }

    public static boolean isSilentSpawnedWitchSteps() {
        if (!loaded) {
            loadConfig();
        }
        return silentSpawnedWitchSteps;
    }

    public static void setSilentSpawnedWitchSteps(boolean enabled) {
        silentSpawnedWitchSteps = enabled;
        saveConfig();
    }

    public static boolean isBlitzExtendedResultsMorePositionsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return blitzExtendedResultsMorePositionsEnabled;
    }

    public static void setBlitzExtendedResultsMorePositionsEnabled(boolean enabled) {
        blitzExtendedResultsMorePositionsEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzExtendedResultsStarInfoEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return blitzExtendedResultsStarInfoEnabled;
    }

    public static void setBlitzExtendedResultsStarInfoEnabled(boolean enabled) {
        blitzExtendedResultsStarInfoEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzExtendedResultsDeathmatchPlayersEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return blitzExtendedResultsDeathmatchPlayersEnabled;
    }

    public static void setBlitzExtendedResultsDeathmatchPlayersEnabled(boolean enabled) {
        blitzExtendedResultsDeathmatchPlayersEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzExtendedResultsMessageCountEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return blitzExtendedResultsMessageCountEnabled;
    }

    public static void setBlitzExtendedResultsMessageCountEnabled(boolean enabled) {
        blitzExtendedResultsMessageCountEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzExtendedResultsGameDurationEnabled() {
        if (!loaded) loadConfig();
        return blitzExtendedResultsGameDurationEnabled;
    }

    public static void setBlitzExtendedResultsGameDurationEnabled(boolean enabled) {
        blitzExtendedResultsGameDurationEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzExtendedResultsMapPlayerCountEnabled() {
        if (!loaded) loadConfig();
        return blitzExtendedResultsMapPlayerCountEnabled;
    }

    public static void setBlitzExtendedResultsMapPlayerCountEnabled(boolean enabled) {
        blitzExtendedResultsMapPlayerCountEnabled = enabled;
        saveConfig();
    }

    public static boolean isDebugHitsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return debugHitsEnabled;
    }

    public static void setDebugHitsEnabled(boolean enabled) {
        debugHitsEnabled = enabled;
        saveConfig();
    }

    public static boolean isDenickCrowdSyncEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return denickCrowdSyncEnabled;
    }

    public static void setDenickCrowdSyncEnabled(boolean enabled) {
        denickCrowdSyncEnabled = enabled;
        saveConfig();
    }

    public static String getDenickCrowdSyncBaseUrl() {
        if (!loaded) {
            loadConfig();
        }
        return denickCrowdSyncBaseUrl == null ? "" : denickCrowdSyncBaseUrl;
    }

    /**
     * Origin used for crowd denick GET/POST: non-empty saved override, else {@link DenickCrowdSyncConstants#publicCrowdOriginTrimmed()}.
     */
    public static String getDenickCrowdSyncEffectiveBaseUrl() {
        if (!loaded) {
            loadConfig();
        }
        String explicit = denickCrowdSyncBaseUrl == null ? "" : denickCrowdSyncBaseUrl.trim();
        String normalized = normalizeDenickCrowdOrigin(explicit);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return normalizeDenickCrowdOrigin(DenickCrowdSyncConstants.publicCrowdOriginTrimmed());
    }

    private static String normalizeDenickCrowdOrigin(String raw) {
        if (raw == null) {
            return "";
        }
        String u = raw.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return "";
        }
        return u;
    }

    public static void setDenickCrowdSyncBaseUrl(String url) {
        denickCrowdSyncBaseUrl = url == null ? "" : url.trim();
        saveConfig();
    }

    public static String getGlobalChatEndpoint() {
        if (!loaded) {
            loadConfig();
        }
        String explicit = globalChatEndpoint == null ? "" : globalChatEndpoint.trim();
        // Keep explicit HTTP(S) override if user set one that's not the legacy loopback default.
        if (!explicit.isEmpty()
                && explicit.startsWith("http")
                && !RelayDevEndpoints.isLegacyGlobalChatOverride(explicit)) {
            return normalizeHttpRelayEndpoint(explicit);
        }
        // Default behavior: derive HTTP relay endpoint from the ShareInv URL so one relay config powers all features.
        return deriveHttpRelayEndpointFromShareInv(getShareInvRelayUrl());
    }

    public static void setGlobalChatEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return;
        globalChatEndpoint = normalizeHttpRelayEndpoint(endpoint.trim());
        saveConfig();
    }

    private static String deriveHttpRelayEndpointFromShareInv(String relayUrlRaw) {
        String relayUrl = relayUrlRaw == null ? "" : relayUrlRaw.trim();
        if (relayUrl.isEmpty()) {
            return devHttpRelayFallback();
        }
        try {
            java.net.URI in = new java.net.URI(relayUrl);
            String scheme = in.getScheme();
            if (scheme == null) {
                return devHttpRelayFallback();
            }
            String outScheme;
            if ("wss".equalsIgnoreCase(scheme)) outScheme = "https";
            else if ("ws".equalsIgnoreCase(scheme)) outScheme = "http";
            else if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) outScheme = scheme.toLowerCase();
            else outScheme = "http";

            java.net.URI out = new java.net.URI(
                    outScheme,
                    in.getUserInfo(),
                    in.getHost(),
                    in.getPort(),
                    "/relay",
                    null,
                    null
            );
            return out.toString();
        } catch (Exception ignored) {
            return devHttpRelayFallback();
        }
    }

    private static String devHttpRelayFallback() {
        if (GomodReleaseBuild.PUBLIC_RELEASE) {
            String pub = ShareInvConstants.publicRelayTrimmed();
            if (!pub.isEmpty()) {
                return deriveHttpRelayEndpointFromShareInv(pub);
            }
            return "";
        }
        return RelayDevEndpoints.defaultHttpRelay();
    }

    private static String devWsRelayFallback() {
        if (GomodReleaseBuild.PUBLIC_RELEASE) {
            return "";
        }
        return RelayDevEndpoints.defaultWsRelay();
    }

    private static String normalizeHttpRelayEndpoint(String endpointRaw) {
        String endpoint = endpointRaw == null ? "" : endpointRaw.trim();
        if (endpoint.isEmpty()) {
            return endpoint;
        }
        try {
            java.net.URI in = new java.net.URI(endpoint);
            String scheme = in.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return endpoint;
            }
            String path = in.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                java.net.URI out = new java.net.URI(
                        scheme.toLowerCase(),
                        in.getUserInfo(),
                        in.getHost(),
                        in.getPort(),
                        "/relay",
                        in.getQuery(),
                        in.getFragment()
                );
                return out.toString();
            }
            return endpoint;
        } catch (Exception ignored) {
            return endpoint;
        }
    }

    public static String getGlobalChatChannel() {
        if (!loaded) {
            loadConfig();
        }
        return globalChatChannel;
    }

    public static void setGlobalChatChannel(String channel) {
        if (channel == null || channel.trim().isEmpty()) return;
        globalChatChannel = channel.trim();
        saveConfig();
    }

    public static int getGlobalChatPollSeconds() {
        if (!loaded) {
            loadConfig();
        }
        return Math.max(1, globalChatPollSeconds);
    }

    public static void setGlobalChatPollSeconds(int seconds) {
        globalChatPollSeconds = Math.max(1, seconds);
        saveConfig();
    }

    // -------------------- ShareInv relay --------------------
    /**
     * URL used for WebSocket (override, or built-in public relay, or local dev fallback).
     */
    public static String getShareInvRelayUrl() {
        if (!loaded) {
            loadConfig();
        }
        String override = shareInvRelayUrl == null ? "" : shareInvRelayUrl.trim();
        if (!override.isEmpty()) {
            return override;
        }
        String pub = ShareInvConstants.publicRelayTrimmed();
        if (!pub.isEmpty()) {
            return pub;
        }
        return devWsRelayFallback();
    }

    /**
     * Value saved from {@code /shareinv url}; empty means “use public relay or dev fallback”.
     */
    public static String getShareInvRelayUrlOverride() {
        if (!loaded) {
            loadConfig();
        }
        return shareInvRelayUrl == null ? "" : shareInvRelayUrl.trim();
    }

    /**
     * True if the player set a custom URL or a non-empty {@link ShareInvConstants#PUBLIC_RELAY_WSS} is compiled in.
     * Used to avoid auto-connecting when no relay is configured for mainstream users.
     */
    public static boolean isShareInvRelayConfigured() {
        if (!loaded) {
            loadConfig();
        }
        if (shareInvRelayUrl != null && !shareInvRelayUrl.trim().isEmpty()) {
            return true;
        }
        return !ShareInvConstants.publicRelayTrimmed().isEmpty();
    }

    public static void setShareInvRelayUrl(String url) {
        shareInvRelayUrl = url == null ? "" : url.trim();
        saveConfig();
    }

    public static String getShareInvSecret() {
        if (!loaded) {
            loadConfig();
        }
        return shareInvSecret == null ? "" : shareInvSecret;
    }

    public static void setShareInvSecret(String secret) {
        shareInvSecret = secret == null ? "" : secret;
        saveConfig();
    }

    public static boolean isShareInvAutoConnect() {
        if (!loaded) {
            loadConfig();
        }
        return shareInvAutoConnect;
    }

    public static void setShareInvAutoConnect(boolean v) {
        shareInvAutoConnect = v;
        saveConfig();
    }

    public static String getLastNotifiedModVersion() {
        if (!loaded) {
            loadConfig();
        }
        return lastNotifiedModVersion == null ? "" : lastNotifiedModVersion;
    }

    public static void setLastNotifiedModVersion(String version) {
        lastNotifiedModVersion = version == null ? "" : version.trim();
        saveConfig();
    }

    public static java.util.List<String> getGomodFriends() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendsForCurrentAccount();
        return new java.util.ArrayList<String>(gomodFriends);
    }

    public static String getOrCreateGomodSocialId() {
        if (!loaded && !loading) {
            loadConfig();
        }
        String installId = getOrCreateGomodSocialInstallId();
        String accountKey = currentStandaloneCapeAccountKey();
        String next = installId;
        if (accountKey != null && !accountKey.isEmpty()) {
            next = java.util.UUID.nameUUIDFromBytes(("gomod-social-account:" + installId + ":" + accountKey)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "")
                    .toLowerCase(java.util.Locale.ROOT);
        }
        if (!next.equals(gomodSocialId)) {
            gomodSocialId = next;
            saveConfig();
        }
        return gomodSocialId;
    }

    private static String getOrCreateGomodSocialInstallId() {
        if (gomodSocialInstallId == null || gomodSocialInstallId.isEmpty()) {
            gomodSocialInstallId = java.util.UUID.randomUUID().toString().replace("-", "")
                    .toLowerCase(java.util.Locale.ROOT);
        }
        return gomodSocialInstallId;
    }

    private static String normalizeGomodSocialId(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "");
        if (s.length() < 32) {
            return "";
        }
        if (s.length() > 32) {
            s = s.substring(0, 32);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || (c > '9' && c < 'a') || c > 'f') {
                return "";
            }
        }
        return s;
    }

    public static String getFriendSocialId(String friendName) {
        if (!loaded) {
            loadConfig();
        }
        if (friendName == null || friendName.trim().isEmpty()) {
            return "";
        }
        String id = gomodFriendSocialIds.get(friendName.trim().toLowerCase(java.util.Locale.ROOT));
        return id == null ? "" : id;
    }

    public static void rememberFriendSocialId(String friendDisplayName, String friendSocialId) {
        if (friendDisplayName == null || friendDisplayName.trim().isEmpty()) {
            return;
        }
        String id = normalizeGomodSocialId(friendSocialId);
        if (id.isEmpty()) {
            return;
        }
        if (!loaded) {
            loadConfig();
        }
        String key = friendDisplayName.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.equals(gomodFriendSocialIds.get(key))) {
            return;
        }
        gomodFriendSocialIds.put(key, id);
        saveConfig();
    }

    /** One-time: copy friends bucket from old per-MC key to gomod Social id. */
    public static void migrateGomodFriendsBucket(String legacyMcKey, String socialKey) {
        if (legacyMcKey == null || socialKey == null || legacyMcKey.isEmpty() || socialKey.isEmpty()
                || legacyMcKey.equals(socialKey)) {
            return;
        }
        if (!loaded) {
            loadConfig();
        }
        if (gomodFriendsByAccount.containsKey(socialKey)) {
            return;
        }
        java.util.List<String> fromMc = gomodFriendsByAccount.get(legacyMcKey);
        if (fromMc != null && !fromMc.isEmpty()) {
            gomodFriendsByAccount.put(socialKey, new java.util.ArrayList<String>(fromMc));
            saveConfig();
        }
    }

    /** One-time copy of pending requests from the old gomod-install key to the per-profile friends key. */
    private static void migrateGomodFriendRequestsBucket(String legacySocialKey, String friendsKey) {
        if (legacySocialKey == null || friendsKey == null || legacySocialKey.isEmpty() || friendsKey.isEmpty()
                || legacySocialKey.equals(friendsKey)) {
            return;
        }
        if (gomodIncomingFriendRequestsByAccount.containsKey(friendsKey)
                && gomodOutgoingFriendRequestsByAccount.containsKey(friendsKey)) {
            java.util.List<String> in = gomodIncomingFriendRequestsByAccount.get(friendsKey);
            java.util.List<String> out = gomodOutgoingFriendRequestsByAccount.get(friendsKey);
            if ((in != null && !in.isEmpty()) || (out != null && !out.isEmpty())) {
                return;
            }
        }
        java.util.List<String> fromIn = gomodIncomingFriendRequestsByAccount.get(legacySocialKey);
        java.util.List<String> fromOut = gomodOutgoingFriendRequestsByAccount.get(legacySocialKey);
        java.util.List<String> fromSeen = gomodSeenIncomingFriendRequestsByAccount.get(legacySocialKey);
        boolean changed = false;
        if (fromIn != null && !fromIn.isEmpty()) {
            gomodIncomingFriendRequestsByAccount.put(friendsKey, new java.util.ArrayList<String>(fromIn));
            changed = true;
        }
        if (fromOut != null && !fromOut.isEmpty()) {
            gomodOutgoingFriendRequestsByAccount.put(friendsKey, new java.util.ArrayList<String>(fromOut));
            changed = true;
        }
        if (fromSeen != null && !fromSeen.isEmpty()) {
            gomodSeenIncomingFriendRequestsByAccount.put(friendsKey, new java.util.ArrayList<String>(fromSeen));
            changed = true;
        }
        if (changed) {
            saveConfigNow();
        }
    }

    private static void syncGomodFriendsForCurrentAccount() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            GomodSocialAccount.refresh(mc);
            syncGomodFriendsForAccountKey(GomodSocialAccount.currentFriendsKey());
        } catch (Throwable ignored) {
        }
    }

    /** Switch active in-memory friend list to the bucket for this Minecraft profile / relay account key. */
    public static void syncGomodFriendsForAccountKey(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (key.equals(activeGomodFriendsAccount)) {
            return;
        }
        if (activeGomodFriendsAccount != null && !activeGomodFriendsAccount.isEmpty()) {
            gomodFriendsByAccount.put(activeGomodFriendsAccount, new java.util.ArrayList<String>(gomodFriends));
        }
        activeGomodFriendsAccount = key;
        java.util.List<String> saved = gomodFriendsByAccount.get(key);
        if (saved != null) {
            gomodFriends = new java.util.ArrayList<String>(saved);
        } else if (!gomodFriendsLegacyPending.isEmpty()) {
            gomodFriends = new java.util.ArrayList<String>(gomodFriendsLegacyPending);
            gomodFriendsLegacyPending.clear();
        } else {
            gomodFriends = new java.util.ArrayList<String>();
        }
        gomodFriendsByAccount.put(key, gomodFriends);
        gomodFriendsServiceAuthoritative = false;
    }

    /**
     * Replace the active profile's friend list with the gomod Service snapshot (poll / accept / remove).
     */
    public static void applyFriendsFromService(com.google.gson.JsonArray snapshot) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendsForCurrentAccount();
        java.util.ArrayList<String> next = new java.util.ArrayList<String>();
        if (snapshot != null) {
            for (int i = 0; i < snapshot.size(); i++) {
                if (!snapshot.get(i).isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject o = snapshot.get(i).getAsJsonObject();
                String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "";
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                name = name.trim();
                next.add(preferredGomodFriendDisplayName(name));
                requestCanonicalFriendName(name);
                String sid = o.has("socialId") && !o.get("socialId").isJsonNull() ? o.get("socialId").getAsString() : "";
                if (sid != null && !sid.trim().isEmpty()) {
                    rememberFriendSocialId(name, sid);
                }
            }
        }
        java.util.Collections.sort(next, String.CASE_INSENSITIVE_ORDER);
        boolean listChanged = !next.equals(gomodFriends);
        gomodFriends = next;
        if (activeGomodFriendsAccount != null && !activeGomodFriendsAccount.isEmpty()) {
            gomodFriendsByAccount.put(activeGomodFriendsAccount, new java.util.ArrayList<String>(gomodFriends));
        }
        gomodFriendsServiceAuthoritative = true;
        if (listChanged) {
            // Service snapshots are polled regularly. Persist real changes off the client thread; an identical
            // response must be a no-op rather than a recurring frame hitch.
            saveConfig();
        }
    }

    private static String preferredGomodFriendDisplayName(String serviceName) {
        String cached = MojangUsernameResolver.getCachedCanonicalUsername(serviceName);
        if (cached != null && !cached.trim().isEmpty()) {
            return cached.trim();
        }
        int existing = findGomodFriendIndex(serviceName);
        if (existing >= 0) {
            String stored = gomodFriends.get(existing);
            if (stored != null && !stored.trim().isEmpty()) {
                return stored.trim();
            }
        }
        return serviceName;
    }

    private static void requestCanonicalFriendName(final String serviceName) {
        MojangUsernameResolver.resolveCanonicalUsernameAsync(serviceName, canonical -> {
            if (canonical == null || canonical.equals(serviceName)) {
                return;
            }
            Runnable apply = () -> updateGomodFriendDisplayName(serviceName, canonical);
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
                if (mc != null) {
                    mc.addScheduledTask(apply);
                    return;
                }
            } catch (Throwable ignored) {
            }
            apply.run();
        });
    }

    /** Repairs legacy friend-name casing even when no fresh service snapshot has changed the list. */
    public static void refreshGomodFriendDisplayNamesAsync() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendsForCurrentAccount();
        java.util.ArrayList<String> snapshot = new java.util.ArrayList<String>(gomodFriends);
        for (String name : snapshot) {
            if (name != null && !name.trim().isEmpty()) {
                requestCanonicalFriendName(name.trim());
            }
        }
    }

    private static void updateGomodFriendDisplayName(String oldName, String canonical) {
        int idx = findGomodFriendIndex(oldName);
        if (idx < 0 || canonical == null || canonical.trim().isEmpty()) {
            return;
        }
        String fixed = canonical.trim();
        String current = gomodFriends.get(idx);
        if (fixed.equals(current)) {
            return;
        }
        gomodFriends.set(idx, fixed);
        if (activeGomodFriendsAccount != null && !activeGomodFriendsAccount.isEmpty()) {
            gomodFriendsByAccount.put(activeGomodFriendsAccount,
                    new java.util.ArrayList<String>(gomodFriends));
        }
        saveConfig();
    }

    public static boolean isGomodFriendsServiceAuthoritative() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendsForCurrentAccount();
        return gomodFriendsServiceAuthoritative;
    }

    /** One-time upload of legacy local friends to the relay after upgrading (server-side list was empty). */
    public static boolean shouldPostFriendsBootstrap() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendsForCurrentAccount();
        if (activeGomodFriendsAccount == null || activeGomodFriendsAccount.isEmpty()) {
            return false;
        }
        return !gomodFriendsBootstrapDoneKeys.contains(activeGomodFriendsAccount)
                && !gomodFriends.isEmpty() && !gomodFriendsServiceAuthoritative;
    }

    public static void markFriendsBootstrapPosted() {
        if (activeGomodFriendsAccount != null && !activeGomodFriendsAccount.isEmpty()) {
            gomodFriendsBootstrapDoneKeys.add(activeGomodFriendsAccount);
        }
    }

    private static int findGomodFriendIndex(String name) {
        syncGomodFriendsForCurrentAccount();
        if (name == null) {
            return -1;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < gomodFriends.size(); i++) {
            String f = gomodFriends.get(i);
            if (f != null && f.trim().equalsIgnoreCase(n)) {
                return i;
            }
        }
        return -1;
    }

    public static String getGomodFriendDisplayName(String name) {
        if (!loaded) {
            loadConfig();
        }
        int idx = findGomodFriendIndex(name);
        if (idx >= 0) {
            String stored = gomodFriends.get(idx);
            if (stored != null && !stored.trim().isEmpty()) {
                return stored.trim();
            }
        }
        return name == null ? "" : name.trim();
    }

    public static boolean isGomodFriend(String name) {
        if (!loaded) {
            loadConfig();
        }
        if (name == null) {
            return false;
        }
        return findGomodFriendIndex(name) >= 0;
    }

    public static boolean addGomodFriend(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendsForCurrentAccount();
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        int existing = findGomodFriendIndex(n);
        if (existing >= 0) {
            String stored = gomodFriends.get(existing);
            if (stored == null || !stored.trim().equals(n)) {
                gomodFriends.set(existing, n);
                saveConfigNow();
            }
            return false;
        }
        gomodFriends.add(n);
        removeGomodIncomingFriendRequest(n);
        removeGomodOutgoingFriendRequest(n);
        saveConfigNow();
        return true;
    }

    public static boolean isAcceptIncomingFriendScreenshots() {
        if (!loaded) {
            loadConfig();
        }
        return acceptIncomingFriendScreenshots;
    }

    public static void setAcceptIncomingFriendScreenshots(boolean accept) {
        acceptIncomingFriendScreenshots = accept;
        saveConfigNow();
    }

    public static boolean isAcceptIncomingNonFriendMessages() {
        if (!loaded) {
            loadConfig();
        }
        return acceptIncomingNonFriendMessages;
    }

    public static void setAcceptIncomingNonFriendMessages(boolean accept) {
        acceptIncomingNonFriendMessages = accept;
        saveConfigNow();
    }

    public static boolean isAcceptIncomingShareInv() {
        if (!loaded) {
            loadConfig();
        }
        return acceptIncomingShareInv;
    }

    public static void setAcceptIncomingShareInv(boolean accept) {
        acceptIncomingShareInv = accept;
        saveConfigNow();
    }

    public static boolean isAutoAcceptIncomingCapes() {
        if (!loaded) {
            loadConfig();
        }
        return autoAcceptIncomingCapes;
    }

    public static void setAutoAcceptIncomingCapes(boolean accept) {
        autoAcceptIncomingCapes = accept;
        saveConfigNow();
    }

    public static boolean isSocialMsgIconHudEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return socialMsgIconHudEnabled;
    }

    public static void setSocialMsgIconHudEnabled(boolean enabled) {
        socialMsgIconHudEnabled = enabled;
        saveConfigNow();
    }

    public static boolean isPublicMessagePopupsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return publicMessagePopupsEnabled;
    }

    public static void setPublicMessagePopupsEnabled(boolean enabled) {
        publicMessagePopupsEnabled = enabled;
        saveConfigNow();
    }

    public static boolean removeGomodFriend(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendsForCurrentAccount();
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        int idx = findGomodFriendIndex(n);
        if (idx >= 0) {
            gomodFriends.remove(idx);
            removeGomodIncomingFriendRequest(n);
            removeGomodOutgoingFriendRequest(n);
            saveConfigNow();
            return true;
        }
        return false;
    }

    private static void loadGomodFriendRequestArray(com.google.gson.JsonObject config, String key,
            java.util.List<String> target) {
        target.clear();
        if (!config.has(key) || !config.get(key).isJsonArray()) {
            return;
        }
        com.google.gson.JsonArray arr = config.getAsJsonArray(key);
        for (int i = 0; i < arr.size(); i++) {
            com.google.gson.JsonElement el = arr.get(i);
            if (el != null && el.isJsonPrimitive()) {
                String n = el.getAsString();
                if (n != null) {
                    String s = n.trim();
                    if (!s.isEmpty() && findNameIndexCaseInsensitive(target, s) < 0) {
                        target.add(s);
                    }
                }
            }
        }
    }

    private static void loadGomodFriendRequestMap(com.google.gson.JsonObject config, String key,
            java.util.LinkedHashMap<String, java.util.List<String>> target) {
        target.clear();
        if (!config.has(key) || !config.get(key).isJsonObject()) {
            return;
        }
        com.google.gson.JsonObject map = config.getAsJsonObject(key);
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : map.entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonArray()) {
                continue;
            }
            java.util.List<String> list = new java.util.ArrayList<String>();
            com.google.gson.JsonArray arr = e.getValue().getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonElement el = arr.get(i);
                if (el != null && el.isJsonPrimitive()) {
                    String s = el.getAsString();
                    if (s != null && !s.trim().isEmpty()) {
                        list.add(s.trim());
                    }
                }
            }
            target.put(e.getKey(), list);
        }
    }

    private static com.google.gson.JsonObject buildGomodFriendRequestMapJson(
            java.util.LinkedHashMap<String, java.util.List<String>> map) {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        for (java.util.Map.Entry<String, java.util.List<String>> e : map.entrySet()) {
            out.add(e.getKey(), buildGomodFriendRequestJsonArray(e.getValue()));
        }
        return out;
    }

    private static com.google.gson.JsonArray buildGomodFriendRequestJsonArray(java.util.List<String> names) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String n : names) {
            if (n != null) {
                String s = n.trim();
                if (!s.isEmpty()) {
                    arr.add(new com.google.gson.JsonPrimitive(s));
                }
            }
        }
        return arr;
    }

    private static int findNameIndexCaseInsensitive(java.util.List<String> list, String name) {
        if (name == null) {
            return -1;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            String f = list.get(i);
            if (f != null && f.trim().equalsIgnoreCase(n)) {
                return i;
            }
        }
        return -1;
    }

    /** Same Minecraft account even if relay casing or Mojang canonicalization differs. */
    static boolean requestPeersMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = a.trim();
        String nb = b.trim();
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.equalsIgnoreCase(nb)) {
            return true;
        }
        try {
            String ca = MojangUsernameResolver.getCachedCanonicalUsername(na);
            String cb = MojangUsernameResolver.getCachedCanonicalUsername(nb);
            if (ca != null && !ca.isEmpty() && cb != null && !cb.isEmpty() && ca.equalsIgnoreCase(cb)) {
                return true;
            }
            if (ca != null && !ca.isEmpty() && ca.equalsIgnoreCase(nb)) {
                return true;
            }
            if (cb != null && !cb.isEmpty() && cb.equalsIgnoreCase(na)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int findRequestPeerIndex(java.util.List<String> list, String name) {
        if (name == null || list == null) {
            return -1;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            String entry = list.get(i);
            if (entry != null && requestPeersMatch(entry, n)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean removeGomodOutgoingFriendRequestPeers(String name) {
        boolean changed = false;
        for (int i = gomodOutgoingFriendRequests.size() - 1; i >= 0; i--) {
            String o = gomodOutgoingFriendRequests.get(i);
            if (o != null && requestPeersMatch(o, name)) {
                gomodOutgoingFriendRequests.remove(i);
                changed = true;
            }
        }
        for (int i = gomodSeenIncomingFriendRequests.size() - 1; i >= 0; i--) {
            String n = gomodSeenIncomingFriendRequests.get(i);
            if (n == null || findNameIndexCaseInsensitive(gomodIncomingFriendRequests, n) < 0) {
                gomodSeenIncomingFriendRequests.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    private static String resolveGomodFriendRequestsAccountKey(net.minecraft.client.Minecraft mc) {
        GomodSocialAccount.refresh(mc);
        String key = GomodSocialAccount.currentFriendsKey();
        if (key == null || key.isEmpty()) {
            key = GomodSocialAccount.friendsAccountKey(mc);
        }
        if (key == null || key.isEmpty() || "unknown".equals(key)) {
            key = GomodSocialAccount.legacyMcIdentityKey(mc);
        }
        return key == null ? "" : key;
    }

    private static void persistGomodFriendRequestsForKey(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        activeGomodFriendRequestsAccount = key;
        gomodIncomingFriendRequestsByAccount.put(key, new java.util.ArrayList<String>(gomodIncomingFriendRequests));
        gomodOutgoingFriendRequestsByAccount.put(key, new java.util.ArrayList<String>(gomodOutgoingFriendRequests));
        gomodSeenIncomingFriendRequestsByAccount.put(key, new java.util.ArrayList<String>(gomodSeenIncomingFriendRequests));
    }

    private static void syncGomodFriendRequestsForCurrentAccount() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            String key = resolveGomodFriendRequestsAccountKey(mc);
            if (key.isEmpty()) {
                return;
            }
            if (key.equals(activeGomodFriendRequestsAccount)) {
                return;
            }
            if (activeGomodFriendRequestsAccount != null && !activeGomodFriendRequestsAccount.isEmpty()) {
                gomodIncomingFriendRequestsByAccount.put(activeGomodFriendRequestsAccount,
                        new java.util.ArrayList<String>(gomodIncomingFriendRequests));
                gomodOutgoingFriendRequestsByAccount.put(activeGomodFriendRequestsAccount,
                        new java.util.ArrayList<String>(gomodOutgoingFriendRequests));
                gomodSeenIncomingFriendRequestsByAccount.put(activeGomodFriendRequestsAccount,
                        new java.util.ArrayList<String>(gomodSeenIncomingFriendRequests));
            }
            activeGomodFriendRequestsAccount = key;
            migrateGomodFriendRequestsBucket(GomodSocialAccount.currentKey(), key);
            java.util.List<String> in = gomodIncomingFriendRequestsByAccount.get(key);
            java.util.List<String> out = gomodOutgoingFriendRequestsByAccount.get(key);
            java.util.List<String> seen = gomodSeenIncomingFriendRequestsByAccount.get(key);
            if (in != null) {
                gomodIncomingFriendRequests = new java.util.ArrayList<String>(in);
            } else if (!gomodIncomingFriendRequestsLegacyPending.isEmpty()) {
                gomodIncomingFriendRequests = new java.util.ArrayList<String>(gomodIncomingFriendRequestsLegacyPending);
                gomodIncomingFriendRequestsLegacyPending.clear();
            } else {
                gomodIncomingFriendRequests = new java.util.ArrayList<String>();
            }
            if (out != null) {
                gomodOutgoingFriendRequests = new java.util.ArrayList<String>(out);
            } else if (!gomodOutgoingFriendRequestsLegacyPending.isEmpty()) {
                gomodOutgoingFriendRequests = new java.util.ArrayList<String>(gomodOutgoingFriendRequestsLegacyPending);
                gomodOutgoingFriendRequestsLegacyPending.clear();
            } else {
                gomodOutgoingFriendRequests = new java.util.ArrayList<String>();
            }
            gomodSeenIncomingFriendRequests = seen == null
                    ? new java.util.ArrayList<String>() : new java.util.ArrayList<String>(seen);
            gomodIncomingFriendRequestsByAccount.put(key, gomodIncomingFriendRequests);
            gomodOutgoingFriendRequestsByAccount.put(key, gomodOutgoingFriendRequests);
            gomodSeenIncomingFriendRequestsByAccount.put(key, gomodSeenIncomingFriendRequests);
            sanitizeFriendRequestListsForSession(mc);
        } catch (Throwable ignored) {
        }
    }

    /** Drops self-name and already-friend entries from the active request lists. */
    private static void sanitizeFriendRequestListsForSession(net.minecraft.client.Minecraft mc) {
        String self = "";
        try {
            if (mc != null && mc.thePlayer != null) {
                self = mc.thePlayer.getName().trim();
            }
        } catch (Throwable ignored) {
        }
        if (self.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (int i = gomodIncomingFriendRequests.size() - 1; i >= 0; i--) {
            String n = gomodIncomingFriendRequests.get(i);
            if (n != null && (n.equalsIgnoreCase(self) || isGomodFriend(n))) {
                gomodIncomingFriendRequests.remove(i);
                changed = true;
            }
        }
        for (int i = gomodOutgoingFriendRequests.size() - 1; i >= 0; i--) {
            String n = gomodOutgoingFriendRequests.get(i);
            if (n != null && (n.equalsIgnoreCase(self) || isGomodFriend(n))) {
                gomodOutgoingFriendRequests.remove(i);
                changed = true;
            }
        }
        if (changed) {
            saveConfigNow();
        }
    }

    public static java.util.List<String> getGomodIncomingFriendRequests() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        return new java.util.ArrayList<String>(gomodIncomingFriendRequests);
    }

    public static java.util.List<String> getGomodOutgoingFriendRequests() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        return new java.util.ArrayList<String>(gomodOutgoingFriendRequests);
    }

    static int findGomodIncomingFriendRequestIndex(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        return findNameIndexCaseInsensitive(gomodIncomingFriendRequests, name);
    }

    public static boolean addGomodIncomingFriendRequest(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        if (name == null || isGomodFriend(name)) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null && n.equalsIgnoreCase(mc.thePlayer.getName())) {
            return false;
        }
        int outPeerIdx = findRequestPeerIndex(gomodOutgoingFriendRequests, n);
        if (outPeerIdx >= 0) {
            String stored = gomodOutgoingFriendRequests.get(outPeerIdx);
            if (stored != null && !stored.trim().isEmpty()) {
                n = stored.trim();
            }
        }
        boolean changed = false;
        boolean added = false;
        if (findRequestPeerIndex(gomodIncomingFriendRequests, n) < 0) {
            gomodIncomingFriendRequests.add(n);
            changed = true;
            added = true;
            int seenIdx = findNameIndexCaseInsensitive(gomodSeenIncomingFriendRequests, n);
            if (seenIdx >= 0) {
                gomodSeenIncomingFriendRequests.remove(seenIdx);
            }
        }
        if (removeGomodOutgoingFriendRequestPeers(n)) {
            changed = true;
        }
        if (changed) {
            persistGomodFriendRequestsForKey(resolveGomodFriendRequestsAccountKey(mc));
            saveConfigNow();
            return added;
        }
        return false;
    }

    public static boolean removeGomodIncomingFriendRequest(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        int idx = findNameIndexCaseInsensitive(gomodIncomingFriendRequests, name);
        if (idx >= 0) {
            gomodIncomingFriendRequests.remove(idx);
            int seenIdx = findNameIndexCaseInsensitive(gomodSeenIncomingFriendRequests, name);
            if (seenIdx >= 0) {
                gomodSeenIncomingFriendRequests.remove(seenIdx);
            }
            persistGomodFriendRequestsForKey(resolveGomodFriendRequestsAccountKey(
                    net.minecraft.client.Minecraft.getMinecraft()));
            saveConfigNow();
            return true;
        }
        return false;
    }

    public static boolean isGomodIncomingFriendRequestSeen(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        return findNameIndexCaseInsensitive(gomodSeenIncomingFriendRequests, name) >= 0;
    }

    public static void markGomodIncomingFriendRequestSeen(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        if (name == null || findNameIndexCaseInsensitive(gomodIncomingFriendRequests, name) < 0
                || findNameIndexCaseInsensitive(gomodSeenIncomingFriendRequests, name) >= 0) {
            return;
        }
        gomodSeenIncomingFriendRequests.add(name.trim());
        persistGomodFriendRequestsForKey(resolveGomodFriendRequestsAccountKey(
                net.minecraft.client.Minecraft.getMinecraft()));
        saveConfigNow();
    }

    /** Marks every currently pending incoming request as acknowledged. */
    public static void markAllGomodIncomingFriendRequestsSeen() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        boolean changed = false;
        for (String name : gomodIncomingFriendRequests) {
            if (name != null && findNameIndexCaseInsensitive(gomodSeenIncomingFriendRequests, name) < 0) {
                gomodSeenIncomingFriendRequests.add(name.trim());
                changed = true;
            }
        }
        if (changed) {
            persistGomodFriendRequestsForKey(resolveGomodFriendRequestsAccountKey(
                    net.minecraft.client.Minecraft.getMinecraft()));
            saveConfigNow();
        }
    }

    public static int getUnseenGomodIncomingFriendRequestCount() {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        int count = 0;
        for (String name : gomodIncomingFriendRequests) {
            if (name != null && findNameIndexCaseInsensitive(gomodSeenIncomingFriendRequests, name) < 0) {
                count++;
            }
        }
        return count;
    }

    public static boolean addGomodOutgoingFriendRequest(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        if (name == null || isGomodFriend(name)) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && n.equalsIgnoreCase(mc.thePlayer.getName().trim())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        if (findNameIndexCaseInsensitive(gomodOutgoingFriendRequests, n) >= 0) {
            return false;
        }
        gomodOutgoingFriendRequests.add(n);
        persistGomodFriendRequestsForKey(resolveGomodFriendRequestsAccountKey(
                net.minecraft.client.Minecraft.getMinecraft()));
        saveConfigNow();
        return true;
    }

    public static boolean removeGomodOutgoingFriendRequest(String name) {
        if (!loaded) {
            loadConfig();
        }
        syncGomodFriendRequestsForCurrentAccount();
        if (removeGomodOutgoingFriendRequestPeers(name)) {
            persistGomodFriendRequestsForKey(resolveGomodFriendRequestsAccountKey(
                    net.minecraft.client.Minecraft.getMinecraft()));
            saveConfigNow();
            return true;
        }
        return false;
    }

    public static synchronized void applyGomodFriendRequestReset(long generation) {
        if (generation <= gomodFriendRequestResetGeneration) {
            return;
        }
        gomodOutgoingFriendRequests.clear();
        gomodOutgoingFriendRequestsLegacyPending.clear();
        gomodOutgoingFriendRequestsByAccount.clear();
        if (activeGomodFriendRequestsAccount != null && !activeGomodFriendRequestsAccount.isEmpty()) {
            gomodOutgoingFriendRequestsByAccount.put(activeGomodFriendRequestsAccount,
                    new java.util.ArrayList<String>());
        }
        gomodFriendRequestResetGeneration = generation;
        saveConfigNow();
    }

	// -------------------- Replay Settings --------------------
	public static boolean isReplayHealthDisplayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return replayHealthDisplayEnabled;
	}

	public static void setReplayHealthDisplayEnabled(boolean enabled) {
		replayHealthDisplayEnabled = enabled;
		saveConfig();
	}
	
	public static String getReplayHealthDisplayStyle() {
		if (!loaded) {
			loadConfig();
		}
		return replayHealthDisplayStyle;
	}
	
	public static void setReplayHealthDisplayStyle(String style) {
		replayHealthDisplayStyle = style;
		saveConfig();
	}
	
	public static float getReplayHealthDisplayYOffset() {
		return replayHealthDisplayYOffset;
	}
	
	public static void setReplayHealthDisplayYOffset(float offset) {
		replayHealthDisplayYOffset = Math.max(0.0f, Math.min(0.8f, offset));
		saveConfig();
	}
	
	public static boolean isReplayShowInvisibleEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return replayShowInvisibleEnabled;
	}
	
	public static void setReplayShowInvisibleEnabled(boolean enabled) {
		replayShowInvisibleEnabled = enabled;
		saveConfig();
	}
	
	// -------------------- Player Target Settings --------------------
	public static boolean isPlayerTargetEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetEnabled;
	}

	public static String getPlayerTargetGlowTeammateMode() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetGlowTeammateMode != null ? playerTargetGlowTeammateMode : "DISABLED";
	}

	public static void setPlayerTargetGlowTeammateMode(String mode) {
		playerTargetGlowTeammateMode = mode != null ? mode : "DISABLED";
		saveConfig();
	}

	public static boolean isPlayerTargetTeammateTrailEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetTeammateTrailEnabled;
	}

	public static void setPlayerTargetTeammateTrailEnabled(boolean enabled) {
		playerTargetTeammateTrailEnabled = enabled;
		saveConfig();
	}
	
	public static void setPlayerTargetEnabled(boolean enabled) {
		playerTargetEnabled = enabled;
		saveConfig();
	}
	
	public static boolean isPlayerTargetShowVerticalDistance() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetShowVerticalDistance;
	}
	
	public static void setPlayerTargetShowVerticalDistance(boolean show) {
		playerTargetShowVerticalDistance = show;
		saveConfig();
	}
	
	public static boolean isPlayerTargetShowBackground() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetShowBackground;
	}
	
	public static void setPlayerTargetShowBackground(boolean show) {
		playerTargetShowBackground = show;
		saveConfig();
	}
	
	public static String getPlayerTargetPosition() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetPosition;
	}
	
	public static void setPlayerTargetPosition(String position) {
		playerTargetPosition = position;
		saveConfig();
	}
	
	public static int getPlayerTargetCustomX() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetCustomX;
	}
	
	public static void setPlayerTargetCustomX(int x) {
		playerTargetCustomX = x;
		saveConfig();
	}
	
	public static int getPlayerTargetCustomY() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetCustomY;
	}
	
	public static void setPlayerTargetCustomY(int y) {
		playerTargetCustomY = y;
		saveConfig();
	}
	
	public static boolean isPlayerTargetShowEnchantments() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetShowEnchantments;
	}
	
	public static void setPlayerTargetShowEnchantments(boolean show) {
		playerTargetShowEnchantments = show;
		saveConfig();
	}
	
	public static boolean isPlayerTargetShowTauntInfo() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetShowTauntInfo;
	}
	
	public static void setPlayerTargetShowTauntInfo(boolean show) {
		playerTargetShowTauntInfo = show;
		saveConfig();
	}

	public static boolean isPlayerTargetTextShadow() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetTextShadow;
	}

	public static void setPlayerTargetTextShadow(boolean shadow) {
		playerTargetTextShadow = shadow;
		saveConfig();
	}

	public static boolean isPlayerTargetTeamInviteNotifierEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetTeamInviteNotifierEnabled;
	}

	public static void setPlayerTargetTeamInviteNotifierEnabled(boolean enabled) {
		playerTargetTeamInviteNotifierEnabled = enabled;
		saveConfig();
	}
	
	public static int getPlayerTargetBackgroundOpacity() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetBackgroundOpacity;
	}
	
	public static void setPlayerTargetBackgroundOpacity(int opacity) {
		playerTargetBackgroundOpacity = opacity;
		saveConfig();
	}

	public static int getPlayerTargetBackgroundColor() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetBackgroundColor;
	}

	public static void setPlayerTargetBackgroundColor(int color) {
		playerTargetBackgroundColor = color & 0xFFFFFF;
		saveConfig();
	}
	
	public static float getPlayerTargetScale() {
		if (!loaded) {
			loadConfig();
		}
		return playerTargetScale;
	}
	
	public static void setPlayerTargetScale(float scale) {
		playerTargetScale = Math.max(0.5f, Math.min(4.0f, scale));
		saveConfig();
	}
	
	// MobHealthDisplay getters and setters
	public static boolean isMobHealthDisplayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayEnabled;
	}
	
	public static void setMobHealthDisplayEnabled(boolean enabled) {
		mobHealthDisplayEnabled = enabled;
		saveConfig();
	}
	
	public static boolean isMobHealthDisplayShowBackground() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayShowBackground;
	}
	
	public static void setMobHealthDisplayShowBackground(boolean value) {
		mobHealthDisplayShowBackground = value;
		saveConfig();
	}

	public static boolean isMobHealthDisplayShowEffects() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayShowEffects;
	}

	public static void setMobHealthDisplayShowEffects(boolean value) {
		mobHealthDisplayShowEffects = value;
		saveConfig();
	}

	public static boolean isMobHealthDisplayTextShadow() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayTextShadow;
	}

	public static void setMobHealthDisplayTextShadow(boolean value) {
		mobHealthDisplayTextShadow = value;
		saveConfig();
	}
	
	public static int getMobHealthDisplayBackgroundOpacity() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayBackgroundOpacity;
	}
	
	public static void setMobHealthDisplayBackgroundOpacity(int value) {
		mobHealthDisplayBackgroundOpacity = value;
		saveConfig();
	}

	public static int getMobHealthDisplayBackgroundColor() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayBackgroundColor;
	}

	public static void setMobHealthDisplayBackgroundColor(int value) {
		mobHealthDisplayBackgroundColor = value & 0xFFFFFF;
		saveConfig();
	}
	
	public static double getMobHealthDisplayMaxDistance() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayMaxDistance;
	}
	
	public static void setMobHealthDisplayMaxDistance(double value) {
		mobHealthDisplayMaxDistance = value;
		saveConfig();
	}
	
	public static double getMobHealthDisplayPersistenceTime() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayPersistenceTime;
	}
	
	public static void setMobHealthDisplayPersistenceTime(double value) {
		mobHealthDisplayPersistenceTime = value;
		saveConfig();
	}
	
	public static int getMobHealthDisplayPanelX() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayPanelX;
	}
	
	public static void setMobHealthDisplayPanelX(int value) {
		mobHealthDisplayPanelX = value;
		saveConfig();
	}
	
	public static int getMobHealthDisplayPanelY() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayPanelY;
	}
	
	public static void setMobHealthDisplayPanelY(int value) {
		mobHealthDisplayPanelY = value;
		saveConfig();
	}

	public static float getMobHealthDisplayScale() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayScale;
	}

	public static void setMobHealthDisplayScale(float value) {
		mobHealthDisplayScale = Math.max(0.5f, Math.min(4.0f, value));
		saveConfig();
	}

	public static String getMobHealthDisplayMode() {
		if (!loaded) {
			loadConfig();
		}
		return mobHealthDisplayMode == null ? "BOTH" : mobHealthDisplayMode;
	}

	public static void setMobHealthDisplayMode(String mode) {
		mobHealthDisplayMode = normalizeMobHealthDisplayMode(mode);
		saveConfig();
	}

	private static String normalizeMobHealthDisplayMode(String mode) {
		if (mode == null) {
			return "PANEL";
		}
		String upper = mode.trim().toUpperCase();
		if ("NAMETAG".equals(upper) || "BOTH".equals(upper) || "BOTH2".equals(upper)) {
			return upper;
		}
		return "PANEL";
	}

	private static void loadMobHealthDisplayHealthTagDisabledIds(String raw) {
		mobHealthDisplayHealthTagDisabledIds.clear();
		if (raw == null || raw.trim().isEmpty()) {
			return;
		}
		for (String part : raw.split(",")) {
			try {
				int id = Integer.parseInt(part.trim());
				if (id > 0) {
					mobHealthDisplayHealthTagDisabledIds.add(id);
				}
			} catch (NumberFormatException ignored) {
			}
		}
	}

	private static void migrateMobHealthDisplayHealthTagDefaultDisabledIds() {
		if (mobHealthDisplayHealthTagDisabledIds.contains(93)
				&& mobHealthDisplayHealthTagDisabledIds.contains(98)
				&& mobHealthDisplayHealthTagDisabledIds.contains(101)) {
			mobHealthDisplayHealthTagDisabledIds.add(120);
			mobHealthDisplayHealthTagDisabledIds.add(90);
			mobHealthDisplayHealthTagDisabledIds.add(94);
		}
	}

	private static void migrateMobHealthDisplayHealthTagLegacyBooleans(
			com.google.gson.JsonObject config) {
		boolean hasLegacy = config.has("mobHealthDisplayHealthTagChicken")
				|| config.has("mobHealthDisplayHealthTagOcelot")
				|| config.has("mobHealthDisplayHealthTagRabbit");
		if (!hasLegacy) {
			return;
		}
		applyMobHealthDisplayHealthTagLegacyBoolean(config, "mobHealthDisplayHealthTagChicken", 93);
		applyMobHealthDisplayHealthTagLegacyBoolean(config, "mobHealthDisplayHealthTagOcelot", 98);
		applyMobHealthDisplayHealthTagLegacyBoolean(config, "mobHealthDisplayHealthTagRabbit", 101);
	}

	private static void applyMobHealthDisplayHealthTagLegacyBoolean(
			com.google.gson.JsonObject config, String key, int entityTypeId) {
		if (!config.has(key)) {
			return;
		}
		setMobHealthDisplayHealthTagEnabledForType(entityTypeId, config.get(key).getAsBoolean(), false);
	}

	private static String formatMobHealthDisplayHealthTagDisabledIds() {
		java.util.ArrayList<Integer> ids = new java.util.ArrayList<Integer>(
				mobHealthDisplayHealthTagDisabledIds);
		java.util.Collections.sort(ids);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(ids.get(i));
		}
		return sb.toString();
	}

	public static boolean isMobHealthDisplayHealthTagEnabledForType(int entityTypeId) {
		if (!loaded) {
			loadConfig();
		}
		return !mobHealthDisplayHealthTagDisabledIds.contains(entityTypeId);
	}

	public static void setMobHealthDisplayHealthTagEnabledForType(int entityTypeId, boolean enabled) {
		setMobHealthDisplayHealthTagEnabledForType(entityTypeId, enabled, true);
	}

	private static void setMobHealthDisplayHealthTagEnabledForType(int entityTypeId, boolean enabled,
			boolean persist) {
		if (enabled) {
			mobHealthDisplayHealthTagDisabledIds.remove(entityTypeId);
		} else {
			mobHealthDisplayHealthTagDisabledIds.add(entityTypeId);
		}
		if (persist) {
			saveConfig();
		}
	}

	public static void toggleMobHealthDisplayHealthTagForType(int entityTypeId) {
		setMobHealthDisplayHealthTagEnabledForType(entityTypeId,
				!isMobHealthDisplayHealthTagEnabledForType(entityTypeId));
	}

	public static boolean isMobHealthDisplayHealthTagChicken() {
		return isMobHealthDisplayHealthTagEnabledForType(93);
	}

	public static void setMobHealthDisplayHealthTagChicken(boolean enabled) {
		setMobHealthDisplayHealthTagEnabledForType(93, enabled);
	}

	public static boolean isMobHealthDisplayHealthTagOcelot() {
		return isMobHealthDisplayHealthTagEnabledForType(98);
	}

	public static void setMobHealthDisplayHealthTagOcelot(boolean enabled) {
		setMobHealthDisplayHealthTagEnabledForType(98, enabled);
	}

	public static boolean isMobHealthDisplayHealthTagRabbit() {
		return isMobHealthDisplayHealthTagEnabledForType(101);
	}

	public static void setMobHealthDisplayHealthTagRabbit(boolean enabled) {
		setMobHealthDisplayHealthTagEnabledForType(101, enabled);
	}

	public static boolean isHorseDonkeyDistanceHudEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyDistanceHudEnabled;
	}

	public static void setHorseDonkeyDistanceHudEnabled(boolean enabled) {
		horseDonkeyDistanceHudEnabled = enabled;
		saveConfig();
	}

	public static boolean isHorseDonkeyShowBackground() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyShowBackground;
	}

	public static void setHorseDonkeyShowBackground(boolean value) {
		horseDonkeyShowBackground = value;
		saveConfig();
	}

	public static boolean isHorseDonkeyTeleportSoundEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyTeleportSoundEnabled;
	}

	public static void setHorseDonkeyTeleportSoundEnabled(boolean value) {
		horseDonkeyTeleportSoundEnabled = value;
		saveConfig();
	}

	public static int getHorseDonkeyBackgroundOpacity() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyBackgroundOpacity;
	}

	public static void setHorseDonkeyBackgroundOpacity(int value) {
		horseDonkeyBackgroundOpacity = value;
		saveConfig();
	}

	public static int getHorseDonkeyBackgroundColor() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyBackgroundColor;
	}

	public static void setHorseDonkeyBackgroundColor(int value) {
		horseDonkeyBackgroundColor = value & 0xFFFFFF;
		saveConfig();
	}

	public static int getHorseDonkeyPanelX() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyPanelX;
	}

	public static void setHorseDonkeyPanelX(int value) {
		horseDonkeyPanelX = value;
		saveConfig();
	}

	public static int getHorseDonkeyPanelY() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyPanelY;
	}

	public static void setHorseDonkeyPanelY(int value) {
		horseDonkeyPanelY = value;
		saveConfig();
	}

	public static float getHorseDonkeyScale() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyScale;
	}

	public static void setHorseDonkeyScale(float value) {
		horseDonkeyScale = Math.max(0.5f, Math.min(4.0f, value));
		saveConfig();
	}

	public static int getHorseDonkeyHudTitleColor() {
		if (!loaded) loadConfig();
		return horseDonkeyHudTitleColor;
	}

	public static void setHorseDonkeyHudTitleColor(int value) {
		horseDonkeyHudTitleColor = value & 0xFFFFFF;
		saveConfig();
	}

	public static int getHorseDonkeyHudValueColor() {
		if (!loaded) loadConfig();
		return horseDonkeyHudValueColor;
	}

	public static void setHorseDonkeyHudValueColor(int value) {
		horseDonkeyHudValueColor = value & 0xFFFFFF;
		saveConfig();
	}

	public static int getHorseDonkeyHudBlocksColor() {
		if (!loaded) loadConfig();
		return horseDonkeyHudBlocksColor;
	}

	public static void setHorseDonkeyHudBlocksColor(int value) {
		horseDonkeyHudBlocksColor = value & 0xFFFFFF;
		saveConfig();
	}

	public static boolean isGomodMenuMoveEnabled() {
		if (!loaded) loadConfig();
		return gomodMenuMoveEnabled;
	}

	public static boolean isGomodMenuDragLookEnabled() { if (!loaded) loadConfig(); return gomodMenuDragLookEnabled; } public static void setGomodMenuDragLookEnabled(boolean enabled) { gomodMenuDragLookEnabled = enabled; saveConfig(); } public static void setGomodMenuMoveEnabled(boolean enabled) {
		gomodMenuMoveEnabled = enabled;
		saveConfig();
	}

	public static boolean isMinecraftMenuControlsEnabled() {
		if (!loaded) loadConfig();
		return minecraftMenuControlsEnabled;
	}

	public static void setMinecraftMenuControlsEnabled(boolean enabled) {
		minecraftMenuControlsEnabled = enabled;
		saveConfig();
	}

	public static boolean isLunarMenuControlsEnabled() {
		if (!loaded) loadConfig();
		return lunarMenuControlsEnabled;
	}

	public static void setLunarMenuControlsEnabled(boolean enabled) {
		lunarMenuControlsEnabled = enabled;
		saveConfig();
	}

	public static int getGomodMenuGuiScale() {
		if (!loaded) loadConfig();
		return gomodMenuGuiScale;
	}

	public static void setGomodMenuGuiScale(int scale) {
		gomodMenuGuiScale = Math.max(-1, Math.min(5, scale));
		saveConfig();
	}

	public static boolean isHypixelTabDupeHideEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return hypixelTabDupeHideEnabled;
	}

	public static void setHypixelTabDupeHideEnabled(boolean enabled) {
		hypixelTabDupeHideEnabled = enabled;
		saveConfig();
	}

	public static boolean isHypixelTabQuitGhostHideEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return hypixelTabQuitGhostHideEnabled;
	}

	public static void setHypixelTabQuitGhostHideEnabled(boolean enabled) {
		hypixelTabQuitGhostHideEnabled = enabled;
		saveConfig();
	}

	public static boolean isForceReplayPlayerHealthTagsEnabled() {
		if (!loaded) loadConfig();
		return forceReplayPlayerHealthTagsEnabled;
	}

	public static void setForceReplayPlayerHealthTagsEnabled(boolean enabled) {
		forceReplayPlayerHealthTagsEnabled = enabled;
		saveConfig();
	}

	public static boolean isBlitzRektHitboxOverlayEnabled() {
		if (!loaded) loadConfig();
		return blitzRektHitboxOverlayEnabled;
	}

	public static void setBlitzRektHitboxOverlayEnabled(boolean enabled) {
		blitzRektHitboxOverlayEnabled = enabled;
		saveConfig();
	}

	public static double getBlitzRektHitboxPreviewPadding(String side) {
		if (!loaded) loadConfig();
		if ("left".equalsIgnoreCase(side)) return blitzRektHitboxPreviewLeft;
		if ("right".equalsIgnoreCase(side)) return blitzRektHitboxPreviewRight;
		if ("up".equalsIgnoreCase(side)) return blitzRektHitboxPreviewUp;
		if ("down".equalsIgnoreCase(side)) return blitzRektHitboxPreviewDown;
		if ("width".equalsIgnoreCase(side)) return blitzRektHitboxPreviewLeft + blitzRektHitboxPreviewRight;
		if ("height".equalsIgnoreCase(side)) return blitzRektHitboxPreviewUp + blitzRektHitboxPreviewDown;
		throw new IllegalArgumentException("Unknown #rekt preview side: " + side);
	}

	public static double adjustBlitzRektHitboxPreviewPadding(String side, double delta) {
		if (!loaded) loadConfig();
		if (Double.isNaN(delta) || Double.isInfinite(delta)) {
			throw new IllegalArgumentException("Padding adjustment must be a finite number.");
		}
		if ("width".equalsIgnoreCase(side)) {
			adjustBlitzRektPreviewSide("left", delta / 2.0D);
			adjustBlitzRektPreviewSide("right", delta / 2.0D);
		} else if ("height".equalsIgnoreCase(side)) {
			adjustBlitzRektPreviewSide("up", delta / 2.0D);
			adjustBlitzRektPreviewSide("down", delta / 2.0D);
		} else {
			adjustBlitzRektPreviewSide(side, delta);
		}
		saveConfig();
		return getBlitzRektHitboxPreviewPadding(side);
	}

	private static void adjustBlitzRektPreviewSide(String side, double delta) {
		double current = getBlitzRektHitboxPreviewPadding(side);
		double adjusted = Math.max(-8.0D, Math.min(8.0D, current + delta));
		if ("left".equalsIgnoreCase(side)) blitzRektHitboxPreviewLeft = adjusted;
		else if ("right".equalsIgnoreCase(side)) blitzRektHitboxPreviewRight = adjusted;
		else if ("up".equalsIgnoreCase(side)) blitzRektHitboxPreviewUp = adjusted;
		else if ("down".equalsIgnoreCase(side)) blitzRektHitboxPreviewDown = adjusted;
		else throw new IllegalArgumentException("Unknown #rekt preview side: " + side);
	}

	public static void resetBlitzRektHitboxPreviewPadding() {
		if (!loaded) loadConfig();
		blitzRektHitboxPreviewLeft = 0.0D;
		blitzRektHitboxPreviewRight = 0.0D;
		blitzRektHitboxPreviewUp = 0.0D;
		blitzRektHitboxPreviewDown = 0.0D;
		saveConfig();
	}

	private static double readBlitzRektPreviewPadding(JsonObject config, String key) {
		if (!config.has(key)) return 0.0D;
		try {
			double value = config.get(key).getAsDouble();
			return Double.isNaN(value) || Double.isInfinite(value)
					? 0.0D : Math.max(-8.0D, Math.min(8.0D, value));
		} catch (RuntimeException ignored) {
			return 0.0D;
		}
	}

	public static boolean isHorseFoodNametagEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return horseFoodNametagEnabled;
	}

	public static void setHorseFoodNametagEnabled(boolean enabled) {
		horseFoodNametagEnabled = enabled;
		saveConfig();
	}

	public static boolean isHorseDonkeyTextShadow() {
		if (!loaded) {
			loadConfig();
		}
		return horseDonkeyTextShadow;
	}

	public static void setHorseDonkeyTextShadow(boolean shadow) {
		horseDonkeyTextShadow = shadow;
		saveConfig();
	}

	public static boolean isMobNametagsEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return mobNametagsEnabled;
	}

	public static void setMobNametagsEnabled(boolean enabled) {
		mobNametagsEnabled = enabled;
		saveConfig();
	}

	public static boolean isFriendlyMobNametagColorEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return friendlyMobNametagColorEnabled;
	}

	public static void setFriendlyMobNametagColorEnabled(boolean enabled) {
		friendlyMobNametagColorEnabled = enabled;
		saveConfig();
	}

	public static boolean isMobTargetDisplayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return mobTargetDisplayEnabled;
	}

	private static String clampMobTargetMarker(String raw) {
		if (raw == null) {
			return "!";
		}
		String t = raw.replace('&', '\u00a7').replace('\r', ' ').replace('\n', ' ').trim();
		if (t.isEmpty()) {
			return "!";
		}
		if (t.length() > 16) {
			t = t.substring(0, 16);
		}
		return t;
	}

	private static String normalizeDisguiseMobVisualKind(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "OFF";
		}
		String t = raw.trim();
		if (t.length() > 48) {
			t = t.substring(0, 48);
		}
		String u = t.toUpperCase(java.util.Locale.ROOT);
		switch (u) {
			case "OFF":
			case "ZOMBIE":
			case "SKELETON":
			case "CREEPER":
			case "COW":
			case "PIG":
			case "SPIDER":
				return u;
			default:
				// Custom mob id for LocalMobDisguise (e.g. EnderDragon) — alphanumeric + underscore only
				StringBuilder b = new StringBuilder();
				for (int i = 0; i < t.length(); i++) {
					char c = t.charAt(i);
					if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
						b.append(c);
					}
				}
				return b.length() > 0 ? b.toString() : "OFF";
		}
	}

	public static String getDisguiseMobVisualKindName() {
		if (!loaded) loadConfig();
		return disguiseMobVisualKind;
	}

	public static void setDisguiseMobVisualKindName(String kind) {
		disguiseMobVisualKind = normalizeDisguiseMobVisualKind(kind);
		saveConfig();
	}

	public static String getDisguiseMobVisualLastUsedName() {
		if (!loaded) loadConfig();
		return disguiseMobVisualLastUsed;
	}

	public static void setDisguiseMobVisualLastUsedName(String name) {
		disguiseMobVisualLastUsed = (name == null ? "OFF" : name);
		saveConfig();
	}

	// BlitzKitTimerHUD getters and setters
	public static boolean isBlitzKitTimerHudEnabled() {
		if (!loaded) loadConfig();
		return blitzKitTimerHudEnabled;
	}

	public static void setBlitzKitTimerHudEnabled(boolean enabled) {
		blitzKitTimerHudEnabled = enabled;
		saveConfig();
	}

	public static float getBlitzKitTimerHudScale() {
		if (!loaded) loadConfig();
		return blitzKitTimerHudScale;
	}

	public static void setBlitzKitTimerHudScale(float scale) {
		blitzKitTimerHudScale = Math.max(0.5f, Math.min(5.0f, scale));
		saveConfig();
	}

	public static int getBlitzKitTimerHudOffsetCenterX() {
		if (!loaded) loadConfig();
		return blitzKitTimerHudOffsetCenterX;
	}

	public static void setBlitzKitTimerHudOffsetCenterX(int offset) {
		blitzKitTimerHudOffsetCenterX = offset;
		saveConfig();
	}

	public static int getBlitzKitTimerHudPosY() {
		if (!loaded) loadConfig();
		return blitzKitTimerHudPosY;
	}

	public static void setBlitzKitTimerHudPosY(int y) {
		blitzKitTimerHudPosY = y;
		saveConfig();
	}

	public static boolean isBlitzKitTimerHudTextShadow() {
		if (!loaded) loadConfig();
		return blitzKitTimerHudTextShadow;
	}

	public static void setBlitzKitTimerHudTextShadow(boolean shadow) {
		blitzKitTimerHudTextShadow = shadow;
		saveConfig();
	}

	public static int getBlitzKitTimerHudBg() {
		if (!loaded) loadConfig();
		return blitzKitTimerHudBg;
	}

	public static void setBlitzKitTimerHudBg(int v) {
		blitzKitTimerHudBg = clamp255(v);
		saveConfig();
	}

	public static int getBlitzKitTimerHudBgColor() {
		if (!loaded) {
			loadConfig();
		}
		return blitzKitTimerHudBgColor;
	}

	public static void setBlitzKitTimerHudBgColor(int v) {
		blitzKitTimerHudBgColor = v & 0xFFFFFF;
		saveConfig();
	}

	public static boolean isIncomingScreenshotOverlayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return incomingScreenshotOverlayEnabled;
	}

	public static void setIncomingScreenshotOverlayEnabled(boolean enabled) {
		incomingScreenshotOverlayEnabled = enabled;
		saveConfig();
	}

	public static boolean isIncomingScreenshotTextShadow() {
		if (!loaded) {
			loadConfig();
		}
		return incomingScreenshotTextShadow;
	}

	public static void setIncomingScreenshotTextShadow(boolean shadow) {
		incomingScreenshotTextShadow = shadow;
		saveConfig();
	}

	public static int getIncomingScreenshotHudPosX() {
		if (!loaded) {
			loadConfig();
		}
		return incomingScreenshotHudPosX;
	}

	public static void setIncomingScreenshotHudPosX(int x) {
		incomingScreenshotHudPosX = x;
		saveConfig();
	}

	public static int getIncomingScreenshotHudPosY() {
		if (!loaded) {
			loadConfig();
		}
		return incomingScreenshotHudPosY;
	}

	public static void setIncomingScreenshotHudPosY(int y) {
		incomingScreenshotHudPosY = y;
		saveConfig();
	}

	public static float getIncomingScreenshotHudScale() {
		if (!loaded) {
			loadConfig();
		}
		return incomingScreenshotHudScale;
	}

	public static void setIncomingScreenshotHudScale(float scale) {
		incomingScreenshotHudScale = Math.max(0.35f, Math.min(2.5f, scale));
		saveConfig();
	}

	public static void resetIncomingScreenshotHud() {
		incomingScreenshotHudPosX = 722;
		incomingScreenshotHudPosY = 16;
		incomingScreenshotHudScale = 0.8373135f;
		saveConfig();
	}

    public static boolean isPtracersCagesEnabled() {
        if (!loaded) loadConfig();
        return ptracersCagesEnabled;
    }

    public static void setPtracersCagesEnabled(boolean enabled) {
        ptracersCagesEnabled = enabled;
        saveConfig();
    }

    public static boolean isCageChestOverlayEnabled() {
        if (!loaded) loadConfig();
        return cageChestOverlayEnabled;
    }

    public static void setCageChestOverlayEnabled(boolean enabled) {
        cageChestOverlayEnabled = enabled;
        saveConfig();
    }

    public static boolean isCageMiddleLootEstimateEnabled() {
        if (!loaded) loadConfig();
        if (GomodReleaseBuild.PUBLIC_RELEASE) {
            return false;
        }
        return cageMiddleLootEstimateEnabled;
    }

    public static void setCageMiddleLootEstimateEnabled(boolean enabled) {
        cageMiddleLootEstimateEnabled = enabled;
        saveConfig();
    }

    public static boolean isCageOpeningSoundEnabled() {
        if (!loaded) loadConfig();
        return cageOpeningSoundEnabled;
    }

    public static void setCageOpeningSoundEnabled(boolean enabled) {
        cageOpeningSoundEnabled = enabled;
        saveConfig();
    }

    public static boolean isGameTeleporterSoundEnabled() {
        if (!loaded) loadConfig();
        return gameTeleporterSoundEnabled;
    }

    public static void setGameTeleporterSoundEnabled(boolean enabled) {
        gameTeleporterSoundEnabled = enabled;
        saveConfig();
    }

    public static boolean isBlitzHourCageSfxEnabled() {
        if (!loaded) loadConfig();
        return blitzHourCageSfxEnabled;
    }

    public static void setBlitzHourCageSfxEnabled(boolean enabled) {
        blitzHourCageSfxEnabled = enabled;
        saveConfig();
    }

    public static boolean isPtracersTargetNametagEnabled() {
        if (!loaded) loadConfig();
        return ptracersTargetNametagEnabled;
    }

    public static void setPtracersTargetNametagEnabled(boolean enabled) {
        ptracersTargetNametagEnabled = enabled;
        saveConfig();
    }

    public static boolean isTabStatsRuntimeMuteEnabled() {
        if (!loaded) loadConfig();
        return tabStatsRuntimeMuteEnabled;
    }

    public static void setTabStatsRuntimeMuteEnabled(boolean enabled) {
        tabStatsRuntimeMuteEnabled = enabled;
        saveConfig();
    }

    public static boolean isWeaveDevTabStatsSeparated() {
        if (!loaded) loadConfig();
        return weaveDevTabStatsSeparated;
    }

    public static void setWeaveDevTabStatsSeparated(boolean separated) {
        if (!loaded) loadConfig();
        weaveDevTabStatsSeparated = separated;
        saveConfig();
    }

	/** Saves equipped kill effect for one local account UUID (lowercased, no dashes). */
	public static void setBlitzKillEffectForAccountUuid(String accountUuid, String effectCode) {
		if (!loaded) loadConfig();
		String key = normalizeAccountUuidKey(accountUuid);
		if (key.isEmpty()) {
			return;
		}
		String code = normalizePersistentKillEffectCode(effectCode);
		if (code == null) {
			blitzKillEffectByAccountUuid.remove(key);
		} else {
			blitzKillEffectByAccountUuid.put(key, code);
		}
		saveConfig();
	}

	/** @return persisted equipped kill effect code for this account UUID, or null when unknown. */
	public static String getBlitzKillEffectForAccountUuid(String accountUuid) {
		if (!loaded) loadConfig();
		String key = normalizeAccountUuidKey(accountUuid);
		if (key.isEmpty()) {
			return null;
		}
		return normalizePersistentKillEffectCode(blitzKillEffectByAccountUuid.get(key));
	}

	private static String normalizeAccountUuidKey(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
	}

	private static String normalizePersistentKillEffectCode(String raw) {
		if (raw == null) {
			return null;
		}
		String v = raw.trim().toUpperCase(Locale.ROOT);
		if (v.isEmpty() || "NONE".equals(v)) {
			return null;
		}
		if ("RAPID_FIRE".equals(v)) {
			return "FLAME";
		}
		if ("LEVELUP".equals(v)) {
			return "LEVEL_UP";
		}
		if ("FLAMING ARROWS".equals(v)) {
			return "FLAME";
		}
		switch (v) {
			case "SPEED":
			case "RESISTANCE":
			case "REGENERATION":
			case "FLAME":
			case "LEVEL_UP":
			case "GRAVEDIGGER":
			case "RANDOM":
				return v;
			default:
				return null;
		}
	}

	public static void setMobTargetDisplayEnabled(boolean enabled) {
		mobTargetDisplayEnabled = enabled;
		saveConfig();
	}

	public static boolean isDuelPredictorEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorEnabled;
	}

	public static void setDuelPredictorEnabled(boolean value) {
		duelPredictorEnabled = value;
		saveConfig();
	}

	public static boolean isDuelPredictorGhostEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorGhostEnabled;
	}

	public static void setDuelPredictorGhostEnabled(boolean value) {
		duelPredictorGhostEnabled = value;
		saveConfig();
	}

	public static boolean isDuelPredictorHudEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorHudEnabled;
	}

	public static void setDuelPredictorHudEnabled(boolean value) {
		duelPredictorHudEnabled = value;
		saveConfig();
	}

	public static int getDuelPredictorLeadTicks() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorLeadTicks;
	}

	public static void setDuelPredictorLeadTicks(int value) {
		duelPredictorLeadTicks = Math.max(1, Math.min(8, value));
		saveConfig();
	}

	public static int getDuelPredictorHudPosX() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorHudPosX;
	}

	public static int getDuelPredictorHudPosY() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorHudPosY;
	}

	public static float getDuelPredictorHudScale() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorHudScale;
	}

	public static int getDuelPredictorGhostColorArgb() {
		if (!loaded) {
			loadConfig();
		}
		return duelPredictorGhostColorArgb;
	}
	
	// Selfie getters and setters
	/**
	 * Gets whether Selfie mode is enabled.
	 * Note: Selfie mode is intentionally not persisted and always starts disabled.
	 * @return true if Selfie mode is enabled, false otherwise
	 */
	public static boolean isSelfieEnabled() {
		return selfieEnabled;
	}
	
	/**
	 * Sets whether Selfie mode is enabled.
	 * Note: This does NOT persist to config - selfie mode always starts disabled.
	 * @param enabled true to enable Selfie mode, false to disable
	 */
	public static void setSelfieEnabled(boolean enabled) {
		selfieEnabled = enabled;
		// Intentionally NOT calling saveConfig() - selfie mode should not persist
	}
	
	// FakeBreak getters and setters
	/**
	 * Checks if FakeBreak is enabled.
	 * @return true if FakeBreak is enabled, false otherwise
	 */
	public static boolean isFakeBreakEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return fakeBreakEnabled;
	}
	
	/**
	 * Sets whether FakeBreak is enabled.
	 * @param enabled true to enable FakeBreak, false to disable
	 */
	public static void setFakeBreakEnabled(boolean enabled) {
		fakeBreakEnabled = enabled;
		saveConfig();
	}
	
	/**
	 * Returns whether BestArmorDisplay is enabled.
	 * @return true if BestArmorDisplay is enabled, false otherwise
	 */
	public static boolean isBestArmorDisplayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return bestArmorDisplayEnabled;
	}
	
	/**
	 * Sets whether BestArmorDisplay is enabled.
	 * @param enabled true to enable BestArmorDisplay, false to disable
	 */
	public static void setBestArmorDisplayEnabled(boolean enabled) {
		bestArmorDisplayEnabled = enabled;
		saveConfig();
	}

	public static int getBestArmorPercentageMode() {
		if (!loaded) {
			loadConfig();
		}
		return bestArmorPercentageMode;
	}

	public static void setBestArmorPercentageMode(int mode) {
		if (mode >= BEST_ARMOR_PERCENTAGE_OFF && mode <= BEST_ARMOR_PERCENTAGE_DETAILED) {
			bestArmorPercentageMode = mode;
		} else {
			bestArmorPercentageMode = BEST_ARMOR_PERCENTAGE_MINIMAL;
		}
		saveConfig();
	}

	public static int getBestArmorContainerRenderStrategy() {
		if (!loaded) {
			loadConfig();
		}
		return bestArmorContainerRenderStrategy;
	}

	public static void setBestArmorContainerRenderStrategy(int mode) {
		bestArmorContainerRenderStrategy = (mode >= 1 && mode <= 8) ? mode : 6;
		saveConfig();
	}

	public static boolean isBestArmorDebugOverlayPaint() {
		if (!loaded) {
			loadConfig();
		}
		return bestArmorDebugOverlayPaint;
	}

	public static void setBestArmorDebugOverlayPaint(boolean on) {
		bestArmorDebugOverlayPaint = on;
		saveConfig();
	}

	public static boolean isUselessItemOverlayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return uselessItemOverlayEnabled;
	}

	public static void setUselessItemOverlayEnabled(boolean on) {
		uselessItemOverlayEnabled = on;
		saveConfig();
	}

	public static void setUselessItemOverlayEnabledNoSave(boolean on) {
		uselessItemOverlayEnabled = on;
	}

	public static boolean isUselessItemOverlayForceEverywhere() {
		if (!loaded) {
			loadConfig();
		}
		return uselessItemOverlayForceEverywhere;
	}

	public static void setUselessItemOverlayForceEverywhere(boolean on) {
		uselessItemOverlayForceEverywhere = on;
		saveConfig();
	}

	public static boolean isUselessItemOverlayChestPickHintsEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return uselessItemOverlayChestPickHintsEnabled;
	}

	public static void setUselessItemOverlayChestPickHintsEnabled(boolean on) {
		uselessItemOverlayChestPickHintsEnabled = on;
		saveConfig();
	}

	private static String expandLoadedChestHintHex(String raw, String fallback, int alphaForSixDigitRgb) {
		if (raw == null) {
			return fallback;
		}
		String s = raw.replace("#", "").trim().toUpperCase(Locale.ROOT);
		if (s.length() == 8 && s.matches("[0-9A-F]{8}")) {
			return s;
		}
		if (s.length() == 6 && s.matches("[0-9A-F]{6}")) {
			try {
				int rgb = Integer.parseUnsignedInt(s, 16);
				int aa = (alphaForSixDigitRgb & 0xFF) << 24;
				return argbToChestHintHex8(aa | (rgb & 0xFFFFFF));
			} catch (Throwable ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	public static int getUselessChestHintBlueFillArgb() {
		if (!loaded) {
			loadConfig();
		}
		return chestHintHexToArgb(uselessChestHintBlueFillHex, 0x404488FF);
	}

	public static int getUselessChestHintBlueEdgeArgb() {
		if (!loaded) {
			loadConfig();
		}
		return chestHintHexToArgb(uselessChestHintBlueEdgeHex, 0xD066AAFF);
	}

	public static int getUselessChestHintYellowFillArgb() {
		if (!loaded) {
			loadConfig();
		}
		return chestHintHexToArgb(uselessChestHintYellowFillHex, 0x40FFCC44);
	}

	public static int getUselessChestHintYellowEdgeArgb() {
		if (!loaded) {
			loadConfig();
		}
		return chestHintHexToArgb(uselessChestHintYellowEdgeHex, 0xD0FFDD66);
	}

	private static int chestHintHexToArgb(String hex, int defaultArgb) {
		if (hex == null) {
			return defaultArgb;
		}
		String s = hex.replace("#", "").trim();
		try {
			if (s.length() == 8) {
				return (int) Long.parseLong(s, 16);
			}
			if (s.length() == 6) {
				int rgb = Integer.parseUnsignedInt(s, 16);
				return (0x40 << 24) | (rgb & 0xFFFFFF);
			}
		} catch (Throwable ignored) {
		}
		return defaultArgb;
	}

	private static String argbToChestHintHex8(int argb) {
		return String.format(Locale.ROOT, "%08X", argb & 0xFFFFFFFFL);
	}

	public static boolean setUselessChestHintBlueFillFromUserHex(String userHex) {
		Integer v = parseUserChestHintColor(userHex, 0x40);
		if (v == null) {
			return false;
		}
		uselessChestHintBlueFillHex = argbToChestHintHex8(v);
		saveConfig();
		return true;
	}

	public static boolean setUselessChestHintBlueEdgeFromUserHex(String userHex) {
		Integer v = parseUserChestHintColor(userHex, 0xD0);
		if (v == null) {
			return false;
		}
		uselessChestHintBlueEdgeHex = argbToChestHintHex8(v);
		saveConfig();
		return true;
	}

	public static boolean setUselessChestHintYellowFillFromUserHex(String userHex) {
		Integer v = parseUserChestHintColor(userHex, 0x40);
		if (v == null) {
			return false;
		}
		uselessChestHintYellowFillHex = argbToChestHintHex8(v);
		saveConfig();
		return true;
	}

	public static boolean setUselessChestHintYellowEdgeFromUserHex(String userHex) {
		Integer v = parseUserChestHintColor(userHex, 0xD0);
		if (v == null) {
			return false;
		}
		uselessChestHintYellowEdgeHex = argbToChestHintHex8(v);
		saveConfig();
		return true;
	}

	/**
	 * @param defaultAlphaSix default AA when user passes 6-digit RRGGBB (fill 0x40, edge 0xD0)
	 */
	private static Integer parseUserChestHintColor(String raw, int defaultAlphaSix) {
		if (raw == null) {
			return null;
		}
		String s = raw.replace("#", "").trim().toUpperCase(Locale.ROOT);
		if (!s.matches("[0-9A-F]{6}|[0-9A-F]{8}")) {
			return null;
		}
		try {
			if (s.length() == 8) {
				return (int) Long.parseLong(s, 16);
			}
			int rgb = Integer.parseUnsignedInt(s, 16);
			int aa = (defaultAlphaSix & 0xFF) << 24;
			return aa | (rgb & 0xFFFFFF);
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static void resetUselessChestHintColorsToDefault() {
		uselessChestHintBlueFillHex = "404488FF";
		uselessChestHintBlueEdgeHex = "D066AAFF";
		uselessChestHintYellowFillHex = "40FFCC44";
		uselessChestHintYellowEdgeHex = "D0FFDD66";
		saveConfig();
	}

	public static String getUselessChestHintBlueFillHexForDisplay() {
		if (!loaded) {
			loadConfig();
		}
		return uselessChestHintBlueFillHex;
	}

	public static String getUselessChestHintBlueEdgeHexForDisplay() {
		if (!loaded) {
			loadConfig();
		}
		return uselessChestHintBlueEdgeHex;
	}

	public static String getUselessChestHintYellowFillHexForDisplay() {
		if (!loaded) {
			loadConfig();
		}
		return uselessChestHintYellowFillHex;
	}

	public static String getUselessChestHintYellowEdgeHexForDisplay() {
		if (!loaded) {
			loadConfig();
		}
		return uselessChestHintYellowEdgeHex;
	}

    public static boolean isUselessAlwaysRedBowl() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedBowl;
    }

    public static void setUselessAlwaysRedBowl(boolean on) {
        uselessAlwaysRedBowl = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedCake() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedCake;
    }

    public static void setUselessAlwaysRedCake(boolean on) {
        uselessAlwaysRedCake = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedChestMinecart() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedChestMinecart;
    }

    public static void setUselessAlwaysRedChestMinecart(boolean on) {
        uselessAlwaysRedChestMinecart = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedMinecart() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedMinecart;
    }

    public static void setUselessAlwaysRedMinecart(boolean on) {
        uselessAlwaysRedMinecart = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedRail() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedRail;
    }

    public static void setUselessAlwaysRedRail(boolean on) {
        uselessAlwaysRedRail = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedRose() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedRose;
    }

    public static void setUselessAlwaysRedRose(boolean on) {
        uselessAlwaysRedRose = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedWheat() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedWheat;
    }

    public static void setUselessAlwaysRedWheat(boolean on) {
        uselessAlwaysRedWheat = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedSharpnessFish() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedSharpnessFish;
    }

    public static void setUselessAlwaysRedSharpnessFish(boolean on) {
        uselessAlwaysRedSharpnessFish = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedApple() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedApple;
    }

    public static void setUselessAlwaysRedApple(boolean on) {
        uselessAlwaysRedApple = on;
        saveConfig();
    }

    public static boolean isUselessAlwaysRedStick() {
        if (!loaded) loadConfig();
        return uselessAlwaysRedStick;
    }

    public static void setUselessAlwaysRedStick(boolean on) {
        uselessAlwaysRedStick = on;
        saveConfig();
    }

    public static String getUselessMeleeIronVsStoneSharpRed() {
        if (!loaded) loadConfig();
        return normalizeUselessMeleeIronStone(uselessMeleeIronVsStoneSharpRed);
    }

    public static void setUselessMeleeIronVsStoneSharpRed(String v) {
        uselessMeleeIronVsStoneSharpRed = normalizeUselessMeleeIronStone(v);
        saveConfig();
    }

    public static String getUselessMeleeDiamondVsIronSharpRed() {
        if (!loaded) loadConfig();
        return normalizeUselessMeleeDiamondIron(uselessMeleeDiamondVsIronSharpRed);
    }

    public static void setUselessMeleeDiamondVsIronSharpRed(String v) {
        uselessMeleeDiamondVsIronSharpRed = normalizeUselessMeleeDiamondIron(v);
        saveConfig();
    }

    public static String getUselessMeleeStoneVsGoldWoodSharpRed() {
        if (!loaded) loadConfig();
        return normalizeUselessMeleeStoneGoldWood(uselessMeleeStoneVsGoldWoodSharpRed);
    }

    public static void setUselessMeleeStoneVsGoldWoodSharpRed(String v) {
        uselessMeleeStoneVsGoldWoodSharpRed = normalizeUselessMeleeStoneGoldWood(v);
        saveConfig();
    }

    private static String normalizeUselessMeleeIronStone(String v) {
        if (v == null) return "STONE";
        return "IRON".equalsIgnoreCase(v.trim()) ? "IRON" : "STONE";
    }

    private static String normalizeUselessMeleeDiamondIron(String v) {
        if (v == null) return "IRON";
        return "DIAMOND".equalsIgnoreCase(v.trim()) ? "DIAMOND" : "IRON";
    }

    private static String normalizeUselessMeleeStoneGoldWood(String v) {
        if (v == null) return "GOLD_WOOD";
        return "STONE".equalsIgnoreCase(v.trim()) ? "STONE" : "GOLD_WOOD";
    }

	public static boolean isWitchSoundsEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return witchSoundsEnabled;
	}

	public static void setWitchSoundsEnabled(boolean enabled) {
		witchSoundsEnabled = enabled;
		saveConfig();
	}

	public static boolean isMainMenuQueueButtonsEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return mainMenuQueueButtonsEnabled;
	}

	public static void setMainMenuQueueButtonsEnabled(boolean enabled) {
		mainMenuQueueButtonsEnabled = enabled;
		saveConfig();
	}

	public static boolean isTntPlacementPreviewEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return tntPlacementPreviewEnabled;
	}

	public static void setTntPlacementPreviewEnabled(boolean enabled) {
		tntPlacementPreviewEnabled = enabled;
		saveConfig();
	}

	public static boolean isChickenPlacementPreviewEnabled() {
		if (!loaded) loadConfig();
		return chickenPlacementPreviewEnabled;
	}

	public static void setChickenPlacementPreviewEnabled(boolean enabled) {
		chickenPlacementPreviewEnabled = enabled;
		saveConfig();
	}

	public static boolean isPigPlacementPreviewEnabled() {
		if (!loaded) loadConfig();
		return pigPlacementPreviewEnabled;
	}

	public static void setPigPlacementPreviewEnabled(boolean enabled) {
		pigPlacementPreviewEnabled = enabled;
		saveConfig();
	}

	public static boolean isHorsePlacementPreviewEnabled() {
		if (!loaded) loadConfig();
		return horsePlacementPreviewEnabled;
	}

	public static void setHorsePlacementPreviewEnabled(boolean enabled) {
		horsePlacementPreviewEnabled = enabled;
		saveConfig();
	}

	public static boolean isDonkeyPlacementPreviewEnabled() {
		if (!loaded) loadConfig();
		return donkeyPlacementPreviewEnabled;
	}

	public static void setDonkeyPlacementPreviewEnabled(boolean enabled) {
		donkeyPlacementPreviewEnabled = enabled;
		saveConfig();
	}

	public static boolean isGomodBlockOverlayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayEnabled;
	}

	public static void setGomodBlockOverlayEnabled(boolean enabled) {
		gomodBlockOverlayEnabled = enabled;
		saveConfig();
	}

	public static String getGomodBlockOverlayMode() {
		if (!loaded) {
			loadConfig();
		}
		return normalizeGomodBlockOverlayMode(gomodBlockOverlayMode);
	}

	public static void setGomodBlockOverlayMode(String mode) {
		gomodBlockOverlayMode = normalizeGomodBlockOverlayMode(mode);
		saveConfig();
	}

	private static String normalizeGomodBlockOverlayMode(String mode) {
		if (mode == null) {
			return "SIDE";
		}
		String upper = mode.trim().toUpperCase();
		if ("FULL".equals(upper)) {
			return "FULL";
		}
		return "SIDE";
	}

	public static int getGomodBlockOverlayColorArgb() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayColorArgb;
	}

	public static void setGomodBlockOverlayColorArgb(int argb) {
		gomodBlockOverlayColorArgb = argb | 0xFF000000;
		saveConfig();
	}

	public static float getGomodBlockOverlayLineWidth() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayLineWidth;
	}

	public static void setGomodBlockOverlayLineWidth(float width) {
		gomodBlockOverlayLineWidth = Math.max(1.0f, Math.min(15.0f, width));
		saveConfig();
	}

	public static String getGomodBlockOverlayLinePlacement() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayLinePlacement;
	}

	public static void setGomodBlockOverlayLinePlacement(String placement) {
		gomodBlockOverlayLinePlacement = placement == null ? "OUTSIDE" : placement;
		saveConfig();
	}

	public static boolean isGomodBlockOverlayFillEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayFillEnabled;
	}

	public static void setGomodBlockOverlayFillEnabled(boolean enabled) {
		gomodBlockOverlayFillEnabled = enabled;
		saveConfig();
	}

	public static float getGomodBlockOverlayFillOpacity() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayFillOpacity;
	}

	public static void setGomodBlockOverlayFillOpacity(float opacity) {
		gomodBlockOverlayFillOpacity = Math.max(0.0f, Math.min(1.0f, opacity));
		saveConfig();
	}

	public static boolean isGomodBlockOverlaySideShapeAware() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlaySideShapeAware;
	}

	public static void setGomodBlockOverlaySideShapeAware(boolean enabled) {
		gomodBlockOverlaySideShapeAware = enabled;
		saveConfig();
	}

	public static boolean isGomodBlockOverlayHideBarriersInCages() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayHideBarriersInCages;
	}

	public static void setGomodBlockOverlayHideBarriersInCages(boolean hide) {
		gomodBlockOverlayHideBarriersInCages = hide;
		saveConfig();
	}

	public static boolean isGomodBlockOverlayPlayerOverlayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return gomodBlockOverlayPlayerOverlayEnabled;
	}

	public static void setGomodBlockOverlayPlayerOverlayEnabled(boolean enabled) {
		gomodBlockOverlayPlayerOverlayEnabled = enabled;
		saveConfig();
	}

	public static boolean isLightningNerfEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return lightningNerfEnabled;
	}

	public static void setLightningNerfEnabled(boolean enabled) {
		lightningNerfEnabled = enabled;
		saveConfig();
	}

	public static boolean isLightningFixRenderEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return lightningFixRenderEnabled;
	}

	public static void setLightningFixRenderEnabled(boolean enabled) {
		lightningFixRenderEnabled = enabled;
		saveConfig();
	}

	public static int getLightningNerfBottomBlocks() {
		if (!loaded) {
			loadConfig();
		}
		return lightningNerfBottomBlocks;
	}

	public static void setLightningNerfBottomBlocks(int blocks) {
		lightningNerfBottomBlocks = Math.max(1, Math.min(64, blocks));
		saveConfig();
	}

	public static boolean isLightningCustomColorEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return lightningCustomColorEnabled;
	}

	public static void setLightningCustomColorEnabled(boolean enabled) {
		lightningCustomColorEnabled = enabled;
		saveConfig();
	}

	public static boolean isLightningRandomColorEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return lightningRandomColorEnabled;
	}

	public static void setLightningRandomColorEnabled(boolean enabled) {
		lightningRandomColorEnabled = enabled;
		saveConfig();
	}

	public static int getLightningBoltColorArgb() {
		if (!loaded) {
			loadConfig();
		}
		return lightningBoltColorArgb;
	}

	public static void setLightningBoltColorArgb(int argb) {
		lightningBoltColorArgb = 0xFF000000 | (argb & 0xFFFFFF);
		lightningCustomColorEnabled = true;
		saveConfig();
	}

	public static float getLightningOpacityPercent() {
		if (!loaded) {
			loadConfig();
		}
		return lightningOpacityPercent;
	}

	public static void setLightningOpacityPercent(float percent) {
		lightningOpacityPercent = clampLightningVolume(percent);
		saveConfig();
	}

	public static float getLightningSpeedPercent() {
		if (!loaded) {
			loadConfig();
		}
		return lightningSpeedPercent;
	}

	public static void setLightningSpeedPercent(float percent) {
		lightningSpeedPercent = clampLightningSpeed(percent);
		saveConfig();
	}

	public static float getLightningSoundVolumePercent() {
		if (!loaded) {
			loadConfig();
		}
		return lightningSoundVolumePercent;
	}

	public static void setLightningSoundVolumePercent(float percent) {
		lightningSoundVolumePercent = clampLightningVolume(percent);
		saveConfig();
	}

	private static float clampLightningSpeed(float percent) {
		return Math.max(25.0f, Math.min(300.0f, percent));
	}

	private static float clampLightningVolume(float percent) {
		return Math.max(0.0f, Math.min(100.0f, percent));
	}

	private static int clampLightningPostCageDurationSec(int seconds) {
		return Math.max(1, Math.min(60, seconds));
	}

	public static boolean isLightningPostCageSoundEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return lightningPostCageSoundEnabled;
	}

	public static void setLightningPostCageSoundEnabled(boolean enabled) {
		lightningPostCageSoundEnabled = enabled;
		saveConfig();
	}

	public static int getLightningPostCageSoundDurationSec() {
		if (!loaded) {
			loadConfig();
		}
		return lightningPostCageSoundDurationSec;
	}

	public static void setLightningPostCageSoundDurationSec(int seconds) {
		lightningPostCageSoundDurationSec = clampLightningPostCageDurationSec(seconds);
		saveConfig();
	}

	public static float getLightningPostCageSpeedPercent() {
		if (!loaded) {
			loadConfig();
		}
		return lightningPostCageSpeedPercent;
	}

	public static void setLightningPostCageSpeedPercent(float percent) {
		lightningPostCageSpeedPercent = clampLightningSpeed(percent);
		saveConfig();
	}

	public static float getLightningPostCageVolumePercent() {
		if (!loaded) {
			loadConfig();
		}
		return lightningPostCageVolumePercent;
	}

	public static void setLightningPostCageVolumePercent(float percent) {
		lightningPostCageVolumePercent = clampLightningVolume(percent);
		saveConfig();
	}

	public static void resetLightningPostCageSettingsToDefaults() {
		lightningPostCageSoundEnabled = false;
		lightningPostCageSoundDurationSec = 30;
		lightningPostCageSpeedPercent = 100.0f;
		lightningPostCageVolumePercent = 45.0f;
		saveConfig();
	}

	public static void resetLightningSettingsToDefaults() {
		lightningNerfEnabled = true;
		lightningFixRenderEnabled = true;
		lightningNerfBottomBlocks = 2;
		lightningCustomColorEnabled = false;
		lightningRandomColorEnabled = false;
		lightningBoltColorArgb = 0xFFE6E6FF;
		lightningOpacityPercent = 100.0f;
		lightningSpeedPercent = 100.0f;
		lightningSoundVolumePercent = 100.0f;
		lightningPostCageSoundEnabled = false;
		lightningPostCageSoundDurationSec = 30;
		lightningPostCageSpeedPercent = 100.0f;
		lightningPostCageVolumePercent = 45.0f;
		saveConfig();
	}

	private static float clampWitchVol(float v) {
		return Math.max(0.0f, Math.min(1.0f, v));
	}

	public static float getWitchSfxVolIdle() {
		if (!loaded) loadConfig();
		return witchSfxVolIdle;
	}
	public static void setWitchSfxVolIdle(float v) {
		witchSfxVolIdle = clampWitchVol(v);
		saveConfig();
	}
	public static float getWitchSfxVolHurt() {
		if (!loaded) loadConfig();
		return witchSfxVolHurt;
	}
	public static void setWitchSfxVolHurt(float v) {
		witchSfxVolHurt = clampWitchVol(v);
		saveConfig();
	}
	public static float getWitchSfxVolDeath() {
		if (!loaded) loadConfig();
		return witchSfxVolDeath;
	}
	public static void setWitchSfxVolDeath(float v) {
		witchSfxVolDeath = clampWitchVol(v);
		saveConfig();
	}
	public static float getWitchSfxVolThrow() {
		if (!loaded) loadConfig();
		return witchSfxVolThrow;
	}
	public static void setWitchSfxVolThrow(float v) {
		witchSfxVolThrow = clampWitchVol(v);
		saveConfig();
	}
	public static float getWitchSfxVolDrink() {
		if (!loaded) loadConfig();
		return witchSfxVolDrink;
	}
	public static void setWitchSfxVolDrink(float v) {
		witchSfxVolDrink = clampWitchVol(v);
		saveConfig();
	}
	public static float getWitchSfxVolCelebrate() {
		if (!loaded) loadConfig();
		return witchSfxVolCelebrate;
	}
	public static void setWitchSfxVolCelebrate(float v) {
		witchSfxVolCelebrate = clampWitchVol(v);
		saveConfig();
	}

	/** Volume multiplier for bundled/vanilla witch.kind playback (idle groups idle1–4). */
	public static float getWitchSfxVolumeMultiplier(String kind) {
		if (!loaded) loadConfig();
		if (kind == null) return 1.0f;
		switch (kind) {
			case "hurt":
				return witchSfxVolHurt;
			case "death":
				return witchSfxVolDeath;
			case "throw":
				return witchSfxVolThrow;
			case "drink":
				return witchSfxVolDrink;
			case "celebrate":
				return witchSfxVolCelebrate;
			case "idle":
			default:
				return witchSfxVolIdle;
		}
	}

	public static boolean isForceResistanceParticlesEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return forceResistanceParticlesEnabled;
	}

	public static void setForceResistanceParticlesEnabled(boolean enabled) {
		forceResistanceParticlesEnabled = enabled;
		saveConfig();
	}

	public static int getForceResistanceSpawnsPerTick() {
		if (!loaded) {
			loadConfig();
		}
		return forceResistanceSpawnsPerTick;
	}

	public static void setForceResistanceSpawnsPerTick(int n) {
		forceResistanceSpawnsPerTick = Math.max(0, Math.min(12, n));
		saveConfig();
	}

	public static float getForceResistanceOpacityScale() {
		if (!loaded) {
			loadConfig();
		}
		return forceResistanceOpacityScale;
	}

	public static void setForceResistanceOpacityScale(float v) {
		forceResistanceOpacityScale = Math.max(0.0f, Math.min(1.5f, v));
		saveConfig();
	}

	public static float getForceResistanceSaturation() {
		if (!loaded) {
			loadConfig();
		}
		return forceResistanceSaturation;
	}

	public static void setForceResistanceSaturation(float v) {
		forceResistanceSaturation = Math.max(0.25f, Math.min(2.5f, v));
		saveConfig();
	}

	// RadialMenu getters and setters
	public static boolean isRadialMenuEnabled() {
		if (!loaded) loadConfig();
		return radialMenuEnabled;
	}
	public static void setRadialMenuEnabled(boolean enabled) {
		radialMenuEnabled = enabled;
		saveConfig();
	}
	public static int getRadialMenuKeyCode() {
		if (!loaded) loadConfig();
		return radialMenuKeyCode;
	}
	public static void setRadialMenuKeyCode(int code) {
		radialMenuKeyCode = code;
		saveConfig();
	}

	public static int getChatMovementKeyCode() {
		if (!loaded) loadConfig();
		return chatMovementKeyCode;
	}

	public static void setChatMovementKeyCode(int code) {
		chatMovementKeyCode = code;
		saveConfig();
	}

	public static boolean isChatMovementDragLookEnabled() {
		if (!loaded) loadConfig();
		return chatMovementDragLookEnabled;
	}

	public static void setChatMovementDragLookEnabled(boolean enabled) {
		chatMovementDragLookEnabled = enabled;
		saveConfig();
	}

	public static int getChatMovementPassthroughKeyCode() {
		if (!loaded) loadConfig();
		return chatMovementPassthroughKeyCode;
	}

	public static void setChatMovementPassthroughKeyCode(int code) {
		chatMovementPassthroughKeyCode = code;
		saveConfig();
	}

	private static String normalizeTeamNickFinderZoneId(String raw) {
		if (raw == null) {
			return "UTC";
		}
		String t = raw.trim();
		if (t.isEmpty()) {
			return "UTC";
		}
		if (t.length() > 64) {
			t = t.substring(0, 64);
		}
		if ("SYSTEM".equalsIgnoreCase(t)) {
			return "SYSTEM";
		}
		if ("UTC".equalsIgnoreCase(t)) {
			return "UTC";
		}
		// Fixed-offset presets cycled in TeamSpamDateSettingsGUI: UTC+1 … UTC+12
		if (t.length() >= 5 && t.regionMatches(true, 0, "UTC+", 0, 4)) {
			try {
				int h = Integer.parseInt(t.substring(4).trim());
				if (h == 0) {
					return "UTC";
				}
				if (h >= 1 && h <= 12) {
					return "UTC+" + h;
				}
			} catch (NumberFormatException ignored) {
			}
			return "UTC";
		}
		try {
			ZoneId.of(t);
			return t;
		} catch (DateTimeException e) {
			return "UTC";
		}
	}

	public static boolean isTeamNickFinderLastFoundRelative() {
		if (!loaded) {
			loadConfig();
		}
		return teamNickFinderLastFoundRelative;
	}

	public static void setTeamNickFinderLastFoundRelative(boolean v) {
		teamNickFinderLastFoundRelative = v;
		saveConfig();
	}

	public static boolean isTeamNickFinder24HourClock() {
		if (!loaded) {
			loadConfig();
		}
		return teamNickFinder24HourClock;
	}

	public static void setTeamNickFinder24HourClock(boolean v) {
		teamNickFinder24HourClock = v;
		saveConfig();
	}

	public static String getTeamNickFinderZoneId() {
		if (!loaded) {
			loadConfig();
		}
		return teamNickFinderZoneId;
	}

	public static void setTeamNickFinderZoneId(String zoneId) {
		teamNickFinderZoneId = normalizeTeamNickFinderZoneId(zoneId);
		saveConfig();
	}

	public static int getGomodFriendsListKeyCode() {
		if (!loaded) {
			loadConfig();
		}
		return gomodFriendsListKeyCode;
	}

	public static void setGomodFriendsListKeyCode(int code) {
		gomodFriendsListKeyCode = code;
		saveConfig();
	}

	public static int getGomodFriendsListSecondKeyCode() {
		if (!loaded) {
			loadConfig();
		}
		return gomodFriendsListSecondKeyCode;
	}

	public static void setGomodFriendsListKeyCodes(int first, int second) {
		gomodFriendsListKeyCode = first;
		gomodFriendsListSecondKeyCode = first == 0 || second == first ? 0 : second;
		gomodSocialRequireCtrl = gomodFriendsListSecondKeyCode == org.lwjgl.input.Keyboard.KEY_LCONTROL
				|| gomodFriendsListSecondKeyCode == org.lwjgl.input.Keyboard.KEY_RCONTROL;
		saveConfig();
	}

	public static boolean isGomodSocialRequireCtrl() {
		if (!loaded) {
			loadConfig();
		}
		return gomodFriendsListSecondKeyCode == org.lwjgl.input.Keyboard.KEY_LCONTROL
				|| gomodFriendsListSecondKeyCode == org.lwjgl.input.Keyboard.KEY_RCONTROL;
	}

	public static void setGomodSocialRequireCtrl(boolean require) {
		gomodSocialRequireCtrl = require;
		if (require) {
			gomodFriendsListSecondKeyCode = org.lwjgl.input.Keyboard.KEY_LCONTROL;
		} else if (gomodFriendsListSecondKeyCode == org.lwjgl.input.Keyboard.KEY_LCONTROL
				|| gomodFriendsListSecondKeyCode == org.lwjgl.input.Keyboard.KEY_RCONTROL) {
			gomodFriendsListSecondKeyCode = 0;
		}
		saveConfig();
	}

	public static boolean isBarrierVisibilityEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return barrierVisibilityEnabled;
	}

	public static void setBarrierVisibilityEnabled(boolean enabled) {
		barrierVisibilityEnabled = enabled;
		saveConfig();
	}

	public static int getBarrierVisibilityKeyCode() {
		if (!loaded) {
			loadConfig();
		}
		return barrierVisibilityKeyCode;
	}

	public static void setBarrierVisibilityKeyCode(int code) {
		barrierVisibilityKeyCode = code;
		saveConfig();
	}

	public static String getBarrierVisibilityStyle() {
		if (!loaded) {
			loadConfig();
		}
		return barrierVisibilityStyle;
	}

	public static void setBarrierVisibilityStyle(String style) {
		barrierVisibilityStyle = BarrierVisibilityStyles.normalize(style);
		saveConfig();
	}

	public static int getBarrierOutlineThickness() {
		if (!loaded) {
			loadConfig();
		}
		return BarrierVisibilityStyles.clampThickness(barrierOutlineThickness);
	}

	public static void setBarrierOutlineThickness(int thickness) {
		barrierOutlineThickness = BarrierVisibilityStyles.clampThickness(thickness);
		saveConfig();
	}

	public static int getBarrierOutlineColorArgb() {
		if (!loaded) {
			loadConfig();
		}
		int v = barrierOutlineColorArgb;
		// Treat fully transparent saved values as the default so a corrupt config can't make the
		// outline silently invisible.
		if (((v >>> 24) & 0xFF) == 0) {
			return BarrierVisibilityStyles.DEFAULT_OUTLINE_COLOR_ARGB;
		}
		return v;
	}

	public static void setBarrierOutlineColorArgb(int argb) {
		// Force the alpha byte to 0xFF — partially-transparent outlines would just disappear under
		// cutout rendering, so we always store an opaque color and rely on the texture's transparent
		// interior for the see-through effect.
		barrierOutlineColorArgb = 0xFF000000 | (argb & 0x00FFFFFF);
		saveConfig();
	}

	public static boolean isBarrierVisibilityRangeEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return barrierVisibilityRangeEnabled;
	}

	public static void setBarrierVisibilityRangeEnabled(boolean enabled) {
		barrierVisibilityRangeEnabled = enabled;
		saveConfig();
	}

	public static int getBarrierVisibilityRange() {
		if (!loaded) {
			loadConfig();
		}
		return clampBarrierRange(barrierVisibilityRange);
	}

	public static void setBarrierVisibilityRange(int range) {
		barrierVisibilityRange = clampBarrierRange(range);
		saveConfig();
	}

	public static boolean isBarrierVisibilityShowAtCursor() {
		if (!loaded) {
			loadConfig();
		}
		return barrierVisibilityShowAtCursor;
	}

	public static void setBarrierVisibilityShowAtCursor(boolean enabled) {
		barrierVisibilityShowAtCursor = enabled;
		saveConfig();
	}

	public static boolean isBarrierVisibilityAimedOnly() {
		if (!loaded) {
			loadConfig();
		}
		return barrierVisibilityAimedOnly;
	}

	public static void setBarrierVisibilityAimedOnly(boolean enabled) {
		barrierVisibilityAimedOnly = enabled;
		saveConfig();
	}

	public static boolean isBarrierBlocksShownInCages() {
		if (!loaded) {
			loadConfig();
		}
		return barrierBlocksShownInCages;
	}

	public static void setBarrierBlocksShownInCages(boolean shown) {
		barrierBlocksShownInCages = shown;
		saveConfig();
	}

	// ── Blocks Finder getters / setters ──────────────────────────────────────

	public static boolean isBlocksFinderEnabled() {
		if (!loaded) loadConfig();
		return blocksFinderEnabled;
	}

	public static void setBlocksFinderEnabled(boolean v) {
		blocksFinderEnabled = v;
		saveConfig();
	}

	public static int getBlocksFinderKeyCode() {
		if (!loaded) loadConfig();
		return blocksFinderKeyCode;
	}

	public static void setBlocksFinderKeyCode(int code) {
		blocksFinderKeyCode = code;
		saveConfig();
	}

	public static String getBlocksFinderEspMode() {
		if (!loaded) loadConfig();
		return blocksFinderEspMode == null ? "OUTLINE_FILLED" : blocksFinderEspMode;
	}

	public static void setBlocksFinderEspMode(String mode) {
		blocksFinderEspMode = mode == null ? "OUTLINE_FILLED" : mode;
		saveConfig();
	}

	public static int getBlocksFinderColorR() {
		if (!loaded) loadConfig();
		return blocksFinderColorR;
	}

	public static void setBlocksFinderColorR(int v) {
		blocksFinderColorR = Math.max(0, Math.min(255, v));
		saveConfig();
	}

	public static int getBlocksFinderColorG() {
		if (!loaded) loadConfig();
		return blocksFinderColorG;
	}

	public static void setBlocksFinderColorG(int v) {
		blocksFinderColorG = Math.max(0, Math.min(255, v));
		saveConfig();
	}

	public static int getBlocksFinderColorB() {
		if (!loaded) loadConfig();
		return blocksFinderColorB;
	}

	public static void setBlocksFinderColorB(int v) {
		blocksFinderColorB = Math.max(0, Math.min(255, v));
		saveConfig();
	}

	public static int getBlocksFinderFillAlpha() {
		if (!loaded) loadConfig();
		return blocksFinderFillAlpha;
	}

	public static void setBlocksFinderFillAlpha(int v) {
		blocksFinderFillAlpha = Math.max(me.napim.gomod.features.BlocksFinder.FILL_ALPHA_MIN,
				Math.min(me.napim.gomod.features.BlocksFinder.FILL_ALPHA_MAX, v));
		saveConfig();
	}

	public static int getBlocksFinderWorldOpacity() {
		if (!loaded) loadConfig();
		return Math.max(0, Math.min(100, blocksFinderWorldOpacity));
	}

	public static void setBlocksFinderWorldOpacity(int v) {
		blocksFinderWorldOpacity = Math.max(0, Math.min(100, v));
		saveConfig();
	}

	public static int getBlocksFinderWorldOpacityKeyCode() {
		if (!loaded) loadConfig();
		return blocksFinderWorldOpacityKeyCode;
	}

	public static void setBlocksFinderWorldOpacityKeyCode(int code) {
		blocksFinderWorldOpacityKeyCode = code;
		saveConfig();
	}

	public static int getBlocksFinderDefaultColorRgb() {
		if (!loaded) loadConfig();
		return ((blocksFinderColorR & 0xFF) << 16)
				| ((blocksFinderColorG & 0xFF) << 8)
				| (blocksFinderColorB & 0xFF);
	}

	public static int getBlocksFinderBlockColorRgb(String blockId) {
		if (!loaded) loadConfig();
		if (blockId == null) {
			return getBlocksFinderDefaultColorRgb();
		}
		Integer custom = blocksFinderBlockColors.get(blockId);
		if (custom != null) {
			return custom & 0x00FFFFFF;
		}
		Integer builtin = BLOCKS_FINDER_BUILTIN_BLOCK_COLORS.get(blockId);
		if (builtin != null) {
			return builtin & 0x00FFFFFF;
		}
		return getBlocksFinderDefaultColorRgb();
	}

	public static boolean hasBlocksFinderBuiltinBlockColor(String blockId) {
		return blockId != null && BLOCKS_FINDER_BUILTIN_BLOCK_COLORS.containsKey(blockId);
	}

	public static boolean hasBlocksFinderBlockColorOverride(String blockId) {
		if (!loaded) loadConfig();
		return blockId != null && blocksFinderBlockColors.containsKey(blockId);
	}

	public static void setBlocksFinderBlockColor(String blockId, int rgb) {
		if (blockId == null || blockId.trim().isEmpty()) {
			return;
		}
		blocksFinderBlockColors.put(blockId, rgb & 0x00FFFFFF);
		saveConfig();
	}

	public static void resetBlocksFinderBlockColor(String blockId) {
		if (blockId == null) {
			return;
		}
		if (blocksFinderBlockColors.remove(blockId) != null) {
			saveConfig();
		}
	}

	public static void clearAllBlocksFinderBlockColors() {
		if (!blocksFinderBlockColors.isEmpty()) {
			blocksFinderBlockColors.clear();
			saveConfig();
		}
	}

	public static List<String> getBlocksFinderBlocks() {
		if (!loaded) loadConfig();
		return new ArrayList<String>(blocksFinderBlocks);
	}

	public static void setBlocksFinderBlocks(List<String> blocks) {
		blocksFinderBlocks = blocks == null ? new ArrayList<String>() : new ArrayList<String>(blocks);
		saveConfig();
	}

	public static int getBarrierVisibilityOpacityPercent() {
		if (!loaded) {
			loadConfig();
		}
		return clampBarrierOpacityPercent(barrierVisibilityOpacityPercent);
	}

	public static void setBarrierVisibilityOpacityPercent(int percent) {
		barrierVisibilityOpacityPercent = clampBarrierOpacityPercent(percent);
		saveConfig();
	}

	private static int clampBarrierOpacityPercent(int v) {
		if (v < BARRIER_OPACITY_MIN) {
			return BARRIER_OPACITY_MIN;
		}
		if (v > BARRIER_OPACITY_MAX) {
			return BARRIER_OPACITY_MAX;
		}
		return v;
	}

	private static int clampBarrierRange(int v) {
		if (v < BARRIER_RANGE_MIN) {
			return BARRIER_RANGE_MIN;
		}
		if (v > BARRIER_RANGE_MAX) {
			return BARRIER_RANGE_MAX;
		}
		return v;
	}

	/**
	 * Checks if NoHitDelay is enabled.
	 * @return true if NoHitDelay is enabled, false otherwise
	 */
	public static boolean isNoHitDelayEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return noHitDelayEnabled;
	}

	/**
	 * Sets whether NoHitDelay is enabled.
	 * @param enabled true to enable NoHitDelay, false to disable
	 */
	public static void setNoHitDelayEnabled(boolean enabled) {
		noHitDelayEnabled = enabled;
		saveConfig();
	}

	/**
	 * Gets the configured CPS cap.
	 * @return CPS cap (0 means disabled)
	 */
	public static double getCpsLimit() {
		if (!loaded) {
			loadConfig();
		}
		return Math.max(0.0, cpsLimit);
	}

	/**
	 * Sets the CPS cap.
	 * @param value CPS cap (0 disables cap)
	 */
	public static void setCpsLimit(double value) {
		cpsLimit = Math.max(0.0, value);
		saveConfig();
	}
	
	// VisibleSpecs getters and setters
	public static boolean isForceGameChatsEnabled() {
		if (!loaded) loadConfig();
		return forceGameChats;
	}
	public static void setForceGameChatsEnabled(boolean enabled) {
		forceGameChats = enabled;
		saveConfig();
	}
	public static boolean isUnbotheredTauntEnabled() {
		if (!loaded) loadConfig();
		return unbotheredTaunt;
	}
	public static void setUnbotheredTauntEnabled(boolean enabled) {
		unbotheredTaunt = enabled;
		saveConfig();
	}
	public static boolean isSeeSpectatorsWhileAliveEnabled() {
		if (!loaded) loadConfig();
		return seeSpectatorsWhileAlive;
	}

	public static void setSeeSpectatorsWhileAliveEnabled(boolean enabled) {
		seeSpectatorsWhileAlive = enabled;
		saveConfig();
	}
	
	/**
	 * Gets whether VisibleSpecs is enabled
	 * @return true if VisibleSpecs is enabled
	 */
	public static boolean isVisibleSpecsEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return visibleSpecsEnabled;
	}
	
	/**
	 * Sets whether VisibleSpecs is enabled
	 * @param enabled true to enable VisibleSpecs
	 */
	public static void setVisibleSpecsEnabled(boolean enabled) {
		visibleSpecsEnabled = enabled;
		saveConfig();
	}

	public static boolean isSpectatorBypassAllInvisible() {
		if (!loaded) {
			loadConfig();
		}
		return spectatorBypassAllInvisible;
	}

	public static void setSpectatorBypassAllInvisible(boolean value) {
		spectatorBypassAllInvisible = value;
		saveConfig();
	}

	public static boolean isVisibleSpecsNametagsEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return !"OFF".equals(visibleSpecsNametagMode);
	}

	public static void setVisibleSpecsNametagsEnabled(boolean enabled) {
		visibleSpecsNametagMode = enabled ? "ON" : "OFF";
		saveConfig();
	}

	public static String getVisibleSpecsNametagMode() {
		if (!loaded) {
			loadConfig();
		}
		return visibleSpecsNametagMode;
	}

	public static void setVisibleSpecsNametagMode(String mode) {
		visibleSpecsNametagMode = normalizeVisibleSpecsNametagMode(mode);
		saveConfig();
	}

	public static boolean isVisibleSpecsKillerNametagEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return visibleSpecsKillerNametagEnabled;
	}

	public static void setVisibleSpecsKillerNametagEnabled(boolean enabled) {
		visibleSpecsKillerNametagEnabled = enabled;
		saveConfig();
	}

	public static boolean isVisibleSpecsApplyToReplaysEnabled() {
		if (!loaded) loadConfig();
		return visibleSpecsApplyToReplaysEnabled;
	}

	public static void setVisibleSpecsApplyToReplaysEnabled(boolean enabled) {
		visibleSpecsApplyToReplaysEnabled = enabled;
		saveConfig();
	}

	private static String normalizeVisibleSpecsNametagMode(String mode) {
		if (mode == null) {
			return "ON";
		}
		String m = mode.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
		if ("ONLYIGN".equals(m)) {
			m = "ONLY_IGN";
		}
		if ("ONLY_IGN".equals(m) || "OFF".equals(m)) {
			return m;
		}
		return "ON";
	}

	public static float getVisibleSpecsOpacity() {
		if (!loaded) {
			loadConfig();
		}
		return visibleSpecsOpacity;
	}

	public static void setVisibleSpecsOpacity(float alpha) {
		visibleSpecsOpacity = clampMountAlpha(alpha);
		saveConfig();
	}

	public static void setVisibleSpecsOpacityRuntime(float alpha) {
		visibleSpecsOpacity = clampMountAlpha(alpha);
	}

	public static void persistVisibleSpecsOpacity() {
		saveConfig();
	}

	// SelfOpacity getters and setters

	public static boolean isSelfOpacityEnabled() {
		if (!loaded) loadConfig();
		return selfOpacityEnabled;
	}

	public static void setSelfOpacityEnabled(boolean enabled) {
		selfOpacityEnabled = enabled;
		saveConfig();
	}

	public static float getSelfOpacityAlpha() {
		if (!loaded) loadConfig();
		return selfOpacityAlpha;
	}

	public static void setSelfOpacityAlpha(float alpha) {
		selfOpacityAlpha = clampMountAlpha(alpha);
		saveConfig();
	}

	public static float getSelfOpacityFirstPersonHandAlpha() {
		if (!loaded) loadConfig();
		return selfOpacityFirstPersonHandAlpha;
	}

	public static void setSelfOpacityFirstPersonHandAlpha(float alpha) {
		selfOpacityFirstPersonHandAlpha = clampMountAlpha(alpha);
		saveConfig();
	}

	public static boolean isSelfOpacityDisableDuringBlindnessEnabled() {
		if (!loaded) loadConfig();
		return selfOpacityDisableDuringBlindness;
	}

	public static void setSelfOpacityDisableDuringBlindnessEnabled(boolean enabled) {
		selfOpacityDisableDuringBlindness = enabled;
		saveConfig();
	}

	public static int getSelfOpacityKeyCode() {
		if (!loaded) loadConfig();
		return selfOpacityKeyCode;
	}

	public static void setSelfOpacityKeyCode(int keyCode) {
		selfOpacityKeyCode = keyCode;
		saveConfig();
	}

	// Schizo getters and setters

	/**
	 * Gets whether Schizo is enabled
	 * @return true if Schizo is enabled
	 */
	public static boolean isSchizoEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return schizoEnabled;
	}

	/**
	 * Sets whether Schizo is enabled
	 * @param enabled true to enable Schizo
	 */
	public static void setSchizoEnabled(boolean enabled) {
		schizoEnabled = enabled;
		saveConfig();
	}
	
	// BorderIndication getters and setters
	/**
	 * Checks if BorderIndication is enabled.
	 * @return true if BorderIndication is enabled, false otherwise
	 */
	public static boolean isBorderIndicationEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return borderIndicationEnabled;
	}
	
	/**
	 * Sets whether BorderIndication is enabled.
	 * @param enabled true to enable BorderIndication, false to disable
	 */
	public static void setBorderIndicationEnabled(boolean enabled) {
		borderIndicationEnabled = enabled;
		saveConfig();
	}
	
	/**
	 * Gets the BorderIndication overlay opacity.
	 * @return opacity value (0.0-1.0)
	 */
	public static float getBorderIndicationOpacity() {
		if (!loaded) {
			loadConfig();
		}
		return borderIndicationOpacity;
	}
	
	/**
	 * Sets the BorderIndication overlay opacity.
	 * @param opacity opacity value (0.0-1.0)
	 */
	public static void setBorderIndicationOpacity(float opacity) {
		borderIndicationOpacity = Math.max(0.0f, Math.min(1.0f, opacity));
		saveConfig();
	}
	
	/**
	 * Gets the BorderIndication render distance.
	 * @return render distance in blocks (16-128)
	 */
	public static int getBorderIndicationRenderDistance() {
		if (!loaded) {
			loadConfig();
		}
		return borderIndicationRenderDistance;
	}
	
	/**
	 * Sets the BorderIndication render distance.
	 * @param distance render distance in blocks (16-128)
	 */
    public static void setBorderIndicationRenderDistance(int distance) {
        borderIndicationRenderDistance = Math.max(16, Math.min(128, distance));
        saveConfig();
    }

    // StarText getters and setters
    public static boolean isStarTextEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return starTextEnabled;
    }

    public static void setStarTextEnabled(boolean enabled) {
        starTextEnabled = enabled;
        saveConfig();
    }

    public static boolean isStarPredictEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return starPredictEnabled;
    }

    public static void setStarPredictEnabled(boolean enabled) {
        starPredictEnabled = enabled;
        saveConfig();
    }

    public static boolean isHotbarEnchantTextEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return hotbarEnchantTextEnabled;
    }

    public static void setHotbarEnchantTextEnabled(boolean enabled) {
        hotbarEnchantTextEnabled = enabled;
        saveConfig();
    }

    public static boolean isHotbarEnchantTextShadow() {
        if (!loaded) {
            loadConfig();
        }
        return hotbarEnchantTextShadow;
    }

    public static void setHotbarEnchantTextShadow(boolean shadow) {
        hotbarEnchantTextShadow = shadow;
        saveConfig();
    }

    public static int getHotbarEnchantTextColor() {
        if (!loaded) {
            loadConfig();
        }
        return hotbarEnchantTextColor & 0xFFFFFF;
    }

    public static void setHotbarEnchantTextColor(int color) {
        hotbarEnchantTextColor = color & 0xFFFFFF;
        saveConfig();
    }

    public static int getHotbarEnchantPotionTextStyle() {
        if (!loaded) {
            loadConfig();
        }
        return hotbarEnchantPotionTextStyle;
    }

    public static boolean isHotbarEnchantPotionTextOnly() {
        return getHotbarEnchantPotionTextStyle() == 1;
    }

    public static void setHotbarEnchantPotionTextStyle(int style) {
        hotbarEnchantPotionTextStyle = Math.max(0, Math.min(1, style));
        saveConfig();
    }

    public static void cycleHotbarEnchantPotionTextStyle() {
        setHotbarEnchantPotionTextStyle((getHotbarEnchantPotionTextStyle() + 1) % 2);
    }

    public static int getHotbarEnchantTextGapAboveName() {
        if (!loaded) {
            loadConfig();
        }
        return hotbarEnchantTextGapAboveName;
    }

    public static void setHotbarEnchantTextGapAboveName(int gap) {
        applyHotbarEnchantTextGapAboveName(gap);
        saveConfig();
    }

    /** Updates gap in memory only (e.g. while dragging the settings slider). */
    public static void applyHotbarEnchantTextGapAboveName(int gap) {
        if (!loaded) {
            loadConfig();
        }
        hotbarEnchantTextGapAboveName = clampHotbarEnchantTextGapAboveName(gap);
    }

    private static int clampHotbarEnchantTextGapAboveName(int gap) {
        return Math.max(0, Math.min(20, gap));
    }

    public static boolean isDcChatMirrorEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return dcChatMirrorEnabled;
    }

    public static void setDcChatMirrorEnabled(boolean enabled) {
        dcChatMirrorEnabled = enabled;
        saveConfig();
    }

    /**
     * One-time: move legacy {@code discord_dm_recipient} from api_keys.json into gomodConfig.
     */
    public static void migrateDcRecipientFromApiKeysIfNeeded() {
        if (GomodReleaseBuild.PUBLIC_RELEASE) {
            return;
        }
        if (!loaded) {
            loadConfig();
        }
        if (!dcMirrorRecipientIds.isEmpty()) {
            return;
        }
        ApiKeyManager.loadApiKeys();
        String legacyRecipientKey = DcStringUtil.dcDmRecipientKey();
        String old = ApiKeyManager.getApiKey(legacyRecipientKey);
        if (old == null || old.trim().isEmpty()) {
            return;
        }
        String t = old.trim().replaceAll("\\s+", "");
        if (!t.matches("\\d{5,22}")) {
            return;
        }
        dcMirrorRecipientIds.add(t);
        dcMirrorSelectedRecipientId = t;
        ApiKeyManager.setApiKey(legacyRecipientKey, null);
        saveConfig();
    }

    public static List<String> getDcMirrorRecipientIdsUnmodifiable() {
        if (!loaded) {
            loadConfig();
        }
        return Collections.unmodifiableList(new ArrayList<String>(dcMirrorRecipientIds));
    }

    public static String getDcMirrorSelectedRecipientId() {
        if (!loaded) {
            loadConfig();
        }
        return dcMirrorSelectedRecipientId == null ? "" : dcMirrorSelectedRecipientId.trim();
    }

    public static void setDcMirrorSelectedRecipientId(String id) {
        if (!loaded) {
            loadConfig();
        }
        dcMirrorSelectedRecipientId = id == null ? "" : id.trim().replaceAll("\\s+", "");
        saveConfig();
    }

    public static void addDcMirrorRecipientIfAbsent(String id) {
        if (!loaded) {
            loadConfig();
        }
        String t = id == null ? "" : id.trim().replaceAll("\\s+", "");
        if (!t.matches("\\d{5,22}")) {
            return;
        }
        for (String x : dcMirrorRecipientIds) {
            if (x.equals(t)) {
                return;
            }
        }
        dcMirrorRecipientIds.add(t);
        DcMirrorBridge.touchRecipientRecencyManual(t);
        if (dcMirrorSelectedRecipientId == null || dcMirrorSelectedRecipientId.trim().isEmpty()) {
            dcMirrorSelectedRecipientId = t;
        }
        saveConfig();
    }

    /**
     * Orders recipients by recent Discord DM activity ({@link DcMirrorBridge#getRecipientRecencyKey}).
     *
     * @return true if the list order changed
     */
    public static boolean sortDcMirrorRecipientsByRecency() {
        if (!loaded) {
            loadConfig();
        }
        ArrayList<String> before = new ArrayList<String>(dcMirrorRecipientIds);
        Collections.sort(dcMirrorRecipientIds, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return DcMirrorBridge.compareRecipientsByRecentTalk(a, b);
            }
        });
        if (before.equals(dcMirrorRecipientIds)) {
            return false;
        }
        saveConfig();
        return true;
    }

    public static void removeDcMirrorRecipientAt(int index) {
        if (!loaded) {
            loadConfig();
        }
        if (index < 0 || index >= dcMirrorRecipientIds.size()) {
            return;
        }
        String removed = dcMirrorRecipientIds.remove(index);
        if (removed != null && removed.equals(dcMirrorSelectedRecipientId)) {
            dcMirrorSelectedRecipientId = dcMirrorRecipientIds.isEmpty() ? "" : dcMirrorRecipientIds.get(0);
        }
        saveConfig();
    }

    /** Remove one recipient by snowflake (GUI row buttons use stable IDs, not list indices). */
    public static boolean removeDcMirrorRecipientById(String id) {
        if (!loaded) {
            loadConfig();
        }
        String t = id == null ? "" : id.trim().replaceAll("\\s+", "");
        if (t.isEmpty()) {
            return false;
        }
        for (int i = 0; i < dcMirrorRecipientIds.size(); i++) {
            if (dcMirrorRecipientIds.get(i).equals(t)) {
                dcMirrorRecipientIds.remove(i);
                if (t.equals(dcMirrorSelectedRecipientId)) {
                    dcMirrorSelectedRecipientId = dcMirrorRecipientIds.isEmpty()
                            ? "" : dcMirrorRecipientIds.get(0);
                }
                saveConfig();
                return true;
            }
        }
        return false;
    }

    public static boolean isDcMirrorIncomingOnlySelectedRecipient() {
        if (!loaded) {
            loadConfig();
        }
        return dcMirrorIncomingOnlySelectedRecipient;
    }

    public static void setDcMirrorIncomingOnlySelectedRecipient(boolean onlySelected) {
        if (!loaded) {
            loadConfig();
        }
        dcMirrorIncomingOnlySelectedRecipient = onlySelected;
        saveConfig();
    }

    /** Reads pre-rename config keys (internal builds only; stripped from public JAR). */
    private static void loadLegacyDcMirrorConfigKeys(JsonObject config) {
        String legacyPrefix = DcStringUtil.dcPrefix();
        if (!config.has("dcChatMirrorEnabled") && config.has(legacyPrefix + "ChatMirrorEnabled")) {
            dcChatMirrorEnabled = config.get(legacyPrefix + "ChatMirrorEnabled").getAsBoolean();
        }
        if (dcMirrorRecipientIds.isEmpty()
                && config.has(legacyPrefix + "MirrorRecipientIds")
                && config.get(legacyPrefix + "MirrorRecipientIds").isJsonArray()) {
            JsonArray arr = config.getAsJsonArray(legacyPrefix + "MirrorRecipientIds");
            for (JsonElement e : arr) {
                if (e != null && e.isJsonPrimitive()) {
                    String s = e.getAsString();
                    if (s != null && s.matches("\\d{5,22}")) {
                        dcMirrorRecipientIds.add(s.trim());
                    }
                }
            }
        }
        if (!config.has("dcMirrorSelectedRecipientId")
                && config.has(legacyPrefix + "MirrorSelectedRecipientId")) {
            dcMirrorSelectedRecipientId = config.get(legacyPrefix + "MirrorSelectedRecipientId").getAsString();
        }
        if (!config.has("dcMirrorIncomingOnlySelectedRecipient")
                && config.has(legacyPrefix + "MirrorIncomingOnlySelectedRecipient")) {
            dcMirrorIncomingOnlySelectedRecipient =
                    config.get(legacyPrefix + "MirrorIncomingOnlySelectedRecipient").getAsBoolean();
        }
    }

    public static float getStarTextDuration() {
        if (!loaded) {
            loadConfig();
        }
        return starTextDuration;
    }

    public static void setStarTextDuration(float duration) {
        starTextDuration = Math.max(1.0f, Math.min(20.0f, duration));
        saveConfig();
    }

    public static int getStarTextYOffset() {
        if (!loaded) {
            loadConfig();
        }
        return starTextYOffset;
    }

    public static void setStarTextYOffset(int offset) {
        starTextYOffset = offset;
        saveConfig();
    }

    public static float getStarTextScale() {
        if (!loaded) {
            loadConfig();
        }
        return starTextScale;
    }

    public static void setStarTextScale(float scale) {
        starTextScale = Math.max(0.5f, Math.min(10.0f, scale));
        saveConfig();
    }

    public static boolean isLobbyPingDisplayEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return lobbyPingDisplayEnabled;
    }

    public static void setLobbyPingDisplayEnabled(boolean enabled) {
        lobbyPingDisplayEnabled = enabled;
        saveConfig();
    }

    public static boolean isMountXpOverlayEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return mountXpOverlayEnabled;
    }

    public static void setMountXpOverlayEnabled(boolean enabled) {
        mountXpOverlayEnabled = enabled;
        saveConfig();
    }

    public static int getMountXpOverlayOpacity() {
        if (!loaded) {
            loadConfig();
        }
        return mountXpOverlayOpacity;
    }

    public static void setMountXpOverlayOpacity(int opacity) {
        mountXpOverlayOpacity = Math.max(0, Math.min(255, opacity));
        saveConfig();
    }

    public static boolean isNickRealNametagOverlayEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return nickRealNametagOverlayEnabled;
    }

    public static void setNickRealNametagOverlayEnabled(boolean enabled) {
        nickRealNametagOverlayEnabled = enabled;
        saveConfig();
    }

    public static boolean isNickRealNametagRankPlateEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return nickRealNametagRankPlateEnabled;
    }

    public static void setNickRealNametagRankPlateEnabled(boolean enabled) {
        nickRealNametagRankPlateEnabled = enabled;
        saveConfig();
    }

    private static String normalizeDenickerTagDisplayMode(String raw) {
        if (raw == null) {
            return "PARENTHESES";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return "RANK".equals(u) ? "RANK" : "PARENTHESES";
    }

    public static boolean isDenickerEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return denickerEnabled;
    }

    public static void setDenickerEnabled(boolean enabled) {
        denickerEnabled = enabled;
        nickRealNametagOverlayEnabled = enabled;
        saveConfig();
    }

    public static boolean isNickRenamedItemScannerEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return nickRenamedItemScannerEnabled;
    }

    public static void setNickRenamedItemScannerEnabled(boolean enabled) {
        nickRenamedItemScannerEnabled = enabled;
        saveConfig();
    }

    public static boolean isNickDeathSearchLinkEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return nickDeathSearchLinkEnabled;
    }

    public static void setNickDeathSearchLinkEnabled(boolean enabled) {
        nickDeathSearchLinkEnabled = enabled;
        saveConfig();
    }

    public static boolean isDenickerRankTagMode() {
        if (!loaded) {
            loadConfig();
        }
        return "RANK".equals(normalizeDenickerTagDisplayMode(denickerTagDisplayMode));
    }

    public static void setDenickerRankTagMode(boolean rankMode) {
        denickerTagDisplayMode = rankMode ? "RANK" : "PARENTHESES";
        nickRealNametagRankPlateEnabled = rankMode;
        saveConfig();
    }

    public static void cycleDenickerTagDisplayMode() {
        setDenickerRankTagMode(!isDenickerRankTagMode());
    }

    public static String getDenickerTagDisplayModeLabel() {
        return isDenickerRankTagMode() ? "Rank tag" : "Parentheses";
    }

    public static boolean isDenickerRevealRealSkinEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return denickerRevealRealSkinEnabled;
    }

    public static void setDenickerRevealRealSkinEnabled(boolean enabled) {
        denickerRevealRealSkinEnabled = enabled;
        saveConfig();
    }

    public static boolean isDenickerShowRealIgnInTabEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return denickerShowRealIgnInTabEnabled;
    }

    public static void setDenickerShowRealIgnInTabEnabled(boolean enabled) {
        denickerShowRealIgnInTabEnabled = enabled;
        saveConfig();
    }

    public static boolean isRecentOpponentsGroupDenickedPlayers() {
        if (!loaded) loadConfig();
        return recentOpponentsGroupDenickedPlayers;
    }

    public static void setRecentOpponentsGroupDenickedPlayers(boolean enabled) {
        recentOpponentsGroupDenickedPlayers = enabled;
        saveConfig();
    }

    public static int getRecentOpponentsSortMode() {
        if (!loaded) loadConfig();
        return Math.max(0, Math.min(4, recentOpponentsSortMode));
    }

    public static void setRecentOpponentsSortMode(int mode) {
        recentOpponentsSortMode = Math.max(0, Math.min(4, mode));
        saveConfig();
    }

    private static String normalizeGuiBackdropMode(String raw) {
        if (raw == null) {
            return "VANILLA";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(u) || "ALL_GUIS".equals(u)) {
            return "ALL";
        }
        // Legacy tri-state: gomod-only merged into single "enabled" (ALL).
        if ("GOMOD".equals(u) || "GOMOD_ONLY".equals(u)) {
            return "ALL";
        }
        return "VANILLA";
    }

    private static int clampGuiBackdropDim(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /** @return {@code VANILLA} (disabled) or {@code ALL} (enabled) */
    public static String getGuiBackdropMode() {
        if (!loaded) {
            loadConfig();
        }
        return guiBackdropMode;
    }

    public static void setGuiBackdropMode(String mode) {
        guiBackdropMode = normalizeGuiBackdropMode(mode);
        saveConfig();
    }

    public static void cycleGuiBackdropMode() {
        guiBackdropMode = "VANILLA".equals(guiBackdropMode) ? "ALL" : "VANILLA";
        saveConfig();
    }

    public static boolean isHudEditorCenterGuidesAlways() {
        if (!loaded) {
            loadConfig();
        }
        return hudEditorCenterGuidesAlways;
    }

    public static void setHudEditorCenterGuidesAlways(boolean value) {
        hudEditorCenterGuidesAlways = value;
        saveConfig();
    }

    public static int getGuiBackdropGomodDimPercent() {
        if (!loaded) {
            loadConfig();
        }
        return guiBackdropGomodDimPercent;
    }

    public static void setGuiBackdropGomodDimPercent(int percent) {
        guiBackdropGomodDimPercent = clampGuiBackdropDim(percent);
        saveConfig();
    }

    public static int getGuiBackdropGomodBlurPercent() {
        if (!loaded) {
            loadConfig();
        }
        return guiBackdropGomodBlurPercent;
    }

    public static void setGuiBackdropGomodBlurPercent(int percent) {
        guiBackdropGomodBlurPercent = clampGuiBackdropDim(percent);
        saveConfig();
    }

    public static int getGuiBackdropOtherDimPercent() {
        if (!loaded) {
            loadConfig();
        }
        return guiBackdropOtherDimPercent;
    }

    public static void setGuiBackdropOtherDimPercent(int percent) {
        guiBackdropOtherDimPercent = clampGuiBackdropDim(percent);
        saveConfig();
    }

    public static int getGuiBackdropOtherBlurPercent() {
        if (!loaded) {
            loadConfig();
        }
        return guiBackdropOtherBlurPercent;
    }

    public static void setGuiBackdropOtherBlurPercent(int percent) {
        guiBackdropOtherBlurPercent = clampGuiBackdropDim(percent);
        saveConfig();
    }

    public static void setGuiBackdropGomodDimPercentRuntime(int percent) {
        if (!loaded) {
            loadConfig();
        }
        guiBackdropGomodDimPercent = clampGuiBackdropDim(percent);
    }

    public static void setGuiBackdropGomodBlurPercentRuntime(int percent) {
        if (!loaded) {
            loadConfig();
        }
        guiBackdropGomodBlurPercent = clampGuiBackdropDim(percent);
    }

    public static void setGuiBackdropOtherDimPercentRuntime(int percent) {
        if (!loaded) {
            loadConfig();
        }
        guiBackdropOtherDimPercent = clampGuiBackdropDim(percent);
    }

    public static void setGuiBackdropOtherBlurPercentRuntime(int percent) {
        if (!loaded) {
            loadConfig();
        }
        guiBackdropOtherBlurPercent = clampGuiBackdropDim(percent);
    }

    public static boolean isGuiBackdropGomodExcludeChatStrip() {
        if (!loaded) {
            loadConfig();
        }
        return guiBackdropGomodExcludeChatStrip;
    }

    public static void setGuiBackdropGomodExcludeChatStrip(boolean v) {
        guiBackdropGomodExcludeChatStrip = v;
        saveConfig();
    }

    public static boolean isGuiBackdropOtherExcludeChatStrip() {
        if (!loaded) {
            loadConfig();
        }
        return guiBackdropOtherExcludeChatStrip;
    }

    public static void setGuiBackdropOtherExcludeChatStrip(boolean v) {
        guiBackdropOtherExcludeChatStrip = v;
        saveConfig();
    }

    public static boolean isHideScoreboardValuesEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return hideScoreboardValuesEnabled;
    }

    public static void setHideScoreboardValuesEnabled(boolean enabled) {
        hideScoreboardValuesEnabled = enabled;
        if (!enabled) {
            SidebarKeScoreboardMutator.forceRestoreSidebarMutation();
            SidebarTauntWastedMutator.forceRestore();
        }
        saveConfig();
    }

    public static boolean isHideDateGameIdEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return hideDateGameIdEnabled;
    }

    public static void setHideDateGameIdEnabled(boolean enabled) {
        hideDateGameIdEnabled = enabled;
        saveConfig();
    }

    public static boolean isHideKitScoreboardEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return hideKitScoreboardEnabled;
    }

    public static void setHideKitScoreboardEnabled(boolean enabled) {
        hideKitScoreboardEnabled = enabled;
        saveConfig();
    }

    public static boolean isHideHypixelFooterEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return hideHypixelFooterEnabled;
    }

    public static void setHideHypixelFooterEnabled(boolean enabled) {
        hideHypixelFooterEnabled = enabled;
        saveConfig();
    }

    public static boolean isSidebarBlitzHourLineEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return sidebarBlitzHourLineEnabled;
    }

    public static void setSidebarBlitzHourLineEnabled(boolean enabled) {
        sidebarBlitzHourLineEnabled = enabled;
        saveConfig();
    }

    /** Date/game-ID hiding is active (master + sub-toggle). */
    public static boolean isHideDateGameIdEffective() {
        return isHideScoreboardValuesEnabled() && isHideDateGameIdEnabled();
    }

    /** Kit-line hiding is active (master + sub-toggle). */
    public static boolean isHideKitScoreboardEffective() {
        return isHideScoreboardValuesEnabled() && isHideKitScoreboardEnabled();
    }

    public static boolean isHideHypixelFooterEffective() {
        return isHideScoreboardValuesEnabled() && isHideHypixelFooterEnabled();
    }

    public static boolean isSidebarBlitzHourLineEffective() {
        return isHideScoreboardValuesEnabled() && isSidebarBlitzHourLineEnabled();
    }

    public static boolean isScoreboardPrivacyHideActive() {
        return isHideScoreboardValuesEnabled()
                && (isHideDateGameIdEnabled() || isHideKitScoreboardEnabled() || isHideHypixelFooterEnabled());
    }

    public static boolean isSidebarKillEffectLineEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return sidebarKillEffectLineEnabled;
    }

    public static void setSidebarKillEffectLineEnabled(boolean enabled) {
        sidebarKillEffectLineEnabled = enabled;
        if (!enabled) {
            SidebarKeScoreboardMutator.forceRestoreSidebarMutation();
        }
        saveConfig();
    }

    public static boolean isSidebarTauntWastedEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return sidebarTauntWastedEnabled;
    }

    public static void setSidebarTauntWastedEnabled(boolean enabled) {
        sidebarTauntWastedEnabled = enabled;
        if (!enabled) {
            SidebarTauntWastedMutator.forceRestore();
        }
        saveConfig();
    }

    public static boolean isMountHeadDimEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return mountHeadDimEnabled;
    }

    public static void setMountHeadDimEnabled(boolean enabled) {
        mountHeadDimEnabled = enabled;
        saveConfig();
    }

    private static float clampMountAlpha(float a) {
        return Math.max(0.05f, Math.min(1.0f, a));
    }

    private static float clampUnitFloat(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    /** @deprecated prefer {@link #getMountAlphaHorse()} */
    @Deprecated
    public static float getMountHeadDimAlpha() {
        if (!loaded) {
            loadConfig();
        }
        return mountAlphaHorse;
    }

    /** @deprecated prefer {@link #setMountAlphaHorse(float)} */
    @Deprecated
    public static void setMountHeadDimAlpha(float alpha) {
        mountAlphaHorse = clampMountAlpha(alpha);
        saveConfig();
    }

    public static void setMountHeadDimAlphaRuntime(float alpha) {
        mountAlphaHorse = clampMountAlpha(alpha);
    }

    public static void persistMountHeadDimAlpha() {
        saveConfig();
    }

    public static float getMountAlphaHorse() {
        if (!loaded) {
            loadConfig();
        }
        return mountAlphaHorse;
    }

    public static float getMountAlphaDonkey() {
        if (!loaded) {
            loadConfig();
        }
        return mountAlphaDonkey;
    }

    public static float getMountAlphaPig() {
        if (!loaded) {
            loadConfig();
        }
        return mountAlphaPig;
    }

    public static float getMountAlphaBoat() {
        if (!loaded) {
            loadConfig();
        }
        return mountAlphaBoat;
    }

    public static float getMountAlphaWagon() {
        if (!loaded) {
            loadConfig();
        }
        return mountAlphaWagon;
    }

    public static void setMountAlphaHorse(float v) {
        mountAlphaHorse = clampMountAlpha(v);
        saveConfig();
    }

    public static void setMountAlphaDonkey(float v) {
        mountAlphaDonkey = clampMountAlpha(v);
        saveConfig();
    }

    public static void setMountAlphaPig(float v) {
        mountAlphaPig = clampMountAlpha(v);
        saveConfig();
    }

    public static void setMountAlphaBoat(float v) {
        mountAlphaBoat = clampMountAlpha(v);
        saveConfig();
    }

    public static void setMountAlphaWagon(float v) {
        mountAlphaWagon = clampMountAlpha(v);
        saveConfig();
    }

    public static void setMountAlphaHorseRuntime(float v) {
        mountAlphaHorse = clampMountAlpha(v);
    }

    public static void setMountAlphaDonkeyRuntime(float v) {
        mountAlphaDonkey = clampMountAlpha(v);
    }

    public static void setMountAlphaPigRuntime(float v) {
        mountAlphaPig = clampMountAlpha(v);
    }

    public static void setMountAlphaBoatRuntime(float v) {
        mountAlphaBoat = clampMountAlpha(v);
    }

    public static void setMountAlphaWagonRuntime(float v) {
        mountAlphaWagon = clampMountAlpha(v);
    }

    /** After dragging any mount opacity slider. */
    public static void persistMountAlphas() {
        saveConfig();
    }

    public static boolean isWaterClarityEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return waterClarityEnabled;
    }

    public static void setWaterClarityEnabled(boolean enabled) {
        waterClarityEnabled = enabled;
        saveConfig();
    }

    public static boolean isNametagThroughBlocksEnabled() {
        if (!loaded) loadConfig();
        return nametagThroughBlocksEnabled;
    }

    public static void setNametagThroughBlocksEnabled(boolean enabled) {
        nametagThroughBlocksEnabled = enabled;
        saveConfig();
    }

    public static float getWaterClarityAmount() {
        if (!loaded) {
            loadConfig();
        }
        return waterClarityAmount;
    }

    public static void setWaterClarityAmount(float amount) {
        waterClarityAmount = clampUnitFloat(amount);
        saveConfig();
    }

    public static boolean isWaterClaritySurfaceFromAirEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return waterClaritySurfaceFromAir;
    }

    public static void setWaterClaritySurfaceFromAirEnabled(boolean v) {
        waterClaritySurfaceFromAir = v;
        saveConfig();
    }

    public static boolean isWaterClarityUnderwaterFogEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return waterClarityUnderwaterFog;
    }

    public static void setWaterClarityUnderwaterFogEnabled(boolean v) {
        waterClarityUnderwaterFog = v;
        saveConfig();
    }

    public static int getMountHeadDimStrategy() {
        if (!loaded) {
            loadConfig();
        }
        return mountHeadDimStrategy;
    }

    public static void setMountHeadDimStrategy(int strategy) {
        mountHeadDimStrategy = Math.max(1, Math.min(8, strategy));
        saveConfig();
    }

    public static int getMountDimDepthMode() {
        if (!loaded) loadConfig();
        return mountDimDepthMode;
    }

    public static void setMountDimDepthMode(int mode) {
        mountDimDepthMode = Math.max(1, Math.min(6, mode));
        saveConfig();
    }

    public static int getMountFlushMode() {
        if (!loaded) {
            loadConfig();
        }
        if (mountFlushMode == 3) {
            mountFlushMode = 1;
        }
        return mountFlushMode;
    }

    public static void setMountFlushMode(int mode) {
        mountFlushMode = Math.max(1, Math.min(4, mode));
        if (mountFlushMode == 3) {
            mountFlushMode = 1;
        }
        saveConfig();
    }

    // LootCall getters and setters
    public static boolean isLootCallEnabled() {
        if (!loaded) loadConfig();
        return lootCallEnabled;
    }

    public static void setLootCallEnabled(boolean value) {
        lootCallEnabled = value;
        saveConfig();
    }

    public static boolean isLootCallEverywhere() {
        if (!loaded) loadConfig();
        return lootCallEverywhere;
    }

    public static void setLootCallEverywhere(boolean value) {
        lootCallEverywhere = value;
        saveConfig();
    }

    public static boolean isLootCallAnnounceDiamond() {
        if (!loaded) loadConfig();
        return lootCallAnnounceDiamond;
    }

    public static void setLootCallAnnounceDiamond(boolean value) {
        lootCallAnnounceDiamond = value;
        saveConfig();
    }

    public static boolean isLootCallAnnounceIron() {
        if (!loaded) loadConfig();
        return lootCallAnnounceIron;
    }

    public static void setLootCallAnnounceIron(boolean value) {
        lootCallAnnounceIron = value;
        saveConfig();
    }

    public static boolean isLootCallAnnounceGold() {
        if (!loaded) loadConfig();
        return lootCallAnnounceGold;
    }

    public static void setLootCallAnnounceGold(boolean value) {
        lootCallAnnounceGold = value;
        saveConfig();
    }

    public static boolean isTeamLootEnabled() {
        if (!loaded) loadConfig();
        return teamLootEnabled;
    }

    public static void setTeamLootEnabled(boolean value) {
        teamLootEnabled = value;
        saveConfig();
    }

    private static float clampItemEspLabelExtraYOffset(float v) {
        return Math.max(-0.75f, Math.min(3.0f, v));
    }

    public static float getItemEspLabelExtraYOffset() {
        if (!loaded) {
            loadConfig();
        }
        return itemEspLabelExtraYOffset;
    }

    public static void setItemEspLabelExtraYOffset(float blocks) {
        itemEspLabelExtraYOffset = clampItemEspLabelExtraYOffset(blocks);
        saveConfig();
    }

    public static boolean isAskForLootEnabled() {
        if (!loaded) loadConfig();
        return askForLootEnabled;
    }

    public static void setAskForLootEnabled(boolean value) {
        askForLootEnabled = value;
        saveConfig();
    }

    public static boolean isAskForLootAllTime() {
        if (!loaded) loadConfig();
        return askForLootAllTime;
    }

    public static void setAskForLootAllTime(boolean value) {
        askForLootAllTime = value;
        saveConfig();
    }

    public static int getAskForLootKeyCode() {
        if (!loaded) loadConfig();
        return askForLootKeyCode;
    }

    public static void setAskForLootKeyCode(int value) {
        askForLootKeyCode = value;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static boolean isPlayerCallerEnabled() {
        if (!loaded) loadConfig();
        return playerCallerEnabled;
    }

    public static void setPlayerCallerEnabled(boolean value) {
        playerCallerEnabled = value;
        saveConfig();
    }

    public static int getPlayerCallerKeyCode() {
        if (!loaded) loadConfig();
        return playerCallerKeyCode;
    }

    public static void setPlayerCallerKeyCode(int value) {
        playerCallerKeyCode = value;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static boolean isChestRoutesRenderEnabled() {
        if (!loaded) loadConfig();
        return chestRoutesRenderEnabled;
    }

    public static void setChestRoutesRenderEnabled(boolean value) {
        chestRoutesRenderEnabled = value;
        saveConfig();
    }

    public static int getChestRoutesPlaceFeetKeyCode() {
        if (!loaded) loadConfig();
        return chestRoutesPlaceFeetKeyCode;
    }

    public static void setChestRoutesPlaceFeetKeyCode(int value) {
        chestRoutesPlaceFeetKeyCode = value;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static int getChestRoutesToggleKeyCode() {
        if (!loaded) loadConfig();
        return chestRoutesToggleKeyCode;
    }

    public static void setChestRoutesToggleKeyCode(int value) {
        chestRoutesToggleKeyCode = value;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static int getChestRoutesUndoKeyCode() {
        if (!loaded) loadConfig();
        return chestRoutesUndoKeyCode;
    }

    public static void setChestRoutesUndoKeyCode(int value) {
        chestRoutesUndoKeyCode = value;
        saveConfig();
        GomodKeybinds.applyConfigBindings();
    }

    public static String getDisguiseKillCounterMode() {
        if (!loaded) loadConfig();
        return disguiseKillCounterMode;
    }

    public static void setDisguiseKillCounterMode(String mode) {
        disguiseKillCounterMode = normalizeDisguiseToggleMode(mode);
        saveConfig();
    }

    public static String getDisguiseTournamentKillCounterMode() {
        if (!loaded) loadConfig();
        return disguiseTournamentKillCounterMode;
    }

    public static void setDisguiseTournamentKillCounterMode(String mode) {
        disguiseTournamentKillCounterMode = normalizeDisguiseTournamentKillCounterMode(mode);
        saveConfig();
    }

    public static int getDisguiseCustomKillCount() {
        if (!loaded) loadConfig();
        return Math.max(0, disguiseCustomKillCount);
    }

    public static void setDisguiseCustomKillCount(int kills) {
        if (kills < 0) {
            kills = 0;
        }
        if (kills > 999_999_999) {
            kills = 999_999_999;
        }
        disguiseCustomKillCount = kills;
        saveConfig();
    }

    public static boolean isDisguiseCustomKillCountEnabled() {
        if (!loaded) loadConfig();
        return disguiseCustomKillCountEnabled;
    }

    public static void setDisguiseCustomKillCountEnabled(boolean enabled) {
        disguiseCustomKillCountEnabled = enabled;
        saveConfig();
    }

    public static boolean isDisguiseBlitzKitSelectorEnabled() {
        if (!loaded) loadConfig();
        return disguiseBlitzKitSelectorEnabled;
    }

    public static void setDisguiseBlitzKitSelectorEnabled(boolean enabled) {
        disguiseBlitzKitSelectorEnabled = enabled;
        saveConfig();
    }

    public static boolean isDisguiseMaskRenamedItemsEnabled() {
        if (!loaded) loadConfig();
        return !"UNCHANGED".equals(disguiseRenamedItemsMode);
    }

    public static void setDisguiseMaskRenamedItemsEnabled(boolean enabled) {
        disguiseRenamedItemsMode = enabled ? "RESETED" : "UNCHANGED";
        saveConfig();
    }

    public static String getDisguiseRenamedItemsMode() {
        if (!loaded) loadConfig();
        return disguiseRenamedItemsMode;
    }

    public static void setDisguiseRenamedItemsMode(String mode) {
        disguiseRenamedItemsMode = normalizeDisguiseRenamedItemsMode(mode);
        saveConfig();
    }

    public static String getDisguiseHypixelPrefixMode() {
        if (!loaded) loadConfig();
        return disguiseHypixelPrefixMode;
    }

    public static void setDisguiseHypixelPrefixMode(String mode) {
        disguiseHypixelPrefixMode = normalizeDisguiseHypixelPrefixMode(mode);
        saveConfig();
    }

    public static boolean isDisguiseHypixelPrefixEnabled() {
        return !"HIDE".equalsIgnoreCase(getDisguiseHypixelPrefixMode());
    }

    public static void setDisguiseHypixelPrefixEnabled(boolean enabled) {
        setDisguiseHypixelPrefixMode(enabled ? "SHOW" : "HIDE");
    }

    public static String getDisguiseHypixelRankPreset() {
        if (!loaded) loadConfig();
        return normalizeDisguiseHypixelRankPreset(disguiseHypixelRankPreset);
    }

    public static void setDisguiseHypixelRankPreset(String preset) {
        disguiseHypixelRankPreset = normalizeDisguiseHypixelRankPreset(preset);
        saveConfig();
    }

    public static String getDisguiseHypixelMvpPlusColor() {
        if (!loaded) loadConfig();
        return normalizeHypixelRankColorName(disguiseHypixelMvpPlusColor);
    }

    public static void setDisguiseHypixelMvpPlusColor(String color) {
        disguiseHypixelMvpPlusColor = normalizeHypixelRankColorName(color);
        saveConfig();
    }

    public static String getDisguiseHypixelMvpBracketColor() {
        if (!loaded) {
            loadConfig();
        }
        return normalizeHypixelMvpPlusPlusColorName(disguiseHypixelMvpBracketColor);
    }

    public static void setDisguiseHypixelMvpBracketColor(String color) {
        disguiseHypixelMvpBracketColor = normalizeHypixelMvpPlusPlusColorName(color);
        saveConfig();
    }

    public static String getDisguiseHypixelMvpPlusBracketBaseColor() {
        if (!loaded) {
            loadConfig();
        }
        return normalizeHypixelMvpPlusPlusColorName(disguiseHypixelMvpPlusBracketBaseColor);
    }

    public static void setDisguiseHypixelMvpPlusBracketBaseColor(String color) {
        disguiseHypixelMvpPlusBracketBaseColor = normalizeHypixelMvpPlusPlusColorName(color);
        saveConfig();
    }

    public static String getDisguiseHypixelMvpPpBaseColor() {
        if (!loaded) loadConfig();
        return normalizeHypixelMvpPlusPlusColorName(disguiseHypixelMvpPpBaseColor);
    }

    public static void setDisguiseHypixelMvpPpBaseColor(String color) {
        disguiseHypixelMvpPpBaseColor = normalizeHypixelMvpPlusPlusColorName(color);
        saveConfig();
    }

    public static String getDisguiseHypixelMvpPpSignColor() {
        if (!loaded) loadConfig();
        return normalizeHypixelMvpPlusPlusColorName(disguiseHypixelMvpPpSignColor);
    }

    public static void setDisguiseHypixelMvpPpSignColor(String color) {
        disguiseHypixelMvpPpSignColor = normalizeHypixelMvpPlusPlusColorName(color);
        saveConfig();
    }

    public static String getDisguiseHypixelRankCustom() {
        if (!loaded) loadConfig();
        return disguiseHypixelRankCustom == null ? "" : disguiseHypixelRankCustom;
    }

    public static void setDisguiseHypixelRankCustom(String raw) {
        disguiseHypixelRankCustom = raw == null ? "" : raw;
        saveConfig();
    }

    public static String getDisguiseNickSetupLastNick() {
        if (!loaded) loadConfig();
        return disguiseNickSetupLastNick == null ? "" : disguiseNickSetupLastNick;
    }

    public static void setDisguiseNickSetupLastNick(String nick) {
        disguiseNickSetupLastNick = nick == null ? "" : nick;
        saveConfig();
    }

    public static String getDisguiseNickSetupLastSkinMode() {
        if (!loaded) loadConfig();
        return normalizeNickSkinMode(disguiseNickSetupLastSkinMode);
    }

    public static void setDisguiseNickSetupLastSkinMode(String mode) {
        disguiseNickSetupLastSkinMode = normalizeNickSkinMode(mode);
        saveConfig();
    }

    public static String getDisguiseNickSetupLastSkinRef() {
        if (!loaded) loadConfig();
        return disguiseNickSetupLastSkinRef == null ? "" : disguiseNickSetupLastSkinRef;
    }

    public static void setDisguiseNickSetupLastSkinRef(String ref) {
        disguiseNickSetupLastSkinRef = ref == null ? "" : ref;
        saveConfig();
    }

    public static void saveDisguiseNickSetupDraft(String nick, String skinMode, String skinRef) {
        if (!loaded) loadConfig();
        disguiseNickSetupLastNick = nick == null ? "" : nick;
        disguiseNickSetupLastSkinMode = normalizeNickSkinMode(skinMode);
        disguiseNickSetupLastSkinRef = skinRef == null ? "" : skinRef;
        saveConfig();
    }

    public static boolean isDisguiseRestoreOnReconnect() {
        if (!loaded) loadConfig();
        return disguiseRestoreOnReconnect;
    }

    public static void setDisguiseRestoreOnReconnect(boolean enabled) {
        disguiseRestoreOnReconnect = enabled;
        if (!enabled) {
            disguiseReconnectKind = "OFF";
            disguiseReconnectPlayerName = "";
            disguiseReconnectShareMode = "LOCAL";
            disguiseReconnectFullLunarUuid = "";
            disguiseReconnectNick = "";
            disguiseReconnectNickRaw = "";
            disguiseReconnectNickSkinMode = "STEVE";
            disguiseReconnectNickSkinRef = "";
        }
        saveConfig();
    }

    public static boolean isDisguiseHideLunarCosmeticsOnWeave() {
        if (!loaded) loadConfig();
        return disguiseHideLunarCosmeticsOnWeave;
    }

    public static void setDisguiseHideLunarCosmeticsOnWeave(boolean enabled) {
        if (!loaded) loadConfig();
        disguiseHideLunarCosmeticsOnWeave = enabled;
        saveConfigNow();
    }

    public static boolean isPlayerStandaloneCapeEnabled() {
        if (!loaded) loadConfig();
        return !getPlayerStandaloneCapeOptionKeyForCurrentAccount().isEmpty();
    }

    public static void setPlayerStandaloneCapeEnabled(boolean enabled) {
        if (!loaded) loadConfig();
        if (!enabled) {
            setPlayerStandaloneCapeSelection("");
        } else {
            playerStandaloneCapeEnabled = true;
            saveConfig();
        }
    }

    public static String getPlayerStandaloneCapeOptionKey() {
        if (!loaded) loadConfig();
        return getPlayerStandaloneCapeOptionKeyForCurrentAccount();
    }

    public static void setPlayerStandaloneCapeOptionKey(String key) {
        setPlayerStandaloneCapeSelection(key);
    }

    public static void setPlayerStandaloneCapeSelection(String optionKey) {
        if (!loaded) loadConfig();
        setPlayerStandaloneCapeSelectionForCurrentAccount(normalizeStandaloneCapeOptionKey(optionKey));
        saveConfig();
    }

    public static boolean isPlayerStandaloneCapeBroadcastToPeers() {
        if (!loaded) loadConfig();
        return playerStandaloneCapeBroadcastToPeers;
    }

    public static void setPlayerStandaloneCapeBroadcastToPeers(boolean broadcast) {
        playerStandaloneCapeBroadcastToPeers = broadcast;
        saveConfigNow();
    }

    public static boolean isPlayerStandaloneCapePreferGomodOnWeave() {
        if (!loaded) loadConfig();
        return playerStandaloneCapePreferGomodOnWeave;
    }

    /** Source preference used by the standalone cape picker on Forge and Weave. */
    public static boolean isPlayerStandaloneCapePreferGomod() {
        return isPlayerStandaloneCapePreferGomodOnWeave();
    }

    public static void setPlayerStandaloneCapePreferGomodOnWeave(boolean preferGomod) {
        if (!loaded) loadConfig();
        playerStandaloneCapePreferGomodOnWeave = preferGomod;
        saveConfigNow();
    }

    /** Source preference used by the standalone cape picker on Forge and Weave. */
    public static void setPlayerStandaloneCapePreferGomod(boolean preferGomod) {
        setPlayerStandaloneCapePreferGomodOnWeave(preferGomod);
    }

    /** Legacy migration hook; public Lunar now has the same configurable FullAccess preferences. */
    private static boolean applyPublicLunarDefaults() {
        return false; // Both Lunar flavors retain the saved FullAccess choices.
    }

    public static boolean isLunarTweaksEnabled() {
        if (!loaded) loadConfig();
        return lunarTweaksEnabled;
    }

    public static void setLunarTweaksEnabled(boolean enabled) {
        if (!loaded) loadConfig();
        boolean previous = lunarTweaksEnabled;
        lunarTweaksEnabled = enabled;
        saveConfigNow();
        GomodLogoColorSync.onFullAccessPreferenceChanged(enabled);
        LunarOnlineStatus.onFullAccessPreferenceChanged(enabled);
        LunarNametagDebugCapture.recordFullAccessToggle(previous, enabled);
    }

    public static boolean isLunarTweaksSeeGomodPlayers() {
        if (!loaded) loadConfig();
        return lunarTweaksSeeGomodPlayers;
    }

    public static void setLunarTweaksSeeGomodPlayers(boolean enabled) {
        if (!loaded) loadConfig();
        lunarTweaksSeeGomodPlayers = enabled;
        saveConfigNow();
    }

    public static boolean isLunarTweaksShowToOtherPlayers() {
        if (!loaded) loadConfig();
        return lunarTweaksShowToOtherPlayers;
    }

    public static void setLunarTweaksShowToOtherPlayers(boolean enabled) {
        if (!loaded) loadConfig();
        lunarTweaksShowToOtherPlayers = enabled;
        saveConfigNow();
    }

    public static boolean isLunarClothCloakEnabled() {
        if (!loaded) loadConfig();
        return lunarClothCloakEnabled;
    }

    public static void setLunarClothCloakEnabled(boolean enabled) {
        if (!loaded) loadConfig();
        lunarClothCloakEnabled = enabled;
        saveConfigNow();
    }

    public static boolean isLunarTweaksAllowMultipleSameTypeCosmetics() {
        if (!loaded) loadConfig();
        return lunarTweaksAllowMultipleSameTypeCosmetics;
    }

    public static void setLunarTweaksAllowMultipleSameTypeCosmetics(boolean enabled) {
        if (!loaded) loadConfig();
        lunarTweaksAllowMultipleSameTypeCosmetics = enabled;
        saveConfigNow();
    }

    public static boolean isLunarLimitedCosmeticsUnlockerEnabled() {
        if (!loaded) loadConfig();
        return lunarLimitedCosmeticsUnlockerEnabled;
    }

    public static void setLunarLimitedCosmeticsUnlockerEnabled(boolean enabled) {
        if (!loaded) loadConfig();
        lunarLimitedCosmeticsUnlockerEnabled = enabled;
        saveConfigNow();
    }

    public static boolean isLunarCosmeticSourceBadgesEnabled() {
        if (!loaded) loadConfig();
        return lunarCosmeticSourceBadgesEnabled;
    }

    public static void setLunarCosmeticSourceBadgesEnabled(boolean enabled) {
        if (!loaded) loadConfig();
        lunarCosmeticSourceBadgesEnabled = enabled;
        saveConfigNow();
    }

    public static synchronized List<String> getLunarTweaksHiddenCosmeticPlayers() {
        if (!loaded) loadConfig();
        return new ArrayList<String>(lunarTweaksHiddenCosmeticPlayers);
    }

    public static synchronized boolean addLunarTweaksHiddenCosmeticPlayer(String rawName) {
        if (!loaded) loadConfig();
        String name = normalizeLunarTweaksIgnoredCosmeticPlayer(rawName);
        if (name.isEmpty() || lunarTweaksHiddenCosmeticPlayers.size() >= 50
                || containsLunarTweaksIgnoredCosmeticPlayer(name)) {
            return false;
        }
        lunarTweaksHiddenCosmeticPlayers.add(name);
        saveConfigNow();
        return true;
    }

    public static synchronized boolean removeLunarTweaksHiddenCosmeticPlayer(String rawName) {
        if (!loaded) loadConfig();
        String name = normalizeLunarTweaksIgnoredCosmeticPlayer(rawName);
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < lunarTweaksHiddenCosmeticPlayers.size(); i++) {
            if (lunarTweaksHiddenCosmeticPlayers.get(i).equalsIgnoreCase(name)) {
                lunarTweaksHiddenCosmeticPlayers.remove(i);
                saveConfigNow();
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean isLunarTweaksHiddenCosmeticPlayer(String rawName) {
        if (!loaded) loadConfig();
        String name = normalizeLunarTweaksIgnoredCosmeticPlayer(rawName);
        return !name.isEmpty() && containsLunarTweaksIgnoredCosmeticPlayer(name);
    }

    /** Returns a trimmed exact Minecraft account name, or empty for invalid input. */
    public static String normalizeLunarTweaksIgnoredCosmeticPlayer(String rawName) {
        if (rawName == null) {
            return "";
        }
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > 16) {
            return "";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_') {
                return "";
            }
        }
        return name;
    }

    private static boolean containsLunarTweaksIgnoredCosmeticPlayer(String name) {
        for (String existing : lunarTweaksHiddenCosmeticPlayers) {
            if (existing != null && existing.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLunarPlusHideBadge() {
        if (!loaded) loadConfig();
        return LunarPlusAccountPreferences.isHidden(lunarPlusHideBadge);
    }

    public static void setLunarPlusHideBadge(boolean hidden) {
        if (!loaded) loadConfig();
        LunarPlusAccountPreferences.setHidden(hidden);
        // The legacy global field is migration input only. New choices live in account files.
        lunarPlusHideBadge = false;
        saveConfigNow();
    }

    public static boolean clearPlayerStandaloneCapeSelectionsForOptionKey(String optionKey) {
        if (!loaded) loadConfig();
        String normalized = normalizeStandaloneCapeOptionKey(optionKey);
        if (normalized.isEmpty()) {
            return false;
        }
        boolean changed = false;
        ArrayList<String> removeAccounts = new ArrayList<String>();
        for (Map.Entry<String, String> e : playerStandaloneCapeOptionKeyByAccount.entrySet()) {
            if (e != null && normalized.equals(normalizeStandaloneCapeOptionKey(e.getValue()))) {
                removeAccounts.add(e.getKey());
            }
        }
        for (String accountKey : removeAccounts) {
            playerStandaloneCapeOptionKeyByAccount.remove(accountKey);
            changed = true;
        }
        if (normalized.equals(normalizeStandaloneCapeOptionKey(playerStandaloneCapeOptionKey))) {
            playerStandaloneCapeEnabled = false;
            playerStandaloneCapeOptionKey = "";
            playerStandaloneCapeLegacySelectionPending = false;
            changed = true;
        }
        if (changed) {
            saveConfig();
        }
        return changed;
    }

    private static String getPlayerStandaloneCapeOptionKeyForCurrentAccount() {
        String accountKey = currentStandaloneCapeAccountKey();
        if (!accountKey.isEmpty()) {
            String stored = playerStandaloneCapeOptionKeyByAccount.get(accountKey);
            if (stored != null) {
                return mirrorPlayerStandaloneCapeSelection(normalizeStandaloneCapeOptionKey(stored));
            }
            String legacyKey = normalizeStandaloneCapeOptionKey(playerStandaloneCapeOptionKey);
            if (playerStandaloneCapeLegacySelectionPending && playerStandaloneCapeEnabled && !legacyKey.isEmpty()) {
                playerStandaloneCapeOptionKeyByAccount.put(accountKey, legacyKey);
                playerStandaloneCapeLegacySelectionPending = false;
                mirrorPlayerStandaloneCapeSelection(legacyKey);
                saveConfig();
                return legacyKey;
            }
            return mirrorPlayerStandaloneCapeSelection("");
        }
        if (playerStandaloneCapeEnabled) {
            return mirrorPlayerStandaloneCapeSelection(playerStandaloneCapeOptionKey);
        }
        return mirrorPlayerStandaloneCapeSelection("");
    }

    private static void setPlayerStandaloneCapeSelectionForCurrentAccount(String optionKey) {
        String normalized = normalizeStandaloneCapeOptionKey(optionKey);
        String accountKey = currentStandaloneCapeAccountKey();
        if (!accountKey.isEmpty()) {
            if (normalized.isEmpty()) {
                playerStandaloneCapeOptionKeyByAccount.remove(accountKey);
            } else {
                playerStandaloneCapeOptionKeyByAccount.put(accountKey, normalized);
            }
            playerStandaloneCapeLegacySelectionPending = false;
        }
        mirrorPlayerStandaloneCapeSelection(normalized);
    }

    private static String mirrorPlayerStandaloneCapeSelection(String optionKey) {
        String normalized = normalizeStandaloneCapeOptionKey(optionKey);
        playerStandaloneCapeOptionKey = normalized;
        playerStandaloneCapeEnabled = !normalized.isEmpty();
        return normalized;
    }

    private static String currentStandaloneCapeAccountKey() {
        try {
            return standaloneCapeAccountKey(Minecraft.getMinecraft());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String standaloneCapeAccountKey(Minecraft mc) {
        String uuid = sessionPlayerUuidUndashed(mc);
        if (!uuid.isEmpty()) {
            return "uuid:" + uuid;
        }

        String name = sessionUsername(mc);
        if (!name.isEmpty()) {
            return "name:" + name;
        }

        try {
            uuid = normalizeUndashedUuidToken(ClientPlayerIdentity.uuidUndashed(mc));
        } catch (Throwable ignored) {
        }
        if (!uuid.isEmpty()) {
            return "uuid:" + uuid;
        }

        try {
            name = normalizeAccountNameToken(ClientPlayerIdentity.displayName(mc));
        } catch (Throwable ignored) {
        }
        return name.isEmpty() ? "" : "name:" + name;
    }

    private static String sessionPlayerUuidUndashed(Minecraft mc) {
        try {
            if (mc != null && mc.getSession() != null) {
                return normalizeUndashedUuidToken(mc.getSession().getPlayerID());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String sessionUsername(Minecraft mc) {
        try {
            if (mc != null && mc.getSession() != null) {
                return normalizeAccountNameToken(mc.getSession().getUsername());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String normalizeStandaloneCapeAccountKey(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        if (s.startsWith("uuid:")) {
            String uuid = normalizeUndashedUuidToken(s.substring("uuid:".length()));
            return uuid.isEmpty() ? "" : "uuid:" + uuid;
        }
        if (s.startsWith("name:")) {
            String name = normalizeAccountNameToken(s.substring("name:".length()));
            return name.isEmpty() ? "" : "name:" + name;
        }
        String uuid = normalizeUndashedUuidToken(s);
        if (!uuid.isEmpty()) {
            return "uuid:" + uuid;
        }
        String name = normalizeAccountNameToken(s);
        return name.isEmpty() ? "" : "name:" + name;
    }

    private static String normalizeStandaloneCapeOptionKey(String optionKey) {
        return optionKey == null ? "" : optionKey.trim();
    }

    private static String readStandaloneCapeOptionKeyFromJson(JsonElement value) {
        try {
            if (value == null || value.isJsonNull()) {
                return "";
            }
            if (value.isJsonObject()) {
                JsonObject obj = value.getAsJsonObject();
                if (obj.has("enabled") && !obj.get("enabled").getAsBoolean()) {
                    return "";
                }
                if (obj.has("optionKey")) {
                    return normalizeStandaloneCapeOptionKey(obj.get("optionKey").getAsString());
                }
                if (obj.has("key")) {
                    return normalizeStandaloneCapeOptionKey(obj.get("key").getAsString());
                }
                return "";
            }
            return normalizeStandaloneCapeOptionKey(value.getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalizeUndashedUuidToken(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace("-", "").toLowerCase(Locale.ROOT);
        if (s.length() != 32) {
            return "";
        }
        boolean anyNonZero = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return "";
            }
            if (c != '0') {
                anyNonZero = true;
            }
        }
        return anyNonZero ? s : "";
    }

    private static String normalizeAccountNameToken(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String getDisguiseReconnectKind() {
        if (!loaded) loadConfig();
        return normalizeDisguiseReconnectKind(disguiseReconnectKind);
    }

    public static String getDisguiseReconnectPlayerName() {
        if (!loaded) loadConfig();
        return disguiseReconnectPlayerName == null ? "" : disguiseReconnectPlayerName;
    }

    public static String getDisguiseReconnectShareMode() {
        if (!loaded) loadConfig();
        return normalizeDisguiseReconnectShareMode(disguiseReconnectShareMode);
    }

    public static String getDisguiseReconnectFullLunarUuid() {
        if (!loaded) loadConfig();
        return disguiseReconnectFullLunarUuid == null ? "" : disguiseReconnectFullLunarUuid;
    }

    public static String getDisguiseReconnectNick() {
        if (!loaded) loadConfig();
        return disguiseReconnectNick == null ? "" : disguiseReconnectNick;
    }

    public static String getDisguiseReconnectNickSkinMode() {
        if (!loaded) loadConfig();
        return normalizeNickSkinMode(disguiseReconnectNickSkinMode);
    }

    public static String getDisguiseReconnectNickSkinRef() {
        if (!loaded) loadConfig();
        return disguiseReconnectNickSkinRef == null ? "" : disguiseReconnectNickSkinRef;
    }

    /**
     * Persist snapshot for restore-after-restart (no-op if toggle is off).
     */
    public static void saveDisguiseReconnectSnapshotPlayer(String playerName) {
        saveDisguiseReconnectSnapshotPlayer(playerName, "LOCAL", "");
    }

    public static void saveDisguiseReconnectSnapshotPlayer(String playerName, String shareMode,
                                                            String fullLunarUuid) {
        if (!loaded) loadConfig();
        if (!disguiseRestoreOnReconnect || playerName == null || playerName.trim().isEmpty()) {
            return;
        }
        disguiseReconnectKind = "PLAYER";
        disguiseReconnectPlayerName = playerName.trim();
        disguiseReconnectShareMode = normalizeDisguiseReconnectShareMode(shareMode);
        disguiseReconnectFullLunarUuid = "FULL_LOCAL".equals(disguiseReconnectShareMode)
                || "GOMOD_FULL_LUNAR".equals(disguiseReconnectShareMode)
                ? normalizeUndashedUuidToken(fullLunarUuid) : "";
        disguiseReconnectNick = "";
        disguiseReconnectNickRaw = "";
        disguiseReconnectNickSkinMode = "STEVE";
        disguiseReconnectNickSkinRef = "";
        saveConfig();
    }

    public static void saveDisguiseReconnectSnapshotNick(String nickPlain, String skinMode, String skinRef) {
        saveDisguiseReconnectSnapshotNick(nickPlain, skinMode, skinRef, null);
    }

    public static void saveDisguiseReconnectSnapshotNick(String nickPlain, String skinMode, String skinRef, String nickFieldRaw) {
        if (!loaded) loadConfig();
        if (!disguiseRestoreOnReconnect || nickPlain == null || nickPlain.trim().isEmpty()) {
            return;
        }
        disguiseReconnectKind = "NICK";
        disguiseReconnectPlayerName = "";
        disguiseReconnectShareMode = "LOCAL";
        disguiseReconnectFullLunarUuid = "";
        disguiseReconnectNick = nickPlain.trim();
        disguiseReconnectNickRaw = nickFieldRaw != null && !nickFieldRaw.trim().isEmpty()
                ? nickFieldRaw.trim()
                : nickPlain.trim();
        disguiseReconnectNickSkinMode = normalizeNickSkinMode(skinMode);
        disguiseReconnectNickSkinRef = skinRef == null ? "" : skinRef;
        saveConfig();
    }

    /** Clear the saved restore target without changing the restore-after-restart toggle. */
    public static void clearDisguiseReconnectSnapshot() {
        if (!loaded) loadConfig();
        disguiseReconnectKind = "OFF";
        disguiseReconnectPlayerName = "";
        disguiseReconnectShareMode = "LOCAL";
        disguiseReconnectFullLunarUuid = "";
        disguiseReconnectNick = "";
        disguiseReconnectNickRaw = "";
        disguiseReconnectNickSkinMode = "STEVE";
        disguiseReconnectNickSkinRef = "";
        saveConfig();
    }

    /** Text to pass back into {@link me.napim.gomod.features.LocalDisguise#enableNickDisguise} on restore. */
    public static String getDisguiseReconnectNickInputForRestore() {
        if (!loaded) loadConfig();
        if (disguiseReconnectNickRaw != null && !disguiseReconnectNickRaw.isEmpty()) {
            return disguiseReconnectNickRaw;
        }
        return disguiseReconnectNick == null ? "" : disguiseReconnectNick;
    }

    private static String normalizeDisguiseReconnectKind(String kind) {
        if (kind == null) {
            return "OFF";
        }
        switch (kind.trim().toUpperCase(Locale.ROOT)) {
            case "PLAYER":
                return "PLAYER";
            case "NICK":
                return "NICK";
            default:
                return "OFF";
        }
    }

    private static String normalizeDisguiseReconnectShareMode(String mode) {
        if (mode == null) return "LOCAL";
        switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "GOMOD_NAME": return "GOMOD_NAME";
            case "FULL_LOCAL": return "FULL_LOCAL";
            case "GOMOD_FULL_LUNAR": return "GOMOD_FULL_LUNAR";
            default: return "LOCAL";
        }
    }

    private static String normalizeNickSkinMode(String mode) {
        if (mode == null) {
            return "STEVE";
        }
        switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "PLAYER_SKIN":
            case "PLAYER":
                return "PLAYER_SKIN";
            case "SKIN_FILE":
            case "FILE":
                return "SKIN_FILE";
            default:
                return "STEVE";
        }
    }

    private static String normalizeDisguiseToggleMode(String mode) {
        if ("FORCE_ON".equalsIgnoreCase(mode)) {
            return "FORCE_ON";
        }
        if ("FORCE_OFF".equalsIgnoreCase(mode)) {
            return "FORCE_OFF";
        }
        return "FOLLOW";
    }

    private static String normalizeDisguiseTournamentKillCounterMode(String mode) {
        return normalizeDisguiseToggleMode(mode);
    }

    private static String normalizeDisguiseRenamedItemsMode(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "UNCHANGED";
        }
        String u = mode.trim().toUpperCase(Locale.ROOT)
                .replace('\'', ' ')
                .replace('-', '_')
                .replace(' ', '_');
        if ("TARGET".equals(u) || "TARGETS".equals(u) || "TARGET_S".equals(u)) {
            return "TARGET";
        }
        if ("RESETED".equals(u) || "RESET".equals(u) || "ENABLED".equals(u) || "ON".equals(u) || "TRUE".equals(u)) {
            return "RESETED";
        }
        if ("UNCHANGED".equals(u) || "DISABLED".equals(u) || "OFF".equals(u) || "FALSE".equals(u)) {
            return "UNCHANGED";
        }
        return "UNCHANGED";
    }

    private static String normalizeDisguiseHypixelPrefixMode(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "SHOW";
        }
        String u = mode.trim();
        if ("DEFAULT".equalsIgnoreCase(u)) {
            return "SHOW";
        }
        if ("HIDE".equalsIgnoreCase(u)) {
            return "HIDE";
        }
        if ("HYPIXEL_OFFICIAL".equalsIgnoreCase(u) || "OFFICIAL".equalsIgnoreCase(u)) {
            return "HYPIXEL_OFFICIAL";
        }
        if ("CUSTOM".equalsIgnoreCase(u)) {
            return "CUSTOM";
        }
        return "SHOW";
    }

    private static String normalizeDisguiseHypixelRankPreset(String preset) {
        if (preset == null || preset.isEmpty()) {
            return "TARGET";
        }
        switch (preset.trim().toUpperCase(Locale.ROOT)) {
            case "TARGET":
                return "TARGET";
            case "NONE":
            case "NORANK":
                return "NONE";
            case "VIP":
                return "VIP";
            case "VIP_PLUS":
            case "VIP+":
                return "VIP_PLUS";
            case "MVP":
                return "MVP";
            case "MVP_PLUS":
            case "MVP+":
                return "MVP_PLUS";
            case "MVP_PLUS_PLUS":
            case "MVP++":
                return "MVP_PLUS_PLUS";
            case "YOUTUBER":
            case "YOUTUBE":
                return "YOUTUBER";
            case "OWNER":
                return "OWNER";
            case "ADMIN":
                return "ADMIN";
            case "GM":
                return "GM";
            case "MOJANG":
                return "MOJANG";
            case "EVENTS":
                return "EVENTS";
            case "INNIT":
                return "INNIT";
            case "PIG":
            case "PIG+++":
            case "PIG_PLUS_PLUS_PLUS":
                return "PIG_PLUS_PLUS_PLUS";
            case "CUSTOM":
                return "CUSTOM";
            default:
                return "TARGET";
        }
    }

    private static String normalizeHypixelRankColorName(String color) {
        if (color == null || color.isEmpty()) {
            return "GOLD";
        }
        switch (color.trim().toUpperCase(Locale.ROOT).replace(' ', '_')) {
            case "RED":
                return "RED";
            case "BLUE":
                return "BLUE";
            case "GREEN":
                return "GREEN";
            case "YELLOW":
                return "YELLOW";
            case "GOLD":
                return "GOLD";
            case "AQUA":
                return "AQUA";
            case "LIGHT_PURPLE":
            case "LIGHTPURPLE":
                return "LIGHT_PURPLE";
            case "DARK_PURPLE":
            case "DARKPURPLE":
                return "DARK_PURPLE";
            case "WHITE":
                return "WHITE";
            case "GRAY":
            case "GREY":
                return "GRAY";
            case "DARK_GRAY":
            case "DARKGRAY":
                return "DARK_GRAY";
            case "BLACK":
                return "BLACK";
            default:
                return "GOLD";
        }
    }

    private static String normalizeHypixelMvpPlusPlusColorName(String color) {
        if (color == null || color.isEmpty()) {
            return "GOLD";
        }
        switch (color.trim().toUpperCase(Locale.ROOT).replace(' ', '_')) {
            case "DEFAULT":
                return "DEFAULT";
            case "DARK_PURPLE":
                return "DARK_PURPLE";
            case "LIGHT_PURPLE":
                return "LIGHT_PURPLE";
            case "DARK_AQUA":
                return "DARK_AQUA";
            case "AQUA":
                return "AQUA";
            case "RED":
                return "RED";
            case "GOLD":
                return "GOLD";
            case "GREEN":
                return "GREEN";
            case "YELLOW":
                return "YELLOW";
            case "WHITE":
                return "WHITE";
            case "BLUE":
                return "BLUE";
            case "DARK_GREEN":
                return "DARK_GREEN";
            case "DARK_RED":
                return "DARK_RED";
            case "DARK_GRAY":
                return "DARK_GRAY";
            case "BLACK":
                return "BLACK";
            case "DARK_BLUE":
                return "DARK_BLUE";
            default:
                return "GOLD";
        }
    }

    private static String normalizeKillEffectTabMode(String mode) {
        if (mode == null) {
            return "COLORED_NAME";
        }
        String u = mode.trim().toUpperCase(Locale.ROOT);
        if ("COLORED_NAME_TAB".equals(u)) {
            return "COLORED_NAME_TAB";
        }
        if ("COLORED_NAME_TAGS".equals(u)) {
            return "COLORED_NAME_TAGS";
        }
        if ("ICON".equals(u)) {
            return "ICON";
        }
        if ("OFF".equals(u) || "DISABLED".equals(u) || "NONE".equals(u)) {
            return "OFF";
        }
        if ("COLORED_NAME".equals(u) || "COLORED_NAME_TAB_TAGS".equals(u)
                || "COLOR".equals(u) || "NAME".equals(u) || "COLORED".equals(u)) {
            return "COLORED_NAME";
        }
        return "COLORED_NAME";
    }

    private static String normalizeKillCounterTabDisplayMode(String mode) {
        if (mode == null) {
            return "AFTER_NAME";
        }
        String u = mode.trim().toUpperCase(Locale.ROOT);
        if ("BEFORE_NAME".equals(u) || "BEFORE".equals(u) || "LEFT_OF_HEAD".equals(u)) {
            return "BEFORE_NAME";
        }
        return "AFTER_NAME";
    }

    private static boolean readJsonBoolPreferKey(JsonObject cfg, String primaryKey, String legacyKey, boolean def) {
        if (cfg.has(primaryKey)) {
            return cfg.get(primaryKey).getAsBoolean();
        }
        if (cfg.has(legacyKey)) {
            return cfg.get(legacyKey).getAsBoolean();
        }
        return def;
    }

    private static int readJsonIntPreferKey(JsonObject cfg, String primaryKey, String legacyKey, int def) {
        if (cfg.has(primaryKey)) {
            return cfg.get(primaryKey).getAsInt();
        }
        if (cfg.has(legacyKey)) {
            return cfg.get(legacyKey).getAsInt();
        }
        return def;
    }

    private static float readJsonFloatPreferKey(JsonObject cfg, String primaryKey, String legacyKey, float def) {
        if (cfg.has(primaryKey)) {
            return cfg.get(primaryKey).getAsFloat();
        }
        if (cfg.has(legacyKey)) {
            return cfg.get(legacyKey).getAsFloat();
        }
        return def;
    }

    private static void loadMoreHudFromConfig(JsonObject root) {
        if (root == null || !root.has("moreHud") || !root.get("moreHud").isJsonObject()) {
            return;
        }
        JsonObject mh = root.getAsJsonObject("moreHud");
        moreHudOffsetCenterX = mh.has("offsetCenterX") ? mh.get("offsetCenterX").getAsInt() : moreHudOffsetCenterX;
        moreHudPosY = mh.has("posY") ? mh.get("posY").getAsInt() : moreHudPosY;
        moreHudRowGap = mh.has("rowGap") ? Math.max(0, mh.get("rowGap").getAsInt()) : moreHudRowGap;
        moreHudScale = mh.has("scale") ? Math.max(0.5f, Math.min(3.0f, mh.get("scale").getAsFloat())) : moreHudScale;
        // Master stack always on; per-module toggles live in the HUD editor.
        moreHudEnabled = true;
        if (!mh.has("fpsPosY")) {
            int y = moreHudPosY;
            int fh = 9;
            int g = moreHudRowGap;
            moreHudFpsPosY = y;
            y += fh + g;
            moreHudCpsPosY = y;
            y += fh + g;
            moreHudPingPosY = y;
            y += fh + g;
            moreHudPotionsPosY = y;
            y += fh + g;
            moreHudArrowsPosY = y;
            y += fh + g;
            moreHudFlamePosY = y;
        } else {
            moreHudFpsPosY = mh.get("fpsPosY").getAsInt();
            int step = 9 + moreHudRowGap;
            moreHudCpsPosY = mh.has("cpsPosY") ? mh.get("cpsPosY").getAsInt() : moreHudFpsPosY + step;
            moreHudPingPosY = mh.has("pingPosY") ? mh.get("pingPosY").getAsInt() : moreHudCpsPosY + step;
            moreHudPotionsPosY = mh.has("potionsPosY") ? mh.get("potionsPosY").getAsInt() : moreHudPingPosY + step;
            moreHudArrowsPosY = mh.has("arrowsPosY") ? mh.get("arrowsPosY").getAsInt() : moreHudPotionsPosY + step;
            moreHudFlamePosY = mh.has("flamePosY") ? mh.get("flamePosY").getAsInt() : moreHudArrowsPosY + step;
        }
        moreHudFpsOffsetCenterX = mh.has("fpsOffsetCenterX") ? mh.get("fpsOffsetCenterX").getAsInt() : moreHudFpsOffsetCenterX;
        moreHudCpsOffsetCenterX = mh.has("cpsOffsetCenterX") ? mh.get("cpsOffsetCenterX").getAsInt() : moreHudCpsOffsetCenterX;
        moreHudPingOffsetCenterX = mh.has("pingOffsetCenterX") ? mh.get("pingOffsetCenterX").getAsInt() : moreHudPingOffsetCenterX;
        moreHudPotionsOffsetCenterX = mh.has("potionsOffsetCenterX") ? mh.get("potionsOffsetCenterX").getAsInt() : moreHudPotionsOffsetCenterX;
        moreHudArrowsOffsetCenterX = mh.has("arrowsOffsetCenterX") ? mh.get("arrowsOffsetCenterX").getAsInt() : moreHudArrowsOffsetCenterX;
        moreHudFlameOffsetCenterX = mh.has("flameOffsetCenterX") ? mh.get("flameOffsetCenterX").getAsInt() : moreHudFlameOffsetCenterX;
        moreHudFps = !mh.has("fps") || mh.get("fps").getAsBoolean();
        moreHudFpsShadow = !mh.has("fpsShadow") || mh.get("fpsShadow").getAsBoolean();
        moreHudFpsBg = mh.has("fpsBg") ? clamp255(mh.get("fpsBg").getAsInt()) : moreHudFpsBg;
        if (mh.has("fpsBgColor")) {
            moreHudFpsBgColor = mh.get("fpsBgColor").getAsInt() & 0xFFFFFF;
        }
        moreHudFpsPrefixColor = mh.has("fpsPrefixColor") ? mh.get("fpsPrefixColor").getAsInt() : moreHudFpsPrefixColor;
        moreHudFpsColor = mh.has("fpsColor") ? mh.get("fpsColor").getAsInt() : moreHudFpsColor;
        moreHudFpsDisplayStyle = mh.has("fpsDisplayStyle")
                ? Math.max(0, Math.min(4, mh.get("fpsDisplayStyle").getAsInt())) : moreHudFpsDisplayStyle;
        if (mh.has("fpsCustomLabel")) {
            moreHudFpsCustomLabel = sanitizeMoreHudFpsCustomLabel(mh.get("fpsCustomLabel").getAsString());
        }
        moreHudCps = !mh.has("cps") || mh.get("cps").getAsBoolean();
        moreHudCpsShadow = !mh.has("cpsShadow") || mh.get("cpsShadow").getAsBoolean();
        moreHudCpsBg = mh.has("cpsBg") ? clamp255(mh.get("cpsBg").getAsInt()) : moreHudCpsBg;
        if (mh.has("cpsBgColor")) {
            moreHudCpsBgColor = mh.get("cpsBgColor").getAsInt() & 0xFFFFFF;
        }
        moreHudCpsColor = mh.has("cpsColor") ? mh.get("cpsColor").getAsInt() : moreHudCpsColor;
        moreHudCpsPrefixColor = mh.has("cpsPrefixColor") ? mh.get("cpsPrefixColor").getAsInt() : moreHudCpsPrefixColor;
        if (mh.has("cpsSeparatorColor")) {
            moreHudCpsSeparatorColor = mh.get("cpsSeparatorColor").getAsInt();
        } else if (mh.has("cpsPrefixColor")) {
            moreHudCpsSeparatorColor = moreHudCpsPrefixColor;
        }
        moreHudCpsMode = mh.has("cpsMode") ? Math.max(0, Math.min(2, mh.get("cpsMode").getAsInt())) : moreHudCpsMode;
        moreHudCpsSquareBrackets = mh.has("cpsBrackets") && mh.get("cpsBrackets").getAsBoolean();
        moreHudPing = !mh.has("ping") || mh.get("ping").getAsBoolean();
        moreHudPingShadow = !mh.has("pingShadow") || mh.get("pingShadow").getAsBoolean();
        moreHudPingBg = mh.has("pingBg") ? clamp255(mh.get("pingBg").getAsInt()) : moreHudPingBg;
        if (mh.has("pingBgColor")) {
            moreHudPingBgColor = mh.get("pingBgColor").getAsInt() & 0xFFFFFF;
        }
        moreHudPingColor = mh.has("pingColor") ? mh.get("pingColor").getAsInt() : moreHudPingColor;
        moreHudPingPrefixColor = mh.has("pingPrefixColor") ? mh.get("pingPrefixColor").getAsInt() : moreHudPingPrefixColor;
        if (mh.has("pingDisplayStyle")) {
            moreHudPingDisplayStyle = Math.max(0, Math.min(3, mh.get("pingDisplayStyle").getAsInt()));
        } else if (mh.has("pingPrefix")) {
            moreHudPingDisplayStyle = mh.get("pingPrefix").getAsBoolean() ? 1 : 0;
        }
        if (mh.has("pingCustomLabel")) {
            moreHudPingCustomLabel = sanitizeMoreHudPingCustomLabel(mh.get("pingCustomLabel").getAsString());
        }
        moreHudPotions = !mh.has("potions") || mh.get("potions").getAsBoolean();
        moreHudPotionsShadow = !mh.has("potionsShadow") || mh.get("potionsShadow").getAsBoolean();
        moreHudPotionsBg = mh.has("potionsBg") ? clamp255(mh.get("potionsBg").getAsInt()) : moreHudPotionsBg;
        if (mh.has("potionsBgColor")) {
            moreHudPotionsBgColor = mh.get("potionsBgColor").getAsInt() & 0xFFFFFF;
        }
        moreHudPotionsColor = mh.has("potionsColor") ? mh.get("potionsColor").getAsInt() : moreHudPotionsColor;
        moreHudPotionsEffectColors = !mh.has("potionsEffectColors") || mh.get("potionsEffectColors").getAsBoolean();
        moreHudPotionsDurationColor = mh.has("potionsDurationColor")
                ? mh.get("potionsDurationColor").getAsInt() & 0xFFFFFF
                : moreHudPotionsDurationColor;
        loadMoreHudPotionsEffectColorById(mh);
        moreHudArrows = !mh.has("arrows") || mh.get("arrows").getAsBoolean();
        moreHudArrowsShadow = !mh.has("arrowsShadow") || mh.get("arrowsShadow").getAsBoolean();
        moreHudArrowsBg = mh.has("arrowsBg") ? clamp255(mh.get("arrowsBg").getAsInt()) : moreHudArrowsBg;
        if (mh.has("arrowsBgColor")) {
            moreHudArrowsBgColor = mh.get("arrowsBgColor").getAsInt() & 0xFFFFFF;
        }
        moreHudArrowsColor = mh.has("arrowsColor") ? mh.get("arrowsColor").getAsInt() : moreHudArrowsColor;
        if (mh.has("arrowsZeroColor")) {
            moreHudArrowsZeroColor = mh.get("arrowsZeroColor").getAsInt();
        } else if (mh.has("arrowsColor")) {
            moreHudArrowsZeroColor = 0xFF5555;
        }
        moreHudArrowsOverlayDx = mh.has("arrowsOverlayDx") ? mh.get("arrowsOverlayDx").getAsInt() : moreHudArrowsOverlayDx;
        moreHudArrowsOverlayDy = mh.has("arrowsOverlayDy") ? mh.get("arrowsOverlayDy").getAsInt() : moreHudArrowsOverlayDy;
        moreHudArmorStatus = !mh.has("armorStatus") || mh.get("armorStatus").getAsBoolean();
        moreHudArmorStatusShadow = !mh.has("armorStatusShadow") || mh.get("armorStatusShadow").getAsBoolean();
        moreHudArmorStatusBg = mh.has("armorStatusBg") ? clamp255(mh.get("armorStatusBg").getAsInt()) : moreHudArmorStatusBg;
        if (mh.has("armorStatusBgColor")) {
            moreHudArmorStatusBgColor = mh.get("armorStatusBgColor").getAsInt() & 0xFFFFFF;
        }
        moreHudArmorStatusPosY = mh.has("armorStatusPosY") ? mh.get("armorStatusPosY").getAsInt() : moreHudArmorStatusPosY;
        moreHudArmorStatusOffsetCenterX = mh.has("armorStatusOffsetCenterX")
                ? mh.get("armorStatusOffsetCenterX").getAsInt()
                : moreHudArmorStatusOffsetCenterX;
        moreHudArmorStatusScale = mh.has("armorStatusScale")
                ? clampMoreHudRowScale(mh.get("armorStatusScale").getAsFloat())
                : moreHudArmorStatusScale;
        moreHudArmorStatusTextScale = mh.has("armorStatusTextScale")
                ? clampMoreHudArmorStatusTextScale(mh.get("armorStatusTextScale").getAsFloat())
                : moreHudArmorStatusTextScale;
        moreHudArmorStatusDuraMode = mh.has("armorStatusDuraMode")
                ? mh.get("armorStatusDuraMode").getAsInt()
                : moreHudArmorStatusDuraMode;
        moreHudArmorStatusDuraRight = !mh.has("armorStatusDuraRight") || mh.get("armorStatusDuraRight").getAsBoolean();
        moreHudArmorStatusDurabilityBar = !mh.has("armorStatusDurabilityBar")
                || mh.get("armorStatusDurabilityBar").getAsBoolean();
        if (mh.has("armorStatusLayout")) {
            moreHudArmorStatusLayout = clampMoreHudArmorStatusLayout(mh.get("armorStatusLayout").getAsInt());
        } else {
            boolean legacyVertical = !mh.has("armorStatusVertical") || mh.get("armorStatusVertical").getAsBoolean();
            moreHudArmorStatusLayout = legacyVertical
                    ? MoreHudArmorStatusFeature.LayoutMode.VERTICAL.getIndex()
                    : MoreHudArmorStatusFeature.LayoutMode.HORIZONTAL.getIndex();
        }
        moreHudArmorStatusVertical = MoreHudArmorStatusFeature.LayoutMode.fromIndex(moreHudArmorStatusLayout).isVertical();
        moreHudArmorStatusChroma = mh.has("armorStatusChroma") && mh.get("armorStatusChroma").getAsBoolean();
        moreHudArmorStatusShowHelmet = !mh.has("armorStatusShowHelmet") || mh.get("armorStatusShowHelmet").getAsBoolean();
        moreHudArmorStatusShowChest = !mh.has("armorStatusShowChest") || mh.get("armorStatusShowChest").getAsBoolean();
        moreHudArmorStatusShowLegs = !mh.has("armorStatusShowLegs") || mh.get("armorStatusShowLegs").getAsBoolean();
        moreHudArmorStatusShowBoots = !mh.has("armorStatusShowBoots") || mh.get("armorStatusShowBoots").getAsBoolean();
        moreHudArmorStatusShowHeld = !mh.has("armorStatusShowHeld") || mh.get("armorStatusShowHeld").getAsBoolean();
        if (mh.has("armorStatusHeldPlacement")) {
            int p = mh.get("armorStatusHeldPlacement").getAsInt();
            moreHudArmorStatusHeldPlacement = (p >= MORE_HUD_ARMOR_HELD_DOWN && p <= MORE_HUD_ARMOR_HELD_OFF) ? p
                    : MORE_HUD_ARMOR_HELD_DOWN;
        } else if (!moreHudArmorStatusShowHeld) {
            moreHudArmorStatusHeldPlacement = MORE_HUD_ARMOR_HELD_OFF;
        }
        moreHudArmorStatusShowHeld = moreHudArmorStatusHeldPlacement != MORE_HUD_ARMOR_HELD_OFF;
        int legacyArmorGap = mh.has("armorStatusArmorSlotGap")
                ? mh.get("armorStatusArmorSlotGap").getAsInt()
                : moreHudArmorStatusArmorSlotGap;
        if (mh.has("armorStatusArmorSlotGapVertical")) {
            moreHudArmorStatusArmorSlotGapVertical = clampMoreHudArmorStatusArmorSlotGapForLayout(
                    mh.get("armorStatusArmorSlotGapVertical").getAsInt(), true);
        } else if (moreHudArmorStatusVertical) {
            moreHudArmorStatusArmorSlotGapVertical = clampMoreHudArmorStatusArmorSlotGapForLayout(legacyArmorGap, true);
        } else {
            moreHudArmorStatusArmorSlotGapVertical = MoreHudArmorStatusFeature.ARMOR_SLOT_GAP_DEFAULT;
        }
        if (mh.has("armorStatusArmorSlotGapHorizontal")) {
            moreHudArmorStatusArmorSlotGapHorizontal = clampMoreHudArmorStatusArmorSlotGapForLayout(
                    mh.get("armorStatusArmorSlotGapHorizontal").getAsInt(), false);
        } else if (!moreHudArmorStatusVertical) {
            moreHudArmorStatusArmorSlotGapHorizontal = clampMoreHudArmorStatusArmorSlotGapForLayout(legacyArmorGap, false);
        } else {
            moreHudArmorStatusArmorSlotGapHorizontal = MoreHudArmorStatusFeature.ARMOR_SLOT_GAP_HORIZONTAL_MAX;
        }
        if (mh.has("armorStatusArmorSlotGap")) {
            moreHudArmorStatusArmorSlotGap = clampMoreHudArmorStatusArmorSlotGap(mh.get("armorStatusArmorSlotGap").getAsInt());
        } else {
            syncLegacyActiveArmorStatusGap();
        }
        if (mh.has("armorStatusDuraColor1")) {
            moreHudArmorStatusDuraColor1 = mh.get("armorStatusDuraColor1").getAsInt() & 0xFFFFFF;
        }
        if (mh.has("armorStatusDuraColor2")) {
            moreHudArmorStatusDuraColor2 = mh.get("armorStatusDuraColor2").getAsInt() & 0xFFFFFF;
        }
        if (mh.has("armorStatusDuraColor3")) {
            moreHudArmorStatusDuraColor3 = mh.get("armorStatusDuraColor3").getAsInt() & 0xFFFFFF;
        }
        if (mh.has("armorStatusDuraColor4")) {
            moreHudArmorStatusDuraColor4 = mh.get("armorStatusDuraColor4").getAsInt() & 0xFFFFFF;
        }
        moreHudFlame = !mh.has("flame") || mh.get("flame").getAsBoolean();
        moreHudKillEffects = !mh.has("killEffects") || mh.get("killEffects").getAsBoolean();
        if (mh.has("killEffectsBg")) {
            moreHudKillEffectsBg = clamp255(mh.get("killEffectsBg").getAsInt());
        } else if (mh.has("flameBg")) {
            moreHudKillEffectsBg = clamp255(mh.get("flameBg").getAsInt());
        }
        if (mh.has("killEffectsShadow")) {
            moreHudKillEffectsShadow = mh.get("killEffectsShadow").getAsBoolean();
        } else {
            moreHudKillEffectsShadow = !mh.has("flameShadow") || mh.get("flameShadow").getAsBoolean();
        }
        moreHudFlameShadow = !mh.has("flameShadow") || mh.get("flameShadow").getAsBoolean();
        moreHudFlameBg = 0;
        if (mh.has("flameBgColor")) {
            moreHudFlameBgColor = mh.get("flameBgColor").getAsInt() & 0xFFFFFF;
        }
        if (mh.has("killEffectsBgColor")) {
            moreHudKillEffectsBgColor = mh.get("killEffectsBgColor").getAsInt() & 0xFFFFFF;
        } else if (mh.has("flameBgColor")) {
            moreHudKillEffectsBgColor = moreHudFlameBgColor;
        }
        moreHudFlameColor = mh.has("flameColor") ? mh.get("flameColor").getAsInt() : moreHudFlameColor;
        moreHudKillEffectSpeedColor = mh.has("killEffectSpeedColor")
                ? mh.get("killEffectSpeedColor").getAsInt()
                : moreHudKillEffectSpeedColor;
        moreHudKillEffectResistanceColor = mh.has("killEffectResistanceColor")
                ? mh.get("killEffectResistanceColor").getAsInt()
                : moreHudKillEffectResistanceColor;
        moreHudKillEffectRegenerationColor = mh.has("killEffectRegenerationColor")
                ? mh.get("killEffectRegenerationColor").getAsInt()
                : moreHudKillEffectRegenerationColor;
        moreHudFlameTimerBar = mh.has("flameTimerBar") && mh.get("flameTimerBar").getAsBoolean();
        float s0 = moreHudScale;
        moreHudFpsScale = mh.has("fpsScale") ? clampMoreHudRowScale(mh.get("fpsScale").getAsFloat()) : s0;
        moreHudCpsScale = mh.has("cpsScale") ? clampMoreHudRowScale(mh.get("cpsScale").getAsFloat()) : s0;
        moreHudPingScale = mh.has("pingScale") ? clampMoreHudRowScale(mh.get("pingScale").getAsFloat()) : s0;
        moreHudPotionsScale = mh.has("potionsScale") ? clampMoreHudRowScale(mh.get("potionsScale").getAsFloat()) : s0;
        moreHudArrowsScale = mh.has("arrowsScale") ? clampMoreHudRowScale(mh.get("arrowsScale").getAsFloat()) : s0;
        moreHudFlameScale = mh.has("flameScale") ? clampMoreHudRowScale(mh.get("flameScale").getAsFloat()) : s0;
        moreHudKillEffectsScale = mh.has("killEffectsScale")
                ? clampMoreHudRowScale(mh.get("killEffectsScale").getAsFloat())
                : s0;
        moreHudKillEffectsIconScale = mh.has("killEffectsIconScale")
                ? clampMoreHudRowScale(mh.get("killEffectsIconScale").getAsFloat())
                : 1.0f;
        moreHudKillEffectsTimerScale = mh.has("killEffectsTimerScale")
                ? clampMoreHudRowScale(mh.get("killEffectsTimerScale").getAsFloat())
                : 1.0f;
        moreHudKillEffectsIconNudgeX = mh.has("killEffectsIconNx") ? mh.get("killEffectsIconNx").getAsInt() : 0;
        moreHudKillEffectsIconNudgeY = mh.has("killEffectsIconNy") ? mh.get("killEffectsIconNy").getAsInt() : 0;
        moreHudKillEffectsTimerNudgeX = mh.has("killEffectsTimerNx") ? mh.get("killEffectsTimerNx").getAsInt() : 0;
        moreHudKillEffectsTimerNudgeY = mh.has("killEffectsTimerNy") ? mh.get("killEffectsTimerNy").getAsInt() : 0;
        moreHudFlameCompact = mh.has("flameCompact") && mh.get("flameCompact").getAsBoolean();
        moreHudFlameCompactPosY = mh.has("flameCompactPosY") ? mh.get("flameCompactPosY").getAsInt() : moreHudFlamePosY;
        moreHudFlameCompactOffsetCenterX = mh.has("flameCompactOffsetCenterX")
                ? mh.get("flameCompactOffsetCenterX").getAsInt()
                : moreHudFlameCompactOffsetCenterX;
        moreHudFlameCompactScale = mh.has("flameCompactScale")
                ? clampMoreHudRowScale(mh.get("flameCompactScale").getAsFloat())
                : moreHudFlameCompactScale;
        moreHudFlameCompactIconNudgeX = mh.has("flameCompactIconNx") ? mh.get("flameCompactIconNx").getAsInt() : moreHudFlameCompactIconNudgeX;
        moreHudFlameCompactIconNudgeY = mh.has("flameCompactIconNy") ? mh.get("flameCompactIconNy").getAsInt() : moreHudFlameCompactIconNudgeY;
        moreHudFlameCompactTimerNudgeX = mh.has("flameCompactTimerNx") ? mh.get("flameCompactTimerNx").getAsInt() : moreHudFlameCompactTimerNudgeX;
        moreHudFlameCompactTimerNudgeY = mh.has("flameCompactTimerNy") ? mh.get("flameCompactTimerNy").getAsInt() : moreHudFlameCompactTimerNudgeY;
        moreHudFlameCompactIconScale = mh.has("flameCompactIconScale")
                ? clampMoreHudRowScale(mh.get("flameCompactIconScale").getAsFloat())
                : moreHudFlameCompactIconScale;
        moreHudFlameCompactTimerScale = mh.has("flameCompactTimerScale")
                ? clampMoreHudRowScale(mh.get("flameCompactTimerScale").getAsFloat())
                : moreHudFlameCompactTimerScale;
        if (mh.has("potionsShowFlamingArrows")) {
            moreHudPotionsShowFlamingArrows = mh.get("potionsShowFlamingArrows").getAsBoolean();
        } else {
            moreHudPotionsShowFlamingArrows = !moreHudFlameCompact;
        }
        if (moreHudPotionsShowFlamingArrows == moreHudFlameCompact) {
            moreHudPotionsShowFlamingArrows = !moreHudFlameCompact;
        }
        if (mh.has("potionsDecimalEffectTimer")) {
            moreHudPotionsDecimalEffectTimer = mh.get("potionsDecimalEffectTimer").getAsBoolean();
        }
        // potionsBgAttached ignored — separated background style removed; always attached.
        moreHudCoords = !mh.has("coords") || mh.get("coords").getAsBoolean();
        moreHudCoordsShadow = !mh.has("coordsShadow") || mh.get("coordsShadow").getAsBoolean();
        moreHudCoordsBg = mh.has("coordsBg") ? clamp255(mh.get("coordsBg").getAsInt()) : moreHudCoordsBg;
        if (mh.has("coordsBgColor")) {
            moreHudCoordsBgColor = mh.get("coordsBgColor").getAsInt() & 0xFFFFFF;
        }
        if (mh.has("coordsPrefixColor")) {
            moreHudCoordsPrefixColor = mh.get("coordsPrefixColor").getAsInt();
        }
        if (mh.has("coordsTextColor")) {
            moreHudCoordsTextColor = mh.get("coordsTextColor").getAsInt();
        }
        if (mh.has("coordsBiomeColor")) {
            moreHudCoordsBiomeColor = mh.get("coordsBiomeColor").getAsInt();
        }
        if (mh.has("coordsDirectionColor")) {
            moreHudCoordsDirectionColor = mh.get("coordsDirectionColor").getAsInt();
        }
        if (mh.has("coordsCompassRingColor")) {
            moreHudCoordsCompassRingColor = mh.get("coordsCompassRingColor").getAsInt();
        } else if (mh.has("coordsDirectionColor")) {
            moreHudCoordsCompassRingColor = dimMoreHudCoordsRgb(moreHudCoordsDirectionColor, 0.55f);
        }
        if (mh.has("coordsCompassDotColor")) {
            moreHudCoordsCompassDotColor = mh.get("coordsCompassDotColor").getAsInt();
        }
        if (mh.has("coordsLayout")) {
            moreHudCoordsLayout = Math.max(0, Math.min(2, mh.get("coordsLayout").getAsInt()));
        } else {
            moreHudCoordsLayout = !mh.has("coordsVertical") || mh.get("coordsVertical").getAsBoolean()
                    ? COORDS_LAYOUT_VERTICAL
                    : COORDS_LAYOUT_COMPASS;
        }
        moreHudCoordsBiome = !mh.has("coordsBiome") || mh.get("coordsBiome").getAsBoolean();
        moreHudCoordsDirection = !mh.has("coordsDirection") || mh.get("coordsDirection").getAsBoolean();
        if (mh.has("coordsShowDuringF3")) {
            moreHudCoordsShowDuringF3 = mh.get("coordsShowDuringF3").getAsBoolean();
        }
        moreHudCoordsShowFace = !mh.has("coordsShowFace") || mh.get("coordsShowFace").getAsBoolean();
        if (mh.has("coordsPrefixStyle")) {
            int style = mh.get("coordsPrefixStyle").getAsInt();
            int schema = mh.has("coordsPrefixStyleSchema")
                    ? mh.get("coordsPrefixStyleSchema").getAsInt()
                    : 1;
            if (schema < COORDS_PREFIX_STYLE_SCHEMA && style >= 1) {
                style++;
            }
            moreHudCoordsPrefixStyle = style;
        }
        if (mh.has("coordsDecimals")) {
            moreHudCoordsDecimals = Math.max(0, Math.min(4, mh.get("coordsDecimals").getAsInt()));
        }
        moreHudCoordsPosY = mh.has("coordsPosY") ? mh.get("coordsPosY").getAsInt() : moreHudCoordsPosY;
        moreHudCoordsOffsetCenterX = mh.has("coordsOffsetCenterX")
                ? mh.get("coordsOffsetCenterX").getAsInt() : moreHudCoordsOffsetCenterX;
        moreHudCoordsScale = mh.has("coordsScale")
                ? clampMoreHudRowScale(mh.get("coordsScale").getAsFloat()) : moreHudCoordsScale;
        moreHudCoordsCompassNudgeX = mh.has("coordsCompassNudgeX")
                ? mh.get("coordsCompassNudgeX").getAsInt() : moreHudCoordsCompassNudgeX;
        moreHudCoordsCompassNudgeY = mh.has("coordsCompassNudgeY")
                ? mh.get("coordsCompassNudgeY").getAsInt() : moreHudCoordsCompassNudgeY;
        if (mh.has("coordsCompassRingRadiusOffset")) {
            moreHudCoordsCompassRingRadiusOffset = mh.get("coordsCompassRingRadiusOffset").getAsInt();
        }
        if (mh.has("coordsCompassDotOrbitOffset")) {
            moreHudCoordsCompassDotOrbitOffset = mh.get("coordsCompassDotOrbitOffset").getAsInt();
        }
        if (mh.has("coordsCompassCardinalOutset")) {
            moreHudCoordsCompassCardinalOutset = mh.get("coordsCompassCardinalOutset").getAsInt();
        }
        moreHudKillEffectsPosY = mh.has("killEffectsPosY") ? mh.get("killEffectsPosY").getAsInt() : moreHudKillEffectsPosY;
        moreHudKillEffectsOffsetCenterX = mh.has("killEffectsOffsetCenterX")
                ? mh.get("killEffectsOffsetCenterX").getAsInt()
                : moreHudKillEffectsOffsetCenterX;
    }

    private static void saveMoreHudToConfig(JsonObject root) {
        if (root == null) {
            return;
        }
        JsonObject mh = new JsonObject();
        mh.addProperty("enabled", moreHudEnabled);
        mh.addProperty("offsetCenterX", moreHudOffsetCenterX);
        mh.addProperty("posY", moreHudPosY);
        mh.addProperty("rowGap", moreHudRowGap);
        mh.addProperty("scale", moreHudScale);
        mh.addProperty("fps", moreHudFps);
        mh.addProperty("fpsShadow", moreHudFpsShadow);
        mh.addProperty("fpsBg", moreHudFpsBg);
        mh.addProperty("fpsBgColor", moreHudFpsBgColor);
        mh.addProperty("fpsPrefixColor", moreHudFpsPrefixColor);
        mh.addProperty("fpsColor", moreHudFpsColor);
        mh.addProperty("fpsDisplayStyle", moreHudFpsDisplayStyle);
        mh.addProperty("fpsCustomLabel", moreHudFpsCustomLabel);
        mh.addProperty("cps", moreHudCps);
        mh.addProperty("cpsShadow", moreHudCpsShadow);
        mh.addProperty("cpsBg", moreHudCpsBg);
        mh.addProperty("cpsBgColor", moreHudCpsBgColor);
        mh.addProperty("cpsColor", moreHudCpsColor);
        mh.addProperty("cpsPrefixColor", moreHudCpsPrefixColor);
        mh.addProperty("cpsSeparatorColor", moreHudCpsSeparatorColor);
        mh.addProperty("cpsMode", moreHudCpsMode);
        mh.addProperty("cpsBrackets", moreHudCpsSquareBrackets);
        mh.addProperty("ping", moreHudPing);
        mh.addProperty("pingShadow", moreHudPingShadow);
        mh.addProperty("pingBg", moreHudPingBg);
        mh.addProperty("pingBgColor", moreHudPingBgColor);
        mh.addProperty("pingColor", moreHudPingColor);
        mh.addProperty("potions", moreHudPotions);
        mh.addProperty("potionsShadow", moreHudPotionsShadow);
        mh.addProperty("potionsBg", moreHudPotionsBg);
        mh.addProperty("potionsBgColor", moreHudPotionsBgColor);
        mh.addProperty("potionsColor", moreHudPotionsColor);
        mh.addProperty("potionsEffectColors", moreHudPotionsEffectColors);
        mh.addProperty("potionsDurationColor", moreHudPotionsDurationColor);
        saveMoreHudPotionsEffectColorById(mh);
        mh.addProperty("potionsShowFlamingArrows", moreHudPotionsShowFlamingArrows);
        mh.addProperty("potionsDecimalEffectTimer", moreHudPotionsDecimalEffectTimer);
        mh.addProperty("potionsBgAttached", moreHudPotionsBgAttached);
        mh.addProperty("coords", moreHudCoords);
        mh.addProperty("coordsShadow", moreHudCoordsShadow);
        mh.addProperty("coordsBg", moreHudCoordsBg);
        mh.addProperty("coordsBgColor", moreHudCoordsBgColor);
        mh.addProperty("coordsPrefixColor", moreHudCoordsPrefixColor);
        mh.addProperty("coordsTextColor", moreHudCoordsTextColor);
        mh.addProperty("coordsBiomeColor", moreHudCoordsBiomeColor);
        mh.addProperty("coordsDirectionColor", moreHudCoordsDirectionColor);
        mh.addProperty("coordsCompassRingColor", moreHudCoordsCompassRingColor);
        mh.addProperty("coordsCompassDotColor", moreHudCoordsCompassDotColor);
        mh.addProperty("coordsLayout", moreHudCoordsLayout);
        mh.addProperty("coordsVertical", moreHudCoordsLayout == COORDS_LAYOUT_VERTICAL);
        mh.addProperty("coordsBiome", moreHudCoordsBiome);
        mh.addProperty("coordsDirection", moreHudCoordsDirection);
        mh.addProperty("coordsShowDuringF3", moreHudCoordsShowDuringF3);
        mh.addProperty("coordsShowFace", moreHudCoordsShowFace);
        mh.addProperty("coordsPrefixStyle", moreHudCoordsPrefixStyle);
        mh.addProperty("coordsPrefixStyleSchema", COORDS_PREFIX_STYLE_SCHEMA);
        mh.addProperty("coordsDecimals", moreHudCoordsDecimals);
        mh.addProperty("coordsPosY", moreHudCoordsPosY);
        mh.addProperty("coordsOffsetCenterX", moreHudCoordsOffsetCenterX);
        mh.addProperty("coordsScale", moreHudCoordsScale);
        mh.addProperty("coordsCompassNudgeX", moreHudCoordsCompassNudgeX);
        mh.addProperty("coordsCompassNudgeY", moreHudCoordsCompassNudgeY);
        mh.addProperty("coordsCompassRingRadiusOffset", moreHudCoordsCompassRingRadiusOffset);
        mh.addProperty("coordsCompassDotOrbitOffset", moreHudCoordsCompassDotOrbitOffset);
        mh.addProperty("coordsCompassCardinalOutset", moreHudCoordsCompassCardinalOutset);
        mh.addProperty("arrows", moreHudArrows);
        mh.addProperty("arrowsShadow", moreHudArrowsShadow);
        mh.addProperty("arrowsBg", moreHudArrowsBg);
        mh.addProperty("arrowsBgColor", moreHudArrowsBgColor);
        mh.addProperty("arrowsColor", moreHudArrowsColor);
        mh.addProperty("arrowsZeroColor", moreHudArrowsZeroColor);
        mh.addProperty("arrowsOverlayDx", moreHudArrowsOverlayDx);
        mh.addProperty("arrowsOverlayDy", moreHudArrowsOverlayDy);
        mh.addProperty("armorStatus", moreHudArmorStatus);
        mh.addProperty("armorStatusShadow", moreHudArmorStatusShadow);
        mh.addProperty("armorStatusBg", moreHudArmorStatusBg);
        mh.addProperty("armorStatusBgColor", moreHudArmorStatusBgColor);
        mh.addProperty("armorStatusPosY", moreHudArmorStatusPosY);
        mh.addProperty("armorStatusOffsetCenterX", moreHudArmorStatusOffsetCenterX);
        mh.addProperty("armorStatusScale", moreHudArmorStatusScale);
        mh.addProperty("armorStatusTextScale", moreHudArmorStatusTextScale);
        mh.addProperty("armorStatusDuraMode", moreHudArmorStatusDuraMode);
        mh.addProperty("armorStatusDuraRight", moreHudArmorStatusDuraRight);
        mh.addProperty("armorStatusDurabilityBar", moreHudArmorStatusDurabilityBar);
        mh.addProperty("armorStatusLayout", moreHudArmorStatusLayout);
        mh.addProperty("armorStatusVertical", moreHudArmorStatusVertical);
        mh.addProperty("armorStatusChroma", moreHudArmorStatusChroma);
        mh.addProperty("armorStatusShowHelmet", moreHudArmorStatusShowHelmet);
        mh.addProperty("armorStatusShowChest", moreHudArmorStatusShowChest);
        mh.addProperty("armorStatusShowLegs", moreHudArmorStatusShowLegs);
        mh.addProperty("armorStatusShowBoots", moreHudArmorStatusShowBoots);
        mh.addProperty("armorStatusShowHeld", moreHudArmorStatusShowHeld);
        mh.addProperty("armorStatusHeldPlacement", moreHudArmorStatusHeldPlacement);
        mh.addProperty("armorStatusArmorSlotGap", getMoreHudArmorStatusArmorSlotGapRaw());
        mh.addProperty("armorStatusArmorSlotGapVertical", moreHudArmorStatusArmorSlotGapVertical);
        mh.addProperty("armorStatusArmorSlotGapHorizontal", moreHudArmorStatusArmorSlotGapHorizontal);
        mh.addProperty("armorStatusDuraColor1", moreHudArmorStatusDuraColor1);
        mh.addProperty("armorStatusDuraColor2", moreHudArmorStatusDuraColor2);
        mh.addProperty("armorStatusDuraColor3", moreHudArmorStatusDuraColor3);
        mh.addProperty("armorStatusDuraColor4", moreHudArmorStatusDuraColor4);
        mh.addProperty("flame", moreHudFlame);
        mh.addProperty("killEffects", moreHudKillEffects);
        mh.addProperty("killEffectsBg", moreHudKillEffectsBg);
        mh.addProperty("killEffectsBgColor", moreHudKillEffectsBgColor);
        mh.addProperty("killEffectsShadow", moreHudKillEffectsShadow);
        mh.addProperty("flameShadow", moreHudFlameShadow);
        mh.addProperty("flameBg", moreHudFlameBg);
        mh.addProperty("flameBgColor", moreHudFlameBgColor);
        mh.addProperty("flameColor", moreHudFlameColor);
        mh.addProperty("killEffectSpeedColor", moreHudKillEffectSpeedColor);
        mh.addProperty("killEffectResistanceColor", moreHudKillEffectResistanceColor);
        mh.addProperty("killEffectRegenerationColor", moreHudKillEffectRegenerationColor);
        mh.addProperty("flameTimerBar", moreHudFlameTimerBar);
        mh.addProperty("fpsPosY", moreHudFpsPosY);
        mh.addProperty("cpsPosY", moreHudCpsPosY);
        mh.addProperty("pingPosY", moreHudPingPosY);
        mh.addProperty("potionsPosY", moreHudPotionsPosY);
        mh.addProperty("arrowsPosY", moreHudArrowsPosY);
        mh.addProperty("coordsPosY", moreHudCoordsPosY);
        mh.addProperty("flamePosY", moreHudFlamePosY);
        mh.addProperty("killEffectsPosY", moreHudKillEffectsPosY);
        mh.addProperty("fpsOffsetCenterX", moreHudFpsOffsetCenterX);
        mh.addProperty("cpsOffsetCenterX", moreHudCpsOffsetCenterX);
        mh.addProperty("pingOffsetCenterX", moreHudPingOffsetCenterX);
        mh.addProperty("potionsOffsetCenterX", moreHudPotionsOffsetCenterX);
        mh.addProperty("arrowsOffsetCenterX", moreHudArrowsOffsetCenterX);
        mh.addProperty("coordsOffsetCenterX", moreHudCoordsOffsetCenterX);
        mh.addProperty("flameOffsetCenterX", moreHudFlameOffsetCenterX);
        mh.addProperty("killEffectsOffsetCenterX", moreHudKillEffectsOffsetCenterX);
        mh.addProperty("fpsScale", moreHudFpsScale);
        mh.addProperty("cpsScale", moreHudCpsScale);
        mh.addProperty("pingScale", moreHudPingScale);
        mh.addProperty("potionsScale", moreHudPotionsScale);
        mh.addProperty("arrowsScale", moreHudArrowsScale);
        mh.addProperty("coordsScale", moreHudCoordsScale);
        mh.addProperty("flameScale", moreHudFlameScale);
        mh.addProperty("killEffectsScale", moreHudKillEffectsScale);
        mh.addProperty("killEffectsIconScale", moreHudKillEffectsIconScale);
        mh.addProperty("killEffectsTimerScale", moreHudKillEffectsTimerScale);
        mh.addProperty("killEffectsIconNx", moreHudKillEffectsIconNudgeX);
        mh.addProperty("killEffectsIconNy", moreHudKillEffectsIconNudgeY);
        mh.addProperty("killEffectsTimerNx", moreHudKillEffectsTimerNudgeX);
        mh.addProperty("killEffectsTimerNy", moreHudKillEffectsTimerNudgeY);
        mh.addProperty("flameCompact", moreHudFlameCompact);
        mh.addProperty("flameCompactPosY", moreHudFlameCompactPosY);
        mh.addProperty("flameCompactOffsetCenterX", moreHudFlameCompactOffsetCenterX);
        mh.addProperty("flameCompactScale", moreHudFlameCompactScale);
        mh.addProperty("flameCompactIconNx", moreHudFlameCompactIconNudgeX);
        mh.addProperty("flameCompactIconNy", moreHudFlameCompactIconNudgeY);
        mh.addProperty("flameCompactTimerNx", moreHudFlameCompactTimerNudgeX);
        mh.addProperty("flameCompactTimerNy", moreHudFlameCompactTimerNudgeY);
        mh.addProperty("flameCompactIconScale", moreHudFlameCompactIconScale);
        mh.addProperty("flameCompactTimerScale", moreHudFlameCompactTimerScale);
        mh.addProperty("pingPrefixColor", moreHudPingPrefixColor);
        mh.addProperty("pingDisplayStyle", moreHudPingDisplayStyle);
        mh.addProperty("pingCustomLabel", moreHudPingCustomLabel);
        root.add("moreHud", mh);
    }

    private static float clampMoreHudRowScale(float v) {
        return Math.max(0.25f, Math.min(4.0f, v));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Always on; per-module visibility is controlled in the HUD editor. */
    public static boolean isMoreHudEnabled() {
        return true;
    }

    public static void setMoreHudEnabled(boolean v) {
        if (!loaded) {
            loadConfig();
        }
        if (!moreHudEnabled) {
            moreHudEnabled = true;
            saveConfig();
        }
    }

    public static int getMoreHudOffsetCenterX() {
        if (!loaded) loadConfig();
        return moreHudOffsetCenterX;
    }

    public static void setMoreHudOffsetCenterX(int v) {
        moreHudOffsetCenterX = v;
        saveConfig();
    }

    public static int getMoreHudPosY() {
        if (!loaded) loadConfig();
        return moreHudPosY;
    }

    public static void setMoreHudPosY(int v) {
        moreHudPosY = v;
        saveConfig();
    }

    public static int getMoreHudRowGap() {
        if (!loaded) loadConfig();
        return moreHudRowGap;
    }

    public static void setMoreHudRowGap(int v) {
        moreHudRowGap = Math.max(0, v);
        saveConfig();
    }

    public static float getMoreHudScale() {
        if (!loaded) loadConfig();
        return moreHudScale;
    }

    public static void setMoreHudScale(float v) {
        float c = clampMoreHudRowScale(v);
        moreHudScale = c;
        moreHudFpsScale = c;
        moreHudCpsScale = c;
        moreHudPingScale = c;
        moreHudPotionsScale = c;
        moreHudArrowsScale = c;
        moreHudFlameScale = c;
        moreHudFlameCompactScale = c;
        saveConfig();
    }

    public static float getMoreHudFpsScale() { if (!loaded) loadConfig(); return moreHudFpsScale; }
    public static void setMoreHudFpsScale(float v) { moreHudFpsScale = clampMoreHudRowScale(v); saveConfig(); }
    public static float getMoreHudCpsScale() { if (!loaded) loadConfig(); return moreHudCpsScale; }
    public static void setMoreHudCpsScale(float v) { moreHudCpsScale = clampMoreHudRowScale(v); saveConfig(); }
    public static float getMoreHudPingScale() { if (!loaded) loadConfig(); return moreHudPingScale; }
    public static void setMoreHudPingScale(float v) { moreHudPingScale = clampMoreHudRowScale(v); saveConfig(); }
    public static float getMoreHudPotionsScale() { if (!loaded) loadConfig(); return moreHudPotionsScale; }
    public static void setMoreHudPotionsScale(float v) { moreHudPotionsScale = clampMoreHudRowScale(v); saveConfig(); }
    public static float getMoreHudArrowsScale() { if (!loaded) loadConfig(); return moreHudArrowsScale; }
    public static void setMoreHudArrowsScale(float v) { moreHudArrowsScale = clampMoreHudRowScale(v); saveConfig(); }

    public static int getMoreHudFpsDisplayStyle() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudFpsDisplayStyle;
    }

    public static void setMoreHudFpsDisplayStyle(int v) {
        moreHudFpsDisplayStyle = Math.max(0, Math.min(4, v));
        saveConfig();
    }

    public static String getMoreHudFpsCustomLabel() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudFpsCustomLabel;
    }

    public static void setMoreHudFpsCustomLabel(String label) {
        moreHudFpsCustomLabel = validateMoreHudFpsCustomLabel(label);
        saveConfig();
    }

    /** Sanitizes and ensures the {@code {value}} FPS placeholder is present. */
    public static String validateMoreHudFpsCustomLabel(String raw) {
        String s = sanitizeMoreHudFpsCustomLabel(raw);
        String token = "{value}";
        if (s.contains(token)) {
            return s;
        }
        if (s.length() + token.length() > 48) {
            s = s.substring(0, Math.max(0, 48 - token.length()));
        }
        return s + token;
    }

    private static String sanitizeMoreHudFpsCustomLabel(String raw) {
        if (raw == null) {
            return "FPS: {value}";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "FPS: {value}";
        }
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    public static int getMoreHudPingDisplayStyle() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudPingDisplayStyle;
    }

    public static void setMoreHudPingDisplayStyle(int v) {
        moreHudPingDisplayStyle = Math.max(0, Math.min(3, v));
        saveConfig();
    }

    public static String getMoreHudPingCustomLabel() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudPingCustomLabel;
    }

    public static void setMoreHudPingCustomLabel(String label) {
        moreHudPingCustomLabel = validateMoreHudPingCustomLabel(label);
        saveConfig();
    }

    /** Sanitizes and ensures the {@code {value}} ping placeholder is present. */
    public static String validateMoreHudPingCustomLabel(String raw) {
        String s = sanitizeMoreHudPingCustomLabel(raw);
        String token = "{value}";
        if (s.contains(token)) {
            return s;
        }
        if (s.length() + token.length() > 48) {
            s = s.substring(0, Math.max(0, 48 - token.length()));
        }
        return s + token;
    }

    private static String sanitizeMoreHudPingCustomLabel(String raw) {
        if (raw == null) {
            return "Ping: {value}";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "Ping: {value}";
        }
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    /** @deprecated use {@link #getMoreHudPingDisplayStyle()} */
    public static boolean isMoreHudPingPrefix() {
        return getMoreHudPingDisplayStyle() != 0;
    }

    /** @deprecated use {@link #setMoreHudPingDisplayStyle(int)} */
    public static void setMoreHudPingPrefix(boolean v) {
        setMoreHudPingDisplayStyle(v ? 1 : 0);
    }

    /** Nudge every row scale by the same amount (More HUD main menu). */
    public static void shiftAllMoreHudScales(float delta) {
        moreHudFpsScale = clampMoreHudRowScale(moreHudFpsScale + delta);
        moreHudCpsScale = clampMoreHudRowScale(moreHudCpsScale + delta);
        moreHudPingScale = clampMoreHudRowScale(moreHudPingScale + delta);
        moreHudPotionsScale = clampMoreHudRowScale(moreHudPotionsScale + delta);
        moreHudArrowsScale = clampMoreHudRowScale(moreHudArrowsScale + delta);
        moreHudCoordsScale = clampMoreHudRowScale(moreHudCoordsScale + delta);
        moreHudFlameScale = clampMoreHudRowScale(moreHudFlameScale + delta);
        moreHudKillEffectsScale = clampMoreHudRowScale(moreHudKillEffectsScale + delta);
        moreHudFlameCompactScale = clampMoreHudRowScale(moreHudFlameCompactScale + delta);
        moreHudScale = moreHudFpsScale;
        saveConfig();
    }

    public static int getMoreHudFpsPosY() { if (!loaded) loadConfig(); return moreHudFpsPosY; }
    public static void setMoreHudFpsPosY(int v) { moreHudFpsPosY = v; saveConfig(); }
    public static int getMoreHudCpsPosY() { if (!loaded) loadConfig(); return moreHudCpsPosY; }
    public static void setMoreHudCpsPosY(int v) { moreHudCpsPosY = v; saveConfig(); }
    public static int getMoreHudPingPosY() { if (!loaded) loadConfig(); return moreHudPingPosY; }
    public static void setMoreHudPingPosY(int v) { moreHudPingPosY = v; saveConfig(); }
    public static int getMoreHudPotionsPosY() { if (!loaded) loadConfig(); return moreHudPotionsPosY; }
    public static void setMoreHudPotionsPosY(int v) { moreHudPotionsPosY = v; saveConfig(); }
    public static int getMoreHudArrowsPosY() { if (!loaded) loadConfig(); return moreHudArrowsPosY; }
    public static void setMoreHudArrowsPosY(int v) { moreHudArrowsPosY = v; saveConfig(); }

    public static int getMoreHudFpsOffsetCenterX() { if (!loaded) loadConfig(); return moreHudFpsOffsetCenterX; }
    public static void setMoreHudFpsOffsetCenterX(int v) { moreHudFpsOffsetCenterX = v; saveConfig(); }
    public static int getMoreHudCpsOffsetCenterX() { if (!loaded) loadConfig(); return moreHudCpsOffsetCenterX; }
    public static void setMoreHudCpsOffsetCenterX(int v) { moreHudCpsOffsetCenterX = v; saveConfig(); }
    public static int getMoreHudPingOffsetCenterX() { if (!loaded) loadConfig(); return moreHudPingOffsetCenterX; }
    public static void setMoreHudPingOffsetCenterX(int v) { moreHudPingOffsetCenterX = v; saveConfig(); }
    public static int getMoreHudPotionsOffsetCenterX() { if (!loaded) loadConfig(); return moreHudPotionsOffsetCenterX; }
    public static void setMoreHudPotionsOffsetCenterX(int v) { moreHudPotionsOffsetCenterX = v; saveConfig(); }
    public static int getMoreHudArrowsOffsetCenterX() { if (!loaded) loadConfig(); return moreHudArrowsOffsetCenterX; }
    public static void setMoreHudArrowsOffsetCenterX(int v) { moreHudArrowsOffsetCenterX = v; saveConfig(); }
    public static int getMoreHudFlamePosY() { if (!loaded) loadConfig(); return moreHudFlamePosY; }
    public static void setMoreHudFlamePosY(int v) { moreHudFlamePosY = v; saveConfig(); }
    public static int getMoreHudFlameOffsetCenterX() { if (!loaded) loadConfig(); return moreHudFlameOffsetCenterX; }
    public static void setMoreHudFlameOffsetCenterX(int v) { moreHudFlameOffsetCenterX = v; saveConfig(); }

    /** Move every More HUD row by the same delta (layout nudge). */
    public static void shiftAllMoreHudRows(int deltaY) {
        moreHudFpsPosY += deltaY;
        moreHudCpsPosY += deltaY;
        moreHudPingPosY += deltaY;
        moreHudPotionsPosY += deltaY;
        moreHudArrowsPosY += deltaY;
        moreHudArmorStatusPosY += deltaY;
        moreHudCoordsPosY += deltaY;
        moreHudFlamePosY += deltaY;
        moreHudKillEffectsPosY += deltaY;
        moreHudFlameCompactPosY += deltaY;
        saveConfig();
    }

    public static boolean isMoreHudFpsEnabled() { if (!loaded) loadConfig(); return moreHudFps; }
    public static void setMoreHudFpsEnabled(boolean v) { moreHudFps = v; saveConfig(); }
    public static boolean isMoreHudFpsShadow() { if (!loaded) loadConfig(); return moreHudFpsShadow; }
    public static void setMoreHudFpsShadow(boolean v) { moreHudFpsShadow = v; saveConfig(); }
    public static int getMoreHudFpsBg() { if (!loaded) loadConfig(); return moreHudFpsBg; }
    public static void setMoreHudFpsBg(int v) { moreHudFpsBg = clamp255(v); saveConfig(); }
    public static int getMoreHudFpsBgColor() { if (!loaded) loadConfig(); return moreHudFpsBgColor; }
    public static void setMoreHudFpsBgColor(int v) { moreHudFpsBgColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudFpsPrefixColor() { if (!loaded) loadConfig(); return moreHudFpsPrefixColor; }
    public static void setMoreHudFpsPrefixColor(int v) { moreHudFpsPrefixColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudFpsColor() { if (!loaded) loadConfig(); return moreHudFpsColor; }
    public static void setMoreHudFpsColor(int v) { moreHudFpsColor = v & 0xFFFFFF; saveConfig(); }

    public static boolean isMoreHudCpsEnabled() { if (!loaded) loadConfig(); return moreHudCps; }
    public static void setMoreHudCpsEnabled(boolean v) { moreHudCps = v; saveConfig(); }
    public static boolean isMoreHudCpsShadow() { if (!loaded) loadConfig(); return moreHudCpsShadow; }
    public static void setMoreHudCpsShadow(boolean v) { moreHudCpsShadow = v; saveConfig(); }
    public static int getMoreHudCpsBg() { if (!loaded) loadConfig(); return moreHudCpsBg; }
    public static void setMoreHudCpsBg(int v) { moreHudCpsBg = clamp255(v); saveConfig(); }
    public static int getMoreHudCpsBgColor() { if (!loaded) loadConfig(); return moreHudCpsBgColor; }
    public static void setMoreHudCpsBgColor(int v) { moreHudCpsBgColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudCpsColor() { if (!loaded) loadConfig(); return moreHudCpsColor; }
    public static void setMoreHudCpsColor(int v) { moreHudCpsColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudCpsPrefixColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCpsPrefixColor;
    }

    public static void setMoreHudCpsPrefixColor(int v) {
        moreHudCpsPrefixColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCpsSeparatorColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCpsSeparatorColor;
    }

    public static void setMoreHudCpsSeparatorColor(int v) {
        moreHudCpsSeparatorColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCpsMode() { if (!loaded) loadConfig(); return moreHudCpsMode; }
    public static void setMoreHudCpsMode(int v) { moreHudCpsMode = Math.max(0, Math.min(2, v)); saveConfig(); }
    public static boolean isMoreHudCpsSquareBrackets() { if (!loaded) loadConfig(); return moreHudCpsSquareBrackets; }
    public static void setMoreHudCpsSquareBrackets(boolean v) { moreHudCpsSquareBrackets = v; saveConfig(); }

    public static boolean isMoreHudPingEnabled() { if (!loaded) loadConfig(); return moreHudPing; }
    public static void setMoreHudPingEnabled(boolean v) { moreHudPing = v; saveConfig(); }
    public static boolean isMoreHudPingShadow() { if (!loaded) loadConfig(); return moreHudPingShadow; }
    public static void setMoreHudPingShadow(boolean v) { moreHudPingShadow = v; saveConfig(); }
    public static int getMoreHudPingBg() { if (!loaded) loadConfig(); return moreHudPingBg; }
    public static void setMoreHudPingBg(int v) { moreHudPingBg = clamp255(v); saveConfig(); }
    public static int getMoreHudPingBgColor() { if (!loaded) loadConfig(); return moreHudPingBgColor; }
    public static void setMoreHudPingBgColor(int v) { moreHudPingBgColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudPingColor() { if (!loaded) loadConfig(); return moreHudPingColor; }
    public static void setMoreHudPingColor(int v) { moreHudPingColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudPingPrefixColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudPingPrefixColor;
    }

    public static void setMoreHudPingPrefixColor(int v) {
        moreHudPingPrefixColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static boolean isMoreHudPotionsEnabled() { if (!loaded) loadConfig(); return moreHudPotions; }
    public static void setMoreHudPotionsEnabled(boolean v) { moreHudPotions = v; saveConfig(); }
    public static boolean isMoreHudPotionsShadow() { if (!loaded) loadConfig(); return moreHudPotionsShadow; }
    public static void setMoreHudPotionsShadow(boolean v) { moreHudPotionsShadow = v; saveConfig(); }
    public static int getMoreHudPotionsBg() { if (!loaded) loadConfig(); return moreHudPotionsBg; }
    public static void setMoreHudPotionsBg(int v) { moreHudPotionsBg = clamp255(v); saveConfig(); }
    public static int getMoreHudPotionsBgColor() { if (!loaded) loadConfig(); return moreHudPotionsBgColor; }
    public static void setMoreHudPotionsBgColor(int v) { moreHudPotionsBgColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudPotionsColor() { if (!loaded) loadConfig(); return moreHudPotionsColor; }
    public static void setMoreHudPotionsColor(int v) { moreHudPotionsColor = v & 0xFFFFFF; saveConfig(); }
    public static boolean isMoreHudPotionsEffectColors() { if (!loaded) loadConfig(); return moreHudPotionsEffectColors; }
    public static void setMoreHudPotionsEffectColors(boolean v) { moreHudPotionsEffectColors = v; saveConfig(); }

    public static int getMoreHudPotionsDurationColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudPotionsDurationColor;
    }

    public static void setMoreHudPotionsDurationColor(int rgb) {
        moreHudPotionsDurationColor = rgb & 0xFFFFFF;
        saveConfig();
    }

    public static void resetMoreHudPotionsDurationColor() {
        setMoreHudPotionsDurationColor(DEFAULT_MORE_HUD_POTIONS_DURATION_COLOR);
    }

    public static int getDefaultMoreHudPotionsEffectColor(int potionId) {
        if (potionId >= BlitzStarEffectsHudTracker.COLOR_SLOT_BASE
                && potionId < MORE_HUD_POTION_COLOR_SLOTS) {
            return BlitzStarEffectsHudTracker.getDefaultColor(potionId);
        }
        if (potionId <= 0 || potionId >= VANILLA_POTION_EFFECT_NAME_RGB.length) {
            return moreHudPotionsColor;
        }
        int c = VANILLA_POTION_EFFECT_NAME_RGB[potionId];
        return c != 0 ? c : (moreHudPotionsColor & 0xFFFFFF);
    }

    private static int[] createDefaultMoreHudPotionsEffectColorById() {
        int[] colors = new int[MORE_HUD_POTION_COLOR_SLOTS];
        for (int id = 1; id < MORE_HUD_POTION_COLOR_SLOTS; id++) {
            colors[id] = getDefaultMoreHudPotionsEffectColor(id);
        }
        return colors;
    }

    private static void ensureMoreHudPotionsEffectColorById() {
        if (moreHudPotionsEffectColorById == null
                || moreHudPotionsEffectColorById.length != MORE_HUD_POTION_COLOR_SLOTS) {
            moreHudPotionsEffectColorById = createDefaultMoreHudPotionsEffectColorById();
        }
    }

    private static void loadMoreHudPotionsEffectColorById(com.google.gson.JsonObject mh) {
        ensureMoreHudPotionsEffectColorById();
        if (mh == null || !mh.has("potionsEffectColorById") || !mh.get("potionsEffectColorById").isJsonArray()) {
            return;
        }
        JsonArray arr = mh.getAsJsonArray("potionsEffectColorById");
        for (int i = 0; i < MORE_HUD_POTION_COLOR_SLOTS && i < arr.size(); i++) {
            moreHudPotionsEffectColorById[i] = arr.get(i).getAsInt() & 0xFFFFFF;
        }
    }

    private static void saveMoreHudPotionsEffectColorById(com.google.gson.JsonObject mh) {
        ensureMoreHudPotionsEffectColorById();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < MORE_HUD_POTION_COLOR_SLOTS; i++) {
            arr.add(new com.google.gson.JsonPrimitive(moreHudPotionsEffectColorById[i]));
        }
        mh.add("potionsEffectColorById", arr);
    }

    public static int getMoreHudPotionsEffectColor(int potionId) {
        if (!loaded) {
            loadConfig();
        }
        ensureMoreHudPotionsEffectColorById();
        if (potionId <= 0 || potionId >= MORE_HUD_POTION_COLOR_SLOTS) {
            return moreHudPotionsColor;
        }
        return moreHudPotionsEffectColorById[potionId];
    }

    public static void setMoreHudPotionsEffectColor(int potionId, int rgb) {
        if (!loaded) {
            loadConfig();
        }
        ensureMoreHudPotionsEffectColorById();
        if (potionId <= 0 || potionId >= MORE_HUD_POTION_COLOR_SLOTS) {
            return;
        }
        moreHudPotionsEffectColorById[potionId] = rgb & 0xFFFFFF;
        moreHudPotionsEffectColors = true;
        saveConfig();
    }

    /** Live preview while dragging a picker; defers disk save until release. */
    public static void previewMoreHudPotionsEffectColor(int potionId, int rgb) {
        if (!loaded) {
            loadConfig();
        }
        ensureMoreHudPotionsEffectColorById();
        if (potionId <= 0 || potionId >= MORE_HUD_POTION_COLOR_SLOTS) {
            return;
        }
        moreHudPotionsEffectColorById[potionId] = rgb & 0xFFFFFF;
        moreHudPotionsEffectColors = true;
    }

    public static void resetMoreHudPotionsEffectColor(int potionId) {
        if (potionId <= 0 || potionId >= MORE_HUD_POTION_COLOR_SLOTS) {
            return;
        }
        setMoreHudPotionsEffectColor(potionId, getDefaultMoreHudPotionsEffectColor(potionId));
    }

    public static void resetAllMoreHudPotionsColors() {
        if (!loaded) {
            loadConfig();
        }
        moreHudPotionsColor = 0xFFFFFF;
        moreHudPotionsDurationColor = DEFAULT_MORE_HUD_POTIONS_DURATION_COLOR;
        moreHudPotionsEffectColorById = createDefaultMoreHudPotionsEffectColorById();
        moreHudPotionsEffectColors = true;
        saveConfig();
    }

    public static boolean isMoreHudPotionsShowFlamingArrows() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudPotionsShowFlamingArrows;
    }

    /**
     * {@code true}: show Flaming Arrows KE in the Effects HUD ({@linkplain #moreHudFlameCompact} off).
     * {@code false}: separate Flaming Arrows HUD only ({@linkplain #moreHudFlameCompact} on); not merged into Effects HUD.
     */
    public static void setMoreHudPotionsShowFlamingArrows(boolean v) {
        if (!loaded) {
            loadConfig();
        }
        moreHudPotionsShowFlamingArrows = v;
        moreHudFlameCompact = !v;
        saveConfig();
    }

    public static boolean isMoreHudPotionsDecimalEffectTimer() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudPotionsDecimalEffectTimer;
    }

    public static void setMoreHudPotionsDecimalEffectTimer(boolean v) {
        if (!loaded) {
            loadConfig();
        }
        moreHudPotionsDecimalEffectTimer = v;
        saveConfig();
    }

    public static boolean isMoreHudPotionsBgAttached() {
        return moreHudPotionsBgAttached;
    }

    public static boolean isMoreHudCoordsEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoords;
    }

    public static void setMoreHudCoordsEnabled(boolean v) {
        moreHudCoords = v;
        saveConfig();
    }

    public static boolean isMoreHudCoordsShadow() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsShadow;
    }

    public static void setMoreHudCoordsShadow(boolean v) {
        moreHudCoordsShadow = v;
        saveConfig();
    }

    public static int getMoreHudCoordsBg() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsBg;
    }

    public static void setMoreHudCoordsBg(int v) {
        moreHudCoordsBg = clamp255(v);
        saveConfig();
    }

    public static int getMoreHudCoordsBgColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsBgColor;
    }

    public static void setMoreHudCoordsBgColor(int v) {
        moreHudCoordsBgColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCoordsPrefixColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsPrefixColor;
    }

    public static void setMoreHudCoordsPrefixColor(int v) {
        moreHudCoordsPrefixColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCoordsTextColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsTextColor;
    }

    public static void setMoreHudCoordsTextColor(int v) {
        moreHudCoordsTextColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCoordsBiomeColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsBiomeColor;
    }

    public static void setMoreHudCoordsBiomeColor(int v) {
        moreHudCoordsBiomeColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCoordsDirectionColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsDirectionColor;
    }

    public static void setMoreHudCoordsDirectionColor(int v) {
        moreHudCoordsDirectionColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCoordsCompassRingColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsCompassRingColor;
    }

    public static void setMoreHudCoordsCompassRingColor(int v) {
        moreHudCoordsCompassRingColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudCoordsCompassDotColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsCompassDotColor;
    }

    public static void setMoreHudCoordsCompassDotColor(int v) {
        moreHudCoordsCompassDotColor = v & 0xFFFFFF;
        saveConfig();
    }

    /** Advances to the next entry in the coords HUD color palette (Powns-style color buttons). */
    public static void cycleMoreHudCoordsPrefixColor() {
        moreHudCoordsPrefixColor = nextMoreHudCoordsPaletteColor(moreHudCoordsPrefixColor);
        saveConfig();
    }

    public static void cycleMoreHudCoordsTextColor() {
        moreHudCoordsTextColor = nextMoreHudCoordsPaletteColor(moreHudCoordsTextColor);
        saveConfig();
    }

    public static void cycleMoreHudCoordsBiomeColor() {
        moreHudCoordsBiomeColor = nextMoreHudCoordsPaletteColor(moreHudCoordsBiomeColor);
        saveConfig();
    }

    public static void cycleMoreHudCoordsDirectionColor() {
        moreHudCoordsDirectionColor = nextMoreHudCoordsPaletteColor(moreHudCoordsDirectionColor);
        saveConfig();
    }

    private static int dimMoreHudCoordsRgb(int rgb, float factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    private static int nextMoreHudCoordsPaletteColor(int current) {
        int[] palette = MORE_HUD_COORDS_COLOR_PALETTE;
        int cur = current & 0xFFFFFF;
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == cur) {
                return palette[(i + 1) % palette.length];
            }
        }
        return palette[0];
    }

    private static final int[] MORE_HUD_COORDS_COLOR_PALETTE = {
            0xFFFFFF, 0x55FF55, 0xFFFF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFAA00, 0xAAAAAA
    };

    public static int getMoreHudCoordsLayout() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsLayout;
    }

    public static void setMoreHudCoordsLayout(int layout) {
        moreHudCoordsLayout = Math.max(COORDS_LAYOUT_VERTICAL, Math.min(COORDS_LAYOUT_COMPASS_ONLY, layout));
        saveConfig();
    }

    public static void cycleMoreHudCoordsLayout() {
        setMoreHudCoordsLayout((getMoreHudCoordsLayout() + 1) % 3);
    }

    public static boolean isMoreHudCoordsVertical() {
        return getMoreHudCoordsLayout() == COORDS_LAYOUT_VERTICAL;
    }

    public static boolean isMoreHudCoordsCompassLayout() {
        int layout = getMoreHudCoordsLayout();
        return layout == COORDS_LAYOUT_COMPASS || layout == COORDS_LAYOUT_COMPASS_ONLY;
    }

    public static boolean isMoreHudCoordsCompassOnly() {
        return getMoreHudCoordsLayout() == COORDS_LAYOUT_COMPASS_ONLY;
    }

    public static void setMoreHudCoordsVertical(boolean v) {
        if (v) {
            setMoreHudCoordsLayout(COORDS_LAYOUT_VERTICAL);
        } else if (isMoreHudCoordsVertical()) {
            setMoreHudCoordsLayout(COORDS_LAYOUT_COMPASS);
        }
    }

    public static boolean isMoreHudCoordsBiome() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsBiome;
    }

    public static void setMoreHudCoordsBiome(boolean v) {
        moreHudCoordsBiome = v;
        saveConfig();
    }

    public static boolean isMoreHudCoordsDirection() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsDirection;
    }

    public static void setMoreHudCoordsDirection(boolean v) {
        moreHudCoordsDirection = v;
        saveConfig();
    }

    public static boolean isMoreHudCoordsShowDuringF3() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsShowDuringF3;
    }

    public static void setMoreHudCoordsShowDuringF3(boolean v) {
        moreHudCoordsShowDuringF3 = v;
        saveConfig();
    }

    public static boolean isMoreHudCoordsShowFace() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsShowFace;
    }

    public static void setMoreHudCoordsShowFace(boolean v) {
        moreHudCoordsShowFace = v;
        saveConfig();
    }

    public static int getMoreHudCoordsPrefixStyle() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsPrefixStyle;
    }

    public static void setMoreHudCoordsPrefixStyle(int v) {
        int max = MoreHudCoordsFeature.PrefixStyle.values().length - 1;
        moreHudCoordsPrefixStyle = Math.max(0, Math.min(max, v));
        saveConfig();
    }

    public static int getMoreHudCoordsDecimals() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsDecimals;
    }

    public static void setMoreHudCoordsDecimals(int v) {
        moreHudCoordsDecimals = Math.max(0, Math.min(4, v));
        saveConfig();
    }

    public static int getMoreHudCoordsPosY() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsPosY;
    }

    public static void setMoreHudCoordsPosY(int v) {
        moreHudCoordsPosY = v;
        saveConfig();
    }

    public static int getMoreHudCoordsOffsetCenterX() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsOffsetCenterX;
    }

    public static void setMoreHudCoordsOffsetCenterX(int v) {
        moreHudCoordsOffsetCenterX = v;
        saveConfig();
    }

    public static float getMoreHudCoordsScale() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsScale;
    }

    public static void setMoreHudCoordsScale(float v) {
        moreHudCoordsScale = clampMoreHudRowScale(v);
        saveConfig();
    }

    public static int getMoreHudCoordsCompassNudgeX() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsCompassNudgeX;
    }

    public static int getMoreHudCoordsCompassNudgeY() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsCompassNudgeY;
    }

    public static void setMoreHudCoordsCompassNudge(int x, int y) {
        int max = 256;
        try {
            max = MoreHudCoordsFeature.compassNudgeConfigLimit(net.minecraft.client.Minecraft.getMinecraft());
        } catch (Throwable ignored) {
        }
        moreHudCoordsCompassNudgeX = Math.max(-max, Math.min(max, x));
        moreHudCoordsCompassNudgeY = Math.max(-max, Math.min(max, y));
        saveConfig();
    }

    public static int getMoreHudCoordsCompassRingRadiusOffset() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsCompassRingRadiusOffset;
    }

    public static void setMoreHudCoordsCompassRingRadiusOffset(int px) {
        moreHudCoordsCompassRingRadiusOffset = clampCompassPositionOffset(px);
        saveConfig();
    }

    public static int getMoreHudCoordsCompassDotOrbitOffset() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsCompassDotOrbitOffset;
    }

    public static void setMoreHudCoordsCompassDotOrbitOffset(int px) {
        moreHudCoordsCompassDotOrbitOffset = clampCompassPositionOffset(px);
        saveConfig();
    }

    public static int getMoreHudCoordsCompassCardinalOutset() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudCoordsCompassCardinalOutset;
    }

    public static void setMoreHudCoordsCompassCardinalOutset(int px) {
        moreHudCoordsCompassCardinalOutset = clampCompassPositionOffset(px);
        saveConfig();
    }

    private static int clampCompassPositionOffset(int px) {
        return Math.max(-64, Math.min(64, px));
    }

    public static void resetMoreHudCoordsPositionOnly() {
        moreHudCoordsPosY = -2;
        moreHudCoordsOffsetCenterX = -427;
        moreHudCoordsScale = 0.75f;
        moreHudCoordsCompassNudgeX = 6;
        moreHudCoordsCompassNudgeY = 0;
        moreHudCoordsCompassRingRadiusOffset = MoreHudCoordsFeature.DEFAULT_COMPASS_RING_RADIUS_OFFSET;
        moreHudCoordsCompassDotOrbitOffset = MoreHudCoordsFeature.DEFAULT_COMPASS_DOT_ORBIT_OFFSET;
        moreHudCoordsCompassCardinalOutset = MoreHudCoordsFeature.DEFAULT_COMPASS_CARDINAL_OUTSET;
        saveConfig();
    }

    public static boolean isMoreHudArrowsEnabled() { if (!loaded) loadConfig(); return moreHudArrows; }
    public static void setMoreHudArrowsEnabled(boolean v) { moreHudArrows = v; saveConfig(); }
    public static boolean isMoreHudArrowsShadow() { if (!loaded) loadConfig(); return moreHudArrowsShadow; }
    public static void setMoreHudArrowsShadow(boolean v) { moreHudArrowsShadow = v; saveConfig(); }
    public static int getMoreHudArrowsBg() { if (!loaded) loadConfig(); return moreHudArrowsBg; }
    public static void setMoreHudArrowsBg(int v) { moreHudArrowsBg = clamp255(v); saveConfig(); }
    public static int getMoreHudArrowsBgColor() { if (!loaded) loadConfig(); return moreHudArrowsBgColor; }
    public static void setMoreHudArrowsBgColor(int v) { moreHudArrowsBgColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudArrowsColor() { if (!loaded) loadConfig(); return moreHudArrowsColor; }
    public static void setMoreHudArrowsColor(int v) { moreHudArrowsColor = v & 0xFFFFFF; saveConfig(); }

    public static int getMoreHudArrowsZeroColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArrowsZeroColor;
    }

    public static void setMoreHudArrowsZeroColor(int v) {
        moreHudArrowsZeroColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudArrowsOverlayDx() { if (!loaded) loadConfig(); return moreHudArrowsOverlayDx; }
    public static void setMoreHudArrowsOverlayDx(int v) { moreHudArrowsOverlayDx = v; saveConfig(); }
    public static int getMoreHudArrowsOverlayDy() { if (!loaded) loadConfig(); return moreHudArrowsOverlayDy; }
    public static void setMoreHudArrowsOverlayDy(int v) { moreHudArrowsOverlayDy = v; saveConfig(); }

    public static boolean isMoreHudArmorStatusEnabled() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatus;
    }

    public static void setMoreHudArmorStatusEnabled(boolean v) {
        moreHudArmorStatus = v;
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusShadow() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusShadow;
    }

    public static void setMoreHudArmorStatusShadow(boolean v) {
        moreHudArmorStatusShadow = v;
        saveConfig();
    }

    public static int getMoreHudArmorStatusBg() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusBg;
    }

    public static void setMoreHudArmorStatusBg(int v) {
        moreHudArmorStatusBg = clamp255(v);
        saveConfig();
    }

    public static int getMoreHudArmorStatusBgColor() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusBgColor;
    }

    public static void setMoreHudArmorStatusBgColor(int v) {
        moreHudArmorStatusBgColor = v & 0xFFFFFF;
        saveConfig();
    }

    public static int getMoreHudArmorStatusPosY() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusPosY;
    }

    public static void setMoreHudArmorStatusPosY(int v) {
        moreHudArmorStatusPosY = v;
        saveConfig();
    }

    public static int getMoreHudArmorStatusOffsetCenterX() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusOffsetCenterX;
    }

    public static void setMoreHudArmorStatusOffsetCenterX(int v) {
        moreHudArmorStatusOffsetCenterX = v;
        saveConfig();
    }

    public static float getMoreHudArmorStatusScale() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusScale;
    }

    public static void setMoreHudArmorStatusScale(float v) {
        moreHudArmorStatusScale = clampMoreHudRowScale(v);
        saveConfig();
    }

    public static float getMoreHudArmorStatusTextScale() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusTextScale;
    }

    public static void setMoreHudArmorStatusTextScale(float v) {
        moreHudArmorStatusTextScale = clampMoreHudArmorStatusTextScale(v);
        saveConfig();
    }

    public static int getMoreHudArmorStatusDuraMode() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusDuraMode;
    }

    public static void setMoreHudArmorStatusDuraMode(int v) {
        moreHudArmorStatusDuraMode = Math.max(0, Math.min(3, v));
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusDuraRight() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusDuraRight;
    }

    public static void setMoreHudArmorStatusDuraRight(boolean v) {
        moreHudArmorStatusDuraRight = v;
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusDurabilityBar() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusDurabilityBar;
    }

    public static void setMoreHudArmorStatusDurabilityBar(boolean v) {
        moreHudArmorStatusDurabilityBar = v;
        saveConfig();
    }

    public static int getMoreHudArmorStatusLayout() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusLayout;
    }

    public static void setMoreHudArmorStatusLayout(int layout) {
        if (!loaded) {
            loadConfig();
        }
        moreHudArmorStatusLayout = clampMoreHudArmorStatusLayout(layout);
        moreHudArmorStatusVertical = MoreHudArmorStatusFeature.LayoutMode.fromIndex(moreHudArmorStatusLayout).isVertical();
        syncLegacyActiveArmorStatusGap();
        clampMoreHudArmorStatusAnchorToScreen();
        saveConfig();
    }

    public static void cycleMoreHudArmorStatusLayout() {
        setMoreHudArmorStatusLayout(getMoreHudArmorStatusLayout() + 1);
    }

    public static boolean isMoreHudArmorStatusVertical() {
        if (!loaded) {
            loadConfig();
        }
        return isMoreHudArmorStatusVerticalRaw();
    }

    public static void setMoreHudArmorStatusVertical(boolean v) {
        setMoreHudArmorStatusLayout(v
                ? MoreHudArmorStatusFeature.LayoutMode.VERTICAL.getIndex()
                : MoreHudArmorStatusFeature.LayoutMode.HORIZONTAL.getIndex());
    }

    public static boolean isMoreHudArmorStatusChroma() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusChroma;
    }

    public static void setMoreHudArmorStatusChroma(boolean v) {
        moreHudArmorStatusChroma = v;
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusShowHelmet() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusShowHelmet;
    }

    public static void setMoreHudArmorStatusShowHelmet(boolean v) {
        moreHudArmorStatusShowHelmet = v;
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusShowChest() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusShowChest;
    }

    public static void setMoreHudArmorStatusShowChest(boolean v) {
        moreHudArmorStatusShowChest = v;
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusShowLegs() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusShowLegs;
    }

    public static void setMoreHudArmorStatusShowLegs(boolean v) {
        moreHudArmorStatusShowLegs = v;
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusShowBoots() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusShowBoots;
    }

    public static void setMoreHudArmorStatusShowBoots(boolean v) {
        moreHudArmorStatusShowBoots = v;
        saveConfig();
    }

    public static boolean isMoreHudArmorStatusShowHeld() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusShowHeld;
    }

    public static void setMoreHudArmorStatusShowHeld(boolean v) {
        moreHudArmorStatusShowHeld = v;
        if (!v) {
            moreHudArmorStatusHeldPlacement = MORE_HUD_ARMOR_HELD_OFF;
        } else if (moreHudArmorStatusHeldPlacement == MORE_HUD_ARMOR_HELD_OFF) {
            moreHudArmorStatusHeldPlacement = MORE_HUD_ARMOR_HELD_DOWN;
        }
        saveConfig();
    }

    public static int getMoreHudArmorStatusHeldPlacement() {
        if (!loaded) {
            loadConfig();
        }
        return moreHudArmorStatusHeldPlacement;
    }

    public static void setMoreHudArmorStatusHeldPlacement(int placement) {
        if (placement < MORE_HUD_ARMOR_HELD_DOWN || placement > MORE_HUD_ARMOR_HELD_OFF) {
            placement = MORE_HUD_ARMOR_HELD_DOWN;
        }
        moreHudArmorStatusHeldPlacement = placement;
        moreHudArmorStatusShowHeld = placement != MORE_HUD_ARMOR_HELD_OFF;
        saveConfig();
    }

    public static void cycleMoreHudArmorStatusHeldPlacement() {
        int next = getMoreHudArmorStatusHeldPlacement() + 1;
        if (next > MORE_HUD_ARMOR_HELD_OFF) {
            next = MORE_HUD_ARMOR_HELD_DOWN;
        }
        setMoreHudArmorStatusHeldPlacement(next);
    }

    public static int getMoreHudArmorStatusArmorSlotGap() {
        if (!loaded) {
            loadConfig();
        }
        return getMoreHudArmorStatusArmorSlotGapRaw();
    }

    public static void setMoreHudArmorStatusArmorSlotGap(int gapPx) {
        if (isMoreHudArmorStatusVerticalRaw()) {
            moreHudArmorStatusArmorSlotGapVertical = clampMoreHudArmorStatusArmorSlotGapForLayout(gapPx, true);
        } else {
            moreHudArmorStatusArmorSlotGapHorizontal = clampMoreHudArmorStatusArmorSlotGapForLayout(gapPx, false);
        }
        syncLegacyActiveArmorStatusGap();
        saveConfig();
    }

    private static int clampMoreHudArmorStatusArmorSlotGap(int gapPx) {
        return clampMoreHudArmorStatusArmorSlotGapForLayout(gapPx, isMoreHudArmorStatusVerticalRaw());
    }

    private static int clampMoreHudArmorStatusArmorSlotGapForLayout(int gapPx, boolean vertical) {
        int min = MoreHudArmorStatusFeature.getArmorSlotGapMin(vertical);
        int max = MoreHudArmorStatusFeature.getArmorSlotGapMax(vertical);
        return Math.max(min, Math.min(max, gapPx));
    }

    private static int clampMoreHudArmorStatusLayout(int layout) {
        int max = MoreHudArmorStatusFeature.LayoutMode.values().length - 1;
        if (layout < 0) {
            return 0;
        }
        if (layout > max) {
            return 0;
        }
        return layout;
    }

    private static float clampMoreHudArmorStatusTextScale(float v) {
        return Math.max(MoreHudArmorStatusFeature.ARMOR_TEXT_SCALE_MIN,
                Math.min(MoreHudArmorStatusFeature.ARMOR_TEXT_SCALE_MAX, v));
    }

    private static void syncLegacyActiveArmorStatusGap() {
        moreHudArmorStatusArmorSlotGap = getMoreHudArmorStatusArmorSlotGapRaw();
    }

    private static boolean isMoreHudArmorStatusVerticalRaw() {
        return MoreHudArmorStatusFeature.LayoutMode.fromIndex(moreHudArmorStatusLayout).isVertical();
    }

    private static int getMoreHudArmorStatusArmorSlotGapRaw() {
        return isMoreHudArmorStatusVerticalRaw()
                ? moreHudArmorStatusArmorSlotGapVertical
                : moreHudArmorStatusArmorSlotGapHorizontal;
    }

    private static void clampMoreHudArmorStatusAnchorToScreen() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        int anchorX = sw / 2 + moreHudArmorStatusOffsetCenterX;
        int anchorY = moreHudArmorStatusPosY;
        int[] clamped = MoreHudArmorStatusFeature.clampAnchorScreen(anchorX, anchorY, moreHudArmorStatusScale, sw, sh);
        moreHudArmorStatusOffsetCenterX = clamped[0] - sw / 2;
        moreHudArmorStatusPosY = clamped[1];
    }

    /** @param tier 1–4 (durability band); other values return white. */
    public static int getMoreHudArmorStatusDuraColor(int tier) {
        if (!loaded) {
            loadConfig();
        }
        switch (tier) {
            case 1:
                return moreHudArmorStatusDuraColor1;
            case 2:
                return moreHudArmorStatusDuraColor2;
            case 3:
                return moreHudArmorStatusDuraColor3;
            case 4:
                return moreHudArmorStatusDuraColor4;
            default:
                return 0xFFFFFF;
        }
    }

    public static void setMoreHudArmorStatusDuraColor(int tier, int rgb) {
        rgb &= 0xFFFFFF;
        switch (tier) {
            case 1:
                moreHudArmorStatusDuraColor1 = rgb;
                break;
            case 2:
                moreHudArmorStatusDuraColor2 = rgb;
                break;
            case 3:
                moreHudArmorStatusDuraColor3 = rgb;
                break;
            case 4:
                moreHudArmorStatusDuraColor4 = rgb;
                break;
            default:
                break;
        }
        saveConfig();
    }

    public static boolean isMoreHudFlameEnabled() { if (!loaded) loadConfig(); return moreHudFlame; }
    public static void setMoreHudFlameEnabled(boolean v) { moreHudFlame = v; saveConfig(); }
    public static boolean isMoreHudKillEffectsEnabled() { if (!loaded) loadConfig(); return moreHudKillEffects; }
    public static void setMoreHudKillEffectsEnabled(boolean v) { moreHudKillEffects = v; saveConfig(); }
    public static int getMoreHudKillEffectsBg() { if (!loaded) loadConfig(); return moreHudKillEffectsBg; }
    public static void setMoreHudKillEffectsBg(int v) { moreHudKillEffectsBg = clamp255(v); saveConfig(); }
    public static int getMoreHudKillEffectsBgColor() { if (!loaded) loadConfig(); return moreHudKillEffectsBgColor; }
    public static void setMoreHudKillEffectsBgColor(int v) { moreHudKillEffectsBgColor = v & 0xFFFFFF; saveConfig(); }
    public static boolean isMoreHudKillEffectsShadow() { if (!loaded) loadConfig(); return moreHudKillEffectsShadow; }
    public static void setMoreHudKillEffectsShadow(boolean v) { moreHudKillEffectsShadow = v; saveConfig(); }
    public static boolean isMoreHudFlameShadow() { if (!loaded) loadConfig(); return moreHudFlameShadow; }
    public static void setMoreHudFlameShadow(boolean v) { moreHudFlameShadow = v; saveConfig(); }
    public static int getMoreHudFlameBg() {
        return 0;
    }

    public static void setMoreHudFlameBg(int v) {
        if (!loaded) {
            loadConfig();
        }
        moreHudFlameBg = 0;
        saveConfig();
    }
    public static int getMoreHudFlameBgColor() { if (!loaded) loadConfig(); return moreHudFlameBgColor; }
    public static void setMoreHudFlameBgColor(int v) { moreHudFlameBgColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudFlameColor() { if (!loaded) loadConfig(); return moreHudFlameColor; }
    public static void setMoreHudFlameColor(int v) { moreHudFlameColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudKillEffectSpeedColor() { if (!loaded) loadConfig(); return moreHudKillEffectSpeedColor; }
    public static void setMoreHudKillEffectSpeedColor(int v) { moreHudKillEffectSpeedColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudKillEffectResistanceColor() { if (!loaded) loadConfig(); return moreHudKillEffectResistanceColor; }
    public static void setMoreHudKillEffectResistanceColor(int v) { moreHudKillEffectResistanceColor = v & 0xFFFFFF; saveConfig(); }
    public static int getMoreHudKillEffectRegenerationColor() { if (!loaded) loadConfig(); return moreHudKillEffectRegenerationColor; }
    public static void setMoreHudKillEffectRegenerationColor(int v) { moreHudKillEffectRegenerationColor = v & 0xFFFFFF; saveConfig(); }
    public static boolean isMoreHudFlameTimerBar() { if (!loaded) loadConfig(); return moreHudFlameTimerBar; }
    public static void setMoreHudFlameTimerBar(boolean v) { moreHudFlameTimerBar = v; saveConfig(); }

    public static int getMoreHudKillEffectColor(String effectCode) {
        if (!loaded) loadConfig();
        if (effectCode == null) {
            return moreHudFlameColor;
        }
        switch (effectCode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SPEED":
                return moreHudKillEffectSpeedColor;
            case "RESISTANCE":
                return moreHudKillEffectResistanceColor;
            case "REGENERATION":
                return moreHudKillEffectRegenerationColor;
            case "FLAME":
            case "RAPID_FIRE":
            default:
                return moreHudFlameColor;
        }
    }

    public static int getDefaultMoreHudKillEffectColor(String effectCode) {
        if (effectCode == null) {
            return 0xAA0000;
        }
        switch (effectCode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SPEED":
                return 0x55FFFF;
            case "RESISTANCE":
                return 0xFFAA00;
            case "REGENERATION":
                return 0xFF55FF;
            case "FLAME":
            case "RAPID_FIRE":
            default:
                return 0xAA0000;
        }
    }

    public static void setMoreHudKillEffectColor(String effectCode, int rgb) {
        if (!loaded) loadConfig();
        int v = rgb & 0xFFFFFF;
        if (effectCode == null) {
            moreHudFlameColor = v;
            saveConfig();
            return;
        }
        switch (effectCode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SPEED":
                moreHudKillEffectSpeedColor = v;
                break;
            case "RESISTANCE":
                moreHudKillEffectResistanceColor = v;
                break;
            case "REGENERATION":
                moreHudKillEffectRegenerationColor = v;
                break;
            case "FLAME":
            case "RAPID_FIRE":
            default:
                moreHudFlameColor = v;
                break;
        }
        saveConfig();
    }

    public static void resetMoreHudKillEffectColor(String effectCode) {
        setMoreHudKillEffectColor(effectCode, getDefaultMoreHudKillEffectColor(effectCode));
    }
    public static float getMoreHudFlameScale() { if (!loaded) loadConfig(); return moreHudFlameScale; }
    public static void setMoreHudFlameScale(float v) { moreHudFlameScale = clampMoreHudRowScale(v); saveConfig(); }
    public static int getMoreHudKillEffectsPosY() { if (!loaded) loadConfig(); return moreHudKillEffectsPosY; }
    public static void setMoreHudKillEffectsPosY(int v) { moreHudKillEffectsPosY = v; saveConfig(); }
    public static int getMoreHudKillEffectsOffsetCenterX() { if (!loaded) loadConfig(); return moreHudKillEffectsOffsetCenterX; }
    public static void setMoreHudKillEffectsOffsetCenterX(int v) { moreHudKillEffectsOffsetCenterX = v; saveConfig(); }
    public static float getMoreHudKillEffectsScale() { if (!loaded) loadConfig(); return moreHudKillEffectsScale; }
    public static void setMoreHudKillEffectsScale(float v) { moreHudKillEffectsScale = clampMoreHudRowScale(v); saveConfig(); }
    public static float getMoreHudKillEffectsIconScale() { if (!loaded) loadConfig(); return moreHudKillEffectsIconScale; }
    public static void setMoreHudKillEffectsIconScale(float v) {
        moreHudKillEffectsIconScale = clampMoreHudRowScale(v);
        saveConfig();
    }
    public static float getMoreHudKillEffectsTimerScale() { if (!loaded) loadConfig(); return moreHudKillEffectsTimerScale; }
    public static void setMoreHudKillEffectsTimerScale(float v) {
        moreHudKillEffectsTimerScale = clampMoreHudRowScale(v);
        saveConfig();
    }
    public static int getMoreHudKillEffectsIconNudgeX() { if (!loaded) loadConfig(); return moreHudKillEffectsIconNudgeX; }
    public static int getMoreHudKillEffectsIconNudgeY() { if (!loaded) loadConfig(); return moreHudKillEffectsIconNudgeY; }
    public static int getMoreHudKillEffectsTimerNudgeX() { if (!loaded) loadConfig(); return moreHudKillEffectsTimerNudgeX; }
    public static int getMoreHudKillEffectsTimerNudgeY() { if (!loaded) loadConfig(); return moreHudKillEffectsTimerNudgeY; }
    public static void setMoreHudKillEffectsIconNudge(int nx, int ny) {
        if (!loaded) {
            loadConfig();
        }
        moreHudKillEffectsIconNudgeX = nx;
        moreHudKillEffectsIconNudgeY = ny;
        saveConfig();
    }
    public static void setMoreHudKillEffectsTimerNudge(int nx, int ny) {
        if (!loaded) {
            loadConfig();
        }
        moreHudKillEffectsTimerNudgeX = nx;
        moreHudKillEffectsTimerNudgeY = ny;
        saveConfig();
    }
    public static void resetMoreHudKillEffectsInternalNudges() {
        if (!loaded) {
            loadConfig();
        }
        moreHudKillEffectsIconNudgeX = 16;
        moreHudKillEffectsIconNudgeY = -5;
        moreHudKillEffectsTimerNudgeX = -1;
        moreHudKillEffectsTimerNudgeY = 8;
        saveConfig();
    }

    public static boolean isMoreHudFlameCompact() { if (!loaded) loadConfig(); return moreHudFlameCompact; }
    public static void setMoreHudFlameCompact(boolean v) {
        if (!loaded) {
            loadConfig();
        }
        moreHudFlameCompact = v;
        moreHudPotionsShowFlamingArrows = !v;
        saveConfig();
    }
    public static int getMoreHudFlameCompactPosY() { if (!loaded) loadConfig(); return moreHudFlameCompactPosY; }
    public static void setMoreHudFlameCompactPosY(int v) { moreHudFlameCompactPosY = v; saveConfig(); }
    public static int getMoreHudFlameCompactOffsetCenterX() { if (!loaded) loadConfig(); return moreHudFlameCompactOffsetCenterX; }
    public static void setMoreHudFlameCompactOffsetCenterX(int v) { moreHudFlameCompactOffsetCenterX = v; saveConfig(); }
    public static float getMoreHudFlameCompactScale() { if (!loaded) loadConfig(); return moreHudFlameCompactScale; }
    public static void setMoreHudFlameCompactScale(float v) { moreHudFlameCompactScale = clampMoreHudRowScale(v); saveConfig(); }

    public static float getMoreHudFlameCompactIconScale() { if (!loaded) loadConfig(); return moreHudFlameCompactIconScale; }
    public static void setMoreHudFlameCompactIconScale(float v) {
        moreHudFlameCompactIconScale = clampMoreHudRowScale(v);
        saveConfig();
    }

    public static float getMoreHudFlameCompactTimerScale() { if (!loaded) loadConfig(); return moreHudFlameCompactTimerScale; }
    public static void setMoreHudFlameCompactTimerScale(float v) {
        moreHudFlameCompactTimerScale = clampMoreHudRowScale(v);
        saveConfig();
    }

    public static int getMoreHudFlameCompactIconNudgeX() { if (!loaded) loadConfig(); return moreHudFlameCompactIconNudgeX; }
    public static int getMoreHudFlameCompactIconNudgeY() { if (!loaded) loadConfig(); return moreHudFlameCompactIconNudgeY; }
    public static int getMoreHudFlameCompactTimerNudgeX() { if (!loaded) loadConfig(); return moreHudFlameCompactTimerNudgeX; }
    public static int getMoreHudFlameCompactTimerNudgeY() { if (!loaded) loadConfig(); return moreHudFlameCompactTimerNudgeY; }

    public static void setMoreHudFlameCompactIconNudge(int nx, int ny) {
        if (!loaded) {
            loadConfig();
        }
        moreHudFlameCompactIconNudgeX = nx;
        moreHudFlameCompactIconNudgeY = ny;
        saveConfig();
    }

    public static void setMoreHudFlameCompactTimerNudge(int nx, int ny) {
        if (!loaded) {
            loadConfig();
        }
        moreHudFlameCompactTimerNudgeX = nx;
        moreHudFlameCompactTimerNudgeY = ny;
        saveConfig();
    }

    public static void nudgeMoreHudFlameCompactIcon(int dx, int dy) {
        if (!loaded) {
            loadConfig();
        }
        setMoreHudFlameCompactIconNudge(
                moreHudFlameCompactIconNudgeX + dx,
                moreHudFlameCompactIconNudgeY + dy);
    }

    public static void nudgeMoreHudFlameCompactTimer(int dx, int dy) {
        if (!loaded) {
            loadConfig();
        }
        setMoreHudFlameCompactTimerNudge(
                moreHudFlameCompactTimerNudgeX + dx,
                moreHudFlameCompactTimerNudgeY + dy);
    }

    public static void resetMoreHudFlameCompactInternalNudges() {
        if (!loaded) {
            loadConfig();
        }
        moreHudFlameCompactIconNudgeX = 0;
        moreHudFlameCompactIconNudgeY = 0;
        moreHudFlameCompactTimerNudgeX = 0;
        moreHudFlameCompactTimerNudgeY = 0;
        saveConfig();
    }

    /** Row anchors + offsets + kill/flame-compact placement (author default HUD layout). */
    private static void applyDefaultMoreHudRowPositions() {
        moreHudOffsetCenterX = 0;
        moreHudPosY = 22;
        moreHudRowGap = 2;
        moreHudFpsPosY = 453;
        moreHudCpsPosY = 472;
        moreHudPingPosY = 462;
        moreHudPotionsPosY = 271;
        moreHudArrowsPosY = 458;
        moreHudFlamePosY = 197;
        moreHudKillEffectsPosY = 269;
        moreHudFpsOffsetCenterX = 113;
        moreHudCpsOffsetCenterX = 113;
        moreHudPingOffsetCenterX = 113;
        moreHudPotionsOffsetCenterX = -416;
        moreHudArrowsOffsetCenterX = -120;
        moreHudFlameOffsetCenterX = 0;
        moreHudKillEffectsOffsetCenterX = -16;
        moreHudKillEffectsIconNudgeX = 16;
        moreHudKillEffectsIconNudgeY = -5;
        moreHudKillEffectsTimerNudgeX = -1;
        moreHudKillEffectsTimerNudgeY = 8;
        moreHudFlameCompactPosY = 195;
        moreHudFlameCompactOffsetCenterX = 0;
        moreHudFlameCompactIconNudgeX = 12;
        moreHudFlameCompactIconNudgeY = 3;
        moreHudFlameCompactTimerNudgeX = -5;
        moreHudFlameCompactTimerNudgeY = 8;
    }

    /** Matches {@link #resetMoreHudAllScalesToDefault()} without touching disk (first-run install path). */
    private static void applyAuthorDefaultMoreHudScalesOnly() {
        moreHudScale = 1.0f;
        moreHudFpsScale = 0.7826087f;
        moreHudCpsScale = 0.7794118f;
        moreHudPingScale = 0.7786458f;
        moreHudPotionsScale = 1.0f;
        moreHudArrowsScale = 0.85f;
        moreHudFlameScale = 1.0f;
        moreHudKillEffectsScale = 1.0f;
        moreHudKillEffectsIconScale = 0.6003686f;
        moreHudKillEffectsTimerScale = 0.8333333f;
        moreHudFlameCompactScale = 1.9f;
        moreHudFlameCompactIconScale = 0.35f;
        moreHudFlameCompactTimerScale = 0.35f;
    }

    public static void resetMoreHudAllPositionsToDefault() {
        if (!loaded) {
            loadConfig();
        }
        applyDefaultMoreHudRowPositions();
        saveConfig();
    }

    public static void resetMoreHudAllScalesToDefault() {
        if (!loaded) {
            loadConfig();
        }
        applyAuthorDefaultMoreHudScalesOnly();
        saveConfig();
    }

    /**
     * Applies the same More HUD anchors, internal nudges, and scales as pressing
     * "Reset all row positions" and "Reset all row scales" in More HUD settings, merged into {@code root}'s
     * {@code moreHud} object. Other {@code moreHud} entries (colors, toggles, etc.) are left as-is unless missing
     * (then this creates {@code moreHud} and only sets geometry/scale keys).
     */
    public static void applyMoreHudFactoryLayoutAndScalesToConfigJson(JsonObject root) {
        if (root == null) {
            return;
        }
        JsonObject mh;
        if (root.has("moreHud") && root.get("moreHud").isJsonObject()) {
            mh = root.getAsJsonObject("moreHud");
        } else {
            mh = new JsonObject();
            root.add("moreHud", mh);
        }
        mh.addProperty("offsetCenterX", 0);
        mh.addProperty("posY", 22);
        mh.addProperty("rowGap", 2);
        mh.addProperty("fpsPosY", 453);
        mh.addProperty("cpsPosY", 472);
        mh.addProperty("pingPosY", 462);
        mh.addProperty("potionsPosY", 271);
        mh.addProperty("arrowsPosY", 458);
        mh.addProperty("flamePosY", 197);
        mh.addProperty("killEffectsPosY", 269);
        mh.addProperty("fpsOffsetCenterX", 113);
        mh.addProperty("cpsOffsetCenterX", 113);
        mh.addProperty("pingOffsetCenterX", 113);
        mh.addProperty("potionsOffsetCenterX", -416);
        mh.addProperty("arrowsOffsetCenterX", -120);
        mh.addProperty("flameOffsetCenterX", 0);
        mh.addProperty("killEffectsOffsetCenterX", -16);
        mh.addProperty("killEffectsIconNx", 16);
        mh.addProperty("killEffectsIconNy", -5);
        mh.addProperty("killEffectsTimerNx", -1);
        mh.addProperty("killEffectsTimerNy", 8);
        mh.addProperty("flameCompactPosY", 195);
        mh.addProperty("flameCompactOffsetCenterX", 0);
        mh.addProperty("flameCompactIconNx", 12);
        mh.addProperty("flameCompactIconNy", 3);
        mh.addProperty("flameCompactTimerNx", -5);
        mh.addProperty("flameCompactTimerNy", 8);
        mh.addProperty("coordsPosY", -2);
        mh.addProperty("coordsOffsetCenterX", -427);
        mh.addProperty("coordsScale", 0.75);
        mh.addProperty("scale", 1.0);
        mh.addProperty("fpsScale", 0.7826087);
        mh.addProperty("cpsScale", 0.7794118);
        mh.addProperty("pingScale", 0.7786458);
        mh.addProperty("potionsScale", 1.0);
        mh.addProperty("arrowsScale", 0.85);
        mh.addProperty("flameScale", 1.0);
        mh.addProperty("killEffectsScale", 1.0);
        mh.addProperty("killEffectsIconScale", 0.6003686);
        mh.addProperty("killEffectsTimerScale", 0.8333333);
        mh.addProperty("flameCompactScale", 1.9);
        mh.addProperty("flameCompactIconScale", 0.35);
        mh.addProperty("flameCompactTimerScale", 0.35);
    }

    /** Factory More HUD geometry + scales, then optional user kill-effects defaults file (matches first-install path). */
    public static void applyMoreHudDefaultsForNewProfile(JsonObject root) {
        applyMoreHudFactoryLayoutAndScalesToConfigJson(root);
        overlayUserHudDefaultsIntoConfigJson(root);
    }

    /** Factory FPS anchor and scale (More HUD settings "Reset position & scale"). */
    public static void resetMoreHudFpsPositionOnly() {
        if (!loaded) loadConfig();
        moreHudFpsPosY = 453;
        moreHudFpsOffsetCenterX = 113;
        moreHudFpsScale = 0.7826087f;
        saveConfig();
    }

    /** Factory CPS anchor and scale (More HUD settings "Reset position & scale"). */
    public static void resetMoreHudCpsPositionOnly() {
        if (!loaded) loadConfig();
        moreHudCpsPosY = 472;
        moreHudCpsOffsetCenterX = 113;
        moreHudCpsScale = 0.7794118f;
        saveConfig();
    }

    /** Factory ping anchor and scale (More HUD settings "Reset position & scale"). */
    public static void resetMoreHudPingPositionOnly() {
        if (!loaded) loadConfig();
        moreHudPingPosY = 462;
        moreHudPingOffsetCenterX = 113;
        moreHudPingScale = 0.7786458f;
        saveConfig();
    }

    public static void resetMoreHudPotionsPositionOnly() {
        if (!loaded) loadConfig();
        moreHudPotionsPosY = 271;
        moreHudPotionsOffsetCenterX = -416;
        saveConfig();
    }

    public static void resetMoreHudPotionsLayout() {
        if (!loaded) {
            loadConfig();
        }
        moreHudPotionsPosY = 271;
        moreHudPotionsOffsetCenterX = -416;
        moreHudPotionsScale = 1.0f;
        saveConfig();
    }

    /** Factory arrows anchor and scale (More HUD settings "Reset position & scale"). */
    public static void resetMoreHudArrowsPositionOnly() {
        if (!loaded) loadConfig();
        moreHudArrowsPosY = 458;
        moreHudArrowsOffsetCenterX = -120;
        moreHudArrowsScale = 0.85f;
        saveConfig();
    }

    public static void resetMoreHudArmorStatusPositionOnly() {
        if (!loaded) {
            loadConfig();
        }
        moreHudArmorStatusPosY = 374;
        moreHudArmorStatusOffsetCenterX = 418;
        moreHudArmorStatusScale = 1.0f;
        saveConfig();
    }

    public static void resetMoreHudFlamePositionOnly() {
        if (!loaded) loadConfig();
        moreHudFlamePosY = 197;
        moreHudFlameOffsetCenterX = 0;
        moreHudFlameCompactPosY = 195;
        moreHudFlameCompactOffsetCenterX = 0;
        moreHudFlameCompactIconNudgeX = 12;
        moreHudFlameCompactIconNudgeY = 3;
        moreHudFlameCompactTimerNudgeX = -5;
        moreHudFlameCompactTimerNudgeY = 8;
        saveConfig();
    }

    /** Resets Flaming Arrows standalone + compact anchors, internal nudges, and scales to factory defaults. */
    public static void resetMoreHudFlameLayout() {
        if (!loaded) {
            loadConfig();
        }
        moreHudFlamePosY = 197;
        moreHudFlameOffsetCenterX = 0;
        moreHudFlameCompactPosY = 195;
        moreHudFlameCompactOffsetCenterX = 0;
        moreHudFlameCompactIconNudgeX = 12;
        moreHudFlameCompactIconNudgeY = 3;
        moreHudFlameCompactTimerNudgeX = -5;
        moreHudFlameCompactTimerNudgeY = 8;
        moreHudFlameScale = 1.0f;
        moreHudFlameCompactScale = 1.9f;
        moreHudFlameCompactIconScale = 0.35f;
        moreHudFlameCompactTimerScale = 0.35f;
        moreHudFlameBg = 0;
        saveConfig();
    }

    public static void resetMoreHudKillEffectsPositionOnly() {
        if (!loaded) loadConfig();
        moreHudKillEffectsPosY = 269;
        moreHudKillEffectsOffsetCenterX = -16;
        moreHudKillEffectsIconNudgeX = 16;
        moreHudKillEffectsIconNudgeY = -5;
        moreHudKillEffectsTimerNudgeX = -1;
        moreHudKillEffectsTimerNudgeY = 8;
        saveConfig();
    }

    /** Resets Blitz kit timer HUD anchor and scale to factory defaults. */
    public static void resetBlitzKitTimerHudLayout() {
        if (!loaded) {
            loadConfig();
        }
        blitzKitTimerHudScale = 0.85f;
        blitzKitTimerHudOffsetCenterX = 0;
        blitzKitTimerHudPosY = 412;
        blitzKitTimerHudTextShadow = false;
        blitzKitTimerHudBg = 0;
        saveConfig();
    }

    /** Resets kill-effects HUD anchor, offsets, and row / part scales to factory defaults. */
    public static void resetMoreHudKillEffectsLayout() {
        if (!loaded) {
            loadConfig();
        }
        moreHudKillEffectsPosY = 269;
        moreHudKillEffectsOffsetCenterX = -16;
        moreHudKillEffectsIconNudgeX = 16;
        moreHudKillEffectsIconNudgeY = -5;
        moreHudKillEffectsTimerNudgeX = -1;
        moreHudKillEffectsTimerNudgeY = 8;
        moreHudKillEffectsScale = 1.0f;
        moreHudKillEffectsIconScale = 0.6003686f;
        moreHudKillEffectsTimerScale = 0.8333333f;
        saveConfig();
    }

    /**
     * Immutable snapshot of factory defaults: {@link #buildConfigJsonFromCurrentState()} captured during class init
     * (before {@link #loadConfig()} overwrites static fields), with the same {@code nickRealNametagOverlayEnabled}
     * override as creating a brand-new {@code gomodConfig.json}.
     */
    private static final JsonObject GOMOD_BUILTIN_DEFAULTS_CONFIG_JSON;
    static {
        boolean prevLoading = loading;
        loading = true;
        try {
            JsonObject snap = buildConfigJsonFromCurrentState();
            snap.addProperty("nickRealNametagOverlayEnabled", true);
            GOMOD_BUILTIN_DEFAULTS_CONFIG_JSON = snap;
        } finally {
            loading = prevLoading;
        }
    }

    /**
     * Writes built-in mod defaults to a file (e.g. new named profile). Does not modify live settings or API keys.
     */
    public static void writeBuiltinDefaultsProfileFile(File destination) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination");
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        JsonObject copy = new JsonParser().parse(GSON.toJson(GOMOD_BUILTIN_DEFAULTS_CONFIG_JSON)).getAsJsonObject();
        // Overlay optional user defaults (if present) so new defaults-based profiles match the user's HUD layout.
        overlayUserHudDefaultsIntoConfigJson(copy);
        try (BufferedWriter w = Files.newBufferedWriter(destination.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(copy, w);
        }
    }

    private static void applyUserHudDefaultsIfPresent() {
        try {
            File f = getUserDefaultsFile();
            if (!f.isFile()) {
                return;
            }
            JsonObject o;
            try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                o = new JsonParser().parse(r).getAsJsonObject();
            }
            if (o == null) {
                return;
            }
            JsonObject mh = o.has("moreHud") && o.get("moreHud").isJsonObject()
                    ? o.getAsJsonObject("moreHud") : new JsonObject();
            // Only touch kill-effects HUD defaults here (the user asked for that).
            if (mh.has("killEffectsPosY")) {
                try { moreHudKillEffectsPosY = mh.get("killEffectsPosY").getAsInt(); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsOffsetCenterX")) {
                try { moreHudKillEffectsOffsetCenterX = mh.get("killEffectsOffsetCenterX").getAsInt(); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsScale")) {
                try { moreHudKillEffectsScale = clampMoreHudRowScale(mh.get("killEffectsScale").getAsFloat()); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsIconScale")) {
                try { moreHudKillEffectsIconScale = clampMoreHudRowScale(mh.get("killEffectsIconScale").getAsFloat()); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsTimerScale")) {
                try { moreHudKillEffectsTimerScale = clampMoreHudRowScale(mh.get("killEffectsTimerScale").getAsFloat()); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsIconNx")) {
                try { moreHudKillEffectsIconNudgeX = mh.get("killEffectsIconNx").getAsInt(); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsIconNy")) {
                try { moreHudKillEffectsIconNudgeY = mh.get("killEffectsIconNy").getAsInt(); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsTimerNx")) {
                try { moreHudKillEffectsTimerNudgeX = mh.get("killEffectsTimerNx").getAsInt(); } catch (Throwable ignored) {}
            }
            if (mh.has("killEffectsTimerNy")) {
                try { moreHudKillEffectsTimerNudgeY = mh.get("killEffectsTimerNy").getAsInt(); } catch (Throwable ignored) {}
            }
            if (o.has("blitzKitTimerHudScale")) {
                try { blitzKitTimerHudScale = Math.max(0.5f, Math.min(5.0f, o.get("blitzKitTimerHudScale").getAsFloat())); } catch (Throwable ignored) {}
            }
            if (o.has("blitzKitTimerHudOffsetCenterX")) {
                try { blitzKitTimerHudOffsetCenterX = o.get("blitzKitTimerHudOffsetCenterX").getAsInt(); } catch (Throwable ignored) {}
            }
            if (o.has("blitzKitTimerHudPosY")) {
                try { blitzKitTimerHudPosY = o.get("blitzKitTimerHudPosY").getAsInt(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persistUserHudDefaultsQuietly() {
        try {
            File dir = getGomodDataDirectory();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            File f = getUserDefaultsFile();
            JsonObject root = new JsonObject();
            JsonObject mh = new JsonObject();
            mh.addProperty("killEffectsPosY", moreHudKillEffectsPosY);
            mh.addProperty("killEffectsOffsetCenterX", moreHudKillEffectsOffsetCenterX);
            mh.addProperty("killEffectsScale", moreHudKillEffectsScale);
            mh.addProperty("killEffectsIconScale", moreHudKillEffectsIconScale);
            mh.addProperty("killEffectsTimerScale", moreHudKillEffectsTimerScale);
            mh.addProperty("killEffectsIconNx", moreHudKillEffectsIconNudgeX);
            mh.addProperty("killEffectsIconNy", moreHudKillEffectsIconNudgeY);
            mh.addProperty("killEffectsTimerNx", moreHudKillEffectsTimerNudgeX);
            mh.addProperty("killEffectsTimerNy", moreHudKillEffectsTimerNudgeY);
            root.add("moreHud", mh);
            root.addProperty("blitzKitTimerHudScale", blitzKitTimerHudScale);
            root.addProperty("blitzKitTimerHudOffsetCenterX", blitzKitTimerHudOffsetCenterX);
            root.addProperty("blitzKitTimerHudPosY", blitzKitTimerHudPosY);
            try (BufferedWriter w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void overlayUserHudDefaultsIntoConfigJson(JsonObject configJson) {
        try {
            if (configJson == null) {
                return;
            }
            File f = getUserDefaultsFile();
            if (!f.isFile()) {
                return;
            }
            JsonObject user;
            try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                user = new JsonParser().parse(r).getAsJsonObject();
            }
            if (user == null) {
                return;
            }
            if (user.has("moreHud") && user.get("moreHud").isJsonObject()) {
                JsonObject userMh = user.getAsJsonObject("moreHud");
                JsonObject mh;
                if (configJson.has("moreHud") && configJson.get("moreHud").isJsonObject()) {
                    mh = configJson.getAsJsonObject("moreHud");
                } else {
                    mh = new JsonObject();
                    configJson.add("moreHud", mh);
                }
                // Copy only the kill-effects row defaults.
                if (userMh.has("killEffectsPosY")) mh.add("killEffectsPosY", userMh.get("killEffectsPosY"));
                if (userMh.has("killEffectsOffsetCenterX")) mh.add("killEffectsOffsetCenterX", userMh.get("killEffectsOffsetCenterX"));
                if (userMh.has("killEffectsScale")) mh.add("killEffectsScale", userMh.get("killEffectsScale"));
                if (userMh.has("killEffectsIconScale")) mh.add("killEffectsIconScale", userMh.get("killEffectsIconScale"));
                if (userMh.has("killEffectsTimerScale")) mh.add("killEffectsTimerScale", userMh.get("killEffectsTimerScale"));
                if (userMh.has("killEffectsIconNx")) mh.add("killEffectsIconNx", userMh.get("killEffectsIconNx"));
                if (userMh.has("killEffectsIconNy")) mh.add("killEffectsIconNy", userMh.get("killEffectsIconNy"));
                if (userMh.has("killEffectsTimerNx")) mh.add("killEffectsTimerNx", userMh.get("killEffectsTimerNx"));
                if (userMh.has("killEffectsTimerNy")) mh.add("killEffectsTimerNy", userMh.get("killEffectsTimerNy"));
                if (userMh.has("killEffectsBg")) mh.add("killEffectsBg", userMh.get("killEffectsBg"));
                if (userMh.has("killEffectsShadow")) mh.add("killEffectsShadow", userMh.get("killEffectsShadow"));
            }
            if (user.has("blitzKitTimerHudScale")) {
                configJson.add("blitzKitTimerHudScale", user.get("blitzKitTimerHudScale"));
            }
            if (user.has("blitzKitTimerHudOffsetCenterX")) {
                configJson.add("blitzKitTimerHudOffsetCenterX", user.get("blitzKitTimerHudOffsetCenterX"));
            }
            if (user.has("blitzKitTimerHudPosY")) {
                configJson.add("blitzKitTimerHudPosY", user.get("blitzKitTimerHudPosY"));
            }
        } catch (Throwable ignored) {
        }
    }

	public static boolean isBlitzChallengesHudEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengesHudEnabled;
	}

	public static void setBlitzChallengesHudEnabled(boolean value) {
		blitzChallengesHudEnabled = value;
		saveConfig();
	}

	public static int getBlitzChallengesHudPosX() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengesHudPosX;
	}

	public static void setBlitzChallengesHudPosX(int value) {
		blitzChallengesHudPosX = value;
		saveConfig();
	}

	public static int getBlitzChallengesHudPosY() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengesHudPosY;
	}

	public static void setBlitzChallengesHudPosY(int value) {
		blitzChallengesHudPosY = value;
		saveConfig();
	}

	public static float getBlitzChallengesHudScale() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengesHudScale;
	}

	public static void setBlitzChallengesHudScale(float value) {
		blitzChallengesHudScale = Math.max(0.5f, Math.min(4.0f, value));
		saveConfig();
	}

	public static boolean isBlitzChallengesHudTextShadow() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengesHudTextShadow;
	}

	public static void setBlitzChallengesHudTextShadow(boolean value) {
		blitzChallengesHudTextShadow = value;
		saveConfig();
	}

	public static int getBlitzChallengesHudBg() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengesHudBg;
	}

	public static void setBlitzChallengesHudBg(int value) {
		blitzChallengesHudBg = clamp255(value);
		saveConfig();
	}

	public static int getBlitzChallengesHudBgColor() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengesHudBgColor;
	}

	public static void setBlitzChallengesHudBgColor(int value) {
		blitzChallengesHudBgColor = value & 0xFFFFFF;
		saveConfig();
	}

	public static void resetBlitzChallengesHudLayout() {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengesHudPosX = 4;
		blitzChallengesHudPosY = 4;
		blitzChallengesHudScale = 1.0f;
		blitzChallengesHudTextShadow = true;
		blitzChallengesHudBg = 144;
		blitzChallengesHudBgColor = 0x000000;
		saveConfig();
	}

	public static Set<String> getBlitzChallengesCompletedTiers() {
		if (!loaded) {
			loadConfig();
		}
		return new HashSet<>(blitzChallengesCompletedTiers);
	}

	public static void addBlitzChallengeCompletedTier(String tierKey) {
		if (tierKey == null || tierKey.isEmpty()) {
			return;
		}
		if (!loaded) {
			loadConfig();
		}
		if (blitzChallengesCompletedTiers.add(tierKey)) {
			saveConfig();
		}
	}

	public static void removeBlitzChallengeCompletedTier(String tierKey) {
		if (tierKey == null || tierKey.isEmpty()) {
			return;
		}
		if (!loaded) {
			loadConfig();
		}
		if (blitzChallengesCompletedTiers.remove(tierKey)) {
			saveConfig();
		}
	}

	public static void clearBlitzChallengesCompletedTiers() {
		if (!loaded) {
			loadConfig();
		}
		if (!blitzChallengesCompletedTiers.isEmpty()) {
			blitzChallengesCompletedTiers.clear();
			saveConfig();
		}
	}

	public static boolean isBlitzChallengeBadgeEnabled() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengeBadgeEnabled;
	}

	public static void setBlitzChallengeBadgeEnabled(boolean enabled) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgeEnabled = enabled;
		saveConfig();
	}

	public static String getBlitzChallengeBadgeEquippedKey() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengeBadgeEquippedKey == null ? "" : blitzChallengeBadgeEquippedKey;
	}

	public static void setBlitzChallengeBadgeEquippedKey(String key) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgeEquippedKey = key == null ? "" : key;
		saveConfig();
	}

	public static float getBlitzChallengeBadgeOffsetX() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengeBadgeOffsetX;
	}

	public static void setBlitzChallengeBadgeOffsetX(float value) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgeOffsetX = value;
		saveConfig();
	}

	public static float getBlitzChallengeBadgeOffsetY() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengeBadgeOffsetY;
	}

	public static void setBlitzChallengeBadgeOffsetY(float value) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgeOffsetY = value;
		saveConfig();
	}

	public static float getBlitzChallengeBadgeOffsetZ() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengeBadgeOffsetZ;
	}

	public static void setBlitzChallengeBadgeOffsetZ(float value) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgeOffsetZ = value;
		saveConfig();
	}

	public static float getBlitzChallengeBadgeScale() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengeBadgeScale;
	}

	public static void setBlitzChallengeBadgeScale(float value) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgeScale = Math.max(0.35f, Math.min(1.85f, value));
		saveConfig();
	}

	public static String getBlitzChallengeBadgeAttachPart() {
		if (!loaded) {
			loadConfig();
		}
		return blitzChallengeBadgeAttachPart == null ? "body" : blitzChallengeBadgeAttachPart;
	}

	public static void setBlitzChallengeBadgeAttachPart(String part) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgeAttachPart = part == null ? "body" : part;
		saveConfig();
	}

	public static JsonArray getBlitzChallengeBadgePlacements() {
		if (!loaded) {
			loadConfig();
		}
		return cloneJsonArray(blitzChallengeBadgePlacements);
	}

	public static void setBlitzChallengeBadgePlacements(JsonArray placements) {
		if (!loaded) {
			loadConfig();
		}
		blitzChallengeBadgePlacements = cloneJsonArray(placements);
		saveConfig();
	}

	private static JsonArray cloneJsonArray(JsonArray source) {
		if (source == null) {
			return new JsonArray();
		}
		try {
			return new JsonParser().parse(source.toString()).getAsJsonArray();
		} catch (Throwable ignored) {
			return new JsonArray();
		}
	}
}
