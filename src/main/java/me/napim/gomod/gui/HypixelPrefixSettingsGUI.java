package me.napim.gomod.gui;



import me.napim.gomod.features.ConfigManager;

import me.napim.gomod.features.LocalDisguise;

import me.napim.gomod.features.MixinStatus;

import net.minecraft.client.gui.GuiButton;

import net.minecraft.client.gui.GuiScreen;

import net.minecraft.client.gui.GuiTextField;

import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;

import org.lwjgl.input.Mouse;



import java.io.IOException;



/**

 * Hypixel TAB/chat rank while disguised: prefix mode buttons (Target's / Hidden / Hypixel prefixes / Custom rank),

 * and when prefixes mode is on, a scrollable list of all ranks including every MVP+ and MVP++ color variant.

 */

public class HypixelPrefixSettingsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {



    static final String[] MVP_PLUS_COLORS = HypixelPrefixOfficialCatalog.MVP_PLUS_COLORS;

    static final String[] MVP_PLUS_PLUS_COLORS = HypixelPrefixOfficialCatalog.MVP_PLUS_PLUS_COLORS;



    private final GuiScreen parent;

    private GuiButton btnModeHide;

    private GuiButton btnModeShow;

    private GuiButton btnModeOfficial;

    private GuiButton btnModeCustom;

    private GuiButton btnOfficialRanks;

    private GuiButton btnMvpPlusColor;

    private GuiButton btnMvpPpStyle;

    private GuiButton btnBack;

    private GuiTextField customField;



    private final HypixelPrefixOfficialCatalog.Panel officialCatalog = new HypixelPrefixOfficialCatalog.Panel();



    public HypixelPrefixSettingsGUI(GuiScreen parent) {

        this.parent = parent;

    }



    private static final int COL_W = 200;
    private static final int CATALOG_W = 292;
    private static final int CATALOG_GAP = 14;
    private static final int PREFIX_BTN_H = 20;
    private static final int PREFIX_BTN_STEP = 24;

    private static final int BTN_MODE_HIDE = 11;

    private static final int BTN_MODE_SHOW = 12;

    private static final int BTN_MODE_OFFICIAL = 13;

    private static final int BTN_MODE_CUSTOM = 14;

    private int leftX;
    private int contentY0 = 48;



    private static boolean targetApiUnavailableForCurrentDisguise() {

        return LocalDisguise.isNickDisguiseActive();

    }



    /** Applies a prefix mode from any disguise / nick prefix UI (no API fetch for Target's). */

    public static void applySelectedPrefixMode(String mode) {

        if (mode == null || mode.trim().isEmpty()) {

            return;

        }

        String next = mode.trim().toUpperCase(java.util.Locale.ROOT);

        if ("SHOW".equals(next) && targetApiUnavailableForCurrentDisguise()) {

            return;

        }

        ConfigManager.setDisguiseHypixelPrefixMode(next);

        ensurePrefixModePresetConsistency();

        if ("SHOW".equals(next) && LocalDisguise.isEnabled() && !targetApiUnavailableForCurrentDisguise()) {

            LocalDisguise.applyStoredTargetApiRankForPrefixMode();

        }

        LocalDisguise.refreshHypixelRankPresetFromConfig();

    }



    public static String prefixModeButtonLabel(String modeKey, String selectedMode) {

        boolean on = modeKey.equalsIgnoreCase(selectedMode);

        EnumChatFormatting h = on ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY;

        String arrow = on ? (EnumChatFormatting.YELLOW + "> " + h) : (EnumChatFormatting.DARK_GRAY + "  " + h);

        if ("HIDE".equals(modeKey)) {

            return arrow + "Hidden";

        }

        if ("SHOW".equals(modeKey)) {

            return arrow + "Target's (API)";

        }

        if ("HYPIXEL_OFFICIAL".equals(modeKey)) {

            return arrow + "Hypixel prefixes";

        }

        if ("CUSTOM".equals(modeKey)) {

            return arrow + "Custom rank";

        }

        return "";

    }



    private void refreshModeButtonLabels() {

        String m = ConfigManager.getDisguiseHypixelPrefixMode();

        if (this.btnModeHide != null) {

            this.btnModeHide.displayString = prefixModeButtonLabel("HIDE", m);

        }

        if (this.btnModeShow != null) {

            this.btnModeShow.displayString = prefixModeButtonLabel("SHOW", m);

            this.btnModeShow.visible = !targetApiUnavailableForCurrentDisguise();

            this.btnModeShow.enabled = !targetApiUnavailableForCurrentDisguise();

        }

        if (this.btnModeOfficial != null) {

            this.btnModeOfficial.displayString = prefixModeButtonLabel("HYPIXEL_OFFICIAL", m);

        }

        if (this.btnModeCustom != null) {

            this.btnModeCustom.displayString = prefixModeButtonLabel("CUSTOM", m);

        }

    }



    private void selectPrefixMode(String mode) {

        String previous = ConfigManager.getDisguiseHypixelPrefixMode();

        applySelectedPrefixMode(mode);

        if ("HYPIXEL_OFFICIAL".equalsIgnoreCase(mode) && !"HYPIXEL_OFFICIAL".equalsIgnoreCase(previous)) {

            this.officialCatalog.resetScroll();

        }

        refreshAuxVisibility();

        relayoutWidgets();

    }



    /** Called when returning from {@link HypixelOfficialRankPickerGUI} so labels stay in sync. */

    void syncAfterOfficialPick() {

        refreshAuxVisibility();

    }



    @Override

    public void initGui() {

        Keyboard.enableRepeatEvents(true);

        this.buttonList.clear();

        HypixelPrefixOfficialCatalog.invalidateCache();

        ensurePrefixModePresetConsistency();

        this.btnModeHide = new GuiButton(BTN_MODE_HIDE, 0, 0, COL_W, PREFIX_BTN_H, "");

        this.btnModeShow = new GuiButton(BTN_MODE_SHOW, 0, 0, COL_W, PREFIX_BTN_H, "");

        this.btnModeOfficial = new GuiButton(BTN_MODE_OFFICIAL, 0, 0, COL_W, PREFIX_BTN_H, "");

        this.btnModeCustom = new GuiButton(BTN_MODE_CUSTOM, 0, 0, COL_W, PREFIX_BTN_H, "");

        this.btnOfficialRanks = new GuiButton(2, 0, 0, COL_W, 20,

                EnumChatFormatting.GOLD + "Choose Hypixel rank…");

        this.btnMvpPlusColor = new GuiButton(5, 0, 0, COL_W, 20, mvpPlusPickLabel());

        this.btnMvpPpStyle = new GuiButton(6, 0, 0, COL_W, 20, mvpPpStylePickLabel());

        layoutBottomBar();

        this.customField = new GuiTextField(10, this.fontRendererObj, 0, 0, COL_W, 20);

        this.customField.setMaxStringLength(120);

        this.customField.setText(ConfigManager.getDisguiseHypixelRankCustom());

        this.buttonList.add(this.btnModeHide);

        this.buttonList.add(this.btnModeShow);

        this.buttonList.add(this.btnModeOfficial);

        this.buttonList.add(this.btnModeCustom);

        this.buttonList.add(this.btnOfficialRanks);

        this.buttonList.add(this.btnMvpPlusColor);

        this.buttonList.add(this.btnMvpPpStyle);

        this.buttonList.add(this.btnBack);

        refreshAuxVisibility();

        relayoutWidgets();

    }



    private void relayoutWidgets() {

        String mode = ConfigManager.getDisguiseHypixelPrefixMode();

        boolean official = "HYPIXEL_OFFICIAL".equalsIgnoreCase(mode);

        int btnW = COL_W;

        if (official) {

            this.leftX = this.width / 2 - (btnW + CATALOG_GAP + CATALOG_W) / 2;

        } else {

            this.leftX = this.width / 2 - btnW / 2;

        }

        int y = this.contentY0;

        if (this.btnModeHide != null) {

            this.btnModeHide.xPosition = this.leftX;

            this.btnModeHide.yPosition = y;

            this.btnModeHide.width = btnW;

            this.btnModeHide.height = PREFIX_BTN_H;

        }

        if (this.btnModeShow != null) {

            this.btnModeShow.xPosition = this.leftX;

            this.btnModeShow.yPosition = y + PREFIX_BTN_STEP;

            this.btnModeShow.width = btnW;

            this.btnModeShow.height = PREFIX_BTN_H;

        }

        if (this.btnModeOfficial != null) {

            this.btnModeOfficial.xPosition = this.leftX;

            this.btnModeOfficial.yPosition = y + PREFIX_BTN_STEP * 2;

            this.btnModeOfficial.width = btnW;

            this.btnModeOfficial.height = PREFIX_BTN_H;

        }

        if (this.btnModeCustom != null) {

            this.btnModeCustom.xPosition = this.leftX;

            this.btnModeCustom.yPosition = y + PREFIX_BTN_STEP * 3;

            this.btnModeCustom.width = btnW;

            this.btnModeCustom.height = PREFIX_BTN_H;

        }

        if (this.customField != null) {

            this.customField.xPosition = this.leftX;

            this.customField.yPosition = y + PREFIX_BTN_STEP * 4 + 16;

            this.customField.width = btnW;

        }

    }



    private void layoutBottomBar() {

        int backY = GuiHeader.backButtonY(this.height, 20);

        if (this.btnBack == null) {

            this.btnBack = new GuiButton(0, this.width / 2 - 50, backY, 100, 20, EnumChatFormatting.GRAY + "Done");

        } else {

            this.btnBack.xPosition = this.width / 2 - 50;

            this.btnBack.yPosition = backY;

        }

    }



    private static void ensurePrefixModePresetConsistency() {

        String mode = ConfigManager.getDisguiseHypixelPrefixMode();

        if (targetApiUnavailableForCurrentDisguise() && "SHOW".equalsIgnoreCase(mode)) {

            ConfigManager.setDisguiseHypixelPrefixMode("HIDE");

            mode = "HIDE";

        }

        if ("SHOW".equalsIgnoreCase(mode)) {

            if (!"TARGET".equals(ConfigManager.getDisguiseHypixelRankPreset())) {

                ConfigManager.setDisguiseHypixelRankPreset("TARGET");

            }

        } else if ("HYPIXEL_OFFICIAL".equalsIgnoreCase(mode)) {

            String p = ConfigManager.getDisguiseHypixelRankPreset();

            if ("TARGET".equals(p) || "CUSTOM".equals(p)) {

                ConfigManager.setDisguiseHypixelRankPreset("NONE");

            }

        } else if ("CUSTOM".equalsIgnoreCase(mode)) {

            ConfigManager.setDisguiseHypixelRankPreset("CUSTOM");

        }

    }



    private String mvpPlusPickLabel() {

        return EnumChatFormatting.GOLD + "MVP+ plus color…";

    }



    private String mvpPpStylePickLabel() {

        return EnumChatFormatting.LIGHT_PURPLE + "MVP++ style…";

    }



    private void refreshAuxVisibility() {

        String mode = ConfigManager.getDisguiseHypixelPrefixMode();

        boolean custom = "CUSTOM".equalsIgnoreCase(mode);

        this.btnOfficialRanks.visible = false;

        this.btnOfficialRanks.enabled = false;

        this.btnMvpPlusColor.visible = false;

        this.btnMvpPlusColor.enabled = false;

        this.btnMvpPpStyle.visible = false;

        this.btnMvpPpStyle.enabled = false;

        refreshModeButtonLabels();

        this.btnMvpPlusColor.displayString = mvpPlusPickLabel();

        this.btnMvpPpStyle.displayString = mvpPpStylePickLabel();

        if (this.customField != null) {

            this.customField.setFocused(custom);

        }

    }



    @Override

    public void onGuiClosed() {

        Keyboard.enableRepeatEvents(false);

        flushCustomToConfig();

    }



    private void flushCustomToConfig() {

        if (this.customField != null) {

            ConfigManager.setDisguiseHypixelRankCustom(this.customField.getText());

        }

    }



    @Override

    protected void actionPerformed(GuiButton button) throws IOException {

        if (button == null) {

            return;

        }

        switch (button.id) {

            case 0:

                flushCustomToConfig();

                LocalDisguise.refreshHypixelRankPresetFromConfig();

                this.mc.displayGuiScreen(this.parent);

                break;

            case BTN_MODE_HIDE:

                flushCustomToConfig();

                selectPrefixMode("HIDE");

                break;

            case BTN_MODE_SHOW:

                flushCustomToConfig();

                selectPrefixMode("SHOW");

                break;

            case BTN_MODE_OFFICIAL:

                flushCustomToConfig();

                selectPrefixMode("HYPIXEL_OFFICIAL");

                break;

            case BTN_MODE_CUSTOM:

                flushCustomToConfig();

                selectPrefixMode("CUSTOM");

                break;

            case 2:

            case 5:

            case 6:

                break;

            default:

                break;

        }

    }



    @Override

    public void updateScreen() {

        super.updateScreen();

        if (this.customField != null && "CUSTOM".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())) {

            this.customField.updateCursorCounter();

        }

    }



    private void layoutOfficialPanel() {

        relayoutWidgets();

        layoutBottomBar();

        int catalogLeft = this.leftX + COL_W + CATALOG_GAP;

        int catalogTop = GuiHeader.STANDARD_SUBTITLE_Y + 2;

        int backTop = GuiHeader.backButtonY(this.height, 20);

        int catalogBottom = backTop - 14;

        this.officialCatalog.layout(catalogLeft, catalogTop, CATALOG_W, catalogBottom);

    }



    private void drawOfficialCatalog(int mouseX, int mouseY) {

        layoutOfficialPanel();

        int catalogLeft = this.leftX + COL_W + CATALOG_GAP;

        String display = LocalDisguise.getHypixelPrefixPreviewDisplayName();

        String full = LocalDisguise.previewNickDisguiseNameLine(null);

        this.fontRendererObj.drawStringWithShadow(

                EnumChatFormatting.DARK_GRAY + "Preview: " + EnumChatFormatting.RESET + full,

                catalogLeft, GuiHeader.STANDARD_SUBTITLE_Y - 2, 0x888888);

        this.officialCatalog.draw(this.fontRendererObj, mouseX, mouseY, display);

    }



    @Override

    public void handleMouseInput() throws IOException {

        super.handleMouseInput();

        if (!"HYPIXEL_OFFICIAL".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())) {

            return;

        }

        int wheel = Mouse.getEventDWheel();

        if (wheel != 0) {

            layoutOfficialPanel();

            this.officialCatalog.handleWheel(wheel);

        }

    }



    @Override

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {

        MixinStatus.skipFontRendererDisguiseReplace = true;

        try {

            this.drawDefaultBackground();

            relayoutWidgets();

            int cx = this.width / 2;

            GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,

                    EnumChatFormatting.LIGHT_PURPLE.toString() + EnumChatFormatting.BOLD + "Hypixel prefix",

                    cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

            this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.WHITE + "Prefix mode",
                    this.leftX, this.contentY0 - 10, 0xFFFFFF);

            if (targetApiUnavailableForCurrentDisguise()) {

                GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,

                        EnumChatFormatting.YELLOW + "Target's (API) is not available for nick disguise.",

                        cx, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

            }

            String mode = ConfigManager.getDisguiseHypixelPrefixMode();

            if ("HYPIXEL_OFFICIAL".equalsIgnoreCase(mode)) {

                drawOfficialCatalog(mouseX, mouseY);

            } else {

                if ("SHOW".equalsIgnoreCase(mode)) {

                    this.fontRendererObj.drawStringWithShadow(

                            EnumChatFormatting.DARK_GRAY + "Uses the disguised player's Hypixel API rank (cached at disguise).",

                            this.leftX, this.contentY0 + PREFIX_BTN_STEP * 4 + 8, 0x888888);

                } else if ("CUSTOM".equalsIgnoreCase(mode)) {

                    this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD + "Custom rank (&c, &l, …)",

                            this.leftX, this.contentY0 + PREFIX_BTN_STEP * 4 + 4, 0xFFFFFF);

                    String tagRaw = this.customField != null ? this.customField.getText() : "";

                    String tag = LocalDisguise.translateDisguiseAmpersandColors(tagRaw);

                    String display = LocalDisguise.getHypixelPrefixPreviewDisplayName();

                    String previewLine = (tag == null || tag.isEmpty())

                            ? EnumChatFormatting.WHITE + display

                            : tag + EnumChatFormatting.RESET + " " + EnumChatFormatting.WHITE + display;

                    this.fontRendererObj.drawStringWithShadow(

                            EnumChatFormatting.DARK_GRAY + "Preview: " + EnumChatFormatting.RESET + previewLine,

                            this.leftX, this.contentY0 + PREFIX_BTN_STEP * 4 + 30, 0xFFFFFF);

                    this.customField.drawTextBox();

                }

            }

            refreshAuxVisibility();

            layoutBottomBar();

            super.drawScreen(mouseX, mouseY, partialTicks);

        } finally {

            MixinStatus.skipFontRendererDisguiseReplace = false;

        }

    }



    @Override

    protected void keyTyped(char typedChar, int keyCode) throws IOException {

        if (this.customField != null && "CUSTOM".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())

                && this.customField.textboxKeyTyped(typedChar, keyCode)) {

            return;

        }

        if (keyCode == 1) {

            flushCustomToConfig();

            LocalDisguise.refreshHypixelRankPresetFromConfig();

            this.mc.displayGuiScreen(this.parent);

            return;

        }

        super.keyTyped(typedChar, keyCode);

    }



    @Override

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {

        if (mouseButton == 0 && "HYPIXEL_OFFICIAL".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())) {

            layoutOfficialPanel();

            if (this.officialCatalog.handleClick(mouseX, mouseY)) {

                return;

            }

        }

        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (this.customField != null && "CUSTOM".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())) {

            this.customField.mouseClicked(mouseX, mouseY, mouseButton);

        }

    }



    @Override

    public boolean shouldBlockGomodMenuMovementAndDragLook() {

        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(customField);

    }



    @Override

    public boolean doesGuiPauseGame() {

        return false;

    }

}


