package me.napim.gomod.gui;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Barrier-style color row: {@link HsvColorPickerWidget} button + {@link HueColorSlider} (FULL spectrum).
 */
public final class HueColorPickerRow {

    private static final int PICKER_GAP = 4;
    private static final int ROW_HEIGHT = 20;
    /** Picker + hue slider only — same as {@link BarrierVisibilitySettingsGUI} {@code BTN_W}. */
    public static final int BARRIER_PICKER_SLIDER_ROW_W = 200;
    public static final int DEFAULT_RESET_BTN_W = 42;
    public static final int DEFAULT_RESET_GAP = 4;
    /** Full HUD color row: barrier-sized picker + slider, plus reset on the right. */
    public static final int HUD_COLOR_ROW_W =
            BARRIER_PICKER_SLIDER_ROW_W + DEFAULT_RESET_GAP + DEFAULT_RESET_BTN_W;

    public interface ColorConsumer {
        void accept(int rgb);
    }

    private final HsvColorPickerWidget picker = new HsvColorPickerWidget();
    private final HueColorSlider slider;
    private final ColorConsumer onApply;
    private Runnable onPersist;
    private boolean syncing;

    public HueColorPickerRow(int sliderId, String label, ColorConsumer onApply) {
        this.slider = new HueColorSlider(sliderId, 0, 0, 0, ROW_HEIGHT, label, HueColorSlider.Spectrum.FULL);
        this.onApply = onApply;
        picker.setListener(new HsvColorPickerWidget.Listener() {
            @Override
            public void onColorChanged(int rgb) {
                applyFromPicker(rgb);
            }

            @Override
            public void onColorChangeComplete(int rgb) {
                applyFromPicker(rgb);
                persist();
            }
        });
    }

    public void setOnPersist(Runnable onPersist) {
        this.onPersist = onPersist;
    }

    /** Warms the shared picker icon once per screen open (does not invalidate an existing cache). */
    public static void prepareIconCache() {
        HsvColorPickerWidget.warmIconCache(Minecraft.getMinecraft());
    }

    /** Vertical HUD color stacks: open panel below the swatch so lower rows are not covered sideways. */
    public static void configureStackedHudRows(HueColorPickerRow[] rows) {
        if (rows == null) {
            return;
        }
        for (HueColorPickerRow row : rows) {
            if (row != null) {
                row.picker.setExpandBelow(true);
            }
        }
    }

    /**
     * Draws picker swatches and expanded panels after {@link net.minecraft.client.gui.GuiScreen#drawScreen}
     * so sliders and reset buttons are not painted over the HSV UI.
     */
    public static void drawAllPickerOverlays(Minecraft mc, int mouseX, int mouseY, float partialTicks,
            HueColorPickerRow[] rows) {
        if (rows == null || rows.length == 0) {
            return;
        }
        HueColorPickerRow openRow = null;
        for (HueColorPickerRow row : rows) {
            if (row != null && row.picker.isOpenOrAnimating()) {
                openRow = row;
                break;
            }
        }
        if (openRow != null) {
            for (HueColorPickerRow row : rows) {
                if (row != null && row != openRow) {
                    row.drawPickerButtonOnly(mc, mouseX, mouseY);
                }
            }
            openRow.drawPickerExpandedOnly(mc, mouseX, mouseY, partialTicks);
            openRow.drawPickerButtonOnly(mc, mouseX, mouseY);
        } else {
            for (HueColorPickerRow row : rows) {
                if (row != null) {
                    row.drawPickerButtonOnly(mc, mouseX, mouseY);
                }
            }
        }
    }

    public HueColorSlider getSlider() {
        return slider;
    }

    public HsvColorPickerWidget getPicker() {
        return picker;
    }

    public void syncFromRgb(int rgb) {
        int c = rgb & 0x00FFFFFF;
        syncing = true;
        picker.syncFromRgb(c);
        slider.syncFromRgb(c);
        syncing = false;
    }

    /** Updates config via the row callback and refreshes picker + slider. */
    public void setRgb(int rgb) {
        int c = rgb & 0x00FFFFFF;
        onApply.accept(c);
        syncFromRgb(c);
    }

    public void layout(int rowLeft, int y, int rowWidth, int resetBtnWidth, int resetGap) {
        int pickerW = picker.getButtonWidth();
        picker.setPosition(rowLeft, y);
        slider.xPosition = rowLeft + pickerW + PICKER_GAP;
        slider.yPosition = y;
        slider.width = rowWidth - pickerW - PICKER_GAP - resetBtnWidth - resetGap;
        slider.height = ROW_HEIGHT;
    }

    /** Picker + hue slider sized like barrier visibility; reset column sits to the right. */
    public void layoutHudColorRow(int rowLeft, int y) {
        int pickerW = picker.getButtonWidth();
        picker.setPosition(rowLeft, y);
        slider.xPosition = rowLeft + pickerW + PICKER_GAP;
        slider.yPosition = y;
        slider.width = BARRIER_PICKER_SLIDER_ROW_W - pickerW - PICKER_GAP;
        slider.height = ROW_HEIGHT;
        slider.visible = true;
        slider.enabled = true;
    }

    /**
     * Picker swatch only — hides the hue slider so a custom control (e.g. fill opacity bar) can
     * occupy the rest of {@code rowWidth} on the same row.
     *
     * @return left X of the companion region; width is {@code rowWidth - (return - rowLeft)}
     */
    public int layoutPickerOnly(int rowLeft, int y, int rowWidth) {
        int pickerW = picker.getButtonWidth();
        picker.setPosition(rowLeft, y);
        slider.visible = false;
        slider.enabled = false;
        return rowLeft + pickerW + PICKER_GAP;
    }

    public static int companionWidth(int rowLeft, int rowWidth, int companionLeft) {
        return Math.max(8, rowWidth - (companionLeft - rowLeft));
    }

    public static int resetButtonX(int rowLeft, int rowWidth, int resetBtnWidth) {
        return rowLeft + rowWidth - resetBtnWidth;
    }

    public void addSliderTo(java.util.List<GuiButton> buttonList) {
        buttonList.add(slider);
    }

    public void drawPickerButtonOnly(Minecraft mc, int mouseX, int mouseY) {
        if (!picker.isVisible()) {
            return;
        }
        withPickerOverlayGl(() -> picker.drawButton(mc, mouseX, mouseY));
    }

    public void drawPickerExpandedOnly(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!picker.isVisible()) {
            return;
        }
        withPickerOverlayGl(() -> picker.drawExpanded(mc, mouseX, mouseY, partialTicks));
    }

    /** Sliders are drawn by {@link GuiScreen#drawScreen}; call this after buttons so the HSV panel stays on top. */
    public void drawPickerOverlay(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!picker.isVisible()) {
            return;
        }
        withPickerOverlayGl(() -> {
            picker.drawButton(mc, mouseX, mouseY);
            picker.drawExpanded(mc, mouseX, mouseY, partialTicks);
            if (picker.isOpenOrAnimating()) {
                picker.drawButton(mc, mouseX, mouseY);
            }
        });
    }

    private static void withPickerOverlayGl(Runnable draw) {
        GlStateManager.pushMatrix();
        try {
            HudGlState.restoreForOverlayText();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            draw.run();
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    public void draw(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        drawPickerOverlay(mc, mouseX, mouseY, partialTicks);
    }

    public void collapse() {
        picker.collapse();
    }

    public static void collapseAllExcept(HueColorPickerRow[] rows, HueColorPickerRow keepOpen) {
        if (rows == null) {
            return;
        }
        for (HueColorPickerRow row : rows) {
            if (row != null && row != keepOpen) {
                row.collapse();
            }
        }
    }

    /** @return true if the picker consumed the click */
    public boolean tryPickerClick(Minecraft mc, int mouseX, int mouseY, int mouseButton,
            HueColorPickerRow[] allRows) {
        if (!picker.isVisible()) {
            return false;
        }
        if (picker.isOpenOrAnimating() && picker.isPointerOverOpenPanel(mouseX, mouseY)) {
            picker.mouseClicked(mc, mouseX, mouseY, mouseButton);
            collapseAllExcept(allRows, this);
            return true;
        }
        if (!picker.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
            return false;
        }
        collapseAllExcept(allRows, this);
        return true;
    }

    /**
     * When a picker panel is open, handle its clicks before sliders / {@link GuiScreen} buttons underneath.
     *
     * @return true if an open panel consumed the click
     */
    public static boolean tryConsumeOpenPickerClicksFirst(Minecraft mc, int mouseX, int mouseY, int mouseButton,
            HueColorPickerRow[] rows) {
        if (rows == null) {
            return false;
        }
        for (HueColorPickerRow row : rows) {
            if (row != null && row.tryPickerClick(mc, mouseX, mouseY, mouseButton, rows)) {
                return true;
            }
        }
        return false;
    }

    public boolean trySliderPress(Minecraft mc, int mouseX, int mouseY) {
        if (slider.visible && slider.enabled && slider.mousePressed(mc, mouseX, mouseY)) {
            applyFromSlider();
            return true;
        }
        return false;
    }

    public void dragSlider(int mouseX) {
        slider.dragTo(mouseX);
        applyFromSlider();
    }

    public void releaseSlider(int mouseX, int mouseY) {
        slider.mouseReleased(mouseX, mouseY);
    }

    public void pickerMouseMove(int mouseX, int mouseY) {
        if (picker.isVisible()) {
            picker.mouseClickMove(mouseX, mouseY);
        }
    }

    public void pickerMouseRelease(int mouseX, int mouseY) {
        if (picker.isVisible()) {
            picker.mouseReleased(mouseX, mouseY);
        }
    }

    private void applyFromPicker(int rgb) {
        if (syncing) {
            return;
        }
        syncing = true;
        int c = rgb & 0x00FFFFFF;
        onApply.accept(c);
        slider.syncFromRgb(c);
        syncing = false;
    }

    private void applyFromSlider() {
        if (syncing) {
            return;
        }
        syncing = true;
        int rgb = slider.getRgb();
        onApply.accept(rgb);
        picker.syncFromRgb(rgb);
        syncing = false;
    }

    private void persist() {
        if (onPersist != null) {
            onPersist.run();
        }
    }
}
