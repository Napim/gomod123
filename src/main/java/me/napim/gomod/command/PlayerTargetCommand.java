package me.napim.gomod.command;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * /pt [player|off]
 * - With a player name, sets the tracking target and enables the overlay
 * - With no args, toggles the overlay (tracks nearest player)
 * - With "off", disables the overlay
 */
public class PlayerTargetCommand extends Command {
    public PlayerTargetCommand() {
        super("pt");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            try {
                Class<?> pt = Class.forName("me.napim.gomod.features.PlayerTarget");
                java.lang.reflect.Method isEnabled = pt.getMethod("isEnabled");
                boolean newState = !((Boolean) isEnabled.invoke(null));
                pt.getMethod("setEnabled", boolean.class).invoke(null, newState);
                if (newState) {
                    pt.getMethod("setTargetPlayerName", String.class).invoke(null, new Object[]{null});
                    pt.getMethod("notifyEnabledMessage").invoke(null);
                } else {
                    pt.getMethod("notifyDisabledMessage").invoke(null);
                }
            } catch (Throwable ignored) {}
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
            try {
                Class<?> pt = Class.forName("me.napim.gomod.features.PlayerTarget");
                pt.getMethod("setEnabled", boolean.class).invoke(null, false);
                pt.getMethod("notifyDisabledMessage").invoke(null);
            } catch (Throwable ignored) {}
            return;
        }

        // /pt <player>
        String name = args[0];
        try {
            Class<?> pt = Class.forName("me.napim.gomod.features.PlayerTarget");
            pt.getMethod("setTargetPlayerName", String.class).invoke(null, name);
            pt.getMethod("setEnabled", boolean.class).invoke(null, true);
        } catch (Throwable ignored) {}
        me.napim.gomod.Main.sendMessage(
            EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.RESET +
            EnumChatFormatting.YELLOW + "Now targeting: " + name);
    }
}

 