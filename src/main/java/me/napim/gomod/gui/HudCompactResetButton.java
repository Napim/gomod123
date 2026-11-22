package me.napim.gomod.gui;

import me.napim.gomod.util.HudGlState;
import me.napim.gomod.util.ScreenshotImageTextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

/**
 * Per-row reset control for HUD color settings — same chrome as {@link HsvColorPickerWidget}
 * (vanilla button frame + inset + centered icon).
 */
public final class HudCompactResetButton extends GuiButton {

    private static final Gui TEXTURE_DRAW = new Gui() {};
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final ResourceLocation RESET_ICON =
            new ResourceLocation("gomod123:textures/resetbutton10.png");
    private static final ResourceLocation RESET_ICON_FALLBACK =
            new ResourceLocation("gomod123:runtime/resetbutton10");

    private static final int BTN_SIZE = 20;
    private static final int DEFAULT_ICON_DRAW_SIZE = 12;

    private static boolean triedLoad;
    private static boolean loaded;
    private static int texW = 16;
    private static int texH = 16;

    public HudCompactResetButton(int id, int x, int y) {
        super(id, x, y, BTN_SIZE, BTN_SIZE, "");
    }

    public HudCompactResetButton(int id, int x, int y, int w, int h) {
        super(id, x, y, w, h, "");
    }

    private static void ensureLoaded(Minecraft mc) {
        if (triedLoad) {
            return;
        }
        triedLoad = true;
        if (mc == null) {
            return;
        }
        try {
            java.io.InputStream stream = null;
            if (mc.getResourceManager() != null) {
                try {
                    stream = mc.getResourceManager().getResource(RESET_ICON).getInputStream();
                } catch (Exception ignored) {
                }
            }
            if (stream == null) {
                stream = HudCompactResetButton.class
                        .getResourceAsStream("/assets/gomod123/textures/resetbutton10.png");
            }
            if (stream != null) {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(stream);
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
                if (img != null) {
                    java.awt.image.BufferedImage argb = ScreenshotImageTextureUtil.toArgbCopy(img);
                    texW = argb.getWidth();
                    texH = argb.getHeight();
                    DynamicTexture dyn = new DynamicTexture(argb);
                    mc.getTextureManager().loadTexture(RESET_ICON_FALLBACK, dyn);
                    loaded = true;
                }
            }
        } catch (Throwable ignored) {
            loaded = false;
        }
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        this.hovered = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width && mouseY < yPosition + height;

        int x = xPosition;
        int y = yPosition;
        boolean mouseOver = enabled && hovered;

        HudGlState.restoreForOverlayText();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableLighting();
        mc.getTextureManager().bindTexture(WIDGETS);

        int hoverState = pickerHoverState(mouseOver);
        int texV = 46 + hoverState * 20;
        float alpha = enabled ? 1.0f : 0.5f;

        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);
        int half = width / 2;
        int rightW = width - half;
        TEXTURE_DRAW.drawTexturedModalRect(x, y, 0, texV, half, height);
        TEXTURE_DRAW.drawTexturedModalRect(x + half, y, 200 - rightW, texV, rightW, height);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        drawInsetFill(x, y);
        if (!drawResetIcon(mc, x, y)) {
            String label = EnumChatFormatting.GRAY + "R";
            int tw = mc.fontRendererObj.getStringWidth(label);
            int tx = x + (width - tw) / 2;
            int ty = y + (height - 8) / 2;
            mc.fontRendererObj.drawString(label, tx, ty, enabled ? 0xFFDDDDDD : 0xFF888888);
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Same hover rows as {@link HsvColorPickerWidget#getPickerHoverState}. */
    private int pickerHoverState(boolean mouseOver) {
        if (!enabled) {
            return 0;
        }
        if (mouseOver) {
            return 2;
        }
        return 1;
    }

    private void drawInsetFill(int x, int y) {
        int inset = 2;
        int l = x + inset;
        int t = y + inset;
        int r = x + width - inset;
        int b = y + height - inset;
        int fill = enabled ? 0xFF2A2A2A : 0xFF1E1E1E;
        Gui.drawRect(l, t, r, b, fill);
        int stroke = 0xFF000000;
        Gui.drawRect(l - 1, t - 1, r + 1, t, stroke);
        Gui.drawRect(l - 1, b, r + 1, b + 1, stroke);
        Gui.drawRect(l - 1, t, l, b, stroke);
        Gui.drawRect(r, t, r + 1, b, stroke);
    }

    private boolean drawResetIcon(Minecraft mc, int x, int y) {
        ensureLoaded(mc);
        if (!loaded) {
            return false;
        }
        try {
            mc.getTextureManager().bindTexture(RESET_ICON_FALLBACK);
            int iconDrawSize = Math.max(10, Math.min(Math.min(width, height) - 6, DEFAULT_ICON_DRAW_SIZE));
            int ix = x + (width - iconDrawSize) / 2;
            int iy = y + (height - iconDrawSize) / 2;
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1.0f, 1.0f, 1.0f, enabled ? 0.92f : 0.5f);
            Gui.drawScaledCustomSizeModalRect(ix, iy, 0, 0, texW, texH, iconDrawSize, iconDrawSize, texW, texH);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
