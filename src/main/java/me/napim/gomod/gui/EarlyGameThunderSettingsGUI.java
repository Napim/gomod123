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

/**
 * Alternate thunder sound profile for a short window after Blitz cages open.
 */
public class EarlyGameThunderSettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 24;
    private static final int GAP = 8;
    private static final float MIN_SPEED = 25.0f;
    private static final float MAX_SPEED = 300.0f;
    private static final float MIN_VOLUME = 0.0f;
    private static final float MAX_VOLUME = 100.0f;
    private static final int MIN_DURATION_SEC = 1;
    private static final int MAX_DURATION_SEC = 60;

    private static final int ID_TOGGLE = 1;
    private static final int ID_TEST_SOUND = 2;
    private static final int ID_RESET = 3;
    private static final int ID_BACK = 99;

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private GuiButton testSoundButton;
    private GuiButton resetButton;
    private GuiButton backButton;
    private IntValueSlider durationSlider;
    private FloatValueSlider speedSlider;
    private FloatValueSlider volumeSlider;
    private GuiButton draggingSlider;
    private int contentButtonWidth;
    private boolean resetPending;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public EarlyGameThunderSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        resetPending = false;
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        contentButtonWidth = HueColorPickerRow.BARRIER_PICKER_SLIDER_ROW_W - 20 - HueColorPickerRow.DEFAULT_RESET_GAP;
        int buttonLeft = cx - contentButtonWidth / 2;

        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int resetY = backY - BUTTON_HEIGHT - GAP;
        int contentBottom = resetY - 12;
        int titleBottom = GuiHeader.STANDARD_SUBTITLE_Y + fontRendererObj.FONT_HEIGHT + 8;
        int rowCount = 5;
        int stackH = rowCount * (BUTTON_HEIGHT + GAP) - GAP;
        int y = titleBottom + Math.max(8, (contentBottom - titleBottom - stackH) / 2);

        toggleButton = new GuiButton(ID_TOGGLE, buttonLeft, y, contentButtonWidth, BUTTON_HEIGHT,
                getToggleText());
        y += BUTTON_HEIGHT + GAP;
        durationSlider = new IntValueSlider(10, buttonLeft, y, contentButtonWidth,
                MIN_DURATION_SEC, MAX_DURATION_SEC, LightningSettings.getPostCageSoundDurationSec(),
                "After cages open", " sec");
        y += BUTTON_HEIGHT + GAP;
        speedSlider = new FloatValueSlider(12, buttonLeft, y, contentButtonWidth, MIN_SPEED, MAX_SPEED,
                LightningSettings.getPostCageSpeedPercent(), "Sound speed", "%", 0);
        y += BUTTON_HEIGHT + GAP;
        volumeSlider = new FloatValueSlider(13, buttonLeft, y, contentButtonWidth, MIN_VOLUME, MAX_VOLUME,
                LightningSettings.getPostCageVolumePercent(), "Sound volume", "%", 0);
        y += BUTTON_HEIGHT + GAP;
        testSoundButton = new GuiButton(ID_TEST_SOUND, buttonLeft, y, contentButtonWidth, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "Test thunder");
        resetButton = new GuiButton(ID_RESET, buttonLeft, resetY, contentButtonWidth, BUTTON_HEIGHT,
                getResetButtonText());
        backButton = new GuiButton(ID_BACK, buttonLeft, backY, contentButtonWidth, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(durationSlider);
        buttonList.add(speedSlider);
        buttonList.add(volumeSlider);
        buttonList.add(testSoundButton);
        buttonList.add(resetButton);
        buttonList.add(backButton);

        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return LightningSettings.isPostCageSoundEnabled();
            }
        });

        refreshDependentButtonState();
    }

    private void refreshDependentButtonState() {
        boolean on = LightningSettings.isPostCageSoundEnabled();
        durationSlider.enabled = on;
        speedSlider.enabled = on;
        volumeSlider.enabled = on;
        testSoundButton.enabled = on;
    }

    private String getToggleText() {
        return EnumChatFormatting.GOLD + "Early game thunder: "
                + (LightningSettings.isPostCageSoundEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getResetButtonText() {
        return resetPending
                ? EnumChatFormatting.DARK_RED + "CONFIRM RESET"
                : EnumChatFormatting.GRAY + "Reset";
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Early game thunder";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String desc = EnumChatFormatting.GRAY + "Softer thunder right after cages open";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, desc, width / 2, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, EarlyGameThunderSettingsGUI.class);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == ID_BACK) {
            mc.displayGuiScreen(parent);
            if (parent instanceof LightningSettingsGUI) {
                ((LightningSettingsGUI) parent).refreshEarlyGameButtonLabel();
            }
            return;
        }
        if (button.id == ID_TOGGLE) {
            boolean now = !LightningSettings.isPostCageSoundEnabled();
            LightningSettings.setPostCageSoundEnabled(now);
            toggleButton.displayString = getToggleText();
            enabledOverlays.onToggled("main", now);
            refreshDependentButtonState();
            draggingSlider = null;
            return;
        }
        if (button.id == ID_RESET) {
            if (!resetPending) {
                resetPending = true;
                resetButton.displayString = getResetButtonText();
                return;
            }
            resetPending = false;
            resetButton.displayString = getResetButtonText();
            LightningSettings.resetPostCageToDefaults();
            refreshAllControls();
            enabledOverlays.onToggled("main", LightningSettings.isPostCageSoundEnabled());
            if (parent instanceof LightningSettingsGUI) {
                ((LightningSettingsGUI) parent).refreshEarlyGameButtonLabel();
            }
            return;
        }
        if (!LightningSettings.isPostCageSoundEnabled()) {
            return;
        }
        if (button.id == ID_TEST_SOUND) {
            LightningSound.playPreviewPostCage(mc);
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (!LightningSettings.isPostCageSoundEnabled()) {
            draggingSlider = null;
            return;
        }
        if (tryPressSlider(durationSlider, mouseX, mouseY)) {
            LightningSettings.setPostCageSoundDurationSec(durationSlider.getIntValue());
        } else if (tryPressSlider(speedSlider, mouseX, mouseY)) {
            LightningSettings.setPostCageSpeedPercent(speedSlider.getFloatValue());
        } else if (tryPressSlider(volumeSlider, mouseX, mouseY)) {
            LightningSettings.setPostCageVolumePercent(volumeSlider.getFloatValue());
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (!LightningSettings.isPostCageSoundEnabled()) {
            draggingSlider = null;
            return;
        }
        if (draggingSlider instanceof IntValueSlider) {
            IntValueSlider slider = (IntValueSlider) draggingSlider;
            slider.dragTo(mouseX);
            LightningSettings.setPostCageSoundDurationSec(slider.getIntValue());
        } else if (draggingSlider instanceof FloatValueSlider) {
            FloatValueSlider slider = (FloatValueSlider) draggingSlider;
            slider.dragTo(mouseX);
            if (slider == speedSlider) {
                LightningSettings.setPostCageSpeedPercent(slider.getFloatValue());
            } else if (slider == volumeSlider) {
                LightningSettings.setPostCageVolumePercent(slider.getFloatValue());
            }
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (!LightningSettings.isPostCageSoundEnabled()) {
            draggingSlider = null;
            return;
        }
        if (state == 0 && draggingSlider instanceof IntValueSlider) {
            LightningSettings.setPostCageSoundDurationSec(((IntValueSlider) draggingSlider).getIntValue());
        } else if (state == 0 && draggingSlider instanceof FloatValueSlider) {
            FloatValueSlider slider = (FloatValueSlider) draggingSlider;
            if (slider == speedSlider) {
                LightningSettings.setPostCageSpeedPercent(slider.getFloatValue());
            } else if (slider == volumeSlider) {
                LightningSettings.setPostCageVolumePercent(slider.getFloatValue());
            }
        }
        draggingSlider = null;
    }

    private void refreshAllControls() {
        toggleButton.displayString = getToggleText();
        durationSlider.setIntValue(LightningSettings.getPostCageSoundDurationSec());
        speedSlider.setFloatValue(LightningSettings.getPostCageSpeedPercent());
        volumeSlider.setFloatValue(LightningSettings.getPostCageVolumePercent());
        refreshDependentButtonState();
    }

    private boolean tryPressSlider(GuiButton slider, int mouseX, int mouseY) {
        if (slider == null || !slider.enabled) {
            return false;
        }
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
            value = clamp(min + Math.round(sliderPos * (max - min)));
            refreshLabel();
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
