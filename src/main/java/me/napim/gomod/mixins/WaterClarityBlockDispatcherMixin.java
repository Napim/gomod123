package me.napim.gomod.mixins;

import me.napim.gomod.features.BlockVerticalLoadClip;
import me.napim.gomod.features.WaterClarity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks chunk rebuild context (active block + surface-water pass depth) so {@link WaterClarityWorldRendererMixin}
 * can patch water vertex alpha and related tint logic can run only when appropriate.
 */
@Mixin(BlockRendererDispatcher.class)
public abstract class WaterClarityBlockDispatcherMixin {

    @Inject(
            method = {"renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_175018_a"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void gomod$surfaceWaterHead(IBlockState state, BlockPos pos, IBlockAccess blockAccess, WorldRenderer worldRenderer,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (BlockVerticalLoadClip.shouldCullBlockForChunkMesh(pos)) {
            cir.setReturnValue(false);
            return;
        }
        if (WaterClarity.isSurfaceChunkTrackingActive()) {
            WaterClarity.beginChunkRenderBlockIfNeeded(state, worldRenderer);
        }
    }

    @Inject(
            method = {"renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_175018_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$surfaceWaterReturn(IBlockState state, BlockPos pos, IBlockAccess blockAccess, WorldRenderer worldRenderer,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (WaterClarity.isSurfaceChunkTrackingActive()) {
            WaterClarity.endChunkRenderBlockIfNeeded(state, worldRenderer);
        }
    }
}
