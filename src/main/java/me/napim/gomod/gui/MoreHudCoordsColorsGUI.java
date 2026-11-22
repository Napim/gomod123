package me.napim.gomod.gui;



import me.napim.gomod.features.ConfigManager;

import me.napim.gomod.util.HudGlState;

import me.napim.gomod.features.MoreHudFeature;

import net.minecraft.client.gui.Gui;

import net.minecraft.client.gui.GuiButton;

import net.minecraft.client.gui.GuiScreen;

import net.minecraft.client.gui.ScaledResolution;

import net.minecraft.client.renderer.GlStateManager;

import net.minecraft.util.EnumChatFormatting;



import java.io.IOException;



/**

 * Per-element hue sliders for the Coords HUD (prefix, values, biome, cardinals, compass ring, dot).

 */

public class MoreHudCoordsColorsGUI extends GuiScreen {



    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;

    private static final int BTN_H = 20;

    private static final int ROW_GAP = 24;

    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;

    private static final int SLIDER_RESET_GAP = HueColorPickerRow.DEFAULT_RESET_GAP;

    private static final int COLOR_ROW_COUNT = 6;



    private static final int ID_BACK = 99;

    private static final int ID_PREFIX = 1;

    private static final int ID_TEXT = 2;

    private static final int ID_BIOME = 3;

    private static final int ID_CARDINAL = 4;

    private static final int ID_COMPASS_RING = 5;

    private static final int ID_COMPASS_DOT = 6;

    private static final int ID_RESET_PREFIX = 101;

    private static final int ID_RESET_TEXT = 102;

    private static final int ID_RESET_BIOME = 103;

    private static final int ID_RESET_CARDINAL = 104;

    private static final int ID_RESET_COMPASS_RING = 105;

    private static final int ID_RESET_COMPASS_DOT = 106;



    private static final int DEFAULT_COLOR = 0xFFFFFF;

    private static final int DEFAULT_COMPASS_RING = 0xAAAAAA;



    private final GuiScreen parent;



    private HueColorPickerRow prefixRow;

    private HueColorPickerRow textRow;

    private HueColorPickerRow biomeRow;

    private HueColorPickerRow cardinalRow;

    private HueColorPickerRow compassRingRow;

    private HueColorPickerRow compassDotRow;

    private HueColorPickerRow[] colorRows;

    private GuiButton backButton;

    private HueColorPickerRow draggingRow;

    private int modulePreviewAnchorX;

    private int modulePreviewAnchorY;



    public MoreHudCoordsColorsGUI(GuiScreen parent) {

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



        prefixRow = new HueColorPickerRow(ID_PREFIX, "Prefix color",

                rgb -> ConfigManager.setMoreHudCoordsPrefixColor(rgb));

        prefixRow.syncFromRgb(ConfigManager.getMoreHudCoordsPrefixColor());

        prefixRow.layoutHudColorRow(rowLeft, y0);

        y0 += ROW_GAP;



        textRow = new HueColorPickerRow(ID_TEXT, "Text color",

                rgb -> ConfigManager.setMoreHudCoordsTextColor(rgb));

        textRow.syncFromRgb(ConfigManager.getMoreHudCoordsTextColor());

        textRow.layoutHudColorRow(rowLeft, y0);

        y0 += ROW_GAP;



        biomeRow = new HueColorPickerRow(ID_BIOME, "Biome color",

                rgb -> ConfigManager.setMoreHudCoordsBiomeColor(rgb));

        biomeRow.syncFromRgb(ConfigManager.getMoreHudCoordsBiomeColor());

        biomeRow.layoutHudColorRow(rowLeft, y0);

        y0 += ROW_GAP;



        cardinalRow = new HueColorPickerRow(ID_CARDINAL, "Cardinal letters",

                rgb -> ConfigManager.setMoreHudCoordsDirectionColor(rgb));

        cardinalRow.syncFromRgb(ConfigManager.getMoreHudCoordsDirectionColor());

        cardinalRow.layoutHudColorRow(rowLeft, y0);

        y0 += ROW_GAP;



        compassRingRow = new HueColorPickerRow(ID_COMPASS_RING, "Compass ring",

                rgb -> ConfigManager.setMoreHudCoordsCompassRingColor(rgb));

        compassRingRow.syncFromRgb(ConfigManager.getMoreHudCoordsCompassRingColor());

        compassRingRow.layoutHudColorRow(rowLeft, y0);

        y0 += ROW_GAP;



        compassDotRow = new HueColorPickerRow(ID_COMPASS_DOT, "Compass dot",

                rgb -> ConfigManager.setMoreHudCoordsCompassDotColor(rgb));

        compassDotRow.syncFromRgb(ConfigManager.getMoreHudCoordsCompassDotColor());

        compassDotRow.layoutHudColorRow(rowLeft, y0);

        int stackBottom = y0 + BTN_H;

        modulePreviewAnchorX = cx;

        modulePreviewAnchorY = (stackBottom + backY) / 2;



        colorRows = new HueColorPickerRow[] {
                prefixRow, textRow, biomeRow, cardinalRow, compassRingRow, compassDotRow
        };
        HueColorPickerRow.configureStackedHudRows(colorRows);
        for (HueColorPickerRow row : colorRows) {
            row.setOnPersist(ConfigManager::saveConfig);
        }

        int rowY = contentTop + Math.max(0, (backY - 16 - contentTop - stackH) / 2);

        GuiButton resetPrefix = new GuiButton(ID_RESET_PREFIX, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        rowY += ROW_GAP;

        GuiButton resetText = new GuiButton(ID_RESET_TEXT, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        rowY += ROW_GAP;

        GuiButton resetBiome = new GuiButton(ID_RESET_BIOME, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        rowY += ROW_GAP;

        GuiButton resetCardinal = new GuiButton(ID_RESET_CARDINAL, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        rowY += ROW_GAP;

        GuiButton resetCompassRing = new GuiButton(ID_RESET_COMPASS_RING, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        rowY += ROW_GAP;

        GuiButton resetCompassDot = new GuiButton(ID_RESET_COMPASS_DOT, resetX, rowY, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);



        backButton = new GuiButton(ID_BACK, rowLeft, backY, ROW_WIDTH, BTN_H, EnumChatFormatting.GRAY + "Back");



        buttonList.clear();

        for (HueColorPickerRow row : colorRows) {

            row.addSliderTo(buttonList);

        }

        buttonList.add(resetPrefix);

        buttonList.add(resetText);

        buttonList.add(resetBiome);

        buttonList.add(resetCardinal);

        buttonList.add(resetCompassRing);

        buttonList.add(resetCompassDot);

        buttonList.add(backButton);

    }



    @Override

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {

        drawDefaultBackground();

        drawCoordsHudPreview();

        GuiHeader.drawTopLeft(this);

        ScaledResolution sr = new ScaledResolution(mc);

        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Coords HUD colors"

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



    private void drawCoordsHudPreview() {

        if (mc == null || mc.fontRendererObj == null || modulePreviewAnchorY <= 0) {

            return;

        }

        int backTop = GuiHeader.backButtonY(height, BTN_H);

        int contentTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj) + 12;

        int zoneTop = Math.max(contentTop, modulePreviewAnchorY - 36);

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

            MoreHudFeature.renderSettingsGalleryPreview(mc, MoreHudSettingsGUI.Panel.COORDS, previewCenterX,

                    previewCenterY);

        } catch (Throwable ignored) {

        }

        GlStateManager.depthMask(true);

        GlStateManager.enableDepth();

        GlStateManager.popMatrix();

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

            case ID_RESET_PREFIX:

                resetColor(prefixRow, DEFAULT_COLOR, true);

                break;

            case ID_RESET_TEXT:

                resetColor(textRow, DEFAULT_COLOR, true);

                break;

            case ID_RESET_BIOME:

                resetColor(biomeRow, DEFAULT_COLOR, true);

                break;

            case ID_RESET_CARDINAL:

                resetColor(cardinalRow, DEFAULT_COLOR, true);

                break;

            case ID_RESET_COMPASS_RING:

                resetColor(compassRingRow, DEFAULT_COMPASS_RING, true);

                break;

            case ID_RESET_COMPASS_DOT:

                resetColor(compassDotRow, GuiHeader.GOMOD_BRAND_RGB, true);

                break;

            default:

                break;

        }

    }



    private void resetColor(HueColorPickerRow row, int rgb, boolean save) {

        row.setRgb(rgb);

        if (save) {

            ConfigManager.saveConfig();

        }

    }



    @Override

    public boolean doesGuiPauseGame() {

        return false;

    }

}

