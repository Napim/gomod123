package me.napim.gomod.mixins;

import me.napim.gomod.features.LightningSettings;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderLightningBolt;
import net.minecraft.entity.effect.EntityLightningBolt;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client lightning visuals: shorten bolts and optional custom tint.
 */
@Mixin(RenderLightningBolt.class)
public class RenderLightningBoltMixin {

    @Unique
    private boolean gomod123$lightningNerfPushed;

    @Unique
    private EntityLightningBolt gomod123$currentLightningBolt;

    @Unique
    private boolean gomod123$fixRenderActive;

    @Unique
    private boolean gomod123$fixRenderPriorDepthMask;

    @Unique
    private boolean gomod123$alphaCutoffCaptured;

    @Unique
    private int gomod123$priorAlphaFunction;

    @Unique
    private float gomod123$priorAlphaReference;

    @Inject(
            method = {"doRender(Lnet/minecraft/entity/effect/EntityLightningBolt;DDDFF)V", "func_76986_a"},
            at = @At("HEAD"),
            require = 0
    )
    private void gomod123$lowerAlphaCutoffHead(EntityLightningBolt entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        gomod123$currentLightningBolt = entity;
        gomod123$alphaCutoffCaptured = false;
        try {
            gomod123$priorAlphaFunction = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            gomod123$priorAlphaReference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            gomod123$alphaCutoffCaptured = true;
            // Vanilla's inherited 0.1 alpha-test threshold culls low-opacity lightning entirely.
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.001f);
        } catch (Throwable ignored) {
            gomod123$alphaCutoffCaptured = false;
        }
    }

    @Inject(
            method = {"doRender(Lnet/minecraft/entity/effect/EntityLightningBolt;DDDFF)V", "func_76986_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod123$restoreAlphaCutoffReturn(EntityLightningBolt entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (gomod123$alphaCutoffCaptured) {
            try {
                GlStateManager.alphaFunc(gomod123$priorAlphaFunction, gomod123$priorAlphaReference);
            } catch (Throwable ignored) {
            }
            gomod123$alphaCutoffCaptured = false;
        }
        gomod123$currentLightningBolt = null;
    }

    @Inject(
            method = {"doRender(Lnet/minecraft/entity/effect/EntityLightningBolt;DDDFF)V", "func_76986_a"},
            at = @At("HEAD"),
            require = 0
    )
    private void gomod123$fixRenderHead(EntityLightningBolt entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        gomod123$fixRenderActive = false;
        if (!LightningSettings.isFixRenderEnabled()) {
            return;
        }
        gomod123$fixRenderActive = true;
        gomod123$fixRenderPriorDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GlStateManager.depthMask(false);
    }

    @Inject(
            method = {"doRender(Lnet/minecraft/entity/effect/EntityLightningBolt;DDDFF)V", "func_76986_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod123$fixRenderReturn(EntityLightningBolt entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (!gomod123$fixRenderActive) {
            return;
        }
        GlStateManager.depthMask(gomod123$fixRenderPriorDepthMask);
        gomod123$fixRenderActive = false;
    }

    @Inject(
            method = {"doRender(Lnet/minecraft/entity/effect/EntityLightningBolt;DDDFF)V", "func_76986_a"},
            at = @At("HEAD"),
            require = 0
    )
    private void gomod123$lightningNerfHead(EntityLightningBolt entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        gomod123$lightningNerfPushed = false;
        if (!LightningSettings.isNerfEnabled()) {
            return;
        }
        int n = LightningSettings.getBottomBlocks();
        if (n <= 0) {
            return;
        }
        try {
            GlStateManager.pushMatrix();
            gomod123$lightningNerfPushed = true;
            float blocks = Math.min(64f, n);
            float shrink = Math.max(0.08f, 1.0f - blocks * 0.018f);
            float lift = blocks * 1.4f;
            GlStateManager.translate(0.0f, lift, 0.0f);
            GlStateManager.scale(1.0f, shrink, 1.0f);
            GlStateManager.translate(0.0f, -lift * 0.35f, 0.0f);
        } catch (Throwable ignored) {
            if (gomod123$lightningNerfPushed) {
                try {
                    GlStateManager.popMatrix();
                } catch (Throwable ignored2) {
                }
                gomod123$lightningNerfPushed = false;
            }
        }
    }

    @Inject(
            method = {"doRender(Lnet/minecraft/entity/effect/EntityLightningBolt;DDDFF)V", "func_76986_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod123$lightningNerfReturn(EntityLightningBolt entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (!gomod123$lightningNerfPushed) {
            return;
        }
        try {
            GlStateManager.popMatrix();
        } catch (Throwable ignored) {
        }
        gomod123$lightningNerfPushed = false;
    }

    @Redirect(
            method = {"doRender(Lnet/minecraft/entity/effect/EntityLightningBolt;DDDFF)V", "func_76986_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WorldRenderer;color(FFFF)Lnet/minecraft/client/renderer/WorldRenderer;"
            ),
            require = 0
    )
    private WorldRenderer gomod123$lightningTint(WorldRenderer renderer, float r, float g, float b, float a) {
        float[] tint = LightningSettings.thunderTintComponents(gomod123$currentLightningBolt, r, g, b, a);
        return renderer.color(tint[0], tint[1], tint[2], tint[3]);
    }
}
