package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import org.lwjgl.input.Keyboard;

/**
 * Proxy command for AskForLoot all-time mode.
 * Uses reflection so command registration cannot load the feature too early.
 */
public class AskLootAllTimeCommand extends Command {
    public AskLootAllTimeCommand() {
        super("asklootalltime");
    }

    @Override
    public void handle(String[] args) {
        try {
            Class<?> askForLootClass = Class.forName("me.napim.gomod.features.AskForLoot");

            if (args.length == 0) {
                Object message = askForLootClass.getMethod("toggleAllTime").invoke(null);
                if (message instanceof String) {
                    Main.sendMessage((String) message);
                }
                return;
            }

            String subcommand = args[0].toLowerCase();
            if ("status".equals(subcommand)) {
                boolean enabled = (Boolean) askForLootClass.getMethod("isAllTimeEnabled").invoke(null);
                boolean askEnabled = (Boolean) askForLootClass.getMethod("isEnabled").invoke(null);
                int keyCode = (Integer) askForLootClass.getMethod("getKeyCode").invoke(null);
                sendStatus(enabled, askEnabled, keyCode);
                return;
            }

            if ("on".equals(subcommand) || "enable".equals(subcommand)) {
                askForLootClass.getMethod("setAllTimeEnabled", boolean.class).invoke(null, true);
                boolean askEnabled = (Boolean) askForLootClass.getMethod("isEnabled").invoke(null);
                int keyCode = (Integer) askForLootClass.getMethod("getKeyCode").invoke(null);
                sendStatus(true, askEnabled, keyCode);
                return;
            }

            if ("off".equals(subcommand) || "disable".equals(subcommand)) {
                askForLootClass.getMethod("setAllTimeEnabled", boolean.class).invoke(null, false);
                boolean askEnabled = (Boolean) askForLootClass.getMethod("isEnabled").invoke(null);
                int keyCode = (Integer) askForLootClass.getMethod("getKeyCode").invoke(null);
                sendStatus(false, askEnabled, keyCode);
                return;
            }

            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /asklootalltime [on|off|status]");
        } catch (Throwable t) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to toggle Ask for Loot all-time mode.");
        }
    }

    private void sendStatus(boolean enabled, boolean askEnabled, int keyCode) {
        String keyName = keyCode > 0 ? Keyboard.getKeyName(keyCode) : "NONE";
        if (keyName == null || keyName.trim().isEmpty()) {
            keyName = "UNKNOWN";
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Ask for Loot all-time mode "
                + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled")
                + EnumChatFormatting.YELLOW + ", Ask for Loot "
                + (askEnabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.YELLOW + ", key " + EnumChatFormatting.WHITE + keyName + EnumChatFormatting.YELLOW + ".");
    }
}
