package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

/**
 * Shared Hypixel API-key validation state and user-facing key warnings.
 * Actual API callers validate the key through requests they already need; this utility deliberately does not
 * issue a separate {@code /counts} request on every server transfer.
 */
public final class GameModeStatusFetcher {
    private static final String HYPX_DEV_URL = "https://developer.hypixel.net/";

    private GameModeStatusFetcher() {
    }

    public static void sendHypixelKeyExpiredMessage() {
        net.minecraft.util.IChatComponent full = new net.minecraft.util.ChatComponentText(me.napim.gomod.Main.CHAT_PREFIX)
                .appendSibling(coloredText(EnumChatFormatting.RED, "Hypixel API key appears to have expired. "))
                .appendSibling(linkText("[Regenerate an API key]", HYPX_DEV_URL));
        sendChatComponent(full);
    }

    public static boolean shouldSkipKnownBadHypixelRequests(String apiKey) {
        return apiKey != null && ApiKeyManager.isHypixelKeyKnownRejected(apiKey.trim());
    }

    public static void noteHypixelApiSuccess(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return;
        }
        ApiKeyManager.rememberHypixelKeyValidated(apiKey.trim());
    }

    public static boolean handleHypixelInvalidKeyFailure(String apiKey, String detail, boolean notifyUser) {
        String trimmedKey = apiKey == null ? null : apiKey.trim();
        if (trimmedKey == null || trimmedKey.isEmpty()) {
            return false;
        }
        if (!HypixelApiHelper.isLikelyInvalidKeyMessage(detail)) {
            return false;
        }
        boolean alreadyKnown = ApiKeyManager.isHypixelKeyKnownRejected(trimmedKey);
        ApiKeyManager.noteHypixelKeyRejected(trimmedKey);
        if (notifyUser && !alreadyKnown) {
            sendKnownHypixelKeyProblem(trimmedKey);
        }
        return true;
    }

    public static void sendKnownHypixelKeyProblem(String apiKey) {
        String trimmedKey = apiKey == null ? null : apiKey.trim();
        if (trimmedKey != null && ApiKeyManager.wasHypixelKeyPreviouslyValidated(trimmedKey)) {
            sendHypixelKeyExpiredMessage();
            return;
        }
        sendInvalidHypixelApiKeyMessage();
    }

    public static void sendInvalidHypixelApiKeyMessage() {
        net.minecraft.util.IChatComponent full = new net.minecraft.util.ChatComponentText(me.napim.gomod.Main.CHAT_PREFIX)
                .appendSibling(coloredText(EnumChatFormatting.RED, "API error: HTTP 403 (Invalid API key) "))
                .appendSibling(linkText("[Generate a key]", HYPX_DEV_URL));
        sendChatComponent(full);
    }

    private static net.minecraft.util.ChatComponentText coloredText(EnumChatFormatting color, String text) {
        net.minecraft.util.ChatComponentText component = new net.minecraft.util.ChatComponentText(text);
        component.getChatStyle().setColor(color);
        return component;
    }

    private static net.minecraft.util.ChatComponentText linkText(String text, String url) {
        net.minecraft.util.ChatComponentText component = new net.minecraft.util.ChatComponentText(EnumChatFormatting.GOLD
                + "" + EnumChatFormatting.BOLD + text);
        component.getChatStyle().setChatClickEvent(
                new net.minecraft.event.ClickEvent(net.minecraft.event.ClickEvent.Action.OPEN_URL, url));
        component.getChatStyle().setChatHoverEvent(
                new net.minecraft.event.HoverEvent(net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
                        new net.minecraft.util.ChatComponentText(EnumChatFormatting.YELLOW + url)));
        return component;
    }

    private static void sendChatComponent(net.minecraft.util.IChatComponent component) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(component);
                return;
            }
        } catch (Throwable ignored) {
        }
        me.napim.gomod.Main.sendMessage(component.getUnformattedText());
    }
}
