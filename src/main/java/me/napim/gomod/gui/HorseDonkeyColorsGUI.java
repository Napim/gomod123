package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.HorseDonkeyTracker;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/** Title, distance value, and "blocks" colors for the horse tracker distance HUD. */
public class HorseDonkeyColorsGUI extends GuiScreen {

    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;
    private static final int BTN_H = 20;
    private static final int ROW_GAP = 24;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;
    private static final int COLOR_ROW_COUNT = 3;
    private static final int DEFAULT_TITLE_COLOR = 0xFFAA00;
    private static final int DEFAULT_VALUE_COLOR = 0x55FF55;
    private static final int DEFAULT_BLOCKS_COLOR = 0xAAAAAA;

    private static final int ID_BACK = 99;
    private static final int ID_TITLE = 1;
    private static final int ID_VALUE = 2;
    private static final int ID_BLOCKS = 3;
    private static final int ID_RESET_TITLE = 101;
    private static final int ID_RESET_VALUE = 102;
    private static final int ID_RESET_BLOCKS = 103;

    private final GuiScreen parent;
    private HueColorPickerRow titleRow;
    private HueColorPickerRow valueRow;
    private HueColorPickerRow blocksRow;
    private HueColorPickerRow[] colorRows;
    private GuiButton backButton;
    private HueColorPickerRow draggingRow;
    private int modulePreviewAnchorY;

    public HorseDonkeyColorsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        HueColorPickerRow.prepareIconCache();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BTN_H);
        int stackH = BTN_H * COLOR_ROW_COUNT + ROW_GAP * (COLOR_ROW_COUNT - 1);
        int contentTop = GuiHeader.standardContentTopAfterTitle(mc.fontRendererObj) + 12;
        int y0 = contentTop + Math.max(0, (backY - 16 - contentTop - stackH) / 2);
        int rowLeft = cx - ROW_WIDTH / 2;
        int resetX = HueColorPickerRow.resetButtonX(rowLeft, ROW_WIDTH, RESET_BTN_W);

        titleRow = new HueColorPickerRow(ID_TITLE, "\"Horse distance\"",
                rgb -> ConfigManager.setHorseDonkeyHudTitleColor(rgb));
        titleRow.syncFromRgb(ConfigManager.getHorseDonkeyHudTitleColor());
        titleRow.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        valueRow = new HueColorPickerRow(ID_VALUE, "Distance value",
                rgb -> ConfigManager.setHorseDonkeyHudValueColor(rgb));
        valueRow.syncFromRgb(ConfigManager.getHorseDonkeyHudValueColor());
        valueRow.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        blocksRow = new HueColorPickerRow(ID_BLOCKS, "\"blocks\"",
                rgb -> ConfigManager.setHorseDonkeyHudBlocksColor(rgb));
        blocksRow.syncFromRgb(ConfigManager.getHorseDonkeyHudBlocksColor());
        blocksRow.layoutHudColorRow(rowLeft, y0);
        int stackBottom = y0 + BTN_H;
        modulePreviewAnchorY = (stackBottom + backY) / 2;

        colorRows = new HueColorPickerRow[] { titleRow, valueRow, blocksRow };
        HueColorPickerRow.configureStackedHudRows(colorRows);
        for (HueColorPickerRow row : colorRows) {
            row.setOnPersist(ConfigManager::saveConfig);
        }

        int rowY = contentTop + Math.max(0, (backY - 16 - contentTop - stackH) / 2);
        GuiButton resetTitle = new GuiButton(ID_RESET_TITLE, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton resetValue = new GuiButton(ID_RESET_VALUE, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton resetBlocks = new GuiButton(ID_RESET_BLOCKS, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        backButton = new GuiButton(ID_BACK, rowLeft, backY, ROW_WIDTH, BTN_H, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        for (HueColorPickerRow row : colorRows) {
            row.addSliderTo(buttonList);
        }
        buttonList.add(resetTitle);
        buttonList.add(resetValue);
        buttonList.add(resetBlocks);
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawHorsePreview();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Horse tracker colors"
                + EnumChatFormatting.RESET;
        GuiHeader.drawScreenCenteredText(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        HueColorPickerRow.drawAllPickerOverlays(mc, mouseX, mouseY, partialTicks, colorRows);
    }

    private void drawHorsePreview() {
        if (mc == null || modulePreviewAnchorY <= 0) {
            return;
        }
        int backTop = GuiHeader.backButtonY(height, BTN_H);
        int contentTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj) + 12;
        int zoneTop = Math.max(contentTop, modulePreviewAnchorY - 48);
        int zoneBottom = Math.max(zoneTop + 8, backTop - 8);
        int zoneLeft = width / 2 - ROW_WIDTH / 2;
        int zoneRight = width / 2 + ROW_WIDTH / 2;
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneBottom, 0x33000000);
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneTop + 1, 0x66AAAAAA);
        Gui.drawRect(zoneLeft, zoneBottom - 1, zoneRight, zoneBottom, 0x66AAAAAA);
        int previewCenterX = (zoneLeft + zoneRight) / 2;
        int previewCenterY = (zoneTop + zoneBottom) / 2;
        float previewScale = 1.0f;
        String previewLabel = HorseDonkeyTracker.getTrackedMountLabel() != null
                ? HorseDonkeyTracker.getTrackedMountLabel() : "Horse";
        double previewDistance = HorseDonkeyTracker.getTrackedDistance() >= 0.0D
                ? HorseDonkeyTracker.getTrackedDistance() : 17.4D;
        int[] panelSize = HorseDonkeyTracker.measureDistancePanelSize(mc, previewLabel, previewDistance);
        int topLeftX = previewCenterX - Math.round(panelSize[0] * previewScale / 2.0f);
        int topLeftY = previewCenterY - Math.round(panelSize[1] * previewScale / 2.0f);
        int savedX = HorseDonkeyTracker.getPanelX();
        int savedY = HorseDonkeyTracker.getPanelY();
        float savedScale = HorseDonkeyTracker.getScale();
        try {
            HorseDonkeyTracker.setCustomPosition(topLeftX, topLeftY);
            HorseDonkeyTracker.setScale(previewScale);
            HudGlState.restoreForOverlayText();
            HorseDonkeyTracker.drawPreviewPanel(mc);
        } finally {
            HorseDonkeyTracker.setCustomPosition(savedX, savedY);
            HorseDonkeyTracker.setScale(savedScale);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        draggingRow = null;
        for (HueColorPickerRow row : colorRows) {
            if (row.tryPickerClick(mc, mouseX, mouseY, mouseButton, colorRows)) {
                return;
            }
        }
        for (HueColorPickerRow row : colorRows) {
            if (row.trySliderPress(mc, mouseX, mouseY)) {
                draggingRow = row;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingRow != null) {
            draggingRow.dragSlider(mouseX);
        }
        for (HueColorPickerRow row : colorRows) {
            row.pickerMouseMove(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (draggingRow != null) {
            draggingRow.releaseSlider(mouseX, mouseY);
            draggingRow = null;
            ConfigManager.saveConfig();
        }
        for (HueColorPickerRow row : colorRows) {
            row.pickerMouseRelease(mouseX, mouseY);
        }
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == ID_BACK) {
            mc.displayGuiScreen(parent);
            return;
        }
        switch (button.id) {
            case ID_RESET_TITLE:
                resetColor(titleRow, DEFAULT_TITLE_COLOR);
                break;
            case ID_RESET_VALUE:
                resetColor(valueRow, DEFAULT_VALUE_COLOR);
                break;
            case ID_RESET_BLOCKS:
                resetColor(blocksRow, DEFAULT_BLOCKS_COLOR);
                break;
            default:
                break;
        }
    }

    private void resetColor(HueColorPickerRow row, int rgb) {
        row.setRgb(rgb);
        ConfigManager.saveConfig();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
