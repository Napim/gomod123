package me.napim.gomod.gui;

import me.napim.gomod.features.LootCall;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

/**
 * Loot Call: master toggle and which ingots to announce. Everywhere mode stays on {@code /lootcall everywhere}.
 */
public class LootCallSettingsGUI extends GuiScreen {

    private final GuiScreen parent;
    private GuiButton enabledButton;
    private GuiButton diamondButton;
    private GuiButton ironButton;
    private GuiButton goldButton;
    private GuiButton backButton;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public LootCallSettingsGUI(GuiScreen parent) {
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
        int gap = 24;
        int y0 = cy - 60;
        enabledButton = new GuiButton(1, cx - w / 2, y0, w, h, getEnabledText());
        diamondButton = new GuiButton(3, cx - w / 2, y0 + gap, w, h, getDiamondText());
        ironButton = new GuiButton(4, cx - w / 2, y0 + gap * 2, w, h, getIronText());
        goldButton = new GuiButton(5, cx - w / 2, y0 + gap * 3, w, h, getGoldText());
        backButton = new GuiButton(0, cx - w / 2, y0 + gap * 4 + 10, w, h, EnumChatFormatting.GRAY + "Back");
        buttonList.add(enabledButton);
        buttonList.add(diamondButton);
        buttonList.add(ironButton);
        buttonList.add(goldButton);
        buttonList.add(backButton);
        refreshDependentButtonState();
        enabledOverlays.trackModuleToggle("main", enabledButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return LootCall.isEnabled();
            }
        });
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Loot Call" + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, LootCallSettingsGUI.class);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            if (parent instanceof MiscellaneousGUI) {
                ((MiscellaneousGUI) parent).refreshLootCallButtonLabel();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            boolean now = !LootCall.isEnabled();
            LootCall.setEnabled(now);
            enabledButton.displayString = getEnabledText();
            enabledOverlays.onToggled("main", now);
            refreshDependentButtonState();
            return;
        }
        if (!LootCall.isEnabled()) {
            return;
        }
        if (button.id == 3) {
            LootCall.setAnnounceDiamond(!LootCall.isAnnounceDiamond());
            diamondButton.displayString = getDiamondText();
            return;
        }
        if (button.id == 4) {
            LootCall.setAnnounceIron(!LootCall.isAnnounceIron());
            ironButton.displayString = getIronText();
            return;
        }
        if (button.id == 5) {
            LootCall.setAnnounceGold(!LootCall.isAnnounceGold());
            goldButton.displayString = getGoldText();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getEnabledText() {
        return "Loot Call: " + (LootCall.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private void refreshDependentButtonState() {
        boolean on = LootCall.isEnabled();
        diamondButton.enabled = on;
        ironButton.enabled = on;
        goldButton.enabled = on;
    }

    private String getDiamondText() {
        return "Announce diamonds: " + (LootCall.isAnnounceDiamond()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getIronText() {
        return "Announce iron ingots: " + (LootCall.isAnnounceIron()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getGoldText() {
        return "Announce gold ingots: " + (LootCall.isAnnounceGold()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }
}
