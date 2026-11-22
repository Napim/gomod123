package me.napim.gomod.mixins;

import me.napim.gomod.features.TntPlacementPreviewGhostVertex;
import net.minecraft.client.renderer.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Patches block-model vertex alpha while {@link me.napim.gomod.features.TntPlacementPreview} draws the ghost TNT.
 */
@Mixin(WorldRenderer.class)
public abstract class TntPlacementPreviewWorldRendererMixin {

    @Inject(method = {"putColorMultiplier", "func_178978_a"}, at = @At("RETURN"), require = 0)
    private void gomod$tntGhostAfterPutColor(float red, float green, float blue, int corner, CallbackInfo ci) {
        TntPlacementPreviewGhostVertex.patchCorner((WorldRenderer) (Object) this, corner);
    }

    @Inject(method = {"putBrightness4", "func_178963_a"}, at = @At("RETURN"), require = 0)
    private void gomod$tntGhostAfterPutBrightness(int b0, int b1, int b2, int b3, CallbackInfo ci) {
        WorldRenderer wr = (WorldRenderer) (Object) this;
        for (int corner = 0; corner < 4; corner++) {
            TntPlacementPreviewGhostVertex.patchCorner(wr, corner);
        }
    }
}
