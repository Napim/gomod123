package me.napim.gomod.mixins;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.napim.gomod.features.GomodLunarPlusLocker;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Invokes Lunar's obfuscated locker-state setter without Mixin's generated Args helper. */
public final class LunarLockerStateBridge {
    private static final ConcurrentMap<Class<?>, Method> SETTERS = new ConcurrentHashMap<Class<?>, Method>();
    private static volatile Object lunarPlusStateOwner;
    private static volatile JsonArray lastPublishedAvailableColors;

    private LunarLockerStateBridge() {
    }

    /** Setter name comes from the verified native publisher call site in this exact runtime. */
    public static void publish(Object owner, String key, Object value, String nativeSetter) {
        try {
            Method setter = owner.getClass().getMethod(nativeSetter, String.class, Object.class);
            if (setter.getReturnType() != Void.TYPE || java.lang.reflect.Modifier.isStatic(setter.getModifiers()))
                throw new NoSuchMethodException("Unsupported Lunar Locker state setter");
            setter.setAccessible(true);
            SETTERS.put(owner.getClass(), setter);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Verified Lunar Locker state setter was unavailable", error);
        }
        boolean fullAccess = GomodLunarPlusLocker.isFullAccessEnabled();
        boolean hidePlus = GomodLunarPlusLocker.isNoPlusSelectedForDisplay();
        if ("lunarPlus".equals(key) && value instanceof JsonObject && (fullAccess || hidePlus)) {
            JsonObject copy = ((JsonObject) value).deepCopy();
            if (fullAccess) copy.addProperty("isLunarPlus", true);
            copy.addProperty("gomodHidePlus", hidePlus);
            if (hidePlus) {
                copy.addProperty("plusColor", "#000000");
            } else if (fullAccess) {
                int selectedPlus = GomodLunarPlusLocker.activeColorForLocker(0);
                if (selectedPlus > 0) {
                    copy.addProperty("plusColor",
                            String.format(java.util.Locale.ROOT, "#%06X", selectedPlus & 0xFFFFFF));
                }
            }
            value = copy;
        }
        if (fullAccess) {
            if ("active".equals(key)) {
                boolean hidden = GomodLunarPlusLocker.isNoPlusSelected();
                if (value instanceof Number) {
                    int nativeActive = ((Number) value).intValue();
                    int activeColor = hidden ? GomodLunarPlusLocker.NO_PLUS_COLOR
                            : GomodLunarPlusLocker.activeColorForLocker(nativeActive);
                    value = Integer.valueOf(activeColor);
                    GomodLunarPlusLocker.recordPublishedNoPlusSelection(hidden, activeColor);
                } else if (hidden) {
                    value = Integer.valueOf(GomodLunarPlusLocker.NO_PLUS_COLOR);
                    GomodLunarPlusLocker.recordPublishedNoPlusSelection(hidden,
                            GomodLunarPlusLocker.NO_PLUS_COLOR);
                }
            }
            if ("availableColors".equals(key) && value instanceof JsonArray) {
                value = GomodLunarPlusLocker.withNoPlusChoice((JsonArray) value, true);
                GomodLunarPlusLocker.recordPublishedAvailableColorCount(((JsonArray) value).size());
            }
        }
        set(owner, key, value);
    }

    public static void set(Object stateOwner, String key, Object value) {
        if (stateOwner == null) {
            throw new IllegalStateException("Lunar Locker state owner was null");
        }
        Method setter = SETTERS.get(stateOwner.getClass());
        if (setter == null) {
            throw new IllegalStateException("Lunar Locker setter has not been captured from its native publisher");
        }

        try {
            setter.invoke(stateOwner, key, value);
            if ("availableColors".equals(key)) {
                lunarPlusStateOwner = stateOwner;
                lastPublishedAvailableColors = value instanceof JsonArray
                        ? ((JsonArray) value).deepCopy() : null;
            }
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Lunar Locker state update failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not invoke Lunar Locker state setter", error);
        }
    }

    /** Re-publishes the option list without changing the user's selected color. */
    public static int republishAvailableColorsForUiRefresh() {
        Object stateOwner = lunarPlusStateOwner;
        if (stateOwner == null) {
            return -1;
        }
        try {
            JsonArray colors = lastPublishedAvailableColors;
            if (colors == null) return -1;
            if (GomodLunarPlusLocker.isFullAccessEnabled())
                colors = GomodLunarPlusLocker.withNoPlusChoice(colors, true);
            // A fresh array emits a state update to the newly mounted Locker. Never pulse
            // `active`: that briefly renders Lunar's black/default plus and can disturb No+.
            set(stateOwner, "availableColors", colors.deepCopy());
            return colors.size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

}
