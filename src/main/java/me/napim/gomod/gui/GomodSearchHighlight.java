package me.napim.gomod.gui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;

/**
 * Carries a pending button-highlight across GUI navigations after search selection.
 */
public final class GomodSearchHighlight {

    private static final long DURATION_MS   = 2400L;
    /** Initial glow when the destination GUI first appears. */
    private static final long ENTRANCE_MS   = 650L;
    /** Gentle follow-up pulse (one slow cycle). */
    private static final long PULSE_MS      = 1200L;

    /** gomod123 brand green ({@link GuiHeader#GOMOD_BRAND_RGB}). */
    private static final int GLOW_RGB_GREEN   = 0x00AA00;
    private static final int GLOW_INNER_GREEN = 0x66FF66;
    private static final int GLOW_RGB_RED     = 0xAA0000;
    private static final int GLOW_INNER_RED   = 0xFF6666;

    private enum GlowStyle {
        GREEN,
        RED
    }

    private static int   pendingId       = -1;
    private static long  startMs         = -1L;
    private static Class<? extends GuiScreen> targetGuiClass = null;
    private static GlowStyle glowStyle = GlowStyle.GREEN;

    private GomodSearchHighlight() {}

    public static void schedule(int buttonId, Class<? extends GuiScreen> guiClass) {
        schedule(buttonId, guiClass, GlowStyle.GREEN);
    }

    /** Same pulse as search navigation, in red (e.g. forced-disabled setting feedback). */
    public static void scheduleRed(int buttonId, Class<? extends GuiScreen> guiClass) {
        schedule(buttonId, guiClass, GlowStyle.RED);
    }

    private static void schedule(int buttonId, Class<? extends GuiScreen> guiClass, GlowStyle style) {
        pendingId      = buttonId;
        startMs        = -1L;
        targetGuiClass = guiClass;
        glowStyle      = style != null ? style : GlowStyle.GREEN;
    }

    public static boolean hasPendingHighlight() {
        return pendingId >= 0;
    }

    public static int getButtonId() {
        return pendingId;
    }

    public static boolean matchesGui(Class<? extends GuiScreen> gui) {
        return targetGuiClass != null && targetGuiClass.equals(gui);
    }

    public static void clear() {
        pendingId      = -1;
        startMs        = -1L;
        targetGuiClass = null;
        glowStyle      = GlowStyle.GREEN;
    }

    public static void draw(List<GuiButton> buttons, Class<? extends GuiScreen> guiClass) {
        if (!matchesGui(guiClass) || pendingId < 0) {
            return;
        }
        if (startMs < 0L) {
            startMs = System.currentTimeMillis();
        }

        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed >= DURATION_MS) {
            clear();
            return;
        }

        float alpha = computeAlpha(elapsed);
        if (alpha <= 0.001f) {
            return;
        }
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        int glowRgb = glowStyle == GlowStyle.RED ? GLOW_RGB_RED : GLOW_RGB_GREEN;
        int glowInner = glowStyle == GlowStyle.RED ? GLOW_INNER_RED : GLOW_INNER_GREEN;

        for (GuiButton b : buttons) {
            if (b == null || b.id != pendingId) {
                continue;
            }
            int outerA = (int) (a * 0.55f);
            Gui.drawRect(b.xPosition - 2, b.yPosition - 2,
                    b.xPosition + b.width + 2, b.yPosition + b.height + 2,
                    (outerA << 24) | glowRgb);
            Gui.drawRect(b.xPosition - 1, b.yPosition - 1,
                    b.xPosition + b.width + 1, b.yPosition + b.height + 1,
                    (a << 24) | glowRgb);
            int innerA = (int) (a * 0.40f);
            Gui.drawRect(b.xPosition, b.yPosition,
                    b.xPosition + b.width, b.yPosition + b.height,
                    (innerA << 24) | glowInner);
            break;
        }
    }

    /** Soft entrance pulse, then one slow fade-out pulse — not a rapid flash. */
    private static float computeAlpha(long elapsedMs) {
        float fadeOut = Math.min(1f, (DURATION_MS - elapsedMs) / 700f);

        if (elapsedMs < ENTRANCE_MS) {
            float t = elapsedMs / (float) ENTRANCE_MS;
            float ease = 1f - (1f - t) * (1f - t);
            float pulse = (float) Math.sin(ease * Math.PI);
            return fadeOut * (0.14f + pulse * 0.38f);
        }

        float sinceEntrance = elapsedMs - ENTRANCE_MS;
        float pulseT = sinceEntrance / (float) PULSE_MS;
        float pulse = (float) Math.sin(pulseT * Math.PI);
        pulse = Math.max(0f, pulse);
        return fadeOut * (0.10f + pulse * 0.22f);
    }
}
