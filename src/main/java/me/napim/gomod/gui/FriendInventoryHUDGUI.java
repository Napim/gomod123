package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.FriendInventoryHUD;
import me.napim.gomod.shareinv.ShareInvConstants;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.features.InventoryHUD;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Settings for the ShareInv friend inventory overlay (same controls as {@link InventoryHUDGUI}).
 */
public class FriendInventoryHUDGUI extends GuiScreen {
    /** Space reserved at bottom for the fixed Back button + margin (scrollable content ends above this). */
    private static final int FIXED_BACK_ZONE_HEIGHT = 36;
    /** Vertical step between the five ShareInv stat toggles (slightly tighter than 25px to fit on Large GUI scale). */
    private static final int STAT_ROW_STEP = 22;
    private static final int POS_FIELD_TOTAL_W = 120;
    private static final int POS_FIELD_GAP = 4;
    private static final int POS_FIELD_BOX_W = (POS_FIELD_TOTAL_W - POS_FIELD_GAP) / 2;
    private static final int SPACING = 25;
    private static final int SLIDER_W = 120;
    private static final int SLIDER_H = 20;
    private static final HudSliderStyle SCALE_SLIDER_STYLE = HudSliderStyle.VANILLA_KNOB;
    private final GuiScreen parent;
    private GuiButton hudEnabledButton;
    private GuiButton forceButton;
    private GuiButton editPositionHudButton;
    private GuiButton resetPositionButton;
    private GuiButton showHealthButton;
    private GuiButton showXpButton;
    private GuiButton showHeldHighlightButton;
    private GuiButton showDirectionButton;
    private GuiButton directionVerticalButton;
    private GuiButton textShadowButton;
    private GuiButton helpButton;
    private GuiButton connectGomodButton;
    private GuiButton backButton;
    private int opacitySliderX;
    private int opacitySliderY;
    private int scaleSliderX;
    private int scaleSliderY;
    private int posXInputX;
    private int posXInputY;
    private final HudPositionFieldPair positionFields = new HudPositionFieldPair(50, 51,
            FriendInventoryHUD::setCustomPosition);
    private float scale = 1.0f;
    private int opacity = 110;
    private HudBackgroundOpacityRow bgOpacityRow;
    private boolean isDraggingScale = false;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    /** When false, Connect / ? are omitted (shown on {@link GomodServiceGUI} instead). */
    private final boolean showServiceActions;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public FriendInventoryHUDGUI(GuiScreen parent) {
        this(parent, true);
    }

    public FriendInventoryHUDGUI(GuiScreen parent, boolean showServiceActions) {
        this.parent = parent;
        this.showServiceActions = showServiceActions;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
        if (this.parent instanceof MiscellaneousGUI) {
            ((MiscellaneousGUI) this.parent).refreshGomodServiceButtonLabel();
        }
        if (this.parent instanceof GomodServiceGUI) {
            ((GomodServiceGUI) this.parent).refreshFriendHudRowText();
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution scaledResolution = new ScaledResolution(Minecraft.getMinecraft());
        int scaledH = scaledResolution.getScaledHeight();
        this.loadCurrentValues();
        int rightColumnX = this.width / 2 + 80;
        int baseRowStartY = friendSettingsContentTop(scaledH);
        int buttonWidth = 120;
        int halfW = (buttonWidth - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        int connectW = Math.max(buttonWidth, 180);
        int connectX = this.width - connectW - 20;
        this.connectGomodButton = null;
        this.helpButton = null;
        if (this.showServiceActions) {
            this.connectGomodButton = new GuiButton(28, connectX, 24, connectW, 20,
                    EnumChatFormatting.GREEN + "Connect to gomod services");
        }
        // Match Inventory HUD: no extra +28px gap under title — saves vertical space on Large GUI scale.
        this.hudEnabledButton = new GuiButton(1, rightColumnX, baseRowStartY, buttonWidth, 20,
                hudEnabledLabel(FriendInventoryHUD.isEnabled()));
        this.forceButton = new GuiButton(2, rightColumnX, baseRowStartY + 25, buttonWidth, 20,
                FriendInventoryHUD.isForce() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        int statY = baseRowStartY + 50;
        this.showHealthButton = new GuiButton(20, rightColumnX, statY, buttonWidth, 20,
                hudEnabledLabel(FriendInventoryHUD.isShowRemoteHealth()));
        this.showXpButton = new GuiButton(22, rightColumnX, statY + STAT_ROW_STEP, buttonWidth, 20,
                hudEnabledLabel(FriendInventoryHUD.isShowRemoteXp()));
        this.showDirectionButton = new GuiButton(26, rightColumnX, statY + STAT_ROW_STEP * 2, buttonWidth, 20,
                hudEnabledLabel(FriendInventoryHUD.isShowDirection()));
        this.directionVerticalButton = new GuiButton(27, rightColumnX, statY + STAT_ROW_STEP * 3, buttonWidth, 20,
                hudEnabledLabel(FriendInventoryHUD.isDirectionVertical()));
        this.showHeldHighlightButton = new GuiButton(25, rightColumnX, statY + STAT_ROW_STEP * 4, buttonWidth, 20,
                heldHotbarLabel(FriendInventoryHUD.isShowHeldHighlight()));
        this.textShadowButton = new GuiButton(31, rightColumnX, statY + STAT_ROW_STEP * 5, buttonWidth, 20,
                textShadowLabel(FriendInventoryHUD.isTextShadow()));
        this.posXInputX = rightColumnX;
        this.posXInputY = statY + STAT_ROW_STEP * 5 + 20 + 10;
        this.positionFields.init(this.fontRendererObj, FriendInventoryHUD.getCustomX(), FriendInventoryHUD.getCustomY(),
                this.posXInputX, this.posXInputY);
        int posRowY = this.posXInputY + SPACING;
        this.editPositionHudButton = new GuiButton(29, rightColumnX, posRowY, halfW, 20, GuiHeader.HUD_EDITOR_EDIT_BUTTON);
        this.resetPositionButton = new GuiButton(3, rightColumnX + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX, posRowY, halfW, 20,
                GuiHeader.HUD_EDITOR_RESET_BUTTON);
        if (this.showServiceActions) {
            this.helpButton = new GuiButton(24, this.width / 2 + 168, 24, 22, 20, EnumChatFormatting.GOLD + "?");
        }
        this.scaleSliderX = rightColumnX;
        this.scaleSliderY = posRowY + SPACING + 20;
        this.opacitySliderX = rightColumnX;
        this.opacitySliderY = this.scaleSliderY + 25;
        this.backButton = new GuiButton(99, this.width / 2 - 100, GuiHeader.backButtonY(this.height, 20), 200, 20,
                EnumChatFormatting.GRAY + "Back");
        if (this.connectGomodButton != null) {
            this.buttonList.add(this.connectGomodButton);
        }
        this.buttonList.add(this.hudEnabledButton);
        this.buttonList.add(this.forceButton);
        this.buttonList.add(this.showHealthButton);
        this.buttonList.add(this.showXpButton);
        this.buttonList.add(this.showHeldHighlightButton);
        this.buttonList.add(this.textShadowButton);
        this.buttonList.add(this.showDirectionButton);
        this.buttonList.add(this.directionVerticalButton);
        this.buttonList.add(this.editPositionHudButton);
        this.buttonList.add(this.resetPositionButton);
        if (this.helpButton != null) {
            this.buttonList.add(this.helpButton);
        }
        this.buttonList.add(this.backButton);
        this.scrollOffset = 0;
        this.computeMaxScroll();
        Keyboard.enableRepeatEvents(true);
        registerEnabledOverlays();
        bgOpacityRow = new HudBackgroundOpacityRow(FriendInventoryHUD::getBackgroundOpacity,
                FriendInventoryHUD::setBackgroundOpacity, FriendInventoryHUD::getBackgroundColor,
                FriendInventoryHUD::setBackgroundColor);
        bgOpacityRow.setInsetModule(HudOpacitySliderBoxInsets.Module.DEFAULT);
        bgOpacityRow.syncPickerFromConfig();
    }

    private void registerEnabledOverlays() {
        enabledOverlays.clear();
        enabledOverlays.trackModuleToggle("main", hudEnabledButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return FriendInventoryHUD.isEnabled();
            }
        });
    }

    private static String hudEnabledLabel(boolean on) {
        return on ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
    }

    private static String textShadowLabel(boolean on) {
        return on ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
    }

    private static String heldHotbarLabel(boolean on) {
        return on ? EnumChatFormatting.GREEN + "Shown" : EnumChatFormatting.RED + "Disabled";
    }

    /**
     * First row of the settings column: same vertical rule as {@link InventoryHUDGUI} ({@code height/2 - 120}), but a
     * lower floor (48 vs 90) so the extra ShareInv rows fit when the scaled GUI is short (e.g. GUI Scale: Large).
     */
    private static int friendSettingsContentTop(int scaledHeight) {
        return Math.max(48, scaledHeight / 2 - 120);
    }

    private void loadCurrentValues() {
        this.scale = FriendInventoryHUD.getScale();
        this.opacity = FriendInventoryHUD.getBackgroundOpacity();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int rowStartY;
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.AQUA.toString() + EnumChatFormatting.BOLD + "Friend Inventory HUD";
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, title, this.width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String sub = EnumChatFormatting.GRAY + "ShareInv remote inventory, armor, hotbar & held slot";
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, sub, this.width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        int labelX = this.width / 2 - 200;
        int baseRowStartY = friendSettingsContentTop(this.height);
        if (this.connectGomodButton != null) {
            this.connectGomodButton.xPosition = this.width - this.connectGomodButton.width - 20;
            this.connectGomodButton.yPosition = 24;
        }
        this.hudEnabledButton.yPosition = rowStartY = baseRowStartY - this.scrollOffset;
        this.forceButton.yPosition = rowStartY + 25;
        int statY = rowStartY + 50;
        this.showHealthButton.yPosition = statY;
        this.showXpButton.yPosition = statY + STAT_ROW_STEP;
        this.showDirectionButton.yPosition = statY + STAT_ROW_STEP * 2;
        this.directionVerticalButton.yPosition = statY + STAT_ROW_STEP * 3;
        this.showHeldHighlightButton.yPosition = statY + STAT_ROW_STEP * 4;
        if (this.textShadowButton != null) {
            this.textShadowButton.yPosition = statY + STAT_ROW_STEP * 5;
        }
        int halfW = (120 - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        int posBtnY = this.posXInputY - this.scrollOffset + SPACING;
        this.editPositionHudButton.xPosition = this.posXInputX;
        this.editPositionHudButton.yPosition = posBtnY;
        this.editPositionHudButton.width = halfW;
        this.resetPositionButton.xPosition = this.posXInputX + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        this.resetPositionButton.yPosition = posBtnY;
        this.resetPositionButton.width = halfW;
        this.fontRendererObj.drawString("Friend Inventory HUD", labelX, rowStartY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Show while a GUI is open", labelX, rowStartY + 25 + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Health display", labelX, statY + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("XP display", labelX, statY + STAT_ROW_STEP + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Tracker", labelX, statY + STAT_ROW_STEP * 2 + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Vertical distance", labelX, statY + STAT_ROW_STEP * 3 + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Held hotbar slot", labelX, statY + STAT_ROW_STEP * 4 + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Text shadow", labelX, statY + STAT_ROW_STEP * 5 + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Position X,Y:", labelX, this.posXInputY - this.scrollOffset + 4, 0xFFFFFF);
        this.fontRendererObj.drawString("Edit Position:", labelX, this.posXInputY - this.scrollOffset + SPACING + 6, 0xFFFFFF);
        this.fontRendererObj.drawString("Scale", labelX, this.scaleSliderY - this.scrollOffset + 4, 0xFFFFFF);
        this.fontRendererObj.drawString("Background Opacity", labelX, this.opacitySliderY - this.scrollOffset + 4, 0xFFFFFF);
        this.positionFields.setScrollOffset(this.scrollOffset);
        this.positionFields.draw();
        this.drawStyledSlider(this.scaleSliderX, this.scaleSliderY - this.scrollOffset, this.scaleNormalized(),
                0xFF4CAF50, "Scale: " + String.format("%.1f", this.scale), SCALE_SLIDER_STYLE);
        this.opacity = FriendInventoryHUD.getBackgroundOpacity();
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.setBounds(this.opacitySliderX, this.opacitySliderY - this.scrollOffset, SLIDER_W, SLIDER_H);
            this.bgOpacityRow.drawSlider(this.fontRendererObj, "Opacity: " + this.opacity);
        }
        this.computeMaxScroll();
        if (this.maxScroll > 0) {
            int trackX = this.width - 10;
            int trackWidth = 4;
            int contentTop = baseRowStartY;
            int contentBottom = Math.max(Math.max(this.posXInputY + SPACING + 20, this.opacitySliderY + 20), this.scaleSliderY + 20);
            int visibleTop = contentTop;
            int visibleBottom = this.height - FIXED_BACK_ZONE_HEIGHT;
            int trackTop = Math.max(visibleTop, 30);
            int trackBottom = Math.max(trackTop + 1, visibleBottom);
            int trackHeight = trackBottom - trackTop;
            int totalContentHeight = Math.max(1, contentBottom - contentTop);
            int visibleHeight = Math.max(1, visibleBottom - visibleTop);
            int thumbHeight = Math.max(20, (int) ((float) trackHeight * ((float) visibleHeight / (float) totalContentHeight)));
            thumbHeight = Math.min(thumbHeight, trackHeight);
            int maxThumbTravel = trackHeight - thumbHeight;
            int thumbY = trackTop + (this.maxScroll == 0 ? 0 : (int) ((float) maxThumbTravel * ((float) this.scrollOffset / (float) this.maxScroll)));
            Gui.drawRect(trackX, trackTop, trackX + trackWidth, trackBottom, 0x60000000);
            Gui.drawRect(trackX, thumbY, trackX + trackWidth, thumbY + thumbHeight, 0x90909090);
        }
        drawHudPreviewOnTop();
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.drawPickerButton(this.mc, mouseX, mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, FriendInventoryHUDGUI.class);
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.drawPickerOverlay(this.mc, mouseX, mouseY, partialTicks);
        }
        GuiHeader.redrawBackBarButtonsOnTop(this.mc, this.buttonList, mouseX, mouseY, this.height, 20);
    }

    private void drawHudPreviewOnTop() {
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        InventoryHUD.renderOnFriendInventorySettingsGui();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private float scaleNormalized() {
        return (this.scale - 0.5f) / 3.5f;
    }

    private void drawStyledSlider(int x, int y, float normalized, int accentArgb, String label, HudSliderStyle style) {
        style.draw(this.fontRendererObj, x, y, SLIDER_W, SLIDER_H, normalized, accentArgb, label, null);
    }

    private boolean hitSlider(int mouseX, int mouseY, int sliderX, int sliderY, int sliderW) {
        int y = sliderY - this.scrollOffset;
        return mouseX >= sliderX && mouseX <= sliderX + sliderW && mouseY >= y && mouseY <= y + SLIDER_H;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.positionFields.mouseClicked(mouseX, mouseY, mouseButton);
        if (hitSlider(mouseX, mouseY, this.scaleSliderX, this.scaleSliderY, SLIDER_W)) {
            this.isDraggingScale = true;
            this.updateScaleFromMouse(mouseX);
        }
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.setBounds(this.opacitySliderX, this.opacitySliderY - this.scrollOffset, SLIDER_W, SLIDER_H);
            if (this.bgOpacityRow.mouseClicked(this.mc, mouseX, mouseY, mouseButton)) {
                return;
            }
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (this.isDraggingScale) {
            this.updateScaleFromMouse(mouseX);
        }
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.mouseClickMove(mouseX, mouseY);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        this.isDraggingScale = false;
        if (this.bgOpacityRow != null) {
            this.bgOpacityRow.mouseReleased(mouseX, mouseY);
        }
    }

    private void updateScaleFromMouse(int mouseX) {
        float normalizedPos = SCALE_SLIDER_STYLE.normalizedFromMouseX(mouseX, this.scaleSliderX, SLIDER_W);
        float newScale = 0.5f + normalizedPos * 3.5f;
        if (Math.abs(newScale - this.scale) > 0.05f) {
            this.scale = newScale;
            FriendInventoryHUD.setScale(this.scale);
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0 && this.maxScroll > 0) {
            if (dWheel < 0) {
                this.scrollOffset = Math.min(this.scrollOffset + 15, this.maxScroll);
            } else if (dWheel > 0) {
                this.scrollOffset = Math.max(this.scrollOffset - 15, 0);
            }
        }
    }

    private void computeMaxScroll() {
        int contentBottom = Math.max(Math.max(this.posXInputY + SPACING + 20, this.opacitySliderY + 20), this.scaleSliderY + 20);
        int visibleBottom = this.height - FIXED_BACK_ZONE_HEIGHT;
        this.maxScroll = Math.max(0, contentBottom - visibleBottom);
        if (this.scrollOffset > this.maxScroll) {
            this.scrollOffset = this.maxScroll;
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.positionFields.updateCursorCounter();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (this.positionFields.keyTyped(typedChar, keyCode, () -> GomodMenuTransitions.closeToGame(this.mc))) {
            return;
        }
        if (keyCode == 1) {
            this.mc.displayGuiScreen(this.parent);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1: {
                FriendInventoryHUD.toggle();
                boolean now = FriendInventoryHUD.isEnabled();
                this.hudEnabledButton.displayString = hudEnabledLabel(now);
                enabledOverlays.onToggled("main", now);
                break;
            }
            case 2: {
                FriendInventoryHUD.toggleForce();
                boolean now = FriendInventoryHUD.isForce();
                this.forceButton.displayString = now
                        ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
                break;
            }
            case 3:
                FriendInventoryHUD.resetPosition();
                this.positionFields.sync(FriendInventoryHUD.getCustomX(), FriendInventoryHUD.getCustomY());
                break;
            case 29:
                this.mc.displayGuiScreen(new GomodHudEditorGUI(this,
                        EnumChatFormatting.GRAY + "Back to Friend Inventory HUD"));
                break;
            case 20: {
                FriendInventoryHUD.toggleShowRemoteHealth();
                this.showHealthButton.displayString = hudEnabledLabel(FriendInventoryHUD.isShowRemoteHealth());
                break;
            }
            case 22: {
                FriendInventoryHUD.toggleShowRemoteXp();
                this.showXpButton.displayString = hudEnabledLabel(FriendInventoryHUD.isShowRemoteXp());
                break;
            }
            case 25: {
                FriendInventoryHUD.toggleShowHeldHighlight();
                this.showHeldHighlightButton.displayString = heldHotbarLabel(FriendInventoryHUD.isShowHeldHighlight());
                break;
            }
            case 31: {
                FriendInventoryHUD.toggleTextShadow();
                this.textShadowButton.displayString = textShadowLabel(FriendInventoryHUD.isTextShadow());
                break;
            }
            case 26: {
                FriendInventoryHUD.toggleShowDirection();
                this.showDirectionButton.displayString = hudEnabledLabel(FriendInventoryHUD.isShowDirection());
                break;
            }
            case 27: {
                FriendInventoryHUD.toggleDirectionVertical();
                this.directionVerticalButton.displayString = hudEnabledLabel(FriendInventoryHUD.isDirectionVertical());
                break;
            }
            case 24:
                this.mc.displayGuiScreen(new FriendShareInvHelpGUI(this));
                break;
            case 28: {
                String relay = ShareInvConstants.publicRelayTrimmed();
                if (relay.isEmpty()) {
                    relay = ShareInvConstants.GOMOD_HOSTED_RELAY_WSS;
                }
                ConfigManager.setShareInvRelayUrl(relay);
                ShareInvManager.setWantConnected(true);
                ShareInvManager.connect();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "ShareInv: saved relay and connecting…");
                break;
            }
            case 99:
                this.mc.displayGuiScreen(this.parent);
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
