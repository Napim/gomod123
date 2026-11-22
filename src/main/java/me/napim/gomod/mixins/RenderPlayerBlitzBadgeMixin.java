package me.napim.gomod.mixins;

import me.napim.gomod.render.LayerBlitzChallengeBadge;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPlayer.class)
public class RenderPlayerBlitzBadgeMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/entity/RenderManager;Z)V", at = @At("RETURN"))
    private void gomod$registerBlitzBadgeLayer(CallbackInfo ci) {
        try {
            ((RenderPlayer) (Object) this).addLayer(new LayerBlitzChallengeBadge((RenderPlayer) (Object) this));
        } catch (Throwable ignored) {
        }
    }
}
