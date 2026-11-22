package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodBlockOverlay;
import me.napim.gomod.features.HudEditorCrosshairRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * Block overlay settings — transparent background so the world stays visible while tuning.
 */
public final class GomodBlockOverlaySettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;
    private static final int SLIDER_WIDTH = 120;

    private static final int ID_TOGGLE = 1;
    private static final int ID_MODE = 2;
    private static final int ID_FILL = 3;
    private static final int ID_LINE_PLACEMENT = 4;
    private static final int ID_SHAPE_AWARE = 12;
    private static final int ID_HIDE_BARRIERS_IN_CAGES = 13;
    private static final int ID_PLAYER_OVERLAY = 14;
    private static final int ID_LINE_WIDTH = 10;
    private static final int ID_BACK = 99;

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private GuiButton modeButton;
    private GuiButton fillButton;
    private GuiButton shapeAwareButton;
    private GuiButton hideBarriersInCagesButton;
    private GuiButton playerOverlayButton;
    private GuiButton linePlacementButton;
    private LineWidthSlider lineWidthSlider;
    private HueColorPickerRow colorRow;
    private HueColorPickerRow[] colorRows;
    private GuiButton backButton;

    private int labelX;
    private int rightColumnX;
    private int rowStartY;
    private int colorRowY;
    private int fillOpacityBarX;
    private int fillOpacityBarY;
    private int fillOpacityBarW;

    private GuiButton draggingSlider;
    private boolean fillOpacityDragging;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public GomodBlockOverlaySettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        Mouse.setGrabbed(false);
        HueColorPickerRow.prepareIconCache();

        ScaledResolution sr = new ScaledResolution(mc);
        labelX = sr.getScaledWidth() / 2 - 200;
        rightColumnX = sr.getScaledWidth() / 2 + 80;
        rowStartY = Math.max(90, sr.getScaledHeight() / 2 - 100);

        toggleButton = new GuiButton(ID_TOGGLE, rightColumnX, rowStartY, SLIDER_WIDTH, BUTTON_HEIGHT, getToggleText());
        modeButton = new GuiButton(ID_MODE, rightColumnX, rowStartY + SPACING, SLIDER_WIDTH, BUTTON_HEIGHT, getModeText());
        shapeAwareButton = new GuiButton(ID_SHAPE_AWARE, rightColumnX, rowStartY + SPACING * 2, SLIDER_WIDTH,
                BUTTON_HEIGHT, getShapeAwareText());
        hideBarriersInCagesButton = new GuiButton(ID_HIDE_BARRIERS_IN_CAGES, rightColumnX, rowStartY + SPACING * 3,
                SLIDER_WIDTH, BUTTON_HEIGHT, getHideBarriersInCagesText());
        fillButton = new GuiButton(ID_FILL, rightColumnX, rowStartY + SPACING * 4, SLIDER_WIDTH, BUTTON_HEIGHT, getFillText());
        linePlacementButton = new GuiButton(ID_LINE_PLACEMENT, rightColumnX, rowStartY + SPACING * 5, SLIDER_WIDTH,
                BUTTON_HEIGHT, getLinePlacementText());
        playerOverlayButton = new GuiButton(ID_PLAYER_OVERLAY, rightColumnX, rowStartY + SPACING * 6, SLIDER_WIDTH,
                BUTTON_HEIGHT, getPlayerOverlayText());

        colorRowY = rowStartY + SPACING * 7 + 4;
        colorRow = new HueColorPickerRow(0, "", new HueColorPickerRow.ColorConsumer() {
            @Override
            public void accept(int rgb) {
                ConfigManager.setGomodBlockOverlayColorArgb(0xFF000000 | (rgb & 0xFFFFFF));
            }
        });
        colorRow.setOnPersist(new Runnable() {
            @Override
            public void run() {
                ConfigManager.saveConfig();
            }
        });
        colorRow.syncFromRgb(ConfigManager.getGomodBlockOverlayColorArgb());
        fillOpacityBarX = colorRow.layoutPickerOnly(rightColumnX, colorRowY, SLIDER_WIDTH);
        fillOpacityBarY = colorRowY;
        fillOpacityBarW = HueColorPickerRow.companionWidth(rightColumnX, SLIDER_WIDTH, fillOpacityBarX);
        colorRows = new HueColorPickerRow[] { colorRow };
        HueColorPickerRow.configureStackedHudRows(colorRows);

        int lineWidthSliderY = colorRowY + SPACING + 4;
        lineWidthSlider = new LineWidthSlider(ID_LINE_WIDTH, rightColumnX, lineWidthSliderY, SLIDER_WIDTH,
                ConfigManager.getGomodBlockOverlayLineWidth());

        backButton = new GuiButton(ID_BACK, sr.getScaledWidth() / 2 - 100,
                GuiHeader.backButtonY(sr.getScaledHeight(), BUTTON_HEIGHT), 200, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(modeButton);
        buttonList.add(shapeAwareButton);
        buttonList.add(hideBarriersInCagesButton);
        buttonList.add(fillButton);
        buttonList.add(linePlacementButton);
        buttonList.add(playerOverlayButton);
        buttonList.add(lineWidthSlider);
        buttonList.add(backButton);

        enabledOverlays.clear();
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return GomodBlockOverlay.isEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("playerOverlay", playerOverlayButton,
                new ModuleSettingsEnabledOverlays.EnabledCheck() {
                    @Override
                    public boolean isOn() {
                        return ConfigManager.isGomodBlockOverlayPlayerOverlayEnabled();
                    }
                });
    }

    private String getToggleText() {
        return GomodBlockOverlay.isEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private String getModeText() {
        return GomodBlockOverlay.getModeLabel();
    }

    private String getShapeAwareText() {
        return ConfigManager.isGomodBlockOverlaySideShapeAware()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
    }

    private String getHideBarriersInCagesText() {
        return ConfigManager.isGomodBlockOverlayHideBarriersInCages()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
    }

    private String getFillText() {
        return ConfigManager.isGomodBlockOverlayFillEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
    }

    private String getLinePlacementText() {
        return GomodBlockOverlay.getLinePlacementLabel();
    }

    private String getPlayerOverlayText() {
        return ConfigManager.isGomodBlockOverlayPlayerOverlayEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // No drawDefaultBackground — world stays fully visible (mixin also skips backdrop for this screen).

        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Block overlay";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String desc = EnumChatFormatting.GRAY + "Configure while looking at a block";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, desc, width / 2, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        fontRendererObj.drawStringWithShadow("Block overlay", labelX, rowStartY + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Mode", labelX, rowStartY + SPACING + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Shape-aware side", labelX, rowStartY + SPACING * 2 + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Hide barrier blocks' overlay during cages", labelX,
                rowStartY + SPACING * 3 + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Fill", labelX, rowStartY + SPACING * 4 + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Line placement", labelX, rowStartY + SPACING * 5 + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Player overlay", labelX, rowStartY + SPACING * 6 + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Color", labelX, colorRowY + 6, 0xFFFFFF);
        if (lineWidthSlider != null) {
            fontRendererObj.drawStringWithShadow("Line width", labelX, lineWidthSlider.yPosition + 4, 0xFFFFFF);
        }

        syncShapeAwareButtonState();

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawFillOpacityBar();
        enabledOverlays.drawAll();
        HueColorPickerRow.drawAllPickerOverlays(mc, mouseX, mouseY, partialTicks, colorRows);
        HudEditorCrosshairRenderer.drawAtScreenCenter();
        GomodSearchHighlight.draw(buttonList, GomodBlockOverlaySettingsGUI.class);
        drawShapeAwareTooltip(mouseX, mouseY);
        drawHideBarriersInCagesTooltip(mouseX, mouseY);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // Non-pausing world-visible GUI: keep the OS pointer visible for clicking controls.
        if (Mouse.isGrabbed()) {
            Mouse.setGrabbed(false);
        }
    }

    private void drawHideBarriersInCagesTooltip(int mouseX, int mouseY) {
        if (hideBarriersInCagesButton == null || !hideBarriersInCagesButton.visible) {
            return;
        }
        if (mouseX < hideBarriersInCagesButton.xPosition
                || mouseX >= hideBarriersInCagesButton.xPosition + hideBarriersInCagesButton.width
                || mouseY < hideBarriersInCagesButton.yPosition
                || mouseY >= hideBarriersInCagesButton.yPosition + hideBarriersInCagesButton.height) {
            return;
        }
        java.util.List<String> lines = new java.util.ArrayList<String>();
        lines.add(EnumChatFormatting.GRAY + "Hides the block overlay on barrier blocks while cages are on");
        drawHoveringText(lines, mouseX, Math.max(0, mouseY - 12));
    }

    private void drawShapeAwareTooltip(int mouseX, int mouseY) {
        if (shapeAwareButton == null || !shapeAwareButton.visible) {
            return;
        }
        if (mouseX < shapeAwareButton.xPosition || mouseX >= shapeAwareButton.xPosition + shapeAwareButton.width
                || mouseY < shapeAwareButton.yPosition || mouseY >= shapeAwareButton.yPosition + shapeAwareButton.height) {
            return;
        }
        java.util.List<String> lines = new java.util.ArrayList<String>();
        lines.add(EnumChatFormatting.GRAY + "Uses the block's collision shape (slab top, stair tread)");
        lines.add(EnumChatFormatting.GRAY + "instead of a full 1x1 box. Side mode only.");
        if (!shapeAwareButton.enabled) {
            lines.add(EnumChatFormatting.DARK_GRAY + "Switch Mode to Side to change this.");
        }
        drawHoveringText(lines, mouseX, Math.max(0, mouseY - 12));
    }

    private String fillOpacityBarLabel() {
        int pct = Math.round(ConfigManager.getGomodBlockOverlayFillOpacity() * 100.0f);
        return "Fill: " + pct + "%";
    }

    private int fillOpacityBarFillArgb() {
        int rgb = ConfigManager.getGomodBlockOverlayColorArgb() & 0xFFFFFF;
        if (rgb == 0) {
            return HudSliderStyle.HUD_OPACITY_FILL_RGB;
        }
        return 0xFF000000 | rgb;
    }

    private void drawFillOpacityBar() {
        float opacity = ConfigManager.getGomodBlockOverlayFillOpacity();
        int alpha = Math.round(opacity * 255.0f);
        HudOpacitySliderBoxInsets.drawValueBar(fontRendererObj, HudOpacitySliderBoxInsets.Module.DEFAULT,
                fillOpacityBarX, fillOpacityBarY, fillOpacityBarW, BUTTON_HEIGHT, alpha, fillOpacityBarFillArgb(),
                fillOpacityBarLabel());
    }

    private boolean isOverFillOpacityBar(int mouseX, int mouseY) {
        return mouseX >= fillOpacityBarX && mouseX <= fillOpacityBarX + fillOpacityBarW
                && mouseY >= fillOpacityBarY && mouseY <= fillOpacityBarY + BUTTON_HEIGHT;
    }

    private void updateFillOpacityFromMouse(int mouseX) {
        int alpha = HudOpacitySliderBoxInsets.opacityFromMouseXOuter(mouseX, fillOpacityBarX, fillOpacityBarW);
        ConfigManager.setGomodBlockOverlayFillOpacity(alpha / 255.0f);
    }

    private void syncShapeAwareButtonState() {
        if (shapeAwareButton == null) {
            return;
        }
        boolean sideMode = GomodBlockOverlay.MODE_SIDE.equals(GomodBlockOverlay.getMode());
        shapeAwareButton.enabled = sideMode;
        shapeAwareButton.displayString = sideMode ? getShapeAwareText()
                : EnumChatFormatting.DARK_GRAY + "Side only";
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_TOGGLE: {
                boolean now = !GomodBlockOverlay.isEnabled();
                GomodBlockOverlay.setEnabled(now);
                toggleButton.displayString = getToggleText();
                enabledOverlays.onToggled("main", now);
                break;
            }
            case ID_MODE:
                GomodBlockOverlay.cycleMode();
                modeButton.displayString = getModeText();
                syncShapeAwareButtonState();
                break;
            case ID_SHAPE_AWARE:
                ConfigManager.setGomodBlockOverlaySideShapeAware(!ConfigManager.isGomodBlockOverlaySideShapeAware());
                shapeAwareButton.displayString = getShapeAwareText();
                break;
            case ID_HIDE_BARRIERS_IN_CAGES:
                ConfigManager.setGomodBlockOverlayHideBarriersInCages(
                        !ConfigManager.isGomodBlockOverlayHideBarriersInCages());
                hideBarriersInCagesButton.displayString = getHideBarriersInCagesText();
                break;
            case ID_FILL:
                ConfigManager.setGomodBlockOverlayFillEnabled(!ConfigManager.isGomodBlockOverlayFillEnabled());
                fillButton.displayString = getFillText();
                break;
            case ID_LINE_PLACEMENT:
                GomodBlockOverlay.cycleLinePlacement();
                linePlacementButton.displayString = getLinePlacementText();
                break;
            case ID_PLAYER_OVERLAY: {
                boolean now = !ConfigManager.isGomodBlockOverlayPlayerOverlayEnabled();
                ConfigManager.setGomodBlockOverlayPlayerOverlayEnabled(now);
                playerOverlayButton.displayString = getPlayerOverlayText();
                enabledOverlays.onToggled("playerOverlay", now);
                break;
            }
            case ID_BACK:
                mc.displayGuiScreen(parent);
                if (parent instanceof MiscellaneousGUI) {
                    ((MiscellaneousGUI) parent).refreshBlockOverlayButtonLabel();
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        draggingSlider = null;
        fillOpacityDragging = false;
        if (HueColorPickerRow.tryConsumeOpenPickerClicksFirst(mc, mouseX, mouseY, mouseButton, colorRows)) {
            return;
        }
        if (colorRows != null) {
            for (HueColorPickerRow row : colorRows) {
                if (row.tryPickerClick(mc, mouseX, mouseY, mouseButton, colorRows)) {
                    return;
                }
            }
        }
        if (lineWidthSlider != null && lineWidthSlider.mousePressed(mc, mouseX, mouseY)) {
            draggingSlider = lineWidthSlider;
            ConfigManager.setGomodBlockOverlayLineWidth(lineWidthSlider.getValue());
            return;
        }
        if (mouseButton == 0 && isOverFillOpacityBar(mouseX, mouseY)) {
            fillOpacityDragging = true;
            updateFillOpacityFromMouse(mouseX);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (colorRow != null) {
            colorRow.pickerMouseMove(mouseX, mouseY);
        }
        if (draggingSlider instanceof LineWidthSlider) {
            LineWidthSlider slider = (LineWidthSlider) draggingSlider;
            slider.dragTo(mouseX);
            ConfigManager.setGomodBlockOverlayLineWidth(slider.getValue());
            return;
        }
        if (fillOpacityDragging) {
            updateFillOpacityFromMouse(mouseX);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (colorRows != null) {
            for (HueColorPickerRow row : colorRows) {
                row.pickerMouseRelease(mouseX, mouseY);
            }
        }
        if (state == 0) {
            if (draggingSlider instanceof LineWidthSlider) {
                ConfigManager.setGomodBlockOverlayLineWidth(((LineWidthSlider) draggingSlider).getValue());
            }
            draggingSlider = null;
            fillOpacityDragging = false;
        }
    }

    @Override
    public void drawDefaultBackground() {
        // Intentionally empty — transparent settings while viewing blocks.
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private abstract static class VanillaKnobSlider extends GuiButton {
        protected float sliderPos;
        protected boolean dragging;

        VanillaKnobSlider(int id, int x, int y, int width) {
            super(id, x, y, width, 20, "");
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            mc.getTextureManager().bindTexture(buttonTextures);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int half = width / 2;
            drawTexturedModalRect(xPosition, yPosition, 0, 46, half, 20);
            drawTexturedModalRect(xPosition + half, yPosition, 200 - half, 46, half, 20);
            int knobX = xPosition + (int) (sliderPos * (width - 8));
            drawTexturedModalRect(knobX, yPosition, 0, 66, 4, 20);
            drawTexturedModalRect(knobX + 4, yPosition, 196, 66, 4, 20);
            boolean hovered = mouseX >= xPosition && mouseY >= yPosition
                    && mouseX < xPosition + width && mouseY < yPosition + height;
            int textColor = !enabled ? 0x808080 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
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
            if (dragging) {
                updateFromMouse(mouseX);
            }
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }

        protected void setSliderPos(float pos) {
            sliderPos = Math.max(0.0F, Math.min(1.0F, pos));
        }

        protected float normalizedFromMouse(int mouseX) {
            float pos = (float) (mouseX - (xPosition + 4)) / (float) (width - 8);
            return Math.max(0.0F, Math.min(1.0F, pos));
        }

        protected abstract void updateFromMouse(int mouseX);
    }

    private static final class LineWidthSlider extends VanillaKnobSlider {
        private static final float MIN = 1.0F;
        private static final float MAX = 15.0F;
        private float value;

        LineWidthSlider(int id, int x, int y, int width, float current) {
            super(id, x, y, width);
            setValue(current);
        }

        float getValue() {
            return value;
        }

        private void setValue(float v) {
            value = Math.max(MIN, Math.min(MAX, v));
            setSliderPos((value - MIN) / (MAX - MIN));
            refreshLabel();
        }

        private void refreshLabel() {
            displayString = EnumChatFormatting.WHITE + String.format("%.1f px", value);
        }

        @Override
        protected void updateFromMouse(int mouseX) {
            float t = normalizedFromMouse(mouseX);
            setValue(MIN + t * (MAX - MIN));
        }
    }

}
