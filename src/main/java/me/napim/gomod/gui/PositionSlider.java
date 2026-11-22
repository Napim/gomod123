package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.EnumChatFormatting;

public class PositionSlider extends GuiButton {
    private float minValue; // 0.0
    private float maxValue; // 0.8
    private int sliderValue; // 0-100 (internal slider value)
    private String displayText;
    private boolean dragging = false;
    
    public PositionSlider(int id, int x, int y, String displayText, float minValue, float maxValue, float currentValue) {
        super(id, x, y, 200, 20, "");
        this.displayText = displayText;
        this.minValue = minValue;
        this.maxValue = maxValue;
        // Convert actual value (0.0-0.8) to slider value (0-100)
        this.sliderValue = Math.round((currentValue - minValue) / (maxValue - minValue) * 100);
        updateDisplayString();
    }
    
    public float getCurrentValue() {
        // Convert slider value (0-100) back to actual value (0.0-0.8)
        return minValue + (sliderValue / 100.0f) * (maxValue - minValue);
    }
    
    public void setCurrentValue(float value) {
        // Convert actual value (0.0-0.8) to slider value (0-100)
        this.sliderValue = Math.round((value - minValue) / (maxValue - minValue) * 100);
        this.sliderValue = Math.max(0, Math.min(100, this.sliderValue));
        updateDisplayString();
    }
    
    private void updateDisplayString() {
        this.displayString = EnumChatFormatting.AQUA + displayText + ": " + 
                           EnumChatFormatting.WHITE + sliderValue; // Show 0-100 instead of decimal
    }
    
    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (this.visible) {
            // Draw vanilla button background
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int v = 46; // always use dark normal row
            int half = this.width / 2;
            this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
            this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);
            
            // Calculate slider position (0-100 scale)
            float sliderPos = sliderValue / 100.0f;
            int knobX = this.xPosition + (int)(sliderPos * (this.width - 8));
            
            // Draw slider knob
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
            
            // Text on top
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
            updateSliderValue(mouseX);
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
            updateSliderValue(mouseX);
        }
    }
    
    private void updateSliderValue(int mouseX) {
        float relativeX = mouseX - (this.xPosition + 4);
        float sliderWidth = this.width - 8;
        float normalizedValue = Math.max(0.0f, Math.min(1.0f, relativeX / sliderWidth));
        
        // Update slider value (0-100)
        this.sliderValue = Math.round(normalizedValue * 100);
        this.sliderValue = Math.max(0, Math.min(100, this.sliderValue));
        
        updateDisplayString();
        
        // Save to config using the actual value (0.0-0.8)
        float actualValue = getCurrentValue();
        ConfigManager.setReplayHealthDisplayYOffset(actualValue);
    }
}
