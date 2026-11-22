package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MoreHudFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * Lists More HUD potion effect name colors and applies temporary RGB overrides (until clear or restart).
 * <p>
 * Usage:<br>
 * {@code /morehudpotioncolor} or {@code list} — table of ids / names / hex<br>
 * {@code /morehudpotioncolor set <idOrName> <RRGGBB>} — e.g. {@code set 1 FF0000} or {@code set speed 66D9FF}<br>
 * {@code /morehudpotioncolor clear} — remove all overrides
 */
public class MoreHudPotionColorCommand extends Command {

    public MoreHudPotionColorCommand() {
        super("morehudpotioncolor");
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
            MoreHudFeature.clearMoreHudPotionColorDebugOverrides();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Cleared all potion color debug overrides.");
            return;
        }
        if (args.length >= 3 && "set".equalsIgnoreCase(args[0])) {
            int id = parsePotionId(args[1]);
            if (id < 1 || id > 23) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid effect id or name. Use /morehudpotioncolor list");
                return;
            }
            int rgb;
            try {
                rgb = parseRgbHex(args[2]);
            } catch (NumberFormatException e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Bad color (use 6 hex digits, e.g. FF8800 or #FF8800).");
                return;
            }
            MoreHudFeature.setMoreHudPotionColorDebugOverride(id, rgb);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Override: id "
                    + id + " → #" + String.format(Locale.ROOT, "%06X", rgb));
            return;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Usage: "
                + EnumChatFormatting.WHITE + "/morehudpotioncolor list"
                + EnumChatFormatting.GRAY + " | "
                + EnumChatFormatting.WHITE + "set <id|name> RRGGBB"
                + EnumChatFormatting.GRAY + " | "
                + EnumChatFormatting.WHITE + "clear");
    }

    private void sendList() {
        boolean vanilla = ConfigManager.isMoreHudPotionsEffectColors();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[More HUD potion name colors]");
        Main.sendMessage(EnumChatFormatting.GRAY + "Vanilla effect colors: "
                + (vanilla ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " (off = one color "
                + EnumChatFormatting.WHITE + "#" + String.format(Locale.ROOT, "%06X",
                        ConfigManager.getMoreHudPotionsColor() & 0xFFFFFF)
                + EnumChatFormatting.GRAY + " for all unless "
                + EnumChatFormatting.WHITE + "set"
                + EnumChatFormatting.GRAY + " override).");
        Main.sendMessage(EnumChatFormatting.GRAY + "Timer color: "
                + EnumChatFormatting.WHITE + "#" + String.format(Locale.ROOT, "%06X",
                        ConfigManager.getMoreHudPotionsDurationColor() & 0xFFFFFF)
                + EnumChatFormatting.GRAY + " (Effects HUD → Color settings). "
                + "Kill-effects widget colors: "
                + EnumChatFormatting.WHITE + "/morehudkecolor" + EnumChatFormatting.GRAY + ".");
        for (int id = 1; id <= 23; id++) {
            Potion p = id < Potion.potionTypes.length ? Potion.potionTypes[id] : null;
            String name = p != null ? I18n.format(p.getName()) : "?";
            int shown = MoreHudFeature.previewMoreHudPotionNameColor(id);
            int ov = MoreHudFeature.getMoreHudPotionColorDebugOverride(id);
            String hex = String.format(Locale.ROOT, "#%06X", shown);
            String extra = ov >= 0 ? EnumChatFormatting.YELLOW + " [debug]" : "";
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + String.format(Locale.ROOT, "%2d", id) + " "
                    + EnumChatFormatting.WHITE + name + " "
                    + EnumChatFormatting.GRAY + hex + extra);
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

    private static int parsePotionId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (n >= 1 && n <= 23) {
                return n;
            }
        } catch (NumberFormatException ignored) {
        }
        switch (raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_')) {
            case "speed":
                return Potion.moveSpeed.id;
            case "slowness":
            case "slow":
                return Potion.moveSlowdown.id;
            case "haste":
                return Potion.digSpeed.id;
            case "fatigue":
            case "mining_fatigue":
                return Potion.digSlowdown.id;
            case "strength":
                return Potion.damageBoost.id;
            case "heal":
            case "instant_health":
                return Potion.heal.id;
            case "harm":
            case "instant_damage":
                return Potion.harm.id;
            case "jump":
            case "jump_boost":
                return Potion.jump.id;
            case "nausea":
                return Potion.confusion.id;
            case "regen":
            case "regeneration":
                return Potion.regeneration.id;
            case "resistance":
                return Potion.resistance.id;
            case "fire_res":
            case "fire_resistance":
                return Potion.fireResistance.id;
            case "water":
            case "water_breathing":
                return Potion.waterBreathing.id;
            case "invis":
            case "invisibility":
                return Potion.invisibility.id;
            case "blind":
            case "blindness":
                return Potion.blindness.id;
            case "night":
            case "night_vision":
                return Potion.nightVision.id;
            case "hunger":
                return Potion.hunger.id;
            case "weak":
            case "weakness":
                return Potion.weakness.id;
            case "poison":
                return Potion.poison.id;
            case "wither":
                return Potion.wither.id;
            case "health_boost":
                return Potion.healthBoost.id;
            case "absorption":
                return Potion.absorption.id;
            case "saturation":
                return Potion.saturation.id;
            default:
                return -1;
        }
    }
}
