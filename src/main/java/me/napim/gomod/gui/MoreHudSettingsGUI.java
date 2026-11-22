package me.napim.gomod.gui;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MoreHudArmorStatusFeature;
import me.napim.gomod.features.MoreHudCoordsFeature;
import me.napim.gomod.features.MoreHudFeature;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Per-row style toggles for {@link me.napim.gomod.features.MoreHudFeature}.
 * Opened from the HUD Editor (right-click a row); there is no separate More HUD hub screen.
 */
public class MoreHudSettingsGUI extends GuiScreen {

    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final String RESET_POSITION_SCALE =
            EnumChatFormatting.GREEN + "Reset position & scale";
    /** Avoid id 0 — some screens/handlers treat it specially; CPS panel Back must always fire. */
    private static final int BTN_BACK = 99;
    private static final int BTN_ID_FPS_PRESET_LABEL = 4;
    private static final int BTN_ID_FPS_CUSTOM_LABEL = 15;
    private static final int FPS_CUSTOM_FIELD_ID = 16;
    private static final int FPS_LABEL_BTN_GAP = 4;
    private static final int FPS_DISPLAY_STYLE_CUSTOM = 3;
    private static final int FPS_DISPLAY_STYLE_FPS_PER_SEC = 4;
    private static final int BTN_ID_PING_PRESET_LABEL = 4;
    private static final int BTN_ID_PING_CUSTOM_LABEL = 17;
    private static final int PING_CUSTOM_FIELD_ID = 18;
    private static final int PING_DISPLAY_STYLE_CUSTOM = 3;

    private final GuiScreen parent;
    private final Panel panel;

    /**
     * Left-click handling assigns this instead of {@code GuiScreen.selectedButton}: that field is private at runtime
     * ({@code field_146290_a}), so subclasses cannot set it — {@link IllegalAccessError}. We mirror release behavior in
     * {@link #mouseReleased}.
     */
    private GuiButton pendingMouseReleaseButton;

    /** Background opacity row (combined label+value drawn on slider track). */
    private int potionsBgOpacityLabelY;
    private int potionsBgOpacitySliderX;
    private int potionsBgOpacitySliderY;
    private int fpsBgOpacityLabelY;
    private int fpsBgOpacitySliderX;
    private int fpsBgOpacitySliderY;
    private int cpsBgOpacityLabelY;
    private int cpsBgOpacitySliderX;
    private int cpsBgOpacitySliderY;
    private int pingBgOpacityLabelY;
    private int pingBgOpacitySliderX;
    private int pingBgOpacitySliderY;
    private int killEffectsBgOpacityLabelY;
    private int killEffectsBgOpacitySliderX;
    private int killEffectsBgOpacitySliderY;
    private int arrowsBgOpacityLabelY;
    private int arrowsBgOpacitySliderX;
    private int arrowsBgOpacitySliderY;
    private int armorStatusBgOpacitySliderX;
    private int armorStatusBgOpacitySliderY;
    private ArmorSlotGapSlider armorSlotGapSlider;
    private ArmorTextScaleSlider armorTextScaleSlider;
    private int coordsBgOpacitySliderX;
    private int coordsBgOpacitySliderY;
    private int coordsResetButtonY;
    private long coordsResetAckUntilMs;
    private int blitzKitTimerBgOpacitySliderX;
    private int blitzKitTimerBgOpacitySliderY;
    private static final int FLAME_BG_SLIDER_W = 120;
    private static final int FLAME_BG_SLIDER_H = 20;
    /** One row: combined-label slider only (+ small gap before next control). */
    private static final int BG_OPACITY_BLOCK_H_COMBINED = FLAME_BG_SLIDER_H + 4;
    /** Center anchor for live HUD preview between Reset and Back. */
    private int modulePreviewAnchorX;
    private int modulePreviewAnchorY;
    private boolean potionsBgOpacityDragging;
    private boolean fpsBgOpacityDragging;
    private boolean cpsBgOpacityDragging;
    private boolean pingBgOpacityDragging;
    private boolean killEffectsBgOpacityDragging;
    private boolean arrowsBgOpacityDragging;
    private boolean armorStatusBgOpacityDragging;
    private boolean coordsBgOpacityDragging;
    private boolean blitzKitTimerBgOpacityDragging;
    /** Color picker + opacity slider for the current panel's background row. */
    private HudBackgroundOpacityRow bgOpacityRow;
    private GuiTextField fpsCustomLabelField;
    private boolean fpsCustomFieldFocusOnInit;
    /** Last preset label style before entering custom (restored when Custom is toggled off). */
    private int fpsLastPresetStyle;
    private GuiTextField pingCustomLabelField;
    private boolean pingCustomFieldFocusOnInit;
    /** Last preset label style before entering custom (restored when Custom is toggled off). */
    private int pingLastPresetStyle;

    public enum Panel {
        FPS,
        CPS,
        PING,
        POTIONS,
        ARROWS,
        ARMOR_STATUS,
        KILL_EFFECTS,
        FLAME,
        BLITZ_KIT_TIMER,
        INCOMING_SCREENSHOT,
        COORDS
    }

    public MoreHudSettingsGUI(GuiScreen parent, Panel initialPanel) {
        this.parent = parent;
        this.panel = initialPanel != null ? initialPanel : Panel.FPS;
    }

    public Panel getPanel() {
        return panel;
    }

    @Override
    public void initGui() {
        super.initGui();
        potionsBgOpacityDragging = false;
        fpsBgOpacityDragging = false;
        cpsBgOpacityDragging = false;
        pingBgOpacityDragging = false;
        killEffectsBgOpacityDragging = false;
        arrowsBgOpacityDragging = false;
        armorStatusBgOpacityDragging = false;
        coordsBgOpacityDragging = false;
        blitzKitTimerBgOpacityDragging = false;
        modulePreviewAnchorX = 0;
        modulePreviewAnchorY = 0;
        fpsCustomLabelField = null;
        pingCustomLabelField = null;
        armorSlotGapSlider = null;
        armorTextScaleSlider = null;
        buttonList.clear();
        int cx = width / 2;
        int w = BTN_W;
        int g = 22;

        switch (panel) {
            case FPS:
                initFpsPanel(cx, w, g, fpsPanelContentStartY(g));
                break;
            case CPS:
                initCpsPanel(cx, w, g, centeredContentStartY(6 * BTN_H + 6 * g + 8 + BG_OPACITY_BLOCK_H_COMBINED));
                break;
            case PING:
                initPingPanel(cx, w, g, pingPanelContentStartY(g));
                break;
            case POTIONS:
                initPotionsPanel(cx, w, g,
                        centeredContentStartY(6 * BTN_H + 6 * g + 8 + BG_OPACITY_BLOCK_H_COMBINED));
                break;
            case ARROWS:
                initArrowsPanel(cx, w, g, centeredContentStartY(4 * BTN_H + 4 * g + 8 + BG_OPACITY_BLOCK_H_COMBINED));
                break;
            case ARMOR_STATUS:
                initArmorStatusPanel(cx, w, g,
                        centeredContentStartY(11 * BTN_H + 10 * g + 8 + BG_OPACITY_BLOCK_H_COMBINED));
                break;
            case KILL_EFFECTS:
                initKillEffectsPanel(cx, w, g, centeredContentStartY(3 * BTN_H + 3 * g + 8 + BG_OPACITY_BLOCK_H_COMBINED));
                break;
            case FLAME:
                int flameG = height < 500 ? 22 : Math.max(g, 26);
                int flameY = centeredContentStartY(flamePanelBlockHeight(flameG)) + 6;
                initFlamePanel(cx, w, flameG, flameY);
                break;
            case BLITZ_KIT_TIMER:
                initBlitzKitTimerPanel(cx, w, g, centeredContentStartY(3 * BTN_H + 3 * g + 8 + BG_OPACITY_BLOCK_H_COMBINED));
                break;
            case INCOMING_SCREENSHOT:
                initIncomingScreenshotPanel(cx, w, g, centeredContentStartY(3 * BTN_H + 2 * g + 8));
                break;
            case COORDS:
                initCoordsPanel(cx, w, g,
                        centeredContentStartY(14 * BTN_H + 14 * g + 8 + BG_OPACITY_BLOCK_H_COMBINED));
                break;
            default:
                break;
        }
        refreshBgOpacityRow();
    }

    private void refreshBgOpacityRow() {
        bgOpacityRow = newBgOpacityRowForPanel(panel);
        if (bgOpacityRow != null) {
            bgOpacityRow.setInsetModule(panelToInsetModule(panel));
            bgOpacityRow.syncPickerFromConfig();
        }
    }

    private static HudBackgroundOpacityRow newBgOpacityRowForPanel(Panel panel) {
        if (panel == null) {
            return null;
        }
        switch (panel) {
            case FPS:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudFpsBg, ConfigManager::setMoreHudFpsBg,
                        ConfigManager::getMoreHudFpsBgColor, ConfigManager::setMoreHudFpsBgColor);
            case CPS:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudCpsBg, ConfigManager::setMoreHudCpsBg,
                        ConfigManager::getMoreHudCpsBgColor, ConfigManager::setMoreHudCpsBgColor);
            case PING:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudPingBg, ConfigManager::setMoreHudPingBg,
                        ConfigManager::getMoreHudPingBgColor, ConfigManager::setMoreHudPingBgColor);
            case POTIONS:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudPotionsBg,
                        ConfigManager::setMoreHudPotionsBg, ConfigManager::getMoreHudPotionsBgColor,
                        ConfigManager::setMoreHudPotionsBgColor);
            case ARROWS:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudArrowsBg, ConfigManager::setMoreHudArrowsBg,
                        ConfigManager::getMoreHudArrowsBgColor, ConfigManager::setMoreHudArrowsBgColor);
            case ARMOR_STATUS:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudArmorStatusBg,
                        ConfigManager::setMoreHudArmorStatusBg, ConfigManager::getMoreHudArmorStatusBgColor,
                        ConfigManager::setMoreHudArmorStatusBgColor);
            case KILL_EFFECTS:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudKillEffectsBg,
                        ConfigManager::setMoreHudKillEffectsBg, ConfigManager::getMoreHudKillEffectsBgColor,
                        ConfigManager::setMoreHudKillEffectsBgColor);
            case FLAME:
                return null;
            case COORDS:
                return new HudBackgroundOpacityRow(ConfigManager::getMoreHudCoordsBg, ConfigManager::setMoreHudCoordsBg,
                        ConfigManager::getMoreHudCoordsBgColor, ConfigManager::setMoreHudCoordsBgColor);
            case BLITZ_KIT_TIMER:
                return new HudBackgroundOpacityRow(ConfigManager::getBlitzKitTimerHudBg,
                        ConfigManager::setBlitzKitTimerHudBg, ConfigManager::getBlitzKitTimerHudBgColor,
                        ConfigManager::setBlitzKitTimerHudBgColor);
            default:
                return null;
        }
    }

    private void addBackRow(int cx, int w, int unusedNextRowY) {
        int barY = GuiHeader.backButtonY(height, BTN_H);
        String label = EnumChatFormatting.GRAY + "Back";
        int backW = Math.min(w, Math.max(60, fontRendererObj != null ? fontRendererObj.getStringWidth(label) + 24 : 60));
        buttonList.add(new GuiButton(BTN_BACK, cx - backW / 2, barY, backW, BTN_H, label));
    }

    /**
     * First row Y so a column of total pixel height {@code blockHeightPx} sits vertically centered between the
     * subtitle block and the bottom Back bar (clamped to the top when the screen is too short).
     */
    private int centeredContentStartY(int blockHeightPx) {
        int top = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj);
        int backTop = GuiHeader.backButtonY(height, BTN_H);
        int margin = 8;
        int avail = backTop - top - margin;
        if (avail <= blockHeightPx) {
            return top;
        }
        return top + (avail - blockHeightPx) / 2;
    }

    /** Pixel height of {@link #initFlamePanel} body (all rows + trailing spacer before Back). */
    private int flamePanelBlockHeight(int rowGap) {
        return 5 * BTN_H + 4 * rowGap + 8;
    }

    private int fpsPanelContentStartY(int g) {
        int rows = ConfigManager.getMoreHudFpsDisplayStyle() == FPS_DISPLAY_STYLE_CUSTOM ? 6 : 5;
        return centeredContentStartY(rows * BTN_H + (rows - 1) * g + 8 + BG_OPACITY_BLOCK_H_COMBINED);
    }

    private void initFpsPanel(int cx, int w, int g, int y) {
        int fpsStyle = ConfigManager.getMoreHudFpsDisplayStyle();
        if (fpsStyle != FPS_DISPLAY_STYLE_CUSTOM) {
            fpsLastPresetStyle = fpsStyle;
        }
        int rowLeft = cx - w / 2;
        buttonList.add(new GuiButton(1, rowLeft, y, w, BTN_H, row("FPS HUD", ConfigManager.isMoreHudFpsEnabled())));
        y += g;
        buttonList.add(new GuiButton(2, rowLeft, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudFpsShadow())));
        y += g;
        fpsBgOpacityLabelY = y;
        fpsBgOpacitySliderX = cx - w / 2;
        fpsBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        int halfW = (w - FPS_LABEL_BTN_GAP) / 2;
        buttonList.add(new GuiButton(BTN_ID_FPS_PRESET_LABEL, rowLeft, y, halfW, BTN_H, fpsPresetLabelButton()));
        buttonList.add(new GuiButton(BTN_ID_FPS_CUSTOM_LABEL, rowLeft + halfW + FPS_LABEL_BTN_GAP, y, halfW, BTN_H,
                fpsCustomLabelButton()));
        y += g;
        if (ConfigManager.getMoreHudFpsDisplayStyle() == FPS_DISPLAY_STYLE_CUSTOM) {
            Keyboard.enableRepeatEvents(true);
            fpsCustomLabelField = new GuiTextField(FPS_CUSTOM_FIELD_ID, fontRendererObj, rowLeft, y, w, BTN_H);
            fpsCustomLabelField.setMaxStringLength(48);
            fpsCustomLabelField.setText(ConfigManager.getMoreHudFpsCustomLabel());
            fpsCustomLabelField.setFocused(fpsCustomFieldFocusOnInit);
            fpsCustomFieldFocusOnInit = false;
            y += g;
        } else {
            Keyboard.enableRepeatEvents(false);
        }
        buttonList.add(new GuiButton(14, rowLeft, y, w, BTN_H, colorSettingsButtonLabel()));
        y += g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private void initCpsPanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H, row("CPS row", ConfigManager.isMoreHudCpsEnabled())));
        y += g;
        buttonList.add(new GuiButton(4, cx - w / 2, y, w, BTN_H, cpsModeLabel()));
        y += g;
        buttonList.add(new GuiButton(5, cx - w / 2, y, w, BTN_H, row("Brackets", ConfigManager.isMoreHudCpsSquareBrackets())));
        y += g;
        buttonList.add(new GuiButton(2, cx - w / 2, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudCpsShadow())));
        y += g;
        buttonList.add(new GuiButton(14, cx - w / 2, y, w, BTN_H, colorSettingsButtonLabel()));
        y += g;
        cpsBgOpacityLabelY = y;
        cpsBgOpacitySliderX = cx - w / 2;
        cpsBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private int pingPanelContentStartY(int g) {
        int rows = ConfigManager.getMoreHudPingDisplayStyle() == PING_DISPLAY_STYLE_CUSTOM ? 6 : 5;
        return centeredContentStartY(rows * BTN_H + (rows - 1) * g + 8 + BG_OPACITY_BLOCK_H_COMBINED);
    }

    private void initPingPanel(int cx, int w, int g, int y) {
        int pingStyle = ConfigManager.getMoreHudPingDisplayStyle();
        if (pingStyle != PING_DISPLAY_STYLE_CUSTOM) {
            pingLastPresetStyle = pingStyle;
        }
        int rowLeft = cx - w / 2;
        buttonList.add(new GuiButton(1, rowLeft, y, w, BTN_H, row("Ping row", ConfigManager.isMoreHudPingEnabled())));
        y += g;
        buttonList.add(new GuiButton(2, rowLeft, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudPingShadow())));
        y += g;
        int halfW = (w - FPS_LABEL_BTN_GAP) / 2;
        buttonList.add(new GuiButton(BTN_ID_PING_PRESET_LABEL, rowLeft, y, halfW, BTN_H, pingPresetLabelButton()));
        buttonList.add(new GuiButton(BTN_ID_PING_CUSTOM_LABEL, rowLeft + halfW + FPS_LABEL_BTN_GAP, y, halfW, BTN_H,
                pingCustomLabelButton()));
        y += g;
        if (ConfigManager.getMoreHudPingDisplayStyle() == PING_DISPLAY_STYLE_CUSTOM) {
            Keyboard.enableRepeatEvents(true);
            pingCustomLabelField = new GuiTextField(PING_CUSTOM_FIELD_ID, fontRendererObj, rowLeft, y, w, BTN_H);
            pingCustomLabelField.setMaxStringLength(48);
            pingCustomLabelField.setText(ConfigManager.getMoreHudPingCustomLabel());
            pingCustomLabelField.setFocused(pingCustomFieldFocusOnInit);
            pingCustomFieldFocusOnInit = false;
            y += g;
        } else {
            Keyboard.enableRepeatEvents(false);
        }
        buttonList.add(new GuiButton(14, rowLeft, y, w, BTN_H, colorSettingsButtonLabel()));
        y += g;
        pingBgOpacityLabelY = y;
        pingBgOpacitySliderX = cx - w / 2;
        pingBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private static String fpsPresetLabelButton() {
        int style = ConfigManager.getMoreHudFpsDisplayStyle();
        if (style == FPS_DISPLAY_STYLE_CUSTOM) {
            style = 0;
        }
        String sample;
        switch (style) {
            case 1:
                sample = "FPS 500";
                break;
            case 2:
                sample = "500 FPS";
                break;
            case FPS_DISPLAY_STYLE_FPS_PER_SEC:
                sample = "500 f/s";
                break;
            case 0:
            default:
                sample = "FPS: 500";
                break;
        }
        return EnumChatFormatting.WHITE + "Label: " + EnumChatFormatting.YELLOW + sample;
    }

    private static int nextFpsPresetDisplayStyle(int style) {
        if (style == FPS_DISPLAY_STYLE_CUSTOM) {
            return 0;
        }
        switch (style) {
            case 0:
                return 1;
            case 1:
                return 2;
            case 2:
                return FPS_DISPLAY_STYLE_FPS_PER_SEC;
            case FPS_DISPLAY_STYLE_FPS_PER_SEC:
            default:
                return 0;
        }
    }

    private static String fpsCustomLabelButton() {
        if (ConfigManager.getMoreHudFpsDisplayStyle() == FPS_DISPLAY_STYLE_CUSTOM) {
            return EnumChatFormatting.GREEN + "Custom";
        }
        return EnumChatFormatting.WHITE + "Custom";
    }

    private void commitFpsCustomLabelField() {
        if (fpsCustomLabelField != null) {
            String validated = ConfigManager.validateMoreHudFpsCustomLabel(fpsCustomLabelField.getText());
            fpsCustomLabelField.setText(validated);
            ConfigManager.setMoreHudFpsCustomLabel(validated);
        }
    }

    private void validateFpsCustomLabelFieldOnEnter() {
        if (fpsCustomLabelField == null) {
            return;
        }
        String validated = ConfigManager.validateMoreHudFpsCustomLabel(fpsCustomLabelField.getText());
        fpsCustomLabelField.setText(validated);
        ConfigManager.setMoreHudFpsCustomLabel(validated);
        ConfigManager.setMoreHudFpsDisplayStyle(FPS_DISPLAY_STYLE_CUSTOM);
        fpsCustomLabelField.setFocused(false);
        mc.getSoundHandler().playSound(
                PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private static String pingPresetLabelButton() {
        int style = ConfigManager.getMoreHudPingDisplayStyle();
        if (style == PING_DISPLAY_STYLE_CUSTOM) {
            style = 0;
        }
        String sample;
        switch (style) {
            case 2:
                sample = "Ping: 20";
                break;
            case 1:
                sample = "Ping: 20ms";
                break;
            case 0:
            default:
                sample = "20ms";
                break;
        }
        return EnumChatFormatting.WHITE + "Label: " + EnumChatFormatting.YELLOW + sample;
    }

    private static String pingCustomLabelButton() {
        if (ConfigManager.getMoreHudPingDisplayStyle() == PING_DISPLAY_STYLE_CUSTOM) {
            return EnumChatFormatting.GREEN + "Custom";
        }
        return EnumChatFormatting.WHITE + "Custom";
    }

    private void commitPingCustomLabelField() {
        if (pingCustomLabelField != null) {
            String validated = ConfigManager.validateMoreHudPingCustomLabel(pingCustomLabelField.getText());
            pingCustomLabelField.setText(validated);
            ConfigManager.setMoreHudPingCustomLabel(validated);
        }
    }

    private void validatePingCustomLabelFieldOnEnter() {
        if (pingCustomLabelField == null) {
            return;
        }
        String validated = ConfigManager.validateMoreHudPingCustomLabel(pingCustomLabelField.getText());
        pingCustomLabelField.setText(validated);
        ConfigManager.setMoreHudPingCustomLabel(validated);
        ConfigManager.setMoreHudPingDisplayStyle(PING_DISPLAY_STYLE_CUSTOM);
        pingCustomLabelField.setFocused(false);
        mc.getSoundHandler().playSound(
                PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private void initPotionsPanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H, row("Effects HUD", ConfigManager.isMoreHudPotionsEnabled())));
        y += g;
        buttonList.add(new GuiButton(2, cx - w / 2, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudPotionsShadow())));
        y += g;
        potionsBgOpacityLabelY = y;
        potionsBgOpacitySliderX = cx - w / 2;
        potionsBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        buttonList.add(new GuiButton(4, cx - w / 2, y, w, BTN_H, potionsEffectTextsModeLabel()));
        y += g;
        buttonList.add(new GuiButton(92, cx - w / 2, y, w, BTN_H,
                row("Show Flaming Arrows KE", ConfigManager.isMoreHudPotionsShowFlamingArrows())));
        y += g;
        buttonList.add(new GuiButton(93, cx - w / 2, y, w, BTN_H, potionsEffectsTimerStyleLabel()));
        y += g;
        buttonList.add(new GuiButton(14, cx - w / 2, y, w, BTN_H, colorSettingsButtonLabel()));
        y += g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private void initCoordsPanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H, row("Coords HUD", ConfigManager.isMoreHudCoordsEnabled())));
        y += g;
        buttonList.add(new GuiButton(2, cx - w / 2, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudCoordsShadow())));
        y += g;
        buttonList.add(new GuiButton(3, cx - w / 2, y, w, BTN_H, coordsLayoutLabel()));
        y += g;
        boolean compassOnly = ConfigManager.isMoreHudCoordsCompassOnly();
        GuiButton prefixStyleBtn = new GuiButton(6, cx - w / 2, y, w, BTN_H, coordsPrefixStyleLabel());
        prefixStyleBtn.enabled = !compassOnly;
        buttonList.add(prefixStyleBtn);
        y += g;
        GuiButton decimalsBtn = new GuiButton(7, cx - w / 2, y, w, BTN_H, coordsDecimalsLabel());
        decimalsBtn.enabled = !compassOnly;
        buttonList.add(decimalsBtn);
        y += g;
        ensureDirectionForCompassLayout();
        GuiButton directionBtn = new GuiButton(5, cx - w / 2, y, w, BTN_H,
                row("Direction", ConfigManager.isMoreHudCoordsDirection()));
        directionBtn.enabled = ConfigManager.isMoreHudCoordsVertical();
        buttonList.add(directionBtn);
        y += g;
        buttonList.add(new GuiButton(8, cx - w / 2, y, w, BTN_H,
                row("Show during F3", ConfigManager.isMoreHudCoordsShowDuringF3())));
        y += g;
        GuiButton biomeBtn = new GuiButton(4, cx - w / 2, y, w, BTN_H, row("Biome name", ConfigManager.isMoreHudCoordsBiome()));
        biomeBtn.enabled = !compassOnly;
        buttonList.add(biomeBtn);
        y += g;
        buttonList.add(new GuiButton(14, cx - w / 2, y, w, BTN_H, colorSettingsButtonLabel()));
        y += g;
        coordsBgOpacitySliderX = cx - w / 2;
        coordsBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        coordsResetButtonY = y;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private static String coordsLayoutLabel() {
        switch (ConfigManager.getMoreHudCoordsLayout()) {
            case ConfigManager.COORDS_LAYOUT_COMPASS:
                return EnumChatFormatting.WHITE + "Layout: " + EnumChatFormatting.AQUA + "Vertical + Compass";
            case ConfigManager.COORDS_LAYOUT_COMPASS_ONLY:
                return EnumChatFormatting.WHITE + "Layout: " + EnumChatFormatting.AQUA + "Compass only";
            case ConfigManager.COORDS_LAYOUT_VERTICAL:
            default:
                return EnumChatFormatting.WHITE + "Layout: " + EnumChatFormatting.GREEN + "Vertical";
        }
    }

    /** Compass layouts need direction on; keep it enabled when switching away from vertical. */
    private static void ensureDirectionForCompassLayout() {
        if (ConfigManager.isMoreHudCoordsCompassLayout() && !ConfigManager.isMoreHudCoordsDirection()) {
            ConfigManager.setMoreHudCoordsDirection(true);
        }
    }

    private static String colorSettingsButtonLabel() {
        return EnumChatFormatting.GOLD + "Color settings" + EnumChatFormatting.GRAY + "…";
    }

    private static String coordsColorLabel(String name, int rgb) {
        return EnumChatFormatting.WHITE + name + ": "
                + EnumChatFormatting.RESET + colorCodeForRgb(rgb) + "#"
                + String.format("%06X", rgb & 0xFFFFFF);
    }

    private static String colorCodeForRgb(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (r > 200 && g > 200 && b > 200) {
            return EnumChatFormatting.WHITE.toString();
        }
        if (r > 200 && g < 100 && b < 100) {
            return EnumChatFormatting.RED.toString();
        }
        if (r < 100 && g > 200 && b < 100) {
            return EnumChatFormatting.GREEN.toString();
        }
        if (r < 100 && g > 200 && b > 200) {
            return EnumChatFormatting.AQUA.toString();
        }
        if (r > 200 && g > 200 && b < 100) {
            return EnumChatFormatting.YELLOW.toString();
        }
        return EnumChatFormatting.GRAY.toString();
    }

    private static String coordsPrefixStyleLabel() {
        switch (ConfigManager.getMoreHudCoordsPrefixStyle()) {
            case 1:
                return EnumChatFormatting.WHITE + "Prefix style: " + EnumChatFormatting.GREEN + "Regular, no-colon";
            case 2:
                return EnumChatFormatting.WHITE + "Prefix style: " + EnumChatFormatting.AQUA + "Parentheses";
            case 3:
                return EnumChatFormatting.WHITE + "Prefix style: " + EnumChatFormatting.AQUA + "Square brackets";
            case 4:
                return EnumChatFormatting.WHITE + "Prefix style: " + EnumChatFormatting.AQUA + "None";
            case 5:
                return EnumChatFormatting.WHITE + "Prefix style: " + EnumChatFormatting.AQUA + "Arrow";
            case 0:
            default:
                return EnumChatFormatting.WHITE + "Prefix style: " + EnumChatFormatting.GREEN + "Regular";
        }
    }

    private static String coordsDecimalsLabel() {
        return EnumChatFormatting.WHITE + "Decimals: " + EnumChatFormatting.YELLOW
                + ConfigManager.getMoreHudCoordsDecimals();
    }

    private void initArrowsPanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H, row("Arrows count", ConfigManager.isMoreHudArrowsEnabled())));
        y += g;
        buttonList.add(new GuiButton(2, cx - w / 2, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudArrowsShadow())));
        y += g;
        buttonList.add(new GuiButton(14, cx - w / 2, y, w, BTN_H, colorSettingsButtonLabel()));
        y += g;
        arrowsBgOpacityLabelY = y;
        arrowsBgOpacitySliderX = cx - w / 2;
        arrowsBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private void initArmorStatusPanel(int cx, int w, int g, int y) {
        int rowLeft = cx - w / 2;
        buttonList.add(new GuiButton(1, rowLeft, y, w, BTN_H,
                row("Armor status HUD", ConfigManager.isMoreHudArmorStatusEnabled())));
        y += g;
        buttonList.add(new GuiButton(2, rowLeft, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudArmorStatusShadow())));
        y += g;
        buttonList.add(new GuiButton(36, rowLeft, y, w, BTN_H, armorStatusLayoutLabel()));
        y += g;
        buttonList.add(new GuiButton(3, rowLeft, y, w, BTN_H, armorStatusDuraModeLabel()));
        y += g;
        buttonList.add(new GuiButton(4, rowLeft, y, w, BTN_H, armorStatusDurabilityBarLabel()));
        y += g;
        GuiButton duraSideButton = new GuiButton(5, rowLeft, y, w, BTN_H, armorStatusDuraSideLabel());
        MoreHudArmorStatusFeature.LayoutMode layoutMode = MoreHudArmorStatusFeature.LayoutMode
                .fromIndex(ConfigManager.getMoreHudArmorStatusLayout());
        duraSideButton.enabled = layoutMode.usesSideDurabilityText();
        buttonList.add(duraSideButton);
        y += g;
        buttonList.add(new GuiButton(14, rowLeft, y, w, BTN_H, colorSettingsButtonLabel()));
        y += g;
        int slotGap = 4;
        int slotBtnW = (w - slotGap * 4) / 5;
        int slotX = rowLeft;
        buttonList.add(new ArmorSlotIconButton(30, slotX, y, slotBtnW, BTN_H, new ItemStack(Items.diamond_helmet),
                ConfigManager::isMoreHudArmorStatusShowHelmet));
        slotX += slotBtnW + slotGap;
        buttonList.add(new ArmorSlotIconButton(31, slotX, y, slotBtnW, BTN_H, new ItemStack(Items.diamond_chestplate),
                ConfigManager::isMoreHudArmorStatusShowChest));
        slotX += slotBtnW + slotGap;
        buttonList.add(new ArmorSlotIconButton(32, slotX, y, slotBtnW, BTN_H, new ItemStack(Items.diamond_leggings),
                ConfigManager::isMoreHudArmorStatusShowLegs));
        slotX += slotBtnW + slotGap;
        buttonList.add(new ArmorSlotIconButton(33, slotX, y, slotBtnW, BTN_H, new ItemStack(Items.diamond_boots),
                ConfigManager::isMoreHudArmorStatusShowBoots));
        slotX += slotBtnW + slotGap;
        buttonList.add(new ArmorHeldPlacementButton(34, slotX, y, slotBtnW, BTN_H));
        y += g;
        boolean verticalLayout = layoutMode.isVertical();
        armorSlotGapSlider = new ArmorSlotGapSlider(35, rowLeft, y, w, BTN_H,
                MoreHudArmorStatusFeature.getArmorSlotGapMin(verticalLayout),
                MoreHudArmorStatusFeature.getArmorSlotGapMax(verticalLayout),
                ConfigManager.getMoreHudArmorStatusArmorSlotGap());
        buttonList.add(armorSlotGapSlider);
        y += g;
        armorTextScaleSlider = new ArmorTextScaleSlider(37, rowLeft, y, w, BTN_H,
                MoreHudArmorStatusFeature.ARMOR_TEXT_SCALE_MIN,
                MoreHudArmorStatusFeature.ARMOR_TEXT_SCALE_MAX,
                ConfigManager.getMoreHudArmorStatusTextScale());
        buttonList.add(armorTextScaleSlider);
        y += g;
        armorStatusBgOpacitySliderX = rowLeft;
        armorStatusBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        int resetY = y - 3;
        buttonList.add(new GuiButton(90, rowLeft, resetY, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private static String armorStatusDuraModeLabel() {
        MoreHudArmorStatusFeature.DurabilityMode mode = MoreHudArmorStatusFeature.DurabilityMode
                .fromIndex(ConfigManager.getMoreHudArmorStatusDuraMode());
        return EnumChatFormatting.WHITE + "Durability: " + EnumChatFormatting.YELLOW + mode.getLabel();
    }

    private static String armorStatusLayoutLabel() {
        MoreHudArmorStatusFeature.LayoutMode layoutMode = MoreHudArmorStatusFeature.LayoutMode
                .fromIndex(ConfigManager.getMoreHudArmorStatusLayout());
        return EnumChatFormatting.WHITE + "Layout: "
                + (layoutMode == MoreHudArmorStatusFeature.LayoutMode.VERTICAL
                ? EnumChatFormatting.GREEN + layoutMode.getLabel()
                : EnumChatFormatting.AQUA + layoutMode.getLabel());
    }

    private static String armorStatusDurabilityBarLabel() {
        return EnumChatFormatting.WHITE + "Durability bar: "
                + (ConfigManager.isMoreHudArmorStatusDurabilityBar()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private static String armorStatusDuraSideLabel() {
        MoreHudArmorStatusFeature.LayoutMode layoutMode = MoreHudArmorStatusFeature.LayoutMode
                .fromIndex(ConfigManager.getMoreHudArmorStatusLayout());
        if (layoutMode.durabilityBelowItems()) {
            return EnumChatFormatting.WHITE + "Durability text: " + EnumChatFormatting.AQUA + "Below";
        }
        if (layoutMode.durabilityAboveItems()) {
            return EnumChatFormatting.WHITE + "Durability text: " + EnumChatFormatting.AQUA + "Above";
        }
        return EnumChatFormatting.WHITE + "Durability text: "
                + (ConfigManager.isMoreHudArmorStatusDuraRight()
                ? EnumChatFormatting.AQUA + "Right" : EnumChatFormatting.AQUA + "Left");
    }

    private static String armorStatusHeldPlacementLabel() {
        boolean vertical = MoreHudArmorStatusFeature.LayoutMode.fromIndex(
                ConfigManager.getMoreHudArmorStatusLayout()).isVertical();
        switch (ConfigManager.getMoreHudArmorStatusHeldPlacement()) {
            case ConfigManager.MORE_HUD_ARMOR_HELD_ABOVE:
                return EnumChatFormatting.WHITE + "Held item: "
                        + (vertical ? EnumChatFormatting.GREEN + "Above" : EnumChatFormatting.GREEN + "Left");
            case ConfigManager.MORE_HUD_ARMOR_HELD_OFF:
                return EnumChatFormatting.WHITE + "Held item: " + EnumChatFormatting.RED + "OFF";
            case ConfigManager.MORE_HUD_ARMOR_HELD_DOWN:
            default:
                return EnumChatFormatting.WHITE + "Held item: "
                        + (vertical ? EnumChatFormatting.GREEN + "Down" : EnumChatFormatting.GREEN + "Right");
        }
    }

    private static String armorStatusHeldPlacementShortLabel() {
        boolean vertical = MoreHudArmorStatusFeature.LayoutMode.fromIndex(
                ConfigManager.getMoreHudArmorStatusLayout()).isVertical();
        switch (ConfigManager.getMoreHudArmorStatusHeldPlacement()) {
            case ConfigManager.MORE_HUD_ARMOR_HELD_ABOVE:
                return vertical ? "A" : "L";
            case ConfigManager.MORE_HUD_ARMOR_HELD_OFF:
                return "";
            case ConfigManager.MORE_HUD_ARMOR_HELD_DOWN:
            default:
                return vertical ? "D" : "R";
        }
    }

    private void initKillEffectsPanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H,
                row("Kill effects HUD", ConfigManager.isMoreHudKillEffectsEnabled())));
        y += g;
        buttonList.add(new GuiButton(2, cx - w / 2, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudKillEffectsShadow())));
        y += g;
        killEffectsBgOpacityLabelY = y;
        killEffectsBgOpacitySliderX = cx - w / 2;
        killEffectsBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private void initFlamePanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H,
                row("Flaming arrows HUD", ConfigManager.isMoreHudFlameEnabled())));
        y += g;
        // Same source of truth as Effects HUD "Show Flaming Arrows KE" (kept in sync with flameCompact by ConfigManager).
        String layout = ConfigManager.isMoreHudPotionsShowFlamingArrows()
                ? EnumChatFormatting.GREEN + "In Effects HUD"
                : EnumChatFormatting.AQUA + "Separate HUD";
        buttonList.add(new GuiButton(4, cx - w / 2, y, w, BTN_H, EnumChatFormatting.WHITE + "Display: " + layout));
        y += g;
        buttonList.add(new GuiButton(29, cx - w / 2, y, w, BTN_H, flameProcDisplayLabel()));
        y += g;
        buttonList.add(new GuiButton(2, cx - w / 2, y, w, BTN_H, sh("Shadow", ConfigManager.isMoreHudFlameShadow())));
        y += g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g;
        y += 8;
        addBackRow(cx, w, y);
    }

    private void initBlitzKitTimerPanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H,
                row("Blitz kit timer HUD", ConfigManager.isBlitzKitTimerHudEnabled())));
        y += g;
        buttonList.add(new GuiButton(91, cx - w / 2, y, w, BTN_H,
                sh("Shadow", ConfigManager.isBlitzKitTimerHudTextShadow())));
        y += g;
        blitzKitTimerBgOpacitySliderX = cx - w / 2;
        blitzKitTimerBgOpacitySliderY = y;
        y += BG_OPACITY_BLOCK_H_COMBINED + g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private void initIncomingScreenshotPanel(int cx, int w, int g, int y) {
        buttonList.add(new GuiButton(1, cx - w / 2, y, w, BTN_H,
                row("Friend screenshots", ConfigManager.isIncomingScreenshotOverlayEnabled())));
        y += g;
        buttonList.add(new GuiButton(3, cx - w / 2, y, w, BTN_H,
                sh("Text shadow", ConfigManager.isIncomingScreenshotTextShadow())));
        y += g;
        int resetY = y;
        buttonList.add(new GuiButton(90, cx - w / 2, y, w, BTN_H, RESET_POSITION_SCALE));
        setModulePreviewAnchor(cx, resetY);
        y += g + 8;
        addBackRow(cx, w, y);
    }

    private void setModulePreviewAnchor(int cx, int resetButtonY) {
        modulePreviewAnchorX = cx;
        int backTop = GuiHeader.backButtonY(height, BTN_H);
        modulePreviewAnchorY = (resetButtonY + BTN_H + backTop) / 2;
    }

    private static String row(String name, boolean on) {
        return name + ": " + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private static String sh(String name, boolean on) {
        return name + ": " + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String cpsModeLabel() {
        int m = ConfigManager.getMoreHudCpsMode();
        String s = m == 0 ? "LMB" : m == 1 ? "RMB" : "LMB | RMB";
        return "CPS source: " + EnumChatFormatting.YELLOW + s;
    }

    /**
     * {@code moreHudPotionsEffectColors} true = per-effect vanilla palette ("Custom colors" in UI); false = solid
     * {@link ConfigManager#getMoreHudPotionsColor()} / gray duration lines ("Vanilla colors" = uniform white-style text).
     */
    private static String potionsEffectTextsModeLabel() {
        if (ConfigManager.isMoreHudPotionsEffectColors()) {
            return EnumChatFormatting.WHITE + "Effect texts: " + EnumChatFormatting.GREEN + "Per-effect colors";
        }
        return EnumChatFormatting.WHITE + "Effect texts: " + EnumChatFormatting.AQUA + "Uniform color";
    }

    /** Click cycles {@link ConfigManager#isMoreHudPotionsDecimalEffectTimer()}: detailed tenths vs vanilla {@code M:SS}. */
    private static String potionsEffectsTimerStyleLabel() {
        if (ConfigManager.isMoreHudPotionsDecimalEffectTimer()) {
            return EnumChatFormatting.WHITE + "Effects timer: " + EnumChatFormatting.GREEN + "Detailed (19.9)";
        }
        return EnumChatFormatting.WHITE + "Effects timer: " + EnumChatFormatting.AQUA + "Vanilla (0:20)";
    }

    private static String flameProcDisplayLabel() {
        if (ConfigManager.isMoreHudFlameTimerBar()) {
            return "Timer: " + EnumChatFormatting.AQUA + "Bar";
        }
        return "Timer: " + EnumChatFormatting.GREEN + "Seconds";
    }

    private static String panelDisplayName(Panel panel) {
        if (panel == Panel.POTIONS) {
            return "Effects HUD";
        }
        if (panel == Panel.PING) {
            return "Ping";
        }
        if (panel == Panel.ARROWS) {
            return "Arrows count";
        }
        if (panel == Panel.ARMOR_STATUS) {
            return "Armor status";
        }
        if (panel == Panel.COORDS) {
            return "Coords HUD";
        }
        if (panel == Panel.KILL_EFFECTS) {
            return "Kill effects";
        }
        if (panel == Panel.FLAME) {
            return "Flaming Arrows";
        }
        if (panel == Panel.BLITZ_KIT_TIMER) {
            return "Blitz kit timer";
        }
        if (panel == Panel.INCOMING_SCREENSHOT) {
            return "Friend screenshots";
        }
        return panel.name();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        boolean slimHeader = panel == Panel.KILL_EFFECTS || panel == Panel.FLAME || panel == Panel.POTIONS
                || panel == Panel.FPS || panel == Panel.CPS || panel == Panel.PING || panel == Panel.ARROWS
                || panel == Panel.ARMOR_STATUS || panel == Panel.COORDS || panel == Panel.BLITZ_KIT_TIMER
                || panel == Panel.INCOMING_SCREENSHOT;
        String headerCore = slimHeader
                ? "HUD Editor - " + panelDisplayName(panel)
                : "More HUD - " + panelDisplayName(panel);
        GuiHeader.drawYellowBoldTitleCentered(fontRendererObj, headerCore, width, GuiHeader.STANDARD_TITLE_Y);
        if (!slimHeader) {
            String sub = EnumChatFormatting.GRAY + "Right-click a row in the HUD Editor to open this screen. "
                    + EnumChatFormatting.DARK_GRAY + "Back returns to the HUD Editor.";
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, sub, width, GuiHeader.STANDARD_SUBTITLE_Y, 0xAAAAAA);
        }
        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerButton(mc, mouseX, mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (panel == Panel.FPS) {
            drawBgOpacityCombinedRow(fpsBgOpacitySliderX, fpsBgOpacitySliderY,
                    ConfigManager.getMoreHudFpsBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
            if (fpsCustomLabelField != null) {
                fpsCustomLabelField.drawTextBox();
            }
        }
        if (panel == Panel.CPS) {
            drawBgOpacityCombinedRow(cpsBgOpacitySliderX, cpsBgOpacitySliderY,
                    ConfigManager.getMoreHudCpsBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
        }
        if (panel == Panel.PING) {
            drawBgOpacityCombinedRow(pingBgOpacitySliderX, pingBgOpacitySliderY,
                    ConfigManager.getMoreHudPingBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
            if (pingCustomLabelField != null) {
                pingCustomLabelField.drawTextBox();
            }
        }
        if (panel == Panel.KILL_EFFECTS) {
            drawBgOpacityCombinedRow(killEffectsBgOpacitySliderX, killEffectsBgOpacitySliderY,
                    ConfigManager.getMoreHudKillEffectsBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
        }
        if (panel == Panel.POTIONS) {
            drawBgOpacityCombinedRow(potionsBgOpacitySliderX, potionsBgOpacitySliderY,
                    ConfigManager.getMoreHudPotionsBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
        }
        if (panel == Panel.ARROWS) {
            drawBgOpacityCombinedRow(arrowsBgOpacitySliderX, arrowsBgOpacitySliderY,
                    ConfigManager.getMoreHudArrowsBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
        }
        if (panel == Panel.ARMOR_STATUS) {
            drawBgOpacityCombinedRow(armorStatusBgOpacitySliderX, armorStatusBgOpacitySliderY,
                    ConfigManager.getMoreHudArmorStatusBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
        }
        if (panel == Panel.COORDS) {
            drawBgOpacityCombinedRow(coordsBgOpacitySliderX, coordsBgOpacitySliderY,
                    ConfigManager.getMoreHudCoordsBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
            if (coordsResetButtonY > 0 && System.currentTimeMillis() < coordsResetAckUntilMs) {
                String ack = EnumChatFormatting.GRAY + "Position & scale reset";
                int aw = fontRendererObj.getStringWidth(ack);
                fontRendererObj.drawString(ack, width / 2 - aw / 2, coordsResetButtonY + BTN_H + 4, 0xAAAAAA);
            }
        }
        if (panel == Panel.BLITZ_KIT_TIMER) {
            drawBgOpacityCombinedRow(blitzKitTimerBgOpacitySliderX, blitzKitTimerBgOpacitySliderY,
                    ConfigManager.getBlitzKitTimerHudBg(), HudSliderStyle.HUD_OPACITY_FILL_RGB);
        }
        drawModulePreview();
        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerOverlay(mc, mouseX, mouseY, partialTicks);
        }
        GuiHeader.redrawBackBarButtonsOnTop(mc, buttonList, mouseX, mouseY, height, BTN_H);
    }

    private void drawModulePreview() {
        if (modulePreviewAnchorY <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        int contentTop = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj);
        int backTop = GuiHeader.backButtonY(height, BTN_H);
        int previewCenterX = modulePreviewAnchorX > 0 ? modulePreviewAnchorX : width / 2;
        int previewCenterY = modulePreviewAnchorY;
        int zonePad = 8;
        int availableLeft = 8;
        int availableRight = width - 8;
        int availableBottom = Math.max(contentTop + 8, backTop - 8);
        int[] bounds = new int[4];
        for (int pass = 0; pass < 3; pass++) {
            MoreHudFeature.settingsGalleryPreviewBounds(mc, panel, previewCenterX, previewCenterY, bounds);
            int leftOverflow = availableLeft - bounds[0];
            int rightOverflow = bounds[0] + bounds[2] - availableRight;
            int topOverflow = contentTop - bounds[1];
            int bottomOverflow = bounds[1] + bounds[3] - availableBottom;
            if (leftOverflow <= 0 && rightOverflow <= 0 && topOverflow <= 0 && bottomOverflow <= 0) {
                break;
            }
            if (leftOverflow > 0) {
                previewCenterX += leftOverflow;
            }
            if (rightOverflow > 0) {
                previewCenterX -= rightOverflow;
            }
            if (topOverflow > 0) {
                previewCenterY += topOverflow;
            }
            if (bottomOverflow > 0) {
                previewCenterY -= bottomOverflow;
            }
        }
        MoreHudFeature.settingsGalleryPreviewBounds(mc, panel, previewCenterX, previewCenterY, bounds);
        int zoneTop = Math.max(contentTop, bounds[1] - zonePad);
        int zoneBottom = Math.min(availableBottom, bounds[1] + bounds[3] + zonePad);
        int zoneLeft = Math.max(0, bounds[0] - zonePad);
        int zoneRight = Math.min(width, bounds[0] + bounds[2] + zonePad);
        if (zoneRight <= zoneLeft) {
            zoneLeft = width / 2 - BTN_W / 2;
            zoneRight = width / 2 + BTN_W / 2;
        }
        if (zoneBottom <= zoneTop) {
            zoneTop = Math.max(contentTop, previewCenterY - 24);
            zoneBottom = Math.min(availableBottom, previewCenterY + 24);
        }
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneBottom, 0x33000000);
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneTop + 1, 0x66AAAAAA);
        Gui.drawRect(zoneLeft, zoneBottom - 1, zoneRight, zoneBottom, 0x66AAAAAA);
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        HudEditorModuleTransition.drawPreview(mc, panel, previewCenterX, previewCenterY);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private void drawBgOpacityCombinedRow(int sliderX, int sliderY, int alpha, int sliderColor) {
        drawBgOpacityCombinedRow(sliderX, sliderY, alpha, sliderColor, panelToInsetModule(panel), BTN_W);
    }

    private void drawBgOpacityCombinedRow(int sliderX, int sliderY, int alpha, int sliderColor,
            HudOpacitySliderBoxInsets.Module insetModule, int rowWidth) {
        if (bgOpacityRow != null) {
            bgOpacityRow.setInsetModule(insetModule);
            bgOpacityRow.setBounds(sliderX, sliderY, rowWidth, FLAME_BG_SLIDER_H);
            bgOpacityRow.drawSlider(fontRendererObj, "Background Opacity: " + alpha);
            return;
        }
        HudOpacitySliderBoxInsets.drawValueBar(fontRendererObj, insetModule, sliderX, sliderY, rowWidth, FLAME_BG_SLIDER_H,
                alpha, sliderColor, "Background Opacity: " + alpha);
    }

    private void drawBgOpacityRow(String opLabel, int labelY, int sliderX, int sliderY, int alpha, int sliderColor) {
        int lw = fontRendererObj.getStringWidth(opLabel);
        fontRendererObj.drawString(opLabel, width / 2 - lw / 2, labelY, 0xCCCCCC);
        HudOpacitySliderBoxInsets.drawValueBar(fontRendererObj, panelToInsetModule(panel), sliderX, sliderY,
                FLAME_BG_SLIDER_W, FLAME_BG_SLIDER_H, alpha, sliderColor, "Opacity: " + alpha);
    }

    private static HudOpacitySliderBoxInsets.Module panelToInsetModule(Panel panel) {
        // The /hudopacitybox tuning is for live HUD backgrounds, not for settings-screen slider chrome.
        return HudOpacitySliderBoxInsets.Module.DEFAULT;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b == null) {
            return;
        }
        if (b.id == BTN_BACK) {
            HudEditorModuleTransition.clear();
            GomodMenuTransitions.returnToParentOrMainMenu(mc, parent);
            return;
        }

        switch (panel) {
            case FPS:
                handleFps(b.id);
                break;
            case CPS:
                handleCps(b.id);
                break;
            case PING:
                handlePing(b.id);
                break;
            case POTIONS:
                handlePotions(b.id);
                break;
            case ARROWS:
                handleArrows(b.id);
                break;
            case ARMOR_STATUS:
                handleArmorStatus(b.id);
                break;
            case KILL_EFFECTS:
                handleKillEffects(b.id);
                break;
            case FLAME:
                handleFlame(b.id);
                break;
            case BLITZ_KIT_TIMER:
                handleBlitzKitTimer(b.id);
                break;
            case INCOMING_SCREENSHOT:
                handleIncomingScreenshot(b.id);
                break;
            case COORDS:
                handleCoords(b.id);
                break;
            default:
                break;
        }
        initGui();
    }

    private void handleCoords(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudCoordsEnabled(!ConfigManager.isMoreHudCoordsEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudCoordsShadow(!ConfigManager.isMoreHudCoordsShadow());
                break;
            case 3:
                ConfigManager.cycleMoreHudCoordsLayout();
                ensureDirectionForCompassLayout();
                break;
            case 4:
                if (!ConfigManager.isMoreHudCoordsCompassOnly()) {
                    ConfigManager.setMoreHudCoordsBiome(!ConfigManager.isMoreHudCoordsBiome());
                }
                break;
            case 5:
                if (ConfigManager.isMoreHudCoordsVertical()) {
                    ConfigManager.setMoreHudCoordsDirection(!ConfigManager.isMoreHudCoordsDirection());
                }
                break;
            case 8:
                ConfigManager.setMoreHudCoordsShowDuringF3(!ConfigManager.isMoreHudCoordsShowDuringF3());
                break;
            case 6:
                if (!ConfigManager.isMoreHudCoordsCompassOnly()) {
                    int prefixStyles = MoreHudCoordsFeature.PrefixStyle.values().length;
                    ConfigManager.setMoreHudCoordsPrefixStyle(
                            (ConfigManager.getMoreHudCoordsPrefixStyle() + 1) % prefixStyles);
                }
                break;
            case 7:
                if (!ConfigManager.isMoreHudCoordsCompassOnly()) {
                    ConfigManager.setMoreHudCoordsDecimals((ConfigManager.getMoreHudCoordsDecimals() + 1) % 5);
                }
                break;
            case 14:
                mc.displayGuiScreen(new MoreHudCoordsColorsGUI(this));
                return;
            case 90:
                ConfigManager.resetMoreHudCoordsPositionOnly();
                coordsResetAckUntilMs = System.currentTimeMillis() + 2500L;
                break;
            default:
                break;
        }
    }

    private void handleIncomingScreenshot(int id) {
        switch (id) {
            case 1:
                ConfigManager.setIncomingScreenshotOverlayEnabled(!ConfigManager.isIncomingScreenshotOverlayEnabled());
                break;
            case 3:
                ConfigManager.setIncomingScreenshotTextShadow(!ConfigManager.isIncomingScreenshotTextShadow());
                break;
            case 90:
                ConfigManager.resetIncomingScreenshotHud();
                break;
            default:
                break;
        }
    }

    private void handleBlitzKitTimer(int id) {
        switch (id) {
            case 1:
                ConfigManager.setBlitzKitTimerHudEnabled(!ConfigManager.isBlitzKitTimerHudEnabled());
                break;
            case 91:
                ConfigManager.setBlitzKitTimerHudTextShadow(!ConfigManager.isBlitzKitTimerHudTextShadow());
                break;
            case 90:
                ConfigManager.resetBlitzKitTimerHudLayout();
                break;
            default:
                break;
        }
    }

    private void handleKillEffects(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudKillEffectsEnabled(!ConfigManager.isMoreHudKillEffectsEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudKillEffectsShadow(!ConfigManager.isMoreHudKillEffectsShadow());
                break;
            case 90:
                ConfigManager.resetMoreHudKillEffectsLayout();
                break;
            default:
                break;
        }
    }

    private void handleFps(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudFpsEnabled(!ConfigManager.isMoreHudFpsEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudFpsShadow(!ConfigManager.isMoreHudFpsShadow());
                break;
            case BTN_ID_FPS_PRESET_LABEL: {
                commitFpsCustomLabelField();
                ConfigManager.setMoreHudFpsDisplayStyle(
                        nextFpsPresetDisplayStyle(ConfigManager.getMoreHudFpsDisplayStyle()));
                break;
            }
            case BTN_ID_FPS_CUSTOM_LABEL:
                commitFpsCustomLabelField();
                if (ConfigManager.getMoreHudFpsDisplayStyle() == FPS_DISPLAY_STYLE_CUSTOM) {
                    ConfigManager.setMoreHudFpsDisplayStyle(fpsLastPresetStyle);
                    fpsCustomFieldFocusOnInit = false;
                } else {
                    fpsLastPresetStyle = ConfigManager.getMoreHudFpsDisplayStyle();
                    fpsCustomFieldFocusOnInit = true;
                    ConfigManager.setMoreHudFpsDisplayStyle(FPS_DISPLAY_STYLE_CUSTOM);
                }
                break;
            case 14:
                commitFpsCustomLabelField();
                mc.displayGuiScreen(new MoreHudFpsColorsGUI(this));
                return;
            case 90:
                ConfigManager.resetMoreHudFpsPositionOnly();
                break;
            default:
                break;
        }
    }

    private void handleCps(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudCpsEnabled(!ConfigManager.isMoreHudCpsEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudCpsShadow(!ConfigManager.isMoreHudCpsShadow());
                break;
            case 4:
                ConfigManager.setMoreHudCpsMode((ConfigManager.getMoreHudCpsMode() + 1) % 3);
                break;
            case 5:
                ConfigManager.setMoreHudCpsSquareBrackets(!ConfigManager.isMoreHudCpsSquareBrackets());
                break;
            case 14:
                mc.displayGuiScreen(new MoreHudCpsColorsGUI(this));
                return;
            case 90:
                ConfigManager.resetMoreHudCpsPositionOnly();
                break;
            default:
                break;
        }
    }

    private void handlePing(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudPingEnabled(!ConfigManager.isMoreHudPingEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudPingShadow(!ConfigManager.isMoreHudPingShadow());
                break;
            case BTN_ID_PING_PRESET_LABEL: {
                commitPingCustomLabelField();
                int style = ConfigManager.getMoreHudPingDisplayStyle();
                if (style == PING_DISPLAY_STYLE_CUSTOM) {
                    style = 0;
                } else {
                    style = (style + 1) % 3;
                }
                ConfigManager.setMoreHudPingDisplayStyle(style);
                break;
            }
            case BTN_ID_PING_CUSTOM_LABEL:
                commitPingCustomLabelField();
                if (ConfigManager.getMoreHudPingDisplayStyle() == PING_DISPLAY_STYLE_CUSTOM) {
                    ConfigManager.setMoreHudPingDisplayStyle(pingLastPresetStyle);
                    pingCustomFieldFocusOnInit = false;
                } else {
                    pingLastPresetStyle = ConfigManager.getMoreHudPingDisplayStyle();
                    pingCustomFieldFocusOnInit = true;
                    ConfigManager.setMoreHudPingDisplayStyle(PING_DISPLAY_STYLE_CUSTOM);
                }
                break;
            case 14:
                commitPingCustomLabelField();
                mc.displayGuiScreen(new MoreHudPingColorsGUI(this));
                return;
            case 90:
                ConfigManager.resetMoreHudPingPositionOnly();
                break;
            default:
                break;
        }
    }

    private void handlePotions(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudPotionsEnabled(!ConfigManager.isMoreHudPotionsEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudPotionsShadow(!ConfigManager.isMoreHudPotionsShadow());
                break;
            case 4:
                ConfigManager.setMoreHudPotionsEffectColors(!ConfigManager.isMoreHudPotionsEffectColors());
                break;
            case 92:
                ConfigManager.setMoreHudPotionsShowFlamingArrows(!ConfigManager.isMoreHudPotionsShowFlamingArrows());
                break;
            case 93:
                ConfigManager.setMoreHudPotionsDecimalEffectTimer(!ConfigManager.isMoreHudPotionsDecimalEffectTimer());
                break;
            case 14:
                mc.displayGuiScreen(new MoreHudPotionsColorsGUI(this));
                return;
            case 90:
                ConfigManager.resetMoreHudPotionsLayout();
                break;
            default:
                break;
        }
    }

    private void handleArrows(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudArrowsEnabled(!ConfigManager.isMoreHudArrowsEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudArrowsShadow(!ConfigManager.isMoreHudArrowsShadow());
                break;
            case 14:
                mc.displayGuiScreen(new MoreHudArrowsColorsGUI(this));
                return;
            case 90:
                ConfigManager.resetMoreHudArrowsPositionOnly();
                break;
            default:
                break;
        }
    }

    private void handleArmorStatus(int id) {
        switch (id) {
            case 1:
                ConfigManager.setMoreHudArmorStatusEnabled(!ConfigManager.isMoreHudArmorStatusEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudArmorStatusShadow(!ConfigManager.isMoreHudArmorStatusShadow());
                break;
            case 36:
                ConfigManager.cycleMoreHudArmorStatusLayout();
                break;
            case 3: {
                MoreHudArmorStatusFeature.DurabilityMode next = MoreHudArmorStatusFeature.DurabilityMode.next(
                        MoreHudArmorStatusFeature.DurabilityMode.fromIndex(ConfigManager.getMoreHudArmorStatusDuraMode()));
                ConfigManager.setMoreHudArmorStatusDuraMode(next.getIndex());
                break;
            }
            case 4:
                ConfigManager.setMoreHudArmorStatusDurabilityBar(!ConfigManager.isMoreHudArmorStatusDurabilityBar());
                break;
            case 5:
                ConfigManager.setMoreHudArmorStatusDuraRight(!ConfigManager.isMoreHudArmorStatusDuraRight());
                break;
            case 14:
                mc.displayGuiScreen(new MoreHudArmorStatusColorsGUI(this));
                return;
            case 30:
                ConfigManager.setMoreHudArmorStatusShowHelmet(!ConfigManager.isMoreHudArmorStatusShowHelmet());
                break;
            case 31:
                ConfigManager.setMoreHudArmorStatusShowChest(!ConfigManager.isMoreHudArmorStatusShowChest());
                break;
            case 32:
                ConfigManager.setMoreHudArmorStatusShowLegs(!ConfigManager.isMoreHudArmorStatusShowLegs());
                break;
            case 33:
                ConfigManager.setMoreHudArmorStatusShowBoots(!ConfigManager.isMoreHudArmorStatusShowBoots());
                break;
            case 34:
                ConfigManager.cycleMoreHudArmorStatusHeldPlacement();
                break;
            case 90:
                ConfigManager.resetMoreHudArmorStatusPositionOnly();
                break;
            default:
                break;
        }
    }

    private void handleFlame(int id) {
        switch (id) {
            case 90:
                ConfigManager.resetMoreHudFlameLayout();
                break;
            case 1:
                ConfigManager.setMoreHudFlameEnabled(!ConfigManager.isMoreHudFlameEnabled());
                break;
            case 2:
                ConfigManager.setMoreHudFlameShadow(!ConfigManager.isMoreHudFlameShadow());
                break;
            case 4:
                ConfigManager.setMoreHudFlameCompact(!ConfigManager.isMoreHudFlameCompact());
                break;
            case 29:
                ConfigManager.setMoreHudFlameTimerBar(!ConfigManager.isMoreHudFlameTimerBar());
                break;
            default:
                break;
        }
    }

    private static boolean isInOpacitySlider(int mouseX, int mouseY, int sliderX, int sliderY, int sliderW) {
        return mouseX >= sliderX && mouseX <= sliderX + sliderW
                && mouseY >= sliderY && mouseY <= sliderY + FLAME_BG_SLIDER_H;
    }

    private void updateRowBgOpacityFromMouse(int mouseX, int sliderX, int sliderW, IntSupplier getter, IntConsumer setter) {
        HudOpacitySliderBoxInsets.Module insetModule = panelToInsetModule(panel);
        int newAlpha = HudOpacitySliderBoxInsets.opacityFromMouseX(mouseX, sliderX, sliderW, insetModule);
        if (newAlpha != getter.getAsInt()) {
            setter.accept(newAlpha);
        }
    }

    private void updatePotionsBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, potionsBgOpacitySliderX, BTN_W,
                ConfigManager::getMoreHudPotionsBg, ConfigManager::setMoreHudPotionsBg);
    }

    private void updateFpsBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, fpsBgOpacitySliderX, BTN_W,
                ConfigManager::getMoreHudFpsBg, ConfigManager::setMoreHudFpsBg);
    }

    private void updateCpsBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, cpsBgOpacitySliderX, BTN_W,
                ConfigManager::getMoreHudCpsBg, ConfigManager::setMoreHudCpsBg);
    }

    private void updatePingBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, pingBgOpacitySliderX, BTN_W,
                ConfigManager::getMoreHudPingBg, ConfigManager::setMoreHudPingBg);
    }

    private void updateKillEffectsBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, killEffectsBgOpacitySliderX, BTN_W,
                ConfigManager::getMoreHudKillEffectsBg, ConfigManager::setMoreHudKillEffectsBg);
    }

    private void updateArrowsBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, arrowsBgOpacitySliderX, BTN_W,
                ConfigManager::getMoreHudArrowsBg, ConfigManager::setMoreHudArrowsBg);
    }

    private void updateCoordsBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, coordsBgOpacitySliderX, BTN_W,
                ConfigManager::getMoreHudCoordsBg, ConfigManager::setMoreHudCoordsBg);
    }

    private void updateBlitzKitTimerBgOpacityFromMouse(int mouseX) {
        updateRowBgOpacityFromMouse(mouseX, blitzKitTimerBgOpacitySliderX, BTN_W,
                ConfigManager::getBlitzKitTimerHudBg, ConfigManager::setBlitzKitTimerHudBg);
    }

    /**
     * Vanilla {@link GuiScreen#mouseClicked} invokes {@link #actionPerformed} for every button where
     * {@link GuiButton#mousePressed} is true. {@link #actionPerformed} switches panels and calls {@link #initGui()},
     * replacing {@link #buttonList} while the loop still runs — so the same click can activate another button on the
     * new panel at the same coordinates. Handle at most one button per left-click (same idea as topmost-hit).
     */
    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (panel == Panel.FPS && fpsCustomLabelField != null && fpsCustomLabelField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                validateFpsCustomLabelFieldOnEnter();
                return;
            }
            if (fpsCustomLabelField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        if (panel == Panel.PING && pingCustomLabelField != null && pingCustomLabelField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                validatePingCustomLabelFieldOnEnter();
                return;
            }
            if (pingCustomLabelField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        if (panel == Panel.FPS && fpsCustomLabelField != null) {
            fpsCustomLabelField.updateCursorCounter();
        }
        if (panel == Panel.PING && pingCustomLabelField != null) {
            pingCustomLabelField.updateCursorCounter();
        }
        super.updateScreen();
    }

    @Override
    public void onGuiClosed() {
        if (panel == Panel.FPS) {
            commitFpsCustomLabelField();
        }
        if (panel == Panel.PING) {
            commitPingCustomLabelField();
        }
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && panel == Panel.FPS && fpsCustomLabelField != null) {
            fpsCustomLabelField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (mouseButton == 0 && panel == Panel.PING && pingCustomLabelField != null) {
            pingCustomLabelField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (mouseButton == 0 && bgOpacityRow != null && bgOpacityRow.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
            return;
        }
        if (mouseButton == 0 && panel == Panel.ARMOR_STATUS && armorSlotGapSlider != null
                && armorSlotGapSlider.mousePressed(mc, mouseX, mouseY)) {
            pendingMouseReleaseButton = armorSlotGapSlider;
            applyArmorSlotGapFromSlider();
            mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
            return;
        }
        if (mouseButton == 0 && panel == Panel.ARMOR_STATUS && armorTextScaleSlider != null
                && armorTextScaleSlider.mousePressed(mc, mouseX, mouseY)) {
            pendingMouseReleaseButton = armorTextScaleSlider;
            applyArmorTextScaleFromSlider();
            mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
            return;
        }
        if (mouseButton == 0) {
            for (int i = buttonList.size() - 1; i >= 0; --i) {
                GuiButton guibutton = buttonList.get(i);
                if (guibutton.mousePressed(mc, mouseX, mouseY)) {
                    pendingMouseReleaseButton = guibutton;
                    mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
                    actionPerformed(guibutton);
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0 && bgOpacityRow != null) {
            bgOpacityRow.mouseClickMove(mouseX, mouseY);
        }
        if (clickedMouseButton == 0 && pendingMouseReleaseButton instanceof ArmorSlotGapSlider) {
            ((ArmorSlotGapSlider) pendingMouseReleaseButton).dragTo(mouseX);
            applyArmorSlotGapFromSlider();
            return;
        }
        if (clickedMouseButton == 0 && pendingMouseReleaseButton instanceof ArmorTextScaleSlider) {
            ((ArmorTextScaleSlider) pendingMouseReleaseButton).dragTo(mouseX);
            applyArmorTextScaleFromSlider();
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) {
            potionsBgOpacityDragging = false;
            fpsBgOpacityDragging = false;
            cpsBgOpacityDragging = false;
            pingBgOpacityDragging = false;
            killEffectsBgOpacityDragging = false;
            arrowsBgOpacityDragging = false;
            coordsBgOpacityDragging = false;
            blitzKitTimerBgOpacityDragging = false;
            if (bgOpacityRow != null) {
                bgOpacityRow.mouseReleased(mouseX, mouseY);
            }
        }
        if (pendingMouseReleaseButton instanceof ArmorSlotGapSlider && state == 0) {
            pendingMouseReleaseButton.mouseReleased(mouseX, mouseY);
            applyArmorSlotGapFromSlider();
            pendingMouseReleaseButton = null;
            return;
        }
        if (pendingMouseReleaseButton instanceof ArmorTextScaleSlider && state == 0) {
            pendingMouseReleaseButton.mouseReleased(mouseX, mouseY);
            applyArmorTextScaleFromSlider();
            pendingMouseReleaseButton = null;
            return;
        }
        if (pendingMouseReleaseButton != null && state == 0) {
            pendingMouseReleaseButton.mouseReleased(mouseX, mouseY);
            pendingMouseReleaseButton = null;
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (panel != Panel.ARMOR_STATUS) {
            return;
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        if (armorSlotGapSlider != null && armorSlotGapSlider.isMouseOver(mc)) {
            int next = ConfigManager.getMoreHudArmorStatusArmorSlotGap() + (wheel > 0 ? 1 : -1);
            ConfigManager.setMoreHudArmorStatusArmorSlotGap(next);
            armorSlotGapSlider.syncFromConfig();
            mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 0.35F));
            return;
        }
        if (armorTextScaleSlider != null && armorTextScaleSlider.isMouseOver(mc)) {
            float next = ConfigManager.getMoreHudArmorStatusTextScale() + (wheel > 0 ? 0.05f : -0.05f);
            ConfigManager.setMoreHudArmorStatusTextScale(next);
            armorTextScaleSlider.syncFromConfig();
            mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 0.35F));
        }
    }

    private void applyArmorSlotGapFromSlider() {
        if (armorSlotGapSlider == null) {
            return;
        }
        ConfigManager.setMoreHudArmorStatusArmorSlotGap(armorSlotGapSlider.getIntValue());
    }

    private void applyArmorTextScaleFromSlider() {
        if (armorTextScaleSlider == null) {
            return;
        }
        ConfigManager.setMoreHudArmorStatusTextScale(armorTextScaleSlider.getFloatValue());
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Armor piece toggle: icon only; green tint when on, red when off. */
    private static final class ArmorSlotIconButton extends GuiButton {
        private static final Gui BUTTON_TEXTURE_DRAW = new Gui() {};
        private static final ResourceLocation BUTTON_WIDGETS =
                new ResourceLocation("textures/gui/widgets.png");
        private static final int ICON_SIZE = 16;
        private static final int FACE_INSET = 2;
        /** ARGB overlays on the inset face (not the vanilla chrome border). */
        private static final int OVERLAY_ON = 0x5030AA40;
        private static final int OVERLAY_OFF = 0x50AA3030;
        private final ItemStack iconStack;
        private final BooleanSupplier slotOn;

        ArmorSlotIconButton(int id, int x, int y, int width, int height, ItemStack iconStack,
                BooleanSupplier slotOn) {
            super(id, x, y, width, height, "");
            this.iconStack = iconStack;
            this.slotOn = slotOn;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            displayString = "";
            int x = xPosition;
            int y = yPosition;
            int w = width;
            int h = height;
            hovered = enabled && mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;

            HudGlState.restoreForOverlayText();
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableLighting();
            if (mc != null && mc.getTextureManager() != null) {
                mc.getTextureManager().bindTexture(BUTTON_WIDGETS);
            }
            int hoverState = !enabled ? 0 : (hovered ? 2 : 1);
            int texV = 46 + hoverState * 20;
            float alpha = enabled ? 1.0f : 0.5f;
            GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);
            int half = w / 2;
            int rightW = w - half;
            BUTTON_TEXTURE_DRAW.drawTexturedModalRect(x, y, 0, texV, half, h);
            BUTTON_TEXTURE_DRAW.drawTexturedModalRect(x + half, y, 200 - rightW, texV, rightW, h);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            int insetL = x + FACE_INSET;
            int insetT = y + FACE_INSET;
            int insetR = x + w - FACE_INSET;
            int insetB = y + h - FACE_INSET;
            if (insetR > insetL && insetB > insetT) {
                Gui.drawRect(insetL, insetT, insetR, insetB,
                        slotOn.getAsBoolean() ? OVERLAY_ON : OVERLAY_OFF);
            }

            if (iconStack == null || mc == null || mc.getRenderItem() == null) {
                return;
            }
            int iconX = x + (w - ICON_SIZE) / 2;
            int iconY = y + (h - ICON_SIZE) / 2;
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            try {
                mc.getRenderItem().renderItemIntoGUI(iconStack, iconX, iconY);
            } catch (Throwable ignored) {
            }
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
        }
    }

    /** Held-item placement selector: sword icon cycles above/down/off (or left/right/off in horizontal layouts). */
    private static final class ArmorHeldPlacementButton extends GuiButton {
        private static final Gui BUTTON_TEXTURE_DRAW = new Gui() {};
        private static final ResourceLocation BUTTON_WIDGETS =
                new ResourceLocation("textures/gui/widgets.png");
        private static final int ICON_SIZE = 16;
        private static final int FACE_INSET = 2;
        private static final int OVERLAY_ON = 0x5030AA40;
        private static final int OVERLAY_OFF = 0x50AA3030;

        ArmorHeldPlacementButton(int id, int x, int y, int width, int height) {
            super(id, x, y, width, height, "");
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            displayString = "";
            int x = xPosition;
            int y = yPosition;
            int w = width;
            int h = height;
            hovered = enabled && mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;

            HudGlState.restoreForOverlayText();
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableLighting();
            if (mc != null && mc.getTextureManager() != null) {
                mc.getTextureManager().bindTexture(BUTTON_WIDGETS);
            }
            int hoverState = !enabled ? 0 : (hovered ? 2 : 1);
            int texV = 46 + hoverState * 20;
            float alpha = enabled ? 1.0f : 0.5f;
            GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);
            int half = w / 2;
            int rightW = w - half;
            BUTTON_TEXTURE_DRAW.drawTexturedModalRect(x, y, 0, texV, half, h);
            BUTTON_TEXTURE_DRAW.drawTexturedModalRect(x + half, y, 200 - rightW, texV, rightW, h);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            int insetL = x + FACE_INSET;
            int insetT = y + FACE_INSET;
            int insetR = x + w - FACE_INSET;
            int insetB = y + h - FACE_INSET;
            boolean off = ConfigManager.getMoreHudArmorStatusHeldPlacement() == ConfigManager.MORE_HUD_ARMOR_HELD_OFF;
            if (insetR > insetL && insetB > insetT) {
                Gui.drawRect(insetL, insetT, insetR, insetB, off ? OVERLAY_OFF : OVERLAY_ON);
            }

            int iconX = x + (w - ICON_SIZE) / 2;
            int iconY = y + (h - ICON_SIZE) / 2;
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            try {
                mc.getRenderItem().renderItemIntoGUI(new ItemStack(Items.diamond_sword), iconX, iconY);
            } catch (Throwable ignored) {
            }
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();

            String label = armorStatusHeldPlacementShortLabel();
            int labelColor = off ? 0xFFB0B0 : 0xE0E0E0;
            if (!label.isEmpty()) {
                int tx = x + w - mc.fontRendererObj.getStringWidth(label) - 3;
                int ty = y + 2;
                if ("D".equals(label)) {
                    ty = y + h - mc.fontRendererObj.FONT_HEIGHT - 2;
                }
                mc.fontRendererObj.drawStringWithShadow(label, tx, ty, labelColor);
            }
        }
    }

    /** Vanilla-style slider for armor spacing; drag or scroll wheel while hovered. */
    private static final class ArmorSlotGapSlider extends GuiButton {
        private final int minValue;
        private final int maxValue;
        private int currentValue;
        private float sliderValue;
        private boolean dragging;

        ArmorSlotGapSlider(int id, int x, int y, int width, int height, int min, int max, int current) {
            super(id, x, y, width, height, "");
            this.minValue = min;
            this.maxValue = max;
            this.currentValue = clampGap(min, max, current);
            this.sliderValue = toNormalized(this.currentValue);
            updateLabel();
        }

        void syncFromConfig() {
            currentValue = clampGap(minValue, maxValue, ConfigManager.getMoreHudArmorStatusArmorSlotGap());
            sliderValue = toNormalized(currentValue);
            updateLabel();
        }

        int getIntValue() {
            return currentValue;
        }

        boolean isMouseOver(Minecraft mc) {
            if (mc == null) {
                return false;
            }
            ScaledResolution sr = new ScaledResolution(mc);
            int sw = sr.getScaledWidth();
            int sh = sr.getScaledHeight();
            int mx = Mouse.getEventX() * sw / mc.displayWidth;
            int my = sh - Mouse.getEventY() * sh / mc.displayHeight - 1;
            return mx >= xPosition && mx < xPosition + this.width && my >= yPosition && my < yPosition + this.height;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int v = 46;
            int half = this.width / 2;
            drawTexturedModalRect(xPosition, yPosition, 0, v, half, 20);
            drawTexturedModalRect(xPosition + half, yPosition, 200 - half, v, half, 20);
            int knobX = xPosition + (int) (sliderValue * (width - 8));
            drawTexturedModalRect(knobX, yPosition, 0, 66, 4, 20);
            drawTexturedModalRect(knobX + 4, yPosition, 196, 66, 4, 20);
            boolean hovered = mouseX >= xPosition && mouseY >= yPosition && mouseX < xPosition + width
                    && mouseY < yPosition + height;
            int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
            int textWidth = mc.fontRendererObj.getStringWidth(displayString);
            mc.fontRendererObj.drawString(displayString, xPosition + (width - textWidth) / 2,
                    yPosition + (height - 8) / 2, textColor);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!visible || mouseX < xPosition || mouseX > xPosition + width || mouseY < yPosition
                    || mouseY > yPosition + height) {
                return false;
            }
            dragging = true;
            dragTo(mouseX);
            return true;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }

        void dragTo(int mouseX) {
            float normalized = (float) (mouseX - (xPosition + 4)) / (float) Math.max(1, width - 8);
            normalized = Math.max(0.0F, Math.min(1.0F, normalized));
            sliderValue = normalized;
            int range = maxValue - minValue;
            currentValue = minValue + Math.round(normalized * range);
            currentValue = clampGap(minValue, maxValue, currentValue);
            sliderValue = toNormalized(currentValue);
            updateLabel();
        }

        private float toNormalized(int value) {
            int range = maxValue - minValue;
            if (range <= 0) {
                return 0.0F;
            }
            return (value - minValue) / (float) range;
        }

        private static int clampGap(int min, int max, int value) {
            return Math.max(min, Math.min(max, value));
        }

        private void updateLabel() {
            displayString = EnumChatFormatting.WHITE + "Armor spacing: " + EnumChatFormatting.YELLOW + currentValue
                    + " px";
        }
    }

    /** Vanilla-style slider for armor durability text size. */
    private static final class ArmorTextScaleSlider extends GuiButton {
        private final float minValue;
        private final float maxValue;
        private float currentValue;
        private float sliderValue;
        private boolean dragging;

        ArmorTextScaleSlider(int id, int x, int y, int width, int height, float min, float max, float current) {
            super(id, x, y, width, height, "");
            this.minValue = min;
            this.maxValue = max;
            this.currentValue = clampScale(min, max, current);
            this.sliderValue = toNormalized(this.currentValue);
            updateLabel();
        }

        void syncFromConfig() {
            currentValue = clampScale(minValue, maxValue, ConfigManager.getMoreHudArmorStatusTextScale());
            sliderValue = toNormalized(currentValue);
            updateLabel();
        }

        float getFloatValue() {
            return currentValue;
        }

        boolean isMouseOver(Minecraft mc) {
            if (mc == null) {
                return false;
            }
            ScaledResolution sr = new ScaledResolution(mc);
            int sw = sr.getScaledWidth();
            int sh = sr.getScaledHeight();
            int mx = Mouse.getEventX() * sw / mc.displayWidth;
            int my = sh - Mouse.getEventY() * sh / mc.displayHeight - 1;
            return mx >= xPosition && mx < xPosition + this.width && my >= yPosition && my < yPosition + this.height;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int v = 46;
            int half = this.width / 2;
            drawTexturedModalRect(xPosition, yPosition, 0, v, half, 20);
            drawTexturedModalRect(xPosition + half, yPosition, 200 - half, v, half, 20);
            int knobX = xPosition + (int) (sliderValue * (width - 8));
            drawTexturedModalRect(knobX, yPosition, 0, 66, 4, 20);
            drawTexturedModalRect(knobX + 4, yPosition, 196, 66, 4, 20);
            boolean hovered = mouseX >= xPosition && mouseY >= yPosition && mouseX < xPosition + width
                    && mouseY < yPosition + height;
            int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
            int textWidth = mc.fontRendererObj.getStringWidth(displayString);
            mc.fontRendererObj.drawString(displayString, xPosition + (width - textWidth) / 2,
                    yPosition + (height - 8) / 2, textColor);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!visible || mouseX < xPosition || mouseX > xPosition + width || mouseY < yPosition
                    || mouseY > yPosition + height) {
                return false;
            }
            dragging = true;
            dragTo(mouseX);
            return true;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }

        void dragTo(int mouseX) {
            float normalized = (float) (mouseX - (xPosition + 4)) / (float) Math.max(1, width - 8);
            normalized = Math.max(0.0F, Math.min(1.0F, normalized));
            sliderValue = normalized;
            currentValue = minValue + normalized * (maxValue - minValue);
            currentValue = clampScale(minValue, maxValue, currentValue);
            sliderValue = toNormalized(currentValue);
            updateLabel();
        }

        private float toNormalized(float value) {
            float range = maxValue - minValue;
            if (range <= 0.0F) {
                return 0.0F;
            }
            return (value - minValue) / range;
        }

        private static float clampScale(float min, float max, float value) {
            return Math.max(min, Math.min(max, value));
        }

        private void updateLabel() {
            displayString = EnumChatFormatting.WHITE + "Text size: " + EnumChatFormatting.YELLOW
                    + Math.round(currentValue * 100.0f) + "%";
        }
    }
}
