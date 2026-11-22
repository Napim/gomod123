package me.napim.gomod.features;

import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.UUID;
import java.lang.reflect.Method;

/** Opt-in, identity-free capture for diagnosing Lunar logo nametag behavior across FullAccess toggles. */
public final class LunarNametagDebugCapture {
    private static final Object LOCK = new Object();
    private static final int MAX_PHASES = 40;
    private static final int MAX_DISPLAY_COLOR_SAMPLES = 12;
    private static final int MAX_PIPELINE_SAMPLES = 80;
    private static final int MAX_TAB_DRAW_SAMPLES = 20;
    private static final List<Phase> PHASES = new ArrayList<Phase>();
    private static final Set<UUID> INSPECTED_ZERO_CACHE_PLAYERS = new HashSet<UUID>();

    private static volatile boolean active;
    private static File output;
    private static int nextPhase;
    private static String lastProfileSummary;
    private static long captureStartedAtMs;
    private static volatile String nametagHookStatus = "not_reported";
    private static int nametagCompileHooks;
    private static int nametagDrawHooks;
    private static int nametagLabelReturnHooks;

    private LunarNametagDebugCapture() {
    }

    public static File start() throws Exception {
        synchronized (LOCK) {
            if (active) {
                return output;
            }
            File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("Could not create diagnostic directory");
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                    .format(new Date());
            output = new File(directory, "lunar_nametag_toggle_" + timestamp + ".log");
            PHASES.clear();
            INSPECTED_ZERO_CACHE_PLAYERS.clear();
            nextPhase = 0;
            lastProfileSummary = null;
            captureStartedAtMs = System.currentTimeMillis();
            active = true;
            Phase phase = newPhaseLocked(ConfigManager.isLunarTweaksEnabled(), "capture_start");
            appendLocked("capture.start version=8 " + runtimeSnapshotLocked()
                    + " nametagHooks=" + nametagHookStatus);
            appendLocked("capture.color_sources " + GomodLogoColorSync.describeLocalColorPipelineState());
            recordProfileStateLocked("capture_start");
            appendLocked("phase.start index=" + phase.index + " fullAccessEnabled=" + phase.fullAccessEnabled
                    + " reason=capture_start");
            return output;
        }
    }

    public static File stop() {
        synchronized (LOCK) {
            if (!active) {
                return output;
            }
            for (Phase phase : PHASES) {
                appendLocked(phase.summary());
            }
            appendLocked("capture.stop " + runtimeSnapshotLocked());
            active = false;
            return output;
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** Records each user selection callback in order while the diagnostic is explicitly active. */
    public static void recordColorSelection(String kind, int rgb) {
        recordColorFlow("selection_callback", kind, rgb, "accepted");
    }

    /** Bounded color-pipeline event with local cache state; it never records player IDs or payloads. */
    public static void recordColorFlow(String event, String kind, int rgb, String outcome) {
        synchronized (LOCK) {
            if (!active) return;
            Phase phase = currentPhaseLocked();
            if (phase.pipelineEvents >= MAX_PIPELINE_SAMPLES) return;
            phase.pipelineEvents++;
            appendLocked("color.flow phase=" + phase.index
                    + " event=" + token(event)
                    + " kind=" + token(kind)
                    + " rgb=" + rgbLabel(rgb)
                    + " result=" + token(outcome)
                    + " " + GomodLogoColorSync.describeLocalColorPipelineState());
        }
    }

    /** Only the category is retained; the raw Lunar route string is intentionally discarded. */
    public static void recordLockerNavigation(String route) {
        if (!active) return;
        String value = route == null ? "" : route.toLowerCase(Locale.ROOT);
        String page = value.contains("plus") ? "lunar_plus"
                : value.contains("logo") && value.contains("color") ? "logo_color" : "other";
        recordColorFlow("locker_navigation", page, -1, "route_changed");
    }

    /** Records only this account's current native profile colors and the color values in the push. */
    public static void recordNativeProfileUpdate(boolean localTarget, Object push,
            String nativeProfileState) {
        synchronized (LOCK) {
            if (!active) return;
            if (!localTarget) return;
            Phase phase = currentPhaseLocked();
            if (phase.pipelineEvents >= MAX_PIPELINE_SAMPLES) return;
            phase.pipelineEvents++;
            appendLocked("profile.update phase=" + phase.index + " target=self"
                    + " pushLogo=" + readPushRgb(push, "getLogoColor")
                    + " pushPlus=" + readPushRgb(push, "getPlusColor")
                    + " native=" + safeState(nativeProfileState)
                    + " " + GomodLogoColorSync.describeLocalColorPipelineState());
        }
    }

    /** Called by TAB's native render hooks; duplicate steady frames are coalesced. */
    public static void recordTabDraw(UUID playerId, String slot, boolean visible) {
        recordTabDraw(playerId, slot, visible, false, -1);
    }

    /** Captures the plus draw decision and disguise-specific visibility inputs, without identity data. */
    public static void recordTabDraw(UUID playerId, String slot, boolean visible,
            boolean fullDisguise, int plusRgb) {
        if (!active || playerId == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                ? null : mc.getSession().getProfile().getId();
        if (!playerId.equals(localId)) return;
        String state = GomodLogoColorSync.describeLocalColorPipelineState();
        synchronized (LOCK) {
            if (!active) return;
            Phase phase = currentPhaseLocked();
            String sample = "slot=" + token(slot) + " visible=" + visible
                    + " fullDisguise=" + fullDisguise + " plusRgb=" + rgbLabel(plusRgb) + " " + state;
            if (phase.tabDrawSamples.size() >= MAX_TAB_DRAW_SAMPLES
                    || !phase.tabDrawSamples.add(sample)) return;
            appendLocked("tab.draw phase=" + phase.index + " " + sample);
        }
    }

    /** Manual timestamp marker lets the user tie the capture to a visual checkpoint. */
    public static boolean recordMarker(String label) {
        if (label == null || !label.matches("[A-Za-z0-9_-]{1,32}")) return false;
        recordColorFlow("user_marker", "checkpoint", -1, label);
        return active;
    }

    /** Persists transform-hook counts so a zero draw hook is visible in the saved capture. */
    public static void reportNametagInstrumentation(int compileHooks, int drawHooks, int labelReturnHooks) {
        synchronized (LOCK) {
            // The compiler/list-dispatch hook lives in Lunar's font wrapper while the
            // label-return hook lives in RendererLivingEntity. Aggregate both transforms.
            nametagCompileHooks = Math.max(nametagCompileHooks, Math.max(0, compileHooks));
            nametagDrawHooks = Math.max(nametagDrawHooks, Math.max(0, drawHooks));
            nametagLabelReturnHooks = Math.max(nametagLabelReturnHooks, Math.max(0, labelReturnHooks));
            nametagHookStatus = "compile=" + nametagCompileHooks
                    + ",draw=" + nametagDrawHooks
                    + ",labelReturn=" + nametagLabelReturnHooks;
            if (active) appendLocked("nametag.hooks " + nametagHookStatus);
        }
    }

    private static String readPushRgb(Object push, String getterName) {
        if (push == null) return "absent";
        try {
            Object color = push.getClass().getMethod(getterName).invoke(push);
            if (color == null) return "absent";
            Object rgb;
            try {
                rgb = color.getClass().getMethod("getColor").invoke(color);
            } catch (NoSuchMethodException missingColor) {
                rgb = color.getClass().getMethod("getRgb").invoke(color);
            }
            return rgb instanceof Number ? rgbLabel(((Number) rgb).intValue()) : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /**
     * Traces the display-only native nametag lookup without player identifiers. Samples are
     * deduplicated and bounded so leaving capture on cannot produce an unbounded render log.
     */
    public static void recordNametagColorLookup(boolean keyIsUuid, boolean localIdKnown,
            boolean localMatch, boolean sourceProfilePresent, int localLogoRgb, int localPlusRgb,
            boolean displayCopyApplied) {
        synchronized (LOCK) {
            if (!active) return;
            Phase phase = currentPhaseLocked();
            phase.nametagColorLookups++;
            if (keyIsUuid) phase.nametagUuidLookups++;
            if (localIdKnown) phase.localIdentityChecks++;
            if (localMatch) {
                phase.localNametagLookups++;
                if (sourceProfilePresent) phase.localSourceProfilesPresent++;
                if (displayCopyApplied) phase.localDisplayCopiesApplied++;
            }

            if (!localMatch && localIdKnown) return;
            String sample = "keyIsUuid=" + keyIsUuid + " localIdKnown=" + localIdKnown
                    + " localMatch=" + localMatch + " sourceProfile=" + sourceProfilePresent
                    + " logoOverride=" + rgbLabel(localLogoRgb)
                    + " plusOverride=" + rgbLabel(localPlusRgb)
                    + " displayCopyApplied=" + displayCopyApplied;
            if (phase.nametagColorSamples.size() < MAX_DISPLAY_COLOR_SAMPLES
                    && phase.nametagColorSamples.add(sample)) {
                appendLocked("nametag.display_lookup phase=" + phase.index + " " + sample);
            }
        }
    }

    /** Only final display colors and cache decisions; no entity IDs or native handles. */
    public static void recordNametagRenderCache(boolean profilePresent, int logoRgb, int plusRgb,
            boolean always, boolean flag, boolean badgePresent, boolean changed) {
        if (!active) return;
        synchronized (LOCK) {
            if (!active) return;
            Phase phase = currentPhaseLocked();
            phase.nametagCacheChecks++;
            if (changed) phase.nametagCacheRefreshes++;
            String sample = "sourceProfile=" + profilePresent + " logoRgb=" + rgbLabel(logoRgb)
                    + " plusRgb=" + rgbLabel(plusRgb) + " always=" + always + " flag=" + flag
                    + " badgePresent=" + badgePresent + " refresh=" + changed;
            if (phase.nametagCacheSamples.size() < MAX_DISPLAY_COLOR_SAMPLES
                    && phase.nametagCacheSamples.add(sample)) {
                appendLocked("nametag.render_cache phase=" + phase.index + " " + sample);
            }
        }
    }

    /** Records the actual logo/+ fields at native label compile/return/draw boundaries. */
    public static void recordNametagRenderStage(String stage, int logoColor, int plusColor,
                                                 int selectedLogo, int selectedPlus) {
        if (!active) return;
        synchronized (LOCK) {
            if (!active) return;
            Phase phase = currentPhaseLocked();
            String safeStage = "draw".equals(stage) ? "draw"
                    : "label_return".equals(stage) ? "label_return" : "compile";
            if ("draw".equals(safeStage)) phase.nametagNativeDraws++;
            else if ("label_return".equals(safeStage)) phase.nametagLabelReturns++;
            else phase.nametagNativeCompiles++;
            String sample = "stage=" + safeStage
                    + " logoRgb=" + rgbLabel(logoColor)
                    + " plusPacked=" + String.format(Locale.ROOT, "%08X", plusColor)
                    + " plusAlpha=" + ((plusColor >>> 24) & 255)
                    + " plusRgb=" + rgbLabel(plusColor)
                    + " selectedLogo=" + rgbLabel(selectedLogo)
                    + " selectedPlus=" + rgbLabel(selectedPlus)
                    + " logoMatchesSelection=" + (selectedLogo >= 0
                        && (logoColor & 0xFFFFFF) == (selectedLogo & 0xFFFFFF))
                    + " plusMatchesSelection=" + (selectedPlus >= 0
                        && (plusColor & 0xFFFFFF) == (selectedPlus & 0xFFFFFF));
            if (phase.nametagNativeStageSamples.size() < MAX_DISPLAY_COLOR_SAMPLES
                    && phase.nametagNativeStageSamples.add(sample)) {
                appendLocked("nametag.native_color_stage phase=" + phase.index + " " + sample);
            }
        }
    }

    /** Records Lunar's native nametag booleans and selected icon presence at render boundaries. */
    public static void recordNametagRenderFlags(String stage, boolean always, boolean flag,
            boolean badgePresent) {
        if (!active) return;
        synchronized (LOCK) {
            if (!active) return;
            Phase phase = currentPhaseLocked();
            String safeStage = "draw".equals(stage) ? "draw"
                    : "label_return".equals(stage) ? "label_return" : "compile";
            String sample = "stage=" + safeStage + " always=" + always
                    + " plusFlag=" + flag + " badgePresent=" + badgePresent;
            if (phase.nametagFlagSamples.size() < MAX_DISPLAY_COLOR_SAMPLES
                    && phase.nametagFlagSamples.add(sample)) {
                appendLocked("nametag.native_flags phase=" + phase.index + " " + sample);
            }
        }
    }

    /** Allows one live-profile recheck per player per capture phase; UUIDs are never written to disk. */
    public static boolean shouldInspectCachedZero(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        synchronized (LOCK) {
            return active && INSPECTED_ZERO_CACHE_PLAYERS.add(playerId);
        }
    }

    public static File getOutput() {
        return output;
    }

    public static void recordFullAccessToggle(boolean previous, boolean enabled) {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            appendLocked("fullaccess.toggle previousEnabled=" + previous + " enabled=" + enabled
                    + " " + runtimeSnapshotLocked());
            if (previous != enabled) {
                Phase phase = newPhaseLocked(enabled, "fullaccess_toggle");
                appendLocked("phase.start index=" + phase.index + " fullAccessEnabled=" + enabled
                        + " reason=fullaccess_toggle");
                recordProfileStateLocked("fullaccess_toggle");
            }
        }
    }

    public static void recordProfileManagerEvent() {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            Phase phase = currentPhaseLocked();
            phase.profileManagerEvents++;
            recordFirstSampleLocked(phase, "profile_manager_callback",
                    "profile.manager_callback_seen phase=" + phase.index);
        }
    }

    public static void recordProfileUpdate(int width) {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            Phase phase = currentPhaseLocked();
            phase.profileUpdates++;
            increment(phase.profileUpdateWidths, widthBucket(width));
            String sample = "profile_update:" + widthBucket(width);
            if (phase.firstSamples.add(sample)) {
                appendLocked("profile.update phase=" + phase.index + " nativeWidth=" + widthBucket(width));
            }
        }
    }

    /** Records payload shape at the native V2 outfit-manager and outer-dispatch boundaries. */
    public static void recordV2CosmeticsPushStage(String stage, Object push) {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            String defaultCount = invokeCount(push, "getDefaultCosmeticsCount");
            String conditionalCount = invokeCount(push, "getConditionalCosmeticsCount");
            String hasOutfitUuid = invokeBoolean(push, "hasOutfitUuid");
            appendLocked("cosmetics.push_v2 stage=" + token(stage)
                    + " at=" + System.currentTimeMillis()
                    + " defaultCount=" + defaultCount
                    + " conditionalCount=" + conditionalCount
                    + " hasOutfitUuid=" + hasOutfitUuid);
        }
    }

    public static void recordRendererHook() {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            Phase phase = currentPhaseLocked();
            phase.rendererHookCalls++;
            recordFirstSampleLocked(phase, "renderer_hook",
                    "nametag.player_name_render_return_seen phase=" + phase.index);
        }
    }

    public static void recordPrimaryNameCandidate() {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            Phase phase = currentPhaseLocked();
            phase.primaryNameCandidates++;
            recordFirstSampleLocked(phase, "primary_name",
                    "nametag.primary_player_name_pass_seen phase=" + phase.index);
        }
    }

    public static void recordLogoLookup(int width, String source) {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            Phase phase = currentPhaseLocked();
            increment(phase.logoLookups, widthBucket(width));
            increment(phase.lookupSources, token(source));
            String sample = "lookup:" + token(source) + ":" + widthBucket(width);
            if (phase.firstSamples.add(sample)) {
                appendLocked("logo.lookup phase=" + phase.index + " source=" + token(source)
                        + " width=" + widthBucket(width));
            }
        }
    }

    public static void recordRenderOutcome(String outcome) {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            Phase phase = currentPhaseLocked();
            String safeOutcome = token(outcome);
            increment(phase.renderOutcomes, safeOutcome);
            recordFirstSampleLocked(phase, "outcome:" + safeOutcome,
                    "nametag.render_outcome phase=" + phase.index + " result=" + safeOutcome);
        }
    }

    private static Phase newPhaseLocked(boolean fullAccessEnabled, String reason) {
        if (PHASES.size() >= MAX_PHASES) {
            return PHASES.get(PHASES.size() - 1);
        }
        Phase phase = new Phase(nextPhase++, fullAccessEnabled, reason);
        PHASES.add(phase);
        INSPECTED_ZERO_CACHE_PLAYERS.clear();
        return phase;
    }

    private static Phase currentPhaseLocked() {
        if (PHASES.isEmpty()) {
            return newPhaseLocked(ConfigManager.isLunarTweaksEnabled(), "implicit");
        }
        return PHASES.get(PHASES.size() - 1);
    }

    private static void recordProfileStateLocked(String reason) {
        String summary;
        try {
            summary = GomodLogoColorSync.describeNativeTabLogoSource();
        } catch (Throwable error) {
            summary = "profile_state_error=" + error.getClass().getSimpleName();
        }
        if (!summary.equals(lastProfileSummary)
                || "capture_start".equals(reason)
                || "fullaccess_toggle".equals(reason)) {
            lastProfileSummary = summary;
            appendLocked("profile.manager phase=" + currentPhaseLocked().index
                    + " reason=" + token(reason) + " " + summary);
        }
    }

    private static String runtimeSnapshotLocked() {
        String lunarState = "unknown";
        String fullAccessState = "unknown";
        try {
            lunarState = LunarOnlineStatus.getLunarOnlineState().name().toLowerCase(Locale.ROOT);
            fullAccessState = LunarOnlineStatus.getFullAccessState().name().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
        }
        return "fullAccessEnabled=" + ConfigManager.isLunarTweaksEnabled()
                + " lunarOnline=" + lunarState
                + " fullAccessConnection=" + fullAccessState;
    }

    private static void recordFirstSampleLocked(Phase phase, String key, String line) {
        if (phase.firstSamples.add(key)) {
            appendLocked(line);
        }
    }

    private static String widthBucket(int width) {
        if (width < 0) return "unknown";
        if (width == 0) return "zero";
        if (width == 9) return "logo9";
        if (width == 18) return "logoPlus18";
        return "otherPositive";
    }

    private static String rgbLabel(int rgb) {
        if (rgb == GomodLogoColorSync.DEFAULT_SENTINEL) return "default";
        if (rgb == 0x1000001) return "query";
        return rgb < 0 ? "unset" : String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    private static String safeState(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        String safe = value.replaceAll("[^A-Za-z0-9_.=,-]", "_");
        return safe.length() > 220 ? safe.substring(0, 220) : safe;
    }

    private static String token(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        String safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static String invokeCount(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            return result instanceof Number ? Integer.toString(((Number) result).intValue()) : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String invokeBoolean(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            return result instanceof Boolean ? result.toString() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static void increment(Map<String, Long> counts, String key) {
        Long previous = counts.get(key);
        counts.put(key, previous == null ? 1L : previous + 1L);
    }

    private static void appendLocked(String line) {
        if (output == null) {
            return;
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(output, true), StandardCharsets.UTF_8)) {
            long elapsed = captureStartedAtMs <= 0L ? 0L
                    : Math.max(0L, System.currentTimeMillis() - captureStartedAtMs);
            writer.write("elapsedMs=" + elapsed + " " + line);
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (Throwable error) {
            active = false;
            System.err.println("[gomod] Lunar nametag diagnostic write failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private static final class Phase {
        final int index;
        final boolean fullAccessEnabled;
        final String reason;
        final Map<String, Long> logoLookups = new TreeMap<String, Long>();
        final Map<String, Long> lookupSources = new TreeMap<String, Long>();
        final Map<String, Long> renderOutcomes = new TreeMap<String, Long>();
        final Map<String, Long> profileUpdateWidths = new TreeMap<String, Long>();
        final Set<String> firstSamples = new HashSet<String>();
        final Set<String> nametagColorSamples = new HashSet<String>();
        final Set<String> nametagCacheSamples = new HashSet<String>();
        final Set<String> nametagNativeStageSamples = new HashSet<String>();
        final Set<String> nametagFlagSamples = new HashSet<String>();
        final Set<String> tabDrawSamples = new HashSet<String>();
        long rendererHookCalls;
        long primaryNameCandidates;
        long profileManagerEvents;
        long profileUpdates;
        long nametagColorLookups;
        long nametagUuidLookups;
        long localIdentityChecks;
        long localNametagLookups;
        long localSourceProfilesPresent;
        long localDisplayCopiesApplied;
        long nametagCacheChecks;
        long nametagCacheRefreshes;
        long nametagNativeCompiles;
        long nametagNativeDraws;
        long nametagLabelReturns;
        long pipelineEvents;

        Phase(int index, boolean fullAccessEnabled, String reason) {
            this.index = index;
            this.fullAccessEnabled = fullAccessEnabled;
            this.reason = reason;
        }

        String summary() {
            return "phase.summary index=" + index + " reason=" + reason
                    + " fullAccessEnabled=" + fullAccessEnabled
                    + " rendererHookCalls=" + rendererHookCalls
                    + " primaryNameCandidates=" + primaryNameCandidates
                    + " logoLookups=" + logoLookups
                    + " lookupSources=" + lookupSources
                    + " renderOutcomes=" + renderOutcomes
                    + " profileManagerEvents=" + profileManagerEvents
                    + " profileUpdates=" + profileUpdates
                    + " profileUpdateWidths=" + profileUpdateWidths
                    + " nametagColorLookups=" + nametagColorLookups
                    + " nametagUuidLookups=" + nametagUuidLookups
                    + " localIdentityChecks=" + localIdentityChecks
                    + " localNametagLookups=" + localNametagLookups
                    + " localSourceProfilesPresent=" + localSourceProfilesPresent
                    + " localDisplayCopiesApplied=" + localDisplayCopiesApplied
                    + " nametagCacheChecks=" + nametagCacheChecks
                    + " nametagCacheRefreshes=" + nametagCacheRefreshes
                    + " nametagNativeCompiles=" + nametagNativeCompiles
                    + " nametagNativeDraws=" + nametagNativeDraws
                    + " nametagLabelReturns=" + nametagLabelReturns
                    + " pipelineEvents=" + pipelineEvents
                    + " tabDrawStates=" + tabDrawSamples.size();
        }
    }
}
