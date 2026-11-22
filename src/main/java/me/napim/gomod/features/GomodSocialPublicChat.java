package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;

/**
 * Global public chat for gomod Social — everyone on the same relay channel can read and post.
 */
public final class GomodSocialPublicChat {
    public static final String THREAD_KEY = "__public__";
    private static final int MAX_MESSAGE_LEN = 500;

    private GomodSocialPublicChat() {}

    public static List<GomodSocialChat.Message> getMessages() {
        return GomodSocialStore.getThread(THREAD_KEY);
    }

    public static int getUnreadCount() {
        return GomodSocialStore.getUnreadCount(THREAD_KEY);
    }

    public static long getLastReadMs() {
        return GomodSocialStore.getLastReadMs(THREAD_KEY);
    }

    /** Mark all current public messages as read (call when leaving the public chat view). */
    public static void markReadNow() {
        long latest = 0L;
        for (GomodSocialChat.Message m : getMessages()) {
            if (m != null && m.ts > latest) {
                latest = m.ts;
            }
        }
        GomodSocialStore.markRead(THREAD_KEY, latest > 0L ? latest : System.currentTimeMillis());
    }

    /** @deprecated use {@link #markReadNow()} when leaving the view; sending still marks read for own messages */
    public static void markRead() {
        markReadNow();
    }

    public static String channelLabel() {
        String ch = ConfigManager.getGlobalChatChannel();
        return ch == null || ch.trim().isEmpty() ? "global" : ch.trim();
    }

    public static void send(String messageRaw) {
        String message = messageRaw == null ? "" : messageRaw.trim();
        if (message.isEmpty()) {
            return;
        }
        if (message.length() > MAX_MESSAGE_LEN) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Message too long (max " + MAX_MESSAGE_LEN + ").");
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String sender = GomodSocialPresence.sessionPlayerName(mc);
        if (sender.isEmpty()) {
            return;
        }
        if (!GomodServiceStatus.isServiceConnected()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Not connected to gomod Service — public chat unavailable.");
            return;
        }
        String server = GomodSocialPresence.resolveLocalServer(mc);
        long ts = System.currentTimeMillis();
        GomodSocialStore.appendMessage(THREAD_KEY,
                new GomodSocialChat.Message(ts, sender, message, "public", server));
        markRead();
        Main.getGlobalChatClient().send(message);
    }

    public static void ingest(long ts, String sender, String server, String text, boolean notify) {
        if (sender == null || sender.trim().isEmpty() || text == null || text.trim().isEmpty()) {
            return;
        }
        String from = resolveDisplaySender(sender.trim());
        String body = text.trim();
        String srv = server == null ? "" : server.trim();
        long useTs = ts > 0L ? ts : System.currentTimeMillis();
        List<GomodSocialChat.Message> thread = GomodSocialStore.getThread(THREAD_KEY);
        if (!thread.isEmpty()) {
            GomodSocialChat.Message last = thread.get(thread.size() - 1);
            if (last != null && last.ts == useTs && from.equalsIgnoreCase(last.from)
                    && body.equals(last.text) && srv.equals(last.server)) {
                return;
            }
            // Skip relay echo of our own optimistic send() append (timestamps often differ).
            for (int i = thread.size() - 1; i >= Math.max(0, thread.size() - 8); i--) {
                GomodSocialChat.Message m = thread.get(i);
                if (m == null) {
                    continue;
                }
                if (!from.equalsIgnoreCase(m.from) || !body.equals(m.text)) {
                    continue;
                }
                if (!srv.isEmpty() && m.server != null && !srv.equals(m.server)) {
                    continue;
                }
                if (Math.abs(useTs - m.ts) <= 60_000L) {
                    return;
                }
            }
        }
        GomodSocialStore.appendMessage(THREAD_KEY,
                new GomodSocialChat.Message(useTs, from, body, "public", srv));
        if (notify) {
            notifyIncomingPublicMessage(from, body);
        }
    }

    private static void notifyIncomingPublicMessage(String from, String body) {
        Minecraft mc = Minecraft.getMinecraft();
        String self = GomodSocialPresence.sessionPlayerName(mc);
        if (self.isEmpty()) {
            return;
        }
        if (from.equalsIgnoreCase(self)) {
            return;
        }
        if (GomodSocialPublicMentions.mentionsPlayer(body, self)) {
            IncomingPublicPingOverlay.show(from, body);
        } else {
            IncomingPublicPingOverlay.showPublicMessage(from, body);
        }
    }

    private static String resolveDisplaySender(String sender) {
        if (sender == null) {
            return "";
        }
        String name = sender.trim();
        if (name.isEmpty()) {
            return "";
        }
        Minecraft mc = Minecraft.getMinecraft();
        String self = GomodSocialPresence.sessionPlayerName(mc);
        if (!self.isEmpty() && name.equalsIgnoreCase(self)) {
            return self;
        }
        String friend = FriendListScanner.matchFriendSender(name);
        if (friend != null && !friend.trim().isEmpty()) {
            return friend.trim();
        }
        if (ConfigManager.isGomodFriend(name)) {
            String display = ConfigManager.getGomodFriendDisplayName(name);
            if (display != null && !display.trim().isEmpty()) {
                return display.trim();
            }
        }
        for (GomodSocialChat.Message m : GomodSocialStore.getThread(THREAD_KEY)) {
            if (m != null && m.from != null && m.from.equalsIgnoreCase(name) && hasUppercase(m.from)) {
                return m.from;
            }
        }
        if (!hasUppercase(name)) {
            String canonical = MojangUsernameResolver.resolveCanonicalUsername(name);
            if (canonical != null && !canonical.trim().isEmpty()) {
                return canonical.trim();
            }
        }
        return name;
    }

    private static boolean hasUppercase(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isUpperCase(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
