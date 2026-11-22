package me.napim.gomod.features;

import com.google.gson.JsonObject;

/** Applies the corrected enabled-by-default values once to configs from the earlier 0.3 build. */
public final class RelayFeatureDefaultsMigration {
    public static final int CURRENT_VERSION = 1;
    private static final String VERSION_KEY = "relayGameFeaturesDefaultsVersion";

    private RelayFeatureDefaultsMigration() {
    }

    /**
     * The earlier 0.3 build persisted false as the default for all three settings, so a missing marker
     * cannot distinguish that old default from an explicit opt-out. Apply the requested new defaults
     * once, then leave subsequent user choices untouched.
     */
    public static boolean migrate(JsonObject config) {
        if (config == null || readVersion(config) >= CURRENT_VERSION) {
            return false;
        }

        config.addProperty("seeSpectatorsWhileAlive", true);
        config.addProperty("forceGameChats", true);
        config.addProperty("unbotheredTaunt", true);
        config.addProperty(VERSION_KEY, CURRENT_VERSION);
        return true;
    }

    public static int readVersion(JsonObject config) {
        if (config == null || !config.has(VERSION_KEY) || config.get(VERSION_KEY).isJsonNull()) {
            return 0;
        }
        try {
            return Math.max(0, config.get(VERSION_KEY).getAsInt());
        } catch (RuntimeException invalidVersion) {
            return 0;
        }
    }
}
