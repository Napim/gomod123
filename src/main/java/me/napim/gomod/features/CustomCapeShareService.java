package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import java.awt.image.BufferedImage;

/**
 * Shares a custom cape PNG to a gomod friend via the HTTP relay.
 */
public final class CustomCapeShareService {

    private static final long MAX_BYTES = 512 * 1024L;
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private CustomCapeShareService() {
    }

    public static boolean isSharingEnabled() {
        return true;
    }

    public static void shareDesignToFriend(final String targetPlayerRaw, final BufferedImage design,
                                           final String capeLabel) {
        final String targetPlayer = targetPlayerRaw == null ? "" : targetPlayerRaw.trim();
        if (targetPlayer.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Enter a gomod friend name first.");
            return;
        }
        if (!ConfigManager.isGomodFriend(targetPlayer)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "You can only share cape designs with gomod friends. Add first: "
                    + EnumChatFormatting.YELLOW + "/gfriend add " + targetPlayer);
            return;
        }
        if (design == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No cape design to share.");
            return;
        }
        if (Minecraft.getMinecraft().thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first.");
            return;
        }
        final String label = GomodCapeShareHistory.cleanLabel(capeLabel == null || capeLabel.trim().isEmpty()
                ? "Custom cape" : capeLabel.trim());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] bytes = PlayerCustomCapes.encodePng(design);
                    if (bytes.length == 0) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to encode cape image.");
                        return;
                    }
                    if (bytes.length > MAX_BYTES) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Cape image is too large.");
                        return;
                    }
                    String sender = Minecraft.getMinecraft().thePlayer.getName();
                    String targetDisplay = ConfigManager.getGomodFriendDisplayName(targetPlayer);
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "cape_design_send");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("sender", sender);
                    payload.addProperty("target", targetDisplay);
                    GomodSocialRelayKeys.attachDmTarget(payload, targetDisplay);
                    payload.addProperty("capeLabel", label);
                    payload.addProperty("mimeType", "image/png");
                    payload.addProperty("imageBase64", java.util.Base64.getEncoder().encodeToString(bytes));
                    payload.addProperty("ts", System.currentTimeMillis());
                    ScreenshotShareService.PostResult result = ScreenshotShareService.postRelayPayload(payload);
                    if (result.ok) {
                        GomodCapeShareHistory.recordSent(targetDisplay, label,
                                java.util.Base64.getEncoder().encodeToString(bytes));
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                                + "Shared cape design with " + EnumChatFormatting.AQUA + targetDisplay
                                + EnumChatFormatting.GREEN + ".");
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Share failed (" + result.describe() + ").");
                    }
                } catch (Exception e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Share failed: " + (e.getMessage() == null ? "error" : e.getMessage()));
                }
            }
        }, "GomodCapeDesignShare").start();
    }
}
