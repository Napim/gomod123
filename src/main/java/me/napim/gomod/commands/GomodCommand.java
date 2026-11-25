package me.napim.gomod.commands;

import me.napim.gomod.Main;
import me.napim.gomod.features.ApiKeyManager;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MixinStatus;
import me.napim.gomod.features.WaterClarity;
import me.napim.gomod.util.GuiOpener;
import net.weavemc.loader.api.command.Command;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.client.Minecraft;
import static me.napim.gomod.Main.sendMessage;

public class GomodCommand extends Command {
    public GomodCommand() {
        super("gomod");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            // Delay opening slightly so ChatScreen can close first, then open on main thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException ignored) {}
                    Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            GuiOpener.open("me.napim.gomod.gui.GomodGUI");
                        }
                    });
                }
            }, "OpenGomodGui").start();
        } else if (args.length >= 1) {
            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "help":
                    showHelp();
                    break;
                case "api":
                    handleApiCommand(args);
                    break;
                case "status":
                    showStatus();
                    break;
                case "update":
                case "updates":
                    handleUpdateCommand(args);
                    break;
                default:
                    showHelp();
                    break;
            }
        }
    }
    
    private void handleUpdateCommand(String[] args) {
        if (args.length >= 2 && "dismiss".equalsIgnoreCase(args[1])) {
            me.napim.gomod.features.GomodUpdateChecker.dismissCurrentUpdate();
            return;
        }
        me.napim.gomod.features.GomodAutoUpdater.checkForUpdatesAsync(true, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ignored) {}
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        GuiOpener.open("me.napim.gomod.gui.GomodUpdateGUI");
                    }
                });
            }
        }, "OpenGomodUpdateGui").start();
    }

    private void showHelp() {
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "gomod Commands:");
        sendMessage(EnumChatFormatting.YELLOW + "  /gomod" + EnumChatFormatting.WHITE + " - Open gomod Main Menu GUI");
        sendMessage(EnumChatFormatting.YELLOW + "  /gomod api hypixel <key>" + EnumChatFormatting.WHITE + " - Set Hypixel API key");
        sendMessage(EnumChatFormatting.YELLOW + "  /gomod status" + EnumChatFormatting.WHITE + " - Show current status");
        sendMessage(EnumChatFormatting.YELLOW + "  /gomod update" + EnumChatFormatting.WHITE + " - Check for and install gomod updates");
        sendMessage(EnumChatFormatting.YELLOW + "  /gomod help" + EnumChatFormatting.WHITE + " - Show this help message");
        sendMessage(EnumChatFormatting.GRAY + "gostats123, Map Queuer, Player Queuer, TAB QoL, HUD layout: use the main menu (gomod \u2192 buttons or Edit HUD).");
    }

    private void handleApiCommand(String[] args) {
        if (args.length < 2) {
            sendMessage(EnumChatFormatting.RED + "Usage: /gomod api <service> <key>");
            sendMessage(EnumChatFormatting.YELLOW + "Available service: hypixel");
            return;
        }
        
        String service = args[1].toLowerCase();
        if (service.equals("openai")) {
            sendMessage(EnumChatFormatting.GRAY + "gostats123 uses the authenticated gomod relay; no personal OpenAI key is needed.");
            sendMessage(EnumChatFormatting.YELLOW + "Check the daily allowance in gostats123 AI settings.");
            return;
        }
        if (!service.equals("hypixel")) {
            sendMessage(EnumChatFormatting.RED + "Unknown service: " + service);
            sendMessage(EnumChatFormatting.YELLOW + "Available service: hypixel");
            return;
        }
        if (args.length < 3) {
            // Show current API key status
            if (ApiKeyManager.hasApiKey("hypixel")) {
                sendMessage(EnumChatFormatting.GREEN + "Hypixel API key is set: " + ApiKeyManager.getMaskedApiKey("hypixel"));
            } else {
                sendMessage(EnumChatFormatting.RED + "No Hypixel API key is set.");
                sendMessage(EnumChatFormatting.YELLOW + "Usage: /gomod api hypixel YOUR_API_KEY");
            }
            return;
        }
        
        // Set API key
        String apiKey = args[2];
        ApiKeyManager.setApiKey("hypixel", apiKey);
        sendMessage(EnumChatFormatting.GREEN + "Hypixel API key has been set successfully!");
        sendMessage(EnumChatFormatting.YELLOW + "Kill effect commands are now ready to use.");
    }
    
    private void showStatus() {
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.BLUE + "gomod Status:");
        sendMessage(EnumChatFormatting.YELLOW + "gostats123 AI: " + 
                   (ConfigManager.isGoStatsEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        sendMessage(EnumChatFormatting.YELLOW + "gostats123 AI: "
                + EnumChatFormatting.GRAY + "uses the authenticated relay (quota in gostats123 settings)");
        sendMessage(EnumChatFormatting.YELLOW + "Hypixel API: " + 
                   (ApiKeyManager.hasApiKey("hypixel") ? EnumChatFormatting.GREEN + "Configured" : EnumChatFormatting.RED + "Not Configured"));
        sendMessage(EnumChatFormatting.YELLOW + "Auto Queue: " + 
                   (ConfigManager.isAutoQueueEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        sendMessage(EnumChatFormatting.YELLOW + "Inventory HUD: " + 
                   (ConfigManager.isInventoryHUDEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        sendMessage(EnumChatFormatting.YELLOW + "Mixins: "
                + EnumChatFormatting.WHITE + "font=" + (MixinStatus.fontRendererActive ? "on" : "off")
                + EnumChatFormatting.GRAY + " gui=" + (MixinStatus.guiIngameActive ? "on" : "off")
                + EnumChatFormatting.GRAY + " slot=" + (MixinStatus.slotMixinActive ? "on" : "off"));
        sendMessage(EnumChatFormatting.YELLOW + "Water clarity: "
                + (WaterClarity.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " tracking="
                + (WaterClarity.isSurfaceChunkTrackingActive() ? "air" : "submerged/off")
                + EnumChatFormatting.GRAY + " meshPatches=" + MixinStatus.waterClarityVertexPatches);
    }
}
