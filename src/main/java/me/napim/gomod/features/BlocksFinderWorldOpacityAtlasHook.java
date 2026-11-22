package me.napim.gomod.features;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block Finder world opacity — same pipeline as {@link BarrierVisibilityAtlasHook}:
 * premultiplied atlas alpha + {@link net.minecraft.util.EnumWorldBlockLayer#TRANSLUCENT}
 * (via {@link me.napim.gomod.mixins.BlockWorldOpacityLayerMixin} during terrain).
 */
public final class BlocksFinderWorldOpacityAtlasHook {

    private static final ConcurrentHashMap<String, int[]> ORIGINAL_MIP0 = new ConcurrentHashMap<String, int[]>();

    private static final ThreadLocal<Deque<Set<String>>> ITEM_GUI_SPRITE_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);
    /** Nesting depth for RenderItem calls while the faded atlas is temporarily restored. */
    private static final ThreadLocal<Integer> ITEM_RENDER_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Set<String>> HUD_ITEM_SPRITES =
            ThreadLocal.withInitial(HashSet::new);
    private static final ConcurrentHashMap<String, Set<String>> ITEM_SPRITE_CACHE =
            new ConcurrentHashMap<String, Set<String>>();
    private static volatile List<SpriteBounds> spriteBoundsCache = Collections.emptyList();

    /** Hotbar / inventory overlay: keep block atlas full-opacity for the whole pass (see {@link #endHudItemPass}). */
    private static final ThreadLocal<Integer> HUD_ITEM_PASS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static volatile int appliedPercent = 100;

    public BlocksFinderWorldOpacityAtlasHook() {}

    public static int getAppliedPercent() {
        return appliedPercent;
    }

    public static boolean isAtlasFaded() {
        return appliedPercent < 100;
    }

    public static boolean isItemGuiRestoreActive() {
        return isHudItemPassActive() || ITEM_RENDER_DEPTH.get() > 0 || !ITEM_GUI_SPRITE_STACK.get().isEmpty();
    }

    public static boolean isHudItemPassActive() {
        return HUD_ITEM_PASS_DEPTH.get() > 0;
    }

    /** Start of hotbar / survival overlay item drawing (paired with {@link #endHudItemPass}). */
    public static void beginHudItemPass() {
        int depth = HUD_ITEM_PASS_DEPTH.get();
        HUD_ITEM_PASS_DEPTH.set(depth + 1);
        if (depth == 0) {
            HUD_ITEM_SPRITES.get().clear();
        }
    }

    /** Re-apply world-opacity fade after hotbar / inventory item drawing. */
    public static void endHudItemPass() {
        int depth = HUD_ITEM_PASS_DEPTH.get();
        if (depth <= 0) {
            return;
        }
        depth--;
        HUD_ITEM_PASS_DEPTH.set(depth);
        if (depth == 0) {
            Set<String> sprites = HUD_ITEM_SPRITES.get();
            if (isAtlasFaded() && !sprites.isEmpty()) {
                refadeSpriteNames(sprites);
            }
            sprites.clear();
        }
    }

    /**
     * Restore full-opacity block sprites used by this inventory icon (paired with {@link #endItemGuiRender}).
     */
    public static void beginItemGuiRender(ItemStack stack) {
        beginItemGuiRender(stack, null);
    }

    public static void beginItemGuiRender(ItemStack stack, IBakedModel model) {
        if (!isAtlasFaded() || stack == null || stack.getItem() == null) {
            return;
        }
        int depth = ITEM_RENDER_DEPTH.get();
        ITEM_RENDER_DEPTH.set(depth + 1);
        if (depth > 0) {
            return;
        }
        Set<String> sprites = collectBlockAtlasSpriteNames(stack, model);
        ITEM_GUI_SPRITE_STACK.get().push(sprites);
        if (sprites.isEmpty()) {
            return;
        }
        restoreSpriteNames(sprites);
        if (isHudItemPassActive()) {
            HUD_ITEM_SPRITES.get().addAll(sprites);
        }
    }

    /**
     * Re-apply world-opacity fade for standalone item draws (mod GUIs). During {@link #beginHudItemPass},
     * fading is deferred to {@link #endHudItemPass} so hotbar icons stay opaque for the whole overlay pass.
     */
    public static void endItemGuiRender() {
        int depth = ITEM_RENDER_DEPTH.get();
        if (depth <= 0) {
            ITEM_GUI_SPRITE_STACK.get().clear();
            return;
        }
        depth--;
        ITEM_RENDER_DEPTH.set(depth);
        if (!isAtlasFaded()) {
            if (depth == 0) {
                ITEM_GUI_SPRITE_STACK.get().clear();
            }
            return;
        }
        if (depth > 0) {
            return;
        }
        Deque<Set<String>> stack = ITEM_GUI_SPRITE_STACK.get();
        Set<String> sprites = stack.poll();
        if (isHudItemPassActive()) {
            return;
        }
        if (sprites == null || sprites.isEmpty()) {
            return;
        }
        refadeSpriteNames(sprites);
    }

    @SubscribeEvent
    public void onTextureStitchPost(TextureStitchEvent.Post event) {
        if (event.map != Minecraft.getMinecraft().getTextureMapBlocks()) {
            return;
        }
        ORIGINAL_MIP0.clear();
        ITEM_SPRITE_CACHE.clear();
        spriteBoundsCache = Collections.emptyList();
        appliedPercent = 100;
        TntPlacementPreviewAtlasHook.captureVanillaSpritesFromAtlas(event.map);
        if (BlocksFinder.isEnabled()
                && ConfigManager.getBlocksFinderWorldOpacity() < BlocksFinder.WORLD_OPACITY_MAX) {
            applyPercent(ConfigManager.getBlocksFinderWorldOpacity());
        }
    }

    /** Apply or restore block-atlas opacity (call when slider changes, not every frame). */
    public static void applyPercent(int percent) {
        percent = Math.max(BlocksFinder.WORLD_OPACITY_MIN, Math.min(BlocksFinder.WORLD_OPACITY_MAX, percent));
        if (percent == appliedPercent) {
            return;
        }
        if (percent >= 100) {
            restoreAll();
            appliedPercent = 100;
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
                for (TextureAtlasSprite sprite : registeredSprites(map)) {
                    uploadSpriteWithPercent(mc, sprite, percent);
                }
            } finally {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
            }
            appliedPercent = percent;
            BlocksFinder.reloadChunkMeshesForWorldOpacity();
        } catch (Throwable ignored) {
        }
    }

    public static void restoreAll() {
        if (appliedPercent >= 100 && ORIGINAL_MIP0.isEmpty()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                appliedPercent = 100;
                return;
            }
            TextureMap map = mc.getTextureMapBlocks();
            if (map == null) {
                appliedPercent = 100;
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
            appliedPercent = 100;
            TntPlacementPreviewAtlasHook.invalidateCache();
            BlocksFinder.reloadChunkMeshesForWorldOpacity();
        } catch (Throwable ignored) {
            appliedPercent = 100;
        }
    }

    private static Set<String> collectBlockAtlasSpriteNames(ItemStack stack, IBakedModel suppliedModel) {
        String cacheKey = itemSpriteCacheKey(stack, suppliedModel);
        Set<String> cached = cacheKey == null ? null : ITEM_SPRITE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Set<String> names = new LinkedHashSet<String>();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getRenderItem() == null) {
                return names;
            }
            IBakedModel model = suppliedModel != null ? suppliedModel
                    : mc.getRenderItem().getItemModelMesher().getItemModel(stack);
            if (model == null) {
                return names;
            }
            addSpriteName(names, model.getParticleTexture());
            IBlockState state = null;
            Item item = stack.getItem();
            if (item instanceof ItemBlock) {
                Block block = ((ItemBlock) item).getBlock();
                if (block != null) {
                    state = block.getStateFromMeta(stack.getMetadata());
                }
            }
            for (EnumFacing facing : EnumFacing.values()) {
                collectModelQuadSprites(model, state, facing, names);
            }
            collectModelGeneralQuadSprites(model, names);
        } catch (Throwable ignored) {
        }
        Set<String> result = names.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(names));
        if (cacheKey != null) {
            ITEM_SPRITE_CACHE.put(cacheKey, result);
        }
        return result;
    }

    private static String itemSpriteCacheKey(ItemStack stack, IBakedModel model) {
        try {
            if (stack == null || stack.getItem() == null) {
                return null;
            }
            Object id = Item.itemRegistry.getNameForObject(stack.getItem());
            String itemId = id == null ? String.valueOf(Item.getIdFromItem(stack.getItem())) : String.valueOf(id);
            return itemId + "#" + stack.getMetadata() + "#" + System.identityHashCode(model);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void collectModelGeneralQuadSprites(IBakedModel model, Set<String> out) {
        try {
            for (Object quad : model.getGeneralQuads()) {
                addSpriteName(out, resolveQuadSprite(model, quad));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectModelQuadSprites(IBakedModel model, IBlockState state, EnumFacing facing,
                                                Set<String> out) {
        try {
            java.util.List<?> quads = model.getFaceQuads(facing);
            if (quads == null) {
                return;
            }
            for (Object quad : quads) {
                addSpriteName(out, resolveQuadSprite(model, quad));
            }
        } catch (Throwable ignored) {
        }
    }

    private static TextureAtlasSprite resolveQuadSprite(IBakedModel model, Object quad) {
        if (quad == null) {
            return null;
        }
        for (String method : new String[] {"getSprite", "func_178615_c"}) {
            try {
                java.lang.reflect.Method m = quad.getClass().getMethod(method);
                Object result = m.invoke(quad);
                if (result instanceof TextureAtlasSprite) {
                    return (TextureAtlasSprite) result;
                }
            } catch (Throwable ignored) {
            }
        }
        TextureAtlasSprite byUv = resolveQuadSpriteByUv(quad);
        if (byUv != null) {
            return byUv;
        }
        try {
            return model == null ? null : model.getParticleTexture();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TextureAtlasSprite resolveQuadSpriteByUv(Object quad) {
        try {
            java.lang.reflect.Method m = quad.getClass().getMethod("getVertexData");
            Object raw = m.invoke(quad);
            if (!(raw instanceof int[])) {
                return null;
            }
            int[] data = (int[]) raw;
            if (data.length < 28) {
                return null;
            }
            int stride = data.length / 4;
            if (stride <= 5) {
                stride = 7;
            }
            float minU = Float.MAX_VALUE;
            float maxU = -Float.MAX_VALUE;
            float minV = Float.MAX_VALUE;
            float maxV = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                if (base + 5 >= data.length) {
                    return null;
                }
                float u = Float.intBitsToFloat(data[base + 4]);
                float v = Float.intBitsToFloat(data[base + 5]);
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }
            return findSpriteContainingUv((minU + maxU) * 0.5F, (minV + maxV) * 0.5F,
                    minU, maxU, minV, maxV);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TextureAtlasSprite findSpriteContainingUv(float midU, float midV,
                                                             float minU, float maxU, float minV, float maxV) {
        TextureAtlasSprite best = null;
        float bestArea = Float.MAX_VALUE;
        for (SpriteBounds b : spriteBounds()) {
            if (b == null || b.sprite == null) {
                continue;
            }
            if (midU + 0.00001F < b.minU || midU - 0.00001F > b.maxU
                    || midV + 0.00001F < b.minV || midV - 0.00001F > b.maxV) {
                continue;
            }
            if (minU + 0.002F < b.minU || maxU - 0.002F > b.maxU
                    || minV + 0.002F < b.minV || maxV - 0.002F > b.maxV) {
                continue;
            }
            float area = (b.maxU - b.minU) * (b.maxV - b.minV);
            if (area < bestArea) {
                bestArea = area;
                best = b.sprite;
            }
        }
        return best;
    }

    private static List<SpriteBounds> spriteBounds() {
        List<SpriteBounds> cached = spriteBoundsCache;
        if (!cached.isEmpty()) {
            return cached;
        }
        List<SpriteBounds> out = new ArrayList<SpriteBounds>();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            TextureMap map = mc == null ? null : mc.getTextureMapBlocks();
            if (map != null) {
                for (TextureAtlasSprite sprite : registeredSprites(map)) {
                    if (sprite == null || isMissing(sprite)) {
                        continue;
                    }
                    String name = sprite.getIconName();
                    if (name == null || BlocksFinderWorldOpacityRules.shouldSkipSpriteFade(name)) {
                        continue;
                    }
                    out.add(new SpriteBounds(sprite));
                }
            }
        } catch (Throwable ignored) {
        }
        spriteBoundsCache = Collections.unmodifiableList(out);
        return spriteBoundsCache;
    }

    private static final class SpriteBounds {
        final TextureAtlasSprite sprite;
        final float minU;
        final float maxU;
        final float minV;
        final float maxV;

        SpriteBounds(TextureAtlasSprite sprite) {
            this.sprite = sprite;
            this.minU = Math.min(sprite.getMinU(), sprite.getMaxU());
            this.maxU = Math.max(sprite.getMinU(), sprite.getMaxU());
            this.minV = Math.min(sprite.getMinV(), sprite.getMaxV());
            this.maxV = Math.max(sprite.getMinV(), sprite.getMaxV());
        }
    }

    private static void addSpriteName(Set<String> out, TextureAtlasSprite sprite) {
        if (sprite == null) {
            return;
        }
        String name = sprite.getIconName();
        if (name == null || name.contains("missingno")) {
            return;
        }
        if (!BlocksFinderWorldOpacityRules.shouldSkipSpriteFade(name)) {
            out.add(name);
        }
    }

    private static void restoreSpriteNames(Set<String> sprites) {
        if (sprites == null || sprites.isEmpty()) {
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
            uploadOriginalsForSpriteNames(mc, map, sprites);
        } catch (Throwable ignored) {
        }
    }

    private static void refadeSpriteNames(Set<String> sprites) {
        if (sprites == null || sprites.isEmpty()) {
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
            uploadFadedForSpriteNames(mc, map, sprites, appliedPercent);
        } catch (Throwable ignored) {
        }
    }

    private static void uploadOriginalsForSpriteNames(Minecraft mc, TextureMap map, Set<String> spriteNames) {
        int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        try {
            for (String name : spriteNames) {
                TextureAtlasSprite sprite = map.getAtlasSprite(name);
                if (sprite == null || isMissing(sprite)) {
                    continue;
                }
                int[] original = ORIGINAL_MIP0.get(name);
                if (original == null) {
                    original = captureMip0(sprite);
                    if (original == null) {
                        continue;
                    }
                    ORIGINAL_MIP0.put(name, original);
                }
                uploadSpriteMipChain(mc, sprite, original);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
        }
    }

    /** Upload every stored original mip0 back to the block atlas (does not change {@link #appliedPercent}). */
    private static void restoreAllSpritesOnGpu() {
        if (ORIGINAL_MIP0.isEmpty()) {
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
            uploadOriginalsForSpriteNames(mc, map, ORIGINAL_MIP0.keySet());
        } catch (Throwable ignored) {
        }
    }

    /** Re-apply the current world-opacity fade to every registered block atlas sprite. */
    private static void refadeAllSpritesOnGpu(int percent) {
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
                for (TextureAtlasSprite sprite : registeredSprites(map)) {
                    uploadSpriteWithPercent(mc, sprite, percent);
                }
            } finally {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void uploadFadedForSpriteNames(Minecraft mc, TextureMap map, Set<String> spriteNames, int percent) {
        int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int prevRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        try {
            for (String name : spriteNames) {
                TextureAtlasSprite sprite = map.getAtlasSprite(name);
                if (sprite != null && !isMissing(sprite)) {
                    uploadSpriteWithPercent(mc, sprite, percent);
                }
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLength);
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
        if (BlocksFinderWorldOpacityRules.shouldSkipSpriteFade(name)) {
            uploadSpriteMipChain(mc, sprite, original);
            return;
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

    private static Iterable<TextureAtlasSprite> registeredSprites(TextureMap map) {
        for (String fieldName : new String[]{"mapRegisteredSprites", "field_110574_e"}) {
            try {
                Field f = TextureMap.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object raw = f.get(map);
                if (raw instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, TextureAtlasSprite> m = (Map<String, TextureAtlasSprite>) raw;
                    return m.values();
                }
            } catch (Throwable ignored) {
            }
        }
        return java.util.Collections.emptyList();
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        String name = sprite.getIconName();
        return name != null && name.contains("missingno");
    }

    /** Premultiplied RGBA — same as {@link BarrierVisibilityAtlasHook} for TRANSLUCENT terrain blend. */
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
