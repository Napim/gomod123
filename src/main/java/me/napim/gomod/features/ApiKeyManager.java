package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.DcStringUtil;
import me.napim.gomod.util.GomodPaths;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Manages API keys for various services used by the mod.
 * Supports OpenAI (gostats123 AI) and Hypixel API. DC bot keys are internal/dev builds only.
 */
public class ApiKeyManager {
    private static final String HYPX_LAST_VALID_HASH_KEY = "_hypixelLastKnownValidKeyHash";
    private static volatile String hypixelKnownRejectedHash = null;

    private static File apiKeysFile() {
        return new File(GomodPaths.getGomod123Dir(), "api_keys.json");
    }
    private static JsonObject apiKeys = new JsonObject();
    private static boolean loaded = false;
    
    /**
     * Loads API keys from the configuration file.
     * Creates the file with default values if it doesn't exist.
     */
    public static void loadApiKeys() {
        if (loaded) return;
        
        try {
            File configDir = GomodPaths.getGomod123Dir();
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            
            File keysFile = apiKeysFile();
            if (keysFile.exists()) {
                try (FileReader reader = new FileReader(keysFile)) {
                    JsonParser parser = new JsonParser();
                    apiKeys = parser.parse(reader).getAsJsonObject();
                    System.out.println("[ApiKeyManager] Loaded API keys from " + keysFile.getAbsolutePath());
                }
            } else {
                apiKeys = new JsonObject();
                apiKeys.addProperty("openai", "");
                apiKeys.addProperty("hypixel", "");
                if (!GomodReleaseBuild.PUBLIC_RELEASE) {
                    apiKeys.addProperty(dcBotKeyName(), "");
                    apiKeys.addProperty(dcDmRecipientKeyName(), "");
                }
                saveApiKeys();
                System.out.println("[ApiKeyManager] Created new API keys file at " + keysFile.getAbsolutePath());
            }
            
            if (GomodReleaseBuild.PUBLIC_RELEASE && stripDcKeysFromStore()) {
                saveApiKeys();
            }

            loaded = true;
        } catch (Exception e) {
            System.err.println("[ApiKeyManager] Error loading API keys: " + e.getMessage());
            e.printStackTrace();
            apiKeys = new JsonObject();
            loaded = true;
        }
    }
    
    /**
     * Saves API keys to the configuration file.
     */
    public static void saveApiKeys() {
        try {
            if (GomodReleaseBuild.PUBLIC_RELEASE) {
                stripDcKeysFromStore();
            }

            File configDir = GomodPaths.getGomod123Dir();
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            File keysFile = apiKeysFile();
            try (FileWriter writer = new FileWriter(keysFile)) {
                gson.toJson(apiKeys, writer);
                System.out.println("[ApiKeyManager] Saved API keys to " + keysFile.getAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("[ApiKeyManager] Error saving API keys: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static String getApiKey(String service) {
        if (!loaded) {
            loadApiKeys();
        }

        if (GomodReleaseBuild.PUBLIC_RELEASE && isDcApiKey(service)) {
            return null;
        }
        
        if (apiKeys.has(service)) {
            String key = apiKeys.get(service).getAsString();
            return key.isEmpty() ? null : key;
        }
        
        return null;
    }
    
    public static void setApiKey(String service, String apiKey) {
        if (!loaded) {
            loadApiKeys();
        }

        String previousValue = null;
        if ("hypixel".equals(service) && apiKeys.has(service) && !apiKeys.get(service).isJsonNull()) {
            previousValue = apiKeys.get(service).getAsString();
        }

        if (GomodReleaseBuild.PUBLIC_RELEASE && isDcApiKey(service)) {
            return;
        }
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKeys.addProperty(service, "");
        } else {
            apiKeys.addProperty(service, apiKey.trim());
        }

        if ("hypixel".equals(service)) {
            String previousHash = keyHash(previousValue);
            String nextHash = keyHash(apiKey);
            if (previousHash == null || nextHash == null || !previousHash.equals(nextHash)) {
                clearHypixelKeyRejectedState();
            }
        }
        
        saveApiKeys();
        System.out.println("[ApiKeyManager] Set API key for " + service + ": " + 
                          (apiKey == null || apiKey.trim().isEmpty() ? "cleared" : "updated"));
    }
    
    public static boolean hasApiKey(String service) {
        String key = getApiKey(service);
        return key != null && !key.trim().isEmpty();
    }

    public static synchronized void rememberHypixelKeyValidated(String apiKey) {
        if (!loaded) {
            loadApiKeys();
        }
        String hash = keyHash(apiKey);
        if (hash == null || hash.isEmpty()) {
            return;
        }
        if (apiKeys.has(HYPX_LAST_VALID_HASH_KEY) && !apiKeys.get(HYPX_LAST_VALID_HASH_KEY).isJsonNull()
                && hash.equals(apiKeys.get(HYPX_LAST_VALID_HASH_KEY).getAsString())) {
            clearHypixelKeyRejectedState();
            return;
        }
        apiKeys.addProperty(HYPX_LAST_VALID_HASH_KEY, hash);
        clearHypixelKeyRejectedState();
        saveApiKeys();
    }

    public static boolean wasHypixelKeyPreviouslyValidated(String apiKey) {
        if (!loaded) {
            loadApiKeys();
        }
        String hash = keyHash(apiKey);
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        if (!apiKeys.has(HYPX_LAST_VALID_HASH_KEY) || apiKeys.get(HYPX_LAST_VALID_HASH_KEY).isJsonNull()) {
            return false;
        }
        String stored = apiKeys.get(HYPX_LAST_VALID_HASH_KEY).getAsString();
        return stored != null && stored.equals(hash);
    }

    public static void noteHypixelKeyRejected(String apiKey) {
        String hash = keyHash(apiKey);
        if (hash == null || hash.isEmpty()) {
            return;
        }
        hypixelKnownRejectedHash = hash;
    }

    public static boolean isHypixelKeyKnownRejected(String apiKey) {
        String hash = keyHash(apiKey);
        return hash != null && hash.equals(hypixelKnownRejectedHash);
    }

    public static void clearHypixelKeyRejectedState() {
        hypixelKnownRejectedHash = null;
    }
    
    public static String getMaskedApiKey(String service) {
        String key = getApiKey(service);
        if (key == null || key.trim().isEmpty()) {
            return "Not set";
        }
        
        if (key.length() <= 8) {
            return "****";
        }
        
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
    
    public static String listApiKeys() {
        if (!loaded) {
            loadApiKeys();
        }
        
        StringBuilder result = new StringBuilder();
        result.append("API Keys Status:\n");
        result.append("OpenAI: ").append(getMaskedApiKey("openai")).append("\n");
        result.append("Hypixel: ").append(getMaskedApiKey("hypixel"));
        
        return result.toString();
    }

    /** Bot token key name (internal builds). */
    public static String dcBotKeyName() {
        return DcStringUtil.dcBotKey();
    }

    /** Legacy DM recipient key in api_keys.json (internal builds). */
    public static String dcDmRecipientKeyName() {
        return DcStringUtil.dcDmRecipientKey();
    }

    private static boolean isDcApiKey(String service) {
        if (service == null) {
            return false;
        }
        return service.equals(dcBotKeyName()) || service.equals(dcDmRecipientKeyName());
    }

    private static boolean stripDcKeysFromStore() {
        boolean changed = false;
        if (apiKeys.has(dcBotKeyName())) {
            apiKeys.remove(dcBotKeyName());
            changed = true;
        }
        if (apiKeys.has(dcDmRecipientKeyName())) {
            apiKeys.remove(dcDmRecipientKeyName());
            changed = true;
        }
        return changed;
    }

    private static String keyHash(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        String trimmed = apiKey.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(trimmed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
