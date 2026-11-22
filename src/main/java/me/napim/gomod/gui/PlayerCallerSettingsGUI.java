package me.napim.gomod.gui;

import me.napim.gomod.features.GomodKeybinds;
import me.napim.gomod.features.PlayerCaller;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Player Caller: enable/disable + key bind (opened from Miscellaneous).
 */
public class PlayerCallerSettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private GuiButton keyButton;
    private GuiButton backButton;
    private boolean awaitingKey;

    public PlayerCallerSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;
        int w = 200;
        int h = 20;
        toggleButton = new GuiButton(1, cx - w / 2, cy - 30, w, h, getToggleText());
        keyButton = new GuiButton(2, cx - w / 2, cy, w, h, getKeyText());
        backButton = new GuiButton(0, cx - w / 2, cy + 35, w, h, EnumChatFormatting.GRAY + "Back");
        buttonList.add(toggleButton);
        buttonList.add(keyButton);
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Player caller" + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, PlayerCallerSettingsGUI.class);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            awaitingKey = false;
            if (parent instanceof MiscellaneousGUI) {
                ((MiscellaneousGUI) parent).refreshPlayerCallerButtonLabel();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            PlayerCaller.setEnabled(!PlayerCaller.isEnabled());
            toggleButton.displayString = getToggleText();
            return;
        }
        if (button.id == 2) {
            awaitingKey = true;
            keyButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (awaitingKey) {
            awaitingKey = false;
            if (keyCode == Keyboard.KEY_ESCAPE) {
                PlayerCaller.setKeyCode(0);
            } else {
                PlayerCaller.setKeyCode(keyCode);
            }
            GomodKeybinds.applyConfigBindings();
            keyButton.displayString = getKeyText();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return awaitingKey;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getToggleText() {
        return "Player Caller: " + (PlayerCaller.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getKeyText() {
        return "Key: " + EnumChatFormatting.WHITE + formatKey(PlayerCaller.getKeyCode());
    }

    private static String formatKey(int keyCode) {
        if (keyCode <= 0) {
            return "NONE";
        }
        String name = Keyboard.getKeyName(keyCode);
        return name == null || name.trim().isEmpty() ? "UNKNOWN" : name;
    }
}
