package me.napim.gomod.mixins;

import me.napim.gomod.features.BorderIndication;
import me.napim.gomod.features.PlayersPlayMode;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the border indication overlay after world rendering.
 */
@Mixin(EntityRenderer.class)
public abstract class BorderIndicationRendererMixin {

    @Inject(method = "renderWorldPass", at = @At("RETURN"))
    private void gomod$renderBorderIndication(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        try {
            if (PlayersPlayMode.getCurrentRenderTarget() != null) {
                return;
            }
            BorderIndication.render(partialTicks);
        } catch (Exception e) {
            System.err.println("[gomod] BorderIndicationRendererMixin: " + e.getMessage());
        }
    }
}
