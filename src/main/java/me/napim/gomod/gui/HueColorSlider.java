package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.function.IntSupplier;

/**
 * Full-width hue slider with inset stroke and vanilla thumb.
 * {@link Spectrum#FULL} adds smooth black/white ends (HUD text colors).
 * {@link Spectrum#HUE_RAINBOW} is saturation/value-locked rainbow only (barrier outlines).
 */
public class HueColorSlider extends GuiButton {
    public enum Spectrum {
        /** Black → rainbow → white (coords HUD and similar). */
        FULL,
        /** Full-saturation hues only — barrier outline tinting expects this. */
        HUE_RAINBOW
    }

    private static final int INSET_STROKE_PX = 2;
    private static final int STROKE_RGB = 0x000000;
    /** Left: black → full red via brightness (no gray→red cliff). */
    private static final float BLACK_ZONE = 0.09f;
    /** Right: full red → white via desaturation. */
    private static final float WHITE_ZONE = 0.10f;

    /** Cached 1×w gradient texture instead of per-frame GL strips (on by default). */
    private static boolean hudColorsPerformanceMode = true;
    private static final ResourceLocation PERF_GRAD_TEX =
            new ResourceLocation("gomod123", "runtime/hue_slider_grad");
    private static DynamicTexture perfGradTexture;
    private static int perfGradCacheW;
    private static int perfGradCacheSpectrum = -1;

    private final String labelPrefix;
    private final Spectrum spectrum;
    private float sliderPos;
    private int rgbOpaque;
    private boolean dragging;
    private IntSupplier labelColorSupplier;

    public HueColorSlider(int id, int x, int y, int w, int h, String labelPrefix) {
        this(id, x, y, w, h, labelPrefix, Spectrum.FULL);
    }

    public HueColorSlider(int id, int x, int y, int w, int h, String labelPrefix, Spectrum spectrum) {
        super(id, x, y, w, h, "");
        this.labelPrefix = labelPrefix == null ? "Color" : labelPrefix;
        this.spectrum = spectrum == null ? Spectrum.FULL : spectrum;
        this.rgbOpaque = 0xFF0000;
        this.sliderPos = 0f;
        refreshLabel();
    }

    public static void setHudColorsPerformanceMode(boolean enabled) {
        hudColorsPerformanceMode = enabled;
        if (!enabled) {
            perfGradCacheW = 0;
            perfGradCacheSpectrum = -1;
        }
    }

    public void syncFromRgb(int rgb) {
        this.sliderPos = rgbToPos(rgb);
        this.rgbOpaque = 0xFF000000 | (rgb & 0x00FFFFFF);
        refreshLabel();
    }

    public int getRgb() {
        return rgbOpaque & 0x00FFFFFF;
    }

    public HueColorSlider setLabelColorSupplier(IntSupplier supplier) {
        this.labelColorSupplier = supplier;
        return this;
    }

    private void refreshLabel() {
        this.displayString = labelPrefix + "  #" + String.format("%06X", getRgb());
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        int x0 = this.xPosition;
        int y0 = this.yPosition;
        int w = this.width;
        int h = this.height;
        float trackAlpha = this.enabled ? 1f : 0.42f;

        float gradAlpha = this.enabled ? 0.92f : 0.38f;
        if (hudColorsPerformanceMode) {
            drawPerfGradient(mc, x0, y0, w, h, gradAlpha);
        } else {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            int segs = Math.max(120, w * 2);
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            for (int i = 0; i <= segs; i++) {
                float t = (float) i / segs;
                int c = posToRgb(t);
                float r = ((c >> 16) & 0xFF) / 255f;
                float g = ((c >> 8) & 0xFF) / 255f;
                float b = (c & 0xFF) / 255f;
                float x = x0 + t * w;
                GL11.glColor4f(r, g, b, gradAlpha);
                GL11.glVertex2f(x, y0);
                GL11.glVertex2f(x, y0 + h);
            }
            GL11.glEnd();
            GL11.glShadeModel(GL11.GL_FLAT);
        }

        if (!this.enabled) {
            GlStateManager.color(0f, 0f, 0f, 0.45f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x0, y0 + h);
            GL11.glVertex2f(x0 + w, y0 + h);
            GL11.glVertex2f(x0 + w, y0);
            GL11.glVertex2f(x0, y0);
            GL11.glEnd();
        }

        drawInsetStroke(x0, y0, w, h, INSET_STROKE_PX, STROKE_RGB, this.enabled ? 1f : 0.42f);

        GlStateManager.enableTexture2D();
        mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
        int knobX = x0 + (int) (sliderPos * (w - 8));
        GlStateManager.color(1f, 1f, 1f, trackAlpha);
        this.drawTexturedModalRect(knobX, y0, 0, 66, 4, h);
        this.drawTexturedModalRect(knobX + 4, y0, 196, 66, 4, h);
        GlStateManager.color(1f, 1f, 1f, 1f);

        boolean hovered = mouseX >= x0 && mouseY >= y0 && mouseX < x0 + w && mouseY < y0 + h;
        int textColor;
        if (!this.enabled) {
            textColor = 0x808080;
        } else if (labelColorSupplier != null) {
            textColor = 0xFF000000 | (labelColorSupplier.getAsInt() & 0xFFFFFF);
        } else {
            textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
        }
        int tw = mc.fontRendererObj.getStringWidth(this.displayString);
        int tx = x0 + (w - tw) / 2;
        int ty = y0 + (h - 8) / 2;
        drawStringThinBlackOutline(mc.fontRendererObj, this.displayString, tx, ty, textColor);
    }

    private static void drawStringThinBlackOutline(net.minecraft.client.gui.FontRenderer fr, String text, int x,
            int y, int fillColor) {
        int outline = 0xFF000000;
        fr.drawString(text, x - 1, y, outline);
        fr.drawString(text, x + 1, y, outline);
        fr.drawString(text, x, y - 1, outline);
        fr.drawString(text, x, y + 1, outline);
        fr.drawString(text, x, y, fillColor);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible || !this.enabled) {
            return false;
        }
        if (mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height) {
            this.dragging = true;
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    public void dragTo(int mouseX) {
        if (!this.enabled) {
            return;
        }
        if (this.dragging) {
            updateFromMouse(mouseX);
        }
    }

    private static void drawInsetStroke(int x0, int y0, int w, int h, int strokePx, int rgb, float alpha) {
        if (strokePx <= 0 || w <= strokePx * 2 || h <= strokePx * 2) {
            return;
        }
        int a = Math.min(255, Math.max(0, Math.round(alpha * 255f)));
        int argb = (a << 24) | (rgb & 0xFFFFFF);
        int x1 = x0 + w;
        int y1 = y0 + h;
        int innerT = y0 + strokePx;
        int innerB = y1 - strokePx;
        int innerR = x1 - strokePx;
        Gui.drawRect(x0, y0, x1, y0 + strokePx, argb);
        Gui.drawRect(x0, y1 - strokePx, x1, y1, argb);
        Gui.drawRect(x0, innerT, x0 + strokePx, innerB, argb);
        Gui.drawRect(innerR, innerT, x1, innerB, argb);
    }

    private void updateFromMouse(int mouseX) {
        float t = (mouseX - this.xPosition) / (float) this.width;
        if (t < 0f) {
            t = 0f;
        }
        if (t > 1f) {
            t = 1f;
        }
        this.sliderPos = t;
        this.rgbOpaque = 0xFF000000 | (posToRgb(t) & 0x00FFFFFF);
        refreshLabel();
    }

    private static float hueSpanStart() {
        return BLACK_ZONE;
    }

    private static float hueSpanEnd() {
        return 1f - WHITE_ZONE;
    }

    private static float hueSpanWidth() {
        return hueSpanEnd() - hueSpanStart();
    }

    private int posToRgb(float t) {
        if (spectrum == Spectrum.HUE_RAINBOW) {
            return Color.HSBtoRGB(t, 1f, 1f) & 0xFFFFFF;
        }
        if (t <= BLACK_ZONE) {
            float u = smoothstep(t / BLACK_ZONE);
            return Color.HSBtoRGB(0f, 1f, u) & 0xFFFFFF;
        }
        if (t >= 1f - WHITE_ZONE) {
            float u = smoothstep((t - (1f - WHITE_ZONE)) / WHITE_ZONE);
            return Color.HSBtoRGB(1f, 1f - u, 1f) & 0xFFFFFF;
        }
        float hue = (t - hueSpanStart()) / hueSpanWidth();
        return Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF;
    }

    private float rgbToPos(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        float h = hsb[0];
        float s = hsb[1];
        float v = hsb[2];

        if (spectrum == Spectrum.HUE_RAINBOW) {
            if (s <= 0.05f) {
                return 0f;
            }
            return h;
        }

        if (v <= 0.04f) {
            return 0f;
        }
        if (s <= 0.04f && v >= 0.96f) {
            return 1f;
        }
        if (s <= 0.08f) {
            float lum = (r + g + b) / (3f * 255f);
            if (lum >= 0.92f) {
                return 1f - (1f - lum) / 0.08f * WHITE_ZONE;
            }
            if (lum <= 0.12f) {
                return lum / 0.12f * BLACK_ZONE;
            }
        }
        if (v < 0.35f && s > 0.5f) {
            return (v / 0.35f) * BLACK_ZONE * 0.85f;
        }
        return hueSpanStart() + h * hueSpanWidth();
    }

    private static float smoothstep(float u) {
        u = Math.max(0f, Math.min(1f, u));
        return u * u * (3f - 2f * u);
    }

    private void drawPerfGradient(Minecraft mc, int x0, int y0, int w, int h, float gradAlpha) {
        ensurePerfGradient(mc, w);
        if (perfGradTexture == null) {
            return;
        }
        try {
            mc.getTextureManager().bindTexture(PERF_GRAD_TEX);
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, gradAlpha);
            Gui.drawScaledCustomSizeModalRect(x0, y0, 0, 0, w, 1, w, h, w, 1);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    private void ensurePerfGradient(Minecraft mc, int w) {
        int spec = spectrum.ordinal();
        if (perfGradTexture != null && perfGradCacheW == w && perfGradCacheSpectrum == spec) {
            return;
        }
        int safeW = Math.max(1, w);
        BufferedImage img = new BufferedImage(safeW, 1, BufferedImage.TYPE_INT_ARGB);
        for (int px = 0; px < safeW; px++) {
            float t = safeW <= 1 ? 0f : px / (float) (safeW - 1);
            int c = 0xFF000000 | (posToRgb(t) & 0xFFFFFF);
            img.setRGB(px, 0, c);
        }
        perfGradTexture = new DynamicTexture(img);
        mc.getTextureManager().loadTexture(PERF_GRAD_TEX, perfGradTexture);
        perfGradCacheW = safeW;
        perfGradCacheSpectrum = spec;
    }
}
