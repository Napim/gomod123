package me.napim.gomod.render;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.mixins.GuiInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;

public final class GuiBackdropRenderer {
    private GuiBackdropRenderer() {
    }

    public static boolean renderCustomBackdrop(GuiScreen screen, int width, int height) {
        return renderCustomBackdrop(screen, width, height, 1.0f);
    }

    public static boolean renderCustomBackdrop(GuiScreen screen, int width, int height, float alphaScale) {
        try {
            if ("VANILLA".equals(ConfigManager.getGuiBackdropMode())) {
                return false;
            }

            Minecraft mc = Minecraft.getMinecraft();
            boolean isGomodGui = screen != null && screen.getClass().getName().startsWith("me.napim.gomod.gui.");
            if (!isGomodGui && (mc == null || mc.theWorld == null)) {
                return false;
            }
            int dimPct = isGomodGui ? ConfigManager.getGuiBackdropGomodDimPercent()
                    : ConfigManager.getGuiBackdropOtherDimPercent();
            int blurPct = isGomodGui ? ConfigManager.getGuiBackdropGomodBlurPercent()
                    : ConfigManager.getGuiBackdropOtherBlurPercent();
            boolean wantBlur = blurPct > 0 && GuiBackdropBlur.isAvailable();
            int excludeBottomGui = GuiBackdropChatReserve.computeExcludeBottomGuiPixels(mc, screen, isGomodGui);
            float clampedAlpha = clamp01(alphaScale);

            if (dimPct >= 100 && !wantBlur) {
                return false;
            }
            if (clampedAlpha <= 0.0f) {
                return true;
            }
            if (dimPct <= 0 && !wantBlur) {
                return true;
            }

            boolean inWorld = mc != null && mc.theWorld != null;
            int top = inWorld ? 0xC0101010 : -1072689136;
            int bottom = inWorld ? 0xD0101010 : -804253680;

            if (wantBlur) {
                GuiBackdropBlur.render(mc, width, height, blurPct, excludeBottomGui, clampedAlpha);
            }
            if (dimPct > 0 && screen != null) {
                if (excludeBottomGui > 0) {
                    GuiBackdropChatReserve.enableBottomChatClearScissor(mc, excludeBottomGui);
                }
                try {
                    ((GuiInvoker) (Gui) (Object) screen).gomod$invokeDrawGradientRect(
                            0, 0, width, height,
                            scaleGradientAlpha(top, dimPct, clampedAlpha),
                            scaleGradientAlpha(bottom, dimPct, clampedAlpha));
                } finally {
                    if (excludeBottomGui > 0) {
                        GuiBackdropChatReserve.disableBottomChatClearScissor();
                    }
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int scaleGradientAlpha(int argb, int dimPercent, float alphaScale) {
        long u = argb & 0xFFFFFFFFL;
        int a = (int) (u >>> 24);
        int scaled = Math.max(0, Math.min(255, Math.round(a * (dimPercent / 100.0f) * alphaScale)));
        int rgb = (int) (u & 0xFFFFFFL);
        return (scaled << 24) | rgb;
    }

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }
}
