package me.napim.gomod.mixins;

import me.napim.gomod.features.BlitzVampireEffectActivation;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vampire Blitz star HUD: start the 30s timer when the player right-clicks the activator redstone, not on BLITZ chat.
 */
@Mixin(PlayerControllerMP.class)
public class BlitzVampireRedstoneUseMixin {

    @Inject(method = "processRightClick", at = @At("HEAD"))
    private void gomod$vampireProcessRightClickHead(EntityPlayerSP player, WorldClient world, ItemStack held,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            BlitzVampireEffectActivation.onProcessUseHead(held);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "processRightClick", at = @At("RETURN"))
    private void gomod$vampireProcessRightClickTail(EntityPlayerSP player, WorldClient world, ItemStack held,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            BlitzVampireEffectActivation.onProcessUseTail(player, cir.getReturnValue());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "processRightClickBlock", at = @At("HEAD"))
    private void gomod$vampireProcessRightClickBlockHead(EntityPlayerSP player, WorldClient worldIn, BlockPos pos,
            EnumFacing side, Vec3 hitVec, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            BlitzVampireEffectActivation.onProcessUseHead(stack);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "processRightClickBlock", at = @At("RETURN"))
    private void gomod$vampireProcessRightClickBlockTail(EntityPlayerSP player, WorldClient worldIn, BlockPos pos,
            EnumFacing side, Vec3 hitVec, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            BlitzVampireEffectActivation.onProcessUseTail(player, cir.getReturnValue());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "sendUseItem", at = @At("HEAD"))
    private void gomod$vampireSendUseItemHead(EntityPlayer player, World world, ItemStack held,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            BlitzVampireEffectActivation.onProcessUseHead(held);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "sendUseItem", at = @At("RETURN"))
    private void gomod$vampireSendUseItemTail(EntityPlayer player, World world, ItemStack held,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            BlitzVampireEffectActivation.onProcessUseTail(player, cir.getReturnValue());
        } catch (Throwable ignored) {
        }
    }
}
