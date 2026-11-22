package me.napim.gomod.gui;

/**
 * Easing helpers for the gomod Menu Zoom Pop open/close animation.
 */
public class MenuAnimationManager {

    /**
     * Ease-out with slight overshoot (back easing).
     * Used for open: scale reaches ~1.03 then settles at 1.0.
     */
    public static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float tm1 = t - 1f;
        return 1f + c3 * tm1 * tm1 * tm1 + c1 * tm1 * tm1;
    }

    /**
     * Ease-in-back -- accelerates behind then snaps off screen.
     * Used for close: menu quickly shrinks away.
     */
    public static float easeInBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return c3 * t * t * t - c1 * t * t;
    }

    /** Clamps a float to [0, 1]. */
    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
