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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent friend forums: boards with threads and posts (not auto-deleted).
 */
public final class GomodSocialForums {
    private static final Gson GSON = new Gson();
    private static final JsonParser PARSER = new JsonParser();

    /** Implicit board for dock threads (users create threads, not separate forums). */
    public static final String DEFAULT_FORUM_ID = "gomod_threads";

    private static final ConcurrentHashMap<String, Forum> forums = new ConcurrentHashMap<String, Forum>();
    private static String loadedForAccount = "";
    private static volatile long lastThreadEventTs;

    public static void ensureAccount(Minecraft mc) {
        GomodSocialAccount.refresh(mc);
        String key = GomodSocialAccount.currentKey();
        if (key.equals(loadedForAccount)) {
            return;
        }
        loadedForAccount = key;
        forums.clear();
        load();
    }

    /** Flattened thread row for the Social dock. */
    public static final class ThreadEntry {
        public final String forumId;
        public final Thread thread;

        public ThreadEntry(String forumId, Thread thread) {
            this.forumId = forumId;
            this.thread = thread;
        }
    }

    private GomodSocialForums() {}

    public static final class Forum {
        public final String id;
        public String title;
        public String description;
        public final List<Thread> threads;

        Forum(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description == null ? "" : description;
            this.threads = new ArrayList<Thread>();
        }

        public int postCount() {
            int n = 0;
            for (Thread t : threads) {
                n += t.posts.size();
            }
            return n;
        }
    }

    public static final class Thread {
        public final String id;
        public String title;
        public String author;
        public long createdMs;
        public final List<Post> posts;

        Thread(String id, String title, String author) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.createdMs = System.currentTimeMillis();
            this.posts = new ArrayList<Post>();
        }
    }

    public static final class Post {
        public final String author;
        public final String body;
        public final long ts;

        public Post(String author, String body, long ts) {
            this.author = author;
            this.body = body;
            this.ts = ts;
        }
    }

    public static void load() {
        File f = storeFile();
        if (!f.isFile()) {
            seedDefaults();
            return;
        }
        try (FileReader fr = new FileReader(f)) {
            JsonObject root = PARSER.parse(fr).getAsJsonObject();
            if (root == null || !root.has("forums") || !root.get("forums").isJsonArray()) {
                seedDefaults();
                return;
            }
            forums.clear();
            for (JsonElement el : root.getAsJsonArray("forums")) {
                Forum forum = parseForum(el);
                if (forum != null) {
                    forums.put(forum.id, forum);
                }
            }
            if (forums.isEmpty()) {
                seedDefaults();
            }
        } catch (Exception e) {
            seedDefaults();
        }
    }

    private static void seedDefaults() {
        forums.clear();
        ensureDefaultForum();
        save();
    }

    public static long getLastThreadEventTs() {
        return lastThreadEventTs;
    }

    /** Next poll requests a full {@code threadsSnapshot} from gomod Service. */
    public static void resetThreadSyncCursor() {
        lastThreadEventTs = 0L;
    }

    public static void noteThreadEventTs(long ts) {
        if (ts > lastThreadEventTs) {
            lastThreadEventTs = ts;
        }
    }

    /** Replace community board from relay snapshot (poll threadsSince=0). */
    public static void applyServiceSnapshot(JsonObject snapshot) {
        if (snapshot == null) {
            return;
        }
        String forumId = snapshot.has("forumId") ? jsonStr(snapshot, "forumId") : DEFAULT_FORUM_ID;
        if (forumId.isEmpty()) {
            forumId = DEFAULT_FORUM_ID;
        }
        Forum board = new Forum(forumId, "Threads", "gomod community threads");
        if (snapshot.has("threads") && snapshot.get("threads").isJsonArray()) {
            for (JsonElement te : snapshot.getAsJsonArray("threads")) {
                Thread t = parseThread(te);
                if (t != null) {
                    board.threads.add(t);
                }
            }
        }
        forums.put(forumId, board);
        save();
    }

    public static void applyThreadEvent(JsonObject ev) {
        applyThreadEvent(ev, true);
    }

    public static void applyThreadEvent(JsonObject ev, boolean notifyNewThread) {
        if (ev == null) {
            return;
        }
        long ts = ev.has("ts") ? ev.get("ts").getAsLong() : System.currentTimeMillis();
        noteThreadEventTs(ts);
        String kind = jsonStr(ev, "kind");
        if ("thread_created".equalsIgnoreCase(kind)) {
            String forumId = jsonStr(ev, "forumId");
            if (forumId.isEmpty()) {
                forumId = DEFAULT_FORUM_ID;
            }
            if (ev.has("thread") && ev.get("thread").isJsonObject()) {
                Thread t = parseThread(ev.get("thread"));
                if (t != null) {
                    Forum f = get(forumId);
                    if (f == null) {
                        f = new Forum(forumId, "Threads", "");
                        forums.put(forumId, f);
                    }
                    if (findThread(forumId, t.id) == null) {
                        f.threads.add(0, t);
                        save();
                        if (notifyNewThread) {
                            IncomingPublicPingOverlay.showThread(t.author, t.title, forumId, t.id);
                        }
                    }
                }
            }
        } else if ("thread_posted".equalsIgnoreCase(kind)) {
            String forumId = jsonStr(ev, "forumId");
            String threadId = jsonStr(ev, "threadId");
            if (ev.has("post") && ev.get("post").isJsonObject()) {
                Post p = parsePost(ev.get("post"));
                if (p != null) {
                    addPostLocal(forumId, threadId, p);
                }
            }
        } else if ("thread_deleted".equalsIgnoreCase(kind)) {
            removeThreadLocal(jsonStr(ev, "forumId"), jsonStr(ev, "threadId"));
        }
    }

    public static void addPostLocal(String forumId, String threadId, Post post) {
        if (post == null) {
            return;
        }
        String fid = forumId == null || forumId.isEmpty() ? DEFAULT_FORUM_ID : forumId;
        Forum f = get(fid);
        if (f == null) {
            return;
        }
        for (Thread t : f.threads) {
            if (t != null && t.id.equals(threadId)) {
                if (!t.posts.isEmpty()) {
                    Post last = t.posts.get(t.posts.size() - 1);
                    if (last.author != null && last.author.equalsIgnoreCase(post.author)
                            && last.body != null && last.body.equals(post.body)
                            && Math.abs(last.ts - post.ts) < 5000L) {
                        return;
                    }
                }
                t.posts.add(post);
                save();
                return;
            }
        }
    }

    public static void removeThreadLocal(String forumId, String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return;
        }
        String fid = forumId == null || forumId.isEmpty() ? DEFAULT_FORUM_ID : forumId;
        Forum f = get(fid);
        if (f == null) {
            return;
        }
        for (int i = 0; i < f.threads.size(); i++) {
            Thread t = f.threads.get(i);
            if (t != null && threadId.equals(t.id)) {
                f.threads.remove(i);
                save();
                return;
            }
        }
    }

    public static Thread addThreadFromService(String forumId, String threadId, String title, String author) {
        String fid = forumId == null || forumId.isEmpty() ? DEFAULT_FORUM_ID : forumId;
        Forum f = get(fid);
        if (f == null) {
            f = ensureDefaultForum();
        }
        if (findThread(fid, threadId) != null) {
            return findThread(fid, threadId).thread;
        }
        String id = threadId == null || threadId.isEmpty()
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 10) : threadId;
        Thread t = new Thread(id, title.trim(), author == null ? "?" : author.trim());
        f.threads.add(0, t);
        save();
        return t;
    }

    public static void save() {
        File f = storeFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Forum forum : forums.values()) {
            arr.add(forumToJson(forum));
        }
        root.add("forums", arr);
        try (FileWriter fw = new FileWriter(f)) {
            GSON.toJson(root, fw);
        } catch (IOException ignored) {
        }
    }

    public static List<Forum> sortedForums() {
        List<Forum> list = new ArrayList<Forum>(forums.values());
        Collections.sort(list, new Comparator<Forum>() {
            @Override
            public int compare(Forum a, Forum b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return list;
    }

    /** Ensures the default thread board exists (used by Social dock). */
    public static Forum ensureDefaultForum() {
        Forum f = forums.get(DEFAULT_FORUM_ID);
        if (f == null) {
            f = new Forum(DEFAULT_FORUM_ID, "Threads", "gomod community threads");
            forums.put(DEFAULT_FORUM_ID, f);
            save();
        }
        return f;
    }

    /** All threads across every board, newest activity first. */
    public static List<ThreadEntry> sortedThreadEntries() {
        ensureDefaultForum();
        List<ThreadEntry> out = new ArrayList<ThreadEntry>();
        for (Forum forum : forums.values()) {
            if (forum == null || forum.threads == null) {
                continue;
            }
            for (Thread t : forum.threads) {
                if (t != null) {
                    out.add(new ThreadEntry(forum.id, t));
                }
            }
        }
        Collections.sort(out, new Comparator<ThreadEntry>() {
            @Override
            public int compare(ThreadEntry a, ThreadEntry b) {
                long ta = lastActivityMs(a.thread);
                long tb = lastActivityMs(b.thread);
                return Long.compare(tb, ta);
            }
        });
        return out;
    }

    private static long lastActivityMs(Thread t) {
        if (t == null) {
            return 0L;
        }
        long last = t.createdMs;
        if (t.posts != null) {
            for (Post p : t.posts) {
                if (p != null && p.ts > last) {
                    last = p.ts;
                }
            }
        }
        return last;
    }

    public static ThreadEntry findThread(String forumId, String threadId) {
        if (forumId == null || threadId == null) {
            return null;
        }
        Forum f = get(forumId);
        if (f == null) {
            return null;
        }
        for (Thread t : f.threads) {
            if (t != null && threadId.equals(t.id)) {
                return new ThreadEntry(forumId, t);
            }
        }
        return null;
    }

    public static Forum get(String id) {
        return id == null ? null : forums.get(id);
    }

    public static Forum create(String title, String description) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Forum f = new Forum(id, title.trim(), description);
        forums.put(id, f);
        save();
        return f;
    }

    public static Thread addThread(String forumId, String title, String author) {
        Forum f = get(forumId);
        if (f == null || title == null || title.trim().isEmpty()) {
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Thread t = new Thread(id, title.trim(), author == null ? "?" : author.trim());
        f.threads.add(0, t);
        save();
        return t;
    }

    public static void addPost(String forumId, String threadId, String author, String body) {
        if (body == null || body.trim().isEmpty()) {
            return;
        }
        addPostLocal(forumId, threadId, new Post(author, body.trim(), System.currentTimeMillis()));
    }

    private static Forum parseForum(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String id = jsonStr(o, "id");
        String title = jsonStr(o, "title");
        if (id.isEmpty() || title.isEmpty()) {
            return null;
        }
        Forum f = new Forum(id, title, jsonStr(o, "description"));
        if (o.has("threads") && o.get("threads").isJsonArray()) {
            for (JsonElement te : o.getAsJsonArray("threads")) {
                Thread t = parseThread(te);
                if (t != null) {
                    f.threads.add(t);
                }
            }
        }
        return f;
    }

    private static Thread parseThread(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String id = jsonStr(o, "id");
        String title = jsonStr(o, "title");
        if (id.isEmpty() || title.isEmpty()) {
            return null;
        }
        Thread t = new Thread(id, title, jsonStr(o, "author"));
        t.createdMs = o.has("createdMs") ? o.get("createdMs").getAsLong() : t.createdMs;
        if (o.has("posts") && o.get("posts").isJsonArray()) {
            for (JsonElement pe : o.getAsJsonArray("posts")) {
                Post p = parsePost(pe);
                if (p != null) {
                    t.posts.add(p);
                }
            }
        }
        return t;
    }

    private static Post parsePost(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        return new Post(jsonStr(o, "author"), jsonStr(o, "body"),
                o.has("ts") ? o.get("ts").getAsLong() : System.currentTimeMillis());
    }

    private static JsonObject forumToJson(Forum f) {
        JsonObject o = new JsonObject();
        o.addProperty("id", f.id);
        o.addProperty("title", f.title);
        o.addProperty("description", f.description);
        JsonArray threads = new JsonArray();
        for (Thread t : f.threads) {
            JsonObject to = new JsonObject();
            to.addProperty("id", t.id);
            to.addProperty("title", t.title);
            to.addProperty("author", t.author);
            to.addProperty("createdMs", t.createdMs);
            JsonArray posts = new JsonArray();
            for (Post p : t.posts) {
                JsonObject po = new JsonObject();
                po.addProperty("author", p.author);
                po.addProperty("body", p.body);
                po.addProperty("ts", p.ts);
                posts.add(po);
            }
            to.add("posts", posts);
            threads.add(to);
        }
        o.add("threads", threads);
        return o;
    }

    private static File storeFile() {
        return GomodSocialAccount.dataFile("gomodSocialForums");
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
