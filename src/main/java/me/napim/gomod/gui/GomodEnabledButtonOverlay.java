package me.napim.gomod.gui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Green selection overlay + border animation (Map Queuer style) for Enabled module toggles.
 */
public final class GomodEnabledButtonOverlay {

    public static final long ANIMATION_DURATION_MS = 800L;

    private final Map<String, Long> animationStart = new ConcurrentHashMap<String, Long>();

    public void startAnimation(String key) {
        if (key != null) {
            animationStart.put(key, System.currentTimeMillis());
        }
    }

    public void clearAnimation(String key) {
        if (key != null) {
            animationStart.remove(key);
        }
    }

    /** Draw overlay on {@code button} when {@code enabled} is true (call after {@code super.drawScreen}). */
    public void draw(String key, GuiButton button, boolean enabled) {
        draw(key, button, enabled, 1.0F);
    }

    /** Draw overlay with caller-provided opacity so scroll-faded buttons and overlays stay in sync. */
    public void draw(String key, GuiButton button, boolean enabled, float alpha) {
        if (button == null || !button.visible || !enabled) {
            return;
        }
        alpha = Math.max(0.0F, Math.min(1.0F, alpha));
        if (alpha <= 0.01F) {
            return;
        }
        int x = button.xPosition;
        int y = button.yPosition;
        int w = button.width;
        int h = button.height;
        if (animationStart.containsKey(key)) {
            drawAnimated(x, y, w, h, key, alpha);
        } else {
            drawStatic(x, y, w, h, alpha);
        }
    }

    public static void drawStatic(int x, int y, int width, int height) {
        drawStatic(x, y, width, height, 1.0F);
    }

    public static void drawStatic(int x, int y, int width, int height, float alpha) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableDepth();
            GlStateManager.translate(0, 0, 300);
            GlStateManager.enableBlend();
            Gui.drawRect(x, y, x + width, y + height, withAlpha(0x204CAF50, alpha));
            Gui.drawRect(x, y, x + width, y + 2, withAlpha(0xFF4CAF50, alpha));
            Gui.drawRect(x, y, x + 2, y + height, withAlpha(0xFF4CAF50, alpha));
            Gui.drawRect(x, y + height - 2, x + width, y + height, withAlpha(0xFF4CAF50, alpha));
            Gui.drawRect(x + width - 2, y, x + width, y + height, withAlpha(0xFF4CAF50, alpha));
        } finally {
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    public void drawAnimated(int x, int y, int width, int height, String key) {
        drawAnimated(x, y, width, height, key, 1.0F);
    }

    public void drawAnimated(int x, int y, int width, int height, String key, float alpha) {
        Long startTime = animationStart.get(key);
        if (startTime == null) {
            drawStatic(x, y, width, height, alpha);
            return;
        }

        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableDepth();
            GlStateManager.translate(0, 0, 300);
            GlStateManager.enableBlend();

            int fill = withAlpha(0x204CAF50, alpha);
            int border = withAlpha(0xFF4CAF50, alpha);
            Gui.drawRect(x, y, x + width, y + height, fill);

        long elapsed = System.currentTimeMillis() - startTime;
        float progress = Math.min(1.0f, (float) elapsed / ANIMATION_DURATION_MS);
        int borderThickness = 2;
        float leftRightProgress = Math.min(1.0f, progress * 2.0f);
        float topBottomProgress = Math.max(0.0f, (progress - 0.5f) * 2.0f);

        int leftCenterY = y + height / 2;
        int leftMaxPixels = height / 2;
        int leftUpPixels = (int) (leftMaxPixels * leftRightProgress);
        int leftDownPixels = (int) (leftMaxPixels * leftRightProgress);
        if (leftUpPixels > 0) {
            Gui.drawRect(x, leftCenterY - leftUpPixels, x + borderThickness, leftCenterY, border);
        }
        if (leftDownPixels > 0) {
            Gui.drawRect(x, leftCenterY, x + borderThickness, leftCenterY + leftDownPixels, border);
        }

        int rightCenterY = y + height / 2;
        int rightMaxPixels = height / 2;
        int rightUpPixels = (int) (rightMaxPixels * leftRightProgress);
        int rightDownPixels = (int) (rightMaxPixels * leftRightProgress);
        if (rightUpPixels > 0) {
            Gui.drawRect(x + width - borderThickness, rightCenterY - rightUpPixels, x + width, rightCenterY,
                    border);
        }
        if (rightDownPixels > 0) {
            Gui.drawRect(x + width - borderThickness, rightCenterY, x + width, rightCenterY + rightDownPixels,
                    border);
        }

        int topMaxPixels = width / 2;
        int topLeftPixels = (int) (topMaxPixels * topBottomProgress);
        int topRightPixels = (int) (topMaxPixels * topBottomProgress);
        if (topLeftPixels > 0) {
            Gui.drawRect(x, y, x + topLeftPixels, y + borderThickness, border);
        }
        if (topRightPixels > 0) {
            Gui.drawRect(x + width - topRightPixels, y, x + width, y + borderThickness, border);
        }

        int bottomMaxPixels = width / 2;
        int bottomLeftPixels = (int) (bottomMaxPixels * topBottomProgress);
        int bottomRightPixels = (int) (bottomMaxPixels * topBottomProgress);
        if (bottomLeftPixels > 0) {
            Gui.drawRect(x, y + height - borderThickness, x + bottomLeftPixels, y + height, border);
        }
        if (bottomRightPixels > 0) {
            Gui.drawRect(x + width - bottomRightPixels, y + height - borderThickness, x + width, y + height,
                    border);
        }

            if (progress >= 1.0f) {
                animationStart.remove(key);
            }
        } finally {
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    private static int withAlpha(int argb, float alphaMultiplier) {
        int alpha = (argb >>> 24) & 0xFF;
        int scaledAlpha = Math.max(0, Math.min(255, Math.round(alpha * alphaMultiplier)));
        return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
    }
}
