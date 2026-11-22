package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weave/Lunar safety renderer for barriers. Lunar can keep vanilla barrier chunk rendering
 * invisible even after the model/resources are repaired, so this draws nearby barrier blocks
 * directly from the world render event.
 */
public final class BarrierVisibilityWeaveFallbackRenderer {

    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MAX_RENDERED_BARRIERS = 65536;
    /** Raw chunk sections are cheap to inspect; spreading this work avoids frame-time spikes. */
    private static final int STATIC_CHUNKS_PER_TICK = 8;
    private static final double MOVE_RESCAN_BLOCKS_SQ = 1.0D;

    private static List<BlockPos> cached = Collections.emptyList();
    private static Set<BlockPos> cachedSet = Collections.emptySet();
    private static int scanCooldown;
    private static double lastX = Double.NaN;
    private static double lastY = Double.NaN;
    private static double lastZ = Double.NaN;
    private static boolean lastRangeEnabled;
    private static int lastRange;
    private static boolean lastAimedOnly;
    private static BlockPos lastCursor;

    /* Static/all-visible mode cache. The old fallback scanned a 48-block sphere, which both
       hid farther barriers and performed hundreds of thousands of World#getBlockState calls.
       Scan the raw char arrays of loaded chunks incrementally instead. */
    private static final Map<Long, List<BlockPos>> staticChunkBarriers = new HashMap<Long, List<BlockPos>>();
    private static final ArrayDeque<Long> staticScanQueue = new ArrayDeque<Long>();
    private static final ArrayList<Long> staticDesiredChunks = new ArrayList<Long>();
    private static World staticWorld;
    private static int staticCenterChunkX = Integer.MIN_VALUE;
    private static int staticCenterChunkZ = Integer.MIN_VALUE;
    private static int staticRenderChunks = -1;

    private BarrierVisibilityWeaveFallbackRenderer() {}

    public static void clientTickEnd() {
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable ignored) {
            return;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || !BarrierVisibility.isEffectivelyEnabled()) {
            clear();
            return;
        }

        EntityPlayer player = mc.thePlayer;
        boolean rangeEnabled = ConfigManager.isBarrierVisibilityRangeEnabled();
        int range = rangeEnabled ? ConfigManager.getBarrierVisibilityRange() : 0;
        boolean aimedOnly = ConfigManager.isBarrierVisibilityAimedOnly();
        BlockPos cursor = BarrierVisibilityRangeTicker.getCursorTargetPos();

        if (!rangeEnabled && !aimedOnly) {
            updateStaticChunkCache(mc, player);
            lastRangeEnabled = false;
            lastRange = 0;
            lastAimedOnly = false;
            lastCursor = cursor;
            return;
        }
        clearStaticChunkCache();

        boolean first = Double.isNaN(lastX);
        boolean moved = !first && distanceSq(player.posX, player.posY, player.posZ, lastX, lastY, lastZ)
                >= MOVE_RESCAN_BLOCKS_SQ;
        boolean changed = first
                || moved
                || rangeEnabled != lastRangeEnabled
                || range != lastRange
                || aimedOnly != lastAimedOnly
                || !samePos(cursor, lastCursor);

        if (!changed && scanCooldown-- > 0) {
            return;
        }

        scanCooldown = SCAN_INTERVAL_TICKS;
        publish(scanBarriers(mc, player));
        lastX = player.posX;
        lastY = player.posY;
        lastZ = player.posZ;
        lastRangeEnabled = rangeEnabled;
        lastRange = range;
        lastAimedOnly = aimedOnly;
        lastCursor = cursor;
    }

    public static boolean render(float partialTicks) {
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable ignored) {
            BarrierVisibilityDebugCapture.sample("fallback.skip", "reason=minecraft-unavailable");
            return false;
        }
        if (mc == null) {
            BarrierVisibilityDebugCapture.sample("fallback.skip", "reason=no-minecraft");
            return false;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            BarrierVisibilityDebugCapture.sample("fallback.skip", "reason=no-world-or-player");
            return false;
        }
        if (!BarrierVisibility.isEffectivelyEnabled()) {
            BarrierVisibilityDebugCapture.sample("fallback.skip", "reason=not-effectively-enabled");
            return false;
        }
        List<BlockPos> positions = cached;
        Set<BlockPos> positionSet = cachedSet;
        if (positions == null || positions.isEmpty()) {
            BarrierVisibilityDebugCapture.sample("fallback.skip", "reason=no-cached-barriers count="
                    + (positions == null ? -1 : positions.size()));
            return false;
        }
        Entity cam = mc.getRenderViewEntity();
        if (cam == null) {
            BarrierVisibilityDebugCapture.sample("fallback.skip", "reason=no-camera-entity cached=" + positions.size());
            return false;
        }

        double camX = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double camY = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double camZ = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;
        List<BlockPos> visiblePositions = visiblePositions(mc.thePlayer, positions, camX, camY, camZ);
        if (visiblePositions.isEmpty()) {
            BarrierVisibilityDebugCapture.sample("fallback.skip", "reason=no-barriers-in-frustum cached=" + positions.size());
            return false;
        }

        boolean vanillaStyle = BarrierVisibilityStyles.isFull(ConfigManager.getBarrierVisibilityStyle());
        int argb = colorArgb();
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float opacity = Math.max(0.1f, Math.min(1.0f, ConfigManager.getBarrierVisibilityOpacityPercent() / 100.0f));
        float fillA = Math.min(0.28f, Math.max(0.10f, opacity * 0.22f));
        float lineA = Math.min(1.0f, Math.max(0.65f, opacity));

        BarrierVisibilityDebugCapture.sample("fallback.candidates", "cached=" + positions.size()
                + " inFrustum=" + visiblePositions.size() + " path=" + (vanillaStyle ? "vanilla-atlas" : "outline"));

        try {
            HudGlState.restoreBeforeWorldText();
        } catch (Throwable ignored) {
        }

        GlStateManager.pushMatrix();
        boolean drew = false;
        boolean renderedVanilla = false;
        try {
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GlStateManager.disableCull();

            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            renderedVanilla = vanillaStyle
                    && renderTexturedVanilla(mc, visiblePositions, positionSet, camX, camY, camZ, opacity, tess, wr);
            drew = renderedVanilla;
            if (vanillaStyle && !renderedVanilla) {
                BarrierVisibilityDebugCapture.sample("fallback.path", "vanilla-atlas-failed; using-outline-fallback");
            } else {
                BarrierVisibilityDebugCapture.sample("fallback.path", "path="
                        + (renderedVanilla ? "vanilla-atlas" : "outline") + " rendered=" + renderedVanilla);
            }
            if (!vanillaStyle || !renderedVanilla) {
                GlStateManager.disableTexture2D();
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                for (BlockPos pos : visiblePositions) {
                    drew = true;
                    AxisAlignedBB bb = expandedRenderBox(pos, camX, camY, camZ, 0.002D);
                    addExposedCubeQuads(wr, positionSet, pos, bb, r, g, b, fillA);
                }
                tess.draw();

                GL11.glLineWidth(Math.max(1.0f, ConfigManager.getBarrierOutlineThickness()));
                wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                for (BlockPos pos : visiblePositions) {
                    AxisAlignedBB bb = expandedRenderBox(pos, camX, camY, camZ, 0.003D);
                    addCubeLines(wr, bb, r, g, b, lineA);
                }
                tess.draw();
            }
        } catch (Throwable ignored) {
            drew = false;
            BarrierVisibilityDebugCapture.sample("fallback.error", "exception=" + ignored.getClass().getSimpleName());
        } finally {
            GlStateManager.popMatrix();
            BarrierVisibilityDebugCapture.sample("fallback.before-cleanup", "drawn=" + drew
                    + " vanilla=" + renderedVanilla);
            restoreGlAfterBarrier(mc);
            BarrierVisibilityDebugCapture.sample("fallback.after-cleanup", "drawn=" + drew
                    + " vanilla=" + renderedVanilla);
        }
        return drew;
    }

    /** Cull once per frame and share the result between fill/texture and outline passes. */
    private static List<BlockPos> visiblePositions(EntityPlayer player, List<BlockPos> positions,
                                                   double camX, double camY, double camZ) {
        Frustum frustum = new Frustum();
        frustum.setPosition(camX, camY, camZ);
        ArrayList<BlockPos> visible = new ArrayList<BlockPos>(Math.min(positions.size(), 4096));
        for (BlockPos pos : positions) {
            if (pos == null || !shouldRenderBarrier(player, pos)) {
                continue;
            }
            AxisAlignedBB worldBox = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D);
            if (frustum.isBoundingBoxInFrustum(worldBox)) {
                visible.add(pos);
            }
        }
        return visible;
    }

    private static void restoreGlAfterBarrier(Minecraft mc) {
        HudGlState.restoreForFirstPersonHand();
        // The textured fallback lowers the alpha-test threshold so the vanilla barrier
        // sprite keeps its anti-aliased edges. The hand pass runs immediately after this
        // event; leaving that threshold behind makes the first-person arm's translucent
        // pixels/shading differ depending on whether a barrier was in the camera frustum.
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glLineWidth(1.0f);
        if (mc.getFramebuffer() != null) {
            mc.getFramebuffer().bindFramebuffer(false);
        }
    }

    private static boolean renderTexturedVanilla(Minecraft mc, List<BlockPos> positions, Set<BlockPos> positionSet,
                                                 double camX, double camY, double camZ, float opacity,
                                                 Tessellator tess, WorldRenderer wr) {
        TextureAtlasSprite sprite = barrierSprite(mc);
        if (sprite == null) {
            BarrierVisibilityDebugCapture.sample("fallback.vanilla-sprite", "sprite=null");
            return false;
        }
        boolean missing = isMissing(sprite);
        BarrierVisibilityDebugCapture.sample("fallback.vanilla-sprite", "sprite=" + sprite.getIconName()
                + " missing=" + missing + " atlas=" + BarrierVisibilityAtlasHook.debugStatus());
        if (missing) {
            return false;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01F);
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        GlStateManager.color(1.0f, 1.0f, 1.0f, opacity);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        boolean drew = false;
        for (BlockPos pos : positions) {
            drew = true;
            AxisAlignedBB bb = expandedRenderBox(pos, camX, camY, camZ, 0.002D);
            addExposedTexturedCubeQuads(wr, positionSet, pos, bb, sprite, opacity);
        }
        tess.draw();
        return drew;
    }

    private static List<BlockPos> scanBarriers(Minecraft mc, EntityPlayer player) {
        if (ConfigManager.isBarrierVisibilityAimedOnly()) {
            return scanAimedOnlyBarriers(mc, player);
        }

        int radius = Math.max(2, ConfigManager.getBarrierVisibilityRange() + 2);
        int centerChunkX = ((int) Math.floor(player.posX)) >> 4;
        int centerChunkZ = ((int) Math.floor(player.posZ)) >> 4;
        int chunkRadius = (radius + 15) >> 4;
        ArrayList<BlockPos> out = new ArrayList<BlockPos>();

        addCursorRevealBarriers(mc, player, out, false);
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                Chunk chunk = loadedChunk(mc.theWorld, chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                appendChunkBarriers(chunk, out, player, true);
                if (out.size() >= MAX_RENDERED_BARRIERS) {
                    return out;
                }
            }
        }
        return out;
    }

    private static List<BlockPos> scanAimedOnlyBarriers(Minecraft mc, EntityPlayer player) {
        ArrayList<BlockPos> out = new ArrayList<BlockPos>();
        addCursorRevealBarriers(mc, player, out, true);
        return out;
    }

    private static void addCursorRevealBarriers(Minecraft mc, EntityPlayer player, ArrayList<BlockPos> out,
                                                boolean forceCursorTracking) {
        if (!forceCursorTracking && !ConfigManager.isBarrierVisibilityShowAtCursor()) {
            return;
        }
        BlockPos cursor = BarrierVisibilityRangeTicker.getCursorTargetPos();
        if (cursor == null) {
            return;
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int r = BarrierVisibilityRangeTicker.CURSOR_RADIUS;
        for (int x = cursor.getX() - r; x <= cursor.getX() + r; x++) {
            for (int y = Math.max(0, cursor.getY() - r); y <= Math.min(255, cursor.getY() + r); y++) {
                for (int z = cursor.getZ() - r; z <= cursor.getZ() + r; z++) {
                    probe.set(x, y, z);
                    if (isBarrier(mc.theWorld, probe) && shouldRenderBarrier(player, probe)) {
                        out.add(new BlockPos(x, y, z));
                        if (out.size() >= MAX_RENDERED_BARRIERS) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static void updateStaticChunkCache(Minecraft mc, EntityPlayer player) {
        int centerX = ((int) Math.floor(player.posX)) >> 4;
        int centerZ = ((int) Math.floor(player.posZ)) >> 4;
        int renderChunks;
        try {
            renderChunks = Math.max(2, mc.gameSettings.renderDistanceChunks);
        } catch (Throwable ignored) {
            renderChunks = 8;
        }

        if (staticWorld != mc.theWorld || staticCenterChunkX != centerX || staticCenterChunkZ != centerZ
                || staticRenderChunks != renderChunks || staticDesiredChunks.isEmpty()) {
            rebuildStaticDesiredChunks(mc.theWorld, centerX, centerZ, renderChunks);
        }
        if (staticScanQueue.isEmpty()) {
            // Continuous low-cost refresh picks up newly received chunks and block changes.
            staticScanQueue.addAll(staticDesiredChunks);
        }

        boolean changed = false;
        for (int i = 0; i < STATIC_CHUNKS_PER_TICK && !staticScanQueue.isEmpty(); i++) {
            long key = staticScanQueue.removeFirst();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            Chunk chunk = loadedChunk(mc.theWorld, chunkX, chunkZ);
            List<BlockPos> next = chunk == null ? null : scanLoadedChunk(chunk);
            List<BlockPos> previous = staticChunkBarriers.get(key);
            if (next == null) {
                if (previous != null) {
                    staticChunkBarriers.remove(key);
                    changed = true;
                }
            } else if (!next.equals(previous)) {
                staticChunkBarriers.put(key, next);
                changed = true;
            }
        }
        if (changed || cached.isEmpty()) {
            publishStaticCache();
        }

        lastX = player.posX;
        lastY = player.posY;
        lastZ = player.posZ;
    }

    private static void rebuildStaticDesiredChunks(World world, final int centerX, final int centerZ,
                                                   int renderChunks) {
        staticWorld = world;
        staticCenterChunkX = centerX;
        staticCenterChunkZ = centerZ;
        staticRenderChunks = renderChunks;
        staticDesiredChunks.clear();
        for (int x = centerX - renderChunks; x <= centerX + renderChunks; x++) {
            for (int z = centerZ - renderChunks; z <= centerZ + renderChunks; z++) {
                staticDesiredChunks.add(chunkKey(x, z));
            }
        }
        Collections.sort(staticDesiredChunks, new Comparator<Long>() {
            @Override
            public int compare(Long a, Long b) {
                int ax = (int) (a.longValue() >> 32) - centerX;
                int az = (int) a.longValue() - centerZ;
                int bx = (int) (b.longValue() >> 32) - centerX;
                int bz = (int) b.longValue() - centerZ;
                return Integer.compare(ax * ax + az * az, bx * bx + bz * bz);
            }
        });
        HashSet<Long> desired = new HashSet<Long>(staticDesiredChunks);
        boolean removed = staticChunkBarriers.keySet().retainAll(desired);
        staticScanQueue.clear();
        staticScanQueue.addAll(staticDesiredChunks);
        if (removed) {
            publishStaticCache();
        }
    }

    private static List<BlockPos> scanLoadedChunk(Chunk chunk) {
        ArrayList<BlockPos> found = new ArrayList<BlockPos>();
        appendChunkBarriers(chunk, found, null, false);
        return found.isEmpty() ? Collections.<BlockPos>emptyList() : found;
    }

    /** Scan packed block-state ids directly: no allocation or getBlockState call for non-barriers. */
    private static void appendChunkBarriers(Chunk chunk, ArrayList<BlockPos> out, EntityPlayer player,
                                            boolean applyVisibilityFilter) {
        if (chunk == null || out.size() >= MAX_RENDERED_BARRIERS) {
            return;
        }
        int barrierStateId = Block.BLOCK_STATE_IDS.get(Blocks.barrier.getDefaultState());
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        if (sections == null) {
            return;
        }
        int baseX = chunk.xPosition << 4;
        int baseZ = chunk.zPosition << 4;
        for (ExtendedBlockStorage section : sections) {
            if (section == null || section.isEmpty()) {
                continue;
            }
            char[] data = section.getData();
            if (data == null) {
                continue;
            }
            int baseY = section.getYLocation();
            for (int index = 0; index < data.length; index++) {
                if (data[index] != (char) barrierStateId) {
                    continue;
                }
                BlockPos pos = new BlockPos(baseX + (index & 15), baseY + ((index >> 8) & 15),
                        baseZ + ((index >> 4) & 15));
                if (applyVisibilityFilter && !shouldRenderBarrier(player, pos)) {
                    continue;
                }
                if (isInsideCursorReveal(pos) && containsPos(out, pos)) {
                    continue;
                }
                out.add(pos);
                if (out.size() >= MAX_RENDERED_BARRIERS) {
                    return;
                }
            }
        }
    }

    private static Chunk loadedChunk(World world, int chunkX, int chunkZ) {
        try {
            Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
            return chunk != null && chunk.isLoaded() ? chunk : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void publishStaticCache() {
        ArrayList<BlockPos> all = new ArrayList<BlockPos>();
        for (Long key : staticDesiredChunks) {
            List<BlockPos> positions = staticChunkBarriers.get(key);
            if (positions == null || positions.isEmpty()) {
                continue;
            }
            int room = MAX_RENDERED_BARRIERS - all.size();
            if (room <= 0) {
                break;
            }
            if (positions.size() <= room) {
                all.addAll(positions);
            } else {
                all.addAll(positions.subList(0, room));
            }
        }
        publish(all);
    }

    private static void publish(List<BlockPos> positions) {
        cached = positions == null ? Collections.<BlockPos>emptyList() : positions;
        cachedSet = cached.isEmpty() ? Collections.<BlockPos>emptySet() : new HashSet<BlockPos>(cached);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static boolean shouldRenderBarrier(EntityPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return false;
        }
        if (ConfigManager.isBarrierVisibilityAimedOnly()) {
            return cursorRevealActive() && isInsideCursorRevealCube(pos);
        }
        if (ConfigManager.isBarrierVisibilityRangeEnabled()
                && !isInsideCursorReveal(pos)
                && isOutsideConfiguredRange(player, pos)) {
            return false;
        }
        return true;
    }

    private static boolean isOutsideConfiguredRange(EntityPlayer player, BlockPos pos) {
        double dx = (pos.getX() + 0.5D) - player.posX;
        double dy = (pos.getY() + 0.5D) - player.posY;
        double dz = (pos.getZ() + 0.5D) - player.posZ;
        int range = ConfigManager.getBarrierVisibilityRange();
        return dx * dx + dy * dy + dz * dz > (double) range * range;
    }

    private static boolean isInsideCursorReveal(BlockPos barrierPos) {
        if (!ConfigManager.isBarrierVisibilityShowAtCursor()) {
            return false;
        }
        return cursorRevealActive() && isInsideCursorRevealCube(barrierPos);
    }

    private static boolean cursorRevealActive() {
        return BarrierVisibilityRangeTicker.getCursorTargetPos() != null;
    }

    private static boolean isInsideCursorRevealCube(BlockPos barrierPos) {
        BlockPos cursor = BarrierVisibilityRangeTicker.getCursorTargetPos();
        if (cursor == null) {
            return false;
        }
        int adx = Math.abs(barrierPos.getX() - cursor.getX());
        int ady = Math.abs(barrierPos.getY() - cursor.getY());
        int adz = Math.abs(barrierPos.getZ() - cursor.getZ());
        int r = BarrierVisibilityRangeTicker.CURSOR_RADIUS;
        return adx <= r && ady <= r && adz <= r;
    }

    private static boolean isBarrier(World world, BlockPos pos) {
        try {
            if (world == null || pos == null) {
                return false;
            }
            IBlockState state = world.getBlockState(pos);
            return state != null && state.getBlock() == Blocks.barrier;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static AxisAlignedBB expandedRenderBox(BlockPos pos, double camX, double camY, double camZ, double eps) {
        return new AxisAlignedBB(
                pos.getX() - camX - eps,
                pos.getY() - camY - eps,
                pos.getZ() - camZ - eps,
                pos.getX() + 1.0D - camX + eps,
                pos.getY() + 1.0D - camY + eps,
                pos.getZ() + 1.0D - camZ + eps);
    }

    private static TextureAtlasSprite barrierSprite(Minecraft mc) {
        try {
            TextureMap map = mc.getTextureMapBlocks();
            if (map == null) {
                return null;
            }
            // Match the block model and atlas hook. The separate item sprite can remain
            // vanilla red after the block sprite has been recolored, masking Vanilla tint
            // changes whenever this fallback draws over the native block.
            TextureAtlasSprite sprite = map.getAtlasSprite("minecraft:blocks/barrier");
            if (sprite != null && !isMissing(sprite)) {
                return sprite;
            }
            sprite = map.getAtlasSprite("blocks/barrier");
            if (sprite != null && !isMissing(sprite)) {
                return sprite;
            }
            sprite = map.getAtlasSprite("minecraft:items/barrier");
            if (sprite != null && !isMissing(sprite)) {
                return sprite;
            }
            return map.getAtlasSprite("items/barrier");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return true;
        }
        String name = sprite.getIconName();
        return name != null && name.contains("missingno");
    }

    private static void addExposedTexturedCubeQuads(WorldRenderer wr, Set<BlockPos> barriers, BlockPos pos, AxisAlignedBB bb,
                                                    TextureAtlasSprite sprite, float a) {
        if (!barriers.contains(pos.down())) {
            addTexturedFaceY(wr, bb.minY, bb.minX, bb.maxX, bb.minZ, bb.maxZ, sprite, a, false);
        }
        if (!barriers.contains(pos.up())) {
            addTexturedFaceY(wr, bb.maxY, bb.minX, bb.maxX, bb.minZ, bb.maxZ, sprite, a, true);
        }
        if (!barriers.contains(pos.north())) {
            addTexturedFaceZ(wr, bb.minZ, bb.minX, bb.maxX, bb.minY, bb.maxY, sprite, a, false);
        }
        if (!barriers.contains(pos.south())) {
            addTexturedFaceZ(wr, bb.maxZ, bb.minX, bb.maxX, bb.minY, bb.maxY, sprite, a, true);
        }
        if (!barriers.contains(pos.west())) {
            addTexturedFaceX(wr, bb.minX, bb.minZ, bb.maxZ, bb.minY, bb.maxY, sprite, a, false);
        }
        if (!barriers.contains(pos.east())) {
            addTexturedFaceX(wr, bb.maxX, bb.minZ, bb.maxZ, bb.minY, bb.maxY, sprite, a, true);
        }
    }

    private static void addTexturedFaceY(WorldRenderer wr, double y, double x1, double x2, double z1, double z2,
                                         TextureAtlasSprite s, float a, boolean top) {
        float u1 = s.getMinU();
        float u2 = s.getMaxU();
        float v1 = s.getMinV();
        float v2 = s.getMaxV();
        if (top) {
            texturedVertex(wr, x1, y, z1, u1, v1, a);
            texturedVertex(wr, x1, y, z2, u1, v2, a);
            texturedVertex(wr, x2, y, z2, u2, v2, a);
            texturedVertex(wr, x2, y, z1, u2, v1, a);
        } else {
            texturedVertex(wr, x1, y, z1, u1, v1, a);
            texturedVertex(wr, x2, y, z1, u2, v1, a);
            texturedVertex(wr, x2, y, z2, u2, v2, a);
            texturedVertex(wr, x1, y, z2, u1, v2, a);
        }
    }

    private static void addTexturedFaceZ(WorldRenderer wr, double z, double x1, double x2, double y1, double y2,
                                         TextureAtlasSprite s, float a, boolean south) {
        float u1 = s.getMinU();
        float u2 = s.getMaxU();
        float v1 = s.getMinV();
        float v2 = s.getMaxV();
        if (south) {
            texturedVertex(wr, x1, y1, z, u1, v2, a);
            texturedVertex(wr, x2, y1, z, u2, v2, a);
            texturedVertex(wr, x2, y2, z, u2, v1, a);
            texturedVertex(wr, x1, y2, z, u1, v1, a);
        } else {
            texturedVertex(wr, x1, y1, z, u2, v2, a);
            texturedVertex(wr, x1, y2, z, u2, v1, a);
            texturedVertex(wr, x2, y2, z, u1, v1, a);
            texturedVertex(wr, x2, y1, z, u1, v2, a);
        }
    }

    private static void addTexturedFaceX(WorldRenderer wr, double x, double z1, double z2, double y1, double y2,
                                         TextureAtlasSprite s, float a, boolean east) {
        float u1 = s.getMinU();
        float u2 = s.getMaxU();
        float v1 = s.getMinV();
        float v2 = s.getMaxV();
        if (east) {
            texturedVertex(wr, x, y1, z1, u1, v2, a);
            texturedVertex(wr, x, y2, z1, u1, v1, a);
            texturedVertex(wr, x, y2, z2, u2, v1, a);
            texturedVertex(wr, x, y1, z2, u2, v2, a);
        } else {
            texturedVertex(wr, x, y1, z1, u2, v2, a);
            texturedVertex(wr, x, y1, z2, u1, v2, a);
            texturedVertex(wr, x, y2, z2, u1, v1, a);
            texturedVertex(wr, x, y2, z1, u2, v1, a);
        }
    }

    private static void texturedVertex(WorldRenderer wr, double x, double y, double z, float u, float v, float a) {
        wr.pos(x, y, z).tex(u, v).color(1.0f, 1.0f, 1.0f, a).endVertex();
    }

    private static void addExposedCubeQuads(WorldRenderer wr, Set<BlockPos> barriers, BlockPos pos, AxisAlignedBB bb,
                                            float r, float g, float b, float a) {
        if (!barriers.contains(pos.down())) {
            addFaceY(wr, bb.minY, bb.minX, bb.maxX, bb.minZ, bb.maxZ, r, g, b, a);
        }
        if (!barriers.contains(pos.up())) {
            addFaceY(wr, bb.maxY, bb.minX, bb.maxX, bb.minZ, bb.maxZ, r, g, b, a);
        }
        if (!barriers.contains(pos.north())) {
            addFaceZ(wr, bb.minZ, bb.minX, bb.maxX, bb.minY, bb.maxY, r, g, b, a);
        }
        if (!barriers.contains(pos.south())) {
            addFaceZ(wr, bb.maxZ, bb.minX, bb.maxX, bb.minY, bb.maxY, r, g, b, a);
        }
        if (!barriers.contains(pos.west())) {
            addFaceX(wr, bb.minX, bb.minZ, bb.maxZ, bb.minY, bb.maxY, r, g, b, a);
        }
        if (!barriers.contains(pos.east())) {
            addFaceX(wr, bb.maxX, bb.minZ, bb.maxZ, bb.minY, bb.maxY, r, g, b, a);
        }
    }

    private static void addFaceY(WorldRenderer wr, double y, double x1, double x2, double z1, double z2,
                                 float r, float g, float b, float a) {
        wr.pos(x1, y, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y, z2).color(r, g, b, a).endVertex();
    }

    private static void addFaceZ(WorldRenderer wr, double z, double x1, double x2, double y1, double y2,
                                 float r, float g, float b, float a) {
        wr.pos(x1, y1, z).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z).color(r, g, b, a).endVertex();
    }

    private static void addFaceX(WorldRenderer wr, double x, double z1, double z2, double y1, double y2,
                                 float r, float g, float b, float a) {
        wr.pos(x, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x, y1, z2).color(r, g, b, a).endVertex();
    }

    private static void addCubeLines(WorldRenderer wr, AxisAlignedBB bb, float r, float g, float b, float a) {
        addLine(wr, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ, r, g, b, a);
        addLine(wr, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, r, g, b, a);
        addLine(wr, bb.maxX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.maxZ, r, g, b, a);
        addLine(wr, bb.minX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.minZ, r, g, b, a);
        addLine(wr, bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, r, g, b, a);
        addLine(wr, bb.maxX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, r, g, b, a);
        addLine(wr, bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ, r, g, b, a);
        addLine(wr, bb.minX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.minZ, r, g, b, a);
        addLine(wr, bb.minX, bb.minY, bb.minZ, bb.minX, bb.maxY, bb.minZ, r, g, b, a);
        addLine(wr, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, r, g, b, a);
        addLine(wr, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ, r, g, b, a);
        addLine(wr, bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ, r, g, b, a);
    }

    private static void addLine(WorldRenderer wr, double x1, double y1, double z1, double x2, double y2, double z2,
                                float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
    }

    private static int colorArgb() {
        return ConfigManager.getBarrierOutlineColorArgb();
    }

    private static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean samePos(BlockPos a, BlockPos b) {
        return a == b || (a != null && a.equals(b));
    }

    private static boolean containsPos(List<BlockPos> positions, BlockPos probe) {
        if (positions == null || probe == null) {
            return false;
        }
        for (BlockPos pos : positions) {
            if (probe.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private static void clear() {
        publish(Collections.<BlockPos>emptyList());
        clearStaticChunkCache();
        scanCooldown = 0;
        lastX = Double.NaN;
        lastY = Double.NaN;
        lastZ = Double.NaN;
        lastCursor = null;
    }

    private static void clearStaticChunkCache() {
        staticChunkBarriers.clear();
        staticScanQueue.clear();
        staticDesiredChunks.clear();
        staticWorld = null;
        staticCenterChunkX = Integer.MIN_VALUE;
        staticCenterChunkZ = Integer.MIN_VALUE;
        staticRenderChunks = -1;
    }
}
