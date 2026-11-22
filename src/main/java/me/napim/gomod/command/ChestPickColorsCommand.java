package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.UselessItemOverlay;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Sets ARGB tints for Inventory QoL chest pick hints (blue = pick, yellow = skip).
 * <p>
 * Hex: {@code AARRGGBB} (8 digits) or {@code RRGGBB} (6 digits; fill uses alpha 0x40, edge 0xD0).
 */
public class ChestPickColorsCommand extends Command {

    public ChestPickColorsCommand() {
        super("chestpickcolors");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            sendHelp();
            return;
        }
        String a0 = args[0].toLowerCase(java.util.Locale.ROOT);
        if ("help".equals(a0) || "?".equals(a0)) {
            sendHelp();
            return;
        }
        if ("status".equals(a0)) {
            sendStatus();
            return;
        }
        if ("reset".equals(a0)) {
            ConfigManager.resetUselessChestHintColorsToDefault();
            UselessItemOverlay.refreshChestPickHintColorsFromConfig();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Chest pick hint colors reset to defaults.");
            sendStatus();
            return;
        }
        if ("blue".equals(a0) || "pick".equals(a0)) {
            if (args.length < 3) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Usage: /chestpickcolors blue <fillHex> <edgeHex>");
                return;
            }
            applyPair(true, args[1], args[2]);
            return;
        }
        if ("yellow".equals(a0) || "skip".equals(a0)) {
            if (args.length < 3) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Usage: /chestpickcolors yellow <fillHex> <edgeHex>");
                return;
            }
            applyPair(false, args[1], args[2]);
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown subcommand. Try /chestpickcolors help");
    }

    private static void applyPair(boolean blue, String fillRaw, String edgeRaw) {
        if (blue) {
            if (!ConfigManager.setUselessChestHintBlueFillFromUserHex(fillRaw)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid fill hex: " + fillRaw);
                return;
            }
            if (!ConfigManager.setUselessChestHintBlueEdgeFromUserHex(edgeRaw)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid edge hex: " + edgeRaw);
                return;
            }
        } else {
            if (!ConfigManager.setUselessChestHintYellowFillFromUserHex(fillRaw)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid fill hex: " + fillRaw);
                return;
            }
            if (!ConfigManager.setUselessChestHintYellowEdgeFromUserHex(edgeRaw)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid edge hex: " + edgeRaw);
                return;
            }
        }
        UselessItemOverlay.refreshChestPickHintColorsFromConfig();
        String label = blue ? "Blue (pick)" : "Yellow (skip)";
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + label + " colors updated.");
        sendStatus();
    }

    private static void sendStatus() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Pick (blue): fill #"
                + ConfigManager.getUselessChestHintBlueFillHexForDisplay() + " edge #"
                + ConfigManager.getUselessChestHintBlueEdgeHexForDisplay());
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Skip (yellow): fill #"
                + ConfigManager.getUselessChestHintYellowFillHexForDisplay() + " edge #"
                + ConfigManager.getUselessChestHintYellowEdgeHexForDisplay());
    }

    private static void sendHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "/chestpickcolors "
                + EnumChatFormatting.WHITE + "— chest pick-hint tint (Inventory QoL, chest GUIs)");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Hex: "
                + EnumChatFormatting.WHITE + "AARRGGBB " + EnumChatFormatting.GRAY + "(8 digits) or "
                + EnumChatFormatting.WHITE + "RRGGBB " + EnumChatFormatting.GRAY
                + "(6 digits; fill alpha 0x40, edge alpha 0xD0). Optional # prefix.");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "/chestpickcolors blue <fill> <edge>"
                + EnumChatFormatting.DARK_GRAY + " | " + EnumChatFormatting.GRAY + "yellow <fill> <edge>");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "/chestpickcolors status"
                + EnumChatFormatting.DARK_GRAY + " | " + EnumChatFormatting.GRAY + "reset");
    }
}
