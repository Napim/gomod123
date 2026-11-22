package me.napim.gomod.gui;

import me.napim.gomod.command.DisguiseCommand;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.MixinStatus;
import me.napim.gomod.util.GomodFileDialogs;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;

/**
 * Fake-username disguise: offline UUID, skin from Steve / real player / PNG in {@code gomod123/skins}.
 */
public class NickDisguiseSetupGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int BTN_SKIN_STEVE = 20;
    private static final int BTN_SKIN_PLAYER = 21;
    private static final int BTN_SKIN_FILE = 22;
    private static final int BTN_PREFIX_HIDE = 30;
    private static final int BTN_PREFIX_OFFICIAL = 31;
    private static final int BTN_PREFIX_CUSTOM = 32;

    private static final int COL_W = 150;
    private static final int CATALOG_W = 292;
    private static final int CATALOG_GAP = 14;
    private static final int PREFIX_BTN_H = 20;
    private static final int PREFIX_BTN_STEP = 24;

    private final GuiScreen parent;
    private GuiTextField nickField;
    private GuiTextField skinPlayerField;
    private GuiTextField prefixCustomField;
    private GuiButton skinSteveButton;
    private GuiButton skinPlayerButton;
    private GuiButton skinFileButton;
    private GuiButton browseFileButton;
    private GuiButton prefixHideButton;
    private GuiButton prefixOfficialButton;
    private GuiButton prefixCustomButton;
    private GuiButton applyButton;
    private GuiButton cancelButton;
    private final HypixelPrefixOfficialCatalog.Panel officialCatalog = new HypixelPrefixOfficialCatalog.Panel();
    private LocalDisguise.NickSkinMode skinMode = LocalDisguise.NickSkinMode.STEVE;
    private volatile String pendingPickedFile;

    /** Layout anchors (updated by {@link #relayoutWidgets()}). */
    private int leftX;
    private int y0 = 48;
    private int colW = COL_W;
    private int usernameLabelY;
    private int skinSourceLabelY;
    private int nickFieldY;
    private int skinSteveY;
    private int skinPlayerSkinY;
    private int skinFileY;
    private int skinPlayerLabelY;
    private int skinPlayerFieldY;
    private int browseY;
    private int prefixLabelY;
    private int prefixHideY;
    private int prefixOfficialY;
    private int prefixCustomY;
    private int prefixExtraY;
    private int applyY;

    public NickDisguiseSetupGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();

        this.nickField = new GuiTextField(0, this.fontRendererObj, 0, 0, this.colW, 20);
        this.nickField.setMaxStringLength(64);
        this.skinPlayerField = new GuiTextField(1, this.fontRendererObj, this.leftX, 0, this.colW, 20);
        this.skinPlayerField.setMaxStringLength(16);
        this.prefixCustomField = new GuiTextField(2, this.fontRendererObj, this.leftX, 0, this.colW, 20);
        this.prefixCustomField.setMaxStringLength(120);
        this.prefixCustomField.setText(ConfigManager.getDisguiseHypixelRankCustom());

        loadDraftFromConfig();

        this.nickField.setFocused(true);

        this.skinSteveButton = new GuiButton(BTN_SKIN_STEVE, this.leftX, 0, this.colW, 20, "");
        this.skinPlayerButton = new GuiButton(BTN_SKIN_PLAYER, this.leftX, 0, this.colW, 20, "");
        this.skinFileButton = new GuiButton(BTN_SKIN_FILE, this.leftX, 0, this.colW, 20, "");
        this.browseFileButton = new GuiButton(4, this.leftX, 0, this.colW, 20, EnumChatFormatting.AQUA + "Browse for PNG...");
        this.prefixHideButton = new GuiButton(BTN_PREFIX_HIDE, this.leftX, 0, this.colW, PREFIX_BTN_H, "");
        this.prefixOfficialButton = new GuiButton(BTN_PREFIX_OFFICIAL, this.leftX, 0, this.colW, PREFIX_BTN_H, "");
        this.prefixCustomButton = new GuiButton(BTN_PREFIX_CUSTOM, 0, 0, this.colW, PREFIX_BTN_H, "");
        this.applyButton = new GuiButton(5, 0, 0, 73, 20, "Apply");
        this.cancelButton = new GuiButton(6, 0, 0, 73, 20, "Cancel");

        this.buttonList.add(this.skinSteveButton);
        this.buttonList.add(this.skinPlayerButton);
        this.buttonList.add(this.skinFileButton);
        this.buttonList.add(this.browseFileButton);
        this.buttonList.add(this.prefixHideButton);
        this.buttonList.add(this.prefixOfficialButton);
        this.buttonList.add(this.prefixCustomButton);
        this.buttonList.add(this.applyButton);
        this.buttonList.add(this.cancelButton);
        relayoutWidgets();
        updateChildButtonStates();
        refreshSkinButtonsHighlight();
    }

    private void loadDraftFromConfig() {
        try {
            this.skinMode = LocalDisguise.NickSkinMode.valueOf(ConfigManager.getDisguiseNickSetupLastSkinMode());
        } catch (Throwable ignored) {
            this.skinMode = LocalDisguise.NickSkinMode.STEVE;
        }
        String nick = ConfigManager.getDisguiseNickSetupLastNick();
        String ref = ConfigManager.getDisguiseNickSetupLastSkinRef();
        this.nickField.setText(nick == null ? "" : nick);
        if (this.skinMode == LocalDisguise.NickSkinMode.PLAYER_SKIN) {
            this.skinPlayerField.setText(ref == null ? "" : ref);
        } else {
            this.skinPlayerField.setText("");
        }
        this.pendingPickedFile = null;
        if (this.skinMode == LocalDisguise.NickSkinMode.SKIN_FILE && ref != null && !ref.isEmpty()) {
            File sf = new File(ref);
            if (sf.isFile()) {
                this.pendingPickedFile = sf.getAbsolutePath();
            }
        }
    }

    private void relayoutWidgets() {
        String prefixMode = ConfigManager.getDisguiseHypixelPrefixMode();
        boolean official = "HYPIXEL_OFFICIAL".equalsIgnoreCase(prefixMode);
        if (official) {
            this.leftX = this.width / 2 - (this.colW + CATALOG_GAP + CATALOG_W) / 2;
        } else {
            this.leftX = this.width / 2 - this.colW / 2;
        }

        int y = this.y0;
        int previewStatusBottom = GuiHeader.STANDARD_SUBTITLE_Y + 6 + 18 + 8 + 9;
        this.usernameLabelY = Math.max(y + 28, previewStatusBottom + 10);
        this.nickFieldY = this.usernameLabelY + 24;
        int skinSourceLabelBottom = this.nickFieldY + 20 + 4;
        this.skinSourceLabelY = skinSourceLabelBottom;
        this.skinSteveY = skinSourceLabelBottom + 10;
        this.skinPlayerSkinY = this.skinSteveY + 24;
        this.skinFileY = this.skinPlayerSkinY + 24;
        int stack = this.skinFileY + 24;
        this.skinPlayerLabelY = 0;
        this.skinPlayerFieldY = 0;
        if (skinModeNeedsPlayerName()) {
            this.skinPlayerLabelY = stack;
            this.skinPlayerFieldY = stack + 12;
            stack = this.skinPlayerFieldY + 24;
        }
        this.browseY = 0;
        if (skinMode == LocalDisguise.NickSkinMode.SKIN_FILE) {
            this.browseY = stack;
            stack = this.browseY + 24;
        }
        this.prefixLabelY = stack + 8;
        this.prefixHideY = this.prefixLabelY + 12;
        this.prefixOfficialY = this.prefixHideY + PREFIX_BTN_STEP;
        this.prefixCustomY = this.prefixOfficialY + PREFIX_BTN_STEP;
        if ("CUSTOM".equalsIgnoreCase(prefixMode)) {
            this.prefixExtraY = this.prefixCustomY + 24;
        } else {
            this.prefixExtraY = 0;
        }
        this.applyY = (this.prefixExtraY > 0 ? this.prefixExtraY + 28 : this.prefixCustomY + 28);

        this.nickField.yPosition = this.nickFieldY;
        this.nickField.xPosition = this.leftX;
        this.skinPlayerField.yPosition = this.skinPlayerFieldY;
        this.skinPlayerField.xPosition = this.leftX;
        this.skinSteveButton.yPosition = this.skinSteveY;
        this.skinSteveButton.xPosition = this.leftX;
        this.skinPlayerButton.yPosition = this.skinPlayerSkinY;
        this.skinPlayerButton.xPosition = this.leftX;
        this.skinFileButton.yPosition = this.skinFileY;
        this.skinFileButton.xPosition = this.leftX;
        this.browseFileButton.yPosition = this.browseY;
        this.browseFileButton.xPosition = this.leftX;
        this.prefixHideButton.yPosition = this.prefixHideY;
        this.prefixHideButton.xPosition = this.leftX;
        this.prefixOfficialButton.yPosition = this.prefixOfficialY;
        this.prefixOfficialButton.xPosition = this.leftX;
        this.prefixCustomButton.yPosition = this.prefixCustomY;
        this.prefixCustomButton.xPosition = this.leftX;
        this.prefixCustomField.yPosition = this.prefixExtraY;
        this.prefixCustomField.xPosition = this.leftX;
        this.applyButton.xPosition = this.leftX;
        this.applyButton.yPosition = this.applyY;
        this.cancelButton.xPosition = this.leftX + 77;
        this.cancelButton.yPosition = this.applyY;
        refreshPrefixButtons();
        updatePrefixExtraVisibility();
    }

    private void refreshPrefixButtons() {
        String mode = ConfigManager.getDisguiseHypixelPrefixMode();
        if (this.prefixHideButton != null) {
            this.prefixHideButton.displayString = HypixelPrefixSettingsGUI.prefixModeButtonLabel("HIDE", mode);
        }
        if (this.prefixOfficialButton != null) {
            this.prefixOfficialButton.displayString = HypixelPrefixSettingsGUI.prefixModeButtonLabel("HYPIXEL_OFFICIAL", mode);
        }
        if (this.prefixCustomButton != null) {
            this.prefixCustomButton.displayString = HypixelPrefixSettingsGUI.prefixModeButtonLabel("CUSTOM", mode);
        }
    }

    private void updatePrefixExtraVisibility() {
        String mode = ConfigManager.getDisguiseHypixelPrefixMode();
        boolean custom = "CUSTOM".equalsIgnoreCase(mode);
        if (this.prefixCustomField != null) {
            this.prefixCustomField.setFocused(custom);
        }
    }

    private void selectPrefixMode(String mode) {
        if (this.prefixCustomField != null) {
            ConfigManager.setDisguiseHypixelRankCustom(this.prefixCustomField.getText());
        }
        String previous = ConfigManager.getDisguiseHypixelPrefixMode();
        HypixelPrefixSettingsGUI.applySelectedPrefixMode(mode);
        if ("HYPIXEL_OFFICIAL".equalsIgnoreCase(mode) && !"HYPIXEL_OFFICIAL".equalsIgnoreCase(previous)) {
            this.officialCatalog.resetScroll();
        }
        relayoutWidgets();
    }

    private String skinBtnLabel(LocalDisguise.NickSkinMode mode) {
        boolean on = this.skinMode == mode;
        EnumChatFormatting h = on ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY;
        String arrow = on ? (EnumChatFormatting.YELLOW + "> " + h) : (EnumChatFormatting.DARK_GRAY + "  " + h);
        switch (mode) {
            case STEVE:
                return arrow + "Skin: Default (Steve)";
            case PLAYER_SKIN:
                return arrow + "Skin: Copy player";
            case SKIN_FILE:
                return arrow + "Skin: PNG file";
            default:
                return "";
        }
    }

    private void setSkinMode(LocalDisguise.NickSkinMode mode) {
        this.skinMode = mode;
        relayoutWidgets();
        refreshSkinButtonsHighlight();
        updateChildButtonStates();
    }

    private void refreshSkinButtonsHighlight() {
        if (this.skinSteveButton != null) {
            this.skinSteveButton.displayString = skinBtnLabel(LocalDisguise.NickSkinMode.STEVE);
        }
        if (this.skinPlayerButton != null) {
            this.skinPlayerButton.displayString = skinBtnLabel(LocalDisguise.NickSkinMode.PLAYER_SKIN);
        }
        if (this.skinFileButton != null) {
            this.skinFileButton.displayString = skinBtnLabel(LocalDisguise.NickSkinMode.SKIN_FILE);
        }
    }

    private boolean skinModeNeedsPlayerName() {
        return skinMode == LocalDisguise.NickSkinMode.PLAYER_SKIN;
    }

    private void updateChildButtonStates() {
        if (this.browseFileButton != null) {
            boolean file = skinMode == LocalDisguise.NickSkinMode.SKIN_FILE;
            this.browseFileButton.visible = file;
            this.browseFileButton.enabled = file;
            this.browseFileButton.displayString = EnumChatFormatting.AQUA + "Browse for PNG...";
        }
    }

    private void persistDraftToConfig() {
        String nick = this.nickField == null ? "" : this.nickField.getText().trim();
        String ref = "";
        switch (skinMode) {
            case STEVE:
                ref = "";
                break;
            case PLAYER_SKIN:
                ref = this.skinPlayerField == null ? "" : sanitize(this.skinPlayerField.getText());
                break;
            case SKIN_FILE:
                ref = this.pendingPickedFile != null ? this.pendingPickedFile : "";
                break;
            default:
                break;
        }
        ConfigManager.saveDisguiseNickSetupDraft(nick, skinMode.name(), ref);
        if (this.prefixCustomField != null) {
            ConfigManager.setDisguiseHypixelRankCustom(this.prefixCustomField.getText());
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        persistDraftToConfig();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        switch (button.id) {
            case BTN_SKIN_STEVE:
                setSkinMode(LocalDisguise.NickSkinMode.STEVE);
                break;
            case BTN_SKIN_PLAYER:
                setSkinMode(LocalDisguise.NickSkinMode.PLAYER_SKIN);
                break;
            case BTN_SKIN_FILE:
                setSkinMode(LocalDisguise.NickSkinMode.SKIN_FILE);
                break;
            case BTN_PREFIX_HIDE:
                selectPrefixMode("HIDE");
                break;
            case BTN_PREFIX_OFFICIAL:
                selectPrefixMode("HYPIXEL_OFFICIAL");
                break;
            case BTN_PREFIX_CUSTOM:
                selectPrefixMode("CUSTOM");
                break;
            case 4:
                openSkinFileDialogAsync();
                break;
            case 5:
                apply();
                break;
            case 6:
                goBack();
                break;
            default:
                break;
        }
    }

    private void openSkinFileDialogAsync() {
        if (this.mc == null) {
            return;
        }
        final File startDir = ConfigManager.getGomodSkinsDirectory();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File pickedFile = GomodFileDialogs.pickOpenPngFile("Choose skin PNG", startDir);
                    if (pickedFile == null) {
                        return;
                    }
                    final String path = pickedFile.getAbsolutePath();
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            NickDisguiseSetupGUI.this.pendingPickedFile = path;
                        }
                    });
                } catch (Throwable t) {
                    System.err.println("[NickDisguise] Skin file dialog failed: " + t.getMessage());
                }
            }
        }, "gomod-nick-skin-file").start();
    }

    private void apply() {
        String raw = this.nickField == null ? "" : this.nickField.getText().trim();
        String nick = LocalDisguise.stripToPlainNickForOfflineUuid(raw);
        if (nick.length() < 3) {
            return;
        }
        String ref;
        switch (skinMode) {
            case STEVE:
                ref = "";
                break;
            case PLAYER_SKIN:
                ref = this.skinPlayerField == null ? "" : sanitize(this.skinPlayerField.getText());
                if (ref.isEmpty()) {
                    return;
                }
                break;
            case SKIN_FILE:
                ref = this.pendingPickedFile != null ? this.pendingPickedFile : "";
                if (ref.isEmpty()) {
                    return;
                }
                break;
            default:
                ref = "";
        }
        ConfigManager.saveDisguiseNickSetupDraft(raw, skinMode.name(), ref);
        DisguiseCommand.enableNickDisguiseAsync(raw, skinMode, ref, this.parent);
        goBack();
    }

    private void goBack() {
        if (this.mc != null) {
            this.mc.displayGuiScreen(this.parent);
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < raw.length() && cleaned.length() < 16; ++i) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.nickField != null && this.nickField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (skinModeNeedsPlayerName() && this.skinPlayerField != null && this.skinPlayerField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if ("CUSTOM".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())
                && this.prefixCustomField != null && this.prefixCustomField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            apply();
            return;
        }
        if (keyCode == 1) {
            goBack();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && "HYPIXEL_OFFICIAL".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())) {
            layoutOfficialCatalogPanel();
            if (this.officialCatalog.handleClick(mouseX, mouseY)) {
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.nickField != null) {
            this.nickField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (skinModeNeedsPlayerName() && this.skinPlayerField != null) {
            this.skinPlayerField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if ("CUSTOM".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode()) && this.prefixCustomField != null) {
            this.prefixCustomField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        if (this.nickField != null) {
            this.nickField.updateCursorCounter();
        }
        if (this.skinPlayerField != null) {
            this.skinPlayerField.updateCursorCounter();
        }
        if (this.prefixCustomField != null) {
            this.prefixCustomField.updateCursorCounter();
        }
    }

    private void drawSkinPreview(int px, int py, int size) {
        switch (skinMode) {
            case STEVE:
                PlayerHeadTextureUtil.drawMinotarAvatar("MHF_Steve", px, py, size);
                break;
            case PLAYER_SKIN:
                String ign = this.skinPlayerField != null ? sanitize(this.skinPlayerField.getText()) : "";
                if (ign.length() >= 1) {
                    PlayerHeadTextureUtil.drawMinotarAvatar(ign, px, py, size);
                } else {
                    PlayerHeadTextureUtil.drawMinotarAvatar("MHF_Steve", px, py, size);
                }
                break;
            case SKIN_FILE:
                String path = this.pendingPickedFile;
                if (path != null && !path.isEmpty()) {
                    PlayerHeadTextureUtil.drawLocalSkinHeadPreview(path, px, py, size);
                } else {
                    PlayerHeadTextureUtil.drawMinotarAvatar("MHF_Steve", px, py, size);
                }
                break;
            default:
                break;
        }
    }

    private void layoutOfficialCatalogPanel() {
        int catalogLeft = this.leftX + this.colW + CATALOG_GAP;
        int catalogTop = GuiHeader.STANDARD_SUBTITLE_Y + 2;
        int catalogBottom = Math.min(this.applyY + 24, this.height - 38);
        this.officialCatalog.layout(catalogLeft, catalogTop, CATALOG_W, catalogBottom);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (!"HYPIXEL_OFFICIAL".equalsIgnoreCase(ConfigManager.getDisguiseHypixelPrefixMode())) {
            return;
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            layoutOfficialCatalogPanel();
            this.officialCatalog.handleWheel(wheel);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        relayoutWidgets();

        String prefixMode = ConfigManager.getDisguiseHypixelPrefixMode();
        boolean official = "HYPIXEL_OFFICIAL".equalsIgnoreCase(prefixMode);
        boolean customRank = "CUSTOM".equalsIgnoreCase(prefixMode);
        String customRankDraft = customRank && this.prefixCustomField != null
                ? this.prefixCustomField.getText()
                : null;
        int previewCx = official ? this.leftX + this.colW / 2 : this.width / 2;
        int previewY = GuiHeader.STANDARD_SUBTITLE_Y + 6;
        int headSize = 18;

        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.LIGHT_PURPLE.toString() + EnumChatFormatting.BOLD + "Nick disguise",
                this.width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        String rawDraft = this.nickField != null ? this.nickField.getText().trim() : "";
        String namePreview = LocalDisguise.previewNickDisguiseNameLine(rawDraft, customRankDraft);
        String namePlain = EnumChatFormatting.getTextWithoutFormattingCodes(namePreview);
        int nameW = this.fontRendererObj.getStringWidth(namePlain == null ? "" : namePlain);
        int lineW = headSize + 4 + nameW;
        int lineX = previewCx - lineW / 2;
        drawSkinPreview(lineX, previewY, headSize);
        this.fontRendererObj.drawStringWithShadow(namePreview, lineX + headSize + 4, previewY + 5, 0xFFFFFF);
        String status = LocalDisguise.getNickSetupPrefixStatusLine(customRankDraft);
        int statusW = this.fontRendererObj.getStringWidth(EnumChatFormatting.getTextWithoutFormattingCodes(status));
        this.fontRendererObj.drawStringWithShadow(status, previewCx - statusW / 2, previewY + headSize + 8, 0xFFFFFF);

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.WHITE + "Username", this.leftX, this.usernameLabelY, 0xFFFFFF);
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + "Use & for colors (e.g. &6&l)",
                this.leftX, this.usernameLabelY + 11, 0x888888);
        if (this.nickField != null) {
            this.nickField.drawTextBox();
        }
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.WHITE + "Skin source", this.leftX, this.skinSourceLabelY, 0xFFFFFF);

        if (skinModeNeedsPlayerName()) {
            this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.WHITE + "Copy from player", this.leftX, this.skinPlayerLabelY, 0xFFFFFF);
            if (this.skinPlayerField != null) {
                this.skinPlayerField.drawTextBox();
            }
        }

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.WHITE + "Hypixel prefix", this.leftX, this.prefixLabelY, 0xFFFFFF);
        if ("CUSTOM".equalsIgnoreCase(prefixMode) && this.prefixCustomField != null) {
            this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD + "Custom rank (&c, &l, …)",
                    this.leftX, this.prefixExtraY - 12, 0xFFFFFF);
            this.prefixCustomField.drawTextBox();
        }

        if (official) {
            MixinStatus.skipFontRendererDisguiseReplace = true;
            try {
                layoutOfficialCatalogPanel();
                String display;
                if (rawDraft != null && !rawDraft.isEmpty()) {
                    String plain = LocalDisguise.stripToPlainNickForOfflineUuid(rawDraft);
                    String colored = LocalDisguise.translateDisguiseAmpersandColors(rawDraft);
                    display = (colored != null && !colored.isEmpty()) ? colored : plain;
                } else {
                    display = LocalDisguise.getHypixelPrefixPreviewDisplayName();
                }
                this.officialCatalog.draw(this.fontRendererObj, mouseX, mouseY, display);
            } finally {
                MixinStatus.skipFontRendererDisguiseReplace = false;
            }
        }

        refreshSkinButtonsHighlight();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(nickField, skinPlayerField, prefixCustomField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
