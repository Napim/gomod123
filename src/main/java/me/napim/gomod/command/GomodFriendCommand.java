package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodFriendRelay;
import me.napim.gomod.features.GomodFriendRelay.FriendAddSource;
import me.napim.gomod.features.GomodServiceStatus;
import me.napim.gomod.gui.GomodFriendsGUI;
import me.napim.gomod.gui.GomodSocialGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Collections;
import java.util.List;

public class GomodFriendCommand extends Command {
    public GomodFriendCommand() {
        super("gfriend");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            help();
            return;
        }
        String sub = args[0].toLowerCase();
        if ("add".equals(sub)) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /gfriend add <player>");
                return;
            }
            String name = args[1].trim();
            if (name.isEmpty()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            if (mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName())) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Are you schizophrenic? Do you need help?");
                return;
            }
            GomodFriendRelay.postFriendRequestAddAsync(mc, name, FriendAddSource.GFRIEND_COMMAND);
            return;
        }
        if ("remove".equals(sub) || "del".equals(sub)) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /gfriend remove <player>");
                return;
            }
            String name = args[1];
            if (!GomodServiceStatus.isServiceConnected()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Not connected to gomod Service — cannot update friends online.");
                return;
            }
            GomodFriendRelay.postFriendRemoveAsync(name);
            return;
        }
        if ("menu".equals(sub) || "gui".equals(sub)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                if (args.length >= 2 && "requests".equalsIgnoreCase(args[1].trim())) {
                    GomodSocialGUI.openRequestsView();
                    return;
                }
                String openWith = null;
                if (args.length >= 2) {
                    openWith = args[1].trim();
                    if (openWith.isEmpty()) {
                        openWith = null;
                    } else if (!ConfigManager.isGomodFriend(openWith)) {
                        boolean found = false;
                        for (String f : ConfigManager.getGomodFriends()) {
                            if (f != null && f.equalsIgnoreCase(openWith)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                    + "Not a gomod friend: " + EnumChatFormatting.AQUA + openWith
                                    + EnumChatFormatting.RED + " (opening Messages tab anyway)");
                        }
                    }
                }
                final String chatTarget = openWith;
                final net.minecraft.client.gui.GuiScreen cur = mc.currentScreen;
                mc.addScheduledTask(() -> mc.displayGuiScreen(new GomodFriendsGUI(cur, chatTarget)));
            }
            return;
        }
        if ("list".equals(sub)) {
            List<String> friends = ConfigManager.getGomodFriends();
            Collections.sort(friends);
            if (friends.isEmpty()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No gomod friends yet. Use /gfriend add <player> to send a request.");
                return;
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "gomod friends (" + friends.size() + "):");
            for (String f : friends) {
                Main.sendMessage(EnumChatFormatting.AQUA + " - " + f);
            }
            return;
        }
        help();
    }

    private void help() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "gomod Friend Commands:");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /gfriend add <player>" + EnumChatFormatting.WHITE + " - Send a friend request (they must accept)");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /gfriend remove <player>" + EnumChatFormatting.WHITE + " - Remove a gomod friend");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /gfriend list" + EnumChatFormatting.WHITE + " - List gomod friends");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /gfriend gui [player]" + EnumChatFormatting.WHITE + " - Open gomod Social (Messages tab; optional friend to chat with)");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /gfriend gui requests" + EnumChatFormatting.WHITE + " - Open gomod Social Requests");
    }
}
