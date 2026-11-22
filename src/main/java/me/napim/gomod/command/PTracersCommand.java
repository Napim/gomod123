package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.PlayerTracers;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * /ptracers — draws a tracer line from every visible player's head toward the block they are looking at,
 * with a bracket reticle at the hit face. Glass, glass panes, stained glass / panes, and barrier
 * blocks are treated as transparent (rays pass through them).
 *
 * Usage:
 *   /ptracers              — toggle on/off
 *   /ptracers self <on|off>  — include/exclude the local player's own tracer (default: off)
 *   /ptracers range <n>      — set max ray-cast range in blocks (8–256, default 64)
 */
public class PTracersCommand extends Command {

    public PTracersCommand() {
        super("ptracers");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            PlayerTracers.toggle();
            Main.sendMessage(Main.CHAT_PREFIX + (PlayerTracers.isEnabled()
                    ? EnumChatFormatting.GREEN  + "Player tracers " + EnumChatFormatting.BOLD + "ENABLED."
                    : EnumChatFormatting.RED    + "Player tracers " + EnumChatFormatting.BOLD + "DISABLED."));
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("debug".equals(sub)) {
            CageTracerDebugCommand.run(args.length > 1 ? args[1] : "status");
            return;
        }

        if ("self".equals(sub)) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Self tracer is " + (PlayerTracers.isShowSelf()
                        ? EnumChatFormatting.GREEN + "ON"
                        : EnumChatFormatting.RED   + "OFF"));
                return;
            }
            boolean on = "on".equals(args[1]) || "true".equals(args[1]) || "1".equals(args[1]);
            PlayerTracers.setShowSelf(on);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA
                    + "Self tracer: " + (on
                    ? EnumChatFormatting.GREEN + "ON"
                    : EnumChatFormatting.RED   + "OFF"));
            return;
        }

        if ("range".equals(sub)) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Current range: " + (int) PlayerTracers.getMaxRange() + " blocks");
                return;
            }
            try {
                double range = Double.parseDouble(args[1]);
                PlayerTracers.setMaxRange(range);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA
                        + "Tracer range set to " + (int) PlayerTracers.getMaxRange() + " blocks.");
            } catch (NumberFormatException e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Invalid range: " + args[1]);
            }
            return;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Usage: /ptracers [self <on|off>] [range <blocks>] [debug start|stop|status]");
    }
}
