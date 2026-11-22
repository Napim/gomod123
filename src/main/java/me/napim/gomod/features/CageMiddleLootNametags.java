package me.napim.gomod.features;

import me.napim.gomod.render.LabelBillboardAngles;
import me.napim.gomod.util.GameContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * While in Blitz cages (and for 5s after cages open), shows estimated total loot above the
 * dense chest cluster near the player (middle 3×3 pile), scaled by chest count and
 * {@link BlitzChestItemsData} slot rates.
 */
public final class CageMiddleLootNametags {

    private static final long POST_OPEN_MS = 5_000L;
    private static final int RESCAN_INTERVAL_TICKS = 20;
    private static final int SCAN_RADIUS_H = 20;
    private static final int SCAN_RADIUS_V = 8;
    /** Chests within this Chebyshev distance (same layer ±1 Y) join one cluster. */
    private static final int CLUSTER_LINK_DIST = 2;
    private static final float LINE_STACK_Y = 0.24f;
    private static final double LABEL_BASE_OFFSET_Y = 1.15;

    private static boolean lastCageSidebar = false;
    private static long activeUntilMs = 0L;
    private static int rescanCooldownTicks = 0;

    private static BlockPos anchorPos = null;
    private static int middleChestCount = 0;
    private static List<BlitzChestLootEstimator.EstimateLine> cachedLines = Collections.emptyList();

    private CageMiddleLootNametags() {}

    public static void render(float partialTicks) {
        if (!ConfigManager.isCageMiddleLootEstimateEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.fontRendererObj == null) {
            return;
        }
        tickWindow(mc);
        if (!isFeatureActive()) {
            return;
        }
        if (rescanCooldownTicks <= 0) {
            rescanCooldownTicks = RESCAN_INTERVAL_TICKS;
            scanMiddleCluster(mc);
        } else {
            rescanCooldownTicks--;
        }
        if (anchorPos == null || cachedLines.isEmpty()) {
            return;
        }

        Entity cam = mc.getRenderViewEntity();
        if (cam == null) {
            return;
        }
        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        double ax = anchorPos.getX() + 0.5;
        double ay = anchorPos.getY() + LABEL_BASE_OFFSET_Y;
        double az = anchorPos.getZ() + 0.5;

        RenderManager rm = mc.getRenderManager();
        float py = rm.playerViewY;
        float px = LabelBillboardAngles.entityLabelPitchX(rm, mc);
        FontRenderer fr = mc.fontRendererObj;

        double dx = ax - cx;
        double dy = ay - cy;
        double dz = az - cz;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > 96 * 96) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);

        int rows = cachedLines.size();
        for (int row = 0; row < rows; row++) {
            String text = cachedLines.get(row).label;
            if (text == null || text.isEmpty()) {
                continue;
            }
            double rowDy = dy + row * LINE_STACK_Y;
            GlStateManager.pushMatrix();
            GlStateManager.translate(dx, rowDy, dz);
            GlStateManager.rotate(-py, 0f, 1f, 0f);
            GlStateManager.rotate(px, 1f, 0f, 0f);
            GlStateManager.scale(-0.026f, -0.026f, 0.026f);
            int w = fr.getStringWidth(text);
            fr.drawStringWithShadow(text, -w / 2, 0, 0xFFFFFF);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popMatrix();
        }

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private static void tickWindow(Minecraft mc) {
        if (!GameContext.isOnHypixel(mc)) {
            resetState();
            return;
        }
        boolean cageNow = GameContext.isBlitzCagePhaseSidebar(mc);
        long now = System.currentTimeMillis();
        if (cageNow) {
            activeUntilMs = 0L;
        } else if (lastCageSidebar && !cageNow) {
            activeUntilMs = now + POST_OPEN_MS;
            rescanCooldownTicks = 0;
        }
        lastCageSidebar = cageNow;
    }

    private static boolean isFeatureActive() {
        if (lastCageSidebar) {
            return true;
        }
        return activeUntilMs > 0L && System.currentTimeMillis() <= activeUntilMs;
    }

    private static void resetState() {
        lastCageSidebar = false;
        activeUntilMs = 0L;
        rescanCooldownTicks = 0;
        anchorPos = null;
        middleChestCount = 0;
        cachedLines = Collections.emptyList();
    }

    private static void scanMiddleCluster(Minecraft mc) {
        World world = mc.theWorld;
        BlockPos playerPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        List<BlockPos> chests = collectNearbyChests(world, playerPos);
        if (chests.isEmpty()) {
            anchorPos = null;
            middleChestCount = 0;
            cachedLines = Collections.emptyList();
            return;
        }
        List<BlockPos> cluster = largestCluster(chests, playerPos);
        if (cluster.isEmpty()) {
            anchorPos = null;
            middleChestCount = 0;
            cachedLines = Collections.emptyList();
            return;
        }
        middleChestCount = cluster.size();
        anchorPos = clusterCentroid(cluster);
        cachedLines = BlitzChestLootEstimator.buildEstimates(middleChestCount);
    }

    private static List<BlockPos> collectNearbyChests(World world, BlockPos origin) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        int minY = origin.getY() - SCAN_RADIUS_V;
        int maxY = origin.getY() + SCAN_RADIUS_V;
        for (Object obj : world.loadedTileEntityList) {
            if (!(obj instanceof TileEntityChest)) {
                continue;
            }
            TileEntity te = (TileEntity) obj;
            BlockPos pos = te.getPos();
            if (pos == null) {
                continue;
            }
            if (Math.abs(pos.getX() - origin.getX()) > SCAN_RADIUS_H
                    || Math.abs(pos.getZ() - origin.getZ()) > SCAN_RADIUS_H
                    || pos.getY() < minY || pos.getY() > maxY) {
                continue;
            }
            try {
                Block block = world.getBlockState(pos).getBlock();
                if (block instanceof BlockChest) {
                    out.add(pos);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<BlockPos> largestCluster(List<BlockPos> chests, BlockPos playerPos) {
        List<BlockPos> best = Collections.emptyList();
        long bestDist = Long.MAX_VALUE;
        Set<BlockPos> remaining = new HashSet<BlockPos>(chests);
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            List<BlockPos> cluster = growCluster(seed, remaining);
            long dist = clusterDistanceSq(cluster, playerPos);
            if (cluster.size() > best.size() || (cluster.size() == best.size() && dist < bestDist)) {
                best = cluster;
                bestDist = dist;
            }
        }
        return best;
    }

    private static long clusterDistanceSq(List<BlockPos> cluster, BlockPos playerPos) {
        if (cluster.isEmpty() || playerPos == null) {
            return Long.MAX_VALUE;
        }
        long sx = 0;
        long sz = 0;
        for (BlockPos p : cluster) {
            sx += p.getX();
            sz += p.getZ();
        }
        int n = cluster.size();
        double cx = sx / (double) n;
        double cz = sz / (double) n;
        double dx = cx - playerPos.getX();
        double dz = cz - playerPos.getZ();
        return (long) (dx * dx + dz * dz);
    }

    private static List<BlockPos> growCluster(BlockPos seed, Set<BlockPos> pool) {
        List<BlockPos> cluster = new ArrayList<BlockPos>();
        List<BlockPos> queue = new ArrayList<BlockPos>();
        queue.add(seed);
        pool.remove(seed);
        while (!queue.isEmpty()) {
            BlockPos current = queue.remove(queue.size() - 1);
            cluster.add(current);
            List<BlockPos> neighbors = new ArrayList<BlockPos>();
            for (BlockPos other : pool) {
                if (isClusterLinked(current, other)) {
                    neighbors.add(other);
                }
            }
            for (BlockPos n : neighbors) {
                pool.remove(n);
                queue.add(n);
            }
        }
        return cluster;
    }

    private static boolean isClusterLinked(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        if (dy > 1) {
            return false;
        }
        return Math.max(dx, dz) <= CLUSTER_LINK_DIST;
    }

    /** Integer block at rounded average; Y is top of cluster for label height. */
    private static BlockPos clusterCentroid(List<BlockPos> cluster) {
        long sx = 0;
        long sz = 0;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : cluster) {
            sx += p.getX();
            sz += p.getZ();
            if (p.getY() > maxY) {
                maxY = p.getY();
            }
        }
        int n = cluster.size();
        int cx = (int) Math.round(sx / (double) n);
        int cz = (int) Math.round(sz / (double) n);
        BlockPos closest = cluster.get(0);
        long bestDist = Long.MAX_VALUE;
        for (BlockPos p : cluster) {
            long d = (long) (p.getX() - cx) * (p.getX() - cx) + (long) (p.getZ() - cz) * (p.getZ() - cz);
            if (d < bestDist) {
                bestDist = d;
                closest = p;
            }
        }
        return new BlockPos(closest.getX(), maxY, closest.getZ());
    }
}
