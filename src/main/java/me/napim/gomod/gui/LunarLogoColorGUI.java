package me.napim.gomod.gui;

import me.napim.gomod.features.GomodLogoColorSync;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;

/** Gomod-side RGB selector; color is persisted and distributed by the Lunar API service. */
public final class LunarLogoColorGUI extends GuiScreen {
    private static final int BUTTON_WIDTH = 320;
    private static final int BUTTON_HEIGHT = 20;
    private static final int DEFAULT_PREVIEW_RGB = 0xFFFFFF;
    private static final int INLINE_PICKER_MIN_HEIGHT = 480;

    private final GuiScreen parent;
    private final HsvColorPickerWidget colorPicker = new HsvColorPickerWidget();
    private GuiTextField rgbField;
    private GuiButton applyButton;
    private boolean inlinePicker;
    private boolean sideBySideButtons;
    private boolean shortLayout;
    private boolean userEditedColor;
    private int inputY;
    private int statusY;
    private int previewRgb = DEFAULT_PREVIEW_RGB;
    private int lastObservedServiceRgb = Integer.MIN_VALUE;

    public LunarLogoColorGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int screenWidth = sr.getScaledWidth();
        int screenHeight = sr.getScaledHeight();
        int cx = screenWidth / 2;
        int backY = GuiHeader.backButtonY(screenHeight, BUTTON_HEIGHT);
        inlinePicker = screenHeight >= INLINE_PICKER_MIN_HEIGHT;
        sideBySideButtons = screenHeight < 250;
        shortLayout = screenHeight < 250;

        if (inlinePicker) {
            int panelY = Math.max(105, Math.min(112, screenHeight / 2 - 220));
            int panelX = cx - HsvColorPickerWidget.panelOuterWidth() / 2 + 3;
            colorPicker.enableInlineMode(panelX, panelY);
            inputY = panelY + HsvColorPickerWidget.panelOuterHeight() + 12;
        } else {
            if (sideBySideButtons) {
                inputY = Math.max(64, backY - 72);
            } else if (screenHeight < 350) {
                inputY = Math.max(102, Math.min(screenHeight / 2 + 10, backY - 104));
            } else {
                inputY = screenHeight / 2 + 10;
            }
            colorPicker.setExpandBelow(true);
            colorPicker.setPosition(cx + 60, inputY);
        }

        int initialServiceRgb = GomodLogoColorSync.getCurrentRgb();
        lastObservedServiceRgb = initialServiceRgb;
        previewRgb = displayRgb(initialServiceRgb);
        colorPicker.syncFromRgb(previewRgb);
        colorPicker.setListener(new HsvColorPickerWidget.Listener() {
            @Override
            public void onColorChanged(int rgb) {
                userEditedColor = true;
                previewRgb = rgb & 0xFFFFFF;
                if (rgbField != null) {
                    rgbField.setText(formatRgb(previewRgb));
                }
            }

            @Override
            public void onColorChangeComplete(int rgb) {
                previewRgb = rgb & 0xFFFFFF;
                if (rgbField != null) {
                    rgbField.setText(formatRgb(previewRgb));
                }
            }
        });

        rgbField = new GuiTextField(0, fontRendererObj, cx - 51, inputY, 102, 20);
        rgbField.setMaxStringLength(7);
        rgbField.setText(formatRgb(previewRgb));

        int applyY;
        int resetY;
        int applyX;
        int applyWidth;
        int resetX;
        int resetWidth;
        if (sideBySideButtons) {
            applyY = backY - BUTTON_HEIGHT - 3;
            resetY = applyY;
            applyWidth = (BUTTON_WIDTH - 6) / 2;
            resetWidth = applyWidth;
            applyX = cx - BUTTON_WIDTH / 2;
            resetX = cx + 3;
        } else {
            applyY = Math.min(inputY + 32, backY - 47);
            resetY = applyY + BUTTON_HEIGHT + 7;
            applyWidth = BUTTON_WIDTH;
            resetWidth = BUTTON_WIDTH;
            applyX = cx - BUTTON_WIDTH / 2;
            resetX = applyX;
        }

        applyButton = new GuiButton(1, applyX, applyY, applyWidth, BUTTON_HEIGHT,
                EnumChatFormatting.GREEN + "Apply color");
        GuiButton resetButton = new GuiButton(2, resetX, resetY, resetWidth, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "Use API default");
        GuiButton backButton = new GuiButton(0, cx - BUTTON_WIDTH / 2, backY,
                BUTTON_WIDTH, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back");
        buttonList.clear();
        buttonList.add(applyButton);
        buttonList.add(resetButton);
        buttonList.add(backButton);

        statusY = sideBySideButtons ? inputY + 22 : resetY + 25;
        if (!sideBySideButtons) {
            statusY = Math.min(statusY, backY - 34);
        }
        GomodLogoColorSync.queryLogoColor();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        syncFromServiceIfUntouched();
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        int screenWidth = sr.getScaledWidth();
        int cx = screenWidth / 2;
        int screenHeight = sr.getScaledHeight();
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "Lunar Logo Color", screenWidth,
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "Pick visually or enter a hex value", screenWidth,
                GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        if (screenHeight >= 350) {
            drawCenteredString(fontRendererObj, "Default / White #FFFFFF   |   Lunar+ Pink #FF55FF",
                    cx, 55, 0xD0D0D0);
            drawCenteredString(fontRendererObj, "Staff: Green #00AAAA / Purple #AA00AA",
                    cx, 65, 0xD0D0D0);
            drawCenteredString(fontRendererObj, "Developer: Cyan #55FFFF   |   Tester: Yellow #FFFF55",
                    cx, 75, 0xD0D0D0);
            drawCenteredString(fontRendererObj, "Sponsor / Partner: Orange / Gold #FFAA00",
                    cx, 85, 0xD0D0D0);
        } else if (!shortLayout) {
            drawCenteredString(fontRendererObj,
                    "Standard colors: white, pink, teal, purple, cyan, yellow, orange/gold",
                    cx, 55, 0xD0D0D0);
        }

        if (inlinePicker) {
            int panelLabelY = Math.max(91, inputY - HsvColorPickerWidget.panelOuterHeight() - 17);
            drawCenteredString(fontRendererObj, "Drag in the color grid to choose a shade",
                    cx, panelLabelY, 0xD0D0D0);
        } else if (!shortLayout) {
            drawCenteredString(fontRendererObj, "Click the color swatch to open the picker",
                    cx, inputY - 25, 0xD0D0D0);
        }

        drawCenteredString(fontRendererObj, "Hex color (optional)", cx, inputY - 13, 0xD0D0D0);
        drawColorPreview(cx - 77, inputY + 4, previewRgb, parseRgb(rgbField.getText()) >= 0);
        rgbField.drawTextBox();
        if (!inlinePicker) {
            if (!shortLayout) {
                drawCenteredString(fontRendererObj, "Pick", cx + 70, inputY - 10, 0xB8B8B8);
            }
        }
        applyButton.enabled = parseRgb(rgbField.getText()) >= 0;

        String status = GomodLogoColorSync.getStatus();
        drawCenteredString(fontRendererObj, status, cx, statusY,
                status.startsWith("Lunar API unavailable") || status.startsWith("Logo color request failed")
                        ? 0xFF7777 : 0xC0C0C0);
        if (screenHeight >= 350) {
            drawCenteredString(fontRendererObj,
                    "Role labels follow the connected gomod API's color mapping.", cx,
                    statusY + 14, 0xA0A0A0);
            drawCenteredString(fontRendererObj,
                    "Changes are shared with gomod clients using Lunar FullAccess.", cx,
                    statusY + 27, 0xA0A0A0);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, LunarLogoColorGUI.class);
        if (inlinePicker) {
            colorPicker.drawExpanded(mc, mouseX, mouseY, partialTicks);
        } else {
            colorPicker.drawButton(mc, mouseX, mouseY);
            colorPicker.drawExpanded(mc, mouseX, mouseY, partialTicks);
        }
    }

    private void drawColorPreview(int x, int y, int rgb, boolean validInput) {
        int border = validInput ? 0xFF000000 : 0xFFFF5555;
        drawRect(x - 1, y - 1, x + 20, y + 15, border);
        drawRect(x, y, x + 19, y + 14, 0xFF000000 | (rgb & 0xFFFFFF));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                mc.displayGuiScreen(parent);
                break;
            case 1:
                applySelectedColor();
                break;
            case 2:
                GomodLogoColorSync.resetLogoColor();
                previewRgb = DEFAULT_PREVIEW_RGB;
                rgbField.setText(formatRgb(previewRgb));
                colorPicker.syncFromRgb(previewRgb);
                userEditedColor = false;
                lastObservedServiceRgb = GomodLogoColorSync.getCurrentRgb();
                break;
            default:
                break;
        }
    }

    private void applySelectedColor() {
        int rgb = parseRgb(rgbField.getText());
        if (rgb < 0) {
            return;
        }
        previewRgb = rgb;
        colorPicker.syncFromRgb(rgb);
        GomodLogoColorSync.setLogoColor(rgb);
        userEditedColor = false;
        lastObservedServiceRgb = GomodLogoColorSync.getCurrentRgb();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (colorPicker.tryConsumeClick(mc, mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        rgbField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton,
                                  long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        colorPicker.mouseClickMove(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        colorPicker.mouseReleased(mouseX, mouseY);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (rgbField.textboxKeyTyped(typedChar, keyCode)) {
            userEditedColor = true;
            int rgb = parseRgb(rgbField.getText());
            if (rgb >= 0) {
                previewRgb = rgb;
                colorPicker.syncFromRgb(rgb);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            applySelectedColor();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void syncFromServiceIfUntouched() {
        int current = GomodLogoColorSync.getCurrentRgb();
        if (current == lastObservedServiceRgb) {
            return;
        }
        lastObservedServiceRgb = current;
        if (userEditedColor || current < 0
                || (current > 0xFFFFFF && current != GomodLogoColorSync.DEFAULT_SENTINEL)) {
            return;
        }
        previewRgb = displayRgb(current);
        rgbField.setText(formatRgb(previewRgb));
        colorPicker.syncFromRgb(previewRgb);
    }

    private static int displayRgb(int rgb) {
        return rgb >= 0 && rgb <= 0xFFFFFF ? rgb : DEFAULT_PREVIEW_RGB;
    }

    private static String formatRgb(int rgb) {
        String value = Integer.toHexString(rgb & 0xFFFFFF).toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(6);
        for (int i = value.length(); i < 6; i++) {
            result.append('0');
        }
        result.append(value);
        return result.toString();
    }

    private static int parseRgb(String text) {
        if (text == null) return -1;
        String value = text.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() != 6) return -1;
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
