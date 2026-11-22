package me.napim.gomod.gui;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * Pill-shaped UI textures: fixed rounded ends, stretchable middle (not full-image scale).
 */
final class SocialPillTextureDraw {

    private static final int MIN_CENTER_DRAW_PX = 4;

    private SocialPillTextureDraw() {}

    /**
     * Horizontal 3-slice only (for fixed-height pills like workspace tags).
     */
    static boolean drawHorizontal3Slice(ResourceLocation texture, int x, int y, int width, int height,
            int defaultTexW, int defaultTexH) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || texture == null || width <= 0 || height <= 0) {
            return false;
        }
        if (GomodClasspathTextures.ensureLoaded(mc, texture) == null) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(texture, defaultTexW);
        int texH = GomodClasspathTextures.getHeight(texture, defaultTexH);
        if (texW <= 0 || texH <= 0) {
            return false;
        }

        beginDraw(mc, texture);

        int capTex = endCapTexPixels(texW, texH);
        int capDraw = capDrawForHeight(height, texW, texH);
        int minWidth = capDraw * 2 + MIN_CENTER_DRAW_PX;

        if (width < minWidth) {
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH, width, height, texW, texH);
        } else {
            int centerTexW = texW - capTex * 2;
            int centerDrawW = width - capDraw * 2;
            drawTexRegion(x, y, capDraw, height, 0, 0, capTex, texH, texW, texH);
            drawTexRegion(x + capDraw, y, centerDrawW, height, capTex, 0, centerTexW, texH, texW, texH);
            drawTexRegion(x + capDraw + centerDrawW, y, capDraw, height, capTex + centerTexW, 0, capTex, texH, texW, texH);
        }

        endDraw();
        return true;
    }

    /**
     * Full 9-slice for variable width and height (chat speech bubbles).
     */
    static boolean drawNineSlice(ResourceLocation texture, int x, int y, int width, int height,
            int defaultTexW, int defaultTexH) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || texture == null || width <= 0 || height <= 0) {
            return false;
        }
        if (GomodClasspathTextures.ensureLoaded(mc, texture) == null) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(texture, defaultTexW);
        int texH = GomodClasspathTextures.getHeight(texture, defaultTexH);
        if (texW <= 0 || texH <= 0) {
            return false;
        }

        beginDraw(mc, texture);

        int capTexW = endCapTexPixels(texW, texH);
        int capTexH = endCapTexPixels(texH, texW);
        int capDrawW = capDrawForHeight(height, texW, texH);
        int capDrawH = capDrawForHeight(height, texH, texW);
        int minW = capDrawW * 2 + MIN_CENTER_DRAW_PX;
        int minH = capDrawH * 2 + MIN_CENTER_DRAW_PX;

        if (width < minW || height < minH) {
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH, width, height, texW, texH);
        } else {
            int centerTexW = texW - capTexW * 2;
            int centerTexH = texH - capTexH * 2;
            int centerDrawW = width - capDrawW * 2;
            int centerDrawH = height - capDrawH * 2;

            int uMid = capTexW;
            int vMid = capTexH;
            int x0 = x;
            int x1 = x + capDrawW;
            int x2 = x + capDrawW + centerDrawW;
            int y0 = y;
            int y1 = y + capDrawH;
            int y2 = y + capDrawH + centerDrawH;

            drawTexRegion(x0, y0, capDrawW, capDrawH, 0, 0, capTexW, capTexH, texW, texH);
            drawTexRegion(x1, y0, centerDrawW, capDrawH, uMid, 0, centerTexW, capTexH, texW, texH);
            drawTexRegion(x2, y0, capDrawW, capDrawH, uMid + centerTexW, 0, capTexW, capTexH, texW, texH);

            drawTexRegion(x0, y1, capDrawW, centerDrawH, 0, vMid, capTexW, centerTexH, texW, texH);
            drawTexRegion(x1, y1, centerDrawW, centerDrawH, uMid, vMid, centerTexW, centerTexH, texW, texH);
            drawTexRegion(x2, y1, capDrawW, centerDrawH, uMid + centerTexW, vMid, capTexW, centerTexH, texW, texH);

            drawTexRegion(x0, y2, capDrawW, capDrawH, 0, vMid + centerTexH, capTexW, capTexH, texW, texH);
            drawTexRegion(x1, y2, centerDrawW, capDrawH, uMid, vMid + centerTexH, centerTexW, capTexH, texW, texH);
            drawTexRegion(x2, y2, capDrawW, capDrawH, uMid + centerTexW, vMid + centerTexH, capTexW, capTexH, texW, texH);
        }

        endDraw();
        return true;
    }

    private static void beginDraw(Minecraft mc, ResourceLocation texture) {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GomodClasspathTextures.bindTexture(mc, texture);
    }

    private static void endDraw() {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Width of one rounded end in texture pixels (semicircle radius ≈ half of the short side). */
    static int endCapTexPixels(int texW, int texH) {
        return Math.max(1, Math.min(texH / 2, (texW - 2) / 2));
    }

    static int capDrawForHeight(int height, int texW, int texH) {
        int capTex = endCapTexPixels(texW, texH);
        return Math.max(1, Math.round(capTex * (float) height / (float) texH));
    }

    private static void drawTexRegion(int x, int y, int drawW, int drawH,
            int texU, int texV, int texRegionW, int texRegionH, int texFullW, int texFullH) {
        if (drawW <= 0 || drawH <= 0 || texRegionW <= 0 || texRegionH <= 0) {
            return;
        }
        Gui.drawScaledCustomSizeModalRect(x, y, texU, texV, texRegionW, texRegionH, drawW, drawH, texFullW, texFullH);
    }
}
