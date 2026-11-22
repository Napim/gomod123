package me.napim.gomod.command;

import me.napim.gomod.features.KillCounter;
import me.napim.gomod.Main;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Command handler for /stopkc command.
 */
public class StopKillCounterCommand extends Command {
    
    public StopKillCounterCommand() {
        super("stopkc");
    }
    
    @Override
    public void handle(String[] args) {
        if (KillCounter.isEnabled()) {
            KillCounter.stop();
            Main.sendMessage(EnumChatFormatting.RED + "Kill counter stopped!");
        } else {
            Main.sendMessage(EnumChatFormatting.YELLOW + "Kill counter is not running!");
        }
    }
}
