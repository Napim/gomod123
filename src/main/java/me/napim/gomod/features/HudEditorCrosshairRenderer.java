package me.napim.gomod.features;

import me.napim.gomod.mixins.GuiIngameForgeAccessor;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Method;

/**
 * Draws the player's active in-game crosshair at the scaled screen center (HUD editor guides).
 * Custom Crosshair Mod (Sparkless101) is invoked directly when present; otherwise Forge
 * {@code renderCrosshairs} is used as a fallback.
 */
public final class HudEditorCrosshairRenderer {

    private static final String CCM_MOD_CLASS = "sparkless101.crosshairmod.main.CustomCrosshairMod";
    private static final String CCM_CROSSHAIR_CLASS = "sparkless101.crosshairmod.crosshair.Crosshair";

    private HudEditorCrosshairRenderer() {
    }

    public static void drawAtScreenCenter() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) {
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int scaledW = sr.getScaledWidth();
        int scaledH = sr.getScaledHeight();
        float partialTicks = 1.0f;
        try {
            if (mc.timer != null) {
                partialTicks = mc.timer.renderPartialTicks;
            }
        } catch (Throwable ignored) {
        }

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        HudGlState.restoreForOverlayText();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            HudEditorCenterGuides.setRenderingCrosshair(true);
            try {
                if (!tryDrawCustomCrosshairMod()) {
                    drawForgeCrosshair(mc, sr, scaledW, scaledH, partialTicks);
                }
            } finally {
                HudEditorCenterGuides.setRenderingCrosshair(false);
            }
        } catch (Throwable ignored) {
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * Custom Crosshair Mod only calls {@code drawCrosshair} from its tick handler when no GUI is open.
     * HUD editor must invoke it directly and temporarily allow hidden-GUI visibility.
     */
    private static boolean tryDrawCustomCrosshairMod() {
        try {
            Class<?> modClass = Class.forName(CCM_MOD_CLASS);
            Method getMod = modClass.getMethod("getCrosshairMod");
            Object mod = getMod.invoke(null);
            if (mod == null) {
                return false;
            }

            Method getCrosshair = modClass.getMethod("getCrosshair");
            Object crosshair = getCrosshair.invoke(mod);
            if (crosshair == null) {
                return false;
            }

            Class<?> crosshairClass = Class.forName(CCM_CROSSHAIR_CLASS);
            if (!Boolean.TRUE.equals(crosshairClass.getMethod("getEnabled").invoke(crosshair))) {
                return false;
            }

            Method getVisibleHiddenGui = crosshairClass.getMethod("getVisibleHiddenGui");
            Method setVisibleHiddenGui = crosshairClass.getMethod("setVisibleHiddenGui", boolean.class);
            boolean prevHiddenGui = Boolean.TRUE.equals(getVisibleHiddenGui.invoke(crosshair));
            setVisibleHiddenGui.invoke(crosshair, true);
            try {
                crosshairClass.getMethod("drawCrosshair").invoke(crosshair);
            } finally {
                setVisibleHiddenGui.invoke(crosshair, prevHiddenGui);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void drawForgeCrosshair(Minecraft mc, ScaledResolution sr, int scaledW, int scaledH,
            float partialTicks) {
        RenderGameOverlayEvent base = new RenderGameOverlayEvent(partialTicks, sr);
        RenderGameOverlayEvent.Pre pre = new RenderGameOverlayEvent.Pre(
                base, RenderGameOverlayEvent.ElementType.CROSSHAIRS);
        MinecraftForge.EVENT_BUS.post(pre);
        if (!pre.isCanceled()) {
            invokeRenderCrosshairs(mc.ingameGUI, scaledW, scaledH);
        }
        MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Post(
                base, RenderGameOverlayEvent.ElementType.CROSSHAIRS));
    }

    private static void invokeRenderCrosshairs(GuiIngame ingame, int scaledW, int scaledH) {
        if (ingame instanceof GuiIngameForgeAccessor) {
            ((GuiIngameForgeAccessor) ingame).gomod$invokeRenderCrosshairs(scaledW, scaledH);
        }
    }
}
