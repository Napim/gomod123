package me.napim.gomod.features;

import me.napim.gomod.shareinv.ShareInvManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent incoming/outgoing gomod friend requests (Essential-style pending list).
 */
public final class GomodSocialRequests {
    private GomodSocialRequests() {}

    public static List<String> getIncoming() {
        return ConfigManager.getGomodIncomingFriendRequests();
    }

    public static List<String> getOutgoing() {
        return ConfigManager.getGomodOutgoingFriendRequests();
    }

    /** Normalize relay / Mojang names and prefer labels already stored in pending lists. */
    public static String resolveIncomingPeer(String raw) {
        if (raw == null) {
            return "";
        }
        String n = raw.trim();
        if (n.isEmpty()) {
            return n;
        }
        for (String o : getOutgoing()) {
            if (o != null && ConfigManager.requestPeersMatch(o, n)) {
                return o.trim();
            }
        }
        for (String i : getIncoming()) {
            if (i != null && ConfigManager.requestPeersMatch(i, n)) {
                return i.trim();
            }
        }
        try {
            String canon = MojangUsernameResolver.getCachedCanonicalUsername(n);
            if (canon != null && !canon.trim().isEmpty()) {
                return canon.trim();
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** @return true only when this is a newly stored incoming request. */
    public static boolean addIncoming(String name) {
        if (name == null) {
            return false;
        }
        String n = resolveIncomingPeer(name);
        if (n.isEmpty() || isSelfName(n)) {
            return false;
        }
        boolean added = false;
        if (!ConfigManager.isGomodFriend(n)) {
            added = ConfigManager.addGomodIncomingFriendRequest(n);
        }
        ConfigManager.removeGomodOutgoingFriendRequest(n);
        return added;
    }

    public static void addOutgoing(String name) {
        if (name == null) {
            return;
        }
        String n = name.trim();
        if (n.isEmpty() || ConfigManager.isGomodFriend(n)) {
            return;
        }
        ConfigManager.addGomodOutgoingFriendRequest(n);
    }

    public static void removeIncoming(String name) {
        ConfigManager.removeGomodIncomingFriendRequest(name);
    }

    public static void cancelOutgoing(String name) {
        ConfigManager.removeGomodOutgoingFriendRequest(name);
    }

    public static void clearIncoming() {
        for (String n : new ArrayList<String>(getIncoming())) {
            ConfigManager.removeGomodIncomingFriendRequest(n);
        }
    }

    public static List<String> sortedIncoming() {
        List<String> list = new ArrayList<String>(getIncoming());
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    public static List<String> unseenIncoming() {
        List<String> unseen = new ArrayList<String>();
        for (String name : sortedIncoming()) {
            if (name != null && !ConfigManager.isGomodIncomingFriendRequestSeen(name)) {
                unseen.add(name);
            }
        }
        return unseen;
    }

    public static boolean isIncomingSeen(String name) {
        return ConfigManager.isGomodIncomingFriendRequestSeen(name);
    }

    public static void markIncomingSeen(String name) {
        ConfigManager.markGomodIncomingFriendRequestSeen(name);
    }

    public static void markAllIncomingSeen() {
        ConfigManager.markAllGomodIncomingFriendRequestsSeen();
    }

    public static boolean hasOutgoingTo(String name) {
        if (name == null) {
            return false;
        }
        for (String outgoing : getOutgoing()) {
            if (outgoing != null && ConfigManager.requestPeersMatch(outgoing, name)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> sortedOutgoing() {
        List<String> list = new ArrayList<String>(getOutgoing());
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    /** Hides stale outgoing rows when the same player already has an incoming request. */
    public static List<String> sortedOutgoingForDisplay() {
        List<String> out = sortedOutgoing();
        List<String> in = sortedIncoming();
        List<String> filtered = new ArrayList<String>();
        for (String o : out) {
            if (o == null) {
                continue;
            }
            boolean hasIncoming = false;
            for (String i : in) {
                if (i != null && ConfigManager.requestPeersMatch(o, i)) {
                    hasIncoming = true;
                    break;
                }
            }
            if (!hasIncoming) {
                String live = IncomingFriendRequestOverlay.peekActiveIncomingFrom();
                if (live != null && ConfigManager.requestPeersMatch(o, live)) {
                    hasIncoming = true;
                }
            }
            if (!hasIncoming) {
                filtered.add(o);
            }
        }
        return filtered;
    }

    public static boolean hasPending() {
        return !getIncoming().isEmpty() || !sortedOutgoingForDisplay().isEmpty()
                || IncomingFriendRequestOverlay.peekActiveIncomingFrom() != null
                || ShareInvManager.hasPendingInvite();
    }

    public static int pendingBadgeCount() {
        // Only requests awaiting an action from this player belong in the notification badge.
        // Outgoing requests remain visible in the Requests panel, but are not notifications.
        int n = ConfigManager.getUnseenGomodIncomingFriendRequestCount();
        if (IncomingFriendRequestOverlay.peekActiveIncomingFrom() != null) {
            String live = IncomingFriendRequestOverlay.peekActiveIncomingFrom();
            if (live != null && ConfigManager.findGomodIncomingFriendRequestIndex(live) < 0) {
                n++;
            }
        }
        if (ShareInvManager.hasPendingInvite()) {
            n++;
        }
        return n;
    }

    private static boolean isSelfName(String name) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName());
    }
}
