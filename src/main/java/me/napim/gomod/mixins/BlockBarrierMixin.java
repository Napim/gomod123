package me.napim.gomod.mixins;

import me.napim.gomod.features.BarrierVisibility;
import me.napim.gomod.features.BarrierVisibilityRangeTicker;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBarrier;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Barrier Visibility — patches {@link BlockBarrier} so it actually renders when the
 * feature is enabled. Mirrors the original
 * {@code me.ballmc.BarrierVisibility.mixins.MixinBlockBarrier} weave mixin.
 *
 * <p>Key insight: the mixin class extends {@link Block} so methods that BlockBarrier only
 * <em>inherits</em> (like {@code getBlockLayer} returning {@code CUTOUT} and {@code shouldSideBeRendered}) can be
 * <em>added</em> to BlockBarrier via Mixin's method merging. A plain {@code @Inject} on
 * those names silently no-ops because Mixin can only inject into methods physically present
 * in the target class — that was the second half of the missing-texture bug.
 */
@Mixin(BlockBarrier.class)
public abstract class BlockBarrierMixin extends Block {

    private BlockBarrierMixin(Material blockMaterialIn) {
        super(blockMaterialIn);
    }

    @Inject(method = {"getRenderType", "func_149645_b"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$barrierVisibilityRenderType(CallbackInfoReturnable<Integer> cir) {
        if (BarrierVisibility.isEffectivelyEnabled()) {
            cir.setReturnValue(3);
        }
    }

    /**
     * {@link EnumWorldBlockLayer#CUTOUT} when fully opaque: depth is written so interior/back faces stay hidden.
     * When texture opacity is below 100%, use {@link EnumWorldBlockLayer#TRANSLUCENT} so atlas alpha actually
     * blends; on CUTOUT the alpha test keeps texels above the threshold looking fully solid, so the slider
     * had no visible effect.
     *
     * <p>Nearby-only mode still uses {@link EnumWorldBlockLayer#CUTOUT}: translucent quads in a thin radius
     * re-mesh as you move and sort order shifts every frame, which makes the barrier texture look wrong
     * compared to static (full-map) mode. Opacity is applied in the atlas; cutout alpha-threshold gives
     * stable visuals.</p>
     */
    @Override
    public EnumWorldBlockLayer getBlockLayer() {
        if (BarrierVisibility.isEffectivelyEnabled()) {
            // Opacity must win over range mode: atlas alpha only blends on TRANSLUCENT. On CUTOUT,
            // texels above the alpha test still look fully solid (including hue-shifted vanilla).
            if (ConfigManager.getBarrierVisibilityOpacityPercent() < 100) {
                return EnumWorldBlockLayer.TRANSLUCENT;
            }
            if (ConfigManager.isBarrierVisibilityRangeEnabled()) {
                return EnumWorldBlockLayer.CUTOUT;
            }
            return EnumWorldBlockLayer.CUTOUT;
        }
        return super.getBlockLayer();
    }

    /**
     * Decide whether a specific barrier face should be meshed.
     *
     * <ul>
     *     <li>Merge adjacent barriers into one wall (matches the original mod).</li>
     *     <li>When range mode is on, hide the face if the barrier itself is further from the
     *         player than the configured radius.</li>
     *     <li>When the "show under cursor" option is on, force-render barriers within a small
     *         Chebyshev cube around the block the player is currently aiming at, even if the
     *         range filter would otherwise hide them.</li>
     * </ul>
     *
     * <p>{@code pos} is the neighbor position; the barrier itself sits at
     * {@code pos.offset(side.getOpposite())}.
     */
    @Override
    public boolean shouldSideBeRendered(IBlockAccess worldIn, BlockPos pos, EnumFacing side) {
        if (BarrierVisibility.isEffectivelyEnabled()) {
            try {
                if (worldIn.getBlockState(pos).getBlock() == Blocks.barrier) {
                    return false;
                }
                BlockPos myPos = pos.offset(side.getOpposite());

                // Aimed-only mode: nothing renders unless the barrier is inside the cursor
                // reveal cube. Cursor tracking is implicitly on in this mode, so the
                // `isInsideCursorReveal` check ignores the showAtCursor toggle.
                if (ConfigManager.isBarrierVisibilityAimedOnly()) {
                    if (cursorRevealActive() && isInsideCursorRevealCube(myPos)) {
                        return super.shouldSideBeRendered(worldIn, pos, side);
                    }
                    return false;
                }

                if (ConfigManager.isBarrierVisibilityRangeEnabled()) {
                    if (isInsideCursorReveal(myPos)) {
                        return super.shouldSideBeRendered(worldIn, pos, side);
                    }

                    EntityPlayer p = Minecraft.getMinecraft().thePlayer;
                    if (p != null) {
                        double dx = (myPos.getX() + 0.5) - p.posX;
                        double dy = (myPos.getY() + 0.5) - p.posY;
                        double dz = (myPos.getZ() + 0.5) - p.posZ;
                        int range = ConfigManager.getBarrierVisibilityRange();
                        if (dx * dx + dy * dy + dz * dz > (double) range * range) {
                            return false;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return super.shouldSideBeRendered(worldIn, pos, side);
    }

    /** True only when the Reveal-by-aiming toggle is on AND the cursor cube currently contains the barrier. */
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
}
