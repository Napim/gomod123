package me.napim.gomod.features;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockWall;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in crosshair block overlay (Miscellaneous). Depth-tested only — not ESP.
 */
public final class GomodBlockOverlay {

    public static final String MODE_SIDE = "SIDE";
    public static final String MODE_FULL = "FULL";
    private static final String[] MODES = { MODE_SIDE, MODE_FULL };

    public static final String LINE_INSIDE = "INSIDE";
    public static final String LINE_MIDDLE = "MIDDLE";
    public static final String LINE_OUTSIDE = "OUTSIDE";
    private static final String[] LINE_PLACEMENTS = { LINE_INSIDE, LINE_MIDDLE, LINE_OUTSIDE };

    /** Reduces shape-aware flicker when hit detection briefly falls back to a larger box. */
    private static BlockPos stableShapeBoxPos;
    private static AxisAlignedBB stableShapeBox;

    /** Last authoritative slab overlay — survives 1-tick neighbor mis-picks while moving. */
    private static BlockPos pinnedSlabPos;
    private static AxisAlignedBB pinnedSlabBox;
    private static int pinnedSlabTicks;

    /**
     * Self-opacity prepass writes player depth before {@code RenderWorldLastEvent}. When deferred, draw the overlay
     * earlier (before that flush) so it stays visible through the semi-transparent body in F5.
     */
    private static boolean renderedThisFrame;

    private GomodBlockOverlay() {}

    /** Called from {@code RenderGlobal#renderEntities} HEAD each frame. */
    public static void beginWorldEntitiesPass() {
        renderedThisFrame = false;
    }

    public static boolean drewThisFrame() {
        return renderedThisFrame;
    }

    /**
     * Intentionally empty — overlay only at {@code RenderWorldLast} so F5/first-person stay stable.
     */
    public static void renderBeforeSelfOpacityDepthPrepassIfNeeded(float partialTicks, boolean thirdPersonSite) {
    }

    public static boolean isEnabled() {
        return ConfigManager.isGomodBlockOverlayEnabled();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setGomodBlockOverlayEnabled(enabled);
    }

    public static String getMode() {
        return ConfigManager.getGomodBlockOverlayMode();
    }

    public static void cycleMode() {
        String current = normalizeMode(getMode());
        int idx = 0;
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(current)) {
                idx = (i + 1) % MODES.length;
                break;
            }
        }
        ConfigManager.setGomodBlockOverlayMode(MODES[idx]);
    }

    public static void cycleLinePlacement() {
        String current = normalizeLinePlacement(ConfigManager.getGomodBlockOverlayLinePlacement());
        int idx = 0;
        for (int i = 0; i < LINE_PLACEMENTS.length; i++) {
            if (LINE_PLACEMENTS[i].equals(current)) {
                idx = (i + 1) % LINE_PLACEMENTS.length;
                break;
            }
        }
        ConfigManager.setGomodBlockOverlayLinePlacement(LINE_PLACEMENTS[idx]);
    }

    public static String normalizeMode(String mode) {
        if (mode == null) {
            return MODE_SIDE;
        }
        String upper = mode.trim().toUpperCase();
        for (String m : MODES) {
            if (m.equals(upper)) {
                return upper;
            }
        }
        return MODE_SIDE;
    }

    public static String normalizeLinePlacement(String placement) {
        if (placement == null) {
            return LINE_MIDDLE;
        }
        String upper = placement.trim().toUpperCase();
        for (String p : LINE_PLACEMENTS) {
            if (p.equals(upper)) {
                return upper;
            }
        }
        return LINE_MIDDLE;
    }

    public static String getModeLabel() {
        String mode = normalizeMode(getMode());
        if (MODE_FULL.equals(mode)) {
            return "Full box";
        }
        return "Side";
    }

    public static String getLinePlacementLabel() {
        String p = normalizeLinePlacement(ConfigManager.getGomodBlockOverlayLinePlacement());
        if (LINE_INSIDE.equals(p)) {
            return "Inside";
        }
        if (LINE_OUTSIDE.equals(p)) {
            return "Outside";
        }
        return "Middle";
    }

    /**
     * Cancel vanilla highlight. Third person draws here (same timing as vanilla selection box). First person draws at
     * {@code RenderWorldLastEvent} after translucent/self-opacity so the hand is not tinted.
     */
    public static void onDrawBlockHighlight(DrawBlockHighlightEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mc.theWorld != null) {
            BlockPos pos = mc.objectMouseOver.getBlockPos();
            if (pos != null && shouldSkipBarrierOverlayInCages(mc, mc.theWorld.getBlockState(pos).getBlock())) {
                event.setCanceled(true);
                return;
            }
        }
        if (mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0) {
            render(event.partialTicks);
            GomodBlockOverlayPlayerOutline.render(event.partialTicks);
        }
        event.setCanceled(true);
    }

    /** When enabled, no overlay on barrier blocks during Blitz cages; always works outside cages. */
    private static boolean shouldSkipBarrierOverlayInCages(Minecraft mc, Block block) {
        if (block != Blocks.barrier || !ConfigManager.isGomodBlockOverlayHideBarriersInCages()) {
            return false;
        }
        return BarrierVisibility.isBlitzCageContext(mc);
    }

    public static void render(float partialTicks) {
        if (renderedThisFrame || !isEnabled()) {
            return;
        }
        try {
            me.napim.gomod.util.HudGlState.restoreBeforeWorldGeometryOverlay();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.renderGlobal == null || mc.gameSettings.hideGUI) {
                return;
            }
            if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
                return;
            }
            if (mc.objectMouseOver == null
                    || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                clearPinnedSlab();
                return;
            }
            MovingObjectPosition mop = mc.objectMouseOver;
            BlockPos blockPos = resolveOverlayBlockPos(mc, mop, partialTicks);
            if (blockPos == null || mc.theWorld == null) {
                clearPinnedSlab();
                return;
            }
            Entity view = mc.getRenderViewEntity();
            if (view == null) {
                return;
            }

            String mode = normalizeMode(getMode());

            IBlockState state = mc.theWorld.getBlockState(blockPos);
            Block block = state.getBlock();
            if (block == null) {
                return;
            }
            if (shouldSkipBarrierOverlayInCages(mc, block)) {
                clearPinnedSlab();
                return;
            }

            AxisAlignedBB slabBox = tryExplicitPartialBlockBox(blockPos, state, block);
            AxisAlignedBB stairShapeBox = null;
            if (slabBox == null && block instanceof BlockStairs
                    && ConfigManager.isGomodBlockOverlaySideShapeAware()) {
                stairShapeBox = resolveShapeAwareSideBox(mc, blockPos, block, mop, partialTicks);
            }
            AxisAlignedBB partialShapeBox = slabBox != null ? slabBox : stairShapeBox;
            AxisAlignedBB wireBox;
            AxisAlignedBB fillBox;
            if (MODE_SIDE.equals(mode) && ConfigManager.isGomodBlockOverlaySideShapeAware() && slabBox != null) {
                wireBox = slabBox;
                fillBox = slabBox;
                pinSlabTarget(blockPos, slabBox);
            } else if (MODE_FULL.equals(mode)) {
                wireBox = resolveFullModeWireBox(mc, blockPos, block, state, partialShapeBox, mop, partialTicks);
                fillBox = (slabBox != null && wireBox != slabBox) ? slabBox : wireBox;
                if (slabBox != null) {
                    pinSlabTarget(blockPos, slabBox);
                }
            } else {
                wireBox = MODE_SIDE.equals(mode)
                        ? resolveSideModeBox(mc, blockPos, block, mop, partialTicks)
                        : getUpdatedSelectionBox(mc, blockPos, block, state);
                if (wireBox == null) {
                    return;
                }
                if (ConfigManager.isGomodBlockOverlaySideShapeAware() || isSmallerThanUnitCube(wireBox, blockPos)) {
                    wireBox = stabilizeShapeAwareBox(mc, blockPos, wireBox);
                }
                fillBox = wireBox;
            }
            if (wireBox == null || fillBox == null) {
                return;
            }
            if (MODE_FULL.equals(mode) && partialShapeBox == null && isSmallerThanUnitCube(wireBox, blockPos)) {
                wireBox = stabilizeShapeAwareBox(mc, blockPos, wireBox);
                fillBox = wireBox;
            }

            StairOverlayPick stairPick = null;
            if (block instanceof BlockStairs && ConfigManager.isGomodBlockOverlaySideShapeAware()) {
                stairPick = pickStairOverlay(mc, blockPos, block, state, mop, partialTicks);
                if (MODE_SIDE.equals(mode)) {
                    wireBox = stairPick.drawBox;
                    fillBox = stairPick.drawBox;
                } else if (MODE_FULL.equals(mode)) {
                    wireBox = fullCellBox(blockPos);
                    fillBox = stairPick.drawBox;
                }
            }

            float lineWidth = ConfigManager.getGomodBlockOverlayLineWidth();
            String linePlacement = ConfigManager.getGomodBlockOverlayLinePlacement();

            double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
            double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
            double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

            int argb = ConfigManager.getGomodBlockOverlayColorArgb();
            float r = ((argb >> 16) & 0xFF) / 255f;
            float g = ((argb >> 8) & 0xFF) / 255f;
            float b = (argb & 0xFF) / 255f;
            float fillA = ConfigManager.getGomodBlockOverlayFillOpacity();
            boolean fill = ConfigManager.isGomodBlockOverlayFillEnabled();

            double wx1 = wireBox.minX - camX;
            double wy1 = wireBox.minY - camY;
            double wz1 = wireBox.minZ - camZ;
            double wx2 = wireBox.maxX - camX;
            double wy2 = wireBox.maxY - camY;
            double wz2 = wireBox.maxZ - camZ;

            Double slabSplitY = null;
            if (MODE_FULL.equals(mode) && slabBox != null && wireBox != slabBox) {
                double splitWorldY = slabBox.minY <= wireBox.minY + 0.01D ? slabBox.maxY : slabBox.minY;
                if (shouldDrawSlabSplitRing(mc, blockPos, slabBox, wireBox)) {
                    slabSplitY = splitWorldY - camY;
                }
            }

            AxisAlignedBB fillRenderBox = fillBox;
            boolean fillOnPartialShape = false;
            if (MODE_FULL.equals(mode) && slabBox != null && wireBox != slabBox) {
                fillOnPartialShape = isHitOnSlabVolume(mop, slabBox);
                fillRenderBox = fillOnPartialShape ? slabBox : wireBox;
            } else if (block instanceof BlockStairs && MODE_FULL.equals(mode) && fillBox != wireBox) {
                fillOnPartialShape = true;
                fillRenderBox = fillBox;
            }

            double frx1 = fillRenderBox.minX - camX;
            double fry1 = fillRenderBox.minY - camY;
            double frz1 = fillRenderBox.minZ - camZ;
            double frx2 = fillRenderBox.maxX - camX;
            double fry2 = fillRenderBox.maxY - camY;
            double frz2 = fillRenderBox.maxZ - camZ;

            EnumFacing hitSide;
            if (stairPick != null && stairPick.hitFace != null) {
                hitSide = stairPick.hitFace;
            } else if (fillOnPartialShape) {
                AxisAlignedBB shapeForHit = slabBox != null ? slabBox : fillRenderBox;
                hitSide = resolveBoxHitFace(shapeForHit, mc, partialTicks, mop.hitVec);
                if (hitSide == null) {
                    hitSide = resolveOverlayHitSide(mc, blockPos, mop, shapeForHit);
                }
            } else if (MODE_FULL.equals(mode) || !isUnitCubeAt(fillRenderBox, blockPos)) {
                hitSide = mop.sideHit;
                if (hitSide == null) {
                    hitSide = resolveBoxHitFace(fillRenderBox, mc, partialTicks, mop.hitVec);
                }
                if (hitSide == null) {
                    hitSide = resolveOverlayHitSide(mc, blockPos, mop, fillRenderBox);
                }
            } else if (partialShapeBox != null) {
                hitSide = resolveBoxHitFace(partialShapeBox, mc, partialTicks, mop.hitVec);
                if (hitSide == null) {
                    hitSide = resolveOverlayHitSide(mc, blockPos, mop, fillBox);
                }
            } else {
                hitSide = resolveOverlayHitSide(mc, blockPos, mop, fillBox);
            }
            double hitX = mop.hitVec.xCoord - camX;
            double hitY = mop.hitVec.yCoord - camY;
            double hitZ = mop.hitVec.zCoord - camZ;
            drawCustomOverlay(mc, blockPos, wx1, wy1, wz1, wx2, wy2, wz2, frx1, fry1, frz1, frx2, fry2, frz2,
                    slabSplitY, mode, hitSide, linePlacement, r, g, b, fillA, lineWidth, fill, hitX, hitY, hitZ);
            renderedThisFrame = true;
        } catch (Throwable ignored) {
        }
    }

    /** Call before first-person hand/item render when {@link #drewThisFrame()} is true. */
    public static void prepareFirstPersonHandGl() {
        me.napim.gomod.util.HudGlState.restoreForFirstPersonHand();
        try {
            if (SelfOpacity.isActive() && SelfOpacity.getAlpha() < 0.99f) {
                org.lwjgl.opengl.GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
                net.minecraft.client.renderer.GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Crosshair often flickers between partial blocks (slabs, stairs) and neighbors while moving.
     * Prefer the partial block when the hit still lies on its collision geometry.
     */
    private static BlockPos resolveOverlayBlockPos(Minecraft mc, MovingObjectPosition mop, float partialTicks) {
        BlockPos raw = mop.getBlockPos();
        if (raw == null || mc.theWorld == null || mop.hitVec == null || mop.sideHit == null) {
            return raw;
        }

        if (isStairBlock(mc, raw)) {
            return raw;
        }

        Vec3 hit = mop.hitVec;
        EnumFacing side = mop.sideHit;

        BlockPos stairHit = findStairForHit(mc, mop, partialTicks);
        if (stairHit != null) {
            return stairHit;
        }

        if (side == EnumFacing.DOWN) {
            BlockPos below = raw.down();
            if (isStairBlock(mc, below) && stairContainsHit(mc, below, hit, mop, partialTicks)) {
                return below;
            }
        }

        if (side == EnumFacing.UP) {
            BlockPos above = raw.up();
            if (isStairBlock(mc, above) && stairContainsHit(mc, above, hit, mop, partialTicks)) {
                return above;
            }
        }

        if (side.getAxis().isHorizontal()) {
            BlockPos towardStair = raw.offset(side.getOpposite());
            if (isStairBlock(mc, towardStair) && stairContainsHit(mc, towardStair, hit, mop, partialTicks)) {
                return towardStair;
            }
        }

        String mode = normalizeMode(getMode());
        boolean slabAwarePos = MODE_FULL.equals(mode)
                || (MODE_SIDE.equals(mode) && ConfigManager.isGomodBlockOverlaySideShapeAware());
        if (!slabAwarePos) {
            return raw;
        }

        if (slabBoxAt(mc, raw) != null) {
            return raw;
        }

        // Seam on block above lower slab: DOWN face coplanar with slab top (not air above the slab cell).
        if (side == EnumFacing.DOWN) {
            BlockPos below = raw.down();
            AxisAlignedBB belowSlab = slabBoxAt(mc, below);
            if (belowSlab != null && hitNearSlabSeam(hit, belowSlab, EnumFacing.UP)) {
                return below;
            }
        }

        // Seam on block below top half slab: UP face coplanar with slab bottom.
        if (side == EnumFacing.UP) {
            BlockPos above = raw.up();
            AxisAlignedBB aboveSlab = slabBoxAt(mc, above);
            if (aboveSlab != null && hitNearSlabSeam(hit, aboveSlab, EnumFacing.DOWN)) {
                return above;
            }
        }

        // Neighbor slab only when the crosshair hit lies on slab collision, not through upper air at a block behind.
        if (side.getAxis().isHorizontal()) {
            BlockPos towardSlab = raw.offset(side.getOpposite());
            if (slabHitContainsPoint(mc, towardSlab, hit)) {
                return towardSlab;
            }
        }

        BlockPos nearbySlab = findSlabContainingHit(mc, mop);
        if (nearbySlab != null) {
            return nearbySlab;
        }

        // Brief neighbor mis-pick while still looking at the same pinned slab.
        if (pinnedSlabPos != null && pinnedSlabBox != null && pinnedSlabTicks > 0) {
            if (raw.distanceSq(pinnedSlabPos.getX(), pinnedSlabPos.getY(), pinnedSlabPos.getZ()) <= 3
                    && containsPoint(pinnedSlabBox, hit.xCoord, hit.yCoord, hit.zCoord)) {
                pinnedSlabTicks--;
                return pinnedSlabPos;
            }
            clearPinnedSlab();
        }

        return raw;
    }

    private static boolean isStairBlock(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.theWorld == null || pos == null) {
            return false;
        }
        try {
            return mc.theWorld.getBlockState(pos).getBlock() instanceof BlockStairs;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean stairContainsHit(Minecraft mc, BlockPos stairPos, Vec3 hit, MovingObjectPosition mop,
                                            float partialTicks) {
        if (mc == null || mc.theWorld == null || stairPos == null || hit == null) {
            return false;
        }
        IBlockState state = mc.theWorld.getBlockState(stairPos);
        Block block = state.getBlock();
        if (!(block instanceof BlockStairs)) {
            return false;
        }
        List<AxisAlignedBB> boxes = collectCollisionBoxes(mc, stairPos, block, state);
        for (AxisAlignedBB box : boxes) {
            if (box != null && containsPoint(box, hit.xCoord, hit.yCoord, hit.zCoord)) {
                return true;
            }
        }
        for (AxisAlignedBB box : boxes) {
            if (box != null && hitNearExpandedBox(box, hit, 0.12D)) {
                return true;
            }
        }
        StairOverlayPick pick = pickStairOverlay(mc, stairPos, block, state, mop, partialTicks);
        return pick.hitBox != null && hitNearExpandedBox(pick.hitBox, hit, 0.15D);
    }

    /**
     * When vanilla picks a neighbor full block, prefer a stair if the view ray hits stair collision closer.
     */
    private static BlockPos findStairForHit(Minecraft mc, MovingObjectPosition mop, float partialTicks) {
        BlockPos raw = mop.getBlockPos();
        if (raw == null || mop.hitVec == null || mc.theWorld == null) {
            return null;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return null;
        }
        Vec3 eye = view.getPositionEyes(partialTicks);
        Vec3 look = view.getLook(partialTicks);
        Vec3 end = eye.addVector(look.xCoord * 6.0D, look.yCoord * 6.0D, look.zCoord * 6.0D);
        double vanillaDist = eye.distanceTo(mop.hitVec);

        BlockPos best = null;
        double bestDist = vanillaDist - 0.02D;
        int cx = raw.getX();
        int cy = raw.getY();
        int cz = raw.getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!isStairBlock(mc, pos)) {
                        continue;
                    }
                    IBlockState state = mc.theWorld.getBlockState(pos);
                    Block block = state.getBlock();
                    List<AxisAlignedBB> boxes = collectCollisionBoxes(mc, pos, block, state);
                    for (AxisAlignedBB box : boxes) {
                        if (box == null) {
                            continue;
                        }
                        MovingObjectPosition rayHit = box.calculateIntercept(eye, end);
                        if (rayHit == null || rayHit.hitVec == null) {
                            continue;
                        }
                        double dist = eye.distanceTo(rayHit.hitVec);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Ray vs an AABB — authoritative hit face when vanilla mop flickers or uses a full 1×1 fallback. */
    private static EnumFacing resolveBoxHitFace(AxisAlignedBB box, Minecraft mc, float partialTicks,
                                                 Vec3 fallbackHit) {
        Entity view = mc.getRenderViewEntity();
        if (view == null || box == null) {
            return null;
        }
        Vec3 eye = view.getPositionEyes(partialTicks);
        Vec3 look = view.getLook(partialTicks);
        Vec3 end = eye.addVector(look.xCoord * 6.0D, look.yCoord * 6.0D, look.zCoord * 6.0D);
        MovingObjectPosition rayHit = box.calculateIntercept(eye, end);
        if (rayHit != null && rayHit.sideHit != null) {
            return rayHit.sideHit;
        }
        if (fallbackHit != null) {
            EnumFacing fromHit = pickDominantFaceForPoint(box, fallbackHit);
            if (fromHit != null) {
                return fromHit;
            }
        }
        return rayHit != null ? rayHit.sideHit : null;
    }

    private static BlockPos findSlabContainingHit(Minecraft mc, MovingObjectPosition mop) {
        BlockPos raw = mop.getBlockPos();
        if (raw == null || mop.hitVec == null || mc.theWorld == null) {
            return null;
        }
        Vec3 hit = mop.hitVec;
        int cx = raw.getX();
        int cy = raw.getY();
        int cz = raw.getZ();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    AxisAlignedBB slab = slabBoxAt(mc, pos);
                    if (slab == null) {
                        continue;
                    }
                    if (!containsPoint(slab, hit.xCoord, hit.yCoord, hit.zCoord)) {
                        continue;
                    }
                    double dist = hit.squareDistanceTo(new Vec3(
                            (slab.minX + slab.maxX) * 0.5D,
                            (slab.minY + slab.maxY) * 0.5D,
                            (slab.minZ + slab.maxZ) * 0.5D));
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** When we redirected to a slab, remap the hit face onto the slab cell. */
    private static EnumFacing resolveOverlayHitSide(Minecraft mc, BlockPos blockPos, MovingObjectPosition mop,
                                                    AxisAlignedBB box) {
        if (mop == null || mop.sideHit == null || blockPos == null || mop.getBlockPos() == null) {
            return mop != null ? mop.sideHit : null;
        }
        if (blockPos.equals(mop.getBlockPos())) {
            return mop.sideHit;
        }
        BlockPos raw = mop.getBlockPos();
        EnumFacing side = mop.sideHit;
        int dx = blockPos.getX() - raw.getX();
        int dy = blockPos.getY() - raw.getY();
        int dz = blockPos.getZ() - raw.getZ();
        if (side == EnumFacing.DOWN && dy == 1) {
            return EnumFacing.UP;
        }
        if (side == EnumFacing.UP && dy == 1) {
            return EnumFacing.DOWN;
        }
        if (side == EnumFacing.UP && dy == -1) {
            return EnumFacing.DOWN;
        }
        if (side.getAxis().isHorizontal()) {
            if (dx == side.getFrontOffsetX() && dy == 0 && dz == side.getFrontOffsetZ()) {
                return side.getOpposite();
            }
        }
        if (box != null && mop.hitVec != null) {
            EnumFacing fromHit = pickDominantFaceForPoint(box, mop.hitVec);
            if (fromHit != null) {
                return fromHit;
            }
        }
        return side;
    }

    private static EnumFacing pickDominantFaceForPoint(AxisAlignedBB box, Vec3 hit) {
        if (box == null || hit == null) {
            return null;
        }
        double eps = 0.15D;
        if (Math.abs(hit.yCoord - box.maxY) <= eps) {
            return EnumFacing.UP;
        }
        if (Math.abs(hit.yCoord - box.minY) <= eps) {
            return EnumFacing.DOWN;
        }
        if (Math.abs(hit.zCoord - box.minZ) <= eps) {
            return EnumFacing.NORTH;
        }
        if (Math.abs(hit.zCoord - box.maxZ) <= eps) {
            return EnumFacing.SOUTH;
        }
        if (Math.abs(hit.xCoord - box.minX) <= eps) {
            return EnumFacing.WEST;
        }
        if (Math.abs(hit.xCoord - box.maxX) <= eps) {
            return EnumFacing.EAST;
        }
        return null;
    }

    private static void pinSlabTarget(BlockPos pos, AxisAlignedBB box) {
        pinnedSlabPos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        pinnedSlabBox = box;
        pinnedSlabTicks = 10;
    }

    private static void clearPinnedSlab() {
        pinnedSlabPos = null;
        pinnedSlabBox = null;
        pinnedSlabTicks = 0;
    }

    private static AxisAlignedBB slabBoxAt(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.theWorld == null || pos == null) {
            return null;
        }
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        return tryExplicitPartialBlockBox(pos, state, block);
    }

    private static boolean slabHitContainsPoint(Minecraft mc, BlockPos slabPos, Vec3 hit) {
        if (mc == null || slabPos == null || hit == null) {
            return false;
        }
        AxisAlignedBB slab = slabBoxAt(mc, slabPos);
        return slab != null && containsPoint(slab, hit.xCoord, hit.yCoord, hit.zCoord);
    }

    /** Hit on the outer face of a half-slab (top/bottom seam), not floating above/below the slab volume. */
    private static boolean hitNearSlabSeam(Vec3 hit, AxisAlignedBB slab, EnumFacing slabFace) {
        if (hit == null || slab == null || slabFace == null) {
            return false;
        }
        double eps = 0.06D;
        if (slabFace == EnumFacing.UP) {
            return Math.abs(hit.yCoord - slab.maxY) <= eps
                    && hit.xCoord >= slab.minX - eps && hit.xCoord <= slab.maxX + eps
                    && hit.zCoord >= slab.minZ - eps && hit.zCoord <= slab.maxZ + eps;
        }
        if (slabFace == EnumFacing.DOWN) {
            return Math.abs(hit.yCoord - slab.minY) <= eps
                    && hit.xCoord >= slab.minX - eps && hit.xCoord <= slab.maxX + eps
                    && hit.zCoord >= slab.minZ - eps && hit.zCoord <= slab.maxZ + eps;
        }
        return false;
    }

    private static boolean hitNearExpandedBox(AxisAlignedBB box, Vec3 hit, double pad) {
        return hit.xCoord >= box.minX - pad && hit.xCoord <= box.maxX + pad
                && hit.yCoord >= box.minY - pad && hit.yCoord <= box.maxY + pad
                && hit.zCoord >= box.minZ - pad && hit.zCoord <= box.maxZ + pad;
    }

    /**
     * Side mode bounds: shape-aware uses collision sub-box (stairs/slabs); otherwise full selection box.
     */
    private static AxisAlignedBB resolveSideModeBox(Minecraft mc, BlockPos blockPos, Block block,
                                                    MovingObjectPosition mop, float partialTicks) {
        AxisAlignedBB decor = tryExplicitDecorShapeBox(mc, blockPos, stateAt(mc, blockPos), block);
        if (decor != null) {
            return decor;
        }
        if (!ConfigManager.isGomodBlockOverlaySideShapeAware()) {
            return getUpdatedSelectionBox(mc, blockPos, block, stateAt(mc, blockPos));
        }
        if (block instanceof BlockStairs) {
            IBlockState state = stateAt(mc, blockPos);
            return pickStairOverlay(mc, blockPos, block, state, mop, partialTicks).drawBox;
        }
        return resolveShapeAwareSideBox(mc, blockPos, block, mop, partialTicks);
    }

    private static IBlockState stateAt(Minecraft mc, BlockPos blockPos) {
        return mc != null && mc.theWorld != null && blockPos != null
                ? mc.theWorld.getBlockState(blockPos) : null;
    }

    /**
     * Collision sub-box the crosshair hit (stairs tread, slab top, etc.).
     */
    private static AxisAlignedBB resolveShapeAwareSideBox(Minecraft mc, BlockPos blockPos, Block block,
                                                          MovingObjectPosition mop, float partialTicks) {
        if (mc.theWorld == null || blockPos == null || block == null) {
            return null;
        }
        IBlockState state = mc.theWorld.getBlockState(blockPos);

        AxisAlignedBB explicit = tryExplicitPartialBlockBox(blockPos, state, block);
        if (explicit != null) {
            return explicit;
        }

        AxisAlignedBB decor = tryExplicitDecorShapeBox(mc, blockPos, state, block);
        if (decor != null) {
            return decor;
        }

        List<AxisAlignedBB> boxes = collectCollisionBoxes(mc, blockPos, block, state);
        if (boxes.isEmpty()) {
            return collisionOrBlockBox(mc, blockPos, block, state);
        }
        if (boxes.size() == 1) {
            return boxes.get(0);
        }

        AxisAlignedBB rayBox = pickBoxByViewRay(mc, boxes, partialTicks);
        if (rayBox != null) {
            return rayBox;
        }

        Vec3 hit = mop != null ? mop.hitVec : null;
        EnumFacing side = mop != null ? mop.sideHit : null;

        if (hit != null) {
            AxisAlignedBB contained = pickSmallestContainingBox(boxes, hit);
            if (contained != null) {
                return contained;
            }
        }
        if (hit != null && side != null) {
            AxisAlignedBB faceBox = pickBoxByHitFace(boxes, hit, side);
            if (faceBox != null) {
                return faceBox;
            }
        }
        return smallestBox(boxes, collisionOrBlockBox(mc, blockPos, block, state));
    }

    /** Full box wire: 1×1 cell for half-slabs and stairs; torches/chests/etc. use their collision shape. */
    private static AxisAlignedBB resolveFullModeWireBox(Minecraft mc, BlockPos blockPos, Block block,
                                                        IBlockState state, AxisAlignedBB partialShapeBox,
                                                        MovingObjectPosition mop, float partialTicks) {
        if (partialShapeBox != null && block instanceof BlockSlab && !((BlockSlab) block).isDouble()) {
            return fullCellBox(blockPos);
        }
        if (partialShapeBox != null && block instanceof BlockStairs) {
            return fullCellBox(blockPos);
        }
        AxisAlignedBB shape = resolveShapeAwareSideBox(mc, blockPos, block, mop, partialTicks);
        if (shape != null) {
            return shape;
        }
        AxisAlignedBB selected = getUpdatedSelectionBox(mc, blockPos, block, state);
        return selected != null ? selected : fullCellBox(blockPos);
    }

    /** Refreshes shared block bounds (1.8 hitbox sync) before reading the selection box. */
    private static AxisAlignedBB getUpdatedSelectionBox(Minecraft mc, BlockPos blockPos, Block block,
                                                      IBlockState state) {
        if (mc == null || mc.theWorld == null || blockPos == null || block == null) {
            return null;
        }
        try {
            block.setBlockBoundsBasedOnState(mc.theWorld, blockPos);
            return block.getSelectedBoundingBox(mc.theWorld, blockPos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AxisAlignedBB fullCellBox(BlockPos pos) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        return new AxisAlignedBB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
    }

    private static final class StairOverlayPick {
        final AxisAlignedBB hitBox;
        final AxisAlignedBB drawBox;
        final EnumFacing hitFace;

        StairOverlayPick(AxisAlignedBB hitBox, AxisAlignedBB drawBox, EnumFacing hitFace) {
            this.hitBox = hitBox;
            this.drawBox = drawBox;
            this.hitFace = hitFace;
        }
    }

    /**
     * Picks the collision piece and face the crosshair actually hit (e.g. inner tread from the side = UP on a
     * sub-box). Expands to the full 1×1 cell only for a true outer shell face (full-height side / full top).
     */
    private static StairOverlayPick pickStairOverlay(Minecraft mc, BlockPos blockPos, Block block, IBlockState state,
                                                     MovingObjectPosition mop, float partialTicks) {
        AxisAlignedBB fullCell = fullCellBox(blockPos);
        List<AxisAlignedBB> boxes = collectCollisionBoxes(mc, blockPos, block, state);
        if (boxes.isEmpty()) {
            return new StairOverlayPick(fullCell, fullCell, mop != null ? mop.sideHit : null);
        }

        AxisAlignedBB hitBox = null;
        EnumFacing hitFace = null;
        Entity view = mc.getRenderViewEntity();
        if (view != null) {
            Vec3 eye = view.getPositionEyes(partialTicks);
            Vec3 look = view.getLook(partialTicks);
            Vec3 end = eye.addVector(look.xCoord * 6.0D, look.yCoord * 6.0D, look.zCoord * 6.0D);
            double bestDist = Double.MAX_VALUE;
            for (AxisAlignedBB box : boxes) {
                if (box == null) {
                    continue;
                }
                MovingObjectPosition rayHit = box.calculateIntercept(eye, end);
                if (rayHit == null || rayHit.sideHit == null || rayHit.hitVec == null) {
                    continue;
                }
                double dist = eye.distanceTo(rayHit.hitVec);
                if (dist < bestDist) {
                    bestDist = dist;
                    hitBox = box;
                    hitFace = rayHit.sideHit;
                }
            }
        }

        if (hitBox == null && mop != null && mop.hitVec != null) {
            hitBox = pickSmallestContainingBox(boxes, mop.hitVec);
            if (hitBox != null) {
                hitFace = resolveBoxHitFace(hitBox, mc, partialTicks, mop.hitVec);
            }
        }
        if (hitBox == null) {
            hitBox = pickBoxByViewRay(mc, boxes, partialTicks);
        }
        if (hitBox == null) {
            hitBox = smallestBox(boxes, fullCell);
        }
        if (hitFace == null && mop != null) {
            hitFace = mop.sideHit;
        }
        if (hitFace == null && hitBox != null && mop != null && mop.hitVec != null) {
            hitFace = resolveBoxHitFace(hitBox, mc, partialTicks, mop.hitVec);
        }

        AxisAlignedBB drawBox = (hitFace != null && isStairOuterShellFace(blockPos, hitBox, hitFace))
                ? fullCell : hitBox;
        return new StairOverlayPick(hitBox, drawBox, hitFace);
    }

    /** True when {@code face} lies on the block's outer shell and should use the full 1×1 face (not a sub-piece). */
    private static boolean isStairOuterShellFace(BlockPos pos, AxisAlignedBB box, EnumFacing face) {
        if (pos == null || box == null || face == null) {
            return false;
        }
        double eps = 0.02D;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        switch (face) {
            case DOWN:
                return Math.abs(box.minY - y) <= eps
                        && spansFullX(box, x, eps) && spansFullZ(box, z, eps);
            case UP:
                return Math.abs(box.maxY - (y + 1.0D)) <= eps
                        && spansFullX(box, x, eps) && spansFullZ(box, z, eps);
            case NORTH:
                return Math.abs(box.minZ - z) <= eps;
            case SOUTH:
                return Math.abs(box.maxZ - (z + 1.0D)) <= eps;
            case WEST:
                return Math.abs(box.minX - x) <= eps;
            case EAST:
            default:
                return Math.abs(box.maxX - (x + 1.0D)) <= eps;
        }
    }

    private static boolean spansFullX(AxisAlignedBB box, int blockX, double eps) {
        return box.minX <= blockX + eps && box.maxX >= blockX + 1.0D - eps;
    }

    private static boolean spansFullZ(AxisAlignedBB box, int blockZ, double eps) {
        return box.minZ <= blockZ + eps && box.maxZ >= blockZ + 1.0D - eps;
    }

    /** Torch (full model) + chest body + fence/wall visual bounds. */
    private static AxisAlignedBB tryExplicitDecorShapeBox(Minecraft mc, BlockPos pos, IBlockState state,
                                                          Block block) {
        if (pos == null || state == null || block == null || mc == null || mc.theWorld == null) {
            return null;
        }
        if (block instanceof BlockFence || block instanceof BlockFenceGate) {
            return fenceVisualBox(mc, pos, state, block);
        }
        if (block instanceof BlockWall) {
            return wallVisualBox(mc, pos, state, block);
        }
        if (block instanceof BlockTorch) {
            return torchVisualBox(mc, pos, state, block);
        }
        if (block instanceof BlockChest) {
            return getUpdatedSelectionBox(mc, pos, block, state);
        }
        return null;
    }

    /**
     * Fences/gates use a 1.5-block selection/collision box for stacking; the visible model is one block tall.
     */
    private static AxisAlignedBB fenceVisualBox(Minecraft mc, BlockPos pos, IBlockState state, Block block) {
        AxisAlignedBB box = getUpdatedSelectionBox(mc, pos, block, state);
        if (box == null) {
            box = unionBoxes(collectCollisionBoxes(mc, pos, block, state));
        }
        if (box == null) {
            return null;
        }
        return clampMaxY(box, pos.getY() + 1.0D);
    }

    /** Cobblestone walls: vanilla selection height (1.5 standalone, 1.0 when capped by a block above). */
    private static AxisAlignedBB wallVisualBox(Minecraft mc, BlockPos pos, IBlockState state, Block block) {
        AxisAlignedBB box = getUpdatedSelectionBox(mc, pos, block, state);
        if (box == null) {
            box = unionBoxes(collectCollisionBoxes(mc, pos, block, state));
        }
        if (box == null) {
            return null;
        }
        boolean connectsUp = false;
        try {
            connectsUp = ((BlockWall) block).canConnectTo(mc.theWorld, pos.up());
        } catch (Throwable ignored) {
        }
        double maxY = pos.getY() + (connectsUp ? 1.0D : 1.5D);
        return clampMaxY(box, maxY);
    }

    private static AxisAlignedBB unionBoxes(List<AxisAlignedBB> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (AxisAlignedBB box : boxes) {
            if (box == null) {
                continue;
            }
            any = true;
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        if (!any) {
            return null;
        }
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static AxisAlignedBB clampMaxY(AxisAlignedBB box, double maxY) {
        if (box == null || box.maxY <= maxY + 0.001D) {
            return box;
        }
        return new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, maxY, box.maxZ);
    }

    /** Vanilla torch AABB plus flame/cross model extent (selection box is shorter than the texture). */
    private static AxisAlignedBB torchVisualBox(Minecraft mc, BlockPos pos, IBlockState state, Block block) {
        try {
            AxisAlignedBB world = block.getSelectedBoundingBox(mc.theWorld, pos);
            if (world == null) {
                return null;
            }
            AxisAlignedBB local = new AxisAlignedBB(
                    world.minX - pos.getX(), world.minY - pos.getY(), world.minZ - pos.getZ(),
                    world.maxX - pos.getX(), world.maxY - pos.getY(), world.maxZ - pos.getZ());
            local = expandTorchVisualBounds(local, state);
            return local.offset(pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 1.8.9 torch block models extend slightly past vanilla selection — floor flame cross and wall torch tips.
     */
    private static AxisAlignedBB expandTorchVisualBounds(AxisAlignedBB local, IBlockState state) {
        if (local == null || state == null) {
            return local;
        }
        EnumFacing facing;
        try {
            facing = state.getValue(BlockTorch.FACING);
        } catch (Throwable ignored) {
            facing = EnumFacing.UP;
        }
        if (facing == EnumFacing.UP) {
            return unionLocal(local, new AxisAlignedBB(0.40625D, 0.0D, 0.40625D, 0.59375D, 0.71875D, 0.59375D));
        }
        double minX = local.minX - 0.03125D;
        double maxX = local.maxX + 0.03125D;
        double minY = local.minY;
        double maxY = Math.min(1.0D, local.maxY + 0.09375D);
        double minZ = local.minZ;
        double maxZ = local.maxZ;
        switch (facing) {
            case NORTH:
                maxZ = Math.min(1.0D, local.maxZ + 0.09375D);
                break;
            case SOUTH:
                minZ = Math.max(0.0D, local.minZ - 0.09375D);
                break;
            case WEST:
                maxX = Math.min(1.0D, local.maxX + 0.09375D);
                break;
            case EAST:
                minX = Math.max(0.0D, local.minX - 0.09375D);
                break;
            default:
                break;
        }
        return new AxisAlignedBB(
                clampBlockLocal(minX), clampBlockLocal(minY), clampBlockLocal(minZ),
                clampBlockLocal(maxX), clampBlockLocal(maxY), clampBlockLocal(maxZ));
    }

    private static AxisAlignedBB unionLocal(AxisAlignedBB a, AxisAlignedBB b) {
        return new AxisAlignedBB(
                Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }

    private static double clampBlockLocal(double v) {
        return Math.max(0.0D, Math.min(1.0D, v));
    }

    /** Deterministic half-slab bounds — avoids selection-box fallback flicker on slab tops. */
    private static AxisAlignedBB tryExplicitPartialBlockBox(BlockPos pos, IBlockState state, Block block) {
        if (!(block instanceof BlockSlab)) {
            return null;
        }
        try {
            BlockSlab slab = (BlockSlab) block;
            double x = pos.getX();
            double y = pos.getY();
            double z = pos.getZ();
            if (slab.isDouble()) {
                return new AxisAlignedBB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
            }
            BlockSlab.EnumBlockHalf half = state.getValue(BlockSlab.HALF);
            if (half == BlockSlab.EnumBlockHalf.TOP) {
                return new AxisAlignedBB(x, y + 0.5D, z, x + 1.0D, y + 1.0D, z + 1.0D);
            }
            return new AxisAlignedBB(x, y, z, x + 1.0D, y + 0.5D, z + 1.0D);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<AxisAlignedBB> collectCollisionBoxes(Minecraft mc, BlockPos blockPos, Block block,
                                                             IBlockState state) {
        List<AxisAlignedBB> boxes = new ArrayList<>();
        AxisAlignedBB mask = new AxisAlignedBB(
                blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                blockPos.getX() + 1, blockPos.getY() + 1, blockPos.getZ() + 1);
        try {
            block.setBlockBoundsBasedOnState(mc.theWorld, blockPos);
            if (block instanceof BlockStairs) {
                ((BlockStairs) block).setBaseCollisionBounds(mc.theWorld, blockPos);
            }
            block.addCollisionBoxesToList(mc.theWorld, blockPos, state, mask, boxes, null);
        } catch (Throwable ignored) {
        }
        if (boxes.isEmpty()) {
            AxisAlignedBB collision = block.getCollisionBoundingBox(mc.theWorld, blockPos, state);
            if (collision != null) {
                boxes.add(collision);
            }
        }
        return boxes;
    }

    /** Prefer collision; torches etc. fall back to refreshed selection / decor shape, not a 1×1 cube. */
    private static AxisAlignedBB collisionOrBlockBox(Minecraft mc, BlockPos blockPos, Block block,
                                                     IBlockState state) {
        AxisAlignedBB collision = block.getCollisionBoundingBox(mc.theWorld, blockPos, state);
        if (collision != null) {
            return collision;
        }
        AxisAlignedBB decor = tryExplicitDecorShapeBox(mc, blockPos, state, block);
        if (decor != null) {
            return decor;
        }
        AxisAlignedBB selected = getUpdatedSelectionBox(mc, blockPos, block, state);
        if (selected != null && !isUnitCubeAt(selected, blockPos)) {
            return selected;
        }
        double x = blockPos.getX();
        double y = blockPos.getY();
        double z = blockPos.getZ();
        return new AxisAlignedBB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
    }

    private static AxisAlignedBB pickBoxByViewRay(Minecraft mc, List<AxisAlignedBB> boxes, float partialTicks) {
        Entity view = mc.getRenderViewEntity();
        if (view == null || boxes == null || boxes.isEmpty()) {
            return null;
        }
        Vec3 eye = view.getPositionEyes(partialTicks);
        Vec3 look = view.getLook(partialTicks);
        Vec3 end = eye.addVector(look.xCoord * 6.0D, look.yCoord * 6.0D, look.zCoord * 6.0D);
        AxisAlignedBB best = null;
        double bestDist = Double.MAX_VALUE;
        for (AxisAlignedBB box : boxes) {
            if (box == null) {
                continue;
            }
            MovingObjectPosition intercept = box.calculateIntercept(eye, end);
            if (intercept == null || intercept.hitVec == null) {
                continue;
            }
            double dist = eye.distanceTo(intercept.hitVec);
            if (dist < bestDist) {
                bestDist = dist;
                best = box;
            }
        }
        return best;
    }

    /**
     * When the crosshair briefly resolves a larger box (e.g. full 1x1 selection vs slab top),
     * keep the last smaller collision box for the same block.
     */
    private static AxisAlignedBB stabilizeShapeAwareBox(Minecraft mc, BlockPos blockPos, AxisAlignedBB box) {
        if (blockPos == null || box == null) {
            return box;
        }
        AxisAlignedBB slabBox = slabBoxAt(mc, blockPos);
        if (slabBox != null) {
            return slabBox;
        }
        boolean sameBlock = stableShapeBoxPos != null && stableShapeBox != null
                && stableShapeBoxPos.getX() == blockPos.getX()
                && stableShapeBoxPos.getY() == blockPos.getY()
                && stableShapeBoxPos.getZ() == blockPos.getZ();
        if (sameBlock) {
            if (isUnitCubeAt(box, blockPos) && isSmallerThanUnitCube(stableShapeBox, blockPos)) {
                return stableShapeBox;
            }
            if (boxVolume(box) > boxVolume(stableShapeBox) * 1.15D
                    && isSmallerThanUnitCube(stableShapeBox, blockPos)) {
                return stableShapeBox;
            }
        }
        if (isSmallerThanUnitCube(box, blockPos)) {
            stableShapeBoxPos = new BlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            stableShapeBox = box;
        } else if (!sameBlock) {
            stableShapeBoxPos = null;
            stableShapeBox = null;
        }
        return box;
    }

    private static boolean isUnitCubeAt(AxisAlignedBB box, BlockPos pos) {
        if (box == null || pos == null) {
            return false;
        }
        double eps = 0.02D;
        return box.minX >= pos.getX() - eps && box.minY >= pos.getY() - eps && box.minZ >= pos.getZ() - eps
                && box.maxX <= pos.getX() + 1.0D + eps && box.maxY <= pos.getY() + 1.0D + eps
                && box.maxZ <= pos.getZ() + 1.0D + eps
                && (box.maxY - box.minY) > 0.9D && (box.maxX - box.minX) > 0.9D && (box.maxZ - box.minZ) > 0.9D;
    }

    private static boolean isSmallerThanUnitCube(AxisAlignedBB box, BlockPos pos) {
        return box != null && pos != null && !isUnitCubeAt(box, pos);
    }

    /** Smallest collision box whose hit face matches the crosshair side (slab tops, stair treads). */
    private static AxisAlignedBB pickBoxByHitFace(List<AxisAlignedBB> boxes, Vec3 hit, EnumFacing side) {
        AxisAlignedBB best = null;
        double bestVol = Double.MAX_VALUE;
        for (AxisAlignedBB box : boxes) {
            if (box == null || !isPointOnBoxFace(box, hit, side)) {
                continue;
            }
            double vol = boxVolume(box);
            if (vol < bestVol) {
                bestVol = vol;
                best = box;
            }
        }
        return best;
    }

    private static AxisAlignedBB pickSmallestContainingBox(List<AxisAlignedBB> boxes, Vec3 hit) {
        AxisAlignedBB best = null;
        double bestVol = Double.MAX_VALUE;
        for (AxisAlignedBB box : boxes) {
            if (box == null) {
                continue;
            }
            if (!containsPoint(box, hit.xCoord, hit.yCoord, hit.zCoord)) {
                continue;
            }
            double vol = boxVolume(box);
            if (vol < bestVol) {
                bestVol = vol;
                best = box;
            }
        }
        return best;
    }

    private static boolean isPointOnBoxFace(AxisAlignedBB box, Vec3 hit, EnumFacing side) {
        double eps = 0.05D;
        switch (side) {
            case UP:
                return Math.abs(hit.yCoord - box.maxY) <= eps
                        && hit.xCoord >= box.minX - eps && hit.xCoord <= box.maxX + eps
                        && hit.zCoord >= box.minZ - eps && hit.zCoord <= box.maxZ + eps;
            case DOWN:
                return Math.abs(hit.yCoord - box.minY) <= eps
                        && hit.xCoord >= box.minX - eps && hit.xCoord <= box.maxX + eps
                        && hit.zCoord >= box.minZ - eps && hit.zCoord <= box.maxZ + eps;
            case NORTH:
                return Math.abs(hit.zCoord - box.minZ) <= eps
                        && hit.xCoord >= box.minX - eps && hit.xCoord <= box.maxX + eps
                        && hit.yCoord >= box.minY - eps && hit.yCoord <= box.maxY + eps;
            case SOUTH:
                return Math.abs(hit.zCoord - box.maxZ) <= eps
                        && hit.xCoord >= box.minX - eps && hit.xCoord <= box.maxX + eps
                        && hit.yCoord >= box.minY - eps && hit.yCoord <= box.maxY + eps;
            case WEST:
                return Math.abs(hit.xCoord - box.minX) <= eps
                        && hit.yCoord >= box.minY - eps && hit.yCoord <= box.maxY + eps
                        && hit.zCoord >= box.minZ - eps && hit.zCoord <= box.maxZ + eps;
            case EAST:
            default:
                return Math.abs(hit.xCoord - box.maxX) <= eps
                        && hit.yCoord >= box.minY - eps && hit.yCoord <= box.maxY + eps
                        && hit.zCoord >= box.minZ - eps && hit.zCoord <= box.maxZ + eps;
        }
    }

    private static boolean containsPoint(AxisAlignedBB box, double x, double y, double z) {
        double eps = 0.02D;
        return x >= box.minX - eps && x <= box.maxX + eps
                && y >= box.minY - eps && y <= box.maxY + eps
                && z >= box.minZ - eps && z <= box.maxZ + eps;
    }

    private static double boxVolume(AxisAlignedBB box) {
        return (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
    }

    private static AxisAlignedBB smallestBox(List<AxisAlignedBB> boxes, AxisAlignedBB fallback) {
        AxisAlignedBB smallest = null;
        double bestVol = Double.MAX_VALUE;
        for (AxisAlignedBB box : boxes) {
            if (box == null) {
                continue;
            }
            double vol = boxVolume(box);
            if (vol < bestVol) {
                bestVol = vol;
                smallest = box;
            }
        }
        return smallest != null ? smallest : fallback;
    }

    /**
     * In-plane shift on the looked-at face (not into the block volume).
     * Inside = outline moves toward the face center; Outside = toward the face perimeter / air.
     */
    private static double facePlaneOffset(String placement, float lineWidth) {
        if (LINE_MIDDLE.equals(normalizeLinePlacement(placement))) {
            return 0.0D;
        }
        return (lineWidth * 0.5D) * 0.0015D;
    }

    /** Shrinks or expands the face rectangle in its own 2D plane — never along the face normal. */
    private static void applyFacePlaneOffset(double[] b, EnumFacing side, String linePlacement, float lineWidth) {
        double offset = facePlaneOffset(linePlacement, lineWidth);
        if (offset <= 0.0D) {
            return;
        }
        boolean outside = LINE_OUTSIDE.equals(normalizeLinePlacement(linePlacement));
        // Inside: +offset pulls min corners toward face center; Outside: negates to push toward exterior.
        double towardCenter = outside ? -offset : offset;
        switch (side) {
            case DOWN:
            case UP:
                b[0] += towardCenter;
                b[2] += towardCenter;
                b[3] -= towardCenter;
                b[5] -= towardCenter;
                break;
            case NORTH:
            case SOUTH:
                b[0] += towardCenter;
                b[1] += towardCenter;
                b[3] -= towardCenter;
                b[4] -= towardCenter;
                break;
            case WEST:
            case EAST:
            default:
                b[1] += towardCenter;
                b[2] += towardCenter;
                b[4] -= towardCenter;
                b[5] -= towardCenter;
                break;
        }
    }

    /** Tiny push along face normal so geometry sits on the visible surface (z-fight only). */
    private static void applySurfaceBias(double[] b, EnumFacing side) {
        double eps = 0.002D;
        switch (side) {
            case DOWN:
                b[1] -= eps;
                break;
            case UP:
                b[4] += eps;
                break;
            case NORTH:
                b[2] -= eps;
                break;
            case SOUTH:
                b[5] += eps;
                break;
            case WEST:
                b[0] -= eps;
                break;
            case EAST:
            default:
                b[3] += eps;
                break;
        }
    }

    private static double[] boundsForFace(double x1, double y1, double z1, double x2, double y2, double z2,
                                          EnumFacing side, String linePlacement, float lineWidth, boolean forFill) {
        double[] b = new double[] { x1, y1, z1, x2, y2, z2 };
        if (!forFill) {
            applyFacePlaneOffset(b, side, linePlacement, lineWidth);
        }
        applySurfaceBias(b, side);
        return b;
    }

    private static boolean isHitOnSlabVolume(MovingObjectPosition mop, AxisAlignedBB slabBox) {
        if (mop == null || slabBox == null || mop.hitVec == null) {
            return false;
        }
        return containsPoint(slabBox, mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord);
    }

    private static boolean isFaceExposed(Minecraft mc, BlockPos blockPos, EnumFacing face) {
        if (mc == null || mc.theWorld == null || blockPos == null || face == null) {
            return true;
        }
        BlockPos neighbor = blockPos.offset(face);
        Block block = mc.theWorld.getBlockState(neighbor).getBlock();
        if (block == null) {
            return true;
        }
        try {
            return !block.isOpaqueCube();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean shouldDrawSlabSplitRing(Minecraft mc, BlockPos blockPos, AxisAlignedBB slabBox,
                                                   AxisAlignedBB wireBox) {
        if (slabBox.minY <= wireBox.minY + 0.01D) {
            return isFaceExposed(mc, blockPos, EnumFacing.UP);
        }
        return isFaceExposed(mc, blockPos, EnumFacing.DOWN);
    }

    /** Horizontal ring at slab split — X/Z inset only (same plane as the split face). */
    private static void addSlabSplitRing(WorldRenderer wr,
                                         double x1, double splitY, double z1,
                                         double x2, double z2,
                                         float r, float g, float b, float a,
                                         String linePlacement, float lineWidth) {
        double[] bounds = { x1, splitY, z1, x2, splitY, z2 };
        applyFacePlaneOffset(bounds, EnumFacing.UP, linePlacement, lineWidth);
        x1 = bounds[0];
        splitY = bounds[1];
        z1 = bounds[2];
        x2 = bounds[3];
        z2 = bounds[5];
        addLine(wr, x1, splitY, z1, x2, splitY, z1, r, g, b, a);
        addLine(wr, x2, splitY, z1, x2, splitY, z2, r, g, b, a);
        addLine(wr, x2, splitY, z2, x1, splitY, z2, r, g, b, a);
        addLine(wr, x1, splitY, z2, x1, splitY, z1, r, g, b, a);
    }

    private static final EnumFacing[] FULL_BOX_FACES = {
            EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH,
            EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };

    private static void addFullBoxWireframe(Minecraft mc, BlockPos blockPos, Tessellator tess,
                                            double x1, double y1, double z1, double x2, double y2, double z2,
                                            float r, float g, float b, float a,
                                            String linePlacement, float lineWidth) {
        for (EnumFacing face : FULL_BOX_FACES) {
            if (!isFaceExposed(mc, blockPos, face)) {
                continue;
            }
            WorldRenderer wr = tess.getWorldRenderer();
            wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
            addFaceLoop(wr, face, x1, y1, z1, x2, y2, z2, r, g, b, a, linePlacement, lineWidth);
            tess.draw();
        }
    }

    private static void addLine(WorldRenderer wr,
                                double x1, double y1, double z1, double x2, double y2, double z2,
                                float r, float g, float b, float a) {
        vtx(wr, x1, y1, z1, r, g, b, a);
        vtx(wr, x2, y2, z2, r, g, b, a);
    }

    private static final int AIM_BRILLIANCE_GRID = 12;
    private static final float AIM_BRILLIANCE_ALPHA_BOOST = 2.25f;
    private static final float AIM_BRILLIANCE_WHITE_MIX = 0.48f;

    private static void drawCustomOverlay(Minecraft mc, BlockPos blockPos,
                                          double wx1, double wy1, double wz1, double wx2, double wy2, double wz2,
                                          double fx1, double fy1, double fz1, double fx2, double fy2, double fz2,
                                          Double slabSplitY, String mode, EnumFacing hitSide, String linePlacement,
                                          float r, float g, float b, float fillA, float lineWidth, boolean fill,
                                          double hitX, double hitY, double hitZ) {
        Minecraft mcView = Minecraft.getMinecraft();
        boolean thirdPerson = mcView != null && mcView.gameSettings != null && mcView.gameSettings.thirdPersonView != 0;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableLighting();
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableTexture2D();
            // Always depth-test against the world — never x-ray through blocks.
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(515); // GL_LEQUAL
            GlStateManager.depthMask(false);
            GlStateManager.disableCull();

            // Fill uses the physical sub-shape (slab tread); wireframe may use the full 1×1 cell.
            if (fill && fillA > 0.001f && hitSide != null) {
                Tessellator tess = Tessellator.getInstance();
                WorldRenderer wr = tess.getWorldRenderer();
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                addFaceQuads(wr, hitSide, fx1, fy1, fz1, fx2, fy2, fz2, r, g, b, fillA, linePlacement, lineWidth,
                        true);
                tess.draw();
            }

            GL11.glLineWidth(Math.max(1.0f, lineWidth));
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();

            if (MODE_SIDE.equals(mode)) {
                if (hitSide != null) {
                    wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
                    addFaceLoop(wr, hitSide, fx1, fy1, fz1, fx2, fy2, fz2, r, g, b, 1.0f, linePlacement, lineWidth);
                    tess.draw();
                }
            } else {
                addFullBoxWireframe(mc, blockPos, tess, wx1, wy1, wz1, wx2, wy2, wz2, r, g, b, 1.0f, linePlacement,
                        lineWidth);
                if (slabSplitY != null) {
                    wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                    addSlabSplitRing(wr, wx1, slabSplitY, wz1, wx2, wz2, r, g, b, 1.0f, linePlacement, lineWidth);
                    tess.draw();
                }
            }
        } finally {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.0f);
            GlStateManager.popMatrix();
            me.napim.gomod.util.HudGlState.restoreAfterBlockOverlay(thirdPerson);
        }
    }

    /** Closed loop on the face plane; inside/outside inset the 2D face edges, not the block volume. */
    private static void addFaceLoop(WorldRenderer wr, EnumFacing side,
                                    double x1, double y1, double z1,
                                    double x2, double y2, double z2,
                                    float r, float g, float b, float a,
                                    String linePlacement, float lineWidth) {
        double[] bounds = boundsForFace(x1, y1, z1, x2, y2, z2, side, linePlacement, lineWidth, false);
        x1 = bounds[0];
        y1 = bounds[1];
        z1 = bounds[2];
        x2 = bounds[3];
        y2 = bounds[4];
        z2 = bounds[5];
        switch (side) {
            case DOWN:
                vtx(wr, x1, y1, z1, r, g, b, a);
                vtx(wr, x2, y1, z1, r, g, b, a);
                vtx(wr, x2, y1, z2, r, g, b, a);
                vtx(wr, x1, y1, z2, r, g, b, a);
                break;
            case UP:
                vtx(wr, x1, y2, z1, r, g, b, a);
                vtx(wr, x1, y2, z2, r, g, b, a);
                vtx(wr, x2, y2, z2, r, g, b, a);
                vtx(wr, x2, y2, z1, r, g, b, a);
                break;
            case NORTH:
                vtx(wr, x1, y1, z1, r, g, b, a);
                vtx(wr, x1, y2, z1, r, g, b, a);
                vtx(wr, x2, y2, z1, r, g, b, a);
                vtx(wr, x2, y1, z1, r, g, b, a);
                break;
            case SOUTH:
                vtx(wr, x1, y1, z2, r, g, b, a);
                vtx(wr, x2, y1, z2, r, g, b, a);
                vtx(wr, x2, y2, z2, r, g, b, a);
                vtx(wr, x1, y2, z2, r, g, b, a);
                break;
            case WEST:
                vtx(wr, x1, y1, z1, r, g, b, a);
                vtx(wr, x1, y1, z2, r, g, b, a);
                vtx(wr, x1, y2, z2, r, g, b, a);
                vtx(wr, x1, y2, z1, r, g, b, a);
                break;
            case EAST:
            default:
                vtx(wr, x2, y1, z1, r, g, b, a);
                vtx(wr, x2, y2, z1, r, g, b, a);
                vtx(wr, x2, y2, z2, r, g, b, a);
                vtx(wr, x2, y1, z2, r, g, b, a);
                break;
        }
    }

    private static float aimBrillianceStrength(double u, double v, double aimU, double aimV, double maxDist) {
        if (maxDist <= 1.0E-6D) {
            return 1.0f;
        }
        double du = u - aimU;
        double dv = v - aimV;
        float t = (float) Math.min(1.0D, Math.sqrt(du * du + dv * dv) / maxDist);
        float inv = 1.0f - t;
        return inv * inv * (3.0f - 2.0f * inv);
    }

    private static void brillianceVertexColor(float strength, float baseR, float baseG, float baseB, float baseA,
                                            float[] outRgbA) {
        outRgbA[0] = Math.min(1.0f, baseR + (1.0f - baseR) * AIM_BRILLIANCE_WHITE_MIX * strength);
        outRgbA[1] = Math.min(1.0f, baseG + (1.0f - baseG) * AIM_BRILLIANCE_WHITE_MIX * strength);
        outRgbA[2] = Math.min(1.0f, baseB + (1.0f - baseB) * AIM_BRILLIANCE_WHITE_MIX * strength);
        outRgbA[3] = Math.min(1.0f, baseA * (1.0f + AIM_BRILLIANCE_ALPHA_BOOST * strength));
    }

    private static void vtxBrilliance(WorldRenderer wr, double x, double y, double z, float strength,
                                      float baseR, float baseG, float baseB, float baseA) {
        float[] c = new float[4];
        brillianceVertexColor(strength, baseR, baseG, baseB, baseA, c);
        wr.pos(x, y, z).color(c[0], c[1], c[2], c[3]).endVertex();
    }

    private static void quadBrilliance(WorldRenderer wr,
                                       double x1, double y1, double z1, float s1,
                                       double x2, double y2, double z2, float s2,
                                       double x3, double y3, double z3, float s3,
                                       double x4, double y4, double z4, float s4,
                                       float baseR, float baseG, float baseB, float baseA) {
        vtxBrilliance(wr, x1, y1, z1, s1, baseR, baseG, baseB, baseA);
        vtxBrilliance(wr, x2, y2, z2, s2, baseR, baseG, baseB, baseA);
        vtxBrilliance(wr, x3, y3, z3, s3, baseR, baseG, baseB, baseA);
        vtxBrilliance(wr, x4, y4, z4, s4, baseR, baseG, baseB, baseA);
    }

    /** Subdivided hit-face fill: brightest at the crosshair impact, fading toward face edges. */
    private static void addFaceQuadsAimBrilliance(WorldRenderer wr, EnumFacing side,
                                                  double x1, double y1, double z1,
                                                  double x2, double y2, double z2,
                                                  double hitX, double hitY, double hitZ,
                                                  float r, float g, float b, float baseA,
                                                  String linePlacement, float lineWidth) {
        double[] bounds = boundsForFace(x1, y1, z1, x2, y2, z2, side, linePlacement, lineWidth, true);
        x1 = bounds[0];
        y1 = bounds[1];
        z1 = bounds[2];
        x2 = bounds[3];
        y2 = bounds[4];
        z2 = bounds[5];

        double uMin;
        double uMax;
        double vMin;
        double vMax;
        double aimU;
        double aimV;
        double maxDist;

        switch (side) {
            case DOWN:
                uMin = x1;
                uMax = x2;
                vMin = z1;
                vMax = z2;
                aimU = hitX;
                aimV = hitZ;
                maxDist = maxCornerDist2D(aimU, aimV, uMin, vMin, uMax, vMin, uMax, vMax, uMin, vMax);
                subdivideFaceDown(wr, y1, uMin, uMax, vMin, vMax, aimU, aimV, maxDist, r, g, b, baseA);
                break;
            case UP:
                uMin = x1;
                uMax = x2;
                vMin = z1;
                vMax = z2;
                aimU = hitX;
                aimV = hitZ;
                maxDist = maxCornerDist2D(aimU, aimV, uMin, vMin, uMax, vMin, uMax, vMax, uMin, vMax);
                subdivideFaceUp(wr, y2, uMin, uMax, vMin, vMax, aimU, aimV, maxDist, r, g, b, baseA);
                break;
            case NORTH:
                uMin = x1;
                uMax = x2;
                vMin = y1;
                vMax = y2;
                aimU = hitX;
                aimV = hitY;
                maxDist = maxCornerDist2D(aimU, aimV, uMin, vMin, uMax, vMin, uMax, vMax, uMin, vMax);
                subdivideFaceNorth(wr, z1, uMin, uMax, vMin, vMax, aimU, aimV, maxDist, r, g, b, baseA);
                break;
            case SOUTH:
                uMin = x1;
                uMax = x2;
                vMin = y1;
                vMax = y2;
                aimU = hitX;
                aimV = hitY;
                maxDist = maxCornerDist2D(aimU, aimV, uMin, vMin, uMax, vMin, uMax, vMax, uMin, vMax);
                subdivideFaceSouth(wr, z2, uMin, uMax, vMin, vMax, aimU, aimV, maxDist, r, g, b, baseA);
                break;
            case WEST:
                uMin = z1;
                uMax = z2;
                vMin = y1;
                vMax = y2;
                aimU = hitZ;
                aimV = hitY;
                maxDist = maxCornerDist2D(aimU, aimV, uMin, vMin, uMax, vMin, uMax, vMax, uMin, vMax);
                subdivideFaceWest(wr, x1, uMin, uMax, vMin, vMax, aimU, aimV, maxDist, r, g, b, baseA);
                break;
            case EAST:
            default:
                uMin = z1;
                uMax = z2;
                vMin = y1;
                vMax = y2;
                aimU = hitZ;
                aimV = hitY;
                maxDist = maxCornerDist2D(aimU, aimV, uMin, vMin, uMax, vMin, uMax, vMax, uMin, vMax);
                subdivideFaceEast(wr, x2, uMin, uMax, vMin, vMax, aimU, aimV, maxDist, r, g, b, baseA);
                break;
        }
    }

    private static double maxCornerDist2D(double aimU, double aimV,
                                          double u0, double v0, double u1, double v1,
                                          double u2, double v2, double u3, double v3) {
        double max = 0.0D;
        max = Math.max(max, dist2D(aimU, aimV, u0, v0));
        max = Math.max(max, dist2D(aimU, aimV, u1, v1));
        max = Math.max(max, dist2D(aimU, aimV, u2, v2));
        max = Math.max(max, dist2D(aimU, aimV, u3, v3));
        return max;
    }

    private static double dist2D(double u1, double v1, double u2, double v2) {
        double du = u1 - u2;
        double dv = v1 - v2;
        return Math.sqrt(du * du + dv * dv);
    }

    private static void subdivideFaceDown(WorldRenderer wr, double y,
                                          double uMin, double uMax, double vMin, double vMax,
                                          double aimU, double aimV, double maxDist,
                                          float r, float g, float b, float baseA) {
        int n = AIM_BRILLIANCE_GRID;
        double du = (uMax - uMin) / n;
        double dv = (vMax - vMin) / n;
        for (int i = 0; i < n; i++) {
            double u0 = uMin + du * i;
            double u1 = uMin + du * (i + 1);
            for (int j = 0; j < n; j++) {
                double v0 = vMin + dv * j;
                double v1 = vMin + dv * (j + 1);
                quadBrilliance(wr, u0, y, v0, aimBrillianceStrength(u0, v0, aimU, aimV, maxDist),
                        u1, y, v0, aimBrillianceStrength(u1, v0, aimU, aimV, maxDist),
                        u1, y, v1, aimBrillianceStrength(u1, v1, aimU, aimV, maxDist),
                        u0, y, v1, aimBrillianceStrength(u0, v1, aimU, aimV, maxDist),
                        r, g, b, baseA);
            }
        }
    }

    private static void subdivideFaceUp(WorldRenderer wr, double y,
                                        double uMin, double uMax, double vMin, double vMax,
                                        double aimU, double aimV, double maxDist,
                                        float r, float g, float b, float baseA) {
        int n = AIM_BRILLIANCE_GRID;
        double du = (uMax - uMin) / n;
        double dv = (vMax - vMin) / n;
        for (int i = 0; i < n; i++) {
            double u0 = uMin + du * i;
            double u1 = uMin + du * (i + 1);
            for (int j = 0; j < n; j++) {
                double v0 = vMin + dv * j;
                double v1 = vMin + dv * (j + 1);
                quadBrilliance(wr, u0, y, v1, aimBrillianceStrength(u0, v1, aimU, aimV, maxDist),
                        u1, y, v1, aimBrillianceStrength(u1, v1, aimU, aimV, maxDist),
                        u1, y, v0, aimBrillianceStrength(u1, v0, aimU, aimV, maxDist),
                        u0, y, v0, aimBrillianceStrength(u0, v0, aimU, aimV, maxDist),
                        r, g, b, baseA);
            }
        }
    }

    private static void subdivideFaceNorth(WorldRenderer wr, double z,
                                           double uMin, double uMax, double vMin, double vMax,
                                           double aimU, double aimV, double maxDist,
                                           float r, float g, float b, float baseA) {
        int n = AIM_BRILLIANCE_GRID;
        double du = (uMax - uMin) / n;
        double dv = (vMax - vMin) / n;
        for (int i = 0; i < n; i++) {
            double u0 = uMin + du * i;
            double u1 = uMin + du * (i + 1);
            for (int j = 0; j < n; j++) {
                double v0 = vMin + dv * j;
                double v1 = vMin + dv * (j + 1);
                quadBrilliance(wr, u0, v1, z, aimBrillianceStrength(u0, v1, aimU, aimV, maxDist),
                        u1, v1, z, aimBrillianceStrength(u1, v1, aimU, aimV, maxDist),
                        u1, v0, z, aimBrillianceStrength(u1, v0, aimU, aimV, maxDist),
                        u0, v0, z, aimBrillianceStrength(u0, v0, aimU, aimV, maxDist),
                        r, g, b, baseA);
            }
        }
    }

    private static void subdivideFaceSouth(WorldRenderer wr, double z,
                                           double uMin, double uMax, double vMin, double vMax,
                                           double aimU, double aimV, double maxDist,
                                           float r, float g, float b, float baseA) {
        int n = AIM_BRILLIANCE_GRID;
        double du = (uMax - uMin) / n;
        double dv = (vMax - vMin) / n;
        for (int i = 0; i < n; i++) {
            double u0 = uMin + du * i;
            double u1 = uMin + du * (i + 1);
            for (int j = 0; j < n; j++) {
                double v0 = vMin + dv * j;
                double v1 = vMin + dv * (j + 1);
                quadBrilliance(wr, u0, v0, z, aimBrillianceStrength(u0, v0, aimU, aimV, maxDist),
                        u1, v0, z, aimBrillianceStrength(u1, v0, aimU, aimV, maxDist),
                        u1, v1, z, aimBrillianceStrength(u1, v1, aimU, aimV, maxDist),
                        u0, v1, z, aimBrillianceStrength(u0, v1, aimU, aimV, maxDist),
                        r, g, b, baseA);
            }
        }
    }

    private static void subdivideFaceWest(WorldRenderer wr, double x,
                                          double uMin, double uMax, double vMin, double vMax,
                                          double aimU, double aimV, double maxDist,
                                          float r, float g, float b, float baseA) {
        int n = AIM_BRILLIANCE_GRID;
        double du = (uMax - uMin) / n;
        double dv = (vMax - vMin) / n;
        for (int i = 0; i < n; i++) {
            double u0 = uMin + du * i;
            double u1 = uMin + du * (i + 1);
            for (int j = 0; j < n; j++) {
                double v0 = vMin + dv * j;
                double v1 = vMin + dv * (j + 1);
                quadBrilliance(wr, x, v0, u0, aimBrillianceStrength(u0, v0, aimU, aimV, maxDist),
                        x, v0, u1, aimBrillianceStrength(u1, v0, aimU, aimV, maxDist),
                        x, v1, u1, aimBrillianceStrength(u1, v1, aimU, aimV, maxDist),
                        x, v1, u0, aimBrillianceStrength(u0, v1, aimU, aimV, maxDist),
                        r, g, b, baseA);
            }
        }
    }

    private static void subdivideFaceEast(WorldRenderer wr, double x,
                                          double uMin, double uMax, double vMin, double vMax,
                                          double aimU, double aimV, double maxDist,
                                          float r, float g, float b, float baseA) {
        int n = AIM_BRILLIANCE_GRID;
        double du = (uMax - uMin) / n;
        double dv = (vMax - vMin) / n;
        for (int i = 0; i < n; i++) {
            double u0 = uMin + du * i;
            double u1 = uMin + du * (i + 1);
            for (int j = 0; j < n; j++) {
                double v0 = vMin + dv * j;
                double v1 = vMin + dv * (j + 1);
                quadBrilliance(wr, x, v0, u0, aimBrillianceStrength(u0, v0, aimU, aimV, maxDist),
                        x, v1, u0, aimBrillianceStrength(u0, v1, aimU, aimV, maxDist),
                        x, v1, u1, aimBrillianceStrength(u1, v1, aimU, aimV, maxDist),
                        x, v0, u1, aimBrillianceStrength(u1, v0, aimU, aimV, maxDist),
                        r, g, b, baseA);
            }
        }
    }

    private static void addFaceQuads(WorldRenderer wr, EnumFacing side,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     float r, float g, float b, float a,
                                     String linePlacement, float lineWidth, boolean forFill) {
        double[] bounds = boundsForFace(x1, y1, z1, x2, y2, z2, side, linePlacement, lineWidth, forFill);
        x1 = bounds[0];
        y1 = bounds[1];
        z1 = bounds[2];
        x2 = bounds[3];
        y2 = bounds[4];
        z2 = bounds[5];
        switch (side) {
            case DOWN:
                quad(wr, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
                break;
            case UP:
                quad(wr, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, b, a);
                break;
            case NORTH:
                quad(wr, x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1, r, g, b, a);
                break;
            case SOUTH:
                quad(wr, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
                break;
            case WEST:
                quad(wr, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a);
                break;
            case EAST:
            default:
                quad(wr, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
                break;
        }
    }

    private static void vtx(WorldRenderer wr, double x, double y, double z, float r, float g, float b, float a) {
        wr.pos(x, y, z).color(r, g, b, a).endVertex();
    }

    private static void quad(WorldRenderer wr,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4,
                             float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x3, y3, z3).color(r, g, b, a).endVertex();
        wr.pos(x4, y4, z4).color(r, g, b, a).endVertex();
    }
}
