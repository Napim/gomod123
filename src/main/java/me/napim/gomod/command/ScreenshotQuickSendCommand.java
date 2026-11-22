package me.napim.gomod.command;

import me.napim.gomod.gui.ScreenshotShareGUI;
import me.napim.gomod.features.ScreenshotShareService;
import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.command.Command;

public class ScreenshotQuickSendCommand extends Command {
    public ScreenshotQuickSendCommand() {
        super("gssend");
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0) {
            ScreenshotShareService.shareLatestToPlayer(args[0]);
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> mc.displayGuiScreen(new ScreenshotShareGUI(mc.currentScreen)));
    }
}
