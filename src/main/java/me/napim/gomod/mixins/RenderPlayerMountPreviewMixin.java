package me.napim.gomod.mixins;

import me.napim.gomod.gui.MountPreviewHelper;
import me.napim.gomod.features.RenderProfiler;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Transparent-mounts GUI preview riders are client-only {@code EntityOtherPlayerMP} copies with no
 * model-part flags; vanilla {@link RenderPlayer#setModelVisibilities} hides all outer skin layers.
 */
@Mixin(RenderPlayer.class)
public class RenderPlayerMountPreviewMixin {

    @Inject(method = {"setModelVisibilities", "func_177137_d"}, at = @At("RETURN"), require = 0)
    private void gomod$mountPreviewShowOuterSkinLayers(AbstractClientPlayer clientPlayer, CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            if (!MountPreviewHelper.isGuiPreviewRider(clientPlayer)
                    || MountPreviewHelper.isMountedPreviewRider(clientPlayer)) {
                return;
            }
            if (MountPreviewHelper.isCapeEditorCapeOnlyPass()
                    && MountPreviewHelper.isCapeEditorPreviewEntity(clientPlayer)) {
                MountPreviewHelper.applyCapeEditorCapeOnlyModelVisibility((RenderPlayer) (Object) this);
                return;
            }
            MountPreviewHelper.forceSkinOverlayLayersVisible((RenderPlayer) (Object) this);
        } catch (Throwable ignored) {
        }
    }
}
