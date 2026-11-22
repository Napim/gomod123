package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * /lightningnerf &lt;blocks&gt; — clip the bottom of lightning bolts (client render only). /lightningnerf off to disable.
 */
public class LightningNerfCommand extends Command {
    public LightningNerfCommand() {
        super("lightningnerf");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        String a0 = args[0].trim().toLowerCase();
        if ("off".equals(a0) || "disable".equals(a0) || "false".equals(a0)) {
            ConfigManager.setLightningNerfEnabled(false);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Thunder shorten disabled.");
            return;
        }
        try {
            int blocks = Integer.parseInt(args[0].trim());
            if (blocks <= 0) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Block count must be a positive integer.");
                return;
            }
            if (blocks > 64) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Maximum is 64 blocks.");
                return;
            }
            ConfigManager.setLightningNerfBottomBlocks(blocks);
            ConfigManager.setLightningNerfEnabled(true);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Thunder shorten on: hiding the bottom "
                    + blocks + " block(s) of each strike (client only). " + EnumChatFormatting.GRAY
                    + "Open Miscellaneous \u2192 Thunder Settings for speed, volume, and color.");
        } catch (NumberFormatException e) {
            printUsage();
        }
    }

    private void printUsage() {
        boolean on = ConfigManager.isLightningNerfEnabled();
        int b = ConfigManager.getLightningNerfBottomBlocks();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /lightningnerf <blocks> | off");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Shortens thunder on your client. Speed, volume, and color are in Thunder Settings. Current: "
                + (on ? EnumChatFormatting.GREEN + "on (" + b + " blocks)" : EnumChatFormatting.RED + "off"));
    }
}
