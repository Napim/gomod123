package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Background-opacity slider with an {@link HsvColorPickerWidget} on the left (Friend Inventory / More HUD style).
 */
public final class HudBackgroundOpacityRow {

    private static final int PICKER_GAP = 4;

    private final HsvColorPickerWidget picker = new HsvColorPickerWidget();
    private final IntSupplier getOpacity;
    private final IntConsumer setOpacity;
    private final IntSupplier getColorRgb;
    private final IntConsumer setColorRgb;

    private HudOpacitySliderBoxInsets.Module insetModule = HudOpacitySliderBoxInsets.Module.DEFAULT;
    /** {@link HudSliderStyle#VANILLA_KNOB} = full-width vanilla button + knob (Effects HUD settings). */
    private HudSliderStyle sliderStyle = HudSliderStyle.FILL_BAR;
    private int rowX;
    private int rowY;
    private int totalWidth;
    private int height = 20;
    private boolean draggingOpacity;

    public HudBackgroundOpacityRow(IntSupplier getOpacity, IntConsumer setOpacity, IntSupplier getColorRgb,
            IntConsumer setColorRgb) {
        this.getOpacity = getOpacity;
        this.setOpacity = setOpacity;
        this.getColorRgb = getColorRgb;
        this.setColorRgb = setColorRgb;
        HsvColorPickerWidget.resetIconCache();
        picker.setExpandToRight(true);
        picker.setListener(new HsvColorPickerWidget.Listener() {
            @Override
            public void onColorChanged(int rgb) {
                applyColor(rgb);
            }

            @Override
            public void onColorChangeComplete(int rgb) {
                applyColor(rgb);
            }
        });
    }

    public void setInsetModule(HudOpacitySliderBoxInsets.Module insetModule) {
        this.insetModule = insetModule != null ? insetModule : HudOpacitySliderBoxInsets.Module.DEFAULT;
    }

    public void setSliderStyle(HudSliderStyle sliderStyle) {
        this.sliderStyle = sliderStyle != null ? sliderStyle : HudSliderStyle.FILL_BAR;
    }

    public void setBounds(int rowX, int rowY, int totalWidth, int height) {
        this.rowX = rowX;
        this.rowY = rowY;
        this.totalWidth = totalWidth;
        this.height = height;
        picker.setPosition(rowX, rowY);
    }

    public void syncPickerFromConfig() {
        picker.syncFromRgb(getColorRgb.getAsInt());
    }

    public int sliderX() {
        return rowX + picker.getButtonWidth() + PICKER_GAP;
    }

    public int sliderWidth() {
        return Math.max(8, totalWidth - picker.getButtonWidth() - PICKER_GAP);
    }

    public int fillArgb() {
        int rgb = getColorRgb.getAsInt() & 0xFFFFFF;
        if (rgb == 0) {
            return HudSliderStyle.HUD_OPACITY_FILL_RGB;
        }
        return 0xFF000000 | rgb;
    }

    public void drawSlider(FontRenderer fr, String label) {
        int alpha = getOpacity.getAsInt();
        float normalized = alpha / 255.0f;
        int sx = sliderX();
        int sw = sliderWidth();
        if (sliderStyle == HudSliderStyle.VANILLA_KNOB) {
            HudSliderStyle.VANILLA_KNOB.draw(fr, sx, rowY, sw, height, normalized, fillArgb(), label);
        } else {
            HudOpacitySliderBoxInsets.drawValueBar(fr, insetModule, sx, rowY, sw, height, alpha, fillArgb(), label);
        }
    }

    /**
     * Swatch button only — call before {@link net.minecraft.client.gui.GuiScreen#drawScreen} so the bottom
     * back bar is not covered when the opacity row sits near it.
     */
    public void drawPickerButton(Minecraft mc, int mouseX, int mouseY) {
        picker.setPosition(rowX, rowY);
        picker.drawButton(mc, mouseX, mouseY);
    }

    /** Expanded panel (and swatch redraw when open) — call after buttons; redraw back bar on top afterward. */
    public void drawPickerOverlay(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        picker.setPosition(rowX, rowY);
        picker.drawExpanded(mc, mouseX, mouseY, partialTicks);
        if (picker.isOpenOrAnimating()) {
            picker.drawButton(mc, mouseX, mouseY);
        }
    }

    /** @deprecated Prefer {@link #drawPickerButton} + {@link #drawPickerOverlay} with back bar redrawn last. */
    public void drawPicker(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        drawPickerButton(mc, mouseX, mouseY);
        drawPickerOverlay(mc, mouseX, mouseY, partialTicks);
    }

    public boolean isMouseOverSlider(int mouseX, int mouseY) {
        return mouseX >= sliderX() && mouseX <= sliderX() + sliderWidth()
                && mouseY >= rowY && mouseY <= rowY + height;
    }

    /** @return true if the event was consumed (picker or slider). */
    public boolean mouseClicked(Minecraft mc, int mouseX, int mouseY, int mouseButton) {
        if (picker.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
            return true;
        }
        if (mouseButton == 0 && isMouseOverSlider(mouseX, mouseY)) {
            draggingOpacity = true;
            updateOpacityFromMouse(mouseX);
            return true;
        }
        return false;
    }

    public void mouseClickMove(int mouseX, int mouseY) {
        picker.mouseClickMove(mouseX, mouseY);
        if (draggingOpacity) {
            updateOpacityFromMouse(mouseX);
        }
    }

    public void mouseReleased(int mouseX, int mouseY) {
        draggingOpacity = false;
        picker.mouseReleased(mouseX, mouseY);
    }

    private void updateOpacityFromMouse(int mouseX) {
        float normalized;
        if (sliderStyle == HudSliderStyle.VANILLA_KNOB) {
            normalized = HudSliderStyle.VANILLA_KNOB.normalizedFromMouseX(mouseX, sliderX(), sliderWidth());
        } else {
            normalized = HudOpacitySliderBoxInsets.normalizedFromMouseXOuter(mouseX, sliderX(), sliderWidth());
        }
        int next = Math.round(normalized * 255.0f);
        if (next != getOpacity.getAsInt()) {
            setOpacity.accept(next);
        }
    }

    private void applyColor(int rgb) {
        int rgb24 = rgb & 0xFFFFFF;
        if (rgb24 != (getColorRgb.getAsInt() & 0xFFFFFF)) {
            setColorRgb.accept(rgb24);
        }
    }

    /** Compose alpha + RGB for {@link net.minecraft.client.gui.Gui#drawRect} backgrounds. */
    public static int composeBackgroundPixel(int alpha, int rgb24) {
        if (alpha <= 0) {
            return 0;
        }
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb24 & 0xFFFFFF);
    }
}
