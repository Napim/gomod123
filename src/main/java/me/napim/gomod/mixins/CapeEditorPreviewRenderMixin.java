package me.napim.gomod.mixins;

import me.napim.gomod.gui.MountPreviewHelper;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cape editor preview: opaque cape depth pass followed by a faded body-only pass.
 */
public final class CapeEditorPreviewRenderMixin {

    private CapeEditorPreviewRenderMixin() {
    }

    @Mixin(RendererLivingEntity.class)
    public static class LivingModelSkipMixin {
        @Inject(
                method = {"renderModel", "func_77036_a"},
                at = @At("HEAD"),
                cancellable = true,
                require = 0
        )
        private void gomod$skipBodyModelOnCapeOnlyPass(
                EntityLivingBase entity,
                float limbSwing,
                float limbSwingAmount,
                float ageInTicks,
                float netHeadYaw,
                float headPitch,
                float scale,
                CallbackInfo ci) {
            if (MountPreviewHelper.isCapeEditorCapeOnlyPass()
                    && MountPreviewHelper.isCapeEditorPreviewEntity(entity)) {
                ci.cancel();
            }
        }
    }

    @Mixin(LayerCape.class)
    public static class CapeLayerMixin {
        @Inject(
                method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
                at = @At("HEAD"),
                cancellable = true,
                require = 0
        )
        private void gomod$suppressCapeOnBodyPass(
                AbstractClientPlayer entity,
                float limbSwing,
                float limbSwingAmount,
                float partialTicks,
                float ageInTicks,
                float netHeadYaw,
                float headPitch,
                float scale,
                CallbackInfo ci) {
            if (MountPreviewHelper.shouldSuppressCapeLayer(entity)) {
                ci.cancel();
            }
        }

        @Inject(
                method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
                at = @At("HEAD"),
                require = 0
        )
        private void gomod$capeOnlyConfigureDepth(
                AbstractClientPlayer entity,
                float limbSwing,
                float limbSwingAmount,
                float partialTicks,
                float ageInTicks,
                float netHeadYaw,
                float headPitch,
                float scale,
                CallbackInfo ci) {
            if (MountPreviewHelper.isCapeEditorCapeOnlyPass()
                    && MountPreviewHelper.isCapeEditorPreviewEntity(entity)) {
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GlStateManager.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(org.lwjgl.opengl.GL11.GL_GREATER, 0.003921569F);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }

        @Inject(
                method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
                at = @At("RETURN"),
            require = 0
        )
        private void gomod$capeOnlyRestoreDepth(
                AbstractClientPlayer entity,
                float limbSwing,
                float limbSwingAmount,
                float partialTicks,
                float ageInTicks,
                float netHeadYaw,
                float headPitch,
                float scale,
                CallbackInfo ci) {
            if (MountPreviewHelper.isCapeEditorCapeOnlyPass()
                    && MountPreviewHelper.isCapeEditorPreviewEntity(entity)) {
                GlStateManager.depthMask(true);
                GlStateManager.enableDepth();
                GlStateManager.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    @Mixin({LayerHeldItem.class, LayerCustomHead.class, LayerArmorBase.class})
    public static class OtherLayersSkipMixin {
        @Inject(method = "doRenderLayer", at = @At("HEAD"), cancellable = true, require = 0)
        private void gomod$skipLayersOnCapeOnlyPass(
                EntityLivingBase entity,
                float limbSwing,
                float limbSwingAmount,
                float partialTicks,
                float ageInTicks,
                float netHeadYaw,
                float headPitch,
                float scale,
                CallbackInfo ci) {
            if (MountPreviewHelper.isCapeEditorCapeOnlyPass()
                    && MountPreviewHelper.isCapeEditorPreviewEntity(entity)) {
                ci.cancel();
            }
        }
    }
}
