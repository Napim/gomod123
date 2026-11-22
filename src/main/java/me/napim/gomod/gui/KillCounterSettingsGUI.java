/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.Gui
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.util.EnumChatFormatting
 *  org.lwjgl.input.Mouse
 */
package me.napim.gomod.gui;

import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.TabStatsBlitzMetrics;
import me.napim.gomod.gui.GomodGUI;
import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Arrays;
import java.util.List;

public class KillCounterSettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    /** Pool for {@link #pickRandomKillEffectIcon()} (stable preview until mode changes / re-roll). */
    private static final String[] PREVIEW_KILL_EFFECT_KEYS = new String[]{
            "SPEED", "RESISTANCE", "REGENERATION", "FLAME", "GRAVEDIGGER", "LEVEL_UP", "RANDOM"};
    private static final int SCROLL_BOTTOM_RESERVE = 52; // matches GuiHeader bottom bar (30 + 20 + 2)
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 30;
    /** Default settings-button width; column X matches {@link MobHealthDisplayGUI} / {@link InventoryHUDGUI} ({@code width/2 + 80}). */
    private static final int TAB_STATS_BTN_W = 120;
    /** Gap from a section label to the first line of content beneath it (Preview row, kill-effect grid). */
    private static final int LABEL_TO_CONTENT_GAP = 14;
    private static final int CONTENT_LINE_STEP = 12;
    private static final int LABEL_CONTENT_OFFSET = LABEL_TO_CONTENT_GAP + CONTENT_LINE_STEP;
    private static final int PREVIEW_PANEL_PAD = 10;
    private static final int PREVIEW_PANEL_GRID_W = 240;
    /** Inner horizontal pad so the TAB preview row does not sit flush against the box edges. */
    private static final int PREVIEW_ROW_INNER_PAD = 16;
    /** Gap between the preview panel bottom edge and the first settings row. */
    private static final int PREVIEW_TO_SETTINGS_GAP = 16;
    /** Fixed top of the preview panel (not affected by scroll). */
    private static final int PREVIEW_PANEL_TOP = 44;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private static final int SCROLL_STEP = 15;
    // Smooth "rolling" scroll (ported from MWE ConfigGuiScreen approach)
    private int scrollDirection = 0;
    private int amountToScroll = 0;
    private long lastScrollTime = 0L;
    private static final int SCROLL_IMPULSE_PX = 18;
    private static final int SCROLL_MAX_QUEUE_PX = 72;
    private static final int SCROLL_STEP_PX = 1;
    private static final int SCROLL_ROW_H = 20;
    private boolean draggingScrollBar = false;
    private int scrollGrabbedAtY = 0;
    private int SCROLL_BAR_LEFT = 0, SCROLL_BAR_TOP = 0, SCROLL_BAR_RIGHT = 0, SCROLL_BAR_BOTTOM = 0;
    private GuiButton enableButton;
    private GuiButton colorButton;
    private GuiButton statsKdButton;
    private GuiButton statsWlButton;
    private GuiButton statsWButton;
    private GuiButton autoKeAllButton;
    private GuiButton killEffectTabButton;
    private GuiButton killEffectTagsButton;
    private GuiButton guildTagButton;
    private GuiButton teammateKitButton;
    private GuiButton enemyKitButton;
    private GuiButton tabKillersToggleButton;
    private GuiButton tabKillersKeyButton;
    private GuiButton betterPlayerFormatTabButton;
    private GuiButton betterPlayerFormatTagsButton;
    private GuiButton blitzStarTabButton;
    private GuiButton tabListSortButton;
    private GuiButton tabListDistrictSortButton;
    private GuiButton backButton;
    private boolean enabled = KillCounter.isEnabled();
    private boolean showStatsKd = KillCounter.isStatsShowKd();
    private boolean showStatsWl = KillCounter.isStatsShowWl();
    private boolean showStatsW = KillCounter.isStatsShowW();
    private KillCounter.AutoScanMode autoScanMode = KillCounter.getAutoScanMode();
    private boolean showGuildTag = KillCounter.isShowGuildTag();
    private boolean showTeammateKits = KillCounter.isShowTeammateKits();
    private boolean showEnemyKits = KillCounter.isShowEnemyKits();
    private boolean showTabKillers = ConfigManager.isKillCounterTabKillersEnabled();
    private boolean showBlitzStarTabIcon = ConfigManager.isKillCounterBlitzStarTabIconEnabled();
    private KillCounter.TabListSortMode tabListSortMode = KillCounter.getTabListSortMode();
    private KillCounter.TabListSortGameScope tabListSortGameScope = KillCounter.getTabListSortGameScope();
    private String playerName = "PlayerName";
    private EnumChatFormatting randomKillEffectColor = EnumChatFormatting.RED;
    /** Random API effect for ICON-mode preview (matches {@link #pickRandomKillEffectIcon()} pool). */
    private String randomPreviewKillEffect = "REGENERATION";
    /** Random 1–9 for kill-count brackets in the TAB preview row (re-rolled when TAB stats is re-enabled). */
    private int previewKillCount = 2;
    /** Blitz star texture slug for preview; re-rolled when "Show blitz star user" is turned on. */
    private String previewBlitzStarSlug = "vaulthunter";
    private boolean awaitingTabKillersKey = false;

    /** Left column for setting names (same as {@link MobHealthDisplayGUI}: {@code width/2 - 200}). */
    private int tabStatsLabelX() {
        return this.width / 2 - 200;
    }

    /** Right column for controls (same as {@link MobHealthDisplayGUI}: {@code width/2 + 80}). */
    private int tabStatsButtonColumnLeft() {
        return this.width / 2 + 80;
    }

    public void initGui() {
        super.initGui();
        this.tabListSortMode = KillCounter.getTabListSortMode();
        this.tabListSortGameScope = KillCounter.getTabListSortGameScope();
        if (KillCounter.getKillEffectTabDisplayMode().colorsTab()) {
            this.pickRandomKillEffectColor();
        } else if (KillCounter.getKillEffectTabDisplayMode() == KillCounter.KillEffectTabDisplayMode.ICON) {
            this.pickRandomKillEffectIcon();
        }
        if (ConfigManager.isKillCounterBlitzStarTabIconEnabled()) {
            this.pickRandomBlitzStarPreviewSlug();
        }
        try {
            if (Minecraft.getMinecraft().thePlayer != null && Minecraft.getMinecraft().thePlayer.getName() != null) {
                this.playerName = Minecraft.getMinecraft().thePlayer.getName();
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        this.previewKillCount = 1 + (int) (Math.random() * 9.0);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            GomodClasspathTextures.preloadTabQoLPreviewIcons(mc);
        }
        this.showStatsKd = KillCounter.isStatsShowKd();
        this.showStatsWl = KillCounter.isStatsShowWl();
        this.showStatsW = KillCounter.isStatsShowW();
        int rightColumnX = this.tabStatsButtonColumnLeft();
        int settingsTop = this.settingsContentTop();
        int buttonWidth = TAB_STATS_BTN_W;
        this.enableButton = new GuiButton(0, rightColumnX, settingsTop, buttonWidth, 20, this.killCounterButtonLabel());
        int sortBtnW = Math.max(buttonWidth, this.fontRendererObj.getStringWidth(
                EnumChatFormatting.getTextWithoutFormattingCodes(this.tabListSortModeButtonLabel())) + 24);
        int sortBtnX = rightColumnX + (buttonWidth - sortBtnW) / 2;
        this.tabListSortButton = new GuiButton(9, sortBtnX, settingsTop + 30, sortBtnW, 20, this.tabListSortModeButtonLabel());
        this.tabListDistrictSortButton = new GuiButton(11, rightColumnX, settingsTop + 60, buttonWidth, 20,
                this.tabListDistrictSortButtonLabel());
        this.statsKdButton = new GuiButton(30, rightColumnX, settingsTop + 90, 40, 20,
                this.statsPartButtonLabel(this.showStatsKd, "KD"));
        this.statsWlButton = new GuiButton(31, rightColumnX, settingsTop + 90, 40, 20,
                this.statsPartButtonLabel(this.showStatsWl, "WL"));
        this.statsWButton = new GuiButton(32, rightColumnX, settingsTop + 90, 40, 20,
                this.statsPartButtonLabel(this.showStatsW, "W"));
        this.layoutStatsPartButtons(rightColumnX, settingsTop + 90, buttonWidth);
        int killEffectRowY = settingsTop + 150;
        this.colorButton = new GuiButton(2, rightColumnX, killEffectRowY, buttonWidth, 20,
                killEffectTabModeButtonLabel());
        this.killEffectTabButton = new GuiButton(34, rightColumnX, killEffectRowY, 40, 20,
                killEffectTargetButtonLabel(true));
        this.killEffectTagsButton = new GuiButton(35, rightColumnX, killEffectRowY, 40, 20,
                killEffectTargetButtonLabel(false));
        this.layoutKillEffectDisplayButtons(rightColumnX, killEffectRowY, buttonWidth);
        this.blitzStarTabButton = new GuiButton(12, rightColumnX, killEffectRowY + 30, buttonWidth, 20,
                this.blitzStarTabIconButtonLabel());
        String autoScanLabel = this.getAutoScanModeLabel();
        int autoScanWidth = Math.max(buttonWidth, this.fontRendererObj.getStringWidth(autoScanLabel) + 20);
        int autoScanX = rightColumnX + (buttonWidth - autoScanWidth) / 2;
        this.autoKeAllButton = new GuiButton(4, autoScanX, killEffectRowY + 60, autoScanWidth, 20, autoScanLabel);
        this.guildTagButton = new GuiButton(5, rightColumnX, killEffectRowY + 90, buttonWidth, 20, this.showGuildTag ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        this.teammateKitButton = new GuiButton(6, rightColumnX, killEffectRowY + 120, buttonWidth, 20, this.showTeammateKits ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        this.enemyKitButton = new GuiButton(7, rightColumnX, killEffectRowY + 150, buttonWidth, 20, this.showEnemyKits ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        this.tabKillersToggleButton = new GuiButton(13, rightColumnX, killEffectRowY + 180, 40, 20, this.tabKillersButtonLabel());
        this.tabKillersKeyButton = new GuiButton(14, rightColumnX, killEffectRowY + 180, 40, 20, this.tabKillersKeyButtonLabel());
        this.layoutTabKillersButtons(rightColumnX, killEffectRowY + 180, buttonWidth);
        this.betterPlayerFormatTabButton = new GuiButton(33, rightColumnX, settingsTop + 120, 40, 20,
                betterPlayerFormatTargetButtonLabel(true));
        this.betterPlayerFormatTagsButton = new GuiButton(36, rightColumnX, settingsTop + 120, 40, 20,
                betterPlayerFormatTargetButtonLabel(false));
        this.layoutBetterPlayerFormatButtons(rightColumnX, settingsTop + 120, buttonWidth);
        int backW = 140;
        this.backButton = new GuiButton(99, this.width / 2 - backW / 2, GuiHeader.backButtonY(this.height, 20), backW, 20, EnumChatFormatting.GRAY + "Back to Menu");
        this.buttonList.add(this.enableButton);
        this.buttonList.add(this.tabListSortButton);
        this.buttonList.add(this.tabListDistrictSortButton);
        this.buttonList.add(this.statsKdButton);
        this.buttonList.add(this.statsWlButton);
        this.buttonList.add(this.statsWButton);
        this.buttonList.add(this.colorButton);
        this.buttonList.add(this.killEffectTabButton);
        this.buttonList.add(this.killEffectTagsButton);
        this.buttonList.add(this.blitzStarTabButton);
        this.buttonList.add(this.autoKeAllButton);
        this.buttonList.add(this.guildTagButton);
        this.buttonList.add(this.teammateKitButton);
        this.buttonList.add(this.enemyKitButton);
        this.buttonList.add(this.tabKillersToggleButton);
        this.buttonList.add(this.tabKillersKeyButton);
        this.buttonList.add(this.betterPlayerFormatTabButton);
        this.buttonList.add(this.betterPlayerFormatTagsButton);
        this.buttonList.add(this.backButton);
        this.refreshKillEffectDisplayButtonState();
        this.scrollOffset = 0;
        this.computeMaxScroll();
        scrollToHighlightedButtonIfNeeded();
    }

    private void scrollToHighlightedButtonIfNeeded() {
        if (!GomodSearchHighlight.matchesGui(KillCounterSettingsGUI.class)) {
            return;
        }
        int pendingId = GomodSearchHighlight.getButtonId();
        if (pendingId < 0) {
            return;
        }
        for (GuiButton b : this.buttonList) {
            if (b == null || b.id != pendingId) {
                continue;
            }
            int settingsTop = this.settingsContentTop();
            int visibleBottom = this.height - SCROLL_BOTTOM_RESERVE;
            int vh = Math.max(1, visibleBottom - settingsTop);
            int target = b.yPosition - settingsTop - vh / 2 + 10;
            this.scrollOffset = Math.max(0, Math.min(this.maxScroll, target));
            break;
        }
    }

    private String tabListSortModeButtonLabel() {
        switch (this.tabListSortMode) {
            case KILLS_THEN_WLR:
                return EnumChatFormatting.DARK_RED + "Kill c." + EnumChatFormatting.GRAY + ">" + EnumChatFormatting.GOLD + "W/L";
            case WLR_THEN_KILLS:
                return EnumChatFormatting.GOLD + "W/L" + EnumChatFormatting.GRAY + ">" + EnumChatFormatting.DARK_RED + "Kill c.";
            case KILLS_THEN_WINS:
                return EnumChatFormatting.DARK_RED + "Kill c." + EnumChatFormatting.GRAY + ">" + EnumChatFormatting.GOLD + "Wins";
            case WINS_THEN_KILLS:
                return EnumChatFormatting.GOLD + "Wins" + EnumChatFormatting.GRAY + ">" + EnumChatFormatting.DARK_RED + "Kill c.";
            case KILLS_THEN_OVERALL_KILLS:
                return EnumChatFormatting.DARK_RED + "Kill c." + EnumChatFormatting.GRAY + ">" + EnumChatFormatting.GOLD + "Overall kills";
            case DISTRICT:
                return EnumChatFormatting.GREEN + "District [DIST n]";
            case OFF:
            default:
                return EnumChatFormatting.RED + "Disabled";
        }
    }

    private String tabListDistrictSortButtonLabel() {
        boolean allowed = this.tabListSortGameScope == KillCounter.TabListSortGameScope.SOLO;
        if (!allowed) {
            return EnumChatFormatting.DARK_GRAY + "Disabled in teams";
        }
        boolean on = KillCounter.isDistrictTabListSortEnabled();
        return on ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
    }

    /** Bottom Y of the fixed preview panel (height does not depend on panel width). */
    private int computePreviewPanelBottom() {
        int panelTop = PREVIEW_PANEL_TOP;
        int previewLabelY = panelTop + PREVIEW_PANEL_PAD;
        int previewRowTop = previewLabelY + LABEL_CONTENT_OFFSET;
        int previewRowBottom = previewRowTop + 9;
        int legendLabelY = previewRowBottom + LABEL_TO_CONTENT_GAP;
        int killEffectGridTop = legendLabelY + LABEL_CONTENT_OFFSET;
        int killEffectGridBottom = killEffectGridTop + 2 * CONTENT_LINE_STEP + this.fontRendererObj.FONT_HEIGHT;
        return killEffectGridBottom + PREVIEW_PANEL_PAD;
    }

    private int settingsContentTop() {
        return this.computePreviewPanelBottom() + PREVIEW_TO_SETTINGS_GAP;
    }

    private void computeMaxScroll() {
        int contentTop = this.settingsContentTop();
        int contentBottom = contentTop + 330 + 20;
        int visibleBottom = this.height - SCROLL_BOTTOM_RESERVE;
        int visibleHeight = Math.max(1, visibleBottom - contentTop);
        int totalContentHeight = Math.max(1, contentBottom - contentTop);
        this.maxScroll = Math.max(0, totalContentHeight - visibleHeight);
        if (this.scrollOffset > this.maxScroll) {
            this.scrollOffset = this.maxScroll;
        }
    }

    private void scrollBy(int amount) {
        // Same sign convention as MWE: scrollOffset = scrollOffset - amount
        this.scrollOffset = this.scrollOffset - amount;
        if (this.scrollOffset > this.maxScroll) {
            this.scrollOffset = this.maxScroll;
            this.amountToScroll = 0;
        }
        if (this.scrollOffset < 0) {
            this.scrollOffset = 0;
            this.amountToScroll = 0;
        }
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.refreshButtonStates();
        GuiHeader.drawColoredBoldTitleCentered(this.fontRendererObj, "TAB QoL Settings",
                EnumChatFormatting.DARK_RED, this.width, GuiHeader.STANDARD_TITLE_Y);

        // Smooth scrolling updated on every frame instead of per-wheel-tick jumps
        if (this.amountToScroll > 0 && this.maxScroll > 0) {
            long time = System.currentTimeMillis();
            if (time - this.lastScrollTime > 1) {
                int step = Math.min(SCROLL_STEP_PX, this.amountToScroll);
                this.scrollBy(this.scrollDirection * step);
                this.amountToScroll -= step;
                this.lastScrollTime = time;
            }
        }

        String previewText = this.buildPreviewText();
        int previewRowWidth = this.measurePreviewTabRowTotalWidth(previewText);
        int panelInnerW = Math.max(PREVIEW_PANEL_GRID_W, previewRowWidth + PREVIEW_ROW_INNER_PAD * 2);
        int maxPanelInnerW = Math.max(PREVIEW_PANEL_GRID_W, this.width - PREVIEW_PANEL_PAD * 4);
        panelInnerW = Math.min(panelInnerW, maxPanelInnerW);
        int panelLeft = this.width / 2 - panelInnerW / 2 - PREVIEW_PANEL_PAD;
        int panelRight = this.width / 2 + panelInnerW / 2 + PREVIEW_PANEL_PAD;
        int panelTop = PREVIEW_PANEL_TOP;
        int previewLabelY = panelTop + PREVIEW_PANEL_PAD;
        int previewRowTop = previewLabelY + LABEL_CONTENT_OFFSET;
        int previewRowBottom = previewRowTop + 9;
        String legendLabel = "Kill effect display:";
        int legendLabelY = previewRowBottom + LABEL_TO_CONTENT_GAP;
        int killEffectGridTop = legendLabelY + LABEL_CONTENT_OFFSET;
        int killEffectGridBottom = killEffectGridTop + 2 * CONTENT_LINE_STEP + this.fontRendererObj.FONT_HEIGHT;
        int panelBottom = killEffectGridBottom + PREVIEW_PANEL_PAD;
        int rightColumnX = this.tabStatsButtonColumnLeft();
        int labelX = this.tabStatsLabelX();
        int settingsTop = this.settingsContentTop();
        int visibleTop = settingsTop;
        int visibleBottom = this.height - SCROLL_BOTTOM_RESERVE;
        int buttonWidth = TAB_STATS_BTN_W;
        int rowStartY = settingsTop - this.scrollOffset;
        this.enableButton.yPosition = rowStartY;
        this.enableButton.xPosition = rightColumnX;
        if (this.tabListDistrictSortButton != null) {
            this.tabListDistrictSortButton.xPosition = rightColumnX;
        }
        if (this.blitzStarTabButton != null) {
            this.blitzStarTabButton.xPosition = rightColumnX;
        }
        if (this.guildTagButton != null) {
            this.guildTagButton.xPosition = rightColumnX;
        }
        if (this.teammateKitButton != null) {
            this.teammateKitButton.xPosition = rightColumnX;
        }
        if (this.enemyKitButton != null) {
            this.enemyKitButton.xPosition = rightColumnX;
        }
        if (this.tabListSortButton != null) {
            this.tabListSortButton.yPosition = rowStartY + 30;
        }
        if (this.tabListDistrictSortButton != null) {
            this.tabListDistrictSortButton.yPosition = rowStartY + 60;
        }
        this.layoutStatsPartButtons(rightColumnX, rowStartY + 90, buttonWidth);
        int killEffectRowY = rowStartY + 150;
        this.layoutKillEffectDisplayButtons(rightColumnX, killEffectRowY, buttonWidth);
        if (this.blitzStarTabButton != null) {
            this.blitzStarTabButton.yPosition = killEffectRowY + 30;
        }
        this.autoKeAllButton.yPosition = killEffectRowY + 60;
        this.guildTagButton.yPosition = killEffectRowY + 90;
        this.teammateKitButton.yPosition = killEffectRowY + 120;
        this.enemyKitButton.yPosition = killEffectRowY + 150;
        this.layoutTabKillersButtons(rightColumnX, killEffectRowY + 180, buttonWidth);
        this.layoutBetterPlayerFormatButtons(rightColumnX, rowStartY + 120, buttonWidth);
        updateRowButtonVisibility(this.enableButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.tabListSortButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.tabListDistrictSortButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.statsKdButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.statsWlButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.statsWButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.colorButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.blitzStarTabButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.autoKeAllButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.guildTagButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.teammateKitButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.enemyKitButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.tabKillersToggleButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.tabKillersKeyButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.betterPlayerFormatTabButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.betterPlayerFormatTagsButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.killEffectTabButton, visibleTop, visibleBottom);
        updateRowButtonVisibility(this.killEffectTagsButton, visibleTop, visibleBottom);
        boolean showKillEffectTargets = KillCounter.getKillEffectTabDisplayMode().colorsTab()
                || KillCounter.getKillEffectTabDisplayMode().colorsNametags();
        this.killEffectTabButton.visible &= showKillEffectTargets;
        this.killEffectTagsButton.visible &= showKillEffectTargets;
        this.computeMaxScroll();
        if (this.backButton != null) {
            this.backButton.yPosition = GuiHeader.backButtonY(this.height, 20);
            this.backButton.visible = true;
        }
        SocialDraw.enableScissor(labelX - 4, visibleTop, rightColumnX + buttonWidth + 8, visibleBottom);
        try {
            drawScrollableSettingLabels(labelX, rowStartY, killEffectRowY, visibleTop, visibleBottom);
            drawScrollableSettingButtons(mouseX, mouseY, visibleTop, visibleBottom);
        } finally {
            SocialDraw.disableScissor();
        }
        if (this.maxScroll > 0) {
            int trackX = this.width - 10;
            int trackWidth = 4;
            int contentTop = settingsTop;
            int contentBottom = settingsTop + 330 + 20;
            int trackTop = Math.max(visibleTop, 30);
            int trackBottom = Math.max(trackTop + 1, visibleBottom);
            int trackHeight = trackBottom - trackTop;
            int totalContentHeight = Math.max(1, contentBottom - contentTop);
            int visibleHeight = Math.max(1, visibleBottom - visibleTop);
            int thumbHeight = Math.max(20, (int)((float)trackHeight * ((float)visibleHeight / (float)totalContentHeight)));
            thumbHeight = Math.min(thumbHeight, trackHeight);
            int maxThumbTravel = trackHeight - thumbHeight;
            int thumbY = trackTop + (this.maxScroll == 0 ? 0 : (int)((float)maxThumbTravel * ((float)this.scrollOffset / (float)this.maxScroll)));
            Gui.drawRect((int)trackX, (int)trackTop, (int)(trackX + trackWidth), (int)trackBottom, (int)0x60000000);
            Gui.drawRect((int)trackX, (int)thumbY, (int)(trackX + trackWidth), (int)(thumbY + thumbHeight), (int)-1862270977);

            // Save thumb rect for click+drag
            this.SCROLL_BAR_LEFT = trackX;
            this.SCROLL_BAR_TOP = thumbY;
            this.SCROLL_BAR_RIGHT = trackX + trackWidth;
            this.SCROLL_BAR_BOTTOM = thumbY + thumbHeight;

            // Dragging: map mouse Y to scrollOffset (same approach as MWE)
            if (this.draggingScrollBar) {
                int relativeMouseY = mouseY - trackTop - this.scrollGrabbedAtY;
                int clamped = Math.max(0, Math.min(maxThumbTravel, relativeMouseY));
                int newScroll = (maxThumbTravel <= 0) ? 0 : (int)((float)this.maxScroll * ((float)clamped / (float)maxThumbTravel));
                this.scrollOffset = Math.max(0, Math.min(this.maxScroll, newScroll));
                this.amountToScroll = 0;
            }
        }
        if (this.backButton != null && this.backButton.visible) {
            this.backButton.drawButton(this.mc, mouseX, mouseY);
        }
        GomodSearchHighlight.draw(this.buttonList, KillCounterSettingsGUI.class);
        this.drawFixedPreviewPanel(panelLeft, panelTop, panelRight, panelBottom, previewText, previewRowTop,
                legendLabel, legendLabelY, killEffectGridTop);
        this.drawHoverTooltip(mouseX, mouseY);
    }

    private boolean isMouseInBox(int mouseX, int mouseY, int boxLeft, int boxRight, int boxTop, int boxBottom) {
        return mouseX >= boxLeft && mouseX < boxRight && mouseY >= boxTop && mouseY < boxBottom;
    }

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (mouseButton == 0 && this.maxScroll > 0
                && isMouseInBox(mouseX, mouseY, SCROLL_BAR_LEFT, SCROLL_BAR_RIGHT, SCROLL_BAR_TOP, SCROLL_BAR_BOTTOM)) {
            this.draggingScrollBar = true;
            this.scrollGrabbedAtY = mouseY - SCROLL_BAR_TOP;
            this.amountToScroll = 0;
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && this.draggingScrollBar) {
            this.draggingScrollBar = false;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    private void updateRowButtonVisibility(GuiButton button, int visibleTop, int visibleBottom) {
        if (button == null) {
            return;
        }
        button.visible = rowIntersectsVisible(button.yPosition, button.height, visibleTop, visibleBottom);
    }

    private void drawScrollableSettingLabels(int labelX, int rowStartY, int killEffectRowY,
                                             int visibleTop, int visibleBottom) {
        drawSettingLabelLeftColumn("Kill counter", labelX, rowStartY, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("TAB list sort (SOLO only)", labelX, rowStartY + 30, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Sort by DIST (TEAMS)", labelX, rowStartY + 60, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Stats display", labelX, rowStartY + 90, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Better player format", labelX, rowStartY + 120, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Kill effect display", labelX, killEffectRowY, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Show blitz star user", labelX, killEffectRowY + 30, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Players stats scan", labelX, killEffectRowY + 60, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Show Guild Tag", labelX, killEffectRowY + 90, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Show teammate's kit", labelX, killEffectRowY + 120, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Show enemy players' kit", labelX, killEffectRowY + 150, visibleTop, visibleBottom);
        drawSettingLabelLeftColumn("Show players' victims", labelX, killEffectRowY + 180, visibleTop, visibleBottom);
    }

    private void drawScrollableSettingButtons(int mouseX, int mouseY, int visibleTop, int visibleBottom) {
        for (GuiButton button : scrollableSettingButtons()) {
            String label = button.displayString;
            try {
                if (button == this.colorButton) button.displayString = "";
                drawButtonWithScrollFade(button, mouseX, mouseY, visibleTop, visibleBottom);
            } finally {
                button.displayString = label;
            }
        }
        // Draw last: the marquee owns its text clip, bounded by the scrolling viewport.
        if (this.colorButton != null && this.colorButton.visible) {
            float alpha = this.maxScroll <= 0 ? 1.0F : GuiScrollFade.alphaForY(
                    this.colorButton.yPosition + this.colorButton.height / 2,
                    visibleTop, visibleBottom, this.scrollOffset, this.maxScroll);
            if (alpha > 0.01F) GuiMarqueeButtonLabel.draw(this.fontRendererObj, this.colorButton,
                    mouseX, mouseY, killEffectTabModeButtonLabel(), false, visibleTop, visibleBottom, alpha);
        }
    }

    private java.util.List<GuiButton> scrollableSettingButtons() {
        java.util.List<GuiButton> out = new java.util.ArrayList<GuiButton>();
        if (this.enableButton != null) out.add(this.enableButton);
        if (this.tabListSortButton != null) out.add(this.tabListSortButton);
        if (this.tabListDistrictSortButton != null) out.add(this.tabListDistrictSortButton);
        if (this.statsKdButton != null) out.add(this.statsKdButton);
        if (this.statsWlButton != null) out.add(this.statsWlButton);
        if (this.statsWButton != null) out.add(this.statsWButton);
        if (this.colorButton != null) out.add(this.colorButton);
        if (this.blitzStarTabButton != null) out.add(this.blitzStarTabButton);
        if (this.autoKeAllButton != null) out.add(this.autoKeAllButton);
        if (this.guildTagButton != null) out.add(this.guildTagButton);
        if (this.teammateKitButton != null) out.add(this.teammateKitButton);
        if (this.enemyKitButton != null) out.add(this.enemyKitButton);
        if (this.tabKillersToggleButton != null) out.add(this.tabKillersToggleButton);
        if (this.tabKillersKeyButton != null) out.add(this.tabKillersKeyButton);
        if (this.betterPlayerFormatTabButton != null) out.add(this.betterPlayerFormatTabButton);
        if (this.betterPlayerFormatTagsButton != null) out.add(this.betterPlayerFormatTagsButton);
        if (this.killEffectTabButton != null) out.add(this.killEffectTabButton);
        if (this.killEffectTagsButton != null) out.add(this.killEffectTagsButton);
        return out;
    }

    /** Left column label on the same row as the right-column button ({@link MobHealthDisplayGUI} pattern). */
    private void drawSettingLabelLeftColumn(String text, int labelX, int rowTop, int visibleTop, int visibleBottom) {
        if (!rowIntersectsVisible(rowTop, SCROLL_ROW_H, visibleTop, visibleBottom)) {
            return;
        }
        GuiScrollFade.drawLabel(fontRendererObj, text, labelX, rowTop, 6, SCROLL_ROW_H,
                visibleTop, visibleBottom, scrollOffset, maxScroll);
    }

    private void drawButtonWithScrollFade(GuiButton button, int mouseX, int mouseY,
                                          int visibleTop, int visibleBottom) {
        if (button == null || !button.visible) {
            return;
        }
        GuiScrollFade.drawButton(mc, fontRendererObj, button, mouseX, mouseY,
                visibleTop, visibleBottom, scrollOffset, maxScroll);
    }

    private boolean rowIntersectsVisible(int rowTop, int rowHeight, int visibleTop, int visibleBottom) {
        return GuiScrollFade.intersectsVisible(rowTop, rowHeight, visibleTop, visibleBottom);
    }

    private void refreshButtonStates() {
        if (this.betterPlayerFormatTabButton != null) this.betterPlayerFormatTabButton.displayString = betterPlayerFormatTargetButtonLabel(true);
        if (this.betterPlayerFormatTagsButton != null) this.betterPlayerFormatTagsButton.displayString = betterPlayerFormatTargetButtonLabel(false);
        this.enabled = KillCounter.isEnabled();
        this.showStatsKd = KillCounter.isStatsShowKd();
        this.showStatsWl = KillCounter.isStatsShowWl();
        this.showStatsW = KillCounter.isStatsShowW();
        this.autoScanMode = KillCounter.getAutoScanMode();
        this.showGuildTag = KillCounter.isShowGuildTag();
        this.showTeammateKits = KillCounter.isShowTeammateKits();
        this.showEnemyKits = KillCounter.isShowEnemyKits();
        this.showTabKillers = ConfigManager.isKillCounterTabKillersEnabled();
        this.showBlitzStarTabIcon = ConfigManager.isKillCounterBlitzStarTabIconEnabled();
        this.tabListSortMode = KillCounter.getTabListSortMode();
        this.tabListSortGameScope = KillCounter.getTabListSortGameScope();
        if (this.tabListSortButton != null) {
            String sortLabel = this.tabListSortModeButtonLabel();
            this.tabListSortButton.displayString = sortLabel;
            int sortBtnW = Math.max(120, this.fontRendererObj.getStringWidth(
                    EnumChatFormatting.getTextWithoutFormattingCodes(sortLabel)) + 24);
            this.tabListSortButton.width = sortBtnW;
            int sortBtnX = this.tabStatsButtonColumnLeft() + (TAB_STATS_BTN_W - sortBtnW) / 2;
            this.tabListSortButton.xPosition = sortBtnX;
        }
        if (this.tabListDistrictSortButton != null) {
            boolean allowed = this.tabListSortGameScope == KillCounter.TabListSortGameScope.SOLO;
            this.tabListDistrictSortButton.enabled = allowed;
            this.tabListDistrictSortButton.displayString = tabListDistrictSortButtonLabel();
        }
        if (this.enableButton != null) {
            Object object = this.enableButton.displayString = this.killCounterButtonLabel();
        }
        this.refreshKillEffectDisplayButtonState();
        if (this.autoKeAllButton != null) {
            String label = this.getAutoScanModeLabel();
            this.autoKeAllButton.displayString = label;
            int newWidth = Math.max(120, this.fontRendererObj.getStringWidth(label) + 20);
            this.autoKeAllButton.width = newWidth;
            this.autoKeAllButton.xPosition = this.tabStatsButtonColumnLeft() + (TAB_STATS_BTN_W - newWidth) / 2;
        }
        if (this.guildTagButton != null) {
            Object object = this.guildTagButton.displayString = this.showGuildTag ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
        }
        if (this.teammateKitButton != null) {
            Object object = this.teammateKitButton.displayString = this.showTeammateKits ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
        }
        if (this.enemyKitButton != null) {
            Object object = this.enemyKitButton.displayString = this.showEnemyKits ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
        }
        if (this.tabKillersToggleButton != null) {
            this.tabKillersToggleButton.displayString = this.tabKillersButtonLabel();
        }
        if (this.tabKillersKeyButton != null && !this.awaitingTabKillersKey) {
            this.tabKillersKeyButton.displayString = this.tabKillersKeyButtonLabel();
        }
        this.layoutTabKillersButtons(this.tabStatsButtonColumnLeft(), this.tabKillersToggleButton != null ? this.tabKillersToggleButton.yPosition : 0, TAB_STATS_BTN_W);
        if (this.blitzStarTabButton != null) {
            this.blitzStarTabButton.displayString = this.blitzStarTabIconButtonLabel();
        }
        if (this.statsKdButton != null && this.statsWlButton != null && this.statsWButton != null) {
            this.statsKdButton.displayString = this.statsPartButtonLabel(this.showStatsKd, "KD");
            this.statsWlButton.displayString = this.statsPartButtonLabel(this.showStatsWl, "WL");
            this.statsWButton.displayString = this.statsPartButtonLabel(this.showStatsW, "W");
            this.layoutStatsPartButtons(this.tabStatsButtonColumnLeft(),
                    this.statsKdButton.yPosition, TAB_STATS_BTN_W);
        }
    }

    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 33: {
                toggleBetterPlayerFormatTarget(true);
                onBetterPlayerFormatChanged();
                break;
            }
            case 36: {
                toggleBetterPlayerFormatTarget(false);
                onBetterPlayerFormatChanged();
                break;
            }
            case 0: {
                KillCounter.TabKillCounterDisplayMode currentMode = KillCounter.getTabKillCounterDisplayMode();
                if (!this.enabled) {
                    this.enabled = true;
                    this.previewKillCount = 1 + (int) (Math.random() * 9.0);
                    KillCounter.setTabKillCounterDisplayMode(KillCounter.TabKillCounterDisplayMode.BEFORE_NAME);
                    KillCounter.setEnabled(true);
                } else if (currentMode == KillCounter.TabKillCounterDisplayMode.BEFORE_NAME) {
                    KillCounter.setTabKillCounterDisplayMode(KillCounter.TabKillCounterDisplayMode.AFTER_NAME);
                    KillCounter.setEnabled(true);
                    this.enabled = true;
                } else {
                    this.enabled = false;
                    KillCounter.setEnabled(false);
                }
                KillCounter.markTabCompositionDirty();
                KillCounter.forceTabRefresh();
                this.enableButton.displayString = this.killCounterButtonLabel();
                break;
            }
            case 2: {
                KillCounter.KillEffectTabDisplayMode mode = KillCounter.getKillEffectTabDisplayMode();
                if (mode == KillCounter.KillEffectTabDisplayMode.OFF) {
                    KillCounter.setKillEffectTabDisplayMode(KillCounter.KillEffectTabDisplayMode.ICON);
                } else if (mode == KillCounter.KillEffectTabDisplayMode.ICON) {
                    boolean colorTab = !ConfigManager.isKillCounterChatStyleTab();
                    boolean colorTags = !ConfigManager.isKillCounterChatStyleNametag();
                    KillCounter.setKillEffectTabDisplayMode(colorTab
                            ? colorTags ? KillCounter.KillEffectTabDisplayMode.COLORED_NAME
                            : KillCounter.KillEffectTabDisplayMode.COLORED_NAME_TAB
                            : colorTags ? KillCounter.KillEffectTabDisplayMode.COLORED_NAME_TAGS
                            : KillCounter.KillEffectTabDisplayMode.OFF);
                } else {
                    KillCounter.setKillEffectTabDisplayMode(KillCounter.KillEffectTabDisplayMode.OFF);
                }
                mode = KillCounter.getKillEffectTabDisplayMode();
                if (mode.colorsTab() || mode.colorsNametags()) {
                    this.pickRandomKillEffectColor();
                } else if (mode == KillCounter.KillEffectTabDisplayMode.ICON) {
                    this.pickRandomKillEffectIcon();
                }
                this.refreshButtonStates();
                KillCounter.forceTabRefresh();
                break;
            }
            case 34:
            case 35: {
                toggleKillEffectTarget(button.id == 34);
                this.refreshButtonStates();
                KillCounter.forceTabRefresh();
                break;
            }
            case 30: {
                this.showStatsKd = !KillCounter.isStatsShowKd();
                KillCounter.setStatsShowKd(this.showStatsKd);
                KillCounter.forceTabRefresh();
                this.refreshButtonStates();
                break;
            }
            case 31: {
                this.showStatsWl = !KillCounter.isStatsShowWl();
                KillCounter.setStatsShowWl(this.showStatsWl);
                KillCounter.forceTabRefresh();
                this.refreshButtonStates();
                break;
            }
            case 32: {
                this.showStatsW = !KillCounter.isStatsShowW();
                KillCounter.setStatsShowW(this.showStatsW);
                KillCounter.forceTabRefresh();
                this.refreshButtonStates();
                break;
            }
            case 4: {
                this.autoScanMode = KillCounter.cycleAutoScanMode();
                String label = this.getAutoScanModeLabel();
                this.autoKeAllButton.displayString = label;
                int newWidth = Math.max(120, this.fontRendererObj.getStringWidth(label) + 20);
                this.autoKeAllButton.width = newWidth;
                this.autoKeAllButton.xPosition = this.tabStatsButtonColumnLeft() + (TAB_STATS_BTN_W - newWidth) / 2;
                this.refreshKillEffectDisplayButtonState();
                break;
            }
            case 5: {
                this.showGuildTag = !this.showGuildTag;
                KillCounter.setShowGuildTag(this.showGuildTag);
                this.guildTagButton.displayString = this.showGuildTag ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
                KillCounter.forceTabRefresh();
                break;
            }
            case 6: {
                this.showTeammateKits = !this.showTeammateKits;
                KillCounter.setShowTeammateKits(this.showTeammateKits);
                this.teammateKitButton.displayString = this.showTeammateKits ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
                KillCounter.forceTabRefresh();
                break;
            }
            case 7: {
                this.showEnemyKits = !this.showEnemyKits;
                KillCounter.setShowEnemyKits(this.showEnemyKits);
                this.enemyKitButton.displayString = this.showEnemyKits ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
                KillCounter.forceTabRefresh();
                break;
            }
            case 13: {
                boolean next = !ConfigManager.isKillCounterTabKillersEnabled();
                KillCounter.setTabKillersEnabled(next, false);
                this.showTabKillers = ConfigManager.isKillCounterTabKillersEnabled();
                if (this.tabKillersToggleButton != null) {
                    this.tabKillersToggleButton.displayString = this.tabKillersButtonLabel();
                }
                break;
            }
            case 14: {
                this.awaitingTabKillersKey = true;
                if (this.tabKillersKeyButton != null) {
                    this.tabKillersKeyButton.displayString = EnumChatFormatting.YELLOW + "Press key";
                }
                this.layoutTabKillersButtons(this.tabStatsButtonColumnLeft(), this.tabKillersToggleButton != null ? this.tabKillersToggleButton.yPosition : 0, TAB_STATS_BTN_W);
                break;
            }
            case 12: {
                boolean wasBlitzStarOn = this.showBlitzStarTabIcon;
                this.showBlitzStarTabIcon = !this.showBlitzStarTabIcon;
                ConfigManager.setKillCounterBlitzStarTabIconEnabled(this.showBlitzStarTabIcon);
                if (this.showBlitzStarTabIcon && !wasBlitzStarOn) {
                    this.pickRandomBlitzStarPreviewSlug();
                }
                if (this.blitzStarTabButton != null) {
                    this.blitzStarTabButton.displayString = this.blitzStarTabIconButtonLabel();
                }
                KillCounter.forceTabRefresh();
                break;
            }
            case 9: {
                KillCounter.TabListSortMode next = KillCounter.advanceTabListSortMode();
                this.tabListSortMode = next;
                if (this.tabListSortButton != null) {
                    String sortLabel = this.tabListSortModeButtonLabel();
                    this.tabListSortButton.displayString = sortLabel;
                    int sortBtnW = Math.max(120, this.fontRendererObj.getStringWidth(
                            EnumChatFormatting.getTextWithoutFormattingCodes(sortLabel)) + 24);
                    this.tabListSortButton.width = sortBtnW;
                    int sortBtnX = this.tabStatsButtonColumnLeft() + (TAB_STATS_BTN_W - sortBtnW) / 2;
                    this.tabListSortButton.xPosition = sortBtnX;
                }
                KillCounter.forceTabRefresh();
                break;
            }
            case 10: {
                KillCounter.TabListSortGameScope nx = this.tabListSortGameScope.next();
                KillCounter.setTabListSortGameScope(nx);
                this.tabListSortGameScope = KillCounter.getTabListSortGameScope();
                this.tabListSortMode = KillCounter.getTabListSortMode();
                this.refreshButtonStates();
                KillCounter.forceTabRefresh();
                break;
            }
            case 11: {
                boolean allowed = this.tabListSortGameScope == KillCounter.TabListSortGameScope.SOLO;
                if (!allowed) {
                    break;
                }
                boolean next = !KillCounter.isDistrictTabListSortEnabled();
                ConfigManager.setKillCounterTabListSortDistrictEnabled(next);
                KillCounter.forceTabRefresh();
                this.refreshButtonStates();
                break;
            }
            case 99: {
                GomodMenuTransitions.openMainMenuWithoutAnimation(this.mc);
            }
        }
    }

    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (this.awaitingTabKillersKey) {
            this.awaitingTabKillersKey = false;
            ConfigManager.setKillCounterTabKillersKeyCode(keyCode == Keyboard.KEY_ESCAPE ? 0 : keyCode);
            if (this.tabKillersKeyButton != null) {
                this.tabKillersKeyButton.displayString = this.tabKillersKeyButtonLabel();
            }
            this.layoutTabKillersButtons(this.tabStatsButtonColumnLeft(), this.tabKillersToggleButton != null ? this.tabKillersToggleButton.yPosition : 0, TAB_STATS_BTN_W);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return this.awaitingTabKillersKey;
    }

    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getAutoScanModeLabel() {
        KillCounter.AutoScanMode mode = this.autoScanMode;
        if (mode == null) mode = KillCounter.getAutoScanMode();
        switch (mode) {
            case CAGES_OPEN:
                return EnumChatFormatting.GREEN + "When cages open";
            case PLAYERS_JOIN:
                return EnumChatFormatting.GREEN + "When players join";
            default:
                return EnumChatFormatting.RED + "Disabled";
        }
    }

    private String statsPartButtonLabel(boolean enabled, String part) {
        return (enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + part;
    }

    private void layoutStatsPartButtons(int rightColumnX, int rowY, int baseButtonWidth) {
        if (this.statsKdButton == null || this.statsWlButton == null || this.statsWButton == null) {
            return;
        }
        int gap = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        int thirdW = (baseButtonWidth - gap * 2) / 3;
        int x = rightColumnX;
        this.statsKdButton.xPosition = x;
        this.statsKdButton.yPosition = rowY;
        this.statsKdButton.width = thirdW;
        this.statsKdButton.height = BUTTON_HEIGHT;
        x += thirdW + gap;
        this.statsWlButton.xPosition = x;
        this.statsWlButton.yPosition = rowY;
        this.statsWlButton.width = thirdW;
        this.statsWlButton.height = BUTTON_HEIGHT;
        x += thirdW + gap;
        this.statsWButton.xPosition = x;
        this.statsWButton.yPosition = rowY;
        this.statsWButton.width = thirdW;
        this.statsWButton.height = BUTTON_HEIGHT;
    }

    private String killCounterButtonLabel() {
        if (!KillCounter.isEnabled()) {
            return EnumChatFormatting.RED + "Disabled";
        }
        switch (KillCounter.getTabKillCounterDisplayMode()) {
            case BEFORE_NAME:
                return EnumChatFormatting.AQUA + "Before name";
            default:
                return EnumChatFormatting.GREEN + "After name";
        }
    }

    private String blitzStarTabIconButtonLabel() {
        return this.showBlitzStarTabIcon ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
    }

    private String tabKillersButtonLabel() {
        return ConfigManager.isKillCounterTabKillersEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF";
    }

    private String tabKillersKeyButtonLabel() {
        return EnumChatFormatting.WHITE + formatKeyName(ConfigManager.getKillCounterTabKillersKeyCode());
    }

    private void layoutTabKillersButtons(int rightColumnX, int rowY, int baseButtonWidth) {
        if (this.tabKillersToggleButton == null || this.tabKillersKeyButton == null) {
            return;
        }
        int gap = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        int halfW = (baseButtonWidth - gap) / 2;
        this.tabKillersToggleButton.xPosition = rightColumnX;
        this.tabKillersToggleButton.yPosition = rowY;
        this.tabKillersToggleButton.width = halfW;
        this.tabKillersToggleButton.height = BUTTON_HEIGHT;
        this.tabKillersKeyButton.xPosition = rightColumnX + halfW + gap;
        this.tabKillersKeyButton.yPosition = rowY;
        this.tabKillersKeyButton.width = halfW;
        this.tabKillersKeyButton.height = BUTTON_HEIGHT;
    }

    private String killEffectTabModeButtonLabel() {
        switch (KillCounter.getKillEffectTabDisplayMode()) {
            case COLORED_NAME_TAB:
            case COLORED_NAME_TAGS:
            case COLORED_NAME:
                return EnumChatFormatting.LIGHT_PURPLE + "Colored name";
            case ICON:
                return EnumChatFormatting.AQUA + "Kill effect icon";
            default:
                return EnumChatFormatting.RED + "Disabled";
        }
    }

    private void refreshKillEffectDisplayButtonState() {
        if (this.colorButton == null) {
            return;
        }
        this.colorButton.enabled = true;
        this.colorButton.displayString = killEffectTabModeButtonLabel();
        int rightColumnX = this.tabStatsButtonColumnLeft();
        this.layoutKillEffectDisplayButtons(rightColumnX, this.colorButton.yPosition, TAB_STATS_BTN_W);
        KillCounter.KillEffectTabDisplayMode mode = KillCounter.getKillEffectTabDisplayMode();
        boolean showTargets = mode.colorsTab() || mode.colorsNametags();
        this.killEffectTabButton.visible = showTargets;
        this.killEffectTagsButton.visible = showTargets;
        this.killEffectTabButton.displayString = killEffectTargetButtonLabel(true);
        this.killEffectTagsButton.displayString = killEffectTargetButtonLabel(false);
        boolean formatUsesTab = ConfigManager.isKillCounterChatStyleTab();
        boolean formatUsesTags = ConfigManager.isKillCounterChatStyleNametag();
        this.killEffectTabButton.enabled = !formatUsesTab;
        this.killEffectTagsButton.enabled = !formatUsesTags;
        if (this.betterPlayerFormatTabButton != null) {
            this.betterPlayerFormatTabButton.enabled = !mode.colorsTab();
        }
        if (this.betterPlayerFormatTagsButton != null) {
            this.betterPlayerFormatTagsButton.enabled = !mode.colorsNametags();
        }
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        KillCounter.KillEffectTabDisplayMode killEffectMode = KillCounter.getKillEffectTabDisplayMode();
        if (isMouseOver(this.betterPlayerFormatTabButton, mouseX, mouseY) && killEffectMode.colorsTab()) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Better player format > TAB",
                    EnumChatFormatting.YELLOW + "Disable Kill effect display > TAB first.");
        } else if (isMouseOver(this.betterPlayerFormatTagsButton, mouseX, mouseY) && killEffectMode.colorsNametags()) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Better player format > TAGS",
                    EnumChatFormatting.YELLOW + "Disable Kill effect display > TAGS first.");
        } else if (isMouseOver(this.betterPlayerFormatTabButton, mouseX, mouseY)
                || isMouseOver(this.betterPlayerFormatTagsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Better player format",
                    EnumChatFormatting.GRAY + "Instead of basic white/red colors for usernames, display them as they are in chat.");
        }
    }

    private String betterPlayerFormatTargetButtonLabel(boolean tab) {
        boolean enabled = tab ? ConfigManager.isKillCounterChatStyleTab() : ConfigManager.isKillCounterChatStyleNametag();
        return (enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + (tab ? "TAB" : "TAGS");
    }

    private String killEffectTargetButtonLabel(boolean tab) {
        KillCounter.KillEffectTabDisplayMode mode = KillCounter.getKillEffectTabDisplayMode();
        boolean enabled = tab ? mode.colorsTab() : mode.colorsNametags();
        return (enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + (tab ? "TAB" : "TAGS");
    }

    private void toggleBetterPlayerFormatTarget(boolean tab) {
        String current = ConfigManager.getKillCounterBetterPlayerFormat();
        boolean hasTab = "TAB".equals(current) || "TAB_TAGS".equals(current);
        boolean hasTags = "TAGS".equals(current) || "TAB_TAGS".equals(current);
        if (tab) hasTab = !hasTab; else hasTags = !hasTags;
        ConfigManager.setKillCounterBetterPlayerFormat(hasTab
                ? hasTags ? "TAB_TAGS" : "TAB"
                : hasTags ? "TAGS" : "OFF");
    }

    private void toggleKillEffectTarget(boolean tab) {
        KillCounter.KillEffectTabDisplayMode current = KillCounter.getKillEffectTabDisplayMode();
        boolean hasTab = current.colorsTab();
        boolean hasTags = current.colorsNametags();
        if (tab) hasTab = !hasTab; else hasTags = !hasTags;
        KillCounter.setKillEffectTabDisplayMode(hasTab
                ? hasTags ? KillCounter.KillEffectTabDisplayMode.COLORED_NAME
                : KillCounter.KillEffectTabDisplayMode.COLORED_NAME_TAB
                : hasTags ? KillCounter.KillEffectTabDisplayMode.COLORED_NAME_TAGS
                : KillCounter.KillEffectTabDisplayMode.OFF);
        if (hasTab) this.pickRandomKillEffectColor();
    }

    private void layoutBetterPlayerFormatButtons(int rightColumnX, int rowY, int baseButtonWidth) {
        layoutTwoTargetButtons(this.betterPlayerFormatTabButton, this.betterPlayerFormatTagsButton,
                rightColumnX, rowY, baseButtonWidth);
    }

    private void layoutKillEffectDisplayButtons(int rightColumnX, int rowY, int baseButtonWidth) {
        int gap = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        boolean colored = KillCounter.getKillEffectTabDisplayMode().colorsTab()
                || KillCounter.getKillEffectTabDisplayMode().colorsNametags();
        String primaryLabel = EnumChatFormatting.getTextWithoutFormattingCodes(killEffectTabModeButtonLabel());
        int fullLabelWidth = this.fontRendererObj == null || primaryLabel == null
                ? 0 : this.fontRendererObj.getStringWidth(primaryLabel);
        int primaryWidth = colored ? Math.min(baseButtonWidth, Math.max(96, fullLabelWidth + 16)) : baseButtonWidth;
        this.colorButton.width = primaryWidth;
        this.colorButton.xPosition = colored
                ? rightColumnX - gap - primaryWidth
                : rightColumnX + (baseButtonWidth - primaryWidth) / 2;
        this.colorButton.yPosition = rowY;
        layoutTwoTargetButtons(this.killEffectTabButton, this.killEffectTagsButton,
                rightColumnX, rowY, baseButtonWidth);
    }

    private void layoutTwoTargetButtons(GuiButton tabButton, GuiButton tagsButton,
                                        int rightColumnX, int rowY, int baseButtonWidth) {
        if (tabButton == null || tagsButton == null) return;
        int gap = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        int halfWidth = (baseButtonWidth - gap) / 2;
        tabButton.xPosition = rightColumnX;
        tabButton.yPosition = rowY;
        tabButton.width = halfWidth;
        tabButton.height = BUTTON_HEIGHT;
        tagsButton.xPosition = rightColumnX + halfWidth + gap;
        tagsButton.yPosition = rowY;
        tagsButton.width = halfWidth;
        tagsButton.height = BUTTON_HEIGHT;
    }

    private void onBetterPlayerFormatChanged() {
        KillCounter.forceTabRefresh();
        if ((ConfigManager.isKillCounterChatStyleTab() || ConfigManager.isKillCounterChatStyleNametag())
                && KillCounter.getAutoScanMode() == KillCounter.AutoScanMode.PLAYERS_JOIN
                && me.napim.gomod.features.BlitzChatNameDisplay.isActiveContext(this.mc)) {
            me.napim.gomod.command.KillEffectCommand.runTabStatsAllScanAsync(false, "blitz pregame chat-format setting");
        }
        refreshButtonStates();
    }

    private void drawTooltip(int mouseX, int mouseY, String... lines) {
        List<String> tooltip = Arrays.asList(lines);
        drawHoveringText(tooltip, mouseX, Math.max(0, mouseY - 12));
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && button.visible
                && mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + button.height;
    }

    /** Dark panel behind Preview + kill-effect legend (same style as Effects HUD colors box). */
    private void drawPreviewPanelBox(int left, int top, int right, int bottom) {
        Gui.drawRect(left, top, right, bottom, 0x22000000);
        Gui.drawRect(left, top, right, top + 1, 0x44AAAAAA);
        Gui.drawRect(left, bottom - 1, right, bottom, 0x44AAAAAA);
    }

    /** Fixed preview header; redrawn after settings buttons so scrolled rows cannot overlap it. */
    private void drawFixedPreviewPanel(int panelLeft, int panelTop, int panelRight, int panelBottom,
            String previewText, int previewRowTop, String legendLabel, int legendLabelY, int killEffectGridTop) {
        this.drawPreviewPanelBox(panelLeft, panelTop, panelRight, panelBottom);
        String previewLabel = EnumChatFormatting.WHITE + "Preview" + EnumChatFormatting.GRAY + ":";
        int previewLabelY = panelTop + PREVIEW_PANEL_PAD;
        GuiHeader.drawScreenCenteredText(this.fontRendererObj, previewLabel, this.width, previewLabelY, 0xFFFFFF);
        this.drawPreviewTabRow(previewRowTop, previewText);
        int legendLabelWidth = this.fontRendererObj.getStringWidth(legendLabel);
        this.fontRendererObj.drawStringWithShadow(legendLabel, this.width / 2 - legendLabelWidth / 2, legendLabelY, 0xFFFFFF);
        String[] killEffectColors = new String[]{EnumChatFormatting.AQUA + "Speed", EnumChatFormatting.GOLD + "Resistance",
                EnumChatFormatting.LIGHT_PURPLE + "Regeneration", EnumChatFormatting.DARK_RED + "Flaming Arrows",
                EnumChatFormatting.YELLOW + "Level Up", EnumChatFormatting.GREEN + "Grave digger", EnumChatFormatting.DARK_BLUE + "Random"};
        String[] killEffectKeys = new String[]{"SPEED", "RESISTANCE", "REGENERATION", "FLAME", "LEVEL_UP", "GRAVEDIGGER", "RANDOM"};
        Minecraft mc = Minecraft.getMinecraft();
        int iconSize = 9;
        int iconGap = 2;
        int legendGridW = PREVIEW_PANEL_GRID_W;
        int startX = this.width / 2 - legendGridW / 2;
        for (int i = 0; i < killEffectColors.length; ++i) {
            int y;
            int cellCenterX;
            int cellWidth;
            String colorText = killEffectColors[i];
            int textWidth = this.fontRendererObj.getStringWidth(colorText);
            Object icon = KillCounter.getKillEffectIconPrefixForEffect(killEffectKeys[i]);
            int entryWidth = textWidth + (icon instanceof ResourceLocation ? iconSize + iconGap : 0);
            if (i == 6) {
                int levelUpCol = 1;
                int levelUpRow = 2;
                cellWidth = legendGridW / 3;
                cellCenterX = startX + levelUpCol * cellWidth + cellWidth / 2;
                y = killEffectGridTop + levelUpRow * CONTENT_LINE_STEP;
            } else {
                int col = i % 3;
                int row = i / 3;
                cellWidth = legendGridW / 3;
                cellCenterX = startX + col * cellWidth + cellWidth / 2;
                y = killEffectGridTop + row * CONTENT_LINE_STEP;
            }
            int entryX = cellCenterX - entryWidth / 2;
            int textX = entryX;
            if (icon instanceof ResourceLocation) {
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                ResourceLocation iconLocation = (ResourceLocation) icon;
                GomodClasspathTextures.bindTexture(mc, iconLocation);
                Gui.drawScaledCustomSizeModalRect(entryX, y - 1, 0, 0, 16, 16,
                        iconSize, iconSize, 16.0f, 16.0f);
                textX += iconSize + iconGap;
            }
            this.fontRendererObj.drawStringWithShadow(colorText, textX, y, 0xFFFFFF);
        }
    }

    /** Full rendered width of the TAB preview row (icons + head + formatted text). */
    private int measurePreviewTabRowTotalWidth(String previewText) {
        PreviewTabRowLayout layout = this.layoutPreviewTabRow(previewText);
        return layout.totalWidth;
    }

    private PreviewTabRowLayout layoutPreviewTabRow(String previewText) {
        int headS = 9;
        int iconS = 9;
        int textW = this.fontRendererObj.getStringWidth(previewText);
        String killCounterPrefix = this.enabled && KillCounter.getTabKillCounterDisplayMode() == KillCounter.TabKillCounterDisplayMode.BEFORE_NAME
                ? EnumChatFormatting.DARK_RED + "[" + this.previewKillCount + "]" + EnumChatFormatting.RESET
                : "";
        int killCounterPrefixW = killCounterPrefix.isEmpty() ? 0 : this.fontRendererObj.getStringWidth(killCounterPrefix);
        int gapAfterHead = 4;
        KillCounter.KillEffectTabDisplayMode mode = KillCounter.getKillEffectTabDisplayMode();
        boolean showBlitzStarPreview = this.showBlitzStarTabIcon;
        int blitzStarW = showBlitzStarPreview ? iconS + 4 : 0;
        int keIconW = (mode == KillCounter.KillEffectTabDisplayMode.ICON ? iconS + 4 : 0);
        int extras = blitzStarW + keIconW
                + (killCounterPrefixW > 0 ? killCounterPrefixW + 4 : 0)
                + headS + gapAfterHead;
        int total = extras + textW;
        int left = this.width / 2 - total / 2;
        int headLeft = left + blitzStarW + keIconW + (killCounterPrefixW > 0 ? killCounterPrefixW + 4 : 0);
        return new PreviewTabRowLayout(total, left, headLeft, headS, gapAfterHead, textW, killCounterPrefix, killCounterPrefixW,
                blitzStarW, keIconW, iconS, showBlitzStarPreview, mode);
    }

    private static final class PreviewTabRowLayout {
        final int totalWidth;
        final int left;
        final int headLeft;
        final int headS;
        final int gapAfterHead;
        final int textW;
        final String killCounterPrefix;
        final int killCounterPrefixW;
        final int blitzStarW;
        final int keIconW;
        final int iconS;
        final boolean showBlitzStarPreview;
        final KillCounter.KillEffectTabDisplayMode killEffectMode;

        PreviewTabRowLayout(int totalWidth, int left, int headLeft, int headS, int gapAfterHead, int textW,
                String killCounterPrefix, int killCounterPrefixW, int blitzStarW, int keIconW, int iconS,
                boolean showBlitzStarPreview, KillCounter.KillEffectTabDisplayMode killEffectMode) {
            this.totalWidth = totalWidth;
            this.left = left;
            this.headLeft = headLeft;
            this.headS = headS;
            this.gapAfterHead = gapAfterHead;
            this.textW = textW;
            this.killCounterPrefix = killCounterPrefix;
            this.killCounterPrefixW = killCounterPrefixW;
            this.blitzStarW = blitzStarW;
            this.keIconW = keIconW;
            this.iconS = iconS;
            this.showBlitzStarPreview = showBlitzStarPreview;
            this.killEffectMode = killEffectMode;
        }
    }

    /**
     * @param rowTopY nominal top edge for the 9px head/icon column (icons nudged up a few px to match in-game TAB).
     */
    private void drawPreviewTabRow(int rowTopY, String previewText) {
        Minecraft mc = Minecraft.getMinecraft();
        PreviewTabRowLayout layout = this.layoutPreviewTabRow(previewText);
        int fh = this.fontRendererObj.FONT_HEIGHT;
        final int iconLiftPx = 1;
        int iconTop = rowTopY - iconLiftPx;
        int textY = rowTopY + (layout.headS - fh + 1) / 2;
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        int left = layout.left;
        int headLeft = layout.headLeft;
        int iy = iconTop;
        if (layout.showBlitzStarPreview) {
            String slug = this.previewBlitzStarSlug != null && !this.previewBlitzStarSlug.isEmpty()
                    ? this.previewBlitzStarSlug
                    : "vaulthunter";
            ResourceLocation starRl = new ResourceLocation("gomod123", "textures/blitzstars/star_" + slug + ".png");
            GomodClasspathTextures.bindTexture(mc, starRl);
            Gui.drawScaledCustomSizeModalRect(left, iy, 0, 0, 16, 16, layout.iconS, layout.iconS, 16.0f, 16.0f);
        }
        if (layout.killEffectMode == KillCounter.KillEffectTabDisplayMode.ICON) {
            String keKey = this.randomPreviewKillEffect != null && !this.randomPreviewKillEffect.isEmpty()
                    ? this.randomPreviewKillEffect
                    : "REGENERATION";
            Object prefix = KillCounter.getKillEffectIconPrefixForEffect(keKey);
            if (prefix == null) {
                prefix = new ResourceLocation("gomod123", "textures/killeffects/regeneration.png");
            }
            int iconX = left + layout.blitzStarW;
            if (prefix instanceof ResourceLocation) {
                GomodClasspathTextures.bindTexture(mc, (ResourceLocation) prefix);
                Gui.drawScaledCustomSizeModalRect(iconX, iy, 0, 0, 16, 16, layout.iconS, layout.iconS, 16.0f, 16.0f);
            } else if (prefix instanceof String) {
                int rgb = 0xFF000000 | (KillCounter.getKillEffectColorForEffect(keKey) & 0xFFFFFF);
                this.fontRendererObj.drawStringWithShadow((String) prefix, iconX, iy + (layout.iconS - fh + 1) / 2, rgb);
            }
        }
        if (!layout.killCounterPrefix.isEmpty()) {
            int counterX = headLeft - 4 - layout.killCounterPrefixW;
            this.fontRendererObj.drawStringWithShadow(layout.killCounterPrefix, counterX, textY, 0xFFFFFF);
        }
        if (mc.thePlayer != null) {
            ResourceLocation skin = DefaultPlayerSkin.getDefaultSkinLegacy();
            try {
                if (mc.getNetHandler() != null) {
                    NetworkPlayerInfo inf = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
                    if (inf != null && inf.getLocationSkin() != null) {
                        skin = inf.getLocationSkin();
                    }
                }
            } catch (Throwable ignored) {}
            mc.getTextureManager().bindTexture(skin);
            int hy = iconTop;
            Gui.drawScaledCustomSizeModalRect(headLeft, hy, 8.0f, 8.0f, 8, 8, layout.headS, layout.headS, 64.0f, 64.0f);
            Gui.drawScaledCustomSizeModalRect(headLeft, hy, 40.0f, 8.0f, 8, 8, layout.headS, layout.headS, 64.0f, 64.0f);
        }
        int textX = mc.thePlayer == null ? this.width / 2 - layout.textW / 2 : headLeft + layout.headS + layout.gapAfterHead;
        this.fontRendererObj.drawStringWithShadow(previewText, textX, textY, 0xFFFFFF);
    }

    private String buildPreviewText() {
        StringBuilder preview = new StringBuilder();
        KillCounter.KillEffectTabDisplayMode km = KillCounter.getKillEffectTabDisplayMode();
        EnumChatFormatting nameColor;
        if (km.colorsTab()) {
            nameColor = this.randomKillEffectColor;
        } else if (km == KillCounter.KillEffectTabDisplayMode.ICON) {
            nameColor = EnumChatFormatting.RED;
        } else {
            nameColor = EnumChatFormatting.RED;
        }
        if (ConfigManager.isKillCounterChatStyleTab()) {
            preview.append(me.napim.gomod.features.LocalDisguise.formatBsg2KillBracketForDisplay(120000)).append(' ')
                    .append(EnumChatFormatting.AQUA).append("[MVP").append(EnumChatFormatting.RED).append('+')
                    .append(EnumChatFormatting.AQUA).append("] ").append(this.playerName).append(EnumChatFormatting.RESET);
        } else {
            preview.append(nameColor).append(this.playerName);
        }
        if (this.enabled && KillCounter.getTabKillCounterDisplayMode() == KillCounter.TabKillCounterDisplayMode.AFTER_NAME) {
            preview.append(EnumChatFormatting.DARK_RED).append(" [").append(this.previewKillCount).append("]");
        }
        if (KillCounter.isStatsDisplay()) {
            String statsSuffix = TabStatsBlitzMetrics.previewSample().formatSuffix(
                    this.showStatsKd, this.showStatsWl, this.showStatsW);
            if (statsSuffix != null && !statsSuffix.isEmpty()) {
                preview.append(EnumChatFormatting.RESET).append(' ').append(statsSuffix);
            }
        }
        if (this.showGuildTag) {
            preview.append(EnumChatFormatting.GREEN).append(" [TAG]");
        }
        preview.append(KillCounter.buildTabStatsSettingsPreviewKitSuffix(this.showTeammateKits, this.showEnemyKits));
        return preview.toString();
    }

    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0 && this.maxScroll > 0) {
            if (dWheel > 1) {
                if (this.scrollDirection != 1) {
                    this.scrollDirection = 1;
                    this.amountToScroll = 0;
                }
                this.amountToScroll = Math.min(SCROLL_MAX_QUEUE_PX, this.amountToScroll + SCROLL_IMPULSE_PX);
            }
            if (dWheel < -1) {
                if (this.scrollDirection != -1) {
                    this.scrollDirection = -1;
                    this.amountToScroll = 0;
                }
                this.amountToScroll = Math.min(SCROLL_MAX_QUEUE_PX, this.amountToScroll + SCROLL_IMPULSE_PX);
            }
        }
    }

    private void pickRandomKillEffectColor() {
        EnumChatFormatting[] killEffectColors = new EnumChatFormatting[]{EnumChatFormatting.AQUA, EnumChatFormatting.GOLD, EnumChatFormatting.LIGHT_PURPLE, EnumChatFormatting.DARK_RED, EnumChatFormatting.YELLOW, EnumChatFormatting.GREEN, EnumChatFormatting.DARK_BLUE};
        int randomIndex = (int)(Math.random() * (double)killEffectColors.length);
        this.randomKillEffectColor = killEffectColors[randomIndex];
    }

    private void pickRandomKillEffectIcon() {
        this.randomPreviewKillEffect = PREVIEW_KILL_EFFECT_KEYS[(int) (Math.random() * (double) PREVIEW_KILL_EFFECT_KEYS.length)];
        this.ensurePreviewIconLoaded(this.randomPreviewKillEffect, null);
    }

    private void pickRandomBlitzStarPreviewSlug() {
        String[] slugs = KillCounter.getKnownBlitzStarTextureSlugsSorted();
        if (slugs.length > 0) {
            this.previewBlitzStarSlug = slugs[(int) (Math.random() * (double) slugs.length)];
        } else {
            this.previewBlitzStarSlug = "vaulthunter";
        }
        this.ensurePreviewIconLoaded(null, this.previewBlitzStarSlug);
    }

    private void ensurePreviewIconLoaded(String killEffectKey, String blitzStarSlug) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (killEffectKey != null && !killEffectKey.isEmpty()) {
            Object prefix = KillCounter.getKillEffectIconPrefixForEffect(killEffectKey);
            if (prefix instanceof ResourceLocation) {
                GomodClasspathTextures.ensureLoaded(mc, (ResourceLocation) prefix);
            }
        }
        if (blitzStarSlug != null && !blitzStarSlug.isEmpty()) {
            GomodClasspathTextures.ensureLoaded(mc,
                    new ResourceLocation("gomod123", "textures/blitzstars/star_" + blitzStarSlug + ".png"));
        }
    }

    private static String formatKeyName(int keyCode) {
        if (keyCode <= 0) {
            return "NONE";
        }
        String name = Keyboard.getKeyName(keyCode);
        return name == null || name.trim().isEmpty() ? "UNKNOWN" : name;
    }
}
