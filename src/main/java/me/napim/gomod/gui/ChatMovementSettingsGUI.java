package me.napim.gomod.gui;

import me.napim.gomod.features.ChatMovement;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.ForceGameChat;
import me.napim.gomod.features.GomodKeybinds;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Arrays;

/** Chat controls, movement-chat hotkey, camera-drag, and Blitz game-chat relay settings. */
public class ChatMovementSettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int ID_BACK = 0;
    private static final int ID_KEY = 2;
    private static final int ID_DRAG_LOOK = 3;
    private static final int ID_PASSTHROUGH_KEY = 4;
    private static final int ID_FORCE_GAME_CHATS = 5;

    private final GuiScreen parent;
    private GuiButton keyButton;
    private GuiButton dragLookButton;
    private GuiButton passthroughKeyButton;
    private GuiButton forceGameChatsButton;
    private boolean awaitingKey;
    private boolean awaitingPassthroughKey;

    public ChatMovementSettingsGUI(GuiScreen parent) {
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

        keyButton = new GuiButton(ID_KEY, cx - w / 2, cy - 54, w, h, getKeyText());
        passthroughKeyButton = new GuiButton(ID_PASSTHROUGH_KEY, cx - w / 2, cy - 28, w, h,
                getPassthroughKeyText());
        dragLookButton = new GuiButton(ID_DRAG_LOOK, cx - w / 2, cy - 2, w, h, getDragLookText());
        forceGameChatsButton = new GuiButton(ID_FORCE_GAME_CHATS, cx - w / 2, cy + 24, w, h,
                getForceGameChatsText());
        buttonList.add(keyButton);
        buttonList.add(passthroughKeyButton);
        buttonList.add(dragLookButton);
        buttonList.add(forceGameChatsButton);
        buttonList.add(new GuiButton(ID_BACK, cx - w / 2, cy + 54, w, h,
                EnumChatFormatting.GRAY + "Back"));

    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Chat Settings",
                sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHoverTooltips(mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, ChatMovementSettingsGUI.class);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_BACK) {
            awaitingKey = false;
            awaitingPassthroughKey = false;
            if (parent instanceof MiscellaneousGUI) {
                ((MiscellaneousGUI) parent).refreshChatMovementButtonLabel();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == ID_KEY) {
            awaitingKey = true;
            awaitingPassthroughKey = false;
            keyButton.displayString = EnumChatFormatting.YELLOW + "Press a key... (ESC = unbind)";
            return;
        }
        if (button.id == ID_PASSTHROUGH_KEY) {
            awaitingKey = false;
            awaitingPassthroughKey = true;
            passthroughKeyButton.displayString = EnumChatFormatting.YELLOW + "Press a modifier... (ESC = unbind)";
            return;
        }
        if (button.id == ID_DRAG_LOOK) {
            ChatMovement.setDragLookEnabled(!ChatMovement.isDragLookEnabled());
            dragLookButton.displayString = getDragLookText();
            return;
        }
        if (button.id == ID_FORCE_GAME_CHATS) {
            ForceGameChat.setEnabled(!ConfigManager.isForceGameChatsEnabled());
            forceGameChatsButton.displayString = getForceGameChatsText();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (awaitingKey || awaitingPassthroughKey) {
            boolean settingOpenKey = awaitingKey;
            awaitingKey = false;
            awaitingPassthroughKey = false;
            int chosen = keyCode == Keyboard.KEY_ESCAPE ? 0 : keyCode;
            if (settingOpenKey) {
                GomodKeybinds.applyChatMovementKeyCode(chosen);
            } else {
                ChatMovement.setPassthroughKeyCode(chosen);
            }
            keyButton.displayString = getKeyText();
            passthroughKeyButton.displayString = getPassthroughKeyText();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return awaitingKey || awaitingPassthroughKey;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getKeyText() {
        return EnumChatFormatting.WHITE + "Movement chat: "
                + GomodKeybinds.formatKeyName(ConfigManager.getChatMovementKeyCode());
    }

    private String getDragLookText() {
        return EnumChatFormatting.WHITE + "Right-click drag: "
                + (ChatMovement.isDragLookEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getPassthroughKeyText() {
        return EnumChatFormatting.WHITE + "In-chat gameplay modifier: "
                + GomodKeybinds.formatKeyName(ChatMovement.getPassthroughKeyCode());
    }

    private String getForceGameChatsText() {
        return EnumChatFormatting.WHITE + "Force game chats: "
                + (ConfigManager.isForceGameChatsEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private void drawHoverTooltips(int mouseX, int mouseY) {
        if (isHovering(keyButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Movement chat",
                    EnumChatFormatting.GRAY + "Opens chat while preserving the movement",
                    EnumChatFormatting.GRAY + "you were doing until chat is closed"
            ), mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isHovering(dragLookButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Lets you move your cursor while in Chat",
                    EnumChatFormatting.GRAY + "by dragging the screen while holding right-click"
            ), mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isHovering(passthroughKeyButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "In-chat gameplay modifier",
                    EnumChatFormatting.GRAY + "Hold it to use WASD, jump, sprint, sneak, perspective",
                    EnumChatFormatting.GRAY + "and TAB without typing those keys into Chat"
            ), mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isHovering(forceGameChatsButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Force game chats",
                    EnumChatFormatting.GRAY + "Shares public alive and spectator chat in this Blitz game.",
                    EnumChatFormatting.GRAY + "Needs another gomod user on the other side with this ON."
            ), mouseX, Math.max(0, mouseY - 12));
        }
    }

    private static boolean isHovering(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }
}
