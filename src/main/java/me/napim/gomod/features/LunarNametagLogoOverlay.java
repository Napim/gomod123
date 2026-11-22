package me.napim.gomod.features;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;

/**
 * Compatibility hook for the former Gomod-drawn nametag logo.
 *
 * <p>Lunar's native nametag renderer owns this feature: it uses the current
 * per-player profile, correct logo tint, GUI-scale texture, and Lunar+ badge.
 * Drawing a second bundled 16x16 texture here creates the low-quality duplicate
 * seen when FullAccess profile pushes arrive, so this hook intentionally draws
 * nothing and leaves the native renderer as the sole source.</p>
 */
public final class LunarNametagLogoOverlay {
    private LunarNametagLogoOverlay() {
    }

    public static void render(AbstractClientPlayer player, Render render, double x, double footY, double z,
            String visibleLabel) {
        LunarNametagDebugCapture.recordRenderOutcome("native_lunar_renderer_owns_logo");
    }
}
