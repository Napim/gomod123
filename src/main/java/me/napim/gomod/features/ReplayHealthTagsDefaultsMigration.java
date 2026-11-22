package me.napim.gomod.features;

import com.google.gson.JsonObject;

/** Enables forced replay health tags once for configs created before the default changed. */
public final class ReplayHealthTagsDefaultsMigration {
    public static final int CURRENT_VERSION = 1;
    private static final String VERSION_KEY = "forceReplayPlayerHealthTagsDefaultsVersion";

    private ReplayHealthTagsDefaultsMigration() {
    }

    /**
     * Older configs persisted false for the OFF default, so apply the requested default once.
     * The version marker preserves later user opt-outs on every subsequent launch.
     */
    public static boolean migrate(JsonObject config) {
        if (config == null || readVersion(config) >= CURRENT_VERSION) {
            return false;
        }
        config.addProperty("forceReplayPlayerHealthTagsEnabled", true);
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
