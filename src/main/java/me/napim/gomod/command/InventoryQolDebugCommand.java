package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.InventoryQolDebugCapture;
import me.napim.gomod.features.InventoryQolChestDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.IOException;

/** Captures inventory contents, overlay decisions, and the hotbar/HUD GL state around inventory QoL tints. */
public final class InventoryQolDebugCommand extends Command {
    private static boolean chestMode;
    public InventoryQolDebugCommand() {
        super("inventoryqoldebug");
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.requireInternalCommand("inventoryqoldebug")) {
            return;
        }
        String action = args.length == 0 ? "help" : args[0].trim().toLowerCase(java.util.Locale.ROOT);
        if ("chests".equals(action)) {
            int seconds = 300;
            if (args.length > 1) {
                try {
                    seconds = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                    usage();
                    return;
                }
            }
            seconds = Math.max(30, Math.min(900, seconds));
            try {
                if (!InventoryQolChestDebug.start(seconds)) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "An Inventory QoL capture is already running; use /inventoryqoldebug stop.");
                    return;
                }
                chestMode = true;
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + "Chest decision capture started for " + seconds + "s. Open chests/inventory normally;"
                        + " it saves automatically, or use /inventoryqoldebug stop.");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Optional: /inventoryqoldebug mark <what should be different> after an incorrect hint.");
            } catch (IOException error) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Could not start chest decision debug: " + error.getClass().getSimpleName());
            }
            return;
        }
        if ("mark".equals(action)) {
            if (args.length < 2) {
                usage();
                return;
            }
            StringBuilder note = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) note.append(' ');
                note.append(args[i]);
            }
            try {
                boolean added = InventoryQolChestDebug.mark(note.toString());
                Main.sendMessage(Main.CHAT_PREFIX + (added ? EnumChatFormatting.GREEN : EnumChatFormatting.RED)
                        + (added ? "Note added to the latest chest/inventory snapshot."
                        : "Start a chest capture first: /inventoryqoldebug chests."));
            } catch (IOException error) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Could not save debug note: " + error.getClass().getSimpleName());
            }
            return;
        }
        if ("start".equals(action)) {
            int seconds = 20;
            if (args.length > 1) {
                try {
                    seconds = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                    usage();
                    return;
                }
            }
            seconds = Math.max(5, Math.min(60, seconds));
            if (InventoryQolChestDebug.isArmed() || !InventoryQolDebugCapture.start(Minecraft.getMinecraft(), seconds)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "An Inventory QoL capture is already running; use /inventoryqoldebug stop.");
                return;
            }
            chestMode = false;
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Inventory QoL capture started for " + seconds + "s. Close chat and keep the affected hotbar visible;"
                    + " then use /inventoryqoldebug stop.");
            return;
        }
        if ("stop".equals(action)) {
            try {
                String path = chestMode ? InventoryQolChestDebug.stopAndSave()
                        : InventoryQolDebugCapture.stopAndSave(Minecraft.getMinecraft());
                if (path == null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "No Inventory QoL capture is available. Start one with /inventoryqoldebug start.");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                            + (chestMode ? "Inventory QoL chest debug saved: " : "Inventory QoL debug saved: ") + path);
                }
            } catch (IOException error) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Could not save Inventory QoL debug: " + error.getClass().getSimpleName());
            }
            return;
        }
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + (chestMode ? InventoryQolChestDebug.status() : InventoryQolDebugCapture.status()));
            return;
        }
        usage();
    }

    private static void usage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Usage: /inventoryqoldebug chests [30-900s] | mark <note> | start [5-60s] | stop | status");
    }
}
