package me.napim.gomod.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiButton.class)
public interface GuiButtonInvoker {
    @Invoker("mouseDragged")
    void gomod$invokeMouseDragged(Minecraft mc, int mouseX, int mouseY);
}
