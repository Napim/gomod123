package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import org.lwjgl.input.Mouse;

/**
 * Smooth vertical scroll with queued wheel impulses and a draggable scrollbar thumb
 * (same feel as {@link KillCounterSettingsGUI} / TAB QoL settings).
 */
public final class GuiSmoothScroll {

    private static final int DEFAULT_IMPULSE_PX = 18;
    private static final int DEFAULT_MAX_QUEUE_PX = 72;
    private static final int DEFAULT_MAX_STEP_PX = 1;

    private int scrollOffset;
    private int maxScroll;
    private int contentTop;
    private int contentBottom;
    private int visibleTop;
    private int visibleBottom;

    private int scrollDirection;
    private int amountToScroll;
    private long lastScrollTime;

    private int impulsePx = DEFAULT_IMPULSE_PX;
    private int maxQueuePx = DEFAULT_MAX_QUEUE_PX;
    private int maxStepPx = DEFAULT_MAX_STEP_PX;

    private boolean dragging;
    private int scrollGrabbedAtY;
    private int barLeft;
    private int barTop;
    private int barRight;
    private int barBottom;

    public void reset() {
        scrollOffset = 0;
        maxScroll = 0;
        amountToScroll = 0;
        scrollDirection = 0;
        dragging = false;
    }

    public int getOffset() {
        return scrollOffset;
    }

    public int getMaxScroll() {
        return maxScroll;
    }

    public boolean hasScroll() {
        return maxScroll > 0;
    }

    public int getVisibleTop() {
        return visibleTop;
    }

    public int getVisibleBottom() {
        return visibleBottom;
    }

    /** Draws buttons with viewport edge fade when scrollable; vanilla draw otherwise. */
    public void drawFadedButtons(Minecraft mc, FontRenderer fontRenderer, Iterable<GuiButton> buttons,
                                 int mouseX, int mouseY) {
        if (!hasScroll()) {
            for (GuiButton button : buttons) {
                if (button != null && button.visible) {
                    button.drawButton(mc, mouseX, mouseY);
                }
            }
            return;
        }
        GuiScrollFade.drawButtons(mc, fontRenderer, buttons, mouseX, mouseY,
                visibleTop, visibleBottom, scrollOffset, maxScroll);
    }

    public void drawFadedButtonsExcluding(Minecraft mc, FontRenderer fontRenderer,
                                          java.util.List<GuiButton> buttonList, int mouseX, int mouseY,
                                          int... excludeIds) {
        if (!hasScroll()) {
            outer:
            for (GuiButton button : buttonList) {
                if (button == null || !button.visible) {
                    continue;
                }
                for (int exclude : excludeIds) {
                    if (button.id == exclude) {
                        continue outer;
                    }
                }
                button.drawButton(mc, mouseX, mouseY);
            }
            return;
        }
        GuiScrollFade.drawButtonsExcluding(mc, fontRenderer, buttonList, mouseX, mouseY,
                visibleTop, visibleBottom, scrollOffset, maxScroll, excludeIds);
    }

    public void drawFadedString(FontRenderer fontRenderer, String text, int x, int y, int color) {
        GuiScrollFade.drawStringAt(fontRenderer, text, x, y, color,
                visibleTop, visibleBottom, scrollOffset, maxScroll);
    }

    public GuiSmoothScroll setWheelTuning(int impulsePx, int maxQueuePx, int maxStepPx) {
        this.impulsePx = Math.max(1, impulsePx);
        this.maxQueuePx = Math.max(this.impulsePx, maxQueuePx);
        this.maxStepPx = Math.max(1, maxStepPx);
        return this;
    }

    /**
     * @param contentTop    top of scrollable content in screen space (unscrolled)
     * @param contentBottom bottom of scrollable content in screen space (unscrolled)
     * @param visibleTop    top of visible clip region
     * @param visibleBottom bottom of visible clip region (above back bar, etc.)
     */
    public void updateBounds(int contentTop, int contentBottom, int visibleTop, int visibleBottom) {
        this.contentTop = contentTop;
        this.contentBottom = contentBottom;
        this.visibleTop = visibleTop;
        this.visibleBottom = visibleBottom;
        int visibleHeight = Math.max(1, visibleBottom - visibleTop);
        int totalContentHeight = Math.max(1, contentBottom - contentTop);
        maxScroll = Math.max(0, totalContentHeight - visibleHeight);
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            amountToScroll = 0;
        }
    }

    /** Call at the start of each {@code drawScreen} frame. */
    public void tick() {
        if (amountToScroll > 0 && maxScroll > 0) {
            long time = System.currentTimeMillis();
            if (time - lastScrollTime > 1L) {
                int step = Math.min(resolveStepPx(), amountToScroll);
                scrollBy(scrollDirection * step);
                amountToScroll -= step;
                lastScrollTime = time;
            }
        }
    }

    private int resolveStepPx() {
        if (maxStepPx <= 1) {
            return 1;
        }
        int scaled = 1 + amountToScroll / 10;
        return Math.max(1, Math.min(maxStepPx, scaled));
    }

    private void scrollBy(int amount) {
        scrollOffset = scrollOffset - amount;
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            amountToScroll = 0;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
            amountToScroll = 0;
        }
    }

    /**
     * Draws the track and thumb; updates thumb hit box for drag.
     *
     * @param screenWidth parent GUI width
     * @param mouseY      current mouse Y
     */
    public void drawScrollbar(int screenWidth, int mouseY) {
        drawScrollbarAt(screenWidth - 10, mouseY);
    }

    public void drawScrollbarAt(int trackX, int mouseY) {
        drawScrollbarAt(trackX, mouseY, 4, 0x50000000, 0xE8FFFFFF);
    }

    public void drawScrollbarAt(int trackX, int mouseY, int trackWidth, int trackColor, int thumbColor) {
        drawScrollbarAt(trackX, visibleTop, visibleBottom, mouseY, trackWidth, trackColor, thumbColor);
    }

    /** Draws the scrollbar with an explicit track region (stays inside panel borders). */
    public void drawScrollbarAt(int trackX, int trackTop, int trackBottom, int mouseY,
                                int trackWidth, int trackColor, int thumbColor) {
        if (maxScroll <= 0) {
            return;
        }
        trackWidth = Math.max(2, trackWidth);
        trackTop = Math.max(trackTop, visibleTop);
        trackBottom = Math.min(trackBottom, visibleBottom);
        if (trackBottom <= trackTop) {
            trackBottom = Math.max(trackTop + 1, visibleBottom);
        }
        int trackHeight = trackBottom - trackTop;
        int totalContentHeight = Math.max(1, contentBottom - contentTop);
        int visibleHeight = Math.max(1, visibleBottom - visibleTop);
        int thumbHeight = Math.max(6, (int) ((float) trackHeight * ((float) visibleHeight / (float) totalContentHeight)));
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int maxThumbTravel = Math.max(0, trackHeight - thumbHeight);
        int thumbY = trackTop + (maxScroll == 0 ? 0 : (int) ((float) maxThumbTravel * ((float) scrollOffset / (float) maxScroll)));
        thumbY = Math.min(trackTop + maxThumbTravel, Math.max(trackTop, thumbY));

        Gui.drawRect(trackX, trackTop, trackX + trackWidth, trackBottom, trackColor);
        Gui.drawRect(trackX, thumbY, trackX + trackWidth, thumbY + thumbHeight, thumbColor);

        barLeft = trackX;
        barTop = thumbY;
        barRight = trackX + trackWidth;
        barBottom = thumbY + thumbHeight;

        if (dragging) {
            int relativeMouseY = mouseY - trackTop - scrollGrabbedAtY;
            int clamped = Math.max(0, Math.min(maxThumbTravel, relativeMouseY));
            scrollOffset = maxThumbTravel <= 0 ? 0 : (int) ((float) maxScroll * ((float) clamped / (float) maxThumbTravel));
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
            amountToScroll = 0;
        }
    }

    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || maxScroll <= 0) {
            return false;
        }
        if (mouseX >= barLeft && mouseX < barRight && mouseY >= barTop && mouseY < barBottom) {
            dragging = true;
            scrollGrabbedAtY = mouseY - barTop;
            amountToScroll = 0;
            return true;
        }
        return false;
    }

    public void mouseReleased(int state) {
        if (state == 0 && dragging) {
            dragging = false;
        }
    }

    /** Cancels queued wheel movement and a captured thumb drag without changing the current offset. */
    public void cancelInteraction() {
        amountToScroll = 0;
        scrollDirection = 0;
        dragging = false;
    }

    /**
     * Instantly jump to a scroll offset without animation.
     * The value is clamped to [0, maxScroll]; if maxScroll has not been computed yet
     * (first frame), the clamp will be applied by the next {@link #updateBounds} call.
     */
    public void setOffsetClamped(int offset) {
        scrollOffset  = Math.max(0, offset);
        amountToScroll = 0;
        if (maxScroll > 0) {
            scrollOffset = Math.min(scrollOffset, maxScroll);
        }
    }

    public void handleMouseWheel() {
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0 || maxScroll <= 0) {
            return;
        }
        int notches = Math.max(1, Math.abs(dWheel) / 120);
        int queuedImpulse = Math.max(1, impulsePx * notches);
        if (dWheel > 1) {
            if (scrollDirection != 1) {
                scrollDirection = 1;
                amountToScroll = 0;
            }
            amountToScroll = Math.min(maxQueuePx, amountToScroll + queuedImpulse);
        }
        if (dWheel < -1) {
            if (scrollDirection != -1) {
                scrollDirection = -1;
                amountToScroll = 0;
            }
            amountToScroll = Math.min(maxQueuePx, amountToScroll + queuedImpulse);
        }
    }
}
