package me.napim.gomod.features;

/**
 * Per-row More HUD background fill color (RGB). Opacity remains in {@link ConfigManager} *Bg fields.
 */
public final class MoreHudBackgroundColors {

    private MoreHudBackgroundColors() {
    }

    public static int composePixel(int alpha, int rgb24) {
        if (alpha <= 0) {
            return 0;
        }
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb24 & 0xFFFFFF);
    }

    public static int fpsPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudFpsBgColor());
    }

    public static int cpsPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudCpsBgColor());
    }

    public static int pingPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudPingBgColor());
    }

    public static int potionsPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudPotionsBgColor());
    }

    public static int arrowsPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudArrowsBgColor());
    }

    public static int armorStatusPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudArmorStatusBgColor());
    }

    public static int killEffectsPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudKillEffectsBgColor());
    }

    public static int flamePixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudFlameBgColor());
    }

    public static int coordsPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getMoreHudCoordsBgColor());
    }

    public static int blitzKitTimerPixel(int alpha) {
        return composePixel(alpha, ConfigManager.getBlitzKitTimerHudBgColor());
    }
}
