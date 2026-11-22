package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Preview-only TNT atlas alpha (same idea as {@link BarrierVisibilityAtlasHook}): while the ghost
 * draws, TNT block sprites are temporarily uploaded with scaled texel alpha, then restored so
 * world TNT blocks are unchanged.
 */
public final class TntPlacementPreviewAtlasHook {

    private static final String[] TNT_SPRITE_KEYS = {
            "minecraft:blocks/tnt_top",
            "minecraft:blocks/tnt_bottom",
            "minecraft:blocks/tnt_side",
            "blocks/tnt_top",
            "blocks/tnt_bottom",
            "blocks/tnt_side",
    };

    private static final ThreadLocal<Integer> previewDepth = ThreadLocal.withInitial(() -> 0);
    private static int[][] cachedOriginalMip0;
    private static TextureAtlasSprite[] cachedSprites;

    private TntPlacementPreviewAtlasHook() {}

    public static boolean isPreviewRenderActive() {
        return previewDepth.get() > 0;
    }

    /** Call after resource/atlas reload so cached mip0 matches the new atlas. */
    public static void invalidateCache() {
        cachedOriginalMip0 = null;
        cachedSprites = null;
    }

    /** Capture true vanilla TNT mips right after stitch (before world-opacity atlas fade). */
    public static void captureVanillaSpritesFromAtlas(TextureMap map) {
        if (map == null) {
            invalidateCache();
            return;
        }
        cachedSprites = new TextureAtlasSprite[] {
                resolveSprite(map, "tnt_top"),
                resolveSprite(map, "tnt_bottom"),
                resolveSprite(map, "tnt_side"),
        };
        int[][] out = new int[3][];
        for (int i = 0; i < 3; i++) {
            TextureAtlasSprite sprite = cachedSprites[i];
            if (sprite == null || isMissing(sprite)) {
                continue;
            }
            try {
                int[][] frame = sprite.getFrameTextureData(0);
                if (frame != null && frame.length > 0 && frame[0] != null) {
                    out[i] = frame[0].clone();
                }
            } catch (Throwable ignored) {
            }
        }
        cachedOriginalMip0 = out;
    }

    /** Call immediately before ghost {@code renderBlock}; always pair with {@link #end()}. */
    public static void begin(float alpha) {
        int depth = previewDepth.get();
        previewDepth.set(depth + 1);
        if (depth > 0) {
            return;
        }
        int percent = Math.max(1, Math.min(100, Math.round(alpha * 100f)));
        if (percent >= 100) {
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
            ensureOriginalsCached(map);
            if (cachedOriginalMip0 == null) {
                return;
            }
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            for (int i = 0; i < cachedOriginalMip0.length; i++) {
                int[] original = cachedOriginalMip0[i];
                if (original == null) {
                    continue;
                }
                TextureAtlasSprite sprite = cachedSprites[i];
                if (sprite == null || isMissing(sprite)) {
                    continue;
                }
                int[] scaled = original.clone();
                multiplyMip0AlphaByPercent(scaled, percent);
                uploadSpriteMipChain(mc, sprite, scaled);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Restore vanilla TNT atlas pixels after the ghost draw. */
    public static void end() {
        int depth = previewDepth.get();
        if (depth <= 0) {
            previewDepth.set(0);
            return;
        }
        previewDepth.set(depth - 1);
        if (depth != 1) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || cachedOriginalMip0 == null) {
                return;
            }
            TextureMap map = mc.getTextureMapBlocks();
            if (map == null) {
                return;
            }
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            for (int i = 0; i < cachedOriginalMip0.length; i++) {
                int[] original = cachedOriginalMip0[i];
                if (original == null) {
                    continue;
                }
                TextureAtlasSprite sprite = cachedSprites[i];
                if (sprite == null || isMissing(sprite)) {
                    continue;
                }
                uploadSpriteMipChain(mc, sprite, original);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureOriginalsCached(TextureMap map) {
        if (cachedOriginalMip0 != null && cachedSprites != null) {
            return;
        }
        TextureAtlasSprite top = resolveSprite(map, "tnt_top");
        TextureAtlasSprite bottom = resolveSprite(map, "tnt_bottom");
        TextureAtlasSprite side = resolveSprite(map, "tnt_side");
        cachedSprites = new TextureAtlasSprite[] { top, bottom, side };
        int[][] out = new int[3][];
        for (int i = 0; i < 3; i++) {
            TextureAtlasSprite sprite = cachedSprites[i];
            if (sprite == null || isMissing(sprite)) {
                continue;
            }
            try {
                int[][] frame = sprite.getFrameTextureData(0);
                if (frame != null && frame.length > 0 && frame[0] != null) {
                    out[i] = frame[0].clone();
                }
            } catch (Throwable ignored) {
            }
        }
        cachedOriginalMip0 = out;
    }

    private static TextureAtlasSprite resolveSprite(TextureMap map, String baseName) {
        for (String key : TNT_SPRITE_KEYS) {
            if (!key.endsWith(baseName)) {
                continue;
            }
            TextureAtlasSprite sprite = map.getAtlasSprite(key);
            if (sprite != null && !isMissing(sprite)) {
                return sprite;
            }
        }
        return map.getAtlasSprite("minecraft:blocks/" + baseName);
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        String name = sprite.getIconName();
        return name != null && name.contains("missingno");
    }

    private static void uploadSpriteMipChain(Minecraft mc, TextureAtlasSprite sprite, int[] mip0) {
        int w = sprite.getIconWidth();
        int h = sprite.getIconHeight();
        if (w <= 0 || h <= 0 || mip0 == null || mip0.length != w * h) {
            return;
        }
        int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
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
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
        }
    }

    /** Premultiplied RGB so {@link net.minecraft.util.EnumWorldBlockLayer#TRANSLUCENT} blends correctly. */
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
