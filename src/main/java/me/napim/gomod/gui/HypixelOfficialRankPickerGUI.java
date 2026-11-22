package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Chooses a built-in Hypixel rank tag (VIP, MVP+, etc.) for {@code HYPIXEL_OFFICIAL} prefix mode.
 */
public class HypixelOfficialRankPickerGUI extends GuiScreen {

    /** Preset keys (not TARGET / CUSTOM). */
    static final String[] OFFICIAL_PRESETS = {
            "NONE", "VIP", "VIP_PLUS", "MVP", "MVP_PLUS", "MVP_PLUS_PLUS",
            "YOUTUBER", "OWNER", "ADMIN", "GM", "MOJANG", "EVENTS", "INNIT", "PIG_PLUS_PLUS_PLUS",
    };

    private final GuiScreen parent;
    private final List<GuiButton> rankButtons = new ArrayList<>();

    public HypixelOfficialRankPickerGUI(GuiScreen parent) {
        this.parent = parent;
    }

    static String coloredRankButtonLabel(String preset) {
        if (preset == null) {
            return "";
        }
        switch (preset) {
            case "NONE":
                return EnumChatFormatting.GRAY + "No rank";
            case "VIP":
                return EnumChatFormatting.GREEN + "VIP";
            case "VIP_PLUS":
                return EnumChatFormatting.GREEN + "VIP" + EnumChatFormatting.GOLD + "+";
            case "MVP":
                return EnumChatFormatting.AQUA + "MVP";
            case "MVP_PLUS":
                return EnumChatFormatting.AQUA + "MVP" + EnumChatFormatting.GOLD + "+";
            case "MVP_PLUS_PLUS":
                return EnumChatFormatting.GOLD + "MVP" + EnumChatFormatting.RED + "++";
            case "YOUTUBER":
                return EnumChatFormatting.RED + "YOUTUBE";
            case "OWNER":
            case "ADMIN":
                return EnumChatFormatting.RED + preset;
            case "GM":
                return EnumChatFormatting.DARK_GREEN + "GM";
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD + preset;
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE + "INNIT";
            case "PIG_PLUS_PLUS_PLUS":
                return EnumChatFormatting.LIGHT_PURPLE + "PIG" + EnumChatFormatting.AQUA + "+++";
            default:
                return EnumChatFormatting.WHITE + preset;
        }
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.rankButtons.clear();
        int cx = this.width / 2;
        int y = 72;
        int colW = 158;
        int gap = 6;
        int leftX = cx - colW - gap / 2;
        int rightX = cx + gap / 2;
        int rowH = 22;
        int id = 10;
        for (int i = 0; i < OFFICIAL_PRESETS.length; i++) {
            String preset = OFFICIAL_PRESETS[i];
            int x = (i % 2 == 0) ? leftX : rightX;
            int row = i / 2;
            GuiButton b = new GuiButton(id++, x, y + row * rowH, colW, 20, coloredRankButtonLabel(preset));
            this.rankButtons.add(b);
            this.buttonList.add(b);
        }
        this.buttonList.add(new GuiButton(0, cx - 60, this.height - 30, 120, 20, EnumChatFormatting.GRAY + "Back"));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        if (button.id == 0) {
            if (this.parent instanceof HypixelPrefixSettingsGUI) {
                ((HypixelPrefixSettingsGUI) this.parent).syncAfterOfficialPick();
            }
            this.mc.displayGuiScreen(this.parent);
            return;
        }
        int idx = button.id - 10;
        if (idx >= 0 && idx < OFFICIAL_PRESETS.length) {
            ConfigManager.setDisguiseHypixelRankPreset(OFFICIAL_PRESETS[idx]);
            LocalDisguise.refreshHypixelRankPresetFromConfig();
            if (this.parent instanceof HypixelPrefixSettingsGUI) {
                ((HypixelPrefixSettingsGUI) this.parent).syncAfterOfficialPick();
            }
            this.mc.displayGuiScreen(this.parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int cx = this.width / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GOLD.toString() + EnumChatFormatting.BOLD + "Hypixel official rank",
                cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GRAY + "Matches in-game tag colors (VIP green, MVP aqua, …)",
                cx, 40, 0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
