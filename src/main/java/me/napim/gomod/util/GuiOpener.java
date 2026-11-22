package me.napim.gomod.util;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

/**
 * Opens GUI screens via reflection to avoid early class loading during mod init.
 */
public final class GuiOpener {
    private GuiOpener() {}

    public static void open(String className) {
        open(className, new Class<?>[0], new Object[0]);
    }

    public static void open(String className, Class<?>[] ctorTypes, Object[] ctorArgs) {
        try {
            Class<?> cls = Class.forName(className);
            Object instance;
            if (ctorTypes == null || ctorTypes.length == 0) {
                instance = cls.getDeclaredConstructor().newInstance();
            } else {
                instance = cls.getDeclaredConstructor(ctorTypes).newInstance(ctorArgs);
            }

            if (!(instance instanceof GuiScreen)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to open GUI: not a GuiScreen (" + className + ")");
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.displayGuiScreen((GuiScreen) instance);
            }
        } catch (Throwable t) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to open GUI: " + className);
            System.err.println("[gomod] GuiOpener failed for " + className + ": " + t.getMessage());
        }
    }
}
