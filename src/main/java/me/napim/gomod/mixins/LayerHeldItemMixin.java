package me.napim.gomod.mixins;

import me.napim.gomod.features.PeeEffectManager;
import me.napim.gomod.features.GomodPeerPregameDye;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels LayerHeldItem rendering during our flat-colour outline pass.
 * Without this, the held item (sword, pick, etc.) renders as a solid-colour
 * silhouette that the outline shader adds glow to, making it look like the
 * player is permanently gripping a glowing stick.
 */
@Mixin(LayerHeldItem.class)
public class LayerHeldItemMixin {

    @Redirect(
            method = "doRenderLayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;getHeldItem()Lnet/minecraft/item/ItemStack;"),
            require = 0
    )
    private ItemStack gomod$showPeerPregameDye(EntityLivingBase entity) {
        ItemStack peerDye = GomodPeerPregameDye.heldDyeFor(entity);
        return peerDye != null ? peerDye : entity.getHeldItem();
    }

    @Inject(method = "doRenderLayer", at = @At("HEAD"), cancellable = true)
    private void gomod$skipHeldItemInOutlinePass(
            EntityLivingBase entityIn,
            float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale,
            CallbackInfo ci) {
        if (OutlineRenderingHelper.isInOutlineMode()) {
            ci.cancel();
        }
    }

    /** Block items use an overhead hold pose; skip those extra transforms during /pee. */
    @Redirect(
            method = "doRenderLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V"),
            require = 0
    )
    private void gomod$peeRedirectHeldTranslate(float x, float y, float z, EntityLivingBase entityIn) {
        if (PeeEffectManager.isActiveForPlayer(entityIn)
                && Math.abs(x) < 1.0E-4F && Math.abs(y - 0.1875F) < 1.0E-4F && Math.abs(z + 0.3125F) < 1.0E-4F) {
            return;
        }
        GlStateManager.translate(x, y, z);
    }

    @Redirect(
            method = "doRenderLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V"),
            require = 0
    )
    private void gomod$peeRedirectHeldRotate(float angle, float x, float y, float z, EntityLivingBase entityIn) {
        if (PeeEffectManager.isActiveForPlayer(entityIn)
                && ((Math.abs(angle - 20.0F) < 1.0E-4F && Math.abs(x - 1.0F) < 1.0E-4F)
                || (Math.abs(angle - 45.0F) < 1.0E-4F && Math.abs(y - 1.0F) < 1.0E-4F))) {
            return;
        }
        GlStateManager.rotate(angle, x, y, z);
    }

    @Redirect(
            method = "doRenderLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;scale(FFF)V"),
            require = 0
    )
    private void gomod$peeRedirectHeldScale(float x, float y, float z, EntityLivingBase entityIn) {
        if (PeeEffectManager.isActiveForPlayer(entityIn)
                && Math.abs(x + 0.375F) < 1.0E-4F && Math.abs(y + 0.375F) < 1.0E-4F && Math.abs(z - 0.375F) < 1.0E-4F) {
            return;
        }
        GlStateManager.scale(x, y, z);
    }

    @Inject(
            method = "doRenderLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V"
            ),
            require = 0
    )
    private void gomod$peeThirdPersonHeldItemCorrection(
            EntityLivingBase entityIn,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float scale,
            CallbackInfo ci) {
        if (!PeeEffectManager.isActiveForPlayer(entityIn)) {
            return;
        }
        PeeEffectManager.applyThirdPersonHeldItemCorrection();
    }
}
