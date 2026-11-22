package me.napim.gomod.mixins;

import me.napim.gomod.features.BarrierVisibility;
import me.napim.gomod.features.BarrierVisibilityRangeTicker;
import me.napim.gomod.features.ConfigManager;
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
 * Native Weave/Lunar barrier hook. Lunar's BlockBarrier already has getBlockLayer(), so the
 * Forge-style method merge in {@link BlockBarrierMixin} would create a duplicate method.
 */
@Mixin(net.minecraft.block.BlockBarrier.class)
public abstract class BlockBarrierWeaveMixin {

    @Inject(method = {"getRenderType", "func_149645_b"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$barrierVisibilityRenderType(CallbackInfoReturnable<Integer> cir) {
        if (BarrierVisibility.isEffectivelyEnabled()) {
            cir.setReturnValue(3);
        }
    }

    @Inject(method = {"getBlockLayer", "func_180664_k"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$barrierVisibilityBlockLayer(CallbackInfoReturnable<EnumWorldBlockLayer> cir) {
        if (!BarrierVisibility.isEffectivelyEnabled()) {
            return;
        }
        if (ConfigManager.getBarrierVisibilityOpacityPercent() < 100) {
            cir.setReturnValue(EnumWorldBlockLayer.TRANSLUCENT);
            return;
        }
        cir.setReturnValue(EnumWorldBlockLayer.CUTOUT);
    }

    @Inject(method = {"shouldSideBeRendered", "func_176225_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$barrierVisibilityShouldSide(IBlockAccess worldIn, BlockPos pos, EnumFacing side,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!BarrierVisibility.isEffectivelyEnabled()) {
            return;
        }
        try {
            if (worldIn.getBlockState(pos).getBlock() == Blocks.barrier) {
                cir.setReturnValue(false);
                return;
            }
            BlockPos myPos = pos.offset(side.getOpposite());
            if (ConfigManager.isBarrierVisibilityAimedOnly()) {
                cir.setReturnValue(cursorRevealActive() && isInsideCursorRevealCube(myPos));
                return;
            }
            if (ConfigManager.isBarrierVisibilityRangeEnabled()
                    && !isInsideCursorReveal(myPos)
                    && isOutsideConfiguredRange(myPos)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isOutsideConfiguredRange(BlockPos pos) {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            return false;
        }
        double dx = (pos.getX() + 0.5) - p.posX;
        double dy = (pos.getY() + 0.5) - p.posY;
        double dz = (pos.getZ() + 0.5) - p.posZ;
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
}
