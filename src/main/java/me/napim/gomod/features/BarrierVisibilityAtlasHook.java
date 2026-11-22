package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers the barrier sprite in the block atlas and live-swaps its pixels for the active
 * {@link BarrierVisibilityStyles} id (no full resource reload).
 *
 * <p>Writes <em>all</em> mip levels so the atlas's pre-stitched higher mips (which still contain
 * whatever was served at startup, i.e. the classic X) don't bleed through at distance or grazing
 * angles. Lower mips are produced with a cutout-aware 2×2 downsample so a 1-pixel transparent-
 * interior outline keeps full alpha at every level instead of fading below the cutout threshold.
 */
public final class BarrierVisibilityAtlasHook {

    public static final ResourceLocation BARRIER_SPRITE = new ResourceLocation("minecraft", "blocks/barrier");

    private static volatile TextureAtlasSprite barrierSprite;
    private static volatile String lastUploadError;
    /** False until {@link #applyStyleNow} succeeds with the current {@link ConfigManager} color/style. */
    private static volatile boolean savedStyleApplied;
    private static int applyRetryCooldownTicks;

    private static final ConcurrentHashMap<String, BufferedImage> PNG_CACHE = new ConcurrentHashMap<String, BufferedImage>();

    @SubscribeEvent
    public void onTextureStitchPre(TextureStitchEvent.Pre event) {
        try {
            if (event.map != Minecraft.getMinecraft().getTextureMapBlocks()) {
                return;
            }
            barrierSprite = event.map.registerSprite(BARRIER_SPRITE);
        } catch (Throwable t) {
            log("registerSprite failed: " + t);
        }
    }

    @SubscribeEvent
    public void onTextureStitchPost(TextureStitchEvent.Post event) {
        try {
            if (event.map != Minecraft.getMinecraft().getTextureMapBlocks()) {
                return;
            }
            barrierSprite = event.map.getAtlasSprite(BARRIER_SPRITE.toString());
            invalidateSavedStyleApplication();
            applyStyleNow(ConfigManager.getBarrierVisibilityStyle());
        } catch (Throwable t) {
            log("post-stitch lookup failed: " + t);
        }
    }

    /** Clears decoded PNG cache (e.g. after resource reload). */
    public static void clearPngCache() {
        PNG_CACHE.clear();
        invalidateSavedStyleApplication();
    }

    /**
     * After config load / runtime bootstrap the block atlas may already be stitched without our hook.
     * Retries on the client thread until {@link #applyStyleNow} succeeds.
     */
    public static void invalidateSavedStyleApplication() {
        savedStyleApplied = false;
        applyRetryCooldownTicks = 0;
    }

    public static void scheduleApplySavedStyle() {
        invalidateSavedStyleApplication();
        tryApplySavedStyleOnClientThread();
    }

    public static void clientTickRetryApplyIfNeeded() {
        if (savedStyleApplied) {
            return;
        }
        if (applyRetryCooldownTicks > 0) {
            applyRetryCooldownTicks--;
            return;
        }
        if (!tryApplySavedStyle()) {
            applyRetryCooldownTicks = 10;
        }
    }

    private static void tryApplySavedStyleOnClientThread() {
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return;
        }
        if (mc == null) {
            return;
        }
        Runnable run = BarrierVisibilityAtlasHook::tryApplySavedStyle;
        try {
            if (mc.isCallingFromMinecraftThread()) {
                run.run();
            } else {
                mc.addScheduledTask(run);
            }
        } catch (Throwable t) {
            run.run();
        }
    }

    /**
     * @return {@code true} when the atlas already reflects saved config (or barrier visibility is off).
     */
    public static boolean tryApplySavedStyle() {
        if (savedStyleApplied) {
            return true;
        }
        if (!ConfigManager.isBarrierVisibilityEnabled()) {
            savedStyleApplied = true;
            return true;
        }
        if (applyStyleNow(ConfigManager.getBarrierVisibilityStyle())) {
            savedStyleApplied = true;
            return true;
        }
        return false;
    }

    public static boolean applyStyleNow(String style) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return failed("no Minecraft instance");
            }
            TextureMap map = mc.getTextureMapBlocks();
            if (map == null) {
                return failed("getTextureMapBlocks() == null");
            }
            TextureAtlasSprite s = (barrierSprite != null)
                    ? barrierSprite
                    : map.getAtlasSprite(BARRIER_SPRITE.toString());
            if (s == null) {
                return failed("atlas sprite not found");
            }
            if (s.getIconName() != null && s.getIconName().contains("missingno")) {
                return failed("sprite is missingno — atlas needs rebuild first");
            }

            int w = s.getIconWidth();
            int h = s.getIconHeight();

            BufferedImage base = buildBaseImage(style, w, h);
            if (base == null) {
                return failed("base image build returned null");
            }

            int[] mip0 = new int[w * h];
            base.getRGB(0, 0, w, h, mip0, 0, w);
            multiplyMip0AlphaByPercent(mip0, ConfigManager.getBarrierVisibilityOpacityPercent());

            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);

            int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            try {
                int[] curPixels = mip0;
                int curW = w;
                int curH = h;
                int mipLevels = Math.max(0, mc.gameSettings.mipmapLevels);
                int originX = s.getOriginX();
                int originY = s.getOriginY();

                for (int level = 0; level <= mipLevels; level++) {
                    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, curW);
                    uploadLevel(level, originX >> level, originY >> level, curW, curH, curPixels);

                    if (curW <= 1 && curH <= 1) {
                        break;
                    }
                    int nextW = Math.max(1, curW >> 1);
                    int nextH = Math.max(1, curH >> 1);
                    curPixels = downsampleAverage(curPixels, curW, curH, nextW, nextH);
                    curW = nextW;
                    curH = nextH;
                }
            } finally {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
            }

            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                return failed("glTexSubImage2D returned GL error 0x" + Integer.toHexString(err));
            }
            lastUploadError = null;
            savedStyleApplied = true;
            return true;
        } catch (Throwable t) {
            savedStyleApplied = false;
            return failed("threw " + t);
        }
    }

    /**
     * Scales alpha of non-transparent pixels by {@code percent}/100 and premultiplies RGB so
     * {@link EnumWorldBlockLayer#TRANSLUCENT} blending matches the slider (required for hue-shifted vanilla).
     */
    private static void multiplyMip0AlphaByPercent(int[] argb, int percent) {
        if (percent >= 100) {
            return;
        }
        if (percent < 1) {
            percent = 1;
        }
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int a = (p >>> 24) & 0xFF;
            if (a == 0) {
                continue;
            }
            int na = (a * percent + 50) / 100;
            if (na > 255) {
                na = 255;
            }
            int r = ((p >> 16) & 0xFF) * na / 255;
            int g = ((p >> 8) & 0xFF) * na / 255;
            int b = (p & 0xFF) * na / 255;
            argb[i] = (na << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static void uploadLevel(int level, int x, int y, int w, int h, int[] pixelsArgb) {
        IntBuffer pixels = ByteBuffer.allocateDirect(w * h * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        pixels.put(pixelsArgb);
        // Java 8: chained IntBuffer.flip() can NoSuchMethodError under newer compile targets.
        ((Buffer) pixels).flip();
        GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                level,
                x,
                y,
                w,
                h,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                pixels);
    }

    /**
     * Proper alpha-averaging 2×2 downsample. Alpha is the plain mean of the four source alphas;
     * RGB is the alpha-weighted mean of the four source RGBs so transparent pixels don't drag the
     * color toward black. At higher mip levels the line's fractional coverage drops below the
     * cutout alpha threshold and gracefully fades to invisible instead of being preserved at full
     * alpha and bleeding red across the whole face.
     */
    private static int[] downsampleAverage(int[] src, int srcW, int srcH, int dstW, int dstH) {
        int[] out = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                int sx = x << 1;
                int sy = y << 1;
                long sumA = 0;
                long sumR = 0, sumG = 0, sumB = 0;
                long weight = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int yy = Math.min(srcH - 1, sy + dy);
                    for (int dx = 0; dx < 2; dx++) {
                        int xx = Math.min(srcW - 1, sx + dx);
                        int p = src[yy * srcW + xx];
                        int a = (p >>> 24) & 0xFF;
                        sumA += a;
                        sumR += ((p >>> 16) & 0xFF) * a;
                        sumG += ((p >>> 8) & 0xFF) * a;
                        sumB += (p & 0xFF) * a;
                        weight += a;
                    }
                }
                int outA = (int) (sumA / 4);
                int outR, outG, outB;
                if (weight > 0) {
                    outR = (int) (sumR / weight);
                    outG = (int) (sumG / weight);
                    outB = (int) (sumB / weight);
                } else {
                    outR = outG = outB = 0;
                }
                out[y * dstW + x] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
            }
        }
        return out;
    }

    /** OUTLINE → procedural with current config; FULL → load vanilla PNG. */
    private static BufferedImage buildBaseImage(String style, int w, int h) {
        String id = BarrierVisibilityStyles.normalize(style);
        if (BarrierVisibilityStyles.OUTLINE.equals(id)) {
            return BarrierVisibilityStyles.generateOutline(
                    w, h,
                    ConfigManager.getBarrierOutlineThickness(),
                    ConfigManager.getBarrierOutlineColorArgb());
        }
        ResourceLocation src = BarrierVisibilityStyles.textureLocation(id);
        BufferedImage img = loadPngCached(src);
        if (img == null) {
            return null;
        }
        if (img.getWidth() != w || img.getHeight() != h) {
            img = scale(img, w, h);
        }
        if (BarrierVisibilityStyles.isFull(id)) {
            img = hueShiftOpaquePixels(img, ConfigManager.getBarrierOutlineColorArgb());
        }
        return img;
    }

    /**
     * Recolors opaque texels toward the configured RGB while preserving each pixel's relative
     * brightness so the vanilla X keeps anti-aliasing. Uses the picked saturation and value, not
     * hue-only, so HSV picker colors show on the barrier in Vanilla (FULL) style too.
     */
    private static BufferedImage hueShiftOpaquePixels(BufferedImage src, int argb) {
        int tr = (argb >> 16) & 0xFF;
        int tg = (argb >> 8) & 0xFF;
        int tb = argb & 0xFF;
        float[] targetHsb = Color.RGBtoHSB(tr, tg, tb, null);
        float targetHue = targetHsb[0];
        float targetSat = targetHsb[1];
        float targetVal = targetHsb[2];
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int a = (p >>> 24) & 0xFF;
                if (a == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                int pr = (p >> 16) & 0xFF;
                int pg = (p >> 8) & 0xFF;
                int pb = p & 0xFF;
                float[] pxHsb = Color.RGBtoHSB(pr, pg, pb, null);
                float outSat;
                float outVal;
                if (targetSat < 0.08f) {
                    int lum = (int) (targetVal * pxHsb[2] * 255f);
                    lum = Math.min(255, Math.max(0, lum));
                    out.setRGB(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
                    continue;
                }
                outSat = Math.min(1f, targetSat * Math.max(0.35f, pxHsb[1]));
                outVal = Math.min(1f, targetVal * pxHsb[2]);
                int rgb = Color.HSBtoRGB(targetHue, outSat, outVal) & 0xFFFFFF;
                out.setRGB(x, y, (a << 24) | rgb);
            }
        }
        return out;
    }

    private static boolean failed(String reason) {
        lastUploadError = reason;
        log("applyStyleNow failed: " + reason);
        return false;
    }

    public static String getLastUploadError() {
        return lastUploadError;
    }

    /** Short renderer state for the opt-in Barrier Visibility diagnostic. */
    public static String debugStatus() {
        TextureAtlasSprite sprite = barrierSprite;
        String spriteName = "<none>";
        try {
            if (sprite != null) spriteName = String.valueOf(sprite.getIconName());
        } catch (Throwable ignored) {
            spriteName = "<unreadable>";
        }
        return "sprite=" + spriteName + " registered=" + (sprite != null)
                + " styleApplied=" + savedStyleApplied + " uploadErrorPresent="
                + (lastUploadError != null && !lastUploadError.isEmpty());
    }

    private static BufferedImage loadPngCached(ResourceLocation loc) {
        String key = loc.toString();
        BufferedImage hit = PNG_CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        synchronized (PNG_CACHE) {
            hit = PNG_CACHE.get(key);
            if (hit != null) {
                return hit;
            }
            BufferedImage decoded = loadPngUncached(loc);
            if (decoded != null) {
                PNG_CACHE.put(key, decoded);
            }
            return decoded;
        }
    }

    private static BufferedImage loadPngUncached(ResourceLocation loc) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            IResource r = mc.getResourceManager().getResource(loc);
            InputStream is = r.getInputStream();
            try {
                return ImageIO.read(is);
            } finally {
                try {
                    is.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("loadPng(" + loc + ") failed: " + t);
            return null;
        }
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    public static TextureAtlasSprite getBarrierSprite() {
        return barrierSprite;
    }

    private static void log(String msg) {
        System.out.println("[gomod][barrier-atlas] " + msg);
    }

    public BarrierVisibilityAtlasHook() {
    }
}
