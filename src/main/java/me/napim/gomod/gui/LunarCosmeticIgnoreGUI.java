package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodPeerLunarFullAccess;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/** Local list of remote players whose Lunar cosmetic layers and companions should not render. */
public final class LunarCosmeticIgnoreGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_HEIGHT = 12;
    private static final int MAX_HIDDEN_PLAYERS = 50;

    private final GuiScreen parent;
    private GuiTextField playerNameField;
    private int listTop;
    private int listLeft;
    private int listRight;
    private int visibleRows;
    private int scrollOffset;
    private String feedback = "";
    private int feedbackColor = 0xFFFFFF;

    public LunarCosmeticIgnoreGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        super.initGui();

        ScaledResolution resolution = new ScaledResolution(mc);
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        int backY = GuiHeader.backButtonY(screenHeight, BUTTON_HEIGHT);
        boolean compact = screenHeight < 230;
        int controlWidth = Math.min(220, Math.max(120, screenWidth - 16));
        int centerX = screenWidth / 2;
        int fieldY = compact ? 44 : 65;
        int addY = fieldY + 22;

        playerNameField = new GuiTextField(1, fontRendererObj,
                centerX - controlWidth / 2, fieldY, controlWidth, BUTTON_HEIGHT);
        playerNameField.setMaxStringLength(16);
        playerNameField.setFocused(true);

        buttonList.add(new GuiButton(1, centerX - controlWidth / 2, addY,
                controlWidth, BUTTON_HEIGHT, EnumChatFormatting.GREEN + "Add player"));
        buttonList.add(new GuiButton(0, centerX - controlWidth / 2, backY,
                controlWidth, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back"));

        listTop = addY + 22;
        listLeft = centerX - controlWidth / 2;
        listRight = centerX + controlWidth / 2;
        visibleRows = Math.max(1, (backY - 8 - (listTop + 13)) / ROW_HEIGHT);
        clampScrollOffset();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        ScaledResolution resolution = new ScaledResolution(mc);
        int screenWidth = resolution.getScaledWidth();
        boolean compact = resolution.getScaledHeight() < 230;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Hide Lunar cosmetics",
                screenWidth, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GRAY + "Enter an IGN",
                screenWidth / 2, compact ? 32 : 52, 0xFFFFFF);
        if (playerNameField != null) {
            playerNameField.drawTextBox();
        }

        List<String> hiddenPlayers = ConfigManager.getLunarTweaksHiddenCosmeticPlayers();
        int headerY = listTop;
        String listHeader = feedback.isEmpty()
                ? EnumChatFormatting.GRAY + "Hidden locally: " + hiddenPlayers.size() + "/" + MAX_HIDDEN_PLAYERS
                        + " (Click a name to remove)"
                : feedback;
        drawCenteredString(fontRendererObj, listHeader, screenWidth / 2, headerY, feedbackColor);

        int firstRowY = headerY + 13;
        if (hiddenPlayers.isEmpty()) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.DARK_GRAY + "No players hidden.",
                    screenWidth / 2, firstRowY, 0xFFFFFF);
        } else {
            clampScrollOffset(hiddenPlayers.size());
            int end = Math.min(hiddenPlayers.size(), scrollOffset + visibleRows);
            for (int i = scrollOffset; i < end; i++) {
                int rowY = firstRowY + (i - scrollOffset) * ROW_HEIGHT;
                String label = EnumChatFormatting.YELLOW + hiddenPlayers.get(i)
                        + EnumChatFormatting.GRAY + "  (click to remove)";
                fontRendererObj.drawStringWithShadow(label, listLeft + 4, rowY, 0xFFFFFF);
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        } else if (button.id == 1) {
            addPlayer();
        }
    }

    private void addPlayer() {
        String rawName = playerNameField == null ? "" : playerNameField.getText();
        String name = ConfigManager.normalizeLunarTweaksIgnoredCosmeticPlayer(rawName);
        if (name.isEmpty()) {
            setFeedback(EnumChatFormatting.RED + "Enter a valid Minecraft username.", 0xFF5555);
            return;
        }
        if (mc != null && mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName())) {
            setFeedback(EnumChatFormatting.YELLOW + "This list filters other players, not your own cosmetics.", 0xFFAA00);
            return;
        }
        if (ConfigManager.isLunarTweaksHiddenCosmeticPlayer(name)) {
            setFeedback(EnumChatFormatting.YELLOW + name + " is already on the list.", 0xFFAA00);
            return;
        }
        if (ConfigManager.getLunarTweaksHiddenCosmeticPlayers().size() >= MAX_HIDDEN_PLAYERS) {
            setFeedback(EnumChatFormatting.RED + "The local list is full (50 players).", 0xFF5555);
            return;
        }
        if (ConfigManager.addLunarTweaksHiddenCosmeticPlayer(name)) {
            int refreshed = GomodPeerLunarFullAccess.refreshLocalLunarCosmeticsForPlayer(name);
            playerNameField.setText("");
            scrollOffset = Math.max(0,
                    ConfigManager.getLunarTweaksHiddenCosmeticPlayers().size() - visibleRows);
            setFeedback(refreshed > 0
                    ? EnumChatFormatting.GREEN + "Added " + name + "; nearby cosmetics refreshed."
                    : EnumChatFormatting.GREEN + "Added " + name + "; applies when they appear nearby.", 0x55FF55);
        } else {
            setFeedback(EnumChatFormatting.RED + "Could not add that player.", 0xFF5555);
        }
    }

    private void removePlayerAt(int mouseY) {
        List<String> hiddenPlayers = ConfigManager.getLunarTweaksHiddenCosmeticPlayers();
        int row = (mouseY - (listTop + 13)) / ROW_HEIGHT;
        int index = scrollOffset + row;
        if (row < 0 || row >= visibleRows || index < 0 || index >= hiddenPlayers.size()) {
            return;
        }
        String name = hiddenPlayers.get(index);
        if (ConfigManager.removeLunarTweaksHiddenCosmeticPlayer(name)) {
            int refreshed = GomodPeerLunarFullAccess.refreshLocalLunarCosmeticsForPlayer(name);
            setFeedback(refreshed > 0
                    ? EnumChatFormatting.GREEN + "Removed " + name + "; nearby companions refreshed."
                    : EnumChatFormatting.GREEN + "Removed " + name + "; applies on their next Lunar refresh.", 0x55FF55);
            clampScrollOffset();
        }
    }

    private void setFeedback(String text, int color) {
        feedback = text;
        feedbackColor = color;
    }

    private void clampScrollOffset() {
        clampScrollOffset(ConfigManager.getLunarTweaksHiddenCosmeticPlayers().size());
    }

    private void clampScrollOffset(int itemCount) {
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, itemCount - visibleRows)));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (playerNameField != null && playerNameField.textboxKeyTyped(typedChar, keyCode)) {
            feedback = "";
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            addPlayer();
            return;
        }
        if (keyCode == 1) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (playerNameField != null) {
            playerNameField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        int firstRowY = listTop + 13;
        if (mouseButton == 0 && mouseX >= listLeft && mouseX < listRight
                && mouseY >= firstRowY && mouseY < firstRowY + visibleRows * ROW_HEIGHT) {
            removePlayerAt(mouseY);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollOffset += wheel < 0 ? 1 : -1;
            clampScrollOffset();
        }
        super.handleMouseInput();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (playerNameField != null) {
            playerNameField.updateCursorCounter();
        }
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(playerNameField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
