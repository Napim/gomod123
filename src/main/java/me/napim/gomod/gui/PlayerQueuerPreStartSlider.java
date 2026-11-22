package me.napim.gomod.gui;

import me.napim.gomod.features.PlayerQueuer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.EnumChatFormatting;

/**
 * Continuous 0–3s delay after Hypixel's "The game starts in 3 seconds!" before the TAB check (ping tuning).
 * Track uses vanilla button texture UVs (200-wide caps) like {@link net.minecraft.client.gui.GuiButton}.
 */
public class PlayerQueuerPreStartSlider extends GuiButton {
    private final float minValue;
    private final float maxValue;
    private float currentValue;
    private boolean dragging = false;

    public PlayerQueuerPreStartSlider(int id, int x, int y, int sliderWidth) {
        super(id, x, y, sliderWidth, 20, "");
        this.minValue = PlayerQueuer.TAB_CHECK_AFTER_3S_DELAY_MIN;
        this.maxValue = PlayerQueuer.TAB_CHECK_AFTER_3S_DELAY_MAX;
        this.currentValue = PlayerQueuer.getTabCheckDelayAfter3Seconds();
        updateDisplayString();
    }

    public float getValue() {
        return currentValue;
    }

    private void setCurrentValue(float value) {
        float v = Math.max(minValue, Math.min(maxValue, value));
        this.currentValue = v;
        updateDisplayString();
        PlayerQueuer.setTabCheckDelayAfter3Silent(v);
    }

    /** Short label so it stays inside narrow tracks (full description is drawn above the slider in the GUI). */
    private void updateDisplayString() {
        this.displayString = EnumChatFormatting.WHITE + String.format("%.2f", currentValue)
                + EnumChatFormatting.GRAY + "s";
    }

    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (this.visible) {
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int rowV = 46;
            int half = this.width / 2;
            this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, rowV, half, 20);
            this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, rowV, half, 20);

            float denom = maxValue - minValue;
            float normalized = denom <= 1e-6f ? 0f : (currentValue - minValue) / denom;
            int knobX = this.xPosition + (int) (normalized * (this.width - 8));
            if (knobX > this.xPosition + this.width - 8) {
                knobX = this.xPosition + this.width - 8;
            }
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);

            boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                    && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
            int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
            String s = this.displayString;
            int textWidth = mc.fontRendererObj.getStringWidth(s);
            int textX = this.xPosition + (this.width - textWidth) / 2;
            int textY = this.yPosition + (this.height - 8) / 2;
            mc.fontRendererObj.drawString(s, textX, textY, textColor);
        }
    }

    @Override
    public boolean mousePressed(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width
                && mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
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
    }
}
