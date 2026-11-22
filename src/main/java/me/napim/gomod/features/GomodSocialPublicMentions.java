package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;

/**
 * Parses and formats {@code @player} mentions in gomod Social public chat.
 */
public final class GomodSocialPublicMentions {

    private static final int MAX_NAME_LEN = 16;

    private GomodSocialPublicMentions() {}

    /** Discord-style blue {@code @name} highlights for bubble text. */
    public static String formatDisplayText(String body) {
        if (body == null || body.isEmpty()) {
            return body == null ? "" : body;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String self = mc != null && mc.thePlayer != null ? mc.thePlayer.getName() : null;
        return colorizeMentions(body, self);
    }

    /** Draws public-chat text with a subtle black outline around recognized {@code @name} spans only. */
    public static void drawDisplayText(FontRenderer fr, String body, int x, int y, int textColor) {
        if (fr == null) {
            return;
        }
        String safeBody = body == null ? "" : body;
        Minecraft mc = Minecraft.getMinecraft();
        String selfName = mc != null && mc.thePlayer != null ? mc.thePlayer.getName() : null;
        drawMentionOutlines(fr, safeBody, selfName, x, y);
        fr.drawString(colorizeMentions(safeBody, selfName), x, y, textColor);
    }

    public static boolean mentionsPlayer(String body, String playerName) {
        if (body == null || body.isEmpty() || playerName == null || playerName.trim().isEmpty()) {
            return false;
        }
        String target = playerName.trim();
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) == '@' && (i == 0 || Character.isWhitespace(body.charAt(i - 1)))) {
                int end = scanMentionEnd(body, i + 1);
                if (end > i + 1) {
                    String name = body.substring(i + 1, end);
                    if (name.equalsIgnoreCase(target) && isTaggableMention(name, target)) {
                        return true;
                    }
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return false;
    }

    private static String colorizeMentions(String body, String selfName) {
        StringBuilder out = new StringBuilder(body.length() + 16);
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) == '@' && (i == 0 || Character.isWhitespace(body.charAt(i - 1)))) {
                int end = scanMentionEnd(body, i + 1);
                if (end > i + 1) {
                    String name = body.substring(i + 1, end);
                    boolean self = selfName != null && name.equalsIgnoreCase(selfName);
                    if (!isTaggableMention(name, selfName)) {
                        out.append('@').append(name);
                    } else if (self) {
                        out.append(EnumChatFormatting.AQUA).append(EnumChatFormatting.BOLD);
                        out.append('@').append(name).append(EnumChatFormatting.RESET);
                    } else {
                        out.append(EnumChatFormatting.BLUE);
                        out.append('@').append(name).append(EnumChatFormatting.RESET);
                    }
                    i = end;
                    continue;
                }
            }
            out.append(body.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static void drawMentionOutlines(FontRenderer fr, String body, String selfName, int x, int y) {
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) == '@' && (i == 0 || Character.isWhitespace(body.charAt(i - 1)))) {
                int end = scanMentionEnd(body, i + 1);
                if (end > i + 1) {
                    String name = body.substring(i + 1, end);
                    if (isTaggableMention(name, selfName)) {
                        int mentionX = x + fr.getStringWidth(colorizeMentions(body.substring(0, i), selfName));
                        boolean self = selfName != null && name.equalsIgnoreCase(selfName);
                        String strokeText = (self ? EnumChatFormatting.BOLD.toString() : "") + "@" + name;
                        int strokeColor = 0xC0000000;
                        fr.drawString(strokeText, mentionX - 1, y, strokeColor);
                        fr.drawString(strokeText, mentionX + 1, y, strokeColor);
                        fr.drawString(strokeText, mentionX, y - 1, strokeColor);
                        fr.drawString(strokeText, mentionX, y + 1, strokeColor);
                    }
                    i = end;
                    continue;
                }
            }
            i++;
        }
    }

    private static int scanMentionEnd(String body, int start) {
        int end = start;
        while (end < body.length() && isNameChar(body.charAt(end)) && end - start < MAX_NAME_LEN) {
            end++;
        }
        return end;
    }

    private static boolean isTaggableMention(String name, String selfName) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String n = name.trim();
        if (selfName != null && n.equalsIgnoreCase(selfName)) {
            return true;
        }
        if (ConfigManager.isGomodFriend(n)) {
            return true;
        }
        List<String> friends = ConfigManager.getGomodFriends();
        if (friends != null) {
            for (String friend : friends) {
                if (friend != null && n.equalsIgnoreCase(friend.trim())) {
                    return true;
                }
                String display = ConfigManager.getGomodFriendDisplayName(friend);
                if (display != null && n.equalsIgnoreCase(display.trim())) {
                    return true;
                }
            }
        }
        for (GomodSocialChat.Message m : GomodSocialPublicChat.getMessages()) {
            if (m != null && m.from != null && n.equalsIgnoreCase(m.from.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
