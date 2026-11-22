package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Vec3;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-map named chest routes (ordered block-top positions), {@code chest_routes.json}.
 * v4: multiple paths per map under {@code paths}.
 */
public final class ChestRouteStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** mapKey → (pathName → checkpoints) */
    private static final Map<String, Map<String, List<Vec3>>> ROUTES = new LinkedHashMap<>();
    /** mapKey → preferred default path for single-load actions. */
    private static final Map<String, String> MAIN_PATH = new LinkedHashMap<>();

    private ChestRouteStorage() {
    }

    private static File getRoutesFile() {
        return new File(ConfigManager.getGomodDataDirectory(), "chest_routes.json");
    }

    public static void load() {
        ROUTES.clear();
        MAIN_PATH.clear();
        File f = getRoutesFile();
        if (!f.isFile()) {
            return;
        }
        try (FileReader r = new FileReader(f)) {
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            if (!root.has("routes")) {
                return;
            }
            JsonObject routes = root.getAsJsonObject("routes");
            for (Map.Entry<String, JsonElement> e : routes.entrySet()) {
                String key = e.getKey();
                if (key == null || key.isEmpty()) {
                    continue;
                }
                JsonElement val = e.getValue();
                Map<String, List<Vec3>> paths = readMapEntry(val);
                if (paths != null && !paths.isEmpty()) {
                    ROUTES.put(key, paths);
                    String main = readMainPath(val, paths);
                    if (main != null) {
                        MAIN_PATH.put(key, main);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[gomod] ChestRouteStorage.load: " + t.getMessage());
        }
    }

    private static String readMainPath(JsonElement val, Map<String, List<Vec3>> paths) {
        if (val == null || paths == null || paths.isEmpty() || !val.isJsonObject()) {
            return null;
        }
        JsonObject o = val.getAsJsonObject();
        if (!o.has("mainPath") || o.get("mainPath").isJsonNull()) {
            return null;
        }
        String p = HypixelScoreboardMapResolver.slugify(o.get("mainPath").getAsString());
        if (p.isEmpty() || "unknown".equals(p) || !paths.containsKey(p)) {
            return null;
        }
        return p;
    }

    private static Map<String, List<Vec3>> readMapEntry(JsonElement val) {
        if (val == null) {
            return null;
        }
        Map<String, List<Vec3>> out = new LinkedHashMap<>();
        if (val.isJsonObject()) {
            JsonObject o = val.getAsJsonObject();
            if (o.has("paths") && o.get("paths").isJsonObject()) {
                JsonObject pathsObj = o.getAsJsonObject("paths");
                for (Map.Entry<String, JsonElement> pe : pathsObj.entrySet()) {
                    String pname = HypixelScoreboardMapResolver.slugify(pe.getKey());
                    if (pname.isEmpty() || "unknown".equals(pname)) {
                        continue;
                    }
                    if (!pe.getValue().isJsonArray()) {
                        continue;
                    }
                    List<Vec3> pts = readStandArray(pe.getValue().getAsJsonArray());
                    if (pts != null && pts.size() >= 2) {
                        out.put(pname, pts);
                    }
                }
                return out.isEmpty() ? null : out;
            }
            if (o.has("checkpoints") && o.get("checkpoints").isJsonArray()) {
                List<Vec3> pts = readStandArray(o.getAsJsonArray("checkpoints"));
                if (pts != null && pts.size() >= 2) {
                    out.put("main", pts);
                }
                return out.isEmpty() ? null : out;
            }
            if (o.has("stands") && o.get("stands").isJsonArray()) {
                List<Vec3> pts = readStandArray(o.getAsJsonArray("stands"));
                if (pts != null && pts.size() >= 2) {
                    out.put("main", pts);
                }
                return out.isEmpty() ? null : out;
            }
            return out.isEmpty() ? null : out;
        }
        if (val.isJsonArray()) {
            List<Vec3> pts = migrateLegacyChestBlocks(val.getAsJsonArray());
            if (pts != null && pts.size() >= 2) {
                out.put("main", pts);
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    private static List<Vec3> readStandArray(JsonArray arr) {
        List<Vec3> pts = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonArray()) {
                continue;
            }
            JsonArray t = el.getAsJsonArray();
            if (t.size() < 3) {
                continue;
            }
            double x = t.get(0).getAsDouble();
            double y = t.get(1).getAsDouble();
            double z = t.get(2).getAsDouble();
            pts.add(new Vec3(x, y, z));
        }
        return pts;
    }

    private static List<Vec3> migrateLegacyChestBlocks(JsonArray arr) {
        List<Vec3> centers = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonArray()) {
                continue;
            }
            JsonArray t = el.getAsJsonArray();
            if (t.size() < 3) {
                continue;
            }
            int x = t.get(0).getAsInt();
            int y = t.get(1).getAsInt();
            int z = t.get(2).getAsInt();
            centers.add(new Vec3(x + 0.5, y + 0.125, z + 0.5));
        }
        if (centers.size() < 2) {
            return centers;
        }
        List<Vec3> stands = new ArrayList<>();
        for (int i = 0; i < centers.size(); i++) {
            Vec3 c = centers.get(i);
            Vec3 prev = i > 0 ? centers.get(i - 1) : centers.get(i + 1);
            Vec3 next = i + 1 < centers.size() ? centers.get(i + 1) : centers.get(i - 1);
            double dx = next.xCoord - prev.xCoord;
            double dz = next.zCoord - prev.zCoord;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-4) {
                dx = 0;
                dz = 1.0;
                len = 1.0;
            }
            dx /= len;
            dz /= len;
            stands.add(new Vec3(c.xCoord - dx * 1.1, c.yCoord, c.zCoord - dz * 1.1));
        }
        return stands;
    }

    public static void save() {
        try {
            File dir = ConfigManager.getGomodDataDirectory();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                System.err.println("[gomod] ChestRouteStorage: could not mkdir " + dir);
            }
            JsonObject routes = new JsonObject();
            for (Map.Entry<String, Map<String, List<Vec3>>> e : ROUTES.entrySet()) {
                JsonObject wrap = new JsonObject();
                wrap.addProperty("version", 4);
                String main = MAIN_PATH.get(e.getKey());
                if (main != null && e.getValue().containsKey(main)) {
                    wrap.addProperty("mainPath", main);
                }
                JsonObject pathsObj = new JsonObject();
                for (Map.Entry<String, List<Vec3>> pe : e.getValue().entrySet()) {
                    JsonArray cps = new JsonArray();
                    for (Vec3 p : pe.getValue()) {
                        JsonArray t = new JsonArray();
                        t.add(new JsonPrimitive(p.xCoord));
                        t.add(new JsonPrimitive(p.yCoord));
                        t.add(new JsonPrimitive(p.zCoord));
                        cps.add(t);
                    }
                    pathsObj.add(pe.getKey(), cps);
                }
                wrap.add("paths", pathsObj);
                routes.add(e.getKey(), wrap);
            }
            JsonObject root = new JsonObject();
            root.add("routes", routes);
            try (FileWriter w = new FileWriter(getRoutesFile())) {
                GSON.toJson(root, w);
            }
        } catch (Throwable t) {
            System.err.println("[gomod] ChestRouteStorage.save: " + t.getMessage());
        }
    }

    /** Legacy: save as path {@code main} on the map. */
    public static void putRoute(String mapKey, List<Vec3> stands) {
        putPath(mapKey, "main", stands);
    }

    public static void putPath(String mapKey, String pathName, List<Vec3> stands) {
        if (mapKey == null || mapKey.isEmpty() || pathName == null || pathName.isEmpty()
                || stands == null || stands.size() < 2) {
            return;
        }
        Map<String, List<Vec3>> mapPaths = ROUTES.computeIfAbsent(mapKey, k -> new LinkedHashMap<>());
        mapPaths.put(pathName, new ArrayList<>(stands));
        if (!MAIN_PATH.containsKey(mapKey) || MAIN_PATH.get(mapKey) == null || MAIN_PATH.get(mapKey).isEmpty()) {
            MAIN_PATH.put(mapKey, pathName);
        }
        save();
    }

    public static List<Vec3> getRoute(String mapKey) {
        return getPath(mapKey, "main");
    }

    public static List<Vec3> getPath(String mapKey, String pathName) {
        if (mapKey == null || pathName == null) {
            return null;
        }
        Map<String, List<Vec3>> paths = ROUTES.get(mapKey);
        if (paths == null) {
            return null;
        }
        List<Vec3> list = paths.get(pathName);
        if (list == null) {
            return null;
        }
        List<Vec3> copy = new ArrayList<>(list.size());
        for (Vec3 v : list) {
            copy.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
        }
        return Collections.unmodifiableList(copy);
    }

    public static boolean hasRoute(String mapKey) {
        return mapKey != null && ROUTES.containsKey(mapKey) && !ROUTES.get(mapKey).isEmpty();
    }

    public static boolean hasPath(String mapKey, String pathName) {
        if (mapKey == null || pathName == null) {
            return false;
        }
        Map<String, List<Vec3>> p = ROUTES.get(mapKey);
        return p != null && p.containsKey(pathName);
    }

    /** Removes every saved path for a map key. @return false if the map did not exist */
    public static boolean deleteMap(String mapKey) {
        if (mapKey == null || mapKey.isEmpty()) {
            return false;
        }
        if (!ROUTES.containsKey(mapKey)) {
            return false;
        }
        ROUTES.remove(mapKey);
        MAIN_PATH.remove(mapKey);
        save();
        return true;
    }

    /** Removes one named path from a map. @return false if nothing removed */
    public static boolean deletePath(String mapKey, String pathName) {
        if (mapKey == null || pathName == null || pathName.isEmpty()) {
            return false;
        }
        Map<String, List<Vec3>> paths = ROUTES.get(mapKey);
        if (paths == null || !paths.containsKey(pathName)) {
            return false;
        }
        paths.remove(pathName);
        if (pathName.equals(MAIN_PATH.get(mapKey))) {
            String next = paths.isEmpty() ? null : paths.keySet().iterator().next();
            if (next == null) {
                MAIN_PATH.remove(mapKey);
            } else {
                MAIN_PATH.put(mapKey, next);
            }
        }
        if (paths.isEmpty()) {
            ROUTES.remove(mapKey);
            MAIN_PATH.remove(mapKey);
        }
        save();
        return true;
    }

    public static List<String> listMapKeys() {
        return new ArrayList<>(ROUTES.keySet());
    }

    /** Path names in insertion order (stable colors: first saved path = palette index 0 / pink). */
    public static List<String> listPathNames(String mapKey) {
        if (mapKey == null) {
            return Collections.emptyList();
        }
        Map<String, List<Vec3>> p = ROUTES.get(mapKey);
        if (p == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(p.keySet());
    }

    public static String getMainPath(String mapKey) {
        if (mapKey == null || mapKey.isEmpty()) {
            return null;
        }
        String main = MAIN_PATH.get(mapKey);
        if (main == null || main.isEmpty()) {
            return null;
        }
        Map<String, List<Vec3>> p = ROUTES.get(mapKey);
        if (p == null || !p.containsKey(main)) {
            return null;
        }
        return main;
    }

    public static String getEffectiveMainPath(String mapKey) {
        if (mapKey == null || mapKey.isEmpty()) {
            return null;
        }
        String main = getMainPath(mapKey);
        if (main != null) {
            return main;
        }
        List<String> names = listPathNames(mapKey);
        return names.isEmpty() ? null : names.get(0);
    }

    public static boolean setMainPath(String mapKey, String pathName) {
        if (mapKey == null || pathName == null) {
            return false;
        }
        Map<String, List<Vec3>> p = ROUTES.get(mapKey);
        if (p == null || !p.containsKey(pathName)) {
            return false;
        }
        MAIN_PATH.put(mapKey, pathName);
        save();
        return true;
    }
}
