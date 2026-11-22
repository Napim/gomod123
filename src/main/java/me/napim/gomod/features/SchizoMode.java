package me.napim.gomod.features;

import net.minecraft.util.EnumChatFormatting;

import static me.napim.gomod.Main.CHAT_PREFIX;
import static me.napim.gomod.Main.sendMessage;

/**
 * Schizo mode: forces other players to face the local player while rendering.
 */
public class SchizoMode {
    private static boolean enabled = false; // Disabled by default

    /**
     * Checks if Schizo mode is enabled.
     * @return true if enabled, false otherwise
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether Schizo mode is enabled.
     * @param value true to enable, false to disable
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        ConfigManager.setSchizoEnabled(value);

        String status = enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled";
        sendMessage(CHAT_PREFIX + "Schizo " + status);
    }

    /**
     * Set enabled state without sending a chat message.
     * Used by GUIs to avoid chat spam.
     */
    public static void setEnabledSilent(boolean value) {
        enabled = value;
        ConfigManager.setSchizoEnabled(value);
    }

    /**
     * Loads the Schizo setting from config.
     */
    public static void loadSetting() {
        enabled = ConfigManager.isSchizoEnabled();
    }
}
