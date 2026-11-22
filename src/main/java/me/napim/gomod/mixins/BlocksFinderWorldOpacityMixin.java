package me.napim.gomod.mixins;



import me.napim.gomod.features.BlocksFinder;

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

 * Patches chunk-mesh vertex alpha for non-target blocks while chunks rebuild (x-ray world opacity).

 */

@Mixin(BlockRendererDispatcher.class)

public abstract class BlocksFinderWorldOpacityMixin {



    @Inject(
            method = {"renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_175018_a"},
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$worldOpacityHead(IBlockState state, BlockPos pos, IBlockAccess blockAccess,
                                        WorldRenderer worldRenderer, CallbackInfoReturnable<Boolean> cir) {
        BlocksFinder.beginWorldOpacityChunkBlock(state, worldRenderer);
    }

    @Inject(
            method = {"renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;"
                    + "Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    "func_175018_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$worldOpacityReturn(IBlockState state, BlockPos pos, IBlockAccess blockAccess,
                                          WorldRenderer worldRenderer, CallbackInfoReturnable<Boolean> cir) {
        BlocksFinder.endWorldOpacityChunkBlock(state, worldRenderer);
    }

}


