package me.napim.gomod.features;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import me.napim.gomod.gui.GomodHudEditorGUI;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.gui.HudEditorModuleTransition;
import me.napim.gomod.gui.MoreHudSettingsGUI;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

/**
 * Armor + held-item durability HUD (inspired by Powns ArmorStatus HUD), integrated into moreHUD / HUD Editor.
 */
public final class MoreHudArmorStatusFeature {

    public enum DurabilityMode {
        NONE(0, "None"),
        REGULAR(1, "Default"),
        PERCENTAGE(2, "Percentage"),
        COMPARED(3, "Compared");

        private final int index;
        private final String label;

        DurabilityMode(int index, String label) {
            this.index = index;
            this.label = label;
        }

        public int getIndex() {
            return index;
        }

        public String getLabel() {
            return label;
        }

        public static DurabilityMode fromIndex(int index) {
            for (DurabilityMode m : values()) {
                if (m.index == index) {
                    return m;
                }
            }
            return REGULAR;
        }

        public static DurabilityMode next(DurabilityMode current) {
            DurabilityMode[] vals = values();
            int i = current == null ? 0 : current.ordinal();
            return vals[(i + 1) % vals.length];
        }
    }

    public enum LayoutMode {
        VERTICAL(0, "Vertical"),
        HORIZONTAL(1, "Horizontal"),
        HORIZONTAL_2(2, "Horizontal 2"),
        HORIZONTAL_3(3, "Horizontal 3");

        private final int index;
        private final String label;

        LayoutMode(int index, String label) {
            this.index = index;
            this.label = label;
        }

        public int getIndex() {
            return index;
        }

        public String getLabel() {
            return label;
        }

        public boolean isVertical() {
            return this == VERTICAL;
        }

        public boolean usesSideDurabilityText() {
            return this == VERTICAL || this == HORIZONTAL;
        }

        public boolean durabilityBelowItems() {
            return this == HORIZONTAL_2;
        }

        public boolean durabilityAboveItems() {
            return this == HORIZONTAL_3;
        }

        public static LayoutMode fromIndex(int index) {
            for (LayoutMode mode : values()) {
                if (mode.index == index) {
                    return mode;
                }
            }
            return VERTICAL;
        }

        public static LayoutMode next(LayoutMode current) {
            LayoutMode[] vals = values();
            int i = current == null ? 0 : current.ordinal();
            return vals[(i + 1) % vals.length];
        }
    }

    private enum SlotKind {
        HELMET(3),
        CHEST(2),
        LEGS(1),
        BOOTS(0),
        HELD(-1);

        final int armorIndex;

        SlotKind(int armorIndex) {
            this.armorIndex = armorIndex;
        }
    }

    private static final int ITEM_SIZE = 16;
    private static final int BG_PAD = 1;
    private static final int DURA_TEXT_GAP = 1;
    /** Default gap between helmet, chest, leggings, and boots. */
    public static final int ARMOR_SLOT_GAP_DEFAULT = 13;
    public static final int ARMOR_SLOT_GAP_VERTICAL_MIN = 13;
    /** Maximum vertical armor gap matches the largest held↔armor gap so spacing can match held distance. */
    public static final int ARMOR_SLOT_GAP_VERTICAL_MAX = 18;
    public static final int ARMOR_SLOT_GAP_HORIZONTAL_MIN = 1;
    public static final int ARMOR_SLOT_GAP_HORIZONTAL_MAX = 13;
    public static final float ARMOR_TEXT_SCALE_DEFAULT = 1.0f;
    public static final float ARMOR_TEXT_SCALE_MIN = 0.25f;
    public static final float ARMOR_TEXT_SCALE_MAX = 4.0f;
    /** Gap below armor stack before held item (held placement: down) — not affected by armor spacing setting. */
    private static final int VERTICAL_STEP_TO_HELD = 16;
    /** Gap between held item and helmet when held is above the armor stack — not affected by armor spacing setting. */
    private static final int VERTICAL_STEP_AFTER_HELD_ABOVE = 18;
    /** Gap before held item when horizontal layout places it to the right of the armor row. */
    private static final int HORIZONTAL_STEP_TO_HELD = 16;
    /** Gap after held item when horizontal layout places it to the left of the armor row. */
    private static final int HORIZONTAL_STEP_AFTER_HELD_LEFT = 18;
    private static final int STACKED_DURA_TEXT_GAP = 1;
    /** Leggings icon only — durability text stays aligned with other rows. */
    private static final int LEGGINGS_ITEM_Y_NUDGE_VERTICAL = 2;
    private static final int LEGGINGS_ITEM_Y_NUDGE_HORIZONTAL = 1;
    private static final DecimalFormat PERCENT_FMT = new DecimalFormat("#0.00");

    private static final class LayoutBox {
        int width;
        int height;
        LayoutMode layoutMode;
        int duraTextW;
        boolean duraRight;
        int iconColRelX;
        int bgMinX;
        int bgMaxX;
        int bgMinY;
        int bgMaxY;
        int[] slotRelX;
        int[] slotRelY;
        SlotKind[] kinds;
    }

    private MoreHudArmorStatusFeature() {
    }

    public static void render(Minecraft mc) {
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        boolean hudEditor = mc.currentScreen instanceof GomodHudEditorGUI;
        if (!hudEditor && !ConfigManager.isMoreHudEnabled()) {
            return;
        }
        if (!hudEditor && !ConfigManager.isMoreHudArmorStatusEnabled()) {
            return;
        }
        if (HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.ARMOR_STATUS)) {
            return;
        }
        if (!hudEditor && (mc.thePlayer == null || mc.theWorld == null)) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2 + ConfigManager.getMoreHudArmorStatusOffsetCenterX();
        int cy = ConfigManager.getMoreHudArmorStatusPosY();
        float scale = ConfigManager.getMoreHudArmorStatusScale();
        drawWithRowScale(cx, cy, scale,
                () -> drawBlock(mc, mc.fontRendererObj, cx, cy, scale, false, hudEditor));
    }

    public static void renderGalleryAt(Minecraft mc, int anchorX, int anchorY) {
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        float scale = ConfigManager.getMoreHudArmorStatusScale();
        drawWithRowScale(anchorX, anchorY, scale,
                () -> drawBlock(mc, mc.fontRendererObj, anchorX, anchorY, scale, true, true));
    }

    /**
     * Fills {@code out} with left, top, width, height in screen space (scaled).
     * Anchor is the top-center of the first 16px icon column (not the durability text gutter).
     */
    public static void screenBounds(int anchorX, int anchorY, float scale, int[] out) {
        screenBounds(anchorX, anchorY, scale, out, false);
    }

    /**
     * @param galleryPreview when true, matches {@link #renderGalleryAt} (preview stacks + full background).
     */
    public static void screenBounds(int anchorX, int anchorY, float scale, int[] out, boolean galleryPreview) {
        if (out == null || out.length < 4) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean editorPreview = isEditorPreviewScreen(mc);
        LayoutBox box = buildLayout(mc, galleryPreview, galleryPreview || editorPreview);
        if (box == null) {
            out[0] = (int) Math.round(anchorX + (-ITEM_SIZE / 2.0F - BG_PAD) * scale);
            out[1] = (int) Math.round(anchorY - BG_PAD * scale);
            out[2] = (int) Math.ceil((ITEM_SIZE + BG_PAD * 2) * scale);
            out[3] = (int) Math.ceil((ITEM_SIZE + BG_PAD * 2) * scale);
            return;
        }
        float s = scale <= 0f ? 1f : scale;
        int blockLeft = blockLeftUnscaled(anchorX, box.layoutMode, box.duraRight, box.duraTextW);
        int leftDelta = (blockLeft + box.bgMinX) - anchorX;
        int uw = (box.bgMaxX - box.bgMinX) + BG_PAD * 2;
        int uh = (box.bgMaxY - box.bgMinY) + BG_PAD * 2;
        out[0] = (int) Math.round(anchorX + leftDelta * s);
        out[1] = (int) Math.round(anchorY + (-BG_PAD + box.bgMinY) * s);
        out[2] = (int) Math.ceil(uw * s);
        out[3] = (int) Math.ceil(uh * s);
    }

    /** Clamps the first icon-column anchor so the selection box can touch screen edges (HUD Editor drag). */
    public static int[] clampAnchorScreen(int anchorX, int anchorY, float scale, int screenW, int screenH) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean editorPreview = isEditorPreviewScreen(mc);
        LayoutBox box = buildLayout(mc, false, editorPreview);
        if (box == null) {
            return new int[] { anchorX, anchorY };
        }
        float s = scale <= 0f ? 1f : scale;
        int blockLeftDelta = blockLeftUnscaled(0, box.layoutMode, box.duraRight, box.duraTextW) + box.bgMinX;
        int uw = (box.bgMaxX - box.bgMinX) + BG_PAD * 2;
        int uh = (box.bgMaxY - box.bgMinY) + BG_PAD * 2;
        int topDelta = -BG_PAD + box.bgMinY;
        int minAnchorX = (int) Math.ceil(-blockLeftDelta * s);
        int maxAnchorX = (int) Math.floor(screenW - (blockLeftDelta + uw) * s);
        int minAnchorY = (int) Math.ceil(-topDelta * s);
        int maxAnchorY = (int) Math.floor(screenH - (topDelta + uh) * s);
        if (maxAnchorX < minAnchorX) {
            maxAnchorX = minAnchorX;
        }
        if (maxAnchorY < minAnchorY) {
            maxAnchorY = minAnchorY;
        }
        return new int[] {
                clampInt(anchorX, minAnchorX, maxAnchorX),
                clampInt(anchorY, minAnchorY, maxAnchorY)
        };
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int iconColumnLeft(int anchorX) {
        return anchorX - ITEM_SIZE / 2;
    }

    private static boolean isEditorPreviewScreen(Minecraft mc) {
        if (mc == null || mc.currentScreen == null) {
            return false;
        }
        if (mc.currentScreen instanceof GomodHudEditorGUI) {
            return true;
        }
        if (mc.currentScreen instanceof MoreHudSettingsGUI) {
            return ((MoreHudSettingsGUI) mc.currentScreen).getPanel() == MoreHudSettingsGUI.Panel.ARMOR_STATUS;
        }
        return false;
    }

    private static int blockLeftUnscaled(int anchorX, LayoutMode layoutMode, boolean duraRight, int duraTextW) {
        int left = iconColumnLeft(anchorX) - BG_PAD;
        if (layoutMode != null && layoutMode.usesSideDurabilityText() && !duraRight) {
            left -= duraTextW;
        }
        return left;
    }

    /** Slot grid from settings only — positions stay fixed when items are missing in-game. */
    private static LayoutBox buildLayout(Minecraft mc, boolean galleryPreviewStacks, boolean includeEmptyHeldInBackground) {
        FontRenderer fr = mc != null ? mc.fontRendererObj : Minecraft.getMinecraft().fontRendererObj;
        if (fr == null) {
            return null;
        }
        List<SlotKind> slots = visibleSlots(mc);
        if (slots.isEmpty()) {
            return null;
        }
        DurabilityMode mode = DurabilityMode.fromIndex(ConfigManager.getMoreHudArmorStatusDuraMode());
        LayoutMode layoutMode = LayoutMode.fromIndex(ConfigManager.getMoreHudArmorStatusLayout());
        boolean duraRight = ConfigManager.isMoreHudArmorStatusDuraRight();
        boolean vertical = layoutMode.isVertical();
        int n = slots.size();
        SlotKind[] kinds = slots.toArray(new SlotKind[n]);
        float textScale = durabilityTextScale();
        int maxDuraTextW = maxDuraTextWidth(fr, mode, mc, kinds, galleryPreviewStacks, includeEmptyHeldInBackground,
                textScale);
        int sideTextW = layoutMode.usesSideDurabilityText() && maxDuraTextW > 0 ? maxDuraTextW + DURA_TEXT_GAP : 0;
        int iconColRelX = layoutMode.usesSideDurabilityText() && !duraRight ? sideTextW : 0;
        int[] relX = new int[n];
        int[] relY = new int[n];
        if (vertical) {
            int cursorY = 0;
            for (int i = 0; i < n; i++) {
                relX[i] = iconColRelX;
                relY[i] = cursorY;
                if (i + 1 < n) {
                    cursorY += verticalStepBetween(kinds[i], kinds[i + 1]);
                }
            }
        } else {
            int cursorX = 0;
            int stepBlockWidth = layoutMode.usesSideDurabilityText() ? ITEM_SIZE + sideTextW : ITEM_SIZE;
            for (int i = 0; i < n; i++) {
                relX[i] = cursorX + iconColRelX;
                relY[i] = 0;
                if (i + 1 < n) {
                    cursorX += horizontalStepBetween(stepBlockWidth, kinds[i], kinds[i + 1]);
                }
            }
        }

        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int bgMinX = Integer.MAX_VALUE;
        int bgMaxX = Integer.MIN_VALUE;
        int bgMinY = Integer.MAX_VALUE;
        int bgMaxY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            ItemStack stack = resolveStackForLayout(mc, kinds[i], galleryPreviewStacks, includeEmptyHeldInBackground);
            String dura = stack == null ? "" : formatDura(stack, mode);
            int slotTextW = dura.isEmpty() ? 0 : scaledTextWidth(fr.getStringWidth(dura), textScale);
            boolean hasText = slotTextW > 0;
            int slotTextH = hasText ? scaledTextHeight(fr, textScale) : 0;
            int rowX = relX[i];
            int rowY = relY[i];
            int itemY = slotItemY(rowY, kinds[i], layoutMode, fr, hasText, textScale);
            int textY = slotTextY(rowY, layoutMode, fr, hasText, textScale);
            int groupLeft = slotGroupLeft(rowX, slotTextW, layoutMode, duraRight, sideTextW);
            int groupRight = slotGroupRight(rowX, slotTextW, layoutMode, duraRight, sideTextW);
            int groupTop = Math.min(itemY, hasText ? textY : itemY);
            int groupBottom = Math.max(itemY + ITEM_SIZE, hasText ? textY + slotTextH : itemY + ITEM_SIZE);
            maxX = Math.max(maxX, groupRight);
            maxY = Math.max(maxY, groupBottom);
            if (includeSlotInBackground(mc, kinds[i], galleryPreviewStacks, includeEmptyHeldInBackground)) {
                bgMinX = Math.min(bgMinX, groupLeft);
                bgMaxX = Math.max(bgMaxX, groupRight);
                bgMinY = Math.min(bgMinY, groupTop);
                bgMaxY = Math.max(bgMaxY, groupBottom);
            }
        }
        if (maxX == Integer.MIN_VALUE) {
            maxX = ITEM_SIZE + sideTextW;
        }
        if (maxY == Integer.MIN_VALUE) {
            maxY = ITEM_SIZE;
        }
        if (bgMinX == Integer.MAX_VALUE) {
            bgMinX = 0;
            bgMaxX = maxX;
        }
        if (bgMinY == Integer.MAX_VALUE) {
            bgMinY = 0;
            bgMaxY = maxY;
        }

        LayoutBox box = new LayoutBox();
        box.layoutMode = layoutMode;
        box.kinds = kinds;
        box.duraTextW = sideTextW;
        box.duraRight = duraRight;
        box.iconColRelX = iconColRelX;
        box.bgMinX = bgMinX;
        box.bgMaxX = bgMaxX;
        box.bgMinY = bgMinY;
        box.bgMaxY = bgMaxY;
        box.slotRelX = new int[n];
        box.slotRelY = new int[n];
        for (int i = 0; i < n; i++) {
            box.slotRelX[i] = relX[i];
            box.slotRelY[i] = relY[i];
        }
        box.width = maxX;
        box.height = maxY;
        return box;
    }

    /** Widest durability string among visible slots. */
    private static int maxDuraTextWidth(FontRenderer fr, DurabilityMode mode, Minecraft mc, SlotKind[] kinds,
            boolean galleryPreviewStacks, boolean fillEmptyInEditor, float textScale) {
        if (fr == null || mode == DurabilityMode.NONE || kinds == null) {
            return 0;
        }
        int max = 0;
        for (SlotKind kind : kinds) {
            ItemStack stack = resolveStackForLayout(mc, kind, galleryPreviewStacks, fillEmptyInEditor);
            if (stack == null) {
                continue;
            }
            String dura = formatDura(stack, mode);
            if (!dura.isEmpty()) {
                max = Math.max(max, scaledTextWidth(fr.getStringWidth(dura), textScale));
            }
        }
        if (max == 0) {
            switch (mode) {
                case PERCENTAGE:
                    max = scaledTextWidth(fr.getStringWidth("100%"), textScale);
                    break;
                case COMPARED:
                    max = scaledTextWidth(fr.getStringWidth("0/0"), textScale);
                    break;
                case REGULAR:
                    max = scaledTextWidth(fr.getStringWidth("0"), textScale);
                    break;
                case NONE:
                default:
                    break;
            }
        }
        return max;
    }

    private static boolean includeSlotInBackground(Minecraft mc, SlotKind kind, boolean galleryPreviewStacks,
            boolean includeEmptyHeldInBackground) {
        if (kind != SlotKind.HELD) {
            return true;
        }
        ItemStack held = resolveStack(mc, SlotKind.HELD, galleryPreviewStacks);
        if (held != null) {
            return true;
        }
        return includeEmptyHeldInBackground;
    }

    private static int verticalStepBetween(SlotKind current, SlotKind next) {
        if (current == SlotKind.HELD) {
            return VERTICAL_STEP_AFTER_HELD_ABOVE;
        }
        if (next == SlotKind.HELD) {
            return VERTICAL_STEP_TO_HELD;
        }
        return ConfigManager.getMoreHudArmorStatusArmorSlotGap();
    }

    private static int horizontalStepBetween(int slotBlockWidth, SlotKind current, SlotKind next) {
        if (current == SlotKind.HELD) {
            return slotBlockWidth + HORIZONTAL_STEP_AFTER_HELD_LEFT;
        }
        if (next == SlotKind.HELD) {
            return slotBlockWidth + HORIZONTAL_STEP_TO_HELD;
        }
        return slotBlockWidth + ConfigManager.getMoreHudArmorStatusArmorSlotGap();
    }

    private static int itemYOffset(SlotKind kind, LayoutMode layoutMode) {
        if (kind == SlotKind.LEGS) {
            return layoutMode != null && layoutMode.isVertical()
                    ? LEGGINGS_ITEM_Y_NUDGE_VERTICAL
                    : LEGGINGS_ITEM_Y_NUDGE_HORIZONTAL;
        }
        return 0;
    }

    private static int slotItemY(int rowY, SlotKind kind, LayoutMode layoutMode, FontRenderer fr, boolean hasText,
            float textScale) {
        int itemBaseY = rowY;
        if (layoutMode != null && layoutMode.durabilityAboveItems() && hasText && fr != null) {
            itemBaseY += scaledTextHeight(fr, textScale) + STACKED_DURA_TEXT_GAP;
        }
        return itemBaseY + itemYOffset(kind, layoutMode);
    }

    private static int slotTextY(int rowY, LayoutMode layoutMode, FontRenderer fr, boolean hasText, float textScale) {
        if (!hasText) {
            return rowY;
        }
        if (layoutMode == null || layoutMode.usesSideDurabilityText()) {
            return rowY + durabilityTextY(fr, textScale);
        }
        if (layoutMode.durabilityBelowItems()) {
            return rowY + ITEM_SIZE + STACKED_DURA_TEXT_GAP;
        }
        if (layoutMode.durabilityAboveItems()) {
            return rowY;
        }
        return rowY;
    }

    private static int slotTextX(int iconX, int slotTextW, LayoutMode layoutMode, boolean duraRight, int sideTextW) {
        if (layoutMode == null || layoutMode.usesSideDurabilityText()) {
            if (duraRight) {
                return iconX + ITEM_SIZE + DURA_TEXT_GAP;
            }
            return iconX - sideTextW + (sideTextW - slotTextW);
        }
        return iconX + (ITEM_SIZE - slotTextW) / 2;
    }

    private static int slotGroupLeft(int iconX, int slotTextW, LayoutMode layoutMode, boolean duraRight, int sideTextW) {
        if (layoutMode == null || layoutMode.usesSideDurabilityText()) {
            return iconX - (duraRight ? 0 : sideTextW);
        }
        if (slotTextW <= 0) {
            return iconX;
        }
        return Math.min(iconX, slotTextX(iconX, slotTextW, layoutMode, duraRight, sideTextW));
    }

    private static int slotGroupRight(int iconX, int slotTextW, LayoutMode layoutMode, boolean duraRight, int sideTextW) {
        if (layoutMode == null || layoutMode.usesSideDurabilityText()) {
            return iconX + ITEM_SIZE + (duraRight ? sideTextW : 0);
        }
        if (slotTextW <= 0) {
            return iconX + ITEM_SIZE;
        }
        return Math.max(iconX + ITEM_SIZE, slotTextX(iconX, slotTextW, layoutMode, duraRight, sideTextW) + slotTextW);
    }

    public static int getArmorSlotGapMin(boolean vertical) {
        return vertical ? ARMOR_SLOT_GAP_VERTICAL_MIN : ARMOR_SLOT_GAP_HORIZONTAL_MIN;
    }

    public static int getArmorSlotGapMax(boolean vertical) {
        return vertical ? ARMOR_SLOT_GAP_VERTICAL_MAX : ARMOR_SLOT_GAP_HORIZONTAL_MAX;
    }

    private static float durabilityTextScale() {
        return ConfigManager.getMoreHudArmorStatusTextScale();
    }

    private static int scaledTextWidth(int rawWidth, float textScale) {
        return Math.max(0, (int) Math.ceil(rawWidth * textScale));
    }

    private static int scaledTextHeight(FontRenderer fr, float textScale) {
        int fontHeight = fr == null ? 8 : fr.FONT_HEIGHT;
        return Math.max(1, (int) Math.ceil(fontHeight * textScale));
    }

    private static int durabilityTextY(FontRenderer fr, float textScale) {
        if (fr == null) {
            return Math.max(0, (ITEM_SIZE - scaledTextHeight(null, textScale)) / 2) + 2;
        }
        return Math.max(0, (ITEM_SIZE - scaledTextHeight(fr, textScale)) / 2) + 2;
    }

    private static void drawBlock(Minecraft mc, FontRenderer fr, int anchorX, int anchorY, float renderScale,
            boolean galleryPreviewStacks, boolean fillEmptySlotsInEditor) {
        LayoutBox box = buildLayout(mc, galleryPreviewStacks, fillEmptySlotsInEditor);
        if (box == null) {
            return;
        }
        int blockLeft = blockLeftUnscaled(anchorX, box.layoutMode, box.duraRight, box.duraTextW);
        int blockTop = anchorY - BG_PAD;
        int bg = ConfigManager.getMoreHudArmorStatusBg();
        if (bg > 0) {
            int bgLeft = blockLeft + box.bgMinX;
            int bgTop = blockTop + BG_PAD + box.bgMinY;
            int bgRight = blockLeft + box.bgMaxX + BG_PAD;
            int bgBottom = blockTop + BG_PAD + box.bgMaxY;
            HudEditorBackgroundMask.drawRect(bgLeft, bgTop, bgRight, bgBottom, MoreHudBackgroundColors.armorStatusPixel(bg));
        }
        DurabilityMode mode = DurabilityMode.fromIndex(ConfigManager.getMoreHudArmorStatusDuraMode());
        int originX = blockLeft + BG_PAD;
        int originY = blockTop + BG_PAD;
        for (int i = 0; i < box.kinds.length; i++) {
            ItemStack stack = resolveStackForLayout(mc, box.kinds[i], galleryPreviewStacks, fillEmptySlotsInEditor);
            if (stack != null) {
                drawSlot(mc, fr, box.kinds[i], stack, originX + box.slotRelX[i], originY + box.slotRelY[i], mode,
                        box.layoutMode, box.duraTextW, box.duraRight, anchorX, anchorY, renderScale);
            }
        }
    }

    private static List<SlotKind> visibleSlots(Minecraft mc) {
        List<SlotKind> armor = new ArrayList<SlotKind>(4);
        if (ConfigManager.isMoreHudArmorStatusShowHelmet()) {
            armor.add(SlotKind.HELMET);
        }
        if (ConfigManager.isMoreHudArmorStatusShowChest()) {
            armor.add(SlotKind.CHEST);
        }
        if (ConfigManager.isMoreHudArmorStatusShowLegs()) {
            armor.add(SlotKind.LEGS);
        }
        if (ConfigManager.isMoreHudArmorStatusShowBoots()) {
            armor.add(SlotKind.BOOTS);
        }

        List<SlotKind> out = new ArrayList<SlotKind>(5);
        int heldPlacement = ConfigManager.getMoreHudArmorStatusHeldPlacement();
        if (heldPlacement == ConfigManager.MORE_HUD_ARMOR_HELD_ABOVE) {
            out.add(SlotKind.HELD);
        }
        out.addAll(armor);
        if (heldPlacement == ConfigManager.MORE_HUD_ARMOR_HELD_DOWN) {
            out.add(SlotKind.HELD);
        }

        return out;
    }

    private static ItemStack resolveStack(Minecraft mc, SlotKind kind, boolean galleryPreviewStacks) {
        if (galleryPreviewStacks) {
            return previewStack(kind);
        }
        if (mc != null && mc.thePlayer != null && mc.thePlayer.inventory != null) {
            InventoryPlayer inv = mc.thePlayer.inventory;
            if (kind == SlotKind.HELD) {
                return inv.getCurrentItem();
            }
            if (kind.armorIndex >= 0 && inv.armorInventory != null) {
                return inv.armorInventory[kind.armorIndex];
            }
        }
        return null;
    }

    /** Same stack resolution as {@link #drawBlock} — preview stacks fill empty slots in HUD Editor. */
    private static ItemStack resolveStackForLayout(Minecraft mc, SlotKind kind, boolean galleryPreviewStacks,
            boolean fillEmptyInEditor) {
        ItemStack stack = resolveStack(mc, kind, galleryPreviewStacks);
        if (stack == null && fillEmptyInEditor) {
            return previewStack(kind);
        }
        return stack;
    }

    private static ItemStack previewStack(SlotKind kind) {
        switch (kind) {
            case HELMET:
                return damagedRemainingPct(Items.diamond_helmet, 15);
            case CHEST:
                return damagedRemainingPct(Items.diamond_chestplate, 90);
            case LEGS:
                return damagedRemainingPct(Items.diamond_leggings, 70);
            case BOOTS:
                return damagedRemainingPct(Items.diamond_boots, 30);
            case HELD:
                return damaged(Items.diamond_sword, 200);
            default:
                return null;
        }
    }

    private static ItemStack damaged(net.minecraft.item.Item item, int damage) {
        ItemStack st = new ItemStack(item);
        st.setItemDamage(damage);
        return st;
    }

    private static ItemStack damagedRemainingPct(net.minecraft.item.Item item, int remainingPct) {
        ItemStack st = new ItemStack(item);
        int max = st.getMaxDamage();
        if (max <= 0) {
            return st;
        }
        int clampedPct = Math.max(0, Math.min(100, remainingPct));
        int remaining = (int) Math.round(max * (clampedPct / 100.0D));
        st.setItemDamage(Math.max(0, Math.min(max, max - remaining)));
        return st;
    }

    private static void drawSlot(Minecraft mc, FontRenderer fr, SlotKind kind, ItemStack stack, int x, int rowY,
            DurabilityMode mode, LayoutMode layoutMode, int textColW, boolean duraRight, int anchorX, int anchorY,
            float renderScale) {
        float textScale = durabilityTextScale();
        String dura = formatDura(stack, mode);
        boolean hasText = !dura.isEmpty();
        int itemY = slotItemY(rowY, kind, layoutMode, fr, hasText, textScale);
        drawItem(mc, stack, x, itemY, anchorX, anchorY, renderScale);
        if (!hasText) {
            return;
        }
        int color = resolveDuraColor(stack);
        int sw = scaledTextWidth(fr.getStringWidth(dura), textScale);
        float tx = slotTextX(x, sw, layoutMode, duraRight, textColW);
        float ty = slotTextY(rowY, layoutMode, fr, true, textScale);
        HudGlState.restoreForOverlayText();
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.translate(tx, ty, 300.0F);
            GlStateManager.scale(textScale, textScale, 1.0F);
            if (ConfigManager.isMoreHudArmorStatusShadow()) {
                fr.drawStringWithShadow(dura, 0.0F, 0.0F, color);
            } else {
                fr.drawString(dura, 0, 0, color);
            }
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
        }
    }

    /** Renders a 16×16 slot icon without enchant glint (vanilla effect overlay is not used on HUD rows). */
    private static void drawItem(Minecraft mc, ItemStack stack, int x, int y, int anchorX, int anchorY,
            float renderScale) {
        me.napim.gomod.util.HudGuiItemIcon.drawInventoryHudSlot(mc, stack, x, y);
        int count = stack.stackSize;
        String amount = count > 1 ? String.valueOf(count) : "";
        if (ConfigManager.isMoreHudArmorStatusDurabilityBar()) {
            try {
                mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, stack, x, y, amount);
            } catch (Throwable ignored) {
            }
        } else if (!amount.isEmpty()) {
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.disableBlend();
            mc.fontRendererObj.drawString(amount, x + 19 - 2 - mc.fontRendererObj.getStringWidth(amount),
                    y + 6 + 3, 0xFFFFFF);
            GlStateManager.enableLighting();
            GlStateManager.enableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        }
    }

    private static String formatDura(ItemStack stack, DurabilityMode mode) {
        if (stack == null || mode == DurabilityMode.NONE || !stack.isItemStackDamageable()) {
            return "";
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return "";
        }
        int remaining = max - stack.getItemDamage();
        switch (mode) {
            case REGULAR:
                return String.valueOf(remaining);
            case PERCENTAGE: {
                double pct = (double) remaining / (double) max * 100.0;
                if (pct >= 100.0) {
                    return "100%";
                }
                return PERCENT_FMT.format(pct) + "%";
            }
            case COMPARED:
                return remaining + "/" + max;
            case NONE:
            default:
                return "";
        }
    }

    private static int resolveDuraColor(ItemStack stack) {
        int tier = durabilityTier(stack);
        return ConfigManager.getMoreHudArmorStatusDuraColor(tier);
    }

    /** 1 = high, 4 = low; 0 = unknown. */
    private static int durabilityTier(ItemStack stack) {
        if (stack == null || !stack.isItemStackDamageable()) {
            return 0;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return 0;
        }
        double pct = (double) (max - stack.getItemDamage()) / (double) max * 100.0;
        if (pct > 75.0) {
            return 1;
        }
        if (pct >= 50.0) {
            return 2;
        }
        if (pct >= 25.0) {
            return 3;
        }
        return 4;
    }

    private static void drawWithRowScale(int anchorX, int anchorY, float scale, Runnable draw) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(anchorX, anchorY, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.translate(-anchorX, -anchorY, 0.0F);
        HudEditorBackgroundMask.pushTransform(anchorX, anchorY, scale);
        try {
            draw.run();
        } catch (Throwable ignored) {
        }
        HudEditorBackgroundMask.popTransform();
        GlStateManager.popMatrix();
    }
}
