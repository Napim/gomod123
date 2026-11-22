package me.napim.gomod.features;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Cache both present and absent bridge getters; native player values are always read live. */
public final class LunarPlayerBridgeMethods {
    private static final String[] NAMES = {"bridge$isSelf", "bridge$getUniqueID", "bridge$getName", "getName",
            "bridge$getUsername", "getUsername", "getDummyPlayerType"};
    private static final ClassValue<Map<String, Method>> METHODS = new MethodCache();

    private LunarPlayerBridgeMethods() { }

    public static Method find(Class<?> type, String name) {
        return METHODS.get(type).get(name);
    }

    private static final class MethodCache extends ClassValue<Map<String, Method>> {
        @Override protected Map<String, Method> computeValue(Class<?> type) {
            Map<String, Method> methods = new HashMap<String, Method>();
            for (String name : NAMES) {
                try { methods.put(name, type.getMethod(name)); }
                catch (Throwable unavailable) { methods.put(name, null); }
            }
            return Collections.unmodifiableMap(methods);
        }
    }
}
