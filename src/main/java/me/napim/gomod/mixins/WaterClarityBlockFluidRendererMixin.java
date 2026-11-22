package me.napim.gomod.mixins;

import me.napim.gomod.features.BlockVerticalLoadClip;
import me.napim.gomod.features.WaterClarity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water is meshed via {@link BlockFluidRenderer#renderFluid}. Fluids normally set color with {@code putColorRGB_F} / {@code color()},
 * not {@code putColorMultiplier}, so {@link WaterClarityWorldRendererMixin} alone cannot see those writes. We also patch the vertex
 * buffer range after each {@code renderFluid} (see {@link me.napim.gomod.features.WaterClarity#applyFluidMeshVertexAlphaAfterRender}).
 */
@Mixin(BlockFluidRenderer.class)
public abstract class WaterClarityBlockFluidRendererMixin {

    @Inject(
            method = {
                    "renderFluid(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/block/state/IBlockState;"
                            + "Lnet/minecraft/util/BlockPos;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_178270_a"
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void gomod$fluidWaterHead(IBlockAccess world, IBlockState state, BlockPos pos, WorldRenderer buffer,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (BlockVerticalLoadClip.shouldCullBlockForChunkMesh(pos)) {
            cir.setReturnValue(false);
            return;
        }
        if (WaterClarity.isSurfaceChunkTrackingActive()) {
            WaterClarity.beginFluidRenderIfNeeded(state, buffer);
        }
    }

    @Inject(
            method = {
                    "renderFluid(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/block/state/IBlockState;"
                            + "Lnet/minecraft/util/BlockPos;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_178270_a"
            },
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$fluidWaterReturn(IBlockAccess world, IBlockState state, BlockPos pos, WorldRenderer buffer,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (WaterClarity.isSurfaceChunkTrackingActive()) {
            WaterClarity.endFluidRenderIfNeeded(buffer, state);
        }
    }
}
