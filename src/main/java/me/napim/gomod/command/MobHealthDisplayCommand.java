package me.napim.gomod.command;

import me.napim.gomod.features.MobHealthDisplay;
import net.weavemc.loader.api.command.Command;
import net.minecraft.util.EnumChatFormatting;

import static me.napim.gomod.Main.sendMessage;

/**
 * Command to toggle the mob health display feature
 * Usage: /mobhealth
 */
public class MobHealthDisplayCommand extends Command {

    public MobHealthDisplayCommand() {
        super("mobhealth", "mh");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            // Toggle the feature
            boolean newState = !MobHealthDisplay.isEnabled();
            MobHealthDisplay.setEnabled(newState);
            
            String status = newState ? 
                EnumChatFormatting.GREEN + "enabled" : 
                EnumChatFormatting.RED + "disabled";
            
            sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.RESET +
                    EnumChatFormatting.YELLOW + "Mob Health Display " + status + EnumChatFormatting.RESET);
        } else {
            // Show usage
            sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.RESET +
                    EnumChatFormatting.YELLOW + "Usage: /mobhealth or /mh (toggle mob health display)" + 
                    EnumChatFormatting.RESET);
        }
    }
}

