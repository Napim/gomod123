package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.TabStatsTrafficDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Shows what Tab Stats is doing: inbound tab/team packets (batched), full TAB recomposes, HTTP to Hypixel/Mojang,
 * and queued scans. Use this to confirm lag is not "constant packet spam" — usually it is heavy TAB recomputation
 * when Hypixel sends many {@code S3EPacketTeams} updates.
 */
public class TabStatsNetDebugCommand extends Command {
    public TabStatsNetDebugCommand() {
        super("tabstatsnetdebug");
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0) {
            String a = args[0].toLowerCase();
            if ("off".equals(a) || "0".equals(a) || "false".equals(a)) {
                TabStatsTrafficDebug.setMode(TabStatsTrafficDebug.Mode.OFF);
                send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "TabStats traffic debug OFF");
                send(EnumChatFormatting.GRAY + "Logs folder: " + EnumChatFormatting.WHITE
                        + TabStatsTrafficDebug.getDebugLogFolder().getAbsolutePath());
                return;
            }
            if ("summary".equals(a) || "1".equals(a)) {
                TabStatsTrafficDebug.setMode(TabStatsTrafficDebug.Mode.SUMMARY);
                send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TabStats traffic: SUMMARY"
                        + EnumChatFormatting.GRAY + " — chat: 1s tab-packet totals; compose/API → console + file");
                sendSessionLogHint();
                return;
            }
            if ("verbose".equals(a) || "2".equals(a)) {
                TabStatsTrafficDebug.setMode(TabStatsTrafficDebug.Mode.VERBOSE);
                send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TabStats traffic: VERBOSE"
                        + EnumChatFormatting.GRAY + " — chat + file for tab batch, compose, HTTP/queue/bulk");
                sendSessionLogHint();
                return;
            }
            if ("help".equals(a) || "?".equals(a)) {
                sendHelp();
                return;
            }
        }
        TabStatsTrafficDebug.Mode m = TabStatsTrafficDebug.cycleMode();
        String desc;
        switch (m) {
            case SUMMARY:
                desc = "SUMMARY — see console for compose/API; chat gets 1s tab-packet totals";
                break;
            case VERBOSE:
                desc = "VERBOSE — chat + console for compose/API/queue";
                break;
            default:
                desc = "OFF";
                break;
        }
        send(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "TabStats traffic debug: "
                + EnumChatFormatting.WHITE + desc);
        if (m != TabStatsTrafficDebug.Mode.OFF) {
            sendSessionLogHint();
        }
        send(EnumChatFormatting.GRAY + "Does not log outgoing Minecraft chat packets. Keys redacted in URLs.");
    }

    private void sendSessionLogHint() {
        String path = TabStatsTrafficDebug.getActiveSessionLogPath();
        send(EnumChatFormatting.GRAY + "Folder: " + EnumChatFormatting.WHITE
                + TabStatsTrafficDebug.getDebugLogFolder().getAbsolutePath());
        if (path != null) {
            send(EnumChatFormatting.GRAY + "This session: " + EnumChatFormatting.WHITE + path);
        }
    }

    private void sendHelp() {
        send(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/tabstatsnetdebug"
                + EnumChatFormatting.WHITE + " — cycle off → summary → verbose");
        send(EnumChatFormatting.GRAY + "/tabstatsnetdebug summary | verbose | off");
    }

    private void send(String s) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(s));
        }
    }
}
