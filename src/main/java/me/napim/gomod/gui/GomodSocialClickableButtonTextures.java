package me.napim.gomod.gui;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * Social action buttons — draw PNG as-is (same approach as privacy toggles).
 */
public final class GomodSocialClickableButtonTextures {

    private static final int TEX_W = 334;
    private static final int TEX_H = 111;

    public enum Style {
        SCREENSHOT("clickablebutton.png", "clickablebuttonhovering.png"),
        SHARE_INV("clickablebutton2.png", "clickablebutton2hovering.png"),
        REMOVE_FRIEND("clickablebutton3.png", "clickablebutton3hovering.png"),
        SEND("clickablebutton4.png", "clickablebutton4hovering.png");

        private final ResourceLocation normal;
        private final ResourceLocation hover;

        Style(String normalFile, String hoverFile) {
            this.normal = new ResourceLocation("gomod123", "textures/" + normalFile);
            this.hover = new ResourceLocation("gomod123", "textures/" + hoverFile);
        }

        public ResourceLocation normal() {
            return normal;
        }

        public ResourceLocation hover() {
            return hover;
        }
    }

    private GomodSocialClickableButtonTextures() {}

    public static void preload(Minecraft mc) {
        if (mc == null) {
            return;
        }
        for (Style style : Style.values()) {
            GomodClasspathTextures.ensureLoaded(mc, style.normal());
            GomodClasspathTextures.ensureLoaded(mc, style.hover());
        }
    }

    private static final int LABEL_PAD_PX = 16;

    /** Width that preserves source aspect ratio at the given draw height. */
    public static int widthForHeight(int height) {
        if (height <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round((double) height * TEX_W / (double) TEX_H));
    }

    /** At least texture aspect width, expanded when the label is wider (e.g. Remove friend). */
    public static int widthForHeightAndLabel(FontRenderer fr, int height, String label) {
        int w = widthForHeight(height);
        if (fr != null && label != null && !label.isEmpty()) {
            w = Math.max(w, fr.getStringWidth(label) + LABEL_PAD_PX);
        }
        return w;
    }

    public static boolean draw(int x, int y, int width, int height, Style style, boolean hover) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || style == null || width <= 0 || height <= 0) {
            return false;
        }
        ResourceLocation pack = hover ? style.hover() : style.normal();
        if (GomodClasspathTextures.ensureLoaded(mc, pack) == null) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(pack, TEX_W);
        int texH = GomodClasspathTextures.getHeight(pack, TEX_H);
        if (texW <= 0 || texH <= 0) {
            return false;
        }

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(hover ? 1.08f : 1f, hover ? 1.08f : 1f, hover ? 1.08f : 1f, 1f);
        GomodClasspathTextures.bindTexture(mc, pack);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH, width, height, texW, texH);
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }
}
