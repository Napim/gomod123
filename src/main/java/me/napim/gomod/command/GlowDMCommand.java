package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.DeathmatchGlow;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.stream.Collectors;

/**
 * Command to toggle the deathmatch border glow per map.
 *
 * Usage:
 *   /glowdm <map>   — activate glow for the given map (e.g. gulch)
 *   /glowdm off     — disable the glow
 *   /glowdm list    — list all registered maps
 */
public class GlowDMCommand extends Command {

    public GlowDMCommand() {
        super("glowdm");
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
                DeathmatchGlow.deactivate();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Deathmatch glow " + EnumChatFormatting.BOLD + "disabled" + EnumChatFormatting.RESET + EnumChatFormatting.RED + ".");
                break;

            case "list": {
                String names = DeathmatchGlow.getMapNames().stream()
                        .sorted()
                        .collect(Collectors.joining(EnumChatFormatting.GRAY + ", " + EnumChatFormatting.WHITE));
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Available maps: " + EnumChatFormatting.WHITE + names);
                break;
            }

            case "edit": {
                // Usage: /glowdm edit <map> <centerX> <centerZ> <radius>
                if (args.length < 5) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                            + "Usage: /glowdm edit <map> <centerX> <centerZ> <radius>");
                    return;
                }
                String mapKey = args[1];
                try {
                    double cx     = Double.parseDouble(args[2]);
                    double cz     = Double.parseDouble(args[3]);
                    int    radius = Integer.parseInt(args[4]);
                    // Display name: capitalise first letter of the key
                    String displayName = mapKey.substring(0, 1).toUpperCase() + mapKey.substring(1).toLowerCase();
                    DeathmatchGlow.editMap(mapKey, displayName, cx, cz, radius);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Map '" + EnumChatFormatting.WHITE + mapKey
                            + EnumChatFormatting.GREEN + "' saved — center (" + (int)cx + ", " + (int)cz
                            + "), radius " + radius);
                } catch (NumberFormatException e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Invalid number. Usage: /glowdm edit <map> <centerX> <centerZ> <radius>");
                }
                break;
            }

            default: {
                if (DeathmatchGlow.activate(sub)) {
                    DeathmatchGlow.MapBounds m = DeathmatchGlow.getActiveMap();
                    Main.sendMessage(Main.CHAT_PREFIX
                            + EnumChatFormatting.GREEN + "Deathmatch glow enabled for "
                            + EnumChatFormatting.WHITE + m.displayName
                            + EnumChatFormatting.GRAY  + " (center " + (int)m.centerX + ", " + (int)m.centerZ
                            + " | radius " + m.radius + ")");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown map: " + EnumChatFormatting.WHITE + args[0]);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Use "
                            + EnumChatFormatting.WHITE + "/glowdm list"
                            + EnumChatFormatting.YELLOW + " to see available maps.");
                }
                break;
            }
        }
    }

    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "=== /glowdm ===");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/glowdm <map>"
                + EnumChatFormatting.GRAY + " — activate deathmatch border glow");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/glowdm edit <map> <centerX> <centerZ> <radius>"
                + EnumChatFormatting.GRAY + " — create / update map and save to JSON");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/glowdm off"
                + EnumChatFormatting.GRAY + " — disable glow");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/glowdm list"
                + EnumChatFormatting.GRAY + " — show registered maps");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Maps: "
                + EnumChatFormatting.WHITE + DeathmatchGlow.getMapNames().stream().sorted().collect(Collectors.joining(", ")));
    }
}
