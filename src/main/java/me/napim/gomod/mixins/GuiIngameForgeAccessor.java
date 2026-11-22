package me.napim.gomod.mixins;

import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invokes Forge crosshair rendering so HUD editor guides use the same crosshair as in-game
 * (including third-party crosshair mods that mixin {@code renderCrosshairs}).
 */
@Mixin(GuiIngameForge.class)
public interface GuiIngameForgeAccessor {

    @Invoker(value = "renderCrosshairs", remap = false)
    void gomod$invokeRenderCrosshairs(int scaledWidth, int scaledHeight);
}
