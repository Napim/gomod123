package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MoreHudArmorStatusFeature;
import me.napim.gomod.features.MoreHudFeature;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/** Durability tier colors for the Armor status HUD row (HUD Editor → Armor status). */
public class MoreHudArmorStatusColorsGUI extends GuiScreen {

    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;
    private static final int BTN_H = 20;
    private static final int ROW_GAP = 24;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;
    private static final int COLOR_ROW_COUNT = 4;

    private static final int ID_BACK = 99;
    private static final int ID_TIER1 = 1;
    private static final int ID_TIER2 = 2;
    private static final int ID_TIER3 = 3;
    private static final int ID_TIER4 = 4;
    private static final int ID_RESET1 = 101;
    private static final int ID_RESET2 = 102;
    private static final int ID_RESET3 = 103;
    private static final int ID_RESET4 = 104;

    private final GuiScreen parent;
    private HueColorPickerRow tier1Row;
    private HueColorPickerRow tier2Row;
    private HueColorPickerRow tier3Row;
    private HueColorPickerRow tier4Row;
    private HueColorPickerRow[] colorRows;
    private GuiButton backButton;
    private HueColorPickerRow draggingRow;
    private int modulePreviewAnchorY;

    public MoreHudArmorStatusColorsGUI(GuiScreen parent) {
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

        tier1Row = new HueColorPickerRow(ID_TIER1, "75–100%",
                rgb -> ConfigManager.setMoreHudArmorStatusDuraColor(1, rgb));
        tier1Row.syncFromRgb(ConfigManager.getMoreHudArmorStatusDuraColor(1));
        tier1Row.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        tier2Row = new HueColorPickerRow(ID_TIER2, "50–75%",
                rgb -> ConfigManager.setMoreHudArmorStatusDuraColor(2, rgb));
        tier2Row.syncFromRgb(ConfigManager.getMoreHudArmorStatusDuraColor(2));
        tier2Row.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        tier3Row = new HueColorPickerRow(ID_TIER3, "25–50%",
                rgb -> ConfigManager.setMoreHudArmorStatusDuraColor(3, rgb));
        tier3Row.syncFromRgb(ConfigManager.getMoreHudArmorStatusDuraColor(3));
        tier3Row.layoutHudColorRow(rowLeft, y0);
        y0 += ROW_GAP;

        tier4Row = new HueColorPickerRow(ID_TIER4, "0–25%",
                rgb -> ConfigManager.setMoreHudArmorStatusDuraColor(4, rgb));
        tier4Row.syncFromRgb(ConfigManager.getMoreHudArmorStatusDuraColor(4));
        tier4Row.layoutHudColorRow(rowLeft, y0);
        int stackBottom = y0 + BTN_H;
        modulePreviewAnchorY = (stackBottom + backY) / 2;

        colorRows = new HueColorPickerRow[] { tier1Row, tier2Row, tier3Row, tier4Row };
        HueColorPickerRow.configureStackedHudRows(colorRows);
        for (HueColorPickerRow row : colorRows) {
            row.setOnPersist(ConfigManager::saveConfig);
        }

        int rowY = contentTop + Math.max(0, (backY - 16 - contentTop - stackH) / 2);
        GuiButton reset1 = new GuiButton(ID_RESET1, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton reset2 = new GuiButton(ID_RESET2, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton reset3 = new GuiButton(ID_RESET3, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);
        rowY += ROW_GAP;
        GuiButton reset4 = new GuiButton(ID_RESET4, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        backButton = new GuiButton(ID_BACK, rowLeft, backY, ROW_WIDTH, BTN_H, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        for (HueColorPickerRow row : colorRows) {
            row.addSliderTo(buttonList);
        }
        buttonList.add(reset1);
        buttonList.add(reset2);
        buttonList.add(reset3);
        buttonList.add(reset4);
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawArmorPreview();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Armor status colors"
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

    private void drawArmorPreview() {
        if (mc == null || modulePreviewAnchorY <= 0) {
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
        int[] pivot = new int[2];
        MoreHudFeature.resolveSettingsGalleryPivot(mc, MoreHudSettingsGUI.Panel.ARMOR_STATUS, previewCenterX,
                previewCenterY, pivot);
        try {
            MoreHudArmorStatusFeature.renderGalleryAt(mc, pivot[0], pivot[1]);
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
            case ID_RESET1:
                resetTier(tier1Row, 1, 0x00A000);
                break;
            case ID_RESET2:
                resetTier(tier2Row, 2, 0xDCD200);
                break;
            case ID_RESET3:
                resetTier(tier3Row, 3, 0xBE7800);
                break;
            case ID_RESET4:
                resetTier(tier4Row, 4, 0xA00000);
                break;
            default:
                break;
        }
    }

    private void resetTier(HueColorPickerRow row, int tier, int rgb) {
        row.setRgb(rgb);
        ConfigManager.setMoreHudArmorStatusDuraColor(tier, rgb);
        ConfigManager.saveConfig();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
