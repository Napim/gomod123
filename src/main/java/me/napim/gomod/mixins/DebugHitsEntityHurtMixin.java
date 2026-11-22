package me.napim.gomod.mixins;

import me.napim.gomod.features.DebugHits;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hurt flash ({@code handleStatusUpdate(2)}) — reliable on MP when {@code hurtTime} does not tick client-side.
 */
@Mixin(Entity.class)
public abstract class DebugHitsEntityHurtMixin {

    @Inject(method = "handleStatusUpdate", at = @At("HEAD"))
    private void gomod$debugHitsHurtStatus(byte id, CallbackInfo ci) {
        try {
            if (id != 2) {
                return;
            }
            Entity self = (Entity) (Object) this;
            if (self instanceof EntityLivingBase) {
                DebugHits.onEntityHurtStatus((EntityLivingBase) self);
            }
        } catch (Throwable ignored) {
        }
    }
}
