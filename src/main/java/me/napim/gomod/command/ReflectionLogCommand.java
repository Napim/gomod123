package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ReflectionLogger;
import net.weavemc.loader.api.command.Command;
import net.minecraft.util.EnumChatFormatting;

/**
 * Command to show the path to the reflection log and basic usage.
 */
public class ReflectionLogCommand extends Command {
    public ReflectionLogCommand() {
        super("reflog");
    }

    @Override
    public void handle(String[] args) {
        String path = ReflectionLogger.getLogFilePath();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Reflection log: " + EnumChatFormatting.RESET + path);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tip: Share this file when reporting reflection errors.");
    }
}


