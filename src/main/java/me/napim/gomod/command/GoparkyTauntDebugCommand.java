package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.GoparkyBlitzReactions;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Locally previews the Goparky kill taunt without changing the named player's game state. */
public final class GoparkyTauntDebugCommand extends Command {
    public GoparkyTauntDebugCommand() {
        super("debuggoparktaunt");
    }

    @Override
    public void handle(String[] args) {
        if (args == null || args.length != 1 || !args[0].matches("[A-Za-z0-9_]{1,16}")) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Usage: /debuggoparktaunt <player>");
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (!GoparkyBlitzReactions.debugTaunt(mc, args[0])) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Equip Goparky and join a world before running this test.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                + "Simulated " + EnumChatFormatting.YELLOW + args[0]
                + EnumChatFormatting.GREEN + " dying in the render; local taunt timer armed for 5s."
                + " Run /debuggoparky during the movement to save path and compass checkpoints.");
    }
}
