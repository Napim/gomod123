package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

/** Chat speech bubbles drawn in the gomod Social panel style. */
public final class GomodSocialSpeechBubbleTextures {

    private GomodSocialSpeechBubbleTextures() {}

    public static void preload(Minecraft mc) {
        // Procedural bubble renderer; no PNG preload needed.
    }

    public static boolean draw(boolean self, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        float radius = Math.min(6.0F, Math.max(4.0F, height * 0.22F));
        int left = x;
        int top = y;
        int right = x + width;
        int bottom = y + height;
        if (self) {
            SocialDraw.roundedBorder(left, top, right, bottom, radius, SocialTheme.BUBBLE_SELF,
                    SocialDraw.withAlpha(SocialTheme.ACCENT_BRIGHT, 120));
        } else {
            SocialDraw.roundedBorder(left, top, right, bottom, radius, SocialTheme.BUBBLE_OTHER,
                    SocialTheme.BORDER_SUBTLE);
        }
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        return true;
    }
}
