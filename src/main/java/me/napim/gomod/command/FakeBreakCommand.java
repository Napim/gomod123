package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;
import net.minecraft.util.EnumChatFormatting;
import me.napim.gomod.features.FakeBreak;
import me.napim.gomod.Main;

/**
 * /fakebreak [on|off|clear]
 */
public class FakeBreakCommand extends Command {
    public FakeBreakCommand() {
        super("fakebreak");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /fakebreak on|off|clear|clearblocks|replace <distance>|timereset|[time]s");
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "on":
                FakeBreak.setEnabled(true);
                break;
            case "off":
                FakeBreak.setEnabled(false);
                break;
            case "clear":
                FakeBreak.clearCollectedFromInventory();
                break;
            case "clearblocks":
                FakeBreak.restoreBrokenBlocks();
                break;
            case "replace":
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /fakebreak replace <distance>");
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Example: /fakebreak replace 10");
                    return;
                }
                try {
                    int distance = Integer.parseInt(args[1]);
                    if (distance <= 0) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Distance must be positive");
                        return;
                    }
                    if (distance > 50) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Distance too large! Maximum is 50 blocks");
                        return;
                    }
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Replacing blocks within " + distance + " blocks...");
                    FakeBreak.replaceBlocksInRadius(distance);
                } catch (NumberFormatException e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid distance! Must be a number");
                }
                break;
            case "timereset":
                FakeBreak.setFixedBreakTime(-1.0f); // Reset to use real hardness
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Break time reset to use real block hardness");
                break;
            default:
                // Check if it's a time format like "1s", "0.5s", etc
                if (sub.endsWith("s")) {
                    try {
                        String timeStr = sub.substring(0, sub.length() - 1);
                        float seconds = Float.parseFloat(timeStr);
                        if (seconds <= 0) {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Time must be positive");
                            return;
                        }
                        FakeBreak.setFixedBreakTime(seconds);
                        FakeBreak.setEnabled(true);
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "FakeBreak enabled with fixed " + seconds + "s break time");
                    } catch (NumberFormatException e) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /fakebreak on|off|clear|clearblocks|replace <distance>|timereset|[time]s");
                    }
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /fakebreak on|off|clear|clearblocks|replace <distance>|timereset|[time]s");
                }
        }
    }
}

