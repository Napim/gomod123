package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

/**
 * Three HUD slider looks for comparison (e.g. Mob Health Display settings).
 */
public enum HudSliderStyle {
    /** Dark inset track with colored fill + top highlight (legacy mob-health look). */
    FILL_BAR,
    /** Vanilla button row with sliding knob. */
    VANILLA_KNOB,
    /** Flat gomod track: muted rail, accent fill, square thumb + label under rail. */
    INSET_THUMB;

    /** Standard blue fill for HUD background-opacity sliders (Mob Health, More HUD modules, etc.). */
    public static final int HUD_OPACITY_FILL_RGB = 0xFF2196F3;

    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final Gui GUI_DRAW = new Gui() {};

    public void draw(FontRenderer fr, int x, int y, int width, int height, float normalized, int accentArgb,
            String label) {
        draw(fr, x, y, width, height, normalized, accentArgb, label, null);
    }

    public void draw(FontRenderer fr, int x, int y, int width, int height, float normalized, int accentArgb,
            String label, HudOpacitySliderBoxInsets.Module insetModule) {
        normalized = clamp01(normalized);
        switch (this) {
            case FILL_BAR:
                if (insetModule != null) {
                    HudOpacitySliderBoxInsets.draw(fr, insetModule, x, y, width, height, normalized, accentArgb, label);
                } else {
                    drawFillBar(fr, x, y, width, height, normalized, accentArgb, label);
                }
                break;
            case VANILLA_KNOB:
                drawVanillaKnob(fr, x, y, width, height, normalized, label);
                break;
            case INSET_THUMB:
                drawInsetThumb(fr, x, y, width, height, normalized, accentArgb, label);
                break;
            default:
                break;
        }
    }

    public float normalizedFromMouseX(int mouseX, int x, int width) {
        return normalizedFromMouseX(mouseX, x, width, null);
    }

    public float normalizedFromMouseX(int mouseX, int x, int width, HudOpacitySliderBoxInsets.Module insetModule) {
        if (width <= 1) {
            return 0f;
        }
        switch (this) {
            case VANILLA_KNOB:
                return clamp01((mouseX - (x + 4)) / (float) (width - 8));
            case INSET_THUMB:
                int thumb = 6;
                int innerW = Math.max(1, width - thumb);
                return clamp01((mouseX - x - thumb / 2) / (float) innerW);
            case FILL_BAR:
                return HudOpacitySliderBoxInsets.normalizedFromMouseXOuter(mouseX, x, width);
            default:
                return clamp01((mouseX - (x + 1)) / (float) Math.max(1, width - 2));
        }
    }

    private static void drawFillBar(FontRenderer fr, int x, int y, int width, int height, float normalized,
            int accentArgb, String label) {
        Gui.drawRect(x, y, x + width, y + height, 0xFF1A1A1A);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF0F0F0F);
        int fillWidth = Math.max(0, Math.min(width - 2, (int) (normalized * (width - 2))));
        Gui.drawRect(x + 1, y + 1, x + 1 + fillWidth, y + height - 1, accentArgb);
        if (fillWidth > 0) {
            Gui.drawRect(x + 1, y + 1, x + 1 + fillWidth, y + 2, 0x40FFFFFF);
        }
        Gui.drawRect(x, y, x + width, y + 1, 0xFF404040);
        Gui.drawRect(x, y + height - 1, x + width, y + height, 0xFF202020);
        Gui.drawRect(x, y, x + 1, y + height, 0xFF404040);
        Gui.drawRect(x + width - 1, y, x + width, y + height, 0xFF202020);
        drawCenteredLabel(fr, label, x, y, width, height, 0xFFFFFFFF, true);
    }

    private static void drawVanillaKnob(FontRenderer fr, int x, int y, int width, int height, float normalized,
            String label) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.getTextureManager().bindTexture(WIDGETS);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        int v = 46;
        int half = width / 2;
        GUI_DRAW.drawTexturedModalRect(x, y, 0, v, half, height);
        GUI_DRAW.drawTexturedModalRect(x + half, y, 200 - half, v, width - half, height);
        int knobX = x + (int) (normalized * Math.max(0, width - 8));
        knobX = Math.min(knobX, x + width - 8);
        GUI_DRAW.drawTexturedModalRect(knobX, y, 0, 66, 4, height);
        GUI_DRAW.drawTexturedModalRect(knobX + 4, y, 196, 66, 4, height);
        drawCenteredLabel(fr, label, x, y, width, height, 0xE0E0E0, true);
    }

    private static void drawInsetThumb(FontRenderer fr, int x, int y, int width, int height, float normalized,
            int accentArgb, String label) {
        int thumb = Math.max(6, height - 4);
        int trackY = y + 2;
        int trackH = Math.max(6, height - 10);
        Gui.drawRect(x, trackY, x + width, trackY + trackH, 0xFF151515);
        Gui.drawRect(x + 1, trackY + 1, x + width - 1, trackY + trackH - 1, 0xFF0A0A0A);
        int innerW = Math.max(1, width - thumb);
        int fillEnd = x + 1 + (int) (normalized * innerW);
        if (fillEnd > x + 1) {
            Gui.drawRect(x + 1, trackY + 1, fillEnd, trackY + trackH - 1, accentArgb);
        }
        int thumbX = x + (int) (normalized * innerW);
        thumbX = Math.max(x, Math.min(thumbX, x + width - thumb));
        Gui.drawRect(thumbX, trackY - 1, thumbX + thumb, trackY + trackH, 0xFF2A2A2A);
        Gui.drawRect(thumbX + 1, trackY, thumbX + thumb - 1, trackY + trackH - 1, 0xFFF0F0F0);
        Gui.drawRect(x, trackY, x + width, trackY + 1, 0xFF3A3A3A);
        Gui.drawRect(x, trackY + trackH - 1, x + width, trackY + trackH, 0xFF080808);
        if (label != null && !label.isEmpty()) {
            int lw = fr.getStringWidth(label);
            fr.drawString(label, x + (width - lw) / 2, y + height - 8, 0xFFCCCCCC, false);
        }
    }

    private static void drawCenteredLabel(FontRenderer fr, String label, int x, int y, int width, int height,
            int color, boolean shadow) {
        if (label == null || label.isEmpty()) {
            return;
        }
        int textWidth = fr.getStringWidth(label);
        int textX = x + (width - textWidth) / 2;
        int textY = y + (height - 8) / 2;
        if (shadow) {
            fr.drawString(label, textX + 1, textY + 1, 0x80000000, false);
        }
        fr.drawString(label, textX, textY, color, false);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static String styleTag(HudSliderStyle style) {
        switch (style) {
            case FILL_BAR:
                return EnumChatFormatting.GRAY + "(Style A: fill bar)";
            case VANILLA_KNOB:
                return EnumChatFormatting.GRAY + "(Style B: vanilla knob)";
            case INSET_THUMB:
                return EnumChatFormatting.GRAY + "(Style C: inset thumb)";
            default:
                return "";
        }
    }
}
