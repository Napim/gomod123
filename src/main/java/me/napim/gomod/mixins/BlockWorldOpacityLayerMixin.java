package me.napim.gomod.mixins;

import me.napim.gomod.features.BlocksFinder;
import net.minecraft.block.Block;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World opacity for opaque blocks (planks, stone, clay, …).
 *
 * <p>{@code @Mixin(Block.class)} must not {@code extend Block} with {@code @Override} — that merge
 * silently fails (no refmap entry). {@link BlockLeavesWorldOpacityMixin} worked because it uses
 * {@code @Inject} on a subclass that declares {@code getBlockLayer}. Grass/tall grass work without
 * this mixin because they already render on CUTOUT + our CUTOUT blend hook.</p>
 */
@Mixin(Block.class)
public abstract class BlockWorldOpacityLayerMixin {

    @Inject(method = {"getBlockLayer", "func_180664_k"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$worldOpacityGetBlockLayer(CallbackInfoReturnable<EnumWorldBlockLayer> cir) {
        if (BlocksFinder.shouldForceWorldOpacityTranslucentLayer((Block) (Object) this)) {
            cir.setReturnValue(EnumWorldBlockLayer.TRANSLUCENT);
        }
    }

    /** Forge chunk rebuild buckets with {@code canRenderInLayer}, not only {@code getBlockLayer}. */
    @Inject(method = "canRenderInLayer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void gomod$worldOpacityCanRenderInLayer(EnumWorldBlockLayer layer,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (BlocksFinder.shouldForceWorldOpacityTranslucentLayer((Block) (Object) this)) {
            cir.setReturnValue(layer == EnumWorldBlockLayer.TRANSLUCENT);
        }
    }
}
