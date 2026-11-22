package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.BestArmorContainerRenderStrategy;
import me.napim.gomod.features.BestArmorDisplay;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Switches best-armor GUI hooking ({@code /fixbestarmor 1-8}) and optional magenta debug paint ({@code /fixbestarmor debug}).
 */
public class FixBestArmorCommand extends Command {

    public FixBestArmorCommand() {
        super("fixbestarmor");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            printCurrent();
            printHelp();
            return;
        }
        String a = args[0].trim();
        if (a.equalsIgnoreCase("help") || a.equalsIgnoreCase("?")) {
            printCurrent();
            printHelp();
            return;
        }
        if (a.equalsIgnoreCase("debug")) {
            if (!GomodInternalFeatures.requireInternalCommand("fixbestarmor debug")) return;
            boolean next = !ConfigManager.isBestArmorDebugOverlayPaint();
            ConfigManager.setBestArmorDebugOverlayPaint(next);
            Main.sendMessage(Main.CHAT_PREFIX + (next ? EnumChatFormatting.LIGHT_PURPLE : EnumChatFormatting.GRAY)
                + "Best armor debug paint (magenta on armor slots): " + (next ? "ON" : "OFF"));
            Main.sendMessage(EnumChatFormatting.GRAY + "Open inventory, then try /fixbestarmor 6 (z-order hook tail) for strongest results.");
            return;
        }
        // Keyword aliases for easier testing
        if (a.equalsIgnoreCase("hook") || a.equalsIgnoreCase("zorder")) {
            applyMode(6);
            return;
        }
        if (a.equalsIgnoreCase("weave")) {
            applyMode(7);
            return;
        }
        if (a.equalsIgnoreCase("runTick") || a.equalsIgnoreCase("runtime")) {
            applyMode(5);
            return;
        }
        int mode;
        try {
            mode = Integer.parseInt(a);
        } catch (NumberFormatException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + (GomodInternalFeatures.isEnabled()
                    ? "Use 1-8, /fixbestarmor debug, or help"
                    : "Use 1-8 or help"));
            return;
        }
        if (mode < 1 || mode > 8) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Mode must be 1-8.");
            return;
        }
        applyMode(mode);
    }

    private static void applyMode(int mode) {
        String desc = BestArmorContainerRenderStrategy.setAndPersist(mode);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Best armor GUI hook: mode " + mode);
        Main.sendMessage(EnumChatFormatting.GRAY + desc);
        if (!BestArmorDisplay.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.YELLOW + "Best armor display is OFF — /bestarmor on");
        } else if (GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "Try /fixbestarmor debug to confirm what’s drawing on your screen.");
        }
    }

    private static void printCurrent() {
        int m = BestArmorContainerRenderStrategy.get();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Mode " + m + " — "
            + BestArmorContainerRenderStrategy.describe(m));
        if (GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "Debug magenta: "
                + (ConfigManager.isBestArmorDebugOverlayPaint() ? "ON" : "OFF"));
        }
    }

    private static void printHelp() {
        if (GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 8" + EnumChatFormatting.WHITE
                + " — per-slot drawSlot HEAD + panel background hook (recommended z-order)");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 6" + EnumChatFormatting.WHITE
                + " — GuiContainer.drawScreen tail hook (z-order after slots/items; recommended)");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 5" + EnumChatFormatting.WHITE
                + " — after GuiScreen.drawScreen in Minecraft.runTick");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 7" + EnumChatFormatting.WHITE
                + " — Weave RenderGameOverlayEvent.Post fallback (may be buried)");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 1" + EnumChatFormatting.WHITE + " — drawSlot HEAD");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 2" + EnumChatFormatting.WHITE + " — drawScreen→drawSlot redirect");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 3" + EnumChatFormatting.WHITE + " — 1+2");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor 4" + EnumChatFormatting.WHITE + " — GuiContainer.drawScreen RETURN");
        } else {
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor <mode>" + EnumChatFormatting.WHITE
                + " — choose the best-armor highlight drawing mode (1–8)");
        }
        if (GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /fixbestarmor debug" + EnumChatFormatting.WHITE
                + " — magenta on armor slots (helps confirm z-order)");
        }
        Main.sendMessage(EnumChatFormatting.DARK_GRAY + "Survival % text: depends on mode (5/6/7). Log: [gomod] BestArmor: ...");
        Main.sendMessage(EnumChatFormatting.DARK_GRAY + "Config: bestArmorContainerRenderStrategy, bestArmorDebugOverlayPaint");
    }
}
