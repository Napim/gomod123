package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import me.napim.gomod.util.GomodPaths;

import java.io.File;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-cape like state for the standalone cape picker (keyed by catalog {@code optionKey}).
 */
public final class PlayerCapeRatings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> likedKeys = new HashSet<String>();
    private static boolean loaded;

    private PlayerCapeRatings() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        reloadLocked();
    }

    public static synchronized boolean isLiked(String optionKey) {
        if (optionKey == null || optionKey.isEmpty()) {
            return false;
        }
        ensureLoaded();
        return likedKeys.contains(optionKey);
    }

    /** Toggles liked state for this cape option key. */
    public static synchronized boolean toggleLiked(String optionKey) {
        if (optionKey == null || optionKey.isEmpty()) {
            return false;
        }
        ensureLoaded();
        boolean nowLiked;
        if (likedKeys.contains(optionKey)) {
            likedKeys.remove(optionKey);
            nowLiked = false;
        } else {
            likedKeys.add(optionKey);
            nowLiked = true;
        }
        persistLocked();
        return nowLiked;
    }

    public static synchronized boolean canRate(LocalDisguise.CapeOptionInfo opt) {
        if (opt == null || opt.createEntry) {
            return false;
        }
        String key = opt.optionKey;
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (PlayerCustomCapes.CREATE_OPTION_KEY.equals(key)) {
            return false;
        }
        return !PlayerStandaloneCape.isNonCapeCatalogKey(key);
    }

    private static void reloadLocked() {
        likedKeys.clear();
        File file = ratingsFile();
        if (!file.isFile()) {
            loaded = true;
            return;
        }
        try (FileReader fr = new FileReader(file)) {
            JsonObject root = new JsonParser().parse(fr).getAsJsonObject();
            if (root != null && root.has("liked") && root.get("liked").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("liked");
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i).isJsonPrimitive()) {
                        String key = arr.get(i).getAsString();
                        if (key != null && !key.isEmpty()) {
                            likedKeys.add(key);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        loaded = true;
    }

    private static void persistLocked() {
        File file = ratingsFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String key : likedKeys) {
            arr.add(new JsonPrimitive(key));
        }
        root.add("liked", arr);
        try (OutputStreamWriter w = new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        } catch (Throwable ignored) {
        }
    }

    private static File ratingsFile() {
        return new File(GomodPaths.getGomod123Dir(), "cape_ratings.json");
    }
}
