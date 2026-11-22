package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

/**
 * Viewport edge fade for scrollable settings GUIs (TAB QoL-style opacity ramp).
 */
public final class GuiScrollFade {

    public static final int MAX_FADE_H = 56;

    private static final ResourceLocation BUTTON_WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final Gui WIDGET_DRAW = new Gui() {};

    private GuiScrollFade() {
    }

    public static boolean intersectsVisible(int rowTop, int rowHeight, int visibleTop, int visibleBottom) {
        return rowTop + rowHeight > visibleTop && rowTop < visibleBottom;
    }

    /** Opacity for content near clipped top/bottom — gradual ramp, depth follows scroll amount. */
    public static float alphaForY(int centerY, int visibleTop, int visibleBottom, int scrollOffset, int maxScroll) {
        if (maxScroll <= 0) {
            return 1.0F;
        }
        int maxFadeH = Math.min(MAX_FADE_H, Math.max(24, (visibleBottom - visibleTop) / 3));
        float alpha = 1.0F;
        if (scrollOffset > 0) {
            float hidden = Math.min(1.0F, scrollOffset / (float) maxScroll);
            int fadeH = Math.max(14, Math.round(maxFadeH * (0.3F + 0.7F * hidden)));
            if (centerY < visibleTop + fadeH) {
                float t = (centerY - visibleTop) / (float) fadeH;
                alpha = scrollFadeRamp(t);
            }
        }
        if (scrollOffset < maxScroll) {
            float hidden = Math.min(1.0F, (maxScroll - scrollOffset) / (float) maxScroll);
            int fadeH = Math.max(14, Math.round(maxFadeH * (0.3F + 0.7F * hidden)));
            if (centerY > visibleBottom - fadeH) {
                float t = (visibleBottom - centerY) / (float) fadeH;
                alpha = Math.min(alpha, scrollFadeRamp(t));
            }
        }
        return Math.max(0.0F, Math.min(1.0F, alpha));
    }

    private static float scrollFadeRamp(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t;
    }

    public static int argb(float alpha, int rgb) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        if (a <= 0) {
            return 0;
        }
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    public static void drawStringWithShadow(FontRenderer fontRenderer, String text, int x, int y, int rgb, float alpha) {
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        fontRenderer.drawStringWithShadow(text, x, y, argb(alpha, rgb));
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Draws a left-column label on a scroll row ({@code rowTop + textYOffset}).
     */
    public static void drawLabel(FontRenderer fontRenderer, String text, int x, int rowTop, int textYOffset,
                                 int rowHeight, int visibleTop, int visibleBottom,
                                 int scrollOffset, int maxScroll) {
        if (!intersectsVisible(rowTop, rowHeight, visibleTop, visibleBottom)) {
            return;
        }
        float alpha = alphaForY(rowTop + rowHeight / 2, visibleTop, visibleBottom, scrollOffset, maxScroll);
        if (alpha <= 0.01F) {
            return;
        }
        drawStringWithShadow(fontRenderer, text, x, rowTop + textYOffset, 0xFFFFFF, alpha);
    }

    public static void drawStringAt(FontRenderer fontRenderer, String text, int x, int y, int rgb,
                                    int visibleTop, int visibleBottom, int scrollOffset, int maxScroll) {
        if (maxScroll <= 0) {
            fontRenderer.drawString(text, x, y, rgb);
            return;
        }
        float alpha = alphaForY(y + 4, visibleTop, visibleBottom, scrollOffset, maxScroll);
        if (alpha <= 0.01F) {
            return;
        }
        drawStringWithShadow(fontRenderer, text, x, y, rgb, alpha);
    }

    public static void drawButton(Minecraft mc, FontRenderer fontRenderer, GuiButton button,
                                  int mouseX, int mouseY, int visibleTop, int visibleBottom,
                                  int scrollOffset, int maxScroll) {
        if (button == null || !button.visible) {
            return;
        }
        float alpha = maxScroll <= 0 ? 1.0F
                : alphaForY(button.yPosition + button.height / 2, visibleTop, visibleBottom, scrollOffset, maxScroll);
        if (alpha <= 0.01F) {
            return;
        }
        if (button instanceof MobHealthTagIconButton) {
            ((MobHealthTagIconButton) button).drawButtonWithAlpha(mc, mouseX, mouseY, alpha);
            return;
        }

        int x = button.xPosition;
        int y = button.yPosition;
        int w = button.width;
        int h = button.height;
        boolean hovered = button.enabled && mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;

        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableLighting();
        mc.getTextureManager().bindTexture(BUTTON_WIDGETS);

        int hoverState = !button.enabled ? 0 : (hovered ? 2 : 1);
        int texV = 46 + hoverState * 20;
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        int half = w / 2;
        int rightW = w - half;
        WIDGET_DRAW.drawTexturedModalRect(x, y, 0, texV, half, h);
        WIDGET_DRAW.drawTexturedModalRect(x + half, y, 200 - rightW, texV, rightW, h);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        String text = button.displayString;
        if (text != null && !text.isEmpty()) {
            int baseColor = button.enabled ? (hovered ? 0xFFFFA0 : 0xE0E0E0) : 0xA0A0A0;
            if (text.indexOf('\u00a7') >= 0) {
                baseColor = 0xFFFFFF;
            }
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
            int textX = x + (w - fontRenderer.getStringWidth(plain)) / 2;
            int textY = y + (h - 8) / 2;
            drawStringWithShadow(fontRenderer, text, textX, textY, baseColor, alpha);
        }
    }

    public static void drawButtons(Minecraft mc, FontRenderer fontRenderer, Iterable<GuiButton> buttons,
                                   int mouseX, int mouseY, int visibleTop, int visibleBottom,
                                   int scrollOffset, int maxScroll) {
        for (GuiButton button : buttons) {
            drawButton(mc, fontRenderer, button, mouseX, mouseY, visibleTop, visibleBottom, scrollOffset, maxScroll);
        }
    }

    /** Draws every button in {@code buttonList} except those whose ids are in {@code excludeIds}. */
    public static void drawButtonsExcluding(Minecraft mc, FontRenderer fontRenderer,
                                            java.util.List<GuiButton> buttonList, int mouseX, int mouseY,
                                            int visibleTop, int visibleBottom, int scrollOffset, int maxScroll,
                                            int... excludeIds) {
        outer:
        for (GuiButton button : buttonList) {
            if (button == null) {
                continue;
            }
            for (int exclude : excludeIds) {
                if (button.id == exclude) {
                    continue outer;
                }
            }
            drawButton(mc, fontRenderer, button, mouseX, mouseY, visibleTop, visibleBottom, scrollOffset, maxScroll);
        }
    }
}
