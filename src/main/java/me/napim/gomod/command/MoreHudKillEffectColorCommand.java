package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * Persistent text colors for the standalone/compact More HUD kill-effect widget.
 *
 * Usage:
 * /morehudkecolor list
 * /morehudkecolor set <flame|speed|resistance|regen> <RRGGBB>
 * /morehudkecolor reset <flame|speed|resistance|regen|all>
 * /morehudkecolor clear
 */
public class MoreHudKillEffectColorCommand extends Command {

    public MoreHudKillEffectColorCommand() {
        super("morehudkecolor");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }

        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            sendList();
            return;
        }

        if ("clear".equalsIgnoreCase(args[0])) {
            resetAll();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Reset all More HUD kill-effect text colors.");
            return;
        }

        if ("reset".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || "all".equalsIgnoreCase(args[1])) {
                resetAll();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Reset all More HUD kill-effect text colors.");
                return;
            }
            String effectCode = parseEffectCode(args[1]);
            if (effectCode == null) {
                sendUsage();
                return;
            }
            ConfigManager.resetMoreHudKillEffectColor(effectCode);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Reset "
                    + prettyName(effectCode) + EnumChatFormatting.GREEN + " to #"
                    + String.format(Locale.ROOT, "%06X", ConfigManager.getMoreHudKillEffectColor(effectCode)));
            return;
        }

        if ("set".equalsIgnoreCase(args[0]) && args.length >= 3) {
            String effectCode = parseEffectCode(args[1]);
            if (effectCode == null) {
                sendUsage();
                return;
            }
            int rgb;
            try {
                rgb = parseRgbHex(args[2]);
            } catch (NumberFormatException e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Bad color. Use 6 hex digits like FF8800 or #FF8800.");
                return;
            }
            ConfigManager.setMoreHudKillEffectColor(effectCode, rgb);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + prettyName(effectCode)
                    + EnumChatFormatting.GREEN + " text color set to #"
                    + String.format(Locale.ROOT, "%06X", rgb));
            return;
        }

        sendUsage();
    }

    private static void sendList() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[More HUD kill-effect text colors]");
        sendEffectLine("FLAME");
        sendEffectLine("SPEED");
        sendEffectLine("RESISTANCE");
        sendEffectLine("REGENERATION");
        Main.sendMessage(EnumChatFormatting.GRAY + "Applies to the standalone/compact Kill effects widget, not the potion HUD row.");
    }

    private static void sendEffectLine(String effectCode) {
        int rgb = ConfigManager.getMoreHudKillEffectColor(effectCode);
        Main.sendMessage(EnumChatFormatting.DARK_GRAY + prettyName(effectCode) + " "
                + EnumChatFormatting.WHITE + "#" + String.format(Locale.ROOT, "%06X", rgb));
    }

    private static void resetAll() {
        ConfigManager.resetMoreHudKillEffectColor("FLAME");
        ConfigManager.resetMoreHudKillEffectColor("SPEED");
        ConfigManager.resetMoreHudKillEffectColor("RESISTANCE");
        ConfigManager.resetMoreHudKillEffectColor("REGENERATION");
    }

    private static void sendUsage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Usage: "
                + EnumChatFormatting.WHITE + "/morehudkecolor list"
                + EnumChatFormatting.GRAY + " | "
                + EnumChatFormatting.WHITE + "set <flame|speed|resistance|regen> RRGGBB"
                + EnumChatFormatting.GRAY + " | "
                + EnumChatFormatting.WHITE + "reset <effect|all>");
    }

    private static String parseEffectCode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        switch (value) {
            case "flame":
            case "flaming":
            case "flaming_arrows":
            case "rapid_fire":
                return "FLAME";
            case "speed":
                return "SPEED";
            case "resistance":
                return "RESISTANCE";
            case "regen":
            case "regeneration":
                return "REGENERATION";
            default:
                return null;
        }
    }

    private static String prettyName(String effectCode) {
        if (effectCode == null) {
            return "Flaming Arrows";
        }
        switch (effectCode) {
            case "SPEED":
                return "Speed";
            case "RESISTANCE":
                return "Resistance";
            case "REGENERATION":
                return "Regeneration";
            case "FLAME":
            default:
                return "Flaming Arrows";
        }
    }

    private static int parseRgbHex(String s) {
        if (s == null || s.isEmpty()) {
            throw new NumberFormatException();
        }
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.length() > 2 && (t.startsWith("0x") || t.startsWith("0X"))) {
            t = t.substring(2);
        }
        if (t.length() != 6) {
            throw new NumberFormatException();
        }
        return Integer.parseInt(t, 16) & 0xFFFFFF;
    }
}
