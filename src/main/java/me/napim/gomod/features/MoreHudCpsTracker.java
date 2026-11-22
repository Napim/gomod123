package me.napim.gomod.features;

import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

/**
 * Rolling 1s CPS windows for left / right mouse (used by {@link MoreHudFeature}).
 * Counts only while no GUI screen is open (inventory, chat, menus), so slot/chat clicks are ignored.
 */
public final class MoreHudCpsTracker {

    private static final long WINDOW_MS = 1000L;
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();
    private static boolean wasLeft;
    private static boolean wasRight;

    private MoreHudCpsTracker() {
    }

    /** True when CPS should reflect world clicks only (no open {@link GuiScreen}). */
    public static boolean shouldCountClicks(Minecraft mc) {
        return mc != null && mc.currentScreen == null;
    }

    /**
     * Samples mouse edges every frame. When a GUI is open, updates edge state without counting so
     * returning to the world does not register a spurious click.
     */
    public static void pollEdges(Minecraft mc) {
        if (mc == null) {
            return;
        }
        boolean l;
        boolean r;
        try {
            l = Mouse.isButtonDown(0);
            r = Mouse.isButtonDown(1);
        } catch (Throwable t) {
            return;
        }
        long now = System.currentTimeMillis();
        if (shouldCountClicks(mc)) {
            if (l && !wasLeft) {
                push(LEFT, now);
            }
            if (r && !wasRight) {
                push(RIGHT, now);
            }
        }
        wasLeft = l;
        wasRight = r;
        prune(LEFT, now);
        prune(RIGHT, now);
    }

    private static void push(ArrayDeque<Long> q, long now) {
        q.addLast(now);
        prune(q, now);
    }

    private static void prune(ArrayDeque<Long> q, long now) {
        long cut = now - WINDOW_MS;
        while (!q.isEmpty() && q.peekFirst() < cut) {
            q.removeFirst();
        }
    }

    public static int getLeftCps() {
        prune(LEFT, System.currentTimeMillis());
        return LEFT.size();
    }

    public static int getRightCps() {
        prune(RIGHT, System.currentTimeMillis());
        return RIGHT.size();
    }
}
