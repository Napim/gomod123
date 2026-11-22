package me.napim.gomod.gui;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.util.Collections;
import java.util.List;

/**
 * Compact green-themed search bar for {@link GomodGUI}.
 * Starts collapsed as a search icon; animates open on hover.
 */
public final class GomodSearchBar {

    public static final int BAR_H = 13;
    /** Width of the collapsed icon pill. */
    private static final int ICON_PILL_W = 18;
    private static final int ICON_PILL_TRIM_LEFT = 2;
    private static final int ICON_PILL_TRIM_RIGHT = 1;
    private static final int ICON_PAD = 14;
    private static final int DROPDOWN_ITEM_H = 16;
    private static final int DROPDOWN_VISIBLE_MAX = 9;
    private static final int SEARCH_ICON_DRAW_SIZE = 10;
    private static final ResourceLocation SEARCH_ICON =
            new ResourceLocation("gomod123", "textures/searchicon.png");

    /** Expand/collapse duration in milliseconds. */
    private static final long EXPAND_MS  = 220L;
    private static final long COLLAPSE_MS = 180L;

    // gomod123 cactus green theme
    private static final int COL_BAR_BG      = 0xD0081808;
    private static final int COL_BAR_BORDER  = 0xFF2D6B2D;
    private static final int COL_BAR_FOCUS   = 0xFF44BB44;
    private static final int COL_PLACEHOLDER = 0x7070AA70;
    private static final int COL_ICON        = 0xB055DD55;
    private static final int COL_ICON_HOVER  = 0xFFAAFFAA;
    private static final int COL_TEXT        = 0xE8FFE8;

    private static final int COL_DROP_BG     = 0xF0081408;
    private static final int COL_DROP_BORDER = 0xFF2A7A2A;
    private static final int COL_DROP_HOVER  = 0x6044BB44;
    private static final int COL_DROP_SEP    = 0x38338838;
    private static final int COL_LABEL       = 0xEEFFEE;
    private static final int COL_PARENT      = 0xFF88CC88;

    private final FontRenderer fr;
    private final GuiTextField field;
    /** Full (target) bar position/size — never changes after construction. */
    private final int barX;
    private final int barY;
    private final int barW;

    private List<GomodSearchEntry> results = Collections.emptyList();
    private String lastQuery = "";
    private int hoveredIndex = -1;
    private int dropdownScroll = 0;

    // ── Animation ────────────────────────────────────────────────────────────
    /** 0 = fully collapsed, 1 = fully expanded. */
    private float expandAnim = 0f;
    private boolean expanding = false;
    private long animStartMs  = -1L;
    /** expandAnim value at the moment the direction last reversed. */
    private float animStartVal = 0f;

    public GomodSearchBar(FontRenderer fontRenderer, int barX, int barY, int barW) {
        this.fr = fontRenderer;
        this.barX = barX;
        this.barY = barY;
        this.barW = barW;
        int fieldX = barX + ICON_PAD + 1;
        int fieldW  = barW - ICON_PAD - 6;
        field = new GuiTextField(999, fontRenderer, fieldX, barY + 2, fieldW, BAR_H - 4);
        field.setMaxStringLength(60);
        field.setEnableBackgroundDrawing(false);
        field.setTextColor(COL_TEXT);
        field.setDisabledTextColour(0x668866);
    }

    // ── Expansion helpers ─────────────────────────────────────────────────────

    private boolean wantExpanded(int mouseX, int mouseY) {
        if (field.isFocused() || !field.getText().isEmpty()) {
            return true;
        }
        return isMouseOverHitbox(mouseX, mouseY);
    }

    private void updateAnim(boolean wantExpand) {
        if (wantExpand == expanding) {
            return;
        }
        expanding    = wantExpand;
        animStartVal = expandAnim;
        animStartMs  = System.currentTimeMillis();
    }

    private void tickAnim() {
        if (animStartMs < 0L) {
            return;
        }
        long elapsed  = System.currentTimeMillis() - animStartMs;
        long duration = expanding ? EXPAND_MS : COLLAPSE_MS;
        float t = Math.min(1f, (float) elapsed / duration);
        float eased = easeOutQuad(t);
        expandAnim = expanding
                ? animStartVal + eased * (1f - animStartVal)
                : animStartVal - eased * animStartVal;
        if (t >= 1f) {
            expandAnim = expanding ? 1f : 0f;
            animStartMs = -1L;
        }
    }

    private static float easeOutQuad(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    /** Current drawn width (lerp between pill and full bar). */
    private int drawnW() {
        return Math.round(ICON_PILL_W + (barW - ICON_PILL_W) * expandAnim);
    }

    /** Current drawn left edge (bar grows from centre). */
    private int drawnX() {
        int cx = barX + barW / 2;
        return cx - drawnW() / 2;
    }

    /** Visible left edge of the animated bar after applying the collapsed-pill trims. */
    private int drawnVisualX() {
        return drawnX() + Math.round((1f - expandAnim) * ICON_PILL_TRIM_LEFT);
    }

    /** Visible width of the animated bar after applying the collapsed-pill trims. */
    private int drawnVisualW() {
        int trim = Math.round((1f - expandAnim) * (ICON_PILL_TRIM_LEFT + ICON_PILL_TRIM_RIGHT));
        return Math.max(1, drawnW() - trim);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void tick() {
        field.updateCursorCounter();
    }

    public boolean isFocused() {
        return field.isFocused();
    }

    /** Block WASD / perspective while the search field is active. */
    public boolean shouldBlockMenuMovement() {
        return field.isFocused();
    }

    public void clearAndClose() {
        field.setText("");
        field.setFocused(false);
        results      = Collections.emptyList();
        lastQuery    = "";
        dropdownScroll = 0;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    public void draw(int mouseX, int mouseY) {
        // Update query / results
        String q = field.getText();
        if (!q.equals(lastQuery)) {
            lastQuery = q;
            results   = GomodSearchIndex.search(q);
            dropdownScroll = 0;
        }

        // Drive animation
        updateAnim(wantExpanded(mouseX, mouseY));
        tickAnim();

        int dw = drawnVisualW();
        int dx = drawnVisualX();

        boolean iconHover = isMouseOverIconHitbox(mouseX, mouseY) && expandAnim < 0.5f;
        boolean focused   = field.isFocused() || !q.isEmpty();

        // Always show the green frame (matches hover look); brighter when focused
        int border = focused ? COL_BAR_FOCUS : COL_BAR_BORDER;
        Gui.drawRect(dx - 1, barY - 1, dx + dw + 1, barY + BAR_H + 1, border);
        Gui.drawRect(dx, barY, dx + dw, barY + BAR_H, COL_BAR_BG);

        // Search icon: centered in collapsed pill, slides to left edge when expanded
        int collapsedCenterX = dx + dw / 2;
        int expandedIconX = barX + 7;
        int iconCx = Math.round(collapsedCenterX + (expandedIconX - collapsedCenterX) * expandAnim);
        int iconC = iconHover ? COL_ICON_HOVER : COL_ICON;
        drawMagnifierIcon(iconCx, barY + BAR_H / 2, iconC);

        // Text field + placeholder — only when sufficiently expanded
        if (expandAnim > 0.6f) {
            float textAlpha = (expandAnim - 0.6f) / 0.4f;
            int ta = Math.max(0, Math.min(255, (int) (textAlpha * 255)));
            field.drawTextBox();
            if (q.isEmpty() && !field.isFocused()) {
                int col = (ta << 24) | (COL_PLACEHOLDER & 0x00FFFFFF);
                fr.drawString("Search settings...", barX + ICON_PAD + 1, barY + 3, col);
            }
        }

        if (isDropdownOpen()) {
            drawDropdown(mouseX, mouseY);
        }
    }

    private boolean isDropdownOpen() {
        return field.isFocused() && !results.isEmpty();
    }

    public static void drawMagnifierIcon(int cx, int cy, int c) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            try {
                GomodClasspathTextures.bindTexture(mc, SEARCH_ICON);
                int texW = GomodClasspathTextures.getWidth(SEARCH_ICON, SEARCH_ICON_DRAW_SIZE);
                int texH = GomodClasspathTextures.getHeight(SEARCH_ICON, SEARCH_ICON_DRAW_SIZE);
                int x = cx - SEARCH_ICON_DRAW_SIZE / 2;
                int y = cy - SEARCH_ICON_DRAW_SIZE / 2;
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.color(1f, 1f, 1f, 1f);
                Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH,
                        SEARCH_ICON_DRAW_SIZE, SEARCH_ICON_DRAW_SIZE, texW, texH);
                GlStateManager.color(1f, 1f, 1f, 1f);
                return;
            } catch (Throwable ignored) {
            }
        }
        int lx = cx - 2, ly = cy - 2;
        Gui.drawRect(lx + 1, ly,     lx + 3, ly + 1, c);
        Gui.drawRect(lx + 1, ly + 3, lx + 3, ly + 4, c);
        Gui.drawRect(lx,     ly + 1, lx + 1, ly + 3, c);
        Gui.drawRect(lx + 3, ly + 1, lx + 4, ly + 3, c);
        Gui.drawRect(lx + 3, ly + 3, lx + 4, ly + 4, c);
        Gui.drawRect(lx + 4, ly + 4, lx + 5, ly + 5, c);
    }

    private void drawDropdown(int mouseX, int mouseY) {
        int total   = results.size();
        int visible = Math.min(DROPDOWN_VISIBLE_MAX, total);
        int maxScroll = Math.max(0, total - visible);
        if (dropdownScroll > maxScroll) dropdownScroll = maxScroll;

        int dropX = barX;
        int dropY = barY + BAR_H + 1;
        int dropW = barW;
        int dropH = visible * DROPDOWN_ITEM_H + 2;

        Gui.drawRect(dropX - 1, dropY - 1, dropX + dropW + 1, dropY + dropH + 1, COL_DROP_BORDER);
        Gui.drawRect(dropX, dropY, dropX + dropW, dropY + dropH, COL_DROP_BG);

        hoveredIndex = -1;
        for (int slot = 0; slot < visible; slot++) {
            int idx = slot + dropdownScroll;
            if (idx >= total) break;
            int iy    = dropY + 1 + slot * DROPDOWN_ITEM_H;
            boolean hover = mouseX >= dropX && mouseX < dropX + dropW
                    && mouseY >= iy && mouseY < iy + DROPDOWN_ITEM_H;
            if (hover) hoveredIndex = idx;
            if (hover) Gui.drawRect(dropX, iy, dropX + dropW, iy + DROPDOWN_ITEM_H, COL_DROP_HOVER);

            GomodSearchEntry e = results.get(idx);
            int textY  = iy + 4;
            fr.drawStringWithShadow(stripFormatting(e.label), dropX + 6, textY, COL_LABEL);

            String parent  = stripFormatting(e.parentLabel);
            int parentW    = fr.getStringWidth(parent);
            int parentX    = dropX + dropW - parentW - 5;
            int labelW     = fr.getStringWidth(stripFormatting(e.label));
            if (parentX > dropX + 8 + labelW + 4) {
                fr.drawString(parent, parentX, textY, COL_PARENT);
            }

            if (slot < visible - 1 && idx + 1 < total) {
                int sepY = iy + DROPDOWN_ITEM_H - 1;
                Gui.drawRect(dropX + 3, sepY, dropX + dropW - 3, sepY + 1, COL_DROP_SEP);
            }
        }

        if (maxScroll > 0) {
            int trackX   = dropX + dropW - 3;
            int trackTop = dropY + 1;
            int trackH   = dropH - 2;
            Gui.drawRect(trackX, trackTop, trackX + 2, trackTop + trackH, 0x40224422);
            int thumbH = Math.max(8, trackH * visible / total);
            int thumbY = trackTop + (maxScroll == 0 ? 0 : (trackH - thumbH) * dropdownScroll / maxScroll);
            Gui.drawRect(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xAA55DD55);
        }
    }

    private static String stripFormatting(String s) {
        return s.replaceAll("\u00A7.", "");
    }

    // ── Mouse / keyboard ─────────────────────────────────────────────────────

    public GomodSearchEntry mouseClicked(int mouseX, int mouseY, int button) {
        // Click on the full bar → focus field (bar will stay expanded)
        if (expandAnim > 0.4f
                && mouseX >= drawnVisualX() && mouseX < drawnVisualX() + drawnVisualW()
                && mouseY >= barY && mouseY < barY + BAR_H) {
            field.setFocused(true);
            field.mouseClicked(mouseX, mouseY, button);
            return null;
        }

        // Click on collapsed icon → expand without focusing (hover will do it)
        if (isMouseOverIconHitbox(mouseX, mouseY)) {
            field.setFocused(true);
            return null;
        }

        // Click inside dropdown
        if (isDropdownOpen()) {
            int visible = Math.min(DROPDOWN_VISIBLE_MAX, results.size());
            int dropX = barX;
            int dropY = barY + BAR_H + 1;
            int dropW = barW;
            int dropH = visible * DROPDOWN_ITEM_H + 2;
            if (mouseX >= dropX && mouseX < dropX + dropW
                    && mouseY >= dropY && mouseY < dropY + dropH) {
                int slot = (mouseY - dropY - 1) / DROPDOWN_ITEM_H;
                int idx  = slot + dropdownScroll;
                if (idx >= 0 && idx < results.size()) {
                    GomodSearchEntry selected = results.get(idx);
                    clearAndClose();
                    return selected;
                }
            }
        }

        field.setFocused(false);
        return null;
    }

    public boolean keyTyped(char typedChar, int keyCode) {
        if (!field.isFocused()) {
            return false;
        }
        if (keyCode == 1) {
            if (!field.getText().isEmpty() || !results.isEmpty()) {
                clearAndClose();
                return true;
            }
            return false;
        }
        field.textboxKeyTyped(typedChar, keyCode);
        return true;
    }

    /** Scroll dropdown when the mouse is over it. */
    public void handleMouseWheel(int mouseX, int mouseY) {
        if (!isDropdownOpen()) return;
        int visible = Math.min(DROPDOWN_VISIBLE_MAX, results.size());
        int dropY   = barY + BAR_H + 1;
        int dropH   = visible * DROPDOWN_ITEM_H + 2;
        if (!(mouseX >= barX && mouseX < barX + barW && mouseY >= dropY && mouseY < dropY + dropH)) return;
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0) return;
        int maxScroll = Math.max(0, results.size() - visible);
        if (dWheel > 0) dropdownScroll = Math.max(0, dropdownScroll - 1);
        else            dropdownScroll = Math.min(maxScroll, dropdownScroll + 1);
    }

    public boolean isDropdownVisible() {
        return isDropdownOpen();
    }

    // ── Hit-test helpers ─────────────────────────────────────────────────────

    /** Hit area of the collapsed icon pill (always valid). */
    private boolean isMouseOverIconHitbox(int mx, int my) {
        int cx = barX + barW / 2;
        int ix = cx - ICON_PILL_W / 2 + ICON_PILL_TRIM_LEFT;
        int iw = ICON_PILL_W - ICON_PILL_TRIM_LEFT - ICON_PILL_TRIM_RIGHT;
        return mx >= ix && mx < ix + iw
                && my >= barY && my < barY + BAR_H;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        // Full bar (when expanded or expanding)
        if (expandAnim > 0.01f) {
            int dx = drawnVisualX();
            int dw = drawnVisualW();
            if (mouseX >= dx && mouseX < dx + dw
                    && mouseY >= barY && mouseY < barY + BAR_H) {
                return true;
            }
        }
        // Collapsed icon
        if (isMouseOverIconHitbox(mouseX, mouseY)) return true;
        // Dropdown
        if (isDropdownOpen()) {
            int visible = Math.min(DROPDOWN_VISIBLE_MAX, results.size());
            int dropY   = barY + BAR_H + 1;
            int dropH   = visible * DROPDOWN_ITEM_H + 2;
            return mouseX >= barX && mouseX < barX + barW
                    && mouseY >= dropY && mouseY < dropY + dropH;
        }
        return false;
    }

    /** Broader hit test used by {@link GomodGUI} to decide whether a click should be forwarded to buttons. */
    private boolean isMouseOverHitbox(int mx, int my) {
        // When animating/expanded, match the live drawn area
        if (expandAnim > 0.01f) {
            int dx = drawnVisualX();
            int dw = drawnVisualW();
            if (mx >= dx && mx < dx + dw && my >= barY && my < barY + BAR_H) {
                return true;
            }
        }
        return isMouseOverIconHitbox(mx, my);
    }
}
