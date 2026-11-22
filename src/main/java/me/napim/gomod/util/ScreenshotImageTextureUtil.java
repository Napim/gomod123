package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Converts decoded screenshots to a format LWJGL/Minecraft 1.8.9 uploads reliably (fixes black previews).
 */
public final class ScreenshotImageTextureUtil {
    private ScreenshotImageTextureUtil() {}

    public static BufferedImage toArgbCopy(BufferedImage src) {
        if (src == null) {
            return null;
        }
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Registers a new dynamic texture under a unique {@link ResourceLocation} (caller should
     * {@link net.minecraft.client.renderer.texture.TextureManager#deleteTexture} when done).
     */
    public static ResourceLocation registerFromFile(Minecraft mc, File file, String folder) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                return null;
            }
            return registerDynamicTexture(mc, img, folder);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static BufferedImage loadThumbnailFromFile(File file, int maxWidth, int maxHeight) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                return null;
            }
            int srcW = Math.max(1, img.getWidth());
            int srcH = Math.max(1, img.getHeight());
            int limitW = Math.max(1, maxWidth);
            int limitH = Math.max(1, maxHeight);
            double scale = Math.min(1.0D, Math.min((double) limitW / (double) srcW, (double) limitH / (double) srcH));
            int dstW = Math.max(1, (int) Math.round(srcW * scale));
            int dstH = Math.max(1, (int) Math.round(srcH * scale));
            BufferedImage out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(img, 0, 0, dstW, dstH, null);
            } finally {
                g.dispose();
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ResourceLocation registerDynamicTexture(Minecraft mc, BufferedImage image, String folder) {
        if (mc == null || mc.getTextureManager() == null || image == null) {
            return null;
        }
        BufferedImage argb = toArgbCopy(image);
        String safeFolder = folder == null || folder.trim().isEmpty() ? "shot" : folder.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String token = Long.toHexString(System.nanoTime());
        ResourceLocation rl = new ResourceLocation("gomod123", safeFolder + "/" + token);
        mc.getTextureManager().loadTexture(rl, new DynamicTexture(argb));
        return rl;
    }
}
