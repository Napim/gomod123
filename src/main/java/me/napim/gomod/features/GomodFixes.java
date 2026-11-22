package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.util.EnumChatFormatting;

/**
 * Client bug-fix toggles grouped under Miscellaneous → Fixes.
 */
public final class GomodFixes {

    private static final boolean WEAVE_RUNTIME = detectWeaveRuntime();

    private GomodFixes() {
    }

    public static boolean isHypixelTabDupeHideEnabled() {
        return ConfigManager.isHypixelTabDupeHideEnabled();
    }

    public static void setHypixelTabDupeHideEnabled(boolean enabled) {
        setHypixelTabDupeHideEnabled(enabled, false);
    }

    public static void setHypixelTabDupeHideEnabled(boolean enabled, boolean announce) {
        ConfigManager.setHypixelTabDupeHideEnabled(enabled);
        if (!enabled) {
            DeathmatchTabCleaner.clearDuplicateHides();
        }
        if (announce) {
            sendToggleMessage("Hide TAB duplicate names", enabled);
        }
    }

    public static boolean isHypixelTabQuitGhostHideEnabled() {
        return ConfigManager.isHypixelTabQuitGhostHideEnabled();
    }

    public static void setHypixelTabQuitGhostHideEnabled(boolean enabled) {
        setHypixelTabQuitGhostHideEnabled(enabled, false);
    }

    public static void setHypixelTabQuitGhostHideEnabled(boolean enabled, boolean announce) {
        ConfigManager.setHypixelTabQuitGhostHideEnabled(enabled);
        if (!enabled) {
            DeathmatchTabCleaner.clearQuitGhostHides();
        }
        if (announce) {
            sendToggleMessage("Force-clean dead players", enabled);
        }
    }

    public static boolean isNametagThroughChestsEnabled() {
        return ConfigManager.isNametagThroughBlocksEnabled();
    }

    public static void setNametagThroughChestsEnabled(boolean enabled) {
        ConfigManager.setNametagThroughBlocksEnabled(enabled);
    }

    public static boolean isThunderRenderFixEnabled() {
        return LightningSettings.isFixRenderEnabled();
    }

    public static void setThunderRenderFixEnabled(boolean enabled) {
        LightningSettings.setFixRenderEnabled(enabled);
    }

    public static boolean isWeaveRuntime() {
        return WEAVE_RUNTIME;
    }

    public static boolean isForceReplayPlayerHealthTagsEnabled() {
        return ConfigManager.isForceReplayPlayerHealthTagsEnabled();
    }

    public static void setForceReplayPlayerHealthTagsEnabled(boolean enabled) {
        ConfigManager.setForceReplayPlayerHealthTagsEnabled(enabled);
    }

    public static boolean isBlitzRektHitboxOverlayEnabled() {
        return ConfigManager.isBlitzRektHitboxOverlayEnabled();
    }

    public static void setBlitzRektHitboxOverlayEnabled(boolean enabled) {
        ConfigManager.setBlitzRektHitboxOverlayEnabled(enabled);
    }

    public static double getBlitzRektHitboxPreviewPadding(String side) {
        return ConfigManager.getBlitzRektHitboxPreviewPadding(side);
    }

    public static double adjustBlitzRektHitboxPreviewPadding(String side, double delta) {
        return ConfigManager.adjustBlitzRektHitboxPreviewPadding(side, delta);
    }

    public static void resetBlitzRektHitboxPreviewPadding() {
        ConfigManager.resetBlitzRektHitboxPreviewPadding();
    }

    private static boolean detectWeaveRuntime() {
        try {
            Class.forName("net.weavemc.api.ModInitializer", false, GomodFixes.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("net.weavemc.loader.api.ModInitializer", false, GomodFixes.class.getClassLoader());
            return !isForgeRuntime();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isForgeRuntime() {
        try {
            Class.forName("net.minecraftforge.common.MinecraftForge", false, GomodFixes.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sendToggleMessage(String label, boolean enabled) {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + label + ": "
                + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
    }
}
