package me.napim.gomod.gui;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * Search magnifier art for gomod Social dock filter ({@code searchicongomodsocial.png}).
 */
public final class GomodSocialSearchIconTextures {

    public static final ResourceLocation TEXTURE =
            new ResourceLocation("gomod123", "textures/searchicongomodsocial.png");

    public static final int DRAW_SIZE = 13;
    public static final int PAD_LEFT = 8;
    public static final int TEXT_GAP = 3;

    private GomodSocialSearchIconTextures() {}

    /** Left padding reserved before typed text (icon + gaps). */
    public static int insetLeft() {
        return PAD_LEFT + DRAW_SIZE + TEXT_GAP;
    }

    public static void preload(Minecraft mc) {
        if (mc == null) {
            return;
        }
        GomodClasspathTextures.ensureLoaded(mc, TEXTURE);
    }

    /**
     * @return true if the texture was drawn
     */
    public static boolean draw(int boxL, int boxT, int boxH) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        if (GomodClasspathTextures.ensureLoaded(mc, TEXTURE) == null) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(TEXTURE, DRAW_SIZE);
        int texH = GomodClasspathTextures.getHeight(TEXTURE, DRAW_SIZE);
        if (texW <= 0 || texH <= 0) {
            return false;
        }
        int iconX = boxL + PAD_LEFT;
        int iconY = boxT + Math.max(0, (boxH - DRAW_SIZE) / 2);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GomodClasspathTextures.bindTexture(mc, TEXTURE);
        Gui.drawScaledCustomSizeModalRect(iconX, iconY, 0, 0, texW, texH, DRAW_SIZE, DRAW_SIZE, texW, texH);
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }
}
