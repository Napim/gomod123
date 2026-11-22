package me.napim.gomod.features;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.mixins.LunarLockerStateBridge;
import net.minecraft.client.Minecraft;

/** Local-only Lunar+ badge visibility, exposed as a synthetic choice in Lunar's native Locker. */
public final class GomodLunarPlusLocker {
    // Lunar's Locker UI treats #000000 as no plus (its plus glyph is omitted). Keep the
    // synthetic choice at that native value; it does not clear a separate selected item in
    // the Badges category. The native selection hook intercepts it before any Lunar API callback.
    public static final int NO_PLUS_COLOR = 0;

    private static volatile Object lockerModel;
    private static volatile String nativePublisher;
    private static volatile boolean refreshWarningLogged;
    private static volatile int lastPublishedColorCount = -1;
    private static volatile int lastPublishedNoPlusSelection = -1;
    private static volatile int lastPublishedActiveColor = Integer.MIN_VALUE;
    private static final String CLOTH_OPTION = "showClothCloaksForOthers";
    private static final Object CLOTH_LOCK = new Object();
    private static final LinkedHashSet<Integer> CLOTH_OPTION_IDS = new LinkedHashSet<Integer>();
    private static final LinkedHashSet<Integer> ACTIVE_LOCAL_CLOAK_IDS = new LinkedHashSet<Integer>();
    private static final LinkedHashMap<Integer, Boolean> LAST_CLOTH_VALUES = new LinkedHashMap<Integer, Boolean>();
    private static volatile Class<?> nativeClothCallbackClass;
    private static volatile String nativeClothUpdateMethod;
    private static String localClothOwner = "";

    private GomodLunarPlusLocker() {
    }

    public static void captureNativePublisher(Object model, String publisher) {
        lockerModel = model;
        nativePublisher = publisher;
    }

    public static boolean isNoPlusSelected() {
        return isFullAccessEnabled() && GomodLogoColorSync.getLocalPlusRgbForRelay() == 0;
    }

    /** The account tile must not draw a cached FullAccess Plus while the relay is connecting. */
    public static boolean isNoPlusSelectedForDisplay() {
        return isNoPlusSelected() || !isFullAccessEnabled()
                && LunarPublicLoginGuard.officialPlusRgbForRestore() <= 0;
    }

    /**
     * Keeps Lunar's Locker checkmark aligned with the account-local FullAccess choice.
     * The nametag already reads this saved choice, while Lunar's native `active` value can
     * lag behind after its color callback updates the profile but not the Locker preference.
     */
    public static int activeColorForLocker(int nativeActiveColor) {
        if (!isFullAccessEnabled()) return nativeActiveColor;
        int selected = GomodLogoColorSync.getLocalPlusRgbForRelay();
        return selected >= 0 && selected <= 0xFFFFFF ? selected : nativeActiveColor;
    }

    public static boolean isFullAccessEnabled() {
        return ConfigManager.isLunarTweaksEnabled()
                && me.napim.gomod.features.LunarOnlineStatus.isFullAccessConnected();
    }

    /** The hook is limited to Lunar's cloth physics option, so always allow that local callback. */
    public static boolean allowClothCloak(boolean nativeAllowed) {
        return true;
    }

    /** Captures Lunar's native callback and makes the Locker checkbox reflect the FullAccess choice. */
    public static String captureNativeClothData(Class<?> owner, String updateMethod,
                                                 String json, Integer[] requestedIds) {
        captureNativeClothCallback(owner, updateMethod);
        if (json == null || json.isEmpty()) return json;
        try {
            JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonArray()) return json;
            JsonArray rows = parsed.getAsJsonArray();
            boolean enabled = ConfigManager.isLunarClothCloakEnabled();
            List<Integer> apply = new ArrayList<Integer>();
            int rowIndex = 0;
            for (JsonElement element : rows) {
                if (!element.isJsonObject()) { rowIndex++; continue; }
                JsonObject row = element.getAsJsonObject();
                JsonElement rawOptions = row.get("options");
                if (rawOptions == null || !rawOptions.isJsonArray()) { rowIndex++; continue; }
                boolean hasClothOption = false;
                for (JsonElement optionElement : rawOptions.getAsJsonArray()) {
                    if (!optionElement.isJsonObject()) continue;
                    JsonObject option = optionElement.getAsJsonObject();
                    JsonElement rawId = option.get("id");
                    if (rawId == null || !rawId.isJsonPrimitive()
                            || !CLOTH_OPTION.equals(rawId.getAsString())) continue;
                    option.addProperty("value", enabled);
                    hasClothOption = true;
                }
                Integer cosmeticId = hasClothOption ? nativeCosmeticId(row) : null;
                if (cosmeticId == null && hasClothOption && requestedIds != null
                        && rowIndex < requestedIds.length) cosmeticId = requestedIds[rowIndex];
                if (cosmeticId != null && cosmeticId.intValue() > 0) {
                    synchronized (CLOTH_LOCK) {
                        rememberBounded(CLOTH_OPTION_IDS, cosmeticId);
                        if (!Boolean.valueOf(enabled).equals(LAST_CLOTH_VALUES.get(cosmeticId))) {
                            apply.add(cosmeticId);
                        }
                    }
                }
                rowIndex++;
            }
            for (Integer id : apply) scheduleNativeClothUpdate(id);
            return rows.toString();
        } catch (Throwable ignored) {
            // Keep Lunar's original response intact if a future version changes its JSON shape.
            return json;
        }
    }

    /** Makes the single-cosmetic settings panel use the same saved value as the catalogue and FullAccess toggle. */
    public static String captureNativeClothDetailData(Class<?> owner, String updateMethod,
                                                       String json, Integer requestedId) {
        captureNativeClothCallback(owner, updateMethod);
        if (json == null || json.isEmpty()) return json;
        try {
            JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonObject()) return json;
            JsonObject row = parsed.getAsJsonObject();
            JsonElement rawOptions = row.get("options");
            if (rawOptions == null || !rawOptions.isJsonArray()) return json;

            boolean enabled = ConfigManager.isLunarClothCloakEnabled();
            boolean hasClothOption = false;
            for (JsonElement optionElement : rawOptions.getAsJsonArray()) {
                if (!optionElement.isJsonObject()) continue;
                JsonObject option = optionElement.getAsJsonObject();
                JsonElement rawId = option.get("id");
                if (rawId == null || !rawId.isJsonPrimitive()
                        || !CLOTH_OPTION.equals(rawId.getAsString())) continue;
                option.addProperty("value", enabled);
                hasClothOption = true;
            }
            if (!hasClothOption) return json;

            Integer cosmeticId = nativeCosmeticId(row);
            if (cosmeticId == null) cosmeticId = requestedId;
            if (cosmeticId != null && cosmeticId.intValue() > 0) {
                boolean apply = false;
                synchronized (CLOTH_LOCK) {
                    rememberBounded(CLOTH_OPTION_IDS, cosmeticId);
                    if (!Boolean.valueOf(enabled).equals(LAST_CLOTH_VALUES.get(cosmeticId))) {
                        apply = true;
                    }
                }
                if (apply) scheduleNativeClothUpdate(cosmeticId);
            }
            return row.toString();
        } catch (Throwable ignored) {
            return json;
        }
    }

    /** Keeps Lunar's Locker checkbox and the FullAccess setting on the same saved value. */
    public static String nativeClothOptionValue(Class<?> owner, String updateMethod, Integer id,
                                                 String optionId, String requestedValue) {
        captureNativeClothCallback(owner, updateMethod);
        if (!CLOTH_OPTION.equals(optionId)) return requestedValue;
        if (requestedValue == null
                || (!"true".equalsIgnoreCase(requestedValue)
                && !"false".equalsIgnoreCase(requestedValue))) {
            return requestedValue;
        }

        boolean requested = Boolean.parseBoolean(requestedValue);
        boolean preferenceChanged = ConfigManager.isLunarClothCloakEnabled() != requested;
        if (preferenceChanged) {
            // A click in Lunar's native Locker is an equal peer to the FullAccess toggle.
            ConfigManager.setLunarClothCloakEnabled(requested);
        }

        List<Integer> apply = new ArrayList<Integer>();
        synchronized (CLOTH_LOCK) {
            if (id != null && id.intValue() > 0) {
                rememberBounded(CLOTH_OPTION_IDS, id);
                LAST_CLOTH_VALUES.put(id, Boolean.valueOf(requested));
            }
            if (preferenceChanged) {
                // The FullAccess preference is shared by Locker rows. Update every known native option,
                // including rows whose cloak has not rendered in this frame or is currently unequipped.
                for (Integer knownId : CLOTH_OPTION_IDS) {
                    if (id != null && id.equals(knownId)) continue;
                    LAST_CLOTH_VALUES.remove(knownId);
                    apply.add(knownId);
                }
            }
        }
        for (Integer wornId : apply) scheduleNativeClothUpdate(wornId);
        return Boolean.toString(requested);
    }

    /** Reapply the shared choice to every known native Cloth Cloak row as soon as the toggle changes. */
    public static void onClothCloakPreferenceChanged() {
        List<Integer> ids = new ArrayList<Integer>();
        boolean callbackAvailable = nativeClothCallbackClass != null
                && nativeClothUpdateMethod != null && !nativeClothUpdateMethod.isEmpty();
        synchronized (CLOTH_LOCK) {
            for (Integer id : CLOTH_OPTION_IDS) {
                LAST_CLOTH_VALUES.remove(id);
                ids.add(id);
            }
        }
        LunarConnectionDiagnostics.record("lunar.cloth_cloak.preference_changed",
                "enabled=" + ConfigManager.isLunarClothCloakEnabled() + " knownOptions=" + ids.size()
                        + " activeWorn=" + ACTIVE_LOCAL_CLOAK_IDS.size()
                        + " callbackAvailable=" + callbackAvailable);
        for (Integer id : ids) scheduleNativeClothUpdate(id);
    }

    /** Reconciles the real local player's currently rendered cloak set after each native render pass. */
    public static void updateLocalWornClothCloaks(String owner, Iterable<Integer> wornIds) {
        if (owner == null || owner.isEmpty() || wornIds == null) return;
        List<Integer> apply = new ArrayList<Integer>();
        boolean enabled = ConfigManager.isLunarClothCloakEnabled();
        synchronized (CLOTH_LOCK) {
            if (!owner.equals(localClothOwner)) {
                localClothOwner = owner;
                ACTIVE_LOCAL_CLOAK_IDS.clear();
                LAST_CLOTH_VALUES.clear();
            }
            LinkedHashSet<Integer> previouslyWorn = new LinkedHashSet<Integer>(ACTIVE_LOCAL_CLOAK_IDS);
            ACTIVE_LOCAL_CLOAK_IDS.clear();
            for (Integer id : wornIds) {
                if (id == null || id.intValue() <= 0) continue;
                Integer key = Integer.valueOf(id.intValue());
                rememberBounded(ACTIVE_LOCAL_CLOAK_IDS, key);
                if (!previouslyWorn.contains(key)) LAST_CLOTH_VALUES.remove(key);
                if (CLOTH_OPTION_IDS.contains(key)
                        && !Boolean.valueOf(enabled).equals(LAST_CLOTH_VALUES.get(key))) {
                    apply.add(key);
                }
            }
        }
        for (Integer id : apply) scheduleNativeClothUpdate(id);
    }

    public static void captureNativeClothCallback(Class<?> owner, String method) {
        if (owner == null || method == null || method.isEmpty()) return;
        nativeClothCallbackClass = owner;
        nativeClothUpdateMethod = method;
        LunarConnectionDiagnostics.recordOnce("lunar.cloth_cloak.callback_registered",
                owner.getName() + ":" + method,
                "class=" + owner.getName() + " methodCaptured=true");
    }

    /** Called only by Lunar's cloth renderer after it selects one of the local player's cloth items. */
    public static void rememberLocalClothCloak(int id) {
        if (id <= 0) return;
        synchronized (CLOTH_LOCK) {
            rememberBounded(CLOTH_OPTION_IDS, Integer.valueOf(id));
        }
        LunarConnectionDiagnostics.recordOnce("lunar.cloth_cloak.local_item_discovered", Integer.toString(id),
                "id=" + id + " source=native_cloth_render");
    }

    private static Integer nativeCosmeticId(JsonObject row) {
        try {
            JsonElement cosmetic = row.get("cosmetic");
            JsonElement id = cosmetic != null && cosmetic.isJsonObject()
                    ? cosmetic.getAsJsonObject().get("id") : row.get("id");
            if (id == null || !id.isJsonPrimitive()) return null;
            long value = id.getAsLong();
            return value > 0L && value <= Integer.MAX_VALUE ? Integer.valueOf((int) value) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void rememberBounded(LinkedHashSet<Integer> values, Integer id) {
        values.remove(id);
        values.add(id);
        while (values.size() > 256) {
            Iterator<Integer> oldest = values.iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    private static void scheduleNativeClothUpdate(final Integer id) {
        if (id == null) return;
        final Class<?> owner = nativeClothCallbackClass;
        final String methodName = nativeClothUpdateMethod;
        if (owner == null || methodName == null || methodName.isEmpty()) {
            LunarConnectionDiagnostics.recordOnce("lunar.cloth_cloak.setting_update_skipped",id+":native_callback_not_captured",
                    "id=" + id + " reason=native_callback_not_captured");
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            LunarConnectionDiagnostics.record("lunar.cloth_cloak.setting_update_skipped",
                    "id=" + id + " reason=minecraft_unavailable");
            return;
        }
        LunarConnectionDiagnostics.record("lunar.cloth_cloak.setting_update_scheduled",
                "id=" + id + " enabled=" + ConfigManager.isLunarClothCloakEnabled());
        mc.addScheduledTask(new Runnable() {
            @Override public void run() {
                final boolean enabled = ConfigManager.isLunarClothCloakEnabled();
                synchronized (CLOTH_LOCK) {
                    if (nativeClothCallbackClass != owner || !methodName.equals(nativeClothUpdateMethod)
                            || !CLOTH_OPTION_IDS.contains(id)) {
                        LunarConnectionDiagnostics.record("lunar.cloth_cloak.setting_update_skipped",
                                "id=" + id + " reason=callback_or_option_changed");
                        return;
                    }
                    if (Boolean.valueOf(enabled).equals(LAST_CLOTH_VALUES.get(id))) {
                        LunarConnectionDiagnostics.record("lunar.cloth_cloak.setting_update_skipped",
                                "id=" + id + " reason=already_current enabled=" + enabled);
                        return;
                    }
                }
                try {
                    Method callback = owner.getDeclaredMethod(methodName,
                            Integer.class, String.class, String.class);
                    callback.setAccessible(true);
                    callback.invoke(null, id, CLOTH_OPTION, Boolean.toString(enabled));
                    LunarConnectionDiagnostics.record("lunar.cloth_cloak.setting_update",
                            "id=" + id + " enabled=" + enabled + " result=callback_invoked");
                } catch (Throwable failure) {
                    LunarConnectionDiagnostics.recordOnce("lunar.cloth_cloak.setting_update_failed",
                            owner.getName() + ":" + failure.getClass().getName(),
                            "callback=unavailable exception=" + failure.getClass().getSimpleName());
                }
            }
        });
    }

    /** Rebuilds the visible synthetic choice when the FullAccess preference changes. */
    public static void onFullAccessPreferenceChanged() {
        refreshNativeLockerState();
    }

    /** Hides FullAccess-only Locker state and restores native colors as soon as the relay drops. */
    public static void onFullAccessConnectionChanged(final boolean connected) {
        GomodLogoColorSync.onFullAccessConnectionChanged(connected);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            refreshNativeLockerState();
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                refreshNativeLockerState();
            }
        });
    }

    /** Compatibility entry point for inactive legacy Mixins; live capture happens at the publisher. */
    public static void onLockerModelAvailable(Object model, Object response) {
        if (model != null) lockerModel = model;
    }

    /** Records only the rendered option count; never records response contents or account data. */
    public static void recordPublishedAvailableColorCount(int count) {
        if (lastPublishedColorCount == count) return;
        lastPublishedColorCount = count;
        LunarConnectionDiagnostics.record("lunar_plus.locker_state",
                "publishedAvailableColorCount=" + count
                        + " noPlusIndex=0 noPlusColor=" + NO_PLUS_COLOR);
    }

    public static void recordPublishedNoPlusSelection(boolean selected, int activeColor) {
        int state = selected ? 1 : 0;
        activeColor &= 0xFFFFFF;
        if (lastPublishedNoPlusSelection == state && lastPublishedActiveColor == activeColor) return;
        lastPublishedNoPlusSelection = state;
        lastPublishedActiveColor = activeColor;
        LunarConnectionDiagnostics.record("lunar_plus.selection_state",
                "noPlusSelected=" + selected + " activeColor="
                        + String.format(java.util.Locale.ROOT, "%06X", activeColor));
    }

    /** Pure, defensive normalization shared with the native publisher and offline regression checks. */
    public static JsonArray withNoPlusChoice(JsonArray nativeColors, boolean fullAccessEnabled) {
        JsonArray result = new JsonArray();
        if (fullAccessEnabled) {
            JsonObject noPlus = new JsonObject();
            noPlus.addProperty("color", NO_PLUS_COLOR);
            noPlus.addProperty("name", "No +");
            noPlus.addProperty("index", 0);
            result.add(noPlus);
        }
        for (JsonElement element : nativeColors) {
            if (element.isJsonObject()) {
                JsonObject row = element.getAsJsonObject();
                if ((row.has("color") && row.get("color").isJsonPrimitive()
                        && row.get("color").getAsInt() == NO_PLUS_COLOR)
                        || (row.has("name") && row.get("name").isJsonPrimitive()
                        && "No +".equalsIgnoreCase(row.get("name").getAsString()))) {
                    continue;
                }
            }
            JsonElement copy = element.deepCopy();
            if (copy.isJsonObject()) copy.getAsJsonObject().addProperty("index", result.size());
            result.add(copy);
        }
        return result;
    }

    /** Called only for the Locker's synthetic zero entry; it is never sent to Lunar. */
    public static void selectNoPlus() {
        if (!isFullAccessEnabled()) return;
        LunarNametagDebugCapture.recordColorFlow("plus_selection_handler", "plus", NO_PLUS_COLOR,
                "synthetic_no_plus");
        ConfigManager.setLunarPlusHideBadge(true);
        GomodLogoColorSync.rememberLocalLunarPlusColor(NO_PLUS_COLOR);
        GomodLogoColorSync.refreshLocalLunarPlusVisibility();
        // The Locker's right-side mannequin reads Lunar's player-profile cache, not only the
        // badge-visibility preference. Apply the native zero color through the same local-only
        // profile path used for real colors so its preview changes immediately as well.
        GomodLogoColorSync.applyLocalLunarPlusColor(NO_PLUS_COLOR);
        // The main-menu account tile reads Lunar's separate local lunarPlusColor preference.
        // Keep that cache in sync too; this is a local store write and never calls Lunar's RPC.
        GomodLogoColorSync.applyLocalLunarPlusAccountColor(NO_PLUS_COLOR);
        refreshNativeLockerState();
        requestPeerStateRefresh();
    }

    /** Selecting a real Lunar color restores the local badge and updates its preview cache. */
    public static void selectLunarColor(int color) {
        if (!isFullAccessEnabled()) return;
        color &= 0xFFFFFF; // Native palettes may encode white as signed ARGB (-1).
        LunarNametagDebugCapture.recordColorSelection("plus", color);
        if (ConfigManager.isLunarPlusHideBadge()) {
            ConfigManager.setLunarPlusHideBadge(false);
        }
        GomodLogoColorSync.rememberLocalLunarPlusColor(color);
        LunarConnectionDiagnostics.record("lunar_plus.local_color_selected",
                "rgb=" + String.format(java.util.Locale.ROOT, "%06X", color & 0xFFFFFF)
                        + " source=locker-selection-callback");
        GomodLogoColorSync.applyLocalLunarPlusColor(color);
        refreshNativeLockerState();
        requestPeerStateRefresh();
        LunarNametagDebugCapture.recordColorFlow("plus_selection_handler", "plus", color,
                "native_refresh_queued");
    }

    /** Publish a changed visible badge preference at the next relay poll instead of waiting. */
    private static void requestPeerStateRefresh() {
        if (!ConfigManager.isLunarTweaksShowToOtherPlayers()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (Main.getGlobalChatClient() != null) {
                    Main.getGlobalChatClient().requestImmediatePoll();
                }
            }
        });
    }

    /** Re-publishes active color so the native Locker marks “No +” as equipped. */
    public static void refreshNativeLockerState() {
        Object model = lockerModel;
        if (model == null) return;
        try {
            String publisher = nativePublisher;
            if (publisher == null) throw new NoSuchMethodException("Lunar+ native publisher has not been captured");
            invokeNoArgs(model, publisher);
            refreshWarningLogged = false;
        } catch (Throwable error) {
            logRefreshFailure(error);
        }
    }

    /** Republish the live model after the Locker route opens so a newly mounted panel gets its color list. */
    public static void onLockerUiOpened() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            refreshNativeLockerState();
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                LunarConnectionDiagnostics.record("lunar_plus.ui_refresh", "reason=locker-route-open");
                int republishedColorCount = LunarLockerStateBridge.republishAvailableColorsForUiRefresh();
                if (republishedColorCount >= 0) {
                    LunarConnectionDiagnostics.record("lunar_plus.ui_refresh_publish",
                            "activeSelectionChanged=false republishedColorCount="
                                    + republishedColorCount + " refreshMode=available-colors-only");
                } else {
                    refreshNativeLockerState();
                    LunarConnectionDiagnostics.record("lunar_plus.ui_refresh_publish",
                            "activeSelectionTemporarilyChanged=false fallback=model-refresh");
                }
            }
        });
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static void logRefreshFailure(Throwable error) {
        if (refreshWarningLogged) return;
        refreshWarningLogged = true;
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        System.err.println("[gomod] Could not refresh Lunar+ Locker selection: "
                + cause.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }
}
