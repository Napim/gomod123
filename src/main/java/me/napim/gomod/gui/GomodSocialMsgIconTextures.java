package me.napim.gomod.gui;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * Top-right gomod Social unread indicators.
 */
public final class GomodSocialMsgIconTextures {

    public static final ResourceLocation PUBLIC =
            new ResourceLocation("gomod123", "textures/gomodmsgicon.png");
    public static final ResourceLocation PUBLIC_HOVER =
            new ResourceLocation("gomod123", "textures/gomodmsgiconhovering.png");
    public static final ResourceLocation DM =
            new ResourceLocation("gomod123", "textures/gomoddmicon.png");
    public static final ResourceLocation DM_HOVER =
            new ResourceLocation("gomod123", "textures/gomoddmiconhovering.png");
    public static final ResourceLocation FRIEND =
            new ResourceLocation("gomod123", "textures/gomodfriendicon.png");
    public static final ResourceLocation FRIEND_HOVER =
            new ResourceLocation("gomod123", "textures/gomodfriendiconhovering.png");
    public static final ResourceLocation CAPE =
            new ResourceLocation("gomod123", "textures/gomodcapeicon.png");
    public static final ResourceLocation CAPE_HOVER =
            new ResourceLocation("gomod123", "textures/gomodcapeiconhovering.png");

    public static final int DRAW_SIZE = 20;

    private GomodSocialMsgIconTextures() {}

    public static void preload(Minecraft mc) {
        if (mc != null) {
            GomodClasspathTextures.ensureLoaded(mc, PUBLIC);
            GomodClasspathTextures.ensureLoaded(mc, PUBLIC_HOVER);
            GomodClasspathTextures.ensureLoaded(mc, DM);
            GomodClasspathTextures.ensureLoaded(mc, DM_HOVER);
            GomodClasspathTextures.ensureLoaded(mc, FRIEND);
            GomodClasspathTextures.ensureLoaded(mc, FRIEND_HOVER);
            GomodClasspathTextures.ensureLoaded(mc, CAPE);
            GomodClasspathTextures.ensureLoaded(mc, CAPE_HOVER);
        }
    }

    /** @return true if drawn */
    public static boolean drawPublic(int x, int y, boolean hovering) {
        return draw(x, y, hovering ? PUBLIC_HOVER : PUBLIC);
    }

    /** @return true if drawn */
    public static boolean drawDm(int x, int y, boolean hovering) {
        return draw(x, y, hovering ? DM_HOVER : DM);
    }

    /** @return true if drawn */
    public static boolean drawFriend(int x, int y, boolean hovering) {
        return draw(x, y, hovering ? FRIEND_HOVER : FRIEND);
    }

    /** @return true if drawn */
    public static boolean drawCape(int x, int y, boolean hovering) {
        return draw(x, y, hovering ? CAPE_HOVER : CAPE);
    }

    private static boolean draw(int x, int y, ResourceLocation texture) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        if (GomodClasspathTextures.ensureLoaded(mc, texture) == null) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(texture, DRAW_SIZE);
        int texH = GomodClasspathTextures.getHeight(texture, DRAW_SIZE);
        if (texW <= 0 || texH <= 0) {
            return false;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GomodClasspathTextures.bindTexture(mc, texture);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH, DRAW_SIZE, DRAW_SIZE, texW, texH);
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }
}
