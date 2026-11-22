package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/** Controls the per-item world placement ghosts for TNT and supported passive-mob spawn eggs. */
public final class PlacementPreviewSettingsGUI extends GuiScreen {
    private static final int BUTTON_W = 240;
    private static final int BUTTON_H = 20;
    private static final int GAP = 7;

    private final GuiScreen parent;
    private GuiButton tntButton;
    private GuiButton chickenButton;
    private GuiButton pigButton;
    private GuiButton horseButton;
    private GuiButton donkeyButton;

    public PlacementPreviewSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        int x = this.width / 2 - BUTTON_W / 2;
        int contentTop = GuiHeader.standardContentTopAfterTitle(this.fontRendererObj);
        int totalH = BUTTON_H * 5 + GAP * 4;
        int y = Math.max(contentTop, this.height / 2 - totalH / 2 - 4);

        tntButton = new GuiButton(1, x, y, BUTTON_W, BUTTON_H, tntText());
        chickenButton = new GuiButton(2, x, y + (BUTTON_H + GAP), BUTTON_W, BUTTON_H, chickenText());
        pigButton = new GuiButton(3, x, y + (BUTTON_H + GAP) * 2, BUTTON_W, BUTTON_H, pigText());
        horseButton = new GuiButton(4, x, y + (BUTTON_H + GAP) * 3, BUTTON_W, BUTTON_H, horseText());
        donkeyButton = new GuiButton(5, x, y + (BUTTON_H + GAP) * 4, BUTTON_W, BUTTON_H, donkeyText());

        this.buttonList.add(tntButton);
        this.buttonList.add(chickenButton);
        this.buttonList.add(pigButton);
        this.buttonList.add(horseButton);
        this.buttonList.add(donkeyButton);
        this.buttonList.add(new GuiButton(99, x, GuiHeader.backButtonY(this.height, BUTTON_H),
                BUTTON_W, BUTTON_H, EnumChatFormatting.WHITE + "Back"));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 1:
                ConfigManager.setTntPlacementPreviewEnabled(!ConfigManager.isTntPlacementPreviewEnabled());
                break;
            case 2:
                ConfigManager.setChickenPlacementPreviewEnabled(!ConfigManager.isChickenPlacementPreviewEnabled());
                break;
            case 3:
                ConfigManager.setPigPlacementPreviewEnabled(!ConfigManager.isPigPlacementPreviewEnabled());
                break;
            case 4:
                ConfigManager.setHorsePlacementPreviewEnabled(!ConfigManager.isHorsePlacementPreviewEnabled());
                break;
            case 5:
                ConfigManager.setDonkeyPlacementPreviewEnabled(!ConfigManager.isDonkeyPlacementPreviewEnabled());
                break;
            case 99:
                closeToParent();
                return;
            default:
                return;
        }
        refreshLabels();
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
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GRAY.toString() + EnumChatFormatting.BOLD + "Placement preview"
                        + EnumChatFormatting.RESET,
                this.width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, PlacementPreviewSettingsGUI.class);
    }

    private void closeToParent() {
        if (parent instanceof MiscellaneousGUI) {
            ((MiscellaneousGUI) parent).refreshTntPlacementPreviewButtonLabel();
        }
        this.mc.displayGuiScreen(parent);
    }

    private void refreshLabels() {
        if (tntButton != null) tntButton.displayString = tntText();
        if (chickenButton != null) chickenButton.displayString = chickenText();
        if (pigButton != null) pigButton.displayString = pigText();
        if (horseButton != null) horseButton.displayString = horseText();
        if (donkeyButton != null) donkeyButton.displayString = donkeyText();
    }

    private static String tntText() {
        return whiteLabel("TNT", ConfigManager.isTntPlacementPreviewEnabled());
    }

    private static String chickenText() {
        return whiteLabel("Chicken", ConfigManager.isChickenPlacementPreviewEnabled());
    }

    private static String pigText() {
        return whiteLabel("Pig", ConfigManager.isPigPlacementPreviewEnabled());
    }

    private static String horseText() {
        return whiteLabel("Horse", ConfigManager.isHorsePlacementPreviewEnabled());
    }

    private static String donkeyText() {
        return whiteLabel("Donkey", ConfigManager.isDonkeyPlacementPreviewEnabled());
    }

    private static String whiteLabel(String name, boolean enabled) {
        return EnumChatFormatting.WHITE + name + " placement preview: "
                + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
