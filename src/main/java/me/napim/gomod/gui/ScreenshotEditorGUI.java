package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.util.ScreenshotImageTextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;

/**
 * Full-screen screenshot editor. The canvas BufferedImage shares the same int[] backing array as
 * the DynamicTexture so drawing writes directly into GPU-upload memory — no per-frame pixel copy.
 */
public class ScreenshotEditorGUI extends GuiScreen {

    // ── Tool IDs ──────────────────────────────────────────────────────────
    private static final int TOOL_PEN    = 0;
    private static final int TOOL_ERASER = 1;
    private static final int TOOL_LINE   = 2;
    private static final int TOOL_RECT   = 3;

    // ── Eight preset swatches (3×3 grid, top-right = HSV picker) ───────────
    private static final int[] PALETTE = {
        0xFFFF4444, 0xFFFF8800,
        0xFFFFFF00, 0xFF44FF44, 0xFF00FFFF,
        0xFF4488FF, 0xFF000000, 0xFFFFFFFF,
    };
    private static final int SWATCH_COLS = 3;
    private static final int SWATCH_ROWS = 3;
    /** Grid index (row-major) of the {@link HsvColorPickerWidget} — top-right. */
    private static final int PICKER_GRID_INDEX = 2;

    // ── Layout ────────────────────────────────────────────────────────────
    private static final int PANEL_W     = 68;
    private static final int BOTTOM_H    = 28;
    private static final int TOOL_BTN_H  = 14;
    private static final int SWATCH_SIZE = 14;
    private static final int SWATCH_GAP  = 3;

    // ── Button IDs ────────────────────────────────────────────────────────
    private static final int BTN_UNDO  = 50;
    private static final int BTN_REDO  = 55;
    private static final int BTN_CLEAR = 51;
    private static final int BTN_SEND  = 52;
    private static final int BTN_SAVE  = 53;
    private static final int BTN_BACK  = 54;

    // ── Image / canvas state ──────────────────────────────────────────────
    private final GuiScreen parent;

    /** Pixel data shared between canvasImage and dynamicTexture — drawing writes here directly. */
    private int[] texData;
    /** BufferedImage whose raster is backed by texData (zero-copy GPU sync). */
    private BufferedImage canvasImage;
    /** Long-lived Graphics2D over canvasImage. */
    private Graphics2D g2d;
    /** Original screenshot pixels, used for Eraser and Clear. */
    private int[] originalData;

    private DynamicTexture   dynamicTexture;
    private ResourceLocation textureLocation;

    private int imgW = 1;
    private int imgH = 1;

    // ── Tool state ────────────────────────────────────────────────────────
    private int currentTool  = TOOL_PEN;
    /** Active pen color (ARGB); updated by swatches or {@link #colorPicker}. */
    private int activeColorArgb = PALETTE[0];
    /** Selected preset index, or {@code -1} when only the custom picker color is active. */
    private int selectedPaletteIndex = 0;
    private int penSize      = 4;
    private HsvColorPickerWidget colorPicker;

    private boolean isDrawing = false;
    private int lastImgX = -1, lastImgY = -1;
    private int startImgX, startImgY;

    /**
     * For line/rect live preview: points at the undo snapshot pushed at stroke start.
     * System.arraycopy restores this every mouse-move without any extra allocation.
     */
    private int[] preStrokeData;

    // ── Undo / redo stacks ────────────────────────────────────────────────
    private static final int MAX_HISTORY = 20;
    private final ArrayDeque<int[]> undoStack = new ArrayDeque<>();
    private final ArrayDeque<int[]> redoStack = new ArrayDeque<>();

    // ── Screen draw area ──────────────────────────────────────────────────
    private int drawX, drawY, drawW, drawH;

    // ─────────────────────────────────────────────────────────────────────

    public ScreenshotEditorGUI(GuiScreen parent) {
        this.parent = parent;
    }

    // ── Init ──────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        buttonList.clear();
        super.initGui();

        if (originalData == null) {
            loadLatestScreenshot();
        } else {
            computeDrawArea();
            rebuildSharedCanvas(); // re-upload on resize
        }

        // Bottom-left: Undo | Redo | Clear
        int bx = PANEL_W + 4;
        int by = height - BOTTOM_H + 4;
        buttonList.add(new GuiButton(BTN_UNDO,  bx,       by, 54, 20, EnumChatFormatting.YELLOW + "Undo"));
        buttonList.add(new GuiButton(BTN_REDO,  bx + 58,  by, 54, 20, EnumChatFormatting.YELLOW + "Redo"));
        buttonList.add(new GuiButton(BTN_CLEAR, bx + 116, by, 54, 20, EnumChatFormatting.RED    + "Clear"));

        // Bottom-right: Send | Save | Back
        int rx = width - 4;
        buttonList.add(new GuiButton(BTN_BACK, rx - 64,  by, 64,  20, EnumChatFormatting.GRAY  + "Back"));
        buttonList.add(new GuiButton(BTN_SAVE, rx - 134, by, 66,  20, EnumChatFormatting.GREEN + "Save"));
        buttonList.add(new GuiButton(BTN_SEND, rx - 240, by, 102, 20, EnumChatFormatting.AQUA  + "Send to Friend"));

        initColorPicker();
    }

    private void initColorPicker() {
        HsvColorPickerWidget.resetIconCache();
        if (colorPicker == null) {
            colorPicker = new HsvColorPickerWidget();
            colorPicker.setExpandToRight(true);
            colorPicker.setListener(new HsvColorPickerWidget.Listener() {
                @Override
                public void onColorChanged(int rgb) {
                    applyCustomColor(rgb);
                }

                @Override
                public void onColorChangeComplete(int rgb) {
                    applyCustomColor(rgb);
                }
            });
        }
        colorPicker.setExpandToRight(true);
        colorPicker.setVisible(true);
        colorPicker.setEnabled(true);
        colorPicker.syncFromRgb(activeColorArgb & 0x00FFFFFF);
        layoutColorPicker();
    }

    private void layoutColorPicker() {
        if (colorPicker == null) {
            return;
        }
        int col = PICKER_GRID_INDEX % SWATCH_COLS;
        int row = PICKER_GRID_INDEX / SWATCH_COLS;
        int sx = swatchGridX(col);
        int sy = swatchGridY(row);
        int pickerSize = colorPicker.getButtonWidth();
        colorPicker.setPosition(
                sx + (SWATCH_SIZE - pickerSize) / 2,
                sy + (SWATCH_SIZE - pickerSize) / 2);
    }

    private void applyCustomColor(int rgb) {
        activeColorArgb = 0xFF000000 | (rgb & 0xFFFFFF);
        selectedPaletteIndex = -1;
    }

    private void selectPaletteColor(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= PALETTE.length) {
            return;
        }
        selectedPaletteIndex = paletteIndex;
        activeColorArgb = PALETTE[paletteIndex];
        if (colorPicker != null) {
            colorPicker.syncFromRgb(activeColorArgb & 0x00FFFFFF);
        }
    }

    // ── Image loading ─────────────────────────────────────────────────────

    private void loadLatestScreenshot() {
        try {
            File f = findLatestScreenshot();
            if (f == null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No screenshot found to edit.");
                return;
            }
            BufferedImage loaded = ImageIO.read(f);
            if (loaded == null) return;
            BufferedImage argb = ScreenshotImageTextureUtil.toArgbCopy(loaded);
            imgW = argb.getWidth();
            imgH = argb.getHeight();
            originalData = new int[imgW * imgH];
            argb.getRGB(0, 0, imgW, imgH, originalData, 0, imgW);
            computeDrawArea();
            buildSharedCanvas(argb);
        } catch (Exception e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Failed to load screenshot: " + e.getMessage());
        }
    }

    /**
     * Creates the DynamicTexture from the source image, then wraps its internal int[] with a
     * BufferedImage so any Graphics2D call writes directly into the GPU-upload buffer.
     * After this call, calling {@code dynamicTexture.updateDynamicTexture()} is sufficient
     * to push new pixel data to the GPU — no separate copy step needed.
     */
    private void buildSharedCanvas(BufferedImage sourceArgb) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || sourceArgb == null) return;

        if (textureLocation != null && mc.getTextureManager() != null) {
            mc.getTextureManager().deleteTexture(textureLocation);
        }
        if (g2d != null) { g2d.dispose(); g2d = null; }

        // 1. Create DynamicTexture — its internal int[] is populated from sourceArgb
        dynamicTexture = new DynamicTexture(sourceArgb);
        texData = dynamicTexture.getTextureData(); // shared backing array

        // 2. Wrap texData in a BufferedImage — draws go directly to texData (zero copy)
        DataBufferInt db = new DataBufferInt(texData, texData.length);
        int[] masks = {0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000}; // ARGB
        WritableRaster raster = Raster.createPackedRaster(db, imgW, imgH, imgW, masks, null);
        DirectColorModel cm = new DirectColorModel(32, 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000);
        canvasImage = new BufferedImage(cm, raster, false, null);
        g2d = makeG2D(canvasImage);

        // 3. Register the texture with the engine
        textureLocation = new ResourceLocation("gomod123",
                "gs_editor/" + Long.toHexString(System.nanoTime()));
        mc.getTextureManager().loadTexture(textureLocation, dynamicTexture);

        // 4. Override GL_NEAREST (DynamicTexture default) with GL_LINEAR so strokes
        //    render smoothly regardless of display scale.
        mc.getTextureManager().bindTexture(textureLocation);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    /** Called on window resize — re-registers the texture, canvas still backed by same data. */
    private void rebuildSharedCanvas() {
        if (originalData == null || imgW < 1 || imgH < 1) return;
        BufferedImage tmp = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        if (texData != null) {
            tmp.setRGB(0, 0, imgW, imgH, texData, 0, imgW);
        } else {
            tmp.setRGB(0, 0, imgW, imgH, originalData, 0, imgW);
        }
        buildSharedCanvas(tmp);
    }

    private void computeDrawArea() {
        int availW = width  - PANEL_W - 8;
        int availH = height - BOTTOM_H - 8;
        double aspect = (double) imgH / Math.max(1, imgW);
        drawW = availW;
        drawH = (int)(drawW * aspect);
        if (drawH > availH) {
            drawH = availH;
            drawW = (int)(drawH / aspect);
        }
        drawW = Math.max(1, drawW);
        drawH = Math.max(1, drawH);
        drawX = PANEL_W + 4 + (availW - drawW) / 2;
        drawY = 4        + (availH - drawH) / 2;
    }

    // ── Graphics helpers ──────────────────────────────────────────────────

    private static Graphics2D makeG2D(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private void setupStroke() {
        g2d.setColor(new Color(activeColorArgb, true));
        // Scale pen size from screen pixels to image pixels
        float sw = Math.max(1.0f, (float) penSize * imgW / Math.max(1, drawW));
        g2d.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    // ── Coordinate conversion ─────────────────────────────────────────────

    private int toImgX(int sx) { return (int)((double)(sx - drawX) * imgW / drawW); }
    private int toImgY(int sy) { return (int)((double)(sy - drawY) * imgH / drawH); }
    private int clampX(int x)  { return Math.max(0, Math.min(imgW - 1, x)); }
    private int clampY(int y)  { return Math.max(0, Math.min(imgH - 1, y)); }

    private boolean inCanvas(int mx, int my) {
        return mx >= drawX && mx < drawX + drawW && my >= drawY && my < drawY + drawH;
    }

    // ── Undo / Redo ───────────────────────────────────────────────────────

    private void pushUndo() {
        if (texData == null) return;
        if (undoStack.size() >= MAX_HISTORY) undoStack.pollLast();
        undoStack.push(texData.clone());
        redoStack.clear(); // new stroke invalidates redo history
    }

    private void undo() {
        if (undoStack.isEmpty() || texData == null) return;
        if (redoStack.size() >= MAX_HISTORY) redoStack.pollLast();
        redoStack.push(texData.clone());
        System.arraycopy(undoStack.pop(), 0, texData, 0, texData.length);
        dynamicTexture.updateDynamicTexture();
    }

    private void redo() {
        if (redoStack.isEmpty() || texData == null) return;
        if (undoStack.size() >= MAX_HISTORY) undoStack.pollLast();
        undoStack.push(texData.clone());
        System.arraycopy(redoStack.pop(), 0, texData, 0, texData.length);
        dynamicTexture.updateDynamicTexture();
    }

    // ── Mouse events ──────────────────────────────────────────────────────

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (colorPicker != null && colorPicker.mouseClicked(mc, mouseX, mouseY, button)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0 || texData == null) return;
        if (handleSidePanelClick(mouseX, mouseY)) return;
        if (!inCanvas(mouseX, mouseY)) return;

        int ix = clampX(toImgX(mouseX));
        int iy = clampY(toImgY(mouseY));

        pushUndo();
        isDrawing = true;
        lastImgX  = ix;
        lastImgY  = iy;
        startImgX = ix;
        startImgY = iy;

        if (currentTool == TOOL_LINE || currentTool == TOOL_RECT) {
            // Reuse the undo snapshot as the pre-stroke base — no extra copy
            preStrokeData = undoStack.peek();
        } else {
            applyBrush(ix, iy, ix, iy);
            dynamicTexture.updateDynamicTexture();
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        if (colorPicker != null) {
            colorPicker.mouseReleased(mouseX, mouseY);
        }
        super.mouseReleased(mouseX, mouseY, state);
        if (!isDrawing || texData == null) { isDrawing = false; return; }

        int ix = clampX(toImgX(mouseX));
        int iy = clampY(toImgY(mouseY));

        if ((currentTool == TOOL_LINE || currentTool == TOOL_RECT) && preStrokeData != null) {
            // Restore pre-stroke pixels, draw the final committed shape
            System.arraycopy(preStrokeData, 0, texData, 0, texData.length);
            setupStroke();
            drawShape(ix, iy);
            preStrokeData = null;
            dynamicTexture.updateDynamicTexture();
        }

        isDrawing = false;
        lastImgX  = -1;
        lastImgY  = -1;
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (colorPicker != null) {
            colorPicker.mouseClickMove(mouseX, mouseY);
        }
        // Pen/eraser are drawn per render-frame in drawScreen for maximum smoothness.
        // Only handle line/rect shape previews here.
        if (!isDrawing || texData == null || clickedMouseButton != 0) return;
        if ((currentTool == TOOL_LINE || currentTool == TOOL_RECT) && preStrokeData != null) {
            int ix = clampX(toImgX(mouseX));
            int iy = clampY(toImgY(mouseY));
            System.arraycopy(preStrokeData, 0, texData, 0, texData.length);
            setupStroke();
            drawShape(ix, iy);
            dynamicTexture.updateDynamicTexture();
        }
    }

    private void drawShape(int endX, int endY) {
        if (currentTool == TOOL_LINE) {
            g2d.drawLine(startImgX, startImgY, endX, endY);
        } else {
            int rx = Math.min(startImgX, endX);
            int ry = Math.min(startImgY, endY);
            g2d.drawRect(rx, ry, Math.abs(endX - startImgX), Math.abs(endY - startImgY));
        }
    }

    private void applyBrush(int x0, int y0, int x1, int y1) {
        if (currentTool == TOOL_ERASER) {
            eraseSegment(x0, y0, x1, y1);
        } else {
            setupStroke();
            g2d.drawLine(x0, y0, x1, y1);
        }
    }

    /** Restores original pixels in a circular brush along a segment — writes directly to texData. */
    private void eraseSegment(int x0, int y0, int x1, int y1) {
        if (originalData == null) return;
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)));
        int r = Math.max(2, penSize * imgW / Math.max(1, drawW) / 2);
        for (int s = 0; s <= steps; s++) {
            int cx = x0 + dx * s / steps;
            int cy = y0 + dy * s / steps;
            for (int ey = -r; ey <= r; ey++) {
                for (int ex = -r; ex <= r; ex++) {
                    if (ex * ex + ey * ey <= r * r) {
                        int px = cx + ex, py = cy + ey;
                        if (px >= 0 && px < imgW && py >= 0 && py < imgH) {
                            texData[py * imgW + px] = originalData[py * imgW + px];
                        }
                    }
                }
            }
        }
    }

    // ── Side panel interaction ─────────────────────────────────────────────

    private boolean handleSidePanelClick(int mx, int my) {
        if (mx < 0 || mx >= PANEL_W) return false;

        // Tool buttons
        int ty = getToolButtonsY();
        for (int i = 0; i < 4; i++) {
            if (my >= ty && my < ty + TOOL_BTN_H) { currentTool = i; return true; }
            ty += TOOL_BTN_H + 3;
        }

        // Color swatches (picker cell handled by {@link HsvColorPickerWidget})
        for (int grid = 0; grid < SWATCH_COLS * SWATCH_ROWS; grid++) {
            if (grid == PICKER_GRID_INDEX) {
                continue;
            }
            int paletteIndex = paletteIndexForGrid(grid);
            if (paletteIndex < 0 || paletteIndex >= PALETTE.length) {
                continue;
            }
            int col = grid % SWATCH_COLS;
            int row = grid / SWATCH_COLS;
            int sx = swatchGridX(col);
            int sy = swatchGridY(row);
            if (mx >= sx && mx < sx + SWATCH_SIZE && my >= sy && my < sy + SWATCH_SIZE) {
                selectPaletteColor(paletteIndex);
                return true;
            }
        }

        // Pen size [-] [+]  (buttons occupy psY+10 to psY+22)
        int psY = getPenSizeY();
        if (my >= psY + 10 && my < psY + 22) {
            int half = PANEL_W / 2;
            if (mx >= 4    && mx < half - 2) { penSize = Math.max(1,  penSize - 1); return true; }
            if (mx >= half + 2 && mx < PANEL_W - 4) { penSize = Math.min(30, penSize + 1); return true; }
        }

        return false;
    }

    // ── Button actions ────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case BTN_BACK:
                mc.displayGuiScreen(parent);
                break;
            case BTN_UNDO:
                undo();
                break;
            case BTN_REDO:
                redo();
                break;
            case BTN_CLEAR:
                if (originalData != null && texData != null) {
                    pushUndo();
                    System.arraycopy(originalData, 0, texData, 0, texData.length);
                    dynamicTexture.updateDynamicTexture();
                }
                break;
            case BTN_SAVE:
                saveEditedToDisk();
                break;
            case BTN_SEND:
                byte[] bytes = exportBytes();
                if (bytes != null) {
                    mc.displayGuiScreen(new ScreenshotShareGUI(this, bytes));
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char c, int keyCode) throws IOException {
        if (keyCode == 1) { mc.displayGuiScreen(parent); return; }
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
        // Optional extra: Ctrl+Y for redo (common on Windows)
        if (ctrl && keyCode == Keyboard.KEY_Y) { redo(); return; }
        super.keyTyped(c, keyCode);
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // ── Per-frame pen / eraser drawing ───────────────────────────────────
        // drawScreen runs at render frame rate (60+ fps), far smoother than
        // mouseClickMove which is dispatched at game-tick rate (~20 Hz).
        if (isDrawing && texData != null
                && (currentTool == TOOL_PEN || currentTool == TOOL_ERASER)) {
            int ix = clampX(toImgX(mouseX));
            int iy = clampY(toImgY(mouseY));
            if (ix != lastImgX || iy != lastImgY) {
                int fromX = lastImgX >= 0 ? lastImgX : ix;
                int fromY = lastImgY >= 0 ? lastImgY : iy;
                applyBrush(fromX, fromY, ix, iy);
                dynamicTexture.updateDynamicTexture();
                lastImgX = ix;
                lastImgY = iy;
            }
        }

        drawDefaultBackground();

        // Left sidebar
        drawRect(0, 0, PANEL_W, height - BOTTOM_H, 0xD3111122);
        drawRect(PANEL_W, 0, PANEL_W + 1, height - BOTTOM_H, 0xFF444466);

        // Tool buttons
        int ty = getToolButtonsY();
        drawCenteredString(fontRendererObj, EnumChatFormatting.YELLOW + "TOOLS", PANEL_W / 2, ty - 10, 0xFFFFFF);
        String[] toolLabels = {
            EnumChatFormatting.WHITE + "Pen",
            EnumChatFormatting.WHITE + "Eraser",
            EnumChatFormatting.WHITE + "Line",
            EnumChatFormatting.WHITE + "Rect"
        };
        for (int i = 0; i < toolLabels.length; i++) {
            boolean sel = currentTool == i;
            drawRect(2, ty, PANEL_W - 2, ty + TOOL_BTN_H, sel ? 0xFF3355BB : 0xFF222244);
            if (sel) drawRect(2, ty, 3, ty + TOOL_BTN_H, 0xFF88AAFF);
            drawString(fontRendererObj, toolLabels[i], 8, ty + 3, 0xFFFFFF);
            ty += TOOL_BTN_H + 3;
        }

        // Color swatches + HSV picker (top-right)
        int swatchY = getSwatchY();
        drawCenteredString(fontRendererObj, EnumChatFormatting.YELLOW + "COLOR", PANEL_W / 2, swatchY - 10, 0xFFFFFF);
        for (int grid = 0; grid < SWATCH_COLS * SWATCH_ROWS; grid++) {
            if (grid == PICKER_GRID_INDEX) {
                continue;
            }
            int paletteIndex = paletteIndexForGrid(grid);
            if (paletteIndex < 0 || paletteIndex >= PALETTE.length) {
                continue;
            }
            int col = grid % SWATCH_COLS;
            int row = grid / SWATCH_COLS;
            int sx = swatchGridX(col);
            int sy = swatchGridY(row);
            if (selectedPaletteIndex == paletteIndex) {
                drawRect(sx - 2, sy - 2, sx + SWATCH_SIZE + 2, sy + SWATCH_SIZE + 2, 0xFFFFFFFF);
            }
            drawRect(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, PALETTE[paletteIndex] | 0xFF000000);
        }
        // Pen size control — [-] left half, [+] right half
        int psY  = getPenSizeY();
        int half = PANEL_W / 2;
        drawCenteredString(fontRendererObj, EnumChatFormatting.YELLOW + "SIZE", half, psY, 0xFFFFFF);
        drawRect(4, psY + 10, half - 2, psY + 22, 0xFF333355);
        drawCenteredString(fontRendererObj, EnumChatFormatting.WHITE + "-", half / 2, psY + 12, 0xFFFFFF);
        drawRect(half + 2, psY + 10, PANEL_W - 4, psY + 22, 0xFF333355);
        drawCenteredString(fontRendererObj, EnumChatFormatting.WHITE + "+", half + (PANEL_W - half) / 2, psY + 12, 0xFFFFFF);
        // Numeric value
        drawCenteredString(fontRendererObj, EnumChatFormatting.WHITE + "" + penSize, half, psY + 26, 0xFFFFFF);

        // ── Brush shape preview ──────────────────────────────────────────────
        // Show an actual circle (pen) or square outline (eraser) so the user can
        // see how big the brush will be.  Diameter is capped to fit the sidebar.
        int maxD     = PANEL_W - 12;                          // max px that fits
        int previewD = Math.min(penSize, maxD);               // actual display diameter
        int pcx      = half;                                  // center X
        int pcy      = psY + 36 + previewD / 2 + 2;          // center Y (below number)
        int panelBot = height - BOTTOM_H - 4;                // sidebar bottom edge

        if (pcy + previewD / 2 <= panelBot) {               // only draw if it fits
            if (currentTool == TOOL_ERASER) {
                // Square outline in white
                int px0 = pcx - previewD / 2, py0 = pcy - previewD / 2;
                int px1 = pcx + previewD / 2, py1 = pcy + previewD / 2;
                drawRect(px0, py0, px1, py0 + 1, 0xFFFFFFFF);
                drawRect(px0, py1 - 1, px1, py1,  0xFFFFFFFF);
                drawRect(px0, py0, px0 + 1, py1,  0xFFFFFFFF);
                drawRect(px1 - 1, py0, px1, py1,  0xFFFFFFFF);
            } else {
                // Filled circle with the current pen color using horizontal scanlines
                int r   = previewD / 2;
                int col = activeColorArgb | 0xFF000000;
                for (int dy = -r; dy <= r; dy++) {
                    int dx = (int) Math.sqrt(Math.max(0.0, (double) r * r - (double) dy * dy));
                    if (dx > 0) {
                        drawRect(pcx - dx, pcy + dy, pcx + dx, pcy + dy + 1, col);
                    }
                }
            }
        }

        // Canvas + image — MUST reset GL color to white before binding texture.
        // Previous drawRect calls leave GL color tinted, which darkens the texture.
        if (textureLocation != null && texData != null) {
            drawRect(drawX - 1, drawY - 1, drawX + drawW + 1, drawY + drawH + 1, 0xFFAAAAAA);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            Minecraft.getMinecraft().getTextureManager().bindTexture(textureLocation);
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                    drawX, drawY, 0, 0, imgW, imgH, drawW, drawH, imgW, imgH);
        } else {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.RED + "No screenshot loaded", width / 2, height / 2, 0xFFFFFF);
        }

        // Cursor preview ring for pen / eraser.
        // The stroke is `penSize` screen-pixels wide (by setupStroke's scale formula),
        // so the radius = penSize/2 screen pixels.
        if (inCanvas(mouseX, mouseY) && (currentTool == TOOL_PEN || currentTool == TOOL_ERASER)) {
            int r = Math.max(1, penSize / 2);
            int previewColor = currentTool == TOOL_ERASER
                    ? 0x80FFFFFF
                    : (0x80 << 24) | (activeColorArgb & 0x00FFFFFF);
            drawRect(mouseX - r, mouseY - r, mouseX + r, mouseY + r, previewColor);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (colorPicker != null) {
            layoutColorPicker();
            colorPicker.drawButton(mc, mouseX, mouseY);
            colorPicker.drawExpanded(mc, mouseX, mouseY, partialTicks);
            if (colorPicker.isOpenOrAnimating()) {
                colorPicker.drawButton(mc, mouseX, mouseY);
            }
        }
    }

    // ── Panel layout helpers ───────────────────────────────────────────────

    private int getToolButtonsY() { return 18; }

    private int getSwatchY() {
        return getToolButtonsY() + 4 * (TOOL_BTN_H + 3) + 12;
    }

    private int getPenSizeY() {
        return getSwatchY() + SWATCH_ROWS * (SWATCH_SIZE + SWATCH_GAP) + 8;
    }

    private int swatchGridX(int col) {
        return 6 + col * (SWATCH_SIZE + SWATCH_GAP);
    }

    private int swatchGridY(int row) {
        return getSwatchY() + row * (SWATCH_SIZE + SWATCH_GAP);
    }

    /** Maps a 3×3 grid cell (excluding {@link #PICKER_GRID_INDEX}) to {@link #PALETTE} index. */
    private static int paletteIndexForGrid(int gridIndex) {
        if (gridIndex == PICKER_GRID_INDEX) {
            return -1;
        }
        return gridIndex < PICKER_GRID_INDEX ? gridIndex : gridIndex - 1;
    }

    // ── Export / save ─────────────────────────────────────────────────────

    private byte[] exportBytes() {
        if (canvasImage == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No image to export.");
            return null;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(canvasImage, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Export failed: " + e.getMessage());
            return null;
        }
    }

    private void saveEditedToDisk() {
        byte[] bytes = exportBytes();
        if (bytes == null) return;
        try {
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "screenshots");
            if (!dir.exists() && !dir.mkdirs()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Could not create screenshots folder.");
                return;
            }
            String ts   = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
            String name = "gomodEdit_" + ts + ".png";
            // Avoid collisions if two saves happen in the same second
            File out = new File(dir, name);
            int  i   = 1;
            while (out.exists()) {
                out = new File(dir, "gomodEdit_" + ts + "_" + i + ".png");
                i++;
            }
            Files.write(out.toPath(), bytes);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Saved as " + EnumChatFormatting.AQUA + out.getName());
        } catch (Exception e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Save failed: " + e.getMessage());
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static File findLatestScreenshot() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.mcDataDir == null) return null;
        File dir = new File(mc.mcDataDir, "screenshots");
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        long bestTs = Long.MIN_VALUE;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"))) continue;
            long ts = f.lastModified();
            if (best == null || ts > bestTs) { best = f; bestTs = ts; }
        }
        return best;
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    @Override
    public void onGuiClosed() {
        if (colorPicker != null) {
            colorPicker.collapse();
        }
        if (g2d != null) { g2d.dispose(); g2d = null; }
        Minecraft mc = Minecraft.getMinecraft();
        if (textureLocation != null && mc != null && mc.getTextureManager() != null) {
            mc.getTextureManager().deleteTexture(textureLocation);
            textureLocation = null;
            dynamicTexture  = null;
        }
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
