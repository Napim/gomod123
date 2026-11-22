package me.napim.gomod.mixins;



import me.napim.gomod.features.BlocksFinder;

import net.minecraft.client.renderer.WorldRenderer;

import org.spongepowered.asm.mixin.Mixin;

import org.spongepowered.asm.mixin.injection.At;

import org.spongepowered.asm.mixin.injection.Inject;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



/**

 * Secondary path: some block models set vertex color via {@code putColorMultiplier} during chunk rebuild.

 */

@Mixin(WorldRenderer.class)

public abstract class BlocksFinderWorldOpacityWorldRendererMixin {



    @Inject(method = {"putColorMultiplier", "func_178978_a"}, at = @At("RETURN"), require = 0)
    private void gomod$worldOpacityAfterPutColor(float red, float green, float blue, int corner, CallbackInfo ci) {
        if (!BlocksFinder.isWorldOpacityChunkPatchActive()) {
            return;
        }
        BlocksFinder.patchWorldOpacityVertexCorner((WorldRenderer) (Object) this, corner);
    }

    /** Solid blocks (stone, dirt) tint via brightness, not {@code putColorMultiplier}. */
    @Inject(method = {"putBrightness4", "func_178963_a"}, at = @At("RETURN"), require = 0)
    private void gomod$worldOpacityAfterPutBrightness(int b0, int b1, int b2, int b3, CallbackInfo ci) {
        if (!BlocksFinder.isWorldOpacityChunkPatchActive()) {
            return;
        }
        WorldRenderer wr = (WorldRenderer) (Object) this;
        for (int corner = 0; corner < 4; corner++) {
            BlocksFinder.patchWorldOpacityVertexCorner(wr, corner);
        }
    }

}


