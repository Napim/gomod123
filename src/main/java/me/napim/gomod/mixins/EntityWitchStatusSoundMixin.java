package me.napim.gomod.mixins;

import me.napim.gomod.features.WitchAutoSound;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server hurt flash: {@code handleStatusUpdate(2)} — works when tick {@code hurtTime} never updates on MP.
 */
@Mixin(Entity.class)
public abstract class EntityWitchStatusSoundMixin {

    @Inject(method = "handleStatusUpdate", at = @At("HEAD"))
    private void gomod$witchHurtFromStatus(byte id, CallbackInfo ci) {
        try {
            if (id != 2) {
                return;
            }
            Entity self = (Entity) (Object) this;
            if (!(self instanceof EntityLivingBase)) {
                return;
            }
            if (!WitchAutoSound.entityIsWitchForHurt(self)) {
                return;
            }
            WitchAutoSound.onWitchHurtEntityStatus(Minecraft.getMinecraft(), (EntityLivingBase) self);
        } catch (Throwable ignored) {
        }
    }
}
