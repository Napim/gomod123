package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.RoleplayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * /roleplay <player> — enters the target player's first-person POV (client-side only).
 * /roleplay           — exits roleplay mode.
 *
 * The feature works entirely client-side (like a freecam), so no anti-cheat
 * on any server can detect it.
 */
public class RoleplayCommand extends Command {

    public RoleplayCommand() {
        super("roleplay");
    }

    @Override
    public void handle(String[] args) {
        // No argument → toggle off if active, otherwise show usage
        if (args.length == 0) {
            if (RoleplayMode.isEnabled()) {
                RoleplayMode.disable();
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: "
                        + EnumChatFormatting.WHITE + "/roleplay <player>"
                        + EnumChatFormatting.YELLOW + "  |  Run again (or /roleplay) to exit.");
            }
            return;
        }

        String playerName = args[0];

        // Running the command on the currently tracked player acts as a toggle-off
        if (RoleplayMode.isEnabled() && playerName.equalsIgnoreCase(RoleplayMode.getTargetPlayerName())) {
            RoleplayMode.disable();
            return;
        }

        // Make sure the player actually exists in the current world
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Cannot search for players right now.");
            return;
        }

        EntityPlayer found = null;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player.getName().equalsIgnoreCase(playerName)) {
                found = player;
                break;
            }
        }

        if (found == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Player '" + playerName
                    + "' not found. They must be within render distance.");
            return;
        }

        boolean success = RoleplayMode.enable(playerName);
        if (!success) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to enter roleplay mode.");
        }
    }
}
