package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
public final class BlitzEndGameScoreGUI extends GuiScreen {
    private final GuiScreen parent;
    private GuiButton masterButton;
    private GuiButton positionsButton;
    private GuiButton starButton;
    private GuiButton deathmatchButton;
    private GuiButton messageCountButton;
    private GuiButton gameDurationButton;
    private GuiButton mapPlayerCountButton;

    public BlitzEndGameScoreGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int w = 220;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 78;
        masterButton = new GuiButton(0, x, y, w, 20, masterText());
        mapPlayerCountButton = new GuiButton(6, x, y + 24, w, 20, mapPlayerCountText());
        gameDurationButton = new GuiButton(5, x, y + 48, w, 20, gameDurationText());
        positionsButton = new GuiButton(1, x, y + 72, w, 20, positionsText());
        starButton = new GuiButton(2, x, y + 96, w, 20, starText());
        deathmatchButton = new GuiButton(3, x, y + 120, w, 20, deathmatchText());
        messageCountButton = new GuiButton(4, x, y + 144, w, 20, messageCountText());
        this.buttonList.add(masterButton);
        this.buttonList.add(mapPlayerCountButton);
        this.buttonList.add(gameDurationButton);
        this.buttonList.add(positionsButton);
        this.buttonList.add(starButton);
        this.buttonList.add(deathmatchButton);
        this.buttonList.add(messageCountButton);
        this.buttonList.add(new GuiButton(7, x, this.height - 26, w, 20, "Done"));
        refreshButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 0:
                ConfigManager.setBlitzExtendedResultsEnabled(!ConfigManager.isBlitzExtendedResultsEnabled());
                refreshButtons();
                break;
            case 1:
                ConfigManager.setBlitzExtendedResultsMorePositionsEnabled(!ConfigManager.isBlitzExtendedResultsMorePositionsEnabled());
                refreshButtons();
                break;
            case 2:
                ConfigManager.setBlitzExtendedResultsStarInfoEnabled(!ConfigManager.isBlitzExtendedResultsStarInfoEnabled());
                refreshButtons();
                break;
            case 3:
                ConfigManager.setBlitzExtendedResultsDeathmatchPlayersEnabled(!ConfigManager.isBlitzExtendedResultsDeathmatchPlayersEnabled());
                refreshButtons();
                break;
            case 4:
                ConfigManager.setBlitzExtendedResultsMessageCountEnabled(
                        !ConfigManager.isBlitzExtendedResultsMessageCountEnabled());
                refreshButtons();
                break;
            case 5:
                ConfigManager.setBlitzExtendedResultsGameDurationEnabled(
                        !ConfigManager.isBlitzExtendedResultsGameDurationEnabled());
                refreshButtons();
                break;
            case 6:
                ConfigManager.setBlitzExtendedResultsMapPlayerCountEnabled(
                        !ConfigManager.isBlitzExtendedResultsMapPlayerCountEnabled());
                refreshButtons();
                break;
            case 7:
                closeToParent();
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            closeToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Detailed end-game score"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, title, this.width,
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, BlitzEndGameScoreGUI.class);
    }

    private void closeToParent() {
        if (parent instanceof MiscellaneousGUI) {
            ((MiscellaneousGUI) parent).refreshBlitzExtendedResultsButtonLabel();
        }
        this.mc.displayGuiScreen(parent);
    }

    private void refreshButtons() {
        if (masterButton != null) masterButton.displayString = masterText();
        if (positionsButton != null) positionsButton.displayString = positionsText();
        if (starButton != null) starButton.displayString = starText();
        if (deathmatchButton != null) deathmatchButton.displayString = deathmatchText();
        if (messageCountButton != null) messageCountButton.displayString = messageCountText();
        if (gameDurationButton != null) gameDurationButton.displayString = gameDurationText();
        if (mapPlayerCountButton != null) mapPlayerCountButton.displayString = mapPlayerCountText();
        boolean master = ConfigManager.isBlitzExtendedResultsEnabled();
        if (positionsButton != null) positionsButton.enabled = master;
        if (starButton != null) starButton.enabled = master;
        if (deathmatchButton != null) deathmatchButton.enabled = master;
        if (messageCountButton != null) messageCountButton.enabled = master;
        if (gameDurationButton != null) gameDurationButton.enabled = master;
        if (mapPlayerCountButton != null) mapPlayerCountButton.enabled = master;
    }

    private static String masterText() {
        return EnumChatFormatting.WHITE + "Detailed end-game score: "
                + (ConfigManager.isBlitzExtendedResultsEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled");
    }

    private static String positionsText() {
        return EnumChatFormatting.WHITE + "More positions: " + state(ConfigManager.isBlitzExtendedResultsMorePositionsEnabled());
    }

    private static String starText() {
        return EnumChatFormatting.WHITE + "Blitz Star infos: " + state(ConfigManager.isBlitzExtendedResultsStarInfoEnabled());
    }

    private static String deathmatchText() {
        return EnumChatFormatting.WHITE + "Deathmatch players: " + state(ConfigManager.isBlitzExtendedResultsDeathmatchPlayersEnabled());
    }

    private static String messageCountText() {
        return EnumChatFormatting.WHITE + "Messages count: "
                + state(ConfigManager.isBlitzExtendedResultsMessageCountEnabled());
    }

    private static String gameDurationText() {
        return EnumChatFormatting.WHITE + "Game duration: "
                + state(ConfigManager.isBlitzExtendedResultsGameDurationEnabled());
    }

    private static String mapPlayerCountText() {
        return EnumChatFormatting.WHITE + "Map & Player count: "
                + state(ConfigManager.isBlitzExtendedResultsMapPlayerCountEnabled());
    }

    private static String state(boolean enabled) {
        return enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
