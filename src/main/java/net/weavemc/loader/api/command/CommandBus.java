package net.weavemc.loader.api.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CommandBus {
    public static final List<Command> commands = Collections.synchronizedList(new ArrayList<>());
    private static volatile Registrar platformRegistrar;

    /**
     * Platform hook used by the native Weave build. Keeping the gomod command model behind
     * this tiny boundary lets the Forge and Weave jars share every command implementation.
     */
    public interface Registrar {
        void register(Command command);
    }

    private CommandBus() {
    }

    public static void setPlatformRegistrar(Registrar registrar) {
        platformRegistrar = registrar;
    }

    public static void register(Command command) {
        if (command == null) {
            return;
        }
        if (isRegistered(command)) {
            return;
        }
        Registrar registrar = platformRegistrar;
        if (registrar != null) {
            registrar.register(command);
            addRegistered(command);
            return;
        }

        // Forge is deliberately optional: the native Weave jar does not ship or load Forge.
        // Reflection also keeps this compatibility API linkable on Lunar Client.
        try {
            Class<?> handlerClass = Class.forName("net.minecraftforge.client.ClientCommandHandler");
            Object handler = handlerClass.getField("instance").get(null);
            handlerClass.getMethod("registerCommand", net.minecraft.command.ICommand.class)
                    .invoke(handler, command);
            addRegistered(command);
        } catch (Throwable t) {
            throw new IllegalStateException("No command registrar is available", t);
        }
    }

    private static boolean isRegistered(Command command) {
        synchronized (commands) {
            for (Command existing : commands) {
                if (existing == command) {
                    return true;
                }
                if (existing != null && existing.getCommandName().equals(command.getCommandName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addRegistered(Command command) {
        synchronized (commands) {
            if (!isRegistered(command)) {
                commands.add(command);
            }
        }
    }
}
