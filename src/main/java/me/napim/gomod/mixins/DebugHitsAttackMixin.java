package me.napim.gomod.mixins;

import me.napim.gomod.features.DebugHits;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks melee swings for {@link DebugHits}; chat is sent only when target health drops.
 */
@Mixin(PlayerControllerMP.class)
public class DebugHitsAttackMixin {

    @Inject(method = {"attackEntity", "func_78764_a"}, at = @At("RETURN"), require = 0)
    private void gomod$debugHitsAfterAttack(EntityPlayer playerIn, Entity targetEntity, CallbackInfo ci) {
        DebugHits.onMeleeAttack(playerIn, targetEntity);
    }
}
