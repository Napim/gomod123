package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Opens gomod GUIs after the current chat/toast GUI has fully released input. */
public final class GomodGuiOpenHelper {
    private static final Object PENDING_LOCK = new Object();
    private static Minecraft pendingMinecraft;
    private static GuiScreen pendingGuiAfterMouseRelease;

    private GomodGuiOpenHelper() {
    }

    public static void openClean(final Minecraft mc, final GuiScreen gui) {
        openClean(mc, gui, false);
    }

    public static void openCleanAfterMouseRelease(final Minecraft mc, final GuiScreen gui) {
        if (mc == null || gui == null) {
            return;
        }
        synchronized (PENDING_LOCK) {
            pendingMinecraft = mc;
            pendingGuiAfterMouseRelease = gui;
        }
    }

    public static void clientTick() {
        if (Mouse.isButtonDown(0)) {
            return;
        }
        Minecraft mc;
        GuiScreen gui;
        synchronized (PENDING_LOCK) {
            mc = pendingMinecraft;
            gui = pendingGuiAfterMouseRelease;
            pendingMinecraft = null;
            pendingGuiAfterMouseRelease = null;
        }
        if (mc != null && gui != null) {
            openClean(mc, gui, false);
        }
    }

    private static void openClean(final Minecraft mc, final GuiScreen gui, final boolean ignored) {
        if (mc == null || gui == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (mc.currentScreen != null) {
                    mc.displayGuiScreen(null);
                }
                drainInputEvents();
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (mc.thePlayer != null || mc.theWorld == null) {
                            drainInputEvents();
                            mc.displayGuiScreen(gui);
                        }
                    }
                });
            }
        });
    }

    private static void drainInputEvents() {
        try {
            while (Mouse.next()) {
                // Drain the click that opened the GUI so it cannot hit the new screen.
            }
        } catch (Throwable ignored) {
        }
        try {
            while (Keyboard.next()) {
                // Drain pending chat command / ESC key events from the previous screen.
            }
        } catch (Throwable ignored) {
        }
    }
}
