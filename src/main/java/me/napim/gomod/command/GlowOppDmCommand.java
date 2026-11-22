package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.DeathmatchGlow;
import me.napim.gomod.features.DeathmatchOppZone;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.stream.Collectors;

/**
 * Command for the outside-zone red overlay.
 *
 * Usage:
 *   /glowoppdm <map>   — activate red overlay outside the given map's zone
 *   /glowoppdm off     — disable the overlay
 *   /glowoppdm list    — list registered maps
 */
public class GlowOppDmCommand extends Command {

    public GlowOppDmCommand() {
        super("glowoppdm");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            showHelp();
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "off":
                DeathmatchOppZone.deactivate();
                Main.sendMessage(Main.CHAT_PREFIX
                        + EnumChatFormatting.RED + "Outside zone overlay "
                        + EnumChatFormatting.BOLD + "disabled" + EnumChatFormatting.RESET
                        + EnumChatFormatting.RED + ".");
                break;

            case "list": {
                String names = DeathmatchGlow.getMapNames().stream()
                        .sorted()
                        .collect(Collectors.joining(
                                EnumChatFormatting.GRAY + ", " + EnumChatFormatting.WHITE));
                Main.sendMessage(Main.CHAT_PREFIX
                        + EnumChatFormatting.AQUA + "Available maps: "
                        + EnumChatFormatting.WHITE + names);
                break;
            }

            default: {
                if (DeathmatchOppZone.activate(sub)) {
                    DeathmatchGlow.MapBounds m = DeathmatchOppZone.getActiveMap();
                    Main.sendMessage(Main.CHAT_PREFIX
                            + EnumChatFormatting.GREEN + "Outside zone overlay enabled for "
                            + EnumChatFormatting.WHITE + m.displayName
                            + EnumChatFormatting.GRAY  + " (center " + (int) m.centerX
                            + ", " + (int) m.centerZ + " | radius " + m.radius + ")");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX
                            + EnumChatFormatting.RED + "Unknown map: "
                            + EnumChatFormatting.WHITE + args[0]);
                    Main.sendMessage(Main.CHAT_PREFIX
                            + EnumChatFormatting.YELLOW + "Use "
                            + EnumChatFormatting.WHITE + "/glowoppdm list"
                            + EnumChatFormatting.YELLOW + " to see available maps.");
                }
                break;
            }
        }
    }

    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "=== /glowoppdm ===");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/glowoppdm <map>"
                + EnumChatFormatting.GRAY + " — activate red outside-zone overlay");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/glowoppdm off"
                + EnumChatFormatting.GRAY + " — disable overlay");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/glowoppdm list"
                + EnumChatFormatting.GRAY + " — show registered maps");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Maps: "
                + EnumChatFormatting.WHITE
                + DeathmatchGlow.getMapNames().stream().sorted().collect(Collectors.joining(", ")));
    }
}
