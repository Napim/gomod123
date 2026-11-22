package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * HUD editor center snap guides (vertical + horizontal crosshair), optionally shown everywhere.
 */
public final class HudEditorCenterGuides {

    private static boolean renderingCrosshair;

    public HudEditorCenterGuides() {
    }

    static void setRenderingCrosshair(boolean value) {
        renderingCrosshair = value;
    }

    public static boolean isRenderingCrosshair() {
        return renderingCrosshair;
    }

    /** Scaled screen center X (matches in-game crosshair / snap guides). */
    public static int getScaledCenterX() {
        return resolveScaledSize()[0] / 2;
    }

    /** Scaled screen center Y (matches in-game crosshair / snap guides). */
    public static int getScaledCenterY() {
        return resolveScaledSize()[1] / 2;
    }

    private static int[] resolveScaledSize() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                ScaledResolution sr = new ScaledResolution(mc);
                return new int[] { sr.getScaledWidth(), sr.getScaledHeight() };
            }
        } catch (Throwable ignored) {
        }
        return new int[] { 0, 0 };
    }

    public static boolean isAlwaysVisible() {
        return ConfigManager.isHudEditorCenterGuidesAlways();
    }

    public static void setAlwaysVisible(boolean value) {
        ConfigManager.setHudEditorCenterGuidesAlways(value);
    }

    public static void toggleAlwaysVisible() {
        setAlwaysVisible(!isAlwaysVisible());
    }

    /** Draw on top of an open {@link net.minecraft.client.gui.GuiScreen} (called from mixin at drawScreen RETURN). */
    public static void drawOnGui(int width, int height) {
        if (!isAlwaysVisible()) {
            return;
        }
        draw(width, height);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!isAlwaysVisible()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        draw(sr.getScaledWidth(), sr.getScaledHeight());
    }

    /**
     * Screen-center snap guides: faint full-screen lines plus the active in-game crosshair at the midpoint.
     */
    public static void draw(int width, int height) {
        int[] scaled = resolveScaledSize();
        int drawW = scaled[0] > 0 ? scaled[0] : width;
        int drawH = scaled[1] > 0 ? scaled[1] : height;
        if (drawW <= 0 || drawH <= 0) {
            return;
        }
        int centerX = drawW / 2;
        int centerY = drawH / 2;

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            Gui.drawRect(centerX, 0, centerX + 1, drawH, 0x5097C9FF);
            Gui.drawRect(0, centerY, drawW, centerY + 1, 0x5097C9FF);
            HudEditorCrosshairRenderer.drawAtScreenCenter();
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
