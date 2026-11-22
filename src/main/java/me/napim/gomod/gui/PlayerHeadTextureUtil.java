package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Minotar-based player head (same approach as {@link TeamSpamGUI}). */
public final class PlayerHeadTextureUtil {
    /** Fixed download resolution — always sample this region when drawing (avoids stretched eyes). */
    private static final int AVATAR_TEX_PX = 32;

    private static String lastLocalSkinPreviewPath;
    private static ResourceLocation lastLocalSkinPreviewRl;
    private static int lastLocalSkinIw = 64;
    private static int lastLocalSkinIh = 64;

    private PlayerHeadTextureUtil() {
    }

    /**
     * Minotar avatar by Mojang UUID (undashed) or by player name (e.g. {@code MHF_Steve}).
     */
    public static void drawMinotarAvatar(String uuidOrName, int x, int y, int size) {
        drawMinotarAvatar(uuidOrName, x, y, size, 0);
    }

    /**
     * Draw a Minotar face inside a square. {@code inset} shrinks the draw area (e.g. 1 for a 1px margin).
     */
    public static void drawMinotarAvatar(String uuidOrName, int x, int y, int size, int inset) {
        try {
            String key = uuidOrName == null || uuidOrName.isEmpty() ? "MHF_Steve" : uuidOrName.trim();
            String enc = URLEncoder.encode(key, StandardCharsets.UTF_8.name()).replace("+", "%20");
            int dim = AVATAR_TEX_PX;
            String url = "https://minotar.net/avatar/" + enc + "/" + dim;
            ResourceLocation rl = new ResourceLocation("minotar/avatarkey/" + dim + "/"
                    + Integer.toHexString(key.toLowerCase().hashCode()));
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return;
            }
            TextureManager tm = mc.getTextureManager();
            ITextureObject tex = tm.getTexture(rl);
            if (tex == null) {
                ThreadDownloadImageData data = new ThreadDownloadImageData(null, url, null, null);
                tm.loadTexture(rl, data);
            }
            tm.bindTexture(rl);
            int pad = Math.max(0, inset);
            int drawSize = Math.max(1, size - pad * 2);
            int drawX = x + pad;
            int drawY = y + pad;
            GlStateManager.pushMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Gui.drawScaledCustomSizeModalRect(drawX, drawY, 0, 0, dim, dim, drawSize, drawSize, dim, dim);
            GlStateManager.popMatrix();
        } catch (Exception e) {
            System.err.println("[gomod] PlayerHeadTextureUtil avatar key: " + e.getMessage());
        }
    }

    /**
     * Face preview from a local 64x64 (or larger) skin PNG; falls back to scaling the whole image.
     */
    public static void drawLocalSkinHeadPreview(String absolutePath, int x, int y, int faceSize) {
        try {
            if (absolutePath == null || absolutePath.isEmpty()) {
                return;
            }
            File f = new File(absolutePath);
            if (!f.isFile()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return;
            }
            if (!absolutePath.equals(lastLocalSkinPreviewPath) || lastLocalSkinPreviewRl == null) {
                lastLocalSkinPreviewPath = absolutePath;
                BufferedImage img = ImageIO.read(f);
                if (img == null) {
                    lastLocalSkinPreviewRl = null;
                    return;
                }
                lastLocalSkinIw = Math.max(1, img.getWidth());
                lastLocalSkinIh = Math.max(1, img.getHeight());
                ResourceLocation rl = new ResourceLocation("gomod123", "nickpreview/skin/" + Integer.toHexString(absolutePath.hashCode()));
                try {
                    mc.getTextureManager().deleteTexture(rl);
                } catch (Throwable ignored) {
                }
                mc.getTextureManager().loadTexture(rl, new DynamicTexture(img));
                lastLocalSkinPreviewRl = rl;
            }
            if (lastLocalSkinPreviewRl == null) {
                return;
            }
            int iw = lastLocalSkinIw;
            int ih = lastLocalSkinIh;
            int u = 8;
            int v = 8;
            int fw = 8;
            int fh = 8;
            if (iw < 64 || ih < 64) {
                u = 0;
                v = 0;
                fw = iw;
                fh = ih;
            }
            mc.getTextureManager().bindTexture(lastLocalSkinPreviewRl);
            GlStateManager.pushMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Gui.drawScaledCustomSizeModalRect(x, y, u, v, fw, fh, faceSize, faceSize, iw, ih);
            GlStateManager.popMatrix();
        } catch (Exception e) {
            System.err.println("[gomod] local skin preview: " + e.getMessage());
        }
    }

    /**
     * @param uuid 32 hex without dashes, or with dashes (normalized)
     */
    public static void drawMinotarHead(String uuid, int x, int y, int size) {
        try {
            if (uuid == null || uuid.isEmpty()) {
                uuid = "00000000000000000000000000000000";
            } else {
                uuid = uuid.replace("-", "");
            }
            String url = "https://minotar.net/avatar/" + uuid + "/16";
            ResourceLocation rl = new ResourceLocation("minotar/avatar/" + uuid);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return;
            }
            TextureManager tm = mc.getTextureManager();
            ITextureObject tex = tm.getTexture(rl);
            if (tex == null) {
                ThreadDownloadImageData data = new ThreadDownloadImageData(null, url, null, null);
                tm.loadTexture(rl, data);
            }
            tm.bindTexture(rl);
            GlStateManager.pushMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 16, 16, size, size, 16, 16);
            GlStateManager.popMatrix();
        } catch (Exception e) {
            System.err.println("[gomod] PlayerHeadTextureUtil: " + e.getMessage());
        }
    }
}
