package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;

/**
 * Reloads feature singletons from {@link ConfigManager} after {@link ConfigManager#reloadConfigFromDisk()} —
 * same logical sequence as {@link me.napim.gomod.Main#preInit()} minus one-time setup.
 * <p>
 * Never touches {@link ApiKeyManager} (tokens stay in {@code api_keys.json}, independent of gomod profiles).
 */
public final class GomodSettingsReloader {

    private GomodSettingsReloader() {
    }

    /**
     * Call after {@link ConfigManager#loadConfig()} or {@link ConfigManager#reloadConfigFromDisk()}.
     * Safe to call once at startup via Main — excludes one-time {@code preInit} steps (resource pack injection,
     * {@link ChestRouteManager} route storage, {@link me.napim.gomod.features.ApiKeyManager#loadApiKeys()}).
     */
    public static void reloadAllAfterConfigLoaded() {
        GomodInternalFeatures.sanitizePersistedStateForPublicBuild();
        PlayerTracers.setCagesAutoEnabled(ConfigManager.isPtracersCagesEnabled());
        TabStatsRuntimeMute.setMuted(ConfigManager.isTabStatsRuntimeMuteEnabled());
        ForceResistanceManager.applyFromConfig();
        if (GomodInternalFeatures.isEnabled()) {
            DcMirrorBridge.loadSettings();
            DcMirrorBridge.warmSelfDisplayFromToken();
        } else {
            DcMirrorBridge.loadSettings();
        }
        AutoQueue.loadSetting();
        InventoryHUD.loadSettings();
        FriendInventoryHUD.loadSettings();
        PerspectiveDistance.loadSetting();
        AdvancedRenderDistance.loadSetting();
        TeamSpamManager.loadPlayers();
        KitLevelsManager.loadCaches();
        KillCounter.initializeSettings();
        TabDuplicateNickResolver.loadPersistedNickMappings();
        Denicker.applyRuntime();
        NickRealNametagOverlay.ensureHooksLoaded();
        GomodFriendPresenceIconOverlay.ensureHooksLoaded();
        SpectatorKillerNametagOverlay.ensureHooksLoaded();
        PlayerQueuer.loadConfig();
        GlowPlayer.loadConfig();
        DeathmatchGlow.loadConfig();
        MobHealthDisplay.loadSettings();
        MobTargetDisplay.loadSettings();
        DuelPredictor.loadSettings();
        BlitzChallenges.loadSettings();
        BlitzChallengeBadgeEquip.loadSetting();
        LocalMobDisguise.loadFromConfig();
        PlayerCustomCapes.ensureLoaded();
        PlayerStandaloneCape.loadSetting();
        HorseDonkeyTracker.loadSettings();
        Selfie.loadSetting();
        BestArmorDisplay.loadSettings();
        UselessItemOverlay.loadSettings();
        VisibleSpecs.loadSetting();
        SchizoMode.loadSetting();
        LootCall.loadFromConfig();
        PlayerCaller.loadFromConfig();
        ItemESP.loadSettings();
        BlocksFinder.loadSettings();
        NoHitDelay.loadSetting();
        DebugHits.loadSetting();
        CpsLimiter.loadSetting();
        RadialMenuWheel.loadFromConfig();
        DeathmatchTimerBossBar.onSettingChanged();
        GomodKeybinds.resyncFromConfigAfterProfileChange();
        BarrierVisibilityAtlasHook.scheduleApplySavedStyle();
        me.napim.gomod.util.GomodClasspathTextures.schedulePreloadHudIcons();
    }
}
