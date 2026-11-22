package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.command.Command;
import net.weavemc.loader.api.command.CommandBus;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Fallback command dispatcher for environments where ChatSentEvent hooks are skipped.
 */
public final class CommandFallbackDispatcher {
    private static Field commandsField = null;

    private CommandFallbackDispatcher() {}

    /**
     * Dispatches a gomod slash command locally and records it in chat history.
     * Use from {@code EntityPlayerSP#sendChatMessage} so chat-click RUN_COMMAND actions never reach the server.
     */
    public static boolean dispatchOutgoingChat(String message) {
        if (!dispatchIfMatched(message)) {
            return false;
        }
        addToSentMessages(message);
        return true;
    }

    private static void addToSentMessages(String message) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().addToSentMessages(message);
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean dispatchIfMatched(String message) {
        if (message == null || message.isEmpty() || message.charAt(0) != '/') {
            return false;
        }
        if (message.length() == 1) {
            return false;
        }

        String[] parts = message.substring(1).split("\\s+");
        if (parts.length == 0) {
            return false;
        }

        String commandName = parts[0];
        String[] args;
        if (parts.length > 1) {
            args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
        } else {
            args = new String[0];
        }

        List<Command> commands = getCommands();
        if (commands == null || commands.isEmpty()) {
            return false;
        }

        boolean matched = false;
        for (Command command : commands) {
            if (command == null) continue;
            if (matches(command, commandName)) {
                matched = true;
                try {
                    command.handle(args);
                } catch (Throwable t) {
                    System.err.println("[gomod] Command dispatch failed for /" + commandName + ": " + t.getMessage());
                }
            }
        }
        return matched;
    }

    private static boolean matches(Command command, String name) {
        if (name == null) return false;
        if (command.name != null && command.name.equalsIgnoreCase(name)) {
            return true;
        }
        if (command.aliases != null) {
            for (String alias : command.aliases) {
                if (alias != null && alias.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Command> getCommands() {
        try {
            if (commandsField == null) {
                commandsField = CommandBus.class.getDeclaredField("commands");
                commandsField.setAccessible(true);
            }
            Object value = commandsField.get(null);
            if (value instanceof List) {
                return (List<Command>) value;
            }
        } catch (Throwable t) {
            System.err.println("[gomod] Unable to access CommandBus commands: " + t.getMessage());
        }
        return null;
    }
}
