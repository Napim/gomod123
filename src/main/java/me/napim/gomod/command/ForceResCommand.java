package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.ForceResistanceManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * Toggles client-side Resistance potion particles (Hypixel Blitz often hides them).
 * <ul>
 *   <li>{@code /forceres} — toggle ON/OFF</li>
 *   <li>{@code /forceres status} — show ON/OFF and numeric settings</li>
 *   <li>{@code /forceres spawn <0-12>} — base extra SPELL_MOB per tick (then scaled by opacity); aliases: {@code count}, {@code spawns}</li>
 *   <li>{@code /forceres opacity <0.0-1.5>} — scales spawn count (“how strong” the extra layer looks); aliases: {@code opa}, {@code strength}</li>
 *   <li>{@code /forceres sat <0.25-2.5>} — RGB saturation multiplier (1.0 ≈ vanilla resistance tint); aliases: {@code saturation}, {@code color}</li>
 * </ul>
 */
public final class ForceResCommand extends Command {

    public ForceResCommand() {
        super("forceres");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "[ForceRes] "
                    + (ForceResistanceManager.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
            Main.sendMessage(EnumChatFormatting.GRAY + "  spawn/tick (base) = "
                    + ConfigManager.getForceResistanceSpawnsPerTick());
            Main.sendMessage(EnumChatFormatting.GRAY + "  opacity scale = "
                    + String.format(Locale.ROOT, "%.2f", ConfigManager.getForceResistanceOpacityScale()));
            Main.sendMessage(EnumChatFormatting.GRAY + "  saturation = "
                    + String.format(Locale.ROOT, "%.2f", ConfigManager.getForceResistanceSaturation()));
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "  Particle: SPELL_MOB tint (extra layer for every player with Resistance).");
            return;
        }
        if (args.length >= 2) {
            String sub = args[0].toLowerCase();
            try {
                if ("spawn".equals(sub) || "count".equals(sub) || "spawns".equals(sub)) {
                    int v = Integer.parseInt(args[1]);
                    ConfigManager.setForceResistanceSpawnsPerTick(v);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "[ForceRes] spawn/tick = "
                            + ConfigManager.getForceResistanceSpawnsPerTick());
                    return;
                }
                if ("opacity".equals(sub) || "opa".equals(sub) || "strength".equals(sub)) {
                    float v = Float.parseFloat(args[1]);
                    ConfigManager.setForceResistanceOpacityScale(v);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "[ForceRes] opacity scale = "
                            + String.format("%.2f", ConfigManager.getForceResistanceOpacityScale()));
                    return;
                }
                if ("sat".equals(sub) || "saturation".equals(sub) || "color".equals(sub)) {
                    float v = Float.parseFloat(args[1]);
                    ConfigManager.setForceResistanceSaturation(v);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "[ForceRes] saturation = "
                            + String.format("%.2f", ConfigManager.getForceResistanceSaturation()));
                    return;
                }
            } catch (NumberFormatException ex) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "[ForceRes] Invalid number.");
                return;
            }
            printHelp();
            return;
        }
        if (args.length == 1 && ("help".equalsIgnoreCase(args[0]) || "?".equals(args[0]))) {
            printHelp();
            return;
        }

        ForceResistanceManager.toggle();
        Main.sendMessage(Main.CHAT_PREFIX + (ForceResistanceManager.isEnabled()
                ? EnumChatFormatting.GREEN + "[ForceRes] ON — extra Resistance SPELL_MOB while the effect is active."
                : EnumChatFormatting.RED + "[ForceRes] OFF"));
    }

    private static void printHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/forceres §7— toggle ON/OFF");
        Main.sendMessage(EnumChatFormatting.GRAY + "  /forceres status §7— current settings");
        Main.sendMessage(EnumChatFormatting.GRAY + "  /forceres spawn <0-12> §7— base SPELL_MOB / tick (× opacity)");
        Main.sendMessage(EnumChatFormatting.GRAY + "  /forceres opacity <0-1.5> §7— strength multiplier");
        Main.sendMessage(EnumChatFormatting.GRAY + "  /forceres sat <0.25-2.5> §7— tint saturation (1≈vanilla)");
        Main.sendMessage(EnumChatFormatting.DARK_GRAY + "  Aliases: count/spawns · opa/strength · saturation/color");
    }
}
