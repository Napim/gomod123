package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Numeric input for disguise "Custom Kill Count" (TAB/chat bracket tiers when enabled).
 */
public class DisguiseCustomKillsInputGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private final GuiScreen parent;
    private final String initialValue;
    private GuiTextField textField;
    private GuiButton applyButton;
    private GuiButton cancelButton;

    public DisguiseCustomKillsInputGUI(GuiScreen parent, String initialValue) {
        this.parent = parent;
        this.initialValue = initialValue == null ? "" : initialValue;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        Keyboard.enableRepeatEvents(true);
        int fieldWidth = 200;
        int fieldHeight = 20;
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.textField = new GuiTextField(0, this.fontRendererObj, centerX - fieldWidth / 2, centerY - 10, fieldWidth, fieldHeight);
        this.textField.setMaxStringLength(9);
        this.textField.setFocused(true);
        this.textField.setText(sanitizeDigits(this.initialValue));
        this.textField.setCursorPositionEnd();
        this.applyButton = new GuiButton(1, centerX - 102, centerY + 20, 100, 20, "Apply");
        this.cancelButton = new GuiButton(2, centerX + 2, centerY + 20, 100, 20, "Cancel");
        this.buttonList.add(this.applyButton);
        this.buttonList.add(this.cancelButton);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button == null) {
            return;
        }
        if (button.id == 1) {
            applyAndClose();
        } else if (button.id == 2) {
            goBack();
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (this.textField != null && this.textField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            applyAndClose();
            return;
        }
        if (keyCode == 1) {
            goBack();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.textField != null) {
            this.textField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        if (this.textField != null) {
            this.textField.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        String title = EnumChatFormatting.LIGHT_PURPLE.toString() + EnumChatFormatting.BOLD + "Disguise";
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, title, this.width, this.height / 2 - 40, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, EnumChatFormatting.GRAY + "Custom kill count", this.width, this.height / 2 - 26, 0xCCCCCC);
        if (this.textField != null) {
            this.textField.drawTextBox();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void applyAndClose() {
        String raw = this.textField == null ? "" : this.textField.getText();
        raw = sanitizeDigits(raw);
        int kills = 0;
        if (!raw.isEmpty()) {
            try {
                kills = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                kills = 0;
            }
        }
        ConfigManager.setDisguiseCustomKillCount(kills);
        goBack();
    }

    private void goBack() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.displayGuiScreen(this.parent);
        }
    }

    private static String sanitizeDigits(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < raw.length() && cleaned.length() < 9; i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(textField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
