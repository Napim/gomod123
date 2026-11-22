package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/** Settings screen for held-item enchant / potion info above the vanilla item-name popup. */
public class HeldItemInfoSettingsGUI extends GuiScreen {

    private static final int BTN_H = 20;
    private static final int BUTTON_WIDTH = 220;
    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;
    private static final int DEFAULT_ENCHANT_RGB = 0x00AAAA;
    private static final int ROW = 24;

    private static final int ID_TOGGLE = 1;
    private static final int ID_SHADOW = 2;
    private static final int ID_POTION_STYLE = 4;
    private static final int ID_GAP = 5;
    private static final int ID_RESET = 101;
    private static final int ID_BACK = 99;
    private static final int GAP_MIN = 0;
    private static final int GAP_MAX = 20;

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private GuiButton shadowButton;
    private GuiButton potionStyleButton;
    private GapAboveNameSlider gapSlider;
    private GuiButton draggingGapSlider;
    private GuiButton backButton;
    private HueColorPickerRow enchantRow;
    private HueColorPickerRow[] colorRows;
    private HueColorPickerRow draggingRow;
    private int modulePreviewAnchorY;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public HeldItemInfoSettingsGUI(GuiScreen parent) {
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
        int contentTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj) + 12;
        int stackH = BTN_H + ROW * 4;
        int y = contentTop + Math.max(0, (backY - 16 - contentTop - stackH) / 2);

        enchantRow = new HueColorPickerRow(200, "Enchant text", ConfigManager::setHotbarEnchantTextColor);
        enchantRow.syncFromRgb(ConfigManager.getHotbarEnchantTextColor());
        enchantRow.getSlider().setLabelColorSupplier(ConfigManager::getHotbarEnchantTextColor);

        int pickerW = enchantRow.getPicker().getButtonWidth();
        int pickerGap = HueColorPickerRow.DEFAULT_RESET_GAP;
        int sliderWidth = HueColorPickerRow.BARRIER_PICKER_SLIDER_ROW_W - pickerW - pickerGap;
        int buttonLeft = cx - sliderWidth / 2;
        int rowLeft = buttonLeft - pickerW - pickerGap;
        int resetX = buttonLeft + sliderWidth + HueColorPickerRow.DEFAULT_RESET_GAP;
        int backButtonLeft = cx - BUTTON_WIDTH / 2;

        toggleButton = new GuiButton(ID_TOGGLE, buttonLeft, y, sliderWidth, BTN_H,
                toggleText());
        y += ROW;
        shadowButton = new GuiButton(ID_SHADOW, buttonLeft, y, sliderWidth, BTN_H, shadowText());
        y += ROW;
        potionStyleButton = new GuiButton(ID_POTION_STYLE, buttonLeft, y, sliderWidth, BTN_H, potionStyleText());
        y += ROW;
        gapSlider = new GapAboveNameSlider(ID_GAP, buttonLeft, y, sliderWidth,
                ConfigManager.getHotbarEnchantTextGapAboveName());
        y += ROW;

        enchantRow.layoutHudColorRow(rowLeft, y);
        enchantRow.setOnPersist(ConfigManager::saveConfig);
        colorRows = new HueColorPickerRow[] { enchantRow };
        HueColorPickerRow.configureStackedHudRows(colorRows);

        modulePreviewAnchorY = (y + BTN_H + backY) / 2;
        backButton = new GuiButton(ID_BACK, backButtonLeft, backY, BUTTON_WIDTH, BTN_H,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(shadowButton);
        buttonList.add(potionStyleButton);
        buttonList.add(gapSlider);
        enchantRow.addSliderTo(buttonList);
        buttonList.add(new GuiButton(ID_RESET, resetX, y, RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON));
        buttonList.add(backButton);

        enabledOverlays.clear();
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return ConfigManager.isHotbarEnchantTextEnabled();
            }
        });
    }

    private String toggleText() {
        return EnumChatFormatting.WHITE + "Held item info: "
                + (ConfigManager.isHotbarEnchantTextEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String shadowText() {
        return EnumChatFormatting.WHITE + "Shadow: "
                + (ConfigManager.isHotbarEnchantTextShadow()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String potionStyleText() {
        return EnumChatFormatting.WHITE + "Potion text: "
                + (ConfigManager.isHotbarEnchantPotionTextOnly()
                ? EnumChatFormatting.AQUA + "Text only"
                : EnumChatFormatting.GREEN + "With icon");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawHeldItemPreview();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Held item info"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width,
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, HeldItemInfoSettingsGUI.class);
        HueColorPickerRow.drawAllPickerOverlays(mc, mouseX, mouseY, partialTicks, colorRows);
    }

    private void drawHeldItemPreview() {
        if (mc == null || fontRendererObj == null || modulePreviewAnchorY <= 0) {
            return;
        }
        int contentTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj) + 12;
        int backTop = GuiHeader.backButtonY(height, BTN_H);
        int zoneTop = Math.max(contentTop, modulePreviewAnchorY - 48);
        int zoneBottom = Math.max(zoneTop + 8, backTop - 8);
        int zoneLeft = width / 2 - ROW_WIDTH / 2;
        int zoneRight = width / 2 + ROW_WIDTH / 2;
        HeldItemInfoPreviewRenderer.draw(mc, fontRendererObj, width, zoneLeft, zoneTop, zoneRight, zoneBottom);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        draggingGapSlider = null;
        draggingRow = null;
        if (gapSlider != null && gapSlider.mousePressed(mc, mouseX, mouseY)) {
            draggingGapSlider = gapSlider;
            ConfigManager.applyHotbarEnchantTextGapAboveName(gapSlider.getValue());
            return;
        }
        if (colorRows != null) {
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
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingGapSlider instanceof GapAboveNameSlider) {
            GapAboveNameSlider slider = (GapAboveNameSlider) draggingGapSlider;
            slider.dragTo(mouseX);
            ConfigManager.applyHotbarEnchantTextGapAboveName(slider.getValue());
            return;
        }
        if (draggingRow != null) {
            draggingRow.dragSlider(mouseX);
        }
        if (colorRows != null) {
            for (HueColorPickerRow row : colorRows) {
                row.pickerMouseMove(mouseX, mouseY);
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && draggingGapSlider instanceof GapAboveNameSlider) {
            ConfigManager.setHotbarEnchantTextGapAboveName(((GapAboveNameSlider) draggingGapSlider).getValue());
        }
        draggingGapSlider = null;
        if (draggingRow != null) {
            draggingRow.releaseSlider(mouseX, mouseY);
            draggingRow = null;
            ConfigManager.saveConfig();
        }
        if (colorRows != null) {
            for (HueColorPickerRow row : colorRows) {
                row.pickerMouseRelease(mouseX, mouseY);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_TOGGLE:
                boolean now = !ConfigManager.isHotbarEnchantTextEnabled();
                ConfigManager.setHotbarEnchantTextEnabled(now);
                toggleButton.displayString = toggleText();
                enabledOverlays.onToggled("main", now);
                break;
            case ID_SHADOW:
                ConfigManager.setHotbarEnchantTextShadow(!ConfigManager.isHotbarEnchantTextShadow());
                shadowButton.displayString = shadowText();
                break;
            case ID_POTION_STYLE:
                ConfigManager.cycleHotbarEnchantPotionTextStyle();
                potionStyleButton.displayString = potionStyleText();
                break;
            case ID_RESET:
                enchantRow.setRgb(DEFAULT_ENCHANT_RGB);
                ConfigManager.setHotbarEnchantTextColor(DEFAULT_ENCHANT_RGB);
                ConfigManager.saveConfig();
                break;
            case ID_BACK:
                mc.displayGuiScreen(parent);
                if (parent instanceof MiscellaneousGUI) {
                    ((MiscellaneousGUI) parent).refreshHeldItemInfoButtonLabel();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class GapAboveNameSlider extends GuiButton {
        private float sliderPos;
        private int value;
        private boolean dragging;

        GapAboveNameSlider(int id, int x, int y, int w, int current) {
            super(id, x, y, w, BTN_H, "");
            this.value = clamp(current);
            this.sliderPos = (GAP_MAX == GAP_MIN) ? 0f : (float) (value - GAP_MIN) / (float) (GAP_MAX - GAP_MIN);
            refreshLabel();
        }

        int getValue() {
            return value;
        }

        private void refreshLabel() {
            displayString = EnumChatFormatting.WHITE + "Gap from item name: "
                    + EnumChatFormatting.YELLOW + value + " px";
        }

        private static int clamp(int v) {
            return Math.max(GAP_MIN, Math.min(GAP_MAX, v));
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
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

        private void updateFromMouse(int mouseX) {
            float pos = (float) (mouseX - (xPosition + 4)) / (float) (width - 8);
            sliderPos = Math.max(0f, Math.min(1f, pos));
            value = clamp(GAP_MIN + Math.round(sliderPos * (GAP_MAX - GAP_MIN)));
            refreshLabel();
        }
    }
}
