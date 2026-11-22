package me.napim.gomod.mixins;

import me.napim.gomod.features.MountHeadDim;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Patcher's "Ridden Horse Opacity" feature also wraps the ridden horse render
 * and mutates blend/alpha state in Forge RenderLivingEvent pre/post callbacks.
 * When gomod Transparent Mounts owns the render, cancel those callbacks so only
 * one mod controls the mount opacity pipeline.
 */
@Pseudo
@Mixin(targets = "club.sk1er.patcher.util.world.render.entity.EntityRendering", remap = false)
public abstract class PatcherEntityRenderingCompatMixin {
    @Shadow
    private boolean shouldMakeTransparent;

    @Inject(
            method = "setHorseTransparentPre(Lnet/minecraftforge/client/event/RenderLivingEvent$Pre;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void gomod$cancelPatcherRiddenHorseOpacityPre(RenderLivingEvent.Pre event, CallbackInfo ci) {
        if (MountHeadDim.shouldSuppressPatcherRiddenHorseOpacity()) {
            this.shouldMakeTransparent = false;
            ci.cancel();
        }
    }

    @Inject(
            method = "setHorseTransparentPre(Lnet/minecraftforge/client/event/RenderLivingEvent$Post;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void gomod$cancelPatcherRiddenHorseOpacityPost(RenderLivingEvent.Post event, CallbackInfo ci) {
        if (MountHeadDim.shouldSuppressPatcherRiddenHorseOpacity()) {
            this.shouldMakeTransparent = false;
            ci.cancel();
        }
    }
}
