package me.napim.gomod.features;

import java.util.UUID;

/**
 * Neutral Forge bridge for access to Lunar's native color services and profile caches.
 * No Lunar service or profile records exist in the Forge runtime.
 */
public final class LunarColorRuntime {
    private LunarColorRuntime() {}

    public static Object cosmeticService() {
        return null;
    }

    public static Object cosmeticSocket() {
        return null;
    }

    public static void setAccountPlus(UUID playerId, int rgb) {}

    public static Object profileMap(Object manager) {
        return null;
    }

    public static Object tabCache(Object manager) {
        return null;
    }

    public static Object recordValue(Object record, int fieldIndex, int expectedFieldCount) {
        return null;
    }

    public static int profileRgb(Object record) {
        return -1;
    }

    public static void applyProfilePush(Object manager, UUID playerId, Class<?> pushClass,
                                        Object push, int badgeId) {}

    public static Object tabRecord(Object manager, UUID playerId) {
        return null;
    }

    public static void requestTabRecord(Object manager, UUID playerId) {}
}
