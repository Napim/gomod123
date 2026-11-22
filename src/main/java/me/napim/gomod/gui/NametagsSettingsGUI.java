package me.napim.gomod.gui;

import me.napim.gomod.features.ItemESP;
import me.napim.gomod.features.HorseDonkeyTracker;
import me.napim.gomod.features.MobNametags;
import me.napim.gomod.features.MobTargetDisplay;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import java.util.Arrays;
public class NametagsSettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 220;
    private static final int GAP = 8;

    private final GuiScreen parent;
    private GuiButton teamLootButton;
    private GuiButton mobNametagsButton;
    private GuiButton friendlyMobColorButton;
    private GuiButton mobTargetButton;
    private GuiButton horseFoodNametagButton;
    private GuiButton backButton;

    public NametagsSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int maxFirstY = backY - 10 - BUTTON_HEIGHT * 5 - GAP * 4;
        int yStart = Math.max(GuiHeader.standardContentTopAfterSubtitle(mc.fontRendererObj), Math.min(sh / 2 - 50, maxFirstY));

        int y = yStart;
        teamLootButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getTeamLootText());
        y += BUTTON_HEIGHT + GAP;
        mobNametagsButton = new GuiButton(2, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getMobNametagsText());
        y += BUTTON_HEIGHT + GAP;
        friendlyMobColorButton = new GuiButton(5, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getFriendlyMobColorText());
        y += BUTTON_HEIGHT + GAP;
        mobTargetButton = new GuiButton(3, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getMobTargetText());
        y += BUTTON_HEIGHT + GAP;
        horseFoodNametagButton = new GuiButton(4, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getHorseFoodNametagText());
        backButton = new GuiButton(99, cx - BUTTON_WIDTH / 2, backY, BUTTON_WIDTH, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(teamLootButton);
        buttonList.add(mobNametagsButton);
        buttonList.add(friendlyMobColorButton);
        buttonList.add(mobTargetButton);
        buttonList.add(horseFoodNametagButton);
        buttonList.add(backButton);
    }

    private String getTeamLootText() {
        return "Teammate LootESP: " + (ItemESP.isTeamLootEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getHorseFoodNametagText() {
        return EnumChatFormatting.WHITE + "Horse food nametag: " + (HorseDonkeyTracker.isFoodNametagEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getMobNametagsText() {
        return EnumChatFormatting.WHITE + "Mob nametags: " + (MobNametags.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getMobTargetText() {
        return EnumChatFormatting.WHITE + "Mob target display: " + (MobTargetDisplay.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getFriendlyMobColorText() {
        return EnumChatFormatting.WHITE + "Friendly mob color: " + (me.napim.gomod.features.ConfigManager.isFriendlyMobNametagColorEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        int cx = width / 2;
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Nametag Settings";
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, title, cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, NametagsSettingsGUI.class);
        if (isMouseOver(mobTargetButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Colors targeting mobs' names red.",
                    EnumChatFormatting.GRAY + "Shows one ! per mob above its target",
                    EnumChatFormatting.GRAY + "(other players only).",
                    EnumChatFormatting.GRAY + "Targets may be estimated from facing."), mouseX, mouseY);
        } else if (isMouseOver(horseFoodNametagButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(EnumChatFormatting.GRAY + "Displays how much food your horse needs"), mouseX, mouseY);
        } else if (isMouseOver(friendlyMobColorButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(EnumChatFormatting.GRAY + "Colors mobs you spawned green."), mouseX, mouseY);
        }
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1:
                ItemESP.setTeamLootEnabled(!ItemESP.isTeamLootEnabled());
                teamLootButton.displayString = getTeamLootText();
                break;
            case 2:
                MobNametags.setEnabled(!MobNametags.isEnabled());
                mobNametagsButton.displayString = getMobNametagsText();
                break;
            case 3:
                MobTargetDisplay.toggle();
                mobTargetButton.displayString = getMobTargetText();
                break;
            case 4:
                HorseDonkeyTracker.setFoodNametagEnabled(!HorseDonkeyTracker.isFoodNametagEnabled());
                horseFoodNametagButton.displayString = getHorseFoodNametagText();
                break;
            case 5:
                me.napim.gomod.features.ConfigManager.setFriendlyMobNametagColorEnabled(
                        !me.napim.gomod.features.ConfigManager.isFriendlyMobNametagColorEnabled());
                friendlyMobColorButton.displayString = getFriendlyMobColorText();
                break;
            case 99:
                mc.displayGuiScreen(parent);
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
