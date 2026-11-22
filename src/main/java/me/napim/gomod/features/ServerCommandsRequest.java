package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C14PacketTabComplete;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import net.weavemc.loader.api.command.CommandBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class ServerCommandsRequest {
    private static final long TIMEOUT_MS = 5000L;
    private static volatile long pendingSinceMs = 0L;

    private ServerCommandsRequest() {
    }

    public static void request() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.sendQueue == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a server first.");
            return;
        }

        long now = System.currentTimeMillis();
        if (isPending(now)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Still waiting for the server command list...");
            return;
        }

        pendingSinceMs = now;
        try {
            mc.thePlayer.sendQueue.addToSendQueue(new C14PacketTabComplete("/", (BlockPos) null));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Asking the server for its command list...");
        } catch (Throwable t) {
            pendingSinceMs = 0L;
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Couldn't request server commands: " + t.getMessage());
        }
    }

    public static boolean handleTabComplete(String[] matches) {
        long pendingAt = pendingSinceMs;
        if (pendingAt <= 0L) {
            return false;
        }
        pendingSinceMs = 0L;
        printCommands(matches);
        return true;
    }

    private static boolean isPending(long now) {
        long pendingAt = pendingSinceMs;
        if (pendingAt <= 0L) {
            return false;
        }
        if (now - pendingAt > TIMEOUT_MS) {
            pendingSinceMs = 0L;
            return false;
        }
        return true;
    }

    private static void printCommands(String[] matches) {
        List<String> commands = normalize(matches);
        if (commands.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "The server didn't return any commands.");
            return;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Server commands (" + commands.size() + "):");
        StringBuilder line = new StringBuilder();
        for (String command : commands) {
            String entry = "/" + command;
            if (line.length() == 0) {
                line.append(entry);
            } else if (line.length() + entry.length() + 2 > 220) {
                Main.sendMessage(EnumChatFormatting.GRAY + line.toString());
                line.setLength(0);
                line.append(entry);
            } else {
                line.append(", ").append(entry);
            }
        }
        if (line.length() > 0) {
            Main.sendMessage(EnumChatFormatting.GRAY + line.toString());
        }
    }

    private static List<String> normalize(String[] matches) {
        if (matches == null || matches.length == 0) {
            return Collections.emptyList();
        }

        Set<String> clientCommands = getClientCommands();
        Set<String> commands = new TreeSet<String>();
        for (String match : matches) {
            String command = normalizeOne(match);
            if (!command.isEmpty() && !clientCommands.contains(command)) {
                commands.add(command);
            }
        }
        return new ArrayList<String>(commands);
    }

    private static Set<String> getClientCommands() {
        Set<String> names = new TreeSet<String>();
        synchronized (CommandBus.commands) {
            for (Command command : CommandBus.commands) {
                if (command == null) {
                    continue;
                }
                addCommandName(names, command.getCommandName());
                List<String> aliases = command.getCommandAliases();
                if (aliases != null) {
                    for (String alias : aliases) {
                        addCommandName(names, alias);
                    }
                }
            }
        }
        return names;
    }

    private static void addCommandName(Set<String> names, String name) {
        String normalized = normalizeOne(name);
        if (!normalized.isEmpty()) {
            names.add(normalized);
        }
    }

    private static String normalizeOne(String match) {
        if (match == null) {
            return "";
        }
        String command = match.trim();
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        int space = command.indexOf(' ');
        if (space >= 0) {
            command = command.substring(0, space);
        }
        command = command.trim().toLowerCase(Locale.ROOT);
        return command;
    }
}
