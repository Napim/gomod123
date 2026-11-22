package me.napim.gomod.gui;

import me.napim.gomod.features.BestArmorDisplay;
import me.napim.gomod.features.UselessItemOverlay;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;
import java.util.List;

/**
 * Inventory quality-of-life: {@link BestArmorDisplay} and {@link UselessItemOverlay}.
 */
public class InventoryQolSettingsGUI extends GuiScreen {

    private static final int BUTTON_WIDTH = 240;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 26;

    private final GuiScreen parent;
    private GuiButton bestArmorButton;
    private GuiButton uselessButton;
    private GuiButton uselessAdvancedButton;
    private GuiButton chestPickHintsButton;
    private GuiButton bestArmorDetailsButton;
    private GuiButton backButton;

    public InventoryQolSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        UselessItemOverlay.loadSettings();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int maxFirstY = backY - 10 - BUTTON_HEIGHT * 6 - GAP * 5;
        int y0 = Math.max(24, Math.min(sh / 2 - 40, maxFirstY));
        bestArmorButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, y0, BUTTON_WIDTH, BUTTON_HEIGHT, getBestArmorText());
        bestArmorDetailsButton = new GuiButton(5, cx - BUTTON_WIDTH / 2, y0 + GAP, BUTTON_WIDTH, BUTTON_HEIGHT,
                getBestArmorPercentageText());
        uselessButton = new GuiButton(2, cx - BUTTON_WIDTH / 2, y0 + GAP * 2, BUTTON_WIDTH, BUTTON_HEIGHT, getUselessText());
        chestPickHintsButton = new GuiButton(3, cx - BUTTON_WIDTH / 2, y0 + GAP * 3, BUTTON_WIDTH, BUTTON_HEIGHT,
                getChestPickHintsText());
        uselessAdvancedButton = new GuiButton(4, cx - BUTTON_WIDTH / 2, y0 + GAP * 4, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "Useless item options");
        backButton = new GuiButton(0, cx - BUTTON_WIDTH / 2, backY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");
        buttonList.add(bestArmorButton);
        buttonList.add(bestArmorDetailsButton);
        buttonList.add(uselessButton);
        buttonList.add(chestPickHintsButton);
        buttonList.add(uselessAdvancedButton);
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Inventory QoL"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String sub = EnumChatFormatting.GRAY + "Highlight over specifics items";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, sub, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, InventoryQolSettingsGUI.class);
        drawHoverTooltip(mouseX, mouseY);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            if (parent instanceof MiscellaneousGUI) {
                ((MiscellaneousGUI) parent).refreshInventoryQolButtonLabel();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            BestArmorDisplay.setEnabledSilent(!BestArmorDisplay.isEnabled());
            bestArmorButton.displayString = getBestArmorText();
            return;
        }
        if (button.id == 2) {
            UselessItemOverlay.setEnabled(!UselessItemOverlay.isEnabled());
            uselessButton.displayString = getUselessText();
            return;
        }
        if (button.id == 3) {
            UselessItemOverlay.setChestPickHintsEnabled(!UselessItemOverlay.isChestPickHintsEnabled());
            chestPickHintsButton.displayString = getChestPickHintsText();
            return;
        }
        if (button.id == 4) {
            mc.displayGuiScreen(new UselessItemSettingsGUI(this));
            return;
        }
        if (button.id == 5) {
            int m = me.napim.gomod.features.ConfigManager.getBestArmorPercentageMode();
            int next = m == me.napim.gomod.features.ConfigManager.BEST_ARMOR_PERCENTAGE_MINIMAL
                    ? me.napim.gomod.features.ConfigManager.BEST_ARMOR_PERCENTAGE_DETAILED
                    : m == me.napim.gomod.features.ConfigManager.BEST_ARMOR_PERCENTAGE_DETAILED
                    ? me.napim.gomod.features.ConfigManager.BEST_ARMOR_PERCENTAGE_OFF
                    : me.napim.gomod.features.ConfigManager.BEST_ARMOR_PERCENTAGE_MINIMAL;
            me.napim.gomod.features.ConfigManager.setBestArmorPercentageMode(next);
            bestArmorDetailsButton.displayString = getBestArmorPercentageText();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getBestArmorText() {
        return "BestArmor overlay: " + (BestArmorDisplay.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getUselessText() {
        return "Useless item overlay: " + (UselessItemOverlay.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getChestPickHintsText() {
        return "Chest pick hints: " + (UselessItemOverlay.isChestPickHintsEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getBestArmorPercentageText() {
        int m = me.napim.gomod.features.ConfigManager.getBestArmorPercentageMode();
        String modeLabel;
        if (m == me.napim.gomod.features.ConfigManager.BEST_ARMOR_PERCENTAGE_OFF) {
            modeLabel = EnumChatFormatting.RED + "OFF";
        } else if (m == me.napim.gomod.features.ConfigManager.BEST_ARMOR_PERCENTAGE_MINIMAL) {
            modeLabel = EnumChatFormatting.AQUA + "Average only";
        } else {
            modeLabel = EnumChatFormatting.YELLOW + "Detailed";
        }
        return "BestArmor percentage: " + modeLabel;
    }

    /** Example DR readout colors match {@link BestArmorDisplay} inventory rendering. */
    private static List<String> getBestArmorPercentageTooltipLines() {
        String samplePercent = EnumChatFormatting.GREEN + "65.24%";
        String sampleRange = EnumChatFormatting.GRAY + " (62.16% - 68.32%)";
        return Arrays.asList(
                EnumChatFormatting.GRAY + "Displays a damage reduction percentage in inventory",
                EnumChatFormatting.AQUA + "Average only: " + samplePercent,
                EnumChatFormatting.YELLOW + "Detailed: " + samplePercent + sampleRange
        );
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (isMouseOver(bestArmorButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "BestArmor overlay",
                    EnumChatFormatting.GRAY + "Highlights in green the recommended armor in your inventory"
            ), mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isMouseOver(uselessButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Useless item overlay",
                    EnumChatFormatting.GRAY + "Highlights in red weak/throwable Blitz items/gears in your inventory"
            ), mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isMouseOver(chestPickHintsButton, mouseX, mouseY)) {
            drawHoveringText(UselessItemOverlay.getChestPickHintsTooltipLines(), mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isMouseOver(uselessAdvancedButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Useless item options",
                    EnumChatFormatting.GRAY + "Chest pick hints settings"
            ), mouseX, Math.max(0, mouseY - 12));
            return;
        }
        if (isMouseOver(bestArmorDetailsButton, mouseX, mouseY)) {
            drawHoveringText(getBestArmorPercentageTooltipLines(), mouseX, Math.max(0, mouseY - 12));
        }
    }

    private boolean isMouseOver(GuiButton btn, int mx, int my) {
        return btn != null && mx >= btn.xPosition && mx < btn.xPosition + btn.width
                && my >= btn.yPosition && my < btn.yPosition + btn.height;
    }
}
