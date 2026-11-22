package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzChallenges;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * HUD Editor module settings for the active Blitz Challenges progress overlay.
 */
public class BlitzChallengesHUDGUI extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;
    private static final int SLIDER_HEIGHT = 20;
    private static final int SLIDER_W = 120;
    private static final HudSliderStyle SCALE_SLIDER_STYLE = HudSliderStyle.VANILLA_KNOB;

    private final GuiScreen parent;

    private GuiButton toggleButton;
    private GuiButton textShadowButton;
    private GuiButton editPositionHudButton;
    private GuiButton resetPositionButton;
    private GuiButton backButton;

    private int posXInputX;
    private int posXInputY;
    private final HudPositionFieldPair positionFields = new HudPositionFieldPair(50, 51,
            BlitzChallenges::setCustomPosition);

    private int scaleSliderX;
    private int scaleSliderY;
    private int opacitySliderX;
    private int opacitySliderY;

    private boolean draggingScale = false;
    private int opacity = 144;
    private float scale = 1.0f;
    private HudBackgroundOpacityRow bgOpacityRow;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    public BlitzChallengesHUDGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerY = sr.getScaledHeight() / 2;
        int rightColumnX = width / 2 + 80;
        int rowStartY = Math.max(90, centerY - 70);
        int buttonWidth = 120;
        int halfW = (buttonWidth - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;

        loadCurrentValues();

        toggleButton = new GuiButton(0, rightColumnX, rowStartY, buttonWidth, BUTTON_HEIGHT, getToggleText());
        textShadowButton = new GuiButton(7, rightColumnX, rowStartY + SPACING, buttonWidth, BUTTON_HEIGHT,
                getTextShadowText());

        posXInputX = rightColumnX;
        posXInputY = rowStartY + SPACING * 2 + 20;
        positionFields.init(fontRendererObj, BlitzChallenges.getHudPosX(), BlitzChallenges.getHudPosY(),
                posXInputX, posXInputY);

        int posBtnRowY = posXInputY + SPACING;
        editPositionHudButton = new GuiButton(4, rightColumnX, posBtnRowY, halfW, BUTTON_HEIGHT,
                GuiHeader.HUD_EDITOR_EDIT_BUTTON);
        resetPositionButton = new GuiButton(3, rightColumnX + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX, posBtnRowY,
                halfW, BUTTON_HEIGHT, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        scaleSliderX = rightColumnX;
        scaleSliderY = posBtnRowY + SPACING + 20;
        opacitySliderX = rightColumnX;
        opacitySliderY = scaleSliderY + SPACING;

        backButton = new GuiButton(99, width / 2 - 100, GuiHeader.backButtonY(height, BUTTON_HEIGHT), 200, BUTTON_HEIGHT,
                GuiHeader.moduleSettingsBackButtonLabel(this.parent));

        buttonList.add(toggleButton);
        buttonList.add(textShadowButton);
        buttonList.add(editPositionHudButton);
        buttonList.add(resetPositionButton);
        buttonList.add(backButton);
        Keyboard.enableRepeatEvents(true);
        registerEnabledOverlays();
        bgOpacityRow = new HudBackgroundOpacityRow(BlitzChallenges::getBackgroundOpacity,
                BlitzChallenges::setBackgroundOpacity, BlitzChallenges::getBackgroundColor,
                BlitzChallenges::setBackgroundColor);
        bgOpacityRow.setInsetModule(HudOpacitySliderBoxInsets.Module.DEFAULT);
        bgOpacityRow.syncPickerFromConfig();
        scrollToHighlightedButtonIfNeeded();
    }

    private void scrollToHighlightedButtonIfNeeded() {
        if (!GomodSearchHighlight.matchesGui(BlitzChallengesHUDGUI.class)) {
            return;
        }
        int pendingId = GomodSearchHighlight.getButtonId();
        if (pendingId < 0) {
            return;
        }
        for (GuiButton b : buttonList) {
            if (b == null || b.id != pendingId) {
                continue;
            }
            int baseRowStartY = Math.max(90, height / 2 - 70);
            int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
            int vh = Math.max(1, visibleBottom - baseRowStartY);
            int target = b.yPosition - baseRowStartY - vh / 2 + BUTTON_HEIGHT / 2;
            scroll.setOffsetClamped(Math.max(0, target));
            break;
        }
    }

    private void registerEnabledOverlays() {
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return BlitzChallenges.isHudEnabled();
            }
        });
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    private void loadCurrentValues() {
        opacity = BlitzChallenges.getBackgroundOpacity();
        scale = BlitzChallenges.getHudScale();
    }

    private void applyScrollLayout(int rowStartY) {
        int rx = width / 2 + 80;
        int buttonWidth = 120;
        toggleButton.xPosition = rx;
        toggleButton.yPosition = rowStartY;
        textShadowButton.xPosition = rx;
        textShadowButton.yPosition = rowStartY + SPACING;
        posXInputX = rx;
        posXInputY = rowStartY + SPACING * 2 + 20;
        positionFields.syncLayout(posXInputX, posXInputY);
        int posBtnRowY = posXInputY + SPACING;
        int halfW = (buttonWidth - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        editPositionHudButton.xPosition = rx;
        editPositionHudButton.yPosition = posBtnRowY;
        editPositionHudButton.width = halfW;
        resetPositionButton.xPosition = rx + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        resetPositionButton.yPosition = posBtnRowY;
        resetPositionButton.width = halfW;
        scaleSliderX = rx;
        scaleSliderY = posBtnRowY + SPACING + 20;
        opacitySliderX = rx;
        opacitySliderY = scaleSliderY + SPACING;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        scroll.tick();

        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Blitz Challenges HUD";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        BlitzChallenges.drawPreviewPanel(Minecraft.getMinecraft());

        int labelX = width / 2 - 200;
        int baseRowStartY = Math.max(90, height / 2 - 70);
        int scrollOff = scroll.getOffset();
        int rowStartY = baseRowStartY - scrollOff;
        applyScrollLayout(rowStartY);

        int contentBottom = opacitySliderY + SLIDER_HEIGHT + 20;
        scroll.updateBounds(baseRowStartY, contentBottom, baseRowStartY,
                GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10));
        int fadeTop = scroll.getVisibleTop();
        int fadeBottom = scroll.getVisibleBottom();
        int fadeMax = scroll.getMaxScroll();
        GuiScrollFade.drawLabel(fontRendererObj, "HUD overlay", labelX, rowStartY, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Text shadow", labelX, rowStartY + SPACING, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Position X,Y:", labelX, posXInputY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Edit Position:", labelX, posXInputY + SPACING, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Scale", labelX, scaleSliderY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Background Opacity", labelX, opacitySliderY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);

        positionFields.setScrollOffset(scrollOff);
        positionFields.draw();
        drawScaleSlider(scaleSliderX, scaleSliderY, "Scale: " + String.format("%.1f", scale));
        opacity = BlitzChallenges.getBackgroundOpacity();
        if (bgOpacityRow != null) {
            bgOpacityRow.setBounds(opacitySliderX, opacitySliderY, SLIDER_W, SLIDER_HEIGHT);
            bgOpacityRow.drawSlider(fontRendererObj, "Opacity: " + opacity);
        }

        scroll.drawScrollbar(width, mouseY);
        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerButton(mc, mouseX, mouseY);
        }
        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 99);
        enabledOverlays.drawAllFaded(fadeTop, fadeBottom, scrollOff, fadeMax);
        GomodSearchHighlight.draw(buttonList, BlitzChallengesHUDGUI.class);
        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerOverlay(mc, mouseX, mouseY, partialTicks);
        }
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
    }

    private float scaleNormalized() {
        return (scale - 0.5f) / 3.5f;
    }

    private void drawScaleSlider(int x, int y, String label) {
        SCALE_SLIDER_STYLE.draw(fontRendererObj, x, y, SLIDER_W, SLIDER_HEIGHT, scaleNormalized(),
                0xFF4CAF50, label);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        positionFields.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseX >= scaleSliderX && mouseX <= scaleSliderX + SLIDER_W
                && mouseY >= scaleSliderY && mouseY <= scaleSliderY + SLIDER_HEIGHT) {
            draggingScale = true;
            updateScaleFromMouse(mouseX);
        }

        if (bgOpacityRow != null) {
            bgOpacityRow.setBounds(opacitySliderX, opacitySliderY, SLIDER_W, SLIDER_HEIGHT);
            if (bgOpacityRow.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
                return;
            }
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingScale) {
            updateScaleFromMouse(mouseX);
        }
        if (bgOpacityRow != null) {
            bgOpacityRow.mouseClickMove(mouseX, mouseY);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
        draggingScale = false;
        if (bgOpacityRow != null) {
            bgOpacityRow.mouseReleased(mouseX, mouseY);
        }
    }

    private void updateScaleFromMouse(int mouseX) {
        float normalizedPos = SCALE_SLIDER_STYLE.normalizedFromMouseX(mouseX, scaleSliderX, SLIDER_W);
        float newScale = 0.5f + normalizedPos * 3.5f;
        if (Math.abs(newScale - scale) > 0.05f) {
            scale = newScale;
            BlitzChallenges.setHudScale(scale);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        positionFields.updateCursorCounter();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (positionFields.keyTyped(typedChar, keyCode, this::returnToParent)) {
            return;
        }
        if (keyCode == 1) {
            returnToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: {
                boolean now = !BlitzChallenges.isHudEnabled();
                BlitzChallenges.setHudEnabled(now);
                toggleButton.displayString = getToggleText();
                enabledOverlays.onToggled("main", now);
                break;
            }
            case 3:
                BlitzChallenges.resetHudLayout();
                positionFields.sync(BlitzChallenges.getHudPosX(), BlitzChallenges.getHudPosY());
                scale = BlitzChallenges.getHudScale();
                opacity = BlitzChallenges.getBackgroundOpacity();
                if (bgOpacityRow != null) {
                    bgOpacityRow.syncPickerFromConfig();
                }
                break;
            case 4:
                mc.displayGuiScreen(new GomodHudEditorGUI(this,
                        EnumChatFormatting.GRAY + "Back to Blitz Challenges HUD Settings"));
                break;
            case 7:
                BlitzChallenges.toggleHudTextShadow();
                textShadowButton.displayString = getTextShadowText();
                break;
            case 99:
                returnToParent();
                break;
            default:
                break;
        }
    }

    private void returnToParent() {
        GomodMenuTransitions.returnToParentOrMainMenu(mc, parent);
    }

    private String getToggleText() {
        return BlitzChallenges.isHudEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private String getTextShadowText() {
        return BlitzChallenges.isHudTextShadow()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
