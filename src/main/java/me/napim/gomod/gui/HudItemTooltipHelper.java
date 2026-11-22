package me.napim.gomod.gui;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;

/**
 * Shared item tooltip formatting for HUD / teammate tracker previews.
 */
public final class HudItemTooltipHelper {

    private HudItemTooltipHelper() {
    }

    /**
     * Aqua {@code Stone Sword} for teammate tracker, keeping vanilla advanced-tooltip suffix
     * from F3+H (e.g. {@code (#0272)} appended to the display name).
     */
    public static String formatStoneSwordFirstLine(String vanillaFirstLine) {
        return formatStoneSwordFirstLine(vanillaFirstLine, null, false);
    }

    public static String formatStoneSwordFirstLine(String vanillaFirstLine, ItemStack stack, boolean advancedItemTooltips) {
        String base = EnumChatFormatting.AQUA + "Stone Sword";
        if (vanillaFirstLine != null && !vanillaFirstLine.isEmpty()) {
            int marker = vanillaFirstLine.indexOf(" (#");
            if (marker < 0) {
                int open = vanillaFirstLine.indexOf('(');
                if (open > 0 && vanillaFirstLine.indexOf('#', open) > open) {
                    marker = open;
                }
            }
            if (marker >= 0) {
                // Reset color so F3+H id suffix stays vanilla white, not aqua.
                return base + EnumChatFormatting.RESET + vanillaFirstLine.substring(marker);
            }
        }
        if (advancedItemTooltips && stack != null && stack.getItem() != null) {
            int id = Item.getIdFromItem(stack.getItem());
            return base + EnumChatFormatting.RESET + " (#" + String.format("%04d", id) + ")";
        }
        return base;
    }

    public static void applyStoneSwordFirstLineColor(List<String> lines) {
        applyStoneSwordFirstLineColor(lines, null, false);
    }

    public static void applyStoneSwordFirstLineColor(List<String> lines, ItemStack stack, boolean advancedItemTooltips) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        lines.set(0, formatStoneSwordFirstLine(lines.get(0), stack, advancedItemTooltips));
    }
}
