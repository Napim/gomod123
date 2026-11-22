package me.napim.gomod.mixins;

import me.napim.gomod.features.ArmorDisguise;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Substitute only the stack consumed by the native armor layer, never inventory/equipment state. */
@Mixin(LayerArmorBase.class)
public abstract class ArmorDisguiseLayerMixin {
    @Inject(method = "getCurrentArmor", at = @At("RETURN"), cancellable = true)
    private void gomod$displayArmor(EntityLivingBase entity, int slot, CallbackInfoReturnable<ItemStack> cir) {
        cir.setReturnValue(ArmorDisguise.renderArmor(entity, slot - 1, cir.getReturnValue()));
    }
}
