package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.GlowPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Vanilla-style entity outline (team-colored / "weird" look) for a player.
 * Mutually exclusive with /glowplayer on the same name.
 */
public class GlowPlayer2Command extends Command {

    public GlowPlayer2Command() {
        super("glowplayer2");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            Main.sendMessage(GlowPlayer.clearChameleonGlowList());
            return;
        }
        if ("outlinebug".equalsIgnoreCase(args[0]) || "outline-debug".equalsIgnoreCase(args[0])) {
            String dump = GlowPlayer.buildOutlinePipelineBugReport();
            for (String line : dump.split("\n")) {
                if (!line.trim().isEmpty()) {
                    Main.sendMessage(line);
                }
            }
            return;
        }
        Main.sendMessage(GlowPlayer.toggle2(args[0]));
    }

    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage:");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer2" + EnumChatFormatting.WHITE + " — clear /glowplayer2 list + disable Teammate glow (Green/Chameleon)");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer2 <player>" + EnumChatFormatting.WHITE + " — toggle vanilla-style outline for that player");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer2 outlinebug" + EnumChatFormatting.WHITE + " — dump why outlines might show on everyone");
        Main.sendMessage(EnumChatFormatting.GRAY + "Uses the same list file as /glowplayer; each name is either glow or glow2, not both.");
    }
}
