package me.napim.gomod.command;

import me.napim.gomod.util.GuiOpener;
import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.command.Command;

public class PerspectiveGuiCommand extends Command {
    public PerspectiveGuiCommand() {
        super("perspective");
    }

    @Override
    public void handle(String[] args) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100L);
                    } catch (InterruptedException ignored) {}
                    Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            GuiOpener.open("me.napim.gomod.gui.PerspectiveGUI");
                        }
                    });
                }
            }, "OpenPerspectiveGui").start();
    }
}


