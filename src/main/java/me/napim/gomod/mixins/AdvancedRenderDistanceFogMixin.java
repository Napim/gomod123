package me.napim.gomod.mixins;

import me.napim.gomod.features.AdvancedRenderDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clips terrain fog to the block-precise render distance when it does not align to 16-block chunks.
 * When OptiFine/vanilla fog is disabled, terrain fog setup is replaced so sub-chunk distances still clip.
 */
@Mixin(EntityRenderer.class)
public abstract class AdvancedRenderDistanceFogMixin {

    @Shadow
    private float farPlaneDistance;

    @Inject(
            method = {"setupCameraTransform", "func_78479_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$clampFarPlaneForAdvancedRenderDistance(float partialTicks, int pass, CallbackInfo ci) {
        float cap = AdvancedRenderDistance.getEffectiveFarPlaneDistance();
        if (cap > 0.0F) {
            this.farPlaneDistance = cap;
        }
    }

    @Inject(
            method = {"setupFog", "func_78468_a"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void gomod$overrideTerrainFogWhenAdvancedRenderDistance(int startCoords, float partialTicks, CallbackInfo ci) {
        if (startCoords != 0 || !AdvancedRenderDistance.shouldOverrideTerrainFog()) {
            return;
        }
        AdvancedRenderDistance.applyForcedTerrainFog(startCoords);
        ci.cancel();
    }

    @Redirect(
            method = {"setupFog", "func_78468_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;setFogEnd(F)V"),
            require = 0
    )
    private void gomod$advancedRenderDistanceFogEnd(float fogEnd) {
        if (!AdvancedRenderDistance.shouldOverrideTerrainFog()) {
            GlStateManager.setFogEnd(fogEnd);
            return;
        }
        GlStateManager.setFogEnd(AdvancedRenderDistance.adjustFogEnd(fogEnd));
    }

    @Redirect(
            method = {"setupFog", "func_78468_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;setFogStart(F)V"),
            require = 0
    )
    private void gomod$advancedRenderDistanceFogStart(float fogStart) {
        if (!AdvancedRenderDistance.shouldOverrideTerrainFog()) {
            GlStateManager.setFogStart(fogStart);
            return;
        }
        float vanillaEnd = fogStart * 4.0F;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null) {
                vanillaEnd = mc.gameSettings.renderDistanceChunks * 16.0F;
            }
        } catch (Throwable ignored) {
        }
        GlStateManager.setFogStart(AdvancedRenderDistance.adjustFogStart(fogStart, vanillaEnd));
    }

    @Redirect(
            method = {"setupFog", "func_78468_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;disableFog()V"),
            require = 0
    )
    private void gomod$advancedRenderDistanceKeepFogWhenActive() {
        if (!AdvancedRenderDistance.shouldOverrideTerrainFog()) {
            GlStateManager.disableFog();
        }
    }
}
