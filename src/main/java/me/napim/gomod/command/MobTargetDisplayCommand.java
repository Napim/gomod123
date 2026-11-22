package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.MobTargetDisplay;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Toggles mob target display. Marker text is fixed to {@code !}.
 */
public class MobTargetDisplayCommand extends Command {

    public MobTargetDisplayCommand() {
        super("mobtarget", "mt");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            MobTargetDisplay.toggle();
            Main.sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.RESET
                    + EnumChatFormatting.YELLOW + "Mob target display "
                    + (MobTargetDisplay.isEnabled() ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled")
                    + EnumChatFormatting.RESET);
            return;
        }

        String a0 = args[0].trim();
        if (args.length == 1 && (a0.equalsIgnoreCase("help") || a0.equalsIgnoreCase("?"))) {
            Main.sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.YELLOW
                    + "/mobtarget — toggle");
            return;
        }
        Main.sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.YELLOW
                + "Use /mobtarget to toggle. The marker is always "
                + EnumChatFormatting.DARK_RED + EnumChatFormatting.BOLD + "!"
                + EnumChatFormatting.RESET + EnumChatFormatting.YELLOW + ".");
    }
}
