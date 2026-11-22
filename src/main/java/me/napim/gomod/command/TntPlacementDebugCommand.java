package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.TntPlacementPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Local Hypixel TNT placement tester (singleplayer / offline).
 * <p>
 * {@code /tntplacementdebug} toggles client-side primed-TNT placement at the NW corner
 * so you can compare the ghost preview to a real entity without Hypixel.
 */
public class TntPlacementDebugCommand extends Command {

    public TntPlacementDebugCommand() {
        super("tntplacementdebug");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            boolean next = !TntPlacementPreview.isDebugMode();
            TntPlacementPreview.setDebugMode(next);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "TNT placement debug: "
                    + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
            if (next) {
                Main.sendMessage(EnumChatFormatting.GRAY + "Hold TNT, tune ghost preview. Right-click places primed TNT "
                        + EnumChatFormatting.DARK_GRAY + "(plain TNT only; /gogive TNT won't prime while debug is on). "
                        + EnumChatFormatting.WHITE + "/tntplacementdebug help");
            }
            sendStatus();
            return;
        }
        String sub = args[0].trim().toLowerCase();
        if ("on".equals(sub) || "enable".equals(sub) || "true".equals(sub)) {
            TntPlacementPreview.setDebugMode(true);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TNT placement debug: ON");
            sendStatus();
            return;
        }
        if ("off".equals(sub) || "disable".equals(sub) || "false".equals(sub)) {
            TntPlacementPreview.setDebugMode(false);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "TNT placement debug: OFF");
            return;
        }
        if ("look".equals(sub) || "info".equals(sub) || "peek".equals(sub)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not in world.");
                return;
            }
            TntPlacementPreview.printLookInfo(mc.thePlayer);
            return;
        }
        if ("tnt".equals(sub) || "give".equals(sub)) {
            giveTnt();
            return;
        }
        if ("offset".equals(sub)) {
            handleOffset(args);
            return;
        }
        if ("opacity".equals(sub) || "alpha".equals(sub)) {
            handleOpacity(args);
            return;
        }
        if ("preview".equals(sub)) {
            if (args.length >= 2 && "off".equals(args[1].toLowerCase())) {
                ConfigManager.setTntPlacementPreviewEnabled(false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "TNT placement preview: OFF");
            } else {
                ConfigManager.setTntPlacementPreviewEnabled(true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TNT placement preview: ON");
            }
            return;
        }
        if ("help".equals(sub) || "?".equals(sub)) {
            sendHelp();
            return;
        }
        sendHelp();
    }

    private void handleOpacity(String[] args) {
        if (args.length >= 2 && "reset".equals(args[1].toLowerCase())) {
            TntPlacementPreview.resetGhostAlpha();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Ghost opacity reset to "
                    + EnumChatFormatting.WHITE + formatOpacityPercent(TntPlacementPreview.getGhostAlpha()));
            return;
        }
        if (args.length < 2) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Ghost opacity: "
                    + EnumChatFormatting.WHITE + formatOpacityPercent(TntPlacementPreview.getGhostAlpha()));
            Main.sendMessage(EnumChatFormatting.GRAY + "Usage: /tntplacementdebug opacity <0-100|0.0-1.0|reset>");
            return;
        }
        try {
            float value;
            String raw = args[1].trim();
            if (raw.endsWith("%")) {
                raw = raw.substring(0, raw.length() - 1).trim();
            }
            double parsed = Double.parseDouble(raw);
            if (parsed > 1.0D) {
                value = (float) (parsed / 100.0D);
            } else {
                value = (float) parsed;
            }
            TntPlacementPreview.setGhostAlpha(value);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Ghost opacity set to "
                    + EnumChatFormatting.WHITE + formatOpacityPercent(TntPlacementPreview.getGhostAlpha()));
        } catch (NumberFormatException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid opacity. Use 0-100, 0.0-1.0, or reset.");
        }
    }

    private static String formatOpacityPercent(float alpha) {
        return String.format("%.0f%% (%.2f)", alpha * 100.0f, alpha);
    }

    private void handleOffset(String[] args) {
        if (args.length >= 2 && "reset".equals(args[1].toLowerCase())) {
            TntPlacementPreview.resetCornerOffsets();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Offsets reset to Hypixel default (0.5, 0, 0.5)");
            return;
        }
        if (args.length < 4) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /tntplacementdebug offset <x> <y> <z>");
            Main.sendMessage(EnumChatFormatting.GRAY + "Offsets from block origin. Default: "
                    + EnumChatFormatting.WHITE + "0.5 0 0.5");
            Main.sendMessage(EnumChatFormatting.GRAY + "Current: "
                    + EnumChatFormatting.WHITE + formatOffsets());
            return;
        }
        try {
            double x = Double.parseDouble(args[1]);
            double y = Double.parseDouble(args[2]);
            double z = Double.parseDouble(args[3]);
            TntPlacementPreview.setCornerOffsets(x, y, z);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Corner offsets set to "
                    + EnumChatFormatting.WHITE + formatOffsets());
            Main.sendMessage(EnumChatFormatting.GRAY + "Ghost + debug placement use these until reset.");
        } catch (NumberFormatException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid numbers.");
        }
    }

    private static String formatOffsets() {
        return String.format("%.4f, %.4f, %.4f",
                TntPlacementPreview.getCornerOffsetX(),
                TntPlacementPreview.getCornerOffsetY(),
                TntPlacementPreview.getCornerOffsetZ());
    }

    private void giveTnt() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not in world.");
            return;
        }
        mc.thePlayer.inventory.addItemStackToInventory(new ItemStack(Blocks.tnt, 64));
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Gave 64× TNT.");
    }

    private void sendStatus() {
        Main.sendMessage(EnumChatFormatting.GRAY + "Debug: "
                + (TntPlacementPreview.isDebugMode() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " | Preview: "
                + (TntPlacementPreview.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " | Opacity: "
                + EnumChatFormatting.WHITE + formatOpacityPercent(TntPlacementPreview.getGhostAlpha())
                + EnumChatFormatting.GRAY + " | Offsets: " + EnumChatFormatting.WHITE + formatOffsets());
    }

    private void sendHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/tntplacementdebug"
                + EnumChatFormatting.WHITE + " — toggle Hypixel-style local TNT test");
        Main.sendMessage(EnumChatFormatting.GRAY + "  "
                + EnumChatFormatting.WHITE + "on | off"
                + EnumChatFormatting.GRAY + " — ghost preview + primed place (plain TNT only)");
        Main.sendMessage(EnumChatFormatting.GRAY + "  "
                + EnumChatFormatting.WHITE + "look"
                + EnumChatFormatting.GRAY + " — coords at crosshair (no place)");
        Main.sendMessage(EnumChatFormatting.GRAY + "  "
                + EnumChatFormatting.WHITE + "tnt"
                + EnumChatFormatting.GRAY + " — give 64 TNT");
        Main.sendMessage(EnumChatFormatting.GRAY + "  "
                + EnumChatFormatting.WHITE + "offset <x> <y> <z> | offset reset"
                + EnumChatFormatting.GRAY + " — tune NW corner");
        Main.sendMessage(EnumChatFormatting.GRAY + "  "
                + EnumChatFormatting.WHITE + "opacity <0-100|0.0-1.0> | opacity reset"
                + EnumChatFormatting.GRAY + " — ghost transparency");
        Main.sendMessage(EnumChatFormatting.GRAY + "  "
                + EnumChatFormatting.WHITE + "preview on|off"
                + EnumChatFormatting.GRAY + " — ghost overlay only");
    }
}
