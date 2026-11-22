package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/** Color picker for held-item enchant text. Potion effect colors keep following the Effects style toggle. */
public class HeldItemInfoColorsGUI extends GuiScreen {

    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;
    private static final int BTN_H = 20;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;

    private static final int ID_COLOR = 1;
    private static final int ID_RESET = 101;
    private static final int ID_BACK = 99;
    private static final int DEFAULT_ENCHANT_RGB = 0x00AAAA;

    private final GuiScreen parent;
    private HueColorPickerRow enchantRow;
    private HueColorPickerRow[] colorRows;
    private GuiButton backButton;
    private HueColorPickerRow draggingRow;
    private int modulePreviewAnchorY;

    public HeldItemInfoColorsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        HueColorPickerRow.prepareIconCache();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int backY = GuiHeader.backButtonY(sr.getScaledHeight(), BTN_H);
        int contentTop = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj);
        int rowLeft = cx - ROW_WIDTH / 2;
        int y = contentTop + Math.max(0, (backY - 16 - contentTop - BTN_H) / 2);
        int resetX = HueColorPickerRow.resetButtonX(rowLeft, ROW_WIDTH, RESET_BTN_W);

        enchantRow = new HueColorPickerRow(ID_COLOR, "Enchant text",
                ConfigManager::setHotbarEnchantTextColor);
        enchantRow.syncFromRgb(ConfigManager.getHotbarEnchantTextColor());
        enchantRow.getSlider().setLabelColorSupplier(ConfigManager::getHotbarEnchantTextColor);
        enchantRow.layoutHudColorRow(rowLeft, y);
        enchantRow.setOnPersist(ConfigManager::saveConfig);

        colorRows = new HueColorPickerRow[] { enchantRow };
        HueColorPickerRow.configureStackedHudRows(colorRows);
        modulePreviewAnchorY = (y + BTN_H + backY) / 2;

        buttonList.clear();
        enchantRow.addSliderTo(buttonList);
        buttonList.add(new GuiButton(ID_RESET, resetX, y, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON));
        backButton = new GuiButton(ID_BACK, rowLeft, backY, ROW_WIDTH, BTN_H, EnumChatFormatting.GRAY + "Back");
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawHeldItemPreview();
        GuiHeader.drawTopLeft(this);
        String title = "Held item info colors";
        String subtitle = EnumChatFormatting.GRAY + "Enchant lines use this color";
        GuiHeader.drawYellowBoldTitleCentered(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, subtitle, width / 2, GuiHeader.STANDARD_SUBTITLE_Y,
                0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        HueColorPickerRow.drawAllPickerOverlays(mc, mouseX, mouseY, partialTicks, colorRows);
    }

    private void drawHeldItemPreview() {
        if (mc == null || fontRendererObj == null || modulePreviewAnchorY <= 0) {
            return;
        }
        int contentTop = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj);
        int backTop = GuiHeader.backButtonY(height, BTN_H);
        int zoneTop = Math.max(contentTop, modulePreviewAnchorY - 48);
        int zoneBottom = Math.max(zoneTop + 8, backTop - 8);
        int zoneLeft = width / 2 - ROW_WIDTH / 2;
        int zoneRight = width / 2 + ROW_WIDTH / 2;
        HeldItemInfoPreviewRenderer.draw(mc, fontRendererObj, width, zoneLeft, zoneTop, zoneRight, zoneBottom);
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
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_RESET:
                enchantRow.setRgb(DEFAULT_ENCHANT_RGB);
                ConfigManager.setHotbarEnchantTextColor(DEFAULT_ENCHANT_RGB);
                ConfigManager.saveConfig();
                break;
            case ID_BACK:
                mc.displayGuiScreen(parent);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
