package me.napim.gomod.mixins;

import me.napim.gomod.features.EntityOpacityGlintBlendTracker;
import me.napim.gomod.features.FirstPersonItemVertexAlpha;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.RenderProfiler;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererSelfOpacityColorMixin {

    @ModifyVariable(
            method = {"color(FFFF)Lnet/minecraft/client/renderer/WorldRenderer;", "func_181666_a"},
            at = @At("HEAD"),
            ordinal = 3,
            argsOnly = true,
            require = 0
    )
    private float gomod$scaleSelfOpacityVertexAlphaFloat(float alpha) {
        // Held items receive one alpha multiplier after all quad colors are written.
        if (SelfOpacity.isFirstPersonHeldItemOpacityActive()) return alpha;
        if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_HOOK)) {
            SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_HOOK,
                    "WorldRenderer.vertexAlpha", null, "inputFloat=" + alpha);
        }
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return alpha;
        if (RenderProfiler.shouldBypassPlayerHooks()) return alpha;
        if (SelfOpacity.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            float scaled = alpha * SelfOpacity.getRenderAlpha();
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_SCALE)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_SCALE,
                        "WorldRenderer.vertexAlphaScaled", null,
                        "inputFloat=" + alpha + " outputFloat=" + scaled);
            }
            return scaled;
        }
        if (VisibleSpecs.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            return alpha * VisibleSpecs.getRenderOpacity();
        }
        return alpha;
    }

    @ModifyVariable(
            method = {"color(IIII)Lnet/minecraft/client/renderer/WorldRenderer;", "func_181669_b"},
            at = @At("HEAD"),
            ordinal = 3,
            argsOnly = true,
            require = 0
    )
    private int gomod$scaleSelfOpacityVertexAlphaInt(int alpha) {
        // Held items receive one alpha multiplier after all quad colors are written.
        if (SelfOpacity.isFirstPersonHeldItemOpacityActive()) return alpha;
        if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_HOOK)) {
            SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_HOOK,
                    "WorldRenderer.vertexAlpha", null, "inputInt=" + alpha);
        }
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return alpha;
        if (RenderProfiler.shouldBypassPlayerHooks()) return alpha;
        if (SelfOpacity.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            int scaled = Math.max(0, Math.min(255, Math.round(alpha * SelfOpacity.getRenderAlpha())));
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_SCALE)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_SCALE,
                        "WorldRenderer.vertexAlphaScaled", null,
                        "inputInt=" + alpha + " outputInt=" + scaled);
            }
            return scaled;
        }
        if (VisibleSpecs.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            return Math.max(0, Math.min(255, Math.round(alpha * VisibleSpecs.getRenderOpacity())));
        }
        return alpha;
    }

    @ModifyVariable(
            method = {"putColor4", "func_178968_d"},
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true,
            require = 0
    )
    private int gomod$scaleSelfOpacityPackedArgb(int argb) {
        // Held items receive one alpha multiplier after all quad colors are written.
        if (SelfOpacity.isFirstPersonHeldItemOpacityActive()) return argb;
        if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_HOOK)) {
            SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_HOOK,
                    "WorldRenderer.packedVertexAlpha", null, "inputArgbAlpha=" + ((argb >>> 24) & 0xFF));
        }
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return argb;
        if (RenderProfiler.shouldBypassPlayerHooks()) return argb;
        if (SelfOpacity.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            int alpha = (argb >>> 24) & 0xFF;
            int scaled = Math.max(0, Math.min(255, Math.round(alpha * SelfOpacity.getRenderAlpha())));
            if (SelfOpacity.isFirstPersonHeldItemOpacityActive()
                    && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_SCALE)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_SCALE,
                        "WorldRenderer.vertexAlphaScaled", null,
                        "inputArgbAlpha=" + alpha + " outputArgbAlpha=" + scaled);
            }
            return (argb & 0x00FFFFFF) | (scaled << 24);
        }
        if (VisibleSpecs.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            int alpha = (argb >>> 24) & 0xFF;
            int scaled = Math.max(0, Math.min(255, Math.round(alpha * VisibleSpecs.getRenderOpacity())));
            return (argb & 0x00FFFFFF) | (scaled << 24);
        }
        return argb;
    }

    /** Tessellator calls finishDrawing before uploading the finalized color attribute. */
    @Inject(method = "finishDrawing()V", at = @At("RETURN"), require = 1)
    private void gomod$finalizeHeldItemVertexAlpha(CallbackInfo ci) {
        if (!SelfOpacity.isFirstPersonHeldItemOpacityActive()
                || !SelfOpacity.shouldScaleVertexAlphaOnRenderThread()
                || RenderProfiler.shouldBypassPlayerHooks()
                || EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) return;
        WorldRenderer renderer = (WorldRenderer) (Object) this;
        VertexFormat format = renderer.getVertexFormat();
        if (format == null || !format.hasColor()) return;
        ByteBuffer buffer = renderer.getByteBuffer();
        int vertices = FirstPersonItemVertexAlpha.scale(buffer, renderer.getVertexCount(),
                format.getNextOffset(), format.getColorOffset(), SelfOpacity.getRenderAlpha());
        if (SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_BUFFER)) {
            SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_VERTEX_BUFFER,
                    "WorldRenderer.finishDrawing:finalVertexAlpha", null,
                    "vertices=" + vertices + " stride=" + format.getNextOffset()
                            + " colorOffset=" + format.getColorOffset()
                            + " alpha=" + SelfOpacity.getRenderAlpha());
        }
    }
}
