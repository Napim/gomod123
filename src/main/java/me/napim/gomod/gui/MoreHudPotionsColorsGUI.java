package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzStarEffectsHudTracker;
import me.napim.gomod.features.BlitzStarEffectIcons;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MixinStatus;
import me.napim.gomod.features.MoreHudFeature;
import me.napim.gomod.util.HudGlState;
import me.napim.gomod.util.HudGuiItemIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-effect and timer colors for the Effects HUD (HUD Editor → Effects HUD → Color settings).
 */
public class MoreHudPotionsColorsGUI extends GuiScreen {

    private static final int ROW_WIDTH = HueColorPickerRow.HUD_COLOR_ROW_W;
    private static final int FOOTER_BTN_W = 168;
    private static final int BTN_H = 20;
    /** Vertical gap between "All effect names" and "Effect timer" rows. */
    private static final int FIXED_ROW_GAP = 10;
    /** Padding above/below the dark effects box inside the timer↔reset-all gap. */
    private static final int EFFECTS_SECTION_PAD = 8;
    private static final int RESET_BTN_W = HueColorPickerRow.DEFAULT_RESET_BTN_W;
    private static final int PICKER_SIZE = 20;
    private static final int PICKER_RESET_GAP = 4;
    private static final int EFFECT_RESET_SIZE = 20;
    private static final int EFFECT_CONTROLS_W = PICKER_SIZE + PICKER_RESET_GAP + EFFECT_RESET_SIZE;
    private static final int CELL_H = 24;
    private static final int CELL_ROW_GAP = 4;
    private static final int GRID_COL_GAP = 10;
    private static final int SCROLL_STEP_PX = 18;
    private static final int SCROLL_IMPULSE_PX = 36;
    private static final int SCROLL_MAX_QUEUE_PX = 240;

    private static final int ID_BACK = 99;
    private static final int ID_RESET_ALL = 98;
    private static final int ID_TIMER = 1;
    private static final int ID_UNIFORM = 2;
    private static final int ID_RESET_TIMER = 101;
    private static final int ID_RESET_UNIFORM = 102;
    private static final int ID_EFFECT_RESET_BASE = 400;

    private final GuiScreen parent;

    private final List<EffectColorCell> effectCells = new ArrayList<EffectColorCell>();
    private HueColorPickerRow timerRow;
    private HueColorPickerRow uniformRow;
    private HueColorPickerRow[] fixedColorRows;
    private GuiButton backButton;
    private GuiButton resetAllButton;
    private HueColorPickerRow draggingFixedRow;

    private int rowLeft;
    private int fixedRowLeft;
    private int contentWidth;
    private int gridCols;
    /** Rows per column (column-major fill: down first, then next column). */
    private int gridRowsPerColumn;
    private int cellWidth;
    private int fixedRowsTop;
    private int effectsAreaTop;
    private int effectsAreaBottom;
    private int scrollOffset;
    private int maxScroll;
    private boolean draggingScrollBar;
    private int scrollGrabbedAtY;
    private int scrollBarLeft;
    private int scrollBarTop;
    private int scrollBarRight;
    private int scrollBarBottom;
    private int amountToScroll;
    private int scrollDirection;
    private long lastScrollTime;
    private boolean resetAllPending;
    private String resetAckText;
    private long resetAckUntilMs;
    private boolean effectLayoutDirty = true;
    private final int[] iconPosScratch = new int[2];

    private static final class EffectColorCell {
        final int potionId;
        final HsvColorPickerWidget picker = new HsvColorPickerWidget();
        final HudCompactResetButton resetButton;
        int cellLeft;
        int rowTop;

        EffectColorCell(int potionId, int resetId) {
            this.potionId = potionId;
            this.resetButton = new HudCompactResetButton(resetId, 0, 0);
            final int id = potionId;
            picker.setExpandToRight(true);
            picker.setListener(new HsvColorPickerWidget.Listener() {
                @Override
                public void onColorChanged(int rgb) {
                    ConfigManager.previewMoreHudPotionsEffectColor(id, rgb);
                }

                @Override
                public void onColorChangeComplete(int rgb) {
                    ConfigManager.setMoreHudPotionsEffectColor(id, rgb);
                }
            });
        }

        void syncFromConfig() {
            picker.syncFromRgb(ConfigManager.getMoreHudPotionsEffectColor(potionId));
        }

        int pickerX(int cellW) {
            return cellLeft + cellW - EFFECT_CONTROLS_W;
        }

        int resetX(int cellW) {
            return pickerX(cellW) + PICKER_SIZE + PICKER_RESET_GAP;
        }

        boolean isVisible(int areaTop, int areaBottom) {
            return rowTop + CELL_H > areaTop && rowTop < areaBottom;
        }
    }

    public MoreHudPotionsColorsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    private static String timerRowLabel() {
        return "Effect timer";
    }

    @Override
    public void initGui() {
        super.initGui();
        HueColorPickerRow.prepareIconCache();
        effectCells.clear();

        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        contentWidth = Math.min(700, width - 40);
        rowLeft = cx - contentWidth / 2;
        fixedRowLeft = cx - ROW_WIDTH / 2;
        int fixedResetX = HueColorPickerRow.resetButtonX(fixedRowLeft, ROW_WIDTH, RESET_BTN_W);
        int backY = GuiHeader.backButtonY(sh, BTN_H);
        int footerLeft = cx - FOOTER_BTN_W / 2;
        int resetAllY = backY - BTN_H - 4;

        int contentTop = GuiHeader.standardContentTopAfterTitle(mc.fontRendererObj) + 8;
        fixedRowsTop = contentTop;

        gridCols = contentWidth >= 520 ? 4 : (contentWidth >= 380 ? 3 : 2);
        cellWidth = (contentWidth - GRID_COL_GAP * (gridCols - 1)) / gridCols;

        uniformRow = new HueColorPickerRow(ID_UNIFORM, "All effect names",
                rgb -> {
                    ConfigManager.setMoreHudPotionsColor(rgb);
                    ConfigManager.setMoreHudPotionsEffectColors(false);
                });
        uniformRow.syncFromRgb(ConfigManager.getMoreHudPotionsColor());
        uniformRow.layoutHudColorRow(fixedRowLeft, fixedRowsTop);

        timerRow = new HueColorPickerRow(ID_TIMER, timerRowLabel(),
                ConfigManager::setMoreHudPotionsDurationColor);
        timerRow.syncFromRgb(ConfigManager.getMoreHudPotionsDurationColor());
        timerRow.layoutHudColorRow(fixedRowLeft, fixedRowsTop + BTN_H + FIXED_ROW_GAP);

        fixedColorRows = new HueColorPickerRow[] { uniformRow, timerRow };
        HueColorPickerRow.configureStackedHudRows(fixedColorRows);
        for (HueColorPickerRow row : fixedColorRows) {
            row.setOnPersist(ConfigManager::saveConfig);
        }

        int idx = 0;
        for (int id : MoreHudFeature.getPotionsColorSettingsEffectOrder()) {
            EffectColorCell cell = new EffectColorCell(id, ID_EFFECT_RESET_BASE + id);
            cell.syncFromConfig();
            effectCells.add(cell);
            idx++;
        }
        gridRowsPerColumn = effectCells.isEmpty() ? 0 : (effectCells.size() + gridCols - 1) / gridCols;
        layoutEffectsViewport(resetAllY);

        GuiButton resetUniform = new GuiButton(ID_RESET_UNIFORM, fixedResetX, fixedRowsTop, RESET_BTN_W, BTN_H,
                GuiHeader.HUD_EDITOR_RESET_BUTTON);
        GuiButton resetTimer = new GuiButton(ID_RESET_TIMER, fixedResetX, fixedRowsTop + BTN_H + FIXED_ROW_GAP,
                RESET_BTN_W, BTN_H, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        backButton = new GuiButton(ID_BACK, footerLeft, backY, FOOTER_BTN_W, BTN_H, EnumChatFormatting.GRAY + "Back");
        resetAllButton = new ResetAllColorsButton(ID_RESET_ALL, footerLeft, resetAllY, FOOTER_BTN_W, BTN_H);

        buttonList.clear();
        uniformRow.addSliderTo(buttonList);
        timerRow.addSliderTo(buttonList);
        buttonList.add(resetUniform);
        buttonList.add(resetTimer);
        for (EffectColorCell cell : effectCells) {
            buttonList.add(cell.resetButton);
        }
        buttonList.add(resetAllButton);
        buttonList.add(backButton);

        scrollOffset = 0;
        resetAllPending = false;
        updateResetAllButtonLabel();
        computeMaxScroll();
        markEffectLayoutDirty();
    }

    private void markEffectLayoutDirty() {
        effectLayoutDirty = true;
    }

    private void layoutEffectCellsIfNeeded() {
        if (!effectLayoutDirty) {
            return;
        }
        layoutEffectCells();
        effectLayoutDirty = false;
    }

    private int fixedTimerRowBottom() {
        return fixedRowsTop + BTN_H + FIXED_ROW_GAP + BTN_H;
    }

    /** Height of all effect rows (no viewport padding). */
    private int effectsContentHeight() {
        if (effectCells.isEmpty() || gridRowsPerColumn <= 0) {
            return 0;
        }
        return gridRowsPerColumn * CELL_H + (gridRowsPerColumn - 1) * CELL_ROW_GAP;
    }

    private int effectsContentTop() {
        int contentH = effectsContentHeight();
        int viewportH = effectsAreaBottom - effectsAreaTop;
        if (contentH <= 0 || contentH >= viewportH) {
            return effectsAreaTop;
        }
        return effectsAreaTop + (viewportH - contentH) / 2;
    }

    /** Vertical center of name + timer text block within a cell. */
    private int effectControlsY(int rowTop) {
        int textBlockH = fontRendererObj.FONT_HEIGHT * 2 + 1;
        int textTop = rowTop + (CELL_H - textBlockH) / 2;
        return textTop + textBlockH / 2 - PICKER_SIZE / 2;
    }

    /**
     * Positions the dark scroll viewport between the effect-timer row and Reset all colors.
     * Tweak {@link #EFFECTS_SECTION_PAD} and {@link #FIXED_ROW_GAP} to move/resize that box.
     */
    private void layoutEffectsViewport(int resetAllY) {
        int sectionTop = fixedTimerRowBottom();
        int sectionBottom = resetAllY;
        int sectionH = Math.max(0, sectionBottom - sectionTop);
        int contentH = effectsContentHeight();
        int maxViewport = Math.max(0, sectionH - EFFECTS_SECTION_PAD * 2);
        if (contentH <= maxViewport) {
            int boxH = Math.max(contentH, 1);
            int boxTop = sectionTop + (sectionH - boxH) / 2;
            effectsAreaTop = boxTop;
            effectsAreaBottom = boxTop + boxH;
        } else {
            effectsAreaTop = sectionTop + EFFECTS_SECTION_PAD;
            effectsAreaBottom = sectionBottom - EFFECTS_SECTION_PAD;
        }
    }

    private int scrollableEffectsHeight() {
        return effectsContentHeight();
    }

    private void computeMaxScroll() {
        maxScroll = Math.max(0, scrollableEffectsHeight() - (effectsAreaBottom - effectsAreaTop));
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
    }

    private void scrollBy(int amount) {
        scrollOffset = scrollOffset - amount;
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            amountToScroll = 0;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
            amountToScroll = 0;
        }
        markEffectLayoutDirty();
    }

    private void layoutEffectCells() {
        for (int idx = 0; idx < effectCells.size(); idx++) {
            EffectColorCell cell = effectCells.get(idx);
            int col = gridRowsPerColumn > 0 ? idx / gridRowsPerColumn : 0;
            int row = gridRowsPerColumn > 0 ? idx % gridRowsPerColumn : idx;
            cell.cellLeft = rowLeft + col * (cellWidth + GRID_COL_GAP);
            cell.rowTop = effectsContentTop() + row * (CELL_H + CELL_ROW_GAP) - scrollOffset;
            int controlsY = effectControlsY(cell.rowTop);
            boolean lastCol = col >= gridCols - 1;
            cell.picker.setExpandBelow(lastCol);
            cell.picker.setExpandToRight(!lastCol);
            cell.picker.setPosition(cell.pickerX(cellWidth), controlsY);
            cell.resetButton.xPosition = cell.resetX(cellWidth);
            cell.resetButton.yPosition = controlsY;
        }
        updateEffectCellVisibility();
    }

    private void updateResetAllButtonLabel() {
        if (resetAllButton == null) {
            return;
        }
        resetAllButton.displayString = resetAllPending
                ? EnumChatFormatting.DARK_RED + "CONFIRM RESET"
                : EnumChatFormatting.RED + "Reset all colors";
    }

    private void updateEffectCellVisibility() {
        for (EffectColorCell cell : effectCells) {
            boolean visible = cell.isVisible(effectsAreaTop, effectsAreaBottom);
            cell.picker.setVisible(visible);
            cell.resetButton.visible = visible;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        if (amountToScroll > 0 && maxScroll > 0) {
            long time = System.currentTimeMillis();
            if (time - lastScrollTime > 1) {
                int step = Math.min(SCROLL_STEP_PX, amountToScroll);
                scrollBy(scrollDirection * step);
                amountToScroll -= step;
                lastScrollTime = time;
            }
        }

        layoutEffectCellsIfNeeded();
        drawEffectsGrid(mouseX, mouseY, partialTicks);

        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Effects HUD colors"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y,
                0xFFFFFF);

        drawScrollBar(mouseX, mouseY);
        layoutEffectCellsIfNeeded();

        drawMenuButtons(mouseX, mouseY, partialTicks);

        drawResetAcknowledgement();
        drawEffectPickers(mouseX, mouseY, partialTicks);
        drawEffectsEdgeFades();
        HueColorPickerRow.drawAllPickerOverlays(mc, mouseX, mouseY, partialTicks, fixedColorRows);
    }

    private void drawMenuButtons(int mouseX, int mouseY, float partialTicks) {
        // Let GuiScreen draw the fixed controls, then paint scrolling reset buttons under the viewport clip.
        for (EffectColorCell cell : effectCells) {
            cell.resetButton.visible = false;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        updateEffectCellVisibility();

        beginEffectsViewportClip();
        try {
            for (EffectColorCell cell : effectCells) {
                if (cell.isVisible(effectsAreaTop, effectsAreaBottom) && cell.resetButton.visible) {
                    cell.resetButton.drawButton(mc, mouseX, mouseY);
                }
            }
        } finally {
            endEffectsViewportClip();
        }
    }

    private void showResetAcknowledged() {
        resetAckText = EnumChatFormatting.GRAY + "Colors have been reset";
        resetAckUntilMs = System.currentTimeMillis() + 2500L;
    }

    private void drawResetAcknowledgement() {
        if (resetAckText == null || resetAckText.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() > resetAckUntilMs) {
            resetAckText = null;
            return;
        }
        if (resetAllButton == null) {
            return;
        }
        int tw = fontRendererObj.getStringWidth(resetAckText);
        int tx = resetAllButton.xPosition + (resetAllButton.width - tw) / 2;
        int ty = resetAllButton.yPosition - fontRendererObj.FONT_HEIGHT - 3;
        fontRendererObj.drawStringWithShadow(resetAckText, tx, ty, 0xFFAAAAAA);
    }

    private void drawEffectsGrid(int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(rowLeft, effectsAreaTop, rowLeft + contentWidth, effectsAreaBottom, 0x22000000);
        Gui.drawRect(rowLeft, effectsAreaTop, rowLeft + contentWidth, effectsAreaTop + 1, 0x44AAAAAA);
        Gui.drawRect(rowLeft, effectsAreaBottom - 1, rowLeft + contentWidth, effectsAreaBottom, 0x44AAAAAA);

        HudGlState.restoreForOverlayText();
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        beginEffectsViewportClip();
        boolean perEffect = ConfigManager.isMoreHudPotionsEffectColors();
        int uniformRgb = ConfigManager.getMoreHudPotionsColor();
        int durationRgb = MoreHudFeature.getPotionsDurationDrawRgb();
        try {
            for (EffectColorCell cell : effectCells) {
                if (!cell.isVisible(effectsAreaTop, effectsAreaBottom)) {
                    continue;
                }
                int textRight = cell.pickerX(cellWidth) - 4;
                boolean blitz = BlitzStarEffectsHudTracker.effectForColorSlot(cell.potionId) != null;
                MoreHudFeature.drawPotionsColorSettingsEffectRow(mc, fontRendererObj, cell.potionId,
                        cell.cellLeft, cell.rowTop, textRight, CELL_H, !blitz,
                        perEffect, uniformRgb, durationRgb);
            }
            drawVisibleBlitzItemIcons();
        } catch (Throwable ignored) {
        } finally {
            endEffectsViewportClip();
            HudGlState.restoreForOverlayText();
            GlStateManager.popMatrix();
        }
    }

    /** Blitz star item icons in one GL batch (custom PNGs drawn outside the batch). */
    private void drawVisibleBlitzItemIcons() {
        // The Infection icon is a flat texture. Draw it before enabling item lighting/depth for the
        // vanilla item batch; mixing the two render paths made the custom icon flicker in this grid.
        for (EffectColorCell cell : effectCells) {
            if (!cell.isVisible(effectsAreaTop, effectsAreaBottom)) {
                continue;
            }
            BlitzStarEffectsHudTracker.Effect effect =
                    BlitzStarEffectsHudTracker.effectForColorSlot(cell.potionId);
            if (effect == BlitzStarEffectsHudTracker.Effect.INFECTION
                    && MoreHudFeature.getPotionsColorSettingsIconPos(
                            fontRendererObj, cell.cellLeft, cell.rowTop, CELL_H, iconPosScratch)) {
                BlitzStarEffectIcons.draw(mc, effect.getIconStack(), iconPosScratch[0], iconPosScratch[1],
                        MoreHudFeature.POTIONS_COLOR_SETTINGS_ICON_SIZE, effect);
            }
        }

        boolean needsItemBatch = false;
        for (EffectColorCell cell : effectCells) {
            if (!cell.isVisible(effectsAreaTop, effectsAreaBottom)) {
                continue;
            }
            BlitzStarEffectsHudTracker.Effect effect =
                    BlitzStarEffectsHudTracker.effectForColorSlot(cell.potionId);
            if (effect != null && effect != BlitzStarEffectsHudTracker.Effect.INFECTION) {
                needsItemBatch = true;
                break;
            }
        }
        if (needsItemBatch) {
            HudGuiItemIcon.beginGuiScreenItemBatch(mc);
        }
        try {
            for (EffectColorCell cell : effectCells) {
                if (!cell.isVisible(effectsAreaTop, effectsAreaBottom)) {
                    continue;
                }
                BlitzStarEffectsHudTracker.Effect effect =
                        BlitzStarEffectsHudTracker.effectForColorSlot(cell.potionId);
                if (effect == null || !MoreHudFeature.getPotionsColorSettingsIconPos(
                        fontRendererObj, cell.cellLeft, cell.rowTop, CELL_H, iconPosScratch)
                        || effect == BlitzStarEffectsHudTracker.Effect.INFECTION) {
                    continue;
                }
                HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, effect.getIconStack(), iconPosScratch[0], iconPosScratch[1]);
            }
        } finally {
            if (needsItemBatch) {
                HudGuiItemIcon.endGuiScreenItemBatch(mc);
            }
        }
    }

    private void drawEffectPickers(int mouseX, int mouseY, float partialTicks) {
        HsvColorPickerWidget openPicker = findOpenEffectPicker();
        GlStateManager.pushMatrix();
        beginEffectsViewportClip();
        try {
            HudGlState.restoreForOverlayText();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            for (EffectColorCell cell : effectCells) {
                if (!cell.isVisible(effectsAreaTop, effectsAreaBottom)) {
                    continue;
                }
                if (openPicker != null && cell.picker.isObscuredByOtherOpenPanel(openPicker)) {
                    continue;
                }
                cell.picker.drawButton(mc, mouseX, mouseY);
            }
            if (openPicker != null) {
                openPicker.drawExpanded(mc, mouseX, mouseY, partialTicks);
                openPicker.drawButton(mc, mouseX, mouseY);
            }
        } finally {
            endEffectsViewportClip();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    private void beginEffectsViewportClip() {
        try {
            ScaledResolution sr = new ScaledResolution(mc);
            int scale = Math.max(1, sr.getScaleFactor());
            int x = rowLeft * scale;
            int y = mc.displayHeight - effectsAreaBottom * scale;
            int w = Math.max(1, contentWidth * scale);
            int h = Math.max(1, (effectsAreaBottom - effectsAreaTop) * scale);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, w, h);
        } catch (Throwable ignored) {
        }
    }

    private void endEffectsViewportClip() {
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } catch (Throwable ignored) {
        }
    }

    private boolean isInsideEffectsViewport(int mouseX, int mouseY) {
        return mouseX >= rowLeft && mouseX < rowLeft + contentWidth
                && mouseY >= effectsAreaTop && mouseY < effectsAreaBottom;
    }

    /** Fade rows into the clipped edges, matching the other scrollable settings panels. */
    private void drawEffectsEdgeFades() {
        if (maxScroll <= 0) {
            return;
        }
        int viewportH = effectsAreaBottom - effectsAreaTop;
        int fadeH = Math.min(GuiScrollFade.MAX_FADE_H, Math.max(14, viewportH / 4));
        fadeH = Math.min(fadeH, Math.max(1, viewportH / 2));
        int left = rowLeft + 1;
        int right = rowLeft + contentWidth - 1;
        for (int i = 0; i < fadeH; i++) {
            float alpha = 0.8F * (fadeH - i) / fadeH;
            int color = GuiScrollFade.argb(alpha, 0x101010);
            if (scrollOffset > 0) {
                Gui.drawRect(left, effectsAreaTop + 1 + i, right, effectsAreaTop + 2 + i, color);
            }
            if (scrollOffset < maxScroll) {
                Gui.drawRect(left, effectsAreaBottom - 2 - i, right, effectsAreaBottom - 1 - i, color);
            }
        }
    }

    private HsvColorPickerWidget findOpenEffectPicker() {
        for (EffectColorCell cell : effectCells) {
            if (cell.isVisible(effectsAreaTop, effectsAreaBottom) && cell.picker.isOpenOrAnimating()) {
                return cell.picker;
            }
        }
        return null;
    }

    private void drawScrollBar(int mouseX, int mouseY) {
        computeMaxScroll();
        if (maxScroll <= 0) {
            return;
        }
        int trackX = width - 10;
        int trackWidth = 4;
        int trackTop = effectsAreaTop;
        int trackBottom = effectsAreaBottom;
        int trackHeight = trackBottom - trackTop;
        int visibleHeight = effectsAreaBottom - effectsAreaTop;
        int totalHeight = Math.max(1, scrollableEffectsHeight());
        int thumbHeight = Math.max(20, (int) ((float) trackHeight * ((float) visibleHeight / (float) totalHeight)));
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int maxThumbTravel = trackHeight - thumbHeight;
        int thumbY = trackTop + (int) ((float) maxThumbTravel * ((float) scrollOffset / (float) maxScroll));

        Gui.drawRect(trackX, trackTop, trackX + trackWidth, trackBottom, 0x60000000);
        Gui.drawRect(trackX, thumbY, trackX + trackWidth, thumbY + thumbHeight, 0x90FFFFFF);

        scrollBarLeft = trackX;
        scrollBarTop = thumbY;
        scrollBarRight = trackX + trackWidth;
        scrollBarBottom = thumbY + thumbHeight;

        if (draggingScrollBar) {
            int relativeMouseY = mouseY - trackTop - scrollGrabbedAtY;
            int clamped = Math.max(0, Math.min(maxThumbTravel, relativeMouseY));
            scrollOffset = maxThumbTravel <= 0 ? 0 : (int) ((float) maxScroll * ((float) clamped / (float) maxThumbTravel));
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
            amountToScroll = 0;
            markEffectLayoutDirty();
        }
    }

    private void collapseAllPickersExcept(HsvColorPickerWidget keepEffect, HueColorPickerRow keepFixed) {
        if (keepFixed != uniformRow) {
            uniformRow.collapse();
        }
        if (keepFixed != timerRow) {
            timerRow.collapse();
        }
        for (EffectColorCell cell : effectCells) {
            if (cell.picker != keepEffect) {
                cell.picker.collapse();
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        draggingFixedRow = null;

        if (mouseButton == 0 && maxScroll > 0
                && mouseX >= scrollBarLeft && mouseX < scrollBarRight
                && mouseY >= scrollBarTop && mouseY < scrollBarBottom) {
            draggingScrollBar = true;
            scrollGrabbedAtY = mouseY - scrollBarTop;
            amountToScroll = 0;
            return;
        }

        if (uniformRow.tryPickerClick(mc, mouseX, mouseY, mouseButton, fixedColorRows)) {
            collapseAllPickersExcept(null, uniformRow);
            return;
        }
        if (timerRow.tryPickerClick(mc, mouseX, mouseY, mouseButton, fixedColorRows)) {
            collapseAllPickersExcept(null, timerRow);
            return;
        }

        boolean inEffectsViewport = isInsideEffectsViewport(mouseX, mouseY);
        if (inEffectsViewport) {
            for (EffectColorCell cell : effectCells) {
                if (!cell.isVisible(effectsAreaTop, effectsAreaBottom)) {
                    continue;
                }
                if (cell.picker.isVisible() && cell.picker.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
                    collapseAllPickersExcept(cell.picker, null);
                    return;
                }
            }
        }

        if (uniformRow.trySliderPress(mc, mouseX, mouseY)) {
            draggingFixedRow = uniformRow;
            return;
        }
        if (timerRow.trySliderPress(mc, mouseX, mouseY)) {
            draggingFixedRow = timerRow;
            return;
        }

        if (inEffectsViewport) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        // A partly clipped cell reset button must not remain clickable beyond the visible box.
        for (EffectColorCell cell : effectCells) {
            cell.resetButton.visible = false;
        }
        try {
            super.mouseClicked(mouseX, mouseY, mouseButton);
        } finally {
            updateEffectCellVisibility();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingFixedRow != null) {
            draggingFixedRow.dragSlider(mouseX);
        }
        uniformRow.pickerMouseMove(mouseX, mouseY);
        timerRow.pickerMouseMove(mouseX, mouseY);
        for (EffectColorCell cell : effectCells) {
            if (cell.isVisible(effectsAreaTop, effectsAreaBottom)) {
                cell.picker.mouseClickMove(mouseX, mouseY);
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && draggingScrollBar) {
            draggingScrollBar = false;
        }
        if (draggingFixedRow != null) {
            draggingFixedRow.releaseSlider(mouseX, mouseY);
            draggingFixedRow = null;
            ConfigManager.saveConfig();
        }
        uniformRow.pickerMouseRelease(mouseX, mouseY);
        timerRow.pickerMouseRelease(mouseX, mouseY);
        for (EffectColorCell cell : effectCells) {
            cell.picker.mouseReleased(mouseX, mouseY);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0 || maxScroll <= 0) {
            return;
        }
        if (dWheel > 0) {
            if (scrollDirection != 1) {
                scrollDirection = 1;
                amountToScroll = 0;
            }
            amountToScroll = Math.min(SCROLL_MAX_QUEUE_PX, amountToScroll + SCROLL_IMPULSE_PX);
        } else if (dWheel < 0) {
            if (scrollDirection != -1) {
                scrollDirection = -1;
                amountToScroll = 0;
            }
            amountToScroll = Math.min(SCROLL_MAX_QUEUE_PX, amountToScroll + SCROLL_IMPULSE_PX);
        }
        lastScrollTime = System.currentTimeMillis();
    }

    public void actionPerformed(GuiButton button) {
        if (button.id == ID_BACK) {
            resetAllPending = false;
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == ID_RESET_ALL) {
            if (!resetAllPending) {
                resetAllPending = true;
                updateResetAllButtonLabel();
                return;
            }
            resetAllPending = false;
            updateResetAllButtonLabel();
            ConfigManager.resetAllMoreHudPotionsColors();
            refreshAllRows();
            showResetAcknowledged();
            return;
        }
        if (button.id == ID_RESET_TIMER) {
            ConfigManager.resetMoreHudPotionsDurationColor();
            timerRow.setRgb(ConfigManager.getMoreHudPotionsDurationColor());
            ConfigManager.saveConfig();
            return;
        }
        if (button.id == ID_RESET_UNIFORM) {
            ConfigManager.setMoreHudPotionsColor(0xFFFFFF);
            ConfigManager.setMoreHudPotionsEffectColors(false);
            uniformRow.setRgb(ConfigManager.getMoreHudPotionsColor());
            ConfigManager.saveConfig();
            return;
        }
        if (button.id >= ID_EFFECT_RESET_BASE && button.id < ID_EFFECT_RESET_BASE + 100) {
            int potionId = button.id - ID_EFFECT_RESET_BASE;
            ConfigManager.resetMoreHudPotionsEffectColor(potionId);
            for (EffectColorCell cell : effectCells) {
                if (cell.potionId == potionId) {
                    cell.syncFromConfig();
                    break;
                }
            }
            ConfigManager.saveConfig();
        }
    }

    private void refreshAllRows() {
        timerRow.syncFromRgb(ConfigManager.getMoreHudPotionsDurationColor());
        uniformRow.syncFromRgb(ConfigManager.getMoreHudPotionsColor());
        for (EffectColorCell cell : effectCells) {
            cell.syncFromConfig();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /**
     * Centers label text correctly (vanilla {@link GuiButton} mis-centers formatted / bold strings).
     */
    private static final class ResetAllColorsButton extends GuiButton {

        ResetAllColorsButton(int id, int x, int y, int w, int h) {
            super(id, x, y, w, h, "");
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            String label = displayString;
            displayString = "";
            int textColor;
            if (!enabled) {
                textColor = 0xFFA0A0A0;
            } else if (hovered) {
                textColor = 0xFFFFFFA0;
            } else {
                textColor = 0xFFE0E0E0;
            }
            MixinStatus.skipFontRendererDisguiseReplace = true;
            try {
                super.drawButton(mc, mouseX, mouseY);
                if (label != null && !label.isEmpty()) {
                    FontRenderer fr = mc.fontRendererObj;
                    int ty = yPosition + (height - 8) / 2;
                    int tx = GuiHeader.getButtonCenteredTextX(fr, xPosition, width, label);
                    fr.drawStringWithShadow(label, tx, ty, textColor);
                }
            } finally {
                MixinStatus.skipFontRendererDisguiseReplace = false;
                displayString = label;
            }
        }
    }
}
