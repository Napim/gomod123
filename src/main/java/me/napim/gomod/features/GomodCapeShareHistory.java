package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.util.GomodPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;

/** Local history of gomod Social cape shares so pending/deleted capes can be restored. */
public final class GomodCapeShareHistory {
    public static final String SENT = "sent";
    public static final String RECEIVED = "received";
    private static final int MAX_ITEMS = 80;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Entry> entriesById = new LinkedHashMap<String, Entry>();
    private static boolean loaded;
    /** Absolute path of the account-specific history currently loaded in memory. */
    private static String loadedFilePath = "";

    private GomodCapeShareHistory() {
    }

    public static final class Entry {
        public final String id;
        public final String peer;
        public final String label;
        public final String imageBase64;
        public final String direction;
        public final String pendingId;
        public final boolean accepted;
        public final String savedCapeId;
        public final long ts;

        private Entry(String id, String peer, String label, String imageBase64, String direction,
                      String pendingId, boolean accepted, String savedCapeId, long ts) {
            this.id = id == null ? "" : id;
            this.peer = peer == null ? "" : peer;
            this.label = cleanLabel(label);
            this.imageBase64 = imageBase64 == null ? "" : imageBase64;
            this.direction = direction == null ? RECEIVED : direction;
            this.pendingId = pendingId == null ? "" : pendingId;
            this.accepted = accepted;
            this.savedCapeId = savedCapeId == null ? "" : savedCapeId;
            this.ts = ts;
        }
    }

    public static synchronized void recordSent(String peer, String label, String imageBase64) {
        put(new Entry(newId(), clean(peer), cleanLabel(label), clean(imageBase64), SENT,
                "", true, "", System.currentTimeMillis()));
    }

    public static synchronized void recordReceived(String pendingId, String peer, String label, String imageBase64,
                                                   boolean accepted, String savedCapeId) {
        Entry existing = findByPendingId(pendingId);
        String id = existing != null ? existing.id : newId();
        put(new Entry(id, clean(peer), cleanLabel(label), clean(imageBase64), RECEIVED,
                clean(pendingId), accepted, clean(savedCapeId), System.currentTimeMillis()));
    }

    public static synchronized void markAccepted(String pendingId, String savedCapeId) {
        Entry existing = findByPendingId(pendingId);
        if (existing == null) {
            return;
        }
        put(new Entry(existing.id, existing.peer, existing.label, existing.imageBase64, existing.direction,
                existing.pendingId, true, clean(savedCapeId), System.currentTimeMillis()));
    }

    public static synchronized Entry acceptPending(String pendingId) {
        Entry existing = findByPendingId(pendingId);
        if (existing == null || !RECEIVED.equals(existing.direction) || existing.accepted) {
            return null;
        }
        String savedId = PlayerCustomCapes.importSharedFromFriend(existing.peer, existing.label, existing.imageBase64);
        if (savedId == null) {
            return null;
        }
        Entry accepted = new Entry(existing.id, existing.peer, existing.label, existing.imageBase64, existing.direction,
                existing.pendingId, true, savedId, System.currentTimeMillis());
        put(accepted);
        return accepted;
    }

    public static synchronized boolean deleteEntry(String id) {
        ensureLoaded();
        String key = clean(id);
        if (key.isEmpty()) {
            return false;
        }
        boolean ok = entriesById.remove(key) != null;
        if (ok) {
            persistLocked();
        }
        return ok;
    }

    public static synchronized List<Entry> forPeer(String peer) {
        ensureLoaded();
        String p = clean(peer);
        ArrayList<Entry> out = new ArrayList<Entry>();
        for (Entry e : entriesById.values()) {
            if (e != null && e.peer.equalsIgnoreCase(p)) {
                out.add(e);
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.ts, a.ts);
            }
        });
        return out;
    }

    public static synchronized List<Entry> all() {
        ensureLoaded();
        ArrayList<Entry> out = new ArrayList<Entry>(entriesById.values());
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.ts, a.ts);
            }
        });
        return out;
    }

    public static synchronized String downloadToCapeGui(String id) {
        ensureLoaded();
        Entry e = entriesById.get(id);
        if (e == null || e.imageBase64.isEmpty()) {
            return null;
        }
        String sharedFrom = RECEIVED.equals(e.direction) ? e.peer : "";
        String savedId = PlayerCustomCapes.importFromBase64(e.label, e.imageBase64, sharedFrom);
        if (savedId != null) {
            put(new Entry(e.id, e.peer, e.label, e.imageBase64, e.direction, e.pendingId,
                    true, savedId, System.currentTimeMillis()));
        }
        return savedId;
    }

    public static synchronized boolean needsRedownload(Entry e) {
        if (e == null || !RECEIVED.equals(e.direction) || !e.accepted) {
            return false;
        }
        if (e.savedCapeId == null || e.savedCapeId.isEmpty()) {
            return true;
        }
        if (PlayerCustomCapes.getById(e.savedCapeId) == null) {
            return true;
        }
        BufferedImage saved = PlayerCustomCapes.loadImageForId(e.savedCapeId);
        BufferedImage original = decodeSharedImage(e.imageBase64);
        if (saved == null || original == null) {
            return true;
        }
        if (saved.getWidth() != original.getWidth() || saved.getHeight() != original.getHeight()) {
            return true;
        }
        for (int y = 0; y < saved.getHeight(); y++) {
            for (int x = 0; x < saved.getWidth(); x++) {
                if (saved.getRGB(x, y) != original.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void put(Entry entry) {
        ensureLoaded();
        if (entry == null || loadedFilePath.isEmpty()) {
            return;
        }
        entriesById.put(entry.id, entry);
        while (entriesById.size() > MAX_ITEMS) {
            String first = entriesById.keySet().iterator().next();
            entriesById.remove(first);
        }
        persistLocked();
    }

    private static Entry findByPendingId(String pendingId) {
        ensureLoaded();
        String p = clean(pendingId);
        if (p.isEmpty()) {
            return null;
        }
        for (Entry e : entriesById.values()) {
            if (e != null && p.equals(e.pendingId)) {
                return e;
            }
        }
        return null;
    }

    private static void ensureLoaded() {
        File file = historyFile();
        String filePath = file == null ? "" : file.getAbsolutePath();
        if (loaded && filePath.equals(loadedFilePath)) {
            return;
        }
        entriesById.clear();
        loaded = true;
        loadedFilePath = filePath;
        if (file == null) {
            return;
        }
        quarantineLegacyHistory();
        if (!file.isFile()) {
            return;
        }
        try (FileReader fr = new FileReader(file)) {
            JsonObject root = new JsonParser().parse(fr).getAsJsonObject();
            JsonArray arr = root != null && root.has("items") && root.get("items").isJsonArray()
                    ? root.getAsJsonArray("items") : new JsonArray();
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject o = arr.get(i).getAsJsonObject();
                Entry e = new Entry(read(o, "id"), read(o, "peer"), read(o, "label"), read(o, "imageBase64"),
                        read(o, "direction"), read(o, "pendingId"),
                        o.has("accepted") && o.get("accepted").getAsBoolean(),
                        read(o, "savedCapeId"),
                        o.has("ts") ? o.get("ts").getAsLong() : 0L);
                if (!e.id.isEmpty()) {
                    entriesById.put(e.id, e);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persistLocked() {
        File file = historyFile();
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Entry e : entriesById.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("peer", e.peer);
            o.addProperty("label", e.label);
            o.addProperty("imageBase64", e.imageBase64);
            o.addProperty("direction", e.direction);
            o.addProperty("pendingId", e.pendingId);
            o.addProperty("accepted", e.accepted);
            o.addProperty("savedCapeId", e.savedCapeId);
            o.addProperty("ts", e.ts);
            arr.add(o);
        }
        root.add("items", arr);
        try (OutputStreamWriter w = new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        } catch (Throwable ignored) {
        }
    }

    private static File historyFile() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            GomodSocialAccount.refresh(mc);
            String accountKey = GomodSocialAccount.currentKey();
            if (accountKey == null || accountKey.trim().isEmpty() || "unknown".equalsIgnoreCase(accountKey.trim())) {
                return null;
            }
            // GomodSocialAccount sanitizes the Social ID for a safe filename, producing
            // gomod123/cape_share_history_<socialId>.json.
            return GomodSocialAccount.dataFile("cape_share_history");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The old implementation used one shared cape_share_history.json. Keep it recoverable,
     * but never load it into any account's history because it has no owner/account metadata.
     */
    private static void quarantineLegacyHistory() {
        File legacy = new File(GomodPaths.getGomod123Dir(), "cape_share_history.json");
        if (!legacy.isFile()) {
            return;
        }
        File parent = legacy.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return;
        }
        File target = new File(parent, "cape_share_history.legacy-quarantine.json");
        if (target.exists()) {
            target = new File(parent, "cape_share_history.legacy-quarantine-"
                    + System.currentTimeMillis() + ".json");
        }
        try {
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // If the move is unavailable, the legacy file is still intentionally ignored.
        }
    }

    private static String read(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static String cleanLabel(String value) {
        String label = clean(value);
        label = stripFormatting(label);
        return label.isEmpty() ? "Shared cape" : label;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String stripFormatting(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String out = value;
        out = out.replaceAll("(?i)(?:\\u00C2)?\\u00A7[0-9A-FK-OR]", "");
        out = out.replaceAll("\\p{Cntrl}", "");
        out = out.replace('\uFFFD', ' ').trim();
        while (out.contains("  ")) {
            out = out.replace("  ", " ");
        }
        return out.trim();
    }

    private static BufferedImage decodeSharedImage(String imageBase64) {
        if (imageBase64 == null || imageBase64.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(imageBase64.trim());
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            return img == null ? null : PlayerCustomCapes.normalizeCapeImage(img);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
