package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Field;

import javax.imageio.ImageIO;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * User-created cape designs stored under {@code gomod123/custom_capes/}.
 */
public final class PlayerCustomCapes {

    public static final String CREATE_OPTION_KEY = "gomod:custom:__create__";
    public static final String KEY_PREFIX = "gomod:custom:";
    public static final String LAYOUT_OPTIFINE = "optifine";

    private static final int CAPE_W = 64;
    private static final int CAPE_H = 32;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, SavedCape> savedById = new LinkedHashMap<String, SavedCape>();
    /** Exact GPU texture objects installed by this class, used to distinguish them from Minecraft's purple fallback. */
    private static final Map<String, DynamicTexture> registeredTextureById = new LinkedHashMap<String, DynamicTexture>();
    private static boolean loaded;

    private PlayerCustomCapes() {
    }

    public static final class SavedCape {
        public final String id;
        public final String label;
        public final ResourceLocation texture;
        public final String sharedFrom;
        public final String layout;
        /** True when the layout was selected by the current editor/import path, rather than legacy auto-detection. */
        public final boolean layoutExplicit;

        SavedCape(String id, String label, ResourceLocation texture) {
            this(id, label, texture, "", "", false);
        }

        SavedCape(String id, String label, ResourceLocation texture, String sharedFrom) {
            this(id, label, texture, sharedFrom, "", false);
        }

        SavedCape(String id, String label, ResourceLocation texture, String sharedFrom, String layout) {
            this(id, label, texture, sharedFrom, layout, false);
        }

        SavedCape(String id, String label, ResourceLocation texture, String sharedFrom, String layout,
                  boolean layoutExplicit) {
            this.id = id;
            this.label = label;
            this.texture = texture;
            this.sharedFrom = sharedFrom == null ? "" : sharedFrom;
            this.layout = sanitizeLayout(layout);
            this.layoutExplicit = layoutExplicit;
        }

        public String optionKey() {
            return KEY_PREFIX + id;
        }

        public boolean isOptifineLayout() {
            if (!LAYOUT_OPTIFINE.equals(layout)) {
                return false;
            }
            if (layoutExplicit) {
                return true;
            }
            // Older builds marked ordinary gomod capes as OptiFine solely because their visible pixels happened to
            // fit in 46x22. Preserve only legacy entries whose name/provenance genuinely identifies OptiFine.
            String hint = (label + " " + sharedFrom).toLowerCase(Locale.ROOT);
            return hint.contains("optifine");
        }
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            ensureTexturesRegisteredLocked();
            return;
        }
        reloadLocked();
    }

    public static synchronized void reload() {
        reloadLocked();
        PlayerStandaloneCape.invalidateCatalog();
    }

    public static synchronized List<SavedCape> listSaved() {
        ensureLoaded();
        return new ArrayList<SavedCape>(savedById.values());
    }

    public static synchronized boolean isCustomOptionKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX) && !CREATE_OPTION_KEY.equals(key);
    }

    public static synchronized boolean isCreateOptionKey(String key) {
        return CREATE_OPTION_KEY.equals(key);
    }

    public static synchronized SavedCape getByOptionKey(String key) {
        if (!isCustomOptionKey(key)) {
            return null;
        }
        ensureLoaded();
        return savedById.get(key.substring(KEY_PREFIX.length()));
    }

    public static synchronized SavedCape getById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ensureLoaded();
        return savedById.get(id);
    }

    public static synchronized String idFromOptionKey(String key) {
        if (!isCustomOptionKey(key)) {
            return null;
        }
        return key.substring(KEY_PREFIX.length());
    }

    public static synchronized ResourceLocation getTextureForOptionKey(String key) {
        if (!isCustomOptionKey(key)) {
            return null;
        }
        if (!loaded) {
            reloadLocked();
        }
        SavedCape cape = savedById.get(key.substring(KEY_PREFIX.length()));
        if (cape == null) {
            return null;
        }
        ResourceLocation ready = ensureTextureRegisteredLocked(cape);
        if (ready != null && (cape.texture == null || !ready.equals(cape.texture))) {
            cape = new SavedCape(cape.id, cape.label, ready, cape.sharedFrom, cape.layout, cape.layoutExplicit);
            savedById.put(cape.id, cape);
        }
        return ready != null ? ready : cape.texture;
    }

    /** Base64 PNG for relay peer cape sync (custom designs only). */
    public static synchronized String encodeOptionKeyPngBase64(String key) {
        if (!isCustomOptionKey(key)) {
            return "";
        }
        String id = key.substring(KEY_PREFIX.length());
        BufferedImage img = loadImageForId(id);
        return encodeImagePngBase64(img);
    }

    /** Base64 PNG for relay peer sync of worn catalog / owned capes. */
    public static synchronized String encodeLocationPngBase64(ResourceLocation location) {
        return encodeImagePngBase64(loadImageFromCapeTexture(location));
    }

    private static String encodeImagePngBase64(BufferedImage img) {
        if (img == null) {
            return "";
        }
        byte[] bytes = encodePng(img);
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Ephemeral peer-wear texture (not saved to the local custom cape library).
     */
    public static synchronized ResourceLocation registerEphemeralPeerWear(String peerUuid, String imageBase64) {
        if (peerUuid == null || peerUuid.isEmpty() || imageBase64 == null || imageBase64.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(imageBase64.trim());
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) {
                return null;
            }
            img = normalizeCapeImage(img);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return null;
            }
            String id = peerUuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
            ResourceLocation rl = new ResourceLocation("gomod/peer_capes/" + id);
            mc.getTextureManager().loadTexture(rl, new net.minecraft.client.renderer.texture.DynamicTexture(img));
            return rl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static synchronized String saveNew(String label, BufferedImage image) {
        return saveNew(label, image, false);
    }

    public static synchronized String saveNew(String label, BufferedImage image, boolean optifineLayout) {
        ensureLoaded();
        if (image == null) {
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return saveLocked(id, sanitizeLabel(label, "Custom cape"), normalizeCapeImage(image),
                "", layoutFor(optifineLayout)) ? id : null;
    }

    public static synchronized boolean updateExisting(String id, String label, BufferedImage image) {
        ensureLoaded();
        SavedCape existing = savedById.get(id);
        return updateExisting(id, label, image, existing != null && existing.isOptifineLayout());
    }

    public static synchronized boolean updateExisting(String id, String label, BufferedImage image, boolean optifineLayout) {
        if (id == null || id.isEmpty() || image == null) {
            return false;
        }
        ensureLoaded();
        SavedCape existing = savedById.get(id);
        if (existing == null) {
            return false;
        }
        return saveLocked(id, sanitizeLabel(label, existing.label), normalizeCapeImage(image),
                existing.sharedFrom, layoutFor(optifineLayout));
    }

    public static synchronized BufferedImage loadImageForId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        File file = capeFile(id);
        if (!file.isFile()) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(file);
            return img == null ? null : normalizeCapeImage(img);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static synchronized boolean delete(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        ensureLoaded();
        if (!savedById.containsKey(id)) {
            return false;
        }
        savedById.remove(id);
        DynamicTexture registered = registeredTextureById.remove(id);
        if (registered != null) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                ResourceLocation location = textureLocationForId(id);
                if (mc != null && mc.getTextureManager() != null
                        && mc.getTextureManager().getTexture(location) == registered) {
                    mc.getTextureManager().deleteTexture(location);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Files.deleteIfExists(capeFile(id).toPath());
        } catch (Throwable ignored) {
        }
        persistManifestLocked();
        PlayerStandaloneCape.invalidateCatalog();
        if (ConfigManager.clearPlayerStandaloneCapeSelectionsForOptionKey(KEY_PREFIX + id)) {
            PlayerStandaloneCape.loadSetting();
            PlayerStandaloneCape.notifyWearChanged();
        }
        return true;
    }

    public static synchronized String importSharedFromFriend(String friendName, String label, String imageBase64) {
        return importFromBase64(label, imageBase64, friendName == null ? "friend" : friendName.trim());
    }

    public static synchronized String importFromBase64(String label, String imageBase64, String sharedFrom) {
        if (imageBase64 == null || imageBase64.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(imageBase64.trim());
            if (bytes.length == 0) {
                return null;
            }
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) {
                return null;
            }
            String baseLabel = sanitizeLabel(label, "Shared cape");
            String from = sanitizeSharedFrom(sharedFrom);
            BufferedImage normalized = normalizeCapeImage(img);
            String existingId = findMatchingSharedCapeLocked(baseLabel, from, normalized);
            if (existingId != null) {
                return existingId;
            }
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            return saveLocked(id, baseLabel, normalized, from) ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static byte[] encodePng(BufferedImage image) {
        if (image == null) {
            return new byte[0];
        }
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(normalizeCapeImage(image), "png", baos);
            return baos.toByteArray();
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    private static void reloadLocked() {
        savedById.clear();
        File dir = capesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            loaded = true;
            return;
        }
        File manifest = manifestFile();
        if (manifest.isFile()) {
            try (FileReader fr = new FileReader(manifest)) {
                JsonObject root = new JsonParser().parse(fr).getAsJsonObject();
                if (root != null && root.has("capes") && root.get("capes").isJsonArray()) {
                    JsonArray arr = root.getAsJsonArray("capes");
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isJsonObject()) {
                            continue;
                        }
                        JsonObject o = arr.get(i).getAsJsonObject();
                        String id = o.has("id") ? o.get("id").getAsString() : "";
                        String label = o.has("label") ? o.get("label").getAsString() : "Custom cape";
                        String sharedFrom = o.has("sharedFrom") ? o.get("sharedFrom").getAsString() : "";
                        String layout = o.has("layout") ? o.get("layout").getAsString() : "";
                        boolean layoutExplicit = o.has("layoutExplicit") && o.get("layoutExplicit").getAsBoolean();
                        SharedLabelParts legacyShared = parseLegacySharedLabel(label);
                        if ((sharedFrom == null || sharedFrom.trim().isEmpty()) && legacyShared != null) {
                            sharedFrom = legacyShared.from;
                            label = legacyShared.label;
                        }
                        if (id.isEmpty() || !capeFile(id).isFile()) {
                            continue;
                        }
                        ResourceLocation rl = registerTexture(id, capeFile(id));
                        if (rl == null) {
                            rl = textureLocationForId(id);
                        }
                        savedById.put(id, new SavedCape(id, sanitizeLabel(label, "Custom cape"), rl,
                                sanitizeSharedFrom(sharedFrom), sanitizeLayout(layout), layoutExplicit));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        loaded = true;
    }

    private static boolean saveLocked(String id, String label, BufferedImage image) {
        return saveLocked(id, label, image, "", "");
    }

    private static boolean saveLocked(String id, String label, BufferedImage image, String sharedFrom) {
        return saveLocked(id, label, image, sharedFrom, "");
    }

    private static boolean saveLocked(String id, String label, BufferedImage image, String sharedFrom, String layout) {
        File dir = capesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        try {
            ImageIO.write(image, "png", capeFile(id));
        } catch (Throwable ignored) {
            return false;
        }
        ResourceLocation rl = registerTexture(id, capeFile(id));
        if (rl == null) {
            rl = textureLocationForId(id);
        }
        savedById.put(id, new SavedCape(id, label, rl, sanitizeSharedFrom(sharedFrom), sanitizeLayout(layout), true));
        persistManifestLocked();
        PlayerStandaloneCape.invalidateCatalog();
        return true;
    }

    private static String findMatchingSharedCapeLocked(String label, String sharedFrom, BufferedImage image) {
        if (image == null) return null;
        for (SavedCape cape : savedById.values()) {
            if (cape == null || !label.equals(cape.label) || !sharedFrom.equals(cape.sharedFrom)) continue;
            try {
                BufferedImage saved = ImageIO.read(capeFile(cape.id));
                if (samePixels(image, saved == null ? null : normalizeCapeImage(saved))) return cape.id;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    private static void ensureTexturesRegisteredLocked() {
        if (savedById.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (SavedCape cape : new ArrayList<SavedCape>(savedById.values())) {
            if (cape == null || cape.id == null || cape.id.isEmpty()) {
                continue;
            }
            ResourceLocation rl = ensureTextureRegisteredLocked(cape);
            if (rl != null && (cape.texture == null || !rl.equals(cape.texture))) {
                savedById.put(cape.id, new SavedCape(cape.id, cape.label, rl, cape.sharedFrom, cape.layout,
                        cape.layoutExplicit));
                changed = true;
            }
        }
        if (changed) {
            PlayerStandaloneCape.invalidateCatalog();
        }
    }

    /**
     * Startup can run before Minecraft's texture manager is ready. In that case the saved cape still receives its
     * deterministic ResourceLocation, but the first render binds Minecraft's purple missing-texture placeholder.
     * Retry only while that location is not backed by our DynamicTexture; once registered, this is just a map lookup.
     */
    private static ResourceLocation ensureTextureRegisteredLocked(SavedCape cape) {
        if (cape == null || cape.id == null || cape.id.isEmpty()) {
            return null;
        }
        ResourceLocation location = textureLocationForId(cape.id);
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return cape.texture != null ? cape.texture : location;
            }
            ITextureObject current = mc.getTextureManager().getTexture(location);
            if (current != null && current == registeredTextureById.get(cape.id)) {
                return location;
            }
        } catch (Throwable ignored) {
            return cape.texture != null ? cape.texture : location;
        }
        ResourceLocation registered = registerTexture(cape.id, capeFile(cape.id));
        return registered != null ? registered : (cape.texture != null ? cape.texture : location);
    }

    private static void persistManifestLocked() {
        File dir = capesDir();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (SavedCape cape : savedById.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", cape.id);
            o.addProperty("label", cape.label);
            if (cape.sharedFrom != null && !cape.sharedFrom.isEmpty()) {
                o.addProperty("sharedFrom", cape.sharedFrom);
            }
            if (cape.layout != null && !cape.layout.isEmpty()) {
                o.addProperty("layout", cape.layout);
            }
            o.addProperty("layoutExplicit", cape.layoutExplicit);
            arr.add(o);
        }
        root.add("capes", arr);
        try (java.io.OutputStreamWriter fw = new java.io.OutputStreamWriter(
                Files.newOutputStream(manifestFile().toPath()), StandardCharsets.UTF_8)) {
            GSON.toJson(root, fw);
        } catch (Throwable ignored) {
        }
    }

    private static ResourceLocation registerTexture(String id, File file) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return null;
            }
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                return null;
            }
            img = normalizeCapeImage(img);
            ResourceLocation rl = textureLocationForId(id);
            DynamicTexture previous = registeredTextureById.get(id);
            if (previous != null && mc.getTextureManager().getTexture(rl) == previous) {
                mc.getTextureManager().deleteTexture(rl);
            }
            DynamicTexture texture = new DynamicTexture(img);
            mc.getTextureManager().loadTexture(rl, texture);
            registeredTextureById.put(id, texture);
            return rl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation textureLocationForId(String id) {
        return new ResourceLocation("gomod/custom_capes/" + id);
    }

    public static BufferedImage normalizeCapeImage(BufferedImage source) {
        if (source == null) {
            return null;
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int targetWidth = CAPE_W;
        int targetHeight = CAPE_H;
        while (targetWidth < sourceWidth || targetHeight < sourceHeight) {
            targetWidth *= 2;
            targetHeight *= 2;
        }
        BufferedImage normalized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = normalized.getGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    public static BufferedImage blankCanvas() {
        return new BufferedImage(CAPE_W, CAPE_H, BufferedImage.TYPE_INT_ARGB);
    }

    /** New editor cape: white only on {@link me.napim.gomod.gui.CapeWearMask} texels (user newcape layout). */
    public static BufferedImage newEditorCanvas() {
        BufferedImage img = blankCanvas();
        fillWearRegionsWhite(img);
        return img;
    }

    public static void fillWearRegionsWhite(BufferedImage img) {
        if (img == null) {
            return;
        }
        int w = Math.min(CAPE_W, img.getWidth());
        int h = Math.min(CAPE_H, img.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (me.napim.gomod.gui.CapeWearMask.isWearPixel(x, y)) {
                    img.setRGB(x, y, 0xFFFFFFFF);
                }
            }
        }
    }

    /**
     * Copies pixels from a loaded cape texture (custom {@link DynamicTexture} or downloaded Mojang cape).
     */
    public static BufferedImage loadImageFromCapeTexture(ResourceLocation location) {
        if (location == null) {
            return null;
        }
        BufferedImage downloaded = CapeTextureLoader.getCachedCapeImage(location);
        if (downloaded != null) {
            return normalizeCapeImage(downloaded);
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getTextureManager() == null) {
            return null;
        }
        ITextureObject tex = mc.getTextureManager().getTexture(location);
        if (tex instanceof DynamicTexture) {
            BufferedImage img = readDynamicTextureImage((DynamicTexture) tex);
            return img == null ? null : normalizeCapeImage(img);
        }
        if (tex instanceof ThreadDownloadImageData) {
            BufferedImage img = readDownloadedImage((ThreadDownloadImageData) tex);
            return img == null ? null : normalizeCapeImage(img);
        }
        return null;
    }

    public static String debugCapeImageStats(ResourceLocation location) {
        BufferedImage image = loadImageFromCapeTexture(location);
        if (image == null) {
            return "unavailable";
        }
        int opaque = 0;
        int transparent = 0;
        int nonWhite = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    transparent++;
                } else {
                    opaque++;
                    if ((argb & 0x00FFFFFF) != 0x00FFFFFF) {
                        nonWhite++;
                    }
                }
            }
        }
        return image.getWidth() + "x" + image.getHeight()
                + ",opaque=" + opaque + ",transparent=" + transparent + ",nonWhite=" + nonWhite;
    }

    public static boolean hasReadableCapeImage(ResourceLocation location) {
        return loadImageFromCapeTexture(location) != null;
    }

    private static BufferedImage readDynamicTextureImage(DynamicTexture dynamic) {
        for (String name : new String[]{"bufferedImage", "field_110560_d"}) {
            try {
                Field f = DynamicTexture.class.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(dynamic);
                if (v instanceof BufferedImage) {
                    return copyBufferedImage((BufferedImage) v);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static BufferedImage readDownloadedImage(ThreadDownloadImageData dl) {
        for (String name : new String[]{"bufferedImage", "field_110560_d"}) {
            try {
                Field f = dl.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(dl);
                if (v instanceof BufferedImage) {
                    return copyBufferedImage((BufferedImage) v);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static BufferedImage copyBufferedImage(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    public static int capeWidth() {
        return CAPE_W;
    }

    public static int capeHeight() {
        return CAPE_H;
    }

    private static ResourceLocation editorPreviewTexture;
    private static DynamicTexture editorPreviewDynamic;

    /** Live cape texture for {@link me.napim.gomod.gui.CustomCapeEditorGUI} player preview. */
    public static synchronized ResourceLocation textureForEditorPreview(BufferedImage canvas) {
        if (canvas == null) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getTextureManager() == null) {
            return null;
        }
        BufferedImage normalized = normalizeCapeImage(canvas);
        if (normalized == null) {
            return null;
        }
        if (editorPreviewTexture == null) {
            editorPreviewTexture = new ResourceLocation("gomod123", "runtime/cape_editor_preview");
        }
        try {
            if (editorPreviewDynamic != null) {
                mc.getTextureManager().deleteTexture(editorPreviewTexture);
            }
            editorPreviewDynamic = new DynamicTexture(normalized);
            mc.getTextureManager().loadTexture(editorPreviewTexture, editorPreviewDynamic);
            return editorPreviewTexture;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static synchronized void releaseEditorPreviewTexture() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getTextureManager() != null && editorPreviewTexture != null) {
            try {
                mc.getTextureManager().deleteTexture(editorPreviewTexture);
            } catch (Throwable ignored) {
            }
        }
        editorPreviewDynamic = null;
    }

    private static File capesDir() {
        return new File(GomodPaths.getGomod123Dir(), "custom_capes");
    }

    private static File manifestFile() {
        return new File(capesDir(), "manifest.json");
    }

    private static File capeFile(String id) {
        return new File(capesDir(), id + ".png");
    }

    private static String sanitizeLabel(String label, String fallback) {
        String out = GomodCapeShareHistory.cleanLabel(label);
        if (out.isEmpty()) {
            out = fallback == null ? "Custom cape" : fallback;
        }
        if (out.length() > 48) {
            out = out.substring(0, 45) + "...";
        }
        return out;
    }

    private static String sanitizeSharedFrom(String from) {
        String out = GomodCapeShareHistory.cleanLabel(from);
        if (out.length() > 16) {
            out = out.substring(0, 16);
        }
        return out;
    }

    private static String layoutFor(boolean optifineLayout) {
        return optifineLayout ? LAYOUT_OPTIFINE : "";
    }

    private static String sanitizeLayout(String layout) {
        String clean = layout == null ? "" : layout.trim().toLowerCase(Locale.ROOT);
        return LAYOUT_OPTIFINE.equals(clean) ? LAYOUT_OPTIFINE : "";
    }

    private static SharedLabelParts parseLegacySharedLabel(String label) {
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(label == null ? "" : label).trim();
        if (!plain.startsWith("From ")) {
            return null;
        }
        int colon = plain.indexOf(": ", 5);
        if (colon <= 5 || colon >= plain.length() - 2) {
            return null;
        }
        return new SharedLabelParts(plain.substring(5, colon), plain.substring(colon + 2));
    }

    private static final class SharedLabelParts {
        final String from;
        final String label;

        SharedLabelParts(String from, String label) {
            this.from = from;
            this.label = label;
        }
    }

    public static List<LocalDisguise.CapeOptionInfo> buildGuiEntries() {
        ensureLoaded();
        ArrayList<LocalDisguise.CapeOptionInfo> out = new ArrayList<LocalDisguise.CapeOptionInfo>();
        out.add(LocalDisguise.CapeOptionInfo.createEntry("+ Create custom cape", null, CREATE_OPTION_KEY, false, true));
        for (SavedCape cape : savedById.values()) {
            out.add(LocalDisguise.CapeOptionInfo.createEntry(
                    EnumChatFormatting.LIGHT_PURPLE + cape.label,
                    cape.texture,
                    cape.optionKey(),
                    true,
                    false,
                    true,
                    true,
                    cape.sharedFrom));
        }
        return Collections.unmodifiableList(out);
    }
}
