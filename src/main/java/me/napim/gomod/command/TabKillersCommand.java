package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.KillCounter;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Toggles grouping dead players under their killer on TAB (Blitz in-game), with dim spectator-blue names.
 */
public class TabKillersCommand extends Command {
    public TabKillersCommand() {
        super("tabvictims", new String[] { "tabkillers" });
    }

    @Override
    public void handle(String[] args) {
        if (args != null && args.length >= 2
                && "victims".equalsIgnoreCase(args[0])
                && ("dim".equalsIgnoreCase(args[1]) || "dimmed".equalsIgnoreCase(args[1])
                    || "italic".equalsIgnoreCase(args[1]))) {
            handleVictimDimArgs(args);
            return;
        }
        if (args != null && args.length >= 2
                && "killcount".equalsIgnoreCase(args[0])
                && "offset".equalsIgnoreCase(args[1])) {
            handleKillCountOffsetArgs(args);
            return;
        }
        if (args != null && args.length >= 1 && "offset".equalsIgnoreCase(args[0])) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "TAB killers victim head offset is now fixed at "
                    + EnumChatFormatting.AQUA + "8"
                    + EnumChatFormatting.GRAY + " px per depth.");
            return;
        }
        KillCounter.toggleTabKillersEnabled(true);
    }

    private void handleKillCountOffsetArgs(String[] args) {
        if (args.length == 2) {
            boolean next = !ConfigManager.isKillCounterTabKillersVictimKillCountAligned();
            ConfigManager.setKillCounterTabKillersVictimKillCountAligned(next);
            KillCounter.forceTabRefresh();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "TAB killers victim kill-count alignment: "
                    + (next ? EnumChatFormatting.GREEN + "SAME COLUMN" : EnumChatFormatting.RED + "FOLLOW CHAIN"));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Use " + EnumChatFormatting.WHITE + "/tabvictims killcount offset <px>"
                    + EnumChatFormatting.GRAY + " to fine-tune the extra shift (current "
                    + EnumChatFormatting.AQUA + ConfigManager.getKillCounterTabKillersVictimKillCountOffsetPx()
                    + EnumChatFormatting.GRAY + " px per depth).");
            return;
        }
        String raw = args[2] == null ? "" : args[2].trim();
        if ("on".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw) || "toggle".equalsIgnoreCase(raw)) {
            boolean next = "toggle".equalsIgnoreCase(raw)
                    ? !ConfigManager.isKillCounterTabKillersVictimKillCountAligned()
                    : "on".equalsIgnoreCase(raw);
            ConfigManager.setKillCounterTabKillersVictimKillCountAligned(next);
            KillCounter.forceTabRefresh();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "TAB killers victim kill-count alignment: "
                    + (next ? EnumChatFormatting.GREEN + "SAME COLUMN" : EnumChatFormatting.RED + "FOLLOW CHAIN"));
            return;
        }
        int px;
        try {
            px = Integer.parseInt(raw);
        } catch (Throwable ignored) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Usage: /tabvictims killcount offset [on|off|toggle|pixels]");
            return;
        }
        ConfigManager.setKillCounterTabKillersVictimKillCountOffsetPx(px);
        KillCounter.forceTabRefresh();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "TAB killers victim kill-count offset set to "
                + EnumChatFormatting.AQUA + ConfigManager.getKillCounterTabKillersVictimKillCountOffsetPx()
                + EnumChatFormatting.GRAY + " px per depth.");
    }

    private void handleVictimDimArgs(String[] args) {
        boolean next = !ConfigManager.isKillCounterTabKillersVictimDimmed();
        if (args.length >= 3) {
            String raw = args[2] == null ? "" : args[2].trim();
            if ("on".equalsIgnoreCase(raw)) {
                next = true;
            } else if ("off".equalsIgnoreCase(raw)) {
                next = false;
            } else if (!"toggle".equalsIgnoreCase(raw)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Usage: /tabvictims victims dim [on|off|toggle]");
                return;
            }
        }
        ConfigManager.setKillCounterTabKillersVictimDimmed(next);
        KillCounter.forceTabRefresh();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "TAB departed victims dimmed names: "
                + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
    }
}
