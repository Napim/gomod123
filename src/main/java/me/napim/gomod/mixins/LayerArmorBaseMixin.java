package me.napim.gomod.mixins;

import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes glow so it follows the outermost silhouette (gear/armor) rather than
 * just the bare skin.
 *
 * Root cause:
 *   RendererLivingEntity.renderLayers reads RenderManager.renderOutlines to
 *   decide how to render each LayerRenderer.  When renderOutlines=true it
 *   computes:  flag = renderOutlines & !layer.shouldCombineTextures()
 *   For LayerArmorBase, shouldCombineTextures()=false, so flag=true.
 *   When flag=true it calls GlStateManager.depthMask(false) before calling the
 *   layer — meaning the armor fragments are drawn to the COLOR buffer but their
 *   depth is NOT written.  The entity_outline shader then  performs edge-detection
 *   using depth information, so it only sees the body's depth-written silhouette
 *   and not the armor, producing a glow that hugs the body/skin.
 *
 * Fix:
 *   Redirect the GETFIELD of RenderManager.renderOutlines inside renderLayers so
 *   it returns false during our custom glow pass.  This sets flag=false for every
 *   layer, so depthMask(false) is never called and armor renders with full depth
 *   writes just like the body — the outline shader then sees the complete
 *   player silhouette including all gear.
 */
@Mixin(RendererLivingEntity.class)
public abstract class LayerArmorBaseMixin<T extends EntityLivingBase> {

    /**
     * Redirects the GETFIELD of RenderManager.renderOutlines inside
     * RendererLivingEntity.renderLayers.
     *
     * During our outline pass we return false so the per-layer "flag" ends up
     * false for every layer (including armor).  This means:
     *  - depthMask(false) is never set → armor writes to depth, edge-detection works
     *  - No forced team-color tint is applied to individual layers
     *  - All layers render into the framebuffer as a full unified silhouette
     *
     * require=0: silently skips the redirect if the target GETFIELD is absent in
     * this build, so the mod never hard-errors due to mapping differences.
     */
    @Redirect(
        method = "renderLayers",
        require = 0,
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/entity/RenderManager;renderOutlines:Z"
        )
    )
    private boolean gomod$fixArmorDepthInOutlinePass(RenderManager renderManager) {
        // Flat silhouette FBO pass (/glowplayer was flat; teammate flat): pretend renderOutlines
        // is false so vanilla never calls depthMask(false) per layer — armor writes depth.
        if (OutlineRenderingHelper.isInOutlineMode()) {
            return false;
        }
        // Textured chameleon FBO pass (/glowplayer forced tint, /glowplayer2, Player Queuer): outlineMode
        // is false but we set suppressNametag — use that signal so
        // armor layers still write depth and the outline wraps boots/gear like the queuer glow.
        if (OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
            return false;
        }
        if (OutlineRenderingHelper.isModEntityOutlinePassActive()) {
            return false;
        }
        return ((RenderManagerAccessor) renderManager).gomod$getRenderOutlines();
    }
}
