package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.PlayerTagDebug;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Internal/dev support command for player nametag, healthtag, denicker, and Lunar stack issues. */
public final class PlayerTagDebugCommand extends Command {
    public PlayerTagDebugCommand() {
        super("tagdebug", new String[]{"nametagdebug", "healthtagdebug"});
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 1) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Usage: /tagdebug [player|all] (no name = player under crosshair or nearest player)");
            return;
        }
        String target = args.length == 0 ? "" : args[0];
        try {
            PlayerTagDebug.Result result = PlayerTagDebug.capture(target);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Tag debug captured for "
                    + result.playerCount + " player(s)." + (result.copiedToClipboard
                    ? EnumChatFormatting.GRAY + " Report copied to clipboard." : ""));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + result.file.getAbsolutePath());
        } catch (Throwable t) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Tag debug failed: "
                    + EnumChatFormatting.WHITE + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }
}
