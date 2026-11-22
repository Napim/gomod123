package net.weavemc.loader.api.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public abstract class Command extends CommandBase {
    public final String name;
    public final List<String> aliases;

    protected Command(String name, String... aliases) {
        this.name = name;
        if (aliases != null && aliases.length > 0) {
            this.aliases = Arrays.asList(aliases);
        } else {
            this.aliases = Collections.emptyList();
        }
    }

    @Override
    public String getCommandName() {
        return name;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + name;
    }

    @Override
    public List<String> getCommandAliases() {
        return aliases;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        try {
            handle(args);
        } catch (Throwable t) {
            try {
                sender.addChatMessage(new ChatComponentText("[gomod] Command error: " + t.getMessage()));
            } catch (Throwable ignored) {
            }
            t.printStackTrace();
        }
    }

    public abstract void handle(String[] args);
}
