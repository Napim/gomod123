package me.napim.gomod.gui;

import me.napim.gomod.features.LightningSettings;
import me.napim.gomod.features.LightningSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Client lightning — shorten bolts, thunder sound speed/volume, and bolt color tint.
 */
public class LightningSettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;
    private static final int ROW = 24;
    private static final int GAP = 8;
    private static final int MIN_BLOCKS = 1;
    private static final int MAX_BLOCKS = 64;
    private static final float MIN_SPEED = 25.0f;
    private static final float MAX_SPEED = 300.0f;
    private static final float MIN_VOLUME = 0.0f;
    private static final float MAX_VOLUME = 100.0f;
    private static final float MIN_OPACITY = 0.0f;
    private static final float MAX_OPACITY = 100.0f;

    private static final int ID_NERF_TOGGLE = 1;
    private static final int ID_FIX_RENDER = 12;
    private static final int ID_EARLY_GAME = 11;
    private static final int ID_TEST_SOUND = 8;
    private static final int ID_RESET_ALL = 9;
    private static final int ID_COLOR_SLIDER = 6;
    private static final int ID_RESET_COLOR = 7;
    private static final int ID_OPACITY_SLIDER = 13;
    private static final int ID_RANDOM_COLOR = 14;
    private static final int ID_BACK = 99;

    private final GuiScreen parent;
    private GuiButton nerfToggleButton;
    private GuiButton fixRenderButton;
    private GuiButton earlyGameButton;
    private GuiButton testSoundButton;
    private GuiButton backButton;
    private GuiButton resetAllButton;
    private GuiButton resetColorButton;
    private GuiButton randomColorButton;
    private IntValueSlider blocksSlider;
    private FloatValueSlider speedSlider;
    private FloatValueSlider volumeSlider;
    private FloatValueSlider opacitySlider;
    private HueColorPickerRow colorRow;
    private HueColorPickerRow[] colorRows;
    private HueColorPickerRow draggingColorRow;
    private boolean resetAllPending;
    private GuiButton draggingSlider;
    private int contentButtonWidth;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public LightningSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        resetAllPending = false;
        HueColorPickerRow.prepareIconCache();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();

        colorRow = new HueColorPickerRow(ID_COLOR_SLIDER, "Thunder color",
                rgb -> LightningSettings.setBoltColorArgb(0xFF000000 | (rgb & 0xFFFFFF)));
        colorRow.setOnPersist(() -> {});
        colorRow.syncFromRgb(LightningSettings.getBoltColorArgb());

        int pickerW = colorRow.getPicker().getButtonWidth();
        int pickerGap = HueColorPickerRow.DEFAULT_RESET_GAP;
        contentButtonWidth = HueColorPickerRow.BARRIER_PICKER_SLIDER_ROW_W - pickerW - pickerGap;
        int buttonLeft = cx - contentButtonWidth / 2;
        int rowLeft = buttonLeft - pickerW - pickerGap;
        int resetX = buttonLeft + contentButtonWidth + HueColorPickerRow.DEFAULT_RESET_GAP;

        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int resetAllY = backY - BUTTON_HEIGHT - GAP;
        int contentBottom = resetAllY - 12;
        int titleBottom = GuiHeader.STANDARD_TITLE_Y + fontRendererObj.FONT_HEIGHT + 14;
        int rowsBeforeColor = 6;
        int stackH = rowsBeforeColor * (BUTTON_HEIGHT + GAP) - GAP + ROW + 4
                + 3 * (BUTTON_HEIGHT + GAP);
        int y = titleBottom + Math.max(8, (contentBottom - titleBottom - stackH) / 2);

        nerfToggleButton = new GuiButton(ID_NERF_TOGGLE, buttonLeft, y, contentButtonWidth, BUTTON_HEIGHT,
                getNerfToggleText());
        y += BUTTON_HEIGHT + GAP;
        blocksSlider = new IntValueSlider(2, buttonLeft, y, contentButtonWidth, MIN_BLOCKS, MAX_BLOCKS,
                LightningSettings.getBottomBlocks(), "Bottom hidden", " blocks");
        y += BUTTON_HEIGHT + GAP;
        speedSlider = new FloatValueSlider(4, buttonLeft, y, contentButtonWidth, MIN_SPEED, MAX_SPEED,
                LightningSettings.getSpeedPercent(), "Sound speed", "%", 0);
        y += BUTTON_HEIGHT + GAP;
        volumeSlider = new FloatValueSlider(10, buttonLeft, y, contentButtonWidth, MIN_VOLUME, MAX_VOLUME,
                LightningSettings.getVolumePercent(), "Sound volume", "%", 0);
        y += BUTTON_HEIGHT + GAP;
        earlyGameButton = new GuiButton(ID_EARLY_GAME, buttonLeft, y, contentButtonWidth, BUTTON_HEIGHT,
                getEarlyGameButtonText());
        y += BUTTON_HEIGHT + GAP;
        testSoundButton = new GuiButton(ID_TEST_SOUND, buttonLeft, y, contentButtonWidth, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "Test thunder");
        y += BUTTON_HEIGHT + GAP;

        colorRow.layoutHudColorRow(rowLeft, y);
        resetColorButton = new GuiButton(ID_RESET_COLOR, resetX, y, RESET_BTN_W, BUTTON_HEIGHT,
                GuiHeader.HUD_EDITOR_RESET_BUTTON);
        y += BUTTON_HEIGHT + GAP;

        randomColorButton = new GuiButton(ID_RANDOM_COLOR, buttonLeft, y, contentButtonWidth, BUTTON_HEIGHT,
                getRandomColorToggleText());
        y += BUTTON_HEIGHT + GAP;

        opacitySlider = new FloatValueSlider(ID_OPACITY_SLIDER, buttonLeft, y, contentButtonWidth,
                MIN_OPACITY, MAX_OPACITY, LightningSettings.getOpacityPercent(), "Thunder opacity", "%", 0);
        y += BUTTON_HEIGHT + GAP;

        fixRenderButton = new GuiButton(ID_FIX_RENDER, buttonLeft, y, contentButtonWidth, BUTTON_HEIGHT,
                getFixRenderToggleText());

        resetAllButton = new GuiButton(ID_RESET_ALL, buttonLeft, resetAllY, contentButtonWidth, BUTTON_HEIGHT,
                getResetAllButtonText());
        backButton = new GuiButton(ID_BACK, buttonLeft, backY, contentButtonWidth, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");

        colorRows = new HueColorPickerRow[] { colorRow };
        HueColorPickerRow.configureStackedHudRows(colorRows);

        buttonList.clear();
        buttonList.add(nerfToggleButton);
        buttonList.add(blocksSlider);
        buttonList.add(speedSlider);
        buttonList.add(volumeSlider);
        buttonList.add(earlyGameButton);
        buttonList.add(testSoundButton);
        colorRow.addSliderTo(buttonList);
        buttonList.add(resetColorButton);
        buttonList.add(randomColorButton);
        buttonList.add(opacitySlider);
        buttonList.add(fixRenderButton);
        buttonList.add(resetAllButton);
        buttonList.add(backButton);

        enabledOverlays.trackModuleToggle("nerf", nerfToggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return LightningSettings.isNerfEnabled();
            }
        });
        enabledOverlays.trackModuleToggle("fixRender", fixRenderButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return LightningSettings.isFixRenderEnabled();
            }
        });

        refreshDependentButtonState();
    }

    private void refreshDependentButtonState() {
        boolean nerfOn = LightningSettings.isNerfEnabled();
        boolean randomColorOn = LightningSettings.isRandomColorEnabled();
        blocksSlider.visible = true;
        blocksSlider.enabled = nerfOn;
        colorRow.getSlider().enabled = !randomColorOn;
        colorRow.getPicker().setEnabled(!randomColorOn);
        resetColorButton.enabled = !randomColorOn;
        randomColorButton.displayString = getRandomColorToggleText();
    }

    private String getEarlyGameButtonText() {
        return EnumChatFormatting.GOLD + "Early game thunder: "
                + (LightningSettings.isPostCageSoundEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    public void refreshEarlyGameButtonLabel() {
        if (earlyGameButton != null) {
            earlyGameButton.displayString = getEarlyGameButtonText();
        }
    }

    private String getFixRenderToggleText() {
        return EnumChatFormatting.GOLD + "Fix thunder renders: "
                + (LightningSettings.isFixRenderEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getNerfToggleText() {
        return EnumChatFormatting.GOLD + "Shorten thunder: "
                + (LightningSettings.isNerfEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getRandomColorToggleText() {
        return EnumChatFormatting.GOLD + "Random color: "
                + (LightningSettings.isRandomColorEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getResetAllButtonText() {
        return resetAllPending
                ? EnumChatFormatting.DARK_RED + "CONFIRM RESET"
                : EnumChatFormatting.GRAY + "Reset all";
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Thunder Settings";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        HueColorPickerRow.drawAllPickerOverlays(mc, mouseX, mouseY, partialTicks, colorRows);
        enabledOverlays.drawAll();
        drawHoverTooltip(mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, LightningSettingsGUI.class);
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (LightningSettings.isRandomColorEnabled()
                && (colorRow.getPicker().isOverButton(mouseX, mouseY)
                || isMouseOver(colorRow.getSlider(), mouseX, mouseY)
                || isMouseOver(resetColorButton, mouseX, mouseY))) {
            drawTooltip(mouseX, mouseY, EnumChatFormatting.GRAY + "Disable Random color first");
        } else if (isMouseOver(randomColorButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Random color",
                    EnumChatFormatting.GRAY + "Choose a new color for each thunder strike.");
        } else if (isMouseOver(fixRenderButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Fix thunder renders",
                    EnumChatFormatting.GRAY + "Fixes a vanilla bug where players and other",
                    EnumChatFormatting.GRAY + "entities vanish when thunder is in front of them.",
                    EnumChatFormatting.GRAY + "Draws bolts after entities so you still see",
                    EnumChatFormatting.GRAY + "players through the flash.");
        } else if (isMouseOver(opacitySlider, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Thunder opacity",
                    EnumChatFormatting.GRAY + "Scales the bolt alpha from 0% to 100%.",
                    EnumChatFormatting.GRAY + "Fix thunder renders does not lower it.");
        } else if (isMouseOver(earlyGameButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Early game thunder",
                    EnumChatFormatting.GRAY + "Use different thunder sound settings for a",
                    EnumChatFormatting.GRAY + "short time after cages open");
        }
    }

    private void drawTooltip(int mouseX, int mouseY, String... lines) {
        List<String> tooltip = Arrays.asList(lines);
        drawHoveringText(tooltip, mouseX, Math.max(0, mouseY - 12));
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + button.height;
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == ID_BACK) {
            mc.displayGuiScreen(parent);
            if (parent instanceof MiscellaneousGUI) {
                ((MiscellaneousGUI) parent).refreshLightningSettingsButtonLabel();
            }
            return;
        }
        if (button.id == ID_NERF_TOGGLE) {
            boolean now = !LightningSettings.isNerfEnabled();
            LightningSettings.setNerfEnabled(now);
            nerfToggleButton.displayString = getNerfToggleText();
            enabledOverlays.onToggled("nerf", now);
            refreshDependentButtonState();
            draggingSlider = null;
            return;
        }
        if (button.id == ID_FIX_RENDER) {
            boolean now = !LightningSettings.isFixRenderEnabled();
            LightningSettings.setFixRenderEnabled(now);
            fixRenderButton.displayString = getFixRenderToggleText();
            enabledOverlays.onToggled("fixRender", now);
            return;
        }
        if (button.id == ID_RANDOM_COLOR) {
            boolean now = !LightningSettings.isRandomColorEnabled();
            LightningSettings.setRandomColorEnabled(now);
            randomColorButton.displayString = getRandomColorToggleText();
            refreshDependentButtonState();
            draggingColorRow = null;
            draggingSlider = null;
            return;
        }
        if (button.id == ID_EARLY_GAME) {
            mc.displayGuiScreen(new EarlyGameThunderSettingsGUI(this));
            return;
        }
        if (button.id == ID_RESET_ALL) {
            if (!resetAllPending) {
                resetAllPending = true;
                resetAllButton.displayString = getResetAllButtonText();
                return;
            }
            resetAllPending = false;
            resetAllButton.displayString = getResetAllButtonText();
            LightningSettings.resetAllToDefaults();
            refreshAllControls();
            enabledOverlays.onToggled("nerf", LightningSettings.isNerfEnabled());
            enabledOverlays.onToggled("fixRender", LightningSettings.isFixRenderEnabled());
            return;
        }
        if (button.id == ID_RESET_COLOR) {
            LightningSettings.setBoltColorArgb(LightningSettings.DEFAULT_BOLT_COLOR_ARGB);
            LightningSettings.setCustomColorEnabled(LightningSettings.DEFAULT_CUSTOM_COLOR_ENABLED);
            colorRow.syncFromRgb(LightningSettings.DEFAULT_BOLT_COLOR_ARGB);
            return;
        }
        if (button.id == ID_TEST_SOUND) {
            LightningSound.playPreview(mc);
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        draggingColorRow = null;
        if (HueColorPickerRow.tryConsumeOpenPickerClicksFirst(mc, mouseX, mouseY, mouseButton, colorRows)) {
            return;
        }
        for (HueColorPickerRow row : colorRows) {
            if (row.tryPickerClick(mc, mouseX, mouseY, mouseButton, colorRows)) {
                return;
            }
        }
        for (HueColorPickerRow row : colorRows) {
            if (row.trySliderPress(mc, mouseX, mouseY)) {
                draggingColorRow = row;
                return;
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (!LightningSettings.isNerfEnabled()) {
            draggingSlider = null;
        }
        if (tryPressSlider(blocksSlider, mouseX, mouseY)) {
            if (LightningSettings.isNerfEnabled()) {
                LightningSettings.setBottomBlocks(blocksSlider.getIntValue());
            }
        } else if (tryPressSlider(speedSlider, mouseX, mouseY)) {
            LightningSettings.setSpeedPercent(speedSlider.getFloatValue());
        } else if (tryPressSlider(volumeSlider, mouseX, mouseY)) {
            LightningSettings.setVolumePercent(volumeSlider.getFloatValue());
        } else if (tryPressSlider(opacitySlider, mouseX, mouseY)) {
            LightningSettings.setOpacityPercent(opacitySlider.getFloatValue());
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingColorRow != null) {
            draggingColorRow.dragSlider(mouseX);
        }
        for (HueColorPickerRow row : colorRows) {
            row.pickerMouseMove(mouseX, mouseY);
        }
        if (draggingSlider instanceof IntValueSlider) {
            IntValueSlider slider = (IntValueSlider) draggingSlider;
            if (slider.enabled) {
                slider.dragTo(mouseX);
                if (slider == blocksSlider && LightningSettings.isNerfEnabled()) {
                    LightningSettings.setBottomBlocks(slider.getIntValue());
                }
            }
        } else if (draggingSlider instanceof FloatValueSlider) {
            FloatValueSlider slider = (FloatValueSlider) draggingSlider;
            slider.dragTo(mouseX);
            if (slider == speedSlider) {
                LightningSettings.setSpeedPercent(slider.getFloatValue());
            } else if (slider == volumeSlider) {
                LightningSettings.setVolumePercent(slider.getFloatValue());
            } else if (slider == opacitySlider) {
                LightningSettings.setOpacityPercent(slider.getFloatValue());
            }
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (draggingColorRow != null) {
            draggingColorRow.releaseSlider(mouseX, mouseY);
            draggingColorRow = null;
        }
        for (HueColorPickerRow row : colorRows) {
            row.pickerMouseRelease(mouseX, mouseY);
        }
        if (state == 0 && draggingSlider instanceof IntValueSlider) {
            IntValueSlider slider = (IntValueSlider) draggingSlider;
            if (slider.enabled && slider == blocksSlider && LightningSettings.isNerfEnabled()) {
                LightningSettings.setBottomBlocks(slider.getIntValue());
            }
        } else if (state == 0 && draggingSlider instanceof FloatValueSlider) {
            FloatValueSlider slider = (FloatValueSlider) draggingSlider;
            if (slider == speedSlider) {
                LightningSettings.setSpeedPercent(slider.getFloatValue());
            } else if (slider == volumeSlider) {
                LightningSettings.setVolumePercent(slider.getFloatValue());
            } else if (slider == opacitySlider) {
                LightningSettings.setOpacityPercent(slider.getFloatValue());
            }
        }
        draggingSlider = null;
    }

    private void refreshAllControls() {
        nerfToggleButton.displayString = getNerfToggleText();
        fixRenderButton.displayString = getFixRenderToggleText();
        earlyGameButton.displayString = getEarlyGameButtonText();
        randomColorButton.displayString = getRandomColorToggleText();
        blocksSlider.setIntValue(LightningSettings.getBottomBlocks());
        speedSlider.setFloatValue(LightningSettings.getSpeedPercent());
        volumeSlider.setFloatValue(LightningSettings.getVolumePercent());
        opacitySlider.setFloatValue(LightningSettings.getOpacityPercent());
        colorRow.syncFromRgb(LightningSettings.getBoltColorArgb());
        refreshDependentButtonState();
    }

    private boolean tryPressSlider(GuiButton slider, int mouseX, int mouseY) {
        if (slider != null && slider.mousePressed(mc, mouseX, mouseY)) {
            draggingSlider = slider;
            return true;
        }
        return false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class IntValueSlider extends GuiButton {
        private final int min;
        private final int max;
        private final String labelPrefix;
        private final String labelSuffix;
        private float sliderPos;
        private int value;
        private boolean dragging;

        IntValueSlider(int id, int x, int y, int w, int min, int max, int current, String labelPrefix, String labelSuffix) {
            super(id, x, y, w, 20, "");
            this.min = min;
            this.max = max;
            this.labelPrefix = labelPrefix;
            this.labelSuffix = labelSuffix;
            this.value = clamp(current);
            this.sliderPos = rangePos(this.value);
            refreshLabel();
        }

        int getIntValue() {
            return value;
        }

        void setIntValue(int v) {
            value = clamp(v);
            sliderPos = rangePos(value);
            refreshLabel();
        }

        private int clamp(int v) {
            return Math.max(min, Math.min(max, v));
        }

        private float rangePos(int v) {
            return (max == min) ? 0f : (float) (v - min) / (float) (max - min);
        }

        private void refreshLabel() {
            displayString = EnumChatFormatting.WHITE + labelPrefix + ": " + value + labelSuffix;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            drawSliderChrome(mc, mouseX, mouseY);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!visible || !enabled) {
                return false;
            }
            if (mouseX >= xPosition && mouseY >= yPosition
                    && mouseX < xPosition + width && mouseY < yPosition + height) {
                dragging = true;
                updateFromMouse(mouseX);
                return true;
            }
            return false;
        }

        void dragTo(int mouseX) {
            if (!enabled || !dragging) {
                return;
            }
            updateFromMouse(mouseX);
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }

        private void updateFromMouse(int mouseX) {
            float pos = (float) (mouseX - (xPosition + 4)) / (float) (width - 8);
            sliderPos = Math.max(0f, Math.min(1f, pos));
            value = clamp(min + Math.round(sliderPos * (max - min)));
            refreshLabel();
        }

        private void drawSliderChrome(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            if (!enabled) {
                super.drawButton(mc, mouseX, mouseY);
                return;
            }
            mc.getTextureManager().bindTexture(buttonTextures);
            GlStateManager.color(1f, 1f, 1f, 1f);
            int half = width / 2;
            drawTexturedModalRect(xPosition, yPosition, 0, 46, half, 20);
            drawTexturedModalRect(xPosition + half, yPosition, 200 - half, 46, half, 20);
            int knobX = xPosition + (int) (sliderPos * (width - 8));
            drawTexturedModalRect(knobX, yPosition, 0, 66, 4, 20);
            drawTexturedModalRect(knobX + 4, yPosition, 196, 66, 4, 20);
            boolean hovered = mouseX >= xPosition && mouseY >= yPosition
                    && mouseX < xPosition + width && mouseY < yPosition + height;
            int textColor = !enabled ? 0x808080 : (hovered ? 0xFFFFA0 : 0xFFFFFF);
            int tw = mc.fontRendererObj.getStringWidth(displayString);
            mc.fontRendererObj.drawString(displayString, xPosition + (width - tw) / 2, yPosition + 6, textColor);
        }
    }

    private static final class FloatValueSlider extends GuiButton {
        private final float min;
        private final float max;
        private final String labelPrefix;
        private final String labelSuffix;
        private final int decimals;
        private float sliderPos;
        private float value;
        private boolean dragging;

        FloatValueSlider(int id, int x, int y, int w, float min, float max, float current,
                String labelPrefix, String labelSuffix, int decimals) {
            super(id, x, y, w, 20, "");
            this.min = min;
            this.max = max;
            this.labelPrefix = labelPrefix;
            this.labelSuffix = labelSuffix;
            this.decimals = decimals;
            this.value = clamp(current);
            this.sliderPos = rangePos(this.value);
            refreshLabel();
        }

        float getFloatValue() {
            return value;
        }

        void setFloatValue(float v) {
            value = clamp(v);
            sliderPos = rangePos(value);
            refreshLabel();
        }

        private float clamp(float v) {
            return Math.max(min, Math.min(max, v));
        }

        private float rangePos(float v) {
            return (max == min) ? 0f : (v - min) / (max - min);
        }

        private void refreshLabel() {
            String num = decimals <= 0
                    ? String.valueOf(Math.round(value))
                    : String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
            displayString = EnumChatFormatting.WHITE + labelPrefix + ": " + num + labelSuffix;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            if (!enabled) {
                super.drawButton(mc, mouseX, mouseY);
                return;
            }
            mc.getTextureManager().bindTexture(buttonTextures);
            GlStateManager.color(1f, 1f, 1f, 1f);
            int half = width / 2;
            drawTexturedModalRect(xPosition, yPosition, 0, 46, half, 20);
            drawTexturedModalRect(xPosition + half, yPosition, 200 - half, 46, half, 20);
            int knobX = xPosition + (int) (sliderPos * (width - 8));
            drawTexturedModalRect(knobX, yPosition, 0, 66, 4, 20);
            drawTexturedModalRect(knobX + 4, yPosition, 196, 66, 4, 20);
            boolean hovered = mouseX >= xPosition && mouseY >= yPosition
                    && mouseX < xPosition + width && mouseY < yPosition + height;
            int textColor = hovered ? 0xFFFFA0 : 0xFFFFFF;
            int tw = mc.fontRendererObj.getStringWidth(displayString);
            mc.fontRendererObj.drawString(displayString, xPosition + (width - tw) / 2, yPosition + 6, textColor);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!visible || !enabled) {
                return false;
            }
            if (mouseX >= xPosition && mouseY >= yPosition
                    && mouseX < xPosition + width && mouseY < yPosition + height) {
                dragging = true;
                updateFromMouse(mouseX);
                return true;
            }
            return false;
        }

        void dragTo(int mouseX) {
            if (!enabled || !dragging) {
                return;
            }
            updateFromMouse(mouseX);
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }

        private void updateFromMouse(int mouseX) {
            float pos = (float) (mouseX - (xPosition + 4)) / (float) (width - 8);
            sliderPos = Math.max(0f, Math.min(1f, pos));
            value = clamp(min + sliderPos * (max - min));
            refreshLabel();
        }
    }
}
