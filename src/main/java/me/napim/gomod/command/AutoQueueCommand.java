package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;
import me.napim.gomod.features.AutoQueue;
import static me.napim.gomod.Main.sendMessage;
import net.minecraft.util.EnumChatFormatting;

public class AutoQueueCommand extends Command {
    public AutoQueueCommand() {
        super("autoqueue");
    }

    @Override
    public void handle(String[] args) {
        AutoQueue.setEnabled(!AutoQueue.isEnabled());
    }
}
