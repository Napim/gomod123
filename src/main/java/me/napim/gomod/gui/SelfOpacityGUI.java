package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import me.napim.gomod.features.SelfOpacity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Arrays;

/** Self-opacity settings using the standard label-left/control-right module layout. */
public class SelfOpacityGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int BUTTON_HEIGHT = 20;
    private static final int CONTROL_WIDTH = 120;
    private static final int ROW = 25;
    private static final int ROW_COUNT = 5;

    private final MiscellaneousGUI parent;
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private GuiButton toggleButton;
    private GuiButton disableDuringBlindnessButton;
    private GuiButton keybindButton;
    private GuiButton backButton;
    private MountOpacitySlider opacitySlider;
    private MountOpacitySlider firstPersonHandOpacitySlider;
    private GuiButton selectedButton;
    private boolean capturingKeybind;
    private int baseRowY;
    private int labelX;
    private int controlX;

    public SelfOpacityGUI(MiscellaneousGUI parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        labelX = width / 2 - 200;
        controlX = width / 2 + 80;
        int visibleTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj);
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
        int contentHeight = (ROW_COUNT - 1) * ROW + BUTTON_HEIGHT;
        baseRowY = visibleTop + Math.max(0, (visibleBottom - visibleTop - contentHeight) / 2);

        toggleButton = button(1, 0, getOnOff(SelfOpacity.isEnabled()));
        opacitySlider = new MountOpacitySlider(2, controlX, 0, CONTROL_WIDTH,
                "Opacity: ", SelfOpacity.getAlpha());
        firstPersonHandOpacitySlider = new MountOpacitySlider(8, controlX, 0, CONTROL_WIDTH,
                "Hand opacity:", SelfOpacity.getFirstPersonHandAlpha(), true);
        disableDuringBlindnessButton = button(3, 0, getYesNo(SelfOpacity.isDisableDuringBlindnessEnabled()));
        keybindButton = button(5, 0, getKeybindText());
        backButton = new GuiButton(99, width / 2 - 100,
                GuiHeader.backButtonY(height, BUTTON_HEIGHT), 200, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");
        capturingKeybind = false;

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(opacitySlider);
        buttonList.add(firstPersonHandOpacitySlider);
        buttonList.add(disableDuringBlindnessButton);
        buttonList.add(keybindButton);
        buttonList.add(backButton);
        updateSettingsControlState();
        applyLayout(0);
    }

    private GuiButton button(int id, int y, String text) {
        return new GuiButton(id, controlX, y, CONTROL_WIDTH, BUTTON_HEIGHT, text);
    }

    private String getOnOff(boolean enabled) {
        return enabled ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
    }

    private String getYesNo(boolean enabled) {
        return enabled ? EnumChatFormatting.GREEN + "YES" : EnumChatFormatting.RED + "NO";
    }

    private String getKeybindText() {
        int code = ConfigManager.getSelfOpacityKeyCode();
        if (code == 0) return EnumChatFormatting.WHITE + "NONE";
        if (code < 0) {
            int mouseButton = code <= -100 ? code + 100 : -(code + 1);
            return EnumChatFormatting.WHITE + "Mouse " + (mouseButton + 1);
        }
        String keyName = Keyboard.getKeyName(code);
        return EnumChatFormatting.WHITE
                + (keyName == null || keyName.trim().isEmpty() ? "UNKNOWN" : keyName);
    }

    private void applyLayout(int scrollOffset) {
        int y = baseRowY - scrollOffset;
        GuiButton[] rows = {toggleButton, opacitySlider, firstPersonHandOpacitySlider,
                disableDuringBlindnessButton, keybindButton};
        int visibleTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj);
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
        for (int i = 0; i < rows.length; i++) {
            GuiButton button = rows[i];
            button.xPosition = controlX;
            button.yPosition = y + i * ROW;
            button.width = CONTROL_WIDTH;
            button.visible = GuiScrollFade.intersectsVisible(button.yPosition, BUTTON_HEIGHT,
                    visibleTop, visibleBottom);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Self opacity",
                width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        boolean settingsEnabled = SelfOpacity.isEnabled();
        if (settingsEnabled) {
            scroll.tick();
        } else {
            scroll.cancelInteraction();
        }
        int visibleTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj);
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
        scroll.updateBounds(baseRowY, baseRowY + (ROW_COUNT - 1) * ROW + BUTTON_HEIGHT,
                visibleTop, visibleBottom);
        updateSettingsControlState();
        applyLayout(scroll.getOffset());

        if (capturingKeybind) keybindButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
        String[] labels = {"Self opacity", "Opacity", "First person hand", "Disable during blindness", "Toggle key"};
        GuiButton[] rows = {toggleButton, opacitySlider, firstPersonHandOpacitySlider,
                disableDuringBlindnessButton, keybindButton};
        for (int i = 0; i < labels.length; i++) {
            GuiScrollFade.drawLabel(fontRendererObj, labels[i], labelX, rows[i].yPosition, 6, BUTTON_HEIGHT,
                    visibleTop, visibleBottom, scroll.getOffset(), scroll.getMaxScroll());
        }

        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 99);
        if (settingsEnabled) {
            scroll.drawScrollbar(width, mouseY);
        }
        backButton.drawButton(mc, mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, SelfOpacityGUI.class);
        drawHoverTooltip(mouseX, mouseY);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (capturingKeybind) {
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        switch (button.id) {
            case 1:
                SelfOpacity.setEnabled(!SelfOpacity.isEnabled());
                toggleButton.displayString = getOnOff(SelfOpacity.isEnabled());
                updateSettingsControlState();
                refreshParent();
                break;
            case 3:
                SelfOpacity.setDisableDuringBlindnessEnabled(!SelfOpacity.isDisableDuringBlindnessEnabled());
                disableDuringBlindnessButton.displayString = getYesNo(SelfOpacity.isDisableDuringBlindnessEnabled());
                break;
            case 5:
                capturingKeybind = true;
                break;
            case 99:
                refreshParent();
                mc.displayGuiScreen(parent);
                break;
            default:
                break;
        }
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (isMouseOverRow(firstPersonHandOpacitySlider, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Controls both your hand and the item you are holding."),
                    mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isMouseOverRow(disableDuringBlindnessButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Disable during blindness",
                    EnumChatFormatting.GRAY + "Temporarily disables SelfOpacity while blindness is active.",
                    EnumChatFormatting.ITALIC + "Why use this?: Because nametags are easier to see through your",
                    EnumChatFormatting.ITALIC + "own player in F5 while you have blindness effect."),
                    mouseX, Math.max(0, mouseY - 12));
            return;
        }
    }

    private boolean isMouseOverRow(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    private void updateSettingsControlState() {
        boolean enabled = SelfOpacity.isEnabled();
        if (opacitySlider != null) opacitySlider.enabled = enabled;
        if (firstPersonHandOpacitySlider != null) firstPersonHandOpacitySlider.enabled = enabled;
        if (disableDuringBlindnessButton != null) disableDuringBlindnessButton.enabled = enabled;
        // The master toggle and keybind remain available so the feature can be re-enabled.
        if (toggleButton != null) toggleButton.enabled = true;
        if (keybindButton != null) keybindButton.enabled = true;
        // Disabling Self Opacity must not disable navigation out of its settings screen.
        if (backButton != null) backButton.enabled = true;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (SelfOpacity.isEnabled()) {
            scroll.handleMouseWheel();
        } else {
            scroll.cancelInteraction();
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (SelfOpacity.isEnabled() && scroll.mouseClicked(mouseX, mouseY, mouseButton)) return;
        if (!SelfOpacity.isEnabled()) scroll.cancelInteraction();
        if (capturingKeybind) {
            GomodKeybinds.applySelfOpacityKeyCode(mouseButton - 100);
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (SelfOpacity.isEnabled() && opacitySlider.visible
                && opacitySlider.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {
            selectedButton = opacitySlider;
            applyOpacityFromSlider();
        } else if (SelfOpacity.isEnabled() && firstPersonHandOpacitySlider.visible
                && firstPersonHandOpacitySlider.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {
            selectedButton = firstPersonHandOpacitySlider;
            applyFirstPersonHandOpacityFromSlider();
        }
    }

    private void applyOpacityFromSlider() {
        if (!SelfOpacity.isEnabled()) return;
        SelfOpacity.setAlpha(opacitySlider.getValue());
        refreshParent();
    }

    private void applyFirstPersonHandOpacityFromSlider() {
        if (!SelfOpacity.isEnabled()) return;
        SelfOpacity.setFirstPersonHandAlpha(firstPersonHandOpacitySlider.getValue());
        refreshParent();
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (!SelfOpacity.isEnabled()) {
            selectedButton = null;
            return;
        }
        if (selectedButton == opacitySlider) {
            opacitySlider.mouseDragged(Minecraft.getMinecraft(), mouseX, mouseY);
            applyOpacityFromSlider();
        } else if (selectedButton == firstPersonHandOpacitySlider) {
            firstPersonHandOpacitySlider.mouseDragged(Minecraft.getMinecraft(), mouseX, mouseY);
            applyFirstPersonHandOpacityFromSlider();
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
        if (SelfOpacity.isEnabled() && state == 0 && selectedButton == opacitySlider) applyOpacityFromSlider();
        if (SelfOpacity.isEnabled() && state == 0 && selectedButton == firstPersonHandOpacitySlider) {
            applyFirstPersonHandOpacityFromSlider();
        }
        selectedButton = null;
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (capturingKeybind) {
            GomodKeybinds.applySelfOpacityKeyCode(keyCode == Keyboard.KEY_ESCAPE ? 0 : keyCode);
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void refreshParent() {
        if (parent != null) parent.refreshSelfOpacityButtonLabel();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        refreshParent();
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return capturingKeybind;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
