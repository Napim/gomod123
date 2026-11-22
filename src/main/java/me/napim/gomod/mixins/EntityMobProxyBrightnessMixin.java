package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalMobDisguise;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link Entity#getBrightnessForRender} lives on {@link Entity}, not {@link EntityLivingBase}.
 * Mob disguise proxy entities need the local player's light sampling so the model is not full-bright.
 */
@Mixin(Entity.class)
public abstract class EntityMobProxyBrightnessMixin {

    /** Explicit (F)I descriptor so the AP resolves Entity's method (avoids silent failure → full-bright proxy). */
    @Inject(method = "getBrightnessForRender(F)I", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$mobProxyBrightness(float partialTicks, CallbackInfoReturnable<Integer> cir) {
        try {
            if (!((Object) this instanceof EntityLivingBase)) {
                return;
            }
            EntityLivingBase self = (EntityLivingBase) (Object) this;
            EntityLivingBase proxy = LocalMobDisguise.getProxyEntity();
            if (proxy == null || self != proxy) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                cir.setReturnValue(mc.thePlayer.getBrightnessForRender(partialTicks));
            }
        } catch (Throwable ignored) {
        }
    }
}
