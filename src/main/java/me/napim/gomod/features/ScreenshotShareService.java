package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Sends the latest local screenshot to a specific gomod user via gomod relay.
 *
 * Protocol contract (relay-side):
 * - POST ConfigManager.getGlobalChatEndpoint()
 * - JSON payload with type = "screenshot_send"
 */
public final class ScreenshotShareService {
    private static final long MAX_BYTES = 6L * 1024L * 1024L; // 6 MB
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private ScreenshotShareService() {}

    public static void shareLatestToPlayer(final String targetPlayerRaw) {
        final String targetPlayer = targetPlayerRaw == null ? "" : targetPlayerRaw.trim();
        if (targetPlayer.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Enter a target player name first.");
            return;
        }
        if (!ConfigManager.isGomodFriend(targetPlayer)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "You can only send screenshots to gomod friends. Add first: "
                    + EnumChatFormatting.YELLOW + "/gfriend add " + targetPlayer);
            return;
        }
        if (Minecraft.getMinecraft().thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first.");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File latest = findLatestScreenshot();
                    if (latest == null) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No screenshot found in your screenshots folder.");
                        return;
                    }
                    if (latest.length() <= 0L) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Latest screenshot file is empty.");
                        return;
                    }
                    if (latest.length() > MAX_BYTES) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot is too large (>6MB).");
                        return;
                    }
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                            + "Sending latest screenshot: " + EnumChatFormatting.AQUA + latest.getName());

                    byte[] bytes = Files.readAllBytes(latest.toPath());
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    String sender = Minecraft.getMinecraft().thePlayer.getName();

                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "screenshot_send");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("sender", sender);
                    String targetDisplay = ConfigManager.getGomodFriendDisplayName(targetPlayer);
                    payload.addProperty("target", targetDisplay);
                    GomodSocialRelayKeys.attachDmTarget(payload, targetDisplay);
                    payload.addProperty("fileName", latest.getName());
                    payload.addProperty("mimeType", guessMimeType(latest.getName()));
                    payload.addProperty("imageBase64", b64);
                    payload.addProperty("ts", System.currentTimeMillis());

                    PostResult result = post(ConfigManager.getGlobalChatEndpoint(), payload);
                    if (result.ok) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                                + "Shared screenshot with " + EnumChatFormatting.AQUA + targetDisplay
                                + EnumChatFormatting.GREEN + " (" + latest.getName() + ").");
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Share failed (" + result.describe() + "). Check your gomod relay screenshot endpoint.");
                    }
                } catch (Exception e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Share failed: " + safeMsg(e));
                }
            }
        }, "GomodScreenshotShare").start();
    }

    /**
     * Sends already-encoded image bytes to a gomod friend (used by the screenshot editor).
     */
    public static void shareBytesToPlayer(final String targetPlayerRaw, final byte[] imageBytes, final String suggestedName) {
        final String targetPlayer = targetPlayerRaw == null ? "" : targetPlayerRaw.trim();
        if (targetPlayer.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Enter a target player name first.");
            return;
        }
        if (!ConfigManager.isGomodFriend(targetPlayer)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "You can only send screenshots to gomod friends. Add first: "
                    + EnumChatFormatting.YELLOW + "/gfriend add " + targetPlayer);
            return;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No image data to send.");
            return;
        }
        if (imageBytes.length > MAX_BYTES) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Image is too large (>6MB).");
            return;
        }
        if (Minecraft.getMinecraft().thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first.");
            return;
        }
        final String fileName = (suggestedName == null || suggestedName.isEmpty()) ? "edited_screenshot.png" : suggestedName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String b64 = Base64.getEncoder().encodeToString(imageBytes);
                    String sender = Minecraft.getMinecraft().thePlayer.getName();
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "screenshot_send");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("sender", sender);
                    String targetDisplay = ConfigManager.getGomodFriendDisplayName(targetPlayer);
                    payload.addProperty("target", targetDisplay);
                    GomodSocialRelayKeys.attachDmTarget(payload, targetDisplay);
                    payload.addProperty("fileName", fileName);
                    payload.addProperty("mimeType", "image/png");
                    payload.addProperty("imageBase64", b64);
                    payload.addProperty("ts", System.currentTimeMillis());
                    PostResult result = post(ConfigManager.getGlobalChatEndpoint(), payload);
                    if (result.ok) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                                + "Shared edited screenshot with " + EnumChatFormatting.AQUA + targetDisplay
                                + EnumChatFormatting.GREEN + ".");
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Share failed (" + result.describe() + ").");
                    }
                } catch (Exception e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Share failed: " + safeMsg(e));
                }
            }
        }, "GomodScreenshotShareBytes").start();
    }

    public static void shareFileToPlayer(final String targetPlayerRaw, final File imageFile) {
        final String targetPlayer = targetPlayerRaw == null ? "" : targetPlayerRaw.trim();
        if (targetPlayer.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Select a friend first.");
            return;
        }
        if (!ConfigManager.isGomodFriend(targetPlayer)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "You can only send screenshots to gomod friends.");
            return;
        }
        if (imageFile == null || !imageFile.isFile()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot file not found.");
            return;
        }
        if (Minecraft.getMinecraft().thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first.");
            return;
        }
        final String path = imageFile.getAbsolutePath();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File latest = new File(path);
                    if (!latest.isFile()) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot file not found.");
                        return;
                    }
                    if (latest.length() <= 0L) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot file is empty.");
                        return;
                    }
                    if (latest.length() > MAX_BYTES) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot is too large (>6MB).");
                        return;
                    }
                    byte[] bytes = Files.readAllBytes(latest.toPath());
                    String sender = Minecraft.getMinecraft().thePlayer.getName();
                    String targetDisplay = ConfigManager.getGomodFriendDisplayName(targetPlayer);
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "screenshot_send");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("sender", sender);
                    payload.addProperty("target", targetDisplay);
                    GomodSocialRelayKeys.attachDmTarget(payload, targetDisplay);
                    payload.addProperty("fileName", latest.getName());
                    payload.addProperty("mimeType", guessMimeType(latest.getName()));
                    payload.addProperty("imageBase64", b64);
                    payload.addProperty("ts", System.currentTimeMillis());
                    PostResult result = post(ConfigManager.getGlobalChatEndpoint(), payload);
                    if (result.ok) {
                        GomodSocialChat.onScreenshotSent(targetDisplay, latest.getName());
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                                + "Sent screenshot to " + EnumChatFormatting.AQUA + targetDisplay
                                + EnumChatFormatting.GREEN + ".");
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Screenshot send failed (" + result.describe() + ").");
                    }
                } catch (Exception e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot send failed: " + safeMsg(e));
                }
            }
        }, "GomodScreenshotShareFile").start();
    }

    private static File findLatestScreenshot() {
        return PlayerScreenshotFiles.findLatest(Minecraft.getMinecraft());
    }

    private static String guessMimeType(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "image/png";
    }

    public static PostResult postRelayPayload(JsonObject payload) throws Exception {
        return post(ConfigManager.getGlobalChatEndpoint(), payload);
    }

    public static PostResult postRelayPayload(JsonObject payload, java.util.UUID owner) throws Exception {
        return post(ConfigManager.getGlobalChatEndpoint(), payload, owner);
    }

    private static PostResult post(String urlStr, JsonObject payload) throws Exception {
        return post(urlStr, payload, null);
    }

    private static PostResult post(String urlStr, JsonObject payload, java.util.UUID owner) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(12000);
        if (owner != null && !owner.equals(LunarFitSharing.account())) throw new IllegalStateException("Account changed");
        GomodRelayAuth.authorize(conn, urlStr);
        if (owner != null && !owner.equals(LunarFitSharing.account())) throw new IllegalStateException("Account changed");

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        GomodRelayAuth.noteResponseCode(urlStr, code);
        String response;
        if (code >= 400) {
            response = readFully(conn.getErrorStream());
        } else {
            response = readFully(conn.getInputStream());
        }
        String responseTrim = response == null ? "" : response.trim();
        boolean relayOk = false;
        String error = "";
        try {
            JsonObject obj = GSON.fromJson(responseTrim, JsonObject.class);
            relayOk = obj != null && obj.has("ok") && obj.get("ok").getAsBoolean();
            if (obj != null && obj.has("error") && !obj.get("error").isJsonNull()) {
                error = obj.get("error").getAsString();
            }
        } catch (Throwable ignored) {
            if (responseTrim.contains("use WebSocket on this port")) {
                error = "relay has no HTTP /relay API (deploy old Node relay or Java-only JAR). Redeploy latest shareinv-relay Node server.js on Render.";
            } else {
                error = "non-relay response";
            }
        }
        return new PostResult(code, relayOk, error);
    }

    private static String readFully(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    public static final class PostResult {
        final int code;
        final boolean ok;
        final String error;

        PostResult(int code, boolean ok, String error) {
            this.code = code;
            this.ok = code >= 200 && code < 300 && ok;
            this.error = error == null ? "" : error.trim();
        }

        String describe() {
            if (!error.isEmpty()) {
                return "HTTP " + code + ", " + error;
            }
            return "HTTP " + code;
        }
    }

    private static String safeMsg(Throwable t) {
        if (t == null || t.getMessage() == null || t.getMessage().trim().isEmpty()) {
            return t == null ? "unknown error" : t.getClass().getSimpleName();
        }
        return t.getMessage().trim();
    }
}
