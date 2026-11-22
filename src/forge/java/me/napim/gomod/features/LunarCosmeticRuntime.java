package me.napim.gomod.features;

/**
 * Forge-side compile bridge for helpers implemented by the native Lunar/Weave runtime.
 * Forge has no Lunar companion definitions or immutable render frames to inspect or replace.
 */
public final class LunarCosmeticRuntime {
    private LunarCosmeticRuntime() {}

    public static long id(Object item) {
        return 0L;
    }

    public static String category(Object item) {
        return "";
    }

    public static double[] framePosition(Object frame) {
        return null;
    }

    public static Object copyFrame(Object frame, double x, double y, double z, float yaw) {
        return frame;
    }
}
