/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.Gui
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.util.EnumChatFormatting
 */
package me.napim.gomod.gui;

import me.napim.gomod.features.KillCounter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

public class KillCounterHUDGUI
extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;
    private static final int SLIDER_HEIGHT = 20;
    private GuiButton toggleButton;
    private GuiButton resetButton;
    private GuiButton backButton;
    private int posXInputX;
    private int posXInputY;
    private int posYInputY;
    private GuiTextField posXField;
    private GuiTextField posYField;
    private float scale = 1.0f;
    private int opacity = 100;
    private int scaleSliderX;
    private int scaleSliderY;
    private int opacitySliderX;
    private int opacitySliderY;
    private boolean isDraggingScale = false;
    private boolean isDraggingOpacity = false;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private static final int SCROLL_STEP = 15;

    public void initGui() {
        super.initGui();
        ScaledResolution scaledResolution = new ScaledResolution(Minecraft.getMinecraft());
        int centerY = scaledResolution.getScaledHeight() / 2;
        this.loadCurrentValues();
        int rightColumnX = this.width / 2 + 80;
        int rowStartY = Math.max(90, centerY - 120);
        int buttonWidth = 120;
        this.toggleButton = new GuiButton(0, rightColumnX, rowStartY, buttonWidth, 20, KillCounter.isEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        this.resetButton = new GuiButton(1, rightColumnX, rowStartY + 25, buttonWidth, 20, "Reset Counts");
        this.posXInputX = rightColumnX;
        this.posXInputY = rowStartY + 50 + 20;
        this.posYInputY = this.posXInputY + 25;
        this.posXField = HudPositionFieldPair.createFullWidthField(this.fontRendererObj, 50, this.posXInputX,
                this.posXInputY, "10", true);
        this.posYField = HudPositionFieldPair.createFullWidthField(this.fontRendererObj, 51, this.posXInputX,
                this.posYInputY, "10", true);
        this.backButton = new GuiButton(99, this.width / 2 - 100, this.height - 32, 200, 20, EnumChatFormatting.GRAY + "Back to Menu");
        this.scaleSliderX = rightColumnX;
        this.scaleSliderY = this.posYInputY + 50 + 20;
        this.opacitySliderX = rightColumnX;
        this.opacitySliderY = this.scaleSliderY + 25;
        this.buttonList.add(this.toggleButton);
        this.buttonList.add(this.resetButton);
        this.buttonList.add(this.backButton);
        this.scrollOffset = 0;
        this.maxScroll = Math.max(0, this.opacitySliderY + 100 - this.height + 50);
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    private void loadCurrentValues() {
        this.scale = 1.0f;
        this.opacity = 100;
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, EnumChatFormatting.GOLD.toString() + EnumChatFormatting.BOLD + "TAB QoL HUD Settings", this.width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        int leftColumnX = this.width / 2 - 200;
        int rowStartY = Math.max(90, this.height / 2 - 120);
        this.fontRendererObj.drawString(EnumChatFormatting.WHITE + "Enable TAB QoL:", leftColumnX, rowStartY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString(EnumChatFormatting.WHITE + "Reset Kill Counts:", leftColumnX, rowStartY + 25 + 6, 0xFFFFFF);
        this.fontRendererObj.drawString(EnumChatFormatting.WHITE + "Position X:", leftColumnX, this.posXInputY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString(EnumChatFormatting.WHITE + "Position Y:", leftColumnX, this.posYInputY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString(EnumChatFormatting.WHITE + "Scale:", leftColumnX, this.scaleSliderY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString(EnumChatFormatting.GRAY + String.format("%.1f", Float.valueOf(this.scale)), this.scaleSliderX + 200, this.scaleSliderY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString(EnumChatFormatting.WHITE + "Opacity:", leftColumnX, this.opacitySliderY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString(EnumChatFormatting.GRAY + String.valueOf(this.opacity) + "%", this.opacitySliderX + 200, this.opacitySliderY + 6, 0xFFFFFF);
        HudPositionFieldPair.drawField(this.posXField, this.posXInputX, this.posXInputY,
                GuiHeader.HUD_POSITION_FIELDS_TOTAL_W);
        HudPositionFieldPair.drawField(this.posYField, this.posXInputX, this.posYInputY,
                GuiHeader.HUD_POSITION_FIELDS_TOTAL_W);
        this.drawSlider(this.scaleSliderX, this.scaleSliderY, this.scale, 0.5f, 3.0f, "Scale");
        this.drawSlider(this.opacitySliderX, this.opacitySliderY, this.opacity, 0.0f, 100.0f, "Opacity");
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawSlider(int x, int y, float value, float min, float max, String label) {
        int sliderWidth = 200;
        int sliderHeight = 20;
        Gui.drawRect((int)x, (int)y, (int)(x + sliderWidth), (int)(y + sliderHeight), (int)-12566464);
        Gui.drawRect((int)x, (int)y, (int)(x + sliderWidth), (int)(y + sliderHeight), (int)-8355712);
        float normalizedValue = (value - min) / (max - min);
        int sliderPos = (int)(normalizedValue * (float)(sliderWidth - 10));
        Gui.drawRect((int)(x + sliderPos), (int)y, (int)(x + sliderPos + 10), (int)(y + sliderHeight), (int)-10461088);
        Gui.drawRect((int)(x + sliderPos), (int)y, (int)(x + sliderPos + 10), (int)(y + sliderHeight), (int)-8355712);
    }

    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: {
                if (KillCounter.isEnabled()) {
                    KillCounter.stop();
                    this.toggleButton.displayString = EnumChatFormatting.RED + "Disabled";
                    break;
                }
                KillCounter.start();
                this.toggleButton.displayString = EnumChatFormatting.GREEN + "Enabled";
                break;
            }
            case 1: {
                KillCounter.reset();
                break;
            }
            case 99: {
                GomodMenuTransitions.closeToGame(this.mc);
            }
        }
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        HudPositionFieldPair.mouseClickedStacked(this.posXField, this.posXInputX, this.posXInputY,
                this.posYField, this.posYInputY, GuiHeader.HUD_POSITION_FIELDS_TOTAL_W,
                mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseX >= this.scaleSliderX && mouseX <= this.scaleSliderX + 200 && mouseY >= this.scaleSliderY && mouseY <= this.scaleSliderY + 20) {
            this.isDraggingScale = true;
        } else if (mouseX >= this.opacitySliderX && mouseX <= this.opacitySliderX + 200 && mouseY >= this.opacitySliderY && mouseY <= this.opacitySliderY + 20) {
            this.isDraggingOpacity = true;
        }
    }

    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        this.isDraggingScale = false;
        this.isDraggingOpacity = false;
    }

    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (HudPositionFieldPair.keyTyped(this.posXField, typedChar, keyCode, true, null, null)) {
            return;
        }
        if (HudPositionFieldPair.keyTyped(this.posYField, typedChar, keyCode, true, null, null)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        HudPositionFieldPair.updateCursorCounter(this.posXField);
        HudPositionFieldPair.updateCursorCounter(this.posYField);
    }
}
