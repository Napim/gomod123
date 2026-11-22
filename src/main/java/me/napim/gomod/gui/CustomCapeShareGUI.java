package me.napim.gomod.gui;

import me.napim.gomod.features.CustomCapeShareService;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Pick a gomod friend to receive the current custom cape design. */
public class CustomCapeShareGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int BTN_W = 220;
    private static final int BTN_H = 20;

    private final GuiScreen parent;
    private final BufferedImage design;
    private final String capeLabel;
    private GuiTextField targetField;

    public CustomCapeShareGUI(GuiScreen parent, BufferedImage design, String capeLabel) {
        this.parent = parent;
        this.design = design;
        this.capeLabel = capeLabel == null ? "Custom cape" : capeLabel;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        super.initGui();
        int cx = this.width / 2;
        int cy = this.height / 2;
        this.targetField = new GuiTextField(1, this.fontRendererObj, cx - BTN_W / 2, cy - 25, BTN_W, BTN_H);
        this.targetField.setMaxStringLength(32);
        this.targetField.setFocused(true);
        this.buttonList.add(new GuiButton(2, cx - BTN_W / 2, cy + 5, BTN_W, BTN_H,
                EnumChatFormatting.GREEN + "Share cape design"));
        this.buttonList.add(new GuiButton(0, cx - BTN_W / 2, cy + 30, BTN_W, BTN_H,
                EnumChatFormatting.GRAY + "Back"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Share cape design",
                this.width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        this.fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + "Send to a gomod friend (dev build)",
                this.width / 2 - this.fontRendererObj.getStringWidth("Send to a gomod friend (dev build)") / 2,
                GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        drawCenteredString(this.fontRendererObj, EnumChatFormatting.YELLOW + "Friend name:",
                this.width / 2, this.height / 2 - 38, 0xFFFFFF);
        this.targetField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 2) {
            String target = this.targetField == null ? "" : this.targetField.getText();
            CustomCapeShareService.shareDesignToFriend(target, design, capeLabel);
            this.mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.targetField != null && this.targetField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            for (Object o : this.buttonList) {
                if (o instanceof GuiButton && ((GuiButton) o).id == 2) {
                    actionPerformed((GuiButton) o);
                    return;
                }
            }
        }
        if (keyCode == 1) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (this.targetField != null) {
            this.targetField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.targetField != null) {
            this.targetField.updateCursorCounter();
        }
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(targetField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
