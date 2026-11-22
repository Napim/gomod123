package me.napim.gomod.features;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Client-only water visuals: see into lakes from above by lowering surface-water opacity.
 */
public final class WaterClarity {

    private static final float MIN_FOG_DENSITY = 0.005f;

    /**
     * Chunk mesh workers call {@code renderBlock} off the main thread; a global depth counter races and can crash
     * (e.g. after F3+A). Per-thread depth + paired push/pop keeps nesting correct per worker.
     */
    private static final ThreadLocal<Integer> surfaceWaterPassDepth = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Deque<Boolean>> surfaceWaterPassPushed = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * {@link #isViewEntitySubmerged} touches the client world; must only run on the main thread. Chunk workers read
     * this volatile snapshot instead of calling {@link Minecraft#getMinecraft()} from a worker.
     */
    private static volatile boolean chunkBuildViewSubmergedCache = false;
    private static volatile boolean surfaceChunkTrackingActiveCache = false;
    private static boolean lastMainThreadViewSubmergedKnown = false;
    private static boolean lastMainThreadViewSubmerged = false;

    /** Current {@code BlockRendererDispatcher.renderBlock} state (stacked — render may recurse). */
    private static final ThreadLocal<ArrayDeque<IBlockState>> activeChunkRenderBlockStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Boolean>> trackedChunkRenderBlockStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Boolean>> trackedFluidRenderStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    /** Vertex baseline for {@code BlockRendererDispatcher.renderBlock} water path (Lunar-safe fallback). */
    private static final ThreadLocal<ArrayDeque<Integer>> chunkBlockVertexStartStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * {@link net.minecraft.client.renderer.BlockFluidRenderer#renderFluid} usually does not call {@code putColorMultiplier};
     * it uses {@code putColorRGB_F} / {@code color()} instead. We record {@link WorldRenderer#getVertexCount()} at fluid HEAD
     * and patch the alpha byte for every vertex appended before RETURN.
     */
    private static final ThreadLocal<ArrayDeque<Integer>> fluidMeshVertexStartStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static Field worldRendererByteBufferField;

    static {
        for (String n : new String[]{"byteBuffer", "field_178999_b", "field_179010_h", "field_78974_h"}) {
            try {
                Field f = WorldRenderer.class.getDeclaredField(n);
                if (ByteBuffer.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    worldRendererByteBufferField = f;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (worldRendererByteBufferField == null) {
            for (Field f : WorldRenderer.class.getDeclaredFields()) {
                if (ByteBuffer.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        worldRendererByteBufferField = f;
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private WaterClarity() {
    }

    public static boolean isEnabled() {
        return ConfigManager.isWaterClarityEnabled();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setWaterClarityEnabled(enabled);
        if (!enabled) {
            surfaceChunkTrackingActiveCache = false;
            chunkBuildViewSubmergedCache = false;
            lastMainThreadViewSubmergedKnown = false;
            WaterClarityAtlasHook.restoreAll();
        } else {
            WaterClarityAtlasHook.syncFromConfig();
        }
        reloadChunkMeshesForSurfaceClarity();
    }

    public static float getClarityAmount() {
        return ConfigManager.getWaterClarityAmount();
    }

    public static void setClarityAmount(float amount) {
        ConfigManager.setWaterClarityAmount(amount);
        WaterClarityAtlasHook.syncFromConfig();
    }

    /**
     * Rebuilds visible chunk meshes so surface-water alpha matches settings without tearing down every renderer.
     */
    public static void reloadChunkMeshesForSurfaceClarity() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.theWorld != null && mc.renderGlobal != null) {
                markVisibleChunksDirty(mc);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void markVisibleChunksDirty(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            view = mc.thePlayer;
        }
        if (view == null) {
            return;
        }
        int chunks = 8;
        try {
            if (mc.gameSettings != null) {
                chunks = Math.max(2, mc.gameSettings.renderDistanceChunks);
            }
        } catch (Throwable ignored) {
        }
        int radius = chunks * 16 + 32;
        BlockPos pos = view.getPosition();
        mc.theWorld.markBlockRangeForRenderUpdate(
                pos.getX() - radius, 0, pos.getZ() - radius,
                pos.getX() + radius, 255, pos.getZ() + radius);
    }

    public static boolean isSurfaceFromAirEnabled() {
        return ConfigManager.isWaterClaritySurfaceFromAirEnabled();
    }

    public static void setSurfaceFromAirEnabled(boolean v) {
        ConfigManager.setWaterClaritySurfaceFromAirEnabled(v);
    }

    public static boolean isUnderwaterFogTweakEnabled() {
        return ConfigManager.isWaterClarityUnderwaterFogEnabled();
    }

    public static void setUnderwaterFogTweakEnabled(boolean v) {
        ConfigManager.setWaterClarityUnderwaterFogEnabled(v);
    }

    /**
     * Multiplier for biome water tint alpha while rebuilding surface-water quads from air.
     * Higher clarity ({@link #getClarityAmount()}) means lower opacity.
     */
    public static float getSurfaceTintAlphaScale() {
        return getSurfaceTintAlphaScale(getClarityAmount());
    }

    public static float getSurfaceTintAlphaScale(float clarityAmount) {
        return clampUnit(1.0f - clampUnit(clarityAmount));
    }

    public static int getSurfaceOpacityPercent() {
        return getSurfaceOpacityPercent(getClarityAmount());
    }

    public static int getSurfaceOpacityPercent(float clarityAmount) {
        return Math.round(getSurfaceTintAlphaScale(clarityAmount) * 100.0f);
    }

    public static boolean isWaterState(IBlockState state) {
        if (state == null || state.getBlock() == null) {
            return false;
        }
        return state.getBlock() == Blocks.water || state.getBlock() == Blocks.flowing_water;
    }

    /**
     * Call once per client tick on the main thread ({@link net.minecraft.client.Minecraft#runTick}) so mesh workers
     * can safely read {@link #chunkBuildViewSubmergedCache}.
     */
    public static void refreshChunkWorkerSafeState() {
        if (!isEnabled() || !isSurfaceFromAirEnabled()) {
            chunkBuildViewSubmergedCache = false;
            surfaceChunkTrackingActiveCache = false;
            lastMainThreadViewSubmergedKnown = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean submerged = mc != null && isViewEntitySubmerged(mc);
        chunkBuildViewSubmergedCache = submerged;
        surfaceChunkTrackingActiveCache = !submerged;
        lastMainThreadViewSubmerged = submerged;
        lastMainThreadViewSubmergedKnown = true;
    }

    public static boolean shouldTrackChunkRenderState(IBlockState state) {
        return isSurfaceChunkTrackingActive() && isWaterState(state);
    }

    public static boolean isSurfaceChunkTrackingActive() {
        return surfaceChunkTrackingActiveCache;
    }

    public static void beginChunkRenderBlockIfNeeded(IBlockState state, WorldRenderer wr) {
        boolean track = shouldTrackChunkRenderState(state);
        trackedChunkRenderBlockStack.get().push(track);
        if (!track) {
            return;
        }
        pushActiveChunkRenderBlockState(state);
        onBeginRenderBlockWater(state);
        pushVertexBaseline(chunkBlockVertexStartStack, wr);
    }

    public static void endChunkRenderBlockIfNeeded(IBlockState state, WorldRenderer wr) {
        ArrayDeque<Boolean> stack = trackedChunkRenderBlockStack.get();
        if (stack.isEmpty()) {
            return;
        }
        boolean tracked = stack.pop();
        if (!tracked || !isWaterState(state)) {
            if (tracked) {
                pollVertexBaseline(chunkBlockVertexStartStack);
            }
            return;
        }
        try {
            patchSurfaceWaterVertexRange(wr, pollVertexBaseline(chunkBlockVertexStartStack), state);
            onEndRenderBlockWater(state);
        } finally {
            popActiveChunkRenderBlockState();
        }
    }

    public static void beginFluidRenderIfNeeded(IBlockState state, WorldRenderer wr) {
        boolean track = shouldTrackChunkRenderState(state);
        if (!track) {
            return;
        }
        trackedFluidRenderStack.get().push(Boolean.TRUE);
        pushActiveChunkRenderBlockState(state);
        onBeginRenderBlockWater(state);
        recordFluidMeshVertexBaseline(wr);
    }

    public static void endFluidRenderIfNeeded(WorldRenderer wr, IBlockState state) {
        if (!isWaterState(state)) {
            return;
        }
        ArrayDeque<Boolean> stack = trackedFluidRenderStack.get();
        if (stack.isEmpty() || !stack.pop()) {
            return;
        }
        try {
            applyFluidMeshVertexAlphaAfterRender(wr, state);
        } finally {
            try {
                onEndRenderBlockWater(state);
            } finally {
                popActiveChunkRenderBlockState();
            }
        }
    }

    public static void pushActiveChunkRenderBlockState(IBlockState state) {
        activeChunkRenderBlockStack.get().push(state);
    }

    public static void popActiveChunkRenderBlockState() {
        ArrayDeque<IBlockState> d = activeChunkRenderBlockStack.get();
        if (!d.isEmpty()) {
            d.pop();
        }
    }

    public static IBlockState peekActiveChunkRenderBlockState() {
        ArrayDeque<IBlockState> d = activeChunkRenderBlockStack.get();
        return d.isEmpty() ? null : d.peek();
    }

    /** Call at {@code BlockFluidRenderer.renderFluid} HEAD (after {@link #onBeginRenderBlockWater}) — before any fluid verts. */
    public static void recordFluidMeshVertexBaseline(WorldRenderer wr) {
        pushVertexBaseline(fluidMeshVertexStartStack, wr);
    }

    /**
     * Call at {@code BlockFluidRenderer.renderFluid} RETURN — patches vertex color alpha for this fluid's geometry.
     * This is the main path for water; {@code putColorMultiplier} is rarely used for fluids.
     */
    public static void applyFluidMeshVertexAlphaAfterRender(WorldRenderer wr, IBlockState state) {
        Integer start = pollVertexBaseline(fluidMeshVertexStartStack);
        patchSurfaceWaterVertexRange(wr, start, state);
    }

    private static void pushVertexBaseline(ThreadLocal<ArrayDeque<Integer>> stack, WorldRenderer wr) {
        if (wr == null) {
            stack.get().push(0);
            return;
        }
        try {
            stack.get().push(wr.getVertexCount());
        } catch (Throwable ignored) {
            stack.get().push(0);
        }
    }

    private static Integer pollVertexBaseline(ThreadLocal<ArrayDeque<Integer>> stack) {
        ArrayDeque<Integer> d = stack.get();
        return d.isEmpty() ? null : d.poll();
    }

    /**
     * Lowers alpha on water quads appended since {@code start} (chunk rebuild from above).
     */
    private static void patchSurfaceWaterVertexRange(WorldRenderer wr, Integer startBoxed, IBlockState state) {
        if (wr == null || startBoxed == null || !isWaterState(state) || !isEnabled() || !isSurfaceFromAirEnabled()) {
            return;
        }
        if (!isSurfaceChunkTrackingActive()) {
            return;
        }
        float scale = getSurfaceTintAlphaScale();
        if (scale >= 0.999f) {
            return;
        }
        int start = startBoxed;
        try {
            int end = wr.getVertexCount();
            if (end <= start) {
                return;
            }
            VertexFormat vf = wr.getVertexFormat();
            if (vf == null) {
                return;
            }
            int stride = vf.getNextOffset();
            int colorOff = colorElementByteOffset(vf);
            if (stride <= 0 || colorOff < 0) {
                return;
            }
            ByteBuffer buf = resolveWorldRendererByteBuffer(wr);
            if (buf == null) {
                return;
            }
            int na = Math.max(16, Math.min(255, Math.round(255f * scale)));
            int cap = buf.capacity();
            for (int vi = start; vi < end; vi++) {
                int aByte = vi * stride + colorOff + 3;
                if (aByte < 0 || aByte >= cap) {
                    continue;
                }
                buf.put(aByte, (byte) na);
            }
            me.napim.gomod.features.MixinStatus.waterClarityVertexPatches++;
        } catch (Throwable ignored) {
        }
    }

    private static int colorElementByteOffset(VertexFormat vf) {
        int offset = 0;
        for (int i = 0; i < vf.getElementCount(); i++) {
            VertexFormatElement el = vf.getElement(i);
            if (el.getUsage() == VertexFormatElement.EnumUsage.COLOR) {
                return offset;
            }
            offset += el.getSize();
        }
        return -1;
    }

    /** Exposed for mixins; same resolution as chunk mesh patching. */
    public static ByteBuffer resolveWorldRendererByteBufferPublic(WorldRenderer wr) {
        return resolveWorldRendererByteBuffer(wr);
    }

    private static ByteBuffer resolveWorldRendererByteBuffer(WorldRenderer wr) {
        try {
            ByteBuffer direct = wr.getByteBuffer();
            if (direct != null) {
                return direct;
            }
        } catch (Throwable ignored) {
        }
        if (worldRendererByteBufferField != null) {
            try {
                return (ByteBuffer) worldRendererByteBufferField.get(wr);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * After {@code putColorMultiplier}, patch water vertex alpha (some code paths still use this for water).
     */
    public static boolean shouldPatchWaterVertexAlpha() {
        if (!isSurfaceChunkTrackingActive()) {
            return false;
        }
        IBlockState st = peekActiveChunkRenderBlockState();
        if (st == null || !isWaterState(st)) {
            return false;
        }
        if (surfaceWaterPassDepth.get() <= 0) {
            return false;
        }
        return getSurfaceTintAlphaScale() < 0.999f;
    }

    public static void onBeginRenderBlockWater(IBlockState state) {
        boolean count = shouldIncrementSurfacePassForState(state);
        surfaceWaterPassPushed.get().push(count);
        if (count) {
            int d = surfaceWaterPassDepth.get();
            surfaceWaterPassDepth.set(d + 1);
        }
    }

    public static void onEndRenderBlockWater(IBlockState state) {
        Deque<Boolean> st = surfaceWaterPassPushed.get();
        if (st.isEmpty()) {
            return;
        }
        boolean count = st.pop();
        if (count) {
            int d = surfaceWaterPassDepth.get();
            if (d > 0) {
                surfaceWaterPassDepth.set(d - 1);
            }
        }
    }

    public static boolean shouldScaleSurfaceWaterTint(IBlockState state) {
        return isEnabled() && isSurfaceFromAirEnabled() && surfaceWaterPassDepth.get() > 0 && isWaterState(state);
    }

    private static boolean shouldIncrementSurfacePassForState(IBlockState state) {
        if (!isEnabled() || !isSurfaceFromAirEnabled() || !isWaterState(state)) {
            return false;
        }
        return !chunkBuildViewSubmergedCache;
    }

    /**
     * True when the render view is in water (no surface thinning; optional underwater tweaks use this too).
     */
    public static boolean isViewEntitySubmerged(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            view = mc.thePlayer;
        }
        if (view == null) {
            return false;
        }
        float pt = mc.timer != null ? mc.timer.renderPartialTicks : 1.0F;
        return isViewSubmergedAtPartialTicks(mc, view, pt);
    }

    public static boolean isViewSubmergedAtPartialTicks(Minecraft mc, Entity view, float partialTicks) {
        if (mc == null || mc.theWorld == null || view == null) {
            return false;
        }
        try {
            net.minecraft.block.Block b = ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, view, partialTicks);
            if (b != null && b.getMaterial() == Material.water) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // Do not use isInsideOfMaterial alone — standing in shallow water with eyes in air should still
        // get surface-water transparency when looking down into a lake (Lunar / third-person edge cases).
        return false;
    }

    /**
     * Mirrors vanilla's underwater fog baseline, then interpolates toward a clearer minimum.
     */
    public static float getUnderwaterFogDensity(EntityLivingBase entity) {
        if (entity == null) {
            return MIN_FOG_DENSITY;
        }
        float baseDensity;
        if (entity.isPotionActive(Potion.waterBreathing)) {
            baseDensity = 0.01f;
        } else {
            int respiration = EnchantmentHelper.getRespiration(entity);
            baseDensity = 0.1f - (float) respiration * 0.03f;
        }
        baseDensity = Math.max(MIN_FOG_DENSITY, baseDensity);
        float clarity = getClarityAmount();
        return baseDensity + (MIN_FOG_DENSITY - baseDensity) * clarity;
    }

    private static float clampUnit(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
