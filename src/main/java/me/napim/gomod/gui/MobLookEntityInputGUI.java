package me.napim.gomod.gui;

import me.napim.gomod.features.LocalMobDisguise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Set third-person mob look by Minecraft entity id (e.g. {@code EnderDragon}, {@code Zombie}, {@code Pig}).
 */
public class MobLookEntityInputGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int MAX_LEN = 48;

    private final GuiScreen parent;
    private final String initialValue;
    private GuiTextField textField;
    private GuiButton applyButton;
    private GuiButton cancelButton;
    private GuiButton offButton;

    public MobLookEntityInputGUI(GuiScreen parent, String initialValue) {
        this.parent = parent;
        this.initialValue = initialValue == null ? "" : initialValue;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        Keyboard.enableRepeatEvents(true);
        int fieldWidth = 220;
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.textField = new GuiTextField(0, this.fontRendererObj, centerX - fieldWidth / 2, centerY - 10, fieldWidth, 20);
        this.textField.setMaxStringLength(MAX_LEN);
        this.textField.setFocused(true);
        this.textField.setText(sanitize(this.initialValue));
        this.textField.setCursorPositionEnd();
        this.applyButton = new GuiButton(1, centerX - 102, centerY + 20, 100, 20, "Apply");
        this.cancelButton = new GuiButton(2, centerX + 2, centerY + 20, 100, 20, "Cancel");
        this.offButton = new GuiButton(3, centerX - 70, centerY + 46, 140, 20,
                EnumChatFormatting.RED + "Disable Mob Disguise");
        this.offButton.enabled = LocalMobDisguise.isActive();
        this.buttonList.add(this.applyButton);
        this.buttonList.add(this.cancelButton);
        this.buttonList.add(this.offButton);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null) {
            return;
        }
        if (button.id == 1) {
            applyAndClose();
        } else if (button.id == 2) {
            goBack();
        } else if (button.id == 3) {
            if (LocalMobDisguise.isActive()) {
                LocalMobDisguise.clearCustomAndSetPreset(LocalMobDisguise.Kind.OFF);
            }
            goBack();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
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
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
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
        if (this.offButton != null) {
            this.offButton.enabled = LocalMobDisguise.isActive();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        String title = EnumChatFormatting.LIGHT_PURPLE.toString() + EnumChatFormatting.BOLD + "Mob disguise";
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, title, this.width, this.height / 2 - 52, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, EnumChatFormatting.GRAY + "(e.g Zombie, pig, chicken..)", this.width, this.height / 2 - 36, 0xCCCCCC);
        if (this.textField != null) {
            this.textField.drawTextBox();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void applyAndClose() {
        String raw = this.textField == null ? "" : sanitize(this.textField.getText());
        if (!raw.isEmpty()) {
            LocalMobDisguise.setCustomEntityId(raw);
        }
        goBack();
    }

    private void goBack() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.displayGuiScreen(this.parent);
        }
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_LEN));
        for (int i = 0; i < s.length() && b.length() < MAX_LEN; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                b.append(c);
            }
        }
        return b.toString();
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(textField);
    }
}
