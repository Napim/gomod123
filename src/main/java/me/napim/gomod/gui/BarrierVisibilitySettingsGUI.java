package me.napim.gomod.gui;

import me.napim.gomod.features.BarrierVisibility;
import me.napim.gomod.features.BarrierVisibilityStyles;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.Collections;

/**
 * Barrier blocks: three-way show mode (off / on everywhere / nearby only with radius slider),
 * texture style, opacity slider, outline hue slider + HSV picker button, keybind, back.
 */
public class BarrierVisibilitySettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int BTN_W = 200;
    private static final int BTN_H = 20;

    private static final int ID_BACK = 0;
    private static final int ID_TOGGLE = 1;
    private static final int ID_KEYBIND = 2;
    private static final int ID_STYLE = 3;
    private static final int ID_HUE_SLIDER = 4;
    private static final int ID_CURSOR_TOGGLE = 5;
    private static final int ID_RANGE_SLIDER = 6;
    private static final int ID_OPACITY_SLIDER = 7;
    private static final int ID_CAGE_BARRIERS = 8;

    private final GuiScreen parent;

    private boolean capturingKeybind;
    private GuiButton toggleButton;
    private BarrierStyleButton styleButton;
    private HsvColorPickerWidget outlineColorPicker;
    private HueColorSlider hueSlider;
    private boolean syncingOutlineColor;
    private BarrierRangeSlider rangeSlider;
    private BarrierOpacitySlider opacitySlider;
    private GuiButton cursorToggleButton;
    private GuiButton cageBarriersButton;
    private GuiButton keybindButton;
    private GuiButton backButton;

    private GuiButton draggingSlider;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public BarrierVisibilitySettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        capturingKeybind = false;
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;

        toggleButton = new GuiButton(ID_TOGGLE, 0, 0, BTN_W, BTN_H, "");
        styleButton = new BarrierStyleButton(ID_STYLE, 0, 0, BTN_W, BTN_H);
        HsvColorPickerWidget.resetIconCache();
        outlineColorPicker = new HsvColorPickerWidget();
        outlineColorPicker.setListener(new HsvColorPickerWidget.Listener() {
            @Override
            public void onColorChanged(int rgb) {
                applyOutlineColorFromPicker(rgb);
            }

            @Override
            public void onColorChangeComplete(int rgb) {
                applyOutlineColorFromPicker(rgb);
                ConfigManager.saveConfig();
            }
        });
        hueSlider = new HueColorSlider(ID_HUE_SLIDER, 0, 0, BTN_W, BTN_H, "Barrier color",
                HueColorSlider.Spectrum.FULL);
        rangeSlider = new BarrierRangeSlider(ID_RANGE_SLIDER, 0, 0, BTN_W, BTN_H,
                ConfigManager.BARRIER_RANGE_MIN, ConfigManager.BARRIER_RANGE_MAX,
                ConfigManager.getBarrierVisibilityRange());
        opacitySlider = new BarrierOpacitySlider(ID_OPACITY_SLIDER, 0, 0, BTN_W, BTN_H,
                ConfigManager.BARRIER_OPACITY_MIN, ConfigManager.BARRIER_OPACITY_MAX,
                ConfigManager.getBarrierVisibilityOpacityPercent());
        cursorToggleButton = new GuiButton(ID_CURSOR_TOGGLE, 0, 0, BTN_W, BTN_H, "");
        cageBarriersButton = new GuiButton(ID_CAGE_BARRIERS, 0, 0, BTN_W, BTN_H, "");
        keybindButton = new GuiButton(ID_KEYBIND, 0, 0, BTN_W, BTN_H, getKeybindText());
        backButton = new GuiButton(ID_BACK, 0, 0, BTN_W, BTN_H, EnumChatFormatting.GRAY + "Back");

        refreshStringsAndStates();
        relayoutButtons(cx, cy);
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return ConfigManager.isBarrierVisibilityEnabled();
            }
        });
    }

    /**
     * Vertical stack anchored to a fixed top Y. Adding/removing conditional rows pushes the
     * rows below them down instead of re-centering the stack — the "Show barrier blocks"
     * button never jumps when the range slider appears or disappears.
     */
    private void relayoutButtons(int cx, int cy) {
        int rowGap = 24;
        boolean enabled = ConfigManager.isBarrierVisibilityEnabled();
        boolean nearby = enabled && ConfigManager.isBarrierVisibilityRangeEnabled();

        // Range slider shows only in Nearby-only mode (it controls the radius).
        rangeSlider.visible = nearby;
        rangeSlider.enabled = nearby;
        // Reveal-by-aiming toggle is only meaningful in Nearby-only — in ON mode every
        // barrier already renders so the reveal does nothing; in Aimed-only the cursor
        // cube IS the entire visibility mechanic and the toggle is implicit; in OFF
        // there's nothing to reveal.
        boolean showCursor = nearby;
        cursorToggleButton.visible = showCursor;
        cursorToggleButton.enabled = showCursor;

        boolean showOpacity = enabled;
        opacitySlider.visible = showOpacity;
        opacitySlider.enabled = showOpacity;

        int y = cy - rowGap * 3;

        toggleButton.xPosition = cx - BTN_W / 2;
        toggleButton.yPosition = y;
        y += rowGap;

        if (nearby) {
            rangeSlider.xPosition = cx - BTN_W / 2;
            rangeSlider.yPosition = y;
            y += rowGap;
        }
        if (showCursor) {
            cursorToggleButton.xPosition = cx - BTN_W / 2;
            cursorToggleButton.yPosition = y;
            y += rowGap;
        }

        cageBarriersButton.xPosition = cx - BTN_W / 2;
        cageBarriersButton.yPosition = y;
        y += rowGap;

        styleButton.xPosition = cx - BTN_W / 2;
        styleButton.yPosition = y;
        y += rowGap;

        if (showOpacity) {
            opacitySlider.xPosition = cx - BTN_W / 2;
            opacitySlider.yPosition = y;
            y += rowGap;
        }

        if (outlineColorPicker.isVisible()) {
            int rowLeft = cx - BTN_W / 2;
            int pickerW = outlineColorPicker.getButtonWidth();
            outlineColorPicker.setPosition(rowLeft, y);
            hueSlider.xPosition = rowLeft + pickerW + 4;
            hueSlider.yPosition = y;
            hueSlider.width = BTN_W - pickerW - 4;
            y += rowGap;
        }

        keybindButton.xPosition = cx - BTN_W / 2;
        keybindButton.yPosition = y;
        y += rowGap + 8;

        backButton.xPosition = cx - BTN_W / 2;
        backButton.yPosition = y;

        buttonList.clear();
        buttonList.add(toggleButton);
        if (nearby) {
            buttonList.add(rangeSlider);
        }
        if (showCursor) {
            buttonList.add(cursorToggleButton);
        }
        buttonList.add(cageBarriersButton);
        buttonList.add(styleButton);
        if (showOpacity) {
            buttonList.add(opacitySlider);
        }
        if (outlineColorPicker.isVisible()) {
            buttonList.add(hueSlider);
        }
        buttonList.add(keybindButton);
        buttonList.add(backButton);
    }

    private void refreshStringsAndStates() {
        toggleButton.displayString = getToggleText();
        // styleButton paints its own text; keep the display string empty so the parent
        // GuiScreen tooltip layer can't accidentally show stale text.
        styleButton.displayString = "";
        keybindButton.displayString = getKeybindText();
        cursorToggleButton.displayString = getCursorText();
        cageBarriersButton.displayString = getCageBarriersText();

        boolean barrierOn = ConfigManager.isBarrierVisibilityEnabled();
        outlineColorPicker.setVisible(barrierOn);
        outlineColorPicker.setEnabled(barrierOn);
        hueSlider.visible = barrierOn;
        hueSlider.enabled = barrierOn;
        if (barrierOn) {
            int rgb = ConfigManager.getBarrierOutlineColorArgb() & 0x00FFFFFF;
            hueSlider.syncFromRgb(rgb);
            outlineColorPicker.syncFromRgb(rgb);
        }

        boolean rangeUi = barrierOn
                && ConfigManager.isBarrierVisibilityRangeEnabled();
        rangeSlider.enabled = rangeUi;
        rangeSlider.setValue(ConfigManager.getBarrierVisibilityRange());
        rangeSlider.refreshLabel();

        opacitySlider.visible = barrierOn;
        opacitySlider.enabled = barrierOn;
        if (barrierOn) {
            opacitySlider.setValue(ConfigManager.getBarrierVisibilityOpacityPercent());
            opacitySlider.refreshLabel();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Barrier blocks visibility"
                        + EnumChatFormatting.RESET,
                sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        if (capturingKeybind) {
            keybindButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, BarrierVisibilitySettingsGUI.class);

        if (outlineColorPicker.isVisible()) {
            outlineColorPicker.drawButton(mc, mouseX, mouseY);
            outlineColorPicker.drawExpanded(mc, mouseX, mouseY, partialTicks);
            if (outlineColorPicker.isOpenOrAnimating()) {
                outlineColorPicker.drawButton(mc, mouseX, mouseY);
            }
        }

        if (cursorToggleButton.visible
                && mouseX >= cursorToggleButton.xPosition
                && mouseY >= cursorToggleButton.yPosition
                && mouseX < cursorToggleButton.xPosition + cursorToggleButton.width
                && mouseY < cursorToggleButton.yPosition + cursorToggleButton.height) {
            drawHoveringText(Collections.singletonList(
                    EnumChatFormatting.GRAY + "Aiming at the barrier blocks will make them appear"
            ), mouseX, mouseY);
            return;
        }
        if (cageBarriersButton != null
                && mouseX >= cageBarriersButton.xPosition
                && mouseY >= cageBarriersButton.yPosition
                && mouseX < cageBarriersButton.xPosition + cageBarriersButton.width
                && mouseY < cageBarriersButton.yPosition + cageBarriersButton.height) {
            drawHoveringText(Collections.singletonList(
                    EnumChatFormatting.GRAY + "Toggle barrier blocks visibility in cages"
            ), mouseX, mouseY);
        }
    }

    @Override
    public void actionPerformed(GuiButton button) throws IOException {
        if (capturingKeybind) {
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        switch (button.id) {
            case ID_BACK:
                // Flush any pending debounced save so the radius / cursor toggle / mode all
                // persist across GUI navigations even if the user closes Minecraft in the
                // next ~2 seconds.
                me.napim.gomod.features.ConfigManager.saveConfigNow();
                if (parent instanceof MiscellaneousGUI) {
                    ((MiscellaneousGUI) parent).refreshBarrierVisibilityEntryButton();
                }
                mc.displayGuiScreen(parent);
                break;
            case ID_TOGGLE:
                cycleBarrierShowMode();
                break;
            case ID_STYLE:
                BarrierVisibility.toggleStyleWithRendererReload();
                refreshStringsAndStates();
                ScaledResolution sr2 = new ScaledResolution(mc);
                relayoutButtons(sr2.getScaledWidth() / 2, sr2.getScaledHeight() / 2);
                break;
            case ID_KEYBIND:
                capturingKeybind = true;
                break;
            case ID_CURSOR_TOGGLE:
                BarrierVisibility.setShowAtCursorWithRendererReload(
                        !ConfigManager.isBarrierVisibilityShowAtCursor());
                refreshStringsAndStates();
                break;
            case ID_CAGE_BARRIERS:
                BarrierVisibility.setBarrierBlocksShownInCagesWithRendererReload(
                        !ConfigManager.isBarrierBlocksShownInCages());
                refreshStringsAndStates();
                break;
            default:
                break;
        }
    }

    /**
     * Cycles: OFF → ON (everywhere) → Nearby only → Aimed only → OFF.
     * <ul>
     *     <li><b>ON</b>: every barrier renders, no range limit.</li>
     *     <li><b>Nearby only</b>: barriers render only within the configured radius
     *         (radius slider becomes visible).</li>
     *     <li><b>Aimed only</b>: barriers render only inside the 3×3×3 cube around the
     *         block the player is currently aiming at — cursor reveal is the whole
     *         visibility mechanic, so the Reveal-by-aiming toggle is implicit and hidden.</li>
     * </ul>
     */
    private void cycleBarrierShowMode() {
        boolean wasOn = ConfigManager.isBarrierVisibilityEnabled();
        boolean near = ConfigManager.isBarrierVisibilityRangeEnabled();
        boolean aimed = ConfigManager.isBarrierVisibilityAimedOnly();
        if (!wasOn) {
            // OFF → ON
            BarrierVisibility.setEnabledWithRendererReload(true);
            BarrierVisibility.setRangeEnabledWithRendererReload(false);
            BarrierVisibility.setAimedOnlyWithRendererReload(false);
        } else if (wasOn && !near && !aimed) {
            // ON → Nearby only
            BarrierVisibility.setRangeEnabledWithRendererReload(true);
            BarrierVisibility.setAimedOnlyWithRendererReload(false);
        } else if (near && !aimed) {
            // Nearby only → Aimed only
            BarrierVisibility.setRangeEnabledWithRendererReload(false);
            BarrierVisibility.setAimedOnlyWithRendererReload(true);
        } else {
            // Aimed only → OFF
            BarrierVisibility.setEnabledWithRendererReload(false);
            BarrierVisibility.setRangeEnabledWithRendererReload(false);
            BarrierVisibility.setAimedOnlyWithRendererReload(false);
        }
        ScaledResolution sr = new ScaledResolution(mc);
        refreshStringsAndStates();
        relayoutButtons(sr.getScaledWidth() / 2, sr.getScaledHeight() / 2);
        boolean nowOn = ConfigManager.isBarrierVisibilityEnabled();
        if (nowOn != wasOn) {
            enabledOverlays.onToggled("main", nowOn);
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (capturingKeybind) {
            int code = -(mouseButton + 1);
            ConfigManager.setBarrierVisibilityKeyCode(code);
            GomodKeybinds.applyBarrierVisibilityKeyCode(code);
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        if (rangeSlider != null && rangeSlider.visible && rangeSlider.enabled
                && rangeSlider.mousePressed(mc, mouseX, mouseY)) {
            draggingSlider = rangeSlider;
            applyRangeFromSlider();
            return;
        }
        if (opacitySlider != null && opacitySlider.visible && opacitySlider.enabled
                && opacitySlider.mousePressed(mc, mouseX, mouseY)) {
            draggingSlider = opacitySlider;
            applyOpacityFromSlider();
            return;
        }
        if (hueSlider != null && hueSlider.visible && hueSlider.enabled
                && hueSlider.mousePressed(mc, mouseX, mouseY)) {
            draggingSlider = hueSlider;
            applyHueFromSlider();
            return;
        }
        if (outlineColorPicker != null && outlineColorPicker.isVisible()
                && outlineColorPicker.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingSlider == rangeSlider) {
            rangeSlider.dragTo(mouseX);
            applyRangeFromSlider();
        } else if (draggingSlider == opacitySlider) {
            opacitySlider.dragTo(mouseX);
            applyOpacityFromSlider();
        } else if (draggingSlider == hueSlider) {
            hueSlider.dragTo(mouseX);
            applyHueFromSlider();
        }
        if (outlineColorPicker != null && outlineColorPicker.isVisible()) {
            outlineColorPicker.mouseClickMove(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (draggingSlider != null) {
            draggingSlider.mouseReleased(mouseX, mouseY);
            if (draggingSlider == rangeSlider) {
                applyRangeFromSlider();
            } else if (draggingSlider == opacitySlider) {
                applyOpacityFromSlider();
            } else if (draggingSlider == hueSlider) {
                applyHueFromSlider();
                ConfigManager.saveConfig();
            }
            draggingSlider = null;
        }
        if (outlineColorPicker != null && outlineColorPicker.isVisible()) {
            outlineColorPicker.mouseReleased(mouseX, mouseY);
        }
    }

    private void applyRangeFromSlider() {
        if (rangeSlider == null || !rangeSlider.visible) {
            return;
        }
        int v = rangeSlider.getValue();
        if (v != ConfigManager.getBarrierVisibilityRange()) {
            BarrierVisibility.setRangeWithRendererReload(v);
        }
    }

    private void applyOutlineColorFromPicker(int rgb) {
        if (syncingOutlineColor) {
            return;
        }
        syncingOutlineColor = true;
        int argb = 0xFF000000 | (rgb & 0x00FFFFFF);
        BarrierVisibility.setOutlineColorWithRendererReload(argb);
        hueSlider.syncFromRgb(rgb & 0x00FFFFFF);
        syncingOutlineColor = false;
    }

    private void applyHueFromSlider() {
        if (hueSlider == null || !hueSlider.visible || syncingOutlineColor) {
            return;
        }
        syncingOutlineColor = true;
        int rgb = hueSlider.getRgb();
        BarrierVisibility.setOutlineColorWithRendererReload(0xFF000000 | rgb);
        outlineColorPicker.syncFromRgb(rgb);
        syncingOutlineColor = false;
    }

    private void applyOpacityFromSlider() {
        if (opacitySlider == null || !opacitySlider.visible) {
            return;
        }
        int v = opacitySlider.getValue();
        // Always re-upload: config can match the slider while the atlas was reset (resource reload)
        // or a previous apply failed, which made opacity look stuck at 100%.
        BarrierVisibility.setOpacityPercentWithRendererReload(v);
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (capturingKeybind) {
            int code = (keyCode == 1) ? 0 : keyCode;
            ConfigManager.setBarrierVisibilityKeyCode(code);
            GomodKeybinds.applyBarrierVisibilityKeyCode(code);
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return capturingKeybind;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getToggleText() {
        if (!ConfigManager.isBarrierVisibilityEnabled()) {
            return "Show barrier blocks: " + EnumChatFormatting.RED + "OFF";
        }
        if (ConfigManager.isBarrierVisibilityAimedOnly()) {
            return "Show barrier blocks: " + EnumChatFormatting.GOLD + "Aimed only";
        }
        if (ConfigManager.isBarrierVisibilityRangeEnabled()) {
            return "Show barrier blocks: " + EnumChatFormatting.AQUA + "Nearby only";
        }
        return "Show barrier blocks: " + EnumChatFormatting.GREEN + "ON"
                + EnumChatFormatting.DARK_GRAY + " [STATIC]";
    }

    private String getCursorText() {
        boolean on = ConfigManager.isBarrierVisibilityShowAtCursor();
        return "Reveal by aiming: "
                + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getCageBarriersText() {
        return "Show during cages: "
                + (ConfigManager.isBarrierBlocksShownInCages()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getKeybindText() {
        int code = ConfigManager.getBarrierVisibilityKeyCode();
        if (code == 0) {
            return "Key: " + EnumChatFormatting.WHITE + "NONE";
        }
        if (code < 0) {
            int mb = -code - 1;
            return "Key: " + EnumChatFormatting.WHITE + "Mouse " + (mb + 1);
        }
        String keyName = Keyboard.getKeyName(code);
        String label = (keyName == null || keyName.trim().isEmpty()) ? "UNKNOWN" : keyName;
        return "Key: " + EnumChatFormatting.WHITE + label;
    }
}

/**
 * Texture row button — paints "Texture: " in the normal button color, then the style title
 * (Vanilla / Edge outline) in the configured barrier color so the selected color is visible
 * for either texture style.
 */
class BarrierStyleButton extends GuiButton {

    BarrierStyleButton(int id, int x, int y, int w, int h) {
        super(id, x, y, w, h, "");
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        // Vanilla draws the frame fine; suppress its text by passing an empty string.
        String saved = this.displayString;
        this.displayString = "";
        super.drawButton(mc, mouseX, mouseY);
        this.displayString = saved;

        String id = ConfigManager.getBarrierVisibilityStyle();
        String title = BarrierVisibilityStyles.getTitle(id);
        boolean outline = !BarrierVisibilityStyles.isFull(id);
        String prefix = "Texture: ";

        boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int prefixColor = !this.enabled ? 0xA0A0A0 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
        // Use the picked color verbatim for both styles so Vanilla clearly previews the
        // selected barrier tint too.
        int titleColor = ConfigManager.getBarrierOutlineColorArgb() & 0x00FFFFFF;
        if (!this.enabled) {
            // Match the muted look of disabled vanilla buttons.
            titleColor = mix(titleColor, 0x808080, 0.55f);
        }

        int prefixWidth = mc.fontRendererObj.getStringWidth(prefix);
        int titleWidth = mc.fontRendererObj.getStringWidth(title);

        // For the Vanilla (FULL) style, slot a small barrier-block icon AFTER the
        // "Vanilla" word as a quick visual hint of the picked style. The atlas already
        // carries the X texture in this mode (procedural outline only overrides it when
        // OUTLINE is active), so the item renderer paints the expected red-X glyph here
        // for free.
        boolean showIcon = !outline;
        int iconSize = 16;
        int iconGap = 3;
        int total = prefixWidth + titleWidth + (showIcon ? iconGap + iconSize : 0);
        int tx = this.xPosition + (this.width - total) / 2;
        int ty = this.yPosition + (this.height - 8) / 2;

        int cursor = tx + prefixWidth;
        if (!outline) {
            mc.fontRendererObj.drawStringWithShadow(prefix, tx, ty, prefixColor);
            mc.fontRendererObj.drawStringWithShadow(title, cursor, ty, titleColor);
        } else {
            mc.fontRendererObj.drawString(prefix, tx, ty, prefixColor);
            mc.fontRendererObj.drawString(title, cursor, ty, titleColor);
        }
        cursor += titleWidth;
        if (showIcon) {
            cursor += iconGap;
            int iconY = this.yPosition + (this.height - iconSize) / 2;
            try {
                RenderHelper.enableGUIStandardItemLighting();
                mc.getRenderItem().renderItemIntoGUI(new ItemStack(Blocks.barrier), cursor, iconY);
                RenderHelper.disableStandardItemLighting();
            } catch (Throwable ignored) {
            }
            // Item renderer can leak GL state — restore what the text drawer expects.
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }

    private static int mix(int rgbA, int rgbB, float t) {
        float a = 1f - t;
        int rA = (rgbA >> 16) & 0xFF, gA = (rgbA >> 8) & 0xFF, bA = rgbA & 0xFF;
        int rB = (rgbB >> 16) & 0xFF, gB = (rgbB >> 8) & 0xFF, bB = rgbB & 0xFF;
        int r = Math.min(255, Math.round(rA * a + rB * t));
        int g = Math.min(255, Math.round(gA * a + gB * t));
        int b = Math.min(255, Math.round(bA * a + bB * t));
        return (r << 16) | (g << 8) | b;
    }
}

/**
 * Integer radius slider; when disabled, label and value use a single muted gray (no § codes).
 */
class BarrierRangeSlider extends GuiButton {
    private final int minValue;
    private final int maxValue;
    private float sliderPos;
    private int value;
    private boolean dragging;

    BarrierRangeSlider(int id, int x, int y, int w, int h, int min, int max, int current) {
        super(id, x, y, w, h, "");
        this.minValue = min;
        this.maxValue = max;
        this.value = clamp(current);
        this.sliderPos = (maxValue == minValue) ? 0f : (float) (value - minValue) / (maxValue - minValue);
        refreshLabel();
    }

    int getValue() {
        return value;
    }

    void setValue(int v) {
        this.value = clamp(v);
        this.sliderPos = (maxValue == minValue) ? 0f : (float) (value - minValue) / (maxValue - minValue);
        refreshLabel();
    }

    void refreshLabel() {
        // Plain text only — drawString color applies to the whole line (no embedded §WHITE).
        this.displayString = "Visibility radius: " + value + " blocks";
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
        GlStateManager.color(1f, 1f, 1f, this.enabled ? 1f : 0.42f);
        int half = this.width / 2;
        this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, 46, half, 20);
        this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, 46, half, 20);
        int knobX = this.xPosition + (int) (this.sliderPos * (this.width - 8));
        this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
        this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
        GlStateManager.color(1f, 1f, 1f, 1f);

        boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int textColor = !this.enabled ? 0x808080 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
        int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
        int textX = this.xPosition + (this.width - textWidth) / 2;
        int textY = this.yPosition + (this.height - 8) / 2;
        mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible || !this.enabled) {
            return false;
        }
        if (mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height) {
            this.dragging = true;
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    void dragTo(int mouseX) {
        if (!this.enabled) {
            return;
        }
        if (this.dragging) {
            updateFromMouse(mouseX);
        }
    }

    private void updateFromMouse(int mouseX) {
        float t = (mouseX - (this.xPosition + 4)) / (float) (this.width - 8);
        if (t < 0f) {
            t = 0f;
        }
        if (t > 1f) {
            t = 1f;
        }
        this.sliderPos = t;
        int newValue = Math.round(minValue + t * (maxValue - minValue));
        this.value = clamp(newValue);
        refreshLabel();
    }

    private int clamp(int v) {
        if (v < minValue) {
            return minValue;
        }
        if (v > maxValue) {
            return maxValue;
        }
        return v;
    }
}

/** Integer opacity slider (10–100%) for barrier texture alpha when visibility is on. */
class BarrierOpacitySlider extends BarrierRangeSlider {

    BarrierOpacitySlider(int id, int x, int y, int w, int h, int min, int max, int current) {
        super(id, x, y, w, h, min, max, current);
    }

    @Override
    void refreshLabel() {
        this.displayString = "Barrier opacity: " + getValue() + "%";
    }
}
