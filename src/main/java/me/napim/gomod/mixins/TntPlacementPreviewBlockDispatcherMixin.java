package me.napim.gomod.mixins;

import me.napim.gomod.features.TntPlacementPreviewGhostVertex;
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
 * Fallback vertex-alpha pass after ghost {@code renderBlock} (solid blocks use brightness, not color multiplier).
 */
@Mixin(BlockRendererDispatcher.class)
public abstract class TntPlacementPreviewBlockDispatcherMixin {

    @Inject(
            method = {"renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_175018_a"},
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$tntGhostMeshHead(IBlockState state, BlockPos pos, IBlockAccess blockAccess,
                                         WorldRenderer worldRenderer, CallbackInfoReturnable<Boolean> cir) {
        if (TntPlacementPreviewGhostVertex.isActive()) {
            TntPlacementPreviewGhostVertex.beginBlockMesh(worldRenderer);
        }
    }

    @Inject(
            method = {"renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_175018_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$tntGhostMeshReturn(IBlockState state, BlockPos pos, IBlockAccess blockAccess,
                                          WorldRenderer worldRenderer, CallbackInfoReturnable<Boolean> cir) {
        if (TntPlacementPreviewGhostVertex.isActive()) {
            TntPlacementPreviewGhostVertex.finishBlockMesh(worldRenderer);
        }
    }
}
