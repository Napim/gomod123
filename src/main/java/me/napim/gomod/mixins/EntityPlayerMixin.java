package me.napim.gomod.mixins;

import me.napim.gomod.features.BlitzVampireEffectActivation;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityPlayer.class)
public abstract class EntityPlayerMixin {

    @Inject(method = "dropOneItem", at = @At("HEAD"))
    private void gomod$vampireDropHeldRedstoneGuard(boolean dropAll, CallbackInfoReturnable<?> cir) {
        try {
            EntityPlayer self = (EntityPlayer) (Object) this;
            if (self.worldObj == null || !self.worldObj.isRemote) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer != self) {
                return;
            }
            ItemStack held = self.getHeldItem();
            if (held != null && held.getItem() == Items.redstone) {
                BlitzVampireEffectActivation.onLocalPlayerDroppedHeldRedstone();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void gomod$localDisguiseDisplayName(CallbackInfoReturnable<IChatComponent> cir) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || (Object) this != mc.thePlayer) {
                return;
            }
            if (LocalDisguise.shouldApplyTabDisplayOverride()) {
                IChatComponent originalComponent = cir.getReturnValue();
                String original = originalComponent != null ? originalComponent.getFormattedText() : "";
                String rewritten = LocalDisguise.buildTabDisplayName(original, mc.thePlayer.getName());
                cir.setReturnValue(new ChatComponentText(rewritten));
            }
        } catch (Throwable ignored) {
        }
    }
}
