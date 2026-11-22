package me.napim.gomod.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Snaps a Y coordinate to the top of a walkable surface at (x,z), searching only
 * near {@code hintY} so we don't jump to roofs or wrong floors.
 */
public final class RouteGroundSnap {

    private static final double SURFACE_EPS = 0.055;
    /** ~3 texture pixels above block top (3/16 block) so the line visibly floats on surfaces. */
    private static final double ROUTE_TOP_EPS = 3.0 / 16.0;
    /** Prefer walkable tops at or slightly above this band below {@code hintY} (player-ish height). */
    private static final double HINT_BELOW_BAND = 1.35;

    private RouteGroundSnap() {
    }

    private static boolean blocksMovement(World world, BlockPos p) {
        if (world == null || p == null) {
            return false;
        }
        IBlockState st = world.getBlockState(p);
        return st.getBlock().getMaterial().blocksMovement();
    }

    private static double blockTopOffset(World world, BlockPos p) {
        if (world == null || p == null) {
            return 1.0;
        }
        try {
            IBlockState st = world.getBlockState(p);
            net.minecraft.block.Block b = st.getBlock();
            // 1.8.9 block bounds are stateful; set from world to read slab/stair heights correctly.
            b.setBlockBoundsBasedOnState(world, p);
            double maxY = b.getBlockBoundsMaxY();
            if (maxY <= 0.0) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, maxY));
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /**
     * Solid block underfoot with at least two non-blocking cells above (standing headroom),
     * so thin ceilings / roof slabs are not treated as the floor.
     */
    private static boolean isWalkableSurface(World world, BlockPos feet) {
        if (!blocksMovement(world, feet)) {
            return false;
        }
        BlockPos head = feet.up();
        // Allow low-profile walkable shapes (e.g. slabs/stairs on bridges) where the second head cell can be occupied.
        return !blocksMovement(world, head);
    }

    /**
     * Top Y of walkable surface whose feet block is at {@code feetY} (block Y index).
     */
    private static double surfaceTopY(World world, BlockPos feet) {
        return feet.getY() + blockTopOffset(world, feet) + ROUTE_TOP_EPS;
    }

    /**
     * Finds a walkable block top at (x,z) near {@code hintY}: prefers the highest surface whose top is
     * roughly at or below the hint (typical ground under the path); otherwise the closest top.
     * Avoids snapping to interior ceilings / roofs when the path runs inside buildings.
     */
    public static double snapRouteTopY(World world, double x, double z, double hintY) {
        if (world == null) {
            return hintY;
        }
        int bx = MathHelper.floor_double(x);
        int bz = MathHelper.floor_double(z);
        int base = MathHelper.floor_double(hintY);
        int yHi = Math.min(255, base + 12);
        int yLo = Math.max(0, base - 28);

        double bestBelow = Double.NaN;
        double bestBelowTop = Double.NaN;
        double bestAnyTop = Double.NaN;
        double bestAnyDist = Double.MAX_VALUE;

        for (int y = yHi; y >= yLo; y--) {
            BlockPos feet = new BlockPos(bx, y, bz);
            if (!isWalkableSurface(world, feet)) {
                continue;
            }
            double top = surfaceTopY(world, feet);
            if (top <= hintY + HINT_BELOW_BAND) {
                if (Double.isNaN(bestBelowTop) || top > bestBelowTop) {
                    bestBelowTop = top;
                    bestBelow = top;
                }
            }
            double d = Math.abs(top - hintY);
            if (d < bestAnyDist) {
                bestAnyDist = d;
                bestAnyTop = top;
            }
        }
        if (!Double.isNaN(bestBelow)) {
            return bestBelow;
        }
        if (!Double.isNaN(bestAnyTop)) {
            return bestAnyTop;
        }
        return hintY;
    }

    /**
     * @param hintY   reference height (e.g. block top or player feet)
     * @param vRadius how far above/below {@code hintY} to search (blocks)
     */
    public static double snapSurfaceY(World world, double x, double z, double hintY, int vRadius) {
        if (world == null) {
            return hintY;
        }
        int bx = MathHelper.floor_double(x);
        int bz = MathHelper.floor_double(z);
        int cy = MathHelper.floor_double(hintY);
        int yHi = Math.min(255, cy + vRadius);
        int yLo = Math.max(0, cy - vRadius);
        for (int y = yHi; y >= yLo; y--) {
            BlockPos feet = new BlockPos(bx, y, bz);
            if (isWalkableSurface(world, feet)) {
                return y + 1.0 + SURFACE_EPS;
            }
        }
        return hintY;
    }
}
