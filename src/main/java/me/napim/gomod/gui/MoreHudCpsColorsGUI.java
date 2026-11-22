package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MoreHudFeature;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/** Structural and digit colors for the CPS HUD row (HUD Editor → CPS). */
public class MoreHudCpsColorsGUI extends GuiScreen {

    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;
    private static final int BTN_H = 20;
    private static final int ROW_GAP = 24;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;
    private static final int SLIDER_RESET_GAP = HueColorPickerRow.DEFAULT_RESET_GAP;
    private static final int COLOR_ROW_COUNT = 3;
    private static final int DEFAULT_PREFIX_COLOR = 0xFFFFFF;
    private static final int DEFAULT_SEPARATOR_COLOR = 0xFFFFFF;
    private static final int DEFAULT_VALUE_COLOR = 0xFFFFFF;

    private static final int ID_BACK = 99;
    private static final int ID_PREFIX = 1;
    private static final int ID_SEPARATOR = 3;
    private static final int ID_VALUE = 2;
    private static final int ID_RESET_PREFIX = 101;
    private static final int ID_RESET_SEPARATOR = 103;
    private static final int ID_RESET_VALUE = 102;

    private final GuiScreen parent;
    private HueColorPickerRow prefixRow;
    private HueColorPickerRow separatorRow;
    private HueColorPickerRow valueRow;
    private HueColorPickerRow[] colorRows;
    private GuiButton backButton;
    private HueColorPickerRow draggingRow;
    private int modulePreviewAnchorY;

    public MoreHudCpsColorsGUI(GuiScreen parent) {
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

        prefixRow = new HueColorPickerRow(ID_PREFIX, "Brackets",
                rgb -> ConfigManager.setMoreHudCpsPrefixColor(rgb));
        prefixRow.syncFromRgb(ConfigManager.getMoreHudCpsPrefixColor());
        prefixRow.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        separatorRow = new HueColorPickerRow(ID_SEPARATOR, "Separator",
                rgb -> ConfigManager.setMoreHudCpsSeparatorColor(rgb));
        separatorRow.syncFromRgb(ConfigManager.getMoreHudCpsSeparatorColor());
        separatorRow.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        valueRow = new HueColorPickerRow(ID_VALUE, "CPS value",
                rgb -> ConfigManager.setMoreHudCpsColor(rgb));
        valueRow.syncFromRgb(ConfigManager.getMoreHudCpsColor());
        valueRow.layoutHudColorRow(rowLeft, y0);
        int stackBottom = y0 + BTN_H;
        modulePreviewAnchorY = (stackBottom + backY) / 2;

        colorRows = new HueColorPickerRow[] { prefixRow, separatorRow, valueRow };
        HueColorPickerRow.configureStackedHudRows(colorRows);
        for (HueColorPickerRow row : colorRows) {
            row.setOnPersist(ConfigManager::saveConfig);
        }

        int rowY = contentTop + Math.max(0, (backY - 16 - contentTop - stackH) / 2);
        GuiButton resetPrefix = new GuiButton(ID_RESET_PREFIX, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton resetSeparator = new GuiButton(ID_RESET_SEPARATOR, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton resetValue = new GuiButton(ID_RESET_VALUE, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        backButton = new GuiButton(ID_BACK, rowLeft, backY, ROW_WIDTH, BTN_H, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        for (HueColorPickerRow row : colorRows) {
            row.addSliderTo(buttonList);
        }
        buttonList.add(resetPrefix);
        buttonList.add(resetSeparator);
        buttonList.add(resetValue);
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCpsPreview();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "CPS HUD colors"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y,
                0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        HueColorPickerRow.drawAllPickerOverlays(mc, mouseX, mouseY, partialTicks, colorRows);
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

    private void drawCpsPreview() {
        if (mc == null || mc.fontRendererObj == null || modulePreviewAnchorY <= 0) {
            return;
        }
        int backTop = GuiHeader.backButtonY(height, BTN_H);
        int contentTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj) + 12;
        int zoneTop = Math.max(contentTop, modulePreviewAnchorY - 28);
        int zoneBottom = Math.max(zoneTop + 8, backTop - 8);
        int zoneLeft = width / 2 - ROW_WIDTH / 2;
        int zoneRight = width / 2 + ROW_WIDTH / 2;
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneBottom, 0x33000000);
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneTop + 1, 0x66AAAAAA);
        Gui.drawRect(zoneLeft, zoneBottom - 1, zoneRight, zoneBottom, 0x66AAAAAA);
        int previewCenterX = (zoneLeft + zoneRight) / 2;
        int previewCenterY = (zoneTop + zoneBottom) / 2;
        HudGlState.restoreForOverlayText();
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        try {
            MoreHudFeature.renderCpsColorGallerySample(mc, mc.fontRendererObj, previewCenterX, previewCenterY);
        } catch (Throwable ignored) {
        }
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == ID_BACK) {
            mc.displayGuiScreen(parent);
            return;
        }
        switch (button.id) {
            case ID_RESET_PREFIX:
                resetColor(prefixRow, DEFAULT_PREFIX_COLOR);
                break;
            case ID_RESET_SEPARATOR:
                resetColor(separatorRow, DEFAULT_SEPARATOR_COLOR);
                break;
            case ID_RESET_VALUE:
                resetColor(valueRow, DEFAULT_VALUE_COLOR);
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
