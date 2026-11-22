package me.napim.gomod.command;

import me.napim.gomod.gui.ScreenshotEditorGUI;
import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.command.Command;

/** Opens the screenshot editor on the latest screenshot. Triggered by the [Edit] chat button. */
public class GsEditCommand extends Command {
    public GsEditCommand() {
        super("gsedit");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                Minecraft m = Minecraft.getMinecraft();
                if (m != null) {
                    m.displayGuiScreen(new ScreenshotEditorGUI(m.currentScreen));
                }
            }
        });
    }
}
