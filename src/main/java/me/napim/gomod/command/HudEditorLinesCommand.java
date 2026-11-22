package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.HudEditorCenterGuides;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * {@code /hudeditorlines} — toggle always-on HUD editor center guides (in GUIs and in-game).
 */
public class HudEditorLinesCommand extends Command {

    public HudEditorLinesCommand() {
        super("hudeditorlines", new String[] { "hudlines", "hudcenterlines" });
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0 || "toggle".equalsIgnoreCase(args[0])) {
            HudEditorCenterGuides.toggleAlwaysVisible();
            sendStatus();
            return;
        }
        String sub = args[0].toLowerCase();
        if ("on".equals(sub) || "enable".equals(sub) || "true".equals(sub)) {
            HudEditorCenterGuides.setAlwaysVisible(true);
            sendStatus();
            return;
        }
        if ("off".equals(sub) || "disable".equals(sub) || "false".equals(sub)) {
            HudEditorCenterGuides.setAlwaysVisible(false);
            sendStatus();
            return;
        }
        if ("status".equals(sub)) {
            sendStatus();
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /hudeditorlines [toggle|on|off|status]");
    }

    private void sendStatus() {
        boolean on = HudEditorCenterGuides.isAlwaysVisible();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "HUD editor center guides: "
                + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " (GUIs + in-game)");
    }
}
