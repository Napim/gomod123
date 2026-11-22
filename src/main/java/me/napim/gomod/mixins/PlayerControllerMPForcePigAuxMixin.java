package me.napim.gomod.mixins;

import me.napim.gomod.features.ForcePigManager;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spawns force pigs when the controller receives real left-use actions (not LWJGL polling).
 */
@Mixin(PlayerControllerMP.class)
public abstract class PlayerControllerMPForcePigAuxMixin {

    @Shadow
    private float curBlockDamageMP;

    @Inject(method = {"onPlayerDamageBlock", "func_180512_c"}, at = @At("HEAD"), require = 0)
    private void gomod$forcePigFirstDigTick(BlockPos pos, EnumFacing side, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (curBlockDamageMP > 0.001f) {
                return;
            }
            ForcePigManager.onLeftClickWorldInteractionAttempt();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"attackEntity", "func_78764_a"}, at = @At("HEAD"), require = 0)
    private void gomod$forcePigAttackEntity(EntityPlayer playerIn, Entity targetEntity, CallbackInfo ci) {
        try {
            ForcePigManager.onLeftClickWorldInteractionAttempt();
        } catch (Throwable ignored) {
        }
    }
}
