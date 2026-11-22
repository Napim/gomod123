package me.napim.gomod.features;

/** No-op Forge bridge for the native Lunar+ Locker integration. */
public final class GomodLunarPlusLocker {
    private GomodLunarPlusLocker() {}

    public static void refreshNativeLockerState() {}

    public static void onFullAccessPreferenceChanged() {}

    public static void onClothCloakPreferenceChanged() {}
}
