package me.napim.gomod.gui;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * Compact expandable HSV picker. By default the panel opens to the left of the button (HUD color rows).
 * Use {@link #setExpandToRight(boolean)} when the button sits on the left edge of the screen.
 */
public final class HsvColorPickerWidget {

    private static final Gui TEXTURE_DRAW = new Gui() {};
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final ResourceLocation PICKER_ICON =
            new ResourceLocation("gomod123", "textures/colorpicker.png");
    private static final ResourceLocation PICKER_ICON_FALLBACK =
            new ResourceLocation("gomod123", "runtime/colorpicker");

    private static boolean iconTried;
    private static boolean iconLoaded;
    private static boolean iconFallback;
    private static int iconSrcW = 16;
    private static int iconSrcH = 16;

    public interface Listener {
        void onColorChanged(int rgb);

        void onColorChangeComplete(int rgb);
    }

    private static final int BTN_SIZE = 20;
    /** Eyedropper overlay inside the inset color swatch ({@link #BTN_SIZE} minus 2px stroke). */
    private static final int ICON_DRAW_SIZE = 12;
    private static final int PICKER_W = 130;
    private static final int PICKER_H = 90;
    private static final int PICKER_W_PERF = 96;
    private static final int PICKER_H_PERF = 64;
    private static final int HUE_W = 10;
    private static final ResourceLocation HUE_BAR_TEX =
            new ResourceLocation("gomod123", "runtime/hsv_hue_bar");
    private static final ResourceLocation SV_FIELD_TEX =
            new ResourceLocation("gomod123", "runtime/hsv_sv_field");

    /** Cached SV/hue textures and compact panel — default on for all HUD color screens. */
    private static boolean hudColorsPerformanceMode = true;
    private static DynamicTexture hueBarTexture;
    private static int hueBarCacheH;
    private static DynamicTexture svFieldTexture;
    private static float svFieldCacheHue = -1f;
    private static int svFieldCacheW;
    private static int svFieldCacheH;
    private static final int PANEL_GAP = 4;
    private static final int PANEL_BG_PAD = 2;
    private static final int OUTSIDE_STROKE = 1;
    private static final int EXPAND_ANIM_MS = 200;

    private int x;
    private int y;
    /** When true, panel opens to the right of the button instead of the left. */
    private boolean expandToRight;
    /** When true, panel opens below the button with its right edge aligned to the button (opens leftward). */
    private boolean expandBelow;
    /** Fixed panel at {@link #inlinePanelX}/{@link #inlinePanelY} — no popup (cape editor). */
    private boolean inlineMode;
    private int inlinePanelX;
    private int inlinePanelY;
    /** When false, the panel opens at full size (hit-testing matches pixels in cape editor). */
    private boolean animateExpand = true;
    /** When true, outside clicks do not collapse the panel (cape editor eyedropper). */
    private boolean retainOpenForEyedropper;
    private boolean visible = true;
    private boolean enabled = true;
    private boolean expanded;
    private float expandAnim;
    private float hue;
    private float saturation = 1f;
    private float brightness = 1f;
    private int rgb;
    private boolean draggingSv;
    private boolean draggingHue;
    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setExpandToRight(boolean expandToRight) {
        this.expandToRight = expandToRight;
    }

    /**
     * Panel under the button, right-aligned to it so the picker grows left (for right-edge HUD columns).
     */
    public void setExpandBelow(boolean expandBelow) {
        this.expandBelow = expandBelow;
        if (expandBelow) {
            this.expandToRight = false;
        }
    }

    /** Disable scale animation so screen-space clicks match the drawn panel (cape editor). */
    public void setAnimateExpand(boolean animateExpand) {
        this.animateExpand = animateExpand;
        if (!animateExpand && expanded) {
            expandAnim = 1f;
        }
    }

    /** Keeps the HSV panel open when clicking outside it so the host GUI can sample colors (cape editor). */
    public void setRetainOpenForEyedropper(boolean retainOpenForEyedropper) {
        this.retainOpenForEyedropper = retainOpenForEyedropper;
    }

    public boolean isOverButton(int mouseX, int mouseY) {
        int[] btn = new int[4];
        getButtonBounds(btn);
        return mouseX >= btn[0] && mouseX < btn[2] && mouseY >= btn[1] && mouseY < btn[3];
    }

    /** Eyedropper cursor when the panel is open and the pointer is not on the panel or swatch button. */
    public boolean shouldDrawEyedropperCursor(int mouseX, int mouseY) {
        return isOpenOrAnimating() && !isPointerOverOpenPanel(mouseX, mouseY) && !isOverButton(mouseX, mouseY);
    }

    public static int panelOuterWidth() {
        return pickerW() + HUE_W + 6 + PANEL_BG_PAD * 2 + OUTSIDE_STROKE * 2;
    }

    public static int panelOuterHeight() {
        return pickerH() + PANEL_BG_PAD * 2 + OUTSIDE_STROKE * 2;
    }

    /**
     * Always-visible HSV panel at fixed coordinates (no dropdown). Clicks target the same rect that is drawn.
     */
    public void enableInlineMode(int panelX, int panelY) {
        inlineMode = true;
        inlinePanelX = panelX;
        inlinePanelY = panelY;
        expanded = true;
        expandAnim = 1f;
        animateExpand = false;
        expandBelow = false;
    }

    public boolean isInlineMode() {
        return inlineMode;
    }

    public int getButtonWidth() {
        return BTN_SIZE;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            setExpanded(false);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            setExpanded(false);
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public int getLayoutExtraHeight() {
        return 0;
    }

    public void syncFromRgb(int rgb24) {
        this.rgb = rgb24 & 0xFFFFFF;
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        hue = hsb[0];
        saturation = hsb[1];
        brightness = hsb[2];
    }

    /** Smaller SV panel + cached textures (on by default for all color pickers). */
    public static void setHudColorsPerformanceMode(boolean enabled) {
        hudColorsPerformanceMode = enabled;
        if (!enabled) {
            svFieldCacheHue = -1f;
        }
    }

    private static int pickerW() {
        return hudColorsPerformanceMode ? PICKER_W_PERF : PICKER_W;
    }

    private static int pickerH() {
        return hudColorsPerformanceMode ? PICKER_H_PERF : PICKER_H;
    }

    public int getRgb() {
        return rgb;
    }

    public static void resetIconCache() {
        iconTried = false;
        iconLoaded = false;
        iconFallback = false;
    }

    /** Loads the eyedropper icon if needed; safe to call from HUD color screen {@code initGui}. */
    public static void warmIconCache(Minecraft mc) {
        ensureIconLoaded(mc);
    }

    /** Eyedropper icon beside the cursor while the picker is open (outside the HSV panel). */
    public static void drawEyedropperNearCursor(Minecraft mc, int mouseX, int mouseY) {
        if (mc == null) {
            return;
        }
        ensureIconLoaded(mc);
        int drawSize = ICON_DRAW_SIZE;
        int ix = mouseX + 10;
        int iy = mouseY + 6;
        if (!iconLoaded) {
            return;
        }
        try {
            HudGlState.restoreForOverlayText();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableLighting();
            if (iconFallback) {
                mc.getTextureManager().bindTexture(PICKER_ICON_FALLBACK);
            } else {
                mc.getTextureManager().bindTexture(PICKER_ICON);
            }
            GlStateManager.color(1f, 1f, 1f, 0.95f);
            Gui.drawScaledCustomSizeModalRect(ix, iy, 0, 0, iconSrcW, iconSrcH,
                    drawSize, drawSize, iconSrcW, iconSrcH);
        } catch (Throwable ignored) {
        } finally {
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.depthMask(true);
        }
    }

    private static void ensureIconLoaded(Minecraft mc) {
        if (iconTried || mc == null) {
            return;
        }
        iconTried = true;
        try {
            if (mc.getResourceManager() != null) {
                try {
                    mc.getResourceManager().getResource(PICKER_ICON);
                    iconLoaded = true;
                    iconFallback = false;
                    return;
                } catch (Exception ignored) {
                }
            }
            BufferedImage img = loadIconFromClasspath();
            if (img != null && mc.getTextureManager() != null) {
                iconSrcW = img.getWidth();
                iconSrcH = img.getHeight();
                mc.getTextureManager().loadTexture(PICKER_ICON_FALLBACK, new DynamicTexture(img));
                iconLoaded = true;
                iconFallback = true;
            }
        } catch (Throwable ignored) {
            iconLoaded = false;
        }
    }

    private static BufferedImage loadIconFromClasspath() {
        String[] paths = {
                "/assets/gomod123/textures/colorpicker.png",
                "/colorpicker.png"
        };
        for (String path : paths) {
            try (InputStream in = HsvColorPickerWidget.class.getResourceAsStream(path)) {
                if (in != null) {
                    BufferedImage img = ImageIO.read(in);
                    if (img != null) {
                        return img;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        drawPickerButton(mc, mouseX, mouseY);
    }

    public void drawExpanded(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!visible || !enabled) {
            return;
        }
        if (inlineMode) {
            HudGlState.restoreForOverlayText();
            GlStateManager.disableDepth();
            drawExpandedPicker(mc);
            return;
        }
        float t = updateExpandAnim(partialTicks);
        if (t <= 0.001f) {
            return;
        }
        HudGlState.restoreForOverlayText();
        GlStateManager.disableDepth();
        GlStateManager.pushMatrix();
        float scale = animateExpand ? easeOutCubic(t) : 1f;
        if (animateExpand && scale > 0.001f) {
            float pivotX = expandBelow ? x + BTN_SIZE : (expandToRight ? x + BTN_SIZE : x);
            float pivotY = expandBelow ? y + BTN_SIZE : y + BTN_SIZE / 2f;
            GlStateManager.translate(pivotX, pivotY, 0f);
            GlStateManager.scale(scale, scale, 1f);
            GlStateManager.translate(-pivotX, -pivotY, 0f);
            GlStateManager.color(1f, 1f, 1f, t);
        } else {
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
        drawExpandedPicker(mc);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
        GlStateManager.enableDepth();
    }

    private float updateExpandAnim(float partialTicks) {
        if (!animateExpand) {
            expandAnim = expanded ? 1f : 0f;
            return expandAnim;
        }
        float target = expanded ? 1f : 0f;
        float speed = 1f / EXPAND_ANIM_MS;
        float step = speed * (16f + partialTicks * 16f);
        if (expandAnim < target) {
            expandAnim = Math.min(target, expandAnim + step);
        } else if (expandAnim > target) {
            expandAnim = Math.max(target, expandAnim - step);
        }
        return expandAnim;
    }

    private float panelHitScale() {
        if (!animateExpand) {
            return 1f;
        }
        float s = easeOutCubic(expandAnim);
        return s < 0.05f ? 0.05f : s;
    }

    private void mapMouseToPanelSpace(int mouseX, int mouseY, float[] out) {
        if (inlineMode || !animateExpand) {
            out[0] = mouseX;
            out[1] = mouseY;
            return;
        }
        float s = panelHitScale();
        float pivotX = expandBelow ? x + BTN_SIZE : (expandToRight ? x + BTN_SIZE : x);
        float pivotY = expandBelow ? y + BTN_SIZE : y + BTN_SIZE / 2f;
        out[0] = pivotX + (mouseX - pivotX) / s;
        out[1] = pivotY + (mouseY - pivotY) / s;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void setExpanded(boolean open) {
        expanded = open;
    }

    public void collapse() {
        setExpanded(false);
    }

    public boolean isOpenOrAnimating() {
        return inlineMode || expanded || expandAnim > 0.01f;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public boolean isDraggingAdjust() {
        return draggingSv || draggingHue;
    }

    /** Outer bounds of the open panel (stroke included), or false if closed. {@code out} = [left, top, right, bottom]. */
    public boolean getOpenPanelOuterBounds(int[] out) {
        if (!isOpenOrAnimating() || out == null || out.length < 4) {
            return false;
        }
        out[0] = strokeOuterLeft();
        out[1] = strokeOuterTop();
        out[2] = strokeOuterRight();
        out[3] = strokeOuterBottom();
        return true;
    }

    /** True when the pointer is over the open HSV panel (screen-space bounds). */
    public boolean isPointerOverOpenPanel(int mouseX, int mouseY) {
        return isOpenOrAnimating() && containsOpenPanelPoint(mouseX, mouseY);
    }

    /**
     * When the picker is open or the user is dragging inside it, block clicks on widgets drawn
     * underneath (palette, paint canvas, {@link GuiScreen} buttons).
     */
    public boolean blocksGuiClicksAt(int mouseX, int mouseY) {
        if (!visible || !enabled) {
            return false;
        }
        if (draggingSv || draggingHue) {
            return true;
        }
        if (isOpenOrAnimating() && isPointerOverOpenPanel(mouseX, mouseY)) {
            return true;
        }
        int[] btn = new int[4];
        getButtonBounds(btn);
        return mouseX >= btn[0] && mouseX < btn[2] && mouseY >= btn[1] && mouseY < btn[3];
    }

    /** Button hit box: [left, top, right, bottom]. */
    public void getButtonBounds(int[] out) {
        if (out == null || out.length < 4) {
            return;
        }
        out[0] = x;
        out[1] = y;
        out[2] = x + BTN_SIZE;
        out[3] = y + BTN_SIZE;
    }

    private static boolean rectsOverlap(int[] a, int[] b) {
        return a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
    }

    /** True if this swatch button overlaps another picker's open panel (not its own). */
    public boolean isObscuredByOtherOpenPanel(HsvColorPickerWidget otherOpen) {
        if (otherOpen == null || otherOpen == this || !otherOpen.isOpenOrAnimating()) {
            return false;
        }
        int[] panel = new int[4];
        int[] btn = new int[4];
        if (!otherOpen.getOpenPanelOuterBounds(panel)) {
            return false;
        }
        getButtonBounds(btn);
        return rectsOverlap(btn, panel);
    }

    /**
     * Matches {@link net.minecraft.client.gui.GuiButton#getHoverState}: 0 = disabled row (v=46),
     * 1 = enabled idle (v=66), 2 = hover (v=86). Previous code used 46/66/0 and inverted every state.
     */
    private int getPickerHoverState(boolean mouseOver) {
        if (!enabled) {
            return 0;
        }
        if (expanded) {
            return 0;
        }
        if (mouseOver) {
            return 2;
        }
        return 1;
    }

    private void drawPickerButton(Minecraft mc, int mouseX, int mouseY) {
        boolean hovered = enabled && mouseX >= x && mouseX < x + BTN_SIZE
                && mouseY >= y && mouseY < y + BTN_SIZE;

        HudGlState.restoreForOverlayText();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableLighting();
        mc.getTextureManager().bindTexture(WIDGETS);

        int hoverState = getPickerHoverState(hovered);
        int texV = 46 + hoverState * 20;
        float alpha = enabled ? 1f : 0.5f;

        GlStateManager.color(1f, 1f, 1f, alpha);
        int half = BTN_SIZE / 2;
        TEXTURE_DRAW.drawTexturedModalRect(x, y, 0, texV, half, BTN_SIZE);
        TEXTURE_DRAW.drawTexturedModalRect(x + half, y, 200 - half, texV, half, BTN_SIZE);
        GlStateManager.color(1f, 1f, 1f, 1f);

        drawPickerColorFill();
        drawPickerIcon(mc);
    }

    private void drawPickerColorFill() {
        int inset = 2;
        int l = x + inset;
        int t = y + inset;
        int r = x + BTN_SIZE - inset;
        int b = y + BTN_SIZE - inset;
        int swatch = 0xFF000000 | (rgb & 0xFFFFFF);
        if (!enabled) {
            swatch = mixRgb(swatch, 0xFF808080, 0.45f);
        }
        Gui.drawRect(l, t, r, b, swatch);
        int stroke = 0xFF000000;
        Gui.drawRect(l - 1, t - 1, r + 1, t, stroke);
        Gui.drawRect(l - 1, b, r + 1, b + 1, stroke);
        Gui.drawRect(l - 1, t, l, b, stroke);
        Gui.drawRect(r, t, r + 1, b, stroke);
    }

    private static int mixRgb(int argb, int argbB, float t) {
        float a = 1f - t;
        int rA = (argb >> 16) & 0xFF, gA = (argb >> 8) & 0xFF, bA = argb & 0xFF;
        int rB = (argbB >> 16) & 0xFF, gB = (argbB >> 8) & 0xFF, bB = argbB & 0xFF;
        int r = Math.min(255, Math.round(rA * a + rB * t));
        int g = Math.min(255, Math.round(gA * a + gB * t));
        int b = Math.min(255, Math.round(bA * a + bB * t));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawPickerIcon(Minecraft mc) {
        ensureIconLoaded(mc);
        int drawSize = ICON_DRAW_SIZE;
        int ix = x + (BTN_SIZE - drawSize) / 2;
        int iy = y + (BTN_SIZE - drawSize) / 2;

        if (iconLoaded) {
            try {
                if (iconFallback) {
                    mc.getTextureManager().bindTexture(PICKER_ICON_FALLBACK);
                } else {
                    mc.getTextureManager().bindTexture(PICKER_ICON);
                }
                GlStateManager.enableBlend();
                GlStateManager.color(1f, 1f, 1f, enabled ? 0.92f : 0.5f);
                Gui.drawScaledCustomSizeModalRect(ix, iy, 0, 0, iconSrcW, iconSrcH,
                        drawSize, drawSize, iconSrcW, iconSrcH);
                GlStateManager.color(1f, 1f, 1f, 1f);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private int panelWidth() {
        return pickerW() + HUE_W + 6;
    }

    private int panelX() {
        if (inlineMode) {
            return inlinePanelX;
        }
        if (expandBelow) {
            return Math.max(PANEL_GAP, x + BTN_SIZE - panelWidth());
        }
        if (expandToRight) {
            return x + BTN_SIZE + PANEL_GAP;
        }
        return x - panelWidth() - PANEL_GAP;
    }

    private int panelY() {
        if (inlineMode) {
            return inlinePanelY;
        }
        if (expandBelow) {
            return y + BTN_SIZE + PANEL_GAP;
        }
        return y;
    }

    private void drawExpandedPicker(Minecraft mc) {
        int panelX = panelX();
        int panelY = panelY();
        int panelW = panelWidth();
        int ph = pickerH();
        int pw = pickerW();
        int bgL = panelX - PANEL_BG_PAD;
        int bgT = panelY - PANEL_BG_PAD;
        int bgR = panelX + panelW + PANEL_BG_PAD;
        int bgB = panelY + ph + PANEL_BG_PAD;

        Gui.drawRect(bgL, bgT, bgR, bgB, 0xE0202020);

        drawSvField(mc, panelX, panelY);
        drawHueBar(mc, panelX + pw + 4, panelY);

        int indicatorX = panelX + (int) (saturation * (pw - 1));
        int indicatorY = panelY + (int) ((1f - brightness) * (ph - 1));
        drawSvIndicator(indicatorX, indicatorY);

        int hueY = panelY + (int) (hue * (ph - 1));
        int hueX = panelX + pw + 4;
        drawHueBarMarker(hueX, hueY);

        drawOutsideBlackStroke(bgL, bgT, bgR, bgB, OUTSIDE_STROKE);
    }

    /** White “+” with a black outline on the arms (not a square frame). */
    private static void drawSvIndicator(int cx, int cy) {
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;
        Gui.drawRect(cx - 2, cy - 1, cx + 3, cy + 2, black);
        Gui.drawRect(cx - 1, cy - 2, cx + 2, cy + 3, black);
        Gui.drawRect(cx - 1, cy, cx + 2, cy + 1, white);
        Gui.drawRect(cx, cy - 1, cx + 1, cy + 2, white);
    }

    /** Hue slider thumb: black / white / black rows (was missing the bottom black row). */
    private static void drawHueBarMarker(int hueX, int hueY) {
        int top = hueY - 1;
        Gui.drawRect(hueX - 1, top, hueX + HUE_W + 1, top + 1, 0xFF000000);
        Gui.drawRect(hueX - 1, top + 1, hueX + HUE_W + 1, top + 2, 0xFFFFFFFF);
        Gui.drawRect(hueX - 1, top + 2, hueX + HUE_W + 1, top + 3, 0xFF000000);
    }

    private static void drawOutsideBlackStroke(int bgL, int bgT, int bgR, int bgB, int stroke) {
        int black = 0xFF000000;
        int sL = bgL - stroke;
        int sT = bgT - stroke;
        int sR = bgR + stroke;
        int sB = bgB + stroke;
        Gui.drawRect(sL, sT, sR, bgT, black);
        Gui.drawRect(sL, bgB, sR, sB, black);
        Gui.drawRect(sL, bgT, bgL, bgB, black);
        Gui.drawRect(bgR, bgT, sR, bgB, black);
    }

    private int strokeOuterLeft() {
        return panelX() - PANEL_BG_PAD - OUTSIDE_STROKE;
    }

    private int strokeOuterTop() {
        return panelY() - PANEL_BG_PAD - OUTSIDE_STROKE;
    }

    private int strokeOuterRight() {
        return panelX() + panelWidth() + PANEL_BG_PAD + OUTSIDE_STROKE;
    }

    private int strokeOuterBottom() {
        return panelY() + pickerH() + PANEL_BG_PAD + OUTSIDE_STROKE;
    }

    private void drawSvField(Minecraft mc, int startX, int startY) {
        int w = pickerW();
        int h = pickerH();
        if (!hudColorsPerformanceMode || mc == null) {
            drawSvFieldPixels(startX, startY, w, h);
            return;
        }
        ensureSvFieldCache(mc, w, h);
        if (svFieldTexture == null) {
            drawSvFieldPixels(startX, startY, w, h);
            return;
        }
        drawCachedTexture(mc, SV_FIELD_TEX, startX, startY, w, h);
    }

    private void drawSvFieldPixels(int startX, int startY, int w, int h) {
        for (int py = 0; py < h; py++) {
            float br = 1f - py / (float) (h - 1);
            for (int px = 0; px < w; px++) {
                float sat = px / (float) (w - 1);
                int c = 0xFF000000 | (Color.HSBtoRGB(hue, sat, br) & 0xFFFFFF);
                Gui.drawRect(startX + px, startY + py, startX + px + 1, startY + py + 1, c);
            }
        }
    }

    private void drawHueBar(Minecraft mc, int startX, int startY) {
        int h = pickerH();
        if (!hudColorsPerformanceMode || mc == null) {
            drawHueBarPixels(startX, startY, h);
            return;
        }
        ensureHueBarCache(mc, h);
        if (hueBarTexture == null) {
            drawHueBarPixels(startX, startY, h);
            return;
        }
        drawCachedTexture(mc, HUE_BAR_TEX, startX, startY, HUE_W, h);
    }

    private void drawHueBarPixels(int startX, int startY, int h) {
        for (int py = 0; py < h; py++) {
            float hueVal = py / (float) (h - 1);
            int c = 0xFF000000 | (Color.HSBtoRGB(hueVal, 1f, 1f) & 0xFFFFFF);
            Gui.drawRect(startX, startY + py, startX + HUE_W, startY + py + 1, c);
        }
    }

    private static void ensureHueBarCache(Minecraft mc, int h) {
        if (hueBarTexture != null && hueBarCacheH == h) {
            return;
        }
        BufferedImage img = new BufferedImage(HUE_W, h, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < h; py++) {
            float hueVal = py / (float) (h - 1);
            int c = 0xFF000000 | (Color.HSBtoRGB(hueVal, 1f, 1f) & 0xFFFFFF);
            for (int px = 0; px < HUE_W; px++) {
                img.setRGB(px, py, c);
            }
        }
        hueBarTexture = new DynamicTexture(img);
        mc.getTextureManager().loadTexture(HUE_BAR_TEX, hueBarTexture);
        hueBarCacheH = h;
    }

    private void ensureSvFieldCache(Minecraft mc, int w, int h) {
        if (svFieldTexture != null && svFieldCacheHue == hue && svFieldCacheW == w && svFieldCacheH == h) {
            return;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < h; py++) {
            float br = 1f - py / (float) (h - 1);
            for (int px = 0; px < w; px++) {
                float sat = px / (float) (w - 1);
                int c = 0xFF000000 | (Color.HSBtoRGB(hue, sat, br) & 0xFFFFFF);
                img.setRGB(px, py, c);
            }
        }
        svFieldTexture = new DynamicTexture(img);
        mc.getTextureManager().loadTexture(SV_FIELD_TEX, svFieldTexture);
        svFieldCacheHue = hue;
        svFieldCacheW = w;
        svFieldCacheH = h;
    }

    private static void drawCachedTexture(Minecraft mc, ResourceLocation tex, int x, int y, int w, int h) {
        try {
            mc.getTextureManager().bindTexture(tex);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            TEXTURE_DRAW.drawScaledCustomSizeModalRect(x, y, 0, 0, w, h, w, h, w, h);
            GlStateManager.disableBlend();
        } catch (Throwable ignored) {
        }
    }

    /**
     * When the HSV panel is open, consume every click on it before paint canvases / eraser (RMB) underneath.
     *
     * @return true if this picker handled or blocked the click
     */
    public boolean tryConsumeClick(Minecraft mc, int mouseX, int mouseY, int mouseButton) {
        if (!visible || !enabled) {
            return false;
        }
        if (isOpenOrAnimating() && isPointerOverOpenPanel(mouseX, mouseY)) {
            if (mouseButton == 0) {
                mouseClicked(mc, mouseX, mouseY, 0);
            }
            return true;
        }
        return mouseClicked(mc, mouseX, mouseY, mouseButton);
    }

    public boolean mouseClicked(Minecraft mc, int mouseX, int mouseY, int button) {
        if (!visible || !enabled || button != 0) {
            return false;
        }
        if (inlineMode) {
            if (containsOpenPanelPoint(mouseX, mouseY)) {
                float[] mapped = new float[2];
                mapMouseToPanelSpace(mouseX, mouseY, mapped);
                int panelX = panelX();
                int panelY = panelY();
                int pw = pickerW();
                int ph = pickerH();
                float mx = mapped[0];
                float my = mapped[1];
                if (mx >= panelX && mx <= panelX + pw && my >= panelY && my <= panelY + ph) {
                    draggingSv = true;
                    updateSv(mx, my);
                    return true;
                }
                int hueX = panelX + pw + 4;
                if (mx >= hueX && mx <= hueX + HUE_W && my >= panelY && my <= panelY + ph) {
                    draggingHue = true;
                    updateHue(my);
                    return true;
                }
                return true;
            }
            if (mouseX >= x && mouseX < x + BTN_SIZE && mouseY >= y && mouseY < y + BTN_SIZE) {
                return true;
            }
            return false;
        }
        if (mouseX >= x && mouseX < x + BTN_SIZE && mouseY >= y && mouseY < y + BTN_SIZE) {
            setExpanded(!expanded);
            return true;
        }

        if (isOpenOrAnimating() && containsOpenPanelPoint(mouseX, mouseY)) {
            float[] mapped = new float[2];
            mapMouseToPanelSpace(mouseX, mouseY, mapped);
            int panelX = panelX();
            int panelY = panelY();
            int pw = pickerW();
            int ph = pickerH();
            float mx = mapped[0];
            float my = mapped[1];
            if (mx >= panelX && mx <= panelX + pw && my >= panelY && my <= panelY + ph) {
                draggingSv = true;
                updateSv(mx, my);
                return true;
            }
            int hueX = panelX + pw + 4;
            if (mx >= hueX && mx <= hueX + HUE_W && my >= panelY && my <= panelY + ph) {
                draggingHue = true;
                updateHue(my);
                return true;
            }
            return true;
        }
        if (expanded && !retainOpenForEyedropper) {
            setExpanded(false);
        }
        return false;
    }

    private static final int PANEL_HIT_PAD = 4;

    private boolean containsOpenPanelPoint(int mouseX, int mouseY) {
        float[] mapped = new float[2];
        mapMouseToPanelSpace(mouseX, mouseY, mapped);
        int mx = (int) mapped[0];
        int my = (int) mapped[1];
        return mx >= strokeOuterLeft() - PANEL_HIT_PAD && mx <= strokeOuterRight() + PANEL_HIT_PAD
                && my >= strokeOuterTop() - PANEL_HIT_PAD && my <= strokeOuterBottom() + PANEL_HIT_PAD;
    }

    public void mouseReleased(int mouseX, int mouseY) {
        if (draggingSv || draggingHue) {
            draggingSv = false;
            draggingHue = false;
            if (listener != null) {
                listener.onColorChangeComplete(rgb);
            }
        }
    }

    public void mouseClickMove(int mouseX, int mouseY) {
        if (draggingSv) {
            float[] mapped = new float[2];
            mapMouseToPanelSpace(mouseX, mouseY, mapped);
            updateSv(mapped[0], mapped[1]);
        } else if (draggingHue) {
            float[] mapped = new float[2];
            mapMouseToPanelSpace(mouseX, mouseY, mapped);
            updateHue(mapped[1]);
        }
    }

    private void updateSv(float mouseX, float mouseY) {
        int panelX = panelX();
        int panelY = panelY();
        saturation = clamp01((mouseX - panelX) / (float) (pickerW() - 1));
        brightness = clamp01(1f - (mouseY - panelY) / (float) (pickerH() - 1));
        applyHsv();
    }

    private void updateHue(float mouseY) {
        int panelY = panelY();
        hue = clamp01((mouseY - panelY) / (float) (pickerH() - 1));
        applyHsv();
    }

    private void applyHsv() {
        rgb = Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
        if (listener != null) {
            listener.onColorChanged(rgb);
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }
}
