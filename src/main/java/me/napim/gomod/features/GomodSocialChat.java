package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Friend direct messages over the HTTP relay ({@code friend_dm_send} / {@code kind: friend_dm}).
 */
public final class GomodSocialChat {
    private static final int MAX_MESSAGE_LEN = 500;

    private GomodSocialChat() {}

    public static final class Message {
        public final long ts;
        public final String from;
        public final String text;
        public final String kind;
        /** Optional server label (public chat). */
        public final String server;
        /** Optional cape receipt label/image metadata. */
        public final String capeLabel;
        public final String capeImageBase64;
        public final boolean automaticCapeAccept;
        public final String fitAttachment;
        public final String emoteAttachment;

        public Message(long ts, String from, String text, String kind) {
            this(ts, from, text, kind, "");
        }

        public Message(long ts, String from, String text, String kind, String server) {
            this(ts, from, text, kind, server, "", "", false);
        }

        public Message(long ts, String from, String text, String kind, String server,
                String capeLabel, String capeImageBase64, boolean automaticCapeAccept) {
            this(ts,from,text,kind,server,capeLabel,capeImageBase64,automaticCapeAccept,"");
        }
        public Message(long ts, String from, String text, String kind, String server,
                String capeLabel, String capeImageBase64, boolean automaticCapeAccept,String fitAttachment) {
            this.ts = ts;
            this.from = from == null ? "" : from;
            this.text = text == null ? "" : text;
            this.kind = kind == null ? "text" : kind;
            this.server = server == null ? "" : server;
            this.capeLabel = capeLabel == null ? "" : capeLabel;
            this.capeImageBase64 = capeImageBase64 == null ? "" : capeImageBase64;
            this.automaticCapeAccept = automaticCapeAccept;
            this.fitAttachment = LunarFitSharing.attachment(fitAttachment);
            this.emoteAttachment = GomodEmoteSharing.attachment(fitAttachment);
        }
    }

    public static void init() {
        Minecraft mc = Minecraft.getMinecraft();
        GomodSocialAccount.refresh(mc);
        GomodSocialForums.resetThreadSyncCursor();
        GomodSocialStore.ensureAccount(mc);
        GomodSocialTempGroups.ensureAccount(mc);
        GomodSocialForums.ensureAccount(mc);
    }

    public static void sendToFriend(final String targetRaw, final String messageRaw) {
        final String target = targetRaw == null ? "" : targetRaw.trim();
        final String message = messageRaw == null ? "" : messageRaw.trim();
        if (target.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Select a friend to message.");
            return;
        }
        if (message.isEmpty()) {
            return;
        }
        if (message.length() > MAX_MESSAGE_LEN) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Message too long (max " + MAX_MESSAGE_LEN + ").");
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        final String sender = GomodSocialPresence.sessionPlayerName(mc);
        if (sender.isEmpty()) {
            return;
        }
        if (target.equalsIgnoreCase(sender)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "You can't message yourself.");
            return;
        }
        final String displayTarget = ConfigManager.getGomodFriendDisplayName(target);
        final long ts = System.currentTimeMillis();
        GomodSocialStore.appendPendingOutgoingMessage(displayTarget, new Message(ts, sender, message, "text"));
        GomodSocialStore.markRead(displayTarget, ts);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "friend_dm_send");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("sender", sender);
                    payload.addProperty("target", displayTarget);
                    payload.addProperty("message", message);
                    GomodSocialRelayKeys.attachDmTarget(payload, displayTarget);
                    post(ConfigManager.getGlobalChatEndpoint(), payload);
                } catch (Exception e) {
                    final String err = e.getMessage() == null ? "unknown error" : e.getMessage();
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                    + "Failed to send message: " + err);
                            if (err.contains("unknown_type") || err.contains("HTTP 400")) {
                                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                                        + "Your relay server is outdated — redeploy gomod-shareinv-relay with the latest server.js (friend_dm_send).");
                            }
                        }
                    });
                }
            }
        }, "gomod-friend-dm").start();
    }

    public static void handleIncomingDm(String from, String text, long ts) {
        if (from == null || from.trim().isEmpty()) {
            return;
        }
        String fitAttachment=LunarFitSharing.attachment(text);
        String emoteAttachment=GomodEmoteSharing.attachment(text);
        text = GomodEmoteSharing.messageText(from,LunarFitSharing.messageText(from, text, ts),ts);
        String senderIgn = from.trim();
        String peer = FriendListScanner.matchFriendSender(senderIgn);
        if (peer == null) {
            peer = ConfigManager.getGomodFriendDisplayName(senderIgn);
        }
        if (peer == null || peer.isEmpty()) {
            peer = senderIgn;
        }
        long useTs = ts > 0L ? ts : System.currentTimeMillis();
        GomodSocialStore.recordLastSeen(peer, useTs);
        GomodSocialStore.appendMessage(peer, new Message(useTs, senderIgn, text == null ? "" : text, !emoteAttachment.isEmpty()?"lunar_emote":fitAttachment.isEmpty()?"text":"lunar_fit", "", "", "", false, emoteAttachment.isEmpty()?fitAttachment:emoteAttachment));
        if(fitAttachment.isEmpty()&&emoteAttachment.isEmpty())IncomingFriendDmOverlay.show(peer, text);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "[DM] " + EnumChatFormatting.YELLOW + peer
                    + EnumChatFormatting.WHITE + ": " + (text == null ? "" : text));
        }
    }

    public static void handleIncomingCapeAcceptReceipt(String from, String text, long ts,
            String capeLabel, String capeImageBase64, boolean automatic) {
        if (from == null || from.trim().isEmpty()) {
            return;
        }
        String senderIgn = from.trim();
        String peer = FriendListScanner.matchFriendSender(senderIgn);
        if (peer == null) {
            peer = ConfigManager.getGomodFriendDisplayName(senderIgn);
        }
        if (peer == null || peer.isEmpty()) {
            peer = senderIgn;
        }
        String label = GomodCapeShareHistory.cleanLabel(capeLabel);
        String body = text == null || text.trim().isEmpty()
                ? (net.minecraft.util.EnumChatFormatting.ITALIC
                + (automatic ? "Auto-accepted your cape: " : "Accepted your cape: ")
                + net.minecraft.util.EnumChatFormatting.LIGHT_PURPLE
                + net.minecraft.util.EnumChatFormatting.ITALIC + label)
                : text;
        long useTs = ts > 0L ? ts : System.currentTimeMillis();
        GomodSocialStore.recordLastSeen(peer, useTs);
        GomodSocialStore.appendMessage(peer, new Message(useTs, senderIgn, body, "cape_accept_receipt", "",
                label, capeImageBase64, automatic));
        IncomingFriendDmOverlay.show(peer, net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(body));
    }

    public static void sendTempGroupInvite(final GomodSocialTempGroups.Group group, final String targetRaw) {
        if (group == null || targetRaw == null || targetRaw.trim().isEmpty()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        final String sender = GomodSocialPresence.sessionPlayerName(mc);
        if (sender.isEmpty()) return;
        final String target = targetRaw.trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "temp_group_invite");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("sender", sender);
                    payload.addProperty("target", target);
                    JsonObject groupJson = new JsonObject();
                    groupJson.addProperty("id", group.id);
                    groupJson.addProperty("name", group.name);
                    groupJson.addProperty("creator", group.creator);
                    groupJson.addProperty("lastActivityMs", group.lastActivityMs);
                    JsonArray members = new JsonArray();
                    for (String member : group.members) members.add(new com.google.gson.JsonPrimitive(member));
                    groupJson.add("members", members);
                    payload.add("group", groupJson);
                    GomodSocialRelayKeys.attachDmTarget(payload, target);
                    post(ConfigManager.getGlobalChatEndpoint(), payload);
                } catch (Exception ignored) {
                }
            }
        }, "gomod-group-invite").start();
    }

    public static void sendScreenshotToFriend(String targetRaw, java.io.File imageFile) {
        if (targetRaw == null || targetRaw.trim().isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Select a friend to message.");
            return;
        }
        if (imageFile == null || !imageFile.isFile()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No screenshot selected.");
            return;
        }
        String target = ConfigManager.getGomodFriendDisplayName(targetRaw.trim());
        ScreenshotShareService.shareFileToPlayer(target, imageFile);
    }

    /** Called after relay accepts an outgoing screenshot (updates local thread). */
    public static void onScreenshotSent(String peer, String fileName) {
        if (peer == null || peer.trim().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String sender = GomodSocialPresence.sessionPlayerName(mc);
        if (sender.isEmpty()) sender = "You";
        long ts = System.currentTimeMillis();
        String label = fileName == null || fileName.isEmpty() ? "screenshot.png" : fileName;
        GomodSocialStore.appendMessage(peer, new Message(ts, sender, label, "screenshot"));
        GomodSocialStore.markRead(peer, ts);
    }

    public static void handleIncomingScreenshot(String fromIgn, long ts, String fileName) {
        if (fromIgn == null || fromIgn.trim().isEmpty()) {
            return;
        }
        String senderIgn = fromIgn.trim();
        String peer = ConfigManager.getGomodFriendDisplayName(senderIgn);
        long useTs = ts > 0L ? ts : System.currentTimeMillis();
        GomodSocialStore.recordLastSeen(peer, useTs);
        String label = fileName == null ? "" : fileName.trim();
        GomodSocialStore.appendMessage(peer, new Message(useTs, senderIgn, label, "screenshot"));
    }

    public static void handlePresence(String from, long ts, String server) {
        if (from == null || from.trim().isEmpty() || ts <= 0L) {
            return;
        }
        String display = ConfigManager.getGomodFriendDisplayName(from.trim());
        GomodSocialStore.recordLastSeen(display, ts);
        GomodSocialStore.recordFriendPresence(display, ts, server);
    }

    /** Dock header status line (online time only — server is on {@link #localPlayerOnlineLocationLine}). */
    public static String localPlayerOnlineStatusLine(Minecraft mc) {
        if (!GomodServiceStatus.isServiceConnected()) {
            return net.minecraft.util.EnumChatFormatting.RED + "Not connected to gomod Service";
        }
        GomodSocialStore.ensureLocalOnlineSession();
        if (GomodSocialStore.isAppearOffline()) {
            return net.minecraft.util.EnumChatFormatting.GRAY + "Appearing offline";
        }
        long since = GomodSocialStore.getLocalOnlineSinceMs();
        if (since <= 0L) {
            return net.minecraft.util.EnumChatFormatting.GRAY + "Online";
        }
        return net.minecraft.util.EnumChatFormatting.GRAY + "Online since " + formatMessageTime(since);
    }

    /** Server / location on its own line under the status line; empty when not applicable. */
    public static String localPlayerOnlineLocationLine(Minecraft mc) {
        if (!GomodServiceStatus.isServiceConnected() || GomodSocialStore.isAppearOffline() || mc == null) {
            return "";
        }
        String server = GomodSocialPresence.resolveLocalServer(mc);
        if (server == null) {
            return "";
        }
        String label = GomodSocialPresence.formatServerLabel(server, false);
        if (label.isEmpty()) {
            return "";
        }
        return net.minecraft.util.EnumChatFormatting.GRAY + label;
    }

    /** Dock header subtitle: how long the local player has been online this session. */
    public static String localPlayerOnlineSubtitle(Minecraft mc) {
        String status = localPlayerOnlineStatusLine(mc);
        String loc = localPlayerOnlineLocationLine(mc);
        if (loc == null || loc.isEmpty()) {
            return status;
        }
        return status + net.minecraft.util.EnumChatFormatting.DARK_GRAY + " \u00b7 " + loc;
    }

    /** Subtitle for friend rows: online state, last seen, or last message hint. */
    public static String friendSubtitle(String name, Minecraft mc) {
        if (name == null || name.trim().isEmpty()) {
            return EnumChatFormatting.DARK_GRAY + "Unknown";
        }
        String live = FriendListScanner.resolveGomodFriendStatusLabel(name, mc);
        if (live != null && (live.contains("Online") || live.contains("Blitz") || live.contains("Away"))) {
            return live;
        }
        long seen = GomodSocialStore.getLastSeenMs(name);
        if (seen > 0L) {
            return EnumChatFormatting.DARK_GRAY + "Last seen " + formatRelativeTime(seen);
        }
        return EnumChatFormatting.DARK_GRAY + "Offline";
    }

    public static String formatRelativeTime(long epochMs) {
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 60_000L) {
            return "just now";
        }
        if (diff < 3_600_000L) {
            long m = TimeUnit.MILLISECONDS.toMinutes(diff);
            return m + "m ago";
        }
        if (diff < 86_400_000L) {
            long h = TimeUnit.MILLISECONDS.toHours(diff);
            return h + "h ago";
        }
        long d = TimeUnit.MILLISECONDS.toDays(diff);
        if (d < 14L) {
            return d + "d ago";
        }
        return new java.text.SimpleDateFormat("MMM d", Locale.US).format(new java.util.Date(epochMs));
    }

    /** Clock-style label for chat bubbles (today shows time only). */
    public static String formatMessageTime(long epochMs) {
        if (epochMs <= 0L) {
            return "";
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar when = java.util.Calendar.getInstance();
        when.setTimeInMillis(epochMs);
        java.text.SimpleDateFormat timeOnly = new java.text.SimpleDateFormat("h:mm a", Locale.US);
        String clock = timeOnly.format(new java.util.Date(epochMs));
        if (now.get(java.util.Calendar.YEAR) == when.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == when.get(java.util.Calendar.DAY_OF_YEAR)) {
            return clock;
        }
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (now.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR)
                && yesterday.get(java.util.Calendar.YEAR) == when.get(java.util.Calendar.YEAR)
                && yesterday.get(java.util.Calendar.DAY_OF_YEAR) == when.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "Yesterday " + clock;
        }
        if (now.get(java.util.Calendar.YEAR) == when.get(java.util.Calendar.YEAR)) {
            return new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new java.util.Date(epochMs));
        }
        return new java.text.SimpleDateFormat("MMM d yyyy, h:mm a", Locale.US).format(new java.util.Date(epochMs));
    }

    private static String post(String urlStr, JsonObject payload) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(12000);
        conn.setDoOutput(true);
        GomodRelayAuth.authorize(conn, urlStr);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        GomodRelayAuth.noteResponseCode(urlStr, code);
        String responseBody = readHttpBody(conn, code);
        if (code < 200 || code >= 300) {
            String detail = parseRelayError(responseBody);
            if (detail.isEmpty()) {
                throw new Exception("HTTP " + code);
            }
            throw new Exception("HTTP " + code + " (" + detail + ")");
        }
        return responseBody;
    }

    private static String readHttpBody(HttpURLConnection conn, int code) {
        java.io.InputStream stream = null;
        try {
            stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        } catch (Exception ignored) {
        }
        if (stream == null) {
            return "";
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String parseRelayError(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            JsonObject o = new JsonParser().parse(body).getAsJsonObject();
            if (o.has("error") && !o.get("error").isJsonNull()) {
                return o.get("error").getAsString();
            }
        } catch (Exception ignored) {
        }
        return body.length() > 120 ? body.substring(0, 117) + "..." : body;
    }
}
