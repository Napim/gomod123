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

/** Count colors for the Arrows HUD row (HUD Editor → Arrows count). */
public class MoreHudArrowsColorsGUI extends GuiScreen {

    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;
    private static final int BTN_H = 20;
    private static final int ROW_GAP = 24;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;
    private static final int SLIDER_RESET_GAP = HueColorPickerRow.DEFAULT_RESET_GAP;
    private static final int COLOR_ROW_COUNT = 2;
    private static final int DEFAULT_COUNT_COLOR = 0xFFFFFF;
    private static final int DEFAULT_ZERO_COLOR = 0xFF5555;

    private static final int ID_BACK = 99;
    private static final int ID_COUNT = 1;
    private static final int ID_ZERO = 2;
    private static final int ID_RESET_COUNT = 101;
    private static final int ID_RESET_ZERO = 102;

    private final GuiScreen parent;
    private HueColorPickerRow countRow;
    private HueColorPickerRow zeroRow;
    private HueColorPickerRow[] colorRows;
    private GuiButton backButton;
    private HueColorPickerRow draggingRow;
    private int modulePreviewAnchorY;

    public MoreHudArrowsColorsGUI(GuiScreen parent) {
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

        countRow = new HueColorPickerRow(ID_COUNT, "Count",
                rgb -> ConfigManager.setMoreHudArrowsColor(rgb));
        countRow.syncFromRgb(ConfigManager.getMoreHudArrowsColor());
        countRow.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        zeroRow = new HueColorPickerRow(ID_ZERO, "Count at zero",
                rgb -> ConfigManager.setMoreHudArrowsZeroColor(rgb));
        zeroRow.syncFromRgb(ConfigManager.getMoreHudArrowsZeroColor());
        zeroRow.layoutHudColorRow(rowLeft, y0);
        int stackBottom = y0 + BTN_H;
        modulePreviewAnchorY = (stackBottom + backY) / 2;

        colorRows = new HueColorPickerRow[] { countRow, zeroRow };
        HueColorPickerRow.configureStackedHudRows(colorRows);
        for (HueColorPickerRow row : colorRows) {
            row.setOnPersist(ConfigManager::saveConfig);
        }

        int rowY = contentTop + Math.max(0, (backY - 16 - contentTop - stackH) / 2);
        GuiButton resetCount = new GuiButton(ID_RESET_COUNT, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton resetZero = new GuiButton(ID_RESET_ZERO, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        backButton = new GuiButton(ID_BACK, rowLeft, backY, ROW_WIDTH, BTN_H, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        for (HueColorPickerRow row : colorRows) {
            row.addSliderTo(buttonList);
        }
        buttonList.add(resetCount);
        buttonList.add(resetZero);
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawArrowsPreview();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Arrows count colors"
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

    private void drawArrowsPreview() {
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
            MoreHudFeature.renderArrowsColorGallerySample(mc, mc.fontRendererObj, previewCenterX, previewCenterY);
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
            case ID_RESET_COUNT:
                resetColor(countRow, DEFAULT_COUNT_COLOR);
                break;
            case ID_RESET_ZERO:
                resetColor(zeroRow, DEFAULT_ZERO_COLOR);
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
