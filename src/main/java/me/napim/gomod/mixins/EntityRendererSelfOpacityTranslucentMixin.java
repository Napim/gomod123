package me.napim.gomod.mixins;

import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Third-person deferred self opacity must run <strong>after</strong> water/ice (same frame as
 * {@code renderBlockLayer(TRANSLUCENT)}) but <strong>outside</strong> {@link RenderGlobal#renderBlockLayer} — the TAIL
 * inside that method leaves GL matrices in a bad state for {@code renderEntitySimple} when staring into the void /
 * empty sky and with PolyBlur Phosphor. Wrapping the vanilla invoke from {@code EntityRenderer#renderWorldPass} restores
 * correct ordering without that skew.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererSelfOpacityTranslucentMixin {

    private static final String RENDER_BLOCK_LAYER =
            "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer(Lnet/minecraft/util/EnumWorldBlockLayer;DILnet/minecraft/entity/Entity;)I";

    @Redirect(
            method = {"renderWorldPass", "func_175068_a"},
            at = @At(value = "INVOKE", target = RENDER_BLOCK_LAYER)
    )
    private int gomod$selfOpacityAfterTranslucentBlockLayer(
            RenderGlobal renderGlobal,
            EnumWorldBlockLayer blockLayerIn,
            double partialTicks,
            int pass,
            Entity entityIn) {
        int result = renderGlobal.renderBlockLayer(blockLayerIn, partialTicks, pass, entityIn);
        if (blockLayerIn == EnumWorldBlockLayer.TRANSLUCENT) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getRenderManager() != null) {
                    if (VisibleSpecs.hasDeferredRender()) {
                        VisibleSpecs.flushDeferred(mc.getRenderManager());
                    }
                    if (SelfOpacity.hasDeferredRender()
                            && SelfOpacity.shouldFlushSelfOpacityAfterTranslucentWorldPassPostBlockLayer()) {
                        SelfOpacity.flushDeferred(
                                mc.getRenderManager(),
                                SelfOpacity.DeferredFlushSite.TRANSLUCENT_WORLD_PASS_POST_BLOCK_LAYER,
                                pass);
                    }
                }
            } catch (Throwable t) {
                System.err.println("[gomod] flushDeferredSelfOpacity (worldPass post-translucent): " + t.getMessage());
            }
        }
        return result;
    }
}
