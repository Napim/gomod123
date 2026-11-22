package me.napim.gomod.gui;

import me.napim.gomod.features.HorseDonkeyTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Settings GUI for Horse tracker (distance HUD, food nametag, teleport alert).
 */
public class HorseDonkeySettingsGUI extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;
    private static final int SLIDER_HEIGHT = 20;
    /** Total width of the position field column (two compact boxes side by side). */
    private static final int POS_FIELD_TOTAL_W = 120;
    private static final int POS_FIELD_GAP = 4;
    private static final int POS_FIELD_BOX_W = (POS_FIELD_TOTAL_W - POS_FIELD_GAP) / 2;
    private static final int SLIDER_W = 120;
    private static final HudSliderStyle SCALE_SLIDER_STYLE = HudSliderStyle.VANILLA_KNOB;

    private final GuiScreen parent;

    private GuiButton distanceHudButton;
    private GuiButton teleportSoundButton;
    private GuiButton foodNametagButton;
    private GuiButton textShadowButton;
    private GuiButton colorSettingsButton;
    private GuiButton editPositionHudButton;
    private GuiButton resetPositionButton;
    private GuiButton backButton;

    private int posXInputX;
    private int posXInputY;
    private final HudPositionFieldPair positionFields = new HudPositionFieldPair(50, 51,
            HorseDonkeyTracker::setCustomPosition);
    private int scaleSliderX;
    private int scaleSliderY;
    private int opacitySliderX;
    private int opacitySliderY;

    private boolean draggingScale = false;
    private int opacity = 120;
    private float scale = 1.0f;
    private HudBackgroundOpacityRow bgOpacityRow;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    public HorseDonkeySettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerY = sr.getScaledHeight() / 2;
        int rightColumnX = width / 2 + 80;
        int rowStartY = Math.max(90, centerY - 90);
        int buttonWidth = 120;
        int halfW = (buttonWidth - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;

        loadCurrentValues();

        distanceHudButton = new GuiButton(0, rightColumnX, rowStartY, buttonWidth, BUTTON_HEIGHT, getDistanceHudText());
        colorSettingsButton = new GuiButton(6, rightColumnX, rowStartY + SPACING, buttonWidth, BUTTON_HEIGHT,
                EnumChatFormatting.GOLD + "Color settings" + EnumChatFormatting.GRAY + "…");
        teleportSoundButton = new GuiButton(1, rightColumnX, rowStartY + SPACING * 2, buttonWidth, BUTTON_HEIGHT,
                getTeleportSoundText());
        foodNametagButton = new GuiButton(5, rightColumnX, rowStartY + SPACING * 3, buttonWidth, BUTTON_HEIGHT,
                getFoodNametagText());
        textShadowButton = new GuiButton(7, rightColumnX, rowStartY + SPACING * 4, buttonWidth, BUTTON_HEIGHT,
                getTextShadowText());

        posXInputX = rightColumnX;
        posXInputY = rowStartY + SPACING * 5 + 20;
        positionFields.init(fontRendererObj, HorseDonkeyTracker.getPanelX(), HorseDonkeyTracker.getPanelY(),
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
                EnumChatFormatting.GRAY + "Back");

        buttonList.add(distanceHudButton);
        buttonList.add(teleportSoundButton);
        buttonList.add(foodNametagButton);
        buttonList.add(textShadowButton);
        buttonList.add(colorSettingsButton);
        buttonList.add(editPositionHudButton);
        buttonList.add(resetPositionButton);
        buttonList.add(backButton);
        Keyboard.enableRepeatEvents(true);
        registerEnabledOverlays();
        bgOpacityRow = new HudBackgroundOpacityRow(HorseDonkeyTracker::getBackgroundOpacity,
                HorseDonkeyTracker::setBackgroundOpacity, HorseDonkeyTracker::getBackgroundColor,
                HorseDonkeyTracker::setBackgroundColor);
        bgOpacityRow.setInsetModule(HudOpacitySliderBoxInsets.Module.DEFAULT);
        bgOpacityRow.syncPickerFromConfig();
        scrollToHighlightedButtonIfNeeded();
    }

    private void scrollToHighlightedButtonIfNeeded() {
        if (!GomodSearchHighlight.matchesGui(HorseDonkeySettingsGUI.class)) return;
        int pendingId = GomodSearchHighlight.getButtonId();
        if (pendingId < 0) return;
        for (GuiButton b : buttonList) {
            if (b == null || b.id != pendingId) continue;
            int baseRowStartY = Math.max(90, height / 2 - 90);
            int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
            int vh = Math.max(1, visibleBottom - baseRowStartY);
            int target = b.yPosition - baseRowStartY - vh / 2 + BUTTON_HEIGHT / 2;
            scroll.setOffsetClamped(Math.max(0, target));
            break;
        }
    }

    private void registerEnabledOverlays() {
        enabledOverlays.trackModuleToggle("distance", distanceHudButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return HorseDonkeyTracker.isDistanceHudEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("teleportSound", teleportSoundButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return HorseDonkeyTracker.isTeleportSoundEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("foodNametag", foodNametagButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return HorseDonkeyTracker.isFoodNametagEnabled();
            }
        });
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    private void loadCurrentValues() {
        opacity = HorseDonkeyTracker.getBackgroundOpacity();
        scale = HorseDonkeyTracker.getScale();
    }

    private void applyScrollLayout(int rowStartY) {
        int rx = width / 2 + 80;
        int buttonWidth = 120;
        distanceHudButton.xPosition = rx;
        distanceHudButton.yPosition = rowStartY;
        colorSettingsButton.xPosition = rx;
        colorSettingsButton.yPosition = rowStartY + SPACING;
        teleportSoundButton.xPosition = rx;
        teleportSoundButton.yPosition = rowStartY + SPACING * 2;
        foodNametagButton.xPosition = rx;
        foodNametagButton.yPosition = rowStartY + SPACING * 3;
        textShadowButton.xPosition = rx;
        textShadowButton.yPosition = rowStartY + SPACING * 4;
        posXInputX = rx;
        posXInputY = rowStartY + SPACING * 5 + 20;
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

        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Horse tracker"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        HorseDonkeyTracker.drawPreviewPanel(Minecraft.getMinecraft());

        int labelX = width / 2 - 200;
        int baseRowStartY = Math.max(90, height / 2 - 90);
        int scrollOff = scroll.getOffset();
        int rowStartY = baseRowStartY - scrollOff;
        applyScrollLayout(rowStartY);

        int contentBottom = opacitySliderY + SLIDER_HEIGHT + 20;
        scroll.updateBounds(baseRowStartY, contentBottom, baseRowStartY,
                GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10));
        int fadeTop = scroll.getVisibleTop();
        int fadeBottom = scroll.getVisibleBottom();
        int fadeMax = scroll.getMaxScroll();
        GuiScrollFade.drawLabel(fontRendererObj, "Distance HUD", labelX, rowStartY, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "HUD text colors", labelX, rowStartY + SPACING, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Teleportation sound", labelX, rowStartY + SPACING * 2, 6,
                BUTTON_HEIGHT, fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Required food nametag", labelX, rowStartY + SPACING * 3, 6,
                BUTTON_HEIGHT, fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Text shadow", labelX, rowStartY + SPACING * 4, 6, BUTTON_HEIGHT,
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
        opacity = HorseDonkeyTracker.getBackgroundOpacity();
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
        GomodSearchHighlight.draw(buttonList, HorseDonkeySettingsGUI.class);
        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerOverlay(mc, mouseX, mouseY, partialTicks);
        }
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        drawSettingTooltips(mouseX, mouseY);
    }

    private void drawSettingTooltips(int mouseX, int mouseY) {
        if (teleportSoundButton != null && isMouseOver(teleportSoundButton, mouseX, mouseY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.GRAY + "Plays a distinct sound for when your horse teleports to you"
            ), mouseX, mouseY);
            return;
        }
        if (foodNametagButton != null && isMouseOver(foodNametagButton, mouseX, mouseY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.GRAY + "Shows how much food the horse needs to fully heal"
            ), mouseX, mouseY);
        }
    }

    private static boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
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
            HorseDonkeyTracker.setScale(scale);
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
                boolean now = !HorseDonkeyTracker.isDistanceHudEnabled();
                HorseDonkeyTracker.setDistanceHudEnabled(now);
                distanceHudButton.displayString = getDistanceHudText();
                enabledOverlays.onToggled("distance", now);
                break;
            }
            case 1: {
                boolean now = !HorseDonkeyTracker.isTeleportSoundEnabled();
                HorseDonkeyTracker.setTeleportSoundEnabled(now);
                teleportSoundButton.displayString = getTeleportSoundText();
                enabledOverlays.onToggled("teleportSound", now);
                if (now && mc != null && mc.thePlayer != null) {
                    mc.thePlayer.playSound("mob.horse.leather", 1.0F, 0.5F);
                }
                break;
            }
            case 3:
                HorseDonkeyTracker.resetPosition();
                positionFields.sync(HorseDonkeyTracker.getPanelX(), HorseDonkeyTracker.getPanelY());
                break;
            case 4:
                mc.displayGuiScreen(new GomodHudEditorGUI(this,
                        EnumChatFormatting.GRAY + "Back to Horse tracker"));
                break;
            case 5: {
                boolean now = !HorseDonkeyTracker.isFoodNametagEnabled();
                HorseDonkeyTracker.setFoodNametagEnabled(now);
                foodNametagButton.displayString = getFoodNametagText();
                enabledOverlays.onToggled("foodNametag", now);
                break;
            }
            case 6:
                mc.displayGuiScreen(new HorseDonkeyColorsGUI(this));
                break;
            case 7: {
                HorseDonkeyTracker.toggleTextShadow();
                textShadowButton.displayString = getTextShadowText();
                break;
            }
            case 99:
                returnToParent();
                break;
            default:
                break;
        }
    }

    private void returnToParent() {
        if (parent instanceof MiscellaneousGUI) {
            ((MiscellaneousGUI) parent).refreshHorseDonkeyButtonLabel();
        }
        GomodMenuTransitions.returnToParentOrMainMenu(mc, parent);
    }

    private String getDistanceHudText() {
        return HorseDonkeyTracker.isDistanceHudEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private String getFoodNametagText() {
        return HorseDonkeyTracker.isFoodNametagEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private String getTeleportSoundText() {
        return HorseDonkeyTracker.isTeleportSoundEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private String getTextShadowText() {
        return HorseDonkeyTracker.isTextShadow()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
