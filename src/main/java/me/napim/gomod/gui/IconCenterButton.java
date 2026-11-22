package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiButton;

class IconCenterButton extends GuiButton {
    private final String prefix;
    private final String mainLabel;

    IconCenterButton(int buttonId, int x, int y, int widthIn, int heightIn, String prefix, String mainLabel) {
        super(buttonId, x, y, widthIn, heightIn, mainLabel);
        this.prefix = prefix;
        this.mainLabel = mainLabel;
    }

    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (this.visible) {
            String saved = this.displayString;
            this.displayString = "";
            super.drawButton(mc, mouseX, mouseY);
            this.displayString = saved;

            int textColor;
            if (!this.enabled) {
                textColor = 10526880;
            } else if (this.hovered) {
                textColor = 16777120;
            } else {
                textColor = 14737632;
            }

            String prefixToDraw = this.prefix == null ? "" : this.prefix;
            int textY = this.yPosition + (this.height - 8) / 2;
            int prefixX = this.xPosition + 4;
            mc.fontRendererObj.drawString(prefixToDraw, prefixX, textY, textColor, false);

            String labelToDraw = this.mainLabel == null ? "" : this.mainLabel;
            int labelWidth = mc.fontRendererObj.getStringWidth(labelToDraw);
            int labelX = this.xPosition + (this.width - labelWidth) / 2;
            mc.fontRendererObj.drawString(labelToDraw, labelX, textY, textColor, false);
        }
    }
}
