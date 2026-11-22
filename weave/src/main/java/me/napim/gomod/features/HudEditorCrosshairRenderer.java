package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Method;

/** Forge-free crosshair preview used by the native Weave HUD editor. */
public final class HudEditorCrosshairRenderer {
    private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
    private static final String CCM_MOD_CLASS = "sparkless101.crosshairmod.main.CustomCrosshairMod";
    private static final String CCM_CROSSHAIR_CLASS = "sparkless101.crosshairmod.crosshair.Crosshair";

    private HudEditorCrosshairRenderer() {}

    public static void drawAtScreenCenter() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) return;
        ScaledResolution sr = new ScaledResolution(mc);
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        HudGlState.restoreForOverlayText();
        try {
            HudEditorCenterGuides.setRenderingCrosshair(true);
            if (!tryDrawCustomCrosshairMod()) {
                mc.getTextureManager().bindTexture(ICONS);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(775, 769, 1, 0);
                Gui.drawModalRectWithCustomSizedTexture(
                        sr.getScaledWidth() / 2 - 7, sr.getScaledHeight() / 2 - 7,
                        0.0F, 0.0F, 16, 16, 256.0F, 256.0F);
            }
        } catch (Throwable ignored) {
        } finally {
            HudEditorCenterGuides.setRenderingCrosshair(false);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
            GlStateManager.color(1F, 1F, 1F, 1F);
        }
    }

    private static boolean tryDrawCustomCrosshairMod() {
        try {
            Class<?> modClass = Class.forName(CCM_MOD_CLASS);
            Object mod = modClass.getMethod("getCrosshairMod").invoke(null);
            if (mod == null) return false;
            Object crosshair = modClass.getMethod("getCrosshair").invoke(mod);
            if (crosshair == null) return false;
            Class<?> type = Class.forName(CCM_CROSSHAIR_CLASS);
            if (!Boolean.TRUE.equals(type.getMethod("getEnabled").invoke(crosshair))) return false;
            Method getVisible = type.getMethod("getVisibleHiddenGui");
            Method setVisible = type.getMethod("setVisibleHiddenGui", boolean.class);
            boolean previous = Boolean.TRUE.equals(getVisible.invoke(crosshair));
            setVisible.invoke(crosshair, true);
            try { type.getMethod("drawCrosshair").invoke(crosshair); }
            finally { setVisible.invoke(crosshair, previous); }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
