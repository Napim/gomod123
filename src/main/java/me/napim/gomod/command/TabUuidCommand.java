/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.network.NetworkPlayerInfo
 *  net.minecraft.util.ChatComponentText
 *  net.minecraft.util.EnumChatFormatting
 *  net.minecraft.util.IChatComponent
 *  net.weavemc.loader.api.command.Command
 */
package me.napim.gomod.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.napim.gomod.Main;
import me.napim.gomod.features.TabDuplicateNickResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.command.Command;

public class TabUuidCommand
extends Command {
    public TabUuidCommand() {
        super("tabuuid", new String[0]);
    }

    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            this.sendMessage(EnumChatFormatting.RED + "Not connected.");
            return;
        }
        String filter = args.length > 0 ? args[0].toLowerCase() : null;
        this.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Reading tab UUIDs...");
        new Thread(() -> this.printTabEntries(filter), "gomod-tabuuid").start();
    }

    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage((IChatComponent)new ChatComponentText(message));
        }
    }

    private void printTabEntries(String filter) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return;
        }
        ArrayList<NetworkPlayerInfo> entries = new ArrayList<NetworkPlayerInfo>(mc.getNetHandler().getPlayerInfoMap());
        entries.sort(Comparator.comparing(info -> {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) {
                return "";
            }
            return info.getGameProfile().getName().toLowerCase();
        }));
        Map<String, Integer> nameCounts = this.countNames(entries);
        int shown = 0;
        this.sendMessage(EnumChatFormatting.GOLD + "=== TAB UUIDS ===");
        for (NetworkPlayerInfo info2 : entries) {
            String actualName;
            if (info2 == null || info2.getGameProfile() == null) continue;
            String name = info2.getGameProfile().getName();
            UUID uuid = info2.getGameProfile().getId();
            if (name == null || name.isEmpty() || filter != null && !name.toLowerCase().contains(filter)) continue;
            StringBuilder line = new StringBuilder();
            line.append(EnumChatFormatting.YELLOW).append(name).append(EnumChatFormatting.DARK_GRAY).append(" -> ").append(EnumChatFormatting.AQUA).append(uuid == null ? "<null>" : uuid.toString());
            if (uuid != null && nameCounts.getOrDefault(name.toLowerCase(), 0) > 1 && (actualName = TabDuplicateNickResolver.resolveCurrentUsernameBlocking(uuid)) != null && !actualName.equalsIgnoreCase(name)) {
                line.append(EnumChatFormatting.DARK_GRAY).append(" -> ").append(EnumChatFormatting.GREEN).append("actual=").append(EnumChatFormatting.WHITE).append(actualName);
            }
            this.sendMessage(line.toString());
            ++shown;
        }
        if (shown == 0) {
            this.sendMessage(EnumChatFormatting.RED + "No tab players matched.");
            return;
        }
        this.sendMessage(EnumChatFormatting.GREEN + "Shown " + shown + " tab entr" + (shown == 1 ? "y." : "ies."));
    }

    private Map<String, Integer> countNames(Collection<NetworkPlayerInfo> entries) {
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        for (NetworkPlayerInfo info : entries) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) continue;
            String key = info.getGameProfile().getName().toLowerCase();
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }
}

