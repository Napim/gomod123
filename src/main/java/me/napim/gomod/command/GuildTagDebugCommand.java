package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.KillCounter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.List;

/**
 * Explains guild tag cache, denick lookup, and why TAB might not show {@code [TAG]} for a player.
 * <p>{@code /guildtagdebug PlayerName api} — clear cache row and fetch guild from Hypixel (needs API key),
 * even when "show guild tag" is off.
 */
public class GuildTagDebugCommand extends Command {

    public GuildTagDebugCommand() {
        super("guildtagdebug", new String[] {"gtdb", "guilddebug"});
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (args.length >= 2 && "api".equalsIgnoreCase(args[args.length - 1])) {
            String ign = String.join(" ", java.util.Arrays.copyOfRange(args, 0, args.length - 1)).trim();
            if (ign.isEmpty()) {
                mc.thePlayer.addChatMessage(new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Usage: /guildtagdebug <player> api"));
                return;
            }
            KillCounter.fetchGuildTagForDebug(ign);
            mc.thePlayer.addChatMessage(new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Queued guild API refresh for " + ign + " — check chat in a moment, then /guildtagdebug " + ign));
            return;
        }

        String ign = String.join(" ", args).trim();
        List<String> lines = KillCounter.buildGuildTagDebugLines(ign.isEmpty() ? null : ign);
        for (String line : lines) {
            mc.thePlayer.addChatMessage(new ChatComponentText(line));
        }
    }
}
