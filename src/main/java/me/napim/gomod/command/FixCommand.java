package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.ForcePigManager;
import me.napim.gomod.features.GlobalFixMode;
import me.napim.gomod.features.WitchAutoSound;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Unified fix profile switch for witch/forcepig/chest-edit input handling.
 * Usage: /fix 1..8
 */
public class FixCommand extends Command {

    public FixCommand() {
        super("fix");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            int cur = GlobalFixMode.getMode();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Current shared fix mode: " + cur + " — /fix 1..8");
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + GlobalFixMode.describe(cur));
            Main.sendMessage(EnumChatFormatting.YELLOW + "[WitchSound] "
                    + EnumChatFormatting.WHITE + WitchAutoSound.debugState());
            return;
        }
        int m;
        try {
            m = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /fix 1..8");
            return;
        }
        if (m < 1 || m > 8) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Use a mode from 1 to 8.");
            return;
        }
        GlobalFixMode.setMode(m);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Applied /fix mode " + m);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + GlobalFixMode.describe(m));
        long ago = System.currentTimeMillis() - ClientTickHub.getLastRunWallMs();
        Main.sendMessage(EnumChatFormatting.YELLOW + "[TickHub] " + EnumChatFormatting.WHITE + "runs=" + ClientTickHub.getTotalRuns()
                + " lastMsAgo=" + (ClientTickHub.getLastRunWallMs() <= 0 ? "never" : ago));
        Main.sendMessage(EnumChatFormatting.YELLOW + "[ForcePig] " + EnumChatFormatting.WHITE + ForcePigManager.debugState());
        Main.sendMessage(EnumChatFormatting.YELLOW + "[WitchSound] " + EnumChatFormatting.WHITE + WitchAutoSound.debugState());
        Main.sendMessage(EnumChatFormatting.YELLOW + "[CE Edit] " + EnumChatFormatting.WHITE + ChestRouteManager.debugEditState());
    }
}

