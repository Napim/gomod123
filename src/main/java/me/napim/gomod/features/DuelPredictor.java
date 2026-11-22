package me.napim.gomod.features;

/**
 * Client-side duel opponent movement predictor (ghost + HUD).
 * Hooks: {@link #clientTick()}, {@link #render(float)}, {@link #renderHud()}.
 */
public final class DuelPredictor {

    private static boolean enabled;

    private DuelPredictor() {}

    public static void loadSettings() {
        enabled = ConfigManager.isDuelPredictorEnabled();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        ConfigManager.setDuelPredictorEnabled(value);
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    public static void clientTick() {
        if (!enabled) {
            return;
        }
    }

    public static void render(float partialTicks) {
        if (!enabled || !ConfigManager.isDuelPredictorGhostEnabled()) {
            return;
        }
    }

    public static void renderHud() {
        if (!enabled || !ConfigManager.isDuelPredictorHudEnabled()) {
            return;
        }
    }
}
