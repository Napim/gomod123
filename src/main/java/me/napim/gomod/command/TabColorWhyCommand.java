package me.napim.gomod.command;

import me.napim.gomod.features.KillCounter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.weavemc.loader.api.command.Command;

import java.util.List;

/**
 * Explains why a TAB row’s name appears with its current colors (Hypixel context, gomod cache, kill-effect rules).
 */
public class TabColorWhyCommand extends Command {

    public TabColorWhyCommand() {
        super("tabcolorwhy", new String[]{"tcw"});
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        String ign = args.length > 0 ? args[0] : null;
        List<String> lines = KillCounter.buildTabColorWhyLines(ign);
        for (String line : lines) {
            mc.thePlayer.addChatMessage(new ChatComponentText(line));
        }
    }
}
