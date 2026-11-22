package me.napim.gomod.gui;

import me.napim.gomod.features.WaterClarity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

/**
 * Water clarity: see into water from above by adjusting water opacity.
 */
public class WaterClaritySettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 220;
    private static final int ROW = 24;
    private static final int GAP = 8;

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private GuiButton backButton;
    private WaterClaritySlider claritySlider;
    private GuiButton selectedButton;

    public WaterClaritySettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int contentH = 16 + (BUTTON_HEIGHT + GAP) * 2 + ROW + BUTTON_HEIGHT + 12;
        int top = Math.max(8, (sh - contentH) / 2);

        int y = top + 16;
        toggleButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getToggleText());
        y += BUTTON_HEIGHT + GAP;
        claritySlider = new WaterClaritySlider(101, cx - BUTTON_WIDTH / 2, y,
                BUTTON_WIDTH, WaterClarity.getClarityAmount());
        y += BUTTON_HEIGHT + ROW + 4;
        backButton = new GuiButton(99, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(claritySlider);
        buttonList.add(backButton);
    }

    private String getToggleText() {
        return EnumChatFormatting.GOLD + "Water clarity: "
                + (WaterClarity.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        int cx = width / 2;
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Water clarity";
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, title, cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, WaterClaritySettingsGUI.class);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1:
                WaterClarity.setEnabled(!WaterClarity.isEnabled());
                toggleButton.displayString = getToggleText();
                WaterClarity.reloadChunkMeshesForSurfaceClarity();
                break;
            case 99:
                mc.displayGuiScreen(parent);
                if (parent instanceof MiscellaneousGUI) {
                    ((MiscellaneousGUI) parent).refreshWaterClarityButtonLabel();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (claritySlider.mousePressed(mc, mouseX, mouseY)) {
            selectedButton = claritySlider;
            WaterClarity.setClarityAmount(claritySlider.getValue());
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (selectedButton instanceof WaterClaritySlider) {
            WaterClaritySlider slider = (WaterClaritySlider) selectedButton;
            slider.mouseDragged(mc, mouseX, mouseY);
            WaterClarity.setClarityAmount(slider.getValue());
            WaterClarity.reloadChunkMeshesForSurfaceClarity();
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        if (selectedButton instanceof WaterClaritySlider) {
            WaterClarity.setClarityAmount(((WaterClaritySlider) selectedButton).getValue());
            WaterClarity.reloadChunkMeshesForSurfaceClarity();
        }
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0) {
            selectedButton = null;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

final class WaterClaritySlider extends GuiButton {

    private static final float MIN = 0.0f;
    private static final float MAX = 1.0f;

    private float currentValue;
    private float sliderValue;
    private boolean dragging;

    WaterClaritySlider(int id, int x, int y, int width, float current) {
        super(id, x, y, width, 20, "");
        this.currentValue = Math.max(MIN, Math.min(MAX, current));
        this.sliderValue = (this.currentValue - MIN) / (MAX - MIN);
        updateLabel();
    }

    private void updateLabel() {
        this.displayString = "Water opacity: " + WaterClarity.getSurfaceOpacityPercent(currentValue) + "%";
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(buttonTextures);
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        int v = 46;
        int half = this.width / 2;
        this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
        this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);

        int knobX = this.xPosition + (int) (this.sliderValue * (this.width - 8));
        this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
        this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);

        boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
        int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
        int textX = this.xPosition + (this.width - textWidth) / 2;
        int textY = this.yPosition + (this.height - 8) / 2;
        mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width
                && mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
            this.dragging = true;
            applyMouseX(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    @Override
    public void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && this.dragging) {
            applyMouseX(mouseX);
        }
    }

    private void applyMouseX(int mouseX) {
        int innerLeft = this.xPosition + 4;
        int innerRight = this.xPosition + this.width - 4;
        int clamped = Math.max(innerLeft, Math.min(innerRight, mouseX));
        float normalizedPos = (float) (clamped - innerLeft) / (float) (this.width - 8);
        this.sliderValue = Math.max(0.0f, Math.min(1.0f, normalizedPos));
        this.currentValue = MIN + this.sliderValue * (MAX - MIN);
        updateLabel();
    }

    float getValue() {
        return currentValue;
    }
}
