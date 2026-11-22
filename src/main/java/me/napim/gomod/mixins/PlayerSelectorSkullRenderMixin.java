package me.napim.gomod.mixins;

import me.napim.gomod.features.ReplayPlayerSelectorDisplay;
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Renders disguise-target skins on selector skulls without mutating the server-owned inventory stack. */
@Mixin(TileEntityItemStackRenderer.class)
public abstract class PlayerSelectorSkullRenderMixin {
    @ModifyVariable(
            method = {"renderByItem", "func_179022_a"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private ItemStack gomod$renderDisguisedSelectorSkull(ItemStack stack) {
        return ReplayPlayerSelectorDisplay.copyStackForSelectorRender(stack);
    }
}
