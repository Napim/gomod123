package me.napim.gomod.gui;

import me.napim.gomod.features.OfficialCapePalette;
import me.napim.gomod.features.PlayerCustomCapes;
import me.napim.gomod.features.PlayerStandaloneCape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import me.napim.gomod.util.GomodFileDialogs;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.renderer.GlStateManager;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Custom cape painter: live player preview, wear-region canvas (where pixels actually show in-game),
 * and a full 64×32 reference map.
 */
public class CustomCapeEditorGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int BTN_SAVE = 1;
    private static final int BTN_CANCEL = 2;
    private static final int BTN_CLEAR = 3;
    private static final int BTN_IMPORT = 4;
    private static final int BTN_PLAYER_OPACITY = 6;
    private static final int BTN_RESET_COLOR_ADJUST = 8;
    private static final int BTN_HUE_ADJUST = 9;
    private static final int BTN_SATURATION_ADJUST = 10;
    private static final int BTN_BRIGHTNESS_ADJUST = 11;
    private static final int MAX_UNDO = 20;

    private static final int CAPE_W = PlayerCustomCapes.capeWidth();
    private static final int CAPE_H = PlayerCustomCapes.capeHeight();
    private static final int MINI_SHEET_SCALE = 3;
    private static final int PREVIEW_CTRL_W = 118;
    private static final int PALETTE_SWATCH = 11;
    private static final int PALETTE_GAP = 2;
    private static final int RECENT_COLOR_SLOTS = 6;
    private static final int COLOR_ADJUST_SLIDER_H = 20;
    private static final int COLOR_ADJUST_SLIDER_W = 160;
    private static final int COLOR_ADJUST_START_Y = 22;
    private static final int COLOR_ADJUST_STEP_Y = 22;
    private static final int COLOR_ADJUST_RESET_Y = COLOR_ADJUST_START_Y + COLOR_ADJUST_STEP_Y * 2;
    private static final int PAINT_CANVAS_TOP_OFFSET = 94;
    private static final int PAINT_CANVAS_BOTTOM_PAD = 20;
    private static final int PREVIEW_CONTROLS_H = 92;
    private static final int PREVIEW_FOOTER_BTN_H = 20;
    private static final int PREVIEW_FOOTER_PAD = 6;
    private static final int PREVIEW_FOOTER_BTN_W = 56;
    private static final int PREVIEW_FOOTER_BTN_GAP = 5;
    private static final int MIN_PREVIEW_PANEL_W = PREVIEW_FOOTER_BTN_W * 3
            + PREVIEW_FOOTER_BTN_GAP * 2 + 14;

    private enum CapeFace {
        BACK(0, CapeEditorRegions.HALF_W),
        FRONT(CapeEditorRegions.FRONT_HALF_X, CapeEditorRegions.HALF_W);

        final int sheetX;
        final int sheetW;

        CapeFace(int sheetX, int sheetW) {
            this.sheetX = sheetX;
            this.sheetW = sheetW;
        }
    }

    private final GuiScreen parent;
    private final String editId;
    private final String loadSourceId;
    private final BufferedImage initialCanvas;
    private final String defaultNameOverride;
    private boolean optifineLayout;
    private BufferedImage renameBaselineCanvas;
    private BufferedImage canvas;
    private GuiTextField nameField;
    private MountOpacitySlider playerOpacitySlider;
    private CapeColorAdjustSlider hueAdjustSlider;
    private CapeColorAdjustSlider saturationAdjustSlider;
    private CapeColorAdjustSlider brightnessAdjustSlider;
    private GuiButton selectedSlider;
    private int[] paletteColors = OfficialCapePalette.colorsForEditor();
    private final int[] recentColors = new int[RECENT_COLOR_SLOTS];
    private int recentColorCount;
    private int selectedColor = 0xFF000000;
    private CapeFace editingFace = CapeFace.BACK;

    private EntityOtherPlayerMP previewPlayer;
    private float previewYaw = 200.0F;
    private float previewPitch = 14.0F;
    private float previewDisplayYaw = 200.0F;
    private float previewDisplayPitch = 14.0F;
    private float playerBodyAlpha = 1.0F;
    private boolean draggingPreview;
    private int lastDragMouseX;
    private int lastDragMouseY;
    private boolean painting;
    private boolean strokeUndoPushed;
    private boolean paintedThisStroke;
    private int paletteScrollRow;

    private HsvColorPickerWidget colorPicker;

    private final ArrayDeque<int[]> undoStack = new ArrayDeque<int[]>();
    private final ArrayDeque<int[]> redoStack = new ArrayDeque<int[]>();
    /** Canvas before the currently previewed H/S/B adjustment group. */
    private int[] colorAdjustmentBaseline;

    private int headerBottom;
    private int previewPanelL;
    private int previewPanelT;
    private int previewPanelR;
    private int previewPanelB;
    private int previewCenterX;
    private int previewBaseY;
    private int previewScale;
    private int previewModelTop;
    private int previewModelBottom;
    private int previewDragHintY;

    private int paintPanelL;
    private int paintPanelT;
    private int paintPanelR;
    private int paintPanelB;
    private int wearCanvasX;
    private int wearCanvasY;
    private int wearCanvasW;
    private int wearCanvasH;

    private int toolsPanelL;
    private int toolsPanelT;
    private int toolsPanelR;
    private int toolsPanelB;
    private int paletteLeftX;
    private int paletteRightX;
    private int paletteY;
    private int recentColorsY;
    private int miniSheetX;
    private int miniSheetY;
    private int miniSheetW;
    private int miniSheetH;

    public CustomCapeEditorGUI(GuiScreen parent) {
        this(parent, null);
    }

    public CustomCapeEditorGUI(GuiScreen parent, String editId) {
        this(parent, editId, false);
    }

    public CustomCapeEditorGUI(GuiScreen parent, String sourceEditId, boolean saveAsNewCopy) {
        this.parent = parent;
        if (saveAsNewCopy && sourceEditId != null && !sourceEditId.isEmpty()) {
            this.editId = null;
            this.loadSourceId = sourceEditId;
            this.initialCanvas = null;
            this.defaultNameOverride = null;
        } else {
            this.editId = sourceEditId == null || sourceEditId.isEmpty() ? null : sourceEditId;
            this.loadSourceId = null;
            this.initialCanvas = null;
            this.defaultNameOverride = null;
        }
    }

    public CustomCapeEditorGUI(GuiScreen parent, BufferedImage initialCanvas, String defaultName) {
        this(parent, initialCanvas, defaultName, looksLikeOptifineCanvas(initialCanvas, defaultName));
    }

    public CustomCapeEditorGUI(GuiScreen parent, BufferedImage initialCanvas, String defaultName, boolean optifineLayout) {
        this.parent = parent;
        this.editId = null;
        this.loadSourceId = null;
        this.initialCanvas = initialCanvas;
        this.defaultNameOverride = defaultName == null ? "My cape" : defaultName;
        this.optifineLayout = optifineLayout;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        if (editId != null) {
            PlayerCustomCapes.SavedCape saved = PlayerCustomCapes.getById(editId);
            BufferedImage existing = PlayerCustomCapes.loadImageForId(editId);
            this.canvas = existing != null ? copyImage(existing) : PlayerCustomCapes.newEditorCanvas();
            // Saved metadata is authoritative. Normal gomod capes use many of the same 46x22 texels as OptiFine,
            // so inferring the layout from non-transparent pixel bounds mislabels ordinary custom capes.
            this.optifineLayout = isOptifineSavedCape(saved);
        } else if (loadSourceId != null) {
            PlayerCustomCapes.SavedCape saved = PlayerCustomCapes.getById(loadSourceId);
            BufferedImage existing = PlayerCustomCapes.loadImageForId(loadSourceId);
            this.canvas = existing != null ? copyImage(existing) : PlayerCustomCapes.newEditorCanvas();
            this.renameBaselineCanvas = this.canvas != null ? copyImage(this.canvas) : null;
            this.optifineLayout = isOptifineSavedCape(saved);
        } else if (initialCanvas != null) {
            this.canvas = copyImage(initialCanvas);
        } else {
            this.canvas = PlayerCustomCapes.newEditorCanvas();
        }
        layoutPanels();

        this.nameField = new GuiTextField(10, this.fontRendererObj, previewPanelL + 8,
                previewPanelT + 28, PREVIEW_CTRL_W, 18);
        this.nameField.setMaxStringLength(48);
        // Do not steal keyboard focus when the editor opens; focus the field only after the user clicks it.
        this.nameField.setFocused(false);
        if (defaultNameOverride != null) {
            this.nameField.setText(EnumChatFormatting.getTextWithoutFormattingCodes(defaultNameOverride));
        } else if (editId != null) {
            PlayerCustomCapes.SavedCape saved = PlayerCustomCapes.getById(editId);
            if (saved != null) {
                this.nameField.setText(EnumChatFormatting.getTextWithoutFormattingCodes(saved.label));
            }
        } else if (loadSourceId != null) {
            PlayerCustomCapes.SavedCape saved = PlayerCustomCapes.getById(loadSourceId);
            if (saved != null) {
                this.nameField.setText(EnumChatFormatting.getTextWithoutFormattingCodes(saved.label));
            } else {
                this.nameField.setText("My cape");
            }
        } else {
            this.nameField.setText("My cape");
        }

        int btnY = previewPanelB - PREVIEW_FOOTER_PAD - PREVIEW_FOOTER_BTN_H;
        int cx = (previewPanelL + previewPanelR) / 2;
        int btnW = PREVIEW_FOOTER_BTN_W;
        int btnGap = PREVIEW_FOOTER_BTN_GAP;
        int totalBtnW = btnW * 3 + btnGap * 2;
        int btnStartX = cx - totalBtnW / 2;
        this.buttonList.add(new GuiButton(BTN_SAVE, btnStartX, btnY, btnW, PREVIEW_FOOTER_BTN_H,
                EnumChatFormatting.GREEN + "Save"));
        this.buttonList.add(new GuiButton(BTN_CANCEL, btnStartX + btnW + btnGap, btnY, btnW, PREVIEW_FOOTER_BTN_H,
                EnumChatFormatting.GRAY + "Cancel"));
        this.buttonList.add(new GuiButton(BTN_CLEAR, btnStartX + (btnW + btnGap) * 2, btnY, btnW, PREVIEW_FOOTER_BTN_H,
                EnumChatFormatting.RED + "Clear"));
        this.buttonList.add(new GuiButton(BTN_IMPORT, previewPanelL + 8, previewPanelT + 50, PREVIEW_CTRL_W, 20,
                EnumChatFormatting.AQUA + "Import cape"));
        this.playerOpacitySlider = new MountOpacitySlider(BTN_PLAYER_OPACITY,
                previewPanelL + 8, previewPanelT + 74, PREVIEW_CTRL_W,
                "Player opacity:", 1.0F, true);
        this.playerOpacitySlider.setInteractionLocked(false);
        this.playerOpacitySlider.enabled = true;
        this.playerBodyAlpha = this.playerOpacitySlider.getValue();
        this.buttonList.add(this.playerOpacitySlider);

        this.colorAdjustmentBaseline = null;
        this.hueAdjustSlider = new CapeColorAdjustSlider(BTN_HUE_ADJUST, 0, 0, 100,
                "Hue", -180.0F, 180.0F, true);
        this.saturationAdjustSlider = new CapeColorAdjustSlider(BTN_SATURATION_ADJUST, 0, 0, 100,
                "Saturation", -100.0F, 100.0F, false);
        this.brightnessAdjustSlider = new CapeColorAdjustSlider(BTN_BRIGHTNESS_ADJUST, 0, 0, 100,
                "Brightness", -100.0F, 100.0F, false);
        this.buttonList.add(this.hueAdjustSlider);
        this.buttonList.add(this.saturationAdjustSlider);
        this.buttonList.add(this.brightnessAdjustSlider);
        this.buttonList.add(new GuiButton(BTN_RESET_COLOR_ADJUST, 0, 0, 64, 20,
                EnumChatFormatting.RED + "Reset"));
        positionColorAdjustmentWidgets();

        OfficialCapePalette.refreshAsync();
        paletteColors = OfficialCapePalette.colorsForEditor();

        initColorPicker();

        refreshPreviewPlayer();
    }

    private void initColorPicker() {
        HsvColorPickerWidget.warmIconCache(this.mc);
        if (this.colorPicker == null) {
            this.colorPicker = new HsvColorPickerWidget();
            this.colorPicker.setRetainOpenForEyedropper(true);
            this.colorPicker.setAnimateExpand(false);
            this.colorPicker.setListener(new HsvColorPickerWidget.Listener() {
                @Override
                public void onColorChanged(int rgb) {
                    applySelectedColor(0xFF000000 | (rgb & 0xFFFFFF), false);
                }

                @Override
                public void onColorChangeComplete(int rgb) {
                    applySelectedColor(0xFF000000 | (rgb & 0xFFFFFF), true);
                    if (CustomCapeEditorGUI.this.colorPicker != null) {
                        CustomCapeEditorGUI.this.colorPicker.collapse();
                    }
                }
            });
        }
        this.colorPicker.setVisible(true);
        this.colorPicker.setEnabled(true);
        syncColorPickerFromSelection();
        layoutColorPicker();
    }

    private void layoutColorPicker() {
        if (this.colorPicker == null) {
            return;
        }
        int pickerSize = this.colorPicker.getButtonWidth();
        this.colorPicker.setPosition(toolsPanelR - pickerSize - 4, toolsPanelT + 6);
    }

    private void syncColorPickerFromSelection() {
        if (this.colorPicker == null) {
            return;
        }
        if (selectedColor == 0 || (selectedColor >>> 24) < 8) {
            this.colorPicker.syncFromRgb(0);
        } else {
            this.colorPicker.syncFromRgb(selectedColor & 0xFFFFFF);
        }
    }

    private void drawColorPickerOverlay(int mouseX, int mouseY, float partialTicks) {
        if (this.colorPicker == null || !this.colorPicker.isVisible()) {
            return;
        }
        layoutColorPicker();
        GlStateManager.pushMatrix();
        try {
            HudGlState.restoreForOverlayText();
            GlStateManager.disableDepth();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GlStateManager.depthMask(false);
            GlStateManager.translate(0.0F, 0.0F, 300.0F);
            this.colorPicker.drawButton(this.mc, mouseX, mouseY);
            this.colorPicker.drawExpanded(this.mc, mouseX, mouseY, partialTicks);
            if (this.colorPicker.isOpenOrAnimating()) {
                GlStateManager.disableDepth();
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GlStateManager.depthMask(false);
                this.colorPicker.drawButton(this.mc, mouseX, mouseY);
                if (this.colorPicker.shouldDrawEyedropperCursor(mouseX, mouseY)) {
                    HsvColorPickerWidget.drawEyedropperNearCursor(this.mc, mouseX, mouseY);
                }
            }
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    private boolean tryColorPickerClick(int mouseX, int mouseY, int mouseButton) {
        if (this.colorPicker == null || !this.colorPicker.isVisible()) {
            return false;
        }
        layoutColorPicker();
        if (this.colorPicker.tryConsumeClick(this.mc, mouseX, mouseY, mouseButton)) {
            return true;
        }
        if (mouseButton == 0 && this.colorPicker.isExpanded()
                && this.colorPicker.shouldDrawEyedropperCursor(mouseX, mouseY)) {
            if (tryPickColorFromCanvas(mouseX, mouseY)) {
                syncColorPickerFromSelection();
                this.colorPicker.collapse();
                return true;
            }
        }
        return false;
    }

    @Override
    public void onGuiClosed() {
        this.draggingPreview = false;
        MountPreviewHelper.discardCapePreviewPlayer(this.mc);
        this.previewPlayer = null;
        PlayerCustomCapes.releaseEditorPreviewTexture();
        super.onGuiClosed();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        layoutPanels();
        positionDynamicWidgets();

        String title = editId == null
                ? (loadSourceId != null || initialCanvas != null ? "Save as new cape" : "Create custom cape")
                : "Edit custom cape";
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + title,
                this.width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        drawPreviewPanel(mouseX, mouseY, partialTicks);
        drawPaintPanel(mouseX, mouseY);
        drawToolsPanel(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.nameField.drawTextBox();
        drawColorPickerOverlay(mouseX, mouseY, partialTicks);
    }

    private void positionDynamicWidgets() {
        if (this.playerOpacitySlider != null) {
            this.playerOpacitySlider.xPosition = previewPanelL + 8;
            this.playerOpacitySlider.yPosition = previewPanelT + 74;
            this.playerOpacitySlider.width = PREVIEW_CTRL_W;
        }
        GuiButton importBtn = findButton(BTN_IMPORT);
        if (importBtn != null) {
            importBtn.xPosition = previewPanelL + 8;
            importBtn.yPosition = previewPanelT + 50;
            importBtn.width = PREVIEW_CTRL_W;
            importBtn.height = 20;
        }
        if (this.nameField != null) {
            this.nameField.xPosition = previewPanelL + 8;
            this.nameField.yPosition = previewPanelT + 28;
            this.nameField.width = PREVIEW_CTRL_W;
        }
        positionColorAdjustmentWidgets();
        positionPreviewFooterButtons();
    }

    private void positionColorAdjustmentWidgets() {
        int innerW = Math.max(80, paintPanelR - paintPanelL - 16);
        int resetW = Math.min(64, Math.max(48, innerW / 3));
        int controlGap = 6;
        int sliderW = Math.min(COLOR_ADJUST_SLIDER_W,
                Math.max(80, innerW - resetW - controlGap));
        int controlsW = sliderW + controlGap + resetW;
        int sliderX = paintPanelL + (paintPanelR - paintPanelL - controlsW) / 2;
        positionColorAdjustmentSlider(this.hueAdjustSlider, sliderX,
                paintPanelT + COLOR_ADJUST_START_Y, sliderW);
        positionColorAdjustmentSlider(this.saturationAdjustSlider, sliderX,
                paintPanelT + COLOR_ADJUST_START_Y + COLOR_ADJUST_STEP_Y, sliderW);
        positionColorAdjustmentSlider(this.brightnessAdjustSlider, sliderX,
                paintPanelT + COLOR_ADJUST_START_Y + COLOR_ADJUST_STEP_Y * 2, sliderW);

        GuiButton reset = findButton(BTN_RESET_COLOR_ADJUST);
        if (reset != null) {
            reset.width = resetW;
            reset.xPosition = sliderX + sliderW + controlGap;
            reset.yPosition = paintPanelT + COLOR_ADJUST_RESET_Y;
            reset.height = 20;
            reset.enabled = this.colorAdjustmentBaseline != null;
        }
    }

    private static void positionColorAdjustmentSlider(CapeColorAdjustSlider slider, int x, int y, int width) {
        if (slider == null) {
            return;
        }
        slider.xPosition = x;
        slider.yPosition = y;
        slider.width = width;
        slider.height = COLOR_ADJUST_SLIDER_H;
    }

    private void positionPreviewFooterButtons() {
        int btnY = previewPanelB - PREVIEW_FOOTER_PAD - PREVIEW_FOOTER_BTN_H;
        int cx = previewCenterX;
        int btnW = PREVIEW_FOOTER_BTN_W;
        int btnGap = PREVIEW_FOOTER_BTN_GAP;
        int totalBtnW = btnW * 3 + btnGap * 2;
        int btnStartX = cx - totalBtnW / 2;
        GuiButton save = findButton(BTN_SAVE);
        GuiButton cancel = findButton(BTN_CANCEL);
        GuiButton clear = findButton(BTN_CLEAR);
        if (save != null) {
            save.xPosition = btnStartX;
            save.yPosition = btnY;
            save.width = btnW;
            save.height = PREVIEW_FOOTER_BTN_H;
        }
        if (cancel != null) {
            cancel.xPosition = btnStartX + btnW + btnGap;
            cancel.yPosition = btnY;
            cancel.width = btnW;
            cancel.height = PREVIEW_FOOTER_BTN_H;
        }
        if (clear != null) {
            clear.xPosition = btnStartX + (btnW + btnGap) * 2;
            clear.yPosition = btnY;
            clear.width = btnW;
            clear.height = PREVIEW_FOOTER_BTN_H;
        }
    }

    private GuiButton findButton(int id) {
        for (Object o : this.buttonList) {
            if (o instanceof GuiButton && ((GuiButton) o).id == id) {
                return (GuiButton) o;
            }
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        layoutPanels();
        positionDynamicWidgets();
        if (this.nameField != null) {
            this.nameField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (tryColorPickerClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        boolean eyedropperOpen = this.colorPicker != null && this.colorPicker.isExpanded();
        if (playerOpacitySlider != null && playerOpacitySlider.mousePressed(this.mc, mouseX, mouseY)) {
            selectedSlider = playerOpacitySlider;
            playerBodyAlpha = playerOpacitySlider.getValue();
            return;
        }
        if (mouseButton == 0 && tryPressColorAdjustmentSlider(mouseX, mouseY)) {
            return;
        }
        if (mouseButton == 0 || mouseButton == 1) {
            int recent = recentColorHit(mouseX, mouseY);
            if (recent >= 0) {
                applySelectedColor(recentColors[recent], true);
                return;
            }
            int pal = paletteHit(mouseX, mouseY);
            if (pal >= 0) {
                applySelectedColor(paletteColors[pal], true);
                return;
            }
            if (wearCanvasHit(mouseX, mouseY)) {
                if (eyedropperOpen && mouseButton == 0) {
                    if (tryPickColorFromCanvas(mouseX, mouseY)) {
                        syncColorPickerFromSelection();
                        this.colorPicker.collapse();
                    }
                    return;
                }
                pushUndoOncePerStroke();
                painting = true;
                paintOnWearCanvas(mouseX, mouseY, mouseButton != 1);
                refreshPreviewPlayer();
                return;
            }
            if (miniSheetHit(mouseX, mouseY)) {
                if (eyedropperOpen && mouseButton == 0) {
                    if (tryPickColorFromCanvas(mouseX, mouseY)) {
                        syncColorPickerFromSelection();
                        this.colorPicker.collapse();
                    }
                    return;
                }
                int px = (mouseX - miniSheetX) / MINI_SHEET_SCALE;
                editingFace = px < CapeEditorRegions.SHEET_W / 2 ? CapeFace.BACK : CapeFace.FRONT;
                pushUndoOncePerStroke();
                painting = true;
                paintOnMiniSheet(mouseX, mouseY, mouseButton != 1);
                refreshPreviewPlayer();
                return;
            }
            if (isInsidePreviewModelArea(mouseX, mouseY)) {
                draggingPreview = true;
                lastDragMouseX = mouseX;
                lastDragMouseY = mouseY;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        layoutPanels();
        positionDynamicWidgets();
        if (this.colorPicker != null) {
            this.colorPicker.mouseClickMove(mouseX, mouseY);
            if (this.colorPicker.isDraggingAdjust()) {
                return;
            }
        }
        if (selectedSlider instanceof MountOpacitySlider) {
            MountOpacitySlider slider = (MountOpacitySlider) selectedSlider;
            slider.mouseDragged(this.mc, mouseX, mouseY);
            playerBodyAlpha = slider.getValue();
            return;
        }
        if (selectedSlider instanceof CapeColorAdjustSlider) {
            CapeColorAdjustSlider slider = (CapeColorAdjustSlider) selectedSlider;
            slider.mouseDragged(this.mc, mouseX, mouseY);
            applyColorAdjustmentsFromBaseline();
            return;
        }
        if (draggingPreview && clickedMouseButton == 0) {
            previewYaw += (mouseX - lastDragMouseX) * 1.35F;
            previewPitch += (lastDragMouseY - mouseY) * 0.85F;
            previewPitch = Math.max(-55.0F, Math.min(55.0F, previewPitch));
            lastDragMouseX = mouseX;
            lastDragMouseY = mouseY;
            return;
        }
        if (painting && (clickedMouseButton == 0 || clickedMouseButton == 1)) {
            if (this.colorPicker != null && this.colorPicker.isExpanded()) {
                return;
            }
            if (wearCanvasHit(mouseX, mouseY)) {
                paintOnWearCanvas(mouseX, mouseY, clickedMouseButton != 1);
            } else if (miniSheetHit(mouseX, mouseY)) {
                paintOnMiniSheet(mouseX, mouseY, clickedMouseButton != 1);
            }
            refreshPreviewPlayer();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        layoutPanels();
        positionDynamicWidgets();
        if (this.colorPicker != null) {
            this.colorPicker.mouseReleased(mouseX, mouseY);
        }
        if (state == 0 && selectedSlider instanceof MountOpacitySlider) {
            playerBodyAlpha = ((MountOpacitySlider) selectedSlider).getValue();
        }
        if (state == 0 && selectedSlider instanceof CapeColorAdjustSlider) {
            ((CapeColorAdjustSlider) selectedSlider).mouseReleased(mouseX, mouseY);
            applyColorAdjustmentsFromBaseline();
        }
        selectedSlider = null;
        if (paintedThisStroke && selectedColor != 0) {
            pushRecentColor(selectedColor);
        }
        paintedThisStroke = false;
        painting = false;
        strokeUndoPushed = false;
        draggingPreview = false;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        ScaledResolution scaled = new ScaledResolution(this.mc);
        int eventX = org.lwjgl.input.Mouse.getEventX();
        int eventY = org.lwjgl.input.Mouse.getEventY();
        int mx = eventX * scaled.getScaledWidth() / this.mc.displayWidth;
        int my = scaled.getScaledHeight() - eventY * scaled.getScaledHeight() / this.mc.displayHeight - 1;
        layoutPanels();
        int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (dWheel == 0) {
            return;
        }
        int maxScroll = maxPaletteScrollRows();
        if (maxScroll <= 0) {
            paletteScrollRow = 0;
            return;
        }
        if (maxScroll > 0 && mx >= toolsPanelL && mx < toolsPanelR && my >= paletteY && my < miniSheetY - 8) {
            paletteScrollRow += dWheel > 0 ? -1 : 1;
            paletteScrollRow = Math.max(0, Math.min(maxScroll, paletteScrollRow));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BTN_CANCEL) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == BTN_CLEAR) {
            pushUndo();
            this.canvas = PlayerCustomCapes.newEditorCanvas();
            refreshPreviewPlayer();
            return;
        }
        if (button.id == BTN_IMPORT) {
            openImportFileDialog();
            return;
        }
        if (button.id == BTN_RESET_COLOR_ADJUST) {
            resetColorAdjustmentSession();
            return;
        }
        if (button.id == BTN_SAVE) {
            saveDesign();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.nameField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (keyCode == 1) {
            if (this.colorPicker != null && this.colorPicker.isOpenOrAnimating()) {
                this.colorPicker.collapse();
                return;
            }
            this.mc.displayGuiScreen(parent);
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
        if (ctrl && keyCode == Keyboard.KEY_Y) {
            redo();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.nameField != null) {
            this.nameField.updateCursorCounter();
        }
        if (this.playerOpacitySlider != null) {
            playerBodyAlpha = this.playerOpacitySlider.getValue();
        }
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(nameField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void layoutPanels() {
        headerBottom = 92;
        int bottom = this.height - 36;
        int pad = 12;
        int gap = 10;
        int usableW = Math.max(320, this.width - pad * 2);

        int toolsW = Math.min(200, Math.max(168, usableW / 4));
        int availableForPreviewAndPaint = Math.max(240, usableW - toolsW - gap * 2);
        int minPaintW = paintSheetWidth() * 2 + 20;
        int preferredPaintW = optifineLayout ? 300 : 286;
        int paintW = Math.min(preferredPaintW,
                Math.max(minPaintW, availableForPreviewAndPaint - MIN_PREVIEW_PANEL_W));
        int previewW = availableForPreviewAndPaint - paintW;
        if (previewW < MIN_PREVIEW_PANEL_W) {
            previewW = MIN_PREVIEW_PANEL_W;
            paintW = Math.max(minPaintW, availableForPreviewAndPaint - previewW);
        }

        previewPanelL = pad;
        previewPanelT = headerBottom;
        previewPanelR = previewPanelL + previewW;
        previewPanelB = bottom;

        paintPanelL = previewPanelR + gap;
        paintPanelT = headerBottom;
        paintPanelR = paintPanelL + paintW;
        paintPanelB = bottom;

        toolsPanelL = paintPanelR + gap;
        toolsPanelT = headerBottom;
        toolsPanelR = toolsPanelL + toolsW;
        toolsPanelB = bottom;

        int wearScale = wearPixelScaleForLayout();
        wearCanvasW = paintSheetWidth() * wearScale;
        wearCanvasH = paintSheetHeight() * wearScale;
        wearCanvasX = paintPanelL + (paintPanelR - paintPanelL - wearCanvasW) / 2;
        wearCanvasY = paintPanelT + PAINT_CANVAS_TOP_OFFSET;

        previewModelTop = previewPanelT + PREVIEW_CONTROLS_H;
        previewModelBottom = previewPanelB - 22;
        previewDragHintY = previewPanelB - 52;
        previewCenterX = (previewPanelL + previewPanelR) / 2;
        int modelAreaHeight = Math.max(72, previewModelBottom - previewModelTop);
        previewScale = Math.max(48, Math.min(68, modelAreaHeight / 2));
        int modelHeight = (int) (previewScale * 1.95F);
        previewBaseY = previewModelTop + (modelAreaHeight + modelHeight) / 2 - 4;

        miniSheetW = CapeEditorRegions.SHEET_W * MINI_SHEET_SCALE;
        miniSheetH = CapeEditorRegions.SHEET_H * MINI_SHEET_SCALE;
        miniSheetX = toolsPanelL + (toolsPanelR - toolsPanelL - miniSheetW) / 2;
        miniSheetY = toolsPanelB - miniSheetH - 14;

        paletteLeftX = toolsPanelL + 4;
        paletteRightX = toolsPanelL + (toolsPanelR - toolsPanelL) / 2 + 2;
        paletteY = toolsPanelT + 50;
        recentColorsY = toolsPanelT + 32;
    }

    private void drawPreviewPanel(int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(previewPanelL, previewPanelT, previewPanelR, previewPanelB, 0xAA070B10);
        drawBorder(previewPanelL, previewPanelT, previewPanelR - previewPanelL, previewPanelB - previewPanelT, 0x664A6278);

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "Live preview",
                previewPanelL + 8, previewPanelT + 6, 0xFFFFFF);
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.YELLOW + "Name",
                previewPanelL + 8, previewPanelT + 16, 0xFFFFFF);

        Gui.drawRect(previewPanelL + 6, previewModelTop - 2, previewPanelR - 6, previewPanelB - 2, 0x55080C12);

        refreshPreviewPlayer();
        if (previewPlayer != null) {
            float renderYaw = tickPreviewDisplayYaw(partialTicks);
            float renderPitch = tickPreviewDisplayPitch(partialTicks);
            MountPreviewHelper.renderCapePreviewPlayer(this.mc, previewPlayer, previewCenterX, previewBaseY,
                    previewScale, 1.0F, renderYaw, renderPitch, playerBodyAlpha);
        }

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + "Drag player to orbit",
                previewCenterX - this.fontRendererObj.getStringWidth("Drag player to orbit") / 2,
                previewDragHintY, 0x888888);
    }

    private void drawPaintPanel(int mouseX, int mouseY) {
        Gui.drawRect(paintPanelL, paintPanelT, paintPanelR, paintPanelB, 0xAA070B10);
        drawBorder(paintPanelL, paintPanelT, paintPanelR - paintPanelL, paintPanelB - paintPanelT, 0x664A6278);

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "Cape layout",
                paintPanelL + 8, paintPanelT + 8, 0xFFFFFF);

        CapeFace face = editingFace;
        int scale = wearPixelScale();
        CapeEditorRegions.drawChecker(wearCanvasX - 2, wearCanvasY - 2,
                paintSheetWidth() * scale + 4, paintSheetHeight() * scale + 4);
        drawBorder(wearCanvasX - 1, wearCanvasY - 1, paintSheetWidth() * scale + 2, paintSheetHeight() * scale + 2,
                wearCanvasHit(mouseX, mouseY) ? 0xCC88CCFF : 0x88667788);

        for (int row = 0; row < paintSheetHeight(); row++) {
            for (int col = 0; col < paintSheetWidth(); col++) {
                int px = optifineLayout ? col : face.sheetX + col;
                int py = row;
                int sx = wearCanvasX + col * scale;
                int sy = wearCanvasY + row * scale;
                if (!canPaintPixel(px, py, face)) {
                    Gui.drawRect(sx, sy, sx + scale, sy + scale, 0xFF151A20);
                    continue;
                }
                int rgb = canvas.getRGB(px, py);
                Gui.drawRect(sx, sy, sx + scale, sy + scale, argbToGuiColor(rgb));
            }
        }
        drawWearCanvasHoverPreview(mouseX, mouseY, face, scale);

        String historyHint = "CTRL+Z Undo  |  CTRL+SHIFT+Z Redo";
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + historyHint,
                (paintPanelL + paintPanelR - this.fontRendererObj.getStringWidth(historyHint)) / 2,
                paintPanelB - 12, 0x777777);
    }

    private void drawToolsPanel(int mouseX, int mouseY) {
        Gui.drawRect(toolsPanelL, toolsPanelT, toolsPanelR, toolsPanelB, 0xAA070B10);
        drawBorder(toolsPanelL, toolsPanelT, toolsPanelR - toolsPanelL, toolsPanelB - toolsPanelT, 0x664A6278);

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "Colors",
                toolsPanelL + 6, toolsPanelT + 8, 0xFFFFFF);
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + "Recent",
                toolsPanelL + 6, recentColorsY - 10, 0x777777);
        drawRecentColors(mouseX, mouseY);
        drawPalette(mouseX, mouseY);

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + "Sheet",
                miniSheetX, miniSheetY - 10, 0x888888);

        Gui.drawRect(miniSheetX - 2, miniSheetY - 2, miniSheetX + miniSheetW + 2, miniSheetY + miniSheetH + 2, 0xFF4A6278);
        CapeEditorRegions.drawChecker(miniSheetX, miniSheetY, miniSheetW, miniSheetH);
        for (int py = 0; py < CapeEditorRegions.SHEET_H; py++) {
            for (int px = 0; px < CapeEditorRegions.SHEET_W; px++) {
                int x = miniSheetX + px * MINI_SHEET_SCALE;
                int y = miniSheetY + py * MINI_SHEET_SCALE;
                int rgb = canvas.getRGB(px, py);
                int color = argbToGuiColor(rgb);
                boolean wear = canPaintPixel(px, py, editingFace);
                if (!wear) {
                    color = dimColor(color, CapeEditorRegions.isFoldPixelOnHalf(px, py, editingFace == CapeFace.BACK) ? 0.35F : 0.55F);
                }
                Gui.drawRect(x, y, x + MINI_SHEET_SCALE, y + MINI_SHEET_SCALE, color);
            }
        }
        if (miniSheetHit(mouseX, mouseY)) {
            drawBorder(miniSheetX, miniSheetY, miniSheetW, miniSheetH, 0xAAFFFF88);
        }
    }

    private void drawRecentColors(int mouseX, int mouseY) {
        int cell = PALETTE_SWATCH + PALETTE_GAP;
        for (int i = 0; i < RECENT_COLOR_SLOTS; i++) {
            int x = toolsPanelL + 6 + i * cell;
            int y = recentColorsY;
            if (i >= recentColorCount) {
                Gui.drawRect(x, y, x + PALETTE_SWATCH, y + PALETTE_SWATCH, 0xFF1A2228);
                continue;
            }
            int c = recentColors[i];
            drawPaletteSwatch(x, y, c, mouseX, mouseY);
        }
    }

    private void drawPalette(int mouseX, int mouseY) {
        if (paletteColors == null || paletteColors.length == 0) {
            paletteColors = OfficialCapePalette.colorsForEditor();
        }
        int rowH = PALETTE_SWATCH + PALETTE_GAP;
        int maxRows = Math.max(1, (miniSheetY - 10 - paletteY) / rowH);
        int cols = paletteColumnsPerSide();
        int half = (paletteColors.length + 1) / 2;
        int leftRows = (half + cols - 1) / cols;
        int rightRows = ((paletteColors.length - half) + cols - 1) / cols;
        int totalRows = Math.max(leftRows, rightRows);
        int maxScroll = Math.max(0, totalRows - maxRows);
        paletteScrollRow = Math.min(paletteScrollRow, maxScroll);
        drawPaletteSide(mouseX, mouseY, 0, half, paletteLeftX, paletteScrollRow, maxRows, cols);
        drawPaletteSide(mouseX, mouseY, half, paletteColors.length, paletteRightX, paletteScrollRow, maxRows, cols);
    }

    private int maxPaletteScrollRows() {
        if (paletteColors == null || paletteColors.length == 0) {
            return 0;
        }
        int rowH = PALETTE_SWATCH + PALETTE_GAP;
        int maxRows = Math.max(1, (miniSheetY - 10 - paletteY) / rowH);
        int cols = paletteColumnsPerSide();
        int half = (paletteColors.length + 1) / 2;
        int leftRows = (half + cols - 1) / cols;
        int rightRows = ((paletteColors.length - half) + cols - 1) / cols;
        return Math.max(0, Math.max(leftRows, rightRows) - maxRows);
    }

    private int paletteColumnsPerSide() {
        int rowH = PALETTE_SWATCH + PALETTE_GAP;
        int halfWidth = Math.max(rowH, (toolsPanelR - toolsPanelL) / 2 - 8);
        return Math.max(1, halfWidth / rowH);
    }

    private void drawPaletteSide(int mouseX, int mouseY, int from, int to, int baseX, int scrollRow,
                                 int maxRows, int cols) {
        int rowH = PALETTE_SWATCH + PALETTE_GAP;
        int start = scrollRow * cols;
        int end = Math.min(to - from, start + maxRows * cols);
        for (int rel = start; rel < end; rel++) {
            int i = from + rel;
            int col = rel % cols;
            int row = rel / cols - scrollRow;
            int x = baseX + col * rowH;
            int y = paletteY + row * rowH;
            drawPaletteSwatch(x, y, paletteColors[i], mouseX, mouseY);
        }
    }

    private void drawPaletteSwatch(int x, int y, int c, int mouseX, int mouseY) {
        if (c == 0) {
            Gui.drawRect(x, y, x + PALETTE_SWATCH, y + PALETTE_SWATCH, 0xFF202830);
            CapeEditorRegions.drawChecker(x + 2, y + 2, PALETTE_SWATCH - 4, PALETTE_SWATCH - 4);
        } else {
            Gui.drawRect(x, y, x + PALETTE_SWATCH, y + PALETTE_SWATCH, argbToGuiColor(c));
        }
        if (colorsMatch(selectedColor, c)) {
            drawBorder(x - 1, y - 1, PALETTE_SWATCH + 2, PALETTE_SWATCH + 2, 0xFFFFFFFF);
        }
        if (mouseX >= x && mouseX < x + PALETTE_SWATCH && mouseY >= y && mouseY < y + PALETTE_SWATCH) {
            drawBorder(x, y, PALETTE_SWATCH, PALETTE_SWATCH, 0xAAFFFF88);
        }
    }

    private static boolean colorsMatch(int a, int b) {
        if (a == b) {
            return true;
        }
        if ((a >>> 24) < 8 && (b >>> 24) < 8) {
            return true;
        }
        return false;
    }

    private int wearPixelScaleForLayout() {
        int maxW = Math.max(80, paintPanelR - paintPanelL - 12);
        int maxH = Math.max(48, paintPanelB - paintPanelT
                - PAINT_CANVAS_TOP_OFFSET - PAINT_CANVAS_BOTTOM_PAD);
        int byW = maxW / paintSheetWidth();
        int byH = maxH / paintSheetHeight();
        return Math.max(2, Math.min(9, Math.min(byW, byH)));
    }

    private int wearPixelScale() {
        return wearPixelScaleForLayout();
    }

    private int paintSheetWidth() {
        return optifineLayout ? CapeEditorRegions.SHEET_W : CapeEditorRegions.HALF_W;
    }

    private int paintSheetHeight() {
        return CapeEditorRegions.SHEET_H;
    }

    private boolean canPaintPixel(int px, int py, CapeFace face) {
        if (optifineLayout) {
            return isCapeSheetPixel(px, py);
        }
        return CapeEditorRegions.isWearPixel(px, py, face == CapeFace.BACK);
    }

    private boolean canPaintPixelOnMiniSheet(int px, int py) {
        if (optifineLayout) {
            return isCapeSheetPixel(px, py);
        }
        return CapeEditorRegions.isWearPixel(px, py);
    }

    private static boolean isCapeSheetPixel(int px, int py) {
        return px >= 0 && px < CapeEditorRegions.SHEET_W && py >= 0 && py < CapeEditorRegions.SHEET_H;
    }

    private static boolean isOptifineSavedCape(PlayerCustomCapes.SavedCape saved) {
        return saved != null && saved.isOptifineLayout();
    }

    private static boolean looksLikeOptifineCanvas(BufferedImage img, String label) {
        if (label != null && label.toLowerCase(Locale.ROOT).contains("optifine")) {
            return true;
        }
        // Raw OptiFine cape sheets are 46x22 (or an integer-resolution multiple). A normalized 64x32 editor
        // canvas is deliberately not guessed from its pixel contents because gomod's native mask overlaps it.
        return img != null && img.getWidth() >= CapeWearMask.OPTIFINE_W
                && img.getHeight() >= CapeWearMask.OPTIFINE_H
                && img.getWidth() % CapeWearMask.OPTIFINE_W == 0
                && img.getHeight() % CapeWearMask.OPTIFINE_H == 0
                && img.getWidth() / CapeWearMask.OPTIFINE_W == img.getHeight() / CapeWearMask.OPTIFINE_H;
    }

    private void refreshPreviewPlayer() {
        if (this.mc == null || this.mc.theWorld == null || this.canvas == null) {
            return;
        }
        ResourceLocation cape = PlayerCustomCapes.textureForEditorPreview(this.canvas);
        if (previewPlayer == null) {
            previewPlayer = MountPreviewHelper.createCapePreviewPlayer(this.mc.theWorld, this.mc, cape);
        } else {
            MountPreviewHelper.updateCapePreviewPlayer(this.mc, previewPlayer, cape);
        }
    }

    private float tickPreviewDisplayYaw(float partialTicks) {
        float delta = wrapYawDelta(previewYaw - previewDisplayYaw);
        previewDisplayYaw += delta * Math.min(1.0F, 0.28F * (0.35F + partialTicks * 0.65F));
        return previewDisplayYaw;
    }

    private float tickPreviewDisplayPitch(float partialTicks) {
        float delta = previewPitch - previewDisplayPitch;
        previewDisplayPitch += delta * Math.min(1.0F, 0.28F * (0.35F + partialTicks * 0.65F));
        return previewDisplayPitch;
    }

    private static float wrapYawDelta(float degrees) {
        float d = degrees % 360.0F;
        if (d > 180.0F) {
            d -= 360.0F;
        } else if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    private boolean isInsidePreviewModelArea(int mouseX, int mouseY) {
        int orbitBottom = previewPanelB - PREVIEW_FOOTER_PAD - PREVIEW_FOOTER_BTN_H - 2;
        return mouseX >= previewPanelL + 6 && mouseX < previewPanelR - 6
                && mouseY >= previewModelTop && mouseY < orbitBottom;
    }

    private void applySelectedColor(int rgb, boolean recordRecent) {
        int opaque = rgb & 0xFFFFFF;
        if ((rgb >>> 24) < 8) {
            selectedColor = 0x00000000;
        } else {
            selectedColor = 0xFF000000 | opaque;
        }
        if (recordRecent && selectedColor != 0) {
            pushRecentColor(selectedColor);
        }
        syncColorPickerFromSelection();
    }

    private void pushRecentColor(int argb) {
        for (int i = 0; i < recentColorCount; i++) {
            if (recentColors[i] == argb) {
                if (i == 0) {
                    return;
                }
                System.arraycopy(recentColors, 0, recentColors, 1, i);
                recentColors[0] = argb;
                return;
            }
        }
        if (recentColorCount < RECENT_COLOR_SLOTS) {
            System.arraycopy(recentColors, 0, recentColors, 1, recentColorCount);
            recentColorCount++;
        } else {
            System.arraycopy(recentColors, 0, recentColors, 1, RECENT_COLOR_SLOTS - 1);
        }
        recentColors[0] = argb;
    }

    private int recentColorHit(int mouseX, int mouseY) {
        int cell = PALETTE_SWATCH + PALETTE_GAP;
        for (int i = 0; i < recentColorCount; i++) {
            int x = toolsPanelL + 6 + i * cell;
            int y = recentColorsY;
            if (mouseX >= x && mouseX < x + PALETTE_SWATCH && mouseY >= y && mouseY < y + PALETTE_SWATCH) {
                return i;
            }
        }
        return -1;
    }

    private boolean tryPickColorFromCanvas(int mouseX, int mouseY) {
        int argb;
        if (wearCanvasHit(mouseX, mouseY)) {
            CapeFace face = editingFace;
            int scale = wearPixelScale();
            int lx = mouseX - wearCanvasX;
            int ly = mouseY - wearCanvasY;
            int col = Math.min(paintSheetWidth() - 1, Math.max(0, lx / Math.max(1, scale)));
            int row = Math.min(paintSheetHeight() - 1, Math.max(0, ly / Math.max(1, scale)));
            int px = optifineLayout ? col : face.sheetX + col;
            argb = canvas.getRGB(px, row);
        } else if (miniSheetHit(mouseX, mouseY)) {
            int px = (mouseX - miniSheetX) / MINI_SHEET_SCALE;
            int py = (mouseY - miniSheetY) / MINI_SHEET_SCALE;
            argb = canvas.getRGB(px, py);
        } else if (isInsidePreviewModelArea(mouseX, mouseY)) {
            Integer sampled = sampleScreenPixel(mouseX, mouseY);
            if (sampled == null) {
                return false;
            }
            argb = sampled;
        } else {
            return false;
        }
        applySelectedColor(argb, true);
        return true;
    }

    private Integer sampleScreenPixel(int mouseX, int mouseY) {
        try {
            if (this.mc == null || this.mc.displayWidth <= 0 || this.mc.displayHeight <= 0) {
                return null;
            }
            ScaledResolution scaled = new ScaledResolution(this.mc);
            int scaleFactor = Math.max(1, scaled.getScaleFactor());
            int px = Math.max(0, Math.min(this.mc.displayWidth - 1, mouseX * scaleFactor));
            int py = Math.max(0, Math.min(this.mc.displayHeight - 1,
                    this.mc.displayHeight - (mouseY + 1) * scaleFactor));
            ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(4);
            GL11.glReadPixels(px, py, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            int r = buffer.get(0) & 0xFF;
            int g = buffer.get(1) & 0xFF;
            int b = buffer.get(2) & 0xFF;
            int a = buffer.get(3) & 0xFF;
            if (a < 8) {
                return null;
            }
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean wearCanvasHit(int mouseX, int mouseY) {
        int scale = wearPixelScale();
        int w = paintSheetWidth() * scale;
        int h = paintSheetHeight() * scale;
        return mouseX >= wearCanvasX && mouseX < wearCanvasX + w
                && mouseY >= wearCanvasY && mouseY < wearCanvasY + h;
    }

    private boolean miniSheetHit(int mouseX, int mouseY) {
        return mouseX >= miniSheetX && mouseX < miniSheetX + miniSheetW
                && mouseY >= miniSheetY && mouseY < miniSheetY + miniSheetH;
    }

    /** Shows the exact result of a left-click without mutating the cape until the click actually happens. */
    private void drawWearCanvasHoverPreview(int mouseX, int mouseY, CapeFace face, int scale) {
        if (!wearCanvasHit(mouseX, mouseY)
                || this.colorPicker != null && this.colorPicker.isExpanded()) {
            return;
        }

        int col = Math.min(paintSheetWidth() - 1,
                Math.max(0, (mouseX - wearCanvasX) / Math.max(1, scale)));
        int row = Math.min(paintSheetHeight() - 1,
                Math.max(0, (mouseY - wearCanvasY) / Math.max(1, scale)));
        int px = optifineLayout ? col : face.sheetX + col;
        if (!canPaintPixel(px, row, face)) {
            return;
        }

        int sx = wearCanvasX + col * scale;
        int sy = wearCanvasY + row * scale;
        if ((selectedColor >>> 24) < 8) {
            drawCheckerRegionAligned(sx, sy, scale, scale, wearCanvasX - 2, wearCanvasY - 2);
        } else {
            Gui.drawRect(sx, sy, sx + scale, sy + scale, argbToGuiColor(selectedColor));
        }

        // A dark outer edge plus a high-contrast inner edge makes the exact texel readable on every color.
        drawBorder(sx - 1, sy - 1, scale + 2, scale + 2, 0xDD000000);
        drawBorder(sx, sy, scale, scale, hoverPixelOutlineColor(selectedColor));
    }

    /** Repaints a clipped piece of the canvas checkerboard while preserving its original grid alignment. */
    private static void drawCheckerRegionAligned(int x, int y, int w, int h, int originX, int originY) {
        final int cell = 6;
        int bottom = y + h;
        int right = x + w;
        for (int cy = y; cy < bottom; ) {
            int gridY = Math.floorDiv(cy - originY, cell);
            int nextY = Math.min(bottom, originY + (gridY + 1) * cell);
            for (int cx = x; cx < right; ) {
                int gridX = Math.floorDiv(cx - originX, cell);
                int nextX = Math.min(right, originX + (gridX + 1) * cell);
                boolean light = ((gridX + gridY) & 1) == 0;
                Gui.drawRect(cx, cy, nextX, nextY, light ? 0xFF2A3340 : 0xFF1A2228);
                cx = nextX;
            }
            cy = nextY;
        }
    }

    private static int hoverPixelOutlineColor(int argb) {
        if ((argb >>> 24) < 8) {
            return 0xFFFFFFFF;
        }
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        return luminance >= 145 ? 0xFF101010 : 0xFFFFFFFF;
    }

    private void paintOnWearCanvas(int mouseX, int mouseY, boolean paint) {
        CapeFace face = editingFace;
        int scale = wearPixelScale();
        int lx = mouseX - wearCanvasX;
        int ly = mouseY - wearCanvasY;
        int col = Math.min(paintSheetWidth() - 1, Math.max(0, lx / Math.max(1, scale)));
        int row = Math.min(paintSheetHeight() - 1, Math.max(0, ly / Math.max(1, scale)));
        int px = optifineLayout ? col : face.sheetX + col;
        if (!canPaintPixel(px, row, face)) {
            return;
        }
        canvas.setRGB(px, row, paint ? selectedColor : 0x00000000);
        if (paint) {
            paintedThisStroke = true;
        }
    }

    private void paintOnMiniSheet(int mouseX, int mouseY, boolean paint) {
        int px = (mouseX - miniSheetX) / MINI_SHEET_SCALE;
        int py = (mouseY - miniSheetY) / MINI_SHEET_SCALE;
        if (px < 0 || py < 0 || px >= CapeEditorRegions.SHEET_W || py >= CapeEditorRegions.SHEET_H) {
            return;
        }
        if (!canPaintPixelOnMiniSheet(px, py)) {
            return;
        }
        canvas.setRGB(px, py, paint ? selectedColor : 0x00000000);
        if (paint) {
            paintedThisStroke = true;
        }
    }

    private int paletteHit(int mouseX, int mouseY) {
        if (paletteColors == null) {
            return -1;
        }
        int rowH = PALETTE_SWATCH + PALETTE_GAP;
        int maxRows = Math.max(1, (miniSheetY - 10 - paletteY) / rowH);
        int cols = paletteColumnsPerSide();
        int half = (paletteColors.length + 1) / 2;
        int hit = paletteSideHit(mouseX, mouseY, 0, half, paletteLeftX, paletteScrollRow, maxRows, cols);
        if (hit >= 0) {
            return hit;
        }
        return paletteSideHit(mouseX, mouseY, half, paletteColors.length, paletteRightX,
                paletteScrollRow, maxRows, cols);
    }

    private int paletteSideHit(int mouseX, int mouseY, int from, int to, int baseX, int scrollRow,
                               int maxRows, int cols) {
        int rowH = PALETTE_SWATCH + PALETTE_GAP;
        int start = scrollRow * cols;
        int end = Math.min(to - from, start + maxRows * cols);
        for (int rel = start; rel < end; rel++) {
            int col = rel % cols;
            int row = rel / cols - scrollRow;
            int x = baseX + col * rowH;
            int y = paletteY + row * rowH;
            if (mouseX >= x && mouseX < x + PALETTE_SWATCH && mouseY >= y && mouseY < y + PALETTE_SWATCH) {
                return from + rel;
            }
        }
        return -1;
    }

    private void openImportFileDialog() {
        final File startDir;
        try {
            startDir = this.mc != null && this.mc.mcDataDir != null ? this.mc.mcDataDir : null;
        } catch (Throwable ignored) {
            return;
        }
        if (this.mc == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File pickedFile = GomodFileDialogs.pickOpenPngFile("Import cape PNG", startDir);
                    if (pickedFile == null) {
                        return;
                    }
                    final BufferedImage img = ImageIO.read(pickedFile);
                    if (img == null) {
                        return;
                    }
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            pushUndo();
                            optifineLayout = optifineLayout || looksLikeOptifineCanvas(img, pickedFile.getName());
                            canvas = PlayerCustomCapes.normalizeCapeImage(img);
                            layoutPanels();
                            positionDynamicWidgets();
                            refreshPreviewPlayer();
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }, "gomod-cape-import").start();
    }

    private boolean tryPressColorAdjustmentSlider(int mouseX, int mouseY) {
        CapeColorAdjustSlider[] sliders = {
                this.hueAdjustSlider, this.saturationAdjustSlider, this.brightnessAdjustSlider
        };
        for (CapeColorAdjustSlider slider : sliders) {
            if (slider != null && slider.mousePressed(this.mc, mouseX, mouseY)) {
                beginColorAdjustmentSession();
                this.selectedSlider = slider;
                applyColorAdjustmentsFromBaseline();
                return true;
            }
        }
        return false;
    }

    private void beginColorAdjustmentSession() {
        if (this.colorAdjustmentBaseline != null) {
            return;
        }
        int[] baseline = copyCanvasPixels();
        pushUndoSnapshot(baseline);
        this.colorAdjustmentBaseline = baseline;
    }

    /** Rebuild from the session baseline so moving a slider back restores colors without cumulative rounding. */
    private void applyColorAdjustmentsFromBaseline() {
        if (this.colorAdjustmentBaseline == null || this.canvas == null) {
            return;
        }
        float hueShift = this.hueAdjustSlider == null ? 0.0F : this.hueAdjustSlider.getValue() / 360.0F;
        float saturationShift = this.saturationAdjustSlider == null
                ? 0.0F : this.saturationAdjustSlider.getValue() / 100.0F;
        float brightnessShift = this.brightnessAdjustSlider == null
                ? 0.0F : this.brightnessAdjustSlider.getValue() / 100.0F;
        float[] hsb = new float[3];
        for (int i = 0; i < this.colorAdjustmentBaseline.length; i++) {
            int argb = this.colorAdjustmentBaseline[i];
            int alpha = (argb >>> 24) & 0xFF;
            if (alpha < 8) {
                this.canvas.setRGB(i % CAPE_W, i / CAPE_W, argb);
                continue;
            }
            int red = (argb >>> 16) & 0xFF;
            int green = (argb >>> 8) & 0xFF;
            int blue = argb & 0xFF;
            Color.RGBtoHSB(red, green, blue, hsb);
            float hue = hsb[0] + hueShift;
            hue = hue - (float) Math.floor(hue);
            float saturation = clamp01(hsb[1] + saturationShift);
            float brightness = clamp01(hsb[2] + brightnessShift);
            int rgb = Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
            this.canvas.setRGB(i % CAPE_W, i / CAPE_W, (alpha << 24) | rgb);
        }
        refreshPreviewPlayer();
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /** Keep the previewed pixels, then make the next adjustment group its own undo step. */
    private void finishColorAdjustmentSession() {
        this.colorAdjustmentBaseline = null;
        resetColorAdjustmentSliders();
        positionColorAdjustmentWidgets();
    }

    /** Revert only the uncommitted adjustment group and discard its pending undo snapshot. */
    private void resetColorAdjustmentSession() {
        if (this.colorAdjustmentBaseline == null) {
            return;
        }
        restoreCanvasPixels(this.colorAdjustmentBaseline);
        if (!this.undoStack.isEmpty()) {
            this.undoStack.pop();
        }
        this.colorAdjustmentBaseline = null;
        resetColorAdjustmentSliders();
        positionColorAdjustmentWidgets();
        refreshPreviewPlayer();
    }

    private void resetColorAdjustmentSliders() {
        if (this.hueAdjustSlider != null) {
            this.hueAdjustSlider.setValue(0.0F);
        }
        if (this.saturationAdjustSlider != null) {
            this.saturationAdjustSlider.setValue(0.0F);
        }
        if (this.brightnessAdjustSlider != null) {
            this.brightnessAdjustSlider.setValue(0.0F);
        }
    }

    private void pushUndoOncePerStroke() {
        if (!strokeUndoPushed) {
            pushUndo();
            strokeUndoPushed = true;
        }
    }

    private void pushUndo() {
        finishColorAdjustmentSession();
        pushUndoSnapshot(copyCanvasPixels());
    }

    private void pushUndoSnapshot(int[] snapshot) {
        if (undoStack.size() >= MAX_UNDO) {
            undoStack.pollLast();
        }
        undoStack.push(snapshot);
        redoStack.clear();
    }

    private void undo() {
        finishColorAdjustmentSession();
        if (undoStack.isEmpty()) {
            return;
        }
        if (redoStack.size() >= MAX_UNDO) {
            redoStack.pollLast();
        }
        redoStack.push(copyCanvasPixels());
        restoreCanvasPixels(undoStack.pop());
        refreshPreviewPlayer();
    }

    private void redo() {
        finishColorAdjustmentSession();
        if (redoStack.isEmpty()) {
            return;
        }
        if (undoStack.size() >= MAX_UNDO) {
            undoStack.pollLast();
        }
        undoStack.push(copyCanvasPixels());
        restoreCanvasPixels(redoStack.pop());
        refreshPreviewPlayer();
    }

    private int[] copyCanvasPixels() {
        int[] out = new int[CAPE_W * CAPE_H];
        for (int y = 0; y < CAPE_H; y++) {
            for (int x = 0; x < CAPE_W; x++) {
                out[y * CAPE_W + x] = canvas.getRGB(x, y);
            }
        }
        return out;
    }

    private void restoreCanvasPixels(int[] pixels) {
        if (pixels == null) {
            return;
        }
        for (int y = 0; y < CAPE_H; y++) {
            for (int x = 0; x < CAPE_W; x++) {
                canvas.setRGB(x, y, pixels[y * CAPE_W + x]);
            }
        }
    }

    private void saveDesign() {
        String label = this.nameField == null ? "Custom cape" : this.nameField.getText().trim();
        if (label.isEmpty()) {
            return;
        }
        boolean ok;
        String savedId = null;
        if (editId != null) {
            ok = PlayerCustomCapes.updateExisting(editId, label, this.canvas, this.optifineLayout);
            savedId = ok ? editId : null;
        } else if (loadSourceId != null && renameBaselineCanvas != null
                && canvasesEqual(renameBaselineCanvas, this.canvas)) {
            ok = PlayerCustomCapes.updateExisting(loadSourceId, label, this.canvas, this.optifineLayout);
            savedId = ok ? loadSourceId : null;
        } else {
            savedId = PlayerCustomCapes.saveNew(label, this.canvas, this.optifineLayout);
            ok = savedId != null;
        }
        if (ok) {
            PlayerStandaloneCape.invalidateCatalog();
            if (parent instanceof MinecraftCapeSelectorGUI) {
                ((MinecraftCapeSelectorGUI) parent).focusCapeAfterEditorSave(savedId);
            }
            this.mc.displayGuiScreen(parent);
        }
    }

    private static boolean canvasesEqual(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) {
            return false;
        }
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        if (w != PlayerCustomCapes.capeWidth() || h != PlayerCustomCapes.capeHeight()) {
            return false;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int argbToGuiColor(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (a < 8) {
            return 0x00000000;
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int dimColor(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >>> 16) & 0xFF) * factor);
        int g = (int) (((argb >>> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                out.setRGB(x, y, src.getRGB(x, y));
            }
        }
        return out;
    }

    /** Compact centered adjustment slider; zero is neutral and the label shows the signed delta. */
    private static final class CapeColorAdjustSlider extends GuiButton {
        private final String label;
        private final float minValue;
        private final float maxValue;
        private final boolean degrees;
        private float value;
        private float sliderValue;
        private boolean dragging;

        CapeColorAdjustSlider(int id, int x, int y, int width, String label,
                              float minValue, float maxValue, boolean degrees) {
            super(id, x, y, width, COLOR_ADJUST_SLIDER_H, "");
            this.label = label;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.degrees = degrees;
            setValue(0.0F);
        }

        void setValue(float value) {
            this.value = Math.max(this.minValue, Math.min(this.maxValue, value));
            this.sliderValue = (this.value - this.minValue) / (this.maxValue - this.minValue);
            updateLabel();
        }

        float getValue() {
            return this.value;
        }

        private void updateLabel() {
            int rounded = Math.round(this.value);
            String sign = rounded > 0 ? "+" : "";
            this.displayString = this.label + ": " + sign + rounded + (this.degrees ? "°" : "%");
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) {
                return;
            }
            mc.getTextureManager().bindTexture(buttonTextures);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int half = this.width / 2;
            this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, 46, half, 20);
            this.drawTexturedModalRect(this.xPosition + half, this.yPosition,
                    200 - half, 46, half, 20);
            int knobX = this.xPosition + (int) (this.sliderValue * Math.max(1, this.width - 8));
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);

            boolean hovered = this.enabled && mouseX >= this.xPosition && mouseY >= this.yPosition
                    && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
            int textColor = !this.enabled ? 0x808080 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
            int textX = this.xPosition + (this.width - mc.fontRendererObj.getStringWidth(this.displayString)) / 2;
            mc.fontRendererObj.drawString(this.displayString, textX, this.yPosition + 6, textColor);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!this.enabled || !this.visible
                    || mouseX < this.xPosition || mouseX > this.xPosition + this.width
                    || mouseY < this.yPosition || mouseY > this.yPosition + this.height) {
                return false;
            }
            this.dragging = true;
            applyMouseX(mouseX);
            return true;
        }

        @Override
        public void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
            if (this.dragging) {
                applyMouseX(mouseX);
            }
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            this.dragging = false;
        }

        private void applyMouseX(int mouseX) {
            int innerLeft = this.xPosition + 4;
            int innerWidth = Math.max(1, this.width - 8);
            int clamped = Math.max(innerLeft, Math.min(innerLeft + innerWidth, mouseX));
            this.sliderValue = (float) (clamped - innerLeft) / (float) innerWidth;
            this.value = this.minValue + this.sliderValue * (this.maxValue - this.minValue);
            updateLabel();
        }
    }

    private static void drawBorder(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + 1, color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, color);
        Gui.drawRect(x, y, x + 1, y + h, color);
        Gui.drawRect(x + w - 1, y, x + w, y + h, color);
    }
}
