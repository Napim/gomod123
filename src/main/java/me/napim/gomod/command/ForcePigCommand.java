package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ForcePigManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * Toggle Blitz-style sneak+attack with compass: two client-only fast saddled pigs (~3s).
 */
public class ForcePigCommand extends Command {
    public ForcePigCommand() {
        super("forcepig");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            ForcePigManager.toggle();
        } else {
            String a = args[0].trim().toLowerCase(Locale.ROOT);
            if ("speed".equals(a)) {
                if (args.length == 1) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Force pig speed: "
                            + EnumChatFormatting.WHITE
                            + String.format(Locale.ROOT, "%.2f", ForcePigManager.getMoveSpeedBlocksPerSec())
                            + EnumChatFormatting.GRAY + " blocks/sec");
                    return;
                }
                try {
                    double speed = Double.parseDouble(args[1]);
                    ForcePigManager.setMoveSpeedBlocksPerSec(speed);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Force pig speed set to "
                            + EnumChatFormatting.WHITE
                            + String.format(Locale.ROOT, "%.2f", ForcePigManager.getMoveSpeedBlocksPerSec())
                            + EnumChatFormatting.GRAY + " blocks/sec");
                } catch (Throwable ignored) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /forcepig speed <value>");
                }
                return;
            } else if ("on".equals(a) || "1".equals(a) || "true".equals(a)) {
                ForcePigManager.setEnabled(true);
            } else if ("off".equals(a) || "0".equals(a) || "false".equals(a)) {
                ForcePigManager.setEnabled(false);
            } else if ("toggle".equals(a)) {
                ForcePigManager.toggle();
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /forcepig [on|off|toggle|speed <value>]");
                return;
            }
        }
        Main.sendMessage(Main.CHAT_PREFIX + (ForcePigManager.isEnabled()
                ? EnumChatFormatting.GREEN + "Force pig: ON " + EnumChatFormatting.GRAY + "(sneak + attack with compass)"
                : EnumChatFormatting.RED + "Force pig: OFF"));
    }
}
