package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import me.napim.gomod.features.RadialMenuItem;
import me.napim.gomod.features.RadialMenuWheel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wheel modules: enable, keybind, and visual slice editor (merged settings + slots).
 */
public class RadialMenuWheelSlotsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int BTN_H = 20;
    private static final int BTN_W = 160;
    private static final int ID_BACK = 0;
    private static final int ID_TOGGLE = 31;
    private static final int ID_KEYBIND = 32;
    private static final int TOP_CTRL_W = 200;

    /** Preview wheel matches in-game proportions (scaled down). */
    private static final float EDITOR_INNER_R = 30f;
    private static final float EDITOR_OUTER_R = 78f;
    /** Ignore cursor outside this radius so palette movement does not fake-hover a slice by angle. */
    private static final float EDITOR_PICK_MAX_R = EDITOR_OUTER_R + 24f;
    /** Furthest slice edge when a slice is hovered/selected (matches {@link RadialMenuWheel} bulge). */
    private static final int EDITOR_WHEEL_VISUAL_OUTER = (int) (EDITOR_OUTER_R + RadialMenuWheel.HOVER_LIFT);
    /** Gap between wheel rim and slice caption text (above / below). */
    private static final int SLICE_CAPTION_WHEEL_GAP = 6;
    /** Gap between Key button bottom and the top of the preview wheel. */
    private static final int CONTROLS_TO_WHEEL_GAP = 16;
    /** Extra lift for Wheel modules / Key above the reserved caption band. */
    private static final int CONTROLS_EXTRA_LIFT_PX = 8;
    /** Gap between hover caption (below wheel) and the "Modules" label. */
    private static final int HOVER_CAPTION_TO_MODULES_GAP = 14;
    /**
     * Pixels to move the wheel down from its highest allowed spot (below Key, above Modules).
     * Capped by free space — if changing this has no effect, slack is 0 (screen too short).
     */
    private static final int WHEEL_DOWN_NUDGE_PX = 20;

    private static final int PALETTE_CELL = 22;
    private static final int PALETTE_GAP = 5;
    /** Rows of module icons before the separate {@code Clear slot} row. */
    private static final int PALETTE_MAIN_ROWS = 2;
    /** Extra space between main module rows and the clear-slot row. */
    private static final int CLEAR_ROW_GAP_PX = 10;

    /** Unused palette cell (dark). */
    private static final int PALETTE_BG_DIM = 0x44000000;
    private static final int PALETTE_BG_DIM_HOT = 0x66000000;
    /** Module already assigned on the wheel (white tint). */
    private static final int PALETTE_BG_USED = 0x66FFFFFF;
    private static final int PALETTE_BG_USED_HOT = 0x88FFFFFF;

    private final GuiScreen parent;
    private boolean capturingKeybind = false;
    private GuiButton toggleButton;
    private GuiButton keybindButton;

    private int wheelCx;
    private int wheelCy;
    private int topToggleY;
    /** Slice index for quick-assign from the palette, or {@code -1}. */
    private int selectedSlice = -1;

    private final List<PaletteEntry> paletteEntries = new ArrayList<PaletteEntry>();
    private int paletteTop;
    private int baseTopToggleY;
    private int baseWheelCy;
    private int basePaletteTop;
    private int paletteBlockH;
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    private GuiButton backButton;

    /** {@code >= 0} while left button is held after pressing a palette cell (click-assign or drag-to-slice). */
    private int dragPaletteIndex = -1;

    /** Reused to avoid per-frame allocations in {@link #paletteCellXY}. */
    private final int[] paletteScratchXY = new int[2];
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();
    private final RadialMenuWheel.VisualState wheelVisual = new RadialMenuWheel.VisualState();

    public RadialMenuWheelSlotsGUI(GuiScreen parent) {
        this(parent, -1);
    }

    /** Opens with the requested wheel slice selected, for direct empty-slot assignment. */
    public RadialMenuWheelSlotsGUI(GuiScreen parent, int initialSelectedSlice) {
        this.parent = parent;
        this.selectedSlice = initialSelectedSlice >= 0 && initialSelectedSlice < RadialMenuWheel.SLOT_COUNT
                ? initialSelectedSlice : -1;
    }

    @Override
    public void initGui() {
        super.initGui();
        capturingKeybind = false;
        buildPaletteEntries();
        ScaledResolution sr = new ScaledResolution(mc);
        wheelCx = sr.getScaledWidth() / 2;

        paletteBlockH = PALETTE_MAIN_ROWS * (PALETTE_CELL + PALETTE_GAP) - PALETTE_GAP
                + CLEAR_ROW_GAP_PX + PALETTE_CELL;
        int fontH = fontRendererObj.FONT_HEIGHT;

        // Bottom block is fixed (Modules label + palette + Back) — never derived from wheel position.
        paletteTop = sr.getScaledHeight() - 30 - paletteBlockH - BTN_H - 24;
        basePaletteTop = paletteTop;
        int modulesLabelY = paletteTop - 12;

        // Top controls are fixed under the title — never move when the wheel shifts.
        int minTopForControls = GuiHeader.STANDARD_TITLE_Y + fontH + 14;
        topToggleY = minTopForControls + CONTROLS_EXTRA_LIFT_PX;
        int keybindBottom = topToggleY + 2 * BTN_H + 4;

        // Only the wheel (+ hover caption drawn with it) moves: stay below Key, above Modules.
        // GUI Y grows downward: wheelCyTop = highest on screen (smallest Y), wheelCyBottom = lowest (largest Y).
        int bulgedRim = Math.round(EDITOR_OUTER_R + RadialMenuWheel.HOVER_LIFT);
        int wheelCyTop = modulesLabelY - HOVER_CAPTION_TO_MODULES_GAP - fontH - SLICE_CAPTION_WHEEL_GAP - bulgedRim;
        int wheelCyBottom = keybindBottom + CONTROLS_TO_WHEEL_GAP + EDITOR_WHEEL_VISUAL_OUTER;
        int slack = Math.max(0, wheelCyBottom - wheelCyTop);
        wheelCy = wheelCyTop + Math.min(WHEEL_DOWN_NUDGE_PX, slack);
        baseTopToggleY = topToggleY;
        baseWheelCy = wheelCy;

        toggleButton = new GuiButton(ID_TOGGLE, wheelCx - TOP_CTRL_W / 2, topToggleY, TOP_CTRL_W, BTN_H, getToggleText());
        keybindButton = new GuiButton(ID_KEYBIND, wheelCx - TOP_CTRL_W / 2, topToggleY + BTN_H + 4, TOP_CTRL_W, BTN_H,
                getKeybindText());

        int backY = GuiHeader.backButtonY(sr.getScaledHeight(), BTN_H);
        backButton = new GuiButton(ID_BACK, wheelCx - BTN_W / 2, backY, BTN_W, BTN_H,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(keybindButton);
        buttonList.add(backButton);
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return RadialMenuWheel.isEnabled();
            }
        });
    }

    private void buildPaletteEntries() {
        paletteEntries.clear();
        for (String[] kv : RadialMenuItem.TOGGLE_FEATURE_OPTIONS) {
            String key = kv[0];
            if (GomodInternalFeatures.isRestrictedToggleFeatureKey(key)) {
                continue;
            }
            String name = kv[1];
            int col = RadialMenuWheel.suggestedColorForToggleKey(key, 0);
            RadialMenuItem t = new RadialMenuItem(name, col, true, RadialMenuItem.ActionType.TOGGLE_FEATURE, key);
            paletteEntries.add(new PaletteEntry(t, name));
        }
        paletteEntries.add(new PaletteEntry(
                new RadialMenuItem("/fonline", 0x4488FF, true, RadialMenuItem.ActionType.COMMAND, "fonline"),
                "/fonline"));
        paletteEntries.add(new PaletteEntry(
                new RadialMenuItem("(empty)", 0x888888, false, RadialMenuItem.ActionType.NONE, ""),
                "Clear slot"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        scroll.tick();
        int scrollOff = scroll.getOffset();
        topToggleY = baseTopToggleY - scrollOff;
        wheelCy = baseWheelCy - scrollOff;
        paletteTop = basePaletteTop - scrollOff;
        if (toggleButton != null) {
            toggleButton.yPosition = topToggleY;
        }
        if (keybindButton != null) {
            keybindButton.yPosition = topToggleY + BTN_H + 4;
        }
        int visibleTop = GuiHeader.STANDARD_TITLE_Y + fontRendererObj.FONT_HEIGHT + 14;
        int visibleBottom = GuiHeader.backButtonY(height, BTN_H) - 8;
        scroll.updateBounds(baseTopToggleY, basePaletteTop + paletteBlockH + 12, visibleTop, visibleBottom);

        int cxTitle = width / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Wheel modules",
                cxTitle, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        if (capturingKeybind) {
            keybindButton.displayString = EnumChatFormatting.YELLOW + "Press a key…";
        }

        int wheelHover = RadialMenuWheel.computeHoveredAt(mouseX, mouseY, wheelCx, wheelCy, EDITOR_INNER_R,
                EDITOR_OUTER_R, EDITOR_PICK_MAX_R);
        RadialMenuWheel.renderWheelEditorPreview(mouseX, mouseY, wheelCx, wheelCy, wheelHover, 0L,
                EDITOR_INNER_R, EDITOR_OUTER_R, selectedSlice, wheelVisual);

        drawPalette(mouseX, mouseY);
        resetGuiGlStateForStringDraw();
        scroll.drawScrollbar(width, mouseY);

        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, ID_BACK);
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        enabledOverlays.drawAllFaded(scroll.getVisibleTop(), scroll.getVisibleBottom(),
                scroll.getOffset(), scroll.getMaxScroll());
        GomodSearchHighlight.draw(buttonList, RadialMenuWheelSlotsGUI.class);

        int palHover = paletteIndexAt(mouseX, mouseY);
        if (dragPaletteIndex < 0 && palHover >= 0) {
            String tip = paletteEntries.get(palHover).tooltipTitle;
            if (tip != null && !tip.isEmpty()) {
                boolean clearTooltip = palHover == paletteEntries.size() - 1;
                String line = clearTooltip
                        ? EnumChatFormatting.RED + tip
                        : EnumChatFormatting.AQUA + tip;
                drawHoveringText(Collections.singletonList(line), mouseX, mouseY);
            }
        }

        if (dragPaletteIndex >= 0 && Mouse.isButtonDown(0)) {
            drawDraggedPaletteGhost(mouseX, mouseY);
        }
    }

    private static void resetGuiGlStateForStringDraw() {
        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Drawn red X (two strokes) for the {@code Clear slot} palette cell / ghost — not a font glyph. */
    private void drawClearGlyph(int centerX, int centerY) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableDepth();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        final float half = 6f;
        final float halfThickness = 2f;
        final float outlineExtra = 1f;

        // Pass 1: black outline. The stroke is thicker AND a little longer than the red
        // core — extending each end of the line by outlineExtra adds a cap that closes
        // the tips of the X, otherwise the red quad pokes through at the four endpoints
        // because the perpendicular-only offset leaves the line ends flush with the red.
        GlStateManager.color(0f, 0f, 0f, 0.92f);
        drawThickStrokeExtended(centerX - half, centerY - half, centerX + half, centerY + half,
                halfThickness + outlineExtra, outlineExtra);
        drawThickStrokeExtended(centerX + half, centerY - half, centerX - half, centerY + half,
                halfThickness + outlineExtra, outlineExtra);

        // Pass 2: red core on top. Using GL_LINES + glLineWidth is unreliable — most
        // drivers cap line width at ~1 px without explicit GL_LINE_SMOOTH state — so the
        // strokes are built as four-vertex quads offset perpendicular to the line.
        GlStateManager.color(1f, 0.25f, 0.25f, 0.95f);
        drawThickStroke(centerX - half, centerY - half, centerX + half, centerY + half, halfThickness);
        drawThickStroke(centerX + half, centerY - half, centerX - half, centerY + half, halfThickness);

        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        resetGuiGlStateForStringDraw();
    }

    /**
     * Filled quad along the segment {@code A→B}, extending {@code halfThickness} pixels on
     * each side of the line in screen-perpendicular direction. Stable thickness regardless
     * of the GL driver's {@code glLineWidth} support.
     */
    private static void drawThickStroke(float ax, float ay, float bx, float by, float halfThickness) {
        drawThickStrokeExtended(ax, ay, bx, by, halfThickness, 0f);
    }

    /**
     * Same as {@link #drawThickStroke} but also extends the quad {@code endExtend} pixels
     * past each endpoint along the line direction. Used to give the X glyph's outline a
     * tip cap; with {@code endExtend == 0} this is identical to the plain stroke.
     */
    private static void drawThickStrokeExtended(float ax, float ay, float bx, float by,
                                                float halfThickness, float endExtend) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0e-4f) {
            return;
        }
        float ux = dx / len;
        float uy = dy / len;
        float nx = -uy * halfThickness;
        float ny = ux * halfThickness;
        float ex = ux * endExtend;
        float ey = uy * endExtend;
        float a0x = ax - ex;
        float a0y = ay - ey;
        float b0x = bx + ex;
        float b0y = by + ey;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(a0x - nx, a0y - ny);
        GL11.glVertex2f(a0x + nx, a0y + ny);
        GL11.glVertex2f(b0x + nx, b0y + ny);
        GL11.glVertex2f(b0x - nx, b0y - ny);
        GL11.glEnd();
    }

    /**
     * Top-left corner of palette cell {@code index}: main toggles fill exactly {@link #PALETTE_MAIN_ROWS}
     * centered rows; the last entry ({@code Clear slot}) sits alone on a third row below.
     */
    private void paletteCellXY(int index, int[] out) {
        int n = paletteEntries.size();
        int clearIndex = n - 1;
        int mainCount = n - 1;

        if (index == clearIndex) {
            int rowY = paletteTop + PALETTE_MAIN_ROWS * (PALETTE_CELL + PALETTE_GAP) + CLEAR_ROW_GAP_PX;
            out[0] = wheelCx - PALETTE_CELL / 2;
            out[1] = rowY;
            return;
        }

        int row0Count = (mainCount + 1) / 2;
        int row = index < row0Count ? 0 : 1;
        int col = row == 0 ? index : index - row0Count;
        int itemsInRow = row == 0 ? row0Count : mainCount - row0Count;
        int rowWidth = itemsInRow * PALETTE_CELL + Math.max(0, itemsInRow - 1) * PALETTE_GAP;
        int rowLeft = wheelCx - rowWidth / 2;
        out[0] = rowLeft + col * (PALETTE_CELL + PALETTE_GAP);
        out[1] = paletteTop + row * (PALETTE_CELL + PALETTE_GAP);
    }

    /** True when some enabled wheel slice uses the same action type and value as this palette template. */
    private static boolean wheelContainsPaletteTemplate(RadialMenuItem template) {
        if (template == null || template.getActionType() == RadialMenuItem.ActionType.NONE) {
            return false;
        }
        RadialMenuItem[] wheel = RadialMenuWheel.getSlots();
        for (RadialMenuItem s : wheel) {
            if (s == null || !s.isSlotEnabled() || s.getActionType() == RadialMenuItem.ActionType.NONE) {
                continue;
            }
            if (s.getActionType() != template.getActionType()) {
                continue;
            }
            String a = s.getActionValue() != null ? s.getActionValue().trim() : "";
            String b = template.getActionValue() != null ? template.getActionValue().trim() : "";
            if (a.equals(b)) {
                return true;
            }
        }
        return false;
    }

    private void drawPalette(int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getMinecraft();
        for (int i = 0; i < paletteEntries.size(); i++) {
            paletteCellXY(i, paletteScratchXY);
            int x = paletteScratchXY[0];
            int y = paletteScratchXY[1];
            boolean hot = mouseX >= x && mouseX < x + PALETTE_CELL && mouseY >= y && mouseY < y + PALETTE_CELL;
            boolean used = wheelContainsPaletteTemplate(paletteEntries.get(i).template);
            int bg = used
                    ? (hot ? PALETTE_BG_USED_HOT : PALETTE_BG_USED)
                    : (hot ? PALETTE_BG_DIM_HOT : PALETTE_BG_DIM);
            Gui.drawRect(x, y, x + PALETTE_CELL, y + PALETTE_CELL, bg);
            ItemStack stack = RadialMenuWheel.getSlotIconStack(paletteEntries.get(i).template);
            if (stack != null) {
                final float itemScale = 1.125f;
                float drawn = 16f * itemScale;
                int inset = Math.round((PALETTE_CELL - drawn) * 0.5f);
                GlStateManager.pushMatrix();
                GlStateManager.enableDepth();
                GlStateManager.translate(x + inset, y + inset, 0);
                GlStateManager.scale(itemScale, itemScale, 1f);
                RenderHelper.enableGUIStandardItemLighting();
                float tint = RadialMenuWheel.iconExtraTintFor(paletteEntries.get(i).template);
                if (tint < 1f) {
                    GlStateManager.color(tint, tint, tint, 1f);
                }
                mc.getRenderItem().zLevel = 200f;
                mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
                mc.getRenderItem().renderItemOverlayIntoGUI(fontRendererObj, stack, 0, 0, null);
                mc.getRenderItem().zLevel = 0f;
                RenderHelper.disableStandardItemLighting();
                GlStateManager.color(1f, 1f, 1f, 1f);
                GlStateManager.disableDepth();
                GlStateManager.popMatrix();
            } else {
                drawClearGlyph(x + PALETTE_CELL / 2, y + PALETTE_CELL / 2);
            }
        }
        resetGuiGlStateForStringDraw();
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "Modules (click selected slice or drag onto a slice)",
                width / 2, paletteTop - 12, 0xAAAAAA);
    }

    private void drawDraggedPaletteGhost(int mouseX, int mouseY) {
        if (dragPaletteIndex < 0 || dragPaletteIndex >= paletteEntries.size()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack stack = RadialMenuWheel.getSlotIconStack(paletteEntries.get(dragPaletteIndex).template);
        if (stack == null) {
            drawClearGlyph(mouseX, mouseY);
            resetGuiGlStateForStringDraw();
            return;
        }
        final float itemScale = 1.125f;
        float drawn = 16f * itemScale;
        int gx = Math.round(mouseX - drawn * 0.5f);
        int gy = Math.round(mouseY - drawn * 0.5f);
        GlStateManager.pushMatrix();
        GlStateManager.enableDepth();
        GlStateManager.translate(gx, gy, 0);
        GlStateManager.scale(itemScale, itemScale, 1f);
        GlStateManager.color(1f, 1f, 1f, 0.82f);
        RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().zLevel = 400f;
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        mc.getRenderItem().renderItemOverlayIntoGUI(fontRendererObj, stack, 0, 0, null);
        mc.getRenderItem().zLevel = 0f;
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
        resetGuiGlStateForStringDraw();
    }

    @Override
    public void actionPerformed(GuiButton button) throws IOException {
        if (capturingKeybind) {
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        if (button.id == ID_BACK) {
            if (parent instanceof MiscellaneousGUI) {
                ((MiscellaneousGUI) parent).refreshRadialMenuButtonLabel();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == ID_TOGGLE) {
            boolean now = !RadialMenuWheel.isEnabled();
            RadialMenuWheel.setEnabled(now);
            ConfigManager.setRadialMenuEnabled(now);
            toggleButton.displayString = getToggleText();
            enabledOverlays.onToggled("main", now);
            return;
        }
        if (button.id == ID_KEYBIND) {
            capturingKeybind = true;
        }
    }

    private String getToggleText() {
        return "Wheel modules: " + (RadialMenuWheel.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getKeybindText() {
        int code = ConfigManager.getRadialMenuKeyCode();
        if (code == 0) {
            return "Key: " + EnumChatFormatting.WHITE + "NONE";
        }
        if (code < 0) {
            int mb = -code - 1;
            return "Key: " + EnumChatFormatting.WHITE + "Mouse " + (mb + 1);
        }
        String keyName = Keyboard.getKeyName(code);
        String label = (keyName == null || keyName.trim().isEmpty()) ? "UNKNOWN" : keyName;
        return "Key: " + EnumChatFormatting.WHITE + label;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (capturingKeybind) {
            int code = -(mouseButton + 1);
            ConfigManager.setRadialMenuKeyCode(code);
            GomodKeybinds.applyRadialMenuKeyCode(code);
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }
        if (isMouseOverAnyButton(mouseX, mouseY)) {
            return;
        }
        int palIdx = paletteIndexAt(mouseX, mouseY);
        if (palIdx >= 0 && !isRestrictedPaletteIndex(palIdx)) {
            dragPaletteIndex = palIdx;
            return;
        }
        int slice = RadialMenuWheel.computeHoveredAt(mouseX, mouseY, wheelCx, wheelCy, EDITOR_INNER_R,
                EDITOR_OUTER_R, EDITOR_PICK_MAX_R);
        if (slice >= 0) {
            if (slice == selectedSlice) {
                selectedSlice = -1;
            } else {
                selectedSlice = slice;
            }
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
        if (state != 0) {
            return;
        }
        if (dragPaletteIndex < 0) {
            return;
        }
        int dragging = dragPaletteIndex;
        dragPaletteIndex = -1;
        if (isMouseOverAnyButton(mouseX, mouseY)) {
            return;
        }
        int slice = RadialMenuWheel.computeHoveredAt(mouseX, mouseY, wheelCx, wheelCy, EDITOR_INNER_R,
                EDITOR_OUTER_R, EDITOR_PICK_MAX_R);
        if (slice >= 0) {
            applyPaletteEntry(dragging, slice);
            selectedSlice = slice;
            return;
        }
        if (paletteIndexAt(mouseX, mouseY) == dragging && selectedSlice >= 0) {
            applyPaletteEntry(dragging, selectedSlice);
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        dragPaletteIndex = -1;
    }

    private boolean isMouseOverAnyButton(int mouseX, int mouseY) {
        for (Object o : buttonList) {
            if (!(o instanceof GuiButton)) {
                continue;
            }
            GuiButton gb = (GuiButton) o;
            if (!gb.visible) {
                continue;
            }
            if (mouseX >= gb.xPosition && mouseX < gb.xPosition + gb.width
                    && mouseY >= gb.yPosition && mouseY < gb.yPosition + gb.height) {
                return true;
            }
        }
        return false;
    }

    private int paletteIndexAt(int mouseX, int mouseY) {
        for (int i = 0; i < paletteEntries.size(); i++) {
            paletteCellXY(i, paletteScratchXY);
            int x = paletteScratchXY[0];
            int y = paletteScratchXY[1];
            if (mouseX >= x && mouseX < x + PALETTE_CELL && mouseY >= y && mouseY < y + PALETTE_CELL) {
                return i;
            }
        }
        return -1;
    }

    private boolean isRestrictedPaletteIndex(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= paletteEntries.size()) {
            return false;
        }
        return GomodInternalFeatures.isRestrictedRadialSlot(paletteEntries.get(paletteIndex).template);
    }

    private void applyPaletteEntry(int paletteIndex, int targetSlice) {
        if (targetSlice < 0 || targetSlice >= RadialMenuWheel.SLOT_COUNT) {
            return;
        }
        if (isRestrictedPaletteIndex(paletteIndex)) {
            return;
        }
        PaletteEntry pe = paletteEntries.get(paletteIndex);
        RadialMenuItem src = pe.template;
        RadialMenuItem copy = new RadialMenuItem(
                src.getLabel(),
                src.getColor(),
                src.isSlotEnabled(),
                src.getActionType(),
                src.getActionValue());
        if (copy.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            copy.setColor(RadialMenuWheel.suggestedColorForToggleKey(copy.getActionValue(), targetSlice));
        }
        RadialMenuWheel.setSlot(targetSlice, copy);
        RadialMenuWheel.saveToFile();
    }

    /** Kept for {@link RadialMenuSlotEditorGUI}; no longer opened from this screen. */
    public void refreshAfterSlotEditor() {
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (capturingKeybind) {
            int code = (keyCode == 1) ? 0 : keyCode;
            ConfigManager.setRadialMenuKeyCode(code);
            GomodKeybinds.applyRadialMenuKeyCode(code);
            capturingKeybind = false;
            keybindButton.displayString = getKeybindText();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return capturingKeybind;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class PaletteEntry {
        final RadialMenuItem template;
        /** Tooltip when hovering the palette cell (module / action name). */
        final String tooltipTitle;

        PaletteEntry(RadialMenuItem template, String tooltipTitle) {
            this.template = template;
            this.tooltipTitle = tooltipTitle;
        }
    }
}
