package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.util.EnumChatFormatting;

/**
 * Settings for the ShareInv friend (remote) inventory overlay — independent of {@link InventoryHUD}.
 */
public final class FriendInventoryHUD {
    private static boolean enabled = true;
    private static boolean force = false;
    private static int customX = 4;
    private static int customY = 161;
    private static float scale = 0.71875f;
    private static int backgroundOpacity = 110;
    private static int backgroundColor = 0;
    private static boolean showRemoteHealth = true;
    private static boolean showRemoteXp = true;
    private static boolean showHeldHighlight = true;
    private static boolean showDirection = true;
    private static boolean directionVertical = false;
    private static boolean textShadow = true;
    private static boolean loaded;

    private FriendInventoryHUD() {}

    public static void loadSettings() {
        enabled = ConfigManager.isFriendInvHudEnabled();
        force = ConfigManager.isFriendInvHudForce();
        customX = ConfigManager.getFriendInvHudCustomX();
        customY = ConfigManager.getFriendInvHudCustomY();
        scale = ConfigManager.getFriendInvHudScale();
        backgroundOpacity = ConfigManager.getFriendInvHudBackgroundOpacity();
        backgroundColor = ConfigManager.getFriendInvHudBackgroundColor();
        showRemoteHealth = ConfigManager.isFriendInvHudShowRemoteHealth();
        showRemoteXp = ConfigManager.isFriendInvHudShowRemoteXp();
        showHeldHighlight = ConfigManager.isFriendInvHudShowHeldHighlight();
        showDirection = ConfigManager.isFriendInvHudShowDirection();
        directionVertical = ConfigManager.isFriendInvHudDirectionVertical();
        textShadow = ConfigManager.isFriendInvHudTextShadow();
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            loadSettings();
        }
    }

    public static void saveSettings() {
        ConfigManager.setFriendInvHudEnabled(enabled);
        ConfigManager.setFriendInvHudForce(force);
        ConfigManager.setFriendInvHudCustomX(customX);
        ConfigManager.setFriendInvHudCustomY(customY);
        ConfigManager.setFriendInvHudScale(scale);
        ConfigManager.setFriendInvHudBackgroundOpacity(backgroundOpacity);
        ConfigManager.setFriendInvHudBackgroundColor(backgroundColor);
        ConfigManager.setFriendInvHudShowRemoteHealth(showRemoteHealth);
        ConfigManager.setFriendInvHudShowRemoteFood(false);
        ConfigManager.setFriendInvHudShowRemoteXp(showRemoteXp);
        ConfigManager.setFriendInvHudShowHeldHighlight(showHeldHighlight);
        ConfigManager.setFriendInvHudShowDirection(showDirection);
        ConfigManager.setFriendInvHudDirectionVertical(directionVertical);
        ConfigManager.setFriendInvHudTextShadow(textShadow);
    }

    /** Toggles only the on-screen friend inventory overlay; ShareInv sessions are unaffected. */
    public static void toggle() {
        ensureLoaded();
        enabled = !enabled;
        saveSettings();
    }

    public static void toggleForce() {
        ensureLoaded();
        force = !force;
        saveSettings();
    }

    public static void toggleShowRemoteHealth() {
        ensureLoaded();
        showRemoteHealth = !showRemoteHealth;
        saveSettings();
    }

    public static void toggleShowRemoteFood() {
        /* Food bar removed from friend HUD; keep method for compatibility. */
    }

    public static void toggleShowRemoteXp() {
        ensureLoaded();
        showRemoteXp = !showRemoteXp;
        saveSettings();
    }

    public static void toggleShowHeldHighlight() {
        ensureLoaded();
        showHeldHighlight = !showHeldHighlight;
        saveSettings();
    }

    public static void toggleShowDirection() {
        ensureLoaded();
        showDirection = !showDirection;
        saveSettings();
    }

    public static void toggleDirectionVertical() {
        ensureLoaded();
        directionVertical = !directionVertical;
        saveSettings();
    }

    public static void toggleTextShadow() {
        ensureLoaded();
        textShadow = !textShadow;
        saveSettings();
    }

    public static void setCustomPosition(int x, int y) {
        ensureLoaded();
        customX = x;
        customY = y;
        saveSettings();
    }

    public static void resetPosition() {
        ensureLoaded();
        customX = 4;
        customY = 161;
        saveSettings();
    }

    public static void setScale(float newScale) {
        ensureLoaded();
        if (newScale >= 0.5f && newScale <= 4.0f) {
            float prev = scale <= 0.0f ? 1.0f : scale;
            int prevMainW = (int) (162.0f * prev);
            int prevMainH = (int) (54.0f * prev);
            int newMainW = (int) (162.0f * newScale);
            int newMainH = (int) (54.0f * newScale);
            customX -= (newMainW - prevMainW) / 2;
            customY -= (newMainH - prevMainH) / 2;
            scale = newScale;
            saveSettings();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid scale! Use 0.5-4.0");
        }
    }

    public static void setBackgroundOpacity(int newOpacity) {
        ensureLoaded();
        if (newOpacity >= 0 && newOpacity <= 255) {
            backgroundOpacity = newOpacity;
            saveSettings();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid opacity! Use 0-255");
        }
    }

    public static void setBackgroundColor(int rgb) {
        ensureLoaded();
        backgroundColor = rgb & 0xFFFFFF;
        saveSettings();
    }

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static int getCustomX() {
        ensureLoaded();
        return customX;
    }

    public static int getCustomY() {
        ensureLoaded();
        return customY;
    }

    public static float getScale() {
        ensureLoaded();
        return scale;
    }

    public static int getBackgroundOpacity() {
        ensureLoaded();
        return backgroundOpacity;
    }

    public static int getBackgroundColor() {
        ensureLoaded();
        return backgroundColor;
    }

    public static boolean isForce() {
        ensureLoaded();
        return force;
    }

    public static boolean isShowRemoteHealth() {
        ensureLoaded();
        return showRemoteHealth;
    }

    public static boolean isShowRemoteFood() {
        return false;
    }

    public static boolean isShowRemoteXp() {
        ensureLoaded();
        return showRemoteXp;
    }

    public static boolean isShowHeldHighlight() {
        ensureLoaded();
        return showHeldHighlight;
    }

    public static boolean isShowDirection() {
        ensureLoaded();
        return showDirection;
    }

    public static boolean isDirectionVertical() {
        ensureLoaded();
        return directionVertical;
    }

    public static boolean isTextShadow() {
        ensureLoaded();
        return textShadow;
    }
}
