package me.napim.gomod.command;

import me.napim.gomod.util.GuiOpener;
import me.napim.gomod.Main;
import net.weavemc.loader.api.command.Command;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.client.Minecraft;
import static me.napim.gomod.Main.sendMessage;

/**
 * Command class for managing the TAB QoL settings
 * Opens a GUI for easy configuration
 */
public class KillCounterSettingsCommand extends Command {
    
    public KillCounterSettingsCommand() {
        super("killcounter");
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
                            GuiOpener.open("me.napim.gomod.gui.KillCounterSettingsGUI");
                        }
                    });
                }
            }, "OpenKillCounterSettingsGui").start();
        } else {
            // Show help for command usage
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "TAB QoL Command:");
            sendMessage(EnumChatFormatting.YELLOW + "  /killcounter" + EnumChatFormatting.WHITE + " - Open TAB QoL Settings GUI");
            sendMessage(EnumChatFormatting.YELLOW + "  /killcounter help" + EnumChatFormatting.WHITE + " - Show this help message");
            sendMessage(EnumChatFormatting.GRAY + "All settings can be configured through the GUI interface.");
        }
    }
}
