package me.napim.gomod.mixins;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Gui.class)
public interface GuiInvoker {
    @Invoker("drawGradientRect")
    void gomod$invokeDrawGradientRect(int left, int top, int right, int bottom, int startColor, int endColor);
}
