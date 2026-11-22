package me.napim.gomod.features;

import me.napim.gomod.gui.GomodGuiOpenHelper;
import me.napim.gomod.util.GomodResourcePackInjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.world.World;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runs client feature logic once per world and player tick, even when both a platform event and
 * the {@code Minecraft.runTick} fallback reach the hub.
 */
public final class ClientTickHub {

    private static int lastPlayerTicksExisted = Integer.MIN_VALUE;
    private static EntityPlayerSP lastPlayer;
    private static World lastWorld;
    private static volatile long lastRunWallClockMs;
    private static volatile long totalRuns;
    private static volatile Method lunarPeerLogoSyncMethod;

    // ---- Per-feature killswitches (all enabled by default) ----
    // The set holds feature names that are currently DISABLED.
    private static final Set<String> disabledFeatures =
            Collections.synchronizedSet(new LinkedHashSet<>());
    /** When true, ALL tick features are skipped (master kill switch for perf debugging). */
    private static volatile boolean allTickFeaturesDisabled = false;

    public static boolean isAllTickFeaturesDisabled() {
        return allTickFeaturesDisabled;
    }

    public static void setAllTickFeaturesDisabled(boolean v) {
        allTickFeaturesDisabled = v;
    }

    /** Returns the mutable set of disabled feature names. Changes take effect next tick. */
    public static Set<String> getDisabledFeatures() {
        return disabledFeatures;
    }

    public static void disableFeature(String name) {
        disabledFeatures.add(name);
    }

    public static void enableFeature(String name) {
        disabledFeatures.remove(name);
    }

    public static boolean isFeatureEnabled(String name) {
        return !allTickFeaturesDisabled && !disabledFeatures.contains(name);
    }

    /** All known tick-feature names in registration order. */
    public static final String[] TICK_FEATURE_NAMES = {
        // --- ClientTickHub helper calls ---
        "BlocksFinder",
        "ChestRouteManager",
        "ChestRouteManager.editInteraction",
        "ChestRouteManager.editDragPreview",
        "WitchAutoSound",
        "ForcePigManager",
        "ForceResistanceManager",
        "PeeEffectManager",
        "BlitzCageEntrySound",
        "HorseDonkeyTracker",
        "MobTargetDisplay",
        "DuelPredictor",
        "BlitzChallenges",
        "RecentOpponents",
        "LocalMobDisguise",
        "MoreHudServerPingRefresher",
        "VisibleSpecsScan",
        "SpectatorTracker",
        "SpawnedMobTracker",
        "NickRenamedItemScanner",
        // --- EventBus @SubscribeEvent tick handlers ---
        "KillCounter",
        "KillEffectCommand",
        "UnknownNickFinder",
        "DeathmatchTabCleaner",
        "TabDuplicateNickResolver",
        "LocalDisguiseSync",
        "RemoteDisguiseSync",
        "FakeBreak",
        "NoHitDelay",
        "DebugHits",
        "RadialMenuWheel",
        "SidebarKeScoreboardMutator",
        "SidebarTauntWastedMutator",
        "DisguiseSidebarScoreboardMutator",
        "PauseMenuButtonFallback",
        "ShareInvTickHandler",
        "GlobalChatClient",
        "IncomingScreenshotOverlay",
        "IncomingFriendRequestOverlay",
        "IncomingFriendDmOverlay",
        "IncomingPublicPingOverlay",
        "GomodSocialMsgIconHud",
    };

    private ClientTickHub() {
    }

    public static long getLastRunWallMs() {
        return lastRunWallClockMs;
    }

    public static long getTotalRuns() {
        return totalRuns;
    }

    /**
     * @return true if a hub tick ran within the last {@code maxAgeMs} milliseconds
     */
    public static boolean isHubRecentlyAlive(long maxAgeMs) {
        long now = System.currentTimeMillis();
        return lastRunWallClockMs > 0L && (now - lastRunWallClockMs) < maxAgeMs;
    }

    /**
     * {@link Minecraft#timer} is private without {@code gomod_at.cfg} on the classpath manifest ({@code FMLAT});
     * reflection avoids {@link IllegalAccessError} in those builds.
     */
    private static float resolveRenderPartialTicks(Minecraft mc) {
        if (mc == null) {
            return 1.0f;
        }
        try {
            return mc.timer != null ? mc.timer.renderPartialTicks : 1.0f;
        } catch (IllegalAccessError | NoSuchFieldError err) {
            return resolveRenderPartialTicksReflect(mc);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static float resolveRenderPartialTicksReflect(Minecraft mc) {
        try {
            java.lang.reflect.Field tf = resolveMinecraftTimerField();
            tf.setAccessible(true);
            Object timer = tf.get(mc);
            if (timer == null) {
                return 1.0f;
            }
            java.lang.reflect.Field pf = resolveTimerPartialTicksField(timer.getClass());
            pf.setAccessible(true);
            return pf.getFloat(timer);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static java.lang.reflect.Field resolveMinecraftTimerField() throws NoSuchFieldException {
        try {
            return Minecraft.class.getDeclaredField("timer");
        } catch (NoSuchFieldException e) {
            return Minecraft.class.getDeclaredField("field_71428_T");
        }
    }

    private static java.lang.reflect.Field resolveTimerPartialTicksField(Class<?> timerClass) throws NoSuchFieldException {
        try {
            return timerClass.getDeclaredField("renderPartialTicks");
        } catch (NoSuchFieldException e) {
            return timerClass.getDeclaredField("field_74281_c");
        }
    }

    public static void runClientFeatureTicks() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        boolean duplicatePlayerTick = false;
        if (player == null) {
            // Keep lifecycle handlers active in menus so they can clean up after a disconnect.
            lastPlayer = null;
            lastWorld = null;
        } else {
            World world = mc.theWorld;
            int playerTick = player.ticksExisted;
            duplicatePlayerTick = player == lastPlayer && world == lastWorld && playerTick == lastPlayerTicksExisted;
            // Timed captures and connection cleanup must still run when the world tick counter is frozen.
            if (duplicatePlayerTick && world != null && !mc.isGamePaused()) {
                return;
            }
            lastPlayer = player;
            lastWorld = world;
            lastPlayerTicksExisted = playerTick;
        }
        NickRealNametagOverlay.clearVisibleNicknameCache();
        // Release tracer world/entity references on disconnect and finish bounded captures without world renders.
        try { PlayerTracers.clientTick(mc); } catch (Throwable ignored) {}
        try { BlitzRouletteDebug.clientTick(mc); } catch (Throwable ignored) {}
        try { BlitzMatchIdentity.clientTick(mc); } catch (Throwable ignored) {}
        try { ArmorDisguise.tick(); } catch (Throwable ignored) {}
        try { SpectatorRelay.clientTick(mc); } catch (Throwable ignored) {}
        tickLunarPeerLogoProfiles(mc);
        try { ForceGameChat.clientTick(mc); } catch (Throwable ignored) {}
        try { UnbotheredTaunt.clientTick(mc); } catch (Throwable ignored) {}
        try { DeathmatchTimerBossBar.clientTick(mc); } catch (Throwable ignored) {}
        if (player == null || duplicatePlayerTick) {
            return;
        }
        lastRunWallClockMs = System.currentTimeMillis();
        totalRuns++;

        // Companion smoke is a cosmetic render effect, not a Lunar FullAccess or lag-debug feature.
        try { GoparkyBlitzReactions.clientTickCigaretteSmoke(mc); } catch (Throwable ignored) {}
        try { GomodPeerDisguise.expirePeerStates(); } catch (Throwable ignored) {}
        try { GomodCompanionPositionSync.tick(mc); } catch (Throwable ignored) {}

        GomodGuiOpenHelper.clientTick();
        GomodResourcePackInjector.ensureInstalled();

        if (!allTickFeaturesDisabled) {
            if (isFeatureEnabled("BlocksFinder")) {
                long _t = FeatureProfiler.begin("BlocksFinder");
                try { BlocksFinder.clientTick(); } catch (Throwable ignored) {}
                FeatureProfiler.end("BlocksFinder", _t);
            }
            if (isFeatureEnabled("ChestRouteManager")) {
                long _t = FeatureProfiler.begin("ChestRouteManager");
                try { ChestRouteManager.clientTick(); } catch (Throwable t) { System.err.println("[gomod] ChestRouteManager.clientTick: " + t); }
                FeatureProfiler.end("ChestRouteManager", _t);
            }
            if (isFeatureEnabled("ChestRouteManager.editInteraction")) {
                long _t = FeatureProfiler.begin("ChestRouteManager.editInteraction");
                try { ChestRouteManager.clientTickEditInteraction(); } catch (Throwable t) { System.err.println("[gomod] ChestRouteManager.clientTickEditInteraction: " + t); }
                FeatureProfiler.end("ChestRouteManager.editInteraction", _t);
            }
            if (isFeatureEnabled("ChestRouteManager.editDragPreview")) {
                long _t = FeatureProfiler.begin("ChestRouteManager.editDragPreview");
                try { ChestRouteManager.updateEditDragPreview(resolveRenderPartialTicks(mc)); } catch (Throwable t) { System.err.println("[gomod] ChestRouteManager.updateEditDragPreview: " + t); }
                FeatureProfiler.end("ChestRouteManager.editDragPreview", _t);
            }
            if (isFeatureEnabled("WitchAutoSound")) {
                long _t = FeatureProfiler.begin("WitchAutoSound");
                try { WitchAutoSound.clientTick(); } catch (Throwable t) { System.err.println("[gomod] WitchAutoSound.clientTick: " + t); }
                FeatureProfiler.end("WitchAutoSound", _t);
            }
            if (isFeatureEnabled("ForcePigManager")) {
                long _t = FeatureProfiler.begin("ForcePigManager");
                try { ForcePigManager.clientTick(); } catch (Throwable t) { System.err.println("[gomod] ForcePigManager.clientTick: " + t); }
                FeatureProfiler.end("ForcePigManager", _t);
            }
            if (isFeatureEnabled("ForceResistanceManager")) {
                long _t = FeatureProfiler.begin("ForceResistanceManager");
                try { ForceResistanceManager.clientTick(); } catch (Throwable t) { System.err.println("[gomod] ForceResistanceManager.clientTick: " + t); }
                FeatureProfiler.end("ForceResistanceManager", _t);
            }
            if (isFeatureEnabled("PeeEffectManager")) {
                long _t = FeatureProfiler.begin("PeeEffectManager");
                try { PeeEffectManager.clientTick(); } catch (Throwable t) { System.err.println("[gomod] PeeEffectManager.clientTick: " + t); }
                FeatureProfiler.end("PeeEffectManager", _t);
            }
            if (isFeatureEnabled("BlitzCageEntrySound")) {
                long _t = FeatureProfiler.begin("BlitzCageEntrySound");
                try { BlitzCageEntrySound.clientTick(); } catch (Throwable t) { System.err.println("[gomod] BlitzCageEntrySound.clientTick: " + t); }
                FeatureProfiler.end("BlitzCageEntrySound", _t);
            }
            if (isFeatureEnabled("HorseDonkeyTracker")) {
                long _t = FeatureProfiler.begin("HorseDonkeyTracker");
                try { HorseDonkeyTracker.clientTick(); } catch (Throwable t) { System.err.println("[gomod] HorseDonkeyTracker.clientTick: " + t); }
                FeatureProfiler.end("HorseDonkeyTracker", _t);
            }
            if (isFeatureEnabled("MobTargetDisplay")) {
                long _t = FeatureProfiler.begin("MobTargetDisplay");
                try { MobTargetDisplay.clientTick(); } catch (Throwable t) { System.err.println("[gomod] MobTargetDisplay.clientTick: " + t); }
                FeatureProfiler.end("MobTargetDisplay", _t);
            }
            if (isFeatureEnabled("DuelPredictor")) {
                long _t = FeatureProfiler.begin("DuelPredictor");
                try { DuelPredictor.clientTick(); } catch (Throwable t) { System.err.println("[gomod] DuelPredictor.clientTick: " + t); }
                FeatureProfiler.end("DuelPredictor", _t);
            }
            if (isFeatureEnabled("BlitzChallenges")) {
                long _t = FeatureProfiler.begin("BlitzChallenges");
                try { BlitzChallenges.clientTick(); } catch (Throwable t) { System.err.println("[gomod] BlitzChallenges.clientTick: " + t); }
                FeatureProfiler.end("BlitzChallenges", _t);
            }
            if (!me.napim.gomod.GomodReleaseBuild.PUBLIC_RELEASE && isFeatureEnabled("RecentOpponents")) {
                long _t = FeatureProfiler.begin("RecentOpponents");
                try { RecentOpponents.clientTick(); } catch (Throwable t) { System.err.println("[gomod] RecentOpponents.clientTick: " + t); }
                FeatureProfiler.end("RecentOpponents", _t);
            }
            if (isFeatureEnabled("LocalMobDisguise")) {
                long _t = FeatureProfiler.begin("LocalMobDisguise");
                try { LocalMobDisguise.clientTick(mc); } catch (Throwable t) { System.err.println("[gomod] LocalMobDisguise.clientTick: " + t); }
                FeatureProfiler.end("LocalMobDisguise", _t);
            }
            if (isFeatureEnabled("MoreHudServerPingRefresher")) {
                long _t = FeatureProfiler.begin("MoreHudServerPingRefresher");
                try { MoreHudServerPingRefresher.clientTick(mc); } catch (Throwable ignored) { }
                FeatureProfiler.end("MoreHudServerPingRefresher", _t);
            }
            if (isFeatureEnabled("VisibleSpecsScan")) {
                long _t = FeatureProfiler.begin("VisibleSpecsScan");
                try {
                    VisibleSpecs.clientTick();
                    KillCounter.scanReplayChatHistoryForMissingKillerRelations();
                } catch (Throwable ignored) { }
                FeatureProfiler.end("VisibleSpecsScan", _t);
            }
            if (isFeatureEnabled("SpectatorTracker")) {
                long _t = FeatureProfiler.begin("SpectatorTracker");
                try { SpectatorTracker.clientTick(); } catch (Throwable ignored) { }
                FeatureProfiler.end("SpectatorTracker", _t);
            }
            // Do not mutate Hypixel's player-team memberships. Those teams carry vanilla nametag colours and
            // Lunar TeamView state; repeatedly removing members causes white labels and random TeamView flicker.
            if (isFeatureEnabled("SpawnedMobTracker")) {
                long _t = FeatureProfiler.begin("SpawnedMobTracker");
                try { SpawnedMobTracker.clientTick(); } catch (Throwable ignored) { }
                FeatureProfiler.end("SpawnedMobTracker", _t);
            }
            if (isFeatureEnabled("NickRenamedItemScanner")) {
                long _t = FeatureProfiler.begin("NickRenamedItemScanner");
                try { NickRenamedItemScanner.clientTick(mc); } catch (Throwable ignored) { }
                FeatureProfiler.end("NickRenamedItemScanner", _t);
            }
            try {
                BlitzVampireEffectActivation.clientTick(mc);
            } catch (Throwable ignored) {
            }
        }

        FeatureProfiler.recordTickDone();
    }

    /** Internal Weave bridge: missing peer logo profiles are requested through Lunar's native API. */
    private static void tickLunarPeerLogoProfiles(Minecraft mc) {
        if (!GomodFixes.isWeaveRuntime()) return;
        try {
            Method method = lunarPeerLogoSyncMethod;
            if (method == null) {
                Class<?> sync = Class.forName("me.napim.gomod.features.LunarSpectatorCosmetics", false,
                        ClientTickHub.class.getClassLoader());
                method = sync.getMethod("updatePeerLogoProfiles", Minecraft.class);
                lunarPeerLogoSyncMethod = method;
            }
            method.invoke(null, mc);
        } catch (Throwable ignored) {
        }
    }
}
