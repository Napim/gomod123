package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP relay calls for mutual friend requests (same /relay endpoint as global chat).
 */
public final class GomodFriendRelay {
    private static final int RELAY_CONNECT_MS = 3500;
    private static final int RELAY_READ_MS = 12000;
    private static final JsonParser JSON_PARSER = new JsonParser();
    private static final Set<String> FRIEND_REQUESTS_IN_FLIGHT = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    public enum FriendAddSource {
        /** {@code /gfriend add …} — includes relay delay note on success */
        GFRIEND_COMMAND,
        /** Friends screen Request button — shorter confirmation */
        FRIENDS_GUI
    }

    private GomodFriendRelay() {}

    /** Result of a relay POST; {@link #exception} is set only when no HTTP status was usable. */
    public static final class RelayHttpOutcome {
        public final boolean success;
        /** Response code when the server replied; {@code -1} if the request failed at the TCP/IO layer. */
        public final int httpCode;
        public final Exception exception;
        public final JsonArray friendsSnapshot;

        private RelayHttpOutcome(boolean success, int httpCode, Exception exception, JsonArray friendsSnapshot) {
            this.success = success;
            this.httpCode = httpCode;
            this.exception = exception;
            this.friendsSnapshot = friendsSnapshot;
        }
    }

    public static boolean postFriendRequest(String targetName) {
        Minecraft mc = Minecraft.getMinecraft();
        String me = GomodSocialPresence.sessionPlayerName(mc);
        if (me.isEmpty()) {
            return false;
        }
        return postFriendNotifyOnCurrentThread(me, targetName.trim());
    }

    /**
     * Runs Mojang name resolution + relay POST off-thread, then shows chat / errors on the client thread.
     * Call after synchronously validating empty name and {@code typedTarget.equalsIgnoreCase(self)} when applicable.
     */
    public static void postFriendRequestAddAsync(final Minecraft mc, final String typedTarget, final FriendAddSource source) {
        if (mc == null || typedTarget == null || source == null) {
            return;
        }
        final String tn = typedTarget.trim();
        if (tn.isEmpty()) {
            return;
        }
        final String sender = GomodSocialPresence.sessionPlayerName(mc);
        if (sender.isEmpty()) {
            return;
        }
        if (GomodSocialRequests.hasOutgoingTo(tn)) {
            notifyFriendRequestAlreadyPending(mc, tn);
            return;
        }
        final String inFlightKey = tn.toLowerCase(Locale.ROOT);
        if (!FRIEND_REQUESTS_IN_FLIGHT.add(inFlightKey)) {
            notifyFriendRequestAlreadyPending(mc, tn);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                runFriendRequestAddWorker(mc, tn, sender, source);
            } finally {
                FRIEND_REQUESTS_IN_FLIGHT.remove(inFlightKey);
            }
        }, "gomod-friend-send");
        t.setDaemon(true);
        t.start();
    }

    private static void runFriendRequestAddWorker(final Minecraft mc, final String typedName, final String sender,
            final FriendAddSource source) {
        String canonical = MojangUsernameResolver.resolveCanonicalUsername(typedName);
        if (canonical == null) {
            mc.addScheduledTask(() -> {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not find a Minecraft account named \""
                        + typedName + "\".");
            });
            return;
        }
        if (sender != null && sender.equalsIgnoreCase(canonical)) {
            mc.addScheduledTask(() -> {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Are you schizophrenic? Do you need help?");
            });
            return;
        }
        if (ConfigManager.isGomodFriend(canonical)) {
            final String canon = canonical;
            mc.addScheduledTask(() -> {
                if (source == FriendAddSource.FRIENDS_GUI) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Already friends with "
                            + ConfigManager.getGomodFriendDisplayName(canon) + ".");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Already friend with "
                            + ConfigManager.getGomodFriendDisplayName(canon) + ".");
                }
            });
            return;
        }
        if (GomodSocialRequests.hasOutgoingTo(canonical)) {
            notifyFriendRequestAlreadyPending(mc, canonical);
            return;
        }
        RelayHttpOutcome outcome = postFriendRelayOnly(sender, canonical);
        final String canonForMsg = canonical;
        mc.addScheduledTask(() -> {
            if (outcome.success) {
                GomodSocialRequests.addOutgoing(canonForMsg);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Friend request sent to "
                        + EnumChatFormatting.AQUA + canonForMsg + EnumChatFormatting.GREEN + ".");
            } else if (outcome.exception != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Friend relay failed: "
                        + outcome.exception.getMessage());
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Friend relay failed (HTTP " + outcome.httpCode + ").");
            }
        });
    }

    private static void notifyFriendRequestAlreadyPending(final Minecraft mc, final String name) {
        mc.addScheduledTask(() -> {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Friend request to " + EnumChatFormatting.AQUA + name
                    + EnumChatFormatting.YELLOW + " is already awaiting a response.");
        });
    }

    public static boolean postFriendAccept(String otherPlayer) {
        Minecraft mc = Minecraft.getMinecraft();
        String me = GomodSocialPresence.sessionPlayerName(mc);
        if (me.isEmpty()) {
            return false;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "friend_accept");
        o.addProperty("channel", ConfigManager.getGlobalChatChannel());
        o.addProperty("from", me);
        o.addProperty("to", otherPlayer.trim());
        GomodSocialRelayKeys.attachFriendRequest(o, otherPlayer.trim());
        GomodSocialIdentity.attachSender(o);
        GomodSocialIdentity.attachRelayPlayer(o, mc);
        return postOkApplyFriends(o);
    }

    public static void postFriendRemoveAsync(final String targetRaw) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String self = GomodSocialPresence.sessionPlayerName(mc);
        if (self.isEmpty() || targetRaw == null) {
            return;
        }
        final String target = targetRaw.trim();
        if (target.isEmpty()) {
            return;
        }
        if (!ConfigManager.isGomodFriend(target)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + target + " is not friend with you.");
            return;
        }
        Thread t = new Thread(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("type", "friend_remove");
            o.addProperty("channel", ConfigManager.getGlobalChatChannel());
            o.addProperty("player", self);
            o.addProperty("target", target);
            GomodSocialIdentity.attachPoll(o);
            GomodSocialIdentity.attachRelayPlayer(o, mc);
            GomodSocialRelayKeys.attachFriendRequest(o, target);
            RelayHttpOutcome outcome = postFriendRemoveWithRetry(o);
            mc.addScheduledTask(() -> {
                if (outcome.success) {
                    if (outcome.friendsSnapshot != null) {
                        ConfigManager.applyFriendsFromService(outcome.friendsSnapshot);
                    } else {
                        ConfigManager.removeGomodFriend(target);
                    }
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Removed gomod friend: "
                            + EnumChatFormatting.AQUA + ConfigManager.getGomodFriendDisplayName(target));
                } else if (outcome.exception != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Friend relay failed: "
                            + outcome.exception.getMessage());
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Friend relay failed (HTTP "
                            + outcome.httpCode + ").");
                }
            });
        }, "gomod-friend-remove");
        t.setDaemon(true);
        t.start();
    }

    /** Upload legacy local friends to the relay once (per profile) after mod upgrade. */
    public static void postFriendsBootstrapIfNeeded() {
        if (!ConfigManager.shouldPostFriendsBootstrap()) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        ConfigManager.markFriendsBootstrapPosted();
        Thread t = new Thread(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("type", "friend_sync");
            o.addProperty("channel", ConfigManager.getGlobalChatChannel());
            GomodSocialIdentity.attachPoll(o);
            GomodSocialIdentity.attachRelayPlayer(o, mc);
            JsonArray friends = new JsonArray();
            for (String name : ConfigManager.getGomodFriends()) {
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                JsonObject f = new JsonObject();
                f.addProperty("name", name.trim());
                String sid = ConfigManager.getFriendSocialId(name);
                if (sid != null && !sid.isEmpty()) {
                    f.addProperty("socialId", sid);
                }
                friends.add(f);
            }
            o.add("friends", friends);
            RelayHttpOutcome outcome = postRelayReadBody(o);
            if (outcome.success && outcome.friendsSnapshot != null) {
                mc.addScheduledTask(() -> ConfigManager.applyFriendsFromService(outcome.friendsSnapshot));
            }
        }, "gomod-friend-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private static boolean postOkApplyFriends(JsonObject payload) {
        RelayHttpOutcome outcome = postRelayReadBody(payload);
        if (outcome.success) {
            if (outcome.friendsSnapshot != null) {
                ConfigManager.applyFriendsFromService(outcome.friendsSnapshot);
            }
            return true;
        }
        if (outcome.exception != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Friend relay failed: "
                    + outcome.exception.getMessage());
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Friend relay failed (HTTP " + outcome.httpCode + ").");
        }
        return false;
    }

    private static boolean postFriendNotifyOnCurrentThread(String fromName, String targetName) {
        JsonObject o = buildFriendRequestPayload(fromName, targetName);
        return postRelayReadBody(o).success;
    }

    private static JsonObject buildFriendRequestPayload(String fromName, String targetName) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "friend_request");
        o.addProperty("channel", ConfigManager.getGlobalChatChannel());
        o.addProperty("from", fromName);
        o.addProperty("to", targetName.trim());
        GomodSocialRelayKeys.attachFriendRequest(o, targetName.trim());
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            GomodSocialIdentity.attachRelayPlayer(o, mc);
        }
        return o;
    }

    /** POST without chat; safe to call off the client thread. */
    static RelayHttpOutcome postFriendRelayOnly(String fromName, String targetName) {
        JsonObject o = buildFriendRequestPayload(fromName, targetName);
        return postRelayReadBody(o);
    }

    private static RelayHttpOutcome postRelayReadBody(JsonObject payload) {
        try {
            String body = postForBody(ConfigManager.getGlobalChatEndpoint(), payload);
            int code = 200;
            if (body == null) {
                return new RelayHttpOutcome(false, -1, null, null);
            }
            JsonArray friends = null;
            try {
                JsonObject parsed = JSON_PARSER.parse(body).getAsJsonObject();
                if (parsed != null && parsed.has("friends") && parsed.get("friends").isJsonArray()) {
                    friends = parsed.getAsJsonArray("friends");
                } else if (parsed != null && parsed.has("friendsSnapshot") && parsed.get("friendsSnapshot").isJsonArray()) {
                    friends = parsed.getAsJsonArray("friendsSnapshot");
                }
                if (parsed != null && parsed.has("ok") && !parsed.get("ok").getAsBoolean()) {
                    return new RelayHttpOutcome(false, 400, null, friends);
                }
            } catch (Throwable ignored) {
            }
            return new RelayHttpOutcome(true, code, null, friends);
        } catch (Exception e) {
            return new RelayHttpOutcome(false, -1, e, null);
        }
    }

    /** Friend removal is idempotent on the relay, so retry transient edge/Render failures safely. */
    private static RelayHttpOutcome postFriendRemoveWithRetry(JsonObject payload) {
        RelayHttpOutcome outcome = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            outcome = postRelayReadBody(payload);
            if (outcome.success || !isRetryableFriendRemoveFailure(outcome) || attempt == 2) {
                return outcome;
            }
            try {
                Thread.sleep(attempt == 0 ? 500L : 1200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return outcome;
            }
        }
        return outcome;
    }

    private static boolean isRetryableFriendRemoveFailure(RelayHttpOutcome outcome) {
        if (outcome == null) return false;
        if (outcome.httpCode >= 500 && outcome.httpCode <= 599) return true;
        if (outcome.exception == null) return false;
        String message = outcome.exception.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("http 502") || lower.contains("http 503") || lower.contains("http 504")
                || lower.contains("timed out") || lower.contains("connection reset")
                || lower.contains("unexpected end of file");
    }

    private static String postForBody(String urlStr, JsonObject payload) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setConnectTimeout(RELAY_CONNECT_MS);
        conn.setReadTimeout(RELAY_READ_MS);
        conn.setDoOutput(true);
        GomodRelayAuth.authorize(conn, urlStr);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        GomodRelayAuth.noteResponseCode(urlStr, code);
        java.io.InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            } catch (Exception ignored) {
            }
        }
        if (code < 200 || code >= 300) {
            throw new java.io.IOException("HTTP " + code + (sb.length() > 0 ? ": " + sb : ""));
        }
        return sb.toString();
    }
}
