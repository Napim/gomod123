package me.napim.gomod.mixins;

import me.napim.gomod.command.TeamSpamCommand;
import me.napim.gomod.features.DcMirrorBridge;
import me.napim.gomod.features.ScreenshotChatPrompt;
import me.napim.gomod.util.CommandFallbackDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Many screenshot mods (and vanilla) deliver feedback via {@link EntityPlayerSP#addChatMessage}, which never
 * becomes {@code S02PacketChat}. Catches those lines so the gomod share row can appear.
 */
@Mixin(EntityPlayerSP.class)
public class EntityPlayerSPMixin {

    /**
     * Forge/Mixin path for plain chat → Discord mirror when {@link me.napim.gomod.features.CommandPacketInterceptor}
     * does not run (same logic as outgoing chat packet hook).
     */
    @Inject(method = "sendChatMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$dcMirrorInterceptOutgoingChat(String message, CallbackInfo ci) {
        try {
            me.napim.gomod.features.PlayerQueuer.onOutgoingChatCommand(message);
            me.napim.gomod.features.AutoQueue.onOutgoingChatCommand(message);
            me.napim.gomod.features.BlitzPregamePlayerToggle.onOutgoingChatCommand(message);
        } catch (Throwable ignored) {
        }
        try {
            TeamSpamCommand.onOutgoingSendChatMessageHead(message);
        } catch (Throwable ignored) {
        }
        try {
            if (message != null && message.length() > 1 && message.charAt(0) == '/') {
                String cmd = message.trim();
                if (!cmd.isEmpty() && CommandFallbackDispatcher.dispatchOutgoingChat(cmd)) {
                    ci.cancel();
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (message == null) {
                return;
            }
            String trimmed = message.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("/")) {
                return;
            }
            if (!DcMirrorBridge.shouldRedirectPlainChatOffServer()) {
                return;
            }
            EntityPlayerSP self = (EntityPlayerSP) (Object) this;
            if (self.worldObj == null || !self.worldObj.isRemote) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer != self) {
                return;
            }
            DcMirrorBridge.onOutgoingChat(message);
            if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().addToSentMessages(message);
            }
            DcMirrorBridge.echoRedirectedLine(trimmed);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "addChatMessage", at = @At("RETURN"), require = 0)
    private void gomod$afterAddChatMessage(IChatComponent chatComponent, CallbackInfo ci) {
        try {
            EntityPlayerSP self = (EntityPlayerSP) (Object) this;
            if (self.worldObj == null || !self.worldObj.isRemote) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer != self) {
                return;
            }
            if (chatComponent == null) {
                return;
            }
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
