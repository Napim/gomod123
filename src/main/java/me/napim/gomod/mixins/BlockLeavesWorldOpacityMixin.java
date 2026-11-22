package me.napim.gomod.mixins;

import me.napim.gomod.features.BlocksFinder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link BlockLeaves} declares its own {@code getBlockLayer} — inject here (not on {@link Block}). */
@Mixin(BlockLeaves.class)
public abstract class BlockLeavesWorldOpacityMixin {

    @Inject(method = {"getBlockLayer", "func_180664_k"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$leavesWorldOpacityLayer(CallbackInfoReturnable<EnumWorldBlockLayer> cir) {
        if (BlocksFinder.shouldForceWorldOpacityTranslucentLayer((Block) (Object) this)) {
            cir.setReturnValue(EnumWorldBlockLayer.TRANSLUCENT);
        }
    }

    @Inject(method = "canRenderInLayer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void gomod$leavesWorldOpacityCanRenderInLayer(EnumWorldBlockLayer layer,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (BlocksFinder.shouldForceWorldOpacityTranslucentLayer((Block) (Object) this)) {
            cir.setReturnValue(layer == EnumWorldBlockLayer.TRANSLUCENT);
        }
    }
}
