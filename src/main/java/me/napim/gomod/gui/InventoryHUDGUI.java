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
 *  org.lwjgl.input.Mouse
 */
package me.napim.gomod.gui;

import me.napim.gomod.features.InventoryHUD;
import me.napim.gomod.gui.GomodGUI;
import me.napim.gomod.gui.GuiHeader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class InventoryHUDGUI
extends GuiScreen {
    private final GuiScreen returnParent;

    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;
    private static final int SLIDER_HEIGHT = 20;
    private static final int POS_FIELD_TOTAL_W = 120;
    private static final int POS_FIELD_GAP = 4;
    private static final int POS_FIELD_BOX_W = (POS_FIELD_TOTAL_W - POS_FIELD_GAP) / 2;
    private GuiButton toggleButton;
    private GuiButton showEmptyButton;
    private GuiButton forceButton;
    private GuiButton editPositionHudButton;
    private GuiButton resetPositionButton;
    private GuiButton backButton;
    private int opacitySliderX;
    private int opacitySliderY;
    private int scaleSliderX;
    private int scaleSliderY;
    private int posXInputX;
    private int posXInputY;
    private final HudPositionFieldPair positionFields = new HudPositionFieldPair(50, 51,
            InventoryHUD::setCustomPosition);

    public InventoryHUDGUI() {
        this(null);
    }

    public InventoryHUDGUI(GuiScreen returnParent) {
        this.returnParent = returnParent;
    }
    private float scale = 2.0f;
    private int opacity = 100;
    private HudBackgroundOpacityRow bgOpacityRow;
    private boolean isDraggingScale = false;
    private boolean isDraggingOpacity = false;
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private static final int SLIDER_W = 120;
    private static final int SLIDER_H = 20;
    private static final HudSliderStyle SCALE_SLIDER_STYLE = HudSliderStyle.VANILLA_KNOB;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public void initGui() {
        super.initGui();
        ScaledResolution scaledResolution = new ScaledResolution(Minecraft.getMinecraft());
        int centerY = scaledResolution.getScaledHeight() / 2;
        this.loadCurrentValues();
        int rightColumnX = this.width / 2 + 80;
        int rowStartY = Math.max(90, centerY - 120);
        int buttonWidth = 120;
        int halfW = (buttonWidth - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        this.toggleButton = new GuiButton(0, rightColumnX, rowStartY, buttonWidth, 20, InventoryHUD.isEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        this.showEmptyButton = new GuiButton(1, rightColumnX, rowStartY + 25, buttonWidth, 20, InventoryHUD.isShowEmpty() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        this.forceButton = new GuiButton(2, rightColumnX, rowStartY + 50, buttonWidth, 20, InventoryHUD.isForce() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        this.posXInputX = rightColumnX;
        this.posXInputY = rowStartY + 75 + 20;
        this.positionFields.init(this.fontRendererObj, InventoryHUD.getCustomX(), InventoryHUD.getCustomY(),
                this.posXInputX, this.posXInputY);
        int posRowY = this.posXInputY + SPACING;
        this.editPositionHudButton = new GuiButton(4, rightColumnX, posRowY, halfW, 20, GuiHeader.HUD_EDITOR_EDIT_BUTTON);
        this.resetPositionButton = new GuiButton(3, rightColumnX + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX, posRowY, halfW, 20,
                GuiHeader.HUD_EDITOR_RESET_BUTTON);
        this.backButton = new GuiButton(99, this.width / 2 - 100, GuiHeader.backButtonY(this.height, 20), 200, 20,
                GuiHeader.moduleSettingsBackButtonLabel(this.returnParent));
        this.scaleSliderX = rightColumnX;
        this.scaleSliderY = posRowY + SPACING + 20;
        this.opacitySliderX = rightColumnX;
        this.opacitySliderY = this.scaleSliderY + 25;
        this.buttonList.add(this.toggleButton);
        this.buttonList.add(this.showEmptyButton);
        this.buttonList.add(this.forceButton);
        this.buttonList.add(this.editPositionHudButton);
        this.buttonList.add(this.resetPositionButton);
        this.buttonList.add(this.backButton);
        this.scroll.reset();
        Keyboard.enableRepeatEvents(true);
        registerEnabledOverlays();
        bgOpacityRow = new HudBackgroundOpacityRow(InventoryHUD::getBackgroundOpacity,
                InventoryHUD::setBackgroundOpacity, InventoryHUD::getBackgroundColor,
                rgb -> InventoryHUD.setBackgroundColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
        bgOpacityRow.setInsetModule(HudOpacitySliderBoxInsets.Module.DEFAULT);
        bgOpacityRow.syncPickerFromConfig();
    }

    private void registerEnabledOverlays() {
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return InventoryHUD.isEnabled();
            }
        });
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    private void loadCurrentValues() {
        this.scale = InventoryHUD.getScale();
        this.opacity = InventoryHUD.getBackgroundOpacity();
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int rowStartY;
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft((Gui)this);
        String title = EnumChatFormatting.AQUA.toString() + EnumChatFormatting.BOLD + "Inventory HUD Settings";
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, title, this.width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        this.scroll.tick();
        int labelX = this.width / 2 - 200;
        int baseRowStartY = Math.max(90, this.height / 2 - 120);
        int scrollOff = this.scroll.getOffset();
        this.toggleButton.yPosition = rowStartY = baseRowStartY - scrollOff;
        this.showEmptyButton.yPosition = rowStartY + 25;
        this.forceButton.yPosition = rowStartY + 50;
        int halfW = (120 - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        int posBtnY = this.posXInputY - scrollOff + SPACING;
        this.editPositionHudButton.xPosition = this.posXInputX;
        this.editPositionHudButton.yPosition = posBtnY;
        this.editPositionHudButton.width = halfW;
        this.resetPositionButton.xPosition = this.posXInputX + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        this.resetPositionButton.yPosition = posBtnY;
        this.resetPositionButton.width = halfW;
        int contentBottom = Math.max(Math.max(this.posXInputY + SPACING + 20, this.opacitySliderY + 20),
                this.scaleSliderY + 20);
        this.scroll.updateBounds(baseRowStartY, contentBottom, baseRowStartY,
                GuiHeader.contentBottomAboveBackBar(this.height, 20, 2));
        int fadeTop = this.scroll.getVisibleTop();
        int fadeBottom = this.scroll.getVisibleBottom();
        int fadeMax = this.scroll.getMaxScroll();
        GuiScrollFade.drawLabel(this.fontRendererObj, "Inventory HUD", labelX, rowStartY, 6, 20,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(this.fontRendererObj, "Show when inventory is empty", labelX, rowStartY + 25, 6, 20,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(this.fontRendererObj, "Show while a GUI is open", labelX, rowStartY + 50, 6, 20,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(this.fontRendererObj, "Position X,Y:", labelX, this.posXInputY - scrollOff + 4,
                0xFFFFFF, fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(this.fontRendererObj, "Edit Position:", labelX, this.posXInputY - scrollOff + SPACING, 6,
                20, fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(this.fontRendererObj, "Scale", labelX, this.scaleSliderY - scrollOff + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(this.fontRendererObj, "Background Opacity", labelX, this.opacitySliderY - scrollOff + 4,
                0xFFFFFF, fadeTop, fadeBottom, scrollOff, fadeMax);
        this.positionFields.setScrollOffset(scrollOff);
        this.positionFields.draw();
        this.drawStyledSlider(this.scaleSliderX, this.scaleSliderY - scrollOff, this.scaleNormalized(),
                0xFF4CAF50, "Scale: " + String.format("%.1f", Float.valueOf(this.scale)), SCALE_SLIDER_STYLE);
        this.opacity = InventoryHUD.getBackgroundOpacity();
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.setBounds(this.opacitySliderX, this.opacitySliderY - scrollOff, SLIDER_W, SLIDER_H);
            this.bgOpacityRow.drawSlider(this.fontRendererObj, "Opacity: " + this.opacity);
        }
        this.scroll.drawScrollbar(this.width, mouseY);
        drawHudPreviewOnTop();
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.drawPickerButton(this.mc, mouseX, mouseY);
        }
        this.scroll.drawFadedButtonsExcluding(this.mc, this.fontRendererObj, this.buttonList, mouseX, mouseY, 99);
        enabledOverlays.drawAllFaded(fadeTop, fadeBottom, scrollOff, fadeMax);
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.drawPickerOverlay(this.mc, mouseX, mouseY, partialTicks);
        }
        if (this.backButton != null && this.backButton.visible) {
            this.backButton.drawButton(this.mc, mouseX, mouseY);
        }
        GomodSearchHighlight.draw(this.buttonList, InventoryHUDGUI.class);
    }

    private void drawHudPreviewOnTop() {
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        InventoryHUD.renderOnInventorySettingsGui();
        GlStateManager.popMatrix();
    }

    private float scaleNormalized() {
        return (this.scale - 0.5f) / 3.5f;
    }

    private void drawStyledSlider(int x, int y, float normalized, int accentArgb, String label, HudSliderStyle style) {
        drawStyledSlider(x, y, normalized, accentArgb, label, style, null);
    }

    private void drawStyledSlider(int x, int y, float normalized, int accentArgb, String label, HudSliderStyle style,
            HudOpacitySliderBoxInsets.Module insetModule) {
        style.draw(this.fontRendererObj, x, y, SLIDER_W, SLIDER_H, normalized, accentArgb, label, insetModule);
    }

    private static boolean hitSlider(int mouseX, int mouseY, int sliderX, int sliderY, int scrollOff) {
        int y = sliderY - scrollOff;
        return mouseX >= sliderX && mouseX <= sliderX + SLIDER_W && mouseY >= y && mouseY <= y + SLIDER_H;
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (this.scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.positionFields.mouseClicked(mouseX, mouseY, mouseButton);
        if (hitSlider(mouseX, mouseY, this.scaleSliderX, this.scaleSliderY, this.scroll.getOffset())) {
            this.isDraggingScale = true;
            this.updateScaleFromMouse(mouseX);
        }
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.setBounds(this.opacitySliderX, this.opacitySliderY - this.scroll.getOffset(), SLIDER_W, SLIDER_H);
            if (this.bgOpacityRow.mouseClicked(this.mc, mouseX, mouseY, mouseButton)) {
                return;
            }
        }
    }

    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (this.isDraggingScale) {
            this.updateScaleFromMouse(mouseX);
        }
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.mouseClickMove(mouseX, mouseY);
        }
    }

    public void mouseReleased(int mouseX, int mouseY, int state) {
        this.scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
        this.isDraggingScale = false;
        this.isDraggingOpacity = false;
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.mouseReleased(mouseX, mouseY);
        }
    }

    private void updateScaleFromMouse(int mouseX) {
        float normalizedPos = SCALE_SLIDER_STYLE.normalizedFromMouseX(mouseX, this.scaleSliderX, SLIDER_W);
        float newScale = 0.5f + normalizedPos * 3.5f;
        if (Math.abs(newScale - this.scale) > 0.05f) {
            this.scale = newScale;
            InventoryHUD.setScale(this.scale);
        }
    }

    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        this.scroll.handleMouseWheel();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.positionFields.updateCursorCounter();
    }

    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (this.positionFields.keyTyped(typedChar, keyCode, () -> GomodMenuTransitions.closeToGame(this.mc))) {
            return;
        }
        if (keyCode == 1) {
            GomodMenuTransitions.closeToGame(this.mc);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: {
                InventoryHUD.toggle();
                boolean now = InventoryHUD.isEnabled();
                this.toggleButton.displayString = now ? EnumChatFormatting.GREEN + "Enabled"
                        : EnumChatFormatting.RED + "Disabled";
                enabledOverlays.onToggled("main", now);
                break;
            }
            case 1: {
                InventoryHUD.toggleShowEmpty();
                boolean now = InventoryHUD.isShowEmpty();
                this.showEmptyButton.displayString = now ? EnumChatFormatting.GREEN + "Enabled"
                        : EnumChatFormatting.RED + "Disabled";
                break;
            }
            case 2: {
                InventoryHUD.toggleForce();
                boolean now = InventoryHUD.isForce();
                this.forceButton.displayString = now ? EnumChatFormatting.GREEN + "Enabled"
                        : EnumChatFormatting.RED + "Disabled";
                break;
            }
            case 3: {
                InventoryHUD.resetPosition();
                this.positionFields.sync(InventoryHUD.getCustomX(), InventoryHUD.getCustomY());
                break;
            }
            case 4: {
                this.mc.displayGuiScreen(new GomodHudEditorGUI(this,
                        EnumChatFormatting.GRAY + "Back to Inventory HUD Settings"));
                break;
            }
            case 99: {
                GomodMenuTransitions.returnToParentOrMainMenu(this.mc, this.returnParent);
            }
        }
    }

    public boolean doesGuiPauseGame() {
        return false;
    }
}
