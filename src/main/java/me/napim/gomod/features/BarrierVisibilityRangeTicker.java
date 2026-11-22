package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Drives chunk re-meshing for the barrier "only visible within N blocks" feature.
 *
 * <p>{@link me.napim.gomod.mixins.BlockBarrierMixin#shouldSideBeRendered} runs the actual
 * per-block distance check, but it only fires when a chunk re-meshes. The chunk mesh is
 * cached, so we tell Minecraft "re-mesh chunks around me" whenever range-mode settings
 * change or the player has moved enough to shift which barriers fall inside the radius.</p>
 *
 * <p>Visibility is binary now: a barrier is rendered iff it's inside the configured range
 * (or inside the cursor reveal cube). The previous per-block hash-dither "gradual reveal"
 * was removed at the user's request — it never gave the impression of fading because vanilla
 * chunk meshes are atomic and we can't alpha-blend individual blocks without a full custom
 * renderer. Pop-in is the honest behaviour.</p>
 */
public final class BarrierVisibilityRangeTicker {

    /**
     * Re-mesh trigger threshold. Walking moves the player ~0.215 b/tick, so 0.5² fires
     * after ~2-3 ticks of walking. Cheaper than re-meshing every tick and the visible
     * boundary moves in fine enough steps not to look choppy.
     */
    private static final double MOVE_THRESHOLD_SQ = 0.5 * 0.5;
    /** Minimum tick gap between fresh re-mesh triggers. */
    private static final int MIN_TICKS_BETWEEN_UPDATES = 3;

    /**
     * Beyond vanilla reach, do a long ray-trace ourselves (like PlayerCaller) so the
     * cursor reveal works for far-away walls too. Capped at a sane distance to avoid
     * walking the whole render distance every tick.
     */
    private static final double MIN_CURSOR_RAY_DISTANCE = 512.0;
    private static final double MAX_CURSOR_RAY_DISTANCE = 2048.0;

    /**
     * Half-extent (Chebyshev) of the cursor "reveal" cube. Radius 1 → 3×3×3 box
     * matches the spec "the block I'm aiming at plus the surrounding barriers, ~3 wide".
     */
    public static final int CURSOR_RADIUS = 1;

    /**
     * Once the player aims away from a barrier, the revealed cube stays visible for this
     * many milliseconds before chunks are re-meshed to hide it. Gives a soft "hold" so a
     * shaky crosshair doesn't strobe the reveal on and off.
     */
    private static final long CURSOR_HOLD_MS = 400L;

    private static double lastX = Double.NaN;
    private static double lastY = Double.NaN;
    private static double lastZ = Double.NaN;
    private static boolean lastRangeEnabled = false;
    private static int lastRange = -1;
    private static int ticksSinceUpdate = 0;

    /**
     * Block the player is currently aiming at while it's a barrier — published to
     * {@link me.napim.gomod.mixins.BlockBarrierMixin} so it can keep that block (and the
     * Chebyshev ring of {@link #CURSOR_RADIUS} around it) rendered even when range mode
     * would hide it. {@code null} means no override is active.
     */
    private static volatile BlockPos cursorTargetPos = null;
    private static BlockPos lastCursorTargetPos = null;
    /**
     * Wall-clock deadline after which the current {@link #cursorTargetPos} is allowed to
     * be cleared (i.e. the cube re-meshed to hide barriers again). {@code 0} means the
     * player is currently aiming at a barrier and no fade-out is pending.
     */
    private static long cursorHoldUntilMs = 0L;

    /**
     * When {@code true}, the next {@link #clientTickEnd()} with a loaded world will call
     * {@link #markCube} once over the whole view distance instead of forcing
     * {@link net.minecraft.client.renderer.RenderGlobal#loadRenderers()} (which rebuilds
     * every chunk renderer and freezes the client for several seconds).
     */
    private static volatile boolean fullRemeshPending;
    private static boolean lastCageHide = false;

    private BarrierVisibilityRangeTicker() {}

    /**
     * Queues a one-shot dirty-mark over the client's render distance (used when master barrier
     * visibility toggles or aimed-only mode flips). Cheap relative to {@code loadRenderers()}.
     */
    public static void requestFullBarrierRemesh() {
        fullRemeshPending = true;
    }

    /** Force a refresh on the very next tick (e.g. after toggling barrier visibility on). */
    public static void invalidate() {
        lastX = Double.NaN;
        lastY = Double.NaN;
        lastZ = Double.NaN;
        lastRange = -1;
        cursorTargetPos = null;
        lastCursorTargetPos = null;
        cursorHoldUntilMs = 0L;
    }

    /**
     * @return the barrier block the player is currently aiming at, or {@code null} when no
     *         cursor override is active (cursor isn't on a barrier, feature disabled, etc.).
     *         Consumed by the barrier mixin — must remain side-effect free.
     */
    public static BlockPos getCursorTargetPos() {
        return cursorTargetPos;
    }

    public static void clientTickEnd() {
        try {
            BarrierVisibilityAtlasHook.clientTickRetryApplyIfNeeded();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            WorldClient world = mc.theWorld;
            EntityPlayer player = mc.thePlayer;
            if (world != null && player != null && fullRemeshPending) {
                fullRemeshPending = false;
                int half = remeshHalfExtentBlocks(mc);
                markCube(world, player, half);
                lastX = player.posX;
                lastY = player.posY;
                lastZ = player.posZ;
                lastRangeEnabled = ConfigManager.isBarrierVisibilityRangeEnabled();
                lastRange = lastRangeEnabled ? ConfigManager.getBarrierVisibilityRange() : 0;
                ticksSinceUpdate = 0;
                if (!BarrierVisibility.isEffectivelyEnabled()) {
                    clearCursorTarget(world);
                }
                return;
            }
            if (world == null || player == null) {
                if (lastCageHide) {
                    lastCageHide = false;
                    BarrierVisibility.resetPreCageSuppress();
                }
                invalidate();
                return;
            }
            boolean cageHide = BarrierVisibility.isCageHideWindowActive(mc);
            if (cageHide != lastCageHide) {
                lastCageHide = cageHide;
                if (ConfigManager.isBarrierVisibilityEnabled()) {
                    requestFullBarrierRemesh();
                    invalidate();
                }
            }
            if (!BarrierVisibility.isEffectivelyEnabled()) {
                clearCursorTarget(world);
                return;
            }

            updateCursorTarget(mc, world);

            boolean rangeEnabled = ConfigManager.isBarrierVisibilityRangeEnabled();
            int range = rangeEnabled ? ConfigManager.getBarrierVisibilityRange() : 0;

            ticksSinceUpdate++;

            boolean firstRun = Double.isNaN(lastX);
            boolean settingsChanged = rangeEnabled != lastRangeEnabled || range != lastRange;
            boolean moved = false;
            if (!firstRun) {
                double dx = player.posX - lastX;
                double dy = player.posY - lastY;
                double dz = player.posZ - lastZ;
                moved = dx * dx + dy * dy + dz * dz > MOVE_THRESHOLD_SQ;
            }

            if (!rangeEnabled && !settingsChanged) {
                lastX = player.posX;
                lastY = player.posY;
                lastZ = player.posZ;
                lastRangeEnabled = false;
                lastRange = 0;
                return;
            }

            boolean trigger = settingsChanged
                    || firstRun
                    || (moved && ticksSinceUpdate >= MIN_TICKS_BETWEEN_UPDATES);
            if (!trigger) {
                return;
            }

            // Size the dirty cube so it covers the visible radius plus padding for chunk edges.
            int prevRange = Math.max(0, lastRange);
            int frameRange = Math.max(prevRange, range);
            int effectiveRange = frameRange + 2;
            if (effectiveRange <= 0) {
                effectiveRange = 2;
            }

            if (rangeEnabled && moved && !firstRun && !settingsChanged) {
                // Only the thin spherical boundary changes while walking. Dirtying the two
                // complete range volumes here used to rebuild hundreds of unaffected chunk
                // sections every few ticks and was the main Forge-side lag source.
                markRangeBoundary(world, lastX, lastY, lastZ, range);
                markRangeBoundary(world, player.posX, player.posY, player.posZ, range);
            } else {
                // First run / radius or mode change genuinely can change the whole volume.
                markCube(world, player, effectiveRange);
            }

            lastX = player.posX;
            lastY = player.posY;
            lastZ = player.posZ;
            lastRangeEnabled = rangeEnabled;
            lastRange = range;
            ticksSinceUpdate = 0;
        } catch (Throwable ignored) {
        }
    }

    /**
     * Compute which barrier block the player is aiming at (if any), apply the
     * {@link #CURSOR_HOLD_MS} grace window so the reveal doesn't blink off the instant the
     * crosshair leaves, and mark the affected cubes dirty so chunks re-mesh.
     *
     * <p>Vanilla {@code objectMouseOver} is limited to ~5 blocks (block reach distance), so
     * we also run a longer ray-trace ourselves. Same pattern as
     * {@link me.napim.gomod.features.PlayerCaller}: any solid block in front blocks the ray,
     * so we never reveal barriers through a wall.
     */
    private static void updateCursorTarget(Minecraft mc, WorldClient world) {
        BlockPos next = null;
        if (cursorTrackingEnabled()) {
            next = nearestBarrierUnderCrosshair(mc, world);
        }

        long now = System.currentTimeMillis();
        BlockPos current = cursorTargetPos;
        int pad = CURSOR_RADIUS + 1;

        if (next != null) {
            // Actively aiming at a barrier — cancel any pending fade-out and (if the target
            // moved) swap the reveal cube right now so the new barrier appears immediately.
            cursorHoldUntilMs = 0L;
            if (!next.equals(current)) {
                if (current != null) {
                    markCubeAt(world, current, pad);
                }
                markCubeAt(world, next, pad);
                lastCursorTargetPos = next;
                cursorTargetPos = next;
            }
            return;
        }

        // Crosshair is no longer on a barrier.
        if (current == null) {
            cursorHoldUntilMs = 0L;
            return;
        }
        if (cursorHoldUntilMs == 0L) {
            // First tick the player aimed away — start the grace timer; keep the reveal alive.
            cursorHoldUntilMs = now + CURSOR_HOLD_MS;
            return;
        }
        if (now < cursorHoldUntilMs) {
            // Still within the 0.4s hold — leave the cube revealed.
            return;
        }

        // Hold expired: hide the reveal cube.
        markCubeAt(world, current, pad);
        lastCursorTargetPos = null;
        cursorTargetPos = null;
        cursorHoldUntilMs = 0L;
    }

    /**
     * Whether the cursor reveal feature could possibly fire right now. Used to skip the
     * long ray-trace when nothing visible would change.
     *
     * <p>In ON mode every barrier is already rendered, so the cursor cube wouldn't
     * change anything visible — we deliberately skip tracking there to avoid wasted
     * chunk re-meshes around the crosshair.</p>
     */
    private static boolean cursorTrackingEnabled() {
        if (!BarrierVisibility.isEffectivelyEnabled()) {
            return false;
        }
        if (ConfigManager.isBarrierVisibilityAimedOnly()) {
            // In Aimed-only mode the cursor cube IS the entire reveal — always track.
            return true;
        }
        return ConfigManager.isBarrierVisibilityRangeEnabled()
                && ConfigManager.isBarrierVisibilityShowAtCursor();
    }

    private static BlockPos nearestBarrierUnderCrosshair(Minecraft mc, WorldClient world) {
        // Fast path: vanilla already raycast for us. If the resolved block is a barrier
        // within reach, use it without doing a second trace.
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos pos = mop.getBlockPos();
            if (pos != null) {
                try {
                    if (world.getBlockState(pos).getBlock() == Blocks.barrier) {
                        return pos;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // Slow path: long manual trace so barriers past vanilla reach still trigger the
        // override. Returns null if the first solid hit is not a barrier — we never want
        // to reveal barriers hiding behind a regular wall.
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            return null;
        }
        try {
            Vec3 eye = player.getPositionEyes(1.0f);
            Vec3 look = player.getLook(1.0f);
            double rayDistance = cursorRayDistance(mc);
            Vec3 end = eye.addVector(
                    look.xCoord * rayDistance,
                    look.yCoord * rayDistance,
                    look.zCoord * rayDistance);
            MovingObjectPosition hit = world.rayTraceBlocks(eye, end, false, true, false);
            if (hit != null
                    && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                BlockPos pos = hit.getBlockPos();
                if (pos != null && world.getBlockState(pos).getBlock() == Blocks.barrier) {
                    return pos;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static double cursorRayDistance(Minecraft mc) {
        try {
            int chunks = mc.gameSettings == null ? 0 : mc.gameSettings.renderDistanceChunks;
            return Math.max(MIN_CURSOR_RAY_DISTANCE,
                    Math.min(MAX_CURSOR_RAY_DISTANCE, chunks * 16.0D + 64.0D));
        } catch (Throwable ignored) {
            return MIN_CURSOR_RAY_DISTANCE;
        }
    }

    private static void clearCursorTarget(WorldClient world) {
        if (lastCursorTargetPos != null && world != null) {
            try {
                markCubeAt(world, lastCursorTargetPos, CURSOR_RADIUS + 1);
            } catch (Throwable ignored) {
            }
        }
        lastCursorTargetPos = null;
        cursorTargetPos = null;
        cursorHoldUntilMs = 0L;
    }

    private static void markCubeAt(WorldClient world, BlockPos center, int halfExtent) {
        int yLo = Math.max(0, center.getY() - halfExtent);
        int yHi = Math.min(255, center.getY() + halfExtent);
        world.markBlockRangeForRenderUpdate(
                center.getX() - halfExtent, yLo, center.getZ() - halfExtent,
                center.getX() + halfExtent, yHi, center.getZ() + halfExtent);
    }

    private static void markCube(WorldClient world, EntityPlayer player, int halfExtentBlocks) {
        int ix = (int) Math.floor(player.posX);
        int iy = (int) Math.floor(player.posY);
        int iz = (int) Math.floor(player.posZ);
        int yLo = Math.max(0, iy - halfExtentBlocks);
        int yHi = Math.min(255, iy + halfExtentBlocks);
        world.markBlockRangeForRenderUpdate(
                ix - halfExtentBlocks, yLo, iz - halfExtentBlocks,
                ix + halfExtentBlocks, yHi, iz + halfExtentBlocks);
    }

    /**
     * Mark only 16³ render sections intersecting the proximity sphere's boundary band.
     * A small band covers sub-block player movement and the padding used by face meshing.
     */
    private static void markRangeBoundary(WorldClient world, double px, double py, double pz, int range) {
        final double band = 3.0D;
        double inner = Math.max(0.0D, range - band);
        double outer = range + band;
        double innerSq = inner * inner;
        double outerSq = outer * outer;
        int minSectionX = floorDiv16((int) Math.floor(px - outer));
        int maxSectionX = floorDiv16((int) Math.floor(px + outer));
        int minSectionY = Math.max(0, floorDiv16((int) Math.floor(py - outer)));
        int maxSectionY = Math.min(15, floorDiv16((int) Math.floor(py + outer)));
        int minSectionZ = floorDiv16((int) Math.floor(pz - outer));
        int maxSectionZ = floorDiv16((int) Math.floor(pz + outer));

        for (int sx = minSectionX; sx <= maxSectionX; sx++) {
            double x0 = sx * 16.0D;
            double x1 = x0 + 16.0D;
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                double y0 = sy * 16.0D;
                double y1 = y0 + 16.0D;
                for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                    double z0 = sz * 16.0D;
                    double z1 = z0 + 16.0D;
                    double minSq = distanceToBoxSq(px, py, pz, x0, y0, z0, x1, y1, z1);
                    double maxSq = farthestCornerDistanceSq(px, py, pz, x0, y0, z0, x1, y1, z1);
                    if (minSq > outerSq || maxSq < innerSq) {
                        continue;
                    }
                    world.markBlockRangeForRenderUpdate(
                            sx << 4, sy << 4, sz << 4,
                            (sx << 4) + 15, (sy << 4) + 15, (sz << 4) + 15);
                }
            }
        }
    }

    private static int floorDiv16(int value) {
        return value >> 4;
    }

    private static double distanceToBoxSq(double x, double y, double z,
                                          double x0, double y0, double z0,
                                          double x1, double y1, double z1) {
        double dx = x < x0 ? x0 - x : (x > x1 ? x - x1 : 0.0D);
        double dy = y < y0 ? y0 - y : (y > y1 ? y - y1 : 0.0D);
        double dz = z < z0 ? z0 - z : (z > z1 ? z - z1 : 0.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double farthestCornerDistanceSq(double x, double y, double z,
                                                   double x0, double y0, double z0,
                                                   double x1, double y1, double z1) {
        double dx = Math.max(Math.abs(x - x0), Math.abs(x - x1));
        double dy = Math.max(Math.abs(y - y0), Math.abs(y - y1));
        double dz = Math.max(Math.abs(z - z0), Math.abs(z - z1));
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Half-extent in blocks (Chebyshev around the player) so {@link #markCube} covers every
     * chunk the client is likely to have meshed for the current render distance, plus margin.
     */
    private static int remeshHalfExtentBlocks(Minecraft mc) {
        try {
            int chunks = mc.gameSettings.renderDistanceChunks;
            if (chunks < 2) {
                chunks = 2;
            }
            return chunks * 16 + 48;
        } catch (Throwable ignored) {
            return 16 * 12 + 48;
        }
    }
}
