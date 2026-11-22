package me.napim.gomod.mixins;

import me.napim.gomod.features.ReplayPlayerSelectorDisplay;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Supplies a display-only target-profile copy before a selector head enters the inventory item renderer. */
@Mixin(RenderItem.class)
public abstract class PlayerSelectorSkullGuiRenderMixin {
    @ModifyVariable(
            method = {"renderItemIntoGUI(Lnet/minecraft/item/ItemStack;II)V", "func_175042_a"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private ItemStack gomod$renderDisguisedSelectorGuiSkull(ItemStack stack) {
        return ReplayPlayerSelectorDisplay.copyStackForSelectorRender(stack);
    }
}
