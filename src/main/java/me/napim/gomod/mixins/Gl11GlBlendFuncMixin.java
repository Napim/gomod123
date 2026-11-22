package me.napim.gomod.mixins;

import me.napim.gomod.features.EntityOpacityGlintBlendTracker;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.render.NametagRenderState;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GL11.class)
public class Gl11GlBlendFuncMixin {
    private static boolean gomod$directVisibleSpecsColor4f = false;

    @Inject(method = "glDisable", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void gomod$keepRawBlendEnabledForInventory(int capability, CallbackInfo ci) {
        if (capability != GL11.GL_BLEND) return;
        if (SelfOpacity.isGuiInventoryOpacityRenderActive()) {
            SelfOpacity.noteGuiInventoryRawDisableBlendAttempt();
            ci.cancel();
            return;
        }
        if (SelfOpacity.shouldKeepFirstPersonHeldItemBlendEnabled()) {
            ci.cancel();
            EntityOpacityGlintBlendTracker.reapplyDeferredOpacityBlendAfterCancelledDisableBlend();
            if (SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RAW_BLEND_BLOCK)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RAW_BLEND_BLOCK,
                        "GL11.glDisable(GL_BLEND)Blocked", null,
                        "first-person opacity blend preserved=true");
            }
        }
    }

    @Inject(method = "glBlendFunc", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gomod$trackGlBlendFunc(int sfactor, int dfactor, CallbackInfo ci) {
        SelfOpacity.noteGuiInventoryBlendFuncCall();
        if (SelfOpacity.shouldGuardGuiInventoryStandardBlend(sfactor, dfactor)) {
            SelfOpacity.forceGuiInventoryBlendState();
            ci.cancel();
            return;
        }
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return;
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        EntityOpacityGlintBlendTracker.onRawGlBlendFunc(sfactor, dfactor);
    }

    @ModifyVariable(method = "glColor4f(FFFF)V", at = @At("HEAD"), ordinal = 3, argsOnly = true, require = 0, remap = false)
    private static float gomod$scaleRawColor4fAlphaForSelfOpacity(float alpha) {
        SelfOpacity.noteGuiInventoryRawColorCall();
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return alpha;
        if (RenderProfiler.shouldBypassPlayerHooks()) return alpha;
        if (NametagRenderState.isActive()) return alpha;
        if (SelfOpacity.isRawGlColorAlphaFinalScopeActive()) {
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RAW_COLOR)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RAW_COLOR,
                        "GL11.glColor4fAlphaAlreadyFinal", null,
                        "input=" + alpha + " output=" + alpha + " duplicateScalingSkipped=true");
            }
            return alpha;
        }
        if (gomod$directVisibleSpecsColor4f) {
            return alpha;
        }
        if (SelfOpacity.isForcingGuiInventoryRawColor()) {
            return alpha;
        }
        if (SelfOpacity.shouldScaleAlphaInCurrentPhase()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            float scaled = alpha * SelfOpacity.getRenderAlpha();
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RAW_COLOR)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RAW_COLOR,
                        "GL11.glColor4fAlpha", null,
                        "input=" + alpha + " output=" + scaled);
            }
            return scaled;
        }
        if (VisibleSpecs.shouldScaleAlphaInCurrentPhase()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            return alpha * VisibleSpecs.getRenderOpacity();
        }
        return alpha;
    }

    @Inject(method = "glColor3f(FFF)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void gomod$rawColor3fUsesSelfOpacityAlpha(float red, float green, float blue, CallbackInfo ci) {
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return;
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        if (NametagRenderState.isActive()) return;
        if (SelfOpacity.shouldScaleAlphaInCurrentPhase()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            GL11.glColor4f(red, green, blue, 1.0F);
            ci.cancel();
            return;
        }
        if (VisibleSpecs.shouldScaleAlphaInCurrentPhase()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            gomod$directVisibleSpecsColor4f = true;
            try {
                GL11.glColor4f(red, green, blue, VisibleSpecs.getRenderOpacity());
            } finally {
                gomod$directVisibleSpecsColor4f = false;
            }
            ci.cancel();
        }
    }

    @ModifyVariable(method = "glDepthMask(Z)V", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0, remap = false)
    private static boolean gomod$forceRawNoDepthWritesForExternalSelfOpacityCosmetic(boolean flag) {
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return flag;
        if (RenderProfiler.shouldBypassPlayerHooks()) return flag;
        if (flag && SelfOpacity.shouldForceExternalCosmeticNoDepthWrite()) {
            return false;
        }
        if (flag && VisibleSpecs.shouldForceExternalCosmeticNoDepthWrite()) {
            return false;
        }
        return flag;
    }
}
