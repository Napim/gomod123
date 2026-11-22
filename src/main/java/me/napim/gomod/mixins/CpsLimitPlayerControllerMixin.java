package me.napim.gomod.mixins;

import me.napim.gomod.features.CpsLimiter;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caps combat attack rate by cancelling extra attackEntity calls.
 */
@Mixin(PlayerControllerMP.class)
public class CpsLimitPlayerControllerMixin {

    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void gomod$cpsLimitAttack(EntityPlayer playerIn, Entity targetEntity, CallbackInfo ci) {
        if (RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled()) {
            ci.cancel();
            return;
        }
        if (!CpsLimiter.tryConsumeAttack()) {
            ci.cancel();
        }
    }
}
