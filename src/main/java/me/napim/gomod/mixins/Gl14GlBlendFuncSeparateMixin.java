package me.napim.gomod.mixins;

import me.napim.gomod.features.EntityOpacityGlintBlendTracker;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.SelfOpacity;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GL14.class)
public class Gl14GlBlendFuncSeparateMixin {

    @Inject(method = "glBlendFuncSeparate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gomod$trackGlBlendFuncSeparate(
            int srcRGB, int dstRGB, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if (SelfOpacity.shouldGuardGuiInventoryStandardBlend(srcRGB, dstRGB)) {
            SelfOpacity.forceGuiInventoryBlendState();
            ci.cancel();
            return;
        }
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return;
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        EntityOpacityGlintBlendTracker.onRawGlBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }
}
