package me.napim.gomod.gui;

import me.napim.gomod.features.ScreenshotShareService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

public class ScreenshotShareGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int BTN_W = 220;
    private static final int BTN_H = 20;

    private final GuiScreen parent;
    /** Pre-filled target name (e.g. from friends list); may be empty. */
    private final String initialTarget;
    /** Non-null when sharing pre-annotated bytes from the screenshot editor. */
    private final byte[] editedBytes;
    private GuiTextField targetField;
    private GuiButton shareButton;
    private GuiButton backButton;

    public ScreenshotShareGUI(GuiScreen parent) {
        this(parent, null, null);
    }

    public ScreenshotShareGUI(GuiScreen parent, String initialTarget) {
        this(parent, initialTarget, null);
    }

    /** Used by the screenshot editor to send annotated bytes to a friend. */
    public ScreenshotShareGUI(GuiScreen parent, byte[] editedBytes) {
        this(parent, null, editedBytes);
    }

    public ScreenshotShareGUI(GuiScreen parent, String initialTarget, byte[] editedBytes) {
        this.parent = parent;
        this.initialTarget = initialTarget == null ? "" : initialTarget.trim();
        this.editedBytes = editedBytes;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        super.initGui();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;

        targetField = new GuiTextField(1, fontRendererObj, cx - BTN_W / 2, cy - 25, BTN_W, BTN_H);
        targetField.setMaxStringLength(32);
        targetField.setCanLoseFocus(true);
        targetField.setFocused(true);
        if (!this.initialTarget.isEmpty()) {
            targetField.setText(this.initialTarget);
        }

        shareButton = new GuiButton(2, cx - BTN_W / 2, cy + 5, BTN_W, BTN_H,
                EnumChatFormatting.GREEN + "Share Screenshot");
        backButton = new GuiButton(0, cx - BTN_W / 2, cy + 30, BTN_W, BTN_H,
                EnumChatFormatting.GRAY + "Back");

        this.buttonList.add(shareButton);
        this.buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());

        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Screenshot Share",
                sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String subtitle = editedBytes != null
                ? EnumChatFormatting.GRAY + "Send your edited screenshot to a gomod player"
                : EnumChatFormatting.GRAY + "Send a screenshot directly to a gomod player";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, subtitle, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        drawCenteredString(fontRendererObj, EnumChatFormatting.YELLOW + "Target player:", width / 2, height / 2 - 38, 0xFFFFFF);
        targetField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (targetField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (keyCode == 28 || keyCode == 156) { // Enter
            triggerShare();
            return;
        }
        if (keyCode == 1) { // ESC
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (targetField != null) {
            targetField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 2) {
            triggerShare();
        }
    }

    private void triggerShare() {
        String target = targetField == null ? "" : targetField.getText();
        if (editedBytes != null) {
            ScreenshotShareService.shareBytesToPlayer(target, editedBytes, "edited_screenshot.png");
        } else {
            ScreenshotShareService.shareLatestToPlayer(target);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (targetField != null) {
            targetField.updateCursorCounter();
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
