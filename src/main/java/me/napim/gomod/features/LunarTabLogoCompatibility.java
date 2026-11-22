package me.napim.gomod.features;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reads Lunar's own TAB-logo layout width without linking against Lunar classes at compile time. */
public final class LunarTabLogoCompatibility {
    private static final String CLIENT_CLASS =
            "com.moonsworth.lunar.client.RRHORRHIIIOHHCICIORHHOHHOHIIHR";
    private static volatile boolean initialized;
    private static volatile Object logoManager;
    private static volatile Method logoWidthMethod;
    private static volatile String initializationError = "not_initialized";
    private static final Map<UUID, Integer> observedLogoWidths = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> profileLogoWidths = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> observedPrimaryLogo = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> observedBadgeLogo = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> observedNativeRowX = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> observedNativeDrawCalls = new ConcurrentHashMap<>();
    private static volatile boolean nativeRowDrawObserved;
    private static volatile Method lunarStringWidthMethod;
    private static volatile Method lunarDrawStringMethod;
    private static volatile Method lunarDrawShadowMethod;
    private static volatile Class<?> lunarFontRuntimeClass;
    private static volatile String nativeDrawError = "not_attempted";

    private LunarTabLogoCompatibility() {
    }

    static int getLogoWidth(UUID playerId) {
        return resolveLogoWidth(playerId, false);
    }

    /** Resolves a player logo and attributes the result to the nametag diagnostic when it's active. */
    static int getLogoWidthForNametag(UUID playerId) {
        return resolveLogoWidth(playerId, true);
    }

    private static int resolveLogoWidth(UUID playerId, boolean captureForNametag) {
        DisguiserLunarIcon icon = LunarDisguiserBadge.iconPolicy(playerId);
        if (GomodFixes.isWeaveRuntime() && icon != DisguiserLunarIcon.KEEP) {
            return reportNametagWidth(icon == DisguiserLunarIcon.LUNAR ? 9 : 0,
                    "manual_disguise_" + icon.wire, captureForNametag);
        }
        int width = resolveNativeLogoWidth(playerId, captureForNametag);
        return LunarDisguiserBadge.shouldHidePlayerBadge(playerId)
                ? reportNametagWidth(Math.min(width, 9), "manual_disguise_basic_logo", captureForNametag) : width;
    }

    private static int resolveNativeLogoWidth(UUID playerId, boolean captureForNametag) {
        if (playerId == null || !GomodFixes.isWeaveRuntime()) {
            return reportNametagWidth(0, "not_weave_or_no_player", captureForNametag);
        }
        Integer observed = observedLogoWidths.get(playerId);
        if (observed != null) {
            String source = observed == 0
                    ? describeZeroCacheSource(playerId, "tab_observed_cache", captureForNametag)
                    : "tab_observed_cache";
            return reportNametagWidth(observed, source, captureForNametag);
        }

        Integer profileWidth = profileLogoWidths.get(playerId);
        if (profileWidth != null) {
            String source = profileWidth == 0
                    ? describeZeroCacheSource(playerId, "profile_width_cache", captureForNametag)
                    : "profile_width_cache";
            return reportNametagWidth(profileWidth, source, captureForNametag);
        }

        int currentProfileWidth = GomodLogoColorSync.getNativeTabLogoWidth(playerId);
        if (currentProfileWidth > 0) {
            cacheProfileLogoWidth(playerId, currentProfileWidth);
            return reportNametagWidth(currentProfileWidth, "live_profile", captureForNametag);
        }
        String nativeProfileSource = captureForNametag
                ? (currentProfileWidth == 0
                    ? GomodLogoColorSync.describeNativeTabLogoLookup(playerId)
                    : "profile_manager_unavailable")
                : "profile_manager_zero";

        ensureInitialized();
        Object manager = logoManager;
        Method width = logoWidthMethod;
        if (manager == null || width == null) {
            if (currentProfileWidth >= 0) {
                cacheProfileLogoWidth(playerId, currentProfileWidth);
                return reportNametagWidth(currentProfileWidth, nativeProfileSource, captureForNametag);
            }
            return reportNametagWidth(0, nativeProfileSource, captureForNametag);
        }
        try {
            Object result = width.invoke(manager, playerId);
            int legacyWidth = result instanceof Number ? Math.max(0, ((Number) result).intValue()) : 0;
            int resolvedWidth = Math.max(legacyWidth, Math.max(0, currentProfileWidth));
            if (currentProfileWidth >= 0) {
                cacheProfileLogoWidth(playerId, resolvedWidth);
            }
            return reportNametagWidth(resolvedWidth,
                    legacyWidth > 0 ? "legacy_manager" : nativeProfileSource + "_and_legacy_zero",
                    captureForNametag);
        } catch (Throwable ignored) {
            if (currentProfileWidth >= 0) {
                cacheProfileLogoWidth(playerId, currentProfileWidth);
                return reportNametagWidth(currentProfileWidth, "profile_manager_after_legacy_error",
                        captureForNametag);
            }
            return reportNametagWidth(0, "logo_lookup_error", captureForNametag);
        }
    }

    private static int reportNametagWidth(int width, String source, boolean captureForNametag) {
        if (captureForNametag) {
            LunarNametagDebugCapture.recordLogoLookup(width, source);
        }
        return width;
    }

    private static void cacheProfileLogoWidth(UUID playerId, int width) {
        if (!LunarDisguiserBadge.shouldHidePlayerBadge(playerId)) profileLogoWidths.put(playerId, width);
    }

    private static String describeZeroCacheSource(UUID playerId, String cacheSource,
                                                   boolean captureForNametag) {
        if (!captureForNametag || !LunarNametagDebugCapture.shouldInspectCachedZero(playerId)) {
            return cacheSource;
        }
        int liveWidth = GomodLogoColorSync.getNativeTabLogoWidth(playerId);
        String liveProfile = liveWidth > 0
                ? (liveWidth == 18 ? "live_logo_plus" : "live_logo")
                : GomodLogoColorSync.describeNativeTabLogoLookup(playerId);
        return cacheSource + "_zero_live_" + (liveWidth < 0 ? "unknown" : String.valueOf(liveWidth))
                + "_" + liveProfile;
    }

    /** Invalidates the current-profile-derived width when Lunar changes one player's logo data. */
    public static void invalidateProfileLogoWidth(UUID playerId) {
        if (playerId != null) {
            profileLogoWidths.remove(playerId);
        }
    }

    /** Clears one player's rendered measurements when a temporary disguise changes logo presence. */
    public static void invalidatePlayerLogoMeasurements(UUID playerId) {
        if (playerId == null) return;
        observedLogoWidths.remove(playerId);
        profileLogoWidths.remove(playerId);
        observedPrimaryLogo.remove(playerId);
        observedBadgeLogo.remove(playerId);
        observedNativeRowX.remove(playerId);
        observedNativeDrawCalls.remove(playerId);
    }

    /** Clears profile-derived widths after Lunar replaces its profile snapshot. */
    public static void clearProfileLogoWidths() {
        profileLogoWidths.clear();
    }

    /** Clears route-derived TAB measurements so an official-route width cannot leak into FullAccess rendering. */
    public static void clearRouteLogoWidths() {
        observedLogoWidths.clear();
        profileLogoWidths.clear();
        observedPrimaryLogo.clear();
        observedBadgeLogo.clear();
    }

    /** Called from the Lunar-targeted mixin, inside Lunar's own classloader/render path. */
    public static void recordLogoWidth(UUID playerId, int width) {
        if (playerId != null && !LunarDisguiserBadge.shouldHidePlayerBadge(playerId)) {
            observedLogoWidths.put(playerId, Math.max(0, width));
        }
    }

    /** Current Lunar exposes two 8px render attempts; each successful icon occupies 9px in TAB layout. */
    public static void recordPrimaryLogoPresence(UUID playerId, boolean rendered) {
        if (playerId != null && !LunarDisguiserBadge.shouldHidePlayerBadge(playerId)) {
            observedPrimaryLogo.put(playerId, rendered);
            updateObservedCurrentWidth(playerId);
        }
    }

    public static boolean isNativeRowDrawObserved() {
        return nativeRowDrawObserved;
    }

    /** Draws one row's stat with the exact font, matrix, and coordinates used by current Lunar. */
    public static void drawNativeAlignedStats(Object matrix, float rowX, float rowY, UUID playerId) {
        // Record the actual native callback before evaluating the layout policy. Public Weave may keep
        // stats inline until this hook runs, then switch to the right-aligned native column.
        nativeRowDrawObserved = true;
        if (!TabStatsLayoutMode.shouldSeparateStats()) {
            return;
        }
        if (matrix == null || playerId == null) {
            return;
        }
        try {
            observedNativeRowX.put(playerId, rowX);
            observedNativeDrawCalls.merge(playerId, 1, Integer::sum);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null || mc.fontRendererObj == null) {
                return;
            }
            Object font = mc.fontRendererObj;
            ensureNativeFontMethods(font, matrix);
            Method widthMethod = lunarStringWidthMethod;
            Method drawMethod = lunarDrawStringMethod;
            Method shadowMethod = lunarDrawShadowMethod;
            if (widthMethod == null || (shadowMethod == null && drawMethod == null)) {
                return;
            }
            String[] current = KillCounter.getLunarNativeTabDrawParts(playerId);
            if (current == null) {
                return;
            }
            float maxWidth = 0.0f;
            float spaceWidth = Math.max(1.0f, number(widthMethod.invoke(font, " ")));
            for (String[] row : KillCounter.getAllLunarNativeTabDrawParts()) {
                // TAB may have multiple side-by-side columns. Only rows whose native origin matches this row belong
                // to this column; using one global maximum lets column 1's stats spill into column 2.
                if (row.length < 4 || row[3] == null || row[3].isEmpty()) {
                    continue;
                }
                float candidateRowX = Float.parseFloat(row[3]);
                if (Math.abs(candidateRowX - rowX) > 0.25f) {
                    continue;
                }
                // Lunar draws its icon to the left of the supplied text origin. Do not reserve another icon slot
                // here: doing so moved every stats column 9px farther away from the actual row text.
                float total = number(widthMethod.invoke(font, row[0]))
                        + spaceWidth + number(widthMethod.invoke(font, row[1]));
                maxWidth = Math.max(maxWidth, total);
            }
            float statsWidth = number(widthMethod.invoke(font, current[1]));
            if (maxWidth <= 0.0f) {
                maxWidth = number(widthMethod.invoke(font, current[0])) + spaceWidth + statsWidth;
            }
            // Keep Lunar's real row origin. Logo-based normalization moved later no-logo rows incorrectly when
            // the lobby composition changed.
            float statsX = rowX + maxWidth - statsWidth - 3.0f;
            // This is Lunar's dedicated draw-with-shadow entry. It owns both passes and their matrix state; invoking
            // bridge$drawString twice displaced the second pass by an entire text run on the current font renderer.
            if (shadowMethod != null) {
                shadowMethod.invoke(font, matrix, current[1], statsX, rowY, -1);
            } else {
                drawMethod.invoke(font, matrix, current[1], statsX, rowY, -1, false);
            }
            nativeDrawError = "none";
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            nativeDrawError = cause.getClass().getName() + ": " + String.valueOf(cause.getMessage());
        }
    }

    static String describeNativeRow(UUID playerId) {
        Float x = playerId == null ? null : observedNativeRowX.get(playerId);
        Integer calls = playerId == null ? null : observedNativeDrawCalls.get(playerId);
        return "native_row_x=" + (x == null ? "<unseen>" : x)
                + " native_calls=" + (calls == null ? 0 : calls);
    }

    static Float getObservedNativeRowX(UUID playerId) {
        return playerId == null ? null : observedNativeRowX.get(playerId);
    }

    private static float number(Object value) {
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    private static synchronized void ensureNativeFontMethods(Object font, Object matrix) {
        if (font == null || (lunarFontRuntimeClass == font.getClass()
                && lunarStringWidthMethod != null
                && (lunarDrawShadowMethod != null || lunarDrawStringMethod != null))) {
            return;
        }
        lunarFontRuntimeClass = font.getClass();
        lunarStringWidthMethod = null;
        lunarDrawStringMethod = null;
        lunarDrawShadowMethod = null;
        for (Method method : font.getClass().getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if ("bridge$getStringWidth".equals(method.getName()) && params.length == 1
                    && params[0] == String.class) {
                method.setAccessible(true);
                lunarStringWidthMethod = method;
            } else if ("bridge$drawString".equals(method.getName()) && params.length == 6
                    && params[0].isInstance(matrix) && params[1] == String.class
                    && params[2] == Float.TYPE && params[3] == Float.TYPE
                    && params[4] == Integer.TYPE && params[5] == Boolean.TYPE) {
                method.setAccessible(true);
                lunarDrawStringMethod = method;
            } else if ("bridge$drawShadow".equals(method.getName()) && params.length == 5
                    && params[0].isInstance(matrix) && params[1] == String.class
                    && params[2] == Float.TYPE && params[3] == Float.TYPE
                    && params[4] == Integer.TYPE) {
                method.setAccessible(true);
                lunarDrawShadowMethod = method;
            }
        }
        if (lunarStringWidthMethod == null
                || (lunarDrawShadowMethod == null && lunarDrawStringMethod == null)) {
            nativeDrawError = "font_methods_missing class=" + font.getClass().getName();
        }
    }

    public static void recordBadgeLogoPresence(UUID playerId, boolean rendered) {
        if (playerId != null && !LunarDisguiserBadge.shouldHidePlayerBadge(playerId)) {
            observedBadgeLogo.put(playerId, rendered);
            updateObservedCurrentWidth(playerId);
        }
    }

    private static void updateObservedCurrentWidth(UUID playerId) {
        int width = Boolean.TRUE.equals(observedPrimaryLogo.get(playerId)) ? 9 : 0;
        if (Boolean.TRUE.equals(observedBadgeLogo.get(playerId))) {
            width += 9;
        }
        observedLogoWidths.put(playerId, width);
    }

    private static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            ClassLoader loader = LunarTabLogoCompatibility.class.getClassLoader();
            Class<?> clientClass = Class.forName(CLIENT_CLASS, false, loader);
            Method instanceGetter = clientClass.getDeclaredMethod("CCHHRCIHIRRCOCOHCCCRIHIOIICCRO");
            instanceGetter.setAccessible(true);
            Object client = instanceGetter.invoke(null);
            if (client == null) {
                return;
            }
            Method managerGetter = clientClass.getDeclaredMethod("COCHROOCRHICRRCOIOCORIRCIORIRC");
            managerGetter.setAccessible(true);
            Object manager = managerGetter.invoke(client);
            if (manager == null) {
                return;
            }
            Method width = manager.getClass().getDeclaredMethod(
                    "IIRHIOIOIHRIIROCOCROHCROCORICO", UUID.class);
            width.setAccessible(true);
            logoManager = manager;
            logoWidthMethod = width;
            initializationError = "none";
        } catch (Throwable error) {
            initializationError = error.getClass().getName() + ": " + String.valueOf(error.getMessage());
        }
    }

    static String describeState() {
        ensureInitialized();
        Object manager = logoManager;
        Method width = logoWidthMethod;
        return "available=" + (manager != null && width != null)
                + " observed_uuid_widths=" + observedLogoWidths.size()
                + " manager=" + (manager == null ? "<null>" : manager.getClass().getName())
                + " width_method=" + (width == null ? "<null>" : width.getName())
                + " error=" + initializationError
                + " native_row=" + nativeRowDrawObserved
                + " native_font=" + (lunarFontRuntimeClass == null ? "<null>" : lunarFontRuntimeClass.getName())
                + " native_width=" + (lunarStringWidthMethod == null ? "<null>" : lunarStringWidthMethod.getName())
                + " native_draw=" + (lunarDrawStringMethod == null ? "<null>" : lunarDrawStringMethod.getName())
                + " native_shadow=" + (lunarDrawShadowMethod == null ? "<null>" : lunarDrawShadowMethod.getName())
                + " native_error=" + nativeDrawError
                + " current_profile_source=" + GomodLogoColorSync.describeNativeTabLogoSource();
    }
}
