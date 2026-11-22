package me.napim.gomod.util;

/**
 * Prevents double-drawing chest-route glow + checkpoint labels when both {@link me.napim.gomod.mixins.RenderGlobalMixin}
 * and the Forge {@link me.napim.gomod.forge.ForgeEventBridge} fallback run in the same frame.
 */
public final class GomodChestWorldOverlayFrame {
    private static boolean trailDrawn;
    private static boolean checkpointLabelsDrawn;

    private GomodChestWorldOverlayFrame() {
    }

    public static void beginEntityRenderFrame() {
        trailDrawn = false;
        checkpointLabelsDrawn = false;
    }

    public static boolean isTrailAlreadyDrawnThisFrame() {
        return trailDrawn;
    }

    public static void markTrailDrawnThisFrame() {
        trailDrawn = true;
    }

    public static boolean isCheckpointLabelsAlreadyDrawnThisFrame() {
        return checkpointLabelsDrawn;
    }

    public static void markCheckpointLabelsDrawnThisFrame() {
        checkpointLabelsDrawn = true;
    }
}
