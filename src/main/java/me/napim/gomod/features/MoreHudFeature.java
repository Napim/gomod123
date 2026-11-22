package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import me.napim.gomod.gui.GomodHudEditorGUI;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.gui.HudEditorModuleTransition;
import me.napim.gomod.gui.MoreHudCoordsColorsGUI;
import me.napim.gomod.gui.MoreHudPotionsColorsGUI;
import me.napim.gomod.gui.MoreHudSettingsGUI;
import me.napim.gomod.gui.HudOpacitySliderBoxInsets;
import me.napim.gomod.util.GomodClasspathTextures;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

/**
 * Optional FPS / CPS / ping / effects / arrows stack, anchored from screen center.
 */
public final class MoreHudFeature {

    private static final ResourceLocation INVENTORY_TEXTURE = new ResourceLocation("textures/gui/container/inventory.png");
    private static final ResourceLocation FLAME_KILL_TEXTURE = new ResourceLocation("gomod123", "textures/killeffects/flame.png");
    /**
     * HUD Editor: Speed II — tier label forced in {@link #formatPotionEffectBaseName} (I18n potency keys vary).
     * Flaming Arrows and {@link BlitzStarEffectsHudTracker.Effect#ROBINHOOD} are drawn separately in
     * {@link #drawPotionRows} (editor order: speed → flame → robinhood).
     */
    private static final PotionEffect[] HUD_EDITOR_PREVIEW_EFFECTS = {
            new PotionEffect(Potion.moveSpeed.id, 15 * 20, 1),
    };
    private static final List<PotionEffect> HUD_EDITOR_PREVIEW_POTIONS = Arrays.asList(HUD_EDITOR_PREVIEW_EFFECTS);
    private static final BlitzStarEffectsHudTracker.Effect HUD_EDITOR_PREVIEW_BLITZ_EFFECT =
            BlitzStarEffectsHudTracker.Effect.ROBINHOOD;
    /**
     * Vanilla 1.8.9 status effect name colors by effect ID (1–23). Index 0 unused; unknown IDs fall back to row text color.
     * RGB as 0xRRGGBB (matches in-game).
     */
    private static final int[] POTION_EFFECT_NAME_RGB = new int[] {
            0,
            0x70DEFF, 0x5A6C81, 0xD9C043, 0x4A4219, 0x932423, 0xF82423, 0x430A09, 0x22FF4C,
            0xB35300, 0xFA5CFA, 0x99453A, 0xE49A3A, 0x2E5299, 0x7F8392, 0x1F1F23, 0x1F1FA1,
            0x587653, 0x484D48, 0x4E9331, 0x352A27, 0xF87D23, 0x2552A5, 0xF82423,
    };

    /**
     * Per-potion-ID RGB override for testing (0xRRGGBB). {@code -1} = use table / config as usual.
     * Cleared with {@link #clearMoreHudPotionColorDebugOverrides()} or {@code /morehudpotioncolor clear}.
     */
    private static final int[] POTION_COLOR_DEBUG_OVERRIDE = new int[32];

    static {
        Arrays.fill(POTION_COLOR_DEBUG_OVERRIDE, -1);
    }

    private static final class KillEffectHudState {
        private final String effectCode;
        private final String displayName;
        private final ResourceLocation iconTexture;
        private final int textRgb;
        private final String fullLinePlain;
        private final String timerPlain;
        private final boolean supportsTimerBar;
        private final float timerBarFill;

        private KillEffectHudState(String effectCode, String displayName, ResourceLocation iconTexture, int textRgb,
                                   String fullLinePlain, String timerPlain, boolean supportsTimerBar, float timerBarFill) {
            this.effectCode = effectCode;
            this.displayName = displayName;
            this.iconTexture = iconTexture;
            this.textRgb = textRgb & 0xFFFFFF;
            this.fullLinePlain = fullLinePlain;
            this.timerPlain = timerPlain;
            this.supportsTimerBar = supportsTimerBar;
            this.timerBarFill = Math.max(0f, Math.min(1f, timerBarFill));
        }
    }

    /** @param rgb 0xRRGGBB, or {@code -1} to remove override for that id */
    public static void setMoreHudPotionColorDebugOverride(int potionId, int rgb) {
        if (potionId < 0 || potionId >= POTION_COLOR_DEBUG_OVERRIDE.length) {
            return;
        }
        POTION_COLOR_DEBUG_OVERRIDE[potionId] = rgb;
    }

    public static void clearMoreHudPotionColorDebugOverrides() {
        Arrays.fill(POTION_COLOR_DEBUG_OVERRIDE, -1);
    }

    public static int getMoreHudPotionColorDebugOverride(int potionId) {
        if (potionId < 0 || potionId >= POTION_COLOR_DEBUG_OVERRIDE.length) {
            return -1;
        }
        return POTION_COLOR_DEBUG_OVERRIDE[potionId];
    }

    /** Depleting proc bar — standalone compact Flaming Arrows HUD only, never Effects HUD. */
    private static boolean isKillEffectTimerBarActive(KillEffectHudState state) {
        return state != null && state.supportsTimerBar && ConfigManager.isMoreHudFlameTimerBar();
    }

    private static KillEffectHudState resolveVisibleKillEffectHudState(Minecraft mc, boolean hudEditorPreview) {
        if (hudEditorPreview) {
            return buildPreviewKillEffectHudState();
        }
        if (!BlitzFlameKillEffectTracker.shouldShowMoreHudFlameTimer(ConfigManager.isMoreHudEnabled())) {
            return null;
        }
        String effectCode = "FLAME";
        return new KillEffectHudState(effectCode, "Flaming Arrows", resolveKillEffectIconTexture(effectCode),
                ConfigManager.getMoreHudKillEffectColor(effectCode),
                BlitzFlameKillEffectTracker.formatFlameTimerPlain(),
                BlitzFlameKillEffectTracker.formatFlameSecondsPlain(), true,
                BlitzFlameKillEffectTracker.getProcRemainingFraction());
    }

    private static KillEffectHudState buildPreviewKillEffectHudState() {
        String effectCode = "FLAME";
        return new KillEffectHudState(effectCode, "Flaming Arrows", resolveKillEffectIconTexture(effectCode),
                ConfigManager.getMoreHudKillEffectColor(effectCode),
                BlitzFlameKillEffectTracker.previewFlameTimerPlain(),
                BlitzFlameKillEffectTracker.previewFlameSecondsPlain(), true, 1f);
    }

    private static KillEffectHudState resolveVisibleOtherKillEffectsHudState(Minecraft mc, boolean hudEditorPreview) {
        if (hudEditorPreview) {
            return buildPreviewOtherKillEffectsHudState();
        }
        if (!BlitzFlameKillEffectTracker.shouldShowMoreHudOtherKillEffectTimer(ConfigManager.isMoreHudEnabled())) {
            return null;
        }
        String effectCode = BlitzFlameKillEffectTracker.getActiveOtherKillEffectCode();
        if (effectCode == null) {
            return null;
        }
        String displayName = resolveKillEffectDisplayName(effectCode);
        return new KillEffectHudState(effectCode, displayName, resolveKillEffectIconTexture(effectCode),
                ConfigManager.getMoreHudKillEffectColor(effectCode),
                BlitzFlameKillEffectTracker.formatOtherKillEffectTimerPlain(),
                BlitzFlameKillEffectTracker.formatOtherKillEffectSecondsPlain(), false, 0f);
    }

    private static KillEffectHudState buildPreviewOtherKillEffectsHudState() {
        String effectCode = BlitzFlameKillEffectTracker.previewOtherKillEffectCode();
        return new KillEffectHudState(effectCode, BlitzFlameKillEffectTracker.previewOtherKillEffectDisplayName(),
                resolveKillEffectIconTexture("REGENERATION"),
                ConfigManager.getMoreHudKillEffectColor(effectCode),
                BlitzFlameKillEffectTracker.previewOtherKillEffectTimerPlain(),
                BlitzFlameKillEffectTracker.previewOtherKillEffectSecondsPlain(), false, 0f);
    }

    private static String resolveSupportedKillEffectCode(String display) {
        if (display == null) {
            return null;
        }
        String lower = display.trim().toLowerCase();
        if (lower.contains("flaming arrow")) {
            return "FLAME";
        }
        if (lower.contains("regeneration")) {
            return "REGENERATION";
        }
        if (lower.contains("resistance")) {
            return "RESISTANCE";
        }
        if (lower.equals("speed") || lower.contains(" speed")) {
            return "SPEED";
        }
        return null;
    }

    private static String resolveKillEffectDisplayName(String effectCode) {
        if (effectCode == null) {
            return "Kill effects";
        }
        switch (effectCode) {
            case "SPEED":
                return "Speed";
            case "RESISTANCE":
                return "Resistance";
            case "REGENERATION":
                return "Regeneration";
            case "RAPID_FIRE":
            case "FLAME":
            default:
                return "Flaming Arrows";
        }
    }

    private static PotionEffect findKillEffectPotionEffect(Minecraft mc, String effectCode) {
        if (mc == null || mc.thePlayer == null || effectCode == null) {
            return null;
        }
        Collection<PotionEffect> fx = mc.thePlayer.getActivePotionEffects();
        if (fx == null || fx.isEmpty()) {
            return null;
        }
        int targetId = -1;
        switch (effectCode) {
            case "SPEED":
                targetId = Potion.moveSpeed.id;
                break;
            case "RESISTANCE":
                targetId = Potion.resistance.id;
                break;
            case "REGENERATION":
                targetId = Potion.regeneration.id;
                break;
            default:
                break;
        }
        if (targetId < 0) {
            return null;
        }
        for (PotionEffect eff : fx) {
            if (eff != null && eff.getPotionID() == targetId) {
                return eff;
            }
        }
        return null;
    }

    private static ResourceLocation resolveKillEffectIconTexture(String effectCode) {
        Object icon = KillCounter.getKillEffectIconPrefixForEffect(effectCode);
        ResourceLocation pack = icon instanceof ResourceLocation ? (ResourceLocation) icon : FLAME_KILL_TEXTURE;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            return GomodClasspathTextures.ensureLoaded(mc, pack);
        }
        return pack;
    }

    /** Same atlas region size as survival inventory effect icons. */
    private static final int POTION_ICON_LARGE = 18;
    /** Icon size for Effects HUD color-settings rows. */
    public static final int POTIONS_COLOR_SETTINGS_ICON_SIZE = POTION_ICON_LARGE;
    /** Depleting proc bar (Flaming Arrows timer-as-bar). */
    private static final int FLAME_PROC_BAR_W = 57;
    private static final int FLAME_PROC_BAR_H = 5;
    /** Extra unscaled padding above compact flame row in HUD Editor hit box / bounds. */
    private static final int POTION_BOUNDS_TOP_PAD_UNSCALED = 8;
    /** Vertical gap between effect rows (attached and separated use the same spacing). */
    private static final int POTION_ROW_GAP = 2;
    /** Shared top inset (1px) + {@link #POTION_BG_PAD_TOP} = 3px above row content to background top. */
    private static final int POTION_BG_TOP_INSET = 1;
    private static final int POTION_BG_PAD_TOP = 2;
    /** Padding below row content to background bottom (attached block + each separated row). */
    private static final int POTION_BG_PAD_BOTTOM = 1;
    /** Draw RGB for Effects HUD duration / timer lines (includes alpha). */
    public static int getPotionsDurationDrawRgb() {
        return potionDurationDrawRgb();
    }

    private static int potionDurationDrawRgb() {
        return 0xFF000000 | (ConfigManager.getMoreHudPotionsDurationColor() & 0xFFFFFF);
    }

    public static int getDefaultPotionDurationColor() {
        return 0xFFFFFF;
    }

    public static int getDefaultPotionEffectNameColor(int potionId) {
        return ConfigManager.getDefaultMoreHudPotionsEffectColor(potionId);
    }

    /**
     * Tab latency can still flicker to fake-low values on Hypixel (~1–15ms) in-game. When that happens and we have
     * a recent believable server-status ping, prefer the recent believable value instead of flashing bogus 1ms.
     */
    private static int moreHudPingLastPlausible = -1;
    private static long moreHudPingLastPlausibleWallMs = 0L;
    private static final int MOREHUD_PING_SUSPICIOUS_MAX_MS = 15;
    private static final int MOREHUD_PING_HOLD_MIN_PRIOR_MS = 28;
    private static final long MOREHUD_PING_HOLD_WALL_MS = 12000L;
    private static boolean skipFpsRowBackground;
    private static boolean skipCpsRowBackground;
    private static boolean skipPingRowBackground;

    private MoreHudFeature() {
    }

    private static boolean canDrawMoreHudWorldContext(Minecraft mc, boolean hudEditor) {
        return hudEditor || (mc.thePlayer != null && mc.theWorld != null);
    }

    public static void render(Minecraft mc) {
        boolean ownBackgroundMask = !HudEditorBackgroundMask.isActive();
        if (ownBackgroundMask) {
            HudEditorBackgroundMask.beginFrame();
        }
        try {
            renderInternal(mc);
        } finally {
            if (ownBackgroundMask) {
                HudEditorBackgroundMask.endFrame();
            }
        }
    }

    private static void renderInternal(Minecraft mc) {
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        boolean hudEditor = mc.currentScreen instanceof GomodHudEditorGUI;
        boolean coordsColorPicker = mc.currentScreen instanceof MoreHudCoordsColorsGUI;
        boolean potionsColorPicker = mc.currentScreen instanceof MoreHudPotionsColorsGUI;
        if (!hudEditor && !coordsColorPicker && !potionsColorPicker && !ConfigManager.isMoreHudEnabled()) {
            return;
        }
        if (!canDrawMoreHudWorldContext(mc, hudEditor || coordsColorPicker || potionsColorPicker)) {
            return;
        }
        HudGlState.restoreForOverlayText();
        // Redundant with GuiIngameMixin HEAD: if that inject is skipped (require=0), CPS still updates here.
        if (hudEditor || ConfigManager.isMoreHudCpsEnabled()) {
            MoreHudCpsTracker.pollEdges(mc);
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int gap = ConfigManager.getMoreHudRowGap();
        FontRenderer fr = mc.fontRendererObj;
        if ((hudEditor || ConfigManager.isMoreHudFpsEnabled())
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.FPS)) {
            int cx = sw / 2 + ConfigManager.getMoreHudFpsOffsetCenterX();
            int cy = ConfigManager.getMoreHudFpsPosY();
            float scale = ConfigManager.getMoreHudFpsScale();
            drawPrecomposedTextRowBackground(mc, cx, cy, scale, HudOpacitySliderBoxInsets.Module.FPS,
                    ConfigManager.getMoreHudFpsBg(), MoreHudBackgroundColors.fpsPixel(ConfigManager.getMoreHudFpsBg()));
            skipFpsRowBackground = true;
            try {
                drawWithRowScale(cx, cy, scale, () -> drawFpsRow(mc, fr, cx, cy));
            } finally {
                skipFpsRowBackground = false;
            }
        }
        if ((hudEditor || ConfigManager.isMoreHudCpsEnabled())
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.CPS)) {
            int cx = sw / 2 + ConfigManager.getMoreHudCpsOffsetCenterX();
            int cy = ConfigManager.getMoreHudCpsPosY();
            float scale = ConfigManager.getMoreHudCpsScale();
            drawPrecomposedTextRowBackground(mc, cx, cy, scale, HudOpacitySliderBoxInsets.Module.CPS,
                    ConfigManager.getMoreHudCpsBg(), MoreHudBackgroundColors.cpsPixel(ConfigManager.getMoreHudCpsBg()));
            skipCpsRowBackground = true;
            try {
                drawWithRowScale(cx, cy, scale, () -> drawCpsRow(mc, fr, cx, cy));
            } finally {
                skipCpsRowBackground = false;
            }
        }
        if ((hudEditor || ConfigManager.isMoreHudPingEnabled())
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.PING)) {
            int cx = sw / 2 + ConfigManager.getMoreHudPingOffsetCenterX();
            int cy = ConfigManager.getMoreHudPingPosY();
            float scale = ConfigManager.getMoreHudPingScale();
            drawPrecomposedTextRowBackground(mc, cx, cy, scale, HudOpacitySliderBoxInsets.Module.PING,
                    ConfigManager.getMoreHudPingBg(), MoreHudBackgroundColors.pingPixel(ConfigManager.getMoreHudPingBg()));
            skipPingRowBackground = true;
            try {
                drawWithRowScale(cx, cy, scale, () -> drawPingRow(mc, fr, cx, cy));
            } finally {
                skipPingRowBackground = false;
            }
        }
        boolean potionsBlockVisible = hudEditor || ConfigManager.isMoreHudPotionsEnabled();
        boolean flameCfgOn = ConfigManager.isMoreHudFlameEnabled();
        boolean killFxCfgOn = ConfigManager.isMoreHudKillEffectsEnabled();
        // In HUD editor, always draw previews so hitboxes match visible content (same as when rows are enabled).
        KillEffectHudState flameState = (hudEditor || flameCfgOn)
                ? resolveVisibleKillEffectHudState(mc, hudEditor || !flameCfgOn)
                : null;
        KillEffectHudState otherKillEffectsState = (hudEditor || killFxCfgOn)
                ? resolveVisibleOtherKillEffectsHudState(mc, hudEditor || !killFxCfgOn)
                : null;
        boolean flameCompact = (hudEditor || flameCfgOn) && ConfigManager.isMoreHudFlameCompact();

        if (potionsBlockVisible && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.POTIONS)) {
            List<PotionEffect> plist = resolvePotionDrawList(mc, hudEditor);
            boolean incFlame = includeFlamingArrowsInPotionsBlock(mc);
            boolean drawPotionsBlock = hudEditor || !plist.isEmpty() || (incFlame && flameState != null)
                    || BlitzStarEffectsHudTracker.hasActiveEffects();
            if (drawPotionsBlock) {
                int cx = sw / 2 + ConfigManager.getMoreHudPotionsOffsetCenterX();
                int cy = ConfigManager.getMoreHudPotionsPosY();
                float scale = ConfigManager.getMoreHudPotionsScale();
                final boolean drawFlameInPotions = incFlame;
                drawWithRowScale(cx, cy, scale, () -> drawPotionRows(mc, fr, cx, cy, gap, hudEditor,
                        drawFlameInPotions && flameState != null, drawFlameInPotions, flameState));
            }
        }
        boolean debugScreenOpen = mc != null && mc.gameSettings != null && mc.gameSettings.showDebugInfo;
        boolean coordsVisibleForDebug = hudEditor || !debugScreenOpen || ConfigManager.isMoreHudCoordsShowDuringF3();
        if ((hudEditor || ConfigManager.isMoreHudCoordsEnabled())
                && coordsVisibleForDebug
                && !coordsColorPicker
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.COORDS)) {
            renderCoordsHudAtConfig(mc, fr, hudEditor);
        }
        if ((hudEditor || ConfigManager.isMoreHudArrowsEnabled())
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.ARROWS)) {
            int cx = sw / 2 + ConfigManager.getMoreHudArrowsOffsetCenterX();
            int cy = ConfigManager.getMoreHudArrowsPosY();
            int n = hudEditor ? 24 : countArrows(mc);
            float scale = ConfigManager.getMoreHudArrowsScale();
            drawWithRowScale(cx, cy, scale, () -> drawArrowRow(mc, fr, cx, cy, n));
        }
        MoreHudArmorStatusFeature.render(mc);
        if (flameState != null && !flameCompact && !includeFlamingArrowsInPotionsBlock(mc)
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.FLAME)) {
            int cx = sw / 2 + ConfigManager.getMoreHudFlameOffsetCenterX();
            int cy = ConfigManager.getMoreHudFlamePosY();
            float scale = ConfigManager.getMoreHudFlameScale();
            if (isKillEffectTimerBarActive(flameState)) {
                drawWithRowScale(cx, cy, scale, () -> drawFlameStandaloneDepleteRow(mc, fr, cx, cy, flameState));
            } else {
                drawWithRowScale(cx, cy, scale, () -> drawFlameStandaloneSimpleRow(mc, fr, cx, cy, flameState));
            }
        }
        // In-game, skip the compact flame row when merged into Effects HUD (same icon/timer already in that block).
        // In the HUD editor, keep the separate preview visible so the inactive red module still has content.
        boolean showCompactFlameRow = hudEditor || flameCompact
                || (!hudEditor && flameState != null && !includeFlamingArrowsInPotionsBlock(mc));
        if (flameState != null && showCompactFlameRow
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.FLAME)) {
            int cx = sw / 2 + ConfigManager.getMoreHudFlameCompactOffsetCenterX();
            int cy = ConfigManager.getMoreHudFlameCompactPosY();
            float sc = ConfigManager.getMoreHudFlameCompactScale();
            drawFlameCompactRow(mc, fr, cx, cy, sc, flameState, false);
        }
        if (otherKillEffectsState != null
                && !HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.KILL_EFFECTS)) {
            int cx = sw / 2 + ConfigManager.getMoreHudKillEffectsOffsetCenterX();
            int cy = ConfigManager.getMoreHudKillEffectsPosY();
            float scale = ConfigManager.getMoreHudKillEffectsScale();
            drawWithRowScale(cx, cy, scale, () -> drawOtherKillEffectsRow(mc, fr, cx, cy, otherKillEffectsState));
        }

        HudGlState.restoreForOverlayText();
    }

    public static String formatPingLine(Minecraft mc) {
        String[] parts = pingRowParts(mc);
        return parts[0] + parts[1];
    }

    private static void drawPrecomposedTextRowBackground(Minecraft mc, int centerX, int centerY, float scale,
            HudOpacitySliderBoxInsets.Module module, int opacity, int pixel) {
        if (mc == null || opacity <= 0 || pixel == 0 || !HudEditorBackgroundMask.isActive()) {
            return;
        }
        FontRenderer fr = mc.fontRendererObj;
        boolean shadow = module == HudOpacitySliderBoxInsets.Module.FPS
                ? ConfigManager.isMoreHudFpsShadow()
                : module == HudOpacitySliderBoxInsets.Module.CPS
                        ? ConfigManager.isMoreHudCpsShadow()
                        : ConfigManager.isMoreHudPingShadow();
        String rowText;
        if (module == HudOpacitySliderBoxInsets.Module.FPS) {
            String[] parts = fpsRowParts(mc);
            rowText = parts[0] + parts[1];
        } else if (module == HudOpacitySliderBoxInsets.Module.CPS) {
            rowText = previewCpsString(mc);
        } else {
            String[] parts = pingRowParts(mc);
            rowText = parts[0] + parts[1];
        }
        int width = measureSplitCenteredRowWidth(fr, rowText, module, shadow);
        int height = measureSplitCenteredRowHeight(fr, module, shadow);
        HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                module, shadow);
        int[] bounds = new int[4];
        centeredTextRowScreenBounds(centerX, centerY, width, height, scale, pad.up, bounds);
        HudEditorBackgroundMask.drawRect(bounds[0], bounds[1], bounds[0] + bounds[2], bounds[1] + bounds[3], pixel);
    }

    public static final String PING_CUSTOM_VALUE_TOKEN = "{value}";

    /** Prefix and value strings for ping HUD (split colors). */
    public static String[] pingRowParts(Minecraft mc) {
        return pingRowPartsForStyle(ConfigManager.getMoreHudPingDisplayStyle(), resolvePing(mc));
    }

    /** Prefix and value for a ping display style (color gallery samples). */
    public static String[] pingRowPartsForStyle(int style, int ping) {
        if (style == 3) {
            String num = ping < 0 ? "—" : String.valueOf(ping);
            return pingRowPartsFromCustomLabel(num, ConfigManager.getMoreHudPingCustomLabel());
        }
        String[] out = new String[] {"", ""};
        if (ping < 0) {
            if (style == 0) {
                out[1] = "—";
            } else {
                out[0] = "Ping: ";
                out[1] = "—";
            }
            return out;
        }
        switch (style) {
            case 2:
                out[0] = "Ping: ";
                out[1] = String.valueOf(ping);
                break;
            case 1:
                out[0] = "Ping: ";
                out[1] = ping + "ms";
                break;
            case 0:
            default:
                out[1] = ping + "ms";
                break;
        }
        return out;
    }

    /** Splits a custom ping label into prefix (label color) and value segment (value color). */
    public static String[] pingRowPartsFromCustomLabel(String num, String template) {
        String tpl = template == null ? "" : template.trim();
        if (tpl.isEmpty()) {
            tpl = "Ping: " + PING_CUSTOM_VALUE_TOKEN;
        }
        String token = PING_CUSTOM_VALUE_TOKEN;
        int idx = tpl.indexOf(token);
        String[] out = new String[2];
        if (idx < 0) {
            out[0] = tpl;
            out[1] = num;
            return out;
        }
        out[0] = tpl.substring(0, idx);
        out[1] = num + tpl.substring(idx + token.length());
        return out;
    }

    /**
     * Draws the active More HUD row centered in the module settings preview band (between Reset and Back).
     */
    public static void renderSettingsGalleryPreview(Minecraft mc, MoreHudSettingsGUI.Panel panel, int visualCenterX,
            int visualCenterY) {
        if (mc == null || mc.fontRendererObj == null || panel == null) {
            return;
        }
        int[] pivot = new int[2];
        resolveSettingsGalleryPivot(mc, panel, visualCenterX, visualCenterY, pivot);
        renderSettingsGalleryPreviewAtPivot(mc, panel, pivot[0], pivot[1]);
    }

    /**
     * Converts a desired visual center into the row anchor used by each HUD draw routine.
     */
    public static void resolveSettingsGalleryPivot(Minecraft mc, MoreHudSettingsGUI.Panel panel, int visualCenterX,
            int visualCenterY, int[] outPivotXY) {
        if (outPivotXY == null || outPivotXY.length < 2 || panel == null) {
            return;
        }
        int pivotX = visualCenterX;
        int pivotY = visualCenterY;
        for (int pass = 0; pass < 3; pass++) {
            int[] bounds = new int[4];
            measureSettingsGalleryBoundsAtPivot(mc, panel, pivotX, pivotY, bounds);
            int geoCx = bounds[0] + bounds[2] / 2;
            int geoCy = bounds[1] + bounds[3] / 2;
            pivotX += visualCenterX - geoCx;
            pivotY += visualCenterY - geoCy;
        }
        outPivotXY[0] = pivotX;
        outPivotXY[1] = pivotY;
    }

    /**
     * Measures the settings-gallery preview bounds after resolving the correct per-panel pivot from a desired visual
     * center.
     */
    public static void settingsGalleryPreviewBounds(Minecraft mc, MoreHudSettingsGUI.Panel panel, int visualCenterX,
            int visualCenterY, int[] outBounds) {
        if (outBounds == null || outBounds.length < 4 || mc == null || panel == null) {
            return;
        }
        int[] pivot = new int[2];
        resolveSettingsGalleryPivot(mc, panel, visualCenterX, visualCenterY, pivot);
        measureSettingsGalleryBoundsAtPivot(mc, panel, pivot[0], pivot[1], outBounds);
    }

    private static void measureSettingsGalleryBoundsAtPivot(Minecraft mc, MoreHudSettingsGUI.Panel panel, int pivotX,
            int pivotY, int[] out) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        int gap = ConfigManager.getMoreHudRowGap();
        switch (panel) {
            case FPS: {
                float scale = ConfigManager.getMoreHudFpsScale();
                HudOpacitySliderBoxInsets.TextBackgroundPad fpsPad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                        HudOpacitySliderBoxInsets.Module.FPS, ConfigManager.isMoreHudFpsShadow());
                centeredTextRowScreenBounds(pivotX, pivotY, measureFpsWidth(mc), measureFpsHeight(mc), scale,
                        fpsPad.up, out);
                break;
            }
            case CPS: {
                float scale = ConfigManager.getMoreHudCpsScale();
                HudOpacitySliderBoxInsets.TextBackgroundPad cpsPad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                        HudOpacitySliderBoxInsets.Module.CPS, ConfigManager.isMoreHudCpsShadow());
                centeredTextRowScreenBounds(pivotX, pivotY, measureCpsWidth(mc), measureCpsHeight(mc), scale,
                        cpsPad.up, out);
                break;
            }
            case PING: {
                float scale = ConfigManager.getMoreHudPingScale();
                HudOpacitySliderBoxInsets.TextBackgroundPad pingPad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                        HudOpacitySliderBoxInsets.Module.PING, ConfigManager.isMoreHudPingShadow());
                centeredTextRowScreenBounds(pivotX, pivotY, measurePingWidth(mc), measurePingHeight(mc), scale,
                        pingPad.up, out);
                break;
            }
            case POTIONS: {
                boolean incFlame = includeFlamingArrowsForGalleryPreview(mc);
                potionsBlockScreenBounds(mc, pivotX, pivotY, gap, ConfigManager.getMoreHudPotionsScale(), true,
                        incFlame, out);
                break;
            }
            case ARROWS:
                arrowRowScreenBounds(pivotX, pivotY, ConfigManager.getMoreHudArrowsScale(), out);
                break;
            case ARMOR_STATUS:
                MoreHudArmorStatusFeature.screenBounds(pivotX, pivotY, ConfigManager.getMoreHudArmorStatusScale(), out,
                        true);
                break;
            case COORDS:
                MoreHudCoordsFeature.screenBounds(mc, fr, pivotX, pivotY, ConfigManager.getMoreHudCoordsScale(),
                        true, out);
                break;
            case KILL_EFFECTS:
                killEffectsRowScreenBounds(pivotX, pivotY, ConfigManager.getMoreHudKillEffectsScale(), out);
                break;
            case FLAME: {
                KillEffectHudState flameState = resolveVisibleKillEffectHudState(mc, true);
                if (flameState == null) {
                    out[0] = pivotX - 40;
                    out[1] = pivotY - 12;
                    out[2] = 80;
                    out[3] = 24;
                    break;
                }
                flameCompactGalleryPreviewScreenBounds(mc, pivotX, pivotY,
                        ConfigManager.getMoreHudFlameCompactScale(), out);
                break;
            }
            case BLITZ_KIT_TIMER:
                BlitzKitTimerHUD.getPreviewBoundsAt(mc, pivotX, pivotY, ConfigManager.getBlitzKitTimerHudScale(), out);
                break;
            case INCOMING_SCREENSHOT:
            default:
                int[] wh = new int[2];
                IncomingScreenshotOverlay.incomingGalleryPanelSize(ConfigManager.getIncomingScreenshotHudScale(), wh);
                out[0] = pivotX - wh[0] / 2;
                out[1] = pivotY - wh[1] / 2;
                out[2] = wh[0];
                out[3] = wh[1];
                break;
        }
    }

    private static void renderSettingsGalleryPreviewAtPivot(Minecraft mc, MoreHudSettingsGUI.Panel panel, int anchorX,
            int anchorY) {
        HudGlState.restoreForOverlayText();
        FontRenderer fr = mc.fontRendererObj;
        int gap = ConfigManager.getMoreHudRowGap();
        switch (panel) {
            case FPS: {
                float scale = ConfigManager.getMoreHudFpsScale();
                renderFpsRowAt(mc, fr, anchorX, anchorY, scale);
                break;
            }
            case CPS: {
                float scale = ConfigManager.getMoreHudCpsScale();
                drawWithRowScale(anchorX, anchorY, scale, () -> drawCpsRow(mc, fr, anchorX, anchorY));
                break;
            }
            case PING: {
                float scale = ConfigManager.getMoreHudPingScale();
                drawWithRowScale(anchorX, anchorY, scale, () -> drawPingRow(mc, fr, anchorX, anchorY));
                break;
            }
            case POTIONS: {
                KillEffectHudState flameState = resolveVisibleKillEffectHudState(mc, true);
                boolean incFlame = includeFlamingArrowsForGalleryPreview(mc);
                float scale = ConfigManager.getMoreHudPotionsScale();
                drawWithRowScale(anchorX, anchorY, scale, () -> drawPotionRows(mc, fr, anchorX, anchorY, gap, true,
                        incFlame && flameState != null, incFlame, flameState));
                break;
            }
            case ARROWS: {
                float scale = ConfigManager.getMoreHudArrowsScale();
                drawWithRowScale(anchorX, anchorY, scale, () -> drawArrowRow(mc, fr, anchorX, anchorY, 24));
                break;
            }
            case ARMOR_STATUS:
                MoreHudArmorStatusFeature.renderGalleryAt(mc, anchorX, anchorY);
                break;
            case COORDS:
                renderCoordsHudAtPivot(mc, fr, anchorX, anchorY, true);
                break;
            case KILL_EFFECTS: {
                KillEffectHudState state = resolveVisibleOtherKillEffectsHudState(mc, true);
                if (state != null) {
                    float scale = ConfigManager.getMoreHudKillEffectsScale();
                    drawWithRowScale(anchorX, anchorY, scale, () -> drawOtherKillEffectsRow(mc, fr, anchorX, anchorY, state));
                }
                break;
            }
            case FLAME: {
                KillEffectHudState flameState = resolveVisibleKillEffectHudState(mc, true);
                if (flameState == null) {
                    break;
                }
                float sc = ConfigManager.getMoreHudFlameCompactScale();
                drawFlameCompactRow(mc, fr, anchorX, anchorY, sc, flameState, false);
                break;
            }
            case BLITZ_KIT_TIMER:
                BlitzKitTimerHUD.renderPreviewAt(mc, anchorX, anchorY, ConfigManager.getBlitzKitTimerHudScale(),
                        ConfigManager.isBlitzKitTimerHudTextShadow());
                break;
            case INCOMING_SCREENSHOT: {
                int[] wh = new int[2];
                float hudScale = ConfigManager.getIncomingScreenshotHudScale();
                IncomingScreenshotOverlay.incomingGalleryPanelSize(hudScale, wh);
                int drawLeft = anchorX - wh[0] / 2;
                int drawTop = anchorY - wh[1] / 2;
                IncomingScreenshotOverlay.renderGalleryAt(mc, drawLeft, drawTop, hudScale);
                break;
            }
            default:
                break;
        }
        HudGlState.restoreForOverlayText();
    }

    private static void renderCenteredTextRowAt(Minecraft mc, FontRenderer fr, int centerX, int centerY, float scale,
            String text, int bgAlpha, int textRgb, boolean shadow) {
        drawWithRowScale(centerX, centerY, scale,
                () -> drawCenteredRow(mc, fr, centerX, centerY, text, bgAlpha, textRgb, shadow));
    }

    /** Coords HUD at saved position (HUD editor, color picker, in-game). */
    public static void renderCoordsHudAtConfig(Minecraft mc, FontRenderer fr, boolean preview) {
        if (mc == null || fr == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int leftX = sr.getScaledWidth() / 2 + ConfigManager.getMoreHudCoordsOffsetCenterX();
        int topY = ConfigManager.getMoreHudCoordsPosY();
        renderCoordsHudAtPivot(mc, fr, leftX, topY, preview);
    }

    public static void renderCoordsHudAtPivot(Minecraft mc, FontRenderer fr, int leftX, int topY, boolean preview) {
        float scale = ConfigManager.getMoreHudCoordsScale();
        drawWithRowScale(leftX, topY, scale, () -> MoreHudCoordsFeature.render(mc, fr, leftX, topY, preview));
    }

    /**
     * Scale HUD row around its anchor so changing scale does not shear content away from (0,0).
     */
    private static void drawWithRowScale(int anchorX, int anchorY, float scale, Runnable draw) {
        GlStateManager.pushMatrix();
        if (scale != 1.0F) {
            GlStateManager.translate(anchorX, anchorY, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            GlStateManager.translate(-anchorX, -anchorY, 0.0F);
        }
        HudEditorBackgroundMask.pushTransform(anchorX, anchorY, scale);
        try {
            draw.run();
        } catch (Throwable ignored) {
        }
        HudEditorBackgroundMask.popTransform();
        GlStateManager.popMatrix();
    }

    /** Padded width used by {@link #drawCenteredRow} (text + 6px horizontal padding). */
    public static int unscaledCenteredTextRowWidth(FontRenderer fr, String text) {
        if (fr == null || text == null) {
            return 24;
        }
        return fr.getStringWidth(text) + 6;
    }

    public static int unscaledCenteredTextRowHeight(FontRenderer fr) {
        return fr == null ? 10 : fr.FONT_HEIGHT + 1;
    }

    public static String previewCpsString(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return "[0 | 0]";
        }
        int mode = ConfigManager.getMoreHudCpsMode();
        int l = MoreHudCpsTracker.getLeftCps();
        int r = MoreHudCpsTracker.getRightCps();
        boolean brackets = ConfigManager.isMoreHudCpsSquareBrackets();
        if (mode == 0) {
            return brackets ? ("[" + l + "]") : String.valueOf(l);
        }
        if (mode == 1) {
            return brackets ? ("[" + r + "]") : String.valueOf(r);
        }
        String sep = " " + EnumChatFormatting.GRAY + "|" + EnumChatFormatting.RESET + " ";
        String core = String.valueOf(l) + sep + r;
        return brackets ? ("[" + core + "]") : core;
    }

    /**
     * Screen-space bounds for a centered text row after row scale (for HUD Editor hit testing).
     * Uses {@code topPadUnscaled = 1} above {@code cy} (legacy row padding).
     */
    public static void centeredTextRowScreenBounds(int cx, int cy, int unscaledPaddedWidth, int unscaledHeight, float scale,
            int[] outLeftTopWidthHeight) {
        centeredTextRowScreenBounds(cx, cy, unscaledPaddedWidth, unscaledHeight, scale, 1, outLeftTopWidthHeight);
    }

    /**
     * @param topPadUnscaled pixels above {@code cy} included in bounds (match FPS background when using insets).
     */
    public static void centeredTextRowScreenBounds(int cx, int cy, int unscaledPaddedWidth, int unscaledHeight, float scale,
            int topPadUnscaled, int[] outLeftTopWidthHeight) {
        int half = (int) Math.ceil((unscaledPaddedWidth / 2.0F) * scale);
        int topPad = (int) Math.ceil(topPadUnscaled * scale);
        int h = (int) Math.ceil(unscaledHeight * scale);
        outLeftTopWidthHeight[0] = cx - half;
        outLeftTopWidthHeight[1] = cy - topPad;
        outLeftTopWidthHeight[2] = half * 2;
        outLeftTopWidthHeight[3] = h;
    }

    public static String fpsRowPrefix() {
        return fpsRowParts(null)[0];
    }

    public static String fpsRowValue(Minecraft mc) {
        return fpsRowParts(mc)[1];
    }

    public static String[] fpsRowParts(Minecraft mc) {
        return fpsRowPartsForStyle(mc, ConfigManager.getMoreHudFpsDisplayStyle());
    }

    public static final String FPS_CUSTOM_VALUE_TOKEN = "{value}";

    public static String[] fpsRowPartsForStyle(Minecraft mc, int style) {
        String num = String.valueOf(mc != null ? Minecraft.getDebugFPS() : 500);
        String[] out = new String[2];
        switch (style) {
            case 1:
                out[0] = "FPS ";
                out[1] = num;
                break;
            case 2:
                out[0] = num + " ";
                out[1] = "FPS";
                break;
            case 3:
                return fpsRowPartsFromCustomLabel(num, ConfigManager.getMoreHudFpsCustomLabel());
            case 4:
                out[0] = "";
                out[1] = num + " f/s";
                break;
            case 0:
            default:
                out[0] = "FPS: ";
                out[1] = num;
                break;
        }
        return out;
    }

    /** Splits a custom FPS label into prefix (label color) and value segment (value color). */
    public static String[] fpsRowPartsFromCustomLabel(String num, String template) {
        String tpl = template == null ? "" : template.trim();
        if (tpl.isEmpty()) {
            tpl = "FPS: " + FPS_CUSTOM_VALUE_TOKEN;
        }
        String token = FPS_CUSTOM_VALUE_TOKEN;
        int idx = tpl.indexOf(token);
        String[] out = new String[2];
        if (idx < 0) {
            out[0] = tpl;
            out[1] = num;
            return out;
        }
        out[0] = tpl.substring(0, idx);
        out[1] = num + tpl.substring(idx + token.length());
        return out;
    }

    public static int measureSplitCenteredRowWidth(FontRenderer fr, String text,
            HudOpacitySliderBoxInsets.Module insetModule, boolean textShadow) {
        if (fr == null || text == null) {
            return 64;
        }
        HudOpacitySliderBoxInsets.TextBackgroundPad pad =
                HudOpacitySliderBoxInsets.splitCenteredTextRowPad(insetModule, textShadow);
        return fr.getStringWidth(text) + pad.left + pad.right;
    }

    public static int measureSplitCenteredRowHeight(FontRenderer fr,
            HudOpacitySliderBoxInsets.Module insetModule, boolean textShadow) {
        if (fr == null) {
            return 11;
        }
        HudOpacitySliderBoxInsets.TextBackgroundPad pad =
                HudOpacitySliderBoxInsets.splitCenteredTextRowPad(insetModule, textShadow);
        return fr.FONT_HEIGHT + pad.up + pad.down;
    }

    public static int measureFpsWidth(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 64;
        }
        boolean shadow = ConfigManager.isMoreHudFpsShadow();
        String custom = ConfigManager.getMoreHudFpsCustomLabel();
        String customSample = custom.contains(FPS_CUSTOM_VALUE_TOKEN)
                ? custom.replace(FPS_CUSTOM_VALUE_TOKEN, "999") : custom + "999";
        return Math.max(
                measureSplitCenteredRowWidth(fr, "FPS: 999", HudOpacitySliderBoxInsets.Module.FPS, shadow),
                Math.max(
                        measureSplitCenteredRowWidth(fr, "FPS 999", HudOpacitySliderBoxInsets.Module.FPS, shadow),
                        Math.max(
                                measureSplitCenteredRowWidth(fr, "999 FPS", HudOpacitySliderBoxInsets.Module.FPS, shadow),
                                Math.max(
                                        measureSplitCenteredRowWidth(fr, "999 f/s", HudOpacitySliderBoxInsets.Module.FPS,
                                                shadow),
                                        measureSplitCenteredRowWidth(fr, customSample,
                                                HudOpacitySliderBoxInsets.Module.FPS, shadow)))));
    }

    public static int measureFpsHeight(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        return measureSplitCenteredRowHeight(fr, HudOpacitySliderBoxInsets.Module.FPS,
                ConfigManager.isMoreHudFpsShadow());
    }

    public static int measureCpsWidth(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 64;
        }
        return measureSplitCenteredRowWidth(fr, previewCpsString(mc), HudOpacitySliderBoxInsets.Module.CPS,
                ConfigManager.isMoreHudCpsShadow());
    }

    public static int measureCpsHeight(Minecraft mc) {
        return measureSplitCenteredRowHeight(mc == null ? null : mc.fontRendererObj,
                HudOpacitySliderBoxInsets.Module.CPS, ConfigManager.isMoreHudCpsShadow());
    }

    public static int measurePingWidth(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 80;
        }
        boolean shadow = ConfigManager.isMoreHudPingShadow();
        String[] parts = pingRowParts(mc);
        return measureSplitCenteredRowWidth(fr, parts[0] + parts[1], HudOpacitySliderBoxInsets.Module.PING, shadow);
    }

    public static int measurePingHeight(Minecraft mc) {
        return measureSplitCenteredRowHeight(mc == null ? null : mc.fontRendererObj,
                HudOpacitySliderBoxInsets.Module.PING, ConfigManager.isMoreHudPingShadow());
    }

    private static int potionRowsVerticalGap() {
        return POTION_ROW_GAP;
    }

    private static int flameRowUnscaledHeightForPotionsBlock(FontRenderer fr, KillEffectHudState state, boolean preview) {
        return potionRowUnscaledHeight(fr);
    }

    /** Bottom Y (exclusive) of the last effect row content, matching per-row {@code y + rowH}. */
    private static int computePotionsBlockContentBottom(FontRenderer fr, int startY, List<PotionEffect> list,
            boolean includeFlame, KillEffectHudState flameState, boolean preview) {
        int rowGap = potionRowsVerticalGap();
        int potionH = potionRowUnscaledHeight(fr);
        int y = startY;
        for (PotionEffect eff : list) {
            if (eff != null) {
                y += potionH + rowGap;
            }
        }
        List<BlitzStarEffectsHudTracker.ActiveRow> blitzRows = resolveBlitzDrawListForPotionsBlock(preview);
        if (preview) {
            if (includeFlame) {
                y += flameRowUnscaledHeightForPotionsBlock(fr, flameState, preview) + rowGap;
            }
            for (BlitzStarEffectsHudTracker.ActiveRow row : blitzRows) {
                if (row != null) {
                    y += potionH + rowGap;
                }
            }
        } else {
            for (BlitzStarEffectsHudTracker.ActiveRow row : blitzRows) {
                if (row != null) {
                    y += potionH + rowGap;
                }
            }
            if (includeFlame) {
                y += flameRowUnscaledHeightForPotionsBlock(fr, flameState, preview) + rowGap;
            }
        }
        if (y == startY) {
            return startY;
        }
        return y - rowGap;
    }

    private static int potionsBgTopOffset() {
        return POTION_BG_TOP_INSET + POTION_BG_PAD_TOP;
    }

    private static int potionsBgBottomOffset() {
        return POTION_BG_PAD_BOTTOM;
    }

    private static int potionRowUnscaledHeight(FontRenderer fr) {
        int fh = fr.FONT_HEIGHT;
        return Math.max(POTION_ICON_LARGE, fh * 2 + 2);
    }

    /** What the HUD would use for the effect name line (respects vanilla-colors toggle + debug overrides). */
    public static int previewMoreHudPotionNameColor(int potionId) {
        return resolveMoreHudPotionNameRgb(potionId, ConfigManager.getMoreHudPotionsColor(),
                ConfigManager.isMoreHudPotionsEffectColors());
    }

    /** Effect name for held-item info popup (matches Effects HUD naming / potency). */
    public static String formatHeldItemPotionEffectName(PotionEffect eff) {
        if (eff == null) {
            return "";
        }
        Potion p = Potion.potionTypes[eff.getPotionID()];
        if (p == null) {
            return "";
        }
        return formatPotionEffectBaseName(p, eff, false);
    }

    /**
     * Plain duration text for held-item info above the hotbar.
     * <p>Vanilla {@link Potion#getDurationString} returns {@code *:*} for ambient effects; many multiplayer
     * servers (e.g. Hypixel) mark kit/splash potions as ambient while still sending a finite duration, so we
     * always format from ticks here. Only absurdly long values use {@code **:**} (true open-ended buffs).</p>
     */
    public static String formatHeldItemPotionDurationPlain(PotionEffect eff) {
        if (eff == null) {
            return "";
        }
        int ticks = eff.getDuration();
        if (ConfigManager.isMoreHudPotionsDecimalEffectTimer()) {
            return formatDecimalEffectTimerPlain(Math.max(0.0, ticks / 20.0));
        }
        return formatHeldItemPotionDurationTicksPlain(ticks);
    }

    /** Held-item durations keep long finite Hypixel potions numeric. */
    private static String formatHeldItemPotionDurationTicksPlain(int totalTicks) {
        if (totalTicks <= 0) {
            return "0:00";
        }
        // Above ~3 years in ticks: treat as infinite (matches spirit of vanilla **:** without capping ~27m potions).
        if (totalTicks >= 1_000_000_000) {
            return "**:**";
        }
        return PotionHudTextCache.seconds((totalTicks + 19) / 20).plain;
    }

    /** Name-line ARGB for held-item info (same rules as {@link #previewMoreHudPotionNameColor}). */
    public static int heldItemPotionNameColor(int potionId) {
        return 0xFF000000 | (previewMoreHudPotionNameColor(potionId) & 0xFFFFFF);
    }

    private static int resolveMoreHudPotionNameRgb(int potionId, int fallbackRgb, boolean useVanillaPerEffectTable) {
        if (potionId >= 0 && potionId < POTION_COLOR_DEBUG_OVERRIDE.length) {
            int o = POTION_COLOR_DEBUG_OVERRIDE[potionId];
            if (o >= 0) {
                return o & 0xFFFFFF;
            }
        }
        if (!useVanillaPerEffectTable) {
            return fallbackRgb & 0xFFFFFF;
        }
        return ConfigManager.getMoreHudPotionsEffectColor(potionId) & 0xFFFFFF;
    }

    private static String formatPotionEffectBaseName(Potion p, PotionEffect eff, boolean hudEditorPreview) {
        return PotionHudTextCache.name(p, eff.getAmplifier(), hudEditorPreview).plain;
    }

    private static String formatDecimalEffectTimerPlain(double seconds) {
        return String.format(Locale.US, "%.1f", seconds);
    }

    /**
     * Plain duration text for draw calls that supply {@link #potionDurationDrawRgb()} (no § codes).
     */
    private static String formatEffectsHudPotionDurationPlain(int ticks, boolean hudEditorPreview) {
        return potionTimerText(ticks, hudEditorPreview).plain;
    }

    /**
     * Effects HUD duration with §7 for width/layout paths that expect formatted strings.
     */
    private static String formatEffectsHudPotionDurationLine(int ticks, boolean hudEditorPreview) {
        return potionTimerText(ticks, hudEditorPreview).formatted;
    }

    private static PotionHudTextCache.Text potionTimerText(int ticks, boolean hudEditorPreview) {
        return PotionHudTextCache.duration(ticks, hudEditorPreview, ConfigManager.isMoreHudPotionsDecimalEffectTimer());
    }

    private static String formatFlameEffectsHudMergedDurationPlain(int ticks) {
        return PotionHudTextCache.duration(ticks, false, false).plain;
    }

    /** Flaming Arrows merged into Effects HUD when decimal timer mode is off: vanilla-style {@code M:SS} (e.g. {@code 0:20}). */
    private static String formatFlameEffectsHudMergedDurationLine(int ticks) {
        return PotionHudTextCache.duration(ticks, false, false).formatted;
    }

    private static int computeMaxPotionTextColumnWidth(FontRenderer fr, List<PotionEffect> list,
                                                       boolean hudEditorPreview) {
        int maxCol = 0;
        if (fr == null || list == null) {
            return 0;
        }
        boolean vanillaEffectColor = ConfigManager.isMoreHudPotionsEffectColors();
        for (PotionEffect eff : list) {
            if (eff == null) {
                continue;
            }
            Potion p = Potion.potionTypes[eff.getPotionID()];
            if (p == null) {
                continue;
            }
            PotionHudTextCache.Text name = PotionHudTextCache.name(p, eff.getAmplifier(), hudEditorPreview);
            String nameLine = vanillaEffectColor ? name.plain : name.formatted;
            String durLine = formatEffectsHudPotionDurationLine(eff.getDuration(), hudEditorPreview);
            int tw = Math.max(PotionHudTextCache.width(fr, nameLine), PotionHudTextCache.width(fr, durLine));
            maxCol = Math.max(maxCol, tw);
        }
        maxCol = Math.max(maxCol, computeMaxBlitzStarTextColumnWidth(fr, hudEditorPreview));
        return maxCol;
    }

    private static int computeMaxBlitzStarTextColumnWidth(FontRenderer fr, boolean hudEditorPreview) {
        if (fr == null) {
            return 0;
        }
        int maxCol = 0;
        boolean perEffect = ConfigManager.isMoreHudPotionsEffectColors();
        List<BlitzStarEffectsHudTracker.ActiveRow> rows = BlitzStarEffectsHudTracker.getActiveRows();
        if (rows.isEmpty() && hudEditorPreview) {
            BlitzStarEffectsHudTracker.Effect e = HUD_EDITOR_PREVIEW_BLITZ_EFFECT;
            return blitzStarRowTextWidth(fr, e, e.durationTicks(), perEffect, true);
        }
        for (BlitzStarEffectsHudTracker.ActiveRow row : rows) {
            maxCol = Math.max(maxCol, blitzStarRowTextWidth(fr, row.effect, row.remainingTicks, perEffect, hudEditorPreview));
        }
        return maxCol;
    }

    private static int blitzStarRowTextWidth(FontRenderer fr, BlitzStarEffectsHudTracker.Effect effect, int ticks,
                                             boolean perEffect, boolean hudEditorPreview) {
        String name = effect.displayName;
        String nameLine = perEffect ? name : EnumChatFormatting.RESET + name;
        String durLine = formatEffectsHudPotionDurationLine(ticks, hudEditorPreview);
        return Math.max(PotionHudTextCache.width(fr, nameLine), PotionHudTextCache.width(fr, durLine));
    }

    /**
     * Text column width for the potion block; effect icons stay centered on the row anchor while text extends right.
     * In-game uses max(current effects, editor preview sample) instead of shrinking when fewer/shorter effects.
     */
    private static int layoutPotionTextColumnWidth(FontRenderer fr, List<PotionEffect> activeList,
                                                   boolean hudEditorPreview, boolean includeFlameRow) {
        if (fr == null) {
            return 0;
        }
        if (hudEditorPreview) {
            int w = computeMaxPotionTextColumnWidth(fr, activeList, true);
            if (includeFlameRow) {
                w = Math.max(w, computeFlameTextColumnWidth(fr));
            }
            return w;
        }
        int wLive = computeMaxPotionTextColumnWidth(fr, activeList, false);
        int wEditorSample = computeMaxPotionTextColumnWidth(fr, HUD_EDITOR_PREVIEW_POTIONS, true);
        int layout = Math.max(wLive, wEditorSample);
        if (includeFlameRow) {
            layout = Math.max(layout, computeFlameTextColumnWidth(fr));
        }
        return layout;
    }

    private static int computeFlameTextColumnWidth(FontRenderer fr) {
        if (fr == null) {
            return 0;
        }
        int nameW = PotionHudTextCache.width(fr, "Flaming Arrows");
        int durW;
        if (ConfigManager.isMoreHudPotionsDecimalEffectTimer()) {
            durW = PotionHudTextCache.width(fr, PotionHudTextCache.tenths(888888).formatted);
            durW = Math.max(durW, PotionHudTextCache.width(fr, PotionHudTextCache.tenths(200).formatted));
        } else {
            durW = Math.max(PotionHudTextCache.width(fr, formatFlameEffectsHudMergedDurationLine(400)),
                    PotionHudTextCache.width(fr, formatFlameEffectsHudMergedDurationLine(72000)));
        }
        return Math.max(nameW, durW);
    }

    public static int measurePotionsWidth(Minecraft mc, boolean hudEditorPreview, boolean includeFlameRow) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 140;
        }
        int maxCol = layoutPotionTextColumnWidth(fr, resolvePotionDrawList(mc, hudEditorPreview), hudEditorPreview,
                includeFlameRow);
        if (maxCol == 0) {
            return POTION_ICON_LARGE + 4;
        }
        return POTION_ICON_LARGE + 4 + maxCol + 4;
    }

    /** Unscaled height through last line; includes trailing {@code gap} like {@link #drawPotionRows}. */
    public static int measurePotionsHeight(Minecraft mc, int gap, boolean hudEditorPreview, boolean includeFlameRow) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 24;
        }
        int rowGap = potionRowsVerticalGap();
        int potionH = potionRowUnscaledHeight(fr);
        int flameRowH = potionH;
        List<PotionEffect> list = resolvePotionDrawList(mc, hudEditorPreview);
        int n = list.size();
        int blitzN = resolveBlitzDrawListForPotionsBlock(hudEditorPreview).size();
        if (n == 0 && blitzN == 0 && !includeFlameRow) {
            return 0;
        }
        int h = n * (potionH + rowGap);
        h += blitzN * (potionH + rowGap);
        if (includeFlameRow) {
            h += flameRowH + rowGap;
        }
        return h + gap;
    }

    public static int measureArrowsWidth(Minecraft mc) {
        return 16;
    }

    public static int measureArrowsHeight(Minecraft mc) {
        return 16;
    }

    public static int measureFlameWidth(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 160;
        }
        KillEffectHudState preview = buildPreviewKillEffectHudState();
        HudOpacitySliderBoxInsets.TextBackgroundPad pad =
                HudOpacitySliderBoxInsets.splitCenteredTextRowPadSigned(HudOpacitySliderBoxInsets.Module.FLAME,
                        ConfigManager.isMoreHudFlameShadow());
        int contentPad = POTION_ICON_LARGE + 4 + 6;
        if (isKillEffectTimerBarActive(preview)) {
            int bw = flameCompactBarWUnscaled();
            return contentPad + bw + pad.left + pad.right;
        }
        return contentPad + fr.getStringWidth(preview.fullLinePlain) + pad.left + pad.right;
    }

    public static int measureFlameHeight(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr != null) {
            HudOpacitySliderBoxInsets.TextBackgroundPad pad =
                    HudOpacitySliderBoxInsets.splitCenteredTextRowPadSigned(HudOpacitySliderBoxInsets.Module.FLAME,
                            ConfigManager.isMoreHudFlameShadow());
            KillEffectHudState preview = buildPreviewKillEffectHudState();
            if (isKillEffectTimerBarActive(preview)) {
                int bh = flameCompactBarHUnscaled();
                int rowH = Math.max(POTION_ICON_LARGE, bh + 4);
                return rowH + pad.up + pad.down;
            }
            return Math.max(POTION_ICON_LARGE, fr.FONT_HEIGHT + 2) + pad.up + pad.down;
        }
        return measureFpsHeight(mc);
    }

    public static int measureKillEffectsWidth(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 56;
        }
        int[] bb = new int[4];
        computeKillEffectsBoundingBox(mc, fr, 0, 0, true, bb);
        return bb[2] - bb[0];
    }

    public static int measureKillEffectsHeight(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return POTION_ICON_LARGE;
        }
        int[] bb = new int[4];
        computeKillEffectsBoundingBox(mc, fr, 0, 0, true, bb);
        return bb[3] - bb[1];
    }

    public static void flameRowScreenBounds(int cx, int cy, float scale, int[] outLeftTopWidthHeight) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc != null ? mc.fontRendererObj : null;
        int uw = measureFlameWidth(mc);
        int uh = measureFlameHeight(mc);
        centeredTextRowScreenBounds(cx, cy, uw, uh, scale, outLeftTopWidthHeight);
    }

    public static void killEffectsRowScreenBounds(int cx, int cy, float scale, int[] outLeftTopWidthHeight) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc != null ? mc.fontRendererObj : null;
        if (fr == null) {
            outLeftTopWidthHeight[0] = cx - 28;
            outLeftTopWidthHeight[1] = cy - 8;
            outLeftTopWidthHeight[2] = 56;
            outLeftTopWidthHeight[3] = 16;
            return;
        }
        int[] bb = new int[4];
        computeKillEffectsBoundingBox(mc, fr, cx, cy, true, bb);
        outLeftTopWidthHeight[0] = screenFromUnscaledPivotX(cx, scale, bb[0]);
        outLeftTopWidthHeight[1] = screenFromUnscaledPivotY(cy, scale, bb[1]);
        outLeftTopWidthHeight[2] = Math.max(1, screenFromUnscaledPivotX(cx, scale, bb[2]) - outLeftTopWidthHeight[0]);
        outLeftTopWidthHeight[3] = Math.max(1, screenFromUnscaledPivotY(cy, scale, bb[3]) - outLeftTopWidthHeight[1]);
    }

    private static void computeKillEffectsBoundingBox(Minecraft mc, FontRenderer fr, int centerX, int y,
                                                      boolean hudEditorPreview, int[] outMinMax) {
        KillEffectHudState state = resolveVisibleOtherKillEffectsHudState(mc, hudEditorPreview);
        computeKillEffectsBoundingBox(mc, fr, centerX, y, state, outMinMax);
    }

    private static void computeKillEffectsBoundingBox(Minecraft mc, FontRenderer fr, int centerX, int y,
                                                      KillEffectHudState state, int[] outMinMax) {
        if (state == null) {
            state = buildPreviewOtherKillEffectsHudState();
        }
        int iconSize = killEffectsIconSizeUnscaled();
        int inx = ConfigManager.getMoreHudKillEffectsIconNudgeX();
        int iny = ConfigManager.getMoreHudKillEffectsIconNudgeY();
        int tnx = ConfigManager.getMoreHudKillEffectsTimerNudgeX();
        int tny = ConfigManager.getMoreHudKillEffectsTimerNudgeY();
        int rowH = killEffectsRowHUnscaled(fr);
        int iconLeft0 = centerX - iconSize / 2;
        int iconY0 = y + (rowH - iconSize) / 2;
        int tx0 = iconLeft0 + iconSize + 4;
        int iconLeft = iconLeft0 + inx;
        int iconY = iconY0 + iny;
        String timerLine = state.timerPlain != null ? state.timerPlain : BlitzFlameKillEffectTracker.previewOtherKillEffectTimerPlain();
        int tw = killEffectsTimerTextWidthUnscaled(fr, timerLine);
        int th = killEffectsTimerTextHeightUnscaled(fr);
        int tcx0 = tx0 + killEffectsPreviewTimerWidthUnscaled(fr) / 2;
        int tx = (tcx0 + tnx) - tw / 2;
        int ty = y + (rowH - th) / 2 + tny;
        outMinMax[0] = Math.min(iconLeft, tx - 1) - 2;
        outMinMax[1] = Math.min(iconY, ty) - 1;
        outMinMax[2] = Math.max(iconLeft + iconSize, tx + tw) + 2;
        outMinMax[3] = Math.max(iconY + iconSize, ty + th);
    }

    private static int killEffectsIconSizeUnscaled() {
        return Math.max(6, Math.round(POTION_ICON_LARGE * ConfigManager.getMoreHudKillEffectsIconScale()));
    }

    private static int killEffectsTimerTextHeightUnscaled(FontRenderer fr) {
        if (fr == null) {
            return 8;
        }
        return Math.max(6, (int) Math.ceil(fr.FONT_HEIGHT * ConfigManager.getMoreHudKillEffectsTimerScale()));
    }

    private static int killEffectsTimerTextWidthUnscaled(FontRenderer fr, String timerLine) {
        if (fr == null) {
            return 28;
        }
        return Math.max(1, (int) Math.ceil(fr.getStringWidth(timerLine) * ConfigManager.getMoreHudKillEffectsTimerScale()));
    }

    private static int killEffectsPreviewTimerWidthUnscaled(FontRenderer fr) {
        return killEffectsTimerTextWidthUnscaled(fr, BlitzFlameKillEffectTracker.previewOtherKillEffectTimerPlain());
    }

    private static int killEffectsRowHUnscaled(FontRenderer fr) {
        return Math.max(killEffectsIconSizeUnscaled(), killEffectsTimerTextHeightUnscaled(fr) + 2);
    }

    public static void killEffectsIconPartScreenBounds(Minecraft mc, int cx, int cy, float scale, boolean preview,
                                                       int[] out, int[] anchorOut) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = cx - 12;
            out[1] = cy - 12;
            out[2] = 24;
            out[3] = 24;
            anchorOut[0] = cx;
            anchorOut[1] = cy;
            return;
        }
        int iconSize = killEffectsIconSizeUnscaled();
        int inx = ConfigManager.getMoreHudKillEffectsIconNudgeX();
        int iny = ConfigManager.getMoreHudKillEffectsIconNudgeY();
        int rowH = killEffectsRowHUnscaled(fr);
        int iconLeft = (cx - iconSize / 2) + inx;
        int iconY = cy + (rowH - iconSize) / 2 + iny;
        flameCompactUnscaledRectToScreen(cx, cy, scale,
                iconLeft - 2, iconY - 2, iconLeft + iconSize + 2, iconY + iconSize + 2, out);
        anchorOut[0] = screenFromUnscaledPivotX(cx, scale, iconLeft + iconSize / 2);
        anchorOut[1] = screenFromUnscaledPivotY(cy, scale, iconY + iconSize / 2);
    }

    public static void killEffectsTimerPartScreenBounds(Minecraft mc, int cx, int cy, float scale, boolean preview,
                                                        int[] out, int[] anchorOut) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = cx + 8;
            out[1] = cy - 6;
            out[2] = 40;
            out[3] = 14;
            anchorOut[0] = cx + 28;
            anchorOut[1] = cy;
            return;
        }
        KillEffectHudState state = preview ? buildPreviewOtherKillEffectsHudState() : resolveVisibleOtherKillEffectsHudState(mc, false);
        if (state == null) {
            state = buildPreviewOtherKillEffectsHudState();
        }
        int iconSize = killEffectsIconSizeUnscaled();
        int rowH = killEffectsRowHUnscaled(fr);
        int th = killEffectsTimerTextHeightUnscaled(fr);
        int tw = killEffectsTimerTextWidthUnscaled(fr, state.timerPlain);
        int tx0 = (cx - iconSize / 2) + iconSize + 4;
        int tcx0 = tx0 + killEffectsPreviewTimerWidthUnscaled(fr) / 2;
        int tx = (tcx0 + ConfigManager.getMoreHudKillEffectsTimerNudgeX()) - tw / 2;
        int ty = cy + (rowH - th) / 2 + ConfigManager.getMoreHudKillEffectsTimerNudgeY();
        flameCompactUnscaledRectToScreen(cx, cy, scale, tx - 1, ty - 1, tx + tw + 1, ty + th + 1, out);
        anchorOut[0] = screenFromUnscaledPivotX(cx, scale, tx + tw / 2);
        anchorOut[1] = screenFromUnscaledPivotY(cy, scale, ty + th / 2);
    }

    public static void applyKillEffectsIconDragToConfig(Minecraft mc, int screenAnchorX, int screenAnchorY) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudKillEffectsScale();
        int cx = sw / 2 + ConfigManager.getMoreHudKillEffectsOffsetCenterX();
        int cy = ConfigManager.getMoreHudKillEffectsPosY();
        int nux = (int) Math.round(cx + (screenAnchorX - cx) / (double) sc);
        int nuy = (int) Math.round(cy + (screenAnchorY - cy) / (double) sc);
        int rowH = killEffectsRowHUnscaled(fr);
        ConfigManager.setMoreHudKillEffectsIconNudge(nux - cx, nuy - (cy + rowH / 2));
    }

    /** HUD editor arrow keys: nudge kill-effects icon in screen space. */
    public static void nudgeKillEffectsIconByScreen(Minecraft mc, int screenDx, int screenDy) {
        if (screenDx == 0 && screenDy == 0) {
            return;
        }
        float sc = ConfigManager.getMoreHudKillEffectsScale();
        int dx = (int) Math.round(screenDx / (double) sc);
        int dy = (int) Math.round(screenDy / (double) sc);
        ConfigManager.setMoreHudKillEffectsIconNudge(
                ConfigManager.getMoreHudKillEffectsIconNudgeX() + dx,
                ConfigManager.getMoreHudKillEffectsIconNudgeY() + dy);
    }

    public static void applyKillEffectsTimerDragToConfig(Minecraft mc, int screenAnchorX, int screenAnchorY) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudKillEffectsScale();
        int cx = sw / 2 + ConfigManager.getMoreHudKillEffectsOffsetCenterX();
        int cy = ConfigManager.getMoreHudKillEffectsPosY();
        int nux = (int) Math.round(cx + (screenAnchorX - cx) / (double) sc);
        int nuy = (int) Math.round(cy + (screenAnchorY - cy) / (double) sc);
        int iconSize = killEffectsIconSizeUnscaled();
        int rowH = killEffectsRowHUnscaled(fr);
        int tx0 = (cx - iconSize / 2) + iconSize + 4;
        int tw = killEffectsPreviewTimerWidthUnscaled(fr);
        int th = killEffectsTimerTextHeightUnscaled(fr);
        int ty0 = cy + (rowH - th) / 2;
        int tcx0 = tx0 + tw / 2;
        ConfigManager.setMoreHudKillEffectsTimerNudge(nux - tcx0, nuy - (ty0 + th / 2));
    }

    /** HUD editor arrow keys: nudge kill-effects timer in screen space. */
    public static void nudgeKillEffectsTimerByScreen(Minecraft mc, int screenDx, int screenDy) {
        if (screenDx == 0 && screenDy == 0) {
            return;
        }
        float sc = ConfigManager.getMoreHudKillEffectsScale();
        int dx = (int) Math.round(screenDx / (double) sc);
        int dy = (int) Math.round(screenDy / (double) sc);
        ConfigManager.setMoreHudKillEffectsTimerNudge(
                ConfigManager.getMoreHudKillEffectsTimerNudgeX() + dx,
                ConfigManager.getMoreHudKillEffectsTimerNudgeY() + dy);
    }

    /** HUD editor arrow keys: nudge compact flame icon in screen space. */
    public static void nudgeFlameCompactIconByScreen(int screenDx, int screenDy) {
        if (screenDx == 0 && screenDy == 0) {
            return;
        }
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        int dx = (int) Math.round(screenDx / (double) sc);
        int dy = (int) Math.round(screenDy / (double) sc);
        ConfigManager.nudgeMoreHudFlameCompactIcon(dx, dy);
    }

    /** HUD editor arrow keys: nudge compact flame timer in screen space. */
    public static void nudgeFlameCompactTimerByScreen(int screenDx, int screenDy) {
        if (screenDx == 0 && screenDy == 0) {
            return;
        }
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        int dx = (int) Math.round(screenDx / (double) sc);
        int dy = (int) Math.round(screenDy / (double) sc);
        ConfigManager.nudgeMoreHudFlameCompactTimer(dx, dy);
    }

    public static int measureFlameCompactWidth(Minecraft mc, boolean hudEditorPreview) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 64;
        }
        int[] bb = new int[4];
        computeFlameCompactBoundingBox(mc, fr, 0, 0, hudEditorPreview, bb);
        return bb[2] - bb[0];
    }

    public static int measureFlameCompactHeight(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 20;
        }
        int[] bb = new int[4];
        computeFlameCompactBoundingBox(mc, fr, 0, 0, true, bb);
        return bb[3] - bb[1];
    }

    /**
     * Unscaled axis-aligned bounds of compact flame icon + timer after nudges.
     * {@code centerX},{@code y} are the row anchor (same as draw).
     */
    private static void computeFlameCompactBoundingBox(Minecraft mc, FontRenderer fr, int centerX, int y,
                                                       boolean hudEditorPreview, int[] outMinMax) {
        KillEffectHudState state = resolveVisibleKillEffectHudState(mc, hudEditorPreview);
        computeFlameCompactBoundingBox(mc, fr, centerX, y, state, outMinMax);
    }

    private static int flameCompactPreviewTimerWidthUnscaled(FontRenderer fr) {
        return flameCompactTimerTextWidthUnscaled(fr, buildPreviewKillEffectHudState().timerPlain);
    }

    /** Row height for compact flame layout (matches Kill effects row height pattern). */
    private static int flameCompactRowHUnscaled(FontRenderer fr, KillEffectHudState state) {
        if (fr == null) {
            return POTION_ICON_LARGE;
        }
        int iconSize = flameCompactIconSizeUnscaled();
        if (state != null && isKillEffectTimerBarActive(state)) {
            return Math.max(iconSize, flameCompactBarHUnscaled()) + 2;
        }
        return Math.max(iconSize, flameCompactTimerTextHeightUnscaled(fr)) + 2;
    }

    /** Shared compact Flaming Arrows HUD geometry (draw + background + editor bounds). */
    private static final class FlameCompactLayout {
        int contentRowH;
        int rowLeft;
        int rowContentW;
        /** Timer/bar column center before timer nudge X (seconds + bar share this slot). */
        int timerSlotCenterX;
        int iconLeft;
        int iconY;
        int iconSize;
        int timerTx;
        int timerTy;
        int timerTw;
        int timerTh;
        int barLeft;
        int barY;
        int barW;
        int barH;
        int bgLeft;
        int bgTop;
        int bgRight;
        int bgBottom;
        boolean barMode;
    }

    private static FlameCompactLayout layoutFlameCompactRow(FontRenderer fr, int centerX, int y,
                                                            KillEffectHudState state) {
        return layoutFlameCompactRow(fr, centerX, y, state,
                ConfigManager.getMoreHudFlameCompactIconNudgeX(),
                ConfigManager.getMoreHudFlameCompactIconNudgeY(),
                ConfigManager.getMoreHudFlameCompactTimerNudgeX(),
                ConfigManager.getMoreHudFlameCompactTimerNudgeY());
    }

    private static FlameCompactLayout layoutFlameCompactRow(FontRenderer fr, int centerX, int y,
                                                            KillEffectHudState state,
                                                            int iconNx, int iconNy, int timerNx, int timerNy) {
        FlameCompactLayout l = new FlameCompactLayout();
        if (state == null) {
            state = buildPreviewKillEffectHudState();
        }
        l.barMode = isKillEffectTimerBarActive(state);
        l.iconSize = flameCompactIconSizeUnscaled();
        int I = l.iconSize;
        int inx = iconNx;
        int iny = iconNy;
        int tnx = timerNx;
        int tny = timerNy;
        String timerLine = state.timerPlain != null ? state.timerPlain : "";
        int th = 0;
        int tw = 0;
        int textW = flameCompactTimerTextWidthUnscaled(fr, timerLine);
        int columnW = Math.max(Math.max(flameCompactPreviewTimerWidthUnscaled(fr), textW),
                flameCompactBarWUnscaled());
        if (l.barMode) {
            l.barW = flameCompactBarWUnscaled();
            l.barH = flameCompactBarHUnscaled();
        } else {
            th = flameCompactTimerTextHeightUnscaled(fr);
            int halfW = flameCompactTimerScaledHalfWidthUnscaled(fr, timerLine);
            tw = halfW * 2;
            l.timerTw = tw;
            l.timerTh = th;
        }
        l.rowContentW = I + 4 + columnW;
        l.rowLeft = centerX - l.rowContentW / 2;
        int tx0 = l.rowLeft + I + 4;
        l.timerSlotCenterX = tx0 + columnW / 2;
        int timerCenterX = l.timerSlotCenterX + tnx;
        l.iconLeft = l.rowLeft + inx;
        if (l.barMode) {
            l.barLeft = timerCenterX - l.barW / 2;
        } else {
            l.timerTx = timerCenterX - tw / 2;
        }
        l.contentRowH = flameCompactRowHUnscaled(fr, state);
        int iconY0 = y + (l.contentRowH - I) / 2;
        l.iconY = iconY0 + iny;
        if (l.barMode) {
            l.barY = y + (l.contentRowH - l.barH) / 2 + tny;
        } else {
            l.timerTy = y + (l.contentRowH - th) / 2 + tny;
        }
        int minX = l.rowLeft;
        int maxX = l.rowLeft + l.rowContentW;
        int minY = y;
        int maxY = y + l.contentRowH;
        minX = Math.min(minX, l.iconLeft);
        maxX = Math.max(maxX, l.iconLeft + I);
        minY = Math.min(minY, l.iconY);
        maxY = Math.max(maxY, l.iconY + I);
        if (l.barMode) {
            minX = Math.min(minX, l.barLeft);
            maxX = Math.max(maxX, l.barLeft + l.barW);
            minY = Math.min(minY, l.barY);
            maxY = Math.max(maxY, l.barY + l.barH);
        } else {
            minX = Math.min(minX, l.timerTx);
            maxX = Math.max(maxX, l.timerTx + tw);
            minY = Math.min(minY, l.timerTy);
            maxY = Math.max(maxY, l.timerTy + th);
        }
        HudOpacitySliderBoxInsets.TextBackgroundPad pad =
                HudOpacitySliderBoxInsets.splitCenteredTextRowPadSigned(HudOpacitySliderBoxInsets.Module.FLAME,
                        ConfigManager.isMoreHudFlameShadow());
        l.bgLeft = minX - pad.left;
        l.bgTop = minY - pad.up;
        l.bgRight = maxX + pad.right;
        l.bgBottom = maxY + pad.down;
        return l;
    }

    /**
     * Screen bounds of the compact flame row content (icon + timer column), for HUD editor selection.
     * {@code out}: left, top, width, height.
     */
    public static void flameCompactRowContentScreenBounds(Minecraft mc, int centerX, int rowTopY, float scale,
                                                          boolean hudEditorPreview, int[] out) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = centerX - 40;
            out[1] = rowTopY - 2;
            out[2] = 80;
            out[3] = 24;
            return;
        }
        KillEffectHudState state = resolveVisibleKillEffectHudState(mc, hudEditorPreview);
        if (state == null) {
            state = buildPreviewKillEffectHudState();
        }
        FlameCompactLayout l = layoutFlameCompactRow(fr, centerX, rowTopY, state);
        int pad = 2;
        flameCompactUnscaledRectToScreen(centerX, rowTopY, scale,
                l.rowLeft - pad, rowTopY - pad, l.rowLeft + l.rowContentW + pad, rowTopY + l.contentRowH + pad, out);
    }

    private static void computeFlameCompactBoundingBox(Minecraft mc, FontRenderer fr, int centerX, int y,
                                                       KillEffectHudState state, int[] outMinMax) {
        if (fr == null) {
            outMinMax[0] = centerX - 24;
            outMinMax[1] = y - 2;
            outMinMax[2] = centerX + 24;
            outMinMax[3] = y + 16;
            return;
        }
        FlameCompactLayout l = layoutFlameCompactRow(fr, centerX, y, state);
        outMinMax[0] = l.bgLeft;
        outMinMax[1] = l.bgTop;
        outMinMax[2] = l.bgRight;
        outMinMax[3] = l.bgBottom;
    }

    public static int flameCompactIconSizeUnscaled() {
        return Math.max(6, Math.round(POTION_ICON_LARGE * ConfigManager.getMoreHudFlameCompactIconScale()));
    }

    private static int flameCompactBarWUnscaled() {
        return Math.max(8, Math.round(FLAME_PROC_BAR_W * ConfigManager.getMoreHudFlameCompactTimerScale()));
    }

    private static int flameCompactBarHUnscaled() {
        return Math.max(2, Math.round(FLAME_PROC_BAR_H * ConfigManager.getMoreHudFlameCompactTimerScale()));
    }

    private static int flameCompactTimerTextHeightUnscaled(FontRenderer fr) {
        if (fr == null) {
            return 8;
        }
        return Math.max(6, (int) Math.ceil(fr.FONT_HEIGHT * ConfigManager.getMoreHudFlameCompactTimerScale()));
    }

    private static int flameCompactTimerTextWidthUnscaled(FontRenderer fr, String durLine) {
        return flameCompactTimerScaledHalfWidthUnscaled(fr, durLine) * 2;
    }

    /** Half of scaled timer text width (matches GL draw + HUD editor hit box). */
    private static int flameCompactTimerScaledHalfWidthUnscaled(FontRenderer fr, String durLine) {
        if (fr == null) {
            return 20;
        }
        String line = durLine != null ? durLine : "";
        return Math.max(1, (int) Math.ceil(fr.getStringWidth(line) * ConfigManager.getMoreHudFlameCompactTimerScale() / 2.0));
    }

    private static int screenFromUnscaledPivotX(int pivotX, float scale, int ux) {
        return (int) Math.round(pivotX + (ux - (double) pivotX) * (double) scale);
    }

    private static int screenFromUnscaledPivotY(int pivotY, float scale, int uy) {
        return (int) Math.round(pivotY + (uy - (double) pivotY) * (double) scale);
    }

    /** Maps an unscaled axis-aligned rect (same pivot as compact row draw) to screen pixels. */
    private static void flameCompactUnscaledRectToScreen(int cx, int cy, float scale,
                                                         int uLeft, int uTop, int uRight, int uBottom,
                                                         int[] outLeftTopWidHei) {
        int sx0 = screenFromUnscaledPivotX(cx, scale, uLeft);
        int sx1 = screenFromUnscaledPivotX(cx, scale, uRight);
        int sy0 = screenFromUnscaledPivotY(cy, scale, uTop);
        int sy1 = screenFromUnscaledPivotY(cy, scale, uBottom);
        int sl = Math.min(sx0, sx1);
        int sr = Math.max(sx0, sx1);
        int st = Math.min(sy0, sy1);
        int sb = Math.max(sy0, sy1);
        outLeftTopWidHei[0] = sl;
        outLeftTopWidHei[1] = st;
        outLeftTopWidHei[2] = Math.max(1, sr - sl);
        outLeftTopWidHei[3] = Math.max(1, sb - st);
    }

    /**
     * HUD Editor hit box for the compact flame icon (includes small padding).
     * {@code out}: left, top, width, height; {@code anchorOut}: screen position of drag anchor (icon center).
     */
    public static void flameCompactIconPartScreenBounds(Minecraft mc, int cx, int cy, float scale, boolean preview,
                                                        int[] out, int[] anchorOut) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = cx - 12;
            out[1] = cy - 12;
            out[2] = 24;
            out[3] = 24;
            anchorOut[0] = cx;
            anchorOut[1] = cy;
            return;
        }
        KillEffectHudState state = preview ? buildPreviewKillEffectHudState() : resolveVisibleKillEffectHudState(mc, false);
        if (state == null) {
            state = buildPreviewKillEffectHudState();
        }
        FlameCompactLayout l = layoutFlameCompactRow(fr, cx, cy, state);
        int iconSize = l.iconSize;
        int iconLeft = l.iconLeft;
        int iconY = l.iconY;
        flameCompactUnscaledRectToScreen(cx, cy, scale,
                iconLeft - 2, iconY - 2, iconLeft + iconSize + 2, iconY + iconSize + 2, out);
        shrinkScreenBounds(out, 2, 2);
        anchorOut[0] = screenFromUnscaledPivotX(cx, scale, iconLeft + iconSize / 2);
        anchorOut[1] = screenFromUnscaledPivotY(cy, scale, iconY + iconSize / 2);
    }

    /** HUD Editor hit box for compact timer text or depleting bar. */
    public static void flameCompactTimerPartScreenBounds(Minecraft mc, int cx, int cy, float scale, boolean preview,
                                                         int[] out, int[] anchorOut) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = cx + 8;
            out[1] = cy - 6;
            out[2] = 60;
            out[3] = 12;
            anchorOut[0] = cx + 38;
            anchorOut[1] = cy;
            return;
        }
        KillEffectHudState state = preview ? buildPreviewKillEffectHudState() : resolveVisibleKillEffectHudState(mc, false);
        if (state == null) {
            state = buildPreviewKillEffectHudState();
        }
        FlameCompactLayout l = layoutFlameCompactRow(fr, cx, cy, state);
        if (isKillEffectTimerBarActive(state)) {
            flameCompactUnscaledRectToScreen(cx, cy, scale, l.barLeft - 1, l.barY - 1, l.barLeft + l.barW + 1,
                    l.barY + l.barH + 1, out);
            anchorOut[0] = screenFromUnscaledPivotX(cx, scale, l.barLeft + l.barW / 2);
            anchorOut[1] = screenFromUnscaledPivotY(cy, scale, l.barY + l.barH / 2);
        } else {
            flameCompactUnscaledRectToScreen(cx, cy, scale, l.timerTx - 1, l.timerTy - 1,
                    l.timerTx + l.timerTw + 1, l.timerTy + l.timerTh + 1, out);
            shrinkScreenBoundsVertically(out, 2);
            anchorOut[0] = screenFromUnscaledPivotX(cx, scale, l.timerTx + l.timerTw / 2);
            anchorOut[1] = screenFromUnscaledPivotY(cy, scale, l.timerTy + l.timerTh / 2);
        }
    }

    private static void shrinkScreenBoundsVertically(int[] bounds, int pxEachSide) {
        shrinkScreenBounds(bounds, 0, pxEachSide);
    }

    private static void shrinkScreenBounds(int[] bounds, int pxXEachSide, int pxYEachSide) {
        if (bounds == null || bounds.length < 4 || (pxXEachSide <= 0 && pxYEachSide <= 0)) {
            return;
        }
        int shrinkX = Math.min(Math.max(0, pxXEachSide), Math.max(0, (bounds[2] - 1) / 2));
        int shrinkY = Math.min(Math.max(0, pxYEachSide), Math.max(0, (bounds[3] - 1) / 2));
        bounds[0] += shrinkX;
        bounds[1] += shrinkY;
        bounds[2] = Math.max(1, bounds[2] - shrinkX * 2);
        bounds[3] = Math.max(1, bounds[3] - shrinkY * 2);
    }

    /**
     * Apply HUD Editor drag for the whole compact row: {@code (screenX, screenY)} is the union center of icon + timer/bar.
     */
    public static void applyFlameCompactRowDragToConfig(Minecraft mc, int screenUnionCenterX, int screenUnionCenterY) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        ConfigManager.setMoreHudFlameCompactOffsetCenterX(screenUnionCenterX - sw / 2);
        int cx = screenUnionCenterX;
        int rowTop = ConfigManager.getMoreHudFlameCompactPosY();
        for (int pass = 0; pass < 6; pass++) {
            int[] iconB = new int[4];
            int[] timerB = new int[4];
            int[] ignore1 = new int[2];
            int[] ignore2 = new int[2];
            flameCompactIconPartScreenBounds(mc, cx, rowTop, sc, true, iconB, ignore1);
            flameCompactTimerPartScreenBounds(mc, cx, rowTop, sc, true, timerB, ignore2);
            int top = Math.min(iconB[1], timerB[1]);
            int bottom = Math.max(iconB[1] + iconB[3], timerB[1] + timerB[3]);
            rowTop += screenUnionCenterY - (top + bottom) / 2;
        }
        ConfigManager.setMoreHudFlameCompactPosY(rowTop);
    }

    /** Apply HUD Editor drag: screen position of icon center → icon nudges (timer nudge clamped). */
    public static void applyFlameCompactIconDragToConfig(Minecraft mc, int screenAnchorX, int screenAnchorY) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        int cx = sw / 2 + ConfigManager.getMoreHudFlameCompactOffsetCenterX();
        int cy = ConfigManager.getMoreHudFlameCompactPosY();
        int nux = (int) Math.round(cx + (screenAnchorX - cx) / (double) sc);
        int nuy = (int) Math.round(cy + (screenAnchorY - cy) / (double) sc);
        KillEffectHudState state = resolveVisibleKillEffectHudState(mc, false);
        if (state == null) {
            state = buildPreviewKillEffectHudState();
        }
        FlameCompactLayout base = layoutFlameCompactRow(fr, cx, cy, state, 0, 0, 0, 0);
        ConfigManager.setMoreHudFlameCompactIconNudge(
                nux - (base.iconLeft + base.iconSize / 2),
                nuy - (base.iconY + base.iconSize / 2));
    }

    /** Apply HUD Editor drag: screen position of timer/bar center → timer nudges (clamp vs icon). */
    public static void applyFlameCompactTimerDragToConfig(Minecraft mc, int screenAnchorX, int screenAnchorY) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        int cx = sw / 2 + ConfigManager.getMoreHudFlameCompactOffsetCenterX();
        int cy = ConfigManager.getMoreHudFlameCompactPosY();
        int nux = (int) Math.round(cx + (screenAnchorX - cx) / (double) sc);
        int nuy = (int) Math.round(cy + (screenAnchorY - cy) / (double) sc);
        KillEffectHudState state = buildPreviewKillEffectHudState();
        FlameCompactLayout base = layoutFlameCompactRow(fr, cx, cy, state, 0, 0, 0, 0);
        if (isKillEffectTimerBarActive(state)) {
            ConfigManager.setMoreHudFlameCompactTimerNudge(
                    nux - (base.barLeft + base.barW / 2),
                    nuy - (base.barY + base.barH / 2));
        } else {
            ConfigManager.setMoreHudFlameCompactTimerNudge(
                    nux - (base.timerTx + base.timerTw / 2),
                    nuy - (base.timerTy + base.timerTh / 2));
        }
    }

    /**
     * Screen bounds for compact Flaming Arrows (same pivot as Kill effects: {@code (centerX, rowTopY)}).
     */
    public static void flameCompactRowScreenBounds(Minecraft mc, int centerX, int rowTopY, float scale,
                                                   boolean hudEditorPreview, int[] out) {
        flameCompactScreenBounds(mc, centerX, rowTopY, scale, hudEditorPreview, out);
    }

    private static void flameMergedEffectsRowScreenBounds(Minecraft mc, FontRenderer fr, int centerX, int rowTopY,
                                                          float scale, boolean preview, int[] out) {
        if (fr == null) {
            out[0] = centerX - 40;
            out[1] = rowTopY - 2;
            out[2] = 80;
            out[3] = 24;
            return;
        }
        KillEffectHudState state = resolveVisibleKillEffectHudState(mc, preview);
        if (state == null) {
            state = buildPreviewKillEffectHudState();
        }
        int rowH = potionRowUnscaledHeight(fr);
        int left = centerX - POTION_ICON_LARGE / 2;
        int textColW = layoutPotionTextColumnWidth(fr, resolvePotionDrawList(mc, preview), preview, true);
        int blockW = POTION_ICON_LARGE + 4 + textColW;
        int bb0 = left;
        int bb1 = rowTopY;
        int bb2 = left + blockW;
        int bb3 = rowTopY + rowH;
        out[0] = screenFromUnscaledPivotX(centerX, scale, bb0);
        out[1] = screenFromUnscaledPivotY(rowTopY, scale, bb1);
        out[2] = Math.max(1, screenFromUnscaledPivotX(centerX, scale, bb2) - out[0]);
        out[3] = Math.max(1, screenFromUnscaledPivotY(rowTopY, scale, bb3) - out[1]);
    }

    /** Screen bounds for compact flame row (pivot = row anchor {@code (centerX, startY)}). */
    public static void flameCompactScreenBounds(Minecraft mc, int centerX, int startY, float scale,
                                                boolean hudEditorPreview, int[] out) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = centerX - 40;
            out[1] = startY - 2;
            out[2] = 80;
            out[3] = 24;
            return;
        }
        int[] bb = new int[4];
        computeFlameCompactBoundingBox(mc, fr, centerX, startY, hudEditorPreview, bb);
        int uw = bb[2] - bb[0];
        int uh = bb[3] - bb[1];
        out[0] = (int) Math.round(centerX + (bb[0] - centerX) * scale);
        out[1] = (int) Math.round(startY + (bb[1] - startY) * scale);
        out[2] = Math.max(1, (int) Math.ceil(uw * scale));
        out[3] = Math.max(1, (int) Math.ceil(uh * scale));
    }

    private static void drawFlameCompactRow(Minecraft mc, FontRenderer fr, int anchorX, int anchorY, float scale,
                                            KillEffectHudState state) {
        drawFlameCompactRow(mc, fr, anchorX, anchorY, scale, state, false);
    }

    /**
     * Draw compact Flaming Arrows HUD in screen space so background, icon, bar, and timer share one transform.
     */
    private static void drawFlameCompactRow(Minecraft mc, FontRenderer fr, int anchorX, int anchorY, float scale,
                                            KillEffectHudState state, boolean skipBackground) {
        if (state == null || fr == null) {
            return;
        }
        HudGlState.restoreForOverlayText();
        FlameCompactLayout l = layoutFlameCompactRow(fr, anchorX, anchorY, state);
        int bg = ConfigManager.getMoreHudFlameBg();
        if (bg > 0 && !skipBackground) {
            int sl = screenFromUnscaledPivotX(anchorX, scale, l.bgLeft);
            int st = screenFromUnscaledPivotY(anchorY, scale, l.bgTop);
            int sr = screenFromUnscaledPivotX(anchorX, scale, l.bgRight);
            int sb = screenFromUnscaledPivotY(anchorY, scale, l.bgBottom);
            HudEditorBackgroundMask.drawRect(Math.min(sl, sr), Math.min(st, sb), Math.max(sl, sr), Math.max(st, sb),
                    MoreHudBackgroundColors.flamePixel(bg));
        }
        int iconSl = screenFromUnscaledPivotX(anchorX, scale, l.iconLeft);
        int iconSt = screenFromUnscaledPivotY(anchorY, scale, l.iconY);
        int iconSizeScreen = Math.max(1, Math.round(l.iconSize * scale));
        drawFlameKillEffectIcon(mc, state.iconTexture, iconSl, iconSt, iconSizeScreen);
        HudGlState.restoreForOverlayText();
        if (l.barMode) {
            int barSl = screenFromUnscaledPivotX(anchorX, scale, l.barLeft);
            int barSt = screenFromUnscaledPivotY(anchorY, scale, l.barY);
            int barWScreen = Math.max(1, Math.round(l.barW * scale));
            int barHScreen = Math.max(1, Math.round(l.barH * scale));
            int barRgb = flameProcBarRgbForHud(state.textRgb);
            drawFlameProcDepleteBar(barSl, barSt, barWScreen, barHScreen, state.timerBarFill, barRgb);
        } else {
            String durLine = state.timerPlain != null ? state.timerPlain : "";
            int centerX = screenFromUnscaledPivotX(anchorX, scale, l.timerTx + l.timerTw / 2);
            int centerY = screenFromUnscaledPivotY(anchorY, scale, l.timerTy + l.timerTh / 2);
            float textScale = ConfigManager.getMoreHudFlameCompactTimerScale() * scale;
            GlStateManager.pushMatrix();
            GlStateManager.translate(centerX, centerY, 0);
            GlStateManager.scale(textScale, textScale, 1.0f);
            int rawW = fr.getStringWidth(durLine);
            int drawX = -rawW / 2;
            int drawY = -fr.FONT_HEIGHT / 2;
            int timerCol = 0xFF000000 | (state.textRgb & 0xFFFFFF);
            if (ConfigManager.isMoreHudFlameShadow()) {
                fr.drawStringWithShadow(durLine, drawX, drawY, timerCol);
            } else {
                fr.drawString(durLine, drawX, drawY, timerCol);
            }
            GlStateManager.popMatrix();
        }
    }

    private static void drawFlameProcDepleteBar(int x, int y, int barW, int barH, float fill01, int rgb) {
        fill01 = Math.max(0f, Math.min(1f, fill01));
        int border = 0x60000000;
        int track = 0xD0101010;
        int fillCol = 0xFF000000 | (rgb & 0xFFFFFF);
        Gui.drawRect(x, y, x + barW, y + barH, track);
        int fw = (int) Math.ceil((double) barW * (double) fill01);
        if (fw > 0) {
            Gui.drawRect(x, y, x + fw, y + barH, fillCol);
        }
        if (barW > 1 && barH > 1) {
            Gui.drawRect(x, y, x + barW, y + 1, border);
            Gui.drawRect(x, y + barH - 1, x + barW, y + barH, border);
            Gui.drawRect(x, y, x + 1, y + barH, border);
            Gui.drawRect(x + barW - 1, y, x + barW, y + barH, border);
        }
    }

    private static void drawFlameStandaloneDepleteRow(Minecraft mc, FontRenderer fr, int cx, int y, KillEffectHudState state) {
        if (state == null) {
            return;
        }
        HudGlState.restoreForOverlayText();
        HudOpacitySliderBoxInsets.TextBackgroundPad pad =
                HudOpacitySliderBoxInsets.splitCenteredTextRowPadSigned(HudOpacitySliderBoxInsets.Module.FLAME,
                        ConfigManager.isMoreHudFlameShadow());
        int I = POTION_ICON_LARGE;
        int fh = fr.FONT_HEIGHT;
        String timerPlain = state.timerPlain != null ? state.timerPlain : "";
        int twTimer = fr.getStringWidth(EnumChatFormatting.getTextWithoutFormattingCodes(timerPlain));
        int nw = fr.getStringWidth(state.displayName);
        int innerW = Math.max(Math.max(nw, twTimer), FLAME_PROC_BAR_W);
        int blockW = I + 4 + innerW;
        int rowH = Math.max(I, fh + 1 + fh + 1 + FLAME_PROC_BAR_H + 4);
        int left = cx - blockW / 2;
        int bg = ConfigManager.getMoreHudFlameBg();
        if (bg > 0) {
            HudEditorBackgroundMask.drawRect(left - pad.left, y - pad.up, left + blockW + pad.right, y + rowH + pad.down,
                    MoreHudBackgroundColors.flamePixel(bg));
        }
        int iconY = y + (rowH - I) / 2;
        drawFlameKillEffectIcon(mc, state.iconTexture, left, iconY, I);
        HudGlState.restoreForOverlayText();
        int tx = left + I + 4;
        int textY = y + (rowH - (fh + 1 + fh + 1 + FLAME_PROC_BAR_H)) / 2;
        int nameCol = 0xFF000000 | (state.textRgb & 0xFFFFFF);
        if (ConfigManager.isMoreHudFlameShadow()) {
            fr.drawStringWithShadow(state.displayName, tx, textY, nameCol);
        } else {
            fr.drawString(state.displayName, tx, textY, nameCol);
        }
        int durY = textY + fh + 1;
        int durCenterX = tx + twTimer / 2;
        int bx = durCenterX - FLAME_PROC_BAR_W / 2;
        int by = durY + fh + 1;
        drawFlameProcDepleteBar(bx, by, FLAME_PROC_BAR_W, FLAME_PROC_BAR_H, state.timerBarFill,
                flameProcBarRgbForHud(state.textRgb));
    }

    /** Standalone Flaming Arrows row when the depleting bar is off: icon + single timer line (same text as vanilla proc). */
    private static void drawFlameStandaloneSimpleRow(Minecraft mc, FontRenderer fr, int cx, int y, KillEffectHudState state) {
        if (state == null) {
            return;
        }
        HudGlState.restoreForOverlayText();
        HudOpacitySliderBoxInsets.TextBackgroundPad pad =
                HudOpacitySliderBoxInsets.splitCenteredTextRowPadSigned(HudOpacitySliderBoxInsets.Module.FLAME,
                        ConfigManager.isMoreHudFlameShadow());
        int I = POTION_ICON_LARGE;
        String line = state.fullLinePlain;
        int tw = fr.getStringWidth(line);
        int total = I + 4 + tw;
        int left = cx - total / 2;
        int rowH = Math.max(I, fr.FONT_HEIGHT + 2);
        int bg = ConfigManager.getMoreHudFlameBg();
        if (bg > 0) {
            HudEditorBackgroundMask.drawRect(left - pad.left, y - pad.up, left + total + pad.right, y + rowH + pad.down,
                    MoreHudBackgroundColors.flamePixel(bg));
        }
        int iconY = y + (rowH - I) / 2;
        drawFlameKillEffectIcon(mc, state.iconTexture, left, iconY, I);
        HudGlState.restoreForOverlayText();
        int textCol = 0xFF000000 | (state.textRgb & 0xFFFFFF);
        int tx = left + I + 4;
        int ty = y + (rowH - fr.FONT_HEIGHT) / 2;
        if (ConfigManager.isMoreHudFlameShadow()) {
            fr.drawStringWithShadow(line, tx, ty, textCol);
        } else {
            fr.drawString(line, tx, ty, textCol);
        }
    }

    private static void renderFpsRowAt(Minecraft mc, FontRenderer fr, int centerX, int centerY, float scale) {
        drawWithRowScale(centerX, centerY, scale, () -> drawFpsRow(mc, fr, centerX, centerY));
    }

    private static int drawFpsRow(Minecraft mc, FontRenderer fr, int centerX, int y) {
        String[] parts = fpsRowParts(mc);
        return drawSplitCenteredRow(mc, fr, centerX, y, parts[0], parts[1],
                skipFpsRowBackground ? 0 : MoreHudBackgroundColors.fpsPixel(ConfigManager.getMoreHudFpsBg()),
                ConfigManager.getMoreHudFpsPrefixColor(), ConfigManager.getMoreHudFpsColor(),
                ConfigManager.isMoreHudFpsShadow(), HudOpacitySliderBoxInsets.Module.FPS);
    }

    private static int drawPingRow(Minecraft mc, FontRenderer fr, int centerX, int y) {
        String[] parts = pingRowParts(mc);
        return drawSplitCenteredRow(mc, fr, centerX, y, parts[0], parts[1],
                skipPingRowBackground ? 0 : MoreHudBackgroundColors.pingPixel(ConfigManager.getMoreHudPingBg()),
                ConfigManager.getMoreHudPingPrefixColor(), ConfigManager.getMoreHudPingColor(),
                ConfigManager.isMoreHudPingShadow(), HudOpacitySliderBoxInsets.Module.PING);
    }

    private static int drawCpsRow(Minecraft mc, FontRenderer fr, int centerX, int y) {
        return drawCpsRowColored(mc, fr, centerX, y, MoreHudCpsTracker.getLeftCps(), MoreHudCpsTracker.getRightCps());
    }

    private static int drawCpsRowColored(Minecraft mc, FontRenderer fr, int centerX, int y, int leftCps, int rightCps) {
        int mode = ConfigManager.getMoreHudCpsMode();
        boolean brackets = ConfigManager.isMoreHudCpsSquareBrackets();
        int bg = ConfigManager.getMoreHudCpsBg();
        int structCol = 0xFF000000 | (ConfigManager.getMoreHudCpsPrefixColor() & 0xFFFFFF);
        int sepCol = 0xFF000000 | (ConfigManager.getMoreHudCpsSeparatorColor() & 0xFFFFFF);
        int digitCol = 0xFF000000 | (ConfigManager.getMoreHudCpsColor() & 0xFFFFFF);
        boolean shadow = ConfigManager.isMoreHudCpsShadow();
        String l = String.valueOf(leftCps);
        String r = String.valueOf(rightCps);
        int bracketW = brackets ? fr.getStringWidth("[") + fr.getStringWidth("]") : 0;
        int fullW;
        if (mode == 0) {
            fullW = fr.getStringWidth(l) + bracketW;
        } else if (mode == 1) {
            fullW = fr.getStringWidth(r) + bracketW;
        } else {
            fullW = fr.getStringWidth(l) + fr.getStringWidth(" | ") + fr.getStringWidth(r) + bracketW;
        }
        int x = centerX - fullW / 2;
        if (bg > 0 && !skipCpsRowBackground) {
            HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                    HudOpacitySliderBoxInsets.Module.CPS, shadow);
            HudEditorBackgroundMask.drawRect(x - pad.left, y - pad.up, x + fullW + pad.right, y + fr.FONT_HEIGHT + pad.down,
                    MoreHudBackgroundColors.cpsPixel(bg));
        }
        if (mode == 0) {
            if (brackets) {
                x = drawHudText(fr, "[", x, y, structCol, shadow);
            }
            x = drawHudText(fr, l, x, y, digitCol, shadow);
            if (brackets) {
                drawHudText(fr, "]", x, y, structCol, shadow);
            }
        } else if (mode == 1) {
            if (brackets) {
                x = drawHudText(fr, "[", x, y, structCol, shadow);
            }
            x = drawHudText(fr, r, x, y, digitCol, shadow);
            if (brackets) {
                drawHudText(fr, "]", x, y, structCol, shadow);
            }
        } else {
            if (brackets) {
                x = drawHudText(fr, "[", x, y, structCol, shadow);
            }
            x = drawHudText(fr, l, x, y, digitCol, shadow);
            x = drawHudText(fr, " | ", x, y, sepCol, shadow);
            x = drawHudText(fr, r, x, y, digitCol, shadow);
            if (brackets) {
                drawHudText(fr, "]", x, y, structCol, shadow);
            }
        }
        return y + fr.FONT_HEIGHT;
    }

    private static int drawSplitCenteredRow(Minecraft mc, FontRenderer fr, int centerX, int y, String prefix,
            String value, int bgPixel, int prefixRgb, int valueRgb, boolean shadow) {
        return drawSplitCenteredRow(mc, fr, centerX, y, prefix, value, bgPixel, prefixRgb, valueRgb, shadow, null);
    }

    /**
     * When {@code insetPadModule} is non-null (FPS row), background padding follows
     * {@link HudOpacitySliderBoxInsets} so {@code /hudopacitybox fps …} matches the on-screen HUD box.
     */
    private static int drawSplitCenteredRow(Minecraft mc, FontRenderer fr, int centerX, int y, String prefix,
            String value, int bgPixel, int prefixRgb, int valueRgb, boolean shadow,
            HudOpacitySliderBoxInsets.Module insetPadModule) {
        int fullW = fr.getStringWidth(prefix + value);
        int x = centerX - fullW / 2;
        if (bgPixel != 0) {
            HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                    insetPadModule, shadow);
            HudEditorBackgroundMask.drawRect(x - pad.left, y - pad.up, x + fullW + pad.right, y + fr.FONT_HEIGHT + pad.down, bgPixel);
        }
        int prefixCol = 0xFF000000 | (prefixRgb & 0xFFFFFF);
        int valueCol = 0xFF000000 | (valueRgb & 0xFFFFFF);
        if (!prefix.isEmpty()) {
            x = drawHudText(fr, prefix, x, y, prefixCol, shadow);
        }
        drawHudText(fr, value, x, y, valueCol, shadow);
        return y + fr.FONT_HEIGHT;
    }

    private static int drawHudText(FontRenderer fr, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            fr.drawStringWithShadow(text, x, y, color);
        } else {
            fr.drawString(text, x, y, color);
        }
        return x + fr.getStringWidth(text);
    }

    /** FPS format samples for the color settings preview band (presets + custom when active). */
    public static void renderFpsColorGallerySamples(Minecraft mc, FontRenderer fr, int centerX, int centerY) {
        if (fr == null) {
            return;
        }
        int lineH = fr.FONT_HEIGHT + 3;
        int[] presets = new int[] { 0, 1, 2, 4 };
        int lines = ConfigManager.getMoreHudFpsDisplayStyle() == 3 ? presets.length + 1 : presets.length;
        int y = centerY - (lines - 1) * lineH / 2;
        for (int style : presets) {
            String[] parts = fpsRowPartsForStyle(mc, style);
            drawSplitCenteredRow(mc, fr, centerX, y, parts[0], parts[1], 0,
                    ConfigManager.getMoreHudFpsPrefixColor(), ConfigManager.getMoreHudFpsColor(),
                    ConfigManager.isMoreHudFpsShadow());
            y += lineH;
        }
        if (ConfigManager.getMoreHudFpsDisplayStyle() == 3) {
            String[] parts = fpsRowParts(mc);
            drawSplitCenteredRow(mc, fr, centerX, y, parts[0], parts[1], 0,
                    ConfigManager.getMoreHudFpsPrefixColor(), ConfigManager.getMoreHudFpsColor(),
                    ConfigManager.isMoreHudFpsShadow());
        }
    }

    /** Three ping format samples for the color settings preview band. */
    public static void renderPingColorGallerySamples(Minecraft mc, FontRenderer fr, int centerX, int centerY) {
        if (fr == null) {
            return;
        }
        int lineH = fr.FONT_HEIGHT + 3;
        int lines = ConfigManager.getMoreHudPingDisplayStyle() == 3 ? 4 : 3;
        int y = centerY - (lines - 1) * lineH / 2;
        for (int style = 0; style < 3; style++) {
            String[] parts = pingRowPartsForStyle(style, 20);
            drawSplitCenteredRow(mc, fr, centerX, y, parts[0], parts[1], 0,
                    ConfigManager.getMoreHudPingPrefixColor(), ConfigManager.getMoreHudPingColor(),
                    ConfigManager.isMoreHudPingShadow());
            y += lineH;
        }
        if (ConfigManager.getMoreHudPingDisplayStyle() == 3) {
            String[] parts = pingRowPartsForStyle(3, 20);
            drawSplitCenteredRow(mc, fr, centerX, y, parts[0], parts[1], 0,
                    ConfigManager.getMoreHudPingPrefixColor(), ConfigManager.getMoreHudPingColor(),
                    ConfigManager.isMoreHudPingShadow());
        }
    }

    private static final int POTIONS_GALLERY_ICON = 12;
    private static final int POTIONS_GALLERY_ROW_H = 20;

    /**
     * Sample duration (ticks) shown beside each effect in Effects HUD color settings.
     * {@code hudEditorPreview=false} so infinite effects display {@code **:**}.
     */
    public static int potionsColorSettingsPreviewTicks(int potionId) {
        BlitzStarEffectsHudTracker.Effect blitz = BlitzStarEffectsHudTracker.effectForColorSlot(potionId);
        if (blitz != null) {
            return blitz.durationTicks();
        }
        if (potionId == Potion.regeneration.id) {
            return 10 * 20;
        }
        if (potionId == Potion.resistance.id) {
            return 14 * 20;
        }
        if (potionId == Potion.moveSlowdown.id) {
            return 10 * 20;
        }
        if (potionId == Potion.absorption.id) {
            return 2 * 60 * 20;
        }
        if (potionId == Potion.invisibility.id) {
            return 10 * 20;
        }
        if (potionId == Potion.damageBoost.id) {
            return 8 * 20;
        }
        if (potionId == Potion.blindness.id) {
            return 8 * 20;
        }
        if (potionId == Potion.fireResistance.id) {
            return 17 * 20;
        }
        if (potionId == Potion.poison.id || potionId == Potion.weakness.id) {
            return 8 * 20;
        }
        if (potionId == Potion.healthBoost.id) {
            return 27 * 60 * 20;
        }
        if (potionId == Potion.nightVision.id || potionId == Potion.waterBreathing.id) {
            return BlitzStarEffectsHudTracker.INFINITE_DURATION_TICKS;
        }
        if (potionId == Potion.hunger.id) {
            return 10 * 20;
        }
        if (potionId == Potion.wither.id) {
            return 4 * 20;
        }
        if (potionId == Potion.confusion.id) {
            return 8 * 20;
        }
        if (potionId == Potion.heal.id || potionId == Potion.harm.id) {
            return 0;
        }
        if (potionId == Potion.digSpeed.id || potionId == Potion.digSlowdown.id || potionId == Potion.jump.id
                || potionId == Potion.saturation.id) {
            return 60 * 20;
        }
        return 20 * 20;
    }

    /** All status effects in a compact grid for the Effects HUD color settings preview. */
    public static void renderPotionsColorGalleryAllEffects(Minecraft mc, FontRenderer fr,
                                                         int left, int top, int right, int bottom) {
        if (mc == null || fr == null) {
            return;
        }
        int[] ids = collectStatusEffectIds();
        if (ids.length == 0) {
            return;
        }
        int cols = 3;
        int colW = Math.max(1, (right - left) / cols);
        boolean perEffect = ConfigManager.isMoreHudPotionsEffectColors();
        int uniformRgb = ConfigManager.getMoreHudPotionsColor();
        int durCol = potionDurationDrawRgb();

        for (int idx = 0; idx < ids.length; idx++) {
            int id = ids[idx];
            int col = idx % cols;
            int row = idx / cols;
            int x = left + col * colW + 2;
            int y = top + row * POTIONS_GALLERY_ROW_H;
            if (y + POTIONS_GALLERY_ROW_H > bottom) {
                break;
            }
            int sampleTicks = potionsColorSettingsPreviewTicks(id);
            BlitzStarEffectsHudTracker.Effect blitz = BlitzStarEffectsHudTracker.effectForColorSlot(id);
            String name;
            if (blitz != null) {
                name = blitz.displayName;
            } else {
                Potion p = id < Potion.potionTypes.length ? Potion.potionTypes[id] : null;
                if (p == null) {
                    continue;
                }
                name = formatPotionEffectBaseName(p, new PotionEffect(id, sampleTicks, 0), false);
            }
            String durPlain = formatEffectsHudPotionDurationPlain(sampleTicks, false);
            int iconY = y + (POTIONS_GALLERY_ROW_H - POTIONS_GALLERY_ICON) / 2;
            drawPotionHudIcon(mc, id, x, iconY, POTIONS_GALLERY_ICON);
            int tx = x + POTIONS_GALLERY_ICON + 3;
            int textTop = y + 3;
            int nameRgb = resolveMoreHudPotionNameRgb(id, uniformRgb, perEffect);
            int nameCol = 0xFF000000 | (nameRgb & 0xFFFFFF);
            String nameDraw = perEffect ? name : EnumChatFormatting.RESET + name;
            fr.drawStringWithShadow(nameDraw, tx, textTop, nameCol);
            fr.drawStringWithShadow(durPlain, tx, textTop + fr.FONT_HEIGHT, durCol);
        }
    }

    /**
     * Draws one status effect (icon, name, timer) for the Effects HUD color settings grid.
     * Text layout matches {@link #drawPotionEffectLine} (HUD editor). {@code textRight} is exclusive.
     */
    public static void drawPotionsColorSettingsEffectRow(Minecraft mc, FontRenderer fr, int potionId,
            int left, int top, int textRight, int cellHeight) {
        drawPotionsColorSettingsEffectRow(mc, fr, potionId, left, top, textRight, cellHeight, true);
    }

    /**
     * @param drawBlitzItemIcon when false, Blitz rows draw text only (icons drawn separately with GUI item GL).
     */
    public static void drawPotionsColorSettingsEffectRow(Minecraft mc, FontRenderer fr, int potionId,
            int left, int top, int textRight, int cellHeight, boolean drawBlitzItemIcon) {
        drawPotionsColorSettingsEffectRow(mc, fr, potionId, left, top, textRight, cellHeight, drawBlitzItemIcon,
                ConfigManager.isMoreHudPotionsEffectColors(), ConfigManager.getMoreHudPotionsColor(),
                potionDurationDrawRgb());
    }

    /**
     * Same as {@link #drawPotionsColorSettingsEffectRow} but reuses cached color flags for a whole grid pass.
     */
    public static void drawPotionsColorSettingsEffectRow(Minecraft mc, FontRenderer fr, int potionId,
            int left, int top, int textRight, int cellHeight, boolean drawBlitzItemIcon,
            boolean perEffect, int uniformRgb, int durationRgb) {
        if (mc == null || fr == null || potionId <= 0) {
            return;
        }
        BlitzStarEffectsHudTracker.Effect blitz = BlitzStarEffectsHudTracker.effectForColorSlot(potionId);
        if (blitz != null) {
            drawBlitzStarColorSettingsEffectRow(mc, fr, blitz, left, top, textRight, cellHeight, drawBlitzItemIcon,
                    perEffect, uniformRgb, durationRgb);
            return;
        }
        if (potionId >= Potion.potionTypes.length) {
            return;
        }
        Potion p = Potion.potionTypes[potionId];
        if (p == null) {
            return;
        }
        int sampleTicks = potionsColorSettingsPreviewTicks(potionId);
        PotionEffect eff = new PotionEffect(potionId, sampleTicks, 0);
        String name = formatPotionEffectBaseName(p, eff, false);
        String durPlain = formatEffectsHudPotionDurationPlain(sampleTicks, false);
        int rowH = Math.max(cellHeight, potionRowUnscaledHeight(fr));
        int iconY = top + (rowH - POTION_ICON_LARGE) / 2;
        int iconX = left + 2;
        drawPotionIcon(mc, p, iconX, iconY, POTION_ICON_LARGE);
        int tx = iconX + POTION_ICON_LARGE + 4;
        int textBlockH = fr.FONT_HEIGHT * 2 + 1;
        int textTop = top + (rowH - textBlockH) / 2;
        int nameRgb = resolveMoreHudPotionNameRgb(potionId, uniformRgb, perEffect);
        int nameCol = 0xFF000000 | (nameRgb & 0xFFFFFF);
        String nameDraw = perEffect ? name : EnumChatFormatting.RESET + name;
        if (tx < textRight) {
            fr.drawStringWithShadow(nameDraw, tx, textTop, nameCol);
            fr.drawStringWithShadow(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durationRgb);
        }
    }

    /** Icon top-left for a color-settings cell ({@code out} length ≥ 2 = [x, y]). */
    public static boolean getPotionsColorSettingsIconPos(FontRenderer fr, int left, int top, int cellHeight, int[] out) {
        if (fr == null || out == null || out.length < 2) {
            return false;
        }
        int rowH = Math.max(cellHeight, potionRowUnscaledHeight(fr));
        out[0] = left + 2;
        out[1] = top + (rowH - POTION_ICON_LARGE) / 2;
        return true;
    }

    private static int[] collectStatusEffectIds() {
        return getPotionsColorSettingsEffectOrder();
    }

    /**
     * Blitz rows in the color GUI (column-major). Infection is placed early so it is not buried in the
     * last column next to expanded color pickers.
     */
    private static int[] getBlitzStarColorGridOrder() {
        return new int[] {
                BlitzStarEffectsHudTracker.Effect.INFECTION.colorSlot,
                BlitzStarEffectsHudTracker.Effect.VAMPIRE.colorSlot,
                BlitzStarEffectsHudTracker.Effect.WITHER_WARRIOR.colorSlot,
                BlitzStarEffectsHudTracker.Effect.ROBINHOOD.colorSlot,
                BlitzStarEffectsHudTracker.Effect.RAGNAROK.colorSlot,
                BlitzStarEffectsHudTracker.Effect.PICKPOCKET.colorSlot,
                BlitzStarEffectsHudTracker.Effect.ACID_RAIN.colorSlot,
                BlitzStarEffectsHudTracker.Effect.LUCKY_CHARM.colorSlot,
                BlitzStarEffectsHudTracker.Effect.LOCKDOWN.colorSlot,
                BlitzStarEffectsHudTracker.Effect.APOCALYPSE.colorSlot,
        };
    }

    private static int[] cachedPotionsColorSettingsEffectOrder;

    /**
     * Effects HUD color grid order (column-major: down each column, then next column).
     * Named effects first, then any others by id.
     */
    public static int[] getPotionsColorSettingsEffectOrder() {
        if (cachedPotionsColorSettingsEffectOrder != null) {
            return cachedPotionsColorSettingsEffectOrder;
        }
        int[] preferred = {
                Potion.moveSpeed.id,
                Potion.regeneration.id,
                Potion.resistance.id,
                Potion.moveSlowdown.id,
                Potion.absorption.id,
                Potion.invisibility.id,
                Potion.damageBoost.id,
                Potion.blindness.id,
                Potion.fireResistance.id,
                Potion.weakness.id,
                Potion.poison.id,
                Potion.healthBoost.id,
                Potion.nightVision.id,
                Potion.waterBreathing.id,
                Potion.hunger.id,
                Potion.wither.id,
                Potion.confusion.id,
                Potion.digSpeed.id,
                Potion.digSlowdown.id,
                Potion.jump.id,
                Potion.saturation.id,
                Potion.heal.id,
                Potion.harm.id,
        };
        java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
        List<Integer> ordered = new ArrayList<Integer>();
        for (int id : preferred) {
            if (id > 0 && id < Potion.potionTypes.length && Potion.potionTypes[id] != null && seen.add(id)) {
                ordered.add(id);
            }
        }
        for (int slot : getBlitzStarColorGridOrder()) {
            if (seen.add(slot)) {
                ordered.add(slot);
            }
        }
        for (int id = 1; id < ConfigManager.MORE_HUD_POTION_COLOR_SLOTS; id++) {
            if (id >= BlitzStarEffectsHudTracker.COLOR_SLOT_BASE) {
                continue;
            }
            if (!seen.contains(id) && id < Potion.potionTypes.length && Potion.potionTypes[id] != null) {
                ordered.add(id);
                seen.add(id);
            }
        }
        int[] out = new int[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            out[i] = ordered.get(i);
        }
        cachedPotionsColorSettingsEffectOrder = out;
        return out;
    }

    /** Draws one status effect icon for the Effects HUD color picker rows. */
    public static void drawPotionHudIcon(Minecraft mc, int potionId, int x, int y, int size) {
        if (mc == null || potionId <= 0) {
            return;
        }
        BlitzStarEffectsHudTracker.Effect blitz = BlitzStarEffectsHudTracker.effectForColorSlot(potionId);
        if (blitz != null) {
            BlitzStarEffectIcons.draw(mc, blitz.iconStack, x, y, size, blitz);
            return;
        }
        if (potionId >= Potion.potionTypes.length) {
            return;
        }
        Potion p = Potion.potionTypes[potionId];
        if (p != null) {
            drawPotionIcon(mc, p, x, y, size);
        }
    }

    /** Blitz star item icon for one color-settings cell (uses armor-status GUI item rendering). */
    public static void drawPotionsColorSettingsBlitzItemIcon(Minecraft mc, FontRenderer fr, int potionId,
            int left, int top, int cellHeight) {
        if (mc == null || fr == null || potionId <= 0) {
            return;
        }
        BlitzStarEffectsHudTracker.Effect effect = BlitzStarEffectsHudTracker.effectForColorSlot(potionId);
        if (effect == null) {
            return;
        }
        int rowH = Math.max(cellHeight, potionRowUnscaledHeight(fr));
        int iconY = top + (rowH - POTION_ICON_LARGE) / 2;
        BlitzStarEffectIcons.draw(mc, effect.iconStack, left + 2, iconY, POTION_ICON_LARGE, effect);
    }

    private static void drawBlitzStarColorSettingsEffectRow(Minecraft mc, FontRenderer fr,
            BlitzStarEffectsHudTracker.Effect effect, int left, int top, int textRight, int cellHeight,
            boolean drawItemIcon) {
        drawBlitzStarColorSettingsEffectRow(mc, fr, effect, left, top, textRight, cellHeight, drawItemIcon,
                ConfigManager.isMoreHudPotionsEffectColors(), ConfigManager.getMoreHudPotionsColor(),
                potionDurationDrawRgb());
    }

    private static void drawBlitzStarColorSettingsEffectRow(Minecraft mc, FontRenderer fr,
            BlitzStarEffectsHudTracker.Effect effect, int left, int top, int textRight, int cellHeight,
            boolean drawItemIcon, boolean perEffect, int uniformRgb, int durationRgb) {
        if (mc == null || fr == null || effect == null) {
            return;
        }
        int sampleTicks = effect.durationSeconds < 0
                ? BlitzStarEffectsHudTracker.INFINITE_DURATION_TICKS
                : effect.durationTicks();
        String name = effect.displayName;
        String durPlain = formatEffectsHudPotionDurationPlain(sampleTicks, false);
        int rowH = Math.max(cellHeight, potionRowUnscaledHeight(fr));
        int iconY = top + (rowH - POTION_ICON_LARGE) / 2;
        int iconX = left + 2;
        if (drawItemIcon) {
            BlitzStarEffectIcons.draw(mc, effect.iconStack, iconX, iconY, POTION_ICON_LARGE, effect);
        }
        int tx = iconX + POTION_ICON_LARGE + 4;
        int textBlockH = fr.FONT_HEIGHT * 2 + 1;
        int textTop = top + (rowH - textBlockH) / 2;
        int nameRgb = resolveMoreHudPotionNameRgb(effect.colorSlot, uniformRgb, perEffect);
        int nameCol = 0xFF000000 | (nameRgb & 0xFFFFFF);
        String nameDraw = perEffect ? name : EnumChatFormatting.RESET + name;
        if (tx < textRight) {
            fr.drawStringWithShadow(nameDraw, tx, textTop, nameCol);
            fr.drawStringWithShadow(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durationRgb);
        }
    }

    private static int drawBlitzStarEffectLine(Minecraft mc, FontRenderer fr, int left, int y,
            BlitzStarEffectsHudTracker.ActiveRow row, int textColumnW, int textRgb, boolean shadow,
            boolean hudEditorPreview) {
        if (mc == null || fr == null || row == null || row.effect == null) {
            return y + fr.FONT_HEIGHT;
        }
        BlitzStarEffectsHudTracker.Effect effect = row.effect;
        boolean perEffect = ConfigManager.isMoreHudPotionsEffectColors();
        String name = effect.displayName;
        String nameLine = perEffect ? name : EnumChatFormatting.RESET + name;
        String durPlain = formatEffectsHudPotionDurationPlain(row.remainingTicks, hudEditorPreview);
        int rowH = potionRowUnscaledHeight(fr);
        int iconY = y + (rowH - POTION_ICON_LARGE) / 2;
        int tx = left + POTION_ICON_LARGE + 4;
        int textTop = y + (rowH - (fr.FONT_HEIGHT * 2 + 1)) / 2;
        BlitzStarEffectIcons.draw(mc, effect.iconStack, left, iconY, POTION_ICON_LARGE, effect);
        int nameRgb = resolveMoreHudPotionNameRgb(effect.colorSlot, textRgb, perEffect);
        int nameCol = 0xFF000000 | nameRgb;
        int durCol = potionDurationDrawRgb();
        if (shadow) {
            fr.drawStringWithShadow(nameLine, tx, textTop, nameCol);
            fr.drawStringWithShadow(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durCol);
        } else {
            fr.drawString(nameLine, tx, textTop, nameCol);
            fr.drawString(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durCol);
        }
        return y + rowH + potionRowsVerticalGap();
    }

    /** CPS row sample for the color settings preview band. */
    public static void renderCpsColorGallerySample(Minecraft mc, FontRenderer fr, int centerX, int centerY) {
        if (fr == null) {
            return;
        }
        drawCpsRowColored(mc, fr, centerX, centerY, 0, 0);
    }

    private static int drawOtherKillEffectsRow(Minecraft mc, FontRenderer fr, int centerX, int y, KillEffectHudState state) {
        if (mc == null || fr == null || state == null) {
            return y;
        }
        int iconSize = killEffectsIconSizeUnscaled();
        int inx = ConfigManager.getMoreHudKillEffectsIconNudgeX();
        int iny = ConfigManager.getMoreHudKillEffectsIconNudgeY();
        int tnx = ConfigManager.getMoreHudKillEffectsTimerNudgeX();
        int tny = ConfigManager.getMoreHudKillEffectsTimerNudgeY();
        int rowH = killEffectsRowHUnscaled(fr);
        int iconLeft0 = centerX - iconSize / 2;
        int iconY0 = y + (rowH - iconSize) / 2;
        int tx0 = iconLeft0 + iconSize + 4;
        int iconLeft = iconLeft0 + inx;
        int iconY = iconY0 + iny;
        int th = killEffectsTimerTextHeightUnscaled(fr);
        int tw = killEffectsTimerTextWidthUnscaled(fr, state.timerPlain);
        int tcx0 = tx0 + killEffectsPreviewTimerWidthUnscaled(fr) / 2;
        int tx = (tcx0 + tnx) - tw / 2;
        int ty = y + (rowH - th) / 2 + tny;
        int[] bb = new int[4];
        computeKillEffectsBoundingBox(mc, fr, centerX, y, state, bb);
        int bgAlpha = ConfigManager.getMoreHudKillEffectsBg();
        if (bgAlpha > 0) {
            HudEditorBackgroundMask.drawRect(bb[0], bb[1], bb[2], bb[3], MoreHudBackgroundColors.killEffectsPixel(bgAlpha));
        }
        HudGlState.restoreForOverlayText();
        drawGroupedKillEffectIcon(mc, state, iconLeft, iconY, iconSize);
        HudGlState.restoreForOverlayText();
        float timerScale = ConfigManager.getMoreHudKillEffectsTimerScale();
        int timerCol = 0xFF000000 | (state.textRgb & 0xFFFFFF);
        GlStateManager.pushMatrix();
        GlStateManager.translate(tx, ty, 0);
        GlStateManager.scale(timerScale, timerScale, 1.0f);
        if (ConfigManager.isMoreHudKillEffectsShadow()) {
            fr.drawStringWithShadow(state.timerPlain, 0, 0, timerCol);
        } else {
            fr.drawString(state.timerPlain, 0, 0, timerCol);
        }
        GlStateManager.popMatrix();
        return y + rowH;
    }

    private static void drawGroupedKillEffectIcon(Minecraft mc, KillEffectHudState state, int x, int y, int size) {
        if (mc == null || state == null || state.iconTexture == null) {
            return;
        }
        int[] crop = groupedKillEffectIconCrop(state.effectCode);
        if (crop == null) {
            drawFlameKillEffectIcon(mc, state.iconTexture, x, y, size);
            return;
        }
        try {
            GomodClasspathTextures.bindTexture(mc, state.iconTexture);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Gui.drawScaledCustomSizeModalRect(x, y, crop[0], crop[1], crop[2], crop[3], size, size, 48.0F, 48.0F);
            GlStateManager.disableBlend();
        } catch (Throwable ignored) {
        }
    }

    private static int[] groupedKillEffectIconCrop(String effectCode) {
        if (effectCode == null) {
            return new int[] {6, 1, 38, 45};
        }
        switch (effectCode) {
            case "REGENERATION":
                return new int[] {6, 1, 38, 45};
            case "RESISTANCE":
                return new int[] {3, 4, 42, 42};
            case "SPEED":
                return new int[] {5, 2, 39, 44};
            default:
                return null;
        }
    }

    private static int drawCenteredRow(Minecraft mc, FontRenderer fr, int centerX, int y, String text,
                                       int bgPixel, int textRgb, boolean shadow) {
        int w = fr.getStringWidth(text);
        int x = centerX - w / 2;
        if (bgPixel != 0) {
            HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.symmetricCenteredTextRowPad(
                    HudOpacitySliderBoxInsets.Module.BLITZ_KIT_TIMER, shadow);
            HudEditorBackgroundMask.drawRect(x - pad.left, y - pad.up, x + w + pad.right, y + fr.FONT_HEIGHT + pad.down, bgPixel);
        }
        int col = 0xFF000000 | (textRgb & 0xFFFFFF);
        if (shadow) {
            fr.drawStringWithShadow(text, x, y, col);
        } else {
            fr.drawString(text, x, y, col);
        }
        return y + fr.FONT_HEIGHT;
    }

    private static List<PotionEffect> resolvePotionDrawList(Minecraft mc, boolean hudEditorPreview) {
        if (hudEditorPreview) {
            return HUD_EDITOR_PREVIEW_POTIONS;
        }
        List<PotionEffect> out = new ArrayList<>();
        if (mc == null || mc.thePlayer == null) {
            return out;
        }
        Collection<PotionEffect> fx = mc.thePlayer.getActivePotionEffects();
        if (fx == null || fx.isEmpty()) {
            return out;
        }
        for (PotionEffect eff : fx) {
            if (eff == null) {
                continue;
            }
            Potion p = Potion.potionTypes[eff.getPotionID()];
            if (p == null) {
                continue;
            }
            out.add(eff);
        }
        return out;
    }

    private static List<BlitzStarEffectsHudTracker.ActiveRow> resolveBlitzDrawListForPotionsBlock(
            boolean hudEditorPreview) {
        List<BlitzStarEffectsHudTracker.ActiveRow> active = BlitzStarEffectsHudTracker.getActiveRows();
        if (!hudEditorPreview || !active.isEmpty()) {
            return active;
        }
        List<BlitzStarEffectsHudTracker.ActiveRow> preview = new ArrayList<BlitzStarEffectsHudTracker.ActiveRow>();
        BlitzStarEffectsHudTracker.Effect e = HUD_EDITOR_PREVIEW_BLITZ_EFFECT;
        preview.add(new BlitzStarEffectsHudTracker.ActiveRow(e, e.durationTicks()));
        return preview;
    }

    private static void drawFlameKillEffectIcon(Minecraft mc, ResourceLocation texture, int x, int y, int size) {
        if (mc == null || texture == null) {
            return;
        }
        try {
            GomodClasspathTextures.bindTexture(mc, texture);
            int tw = GomodClasspathTextures.getWidth(texture, 256);
            int th = GomodClasspathTextures.getHeight(texture, 256);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, tw, th, size, size, tw, th);
            GlStateManager.disableBlend();
        } catch (Throwable ignored) {
        }
    }

    private static void drawPotionIcon(Minecraft mc, Potion potion, int x, int y, int size) {
        MoreHudPotionIcons.drawPotionIcon(mc, potion, x, y, size);
    }

    /**
     * One effect row: icons share a vertical column; text column width is fixed so rows align like the
     * survival inventory list (no per-row horizontal centering).
     */
    private static int drawPotionEffectLine(Minecraft mc, FontRenderer fr, int left, int y, PotionEffect eff,
                                            int textColumnW, int textRgb, boolean shadow, boolean hudEditorPreview) {
        Potion p = Potion.potionTypes[eff.getPotionID()];
        if (p == null) {
            return y + fr.FONT_HEIGHT;
        }
        PotionHudTextCache.Text name = PotionHudTextCache.name(p, eff.getAmplifier(), hudEditorPreview);
        boolean vanillaEffectColor = ConfigManager.isMoreHudPotionsEffectColors();
        String nameLine = vanillaEffectColor ? name.plain : name.formatted;
        String durPlain = formatEffectsHudPotionDurationPlain(eff.getDuration(), hudEditorPreview);
        int rowH = potionRowUnscaledHeight(fr);
        int iconY = y + (rowH - POTION_ICON_LARGE) / 2;
        int tx = left + POTION_ICON_LARGE + 4;
        int textTop = y + (rowH - (fr.FONT_HEIGHT * 2 + 1)) / 2;
        drawPotionIcon(mc, p, left, iconY, POTION_ICON_LARGE);
        int nameRgb = resolveMoreHudPotionNameRgb(eff.getPotionID(), textRgb, vanillaEffectColor);
        int nameCol = 0xFF000000 | nameRgb;
        int durCol = potionDurationDrawRgb();
        if (shadow) {
            fr.drawStringWithShadow(nameLine, tx, textTop, nameCol);
            fr.drawStringWithShadow(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durCol);
        } else {
            fr.drawString(nameLine, tx, textTop, nameCol);
            fr.drawString(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durCol);
        }
        return y + rowH + potionRowsVerticalGap();
    }

    /** Remaining flame proc as potion-style duration ticks (20 ticks = 1s). */
    private static int flameProcRemainingAsPotionTicks(boolean preview) {
        long remMs = preview ? 20_000L : BlitzFlameKillEffectTracker.getProcRemainingMs();
        if (remMs < 0L) {
            remMs = 0L;
        }
        long ticks = (remMs * 20L + 999L) / 1000L;
        if (ticks > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) ticks;
    }

    /**
     * Merged Flaming Arrows row in Effects HUD: text duration only (never the compact depleting bar).
     */
    private static int drawFlameKillEffectPotionLine(Minecraft mc, FontRenderer fr, int left, int y, int textColumnW,
                                                     boolean preview, boolean shadow, KillEffectHudState mergedState) {
        KillEffectHudState state = mergedState != null ? mergedState
                : (preview ? buildPreviewKillEffectHudState() : resolveVisibleKillEffectHudState(mc, false));
        int rowH = potionRowUnscaledHeight(fr);
        int iconY = y + (rowH - POTION_ICON_LARGE) / 2;
        ResourceLocation flameIcon = state != null ? state.iconTexture : FLAME_KILL_TEXTURE;
        int tx = left + POTION_ICON_LARGE + 4;
        int textTop = y + (rowH - (fr.FONT_HEIGHT * 2 + 1)) / 2;
        drawFlameKillEffectIcon(mc, flameIcon, left, iconY, POTION_ICON_LARGE);
        HudGlState.restoreForOverlayText();

        boolean customEffectColors = ConfigManager.isMoreHudPotionsEffectColors();
        int nameRgb = customEffectColors
                ? (state != null ? (state.textRgb & 0xFFFFFF)
                        : (ConfigManager.getMoreHudKillEffectColor("FLAME") & 0xFFFFFF))
                : (ConfigManager.getMoreHudPotionsColor() & 0xFFFFFF);
        int nameCol = 0xFF000000 | nameRgb;
        String namePlain = state != null ? state.displayName : "Flaming Arrows";
        if (shadow) {
            fr.drawStringWithShadow(namePlain, tx, textTop, nameCol);
        } else {
            fr.drawString(namePlain, tx, textTop, nameCol);
        }
        String durPlain;
        if (state != null && ConfigManager.isMoreHudPotionsDecimalEffectTimer()) {
            durPlain = state.timerPlain != null
                    ? EnumChatFormatting.getTextWithoutFormattingCodes(state.timerPlain)
                    : "";
        } else if (state != null) {
            durPlain = formatFlameEffectsHudMergedDurationPlain(flameProcRemainingAsPotionTicks(preview));
        } else if (ConfigManager.isMoreHudPotionsDecimalEffectTimer()) {
            if (preview) {
                durPlain = formatDecimalEffectTimerPlain(20.0);
            } else {
                long remMs = BlitzFlameKillEffectTracker.getProcRemainingMs();
                if (remMs < 0L) {
                    remMs = 0L;
                }
                durPlain = formatDecimalEffectTimerPlain(remMs / 1000.0);
            }
        } else {
            durPlain = formatFlameEffectsHudMergedDurationPlain(flameProcRemainingAsPotionTicks(preview));
        }
        int durCol = potionDurationDrawRgb();
        if (shadow) {
            fr.drawStringWithShadow(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durCol);
        } else {
            fr.drawString(durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durCol);
        }
        return y + rowH + potionRowsVerticalGap();
    }

    /** Bar fill uses a readable orange-red even when {@link ConfigManager#getMoreHudFlameColor()} is very dark. */
    private static int flameProcBarRgbForHud(int textRgb) {
        int r = (textRgb >> 16) & 0xFF;
        int g = (textRgb >> 8) & 0xFF;
        int b = textRgb & 0xFF;
        if (r + g + b < 120) {
            return 0xFFCC4400;
        }
        return textRgb & 0xFFFFFF;
    }

    private static int drawPotionRows(Minecraft mc, FontRenderer fr, int centerX, int startY, int gap,
                                      boolean hudEditorPreview, boolean drawFlameLine, boolean layoutIncludeFlameColumn,
                                      KillEffectHudState mergedFlameState) {
        List<PotionEffect> list = resolvePotionDrawList(mc, hudEditorPreview);
        int textColW = layoutPotionTextColumnWidth(fr, list, hudEditorPreview, layoutIncludeFlameColumn);
        if (textColW == 0 && !drawFlameLine && !BlitzStarEffectsHudTracker.hasActiveEffects()) {
            return startY;
        }
        int left = centerX - POTION_ICON_LARGE / 2;
        int bgAlpha = ConfigManager.getMoreHudPotionsBg();
        int blockW = POTION_ICON_LARGE + 4 + textColW;
        int contentBottom = computePotionsBlockContentBottom(fr, startY, list, drawFlameLine, mergedFlameState,
                hudEditorPreview);
        if (bgAlpha > 0) {
            HudEditorBackgroundMask.drawRect(left - 2, startY - potionsBgTopOffset(), left + blockW + 2,
                    contentBottom + potionsBgBottomOffset(), MoreHudBackgroundColors.potionsPixel(bgAlpha));
        }
        int y = startY;
        for (PotionEffect eff : list) {
            if (eff == null) {
                continue;
            }
            y = drawPotionEffectLine(mc, fr, left, y, eff, textColW, ConfigManager.getMoreHudPotionsColor(),
                    ConfigManager.isMoreHudPotionsShadow(), hudEditorPreview);
        }
        List<BlitzStarEffectsHudTracker.ActiveRow> blitzRows = resolveBlitzDrawListForPotionsBlock(hudEditorPreview);
        if (hudEditorPreview) {
            if (drawFlameLine) {
                y = drawFlameKillEffectPotionLine(mc, fr, left, y, textColW, hudEditorPreview,
                        ConfigManager.isMoreHudPotionsShadow(), mergedFlameState);
            }
            for (BlitzStarEffectsHudTracker.ActiveRow row : blitzRows) {
                y = drawBlitzStarEffectLine(mc, fr, left, y, row, textColW, ConfigManager.getMoreHudPotionsColor(),
                        ConfigManager.isMoreHudPotionsShadow(), hudEditorPreview);
            }
        } else {
            for (BlitzStarEffectsHudTracker.ActiveRow row : blitzRows) {
                y = drawBlitzStarEffectLine(mc, fr, left, y, row, textColW, ConfigManager.getMoreHudPotionsColor(),
                        ConfigManager.isMoreHudPotionsShadow(), hudEditorPreview);
            }
            if (drawFlameLine) {
                y = drawFlameKillEffectPotionLine(mc, fr, left, y, textColW, hudEditorPreview,
                        ConfigManager.isMoreHudPotionsShadow(), mergedFlameState);
            }
        }
        return y + gap;
    }

    private static int drawArrowRow(Minecraft mc, FontRenderer fr, int centerX, int y, int count) {
        int slot = 16;
        int left = centerX - slot / 2;
        int bg = ConfigManager.getMoreHudArrowsBg();
        if (bg > 0) {
            HudEditorBackgroundMask.drawRect(left - 2, y - 2, left + slot + 2, y + slot + 2, MoreHudBackgroundColors.arrowsPixel(bg));
        }
        ItemStack stack = new ItemStack(Items.arrow, Math.max(0, count));
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableRescaleNormal();
        RenderHelper.enableGUIStandardItemLighting();
        try {
            mc.getRenderItem().zLevel = 200.0F;
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, left, y);
        } catch (Throwable ignored) {
        }
        mc.getRenderItem().zLevel = 0.0F;
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.pushMatrix();
        try {
            HudGlState.restoreForOverlayText();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.translate(0.0F, 0.0F, 300.0F);
            drawArrowCountOverlay(fr, left, y, slot, count, ConfigManager.isMoreHudArrowsShadow());
        } catch (Throwable ignored) {
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
        }
        return y + slot + 2;
    }

    private static void drawArrowCountOverlay(FontRenderer fr, int left, int y, int slot, int count, boolean shadow) {
        String s = String.valueOf(count);
        int rgb = count == 0 ? ConfigManager.getMoreHudArrowsZeroColor() : ConfigManager.getMoreHudArrowsColor();
        int col = rgb & 0xFFFFFF;
        int sw = fr.getStringWidth(s);
        int bx = left + slot - 2 - sw + ConfigManager.getMoreHudArrowsOverlayDx();
        int by = y + slot - 2 - fr.FONT_HEIGHT + ConfigManager.getMoreHudArrowsOverlayDy();
        if (shadow) {
            fr.drawStringWithShadow(s, bx, by, col);
        } else {
            fr.drawString(s, bx, by, col);
        }
    }

    /** Preview non-zero and zero counts for the arrows color settings screen. */
    public static void renderArrowsColorGallerySample(Minecraft mc, FontRenderer fr, int centerX, int centerY) {
        if (fr == null) {
            return;
        }
        int slot = 16;
        int rowH = slot + 2;
        drawArrowRow(mc, fr, centerX - 14, centerY - rowH / 2, 12);
        drawArrowRow(mc, fr, centerX + 14, centerY - rowH / 2, 0);
    }

    private static int countArrows(Minecraft mc) {
        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return 0;
        }
        int c = 0;
        for (ItemStack st : mc.thePlayer.inventory.mainInventory) {
            if (st != null && st.getItem() == Items.arrow) {
                c += st.stackSize;
            }
        }
        return c;
    }

    private static int resolvePing(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return -1;
        }
        try {
            long now = System.currentTimeMillis();
            net.minecraft.client.network.NetworkPlayerInfo info =
                    mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            if (info != null) {
                int tab = info.getResponseTime();
                if (tab > 0 && tab < 60000) {
                    if (tab > MOREHUD_PING_SUSPICIOUS_MAX_MS) {
                        notePlausiblePing(tab, now);
                        return tab;
                    }
                    int heldStatusPing = MoreHudServerPingRefresher.getRecentPingMs(MOREHUD_PING_HOLD_WALL_MS);
                    if (heldStatusPing >= MOREHUD_PING_HOLD_MIN_PRIOR_MS) {
                        notePlausiblePing(heldStatusPing, now);
                        return heldStatusPing;
                    }
                    if (moreHudPingLastPlausible >= MOREHUD_PING_HOLD_MIN_PRIOR_MS
                            && (now - moreHudPingLastPlausibleWallMs) < MOREHUD_PING_HOLD_WALL_MS) {
                        return moreHudPingLastPlausible;
                    }
                    return tab;
                }
            }
            int refreshedStatusPing = MoreHudServerPingRefresher.getFreshPingMs();
            if (refreshedStatusPing > 0) {
                notePlausiblePing(refreshedStatusPing, now);
                return refreshedStatusPing;
            }
            return info != null ? info.getResponseTime() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void notePlausiblePing(int ping, long now) {
        if (ping >= MOREHUD_PING_HOLD_MIN_PRIOR_MS) {
            moreHudPingLastPlausible = ping;
            moreHudPingLastPlausibleWallMs = now;
        }
    }

    /** Exposed for HUD Editor layout (same logic as in-game ping row). */
    public static int resolvePingForLayout(Minecraft mc) {
        return resolvePing(mc);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * Screen bounds for potion block (matches row scale pivot at {@code (centerX, startY)}).
     * When background opacity is on, the box wraps the shared attached background, like mob health.
     */
    public static void potionsBlockScreenBounds(Minecraft mc, int centerX, int startY, int gap, float scale,
                                                boolean hudEditorPreview, boolean includeFlameRow, int[] out) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = centerX - 40;
            out[1] = startY - 2;
            out[2] = 80;
            out[3] = 40;
            return;
        }
        List<PotionEffect> list = resolvePotionDrawList(mc, hudEditorPreview);
        KillEffectHudState flameState = null;
        if (includeFlameRow) {
            flameState = resolveVisibleKillEffectHudState(mc, hudEditorPreview);
            if (flameState == null) {
                flameState = buildPreviewKillEffectHudState();
            }
        }
        int contentBottom = computePotionsBlockContentBottom(fr, startY, list, includeFlameRow, flameState,
                hudEditorPreview);
        int uw = measurePotionsWidth(mc, hudEditorPreview, includeFlameRow);
        double boxLeftUnscaled = centerX - POTION_ICON_LARGE / 2.0 - 2;
        // Bounds always include background padding so opacity 0 does not shift the HUD editor box.
        int bgTopUnscaled = startY - potionsBgTopOffset();
        int bgBottomUnscaled = contentBottom + potionsBgBottomOffset();
        int uh = Math.max(1, bgBottomUnscaled - bgTopUnscaled);
        out[0] = (int) Math.round(centerX + (boxLeftUnscaled - centerX) * scale);
        out[1] = (int) Math.round(startY + (bgTopUnscaled - startY) * scale);
        out[2] = (int) Math.ceil(uw * scale);
        out[3] = (int) Math.ceil(uh * scale);
    }

    /**
     * Whether the Flaming Arrows kill-effect row is laid out inside the Effects HUD block (not the standalone / compact row).
     * In {@link GomodHudEditorGUI}, this keeps the preview and drag bounds attached to the Effects HUD box.
     */
    public static boolean includeFlamingArrowsInPotionsBlock(Minecraft mc) {
        if (!ConfigManager.isMoreHudPotionsShowFlamingArrows() || ConfigManager.isMoreHudFlameCompact()) {
            return false;
        }
        if (mc != null && mc.currentScreen instanceof GomodHudEditorGUI) {
            // HUD Editor should match the selected display mode.
            return true;
        }
        return ConfigManager.isMoreHudFlameEnabled();
    }

    /**
     * Gallery preview for the Effects HUD panel only (not the Flaming Arrows panel — that always uses compact preview).
     */
    private static boolean includeFlamingArrowsForGalleryPreview(Minecraft mc) {
        if (!ConfigManager.isMoreHudPotionsShowFlamingArrows() || ConfigManager.isMoreHudFlameCompact()) {
            return false;
        }
        if (mc != null && mc.currentScreen instanceof MoreHudSettingsGUI) {
            MoreHudSettingsGUI screen = (MoreHudSettingsGUI) mc.currentScreen;
            return screen.getPanel() == MoreHudSettingsGUI.Panel.POTIONS;
        }
        return includeFlamingArrowsInPotionsBlock(mc);
    }

    /**
     * Settings-gallery bounds for compact Flaming Arrows: union of icon + timer/bar (same centering target as Kill effects).
     */
    private static void flameCompactGalleryPreviewScreenBounds(Minecraft mc, int pivotX, int pivotY, float scale,
                                                               int[] out) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            out[0] = pivotX - 40;
            out[1] = pivotY - 12;
            out[2] = 80;
            out[3] = 24;
            return;
        }
        int[] iconB = new int[4];
        int[] timerB = new int[4];
        int[] iconAnchor = new int[2];
        int[] timerAnchor = new int[2];
        flameCompactIconPartScreenBounds(mc, pivotX, pivotY, scale, true, iconB, iconAnchor);
        flameCompactTimerPartScreenBounds(mc, pivotX, pivotY, scale, true, timerB, timerAnchor);
        out[0] = Math.min(iconB[0], timerB[0]);
        out[1] = Math.min(iconB[1], timerB[1]);
        int right = Math.max(iconB[0] + iconB[2], timerB[0] + timerB[2]);
        int bottom = Math.max(iconB[1] + iconB[3], timerB[1] + timerB[3]);
        out[2] = Math.max(1, right - out[0]);
        out[3] = Math.max(1, bottom - out[1]);
    }

    /** Screen bounds for arrow icon (pivot {@code (centerX, startY)} = top-center of slot). */
    public static void arrowRowScreenBounds(int centerX, int startY, float scale, int[] out) {
        int slot = 16;
        int pad = 2;
        int uw = slot + pad * 2;
        int uh = slot + pad * 2;
        out[0] = (int) Math.round(centerX - (uw / 2.0F) * scale);
        out[1] = (int) Math.round(startY - pad * scale);
        out[2] = (int) Math.ceil(uw * scale);
        out[3] = (int) Math.ceil(uh * scale);
    }
}
