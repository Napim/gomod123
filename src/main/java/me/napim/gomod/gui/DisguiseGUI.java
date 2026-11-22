/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.Gui
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.util.EnumChatFormatting
 */
package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.command.DisguiseCommand;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LocalMobDisguise;
import me.napim.gomod.features.MixinStatus;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.gui.DisguiseNameInputGUI;
import me.napim.gomod.gui.MobLookEntityInputGUI;
import me.napim.gomod.gui.GuiHeader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class DisguiseGUI
extends GuiScreen {
    private static final int TITLE_RGB = 0x796BFF;
    private static final int BUTTON_HEIGHT = 20;
    /** Same as {@link KillCounterSettingsGUI} TAB column / {@link MobHealthDisplayGUI}. */
    private static final int VALUE_BUTTON_WIDTH = 120;
    private static final int ACTION_BUTTON_WIDTH = 120;
    private static final int SPACING = 30;
    private static final int CONTENT_TOP = 108;
    private static final int CONTENT_BOTTOM_GAP = 8;
    private static final int CONTROL_GAP = 2;
    /**
     * {@link LocalDisguise#formatBsg2KillBracketForDisplay} uses §l at 100k+; vanilla
     * {@link FontRenderer#getStringWidth} centers ~2px too far right in the narrow value cell.
     */
    private static final int CUSTOM_KILL_BOLD_CENTER_NUDGE_X = -2;

    private final GuiScreen parent;
    private GuiButton targetButton;
    private GuiButton disableButton;
    private GuiButton killCounterButton;
    private GuiButton tournamentCounterButton;
    private GuiButton customKillToggleButton;
    private GuiButton customKillValueButton;
    private GuiButton blitzKitSelectorButton;
    private GuiButton maskRenamedItemsButton;
    private GuiButton hypixelPrefixButton;
    private GuiButton capeButton;
    private GuiButton reconnectRestoreButton;
    private GuiButton lunarCosmeticsButton;
    private GuiButton nickDisguiseButton;
    private GuiButton mobLookButton;
    private GuiButton disableMobButton;
    private GuiButton backButton;
    private boolean selfCapeCatalogWasLoading;
    private boolean lastDisguiseCommandLoading;
    private boolean lastCapeCatalogLoading;
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    private boolean isPublicCapeOnly() {
        return !GomodInternalFeatures.isEnabled();
    }

    public DisguiseGUI(GuiScreen parent) {
        this.parent = parent;
    }

    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = this.width / 2;
        int scaledH = sr.getScaledHeight();
        /** Value buttons: {@code width/2 + 80} — same column as TAB QoL / Mob Health settings. */
        int buttonLeft = this.width / 2 + 80;
        int startY = CONTENT_TOP;

        if (isPublicCapeOnly()) {
            this.capeButton = new GuiButton(2, centerX - VALUE_BUTTON_WIDTH / 2, startY,
                    VALUE_BUTTON_WIDTH, BUTTON_HEIGHT, this.getCapeButtonText());
            int backY = GuiHeader.backButtonY(scaledH, BUTTON_HEIGHT);
            this.backButton = new GuiButton(0, centerX - ACTION_BUTTON_WIDTH / 2, backY,
                    ACTION_BUTTON_WIDTH, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back to Menu");
            this.buttonList.add(this.capeButton);
            this.buttonList.add(this.backButton);
            this.refreshButtonStates();
            return;
        }

        int pairGap = 2;
        int halfPair = (VALUE_BUTTON_WIDTH - pairGap) / 2;
        this.targetButton = new GuiButton(3, buttonLeft, startY, VALUE_BUTTON_WIDTH, 20, this.getTargetButtonText());
        this.nickDisguiseButton = new GuiButton(12, buttonLeft, startY + 30, VALUE_BUTTON_WIDTH, 20, this.getNickDisguiseButtonText());
        this.disableButton = new GuiButton(4, buttonLeft + VALUE_BUTTON_WIDTH + CONTROL_GAP, startY,
                VALUE_BUTTON_WIDTH, 20, EnumChatFormatting.RED + "Disable Active Disguise");
        this.killCounterButton = new GuiButton(5, buttonLeft, startY + 60, VALUE_BUTTON_WIDTH, 20, this.getKillCounterText());
        this.tournamentCounterButton = new GuiButton(1, buttonLeft, startY + 90, VALUE_BUTTON_WIDTH, 20, this.getTournamentCounterText());
        this.customKillToggleButton = new GuiButton(10, buttonLeft, startY + 120, halfPair, 20, LocalDisguise.getDisguiseCustomKillFeatureToggleLabel());
        this.customKillValueButton = new DisguiseCustomKillValueButton(9, buttonLeft + halfPair + pairGap, startY + 120, halfPair, 20);
        this.blitzKitSelectorButton = new GuiButton(6, buttonLeft, startY + 150, VALUE_BUTTON_WIDTH, 20, this.getBlitzKitSelectorText());
        this.maskRenamedItemsButton = new GuiButton(13, buttonLeft, startY + 180, VALUE_BUTTON_WIDTH, 20, this.getRenamedItemsText());
        this.hypixelPrefixButton = new GuiButton(7, buttonLeft, startY + 210, VALUE_BUTTON_WIDTH, 20, this.getHypixelPrefixText());
        this.capeButton = new GuiButton(2, buttonLeft, startY + 240, VALUE_BUTTON_WIDTH, 20, this.getCapeButtonText());
        this.reconnectRestoreButton = new GuiButton(11, buttonLeft, startY + 270, VALUE_BUTTON_WIDTH, 20, this.getReconnectRestoreLabel());
        int mobRow = 300;
        if (GomodFixes.isWeaveRuntime()) {
            this.lunarCosmeticsButton = new GuiButton(14, buttonLeft, startY + 300,
                    VALUE_BUTTON_WIDTH, 20, this.getLunarCosmeticsLabel());
            mobRow += 30;
        }
        this.mobLookButton = new GuiButton(8, buttonLeft, startY + mobRow, VALUE_BUTTON_WIDTH, 20,
                LocalMobDisguise.getGuiButtonLabel());
        this.disableMobButton = new GuiButton(15, buttonLeft + VALUE_BUTTON_WIDTH + CONTROL_GAP,
                startY + mobRow, VALUE_BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.RED + "Disable Mob Disguise");
        int backY = GuiHeader.backButtonY(scaledH, 20);
        this.backButton = new GuiButton(0, centerX - ACTION_BUTTON_WIDTH / 2, backY, ACTION_BUTTON_WIDTH, 20, EnumChatFormatting.GRAY + "Back to Menu");
        this.buttonList.add(this.targetButton);
        this.buttonList.add(this.disableButton);
        this.buttonList.add(this.killCounterButton);
        this.buttonList.add(this.tournamentCounterButton);
        this.buttonList.add(this.customKillToggleButton);
        this.buttonList.add(this.customKillValueButton);
        this.buttonList.add(this.blitzKitSelectorButton);
        this.buttonList.add(this.maskRenamedItemsButton);
        this.buttonList.add(this.hypixelPrefixButton);
        this.buttonList.add(this.capeButton);
        this.buttonList.add(this.reconnectRestoreButton);
        if (this.lunarCosmeticsButton != null) {
            this.buttonList.add(this.lunarCosmeticsButton);
        }
        this.buttonList.add(this.nickDisguiseButton);
        this.buttonList.add(this.mobLookButton);
        this.buttonList.add(this.disableMobButton);
        this.buttonList.add(this.backButton);
        this.refreshButtonStates();
        this.applyScrollLayout(0);
    }

    public void updateScreen() {
        super.updateScreen();
        boolean loading = DisguiseCommand.isLoading();
        boolean capeLoading = LocalDisguise.isLocalSelfCapeCatalogLoading();
        if (loading != lastDisguiseCommandLoading || capeLoading != lastCapeCatalogLoading) {
            lastDisguiseCommandLoading = loading;
            lastCapeCatalogLoading = capeLoading;
            refreshButtonStates();
        } else if (capeLoading) {
            selfCapeCatalogWasLoading = true;
        } else if (selfCapeCatalogWasLoading) {
            selfCapeCatalogWasLoading = false;
            refreshButtonStates();
        }
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft((Gui)this);
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = sr.getScaledWidth() / 2;
        int labelX = this.width / 2 - 200;
        String title = EnumChatFormatting.BOLD + "Disguise";
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj, title, centerX, GuiHeader.STANDARD_TITLE_Y, TITLE_RGB);
        if (isPublicCapeOnly()) {
            GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                    EnumChatFormatting.GRAY + "Cape", centerX, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
            int row0 = CONTENT_TOP;
            int publicLabelX = centerX - VALUE_BUTTON_WIDTH / 2 - 55;
            int vText = (BUTTON_HEIGHT - this.fontRendererObj.FONT_HEIGHT) / 2;
            this.fontRendererObj.drawString("Cape", publicLabelX, row0 + vText, 0xFFFFFF);
            String capeMarqueeLabel = null;
            if (this.capeButton != null) {
                capeMarqueeLabel = this.getCapeButtonText();
                this.capeButton.displayString = "";
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
            if (this.capeButton != null && capeMarqueeLabel != null) {
                GuiMarqueeButtonLabel.draw(this.fontRendererObj, this.capeButton,
                        mouseX, mouseY, capeMarqueeLabel, false);
            }
            return;
        }
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                LocalDisguise.getActiveDisguiseGuiStatusLine(), centerX, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        String capeLine = LocalDisguise.isEnabled()
                ? EnumChatFormatting.GRAY + "Disguise capes: " + EnumChatFormatting.AQUA + LocalDisguise.getCapeOptionCount()
                : EnumChatFormatting.GRAY + "Your capes (no disguise)";
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj, capeLine, centerX, 56, 0xFFFFFF);
        scroll.tick();
        int visibleTop = this.getVisibleContentTop();
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(this.height, BUTTON_HEIGHT, 10);
        scroll.updateBounds(CONTENT_TOP, this.getUnscrolledContentBottom(), visibleTop, visibleBottom);
        int scrollOffset = scroll.getOffset();
        this.applyScrollLayout(scrollOffset);

        int vText = (BUTTON_HEIGHT - this.fontRendererObj.FONT_HEIGHT) / 2;
        GuiScrollFade.drawLabel(this.fontRendererObj, "Player disguise", labelX, this.targetButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Nick disguise", labelX, this.nickDisguiseButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Kill Counter", labelX, this.killCounterButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Tournament Kill Counter", labelX, this.tournamentCounterButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Custom Kill Count", labelX, this.customKillToggleButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Blitz Kit Selector disguise", labelX, this.blitzKitSelectorButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Renamed items", labelX, this.maskRenamedItemsButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Hypixel Prefix", labelX, this.hypixelPrefixButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        String hypSum = LocalDisguise.getHypixelPrefixSettingsButtonSummary();
        GuiScrollFade.drawStringAt(this.fontRendererObj, hypSum, labelX, this.hypixelPrefixButton.yPosition + vText + 11,
                0xFFFFFF, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Cape", labelX, this.capeButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        GuiScrollFade.drawLabel(this.fontRendererObj, "Restore disguise after game restarts", labelX,
                this.reconnectRestoreButton.yPosition, vText, BUTTON_HEIGHT, visibleTop, visibleBottom,
                scrollOffset, scroll.getMaxScroll());
        if (this.lunarCosmeticsButton != null) {
            GuiScrollFade.drawLabel(this.fontRendererObj, "Lunar cosmetics during disguise", labelX, this.lunarCosmeticsButton.yPosition,
                    vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        }
        GuiScrollFade.drawLabel(this.fontRendererObj, "Mob disguise", labelX, this.mobLookButton.yPosition,
                vText, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOffset, scroll.getMaxScroll());
        String capeMarqueeLabel = null;
        if (this.capeButton != null) {
            boolean loading = DisguiseCommand.isLoading();
            capeMarqueeLabel = loading ? this.getCapeLoadingText() : this.getCapeButtonText();
            this.capeButton.displayString = "";
        }
        scroll.drawScrollbar(this.width, mouseY);
        scroll.drawFadedButtonsExcluding(this.mc, this.fontRendererObj, this.buttonList, mouseX, mouseY, 0);
        if (this.backButton != null && this.backButton.visible) {
            this.backButton.drawButton(this.mc, mouseX, mouseY);
        }
        if (this.capeButton != null && capeMarqueeLabel != null && this.capeButton.visible) {
            GuiMarqueeButtonLabel.draw(this.fontRendererObj, this.capeButton, mouseX, mouseY, capeMarqueeLabel, false);
        }
        drawHoverTooltips(mouseX, mouseY);
    }

    private int getVisibleContentTop() {
        // The first row is the top of this screen's scroll viewport; the header stays fixed above it.
        return CONTENT_TOP;
    }

    private int getUnscrolledContentBottom() {
        int mobRow = this.lunarCosmeticsButton != null ? 11 : 10;
        return CONTENT_TOP + mobRow * SPACING + BUTTON_HEIGHT + CONTENT_BOTTOM_GAP;
    }

    private int getActiveDisguiseRow() {
        if (!LocalDisguise.isEnabled()) {
            return -1;
        }
        return LocalDisguise.isNickDisguiseActive() ? 1 : 0;
    }

    private void applyScrollLayout(int scrollOffset) {
        int visibleTop = this.getVisibleContentTop();
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(this.height, BUTTON_HEIGHT, 10);
        int buttonLeft = this.width / 2 + 80;
        int rowY = CONTENT_TOP - scrollOffset;
        this.layoutScrollableButton(this.targetButton, buttonLeft, rowY, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.nickDisguiseButton, buttonLeft, rowY + SPACING, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.killCounterButton, buttonLeft, rowY + SPACING * 2, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.tournamentCounterButton, buttonLeft, rowY + SPACING * 3, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.customKillToggleButton, buttonLeft, rowY + SPACING * 4, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.customKillValueButton, buttonLeft + 61 + CONTROL_GAP,
                rowY + SPACING * 4, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.blitzKitSelectorButton, buttonLeft, rowY + SPACING * 5, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.maskRenamedItemsButton, buttonLeft, rowY + SPACING * 6, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.hypixelPrefixButton, buttonLeft, rowY + SPACING * 7, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.capeButton, buttonLeft, rowY + SPACING * 8, visibleTop, visibleBottom);
        this.layoutScrollableButton(this.reconnectRestoreButton, buttonLeft, rowY + SPACING * 9, visibleTop, visibleBottom);

        int nextRow = 10;
        if (this.lunarCosmeticsButton != null) {
            this.layoutScrollableButton(this.lunarCosmeticsButton, buttonLeft, rowY + SPACING * nextRow,
                    visibleTop, visibleBottom);
            nextRow++;
        }
        this.layoutScrollableButton(this.mobLookButton, buttonLeft, rowY + SPACING * nextRow,
                visibleTop, visibleBottom);
        this.layoutScrollableButton(this.disableMobButton, buttonLeft + VALUE_BUTTON_WIDTH + CONTROL_GAP,
                rowY + SPACING * nextRow, visibleTop, visibleBottom);
        boolean mobActive = LocalMobDisguise.isActive();
        this.disableMobButton.enabled = mobActive;
        this.disableMobButton.visible &= mobActive;

        int activeRow = this.getActiveDisguiseRow();
        this.disableButton.xPosition = buttonLeft + VALUE_BUTTON_WIDTH + CONTROL_GAP;
        this.disableButton.yPosition = activeRow < 0 ? rowY : rowY + SPACING * activeRow;
        this.disableButton.visible = activeRow >= 0
                && GuiScrollFade.intersectsVisible(this.disableButton.yPosition, BUTTON_HEIGHT, visibleTop, visibleBottom);
    }

    private void layoutScrollableButton(GuiButton button, int x, int y, int visibleTop, int visibleBottom) {
        if (button == null) {
            return;
        }
        button.xPosition = x;
        button.yPosition = y;
        button.visible = GuiScrollFade.intersectsVisible(y, BUTTON_HEIGHT, visibleTop, visibleBottom);
    }

    private void drawHoverTooltips(int mouseX, int mouseY) {
        if (isHovering(this.killCounterButton, mouseX, mouseY)) {
            drawTooltipLines(buildKillCounterTooltipLines(), mouseX, mouseY);
        }
        if (isHovering(this.tournamentCounterButton, mouseX, mouseY)) {
            drawTooltipLines(buildTournamentKillCounterTooltipLines(), mouseX, mouseY);
        }
        if (isHovering(this.blitzKitSelectorButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Blitz Kit Selector disguise",
                    EnumChatFormatting.GRAY + "Disguises your kits in the Kit Selector GUI & in the lobby kits menu"
            ), mouseX, Math.max(0, mouseY - 12));
        }
        if (isHovering(this.maskRenamedItemsButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Renamed items",
                    EnumChatFormatting.GRAY + "Blitz kit item title while disguised:",
                    EnumChatFormatting.GREEN + "Target's" + EnumChatFormatting.GRAY + " - Uses the disguise target's",
                    EnumChatFormatting.GRAY + " renamed kit items i.e: "
                            + EnumChatFormatting.LIGHT_PURPLE + "MY CUSTOM PIG SWORD",
                    EnumChatFormatting.GOLD + "Reseted" + EnumChatFormatting.GRAY + " - Resets the disguise target's",
                    EnumChatFormatting.GRAY + " renamed kit items i.e: "
                            + EnumChatFormatting.WHITE + "Pigman's Gold Sword " + EnumChatFormatting.GRAY + "(X)",
                    EnumChatFormatting.RED + "Unchanged" + EnumChatFormatting.GRAY + " - Keeps your real kit item names"
            ), mouseX, Math.max(0, mouseY - 12));
        }
    }

    private void drawTooltipLines(List<String> lines, int mouseX, int mouseY) {
        MixinStatus.skipFontRendererDisguiseReplace = true;
        try {
            drawHoveringText(lines, mouseX, Math.max(0, mouseY - 12));
        } finally {
            MixinStatus.skipFontRendererDisguiseReplace = false;
        }
    }

    private List<String> buildKillCounterTooltipLines() {
        int kills = previewKillCountForTooltip();
        ArrayList<String> lines = new ArrayList<String>(7);
        lines.add(EnumChatFormatting.AQUA + "Kill Counter");
        lines.add(EnumChatFormatting.GRAY + "Shows a [kills] prefix before the disguised name in Blitz SG chat and TAB.");
        lines.add(EnumChatFormatting.GRAY + "Preview: " + EnumChatFormatting.RESET
                + LocalDisguise.formatBsg2KillBracketForDisplay(kills) + " "
                + LocalDisguise.buildDisguiseGuiTooltipRankedName());
        appendKillCounterModeTooltipLines(lines, true);
        return lines;
    }

    private List<String> buildTournamentKillCounterTooltipLines() {
        int currentKills = previewKillCountForTooltip();
        int kills = currentKills == Integer.MAX_VALUE ? Integer.MAX_VALUE : currentKills + 1;
        ArrayList<String> lines = new ArrayList<String>(7);
        lines.add(EnumChatFormatting.AQUA + "Tournament Kill Counter");
        lines.add(EnumChatFormatting.GRAY + "Adds (Kill #n) after the disguised name in Blitz SG kill messages.");
        lines.add(EnumChatFormatting.GRAY + "Preview: " + EnumChatFormatting.RESET
                + LocalDisguise.buildDisguiseGuiTooltipRankedName()
                + EnumChatFormatting.GOLD + " (Kill #" + formatGroupedKillCount(kills) + ")");
        appendKillCounterModeTooltipLines(lines, false);
        return lines;
    }

    private static void appendKillCounterModeTooltipLines(List<String> lines, boolean chatKillCounter) {
        lines.add(EnumChatFormatting.GRAY + "Modes:");
        lines.add(EnumChatFormatting.GREEN + "Force ON" + EnumChatFormatting.GRAY + " - Always show");
        lines.add(EnumChatFormatting.RED + "Force OFF" + EnumChatFormatting.GRAY + " - Never show");
        String targetLabel = LocalDisguise.getDisguiseTargetPossessiveLabel();
        if (LocalDisguise.isDisguiseTargetKillPreferenceKnown()) {
            boolean enabled = chatKillCounter
                    ? LocalDisguise.isDisguiseTargetChatKillCounterEnabled()
                    : LocalDisguise.isDisguiseTargetTournamentKillCounterEnabled();
            String state = enabled
                    ? EnumChatFormatting.GREEN + "(ON)"
                    : EnumChatFormatting.RED + "(OFF)";
            lines.add(EnumChatFormatting.YELLOW + targetLabel + " " + state + EnumChatFormatting.GRAY
                    + " - Match target's Hypixel setting");
        } else {
            lines.add(EnumChatFormatting.YELLOW + targetLabel + EnumChatFormatting.GRAY
                    + " - Match target's Hypixel setting");
        }
    }

    private static int previewKillCountForTooltip() {
        Integer targetKills = LocalDisguise.getDisguiseGuiPreviewKillCount();
        return targetKills != null ? Math.max(0, targetKills) : 50000;
    }

    private static String formatGroupedKillCount(int value) {
        return String.format(Locale.US, "%,d", Math.max(0, value));
    }

    private static boolean isHovering(GuiButton btn, int mx, int my) {
        return btn != null && btn.visible
                && mx >= btn.xPosition && mx < btn.xPosition + btn.width
                && my >= btn.yPosition && my < btn.yPosition + btn.height;
    }

    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: {
                this.mc.displayGuiScreen(this.parent);
                break;
            }
            case 1: {
                LocalDisguise.cycleTournamentKillCounterMode();
                this.refreshButtonStates();
                break;
            }
            case 2: {
                if (!LocalDisguise.isEnabled()) {
                    LocalDisguise.prepareSelfCapeCatalogForPicker();
                }
                PlayerStandaloneCape.ensureCatalogLoaded();
                if (LocalDisguise.isEnabled()) {
                    this.mc.displayGuiScreen(new MinecraftCapeSelectorGUI(this, MinecraftCapeSelectorGUI.Mode.DISGUISE));
                } else {
                    this.mc.displayGuiScreen(new MinecraftCapeSelectorGUI(this, MinecraftCapeSelectorGUI.Mode.SELF));
                }
                this.refreshButtonStates();
                break;
            }
            case 3: {
                this.openTargetInput();
                break;
            }
            case 4: {
                DisguiseCommand.disableActiveDisguiseWithMessage();
                this.refreshButtonStates();
                break;
            }
            case 5: {
                LocalDisguise.cycleKillCounterMode();
                this.refreshButtonStates();
                break;
            }
            case 6: {
                LocalDisguise.toggleBlitzKitSelectorOverride();
                this.refreshButtonStates();
                break;
            }
            case 7: {
                this.mc.displayGuiScreen(new HypixelPrefixSettingsGUI(this));
                break;
            }
            case 13: {
                LocalDisguise.cycleRenamedItemsMode();
                this.refreshButtonStates();
                break;
            }
            case 10: {
                LocalDisguise.toggleDisguiseCustomKillFeature();
                this.refreshButtonStates();
                break;
            }
            case 9: {
                if (ConfigManager.isDisguiseCustomKillCountEnabled()) {
                    String cur = String.valueOf(ConfigManager.getDisguiseCustomKillCount());
                    this.mc.displayGuiScreen(new DisguiseCustomKillsInputGUI(this, cur));
                }
                break;
            }
            case 11: {
                ConfigManager.setDisguiseRestoreOnReconnect(!ConfigManager.isDisguiseRestoreOnReconnect());
                this.refreshButtonStates();
                break;
            }
            case 14: {
                if (GomodFixes.isWeaveRuntime()) {
                    ConfigManager.setDisguiseHideLunarCosmeticsOnWeave(
                            !ConfigManager.isDisguiseHideLunarCosmeticsOnWeave());
                    this.refreshButtonStates();
                }
                break;
            }
            case 12: {
                this.mc.displayGuiScreen(new NickDisguiseSetupGUI(this));
                break;
            }
            case 8: {
                String cur = LocalMobDisguise.getPreferredMobInputValue();
                this.mc.displayGuiScreen(new MobLookEntityInputGUI(this, cur));
                break;
            }
            case 15: {
                if (LocalMobDisguise.isActive()) {
                    LocalMobDisguise.clearCustomAndSetPreset(LocalMobDisguise.Kind.OFF);
                    this.refreshButtonStates();
                    this.applyScrollLayout(this.scroll.getOffset());
                }
                break;
            }
        }
    }

    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (!isPublicCapeOnly() && (keyCode == 28 || keyCode == 156)) {
            this.openTargetInput();
            return;
        }
        try {
            super.keyTyped(typedChar, keyCode);
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        if (!isPublicCapeOnly()) {
            this.scroll.handleMouseWheel();
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (!isPublicCapeOnly() && this.scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        if (!isPublicCapeOnly()) {
            this.scroll.mouseReleased(state);
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    private void refreshButtonStates() {
        boolean loading = DisguiseCommand.isLoading();
        if (this.targetButton != null) {
            this.targetButton.displayString = loading ? this.getLoadingText() : this.getTargetButtonText();
            boolean bl = this.targetButton.enabled = !loading;
        }
        if (this.disableButton != null) {
            this.disableButton.enabled = LocalDisguise.isEnabled();
        }
        boolean realPlayerDisguise = hasRealPlayerDisguise();
        boolean nickDisguiseHud = LocalDisguise.isEnabled() && LocalDisguise.isNickDisguiseActive();
        boolean canUsePlayerDisguiseHudOptions = !loading && (realPlayerDisguise || nickDisguiseHud);
        if (this.killCounterButton != null) {
            this.killCounterButton.displayString = loading ? this.getLoadingText() : this.getKillCounterText();
            this.killCounterButton.enabled = canUsePlayerDisguiseHudOptions;
            this.fitStatusButtonWidth(this.killCounterButton);
        }
        if (this.tournamentCounterButton != null) {
            this.tournamentCounterButton.displayString = loading ? this.getLoadingText() : this.getTournamentCounterText();
            this.tournamentCounterButton.enabled = canUsePlayerDisguiseHudOptions;
            this.fitStatusButtonWidth(this.tournamentCounterButton);
        }
        if (this.customKillToggleButton != null) {
            this.customKillToggleButton.displayString = LocalDisguise.getDisguiseCustomKillFeatureToggleLabel();
            this.customKillToggleButton.enabled = canUsePlayerDisguiseHudOptions;
        }
        if (this.customKillValueButton != null) {
            this.customKillValueButton.displayString = LocalDisguise.getDisguiseCustomKillValueButtonLabel();
            this.customKillValueButton.enabled = canUsePlayerDisguiseHudOptions && ConfigManager.isDisguiseCustomKillCountEnabled();
        }
        if (this.blitzKitSelectorButton != null) {
            this.blitzKitSelectorButton.displayString = this.getBlitzKitSelectorText();
            this.blitzKitSelectorButton.enabled = !loading && realPlayerDisguise;
        }
        if (this.maskRenamedItemsButton != null) {
            this.maskRenamedItemsButton.displayString = this.getRenamedItemsText();
            this.maskRenamedItemsButton.enabled = !loading && LocalDisguise.isEnabled();
        }
        if (this.hypixelPrefixButton != null) {
            this.hypixelPrefixButton.displayString = this.getHypixelPrefixText();
            this.hypixelPrefixButton.enabled = canUsePlayerDisguiseHudOptions;
        }
        if (this.capeButton != null) {
            this.capeButton.displayString = loading ? this.getCapeLoadingText() : this.getCapeButtonText();
            boolean disguised = LocalDisguise.isEnabled();
            this.capeButton.enabled = !loading && (!disguised || LocalDisguise.getCapeOptionCount() > 0);
        }
        if (this.mobLookButton != null) {
            this.mobLookButton.displayString = LocalMobDisguise.getGuiButtonLabel();
            this.mobLookButton.enabled = true;
        }
        if (this.disableMobButton != null) {
            this.disableMobButton.enabled = LocalMobDisguise.isActive();
        }
        if (this.reconnectRestoreButton != null) {
            this.reconnectRestoreButton.displayString = this.getReconnectRestoreLabel();
            this.reconnectRestoreButton.enabled = true;
        }
        if (this.lunarCosmeticsButton != null) {
            this.lunarCosmeticsButton.displayString = this.getLunarCosmeticsLabel();
            this.lunarCosmeticsButton.enabled = !loading && GomodFixes.isWeaveRuntime();
        }
        if (this.nickDisguiseButton != null) {
            this.nickDisguiseButton.displayString = loading ? this.getLoadingText() : this.getNickDisguiseButtonText();
            this.nickDisguiseButton.enabled = !loading;
        }
    }

    private String getReconnectRestoreLabel() {
        return ConfigManager.isDisguiseRestoreOnReconnect()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private String getLunarCosmeticsLabel() {
        return ConfigManager.isDisguiseHideLunarCosmeticsOnWeave()
                ? EnumChatFormatting.RED + "Hidden"
                : EnumChatFormatting.GREEN + "Shown";
    }

    private String getLoadingText() {
        return EnumChatFormatting.YELLOW + "Loading..";
    }

    /** Player disguise row: real-Mojang target only — nick disguise uses {@link #getNickDisguiseButtonText()}. */
    private String getTargetButtonText() {
        if (LocalDisguise.isNickDisguiseActive()) {
            return EnumChatFormatting.AQUA + "Choose player";
        }
        String target = LocalDisguise.isEnabled() && LocalDisguise.getTargetName() != null && !LocalDisguise.getTargetName().trim().isEmpty()
                ? LocalDisguise.getTargetName().trim()
                : "Choose player";
        return EnumChatFormatting.AQUA + target;
    }

    private String getNickDisguiseButtonText() {
        if (LocalDisguise.isNickDisguiseActive()) {
            String n = LocalDisguise.getTargetName();
            if (n != null && !n.trim().isEmpty()) {
                return EnumChatFormatting.AQUA + n.trim();
            }
        }
        return EnumChatFormatting.AQUA + "Choose nick";
    }

    private String getKillCounterText() {
        return LocalDisguise.getKillCounterModeLabel();
    }

    private String getTournamentCounterText() {
        return LocalDisguise.getTournamentKillCounterModeLabel();
    }

    /** Keeps the target name plus the optional (ON)/(OFF) suffix inside the value button. */
    private void fitStatusButtonWidth(GuiButton button) {
        if (button == null || this.fontRendererObj == null) {
            return;
        }
        int needed = this.fontRendererObj.getStringWidth(button.displayString) + 14;
        int available = Math.max(VALUE_BUTTON_WIDTH, this.width - button.xPosition - 8);
        button.width = Math.max(VALUE_BUTTON_WIDTH, Math.min(needed, available));
    }

    private String getCapeButtonText() {
        if (!LocalDisguise.isEnabled()) {
            return PlayerStandaloneCape.getButtonLabel();
        }
        return this.getCapeText();
    }

    private String getCapeText() {
        String label = LocalDisguise.getSelectedCapeLabel();
        EnumChatFormatting color = label != null && label.startsWith("Mojang") ? EnumChatFormatting.WHITE : EnumChatFormatting.AQUA;
        return color + label;
    }

    private String getCapeLoadingText() {
        if (!LocalDisguise.isEnabled()) {
            return EnumChatFormatting.YELLOW + "Loading..";
        }
        String label = LocalDisguise.getSelectedCapeLabel();
        EnumChatFormatting color = label != null && label.startsWith("Mojang") ? EnumChatFormatting.WHITE : EnumChatFormatting.AQUA;
        return color + label + "...";
    }

    private String getBlitzKitSelectorText() {
        return LocalDisguise.getBlitzKitSelectorOverrideLabel();
    }

    private String getRenamedItemsText() {
        return LocalDisguise.getRenamedItemsModeLabel();
    }

    private String getHypixelPrefixText() {
        return EnumChatFormatting.GREEN + "Edit prefix…";
    }

    /** Hypixel / Blitz-related toggles apply to real player disguise, not offline nick disguise. */
    private static boolean hasRealPlayerDisguise() {
        return LocalDisguise.isEnabled() && !LocalDisguise.isNickDisguiseActive();
    }

    private void openTargetInput() {
        String initialValue = "";
        if (!LocalDisguise.isNickDisguiseActive() && LocalDisguise.isEnabled() && LocalDisguise.getTargetName() != null) {
            initialValue = LocalDisguise.getTargetName();
        }
        this.mc.displayGuiScreen((GuiScreen)new DisguiseNameInputGUI(this, initialValue));
    }

    public boolean doesGuiPauseGame() {
        return false;
    }

    /**
     * Custom kill preview {@code [kills]} must not go through {@link LocalDisguise#replaceRenderedText} in
     * {@link me.napim.gomod.mixins.FontRendererMixin} (labels contain {@code [} and would be rewritten when custom
     * kills are enabled). Centered in the button; 100k+ §l brackets get a small left nudge only.
     */
    private static final class DisguiseCustomKillValueButton extends GuiButton {

        DisguiseCustomKillValueButton(int id, int x, int y, int w, int h) {
            super(id, x, y, w, h, "");
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) {
                return;
            }
            String label = this.displayString;
            this.displayString = "";

            int textColor;
            if (!this.enabled) {
                textColor = 10526880;
            } else if (this.hovered) {
                textColor = 16777120;
            } else {
                textColor = 14737632;
            }

            MixinStatus.skipFontRendererDisguiseReplace = true;
            try {
                super.drawButton(mc, mouseX, mouseY);
                if (label != null && !label.isEmpty()) {
                    FontRenderer fr = mc.fontRendererObj;
                    int ty = this.yPosition + (this.height - 8) / 2;
                    int tx = GuiHeader.getButtonCenteredTextX(fr, this.xPosition, this.width, label);
                    if (ConfigManager.getDisguiseCustomKillCount() >= 100000) {
                        tx += CUSTOM_KILL_BOLD_CENTER_NUDGE_X;
                    }
                    fr.drawStringWithShadow(label, tx, ty, textColor);
                }
            } finally {
                MixinStatus.skipFontRendererDisguiseReplace = false;
                this.displayString = label;
            }
        }
    }
}
