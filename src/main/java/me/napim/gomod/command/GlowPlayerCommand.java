package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.GlowPlayer;
import me.napim.gomod.util.GlowTrailRenderer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * Command to toggle glowing outline for a specific player.
 * Usage:
 *  - /glowplayer              (clear flat list for all names)
 *  - /glowplayer <player>   (toggle)
 *  - /glowplayer list
 *  - /glowplayer clear
 *  - dev only: /glowplayer debug <on|off>, /glowplayer why <player>, /glowplayer outlinebug
 *  - /glowplayer color <hex>
 */
public class GlowPlayerCommand extends Command {

    public GlowPlayerCommand() {
        super("glowplayer");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            Main.sendMessage(GlowPlayer.clearFlatGlowList());
            return;
        }
        String sub = args[0].toLowerCase();
        if ("list".equals(sub)) {
            java.util.List<String> list = GlowPlayer.getPlayers();
            if (list.isEmpty()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No glowing players.");
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Glowing: " + EnumChatFormatting.WHITE + String.join(", ", list));
            }
            return;
        }
        if ("clear".equals(sub)) {
            GlowPlayer.clear();
            return;
        }
        if ("debug".equals(sub)) {
            if (!GomodInternalFeatures.requireInternalCommand("glowplayer debug")) return;
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + net.minecraft.util.EnumChatFormatting.YELLOW + "Usage: /glowplayer debug <on|off>");
                return;
            }
            String v = args[1].toLowerCase();
            boolean enable = "on".equals(v) || "true".equals(v) || "1".equals(v);
            Main.sendMessage(GlowPlayer.setDebug(enable));
            return;
        }
        if ("color".equals(sub)) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + net.minecraft.util.EnumChatFormatting.YELLOW + "Usage: /glowplayer color <hex>");
                return;
            }
            Main.sendMessage(GlowPlayer.setColorHex(args[1]));
            return;
        }
        if ("trail".equals(sub)) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Trail duration: "
                        + EnumChatFormatting.WHITE + GlowTrailRenderer.getLifetimeSeconds() + "s");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Usage: /glowplayer trail <seconds>");
                return;
            }
            try {
                int secs = Integer.parseInt(args[1]);
                GlowTrailRenderer.setLifetimeSeconds(secs);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + "Trail duration set to " + GlowTrailRenderer.getLifetimeSeconds() + "s");
            } catch (NumberFormatException e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid number: " + args[1]);
            }
            return;
        }
        if ("outlinebug".equals(sub) || "outline-debug".equals(sub)) {
            if (!GomodInternalFeatures.requireInternalCommand("glowplayer outlinebug")) return;
            String dump = GlowPlayer.buildOutlinePipelineBugReport();
            for (String line : dump.split("\n")) {
                if (!line.trim().isEmpty()) {
                    Main.sendMessage(line);
                }
            }
            return;
        }
        if ("why".equals(sub)) {
            if (!GomodInternalFeatures.requireInternalCommand("glowplayer why")) return;
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + net.minecraft.util.EnumChatFormatting.YELLOW + "Usage: /glowplayer why <player>");
                return;
            }
            String debugInfo = GlowPlayer.getGlowDebugInfo(args[1]);
            // Send multi-line message by splitting on newlines
            String[] lines = debugInfo.split("\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    Main.sendMessage(line);
                }
            }
            return;
        }
        if (args.length >= 1) {
            String raw = args[0];
            String low = raw.toLowerCase(Locale.ROOT);
            // Common mistake: /glowplayer 1 thinking it toggles debug (debug uses /glowplayer debug on).
            if (args.length == 1 && ("1".equals(low) || "0".equals(low)) && !GlowPlayer.isAnyLoadedPlayerMatchingGlowArg(raw)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No loaded player is named \""
                        + raw + "\". " + (GomodInternalFeatures.isEnabled()
                        ? "To toggle glow debug, use " + EnumChatFormatting.WHITE + "/glowplayer debug on"
                                + EnumChatFormatting.YELLOW + " or " + EnumChatFormatting.WHITE + "/glowplayer debug off"
                                + EnumChatFormatting.YELLOW + "."
                        : "Use /glowplayer <player> with a loaded player's name."));
                return;
            }
            Main.sendMessage(GlowPlayer.toggle(raw));
            return;
        }
        showHelp();
    }

    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage:");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer" + EnumChatFormatting.WHITE + " - Clear flat glow list for all names");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer <player>" + EnumChatFormatting.WHITE + " - Toggle flat glow for player");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer list" + EnumChatFormatting.WHITE + " - List glowing players");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer clear" + EnumChatFormatting.WHITE + " - Clear glowing players");
        if (GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer debug <on|off>" + EnumChatFormatting.WHITE + " - Toggle debug output");
            Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer outlinebug" + EnumChatFormatting.WHITE + " - Dump outline state (glowplayer2 on everyone, etc.)");
            Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer why <player>" + EnumChatFormatting.WHITE + " - Show why a player is glowing");
        }
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer color <hex>" + EnumChatFormatting.WHITE + " - Set wireframe color (e.g. #00FFFF)");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer trail <seconds>" + EnumChatFormatting.WHITE + " - Set trail fade time (default: 7s)");
        Main.sendMessage(EnumChatFormatting.GOLD + "/glowplayer2 <player>" + EnumChatFormatting.WHITE + " - Vanilla-style outline (cannot stack with /glowplayer on same name)");
    }
}


