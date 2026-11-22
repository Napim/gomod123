package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live water texture opacity — chunk vertex patching often no-ops on Lunar/worker threads; atlas alpha
 * matches the barrier / Block Finder world-opacity approach and updates immediately when the slider moves.
 */
public final class WaterClarityAtlasHook {

    private static final String[] WATER_SPRITE_KEYS = {
            "minecraft:blocks/water_still",
            "minecraft:blocks/water_flow",
            "blocks/water_still",
            "blocks/water_flow",
    };

    private static final ConcurrentHashMap<String, int[]> ORIGINAL_MIP0 = new ConcurrentHashMap<String, int[]>();

    private static volatile int appliedOpacityPercent = 100;

    public WaterClarityAtlasHook() {}

    public static int getAppliedOpacityPercent() {
        return appliedOpacityPercent;
    }

    public static boolean isAtlasAdjusted() {
        return appliedOpacityPercent < 100;
    }

    @SubscribeEvent
    public void onTextureStitchPost(TextureStitchEvent.Post event) {
        if (event.map != Minecraft.getMinecraft().getTextureMapBlocks()) {
            return;
        }
        ORIGINAL_MIP0.clear();
        appliedOpacityPercent = 100;
        syncFromConfig();
    }

    /** Apply slider/config to water sprites only. */
    public static void syncFromConfig() {
        if (!WaterClarity.isEnabled() || !WaterClarity.isSurfaceFromAirEnabled()) {
            restoreAll();
            return;
        }
        int percent = WaterClarity.getSurfaceOpacityPercent();
        applyOpacityPercent(percent);
    }

    public static void applyOpacityPercent(int percent) {
        percent = Math.max(0, Math.min(100, percent));
        if (percent == appliedOpacityPercent) {
            return;
        }
        if (percent >= 100) {
            restoreAll();
            appliedOpacityPercent = 100;
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            TextureMap map = mc.getTextureMapBlocks();
            if (map == null) {
                return;
            }
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            try {
                for (String key : WATER_SPRITE_KEYS) {
                    TextureAtlasSprite sprite = map.getAtlasSprite(key);
                    if (sprite != null && !isMissing(sprite)) {
                        uploadSpriteWithPercent(mc, sprite, percent);
                    }
                }
            } finally {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
            }
            appliedOpacityPercent = percent;
        } catch (Throwable ignored) {
        }
    }

    public static void restoreAll() {
        if (appliedOpacityPercent >= 100 && ORIGINAL_MIP0.isEmpty()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                appliedOpacityPercent = 100;
                return;
            }
            TextureMap map = mc.getTextureMapBlocks();
            if (map == null) {
                appliedOpacityPercent = 100;
                return;
            }
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            try {
                for (Map.Entry<String, int[]> entry : ORIGINAL_MIP0.entrySet()) {
                    TextureAtlasSprite sprite = map.getAtlasSprite(entry.getKey());
                    if (sprite != null && !isMissing(sprite)) {
                        uploadSpriteMipChain(mc, sprite, entry.getValue());
                    }
                }
            } finally {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
            }
            appliedOpacityPercent = 100;
        } catch (Throwable ignored) {
            appliedOpacityPercent = 100;
        }
    }

    private static void uploadSpriteWithPercent(Minecraft mc, TextureAtlasSprite sprite, int percent) {
        if (sprite == null || isMissing(sprite)) {
            return;
        }
        String name = sprite.getIconName();
        if (name == null) {
            return;
        }
        int[] original = ORIGINAL_MIP0.get(name);
        if (original == null) {
            original = captureMip0(sprite);
            if (original == null) {
                return;
            }
            ORIGINAL_MIP0.put(name, original);
        }
        int[] faded = original.clone();
        multiplyMip0AlphaByPercent(faded, percent);
        uploadSpriteMipChain(mc, sprite, faded);
    }

    private static int[] captureMip0(TextureAtlasSprite sprite) {
        try {
            int[][] frame = sprite.getFrameTextureData(0);
            if (frame != null && frame.length > 0 && frame[0] != null) {
                return frame[0].clone();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        String name = sprite.getIconName();
        return name != null && name.contains("missingno");
    }

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

    private static void uploadSpriteMipChain(Minecraft mc, TextureAtlasSprite sprite, int[] mip0) {
        int w = sprite.getIconWidth();
        int h = sprite.getIconHeight();
        if (w <= 0 || h <= 0 || mip0 == null || mip0.length != w * h) {
            return;
        }
        int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        try {
            int[] curPixels = mip0;
            int curW = w;
            int curH = h;
            int mipLevels = Math.max(0, mc.gameSettings.mipmapLevels);
            int originX = sprite.getOriginX();
            int originY = sprite.getOriginY();
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
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
        }
    }

    private static void uploadLevel(int level, int x, int y, int w, int h, int[] pixelsArgb) {
        IntBuffer pixels = ByteBuffer.allocateDirect(w * h * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        pixels.put(pixelsArgb);
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
                        sumG += ((p >> 8) & 0xFF) * a;
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
}
