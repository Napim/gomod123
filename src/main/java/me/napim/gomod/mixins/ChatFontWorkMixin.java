package me.napim.gomod.mixins;

import me.napim.gomod.features.KillCounter;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat painting and line wrapping share FontRenderer with Lunar's TAB overlay. */
@Mixin(GuiNewChat.class)
public abstract class ChatFontWorkMixin {
    @Inject(method = {"drawChat", "setChatLine"}, at = @At("HEAD"), require = 1)
    private void gomod$beginChatFontScope(CallbackInfo ci) {
        KillCounter.beginChatFontWork();
    }

    @Inject(method = {"drawChat", "setChatLine"}, at = @At("RETURN"), require = 1)
    private void gomod$endChatFontScope(CallbackInfo ci) {
        KillCounter.endChatFontWork();
    }
}
