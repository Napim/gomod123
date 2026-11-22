package me.napim.gomod.command;

import java.util.Locale;
import me.napim.gomod.Main;
import me.napim.gomod.features.LocalMobDisguise;
import me.napim.gomod.gui.DisguiseGUI;
import me.napim.gomod.gui.MobLookEntityInputGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Chat entry for third-person mob look ({@link LocalMobDisguise}) — same feature as Disguise → Choose Mob.
 */
public class MobDisguiseCommand extends Command {

    public MobDisguiseCommand() {
        super("mobdisguise", new String[] { "moblook", "mobd" });
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (args.length == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Usage: "
                    + EnumChatFormatting.WHITE + "/mobdisguise <mob>"
                    + EnumChatFormatting.GRAY + " or "
                    + EnumChatFormatting.WHITE + "/disguise off");
            return;
        }
        String a0 = args[0].toLowerCase(Locale.ROOT);
        if ("help".equals(a0) || "?".equals(a0)) {
            sendUsage();
            return;
        }
        if ("off".equals(a0) || "disable".equals(a0) || "reset".equals(a0)) {
            LocalMobDisguise.clearCustomAndSetPreset(LocalMobDisguise.Kind.OFF);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Mob disguise off.");
            return;
        }
        if ("cycle".equals(a0)) {
            LocalMobDisguise.cycleKind();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Mob disguise: "
                    + LocalMobDisguise.getMobDisguiseStatusLine());
            return;
        }
        if ("gui".equals(a0) || "menu".equals(a0)) {
            openMobLookGui(mc);
            return;
        }
        if (args.length >= 2 && "id".equals(a0)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) {
                    sb.append('_');
                }
                sb.append(args[i]);
            }
            LocalMobDisguise.setCustomEntityId(sb.toString());
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Mob disguise: "
                    + LocalMobDisguise.getMobDisguiseStatusLine());
            return;
        }
        try {
            LocalMobDisguise.Kind k = LocalMobDisguise.Kind.valueOf(args[0].toUpperCase(Locale.ROOT));
            LocalMobDisguise.clearCustomAndSetPreset(k);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Mob disguise: "
                    + LocalMobDisguise.getMobDisguiseStatusLine());
        } catch (IllegalArgumentException e) {
            String joined = joinMobIdTokens(args);
            LocalMobDisguise.setCustomEntityId(joined);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Mob disguise: "
                    + LocalMobDisguise.getMobDisguiseStatusLine());
        }
    }

    /** Hypixel-style ids: {@code ender dragon} → {@code enderdragon}. */
    private static String joinMobIdTokens(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        String compact = String.join("", args);
        if (compact.matches("[a-zA-Z0-9_]+")) {
            return compact;
        }
        return String.join("_", args);
    }

    private static void sendUsage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Mob disguise — "
                + EnumChatFormatting.WHITE + "/mobdisguise <mob> | off | cycle | gui | id <Name>"
                + EnumChatFormatting.GRAY + "; presets: "
                + EnumChatFormatting.WHITE + "zombie skeleton creeper cow pig spider");
    }

    private static void openMobLookGui(Minecraft mc) {
        if (mc == null) {
            return;
        }
        Runnable r = () -> {
            String cur = LocalMobDisguise.getPreferredMobInputValue();
            GuiScreen parent = mc.currentScreen instanceof DisguiseGUI ? mc.currentScreen : null;
            mc.displayGuiScreen(new MobLookEntityInputGUI(parent, cur));
        };
        if (mc.isCallingFromMinecraftThread()) {
            r.run();
        } else {
            mc.addScheduledTask(r);
        }
    }
}
