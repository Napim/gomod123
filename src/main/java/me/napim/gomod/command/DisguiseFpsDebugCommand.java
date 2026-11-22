package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Developer-only component isolation for tracking persistent disguise FPS loss. */
public final class DisguiseFpsDebugCommand extends Command {

    public DisguiseFpsDebugCommand() {
        super("disguisefps", "dfps");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            printStatus();
            printUsage();
            return;
        }

        String action = args[0];
        if ("status".equalsIgnoreCase(action)) {
            printStatus();
            return;
        }
        if ("off".equalsIgnoreCase(action) || "disable".equalsIgnoreCase(action)) {
            DisguiseCommand.disableActiveDisguiseWithMessage();
            return;
        }

        LocalDisguise.FpsDebugMode mode = LocalDisguise.FpsDebugMode.parse(action);
        if (mode == null) {
            send(EnumChatFormatting.RED + "Unknown FPS mode: " + action);
            printUsage();
            return;
        }
        if (args.length > 2) {
            send(EnumChatFormatting.RED + "Too many arguments.");
            printUsage();
            return;
        }

        if (args.length == 2) {
            DisguiseCommand.enableFpsDebugDisguiseAsync(args[1], mode);
            return;
        }

        if (!LocalDisguise.setFpsDebugMode(mode)) {
            send(EnumChatFormatting.YELLOW + "No disguise is loaded. Add a player name, for example: "
                    + EnumChatFormatting.WHITE + "/disguisefps " + mode.commandName + " Notch");
            return;
        }
        send(EnumChatFormatting.GREEN + "Switched active disguise to FPS mode "
                + EnumChatFormatting.AQUA + mode.commandName + EnumChatFormatting.GRAY + " — " + mode.description + ".");
        printComponents();
    }

    private static void printStatus() {
        if (!LocalDisguise.isEnabled()) {
            send(EnumChatFormatting.GOLD + "Disguise FPS debug: " + EnumChatFormatting.RED + "no disguise loaded");
            return;
        }
        LocalDisguise.FpsDebugMode mode = LocalDisguise.getFpsDebugMode();
        send(EnumChatFormatting.GOLD + "Disguise FPS debug: " + EnumChatFormatting.AQUA + mode.commandName
                + EnumChatFormatting.GRAY + " — " + mode.description);
        send(EnumChatFormatting.GRAY + "Target: " + EnumChatFormatting.WHITE + LocalDisguise.getTargetName());
        printComponents();
    }

    private static void printComponents() {
        send(EnumChatFormatting.GRAY + "skin=" + onOff(LocalDisguise.shouldApplySkinOverride())
                + EnumChatFormatting.GRAY + " cape=" + onOff(LocalDisguise.shouldApplyCapeOverride())
                + EnumChatFormatting.GRAY + " bind=" + onOff(LocalDisguise.shouldApplyTextureBindOverride())
                + EnumChatFormatting.GRAY + " text=" + onOff(LocalDisguise.shouldApplyRenderTextRewrite())
                + EnumChatFormatting.GRAY + " live=" + onOff(LocalDisguise.shouldApplyLiveSync())
                + EnumChatFormatting.GRAY + " data=" + onOff(LocalDisguise.shouldApplyAuxiliaryOverrides()));
    }

    private static String onOff(boolean enabled) {
        return enabled ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off";
    }

    private static void printUsage() {
        send(EnumChatFormatting.YELLOW + "Usage: " + EnumChatFormatting.WHITE
                + "/disguisefps <mode> [player]" + EnumChatFormatting.GRAY
                + " (omit player to switch the active disguise)");
        send(EnumChatFormatting.GRAY + "Modes: full, noskin, nocape, nobind, notext, nolive, skinonly, textonly, minimal");
        send(EnumChatFormatting.DARK_GRAY + "Suggested: full -> noskin -> nobind -> notext -> nolive -> minimal; hold each for 10-15s in the same scene.");
        send(EnumChatFormatting.DARK_GRAY + "/disguisefps status | /disguisefps off");
    }

    private static void send(String message) {
        Main.sendMessage(Main.CHAT_PREFIX + message + EnumChatFormatting.RESET);
    }
}
