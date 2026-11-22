package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.ForceBlitzHourDebug;
import me.napim.gomod.features.SidebarBlitzHourReminder;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Debug: simulate Hypixel {@code BLITZ HOUR} on the sidebar.
 * <ul>
 *   <li>{@code /forceblitzhour} — toggle ON/OFF</li>
 *   <li>{@code /forceblitzhour status} — detection + reminder state</li>
 * </ul>
 */
public final class ForceBlitzHourCommand extends Command {

    public ForceBlitzHourCommand() {
        super("forceblitzhour");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[ForceBlitzHour] " + ForceBlitzHourDebug.statusLine());
            if (mc != null) {
                Main.sendMessage(EnumChatFormatting.GRAY + "  Hypixel: "
                        + (GameContext.isOnHypixel(mc) ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                        + EnumChatFormatting.GRAY + "  remembered game: "
                        + (SidebarBlitzHourReminder.isRememberedBlitzHourGame()
                        ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
            }
            Main.sendMessage(EnumChatFormatting.GRAY + "  Sidebar Blitz Hour line (setting): "
                    + (ConfigManager.isSidebarBlitzHourLineEffective()
                    ? EnumChatFormatting.GREEN + "effective" : EnumChatFormatting.RED + "off (enable hide-scoreboard + Blitz Hour line)"));
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "  Injects a yellow BLITZ HOUR row when the server sidebar has none.");
            return;
        }
        if (args.length == 1 && ("help".equalsIgnoreCase(args[0]) || "?".equals(args[0]))) {
            printHelp();
            return;
        }
        if (args.length >= 1) {
            if ("on".equalsIgnoreCase(args[0])) {
                ForceBlitzHourDebug.setForced(true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "[ForceBlitzHour] ON");
                return;
            }
            if ("off".equalsIgnoreCase(args[0])) {
                ForceBlitzHourDebug.setForced(false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "[ForceBlitzHour] OFF");
                return;
            }
            printHelp();
            return;
        }

        ForceBlitzHourDebug.toggle();
        Main.sendMessage(Main.CHAT_PREFIX + (ForceBlitzHourDebug.isForced()
                ? EnumChatFormatting.GREEN + "[ForceBlitzHour] ON — sidebar acts like Blitz Hour."
                : EnumChatFormatting.RED + "[ForceBlitzHour] OFF"));
    }

    private static void printHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/forceblitzhour §7— toggle ON/OFF");
        Main.sendMessage(EnumChatFormatting.GRAY + "  /forceblitzhour status §7— live vs forced detection");
        Main.sendMessage(EnumChatFormatting.GRAY + "  /forceblitzhour on|off");
    }
}
