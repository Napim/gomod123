package me.napim.gomod.features;

import com.google.gson.JsonObject;

/**
 * Applies the calibrated Blitz #rekt hitbox default padding once for configs created
 * before the default changed from 0.0 on all sides.
 */
public final class BlitzRektHitboxDefaultsMigration {
    public static final int CURRENT_VERSION = 1;
    public static final double DEFAULT_LEFT = -0.2D;
    public static final double DEFAULT_RIGHT = -0.2D;
    public static final double DEFAULT_UP = -0.175D;
    public static final double DEFAULT_DOWN = -0.21D;

    private static final String VERSION_KEY = "blitzRektHitboxPreviewDefaultsVersion";
    private static final String LEFT_KEY = "blitzRektHitboxPreviewLeft";
    private static final String RIGHT_KEY = "blitzRektHitboxPreviewRight";
    private static final String UP_KEY = "blitzRektHitboxPreviewUp";
    private static final String DOWN_KEY = "blitzRektHitboxPreviewDown";
    private static final double ZERO_EPSILON = 1.0E-9D;

    private BlitzRektHitboxDefaultsMigration() {
    }

    /**
     * Older configs persisted 0.0 on all four sides as the untouched default.
     * Upgrade untouched configs once while preserving any custom calibration values.
     */
    public static boolean migrate(JsonObject config) {
        if (config == null || readVersion(config) >= CURRENT_VERSION) {
            return false;
        }
        if (isLegacyUntouchedDefault(config)) {
            config.addProperty(LEFT_KEY, DEFAULT_LEFT);
            config.addProperty(RIGHT_KEY, DEFAULT_RIGHT);
            config.addProperty(UP_KEY, DEFAULT_UP);
            config.addProperty(DOWN_KEY, DEFAULT_DOWN);
        }
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

    private static boolean isLegacyUntouchedDefault(JsonObject config) {
        return isMissingOrZero(config, LEFT_KEY)
                && isMissingOrZero(config, RIGHT_KEY)
                && isMissingOrZero(config, UP_KEY)
                && isMissingOrZero(config, DOWN_KEY);
    }

    private static boolean isMissingOrZero(JsonObject config, String key) {
        if (!config.has(key) || config.get(key).isJsonNull()) {
            return true;
        }
        try {
            double value = config.get(key).getAsDouble();
            return Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) < ZERO_EPSILON;
        } catch (RuntimeException invalidValue) {
            return true;
        }
    }
}
