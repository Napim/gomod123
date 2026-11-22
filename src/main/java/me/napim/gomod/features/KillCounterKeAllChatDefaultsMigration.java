package me.napim.gomod.features;

import com.google.gson.JsonObject;

/** Disables the legacy /tabstats all chat output once for existing configs. */
public final class KillCounterKeAllChatDefaultsMigration {
    public static final int CURRENT_VERSION = 1;
    private static final String VERSION_KEY = "killCounterShowKeAllChatDefaultsVersion";

    private KillCounterKeAllChatDefaultsMigration() {
    }

    /**
     * The chat-output option is no longer user-facing, so migrate older saved ON values to OFF once.
     * Persisting the marker prevents later config imports or saves from being repeatedly rewritten.
     */
    public static boolean migrate(JsonObject config) {
        if (config == null || readVersion(config) >= CURRENT_VERSION) {
            return false;
        }
        config.addProperty("killCounterShowKeAllChat", false);
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
