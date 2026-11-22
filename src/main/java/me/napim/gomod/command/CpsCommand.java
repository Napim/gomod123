package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.CpsLimiter;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * Command to cap attack CPS.
 * Usage:
 *   /cps <number>
 *   /cps off
 *   /cps status
 */
public class CpsCommand extends Command {
    public CpsCommand() {
        super("cps");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sendStatus();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /cps <number|off>");
            return;
        }

        String raw = args[0].trim().toLowerCase(Locale.ROOT);
        if ("off".equals(raw) || "disable".equals(raw) || "none".equals(raw)) {
            CpsLimiter.setCpsLimit(0.0D);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "CPS cap " + EnumChatFormatting.RED + "disabled");
            return;
        }

        String normalized = raw.replace(',', '.');
        double value;
        try {
            value = Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid number: " + args[0]);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Example: /cps 6 or /cps 0.2");
            return;
        }

        if (value < 0.0D) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "CPS must be >= 0");
            return;
        }

        CpsLimiter.setCpsLimit(value);
        if (value == 0.0D) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "CPS cap " + EnumChatFormatting.RED + "disabled");
            return;
        }

        double intervalMs = 1000.0D / value;
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "CPS cap set to "
                + EnumChatFormatting.GREEN + trimDouble(value)
                + EnumChatFormatting.YELLOW + " (" + EnumChatFormatting.WHITE + trimDouble(intervalMs)
                + "ms" + EnumChatFormatting.YELLOW + " between attacks)");
    }

    private void sendStatus() {
        double v = CpsLimiter.getCpsLimit();
        if (v <= 0.0D) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "CPS cap is " + EnumChatFormatting.RED + "disabled");
            return;
        }
        double intervalMs = 1000.0D / v;
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "CPS cap: "
                + EnumChatFormatting.GREEN + trimDouble(v)
                + EnumChatFormatting.YELLOW + " (" + EnumChatFormatting.WHITE + trimDouble(intervalMs)
                + "ms" + EnumChatFormatting.YELLOW + ")");
    }

    private String trimDouble(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
