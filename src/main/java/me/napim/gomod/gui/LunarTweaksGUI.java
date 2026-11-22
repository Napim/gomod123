package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.GomodPeerLunarFullAccess;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.features.LunarOnlineStatus;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.shareinv.ShareInvManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/** Miscellaneous -> Lunar FullAccess settings for the Lunar/Weave cosmetic relay. */
public final class LunarTweaksGUI extends GuiScreen {

    private static final int CONTROL_WIDTH = 120;
    private static final int ROW = 25;
    private static final int SETTINGS_ROW_COUNT = 8;
    private static final int BUTTON_HEIGHT = 20;
    private static final int STATUS_LAST_Y = 79;
    private static final int STATUS_TO_BUTTON_GAP = 14;
    private static final int COMPACT_SETTINGS_TOP = 66;

    private final GuiScreen parent;
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private GuiButton lunarTweaksEnabledButton;
    private GuiButton lunarFullAccessAudienceButton;
    private GuiButton seePeerLunarFullAccessButton;
    private GuiButton clothCloakAccessButton;
    private GuiButton multipleSameTypeCosmeticsButton;
    private GuiButton hiddenCosmeticPlayersButton;
    private GuiButton limitedCosmeticsUnlockerButton;
    private GuiButton cosmeticSourceBadgesButton;
    private GuiButton backButton;
    private boolean compactLayout;
    private int baseRowY;
    private int labelX;
    private int controlX;

    public LunarTweaksGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        if (!GomodFixes.isWeaveRuntime()) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.initGui();
        scroll.reset();
        labelX = width / 2 - 200;
        controlX = width / 2 + 80;
        int screenHeight = height;
        compactLayout = screenHeight < 342;
        int visibleTop = settingsVisibleTop();
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(screenHeight, BUTTON_HEIGHT, 10);
        int contentHeight = (SETTINGS_ROW_COUNT - 1) * ROW + BUTTON_HEIGHT;
        baseRowY = visibleTop + Math.max(0, (visibleBottom - visibleTop - contentHeight) / 2);

        lunarTweaksEnabledButton = settingButton(1, 0, getLunarTweaksEnabledText());
        lunarFullAccessAudienceButton = settingButton(2, 1, getLunarFullAccessAudienceText());
        seePeerLunarFullAccessButton = settingButton(3, 2, getSeePeerLunarFullAccessText());
        limitedCosmeticsUnlockerButton = settingButton(6, 3, getLimitedCosmeticsUnlockerText());
        cosmeticSourceBadgesButton = settingButton(7, 4, getCosmeticSourceBadgesText());
        clothCloakAccessButton = settingButton(8, 5, getClothCloakAccessText());
        multipleSameTypeCosmeticsButton = settingButton(4, 6, getMultipleSameTypeCosmeticsText());
        hiddenCosmeticPlayersButton = settingButton(5, 7, getHiddenCosmeticPlayersText());
        backButton = new GuiButton(0, width / 2 - 100,
                GuiHeader.backButtonY(screenHeight, BUTTON_HEIGHT), 200, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");

        buttonList.clear();
        buttonList.add(lunarTweaksEnabledButton);
        buttonList.add(lunarFullAccessAudienceButton);
        buttonList.add(seePeerLunarFullAccessButton);
        buttonList.add(limitedCosmeticsUnlockerButton);
        buttonList.add(cosmeticSourceBadgesButton);
        buttonList.add(clothCloakAccessButton);
        buttonList.add(multipleSameTypeCosmeticsButton);
        buttonList.add(hiddenCosmeticPlayersButton);
        buttonList.add(backButton);
        refreshRuntimeState();
    }

    private GuiButton settingButton(int id, int row, String text) {
        return new GuiButton(id, controlX, baseRowY + row * ROW, CONTROL_WIDTH, BUTTON_HEIGHT, text);
    }

    private int settingsVisibleTop() {
        return compactLayout ? COMPACT_SETTINGS_TOP : STATUS_LAST_Y + STATUS_TO_BUTTON_GAP;
    }

    private boolean hasConnectedFullAccess() {
        return GomodFixes.isWeaveRuntime() && ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refreshRuntimeState();
    }

    private void refreshRuntimeState() {
        boolean connected = hasConnectedFullAccess();
        lunarFullAccessAudienceButton.enabled = connected;
        seePeerLunarFullAccessButton.enabled = connected;
        clothCloakAccessButton.enabled = GomodFixes.isWeaveRuntime();
        // This is a local equip/render preference and does not need the FullAccess route.
        multipleSameTypeCosmeticsButton.enabled = true;
        hiddenCosmeticPlayersButton.enabled = GomodFixes.isWeaveRuntime();
        limitedCosmeticsUnlockerButton.enabled = connected;
        cosmeticSourceBadgesButton.enabled = connected;
        lunarTweaksEnabledButton.displayString = getLunarTweaksEnabledText();
        lunarFullAccessAudienceButton.displayString = getLunarFullAccessAudienceText();
        seePeerLunarFullAccessButton.displayString = getSeePeerLunarFullAccessText();
        clothCloakAccessButton.displayString = getClothCloakAccessText();
        multipleSameTypeCosmeticsButton.displayString = getMultipleSameTypeCosmeticsText();
        hiddenCosmeticPlayersButton.displayString = getHiddenCosmeticPlayersText();
        limitedCosmeticsUnlockerButton.displayString = getLimitedCosmeticsUnlockerText();
        cosmeticSourceBadgesButton.displayString = getCosmeticSourceBadgesText();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Lunar FullAccess"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width,
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        if (!compactLayout) {
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                    EnumChatFormatting.GRAY + "Lunar cosmetic and relay integration",
                    width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        }
        int statusTop = compactLayout ? 34 : 53;
        int statusStep = compactLayout ? 9 : 13;
        boolean gomodOnline = ShareInvManager.isRelayOpen();
        LunarOnlineStatus.ConnectionState lunarState = LunarOnlineStatus.getLunarOnlineState();
        boolean fullAccessEnabled = ConfigManager.isLunarTweaksEnabled();
        LunarOnlineStatus.ConnectionState fullAccessState = LunarOnlineStatus.getFullAccessState();
        LunarConnectionDiagnostics.recordServiceSnapshot(gomodOnline,
                lunarState.name().toLowerCase(java.util.Locale.ROOT), fullAccessEnabled,
                fullAccessState.name().toLowerCase(java.util.Locale.ROOT));
        drawOnlineStatus("gomod Online", gomodOnline, statusTop);
        drawLunarOnlineStatus(lunarState, statusTop + statusStep);
        if (GomodFixes.isWeaveRuntime()) {
            drawFullAccessStatus(fullAccessEnabled, fullAccessState, statusTop + 2 * statusStep);
        }

        scroll.tick();
        int visibleTop = settingsVisibleTop();
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
        scroll.updateBounds(baseRowY,
                baseRowY + (SETTINGS_ROW_COUNT - 1) * ROW + BUTTON_HEIGHT,
                visibleTop, visibleBottom);
        applyLayout(scroll.getOffset(), visibleTop, visibleBottom);
        drawSettingLabels(visibleTop, visibleBottom);
        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 0);
        scroll.drawScrollbar(width, mouseY);
        backButton.drawButton(mc, mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, LunarTweaksGUI.class);
        drawHoverTooltip(mouseX, mouseY, lunarState, statusTop + statusStep,
                fullAccessState, statusTop + 2 * statusStep);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if ((button.id == 2 || button.id == 3 || button.id == 6 || button.id == 7)
                && !hasConnectedFullAccess()) {
            refreshRuntimeState();
            return;
        }
        switch (button.id) {
            case 0:
                mc.displayGuiScreen(parent);
                break;
            case 1:
                ConfigManager.setLunarTweaksEnabled(!ConfigManager.isLunarTweaksEnabled());
                PlayerStandaloneCape.notifyWearChanged();
                refreshRuntimeState();
                break;
            case 2:
                if (ConfigManager.isLunarTweaksEnabled()) {
                    ConfigManager.setLunarTweaksShowToOtherPlayers(
                            !ConfigManager.isLunarTweaksShowToOtherPlayers());
                    PlayerStandaloneCape.notifyWearChanged();
                    refreshRuntimeState();
                }
                break;
            case 3:
                if (ConfigManager.isLunarTweaksEnabled()) {
                    ConfigManager.setLunarTweaksSeeGomodPlayers(!ConfigManager.isLunarTweaksSeeGomodPlayers());
                    if (!ConfigManager.isLunarTweaksSeeGomodPlayers()) {
                        GomodPeerLunarFullAccess.stopHiddenPeerEmotes();
                    }
                    GomodPeerLunarFullAccess.refreshPeerPlusBadges();
                    PlayerStandaloneCape.notifyWearChanged();
                    refreshRuntimeState();
                }
                break;
            case 4:
                ConfigManager.setLunarTweaksAllowMultipleSameTypeCosmetics(
                        !ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics());
                refreshRuntimeState();
                break;
            case 5:
                if (GomodFixes.isWeaveRuntime()) {
                    mc.displayGuiScreen(new LunarCosmeticIgnoreGUI(this));
                }
                break;
            case 6:
                if (GomodFixes.isWeaveRuntime()) {
                    ConfigManager.setLunarLimitedCosmeticsUnlockerEnabled(
                            !ConfigManager.isLunarLimitedCosmeticsUnlockerEnabled());
                    refreshRuntimeState();
                }
                break;
            case 7:
                if (GomodFixes.isWeaveRuntime()) {
                    ConfigManager.setLunarCosmeticSourceBadgesEnabled(
                            !ConfigManager.isLunarCosmeticSourceBadgesEnabled());
                    refreshRuntimeState();
                }
                break;
            case 8:
                if (GomodFixes.isWeaveRuntime()) {
                    ConfigManager.setLunarClothCloakEnabled(
                            !ConfigManager.isLunarClothCloakEnabled());
                    me.napim.gomod.features.GomodLunarPlusLocker.onClothCloakPreferenceChanged();
                    refreshRuntimeState();
                }
                break;
            default:
                break;
        }
    }

    private String getLunarTweaksEnabledText() {
        return onOffText(ConfigManager.isLunarTweaksEnabled());
    }

    private String getLunarFullAccessAudienceText() {
        return ConfigManager.isLunarTweaksShowToOtherPlayers()
                ? EnumChatFormatting.GREEN + "gomoders"
                : EnumChatFormatting.YELLOW + "Only me";
    }

    private String getSeePeerLunarFullAccessText() {
        return onOffText(ConfigManager.isLunarTweaksSeeGomodPlayers());
    }

    private String getClothCloakAccessText() {
        return onOffText(ConfigManager.isLunarClothCloakEnabled());
    }

    private String getMultipleSameTypeCosmeticsText() {
        return onOffText(ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics());
    }

    private String getHiddenCosmeticPlayersText() {
        return EnumChatFormatting.AQUA + "Manage";
    }

    private String getLimitedCosmeticsUnlockerText() {
        return onOffText(ConfigManager.isLunarLimitedCosmeticsUnlockerEnabled());
    }

    private String getCosmeticSourceBadgesText() {
        return onOffText(ConfigManager.isLunarCosmeticSourceBadgesEnabled());
    }

    private String getLunarTweaksEnabledLabel() {
        return "Lunar FullAccess";
    }

    private String getLunarFullAccessAudienceLabel() {
        return "Show your FullAccess features with";
    }

    private String getSeePeerLunarFullAccessLabel() {
        return "See gomoders' features";
    }

    private String getClothCloakAccessLabel() {
        return "Cloth cloak";
    }

    private String getMultipleSameTypeCosmeticsLabel() {
        return "Keep conflicting Lunar cosmetics";
    }

    private String getLimitedCosmeticsUnlockerLabel() {
        return "Limited/expired cosmetics";
    }

    private String getCosmeticSourceBadgesLabel() {
        return "Cosmetic source icons";
    }

    private String getHiddenCosmeticPlayersLabel() {
        return "Hide players' Lunar cosmetics (" + ConfigManager.getLunarTweaksHiddenCosmeticPlayers().size() + ")";
    }

    private String onOffText(boolean on) {
        return on ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
    }

    private GuiButton[] settingButtons() {
        return new GuiButton[]{lunarTweaksEnabledButton, lunarFullAccessAudienceButton,
                seePeerLunarFullAccessButton, limitedCosmeticsUnlockerButton, cosmeticSourceBadgesButton,
                clothCloakAccessButton, multipleSameTypeCosmeticsButton, hiddenCosmeticPlayersButton};
    }

    private String[] settingLabels() {
        return new String[]{getLunarTweaksEnabledLabel(), getLunarFullAccessAudienceLabel(),
                getSeePeerLunarFullAccessLabel(), getLimitedCosmeticsUnlockerLabel(),
                getCosmeticSourceBadgesLabel(), getClothCloakAccessLabel(),
                getMultipleSameTypeCosmeticsLabel(), getHiddenCosmeticPlayersLabel()};
    }

    private void applyLayout(int scrollOffset, int visibleTop, int visibleBottom) {
        GuiButton[] rows = settingButtons();
        for (int i = 0; i < rows.length; i++) {
            GuiButton button = rows[i];
            button.xPosition = controlX;
            button.yPosition = baseRowY + i * ROW - scrollOffset;
            button.width = CONTROL_WIDTH;
            button.height = BUTTON_HEIGHT;
            button.visible = GuiScrollFade.intersectsVisible(button.yPosition, BUTTON_HEIGHT,
                    visibleTop, visibleBottom);
        }
        backButton.visible = true;
    }

    private void drawSettingLabels(int visibleTop, int visibleBottom) {
        GuiButton[] rows = settingButtons();
        String[] labels = settingLabels();
        for (int i = 0; i < rows.length; i++) {
            GuiScrollFade.drawLabel(fontRendererObj, labels[i], labelX, rows[i].yPosition, 6,
                    BUTTON_HEIGHT, visibleTop, visibleBottom, scroll.getOffset(), scroll.getMaxScroll());
        }
    }

    private void drawOnlineStatus(String label, boolean connected, int y) {
        String state = connected
                ? EnumChatFormatting.GREEN + "Connected"
                : EnumChatFormatting.RED + "Disconnected";
        GomodSocialPresenceIconTextures.drawCenteredConnectionStatus(fontRendererObj,
                EnumChatFormatting.GRAY + label + ": " + state, width, y, connected);
    }

    private void drawLunarOnlineStatus(LunarOnlineStatus.ConnectionState connectionState, int y) {
        String state = connectionState == LunarOnlineStatus.ConnectionState.CONNECTED
                ? EnumChatFormatting.GREEN + "Connected"
                : connectionState == LunarOnlineStatus.ConnectionState.CONNECTING
                ? EnumChatFormatting.GOLD + "Connecting..."
                : EnumChatFormatting.RED + "Disconnected";
        String line = EnumChatFormatting.GRAY + "Lunar Online: " + state;
        if (connectionState == LunarOnlineStatus.ConnectionState.CONNECTED
                || connectionState == LunarOnlineStatus.ConnectionState.DISCONNECTED) {
            GomodSocialPresenceIconTextures.drawCenteredConnectionStatus(fontRendererObj,
                    line, width, y,
                    connectionState == LunarOnlineStatus.ConnectionState.CONNECTED);
        } else {
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, line, width, y, 0xCCCCCC);
        }
    }

    private void drawFullAccessStatus(boolean enabled, LunarOnlineStatus.ConnectionState connectionState, int y) {
        String state;
        Boolean connected = null;
        if (!enabled) {
            state = EnumChatFormatting.GRAY + "Disabled";
        } else if (connectionState == LunarOnlineStatus.ConnectionState.DISCONNECTED) {
            state = EnumChatFormatting.RED + "Disconnected";
            connected = Boolean.FALSE;
        } else if (connectionState == LunarOnlineStatus.ConnectionState.CONNECTING) {
            state = EnumChatFormatting.GOLD + LunarOnlineStatus.getFullAccessProgressLabel();
        } else {
            state = EnumChatFormatting.GREEN + "Connected";
            connected = Boolean.TRUE;
        }
        String line = EnumChatFormatting.GRAY + "Lunar FullAccess: " + state;
        if (connected != null) {
            GomodSocialPresenceIconTextures.drawCenteredConnectionStatus(fontRendererObj,
                    line, width, y, connected.booleanValue());
        } else {
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, line, width, y, 0xCCCCCC);
        }
    }

    private void drawHoverTooltip(int mouseX, int mouseY,
                                  LunarOnlineStatus.ConnectionState lunarState, int lunarStatusY,
                                  LunarOnlineStatus.ConnectionState fullAccessState, int fullAccessStatusY) {
        if (lunarState == LunarOnlineStatus.ConnectionState.CONNECTING
                && isMouseOverStatusLine("Lunar Online: Connecting...", mouseX, mouseY, lunarStatusY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Lunar Online is connecting",
                    EnumChatFormatting.GRAY + LunarOnlineStatus.getLunarOnlineStatusMessage()),
                    mouseX, Math.max(0, mouseY - 12));
        } else if (GomodFixes.isWeaveRuntime() && ConfigManager.isLunarTweaksEnabled()
                && fullAccessState == LunarOnlineStatus.ConnectionState.CONNECTING
                && isMouseOverStatusLine("Lunar FullAccess: " + LunarOnlineStatus.getFullAccessProgressLabel(),
                        mouseX, mouseY, fullAccessStatusY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Lunar FullAccess connection progress",
                    EnumChatFormatting.GRAY + LunarOnlineStatus.getFullAccessStatusMessage()),
                    mouseX, Math.max(0, mouseY - 12));
        } else if (isMouseOverSettingRow(lunarTweaksEnabledButton, mouseX, mouseY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Lunar FullAccess",
                    EnumChatFormatting.GRAY + "When this is disabled, you won't see other gomoders'",
                    EnumChatFormatting.GRAY + "Lunar FullAccess features."),
                    mouseX, Math.max(0, mouseY - 12));
        } else if (isMouseOverSettingRow(lunarFullAccessAudienceButton, mouseX, mouseY)) {
            drawFullAccessSettingTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Who can see your Lunar FullAccess features?",
                    EnumChatFormatting.GREEN + "gomoders - other gomoders see",
                    EnumChatFormatting.GREEN + "your FullAccess features.",
                    EnumChatFormatting.YELLOW + "Only me - Only you will see your",
                    EnumChatFormatting.YELLOW + "FullAccess features (cosmetics, emotes,",
                    EnumChatFormatting.YELLOW + "Lunar+, Lunar Logo Color, Sprays, Badges).");
        } else if (isMouseOverSettingRow(seePeerLunarFullAccessButton, mouseX, mouseY)) {
            drawFullAccessSettingTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "See gomoders' FullAccess features",
                    EnumChatFormatting.GRAY + "Shows other gomoders' FullAccess cosmetics, emotes,",
                    EnumChatFormatting.GRAY + "Lunar+, Lunar Logo Color, Sprays and Badges.");
        } else if (isMouseOverSettingRow(clothCloakAccessButton, mouseX, mouseY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Cloth cloak",
                    EnumChatFormatting.GRAY + "Additional cape physics for Lunar Cloaks"),
                    mouseX, Math.max(0, mouseY - 12));
        } else if (isMouseOverSettingRow(multipleSameTypeCosmeticsButton, mouseX, mouseY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Keep multiple or incompatible Lunar cosmetics equipped",
                    EnumChatFormatting.GRAY + "Disables Lunar's anti-stacking and incompatibility restrictions.",
                    EnumChatFormatting.GRAY + "Other players using the official Lunar Client will also see your stacked/incompatible cosmetics.",
                    EnumChatFormatting.GRAY + "" + EnumChatFormatting.ITALIC
                            + "This setting does not require Lunar FullAccess enabled."),
                    mouseX, Math.max(0, mouseY - 12));
        } else if (isMouseOverSettingRow(hiddenCosmeticPlayersButton, mouseX, mouseY)) {
            drawHoveringText(java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Hide selected players' Lunar cosmetics",
                    EnumChatFormatting.GRAY + "Their Lunar cosmetics and companions are hidden only for you.",
                    EnumChatFormatting.GRAY + "Their custom Lunar nametag/logo, emotes, sprays, and badges stay visible.",
                    EnumChatFormatting.GRAY + "This works independently of your FullAccess toggle."),
                    mouseX, Math.max(0, mouseY - 12));
        } else if (isMouseOverSettingRow(limitedCosmeticsUnlockerButton, mouseX, mouseY)) {
            drawFullAccessSettingTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Limited/expired cosmetics",
                    EnumChatFormatting.GRAY + "Unlocks Limited/expired Lunar cosmetics in your Locker.",
                    EnumChatFormatting.GRAY + "Equipped cosmetics are shared with gomoders.");
        } else if (isMouseOverSettingRow(cosmeticSourceBadgesButton, mouseX, mouseY)) {
            drawFullAccessSettingTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Cosmetic source icons",
                    EnumChatFormatting.GRAY + "Shows an icon on the Locker preview of cosmetics you own or have edited.");
        }
    }

    private void drawFullAccessSettingTooltip(int mouseX, int mouseY, String... description) {
        java.util.List<String> lines = new java.util.ArrayList<String>(java.util.Arrays.asList(description));
        if (!hasConnectedFullAccess()) {
            lines.add("");
            lines.add(EnumChatFormatting.GRAY + "" + EnumChatFormatting.ITALIC
                    + (ConfigManager.isLunarTweaksEnabled()
                    ? "Lunar FullAccess must be connected." : "Lunar FullAccess must be enabled."));
        }
        drawHoveringText(lines, mouseX, Math.max(0, mouseY - 12));
    }

    private boolean isMouseOverStatusLine(String text, int mouseX, int mouseY, int y) {
        int halfWidth = fontRendererObj.getStringWidth(text) / 2;
        return mouseY >= y - 5 && mouseY <= y + 7
                && mouseX >= width / 2 - halfWidth && mouseX <= width / 2 + halfWidth;
    }

    private boolean isMouseOverSettingRow(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= labelX && mouseX < controlX + CONTROL_WIDTH
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (scroll.mouseClicked(mouseX, mouseY, mouseButton)) return;
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
