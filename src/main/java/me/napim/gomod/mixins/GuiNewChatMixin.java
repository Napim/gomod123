package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.GomodPeerDisguiseDisplay;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.ScreenshotChatPrompt;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Patcher (and similar mods) post screenshot feedback with {@link GuiNewChat#printChatMessage} directly, so it
 * never appears as {@code S02PacketChat} and never hits {@link net.weavemc.loader.api.event.ChatReceivedEvent}.
 * Mirror that path here so the gomod share row can appear after their lines too.
 */
@Mixin(GuiNewChat.class)
public class GuiNewChatMixin {

    @Inject(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), require = 0)
    private void gomod$beginChatFontWork(IChatComponent chatComponent, int id, CallbackInfo ci) {
        KillCounter.beginChatFontWork();
    }

    @ModifyVariable(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private IChatComponent gomod$rewriteLocalDisguiseChatAtSource(IChatComponent chatComponent) {
        return GomodPeerDisguiseDisplay.rewriteIncomingChat(
                LocalDisguise.rewriteIncomingChatComponentForDisplay(chatComponent));
    }

    /** Vanilla's packet handler calls {@code printChatMessage}; cover that entry point before it delegates. */
    @ModifyVariable(method = "printChatMessage", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private IChatComponent gomod$rewriteLocalDisguiseChatBeforeDelegation(IChatComponent chatComponent) {
        return GomodPeerDisguiseDisplay.rewriteIncomingChat(
                LocalDisguise.rewriteIncomingChatComponentForDisplay(chatComponent));
    }

    @Inject(method = "printChatMessage", at = @At("RETURN"), require = 0)
    private void gomod$afterPrintChatMessage(IChatComponent chatComponent, CallbackInfo ci) {
        gomod$maybePromptShare(chatComponent);
    }

    /** Patcher posts screenshot action buttons with {@code printChatMessageWithOptionalDeletion}. */
    @Inject(method = "printChatMessageWithOptionalDeletion", at = @At("RETURN"), require = 0)
    private void gomod$afterPrintOptional(IChatComponent chatComponent, int id, CallbackInfo ci) {
        try {
            gomod$maybePromptShare(chatComponent);
        } finally {
            KillCounter.endChatFontWork();
        }
    }

    private static void gomod$maybePromptShare(IChatComponent chatComponent) {
        try {
            if (chatComponent == null) {
                return;
            }
            KillCounter.observeDisplayedReplayChat(chatComponent);
            String raw = ScreenshotChatPrompt.chatToRawText(chatComponent);
            if ((raw.contains("Send to gomod friend") && raw.contains("Friends list"))
                    || (raw.contains("SEND TO FRIEND") && raw.contains("FRIENDS LIST") && raw.contains("EDIT"))) {
                return;
            }
            if (!ScreenshotChatPrompt.looksLikeScreenshotFeedback(raw)) {
                return;
            }
            ScreenshotChatPrompt.onScreenshotFeedbackPrinted(raw);
        } catch (Throwable ignored) {
        }
    }
}
