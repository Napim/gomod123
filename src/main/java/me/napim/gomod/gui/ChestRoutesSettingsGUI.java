package me.napim.gomod.gui;

import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Chest routes settings: render toggle and editor/view keybinds.
 */
public class ChestRoutesSettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 26;
    private static final int BTN_W = 120;
    private static final int LABEL_COLUMN_OFFSET = 200;
    private static final int RIGHT_COLUMN_OFFSET = 80;
    private static final int MARGIN_ABOVE_BACK = 12;

    private static final int AWAIT_NONE = 0;
    private static final int AWAIT_FEET = 1;
    private static final int AWAIT_TOGGLE_RENDER = 2;
    private static final int AWAIT_UNDO = 3;

    private final ChestRoutesMapsGUI parent;
    private GuiButton renderButton;
    private GuiButton feetKeyButton;
    private GuiButton toggleRenderKeyButton;
    private GuiButton undoKeyButton;
    private GuiButton backButton;
    private int labelX;
    private int rowStartY;
    private int awaitingKeyTarget = AWAIT_NONE;

    public ChestRoutesSettingsGUI(ChestRoutesMapsGUI parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int titleBottom = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj);
        int stackH = 4 * ROW;
        rowStartY = titleBottom + Math.max(8, (backY - MARGIN_ABOVE_BACK - titleBottom - stackH) / 2);
        labelX = cx - LABEL_COLUMN_OFFSET;
        int rightX = cx + RIGHT_COLUMN_OFFSET;

        renderButton = new GuiButton(1, rightX, rowStartY, BTN_W, BUTTON_HEIGHT, getRenderButtonText());
        feetKeyButton = new GuiButton(2, rightX, rowStartY + ROW, BTN_W, BUTTON_HEIGHT, getFeetKeyButtonText());
        toggleRenderKeyButton = new GuiButton(4, rightX, rowStartY + ROW * 2, BTN_W, BUTTON_HEIGHT,
                getToggleRenderKeyButtonText());
        undoKeyButton = new GuiButton(5, rightX, rowStartY + ROW * 3, BTN_W, BUTTON_HEIGHT, getUndoKeyButtonText());
        backButton = new GuiButton(0, cx - 75, backY, 150, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(renderButton);
        buttonList.add(feetKeyButton);
        buttonList.add(toggleRenderKeyButton);
        buttonList.add(undoKeyButton);
        buttonList.add(backButton);
        refreshKeyButtonLabels();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + "Chest Routes Settings";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String sub = EnumChatFormatting.GRAY + "Rendering and keybinds";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, sub, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y,
                0xAAAAAA);

        drawRowLabel("Chest routes rendering", rowStartY);
        drawRowLabel("Place checkpoint at feet", rowStartY + ROW);
        drawRowLabel("Toggle routes view", rowStartY + ROW * 2);
        drawRowLabel("Undo draft key", rowStartY + ROW * 3);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawRowLabel(String text, int rowY) {
        fontRendererObj.drawString(text, labelX, rowY + 6, 0xFFFFFF);
    }

    private void refreshKeyButtonLabels() {
        if (feetKeyButton != null) {
            feetKeyButton.displayString = (awaitingKeyTarget == AWAIT_FEET)
                    ? EnumChatFormatting.WHITE + "Press a key..."
                    : getFeetKeyButtonText();
        }
        if (toggleRenderKeyButton != null) {
            toggleRenderKeyButton.displayString = (awaitingKeyTarget == AWAIT_TOGGLE_RENDER)
                    ? EnumChatFormatting.WHITE + "Press a key..."
                    : getToggleRenderKeyButtonText();
        }
        if (undoKeyButton != null) {
            undoKeyButton.displayString = (awaitingKeyTarget == AWAIT_UNDO)
                    ? EnumChatFormatting.WHITE + "Press a key..."
                    : getUndoKeyButtonText();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (mouseButton == 1 && awaitingKeyTarget != AWAIT_NONE) {
            awaitingKeyTarget = AWAIT_NONE;
            refreshKeyButtonLabels();
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            awaitingKeyTarget = AWAIT_NONE;
            parent.refreshFromDisk();
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            awaitingKeyTarget = AWAIT_NONE;
            refreshKeyButtonLabels();
            ConfigManager.setChestRoutesRenderEnabled(!ConfigManager.isChestRoutesRenderEnabled());
            renderButton.displayString = getRenderButtonText();
            ChestRouteManager.reloadStorageAndRefreshGlow();
            return;
        }
        if (button.id == 2) {
            awaitingKeyTarget = (awaitingKeyTarget == AWAIT_FEET) ? AWAIT_NONE : AWAIT_FEET;
            refreshKeyButtonLabels();
            return;
        }
        if (button.id == 4) {
            awaitingKeyTarget = (awaitingKeyTarget == AWAIT_TOGGLE_RENDER) ? AWAIT_NONE : AWAIT_TOGGLE_RENDER;
            refreshKeyButtonLabels();
            return;
        }
        if (button.id == 5) {
            awaitingKeyTarget = (awaitingKeyTarget == AWAIT_UNDO) ? AWAIT_NONE : AWAIT_UNDO;
            refreshKeyButtonLabels();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (awaitingKeyTarget != AWAIT_NONE) {
            int target = awaitingKeyTarget;
            awaitingKeyTarget = AWAIT_NONE;
            int bound = (keyCode == Keyboard.KEY_ESCAPE) ? 0 : keyCode;
            if (target == AWAIT_FEET) {
                ConfigManager.setChestRoutesPlaceFeetKeyCode(bound);
            } else if (target == AWAIT_TOGGLE_RENDER) {
                ConfigManager.setChestRoutesToggleKeyCode(bound);
            } else if (target == AWAIT_UNDO) {
                ConfigManager.setChestRoutesUndoKeyCode(bound);
            }
            GomodKeybinds.applyConfigBindings();
            refreshKeyButtonLabels();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return awaitingKeyTarget != AWAIT_NONE;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getRenderButtonText() {
        return ConfigManager.isChestRoutesRenderEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private String getFeetKeyButtonText() {
        return "Key: " + EnumChatFormatting.WHITE + formatKey(ConfigManager.getChestRoutesPlaceFeetKeyCode());
    }

    private String getToggleRenderKeyButtonText() {
        return "Key: " + EnumChatFormatting.WHITE + formatKey(ConfigManager.getChestRoutesToggleKeyCode());
    }

    private String getUndoKeyButtonText() {
        return "Key: " + EnumChatFormatting.WHITE + formatKey(ConfigManager.getChestRoutesUndoKeyCode());
    }

    private static String formatKey(int keyCode) {
        if (keyCode <= 0) {
            return "NONE";
        }
        String name = Keyboard.getKeyName(keyCode);
        return name == null || name.trim().isEmpty() ? "UNKNOWN" : name;
    }
}
