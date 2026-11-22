package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.shareinv.ShareInvConstants;
import me.napim.gomod.util.RelayDevEndpoints;
import me.napim.gomod.shareinv.ShareInvManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * /shareinv — WebSocket relay pairing. Release builds can embed a public {@code wss://} relay; optional self-host with relay JAR.
 */
public class ShareInvCommand extends Command {
    public ShareInvCommand() {
        super("shareinv");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            help();
            return;
        }
        String a0 = args[0].toLowerCase();
        switch (a0) {
            case "help":
            case "?":
                help();
                break;
            case "url":
                if (args.length < 2) {
                    sendUrlStatus();
                    return;
                }
                String urlArg = args[1].toLowerCase();
                if ("clear".equals(urlArg) || "reset".equals(urlArg) || "default".equals(urlArg)) {
                    ConfigManager.setShareInvRelayUrl("");
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Custom relay URL cleared.");
                    sendUrlStatus();
                    return;
                }
                ConfigManager.setShareInvRelayUrl(join(args, 1));
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "ShareInv URL saved. /shareinv connect");
                break;
            case "secret":
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Secret is "
                            + (ConfigManager.getShareInvSecret().isEmpty() ? "empty" : "(set)"));
                    return;
                }
                ConfigManager.setShareInvSecret(join(args, 1));
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "ShareInv secret updated.");
                break;
            case "autoconnect":
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "shareInvAutoConnect: "
                            + ConfigManager.isShareInvAutoConnect());
                    return;
                }
                String v = args[1].toLowerCase();
                ConfigManager.setShareInvAutoConnect("on".equals(v) || "true".equals(v) || "1".equals(v));
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Auto-connect: " + ConfigManager.isShareInvAutoConnect());
                break;
            case "connect":
            case "on":
                ShareInvManager.setWantConnected(true);
                ShareInvManager.connect();
                break;
            case "disconnect":
            case "off":
                ShareInvManager.disconnect();
                break;
            case "accept":
                ShareInvManager.acceptInvite();
                break;
            case "decline":
                ShareInvManager.declineInvite();
                break;
            case "stop":
                ShareInvManager.leaveSession();
                break;
            case "debug":
                if (!GomodInternalFeatures.requireInternalCommand("shareinv debug")) return;
                if (args.length >= 2) {
                    String dv = args[1].toLowerCase();
                    if ("on".equals(dv) || "true".equals(dv) || "1".equals(dv)) {
                        ShareInvManager.setShareInvDebugToChat(true);
                    } else if ("off".equals(dv) || "false".equals(dv) || "0".equals(dv)) {
                        ShareInvManager.setShareInvDebugToChat(false);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Use: /shareinv debug on|off (or /shareinv debug to toggle)");
                        return;
                    }
                } else {
                    ShareInvManager.toggleShareInvDebugToChat();
                }
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "ShareInv debug to chat: "
                        + (ShareInvManager.isShareInvDebugToChat()
                        ? EnumChatFormatting.GREEN + "ON" + EnumChatFormatting.GRAY + " (first inv_peer each session)"
                        : EnumChatFormatting.RED + "OFF"));
                break;
            case "relayignore":
            case "relaywarn":
                if (args.length >= 2) {
                    String rv = args[1].toLowerCase();
                    if ("on".equals(rv) || "true".equals(rv) || "1".equals(rv)) {
                        ShareInvManager.setRelayDisconnectChatIgnored(true);
                    } else if ("off".equals(rv) || "false".equals(rv) || "0".equals(rv)) {
                        ShareInvManager.setRelayDisconnectChatIgnored(false);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Use: /shareinv relayignore on|off (or /shareinv relayignore to toggle)");
                        return;
                    }
                } else {
                    ShareInvManager.toggleRelayDisconnectChatIgnored();
                }
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Relay disconnect/error chat: "
                        + (ShareInvManager.isRelayDisconnectChatIgnored()
                        ? EnumChatFormatting.RED + "IGNORED" + EnumChatFormatting.GRAY + " this session "
                                + EnumChatFormatting.DARK_GRAY + "(until restart)"
                        : EnumChatFormatting.GREEN + "VISIBLE"));
                break;
            default:
                String target = join(args, 0);
                if (target.isEmpty()) {
                    help();
                    return;
                }
                if (!ConfigManager.isGomodFriend(target)) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Friend add " + EnumChatFormatting.YELLOW + target
                            + EnumChatFormatting.RED + " to shareinv with him! "
                            + EnumChatFormatting.GRAY + "(/gfriend add " + EnumChatFormatting.YELLOW + target
                            + EnumChatFormatting.GRAY + ")");
                    return;
                }
                ShareInvManager.sendInvite(target);
                break;
        }
    }

    private static void sendUrlStatus() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Effective relay: "
                + EnumChatFormatting.WHITE + ConfigManager.getShareInvRelayUrl());
        String ov = ConfigManager.getShareInvRelayUrlOverride();
        if (!ov.isEmpty()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "Source: your custom URL (/shareinv url clear to remove)");
        } else if (!ShareInvConstants.publicRelayTrimmed().isEmpty()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "Source: built-in public relay (shipped with this build)");
        } else {
            Main.sendMessage(EnumChatFormatting.GRAY + "Source: dev fallback — run relay on this PC or set /shareinv url / compile PUBLIC_RELAY_WSS");
        }
    }

    private static void help() {
        boolean hasPublic = !ShareInvConstants.publicRelayTrimmed().isEmpty();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "ShareInv — live inventory view with a friend");
        if (hasPublic) {
            Main.sendMessage(EnumChatFormatting.GRAY + "This build uses a " + EnumChatFormatting.GREEN + "hosted relay"
                    + EnumChatFormatting.GRAY + ": " + EnumChatFormatting.WHITE + "/shareinv connect"
                    + EnumChatFormatting.GRAY + " then invite. No extra programs.");
        } else {
            Main.sendMessage(EnumChatFormatting.GRAY + "Release builds can ship a hosted relay (see ShareInvConstants in source).");
            Main.sendMessage(EnumChatFormatting.GRAY + "Self-host: " + EnumChatFormatting.WHITE + "java -jar gomod-shareinv-relay-*.jar"
                    + EnumChatFormatting.GRAY + " then " + EnumChatFormatting.WHITE + "/shareinv url " + RelayDevEndpoints.defaultWsRelay());
        }
        Main.sendMessage(EnumChatFormatting.DARK_GRAY + "Maintainers: deploy shareinv-relay-java (or Node shareinv-relay) on a VPS, set PUBLIC_RELAY_WSS, rebuild.");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv url " + EnumChatFormatting.DARK_GRAY + "— show effective URL; /shareinv url clear — drop custom override");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv url ws://... or wss://... " + EnumChatFormatting.DARK_GRAY + "— self-hosted / custom relay");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv secret <text> " + EnumChatFormatting.DARK_GRAY + "— optional, match SHAREINV_SECRET");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv autoconnect on|off");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv connect " + EnumChatFormatting.DARK_GRAY + "— join relay");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv <player> " + EnumChatFormatting.DARK_GRAY + "— invite (they must be connected)");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv accept | decline | stop");
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv disconnect");
        if (GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv debug " + EnumChatFormatting.DARK_GRAY
                    + "— toggle; first inv_peer stats each session in chat (HP/XP relay check)");
        }
        Main.sendMessage(EnumChatFormatting.GRAY + "/shareinv relayignore on|off " + EnumChatFormatting.DARK_GRAY
                + "— hide/show relay disconnect and error chat this session (resets when you restart the game)");
    }

    private static String join(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
