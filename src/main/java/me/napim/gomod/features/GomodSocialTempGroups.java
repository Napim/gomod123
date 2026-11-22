package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import me.napim.gomod.features.GomodSocialChat.Message;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Temporary group chats: deleted after {@link #INACTIVITY_MS} with no messages.
 */
public final class GomodSocialTempGroups {
    public static final long INACTIVITY_MS = TimeUnit.HOURS.toMillis(24);

    private static final Gson GSON = new Gson();
    private static final JsonParser PARSER = new JsonParser();
    private static final int MAX_MESSAGES = 200;

    private static final ConcurrentHashMap<String, Group> groups = new ConcurrentHashMap<String, Group>();
    private static String loadedForAccount = "";

    private GomodSocialTempGroups() {}

    public static void ensureAccount(Minecraft mc) {
        GomodSocialAccount.refresh(mc);
        String key = GomodSocialAccount.currentKey();
        if (key.equals(loadedForAccount)) {
            return;
        }
        loadedForAccount = key;
        groups.clear();
        load();
    }

    public static final class Group {
        public final String id;
        public String name;
        /** Player who created the group (used for dock avatar). */
        public String creator;
        public final List<String> members;
        public long lastActivityMs;
        public final List<Message> messages;

        Group(String id, String name, List<String> members, String creator) {
            this.id = id;
            this.name = name;
            this.members = members;
            this.creator = creator == null ? "" : creator.trim();
            this.lastActivityMs = System.currentTimeMillis();
            this.messages = new ArrayList<Message>();
        }
    }

    public static void load() {
        purgeExpired();
        File f = storeFile();
        if (!f.isFile()) {
            return;
        }
        try (FileReader fr = new FileReader(f)) {
            JsonObject root = PARSER.parse(fr).getAsJsonObject();
            if (root == null || !root.has("groups") || !root.get("groups").isJsonArray()) {
                return;
            }
            groups.clear();
            JsonArray arr = root.getAsJsonArray("groups");
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                String id = jsonStr(o, "id");
                String name = jsonStr(o, "name");
                if (id.isEmpty() || name.isEmpty()) {
                    continue;
                }
                List<String> members = new ArrayList<String>();
                if (o.has("members") && o.get("members").isJsonArray()) {
                    for (JsonElement m : o.getAsJsonArray("members")) {
                        if (m != null && m.isJsonPrimitive()) {
                            String s = m.getAsString();
                            if (s != null && !s.trim().isEmpty()) {
                                members.add(s.trim());
                            }
                        }
                    }
                }
                String creator = jsonStr(o, "creator");
                Group g = new Group(id, name, members, creator);
                if (g.creator.isEmpty() && !members.isEmpty()) {
                    g.creator = members.get(0);
                }
                g.lastActivityMs = o.has("lastActivityMs") ? o.get("lastActivityMs").getAsLong() : System.currentTimeMillis();
                if (o.has("messages") && o.get("messages").isJsonArray()) {
                    for (JsonElement me : o.getAsJsonArray("messages")) {
                        if (me == null || !me.isJsonObject()) {
                            continue;
                        }
                        JsonObject mo = me.getAsJsonObject();
                        long ts = mo.has("ts") ? mo.get("ts").getAsLong() : 0L;
                        g.messages.add(new Message(ts, jsonStr(mo, "from"), jsonStr(mo, "text"), jsonStr(mo, "kind")));
                    }
                }
                if (System.currentTimeMillis() - g.lastActivityMs <= INACTIVITY_MS) {
                    groups.put(id, g);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        purgeExpired();
        File f = storeFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Group g : groups.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", g.id);
            o.addProperty("name", g.name);
            if (g.creator != null && !g.creator.isEmpty()) {
                o.addProperty("creator", g.creator);
            }
            o.addProperty("lastActivityMs", g.lastActivityMs);
            JsonArray mem = new JsonArray();
            for (String m : g.members) {
                mem.add(new JsonPrimitive(m));
            }
            o.add("members", mem);
            JsonArray msgs = new JsonArray();
            int start = Math.max(0, g.messages.size() - MAX_MESSAGES);
            for (int i = start; i < g.messages.size(); i++) {
                Message msg = g.messages.get(i);
                JsonObject mo = new JsonObject();
                mo.addProperty("ts", msg.ts);
                mo.addProperty("from", msg.from);
                mo.addProperty("text", msg.text);
                mo.addProperty("kind", msg.kind);
                msgs.add(mo);
            }
            o.add("messages", msgs);
            arr.add(o);
        }
        root.add("groups", arr);
        try (FileWriter fw = new FileWriter(f)) {
            GSON.toJson(root, fw);
        } catch (IOException ignored) {
        }
    }

    public static void purgeExpired() {
        long now = System.currentTimeMillis();
        List<String> remove = new ArrayList<String>();
        for (Group g : groups.values()) {
            if (now - g.lastActivityMs > INACTIVITY_MS) {
                remove.add(g.id);
            }
        }
        for (String id : remove) {
            groups.remove(id);
        }
    }

    public static List<Group> sortedGroups() {
        purgeExpired();
        List<Group> list = new ArrayList<Group>(groups.values());
        Collections.sort(list, new Comparator<Group>() {
            @Override
            public int compare(Group a, Group b) {
                return Long.compare(b.lastActivityMs, a.lastActivityMs);
            }
        });
        return list;
    }

    public static Group get(String id) {
        purgeExpired();
        return id == null ? null : groups.get(id);
    }

    public static Group create(String name, List<String> memberNames) {
        purgeExpired();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> members = buildPrivateMemberList(memberNames);
        String creator = selfPlayerName();
        Group g = new Group(id, name.trim(), members, creator == null ? "" : creator);
        groups.put(id, g);
        save();
        for (String member : g.members) {
            if (creator == null || !member.equalsIgnoreCase(creator)) {
                GomodSocialChat.sendTempGroupInvite(g, member);
            }
        }
        return g;
    }

    /** Applies a relay group invite and returns the stored group. */
    public static Group applyInvite(JsonObject o) {
        if (o == null) return null;
        String id = jsonStr(o, "id");
        String name = jsonStr(o, "name");
        if (id.isEmpty() || name.isEmpty()) return null;
        List<String> members = new ArrayList<String>();
        if (o.has("members") && o.get("members").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("members")) {
                if (el != null && el.isJsonPrimitive() && !el.getAsString().trim().isEmpty()) {
                    members.add(el.getAsString().trim());
                }
            }
        }
        String self = selfPlayerName();
        if (self != null && !containsIgnoreCase(members, self)) members.add(self);
        Group g = new Group(id, name, members, jsonStr(o, "creator"));
        g.lastActivityMs = o.has("lastActivityMs") ? o.get("lastActivityMs").getAsLong() : System.currentTimeMillis();
        groups.put(id, g);
        save();
        return g;
    }

    /** Comma-separated friend names from the create-group modal. */
    public static List<String> parseMemberNames(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            if (part != null && !part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /**
     * Private group roster: always includes you; only gomod friends can be added.
     */
    public static List<String> buildPrivateMemberList(List<String> requested) {
        List<String> members = new ArrayList<String>();
        String self = selfPlayerName();
        if (self != null) {
            members.add(self);
        }
        if (requested != null) {
            for (String raw : requested) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String name = raw.trim();
                if (self != null && name.equalsIgnoreCase(self)) {
                    continue;
                }
                if (!ConfigManager.isGomodFriend(name)) {
                    continue;
                }
                String display = ConfigManager.getGomodFriendDisplayName(name);
                if (!containsIgnoreCase(members, display)) {
                    members.add(display);
                }
            }
        }
        return members;
    }

    public static void touchActivity(Group g) {
        if (g == null) {
            return;
        }
        g.lastActivityMs = System.currentTimeMillis();
        save();
    }

    public static void appendMessage(Group g, String from, String text, String kind) {
        if (g == null) {
            return;
        }
        g.messages.add(new Message(System.currentTimeMillis(), from, text, kind));
        while (g.messages.size() > MAX_MESSAGES) {
            g.messages.remove(0);
        }
        touchActivity(g);
    }

    /** Adds gomod friends to an existing temp group; returns how many new members were added. */
    public static int addMembers(Group g, List<String> requested) {
        if (g == null) {
            return 0;
        }
        int before = g.members.size();
        List<String> toAdd = buildPrivateMemberList(requested);
        for (String name : toAdd) {
            if (!containsIgnoreCase(g.members, name)) {
                g.members.add(name);
                GomodSocialChat.sendTempGroupInvite(g, name);
            }
        }
        int added = g.members.size() - before;
        if (added > 0) {
            touchActivity(g);
        }
        return added;
    }

    /** Minecraft username for the group creator avatar (legacy groups: first member). */
    public static String creatorName(Group g) {
        if (g == null) {
            return "MHF_Steve";
        }
        if (g.creator != null && !g.creator.isEmpty()) {
            return g.creator;
        }
        if (g.members != null && !g.members.isEmpty() && g.members.get(0) != null && !g.members.get(0).isEmpty()) {
            return g.members.get(0);
        }
        return g.name == null || g.name.isEmpty() ? "MHF_Steve" : g.name;
    }

    public static int hoursUntilExpiry(Group g) {
        if (g == null) {
            return 0;
        }
        long left = INACTIVITY_MS - (System.currentTimeMillis() - g.lastActivityMs);
        if (left <= 0L) {
            return 0;
        }
        return (int) Math.max(1, TimeUnit.MILLISECONDS.toHours(left));
    }

    public static String expiryHint(Group g) {
        if (g == null) {
            return "";
        }
        return "Deletes in " + hoursUntilExpiry(g) + "h if nobody sends a message";
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String selfPlayerName() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        String name = GomodSocialPresence.sessionPlayerName(mc);
        return name.isEmpty() ? null : name;
    }

    private static File storeFile() {
        return GomodSocialAccount.dataFile("gomodSocialGroups");
    }

    private static String jsonStr(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }
}
