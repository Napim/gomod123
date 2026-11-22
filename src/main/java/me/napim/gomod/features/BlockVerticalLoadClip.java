package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

/**
 * Culls block/fluid mesh above the player's feet for a limited vertical span (see /bload).
 * Chunk data stays loaded; only chunk meshing skips geometry at/above the cutoff so it appears "not loaded".
 */
public final class BlockVerticalLoadClip {

    /** 0 = disabled. If N &gt; 0, block column Y is drawn only while {@code Y &lt; feetBlockY + N}. */
    private static volatile int clipHeightBlocks = 0;
    /** Feet block Y updated each client tick for chunk workers (volatile). */
    private static volatile int chunkBuildFeetBlockY = 0;

    private BlockVerticalLoadClip() {
    }

    public static void refreshChunkWorkerSafeState() {
        if (clipHeightBlocks <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        chunkBuildFeetBlockY = MathHelper.floor_double(mc.thePlayer.posY);
    }

    public static int getClipHeightBlocks() {
        return clipHeightBlocks;
    }

    /**
     * @param blocks 0 disables; 1–512 clamps. Only block columns with {@code Y &lt; floor(feetY) + blocks} are meshed.
     */
    public static void setClipHeightBlocks(int blocks) {
        clipHeightBlocks = Math.max(0, Math.min(512, blocks));
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            chunkBuildFeetBlockY = MathHelper.floor_double(mc.thePlayer.posY);
        }
    }

    public static boolean isEnabled() {
        return clipHeightBlocks > 0;
    }

    /**
     * Used from chunk mesh threads; must not touch {@link Minecraft} except via cached fields.
     */
    public static boolean shouldCullBlockForChunkMesh(BlockPos pos) {
        int h = clipHeightBlocks;
        if (h <= 0) {
            return false;
        }
        int limit = chunkBuildFeetBlockY + h;
        return pos.getY() >= limit;
    }
}
