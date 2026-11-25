package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.AskForLoot;
import me.napim.gomod.features.BlocksFinder;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodBlockOverlay;
import me.napim.gomod.features.Denicker;
import me.napim.gomod.features.DcMirrorBridge;
import me.napim.gomod.features.GomodMenuSettings;
import me.napim.gomod.features.LightningSettings;
import me.napim.gomod.features.LobbyPingDisplay;
import me.napim.gomod.features.LootCall;
import me.napim.gomod.features.MountHeadDim;
import me.napim.gomod.features.MountXpOverlay;
import me.napim.gomod.features.HorseDonkeyTracker;
import me.napim.gomod.features.PlayerCaller;
import me.napim.gomod.features.BestArmorDisplay;
import me.napim.gomod.features.RadialMenuWheel;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.UselessItemOverlay;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.WaterClarity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GUI for toggling miscellaneous features (three-column grid; row order in {@link #initGui}).
 */
public class MiscellaneousGUI extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int COLUMN_WIDTH = 170;
    private static final int COLUMN_GAP = 14;
    private static final int GRID_COLUMNS = 3;
    // The public grid omits DC; internal builds add DC plus the two internal-only entries.
    private static final int TOTAL_GRID_BUTTONS = 36 + (GomodInternalFeatures.isEnabled() ? 3 : 0);
    private static final int GRID_ROWS = (TOTAL_GRID_BUTTONS + GRID_COLUMNS - 1) / GRID_COLUMNS;

    private final GuiScreen parent;

    private GuiButton bestArmorButton;
    private GuiButton visibleSpecsButton;
    private GuiButton lootCallButton;
    private GuiButton nametagsSettingsButton;
    private GuiButton askForLootButton;
    private GuiButton playerCallerButton;
    private GuiButton starTextButton;
    private GuiButton heldItemInfoButton;
    private GuiButton lobbyPingButton;
    private GuiButton mountHeadDimButton;
    private GuiButton chestRoutesButton;
    private GuiButton witchSoundsButton;
    private GuiButton gomodServiceButton;
    private GuiButton horseDonkeyButton;
    private GuiButton guiBackdropSettingsButton;
    private GuiButton waterClarityButton;
    private GuiButton radialMenuButton;
    private GuiButton gomodMenuSettingsButton;
    private GuiButton lunarTweaksButton;
    private GuiButton cageSettingsButton;
    private GuiButton selfOpacityButton;
    private GuiButton barrierVisibilityEntryButton;
    private GuiButton dcMirrorChatButton;
    private GuiButton blocksFinderButton;
    private GuiButton lightningNerfButton;
    private GuiButton tntPlacementPreviewButton;
    private GuiButton blitzVaultItemsButton;
    private GuiButton blitzChestItemsButton;
    private GuiButton denickerButton;
    private GuiButton blockOverlayButton;
    private GuiButton mountXpOverlayButton;
    private GuiButton fixesButton;
    private GuiButton blitzExtendedResultsButton;
    private GuiButton deathmatchTimerButton;
    private GuiButton blitzPregamePlayerToggleButton;
    private GuiButton moreCommandsButton;
    private GuiButton chatMovementButton;
    private GuiButton recentOpponentsButton;
    private GuiButton mobHealthDisplayButton;
    private GuiButton mainMenuQueueButtonsButton;
    private GuiButton backButton;

    private int baseGridTop;
    private int gridRowStep;
    private int gridLeftX;
    private int gridRightX;
    private int gridCenterX;
    private int gridPixelHeight;
    private int scrollContentTop;
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private final List<GuiButton> gridButtons = new ArrayList<GuiButton>();

    public MiscellaneousGUI(GuiScreen parent) {
        this.parent = parent;
    }

    /** Recomputes grid anchor and scroll region for the current scaled size (GUI scale / resolution). */
    private void computeGridLayout(int scaledWidth, int scaledHeight) {
        int centerX = scaledWidth / 2;
        int colW = COLUMN_WIDTH;
        int colGap = COLUMN_GAP;
        int rowStep = BUTTON_HEIGHT + 5;
        int titleBottom = 52;
        int backBarTop = GuiHeader.backButtonY(scaledHeight, BUTTON_HEIGHT);
        int moreCommandsBarTop = backBarTop - BUTTON_HEIGHT - 5;
        int contentTop = titleBottom + 12;
        int contentBottom = moreCommandsBarTop - 10;
        int gridH = rowStep * (GRID_ROWS - 1) + BUTTON_HEIGHT;
        int slack = contentBottom - contentTop - gridH;
        int gridTop = contentTop + Math.max(0, slack / 2);
        if (gridTop + gridH > contentBottom) {
            gridTop = Math.max(contentTop, contentBottom - gridH);
        }
        baseGridTop = gridTop;
        gridRowStep = rowStep;
        gridPixelHeight = gridH;
        scrollContentTop = gridTop;
        int gridW = colW * GRID_COLUMNS + colGap * (GRID_COLUMNS - 1);
        gridLeftX = centerX - gridW / 2;
        gridCenterX = centerX;
        gridRightX = gridLeftX + colW + colGap;
        if (moreCommandsButton != null) {
            moreCommandsButton.yPosition = moreCommandsBarTop;
            moreCommandsButton.xPosition = centerX - BUTTON_WIDTH / 2;
        }
        if (backButton != null) {
            backButton.yPosition = backBarTop;
            backButton.xPosition = centerX - BUTTON_WIDTH / 2;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        UselessItemOverlay.loadSettings();
        DcMirrorBridge.loadSettings();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = sr.getScaledWidth() / 2;
        computeGridLayout(sr.getScaledWidth(), sr.getScaledHeight());
        int colW = COLUMN_WIDTH;

        lobbyPingButton = new GuiButton(8, 0, 0, colW, BUTTON_HEIGHT, getLobbyPingText());
        starTextButton = new GuiButton(7, 0, 0, colW, BUTTON_HEIGHT, getStarTextText());
        tntPlacementPreviewButton = new GuiButton(30, 0, 0, colW, BUTTON_HEIGHT, getTntPlacementPreviewText());

        heldItemInfoButton = new GuiButton(31, 0, 0, colW, BUTTON_HEIGHT, getHeldItemInfoText());
        mountHeadDimButton = new GuiButton(11, 0, 0, colW, BUTTON_HEIGHT, getTransparentMountsEntryText());
        selfOpacityButton = new GuiButton(25, 0, 0, colW, BUTTON_HEIGHT, getSelfOpacityText());
        waterClarityButton = new GuiButton(19, 0, 0, colW, BUTTON_HEIGHT, getWaterClarityText());
        guiBackdropSettingsButton = new GuiButton(18, 0, 0, colW, BUTTON_HEIGHT, getGuiBackdropModuleText());
        barrierVisibilityEntryButton = new GuiButton(27, 0, 0, colW, BUTTON_HEIGHT, getBarrierVisibilityEntryText());
        witchSoundsButton = new GuiButton(10, 0, 0, colW, BUTTON_HEIGHT, getWitchSoundsText());
        lightningNerfButton = new GuiButton(29, 0, 0, colW, BUTTON_HEIGHT, getLightningNerfText());
        lootCallButton = new GuiButton(3, 0, 0, colW, BUTTON_HEIGHT, getLootCallText());
        askForLootButton = new GuiButton(5, 0, 0, colW, BUTTON_HEIGHT, getAskForLootText());
        playerCallerButton = new GuiButton(6, 0, 0, colW, BUTTON_HEIGHT, getPlayerCallerText());
        horseDonkeyButton = new GuiButton(15, 0, 0, colW, BUTTON_HEIGHT, getHorseDonkeyText());
        mountXpOverlayButton = new GuiButton(36, 0, 0, colW, BUTTON_HEIGHT, getMountXpOverlayText());
        blocksFinderButton = new GuiButton(28, 0, 0, colW, BUTTON_HEIGHT, getBlocksFinderText());
        chestRoutesButton = new GuiButton(12, 0, 0, colW, BUTTON_HEIGHT, getChestRoutesEntryText());
        radialMenuButton = new GuiButton(20, 0, 0, colW, BUTTON_HEIGHT, getRadialMenuText());
        if (GomodInternalFeatures.isEnabled()) {
            dcMirrorChatButton = new GuiButton(26, 0, 0, colW, BUTTON_HEIGHT, getDcMirrorChatText());
        }

        bestArmorButton = new GuiButton(1, 0, 0, colW, BUTTON_HEIGHT, getInventoryQoledEntrySummary());
        cageSettingsButton = new GuiButton(24, 0, 0, colW, BUTTON_HEIGHT, getCageSettingsText());
        blitzVaultItemsButton = new GuiButton(32, 0, 0, colW, BUTTON_HEIGHT, getBlitzVaultItemsText());
        blitzChestItemsButton = new GuiButton(33, 0, 0, colW, BUTTON_HEIGHT, getBlitzChestItemsText());
        denickerButton = new GuiButton(34, 0, 0, colW, BUTTON_HEIGHT, getDenickerText());
        blockOverlayButton = new GuiButton(35, 0, 0, colW, BUTTON_HEIGHT, getBlockOverlayText());
        blitzExtendedResultsButton = new GuiButton(39, 0, 0, colW, BUTTON_HEIGHT, getBlitzExtendedResultsText());
        deathmatchTimerButton = new GuiButton(45, 0, 0, colW, BUTTON_HEIGHT, getDeathmatchTimerText());
        mainMenuQueueButtonsButton = new GuiButton(46, 0, 0, colW, BUTTON_HEIGHT, getMainMenuQueueButtonsText());
        blitzPregamePlayerToggleButton = new GuiButton(41, 0, 0, colW, BUTTON_HEIGHT, getBlitzPregamePlayerToggleText());

        visibleSpecsButton = new GuiButton(2, 0, 0, colW, BUTTON_HEIGHT, getVisibleSpecsText());
        nametagsSettingsButton = new GuiButton(22, 0, 0, colW, BUTTON_HEIGHT, getNametagsSettingsText());
        fixesButton = new GuiButton(38, 0, 0, colW, BUTTON_HEIGHT, getFixesText());

        gomodServiceButton = new GuiButton(13, 0, 0, colW, BUTTON_HEIGHT, getGomodServiceEntryText());
        gomodMenuSettingsButton = new GuiButton(23, 0, 0, colW, BUTTON_HEIGHT, getGomodMenuSettingsText());
        if (GomodFixes.isWeaveRuntime()) {
            lunarTweaksButton = new GuiButton(42, 0, 0, colW, BUTTON_HEIGHT, getLunarTweaksText());
        }
        chatMovementButton = new GuiButton(40, 0, 0, colW, BUTTON_HEIGHT, getChatMovementText());
        mobHealthDisplayButton = new GuiButton(44, 0, 0, colW, BUTTON_HEIGHT,
                EnumChatFormatting.GOLD + "Mob Health Display");
        if (GomodInternalFeatures.isEnabled()) {
            recentOpponentsButton = new GuiButton(43, 0, 0, colW, BUTTON_HEIGHT,
                    EnumChatFormatting.GOLD + "Recent Opponents");
        }
        int backY = GuiHeader.backButtonY(sr.getScaledHeight(), BUTTON_HEIGHT);
        int moreCommandsY = backY - BUTTON_HEIGHT - 5;
        moreCommandsButton = new GuiButton(37, centerX - BUTTON_WIDTH / 2, moreCommandsY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "More commands");
        backButton = new GuiButton(0, centerX - BUTTON_WIDTH / 2, backY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");

        gridButtons.clear();
        // White-text feature rows (always shown before orange/gold rows).
        gridButtons.add(lobbyPingButton);
        gridButtons.add(starTextButton);
        gridButtons.add(mainMenuQueueButtonsButton);

        // Gold feature rows with an ON/OFF state.
        gridButtons.add(mountHeadDimButton);
        gridButtons.add(blitzExtendedResultsButton);
        gridButtons.add(deathmatchTimerButton);
        gridButtons.add(heldItemInfoButton);
        gridButtons.add(selfOpacityButton);
        gridButtons.add(waterClarityButton);
        gridButtons.add(guiBackdropSettingsButton);
        gridButtons.add(barrierVisibilityEntryButton);
        gridButtons.add(blockOverlayButton);
        gridButtons.add(lootCallButton);
        gridButtons.add(askForLootButton);
        gridButtons.add(playerCallerButton);
        gridButtons.add(horseDonkeyButton);
        gridButtons.add(mountXpOverlayButton);
        gridButtons.add(blocksFinderButton);
        gridButtons.add(chestRoutesButton);
        gridButtons.add(radialMenuButton);
        if (dcMirrorChatButton != null) gridButtons.add(dcMirrorChatButton);
        gridButtons.add(tntPlacementPreviewButton);

        // Gold settings/browser rows without an ON/OFF state.
        gridButtons.add(blitzPregamePlayerToggleButton);
        gridButtons.add(denickerButton);
        gridButtons.add(lightningNerfButton);
        gridButtons.add(cageSettingsButton);
        gridButtons.add(blitzVaultItemsButton);
        gridButtons.add(blitzChestItemsButton);
        gridButtons.add(bestArmorButton);
        gridButtons.add(visibleSpecsButton);
        gridButtons.add(nametagsSettingsButton);
        gridButtons.add(chatMovementButton);
        gridButtons.add(mobHealthDisplayButton);
        gridButtons.add(witchSoundsButton);

        // gomod utility rows always stay last.
        gridButtons.add(fixesButton);
        gridButtons.add(gomodServiceButton);
        gridButtons.add(gomodMenuSettingsButton);
        if (lunarTweaksButton != null) gridButtons.add(lunarTweaksButton);
        if (recentOpponentsButton != null) gridButtons.add(recentOpponentsButton);

        applyGridScroll(baseGridTop);

        this.buttonList.addAll(gridButtons);
        this.buttonList.add(moreCommandsButton);
        this.buttonList.add(backButton);

        // Auto-scroll to highlighted button if navigated here from search.
        scrollToHighlightedButtonIfNeeded(sr.getScaledHeight());
        applyPublicReleaseMiscButtonState();
    }

    private void applyPublicReleaseMiscButtonState() {
        if (dcMirrorChatButton != null) {
            if (!GomodInternalFeatures.isEnabled()) {
                dcMirrorChatButton.enabled = false;
                dcMirrorChatButton.displayString = EnumChatFormatting.DARK_GRAY + "DC";
            } else {
                dcMirrorChatButton.enabled = true;
                dcMirrorChatButton.displayString = getDcMirrorChatText();
            }
        }
        if (blitzChestItemsButton != null) {
            if (!GomodInternalFeatures.isBlitzChestItemsBrowserEnabled()) {
                blitzChestItemsButton.enabled = false;
                blitzChestItemsButton.displayString = EnumChatFormatting.DARK_GRAY + "Blitz Chest items";
            } else {
                blitzChestItemsButton.enabled = true;
                blitzChestItemsButton.displayString = getBlitzChestItemsText();
            }
        }
    }

    private void applyGridScroll(int gridTop) {
        int fullRows = gridButtons.size() / GRID_COLUMNS;
        int lastRowCount = gridButtons.size() % GRID_COLUMNS;
        for (int i = 0; i < gridButtons.size(); i++) {
            GuiButton button = gridButtons.get(i);
            if (button == null) {
                continue;
            }
            int row = i / GRID_COLUMNS;
            int col = i % GRID_COLUMNS;
            int xBase = gridLeftX;
            if (lastRowCount > 0 && row == fullRows) {
                int partialRowWidth = lastRowCount * COLUMN_WIDTH + (lastRowCount - 1) * COLUMN_GAP;
                xBase = gridCenterX - partialRowWidth / 2;
            }
            button.xPosition = xBase + col * (COLUMN_WIDTH + COLUMN_GAP);
            button.yPosition = rowY(gridTop, row);
        }
    }

    /**
     * When arriving here via search navigation, instantly scroll so the highlighted button is
     * vertically centred in the visible area.
     */
    private void scrollToHighlightedButtonIfNeeded(int scaledHeight) {
        if (!GomodSearchHighlight.matchesGui(MiscellaneousGUI.class)) return;
        int pendingId = GomodSearchHighlight.getButtonId();
        if (pendingId < 0) return;
        for (GuiButton b : buttonList) {
            if (b == null || b.id != pendingId) continue;
            int vb = GuiHeader.backButtonY(scaledHeight, BUTTON_HEIGHT) - BUTTON_HEIGHT - 5 - 10;
            int vh = vb - scrollContentTop;
            int target = b.yPosition - scrollContentTop - vh / 2 + BUTTON_HEIGHT / 2;
            scroll.setOffsetClamped(Math.max(0, target));
            break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = sr.getScaledWidth() / 2;

        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Miscellaneous" + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        String desc = EnumChatFormatting.GRAY + "Toggle miscellaneous features:";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, desc, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        scroll.tick();
        computeGridLayout(sr.getScaledWidth(), sr.getScaledHeight());
        int visibleBottom = GuiHeader.backButtonY(sr.getScaledHeight(), BUTTON_HEIGHT) - BUTTON_HEIGHT - 5 - 10;
        scroll.updateBounds(scrollContentTop, scrollContentTop + gridPixelHeight, scrollContentTop, visibleBottom);
        int gridTop = baseGridTop - scroll.getOffset();
        applyGridScroll(gridTop);
        scroll.drawScrollbar(sr.getScaledWidth(), mouseY);

        scroll.drawFadedButtons(mc, fontRendererObj, gridButtons, mouseX, mouseY);
        if (moreCommandsButton != null && moreCommandsButton.visible) {
            moreCommandsButton.drawButton(mc, mouseX, mouseY);
        }
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        applyPublicReleaseMiscButtonState();
        GomodSearchHighlight.draw(buttonList, MiscellaneousGUI.class);
        drawHoverTooltip(mouseX, mouseY);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                this.mc.displayGuiScreen(parent);
                break;
            case 37:
                this.mc.displayGuiScreen(new MiscellaneousMoreCommandsHelpGUI(this));
                break;
            case 1:
                this.mc.displayGuiScreen(new InventoryQolSettingsGUI(this));
                break;
            case 2:
                this.mc.displayGuiScreen(new VisibleSpectatorsGUI(this));
                break;
            case 3:
                this.mc.displayGuiScreen(new LootCallSettingsGUI(this));
                break;
            case 5:
                this.mc.displayGuiScreen(new AskForLootSettingsGUI(this));
                break;
            case 7:
                ConfigManager.setStarTextEnabled(!ConfigManager.isStarTextEnabled());
                starTextButton.displayString = getStarTextText();
                break;
            case 31:
                this.mc.displayGuiScreen(new HeldItemInfoSettingsGUI(this));
                break;
            case 6:
                this.mc.displayGuiScreen(new PlayerCallerSettingsGUI(this));
                break;
            case 8:
                LobbyPingDisplay.setEnabled(!LobbyPingDisplay.isEnabled());
                lobbyPingButton.displayString = getLobbyPingText();
                break;
            case 10:
                this.mc.displayGuiScreen(new WitchSoundsSettingsGUI(this));
                break;
            case 11:
                this.mc.displayGuiScreen(new TransparentMountsGUI(this));
                break;
            case 12:
                this.mc.displayGuiScreen(new ChestRoutesMapsGUI(this));
                break;
            case 13:
                this.mc.displayGuiScreen(new GomodServiceGUI(this));
                break;
            case 15:
                this.mc.displayGuiScreen(new HorseDonkeySettingsGUI(this));
                break;
            case 36:
                this.mc.displayGuiScreen(new MountXpOverlaySettingsGUI(this));
                break;
            case 18:
                this.mc.displayGuiScreen(new GuiBackdropSettingsGUI(this));
                break;
            case 19:
                this.mc.displayGuiScreen(new WaterClaritySettingsGUI(this));
                break;
            case 20:
                this.mc.displayGuiScreen(new RadialMenuWheelSlotsGUI(this));
                break;
            case 23:
                this.mc.displayGuiScreen(new GomodMenuSettingsGUI(this));
                break;
            case 24:
                this.mc.displayGuiScreen(new CageSettingsGUI(this));
                break;
            case 25:
                this.mc.displayGuiScreen(new SelfOpacityGUI(this));
                break;
            case 26:
                if (!GomodInternalFeatures.isEnabled()) {
                    return;
                }
                this.mc.displayGuiScreen(new DcMirrorSettingsGUI(this));
                break;
            case 27:
                this.mc.displayGuiScreen(new BarrierVisibilitySettingsGUI(this));
                break;
            case 22:
                this.mc.displayGuiScreen(new NametagsSettingsGUI(this));
                break;
            case 28:
                this.mc.displayGuiScreen(new BlocksFinderGUI(this));
                break;
            case 29:
                this.mc.displayGuiScreen(new LightningSettingsGUI(this));
                break;
            case 30:
                this.mc.displayGuiScreen(new PlacementPreviewSettingsGUI(this));
                break;
            case 32:
                this.mc.displayGuiScreen(new BlitzVaultItemsGUI(this));
                break;
            case 33:
                if (!GomodInternalFeatures.isBlitzChestItemsBrowserEnabled()) {
                    return;
                }
                this.mc.displayGuiScreen(new BlitzChestItemsGUI(this));
                break;
            case 34:
                this.mc.displayGuiScreen(new DenickerSettingsGUI(this));
                break;
            case 35:
                this.mc.displayGuiScreen(new GomodBlockOverlaySettingsGUI(this));
                break;
            case 38:
                this.mc.displayGuiScreen(new FixesSettingsGUI(this));
                break;
            case 39:
                this.mc.displayGuiScreen(new BlitzEndGameScoreGUI(this));
                break;
            case 40:
                this.mc.displayGuiScreen(new ChatMovementSettingsGUI(this));
                break;
            case 41:
                this.mc.displayGuiScreen(new PregameItemsSettingsGUI(this));
                break;
            case 42:
                if (GomodFixes.isWeaveRuntime()) {
                    this.mc.displayGuiScreen(new LunarTweaksGUI(this));
                }
                break;
            case 43:
                if (GomodInternalFeatures.isEnabled()) {
                    this.mc.displayGuiScreen(new RecentOpponentsGUI(this));
                }
                break;
            case 44:
                this.mc.displayGuiScreen(new MobHealthDisplayGUI(this));
                break;
            case 45:
                this.mc.displayGuiScreen(new DeathmatchTimerSettingsGUI(this));
                break;
            case 46:
                ConfigManager.setMainMenuQueueButtonsEnabled(!ConfigManager.isMainMenuQueueButtonsEnabled());
                if (mainMenuQueueButtonsButton != null) {
                    mainMenuQueueButtonsButton.displayString = getMainMenuQueueButtonsText();
                }
                break;
        }
    }

    private String getMainMenuQueueButtonsText() {
        return EnumChatFormatting.WHITE + "Main Menu Queue: "
                + (ConfigManager.isMainMenuQueueButtonsEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getGuiBackdropModuleText() {
        boolean on = !"VANILLA".equals(ConfigManager.getGuiBackdropMode());
        return EnumChatFormatting.GOLD + "GUI backdrop & blur: "
                + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getWaterClarityText() {
        return EnumChatFormatting.GOLD + "Water clarity: "
                + (WaterClarity.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getRadialMenuText() {
        return EnumChatFormatting.GOLD + "Wheel modules: "
                + (RadialMenuWheel.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getGomodMenuSettingsText() {
        return EnumChatFormatting.GOLD + "gomod Menu Settings";
    }

    private String getLunarTweaksText() {
        return EnumChatFormatting.GOLD + "Lunar FullAccess";
    }

    private String getChatMovementText() {
        return EnumChatFormatting.GOLD + "Chat Settings";
    }

    private String getCageSettingsText() {
        return EnumChatFormatting.GOLD + "Cage features";
    }

    private String getSelfOpacityText() {
        return EnumChatFormatting.GOLD + "Self opacity: "
                + (SelfOpacity.isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getBarrierVisibilityEntryText() {
        return EnumChatFormatting.GOLD + "Barrier visibility: "
                + (ConfigManager.isBarrierVisibilityEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getDcMirrorChatText() {
        if (!GomodInternalFeatures.isEnabled()) {
            return EnumChatFormatting.DARK_GRAY + "DC";
        }
        return EnumChatFormatting.GOLD + "DC: "
                + (DcMirrorBridge.isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    void refreshDcMirrorButtonLabel() {
        if (dcMirrorChatButton != null) {
            dcMirrorChatButton.displayString = getDcMirrorChatText();
        }
    }

    void refreshInventoryQolButtonLabel() {
        if (bestArmorButton != null) {
            bestArmorButton.displayString = getInventoryQoledEntrySummary();
        }
        UselessItemOverlay.loadSettings();
    }

    private String getInventoryQoledEntrySummary() {
        return EnumChatFormatting.GOLD + "Inventory QoL";
    }

    private String getVisibleSpecsText() {
        return EnumChatFormatting.GOLD + "Spectator Settings";
    }

    void refreshVisibleSpecsButtonLabel() {
        if (visibleSpecsButton != null) {
            visibleSpecsButton.displayString = getVisibleSpecsText();
        }
    }

    private String getLootCallText() {
        return EnumChatFormatting.GOLD + "Loot Call: "
                + (LootCall.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    public void refreshLootCallButtonLabel() {
        if (lootCallButton != null) {
            lootCallButton.displayString = getLootCallText();
        }
    }

    private String getStarTextText() {
        return "Better BLITZ texts: " + (ConfigManager.isStarTextEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getHeldItemInfoText() {
        return EnumChatFormatting.GOLD + "Held item info: "
                + (ConfigManager.isHotbarEnchantTextEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    void refreshHeldItemInfoButtonLabel() {
        if (heldItemInfoButton != null) {
            heldItemInfoButton.displayString = getHeldItemInfoText();
        }
    }

    private String getLobbyPingText() {
        return "Lobby Ping: " + LobbyPingDisplay.getToggleLabel();
    }

    private String getWitchSoundsText() {
        return EnumChatFormatting.GOLD + "Mob Sounds";
    }

    /** Main Miscellaneous menu row — opens {@link TransparentMountsGUI}. */
    private String getTransparentMountsEntryText() {
        return EnumChatFormatting.GOLD + "Transparent mounts: " + (MountHeadDim.isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getChestRoutesEntryText() {
        return EnumChatFormatting.GOLD + "Chestroutes: "
                + (ConfigManager.isChestRoutesRenderEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getNametagsSettingsText() {
        return EnumChatFormatting.GOLD + "Nametag Settings";
    }

    private String getFixesText() {
        return EnumChatFormatting.GOLD + "Fixes";
    }

    private String getBlitzExtendedResultsText() {
        return EnumChatFormatting.GOLD + "Detailed end-game score: "
                + (ConfigManager.isBlitzExtendedResultsEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getDeathmatchTimerText() {
        return EnumChatFormatting.GOLD + "Deathmatch timer: "
                + (ConfigManager.isDeathmatchTimerBossBarEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    public void refreshDeathmatchTimerButtonLabel() {
        if (deathmatchTimerButton != null) {
            deathmatchTimerButton.displayString = getDeathmatchTimerText();
        }
    }

    private String getBlitzPregamePlayerToggleText() {
        return EnumChatFormatting.GOLD + "Pre-game tools";
    }

    void refreshBlitzExtendedResultsButtonLabel() {
        if (blitzExtendedResultsButton != null) {
            blitzExtendedResultsButton.displayString = getBlitzExtendedResultsText();
        }
    }

    void refreshFixesButtonLabel() {
        if (fixesButton != null) {
            fixesButton.displayString = getFixesText();
        }
    }

    private String getAskForLootText() {
        return EnumChatFormatting.GOLD + "Ask for Loot: " + (AskForLoot.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getPlayerCallerText() {
        return EnumChatFormatting.GOLD + "Player caller: "
                + (PlayerCaller.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getGomodServiceEntryText() {
        return EnumChatFormatting.GOLD + "gomod Service";
    }


    private String getHorseDonkeyText() {
        return EnumChatFormatting.GOLD + "Horse tracker: "
                + (HorseDonkeyTracker.isAnyFeatureEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getMountXpOverlayText() {
        return EnumChatFormatting.GOLD + "Mount XP overlay: "
                + (MountXpOverlay.isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    public void refreshMountXpOverlayButtonLabel() {
        if (mountXpOverlayButton != null) {
            mountXpOverlayButton.displayString = getMountXpOverlayText();
        }
    }


    /** Called when returning from {@link AskForLootSettingsGUI} so the row label stays in sync. */
    void refreshAskForLootButtonLabel() {
        if (askForLootButton != null) {
            askForLootButton.displayString = getAskForLootText();
        }
    }

    void refreshPlayerCallerButtonLabel() {
        if (playerCallerButton != null) {
            playerCallerButton.displayString = getPlayerCallerText();
        }
    }

    void refreshRadialMenuButtonLabel() {
        if (radialMenuButton != null) {
            radialMenuButton.displayString = getRadialMenuText();
        }
    }

    void refreshGomodMenuSettingsButtonLabel() {
        if (gomodMenuSettingsButton != null) {
            gomodMenuSettingsButton.displayString = getGomodMenuSettingsText();
        }
    }

    void refreshChatMovementButtonLabel() {
        if (chatMovementButton != null) {
            chatMovementButton.displayString = getChatMovementText();
        }
    }

    public void refreshWitchSoundsButtonLabel() {
        if (witchSoundsButton != null) {
            witchSoundsButton.displayString = getWitchSoundsText();
        }
    }

    public void refreshGomodServiceButtonLabel() {
        if (gomodServiceButton != null) {
            gomodServiceButton.displayString = getGomodServiceEntryText();
        }
    }

    public void refreshHorseDonkeyButtonLabel() {
        if (horseDonkeyButton != null) {
            horseDonkeyButton.displayString = getHorseDonkeyText();
        }
    }


    public void refreshWaterClarityButtonLabel() {
        if (waterClarityButton != null) {
            waterClarityButton.displayString = getWaterClarityText();
        }
    }

    void refreshSelfOpacityButtonLabel() {
        if (selfOpacityButton != null) {
            selfOpacityButton.displayString = getSelfOpacityText();
        }
    }

    public void refreshBarrierVisibilityEntryButton() {
        if (barrierVisibilityEntryButton != null) {
            barrierVisibilityEntryButton.displayString = getBarrierVisibilityEntryText();
        }
    }

    private String getBlocksFinderText() {
        return EnumChatFormatting.GOLD + "Block Finder: "
                + (BlocksFinder.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    public void refreshBlocksFinderButtonLabel() {
        if (blocksFinderButton != null) {
            blocksFinderButton.displayString = getBlocksFinderText();
        }
    }

    private String getLightningNerfText() {
        return EnumChatFormatting.GOLD + "Thunder Settings";
    }

    public void refreshLightningSettingsButtonLabel() {
        if (lightningNerfButton != null) {
            lightningNerfButton.displayString = getLightningNerfText();
        }
    }

    /** @deprecated use {@link #refreshLightningSettingsButtonLabel()} */
    @Deprecated
    public void refreshLightningNerfButtonLabel() {
        refreshLightningSettingsButtonLabel();
    }

    private String getTntPlacementPreviewText() {
        return EnumChatFormatting.GOLD + "Placement preview";
    }

    private String getBlitzVaultItemsText() {
        return EnumChatFormatting.GOLD + "Blitz Vault items";
    }

    private String getBlitzChestItemsText() {
        return EnumChatFormatting.GOLD + "Blitz Chest items";
    }

    private String getDenickerText() {
        return EnumChatFormatting.GOLD + "Denicker Tools";
    }

    public void refreshDenickerButtonLabel() {
        if (denickerButton != null) {
            denickerButton.displayString = getDenickerText();
        }
    }

    private String getBlockOverlayText() {
        return EnumChatFormatting.GOLD + "Block overlay: "
                + (GomodBlockOverlay.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    public void refreshBlockOverlayButtonLabel() {
        if (blockOverlayButton != null) {
            blockOverlayButton.displayString = getBlockOverlayText();
        }
    }

    public void refreshTntPlacementPreviewButtonLabel() {
        if (tntPlacementPreviewButton != null) {
            tntPlacementPreviewButton.displayString = getTntPlacementPreviewText();
        }
    }

    void refreshSidebarTweaksButtonLabel() {
        if (chestRoutesButton != null) {
            chestRoutesButton.displayString = getChestRoutesEntryText();
        }
        if (mountHeadDimButton != null) {
            mountHeadDimButton.displayString = getTransparentMountsEntryText();
        }
        if (gomodServiceButton != null) {
            gomodServiceButton.displayString = getGomodServiceEntryText();
        }
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (isMouseOver(bestArmorButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Inventory QoL",
                    EnumChatFormatting.GRAY + "Various QoL features for inventory");
            return;
        }
        if (isMouseOver(visibleSpecsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Spectator settings",
                    EnumChatFormatting.GRAY + "Spectator opacity & nametag settings");
            return;
        }
        if (isMouseOver(lootCallButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Loot Call",
                    EnumChatFormatting.GRAY + "Automatically announces specific loots to your teammate when you pick them up");
            return;
        }
        if (isMouseOver(nametagsSettingsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Nametag Settings",
                    EnumChatFormatting.GRAY + "Configure teammate LootESP, mob nametags and mob target display");
            return;
        }
        if (isMouseOver(fixesButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Fixes",
                    EnumChatFormatting.GRAY + "Hypixel TAB cleanup, nametags through chests,",
                    EnumChatFormatting.GRAY + "thunder render, and invisible-player fix");
            return;
        }
        if (GomodFixes.isWeaveRuntime() && isMouseOver(lunarTweaksButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Lunar FullAccess",
                    EnumChatFormatting.GRAY + "Configure Lunar cosmetics, emotes,",
                    EnumChatFormatting.GRAY + "peer sharing and connection settings.");
            return;
        }
        if (isMouseOver(askForLootButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Ask for Loot",
                    EnumChatFormatting.GRAY + "Instantly ask for required loot you need in teams");
            return;
        }
        if (isMouseOver(playerCallerButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Player caller",
                    EnumChatFormatting.GRAY + "Types in chat the username of the player you are looking at");
            return;
        }
        if (isMouseOver(starTextButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Better BLITZ texts",
                    EnumChatFormatting.GRAY + "Shows a large center-screen text when the Blitz Star is found or used");
            return;
        }
        if (isMouseOver(blitzExtendedResultsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Detailed end-game score",
                    EnumChatFormatting.GRAY + "Shows more infos about the game result at the end of Blitz games");
            return;
        }
        if (isMouseOver(deathmatchTimerButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Deathmatch Timer",
                    EnumChatFormatting.GRAY + "Shows Blitz Deathmatch Game End on the Ender Dragon boss bar",
                    EnumChatFormatting.GRAY + "when remaining time reaches "
                            + ConfigManager.getDeathmatchTimerBossBarThresholdSeconds() + "s");
            return;
        }
        if (isMouseOver(blitzPregamePlayerToggleButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Pre-game tools",
                    EnumChatFormatting.GRAY + "Configure client-side Blitz pregame hotbar tools:",
                    EnumChatFormatting.GRAY + "Player visibility dye, Queue status skull, and Requeue paper");
            return;
        }
        if (isMouseOver(heldItemInfoButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Held item info",
                    EnumChatFormatting.GRAY + "Displays held items' enchantments and potion durations");
            return;
        }
        if (isMouseOver(lobbyPingButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Lobby Ping",
                    EnumChatFormatting.GRAY + "Shows players' ping while in lobbies");
            return;
        }
        if (isMouseOver(witchSoundsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Mob Sounds",
                    EnumChatFormatting.GRAY + "Configure Witch SFX volumes and mute",
                    EnumChatFormatting.GRAY + "stepping sounds of spawned Ocelots & Witches");
            return;
        }
        if (isMouseOver(mountHeadDimButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Transparent mounts",
                    EnumChatFormatting.GRAY + "Makes mounts less visible while riding them");
            return;
        }
        if (isMouseOver(chestRoutesButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Chest routes",
                    EnumChatFormatting.GRAY + "Manage chestroutes");
            return;
        }
        if (isMouseOver(gomodServiceButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "gomod Service",
                    EnumChatFormatting.GRAY + "Friends, screenshots, Friend Inventory HUD");
            return;
        }
        if (isMouseOver(horseDonkeyButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Horse tracker",
                    EnumChatFormatting.GRAY + "Configure the horse & donkey HUD");
            return;
        }
        if (isMouseOver(mountXpOverlayButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Mount XP overlay",
                    EnumChatFormatting.GRAY + "Shows XP while riding a mob");
            return;
        }
        if (isMouseOver(guiBackdropSettingsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "GUI backdrop & blur",
                    EnumChatFormatting.GRAY + "Modify GUI background and blur");
            return;
        }
        if (isMouseOver(waterClarityButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Water clarity",
                    EnumChatFormatting.GRAY + "Reduces water texture's opacity");
            return;
        }
        if (isMouseOver(lightningNerfButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Thunder Settings",
                    EnumChatFormatting.GRAY + "Shorten thunder, tune sound, and tint color");
            return;
        }
        if (isMouseOver(tntPlacementPreviewButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Placement preview",
                    EnumChatFormatting.GRAY + "Shows a preview of TNT and certain mobs' placement");
            return;
        }
        if (isMouseOver(blitzVaultItemsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Blitz Vault items",
                    EnumChatFormatting.GRAY + "Statistics for Blitz Vault items (from a large amount of logged vault chests)");
            return;
        }
        if (isMouseOver(blitzChestItemsButton, mouseX, mouseY)) {
            if (!GomodInternalFeatures.isBlitzChestItemsBrowserEnabled()) {
                drawTooltip(mouseX, mouseY,
                        EnumChatFormatting.GRAY + "Blitz Chest items",
                        EnumChatFormatting.DARK_GRAY + "Not available in this build");
            } else {
                drawTooltip(mouseX, mouseY,
                        EnumChatFormatting.AQUA + "Blitz Chest items",
                        EnumChatFormatting.GRAY + "Statistics for Blitz chest items (from a large amount of logged chests)");
            }
            return;
        }
        if (isMouseOver(denickerButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Denicker Tools",
                    EnumChatFormatting.GRAY + "Denicker nametags/TAB/skins, nick renamed-item detector,",
                    EnumChatFormatting.GRAY + "and clickable death [SEARCH NICK] kill-count links");
            return;
        }
        if (isMouseOver(blockOverlayButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Block overlay",
                    EnumChatFormatting.GRAY + "Colored crosshair block highlight (built-in; disable Aycy Block Overlay mod)");
            return;
        }
        if (isMouseOver(gomodMenuSettingsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "gomod Menu Settings",
                    EnumChatFormatting.GRAY + "Configure gomod menu behaviorS");
            return;
        }
        if (isMouseOver(chatMovementButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Chat Settings",
                    EnumChatFormatting.GRAY + "Configure actions while chat is open",
                    EnumChatFormatting.GRAY + "and right-click camera dragging");
            return;
        }
        if (isMouseOver(cageSettingsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Cage features",
                    EnumChatFormatting.GRAY + "Features for cage phase");
            return;
        }
        if (isMouseOver(selfOpacityButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Self opacity",
                    EnumChatFormatting.GRAY + "Modify your skin's opacity");
            return;
        }
        if (isMouseOver(barrierVisibilityEntryButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Barrier visibility",
                    EnumChatFormatting.GRAY + "Modify barrier visibility settings");
            return;
        }
        if (isMouseOver(blocksFinderButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Block Finder",
                    EnumChatFormatting.GRAY + "Applies ESP over specific blocks in singleplayer & replays");
            return;
        }
        if (isMouseOver(dcMirrorChatButton, mouseX, mouseY)) {
            if (!GomodInternalFeatures.isEnabled()) {
                drawTooltip(mouseX, mouseY,
                        EnumChatFormatting.GRAY + "DC",
                        EnumChatFormatting.DARK_GRAY + "Not available in this build");
            } else {
                drawTooltip(mouseX, mouseY,
                        EnumChatFormatting.AQUA + "DC",
                        EnumChatFormatting.GRAY + "Send and receive messages in Minecraft chat");
            }
            return;
        }
        if (isMouseOver(radialMenuButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Wheel modules",
                    EnumChatFormatting.GRAY + "Module shortcuts");
            return;
        }
        if (isMouseOver(mainMenuQueueButtonsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Main Menu Queue",
                    EnumChatFormatting.GRAY + "Show Queue Solo and Queue Teams buttons on the main menu");
            return;
        }
    }

    private void drawTooltip(int mouseX, int mouseY, String... lines) {
        List<String> tooltip = Arrays.asList(lines);
        drawHoveringText(tooltip, mouseX, Math.max(0, mouseY - 12));
    }

    private int rowY(int gridTop, int row) {
        return gridTop + row * gridRowStep;
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + button.height;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

}
