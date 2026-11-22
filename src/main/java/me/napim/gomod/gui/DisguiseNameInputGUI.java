/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.gui.GuiTextField
 *  net.minecraft.util.EnumChatFormatting
 *  org.lwjgl.input.Keyboard
 */
package me.napim.gomod.gui;

import me.napim.gomod.command.DisguiseCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

public class DisguiseNameInputGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private final GuiScreen parent;
    private final String initialValue;
    private GuiTextField textField;
    private GuiButton applyButton;
    private GuiButton cancelButton;

    public DisguiseNameInputGUI(GuiScreen parent, String initialValue) {
        this.parent = parent;
        this.initialValue = initialValue == null ? "" : initialValue;
    }

    public void initGui() {
        this.buttonList.clear();
        Keyboard.enableRepeatEvents((boolean)true);
        int fieldWidth = 200;
        int fieldHeight = 20;
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.textField = new GuiTextField(0, this.fontRendererObj, centerX - fieldWidth / 2, centerY - 10, fieldWidth, fieldHeight);
        this.textField.setMaxStringLength(16);
        this.textField.setFocused(true);
        this.textField.setText(DisguiseNameInputGUI.sanitize(this.initialValue));
        this.textField.setCursorPositionEnd();
        this.applyButton = new GuiButton(1, centerX - 102, centerY + 20, 100, 20, "Apply");
        this.cancelButton = new GuiButton(2, centerX + 2, centerY + 20, 100, 20, "Cancel");
        this.buttonList.add(this.applyButton);
        this.buttonList.add(this.cancelButton);
    }

    public void onGuiClosed() {
        Keyboard.enableRepeatEvents((boolean)false);
    }

    public void actionPerformed(GuiButton button) {
        if (button == null) {
            return;
        }
        if (button.id == 1) {
            this.applyAndClose();
        } else if (button.id == 2) {
            this.goBack();
        }
    }

    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (this.textField != null && this.textField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            this.applyAndClose();
            return;
        }
        if (keyCode == 1) {
            this.goBack();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.textField != null) {
            this.textField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    public void updateScreen() {
        if (this.textField != null) {
            this.textField.updateCursorCounter();
        }
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        String title = EnumChatFormatting.LIGHT_PURPLE.toString() + EnumChatFormatting.BOLD + "Disguise";
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, title, this.width, this.height / 2 - 40, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, EnumChatFormatting.GRAY + "Enter a player name", this.width, this.height / 2 - 26, 0xCCCCCC);
        if (this.textField != null) {
            this.textField.drawTextBox();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(this.textField);
    }

    private void applyAndClose() {
        String name;
        String string = name = this.textField == null ? "" : DisguiseNameInputGUI.sanitize(this.textField.getText());
        if (!name.isEmpty()) {
            DisguiseCommand.enableDisguiseAsync(name);
        }
        this.goBack();
    }

    private void goBack() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.displayGuiScreen(this.parent);
        }
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < raw.length() && cleaned.length() < 16; ++i) {
            char c = raw.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') && c != '_') continue;
            cleaned.append(c);
        }
        return cleaned.toString();
    }
}
