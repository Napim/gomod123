package me.napim.gomod.features;

import net.minecraft.client.Minecraft;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Sends logo-color updates over the authenticated Lunar cosmetics-v2 connection. */
public final class GomodLogoColorSync {
    public static final int DEFAULT_SENTINEL = 0x1000000;

    private static final int CONTROL_FIELD = 19031;
    private static final int RESPONSE_FIELD = 19032;
    private static final int QUERY_SENTINEL = 0x1000001;
    private static final String REQUEST_CLASS =
            "com.lunarclient.websocket.cosmetic.v2.UpdateLunarPlusColorRequest";
    private static final String UNKNOWN_FIELDS_CLASS = "com.google.protobuf.UnknownFieldSet";
    private static final int MAX_LOGO_PROFILE_RETRIES = 30;
    private static final long LOGO_PROFILE_RETRY_DELAY_MS = 500L;
    private static final ScheduledExecutorService LOGO_PROFILE_RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "Gomod-LunarLogoProfileRetry");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static volatile int currentRgb = DEFAULT_SENTINEL;
    private static volatile UUID confirmedLogoAccount;
    private static volatile String status = "Not loaded from Lunar API";
    private static volatile Object tabLogoManager;
    private static volatile Object playerLogoProfileManager;
    private static volatile boolean profileManagerCaptureLogged;
    private static volatile long localApplyGeneration;
    private static long colorRequestGeneration;
    private static long pendingColorRequestGeneration;
    private static long pendingLogoQueryGeneration;
    private static long logoRestoreGeneration;
    private static boolean logoRestorePending;
    private static long logoRestoreRequestGeneration;
    private static long logoRestoreRequestStartedAt;
    private static int logoRestoreRequests;
    private static final long LOGO_RESTORE_RPC_TIMEOUT_MS = 5000L;
    private static final int MAX_LOGO_RESTORE_REQUESTS = 3;
    private static volatile long lastPeerAppearancePollMs;
    private static final Map<UUID, Integer> originalPlusRgbByPlayer =
            new ConcurrentHashMap<UUID, Integer>();
    /** Last values observed in Lunar's real local profile push, before Gomod's local cache edits. */
    private static final Map<UUID, Integer> nativeLogoRgbByPlayer =
            new ConcurrentHashMap<UUID, Integer>();
    private static final Map<UUID, Integer> nativePlusRgbByPlayer =
            new ConcurrentHashMap<UUID, Integer>();
    private static final Map<UUID, Integer> selectedPlusRgbByPlayer =
            new ConcurrentHashMap<UUID, Integer>();
    /** Badge chosen in Lunar's native badge menu, retained across later logo-profile refreshes. */
    private static final Map<UUID, Integer> selectedBadgeIdByPlayer =
            new ConcurrentHashMap<UUID, Integer>();
    /** Native badge objects are also used by Lunar's separate TAB-logo cache. */
    private static final Map<UUID, Object> selectedBadgeObjectByPlayer =
            new ConcurrentHashMap<UUID, Object>();
    private static final Map<UUID, Integer> logoRgbBeforePlusSelectionByPlayer =
            new ConcurrentHashMap<UUID, Integer>();
    private static final Map<UUID, Long> plusLogoSnapshotTimeByPlayer =
            new ConcurrentHashMap<UUID, Long>();
    private static final long PLUS_LOGO_SNAPSHOT_MAX_AGE_MS = 10000L;
    private static final ThreadLocal<Boolean> APPLYING_NATIVE_PROFILE = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private GomodLogoColorSync() {
    }

    /** Local logo/cache writes are not authoritative outfit snapshots. */
    public static boolean isApplyingNativeProfile() {
        return Boolean.TRUE.equals(APPLYING_NATIVE_PROFILE.get());
    }

    public static int getCurrentRgb() {
        return isFullAccessColorAvailable() ? currentRgb : DEFAULT_SENTINEL;
    }

    /** Default is only a saved Fit value once the API has actually confirmed it. */
    public static synchronized Integer getSettledLogoColorForFit() {
        UUID owner = localAccountUuid();
        return isFullAccessColorAvailable() && owner != null && owner.equals(confirmedLogoAccount)
                && pendingColorRequestGeneration == 0L ? Integer.valueOf(currentRgb) : null;
    }

    public static synchronized long getPendingLogoWriteForFit() {
        return pendingLogoQueryGeneration == 0L ? pendingColorRequestGeneration : 0L;
    }

    public static int getLunarPlusColorForFit() {
        if (ConfigManager.isLunarPlusHideBadge()) return 0;
        int selected = LunarPlusAccountPreferences.getSelectedRgb();
        if (selected >= 0) return selected;
        UUID owner = localAccountUuid();
        Integer nativeValue = owner == null ? null : nativePlusRgbByPlayer.get(owner);
        return nativeValue == null ? -1 : nativeValue.intValue() & 0xFFFFFF;
    }

    public static void applyLunarPlusColorFromFit(int rgb) {
        if (!isFullAccessColorAvailable() || rgb < -1 || rgb > 0xFFFFFF) return;
        ConfigManager.setLunarPlusHideBadge(rgb == 0);
        int profileRgb = rgb;
        if (rgb < 0) {
            LunarPlusAccountPreferences.clearSelection();
            UUID owner = localAccountUuid();
            if (owner != null) selectedPlusRgbByPlayer.remove(owner);
            Integer original = owner == null ? null : nativePlusRgbByPlayer.get(owner);
            profileRgb = original == null ? 0 : original.intValue() & 0xFFFFFF;
        } else rememberLocalLunarPlusColor(rgb);
        applyLocalLunarPlusColor(profileRgb);
        applyLocalLunarPlusAccountColor(rgb);
        refreshLocalLunarPlusVisibility();
        requestPeerAppearanceRefresh();
    }

    public static boolean isFullAccessColorAvailable() {
        return GomodFixes.isWeaveRuntime() && ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected();
    }

    public static String getStatus() {
        return status;
    }

    /** Color-only cache snapshot for the opt-in Locker/TAB/nametag diagnostic. */
    @SuppressWarnings("rawtypes")
    public static String describeLocalColorPipelineState() {
        Minecraft mc = Minecraft.getMinecraft();
        UUID playerId = mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                ? null : mc.getSession().getProfile().getId();
        Object profileManager = playerLogoProfileManager;
        Object tabManager = tabLogoManager;
        int selectedPlus = playerId == null || !selectedPlusRgbByPlayer.containsKey(playerId)
                ? -1 : selectedPlusRgbByPlayer.get(playerId).intValue();
        return "fullAccessPreference=" + ConfigManager.isLunarTweaksEnabled()
                + " fullAccessConnected=" + LunarOnlineStatus.isFullAccessConnected()
                + " hidePlus=" + ConfigManager.isLunarPlusHideBadge()
                + " selectedLogo=" + debugRgb(currentRgb)
                + " logoOverride=" + debugRgb(getLocalLogoRgbForRelay())
                + " selectedPlus=" + debugRgb(selectedPlus)
                + " plusOverride=" + debugRgb(getLocalPlusRgbForRelay())
                + " nativeProfile=" + describeProfileColors(profileManager, playerId)
                + " tabCache=" + describeTabCacheColors(tabManager, playerId);
    }

    /** Color-only native profile snapshot for a just-applied Lunar update; identifiers are omitted. */
    @SuppressWarnings("rawtypes")
    public static String describeNativeProfileColors(Object manager, UUID playerId) {
        return describeProfileColors(manager, playerId);
    }

    @SuppressWarnings("rawtypes")
    private static String describeProfileColors(Object manager, UUID playerId) {
        if (playerId == null) return "no_local_session";
        if (manager == null) return "unavailable";
        try {
            Object profiles = playerLogoProfiles(manager);
            if (!(profiles instanceof Map)) return "map_unavailable";
            Object profile = ((Map) profiles).get(playerId);
            if (profile == null) return "absent";
            return "logo=" + debugRgb(rgbFromPlayerLogoProfile(profile))
                    + ",plus=" + debugRgb(playerLogoPlusRgb(profile));
        } catch (Throwable error) {
            return "error_" + error.getClass().getSimpleName();
        }
    }

    private static String describeTabCacheColors(Object manager, UUID playerId) {
        if (playerId == null) return "no_local_session";
        if (manager == null) return "unavailable";
        try {
            Object record = tabLogoRecord(manager, playerId);
            if (record == null) return "absent";
            int logo = rgbFromRecord(record);
            int plus = tabRecordPlusRgb(record) & 0xFFFFFF;
            return "logo=" + debugRgb(logo) + ",plus=" + debugRgb(plus);
        } catch (Throwable error) {
            return "error_" + error.getClass().getSimpleName();
        }
    }

    private static String debugRgb(int rgb) {
        if (rgb == DEFAULT_SENTINEL) return "default";
        if (rgb == QUERY_SENTINEL) return "query";
        if (rgb < 0 || rgb > 0xFFFFFF) return "unset";
        return String.format(java.util.Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    /** Current visible logo color for the explicitly shared Gomod peer nametag state. */
    @SuppressWarnings("rawtypes")
    public static int getLocalLogoRgbForRelay() {
        if (!GomodFixes.isWeaveRuntime()) return -1;
        if (!ConfigManager.isLunarTweaksEnabled() || !LunarOnlineStatus.isFullAccessConnected()) return -1;
        int selected = currentRgb;
        if (selected >= 0 && selected <= 0xFFFFFF) return selected;
        // The native profile cache also contains Lunar's default color (often encoded as
        // black/zero). Advertising that as a Gomod override made peers render a black logo.
        // Only publish a logo color when the user explicitly selected a FullAccess override.
        return -1;
    }

    /** Current native Lunar+ color for the explicitly shared Gomod peer nametag state. */
    @SuppressWarnings("rawtypes")
    public static int getLocalPlusRgbForRelay() {
        if (!GomodFixes.isWeaveRuntime()) return LunarPublicLoginGuard.officialPlusRgb();
        if (!ConfigManager.isLunarTweaksEnabled() || !LunarOnlineStatus.isFullAccessConnected()) return -1;
        if (ConfigManager.isLunarPlusHideBadge()) return 0;
        int saved = LunarPlusAccountPreferences.getSelectedRgb();
        if (saved >= 0) return saved;
        Minecraft mc = Minecraft.getMinecraft();
        UUID playerId = mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                ? null : mc.getSession().getProfile().getId();
        Integer selected = playerId == null ? null : selectedPlusRgbByPlayer.get(playerId);
        if (selected != null) return selected.intValue() & 0xFFFFFF;
        // Unlocking Plus colors is separate from equipping one. New public users keep their
        // genuine Lunar choice (or No+) until they explicitly select a FullAccess color.
        if (me.napim.gomod.GomodReleaseBuild.PUBLIC_RELEASE) {
            int official = LunarPublicLoginGuard.officialPlusRgb();
            return official >= 0 ? official : 0;
        }
        // Do not broadcast Lunar's unmodified account value as a Gomod color override.
        return -1;
    }

    /** FullAccess selection inputs are loaded by the login, reconnect and preference handlers. */
    static boolean shouldHideLocalPlusForRender(UUID playerId, int officialRgb) {
        if (!GomodFixes.isWeaveRuntime()) return false;
        int saved = LunarPlusAccountPreferences.getCachedSelectedRgb(playerId);
        if (saved >= 0) return saved == 0;
        Integer selected = selectedPlusRgbByPlayer.get(playerId);
        return selected == null ? officialRgb <= 0 : selected.intValue() == 0;
    }

    /** Disable local and peer-visible FullAccess appearance overrides without changing account data. */
    public static void onFullAccessPreferenceChanged(final boolean enabled) {
        if (!GomodFixes.isWeaveRuntime()) return;
        if (!enabled) {
            restoreNativeAppearance(true, "preference-disabled");
            requestPeerAppearanceRefresh(true);
            return;
        }
        if (!LunarOnlineStatus.isFullAccessConnected()) {
            // A saved on-toggle is not proof that the relay is responding. Keep official colors
            // visible until the live connection transition restores the selected FullAccess data.
            restoreNativeAppearance(false, "preference-enabled-while-disconnected");
            requestPeerAppearanceRefresh(true);
            return;
        }
        activateFullAccessAppearance();
        requestPeerAppearanceRefresh(false);
    }

    /** Applies or removes local FullAccess colors when the live relay connection changes. */
    public static void onFullAccessConnectionChanged(boolean connected) {
        if (!GomodFixes.isWeaveRuntime()) return;
        if (!connected || !ConfigManager.isLunarTweaksEnabled()) {
            restoreNativeAppearance(false, connected ? "preference-disabled" : "connection-unavailable");
            requestPeerAppearanceRefresh(true);
            return;
        }
        activateFullAccessAppearance();
        requestPeerAppearanceRefresh(false);
    }

    private static void activateFullAccessAppearance() {
        if (!LunarOnlineStatus.isFullAccessConnected() || !ConfigManager.isLunarTweaksEnabled()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        final UUID playerId = mc == null || mc.getSession() == null
                || mc.getSession().getProfile() == null ? null : mc.getSession().getProfile().getId();
        if (playerId == null) return;
        restoreSavedLogoAfterConnection(playerId);
        int logo = currentRgb;
        if (logo >= 0 && logo <= 0xFFFFFF) scheduleLocalLogoColor(playerId, logo, false, true);
        if (ConfigManager.isLunarPlusHideBadge()) {
            refreshLocalLunarPlusVisibility();
        } else {
            Integer plus = selectedLocalPlusColor(playerId);
            if (plus != null) {
                applyLocalLunarPlusColor(plus.intValue());
                applyLocalLunarPlusAccountColor(plus.intValue());
            }
        }
    }

    /** A new JVM starts at Default; load the account's saved value without opening Locker. */
    private static void restoreSavedLogoAfterConnection(final UUID playerId) {
        final long generation;
        synchronized (GomodLogoColorSync.class) {
            if (pendingLogoQueryGeneration != 0L
                    && pendingColorRequestGeneration == pendingLogoQueryGeneration) {
                pendingColorRequestGeneration = 0L;
                pendingLogoQueryGeneration = 0L;
                ++colorRequestGeneration;
            }
            generation = ++logoRestoreGeneration;
            logoRestorePending = true;
            logoRestoreRequestGeneration = 0L;
            logoRestoreRequestStartedAt = 0L;
            logoRestoreRequests = 0;
        }
        retrySavedLogoAfterConnection(playerId, generation, 0);
    }

    private static void retrySavedLogoAfterConnection(final UUID playerId,
                                                       final long generation, final int retry) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!isFullAccessColorAvailable() || !playerId.equals(localAccountUuid())) return;
                boolean canQuery;
                synchronized (GomodLogoColorSync.class) {
                    if (generation != logoRestoreGeneration || !logoRestorePending) return;
                    // Expire only our unanswered read, never a user's pending color change.
                    if (logoRestoreRequestGeneration != 0L
                            && pendingColorRequestGeneration == logoRestoreRequestGeneration
                            && System.currentTimeMillis() - logoRestoreRequestStartedAt >= LOGO_RESTORE_RPC_TIMEOUT_MS) {
                        pendingColorRequestGeneration = 0L;
                        pendingLogoQueryGeneration = 0L;
                    }
                    if (retry >= MAX_LOGO_PROFILE_RETRIES) {
                        logoRestorePending = false;
                        if (pendingColorRequestGeneration == logoRestoreRequestGeneration) {
                            pendingColorRequestGeneration = 0L;
                            pendingLogoQueryGeneration = 0L;
                        }
                        LunarConnectionDiagnostics.record("lunar_logo_color.connection_restore",
                                "result=timed-out requests=" + logoRestoreRequests);
                        return;
                    }
                    canQuery = pendingColorRequestGeneration == 0L
                            && logoRestoreRequests < MAX_LOGO_RESTORE_REQUESTS
                            && (logoRestoreRequests == 0 || System.currentTimeMillis()
                                    - logoRestoreRequestStartedAt >= LOGO_RESTORE_RPC_TIMEOUT_MS);
                }
                if (canQuery) {
                    try {
                        // FullAccess can become ready just before the native cosmetics service.
                        // Waiting for it here does not issue or consume an RPC attempt.
                        resolveLunarRuntime();
                        long request = querySavedLogoColor();
                        if (request != 0L) {
                            synchronized (GomodLogoColorSync.class) {
                                logoRestoreRequestGeneration = request;
                                logoRestoreRequestStartedAt = System.currentTimeMillis();
                                ++logoRestoreRequests;
                            }
                            LunarConnectionDiagnostics.record("lunar_logo_color.connection_restore",
                                    "result=query-requested attempt=" + logoRestoreRequests);
                        }
                    } catch (Exception notReady) {
                        // Retry readiness briefly; the normal connection recovery remains unchanged.
                    }
                }
                synchronized (GomodLogoColorSync.class) {
                    if (generation != logoRestoreGeneration || !logoRestorePending) return;
                }
                LOGO_PROFILE_RETRY_EXECUTOR.schedule(new Runnable() {
                    @Override public void run() {
                        retrySavedLogoAfterConnection(playerId, generation, retry + 1);
                    }
                }, LOGO_PROFILE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        });
    }

    /** Restores the captured native colors, optionally clearing Logo Color on an explicit toggle-off. */
    private static void restoreNativeAppearance(final boolean clearLogoSelection, final String reason) {
        final Minecraft mc = Minecraft.getMinecraft();
        final UUID playerId = mc == null || mc.getSession() == null
                || mc.getSession().getProfile() == null ? null : mc.getSession().getProfile().getId();
        synchronized (GomodLogoColorSync.class) {
            confirmedLogoAccount = null;
            boolean hadPendingColorRequest = pendingColorRequestGeneration != 0L
                    && pendingColorRequestGeneration != pendingLogoQueryGeneration;
            ++logoRestoreGeneration;
            logoRestorePending = false;
            ++colorRequestGeneration;
            pendingColorRequestGeneration = 0L;
            pendingLogoQueryGeneration = 0L;
            if (clearLogoSelection || hadPendingColorRequest || currentRgb == QUERY_SENTINEL)
                currentRgb = DEFAULT_SENTINEL;
        }
        nextLocalApplyGeneration();
        if (mc != null && playerId != null) {
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (!playerId.equals(localAccountUuid()) || isFullAccessColorAvailable()) return;
                    try {
                        int originalLogo = officialLogoRgbForRestore(playerId);
                        Integer originalPlus = officialPlusRgbForRestore(playerId);
                        if (originalLogo >= 0 && originalLogo <= 0xFFFFFF)
                            scheduleLocalLogoColor(playerId, originalLogo, true, true);
                        if (originalPlus != null) {
                            // A native profile color of zero is the no-plus state. The separate
                            // account preference store uses -1 to clear its selection; storing
                            // zero there makes Lunar render a black plus after FullAccess turns off.
                            int profilePlus = originalPlus.intValue() < 0 ? 0 : originalPlus.intValue();
                            applyLocalLunarPlusAccountColor(originalPlus.intValue(), true);
                            // The account tile exists before the player-profile cache. Clear its
                            // stale FullAccess choice even when that cache cannot yet be updated.
                            applyLocalPlayerLunarPlusColor(playerId, profilePlus, true);
                        }
                        GomodLunarPlusLocker.refreshNativeLockerState();
                        LunarConnectionDiagnostics.record("lunar.appearance_overrides",
                                "connected=" + LunarOnlineStatus.isFullAccessConnected()
                                        + " restoreReason=" + reason
                                        + " officialLogoRestored=" + (originalLogo >= 0)
                                        + " officialPlusRestored=" + (originalPlus != null && originalPlus.intValue() >= 0)
                                        + " officialPlusCleared=" + (originalPlus != null && originalPlus.intValue() < 0));
                    } catch (Throwable error) {
                        LunarConnectionDiagnostics.record("lunar.appearance_overrides",
                                "restoreReason=" + reason + " localRestore=failed errorType="
                                        + error.getClass().getSimpleName());
                    }
                }
            });
        }
    }

    /** Ask Gomod's relay poller to publish a just-selected local logo state without waiting for its interval. */
    public static void requestPeerAppearanceRefresh() {
        requestPeerAppearanceRefresh(false);
    }

    private static void requestPeerAppearanceRefresh(boolean force) {
        if (!force && !ConfigManager.isLunarTweaksShowToOtherPlayers()) return;
        long now = System.currentTimeMillis();
        long previous = lastPeerAppearancePollMs;
        if (now - previous < 250L) return;
        lastPeerAppearancePollMs = now;
        try {
            if (me.napim.gomod.Main.getGlobalChatClient() != null) {
                me.napim.gomod.Main.getGlobalChatClient().requestImmediatePoll();
            }
        } catch (Throwable ignored) {
            // The next normal relay poll still publishes the preference.
        }
    }

    /** Whether Lunar's native player-logo profile cache has been captured for local-only writes. */
    public static boolean isNativePlayerProfileManagerReady() {
        return playerLogoProfileManager != null;
    }

    /**
     * Copies a Lunar V2 push without changing its outfit, badge, or colors, changing only the
     * profile UUID so the local client renders that appearance on its own player.
     */
    public static Object copyCosmeticsPushForPlayer(Object sourcePush, UUID playerId) throws Exception {
        if (sourcePush == null || playerId == null) {
            throw new IllegalArgumentException("A source Lunar profile and local UUID are required");
        }
        ClassLoader loader = sourcePush.getClass().getClassLoader();
        Class<?> lunarUuidClass = load("com.lunarclient.common.v1.Uuid", loader);
        Object uuidBuilder = invokeStatic(lunarUuidClass, "newBuilder", new Class<?>[0]);
        invoke(uuidBuilder, "setHigh64", new Class<?>[]{Long.TYPE}, playerId.getMostSignificantBits());
        invoke(uuidBuilder, "setLow64", new Class<?>[]{Long.TYPE}, playerId.getLeastSignificantBits());
        Object lunarUuid = invoke(uuidBuilder, "build", new Class<?>[0]);

        Object pushBuilder = invoke(sourcePush, "toBuilder", new Class<?>[0]);
        invokeSingleArgument(pushBuilder, "setPlayerUuid", lunarUuid);
        return invoke(pushBuilder, "build", new Class<?>[0]);
    }

    /** Applies the cloned full profile to Lunar's local profile cache only; no RPC is sent. */
    @SuppressWarnings("rawtypes")
    public static void applyFullDisguisePlayerLogoProfile(UUID playerId, Object copiedPush) throws Exception {
        Object manager = playerLogoProfileManager;
        if (playerId == null || manager == null || copiedPush == null) {
            throw new IllegalStateException("Lunar's local player-logo profile manager is unavailable");
        }
        ClassLoader loader = manager.getClass().getClassLoader();
        Class<?> pushClass = load("com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2", loader);
        if (!pushClass.isInstance(copiedPush)) {
            throw new IllegalArgumentException("The copied Lunar profile belongs to another client classloader");
        }
        APPLYING_NATIVE_PROFILE.set(Boolean.TRUE);
        try {
            applyPlayerLogoPush(manager, playerId, pushClass, copiedPush,
                    playerLogoPushBadgeId(copiedPush));
        } finally {
            APPLYING_NATIVE_PROFILE.remove();
        }

        Object profiles = playerLogoProfiles(manager);
        if (!(profiles instanceof Map) || ((Map) profiles).get(playerId) == null) {
            throw new IllegalStateException("Lunar did not accept the copied local player profile");
        }
        Object updatedRecord = ((Map) profiles).get(playerId);
        int logoRgb = rgbFromPlayerLogoProfile(updatedRecord) & 0xFFFFFF;
        int plusRgb = playerLogoPlusRgb(updatedRecord) & 0xFFFFFF;
        try {
            // Lunar keeps the TAB logo record separate from the live player-logo profile.
            // Update both from the accepted native record so a full disguise copies the + color too.
            applyTabLogoPlusColor(playerId, plusRgb, logoRgb);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar_plus.full_disguise_tab_cache",
                    "result=failed errorType=" + error.getClass().getSimpleName());
        }
        LunarTabLogoCompatibility.invalidateProfileLogoWidth(playerId);
        LunarConnectionDiagnostics.record("lunar_plus.full_disguise_profile",
                "result=applied logo=" + debugRgb(logoRgb) + " plus=" + debugRgb(plusRgb)
                        + " native=" + describeProfileColors(manager, playerId)
                        + " tab=" + describeTabCacheColors(tabLogoManager, playerId));
    }

    /** Re-applies the user's selected local logo color and No+ policy after a full disguise ends. */
    public static void restoreLocalLogoPreferencesAfterFullDisguise() {
        Object manager = playerLogoProfileManager;
        if (manager != null) {
            onPlayerLogoProfileManagerAvailable(manager);
        }
    }

    /**
     * Returns the width reserved for a player whose Lunar logo profile is present, or -1 when the
     * current runtime profile cache cannot be inspected. Lunar 2.22.43's profile record stores
     * logo colors and the optional Lunar+ color; it does not expose the drawable asset accessor
     * used by earlier runtime mappings. A present profile record is the current availability signal.
     */
    @SuppressWarnings("rawtypes")
    public static int getNativeTabLogoWidth(UUID playerId) {
        if (playerId == null) {
            return -1;
        }
        Object manager = playerLogoProfileManager;
        if (manager == null) {
            return -1;
        }
        try {
            Object profiles = playerLogoProfiles(manager);
            if (!(profiles instanceof Map)) {
                return -1;
            }
            Object profile = ((Map) profiles).get(playerId);
            if (profile == null) {
                return 0;
            }

            int width = 9;
            try {
                // Current PlayerLogoProfile exposes its Lunar+ color through playerLogoPlusRgb;
                // No+ is represented by zero.
                if (playerLogoPlusRgb(profile) != 0) {
                    width += 9;
                }
            } catch (Throwable ignoredPlusMapping) {
                // The base logo remains present even if a future Lunar build changes the plus accessor.
            }
            return width;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Payload-free explanation for a nametag lookup; never returns or logs the supplied UUID. */
    @SuppressWarnings("rawtypes")
    public static String describeNativeTabLogoLookup(UUID playerId) {
        if (playerId == null) {
            return "no_player_id";
        }
        Object manager = playerLogoProfileManager;
        if (manager == null) {
            return "profile_manager_unavailable";
        }
        try {
            Object profiles = playerLogoProfiles(manager);
            if (!(profiles instanceof Map)) {
                return "profile_map_unavailable";
            }
            Object profile = ((Map) profiles).get(playerId);
            if (profile == null) {
                return "profile_absent";
            }
            try {
                return playerLogoPlusRgb(profile) == 0
                        ? "profile_record_present_no_plus" : "profile_record_present_with_plus";
            } catch (Throwable error) {
                return "profile_record_present_plus_unknown_" + error.getClass().getSimpleName();
            }
        } catch (Throwable error) {
            return "profile_lookup_error_" + error.getClass().getSimpleName();
        }
    }

    /** Current native logo/+ RGB values, including a locally applied /disguisefull profile. */
    @SuppressWarnings("rawtypes")
    public static int[] getNativePlayerLogoColors(UUID playerId) {
        if (playerId == null) return null;
        Object manager = playerLogoProfileManager;
        if (manager == null) return null;
        try {
            Object profiles = playerLogoProfiles(manager);
            if (!(profiles instanceof Map)) return null;
            Object profile = ((Map) profiles).get(playerId);
            if (profile == null) return null;
            return new int[]{rgbFromPlayerLogoProfile(profile) & 0xFFFFFF,
                    playerLogoPlusRgb(profile) & 0xFFFFFF};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Current native logo profile object, useful for copying Lunar's exact presence/badge fields. */
    @SuppressWarnings("rawtypes")
    public static Object getNativePlayerLogoProfile(UUID playerId) {
        if (playerId == null) return null;
        Object manager = playerLogoProfileManager;
        if (manager == null) return null;
        try {
            Object profiles = playerLogoProfiles(manager);
            return profiles instanceof Map ? ((Map) profiles).get(playerId) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Payload-free state for /tabaligndebug; this never emits player names or UUIDs. */
    @SuppressWarnings("rawtypes")
    public static String describeNativeTabLogoSource() {
        Object manager = playerLogoProfileManager;
        if (manager == null) {
            return "profile_manager=not_captured";
        }
        try {
            Object profiles = playerLogoProfiles(manager);
            if (profiles instanceof Map) {
                Map profileMap = (Map) profiles;
                int plusBadges = 0;
                int plusColorUninspectable = 0;
                for (Object profile : profileMap.values()) {
                    if (profile == null) {
                        continue;
                    }
                    try {
                        if (playerLogoPlusRgb(profile) != 0) {
                            plusBadges++;
                        }
                    } catch (Throwable ignoredPlusMapping) {
                        plusColorUninspectable++;
                    }
                }
                return "profile_manager=captured profile_map=available entries=" + profileMap.size()
                        + " profile_records=" + profileMap.size()
                        + " plus_badge_profiles=" + plusBadges
                        + " plus_color_uninspectable=" + plusColorUninspectable;
            }
            return "profile_manager=captured profile_map=unavailable";
        } catch (Throwable error) {
            return "profile_manager=captured profile_map_error=" + error.getClass().getSimpleName();
        }
    }

    /** Called after Lunar loads tab-logo records so custom body colors survive a cache refresh. */
    public static void onTabLogoCacheLoaded(Object manager) {
        if (manager == null) return;
        tabLogoManager = manager;
        if (!GomodFixes.isWeaveRuntime()) return;
        LunarNametagDebugCapture.recordColorFlow("tab_logo_cache_loaded", "tab", -1, "manager_ready");
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                || mc.getSession().getProfile().getId() == null) return;
        UUID playerId = mc.getSession().getProfile().getId();
        applySelectedLocalBadgeToTabCache(playerId);
        if (!ConfigManager.isLunarTweaksEnabled() || !LunarOnlineStatus.isFullAccessConnected()) return;
        int rgb = currentRgb;
        if (rgb != DEFAULT_SENTINEL && rgb != QUERY_SENTINEL) {
            scheduleLocalLogoColor(playerId, rgb, false);
        }
        scheduleLocalLunarPlusCacheRefresh(playerId);
    }

    /** Captures Lunar's live player-profile manager so the local TAB/logo renderer updates too. */
    public static void onPlayerLogoProfileManagerAvailable(Object manager) {
        onPlayerLogoProfileManagerAvailable(manager, true);
    }

    public static void onPlayerLogoProfileManagerAvailable(Object manager, boolean localProfileUpdate) {
        if (manager == null) return;
        playerLogoProfileManager = manager;
        LunarNametagDebugCapture.recordProfileManagerEvent();
        if (!profileManagerCaptureLogged) {
            profileManagerCaptureLogged = true;
            LunarConnectionDiagnostics.record("lunar_plus.profile_manager",
                    "captured=true runtime=active-lunar-player-cosmetics");
        }
        if (Boolean.TRUE.equals(APPLYING_NATIVE_PROFILE.get())) return;
        // Peer profile bursts after reconnect do not change the local account's saved colors.
        if (!localProfileUpdate) return;
        // During /disguisefull, Lunar calls this hook again for the copied target profile.
        // Do not capture that temporary profile as the account's native colors or enqueue
        // the user's saved logo/+ preferences over the copied appearance.
        if (LunarFullDisguise.isActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        UUID playerId = mc == null || mc.getSession() == null
                || mc.getSession().getProfile() == null
                ? null : mc.getSession().getProfile().getId();
        boolean fullAccessConnected = ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected();
        if (playerId != null) {
            applySelectedLocalBadgeToPlayerLogoProfile(manager, playerId, "manager_ready");
            applySelectedLocalBadgeToTabCache(playerId);
        }
        if (playerId != null && (!ConfigManager.isLunarTweaksEnabled() || fullAccessConnected))
            captureNativeLocalAppearance(manager, playerId);
        if (!GomodFixes.isWeaveRuntime()) return;
        if (!fullAccessConnected) return;
        if (ConfigManager.isLunarPlusHideBadge()) {
            refreshLocalLunarPlusVisibility();
        }
        int rgb = currentRgb;
        if (playerId == null) return;
        if (rgb != DEFAULT_SENTINEL && rgb != QUERY_SENTINEL) {
            scheduleLocalLogoColor(playerId, rgb, false);
        }
        if (!ConfigManager.isLunarPlusHideBadge()) {
            scheduleLocalLunarPlusCacheRefresh(playerId);
        }
    }

    /** Snapshot account-owned colors from Lunar before any queued Gomod-only profile edits run. */
    @SuppressWarnings("rawtypes")
    private static void captureNativeLocalAppearance(Object manager, UUID playerId) {
        try {
            Object profiles = playerLogoProfiles(manager);
            if (!(profiles instanceof Map)) return;
            Object profile = ((Map) profiles).get(playerId);
            if (profile == null) return;
            int logoRgb = rgbFromPlayerLogoProfile(profile) & 0xFFFFFF;
            int plusRgb = playerLogoPlusRgb(profile) & 0xFFFFFF;
            int officialLogo = LunarPublicLoginGuard.officialLogoRgbForRestore();
            int officialPlus = LunarPublicLoginGuard.officialPlusRgbForRestore();
            if (officialLogo >= 0) logoRgb = officialLogo;
            if (officialPlus >= 0) plusRgb = officialPlus;
            nativeLogoRgbByPlayer.put(playerId, Integer.valueOf(logoRgb));
            nativePlusRgbByPlayer.put(playerId, Integer.valueOf(plusRgb));
            // Keep the older No+ restoration path in step with the latest genuine Lunar push.
            originalPlusRgbByPlayer.put(playerId, Integer.valueOf(plusRgb));
            LunarNametagDebugCapture.recordColorFlow("native_account_appearance_captured", "logo",
                    logoRgb, "plus_" + String.format(java.util.Locale.ROOT, "%06X", plusRgb));
        } catch (Throwable ignored) {
            // Lunar can call the manager hook before its local account record is available.
            // The first later native PlayerCosmeticsPushV2 callback will provide the snapshot.
        }
    }

    private static int nativeLogoRgb(UUID playerId) {
        Integer rgb = playerId == null ? null : nativeLogoRgbByPlayer.get(playerId);
        return rgb == null ? -1 : rgb.intValue() & 0xFFFFFF;
    }

    private static int officialLogoRgbForRestore(UUID playerId) {
        int official = LunarPublicLoginGuard.officialLogoRgbForRestore();
        return official >= 0 ? official : nativeLogoRgb(playerId);
    }

    private static Integer officialPlusRgbForRestore(UUID playerId) {
        int official = LunarPublicLoginGuard.officialPlusRgbForRestore();
        if (official >= 0) return Integer.valueOf(official);
        if (official == LunarPublicLoginGuard.OFFICIAL_PLUS_UNSELECTED) return Integer.valueOf(-1);
        Integer nativePlus = nativePlusRgbByPlayer.get(playerId);
        if (nativePlus == null) return Integer.valueOf(-1);
        return nativePlus.intValue() == 0 ? Integer.valueOf(-1) : nativePlus;
    }

    /** Applies the Locker's local No + choice to Lunar's cached player profile, never to a Lunar RPC. */
    public static void refreshLocalLunarPlusVisibility() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                || mc.getSession().getProfile().getId() == null) {
            return;
        }
        final UUID playerId = mc.getSession().getProfile().getId();
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isLocalFullLunarDisguiseActive(playerId)) {
                        LunarConnectionDiagnostics.record("lunar_plus.local_visibility",
                                "result=skipped reason=full_lunar_disguise");
                        return;
                    }
                    applyLunarPlusVisibility(playerId, ConfigManager.isLunarTweaksEnabled()
                            && LunarOnlineStatus.isFullAccessConnected()
                            && ConfigManager.isLunarPlusHideBadge());
                    GomodLunarPlusLocker.refreshNativeLockerState();
                } catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    System.err.println("[gomod] Could not apply local Lunar+ visibility: "
                            + safeMessage(cause));
                }
            }
        });
    }

    /** Remembers the legitimate color selected in Lunar so a later No + choice can be reversed locally. */
    public static void rememberLocalLunarPlusColor(int rgb) {
        rgb &= 0xFFFFFF;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                || mc.getSession().getProfile().getId() == null) {
            return;
        }
        UUID playerId = mc.getSession().getProfile().getId();
        rememberLocalLogoBeforePlusSelection(playerId);
        Object manager = playerLogoProfileManager;
        if (manager != null) {
            try {
                Object profiles = playerLogoProfiles(manager);
                if (profiles instanceof Map) {
                    Object profile = ((Map) profiles).get(playerId);
                    if (profile != null) {
                        nativeLogoRgbByPlayer.putIfAbsent(playerId,
                                Integer.valueOf(rgbFromPlayerLogoProfile(profile) & 0xFFFFFF));
                        int plusRgb = playerLogoPlusRgb(profile) & 0xFFFFFF;
                        nativePlusRgbByPlayer.putIfAbsent(playerId, Integer.valueOf(plusRgb));
                        originalPlusRgbByPlayer.putIfAbsent(playerId, Integer.valueOf(plusRgb));
                    }
                }
            } catch (Throwable ignored) {
                // Keep the first native value available from a later profile callback.
            }
        }
        selectedPlusRgbByPlayer.put(playerId, rgb);
        LunarPlusAccountPreferences.select(rgb);
        plusLogoSnapshotTimeByPlayer.put(playerId, Long.valueOf(System.currentTimeMillis()));
        LunarFitColors.rememberPlus(rgb);
    }

    /** Preserve Lunar's current local logo across the transient zero-logo push made by Locker. */
    @SuppressWarnings("rawtypes")
    private static void rememberLocalLogoBeforePlusSelection(UUID playerId) {
        if (playerId == null || getLocalLogoRgbForRelay() >= 0) return;
        Object manager = playerLogoProfileManager;
        if (manager == null) return;
        try {
            Object profiles = playerLogoProfiles(manager);
            if (!(profiles instanceof Map)) return;
            Object profile = ((Map) profiles).get(playerId);
            if (profile == null) return;
            int logoRgb = rgbFromPlayerLogoProfile(profile) & 0xFFFFFF;
            Integer previous = logoRgbBeforePlusSelectionByPlayer.get(playerId);
            // A later rapid + click can arrive after Lunar has already published its
            // temporary black profile. Keep the last real nonzero local logo snapshot.
            if (logoRgb != 0 || previous == null) {
                logoRgbBeforePlusSelectionByPlayer.put(playerId, Integer.valueOf(logoRgb));
            }
            LunarNametagDebugCapture.recordColorFlow("plus_logo_snapshot", "logo", logoRgb,
                    "captured_before_plus_profile_update");
        } catch (Throwable ignored) {
            // Lunar may not have initialized the local profile yet; the API logo query can
            // still establish the selected logo color later.
        }
    }

    /** Updates Lunar's local profile cache, then refreshes Locker after the queued cache write. */
    public static void applyLocalLunarPlusColor(final int rgb) {
        if (!isFullAccessColorAvailable()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                || mc.getSession().getProfile().getId() == null) return;
        final UUID playerId = mc.getSession().getProfile().getId();
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isFullAccessColorAvailable()) return;
                    applyLocalPlayerLunarPlusColor(playerId, rgb, true);
                    GomodLunarPlusLocker.refreshNativeLockerState();
                } catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    LunarConnectionDiagnostics.record("lunar_plus.local_profile_update",
                            "result=failed errorType=" + cause.getClass().getSimpleName());
                    System.err.println("[gomod] Could not refresh local Lunar+ preview: "
                            + safeMessage(cause));
                }
            }
        });
    }

    /**
     * Updates Lunar's local account/menu Lunar+ color preference without sending an RPC.
     * The native -1 sentinel clears the selected color; zero is the black No+ sentinel.
     * The native Locker callback normally updates this separate preference before publishing
     * its `lunarPlus` UI state. Gomod intercepts No+ to keep it local, so mirror just that local
     * write here; otherwise the menu account tile continues reading the previously chosen color.
     */
    public static void applyLocalLunarPlusAccountColor(final int rgb) {
        applyLocalLunarPlusAccountColor(rgb, false);
    }

    private static void applyLocalLunarPlusAccountColor(final int rgb, final boolean restoringNative) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                || mc.getSession().getProfile().getId() == null || rgb < -1 || rgb > 0xFFFFFF) {
            return;
        }
        final UUID playerId = mc.getSession().getProfile().getId();
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!playerId.equals(localAccountUuid())
                        || restoringNative && isFullAccessColorAvailable()) return;
                try {
                    LunarColorRuntime.setAccountPlus(playerId, rgb);
                    LunarConnectionDiagnostics.record("lunar_plus.account_tile_color",
                            "result=" + (rgb < 0 ? "selection-cleared" : "updated")
                                    + " noPlus=" + (rgb == 0));
                    GomodLunarPlusLocker.refreshNativeLockerState();
                } catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    LunarConnectionDiagnostics.record("lunar_plus.account_tile_color",
                            "result=failed errorType=" + cause.getClass().getSimpleName());
                    System.err.println("[gomod] Could not update Lunar's local account Lunar+ color: "
                            + safeMessage(cause));
                }
            }
        });
    }


    private static void scheduleLocalLunarPlusCacheRefresh(final UUID playerId) {
        if (playerId == null || !ConfigManager.isLunarTweaksEnabled()
                || !LunarOnlineStatus.isFullAccessConnected()) return;
        final boolean hidePlus = ConfigManager.isLunarPlusHideBadge();
        final Integer selectedPlus = selectedLocalPlusColor(playerId);
        if (!hidePlus && selectedPlus == null) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ConfigManager.isLunarTweaksEnabled()
                            || !LunarOnlineStatus.isFullAccessConnected()) return;
                    if (hidePlus) {
                        if (isLocalFullLunarDisguiseActive(playerId)) {
                            LunarConnectionDiagnostics.record("lunar_plus.local_cache_refresh",
                                    "result=skipped reason=full_lunar_disguise");
                            return;
                        }
                        applyLunarPlusVisibility(playerId, true);
                    } else {
                        if (isLocalFullLunarDisguiseActive(playerId)) {
                            LunarConnectionDiagnostics.record("lunar_plus.local_cache_refresh",
                                    "result=skipped reason=full_lunar_disguise");
                            return;
                        }
                        applyLocalPlayerLunarPlusColor(playerId, selectedPlus.intValue(), false);
                    }
                    GomodLunarPlusLocker.refreshNativeLockerState();
                } catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    System.err.println("[gomod] Could not restore the selected Lunar+ logo state: "
                            + safeMessage(cause));
                }
            }
        });
    }

    /** Applies a Gomod peer's advertised badge preference to this client's local Lunar cache only. */
    public static void refreshRemoteLunarPlusVisibility(String normalizedUuid, final boolean hidePlus) {
        final UUID playerId = parseUuid(normalizedUuid);
        final Minecraft mc = Minecraft.getMinecraft();
        if (playerId == null || mc == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    applyLunarPlusVisibility(playerId, hidePlus);
                } catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    System.err.println("[gomod] Could not apply a gomod peer's Lunar+ visibility: "
                            + safeMessage(cause));
                }
            }
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyLunarPlusVisibility(UUID playerId, boolean hidePlus) throws Exception {
        if (playerId == null) return;
        if (isLocalFullLunarDisguiseActive(playerId)) {
            LunarConnectionDiagnostics.record("lunar_plus.local_visibility",
                    "result=skipped reason=full_lunar_disguise");
            return;
        }
        Object manager = playerLogoProfileManager;
        Object profileMapObject = manager == null ? null : playerLogoProfiles(manager);
        Map profileMap = profileMapObject instanceof Map ? (Map) profileMapObject : null;
        Object oldRecord = profileMap == null ? null : profileMap.get(playerId);
        Integer tabPlus = readTabLogoPlusRgb(playerId);
        int currentPlusRgb = oldRecord != null ? playerLogoPlusRgb(oldRecord)
                : tabPlus == null ? -1 : tabPlus.intValue();
        if (hidePlus && currentPlusRgb != 0) {
            if (currentPlusRgb > 0) originalPlusRgbByPlayer.put(playerId, currentPlusRgb);
        }
        Integer selectedPlusRgb = selectedLocalPlusColor(playerId);
        Integer originalPlusRgb = originalPlusRgbByPlayer.get(playerId);
        int desiredPlusRgb;
        if (!ConfigManager.isLunarTweaksEnabled() || !LunarOnlineStatus.isFullAccessConnected()) {
            desiredPlusRgb = originalPlusRgb != null ? originalPlusRgb.intValue() : currentPlusRgb;
        } else {
            int sharedPlusRgb = GomodPeerLunarPlus.advertisedPlusRgb(playerId);
            desiredPlusRgb = hidePlus ? 0
                    : sharedPlusRgb >= 0 ? sharedPlusRgb
                    : selectedPlusRgb != null ? selectedPlusRgb.intValue()
                    : originalPlusRgb != null ? originalPlusRgb.intValue() : currentPlusRgb;
        }
        if (desiredPlusRgb < 0) return;

        if (oldRecord != null && currentPlusRgb != desiredPlusRgb) {
            int logoRgb = rgbFromPlayerLogoProfile(oldRecord);
            boolean alwaysShow = playerLogoAlwaysShow(oldRecord);
            ClassLoader loader = manager.getClass().getClassLoader();
            Class<?> colorClass = load("com.lunarclient.common.v1.Color", loader);

            Object logoColorBuilder = invokeStatic(colorClass, "newBuilder", new Class<?>[0]);
            invoke(logoColorBuilder, "setColor", new Class<?>[]{Integer.TYPE}, logoRgb);
            Object plusColorBuilder = invokeStatic(colorClass, "newBuilder", new Class<?>[0]);
            invoke(plusColorBuilder, "setColor", new Class<?>[]{Integer.TYPE}, desiredPlusRgb);

            Class<?> pushClass = load("com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2", loader);
            Object pushBuilder = invokeStatic(pushClass, "newBuilder", new Class<?>[0]);
            invokeSingleArgument(pushBuilder, "setLogoColor", logoColorBuilder);
            invokeSingleArgument(pushBuilder, "setPlusColor", plusColorBuilder);
            invoke(pushBuilder, "setLogoAlwaysShow", new Class<?>[]{Boolean.TYPE}, alwaysShow);
            Object push = invoke(pushBuilder, "build", new Class<?>[0]);

            APPLYING_NATIVE_PROFILE.set(Boolean.TRUE);
            try {
                applyPlayerLogoPush(manager, playerId, pushClass, push,
                        playerLogoBadgeIdForUpdate(playerId, oldRecord));
            } finally {
                APPLYING_NATIVE_PROFILE.remove();
            }
        }
        // The native record is the peer's real Lunar account appearance. Use it as the
        // fallback when FullAccess sharing is hidden; never substitute our own logo tint.
        int sharedLogoRgb = GomodPeerLunarPlus.advertisedLogoRgb(playerId);
        int profileLogoRgb = oldRecord == null ? -1 : rgbFromPlayerLogoProfile(oldRecord);
        applyTabLogoPlusColor(playerId, desiredPlusRgb,
                sharedLogoRgb >= 0 ? sharedLogoRgb : profileLogoRgb);
        if (!hidePlus) {
            originalPlusRgbByPlayer.remove(playerId);
        }
    }

    private static UUID parseUuid(String normalizedUuid) {
        if (normalizedUuid == null) {
            return null;
        }
        String value = normalizedUuid.trim();
        if (value.length() == 32) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                    + value.substring(12, 16) + "-" + value.substring(16, 20) + "-"
                    + value.substring(20);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isLocalFullLunarDisguiseActive(UUID playerId) {
        if (playerId == null || !LunarFullDisguise.isActive()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                ? null : mc.getSession().getProfile().getId();
        return playerId.equals(localId);
    }

    public static void queryLogoColor() {
        querySavedLogoColor();
    }

    private static long querySavedLogoColor() {
        if (!isFullAccessColorAvailable()) {
            status = "Connect Lunar FullAccess to use Logo Color.";
            return 0L;
        }
        final long generation;
        final int previousRgb;
        synchronized (GomodLogoColorSync.class) {
            if (pendingColorRequestGeneration != 0L) return 0L;
            previousRgb = currentRgb;
            generation = ++colorRequestGeneration;
            pendingColorRequestGeneration = generation;
            pendingLogoQueryGeneration = generation;
        }
        LunarNametagDebugCapture.recordColorFlow("logo_color_page_query", "logo", QUERY_SENTINEL,
                "rpc_requested");
        sendControl(QUERY_SENTINEL, "Loading saved logo color...", generation, previousRgb);
        return generation;
    }

    public static void setLogoColor(int rgb) {
        if (!isFullAccessColorAvailable()) {
            status = "Connect Lunar FullAccess to use Logo Color.";
            return;
        }
        if (rgb < 0 || rgb > 0xFFFFFF) {
            status = "Color must be a 6-digit RGB value";
            return;
        }
        LunarNametagDebugCapture.recordColorFlow("logo_color_ui_selection", "logo", rgb, "click_received");
        int previousRgb = currentRgb;
        long generation = beginOptimisticLogoColor(rgb, "Saving logo color...");
        LunarFitColors.logoWriteStarted(generation);
        sendControl(rgb, "Saving logo color...", generation, previousRgb);
    }

    public static void setLogoColorFromUi(String value) {
        if (value == null || "DEFAULT".equalsIgnoreCase(value.trim())) {
            resetLogoColor();
            return;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (!hex.matches("(?i)[0-9a-f]{6}")) {
            status = "Choose a six-digit RGB color";
            return;
        }
        try {
            setLogoColor(Integer.parseInt(hex, 16));
        } catch (NumberFormatException ignored) {
            status = "Choose a six-digit RGB color";
        }
    }

    public static void resetLogoColor() {
        if (!isFullAccessColorAvailable()) {
            status = "Connect Lunar FullAccess to use Logo Color.";
            return;
        }
        LunarNametagDebugCapture.recordColorFlow("logo_color_ui_selection", "logo", DEFAULT_SENTINEL,
                "default_selected");
        int previousRgb = currentRgb;
        long generation = beginOptimisticLogoColor(
                DEFAULT_SENTINEL, "Restoring the API default logo color...");
        LunarFitColors.logoWriteStarted(generation);
        sendControl(DEFAULT_SENTINEL, "Restoring the API default logo color...",
                generation, previousRgb);
    }

    private static void sendControl(final int value, final String pendingStatus,
                                    final long optimisticGeneration, final int previousRgb) {
        status = pendingStatus;
        LunarNametagDebugCapture.recordColorFlow("logo_color_rpc_start", "logo", value,
                value == QUERY_SENTINEL ? "query" : value == DEFAULT_SENTINEL ? "default" : "set");
        try {
            final UUID requestAccount = localAccountUuid();
            final LunarRuntime runtime = resolveLunarRuntime();
            final ClassLoader loader = runtime.loader;
            final Class<?> requestClass = load(REQUEST_CLASS, loader);
            final Class<?> unknownSetClass = load(UNKNOWN_FIELDS_CLASS, loader);
            final Class<?> unknownFieldClass = load(UNKNOWN_FIELDS_CLASS + "$Field", loader);

            Object fieldBuilder = unknownFieldClass.getMethod("newBuilder").invoke(null);
            invoke(fieldBuilder, "addVarint", new Class<?>[]{Long.TYPE}, (long) value);
            Object field = invoke(fieldBuilder, "build", new Class<?>[0]);

            Object unknownBuilder = unknownSetClass.getMethod("newBuilder").invoke(null);
            invoke(unknownBuilder, "addField", new Class<?>[]{Integer.TYPE, unknownFieldClass},
                    CONTROL_FIELD, field);
            Object unknownFields = invoke(unknownBuilder, "build", new Class<?>[0]);

            Object requestBuilder = requestClass.getMethod("newBuilder").invoke(null);
            invoke(requestBuilder, "setUnknownFields", new Class<?>[]{unknownSetClass}, unknownFields);
            final Object request = invoke(requestBuilder, "build", new Class<?>[0]);

            final Object cosmeticService = runtime.cosmeticService;
            Method rpc = findUpdateMethod(cosmeticService.getClass(), requestClass);
            LunarConnectionDiagnostics.record("lunar_logo_color.api_lookup",
                    "result=ready rpc=updateLunarPlusColor");
            LunarNametagDebugCapture.recordColorFlow("logo_color_rpc_ready", "logo", value,
                    "updateLunarPlusColor");
            Class<?> callbackType = rpc.getParameterTypes()[2];
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                    new Class<?>[]{callbackType}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getDeclaringClass() == Object.class) {
                                if ("hashCode".equals(method.getName())) return Integer.valueOf(System.identityHashCode(proxy));
                                if ("equals".equals(method.getName())) return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                                if ("toString".equals(method.getName())) return "Gomod Lunar color callback";
                            }
                            if (requestAccount == null || !requestAccount.equals(localAccountUuid())) return null;
                            if ("run".equals(method.getName()) && args != null && args.length > 0) {
                                acceptResponse(args[0], runtime, optimisticGeneration, previousRgb);
                            } else if (args != null && args.length > 0 && args[0] instanceof Throwable) {
                                LunarNametagDebugCapture.recordColorFlow("logo_color_rpc_failed", "logo", value,
                                        args[0].getClass().getSimpleName());
                                rejectOptimisticLogoColor(optimisticGeneration, previousRgb,
                                        "Logo color request failed: " + safeMessage((Throwable) args[0]));
                            }
                            return null;
                        }
                    });
            rpc.invoke(cosmeticService, null, request, callback);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            LunarConnectionDiagnostics.record("lunar_logo_color.api_lookup",
                    "result=failed errorType=" + cause.getClass().getSimpleName());
            LunarNametagDebugCapture.recordColorFlow("logo_color_rpc_failed", "logo", value,
                    cause.getClass().getSimpleName());
            rejectOptimisticLogoColor(optimisticGeneration, previousRgb,
                    cause instanceof ClassNotFoundException || cause instanceof NoSuchMethodException
                            ? "Lunar color integration unavailable: " + safeMessage(cause)
                            : "Lunar API unavailable: " + safeMessage(cause));
        }
    }

    private static void acceptResponse(Object response, final LunarRuntime runtime,
                                        long optimisticGeneration, int previousRgb) {
        try {
            if (!isFullAccessColorAvailable()) return;
            if (!isCurrentColorRequest(optimisticGeneration)) return;
            Object unknownFields = invoke(response, "getUnknownFields", new Class<?>[0]);
            Object field = invoke(unknownFields, "getField", new Class<?>[]{Integer.TYPE}, RESPONSE_FIELD);
            Object values = invoke(field, "getVarintList", new Class<?>[0]);
            if (!(values instanceof List) || ((List<?>) values).isEmpty()) {
                rejectOptimisticLogoColor(optimisticGeneration, previousRgb,
                        "gomod FullAccess API did not confirm logo color (check relay routing and dev-catalog config)");
                return;
            }
            long value = ((Number) ((List<?>) values).get(((List<?>) values).size() - 1)).longValue();
            LunarNametagDebugCapture.recordColorFlow("logo_color_rpc_response", "logo", (int) value,
                    "value_received");
            if (value != DEFAULT_SENTINEL && (value < 0 || value > 0xFFFFFF)) {
                rejectOptimisticLogoColor(optimisticGeneration, previousRgb,
                        "Lunar API returned an invalid color");
                return;
            }
            if (!commitColorResponse(optimisticGeneration, (int) value)) return;
            LunarFitColors.logoConfirmed(optimisticGeneration, (int) value);
            if (value == DEFAULT_SENTINEL) {
                final Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getSession() != null
                        && mc.getSession().getProfile() != null
                        && mc.getSession().getProfile().getId() != null) {
                    UUID playerId = mc.getSession().getProfile().getId();
                    int restoreRgb = nativeLogoRgb(playerId);
                    if (restoreRgb >= 0) scheduleLocalLogoColor(playerId, restoreRgb, true, true);
                    else status = "Loaded: API default; waiting for Lunar's native logo profile";
                } else {
                    status = "Loaded: API default";
                }
                return;
            }

            final int rgb = (int) value;
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                    || mc.getSession().getProfile().getId() == null) {
                status = String.format("Saved: #%06X; local Lunar identity unavailable", value);
                return;
            }
            final UUID playerId = mc.getSession().getProfile().getId();
            scheduleLocalLogoColor(playerId, rgb, false, true);
        } catch (Throwable error) {
            rejectOptimisticLogoColor(optimisticGeneration, previousRgb,
                    "Could not read the Lunar API response: " + safeMessage(error));
        }
    }

    /** Applies a selected color to the local TAB and native nametag caches before the API replies. */
    private static long beginOptimisticLogoColor(int rgb, String pendingStatus) {
        LunarNametagDebugCapture.recordColorSelection("logo", rgb);
        LunarNametagDebugCapture.recordColorFlow("logo_optimistic_apply_queued", "logo", rgb,
                "before_rpc_response");
        long generation;
        synchronized (GomodLogoColorSync.class) {
            generation = ++colorRequestGeneration;
            pendingColorRequestGeneration = generation;
            pendingLogoQueryGeneration = 0L;
            currentRgb = rgb;
        }
        status = pendingStatus;
        applyOptimisticLocalLogoColor(rgb);
        requestPeerAppearanceRefresh();
        return generation;
    }

    private static void applyOptimisticLocalLogoColor(int rgb) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null
                || mc.getSession().getProfile().getId() == null) return;
        UUID playerId = mc.getSession().getProfile().getId();
        if (rgb == DEFAULT_SENTINEL) {
            int restoreRgb = nativeLogoRgb(playerId);
            if (restoreRgb >= 0 && restoreRgb <= 0xFFFFFF) {
                scheduleLocalLogoColor(playerId, restoreRgb, true, true);
            }
        } else if (rgb >= 0 && rgb <= 0xFFFFFF) {
            scheduleLocalLogoColor(playerId, rgb, false, true);
        }
    }

    private static boolean isCurrentColorRequest(long generation) {
        synchronized (GomodLogoColorSync.class) {
            return generation == 0L ? pendingColorRequestGeneration == 0L
                    : pendingColorRequestGeneration == generation;
        }
    }

    private static boolean commitColorResponse(long generation, int rgb) {
        synchronized (GomodLogoColorSync.class) {
            if (generation == 0L) {
                if (pendingColorRequestGeneration != 0L) return false;
            } else {
                if (pendingColorRequestGeneration != generation) return false;
                pendingColorRequestGeneration = 0L;
            }
            currentRgb = rgb;
            pendingLogoQueryGeneration = 0L;
            logoRestorePending = false;
            confirmedLogoAccount = localAccountUuid();
            return true;
        }
    }

    private static void rejectOptimisticLogoColor(long generation, int previousRgb, String failure) {
        if (generation != 0L) {
            boolean wasQuery;
            synchronized (GomodLogoColorSync.class) {
                if (pendingColorRequestGeneration != generation) return;
                wasQuery = pendingLogoQueryGeneration == generation;
                pendingColorRequestGeneration = 0L;
                pendingLogoQueryGeneration = 0L;
                if (!wasQuery) currentRgb = previousRgb;
            }
            // Cancel the optimistic retry even when the previous value was Default and
            // no native original has arrived yet. A rejected request must not later say Saved.
            if (!wasQuery) {
                nextLocalApplyGeneration();
                applyOptimisticLocalLogoColor(previousRgb);
                LunarFitColors.logoRejected(generation);
            }
        } else if (!isCurrentColorRequest(0L)) {
            return;
        }
        status = failure;
    }

    /**
     * Updates Lunar's per-player logo RGB record. The Lunar+ color manager is deliberately not
     * used here: its UUID/int setter changes the small plus glyph, not the Lunar logo body.
     */
    private static void scheduleLocalLogoColor(final UUID playerId, final int rgb,
                                                final boolean restoringDefault) {
        scheduleLocalLogoColor(playerId, rgb, restoringDefault, false);
    }

    private static void scheduleLocalLogoColor(final UUID playerId, final int rgb,
                                                final boolean restoringDefault,
                                                final boolean forceNativeRefresh) {
        final long generation = nextLocalApplyGeneration();
        applyLocalLogoColor(playerId, rgb, restoringDefault, forceNativeRefresh, generation, 0);
    }

    private static void applyLocalLogoColor(final UUID playerId, final int rgb,
                                             final boolean restoringDefault,
                                             final boolean forceNativeRefresh,
                                             final long generation, final int retry) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            status = "Saved, but Minecraft is not available to apply the logo color";
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (generation != localApplyGeneration) return;
                if (!restoringDefault && !isFullAccessColorAvailable()) return;
                try {
                    if (mc.getSession() == null || mc.getSession().getProfile() == null
                            || !playerId.equals(mc.getSession().getProfile().getId())) {
                        status = "Saved, but the local Minecraft identity changed before the logo update";
                        return;
                    }
                    boolean tabLogoUpdated = false;
                    Throwable tabLogoFailure = null;
                    Object manager = tabLogoManager;
                    if (manager != null) {
                        try {
                            Object cache = tabLogoCache(manager);
                            if (cache == null) throw new LogoProfileNotReadyException();
                            // Lunar stores records in Guava's package-private LocalCache implementation.
                            // Invoke through the public Cache interface; reflecting on LocalManualCache's
                            // class method fails access checks even though the interface method is public.
                            // Ask Lunar's own logo manager first. Its public getter filters out the
                            // cache's "not loaded" sentinel and also checks the current tab response.
                            Object oldRecord = tabLogoRecord(manager, playerId);
                            if (oldRecord == null) {
                                requestTabLogoProfile(manager, playerId);
                                throw new LogoProfileNotReadyException();
                            }

                            if (!restoringDefault) nativeLogoRgbByPlayer.putIfAbsent(playerId,
                                    Integer.valueOf(rgbFromRecord(oldRecord) & 0xFFFFFF));
                            Object updatedRecord = copyLogoRecordWithRgb(oldRecord, rgb);
                            invokeCache(cache, "put", new Class<?>[]{Object.class, Object.class},
                                    playerId, updatedRecord);
                            tabLogoUpdated = true;
                        } catch (LogoProfileNotReadyException notReady) {
                            // Keep trying the independent live player-profile cache below.
                        } catch (Throwable error) {
                            tabLogoFailure = error.getCause() == null ? error : error.getCause();
                        }
                    }

                    boolean liveProfileUpdated = false;
                    Throwable liveProfileFailure = null;
                    try {
                        applyLocalPlayerLogoProfile(playerId, rgb, forceNativeRefresh);
                        liveProfileUpdated = true;
                    } catch (LocalLogoProfileNotReadyException notReady) {
                        // The profile manager may arrive after Lunar receives its cosmetics push.
                    } catch (Throwable error) {
                        liveProfileFailure = error.getCause() == null ? error : error.getCause();
                    }

                    if (tabLogoUpdated && liveProfileUpdated) {
                        LunarConnectionDiagnostics.record("lunar_logo_color.local_apply",
                                "tabCache=updated liveProfile=updated forcedRefresh=" + forceNativeRefresh);
                        LunarNametagDebugCapture.recordColorFlow("logo_local_apply", "logo", rgb,
                                "tab_and_native_profile_updated");
                        GomodLunarPlusLocker.refreshNativeLockerState();
                        status = restoringDefault
                                ? String.format("Default logo color restored: #%06X", rgb)
                                : String.format("Saved and applied to Lunar logo: #%06X", rgb);
                        return;
                    }
                    if (tabLogoUpdated || liveProfileUpdated) {
                        LunarConnectionDiagnostics.record("lunar_logo_color.local_apply",
                                "tabCache=" + (tabLogoUpdated ? "updated" : "pending")
                                        + " liveProfile=" + (liveProfileUpdated ? "updated" : "pending")
                                        + " forcedRefresh=" + forceNativeRefresh);
                        LunarNametagDebugCapture.recordColorFlow("logo_local_apply", "logo", rgb,
                                "tab_" + (tabLogoUpdated ? "updated" : "pending")
                                        + "_profile_" + (liveProfileUpdated ? "updated" : "pending"));
                        GomodLunarPlusLocker.refreshNativeLockerState();
                        if (restoringDefault) {
                            status = String.format("API default saved; Lunar logo partially refreshed: #%06X", rgb);
                        } else if (liveProfileUpdated) {
                            status = String.format("Saved and applied locally: #%06X; Lunar TAB profile will update when loaded", rgb);
                        } else {
                            status = String.format("Saved and applied to Lunar TAB logo: #%06X; local profile will update when loaded", rgb);
                        }
                        return;
                    }
                    if (tabLogoFailure != null || liveProfileFailure != null) {
                        Throwable failure = tabLogoFailure != null ? tabLogoFailure : liveProfileFailure;
                        LunarNametagDebugCapture.recordColorFlow("logo_local_apply_failed", "logo", rgb,
                                failure.getClass().getSimpleName());
                        throw failure instanceof Exception
                                ? (Exception) failure : new Exception(failure);
                    }
                    throw new LogoProfileNotReadyException();
                } catch (LogoProfileNotReadyException notReady) {
                    queueLogoProfileRetry(playerId, rgb, restoringDefault, forceNativeRefresh,
                            generation, retry);
                } catch (LocalLogoProfileNotReadyException notReady) {
                    queueLogoProfileRetry(playerId, rgb, restoringDefault, forceNativeRefresh,
                            generation, retry);
                } catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status = restoringDefault
                            ? "Could not restore the default Lunar logo color: " + safeMessage(cause)
                            : String.format("Saved: #%06X; logo update failed: %s", rgb, safeMessage(cause));
                }
            }
        });
    }

    private static void queueLogoProfileRetry(final UUID playerId, final int rgb,
                                               final boolean restoringDefault,
                                               final boolean forceNativeRefresh,
                                               final long generation, final int retry) {
        if (retry >= MAX_LOGO_PROFILE_RETRIES) {
            status = restoringDefault
                    ? "API default saved; Lunar's local logo data is not loaded. Join a server or reopen Locker to retry."
                    : String.format("Saved: #%06X; Lunar's local logo data is not loaded. Join a server or reopen Locker to retry.", rgb);
            return;
        }
        status = restoringDefault
                ? "Waiting for Lunar's local logo data..."
                : String.format("Saved: #%06X; waiting for Lunar's local logo data...", rgb);
        LOGO_PROFILE_RETRY_EXECUTOR.schedule(new Runnable() {
            @Override
            public void run() {
                if (generation == localApplyGeneration) {
                    applyLocalLogoColor(playerId, rgb, restoringDefault, forceNativeRefresh,
                            generation, retry + 1);
                }
            }
        }, LOGO_PROFILE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static synchronized long nextLocalApplyGeneration() {
        return ++localApplyGeneration;
    }

    private static int rgbFromRecord(Object record) throws Exception {
        return ((Number) LunarColorRuntime.recordValue(record, 0, 3)).intValue() & 0xFFFFFF;
    }

    private static Object copyLogoRecordWithRgb(Object oldRecord, int rgb) throws Exception {
        return copyLogoRecordWithColors(oldRecord, rgb, tabRecordPlusRgb(oldRecord));
    }

    private static Object copyLogoRecordWithColors(Object oldRecord, int logoRgb, int plusRgb)
            throws Exception {
        return copyLogoRecordWithColorsAndBadge(oldRecord, logoRgb, plusRgb, null, false);
    }

    private static Object copyLogoRecordWithColorsAndBadge(Object oldRecord, int logoRgb, int plusRgb,
                                                             Object badge, boolean replaceBadge)
            throws Exception {
        Class<?> recordClass = oldRecord.getClass();
        Object[] arguments = {
                0xFF000000 | (logoRgb & 0xFFFFFF),
                plusRgb == 0 ? 0 : 0xFF000000 | (plusRgb & 0xFFFFFF),
                replaceBadge ? badge : LunarColorRuntime.recordValue(oldRecord, 2, 3)
        };
        for (Constructor<?> constructor : recordClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == arguments.length
                    && parameters[0] == Integer.TYPE && parameters[1] == Integer.TYPE
                    && (arguments[2] == null
                            ? !parameters[2].isPrimitive()
                            : parameters[2].isInstance(arguments[2]))) {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            }
        }
        throw new NoSuchMethodException("Lunar logo profile RGB constructor was not found");
    }

    private static Object copyPlayerLogoProfileWithBadge(Object oldProfile, Object badge)
            throws Exception {
        Class<?> recordClass = oldProfile.getClass();
        Object[] arguments = new Object[7];
        for (int index = 0; index < arguments.length - 1; index++) {
            arguments[index] = LunarColorRuntime.recordValue(oldProfile, index, arguments.length);
        }
        arguments[6] = badge;
        for (Constructor<?> constructor : recordClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 7 && parameters[0] == Float.TYPE
                    && parameters[1] == Float.TYPE && parameters[2] == Float.TYPE
                    && parameters[3] == Boolean.TYPE && parameters[4] == Integer.TYPE
                    && parameters[5] == Boolean.TYPE
                    && (badge == null ? !parameters[6].isPrimitive() : parameters[6].isInstance(badge))) {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            }
        }
        throw new NoSuchMethodException("Lunar player-logo profile badge constructor was not found");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyTabLogoPlusColor(UUID playerId, int plusRgb) throws Exception {
        applyTabLogoPlusColor(playerId, plusRgb, getLocalLogoRgbForRelay());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyTabLogoPlusColor(UUID playerId, int plusRgb, int logoRgbOverride) throws Exception {
        Object manager = tabLogoManager;
        if (manager == null || playerId == null) return;
        Object cache = tabLogoCache(manager);
        if (cache == null) return;
        Object oldRecord = tabLogoRecord(manager, playerId);
        if (oldRecord == null) {
            requestTabLogoProfile(manager, playerId);
            LunarNametagDebugCapture.recordColorFlow("tab_plus_cache", "plus", plusRgb,
                    "profile_missing_fetch_requested");
            return;
        }
        // This cache row belongs to playerId. Its fallback is that player's record,
        // never the local account's selected color.
        int logoRgb = logoRgbOverride >= 0 ? logoRgbOverride & 0xFFFFFF : rgbFromRecord(oldRecord);
        Object updatedRecord = copyLogoRecordWithColors(oldRecord, logoRgb, plusRgb);
        invokeCache(cache, "put", new Class<?>[]{Object.class, Object.class}, playerId, updatedRecord);
        LunarNametagDebugCapture.recordColorFlow("tab_plus_cache", "plus", plusRgb,
                "updated_logo=" + debugRgb(logoRgb) + " source="
                        + (logoRgbOverride >= 0 ? "peer_or_native_profile" : "existing_peer_tab_record"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Integer readTabLogoPlusRgb(UUID playerId) throws Exception {
        Object manager = tabLogoManager;
        if (manager == null || playerId == null) return null;
        Object profiles = tabLogoRecord(manager, playerId);
        if (profiles == null) return null;
        return Integer.valueOf(tabRecordPlusRgb(profiles) & 0xFFFFFF);
    }

    /** Updates Lunar's separate live player-profile cache, which its in-game logo renderer reads. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyLocalPlayerLogoProfile(UUID playerId, int rgb,
                                                    boolean forceNativeRefresh) throws Exception {
        Object manager = playerLogoProfileManager;
        if (manager == null) throw new LocalLogoProfileNotReadyException();

        Object profiles = playerLogoProfiles(manager);
        if (!(profiles instanceof Map)) {
            throw new IllegalStateException("Lunar player-logo profile cache is unavailable");
        }
        Map profileMap = (Map) profiles;
        Object oldRecord = profileMap.get(playerId);
        if (oldRecord != null && !nativeLogoRgbByPlayer.containsKey(playerId)
                && rgbFromPlayerLogoProfile(oldRecord) != (rgb & 0xFFFFFF)) {
            nativeLogoRgbByPlayer.putIfAbsent(playerId,
                    Integer.valueOf(rgbFromPlayerLogoProfile(oldRecord) & 0xFFFFFF));
        }
        boolean alreadyCurrent = oldRecord != null
                && rgbFromPlayerLogoProfile(oldRecord) == (rgb & 0xFFFFFF);
        if (alreadyCurrent && !forceNativeRefresh) return;

        /*
         * Updating just the map is not enough: Lunar's own PlayerCosmeticsPushV2 handler also
         * runs the local profile/cache refresh path used by its renderer. Reuse that handler with
         * a local protobuf (this does not send anything over the network). It can also create the
         * local profile entry when Lunar has not received one yet, which is why the old code kept
         * waiting until an account switch caused a fresh push.
         */
        ClassLoader loader = manager.getClass().getClassLoader();
        Class<?> colorClass = load("com.lunarclient.common.v1.Color", loader);
        Object logoColorBuilder = invokeStatic(colorClass, "newBuilder", new Class<?>[0]);
        invoke(logoColorBuilder, "setColor", new Class<?>[]{Integer.TYPE}, rgb & 0xFFFFFF);

        int plusRgb = 0;
        boolean alwaysShow = false;
        if (oldRecord != null) {
            plusRgb = playerLogoPlusRgb(oldRecord);
            alwaysShow = playerLogoAlwaysShow(oldRecord);
        } else {
            plusRgb = getCachedLunarPlusColor(playerId);
        }
        Object plusColorBuilder = invokeStatic(colorClass, "newBuilder", new Class<?>[0]);
        invoke(plusColorBuilder, "setColor", new Class<?>[]{Integer.TYPE}, plusRgb);

        Class<?> pushClass = load("com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2", loader);
        Object pushBuilder = invokeStatic(pushClass, "newBuilder", new Class<?>[0]);
        invokeSingleArgument(pushBuilder, "setLogoColor", logoColorBuilder);
        invokeSingleArgument(pushBuilder, "setPlusColor", plusColorBuilder);
        invoke(pushBuilder, "setLogoAlwaysShow", new Class<?>[]{Boolean.TYPE}, alwaysShow);
        Object push = invoke(pushBuilder, "build", new Class<?>[0]);

        APPLYING_NATIVE_PROFILE.set(Boolean.TRUE);
        try {
            applyPlayerLogoPush(manager, playerId, pushClass, push,
                    playerLogoBadgeIdForUpdate(playerId, oldRecord));
        } finally {
            APPLYING_NATIVE_PROFILE.remove();
        }

        Object updatedRecord = profileMap.get(playerId);
        if (updatedRecord == null || rgbFromPlayerLogoProfile(updatedRecord) != (rgb & 0xFFFFFF)) {
            throw new IllegalStateException("Lunar did not accept the local logo-profile refresh");
        }
        LunarTabLogoCompatibility.invalidateProfileLogoWidth(playerId);
        LunarConnectionDiagnostics.record("lunar_logo_color.local_profile_update",
                "result=" + (alreadyCurrent ? "refreshed" : "updated")
                        + " forcedRefresh=" + forceNativeRefresh);
        LunarNametagDebugCapture.recordColorFlow("native_logo_profile_apply", "logo", rgb,
                alreadyCurrent ? "refreshed" : "updated");
    }

    @SuppressWarnings("rawtypes")
    private static void applyLocalPlayerLunarPlusColor(UUID playerId, int rgb,
                                                      boolean forceNativeRefresh) throws Exception {
        if (isLocalFullLunarDisguiseActive(playerId)) {
            LunarConnectionDiagnostics.record("lunar_plus.local_profile_update",
                    "result=skipped reason=full_lunar_disguise");
            return;
        }
        Object manager = playerLogoProfileManager;
        if (manager == null) throw new LocalLogoProfileNotReadyException();
        Object profiles = playerLogoProfiles(manager);
        if (!(profiles instanceof Map)) {
            throw new IllegalStateException("Lunar player-logo profile cache is unavailable");
        }
        Map profileMap = (Map) profiles;
        Object oldRecord = profileMap.get(playerId);
        if (oldRecord != null && playerLogoPlusRgb(oldRecord) > 0
                && !selectedPlusRgbByPlayer.containsKey(playerId)) {
            originalPlusRgbByPlayer.putIfAbsent(playerId, playerLogoPlusRgb(oldRecord));
        }
        int desiredPlusRgb = ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected()
                && ConfigManager.isLunarPlusHideBadge() ? 0 : rgb & 0xFFFFFF;
        boolean alreadyCurrent = oldRecord != null
                && playerLogoPlusRgb(oldRecord) == desiredPlusRgb;
        int selectedLogoRgb = getLocalLogoRgbForRelay();
        int oldLogoRgb = oldRecord == null ? -1 : rgbFromPlayerLogoProfile(oldRecord) & 0xFFFFFF;
        int logoRgb = resolveLogoRgbForPlusRefresh(playerId, oldLogoRgb, selectedLogoRgb);
        // A missing native profile is not black. Wait for actual logo data instead of
        // creating a zero-logo profile just because the user changed their plus color.
        if (logoRgb < 0) throw new LocalLogoProfileNotReadyException();
        boolean logoNeedsRestore = oldRecord != null && oldLogoRgb != logoRgb;
        if (alreadyCurrent && !forceNativeRefresh && !logoNeedsRestore) {
            applyTabLogoPlusColor(playerId, desiredPlusRgb);
            LunarConnectionDiagnostics.record("lunar_plus.local_profile_update",
                    "result=already-current noPlus=" + (desiredPlusRgb == 0));
            LunarNametagDebugCapture.recordColorFlow("plus_local_apply", "plus", desiredPlusRgb,
                    "already_current_tab_refreshed");
            return;
        }

        if (oldRecord != null && oldLogoRgb == 0 && logoRgb > 0 && selectedLogoRgb < 0) {
            LunarNametagDebugCapture.recordColorFlow("plus_logo_zero_repair", "logo", logoRgb,
                    "restoring_preselection_color_after_native_zero");
        }
        boolean alwaysShow = oldRecord != null && playerLogoAlwaysShow(oldRecord);
        ClassLoader loader = manager.getClass().getClassLoader();
        Class<?> colorClass = load("com.lunarclient.common.v1.Color", loader);
        Object logoColorBuilder = invokeStatic(colorClass, "newBuilder", new Class<?>[0]);
        invoke(logoColorBuilder, "setColor", new Class<?>[]{Integer.TYPE}, logoRgb);
        Object plusColorBuilder = invokeStatic(colorClass, "newBuilder", new Class<?>[0]);
        invoke(plusColorBuilder, "setColor", new Class<?>[]{Integer.TYPE}, desiredPlusRgb);

        Class<?> pushClass = load("com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2", loader);
        Object pushBuilder = invokeStatic(pushClass, "newBuilder", new Class<?>[0]);
        invokeSingleArgument(pushBuilder, "setLogoColor", logoColorBuilder);
        invokeSingleArgument(pushBuilder, "setPlusColor", plusColorBuilder);
        invoke(pushBuilder, "setLogoAlwaysShow", new Class<?>[]{Boolean.TYPE}, alwaysShow);
        Object push = invoke(pushBuilder, "build", new Class<?>[0]);

        APPLYING_NATIVE_PROFILE.set(Boolean.TRUE);
        try {
            applyPlayerLogoPush(manager, playerId, pushClass, push,
                    playerLogoBadgeIdForUpdate(playerId, oldRecord));
        } finally {
            APPLYING_NATIVE_PROFILE.remove();
        }

        Object updatedRecord = profileMap.get(playerId);
        if (updatedRecord == null || playerLogoPlusRgb(updatedRecord) != desiredPlusRgb) {
            throw new IllegalStateException("Lunar did not accept the local Lunar+ preview refresh");
        }
        LunarTabLogoCompatibility.invalidateProfileLogoWidth(playerId);
        applyTabLogoPlusColor(playerId, desiredPlusRgb);
        LunarConnectionDiagnostics.record("lunar_plus.local_profile_update",
                "result=" + (alreadyCurrent ? "refreshed" : "updated")
                        + " forcedRefresh=" + forceNativeRefresh
                        + " noPlus=" + (desiredPlusRgb == 0));
        LunarNametagDebugCapture.recordColorFlow("plus_local_apply", "plus", desiredPlusRgb,
                "native_and_tab_updated");
    }

    private static int resolveLogoRgbForPlusRefresh(UUID playerId, int oldLogoRgb,
                                                     int selectedLogoRgb) {
        if (selectedLogoRgb >= 0) return selectedLogoRgb & 0xFFFFFF;
        Integer beforePlus = logoRgbBeforePlusSelectionByPlayer.get(playerId);
        Long snapshotTime = plusLogoSnapshotTimeByPlayer.get(playerId);
        boolean recentLocalPlusSelection = ConfigManager.isLunarTweaksEnabled()
                && selectedPlusRgbByPlayer.containsKey(playerId)
                && snapshotTime != null
                && System.currentTimeMillis() - snapshotTime.longValue() <= PLUS_LOGO_SNAPSHOT_MAX_AGE_MS;
        if (recentLocalPlusSelection && beforePlus != null && beforePlus.intValue() > 0
                && (oldLogoRgb < 0 || oldLogoRgb == 0)) {
            return beforePlus.intValue() & 0xFFFFFF;
        }
        if (oldLogoRgb >= 0) return oldLogoRgb & 0xFFFFFF;
        return nativeLogoRgb(playerId);
    }

    private static Object playerLogoProfiles(Object manager) throws Exception {
        return LunarColorRuntime.profileMap(manager);
    }

    private static int playerLogoPlusRgb(Object profile) throws Exception {
        return ((Number) LunarColorRuntime.recordValue(profile, 4, 7)).intValue() & 0xFFFFFF;
    }

    private static boolean playerLogoAlwaysShow(Object profile) throws Exception {
        return ((Boolean) LunarColorRuntime.recordValue(profile, 3, 7)).booleanValue();
    }

    /** Lunar stores the resolved badge object in its native profile record. */
    private static int playerLogoBadgeId(Object profile) throws Exception {
        Object badge = LunarColorRuntime.recordValue(profile, 6, 7);
        if (badge == null) {
            return 0;
        }
        if (badge instanceof Number) {
            return ((Number) badge).intValue();
        }
        return ((Number) invokeFirst(badge, "id", "getId", "getBadgeId")).intValue();
    }

    private static int playerLogoBadgeIdForUpdate(UUID playerId, Object oldProfile) throws Exception {
        Integer selected = playerId == null ? null : selectedBadgeIdByPlayer.get(playerId);
        if (selected != null) return selected.intValue();
        if (oldProfile != null) {
            return playerLogoBadgeId(oldProfile);
        }
        // A local profile can be initialized from LoginResponse before Lunar creates its
        // PlayerLogoProfile record. Preserve the selected badge from that captured snapshot.
        return LunarFullDisguise.localSnapshotBadgeId(playerId);
    }

    /**
     * Called after Lunar's native badge manager changes its equipped badge (including early
     * returns when the active outfit already holds the badge or is not yet loaded) and when
     * switching outfits in Locker.
     */
    public static void rememberNativeBadgeSelection(Object badge) {
        final UUID playerId = localAccountUuid();
        if (playerId == null) return;
        int badgeId = 0;
        try {
            if (badge != null) {
                badgeId = ((Number) invokeFirst(badge, "id", "getId", "getBadgeId")).intValue();
                if (badgeId < 0) badgeId = 0;
                selectedBadgeObjectByPlayer.put(playerId, badge);
            } else {
                selectedBadgeObjectByPlayer.remove(playerId);
            }
            selectedBadgeIdByPlayer.put(playerId, Integer.valueOf(badgeId));
            LunarConnectionDiagnostics.record("lunar_badge.selection_captured",
                    "source=native_badge_manager result=stored badgePresent=" + (badgeId > 0));
            syncSelectedLocalBadgeCaches(playerId, "selection");
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar_badge.selection_captured",
                    "source=native_badge_manager result=failed errorType="
                            + error.getClass().getSimpleName());
        }
    }

    /** Restore the selected badge if a later cosmetics push supplies an older badge value. */
    public static void preserveSelectedLocalBadgeAfterProfileUpdate(Object manager, UUID playerId) {
        if (Boolean.TRUE.equals(APPLYING_NATIVE_PROFILE.get()) || manager == null
                || playerId == null || !playerId.equals(localAccountUuid())
                || LunarFullDisguise.isActive()) return;
        applySelectedLocalBadgeToPlayerLogoProfile(manager, playerId, "profile_push");
        applySelectedLocalBadgeToTabCache(playerId);
    }

    private static void syncSelectedLocalBadgeCaches(final UUID playerId, final String reason) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && !mc.isCallingFromMinecraftThread()) {
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    applySelectedLocalBadgeToPlayerLogoProfile(playerLogoProfileManager, playerId, reason);
                    applySelectedLocalBadgeToTabCache(playerId);
                }
            });
            return;
        }
        applySelectedLocalBadgeToPlayerLogoProfile(playerLogoProfileManager, playerId, reason);
        applySelectedLocalBadgeToTabCache(playerId);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applySelectedLocalBadgeToPlayerLogoProfile(Object manager, UUID playerId, String reason) {
        if (manager == null || playerId == null || !playerId.equals(localAccountUuid())
                || LunarFullDisguise.isActive()) return;
        Integer selected = selectedBadgeIdByPlayer.get(playerId);
        if (selected == null) return;
        int desiredBadgeId = selected.intValue();
        try {
            Object profiles = playerLogoProfiles(manager);
            if (!(profiles instanceof Map)) return;
            Map profileMap = (Map) profiles;
            Object current = profileMap.get(playerId);
            if (current == null) return;
            int actualBadgeId = playerLogoBadgeId(current);
            if (actualBadgeId != desiredBadgeId) {
                Object badge = desiredBadgeId == 0 ? null : selectedBadgeObjectByPlayer.get(playerId);
                if (desiredBadgeId != 0 && badge == null) return;
                profileMap.put(playerId, copyPlayerLogoProfileWithBadge(current, badge));
                LunarTabLogoCompatibility.invalidateProfileLogoWidth(playerId);
                LunarConnectionDiagnostics.record("lunar_badge.profile_repair",
                        "reason=" + reason + " result=restored stalePushBadge=" + (actualBadgeId > 0)
                                + " selectedBadge=" + (desiredBadgeId > 0));
            }
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar_badge.profile_repair",
                    "reason=" + reason + " result=failed errorType=" + error.getClass().getSimpleName());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applySelectedLocalBadgeToTabCache(UUID playerId) {
        if (playerId == null || !playerId.equals(localAccountUuid())
                || LunarFullDisguise.isActive() || !selectedBadgeIdByPlayer.containsKey(playerId)) return;
        try {
            Object manager = tabLogoManager;
            if (manager == null) return;
            Object cache = tabLogoCache(manager);
            Object oldRecord = tabLogoRecord(manager, playerId);
            if (cache == null || oldRecord == null) return;
            int selectedId = selectedBadgeIdByPlayer.get(playerId).intValue();
            Object badge = selectedId == 0 ? null : selectedBadgeObjectByPlayer.get(playerId);
            // If a future native layout does not expose the selected badge object, retain the
            // existing TAB object instead of replacing a valid badge with null.
            boolean replaceBadge = selectedId == 0 || badge != null;
            Object currentBadge = LunarColorRuntime.recordValue(oldRecord, 2, 3);
            boolean cacheStale = false;
            try {
                Object cachedRecord = invokeCache(cache, "getIfPresent", new Class<?>[]{Object.class}, playerId);
                if (cachedRecord != null) {
                    int cachedLogo = ((Number) LunarColorRuntime.recordValue(cachedRecord, 0, 3)).intValue();
                    int cachedPlus = ((Number) LunarColorRuntime.recordValue(cachedRecord, 1, 3)).intValue();
                    boolean isNotLoadedSentinel = cachedLogo == -1 && cachedPlus == -1;
                    if (!isNotLoadedSentinel && LunarColorRuntime.recordValue(cachedRecord, 2, 3) != badge) {
                        cacheStale = true;
                    }
                }
            } catch (Throwable ignoredCacheCheck) {
                // Fall back to the manager's projected record comparison.
            }
            if (replaceBadge && (currentBadge != badge || cacheStale)) {
                Object updated = copyLogoRecordWithColorsAndBadge(oldRecord,
                        rgbFromRecord(oldRecord), tabRecordPlusRgb(oldRecord), badge, true);
                invokeCache(cache, "put", new Class<?>[]{Object.class, Object.class}, playerId, updated);
                LunarTabLogoCompatibility.invalidateProfileLogoWidth(playerId);
            }
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar_badge.tab_repair",
                    "result=failed errorType=" + error.getClass().getSimpleName());
        }
    }

    private static int playerLogoPushBadgeId(Object push) throws Exception {
        return ((Number) invokeFirst(push, "getBadgeId")).intValue();
    }

    private static void applyPlayerLogoPush(Object manager, UUID playerId,
                                            Class<?> pushClass, Object push, int badgeId) throws Exception {
        LunarColorRuntime.applyProfilePush(manager, playerId, pushClass, push, badgeId);
    }

    private static Object invokeFirst(Object target, String... methodNames) throws Exception {
        NoSuchMethodException missing = null;
        for (String name : methodNames) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (NoSuchMethodException error) {
                missing = error;
            }
        }
        throw missing == null ? new NoSuchMethodException("No Lunar method candidates provided") : missing;
    }

    private static int rgbFromPlayerLogoProfile(Object record) throws Exception {
        return LunarColorRuntime.profileRgb(record);
    }

    private static int getCachedLunarPlusColor(UUID playerId) {
        Integer selected = selectedLocalPlusColor(playerId);
        Integer nativeRgb = nativePlusRgbByPlayer.get(playerId);
        return selected != null ? selected.intValue() : nativeRgb != null ? nativeRgb.intValue() : 0;
    }

    private static Integer selectedLocalPlusColor(UUID playerId) {
        UUID account = localAccountUuid();
        if (playerId != null && playerId.equals(account)) {
            int saved = LunarPlusAccountPreferences.getSelectedRgb();
            if (saved >= 0) return Integer.valueOf(saved);
        }
        return selectedPlusRgbByPlayer.get(playerId);
    }

    private static UUID localAccountUuid() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null) return null;
        try {
            String raw = mc.getSession().getPlayerID();
            return parseUuid(raw);
        } catch (Throwable ignored) { return null; }
    }

    private static void invokeSingleArgument(Object target, String methodName, Object argument)
            throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (methodName.equals(method.getName()) && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].isInstance(argument)) {
                method.invoke(target, argument);
                return;
            }
        }
        throw new NoSuchMethodException("Lunar protobuf builder method was not found: " + methodName);
    }

    private static Object invokeStatic(Class<?> type, String methodName, Class<?>[] parameterTypes,
                                       Object... args) throws Exception {
        Method method = type.getMethod(methodName, parameterTypes);
        return method.invoke(null, args);
    }

    private static Object invokeCache(Object cache, String methodName, Class<?>[] parameterTypes,
                                      Object... args) throws Exception {
        if (cache == null) {
            throw new IllegalStateException("Lunar tab-logo cache is unavailable");
        }
        ClassLoader loader = cache.getClass().getClassLoader();
        Class<?> cacheApi = Class.forName("com.google.common.cache.Cache", true, loader);
        Method method = cacheApi.getMethod(methodName, parameterTypes);
        return method.invoke(cache, args);
    }

    /** Requests a missing UUID through Lunar's existing tab-logo fetch path. */
    private static void requestTabLogoProfile(Object manager, UUID playerId) throws Exception {
        LunarNametagDebugCapture.recordColorFlow("tab_logo_profile_fetch", "tab", -1, "requested");
        LunarColorRuntime.requestTabRecord(manager, playerId);
    }

    private static final class LogoProfileNotReadyException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class LocalLogoProfileNotReadyException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static Method findUpdateMethod(Class<?> serviceClass, Class<?> requestClass) throws Exception {
        Class<?> serviceApi = Class.forName("com.lunarclient.websocket.cosmetic.v2.CosmeticService$Interface",
                false, serviceClass.getClassLoader());
        if (!serviceApi.isAssignableFrom(serviceClass)) throw new IllegalStateException("Unsupported Lunar cosmetics service instance");
        for (Method method : serviceApi.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("updateLunarPlusColor".equals(method.getName())
                    && parameters.length == 3
                    && parameters[0].getName().equals("com.google.protobuf.RpcController")
                    && parameters[1].isAssignableFrom(requestClass)
                    && parameters[2].getName().equals("com.google.protobuf.RpcCallback") && parameters[2].isInterface()) {
                return method;
            }
        }
        throw new IllegalStateException("Lunar cosmetics-v2 color RPC was not found");
    }

    private static LunarRuntime resolveLunarRuntime() throws Exception {
        Object service = LunarColorRuntime.cosmeticService();
        return new LunarRuntime(service.getClass().getClassLoader(),
                LunarColorRuntime.cosmeticSocket(), service);
    }



    private static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException {
        if (loader != null) return Class.forName(name, true, loader);
        return Class.forName(name);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target, args);
    }

    private static Object invokeAny(Object target, Class<?>[] parameterTypes, Object[] args,
                                    String... methodNames) throws Exception {
        for (String name : methodNames) {
            final Method method;
            try {
                method = target.getClass().getMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            return method.invoke(target, args);
        }
        throw new NoSuchMethodException("No supported Lunar method with the required signature");
    }

    private static Object tabLogoRecord(Object manager, UUID playerId) throws Exception {
        return LunarColorRuntime.tabRecord(manager, playerId);
    }

    private static int tabRecordPlusRgb(Object record) throws Exception {
        return ((Number) LunarColorRuntime.recordValue(record, 1, 3)).intValue() & 0xFFFFFF;
    }

    private static Object tabLogoCache(Object manager) throws Exception {
        return LunarColorRuntime.tabCache(manager);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static final class LunarRuntime {
        final ClassLoader loader;
        final Object client;
        final Object cosmeticService;

        LunarRuntime(ClassLoader loader, Object client, Object cosmeticService) {
            this.loader = loader;
            this.client = client;
            this.cosmeticService = cosmeticService;
        }
    }
}
