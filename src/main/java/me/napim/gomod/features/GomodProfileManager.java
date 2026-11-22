package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stores copies of {@code gomodConfig.json} per named profile under {@code gomod123/profiles/}.
 * API keys are never written into profile files ({@link ApiKeyManager} stays in {@code api_keys.json} only).
 */
public final class GomodProfileManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** After sanitization: letter/digit start, then letters, digits, underscores, hyphens (max 32). Case preserved. */
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private GomodProfileManager() {
    }

    private static String[] stripJsonKeysForBuild() {
        if (GomodReleaseBuild.PUBLIC_RELEASE) {
            return new String[] {"openai", "hypixel"};
        }
        return new String[] {
                "openai", "hypixel",
                ApiKeyManager.dcBotKeyName(), ApiKeyManager.dcDmRecipientKeyName()
        };
    }

    private static String[] apiKeyServicesForBuild() {
        return stripJsonKeysForBuild();
    }

    public static File profilesDirectory() {
        File d = new File(ConfigManager.getGomodDataDirectory(), "profiles");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    public static File metaFile() {
        return new File(ConfigManager.getGomodDataDirectory(), "gomod_profiles.json");
    }

    /**
     * Normalizes profile names: spaces → underscores, strips invalid characters (case preserved).
     *
     * @return sanitized name or {@code null} if nothing usable remains
     */
    public static String sanitizeProfileName(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        t = t.replace(' ', '_').replaceAll("[^A-Za-z0-9_-]+", "_");
        while (t.startsWith("_")) {
            t = t.substring(1);
        }
        while (t.endsWith("_")) {
            t = t.substring(0, t.length() - 1);
        }
        t = t.replaceAll("_+", "_");
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > 32) {
            t = t.substring(0, 32);
        }
        if (!PROFILE_NAME.matcher(t).matches()) {
            return null;
        }
        return t;
    }

    private static JsonObject readOrCreateMeta() {
        File f = metaFile();
        try {
            if (f.isFile()) {
                try (FileReader r = new FileReader(f)) {
                    JsonObject o = new JsonParser().parse(r).getAsJsonObject();
                    if (!o.has("activeProfile") || !o.get("activeProfile").isJsonPrimitive()) {
                        o.addProperty("activeProfile", "default");
                    }
                    return o;
                }
            }
        } catch (Exception e) {
            System.err.println("[GomodProfileManager] Failed to read meta: " + e.getMessage());
        }
        JsonObject o = new JsonObject();
        o.addProperty("activeProfile", "default");
        writeMeta(o);
        return o;
    }

    private static void writeMeta(JsonObject meta) {
        try {
            File f = metaFile();
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            try (FileWriter w = new FileWriter(f)) {
                GSON.toJson(meta, w);
            }
        } catch (Exception e) {
            System.err.println("[GomodProfileManager] Failed to write meta: " + e.getMessage());
        }
    }

    public static String getActiveProfileName() {
        return readOrCreateMeta().get("activeProfile").getAsString();
    }

    private static void setActiveProfileName(String name) {
        JsonObject m = readOrCreateMeta();
        m.addProperty("activeProfile", name);
        writeMeta(m);
    }

    public static File profileFile(String sanitizedName) {
        return new File(profilesDirectory(), sanitizedName + ".json");
    }

    public static List<String> listProfileNames() {
        File[] files = profilesDirectory().listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        ArrayList<String> out = new ArrayList<String>();
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) {
                continue;
            }
            String base = f.getName().substring(0, f.getName().length() - 5);
            String sn = sanitizeProfileName(base);
            if (sn != null && !out.contains(sn)) {
                out.add(sn);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Persists {@code profiles/<name>.json} from the current live {@code gomodConfig.json} (overwrites when the name exists).
     * API-key-shaped keys are never stored flat in profile files.
     */
    public static void saveCurrentConfigIntoProfile(String rawName) throws Exception {
        String name = sanitizeProfileName(rawName);
        if (name == null) {
            throw new IllegalArgumentException(
                    "Invalid profile name. Type letters/numbers (spaces OK — become _). Example: my hypixel → my_hypixel.");
        }
        ConfigManager.saveConfigNow();
        File dst = profileFile(name);
        Files.copy(ConfigManager.getGomodConfigFile().toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        stripBannedKeysFromProfileFile(dst);
        embedApiKeysIntoProfile(dst);
    }

    /**
     * Saves the current live config and API keys into the active profile snapshot, then loads
     * {@code profiles/<name>.json} (config + API keys) into gomod.
     */
    public static void switchToProfile(String rawTargetName) throws Exception {
        String target = sanitizeProfileName(rawTargetName);
        if (target == null) {
            throw new IllegalArgumentException(
                    "Invalid profile name. Use letters, numbers, spaces, _ or - (max 32 chars after cleanup).");
        }
        File source = profileFile(target);
        if (!source.isFile()) {
            throw new IllegalArgumentException(
                    "Profile \"" + target + "\" does not exist. Use \"Save copy as\" first.");
        }

        ConfigManager.saveConfigNow();

        String active = getActiveProfileName();
        File activeSnap = profileFile(active);
        Files.copy(ConfigManager.getGomodConfigFile().toPath(), activeSnap.toPath(), StandardCopyOption.REPLACE_EXISTING);
        stripBannedKeysFromProfileFile(activeSnap);
        embedApiKeysIntoProfile(activeSnap);

        copyProfileToConfig(source, ConfigManager.getGomodConfigFile());
        ConfigManager.reloadConfigFromDisk();
        setActiveProfileName(target);

        GomodSettingsReloader.reloadAllAfterConfigLoaded();
        if (GomodInternalFeatures.isEnabled() && restoreApiKeysFromProfile(source)) {
            DcMirrorBridge.invalidateChannelCache();
            DcMirrorBridge.invalidateTokenIdentityCaches();
            DcMirrorBridge.warmSelfDisplayFromToken();
            DcMirrorGateway.restartIfNeeded();
        }
    }

    private static void stripBannedKeysFromProfileFile(File f) {
        try {
            JsonObject o;
            try (FileReader r = new FileReader(f)) {
                o = new JsonParser().parse(r).getAsJsonObject();
            }
            boolean changed = false;
            for (String k : stripJsonKeysForBuild()) {
                if (o.has(k)) {
                    o.remove(k);
                    changed = true;
                }
            }
            if (changed) {
                try (FileWriter w = new FileWriter(f)) {
                    GSON.toJson(o, w);
                }
            }
        } catch (Exception e) {
            System.err.println("[GomodProfileManager] stripBannedKeys: " + e.getMessage());
        }
    }

    /**
     * Reads current API keys from {@link ApiKeyManager} and writes them into the profile file
     * under a {@code profile_api_keys} JSON object so they travel with the profile.
     */
    private static void embedApiKeysIntoProfile(File profileFile) {
        try {
            JsonObject profile;
            try (FileReader r = new FileReader(profileFile)) {
                profile = new JsonParser().parse(r).getAsJsonObject();
            }
            JsonObject keys = new JsonObject();
            for (String service : apiKeyServicesForBuild()) {
                String val = ApiKeyManager.getApiKey(service);
                keys.addProperty(service, val != null ? val : "");
            }
            profile.add("profile_api_keys", keys);
            try (FileWriter w = new FileWriter(profileFile)) {
                GSON.toJson(profile, w);
            }
        } catch (Exception e) {
            System.err.println("[GomodProfileManager] embedApiKeys: " + e.getMessage());
        }
    }

    /**
     * Reads {@code profile_api_keys} from a profile file and applies them via {@link ApiKeyManager}.
     *
     * @return {@code true} if keys were found and applied, {@code false} if the profile has no key block
     */
    private static boolean restoreApiKeysFromProfile(File profileFile) {
        try {
            JsonObject profile;
            try (FileReader r = new FileReader(profileFile)) {
                profile = new JsonParser().parse(r).getAsJsonObject();
            }
            if (!profile.has("profile_api_keys") || !profile.get("profile_api_keys").isJsonObject()) {
                return false;
            }
            JsonObject keys = profile.getAsJsonObject("profile_api_keys");
            for (String service : apiKeyServicesForBuild()) {
                if (keys.has(service)) {
                    String val = keys.get(service).getAsString();
                    ApiKeyManager.setApiKey(service, val.isEmpty() ? null : val);
                }
            }
            return true;
        } catch (Exception e) {
            System.err.println("[GomodProfileManager] restoreApiKeys: " + e.getMessage());
            return false;
        }
    }

    /**
     * Copies a profile file into {@code gomodConfig.json}, stripping {@code profile_api_keys}
     * and any stray flat API-key fields so they never pollute the live config.
     */
    private static void copyProfileToConfig(File source, File dest) throws Exception {
        JsonObject profile;
        try (FileReader r = new FileReader(source)) {
            profile = new JsonParser().parse(r).getAsJsonObject();
        }
        if (profile.has("profile_api_keys")) {
            profile.remove("profile_api_keys");
        }
        for (String k : stripJsonKeysForBuild()) {
            if (profile.has(k)) profile.remove(k);
        }
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(dest)) {
            GSON.toJson(profile, w);
        }
    }

    /**
     * Creates a new profile: copies live {@code gomodConfig.json}, applies factory More HUD layout + scales (same as
     * in-game Reset all row positions/scales), optional {@link ConfigManager} user kill-effects HUD defaults, then attaches API keys.
     */
    public static void createNewProfileFromCurrent(String rawName) throws Exception {
        String name = sanitizeProfileName(rawName);
        if (name == null) {
            throw new IllegalArgumentException("Invalid profile name.");
        }
        File dst = profileFile(name);
        if (dst.isFile()) {
            throw new IllegalArgumentException("Profile \"" + name + "\" already exists.");
        }
        ConfigManager.saveConfigNow();
        JsonObject cfg;
        try (FileReader r = new FileReader(ConfigManager.getGomodConfigFile())) {
            cfg = new JsonParser().parse(r).getAsJsonObject();
        }
        ConfigManager.applyMoreHudDefaultsForNewProfile(cfg);
        if (dst.getParentFile() != null) {
            dst.getParentFile().mkdirs();
        }
        try (FileWriter w = new FileWriter(dst)) {
            GSON.toJson(cfg, w);
        }
        stripBannedKeysFromProfileFile(dst);
        embedApiKeysIntoProfile(dst);
    }

    /**
     * Duplicates a profile, first flushing the active profile's current in-memory settings to its snapshot.
     * Without that flush, duplicating the profile currently being edited could copy its previous on-disk version.
     */
    public static void duplicateProfile(String rawSourceName, String rawDestName) throws Exception {
        String src = sanitizeProfileName(rawSourceName);
        String dst = sanitizeProfileName(rawDestName);
        if (src == null || dst == null) {
            throw new IllegalArgumentException("Invalid profile name.");
        }
        if (src.equals(dst)) {
            throw new IllegalArgumentException("Choose a different name to duplicate into.");
        }
        File source = profileFile(src);
        if (!source.isFile()) {
            throw new IllegalArgumentException("Profile \"" + src + "\" does not exist.");
        }
        File dest = profileFile(dst);
        if (dest.isFile()) {
            throw new IllegalArgumentException("Profile \"" + dst + "\" already exists.");
        }
        String active = sanitizeProfileName(getActiveProfileName());
        if (src.equals(active)) {
            // GUI settings update ConfigManager immediately, but the profile file is intentionally only written on
            // profile transitions.  Duplicate must mean "what I have configured now", not that old snapshot.
            saveCurrentConfigIntoProfile(src);
        }
        Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        stripBannedKeysFromProfileFile(dest);
    }

    /** Renames a profile file and updates meta if it was the active profile. */
    public static void renameProfile(String rawOldName, String rawNewName) throws Exception {
        String oldName = sanitizeProfileName(rawOldName);
        String newName = sanitizeProfileName(rawNewName);
        if (oldName == null || newName == null) {
            throw new IllegalArgumentException("Invalid profile name.");
        }
        if (oldName.equals(newName)) {
            throw new IllegalArgumentException("New name is the same as the old name.");
        }
        File oldFile = profileFile(oldName);
        if (!oldFile.isFile()) {
            throw new IllegalArgumentException("Profile \"" + oldName + "\" does not exist.");
        }
        File newFile = profileFile(newName);
        if (newFile.isFile()) {
            throw new IllegalArgumentException("Profile \"" + newName + "\" already exists.");
        }
        Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (getActiveProfileName().equals(oldName)) {
            setActiveProfileName(newName);
        }
    }

    public static void notifySwitchFailure(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    Main.CHAT_PREFIX + EnumChatFormatting.RED + message));
        }
    }

    /** Message for GUIs when {@link Minecraft#thePlayer} is null (e.g. main menu). */
    public static String describeSanitizedName(String rawInput, String sanitized) {
        if (sanitized == null || sanitized.isEmpty()) {
            return rawInput == null || rawInput.trim().isEmpty()
                    ? "Type a profile name first."
                    : "Could not use \"" + rawInput.trim() + "\" as a profile name.";
        }
        if (rawInput != null && !sanitized.equals(rawInput.trim().replace(' ', '_'))) {
            return "Using profile name: \"" + sanitized + "\"";
        }
        return "\"" + sanitized + "\"";
    }
}
