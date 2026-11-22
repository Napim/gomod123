package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.GomodPeerDisguiseDisplay;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Locale;

/** Applies the local disguise to chat at Lunar/Weave's final GUI-display boundary. */
@Mixin(GuiNewChat.class)
public abstract class WeaveLocalDisguiseChatDisplayMixin {

    @ModifyVariable(method = "printChatMessage", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private IChatComponent gomod$rewriteDisguiseChatForDisplay(IChatComponent message) {
        return gomod$rewriteAndReport(message, "printChatMessage");
    }

    @ModifyVariable(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private IChatComponent gomod$rewriteDisguiseChatForDisplayBeforeDeletion(IChatComponent message) {
        return gomod$rewriteAndReport(message, "printChatMessageWithOptionalDeletion");
    }

    private static IChatComponent gomod$rewriteAndReport(IChatComponent message, String boundary) {
        IChatComponent rewritten = LocalDisguise.rewriteIncomingChatComponentForDisplay(message);
        rewritten = GomodPeerDisguiseDisplay.rewriteIncomingChat(rewritten);
        try {
            if (message != null && LocalDisguise.fastEnabledFlag) {
                String realName = LocalDisguise.fastRealNameText;
                String plain = message.getUnformattedText();
                boolean realNameCandidate = realName != null && !realName.isEmpty()
                        && plain != null
                        && plain.toLowerCase(Locale.ROOT).contains(realName.toLowerCase(Locale.ROOT));
                if (realNameCandidate) {
                    String targetName = LocalDisguise.fastTargetNameText;
                    String trimmed = plain.trim();
                    boolean angleBracketSpeaker = trimmed.startsWith("<")
                            && trimmed.indexOf('>') > 1
                            && trimmed.indexOf('>') <= 18;
                    LunarConnectionDiagnostics.recordOnce("disguise.local_chat_display", "self-name-candidate",
                            "boundary=" + boundary
                                    + " selfNameCandidate=true textRewriteEnabled="
                                    + LocalDisguise.fastRenderTextRewriteFlag
                                    + " realNameStandalone="
                                    + containsStandaloneUsername(plain, realName)
                                    + " disguiseTargetConfigured="
                                    + (targetName != null && !targetName.trim().isEmpty())
                                    + " targetSameAsReal=" + (targetName != null && realName != null
                                    && targetName.equalsIgnoreCase(realName))
                                    + " angleBracketSpeakerShape=" + angleBracketSpeaker
                                    + " changed=" + (rewritten != message));
                }
            }
        } catch (Throwable ignored) {
            // The diagnostic must not interfere with displaying chat.
        }
        return rewritten;
    }

    private static boolean containsStandaloneUsername(String text, String username) {
        if (text == null || username == null || username.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerUsername = username.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from <= lowerText.length() - lowerUsername.length()) {
            int at = lowerText.indexOf(lowerUsername, from);
            if (at < 0) {
                return false;
            }
            int end = at + lowerUsername.length();
            boolean leftBoundary = at == 0 || !isUsernameCharacter(lowerText.charAt(at - 1));
            boolean rightBoundary = end == lowerText.length()
                    || !isUsernameCharacter(lowerText.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean isUsernameCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }
}
