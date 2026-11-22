package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.HudEditorCenterGuides;
import me.napim.gomod.features.BlitzChallenges;
import me.napim.gomod.features.BlitzKitTimerHUD;
import me.napim.gomod.features.IncomingScreenshotOverlay;
import me.napim.gomod.features.FriendInventoryHUD;
import me.napim.gomod.features.HorseDonkeyTracker;
import me.napim.gomod.features.InventoryHUD;
import me.napim.gomod.features.MobHealthDisplay;
import me.napim.gomod.features.MoreHudArmorStatusFeature;
import me.napim.gomod.features.MoreHudCoordsFeature;
import me.napim.gomod.features.MoreHudFeature;
import me.napim.gomod.features.PlayerTarget;
import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class GomodHudEditorGUI extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int RESIZE_HANDLE_SIZE = 8;
    private static final String FRIEND_PREVIEW_NAME = "SaturnAndJupiter";
    /** Snap to screen center guides when the HUD center is within this distance (px). */
    private static final int SNAP_THRESHOLD_PX = 4;
    /** Release center snap once the HUD center moves farther than this from the guide (px). */
    private static final int SNAP_RELEASE_PX = 5;
    private static final int ARROW_NUDGE_PX = 1;
    private static final int ARROW_NUDGE_SHIFT_PX = 10;
    private static final int MAX_UNDO_STEPS = 40;
    private static final ResourceLocation SETTING_BUTTON_TEXTURE =
            new ResourceLocation("gomod123", "textures/settingbutton.png");

    private final GuiScreen parent;
    /** When non-null/non-empty, used for the bottom back button instead of {@code Back}. */
    private final String backButtonOverrideText;

    // Hint icon / label layout for "(right click)" helper near the cursor in HUD Editor.
    // Final tuned defaults from /hudedithint testing.
    private static int hintIconOffsetX = 2;    // relative to mouseX
    private static int hintIconOffsetY = -7;   // relative to mouseY
    private static int hintTextOffsetX = 12;   // relative to icon anchor
    private static int hintTextOffsetY = -9;   // extra offset after default below-icon placement
    private static int hintIconSizePx = 10;
    private GuiButton backButton;
    /** Locked selection (yellow); arrow keys move this widget. */
    private HudWidgetType selectedWidget;
    private HudWidgetType draggingWidget;
    private HudWidgetType resizingWidget;
    private ResizeCorner activeResizeCorner;
    private int dragOffsetX;
    private int dragOffsetY;
    private int resizeStartMouseX;
    private int resizeStartMouseY;
    private float resizeStartScale;
    private int resizeStartSpan;
    /** Companion scales for rows that use separate icon/timer scale (kept in proportion while resizing). */
    private float resizeStartAuxScale1;
    private float resizeStartAuxScale2;

    private long hudHoverStartMs;
    private HudWidgetType hudHoverType;
    private boolean snapLockCenterX;
    private boolean snapLockCenterY;
    /** After releasing a snap, do not re-snap until the next mouse drag. */
    private boolean snapEngageBlockedCenterX;
    private boolean snapEngageBlockedCenterY;
    /** Anchor to geometric-center offset, fixed for the duration of a drag (avoids snap oscillation). */
    private int dragGeoOffsetX;
    private int dragGeoOffsetY;
    private int dragPointerStartX;
    private int dragPointerStartY;
    /** True once the pointer moves enough during a drag to count as repositioning (not a tap). */
    private boolean dragPointerMoved;
    private static final int DRAG_COMMIT_MOVE_PX = 3;
    private HudWidgetState gestureBefore;
    private HudWidgetType gestureWidget;
    private final ArrayDeque<HudEditorHistoryStep> undoStack = new ArrayDeque<HudEditorHistoryStep>();
    private final ArrayDeque<HudEditorHistoryStep> redoStack = new ArrayDeque<HudEditorHistoryStep>();

    public GomodHudEditorGUI(GuiScreen parent) {
        this(parent, null);
    }

    /**
     * @param backButtonOverrideText e.g. {@code EnumChatFormatting.GRAY + "Back to Inventory HUD Settings"};
     *                               if null/blank, the button reads {@code Back}.
     */
    public GomodHudEditorGUI(GuiScreen parent, String backButtonOverrideText) {
        this.parent = parent;
        this.backButtonOverrideText = backButtonOverrideText;
    }

    @Override
    public void initGui() {
        super.initGui();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            GomodClasspathTextures.ensureLoaded(mc, SETTING_BUTTON_TEXTURE);
        }
        IncomingScreenshotOverlay.resetEditorPreviewCache(mc);
        this.buttonList.clear();
        String backLabel = (backButtonOverrideText != null && !backButtonOverrideText.isEmpty())
                ? backButtonOverrideText
                : EnumChatFormatting.GRAY + "Back";
        int backY = GuiHeader.backButtonY(height, BUTTON_HEIGHT);
        int backW = Math.max(60, fontRendererObj != null ? fontRendererObj.getStringWidth(backLabel) + 24 : 60);
        backButton = new GuiButton(0, width / 2 - backW / 2, backY, backW, BUTTON_HEIGHT, backLabel);
        this.buttonList.add(backButton);
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        GuiHeader.drawYellowBoldTitleCentered(fontRendererObj, "HUD Editor", width, GuiHeader.STANDARD_TITLE_Y);

        drawHudBounds(mouseX, mouseY, true, false);
        HudEditorBackgroundMask.beginFrame();
        try {
            renderHudPreviews();
        } finally {
            HudEditorBackgroundMask.endFrame();
        }
        drawHudBounds(mouseX, mouseY, false, true);
        GuiHeader.drawTopLeftHudEditor(this, collectHudModuleScreenRects());

        long now = System.currentTimeMillis();
        if (draggingWidget != null || resizingWidget != null) {
            hudHoverStartMs = 0L;
            hudHoverType = null;
        } else {
            HudLayout hl = findHoveredLayout(getHudLayouts(), mouseX, mouseY);
            if (hl == null) {
                hudHoverStartMs = 0L;
                hudHoverType = null;
            } else if (hl.type != hudHoverType) {
                hudHoverType = hl.type;
                hudHoverStartMs = now;
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!HudEditorCenterGuides.isAlwaysVisible()) {
            HudEditorCenterGuides.draw(width, height);
        }
        if (hudHoverType != null && now - hudHoverStartMs >= 1000L
                && draggingWidget == null && resizingWidget == null) {
            renderHoverHint(mouseX, mouseY);
        }
    }

    private void renderHoverHint(int mouseX, int mouseY) {
        String hint = "(right click)";
        String hintColor = EnumChatFormatting.GRAY.toString();

        int iconSize = Math.max(8, hintIconSizePx);
        int rawIx = mouseX + hintIconOffsetX;
        int rawIy = mouseY + hintIconOffsetY;

        int ix = clamp(rawIx, 8, Math.max(8, width - 8 - iconSize));
        int iy = clamp(rawIy, 8, Math.max(8, height - 8 - iconSize));

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                GomodClasspathTextures.bindTexture(mc, SETTING_BUTTON_TEXTURE);
                int tw = GomodClasspathTextures.getWidth(SETTING_BUTTON_TEXTURE, iconSize);
                int th = GomodClasspathTextures.getHeight(SETTING_BUTTON_TEXTURE, iconSize);
                Gui.drawScaledCustomSizeModalRect(ix, iy, 0, 0, tw, th, iconSize, iconSize, tw, th);
            }

            int tx = ix + hintTextOffsetX;
            int ty = iy + iconSize + 2 + hintTextOffsetY;
            GlStateManager.pushMatrix();
            GlStateManager.translate(tx, ty, 0);
            GlStateManager.scale(0.8f, 0.8f, 1f);
            fontRendererObj.drawStringWithShadow(hintColor + hint, 0, 0, 0xFFFFFF);
            GlStateManager.popMatrix();
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    private void renderHudPreviews() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        try {
            InventoryHUD.render();
        } catch (Throwable ignored) {
        }

        try {
            drawPlayerTargetPreview(mc);
        } catch (Throwable ignored) {
        }

        try {
            EntityCow previewCow = createPreviewCow(mc);
            if (previewCow != null && MobHealthDisplay.isPanelMode()) {
                MobHealthDisplay.drawMobPanel(mc, previewCow);
            }
        } catch (Throwable ignored) {
        }

        try {
            HorseDonkeyTracker.drawPreviewPanel(mc);
        } catch (Throwable ignored) {
        }

        try {
            MoreHudFeature.render(mc);
        } catch (Throwable ignored) {
        }

        try {
            BlitzKitTimerHUD.render(mc, true);
        } catch (Throwable ignored) {
        }

        try {
            BlitzChallenges.renderHud(true);
        } catch (Throwable ignored) {
        }

        try {
            IncomingScreenshotOverlay.render(mc, true);
        } catch (Throwable ignored) {
        }

        try {
            InventoryHUD.renderShareInvTooltipLast(mc);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFlameCompactPartType(HudWidgetType type) {
        return type == HudWidgetType.MORE_HUD_FLAME_COMPACT_ICON
                || type == HudWidgetType.MORE_HUD_FLAME_COMPACT_TIMER;
    }

    private static boolean isKillEffectsPartType(HudWidgetType type) {
        return type == HudWidgetType.MORE_HUD_KILL_EFFECTS_ICON
                || type == HudWidgetType.MORE_HUD_KILL_EFFECTS_TIMER;
    }

    private static boolean isCoordsCompassPartType(HudWidgetType type) {
        return type == HudWidgetType.MORE_HUD_COORDS_COMPASS;
    }

    /** Sub-regions positioned via internal nudge (not row anchor); arrow keys use screen-pixel deltas. */
    private static boolean isHudPartWidget(HudWidgetType type) {
        return isCoordsCompassPartType(type) || isKillEffectsPartType(type) || isFlameCompactPartType(type);
    }

    /**
     * Title pills next to draggable regions. No labels on FPS/CPS/ping/arrows (same as on-screen: text only).
     * Compact icon/timer and kill-effect icon/timer are sub-pieces (no separate names).
     */
    private static boolean shouldDrawHudEditorLabel(HudWidgetType type) {
        if (isFlameCompactPartType(type) || isKillEffectsPartType(type) || isCoordsCompassPartType(type)) {
            return false;
        }
        if (type == HudWidgetType.MORE_HUD_FPS || type == HudWidgetType.MORE_HUD_CPS
                || type == HudWidgetType.MORE_HUD_PING || type == HudWidgetType.MORE_HUD_ARROWS) {
            return false;
        }
        return true;
    }

    /**
     * When false, HUD editor draws a red outline instead of white/blue so disabled modules are obvious.
     */
    private boolean isHudModuleEnabled(HudWidgetType type) {
        if (!ConfigManager.isMoreHudEnabled()) {
            switch (type) {
                case MORE_HUD_FPS:
                case MORE_HUD_CPS:
                case MORE_HUD_PING:
                case MORE_HUD_POTIONS:
                case MORE_HUD_ARROWS:
                case MORE_HUD_ARMOR_STATUS:
                case MORE_HUD_COORDS:
                case MORE_HUD_COORDS_COMPASS:
                case MORE_HUD_KILL_EFFECTS:
                case MORE_HUD_KILL_EFFECTS_ICON:
                case MORE_HUD_KILL_EFFECTS_TIMER:
                case MORE_HUD_FLAME:
                case MORE_HUD_FLAME_COMPACT:
                case MORE_HUD_FLAME_COMPACT_ICON:
                case MORE_HUD_FLAME_COMPACT_TIMER:
                    return false;
                default:
                    break;
            }
        }
        switch (type) {
            case INVENTORY_HUD:
                return ConfigManager.isInventoryHUDEnabled();
            case FRIEND_INVENTORY_HUD:
                return ConfigManager.isFriendInvHudEnabled();
            case TEAMMATE_TRACKER:
                return ConfigManager.isPlayerTargetEnabled();
            case MOB_HEALTH_DISPLAY:
                return ConfigManager.isMobHealthDisplayEnabled();
            case HORSE_DONKEY:
                return ConfigManager.isHorseDonkeyDistanceHudEnabled();
            case MORE_HUD_FPS:
                return ConfigManager.isMoreHudFpsEnabled();
            case MORE_HUD_CPS:
                return ConfigManager.isMoreHudCpsEnabled();
            case MORE_HUD_PING:
                return ConfigManager.isMoreHudPingEnabled();
            case MORE_HUD_POTIONS:
                return ConfigManager.isMoreHudPotionsEnabled();
            case MORE_HUD_ARROWS:
                return ConfigManager.isMoreHudArrowsEnabled();
            case MORE_HUD_ARMOR_STATUS:
                return ConfigManager.isMoreHudArmorStatusEnabled();
            case MORE_HUD_COORDS:
                return ConfigManager.isMoreHudCoordsEnabled();
            case MORE_HUD_COORDS_COMPASS:
                return ConfigManager.isMoreHudCoordsEnabled() && ConfigManager.isMoreHudCoordsCompassLayout()
                        && !ConfigManager.isMoreHudCoordsCompassOnly();
            case MORE_HUD_KILL_EFFECTS:
            case MORE_HUD_KILL_EFFECTS_ICON:
            case MORE_HUD_KILL_EFFECTS_TIMER:
                return ConfigManager.isMoreHudKillEffectsEnabled();
            case MORE_HUD_FLAME:
                return ConfigManager.isMoreHudFlameEnabled();
            case MORE_HUD_FLAME_COMPACT:
            case MORE_HUD_FLAME_COMPACT_ICON:
            case MORE_HUD_FLAME_COMPACT_TIMER:
                if (!ConfigManager.isMoreHudFlameEnabled()) {
                    return false;
                }
                if (ConfigManager.isMoreHudFlameCompact()) {
                    return true;
                }
                // In Effects HUD (merged): the live row is part of the Effects HUD layout.
                return false;
            case BLITZ_KIT_TIMER_HUD:
                return ConfigManager.isBlitzKitTimerHudEnabled();
            case BLITZ_CHALLENGES_HUD:
                return ConfigManager.isBlitzChallengesHudEnabled();
            case INCOMING_SCREENSHOT_OVERLAY:
                return ConfigManager.isIncomingScreenshotOverlayEnabled();
            default:
                return true;
        }
    }

    private void drawHudBounds(int mouseX, int mouseY, boolean drawFills, boolean drawChrome) {
        List<HudLayout> layouts = getHudLayouts();
        HudLayout hovered = findHoveredLayout(layouts, mouseX, mouseY);
        List<LabelPlacement> placedEditorLabels = new ArrayList<LabelPlacement>();
        List<HudFillPlacement> fillPlacements = drawFills ? new ArrayList<HudFillPlacement>() : null;

        for (HudLayout layout : layouts) {
            boolean modOn = isHudModuleEnabled(layout.type);
            boolean active = layout.type == resizingWidget || layout.type == selectedWidget
                    || (layout.type == draggingWidget && dragPointerMoved);
            boolean hover = layout == hovered;
            ResizeCorner hoveredCorner = getHoveredResizeCorner(layout, mouseX, mouseY);
            int borderColor;
            int fillColor;
            if (active) {
                if (modOn && layout.type == HudWidgetType.MORE_HUD_FLAME_COMPACT_ICON) {
                    borderColor = 0xFFFFD700;
                    fillColor = 0x33FFD700;
                } else if (modOn && layout.type == HudWidgetType.MORE_HUD_FLAME_COMPACT_TIMER) {
                    borderColor = 0xFF00CED1;
                    fillColor = 0x3300CED1;
                } else if (modOn && layout.type == HudWidgetType.MORE_HUD_KILL_EFFECTS_ICON) {
                    borderColor = 0xFF55FF55;
                    fillColor = 0x3355FF55;
                } else if (modOn && layout.type == HudWidgetType.MORE_HUD_KILL_EFFECTS_TIMER) {
                    borderColor = 0xFF00CED1;
                    fillColor = 0x3300CED1;
                } else {
                    borderColor = 0xFFF5C542;
                    fillColor = 0x22F5C542;
                }
            } else if (!modOn) {
                borderColor = hover ? 0xFFFF7777 : 0xAAFF4444;
                fillColor = hover ? 0x22FF5555 : 0x11000000;
            } else if (layout.type == HudWidgetType.MORE_HUD_FLAME_COMPACT_ICON) {
                borderColor = hover ? 0xFFE5C100 : 0xAAFFD700;
                fillColor = hover ? 0x22FFD700 : 0x11000000;
            } else if (layout.type == HudWidgetType.MORE_HUD_FLAME_COMPACT_TIMER) {
                borderColor = hover ? 0xFF5FDEE6 : 0xAA00CED1;
                fillColor = hover ? 0x2200CED1 : 0x11000000;
            } else if (layout.type == HudWidgetType.MORE_HUD_KILL_EFFECTS_ICON) {
                borderColor = hover ? 0xFF8BFF8B : 0xAA55FF55;
                fillColor = hover ? 0x2255FF55 : 0x11000000;
            } else if (layout.type == HudWidgetType.MORE_HUD_KILL_EFFECTS_TIMER) {
                borderColor = hover ? 0xFF5FDEE6 : 0xAA00CED1;
                fillColor = hover ? 0x2200CED1 : 0x11000000;
            } else {
                borderColor = hover ? 0xFF7FDBFF : 0xAAFFFFFF;
                fillColor = hover ? 0x187FDBFF : 0x11000000;
            }

            if (drawFills && shouldDrawHudBoundsFillBehindPreview(layout.type)) {
                fillPlacements.add(new HudFillPlacement(layout.left, layout.top, layout.width, layout.height, fillColor));
            }

            if (!drawChrome) {
                continue;
            }

            drawBorder(layout.left, layout.top, layout.width, layout.height, borderColor);

            if (shouldDrawHudEditorLabel(layout.type)) {
                LabelPlacement labelPlacement = computeLabelPlacement(layout, layouts, placedEditorLabels);
                placedEditorLabels.add(labelPlacement);
                Gui.drawRect(labelPlacement.x, labelPlacement.y, labelPlacement.x + labelPlacement.width,
                        labelPlacement.y + labelPlacement.height, 0xC0000000);
                fontRendererObj.drawString(layout.label, labelPlacement.x + 4, labelPlacement.y + 1, borderColor);
            }

            if (hoveredCorner != null || (active && activeResizeCorner != null)) {
                ResizeCorner accentCorner = active && activeResizeCorner != null ? activeResizeCorner : hoveredCorner;
                int accentColor = active ? 0xFFF5C542 : (!modOn ? 0xFFFF9999 : 0xFF7FDBFF);
                drawResizeCornerAccent(layout, accentCorner, accentColor);
            }
        }

        if (fillPlacements != null) {
            drawNonOverlappingHudFills(fillPlacements);
        }
    }

    /**
     * Draws the editor's translucent module fills as one coverage layer. A normal alpha draw for every rectangle
     * makes intersections darker because the same background is blended twice. Later layouts take priority in an
     * overlap, but every screen pixel receives at most one fill draw.
     */
    private static void drawNonOverlappingHudFills(List<HudFillPlacement> placements) {
        List<HudFillRect> covered = new ArrayList<HudFillRect>();
        for (int i = placements.size() - 1; i >= 0; i--) {
            HudFillPlacement placement = placements.get(i);
            HudFillRect source = new HudFillRect(placement.left, placement.top,
                    placement.left + placement.width, placement.top + placement.height);
            List<HudFillRect> uncovered = new ArrayList<HudFillRect>();
            uncovered.add(source);
            for (HudFillRect prior : covered) {
                List<HudFillRect> next = new ArrayList<HudFillRect>();
                for (HudFillRect piece : uncovered) {
                    subtractHudFillRect(piece, prior, next);
                }
                uncovered = next;
                if (uncovered.isEmpty()) {
                    break;
                }
            }
            for (HudFillRect piece : uncovered) {
                if (piece.right > piece.left && piece.bottom > piece.top) {
                    Gui.drawRect(piece.left, piece.top, piece.right, piece.bottom, placement.color);
                }
            }
            covered.add(source);
        }
    }

    private static void subtractHudFillRect(HudFillRect source, HudFillRect cutter, List<HudFillRect> output) {
        int left = Math.max(source.left, cutter.left);
        int top = Math.max(source.top, cutter.top);
        int right = Math.min(source.right, cutter.right);
        int bottom = Math.min(source.bottom, cutter.bottom);
        if (left >= right || top >= bottom) {
            output.add(source);
            return;
        }
        if (source.top < top) {
            output.add(new HudFillRect(source.left, source.top, source.right, top));
        }
        if (bottom < source.bottom) {
            output.add(new HudFillRect(source.left, bottom, source.right, source.bottom));
        }
        if (source.left < left) {
            output.add(new HudFillRect(source.left, top, left, bottom));
        }
        if (right < source.right) {
            output.add(new HudFillRect(right, top, source.right, bottom));
        }
    }

    /**
     * Preview modules draw their real configurable backgrounds in renderHudPreviews. Adding a
     * second translucent editor rectangle underneath would still stack alpha at intersections,
     * so editor fills are intentionally omitted for every preview module.
     */
    private static boolean shouldDrawHudBoundsFillBehindPreview(HudWidgetType type) {
        return false;
    }

    private HudLayout findHoveredLayout(List<HudLayout> layouts, int mouseX, int mouseY) {
        HudLayout hovered = null;
        for (HudLayout layout : layouts) {
            if (layout.contains(mouseX, mouseY)) {
                hovered = layout;
            }
        }
        return hovered;
    }

    private LabelPlacement computeLabelPlacement(HudLayout layout, List<HudLayout> allLayouts,
            List<LabelPlacement> placedLabels) {
        int labelWidth = fontRendererObj.getStringWidth(layout.label) + 8;
        int labelHeight = 10;
        int gap = 2;

        List<LabelPlacement> candidates = new ArrayList<LabelPlacement>();

        int aboveY = layout.top - labelHeight - gap;
        int belowY = layout.top + layout.height + gap;
        int leftX = layout.left - labelWidth - gap;
        int rightX = layout.left + layout.width + gap;

        if (layout.type == HudWidgetType.MORE_HUD_COORDS) {
            int[] ref = coordsHudLabelReferenceBounds(layout, allLayouts);
            int refLeft = ref[0];
            int refTop = ref[1];
            int refWidth = ref[2];
            int refHeight = ref[3];
            int refCenterX = refLeft + refWidth / 2;
            int labelX = clamp(refCenterX - labelWidth / 2, 2, Math.max(2, width - labelWidth - 2));
            int refAboveY = refTop - labelHeight - gap;
            int refBelowY = refTop + refHeight + gap;
            int refLeftX = refLeft - labelWidth - gap;
            int refRightX = refLeft + refWidth + gap;
            // Prefer above the combined text + compass footprint (compass may sit higher on the right).
            candidates.add(new LabelPlacement(labelX, refAboveY, labelWidth, labelHeight));
            candidates.add(new LabelPlacement(labelX, refBelowY, labelWidth, labelHeight));
            candidates.add(new LabelPlacement(refLeftX, refTop, labelWidth, labelHeight));
            candidates.add(new LabelPlacement(refRightX, refTop, labelWidth, labelHeight));
        } else {
            int labelX = clamp(layout.left, 2, Math.max(2, width - labelWidth - 2));
            candidates.add(new LabelPlacement(labelX, aboveY, labelWidth, labelHeight));
            candidates.add(new LabelPlacement(labelX, belowY, labelWidth, labelHeight));
            candidates.add(new LabelPlacement(leftX, layout.top, labelWidth, labelHeight));
            candidates.add(new LabelPlacement(rightX, layout.top, labelWidth, labelHeight));
        }

        for (LabelPlacement cand : candidates) {
            if (labelPlacementClearOfHudEditorPeers(cand, layout, allLayouts, placedLabels)) {
                return cand;
            }
        }

        int fallbackLabelX;
        int fallbackAboveY;
        int fallbackBelowY;
        if (layout.type == HudWidgetType.MORE_HUD_COORDS) {
            int[] ref = coordsHudLabelReferenceBounds(layout, allLayouts);
            int refCenterX = ref[0] + ref[2] / 2;
            fallbackLabelX = clamp(refCenterX - labelWidth / 2, 2, Math.max(2, width - labelWidth - 2));
            fallbackAboveY = ref[1] - labelHeight - gap;
            fallbackBelowY = ref[1] + ref[3] + gap;
        } else {
            fallbackLabelX = clamp(layout.left, 2, Math.max(2, width - labelWidth - 2));
            fallbackAboveY = aboveY;
            fallbackBelowY = belowY;
        }
        LabelPlacement above = new LabelPlacement(fallbackLabelX, fallbackAboveY, labelWidth, labelHeight);
        LabelPlacement below = new LabelPlacement(fallbackLabelX, fallbackBelowY, labelWidth, labelHeight);
        if (above.isWithin(width, height)
                && labelPlacementClearOfHudEditorPeers(above, layout, allLayouts, placedLabels)) {
            return above;
        }
        if (labelPlacementClearOfHudEditorPeers(below, layout, allLayouts, placedLabels)) {
            return below;
        }
        return above;
    }

    /** Union of coords text block and compass drag region for editor label placement. */
    private static int[] coordsHudLabelReferenceBounds(HudLayout textLayout, List<HudLayout> allLayouts) {
        int refLeft = textLayout.left;
        int refTop = textLayout.top;
        int refRight = textLayout.left + textLayout.width;
        int refBottom = textLayout.top + textLayout.height;
        for (HudLayout other : allLayouts) {
            if (other.type == HudWidgetType.MORE_HUD_COORDS_COMPASS) {
                refLeft = Math.min(refLeft, other.left);
                refTop = Math.min(refTop, other.top);
                refRight = Math.max(refRight, other.left + other.width);
                refBottom = Math.max(refBottom, other.top + other.height);
            }
        }
        return new int[] { refLeft, refTop, refRight - refLeft, refBottom - refTop };
    }

    /**
     * Label is fully on-screen, does not sit on its own HUD rect, and does not overlap other HUD rects
     * or labels already chosen for this frame (same ordering as {@link #getHudLayouts}).
     */
    private boolean labelPlacementClearOfHudEditorPeers(LabelPlacement cand, HudLayout self, List<HudLayout> allLayouts,
            List<LabelPlacement> placedLabels) {
        if (!cand.isWithin(width, height)) {
            return false;
        }
        if (cand.intersects(self)) {
            return false;
        }
        for (HudLayout other : allLayouts) {
            if (other == self) {
                continue;
            }
            if (cand.intersects(other)) {
                return false;
            }
        }
        for (LabelPlacement p : placedLabels) {
            if (cand.intersects(p)) {
                return false;
            }
        }
        return true;
    }

    /** Screen rects for overlap checks ({@code [left, top, right, bottom]}). */
    private List<int[]> collectHudModuleScreenRects() {
        List<HudLayout> layouts = getHudLayouts();
        List<int[]> rects = new ArrayList<int[]>(layouts.size());
        for (HudLayout layout : layouts) {
            rects.add(new int[] { layout.left, layout.top, layout.left + layout.width, layout.top + layout.height });
        }
        return rects;
    }

    private List<HudLayout> getHudLayouts() {
        List<HudLayout> layouts = new ArrayList<HudLayout>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return layouts;
        }

        layouts.add(getInventoryHudLayout());
        layouts.add(getFriendInventoryHudLayout(mc));
        layouts.add(getPlayerTargetLayout(mc));

        HudLayout mobHealthLayout = getMobHealthLayout(mc);
        if (mobHealthLayout != null) {
            layouts.add(mobHealthLayout);
        }

        layouts.add(getHorseDonkeyLayout(mc));
        layouts.add(getMoreHudFpsLayout(mc));
        layouts.add(getMoreHudCpsLayout(mc));
        layouts.add(getMoreHudPingLayout(mc));
        layouts.add(getMoreHudPotionsLayout(mc));
        layouts.add(getMoreHudArrowsLayout(mc));
        layouts.add(getMoreHudArmorStatusLayout(mc));
        layouts.add(getMoreHudCoordsLayout(mc));
        // Compass drag handle only when coords + compass share the row (not compass-only — one box covers the ring).
        if (ConfigManager.isMoreHudCoordsCompassLayout() && !ConfigManager.isMoreHudCoordsCompassOnly()) {
            layouts.add(getMoreHudCoordsCompassLayout(mc));
        }
        // Always expose hit targets (red outline when disabled) so layout matches {@link MoreHudFeature#render} in editor.
        layouts.add(getMoreHudKillEffectsLayout(mc));
        layouts.add(getMoreHudKillEffectsIconLayout(mc));
        layouts.add(getMoreHudKillEffectsTimerLayout(mc));
        // Keep the separate Flaming Arrows HUD editable/visible even when display mode is "In Effects HUD";
        // isHudModuleEnabled paints it red while the live row is merged into Effects HUD.
        layouts.add(getMoreHudFlameCompactLayout(mc));
        layouts.add(getMoreHudFlameCompactIconLayout(mc));
        layouts.add(getMoreHudFlameCompactTimerLayout(mc));
        if (!ConfigManager.isMoreHudFlameCompact() && !ConfigManager.isMoreHudPotionsShowFlamingArrows()) {
            layouts.add(getMoreHudFlameLayout(mc));
        }
        if (ConfigManager.isBlitzKitTimerHudEnabled()) {
            layouts.add(getBlitzKitTimerHudLayout(mc));
        }
        layouts.add(getBlitzChallengesHudLayout(mc));
        layouts.add(getIncomingScreenshotHudLayout(mc));
        return layouts;
    }

    private HudLayout getBlitzChallengesHudLayout(Minecraft mc) {
        int[] bounds = new int[4];
        BlitzChallenges.getHudBounds(mc, true, bounds);
        int anchorX = BlitzChallenges.getHudPosX();
        int anchorY = BlitzChallenges.getHudPosY();
        return new HudLayout(HudWidgetType.BLITZ_CHALLENGES_HUD, "Blitz Challenges", anchorX, anchorY,
                bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    private HudLayout getIncomingScreenshotHudLayout(Minecraft mc) {
        int[] b = new int[4];
        IncomingScreenshotOverlay.getHudBounds(mc, b);
        return new HudLayout(HudWidgetType.INCOMING_SCREENSHOT_OVERLAY, "Friend screenshots",
                b[0], b[1], b[0], b[1], b[2], b[3]);
    }

    private HudLayout getBlitzKitTimerHudLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int anchorX = sr.getScaledWidth() / 2 + ConfigManager.getBlitzKitTimerHudOffsetCenterX();
        int anchorY = ConfigManager.getBlitzKitTimerHudPosY();
        int[] bounds = new int[4];
        BlitzKitTimerHUD.getHudBounds(mc, true, bounds);
        return new HudLayout(HudWidgetType.BLITZ_KIT_TIMER_HUD, "Blitz Kit Timer", anchorX, anchorY,
                bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    private HudLayout getMoreHudFpsLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFpsScale();
        int cx = sw / 2 + ConfigManager.getMoreHudFpsOffsetCenterX();
        int cy = ConfigManager.getMoreHudFpsPosY();
        int uw = MoreHudFeature.measureFpsWidth(mc);
        int uh = MoreHudFeature.measureFpsHeight(mc);
        int[] b = new int[4];
        HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                HudOpacitySliderBoxInsets.Module.FPS, ConfigManager.isMoreHudFpsShadow());
        MoreHudFeature.centeredTextRowScreenBounds(cx, cy, uw, uh, sc, pad.up, b);
        return new HudLayout(HudWidgetType.MORE_HUD_FPS, "FPS HUD", cx, cy, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudCpsLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudCpsScale();
        int cx = sw / 2 + ConfigManager.getMoreHudCpsOffsetCenterX();
        int cy = ConfigManager.getMoreHudCpsPosY();
        int uw = MoreHudFeature.measureCpsWidth(mc);
        int uh = MoreHudFeature.measureCpsHeight(mc);
        int[] b = new int[4];
        HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                HudOpacitySliderBoxInsets.Module.CPS, ConfigManager.isMoreHudCpsShadow());
        MoreHudFeature.centeredTextRowScreenBounds(cx, cy, uw, uh, sc, pad.up, b);
        return new HudLayout(HudWidgetType.MORE_HUD_CPS, "", cx, cy, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudPingLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudPingScale();
        int cx = sw / 2 + ConfigManager.getMoreHudPingOffsetCenterX();
        int cy = ConfigManager.getMoreHudPingPosY();
        int uw = MoreHudFeature.measurePingWidth(mc);
        int uh = MoreHudFeature.measurePingHeight(mc);
        int[] b = new int[4];
        HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.splitCenteredTextRowPad(
                HudOpacitySliderBoxInsets.Module.PING, ConfigManager.isMoreHudPingShadow());
        MoreHudFeature.centeredTextRowScreenBounds(cx, cy, uw, uh, sc, pad.up, b);
        return new HudLayout(HudWidgetType.MORE_HUD_PING, "Ping row", cx, cy, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudPotionsLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudPotionsScale();
        int gap = ConfigManager.getMoreHudRowGap();
        int cx = sw / 2 + ConfigManager.getMoreHudPotionsOffsetCenterX();
        int cy = ConfigManager.getMoreHudPotionsPosY();
        int[] b = new int[4];
        MoreHudFeature.potionsBlockScreenBounds(mc, cx, cy, gap, sc, true,
                MoreHudFeature.includeFlamingArrowsInPotionsBlock(mc), b);
        return new HudLayout(HudWidgetType.MORE_HUD_POTIONS, "Effects HUD", cx, cy, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudArrowsLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudArrowsScale();
        int cx = sw / 2 + ConfigManager.getMoreHudArrowsOffsetCenterX();
        int cy = ConfigManager.getMoreHudArrowsPosY();
        int[] b = new int[4];
        MoreHudFeature.arrowRowScreenBounds(cx, cy, sc, b);
        return new HudLayout(HudWidgetType.MORE_HUD_ARROWS, "Arrows count", cx, cy, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudArmorStatusLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudArmorStatusScale();
        int cx = sw / 2 + ConfigManager.getMoreHudArmorStatusOffsetCenterX();
        int cy = ConfigManager.getMoreHudArmorStatusPosY();
        int[] b = new int[4];
        MoreHudArmorStatusFeature.screenBounds(cx, cy, sc, b);
        return new HudLayout(HudWidgetType.MORE_HUD_ARMOR_STATUS, "Armor status", cx, cy, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudCoordsLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudCoordsScale();
        int leftX = sw / 2 + ConfigManager.getMoreHudCoordsOffsetCenterX();
        int topY = ConfigManager.getMoreHudCoordsPosY();
        int[] b = new int[4];
        if (ConfigManager.isMoreHudCoordsVertical() || ConfigManager.isMoreHudCoordsCompassOnly()) {
            MoreHudCoordsFeature.screenBounds(mc, mc.fontRendererObj, leftX, topY, sc, true, b);
        } else {
            MoreHudCoordsFeature.textBlockScreenBounds(mc, mc.fontRendererObj, leftX, topY, sc, true, b);
        }
        return new HudLayout(HudWidgetType.MORE_HUD_COORDS, "Coords HUD", leftX, topY, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudCoordsCompassLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudCoordsScale();
        int leftX = sw / 2 + ConfigManager.getMoreHudCoordsOffsetCenterX();
        int topY = ConfigManager.getMoreHudCoordsPosY();
        int[] b = new int[4];
        int[] anchor = new int[2];
        MoreHudCoordsFeature.compassPartScreenBounds(mc, mc.fontRendererObj, leftX, topY, sc, true, b, anchor);
        return new HudLayout(HudWidgetType.MORE_HUD_COORDS_COMPASS,
                "", anchor[0], anchor[1], b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudFlameLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFlameScale();
        int cx = sw / 2 + ConfigManager.getMoreHudFlameOffsetCenterX();
        int cy = ConfigManager.getMoreHudFlamePosY();
        int[] b = new int[4];
        MoreHudFeature.flameRowScreenBounds(cx, cy, sc, b);
        return new HudLayout(HudWidgetType.MORE_HUD_FLAME, "Flaming arrows", cx, cy, b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudKillEffectsLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int cx = sw / 2 + ConfigManager.getMoreHudKillEffectsOffsetCenterX();
        int cy = ConfigManager.getMoreHudKillEffectsPosY();
        int[] iconBounds = new int[4];
        int[] iconAnchor = new int[2];
        int[] timerBounds = new int[4];
        int[] timerAnchor = new int[2];
        MoreHudFeature.killEffectsIconPartScreenBounds(mc, cx, cy, ConfigManager.getMoreHudKillEffectsScale(), true, iconBounds, iconAnchor);
        MoreHudFeature.killEffectsTimerPartScreenBounds(mc, cx, cy, ConfigManager.getMoreHudKillEffectsScale(), true, timerBounds, timerAnchor);
        int[] union = unionBounds(iconBounds, timerBounds);
        return new HudLayout(HudWidgetType.MORE_HUD_KILL_EFFECTS, "Kill effects", cx, cy, union[0], union[1], union[2], union[3]);
    }

    private HudLayout getMoreHudKillEffectsIconLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudKillEffectsScale();
        int cx = sw / 2 + ConfigManager.getMoreHudKillEffectsOffsetCenterX();
        int cy = ConfigManager.getMoreHudKillEffectsPosY();
        int[] b = new int[4];
        int[] anchor = new int[2];
        MoreHudFeature.killEffectsIconPartScreenBounds(mc, cx, cy, sc, true, b, anchor);
        return new HudLayout(HudWidgetType.MORE_HUD_KILL_EFFECTS_ICON,
                "", anchor[0], anchor[1], b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudKillEffectsTimerLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudKillEffectsScale();
        int cx = sw / 2 + ConfigManager.getMoreHudKillEffectsOffsetCenterX();
        int cy = ConfigManager.getMoreHudKillEffectsPosY();
        int[] b = new int[4];
        int[] anchor = new int[2];
        MoreHudFeature.killEffectsTimerPartScreenBounds(mc, cx, cy, sc, true, b, anchor);
        return new HudLayout(HudWidgetType.MORE_HUD_KILL_EFFECTS_TIMER,
                "", anchor[0], anchor[1], b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudFlameCompactLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        int cx = sw / 2 + ConfigManager.getMoreHudFlameCompactOffsetCenterX();
        int cy = ConfigManager.getMoreHudFlameCompactPosY();
        int[] iconBounds = new int[4];
        int[] iconAnchor = new int[2];
        int[] timerBounds = new int[4];
        int[] timerAnchor = new int[2];
        MoreHudFeature.flameCompactIconPartScreenBounds(mc, cx, cy, sc, true, iconBounds, iconAnchor);
        MoreHudFeature.flameCompactTimerPartScreenBounds(mc, cx, cy, sc, true, timerBounds, timerAnchor);
        int[] union = unionBounds(iconBounds, timerBounds);
        int anchorX = union[0] + union[2] / 2;
        int anchorY = union[1] + union[3] / 2;
        return new HudLayout(HudWidgetType.MORE_HUD_FLAME_COMPACT, "Flaming arrows",
                anchorX, anchorY, union[0], union[1], union[2], union[3]);
    }

    private HudLayout getMoreHudFlameCompactIconLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        int cx = sw / 2 + ConfigManager.getMoreHudFlameCompactOffsetCenterX();
        int cy = ConfigManager.getMoreHudFlameCompactPosY();
        int[] b = new int[4];
        int[] anchor = new int[2];
        MoreHudFeature.flameCompactIconPartScreenBounds(mc, cx, cy, sc, true, b, anchor);
        return new HudLayout(HudWidgetType.MORE_HUD_FLAME_COMPACT_ICON,
                "", anchor[0], anchor[1], b[0], b[1], b[2], b[3]);
    }

    private HudLayout getMoreHudFlameCompactTimerLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudFlameCompactScale();
        int cx = sw / 2 + ConfigManager.getMoreHudFlameCompactOffsetCenterX();
        int cy = ConfigManager.getMoreHudFlameCompactPosY();
        int[] b = new int[4];
        int[] anchor = new int[2];
        MoreHudFeature.flameCompactTimerPartScreenBounds(mc, cx, cy, sc, true, b, anchor);
        return new HudLayout(HudWidgetType.MORE_HUD_FLAME_COMPACT_TIMER,
                "", anchor[0], anchor[1], b[0], b[1], b[2], b[3]);
    }

    private static int[] unionBounds(int[] a, int[] b) {
        int left = Math.min(a[0], b[0]);
        int top = Math.min(a[1], b[1]);
        int right = Math.max(a[0] + a[2], b[0] + b[2]);
        int bottom = Math.max(a[1] + a[3], b[1] + b[3]);
        return new int[] { left, top, Math.max(1, right - left), Math.max(1, bottom - top) };
    }

    private HudLayout getInventoryHudLayout() {
        int[] b = new int[4];
        InventoryHUD.getHudScreenBounds(b);
        int pad = InventoryHUD.getHudEditorBoundsPadding();
        int left = b[0] - pad;
        int top = b[1] - pad;
        return new HudLayout(HudWidgetType.INVENTORY_HUD, "Inventory HUD", b[0], b[1], left, top,
                b[2] + pad * 2, b[3] + pad * 2);
    }

    private HudLayout getFriendInventoryHudLayout(Minecraft mc) {
        float scale = FriendInventoryHUD.getScale();
        int x = FriendInventoryHUD.getCustomX();
        int y = FriendInventoryHUD.getCustomY();
        int gap = Math.max(2, (int) (2.0f * scale));
        int headSize = (int) Math.max(8.0f, Math.min(24.0f, 8.0f * scale));
        int mainGridWidth = (int) (9.0f * 18.0f * scale);
        int width = Math.max(mainGridWidth, headSize + 4 + mc.fontRendererObj.getStringWidth(FRIEND_PREVIEW_NAME));

        int height = Math.max(headSize, 10) + gap;
        if (FriendInventoryHUD.isShowRemoteHealth()) {
            height += mc.fontRendererObj.FONT_HEIGHT + gap;
        }
        if (FriendInventoryHUD.isShowRemoteXp()) {
            height += mc.fontRendererObj.FONT_HEIGHT + gap;
        }
        height += (int) (4.0f * scale * 18.0f / 4.0f);
        height += gap;
        height += (int) (54.0f * scale);
        height += gap;
        height += (int) Math.ceil(22.0f * scale);
        int left = x - Math.max(4, (int) Math.ceil(scale * 3.0f));
        int top = y - Math.max(4, (int) Math.ceil(scale * 3.0f));
        int adjustedWidth = width + (x - left) + Math.max(2, (int) Math.ceil(scale * 2.0f));
        int adjustedHeight = height + (y - top);

        return new HudLayout(HudWidgetType.FRIEND_INVENTORY_HUD, "Friend Inventory HUD", x, y, left, top, adjustedWidth, adjustedHeight);
    }

    private HudLayout getPlayerTargetLayout(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int anchorX;
        int anchorY;
        switch (PlayerTarget.getPosition()) {
            case TOP_RIGHT:
                anchorX = sr.getScaledWidth() - 200;
                anchorY = 50;
                break;
            case BOTTOM_LEFT:
                anchorX = 6;
                anchorY = sr.getScaledHeight() - 100;
                break;
            case BOTTOM_RIGHT:
                anchorX = sr.getScaledWidth() - 200;
                anchorY = sr.getScaledHeight() - 100;
                break;
            case CUSTOM:
                anchorX = PlayerTarget.getCustomX();
                anchorY = PlayerTarget.getCustomY();
                break;
            case TOP_LEFT:
            default:
                anchorX = 6;
                anchorY = 50;
                break;
        }

        int headSize = 20;
        int padding = 4;
        int iconSize = 16;
        String displayName = EnumChatFormatting.WHITE + "[TEAM] " + EnumChatFormatting.GREEN + "GallantDrop1196";
        String hpText = EnumChatFormatting.GREEN + "20 " + EnumChatFormatting.RED + "\u2764";
        String distText = PlayerTarget.isShowVerticalDistance()
                ? "15.5m (" + EnumChatFormatting.GREEN + "\u25B2" + EnumChatFormatting.RESET + " 2.3)"
                : "15.5m";
        int textX = anchorX + headSize + 6;
        int nameWidth = fontRendererObj.getStringWidth(displayName);
        int hpWidth = fontRendererObj.getStringWidth(hpText);
        int distWidth = fontRendererObj.getStringWidth(distText) + iconSize + 4;
        int contentWidth = Math.max(nameWidth, Math.max(hpWidth, distWidth));
        int iconLineY = anchorY + headSize + 4;
        int equipmentWidth = (5 * 18) + (4 * 2);
        int equipmentHeight = 18 + 4;
        if (PlayerTarget.isShowTauntInfo()) {
            equipmentHeight += fontRendererObj.FONT_HEIGHT + 4;
        }
        contentWidth = Math.max(contentWidth, equipmentWidth);

        int rawLeft = anchorX - padding;
        int rawRight = textX + contentWidth + padding + 2;
        int rawBottom = Math.max(anchorY + headSize, iconLineY + iconSize) + equipmentHeight + padding;
        int rawTop = anchorY - padding;

        float scale = PlayerTarget.getScale();
        int left = Math.round(anchorX + (rawLeft - anchorX) * scale);
        int top = Math.round(anchorY + (rawTop - anchorY) * scale);
        int width = Math.max(1, Math.round((rawRight - rawLeft) * scale));
        int height = Math.max(1, Math.round((rawBottom - rawTop) * scale));

        return new HudLayout(HudWidgetType.TEAMMATE_TRACKER, "Teammate Tracker", anchorX, anchorY, left, top, width, height);
    }

    private HudLayout getMobHealthLayout(Minecraft mc) {
        EntityCow previewCow = createPreviewCow(mc);
        if (previewCow == null) {
            return null;
        }

        float mobHeight = previewCow.height;
        float mobWidth = previewCow.width;
        int mobScale = (int) Math.max(18, Math.min(25, mobHeight * 10));
        float horizontalSize = (float) Math.sqrt(mobWidth * mobWidth + mobWidth * mobWidth);
        float verticalProjection = mobHeight * 1.3F;
        int mobRenderWidth = (int) (horizontalSize * mobScale);
        int mobRenderHeight = (int) (verticalProjection * mobScale);
        mobRenderWidth = Math.max(20, Math.min(55, mobRenderWidth));
        mobRenderHeight = Math.max(20, Math.min(70, mobRenderHeight));

        int padding = 4;
        String mobName = previewCow.getName();
        String hpText = formatMobHealthText(previewCow.getHealth(), previewCow.getMaxHealth());
        int textWidth = Math.max(fontRendererObj.getStringWidth(mobName), fontRendererObj.getStringWidth(hpText));
        int mobAreaWidth = mobRenderWidth + padding * 3;
        int panelWidth = mobAreaWidth + textWidth + padding * 2;
        int textAreaHeight = fontRendererObj.FONT_HEIGHT * 2 + 4;
        int mobAreaHeight = mobRenderHeight + padding * 2;
        int panelHeight = Math.max(mobAreaHeight, textAreaHeight + padding * 2);
        int anchorX = MobHealthDisplay.getPanelX();
        int anchorY = MobHealthDisplay.getPanelY();
        int top = Math.round(anchorY - panelHeight * MobHealthDisplay.getScale());
        int width = Math.max(1, Math.round(panelWidth * MobHealthDisplay.getScale()));
        int height = Math.max(1, Math.round(panelHeight * MobHealthDisplay.getScale()));

        return new HudLayout(HudWidgetType.MOB_HEALTH_DISPLAY, "Mob Health Display", anchorX, anchorY,
                anchorX, top, width, height);
    }

    private HudLayout getHorseDonkeyLayout(Minecraft mc) {
        String label = HorseDonkeyTracker.getTrackedMountLabel() != null ? HorseDonkeyTracker.getTrackedMountLabel() : "Horse";
        double distance = HorseDonkeyTracker.getTrackedDistance() >= 0.0D ? HorseDonkeyTracker.getTrackedDistance() : 17.4D;
        String title = label + " distance";
        String valueText = formatHorseDistance(distance);
        String blocksText = " blocks";
        int padding = 4;
        int iconSize = Math.max(14, fontRendererObj.FONT_HEIGHT + 5);
        int rowGap = 3;
        int rowWidth = iconSize + rowGap + fontRendererObj.getStringWidth(valueText)
                + fontRendererObj.getStringWidth(blocksText);
        int rowHeight = Math.max(iconSize, fontRendererObj.FONT_HEIGHT);
        int baseWidth = Math.max(fontRendererObj.getStringWidth(title), rowWidth) + padding * 2;
        int baseHeight = padding * 2 + fontRendererObj.FONT_HEIGHT + 2 + rowHeight;
        int x = HorseDonkeyTracker.getPanelX();
        int y = HorseDonkeyTracker.getPanelY();
        int width = Math.max(1, Math.round(baseWidth * HorseDonkeyTracker.getScale()));
        int height = Math.max(1, Math.round(baseHeight * HorseDonkeyTracker.getScale()));
        return new HudLayout(HudWidgetType.HORSE_DONKEY, "Horse tracker", x, y, x, y, width, height);
    }

    private void drawPlayerTargetPreview(Minecraft mc) {
        HudLayout layout = getPlayerTargetLayout(mc);
        int anchorX = layout.anchorX;
        int anchorY = layout.anchorY;

        String displayName = EnumChatFormatting.WHITE + "[TEAM] " + EnumChatFormatting.GREEN + "GallantDrop1196";
        String hpText = EnumChatFormatting.GREEN + "20 " + EnumChatFormatting.RED + "\u2764";
        String distText = PlayerTarget.isShowVerticalDistance()
                ? "15.5m (" + EnumChatFormatting.GREEN + "\u25B2" + EnumChatFormatting.RESET + " 2.3)"
                : "15.5m";

        int headSize = 20;
        int padding = 4;
        int iconSize = 16;
        int textX = anchorX + headSize + 6;
        int nameWidth = fontRendererObj.getStringWidth(displayName);
        int hpWidth = fontRendererObj.getStringWidth(hpText);
        int distWidth = fontRendererObj.getStringWidth(distText) + iconSize + 4;
        int contentWidth = Math.max(nameWidth, Math.max(hpWidth, distWidth));
        int iconLineY = anchorY + headSize + 4;
        int equipmentWidth = (5 * 18) + (4 * 2);
        int equipmentHeight = 18 + 4;
        if (PlayerTarget.isShowTauntInfo()) {
            equipmentHeight += fontRendererObj.FONT_HEIGHT + 4;
        }
        contentWidth = Math.max(contentWidth, equipmentWidth);

        int left = anchorX - padding;
        int right = textX + contentWidth + padding + 2;
        int bottom = Math.max(anchorY + headSize, iconLineY + iconSize) + equipmentHeight + padding;
        int top = anchorY - padding;

        GlStateManager.pushMatrix();
        GlStateManager.translate(anchorX, anchorY, 0.0F);
        GlStateManager.scale(PlayerTarget.getScale(), PlayerTarget.getScale(), 1.0F);
        GlStateManager.translate(-anchorX, -anchorY, 0.0F);
        HudEditorBackgroundMask.pushTransform(anchorX, anchorY, PlayerTarget.getScale());

        if (PlayerTarget.isShowBackground()) {
            HudEditorBackgroundMask.drawRect(left, top, right, bottom,
                    (PlayerTarget.getBackgroundOpacity() << 24) | 0x000000);
        }

        ResourceLocation skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
        try {
            if (mc.theWorld != null && mc.getNetHandler() != null) {
                for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
                    if (!"GallantDrop1196".equalsIgnoreCase(player.getName())) {
                        continue;
                    }
                    net.minecraft.client.network.NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                    if (info != null && info.getLocationSkin() != null) {
                        skin = info.getLocationSkin();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(skin);
        Gui.drawScaledCustomSizeModalRect(anchorX, anchorY, 8, 8, 8, 8, headSize, headSize, 64, 64);
        Gui.drawScaledCustomSizeModalRect(anchorX, anchorY, 40, 8, 8, 8, headSize, headSize, 64, 64);
        GlStateManager.disableBlend();

        fontRendererObj.drawStringWithShadow(displayName, textX, anchorY + 2, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow(hpText, textX, anchorY + 2 + fontRendererObj.FONT_HEIGHT + 1, 0xFFFFFF);

        int cursorY = anchorY + headSize + 4;
        int cursorX = anchorX + headSize / 2;
        boolean sneakPreview = (System.currentTimeMillis() / 2000L) % 2L == 0L;
        float angleDeg = (System.currentTimeMillis() % 3600L) / 10.0f;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.translate(cursorX, cursorY + iconSize / 2.0F, 0.0F);
        GlStateManager.rotate(angleDeg, 0.0F, 0.0F, 1.0F);

        ResourceLocation cursorTexture = PlayerTarget.getCursorTexture(sneakPreview);
        if (cursorTexture != null) {
            mc.getTextureManager().bindTexture(cursorTexture);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int textureWidth = PlayerTarget.getCursorWidth(sneakPreview);
            int textureHeight = PlayerTarget.getCursorHeight(sneakPreview);
            Gui.drawScaledCustomSizeModalRect(-(iconSize / 2), -(iconSize / 2), 0, 0, textureWidth, textureHeight,
                    iconSize, iconSize, textureWidth, textureHeight);
        } else {
            GlStateManager.disableTexture2D();
            Gui.drawRect(-1, -(iconSize / 2), 1, iconSize / 2, 0xFFFFFF00);
            Gui.drawRect(-(iconSize / 4), -(iconSize / 2), iconSize / 4, -(iconSize / 2) + 2, 0xFFFFFF00);
            GlStateManager.enableTexture2D();
        }

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();

        fontRendererObj.drawStringWithShadow(distText, cursorX + iconSize + 4, cursorY + 2, 0xAAAAFF);

        drawPlayerTargetExampleItems(mc, cursorX - 9, cursorY + iconSize + 4);

        if (PlayerTarget.isShowTauntInfo()) {
            String tauntText = EnumChatFormatting.WHITE + "Taunt: " + EnumChatFormatting.GREEN + "READY";
            fontRendererObj.drawStringWithShadow(tauntText, cursorX - 9, cursorY + iconSize + 26, 0xFFFFFF);
        }

        HudEditorBackgroundMask.popTransform();
        GlStateManager.popMatrix();

        try {
            mc.getRenderItem().zLevel = 0.0F;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    private void drawPlayerTargetExampleItems(Minecraft mc, int startX, int y) {
        ItemStack[] items = new ItemStack[5];
        items[0] = new ItemStack(Items.stone_sword);
        items[0].addEnchantment(net.minecraft.enchantment.Enchantment.sharpness, 1);
        items[1] = new ItemStack(Items.iron_helmet);
        items[2] = new ItemStack(Items.iron_chestplate);
        items[3] = new ItemStack(Items.iron_leggings);
        items[4] = new ItemStack(Items.iron_boots);

        for (int i = 0; i < items.length; i++) {
            drawEmptySlot(startX + (i * 20), y, 18);
        }

        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            int slotX = startX + (i * 20);
            mc.getRenderItem().zLevel = 200.0F;
            mc.getRenderItem().renderItemIntoGUI(item, slotX + 1, y + 1);
            mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, item, slotX + 1, y + 1, null);
            mc.getRenderItem().zLevel = 0.0F;
        }

        GlStateManager.disableDepth();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawEmptySlot(int x, int y, int itemSize) {
        Gui.drawRect(x, y, x + itemSize, y + itemSize, 0x50000000);
        drawBorder(x, y, itemSize, itemSize, 0x80FFFFFF);
    }

    /** 1px outline without stacking extra pixels on corners (top/bottom span full width; sides skip corner rows). */
    private static void drawBorder(int x, int y, int width, int height, int color) {
        if (width < 1 || height < 1) {
            return;
        }
        if (width < 2 || height < 2) {
            Gui.drawRect(x, y, x + width, y + height, color);
            return;
        }
        Gui.drawRect(x, y, x + width, y + 1, color);
        Gui.drawRect(x, y + height - 1, x + width, y + height, color);
        Gui.drawRect(x, y + 1, x + 1, y + height - 1, color);
        Gui.drawRect(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private EntityCow createPreviewCow(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        try {
            EntityCow cow = new EntityCow(mc.theWorld);
            cow.setInvisible(false);
            cow.hurtTime = 0;
            return cow;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String formatMobHealthText(float hp, float maxHp) {
        int currentHp = (int) Math.ceil(hp);
        int maxHpShown = (int) Math.ceil(maxHp);
        EnumChatFormatting color;
        float percentage = hp / Math.max(1.0F, maxHp);
        if (percentage >= 0.75f) {
            color = EnumChatFormatting.GREEN;
        } else if (percentage >= 0.40f) {
            color = EnumChatFormatting.YELLOW;
        } else {
            color = EnumChatFormatting.RED;
        }
        return color + String.valueOf(currentHp) + "/" + maxHpShown + " " + EnumChatFormatting.RED + "\u2764";
    }

    private EnumChatFormatting getHorseDistanceColor(double distance) {
        if (distance <= 6.0D) {
            return EnumChatFormatting.GREEN;
        }
        if (distance <= 12.0D) {
            return EnumChatFormatting.YELLOW;
        }
        return EnumChatFormatting.RED;
    }

    private String formatHorseDistance(double distance) {
        return String.format("%.1f", Math.max(0.0D, distance));
    }

    private void openModuleSettings(HudWidgetType type) {
        clearWidgetSelection();
        HudLayout layout = getLayoutForType(type);
        switch (type) {
            case INVENTORY_HUD:
                mc.displayGuiScreen(new InventoryHUDGUI(this));
                break;
            case FRIEND_INVENTORY_HUD:
                mc.displayGuiScreen(new FriendInventoryHUDGUI(this));
                break;
            case TEAMMATE_TRACKER:
                mc.displayGuiScreen(new TeammateTrackerGUI(this));
                break;
            case MOB_HEALTH_DISPLAY:
                mc.displayGuiScreen(new MobHealthDisplayGUI(this));
                break;
            case HORSE_DONKEY:
                mc.displayGuiScreen(new HorseDonkeySettingsGUI(this));
                break;
            case MORE_HUD_FPS:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.FPS, layout);
                break;
            case MORE_HUD_CPS:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.CPS, layout);
                break;
            case MORE_HUD_PING:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.PING, layout);
                break;
            case MORE_HUD_POTIONS:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.POTIONS, layout);
                break;
            case MORE_HUD_ARROWS:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.ARROWS, layout);
                break;
            case MORE_HUD_ARMOR_STATUS:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.ARMOR_STATUS, layout);
                break;
            case MORE_HUD_COORDS:
            case MORE_HUD_COORDS_COMPASS:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.COORDS, layout);
                break;
            case MORE_HUD_FLAME:
            case MORE_HUD_FLAME_COMPACT:
            case MORE_HUD_FLAME_COMPACT_ICON:
            case MORE_HUD_FLAME_COMPACT_TIMER:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.FLAME, layout);
                break;
            case MORE_HUD_KILL_EFFECTS:
            case MORE_HUD_KILL_EFFECTS_ICON:
            case MORE_HUD_KILL_EFFECTS_TIMER:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.KILL_EFFECTS, layout);
                break;
            case BLITZ_KIT_TIMER_HUD:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.BLITZ_KIT_TIMER, layout);
                break;
            case BLITZ_CHALLENGES_HUD:
                mc.displayGuiScreen(new BlitzChallengesHUDGUI(this));
                break;
            case INCOMING_SCREENSHOT_OVERLAY:
                openMoreHudSettings(MoreHudSettingsGUI.Panel.INCOMING_SCREENSHOT, layout);
                break;
            default:
                break;
        }
    }

    private void openMoreHudSettings(MoreHudSettingsGUI.Panel panel, HudLayout layout) {
        if (layout != null) {
            HudEditorModuleTransition.begin(panel, layout.anchorX, layout.anchorY);
        } else {
            HudEditorModuleTransition.clear();
        }
        mc.displayGuiScreen(new MoreHudSettingsGUI(this, panel));
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 1) {
            List<HudLayout> layouts = getHudLayouts();
            for (int i = layouts.size() - 1; i >= 0; i--) {
                HudLayout layout = layouts.get(i);
                if (layout.contains(mouseX, mouseY)) {
                    openModuleSettings(layout.type);
                    return;
                }
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0) {
            return;
        }

        if (isMouseOverBackButton(mouseX, mouseY)) {
            clearWidgetSelection();
            return;
        }

        List<HudLayout> layouts = getHudLayouts();
        for (int i = layouts.size() - 1; i >= 0; i--) {
            HudLayout layout = layouts.get(i);
            ResizeCorner resizeCorner = getHoveredResizeCorner(layout, mouseX, mouseY);
            if (resizeCorner != null) {
                resizingWidget = layout.type;
                activeResizeCorner = resizeCorner;
                resizeStartMouseX = mouseX;
                resizeStartMouseY = mouseY;
                resizeStartScale = getWidgetScale(layout.type);
                captureResizeCompanionScales(layout.type);
                int[] opp = oppositeCornerPixel(layout, resizeCorner);
                resizeStartSpan = Math.max(24, Math.round(distance(mouseX, mouseY, opp[0], opp[1])));
                draggingWidget = null;
                beginGesture(layout.type);
                return;
            }
            if (!layout.contains(mouseX, mouseY)) {
                continue;
            }
            if (selectedWidget == layout.type && resizingWidget == null) {
                clearWidgetSelection();
                return;
            }
            draggingWidget = layout.type;
            resizingWidget = null;
            activeResizeCorner = null;
            clearCenterSnapLocks();
            dragPointerStartX = mouseX;
            dragPointerStartY = mouseY;
            dragPointerMoved = false;
            dragOffsetX = mouseX - layout.anchorX;
            dragOffsetY = mouseY - layout.anchorY;
            dragGeoOffsetX = (layout.left + layout.width / 2) - layout.anchorX;
            dragGeoOffsetY = (layout.top + layout.height / 2) - layout.anchorY;
            beginGesture(layout.type);
            return;
        }
        clearWidgetSelection();
    }

    /** Clears yellow selection (e.g. when returning from module settings). */
    void clearWidgetSelection() {
        selectedWidget = null;
    }

    private boolean isMouseOverBackButton(int mouseX, int mouseY) {
        return backButton != null && backButton.visible
                && mouseX >= backButton.xPosition && mouseX < backButton.xPosition + backButton.width
                && mouseY >= backButton.yPosition && mouseY < backButton.yPosition + backButton.height;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        Mouse.getEventDWheel();
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (clickedMouseButton != 0) {
            return;
        }

        if (resizingWidget != null && activeResizeCorner != null) {
            updateWidgetScaleFromCorner(mouseX, mouseY);
            return;
        }

        if (draggingWidget == null) {
            return;
        }

        if (!dragPointerMoved
                && (Math.abs(mouseX - dragPointerStartX) > DRAG_COMMIT_MOVE_PX
                || Math.abs(mouseY - dragPointerStartY) > DRAG_COMMIT_MOVE_PX)) {
            dragPointerMoved = true;
        }

        HudLayout layout = getLayoutForType(draggingWidget);
        if (layout == null) {
            return;
        }

        int newAnchorX = mouseX - dragOffsetX;
        int newAnchorY = mouseY - dragOffsetY;

        int minAnchorX = layout.anchorX - layout.left;
        int minAnchorY = layout.anchorY - layout.top;
        int maxAnchorX = width - (layout.width - minAnchorX);
        int maxAnchorY = height - (layout.height - minAnchorY);

        if (maxAnchorX < minAnchorX) {
            maxAnchorX = minAnchorX;
        }
        if (maxAnchorY < minAnchorY) {
            maxAnchorY = minAnchorY;
        }

        if (draggingWidget != HudWidgetType.MORE_HUD_COORDS_COMPASS
                && draggingWidget != HudWidgetType.MORE_HUD_ARMOR_STATUS) {
            newAnchorX = clamp(newAnchorX, minAnchorX, maxAnchorX);
            newAnchorY = clamp(newAnchorY, minAnchorY, maxAnchorY);
        }

        int[] snapped = applyCenterGuideSnap(newAnchorX, newAnchorY);
        if (draggingWidget == HudWidgetType.MORE_HUD_ARMOR_STATUS) {
            ScaledResolution srArmor = new ScaledResolution(Minecraft.getMinecraft());
            int[] armorClamped = MoreHudArmorStatusFeature.clampAnchorScreen(snapped[0], snapped[1],
                    ConfigManager.getMoreHudArmorStatusScale(), srArmor.getScaledWidth(), srArmor.getScaledHeight());
            newAnchorX = armorClamped[0];
            newAnchorY = armorClamped[1];
        } else if (draggingWidget != HudWidgetType.MORE_HUD_COORDS_COMPASS) {
            newAnchorX = clamp(snapped[0], minAnchorX, maxAnchorX);
            newAnchorY = clamp(snapped[1], minAnchorY, maxAnchorY);
        } else {
            newAnchorX = snapped[0];
            newAnchorY = snapped[1];
        }
        if (draggingWidget == HudWidgetType.MORE_HUD_COORDS_COMPASS) {
            int[] compassClamped = MoreHudCoordsFeature.clampCompassDragAnchorScreen(Minecraft.getMinecraft(),
                    newAnchorX, newAnchorY, false);
            newAnchorX = compassClamped[0];
            newAnchorY = compassClamped[1];
        }
        applyWidgetPosition(draggingWidget, newAnchorX, newAnchorY);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        commitGestureIfChanged();
        boolean wasResizing = resizingWidget != null;
        boolean moved = dragPointerMoved;
        HudWidgetType tapWidget = draggingWidget;
        draggingWidget = null;
        resizingWidget = null;
        activeResizeCorner = null;
        clearCenterSnapLocks();
        dragPointerMoved = false;
        if (state != 0) {
            return;
        }
        if (wasResizing || moved) {
            clearWidgetSelection();
        } else if (tapWidget != null) {
            selectedWidget = tapWidget;
        }
    }

    private void clearCenterSnapLocks() {
        snapLockCenterX = false;
        snapLockCenterY = false;
        snapEngageBlockedCenterX = false;
        snapEngageBlockedCenterY = false;
    }

    /**
     * Photoshop-style snap: when the HUD geometric center nears a screen center guide, align it exactly.
     * Release uses the raw mouse position (not the snapped position) so the lock does not flicker on/off.
     */
    private int[] applyCenterGuideSnap(int rawAnchorX, int rawAnchorY) {
        int centerX = HudEditorCenterGuides.getScaledCenterX();
        int centerY = HudEditorCenterGuides.getScaledCenterY();
        int rawGeoCx = rawAnchorX + dragGeoOffsetX;
        int rawGeoCy = rawAnchorY + dragGeoOffsetY;

        if (snapLockCenterX) {
            if (Math.abs(rawGeoCx - centerX) > SNAP_RELEASE_PX) {
                snapLockCenterX = false;
                snapEngageBlockedCenterX = true;
            }
        } else if (!snapEngageBlockedCenterX && Math.abs(rawGeoCx - centerX) <= SNAP_THRESHOLD_PX) {
            snapLockCenterX = true;
        }

        if (snapLockCenterY) {
            if (Math.abs(rawGeoCy - centerY) > SNAP_RELEASE_PX) {
                snapLockCenterY = false;
                snapEngageBlockedCenterY = true;
            }
        } else if (!snapEngageBlockedCenterY && Math.abs(rawGeoCy - centerY) <= SNAP_THRESHOLD_PX) {
            snapLockCenterY = true;
        }

        int anchorX = snapLockCenterX ? centerX - dragGeoOffsetX : rawAnchorX;
        int anchorY = snapLockCenterY ? centerY - dragGeoOffsetY : rawAnchorY;
        return new int[] { anchorX, anchorY };
    }

    private HudLayout getLayoutForType(HudWidgetType type) {
        for (HudLayout layout : getHudLayouts()) {
            if (layout.type == type) {
                return layout;
            }
        }
        return null;
    }

    private void applyWidgetPosition(HudWidgetType type, int anchorX, int anchorY) {
        switch (type) {
            case INVENTORY_HUD:
                InventoryHUD.setCustomPosition(anchorX, anchorY);
                break;
            case FRIEND_INVENTORY_HUD:
                FriendInventoryHUD.setCustomPosition(anchorX, anchorY);
                break;
            case TEAMMATE_TRACKER:
                PlayerTarget.setCustomPosition(anchorX, anchorY);
                break;
            case MOB_HEALTH_DISPLAY:
                MobHealthDisplay.setCustomPosition(anchorX, anchorY);
                break;
            case HORSE_DONKEY:
                HorseDonkeyTracker.setCustomPosition(anchorX, anchorY);
                break;
            case MORE_HUD_FPS: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudFpsOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudFpsPosY(anchorY);
                break;
            }
            case MORE_HUD_CPS: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudCpsOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudCpsPosY(anchorY);
                break;
            }
            case MORE_HUD_PING: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudPingOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudPingPosY(anchorY);
                break;
            }
            case MORE_HUD_POTIONS: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudPotionsOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudPotionsPosY(anchorY);
                break;
            }
            case MORE_HUD_ARROWS: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudArrowsOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudArrowsPosY(anchorY);
                break;
            }
            case MORE_HUD_ARMOR_STATUS: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudArmorStatusOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudArmorStatusPosY(anchorY);
                break;
            }
            case MORE_HUD_COORDS: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudCoordsOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudCoordsPosY(anchorY);
                break;
            }
            case MORE_HUD_COORDS_COMPASS:
                MoreHudCoordsFeature.applyCompassDragToConfig(Minecraft.getMinecraft(), anchorX, anchorY);
                break;
            case MORE_HUD_KILL_EFFECTS: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudKillEffectsOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudKillEffectsPosY(anchorY);
                break;
            }
            case MORE_HUD_KILL_EFFECTS_ICON:
                MoreHudFeature.applyKillEffectsIconDragToConfig(Minecraft.getMinecraft(), anchorX, anchorY);
                break;
            case MORE_HUD_KILL_EFFECTS_TIMER:
                MoreHudFeature.applyKillEffectsTimerDragToConfig(Minecraft.getMinecraft(), anchorX, anchorY);
                break;
            case MORE_HUD_FLAME: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setMoreHudFlameOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setMoreHudFlamePosY(anchorY);
                break;
            }
            case MORE_HUD_FLAME_COMPACT:
                MoreHudFeature.applyFlameCompactRowDragToConfig(Minecraft.getMinecraft(), anchorX, anchorY);
                break;
            case MORE_HUD_FLAME_COMPACT_ICON:
                MoreHudFeature.applyFlameCompactIconDragToConfig(Minecraft.getMinecraft(), anchorX, anchorY);
                break;
            case MORE_HUD_FLAME_COMPACT_TIMER:
                MoreHudFeature.applyFlameCompactTimerDragToConfig(Minecraft.getMinecraft(), anchorX, anchorY);
                break;
            case BLITZ_KIT_TIMER_HUD: {
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                ConfigManager.setBlitzKitTimerHudOffsetCenterX(anchorX - sr.getScaledWidth() / 2);
                ConfigManager.setBlitzKitTimerHudPosY(anchorY);
                break;
            }
            case BLITZ_CHALLENGES_HUD:
                BlitzChallenges.setCustomPosition(anchorX, anchorY);
                break;
            case INCOMING_SCREENSHOT_OVERLAY:
                IncomingScreenshotOverlay.setHudPosition(anchorX, anchorY);
                break;
            default:
                break;
        }
    }

    private void applyWidgetScale(HudWidgetType type, float delta) {
        switch (type) {
            case INVENTORY_HUD:
                InventoryHUD.setScale(InventoryHUD.getScale() + delta);
                break;
            case FRIEND_INVENTORY_HUD:
                FriendInventoryHUD.setScale(FriendInventoryHUD.getScale() + delta);
                break;
            case TEAMMATE_TRACKER:
                PlayerTarget.setScale(PlayerTarget.getScale() + delta);
                break;
            case HORSE_DONKEY:
                HorseDonkeyTracker.setScale(HorseDonkeyTracker.getScale() + delta);
                break;
            case MOB_HEALTH_DISPLAY:
                MobHealthDisplay.setScale(MobHealthDisplay.getScale() + delta);
                break;
            case MORE_HUD_FPS:
                ConfigManager.setMoreHudFpsScale(ConfigManager.getMoreHudFpsScale() + delta);
                break;
            case MORE_HUD_CPS:
                ConfigManager.setMoreHudCpsScale(ConfigManager.getMoreHudCpsScale() + delta);
                break;
            case MORE_HUD_PING:
                ConfigManager.setMoreHudPingScale(ConfigManager.getMoreHudPingScale() + delta);
                break;
            case MORE_HUD_POTIONS:
                ConfigManager.setMoreHudPotionsScale(ConfigManager.getMoreHudPotionsScale() + delta);
                break;
            case MORE_HUD_ARROWS:
                ConfigManager.setMoreHudArrowsScale(ConfigManager.getMoreHudArrowsScale() + delta);
                break;
            case MORE_HUD_ARMOR_STATUS:
                ConfigManager.setMoreHudArmorStatusScale(ConfigManager.getMoreHudArmorStatusScale() + delta);
                break;
            case MORE_HUD_COORDS:
            case MORE_HUD_COORDS_COMPASS:
                ConfigManager.setMoreHudCoordsScale(ConfigManager.getMoreHudCoordsScale() + delta);
                break;
            case MORE_HUD_KILL_EFFECTS:
                ConfigManager.setMoreHudKillEffectsScale(ConfigManager.getMoreHudKillEffectsScale() + delta);
                break;
            case MORE_HUD_KILL_EFFECTS_ICON:
                ConfigManager.setMoreHudKillEffectsIconScale(ConfigManager.getMoreHudKillEffectsIconScale() + delta);
                break;
            case MORE_HUD_KILL_EFFECTS_TIMER:
                ConfigManager.setMoreHudKillEffectsTimerScale(ConfigManager.getMoreHudKillEffectsTimerScale() + delta);
                break;
            case MORE_HUD_FLAME:
                ConfigManager.setMoreHudFlameScale(ConfigManager.getMoreHudFlameScale() + delta);
                break;
            case MORE_HUD_FLAME_COMPACT:
                ConfigManager.setMoreHudFlameCompactScale(ConfigManager.getMoreHudFlameCompactScale() + delta);
                break;
            case MORE_HUD_FLAME_COMPACT_ICON:
                ConfigManager.setMoreHudFlameCompactIconScale(ConfigManager.getMoreHudFlameCompactIconScale() + delta);
                break;
            case MORE_HUD_FLAME_COMPACT_TIMER:
                ConfigManager.setMoreHudFlameCompactTimerScale(ConfigManager.getMoreHudFlameCompactTimerScale() + delta);
                break;
            case BLITZ_KIT_TIMER_HUD:
                ConfigManager.setBlitzKitTimerHudScale(ConfigManager.getBlitzKitTimerHudScale() + delta);
                break;
            case BLITZ_CHALLENGES_HUD:
                BlitzChallenges.setHudScale(BlitzChallenges.getHudScale() + delta);
                break;
            case INCOMING_SCREENSHOT_OVERLAY:
                ConfigManager.setIncomingScreenshotHudScale(ConfigManager.getIncomingScreenshotHudScale() + delta);
                break;
            default:
                break;
        }
    }

    private float getWidgetScale(HudWidgetType type) {
        switch (type) {
            case INVENTORY_HUD:
                return InventoryHUD.getScale();
            case FRIEND_INVENTORY_HUD:
                return FriendInventoryHUD.getScale();
            case TEAMMATE_TRACKER:
                return PlayerTarget.getScale();
            case HORSE_DONKEY:
                return HorseDonkeyTracker.getScale();
            case MOB_HEALTH_DISPLAY:
                return MobHealthDisplay.getScale();
            case MORE_HUD_FPS:
                return ConfigManager.getMoreHudFpsScale();
            case MORE_HUD_CPS:
                return ConfigManager.getMoreHudCpsScale();
            case MORE_HUD_PING:
                return ConfigManager.getMoreHudPingScale();
            case MORE_HUD_POTIONS:
                return ConfigManager.getMoreHudPotionsScale();
            case MORE_HUD_ARROWS:
                return ConfigManager.getMoreHudArrowsScale();
            case MORE_HUD_ARMOR_STATUS:
                return ConfigManager.getMoreHudArmorStatusScale();
            case MORE_HUD_COORDS:
            case MORE_HUD_COORDS_COMPASS:
                return ConfigManager.getMoreHudCoordsScale();
            case MORE_HUD_KILL_EFFECTS:
                return ConfigManager.getMoreHudKillEffectsScale();
            case MORE_HUD_KILL_EFFECTS_ICON:
                return ConfigManager.getMoreHudKillEffectsIconScale();
            case MORE_HUD_KILL_EFFECTS_TIMER:
                return ConfigManager.getMoreHudKillEffectsTimerScale();
            case MORE_HUD_FLAME:
                return ConfigManager.getMoreHudFlameScale();
            case MORE_HUD_FLAME_COMPACT:
                return ConfigManager.getMoreHudFlameCompactScale();
            case MORE_HUD_FLAME_COMPACT_ICON:
                return ConfigManager.getMoreHudFlameCompactIconScale();
            case MORE_HUD_FLAME_COMPACT_TIMER:
                return ConfigManager.getMoreHudFlameCompactTimerScale();
            case BLITZ_KIT_TIMER_HUD:
                return ConfigManager.getBlitzKitTimerHudScale();
            case BLITZ_CHALLENGES_HUD:
                return BlitzChallenges.getHudScale();
            case INCOMING_SCREENSHOT_OVERLAY:
                return ConfigManager.getIncomingScreenshotHudScale();
            default:
                return 1.0f;
        }
    }

    private void updateWidgetScaleFromCorner(int mouseX, int mouseY) {
        HudLayout layout = getLayoutForType(resizingWidget);
        if (layout == null || activeResizeCorner == null) {
            return;
        }
        int[] opp = oppositeCornerPixel(layout, activeResizeCorner);
        float newSpan = distance(mouseX, mouseY, opp[0], opp[1]);
        float ratio = newSpan / (float) resizeStartSpan;
        float targetScale = snapHudEditorScale(resizeStartScale * ratio);
        setWidgetScaleAbsolute(resizingWidget, targetScale);
    }

    private static float snapHudEditorScale(float scale) {
        float clamped = Math.max(0.25f, Math.min(4.0f, scale));
        return Math.round(clamped * 20.0f) / 20.0f;
    }

    private static float distance(int x1, int y1, int x2, int y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static int[] oppositeCornerPixel(HudLayout layout, ResizeCorner corner) {
        int[] out = new int[2];
        switch (corner) {
            case TOP_LEFT:
                out[0] = layout.left + layout.width;
                out[1] = layout.top + layout.height;
                break;
            case TOP_RIGHT:
                out[0] = layout.left;
                out[1] = layout.top + layout.height;
                break;
            case BOTTOM_LEFT:
                out[0] = layout.left + layout.width;
                out[1] = layout.top;
                break;
            case BOTTOM_RIGHT:
            default:
                out[0] = layout.left;
                out[1] = layout.top;
                break;
        }
        return out;
    }

    private void captureResizeCompanionScales(HudWidgetType type) {
        resizeStartAuxScale1 = 1.0f;
        resizeStartAuxScale2 = 1.0f;
        switch (type) {
            case MORE_HUD_KILL_EFFECTS:
                resizeStartAuxScale1 = ConfigManager.getMoreHudKillEffectsIconScale();
                resizeStartAuxScale2 = ConfigManager.getMoreHudKillEffectsTimerScale();
                break;
            case MORE_HUD_FLAME_COMPACT:
                resizeStartAuxScale1 = ConfigManager.getMoreHudFlameCompactIconScale();
                resizeStartAuxScale2 = ConfigManager.getMoreHudFlameCompactTimerScale();
                break;
            default:
                break;
        }
    }

    private void applyResizeCompanionScales(HudWidgetType type, float newPrimaryScale) {
        if (resizeStartScale <= 0.0f) {
            return;
        }
        float ratio = newPrimaryScale / resizeStartScale;
        switch (type) {
            case MORE_HUD_KILL_EFFECTS:
                ConfigManager.setMoreHudKillEffectsIconScale(snapHudEditorScale(resizeStartAuxScale1 * ratio));
                ConfigManager.setMoreHudKillEffectsTimerScale(snapHudEditorScale(resizeStartAuxScale2 * ratio));
                break;
            case MORE_HUD_FLAME_COMPACT:
                ConfigManager.setMoreHudFlameCompactIconScale(snapHudEditorScale(resizeStartAuxScale1 * ratio));
                ConfigManager.setMoreHudFlameCompactTimerScale(snapHudEditorScale(resizeStartAuxScale2 * ratio));
                break;
            default:
                break;
        }
    }

    /**
     * Sets row/widget scale to an absolute value (resize handles). Avoids incremental float drift from
     * repeated {@code getScale() + delta} during corner drags.
     */
    private void setWidgetScaleAbsolute(HudWidgetType type, float scale) {
        float snapped = snapHudEditorScale(scale);
        switch (type) {
            case INVENTORY_HUD:
                InventoryHUD.setScale(snapped);
                break;
            case FRIEND_INVENTORY_HUD:
                FriendInventoryHUD.setScale(snapped);
                break;
            case TEAMMATE_TRACKER:
                PlayerTarget.setScale(snapped);
                break;
            case HORSE_DONKEY:
                HorseDonkeyTracker.setScale(snapped);
                break;
            case MOB_HEALTH_DISPLAY:
                MobHealthDisplay.setScale(snapped);
                break;
            case MORE_HUD_FPS:
                ConfigManager.setMoreHudFpsScale(snapped);
                break;
            case MORE_HUD_CPS:
                ConfigManager.setMoreHudCpsScale(snapped);
                break;
            case MORE_HUD_PING:
                ConfigManager.setMoreHudPingScale(snapped);
                break;
            case MORE_HUD_POTIONS:
                ConfigManager.setMoreHudPotionsScale(snapped);
                break;
            case MORE_HUD_ARROWS:
                ConfigManager.setMoreHudArrowsScale(snapped);
                break;
            case MORE_HUD_ARMOR_STATUS:
                ConfigManager.setMoreHudArmorStatusScale(snapped);
                break;
            case MORE_HUD_COORDS:
            case MORE_HUD_COORDS_COMPASS:
                ConfigManager.setMoreHudCoordsScale(snapped);
                break;
            case MORE_HUD_KILL_EFFECTS:
                ConfigManager.setMoreHudKillEffectsScale(snapped);
                applyResizeCompanionScales(type, snapped);
                break;
            case MORE_HUD_KILL_EFFECTS_ICON:
                ConfigManager.setMoreHudKillEffectsIconScale(snapped);
                break;
            case MORE_HUD_KILL_EFFECTS_TIMER:
                ConfigManager.setMoreHudKillEffectsTimerScale(snapped);
                break;
            case MORE_HUD_FLAME:
                ConfigManager.setMoreHudFlameScale(snapped);
                break;
            case MORE_HUD_FLAME_COMPACT:
                ConfigManager.setMoreHudFlameCompactScale(snapped);
                applyResizeCompanionScales(type, snapped);
                break;
            case MORE_HUD_FLAME_COMPACT_ICON:
                ConfigManager.setMoreHudFlameCompactIconScale(snapped);
                break;
            case MORE_HUD_FLAME_COMPACT_TIMER:
                ConfigManager.setMoreHudFlameCompactTimerScale(snapped);
                break;
            case BLITZ_KIT_TIMER_HUD:
                ConfigManager.setBlitzKitTimerHudScale(snapped);
                break;
            case BLITZ_CHALLENGES_HUD:
                BlitzChallenges.setHudScale(snapped);
                break;
            case INCOMING_SCREENSHOT_OVERLAY:
                ConfigManager.setIncomingScreenshotHudScale(snapped);
                break;
            default:
                break;
        }
    }

    private ResizeCorner getHoveredResizeCorner(HudLayout layout, int mouseX, int mouseY) {
        for (ResizeCorner corner : ResizeCorner.values()) {
            HandleBounds handle = getHandleBounds(layout, corner);
            if (handle.contains(mouseX, mouseY)) {
                return corner;
            }
        }
        return null;
    }

    private HandleBounds getHandleBounds(HudLayout layout, ResizeCorner corner) {
        int half = RESIZE_HANDLE_SIZE / 2;
        int centerX;
        int centerY;
        switch (corner) {
            case TOP_LEFT:
                centerX = layout.left;
                centerY = layout.top;
                break;
            case TOP_RIGHT:
                centerX = layout.left + layout.width;
                centerY = layout.top;
                break;
            case BOTTOM_LEFT:
                centerX = layout.left;
                centerY = layout.top + layout.height;
                break;
            case BOTTOM_RIGHT:
            default:
                centerX = layout.left + layout.width;
                centerY = layout.top + layout.height;
                break;
        }
        return new HandleBounds(centerX - half, centerY - half, RESIZE_HANDLE_SIZE);
    }

    private void drawResizeCornerAccent(HudLayout layout, ResizeCorner corner, int color) {
        final int maxAccent = 10;
        final int thickness = 2;
        int x = (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT) ? layout.left : layout.left + layout.width;
        int y = (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT) ? layout.top : layout.top + layout.height;

        int iw = Math.max(1, layout.width);
        int ih = Math.max(1, layout.height);
        int hLen = Math.max(1, Math.min(maxAccent, iw));
        int vLen = Math.max(1, Math.min(maxAccent, ih));

        int horizontalStart;
        int horizontalEnd;
        int verticalStart;
        int verticalEnd;
        switch (corner) {
            case TOP_LEFT:
                horizontalStart = x;
                horizontalEnd = x + hLen;
                verticalStart = y;
                verticalEnd = y + vLen;
                break;
            case TOP_RIGHT:
                horizontalStart = x - hLen;
                horizontalEnd = x;
                verticalStart = y;
                verticalEnd = y + vLen;
                break;
            case BOTTOM_LEFT:
                horizontalStart = x;
                horizontalEnd = x + hLen;
                verticalStart = y - vLen;
                verticalEnd = y;
                break;
            case BOTTOM_RIGHT:
            default:
                horizontalStart = x - hLen;
                horizontalEnd = x;
                verticalStart = y - vLen;
                verticalEnd = y;
                break;
        }

        int halfT = thickness / 2;
        int hTop = y - halfT;
        int hBottom = y - halfT + thickness;
        int vLeft = x - halfT;
        int vRight = x - halfT + thickness;

        Gui.drawRect(horizontalStart, hTop, horizontalEnd, hBottom, color);
        Gui.drawRect(vLeft, verticalStart, vRight, verticalEnd, color);
        Gui.drawRect(vLeft, hTop, vRight, hBottom, color);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void beginGesture(HudWidgetType type) {
        gestureWidget = type;
        gestureBefore = captureWidgetState(type);
    }

    private void commitGestureIfChanged() {
        if (gestureWidget == null || gestureBefore == null) {
            gestureWidget = null;
            gestureBefore = null;
            return;
        }
        HudWidgetState after = captureWidgetState(gestureWidget);
        if (after != null && !gestureBefore.matches(after)) {
            pushHistory(gestureBefore, after);
        }
        gestureWidget = null;
        gestureBefore = null;
    }

    private HudWidgetState captureWidgetState(HudWidgetType type) {
        HudLayout layout = getLayoutForType(type);
        if (layout == null) {
            return null;
        }
        float aux1 = 1.0f;
        float aux2 = 1.0f;
        switch (type) {
            case MORE_HUD_KILL_EFFECTS:
                aux1 = ConfigManager.getMoreHudKillEffectsIconScale();
                aux2 = ConfigManager.getMoreHudKillEffectsTimerScale();
                break;
            case MORE_HUD_FLAME_COMPACT:
                aux1 = ConfigManager.getMoreHudFlameCompactIconScale();
                aux2 = ConfigManager.getMoreHudFlameCompactTimerScale();
                break;
            default:
                break;
        }
        return new HudWidgetState(type, layout.anchorX, layout.anchorY, getWidgetScale(type), aux1, aux2);
    }

    private void applyWidgetState(HudWidgetState state) {
        if (state == null) {
            return;
        }
        applyWidgetPosition(state.type, state.anchorX, state.anchorY);
        setWidgetScaleAbsolute(state.type, state.scale);
        switch (state.type) {
            case MORE_HUD_KILL_EFFECTS:
                ConfigManager.setMoreHudKillEffectsIconScale(snapHudEditorScale(state.auxScale1));
                ConfigManager.setMoreHudKillEffectsTimerScale(snapHudEditorScale(state.auxScale2));
                break;
            case MORE_HUD_FLAME_COMPACT:
                ConfigManager.setMoreHudFlameCompactIconScale(snapHudEditorScale(state.auxScale1));
                ConfigManager.setMoreHudFlameCompactTimerScale(snapHudEditorScale(state.auxScale2));
                break;
            default:
                break;
        }
    }

    private void pushHistory(HudWidgetState before, HudWidgetState after) {
        undoStack.addLast(new HudEditorHistoryStep(before.copy(), after.copy()));
        while (undoStack.size() > MAX_UNDO_STEPS) {
            undoStack.removeFirst();
        }
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        HudEditorHistoryStep step = undoStack.removeLast();
        redoStack.addLast(step);
        applyWidgetState(step.before);
        selectedWidget = step.before.type;
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        HudEditorHistoryStep step = redoStack.removeLast();
        undoStack.addLast(step);
        applyWidgetState(step.after);
        selectedWidget = step.after.type;
    }

    private int[] clampAnchorForWidget(HudWidgetType type, int anchorX, int anchorY) {
        if (type == HudWidgetType.MORE_HUD_COORDS_COMPASS) {
            return MoreHudCoordsFeature.clampCompassDragAnchorScreen(Minecraft.getMinecraft(), anchorX, anchorY, false);
        }
        if (type == HudWidgetType.MORE_HUD_ARMOR_STATUS) {
            ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
            return MoreHudArmorStatusFeature.clampAnchorScreen(anchorX, anchorY,
                    ConfigManager.getMoreHudArmorStatusScale(), sr.getScaledWidth(), sr.getScaledHeight());
        }
        HudLayout layout = getLayoutForType(type);
        if (layout == null) {
            return new int[] { anchorX, anchorY };
        }
        int minAnchorX = layout.anchorX - layout.left;
        int minAnchorY = layout.anchorY - layout.top;
        int maxAnchorX = width - (layout.width - minAnchorX);
        int maxAnchorY = height - (layout.height - minAnchorY);
        if (maxAnchorX < minAnchorX) {
            maxAnchorX = minAnchorX;
        }
        if (maxAnchorY < minAnchorY) {
            maxAnchorY = minAnchorY;
        }
        return new int[] {
                clamp(anchorX, minAnchorX, maxAnchorX),
                clamp(anchorY, minAnchorY, maxAnchorY)
        };
    }

    private void nudgeSelectedWidget(int dx, int dy) {
        if (selectedWidget == null || (dx == 0 && dy == 0)) {
            return;
        }
        HudWidgetState before = captureWidgetState(selectedWidget);
        if (before == null) {
            return;
        }
        if (isHudPartWidget(selectedWidget)) {
            nudgeHudPartByScreenDelta(selectedWidget, dx, dy);
        } else {
            HudLayout layout = getLayoutForType(selectedWidget);
            if (layout == null) {
                return;
            }
            int[] clamped = clampAnchorForWidget(selectedWidget, layout.anchorX + dx, layout.anchorY + dy);
            applyWidgetPosition(selectedWidget, clamped[0], clamped[1]);
        }
        HudWidgetState after = captureWidgetState(selectedWidget);
        if (!before.matches(after)) {
            pushHistory(before, after);
        }
    }

    private void nudgeHudPartByScreenDelta(HudWidgetType type, int screenDx, int screenDy) {
        Minecraft mc = Minecraft.getMinecraft();
        switch (type) {
            case MORE_HUD_COORDS_COMPASS:
                MoreHudCoordsFeature.nudgeCompassByScreen(mc, screenDx, screenDy);
                break;
            case MORE_HUD_KILL_EFFECTS_ICON:
                MoreHudFeature.nudgeKillEffectsIconByScreen(mc, screenDx, screenDy);
                break;
            case MORE_HUD_KILL_EFFECTS_TIMER:
                MoreHudFeature.nudgeKillEffectsTimerByScreen(mc, screenDx, screenDy);
                break;
            case MORE_HUD_FLAME_COMPACT_ICON:
                MoreHudFeature.nudgeFlameCompactIconByScreen(screenDx, screenDy);
                break;
            case MORE_HUD_FLAME_COMPACT_TIMER:
                MoreHudFeature.nudgeFlameCompactTimerByScreen(screenDx, screenDy);
                break;
            default:
                break;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            clearWidgetSelection();
            GomodMenuTransitions.returnToParentOrMainMenu(mc, parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            if (selectedWidget != null) {
                clearWidgetSelection();
                return;
            }
            GomodMenuTransitions.closeToGame(mc);
            return;
        }
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (ctrl && keyCode == Keyboard.KEY_Z) {
            if (shift) {
                redo();
            } else {
                undo();
            }
            return;
        }
        if (selectedWidget != null) {
            int step = shift ? ARROW_NUDGE_SHIFT_PX : ARROW_NUDGE_PX;
            if (keyCode == Keyboard.KEY_LEFT) {
                nudgeSelectedWidget(-step, 0);
                return;
            }
            if (keyCode == Keyboard.KEY_RIGHT) {
                nudgeSelectedWidget(step, 0);
                return;
            }
            if (keyCode == Keyboard.KEY_UP) {
                nudgeSelectedWidget(0, -step);
                return;
            }
            if (keyCode == Keyboard.KEY_DOWN) {
                nudgeSelectedWidget(0, step);
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class HudWidgetState {
        private final HudWidgetType type;
        private final int anchorX;
        private final int anchorY;
        private final float scale;
        private final float auxScale1;
        private final float auxScale2;

        private HudWidgetState(HudWidgetType type, int anchorX, int anchorY, float scale, float auxScale1, float auxScale2) {
            this.type = type;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.scale = scale;
            this.auxScale1 = auxScale1;
            this.auxScale2 = auxScale2;
        }

        private HudWidgetState copy() {
            return new HudWidgetState(type, anchorX, anchorY, scale, auxScale1, auxScale2);
        }

        private boolean matches(HudWidgetState other) {
            if (other == null || type != other.type) {
                return false;
            }
            if (anchorX != other.anchorX || anchorY != other.anchorY) {
                return false;
            }
            if (Math.abs(scale - other.scale) > 0.0001f) {
                return false;
            }
            if (Math.abs(auxScale1 - other.auxScale1) > 0.0001f) {
                return false;
            }
            if (Math.abs(auxScale2 - other.auxScale2) > 0.0001f) {
                return false;
            }
            return true;
        }
    }

    private static final class HudEditorHistoryStep {
        private final HudWidgetState before;
        private final HudWidgetState after;

        private HudEditorHistoryStep(HudWidgetState before, HudWidgetState after) {
            this.before = before;
            this.after = after;
        }
    }

    private enum HudWidgetType {
        INVENTORY_HUD,
        FRIEND_INVENTORY_HUD,
        TEAMMATE_TRACKER,
        MOB_HEALTH_DISPLAY,
        HORSE_DONKEY,
        MORE_HUD_FPS,
        MORE_HUD_CPS,
        MORE_HUD_PING,
        MORE_HUD_POTIONS,
        MORE_HUD_ARROWS,
        MORE_HUD_ARMOR_STATUS,
        MORE_HUD_COORDS,
        MORE_HUD_COORDS_COMPASS,
        MORE_HUD_KILL_EFFECTS,
        MORE_HUD_KILL_EFFECTS_ICON,
        MORE_HUD_KILL_EFFECTS_TIMER,
        MORE_HUD_FLAME,
        MORE_HUD_FLAME_COMPACT,
        MORE_HUD_FLAME_COMPACT_ICON,
        MORE_HUD_FLAME_COMPACT_TIMER,
        BLITZ_KIT_TIMER_HUD,
        BLITZ_CHALLENGES_HUD,
        INCOMING_SCREENSHOT_OVERLAY
    }

    private enum ResizeCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private static final class HudLayout {
        private final HudWidgetType type;
        private final String label;
        private final int anchorX;
        private final int anchorY;
        private final int left;
        private final int top;
        private final int width;
        private final int height;

        private HudLayout(HudWidgetType type, String label, int anchorX, int anchorY, int left, int top, int width, int height) {
            this.type = type;
            this.label = label;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= left && mouseX <= left + width && mouseY >= top && mouseY <= top + height;
        }
    }

    private static final class LabelPlacement {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private LabelPlacement(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private boolean isWithin(int screenWidth, int screenHeight) {
            return x >= 2 && y >= 2 && x + width <= screenWidth - 2 && y + height <= screenHeight - 2;
        }

        private boolean intersects(HudLayout layout) {
            return x < layout.left + layout.width
                    && x + width > layout.left
                    && y < layout.top + layout.height
                    && y + height > layout.top;
        }

        private boolean intersects(LabelPlacement o) {
            return x < o.x + o.width && x + width > o.x && y < o.y + o.height && y + height > o.y;
        }
    }

    private static final class HudFillPlacement {
        private final int left;
        private final int top;
        private final int width;
        private final int height;
        private final int color;

        private HudFillPlacement(int left, int top, int width, int height, int color) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.color = color;
        }
    }

    private static final class HudFillRect {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private HudFillRect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static final class HandleBounds {
        private final int left;
        private final int top;
        private final int size;

        private HandleBounds(int left, int top, int size) {
            this.left = left;
            this.top = top;
            this.size = size;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= left && mouseX <= left + size && mouseY >= top && mouseY <= top + size;
        }
    }
}
