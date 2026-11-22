package me.napim.gomod.gui;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * ON/OFF toggle art for gomod Social ({@code enabledbutton.png} / {@code disabledbutton.png} and hover variants).
 */
public final class GomodSocialToggleTextures {

    public static final ResourceLocation TEXTURE_ON =
            new ResourceLocation("gomod123", "textures/enabledbutton.png");
    public static final ResourceLocation TEXTURE_ON_HOVER =
            new ResourceLocation("gomod123", "textures/enabledbuttonhovering.png");
    public static final ResourceLocation TEXTURE_OFF =
            new ResourceLocation("gomod123", "textures/disabledbutton.png");
    public static final ResourceLocation TEXTURE_OFF_HOVER =
            new ResourceLocation("gomod123", "textures/disabledbuttonhovering.png");

    /** Draw size in Social privacy rows (source art is 173×104). */
    public static final int DRAW_W = 36;
    public static final int DRAW_H = 22;

    private GomodSocialToggleTextures() {}

    public static int drawWidth() {
        return DRAW_W;
    }

    public static int drawHeight() {
        return DRAW_H;
    }

    public static void preload(Minecraft mc) {
        if (mc == null) {
            return;
        }
        GomodClasspathTextures.ensureLoaded(mc, TEXTURE_ON);
        GomodClasspathTextures.ensureLoaded(mc, TEXTURE_ON_HOVER);
        GomodClasspathTextures.ensureLoaded(mc, TEXTURE_OFF);
        GomodClasspathTextures.ensureLoaded(mc, TEXTURE_OFF_HOVER);
    }

    /**
     * @return true if a texture was drawn
     */
    public static boolean draw(int x, int y, boolean on, boolean hover) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        ResourceLocation pack = on
                ? (hover ? TEXTURE_ON_HOVER : TEXTURE_ON)
                : (hover ? TEXTURE_OFF_HOVER : TEXTURE_OFF);
        if (GomodClasspathTextures.ensureLoaded(mc, pack) == null) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(pack, 173);
        int texH = GomodClasspathTextures.getHeight(pack, 104);
        if (texW <= 0 || texH <= 0) {
            return false;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GomodClasspathTextures.bindTexture(mc, pack);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH, DRAW_W, DRAW_H, texW, texH);
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }
}
