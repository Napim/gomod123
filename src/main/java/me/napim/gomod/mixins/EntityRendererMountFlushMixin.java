package me.napim.gomod.mixins;

import me.napim.gomod.features.MountHeadDim;
import me.napim.gomod.features.MobHealthDisplay;
import me.napim.gomod.features.MobNametags;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.LightningRenderFix;
import me.napim.gomod.render.DeferredNametagRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pass 4: flush transparent mount after the full world pass ({@code mountFlushMode == 4}).
 * <p>
 * {@link VisibleSpecs} and {@link SelfOpacity} normally flush after translucent blocks in {@link RenderGlobalMixin}.
 * If that layer is skipped for a frame, both use {@link #gomod$flushDeferredMountAfterRenderWorld} fallback flushes
 * (translucent-entered flag set at {@code renderBlockLayer(TRANSLUCENT)} HEAD).
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMountFlushMixin {

    @Inject(method = {"renderWorld", "func_78471_a"}, at = @At("RETURN"), require = 0)
    private void gomod$flushDeferredMountAfterRenderWorld(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (LightningRenderFix.hasDeferred()) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getRenderManager() != null) {
                    LightningRenderFix.flushDeferred(mc.getRenderManager());
                }
            } catch (Throwable t) {
                System.err.println("[gomod] flushDeferredLightning (renderWorld fallback): " + t.getMessage());
            }
        }
        // Preserve back-to-front alpha composition if the normal translucent-layer hooks were skipped.
        if (VisibleSpecs.needsRenderWorldFallbackFlush()) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getRenderManager() != null) {
                    VisibleSpecs.flushDeferred(mc.getRenderManager());
                }
            } catch (Throwable t) {
                System.err.println("[gomod] flushDeferredVisibleSpecs (renderWorld fallback): " + t.getMessage());
            }
        }
        if (SelfOpacity.needsRenderWorldFallbackFlush()) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getRenderManager() != null) {
                    SelfOpacity.flushDeferred(mc.getRenderManager(), SelfOpacity.DeferredFlushSite.RENDER_WORLD_FALLBACK);
                }
            } catch (Throwable t) {
                System.err.println("[gomod] flushDeferredSelfOpacity (renderWorld fallback): " + t.getMessage());
            }
        }
        if (MountHeadDim.shouldFlushAfterRenderWorld()) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getRenderManager() != null) {
                    MountHeadDim.flushDeferredMountRender(mc.getRenderManager());
                }
            } catch (Throwable t) {
                System.err.println("[gomod] flushDeferredMount (renderWorld): " + t.getMessage());
            }
        }
        // Weave has already flushed deferred chest-obstructed labels from its RenderWorldEvent while the camera
        // transform is valid. Replaying this Forge fallback at renderWorld RETURN draws screen-anchored/flying tags
        // and duplicates labels rendered earlier in the same frame.
        if (!GomodFixes.isWeaveRuntime() && PlayersPlayMode.getCurrentRenderTarget() == null) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getFramebuffer() != null) {
                    mc.getFramebuffer().bindFramebuffer(false);
                }
                boolean forceNametagsThroughChests = VisibleSpecs.shouldAllowNametagThroughBlocksNow();
                DeferredNametagRenderer.flush();
                MobNametags.renderWorldMobNametags(partialTicks, forceNametagsThroughChests);
                MobHealthDisplay.renderWorldHealthNametags(partialTicks, forceNametagsThroughChests);
            } catch (Throwable t) {
                System.err.println("[gomod] flushDeferredNametags (renderWorld fallback): " + t.getMessage());
            }
        }
    }
}
