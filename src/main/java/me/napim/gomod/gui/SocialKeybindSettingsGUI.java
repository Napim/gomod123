package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Arrays;

/**
 * Social open hotkey — kept out of vanilla Controls so it does not steal Tab (player list).
 */
public class SocialKeybindSettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int ID_BACK = 0;
    private static final int ID_KEY = 1;
    private static final int ID_CTRL = 2;

    private final GuiScreen parent;
    private GuiButton keyButton;
    private GuiButton ctrlButton;
    private boolean awaitingKey;

    public SocialKeybindSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;
        int w = 220;
        int h = 20;
        keyButton = new GuiButton(ID_KEY, cx - w / 2, cy - 18, w, h, getKeyButtonText());
        ctrlButton = new GuiButton(ID_CTRL, cx - w / 2, cy + 8, w, h, getCtrlButtonText());
        buttonList.add(keyButton);
        buttonList.add(ctrlButton);
        buttonList.add(new GuiButton(ID_BACK, cx - w / 2, cy + 38, w, h, EnumChatFormatting.GRAY + "Back"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Social hotkey",
                sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.DARK_GRAY + "Effective: "
                        + EnumChatFormatting.WHITE + GomodKeybinds.formatSocialEffectiveBinding(),
                sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xAAAAAA);
        if (awaitingKey) {
            keyButton.displayString = EnumChatFormatting.YELLOW + "Press a key or Ctrl + key...";
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, SocialKeybindSettingsGUI.class);
        if (isHovering(keyButton, mouseX, mouseY) && !awaitingKey) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Open key",
                    EnumChatFormatting.GRAY + "Hold Ctrl while assigning to save a Ctrl + key combo.",
                    EnumChatFormatting.GRAY + "Press a key by itself to save a single-key bind.",
                    EnumChatFormatting.GRAY + "Not in Minecraft Controls — avoids",
                    EnumChatFormatting.GRAY + "stealing Tab from the player list."
            ), mouseX, mouseY - 8);
        }
        if (isHovering(ctrlButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Ctrl modifier",
                    EnumChatFormatting.GRAY + "When ON: hold Ctrl + your key to open Social.",
                    EnumChatFormatting.GRAY + "Tab / player-list keys always need Ctrl."
            ), mouseX, mouseY - 8);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_BACK) {
            awaitingKey = false;
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == ID_KEY) {
            awaitingKey = true;
            return;
        }
        if (button.id == ID_CTRL) {
            if (GomodKeybinds.isSocialKeyAlwaysCtrlModifier(ConfigManager.getGomodFriendsListKeyCode())) {
                return;
            }
            ConfigManager.setGomodSocialRequireCtrl(!ConfigManager.isGomodSocialRequireCtrl());
            ConfigManager.saveConfig();
            refreshButtons();
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (awaitingKey) {
            if (keyCode == Keyboard.KEY_LCONTROL || keyCode == Keyboard.KEY_RCONTROL) {
                // Keep capturing so the next non-modifier key can form Ctrl + key.
                return;
            }
            awaitingKey = false;
            int chosen = keyCode == Keyboard.KEY_ESCAPE ? 0 : keyCode;
            GomodKeybinds.applyGomodFriendsListKeyCode(chosen);
            boolean ctrlHeld = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                    || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            boolean requireCtrl = GomodKeybinds.isSocialKeyAlwaysCtrlModifier(chosen)
                    || ctrlHeld;
            ConfigManager.setGomodSocialRequireCtrl(requireCtrl);
            ConfigManager.saveConfig();
            refreshButtons();
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

    private void refreshButtons() {
        if (keyButton != null) {
            keyButton.displayString = getKeyButtonText();
        }
        if (ctrlButton != null) {
            ctrlButton.displayString = getCtrlButtonText();
        }
    }

    private String getKeyButtonText() {
        return EnumChatFormatting.GOLD + "Key: " + EnumChatFormatting.WHITE
                + GomodKeybinds.formatKeyName(ConfigManager.getGomodFriendsListKeyCode());
    }

    private String getCtrlButtonText() {
        int key = ConfigManager.getGomodFriendsListKeyCode();
        if (GomodKeybinds.isSocialKeyAlwaysCtrlModifier(key)) {
            return EnumChatFormatting.GOLD + "Ctrl: " + EnumChatFormatting.GREEN + "required"
                    + EnumChatFormatting.GRAY + " (Tab / player list)";
        }
        return EnumChatFormatting.GOLD + "Hold Ctrl: "
                + (ConfigManager.isGomodSocialRequireCtrl()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private static boolean isHovering(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && mouseX >= button.xPosition
                && mouseY >= button.yPosition
                && mouseX < button.xPosition + button.width
                && mouseY < button.yPosition + button.height;
    }
}
