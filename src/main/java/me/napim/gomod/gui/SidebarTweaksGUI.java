package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.ScoreboardDateHider;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;

/**
 * Module toggle: when {@linkplain ConfigManager#isHideScoreboardValuesEnabled() disabled}, no sidebar tweak
 * (hide date / optional kit / kill-effect line, taunt details rewrite, layout) runs. Sub-options stay saved but are inactive until re-enabled.
 */
public class SidebarTweaksGUI extends GuiScreen {
    private static final int TITLE_RGB = 0x005AFF;

    /** Between the old fixed 220px buttons and the first auto-size pass (max 200). */
    private static final int BUTTON_WIDTH_MIN = 160;
    private static final int BUTTON_WIDTH_MAX = 210;
    private static final int BUTTON_WIDTH_PAD = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 26;

    private final GuiScreen parent;
    private GuiButton masterButton;
    private GuiButton dateGameIdButton;
    private GuiButton kitButton;
    private GuiButton hypixelFooterButton;
    private GuiButton blitzHourButton;
    private GuiButton killEffectLineButton;
    private GuiButton tauntWastedButton;
    private GuiButton backButton;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public SidebarTweaksGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        // Rows are spaced by GAP (y0, y0+GAP, …), not BUTTON_HEIGHT+GAP — same as Inventory QoL screens.
        int stackH = (7 - 1) * GAP + BUTTON_HEIGHT;
        int contentTop = GuiHeader.standardContentTopAfterTitle(mc.fontRendererObj) + 12;
        int contentBottom = backY - 16;
        int slack = contentBottom - contentTop - stackH;
        int y0 = contentTop + Math.max(0, slack / 2);
        if (y0 + stackH > contentBottom) {
            y0 = Math.max(contentTop, contentBottom - stackH);
        }
        int buttonWidth = buttonWidthForLabels(
                getMasterText(),
                getDateGameIdText(),
                getBlitzHourText(),
                getKitText(),
                getKillEffectLineText(),
                getTauntWastedText(),
                getHypixelFooterText(),
                EnumChatFormatting.GRAY + "Back");
        masterButton = new GuiButton(1, cx - buttonWidth / 2, y0, buttonWidth, BUTTON_HEIGHT, getMasterText());
        dateGameIdButton = new GuiButton(2, cx - buttonWidth / 2, y0 + GAP, buttonWidth, BUTTON_HEIGHT,
                getDateGameIdText());
        blitzHourButton = new GuiButton(3, cx - buttonWidth / 2, y0 + GAP * 2, buttonWidth, BUTTON_HEIGHT,
                getBlitzHourText());
        kitButton = new GuiButton(4, cx - buttonWidth / 2, y0 + GAP * 3, buttonWidth, BUTTON_HEIGHT, getKitText());
        killEffectLineButton = new GuiButton(5, cx - buttonWidth / 2, y0 + GAP * 4, buttonWidth, BUTTON_HEIGHT,
                getKillEffectLineText());
        tauntWastedButton = new GuiButton(6, cx - buttonWidth / 2, y0 + GAP * 5, buttonWidth, BUTTON_HEIGHT,
                getTauntWastedText());
        hypixelFooterButton = new GuiButton(7, cx - buttonWidth / 2, y0 + GAP * 6, buttonWidth, BUTTON_HEIGHT,
                getHypixelFooterText());
        backButton = new GuiButton(0, cx - buttonWidth / 2, backY, buttonWidth, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");
        buttonList.add(masterButton);
        buttonList.add(dateGameIdButton);
        buttonList.add(blitzHourButton);
        buttonList.add(kitButton);
        buttonList.add(killEffectLineButton);
        buttonList.add(tauntWastedButton);
        buttonList.add(hypixelFooterButton);
        buttonList.add(backButton);
        refreshDependentButtonState();
        enabledOverlays.trackModuleToggle("main", masterButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return ConfigManager.isHideScoreboardValuesEnabled();
            }
        });
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, EnumChatFormatting.BOLD + "Sidebar Tweaks",
                sr.getScaledWidth() / 2, GuiHeader.STANDARD_TITLE_Y, TITLE_RGB);
        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, SidebarTweaksGUI.class);
        drawHoverTooltip(mouseX, mouseY);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            if (parent instanceof MiscellaneousGUI) {
                ((MiscellaneousGUI) parent).refreshSidebarTweaksButtonLabel();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            boolean now = !ConfigManager.isHideScoreboardValuesEnabled();
            ConfigManager.setHideScoreboardValuesEnabled(now);
            masterButton.displayString = getMasterText();
            enabledOverlays.onToggled("main", now);
            dateGameIdButton.displayString = getDateGameIdText();
            kitButton.displayString = getKitText();
            hypixelFooterButton.displayString = getHypixelFooterText();
            blitzHourButton.displayString = getBlitzHourText();
            killEffectLineButton.displayString = getKillEffectLineText();
            tauntWastedButton.displayString = getTauntWastedText();
            refreshDependentButtonState();
            ScoreboardDateHider.onGuiToggleScoreboardHide();
            return;
        }
        if (!ConfigManager.isHideScoreboardValuesEnabled()) {
            return;
        }
        if (button.id == 2) {
            ConfigManager.setHideDateGameIdEnabled(!ConfigManager.isHideDateGameIdEnabled());
            dateGameIdButton.displayString = getDateGameIdText();
            ScoreboardDateHider.onGuiToggleScoreboardHide();
            return;
        }
        if (button.id == 3) {
            ConfigManager.setSidebarBlitzHourLineEnabled(!ConfigManager.isSidebarBlitzHourLineEnabled());
            blitzHourButton.displayString = getBlitzHourText();
            ScoreboardDateHider.onGuiToggleScoreboardHide();
            return;
        }
        if (button.id == 4) {
            ConfigManager.setHideKitScoreboardEnabled(!ConfigManager.isHideKitScoreboardEnabled());
            kitButton.displayString = getKitText();
            ScoreboardDateHider.onGuiToggleScoreboardHide();
            return;
        }
        if (button.id == 5) {
            ConfigManager.setSidebarKillEffectLineEnabled(!ConfigManager.isSidebarKillEffectLineEnabled());
            killEffectLineButton.displayString = getKillEffectLineText();
            return;
        }
        if (button.id == 6) {
            ConfigManager.setSidebarTauntWastedEnabled(!ConfigManager.isSidebarTauntWastedEnabled());
            tauntWastedButton.displayString = getTauntWastedText();
            return;
        }
        if (button.id == 7) {
            ConfigManager.setHideHypixelFooterEnabled(!ConfigManager.isHideHypixelFooterEnabled());
            hypixelFooterButton.displayString = getHypixelFooterText();
            ScoreboardDateHider.onGuiToggleScoreboardHide();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getMasterText() {
        return "Sidebar tweaks: " + (ConfigManager.isHideScoreboardValuesEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled");
    }

    private void refreshDependentButtonState() {
        boolean on = ConfigManager.isHideScoreboardValuesEnabled();
        killEffectLineButton.enabled = on;
        dateGameIdButton.enabled = on;
        kitButton.enabled = on;
        hypixelFooterButton.enabled = on;
        blitzHourButton.enabled = on;
        tauntWastedButton.enabled = on;
    }

    private String getDateGameIdText() {
        return "Hide date & game ID: " + (ConfigManager.isHideDateGameIdEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    /**
     * Label is "show kit"; config remains {@link ConfigManager#isHideKitScoreboardEnabled()} — ON here means the kit line
     * is visible ({@code hideKit == false}).
     */
    private String getKitText() {
        boolean showKit = !ConfigManager.isHideKitScoreboardEnabled();
        return "Show Kit: " + (showKit
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getHypixelFooterText() {
        return "Hide " + EnumChatFormatting.YELLOW + "www.hypixel.net" + EnumChatFormatting.RESET + ": "
                + (ConfigManager.isHideHypixelFooterEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getBlitzHourText() {
        return "Show " + EnumChatFormatting.YELLOW + "BLITZ HOUR"
                + EnumChatFormatting.RESET + ": "
                + (ConfigManager.isSidebarBlitzHourLineEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getKillEffectLineText() {
        String ke = ConfigManager.isSidebarKillEffectLineEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
        return "Show Kill Effect: " + ke;
    }

    private String getTauntWastedText() {
        String tw = ConfigManager.isSidebarTauntWastedEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
        return "Taunt details: " + tw;
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (!isMouseOver(tauntWastedButton, mouseX, mouseY)) {
            return;
        }
        drawHoveringText(Arrays.asList(
                EnumChatFormatting.AQUA + "Taunt details",
                EnumChatFormatting.GRAY + "When enabled, the sidebar taunt line is clarified after your taunt attempt:",
                EnumChatFormatting.WHITE + "Taunt: " + EnumChatFormatting.GREEN + "USED"
                        + EnumChatFormatting.GRAY + " means the taunt was successful.",
                EnumChatFormatting.WHITE + "Taunt: " + EnumChatFormatting.RED + "WASTED"
                        + EnumChatFormatting.GRAY + " means it was not."
        ), mouseX, Math.max(0, mouseY - 12));
    }

    private boolean isMouseOver(GuiButton btn, int mx, int my) {
        return btn != null && mx >= btn.xPosition && mx < btn.xPosition + btn.width
                && my >= btn.yPosition && my < btn.yPosition + btn.height;
    }

    private int buttonWidthForLabels(String... labels) {
        int max = 0;
        for (String label : labels) {
            if (label == null) {
                continue;
            }
            max = Math.max(max, fontRendererObj.getStringWidth(stripFormatting(label)));
        }
        return Math.max(BUTTON_WIDTH_MIN, Math.min(BUTTON_WIDTH_MAX, max + BUTTON_WIDTH_PAD));
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("\u00A7.", "");
    }
}
