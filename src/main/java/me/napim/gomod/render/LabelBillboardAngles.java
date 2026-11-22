package me.napim.gomod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;

/**
 * Billboard angles for in-world name labels drawn outside vanilla {@code Render#renderLivingLabel}.
 * {@link RenderManager#playerViewX} / {@code playerViewY} match the camera for most cases; front
 * third-person ({@code GameSettings#thirdPersonView} {@code == 2}) needs the X rotation negated so
 * labels stay square when pitching the view.
 */
public final class LabelBillboardAngles {

    private LabelBillboardAngles() {}

    /**
     * Pitch (degrees) for {@code GlStateManager.rotate(angle, 1, 0, 0)} after yaw, mirroring vanilla
     * nameplate billboarding for the current camera mode.
     */
    public static float entityLabelPitchX(RenderManager renderManager, Minecraft mc) {
        if (renderManager == null) {
            return 0.0F;
        }
        float pitch = renderManager.playerViewX;
        try {
            if (mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView == 2) {
                return -pitch;
            }
        } catch (Throwable ignored) {
        }
        return pitch;
    }
}
