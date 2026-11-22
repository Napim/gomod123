package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.ArmorDisguise;
import me.napim.gomod.features.ArmorDisguiseSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.weavemc.loader.api.command.Command;
import java.util.*;

public final class ArmorDisguiseCommand extends Command {
    private final boolean shared;
    public ArmorDisguiseCommand(boolean shared) {
        super(shared ? "disguisergomodarmor" : "disguiserarmor"); this.shared = shared;
    }
    @Override public void handle(String[] args) {
        String label = shared ? "disguisergomodarmor" : "disguiserarmor";
        if (!GomodInternalFeatures.requireInternalCommand(label)) return;
        try {
            if (args.length == 0) { usage(label); return; }
            if (shared) {
                if (args.length == 1 && args[0].equalsIgnoreCase("off")) { ArmorDisguise.setShared(null, null); return; }
                if (args.length == 2 && args[1].equalsIgnoreCase("off")) { ArmorDisguise.setShared(args[0], null); return; }
                ArmorDisguise.setShared(args[0], ArmorDisguiseSpec.parse(args, 1));
            } else {
                boolean off = args.length == 1 && args[0].equalsIgnoreCase("off");
                ArmorDisguise.setLocal(off ? null : ArmorDisguiseSpec.parse(args, 0));
                Main.sendMessage(Main.CHAT_PREFIX + (off ? "Your local armor disguise is OFF."
                        : "Your armor disguise is visible only to you; it ends when you die."));
            }
        } catch (Exception failure) { Main.sendMessage(Main.CHAT_PREFIX + failure.getMessage()); }
    }
    private void usage(String label) {
        Main.sendMessage(Main.CHAT_PREFIX + "/" + label + (shared ? " <player>" : "")
                + " <material | helmet chestplate leggings boots>");
        Main.sendMessage(Main.CHAT_PREFIX + "d/diamond i/iron c/chain g/gold l/leather n/none keep; + adds glint.");
        Main.sendMessage(Main.CHAT_PREFIX + "/" + label + (shared ? " <player> off | /" + label + " off" : " off"));
    }

    /** Full input replacements, shared by the chat GUI on both loaders. */
    public static List<String> complete(String text) {
        if (!GomodInternalFeatures.isEnabled()) return Collections.emptyList();
        String[] parts = text.split(" ", -1);
        boolean shared = parts[0].equalsIgnoreCase("/disguisergomodarmor");
        int index = parts.length - 1, first = shared ? 2 : 1;
        if (index < 1 || index > first + 3) return Collections.emptyList();
        List<String> choices = new ArrayList<>();
        if (shared && index == 1) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getNetHandler() != null)
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap())
                    if (info.getGameProfile().getName() != null) choices.add(info.getGameProfile().getName());
            choices.add("off");
        } else {
            choices.addAll(Arrays.asList("diamond", "iron", "chain", "gold", "leather", "none", "keep",
                    "diamond+", "iron+", "chain+", "gold+", "leather+", "d", "i", "c", "g", "l", "n",
                    "d+", "i+", "c+", "g+", "l+"));
            if (index == first) choices.add("off");
        }
        String prefix = parts[index].toLowerCase(Locale.ROOT), before = text.substring(0, text.lastIndexOf(' ') + 1);
        List<String> result = new ArrayList<>();
        for (String choice : choices) if (choice.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(before + choice);
        return result;
    }
}
