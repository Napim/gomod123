package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Chest routes settings: render toggle, feet-place keybind, and undo shortcut.
 */
public class ChestRoutesSettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 26;
    private static final int BTN_W = 120;
    private static final int LABEL_COLUMN_OFFSET = 200;
    private static final int RIGHT_COLUMN_OFFSET = 80;
    private static final int MARGIN_ABOVE_BACK = 12;

    private final ChestRoutesMapsGUI parent;
    private GuiButton renderButton;
    private GuiButton feetKeyButton;
    private GuiButton toggleRenderKeyButton;
    private GuiButton undoKeyButton;
    private GuiButton undoButton;
    private GuiButton backButton;
    private int labelX;
    private int rowStartY;
    private boolean awaitingFeetKey;
    private boolean awaitingToggleRenderKey;
    private boolean awaitingUndoKey;

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
        int stackH = 5 * ROW;
        rowStartY = titleBottom + Math.max(8, (backY - MARGIN_ABOVE_BACK - titleBottom - stackH) / 2);
        labelX = cx - LABEL_COLUMN_OFFSET;
        int rightX = cx + RIGHT_COLUMN_OFFSET;

        renderButton = new GuiButton(1, rightX, rowStartY, BTN_W, BUTTON_HEIGHT, getRenderButtonText());
        feetKeyButton = new GuiButton(2, rightX, rowStartY + ROW, BTN_W, BUTTON_HEIGHT, getFeetKeyButtonText());
        toggleRenderKeyButton = new GuiButton(4, rightX, rowStartY + ROW * 2, BTN_W, BUTTON_HEIGHT,
                getToggleRenderKeyButtonText());
        undoKeyButton = new GuiButton(5, rightX, rowStartY + ROW * 3, BTN_W, BUTTON_HEIGHT, getUndoKeyButtonText());
        undoButton = new GuiButton(3, rightX, rowStartY + ROW * 4, BTN_W, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "Undo");
        backButton = new GuiButton(0, cx - 100, backY, 200, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(renderButton);
        buttonList.add(feetKeyButton);
        buttonList.add(toggleRenderKeyButton);
        buttonList.add(undoKeyButton);
        buttonList.add(undoButton);
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + "Chest Routes Settings";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String sub = EnumChatFormatting.GRAY + "Rendering, keybinds, and draft undo";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, sub, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y,
                0xAAAAAA);

        drawRowLabel("Chest routes rendering", rowStartY);
        drawRowLabel("Place checkpoint at feet", rowStartY + ROW);
        drawRowLabel("Toggle routes view", rowStartY + ROW * 2);
        drawRowLabel("Undo draft key", rowStartY + ROW * 3);
        drawRowLabel("Undo last draft", rowStartY + ROW * 4);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawRowLabel(String text, int rowY) {
        fontRendererObj.drawString(text, labelX, rowY + 6, 0xFFFFFF);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            awaitingFeetKey = false;
            awaitingToggleRenderKey = false;
            awaitingUndoKey = false;
            parent.refreshFromDisk();
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            ConfigManager.setChestRoutesRenderEnabled(!ConfigManager.isChestRoutesRenderEnabled());
            renderButton.displayString = getRenderButtonText();
            ChestRouteManager.reloadStorageAndRefreshGlow();
            return;
        }
        if (button.id == 2) {
            awaitingFeetKey = true;
            feetKeyButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
            return;
        }
        if (button.id == 3) {
            String err = ChestRouteManager.undoDraft();
            if (err != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Undid last draft edit.");
            }
            return;
        }
        if (button.id == 4) {
            awaitingToggleRenderKey = true;
            toggleRenderKeyButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
            return;
        }
        if (button.id == 5) {
            awaitingUndoKey = true;
            undoKeyButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
            return;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (awaitingToggleRenderKey) {
            awaitingToggleRenderKey = false;
            if (keyCode == Keyboard.KEY_ESCAPE) {
                ConfigManager.setChestRoutesToggleKeyCode(0);
            } else {
                ConfigManager.setChestRoutesToggleKeyCode(keyCode);
            }
            toggleRenderKeyButton.displayString = getToggleRenderKeyButtonText();
            GomodKeybinds.applyConfigBindings();
            return;
        }
        if (awaitingUndoKey) {
            awaitingUndoKey = false;
            if (keyCode == Keyboard.KEY_ESCAPE) {
                ConfigManager.setChestRoutesUndoKeyCode(0);
            } else {
                ConfigManager.setChestRoutesUndoKeyCode(keyCode);
            }
            undoKeyButton.displayString = getUndoKeyButtonText();
            GomodKeybinds.applyConfigBindings();
            return;
        }
        if (awaitingFeetKey) {
            awaitingFeetKey = false;
            if (keyCode == Keyboard.KEY_ESCAPE) {
                ConfigManager.setChestRoutesPlaceFeetKeyCode(0);
            } else {
                ConfigManager.setChestRoutesPlaceFeetKeyCode(keyCode);
            }
            feetKeyButton.displayString = getFeetKeyButtonText();
            GomodKeybinds.applyConfigBindings();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return awaitingFeetKey || awaitingToggleRenderKey || awaitingUndoKey;
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
