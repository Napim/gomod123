package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.TeamNickFinderDateFormat;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Teams Nick Finder — how "Last found" / "Added" timestamps are shown in the player list.
 */
public class TeamSpamDateSettingsGUI extends GuiScreen {

    private static final int ID_BACK = 0;
    private static final int ID_MODE = 1;
    private static final int ID_CLOCK = 2;
    private static final int ID_ZONE = 3;

    /** Cycled order: OS default, then UTC and UTC+1 … UTC+12 (fixed offsets). */
    private static final List<String> ZONE_PRESETS;

    static {
        List<String> z = new ArrayList<String>();
        z.add("SYSTEM");
        z.add("UTC");
        for (int h = 1; h <= 12; h++) {
            z.add("UTC+" + h);
        }
        ZONE_PRESETS = Collections.unmodifiableList(z);
    }

    private final GuiScreen parent;
    private GuiButton modeButton;
    private GuiButton clockButton;
    private GuiButton zoneButton;
    private GuiButton backButton;

    public TeamSpamDateSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        int cx = this.width / 2;
        /** Same width as {@link GoStatsGUI} main rows — enough for timezone sample text without a huge bar. */
        int w = 200;
        int h = 20;
        int rowGap = 24;
        int y0 = this.height / 2 - 48;
        modeButton = new GuiButton(ID_MODE, cx - w / 2, y0, w, h, modeLabel());
        clockButton = new GuiButton(ID_CLOCK, cx - w / 2, y0 + rowGap, w, h, clockLabel());
        zoneButton = new GuiButton(ID_ZONE, cx - w / 2, y0 + rowGap * 2, w, h, zoneLabel());
        backButton = new GuiButton(ID_BACK, cx - w / 2, y0 + rowGap * 3, w, h, EnumChatFormatting.GRAY + "Back");
        buttonList.clear();
        buttonList.add(modeButton);
        buttonList.add(clockButton);
        buttonList.add(zoneButton);
        buttonList.add(backButton);
        refreshClockZoneButtonState();
    }

    /** Same idea as {@link SidebarTweaksGUI#refreshDependentButtonState()} — greyed, non-interactive when N/A. */
    private void refreshClockZoneButtonState() {
        boolean absolute = !ConfigManager.isTeamNickFinderLastFoundRelative();
        clockButton.enabled = absolute;
        zoneButton.enabled = absolute;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Teams Nick Finder — dates" + EnumChatFormatting.RESET,
                this.width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_BACK:
                mc.displayGuiScreen(parent);
                break;
            case ID_MODE:
                ConfigManager.setTeamNickFinderLastFoundRelative(!ConfigManager.isTeamNickFinderLastFoundRelative());
                modeButton.displayString = modeLabel();
                clockButton.displayString = clockLabel();
                zoneButton.displayString = zoneLabel();
                refreshClockZoneButtonState();
                break;
            case ID_CLOCK:
                ConfigManager.setTeamNickFinder24HourClock(!ConfigManager.isTeamNickFinder24HourClock());
                clockButton.displayString = clockLabel();
                zoneButton.displayString = zoneLabel();
                break;
            case ID_ZONE:
                cycleZone();
                zoneButton.displayString = zoneLabel();
                break;
            default:
                break;
        }
    }

    private void cycleZone() {
        String cur = ConfigManager.getTeamNickFinderZoneId();
        int idx = -1;
        for (int i = 0; i < ZONE_PRESETS.size(); i++) {
            if (ZONE_PRESETS.get(i).equalsIgnoreCase(cur)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            // Older configs (e.g. IANA city zones) — jump into the fixed-offset list at UTC
            ConfigManager.setTeamNickFinderZoneId("UTC");
            return;
        }
        int next = (idx + 1) % ZONE_PRESETS.size();
        ConfigManager.setTeamNickFinderZoneId(ZONE_PRESETS.get(next));
    }

    private String modeLabel() {
        return EnumChatFormatting.GOLD + "Last found / Added: "
                + (ConfigManager.isTeamNickFinderLastFoundRelative()
                ? EnumChatFormatting.AQUA + "Relative (8h ago)"
                : EnumChatFormatting.GREEN + "Date & time");
    }

    /** Plain {@code "Clock: "} prefix matches {@link SidebarTweaksGUI} sub-rows so disabled grey-out reads correctly. */
    private String clockLabel() {
        return "Clock: "
                + (ConfigManager.isTeamNickFinder24HourClock()
                ? EnumChatFormatting.GREEN + "24-hour"
                : EnumChatFormatting.GREEN + "12-hour (AM/PM)");
    }

    private String zoneLabel() {
        String detail = TeamNickFinderDateFormat.formatTimezoneChoiceLabel(ConfigManager.getTeamNickFinderZoneId());
        return "Timezone: " + EnumChatFormatting.WHITE + detail;
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
