package me.napim.gomod.mixins;

import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures armor layer batches write depth during our entity-outline FBO pass. Vanilla
 * {@code renderLayers} can set {@code depthMask(false)} when it thinks entity outlines
 * are active; combined with the pass-wide {@code depthMask(false)} in
 * {@link RenderGlobalMixin}, boots and chestplates were missing from the outline depth
 * buffer so the glow hugged the skin.
 */
@Mixin(LayerArmorBase.class)
public abstract class LayerArmorBaseOutlineDepthMixin {

    @Inject(method = "doRenderLayer", at = @At("HEAD"), require = 0)
    private void gomod$depthMaskForOutlineFbo(
            EntityLivingBase entitylivingbaseIn,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float scale,
            CallbackInfo ci
    ) {
        if (OutlineRenderingHelper.isModEntityOutlinePassActive()) {
            GlStateManager.depthMask(true);
        }
    }
}
