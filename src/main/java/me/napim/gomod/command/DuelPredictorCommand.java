package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.DuelPredictor;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Controls duel predictor: {@code /duelpred}, ghost, hud, and lead ticks.
 */
public class DuelPredictorCommand extends Command {

    public DuelPredictorCommand() {
        super("duelpred", "dpred");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            DuelPredictor.toggle();
            status();
            return;
        }

        String sub = args[0].trim().toLowerCase();
        if (sub.equals("help") || sub.equals("?")) {
            help();
            return;
        }
        if (sub.equals("ghost")) {
            ConfigManager.setDuelPredictorGhostEnabled(!ConfigManager.isDuelPredictorGhostEnabled());
            status();
            return;
        }
        if (sub.equals("hud")) {
            ConfigManager.setDuelPredictorHudEnabled(!ConfigManager.isDuelPredictorHudEnabled());
            status();
            return;
        }
        if (sub.equals("lead") && args.length >= 2) {
            try {
                ConfigManager.setDuelPredictorLeadTicks(Integer.parseInt(args[1].trim()));
                status();
            } catch (NumberFormatException e) {
                Main.sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.RED
                        + "Lead ticks must be a number (1-8).");
            }
            return;
        }

        help();
    }

    private static void status() {
        Main.sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.YELLOW + "Duel predictor "
                + onOff(DuelPredictor.isEnabled())
                + EnumChatFormatting.GRAY + " | ghost " + onOff(ConfigManager.isDuelPredictorGhostEnabled())
                + EnumChatFormatting.GRAY + " | hud " + onOff(ConfigManager.isDuelPredictorHudEnabled())
                + EnumChatFormatting.GRAY + " | lead "
                + EnumChatFormatting.WHITE + ConfigManager.getDuelPredictorLeadTicks() + "t");
    }

    private static String onOff(boolean on) {
        return on ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off";
    }

    private static void help() {
        Main.sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.YELLOW
                + "/duelpred — toggle");
        Main.sendMessage(EnumChatFormatting.GRAY + "  ghost — toggle ghost preview");
        Main.sendMessage(EnumChatFormatting.GRAY + "  hud — toggle HUD");
        Main.sendMessage(EnumChatFormatting.GRAY + "  lead <1-8> — prediction lead ticks");
    }
}
