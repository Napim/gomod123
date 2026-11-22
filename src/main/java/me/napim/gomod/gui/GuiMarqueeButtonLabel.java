package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.EnumChatFormatting;

/**
 * Draws a vanilla button chrome with clipped label text; on hover, long labels scroll left
 * so the full binding stays readable inside a fixed-width panel.
 */
public final class GuiMarqueeButtonLabel {

    private static final int TEXT_PAD = 6;
    private static final int SCROLL_MS_PER_PX = 35;
    private static final int HOVER_PAUSE_MS = 900;

    private GuiMarqueeButtonLabel() {
    }

    public static void draw(FontRenderer fr, GuiButton chrome, int mouseX, int mouseY, String text) {
        draw(fr, chrome, mouseX, mouseY, text, true);
    }

    /** @param drawChrome when {@code false}, only draws clipped text (button chrome already drawn). */
    public static void draw(FontRenderer fr, GuiButton chrome, int mouseX, int mouseY, String text, boolean drawChrome) {
        draw(fr, chrome, mouseX, mouseY, text, drawChrome, Integer.MIN_VALUE, Integer.MAX_VALUE, 1.0F);
    }

    /** Clipped/faded label for a button inside a scrolling settings viewport. */
    public static void draw(FontRenderer fr, GuiButton chrome, int mouseX, int mouseY, String text,
                            boolean drawChrome, int visibleTop, int visibleBottom, float alpha) {
        if (fr == null || chrome == null) {
            return;
        }
        if (drawChrome) {
            chrome.drawButton(Minecraft.getMinecraft(), mouseX, mouseY);
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null || plain.isEmpty()) {
            return;
        }
        int clipLeft = chrome.xPosition + TEXT_PAD;
        int clipRight = chrome.xPosition + chrome.width - TEXT_PAD;
        int visibleW = clipRight - clipLeft;
        if (visibleW <= 0) {
            return;
        }
        int textWidth = fr.getStringWidth(plain);
        int textY = chrome.yPosition + (chrome.height - 8) / 2;
        boolean hover = mouseX >= chrome.xPosition && mouseX < chrome.xPosition + chrome.width
                && mouseY >= chrome.yPosition && mouseY < chrome.yPosition + chrome.height;
        int drawX = textWidth <= visibleW ? clipLeft + (visibleW - textWidth) / 2 : clipLeft;
        int scroll = 0;
        if (hover && textWidth > visibleW) {
            drawX = clipLeft;
            int maxScroll = textWidth - visibleW;
            long scrollSpan = (long) maxScroll * SCROLL_MS_PER_PX;
            long cycle = HOVER_PAUSE_MS * 2L + scrollSpan;
            long phase = System.currentTimeMillis() % cycle;
            if (phase < HOVER_PAUSE_MS) {
                scroll = 0;
            } else if (phase < HOVER_PAUSE_MS + scrollSpan) {
                scroll = (int) ((phase - HOVER_PAUSE_MS) / SCROLL_MS_PER_PX);
            } else if (phase < HOVER_PAUSE_MS * 2L + scrollSpan) {
                scroll = maxScroll;
            } else {
                scroll = maxScroll - (int) ((phase - HOVER_PAUSE_MS * 2L - scrollSpan) / SCROLL_MS_PER_PX);
            }
            scroll = Math.max(0, Math.min(maxScroll, scroll));
        }
        int clipTop = Math.max(visibleTop, chrome.yPosition + 1);
        int clipBottom = Math.min(visibleBottom, chrome.yPosition + chrome.height - 1);
        if (clipBottom <= clipTop) return;
        SocialDraw.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
        try {
            if (chrome.enabled) {
                GuiScrollFade.drawStringWithShadow(fr, text, drawX - scroll, textY, 0xFFFFFF, alpha);
            } else {
                fr.drawString(text, drawX - scroll, textY, GuiScrollFade.argb(alpha, 0xA0A0A0));
            }
        } finally {
            SocialDraw.disableScissor();
        }
    }
}
