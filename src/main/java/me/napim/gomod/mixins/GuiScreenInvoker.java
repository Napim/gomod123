package me.napim.gomod.mixins;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiScreen.class)
public interface GuiScreenInvoker {
    @Invoker("renderToolTip")
    void gomod$invokeRenderToolTip(ItemStack stack, int x, int y);
}
