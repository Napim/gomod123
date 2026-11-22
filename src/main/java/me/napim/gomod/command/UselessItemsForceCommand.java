package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Toggles useless-item tint on Hypixel outside Blitz sidebar contexts (debug).
 */
public class UselessItemsForceCommand extends Command {

    public UselessItemsForceCommand() {
        super("uselessitemsforce");
    }

    @Override
    public void handle(String[] args) {
        boolean next = !ConfigManager.isUselessItemOverlayForceEverywhere();
        ConfigManager.setUselessItemOverlayForceEverywhere(next);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Useless-item overlay context: "
                + (next ? EnumChatFormatting.GREEN + "all Hypixel (not only Blitz sidebar)" : EnumChatFormatting.RED + "Blitz SG sidebar contexts only")
                + EnumChatFormatting.GRAY + " — still requires useless overlay ON in settings.");
    }
}
