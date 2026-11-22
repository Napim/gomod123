package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class TooltipDebugProxyCommand extends Command {
    public TooltipDebugProxyCommand() {
        super("tooltipdebug");
    }

    @Override
    public void handle(String[] args) {
        try {
            Class<?> loggerClass = Class.forName("me.napim.gomod.features.TooltipCaptureLogger");

            if (args.length == 0) {
                boolean enabled = (Boolean) loggerClass.getMethod("toggleFromCommand").invoke(null);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tooltip debug "
                        + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
                return;
            }

            String action = args[0] == null ? "" : args[0].trim().toLowerCase();
            if ("on".equals(action)) {
                loggerClass.getMethod("setEnabled", boolean.class).invoke(null, true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tooltip debug "
                        + EnumChatFormatting.GREEN + "enabled");
                return;
            }
            if ("off".equals(action)) {
                loggerClass.getMethod("setEnabled", boolean.class).invoke(null, false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tooltip debug "
                        + EnumChatFormatting.RED + "disabled");
                return;
            }
            if ("status".equals(action)) {
                boolean enabled = (Boolean) loggerClass.getMethod("isEnabled").invoke(null);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tooltip debug is "
                        + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
                return;
            }

            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /tooltipdebug [on|off|status]");
        } catch (Throwable ignored) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Tooltip debug command failed.");
        }
    }
}
