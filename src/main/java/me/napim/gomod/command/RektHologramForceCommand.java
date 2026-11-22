package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzRektHitboxOverlay;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/** Temporary dev-only override to preview the #rekt hitbox on nearby armor stands. */
public final class RektHologramForceCommand extends Command {
    public RektHologramForceCommand() {
        super("rekthologramforce");
    }

    @Override
    public void handle(String[] args) {
        String action = args == null || args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Force all armor stands: " + (BlitzRektHitboxOverlay.isForceAllArmorStandsEnabled()
                    ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
            return;
        }
        boolean enabled;
        if ("on".equals(action) || "enable".equals(action)) {
            enabled = true;
        } else if ("off".equals(action) || "disable".equals(action)) {
            enabled = false;
        } else if ("toggle".equals(action)) {
            enabled = !BlitzRektHitboxOverlay.isForceAllArmorStandsEnabled();
        } else {
            sendUsage();
            return;
        }

        BlitzRektHitboxOverlay.setForceAllArmorStandsEnabled(enabled);
        Main.sendMessage(Main.CHAT_PREFIX + (enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + "#rekt hitbox preview for all nearby armor stands " + (enabled ? "enabled" : "disabled")
                + (enabled ? EnumChatFormatting.GRAY + " (within 64 blocks; overrides the normal overlay toggle)" : ""));
    }

    private static void sendUsage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Usage: /rekthologramforce [on|off|toggle|status]");
    }
}
