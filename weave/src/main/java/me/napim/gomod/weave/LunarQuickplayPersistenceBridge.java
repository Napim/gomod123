package me.napim.gomod.weave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Runtime bridge ensuring Lunar's Quickplay per-game keybinds and favorites mark config dirty
 * when edited and always serialize into {@code mods.json}, even before Quickplay's remote
 * game list has loaded.
 */
public final class LunarQuickplayPersistenceBridge {
    private static final Map<Object, Map<?, ?>> quickplayKeybindsByMod =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile String optionSaveMethodName;

    private LunarQuickplayPersistenceBridge() { }

    public static void registerOptionSaveMethod(String methodName) {
        if (methodName != null && !methodName.isEmpty()) {
            optionSaveMethodName = methodName;
        }
    }

    public static void registerQuickplayInstance(Object mod, Object keybindsMap) {
        if (mod != null && keybindsMap instanceof Map<?, ?>) {
            quickplayKeybindsByMod.put(mod, (Map<?, ?>) keybindsMap);
        }
    }

    /**
     * Ensures {@code qpKeyBinds} and {@code qpFavorites} in {@code json} include all loaded or
     * modified entries from Quickplay's internal map and favorites set even when
     * {@code gameList} is null or empty.
     */
    public static void persistExtraQuickplayState(Object mod, JsonObject json,
                                                  Object keybindsObj, Object favoritesObj) {
        registerQuickplayInstance(mod, keybindsObj);
        if (json == null) return;

        if (keybindsObj instanceof Map<?, ?>) {
            Map<?, ?> keybinds = (Map<?, ?>) keybindsObj;
            if (!keybinds.isEmpty()) {
                JsonObject bindsJson = json.has("qpKeyBinds") && json.get("qpKeyBinds").isJsonObject()
                        ? json.getAsJsonObject("qpKeyBinds")
                        : new JsonObject();
                for (Map.Entry<?, ?> entry : keybinds.entrySet()) {
                    if (!(entry.getKey() instanceof String) || entry.getValue() == null) continue;
                    String gameKey = (String) entry.getKey();
                    if (bindsJson.has(gameKey)) continue;
                    JsonObject optionJson = new JsonObject();
                    if (invokeOptionSave(entry.getValue(), optionJson)) {
                        bindsJson.add(gameKey, optionJson);
                    }
                }
                if (bindsJson.size() > 0 || !json.has("qpKeyBinds")) {
                    json.add("qpKeyBinds", bindsJson);
                }
            }
        }

        if (favoritesObj instanceof Set<?>) {
            Set<?> favorites = (Set<?>) favoritesObj;
            if (!favorites.isEmpty() && (!json.has("qpFavorites") || !json.get("qpFavorites").isJsonArray())) {
                JsonArray favArray = new JsonArray();
                for (Object fav : favorites) {
                    if (fav instanceof String) {
                        favArray.add(new JsonPrimitive((String) fav));
                    }
                }
                json.add("qpFavorites", favArray);
            }
        }
    }

    /**
     * Invoked at every return of Lunar's recursive mod-option dirty check so changing a
     * Quickplay per-game keybind marks {@code mods.json} dirty just like a standard mod option.
     */
    public static boolean onModOptionDirty(boolean alreadyHandled, Object mod, Object option,
                                           Object configFile, String configSaveMethodName) {
        if (alreadyHandled) return true;
        if (mod == null || option == null || configFile == null || configSaveMethodName == null) {
            return false;
        }
        Map<?, ?> keybinds = quickplayKeybindsByMod.get(mod);
        if (keybinds == null || keybinds.isEmpty()) {
            return false;
        }
        boolean matches;
        synchronized (keybinds) {
            matches = keybinds.containsValue(option);
        }
        if (!matches) return false;

        try {
            Method saveMethod = findZeroArgMethod(configFile.getClass(), configSaveMethodName);
            if (saveMethod == null) return false;
            saveMethod.setAccessible(true);
            saveMethod.invoke(configFile);
            return true;
        } catch (Throwable failure) {
            System.err.println("[gomod] Failed to mark Quickplay keybind config dirty: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
    }

    private static boolean invokeOptionSave(Object option, JsonObject target) {
        Method save = resolveOptionSaveMethod(option.getClass());
        if (save == null) return false;
        try {
            save.setAccessible(true);
            save.invoke(option, target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method resolveOptionSaveMethod(Class<?> optionClass) {
        String configured = optionSaveMethodName;
        if (configured != null) {
            Method m = findJsonObjectMethod(optionClass, configured);
            if (m != null) return m;
        }
        for (Class<?> current = optionClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == void.class
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == JsonObject.class
                        && !"load".equals(method.getName())) {
                    optionSaveMethodName = method.getName();
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findJsonObjectMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, JsonObject.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Method findZeroArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    static void resetTestState() {
        quickplayKeybindsByMod.clear();
        optionSaveMethodName = null;
    }
}
