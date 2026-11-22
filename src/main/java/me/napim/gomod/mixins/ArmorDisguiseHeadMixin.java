package me.napim.gomod.mixins;

import me.napim.gomod.features.ArmorDisguise;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Hide actual skulls/pumpkins when the helmet slot is visually replaced or empty. */
@Mixin(LayerCustomHead.class)
public abstract class ArmorDisguiseHeadMixin {
    @Redirect(method = "doRenderLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;getCurrentArmor(I)Lnet/minecraft/item/ItemStack;"))
    private ItemStack gomod$displayHead(EntityLivingBase entity, int slot) {
        return ArmorDisguise.renderArmor(entity, slot, entity.getCurrentArmor(slot));
    }
}
