package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalMobDisguise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the local player model when "Mob look" is active; a client-spawned mob entity is drawn by the normal entity pipeline instead.
 */
@Mixin(RenderPlayer.class)
public class LocalMobDisguiseRenderMixin {

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$hideLocalPlayerForMobLook(
            AbstractClientPlayer entity,
            double x,
            double y,
            double z,
            float entityYaw,
            float partialTicks,
            CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || entity == null || entity != mc.thePlayer) {
                return;
            }
            if (!LocalMobDisguise.shouldHideLocalPlayerModel()) {
                return;
            }
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
