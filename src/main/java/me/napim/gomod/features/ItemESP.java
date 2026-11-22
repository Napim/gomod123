package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.util.EnumChatFormatting;

/**
 * Feature to display item names above items on the ground.
 * <p>
 * Labels use {@link me.napim.gomod.render.VanillaEntityLabelRenderer} (depth-off text pass) so they stay visible when
 * the item is behind terrain, as long as the entity is still being rendered. Names show for 4 seconds after the item
 * entity spawns (first 80 ticks / 4 seconds of {@code EntityItem} age); the last 10 ticks shrink and fade the label. Teammate tagging for Blitz team loot is
 * implemented in {@link me.napim.gomod.mixins.RenderEntityItemMixin}: {@code EntityItem#getThrower()} matching the
 * current teammate name, else a short proximity heuristic for young drops when thrower is missing. The same
 * classification runs while full ItemESP is on ({@code /itemesp}). Nametag colors match teammate loot ESP (green item
 * name, gray stack count; {@code [TEAM]} prefix only when the drop is classified as the teammate's).
 */
public final class ItemESP {
    private static boolean enabled = false;
    private static boolean teamLootEnabled = true;
    
    private ItemESP() {}
    
    /**
     * Toggles the ItemESP feature
     */
    public static String toggle() {
        enabled = !enabled;
        String status = enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled";
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "ItemESP " + status;
    }
    
    /**
     * Sets the enabled state
     */
    public static void setEnabled(boolean enabled) {
        ItemESP.enabled = enabled;
    }

    public static void loadSettings() {
        teamLootEnabled = ConfigManager.isTeamLootEnabled();
    }

    /** Extra vertical offset in blocks for item labels (nametag height); persisted in config. */
    public static float getLabelExtraYOffset() {
        return ConfigManager.getItemEspLabelExtraYOffset();
    }

    public static void setLabelExtraYOffset(float blocks) {
        ConfigManager.setItemEspLabelExtraYOffset(blocks);
    }

    public static void setTeamLootEnabled(boolean enabled) {
        teamLootEnabled = enabled;
        ConfigManager.setTeamLootEnabled(enabled);
    }

    /**
     * Gets whether ItemESP is enabled
     */
    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isTeamLootEnabled() {
        return teamLootEnabled;
    }
}
