package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.resources.I18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Yes/No confirmation under {@code me.napim.gomod.gui} so "Move while in menu" and drag-look
 * apply (vanilla {@link GuiYesNo} is excluded by package checks in movement mixins).
 */
public final class GomodConfirmDialog extends GuiYesNo {

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;

    private final GuiYesNoCallback callback;
    private final String title;
    private final String message;
    private final int callbackId;
    private List<String> messageLines = Collections.emptyList();
    private int titleY;
    private int messageY;

    public GomodConfirmDialog(GuiYesNoCallback callback, String title, String message, int id) {
        super(callback, title, message, id);
        this.callback = callback;
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.callbackId = id;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.messageLines = wrapMessage(Math.max(80, Math.min(360, this.width - 40)));

        int lineStep = this.fontRendererObj.FONT_HEIGHT + 3;
        int messageHeight = this.messageLines.isEmpty() ? 0 : this.messageLines.size() * lineStep - 3;
        int totalHeight = this.fontRendererObj.FONT_HEIGHT + 14 + messageHeight + 18 + BUTTON_HEIGHT;
        this.titleY = Math.max(18, (this.height - totalHeight) / 2);
        this.messageY = this.titleY + this.fontRendererObj.FONT_HEIGHT + 14;
        int buttonY = Math.min(this.height - BUTTON_HEIGHT - 10, this.messageY + messageHeight + 18);

        int groupWidth = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int left = (this.width - groupWidth) / 2;
        this.buttonList.add(new GuiButton(0, left, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("gui.yes")));
        this.buttonList.add(new GuiButton(1, left + BUTTON_WIDTH + BUTTON_GAP, buttonY,
                BUTTON_WIDTH, BUTTON_HEIGHT, I18n.format("gui.no")));
    }

    private List<String> wrapMessage(int maxWidth) {
        if (this.message.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<String> lines = new ArrayList<String>();
        String[] paragraphs = this.message.split("\\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("");
            } else {
                lines.addAll(this.fontRendererObj.listFormattedStringToWidth(paragraph, maxWidth));
            }
        }
        return lines;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, this.title, this.width / 2, this.titleY, 0xFFFFFF);
        int y = this.messageY;
        for (String line : this.messageLines) {
            this.drawCenteredString(this.fontRendererObj, line, this.width / 2, y, 0xFFFFFF);
            y += this.fontRendererObj.FONT_HEIGHT + 3;
        }
        for (GuiButton button : this.buttonList) {
            button.drawButton(this.mc, mouseX, mouseY);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (this.callback != null && (button.id == 0 || button.id == 1)) {
            this.callback.confirmClicked(button.id == 0, this.callbackId);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            if (this.callback != null) {
                this.callback.confirmClicked(false, this.callbackId);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
