package me.napim.gomod.util;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

/** Flat inventory tint that also composites correctly into Lunar's transparent HUD layers. */
public final class InventoryQolOverlay {
    private InventoryQolOverlay() {}

    public static void drawRect(int left, int top, int right, int bottom, int argb) {
        if (left == right || top == bottom || (argb >>> 24) == 0) {
            return;
        }
        float alpha = (argb >>> 24) / 255.0F;
        float red = ((argb >> 16) & 255) / 255.0F;
        float green = ((argb >> 8) & 255) / 255.0F;
        float blue = (argb & 255) / 255.0F;
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        // Gui.drawRect uses ONE/ZERO for alpha, replacing the background's coverage with the tint's
        // low alpha. Preserve existing coverage and add the tint on transparent HUD/framebuffer pixels.
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 771);
        GlStateManager.color(red, green, blue, alpha);
        try {
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer vertices = tessellator.getWorldRenderer();
            vertices.begin(7, DefaultVertexFormats.POSITION);
            vertices.pos(left, bottom, 0.0D).endVertex();
            vertices.pos(right, bottom, 0.0D).endVertex();
            vertices.pos(right, top, 0.0D).endVertex();
            vertices.pos(left, top, 0.0D).endVertex();
            tessellator.draw();
        } finally {
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
