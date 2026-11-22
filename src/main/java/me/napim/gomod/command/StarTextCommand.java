package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import static me.napim.gomod.Main.sendMessage;

public class StarTextCommand extends Command {

    public StarTextCommand() {
        super("togglestartext");
    }

    @Override
    public void handle(String[] args) {
        boolean newState = !ConfigManager.isStarTextEnabled();
        ConfigManager.setStarTextEnabled(newState);
        
        if (newState) {
            sendMessage(Main.CHAT_PREFIX + "Better BLITZ texts " + EnumChatFormatting.GREEN + "enabled");
        } else {
            sendMessage(Main.CHAT_PREFIX + "Better BLITZ texts " + EnumChatFormatting.RED + "disabled");
        }
    }
}

