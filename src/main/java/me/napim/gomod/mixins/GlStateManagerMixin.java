package me.napim.gomod.mixins;

import me.napim.gomod.features.EntityOpacityGlintBlendTracker;
import me.napim.gomod.features.MountHeadDim;
import me.napim.gomod.features.NametagRenderDebugCapture;
import me.napim.gomod.features.LunarWorldLabelProbe;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.TntPlacementPreview;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.gui.MountPreviewHelper;
import me.napim.gomod.render.NametagRenderState;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces a flat-colour silhouette during our custom entity-outline pass by
 * intercepting all GL-state calls that would otherwise corrupt the glow colour.
 *
 * Problem:
 *   Inside renderEntitySimple → doRender → renderLayers, various code paths
 *   re-enable texturing (RendererLivingEntity re-enables GL_TEXTURE_2D between
 *   renderModel and renderLayers), re-enable lighting, and call color() with
 *   per-armour-piece tints.  Each of these breaks the flat-colour silhouette
 *   we need in the outline FBO, causing the glow to pick up gear colours/textures.
 *
 * Fix:
 *   During outline mode, intercept and cancel:
 *     - color(r,g,b,a) / color(r,g,b) — force the stored glow colour instead
 *     - enableTexture2D()              — keep texturing OFF so geometry uses
 *                                         vertex colour, not texture × vertex
 *     - enableLighting()               — keep lighting OFF so no diffuse shading
 *
 *   Forced chameleon tint (/glowplayer solid color): {@code color()} is still forced so
 *   GL state stays consistent; fragment RGB is pinned by {@link me.napim.gomod.util.ForcedSolidTintTextureEnv}.
 *
 *   The guard is a single boolean read per call — negligible cost during normal
 *   rendering.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

    @Inject(method = "color(FFFF)V", at = @At("RETURN"), require = 0)
    private static void gomod$captureAppliedCountdownGlyphColor(float red, float green,
            float blue, float alpha, CallbackInfo ci) {
        LunarWorldLabelProbe.onGlyphColor();
        NametagRenderDebugCapture.recordAppliedCountdownGlyphColor(red, green, blue, alpha);
    }

    /** GlStateManager's alpha variable is already modified before its raw GL call. */
    @Redirect(
            method = "color(FFFF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V", remap = false),
            require = 0,
            remap = false
    )
    private static void gomod$passFinalColorAlphaToOpenGl(float red, float green, float blue, float alpha) {
        SelfOpacity.beginRawGlColorAlphaFinalScope();
        try {
            GL11.glColor4f(red, green, blue, alpha);
        } finally {
            SelfOpacity.endRawGlColorAlphaFinalScope();
        }
    }

    /**
     * Most GL calls happen in ordinary skins/armor/cosmetics where none of gomod's
     * transparency features owns the render. Keep that path to one compact gate.
     */
    private static boolean gomod$needsCompatibilityInterception() {
        boolean needed = EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()
                || OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.hasForcedChameleonOutlineTint()
                || TntPlacementPreview.isRenderingMobGhost()
                || MountHeadDim.hasAnyGlInterceptionState();
        if (needed) {
            return !RenderProfiler.shouldBypassPlayerHooks();
        }
        try {
            // Mount/cape previews are GUI-only and intentionally share these GL hooks.
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.currentScreen != null
                    && !RenderProfiler.shouldBypassPlayerHooks();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Mount dim: scale the incoming alpha and let vanilla {@code color(FFFF)} run normally so
     * {@link GlStateManager}'s internal color state stays in sync with GL. Cancelling the method
     * and calling {@code GL11.glColor4f} directly desynced state and made the mount disappear
     * (fully transparent) for any opacity slider below 1.0.
     * <p>
     * {@code color(FFF)} delegates to {@code color(FFFF)} with alpha 1.0 in 1.8.9, so this covers both.
     */
    @ModifyVariable(method = "color(FFFF)V", at = @At("HEAD"), ordinal = 3, argsOnly = true, require = 0)
    private static float gomod$mountDimScaleColorAlpha(float alpha) {
        if (!gomod$needsCompatibilityInterception()) return alpha;
        if (OutlineRenderingHelper.isInOutlineMode()) {
            return alpha;
        }
        if (NametagRenderState.isActive()) return alpha;
        if (TntPlacementPreview.isRenderingMobGhost()) {
            return alpha * TntPlacementPreview.getGhostAlpha();
        }
        if (MountHeadDim.shouldApplyMountDimGlHooks()) {
            return alpha * MountHeadDim.getAlphaForActiveMountGl();
        }
        if (MountPreviewHelper.shouldScaleGuiPreviewAlpha()) {
            return alpha * MountPreviewHelper.getGuiPreviewAlpha();
        }
        if (SelfOpacity.shouldScaleAlphaInCurrentPhase()) {
            if (EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
                return alpha;
            }
            if (SelfOpacity.shouldForceWeaveColorAlpha()) {
                return alpha;
            }
            float scaled = alpha * SelfOpacity.getRenderAlpha();
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_GL_COLOR)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_GL_COLOR,
                        "GlStateManager.colorAlpha", null,
                        "input=" + alpha + " output=" + scaled);
            }
            return scaled;
        }
        if (VisibleSpecs.shouldScaleAlphaInCurrentPhase()) {
            if (EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
                return alpha;
            }
            if (VisibleSpecs.shouldForceWeaveColorAlpha()) {
                return alpha;
            }
            return alpha * VisibleSpecs.getRenderOpacity();
        }
        if (MountPreviewHelper.isCapeEditorBodyOpacityPass()) {
            return alpha * MountPreviewHelper.getCapeEditorBodyAlpha();
        }
        return alpha;
    }

    // Enchant glint blend is recorded from LWJGL {@code GL11.glBlendFunc} / {@code GL14.glBlendFuncSeparate}
    // ({@link Gl11GlBlendFuncMixin}, {@link Gl14GlBlendFuncSeparateMixin}) so OptiFine paths are not missed.
    // Do not also inject here or GlStateManager calls double-count against {@link EntityOpacityGlintBlendTracker}.

    // ── colour overrides ────────────────────────────────────────────────────

    @Inject(method = "color(FFFF)V", at = @At("HEAD"), cancellable = true)
    private static void gomod$forceGlowColor4(float r, float g, float b, float a, CallbackInfo ci) {
        if (!gomod$needsCompatibilityInterception()) return;
        if (OutlineRenderingHelper.isInOutlineMode()) {
            int rgb = OutlineRenderingHelper.getGlowColorRgb();
            GL11.glColor4f(
                    ((rgb >> 16) & 0xFF) / 255.0f,
                    ((rgb >>  8) & 0xFF) / 255.0f,
                    ( rgb        & 0xFF) / 255.0f,
                    1.0f);
            ci.cancel();
            return;
        }
        if (OutlineRenderingHelper.hasForcedChameleonOutlineTint()) {
            int rgb = OutlineRenderingHelper.getForcedChameleonOutlineTintRgb();
            // Opaque only: partial alphas from armour layers would modulate against texture and read as uneven tint.
            GL11.glColor4f(
                    ((rgb >> 16) & 0xFF) / 255.0f,
                    ((rgb >>  8) & 0xFF) / 255.0f,
                    ( rgb        & 0xFF) / 255.0f,
                    1.0f);
            ci.cancel();
            return;
        }
        if (NametagRenderState.isActive()) return;
        if (SelfOpacity.shouldForceWeaveColorAlpha()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            // Lunar direct path: force the actual GL colour because its rewritten player renderer can bypass the
            // vanilla colour call shape used by the Forge deferred implementation.
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_GL_COLOR)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_GL_COLOR,
                        "GlStateManager.color4fForced", null,
                        "inputAlpha=" + a + " outputAlpha=" + (a * SelfOpacity.getRenderAlpha()));
            }
            SelfOpacity.beginRawGlColorAlphaFinalScope();
            try {
                GL11.glColor4f(r, g, b, a * SelfOpacity.getRenderAlpha());
            } finally {
                SelfOpacity.endRawGlColorAlphaFinalScope();
            }
            ci.cancel();
            return;
        }
        if (VisibleSpecs.shouldForceWeaveColorAlpha()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            SelfOpacity.beginRawGlColorAlphaFinalScope();
            try {
                GL11.glColor4f(r, g, b, a * VisibleSpecs.getRenderOpacity());
            } finally {
                SelfOpacity.endRawGlColorAlphaFinalScope();
            }
            ci.cancel();
        }
    }

    @Inject(method = "color(FFF)V", at = @At("HEAD"), cancellable = true)
    private static void gomod$forceGlowColor3(float r, float g, float b, CallbackInfo ci) {
        if (!gomod$needsCompatibilityInterception()) return;
        if (OutlineRenderingHelper.isInOutlineMode()) {
            int rgb = OutlineRenderingHelper.getGlowColorRgb();
            GL11.glColor4f(
                    ((rgb >> 16) & 0xFF) / 255.0f,
                    ((rgb >>  8) & 0xFF) / 255.0f,
                    ( rgb        & 0xFF) / 255.0f,
                    1.0f);
            ci.cancel();
            return;
        }
        if (OutlineRenderingHelper.hasForcedChameleonOutlineTint()) {
            int rgb = OutlineRenderingHelper.getForcedChameleonOutlineTintRgb();
            GL11.glColor4f(
                    ((rgb >> 16) & 0xFF) / 255.0f,
                    ((rgb >>  8) & 0xFF) / 255.0f,
                    ( rgb        & 0xFF) / 255.0f,
                    1.0f);
            ci.cancel();
            return;
        }
        if (NametagRenderState.isActive()) return;
        if (SelfOpacity.shouldForceWeaveColorAlpha()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_GL_COLOR)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_GL_COLOR,
                        "GlStateManager.color3fForced", null,
                        "inputAlpha=1.0 outputAlpha=" + SelfOpacity.getRenderAlpha());
            }
            SelfOpacity.beginRawGlColorAlphaFinalScope();
            try {
                GL11.glColor4f(r, g, b, SelfOpacity.getRenderAlpha());
            } finally {
                SelfOpacity.endRawGlColorAlphaFinalScope();
            }
            ci.cancel();
            return;
        }
        if (VisibleSpecs.shouldForceWeaveColorAlpha()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            SelfOpacity.beginRawGlColorAlphaFinalScope();
            try {
                GL11.glColor4f(r, g, b, VisibleSpecs.getRenderOpacity());
            } finally {
                SelfOpacity.endRawGlColorAlphaFinalScope();
            }
            ci.cancel();
        }
    }

    // ── prevent texture / lighting re-enable ────────────────────────────────

    /**
     * RendererLivingEntity.doRender() calls enableTexture2D() after renderModel
     * and before renderLayers.  If texturing is on, the armour layer geometry is
     * shaded by the bound armour texture instead of using our flat vertex colour.
     * Cancelling the call keeps GL_TEXTURE_2D disabled for the entire entity.
     */
    @Inject(method = "enableTexture2D", at = @At("HEAD"), cancellable = true)
    private static void gomod$blockEnableTexture2D(CallbackInfo ci) {
        if (!OutlineRenderingHelper.isInOutlineMode()) return;
        if (NametagRenderState.isActive()) return;
        if (!RenderProfiler.shouldBypassPlayerHooks()) ci.cancel();
    }

    /**
     * Some render paths re-enable lighting mid-entity.  With lighting on the
     * silhouette gets diffuse shading instead of a pure flat fill, causing
     * non-uniform colour in the outline FBO.
     */
    @Inject(method = "enableLighting", at = @At("HEAD"), cancellable = true)
    private static void gomod$blockEnableLighting(CallbackInfo ci) {
        if (!OutlineRenderingHelper.isInOutlineMode()) return;
        if (!RenderProfiler.shouldBypassPlayerHooks()) ci.cancel();
    }

    // ── mount dim: keep blend on + alpha test off while rendering the mount ──

    /**
     * Inner rendering code (doRender, renderModel, layers) calls {@code disableBlend()}.
     * During mount dim render we must keep blend enabled or alpha has no visual effect.
     */
    @Inject(method = "disableBlend", at = @At("HEAD"), cancellable = true)
    private static void gomod$keepBlendForMountDim(CallbackInfo ci) {
        SelfOpacity.noteGuiInventoryDisableBlendAttempt();
        if (!gomod$needsCompatibilityInterception()) return;
        boolean keepBlend = MountHeadDim.isRenderingMountDim()
                || MountHeadDim.shouldApplyMountDimGlHooks()
                || SelfOpacity.shouldKeepBlendEnabled()
                || VisibleSpecs.shouldKeepBlendEnabled()
                || TntPlacementPreview.isRenderingMobGhost()
                || MountPreviewHelper.shouldKeepGuiPreviewBlendEnabled()
                || MountPreviewHelper.isCapeEditorBodyOpacityPass();
        if (keepBlend) {
            ci.cancel();
            if (SelfOpacity.shouldKeepFirstPersonHeldItemBlendEnabled()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_BLEND_BLOCK)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_BLEND_BLOCK,
                        "GlStateManager.disableBlendBlocked", null,
                        "first-person opacity blend preserved=true");
            }
            if (SelfOpacity.isSelfOpacityDebugEnabled() && SelfOpacity.shouldKeepBlendEnabled()) {
                SelfOpacity.enqueueSelfOpacityDebugLine("[GL] cancel disableBlend nest="
                        + EntityOpacityGlintBlendTracker.peekDeferredFlushNesting()
                        + " glintDepth=" + EntityOpacityGlintBlendTracker.peekGlintBlendDepth()
                        + " arm=" + (SelfOpacity.isFirstPersonArmActive() ? 1 : 0)
                        + " item=" + (SelfOpacity.isFirstPersonHeldItemOpacityActive() ? 1 : 0)
                        + " scale=" + (SelfOpacity.shouldScaleAlphaInCurrentPhase() ? 1 : 0)
                        + " alpha=" + String.format(java.util.Locale.ROOT, "%.2f", SelfOpacity.getRenderAlpha()));
            }
            // Self opacity / VisibleSpecs COLOR: vanilla already hit glBlendFunc(770,771) before disableBlend,
            // so depth is often 0 here — still force full blend stack (OptiFine + GlStateManager desync).
            if (SelfOpacity.shouldKeepBlendEnabled() || VisibleSpecs.shouldKeepBlendEnabled()) {
                EntityOpacityGlintBlendTracker.reapplyDeferredOpacityBlendAfterCancelledDisableBlend();
            } else {
                EntityOpacityGlintBlendTracker.restoreStandardBlendIfGlintDepthNonZero();
            }
        }
    }

    @Inject(method = "depthMask", at = @At("HEAD"), cancellable = true)
    private static void gomod$forceNoDepthWritesForExternalSelfOpacityCosmetic(boolean flag, CallbackInfo ci) {
        if (!gomod$needsCompatibilityInterception()) return;
        if (flag && SelfOpacity.shouldForceExternalCosmeticNoDepthWrite()) {
            GL11.glDepthMask(false);
            ci.cancel();
            return;
        }
        if (flag && (VisibleSpecs.shouldForceExternalCosmeticNoDepthWrite()
                || MountPreviewHelper.isGuiMountColorPass()
                || MountHeadDim.getMountHybridPhase() == MountHeadDim.MountHybridPhase.COLOR
                && MountHeadDim.shouldApplyMountDimGlHooks())) {
            GL11.glDepthMask(false);
            ci.cancel();
        }
    }

    /**
     * Inner code may re-enable the alpha test which discards fragments below the
     * threshold (0.1).  For low dim alpha values this could clip the mount.
     *
     * Block during the entire mount render, including passenger sub-renders.
     * When mountPassengerGlSuppressDepth > 0 (player rendering inside mount),
     * shouldApplyMountDimGlHooks() returns false so this was not being blocked.
     * The player render code calls enableAlpha() which re-enabled GL_ALPHA_TEST,
     * causing remaining mount geometry (post-passenger) to have alpha test active,
     * discarding low-alpha fragments and causing the flashing effect.
     */
    @Inject(method = "enableAlpha", at = @At("HEAD"), cancellable = true)
    private static void gomod$blockAlphaTestForMountDim(CallbackInfo ci) {
        if (!gomod$needsCompatibilityInterception()) return;
        // Block during entire mount render, not just when GL hooks are active
        if (MountHeadDim.isRenderingMountDim()) ci.cancel();
        if (MountHeadDim.shouldApplyMountDimGlHooks()) ci.cancel();
        if (SelfOpacity.shouldKeepAlphaTestDisabled()) ci.cancel();
        if (VisibleSpecs.shouldKeepAlphaTestDisabled()) ci.cancel();
    }

    /**
     * Patcher's "Ridden Horse Opacity" feature restores by calling disableAlpha()
     * after horse render. During gomod's transparent mount pass that can punch
     * holes / flicker even when Patcher's slider says 100%, because the feature's
     * internal "was transparent" flag can survive a settings change.
     */
    @Inject(method = "disableAlpha", at = @At("HEAD"), cancellable = true)
    private static void gomod$blockDisableAlphaForMountDim(CallbackInfo ci) {
        if (!gomod$needsCompatibilityInterception()) return;
        if (MountHeadDim.isRenderingMountDim() || MountPreviewHelper.isGuiMountOpacityPass()) ci.cancel();
    }

    @Inject(method = "alphaFunc", at = @At("HEAD"), cancellable = true)
    private static void gomod$keepLowAlphaCutoffForMountDim(int func, float ref, CallbackInfo ci) {
        if (!gomod$needsCompatibilityInterception()) return;
        if (MountPreviewHelper.isGuiMountOpacityPass()) {
            GL11.glAlphaFunc(GL11.GL_GREATER, MountPreviewHelper.getGuiMountAlphaCutoff());
            ci.cancel();
            return;
        }
        if (MountPreviewHelper.isCapeEditorBodyOpacityPass()) {
            GL11.glAlphaFunc(GL11.GL_GREATER,
                    Math.max(0.0001F, MountPreviewHelper.getCapeEditorBodyAlpha() / 255.0F));
            ci.cancel();
            return;
        }
        if (MountHeadDim.isRenderingMountDim() && !MountHeadDim.isMountHybridPrepassPhase()) {
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
            ci.cancel();
        }
    }
}
