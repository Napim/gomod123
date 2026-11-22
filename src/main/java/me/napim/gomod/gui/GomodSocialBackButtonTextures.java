package me.napim.gomod.gui;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Circular back button art for gomod Social workspace ({@code backbutton.png} /
 * {@code backbuttonhovering.png}).
 */
public final class GomodSocialBackButtonTextures {

    public static final ResourceLocation TEXTURE =
            new ResourceLocation("gomod123", "textures/backbutton.png");
    public static final ResourceLocation TEXTURE_HOVER =
            new ResourceLocation("gomod123", "textures/backbuttonhovering.png");

    public static final int DRAW_SIZE = 22;

    private static BufferedImage hitImage;
    private static int hitImageW;
    private static int hitImageH;

    private GomodSocialBackButtonTextures() {}

    public static int drawSize() {
        return DRAW_SIZE;
    }

    public static void preload(Minecraft mc) {
        if (mc == null) {
            return;
        }
        GomodClasspathTextures.ensureLoaded(mc, TEXTURE);
        GomodClasspathTextures.ensureLoaded(mc, TEXTURE_HOVER);
        ensureHitImage();
    }

    /**
     * @return true if a texture was drawn
     */
    public static boolean draw(int x, int y, boolean hover) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        ResourceLocation pack = hover ? TEXTURE_HOVER : TEXTURE;
        if (GomodClasspathTextures.ensureLoaded(mc, pack) == null) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(pack, DRAW_SIZE);
        int texH = GomodClasspathTextures.getHeight(pack, DRAW_SIZE);
        if (texW <= 0 || texH <= 0) {
            return false;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GomodClasspathTextures.bindTexture(mc, pack);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH, DRAW_SIZE, DRAW_SIZE, texW, texH);
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }

    /** True when the click is on a non-transparent pixel of the circular button art. */
    public static boolean hitTest(int mx, int my, int drawX, int drawY) {
        ensureHitImage();
        int lx = mx - drawX;
        int ly = my - drawY;
        if (lx < 0 || ly < 0 || lx >= DRAW_SIZE || ly >= DRAW_SIZE) {
            return false;
        }
        if (hitImage != null && hitImageW > 0 && hitImageH > 0) {
            int tx = lx * hitImageW / DRAW_SIZE;
            int ty = ly * hitImageH / DRAW_SIZE;
            if (tx >= 0 && ty >= 0 && tx < hitImageW && ty < hitImageH) {
                int alpha = (hitImage.getRGB(tx, ty) >>> 24) & 0xFF;
                return alpha > 32;
            }
            return false;
        }
        float cx = drawX + DRAW_SIZE / 2f;
        float cy = drawY + DRAW_SIZE / 2f;
        float dx = mx - cx;
        float dy = my - cy;
        float radius = DRAW_SIZE / 2f - 1f;
        return dx * dx + dy * dy <= radius * radius;
    }

    private static void ensureHitImage() {
        if (hitImage != null) {
            return;
        }
        synchronized (GomodSocialBackButtonTextures.class) {
            if (hitImage != null) {
                return;
            }
            hitImage = readClasspathImage(TEXTURE);
            if (hitImage != null) {
                hitImageW = hitImage.getWidth();
                hitImageH = hitImage.getHeight();
            }
        }
    }

    private static BufferedImage readClasspathImage(ResourceLocation loc) {
        String path = "/assets/" + loc.getResourceDomain() + "/" + loc.getResourcePath();
        InputStream in = GomodSocialBackButtonTextures.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try {
            return ImageIO.read(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
