package me.napim.gomod.features;

import java.util.List;

/** Unread gomod Social DMs + public chat messages. */
public final class GomodSocialUnreadCounter {

    private GomodSocialUnreadCounter() {}

    public static int publicMentionUnread() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return 0;
        }
        long lastRead = GomodSocialPublicChat.getLastReadMs();
        long sessionStart = GomodSocialStore.getLocalOnlineSinceMs();
        long cutoff = sessionStart > 0L ? Math.max(lastRead, sessionStart) : lastRead;
        int count = 0;
        for (GomodSocialChat.Message m : GomodSocialPublicChat.getMessages()) {
            if (m == null || m.ts <= cutoff || m.text == null) {
                continue;
            }
            if (m.from != null && m.from.equalsIgnoreCase(mc.thePlayer.getName())) {
                continue;
            }
            if (GomodSocialPublicMentions.mentionsPlayer(m.text, mc.thePlayer.getName())) {
                count++;
            }
        }
        return count;
    }

    public static int dmUnread() {
        int total = 0;
        List<String> conversations = GomodSocialStore.getDirectConversationNames();
        for (String peer : conversations) {
            if (peer != null && !peer.trim().isEmpty()) {
                total += GomodSocialStore.getSessionUnreadCount(peer);
            }
        }
        return total;
    }

    public static int totalUnread() {
        return publicMentionUnread() + dmUnread();
    }

    public static boolean hasUnread() {
        return totalUnread() > 0;
    }
}
