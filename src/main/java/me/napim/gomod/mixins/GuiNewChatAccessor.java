package me.napim.gomod.mixins;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Accesses already-populated replay chat after the viewer seeks past a kill event. */
@Mixin(GuiNewChat.class)
public interface GuiNewChatAccessor {
    @Accessor("drawnChatLines")
    List<ChatLine> gomod$getDrawnChatLines();
}
