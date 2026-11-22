package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local persistence for gomod Social: DM threads, read cursors, last-seen, appear-offline.
 */
public final class GomodSocialStore {
    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGES_PER_THREAD = 200;
    private static final JsonParser PARSER = new JsonParser();

    private static volatile boolean appearOffline;
    /** Wall-clock start of the current in-game / connected session (not persisted). */
    private static volatile long localOnlineSinceMs;
    private static final Map<String, Long> lastSeenMs = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> lastReadMs = new ConcurrentHashMap<String, Long>();
    /** Friend name (lowercase) -> last relay presence (server label + timestamp). */
    private static final Map<String, FriendPresence> friendPresence = new ConcurrentHashMap<String, FriendPresence>();
    private static final Map<String, List<GomodSocialChat.Message>> threads = new ConcurrentHashMap<String, List<GomodSocialChat.Message>>();
    /** Optimistic sends retained until an authoritative relay snapshot contains them. Not loaded from disk. */
    private static final Map<String, List<GomodSocialChat.Message>> pendingOutgoing =
            new ConcurrentHashMap<String, List<GomodSocialChat.Message>>();
    private static final long PENDING_OUTGOING_TTL_MS = 120000L;

    private static String loadedForAccount = "";

    private GomodSocialStore() {}

    /** Reloads chat storage when the logged-in Minecraft account changes. */
    public static void ensureAccount(Minecraft mc) {
        GomodSocialAccount.refresh(mc);
        String key = GomodSocialAccount.currentKey();
        if (key.equals(loadedForAccount)) {
            return;
        }
        loadedForAccount = key;
        appearOffline = false;
        localOnlineSinceMs = 0L;
        lastSeenMs.clear();
        lastReadMs.clear();
        friendPresence.clear();
        threads.clear();
        load();
    }

    public static void load() {
        File f = storeFile();
        if (!f.isFile()) {
            return;
        }
        try (FileReader fr = new FileReader(f)) {
            JsonObject root = PARSER.parse(fr).getAsJsonObject();
            if (root == null) {
                return;
            }
            // Default OFF — only persist when the user explicitly toggled it on.
            appearOffline = root.has("appearOffline") && root.get("appearOffline").getAsBoolean();
            loadLongMap(root, "lastSeenMs", lastSeenMs);
            loadLongMap(root, "lastReadMs", lastReadMs);
            loadFriendPresence(root);
            if (root.has("threads") && root.get("threads").isJsonObject()) {
                threads.clear();
                JsonObject to = root.getAsJsonObject("threads");
                for (Map.Entry<String, JsonElement> e : to.entrySet()) {
                    if (e.getValue() == null || !e.getValue().isJsonArray()) {
                        continue;
                    }
                    List<GomodSocialChat.Message> list = new ArrayList<GomodSocialChat.Message>();
                    JsonArray arr = e.getValue().getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject m = arr.get(i).getAsJsonObject();
                        if (m == null) {
                            continue;
                        }
                        long ts = m.has("ts") ? m.get("ts").getAsLong() : 0L;
                        String from = jsonStr(m, "from");
                        String text = jsonStr(m, "text");
                        String kind = jsonStr(m, "kind");
                        String server = jsonStr(m, "server");
                        String capeLabel = jsonStr(m, "capeLabel");
                        String capeImageBase64 = jsonStr(m, "capeImageBase64");
                        boolean automatic = m.has("automaticCapeAccept") && m.get("automaticCapeAccept").getAsBoolean();
                        list.add(new GomodSocialChat.Message(ts, from, text, kind, server,
                                capeLabel, capeImageBase64, automatic,jsonStr(m,"emoteAttachment").isEmpty()?jsonStr(m,"fitAttachment"):jsonStr(m,"emoteAttachment")));
                    }
                    if (!list.isEmpty()) {
                        threads.put(e.getKey().toLowerCase(Locale.ROOT), list);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        File f = storeFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        JsonObject root = new JsonObject();
        root.addProperty("appearOffline", appearOffline);
        root.add("lastSeenMs", longMapToJson(lastSeenMs));
        root.add("lastReadMs", longMapToJson(lastReadMs));
        root.add("friendPresence", friendPresenceToJson());
        JsonObject threadsJson = new JsonObject();
        for (Map.Entry<String, List<GomodSocialChat.Message>> e : threads.entrySet()) {
            JsonArray arr = new JsonArray();
            List<GomodSocialChat.Message> list = e.getValue();
            if (list == null) {
                continue;
            }
            int start = Math.max(0, list.size() - MAX_MESSAGES_PER_THREAD);
            for (int i = start; i < list.size(); i++) {
                GomodSocialChat.Message msg = list.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("ts", msg.ts);
                o.addProperty("from", msg.from);
                o.addProperty("text", msg.text);
                o.addProperty("kind", msg.kind);
                if(!msg.fitAttachment.isEmpty())o.addProperty("fitAttachment",msg.fitAttachment);
                if(!msg.emoteAttachment.isEmpty())o.addProperty("emoteAttachment",msg.emoteAttachment);
                if (msg.server != null && !msg.server.isEmpty()) {
                    o.addProperty("server", msg.server);
                }
                if (msg.capeLabel != null && !msg.capeLabel.isEmpty()) {
                    o.addProperty("capeLabel", msg.capeLabel);
                }
                if (msg.capeImageBase64 != null && !msg.capeImageBase64.isEmpty()) {
                    o.addProperty("capeImageBase64", msg.capeImageBase64);
                }
                if (msg.automaticCapeAccept) {
                    o.addProperty("automaticCapeAccept", true);
                }
                arr.add(o);
            }
            threadsJson.add(e.getKey(), arr);
        }
        root.add("threads", threadsJson);
        try (FileWriter fw = new FileWriter(f)) {
            GSON.toJson(root, fw);
        } catch (IOException ignored) {
        }
    }

    private static File storeFile() {
        return GomodSocialAccount.dataFile("gomodSocial");
    }

    private static String jsonStr(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static void loadLongMap(JsonObject root, String key, Map<String, Long> target) {
        target.clear();
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return;
        }
        JsonObject o = root.getAsJsonObject(key);
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            try {
                target.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsLong());
            } catch (Exception ignored) {
            }
        }
    }

    private static JsonObject longMapToJson(Map<String, Long> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    public static String friendKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isAppearOffline() {
        return appearOffline;
    }

    public static void setAppearOffline(boolean offline) {
        appearOffline = offline;
        save();
        // Keep an already-open ShareInv WebSocket in sync with the privacy toggle.
        me.napim.gomod.shareinv.ShareInvManager.resendHelloIfConnected();
    }

    /** Marks the start of the local player's online session (idempotent until reset). */
    public static void ensureLocalOnlineSession() {
        if (localOnlineSinceMs <= 0L) {
            localOnlineSinceMs = System.currentTimeMillis();
        }
    }

    public static void resetLocalOnlineSession() {
        localOnlineSinceMs = 0L;
    }

    public static long getLocalOnlineSinceMs() {
        return localOnlineSinceMs;
    }

    public static final class FriendPresence {
        public final String server;
        public final long ts;

        public FriendPresence(String server, long ts) {
            this.server = server == null ? "" : server;
            this.ts = ts;
        }
    }

    public static void recordFriendPresence(String name, long ts, String server) {
        String key = friendKey(name);
        if (key.isEmpty() || ts <= 0L) {
            return;
        }
        String srv = server == null ? "" : server.trim();
        FriendPresence prev = friendPresence.get(key);
        if (prev == null || ts >= prev.ts) {
            friendPresence.put(key, new FriendPresence(srv, ts));
            save();
        }
    }

    public static FriendPresence getFriendPresence(String name) {
        return friendPresence.get(friendKey(name));
    }

    private static void loadFriendPresence(JsonObject root) {
        friendPresence.clear();
        if (!root.has("friendPresence") || !root.get("friendPresence").isJsonObject()) {
            return;
        }
        JsonObject o = root.getAsJsonObject("friendPresence");
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject p = e.getValue().getAsJsonObject();
            long ts = p.has("ts") ? p.get("ts").getAsLong() : 0L;
            String server = jsonStr(p, "server");
            if (ts > 0L) {
                friendPresence.put(e.getKey().toLowerCase(Locale.ROOT), new FriendPresence(server, ts));
            }
        }
    }

    private static JsonObject friendPresenceToJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, FriendPresence> e : friendPresence.entrySet()) {
            FriendPresence p = e.getValue();
            if (p == null) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("ts", p.ts);
            if (p.server != null && !p.server.isEmpty()) {
                row.addProperty("server", p.server);
            }
            o.add(e.getKey(), row);
        }
        return o;
    }

    public static void recordLastSeen(String name, long ts) {
        String key = friendKey(name);
        if (key.isEmpty() || ts <= 0L) {
            return;
        }
        Long prev = lastSeenMs.get(key);
        if (prev == null || ts >= prev) {
            lastSeenMs.put(key, ts);
            save();
        }
    }

    public static long getLastSeenMs(String name) {
        Long v = lastSeenMs.get(friendKey(name));
        return v == null ? 0L : v;
    }

    public static void markRead(String name, long ts) {
        String key = friendKey(name);
        if (key.isEmpty()) {
            return;
        }
        long use = ts > 0L ? ts : System.currentTimeMillis();
        lastReadMs.put(key, use);
        save();
    }

    public static long getLastReadMs(String name) {
        Long v = lastReadMs.get(friendKey(name));
        return v == null ? 0L : v;
    }

    public static int getUnreadCount(String name) {
        String key = friendKey(name);
        if (key.isEmpty()) {
            return 0;
        }
        long read = getLastReadMs(name);
        List<GomodSocialChat.Message> list = threads.get(key);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (GomodSocialChat.Message m : list) {
            if (isUnreadIncomingMessage(m, read)) {
                n++;
            }
        }
        return n;
    }

    public static int getSessionUnreadCount(String name) {
        String key = friendKey(name);
        if (key.isEmpty()) {
            return 0;
        }
        long read = getLastReadMs(name);
        long sessionStart = localOnlineSinceMs;
        long cutoff = sessionStart > 0L ? Math.max(read, sessionStart) : read;
        List<GomodSocialChat.Message> list = threads.get(key);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (GomodSocialChat.Message m : list) {
            if (isUnreadIncomingMessage(m, cutoff)) {
                n++;
            }
        }
        return n;
    }

    /** Outgoing messages (including later relay echoes) must never create an unread badge. */
    private static boolean isUnreadIncomingMessage(GomodSocialChat.Message message, long cutoff) {
        if (message == null || message.ts <= cutoff) {
            return false;
        }
        String sender = message.from == null ? "" : message.from.trim();
        String localPlayer = GomodSocialPresence.sessionPlayerName(Minecraft.getMinecraft());
        return localPlayer.isEmpty() || !sender.equalsIgnoreCase(localPlayer);
    }

    public static List<String> getUnreadThreadNames() {
        ArrayList<String> out = new ArrayList<String>();
        for (String key : threads.keySet()) {
            if (key != null && !key.trim().isEmpty() && getUnreadCount(key) > 0) {
                out.add(key);
            }
        }
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int ua = getUnreadCount(a);
                int ub = getUnreadCount(b);
                if (ua != ub) {
                    return ub - ua;
                }
                long ta = latestMessageTs(a);
                long tb = latestMessageTs(b);
                if (ta != tb) {
                    return ta > tb ? -1 : 1;
                }
                return a.compareToIgnoreCase(b);
            }
        });
        return out;
    }

    /** Direct-message peers with saved history, most recently active first. */
    public static List<String> getDirectConversationNames() {
        ArrayList<String> out = new ArrayList<String>();
        for (String key : threads.keySet()) {
            if (key == null || key.trim().isEmpty()
                    || GomodSocialPublicChat.THREAD_KEY.equalsIgnoreCase(key)) {
                continue;
            }
            List<GomodSocialChat.Message> list = threads.get(key);
            if (list != null && !list.isEmpty()) {
                out.add(key);
            }
        }
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                long ta = latestMessageTs(a);
                long tb = latestMessageTs(b);
                if (ta != tb) {
                    return ta > tb ? -1 : 1;
                }
                return a.compareToIgnoreCase(b);
            }
        });
        return out;
    }

    public static List<GomodSocialChat.Message> getThread(String name) {
        String key = friendKey(name);
        List<GomodSocialChat.Message> list = threads.get(key);
        if (list == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<GomodSocialChat.Message>(list));
    }

    public static synchronized void appendMessage(String peerName, GomodSocialChat.Message msg) {
        String key = friendKey(peerName);
        if (key.isEmpty() || msg == null) {
            return;
        }
        List<GomodSocialChat.Message> list = threads.get(key);
        if (list == null) {
            list = Collections.synchronizedList(new ArrayList<GomodSocialChat.Message>());
            threads.put(key, list);
        }
        list.add(msg);
        while (list.size() > MAX_MESSAGES_PER_THREAD) {
            list.remove(0);
        }
        save();
    }

    public static synchronized void appendPendingOutgoingMessage(String peerName, GomodSocialChat.Message msg) {
        appendMessage(peerName, msg);
        String peer = friendKey(peerName);
        if (peer.isEmpty() || msg == null) return;
        String key = loadedForAccount + "|" + peer;
        List<GomodSocialChat.Message> list = pendingOutgoing.get(key);
        if (list == null) {
            list = new ArrayList<GomodSocialChat.Message>();
            pendingOutgoing.put(key, list);
        }
        list.add(msg);
    }

    public static synchronized void replaceDirectThreadsFromService(JsonArray dmThreads) {
        for (String key : new ArrayList<String>(threads.keySet())) {
            if (!GomodSocialPublicChat.THREAD_KEY.equalsIgnoreCase(key)) {
                threads.remove(key);
            }
        }
        if (dmThreads != null) {
            for (int i = 0; i < dmThreads.size(); i++) {
                if (!dmThreads.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject thread = dmThreads.get(i).getAsJsonObject();
                String peer = jsonStr(thread, "peer");
                String key = friendKey(peer);
                if (key.isEmpty() || !thread.has("messages") || !thread.get("messages").isJsonArray()) {
                    continue;
                }
                List<GomodSocialChat.Message> list = Collections.synchronizedList(new ArrayList<GomodSocialChat.Message>());
                JsonArray messages = thread.getAsJsonArray("messages");
                int start = Math.max(0, messages.size() - MAX_MESSAGES_PER_THREAD);
                for (int j = start; j < messages.size(); j++) {
                    if (!messages.get(j).isJsonObject()) {
                        continue;
                    }
                    JsonObject m = messages.get(j).getAsJsonObject();
                    long ts = m.has("ts") && !m.get("ts").isJsonNull() ? m.get("ts").getAsLong() : 0L;
                    String from = jsonStr(m, "from");
                    String text = jsonStr(m, "text");
                    String fitAttachment=LunarFitSharing.attachment(text);
                    String emoteAttachment=GomodEmoteSharing.attachment(text);
                    text = GomodEmoteSharing.messageText(from,LunarFitSharing.messageText(from, text, ts),ts);
                    String kind = jsonStr(m, "kind");
                    String capeLabel = jsonStr(m, "capeLabel");
                    String capeImageBase64 = jsonStr(m, "capeImageBase64");
                    boolean automatic = m.has("automatic") && !m.get("automatic").isJsonNull() && m.get("automatic").getAsBoolean();
                    if (kind == null || kind.trim().isEmpty()) {
                        kind = "text";
                    }
                    if (isCapeAcceptanceReceipt(kind, text, capeLabel)) {
                        kind = "cape_accept_receipt";
                    }
                    if(!fitAttachment.isEmpty())kind="lunar_fit";
                    if(!emoteAttachment.isEmpty())kind="lunar_emote";
                    if (ts > 0L && (!from.isEmpty() || !text.isEmpty())) {
                        list.add(new GomodSocialChat.Message(ts, from, text, kind, "",
                                capeLabel, capeImageBase64, automatic,emoteAttachment.isEmpty()?fitAttachment:emoteAttachment));
                    }
                }
                if (!list.isEmpty()) {
                    List<GomodSocialChat.Message> existing = threads.get(key);
                    if (existing != null && !existing.isEmpty()) {
                        list = mergeMessages(existing, list);
                    }
                    threads.put(key, list);
                    String displayPeer = ConfigManager.getGomodFriendDisplayName(peer);
                    if (displayPeer != null && !displayPeer.trim().isEmpty()) {
                        threads.put(friendKey(displayPeer), list);
                    }
                }
            }
        }
        mergePendingOutgoingLocked();
        save();
    }

    private static boolean isCapeAcceptanceReceipt(String kind, String text, String capeLabel) {
        if ("cape_accept_receipt".equalsIgnoreCase(kind)) {
            return true;
        }
        if (capeLabel == null || capeLabel.trim().isEmpty()) {
            return false;
        }
        String plain = text == null ? "" : text.replaceAll("(?i)\\u00a7[0-9A-FK-OR]", "").trim();
        return plain.startsWith("Accepted your cape:") || plain.startsWith("Auto-accepted your cape:");
    }

    private static List<GomodSocialChat.Message> mergeMessages(List<GomodSocialChat.Message> first,
                                                                List<GomodSocialChat.Message> second) {
        List<GomodSocialChat.Message> merged = new ArrayList<GomodSocialChat.Message>();
        if (first != null) merged.addAll(first);
        if (second != null) {
            for (GomodSocialChat.Message candidate : second) {
                boolean duplicate = false;
                for (int index=0;index<merged.size();index++) {
                    GomodSocialChat.Message existing=merged.get(index);
                    boolean sameFit=existing!=null&&candidate!=null&&same(existing.from,candidate.from)
                            &&(!candidate.fitAttachment.isEmpty()&&same(existing.fitAttachment,candidate.fitAttachment)||!candidate.emoteAttachment.isEmpty()&&same(existing.emoteAttachment,candidate.emoteAttachment));
                    if (sameFit || existing != null && candidate != null && existing.ts == candidate.ts
                            && same(existing.from, candidate.from) && same(existing.text, candidate.text)
                            && (same(existing.kind, candidate.kind)||existing.fitAttachment.isEmpty()&&!candidate.fitAttachment.isEmpty())) {
                        if(!candidate.fitAttachment.isEmpty()||!candidate.emoteAttachment.isEmpty())merged.set(index,candidate);
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) merged.add(candidate);
            }
        }
        Collections.sort(merged, new Comparator<GomodSocialChat.Message>() {
            @Override
            public int compare(GomodSocialChat.Message a, GomodSocialChat.Message b) {
                long at = a == null ? 0L : a.ts;
                long bt = b == null ? 0L : b.ts;
                return at == bt ? 0 : (at < bt ? -1 : 1);
            }
        });
        while (merged.size() > MAX_MESSAGES_PER_THREAD) merged.remove(0);
        return Collections.synchronizedList(merged);
    }

    private static void mergePendingOutgoingLocked() {
        long now = System.currentTimeMillis();
        String prefix = loadedForAccount + "|";
        for (Map.Entry<String, List<GomodSocialChat.Message>> entry
                : new ArrayList<Map.Entry<String, List<GomodSocialChat.Message>>>(pendingOutgoing.entrySet())) {
            if (!entry.getKey().startsWith(prefix)) continue;
            String peer = entry.getKey().substring(prefix.length());
            List<GomodSocialChat.Message> relayMessages = threads.get(peer);
            if (relayMessages == null) {
                relayMessages = Collections.synchronizedList(new ArrayList<GomodSocialChat.Message>());
                threads.put(peer, relayMessages);
            }
            boolean[] matched = new boolean[relayMessages.size()];
            List<GomodSocialChat.Message> remaining = new ArrayList<GomodSocialChat.Message>();
            for (GomodSocialChat.Message pending : entry.getValue()) {
                if (pending == null || now - pending.ts > PENDING_OUTGOING_TTL_MS) continue;
                int match = findEquivalentMessage(relayMessages, matched, pending);
                if (match >= 0) {
                    matched[match] = true;
                } else {
                    relayMessages.add(pending);
                    remaining.add(pending);
                }
            }
            if (remaining.isEmpty()) pendingOutgoing.remove(entry.getKey());
            else pendingOutgoing.put(entry.getKey(), remaining);
        }
    }

    private static int findEquivalentMessage(List<GomodSocialChat.Message> messages, boolean[] matched,
                                             GomodSocialChat.Message wanted) {
        for (int i = 0; i < messages.size() && i < matched.length; i++) {
            if (matched[i]) continue;
            GomodSocialChat.Message candidate = messages.get(i);
            if (candidate != null && same(candidate.from, wanted.from) && same(candidate.text, wanted.text)
                    && same(candidate.kind, wanted.kind)
                    && Math.abs(candidate.ts - wanted.ts) <= PENDING_OUTGOING_TTL_MS) return i;
        }
        return -1;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static String previewLine(String name) {
        List<GomodSocialChat.Message> list = threads.get(friendKey(name));
        if (list == null || list.isEmpty()) {
            return "";
        }
        GomodSocialChat.Message last = list.get(list.size() - 1);
        if (last == null) {
            return "";
        }
        if ("screenshot".equalsIgnoreCase(last.kind)) {
            return "[Screenshot]";
        }
        if ("cape_accept_receipt".equalsIgnoreCase(last.kind)) {
            return "[Cape accepted]";
        }
        String t = last.text == null ? "" : last.text.trim();
        if (t.length() > 42) {
            t = t.substring(0, 39) + "...";
        }
        return t;
    }

    public static long latestMessageTs(String name) {
        List<GomodSocialChat.Message> list = threads.get(friendKey(name));
        if (list == null || list.isEmpty()) {
            return 0L;
        }
        GomodSocialChat.Message last = list.get(list.size() - 1);
        return last == null ? 0L : last.ts;
    }

    /** Friends sorted for chat sidebar: unread first, then recent activity. */
    public static List<String> sortFriendsForChat(List<String> friends) {
        List<String> copy = new ArrayList<String>(friends);
        Collections.sort(copy, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int ua = getUnreadCount(a);
                int ub = getUnreadCount(b);
                if (ua != ub) {
                    return ub - ua;
                }
                long ta = Math.max(latestMessageTs(a), getLastSeenMs(a));
                long tb = Math.max(latestMessageTs(b), getLastSeenMs(b));
                if (ta != tb) {
                    return ta > tb ? -1 : 1;
                }
                return a.compareToIgnoreCase(b);
            }
        });
        return copy;
    }
}
