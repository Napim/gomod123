package me.napim.gomod.mixins;

import me.napim.gomod.features.MountHeadDim;
import net.minecraft.client.renderer.entity.RenderHorse;
import net.minecraft.entity.passive.EntityHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the ridden horse for one render frame so {@link me.napim.gomod.mixins.RendererLivingEntityMixin}
 * can apply alpha where vanilla sets color (before {@code renderModel} / {@code renderLayers}).
 */
@Mixin(RenderHorse.class)
public abstract class RenderHorseMixin {

    @Inject(method = "doRender", at = @At("HEAD"))
    private void gomod$mountDimMarkFrame(EntityHorse entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        try {
            if (MountHeadDim.usesRenderHorseActiveMarker() && MountHeadDim.shouldDimThisMount(entity)) {
                MountHeadDim.setActiveRideForFrame(entity);
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "doRender", at = @At("RETURN"))
    private void gomod$mountDimClearFrame(EntityHorse entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        try {
            if (MountHeadDim.usesRenderHorseActiveMarker()) {
                MountHeadDim.clearActiveRideForFrame();
            }
        } catch (Throwable ignored) {
        }
    }
}
