package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;
import me.napim.gomod.Main;
import me.napim.gomod.command.DisguiseCommand;
import me.napim.gomod.gui.DisguiseHypixelDecisionDialog;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import me.napim.gomod.util.GameContext;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemSkull;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.io.File;
import java.io.InputStreamReader;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.texture.DynamicTexture;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side local disguise for the local player only.
 * This never changes packets or server-side identity.
 */
public final class LocalDisguise {
    private static final Pattern LEADERBOARD_ENTRY_PATTERN = Pattern.compile("^\\s*(\\d[\\d,]*)\\.\\s+(.+?)\\s+-\\s+([\\d,]+)\\s*$");
    private static final Pattern BLITZ_COIN_CHEST_ANNOUNCEMENT_PATTERN = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]*\\]\\s*)*([A-Za-z0-9_]{1,16})\\s+found\\s+[\\d,]+\\s+coins\\s+in\\s+a\\s+chest!\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BLITZ_REKT_FINISHER_HOLOGRAM_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9_]{1,16})\\s+has\\s+#rekt\\s+[A-Za-z0-9_]{1,16}\\s+here\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENDGAME_PLACEMENT_PREFIX = Pattern.compile("^\\s*(?:\\d+(?:st|nd|rd|th)\\s*-|winning team\\s*-|winner\\s*-)", Pattern.CASE_INSENSITIVE);
    /** First {@code [...]} span at start of a formatted string (for stripping Hypixel BSG2 kill brackets). */
    private static final Pattern LEADING_FORMATTED_BRACKET = Pattern.compile(
            "^((?:\u00A7[0-9a-fk-or])*)\\[([^\\]]+)\\]((?:\u00A7[0-9a-fk-or])*)(\\s*)",
            Pattern.CASE_INSENSITIVE);
    private static final String CAPES_DEV_LOAD_URL = "https://api.capes.dev/load/";
    private static final String CAPES_DEV_HISTORY_URL = "https://api.capes.dev/history/";
    private static final String[] CAPES_DEV_HISTORY_TYPES = new String[]{"minecraft", "optifine", "minecraftcapes"};
    private static final String NO_CAPE_OPTION_KEY = "gomod:none";
    private static final String CATALOG_PREVIEW_CAPE_SOURCE = "gomod_catalog_preview";
    private static final long HYPIXEL_DISGUISE_CACHE_WINDOW_MS = 60_000L;
    private static boolean enabled = false;
    // Volatile mirror of `enabled`, readable from the render thread without acquiring
    // the class-level lock. Used as a cheap pre-check in FontRendererMixin so that
    // the synchronized replaceRenderedText() is skipped entirely when inactive.
    public static volatile boolean fastEnabledFlag = false;
    /** Dev FPS-isolation switches used by hot mixin paths without taking the LocalDisguise monitor. */
    public static volatile boolean fastRenderTextRewriteFlag = false;
    public static volatile boolean fastTextureBindOverrideFlag = false;
    public static volatile UUID fastRealUuid = null;
    /** Volatile mirrors used by FontRendererMixin to avoid entering the synchronized rewrite path for unrelated text. */
    public static volatile String fastRealNameText = null;
    public static volatile String fastTargetNameText = null;
    public static volatile boolean fastSidebarRenderActive = false;
    /** True when using a fake nick (non-Mojang) with optional custom/skin-from-file (see {@link #enableNickDisguise}). */
    private static boolean nickDisguiseActive = false;

    private static String realName = null;
    private static UUID realUuid = null;

    private static String targetName = null;
    /** TAB/chat display (may include § from & codes); falls back to {@link #targetName}. */
    private static String targetTabChatDisplayName = null;
    private static String targetUuid = null; // undashed
    private static UUID targetUuidDashed = null;
    private static GameProfile targetProfile = null;
    /** Render-only logo policy set by /disguise and cleared with the local disguise. */
    private static volatile DisguiserLunarIcon localDisguiseLunarIconOverride = DisguiserLunarIcon.KEEP;

    private static String skinType = "default";
    /*
     * Render-thread snapshots. TextureManagerMixin reads these on every texture bind, so they must be safely
     * publishable without taking the LocalDisguise monitor in that extremely hot path.
     */
    private static volatile ResourceLocation skinLocation = null;
    private static volatile ResourceLocation capeLocation = null;
    /** Once the user picks/cycles a disguise cape, keep that local choice ahead of relay wear. */
    private static boolean localDisguiseCapeSelectionExplicit = false;
    private static boolean hasCustomSkin = false;
    private static boolean hasCustomCape = false;
    private static volatile ResourceLocation realSkinLocation = null;
    private static volatile ResourceLocation realCapeLocation = null;
    private static volatile ResourceLocation realUsernameSkinLocation = null;
    private static String realRank = "Default";
    private static String realRankPlusColor = "";
    private static String realMonthlyRankColor = "";
    private static boolean realRankKnown = false;
    private static int realNetworkLevel = 1;
    private static float realNetworkProgress = 0.0F;
    private static int realNetworkExperienceTotal = 0;
    private static boolean realNetworkLevelKnown = false;

    private static String rank = "Default";
    private static String rankPlusColor = "";
    private static String monthlyRankColor = "";
    /** Snapshot of target Hypixel API rank at disguise enable (restored for Target's prefix without re-fetching). */
    private static String targetApiRank = "Default";
    private static String targetApiRankPlusColor = "";
    private static String targetApiMonthlyRankColor = "";
    private static boolean rankKnown = false;
    /** When true, {@link #buildRankTag()} uses {@link #hypixelCustomRankTagValue} only. */
    private static boolean hypixelCustomRankTagActive = false;
    private static String hypixelCustomRankTagValue = "";
    private static boolean targetChatKillCounterEnabled = false;
    private static boolean targetAlternativeKillMessageEnabled = false;
    private static String targetGuildTag = "";
    private static String targetGuildTagColor = "";
    private static String targetGuildRank = "";
    private static int targetNetworkLevel = 1;
    private static float targetNetworkProgress = 0.0F;
    private static int targetNetworkExperienceTotal = 0;
    private static boolean targetNetworkLevelKnown = false;
    private static int targetAchievementPoints = 0;
    private static boolean targetAchievementPointsKnown = false;
    private static int lastAppliedNetworkLevel = 1;
    private static float lastAppliedNetworkProgress = 0.0F;
    private static boolean lastAppliedNetworkKnown = false;
    private static long xpOverrideGraceUntilMs = 0L;
    private static volatile int matchKillOffset = 0;
    private static volatile String lastCountedKillMessage = null;
    private static volatile long lastCountedKillMessageMs = 0L;
    private static volatile int fastTargetBlitzKills = -1;
    private static final Map<String, String> frozenKillMessageCache = new LinkedHashMap<>();
    /** Kill-feed {@code victim … by killer} markers (lowercase); keep in sync with KillCounter broadcast patterns. */
    private static final String[] KILL_FEED_BY_MARKERS = {
            " was killed by ",
            " were killed by ",
            " was slain by ",
            " were slain by ",
            " was shot by ",
            " were shot by ",
            " was blown up by ",
            " were blown up by ",
            " was burned to death by ",
            " were burned to death by ",
            " was frozen to death by ",
            " were frozen to death by ",
            " was electrocuted by ",
            " were electrocuted by ",
            " was eliminated by ",
            " were eliminated by ",
            " was defeated by ",
            " were defeated by "
    };
    private static BlitzStats targetBlitzStats = BlitzStats.empty();
    private static ScoreObjective lastSidebarObjective = null;
    private static long lastSidebarObjectiveCheckMs = 0L;
    private static boolean lastSidebarObjectiveMatches = false;

    /** Raw scoreboard entry key → hide the numeric score column for that row (value is inlined in the text). */
    private static final java.util.HashMap<String, Boolean> sidebarScoreColumnSuppressByEntry = new java.util.HashMap<>();
    /** Set in {@link #rewriteSidebarScorePoints} so {@link #shouldHideSidebarRenderedScoreText(String)} can key off the row. */
    private static final ThreadLocal<String> sidebarLastRenderedScoreRowKey = new ThreadLocal<>();

    /** While a disguise is loading on Hypixel, skip drawing the sidebar so real stats do not flash. */
    public static boolean shouldDeferScoreboardDuringDisguiseLoad() {
        if (!DisguiseCommand.isLoading()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && isOnHypixelServer(mc);
    }

    /** Unicode punctuation Hypixel uses on hub stat rows — normalize so aliases like {@code achievements:} match. */
    private static String normalizeSidebarTextForStatAliases(String visible) {
        if (visible == null || visible.isEmpty()) {
            return visible;
        }
        String s = visible;
        s = s.replace('\uFF1A', ':').replace('\uFE55', ':').replace('\u2236', ':');
        s = s.replace('\u2013', '-').replace('\u2014', '-');
        // Hypixel sometimes embeds ZWSP / word joiners between the label and colon — breaks "achievements:" alias match.
        s = s.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "").replace("\uFEFF", "").replace("\u2060", "");
        return s;
    }

    /** Label/value separator on sidebar rows (ASCII + common Unicode colon variants). */
    private static boolean isSidebarLabelValueSeparatorChar(char c) {
        return c == ':' || c == '\uFF1A' || c == '\uFE55' || c == '\u2236';
    }

    private static int skipSidebarLabelValueSeparator(String visible, int index) {
        if (visible == null || index < 0 || index >= visible.length()) {
            return index;
        }
        if (isSidebarLabelValueSeparatorChar(visible.charAt(index))) {
            return skipSidebarLeadWhitespaceAndInvisible(visible, index + 1);
        }
        return index;
    }

    /**
     * Hypixel hub rows use an emoji as the scoreboard "player name" between the label and digits, e.g.
     * {@code Hypixel Level: 🐍409} or {@code Achievements: 🔮22,840}. Skip those so value scan starts at the number.
     */
    private static int skipSidebarLeadingEmojiDecorBeforeDigits(String visible, int start) {
        if (visible == null || start < 0) {
            return start;
        }
        int i = skipSidebarLeadWhitespaceAndInvisible(visible, start);
        while (i < visible.length()) {
            int cp = Character.codePointAt(visible, i);
            int len = Character.charCount(cp);
            if (Character.isDigit(cp)) {
                return i;
            }
            if (isSidebarHypixelDigitBridgeCodePoint(cp)) {
                i += len;
                i = skipSidebarLeadWhitespaceAndInvisible(visible, i);
                continue;
            }
            break;
        }
        return i;
    }

    private static ScoreObjective activeSidebarRenderObjective = null;
    private static int activeSidebarRenderDepth = 0;
    private static long lastLeaderboardTargetSeenMs = 0L;
    private static List<CapeOption> capeOptions = Collections.emptyList();
    private static int selectedCapeIndex = -1;
    private static List<CapeOptionInfo> disguiseCapeGuiOptionsCache = null;
    private static List<CapeOption> localSelfCapeOptions = Collections.emptyList();
    private static List<CapeOptionInfo> localSelfCapeGuiOptionsCache = null;
    /** Per-account cape catalog cache for this Minecraft session. Network refreshes can add to it, never shrink it. */
    private static final Map<UUID, List<CapeOption>> sessionCapeOptionCacheByUuid = new HashMap<UUID, List<CapeOption>>();
    /** Session player UUID the self-cape catalog was built for (account-switch detection). */
    private static UUID localSelfCapeCatalogUuid = null;
    /** Bump when cape catalog parsing changes so cached lists refetch after mod updates. */
    private static final int LOCAL_SELF_CAPE_CATALOG_SCHEMA_VERSION = 7;
    private static int localSelfCapeCatalogSchemaVersion = 0;
    private static volatile boolean localSelfCapeLoadStarted;
    /** True after a background fetch finishes (even when the account has zero capes). */
    private static volatile boolean localSelfCapeLoadComplete;
    private static Map<String, DisguiseKitInfo> targetKitInfoByName = Collections.emptyMap();
    private static final Map<String, CachedHypixelDisguiseData> hypixelDisguiseDataCache = new HashMap<>();
    private static final java.util.LinkedHashMap<String, String> renderCache = new RenderCache();
    private static final int FAST_RENDER_CACHE_SIZE = 2048;
    private static final int FAST_RENDER_CACHE_MASK = FAST_RENDER_CACHE_SIZE - 1;
    /** Direct-mapped and lock-free: render/get-width calls share both positive and negative rewrite results. */
    private static final AtomicReferenceArray<FastRenderCacheEntry> fastRenderCache =
            new AtomicReferenceArray<FastRenderCacheEntry>(FAST_RENDER_CACHE_SIZE);
    private static volatile long fastRenderCacheGeneration = 1L;
    /** FontRenderer asks the prefilter before the rewrite cache; victim rows repeat these unchanged strings. */
    private static final DisguiseTextCandidateCache nonCandidateRenderText = new DisguiseTextCandidateCache();

    private static final class RenderCache extends java.util.LinkedHashMap<String, String> {
        RenderCache() { super(512, 0.75f, true); }
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
            return size() > 512;
        }
    }

    private static final class FastRenderCacheEntry {
        private final long generation;
        private final String input;
        private final String output;

        private FastRenderCacheEntry(long generation, String input, String output) {
            this.generation = generation;
            this.input = input;
            this.output = output;
        }
    }
    private static boolean liveOverrideReflectionReady = false;
    private static boolean liveOverrideReflectionOk = false;
    private static Class<?> liveOverrideClass = null;
    private static java.lang.reflect.Field liveFieldPlayerTexturesLoaded = null;
    private static java.lang.reflect.Field liveFieldLocationSkin = null;
    private static java.lang.reflect.Field liveFieldLocationCape = null;
    private static java.lang.reflect.Field liveFieldSkinType = null;
    private static java.lang.reflect.Field liveFieldDisplayName = null;
    private static IChatComponent liveOriginalDisplayName = null;
    private static String lastBuiltTabDisplaySource = null;
    private static String lastBuiltTabDisplayBaseUsername = null;
    private static IChatComponent lastBuiltTabDisplayComponent = null;

    private LocalDisguise() {
    }

    /** How to source the skin for {@link #enableNickDisguise}. */
    public enum NickSkinMode {
        STEVE,
        PLAYER_SKIN,
        SKIN_FILE
    }

    /**
     * Developer-only runtime variants for isolating disguise FPS costs. The command that selects these modes is
     * registered only in internal builds, but keeping the state here lets every hot hook use the same component mask.
     */
    public enum FpsDebugMode {
        FULL("full", "normal disguise baseline"),
        NO_SKIN("noskin", "no target skin/model override; cape and text stay enabled"),
        NO_CAPE("nocape", "target skin, but no disguise cape"),
        NO_TEXTURE_BIND("nobind", "skip the global TextureManager bind remapper"),
        NO_RENDER_TEXT("notext", "skip FontRenderer/chat text rewrites"),
        NO_LIVE_SYNC("nolive", "skip periodic NetworkPlayerInfo field synchronization"),
        SKIN_ONLY("skinonly", "target skin only; no cape, text, live sync, or HUD/data overrides"),
        TEXT_ONLY("textonly", "name/text rewrites only; no textures, live sync, or HUD/data overrides"),
        MINIMAL("minimal", "loaded disguise state with render/profile/HUD overrides bypassed");

        public final String commandName;
        public final String description;

        FpsDebugMode(String commandName, String description) {
            this.commandName = commandName;
            this.description = description;
        }

        public static FpsDebugMode parse(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if ("full".equals(key) || "baseline".equals(key)) return FULL;
            if ("noskin".equals(key)) return NO_SKIN;
            if ("nocape".equals(key)) return NO_CAPE;
            if ("nobind".equals(key) || "notexturebind".equals(key)) return NO_TEXTURE_BIND;
            if ("notext".equals(key) || "norendertext".equals(key)) return NO_RENDER_TEXT;
            if ("nolive".equals(key) || "nolivesync".equals(key)) return NO_LIVE_SYNC;
            if ("skinonly".equals(key)) return SKIN_ONLY;
            if ("textonly".equals(key)) return TEXT_ONLY;
            if ("minimal".equals(key) || "stateonly".equals(key)) return MINIMAL;
            return null;
        }
    }

    private static volatile FpsDebugMode fpsDebugMode = FpsDebugMode.FULL;
    /** Serializes command intent against slow profile/texture loads. Guarded by LocalDisguise.class. */
    private static long enableRequestGeneration;

    /** Reserve the newest local disguise request before its worker starts doing I/O. */
    public static synchronized long beginEnableRequest() {
        return ++enableRequestGeneration;
    }

    /** Invalidate in-flight enable work when the user explicitly turns the disguise off. */
    public static synchronized void cancelPendingEnableRequests() {
        enableRequestGeneration++;
    }

    public static synchronized boolean isEnableRequestCurrent(long requestGeneration) {
        return requestGeneration == enableRequestGeneration;
    }

    private static void requireCurrentEnableRequest(long requestGeneration) {
        if (requestGeneration != enableRequestGeneration) {
            throw new CancellationException("Disguise request was cancelled by a newer command.");
        }
    }

    /** Thrown when nick disguise uses a name that exists on Mojang (see nick conflict GUI). */
    public static final class NickIsMojangAccountException extends Exception {
        private final String mojangName;
        private final String uuidUndashed;

        public NickIsMojangAccountException(String mojangName, String uuidUndashed) {
            this.mojangName = mojangName == null ? "" : mojangName;
            this.uuidUndashed = uuidUndashed == null ? "" : uuidUndashed.replace("-", "");
        }

        public String getMojangName() {
            return mojangName;
        }

        public String getUuidUndashed() {
            return uuidUndashed;
        }
    }

    public static EnableResult enable(String playerName) throws Exception {
        return enable(playerName, beginEnableRequest());
    }

    public static EnableResult enable(String playerName, long requestGeneration) throws Exception {
        return finishEnable(resolveTarget(playerName), HypixelDisguiseDataMode.AUTO,
                false, FpsDebugMode.FULL, requestGeneration);
    }

    /** Full Lunar disguises already have the target UUID; avoid another name-to-UUID lookup. */
    public static EnableResult enable(GameProfile profile) throws Exception {
        return enable(profile, beginEnableRequest());
    }

    public static EnableResult enable(GameProfile profile, long requestGeneration) throws Exception {
        if (profile == null || profile.getId() == null || profile.getName() == null
                || profile.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("A target name and UUID are required");
        }
        return finishEnable(resolveTarget(profile), HypixelDisguiseDataMode.AUTO,
                false, FpsDebugMode.FULL, requestGeneration);
    }

    /** Automatic kill swaps use the victim's server profile and cached data without opening a combat-time dialog. */
    public static EnableResult enableKilledPlayer(String playerName, GameProfile knownProfile,
                                                   long requestGeneration) throws Exception {
        ResolvedTarget target = knownProfile != null && knownProfile.getId() != null
                && playerName != null && playerName.equalsIgnoreCase(knownProfile.getName())
                ? resolveTarget(knownProfile) : resolveTarget(playerName);
        try {
            return finishEnable(target, HypixelDisguiseDataMode.AUTO,
                    false, FpsDebugMode.FULL, requestGeneration);
        } catch (HypixelDataDecisionRequiredException useCache) {
            synchronized (LocalDisguise.class) {
                requireCurrentEnableRequest(requestGeneration);
            }
            return finishEnable(target, HypixelDisguiseDataMode.CACHE_ONLY,
                    false, FpsDebugMode.FULL, requestGeneration);
        }
    }

    public static EnableResult enableUsingCachedHypixelData(String playerName) throws Exception {
        return enableUsingCachedHypixelData(playerName, beginEnableRequest());
    }

    public static EnableResult enableUsingCachedHypixelData(String playerName, long requestGeneration) throws Exception {
        return finishEnable(resolveTarget(playerName), HypixelDisguiseDataMode.CACHE_ONLY,
                false, FpsDebugMode.FULL, requestGeneration);
    }

    public static EnableResult enableFreshHypixelData(String playerName) throws Exception {
        return enableFreshHypixelData(playerName, beginEnableRequest());
    }

    public static EnableResult enableFreshHypixelData(String playerName, long requestGeneration) throws Exception {
        return finishEnable(resolveTarget(playerName), HypixelDisguiseDataMode.FRESH_ONLY,
                false, FpsDebugMode.FULL, requestGeneration);
    }

    /** Internal-build diagnostic entry point; see {@code /disguisefps}. */
    public static EnableResult enableForFpsDebug(String playerName, FpsDebugMode mode) throws Exception {
        return enableForFpsDebug(playerName, mode, beginEnableRequest());
    }

    public static EnableResult enableForFpsDebug(String playerName, FpsDebugMode mode,
                                                  long requestGeneration) throws Exception {
        FpsDebugMode selected = mode == null ? FpsDebugMode.FULL : mode;
        return finishEnable(resolveTarget(playerName), HypixelDisguiseDataMode.AUTO,
                false, selected, requestGeneration);
    }

    /**
     * Local disguise as a username that is not registered with Mojang (offline UUID). Skin: Steve, copy of a real player, or PNG file.
     *
     * @throws NickIsMojangAccountException if {@code nick} resolves on Mojang — open conflict GUI instead of failing in chat only.
     */
    public static EnableResult enableNickDisguise(String nick, NickSkinMode skinMode, String skinRef) throws Exception {
        return enableNickDisguise(nick, skinMode, skinRef, beginEnableRequest());
    }

    public static EnableResult enableNickDisguise(String nick, NickSkinMode skinMode, String skinRef,
                                                   long requestGeneration) throws Exception {
        return finalizeNickDisguiseEnable(buildNickResolvedTarget(nick, skinMode, skinRef, false),
                skinMode, skinRef, nick, requestGeneration);
    }

    /**
     * Same as {@link #enableNickDisguise} but allows names that exist on Mojang (offline UUID + chosen skin mode). Use only after user confirms in GUI.
     */
    public static EnableResult enableNickDisguiseAllowingTakenName(String nick, NickSkinMode skinMode, String skinRef) throws Exception {
        return enableNickDisguiseAllowingTakenName(nick, skinMode, skinRef, beginEnableRequest());
    }

    public static EnableResult enableNickDisguiseAllowingTakenName(String nick, NickSkinMode skinMode,
                                                                    String skinRef, long requestGeneration) throws Exception {
        return finalizeNickDisguiseEnable(buildNickResolvedTarget(nick, skinMode, skinRef, true),
                skinMode, skinRef, nick, requestGeneration);
    }

    private static EnableResult finalizeNickDisguiseEnable(ResolvedTarget resolvedTarget, NickSkinMode skinMode, String skinRef,
                                                           String nickFieldRawForReconnect,
                                                           long requestGeneration) throws Exception {
        EnableResult result = finishEnable(resolvedTarget, HypixelDisguiseDataMode.AUTO,
                true, FpsDebugMode.FULL, requestGeneration);
        synchronized (LocalDisguise.class) {
            requireCurrentEnableRequest(requestGeneration);
            ConfigManager.saveDisguiseReconnectSnapshotNick(resolvedTarget.name,
                    skinMode == null ? NickSkinMode.STEVE.name() : skinMode.name(),
                    skinRef == null ? "" : skinRef,
                    nickFieldRawForReconnect);
        }
        return result;
    }

    private static EnableResult finishEnable(ResolvedTarget resolvedTarget, HypixelDisguiseDataMode hypixelDataMode,
                                             boolean nickDisguise, FpsDebugMode debugMode,
                                             long requestGeneration) throws Exception {
        LocalIdentity localIdentity = captureLocalIdentity();
        LiveTextureReferences liveTextureReferences = captureLiveTextureReferencesOnClientThread(localIdentity);
        TargetHypixelData targetHypixelData = resolveTargetHypixelData(resolvedTarget, hypixelDataMode);
        RankData localRankData = fetchBestEffortRankData(localIdentity.uuid.toString().replace("-", ""));
        RankData rankData = targetHypixelData.rankData;
        GuildTagData guildTagData = targetHypixelData.guildTagData;
        TextureData textureData = loadTexturesOnClientThread(resolvedTarget);

        synchronized (LocalDisguise.class) {
            requireCurrentEnableRequest(requestGeneration);
            // A standard disguise replaces any active full-profile copy; do it only after this
            // request has won, so stale profile work cannot undo a newer /off or disguise.
            LunarFullDisguise.deactivate();
            // Remove fields written by a previous diagnostic mask before applying this one.
            if (isEnabled() && shouldApplyLiveSync()) {
                clearLiveOverrides();
            }
            // Any replacement local disguise supersedes the previous /disguise timer/icon policy.
            DisguiseCommand.cancelTimedLocalDisguiseExpiry();
            localDisguiseLunarIconOverride = DisguiserLunarIcon.KEEP;
            invalidateLocalDisguiseLogoMeasurements();
            // When switching disguise targets without /disguise off, NetworkPlayerInfo.getLocationSkin() is
            // overridden with the *previous* disguise — capturing it would set realSkinLocation to that URL's RL.
            // Then getReplacementBoundTexture() would remap the real player's skin binds to the new disguise.
            boolean switchingDisguiseTarget = enabled;
            realName = localIdentity.name;
            realUuid = localIdentity.uuid;
            if (!switchingDisguiseTarget) {
                realSkinLocation = liveTextureReferences.skinLocation;
                realCapeLocation = liveTextureReferences.capeLocation;
                realUsernameSkinLocation = liveTextureReferences.usernameSkinLocation;
            }
            targetName = resolvedTarget.name;
            targetTabChatDisplayName = resolvedTarget.tabChatDisplayNick != null && !resolvedTarget.tabChatDisplayNick.isEmpty()
                    ? resolvedTarget.tabChatDisplayNick
                    : resolvedTarget.name;
            targetUuid = resolvedTarget.uuidUndashed;
            targetUuidDashed = resolvedTarget.uuid;
            targetProfile = buildTargetProfile(resolvedTarget);
            GomodPeerStandaloneCape.setLocalDisguiseCapeTarget(nickDisguise ? null : targetProfile);

            skinType = textureData.skinType;
            skinLocation = textureData.skinLocation;
            capeLocation = textureData.capeLocation;
            hasCustomSkin = textureData.hasCustomSkin;
            hasCustomCape = textureData.hasCustomCape;

            realRank = localRankData.rank;
            realRankPlusColor = localRankData.rankPlusColor;
            realMonthlyRankColor = localRankData.monthlyRankColor;
            realRankKnown = localRankData.known;
            realNetworkLevel = computeNetworkLevel(localRankData.networkExp);
            realNetworkProgress = computeNetworkProgress(localRankData.networkExp);
            realNetworkExperienceTotal = toSafeExperienceTotal(localRankData.networkExp);
            realNetworkLevelKnown = localRankData.networkLevelKnown;
            rank = rankData.rank;
            rankPlusColor = rankData.rankPlusColor;
            monthlyRankColor = rankData.monthlyRankColor;
            targetApiRank = rankData.rank;
            targetApiRankPlusColor = rankData.rankPlusColor;
            targetApiMonthlyRankColor = rankData.monthlyRankColor;
            rankKnown = rankData.known;
            targetChatKillCounterEnabled = rankData.blitzStats.chatKillsEnabled;
            targetAlternativeKillMessageEnabled = rankData.blitzStats.alternativeKillMessageEnabled;
            targetGuildTag = guildTagData.tag;
            targetGuildTagColor = guildTagData.colorFormatting;
            targetGuildRank = rankData.guildRank;
            targetNetworkLevel = computeNetworkLevel(rankData.networkExp);
            targetNetworkProgress = computeNetworkProgress(rankData.networkExp);
            targetNetworkExperienceTotal = toSafeExperienceTotal(rankData.networkExp);
            targetNetworkLevelKnown = rankData.networkLevelKnown;
            targetAchievementPoints = rankData.achievementPoints;
            targetAchievementPointsKnown = rankData.achievementPointsKnown;
            lastAppliedNetworkLevel = targetNetworkLevel;
            lastAppliedNetworkProgress = targetNetworkProgress;
            lastAppliedNetworkKnown = targetNetworkLevelKnown;
            targetBlitzStats = rankData.blitzStats;
            fastTargetBlitzKills = rankData.blitzStats != null && rankData.blitzStats.kills != null
                    ? rankData.blitzStats.kills : -1;
            targetKitInfoByName = rankData.blitzStats.kitInfoByName == null
                    ? Collections.emptyMap()
                    : new LinkedHashMap<>(rankData.blitzStats.kitInfoByName);
            applyHypixelRankPresetOverride();
            matchKillOffset = 0;
            lastCountedKillMessage = null;
            lastCountedKillMessageMs = 0L;
            frozenKillMessageCache.clear();
            clearRenderCachesLocked();
            capeOptions = mergeCapeOptionsIntoSessionCacheLocked(resolvedTarget.uuid,
                    textureData.capeOptions == null ? Collections.<CapeOption>emptyList() : textureData.capeOptions);
            invalidateDisguiseCapeGuiOptionsCacheLocked();
            selectedCapeIndex = capeOptions.isEmpty() ? -1 : 0;
            localDisguiseCapeSelectionExplicit = false;
            applySelectedCapeOptionLocked();
            xpOverrideGraceUntilMs = System.currentTimeMillis() + 3000L;
        lastSidebarObjective = null;
        lastSidebarObjectiveCheckMs = 0L;
        lastSidebarObjectiveMatches = false;
        lastLeaderboardTargetSeenMs = 0L;
        sidebarScoreColumnSuppressByEntry.clear();
            sidebarLastRenderedScoreRowKey.remove();
            fpsDebugMode = debugMode == null ? FpsDebugMode.FULL : debugMode;
            nickDisguiseActive = nickDisguise;
            enabled = true;
            fastEnabledFlag = true;
        fastRealUuid = realUuid;
        fastRealNameText = realName;
            fastTargetNameText = targetName;
            fastSidebarRenderActive = false;
            refreshFpsDebugFastFlagsLocked();
            clearLiveTabDisplayCache();
        }
        scheduleLiveOverrideApply();
        scheduleExternalCapeRefresh(resolvedTarget);
        if (!nickDisguise) {
            DisguiserRelay.refreshTargetCape(getTargetProfile());
            ConfigManager.saveDisguiseReconnectSnapshotPlayer(targetName);
        }

        return new EnableResult(targetName, targetUuid, hasCustomSkin, hasCustomCape, rankKnown,
                targetChatKillCounterEnabled, targetAlternativeKillMessageEnabled);
    }

    public static synchronized void disable() {
        enableRequestGeneration++;
        DisguiseCommand.cancelTimedLocalDisguiseExpiry();
        localDisguiseLunarIconOverride = DisguiserLunarIcon.KEEP;
        invalidateLocalDisguiseLogoMeasurements();
        GomodPeerDisguise.clearLocalShare();
        LunarFullDisguise.deactivate();
        enabled = false;
        nickDisguiseActive = false;
        realName = null;
        realUuid = null;
        targetName = null;
        targetTabChatDisplayName = null;
        targetUuid = null;
        targetUuidDashed = null;
        targetProfile = null;
        GomodPeerStandaloneCape.setLocalDisguiseCapeTarget(null);
        localDisguiseCapeSelectionExplicit = false;
        skinType = "default";
        skinLocation = null;
        capeLocation = null;
        hasCustomSkin = false;
        hasCustomCape = false;
        realSkinLocation = null;
        realCapeLocation = null;
        realUsernameSkinLocation = null;
        realRank = "Default";
        realRankPlusColor = "";
        realMonthlyRankColor = "";
        realRankKnown = false;
        realNetworkLevel = 1;
        realNetworkProgress = 0.0F;
        realNetworkExperienceTotal = 0;
        realNetworkLevelKnown = false;
        rank = "Default";
        rankPlusColor = "";
        monthlyRankColor = "";
        targetApiRank = "Default";
        targetApiRankPlusColor = "";
        targetApiMonthlyRankColor = "";
        rankKnown = false;
        hypixelCustomRankTagActive = false;
        hypixelCustomRankTagValue = "";
        targetChatKillCounterEnabled = false;
        targetAlternativeKillMessageEnabled = false;
        targetGuildTag = "";
        targetGuildTagColor = "";
        targetGuildRank = "";
        targetNetworkLevel = 1;
        targetNetworkProgress = 0.0F;
        targetNetworkExperienceTotal = 0;
        targetNetworkLevelKnown = false;
        targetAchievementPoints = 0;
        targetAchievementPointsKnown = false;
        lastAppliedNetworkLevel = 1;
        lastAppliedNetworkProgress = 0.0F;
        lastAppliedNetworkKnown = false;
        xpOverrideGraceUntilMs = 0L;
        matchKillOffset = 0;
        lastCountedKillMessage = null;
        lastCountedKillMessageMs = 0L;
        frozenKillMessageCache.clear();
        clearRenderCachesLocked();
        fastEnabledFlag = false;
        fastRenderTextRewriteFlag = false;
        fastTextureBindOverrideFlag = false;
        fastRealUuid = null;
        fastRealNameText = null;
        fastTargetNameText = null;
        fastTargetBlitzKills = -1;
        fastSidebarRenderActive = false;
        clearLiveTabDisplayCache();
        targetBlitzStats = BlitzStats.empty();
        targetKitInfoByName = Collections.emptyMap();
        lastSidebarObjective = null;
        lastSidebarObjectiveCheckMs = 0L;
        lastSidebarObjectiveMatches = false;
        activeSidebarRenderObjective = null;
        activeSidebarRenderDepth = 0;
        sidebarScoreColumnSuppressByEntry.clear();
        sidebarLastRenderedScoreRowKey.remove();
        lastLeaderboardTargetSeenMs = 0L;
        capeOptions = Collections.emptyList();
        invalidateDisguiseCapeGuiOptionsCacheLocked();
        selectedCapeIndex = -1;
        // Restore TAB/skin immediately. clearLiveOverrides() tolerates a follow-up tick clear
        // (liveOriginalDisplayName already null) by falling back to the profile username.
        clearLiveOverrides();
        fpsDebugMode = FpsDebugMode.FULL;
    }

    public static synchronized boolean isEnabled() {
        return enabled && targetName != null;
    }

    public static synchronized void setLocalDisguiseLunarIconOverride(DisguiserLunarIcon icon) {
        DisguiserLunarIcon selected = icon == null ? DisguiserLunarIcon.KEEP : icon;
        if (localDisguiseLunarIconOverride == selected) return;
        localDisguiseLunarIconOverride = selected;
        invalidateLocalDisguiseLogoMeasurements();
    }

    public static DisguiserLunarIcon getLocalDisguiseLunarIconOverride() {
        return localDisguiseLunarIconOverride;
    }

    private static void invalidateLocalDisguiseLogoMeasurements() {
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                ? null : mc.getSession().getProfile().getId();
        LunarTabLogoCompatibility.invalidatePlayerLogoMeasurements(localId);
    }

    public static FpsDebugMode getFpsDebugMode() {
        return fpsDebugMode;
    }

    /** Switch an already-loaded disguise between FPS isolation masks without downloading the profile again. */
    public static boolean setFpsDebugMode(FpsDebugMode mode) {
        FpsDebugMode selected = mode == null ? FpsDebugMode.FULL : mode;
        boolean previousModeUsedLiveSync;
        synchronized (LocalDisguise.class) {
            if (!isEnabled()) {
                return false;
            }
            previousModeUsedLiveSync = shouldApplyLiveSync();
        }

        // Remove fields written by the previous mask before applying the new one.
        if (previousModeUsedLiveSync) {
            clearLiveOverrides();
        }
        synchronized (LocalDisguise.class) {
            if (!isEnabled()) {
                return false;
            }
            fpsDebugMode = selected;
            clearRenderCachesLocked();
            clearLiveTabDisplayCache();
            refreshFpsDebugFastFlagsLocked();
        }
        applyLiveOverrides();
        return true;
    }

    public static boolean shouldApplySkinOverride() {
        if (!fastEnabledFlag) return false;
        FpsDebugMode mode = fpsDebugMode;
        return mode != FpsDebugMode.NO_SKIN
                && mode != FpsDebugMode.TEXT_ONLY
                && mode != FpsDebugMode.MINIMAL;
    }

    public static boolean shouldApplyCapeOverride() {
        if (!fastEnabledFlag) return false;
        FpsDebugMode mode = fpsDebugMode;
        return mode != FpsDebugMode.NO_CAPE
                && mode != FpsDebugMode.SKIN_ONLY
                && mode != FpsDebugMode.TEXT_ONLY
                && mode != FpsDebugMode.MINIMAL;
    }

    public static boolean shouldApplyTextureBindOverride() {
        return fastTextureBindOverrideFlag;
    }

    public static boolean shouldApplyRenderTextRewrite() {
        return fastRenderTextRewriteFlag;
    }

    public static boolean shouldApplyLiveSync() {
        if (!fastEnabledFlag) return false;
        FpsDebugMode mode = fpsDebugMode;
        return mode != FpsDebugMode.NO_LIVE_SYNC
                && mode != FpsDebugMode.SKIN_ONLY
                && mode != FpsDebugMode.TEXT_ONLY
                && mode != FpsDebugMode.MINIMAL;
    }

    public static boolean shouldApplyTabDisplayOverride() {
        if (!fastEnabledFlag) return false;
        FpsDebugMode mode = fpsDebugMode;
        return mode != FpsDebugMode.SKIN_ONLY && mode != FpsDebugMode.MINIMAL;
    }

    public static boolean shouldApplyAuxiliaryOverrides() {
        if (!fastEnabledFlag) return false;
        FpsDebugMode mode = fpsDebugMode;
        return mode != FpsDebugMode.SKIN_ONLY
                && mode != FpsDebugMode.TEXT_ONLY
                && mode != FpsDebugMode.MINIMAL;
    }

    private static void refreshFpsDebugFastFlagsLocked() {
        fastRenderTextRewriteFlag = enabled && targetName != null
                && fpsDebugMode != FpsDebugMode.NO_RENDER_TEXT
                && fpsDebugMode != FpsDebugMode.SKIN_ONLY
                && fpsDebugMode != FpsDebugMode.MINIMAL;
        fastTextureBindOverrideFlag = enabled && targetName != null
                && fpsDebugMode != FpsDebugMode.NO_SKIN
                && fpsDebugMode != FpsDebugMode.NO_TEXTURE_BIND
                && fpsDebugMode != FpsDebugMode.SKIN_ONLY
                && fpsDebugMode != FpsDebugMode.TEXT_ONLY
                && fpsDebugMode != FpsDebugMode.MINIMAL;
    }

    public static synchronized String getRealName() {
        return realName;
    }

    public static synchronized UUID getRealUuid() {
        return realUuid;
    }

    public static synchronized String getTargetName() {
        return targetName;
    }

    public static synchronized String getTargetUsernameColorForRendering() {
        if (!enabled) {
            return EnumChatFormatting.GRAY.toString();
        }
        return getUsernameColor(rank, monthlyRankColor);
    }

    /** Plain alphanumeric nick for offline UUID / Mojang checks; strips {@code &} / § format codes. */
    public static String stripToPlainNickForOfflineUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 16; i++) {
            char c = raw.charAt(i);
            if (c == '&' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                if ("0123456789abcdefklmnor".indexOf(Character.toLowerCase(n)) >= 0) {
                    i++;
                    continue;
                }
            }
            if (c == '\u00a7' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                if ("0123456789abcdefklmnor".indexOf(Character.toLowerCase(n)) >= 0) {
                    i++;
                    continue;
                }
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String getTargetTabChatDisplayNameForRendering() {
        if (targetTabChatDisplayName != null && !targetTabChatDisplayName.isEmpty()) {
            return targetTabChatDisplayName;
        }
        return targetName != null ? targetName : "";
    }

    /** Plain nick as shown in TAB/chat (preserves user casing); for kill/death alias lookup. */
    public static synchronized String getTargetTabChatDisplayNamePlain() {
        String disp = getTargetTabChatDisplayNameForRendering();
        String plain = stripFormattingCodes(disp);
        return plain == null ? "" : plain.trim();
    }

    /** Target row for the replay Teleport-to-Player selector, which should retain the target's rank/guild colors. */
    public static synchronized String getReplaySelectorTargetDisplayName() {
        if (!shouldApplyTabDisplayOverride()) return null;
        if (nickDisguiseActive) {
            String nickDisplay = getTargetTabChatDisplayNameForRendering();
            return nickDisplay == null || nickDisplay.trim().isEmpty() ? targetName : nickDisplay;
        }
        return buildRankedName(targetName) + buildGuildTagSuffix();
    }

    private static boolean isLocalDisguisePlayerEliminated(Minecraft mc) {
        // The target can be dead while the local player is alive (especially just after
        // DisguiseKiller copies the player we killed). Only the source identity determines
        // whether our own TAB/nametag should use spectator styling.
        if (realName != null && !realName.isEmpty() && KillCounter.isPlayerDead(realName)) {
            return true;
        }
        if (KillCounter.isLocalBlitzEliminatedOrSpectatingTabSignals(mc)) {
            return true;
        }
        if (GameContext.isBlitzSpectator(mc)) {
            return true;
        }
        return mc != null && mc.thePlayer != null && mc.thePlayer.getHealth() <= 0.0F;
    }

    private static int findKillFeedByMarkerIndex(String visibleText) {
        if (visibleText == null || visibleText.isEmpty()) {
            return -1;
        }
        String lower = visibleText.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String marker : KILL_FEED_BY_MARKERS) {
            int idx = lower.indexOf(marker);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private static boolean looksLikeKillFeedLineFast(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String plain = stripFormattingCodes(text);
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        return findKillFeedByMarkerIndex(plain) >= 0;
    }

    private static String findKillFeedByMarkerInVisible(String visibleText) {
        if (visibleText == null || visibleText.isEmpty()) {
            return null;
        }
        String lower = visibleText.toLowerCase(Locale.ROOT);
        String bestMarker = null;
        int bestIdx = Integer.MAX_VALUE;
        for (String marker : KILL_FEED_BY_MARKERS) {
            int idx = lower.indexOf(marker);
            if (idx >= 0 && idx < bestIdx) {
                bestIdx = idx;
                bestMarker = visibleText.substring(idx, idx + marker.length());
            }
        }
        return bestMarker;
    }

    /**
     * Single-line status for Disguise menu: plain nick + colored rank bracket when visible.
     */
    public static synchronized String getActiveDisguiseGuiStatusLine() {
        if (!enabled || targetName == null) {
            return EnumChatFormatting.GRAY + "Active disguise: " + EnumChatFormatting.RED + "None";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GRAY).append("Active disguise: ");
        String killCounterPrefix = buildGuiKillCounterPrefix();
        if (!killCounterPrefix.isEmpty()) {
            sb.append(killCounterPrefix);
        }
        if (getHypixelPrefixMode() != HypixelPrefixMode.HIDE) {
            sb.append(buildRankedName(getTargetName()));
        } else {
            sb.append(EnumChatFormatting.YELLOW).append(getTargetName());
        }
        return sb.toString();
    }

    /** Kill-counter preview for the Disguise GUI header; unlike chat/TAB this does not require an active game context. */
    private static String buildGuiKillCounterPrefix() {
        boolean enabledForTarget;
        switch (getKillCounterMode()) {
            case FORCE_ON:
                enabledForTarget = true;
                break;
            case FORCE_OFF:
                enabledForTarget = false;
                break;
            default:
                enabledForTarget = targetChatKillCounterEnabled;
                break;
        }
        if (!enabledForTarget) {
            return "";
        }
        Integer displayedKillCount = getChatKillCount();
        return displayedKillCount == null
                ? ""
                : formatBsg2KillBracketForDisplay(displayedKillCount) + " ";
    }

    /**
     * Hypixel-style "{@code [RANK] coloredName}" for client-only chat lines (e.g. {@code /pee}), matching TAB/chat
     * prefix rules while {@link #isEnabled() disguise is active} (name is the target / tab display nick).
     */
    public static synchronized String formatCosmeticChatRankAndDisplayName() {
        if (!isEnabled()) {
            return null;
        }
        String disp = getTargetTabChatDisplayNameForRendering();
        if (disp == null) {
            disp = "";
        }
        disp = disp.trim();
        if (disp.isEmpty()) {
            return null;
        }
        // Hypixel owns the rank/color presentation. On every other server the server's own
        // chat/team formatting must remain authoritative; only the disguised name is supplied.
        if (!isOnHypixelServer(Minecraft.getMinecraft())) {
            String plain = stripFormattingCodes(disp);
            return plain == null || plain.trim().isEmpty() ? null : plain.trim();
        }
        if (getHypixelPrefixMode() == HypixelPrefixMode.HIDE) {
            if (disp.indexOf('\u00a7') >= 0) {
                return disp + EnumChatFormatting.RESET;
            }
            return getUsernameColor(rank, monthlyRankColor) + disp + EnumChatFormatting.RESET;
        }
        return buildRankedName(disp);
    }

    /**
     * Nick setup GUI: clearer than raw {@link #getHypixelPrefixSettingsButtonSummary()} alone.
     */
    public static synchronized String getNickSetupPrefixStatusLine() {
        return getNickSetupPrefixStatusLine(null);
    }

    /** @param customRankDraft live text from a custom-rank field, or {@code null} to use saved config */
    public static synchronized String getNickSetupPrefixStatusLine(String customRankDraft) {
        return EnumChatFormatting.DARK_GRAY + "Prefix: " + EnumChatFormatting.RESET
                + getHypixelPrefixSettingsButtonSummary(customRankDraft);
    }

    /**
     * WL/wins suffix for TAB (same style as /tabstats), using the disguise target's Blitz stats.
     */
    public static synchronized String getFormattedTabStatsSuffixForTarget() {
        if (!isEnabled() || !isOnHypixelServer(Minecraft.getMinecraft())
                || targetBlitzStats == null || targetBlitzStats.isEmpty()) {
            return "";
        }
        return targetBlitzStats.formatTabStatsSuffix();
    }

    /** Plain guild tag letters for TAB (disguise target), without brackets. */
    public static synchronized String getTargetGuildTagForTab() {
        if (!isEnabled() || !isOnHypixelServer(Minecraft.getMinecraft())
                || targetGuildTag == null || targetGuildTag.isEmpty()) {
            return "";
        }
        return targetGuildTag;
    }

    /** §-prefixed color codes for the disguise target's guild tag in TAB. */
    public static synchronized String getTargetGuildTagColorForTab() {
        if (!isEnabled() || !isOnHypixelServer(Minecraft.getMinecraft())) {
            return "";
        }
        return targetGuildTagColor == null ? "" : targetGuildTagColor;
    }

    public static synchronized String getTargetUuid() {
        return targetUuid;
    }

    public static synchronized GameProfile getTargetProfile() {
        return targetProfile;
    }

    public static synchronized ResourceLocation getSkinLocation() {
        return skinLocation;
    }

    public static synchronized ResourceLocation getCapeLocation() {
        if (capeOptions != null && selectedCapeIndex >= 0 && selectedCapeIndex < capeOptions.size()) {
            CapeOption selected = capeOptions.get(selectedCapeIndex);
            if (selected != null && PlayerCustomCapes.isCustomOptionKey(selected.key)) {
                ResourceLocation ready = PlayerCustomCapes.getTextureForOptionKey(selected.key);
                if (ready != null) {
                    capeLocation = ready;
                }
            }
        }
        if (enabled && !nickDisguiseActive && targetProfile != null) {
            return GomodPeerStandaloneCape.capeForLocalDisguiseTarget(
                    targetProfile, capeLocation, localDisguiseCapeSelectionExplicit);
        }
        return capeLocation;
    }

    /** Current local picker cape before automatic target Gomod-wear resolution; used for diagnostic output. */
    public static synchronized ResourceLocation getFallbackCapeLocationForDebug() {
        return capeLocation;
    }

    public static synchronized boolean isNoCapeSelected() {
        if (enabled && !nickDisguiseActive && !localDisguiseCapeSelectionExplicit
                && GomodPeerStandaloneCape.isNoCapeForLocalDisguiseTarget(targetProfile, false)) {
            return true;
        }
        if (!isEnabled() || capeOptions == null || capeOptions.isEmpty()
                || selectedCapeIndex < 0 || selectedCapeIndex >= capeOptions.size()) {
            return false;
        }
        CapeOption option = capeOptions.get(selectedCapeIndex);
        return option != null && NO_CAPE_OPTION_KEY.equals(option.key);
    }

    public static synchronized boolean isLocalDisguiseCapeSelectionExplicit() {
        return localDisguiseCapeSelectionExplicit;
    }

    /** Relay wear can arrive after /disguise; refresh NetworkPlayerInfo's backing cape at that point. */
    public static synchronized void onDisguiseTargetCapeWearChanged(String name, String uuid) {
        if (!enabled || nickDisguiseActive || localDisguiseCapeSelectionExplicit || targetProfile == null) return;
        String targetUuid = targetProfile.getId() == null ? "" : targetProfile.getId().toString().replace("-", "");
        String incomingUuid = uuid == null ? "" : uuid.replace("-", "");
        boolean matchesUuid = !targetUuid.isEmpty() && targetUuid.equalsIgnoreCase(incomingUuid);
        boolean matchesName = name != null && targetProfile.getName() != null
                && targetProfile.getName().equalsIgnoreCase(name);
        if (matchesUuid || matchesName) {
            scheduleLiveOverrideApply();
        }
    }

    /** Atomic selection snapshot for shared disguises, without rebuilding the picker catalog. */
    public static synchronized CapeOptionInfo getSelectedCapeForPeerSharing() {
        if (enabled && !nickDisguiseActive && targetProfile != null
                && !localDisguiseCapeSelectionExplicit) {
            CapeOptionInfo targetWear = GomodPeerStandaloneCape.localDisguiseTargetCapeForPeerSharing(
                    targetProfile, false);
            if (targetWear != null) return targetWear;
        }
        if (!shouldApplyCapeOverride() || capeOptions == null
                || selectedCapeIndex < 0 || selectedCapeIndex >= capeOptions.size()) {
            return null;
        }
        CapeOption option = capeOptions.get(selectedCapeIndex);
        if (option == null) {
            return null;
        }
        return CapeOptionInfo.createEntry(option.label, getCapeLocation(), option.key,
                PlayerCustomCapes.isCustomOptionKey(option.key), false, true, true, "", option.imageUrl);
    }

    public static RemoteDisguiseProfile resolveRemoteProfile(String playerName) throws Exception {
        ResolvedTarget target = resolveTarget(playerName);
        return buildRemoteDisguiseProfile(target);
    }

    /** Resolve an already-loaded player's profile without asking Mojang to resolve the name again. */
    public static RemoteDisguiseProfile resolveRemoteProfile(String playerName, GameProfile knownProfile) throws Exception {
        ResolvedTarget target = null;
        if (knownProfile != null && knownProfile.getId() != null && knownProfile.getName() != null
                && playerName != null && playerName.equalsIgnoreCase(knownProfile.getName())) {
            target = resolveTarget(knownProfile);
        }
        if (target == null) {
            target = resolveTarget(playerName);
        }
        return buildRemoteDisguiseProfile(target);
    }

    private static RemoteDisguiseProfile buildRemoteDisguiseProfile(ResolvedTarget target) throws Exception {
        TextureData textureData = loadTexturesOnClientThread(target);
        GameProfile profile = buildTargetProfile(target);
        RankData rankData = fetchBestEffortRankData(target.uuidUndashed);
        GuildTagData guildTagData = fetchBestEffortGuildTagData(target.uuidUndashed);
        int networkLevel = rankData.networkLevelKnown ? computeNetworkLevel(rankData.networkExp) : -1;
        float networkProgress = rankData.networkLevelKnown ? computeNetworkProgress(rankData.networkExp) : 0.0F;
        int networkExperienceTotal = rankData.networkLevelKnown ? toSafeExperienceTotal(rankData.networkExp) : 0;
        String hypixelDisplayName = buildRemoteHypixelDisplayName(target.name, rankData, guildTagData);
        String hypixelChatName = buildRemoteHypixelChatName(target.name, rankData, false);
        String hypixelChatDisplayName = buildRemoteHypixelChatName(target.name, rankData, true);
        return new RemoteDisguiseProfile(target.name, target.uuid, profile,
                textureData.skinLocation, textureData.capeLocation, textureData.skinType,
                textureData.hasCustomSkin, textureData.hasCustomCape, networkLevel, networkProgress,
                networkExperienceTotal, guildTagData.tag, guildTagData.colorFormatting, hypixelDisplayName,
                hypixelChatName, hypixelChatDisplayName, rankData.blitzStats);
    }

    /** Publish the already-resolved identity and effective GUI settings instead of asking each viewer to refetch them. */
    public static synchronized JsonObject getSharedDisguiseProfileSnapshot() {
        if (!enabled || targetProfile == null || targetProfile.getId() == null) return null;
        BlitzStats source = targetBlitzStats == null ? BlitzStats.empty() : targetBlitzStats;
        boolean custom = shouldUseConfiguredCustomKillCount();
        boolean chatCounter = getKillCounterMode() == TournamentKillCounterMode.FORCE_ON
                || (getKillCounterMode() != TournamentKillCounterMode.FORCE_OFF
                && (custom || targetChatKillCounterEnabled));
        boolean tournamentCounter = getTournamentKillCounterMode() == TournamentKillCounterMode.FORCE_ON
                || (getTournamentKillCounterMode() != TournamentKillCounterMode.FORCE_OFF
                && (custom || targetAlternativeKillMessageEnabled));
        BlitzStats effective = new BlitzStats(getChatKillCount(), source.soloWins, source.teamWins,
                source.coins, source.deaths, chatCounter, tournamentCounter,
                source.kitInfoByName, source.renamedItemsByKey);
        String chatName = getUsernameColor(rank, monthlyRankColor) + targetName + EnumChatFormatting.RESET;
        String rankedName = buildRankedName(targetName);
        RemoteDisguiseProfile shared = new RemoteDisguiseProfile(targetName, targetProfile.getId(), targetProfile,
                skinLocation, capeLocation, skinType, hasCustomSkin, hasCustomCape,
                targetNetworkLevelKnown ? targetNetworkLevel : -1, targetNetworkProgress,
                targetNetworkExperienceTotal, targetGuildTag, targetGuildTagColor,
                rankedName + buildGuildTagSuffix(), chatName, rankedName, effective);
        return shared.toRelaySnapshot();
    }

    private static GuildTagData fetchBestEffortGuildTagData(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return GuildTagData.empty();
        }
        CachedHypixelDisguiseData cached = getCachedHypixelDisguiseData(uuid);
        if (cached != null && cached.guildTagData != null) {
            return cached.guildTagData;
        }
        HypixelFetchResult<GuildTagData> result = fetchGuildTagDataDetailed(uuid);
        if (result.status == HypixelFetchStatus.SUCCESS && result.data != null) {
            cacheHypixelDisguiseData(uuid, null, result.data);
            return result.data;
        }
        cached = getCachedHypixelDisguiseData(uuid);
        return cached != null && cached.guildTagData != null ? cached.guildTagData : GuildTagData.empty();
    }

    private static String buildRemoteHypixelDisplayName(String name, RankData rankData, GuildTagData guildTagData) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String result = null;
        if (rankData != null && rankData.known) {
            String tag = buildRankTagForHypixelPreview(rankData.rank, rankData.rankPlusColor,
                    rankData.monthlyRankColor);
            result = (tag.isEmpty() ? "" : tag + " ")
                    + getUsernameColor(rankData.rank, rankData.monthlyRankColor)
                    + name + EnumChatFormatting.RESET;
        }
        if (guildTagData != null && guildTagData.tag != null && !guildTagData.tag.isEmpty()) {
            String color = guildTagData.colorFormatting == null || guildTagData.colorFormatting.isEmpty()
                    ? EnumChatFormatting.WHITE.toString() : guildTagData.colorFormatting;
            if (result == null) {
                result = name;
            }
            result += " " + EnumChatFormatting.RESET + color + "[" + guildTagData.tag + "]"
                    + EnumChatFormatting.RESET;
        }
        return result;
    }

    /** Target's own rank-colored chat identity, without a guild suffix. */
    private static String buildRemoteHypixelChatName(String name, RankData rankData, boolean includeRankPrefix) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        boolean knownRank = rankData != null && rankData.known;
        String usernameColor = knownRank
                ? getUsernameColor(rankData.rank, rankData.monthlyRankColor)
                : EnumChatFormatting.GRAY.toString();
        String rankTag = knownRank
                ? buildRankTagForHypixelPreview(rankData.rank, rankData.rankPlusColor, rankData.monthlyRankColor)
                : "";
        return (includeRankPrefix && rankTag != null && !rankTag.isEmpty() ? rankTag + " " : "")
                + usernameColor + name + EnumChatFormatting.RESET;
    }

    /** Best-effort level metadata for /disguiser targets; this runs on the existing background profile resolver. */
    private static int resolveRemoteHypixelNetworkLevel(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return -1;
        CachedHypixelDisguiseData cached = getCachedHypixelDisguiseData(uuid);
        if (cached != null && cached.rankData != null && cached.rankData.networkLevelKnown) {
            return computeNetworkLevel(cached.rankData.networkExp);
        }
        if (!hasConfiguredHypixelApiKey()) return -1;
        RankData rankData = fetchBestEffortRankData(uuid);
        return rankData != null && rankData.networkLevelKnown ? computeNetworkLevel(rankData.networkExp) : -1;
    }

    public static final class RemoteDisguiseProfile {
        public final String name;
        public final UUID uuid;
        public final GameProfile profile;
        public final ResourceLocation skinLocation;
        public final ResourceLocation capeLocation;
        public final String skinType;
        public final boolean hasCustomSkin;
        public final boolean hasCustomCape;
        public final int networkLevel;
        public final boolean networkLevelKnown;
        public final float networkProgress;
        public final int networkExperienceTotal;
        public final String guildTag;
        public final String guildTagColor;
        public final String hypixelDisplayName;
        public final String hypixelChatName;
        public final String hypixelChatDisplayName;
        private final BlitzStats blitzStats;

        private RemoteDisguiseProfile(String name, UUID uuid, GameProfile profile,
                                      ResourceLocation skinLocation, ResourceLocation capeLocation, String skinType,
                                      boolean hasCustomSkin, boolean hasCustomCape, int networkLevel) {
            this(name, uuid, profile, skinLocation, capeLocation, skinType, hasCustomSkin,
                    hasCustomCape, networkLevel, 0.0F, 0, "", "", null,
                    buildRemoteHypixelChatName(name, RankData.unresolved(), false),
                    buildRemoteHypixelChatName(name, RankData.unresolved(), true), BlitzStats.empty());
        }

        private RemoteDisguiseProfile(String name, UUID uuid, GameProfile profile,
                                      ResourceLocation skinLocation, ResourceLocation capeLocation, String skinType,
                                      boolean hasCustomSkin, boolean hasCustomCape, int networkLevel,
                                      float networkProgress, int networkExperienceTotal, String guildTag,
                                      String guildTagColor, String hypixelDisplayName, String hypixelChatName,
                                      String hypixelChatDisplayName, BlitzStats blitzStats) {
            this.name = name;
            this.uuid = uuid;
            this.profile = profile;
            this.skinLocation = skinLocation;
            this.capeLocation = capeLocation;
            this.skinType = skinType;
            this.hasCustomSkin = hasCustomSkin;
            this.hasCustomCape = hasCustomCape;
            this.networkLevel = networkLevel;
            this.networkLevelKnown = networkLevel >= 1;
            this.networkProgress = clamp01(networkProgress);
            this.networkExperienceTotal = Math.max(0, networkExperienceTotal);
            this.guildTag = guildTag == null ? "" : guildTag;
            this.guildTagColor = guildTagColor == null ? "" : guildTagColor;
            this.hypixelDisplayName = hypixelDisplayName;
            this.hypixelChatName = hypixelChatName;
            this.hypixelChatDisplayName = hypixelChatDisplayName;
            this.blitzStats = blitzStats == null ? BlitzStats.empty() : blitzStats;
        }

        public Integer getBlitzKillCount() {
            return blitzStats == null ? null : blitzStats.kills;
        }

        public boolean isBlitzChatKillCounterEnabled() {
            return blitzStats != null && blitzStats.chatKillsEnabled;
        }

        public boolean isBlitzTournamentKillCounterEnabled() {
            return blitzStats != null && blitzStats.alternativeKillMessageEnabled;
        }

        /** Public display metadata and Mojang's public texture property; no account/session credentials. */
        public JsonObject toRelaySnapshot() {
            JsonObject data = new JsonObject();
            data.addProperty("name", name);
            data.addProperty("uuid", uuid == null ? "" : uuid.toString());
            data.addProperty("displayName", hypixelDisplayName);
            data.addProperty("chatName", hypixelChatName);
            data.addProperty("chatDisplayName", hypixelChatDisplayName);
            data.addProperty("guildTag", guildTag);
            data.addProperty("guildColor", guildTagColor);
            data.addProperty("level", networkLevel);
            data.addProperty("progress", networkProgress);
            data.addProperty("experience", networkExperienceTotal);
            data.addProperty("kills", blitzStats.kills);
            data.addProperty("soloWins", blitzStats.soloWins);
            data.addProperty("teamWins", blitzStats.teamWins);
            data.addProperty("coins", blitzStats.coins);
            data.addProperty("deaths", blitzStats.deaths);
            data.addProperty("chatKillCounter", blitzStats.chatKillsEnabled);
            data.addProperty("tournamentKillCounter", blitzStats.alternativeKillMessageEnabled);
            if (profile != null && profile.getProperties() != null) {
                for (Property texture : profile.getProperties().get("textures")) {
                    if (texture.getValue() == null || texture.getValue().length() > 16384) continue;
                    data.addProperty("textures", texture.getValue());
                    if (texture.hasSignature() && texture.getSignature().length() <= 4096)
                        data.addProperty("signature", texture.getSignature());
                    break;
                }
            }
            return data;
        }

        /** Runs on the client thread; texture registration starts downloads without a Mojang/Hypixel profile lookup. */
        public static RemoteDisguiseProfile fromRelaySnapshot(JsonObject data, String expectedName, String expectedUuid) {
            try {
                if (data == null || data.toString().length() > 26000) return null;
                String name = relayString(data, "name", 16);
                String uuidText = relayString(data, "uuid", 36).replace("-", "");
                if (!name.matches("[A-Za-z0-9_]{1,16}") || !name.equalsIgnoreCase(expectedName)
                        || !uuidText.equalsIgnoreCase(expectedUuid == null ? "" : expectedUuid.replace("-", ""))
                        || !uuidText.matches("[a-fA-F0-9]{32}")) return null;
                UUID uuid = UUID.fromString(uuidText.substring(0, 8) + "-" + uuidText.substring(8, 12) + "-"
                        + uuidText.substring(12, 16) + "-" + uuidText.substring(16, 20) + "-" + uuidText.substring(20));
                GameProfile profile = new GameProfile(uuid, name);
                String value = relayString(data, "textures", 16384);
                String signature = relayString(data, "signature", 4096);
                ResourceLocation skin = DefaultPlayerSkin.getDefaultSkin(uuid), cape = null;
                String model = DefaultPlayerSkin.getSkinType(uuid);
                if (!value.isEmpty()) {
                    JsonObject root = new JsonParser().parse(new String(Base64.getDecoder().decode(value),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!uuidText.equalsIgnoreCase(relayString(root, "profileId", 36).replace("-", ""))) return null;
                    JsonObject textures = root.getAsJsonObject("textures");
                    if (textures != null && textures.has("SKIN") && textures.get("SKIN").isJsonObject()) {
                        JsonObject skinData = textures.getAsJsonObject("SKIN");
                        JsonObject meta = skinData.has("metadata") && skinData.get("metadata").isJsonObject()
                                ? skinData.getAsJsonObject("metadata") : null;
                        model = meta != null && "slim".equals(relayString(meta, "model", 8)) ? "slim" : "default";
                        String url = relayString(skinData, "url", 256);
                        if (url.matches("https?://textures\\.minecraft\\.net/texture/[a-fA-F0-9]{32,128}")) {
                            ResourceLocation loaded = loadSkinManagerTexture(url, model, MinecraftProfileTexture.Type.SKIN);
                            if (loaded != null) skin = loaded;
                        }
                    }
                    if (textures != null && textures.has("CAPE") && textures.get("CAPE").isJsonObject()) {
                        String url = relayString(textures.getAsJsonObject("CAPE"), "url", 256);
                        if (url.matches("https?://textures\\.minecraft\\.net/texture/[a-fA-F0-9]{32,128}"))
                            cape = loadSkinManagerTexture(url, null, MinecraftProfileTexture.Type.CAPE);
                    }
                    profile.getProperties().put("textures", signature.isEmpty()
                            ? new Property("textures", value) : new Property("textures", value, signature));
                }
                BlitzStats stats = new BlitzStats(relayInteger(data, "kills"), relayInteger(data, "soloWins"),
                        relayInteger(data, "teamWins"), relayInteger(data, "coins"), relayInteger(data, "deaths"),
                        relayBoolean(data, "chatKillCounter"), relayBoolean(data, "tournamentKillCounter"),
                        Collections.emptyMap(), Collections.emptyMap());
                Integer level = relayInteger(data, "level"), xp = relayInteger(data, "experience");
                float progress = data.has("progress") && data.get("progress").isJsonPrimitive()
                        && data.getAsJsonPrimitive("progress").isNumber() ? data.get("progress").getAsFloat() : 0F;
                if (!Float.isFinite(progress)) progress = 0F;
                String plain = EnumChatFormatting.GRAY + name + EnumChatFormatting.RESET;
                String chat = relayString(data, "chatName", 192), display = relayString(data, "displayName", 256);
                String ranked = relayString(data, "chatDisplayName", 192);
                return new RemoteDisguiseProfile(name, uuid, profile, skin, cape, model, true, cape != null,
                        level == null ? -1 : level, progress, xp == null ? 0 : xp,
                        relayString(data, "guildTag", 32), relayString(data, "guildColor", 16),
                        display.isEmpty() ? plain : display, chat.isEmpty() ? plain : chat,
                        ranked.isEmpty() ? plain : ranked, stats);
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        private static String relayString(JsonObject data, String key, int limit) {
            JsonElement value = data == null ? null : data.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
            String text = value.getAsString();
            return text.length() > limit || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 ? "" : text;
        }

        private static Integer relayInteger(JsonObject data, String key) {
            JsonElement value = data.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            double number = value.getAsDouble();
            return Double.isFinite(number) && number >= 0 && number <= Integer.MAX_VALUE && number == Math.floor(number)
                    ? (int) number : null;
        }

        private static boolean relayBoolean(JsonObject data, String key) {
            JsonElement value = data.get(key);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
        }

        /** Target's API-derived TAB QoL suffix for cases where no TAB scan is cached yet. */
        public String getFormattedTabStatsSuffix() {
            if (!KillCounter.isStatsDisplay() || blitzStats == null || blitzStats.isEmpty()) {
                return "";
            }
            return blitzStats.formatTabStatsSuffix();
        }
    }

    public static synchronized void applySkullDisguise(ItemStack stack) {
        if (!shouldApplyAuxiliaryOverrides() || stack == null || !(stack.getItem() instanceof ItemSkull) || targetProfile == null) {
            return;
        }

        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey("SkullOwner")) {
            return;
        }

        String ownerName = null;
        String ownerId = null;
        if (tag.hasKey("SkullOwner", 8)) {
            ownerName = tag.getString("SkullOwner");
        } else if (tag.hasKey("SkullOwner", 10)) {
            NBTTagCompound owner = tag.getCompoundTag("SkullOwner");
            ownerName = owner.getString("Name");
            ownerId = owner.getString("Id");
        }

        if (!matchesRealSkullOwner(ownerName, ownerId) || matchesTargetSkullOwner(ownerName, ownerId)) {
            return;
        }

        NBTTagCompound owner = new NBTTagCompound();
        if (targetProfile.getName() != null) {
            owner.setString("Name", targetProfile.getName());
        }
        if (targetProfile.getId() != null) {
            owner.setString("Id", targetProfile.getId().toString());
        }

        NBTTagCompound properties = new NBTTagCompound();
        NBTTagList textures = new NBTTagList();
        if (targetProfile.getProperties() != null && targetProfile.getProperties().containsKey("textures")) {
            for (Property property : targetProfile.getProperties().get("textures")) {
                if (property == null || property.getValue() == null) {
                    continue;
                }
                NBTTagCompound texture = new NBTTagCompound();
                texture.setString("Value", property.getValue());
                if (property.getSignature() != null) {
                    texture.setString("Signature", property.getSignature());
                }
                textures.appendTag(texture);
            }
        }
        if (textures.tagCount() > 0) {
            properties.setTag("textures", textures);
        }
        owner.setTag("Properties", properties);
        tag.setTag("SkullOwner", owner);
        stack.setTagCompound(tag);
    }

    private static boolean matchesRealSkullOwner(String ownerName, String ownerId) {
        if (realName != null && ownerName != null && ownerName.equalsIgnoreCase(realName)) {
            return true;
        }
        if (realUuid != null && ownerId != null && !ownerId.isEmpty()) {
            try {
                String dashed = realUuid.toString();
                return dashed.equalsIgnoreCase(ownerId)
                        || dashed.replace("-", "").equalsIgnoreCase(ownerId);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean matchesTargetSkullOwner(String ownerName, String ownerId) {
        if (targetName != null && ownerName != null && ownerName.equalsIgnoreCase(targetName)) {
            return true;
        }
        if (targetUuidDashed != null && ownerId != null && !ownerId.isEmpty()) {
            try {
                String dashed = targetUuidDashed.toString();
                return dashed.equalsIgnoreCase(ownerId)
                        || dashed.replace("-", "").equalsIgnoreCase(ownerId);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static synchronized String getSelectedCapeLabel() {
        if (!isEnabled()) {
            return "Open while disguised";
        }
        if (capeOptions == null || capeOptions.isEmpty() || selectedCapeIndex < 0 || selectedCapeIndex >= capeOptions.size()) {
            return "No cape";
        }
        return capeOptions.get(selectedCapeIndex).label;
    }

    public static synchronized int getCapeOptionCount() {
        return capeOptions == null ? 0 : capeOptions.size();
    }

    public static synchronized int getSelectedCapeIndex() {
        return selectedCapeIndex;
    }

    /** Immutable snapshot used by GUI pickers (label + resolved texture location). */
    public static final class CapeOptionInfo {
        public final String label;
        public final ResourceLocation location;
        /** Stable selection key; null for legacy catalog rows. */
        public final String optionKey;
        public final boolean customUserCape;
        public final boolean createEntry;
        /** Player/target owns this cape — rows use a greener highlight when selectable. */
        public final boolean owned;
        /** When false the row is preview-only (dimmed, not apply-able). */
        public final boolean selectable;
        /** Sender name for a gomod-shared custom cape, or empty for normal capes. */
        public final String sharedFrom;
        /** Original external image URL, when this row came from OptiFine/MinecraftCapes/Mojang history. */
        public final String imageUrl;

        private CapeOptionInfo(String label, ResourceLocation location, String optionKey,
                               boolean customUserCape, boolean createEntry, boolean owned, boolean selectable,
                               String sharedFrom, String imageUrl) {
            this.label = label;
            this.location = location;
            this.optionKey = optionKey;
            this.customUserCape = customUserCape;
            this.createEntry = createEntry;
            this.owned = owned;
            this.selectable = selectable;
            this.sharedFrom = sharedFrom == null ? "" : sharedFrom;
            this.imageUrl = imageUrl == null ? "" : imageUrl;
        }

        public static CapeOptionInfo of(String label, ResourceLocation location) {
            return createEntry(label, location, null, false, false, true, true);
        }

        public static CapeOptionInfo createEntry(String label, ResourceLocation location, String optionKey,
                                                 boolean customUserCape, boolean createEntry) {
            return createEntry(label, location, optionKey, customUserCape, createEntry, true, true);
        }

        public static CapeOptionInfo createEntry(String label, ResourceLocation location, String optionKey,
                                                 boolean customUserCape, boolean createEntry,
                                                 boolean owned, boolean selectable) {
            return createEntry(label, location, optionKey, customUserCape, createEntry, owned, selectable, "");
        }

        public static CapeOptionInfo createEntry(String label, ResourceLocation location, String optionKey,
                                                 boolean customUserCape, boolean createEntry,
                                                 boolean owned, boolean selectable, String sharedFrom) {
            return createEntry(label, location, optionKey, customUserCape, createEntry, owned, selectable, sharedFrom, "");
        }

        public static CapeOptionInfo createEntry(String label, ResourceLocation location, String optionKey,
                                                 boolean customUserCape, boolean createEntry,
                                                 boolean owned, boolean selectable, String sharedFrom, String imageUrl) {
            return new CapeOptionInfo(label, location, optionKey, customUserCape, createEntry, owned, selectable,
                    sharedFrom, imageUrl);
        }
    }

    private static List<CapeOptionInfo> buildExpandedDisguiseGuiOptionsLocked() {
        return buildExpandedGuiOptionsFromCapeOptionsLocked(capeOptions, true, true);
    }

    private static void invalidateDisguiseCapeGuiOptionsCacheLocked() {
        disguiseCapeGuiOptionsCache = null;
    }

    private static List<CapeOptionInfo> buildExpandedGuiOptionsFromCapeOptionsLocked(
            List<CapeOption> options, boolean includeSelfCustomRows, boolean catalogSelectable) {
        if (options == null) {
            options = Collections.emptyList();
        }
        ArrayList<CapePickerCatalog.OwnedRow> rows = new ArrayList<CapePickerCatalog.OwnedRow>(options.size() + 8);
        java.util.HashSet<String> seenKeys = new java.util.HashSet<String>();
        java.util.HashSet<String> seenMojangHashes = new java.util.HashSet<String>();
        java.util.HashSet<String> seenThirdPartyKeys = new java.util.HashSet<String>();

        for (CapeOption o : options) {
            if (o == null) {
                continue;
            }
            if (NO_CAPE_OPTION_KEY.equals(o.key)) {
                continue;
            }
            String hash = o.textureHash;
            boolean catalogPreview = CATALOG_PREVIEW_CAPE_SOURCE.equals(o.sourceType);
            boolean thirdParty = MinecraftCapeCatalog.isThirdPartyAccountCapeSourceType(o.sourceType);
            if (!thirdParty) {
                if ((hash == null || hash.isEmpty()) && o.imageUrl != null && !o.imageUrl.isEmpty()) {
                    hash = MinecraftCapeCatalog.textureHashFromUrl(o.imageUrl);
                    if (hash == null || hash.isEmpty()) {
                        hash = MinecraftCapeCatalog.lookupCachedTextureHashForImageUrl(o.imageUrl);
                    }
                }
            }
            String rowLabel;
            if (MinecraftCapeCatalog.isPreservedExternalHistoryLabel(o.label)) {
                rowLabel = o.label;
            } else {
                rowLabel = o.label;
                if (!thirdParty && hash != null && !hash.isEmpty()) {
                    String catalogLabel = MinecraftCapeCatalog.labelForTextureHash(hash);
                    if (catalogLabel != null && !catalogLabel.isEmpty()) {
                        rowLabel = catalogLabel;
                    }
                }
                if (rowLabel == null || rowLabel.isEmpty()
                        || "Cape".equalsIgnoreCase(rowLabel.trim())
                        || MinecraftCapeCatalog.isGenericMojangHistoryLabel(rowLabel)) {
                    rowLabel = CapePickerCatalog.resolveOwnedCapeDisplayLabel(
                            o.label, o.imageUrl, o.location, hash);
                }
            }
            if (thirdParty) {
                String extKey = o.sourceType.toLowerCase(Locale.ROOT) + "|"
                        + (hash != null && !hash.isEmpty() ? hash : o.imageUrl);
                if (!seenThirdPartyKeys.add(extKey)) {
                    continue;
                }
            } else if (hash != null && !hash.isEmpty()) {
                if (!seenMojangHashes.add(hash.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            if (o.key != null && !seenKeys.add(o.key)) {
                continue;
            }
            rows.add(new CapePickerCatalog.OwnedRow(
                    rowLabel, o.location, o.key, o.imageUrl, hash, false, false, true, !catalogPreview));
        }

        if (includeSelfCustomRows) {
            PlayerCustomCapes.ensureLoaded();
            for (LocalDisguise.CapeOptionInfo custom : PlayerCustomCapes.buildGuiEntries()) {
                if (custom == null || custom.optionKey == null || PlayerCustomCapes.isCreateOptionKey(custom.optionKey)) {
                    continue;
                }
                if (custom.optionKey != null && !seenKeys.add(custom.optionKey)) {
                    continue;
                }
                String customHash = MinecraftCapeCatalog.textureHashFromResourceLocation(custom.location);
                if (customHash != null && !seenMojangHashes.add(customHash.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                rows.add(new CapePickerCatalog.OwnedRow(
                        custom.label, custom.location, custom.optionKey, null, customHash, true, false, true,
                        CapePickerCatalog.shouldHighlightOwnedRow(custom.optionKey, custom.createEntry),
                        custom.sharedFrom));
            }
        }

        rows.add(new CapePickerCatalog.OwnedRow(
                "No Cape", null, NO_CAPE_OPTION_KEY, null, null, false, false, true));

        if (includeSelfCustomRows) {
            rows.add(new CapePickerCatalog.OwnedRow(
                    "+ Create custom cape", null, PlayerCustomCapes.CREATE_OPTION_KEY, null, null, false, true, true));
        }

        return CapePickerCatalog.buildExpandedGuiOptions(rows, catalogSelectable);
    }

    private static String resolveLocalAccountIgnLocked() {
        if (realName != null && !realName.trim().isEmpty()) {
            return realName.trim();
        }
        String sessionName = resolveLocalAccountSessionIgnLocked();
        return sessionName != null ? sessionName : resolveLocalSessionPlayerIgnLocked();
    }

    private static String resolveLocalAccountSessionIgnLocked() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getSession() != null) {
                String username = mc.getSession().getUsername();
                if (username != null && !username.trim().isEmpty()) {
                    return username.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static UUID resolveLocalAccountSessionUuidLocked() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getSession() != null) {
                String id = mc.getSession().getPlayerID();
                if (id != null && !id.trim().isEmpty()) {
                    return toDashedUuid(id.trim().replace("-", ""));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Current in-world player — used for self cape catalog (not disguise {@link #realName}). */
    private static String resolveLocalSessionPlayerIgnLocked() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
                String profileName = mc.thePlayer.getGameProfile().getName();
                if (profileName != null && !profileName.trim().isEmpty()) {
                    return profileName.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static UUID resolveLocalSessionPlayerUuidLocked() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
                return mc.thePlayer.getGameProfile().getId();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isLocalSelfCapeCatalogForCurrentPlayerLocked() {
        UUID current = resolveLocalAccountSessionUuidLocked();
        if (current == null) {
            current = resolveLocalSessionPlayerUuidLocked();
        }
        if (localSelfCapeCatalogUuid == null) {
            return current == null;
        }
        return current != null && localSelfCapeCatalogUuid.equals(current);
    }

    /** Clears cached self capes when the logged-in session player UUID changed (e.g. account-switch mods). */
    private static void ensureSelfCapeCatalogMatchesCurrentPlayerLocked() {
        if (localSelfCapeCatalogSchemaVersion != LOCAL_SELF_CAPE_CATALOG_SCHEMA_VERSION) {
            resetLocalSelfCapeCatalogLocked();
            localSelfCapeCatalogSchemaVersion = LOCAL_SELF_CAPE_CATALOG_SCHEMA_VERSION;
            return;
        }
        if (!localSelfCapeLoadComplete && localSelfCapeCatalogUuid == null) {
            return;
        }
        if (!isLocalSelfCapeCatalogForCurrentPlayerLocked()) {
            resetLocalSelfCapeCatalogLocked();
        }
    }

    private static void markLocalSelfCapeCatalogForSessionLocked(UUID sessionUuid) {
        localSelfCapeCatalogUuid = sessionUuid;
    }

    private static void ensureLocalSelfCapeCatalogLoadedSync() {
        synchronized (LocalDisguise.class) {
            ensureSelfCapeCatalogMatchesCurrentPlayerLocked();
            if (localSelfCapeLoadComplete && isLocalSelfCapeCatalogForCurrentPlayerLocked()) {
                return;
            }
        }

        String name;
        UUID sessionUuid;
        synchronized (LocalDisguise.class) {
            name = resolveLocalAccountSessionIgnLocked();
            if (name == null) {
                name = resolveLocalSessionPlayerIgnLocked();
            }
            sessionUuid = resolveLocalAccountSessionUuidLocked();
            if (sessionUuid == null) {
                sessionUuid = resolveLocalSessionPlayerUuidLocked();
            }
            if (name == null || sessionUuid == null) {
                localSelfCapeLoadStarted = false;
                return;
            }
            List<CapeOption> cached = getSessionCapeOptionsSnapshotLocked(sessionUuid);
            if (hasOwnedCapeOptionsLocked(cached)) {
                localSelfCapeOptions = mergeCapeOptionsForPickerLocked(localSelfCapeOptions, cached);
                localSelfCapeGuiOptionsCache = null;
                markLocalSelfCapeCatalogForSessionLocked(sessionUuid);
            }
        }

        try {
            ResolvedTarget target = resolveTarget(name);
            target.externalCapeSources = fetchExternalCapeSources(target);
            List<CapeOption> loaded = loadCapeOptionsOnClientThread(target);
            synchronized (LocalDisguise.class) {
                UUID currentSessionUuid = resolveLocalAccountSessionUuidLocked();
                if (currentSessionUuid == null) {
                    currentSessionUuid = resolveLocalSessionPlayerUuidLocked();
                }
                if (!sessionUuid.equals(currentSessionUuid)) {
                    localSelfCapeLoadStarted = false;
                    return;
                }
                List<CapeOption> merged = mergeCapeOptionsForPickerLocked(localSelfCapeOptions, loaded);
                merged = mergeCapeOptionsIntoSessionCacheLocked(sessionUuid, merged);
                if (wouldLoseOwnedSelfCapesLocked(merged)) {
                    localSelfCapeLoadComplete = true;
                    localSelfCapeLoadStarted = false;
                    markLocalSelfCapeCatalogForSessionLocked(sessionUuid);
                    return;
                }
                localSelfCapeOptions = merged;
                localSelfCapeLoadComplete = true;
                markLocalSelfCapeCatalogForSessionLocked(sessionUuid);
                localSelfCapeGuiOptionsCache = null;
            }
            scheduleLocalSelfCapeRefresh(target);
        } catch (Throwable ignored) {
            synchronized (LocalDisguise.class) {
                localSelfCapeLoadStarted = false;
            }
        }
    }

    private static int countOwnedMojangCapeRowsLocked(List<CapeOption> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (CapeOption o : options) {
            if (o == null || NO_CAPE_OPTION_KEY.equals(o.key) || o.location == null) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static int countExpandedCapeRowsLocked(List<CapeOption> options) {
        try {
            return buildExpandedGuiOptionsFromCapeOptionsLocked(options, true, true).size();
        } catch (Throwable ignored) {
            return options == null ? 0 : options.size();
        }
    }

    private static Set<String> ownedCapeIdentitySetLocked(List<CapeOption> options) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (options == null || options.isEmpty()) {
            return out;
        }
        for (CapeOption o : options) {
            if (o == null || NO_CAPE_OPTION_KEY.equals(o.key) || o.location == null) {
                continue;
            }
            if (CATALOG_PREVIEW_CAPE_SOURCE.equals(o.sourceType)) {
                continue;
            }
            String type = o.sourceType == null || o.sourceType.trim().isEmpty()
                    ? "minecraft"
                    : o.sourceType.trim().toLowerCase(Locale.ROOT);
            String id = o.textureHash;
            if (id == null || id.isEmpty()) {
                id = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(o.key);
            }
            if (id == null || id.isEmpty()) {
                id = MinecraftCapeCatalog.textureHashFromResourceLocation(o.location);
            }
            if (id == null || id.isEmpty()) {
                id = o.imageUrl;
            }
            if (id == null || id.isEmpty()) {
                id = o.key;
            }
            if (id != null && !id.isEmpty()) {
                out.add(type + "|" + id.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean wouldLoseOwnedSelfCapesLocked(List<CapeOption> replacement) {
        Set<String> current = ownedCapeIdentitySetLocked(localSelfCapeOptions);
        if (current.isEmpty()) {
            return false;
        }
        Set<String> next = ownedCapeIdentitySetLocked(replacement);
        return next.size() < current.size() || !next.containsAll(current);
    }

    private static String capeOptionMergeKey(CapeOption option) {
        if (option == null) {
            return null;
        }
        if (NO_CAPE_OPTION_KEY.equals(option.key)) {
            return NO_CAPE_OPTION_KEY;
        }
        String type = option.sourceType == null || option.sourceType.trim().isEmpty()
                ? "minecraft"
                : option.sourceType.trim().toLowerCase(Locale.ROOT);
        String id = option.textureHash;
        if (id == null || id.isEmpty()) {
            id = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(option.key);
        }
        if (id == null || id.isEmpty()) {
            id = MinecraftCapeCatalog.textureHashFromResourceLocation(option.location);
        }
        if (id == null || id.isEmpty()) {
            id = option.imageUrl;
        }
        if (id == null || id.isEmpty()) {
            id = option.key;
        }
        return id == null || id.isEmpty() ? null : type + "|" + id.toLowerCase(Locale.ROOT);
    }

    private static boolean hasOwnedCapeOptionsLocked(List<CapeOption> options) {
        return !ownedCapeIdentitySetLocked(options).isEmpty();
    }

    private static boolean sameOwnedCapeIdentitiesLocked(List<CapeOption> a, List<CapeOption> b) {
        Set<String> left = ownedCapeIdentitySetLocked(a);
        Set<String> right = ownedCapeIdentitySetLocked(b);
        return left.size() == right.size() && left.containsAll(right);
    }

    private static boolean isSessionCacheableCapeOption(CapeOption option) {
        return option != null
                && option.location != null
                && !NO_CAPE_OPTION_KEY.equals(option.key)
                && !CATALOG_PREVIEW_CAPE_SOURCE.equals(option.sourceType);
    }

    private static List<CapeOption> cacheableCapeOptionsOnlyLocked(List<CapeOption> options) {
        if (options == null || options.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<CapeOption> out = new ArrayList<CapeOption>();
        for (CapeOption option : options) {
            if (isSessionCacheableCapeOption(option)) {
                out.add(option);
            }
        }
        return out.isEmpty() ? Collections.<CapeOption>emptyList() : ensureNoCapeOption(out);
    }

    private static List<CapeOption> getSessionCapeOptionsSnapshotLocked(UUID uuid) {
        if (uuid == null) {
            return Collections.emptyList();
        }
        List<CapeOption> cached = sessionCapeOptionCacheByUuid.get(uuid);
        return cached == null || cached.isEmpty()
                ? Collections.<CapeOption>emptyList()
                : new ArrayList<CapeOption>(cached);
    }

    private static List<CapeOption> mergeCapeOptionsIntoSessionCacheLocked(UUID uuid, List<CapeOption> incoming) {
        if (uuid == null) {
            return incoming == null ? Collections.<CapeOption>emptyList() : ensureNoCapeOption(incoming);
        }
        List<CapeOption> cached = getSessionCapeOptionsSnapshotLocked(uuid);
        List<CapeOption> merged = mergeCapeOptionsForPickerLocked(cached, incoming);
        List<CapeOption> cacheable = cacheableCapeOptionsOnlyLocked(merged);
        if (hasOwnedCapeOptionsLocked(cacheable)) {
            sessionCapeOptionCacheByUuid.put(uuid, cacheable);
            return new ArrayList<CapeOption>(cacheable);
        }
        return merged;
    }

    private static List<CapeOption> mergeCapeOptionsForPickerLocked(List<CapeOption> existing, List<CapeOption> incoming) {
        LinkedHashMap<String, CapeOption> merged = new LinkedHashMap<String, CapeOption>();
        CapeOption noCape = null;
        addCapeOptionsToMergeMap(merged, existing);
        addCapeOptionsToMergeMap(merged, incoming);
        if (existing != null) {
            for (CapeOption option : existing) {
                if (option != null && NO_CAPE_OPTION_KEY.equals(option.key)) {
                    noCape = option;
                    break;
                }
            }
        }
        if (noCape == null && incoming != null) {
            for (CapeOption option : incoming) {
                if (option != null && NO_CAPE_OPTION_KEY.equals(option.key)) {
                    noCape = option;
                    break;
                }
            }
        }
        ArrayList<CapeOption> out = new ArrayList<CapeOption>(merged.values());
        Collections.sort(out, new java.util.Comparator<CapeOption>() {
            @Override
            public int compare(CapeOption a, CapeOption b) {
                int pa = capeOptionPriority(a);
                int pb = capeOptionPriority(b);
                return pa == pb ? 0 : Integer.compare(pa, pb);
            }
        });
        out.add(noCape != null ? noCape : new CapeOption(NO_CAPE_OPTION_KEY, "No Cape", null, null));
        return out;
    }

    private static void addCapeOptionsToMergeMap(LinkedHashMap<String, CapeOption> merged, List<CapeOption> options) {
        if (merged == null || options == null) {
            return;
        }
        for (CapeOption option : options) {
            if (option == null || NO_CAPE_OPTION_KEY.equals(option.key)) {
                continue;
            }
            String key = capeOptionMergeKey(option);
            if (key != null && !merged.containsKey(key)) {
                merged.put(key, option);
            }
        }
    }

    private static int capeOptionPriority(CapeOption option) {
        String type = option == null || option.sourceType == null ? "minecraft" : option.sourceType.toLowerCase(Locale.ROOT);
        if ("minecraft".equals(type) || CATALOG_PREVIEW_CAPE_SOURCE.equals(type)) {
            return 0;
        }
        if ("optifine".equals(type)) {
            return 1;
        }
        if ("minecraftcapes".equals(type)) {
            return 2;
        }
        return 3;
    }

    private static boolean hasFreshLocalSelfCapeCatalogLocked() {
        if (localSelfCapeOptions == null || localSelfCapeOptions.isEmpty()
                || hasStaleOwnedCapeLabelsLocked(localSelfCapeOptions)) {
            return false;
        }
        for (CapeOption o : localSelfCapeOptions) {
            if (o == null || NO_CAPE_OPTION_KEY.equals(o.key) || o.location == null) {
                continue;
            }
            if (o.sourceType != null && !o.sourceType.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** True when owned rows still carry generic placeholder labels that need a catalog re-resolve. */
    private static boolean hasStaleOwnedCapeLabelsLocked(List<CapeOption> options) {
        if (options == null || options.isEmpty()) {
            return false;
        }
        for (CapeOption o : options) {
            if (o == null || NO_CAPE_OPTION_KEY.equals(o.key) || o.location == null) {
                continue;
            }
            if (o.imageUrl == null || o.imageUrl.isEmpty()) {
                continue;
            }
            if (MinecraftCapeCatalog.isPreservedExternalHistoryLabel(o.label)) {
                continue;
            }
            if (o.label == null || o.label.isEmpty()
                    || "Cape".equalsIgnoreCase(o.label.trim())
                    || MinecraftCapeCatalog.isGenericMojangHistoryLabel(o.label)) {
                return true;
            }
        }
        return false;
    }

    /** Force-fetch account capes when opening the self cape picker (same APIs as /disguise). */
    public static void prepareSelfCapeCatalogForPicker() {
        UUID sessionUuid = resolveLocalAccountSessionUuidLocked();
        if (sessionUuid == null) {
            sessionUuid = resolveLocalSessionPlayerUuidLocked();
        }
        if ((resolveLocalAccountSessionIgnLocked() == null && resolveLocalSessionPlayerIgnLocked() == null)
                || sessionUuid == null) {
            return;
        }
        synchronized (LocalDisguise.class) {
            ensureSelfCapeCatalogMatchesCurrentPlayerLocked();
            List<CapeOption> cached = getSessionCapeOptionsSnapshotLocked(sessionUuid);
            if (hasOwnedCapeOptionsLocked(cached)) {
                localSelfCapeOptions = mergeCapeOptionsForPickerLocked(localSelfCapeOptions, cached);
                markLocalSelfCapeCatalogForSessionLocked(sessionUuid);
            }
            localSelfCapeGuiOptionsCache = null;
            if (!hasStaleOwnedCapeLabelsLocked(localSelfCapeOptions)
                    && hasOwnedCapeOptionsLocked(localSelfCapeOptions)
                    && isLocalSelfCapeCatalogForCurrentPlayerLocked()) {
                localSelfCapeLoadComplete = true;
                localSelfCapeLoadStarted = false;
                return;
            }
            if (localSelfCapeLoadStarted && isLocalSelfCapeCatalogForCurrentPlayerLocked()) {
                return;
            }
            localSelfCapeLoadComplete = false;
            localSelfCapeLoadStarted = false;
            markLocalSelfCapeCatalogForSessionLocked(sessionUuid);
        }
        warmLocalSelfCapeCatalog();
    }

    private static void resetLocalSelfCapeCatalogLocked() {
        localSelfCapeLoadStarted = false;
        localSelfCapeLoadComplete = false;
        localSelfCapeOptions = Collections.emptyList();
        localSelfCapeGuiOptionsCache = null;
        localSelfCapeCatalogUuid = null;
    }

    private static void reloadSelfCapeCatalogIfStaleLocked() {
        ensureSelfCapeCatalogMatchesCurrentPlayerLocked();
        if (!hasStaleOwnedCapeLabelsLocked(localSelfCapeOptions)
                && isLocalSelfCapeCatalogForCurrentPlayerLocked()) {
            return;
        }
        if (localSelfCapeLoadStarted && isLocalSelfCapeCatalogForCurrentPlayerLocked()) {
            return;
        }
        localSelfCapeLoadComplete = false;
        localSelfCapeLoadStarted = false;
        warmLocalSelfCapeCatalog();
    }

    private static void scheduleLocalSelfCapeRefresh(ResolvedTarget target) {
        if (target == null || target.name == null || target.uuid == null) {
            return;
        }
        final ResolvedTarget targetSnapshot = copyResolvedTargetForCapeRefresh(target);
        CompletableFuture.runAsync(() -> {
            try {
                List<ExternalCapeSource> sources = fetchExternalCapeSources(targetSnapshot);
                if (sources == null || sources.isEmpty()) {
                    return;
                }
                targetSnapshot.externalCapeSources = sources;
                List<CapeOption> loadedCapeOptions = loadCapeOptionsOnClientThread(targetSnapshot);
                if (loadedCapeOptions == null || loadedCapeOptions.isEmpty()) {
                    return;
                }
                synchronized (LocalDisguise.class) {
                    UUID sessionUuid = resolveLocalAccountSessionUuidLocked();
                    if (sessionUuid == null) {
                        sessionUuid = resolveLocalSessionPlayerUuidLocked();
                    }
                    if (sessionUuid == null || !sessionUuid.equals(targetSnapshot.uuid)) {
                        return;
                    }
                    List<CapeOption> merged = mergeCapeOptionsForPickerLocked(localSelfCapeOptions, loadedCapeOptions);
                    merged = mergeCapeOptionsIntoSessionCacheLocked(sessionUuid, merged);
                    if (wouldLoseOwnedSelfCapesLocked(merged)) {
                        return;
                    }
                    localSelfCapeOptions = merged;
                    localSelfCapeLoadComplete = true;
                    markLocalSelfCapeCatalogForSessionLocked(sessionUuid);
                    localSelfCapeGuiOptionsCache = null;
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public static void warmLocalSelfCapeCatalog() {
        synchronized (LocalDisguise.class) {
            ensureSelfCapeCatalogMatchesCurrentPlayerLocked();
            if (localSelfCapeLoadComplete && isLocalSelfCapeCatalogForCurrentPlayerLocked()
                    && hasFreshLocalSelfCapeCatalogLocked()) {
                return;
            }
            if (localSelfCapeLoadStarted && isLocalSelfCapeCatalogForCurrentPlayerLocked()) {
                return;
            }
            localSelfCapeLoadStarted = true;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                MinecraftCapeCatalog.prewarmOfficialNormalizedCache();
                ensureLocalSelfCapeCatalogLoadedSync();
                synchronized (LocalDisguise.class) {
                    if (!localSelfCapeLoadComplete) {
                        localSelfCapeLoadStarted = false;
                    }
                }
            } catch (Throwable ignored) {
                synchronized (LocalDisguise.class) {
                    localSelfCapeLoadStarted = false;
                }
            }
        });
    }

    public static synchronized void invalidateLocalSelfCapeCatalog() {
        // Custom capes are included in both the standalone and disguise pickers.
        // Drop both GUI snapshots so create/edit/delete changes are immediately visible in either mode.
        invalidateDisguiseCapeGuiOptionsCacheLocked();
        UUID sessionUuid = resolveLocalAccountSessionUuidLocked();
        if (sessionUuid == null) {
            sessionUuid = resolveLocalSessionPlayerUuidLocked();
        }
        List<CapeOption> cached = getSessionCapeOptionsSnapshotLocked(sessionUuid);
        resetLocalSelfCapeCatalogLocked();
        if (sessionUuid != null && hasOwnedCapeOptionsLocked(cached)) {
            localSelfCapeOptions = cached;
            localSelfCapeCatalogUuid = sessionUuid;
            localSelfCapeLoadComplete = false;
            localSelfCapeGuiOptionsCache = null;
        }
    }

    public static synchronized boolean isLocalSelfCapeCatalogReady() {
        return localSelfCapeLoadComplete;
    }

    public static synchronized boolean isLocalSelfCapeCatalogLoading() {
        return localSelfCapeLoadStarted && !localSelfCapeLoadComplete;
    }

    /**
     * Non-blocking snapshot for GUI rows. Network fetch runs on a background thread via
     * {@link #warmLocalSelfCapeCatalog()}; never block the client thread here.
     */
    public static synchronized List<CapeOptionInfo> getLocalSelfCapeOptionsForGui() {
        ensureSelfCapeCatalogMatchesCurrentPlayerLocked();
        if (!localSelfCapeLoadComplete) {
            warmLocalSelfCapeCatalog();
        } else {
            reloadSelfCapeCatalogIfStaleLocked();
        }
        if (localSelfCapeGuiOptionsCache != null) {
            return localSelfCapeGuiOptionsCache;
        }
        List<CapeOption> owned = localSelfCapeOptions != null
                ? localSelfCapeOptions
                : Collections.<CapeOption>emptyList();
        localSelfCapeGuiOptionsCache = buildExpandedGuiOptionsFromCapeOptionsLocked(owned, true, true);
        return localSelfCapeGuiOptionsCache;
    }

    /** Lightweight label lookup for GUI buttons — avoids rebuilding the full cape picker catalog. */
    public static synchronized String getLocalSelfCapeOptionLabelForKey(String optionKey) {
        if (optionKey == null || optionKey.isEmpty()) {
            return null;
        }
        String catalogHash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(optionKey);
        if (catalogHash != null) {
            String catalogLabel = MinecraftCapeCatalog.labelForTextureHash(catalogHash);
            if (catalogLabel != null && !catalogLabel.isEmpty()) {
                return catalogLabel;
            }
        }
        if (localSelfCapeOptions != null) {
            for (CapeOption option : localSelfCapeOptions) {
                if (option != null && optionKey.equals(option.key)) {
                    return option.label;
                }
            }
        }
        return null;
    }

    private static CapeOption findLocalSelfCapeOptionByWearKeyLocked(String optionKey) {
        if (optionKey == null || optionKey.isEmpty() || localSelfCapeOptions == null) {
            return null;
        }
        for (CapeOption option : localSelfCapeOptions) {
            if (option != null && optionKey.equals(option.key)) {
                return option;
            }
        }
        String catalogHash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(optionKey);
        if (catalogHash != null) {
            for (CapeOption option : localSelfCapeOptions) {
                if (option != null && catalogHash.equalsIgnoreCase(option.textureHash)) {
                    return option;
                }
            }
        }
        return null;
    }

    /** Texture hash from account cape history — not inferrable from {@code gomod/capes/} ResourceLocation paths. */
    public static synchronized String getLocalSelfCapeTextureHashForWearKey(String optionKey) {
        CapeOption option = findLocalSelfCapeOptionByWearKeyLocked(optionKey);
        if (option == null || option.textureHash == null || option.textureHash.isEmpty()) {
            return null;
        }
        return option.textureHash;
    }

    /** Source URL for owned external capes (OptiFine / capes.dev) — used for peer relay wear. */
    public static synchronized String getLocalSelfCapeImageUrlForWearKey(String optionKey) {
        CapeOption option = findLocalSelfCapeOptionByWearKeyLocked(optionKey);
        if (option == null || option.imageUrl == null || option.imageUrl.isEmpty()) {
            return null;
        }
        return option.imageUrl;
    }

    /** Resolves saved legacy aliases against the current account's owned cape history. */
    public static synchronized ResourceLocation getLocalSelfCapeLocationForWearKey(String optionKey) {
        CapeOption option = findLocalSelfCapeOptionByWearKeyLocked(optionKey);
        return option == null ? null : option.location;
    }

    /** Canonical owned-history key, used to repair older builds that rewrote OptiFine keys as Mojang keys. */
    public static synchronized String getLocalSelfCapeCanonicalWearKey(String optionKey) {
        CapeOption option = findLocalSelfCapeOptionByWearKeyLocked(optionKey);
        return option == null || option.key == null ? "" : option.key;
    }

    public static synchronized int getLocalSelfCapeGuiIndex() {
        List<CapeOptionInfo> gui = getLocalSelfCapeOptionsForGui();
        if (gui.isEmpty()) {
            return 0;
        }
        if (!ConfigManager.isPlayerStandaloneCapeEnabled()) {
            ResourceLocation account = PlayerStandaloneCape.getAccountCapeLocation();
            if (account != null) {
                String accountHash = MinecraftCapeCatalog.textureHashFromResourceLocation(account);
                for (int i = 0; i < gui.size(); i++) {
                    CapeOptionInfo info = gui.get(i);
                    if (info == null || !info.selectable || info.createEntry) {
                        continue;
                    }
                    if (info.location != null && account.equals(info.location)) {
                        return i;
                    }
                    if (accountHash != null) {
                        String rowHash = MinecraftCapeCatalog.textureHashFromResourceLocation(info.location);
                        if (accountHash.equals(rowHash)) {
                            return i;
                        }
                    }
                }
            }
            return 0;
        }
        String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
        String canonicalKey = getLocalSelfCapeCanonicalWearKey(key);
        for (int i = 0; i < gui.size(); i++) {
            CapeOptionInfo info = gui.get(i);
            if (info == null || !info.selectable) {
                continue;
            }
            if (key != null && key.equals(info.optionKey)) {
                return i;
            }
            if (!canonicalKey.isEmpty() && canonicalKey.equals(info.optionKey)) {
                return i;
            }
        }
        return 0;
    }

    public static synchronized void setLocalSelfCapeGuiIndex(int guiIndex) {
        List<CapeOptionInfo> gui = getLocalSelfCapeOptionsForGui();
        if (guiIndex < 0 || guiIndex >= gui.size()) {
            return;
        }
        CapeOptionInfo picked = gui.get(guiIndex);
        PlayerStandaloneCape.applyGuiSelection(picked);
    }

    /**
     * Snapshot for GUI rendering; safe to call from the client thread.
     */
    public static synchronized List<CapeOptionInfo> getCapeOptionsForGui() {
        if (isNickDisguiseActive()) {
            stripBuiltinCatalogCapesFromActiveNickOptionsLocked();
        }
        mergeSessionCapeCatalogIntoActiveDisguiseLocked();
        if (hasStaleOwnedCapeLabelsLocked(capeOptions)) {
            invalidateDisguiseCapeGuiOptionsCacheLocked();
            scheduleDisguiseCapeRefreshIfStaleLocked();
        }
        if (disguiseCapeGuiOptionsCache != null) {
            return disguiseCapeGuiOptionsCache;
        }
        disguiseCapeGuiOptionsCache = buildExpandedDisguiseGuiOptionsLocked();
        return disguiseCapeGuiOptionsCache;
    }

    private static void mergeSessionCapeCatalogIntoActiveDisguiseLocked() {
        if (!enabled || targetUuidDashed == null || capeOptions == null) {
            return;
        }
        UUID localUuid = resolveLocalAccountSessionUuidLocked();
        if (localUuid == null) {
            localUuid = realUuid;
        }
        List<CapeOption> merged = mergeCapeOptionsForPickerLocked(capeOptions,
                getSessionCapeOptionsSnapshotLocked(targetUuidDashed));
        if (localUuid != null && localUuid.equals(targetUuidDashed)) {
            ensureSelfCapeCatalogMatchesCurrentPlayerLocked();
            if (localSelfCapeOptions != null && !localSelfCapeOptions.isEmpty()) {
                merged = mergeCapeOptionsForPickerLocked(merged, localSelfCapeOptions);
            }
            if (!localSelfCapeLoadComplete) {
                warmLocalSelfCapeCatalog();
            }
        }
        if (sameOwnedCapeIdentitiesLocked(capeOptions, merged)) {
            return;
        }
        merged = mergeCapeOptionsIntoSessionCacheLocked(targetUuidDashed, merged);
        String selectedKey = getSelectedCapeKeyLocked();
        capeOptions = merged;
        invalidateDisguiseCapeGuiOptionsCacheLocked();
        selectedCapeIndex = findCapeOptionIndexByKeyLocked(selectedKey);
        if (selectedCapeIndex < 0) {
            selectedCapeIndex = capeOptions.isEmpty() ? -1 : 0;
        }
        applySelectedCapeOptionLocked();
    }

    /** Nick disguises used to preload every official cape as owned; drop those for the picker. */
    private static void stripBuiltinCatalogCapesFromActiveNickOptionsLocked() {
        if (capeOptions == null || capeOptions.isEmpty()) {
            return;
        }
        java.util.HashSet<String> officialHashes = new java.util.HashSet<String>();
        for (MinecraftCapeCatalog.Entry entry : MinecraftCapeCatalog.OFFICIAL_MOJANG_CAPES) {
            if (entry != null && entry.textureId != null) {
                officialHashes.add(entry.textureId.toLowerCase(Locale.ROOT));
            }
        }
        ArrayList<CapeOption> kept = new ArrayList<CapeOption>();
        boolean changed = false;
        for (CapeOption o : capeOptions) {
            if (o == null) {
                continue;
            }
            if (NO_CAPE_OPTION_KEY.equals(o.key)) {
                kept.add(o);
                continue;
            }
            boolean builtinCatalogPlaceholder = (o.sourceType == null || o.sourceType.trim().isEmpty())
                    && (o.imageUrl == null || o.imageUrl.isEmpty());
            if (builtinCatalogPlaceholder
                    && o.textureHash != null
                    && officialHashes.contains(o.textureHash.toLowerCase(Locale.ROOT))) {
                changed = true;
                continue;
            }
            kept.add(o);
        }
        if (!changed) {
            return;
        }
        capeOptions = ensureNoCapeOption(kept);
        invalidateDisguiseCapeGuiOptionsCacheLocked();
        if (selectedCapeIndex < 0 || selectedCapeIndex >= capeOptions.size()) {
            selectedCapeIndex = capeOptions.isEmpty() ? -1 : 0;
        }
    }

    public static synchronized int getSelectedCapeGuiIndex() {
        if (capeOptions == null || capeOptions.isEmpty() || selectedCapeIndex < 0 || selectedCapeIndex >= capeOptions.size()) {
            return 0;
        }
        CapeOption selected = capeOptions.get(selectedCapeIndex);
        if (selected == null) {
            return 0;
        }
        List<CapeOptionInfo> gui = buildExpandedDisguiseGuiOptionsLocked();
        for (int i = 0; i < gui.size(); i++) {
            CapeOptionInfo info = gui.get(i);
            if (info == null || !info.selectable) {
                continue;
            }
            if (selected.key != null && selected.key.equals(info.optionKey)) {
                return i;
            }
            String infoCatalogHash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(info.optionKey);
            if (selected.textureHash != null && infoCatalogHash != null
                    && selected.textureHash.equalsIgnoreCase(infoCatalogHash)) {
                return i;
            }
            if (selected.textureHash != null && info.location != null) {
                String rowHash = MinecraftCapeCatalog.textureHashFromResourceLocation(info.location);
                if (selected.textureHash.equals(rowHash)) {
                    return i;
                }
            }
            if (selected.location != null && info.location != null && selected.location.equals(info.location)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Direct cape selection for GUI pickers.
     * @param guiIndex 0-based option index in {@link #getCapeOptionsForGui()} order.
     */
    public static synchronized void setCapeSelectionGuiIndex(int guiIndex) {
        List<CapeOptionInfo> gui = buildExpandedDisguiseGuiOptionsLocked();
        if (guiIndex < 0 || guiIndex >= gui.size()) {
            return;
        }
        CapeOptionInfo picked = gui.get(guiIndex);
        if (picked == null || !picked.selectable) {
            return;
        }
        localDisguiseCapeSelectionExplicit = true;
        removeOtherCatalogPreviewCapeOptionsLocked(picked);
        if (picked.optionKey != null) {
            int idx = findCapeOptionIndexByKeyLocked(picked.optionKey);
            if (idx >= 0) {
                selectedCapeIndex = idx;
                applySelectedCapeOptionLocked();
                scheduleLiveOverrideApply();
                return;
            }
        }
        if (picked.location != null && capeOptions != null) {
            for (int i = 0; i < capeOptions.size(); i++) {
                CapeOption o = capeOptions.get(i);
                if (o != null && picked.location.equals(o.location)) {
                    selectedCapeIndex = i;
                    applySelectedCapeOptionLocked();
                    scheduleLiveOverrideApply();
                    return;
                }
            }
        }
        applyCatalogOnlyCapePickLocked(picked);
    }

    /**
     * Catalog-only picks are temporary entries in the target's owned-cape block. Keep only the currently picked
     * temporary entry so the previous cape returns to its normal catalog position when another cape is selected.
     */
    private static void removeOtherCatalogPreviewCapeOptionsLocked(CapeOptionInfo picked) {
        if (capeOptions == null || capeOptions.isEmpty()) {
            return;
        }
        String pickedHash = picked == null
                ? null
                : MinecraftCapeCatalog.textureHashFromCatalogOptionKey(picked.optionKey);
        if (pickedHash == null && picked != null && picked.location != null) {
            pickedHash = MinecraftCapeCatalog.textureHashFromResourceLocation(picked.location);
        }
        ArrayList<CapeOption> kept = new ArrayList<CapeOption>(capeOptions.size());
        boolean changed = false;
        for (CapeOption option : capeOptions) {
            if (option == null || !CATALOG_PREVIEW_CAPE_SOURCE.equals(option.sourceType)) {
                kept.add(option);
                continue;
            }
            boolean matches = picked != null
                    && ((picked.optionKey != null && picked.optionKey.equals(option.key))
                    || (picked.location != null && picked.location.equals(option.location))
                    || (pickedHash != null && pickedHash.equalsIgnoreCase(option.textureHash)));
            if (matches) {
                kept.add(option);
            } else {
                changed = true;
            }
        }
        if (changed) {
            capeOptions = kept;
            invalidateDisguiseCapeGuiOptionsCacheLocked();
        }
    }

    private static void applyCatalogOnlyCapePickLocked(CapeOptionInfo picked) {
        if (picked == null || picked.location == null) {
            if (picked != null && NO_CAPE_OPTION_KEY.equals(picked.optionKey)) {
                int idx = findCapeOptionIndexByKeyLocked(NO_CAPE_OPTION_KEY);
                if (idx >= 0) {
                    selectedCapeIndex = idx;
                    applySelectedCapeOptionLocked();
                    scheduleLiveOverrideApply();
                }
            }
            return;
        }
        String hash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(picked.optionKey);
        if (hash == null) {
            hash = MinecraftCapeCatalog.textureHashFromResourceLocation(picked.location);
        }
        if (hash != null && capeOptions != null) {
            for (int i = 0; i < capeOptions.size(); i++) {
                CapeOption o = capeOptions.get(i);
                if (o != null && hash.equals(o.textureHash)) {
                    selectedCapeIndex = i;
                    applySelectedCapeOptionLocked();
                    scheduleLiveOverrideApply();
                    return;
                }
            }
        }
        String key = picked.optionKey != null ? picked.optionKey : picked.location.toString();
        if (hash == null && picked.optionKey != null) {
            hash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(picked.optionKey);
        }
        String label = EnumChatFormatting.getTextWithoutFormattingCodes(picked.label);
        if (label == null || label.isEmpty()) {
            label = "Cape";
        }
        CapeOption added = new CapeOption(key, label, picked.location, hash, null, CATALOG_PREVIEW_CAPE_SOURCE);
        ArrayList<CapeOption> next = new ArrayList<CapeOption>(capeOptions == null ? Collections.<CapeOption>emptyList() : capeOptions);
        int insertAt = next.size();
        for (int i = 0; i < next.size(); i++) {
            CapeOption o = next.get(i);
            if (o != null && NO_CAPE_OPTION_KEY.equals(o.key)) {
                insertAt = i;
                break;
            }
        }
        next.add(insertAt, added);
        capeOptions = next;
        invalidateDisguiseCapeGuiOptionsCacheLocked();
        selectedCapeIndex = insertAt;
        applySelectedCapeOptionLocked();
        scheduleLiveOverrideApply();
    }

    public static synchronized boolean canCycleCapeSelection() {
        return isEnabled() && capeOptions != null && capeOptions.size() > 1;
    }

    public static synchronized void cycleCapeSelection() {
        if (!isEnabled() || capeOptions == null || capeOptions.isEmpty()) {
            return;
        }
        localDisguiseCapeSelectionExplicit = true;
        selectedCapeIndex = (selectedCapeIndex + 1) % capeOptions.size();
        applySelectedCapeOptionLocked();
        scheduleLiveOverrideApply();
    }

    public static synchronized boolean isBlitzKitSelectorOverrideEnabled() {
        return ConfigManager.isDisguiseBlitzKitSelectorEnabled();
    }

    public static synchronized void toggleBlitzKitSelectorOverride() {
        ConfigManager.setDisguiseBlitzKitSelectorEnabled(!ConfigManager.isDisguiseBlitzKitSelectorEnabled());
    }

    public static synchronized String getBlitzKitSelectorOverrideLabel() {
        return ConfigManager.isDisguiseBlitzKitSelectorEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    public static synchronized void cycleRenamedItemsMode() {
        String mode = ConfigManager.getDisguiseRenamedItemsMode();
        if ("TARGET".equals(mode)) {
            ConfigManager.setDisguiseRenamedItemsMode("RESETED");
            return;
        }
        if ("RESETED".equals(mode)) {
            ConfigManager.setDisguiseRenamedItemsMode("UNCHANGED");
            return;
        }
        ConfigManager.setDisguiseRenamedItemsMode("TARGET");
    }

    public static synchronized String getRenamedItemsModeLabel() {
        String mode = ConfigManager.getDisguiseRenamedItemsMode();
        if ("TARGET".equals(mode)) {
            return EnumChatFormatting.GREEN + "Target's";
        }
        if ("RESETED".equals(mode)) {
            return EnumChatFormatting.GOLD + "Reseted";
        }
        return EnumChatFormatting.RED + "Unchanged";
    }

    public static synchronized String getRenamedItemsModeValue() {
        return ConfigManager.getDisguiseRenamedItemsMode();
    }

    public static synchronized boolean shouldApplyRenamedItemsMode() {
        return shouldApplyAuxiliaryOverrides()
                && isOnHypixelServer(Minecraft.getMinecraft())
                && !"UNCHANGED".equals(ConfigManager.getDisguiseRenamedItemsMode());
    }

    public static synchronized Map<String, String> getTargetRenamedItemsByKey() {
        if (targetBlitzStats == null || targetBlitzStats.renamedItemsByKey == null || targetBlitzStats.renamedItemsByKey.isEmpty()) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(targetBlitzStats.renamedItemsByKey);
    }

    public static synchronized boolean isHypixelPrefixEnabled() {
        return getHypixelPrefixMode() != HypixelPrefixMode.HIDE;
    }

    public static synchronized String getHypixelPrefixModeValue() {
        return getHypixelPrefixMode().configValue;
    }

    public static synchronized void toggleHypixelPrefix() {
        ConfigManager.setDisguiseHypixelPrefixMode(getHypixelPrefixMode().next().configValue);
    }

    public static synchronized String getHypixelPrefixLabel() {
        if (isNickDisguiseActive() && getHypixelPrefixMode() == HypixelPrefixMode.SHOW) {
            return EnumChatFormatting.YELLOW + "Unavailable on nick";
        }
        switch (getHypixelPrefixMode()) {
            case HIDE:
                return EnumChatFormatting.RED + "Hidden";
            case HYPIXEL_OFFICIAL:
                return EnumChatFormatting.GOLD + "Hypixel prefixes";
            case CUSTOM:
                return EnumChatFormatting.LIGHT_PURPLE + "Custom rank";
            default:
                return EnumChatFormatting.GREEN + "Target's";
        }
    }

    /**
     * Short line for Disguise GUI button: visibility + rank preset.
     */
    public static synchronized String getHypixelPrefixSettingsButtonSummary() {
        return getHypixelPrefixSettingsButtonSummary(null);
    }

    /** @param customRankDraft live text from a custom-rank field, or {@code null} to use saved config */
    public static synchronized String getHypixelPrefixSettingsButtonSummary(String customRankDraft) {
        if (isNickDisguiseActive() && getHypixelPrefixMode() == HypixelPrefixMode.SHOW) {
            return EnumChatFormatting.YELLOW + "Target API unavailable on nick disguise";
        }
        String vis;
        switch (getHypixelPrefixMode()) {
            case HIDE:
                vis = EnumChatFormatting.RED + "Hidden";
                break;
            case HYPIXEL_OFFICIAL:
                vis = EnumChatFormatting.GOLD + "Hypixel prefixes";
                break;
            case CUSTOM:
                vis = EnumChatFormatting.LIGHT_PURPLE + "Custom rank";
                break;
            default:
                vis = EnumChatFormatting.GREEN + "Target's (API)";
                break;
        }
        if (getHypixelPrefixMode() == HypixelPrefixMode.SHOW) {
            return vis;
        }
        if (getHypixelPrefixMode() == HypixelPrefixMode.HIDE) {
            return vis;
        }
        return vis + EnumChatFormatting.DARK_GRAY + " | " + EnumChatFormatting.RESET
                + getHypixelRankPresetColoredTagFragment(customRankDraft);
    }

    /**
     * Rank snippet for GUIs: real {@code [MVP+]} / {@code [MVP++]} colors when official, not plain text.
     */
    public static String getHypixelRankPresetColoredTagFragment() {
        return getHypixelRankPresetColoredTagFragment(null);
    }

    /** @param customRankDraft live text from a custom-rank field, or {@code null} to use saved config */
    public static String getHypixelRankPresetColoredTagFragment(String customRankDraft) {
        String p = ConfigManager.getDisguiseHypixelRankPreset();
        switch (p) {
            case "TARGET":
                return EnumChatFormatting.WHITE + "API rank";
            case "NONE":
                return EnumChatFormatting.GRAY + "No rank";
            case "MVP_PLUS":
                return previewOfficialMvpPlusBracket(
                        ConfigManager.getDisguiseHypixelMvpPlusColor(),
                        ConfigManager.getDisguiseHypixelMvpPlusBracketBaseColor());
            case "MVP_PLUS_PLUS":
                return previewOfficialMvpPlusPlusBracket(
                        ConfigManager.getDisguiseHypixelMvpPpBaseColor(),
                        ConfigManager.getDisguiseHypixelMvpPpSignColor());
            case "VIP":
                return EnumChatFormatting.GREEN + "[VIP]";
            case "VIP_PLUS":
                return EnumChatFormatting.GREEN + "[VIP" + EnumChatFormatting.GOLD + "+" + EnumChatFormatting.GREEN + "]";
            case "MVP":
                return previewOfficialMvpBracket(ConfigManager.getDisguiseHypixelMvpBracketColor());
            case "YOUTUBER":
                return EnumChatFormatting.RED + "[" + EnumChatFormatting.WHITE + "YOUTUBE" + EnumChatFormatting.RED + "]";
            case "OWNER":
            case "ADMIN":
                return EnumChatFormatting.RED + "[" + p + "]";
            case "GM":
                return EnumChatFormatting.DARK_GREEN + "[GM]";
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD + "[" + p + "]";
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE + "[INNIT]";
            case "PIG_PLUS_PLUS_PLUS":
                return EnumChatFormatting.LIGHT_PURPLE + "[PIG" + EnumChatFormatting.AQUA + "+++"
                        + EnumChatFormatting.LIGHT_PURPLE + "]";
            case "CUSTOM": {
                String raw = customRankDraft != null
                        ? customRankDraft
                        : ConfigManager.getDisguiseHypixelRankCustom();
                String t = translateAmpersandToSectionSigns(raw);
                if (t == null || t.isEmpty()) {
                    return EnumChatFormatting.LIGHT_PURPLE + "Custom";
                }
                return t.length() > 28 ? t.substring(0, 28) + EnumChatFormatting.GRAY + "…" : t;
            }
            default:
                return EnumChatFormatting.WHITE + p;
        }
    }

    public static String getHypixelRankPresetButtonFragment() {
        String p = ConfigManager.getDisguiseHypixelRankPreset();
        switch (p) {
            case "TARGET":
                return EnumChatFormatting.WHITE + "API rank";
            case "NONE":
                return EnumChatFormatting.GRAY + "No rank";
            case "VIP":
                return EnumChatFormatting.GREEN + "VIP";
            case "VIP_PLUS":
                return EnumChatFormatting.GREEN + "VIP+";
            case "MVP":
                return EnumChatFormatting.AQUA + "MVP";
            case "MVP_PLUS":
                return EnumChatFormatting.AQUA + "MVP+";
            case "MVP_PLUS_PLUS":
                return EnumChatFormatting.GOLD + "MVP++";
            case "YOUTUBER":
                return EnumChatFormatting.RED + "YOUTUBE";
            case "OWNER":
                return EnumChatFormatting.RED + "OWNER";
            case "ADMIN":
                return EnumChatFormatting.RED + "ADMIN";
            case "GM":
                return EnumChatFormatting.DARK_GREEN + "GM";
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD + p;
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE + "INNIT";
            case "PIG_PLUS_PLUS_PLUS":
                return EnumChatFormatting.LIGHT_PURPLE + "PIG+++";
            case "CUSTOM":
                return EnumChatFormatting.LIGHT_PURPLE + "Custom";
            default:
                return EnumChatFormatting.WHITE + p;
        }
    }

    /**
     * Immediately re-applies rank tag preset (e.g. after changing settings in GUI while already disguised).
     */
    public static synchronized void refreshHypixelRankPresetFromConfig() {
        if (!enabled) {
            return;
        }
        applyHypixelRankPresetOverride();
        clearLiveTabDisplayCache();
        clearRenderCachesLocked();
        scheduleLiveOverrideApply();
    }

    /** Restores rank fields from the snapshot taken when disguise was enabled (no Hypixel API call). */
    public static synchronized void applyStoredTargetApiRankForPrefixMode() {
        if (!enabled || nickDisguiseActive) {
            return;
        }
        hypixelCustomRankTagActive = false;
        hypixelCustomRankTagValue = "";
        rank = targetApiRank == null ? "Default" : targetApiRank;
        rankPlusColor = targetApiRankPlusColor == null ? "" : targetApiRankPlusColor;
        monthlyRankColor = targetApiMonthlyRankColor == null ? "" : targetApiMonthlyRankColor;
        rankKnown = true;
        clearLiveTabDisplayCache();
        clearRenderCachesLocked();
        scheduleLiveOverrideApply();
    }

    /**
     * While disguised as a real player, reload the target's Hypixel rank for {@code SHOW} prefix mode.
     * Respects the same 1-minute disguise cache cooldown as {@link DisguiseCommand#enableDisguiseAsync}.
     */
    public static void refreshTargetHypixelRankForPrefixSettings(GuiScreen parent, Runnable onSuccess, Runnable onCancel) {
        if (!enabled || nickDisguiseActive) {
            runOnClientThread(onSuccess);
            return;
        }
        new Thread(() -> {
            try {
                ResolvedTarget target = buildResolvedTargetFromActiveDisguiseLocked();
                if (target == null || target.uuidUndashed == null || target.uuidUndashed.trim().isEmpty()) {
                    runOnClientThread(onSuccess);
                    return;
                }
                TargetHypixelData data = resolveTargetHypixelData(target, HypixelDisguiseDataMode.AUTO);
                applyTargetRankDataSynchronized(data.rankData);
                runOnClientThread(onSuccess);
            }
            catch (HypixelDataDecisionRequiredException e) {
                showHypixelDataDecisionDialog(parent, e.getDecision(), new HypixelDataDecisionHandler() {
                    @Override
                    public void onUseCache(HypixelDataDecision decision) {
                        refreshTargetHypixelRankForPrefixSettingsWithMode(decision, HypixelDisguiseDataMode.CACHE_ONLY, 0L, onSuccess, onCancel);
                    }

                    @Override
                    public void onWait(HypixelDataDecision decision) {
                        refreshTargetHypixelRankForPrefixSettingsWithMode(decision, HypixelDisguiseDataMode.FRESH_ONLY,
                                decision == null ? 0L : decision.waitMillis, onSuccess, onCancel);
                    }

                    @Override
                    public void onCancel() {
                        runOnClientThread(onCancel);
                    }
                });
            }
            catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to refresh target Hypixel rank: " + e.getMessage());
                runOnClientThread(onCancel);
            }
        }, "gomod-prefix-rank").start();
    }

    public static void showHypixelDataDecisionDialog(GuiScreen parentScreen, HypixelDataDecision decision) {
        showHypixelDataDecisionDialog(parentScreen, decision, null);
    }

    public static void showHypixelDataDecisionDialog(GuiScreen parentScreen, HypixelDataDecision decision,
                                                     HypixelDataDecisionHandler handler) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || decision == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Hypixel disguise data is unavailable.");
            return;
        }
        Runnable task = () -> mc.displayGuiScreen(new DisguiseHypixelDecisionDialog(parentScreen, decision, handler));
        if (mc.isCallingFromMinecraftThread()) {
            task.run();
        } else {
            mc.addScheduledTask(task);
        }
    }

    private static void refreshTargetHypixelRankForPrefixSettingsWithMode(HypixelDataDecision decision,
                                                                            HypixelDisguiseDataMode mode, long waitMillis,
                                                                            Runnable onSuccess, Runnable onCancel) {
        new Thread(() -> {
            try {
                if (waitMillis > 0L) {
                    Thread.sleep(waitMillis);
                }
                ResolvedTarget target = buildResolvedTargetFromActiveDisguiseLocked();
                if (target == null || target.uuidUndashed == null || target.uuidUndashed.trim().isEmpty()) {
                    runOnClientThread(onSuccess);
                    return;
                }
                TargetHypixelData data = resolveTargetHypixelData(target, mode);
                applyTargetRankDataSynchronized(data.rankData);
                runOnClientThread(onSuccess);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Target rank refresh cancelled.");
                runOnClientThread(onCancel);
            }
            catch (Exception e) {
                String targetLabel = decision == null || decision.targetName == null ? "target" : decision.targetName;
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to refresh Hypixel rank for "
                        + EnumChatFormatting.YELLOW + targetLabel + EnumChatFormatting.RED + ": " + e.getMessage());
                runOnClientThread(onCancel);
            }
        }, "gomod-prefix-rank-followup").start();
    }

    private static synchronized ResolvedTarget buildResolvedTargetFromActiveDisguiseLocked() {
        if (!enabled || targetName == null || targetUuid == null) {
            return null;
        }
        ResolvedTarget target = new ResolvedTarget();
        target.name = targetName;
        target.uuidUndashed = targetUuid;
        target.uuid = targetUuidDashed;
        return target;
    }

    private static synchronized void applyTargetRankDataSynchronized(RankData rankData) {
        if (!enabled || rankData == null) {
            return;
        }
        hypixelCustomRankTagActive = false;
        hypixelCustomRankTagValue = "";
        rank = rankData.rank;
        rankPlusColor = rankData.rankPlusColor;
        monthlyRankColor = rankData.monthlyRankColor;
        rankKnown = rankData.known;
        clearLiveTabDisplayCache();
        clearRenderCachesLocked();
        scheduleLiveOverrideApply();
    }

    private static void runOnClientThread(Runnable task) {
        if (task == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (mc.isCallingFromMinecraftThread()) {
            task.run();
        } else {
            mc.addScheduledTask(task);
        }
    }

    private static void applyHypixelRankPresetOverride() {
        hypixelCustomRankTagActive = false;
        hypixelCustomRankTagValue = "";
        String preset = ConfigManager.getDisguiseHypixelRankPreset();
        if ("TARGET".equals(preset)) {
            return;
        }
        if ("CUSTOM".equals(preset)) {
            hypixelCustomRankTagActive = true;
            hypixelCustomRankTagValue = translateAmpersandToSectionSigns(ConfigManager.getDisguiseHypixelRankCustom());
            return;
        }
        if ("NONE".equals(preset)) {
            rank = "Default";
            rankPlusColor = "";
            monthlyRankColor = "";
            rankKnown = true;
            return;
        }
        rankKnown = true;
        switch (preset) {
            case "VIP":
                rank = "VIP";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "VIP_PLUS":
                rank = "VIP+";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "MVP":
                rank = "MVP";
                rankPlusColor = "";
                monthlyRankColor = ConfigManager.getDisguiseHypixelMvpBracketColor();
                break;
            case "MVP_PLUS":
                rank = "MVP+";
                rankPlusColor = ConfigManager.getDisguiseHypixelMvpPlusColor();
                monthlyRankColor = ConfigManager.getDisguiseHypixelMvpPlusBracketBaseColor();
                break;
            case "MVP_PLUS_PLUS":
                rank = "MVP++";
                monthlyRankColor = ConfigManager.getDisguiseHypixelMvpPpBaseColor();
                rankPlusColor = ConfigManager.getDisguiseHypixelMvpPpSignColor();
                break;
            case "YOUTUBER":
                rank = "YOUTUBER";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "OWNER":
                rank = "OWNER";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "ADMIN":
                rank = "ADMIN";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "GM":
                rank = "GM";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "MOJANG":
                rank = "MOJANG";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "EVENTS":
                rank = "EVENTS";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "INNIT":
                rank = "INNIT";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            case "PIG_PLUS_PLUS_PLUS":
                rank = "PIG+++";
                rankPlusColor = "";
                monthlyRankColor = "";
                break;
            default:
                break;
        }
    }

    private static String translateAmpersandToSectionSigns(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '&' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                if ("0123456789abcdefklmnor".indexOf(Character.toLowerCase(n)) >= 0) {
                    sb.append('\u00A7').append(Character.toLowerCase(n));
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static HypixelPrefixMode getHypixelPrefixMode() {
        return HypixelPrefixMode.fromConfigValue(ConfigManager.getDisguiseHypixelPrefixMode());
    }

    public static synchronized boolean shouldOverrideBlitzKitSelector() {
        return shouldApplyAuxiliaryOverrides() && ConfigManager.isDisguiseBlitzKitSelectorEnabled();
    }

    public static synchronized boolean isBlitzSgSidebarActive() {
        if (!shouldApplyAuxiliaryOverrides()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return hasBlitzSgSidebar(findSidebarObjective(mc), mc);
    }

    /** Hypixel context check for shared/manual disguise chat formatting, independent of local /disguise state. */
    public static synchronized boolean isHypixelChatContextActive() {
        return isOnHypixelServer(Minecraft.getMinecraft());
    }

    /** Blitz sidebar check for shared/manual disguise counters, independent of local /disguise state. */
    public static synchronized boolean isBlitzSgChatContextActive() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && hasBlitzSgSidebar(findSidebarObjective(mc), mc);
    }

    /** Formats the remote target's rank and name using the user's existing prefix visibility setting. */
    public static synchronized String formatRemoteDisguiseChatName(RemoteDisguiseProfile profile) {
        if (profile == null || profile.name == null) return "";
        // The cached rank data is fetched from Hypixel. Do not leak its prefix/color into disguises
        // shown on another server, where the server's own name formatting should remain authoritative.
        if (!isHypixelChatContextActive()) return profile.name;
        if (getHypixelPrefixMode() == HypixelPrefixMode.HIDE) {
            return profile.hypixelChatName == null ? profile.name : profile.hypixelChatName;
        }
        return profile.hypixelChatDisplayName == null ? profile.name : profile.hypixelChatDisplayName;
    }

    /** Roulette only displays the rank-colored IGN; this deliberately omits rank and guild tags. */
    public static synchronized String formatRemoteDisguiseRouletteName(RemoteDisguiseProfile profile) {
        if (profile == null || profile.name == null || profile.name.isEmpty()) return "";
        if (!isHypixelChatContextActive()) return profile.name;
        return profile.hypixelChatName == null || profile.hypixelChatName.isEmpty()
                ? EnumChatFormatting.GRAY + profile.name + EnumChatFormatting.RESET
                : profile.hypixelChatName;
    }

    public static synchronized Map<String, DisguiseKitInfo> getTargetKitInfoByName() {
        if (targetKitInfoByName == null || targetKitInfoByName.isEmpty()) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(targetKitInfoByName);
    }

    public static String buildDisguiseRenamedItemLookupKey(String kitToken, String itemToken) {
        String normalizedKit = normalizeDisguiseRenamedItemLookupToken(kitToken);
        String normalizedItem = normalizeDisguiseRenamedItemLookupToken(itemToken);
        if (normalizedKit.isEmpty() || normalizedItem.isEmpty()) {
            return "";
        }
        return normalizedKit + "|" + normalizedItem;
    }

    public static synchronized Integer getDisguisedBlitzCoins() {
        return targetBlitzStats == null ? null : targetBlitzStats.coins;
    }

    public static synchronized String getSidebarMutationSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(enabled).append('|');
        sb.append(rankKnown).append('|');
        sb.append(targetNetworkLevelKnown).append(':').append(targetNetworkLevel).append('|');
        sb.append(targetAchievementPointsKnown).append(':').append(targetAchievementPoints).append('|');
        if (targetBlitzStats == null) {
            sb.append("blitz:null");
        } else {
            sb.append(targetBlitzStats.kills).append('|')
                    .append(targetBlitzStats.soloWins).append('|')
                    .append(targetBlitzStats.teamWins).append('|')
                    .append(targetBlitzStats.coins).append('|')
                    .append(targetBlitzStats.deaths);
        }
        return sb.toString();
    }

    public static synchronized String getTournamentKillCounterModeLabel() {
        switch (getTournamentKillCounterMode()) {
            case FORCE_ON:
                return EnumChatFormatting.GREEN + "Force ON";
            case FORCE_OFF:
                return EnumChatFormatting.RED + "Force OFF";
            default:
                return buildTargetToggleLabel(rankKnown, targetAlternativeKillMessageEnabled);
        }
    }

    public static synchronized void cycleTournamentKillCounterMode() {
        TournamentKillCounterMode next = getTournamentKillCounterMode().next();
        ConfigManager.setDisguiseTournamentKillCounterMode(next.configValue);
    }

    public static synchronized String getKillCounterModeLabel() {
        switch (getKillCounterMode()) {
            case FORCE_ON:
                return EnumChatFormatting.GREEN + "Force ON";
            case FORCE_OFF:
                return EnumChatFormatting.RED + "Force OFF";
            default:
                return buildTargetToggleLabel(rankKnown, targetChatKillCounterEnabled);
        }
    }

    public static synchronized void cycleKillCounterMode() {
        TournamentKillCounterMode next = getKillCounterMode().next();
        ConfigManager.setDisguiseKillCounterMode(next.configValue);
    }

    /** Rank-colored disguised name for GUI tooltips; aqua when rank data is unavailable. */
    public static synchronized String buildDisguiseGuiTooltipRankedName() {
        String name = getTargetName();
        if (name == null || name.trim().isEmpty()) {
            name = "PlayerName";
        } else {
            name = name.trim();
        }
        if (!isEnabled() || !rankKnown || rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return EnumChatFormatting.AQUA + name;
        }
        if (getHypixelPrefixMode() == HypixelPrefixMode.HIDE) {
            return getUsernameColor(rank, monthlyRankColor) + name + EnumChatFormatting.RESET;
        }
        return buildRankedName(name);
    }

    /**
     * Returns the active disguise's own target-rank formatting for local match-result rows, bypassing unrelated
     * server/API name caches. Those caches can contain the real account's white/rank color and must not be reused
     * after only replacing its text with the disguise alias.
     */
    public static synchronized String formatDisguiseNameForMatchResults(String sourceName) {
        if (!isEnabled() || sourceName == null || sourceName.trim().isEmpty()) {
            return null;
        }
        String localName = fastRealNameText;
        String disguiseName = fastTargetNameText;
        String source = sourceName.trim();
        if (localName == null || localName.trim().isEmpty()
                || disguiseName == null || disguiseName.trim().isEmpty()
                || (!localName.trim().equalsIgnoreCase(source)
                && !disguiseName.trim().equalsIgnoreCase(source))) {
            return null;
        }
        return buildRankedName(disguiseName.trim());
    }

    public static synchronized String getDisguiseTargetPossessiveLabel() {
        String name = getTargetName();
        if (name == null || name.trim().isEmpty()) {
            return "Target's";
        }
        return name.trim() + "'s";
    }

    public static synchronized boolean isDisguiseTargetKillPreferenceKnown() {
        return isEnabled() && rankKnown;
    }

    public static synchronized boolean isDisguiseTargetChatKillCounterEnabled() {
        return targetChatKillCounterEnabled;
    }

    public static synchronized boolean isDisguiseTargetTournamentKillCounterEnabled() {
        return targetAlternativeKillMessageEnabled;
    }

    public static synchronized String getDisguiseCustomKillFeatureToggleLabel() {
        return ConfigManager.isDisguiseCustomKillCountEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    public static synchronized void toggleDisguiseCustomKillFeature() {
        ConfigManager.setDisguiseCustomKillCountEnabled(!ConfigManager.isDisguiseCustomKillCountEnabled());
    }

    /** Value preview next to Enabled/Disabled; matches chat/tab bracket styling (full number, no 10k shorthand). */
    public static synchronized String getDisguiseCustomKillValueButtonLabel() {
        return formatBsg2KillBracketForDisplay(ConfigManager.getDisguiseCustomKillCount());
    }

    public static synchronized String getSkinType() {
        return skinType == null || skinType.isEmpty() ? "default" : skinType;
    }

    public static synchronized boolean hasCustomSkin() {
        return hasCustomSkin;
    }

    public static synchronized boolean hasCustomCape() {
        return hasCustomCape;
    }

    public static ResourceLocation getReplacementBoundTexture(ResourceLocation original) {
        // This method is injected into every TextureManager.bindTexture call. Keep it lock- and allocation-free:
        // taking the class monitor and converting every unrelated ResourceLocation to String generated enough
        // garbage to cause recurring GC freezes while /disguise was active on Weave.
        if (!fastTextureBindOverrideFlag || original == null) {
            return original;
        }

        ResourceLocation targetSkin = skinLocation;
        if (shouldApplySkinOverride() && targetSkin != null
                && (sameTexture(original, realSkinLocation) || sameTexture(original, realUsernameSkinLocation))) {
            return targetSkin;
        }

        ResourceLocation targetCape = capeLocation;
        if (shouldApplyCapeOverride() && targetCape != null && sameTexture(original, realCapeLocation)) {
            return targetCape;
        }
        return original;
    }

    public static synchronized boolean matchesLocalProfile(GameProfile profile) {
        if (!isEnabled() || profile == null) {
            return false;
        }

        UUID profileId = profile.getId();
        if (realUuid != null && profileId != null && realUuid.equals(profileId)) {
            return true;
        }

        String profileName = profile.getName();
        return realName != null && profileName != null && realName.equalsIgnoreCase(profileName);
    }

    public static boolean matchesLocalProfileFast(GameProfile profile) {
        if (!fastEnabledFlag || profile == null) {
            return false;
        }
        UUID profileId = profile.getId();
        UUID localId = fastRealUuid;
        if (localId != null && profileId != null && localId.equals(profileId)) {
            return true;
        }
        String profileName = profile.getName();
        String localName = fastRealNameText;
        return localName != null && profileName != null && localName.equalsIgnoreCase(profileName);
    }

    public static void resetMatchKillCounterProgress() {
        matchKillOffset = 0;
        lastCountedKillMessage = null;
        lastCountedKillMessageMs = 0L;
    }

    public static void handleIncomingChat(String message) {
        if (!shouldApplyAuxiliaryOverrides() || message == null || message.isEmpty()) {
            return;
        }

        /*
         * This hook receives every chat entry. Most entries cannot affect the disguise kill offset, so reject them
         * before running normalization (the old replaceAll compiled/ran a regex for every line added to chat).
         */
        String trimmed = message.trim();
        String currentRealName = fastRealNameText;
        boolean possibleReset = trimmed.indexOf("Cage") >= 0 || trimmed.indexOf("cage") >= 0
                || trimmed.indexOf("game starts") >= 0 || trimmed.indexOf("Game starts") >= 0
                || trimmed.indexOf("Blitz Star") >= 0 || trimmed.indexOf("You died") >= 0
                || trimmed.indexOf("Want to play again") >= 0 || trimmed.indexOf("Survive while eliminating") >= 0;
        boolean possibleSelfKill = currentRealName != null && !currentRealName.isEmpty()
                && trimmed.indexOf(" was killed by ") >= 0
                && indexOfIgnoreCase(trimmed, currentRealName) >= 0;
        if (!possibleReset && !possibleSelfKill) {
            return;
        }

        String normalized = collapseChatWhitespace(trimmed);
        if (normalized.isEmpty()) {
            return;
        }

        if (shouldResetMatchKillCounterForMessage(normalized)) {
            resetMatchKillCounterProgress();
            return;
        }

        if (currentRealName == null || currentRealName.isEmpty() || fastTargetBlitzKills < 0) {
            return;
        }

        String killMarker = " was killed by " + currentRealName;
        if (!normalized.contains(killMarker)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (normalized.equals(lastCountedKillMessage) && now - lastCountedKillMessageMs <= 1500L) {
            return;
        }

        matchKillOffset++;
        lastCountedKillMessage = normalized;
        lastCountedKillMessageMs = now;
    }

    /** Collapses chat whitespace without the allocation and pattern compilation cost of {@code replaceAll}. */
    private static String collapseChatWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = null;
        boolean previousWhitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean whitespace = Character.isWhitespace(c);
            if (whitespace && previousWhitespace) {
                if (out == null) {
                    out = new StringBuilder(value.length());
                    out.append(value, 0, i);
                }
                continue;
            }
            if (out != null) {
                out.append(whitespace ? ' ' : c);
            }
            previousWhitespace = whitespace;
        }
        return out == null ? value : out.toString();
    }

    public static synchronized boolean shouldOverrideHypixelLobbyXpBar() {
        if (!shouldApplyAuxiliaryOverrides() || !targetNetworkLevelKnown) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || !isOnHypixelServer(mc)) {
            return false;
        }

        // Vanilla XP reflects Hypixel network level only in lobby-style contexts — not during an active Blitz match
        // (Taunt!, kills, etc.), where it should show real in-game XP/levels.
        if (GameContext.isBlitzGameActive(mc)) {
            return false;
        }
        // Queue/pregame sidebars can contain a lobby marker, but the XP bar should keep its vanilla lobby/queue value
        // until the player is in the actual hub. Main game lobbies remain eligible.
        if (GameContext.isBlitzPregameLobby(mc)) {
            return false;
        }
        // Never override XP bar while watching a Hypixel replay — the player is spectating, not playing.
        if (GameContext.isHypixelReplayWatching(mc)) {
            return false;
        }
        // XP disguise should only mirror Hypixel network level in real hub/lobby contexts, never in Blitz pregame.
        if (!GameContext.isHypixelLobby(mc)) {
            return false;
        }

        if (System.currentTimeMillis() <= xpOverrideGraceUntilMs) {
            return true;
        }

        if (hasSidebarStatBoard(findSidebarObjective(mc))) {
            return true;
        }

        if (matchesXpBar(mc.thePlayer.experienceLevel, mc.thePlayer.experience, targetNetworkLevel, targetNetworkProgress)) {
            return true;
        }

        if (lastAppliedNetworkKnown && matchesXpBar(mc.thePlayer.experienceLevel, mc.thePlayer.experience, lastAppliedNetworkLevel, lastAppliedNetworkProgress)) {
            return true;
        }

        return realNetworkLevelKnown
                && matchesXpBar(mc.thePlayer.experienceLevel, mc.thePlayer.experience, realNetworkLevel, realNetworkProgress);
    }

    public static synchronized int getDisguisedNetworkLevel() {
        return targetNetworkLevel;
    }

    /** Network-level metadata used by Lunar's Level Head row while the local player is disguised. */
    public static synchronized UUID getDisguiseTargetUuidForNametag() {
        return enabled && !nickDisguiseActive ? targetUuidDashed : null;
    }

    /** Returns -1 when this disguise has no known Hypixel network-level data. */
    public static synchronized int getDisguisedNetworkLevelForNametag() {
        return enabled && !nickDisguiseActive && targetNetworkLevelKnown ? targetNetworkLevel : -1;
    }

    public static synchronized float getDisguisedNetworkProgress() {
        return targetNetworkProgress;
    }

    public static synchronized int getDisguisedNetworkExperienceTotal() {
        return targetNetworkExperienceTotal;
    }

    public static synchronized void noteHypixelLobbyXpBarApplied() {
        if (!targetNetworkLevelKnown) {
            return;
        }
        lastAppliedNetworkLevel = targetNetworkLevel;
        lastAppliedNetworkProgress = targetNetworkProgress;
        lastAppliedNetworkKnown = true;
    }

    public static synchronized String rewriteSidebarLine(ScoreObjective objective, String originalFormattedLine) {
        return rewriteSidebarLine(objective, originalFormattedLine, null);
    }

    /**
     * @param scoreboardEntryName {@link Score#getPlayerName()} for this row; when non-null, drives score-column hide when the stat value is inlined in the text.
     */
    public static synchronized String rewriteSidebarLine(ScoreObjective objective, String originalFormattedLine, String scoreboardEntryName) {
        if (originalFormattedLine == null || originalFormattedLine.isEmpty() || !shouldOverrideBlitzLobbySidebar(objective)) {
            return originalFormattedLine;
        }

        boolean trackScoreSuppress = scoreboardEntryName != null && !scoreboardEntryName.isEmpty();

        SidebarStatMatch match = detectSidebarStatMatch(originalFormattedLine);
        if (match == null) {
            return originalFormattedLine;
        }
        if (match.kind == SidebarStatKind.LOBBY_RANK) {
            if (!rankKnown) {
                return originalFormattedLine;
            }
            String out = replaceSidebarRankTailAfterLabel(originalFormattedLine, match);
            if (trackScoreSuppress) {
                sidebarScoreColumnSuppressByEntry.put(scoreboardEntryName, Boolean.TRUE);
            }
            return out;
        }
        Integer statValue = getSidebarStatValue(match.kind);
        if (statValue == null) {
            return originalFormattedLine;
        }

        String replaced = replaceSidebarValueInLine(originalFormattedLine, match, statValue.intValue());
        if (!replaced.equals(originalFormattedLine)) {
            if (trackScoreSuppress) {
                sidebarScoreColumnSuppressByEntry.put(scoreboardEntryName, Boolean.TRUE);
            }
            return replaced;
        }
        // Value is only in the score column — keep the text row short; {@link #rewriteSidebarScorePoints} supplies the number.
        if (trackScoreSuppress) {
            sidebarScoreColumnSuppressByEntry.put(scoreboardEntryName, Boolean.FALSE);
        }
        return originalFormattedLine;
    }

    public static synchronized int rewriteSidebarScorePoints(ScoreObjective objective, Score score, int originalPoints) {
        if (score == null || !shouldOverrideBlitzLobbySidebar(objective)) {
            return originalPoints;
        }

        String entry = score.getPlayerName();
        if (entry != null) {
            sidebarLastRenderedScoreRowKey.set(entry);
        }
        SidebarStatMatch match = detectSidebarStatMatch(buildSidebarEntryLine(objective, score));
        if (match == null) {
            return originalPoints;
        }
        if (match.kind == SidebarStatKind.LOBBY_RANK) {
            return 0;
        }
        Integer statValue = getSidebarStatValue(match.kind);
        return statValue != null ? statValue.intValue() : originalPoints;
    }

    public static synchronized boolean shouldHideSidebarScoreColumnText(ScoreObjective objective, String text) {
        if (text == null || text.isEmpty() || !shouldOverrideBlitzLobbySidebar(objective)) {
            return false;
        }
        String visible = stripFormattingCodes(text);
        if (visible == null) {
            return false;
        }
        visible = visible.trim();
        if (visible.isEmpty() || !isEntirelySidebarScoreNumeric(visible)) {
            return false;
        }
        return shouldHideSidebarScoreColumnTextForActiveBoard(objective, null);
    }

    public static synchronized boolean renderCustomBlitzLobbySidebar(ScoreObjective objective, ScaledResolution scaledResolution) {
        return renderCustomBlitzLobbySidebar(objective, scaledResolution, false);
    }

    public static synchronized boolean renderCustomBlitzLobbySidebar(ScoreObjective objective,
                                                                     ScaledResolution scaledResolution,
                                                                     boolean replaceExisting) {
        if (!shouldOverrideBlitzLobbySidebar(objective) || scaledResolution == null) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null || objective == null || objective.getScoreboard() == null) {
            return false;
        }

        Scoreboard scoreboard = objective.getScoreboard();
        List<Score> visibleScores = SidebarScoreboardLayout.visibleScoresForSidebarLayout(scoreboard, objective);
        if (visibleScores.isEmpty()) {
            return false;
        }

        FontRenderer fontRenderer = mc.fontRendererObj;
        int valueGap = fontRenderer.getStringWidth(": ");
        int width = fontRenderer.getStringWidth(objective.getDisplayName());
        List<String> renderedLines = new ArrayList<>();
        List<String> renderedScoreTexts = new ArrayList<>();

        for (Score score : visibleScores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String line = rewriteSidebarLine(objective, ScorePlayerTeam.formatPlayerName(team, score.getPlayerName()),
                    score.getPlayerName());
            if (line == null) {
                line = "";
            }
            renderedLines.add(line);
            int rewrittenScore = rewriteSidebarScorePoints(objective, score, score.getScorePoints());
            String scoreText = EnumChatFormatting.RED + Integer.toString(rewrittenScore);
            if (shouldHideSidebarScoreColumnText(objective, scoreText)) {
                scoreText = null;
            }
            renderedScoreTexts.add(scoreText);
            int lineWidth = fontRenderer.getStringWidth(line);
            if (scoreText != null && !scoreText.isEmpty()) {
                lineWidth += valueGap + fontRenderer.getStringWidth(scoreText);
            }
            width = Math.max(width, lineWidth);
        }

        int totalHeight = renderedLines.size() * fontRenderer.FONT_HEIGHT;
        int bottomY = scaledResolution.getScaledHeight() / 2 + totalHeight / 3;
        int padding = 3;
        int left = scaledResolution.getScaledWidth() - width - padding;
        int right = scaledResolution.getScaledWidth() - padding + 2;

        if (replaceExisting) {
            int titleTop = bottomY - (renderedLines.size() + 1) * fontRenderer.FONT_HEIGHT - 1;
            int fullTop = titleTop - 2;
            int fullBottom = bottomY + 2;
            Gui.drawRect(left - 8, fullTop - 2, right + 8, fullBottom + 2, 0xFF000000);
        }

        for (int index = 0; index < renderedLines.size(); index++) {
            int row = index + 1;
            int y = bottomY - row * fontRenderer.FONT_HEIGHT;
            Gui.drawRect(left - 2, y, right, y + fontRenderer.FONT_HEIGHT, 1342177280);
            String scoreText = renderedScoreTexts.get(index);
            if (scoreText != null && !scoreText.isEmpty()) {
                int scoreX = right - fontRenderer.getStringWidth(scoreText);
                fontRenderer.drawString(scoreText, scoreX, y, 553648127);
            }
            String rowText = renderedLines.get(index);
            fontRenderer.drawString(rowText, left, y, 553648127);

            if (row == renderedLines.size()) {
                String title = objective.getDisplayName();
                Gui.drawRect(left - 2, y - fontRenderer.FONT_HEIGHT - 1, right, y - 1, 1610612736);
                Gui.drawRect(left - 2, y - 1, right, y, 1342177280);
                fontRenderer.drawString(title, left + width / 2 - fontRenderer.getStringWidth(title) / 2,
                        y - fontRenderer.FONT_HEIGHT, 553648127);
            }
        }

        return true;
    }

    public static synchronized boolean shouldHideSidebarRenderedScoreText(String text) {
        if (text == null || text.isEmpty() || activeSidebarRenderDepth <= 0 || activeSidebarRenderObjective == null) {
            return false;
        }

        String visible = stripFormattingCodes(text);
        if (visible == null) {
            return false;
        }

        visible = visible.trim();
        if (visible.isEmpty()) {
            return false;
        }

        if (!isEntirelySidebarScoreNumeric(visible)) {
            return false;
        }
        String entry = sidebarLastRenderedScoreRowKey.get();
        return shouldHideSidebarScoreColumnTextForActiveBoard(activeSidebarRenderObjective, entry);
    }

    public static synchronized boolean shouldHideSidebarRenderedScoreText(ScoreObjective objective, String text) {
        if (text == null || text.isEmpty() || !shouldOverrideBlitzLobbySidebar(objective)) {
            return false;
        }
        return shouldHideSidebarRenderedScoreText(text);
    }

    /**
     * Normal path is entry-specific: rows that got their stat inlined mark the duplicate score column for hiding.
     * Some client render paths don't preserve the exact row key when score text width/draw happens, so fall back to
     * hiding numeric score-column text for recognized disguise sidebars unless a row explicitly opted out.
     */
    private static boolean shouldHideSidebarScoreColumnTextForActiveBoard(ScoreObjective objective, String scoreboardEntryName) {
        if (objective == null || !shouldOverrideBlitzLobbySidebar(objective)) {
            return false;
        }

        if (scoreboardEntryName != null) {
            Boolean suppress = sidebarScoreColumnSuppressByEntry.get(scoreboardEntryName);
            if (suppress != null) {
                return suppress.booleanValue();
            }
        }

        return hasSidebarStatBoard(objective)
                || hasMainHypixelLobbyDisguiseSidebar(objective)
                || hasBlitzStatFingerprintSidebar(objective);
    }

    public static synchronized IChatComponent buildTabDisplayComponent(IChatComponent originalDisplayName, String baseUsername) {
        Minecraft mcEarly = Minecraft.getMinecraft();
        if (GameContext.isHypixelReplayWatching(mcEarly)) {
            String source = buildLiveTabDisplaySource(originalDisplayName, baseUsername);
            if (source == null || source.isEmpty()) source = baseUsername == null ? "" : baseUsername;
            return new ChatComponentText(rewriteReplayTabRowForRender(source, baseUsername));
        }
        if (isEnabled() && !shouldApplyTabDisplayOverride()) {
            return originalDisplayName;
        }
        if (!isEnabled()) {
            if (ConfigManager.isDisguiseCustomKillCountEnabled() && mcEarly != null && mcEarly.thePlayer != null
                    && baseUsername != null
                    && baseUsername.equalsIgnoreCase(mcEarly.thePlayer.getGameProfile().getName())) {
                String source = buildLiveTabDisplaySource(originalDisplayName, baseUsername);
                if (source == null || source.isEmpty()) {
                    source = baseUsername;
                }
                String merged = mergeCustomBsg2TabLineWithKillCount(source, ConfigManager.getDisguiseCustomKillCount());
                return new ChatComponentText(merged);
            }
            return originalDisplayName;
        }

        String source = buildLiveTabDisplaySource(originalDisplayName, baseUsername);
        if (source == null || source.isEmpty()) {
            source = baseUsername;
        }

        // Do not reuse a Hypixel-composed row after switching to another server. The non-Hypixel path
        // intentionally preserves the current server formatting and substitutes only the name.
        if (!isOnHypixelServer(mcEarly)) {
            return new ChatComponentText(rewriteNonHypixelNameOnly(source));
        }

        if (lastBuiltTabDisplayComponent != null
                && source != null
                && source.equals(lastBuiltTabDisplaySource)
                && ((baseUsername == null && lastBuiltTabDisplayBaseUsername == null)
                || (baseUsername != null && baseUsername.equals(lastBuiltTabDisplayBaseUsername)))) {
            return lastBuiltTabDisplayComponent;
        }

        String rewritten = rewriteTabDisplayText(source);
        if (rewritten == null || rewritten.isEmpty()) {
            return originalDisplayName;
        }
        IChatComponent out = new ChatComponentText(rewritten);
        lastBuiltTabDisplaySource = source;
        lastBuiltTabDisplayBaseUsername = baseUsername;
        lastBuiltTabDisplayComponent = out;
        return out;
    }

    private static String buildLiveTabDisplaySource(IChatComponent originalDisplayName, String baseUsername) {
        String original = originalDisplayName == null ? null : originalDisplayName.getFormattedText();
        // Prefer KillCounter's full TAB line (kill effect on name, [kills], stats). Rebuilding from
        // ScorePlayerTeam + suffix dropped kill-effect colors and sometimes the kill count when casing differed.
        if (original != null && !original.trim().isEmpty()) {
            return original;
        }
        String teamFormatted = buildTeamFormattedName(baseUsername);
        if (teamFormatted != null && !teamFormatted.isEmpty()) {
            return teamFormatted;
        }
        return original != null ? original : "";
    }

    private static String mergeCustomBsg2TabLineWithKillCount(String formattedLine, int killCount) {
        String rest = stripAllLeadingNumericKillBrackets(formattedLine == null ? "" : formattedLine);
        return formatBsg2KillBracketForDisplay(Math.max(0, killCount)) + " " + rest;
    }

    private static String stripAllLeadingNumericKillBrackets(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return "";
        }
        String s = formatted;
        for (int guard = 0; guard < 12; guard++) {
            Matcher m = LEADING_FORMATTED_BRACKET.matcher(s);
            if (!m.find() || m.start() != 0) {
                break;
            }
            if (!isInnerPureNumericKillBracketContent(m.group(2))) {
                break;
            }
            s = s.substring(m.end());
        }
        return s;
    }

    private static boolean isInnerPureNumericKillBracketContent(String inner) {
        if (inner == null) {
            return false;
        }
        String plain = stripFormattingCodes(inner);
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        plain = plain.replace(",", "").trim();
        for (int i = 0; i < plain.length(); i++) {
            if (!Character.isDigit(plain.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Undisguised + custom kills: replace a leading Hypixel {@code [12345]} before your name in chat/HUD strings.
     */
    private static String tryRewriteCustomKillBracketInText(String input) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) {
                return null;
            }
            String self = mc.thePlayer.getGameProfile().getName();
            if (self == null || self.isEmpty()) {
                return null;
            }
            // Only replace leading [kills] on lines where you are the speaker — not when your IGN appears in someone
            // else's message body (e.g. they mention your name after ':').
            String plain = stripFormattingCodes(input);
            if (plain == null) {
                plain = "";
            }
            int firstColon = plain.indexOf(':');
            if (firstColon < 0 || !containsIgnoreCase(plain.substring(0, firstColon), self)) {
                return null;
            }
            Matcher fm = LEADING_FORMATTED_BRACKET.matcher(input);
            if (!fm.find() || fm.start() != 0) {
                return null;
            }
            if (!isInnerPureNumericKillBracketContent(fm.group(2))) {
                return null;
            }
            String rest = stripAllLeadingNumericKillBrackets(input);
            int k = Math.max(0, ConfigManager.getDisguiseCustomKillCount());
            return formatBsg2KillBracketForDisplay(k) + " " + rest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * FontRenderer entry point. Ordinary strings are scanned at most once per disguise state and then returned from
     * a bounded lock-free cache. The cache also stores unchanged strings, which dominate HUD/menu rendering.
     */
    public static String rewriteRenderedTextFast(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (BlitzChatNameDisplay.shouldPreserveRenderedPlayerText(input)) return input;
        String replayLabel = rewriteReplayPlayerFontText(input);
        if (replayLabel != null) return replayLabel;
        if (BlitzSoloExtendedResults.isPreservedDisguiseResultTextExact(input)) {
            return input;
        }
        if (fastEnabledFlag) {
            if (!fastRenderTextRewriteFlag) {
                return input;
            }

            if (!isOnHypixelServer(Minecraft.getMinecraft())) {
                return rewriteNonHypixelNameOnly(input);
            }

            String rouletteName = rewriteBlitzRouletteNameText(input);
            if (!input.equals(rouletteName)) {
                return rouletteName;
            }

            // A #rekt label is a world hologram, not a player nametag. Resolve it with the target's
            // rank color before GAME OVER's context-aware player-label rewrite can apply the nametag tint.
            if (input.indexOf('#') >= 0 && containsIgnoreCase(input, "#rekt")) {
                String rektHologram = rewriteBlitzRektFinisherHologramText(input);
                if (!input.equals(rektHologram)) {
                    return rektHologram;
                }
            }

            // Resolve GAME OVER before either text-only cache can reapply the target's lobby rank color
            // to a red player label. Leave the established cage/live-match rewrite paths unchanged.
            String playerLabel = rewriteGameOverPlayerLabelBeforeRenderCache(input);
            if (playerLabel != null) {
                return playerLabel;
            }

            // Join/leave name tint depends on the current Blitz phase (white in the hub, red in match lobbies).
            // Do not reuse a text-only cache entry across phase changes.
            if (looksLikeBlitzJoinLeaveAnnouncementFast(input)) {
                return rewriteBlitzJoinLeaveAnnouncementText(input);
            }

            // Hypixel's §kXXXX<IGN> cage name does not pass the standalone-name test, and its value changes
            // across AntiShuffle/Lunar paths. Rewrite this exact mask before consulting the text-only cache.
            String maskedName = rewriteLocalObfuscatedName(input);
            if (!input.equals(maskedName)) {
                return maskedName;
            }

            // Coin-chest announcements carry rank/name text but are not ordinary chat-speaker lines.
            if (looksLikeBlitzCoinChestAnnouncementFast(input)) {
                String coinChest = rewriteBlitzCoinChestAnnouncementText(input);
                if (!input.equals(coinChest)) {
                    return coinChest;
                }
            }

            // Identical Kills/Coins rows occur in the hub and in matches. Their meaning is phase-dependent.
            if (isStandaloneSidebarStatText(input)) {
                return rewriteStandaloneSidebarStatLine(input);
            }

            // Sidebar score hiding depends on the current row/thread-local render context, not just the text value.
            if (fastSidebarRenderActive) {
                return shouldAttemptRenderRewriteFast(input) ? replaceRenderedText(input) : input;
            }

            long generation = fastRenderCacheGeneration;
            int hash = input.hashCode();
            int slot = (hash ^ (hash >>> 16)) & FAST_RENDER_CACHE_MASK;
            FastRenderCacheEntry cached = fastRenderCache.get(slot);
            if (cached != null && cached.generation == generation
                    && (cached.input == input || cached.input.equals(input))) {
                return cached.output;
            }

            String output = shouldAttemptRenderRewriteFast(input) ? replaceRenderedText(input) : input;
            if (output == null) {
                output = input;
            }
            // Do not publish a result computed while disguise/rank state was being invalidated.
            if (generation == fastRenderCacheGeneration) {
                fastRenderCache.set(slot, new FastRenderCacheEntry(generation, input, output));
            }
            return output;
        }

        if (!ConfigManager.isDisguiseCustomKillCountEnabled()) {
            return input;
        }
        return shouldAttemptRenderRewriteFast(input) ? replaceRenderedText(input) : input;
    }

    /** Identifies player rows rather than chat, announcements or leaderboard sentences. */
    public static boolean isLocalDisguisePlayerLabelText(String input) {
        if (!fastEnabledFlag || input == null || input.isEmpty() || fastSidebarRenderActive) return false;
        String visible = input.indexOf('\u00a7') < 0 ? input : stripFormattingCodes(input);
        String source = fastRealNameText;
        String target = fastTargetNameText;
        boolean hasSource = source != null && !source.isEmpty()
                && indexOfIgnoreCaseStandalone(visible, source, 0) >= 0;
        boolean hasTarget = target != null && !target.isEmpty()
                && indexOfIgnoreCaseStandalone(visible, target, 0) >= 0;
        if (!hasSource && !hasTarget) return false;
        if (KillCounter.isTabOverlayRenderActive()) {
            return !looksLikeDisguiseChatSpeakerLine(input) && !looksLikeEndgamePlacementLine(input);
        }
        String name = visible.trim();
        return (hasSource && name.equalsIgnoreCase(source)) || (hasTarget && name.equalsIgnoreCase(target));
    }

    private static String rewriteGameOverPlayerLabelBeforeRenderCache(String input) {
        if (!isLocalDisguisePlayerLabelText(input)) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (!GameContext.isBlitzGameOverSidebar(mc)
                || !GameContext.isBlitzTabDisguiseRankSuppressed(mc)) return null;
        return rewriteTabDisplayText(input);
    }

    /**
     * Lunar draws some world text holograms from its own chunk renderer instead of the normal
     * entity-label/font paths. Give those known leaderboard rows the specific rewrite directly,
     * so a text-render prefilter or cached no-op cannot let the source IGN leak through.
     */
    public static String rewriteLunarLeaderboardTextHologram(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (input.indexOf('#') >= 0 && containsIgnoreCase(input, "#rekt")) {
            if (fastEnabledFlag && fastRenderTextRewriteFlag) {
                String localRektHologram = rewriteBlitzRektFinisherHologramText(input);
                if (!input.equals(localRektHologram)) {
                    return localRektHologram;
                }
            }
            // The Lunar text-hologram renderer is also the entry point for Gomod-shared /disguiser
            // targets. Do not gate those peer rewrites on this client having a local disguise enabled.
            return RemoteDisguiseManager.rewriteLeaderboardHologramText(input);
        }
        if (!fastEnabledFlag || !fastRenderTextRewriteFlag) {
            return input;
        }
        String rewrittenLeaderboard = rewriteLeaderboardHologramLine(input);
        if (rewrittenLeaderboard != null && !rewrittenLeaderboard.equals(input)) {
            return rewrittenLeaderboard;
        }
        return rewriteRenderedTextFast(input);
    }

    public static synchronized String replaceRenderedText(String input) {
        String replayLabel = rewriteReplayPlayerFontText(input);
        if (replayLabel != null) return replayLabel;
        if (fastEnabledFlag && !fastRenderTextRewriteFlag) {
            return input;
        }
        if (shouldHideSidebarRenderedScoreText(input)) {
            return "";
        }
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (BlitzSoloExtendedResults.isPreservedDisguiseResultText(input)) {
            return input;
        }
        if (isEnabled() && !isOnHypixelServer(Minecraft.getMinecraft())) {
            return rewriteNonHypixelNameOnly(input);
        }
        String rouletteName = rewriteBlitzRouletteNameText(input);
        if (!input.equals(rouletteName)) {
            renderCache.put(input, rouletteName);
            return rouletteName;
        }
        String playerLabel = rewriteGameOverPlayerLabelBeforeRenderCache(input);
        if (playerLabel != null) {
            return playerLabel;
        }
        // The correct join/leave tint is phase-dependent, so resolve it before consulting the text-only cache.
        if (looksLikeBlitzJoinLeaveAnnouncementFast(input)) {
            return rewriteBlitzJoinLeaveAnnouncementText(input);
        }
        String maskedName = rewriteLocalObfuscatedName(input);
        if (!input.equals(maskedName)) {
            renderCache.put(input, maskedName);
            return maskedName;
        }
        if (looksLikeBlitzCoinChestAnnouncementFast(input)) {
            String coinChest = rewriteBlitzCoinChestAnnouncementText(input);
            if (!input.equals(coinChest)) {
                renderCache.put(input, coinChest);
                return coinChest;
            }
        }
        // Never read or publish a text-only cached sidebar stat across game-phase changes.
        if (isEnabled() && isStandaloneSidebarStatText(input)) {
            return rewriteStandaloneSidebarStatLine(input);
        }
        String cachedEarly = renderCache.get(input);
        if (cachedEarly != null) {
            return cachedEarly;
        }
        if (!isEnabled() && ConfigManager.isDisguiseCustomKillCountEnabled()) {
            String customLine = tryRewriteCustomKillBracketInText(input);
            if (customLine != null) {
                return customLine;
            }
            return input;
        }
        if (!isEnabled()) {
            return input;
        }
        if (looksLikeBlitzStarAnnouncementFast(input)) {
            String rewrittenBlitzStarEarly = rewriteBlitzStarAnnouncementText(input);
            if (rewrittenBlitzStarEarly != null && !rewrittenBlitzStarEarly.equals(input)) {
                renderCache.put(input, rewrittenBlitzStarEarly);
                return rewrittenBlitzStarEarly;
            }
        }
        if (!mightContainDisguiseText(input)) {
            return input;
        }
        if (looksLikeHypixelKitStyledItemDisplayTitle(input)) {
            return input;
        }
        String cached = renderCache.get(input);
        if (cached != null) {
            return cached;
        }
        String rewrittenLeaderboard = rewriteLeaderboardHologramLine(input);
        if (rewrittenLeaderboard != null && !rewrittenLeaderboard.equals(input)) {
            renderCache.put(input, rewrittenLeaderboard);
            return rewrittenLeaderboard;
        }
        String rewrittenKillMessage = rewriteKillMessageText(input);
        if (rewrittenKillMessage != null && !rewrittenKillMessage.equals(input)) {
            renderCache.put(input, rewrittenKillMessage);
            return rewrittenKillMessage;
        }
        String rewrittenTaunt = rewriteTauntAnnouncementText(input);
        if (rewrittenTaunt != null && !rewrittenTaunt.equals(input)) {
            renderCache.put(input, rewrittenTaunt);
            return rewrittenTaunt;
        }
        String rewrittenIgnFragment = rewriteLocalDisguiseStandaloneIgnFragment(input);
        if (rewrittenIgnFragment != null && !rewrittenIgnFragment.equals(input)) {
            renderCache.put(input, rewrittenIgnFragment);
            return rewrittenIgnFragment;
        }
        String rewrittenHypixelLobbyJoin = rewriteHypixelLobbyJoinNotificationText(input);
        if (rewrittenHypixelLobbyJoin != null && !rewrittenHypixelLobbyJoin.equals(input)) {
            renderCache.put(input, rewrittenHypixelLobbyJoin);
            return rewrittenHypixelLobbyJoin;
        }
        String rewrittenPartyInvite = rewritePartyInviteNotificationText(input);
        if (rewrittenPartyInvite != null && !rewrittenPartyInvite.equals(input)) {
            renderCache.put(input, rewrittenPartyInvite);
            return rewrittenPartyInvite;
        }
        String rewrittenSummary = rewriteEndgameSummaryLine(input);
        if (rewrittenSummary != null && !rewrittenSummary.equals(input)) {
            renderCache.put(input, rewrittenSummary);
            return rewrittenSummary;
        }
        String rewrittenBlitzStar = rewriteBlitzStarAnnouncementText(input);
        if (rewrittenBlitzStar != null && !rewrittenBlitzStar.equals(input)) {
            renderCache.put(input, rewrittenBlitzStar);
            return rewrittenBlitzStar;
        }
        String rewrittenChat = rewriteChatSpeakerText(input);
        if (rewrittenChat != null && !rewrittenChat.equals(input)) {
            renderCache.put(input, rewrittenChat);
            return rewrittenChat;
        }
        // TAB/nametag draw paths call FontRenderer after getDisplayName already ran rewriteTabDisplayText.
        // Falling through to rewriteDisplayText here would re-apply disguise rank colors (aqua MVP+, etc.) on Blitz rows.
        Minecraft mcTab = Minecraft.getMinecraft();
        if (mcTab != null && GameContext.isBlitzTabDisguiseRankSuppressed(mcTab)
                && mightContainDisguiseText(input)
                && !looksLikeDisguiseChatSpeakerLine(input)
                && !looksLikeEndgamePlacementLine(input)) {
            String tabRewritten = rewriteTabDisplayText(input);
            renderCache.put(input, tabRewritten);
            return tabRewritten;
        }
        String rewritten = rewriteDisplayText(input, true, false, false);
        // When speaker-line rewrite is skipped (e.g. unusual spacing), generic replace still fixes the name —
        // apply the same §7 body for non-ranked targets on Hypixel everywhere, not only in Blitz.
        rewritten = applyGrayToDisguiseChatBodyIfNeeded(rewritten);
        renderCache.put(input, rewritten);
        return rewritten;
    }

    /**
     * Rewrites a chat line once as it enters {@code GuiNewChat}. This is the source-level fallback for loaders that
     * do not deliver {@code ChatReceivedEvent}; it keeps disguise names in chat without restoring the old
     * per-FontRenderer-call rewrite that caused frame-time spikes.
     */
    public static IChatComponent rewriteIncomingChatComponentForDisplay(IChatComponent original) {
        if (original == null || !fastEnabledFlag || !fastRenderTextRewriteFlag) {
            return original;
        }
        try {
            String formatted = original.getFormattedText();
            if (BlitzSoloExtendedResults.isPreservedDisguiseResultText(formatted)) {
                return original;
            }
            String unformatted = original.getUnformattedText();
            String real = fastRealNameText;
            boolean hasRealInFormatted = real != null && !real.isEmpty()
                    && indexOfIgnoreCaseStandalone(formatted, real, 0) >= 0;
            boolean hasRealInPlainText = real != null && !real.isEmpty()
                    && indexOfIgnoreCaseStandalone(unformatted, real, 0) >= 0;
            boolean looksLikeKillFeed = looksLikeKillFeedLineFast(formatted)
                    || looksLikeKillFeedLineFast(unformatted);
            boolean looksLikeCoinChest = looksLikeBlitzCoinChestAnnouncementFast(formatted)
                    || looksLikeBlitzCoinChestAnnouncementFast(unformatted);
            boolean looksLikeRoulette = BlitzRouletteText.isRouletteNameMessage(formatted)
                    || BlitzRouletteText.isRouletteNameMessage(unformatted);
            if (!hasRealInFormatted && !hasRealInPlainText && !looksLikeKillFeed && !looksLikeCoinChest
                    && !looksLikeRoulette) {
                // This line is unrelated, or it has already been rewritten before this method is reached. Avoid
                // reprocessing formatted target-name lines when printChatMessage delegates to printChatMessageWithOptionalDeletion.
                return original;
            }
            String rewritten = rewriteIncomingChatTextForDisplay(formatted);
            if (rewritten == null || rewritten.equals(formatted)) {
                return original;
            }
            return new ChatComponentText(rewritten);
        } catch (Throwable ignored) {
            // Chat must remain lossless if a server-specific component or formatting sequence is unexpected.
            return original;
        }
    }

    /**
     * Incoming-chat entry point. Unlike the generic HUD/world-text pipeline, this rejects unrelated lines without
     * taking the class monitor and routes common self-chat and kill-feed lines straight to their dedicated parser.
     */
    public static String rewriteIncomingChatTextForDisplay(String formatted) {
        if (formatted == null || formatted.isEmpty() || !fastEnabledFlag || !fastRenderTextRewriteFlag) {
            return formatted;
        }
        if (BlitzSoloExtendedResults.isPreservedDisguiseResultText(formatted)) {
            return formatted;
        }

        String real = fastRealNameText;
        String target = fastTargetNameText;
        // A formatting byte (e.g. the 'f' in §f immediately before an IGN) is not a visible
        // username character. Server components can also insert formatting between letters.
        String visible = formatted.indexOf('\u00a7') >= 0 ? stripFormattingCodes(formatted) : formatted;
        boolean hasReal = real != null && !real.isEmpty()
                && indexOfIgnoreCaseStandalone(visible, real, 0) >= 0;
        boolean hasTarget = target != null && !target.isEmpty()
                && indexOfIgnoreCaseStandalone(visible, target, 0) >= 0;
        if (!hasReal && !hasTarget
                && !looksLikeKillFeedLineFast(formatted)
                && !looksLikeBlitzJoinLeaveAnnouncementFast(formatted)
                && !looksLikeBlitzStarAnnouncementFast(formatted)
                && !looksLikeBlitzCoinChestAnnouncementFast(formatted)
                && !BlitzRouletteText.isRouletteNameMessage(formatted)) {
            return formatted;
        }

        synchronized (LocalDisguise.class) {
            if (!isEnabled() || !shouldApplyRenderTextRewrite()) {
                return formatted;
            }
            if (!isOnHypixelServer(Minecraft.getMinecraft())) {
                String nameOnly = rewriteNonHypixelNameOnly(formatted);
                renderCache.put(formatted, nameOnly);
                return nameOnly;
            }
            String rouletteName = rewriteBlitzRouletteNameText(formatted);
            if (!formatted.equals(rouletteName)) {
                renderCache.put(formatted, rouletteName);
                return rouletteName;
            }
            // These events can be rendered again after the queue/match phase changes. Recompute their tint each time.
            if (looksLikeBlitzJoinLeaveAnnouncementFast(formatted)) {
                return rewriteBlitzJoinLeaveAnnouncementText(formatted);
            }
            if (looksLikeBlitzCoinChestAnnouncementFast(formatted)) {
                String coinChest = rewriteBlitzCoinChestAnnouncementText(formatted);
                if (!formatted.equals(coinChest)) {
                    renderCache.put(formatted, coinChest);
                    return coinChest;
                }
            }
            String cached = renderCache.get(formatted);
            if (cached != null) {
                return cached;
            }

            String rewritten;
            if (looksLikeKillFeedLineFast(formatted)) {
                rewritten = rewriteKillMessageText(formatted);
            } else if (looksLikeLocalChatSpeakerLineFast(formatted)) {
                rewritten = rewriteChatSpeakerText(formatted);
            } else {
                // Rare system formats keep the complete compatibility pipeline.
                rewritten = replaceRenderedText(formatted);
            }
            if (rewritten == null) {
                rewritten = formatted;
            }
            renderCache.put(formatted, rewritten);
            return rewritten;
        }
    }

    /** Cheap visible-name/colon check before the formatting-aware speaker parser builds its index mapping. */
    private static boolean looksLikeLocalChatSpeakerLineFast(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return false;
        }
        int colon = formatted.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String real = fastRealNameText;
        if (real != null && !real.isEmpty()) {
            int at = indexOfIgnoreCaseStandalone(formatted, real, 0);
            if (at >= 0 && at < colon) {
                return true;
            }
        }
        String target = fastTargetNameText;
        if (target != null && !target.isEmpty()) {
            int at = indexOfIgnoreCaseStandalone(formatted, target, 0);
            return at >= 0 && at < colon;
        }
        return false;
    }

    /**
     * Lock-free prefilter for FontRenderer hot paths. Returning false here means "definitely nothing disguise-related
     * to do", so the expensive synchronized rewrite path can be skipped entirely.
     */
    public static boolean shouldAttemptRenderRewriteFast(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (BlitzSoloExtendedResults.isPreservedDisguiseResultTextExact(text)
                || BlitzChatNameDisplay.shouldPreserveRenderedPlayerText(text)) {
            return false;
        }
        if (fastEnabledFlag && !fastRenderTextRewriteFlag) {
            return false;
        }
        if (!fastEnabledFlag && !ConfigManager.isDisguiseCustomKillCountEnabled()) {
            return false;
        }
        if (!fastEnabledFlag) {
            return text.indexOf('[') >= 0 || text.indexOf(realSectionSign()) >= 0;
        }

        if (fastSidebarRenderActive && mightBeStandaloneSidebarStatLine(text)) {
            return true;
        }

        String real = fastRealNameText;
        String target = fastTargetNameText;
        if (real != null && !real.isEmpty() && BlitzRouletteText.isRouletteNameMessage(text)
                && BlitzRouletteText.containsUsername(text, real)) {
            return true;
        }
        long generation = fastRenderCacheGeneration;
        if (nonCandidateRenderText.contains(text, real, target, generation)) {
            return false;
        }
        if (real != null && !real.isEmpty() && indexOfIgnoreCaseStandalone(text, real, 0) >= 0) {
            if (BlitzSoloExtendedResults.isPreservedDisguiseResultText(text)) {
                return false;
            }
            return true;
        }
        if (real != null && !real.isEmpty()
                && LunarDisguiseDisplayCompatibility.containsObfuscatedHypixelName(text, real)) {
            return true;
        }

        if (target != null && !target.isEmpty() && indexOfIgnoreCaseStandalone(text, target, 0) >= 0) {
            if (BlitzSoloExtendedResults.isPreservedDisguiseResultText(text)) {
                return false;
            }
            return true;
        }

        if (looksLikeKillFeedLineFast(text)) {
            return true;
        }

        if (looksLikeBlitzJoinLeaveAnnouncementFast(text)) {
            return true;
        }

        if (looksLikeBlitzStarAnnouncementFast(text)) {
            return true;
        }

        if (looksLikeBlitzCoinChestAnnouncementFast(text)) {
            return true;
        }

        if (containsIgnoreCase(text, "joined the lobby") || containsIgnoreCase(text, "you have no data yet")) {
            return true;
        }
        // Only remember definitely unrelated text. Player labels, chat announcements, sidebar rows and
        // other candidates still use their live context/formatting checks on every draw.
        nonCandidateRenderText.remember(text, real, target, generation);
        return false;
    }

    /** Cheap source-name guard for the optional AntiShuffle return hook on its text-rendering hot path. */
    public static boolean containsLocalDisguiseSourceNameFast(String text) {
        if (!fastEnabledFlag || !fastRenderTextRewriteFlag || text == null || text.isEmpty()) {
            return false;
        }
        String real = fastRealNameText;
        return real != null && !real.isEmpty()
                && (indexOfIgnoreCaseStandalone(text, real, 0) >= 0
                || LunarDisguiseDisplayCompatibility.containsObfuscatedHypixelName(text, real));
    }

    private static boolean looksLikeBlitzCoinChestAnnouncementFast(String text) {
        if (text == null || text.length() >= 512) return false;
        if (text.indexOf(" found ") >= 0 && text.indexOf(" coins in a chest!") >= 0) return true;
        if ((text.indexOf('f') < 0 && text.indexOf('F') < 0)
                || (text.indexOf('c') < 0 && text.indexOf('C') < 0)) return false;
        String plain = text.indexOf('\u00a7') < 0 ? text : stripFormattingCodes(text);
        return containsIgnoreCase(plain, " found ") && containsIgnoreCase(plain, " coins in a chest!");
    }

    private static String rewriteBlitzCoinChestAnnouncementText(String formatted) {
        if (formatted == null || !isEnabled() || realName == null || realName.isEmpty()
                || !isBlitzSgChatContextActive()) {
            return formatted;
        }
        String visible = stripFormattingCodes(formatted);
        Matcher announcement = BLITZ_COIN_CHEST_ANNOUNCEMENT_PATTERN.matcher(visible);
        if (!announcement.matches() || !realName.equalsIgnoreCase(announcement.group(1))) {
            return formatted;
        }
        String replacement = formatCosmeticChatRankAndDisplayName();
        if (replacement == null || replacement.isEmpty()) {
            replacement = getTargetTabChatDisplayNameForRendering();
        }
        return LunarDisguiseDisplayCompatibility.replaceHypixelRankedName(formatted, realName, replacement);
    }

    private static String rewriteLocalObfuscatedName(String formatted) {
        if (formatted == null || !LunarDisguiseDisplayCompatibility.hasHypixelMaskMarker(formatted)
                || !isEnabled() || realName == null || realName.isEmpty()
                || !isOnHypixelServer(Minecraft.getMinecraft())
                || !LunarDisguiseDisplayCompatibility.containsObfuscatedHypixelName(formatted, realName)) {
            return formatted;
        }
        String replacement = getTargetTabChatDisplayNameForRendering();
        if (replacement == null || replacement.isEmpty()) return formatted;
        return LunarDisguiseDisplayCompatibility.replaceObfuscatedHypixelName(formatted, realName, replacement);
    }

    private static boolean looksLikeBlitzStarAnnouncementFast(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return containsIgnoreCase(text, "blitz star")
                || containsIgnoreCase(text, " blitz! ");
    }

    /**
     * Roulette broadcasts and captions show only the player IGN in its rank color. Rewrite the local source IGN
     * without adding a rank tag, kill prefix, or guild suffix.
     */
    public static String rewriteBlitzRouletteNameText(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()
                || !BlitzRouletteText.isRouletteNameMessage(input)
                || !BlitzRouletteText.containsUsername(input, realName)) {
            return input;
        }
        return replaceRankAndNameSegment(input, realName, false, false, true, false, false, false);
    }

    /** The animated Roulette title is a single colored IGN, with no rank or guild prefix. */
    public static boolean isLocalDisguiseSourceNameLabel(String input) {
        return isEnabled() && realName != null && !realName.isEmpty()
                && BlitzRouletteText.isStandaloneUsernameLabel(input, realName);
    }

    /** Normalize a title already rewritten by the general HUD path, removing any appended guild suffix. */
    public static String rewriteBlitzRouletteTitleNameOnly(String input) {
        if (input == null || input.isEmpty() || !isEnabled()) return input;
        String target = getTargetTabChatDisplayNamePlain();
        if (target == null || target.trim().isEmpty()) target = targetName;
        if (target == null || target.trim().isEmpty() || !BlitzRouletteText.containsUsername(input, target)) return input;
        return replaceRankAndNameSegment(input, target, false, false, true, false, false, false);
    }

    private static boolean looksLikeBlitzJoinLeaveAnnouncementFast(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.indexOf(" has joined (") >= 0 && text.indexOf(")!") >= 0
                || containsIgnoreCase(text, " has quit!");
    }

    private static char realSectionSign() {
        return '\u00A7';
    }

    /**
     * Case-insensitive substring test without allocating a lowercased copy of {@code haystack}
     * (FontRenderer hits this thousands of times per frame when disguise is on).
     */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return false;
        }
        final int nl = needle.length();
        final int hl = haystack.length();
        if (nl > hl) {
            return false;
        }
        for (int i = 0; i <= hl - nl; i++) {
            if (haystack.regionMatches(true, i, needle, 0, nl)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mightContainDisguiseText(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        if (realName != null && !realName.isEmpty() && indexOfIgnoreCaseStandalone(input, realName, 0) >= 0) {
            return true;
        }
        if (targetName != null && !targetName.isEmpty() && indexOfIgnoreCaseStandalone(input, targetName, 0) >= 0) {
            return true;
        }
        String tabDispPlain = getTargetTabChatDisplayNamePlain();
        if (!tabDispPlain.isEmpty() && indexOfIgnoreCaseStandalone(input, tabDispPlain, 0) >= 0) {
            return true;
        }
        if (looksLikeBlitzJoinLeaveAnnouncementFast(input)) {
            return true;
        }
        if (looksLikeBlitzStarAnnouncementFast(input)) {
            return true;
        }
        if (looksLikeKillFeedLineFast(input)) {
            return true;
        }
        if (containsIgnoreCase(input, "joined the lobby")
                && ((realName != null && !realName.isEmpty() && containsIgnoreCase(input, realName))
                || (targetName != null && !targetName.isEmpty() && containsIgnoreCase(input, targetName)))) {
            return true;
        }
        // Leaderboard line fallback
        if (containsIgnoreCase(input, "you have no data yet")) {
            return true;
        }
        return false;
    }

    /** True when {@code input} looks like {@code [RANK] Name: message}, not a TAB/nametag row. */
    private static boolean looksLikeDisguiseChatSpeakerLine(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        SpanData sd = buildVisibleMapping(input);
        String visible = sd.visibleText;
        if (visible == null || visible.isEmpty()) {
            return false;
        }
        int colon = visible.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String speaker = visible.substring(0, colon);
        if (realName != null && !realName.isEmpty() && indexOfIgnoreCase(speaker, realName) >= 0) {
            return true;
        }
        if (targetName != null && !targetName.isEmpty() && indexOfIgnoreCase(speaker, targetName) >= 0) {
            return true;
        }
        String tabDisp = getTargetTabChatDisplayNameForRendering();
        String tabDispPlain = stripFormattingCodes(tabDisp);
        return tabDispPlain != null && !tabDispPlain.isEmpty()
                && indexOfIgnoreCase(speaker, tabDispPlain) >= 0;
    }

    /** Post-game placement rows ({@code 1st -}, {@code Winner -}, etc.) — use disguise rank colors, not flat Blitz TAB tints. */
    private static boolean looksLikeEndgamePlacementLine(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        String probe = stripFormattingCodes(input);
        if (probe == null || probe.isEmpty()) {
            return false;
        }
        return ENDGAME_PLACEMENT_PREFIX.matcher(probe.trim()).find();
    }

    public static synchronized boolean shouldHideRenderedLabel(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || !shouldApplyRenderTextRewrite()) {
            return false;
        }

        String rewrittenLeaderboard = rewriteLeaderboardHologramLine(input);
        return rewrittenLeaderboard != null && rewrittenLeaderboard.isEmpty();
    }

    public static synchronized void beginSidebarRender(ScoreObjective objective) {
        activeSidebarRenderDepth++;
        if (activeSidebarRenderDepth == 1) {
            sidebarScoreColumnSuppressByEntry.clear();
            activeSidebarRenderObjective = shouldOverrideBlitzLobbySidebar(objective) ? objective : null;
            fastSidebarRenderActive = activeSidebarRenderObjective != null;
        }
    }

    public static synchronized void endSidebarRender() {
        if (activeSidebarRenderDepth <= 0) {
            return;
        }
        activeSidebarRenderDepth--;
        if (activeSidebarRenderDepth <= 0) {
            activeSidebarRenderDepth = 0;
            activeSidebarRenderObjective = null;
            sidebarLastRenderedScoreRowKey.remove();
            fastSidebarRenderActive = false;
        }
    }

    private static void clearLiveTabDisplayCache() {
        lastBuiltTabDisplaySource = null;
        lastBuiltTabDisplayBaseUsername = null;
        lastBuiltTabDisplayComponent = null;
    }

    /** Clears cached {@link #buildTabDisplayComponent} results when Blitz TAB phase or formatting changes. */
    public static synchronized void invalidateTabDisplayRewriteCache() {
        clearLiveTabDisplayCache();
        clearRenderCachesLocked();
    }

    private static void clearRenderCachesLocked() {
        renderCache.clear();
        fastRenderCacheGeneration++;
    }

    /** Cheap revision for display caches that must observe an automatic target swap within the same tick. */
    public static long getDisplayRevision() {
        return fastRenderCacheGeneration;
    }

    public static synchronized String buildTabDisplayName(String originalName, String baseUsername) {
        Minecraft replayMc = Minecraft.getMinecraft();
        if (GameContext.isHypixelReplayWatching(replayMc)) {
            return rewriteReplayTabRowForRender(originalName, baseUsername);
        }
        boolean override = shouldApplyTabDisplayOverride();
        String rewritten = override ? rewriteTabDisplayText(originalName) : originalName;
        if (LocalDisguiseDebugCapture.isActive()) {
            Minecraft mc = Minecraft.getMinecraft();
            LocalDisguiseDebugCapture.record("tab-line", "local-player",
                    "override=" + override + " enabled=" + isEnabled()
                            + " source=" + baseUsername + " real=" + realName + " target=" + targetName
                            + " input=" + originalName + " output=" + rewritten
                            + " game=" + GameContext.explainDetection(mc));
        }
        return rewritten;
    }

    /** Replay player-list rows use white names, except the local spectator's plain gray Viewer row. */
    public static String rewriteReplayTabRowForRender(String input, String baseUsername) {
        return rewriteReplayTabRowForRender(input, baseUsername, false);
    }

    /** Prefer the connection-owned row identity over names/text when deciding which Replay row is the viewer. */
    public static String rewriteReplayTabRowForRender(String input, NetworkPlayerInfo info) {
        GameProfile profile = info == null ? null : info.getGameProfile();
        Minecraft mc = Minecraft.getMinecraft();
        boolean viewer = isReplayViewerNetworkInfo(info, profile, mc);
        return rewriteReplayTabRowForProfile(input, profile, viewer);
    }

    /** Use the real row UUID: the recorded actor can have the same name as the live viewer. */
    public static String rewriteReplayTabRowForRender(String input, GameProfile profile) {
        Minecraft mc = Minecraft.getMinecraft();
        UUID viewerId = mc != null && mc.thePlayer != null ? mc.thePlayer.getUniqueID() : null;
        boolean viewer = profile != null && viewerId != null && viewerId.equals(profile.getId());
        return rewriteReplayTabRowForProfile(input, profile, viewer);
    }

    private static String rewriteReplayTabRowForProfile(String input, GameProfile profile, boolean viewer) {
        Minecraft mc = Minecraft.getMinecraft();
        UUID viewerId = mc != null && mc.thePlayer != null ? mc.thePlayer.getUniqueID() : null;
        if (profile != null && !viewer && GameContext.isHypixelReplayWatching(mc)) {
            RemoteDisguiseProfile manual = RemoteDisguiseManager.getDisguisedTargetProfile(profile);
            if (manual != null && manual.name != null && !manual.name.isEmpty()) {
                // Replay actors use only their IGN: do not carry either the recorded row's rank/guild
                // or the disguise target's lobby formatting into the replay TAB list.
                input = EnumChatFormatting.WHITE + manual.name;
            } else if (containsReplayViewerTag(input)) {
                // Only the live viewer's connection row may display [Viewer]. A recorded actor can have
                // the same IGN, so rebuild this actor row from its profile/disguise instead of trusting text.
                input = buildReplayActorTabName(profile);
            } else if (!isReplayLocalDisguiseSource(profile.getName())) {
                input = GomodPeerDisguiseDisplay.rewriteTabName(profile, input);
            }
        }
        String output = rewriteReplayTabRowForRender(input, profile == null ? null : profile.getName(), viewer);
        if (LocalDisguiseDebugCapture.isActive() && GameContext.isHypixelReplayWatching(mc)) {
            LocalDisguiseDebugCapture.record("replay-tab-row", String.valueOf(profile == null ? null : profile.getId()),
                    "source=" + (profile == null ? null : profile.getName()) + " localViewer=" + viewer
                            + " viewerUuid=" + viewerId + " input=" + input + " output=" + output);
        }
        return output;
    }

    private static String buildReplayActorTabName(GameProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().trim().isEmpty()) return "";
        String actorName = replayDisplayUsername(profile.getName());
        String display = EnumChatFormatting.WHITE + actorName;
        if (!isReplayLocalDisguiseSource(profile.getName())) {
            display = GomodPeerDisguiseDisplay.rewriteTabName(profile, display);
        }
        return display;
    }

    private static boolean isReplayViewerNetworkInfo(NetworkPlayerInfo info, GameProfile profile, Minecraft mc) {
        if (info == null || profile == null || mc == null || mc.thePlayer == null) return false;
        UUID viewerId = mc.thePlayer.getUniqueID();
        if (viewerId == null) return false;
        NetworkPlayerInfo viewerInfo = mc.getNetHandler() == null
                ? null : mc.getNetHandler().getPlayerInfo(viewerId);
        if (viewerInfo != null) return info == viewerInfo;
        return viewerId.equals(profile.getId());
    }

    /** Keep the actual replay viewer row above every recorded actor, regardless of disguised display names. */
    public static List<NetworkPlayerInfo> prioritizeReplayViewerTabRow(List<NetworkPlayerInfo> rows) {
        if (rows == null || rows.size() < 2) return rows;
        Minecraft mc = Minecraft.getMinecraft();
        if (!GameContext.isHypixelReplayWatching(mc) || mc.thePlayer == null) return rows;
        UUID viewerId = mc.thePlayer.getUniqueID();
        if (viewerId == null) return rows;
        NetworkPlayerInfo viewerInfo = mc.getNetHandler() == null
                ? null : mc.getNetHandler().getPlayerInfo(viewerId);
        int viewerIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            NetworkPlayerInfo info = rows.get(i);
            GameProfile profile = info == null ? null : info.getGameProfile();
            if (viewerInfo != null ? info == viewerInfo : profile != null && viewerId.equals(profile.getId())) {
                viewerIndex = i;
                break;
            }
        }
        if (viewerIndex <= 0) return rows;
        List<NetworkPlayerInfo> ordered = new ArrayList<>(rows.size());
        ordered.add(rows.get(viewerIndex));
        for (int i = 0; i < rows.size(); i++) {
            if (i != viewerIndex) ordered.add(rows.get(i));
        }
        return ordered;
    }

    private static String rewriteReplayTabRowForRender(String input, String baseUsername, boolean localViewer) {
        if (input == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (!GameContext.isHypixelReplayWatching(mc)) return input;
        if (localViewer) return buildReplayViewerName(baseUsername);
        String viewer = rewriteReplayViewerTabLineIfApplicable(input, baseUsername);
        if (viewer != null) return viewer;
        return rewriteReplayActorName(input, baseUsername);
    }

    /** Post-Lunar player-name label normalization for Hypixel replay actors. */
    public static String rewriteReplayNametagForRender(String input, String baseUsername, boolean localViewer) {
        if (input == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (!GameContext.isHypixelReplayWatching(mc)) return null;
        String sourceName = normalizeReplayProfileName(baseUsername);

        if (localViewer) {
            String viewer = rewriteReplayViewerLabelForRender(input, sourceName);
            if (viewer != null) return viewer;
        }
        if (!isNameLabelForDebug(input, sourceName)
                && !(isReplayLocalDisguiseSource(sourceName) && isNameLabelForDebug(input, fastTargetNameText))) {
            return input;
        }
        if (localViewer) return buildReplayViewerName(baseUsername);
        return rewriteReplayActorName(input, sourceName);
    }

    public static String rewriteReplayViewerLabelForRender(String input, String username) {
        if (input == null || !containsReplayViewerTag(input)) return null;
        return rewriteReplayViewerTabLineIfApplicable(input, username);
    }

    private static String replayDisplayUsername(String username) {
        username = normalizeReplayProfileName(username);
        String target = fastTargetNameText;
        if (fastEnabledFlag && shouldApplyTabDisplayOverride() && target != null && !target.isEmpty()
                && (isReplayLocalDisguiseSource(username) || target.equalsIgnoreCase(username))) return target;
        return username;
    }

    private static String buildReplayViewerName(String username) {
        return EnumChatFormatting.GRAY + "[Viewer] " + EnumChatFormatting.GRAY + replayDisplayUsername(username);
    }

    private static String rewriteReplayActorName(String input, String username) {
        username = normalizeReplayProfileName(username);
        String alias = replayDisplayUsername(username);
        if (alias != null && username != null && fastEnabledFlag
                && (isReplayLocalDisguiseSource(username) || alias.equalsIgnoreCase(fastTargetNameText))) {
            String withoutGuild = KillCounter.stripGuildTagFromDisguiseDisplay(input);
            String rewritten = LunarDisguiseDisplayCompatibility.replaceHypixelRankedName(
                    withoutGuild, username, EnumChatFormatting.WHITE + alias);
            if (rewritten.equals(withoutGuild) && fastTargetNameText != null) {
                rewritten = LunarDisguiseDisplayCompatibility.replaceHypixelRankedName(
                        withoutGuild, fastTargetNameText, EnumChatFormatting.WHITE + alias);
            }
            input = rewritten;
        }
        return forceReplayTextWhite(input);
    }

    private static Object replayFontWorld;
    private static long replayFontContextCheckedAt;
    private static boolean replayFontContext;

    private static boolean isReplayFontContext(Minecraft mc) {
        Object world = mc == null ? null : mc.theWorld;
        long now = System.nanoTime();
        if (world != replayFontWorld || now - replayFontContextCheckedAt >= 50_000_000L) {
            replayFontWorld = world;
            replayFontContextCheckedAt = now;
            replayFontContext = GameContext.isHypixelReplayWatching(mc);
        }
        return replayFontContext;
    }

    /** Cached replay gate for entity skin/model lookups that run for many players each rendered frame. */
    public static boolean isHypixelReplayContextCached(Minecraft mc) {
        return isReplayFontContext(mc);
    }

    /** Runs before ordinary rank/guild rewriting and its text-only caches, for both width and draw. */
    public static String rewriteReplayPlayerFontText(String input) {
        if (input == null || input.isEmpty()) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (!isReplayFontContext(mc)) return null;
        EntityPlayer owner = LunarDisguiseDisplayCompatibility.currentNametagFontPlayer();
        String visible = stripFormattingCodes(input).trim();
        if (owner != null) {
            String username = normalizeReplayProfileName(
                    owner.getGameProfile() == null ? owner.getName() : owner.getGameProfile().getName());
            if (visible.equalsIgnoreCase("[Viewer]")) return forceReplayTextColor(input, '7');
            boolean nameFragment = isNameLabelForDebug(input, username)
                    || (isReplayLocalDisguiseSource(username) && isNameLabelForDebug(input, fastTargetNameText));
            if (!nameFragment) return input; // Health/level/logo rows keep their own styles.
            if (owner == mc.thePlayer) {
                if (containsReplayViewerTag(input)) return buildReplayViewerName(username);
                String alias = replayDisplayUsername(username);
                return forceReplayTextColor(RemoteDisguiseManager.replaceNameInFormatted(input,
                        normalizeReplayProfileName(username), alias), '7');
            }
            return rewriteReplayActorName(input, username);
        }
        NetworkPlayerInfo row = KillCounter.currentTabOverlayRow();
        if (row != null && !KillCounter.isChatFontRenderActive()) {
            GameProfile profile = row instanceof NetworkPlayerInfoAccessor
                    ? ((NetworkPlayerInfoAccessor) row).gomod$getGameProfile() : row.getGameProfile();
            if (profile != null && (isNameLabelForDebug(input, profile.getName())
                    || (isReplayLocalDisguiseSource(profile.getName()) && isNameLabelForDebug(input, fastTargetNameText)))) {
                return rewriteReplayTabRowForRender(input, row);
            }
        }
        if (KillCounter.isChatFontRenderActive() || fastSidebarRenderActive) return null;
        // Full labels can also arrive from the vanilla/custom replay stack. Reject sentences and chat speakers.
        String name = isNameLabelForDebug(input, fastRealNameText) ? fastRealNameText
                : isNameLabelForDebug(input, fastTargetNameText) ? fastTargetNameText : null;
        if (name == null || name.isEmpty() || indexOfIgnoreCaseStandalone(visible, name, 0) < 0) return null;
        StringBuilder remainder = new StringBuilder(visible.length());
        for (int i = 0; i < visible.length(); i++) {
            if (visible.charAt(i) == '[') {
                int close = visible.indexOf(']', i + 1);
                if (close >= 0) { i = close; continue; }
            }
            remainder.append(visible.charAt(i));
        }
        String labelRemainder = remainder.toString().trim();
        if (!labelRemainder.equalsIgnoreCase(name)) return null;
        if (containsReplayViewerTag(input)) return buildReplayViewerName(name);
        return rewriteReplayActorName(input, name);
    }

    public static boolean isReplayLocalDisguiseSource(String username) {
        String localName = fastRealNameText;
        String normalized = normalizeReplayProfileName(username);
        return !normalized.isEmpty() && localName != null && !localName.isEmpty()
                && fastEnabledFlag && shouldApplyTabDisplayOverride()
                && normalized.equalsIgnoreCase(normalizeReplayProfileName(localName));
    }

    /** Replay profile names can contain formatting controls (for example {@code OnSeb§r}); compare the visible IGN. */
    public static String normalizeReplayProfileName(String username) {
        if (username == null || username.isEmpty()) return "";
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(username);
        return plain == null ? username.trim() : plain.trim();
    }

    private static boolean containsReplayViewerTag(String input) {
        return input != null && indexOfIgnoreCase(stripFormattingCodes(input), "[viewer]") >= 0;
    }

    private static boolean isLocalReplayProfile(String username, Minecraft mc) {
        if (username == null || username.isEmpty() || mc == null) return false;
        String localName = mc.thePlayer != null && mc.thePlayer.getGameProfile() != null
                ? mc.thePlayer.getGameProfile().getName() : null;
        if ((localName == null || localName.isEmpty()) && mc.getSession() != null) {
            localName = mc.getSession().getUsername();
        }
        return localName != null && username.equalsIgnoreCase(localName);
    }

    /** Recolor legacy text without disturbing bold/italic/underline/strikethrough/obfuscation styles. */
    public static String forceReplayTextWhite(String input) {
        return forceReplayTextColor(input, 'f');
    }

    private static String forceReplayTextColor(String input, char color) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder output = null;
        for (int i = 0; i + 1 < input.length(); i++) {
            if (input.charAt(i) != '\u00a7') continue;
            char code = Character.toLowerCase(input.charAt(i + 1));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') {
                if (output == null) output = new StringBuilder(input);
                output.setCharAt(i + 1, color);
            }
            i++;
        }
        String result = output == null ? input : output.toString();
        return result.length() >= 2 && result.charAt(0) == '\u00a7' && result.charAt(1) == color
                ? result : "\u00a7" + color + result;
    }

    public static synchronized String buildNametagDisplayName(String originalName, String baseUsername) {
        String rewritten = buildNametagDisplayNameInternal(originalName, baseUsername);
        if (ScoreboardTeamDisguiseDebugCapture.isActive()) {
            ScoreboardTeamDisguiseDebugCapture.recordNametag(Minecraft.getMinecraft(), originalName, rewritten, baseUsername);
        }
        if (LocalDisguiseDebugCapture.isActive() && isNameLabelForDebug(originalName, baseUsername)) {
            Minecraft mc = Minecraft.getMinecraft();
            String serverColor = mc == null || mc.thePlayer == null ? null
                    : GameContext.getBlitzDisguiseNametagNameColor(mc, mc.thePlayer, originalName);
            LocalDisguiseDebugCapture.record("nametag", "local-player",
                    "enabled=" + isEnabled() + " override=" + shouldApplyTabDisplayOverride()
                            + " source=" + baseUsername + " real=" + realName + " target=" + targetName
                            + " serverNameColor=" + serverColor + " suppressDecorations="
                            + GameContext.shouldSuppressDisguiseNametagDecorations(mc)
                            + " input=" + originalName + " output=" + rewritten
                            + " game=" + GameContext.explainDetection(mc));
        }
        return rewritten;
    }

    /** Excludes Lunar's level/health rows from the opt-in self nametag trace. */
    public static boolean isNameLabelForDebug(String formattedText, String sourceName) {
        sourceName = normalizeReplayProfileName(sourceName);
        if (formattedText == null || formattedText.isEmpty() || sourceName.isEmpty()) {
            return false;
        }
        String visible = stripFormattingCodes(formattedText);
        if (indexOfIgnoreCaseStandalone(visible, sourceName, 0) >= 0) {
            return true;
        }
        String placeholderSuffix = "XXXX" + sourceName;
        return visible.length() >= placeholderSuffix.length()
                && visible.regionMatches(true, visible.length() - placeholderSuffix.length(),
                placeholderSuffix, 0, placeholderSuffix.length());
    }

    private static String buildNametagDisplayNameInternal(String originalName, String baseUsername) {
        if (BlitzChatNameDisplay.shouldPreserveRenderedPlayerText(originalName)) return originalName;
        Minecraft mc = Minecraft.getMinecraft();
        boolean replay = GameContext.isHypixelReplayWatching(mc);
        if (replay) return rewriteReplayNametagForRender(originalName, baseUsername,
                isLocalReplayProfile(baseUsername, mc));
        if (!shouldApplyTabDisplayOverride()) {
            return replay ? forceReplayTextWhite(originalName) : originalName;
        }
        if (!isOnHypixelServer(mc)) {
            String rewritten = rewriteNonHypixelNameOnly(originalName);
            return replay ? forceReplayTextWhite(rewritten) : rewritten;
        }
        String sourceName = pickLocalDisguiseNameKeyInVisibleText(originalName);
        String sourceWithoutGuild = KillCounter.stripGuildTagFromDisguiseDisplay(originalName);
        String replacement;
        if (GameContext.shouldSuppressDisguiseNametagDecorations(mc)) {
            String targetDisplay = getTargetTabChatDisplayNameForRendering();
            if (targetDisplay == null || targetDisplay.isEmpty()) {
                targetDisplay = targetName == null ? "" : targetName;
            }
            if (targetDisplay.isEmpty()) {
                return originalName;
            }
            String blitzNameColor = GameContext.getBlitzDisguiseNametagNameColor(
                    mc, mc.thePlayer, originalName);
            if (blitzNameColor != null) {
                String plainTarget = stripFormattingCodes(
                        KillCounter.stripGuildTagFromDisguiseDisplay(targetDisplay));
                replacement = EnumChatFormatting.RESET + blitzNameColor + plainTarget + EnumChatFormatting.RESET;
            } else {
                replacement = EnumChatFormatting.RESET
                        + KillCounter.stripGuildTagFromDisguiseDisplay(targetDisplay)
                        + EnumChatFormatting.RESET;
            }
        } else {
            replacement = formatCosmeticChatRankAndDisplayName();
            if (replacement != null) {
                replacement += buildGuildTagSuffix();
            }
        }
        String rewritten = LunarDisguiseDisplayCompatibility.replaceHypixelRankedName(sourceWithoutGuild,
                sourceName, replacement);
        if (rewritten.equals(sourceWithoutGuild) && GameContext.shouldSuppressDisguiseNametagDecorations(mc)) {
            rewritten = LunarDisguiseDisplayCompatibility.replaceObfuscatedHypixelName(sourceWithoutGuild,
                    sourceName, replacement);
        }
        return replay ? forceReplayTextWhite(rewritten) : rewritten;
    }

    private static String rewriteTabDisplayText(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (BlitzChatNameDisplay.shouldPreserveRenderedPlayerText(input)) return input;
        Minecraft mc = Minecraft.getMinecraft();
        // Replay TAB: [Viewer] rows must stay §7 with no rank prefix (works even when disguise is off).
        if (GameContext.isHypixelReplayWatching(mc)) {
            String replayViewer = rewriteReplayViewerTabLineIfApplicable(input, null);
            if (replayViewer != null) {
                return replayViewer;
            }
        }
        if (!isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }
        if (!isOnHypixelServer(mc)) {
            return rewriteNonHypixelNameOnly(input);
        }
        // isHypixelLobby misses some hub/limbo transitions (sidebar flicker). Still inject disguise rank on TAB in
        // main lobbies / generic Hypixel when not in a live Blitz match. Pregame, cages, and post-game (green names /
        // GAME OVER) keep Hypixel's presentation — no injected [MVP+] / disguise rank.
        // While teleporting, the scoreboard can blank for a frame: keep suppress so real rank does not flash.
        boolean blitzTabSuppressDisguiseRank = GameContext.isBlitzTabDisguiseRankSuppressed(mc);
        boolean inMainLobby = GameContext.isHypixelLobby(mc)
                || (isOnHypixelServer(mc) && !GameContext.isBlitzGameActive(mc));
        boolean allowPrefix = !blitzTabSuppressDisguiseRank && (GameContext.isHypixelLobby(mc)
                || (isOnHypixelServer(mc) && !GameContext.isBlitzGameActive(mc)));
        boolean suppressPrefix = !allowPrefix;
        boolean defaultDisguiseTarget = rank == null || rank.isEmpty() || "Default".equals(rank);
        // Main Hypixel lobbies: §7 for non-ranked disguise. Blitz hub queue keeps §f like the server; pregame/cage/post-game keep server TAB colors.
        // Only preserve server formatting during actual Blitz pregame/cage/post-game.
        // If we are already in the main lobby, don't keep the held-back (§f) name color.
        boolean preserveServerFormattingWithoutRank = !defaultDisguiseTarget
                || GameContext.isBlitzHypixelHubQueueSidebar(mc)
                || (blitzTabSuppressDisguiseRank && !inMainLobby);
        if (suppressPrefix) {
            String nameKey = pickLocalDisguiseNameKeyInVisibleText(input);
            String forcedBlitzColor = rewriteSuppressedBlitzSelfTabLine(input, nameKey, getTargetTabChatDisplayNameForRendering());
            if (!input.equals(forcedBlitzColor)) {
                return forcedBlitzColor;
            }
            String preserved = replaceVisibleNameOnlyKeepingCurrentFormatting(input, nameKey, getTargetTabChatDisplayNameForRendering());
            if (!input.equals(preserved)) {
                return preserved;
            }
            return replaceStandaloneIgnOccurrences(input, nameKey, getTargetTabChatDisplayNameForRendering());
        }
        String rewritten = replaceRankAndNameSegment(
                input,
                pickLocalDisguiseNameKeyInVisibleText(input),
                false,
                false,
                false,
                preserveServerFormattingWithoutRank,
                !suppressPrefix,
                !suppressPrefix,
                buildGuildTagSuffix()
        );
        if (!input.equals(rewritten)) {
            return rewritten;
        }
        return replaceStandaloneIgnOccurrences(input, pickLocalDisguiseNameKeyInVisibleText(input),
                getTargetTabChatDisplayNameForRendering());
    }

    private static String replaceVisibleNameOnlyKeepingCurrentFormatting(String input, String originalName, String replacementName) {
        if (input == null || input.isEmpty() || originalName == null || originalName.isEmpty()
                || replacementName == null || replacementName.isEmpty()) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int nameIndex = indexOfIgnoreCaseStandalone(visible, originalName, 0);
        if (nameIndex < 0) {
            return input;
        }
        int formattedStart = spanData.visibleToFormattedStart[nameIndex];
        int formattedEnd = spanData.visibleToFormattedEnd[nameIndex + originalName.length() - 1];
        String originalNameFormatting = getActiveFormattingBefore(input, formattedStart);
        String replacement = replacementName.indexOf('\u00a7') >= 0
                ? replacementName
                : (originalNameFormatting == null ? "" : originalNameFormatting) + replacementName;
        return input.substring(0, formattedStart) + replacement + input.substring(formattedEnd);
    }

    private static String rewriteSuppressedBlitzSelfTabLine(String input, String originalName, String replacementName) {
        Minecraft mc = Minecraft.getMinecraft();
        String forcedColor = getSuppressedBlitzSelfTabNameColor(mc);
        if (forcedColor == null) {
            // Once the live match starts, preserve the server's current color. Hypixel can keep the local
            // TAB row red briefly after cages; the phase flag can turn active before the server sends §a.
            // Still replace the masked source IGN so AntiShuffle/Hypixel's XXXX placeholder doesn't hide it.
            String obfuscated = LunarDisguiseDisplayCompatibility.replaceObfuscatedHypixelName(
                    input, originalName, replacementName);
            if (!input.equals(obfuscated)) {
                return obfuscated;
            }
        }
        return rewriteSuppressedBlitzFlatColorLine(input, originalName, replacementName, forcedColor);
    }

    /**
     * Forces a flat Hypixel Blitz name tint (§f / §c / §9) on one standalone name segment — TAB, join/quit, etc.
     */
    private static String rewriteSuppressedBlitzFlatColorLine(String input, String originalName, String replacementName,
            String forcedColor) {
        if (input == null || input.isEmpty() || originalName == null || originalName.isEmpty()
                || replacementName == null || replacementName.isEmpty()) {
            return input;
        }
        if (forcedColor == null || forcedColor.isEmpty()) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int nameIndex = indexOfIgnoreCaseStandalone(visible, originalName, 0);
        if (nameIndex < 0) {
            String obfuscatedReplacement = EnumChatFormatting.RESET.toString() + forcedColor
                    + replacementName + EnumChatFormatting.RESET;
            return LunarDisguiseDisplayCompatibility.replaceObfuscatedHypixelName(
                    input, originalName, obfuscatedReplacement);
        }
        int formattedStart = spanData.visibleToFormattedStart[nameIndex];
        int formattedEnd = spanData.visibleToFormattedEnd[nameIndex + originalName.length() - 1];
        // Lobby rank bracket (e.g. [MVP+]) can linger on join/TAB lines; strip it but keep [DIST n] game tags.
        if (hypixelRankBracketImmediatelyBefore(visible, nameIndex)) {
            int chainStart = findLeftmostBracketPrefixChainStart(visible, nameIndex);
            if (chainStart >= 0) {
                formattedStart = Math.min(formattedStart, spanData.visibleToFormattedStart[chainStart]);
            }
        }
        // Drop rank/name color codes glued to the IGN so §b MVP+ aqua does not bleed through a forced §f/§c.
        while (formattedStart >= 2 && input.charAt(formattedStart - 2) == '\u00A7') {
            formattedStart -= 2;
        }
        String restoreFormatting = getFormattingForContinuation(input, formattedEnd);
        String mergedFormatting = EnumChatFormatting.RESET.toString() + forcedColor;
        return input.substring(0, formattedStart)
                + mergedFormatting + replacementName
                + restoreFormatting
                + input.substring(formattedEnd);
    }

    /**
     * Applies the local disguise's Hypixel pregame TAB rule to a peer row: remove a recognized Hypixel rank prefix
     * and rename only the source IGN, preserving the row's existing formatting and suffixes.
     */
    public static String rewriteSuppressedHypixelPregamePeerTabLine(String input, String originalName, String replacementName) {
        if (input == null || input.isEmpty() || originalName == null || originalName.isEmpty()
                || replacementName == null || replacementName.isEmpty()) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int nameIndex = indexOfIgnoreCaseStandalone(visible, originalName, 0);
        if (nameIndex < 0) {
            return input;
        }

        int replaceStart = nameIndex;
        if (hypixelRankBracketImmediatelyBefore(visible, nameIndex)) {
            int rankStart = findConsumableHypixelPrefixChainStartBeforeName(visible, nameIndex);
            if (rankStart >= 0) {
                replaceStart = rankStart;
            }
        }
        return replaceVisibleRangePreservingFormatting(input, spanData, replaceStart,
                nameIndex + originalName.length(), replacementName, false);
    }

    private static String getSuppressedBlitzSelfTabNameColor(Minecraft mc) {
        if (mc == null || !isOnHypixelServer(mc)) {
            return null;
        }
        // Keep the disguised local row green through GAME OVER, matching Hypixel's local-player tint.
        if (GameContext.isBlitzGameOverSidebar(mc)) {
            return traceSuppressedBlitzSelfTabNameColor(mc, EnumChatFormatting.GREEN.toString(), "game-over-green", false);
        }
        boolean eliminated = isLocalDisguisePlayerEliminated(mc);
        if (eliminated) {
            return traceSuppressedBlitzSelfTabNameColor(mc, EnumChatFormatting.BLUE.toString(), "eliminated-spectator-blue", true);
        }
        // In the live match, keep the row's current server formatting. Hypixel may set the live-match
        // phase before it changes the local player's TAB color from red to green.
        // All Blitz pregame (hub queue and match-map waiting): plain white on TAB names.
        if (GameContext.isBlitzPregameLobby(mc)) {
            return traceSuppressedBlitzSelfTabNameColor(mc, EnumChatFormatting.WHITE.toString(), "pregame-white", false);
        }
        return traceSuppressedBlitzSelfTabNameColor(mc, null, "server-native", false);
    }

    private static String traceSuppressedBlitzSelfTabNameColor(Minecraft mc, String result,
                                                                String decision, boolean eliminated) {
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("tab-name-color", "local-player",
                    "decision=" + decision + " result=" + result
                            + " eliminated=" + eliminated
                            + " gameOver=" + GameContext.isBlitzGameOverSidebar(mc)
                            + " blitzActive=" + GameContext.isBlitzGameActive(mc)
                            + " context=" + GameContext.explainDetection(mc));
        }
        return result;
    }

    /**
     * Join/quit broadcast lines: match-map waiting uses §c like Hypixel; hub queue pregame stays §f.
     */
    private static String getSuppressedBlitzJoinLeaveAnnouncementColor(Minecraft mc) {
        if (mc == null || !isOnHypixelServer(mc)) {
            return null;
        }
        if (GameContext.isBlitzGameOverSidebar(mc)) {
            // During the first frame of the next queue, Hypixel can still expose the
            // previous GAME OVER sidebar while already sending the new "has joined"
            // announcement. Treat that announcement as match-map waiting so the
            // disguise target's rank color cannot leak into the usual light-red line.
            if (GameContext.isBlitzHypixelHubQueueSidebar(mc)) {
                return EnumChatFormatting.WHITE.toString();
            }
            return EnumChatFormatting.RED.toString();
        }
        if (GameContext.isBlitzCagePhaseSidebar(mc) || GameContext.isBlitzGameActive(mc)) {
            return EnumChatFormatting.RED.toString();
        }
        if (GameContext.isBlitzPregameLobby(mc) && !GameContext.isBlitzHypixelHubQueueSidebar(mc)) {
            return EnumChatFormatting.RED.toString();
        }
        if (GameContext.isBlitzPregameLobby(mc)) {
            return EnumChatFormatting.WHITE.toString();
        }
        return null;
    }

    /**
     * Cheap prefilter for the global FontRenderer hook. Most on-screen strings are unrelated to disguise sidebar
     * stats, so avoid scoreboard/objective work unless the raw text at least resembles a supported stat label.
     */
    private static boolean mightBeStandaloneSidebarStatLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.indexOf(':') < 0 && text.indexOf('\uFF1A') < 0 && text.indexOf('\uFE55') < 0 && text.indexOf('\u2236') < 0) {
            return false;
        }
        return containsIgnoreCase(text, "kills")
                || containsIgnoreCase(text, "wins")
                || containsIgnoreCase(text, "coins")
                || containsIgnoreCase(text, "rank")
                || containsIgnoreCase(text, "achievements")
                || containsIgnoreCase(text, "hypixel level")
                || containsIgnoreCase(text, "level");
    }

    /** Limit phase-sensitive stat handling to labels, excluding chat speakers and name-bearing text. */
    private static boolean isStandaloneSidebarStatText(String text) {
        if (!mightBeStandaloneSidebarStatLine(text)) return false;
        SidebarStatMatch match = detectSidebarStatMatch(text);
        if (match == null) return false;
        String visible = buildVisibleMapping(text).visibleText;
        for (int i = 0; i < match.labelStartVisible; i++) {
            if (Character.isLetterOrDigit(visible.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Replay spectators show as {@code [Viewer] Name} in TAB; strip real rank brackets and force §7.
     */
    private static String rewriteReplayViewerTabLineIfApplicable(String input, String baseUsername) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int vi = indexOfIgnoreCase(visible, "[viewer]");
        if (vi < 0) {
            return null;
        }
        int close = visible.indexOf(']', vi);
        if (close < 0 || close >= visible.length()) {
            return null;
        }
        String username = baseUsername;
        if (username == null || username.trim().isEmpty()) {
            StringBuilder name = new StringBuilder();
            String suffix = visible.substring(close + 1);
            for (int i = 0; i < suffix.length(); i++) {
                char c = suffix.charAt(i);
                if (c == '[') {
                    int bracketEnd = suffix.indexOf(']', i + 1);
                    if (bracketEnd >= 0) {
                        i = bracketEnd;
                        continue;
                    }
                }
                name.append(c);
            }
            username = name.toString().trim();
        }
        if (username == null || username.trim().isEmpty()) return null;
        return buildReplayViewerName(username.trim());
    }

    private static GameProfile buildTargetProfile(ResolvedTarget resolvedTarget) {
        GameProfile profile = new GameProfile(resolvedTarget.uuid, resolvedTarget.name);
        if (resolvedTarget.texturesValue != null && !resolvedTarget.texturesValue.isEmpty()) {
            if (resolvedTarget.texturesSignature != null && !resolvedTarget.texturesSignature.isEmpty()) {
                profile.getProperties().put("textures", new Property("textures", resolvedTarget.texturesValue, resolvedTarget.texturesSignature));
            } else {
                profile.getProperties().put("textures", new Property("textures", resolvedTarget.texturesValue));
            }
        }
        return profile;
    }

    private static LocalIdentity captureLocalIdentity() throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            throw new Exception("Minecraft is not available.");
        }

        if (mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
            String name = mc.thePlayer.getGameProfile().getName();
            UUID uuid = mc.thePlayer.getUniqueID();
            if (name != null && !name.isEmpty() && uuid != null) {
                return new LocalIdentity(name, uuid);
            }
        }

        throw new Exception("Could not determine your local player identity.");
    }

    public static synchronized boolean isNickDisguiseActive() {
        return nickDisguiseActive && enabled;
    }

    private static ResolvedTarget buildNickResolvedTarget(String nick, NickSkinMode skinMode, String skinRef, boolean allowTakenMojangName) throws Exception {
        String raw = nick == null ? "" : nick.trim();
        String name = stripToPlainNickForOfflineUuid(raw);
        if (name.length() < 3) {
            throw new Exception("Nick must be at least 3 characters (like a Minecraft username).");
        }
        if (!allowTakenMojangName) {
            verifyNickNotRegisteredWithMojang(name);
        }
        NickSkinMode mode = skinMode == null ? NickSkinMode.STEVE : skinMode;
        ResolvedTarget t = new ResolvedTarget();
        t.name = name;
        t.offlineNickDisguise = true;
        t.tabChatDisplayNick = translateAmpersandToSectionSigns(raw);
        if (t.tabChatDisplayNick == null || t.tabChatDisplayNick.isEmpty()) {
            t.tabChatDisplayNick = name;
        }
        t.uuid = uuidFromOfflineNick(name);
        t.uuidUndashed = t.uuid.toString().replace("-", "");
        t.skinType = "default";
        t.skinUrl = null;
        t.capeUrl = null;
        t.texturesValue = null;
        t.texturesSignature = null;
        t.externalCapeSources = Collections.emptyList();
        t.localSkinFilePath = null;
        switch (mode) {
            case STEVE:
                break;
            case PLAYER_SKIN:
                if (skinRef == null || skinRef.trim().isEmpty()) {
                    throw new Exception("Enter a real player's name to copy their skin.");
                }
                ResolvedTarget donor = resolveTarget(skinRef.trim());
                copyDonorSkinOnly(t, donor);
                mergeDonorCapeSourcesForNick(t, donor);
                break;
            case SKIN_FILE:
                if (skinRef == null || skinRef.trim().isEmpty()) {
                    throw new Exception("Choose a skin PNG file.");
                }
                File sf = new File(skinRef.trim());
                if (!sf.isFile()) {
                    throw new Exception("Skin file not found.");
                }
                t.localSkinFilePath = sf.getAbsolutePath();
                break;
            default:
                break;
        }
        return t;
    }

    private static String sanitizeDisguiseNick(String raw) {
        return stripToPlainNickForOfflineUuid(raw);
    }

    private static void verifyNickNotRegisteredWithMojang(String name) throws Exception {
        JsonObject profile = fetchJson("https://api.mojang.com/users/profiles/minecraft/" + name, true);
        if (profile != null && profile.has("id")) {
            String id = profile.get("id").getAsString();
            String official = profile.has("name") && !profile.get("name").isJsonNull()
                    ? profile.get("name").getAsString()
                    : name;
            throw new NickIsMojangAccountException(official, id);
        }
    }

    private static UUID uuidFromOfflineNick(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static void copyDonorSkinOnly(ResolvedTarget to, ResolvedTarget donor) {
        to.skinUrl = donor.skinUrl;
        to.skinType = donor.skinType != null && !donor.skinType.isEmpty() ? donor.skinType : "default";
        to.texturesValue = donor.texturesValue;
        to.texturesSignature = donor.texturesSignature;
    }

    /** Nick + player skin: treat the skin donor's Mojang / capes.dev capes as owned in the picker. */
    private static void mergeDonorCapeSourcesForNick(ResolvedTarget nick, ResolvedTarget donor) throws Exception {
        if (nick == null || donor == null) {
            return;
        }
        LinkedHashMap<String, ExternalCapeSource> merged = new LinkedHashMap<String, ExternalCapeSource>();
        if (donor.capeUrl != null && !donor.capeUrl.isEmpty()) {
            MinecraftCapeCatalog.ResolvedCapeMeta meta = MinecraftCapeCatalog.resolveFromImageUrl(donor.capeUrl);
            String label = meta.label != null ? meta.label
                    : MinecraftCapeCatalog.labelForImageUrlOrDefault(donor.capeUrl, "Mojang Cape");
            String hash = meta.textureHash != null ? meta.textureHash
                    : MinecraftCapeCatalog.textureHashFromUrl(donor.capeUrl);
            String dedupe = hash != null ? "minecraft|" + hash : "minecraft|" + donor.capeUrl;
            merged.put(dedupe, new ExternalCapeSource("minecraft", label, donor.capeUrl, hash));
        }
        List<ExternalCapeSource> donorSources = fetchExternalCapeSources(donor);
        if (donorSources != null) {
            for (ExternalCapeSource source : donorSources) {
                if (source == null || source.imageUrl == null || source.imageUrl.isEmpty()) {
                    continue;
                }
                String hash = source.textureHash != null
                        ? source.textureHash
                        : MinecraftCapeCatalog.textureHashFromUrl(source.imageUrl);
                String typeKey = source.sourceType != null ? source.sourceType.toLowerCase(Locale.ROOT) : "ext";
                String dedupe = hash != null ? typeKey + "|" + hash : typeKey + "|" + source.imageUrl;
                if (!merged.containsKey(dedupe)) {
                    merged.put(dedupe, source);
                }
            }
        }
        nick.externalCapeSources = new ArrayList<ExternalCapeSource>(merged.values());
    }

    private static ResourceLocation loadSkinFromLocalPngPath(String absolutePath) {
        try {
            File f = new File(absolutePath);
            if (!f.isFile()) {
                return null;
            }
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                return null;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return null;
            }
            ResourceLocation rl = new ResourceLocation("gomod123", "disguise_localskin/" + Integer.toHexString(absolutePath.hashCode()));
            try {
                mc.getTextureManager().deleteTexture(rl);
            } catch (Throwable ignored) {
            }
            mc.getTextureManager().loadTexture(rl, new DynamicTexture(img));
            return rl;
        } catch (Throwable t) {
            System.err.println("[LocalDisguise] Failed to load local skin PNG: " + t.getMessage());
            return null;
        }
    }

    private static ResolvedTarget resolveTarget(String playerName) throws Exception {
        JsonObject mojangProfile = fetchJson("https://api.mojang.com/users/profiles/minecraft/" + playerName, true);
        if (mojangProfile == null || !mojangProfile.has("id") || !mojangProfile.has("name")) {
            throw new Exception("Player not found: " + playerName);
        }

        ResolvedTarget target = new ResolvedTarget();
        target.name = mojangProfile.get("name").getAsString();
        target.uuidUndashed = mojangProfile.get("id").getAsString();
        target.uuid = toDashedUuid(target.uuidUndashed);

        JsonObject sessionProfile = fetchJson(me.napim.gomod.util.MojangProfileApi.profileJsonUrl(target.uuidUndashed), false);
        if (sessionProfile == null) {
            throw new Exception("Could not fetch session profile for " + target.name);
        }

        populateTextureMetadata(sessionProfile, target);
        return target;
    }

    private static ResolvedTarget resolveTarget(GameProfile profile) throws Exception {
        ResolvedTarget target = new ResolvedTarget();
        target.name = profile.getName();
        target.uuid = profile.getId();
        target.uuidUndashed = target.uuid.toString().replace("-", "");

        JsonArray profileProperties = new JsonArray();
        for (Property textureProperty : profile.getProperties().get("textures")) {
            JsonObject property = new JsonObject();
            property.addProperty("name", "textures");
            property.addProperty("value", textureProperty.getValue());
            if (textureProperty.hasSignature()) {
                property.addProperty("signature", textureProperty.getSignature());
            }
            profileProperties.add(property);
        }
        JsonObject sessionProfile = new JsonObject();
        sessionProfile.add("properties", profileProperties);
        populateTextureMetadata(sessionProfile, target);
        if (target.texturesValue == null || target.texturesValue.isEmpty()) {
            JsonObject fetchedSessionProfile = fetchJson(
                    me.napim.gomod.util.MojangProfileApi.profileJsonUrl(target.uuidUndashed), false);
            populateTextureMetadata(fetchedSessionProfile, target);
        }
        return target;
    }

    private static void populateTextureMetadata(JsonObject sessionProfile, ResolvedTarget target) {
        target.skinType = DefaultPlayerSkin.getSkinType(target.uuid);
        target.skinUrl = null;
        target.capeUrl = null;
        target.texturesValue = null;
        target.texturesSignature = null;

        if (!sessionProfile.has("properties")) {
            return;
        }

        JsonArray properties = sessionProfile.getAsJsonArray("properties");
        for (JsonElement element : properties) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject property = element.getAsJsonObject();
            if (!property.has("name") || !"textures".equals(property.get("name").getAsString()) || !property.has("value")) {
                continue;
            }

            target.texturesValue = property.get("value").getAsString();
            if (property.has("signature")) {
                target.texturesSignature = property.get("signature").getAsString();
            }
            break;
        }

        if (target.texturesValue == null || target.texturesValue.isEmpty()) {
            return;
        }

        String decoded = new String(Base64.getDecoder().decode(target.texturesValue), StandardCharsets.UTF_8);
        JsonObject texturesRoot = new JsonParser().parse(decoded).getAsJsonObject();
        if (!texturesRoot.has("textures") || !texturesRoot.get("textures").isJsonObject()) {
            return;
        }

        JsonObject textures = texturesRoot.getAsJsonObject("textures");
        if (textures.has("SKIN") && textures.get("SKIN").isJsonObject()) {
            JsonObject skin = textures.getAsJsonObject("SKIN");
            if (skin.has("url")) {
                target.skinUrl = skin.get("url").getAsString();
            }
            if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                JsonObject metadata = skin.getAsJsonObject("metadata");
                if (metadata.has("model")) {
                    target.skinType = metadata.get("model").getAsString();
                }
            }
        }

        if (textures.has("CAPE") && textures.get("CAPE").isJsonObject()) {
            JsonObject cape = textures.getAsJsonObject("CAPE");
            if (cape.has("url")) {
                target.capeUrl = cape.get("url").getAsString();
            }
        }
    }

    private static TextureData loadTextures(ResolvedTarget target) {
        TextureData textureData = new TextureData();
        textureData.skinType = target.skinType == null || target.skinType.isEmpty()
                ? DefaultPlayerSkin.getSkinType(target.uuid)
                : target.skinType;
        textureData.skinLocation = DefaultPlayerSkin.getDefaultSkin(target.uuid);
        textureData.capeLocation = null;
        textureData.hasCustomSkin = false;
        textureData.hasCustomCape = false;

        if (target.localSkinFilePath != null && !target.localSkinFilePath.isEmpty()) {
            ResourceLocation loadedLocal = loadSkinFromLocalPngPath(target.localSkinFilePath);
            if (loadedLocal != null) {
                textureData.skinLocation = loadedLocal;
                textureData.hasCustomSkin = true;
            }
        } else if (target.skinUrl != null && !target.skinUrl.isEmpty()) {
            ResourceLocation loadedSkin = loadSkinManagerTexture(target.skinUrl, textureData.skinType, MinecraftProfileTexture.Type.SKIN);
            if (loadedSkin != null) {
                textureData.skinLocation = loadedSkin;
                textureData.hasCustomSkin = true;
            }
        }

        LinkedHashMap<String, CapeOption> capeOptionMap = loadCapeOptionMap(target);
        textureData.capeOptions = ensureNoCapeOption(collectCapeOptions(target, capeOptionMap, false));
        if (!textureData.capeOptions.isEmpty()) {
            CapeOption initialCape = textureData.capeOptions.get(0);
            textureData.capeLocation = initialCape.location;
            textureData.hasCustomCape = initialCape.location != null;
        }

        return textureData;
    }

    private static void addBuiltinMojangStyleCapeOptions(LinkedHashMap<String, CapeOption> capeOptionMap) {
        if (capeOptionMap == null) {
            return;
        }
        for (MinecraftCapeCatalog.Entry entry : MinecraftCapeCatalog.OFFICIAL_MOJANG_CAPES) {
            if (entry == null || entry.textureId == null || entry.textureId.isEmpty()) {
                continue;
            }
            ResourceLocation rl = loadExternalCapeTexture(entry.textureUrl());
            if (rl == null) {
                continue;
            }
            String key = rl.toString();
            if (!capeOptionMap.containsKey(key)) {
                capeOptionMap.put(key, new CapeOption(key, entry.label, rl, entry.textureId));
            }
        }
    }

    private static LinkedHashMap<String, CapeOption> loadCapeOptionMap(ResolvedTarget target) {
        LinkedHashMap<String, CapeOption> capeOptionMap = new LinkedHashMap<>();
        java.util.HashSet<String> seenMojangHashes = new java.util.HashSet<String>();
        java.util.HashSet<String> seenThirdPartyKeys = new java.util.HashSet<String>();

        if (target.capeUrl != null && !target.capeUrl.isEmpty()) {
            ResourceLocation loadedCape = loadSkinManagerTexture(target.capeUrl, null, MinecraftProfileTexture.Type.CAPE);
            if (loadedCape != null) {
                String hash = MinecraftCapeCatalog.textureHashFromUrl(target.capeUrl);
                if (hash == null || hash.isEmpty()) {
                    hash = MinecraftCapeCatalog.lookupCachedTextureHashForImageUrl(target.capeUrl);
                }
                if (hash != null) {
                    seenMojangHashes.add(hash.toLowerCase(Locale.ROOT));
                }
                capeOptionMap.put(loadedCape.toString(),
                        new CapeOption(loadedCape.toString(), MinecraftCapeCatalog.MINECRAFT_NET_EQUIPPED_LABEL,
                                loadedCape, hash, target.capeUrl, "minecraft"));
            }
        }

        if (target.externalCapeSources != null) {
            for (ExternalCapeSource source : target.externalCapeSources) {
                if (source == null || source.imageUrl == null || source.imageUrl.isEmpty()) {
                    continue;
                }

                boolean thirdParty = MinecraftCapeCatalog.isThirdPartyAccountCapeSourceType(source.sourceType);
                String hash = source.textureHash;
                if (thirdParty) {
                    String extKey = source.sourceType.toLowerCase(Locale.ROOT) + "|"
                            + (hash != null && !hash.isEmpty() ? hash : source.imageUrl);
                    if (seenThirdPartyKeys.contains(extKey)) {
                        continue;
                    }
                    seenThirdPartyKeys.add(extKey);
                } else {
                    if (hash == null || hash.isEmpty()) {
                        hash = MinecraftCapeCatalog.textureHashFromUrl(source.imageUrl);
                    }
                    if (hash == null || hash.isEmpty()) {
                        hash = MinecraftCapeCatalog.lookupCachedTextureHashForImageUrl(source.imageUrl);
                    }
                    if (hash != null && !hash.isEmpty()
                            && seenMojangHashes.contains(hash.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }

                ResourceLocation loadedCape = loadExternalCapeTexture(source.imageUrl);
                if (loadedCape == null) {
                    continue;
                }

                String optionKey = thirdParty
                        ? buildThirdPartyCapeOptionKey(source.sourceType, hash, source.imageUrl)
                        : loadedCape.toString();
                if (capeOptionMap.containsKey(optionKey)) {
                    continue;
                }

                String label;
                if (MinecraftCapeCatalog.isPreservedExternalHistoryLabel(source.label)) {
                    label = source.label;
                } else if (!thirdParty && hash != null && !hash.isEmpty()) {
                    label = MinecraftCapeCatalog.labelForTextureHash(hash);
                } else {
                    label = null;
                }
                if (label == null || label.isEmpty()) {
                    if (!MinecraftCapeCatalog.isGenericMojangHistoryLabel(source.label)) {
                        label = source.label;
                    } else {
                        label = "Cape";
                    }
                }
                if (!thirdParty && hash != null && !hash.isEmpty()) {
                    seenMojangHashes.add(hash.toLowerCase(Locale.ROOT));
                }
                capeOptionMap.put(optionKey, new CapeOption(optionKey, label, loadedCape, hash, source.imageUrl, source.sourceType));
            }
        }

        return capeOptionMap;
    }

    private static TextureData loadTexturesOnClientThread(ResolvedTarget target) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            throw new Exception("Minecraft is not available.");
        }

        if (mc.isCallingFromMinecraftThread()) {
            return loadTextures(target);
        }

        final TextureData[] holder = new TextureData[1];
        final Throwable[] error = new Throwable[1];
        CountDownLatch latch = new CountDownLatch(1);

        mc.addScheduledTask(() -> {
            try {
                holder[0] = loadTextures(target);
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });

        if (!awaitClientThreadTask(latch, 20, TimeUnit.SECONDS)) {
            throw new Exception("Timed out while loading disguise textures.");
        }

        if (error[0] != null) {
            throw new Exception("Could not load disguise textures: " + error[0].getClass().getSimpleName());
        }

        return holder[0] == null ? new TextureData() : holder[0];
    }

    private static LiveTextureReferences captureLiveTextureReferencesOnClientThread(LocalIdentity localIdentity) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            throw new Exception("Minecraft is not available.");
        }

        if (mc.isCallingFromMinecraftThread()) {
            return captureLiveTextureReferences(localIdentity);
        }

        final LiveTextureReferences[] holder = new LiveTextureReferences[1];
        final Throwable[] error = new Throwable[1];
        CountDownLatch latch = new CountDownLatch(1);

        mc.addScheduledTask(() -> {
            try {
                holder[0] = captureLiveTextureReferences(localIdentity);
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });

        if (!awaitClientThreadTask(latch, 20, TimeUnit.SECONDS)) {
            throw new Exception("Timed out while capturing local skin references.");
        }

        if (error[0] != null) {
            throw new Exception("Could not capture local skin references: " + error[0].getClass().getSimpleName());
        }

        return holder[0] == null ? new LiveTextureReferences() : holder[0];
    }

    private static LiveTextureReferences captureLiveTextureReferences(LocalIdentity localIdentity) {
        LiveTextureReferences refs = new LiveTextureReferences();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return refs;
        }

        refs.usernameSkinLocation = localIdentity == null || localIdentity.name == null
                ? null
                : AbstractClientPlayer.getLocationSkin(localIdentity.name);

        if (mc.getNetHandler() == null) {
            return refs;
        }

        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(localIdentity.uuid);
        if (info != null) {
            refs.skinLocation = info.getLocationSkin();
            refs.capeLocation = info.getLocationCape();
        }

        return refs;
    }

    private static List<CapeOption> loadCapeOptionsOnClientThread(ResolvedTarget target) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            throw new Exception("Minecraft is not available.");
        }

        if (mc.isCallingFromMinecraftThread()) {
            return ensureNoCapeOption(collectCapeOptions(target, loadCapeOptionMap(target), false));
        }

        final List<CapeOption>[] holder = new List[1];
        final Throwable[] error = new Throwable[1];
        CountDownLatch latch = new CountDownLatch(1);

        mc.addScheduledTask(() -> {
            try {
                holder[0] = ensureNoCapeOption(collectCapeOptions(target, loadCapeOptionMap(target), false));
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });

        if (!awaitClientThreadTask(latch, 20, TimeUnit.SECONDS)) {
            throw new Exception("Timed out while loading external disguise capes.");
        }

        if (error[0] != null) {
            throw new Exception("Could not load external disguise capes: " + error[0].getClass().getSimpleName());
        }

        return holder[0] == null ? Collections.emptyList() : holder[0];
    }

    private static boolean awaitClientThreadTask(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        while (true) {
            if (latch.await(250L, TimeUnit.MILLISECONDS)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
        }
    }

    private static ResourceLocation loadSkinManagerTexture(String imageUrl, String model, MinecraftProfileTexture.Type type) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getSkinManager() == null || imageUrl == null || imageUrl.isEmpty()) {
                return null;
            }

            Map<String, String> metadata;
            if (type == MinecraftProfileTexture.Type.SKIN && model != null && !model.isEmpty()) {
                metadata = new HashMap<>();
                metadata.put("model", model);
            } else {
                metadata = Collections.emptyMap();
            }

            return mc.getSkinManager().loadSkin(new MinecraftProfileTexture(imageUrl, metadata), type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation loadExternalCapeTexture(String imageUrl) {
        return CapeTextureLoader.loadExternalCapeTexture(imageUrl);
    }

    private static List<CapeOption> collectCapeOptions(ResolvedTarget target,
            LinkedHashMap<String, CapeOption> preloadedOptions, boolean allowLiveCapeDetection) {
        LinkedHashMap<String, CapeOption> options = new LinkedHashMap<>();
        int detectedCapeIndex = 1;
        if (preloadedOptions != null && !preloadedOptions.isEmpty()) {
            options.putAll(preloadedOptions);
        }

        boolean hadExternalCapeSources = target != null && target.externalCapeSources != null
                && !target.externalCapeSources.isEmpty();
        if (!allowLiveCapeDetection || hadExternalCapeSources) {
            return new ArrayList<>(options.values());
        }

        boolean alreadyHasExternalOptions = !options.isEmpty();
        Minecraft mc = Minecraft.getMinecraft();
        if (!alreadyHasExternalOptions && mc != null && mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfoMap() != null) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }

                boolean uuidMatches = target != null && target.uuid != null && target.uuid.equals(info.getGameProfile().getId());
                String infoName = info.getGameProfile().getName();
                boolean nameMatches = target != null && target.name != null && infoName != null && target.name.equalsIgnoreCase(infoName);
                if (!uuidMatches && !nameMatches) {
                    continue;
                }

                ResourceLocation liveCape = info.getLocationCape();
                if (liveCape == null || !isLiveCapeTextureUsable(liveCape)) {
                    continue;
                }

                String key = liveCape.toString();
                if (!options.containsKey(key)) {
                    String hash = MinecraftCapeCatalog.textureHashFromResourceLocation(liveCape);
                    String label = MinecraftCapeCatalog.labelForResourceLocation(liveCape);
                    if (label == null || label.isEmpty()) {
                        label = "Detected Cape " + detectedCapeIndex;
                    }
                    options.put(key, new CapeOption(key, label, liveCape, hash, null));
                    detectedCapeIndex++;
                }
            }
        }

        if (!alreadyHasExternalOptions && mc != null && mc.theWorld != null && mc.theWorld.playerEntities != null) {
            for (EntityPlayer entityPlayer : mc.theWorld.playerEntities) {
                if (!(entityPlayer instanceof AbstractClientPlayer)) {
                    continue;
                }

                boolean uuidMatches = target != null && target.uuid != null && target.uuid.equals(entityPlayer.getUniqueID());
                String entityName = entityPlayer.getName();
                boolean nameMatches = target != null && target.name != null && entityName != null && target.name.equalsIgnoreCase(entityName);
                if (!uuidMatches && !nameMatches) {
                    continue;
                }

                ResourceLocation liveCape = ((AbstractClientPlayer) entityPlayer).getLocationCape();
                if (liveCape == null || !isLiveCapeTextureUsable(liveCape)) {
                    continue;
                }

                String key = liveCape.toString();
                if (!options.containsKey(key)) {
                    String hash = MinecraftCapeCatalog.textureHashFromResourceLocation(liveCape);
                    String label = MinecraftCapeCatalog.labelForResourceLocation(liveCape);
                    if (label == null || label.isEmpty()) {
                        label = "Detected Cape " + detectedCapeIndex;
                    }
                    options.put(key, new CapeOption(key, label, liveCape, hash, null));
                    detectedCapeIndex++;
                }
            }
        }

        return new ArrayList<>(options.values());
    }

    /** Skip tab/world cape rows when the texture is not downloaded yet (shows as a white square). */
    private static boolean isLiveCapeTextureUsable(ResourceLocation location) {
        if (location == null) {
            return false;
        }
        try {
            return PlayerCustomCapes.loadImageFromCapeTexture(location) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<CapeOption> collectCapeOptions(ResolvedTarget target, LinkedHashMap<String, CapeOption> preloadedOptions) {
        return collectCapeOptions(target, preloadedOptions, true);
    }

    private static List<CapeOption> ensureNoCapeOption(List<CapeOption> options) {
        List<CapeOption> safe = options == null ? new ArrayList<>() : new ArrayList<>(options);
        for (CapeOption option : safe) {
            if (option != null && NO_CAPE_OPTION_KEY.equals(option.key)) {
                return safe;
            }
        }
        safe.add(new CapeOption(NO_CAPE_OPTION_KEY, "No Cape", null, null));
        return safe;
    }

    private static List<ExternalCapeSource> fetchExternalCapeSources(ResolvedTarget target) {
        if (target == null) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, ExternalCapeSource> sources = new LinkedHashMap<>();
        if (target.uuidUndashed != null && !target.uuidUndashed.isEmpty()) {
            addExternalCapeHistorySources(sources, target, target.uuidUndashed);
            addExternalCurrentCapeSourcesFromIdentifier(sources, target, target.uuidUndashed);
        }
        if (target.name != null && !target.name.isEmpty()) {
            addExternalCapeHistorySources(sources, target, target.name);
            addExternalCurrentCapeSourcesFromIdentifier(sources, target, target.name);
        }
        ArrayList<ExternalCapeSource> ordered = new ArrayList<ExternalCapeSource>(sources.values());
        sortExternalCapeSourcesForPicker(ordered);
        return enrichExternalCapeSourcesOnBackground(ordered);
    }

    private static void sortExternalCapeSourcesForPicker(List<ExternalCapeSource> sources) {
        if (sources == null || sources.size() < 2) {
            return;
        }
        Collections.sort(sources, new java.util.Comparator<ExternalCapeSource>() {
            @Override
            public int compare(ExternalCapeSource a, ExternalCapeSource b) {
                int pa = externalCapeSourcePriority(a);
                int pb = externalCapeSourcePriority(b);
                if (pa != pb) {
                    return Integer.compare(pa, pb);
                }
                return 0;
            }
        });
    }

    private static int externalCapeSourcePriority(ExternalCapeSource source) {
        String type = source == null || source.sourceType == null ? "" : source.sourceType.toLowerCase(Locale.ROOT);
        if ("minecraft".equals(type)) {
            return 0;
        }
        if ("optifine".equals(type)) {
            return 1;
        }
        if ("minecraftcapes".equals(type)) {
            return 2;
        }
        return 3;
    }

    private static List<ExternalCapeSource> enrichExternalCapeSourcesOnBackground(List<ExternalCapeSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return sources;
        }
        ArrayList<ExternalCapeSource> out = new ArrayList<ExternalCapeSource>(sources.size());
        for (ExternalCapeSource source : sources) {
            out.add(enrichExternalCapeSourceOnBackground(source));
        }
        return out;
    }

    private static ExternalCapeSource enrichExternalCapeSourceOnBackground(ExternalCapeSource source) {
        if (source == null) {
            return null;
        }
        if (MinecraftCapeCatalog.isPreservedExternalHistoryLabel(source.label)
                || MinecraftCapeCatalog.isThirdPartyAccountCapeSourceType(source.sourceType)) {
            return source;
        }
        if (source.imageUrl == null || source.imageUrl.isEmpty()) {
            return source;
        }
        MinecraftCapeCatalog.ResolvedCapeMeta meta =
                MinecraftCapeCatalog.resolveCapeMetaFromUrlBlocking(source.imageUrl);
        String hash = source.textureHash;
        if (hash == null || hash.isEmpty()) {
            hash = meta.textureHash;
        }
        if (hash == null || hash.isEmpty()) {
            hash = MinecraftCapeCatalog.textureHashFromUrl(source.imageUrl);
        }
        if (hash == null || hash.isEmpty()) {
            hash = MinecraftCapeCatalog.lookupCachedTextureHashForImageUrl(source.imageUrl);
        }
        String label = source.label;
        if (meta.label != null && !meta.label.isEmpty()) {
            label = meta.label;
        } else if (hash != null && !hash.isEmpty()) {
            String catalogLabel = MinecraftCapeCatalog.labelForTextureHash(hash);
            if (catalogLabel != null && !catalogLabel.isEmpty()) {
                label = catalogLabel;
            }
        }
        return new ExternalCapeSource(source.sourceType, label, source.imageUrl, hash);
    }

    private static void scheduleDisguiseCapeRefreshIfStaleLocked() {
        if (!enabled || targetName == null || targetUuidDashed == null) {
            return;
        }
        ResolvedTarget target = new ResolvedTarget();
        target.name = targetName;
        target.uuid = targetUuidDashed;
        target.uuidUndashed = targetUuid;
        scheduleExternalCapeRefresh(target);
    }

    private static void scheduleExternalCapeRefresh(ResolvedTarget target) {
        if (target == null || target.name == null || target.uuid == null) {
            return;
        }

        final ResolvedTarget targetSnapshot = copyResolvedTargetForCapeRefresh(target);
        CompletableFuture.runAsync(() -> {
            try {
                List<ExternalCapeSource> sources = fetchExternalCapeSources(targetSnapshot);
                if (sources == null || sources.isEmpty()) {
                    return;
                }

                targetSnapshot.externalCapeSources = sources;
                List<CapeOption> loadedCapeOptions = loadCapeOptionsOnClientThread(targetSnapshot);
                if (loadedCapeOptions == null || loadedCapeOptions.isEmpty()) {
                    return;
                }

                synchronized (LocalDisguise.class) {
                    if (!matchesActiveTargetLocked(targetSnapshot)) {
                        return;
                    }

                    String selectedKey = getSelectedCapeKeyLocked();
                    List<CapeOption> merged = mergeCapeOptionsForPickerLocked(capeOptions, loadedCapeOptions);
                    UUID localUuid = resolveLocalAccountSessionUuidLocked();
                    if (localUuid != null && localUuid.equals(targetSnapshot.uuid)
                            && localSelfCapeOptions != null && !localSelfCapeOptions.isEmpty()) {
                        merged = mergeCapeOptionsForPickerLocked(merged, localSelfCapeOptions);
                    }
                    capeOptions = mergeCapeOptionsIntoSessionCacheLocked(targetSnapshot.uuid, merged);
                    invalidateDisguiseCapeGuiOptionsCacheLocked();
                    selectedCapeIndex = findCapeOptionIndexByKeyLocked(selectedKey);
                    if (selectedCapeIndex < 0) {
                        selectedCapeIndex = capeOptions.isEmpty() ? -1 : 0;
                    }
                    applySelectedCapeOptionLocked();
                }
                scheduleLiveOverrideApply();
            } catch (Throwable ignored) {
            }
        });
    }

    private static ResolvedTarget copyResolvedTargetForCapeRefresh(ResolvedTarget source) {
        ResolvedTarget copy = new ResolvedTarget();
        copy.name = source.name;
        copy.uuidUndashed = source.uuidUndashed;
        copy.uuid = source.uuid;
        copy.texturesValue = source.texturesValue;
        copy.texturesSignature = source.texturesSignature;
        copy.skinType = source.skinType;
        copy.skinUrl = source.skinUrl;
        copy.capeUrl = source.capeUrl;
        copy.externalCapeSources = Collections.emptyList();
        copy.localSkinFilePath = source.localSkinFilePath;
        copy.tabChatDisplayNick = source.tabChatDisplayNick;
        copy.offlineNickDisguise = source.offlineNickDisguise;
        return copy;
    }

    private static boolean matchesActiveTargetLocked(ResolvedTarget target) {
        return enabled
                && target != null
                && targetUuidDashed != null
                && target.uuid != null
                && targetUuidDashed.equals(target.uuid);
    }

    private static String getSelectedCapeKeyLocked() {
        if (capeOptions == null || capeOptions.isEmpty() || selectedCapeIndex < 0 || selectedCapeIndex >= capeOptions.size()) {
            return null;
        }
        CapeOption option = capeOptions.get(selectedCapeIndex);
        return option == null ? null : option.key;
    }

    private static int findCapeOptionIndexByKeyLocked(String key) {
        if (key == null || capeOptions == null) {
            return -1;
        }
        for (int i = 0; i < capeOptions.size(); i++) {
            CapeOption option = capeOptions.get(i);
            if (option != null && key.equals(option.key)) {
                return i;
            }
        }
        return -1;
    }

    private static void addExternalCapeHistorySources(LinkedHashMap<String, ExternalCapeSource> sources, ResolvedTarget target, String identifier) {
        if (sources == null || identifier == null || identifier.trim().isEmpty()) {
            return;
        }

        for (String type : CAPES_DEV_HISTORY_TYPES) {
            try {
                String responseText = HypixelApiHelper.fetchText(CAPES_DEV_HISTORY_URL + identifier.trim() + "/" + type);
                JsonObject root = new JsonParser().parse(responseText).getAsJsonObject();
                if (!root.has("history") || !root.get("history").isJsonArray()) {
                    continue;
                }

                JsonArray history = root.getAsJsonArray("history");
                int labelIndex = 1;
                for (JsonElement element : history) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }

                    JsonObject capeObject = element.getAsJsonObject();
                    if (!capeObject.has("exists") || !capeObject.get("exists").getAsBoolean()) {
                        continue;
                    }

                    String resolvedType = capeObject.has("type") && !capeObject.get("type").isJsonNull()
                            ? capeObject.get("type").getAsString()
                            : type;
                    if (!isSupportedExternalCapeType(resolvedType)) {
                        continue;
                    }

                    String imageUrl = readExternalCapeDownloadUrl(capeObject, resolvedType);
                    if (imageUrl == null || imageUrl.isEmpty()) {
                        continue;
                    }
                    ExternalCapeSource source = buildExternalCapeSource(
                            resolvedType, imageUrl, labelIndex, readExternalCapeApiHash(capeObject));
                    String textureHash = source.textureHash;
                    String dedupeKey = textureHash != null
                            ? resolvedType.toLowerCase(Locale.ROOT) + "|" + textureHash
                            : resolvedType.toLowerCase(Locale.ROOT) + "|" + imageUrl;
                    if (!sources.containsKey(dedupeKey)) {
                        sources.put(dedupeKey, source);
                        labelIndex++;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void addExternalCurrentCapeSourcesFromIdentifier(LinkedHashMap<String, ExternalCapeSource> sources, ResolvedTarget target, String identifier) {
        if (sources == null || identifier == null || identifier.trim().isEmpty()) {
            return;
        }

        try {
            String responseText = HypixelApiHelper.fetchText(CAPES_DEV_LOAD_URL + identifier.trim());
            JsonObject root = new JsonParser().parse(responseText).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry == null || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                    continue;
                }

                JsonObject capeObject = entry.getValue().getAsJsonObject();
                if (!capeObject.has("exists") || !capeObject.get("exists").getAsBoolean()) {
                    continue;
                }

                String type = capeObject.has("type") && !capeObject.get("type").isJsonNull()
                        ? capeObject.get("type").getAsString()
                        : entry.getKey();
                if (!isSupportedExternalCapeType(type)) {
                    continue;
                }

                if ("minecraft".equalsIgnoreCase(type) && target != null && target.capeUrl != null && !target.capeUrl.isEmpty()) {
                    continue;
                }

                String imageUrl = readExternalCapeDownloadUrl(capeObject, type);
                if (imageUrl == null || imageUrl.isEmpty()) {
                    continue;
                }

                ExternalCapeSource source = buildExternalCapeSource(
                        type, imageUrl, 0, readExternalCapeApiHash(capeObject));
                String textureHash = source.textureHash;
                String dedupeKey = textureHash != null
                        ? type.toLowerCase(Locale.ROOT) + "|" + textureHash
                        : type.toLowerCase(Locale.ROOT) + "|" + imageUrl;
                if (!sources.containsKey(dedupeKey)) {
                    sources.put(dedupeKey, source);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static ExternalCapeSource buildExternalCapeSource(String type, String imageUrl, int historicalIndex, String apiCapeHash) {
        String hash = null;
        String label = null;
        if ("minecraft".equalsIgnoreCase(type)) {
            MinecraftCapeCatalog.ResolvedCapeMeta meta = MinecraftCapeCatalog.resolveCapeMetaFromUrlBlocking(imageUrl);
            hash = meta.textureHash;
            label = meta.label;
            if (hash == null) {
                hash = MinecraftCapeCatalog.textureHashFromUrl(imageUrl);
            }
        } else if ("optifine".equalsIgnoreCase(type) || "minecraftcapes".equalsIgnoreCase(type)) {
            label = historicalIndex > 0
                    ? formatHistoricalCapeLabel(type, historicalIndex)
                    : formatExternalCapeLabel(type);
            hash = apiCapeHash;
            return new ExternalCapeSource(type, label, imageUrl, hash);
        } else {
            hash = MinecraftCapeCatalog.textureHashFromUrl(imageUrl);
        }
        if (label == null || label.isEmpty()) {
            if (hash != null) {
                label = MinecraftCapeCatalog.labelForTextureHash(hash);
            }
        }
        if (label == null || label.isEmpty()) {
            label = historicalIndex > 0
                    ? formatHistoricalCapeLabel(type, historicalIndex)
                    : formatExternalCapeLabel(type);
        }
        return new ExternalCapeSource(type, label, imageUrl, hash);
    }

    private static String readExternalCapeApiHash(JsonObject capeObject) {
        if (capeObject == null || !capeObject.has("hash") || capeObject.get("hash").isJsonNull()) {
            return null;
        }
        String value = capeObject.get("hash").getAsString();
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** Stable picker/wear key for OptiFine / MinecraftCapes rows (many can share the same PNG texture). */
    private static String buildThirdPartyCapeOptionKey(String sourceType, String apiHash, String imageUrl) {
        String type = sourceType == null ? "ext" : sourceType.toLowerCase(Locale.ROOT);
        String id = apiHash != null && !apiHash.isEmpty() ? apiHash : imageUrl;
        return "gomod:cape-history:" + type + ":" + id;
    }

    private static String readExternalCapeDownloadUrl(JsonObject capeObject, String type) {
        if (capeObject == null) {
            return null;
        }
        if (MinecraftCapeCatalog.isThirdPartyAccountCapeSourceType(type)) {
            String baseUrl = readExternalCapeImageVariantUrl(capeObject, "base");
            if (baseUrl != null) {
                return baseUrl;
            }
            String stillUrl = readExternalCapeImageVariantUrl(capeObject, "still");
            if (stillUrl != null) {
                return stillUrl;
            }
            String imageUrl = readExternalCapeJsonString(capeObject, "imageUrl");
            if (imageUrl != null) {
                return imageUrl;
            }
            String stillImageUrl = readExternalCapeJsonString(capeObject, "stillImageUrl");
            if (stillImageUrl != null) {
                return stillImageUrl;
            }
            String capeUrl = readExternalCapeJsonString(capeObject, "capeUrl");
            if (capeUrl != null && isLikelyDirectCapeImageUrl(capeUrl)) {
                return capeUrl;
            }
            return null;
        }
        return readExternalCapeImageUrl(capeObject);
    }

    private static boolean isLikelyDirectCapeImageUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.contains(".png?")
                || lower.contains("/img/");
    }

    private static String readExternalCapeJsonString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        String value = object.get(field).getAsString();
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String readExternalCapeImageUrl(JsonObject capeObject) {
        if (capeObject == null) {
            return null;
        }

        String baseUrl = readExternalCapeImageVariantUrl(capeObject, "base");
        if (baseUrl != null) {
            return baseUrl;
        }

        if (capeObject.has("stillImageUrl") && !capeObject.get("stillImageUrl").isJsonNull()) {
            String value = capeObject.get("stillImageUrl").getAsString();
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }

        if (capeObject.has("imageUrls") && capeObject.get("imageUrls").isJsonObject()) {
            String stillUrl = readExternalCapeImageVariantUrl(capeObject, "still");
            if (stillUrl != null) {
                return stillUrl;
            }
        }

        if (capeObject.has("imageUrl") && !capeObject.get("imageUrl").isJsonNull()) {
            String value = capeObject.get("imageUrl").getAsString();
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }

        return readExternalCapeJsonString(capeObject, "capeUrl");
    }

    private static String readExternalCapeImageVariantUrl(JsonObject capeObject, String variant) {
        if (capeObject == null || variant == null
                || !capeObject.has("imageUrls") || !capeObject.get("imageUrls").isJsonObject()) {
            return null;
        }
        JsonObject imageUrls = capeObject.getAsJsonObject("imageUrls");
        if (!imageUrls.has(variant) || !imageUrls.get(variant).isJsonObject()) {
            return null;
        }
        JsonObject object = imageUrls.getAsJsonObject(variant);
        String value = readExternalCapeJsonString(object, "full");
        if (value != null) {
            return value;
        }
        value = readExternalCapeJsonString(object, "front");
        if (value != null) {
            return value;
        }
        return readExternalCapeJsonString(object, "back");
    }

    private static String buildTargetToggleLabel(boolean apiStateKnown, boolean enabledByTarget) {
        String name = (targetName != null && !targetName.trim().isEmpty()) ? targetName.trim() : "Target";
        String poss = name + "'s";
        if (!isEnabled() || !apiStateKnown) {
            return poss;
        }
        return poss + " " + (enabledByTarget
                ? EnumChatFormatting.GREEN + "(ON)"
                : EnumChatFormatting.RED + "(OFF)");
    }

    private static boolean isSupportedExternalCapeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }

        switch (type.toLowerCase(Locale.ROOT)) {
            case "minecraft":
            case "optifine":
            case "minecraftcapes":
                return true;
            default:
                return false;
        }
    }

    private static String formatExternalCapeLabel(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "Extra Cape";
        }

        switch (type.toLowerCase(Locale.ROOT)) {
            case "minecraft":
                return "Mojang Cape";
            case "optifine":
                return "OptiFine Cape";
            case "minecraftcapes":
                return "MinecraftCapes Cape";
            case "labymod":
                return "LabyMod Cape";
            case "5zig":
                return "5zig Cape";
            case "tlauncher":
                return "TLauncher Cape";
            case "skinmc":
                return "SkinMC Cape";
            default:
                return Character.toUpperCase(type.charAt(0)) + type.substring(1) + " Cape";
        }
    }

    private static String formatHistoricalCapeLabel(String type, int index) {
        String baseLabel;
        switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "minecraft":
                baseLabel = "Mojang";
                break;
            case "optifine":
                baseLabel = "OptiFine";
                return baseLabel + " " + index;
            case "minecraftcapes":
                baseLabel = "MinecraftCapes";
                break;
            default:
                baseLabel = formatExternalCapeLabel(type).replace(" Cape", "");
                break;
        }
        return index <= 1 ? baseLabel + " Current" : baseLabel + " " + index;
    }

    private static String buildTeamFormattedName(String baseUsername) {
        if (baseUsername == null || baseUsername.isEmpty()) {
            return baseUsername;
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return baseUsername;
            }
            ScorePlayerTeam team = mc.theWorld.getScoreboard().getPlayersTeam(baseUsername);
            return ScorePlayerTeam.formatPlayerName(team, baseUsername);
        } catch (Throwable ignored) {
            return baseUsername;
        }
    }

    public static boolean shouldOverrideBlitzLobbySidebar(ScoreObjective objective) {
        if (!shouldApplyAuxiliaryOverrides() || objective == null) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !isOnHypixelServer(mc)) {
            return false;
        }
        // Never rewrite the sidebar while watching a Hypixel replay — the sidebar shows replay-specific
        // or game-phase rows (map name, timer, etc.) that must not be scrambled by the disguise mutator.
        if (GameContext.isHypixelReplayWatching(mc)) {
            return false;
        }
        // Rewrite lifetime Blitz stats on lobby and pregame stat boards, but never during cages, live games,
        // or post-game where rows like "Kills" are match-state, not profile-state.
        // Also skip the map-pregame context (on the actual Blitz map waiting for cages) — that sidebar shows
        // map/player-count rows, not the hub lobby stat bundle, so rewriting scrambles those lines.
        if (GameContext.isBlitzCagePhaseSidebar(mc)
                || GameContext.isBlitzGameActive(mc)
                || GameContext.isBlitzGameOverSidebar(mc)
                || GameContext.isBlitzMatchMapPregameSidebar(mc)) {
            return false;
        }
        // Title may be rebranded (no literal "BLITZ SG"); still detect Blitz stat bundle on the sidebar.
        boolean titleOrFingerprint = hasBlitzSgSidebar(objective, mc)
                || hasBlitzStatFingerprintSidebar(objective)
                || sidebarEntriesMentionBlitz(objective);
        // Main Hypixel hubs often omit "lobby"/"limbo" on the sidebar, so isHypixelLobby is false even at spawn.
        // Rank can still rewrite via FontRenderer, but score-column stats need this path — use server + sidebar fingerprint only.
        boolean mainHubDisguiseSidebar = isOnHypixelServer(mc) && hasMainHypixelLobbyDisguiseSidebar(objective);
        // A Blitz title and "Kills" also describe a live match. Require actual profile-only rows;
        // this reads the current objective instead of trusting a cached lobby phase during a packet transition.
        if (!mainHubDisguiseSidebar && !hasLobbyOnlySidebarStat(objective)) {
            return false;
        }
        boolean blitzLobbySidebar = GameContext.isHypixelLobby(mc)
                && (hasBlitzSgSidebar(objective, mc) || hasBlitzStatFingerprintSidebar(objective));
        if (!titleOrFingerprint && !blitzLobbySidebar && !mainHubDisguiseSidebar) {
            return false;
        }
        if (!hasSidebarStatBoard(objective)) {
            if (!blitzLobbySidebar || !hasAnyNonMarkerSidebarRow(objective)) {
                return false;
            }
        }
        // Even when the API returns no HungerGames for the disguise target, still rewrite sidebar values
        // (zeros) so your real Blitz stats never leak on-screen.
        return true;
    }

    private static boolean hasLobbyOnlySidebarStat(ScoreObjective objective) {
        if (objective == null || objective.getScoreboard() == null) return false;
        for (Score score : objective.getScoreboard().getSortedScores(objective)) {
            if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) continue;
            SidebarStatKind kind = detectSidebarStat(buildSidebarEntryLine(objective, score));
            if (kind == SidebarStatKind.SOLO_WINS || kind == SidebarStatKind.TEAM_WINS
                    || kind == SidebarStatKind.LOBBY_RANK || kind == SidebarStatKind.LOBBY_ACHIEVEMENT_POINTS
                    || kind == SidebarStatKind.LOBBY_HYPIXEL_LEVEL) return true;
        }
        return false;
    }

    /**
     * Chat debug: why Blitz sidebar disguise rewrite is or is not active. Run on Hypixel while disguised when stats look wrong.
     */
    public static synchronized java.util.List<String> buildDisguiseSidebarDebugLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        net.minecraft.util.EnumChatFormatting y = net.minecraft.util.EnumChatFormatting.YELLOW;
        net.minecraft.util.EnumChatFormatting g = net.minecraft.util.EnumChatFormatting.GRAY;
        Minecraft mc = Minecraft.getMinecraft();
        lines.add(y + "=== Disguise sidebar debug ===");
        lines.add(g + "disguise enabled: " + isEnabled());
        lines.add(g + "source=" + realName + " target=" + targetName);
        lines.add(g + "renderDepth=" + activeSidebarRenderDepth + " fastSidebarActive=" + fastSidebarRenderActive);
        if (mc == null) {
            lines.add(g + "mc null");
            return lines;
        }
        lines.add(g + "hypixel (serverIP): " + isOnHypixelServer(mc));
        lines.add(g + "GameContext.isHypixelLobby: " + GameContext.isHypixelLobby(mc));
        lines.add(g + "GameContext.isBlitzPregameLobby: " + GameContext.isBlitzPregameLobby(mc));
        lines.add(g + "GameContext.isBlitzCagePhaseSidebar: " + GameContext.isBlitzCagePhaseSidebar(mc));
        lines.add(g + "GameContext.isBlitzGameActive: " + GameContext.isBlitzGameActive(mc));
        lines.add(g + "GameContext.isBlitzGameOverSidebar: " + GameContext.isBlitzGameOverSidebar(mc));
        ScoreObjective obj = findSidebarObjective(mc);
        if (obj == null) {
            lines.add(g + "sidebar objective (slot 1): null");
            lines.add(y + "shouldOverrideBlitzLobbySidebar: false (no objective)");
            return lines;
        }
        lines.add(g + "objective displayName (strip): " + stripFormattingCodes(obj.getDisplayName()));
        lines.add(g + "objective internal: " + stripFormattingCodes(obj.getName()));
        boolean t1 = hasBlitzSgSidebar(obj, mc);
        boolean t2 = hasBlitzStatFingerprintSidebar(obj);
        boolean t3 = sidebarEntriesMentionBlitz(obj);
        lines.add(g + "hasBlitzSgSidebar: " + t1);
        lines.add(g + "hasBlitzStatFingerprintSidebar: " + t2);
        lines.add(g + "sidebarEntriesMentionBlitz: " + t3);
        boolean titleOrFingerprint = t1 || t2 || t3;
        boolean gameContextBlitz = GameContext.isBlitzPregameLobby(mc)
                || GameContext.isBlitzGameActive(mc)
                || GameContext.isBlitzGameOverSidebar(mc)
                || (GameContext.isHypixelLobby(mc) && (t1 || t2));
        lines.add(g + "titleOrFingerprint: " + titleOrFingerprint);
        lines.add(g + "gameContextBlitz: " + gameContextBlitz);
        lines.add(g + "hasMainHypixelLobbyDisguiseSidebar: " + hasMainHypixelLobbyDisguiseSidebar(obj));
        lines.add(g + "hasSidebarStatBoard: " + hasSidebarStatBoard(obj));
        lines.add(g + "hasLobbyOnlySidebarStat: " + hasLobbyOnlySidebarStat(obj));
        lines.add(g + "scoreboardMutator: " + DisguiseSidebarScoreboardMutator.debugSummary());
        lines.add(g + "hasAnyNonMarkerSidebarRow: " + hasAnyNonMarkerSidebarRow(obj));
        lines.add(g + "targetBlitzStats empty: " + (targetBlitzStats == null || targetBlitzStats.isEmpty()));
        lines.add(y + "shouldOverrideBlitzLobbySidebar: " + shouldOverrideBlitzLobbySidebar(obj));
        int rowIndex = 0;
        for (Score score : obj.getScoreboard().getSortedScores(obj)) {
            if (score == null || rowIndex >= 32) continue;
            String raw = buildSidebarEntryLine(obj, score);
            String cached = renderCache.get(raw);
            int hash = raw.hashCode();
            FastRenderCacheEntry fast = fastRenderCache.get((hash ^ (hash >>> 16)) & FAST_RENDER_CACHE_MASK);
            String fastOutput = fast != null && fast.generation == fastRenderCacheGeneration
                    && raw.equals(fast.input) ? fast.output : "<none>";
            lines.add(g + "row[" + rowIndex++ + "] entry=" + score.getPlayerName()
                    + " points=" + score.getScorePoints() + " raw=" + raw.replace('\u00a7', '&')
                    + " stat=" + detectSidebarStat(raw)
                    + " renderCache=" + (cached == null ? "<none>" : cached.replace('\u00a7', '&'))
                    + " fastCache=" + fastOutput.replace('\u00a7', '&'));
        }
        return lines;
    }

    /**
     * Main Hypixel hub sidebar (Rank / Achievement pts / Hypixel level). Distinct from Blitz stat rows.
     */
    private static boolean hasMainHypixelLobbyDisguiseSidebar(ScoreObjective objective) {
        if (objective == null || objective.getScoreboard() == null) {
            return false;
        }
        if (hasBlitzStatFingerprintSidebar(objective)) {
            return false;
        }
        try {
            for (Score score : objective.getScoreboard().getSortedScores(objective)) {
                if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                    continue;
                }
                SidebarStatKind k = detectSidebarStat(buildSidebarEntryLine(objective, score));
                if (k == SidebarStatKind.LOBBY_RANK || k == SidebarStatKind.LOBBY_LEVEL
                        || k == SidebarStatKind.LOBBY_HYPIXEL_LEVEL
                        || k == SidebarStatKind.LOBBY_ACHIEVEMENT_POINTS) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Kills + (solo or team) wins lines — strong signal this is Blitz SG pregame, not e.g. Bed Wars. */
    private static boolean hasBlitzStatFingerprintSidebar(ScoreObjective objective) {
        if (objective == null || objective.getScoreboard() == null) {
            return false;
        }
        boolean kills = false;
        boolean solo = false;
        boolean team = false;
        boolean coins = false;
        try {
            for (Score score : objective.getScoreboard().getSortedScores(objective)) {
                if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                    continue;
                }
                SidebarStatKind k = detectSidebarStat(buildSidebarEntryLine(objective, score));
                if (k == SidebarStatKind.KILLS) {
                    kills = true;
                } else if (k == SidebarStatKind.SOLO_WINS) {
                    solo = true;
                } else if (k == SidebarStatKind.TEAM_WINS) {
                    team = true;
                } else if (k == SidebarStatKind.COINS) {
                    coins = true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        // Some builds use a single wins line + coins; coins + kills is still a Blitz pregame fingerprint.
        if (kills && (solo || team || coins)) {
            return true;
        }
        // Hub / condensed sidebars: only "Kills" (or one stat) while the objective title still says Blitz.
        return kills && hasBlitzSgSidebar(objective, Minecraft.getMinecraft());
    }

    private static boolean hasBlitzSgSidebar(ScoreObjective objective, Minecraft mc) {
        if (objective == null || mc == null || !isOnHypixelServer(mc)) {
            return false;
        }

        String displayName = stripFormattingCodes(objective.getDisplayName()).trim().toUpperCase(Locale.ROOT);
        String objectiveName = stripFormattingCodes(objective.getName()).trim().toUpperCase(Locale.ROOT);
        String combined = displayName + " " + objectiveName;
        if (combined.contains("BLITZ SG")) {
            return true;
        }
        if (combined.contains("BLITZ")) {
            return true;
        }
        return combined.contains("SURVIVAL") && combined.contains("GAME");
    }

    /** True when any sidebar row text mentions Blitz (hub / queue / rebranded titles that omit "BLITZ" in the objective name). */
    private static boolean sidebarEntriesMentionBlitz(ScoreObjective objective) {
        if (objective == null || objective.getScoreboard() == null) {
            return false;
        }
        try {
            String title = stripFormattingCodes(objective.getDisplayName()).toLowerCase(Locale.ROOT);
            String internal = stripFormattingCodes(objective.getName()).toLowerCase(Locale.ROOT);
            if (title.contains("blitz") || title.contains("bsg") || internal.contains("blitz") || internal.contains("bsg")) {
                return true;
            }
            for (Score score : objective.getScoreboard().getSortedScores(objective)) {
                if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                    continue;
                }
                String v = stripFormattingCodes(buildSidebarEntryLine(objective, score)).toLowerCase(Locale.ROOT);
                if (v.contains("blitz") || v.contains("bsg") || v.contains("survival games")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasSidebarStatBoard(ScoreObjective objective) {
        if (objective == null) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !isOnHypixelServer(mc)) {
            return false;
        }

        long now = System.currentTimeMillis();
        // Never serve a cached "false" — the board often fills from empty/partials over a few packets; stale false
        // suppresses disguise rewrites for 500ms (real rank/stats flash). Cache only positive matches.
        if (objective == lastSidebarObjective && now - lastSidebarObjectiveCheckMs <= 500L && lastSidebarObjectiveMatches) {
            return true;
        }

        lastSidebarObjective = objective;
        lastSidebarObjectiveCheckMs = now;

        int matchedLines = 0;
        try {
            for (Score score : objective.getScoreboard().getSortedScores(objective)) {
                if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                    continue;
                }
                if (detectSidebarStat(buildSidebarEntryLine(objective, score)) != null) {
                    matchedLines++;
                }
            }
        } catch (Throwable ignored) {
            matchedLines = 0;
        }

        // Hypixel has trimmed or relabeled rows before; one recognized stat line is enough once Blitz context matched.
        lastSidebarObjectiveMatches = matchedLines >= 1;
        return lastSidebarObjectiveMatches;
    }

    private static boolean hasAnyNonMarkerSidebarRow(ScoreObjective objective) {
        if (objective == null || objective.getScoreboard() == null) {
            return false;
        }
        try {
            for (Score score : objective.getScoreboard().getSortedScores(objective)) {
                if (score != null && score.getPlayerName() != null && !score.getPlayerName().startsWith("#")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static ScoreObjective findSidebarObjective(Minecraft mc) {
        try {
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return null;
            }
            return mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildSidebarEntryLine(ScoreObjective objective, Score score) {
        if (objective == null || score == null || objective.getScoreboard() == null) {
            return score == null ? "" : score.getPlayerName();
        }

        try {
            ScorePlayerTeam team = objective.getScoreboard().getPlayersTeam(score.getPlayerName());
            return ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
        } catch (Throwable ignored) {
            return score.getPlayerName();
        }
    }

    /**
     * Finds a Blitz stat label anywhere on the visible line (Hypixel sometimes prefixes rows with icons/text)
     * and returns its kind plus the visible-character range of the matched alias.
     */
    private static SidebarStatMatch detectSidebarStatMatch(String formattedLine) {
        if (formattedLine == null || formattedLine.isEmpty()) {
            return null;
        }
        SpanData spanData = buildVisibleMapping(formattedLine);
        String visible = spanData.visibleText;
        if (visible == null || visible.isEmpty()) {
            return null;
        }
        visible = normalizeSidebarTextForStatAliases(visible);
        int visStart = firstNonWhitespaceIndex(visible);
        if (visStart < 0) {
            return null;
        }
        String rest = visible.substring(visStart).toLowerCase(Locale.ROOT);
        int bestPos = Integer.MAX_VALUE;
        SidebarStatKind bestKind = null;
        int bestAliasLen = 0;

        // Prefer TEAM_WINS before SOLO_WINS so a bare "wins:" inside "teams wins: …" does not steal the row.
        for (int i = 0; i < rest.length(); i++) {
            for (SidebarStatKind kind : SidebarStatKind.detectionOrder()) {
                for (String alias : kind.getAliasesLongestFirst()) {
                    if (alias != null && !alias.isEmpty() && rest.regionMatches(i, alias, 0, alias.length())) {
                        if (i < bestPos || (i == bestPos && alias.length() > bestAliasLen)) {
                            bestPos = i;
                            bestKind = kind;
                            bestAliasLen = alias.length();
                        }
                        break;
                    }
                }
            }
        }

        if (bestKind == null || bestPos == Integer.MAX_VALUE) {
            return null;
        }
        return new SidebarStatMatch(bestKind, visStart + bestPos, bestAliasLen);
    }

    private static SidebarStatKind detectSidebarStat(String formattedLine) {
        SidebarStatMatch m = detectSidebarStatMatch(formattedLine);
        return m == null ? null : m.kind;
    }

    private static Integer getSidebarStatValue(SidebarStatKind statKind) {
        if (statKind == null) {
            return null;
        }
        if (targetBlitzStats == null) {
            return 0;
        }

        switch (statKind) {
            case KILLS:
                return targetBlitzStats.kills != null ? targetBlitzStats.kills : 0;
            case SOLO_WINS:
                return targetBlitzStats.soloWins != null ? targetBlitzStats.soloWins : 0;
            case TEAM_WINS:
                return targetBlitzStats.teamWins != null ? targetBlitzStats.teamWins : 0;
            case COINS:
                return targetBlitzStats.coins != null ? targetBlitzStats.coins : 0;
            case LOBBY_LEVEL:
            case LOBBY_HYPIXEL_LEVEL:
                return targetNetworkLevelKnown ? targetNetworkLevel : 0;
            case LOBBY_ACHIEVEMENT_POINTS:
                return targetAchievementPointsKnown ? targetAchievementPoints : 0;
            default:
                return null;
        }
    }

    private static String replaceSidebarValueInLine(String formattedLine, SidebarStatMatch match, int statValue) {
        SpanData spanData = buildVisibleMapping(formattedLine);
        String visible = spanData.visibleText;
        if (visible == null || match == null) {
            return formattedLine;
        }
        visible = normalizeSidebarTextForStatAliases(visible);

        int labelStartVisible = match.labelStartVisible;
        int labelLength = match.labelLength;
        if (labelStartVisible < 0 || labelLength <= 0 || labelStartVisible + labelLength > visible.length()) {
            return formattedLine;
        }

        int valueStartVisible = skipSidebarLeadWhitespaceAndInvisible(visible, labelStartVisible + labelLength);
        // Label alias may end before ":" (e.g. "teams wins" vs "teams wins: 12") — skip separator so we replace the number.
        valueStartVisible = skipSidebarLabelValueSeparator(visible, valueStartVisible);
        valueStartVisible = skipSidebarLeadingEmojiDecorBeforeDigits(visible, valueStartVisible);
        if (valueStartVisible >= visible.length()) {
            return formattedLine;
        }

        int valueEndVisible = scanSidebarValueEndVisible(visible, valueStartVisible);
        if (valueEndVisible <= valueStartVisible) {
            return formattedLine;
        }

        int formattedValueStart = spanData.visibleToFormattedStart[valueStartVisible];
        int formattedValueEnd = spanData.visibleToFormattedEnd[valueEndVisible - 1];
        String valueFormatting = getActiveFormattingBefore(formattedLine, formattedValueStart);
        String replacementValue = formatSidebarValue(statValue);
        boolean dropTrailingNumericTail = false;
        int trailingVisibleStart = skipSidebarLeadWhitespaceAndInvisible(visible, valueEndVisible);
        if (trailingVisibleStart < visible.length()) {
            dropTrailingNumericTail = true;
            for (int i = trailingVisibleStart; i < visible.length(); i++) {
                char c = visible.charAt(i);
                if (!Character.isWhitespace(c) && !isSidebarNumericValueChar(c) && !isSidebarInvisibleNumberSeparatorChar(c)
                        && !(c == ' ' && i > trailingVisibleStart && i + 1 < visible.length()
                        && Character.isDigit(visible.charAt(i - 1)) && Character.isDigit(visible.charAt(i + 1)))) {
                    dropTrailingNumericTail = false;
                    break;
                }
            }
        }

        String suffix = dropTrailingNumericTail ? "" : formattedLine.substring(formattedValueEnd);
        // If formatted tail is only separators/digits (leftover from a partial visible span), drop it.
        if (suffix != null && !suffix.isEmpty()) {
            String visSuffix = stripFormattingCodes(suffix);
            if (visSuffix != null) {
                String t = visSuffix.trim();
                if (!t.isEmpty()) {
                    boolean onlyNumericNoise = true;
                    for (int i = 0; i < t.length(); i++) {
                        char ch = t.charAt(i);
                        if (!Character.isWhitespace(ch) && !isSidebarNumericValueChar(ch) && !isSidebarInvisibleNumberSeparatorChar(ch)
                                && !(ch == ' ' && i > 0 && i + 1 < t.length()
                                && Character.isDigit(t.charAt(i - 1)) && Character.isDigit(t.charAt(i + 1)))) {
                            onlyNumericNoise = false;
                            break;
                        }
                    }
                    if (onlyNumericNoise) {
                        suffix = "";
                    }
                }
            }
        }
        return formattedLine.substring(0, formattedValueStart)
                + valueFormatting
                + replacementValue
                + suffix;
    }

    /** Main Hypixel lobby sidebar "Rank:" row — replace trailing text with {@link #buildRankTag()}. */
    private static String replaceSidebarRankTailAfterLabel(String formattedLine, SidebarStatMatch match) {
        SpanData spanData = buildVisibleMapping(formattedLine);
        String visible = spanData.visibleText;
        if (visible == null || match == null) {
            return formattedLine;
        }
        visible = normalizeSidebarTextForStatAliases(visible);
        int labelStartVisible = match.labelStartVisible;
        int labelLength = match.labelLength;
        if (labelStartVisible < 0 || labelLength <= 0 || labelStartVisible + labelLength > visible.length()) {
            return formattedLine;
        }
        int valueStartVisible = skipSidebarLeadWhitespaceAndInvisible(visible, labelStartVisible + labelLength);
        valueStartVisible = skipSidebarLabelValueSeparator(visible, valueStartVisible);
        String rankTag = buildRankTag();
        if (rankTag == null || rankTag.isEmpty()) {
            rankTag = EnumChatFormatting.GRAY + "NONE";
        }
        if (valueStartVisible >= visible.length()) {
            return formattedLine + EnumChatFormatting.RESET + " " + rankTag;
        }
        int valueEndVisible = visible.length();
        int formattedValueStart = spanData.visibleToFormattedStart[valueStartVisible];
        int formattedValueEnd = spanData.visibleToFormattedEnd[valueEndVisible - 1];
        String valueFormatting = getActiveFormattingBefore(formattedLine, formattedValueStart);
        return formattedLine.substring(0, formattedValueStart) + valueFormatting + rankTag + EnumChatFormatting.RESET;
    }

    private static String rewriteStandaloneSidebarStatLine(String formattedLine) {
        if (formattedLine == null || formattedLine.isEmpty() || !isEnabled()) {
            return formattedLine;
        }

        SidebarStatMatch match = detectSidebarStatMatch(formattedLine);
        if (match == null) {
            return formattedLine;
        }

        ScoreObjective sidebarObjective = activeSidebarRenderDepth > 0 ? activeSidebarRenderObjective : null;
        if (sidebarObjective == null) {
            Minecraft mc = Minecraft.getMinecraft();
            sidebarObjective = findSidebarObjective(mc);
            if (mc == null || !shouldOverrideBlitzLobbySidebar(sidebarObjective) || !hasSidebarStatBoard(sidebarObjective)) {
                return formattedLine;
            }
        }
        if (!shouldOverrideBlitzLobbySidebar(sidebarObjective)) {
            return formattedLine;
        }
        if (match.kind == SidebarStatKind.LOBBY_RANK) {
            if (!rankKnown) {
                return formattedLine;
            }
            return replaceSidebarRankTailAfterLabel(formattedLine, match);
        }
        Integer statValue = getSidebarStatValue(match.kind);
        if (statValue == null) {
            return formattedLine;
        }

        return replaceSidebarValueInLine(formattedLine, match, statValue.intValue());
    }

    private static String rewriteLeaderboardHologramLine(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty() || targetName == null || targetName.isEmpty()) {
            return input;
        }

        if (GameContext.isHypixelLobby(Minecraft.getMinecraft())) {
            // Never run rank rewrite on arbitrary lobby strings (chat, TAB fragments, etc.) — that was
            // freezing the client and breaking outgoing chat. Only touch real hologram leaderboard rows.
            String plain = stripFormattingCodes(input);
            if (plain == null) {
                plain = "";
            }
            String trimmedPlain = plain.trim();
            if ("You have no data yet.".equalsIgnoreCase(trimmedPlain) && wasTargetLeaderboardSeenRecently()) {
                return "";
            }
            Matcher lobbyMatcher = LEADERBOARD_ENTRY_PATTERN.matcher(trimmedPlain);
            if (!lobbyMatcher.matches()) {
                return input;
            }
            String rewritten = replaceRankAndNameSegment(input, realName, false, true, false, false, false, false);
            return LeaderboardHologramFormatting.restoreSeparatorFormatting(input, rewritten);
        }

        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        String trimmedVisible = visible == null ? "" : visible.trim();
        if ("You have no data yet.".equalsIgnoreCase(trimmedVisible) && wasTargetLeaderboardSeenRecently()) {
            return "";
        }

        Matcher matcher = LEADERBOARD_ENTRY_PATTERN.matcher(visible);
        if (!matcher.matches()) {
            return input;
        }

        int playerSegmentStart = matcher.start(2);
        int playerSegmentEnd = matcher.end(2);
        if (playerSegmentStart < 0 || playerSegmentEnd <= playerSegmentStart) {
            return input;
        }

        String playerSegment = matcher.group(2);
        boolean containsTarget = containsLeaderboardPlayerName(playerSegment, targetName);
        boolean containsReal = containsLeaderboardPlayerName(playerSegment, realName);

        if (containsTarget) {
            lastLeaderboardTargetSeenMs = System.currentTimeMillis();
            return replaceVisibleRangePreservingFormatting(input, spanData, playerSegmentStart, playerSegmentEnd, targetName, true);
        }

        if (containsReal) {
            lastLeaderboardTargetSeenMs = System.currentTimeMillis();
            return replaceVisibleRangePreservingFormatting(input, spanData, playerSegmentStart, playerSegmentEnd, targetName, true);
        }

        return input;
    }

    private static int firstNonWhitespaceIndex(String value) {
        if (value == null) {
            return -1;
        }

        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Characters that form the visible stat value (digits + grouping). Unicode thin/narrow spaces are used between
     * digit groups on Hypixel; if we stop the scan there, only "41" of "418" is replaced and "8" remains → "4,0328".
     */
    private static boolean isSidebarNumericValueChar(char c) {
        if (Character.isDigit(c) || c == ',' || c == '.' || c == '\uFF0C' || c == '\uFF0E') {
            return true;
        }
        return c == '\u00A0' || c == '\u202F' || c == '\u2009' || c == '\u2007';
    }

    /**
     * Invisible format chars Hypixel sometimes embeds between digits; {@link Character#isWhitespace(char)} does not
     * treat ZWSP (U+200B) as whitespace, so the value scan would stop early and leave a trailing digit.
     */
    private static boolean isSidebarInvisibleNumberSeparatorChar(char c) {
        return c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' || c == '\u2060'
                || c == '\u200E' || c == '\u200F';
    }

    /** Index after leading whitespace and invisible format chars (e.g. ZWSP before the value digits). */
    private static int skipSidebarLeadWhitespaceAndInvisible(String visible, int i) {
        if (visible == null) {
            return i;
        }
        while (i < visible.length()) {
            char c = visible.charAt(i);
            if (Character.isWhitespace(c) || isSidebarInvisibleNumberSeparatorChar(c)) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * Hypixel splits stat digits around an emoji (scoreboard player name), e.g. {@code Teams Wins: 11🏀8} → 118.
     * Without bridging, only "11" is replaced and "🏀8" remains → looks like "4,0328".
     */
    private static boolean isSidebarHypixelDigitBridgeCodePoint(int cp) {
        if (cp <= 0x20 || cp == 0x7F) {
            return false;
        }
        if (Character.isLetterOrDigit(cp)) {
            return false;
        }
        int t = Character.getType(cp);
        if (t == Character.OTHER_SYMBOL || t == Character.CURRENCY_SYMBOL || t == Character.MODIFIER_SYMBOL) {
            return true;
        }
        if (cp >= 0x1F300 && cp <= 0x1FAFF) {
            return true;
        }
        if (cp >= 0x2600 && cp <= 0x27BF) {
            return true;
        }
        return false;
    }

    /**
     * Returns true when the current numeric span already contains at least one real digit before {@code index}.
     * This lets Hypixel's emoji bridge appear after grouping punctuation like {@code 2,🏀210}.
     */
    private static boolean hasSidebarDigitBefore(String visible, int start, int index) {
        if (visible == null) {
            return false;
        }
        int from = Math.max(0, start);
        int to = Math.min(index, visible.length());
        for (int i = from; i < to; i++) {
            if (Character.isDigit(visible.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** End index (exclusive) of the numeric value starting at {@code start}. */
    private static int scanSidebarValueEndVisible(String visible, int start) {
        if (visible == null || start < 0 || start >= visible.length()) {
            return start;
        }
        int i = skipSidebarLeadWhitespaceAndInvisible(visible, start);
        int scanAnchor = i;
        int bridgesUsed = 0;
        final int maxBridges = 8;
        while (i < visible.length()) {
            char c = visible.charAt(i);
            if (isSidebarNumericValueChar(c)) {
                i++;
                continue;
            }
            if (isSidebarInvisibleNumberSeparatorChar(c)) {
                i++;
                continue;
            }
            if (c == ' ' && i > scanAnchor && i + 1 < visible.length()
                    && Character.isDigit(visible.charAt(i - 1)) && Character.isDigit(visible.charAt(i + 1))) {
                i++;
                continue;
            }
            if (bridgesUsed >= maxBridges || i <= scanAnchor) {
                break;
            }
            int cp = Character.codePointAt(visible, i);
            int cpLen = Character.charCount(cp);
            if (!isSidebarNumericValueChar(visible.charAt(i - 1))
                    || !hasSidebarDigitBefore(visible, scanAnchor, i)
                    || !isSidebarHypixelDigitBridgeCodePoint(cp)) {
                break;
            }
            int j = i + cpLen;
            j = skipSidebarLeadWhitespaceAndInvisible(visible, j);
            if (j >= visible.length() || !Character.isDigit(visible.charAt(j))) {
                break;
            }
            i += cpLen;
            bridgesUsed++;
        }
        return i;
    }

    private static boolean isEntirelySidebarScoreNumeric(String visibleTrimmed) {
        if (visibleTrimmed == null || visibleTrimmed.isEmpty()) {
            return false;
        }
        int i = 0;
        int bridgesUsed = 0;
        final int maxBridges = 8;
        while (i < visibleTrimmed.length()) {
            char c = visibleTrimmed.charAt(i);
            if (isSidebarNumericValueChar(c)) {
                i++;
                continue;
            }
            if (isSidebarInvisibleNumberSeparatorChar(c)) {
                i++;
                continue;
            }
            if (c == ' ' && i > 0 && i + 1 < visibleTrimmed.length()
                    && Character.isDigit(visibleTrimmed.charAt(i - 1))
                    && Character.isDigit(visibleTrimmed.charAt(i + 1))) {
                i++;
                continue;
            }
            if (bridgesUsed >= maxBridges || i <= 0) {
                return false;
            }
            int cp = Character.codePointAt(visibleTrimmed, i);
            int cpLen = Character.charCount(cp);
            if (!isSidebarNumericValueChar(visibleTrimmed.charAt(i - 1))
                    || !hasSidebarDigitBefore(visibleTrimmed, 0, i)
                    || !isSidebarHypixelDigitBridgeCodePoint(cp)) {
                return false;
            }
            int j = i + cpLen;
            j = skipSidebarLeadWhitespaceAndInvisible(visibleTrimmed, j);
            if (j >= visibleTrimmed.length() || !Character.isDigit(visibleTrimmed.charAt(j))) {
                return false;
            }
            i += cpLen;
            bridgesUsed++;
        }
        return true;
    }

    /** True if visible text already has a stat number after the label (skip append that duplicated Team Wins, etc.). */
    private static boolean visibleLineAlreadyHasNumericValueAfterLabel(String visible, int afterLabelVisible) {
        if (visible == null || afterLabelVisible < 0 || afterLabelVisible >= visible.length()) {
            return false;
        }
        int i = skipSidebarLeadWhitespaceAndInvisible(visible, afterLabelVisible);
        i = skipSidebarLabelValueSeparator(visible, i);
        return i < visible.length() && Character.isDigit(visible.charAt(i));
    }

    private static String formatSidebarValue(int value) {
        return value >= 1000 ? String.format(Locale.US, "%,d", value) : Integer.toString(value);
    }

    private static String formatNumberWithGrouping(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static boolean containsLeaderboardPlayerName(String playerSegment, String playerName) {
        if (playerSegment == null || playerName == null || playerName.isEmpty()) {
            return false;
        }

        String segmentLower = playerSegment.toLowerCase(Locale.ROOT);
        String playerLower = playerName.toLowerCase(Locale.ROOT);
        int nameIndex = segmentLower.indexOf(playerLower);
        while (nameIndex >= 0) {
            int beforeIndex = nameIndex - 1;
            int afterIndex = nameIndex + playerLower.length();
            boolean matchesBefore = beforeIndex < 0 || !isMinecraftNameCharacter(segmentLower.charAt(beforeIndex));
            boolean matchesAfter = afterIndex >= segmentLower.length() || !isMinecraftNameCharacter(segmentLower.charAt(afterIndex));
            if (matchesBefore && matchesAfter) {
                return true;
            }
            nameIndex = segmentLower.indexOf(playerLower, nameIndex + 1);
        }
        return false;
    }

    private static boolean isMinecraftNameCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean wasTargetLeaderboardSeenRecently() {
        return lastLeaderboardTargetSeenMs > 0L && System.currentTimeMillis() - lastLeaderboardTargetSeenMs <= 2000L;
    }

    private static String applyUnderlineToVisibleRange(String formattedText, SpanData spanData, int visibleStartInclusive, int visibleEndExclusive) {
        if (formattedText == null || formattedText.isEmpty() || spanData == null || spanData.visibleText == null) {
            return formattedText;
        }
        if (visibleStartInclusive < 0 || visibleEndExclusive <= visibleStartInclusive || visibleEndExclusive > spanData.visibleText.length()) {
            return formattedText;
        }

        int formattedStart = spanData.visibleToFormattedStart[visibleStartInclusive];
        int formattedEnd = spanData.visibleToFormattedEnd[visibleEndExclusive - 1];
        String restoreFormatting = getFormattingForContinuation(formattedText, formattedEnd);

        return formattedText.substring(0, formattedStart)
                + EnumChatFormatting.UNDERLINE
                + formattedText.substring(formattedStart, formattedEnd)
                + restoreFormatting
                + formattedText.substring(formattedEnd);
    }

    private static String replaceVisibleRangePreservingFormatting(String formattedText, SpanData spanData,
                                                                  int visibleStartInclusive, int visibleEndExclusive,
                                                                  String replacementVisibleText, boolean underline) {
        if (formattedText == null || formattedText.isEmpty() || spanData == null || spanData.visibleText == null) {
            return formattedText;
        }
        if (replacementVisibleText == null) {
            replacementVisibleText = "";
        }
        if (visibleStartInclusive < 0 || visibleEndExclusive <= visibleStartInclusive || visibleEndExclusive > spanData.visibleText.length()) {
            return formattedText;
        }

        int formattedStart = spanData.visibleToFormattedStart[visibleStartInclusive];
        int formattedEnd = spanData.visibleToFormattedEnd[visibleEndExclusive - 1];
        String activeFormatting = getActiveFormattingBefore(formattedText, formattedStart);
        String restoreFormatting = getFormattingForContinuation(formattedText, formattedEnd);

        StringBuilder replacement = new StringBuilder();
        if (activeFormatting != null && !activeFormatting.isEmpty()) {
            replacement.append(activeFormatting);
        }
        if (underline) {
            replacement.append(EnumChatFormatting.UNDERLINE);
        }
        replacement.append(replacementVisibleText);

        return formattedText.substring(0, formattedStart)
                + replacement
                + restoreFormatting
                + formattedText.substring(formattedEnd);
    }

    private static String stripFormattingCodes(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int first = -1;
        for (int i = value.indexOf('\u00a7'); i >= 0 && i + 1 < value.length(); i = value.indexOf('\u00a7', i + 1)) {
            if (isFormattingCode(value.charAt(i + 1))) { first = i; break; }
        }
        if (first < 0) return value;
        StringBuilder plain = new StringBuilder(value.length());
        plain.append(value, 0, first);
        for (int i = first; i < value.length(); i++) {
            if (value.charAt(i) == '\u00a7' && i + 1 < value.length() && isFormattingCode(value.charAt(i + 1))) i++;
            else plain.append(value.charAt(i));
        }
        return plain.toString();
    }

    private static boolean isFormattingCode(char code) {
        char c = code >= 'A' && code <= 'Z' ? (char) (code + ('a' - 'A')) : code;
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'k' && c <= 'o') || c == 'r';
    }

    private static int findFirstKillMessagePunctuation(String visible, int startIndex) {
        if (visible == null || startIndex < 0) {
            return -1;
        }
        for (int i = startIndex; i < visible.length(); i++) {
            char c = visible.charAt(i);
            if (c == '!' || c == '.' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String getFormattingForContinuation(String formattedText, int formattedIndex) {
        String formatting = getActiveFormattingBefore(formattedText, formattedIndex);
        return formatting == null ? "" : formatting;
    }

    public static synchronized void applyLiveOverrides() {
        try {
            if (!isEnabled() || !shouldApplyLiveSync()) {
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
                return;
            }

            NetworkPlayerInfo info = findLocalPlayerInfo(mc);
            if (info == null) {
                return;
            }
            if (info instanceof NetworkPlayerInfoAccessor) {
                NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
                ResourceLocation desiredCape = getCapeLocation();
                if (shouldApplySkinOverride()) {
                    accessor.gomod$setPlayerTexturesLoaded(true);
                }
                if (shouldApplySkinOverride() && !sameTexture(info.getLocationSkin(), skinLocation)) {
                    accessor.gomod$setLocationSkin(skinLocation);
                }
                if (shouldApplyCapeOverride() && !sameTexture(accessor.gomod$getLocationCape(), desiredCape)) {
                    accessor.gomod$setLocationCape(desiredCape);
                }
                if (shouldApplySkinOverride()) {
                    String desiredSkinType = getSkinType();
                    String currentSkinType = info.getSkinType();
                    if ((desiredSkinType == null && currentSkinType != null)
                            || (desiredSkinType != null && !desiredSkinType.equals(currentSkinType))) {
                        accessor.gomod$setSkinType(desiredSkinType);
                    }
                }
                // On Weave the TAB/name render hooks provide the disguise without changing the
                // NetworkPlayerInfo object Lunar uses as a TeamView/nametag input.
                if (shouldApplyTabDisplayOverride() && !GomodFixes.isWeaveRuntime()) {
                    if (liveOriginalDisplayName == null) {
                        liveOriginalDisplayName = accessor.gomod$getDisplayName();
                    }
                    IChatComponent base = accessor.gomod$getDisplayName();
                    if (base == null) {
                        base = liveOriginalDisplayName;
                    }
                    IChatComponent desiredDisplay = buildTabDisplayComponent(base, info.getGameProfile().getName());
                    IChatComponent currentDisplay = accessor.gomod$getDisplayName();
                    if (!sameChatComponentText(currentDisplay, desiredDisplay)) {
                        accessor.gomod$setDisplayName(desiredDisplay);
                    }
                }
                return;
            }

            applyLiveOverridesReflection(info);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void clearLiveOverrides() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
                return;
            }

            NetworkPlayerInfo info = findLocalPlayerInfo(mc);
            if (info == null) {
                return;
            }
            if (info instanceof NetworkPlayerInfoAccessor) {
                NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
                accessor.gomod$setPlayerTexturesLoaded(false);
                accessor.gomod$setLocationSkin(null);
                accessor.gomod$setLocationCape(null);
                accessor.gomod$setSkinType(null);
                if (!GomodFixes.isWeaveRuntime()) {
                    IChatComponent restore = liveOriginalDisplayName;
                    if (restore == null) {
                        String plain = info.getGameProfile() != null ? info.getGameProfile().getName() : "";
                        restore = new ChatComponentText(plain != null ? plain : "");
                    }
                    accessor.gomod$setDisplayName(restore);
                }
                liveOriginalDisplayName = null;
                return;
            }

            clearLiveOverridesReflection(info);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void resetLiveOverrideReflection() {
        liveOverrideReflectionReady = false;
        liveOverrideReflectionOk = false;
        liveOverrideClass = null;
        liveFieldPlayerTexturesLoaded = null;
        liveFieldLocationSkin = null;
        liveFieldLocationCape = null;
        liveFieldSkinType = null;
        liveFieldDisplayName = null;
        liveOriginalDisplayName = null;
    }

    private static void applyLiveOverridesReflection(NetworkPlayerInfo info) {
        try {
            ensureLiveOverrideReflection(info);
            if (liveOverrideReflectionOk) {
                ResourceLocation desiredCape = getCapeLocation();
                if (liveFieldPlayerTexturesLoaded != null && shouldApplySkinOverride()) {
                    liveFieldPlayerTexturesLoaded.setBoolean(info, true);
                }
                if (liveFieldLocationSkin != null && shouldApplySkinOverride()) {
                    Object current = liveFieldLocationSkin.get(info);
                    if (!(current instanceof ResourceLocation) || !sameTexture((ResourceLocation) current, skinLocation)) {
                        liveFieldLocationSkin.set(info, skinLocation);
                    }
                }
                if (liveFieldLocationCape != null && shouldApplyCapeOverride()) {
                    Object current = liveFieldLocationCape.get(info);
                    if ((current instanceof ResourceLocation)
                            ? !sameTexture((ResourceLocation) current, desiredCape)
                            : desiredCape != null) {
                        liveFieldLocationCape.set(info, desiredCape);
                    }
                }
                if (liveFieldSkinType != null && shouldApplySkinOverride()) {
                    Object current = liveFieldSkinType.get(info);
                    String desired = getSkinType();
                    if (!(current instanceof String) || ((desired == null && current != null)
                            || (desired != null && !desired.equals(current)))) {
                        liveFieldSkinType.set(info, desired);
                    }
                }
            }

            if (liveFieldDisplayName != null && shouldApplyTabDisplayOverride()
                    && !GomodFixes.isWeaveRuntime()) {
                if (liveOriginalDisplayName == null) {
                    Object original = liveFieldDisplayName.get(info);
                    if (original instanceof IChatComponent) {
                        liveOriginalDisplayName = (IChatComponent) original;
                    } else {
                        liveOriginalDisplayName = info.getDisplayName();
                    }
                }
                IChatComponent base = info.getDisplayName();
                if (base == null) {
                    base = liveOriginalDisplayName;
                }
                IChatComponent desiredDisplay = buildTabDisplayComponent(base, info.getGameProfile().getName());
                Object current = liveFieldDisplayName.get(info);
                IChatComponent currentDisplay = current instanceof IChatComponent ? (IChatComponent) current : null;
                if (!sameChatComponentText(currentDisplay, desiredDisplay)) {
                    liveFieldDisplayName.set(info, desiredDisplay);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearLiveOverridesReflection(NetworkPlayerInfo info) {
        try {
            ensureLiveOverrideReflection(info);
            if (liveOverrideReflectionOk) {
                if (liveFieldPlayerTexturesLoaded != null) {
                    liveFieldPlayerTexturesLoaded.setBoolean(info, false);
                }
                if (liveFieldLocationSkin != null) {
                    liveFieldLocationSkin.set(info, null);
                }
                if (liveFieldLocationCape != null) {
                    liveFieldLocationCape.set(info, null);
                }
                if (liveFieldSkinType != null) {
                    liveFieldSkinType.set(info, null);
                }
            }
            if (liveFieldDisplayName != null && !GomodFixes.isWeaveRuntime()) {
                IChatComponent restore = liveOriginalDisplayName;
                if (restore == null) {
                    String plain = info.getGameProfile() != null ? info.getGameProfile().getName() : "";
                    restore = new ChatComponentText(plain != null ? plain : "");
                }
                liveFieldDisplayName.set(info, restore);
                liveOriginalDisplayName = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureLiveOverrideReflection(NetworkPlayerInfo info) {
        if (info == null) {
            return;
        }

        Class<?> cls = info.getClass();
        if (liveOverrideReflectionReady && liveOverrideClass == cls) {
            return;
        }

        liveOverrideReflectionReady = true;
        liveOverrideClass = cls;
        liveOverrideReflectionOk = false;
        liveFieldPlayerTexturesLoaded = null;
        liveFieldLocationSkin = null;
        liveFieldLocationCape = null;
        liveFieldSkinType = null;
        liveFieldDisplayName = null;
        liveOriginalDisplayName = null;
        try {
            liveFieldPlayerTexturesLoaded = findField(cls, "playerTexturesLoaded", "field_178868_i");
            liveFieldLocationSkin = findField(cls, "locationSkin", "field_178865_e");
            liveFieldLocationCape = findField(cls, "locationCape", "field_178862_f");
            liveFieldSkinType = findField(cls, "skinType", "field_178863_g");
            liveFieldDisplayName = findField(cls, "displayName", "field_178872_h");

            java.util.List<java.lang.reflect.Field> rlFields = new java.util.ArrayList<>();
            for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
                for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                    if (f.getType() == net.minecraft.util.ResourceLocation.class) {
                        f.setAccessible(true);
                        rlFields.add(f);
                    }
                }
            }

            if (!rlFields.isEmpty() && (liveFieldLocationSkin == null || liveFieldLocationCape == null)) {
                for (java.lang.reflect.Field f : rlFields) {
                    try {
                        Object value = f.get(info);
                        if (!(value instanceof net.minecraft.util.ResourceLocation)) {
                            continue;
                        }
                        net.minecraft.util.ResourceLocation rl = (net.minecraft.util.ResourceLocation) value;
                        if (liveFieldLocationSkin == null
                                && (sameTexture(rl, realSkinLocation) || sameTexture(rl, realUsernameSkinLocation))) {
                            liveFieldLocationSkin = f;
                        }
                        if (liveFieldLocationCape == null && realCapeLocation != null && sameTexture(rl, realCapeLocation)) {
                            liveFieldLocationCape = f;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (!rlFields.isEmpty()) {
                if (liveFieldLocationSkin == null) {
                    liveFieldLocationSkin = rlFields.get(0);
                }
                if (liveFieldLocationCape == null && rlFields.size() > 1) {
                    liveFieldLocationCape = rlFields.get(1);
                }
            }

            if (liveFieldSkinType == null) {
                for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
                    for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                        if (f.getType() == String.class && f.getName().toLowerCase(Locale.ROOT).contains("skin")) {
                            f.setAccessible(true);
                            liveFieldSkinType = f;
                            break;
                        }
                    }
                    if (liveFieldSkinType != null) {
                        break;
                    }
                }
            }

            if (liveFieldDisplayName == null) {
                for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
                    for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                        if (f.getType() == IChatComponent.class) {
                            f.setAccessible(true);
                            liveFieldDisplayName = f;
                            break;
                        }
                    }
                    if (liveFieldDisplayName != null) {
                        break;
                    }
                }
            }

            liveOverrideReflectionOk = liveFieldLocationSkin != null
                    && liveFieldSkinType != null;
        } catch (Throwable t) {
            liveOverrideReflectionOk = false;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String... names) {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    java.lang.reflect.Field f = current.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static NetworkPlayerInfo findLocalPlayerInfo(Minecraft mc) {
        if (mc == null || mc.getNetHandler() == null) {
            return null;
        }

        UUID lookupUuid = realUuid != null ? realUuid : (mc.thePlayer == null ? null : mc.thePlayer.getUniqueID());
        if (lookupUuid != null) {
            NetworkPlayerInfo byUuid = mc.getNetHandler().getPlayerInfo(lookupUuid);
            if (byUuid != null) {
                return byUuid;
            }
        }

        if (mc.thePlayer == null || mc.getNetHandler().getPlayerInfoMap() == null) {
            return null;
        }

        String fallbackName = realName != null ? realName : mc.thePlayer.getName();
        if (fallbackName == null || fallbackName.isEmpty()) {
            return null;
        }

        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) {
                continue;
            }
            if (fallbackName.equalsIgnoreCase(info.getGameProfile().getName())) {
                return info;
            }
        }

        return null;
    }

    private static void scheduleLiveOverrideApply() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(() -> {
                    applyLiveOverrides();
                    if (!isEnabled()) {
                        return;
                    }
                    KillCounter.markTabCompositionDirty();
                    if (KillCounter.shouldHandleTabQolPackets(mc)) {
                        KillCounter.forceTabRefresh();
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleLiveOverrideClear() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(LocalDisguise::clearLiveOverrides);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applySelectedCapeOptionLocked() {
        if (capeOptions == null || capeOptions.isEmpty() || selectedCapeIndex < 0 || selectedCapeIndex >= capeOptions.size()) {
            capeLocation = null;
            hasCustomCape = false;
            return;
        }

        CapeOption option = capeOptions.get(selectedCapeIndex);
        capeLocation = option.location;
        hasCustomCape = capeLocation != null;
    }

    private static void cacheFrozenKillMessage(String original, String frozen) {
        if (original == null || frozen == null) {
            return;
        }
        if (frozenKillMessageCache.size() >= 256) {
            String firstKey = frozenKillMessageCache.keySet().iterator().next();
            frozenKillMessageCache.remove(firstKey);
        }
        frozenKillMessageCache.put(original, frozen);
    }

    private static boolean sameTexture(ResourceLocation a, ResourceLocation b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        // ResourceLocation.equals compares the normalized domain/path pair. A toString fallback is redundant and,
        // in the global bindTexture hook, allocated strings for virtually every texture rendered each frame.
        return a.equals(b);
    }

    private static boolean sameChatComponentText(IChatComponent a, IChatComponent b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        String af = a.getFormattedText();
        String bf = b.getFormattedText();
        if (af == null || bf == null) {
            return af == bf;
        }
        return af.equals(bf);
    }

    private static String rewriteDisplayText(String input, boolean preferTargetRank) {
        return rewriteDisplayText(input, preferTargetRank, true, true);
    }

    private static String rewriteDisplayText(String input, boolean preferTargetRank,
                                             boolean preserveDetectedRankPrefix, boolean allowInjectedRankPrefix) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }

        boolean suppressPrefix = shouldSuppressInjectedPrefix();
        boolean allowInjected = allowInjectedRankPrefix && !suppressPrefix;
        boolean preserveDetected = preserveDetectedRankPrefix && !suppressPrefix;
        // Replace the server's guild tag (yours) with the disguise target's even when rank prefix is suppressed.
        boolean includeGuildSuffix = isEnabled();

        // Run even when rankKnown is false so non-ranked / unknown API still strips real [MVP+] on party lines.
        if (preferTargetRank && (rankKnown || shouldGrayChatBodyForNonRankedDisguise())) {
            String nameKeyInText = pickLocalDisguiseNameKeyInVisibleText(input);
            if (nameKeyInText == null || nameKeyInText.isEmpty()) {
                nameKeyInText = realName;
            }
            String rewrittenRanked = replaceRankAndNameSegment(input, nameKeyInText, false, includeGuildSuffix, false, true,
                    preserveDetected, allowInjected);
            if (!input.equals(rewrittenRanked)) {
                return rewrittenRanked;
            }
        }

        return replaceStandaloneIgnOccurrences(input, realName, getTargetTabChatDisplayNameForRendering());
    }

    /**
     * Non-Hypixel disguise rendering is deliberately limited to the name substitution. The source string's
     * formatting is left in place so the connected server can apply its own rank/team/chat colors to the new name.
     */
    private static String rewriteNonHypixelNameOnly(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }

        String target = stripFormattingCodes(getTargetTabChatDisplayNameForRendering());
        if (target == null || target.trim().isEmpty()) {
            target = targetName;
        }
        if (target == null || target.trim().isEmpty()) {
            return input;
        }
        target = target.trim();

        String mapped = replaceVisibleNameOnlyKeepingCurrentFormatting(input, realName, target);
        if (!input.equals(mapped)) {
            return mapped;
        }
        return replaceStandaloneIgnOccurrences(input, realName, target);
    }

    /**
     * Replaces {@code originalName} with {@code replacement} only at standalone occurrences (alphanumeric + underscore
     * boundaries). Prevents kit titles such as {@code Shadow Knight's Iron Sword} from being corrupted when
     * {@code realName} matches a substring (e.g. {@code Knight}) and the naive {@link String#replace} path runs during
     * {@link #replaceRenderedText} (FontRenderer / chat).
     */
    /**
     * Blitz kit custom names look like {@code Kit's Item (tier)}. Those strings must not go through generic
     * real-name replacement or common IGN tokens (e.g. {@code Knight}, {@code Iron}) mangle the title.
     */
    private static boolean looksLikeHypixelKitStyledItemDisplayTitle(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        String plain = stripFormattingCodes(input);
        if (plain == null) {
            plain = input;
        }
        plain = plain.replace('\u2019', '\'').replace('\u2018', '\'').trim();
        if (plain.length() < 10 || plain.length() > 120) {
            return false;
        }
        int apost = plain.indexOf("'s ");
        if (apost < 1) {
            return false;
        }
        int open = plain.lastIndexOf('(');
        int close = plain.lastIndexOf(')');
        return open > apost + 3 && close > open && close == plain.length() - 1;
    }

    private static String replaceStandaloneIgnOccurrences(String input, String originalName, String replacement) {
        if (input == null || input.isEmpty() || originalName == null || originalName.isEmpty()) {
            return input;
        }
        if (replacement == null) {
            replacement = "";
        }
        if (originalName.length() < 3) {
            return input.replace(originalName, replacement);
        }
        try {
            String quoted = Pattern.quote(originalName);
            Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + quoted + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
            return p.matcher(input).replaceAll(Matcher.quoteReplacement(replacement));
        } catch (Throwable ignored) {
            return input.replace(originalName, replacement);
        }
    }

    /**
     * Hypixel sometimes prints the disguise IGN in messages instead of the real profile name — match whichever appears.
     */
    private static String pickLocalDisguiseNameKeyInVisibleText(String input) {
        if (input == null || input.isEmpty()) {
            return realName;
        }
        SpanData sd = buildVisibleMapping(input);
        String vis = sd.visibleText;
        if (vis == null) {
            return realName;
        }
        if (realName != null && !realName.isEmpty() && indexOfIgnoreCaseStandalone(vis, realName, 0) >= 0) {
            return realName;
        }
        if (targetName != null && !targetName.isEmpty()
                && (realName == null || !realName.equalsIgnoreCase(targetName))
                && indexOfIgnoreCaseStandalone(vis, targetName, 0) >= 0) {
            return targetName;
        }
        String tabDisp = getTargetTabChatDisplayNameForRendering();
        String tabDispPlain = stripFormattingCodes(tabDisp);
        if (tabDispPlain != null && !tabDispPlain.isEmpty()
                && indexOfIgnoreCaseStandalone(vis, tabDispPlain, 0) >= 0) {
            return tabDispPlain;
        }
        return realName;
    }

    private static boolean shouldSuppressInjectedPrefix() {
        // For generic rendered text, never inject rank prefixes.
        return true;
    }

    private static String rewriteJoinLeaveAnnouncementText(String input) {
        return rewriteBlitzJoinLeaveAnnouncementText(input);
    }

    /**
     * Hypixel Blitz {@code has joined (X/Y)!} / {@code has quit!} lines: force §f (hub queue) or §c (match-map waiting
     * through live match) on the player name for every joiner, not only the disguised account.
     */
    private static String rewriteBlitzJoinLeaveAnnouncementText(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String visible = stripFormattingCodes(input).trim();
        String playerName = extractBlitzJoinLeavePlayerName(visible);
        if (playerName == null || playerName.isEmpty()) {
            return input;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (!GameContext.isBlitzTabDisguiseRankSuppressed(mc)) {
            return input;
        }
        String joinLeaveColor = getSuppressedBlitzJoinLeaveAnnouncementColor(mc);
        if (joinLeaveColor == null || joinLeaveColor.isEmpty()) {
            return input;
        }

        String result = input;
        if (isEnabled() && realName != null && !realName.isEmpty()) {
            String nameKey = pickLocalDisguiseNameKeyInVisibleText(input);
            if (nameKey != null && !nameKey.isEmpty()
                    && indexOfIgnoreCaseStandalone(visible, nameKey, 0) >= 0) {
                String disguised = rewriteSuppressedBlitzFlatColorLine(input, nameKey,
                        getTargetTabChatDisplayNameForRendering(), joinLeaveColor);
                if (!input.equals(disguised)) {
                    result = disguised;
                } else {
                    String swapped = replaceRankAndNameSegment(input, nameKey, false, false, false, true, false, false);
                    if (swapped != null && !swapped.equals(input)) {
                        result = swapped;
                    }
                }
            }
        }

        String visibleAfter = stripFormattingCodes(result).trim();
        String nameToColor = extractBlitzJoinLeavePlayerName(visibleAfter);
        if (nameToColor == null || nameToColor.isEmpty()) {
            return result;
        }
        return rewriteSuppressedBlitzFlatColorLine(result, nameToColor, nameToColor, joinLeaveColor);
    }

    private static String extractBlitzJoinLeavePlayerName(String visible) {
        if (visible == null || visible.isEmpty()) {
            return null;
        }
        int joinIndex = visible.indexOf(" has joined (");
        if (joinIndex > 0 && visible.contains(")!")) {
            return visible.substring(0, joinIndex).trim();
        }
        int quitIndex = visible.indexOf(" has quit!");
        if (quitIndex > 0) {
            return visible.substring(0, quitIndex).trim();
        }
        return null;
    }

    /**
     * Hypixel lobby join line for your account. If disguise target is below MVP+, hide the line.
     * If MVP+ or higher, ensure rank tag + disguise name (like party invites when prefix is missing).
     */
    private static String rewriteHypixelLobbyJoinNotificationText(String input) {
        if (input == null || input.isEmpty() || !isEnabled()) {
            return input;
        }
        if (realName == null || realName.isEmpty() || targetName == null || targetName.isEmpty()) {
            return input;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !isOnHypixelServer(mc)) {
            return input;
        }

        String visibleTrim = stripFormattingCodes(input);
        if (visibleTrim == null) {
            visibleTrim = "";
        } else {
            visibleTrim = visibleTrim.trim();
        }
        String vl = visibleTrim.toLowerCase(Locale.ROOT);
        if (!vl.contains("joined the lobby")) {
            return input;
        }

        boolean hitReal = indexOfIgnoreCase(visibleTrim, realName) >= 0;
        boolean hitTarget = indexOfIgnoreCase(visibleTrim, targetName) >= 0;
        if (!hitReal && !hitTarget) {
            return input;
        }

        if (!isDisguiseRankAtLeastMvpPlus()) {
            return "";
        }

        String nameKey = hitReal ? realName : targetName;
        SpanData sd = buildVisibleMapping(input);
        String vis = sd.visibleText;
        if (vis == null || indexOfIgnoreCase(vis, nameKey) < 0) {
            return input;
        }

        boolean allowInject = getHypixelPrefixMode() != HypixelPrefixMode.HIDE;
        // Strip server's [MVP+] (your real colors) and rebuild with disguise rank + plus color.
        return replaceRankAndNameSegment(input, nameKey, false, false, true, true, false, allowInject);
    }

    /** True when disguise target is MVP+, MVP++, staff, etc. (not Default / VIP / VIP+ / plain MVP). */
    private static boolean isDisguiseRankAtLeastMvpPlus() {
        if (!rankKnown || rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return false;
        }
        if ("VIP".equals(rank) || "VIP+".equals(rank) || "MVP".equals(rank)) {
            return false;
        }
        if ("MVP+".equals(rank) || "MVP++".equals(rank)) {
            return true;
        }
        switch (rank) {
            case "YOUTUBER":
            case "PIG+++":
            case "GM":
            case "ADMIN":
            case "OWNER":
            case "MOJANG":
            case "HELPER":
            case "MOD":
            case "EVENTS":
            case "INNIT":
                return true;
            default:
                return rank.contains("MVP+");
        }
    }

    private static boolean shouldGrayChatBodyForNonRankedDisguise() {
        if (!rankKnown) {
            return true;
        }
        return rank == null || rank.isEmpty() || "Default".equals(rank);
    }

    /** After disguise chat rewrite: gray message text on Hypixel when target has no rank (Default). */
    private static String applyGrayToDisguiseChatBodyIfNeeded(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !isOnHypixelServer(mc)) {
            return formatted;
        }
        if (!shouldGrayChatBodyForNonRankedDisguise()) {
            return formatted;
        }
        if (targetName == null || targetName.isEmpty()) {
            return formatted;
        }

        SpanData sd = buildVisibleMapping(formatted);
        String vis = sd.visibleText;
        if (vis == null || vis.isEmpty()) {
            return formatted;
        }
        int firstColon = vis.indexOf(':');
        if (firstColon < 0) {
            return formatted;
        }
        int nameIdx = indexOfIgnoreCase(vis, targetName);
        if (nameIdx < 0 || nameIdx >= firstColon || nameIdx + targetName.length() > firstColon) {
            return formatted;
        }
        int colonIdx = vis.indexOf(':', nameIdx + targetName.length());
        if (colonIdx != firstColon) {
            return formatted;
        }
        // Gray from the ':' onward (colon + message), matching non-ranked Hypixel chat.
        if (sd.visibleToFormattedStart == null || colonIdx >= sd.visibleToFormattedStart.length) {
            return formatted;
        }
        int fmtStart = sd.visibleToFormattedStart[colonIdx];
        return formatted.substring(0, fmtStart) + EnumChatFormatting.GRAY + formatted.substring(fmtStart);
    }

    /**
     * Hypixel party/guild invite system messages. Replaces your real-account {@code [MVP+]} with the disguise
     * rank tag when you're the inviter ({@code [MVP++] nAku invited …}), and injects the tag when you're the
     * invitee and Hypixel omitted it.
     */
    private static String rewritePartyInviteNotificationText(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }
        if (targetName == null || targetName.isEmpty()) {
            return input;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !isOnHypixelServer(mc)) {
            return input;
        }
        if (getHypixelPrefixMode() == HypixelPrefixMode.HIDE) {
            return input;
        }
        if (!rankKnown) {
            return input;
        }
        String tag = buildRankTag();
        if (tag == null || tag.isEmpty()) {
            return input;
        }

        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        if (visible == null || visible.isEmpty()) {
            return input;
        }
        String lower = visible.toLowerCase(Locale.ROOT);
        if (!lower.contains("invited") || (!lower.contains("party") && !lower.contains("guild"))) {
            return input;
        }

        boolean allowInject = getHypixelPrefixMode() != HypixelPrefixMode.HIDE;

        // Inviter: "[MVP+] nAku invited …" — strip server's bracket/tag and rebuild (fixes wrong ++ / colors).
        if (isChatNameImmediatelyBeforeInvitedWord(visible, targetName)) {
            return replaceRankAndNameSegment(input, targetName, false, false, true, false, false, allowInject);
        }
        if (realName != null && !realName.equalsIgnoreCase(targetName)
                && isChatNameImmediatelyBeforeInvitedWord(visible, realName)) {
            return replaceRankAndNameSegment(input, realName, false, false, true, false, false, allowInject);
        }

        // Invitee (name after "invited"): inject tag only when Hypixel didn't put a rank bracket.
        int nameIdx = indexOfIgnoreCase(visible, targetName);
        if (nameIdx < 0) {
            return input;
        }
        int invIdx = lower.lastIndexOf("invited", nameIdx);
        if (invIdx < 0 || nameIdx < invIdx) {
            return input;
        }

        if (hypixelRankBracketImmediatelyBefore(visible, nameIdx)) {
            return input;
        }

        if (spanData.visibleToFormattedStart == null
                || nameIdx >= spanData.visibleToFormattedStart.length) {
            return input;
        }
        int formattedInsert = spanData.visibleToFormattedStart[nameIdx];
        return input.substring(0, formattedInsert) + tag + " " + input.substring(formattedInsert);
    }

    /** True if {@code name} appears immediately before the word {@code invited} (whitespace only between). */
    private static boolean isChatNameImmediatelyBeforeInvitedWord(String visible, String name) {
        if (visible == null || name == null || name.isEmpty()) {
            return false;
        }
        int from = 0;
        while (from <= visible.length()) {
            int idx = indexOfIgnoreCase(visible, name, from);
            if (idx < 0) {
                return false;
            }
            int after = idx + name.length();
            while (after < visible.length() && visible.charAt(after) == ' ') {
                after++;
            }
            if (after + 7 <= visible.length() && visible.regionMatches(true, after, "invited", 0, 7)) {
                return true;
            }
            from = idx + 1;
        }
        return false;
    }

    private static boolean hypixelRankBracketImmediatelyBefore(String visible, int nameStart) {
        int i = nameStart - 1;
        while (i >= 0 && Character.isWhitespace(visible.charAt(i))) {
            i--;
        }
        if (i < 0 || visible.charAt(i) != ']') {
            return false;
        }
        int open = visible.lastIndexOf('[', i);
        if (open < 0 || open >= nameStart) {
            return false;
        }
        String inner = visible.substring(open + 1, i).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return inner.contains("VIP") || inner.contains("MVP") || inner.contains("YOUTUBE")
                || inner.contains("YOUTUBER") || inner.contains("HELPER") || inner.contains("MOD")
                || inner.contains("ADMIN") || inner.contains("OWNER") || inner.contains("MOJANG")
                || inner.contains("GM") || inner.contains("EVENT") || inner.contains("NITRO")
                || inner.contains("PIG");
    }

    /**
     * Game broadcasts like {@code nAku found the Blitz Star!} or {@code nAku BLITZ STAR! NINJA!} have no
     * {@code name:} chat prefix; Hypixel still colors the disguised IGN with your real rank. Rebuild the
     * rank + name segment using the disguise target's presentation (same as generic display text / kit lines:
     * do not keep the server's real-account rank bracket before the disguised IGN).
     */
    private static String rewriteBlitzStarAnnouncementText(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }
        String plain = stripFormattingCodes(input);
        if (plain == null) {
            plain = "";
        }
        if (!looksLikeBlitzStarAnnouncementFast(plain)) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        if (visible == null || visible.isEmpty()) {
            return input;
        }
        // Prefer the source IGN and require username boundaries. A short disguise alias can be a substring
        // of the real IGN (nAku -> aku), and must not prevent the actual source name from being replaced.
        String speakerNameInText = realName;
        int nameIndex = indexOfIgnoreCaseStandalone(visible, realName, 0);
        if (nameIndex < 0 && targetName != null && !targetName.isEmpty()) {
            nameIndex = indexOfIgnoreCaseStandalone(visible, targetName, 0);
            if (nameIndex >= 0) speakerNameInText = targetName;
        }
        if (nameIndex < 0) {
            return input;
        }
        int colonAfterName = visible.indexOf(':', nameIndex + speakerNameInText.length());
        if (colonAfterName >= 0) {
            return input;
        }
        // Match rewriteDisplayText: never preserve Hypixel's [MVP+] etc. for your real account on this broadcast,
        // and do not inject a rank tag (kit/action-bar style is plain disguised name + target colors).
        return replaceRankAndNameSegment(input, speakerNameInText, false, false, false, true, false, false);
    }

    private static String rewriteChatSpeakerText(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }

        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int firstColon = visible.indexOf(':');
        if (firstColon < 0) {
            return input;
        }
        // Match only the chat speaker (before first ':'), not a mention of your name in another player's message body.
        String speakerRegion = visible.substring(0, firstColon);
        String speakerNameInText = realName;
        int nameIndex = indexOfIgnoreCase(speakerRegion, realName);
        if (nameIndex < 0 && targetName != null && !targetName.isEmpty() && !realName.equalsIgnoreCase(targetName)) {
            nameIndex = indexOfIgnoreCase(speakerRegion, targetName);
            if (nameIndex >= 0) {
                speakerNameInText = targetName;
            }
        }
        if (nameIndex < 0) {
            return input;
        }
        int colonIndex = visible.indexOf(':', nameIndex + speakerNameInText.length());
        if (colonIndex != firstColon) {
            return input;
        }

        boolean isPartyChat = isPartyChatLine(visible, nameIndex);
        boolean isGuildChat = isGuildChatLine(visible, nameIndex);
        boolean isOfficerChat = isOfficerChatLine(visible, nameIndex);
        boolean isGuildOrOfficerChat = isGuildChat || isOfficerChat;
        boolean isSocialChannel = isPartyChat || isGuildOrOfficerChat;

        String between = visible.substring(nameIndex + speakerNameInText.length(), colonIndex).trim();
        boolean includeGuildSuffix = false;
        if (!between.isEmpty()) {
            if (isSocialChannel) {
                // Lobbies often add extra tokens between the IGN and ':' (not always a [guild] tag).
                if (isLikelyGuildSuffix(between)) {
                    includeGuildSuffix = true;
                }
            } else if (!isLikelyGuildSuffix(between)) {
                return input;
            } else {
                includeGuildSuffix = true;
            }
        }

        boolean includeKillPrefix = shouldShowKillCounterPrefix();
        if (isPartyChat || isGuildOrOfficerChat) {
            includeKillPrefix = false;
        }
        // Blitz chat speaker prefixes ([SHOUT], [TEAM], [SPECTATOR]) must be preserved; otherwise our
        // bracket-chain consumer can swallow those tokens during rewrite and leave only the kill bracket.
        String lowerSpeakerRegion = speakerRegion.toLowerCase(Locale.ROOT);
        boolean hasBlitzChatSpeakerPrefix = lowerSpeakerRegion.contains("[shout]")
                || lowerSpeakerRegion.contains("[team]")
                || lowerSpeakerRegion.contains("[spectator]");
        // Rebuild the target's kill/rank prefix in the same pass, even when Hypixel omits a spectator kill
        // bracket. The channel-aware consumer below preserves [SHOUT]/[TEAM]/[SPECTATOR].
        int rankPlainStart = findImmediateRankPrefixStart(visible, nameIndex);
        int killPrefixStart = findImmediateKillPrefixStart(visible, rankPlainStart >= 0 ? rankPlainStart : nameIndex);
        boolean hasRankPrefix = rankPlainStart >= 0 && rankPlainStart != killPrefixStart;
        // Party chat (Party >): always rebuild rank from disguise (Hypixel may send [MVP+] or omit ++); same as guild channels.
        boolean allowInjectedRankPrefix = hasRankPrefix || isGuildOrOfficerChat || isPartyChat;

        String guildSuffixOverride = null;
        if (isGuildOrOfficerChat && targetGuildRank != null && !targetGuildRank.isEmpty()) {
            guildSuffixOverride = buildGuildRankSuffix();
        }

        // Always rebuild rank from disguise settings (no "keep server's bracket" mode).
        boolean preserveDetectedRankPrefix = false;
        boolean consumeExistingKillPrefix = true;
        if (hasBlitzChatSpeakerPrefix) {
            consumeExistingKillPrefix = false;
        }
        String rewritten = replaceRankAndNameSegment(
                input,
                speakerNameInText,
                includeKillPrefix,
                includeGuildSuffix,
                consumeExistingKillPrefix,
                false, // preserveServerFormattingWithoutRank
                preserveDetectedRankPrefix,
                allowInjectedRankPrefix,
                guildSuffixOverride,
                hasBlitzChatSpeakerPrefix
        );
        return applyGrayToDisguiseChatBodyIfNeeded(rewritten);
    }

    private static String rewriteEndgameSummaryLine(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }

        String placementProbe = stripFormattingCodes(input);
        if (placementProbe == null || !ENDGAME_PLACEMENT_PREFIX.matcher(placementProbe.trim()).find()) {
            return input;
        }

        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        if (visible == null || visible.isEmpty()) {
            return input;
        }

        String trimmed = visible.trim();
        if (!ENDGAME_PLACEMENT_PREFIX.matcher(trimmed).find()) {
            return input;
        }

        String nameKey = pickLocalDisguiseNameKeyInVisibleText(input);
        if (nameKey == null || nameKey.isEmpty()
                || indexOfIgnoreCaseStandalone(visible, nameKey, 0) < 0) {
            return input;
        }
        return replaceRankAndNameSegment(input, nameKey, false, false, false, true, true, true);
    }

    private static String rewriteKillMessageText(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }

        // Hot path: FontRenderer calls replaceRenderedText for tiny chat fragments thousands of times per frame.
        // Do not build SpanData / victim logic unless this is actually a kill line.
        if (!looksLikeKillFeedLineFast(input)) {
            return input;
        }

        String cached = frozenKillMessageCache.get(input);
        if (cached != null) {
            return cached;
        }

        // Victim appears before the kill marker — recolor/replace using disguise target rank (e.g. §7 for no rank),
        // not the server's coloring for your real account.
        String working = applyKillFeedVictimDisguiseIfNeeded(input);

        SpanData originalSpanData = buildVisibleMapping(working);
        String originalVisible = originalSpanData.visibleText;
        String markerText = findKillFeedByMarkerInVisible(originalVisible);
        if (markerText == null || markerText.isEmpty()) {
            return working;
        }
        int markerIndex = originalVisible.indexOf(markerText);
        if (markerIndex < 0) {
            return working;
        }

        int killerSearchStart = markerIndex + markerText.length();
        String killerInMessage = pickLocalKillFeedKillerNameInVisible(originalVisible, killerSearchStart);
        if (killerInMessage == null) {
            return working;
        }

        // Kill feed: strip Hypixel rank brackets before the killer name; show only target IGN with rank color.
        String rewritten = replaceKillFeedKillerSegment(working, killerInMessage, markerText);
        SpanData spanData = buildVisibleMapping(rewritten);
        String visible = spanData.visibleText;
        String visibleMarker = findKillFeedByMarkerInVisible(visible);
        if (visibleMarker == null || visibleMarker.isEmpty()) {
            cacheFrozenKillMessage(input, rewritten);
            return rewritten;
        }
        int visibleMarkerIndex = visible.indexOf(visibleMarker);
        if (visibleMarkerIndex < 0) {
            cacheFrozenKillMessage(input, rewritten);
            return rewritten;
        }

        String disguisePlain = getTargetTabChatDisplayNamePlain();
        int killerNameIndex = indexOfIgnoreCase(visible, disguisePlain, visibleMarkerIndex + visibleMarker.length());
        if (killerNameIndex < 0 && targetName != null && !targetName.isEmpty()) {
            killerNameIndex = indexOfIgnoreCase(visible, targetName, visibleMarkerIndex + visibleMarker.length());
        }
        if (killerNameIndex < 0) {
            cacheFrozenKillMessage(input, rewritten);
            return rewritten;
        }

        int killerPlainLen = disguisePlain.isEmpty()
                ? (targetName == null ? 0 : targetName.length())
                : disguisePlain.length();
        int killSuffixVisibleStart = visible.indexOf("(Kill #", killerNameIndex + killerPlainLen);
        int punctuationVisibleIndex = findFirstKillMessagePunctuation(visible, killerNameIndex + killerPlainLen);
        int killSuffixVisibleEnd = killSuffixVisibleStart >= 0 ? visible.indexOf(')', killSuffixVisibleStart) : -1;

        Integer displayedKillCount = getTournamentKillCount();
        if (shouldShowTournamentKillCounter() && displayedKillCount != null) {
            String suffixText = "(Kill #" + formatNumberWithGrouping(displayedKillCount) + ")";
            int formattedInsertionIndex;
            boolean replacingExistingSuffix = killSuffixVisibleStart >= 0 && killSuffixVisibleEnd >= killSuffixVisibleStart;
            if (killSuffixVisibleStart >= 0 && killSuffixVisibleEnd >= killSuffixVisibleStart) {
                formattedInsertionIndex = spanData.visibleToFormattedStart[killSuffixVisibleStart];
            } else if (punctuationVisibleIndex >= 0) {
                formattedInsertionIndex = spanData.visibleToFormattedStart[punctuationVisibleIndex];
            } else {
                formattedInsertionIndex = rewritten.length();
            }

            int formattedSuffixEnd = (killSuffixVisibleStart >= 0 && killSuffixVisibleEnd >= killSuffixVisibleStart)
                    ? spanData.visibleToFormattedEnd[killSuffixVisibleEnd]
                    : formattedInsertionIndex;
            String restoreFormatting = getFormattingForContinuation(rewritten, formattedInsertionIndex);

            String frozen = rewritten.substring(0, formattedInsertionIndex)
                    + EnumChatFormatting.GOLD
                    + (replacingExistingSuffix ? suffixText : " " + suffixText)
                    + restoreFormatting
                    + rewritten.substring(formattedSuffixEnd);
            cacheFrozenKillMessage(input, frozen);
            return frozen;
        }

        if (killSuffixVisibleStart >= 0 && killSuffixVisibleEnd >= killSuffixVisibleStart) {
            int removalStartVisible = killSuffixVisibleStart;
            if (removalStartVisible > 0 && visible.charAt(removalStartVisible - 1) == ' ') {
                removalStartVisible--;
            }
            int formattedRemovalStart = spanData.visibleToFormattedStart[removalStartVisible];
            int formattedRemovalEnd = spanData.visibleToFormattedEnd[killSuffixVisibleEnd];
            String frozen = rewritten.substring(0, formattedRemovalStart) + rewritten.substring(formattedRemovalEnd);
            cacheFrozenKillMessage(input, frozen);
            return frozen;
        }

        cacheFrozenKillMessage(input, rewritten);
        return rewritten;
    }

    /**
     * Hypixel often splits kill lines into colored siblings; rewrite a lone real-IGN fragment (e.g. {@code Mike!}).
     */
    private static String rewriteLocalDisguiseStandaloneIgnFragment(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }
        if (looksLikeKillFeedLineFast(input)) {
            return input;
        }
        String visible = stripFormattingCodes(input);
        if (visible == null) {
            return input;
        }
        visible = visible.trim();
        if (visible.isEmpty() || visible.length() > 20) {
            return input;
        }
        String matched = null;
        if (visible.equalsIgnoreCase(realName)) {
            matched = realName;
        } else if (visible.regionMatches(true, 0, realName, 0, realName.length())) {
            String tail = visible.substring(realName.length());
            if (tail.matches("[!.,;:? ]*")) {
                matched = realName;
            }
        }
        if (matched == null) {
            return input;
        }
        String originalNameFormatting = getActiveFormattingBefore(input, 0);
        String replacement = buildKillFeedDisguisedNameReplacement(originalNameFormatting);
        SpanData spanData = buildVisibleMapping(input);
        int nameIdx = indexOfIgnoreCase(spanData.visibleText, matched, 0);
        if (nameIdx < 0) {
            return input;
        }
        int formattedStart = spanData.visibleToFormattedStart[nameIdx];
        int formattedEnd = spanData.visibleToFormattedEnd[nameIdx + matched.length() - 1];
        String tail = input.substring(formattedEnd);
        return input.substring(0, formattedStart) + replacement + tail;
    }

    /**
     * Taunt broadcasts have no chat colon or kill-feed marker, so they used to fall through to the generic
     * replacement path and retain Hypixel's light-red self-name color. Treat the name as a proper speaker instead.
     */
    private static String rewriteTauntAnnouncementText(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        if (visible == null) {
            return input;
        }
        int performedIndex = indexOfIgnoreCase(visible, "performed the");
        if (performedIndex <= 0 || !containsIgnoreCase(visible, "taunt!")) {
            return input;
        }

        String speakerName = null;
        if (indexOfIgnoreCaseStandalone(visible, realName, 0) >= 0) {
            speakerName = realName;
        } else if (targetName != null && !targetName.isEmpty()
                && indexOfIgnoreCaseStandalone(visible, targetName, 0) >= 0) {
            speakerName = targetName;
        }
        if (speakerName == null) {
            return input;
        }
        int nameIndex = indexOfIgnoreCaseStandalone(visible, speakerName, 0);
        if (nameIndex < 0 || nameIndex >= performedIndex) {
            return input;
        }

        // Rebuild the name exactly as a normal chat speaker: target rank tag and target rank color.
        // Taunt broadcasts can arrive as "[real-rank] [nick-rank] Nick performed..." while disguised; consume the
        // Hypixel rank prefix chain before the visible speaker so the rebuilt target tag does not stack on top.
        return replaceRankAndNameSegment(input, speakerName, false, false, false, false, false, true, null, true);
    }

    private static String rewriteBlitzRektFinisherHologramText(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        if (spanData.visibleText == null) return input;
        Matcher hologram = BLITZ_REKT_FINISHER_HOLOGRAM_PATTERN.matcher(spanData.visibleText);
        if (!hologram.matches()) return input;
        String displayedSpeaker = hologram.group(1);
        String targetDisplayPlain = getTargetTabChatDisplayNamePlain();
        boolean localDisguiseSpeaker = realName.equalsIgnoreCase(displayedSpeaker)
                || (targetName != null && targetName.equalsIgnoreCase(displayedSpeaker))
                || (!targetDisplayPlain.isEmpty() && targetDisplayPlain.equalsIgnoreCase(displayedSpeaker));
        if (!localDisguiseSpeaker) return input;

        int visibleStart = hologram.start(1);
        int visibleEnd = hologram.end(1);
        int formattedStart = spanData.visibleToFormattedStart[visibleStart];
        int formattedEnd = spanData.visibleToFormattedEnd[visibleEnd - 1];
        String originalNameFormatting = getActiveFormattingBefore(input, formattedStart);
        String replacement = buildKillFeedDisguisedNameReplacement(originalNameFormatting);
        return input.substring(0, formattedStart) + replacement + input.substring(formattedEnd);
    }

    private static String pickLocalKillFeedKillerNameInVisible(String visible, int killerSearchStart) {
        if (visible == null || killerSearchStart < 0) {
            return null;
        }
        if (indexOfIgnoreCase(visible, realName, killerSearchStart) >= 0) {
            return realName;
        }
        if (targetName != null && !targetName.isEmpty() && !realName.equalsIgnoreCase(targetName)
                && indexOfIgnoreCase(visible, targetName, killerSearchStart) >= 0) {
            return targetName;
        }
        String tabDispPlain = getTargetTabChatDisplayNamePlain();
        if (!tabDispPlain.isEmpty() && indexOfIgnoreCase(visible, tabDispPlain, killerSearchStart) >= 0) {
            return tabDispPlain;
        }
        return null;
    }

    /**
     * When we are the victim in a kill feed line, Hypixel may still color the name with our real rank.
     * Rebuild that segment using the disguise target's rank (gray for Default).
     */
    private static String applyKillFeedVictimDisguiseIfNeeded(String input) {
        if (input == null || input.isEmpty() || !isEnabled() || realName == null || realName.isEmpty()) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        String markerText = findKillFeedByMarkerInVisible(visible);
        if (markerText == null || markerText.isEmpty()) {
            return input;
        }
        int markerIdx = visible.indexOf(markerText);
        if (markerIdx < 0) {
            return input;
        }
        int vReal = indexOfIgnoreCase(visible, realName, 0);
        if (vReal >= 0 && vReal < markerIdx) {
            return replaceKillFeedVictimSegment(input, realName, markerText);
        }
        if (targetName != null && !targetName.isEmpty() && !realName.equalsIgnoreCase(targetName)) {
            int vTgt = indexOfIgnoreCase(visible, targetName, 0);
            if (vTgt >= 0 && vTgt < markerIdx) {
                return replaceKillFeedVictimSegment(input, targetName, markerText);
            }
        }
        String tabDispPlain = getTargetTabChatDisplayNamePlain();
        if (!tabDispPlain.isEmpty()) {
            int vDisp = indexOfIgnoreCase(visible, tabDispPlain, 0);
            if (vDisp >= 0 && vDisp < markerIdx) {
                return replaceKillFeedVictimSegment(input, tabDispPlain, markerText);
            }
        }
        return input;
    }

    private static String replaceKillFeedVictimSegment(String input, String nameInMessage, String markerText) {
        if (input == null || nameInMessage == null || nameInMessage.isEmpty() || markerText == null) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int markerIdx = visible.indexOf(markerText);
        if (markerIdx < 0) {
            return input;
        }
        int nameIdx = indexOfIgnoreCase(visible, nameInMessage, 0);
        if (nameIdx < 0 || nameIdx >= markerIdx) {
            return input;
        }
        int formattedStart = spanData.visibleToFormattedStart[nameIdx];
        int formattedEnd = spanData.visibleToFormattedEnd[nameIdx + nameInMessage.length() - 1];
        int chainStart = findLeftmostBracketPrefixChainStart(visible, nameIdx);
        if (chainStart >= 0) {
            formattedStart = Math.min(formattedStart, spanData.visibleToFormattedStart[chainStart]);
        }
        String originalNameFormatting = getActiveFormattingBefore(input, spanData.visibleToFormattedStart[nameIdx]);
        String replacement = buildKillFeedDisguisedNameReplacement(originalNameFormatting);
        return input.substring(0, formattedStart) + replacement + input.substring(formattedEnd);
    }

    /**
     * Kill-feed only: colored disguise target name, no {@code [MVP+]} / rank bracket (Hypixel or injected).
     */
    private static String buildKillFeedDisguisedNameReplacement(String originalNameFormatting) {
        String disp = getTargetTabChatDisplayNameForRendering();
        if (disp != null && disp.indexOf('\u00a7') >= 0) {
            return disp + EnumChatFormatting.RESET;
        }
        String nameFormatting = mergeColorWithExistingStyles(getUsernameColor(rank, monthlyRankColor), originalNameFormatting);
        if (nameFormatting == null || nameFormatting.isEmpty()) {
            nameFormatting = EnumChatFormatting.GRAY.toString();
        }
        String safeTarget = disp != null ? disp : "";
        return nameFormatting + safeTarget + EnumChatFormatting.RESET;
    }

    private static String replaceKillFeedKillerSegment(String input, String killerNameInMessage, String markerText) {
        if (input == null || killerNameInMessage == null || killerNameInMessage.isEmpty()
                || markerText == null || markerText.isEmpty()) {
            return input;
        }
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int markerIdx = visible.indexOf(markerText);
        if (markerIdx < 0) {
            return input;
        }
        int nameIdx = indexOfIgnoreCase(visible, killerNameInMessage, markerIdx + markerText.length());
        if (nameIdx < 0) {
            return input;
        }
        int formattedStart = spanData.visibleToFormattedStart[nameIdx];
        int formattedEnd = spanData.visibleToFormattedEnd[nameIdx + killerNameInMessage.length() - 1];
        int chainStart = findLeftmostBracketPrefixChainStart(visible, nameIdx);
        if (chainStart >= 0) {
            formattedStart = Math.min(formattedStart, spanData.visibleToFormattedStart[chainStart]);
        }
        String originalNameFormatting = getActiveFormattingBefore(input, spanData.visibleToFormattedStart[nameIdx]);
        String replacement = buildKillFeedDisguisedNameReplacement(originalNameFormatting);
        return input.substring(0, formattedStart) + replacement + input.substring(formattedEnd);
    }

    private static String buildRankedName(String name) {
        String safeName = name == null ? "" : name;
        String usernameColor = getUsernameColor(rank, monthlyRankColor);
        String tag = buildRankTag();
        if (tag == null || tag.isEmpty()) {
            return usernameColor + safeName + EnumChatFormatting.RESET;
        }
        return tag + " " + usernameColor + safeName + EnumChatFormatting.RESET;
    }

    private static String buildRankTag() {
        if (hypixelCustomRankTagActive && hypixelCustomRankTagValue != null && !hypixelCustomRankTagValue.isEmpty()) {
            return hypixelCustomRankTagValue;
        }
        if (rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return "";
        }

        if ("MVP".equals(rank)) {
            String baseColor = usesConfiguredHypixelRankPresetColors()
                    ? getMVPPlusPlusColor(monthlyRankColor)
                    : EnumChatFormatting.AQUA + "";
            return baseColor + "[MVP]";
        }

        if ("MVP+".equals(rank)) {
            String baseColor = usesConfiguredHypixelRankPresetColors()
                    ? getMVPPlusPlusColor(monthlyRankColor)
                    : EnumChatFormatting.AQUA + "";
            String plusColor = getMVPPlusColor(rankPlusColor);
            return baseColor + "[MVP" + plusColor + "+" + baseColor + "]";
        }

        if ("MVP++".equals(rank)) {
            String baseColor = getMVPPlusPlusColor(monthlyRankColor);
            String plusColor = getMVPPlusPlusSignsColor(rankPlusColor);
            return baseColor + "[MVP" + plusColor + "++" + baseColor + "]";
        }

        if ("VIP+".equals(rank)) {
            String baseColor = EnumChatFormatting.GREEN + "";
            return baseColor + "[VIP" + EnumChatFormatting.GOLD + "+" + baseColor + "]";
        }

        if ("YOUTUBER".equals(rank)) {
            return EnumChatFormatting.RED + "[" + EnumChatFormatting.WHITE + "YOUTUBE" + EnumChatFormatting.RED + "]";
        }

        if ("PIG+++".equals(rank)) {
            return EnumChatFormatting.LIGHT_PURPLE + "[PIG" + EnumChatFormatting.AQUA + "+++" + EnumChatFormatting.LIGHT_PURPLE + "]";
        }

        if ("STAFF".equals(rank)) {
            return EnumChatFormatting.RED + "[" + EnumChatFormatting.GOLD + "\u12de"
                    + EnumChatFormatting.RED + "]";
        }

        String rankColor = getRankColor(rank);
        return rankColor + "[" + getRenderedRankLabel(rank) + "]";
    }

    private static String getUsernameColor(String currentRank, String currentMonthlyRankColor) {
        if (currentRank == null || currentRank.isEmpty() || "Default".equals(currentRank)) {
            return EnumChatFormatting.GRAY + "";
        }

        switch (currentRank) {
            case "MVP++":
                return getMVPPlusPlusColor(currentMonthlyRankColor);
            case "MVP+":
            case "MVP":
                return EnumChatFormatting.AQUA + "";
            case "VIP+":
            case "VIP":
                return EnumChatFormatting.GREEN + "";
            case "YOUTUBER":
            case "OWNER":
            case "ADMIN":
            case "STAFF":
                return EnumChatFormatting.RED + "";
            case "GM":
                return EnumChatFormatting.DARK_GREEN + "";
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD + "";
            case "INNIT":
            case "PIG+++":
                return EnumChatFormatting.LIGHT_PURPLE + "";
            default:
                return getRankColor(currentRank);
        }
    }

    private static String getRankColor(String currentRank) {
        if (currentRank == null) {
            return EnumChatFormatting.YELLOW + "";
        }
        switch (currentRank) {
            case "OWNER":
                return EnumChatFormatting.RED + "";
            case "ADMIN":
                return EnumChatFormatting.RED + "";
            case "STAFF":
                return EnumChatFormatting.RED + "";
            case "GM":
                return EnumChatFormatting.DARK_GREEN + "";
            case "YOUTUBER":
                return EnumChatFormatting.RED + "";
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD + "";
            case "PIG+++":
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE + "";
            case "MVP":
            case "MVP+":
                return EnumChatFormatting.AQUA + "";
            case "VIP+":
            case "VIP":
                return EnumChatFormatting.GREEN + "";
            case "Default":
                return EnumChatFormatting.GRAY + "";
            default:
                return EnumChatFormatting.YELLOW + "";
        }
    }

    private static boolean usesConfiguredHypixelRankPresetColors() {
        return enabled && !"TARGET".equalsIgnoreCase(ConfigManager.getDisguiseHypixelRankPreset());
    }

    private static String getRenderedRankLabel(String currentRank) {
        if ("YOUTUBER".equals(currentRank)) {
            return "YOUTUBE";
        }
        return currentRank;
    }

    private static String getMVPPlusColor(String plusColor) {
        if (plusColor == null || plusColor.isEmpty()) {
            return EnumChatFormatting.GOLD + "";
        }
        switch (plusColor) {
            case "RED":
                return EnumChatFormatting.RED + "";
            case "BLUE":
                return EnumChatFormatting.BLUE + "";
            case "GREEN":
                return EnumChatFormatting.GREEN + "";
            case "YELLOW":
                return EnumChatFormatting.YELLOW + "";
            case "GOLD":
                return EnumChatFormatting.GOLD + "";
            case "AQUA":
                return EnumChatFormatting.AQUA + "";
            case "LIGHT_PURPLE":
                return EnumChatFormatting.LIGHT_PURPLE + "";
            case "DARK_PURPLE":
                return EnumChatFormatting.DARK_PURPLE + "";
            case "DARK_AQUA":
                return EnumChatFormatting.DARK_AQUA + "";
            case "DARK_GREEN":
                return EnumChatFormatting.DARK_GREEN + "";
            case "DARK_RED":
                return EnumChatFormatting.DARK_RED + "";
            case "DARK_BLUE":
                return EnumChatFormatting.DARK_BLUE + "";
            case "WHITE":
                return EnumChatFormatting.WHITE + "";
            case "GRAY":
                return EnumChatFormatting.GRAY + "";
            case "DARK_GRAY":
                return EnumChatFormatting.DARK_GRAY + "";
            case "BLACK":
                return EnumChatFormatting.BLACK + "";
            default:
                return EnumChatFormatting.GOLD + "";
        }
    }

    private static String getMVPPlusPlusColor(String color) {
        if (color == null || color.isEmpty()) {
            return EnumChatFormatting.GOLD + "";
        }
        switch (color) {
            case "DEFAULT":
                return EnumChatFormatting.GOLD + "";
            case "DARK_PURPLE":
                return EnumChatFormatting.DARK_PURPLE + "";
            case "LIGHT_PURPLE":
                return EnumChatFormatting.LIGHT_PURPLE + "";
            case "DARK_AQUA":
                return EnumChatFormatting.DARK_AQUA + "";
            case "AQUA":
                return EnumChatFormatting.AQUA + "";
            case "RED":
                return EnumChatFormatting.RED + "";
            case "GOLD":
                return EnumChatFormatting.GOLD + "";
            case "GREEN":
                return EnumChatFormatting.GREEN + "";
            case "YELLOW":
                return EnumChatFormatting.YELLOW + "";
            case "WHITE":
                return EnumChatFormatting.WHITE + "";
            case "BLUE":
                return EnumChatFormatting.BLUE + "";
            case "DARK_GREEN":
                return EnumChatFormatting.DARK_GREEN + "";
            case "DARK_RED":
                return EnumChatFormatting.DARK_RED + "";
            case "DARK_GRAY":
                return EnumChatFormatting.DARK_GRAY + "";
            case "BLACK":
                return EnumChatFormatting.BLACK + "";
            case "DARK_BLUE":
                return EnumChatFormatting.DARK_BLUE + "";
            default:
                return EnumChatFormatting.GOLD + "";
        }
    }

    private static String getMVPPlusPlusSignsColor(String color) {
        return getMVPPlusPlusColor(color);
    }

    /** Bracket preview for disguise settings (official MVP+), same formatting as {@link #buildRankTag()}. */
    public static String previewOfficialMvpPlusBracket(String plusColorConfigKey) {
        return previewOfficialMvpPlusBracket(plusColorConfigKey, ConfigManager.getDisguiseHypixelMvpPlusBracketBaseColor());
    }

    public static String previewOfficialMvpPlusBracket(String plusColorConfigKey, String bracketBaseKey) {
        String baseColor = getMVPPlusPlusColor(bracketBaseKey);
        String plusColor = getMVPPlusColor(plusColorConfigKey);
        return baseColor + "[MVP" + plusColor + "+" + baseColor + "]";
    }

    public static String previewOfficialMvpBracket(String bracketBaseKey) {
        return getMVPPlusPlusColor(bracketBaseKey) + "[MVP]";
    }

    /** &nbsp;→§ for live custom-rank GUI preview (does not persist). */
    public static String translateDisguiseAmpersandColors(String raw) {
        return translateAmpersandToSectionSigns(raw);
    }

    /** Bracket preview for disguise settings (official MVP++), same formatting as {@link #buildRankTag()}. */
    public static String previewOfficialMvpPlusPlusBracket(String baseColorKey, String signColorKey) {
        String baseColor = getMVPPlusPlusColor(baseColorKey);
        String plusColor = getMVPPlusPlusSignsColor(signColorKey);
        return baseColor + "[MVP" + plusColor + "++" + baseColor + "]";
    }

    /**
     * Full rank bracket + colored name as in TAB/chat, from saved disguise settings only (works before Apply).
     */
    /**
     * TAB-style colored name for list previews when no draft nick is passed in: saved nick setup,
     * active nick disguise display, or {@code Username}.
     */
    public static synchronized String getHypixelPrefixPreviewDisplayName() {
        if (isEnabled()) {
            String disp = getTargetTabChatDisplayNameForRendering();
            if (disp != null && !disp.trim().isEmpty()) {
                return disp.trim();
            }
            if (targetName != null && !targetName.trim().isEmpty()) {
                return targetName.trim();
            }
        }
        String raw = ConfigManager.getDisguiseNickSetupLastNick();
        if (raw != null && !raw.trim().isEmpty()) {
            String plain = stripToPlainNickForOfflineUuid(raw.trim());
            if (!plain.isEmpty()) {
                String t = translateAmpersandToSectionSigns(raw.trim());
                return (t != null && !t.isEmpty()) ? t : plain;
            }
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
                String self = mc.thePlayer.getGameProfile().getName();
                if (self != null && !self.trim().isEmpty()) {
                    return self.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "Username";
    }

    /**
     * Colored rank bracket only (official presets), for catalog rows — same style as in-game TAB tag.
     */
    /** @param mvpBracketBase {@code [MVP]} / MVP+ bracket base (MVP++ palette); {@code mvpPlusSign} MVP+ plus color only */
    public static String previewOfficialRankBracketForCatalog(String preset, String mvpBracketBase,
            String mvpPlusSign, String mvpPpBase, String mvpPpSign) {
        if (preset == null) {
            return "";
        }
        switch (preset) {
            case "NONE":
                return "";
            case "VIP":
                return EnumChatFormatting.GREEN + "[VIP]";
            case "VIP_PLUS":
                return EnumChatFormatting.GREEN + "[VIP" + EnumChatFormatting.GOLD + "+" + EnumChatFormatting.GREEN + "]";
            case "MVP":
                return previewOfficialMvpBracket(
                        mvpBracketBase != null && !mvpBracketBase.isEmpty() ? mvpBracketBase : "AQUA");
            case "MVP_PLUS":
                return previewOfficialMvpPlusBracket(
                        mvpPlusSign != null && !mvpPlusSign.isEmpty() ? mvpPlusSign : "GOLD",
                        mvpBracketBase != null && !mvpBracketBase.isEmpty() ? mvpBracketBase : "AQUA");
            case "MVP_PLUS_PLUS":
                return previewOfficialMvpPlusPlusBracket(
                        mvpPpBase != null && !mvpPpBase.isEmpty() ? mvpPpBase : "GOLD",
                        mvpPpSign != null && !mvpPpSign.isEmpty() ? mvpPpSign : "GOLD");
            case "YOUTUBER":
                return EnumChatFormatting.RED + "[" + EnumChatFormatting.WHITE + "YOUTUBE" + EnumChatFormatting.RED + "]";
            case "OWNER":
            case "ADMIN":
                return EnumChatFormatting.RED + "[" + preset + "]";
            case "GM":
                return EnumChatFormatting.DARK_GREEN + "[GM]";
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD + "[" + preset + "]";
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE + "[INNIT]";
            case "PIG_PLUS_PLUS_PLUS":
                return EnumChatFormatting.LIGHT_PURPLE + "[PIG" + EnumChatFormatting.AQUA + "+++"
                        + EnumChatFormatting.LIGHT_PURPLE + "]";
            default:
                return EnumChatFormatting.WHITE + "[" + preset + "]";
        }
    }

    public static synchronized String previewNickDisguiseNameLine(String rawNick) {
        return previewNickDisguiseNameLine(rawNick, null);
    }

    /** @param customRankDraft live text from a custom-rank field, or {@code null} to use saved config */
    public static synchronized String previewNickDisguiseNameLine(String rawNick, String customRankDraft) {
        String raw = rawNick == null ? "" : rawNick.trim();
        String plain = stripToPlainNickForOfflineUuid(raw);
        String display;
        if (plain.isEmpty()) {
            display = getHypixelPrefixPreviewDisplayName();
        } else {
            String t = translateAmpersandToSectionSigns(raw);
            display = (t != null && !t.isEmpty()) ? t : plain;
        }
        HypixelPrefixMode mode = getHypixelPrefixMode();
        switch (mode) {
            case HIDE:
                return EnumChatFormatting.GRAY + display + EnumChatFormatting.RESET;
            case SHOW:
                return EnumChatFormatting.DARK_GRAY + "" + EnumChatFormatting.ITALIC + "Target API rank"
                        + EnumChatFormatting.RESET + " " + EnumChatFormatting.GRAY + display + EnumChatFormatting.RESET;
            case CUSTOM:
                String rawCustom = customRankDraft != null
                        ? customRankDraft
                        : ConfigManager.getDisguiseHypixelRankCustom();
                String customTag = translateAmpersandToSectionSigns(rawCustom);
                if (customTag == null || customTag.isEmpty()) {
                    return previewNameWithOptionalFormatting(display, EnumChatFormatting.WHITE.toString());
                }
                return customTag + " " + previewNameWithOptionalFormatting(display, EnumChatFormatting.WHITE.toString());
            case HYPIXEL_OFFICIAL:
                return buildOfficialHypixelPrefixNamePreview(display, customRankDraft);
            default:
                return previewNameWithOptionalFormatting(display, EnumChatFormatting.WHITE.toString());
        }
    }

    private static String previewNameWithOptionalFormatting(String display, String plainColorPrefix) {
        if (display != null && display.indexOf('\u00a7') >= 0) {
            return display + EnumChatFormatting.RESET;
        }
        return plainColorPrefix + display + EnumChatFormatting.RESET;
    }

    private static String buildOfficialHypixelPrefixNamePreview(String display) {
        return buildOfficialHypixelPrefixNamePreview(display, null);
    }

    private static String buildOfficialHypixelPrefixNamePreview(String display, String customRankDraft) {
        String preset = ConfigManager.getDisguiseHypixelRankPreset();
        if ("CUSTOM".equals(preset)) {
            String rawCustom = customRankDraft != null
                    ? customRankDraft
                    : ConfigManager.getDisguiseHypixelRankCustom();
            String tag = translateAmpersandToSectionSigns(rawCustom);
            if (tag == null || tag.isEmpty()) {
                return previewNameWithOptionalFormatting(display, EnumChatFormatting.WHITE.toString());
            }
            return tag + " " + previewNameWithOptionalFormatting(display, EnumChatFormatting.WHITE.toString());
        }
        if ("TARGET".equals(preset)) {
            return EnumChatFormatting.DARK_GRAY + "" + EnumChatFormatting.ITALIC + "Target API rank"
                    + EnumChatFormatting.RESET + " " + EnumChatFormatting.GRAY + display + EnumChatFormatting.RESET;
        }
        String rank = "Default";
        String plus = "";
        String monthly = "";
        if ("NONE".equals(preset) || preset == null) {
            rank = "Default";
        } else {
            switch (preset) {
                case "VIP":
                    rank = "VIP";
                    break;
                case "VIP_PLUS":
                    rank = "VIP+";
                    break;
                case "MVP":
                    rank = "MVP";
                    monthly = ConfigManager.getDisguiseHypixelMvpBracketColor();
                    break;
                case "MVP_PLUS":
                    rank = "MVP+";
                    monthly = ConfigManager.getDisguiseHypixelMvpPlusBracketBaseColor();
                    plus = ConfigManager.getDisguiseHypixelMvpPlusColor();
                    break;
                case "MVP_PLUS_PLUS":
                    rank = "MVP++";
                    monthly = ConfigManager.getDisguiseHypixelMvpPpBaseColor();
                    plus = ConfigManager.getDisguiseHypixelMvpPpSignColor();
                    break;
                case "YOUTUBER":
                    rank = "YOUTUBER";
                    break;
                case "OWNER":
                    rank = "OWNER";
                    break;
                case "ADMIN":
                    rank = "ADMIN";
                    break;
                case "GM":
                    rank = "GM";
                    break;
                case "MOJANG":
                    rank = "MOJANG";
                    break;
                case "EVENTS":
                    rank = "EVENTS";
                    break;
                case "INNIT":
                    rank = "INNIT";
                    break;
                case "PIG_PLUS_PLUS_PLUS":
                    rank = "PIG+++";
                    break;
                default:
                    rank = "Default";
                    break;
            }
        }
        String tag = buildRankTagForHypixelPreview(rank, plus, monthly);
        String nameFmt = getUsernameColor(rank, monthly);
        if (tag == null || tag.isEmpty()) {
            return previewNameWithOptionalFormatting(display, nameFmt);
        }
        return tag + " " + previewNameWithOptionalFormatting(display, nameFmt);
    }

    /** Same bracket rules as {@link #buildRankTag()} using explicit rank state (config preview). */
    private static String buildRankTagForHypixelPreview(String rank, String rankPlusColor, String monthlyRankColor) {
        if (rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return "";
        }
        if ("MVP".equals(rank)) {
            String baseColor = EnumChatFormatting.AQUA.toString();
            return baseColor + "[MVP]";
        }
        if ("MVP+".equals(rank)) {
            String baseColor = EnumChatFormatting.AQUA.toString();
            String plusColor = getMVPPlusColor(rankPlusColor);
            return baseColor + "[MVP" + plusColor + "+" + baseColor + "]";
        }
        if ("MVP++".equals(rank)) {
            String baseColor = getMVPPlusPlusColor(monthlyRankColor);
            String plusColor = getMVPPlusPlusSignsColor(rankPlusColor);
            return baseColor + "[MVP" + plusColor + "++" + baseColor + "]";
        }
        if ("VIP+".equals(rank)) {
            String baseColor = EnumChatFormatting.GREEN + "";
            return baseColor + "[VIP" + EnumChatFormatting.GOLD + "+" + baseColor + "]";
        }
        if ("YOUTUBER".equals(rank)) {
            return EnumChatFormatting.RED + "[" + EnumChatFormatting.WHITE + "YOUTUBE" + EnumChatFormatting.RED + "]";
        }
        if ("PIG+++".equals(rank)) {
            return EnumChatFormatting.LIGHT_PURPLE + "[PIG" + EnumChatFormatting.AQUA + "+++" + EnumChatFormatting.LIGHT_PURPLE + "]";
        }
        if ("STAFF".equals(rank)) {
            return EnumChatFormatting.RED + "[" + EnumChatFormatting.GOLD + "\u12de"
                    + EnumChatFormatting.RED + "]";
        }
        String rankColor = getRankColor(rank);
        return rankColor + "[" + getRenderedRankLabel(rank) + "]";
    }

    private static boolean isOnHypixelServer(Minecraft mc) {
        try {
            return GameContext.isOnHypixel(mc);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int computeNetworkLevel(double networkExp) {
        if (Double.isNaN(networkExp) || Double.isInfinite(networkExp) || networkExp <= 0.0D) {
            return 1;
        }

        double level = (Math.sqrt((2.0D * networkExp) + 30625.0D) / 50.0D) - 2.5D;
        return Math.max(1, (int) Math.floor(level));
    }

    private static float computeNetworkProgress(double networkExp) {
        if (Double.isNaN(networkExp) || Double.isInfinite(networkExp) || networkExp <= 0.0D) {
            return 0.0F;
        }

        int level = computeNetworkLevel(networkExp);
        double currentLevelExp = getNetworkExpForLevel(level);
        double nextLevelExp = getNetworkExpForLevel(level + 1);
        if (nextLevelExp <= currentLevelExp) {
            return 0.0F;
        }

        double progress = (networkExp - currentLevelExp) / (nextLevelExp - currentLevelExp);
        return clamp01((float) progress);
    }

    private static double getNetworkExpForLevel(int level) {
        int safeLevel = Math.max(1, level);
        return (1250.0D * safeLevel * safeLevel) + (6250.0D * safeLevel) - 7500.0D;
    }

    private static int toSafeExperienceTotal(double networkExp) {
        if (Double.isNaN(networkExp) || Double.isInfinite(networkExp) || networkExp <= 0.0D) {
            return 0;
        }

        long rounded = Math.round(networkExp);
        if (rounded <= 0L) {
            return 0;
        }
        return rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
    }

    private static float clamp01(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        if (value > 1.0F) {
            return 1.0F;
        }
        return value;
    }

    private static boolean matchesXpBar(int currentLevel, float currentProgress, int expectedLevel, float expectedProgress) {
        return currentLevel == expectedLevel && Math.abs(clamp01(currentProgress) - expectedProgress) <= 0.08F;
    }

    private static BufferedImage normalizeCapeImage(BufferedImage source) {
        if (source == null) {
            return null;
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int targetWidth = 64;
        int targetHeight = 32;

        while (targetWidth < sourceWidth || targetHeight < sourceHeight) {
            targetWidth *= 2;
            targetHeight *= 2;
        }

        BufferedImage normalized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = normalized.getGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static TargetHypixelData resolveTargetHypixelData(ResolvedTarget target, HypixelDisguiseDataMode mode) throws Exception {
        if (target == null || target.uuidUndashed == null || target.uuidUndashed.trim().isEmpty()) {
            return new TargetHypixelData(RankData.unresolved(), GuildTagData.empty());
        }

        CachedHypixelDisguiseData cached = getCachedHypixelDisguiseData(target.uuidUndashed);
        long waitMs = getHypixelCacheWaitMs(cached);
        boolean hasConfiguredApiKey = hasConfiguredHypixelApiKey();

        if (mode == HypixelDisguiseDataMode.AUTO && cached != null && waitMs > 0L && hasConfiguredApiKey) {
            throw new HypixelDataDecisionRequiredException(new HypixelDataDecision(
                    target.name,
                    target.uuidUndashed,
                    "Recent Hypixel disguise data for " + target.name + " is still on cooldown.",
                    true,
                    true,
                    waitMs
            ));
        }

        if (mode == HypixelDisguiseDataMode.CACHE_ONLY) {
            if (cached == null) {
                throw new Exception("No cached Hypixel disguise data for " + target.name + ".");
            }
            return new TargetHypixelData(
                    cached.rankData == null ? RankData.unresolved() : cached.rankData,
                    cached.guildTagData == null ? GuildTagData.empty() : cached.guildTagData
            );
        }

        HypixelFetchResult<RankData> rankFetch = fetchRankDataDetailed(target.uuidUndashed);
        HypixelFetchResult<GuildTagData> guildFetch = fetchGuildTagDataDetailed(target.uuidUndashed);

        RankData rankData = rankFetch.status == HypixelFetchStatus.SUCCESS && rankFetch.data != null
                ? rankFetch.data
                : RankData.unresolved();
        GuildTagData guildTagData = guildFetch.status == HypixelFetchStatus.SUCCESS && guildFetch.data != null
                ? guildFetch.data
                : GuildTagData.empty();

        if (rankFetch.status == HypixelFetchStatus.SUCCESS || guildFetch.status == HypixelFetchStatus.SUCCESS) {
            cacheHypixelDisguiseData(target.uuidUndashed, rankFetch.status == HypixelFetchStatus.SUCCESS ? rankData : null,
                    guildFetch.status == HypixelFetchStatus.SUCCESS ? guildTagData : null);
        }

        if (rankFetch.status != HypixelFetchStatus.SUCCESS && cached != null && cached.rankData != null) {
            boolean canWait = rankFetch.status == HypixelFetchStatus.RATE_LIMIT && waitMs > 0L;
            if (mode == HypixelDisguiseDataMode.AUTO || mode == HypixelDisguiseDataMode.FRESH_ONLY) {
                throw new HypixelDataDecisionRequiredException(new HypixelDataDecision(
                        target.name,
                        target.uuidUndashed,
                        buildHypixelDecisionReason(rankFetch),
                        true,
                        canWait,
                        canWait ? waitMs : 0L
                ));
            }
        }

        return new TargetHypixelData(rankData, guildTagData);
    }

    private static RankData fetchBestEffortRankData(String uuid) {
        HypixelFetchResult<RankData> result = fetchRankDataDetailed(uuid);
        if (result.status == HypixelFetchStatus.SUCCESS && result.data != null) {
            cacheHypixelDisguiseData(uuid, result.data, null);
            return result.data;
        }

        CachedHypixelDisguiseData cached = getCachedHypixelDisguiseData(uuid);
        if (cached != null && cached.rankData != null) {
            return cached.rankData;
        }
        return RankData.unresolved();
    }

    private static HypixelFetchResult<RankData> fetchRankDataDetailed(String uuid) {
        try {
            String apiKey = ApiKeyManager.getApiKey("hypixel");
            if (apiKey == null || apiKey.isEmpty()) {
                return HypixelFetchResult.failure(HypixelFetchStatus.NO_API_KEY, "No Hypixel API key configured.", RankData.unresolved());
            }

            JsonObject response = fetchJson("https://api.hypixel.net/player?key=" + apiKey + "&uuid=" + uuid, false);
            if (response == null) {
                return HypixelFetchResult.failure(HypixelFetchStatus.ERROR, "Empty Hypixel player response.", RankData.unresolved());
            }
            if (!response.has("success") || !response.get("success").getAsBoolean()) {
                return HypixelFetchResult.failure(classifyHypixelFailure(response), readHypixelCause(response), RankData.unresolved());
            }
            if (!response.has("player") || response.get("player").isJsonNull()) {
                return HypixelFetchResult.success(RankData.defaultResolvedRank(0.0D, false, "", BlitzStats.empty(), 0, false));
            }

            JsonObject playerData = response.getAsJsonObject("player");
            String fetchedRankPlusColor = playerData.has("rankPlusColor") ? playerData.get("rankPlusColor").getAsString() : "";
            String fetchedMonthlyRankColor = playerData.has("monthlyRankColor") ? playerData.get("monthlyRankColor").getAsString() : "";
            double networkExp = playerData.has("networkExp") ? playerData.get("networkExp").getAsDouble() : 0.0D;
            boolean networkExpKnown = playerData.has("networkExp");
            String guildRank = playerData.has("guildRank") && !playerData.get("guildRank").isJsonNull()
                    ? playerData.get("guildRank").getAsString()
                    : "";
            BlitzStats blitzStats = extractBlitzStats(playerData, uuid);
            int achievementPoints = 0;
            boolean achievementPointsKnown = false;
            if (playerData.has("achievementPoints") && !playerData.get("achievementPoints").isJsonNull()) {
                try {
                    achievementPoints = playerData.get("achievementPoints").getAsInt();
                    achievementPointsKnown = true;
                } catch (Throwable ignored) {
                }
            }

            // Hypixel retains purchase-rank fields for staff; their STAFF role must win.
            if (playerData.has("rank") && "STAFF".equals(playerData.get("rank").getAsString())) {
                return HypixelFetchResult.success(new RankData("STAFF", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
            }

            if (playerData.has("prefix")) {
                String prefix = playerData.get("prefix").getAsString();
                if (prefix.contains("MOJANG")) {
                    return HypixelFetchResult.success(new RankData("MOJANG", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
                if (prefix.contains("EVENTS")) {
                    return HypixelFetchResult.success(new RankData("EVENTS", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
                if (prefix.contains("INNIT")) {
                    return HypixelFetchResult.success(new RankData("INNIT", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
                if (prefix.contains("OWNER")) {
                    return HypixelFetchResult.success(new RankData("OWNER", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
            }

            if (playerData.has("rank")) {
                String exactRank = playerData.get("rank").getAsString();
                if ("YOUTUBER".equals(exactRank)) {
                    return HypixelFetchResult.success(new RankData("YOUTUBER", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
                if ("ADMIN".equals(exactRank)) {
                    if (playerData.has("prefix") && playerData.get("prefix").getAsString().contains("OWNER")) {
                        return HypixelFetchResult.success(new RankData("OWNER", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                    }
                    return HypixelFetchResult.success(new RankData("ADMIN", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
                if ("GAME_MASTER".equals(exactRank)) {
                    return HypixelFetchResult.success(new RankData("GM", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
                if ("MVP_PLUS_PLUS".equals(exactRank)) {
                    return HypixelFetchResult.success(new RankData("MVP++", fetchedRankPlusColor, fetchedMonthlyRankColor, true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
            }

            if (playerData.has("monthlyPackageRank")) {
                String monthlyRank = playerData.get("monthlyPackageRank").getAsString();
                if ("SUPERSTAR".equals(monthlyRank)) {
                    return HypixelFetchResult.success(new RankData("MVP++", fetchedRankPlusColor, fetchedMonthlyRankColor, true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                }
            }

            String packageRank = "";
            if (playerData.has("newPackageRank")) {
                packageRank = playerData.get("newPackageRank").getAsString();
            } else if (playerData.has("packageRank")) {
                packageRank = playerData.get("packageRank").getAsString();
            }

            switch (packageRank) {
                case "MVP_PLUS":
                    return HypixelFetchResult.success(new RankData("MVP+", fetchedRankPlusColor, "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                case "MVP":
                    return HypixelFetchResult.success(new RankData("MVP", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                case "VIP_PLUS":
                    return HypixelFetchResult.success(new RankData("VIP+", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                case "VIP":
                    return HypixelFetchResult.success(new RankData("VIP", "", "", true, networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
                default:
                    return HypixelFetchResult.success(RankData.defaultResolvedRank(networkExp, networkExpKnown, guildRank, blitzStats, achievementPoints, achievementPointsKnown));
            }
        } catch (Exception e) {
            return HypixelFetchResult.failure(classifyHypixelFailure(e), safeExceptionMessage(e), RankData.unresolved());
        }
    }

    private static BlitzStats extractBlitzStats(JsonObject playerData, String uuid) {
        if (playerData == null || !playerData.has("stats") || !playerData.get("stats").isJsonObject()) {
            return BlitzStats.empty();
        }

        JsonObject stats = playerData.getAsJsonObject("stats");
        if (!stats.has("HungerGames") || !stats.get("HungerGames").isJsonObject()) {
            return BlitzStats.empty();
        }

        JsonObject hungerGames = stats.getAsJsonObject("HungerGames");
        String username = playerData.has("displayname") && !playerData.get("displayname").isJsonNull()
                ? playerData.get("displayname").getAsString()
                : uuid;
        Map<String, DisguiseKitInfo> kitInfoByName = buildDisguiseKitInfo(playerData, uuid, username);
        Map<String, String> renamedItemsByKey = extractDisguiseRenamedItems(hungerGames);
        return new BlitzStats(
                readNullableInt(hungerGames, "kills"),
                readNullableInt(hungerGames, "wins", "wins_solo_normal", "wins_solo", "solo_wins"),
                readNullableInt(hungerGames, "wins_teams_normal", "wins_teams", "wins_team_normal", "team_wins"),
                readNullableInt(hungerGames, "coins"),
                readNullableInt(hungerGames, "deaths"),
                readNullableBoolean(hungerGames, "togglekillcounter", "displaykills", "display_kills", "displayKills", "showkills", "show_kills", "showKills"),
                readNullableBoolean(hungerGames, "alternative_kill_message_enabled"),
                kitInfoByName,
                renamedItemsByKey
        );
    }

    private static Map<String, DisguiseKitInfo> buildDisguiseKitInfo(JsonObject playerData, String uuid, String username) {
        try {
            List<KitLevelsManager.KitPlayer> kits = KitLevelsManager.buildPlayerAllKitsFromPlayerData(uuid, username, playerData);
            if (kits == null || kits.isEmpty()) {
                return Collections.emptyMap();
            }

            JsonObject stats = playerData != null && playerData.has("stats") && playerData.get("stats").isJsonObject()
                    ? playerData.getAsJsonObject("stats")
                    : null;
            JsonObject hungerGames = stats != null && stats.has("HungerGames") && stats.get("HungerGames").isJsonObject()
                    ? stats.getAsJsonObject("HungerGames")
                    : null;
            Set<String> packageIds = extractPackageIds(playerData);
            LinkedHashMap<String, DisguiseKitInfo> result = new LinkedHashMap<>();
            for (KitLevelsManager.KitPlayer kit : kits) {
                if (kit == null || kit.getKitName() == null || kit.getKitName().trim().isEmpty()) {
                    continue;
                }
                String apiKitName = kit.getApiKitName();
                int resolvedPrestige = resolveDisguiseKitPrestige(kit, hungerGames, packageIds);
                boolean resolvedPrestiged = resolvedPrestige > 0 || kit.isPrestiged();
                int resolvedLevel = resolveDisguiseKitLevel(kit, hungerGames, resolvedPrestiged);
                int kitKills = readKitProgressInt(hungerGames, "kills_", kit);
                int soloKitWins = readKitProgressInt(hungerGames, "wins_", kit);
                int teamKitWins = readKitProgressInt(hungerGames, "wins_teams_", kit);
                result.put(normalizeDisguiseKitKey(kit.getKitName()),
                        new DisguiseKitInfo(
                                kit.getKitName(),
                                apiKitName,
                                resolvedLevel,
                                resolvedPrestige,
                                resolvedPrestiged,
                                resolvedPrestiged ? (resolvedPrestige >= 2 ? "★★" : "★") : kit.getLevelDisplay(),
                                kitKills,
                                soloKitWins,
                                teamKitWins
                        ));
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private static int resolveDisguiseKitPrestige(KitLevelsManager.KitPlayer kit, JsonObject hungerGames,
                                                  Set<String> packageIds) {
        if (kit == null) {
            return 0;
        }

        int resolvedPrestige = Math.max(0, kit.getPrestige());
        resolvedPrestige = Math.max(resolvedPrestige, resolvePackagePrestige(kit, packageIds));
        if (hungerGames == null) {
            return resolvedPrestige;
        }

        Integer storedPrestige = readNullableInt(hungerGames, buildKitStatKeys(kit, "p", "p_", "prestige", "prestige_"));
        if (storedPrestige != null) {
            resolvedPrestige = Math.max(resolvedPrestige, storedPrestige);
        }
        return Math.max(0, resolvedPrestige);
    }

    private static int resolveDisguiseKitLevel(KitLevelsManager.KitPlayer kit, JsonObject hungerGames, boolean prestiged) {
        if (kit == null) {
            return 0;
        }

        int resolvedLevel = Math.max(1, kit.getLevel());
        if (hungerGames != null) {
            Integer storedLevel = readNullableInt(hungerGames, buildKitStatKeys(kit, "", null));
            if (storedLevel != null && storedLevel >= 0) {
                resolvedLevel = Math.max(resolvedLevel, storedLevel + 1);
            }

            Integer exp = readNullableInt(hungerGames, buildKitStatKeys(kit, "exp_", "exp"));
            if (exp != null && exp >= 0) {
                resolvedLevel = Math.max(resolvedLevel, getDisguiseKitLevelFromExp(exp));
            }
        }

        if (prestiged) {
            resolvedLevel = Math.max(resolvedLevel, 10);
        }
        return Math.max(1, resolvedLevel);
    }

    private static int getDisguiseKitLevelFromExp(int exp) {
        if (exp < 100) return 1;
        if (exp < 250) return 2;
        if (exp < 500) return 3;
        if (exp < 1000) return 4;
        if (exp < 1500) return 5;
        if (exp < 2000) return 6;
        if (exp < 2500) return 7;
        if (exp < 5000) return 8;
        if (exp < 10000) return 9;
        return 10;
    }

    private static String normalizeDisguiseKitKey(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, String> extractDisguiseRenamedItems(JsonObject hungerGames) {
        if (hungerGames == null) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : hungerGames.entrySet()) {
            if (entry == null || entry.getKey() == null || !entry.getKey().startsWith("kit_item_rename_")) {
                continue;
            }
            JsonElement valueElement = entry.getValue();
            if (valueElement == null || valueElement.isJsonNull()) {
                continue;
            }
            String remainder = entry.getKey().substring("kit_item_rename_".length());
            int separator = remainder.indexOf('_');
            if (separator <= 0 || separator >= remainder.length() - 1) {
                continue;
            }
            String lookupKey = buildDisguiseRenamedItemLookupKey(
                    remainder.substring(0, separator),
                    remainder.substring(separator + 1)
            );
            if (lookupKey.isEmpty()) {
                continue;
            }
            try {
                String value = valueElement.getAsString();
                if (value != null
                        && !value.trim().isEmpty()
                        && DisguiseRenamedItemMasker.isPlausibleDisguiseRenamedItemApiValue(value)) {
                    result.put(lookupKey, value);
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static String normalizeDisguiseRenamedItemLookupToken(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u2019', '\'')
                .replace('\u2018', '\'')
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static Set<String> extractPackageIds(JsonObject playerData) {
        if (playerData == null || !playerData.has("packages") || !playerData.get("packages").isJsonArray()) {
            return Collections.emptySet();
        }

        LinkedHashSet<String> packageIds = new LinkedHashSet<>();
        for (JsonElement element : playerData.getAsJsonArray("packages")) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                String value = element.getAsString();
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                packageIds.add(value.trim().toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
        }
        return packageIds;
    }

    private static int resolvePackagePrestige(KitLevelsManager.KitPlayer kit, Set<String> packageIds) {
        if (kit == null || packageIds == null || packageIds.isEmpty()) {
            return 0;
        }

        for (String variant : buildKitStatKeys(kit)) {
            if (variant == null || variant.trim().isEmpty()) {
                continue;
            }
            String normalizedVariant = variant.trim().toLowerCase(Locale.ROOT);
            if (packageIds.contains(normalizedVariant + "_pres_2")) {
                return 2;
            }
            if (packageIds.contains(normalizedVariant + "_pres_1")) {
                return 1;
            }
        }
        return 0;
    }

    private static HypixelFetchResult<GuildTagData> fetchGuildTagDataDetailed(String uuid) {
        try {
            String apiKey = ApiKeyManager.getApiKey("hypixel");
            if (apiKey == null || apiKey.isEmpty()) {
                return HypixelFetchResult.failure(HypixelFetchStatus.NO_API_KEY, "No Hypixel API key configured.", GuildTagData.empty());
            }

            JsonObject response = fetchJson("https://api.hypixel.net/guild?key=" + apiKey + "&player=" + uuid, false);
            if (response == null) {
                return HypixelFetchResult.failure(HypixelFetchStatus.ERROR, "Empty Hypixel guild response.", GuildTagData.empty());
            }
            if (!response.has("success") || !response.get("success").getAsBoolean()) {
                return HypixelFetchResult.failure(classifyHypixelFailure(response), readHypixelCause(response), GuildTagData.empty());
            }
            if (!response.has("guild") || response.get("guild").isJsonNull() || !response.get("guild").isJsonObject()) {
                return HypixelFetchResult.success(GuildTagData.empty());
            }

            JsonObject guild = response.getAsJsonObject("guild");
            if (!guild.has("tag") || guild.get("tag").isJsonNull()) {
                return HypixelFetchResult.success(GuildTagData.empty());
            }

            String guildTag = fixGuildTagUnicode(guild.get("tag").getAsString());
            if (guildTag == null || guildTag.isEmpty()) {
                return HypixelFetchResult.success(GuildTagData.empty());
            }

            String tagColor = guild.has("tagColor") && !guild.get("tagColor").isJsonNull()
                    ? guild.get("tagColor").getAsString()
                    : null;
            return HypixelFetchResult.success(new GuildTagData(guildTag, getGuildTagColor(tagColor)));
        } catch (Exception e) {
            return HypixelFetchResult.failure(classifyHypixelFailure(e), safeExceptionMessage(e), GuildTagData.empty());
        }
    }

    private static synchronized CachedHypixelDisguiseData getCachedHypixelDisguiseData(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return null;
        }
        return hypixelDisguiseDataCache.get(uuid.trim().replace("-", "").toLowerCase(Locale.ROOT));
    }

    private static synchronized void cacheHypixelDisguiseData(String uuid, RankData rankData, GuildTagData guildTagData) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return;
        }

        String key = uuid.trim().replace("-", "").toLowerCase(Locale.ROOT);
        CachedHypixelDisguiseData existing = hypixelDisguiseDataCache.get(key);
        RankData resolvedRankData = rankData != null ? rankData : (existing == null ? null : existing.rankData);
        GuildTagData resolvedGuildTagData = guildTagData != null ? guildTagData : (existing == null ? null : existing.guildTagData);
        hypixelDisguiseDataCache.put(key, new CachedHypixelDisguiseData(resolvedRankData, resolvedGuildTagData, System.currentTimeMillis()));
    }

    private static long getHypixelCacheWaitMs(CachedHypixelDisguiseData cached) {
        if (cached == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - cached.fetchedAtMs;
        return Math.max(0L, HYPIXEL_DISGUISE_CACHE_WINDOW_MS - elapsed);
    }

    private static boolean hasConfiguredHypixelApiKey() {
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    private static String buildHypixelDecisionReason(HypixelFetchResult<?> result) {
        if (result == null) {
            return "Hypixel disguise data is unavailable right now.";
        }

        switch (result.status) {
            case NO_API_KEY:
                return "Hypixel disguise data is unavailable because no Hypixel API key is set.";
            case INVALID_API_KEY:
                return "Hypixel disguise data is unavailable because the Hypixel API key is invalid or expired.";
            case RATE_LIMIT:
                return "Hypixel disguise data is currently rate-limited.";
            default:
                if (result.message != null && !result.message.trim().isEmpty()) {
                    return "Hypixel disguise data is unavailable: " + result.message;
                }
                return "Hypixel disguise data is unavailable right now.";
        }
    }

    private static HypixelFetchStatus classifyHypixelFailure(JsonObject response) {
        String cause = readHypixelCause(response);
        return classifyHypixelFailure(cause);
    }

    private static HypixelFetchStatus classifyHypixelFailure(Exception e) {
        return classifyHypixelFailure(safeExceptionMessage(e));
    }

    private static HypixelFetchStatus classifyHypixelFailure(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("invalid api key") || lower.contains("invalid key") || lower.contains("api key invalid") || lower.contains("expired")) {
            return HypixelFetchStatus.INVALID_API_KEY;
        }
        if (lower.contains("throttle") || lower.contains("rate limit") || lower.contains("ratelimit") || lower.contains("too many requests") || lower.contains("http 429")) {
            return HypixelFetchStatus.RATE_LIMIT;
        }
        return HypixelFetchStatus.ERROR;
    }

    private static String readHypixelCause(JsonObject response) {
        if (response == null) {
            return "";
        }
        if (response.has("cause") && !response.get("cause").isJsonNull()) {
            try {
                return response.get("cause").getAsString();
            } catch (Exception ignored) {
            }
        }
        if (response.has("error") && !response.get("error").isJsonNull()) {
            try {
                return response.get("error").getAsString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String safeExceptionMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) {
            return "Unknown error";
        }
        return e.getMessage().trim();
    }

    private static Integer readNullableInt(JsonObject source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            if (key == null || !source.has(key) || source.get(key).isJsonNull()) {
                continue;
            }

            try {
                return source.get(key).getAsInt();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String[] buildKitStatKeys(KitLevelsManager.KitPlayer kit, String... prefixes) {
        if (kit == null) {
            return new String[0];
        }

        LinkedHashSet<String> variants = new LinkedHashSet<>();
        addKitStatNameVariants(variants, kit.getApiKitName());
        addKitStatNameVariants(variants, kit.getKitName());

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String variant : variants) {
            if (prefixes == null || prefixes.length == 0) {
                keys.add(variant);
                continue;
            }

            for (String prefix : prefixes) {
                if (prefix != null) {
                    keys.add(prefix + variant);
                }
            }
        }
        return keys.toArray(new String[0]);
    }

    private static void addKitStatNameVariants(Set<String> target, String name) {
        if (target == null || name == null) {
            return;
        }

        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        String noSpaces = lower.replace(" ", "");
        String underscored = lower.replace(' ', '_');
        String normalized = normalizeDisguiseKitKey(lower);

        target.add(lower);
        target.add(noSpaces);
        target.add(underscored);
        target.add(normalized);
    }

    private static int readKitProgressInt(JsonObject source, String prefix, KitLevelsManager.KitPlayer kit) {
        if (source == null || prefix == null || kit == null) {
            return 0;
        }
        Integer value = readNullableInt(source, buildKitStatKeys(kit, prefix, null));
        return value == null ? 0 : Math.max(0, value);
    }

    private static boolean readNullableBoolean(JsonObject source, String... keys) {
        if (source == null || keys == null) {
            return false;
        }

        for (String key : keys) {
            if (key == null || !source.has(key) || source.get(key).isJsonNull()) {
                continue;
            }

            try {
                JsonElement element = source.get(key);
                if (element.isJsonPrimitive()) {
                    if (element.getAsJsonPrimitive().isBoolean()) {
                        return element.getAsBoolean();
                    }
                    if (element.getAsJsonPrimitive().isNumber()) {
                        return element.getAsInt() != 0;
                    }
                    if (element.getAsJsonPrimitive().isString()) {
                        String value = element.getAsString().trim();
                        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                            return true;
                        }
                        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
                            return false;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static JsonObject fetchJson(String urlString, boolean allowNotFound) throws Exception {
        if (urlString != null && urlString.contains("api.hypixel.net")) {
            return HypixelApiHelper.fetchJson(urlString);
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "gomod123-local-disguise");

            int responseCode = connection.getResponseCode();
            if (allowNotFound && (responseCode == 204 || responseCode == 404)) {
                return null;
            }
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode + " for " + urlString);
            }

            InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
            try {
                return new JsonParser().parse(reader).getAsJsonObject();
            } finally {
                reader.close();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static UUID toDashedUuid(String undashedUuid) {
        String clean = undashedUuid.replace("-", "");
        return UUID.fromString(
                clean.substring(0, 8) + "-" +
                clean.substring(8, 12) + "-" +
                clean.substring(12, 16) + "-" +
                clean.substring(16, 20) + "-" +
                clean.substring(20, 32)
        );
    }

    private static String replaceRankAndNameSegment(String input, String originalName, boolean includeKillPrefix,
                                                    boolean includeGuildSuffix, boolean consumeExistingKillPrefix,
                                                    boolean preserveServerFormattingWithoutRank) {
        return replaceRankAndNameSegment(input, originalName, includeKillPrefix, includeGuildSuffix,
                consumeExistingKillPrefix, preserveServerFormattingWithoutRank, true, true);
    }

    private static String replaceRankAndNameSegment(String input, String originalName, boolean includeKillPrefix,
                                                    boolean includeGuildSuffix, boolean consumeExistingKillPrefix,
                                                    boolean preserveServerFormattingWithoutRank,
                                                    boolean preserveDetectedRankPrefix, boolean allowInjectedRankPrefix) {
        return replaceRankAndNameSegment(input, originalName, includeKillPrefix, includeGuildSuffix,
                consumeExistingKillPrefix, preserveServerFormattingWithoutRank,
                preserveDetectedRankPrefix, allowInjectedRankPrefix, null, false);
    }

    private static String replaceRankAndNameSegment(String input, String originalName, boolean includeKillPrefix,
                                                    boolean includeGuildSuffix, boolean consumeExistingKillPrefix,
                                                    boolean preserveServerFormattingWithoutRank,
                                                    boolean preserveDetectedRankPrefix, boolean allowInjectedRankPrefix,
                                                    String guildSuffixOverride) {
        return replaceRankAndNameSegment(input, originalName, includeKillPrefix, includeGuildSuffix,
                consumeExistingKillPrefix, preserveServerFormattingWithoutRank,
                preserveDetectedRankPrefix, allowInjectedRankPrefix, guildSuffixOverride, false);
    }

    private static String replaceRankAndNameSegment(String input, String originalName, boolean includeKillPrefix,
                                                    boolean includeGuildSuffix, boolean consumeExistingKillPrefix,
                                                    boolean preserveServerFormattingWithoutRank,
                                                    boolean preserveDetectedRankPrefix, boolean allowInjectedRankPrefix,
                                                    String guildSuffixOverride,
                                                    boolean consumeRankKillPrefixesKeepBlitzSpeakerTags) {
        SpanData spanData = buildVisibleMapping(input);
        String visible = spanData.visibleText;
        int nameIndex = indexOfIgnoreCaseStandalone(visible, originalName, 0);
        if (nameIndex < 0) {
            return input;
        }

        int formattedStart = spanData.visibleToFormattedStart[nameIndex];
        int nameFormattedStart = formattedStart;
        int formattedEnd = spanData.visibleToFormattedEnd[nameIndex + originalName.length() - 1];
        GuildSuffixRange guildSuffixRange = findImmediateGuildSuffixRange(spanData, nameIndex + originalName.length());
        if (guildSuffixRange != null) {
            formattedEnd = guildSuffixRange.formattedEndExclusive;
        }

        int rankPlainStart = findImmediateRankPrefixStart(visible, nameIndex);
        boolean detectedServerRankPrefix = rankPlainStart >= 0;
        boolean includeRankPrefix = preserveDetectedRankPrefix && detectedServerRankPrefix;
        String originalRankPrefix = "";
        // Only consume bracket(s) before the name when we're allowed to treat them as rank —
        // otherwise game TAB tags like [DIST2] get dropped when rewriting the name for disguise.
        if (detectedServerRankPrefix && preserveDetectedRankPrefix) {
            int rankFormattedStart = spanData.visibleToFormattedStart[rankPlainStart];
            formattedStart = rankFormattedStart;
            originalRankPrefix = input.substring(rankFormattedStart, nameFormattedStart);
        }

        // Party/system lines (no colon): server still shows YOUR real [MVP+]; strip it when disguise target has no rank.
        boolean strippedRealRankForNonRankedDisguise = false;
        if (!includeRankPrefix && shouldGrayChatBodyForNonRankedDisguise()
                && hypixelRankBracketImmediatelyBefore(visible, nameIndex)) {
            int chainStart = findLeftmostBracketPrefixChainStart(visible, nameIndex);
            if (chainStart >= 0) {
                formattedStart = Math.min(formattedStart, spanData.visibleToFormattedStart[chainStart]);
                strippedRealRankForNonRankedDisguise = true;
            }
        }

        if (consumeExistingKillPrefix) {
            int killPrefixVisibleStart = findImmediateKillPrefixStart(visible, detectedServerRankPrefix ? rankPlainStart : nameIndex);
            if (killPrefixVisibleStart >= 0) {
                formattedStart = spanData.visibleToFormattedStart[killPrefixVisibleStart];
            }
            // Hypixel may order chat as [MVP+] [kills] Name or [kills] [MVP+] Name. Consume the full bracket run
            // before the name so we don't leave a duplicate rank or kill bracket when rebuilding the line.
            int chainStart = findLeftmostBracketPrefixChainStart(visible, nameIndex);
            if (chainStart >= 0) {
                formattedStart = Math.min(formattedStart, spanData.visibleToFormattedStart[chainStart]);
            }
        } else if (consumeRankKillPrefixesKeepBlitzSpeakerTags) {
            // Blitz [SPECTATOR]/[TEAM]/[SHOUT] lines: we must not use the full leftmost chain (that would eat the
            // channel tag). Still strip Hypixel's rank / kill brackets before the name so we do not stack them on
            // top of the rebuilt disguise rank tag.
            int consumableStart = findConsumableHypixelPrefixChainStartBeforeName(visible, nameIndex);
            if (consumableStart >= 0) {
                formattedStart = Math.min(formattedStart, spanData.visibleToFormattedStart[consumableStart]);
            }
        }

        String originalNameFormatting = getActiveFormattingBefore(input, spanData.visibleToFormattedStart[nameIndex]);
        // Always consult shouldPreserveOriginalNameFormatting. The old (!includeRankPrefix &&
        // preserveServerFormattingWithoutRank) shortcut forced true and skipped that check — e.g. lobby join lines
        // kept Hypixel's real-account name color instead of MVP++ base (gold) for the disguised name.
        boolean preserveOriginalNameFormatting = shouldPreserveOriginalNameFormatting(originalNameFormatting);
        if (strippedRealRankForNonRankedDisguise) {
            preserveOriginalNameFormatting = false;
        }
        boolean shouldIncludeGuildSuffix = includeGuildSuffix && guildSuffixRange != null && guildSuffixOverride == null;
        String replacement = buildRankReplacement(originalNameFormatting, preserveOriginalNameFormatting, includeRankPrefix,
                shouldIncludeGuildSuffix, includeKillPrefix, originalRankPrefix, allowInjectedRankPrefix);
        if (guildSuffixOverride != null) {
            replacement = replacement + guildSuffixOverride;
        }
        return input.substring(0, formattedStart) + replacement + input.substring(formattedEnd);
    }

    private static String buildRankReplacement(String originalNameFormatting, boolean preserveOriginalNameFormatting,
                                               boolean includeRankPrefix, boolean includeGuildSuffix, boolean includeKillPrefix,
                                               String originalRankPrefix, boolean allowInjectedRankPrefix) {
        String nameFormatting = preserveOriginalNameFormatting
                ? originalNameFormatting
                : mergeColorWithExistingStyles(getUsernameColor(rank, monthlyRankColor), originalNameFormatting);

        if (nameFormatting == null || nameFormatting.isEmpty()) {
            nameFormatting = EnumChatFormatting.RESET.toString();
        }

        StringBuilder replacement = new StringBuilder();
        if (includeKillPrefix) {
            replacement.append(buildChatKillCounterPrefix());
        }
        HypixelPrefixMode prefixMode = getHypixelPrefixMode();
        String disp = getTargetTabChatDisplayNameForRendering();
        boolean dispHasFormat = disp != null && disp.indexOf('\u00a7') >= 0;
        if (prefixMode == HypixelPrefixMode.HIDE || rank == null || rank.isEmpty() || "Default".equals(rank)) {
            if (dispHasFormat) {
                replacement.append(disp);
            } else {
                replacement.append(nameFormatting).append(disp);
            }
        } else if (allowInjectedRankPrefix) {
            replacement.append(buildRankTag()).append(" ");
            if (dispHasFormat) {
                replacement.append(disp);
            } else {
                replacement.append(nameFormatting).append(disp);
            }
        } else {
            if (dispHasFormat) {
                replacement.append(disp);
            } else {
                replacement.append(nameFormatting).append(disp);
            }
        }

        if (includeGuildSuffix) {
            replacement.append(buildGuildTagSuffix());
        }

        return replacement.toString();
    }

    private static String mergeColorWithExistingStyles(String colorFormatting, String originalFormatting) {
        String color = colorFormatting == null ? "" : colorFormatting;
        StringBuilder styles = new StringBuilder();
        if (originalFormatting != null && !originalFormatting.isEmpty()) {
            for (int i = 0; i < originalFormatting.length() - 1; i++) {
                if (originalFormatting.charAt(i) != '\u00A7') {
                    continue;
                }

                char code = Character.toLowerCase(originalFormatting.charAt(i + 1));
                if (isFormatCode(code)) {
                    styles.append('\u00A7').append(originalFormatting.charAt(i + 1));
                }
                i++;
            }
        }
        return color + styles;
    }

    private static String buildGuildTagSuffix() {
        if (targetGuildTag == null || targetGuildTag.isEmpty()) {
            return "";
        }

        String color = targetGuildTagColor == null || targetGuildTagColor.isEmpty()
                ? EnumChatFormatting.WHITE.toString()
                : targetGuildTagColor;
        return " " + EnumChatFormatting.RESET + color + "[" + targetGuildTag + "]";
    }

    private static String buildGuildRankSuffix() {
        if (targetGuildRank == null || targetGuildRank.isEmpty()) {
            return "";
        }

        String color = targetGuildTagColor == null || targetGuildTagColor.isEmpty()
                ? EnumChatFormatting.GREEN.toString()
                : targetGuildTagColor;
        return " " + EnumChatFormatting.RESET + color + "[" + targetGuildRank + "]";
    }

    private static String buildChatKillCounterPrefix() {
        Integer displayedKillCount = getChatKillCount();
        if (displayedKillCount == null) {
            return "";
        }
        int kills = Math.max(0, displayedKillCount);
        return formatBsg2KillBracketForDisplay(kills) + " ";
    }

    /**
     * BSG2-style {@code [kills]}: brackets use the same tier color as the digits (yellow 1k–24,999, etc.).
     * 0–999: gray digits, white brackets. 100,000+: brackets and digits bold.
     */
    public static String formatBsg2KillBracketForDisplay(int kills) {
        int k = Math.max(0, kills);
        String numColor = getBsg2KillCounterColor(k);
        String bold = k >= 100000 ? EnumChatFormatting.BOLD.toString() : "";

        if (k <= 999) {
            String white = EnumChatFormatting.WHITE.toString();
            return white + "[" + numColor + k + white + "]" + EnumChatFormatting.RESET;
        }

        String tier = numColor + bold;
        return tier + "[" + k + "]" + EnumChatFormatting.RESET;
    }

    /**
     * @deprecated Use {@link GuiHeader#getButtonCenteredTextX} — vanilla {@code getStringWidth} matches draw.
     */
    public static int getBsg2KillBracketCenteringWidth(net.minecraft.client.gui.FontRenderer fr, String formattedLabel, int kills) {
        if (fr == null || formattedLabel == null) {
            return 0;
        }
        return fr.getStringWidth(formattedLabel);
    }

    private static Integer getChatKillCount() {
        if (shouldUseConfiguredCustomKillCount()) {
            return Math.max(0, ConfigManager.getDisguiseCustomKillCount());
        }
        if (targetBlitzStats == null || targetBlitzStats.kills == null) {
            return null;
        }
        return Math.max(0, targetBlitzStats.kills);
    }

    /**
     * Kill count shown by the Disguise GUI's Kill Counter and Tournament Kill Counter hover previews.
     * Uses the same source as the real chat prefix: explicit custom count first, otherwise the loaded disguise
     * target's Hypixel Blitz kills. {@code null} means no player stats are available and the GUI may use a sample.
     */
    public static synchronized Integer getDisguiseGuiPreviewKillCount() {
        return getChatKillCount();
    }

    private static Integer getTournamentKillCount() {
        if (shouldUseConfiguredCustomKillCount()) {
            return Math.max(0, ConfigManager.getDisguiseCustomKillCount() + matchKillOffset);
        }
        if (targetBlitzStats == null || targetBlitzStats.kills == null) {
            return null;
        }
        return Math.max(0, targetBlitzStats.kills + matchKillOffset);
    }

    /** When the Disguise GUI "Custom Kill Count" feature is enabled, chat/tournament use the saved value. */
    private static boolean shouldUseConfiguredCustomKillCount() {
        return ConfigManager.isDisguiseCustomKillCountEnabled();
    }

    private static boolean isBlitzSgContextActive() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && hasBlitzSgSidebar(findSidebarObjective(mc), mc);
    }

    private static boolean shouldShowTournamentKillCounter() {
        if (!isBlitzSgContextActive()) {
            return false;
        }
        // Custom kill count does not require /tabstats payload; tournament suffix should still work.
        if (shouldUseConfiguredCustomKillCount()) {
            switch (getTournamentKillCounterMode()) {
                case FORCE_ON:
                    return true;
                case FORCE_OFF:
                    return false;
                default:
                    // Custom count is explicit user intent — show (Kill #…) without a loaded disguise/API target.
                    return true;
            }
        }
        if (targetBlitzStats == null || targetBlitzStats.kills == null) {
            return false;
        }

        switch (getTournamentKillCounterMode()) {
            case FORCE_ON:
                return true;
            case FORCE_OFF:
                return false;
            default:
                return targetAlternativeKillMessageEnabled;
        }
    }

    private static boolean shouldShowKillCounterPrefix() {
        if (!isBlitzSgContextActive()) {
            return false;
        }
        if (shouldUseConfiguredCustomKillCount()) {
            switch (getKillCounterMode()) {
                case FORCE_ON:
                    return true;
                case FORCE_OFF:
                    return false;
                default:
                    return true;
            }
        }
        if (targetBlitzStats == null || targetBlitzStats.kills == null) {
            return false;
        }

        switch (getKillCounterMode()) {
            case FORCE_ON:
                return true;
            case FORCE_OFF:
                return false;
            default:
                return targetChatKillCounterEnabled;
        }
    }

    private static TournamentKillCounterMode getKillCounterMode() {
        return TournamentKillCounterMode.fromConfigValue(ConfigManager.getDisguiseKillCounterMode());
    }

    private static TournamentKillCounterMode getTournamentKillCounterMode() {
        return TournamentKillCounterMode.fromConfigValue(ConfigManager.getDisguiseTournamentKillCounterMode());
    }

    private static boolean shouldResetMatchKillCounterForMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return "Survive while eliminating".equals(message)
                || "The game starts in 1 second!".equals(message)
                // Hypixel sidebar/chat uses "Cage open:" / "Cages open:" with a countdown, not "cages open in"
                || lower.contains("cage open")
                || lower.contains("cages open")
                || message.contains("The Blitz Star will be released in 3 minutes!")
                || message.contains("You died!")
                || message.contains("Want to play again?");
    }

    private static String getBsg2KillCounterColor(int kills) {
        if (kills < 1000) {
            return EnumChatFormatting.GRAY.toString();
        }
        if (kills < 25000) {
            return EnumChatFormatting.YELLOW.toString();
        }
        if (kills < 50000) {
            return EnumChatFormatting.GREEN.toString();
        }
        if (kills < 75000) {
            return EnumChatFormatting.RED.toString();
        }
        if (kills < 100000) {
            return EnumChatFormatting.AQUA.toString();
        }
        if (kills < 150000) {
            return EnumChatFormatting.GOLD.toString();
        }
        if (kills < 200000) {
            return EnumChatFormatting.DARK_PURPLE.toString();
        }
        if (kills < 250000) {
            return EnumChatFormatting.DARK_RED.toString();
        }
        if (kills < 300000) {
            return EnumChatFormatting.BLUE.toString();
        }
        // 300k+ stays on the 300k tier (Hypixel-style); do not switch palette again at 400k+.
        return EnumChatFormatting.DARK_GREEN.toString();
    }

    private static boolean shouldPreserveOriginalNameFormatting(String originalNameFormatting) {
        if (originalNameFormatting == null || originalNameFormatting.isEmpty()) {
            return false;
        }

        // Disguised as a ranked player: Hypixel lobby TAB often uses §f on your row; still apply target rank color (aqua MVP+, etc.).
        if (isEnabled() && !shouldGrayChatBodyForNonRankedDisguise()) {
            return false;
        }

        // Target has no Hypixel rank: TAB often shows §f white; real defaults use §7 gray — always apply disguise gray.
        if (rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return false;
        }

        String expectedRealNameColor = getLastColorCode(getUsernameColor(realRank, realMonthlyRankColor));
        String actualServerNameColor = getLastColorCode(originalNameFormatting);

        // Hypixel TAB uses bright green for your own name even when your rank color differs (e.g. MVP+ aqua).
        // Preserving that "self" green overrides kill-effect / rank-based recoloring while disguised.
        String selfGreen = EnumChatFormatting.GREEN.toString();
        if (actualServerNameColor != null && selfGreen.equals(actualServerNameColor)
                && expectedRealNameColor != null && !selfGreen.equals(expectedRealNameColor)) {
            return false;
        }

        return expectedRealNameColor != null
                && actualServerNameColor != null
                && !expectedRealNameColor.equals(actualServerNameColor);
    }

    private static GuildSuffixRange findImmediateGuildSuffixRange(SpanData spanData, int nameEndVisibleExclusive) {
        if (spanData == null || spanData.visibleText == null || nameEndVisibleExclusive < 0 || nameEndVisibleExclusive > spanData.visibleText.length()) {
            return null;
        }

        int cursor = nameEndVisibleExclusive;
        while (cursor < spanData.visibleText.length() && Character.isWhitespace(spanData.visibleText.charAt(cursor))) {
            cursor++;
        }

        if (cursor >= spanData.visibleText.length() || spanData.visibleText.charAt(cursor) != '[') {
            return null;
        }

        int closeBracket = spanData.visibleText.indexOf(']', cursor + 1);
        if (closeBracket < 0) {
            return null;
        }

        String inside = spanData.visibleText.substring(cursor + 1, closeBracket).trim();
        if (inside.isEmpty() || inside.length() > 32) {
            return null;
        }

        // KillCounter TAB suffix is " §c[3]" etc. — same shape as a short guild tag; do not consume it into the
        // rank/name replacement or the kill count disappears for disguised players.
        if (isBracketContentNumericKillCounter(inside)) {
            return null;
        }

        // TAB stats from KillCounter: "[1.23wl]", "[10w]" (≤6 visible chars) — not guild tags; swallowing them hid WL/wins.
        if (isLikelyTabStatsBracketContent(inside)) {
            return null;
        }

        GuildSuffixRange range = new GuildSuffixRange();
        range.formattedStartInclusive = spanData.visibleToFormattedStart[cursor];
        range.formattedEndExclusive = spanData.visibleToFormattedEnd[closeBracket];
        return range;
    }

    /**
     * KillCounter /tabstats suffix brackets immediately after the IGN (short forms like {@code [1.2wl]} or {@code [99w]}).
     */
    private static boolean isLikelyTabStatsBracketContent(String inside) {
        if (inside == null || inside.isEmpty()) {
            return false;
        }
        String s = inside.replace(" ", "").toLowerCase(Locale.ROOT);
        if (s.contains("wl")) {
            return true;
        }
        return s.length() >= 2 && s.endsWith("w") && s.chars().anyMatch(Character::isDigit);
    }

    private static boolean isLikelyGuildSuffix(String between) {
        if (between == null || between.isEmpty()) {
            return false;
        }
        String trimmed = between.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return false;
        }
        int insideLength = trimmed.length() - 2;
        return insideLength > 0 && insideLength <= 6;
    }

    private static boolean isPartyChatLine(String visible, int nameIndex) {
        if (visible == null || visible.isEmpty() || nameIndex < 0) {
            return false;
        }
        String lower = visible.toLowerCase(Locale.ROOT);
        int partyIndex = lower.indexOf("party >");
        if (partyIndex < 0) {
            return false;
        }
        return partyIndex < nameIndex;
    }

    private static boolean isGuildChatLine(String visible, int nameIndex) {
        if (visible == null || visible.isEmpty() || nameIndex < 0) {
            return false;
        }
        String lower = visible.toLowerCase(Locale.ROOT);
        int guildIndex = lower.indexOf("guild >");
        if (guildIndex < 0) {
            return false;
        }
        return guildIndex < nameIndex;
    }

    private static boolean isOfficerChatLine(String visible, int nameIndex) {
        if (visible == null || visible.isEmpty() || nameIndex < 0) {
            return false;
        }
        String lower = visible.toLowerCase(Locale.ROOT);
        int officerIndex = lower.indexOf("officer >");
        if (officerIndex < 0) {
            return false;
        }
        return officerIndex < nameIndex;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return indexOfIgnoreCase(text, needle, 0);
    }

    private static int indexOfIgnoreCase(String text, String needle, int fromIndex) {
        if (text == null || needle == null || fromIndex < 0) {
            return -1;
        }
        int textLength = text.length();
        int needleLength = needle.length();
        if (needleLength == 0) {
            return fromIndex <= textLength ? fromIndex : -1;
        }
        if (fromIndex > textLength - needleLength) {
            return -1;
        }
        for (int i = fromIndex; i <= textLength - needleLength; i++) {
            if (text.regionMatches(true, i, needle, 0, needleLength)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMinecraftIgnChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * Like {@link #indexOfIgnoreCase(String, String, int)} but only matches when {@code needle} is not embedded in a
     * larger alphanumeric token (e.g. avoids {@code Knight} inside {@code Shadow Knight} kit titles). Minecraft
     * formatting codes are zero-width for the boundary check: {@code §cOnSeb} is still a standalone IGN.
     */
    private static int indexOfIgnoreCaseStandalone(String text, String needle, int fromIndex) {
        if (text == null || needle == null || needle.length() == 0) {
            return -1;
        }
        if (needle.length() < 3 || fromIndex < 0) {
            return indexOfIgnoreCase(text, needle, fromIndex);
        }
        int i = fromIndex;
        while (i <= text.length() - needle.length()) {
            int hit = indexOfIgnoreCase(text, needle, i);
            if (hit < 0) {
                return -1;
            }
            int previousVisible = hit - 1;
            while (previousVisible >= 1 && text.charAt(previousVisible - 1) == '\u00A7') {
                previousVisible -= 2;
            }
            boolean leftOk = previousVisible < 0 || !isMinecraftIgnChar(text.charAt(previousVisible));
            int after = hit + needle.length();
            while (after + 1 < text.length() && text.charAt(after) == '\u00A7') {
                after += 2;
            }
            boolean rightOk = after >= text.length() || !isMinecraftIgnChar(text.charAt(after));
            if (leftOk && rightOk) {
                return hit;
            }
            i = hit + 1;
        }
        return -1;
    }

    /**
     * Visible index of the {@code '['} opening the leftmost bracket in the run of {@code [..] [..] ...} tokens
     * (separated only by whitespace) immediately before {@code nameIndex}.
     */
    private static int findLeftmostBracketPrefixChainStart(String visible, int nameIndex) {
        if (visible == null || nameIndex <= 0 || nameIndex > visible.length()) {
            return -1;
        }
        int pos = nameIndex;
        int leftmost = -1;
        while (pos > 0) {
            int open = findBracketOpenImmediatelyBefore(visible, pos);
            if (open < 0) {
                break;
            }
            leftmost = open;
            pos = open;
        }
        return leftmost;
    }

    /**
     * Walks the whitespace-separated {@code [..]} run immediately before {@code nameIndex} and returns the visible
     * index of the opening {@code '['} of the leftmost bracket that is <b>not</b> a Blitz chat channel tag
     * ({@code [SPECTATOR]}, {@code [TEAM]}, {@code [SHOUT]}). Used so we can remove Hypixel rank / kill brackets
     * without stripping those channel prefixes.
     */
    private static int findConsumableHypixelPrefixChainStartBeforeName(String visible, int nameIndex) {
        if (visible == null || nameIndex <= 0 || nameIndex > visible.length()) {
            return -1;
        }
        int consumableStart = -1;
        int pos = nameIndex;
        while (pos > 0) {
            int open = findBracketOpenImmediatelyBefore(visible, pos);
            if (open < 0) {
                break;
            }
            int close = visible.indexOf(']', open + 1);
            if (close < 0 || close >= pos) {
                break;
            }
            String inside = visible.substring(open + 1, close).trim();
            if (isBlitzChatChannelSpeakerTag(inside)) {
                break;
            }
            consumableStart = open;
            pos = open;
        }
        return consumableStart;
    }

    private static boolean isBlitzChatChannelSpeakerTag(String inside) {
        if (inside == null || inside.isEmpty()) {
            return false;
        }
        String u = inside.toUpperCase(Locale.ROOT);
        return "SPECTATOR".equals(u) || "TEAM".equals(u) || "SHOUT".equals(u);
    }

    /** Opening {@code '['} of the {@code [..]} pair whose closing {@code ']'} sits just before {@code endExclusive} (whitespace allowed). */
    private static int findBracketOpenImmediatelyBefore(String visible, int endExclusive) {
        if (visible == null || endExclusive <= 0 || endExclusive > visible.length()) {
            return -1;
        }
        int cursor = endExclusive - 1;
        while (cursor >= 0 && Character.isWhitespace(visible.charAt(cursor))) {
            cursor--;
        }
        if (cursor < 0 || visible.charAt(cursor) != ']') {
            return -1;
        }
        int bracketStart = visible.lastIndexOf('[', cursor);
        if (bracketStart < 0) {
            return -1;
        }
        int between = cursor + 1;
        while (between < endExclusive && Character.isWhitespace(visible.charAt(between))) {
            between++;
        }
        if (between < endExclusive) {
            return -1;
        }
        return bracketStart;
    }

    /** True for Blitz-style chat kill counts like {@code [58689]} (digits / grouping only). */
    private static boolean isBracketContentNumericKillCounter(String inside) {
        if (inside == null || inside.isEmpty()) {
            return false;
        }
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (!Character.isDigit(c) && c != ',' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static int findImmediateKillPrefixStart(String visible, int segmentStart) {
        if (visible == null || segmentStart <= 0 || segmentStart > visible.length()) {
            return -1;
        }

        int cursor = segmentStart - 1;
        while (cursor >= 0 && Character.isWhitespace(visible.charAt(cursor))) {
            cursor--;
        }
        if (cursor < 0 || visible.charAt(cursor) != ']') {
            return -1;
        }

        int bracketStart = visible.lastIndexOf('[', cursor);
        if (bracketStart < 0) {
            return -1;
        }

        String inside = visible.substring(bracketStart + 1, cursor).trim();
        if (!isBracketContentNumericKillCounter(inside)) {
            return -1;
        }

        int between = cursor + 1;
        while (between < segmentStart && Character.isWhitespace(visible.charAt(between))) {
            between++;
        }
        if (between < segmentStart) {
            return -1;
        }

        return bracketStart;
    }

    private static String getActiveFormattingBefore(String formattedText, int formattedIndex) {
        if (formattedText == null || formattedText.isEmpty() || formattedIndex <= 0) {
            return "";
        }

        String color = "";
        StringBuilder styles = new StringBuilder();

        for (int i = 0; i < formattedText.length() && i < formattedIndex; i++) {
            char c = formattedText.charAt(i);
            if (c != '\u00A7' || i + 1 >= formattedText.length()) {
                continue;
            }

            char code = Character.toLowerCase(formattedText.charAt(i + 1));
            if (code == 'r') {
                color = "";
                styles.setLength(0);
            } else if (isColorCode(code)) {
                color = "\u00A7" + formattedText.charAt(i + 1);
                styles.setLength(0);
            } else if (isFormatCode(code)) {
                String token = "\u00A7" + formattedText.charAt(i + 1);
                if (styles.indexOf(token) < 0) {
                    styles.append(token);
                }
            }
            i++;
        }

        return color + styles.toString();
    }

    private static String getLastColorCode(String formatting) {
        if (formatting == null || formatting.isEmpty()) {
            return null;
        }

        String lastColor = null;
        for (int i = 0; i < formatting.length() - 1; i++) {
            if (formatting.charAt(i) != '\u00A7') {
                continue;
            }

            char code = Character.toLowerCase(formatting.charAt(i + 1));
            if (isColorCode(code)) {
                lastColor = "\u00A7" + formatting.charAt(i + 1);
            } else if (code == 'r') {
                lastColor = "\u00A7r";
            }
            i++;
        }
        return lastColor;
    }

    private static boolean isColorCode(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    private static boolean isFormatCode(char code) {
        return code == 'k' || code == 'l' || code == 'm' || code == 'n' || code == 'o';
    }

    private static String fixGuildTagUnicode(String guildTag) {
        if (guildTag == null || guildTag.isEmpty()) {
            return guildTag;
        }

        guildTag = guildTag.replace("\uFE0F", "").replace("\uFE0E", "");
        guildTag = guildTag
                .replace("\u2665", "\u2764")
                .replace("\u2661", "\u2764")
                .replace("\u2765", "\u2764")
                .replace("\u2763", "\u2764")
                .replace("\uD83D\uDC9A", "\u2764")
                .replace("\uD83D\uDC9B", "\u2764")
                .replace("\uD83D\uDC99", "\u2764")
                .replace("\uD83D\uDC9C", "\u2764")
                .replace("\uD83D\uDDA4", "\u2764")
                .replace("\uD83E\uDDE1", "\u2764")
                .replace("\uD83E\uDD0D", "\u2764")
                .replace("\uD83E\uDD0E", "\u2764")
                .replace("\uD83E\uDE77", "\u2764")
                .replace("\uD83E\uDE75", "\u2764")
                .replace("\uD83E\uDE76", "\u2764")
                .replace("&hearts;", "\u2764")
                .replace("&lt;3", "\u2764")
                .replace("<3", "\u2764");
        return guildTag;
    }

    private static String getGuildTagColor(String tagColor) {
        if (tagColor == null || tagColor.isEmpty()) {
            return EnumChatFormatting.WHITE.toString();
        }

        switch (tagColor.toUpperCase(Locale.ROOT)) {
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

    private static int findImmediateRankPrefixStart(String visible, int nameIndex) {
        if (visible == null || nameIndex <= 0 || nameIndex > visible.length()) {
            return -1;
        }

        int searchEnd = nameIndex;
        while (searchEnd > 0) {
            int cursor = searchEnd - 1;
            while (cursor >= 0 && Character.isWhitespace(visible.charAt(cursor))) {
                cursor--;
            }
            if (cursor < 0 || visible.charAt(cursor) != ']') {
                return -1;
            }

            int bracketStart = visible.lastIndexOf('[', cursor);
            if (bracketStart < 0) {
                return -1;
            }

            int between = cursor + 1;
            while (between < searchEnd && Character.isWhitespace(visible.charAt(between))) {
                between++;
            }
            if (between < searchEnd) {
                return -1;
            }

            String inside = visible.substring(bracketStart + 1, cursor).trim();
            // [58689] etc. is a kill counter, not a rank — keep scanning left for [MVP+], [VIP], [DIST2], ...
            if (isBracketContentNumericKillCounter(inside)) {
                searchEnd = bracketStart;
                continue;
            }

            return bracketStart;
        }

        return -1;
    }


    private static SpanData buildVisibleMapping(String formattedText) {
        int visibleCount = 0;
        for (int i = 0; i < formattedText.length(); i++) {
            char c = formattedText.charAt(i);
            if (c == '\u00A7' && i + 1 < formattedText.length()) {
                i++;
                continue;
            }
            visibleCount++;
        }

        int[] starts = new int[visibleCount];
        int[] ends = new int[visibleCount];
        StringBuilder visible = new StringBuilder(visibleCount);

        int visibleIndex = 0;
        for (int i = 0; i < formattedText.length(); ) {
            char c = formattedText.charAt(i);
            if (c == '\u00A7' && i + 1 < formattedText.length()) {
                i += 2;
                continue;
            }

            starts[visibleIndex] = i;
            i++;
            ends[visibleIndex] = i;
            visible.append(c);
            visibleIndex++;
        }

        SpanData data = new SpanData();
        data.visibleText = visible.toString();
        data.visibleToFormattedStart = starts;
        data.visibleToFormattedEnd = ends;
        return data;
    }

    public interface HypixelDataDecisionHandler {
        void onUseCache(HypixelDataDecision decision);

        void onWait(HypixelDataDecision decision);

        void onCancel();
    }

    public static final class HypixelDataDecisionRequiredException extends Exception {
        private final HypixelDataDecision decision;

        private HypixelDataDecisionRequiredException(HypixelDataDecision decision) {
            super(decision == null ? "Hypixel disguise decision required." : decision.reason);
            this.decision = decision;
        }

        public HypixelDataDecision getDecision() {
            return decision;
        }
    }

    public static final class HypixelDataDecision {
        public final String targetName;
        public final String targetUuid;
        public final String reason;
        public final boolean canUseCache;
        public final boolean canWait;
        public final long waitMillis;

        private HypixelDataDecision(String targetName, String targetUuid, String reason, boolean canUseCache, boolean canWait, long waitMillis) {
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.reason = reason == null ? "" : reason;
            this.canUseCache = canUseCache;
            this.canWait = canWait;
            this.waitMillis = Math.max(0L, waitMillis);
        }
    }

    public static final class DisguiseKitInfo {
        public final String displayName;
        public final String apiName;
        public final int level;
        public final int prestige;
        public final boolean prestiged;
        public final String levelDisplay;
        public final int kitKills;
        public final int kitSoloWins;
        public final int kitTeamWins;
        public final int kitTotalWins;

        private DisguiseKitInfo(String displayName, String apiName, int level, int prestige, boolean prestiged,
                                String levelDisplay, int kitKills, int kitSoloWins, int kitTeamWins) {
            this.displayName = displayName;
            this.apiName = apiName;
            this.level = level;
            this.prestige = prestige;
            this.prestiged = prestiged;
            this.levelDisplay = levelDisplay;
            this.kitKills = Math.max(0, kitKills);
            this.kitSoloWins = Math.max(0, kitSoloWins);
            this.kitTeamWins = Math.max(0, kitTeamWins);
            this.kitTotalWins = this.kitSoloWins + this.kitTeamWins;
        }
    }

    private enum HypixelDisguiseDataMode {
        AUTO,
        CACHE_ONLY,
        FRESH_ONLY
    }

    private enum HypixelFetchStatus {
        SUCCESS,
        NO_API_KEY,
        INVALID_API_KEY,
        RATE_LIMIT,
        ERROR
    }

    private static final class HypixelFetchResult<T> {
        private final T data;
        private final HypixelFetchStatus status;
        private final String message;

        private HypixelFetchResult(T data, HypixelFetchStatus status, String message) {
            this.data = data;
            this.status = status;
            this.message = message;
        }

        private static <T> HypixelFetchResult<T> success(T data) {
            return new HypixelFetchResult<>(data, HypixelFetchStatus.SUCCESS, "");
        }

        private static <T> HypixelFetchResult<T> failure(HypixelFetchStatus status, String message, T fallbackData) {
            return new HypixelFetchResult<>(fallbackData, status == null ? HypixelFetchStatus.ERROR : status, message);
        }
    }

    private static final class TargetHypixelData {
        private final RankData rankData;
        private final GuildTagData guildTagData;

        private TargetHypixelData(RankData rankData, GuildTagData guildTagData) {
            this.rankData = rankData == null ? RankData.unresolved() : rankData;
            this.guildTagData = guildTagData == null ? GuildTagData.empty() : guildTagData;
        }
    }

    private static final class CachedHypixelDisguiseData {
        private final RankData rankData;
        private final GuildTagData guildTagData;
        private final long fetchedAtMs;

        private CachedHypixelDisguiseData(RankData rankData, GuildTagData guildTagData, long fetchedAtMs) {
            this.rankData = rankData;
            this.guildTagData = guildTagData;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    public static final class EnableResult {
        public final String targetName;
        public final String targetUuid;
        public final boolean skinLoaded;
        public final boolean capeLoaded;
        public final boolean rankLoaded;
        public final boolean killCounterEnabled;
        public final boolean alternativeKillMessageEnabled;

        private EnableResult(String targetName, String targetUuid, boolean skinLoaded, boolean capeLoaded, boolean rankLoaded,
                             boolean killCounterEnabled, boolean alternativeKillMessageEnabled) {
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.skinLoaded = skinLoaded;
            this.capeLoaded = capeLoaded;
            this.rankLoaded = rankLoaded;
            this.killCounterEnabled = killCounterEnabled;
            this.alternativeKillMessageEnabled = alternativeKillMessageEnabled;
        }
    }

    private static final class TextureData {
        private String skinType;
        private ResourceLocation skinLocation;
        private ResourceLocation capeLocation;
        private boolean hasCustomSkin;
        private boolean hasCustomCape;
        private List<CapeOption> capeOptions = Collections.emptyList();
    }

    private static final class RankData {
        private final String rank;
        private final String rankPlusColor;
        private final String monthlyRankColor;
        private final boolean known;
        private final double networkExp;
        private final boolean networkLevelKnown;
        private final String guildRank;
        private final BlitzStats blitzStats;
        private final int achievementPoints;
        private final boolean achievementPointsKnown;

        private RankData(String rank, String rankPlusColor, String monthlyRankColor, boolean known, double networkExp,
                         boolean networkLevelKnown, String guildRank, BlitzStats blitzStats,
                         int achievementPoints, boolean achievementPointsKnown) {
            this.rank = rank;
            this.rankPlusColor = rankPlusColor;
            this.monthlyRankColor = monthlyRankColor;
            this.known = known;
            this.networkExp = networkExp;
            this.networkLevelKnown = networkLevelKnown;
            this.guildRank = guildRank == null ? "" : guildRank;
            this.blitzStats = blitzStats == null ? BlitzStats.empty() : blitzStats;
            this.achievementPoints = achievementPoints;
            this.achievementPointsKnown = achievementPointsKnown;
        }

        private static RankData defaultResolvedRank(double networkExp, boolean networkLevelKnown, String guildRank, BlitzStats blitzStats,
                                                    int achievementPoints, boolean achievementPointsKnown) {
            return new RankData("Default", "", "", true, networkExp, networkLevelKnown, guildRank, blitzStats,
                    achievementPoints, achievementPointsKnown);
        }

        private static RankData unresolved() {
            return new RankData("Default", "", "", false, 0.0D, false, "", BlitzStats.empty(), 0, false);
        }
    }

    private static final class BlitzStats {
        private final Integer kills;
        private final Integer soloWins;
        private final Integer teamWins;
        private final Integer coins;
        private final Integer deaths;
        private final boolean chatKillsEnabled;
        private final boolean alternativeKillMessageEnabled;
        private final Map<String, DisguiseKitInfo> kitInfoByName;
        private final Map<String, String> renamedItemsByKey;

        private BlitzStats(Integer kills, Integer soloWins, Integer teamWins, Integer coins, Integer deaths,
                           boolean chatKillsEnabled,
                           boolean alternativeKillMessageEnabled, Map<String, DisguiseKitInfo> kitInfoByName,
                           Map<String, String> renamedItemsByKey) {
            this.kills = kills;
            this.soloWins = soloWins;
            this.teamWins = teamWins;
            this.coins = coins;
            this.deaths = deaths;
            this.chatKillsEnabled = chatKillsEnabled;
            this.alternativeKillMessageEnabled = alternativeKillMessageEnabled;
            this.kitInfoByName = kitInfoByName == null ? Collections.emptyMap() : new LinkedHashMap<>(kitInfoByName);
            this.renamedItemsByKey = renamedItemsByKey == null ? Collections.emptyMap() : new LinkedHashMap<>(renamedItemsByKey);
        }

        private static BlitzStats empty() {
            return new BlitzStats(null, null, null, null, null, false, false, Collections.emptyMap(), Collections.emptyMap());
        }

        private boolean isEmpty() {
            return kills == null && soloWins == null && teamWins == null && coins == null;
        }

        /** Same formatting as {@code KillEffectCommand} stats suffix for TAB. */
        private String formatTabStatsSuffix() {
            try {
                int soloW = soloWins != null ? soloWins : 0;
                int teamW = teamWins != null ? teamWins : 0;
                int totalWins = soloW + teamW;
                int d = deaths != null ? deaths : 0;
                int k = kills != null ? kills : 0;
                double wl = d <= 0 ? (double) totalWins : (double) totalWins / (double) d;
                TabStatsBlitzMetrics metrics = new TabStatsBlitzMetrics(
                        TabStatsBlitzMetrics.colorPrefixForWl(wl), wl, totalWins, k, d);
                return metrics.formatSuffix(
                        KillCounter.isStatsShowKd(),
                        KillCounter.isStatsShowWl(),
                        KillCounter.isStatsShowW());
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    private static final class CapeOption {
        private final String key;
        private final String label;
        private final ResourceLocation location;
        private final String textureHash;
        private final String imageUrl;
        private final String sourceType;

        private CapeOption(String key, String label, ResourceLocation location, String textureHash) {
            this(key, label, location, textureHash, null, null);
        }

        private CapeOption(String key, String label, ResourceLocation location, String textureHash, String imageUrl) {
            this(key, label, location, textureHash, imageUrl, null);
        }

        private CapeOption(String key, String label, ResourceLocation location, String textureHash, String imageUrl,
                String sourceType) {
            this.key = key;
            this.label = label;
            this.location = location;
            this.textureHash = textureHash;
            this.imageUrl = imageUrl;
            this.sourceType = sourceType;
        }
    }

    private static final class ExternalCapeSource {
        private final String sourceType;
        private final String label;
        private final String imageUrl;
        private final String textureHash;

        private ExternalCapeSource(String sourceType, String label, String imageUrl, String textureHash) {
            this.sourceType = sourceType;
            this.label = label;
            this.imageUrl = imageUrl;
            this.textureHash = textureHash;
        }
    }

    private static final class CapeImageBuffer implements IImageBuffer {
        @Override
        public BufferedImage parseUserSkin(BufferedImage image) {
            return normalizeCapeImage(image);
        }

        @Override
        public void skinAvailable() {
        }
    }

    private enum TournamentKillCounterMode {
        FOLLOW("FOLLOW"),
        FORCE_ON("FORCE_ON"),
        FORCE_OFF("FORCE_OFF");

        private final String configValue;

        TournamentKillCounterMode(String configValue) {
            this.configValue = configValue;
        }

        private TournamentKillCounterMode next() {
            switch (this) {
                case FOLLOW:
                    return FORCE_ON;
                case FORCE_ON:
                    return FORCE_OFF;
                default:
                    return FOLLOW;
            }
        }

        private static TournamentKillCounterMode fromConfigValue(String value) {
            if ("FORCE_ON".equalsIgnoreCase(value)) {
                return FORCE_ON;
            }
            if ("FORCE_OFF".equalsIgnoreCase(value)) {
                return FORCE_OFF;
            }
            return FOLLOW;
        }
    }

    private enum HypixelPrefixMode {
        SHOW("SHOW"),
        HIDE("HIDE"),
        HYPIXEL_OFFICIAL("HYPIXEL_OFFICIAL"),
        CUSTOM("CUSTOM");

        private final String configValue;

        HypixelPrefixMode(String configValue) {
            this.configValue = configValue;
        }

        private HypixelPrefixMode next() {
            switch (this) {
                case SHOW:
                    return HIDE;
                case HIDE:
                    return HYPIXEL_OFFICIAL;
                case HYPIXEL_OFFICIAL:
                    return CUSTOM;
                default:
                    return SHOW;
            }
        }

        private static HypixelPrefixMode fromConfigValue(String value) {
            if ("DEFAULT".equalsIgnoreCase(value)) {
                return SHOW;
            }
            if ("HIDE".equalsIgnoreCase(value)) {
                return HIDE;
            }
            if ("HYPIXEL_OFFICIAL".equalsIgnoreCase(value) || "OFFICIAL".equalsIgnoreCase(value)) {
                return HYPIXEL_OFFICIAL;
            }
            if ("CUSTOM".equalsIgnoreCase(value)) {
                return CUSTOM;
            }
            return SHOW;
        }
    }

    private static final class GuildTagData {
        private final String tag;
        private final String colorFormatting;

        private GuildTagData(String tag, String colorFormatting) {
            this.tag = tag == null ? "" : tag;
            this.colorFormatting = colorFormatting == null ? "" : colorFormatting;
        }

        private static GuildTagData empty() {
            return new GuildTagData("", "");
        }
    }

    private static final class SidebarStatMatch {
        private final SidebarStatKind kind;
        private final int labelStartVisible;
        private final int labelLength;

        private SidebarStatMatch(SidebarStatKind kind, int labelStartVisible, int labelLength) {
            this.kind = kind;
            this.labelStartVisible = labelStartVisible;
            this.labelLength = labelLength;
        }
    }

    private enum SidebarStatKind {
        KILLS(
                "blitz kills:",
                "blitz kills :",
                "total kills:",
                "total kills :",
                "overall kills:",
                "overall kills :",
                "k/d:",
                "k/d :",
                "kd:",
                "kd :",
                "kills:",
                "kills :"),
        SOLO_WINS(
                "overall wins:",
                "overall wins :",
                "solo wins:",
                "solo wins :",
                "solo win:",
                "solo win :"),
        TEAM_WINS(
                "teams wins:",
                "teams wins :",
                "team wins:",
                "team wins :",
                "team win:",
                "teams win:"),
        COINS(
                "blitz coins:",
                "blitz coins :",
                "hypixel blitz coins:",
                "coins:",
                "coins :"),
        /** Main Hypixel hub — longer aliases first within this group (see {@link #detectionOrder()}). */
        LOBBY_ACHIEVEMENT_POINTS(
                "achievement points:",
                "achievement points :",
                "achievements:",
                "achievements :",
                "achievement:",
                "achievement :",
                "achievement pts:",
                "achievement pts :",
                "achievement score:",
                "achievement score :",
                "total achievement:",
                "total achievement :",
                "ap:",
                "ap :"),
        LOBBY_HYPIXEL_LEVEL(
                "hypixel level:",
                "hypixel level :",
                "network level:",
                "network level :",
                "hypixel lvl:",
                "hypixel lvl :",
                "nw level:",
                "nw level :"),
        LOBBY_RANK(
                "your rank:",
                "your rank :",
                "rank:",
                "rank :"),
        LOBBY_LEVEL(
                "level:",
                "level :",
                "lvl:",
                "lvl :");

        private final String[] aliases;
        private final String[] aliasesLongestFirst;

        SidebarStatKind(String... aliases) {
            this.aliases = aliases;
            String[] copy = aliases.clone();
            Arrays.sort(copy, (a, b) -> Integer.compare(b.length(), a.length()));
            this.aliasesLongestFirst = copy;
        }

        /**
         * Blitz stats first; then main hub (achievement / hypixel level / rank / level) so hub lines are still
         * detected when not in a Blitz sidebar.
         */
        private static SidebarStatKind[] detectionOrder() {
            return new SidebarStatKind[] {
                    KILLS, TEAM_WINS, SOLO_WINS, COINS,
                    LOBBY_ACHIEVEMENT_POINTS, LOBBY_HYPIXEL_LEVEL, LOBBY_RANK, LOBBY_LEVEL
            };
        }

        private String[] getAliasesLongestFirst() {
            return aliasesLongestFirst;
        }

        private boolean matches(String visibleLower) {
            return getMatchedLabelLength(visibleLower) > 0;
        }

        private int getMatchedLabelLength(String visibleLower) {
            if (visibleLower == null) {
                return -1;
            }
            for (String alias : aliasesLongestFirst) {
                if (visibleLower.startsWith(alias)) {
                    return alias.length();
                }
            }
            return -1;
        }
    }

    private static final class LocalIdentity {
        private final String name;
        private final UUID uuid;

        private LocalIdentity(String name, UUID uuid) {
            this.name = name;
            this.uuid = uuid;
        }
    }

    private static final class LiveTextureReferences {
        private ResourceLocation skinLocation;
        private ResourceLocation capeLocation;
        private ResourceLocation usernameSkinLocation;
    }

    private static final class ResolvedTarget {
        private String name;
        private String uuidUndashed;
        private UUID uuid;
        private String texturesValue;
        private String texturesSignature;
        private String skinType;
        private String skinUrl;
        private String capeUrl;
        /** Absolute path to a 64x64 (or valid) player skin PNG when not using Mojang URLs. */
        private String localSkinFilePath;
        private List<ExternalCapeSource> externalCapeSources = Collections.emptyList();
        /** Offline nick: TAB/chat may use § codes from {@code &} in the GUI. */
        private String tabChatDisplayNick;
        private boolean offlineNickDisguise;
    }

    private static final class SpanData {
        private String visibleText;
        private int[] visibleToFormattedStart;
        private int[] visibleToFormattedEnd;
    }

    private static final class GuildSuffixRange {
        private int formattedStartInclusive;
        private int formattedEndExclusive;
    }

}
