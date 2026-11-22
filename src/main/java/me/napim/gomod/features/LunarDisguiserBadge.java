package me.napim.gomod.features;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import me.napim.gomod.util.MinecraftAccountUuid;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Filters a display snapshot only; Lunar's saved profile and logo cache remain intact. */
public final class LunarDisguiserBadge {
    private static final int MAX_NAMETAG_RENDER_STATES = 4096;

    /** Session#getProfile reparses the UUID; native nametag callbacks can ask many times per player/frame. */
    private static UUID getLocalSessionUuid(Minecraft mc) {
        return MinecraftAccountUuid.sessionProfileId(mc);
    }
    // Keys belong to Lunar's per-entity renderer. Values never retain the key or the profile.
    private static final Map<Object, NametagRenderState> NAMETAG_RENDER_STATES = new WeakHashMap<>();
    private static final ClassValue<Optional<NametagLayout>> NAMETAG_LAYOUTS = new ClassValue<Optional<NametagLayout>>() {
        @Override protected Optional<NametagLayout> computeValue(Class<?> type) {
            try { return Optional.of(new NametagLayout(type)); }
            catch (ReflectiveOperationException | RuntimeException ignored) { return Optional.empty(); }
        }
    };
    private static final ClassValue<Optional<Layout>> LAYOUTS = new ClassValue<Optional<Layout>>() {
        @Override protected Optional<Layout> computeValue(Class<?> type) {
            try {
                ValueLayout value = new ValueLayout(type, false);
                return Optional.of(new Layout(value.getters[0], value.getters[1], value.getters[2], value.constructor));
            } catch (ReflectiveOperationException | RuntimeException ignored) { return Optional.empty(); }
        }
    };

    private LunarDisguiserBadge() { }

    public static boolean filterLogoPresence(boolean original, UUID playerId) {
        DisguiserLunarIcon icon = iconPolicy(playerId);
        return icon == DisguiserLunarIcon.LUNAR || icon != DisguiserLunarIcon.NO_LUNAR && original;
    }

    /** Current Lunar caches the profile by identity; equal display copies can reuse its list. */
    public static Object getCachedNametagProfile(Object cached, Object displayed) {
        if (cached == displayed || cached == null || displayed == null || cached.getClass() != displayed.getClass())
            return cached;
        Optional<NametagLayout> layout = NAMETAG_LAYOUTS.get(displayed.getClass());
        if (!layout.isPresent()) return cached;
        try {
            return new NametagRenderState(cached, layout.get()).sameAppearance(
                    new NametagRenderState(displayed, layout.get())) ? displayed : cached;
        } catch (ReflectiveOperationException ignored) { return cached; }
    }

    /** Whether the selected Badges-category item itself should be hidden for this player. */
    public static boolean shouldHidePlayerBadge(UUID playerId) {
        if (playerId == null) return false;
        if (isManualFullLunarDisguise(playerId)) return false;
        // Ordinary /disguiser entries copy the target's skin and name, but keep the source's
        // basic Lunar logo. Strip only the extra Lunar+ overlay and badge while that entry lives.
        if (RemoteDisguiseManager.hasManualDisguiseTarget(playerId)) return true;

        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = getLocalSessionUuid(mc);
        if (playerId.equals(localId) && LocalDisguise.isEnabled()
                && LocalDisguise.getLocalDisguiseLunarIconOverride() != DisguiserLunarIcon.KEEP) {
            return true;
        }
        if (GomodPeerLunarFullAccess.shouldHideLunarFeatures(playerId)) return true;
        return false;
    }

    /** Whether the extra icon slot is hidden; No+ affects the plus glyph, not a selected Badge. */
    public static boolean shouldHideExtraBadge(UUID playerId) {
        if (playerId == null || isManualFullLunarDisguise(playerId)) return false;
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = getLocalSessionUuid(mc);
        if (playerId.equals(localId) && LunarPublicLoginGuard.shouldHideLocalPlus(playerId)) return true;
        if (shouldHidePlayerBadge(playerId) || isLocalNoPlusChoice(playerId, localId)) return true;
        return GomodPeerLunarPlus.hasPeerState(playerId)
                && Boolean.TRUE.equals(GomodPeerLunarPlus.advertisedHidePlus(playerId));
    }

    private static boolean isLocalNoPlusChoice(UUID playerId, UUID localId) {
        return playerId.equals(localId) && ConfigManager.isLunarTweaksEnabled()
                && ConfigManager.isLunarPlusHideBadge();
    }

    public static DisguiserLunarIcon iconPolicy(UUID playerId) {
        if (playerId == null) return DisguiserLunarIcon.KEEP;
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = getLocalSessionUuid(mc);
        if (playerId.equals(localId) && LocalDisguise.isEnabled()) {
            DisguiserLunarIcon local = LocalDisguise.getLocalDisguiseLunarIconOverride();
            if (local != DisguiserLunarIcon.KEEP) return local;
        }
        return RemoteDisguiseManager.getManualLunarIconForPlayer(playerId);
    }

    /** Applies display-only plus and selected-Badge visibility without mutating Lunar's saved profile. */
    public static Object filterLogoForDisplay(Object profile, UUID playerId) {
        return filterLogoForDisplay(profile, playerId, profile == null ? null : profile.getClass());
    }

    /** The native class literal handles an absent original icon using the caller's own loader. */
    public static Object filterLogoForDisplay(Object profile, UUID playerId, Class<?> displayType) {
        return GomodStudioSharing.bindBadgeProfile(LunarConnectionLogo.snapshot(
                filterLogoForDisplayUnbound(profile,playerId,displayType),playerId,displayType),playerId);
    }
    private static Object filterLogoForDisplayUnbound(Object profile, UUID playerId, Class<?> displayType) {
        if (isManualFullLunarDisguise(playerId)) {
            DisguiserLunarIcon fullIcon = iconPolicy(playerId);
            if (fullIcon == DisguiserLunarIcon.NO_LUNAR) return null;
            if (fullIcon == DisguiserLunarIcon.LUNAR) {
                return applyFullDisguiseDisplayColors(profile, playerId, displayType);
            }
            return profile;
        }
        boolean hidePlus = shouldHideExtraBadge(playerId);
        boolean hideBadge = shouldHidePlayerBadge(playerId);
        if (!hidePlus && !hideBadge) return profile;
        DisguiserLunarIcon icon = iconPolicy(playerId);
        if (icon == DisguiserLunarIcon.NO_LUNAR) return null;
        if (profile == null && icon != DisguiserLunarIcon.LUNAR) return null;
        if (displayType == null) return profile;
        if (profile != null && !displayType.isInstance(profile)) return profile;
        if (profile != null && !isTabDisplayProfile(displayType)) {
            Object copy = copyWithoutExtraBadge(profile, displayType, true, hidePlus, hideBadge);
            if (copy != null) return copy;
        }
        Optional<Layout> resolved = LAYOUTS.get(displayType);
        if (profile == null && icon == DisguiserLunarIcon.LUNAR) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.lunar_badge_layout",
                    displayType.getName(), "syntheticDisplayRecord=" + resolved.isPresent());
        }
        if (!resolved.isPresent()) return profile;
        Layout layout = resolved.get();
        try {
            if (profile == null) return layout.constructor.newInstance(Integer.valueOf(-1), Integer.valueOf(0), null);
            int currentPlus = ((Integer) layout.plus.invoke(profile)).intValue();
            Object currentBadge = layout.badge.invoke(profile);
            if ((!hidePlus || currentPlus == 0) && (!hideBadge || currentBadge == null)) {
                return profile;
            }
            return layout.constructor.newInstance(layout.color.invoke(profile),
                    Integer.valueOf(hidePlus ? 0 : currentPlus), hideBadge ? null : currentBadge);
        } catch (ReflectiveOperationException ignored) { return profile; }
    }

    /** The native nametag renderer reads its seven-field record directly, bypassing TAB's cache. */
    public static Object getNametagLogo(Map<?, ?> profiles, Object key, Class<?> displayType) {
        long timer = TabPerformanceDebug.begin();
        try { UUID playerId=key instanceof UUID?(UUID)key:null;return GomodStudioSharing.bindBadgeProfile(
                LunarConnectionLogo.snapshot(getNametagLogoTimed(profiles,key,displayType),playerId,displayType),playerId); }
        finally { TabPerformanceDebug.end(TabPerformanceDebug.Metric.PLAYER_BADGE_LOOKUP, timer); }
    }

    private static Object getNametagLogoTimed(Map<?, ?> profiles, Object key, Class<?> displayType) {
        Object original = profiles.get(key);
        if (!(key instanceof UUID)) {
            LunarNametagDebugCapture.recordNametagColorLookup(false, false, false,
                    original != null, -1, -1, false);
            return original;
        }
        UUID playerId = (UUID) key;
        boolean manualFull = isManualFullLunarDisguise(playerId);
        DisguiserLunarIcon manualIcon = manualFull ? iconPolicy(playerId) : DisguiserLunarIcon.KEEP;
        if (manualFull && manualIcon == DisguiserLunarIcon.NO_LUNAR) return null;
        if (manualFull && manualIcon == DisguiserLunarIcon.LUNAR) {
            Object nativeProfile = GomodLogoColorSync.getNativePlayerLogoProfile(playerId);
            if (nativeProfile != null && isNametagProfile(nativeProfile.getClass())) original = nativeProfile;
            original = applyAdvertisedPeerLogoColors(original, playerId, displayType);
            return forceFullDisguisePlusVisible(original, playerId, displayType);
        }
        original = applyAdvertisedPeerLogoColors(original, playerId, displayType);
        boolean hidePlus = shouldHideExtraBadge(playerId);
        boolean hideBadge = shouldHidePlayerBadge(playerId);
        if (!hidePlus && !hideBadge) return original;
        DisguiserLunarIcon icon = iconPolicy(playerId);
        if (icon == DisguiserLunarIcon.NO_LUNAR) return null;
        if (original == null && icon != DisguiserLunarIcon.LUNAR) return null;
        if (displayType == null || (original != null && !displayType.isInstance(original))) return original;
        if (original != null && !isNametagProfile(displayType)) {
            Object copy = copyWithoutExtraBadge(original, displayType, false, hidePlus, hideBadge);
            if (copy != null) return copy;
        }
        Optional<NametagLayout> resolved = NAMETAG_LAYOUTS.get(displayType);
        LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.nametag_profile_layout",
                displayType.getName(), "resolved=" + resolved.isPresent());
        if (!resolved.isPresent()) return original;
        NametagLayout layout = resolved.get();
        try {
            int currentPlus = original == null ? 0 : ((Number) layout.plus.invoke(original)).intValue();
            Object currentBadge = original == null ? null : layout.badge.invoke(original);
            boolean plusPresent = original != null && (Boolean) layout.flag.invoke(original);
            if (hidePlus) {
                // Lunar's nametag draws a black glyph when the color is zero but this bit
                // remains true. No+ must clear both pieces of state in this display copy.
                plusPresent = false;
            }
            return layout.constructor.newInstance(
                    original == null ? Float.valueOf(1) : layout.red.invoke(original),
                    original == null ? Float.valueOf(1) : layout.green.invoke(original),
                    original == null ? Float.valueOf(1) : layout.blue.invoke(original),
                    icon == DisguiserLunarIcon.LUNAR || (original != null && (Boolean) layout.always.invoke(original)),
                    Integer.valueOf(hidePlus ? 0 : currentPlus),
                    Boolean.valueOf(plusPresent),
                    hideBadge ? null : currentBadge);
        } catch (ReflectiveOperationException ignored) { return original; }
    }

    /** Keep the native player/main-name-line gate; only replace its profile-presence decision. */
    public static boolean containsNametagLogo(Map<?, ?> profiles, Object key, Class<?> displayType) {
        if (!(key instanceof UUID)) return profiles.containsKey(key);
        UUID playerId = (UUID) key;
        if (isManualFullLunarDisguise(playerId)) {
            DisguiserLunarIcon fullIcon = iconPolicy(playerId);
            if (fullIcon == DisguiserLunarIcon.NO_LUNAR) return false;
            if (fullIcon == DisguiserLunarIcon.LUNAR) {
                return getNametagLogo(profiles, key, displayType) != null;
            }
        }
        if (!shouldHideExtraBadge(playerId)) {
            return GomodPeerLunarPlus.advertisedLogoRgb(playerId) >= 0
                    ? getNametagLogo(profiles, key, displayType) != null : profiles.containsKey(key);
        }
        DisguiserLunarIcon icon = iconPolicy(playerId);
        if (icon == DisguiserLunarIcon.NO_LUNAR) return false;
        return icon == DisguiserLunarIcon.LUNAR
                ? getNametagLogo(profiles, key, displayType) != null : profiles.containsKey(key);
    }

    /**
     * Lunar's font renderer compiles logo/+ colors into the cached label, but its native
     * reuse check compares only the text component. Report a cache miss when the final
     * display values change; Lunar then disposes and rebuilds its own display list.
     * Comparing values is essential because getNametagLogo creates a display copy per draw.
     */
    public static Object getCachedNametagComponent(Object component, Object cache, Object displayProfile) {
        if (cache == null) return component;
        NametagRenderState current;
        try {
            if (displayProfile == null) {
                current = NametagRenderState.ABSENT;
            } else {
                Optional<NametagLayout> resolved = NAMETAG_LAYOUTS.get(displayProfile.getClass());
                if (!resolved.isPresent()) return component;
                current = new NametagRenderState(displayProfile, resolved.get());
            }
        } catch (ReflectiveOperationException ignored) { return component; }
        boolean changed;
        synchronized (NAMETAG_RENDER_STATES) {
            NametagRenderState previous = NAMETAG_RENDER_STATES.get(cache);
            changed = previous == null || !previous.sameAppearance(current);
            if (changed) {
                if (NAMETAG_RENDER_STATES.size() >= MAX_NAMETAG_RENDER_STATES) NAMETAG_RENDER_STATES.clear();
                NAMETAG_RENDER_STATES.put(cache, current);
            }
        }
        LunarNametagDebugCapture.recordNametagRenderCache(current.present, current.logoRgb(), current.plus,
                current.always, current.flag, current.badge != null, changed);
        return changed ? null : component;
    }

    /** Opt-in trace at the native label-list compile and call boundaries. */
    public static void recordNametagNativeColorStage(String stage, Object displayProfile) {
        if (!LunarNametagDebugCapture.isActive()) return;
        int selectedLogo = GomodLogoColorSync.getLocalLogoRgbForRelay();
        int selectedPlus = GomodLogoColorSync.getLocalPlusRgbForRelay();
        if (displayProfile == null) {
            LunarNametagDebugCapture.recordNametagRenderStage(stage, -1, 0, selectedLogo, selectedPlus);
            return;
        }
        Optional<NametagLayout> resolved = NAMETAG_LAYOUTS.get(displayProfile.getClass());
        if (!resolved.isPresent()) return;
        try {
            int red = Math.round(((Number) resolved.get().red.invoke(displayProfile)).floatValue() * 255.0F);
            int green = Math.round(((Number) resolved.get().green.invoke(displayProfile)).floatValue() * 255.0F);
            int blue = Math.round(((Number) resolved.get().blue.invoke(displayProfile)).floatValue() * 255.0F);
            int logo = ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
            int plus = ((Number) resolved.get().plus.invoke(displayProfile)).intValue();
            boolean always = (Boolean) resolved.get().always.invoke(displayProfile);
            boolean flag = (Boolean) resolved.get().flag.invoke(displayProfile);
            boolean badgePresent = resolved.get().badge.invoke(displayProfile) != null;
            LunarNametagDebugCapture.recordNametagRenderStage(stage, logo, plus,
                    selectedLogo, selectedPlus);
            LunarNametagDebugCapture.recordNametagRenderFlags(stage, always, flag, badgePresent);
        } catch (ReflectiveOperationException ignored) { }
    }

    /** Draw hook overload; the list ID is intentionally not retained in diagnostics. */
    public static void recordNametagNativeColorStage(int listId, String stage, Object displayProfile) {
        recordNametagNativeColorStage(stage, displayProfile);
    }

    private static boolean isManualFullLunarDisguise(UUID playerId) {
        if (playerId == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = getLocalSessionUuid(mc);
        // /disguisefull stores the copied outfit in LunarFullDisguise, not in the remote
        // disguiser table. Treat the local render as full-disguised so No+ and badge filters
        // do not remove the target's plus from the nametag or TAB display copy.
        if (playerId.equals(localId) && LunarFullDisguise.isActive()) return true;
        return RemoteDisguiseManager.isManualFullLunarDisguiseForPlayer(playerId);
    }

    /** Payload-free diagnostic input for the native TAB plus-draw hook. */
    public static int manualFullDisguisePlusRgb(UUID playerId) {
        if (!isManualFullLunarDisguise(playerId) || iconPolicy(playerId) != DisguiserLunarIcon.LUNAR) return -1;
        int[] colors = GomodLogoColorSync.getNativePlayerLogoColors(playerId);
        return colors == null || colors.length < 2 ? -1 : colors[1] & 0xFFFFFF;
    }

    private static boolean isTabDisplayProfile(Class<?> type) {
        return type != null && LAYOUTS.get(type).isPresent();
    }

    private static boolean isNametagProfile(Class<?> type) {
        return type != null && NAMETAG_LAYOUTS.get(type).isPresent();
    }

    private static Object forceNametagBasicLogo(Object profile, Class<?> displayType) {
        if (displayType == null || (profile != null && !displayType.isInstance(profile))) return profile;
        Optional<NametagLayout> resolved = NAMETAG_LAYOUTS.get(displayType);
        if (!resolved.isPresent()) return profile;
        NametagLayout layout = resolved.get();
        try {
            return layout.constructor.newInstance(
                    profile == null ? Float.valueOf(1.0F) : layout.red.invoke(profile),
                    profile == null ? Float.valueOf(1.0F) : layout.green.invoke(profile),
                    profile == null ? Float.valueOf(1.0F) : layout.blue.invoke(profile),
                    Boolean.TRUE,
                    profile == null ? Integer.valueOf(0) : layout.plus.invoke(profile),
                    profile != null && (Boolean) layout.flag.invoke(profile),
                    profile == null ? null : layout.badge.invoke(profile));
        } catch (ReflectiveOperationException ignored) { return profile; }
    }

    /** Rebuilds a TAB display record from the live disguised profile instead of its stale cache. */
    private static Object applyFullDisguiseDisplayColors(Object profile, UUID playerId, Class<?> displayType) {
        if (displayType == null || (profile != null && !displayType.isInstance(profile))) return profile;
        int[] colors = GomodLogoColorSync.getNativePlayerLogoColors(playerId);
        if (colors == null || colors.length < 2) return profile;
        Optional<Layout> resolved = LAYOUTS.get(displayType);
        if (!resolved.isPresent()) return profile;
        Layout layout = resolved.get();
        try {
            Object nativeProfile = GomodLogoColorSync.getNativePlayerLogoProfile(playerId);
            Object badge = profile == null ? null : layout.badge.invoke(profile);
            if (nativeProfile != null && isNametagProfile(nativeProfile.getClass())) {
                Optional<NametagLayout> nativeLayout = NAMETAG_LAYOUTS.get(nativeProfile.getClass());
                if (nativeLayout.isPresent()) badge = nativeLayout.get().badge.invoke(nativeProfile);
            }
            return layout.constructor.newInstance(Integer.valueOf(colors[0]), Integer.valueOf(colors[1]), badge);
        } catch (ReflectiveOperationException ignored) {
            return profile;
        }
    }

    /** Uses the live disguised profile and sets the nametag plus-presence bit from its color. */
    private static Object forceFullDisguisePlusVisible(Object profile, UUID playerId, Class<?> displayType) {
        if (displayType == null || (profile != null && !displayType.isInstance(profile))) return profile;
        int[] colors = GomodLogoColorSync.getNativePlayerLogoColors(playerId);
        Optional<NametagLayout> resolved = NAMETAG_LAYOUTS.get(displayType);
        if (colors == null || colors.length < 2 || !resolved.isPresent()) return profile;
        NametagLayout layout = resolved.get();
        try {
            Object nativeProfile = GomodLogoColorSync.getNativePlayerLogoProfile(playerId);
            Object source = nativeProfile != null && displayType.isInstance(nativeProfile) ? nativeProfile : profile;
            float red = source == null ? ((colors[0] >> 16) & 0xFF) / 255.0F
                    : ((Number) layout.red.invoke(source)).floatValue();
            float green = source == null ? ((colors[0] >> 8) & 0xFF) / 255.0F
                    : ((Number) layout.green.invoke(source)).floatValue();
            float blue = source == null ? (colors[0] & 0xFF) / 255.0F
                    : ((Number) layout.blue.invoke(source)).floatValue();
            Object badge = source == null ? null : layout.badge.invoke(source);
            boolean flag = colors[1] != 0;
            return layout.constructor.newInstance(Float.valueOf(red), Float.valueOf(green), Float.valueOf(blue),
                    Boolean.TRUE, Integer.valueOf(colors[1] == 0 ? 0 : 0xFF000000 | colors[1]),
                    Boolean.valueOf(flag), badge);
        } catch (ReflectiveOperationException ignored) {
            return profile;
        }
    }

    private static Object applyAdvertisedPeerLogoColors(Object profile, UUID playerId, Class<?> displayType) {
        int logoRgb = GomodPeerLunarPlus.advertisedLogoRgb(playerId);
        int plusRgb = GomodPeerLunarPlus.advertisedPlusRgb(playerId);
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = getLocalSessionUuid(mc);
        boolean localMatch = playerId.equals(localId);
        int localLogoRgb = -1;
        int localPlusRgb = -1;
        if (localMatch) {
            // Lunar's F5 nametag reads a separate cached record from TAB. Resolve the local
            // live player-profile values for this draw so it cannot lag behind TAB's update.
            if (isManualFullLunarDisguise(playerId)) {
                int[] colors = GomodLogoColorSync.getNativePlayerLogoColors(playerId);
                if (colors != null && colors.length >= 2) {
                    localLogoRgb = colors[0];
                    localPlusRgb = colors[1];
                }
            } else {
                localLogoRgb = GomodLogoColorSync.getLocalLogoRgbForRelay();
                localPlusRgb = GomodLogoColorSync.getLocalPlusRgbForRelay();
            }
            if (localLogoRgb >= 0) logoRgb = localLogoRgb;
            if (localPlusRgb >= 0) plusRgb = localPlusRgb;
        }
        if (logoRgb < 0 && plusRgb < 0) {
            LunarNametagDebugCapture.recordNametagColorLookup(true, localId != null, localMatch,
                    profile != null, localLogoRgb, localPlusRgb, false);
            return profile;
        }
        if (displayType == null || (profile != null && !displayType.isInstance(profile))) {
            LunarNametagDebugCapture.recordNametagColorLookup(true, localId != null, localMatch,
                    profile != null, localLogoRgb, localPlusRgb, false);
            return profile;
        }
        Optional<NametagLayout> resolved = NAMETAG_LAYOUTS.get(displayType);
        if (!resolved.isPresent()) {
            LunarNametagDebugCapture.recordNametagColorLookup(true, localId != null, localMatch,
                    profile != null, localLogoRgb, localPlusRgb, false);
            return profile;
        }
        NametagLayout layout = resolved.get();
        try {
            float red = profile == null ? 1.0F : ((Number) layout.red.invoke(profile)).floatValue();
            float green = profile == null ? 1.0F : ((Number) layout.green.invoke(profile)).floatValue();
            float blue = profile == null ? 1.0F : ((Number) layout.blue.invoke(profile)).floatValue();
            int plus = profile == null ? 0 : ((Number) layout.plus.invoke(profile)).intValue();
            boolean flag = profile != null && (Boolean) layout.flag.invoke(profile);
            if (logoRgb >= 0) {
                red = ((logoRgb >> 16) & 0xFF) / 255.0F;
                green = ((logoRgb >> 8) & 0xFF) / 255.0F;
                blue = (logoRgb & 0xFF) / 255.0F;
            }
            // The native font's vertex builder consumes ARGB. TAB/relay selections are RGB;
            // preserve zero as Lunar's no-plus sentinel instead of turning it into opaque black.
            if (plusRgb >= 0) {
                plus = plusRgb == 0 ? 0 : 0xFF000000 | (plusRgb & 0xFFFFFF);
                flag = plusRgb != 0;
            }
            boolean always = profile == null || (Boolean) layout.always.invoke(profile);
            Object badge = profile == null ? null : layout.badge.invoke(profile);
            Object displayCopy = layout.constructor.newInstance(Float.valueOf(red), Float.valueOf(green), Float.valueOf(blue),
                    Boolean.valueOf(always), Integer.valueOf(plus), Boolean.valueOf(flag), badge);
            LunarNametagDebugCapture.recordNametagColorLookup(true, localId != null, localMatch,
                    profile != null, localLogoRgb, localPlusRgb, true);
            return displayCopy;
        } catch (ReflectiveOperationException ignored) {
            LunarNametagDebugCapture.recordNametagColorLookup(true, localId != null, localMatch,
                    profile != null, localLogoRgb, localPlusRgb, false);
            return profile;
        }
    }

    /**
     * New Lunar builds rename the immutable logo records. Copy their canonical value object
     * structurally, clearing only the Lunar+ color/badge while preserving the basic logo fields.
     */
    private static Object copyWithoutExtraBadge(Object profile, Class<?> type, boolean tabProfile,
                                                boolean hidePlus, boolean hideBadge) {
        if (profile == null || type == null || !type.isInstance(profile)) return null;
        try {
            Constructor<?> constructor = null;
            Field[] instanceFields = null;
            int bestScore = Integer.MIN_VALUE;
            for (Constructor<?> candidate : type.getDeclaredConstructors()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                Field[] fields = matchingInstanceFields(type, parameters);
                if (fields == null) continue;
                int ints = 0, floats = 0, booleans = 0, references = 0;
                for (Class<?> parameter : parameters) {
                    if (parameter == Integer.TYPE) ints++;
                    else if (parameter == Float.TYPE) floats++;
                    else if (parameter == Boolean.TYPE) booleans++;
                    else if (!parameter.isPrimitive()) references++;
                }
                boolean shape = tabProfile ? ints >= 2 && references >= 1
                        : ints >= 1 && floats >= 3 && booleans >= 2 && references >= 1;
                if (!shape) continue;
                int score = parameters.length * 4 + (fields.length == parameters.length ? 2 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    constructor = candidate;
                    instanceFields = fields;
                }
            }
            if (constructor == null || instanceFields == null) return null;

            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] values = new Object[parameterTypes.length];
            String[] componentNames = recordComponentNames(type, parameterTypes.length);
            boolean copied = false;
            int intCount = 0;
            for (int i = 0; i < parameterTypes.length; i++) {
                Field field = instanceFields[i];
                field.setAccessible(true);
                values[i] = field.get(profile);
                if (parameterTypes[i] == Integer.TYPE) intCount++;
            }

            int fallbackPlusIndex = -1;
            if (tabProfile && intCount >= 2) {
                int seen = 0;
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (parameterTypes[i] == Integer.TYPE && ++seen == 2) fallbackPlusIndex = i;
                }
            } else if (!tabProfile && intCount >= 1) {
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (parameterTypes[i] == Integer.TYPE) fallbackPlusIndex = i;
                }
            }

            for (int i = 0; i < parameterTypes.length; i++) {
                String name = componentNames[i] == null ? "" : componentNames[i].toLowerCase(java.util.Locale.ROOT);
                Class<?> parameter = parameterTypes[i];
                if (hidePlus && parameter == Integer.TYPE
                        && (name.contains("plus") || i == fallbackPlusIndex)) {
                    values[i] = Integer.valueOf(0);
                    copied = true;
                } else if (hideBadge && parameter == Boolean.TYPE
                        && (name.contains("plus") || name.contains("badge"))) {
                    values[i] = Boolean.FALSE;
                    copied = true;
                } else if (hideBadge && !parameter.isPrimitive() && (name.contains("badge")
                        || parameter.getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("badge")
                        || (i == lastReferenceIndex(parameterTypes) && (tabProfile || intCount == 1)))) {
                    values[i] = null;
                    copied = true;
                }
            }
            if (!copied) return null;
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field[] matchingInstanceFields(Class<?> type, Class<?>[] parameters) {
        Field[] fields = new Field[parameters.length];
        int at = 0;
        for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (at >= fields.length || field.getType() != parameters[at]) return null;
                fields[at++] = field;
            }
        }
        return at == fields.length ? fields : null;
    }

    private static int lastReferenceIndex(Class<?>[] parameters) {
        for (int i = parameters.length - 1; i >= 0; i--) {
            if (!parameters[i].isPrimitive()) return i;
        }
        return -1;
    }

    private static String[] recordComponentNames(Class<?> type, int count) {
        String[] names = new String[count];
        try {
            Method componentsMethod = Class.class.getMethod("getRecordComponents");
            Object components = componentsMethod.invoke(type);
            if (!(components instanceof Object[]) || ((Object[]) components).length != count) return names;
            Object[] array = (Object[]) components;
            for (int i = 0; i < array.length; i++) {
                names[i] = String.valueOf(array[i].getClass().getMethod("getName").invoke(array[i]));
            }
        } catch (Throwable ignored) { }
        return names;
    }

    private static final class NametagRenderState {
        static final NametagRenderState ABSENT = new NametagRenderState();
        final boolean present, always, flag;
        final int red, green, blue, plus;
        final Object badge;
        final String badgeColors;

        private NametagRenderState() {
            present = always = flag = false;
            red = green = blue = plus = 0;
            badge = null;
            badgeColors="";
        }

        NametagRenderState(Object profile, NametagLayout layout) throws ReflectiveOperationException {
            present = true;
            red = Float.floatToIntBits(((Number) layout.red.invoke(profile)).floatValue());
            green = Float.floatToIntBits(((Number) layout.green.invoke(profile)).floatValue());
            blue = Float.floatToIntBits(((Number) layout.blue.invoke(profile)).floatValue());
            always = (Boolean) layout.always.invoke(profile);
            plus = ((Number) layout.plus.invoke(profile)).intValue();
            flag = (Boolean) layout.flag.invoke(profile);
            badge = layout.badge.invoke(profile);
            badgeColors=GomodStudioSharing.badgeSignature(badge);
        }

        boolean sameAppearance(NametagRenderState other) {
            return present == other.present && red == other.red && green == other.green && blue == other.blue
                    && always == other.always && plus == other.plus && flag == other.flag
                    && Objects.equals(badge, other.badge) && badgeColors.equals(other.badgeColors);
        }

        int logoRgb() {
            return present ? (Math.round(Float.intBitsToFloat(red) * 255) & 255) << 16
                    | (Math.round(Float.intBitsToFloat(green) * 255) & 255) << 8
                    | (Math.round(Float.intBitsToFloat(blue) * 255) & 255) : -1;
        }
    }

    private static final class NametagLayout {
        final Method red, green, blue, always, plus, flag, badge;
        final Constructor<?> constructor;
        NametagLayout(Class<?> type) throws ReflectiveOperationException {
            ValueLayout value = new ValueLayout(type, true);
            red = value.getters[0]; green = value.getters[1]; blue = value.getters[2];
            always = value.getters[3]; plus = value.getters[4]; flag = value.getters[5]; badge = value.getters[6];
            constructor = value.constructor;
        }
    }

    /** Resolve value roles from constructor assignments and pure getters once per native class. */
    private static final class ValueLayout {
        final Constructor<?> constructor;
        final Method[] getters;
        ValueLayout(Class<?> type, boolean nametag) throws ReflectiveOperationException {
            if (!type.getName().startsWith("com.moonsworth.lunar."))
                throw new NoSuchMethodException("Not a native Lunar appearance value");
            int count = nametag ? 7 : 3;
            Constructor<?> found = null;
            for (Constructor<?> candidate : type.getConstructors()) {
                Class<?>[] p = candidate.getParameterTypes();
                boolean shape = p.length == count && !p[count - 1].isPrimitive()
                        && (nametag ? p[0] == float.class && p[1] == float.class && p[2] == float.class
                            && p[3] == boolean.class && p[4] == int.class && p[5] == boolean.class
                            : p[0] == int.class && p[1] == int.class);
                if (!shape) continue;
                if (found != null) throw new NoSuchMethodException("Ambiguous Lunar appearance constructor");
                found = candidate;
            }
            if (found == null) throw new NoSuchMethodException("Unknown Lunar appearance constructor");
            constructor = found;
            ClassNode bytes = new ClassNode();
            try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (input == null) throw new NoSuchMethodException("Lunar appearance bytecode unavailable");
                new ClassReader(input).accept(bytes, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (java.io.IOException error) {
                throw new ReflectiveOperationException("Cannot read Lunar appearance layout", error);
            }
            String descriptor = Type.getConstructorDescriptor(constructor);
            MethodNode init = null;
            for (MethodNode method : bytes.methods)
                if ("<init>".equals(method.name) && descriptor.equals(method.desc)) init = method;
            if (init == null) throw new NoSuchMethodException("Missing Lunar appearance constructor bytecode");
            String[] fields = new String[count];
            for (AbstractInsnNode instruction : init.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldInsnNode field = (FieldInsnNode) instruction;
                AbstractInsnNode value = previousCode(instruction), receiver = previousCode(value);
                if (!field.owner.equals(bytes.name) || !(value instanceof VarInsnNode)
                        || !(receiver instanceof VarInsnNode) || receiver.getOpcode() != Opcodes.ALOAD
                        || ((VarInsnNode) receiver).var != 0) continue;
                int role = ((VarInsnNode) value).var - 1;
                if (role < 0 || role >= count || fields[role] != null
                        || !field.desc.equals(Type.getDescriptor(constructor.getParameterTypes()[role])))
                    throw new NoSuchFieldException("Ambiguous Lunar appearance field assignment");
                fields[role] = field.name;
            }
            getters = new Method[count];
            for (int role = 0; role < count; role++) {
                if (fields[role] == null) throw new NoSuchFieldException("Incomplete Lunar appearance field roles");
                String getterDescriptor = "()" + Type.getDescriptor(constructor.getParameterTypes()[role]);
                for (MethodNode method : bytes.methods) {
                    if (!getterDescriptor.equals(method.desc)
                            || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) continue;
                    AbstractInsnNode first = nextCode(method.instructions.getFirst());
                    AbstractInsnNode field = nextCode(first == null ? null : first.getNext());
                    AbstractInsnNode end = nextCode(field == null ? null : field.getNext());
                    if (!(first instanceof VarInsnNode) || first.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) first).var != 0
                            || !(field instanceof FieldInsnNode) || field.getOpcode() != Opcodes.GETFIELD
                            || !((FieldInsnNode) field).owner.equals(bytes.name) || !((FieldInsnNode) field).name.equals(fields[role])
                            || end == null || end.getOpcode() != Type.getType(constructor.getParameterTypes()[role]).getOpcode(Opcodes.IRETURN)
                            || nextCode(end.getNext()) != null) continue;
                    if (getters[role] != null) throw new NoSuchMethodException("Ambiguous Lunar appearance getter");
                    getters[role] = type.getMethod(method.name);
                }
                if (getters[role] == null) throw new NoSuchMethodException("Missing pure Lunar appearance getter");
            }
        }

        private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
            if (instruction == null) return null;
            do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
            return instruction;
        }

        private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
            while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
            return instruction;
        }
    }

    private static final class Layout {
        final Method color, plus, badge;
        final Constructor<?> constructor;
        Layout(Method color, Method plus, Method badge, Constructor<?> constructor) {
            this.color = color; this.plus = plus; this.badge = badge; this.constructor = constructor;
        }
    }
}
