package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.EnumChatFormatting;

public class SoloDelaySlider extends GuiButton {
    private float minValue;
    private float maxValue;
    private float currentValue;
    private String displayText;
    private boolean dragging = false;
    
    public SoloDelaySlider(int id, int x, int y, int sliderWidth, String displayText, float minValue, float maxValue, float currentValue) {
        super(id, x, y, sliderWidth, 20, "");
        this.displayText = displayText;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.currentValue = currentValue;
        updateDisplayString();
    }
    
    public float getCurrentValue() {
        return currentValue;
    }
    
    public float getValue() {
        return currentValue;
    }
    
    public void setCurrentValue(float value) {
        this.currentValue = Math.max(minValue, Math.min(maxValue, value));
        updateDisplayString();
    }
    
    private void updateDisplayString() {
        this.displayString = EnumChatFormatting.YELLOW + displayText + ": " + 
                           EnumChatFormatting.WHITE + String.format("%.2f", currentValue) + "s";
    }
    
    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (this.visible) {
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int v = 46; // dark background row
            int half = this.width / 2;
            this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
            this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);

            float normalized = (currentValue - minValue) / (maxValue - minValue);
            int knobX = this.xPosition + (int) (normalized * (this.width - 8));
            if (knobX > this.xPosition + this.width - 8) {
                knobX = this.xPosition + this.width - 8;
            }
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
            
            boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition && 
                              mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
            int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
            int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
            int textX = this.xPosition + (this.width - textWidth) / 2;
            int textY = this.yPosition + (this.height - 8) / 2;
            mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
        }
    }
    
    @Override
    public boolean mousePressed(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width &&
            mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
            this.dragging = true;
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }
    
    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }
    
    @Override
    public void mouseDragged(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && this.dragging) {
            updateFromMouse(mouseX);
        }
    }
    
    private void updateFromMouse(int mouseX) {
        float relativeX = mouseX - (this.xPosition + 4);
        float sliderWidth = this.width - 8;
        float normalizedValue = Math.max(0.0f, Math.min(1.0f, relativeX / sliderWidth));
        float newValue = minValue + (maxValue - minValue) * normalizedValue;
        setCurrentValue(newValue);
        ConfigManager.setAutoQueueSoloDelay(currentValue);
    }
}
