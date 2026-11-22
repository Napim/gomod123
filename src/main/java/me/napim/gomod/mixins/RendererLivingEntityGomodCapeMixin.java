package me.napim.gomod.mixins;

import me.napim.gomod.features.ManualGomodCapeRenderer;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RendererLivingEntity.class)
public abstract class RendererLivingEntityGomodCapeMixin {
    @Inject(
            method = "renderLayers(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$renderOwnedLocalCape(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale,
            CallbackInfo ci) {
        if ((Object) this instanceof RenderPlayer && entity instanceof AbstractClientPlayer) {
            ManualGomodCapeRenderer.render((RenderPlayer) (Object) this,
                    (AbstractClientPlayer) entity, limbSwing, limbSwingAmount, partialTicks,
                    ageInTicks, netHeadYaw, headPitch, scale);
        }
    }
}
