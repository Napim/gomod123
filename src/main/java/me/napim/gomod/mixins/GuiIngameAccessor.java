package me.napim.gomod.mixins;

import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiIngame.class)
public interface GuiIngameAccessor {

    @Accessor("overlayPlayerList")
    GuiPlayerTabOverlay gomod$getOverlayPlayerList();
}
