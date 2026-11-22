package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.WitchAutoSound;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Switch automatic witch sound strategy: {@code /fixwitch [1|2|3|4]}.
 */
public class FixWitchCommand extends Command {

    public FixWitchCommand() {
        super("fixwitch");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            sendHelp(WitchAutoSound.getSoundMode());
            return;
        }
        if ("packet".equalsIgnoreCase(args[0]) || "replay".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Packet replay: " + (WitchAutoSound.isPacketReplayEnabled() ? "ON" : "OFF")
                        + " — /fixwitch packet on|off");
                return;
            }
            boolean on = "on".equalsIgnoreCase(args[1]) || "true".equalsIgnoreCase(args[1]) || "1".equals(args[1]);
            WitchAutoSound.setPacketReplayEnabled(on);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Witch packet replay = " + (on ? "ON" : "OFF"));
            return;
        }
        int m;
        try {
            m = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException e) {
            sendHelp(WitchAutoSound.getSoundMode());
            return;
        }
        if (m < 1 || m > 4) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Use 1–4.");
            return;
        }
        WitchAutoSound.setSoundMode(m);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Witch auto-sound mode = " + m);
        describe(m);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "State: " + WitchAutoSound.debugState());
    }

    private static void sendHelp(int current) {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Current mode: " + current + " — /fixwitch 1|2|3|4");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Packet replay: "
                + (WitchAutoSound.isPacketReplayEnabled() ? "ON" : "OFF")
                + " — /fixwitch packet on|off");
        describe(current);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "State: " + WitchAutoSound.debugState());
    }

    private static void describe(int m) {
        switch (m) {
            case 1:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "1: SoundHandler only.");
                break;
            case 2:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "2: world.playSound only.");
                break;
            case 3:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "3: SoundHandler + world.playSound (recommended for Lunar; default).");
                break;
            case 4:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "4: SoundHandler + world.playSound + player fallback.");
                break;
            default:
                break;
        }
    }
}
