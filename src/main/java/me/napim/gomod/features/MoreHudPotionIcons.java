package me.napim.gomod.features;

import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;

/**
 * Custom status-effect icons for 1.8.9 effects that have no vanilla HUD atlas sprite
 * (Instant Health, Instant Damage, Saturation).
 */
public final class MoreHudPotionIcons {

    private static final ResourceLocation INSTANT_HEALTH = new ResourceLocation("gomod123",
            "textures/hud/potions/instant_health.png");
    private static final ResourceLocation INSTANT_DAMAGE = new ResourceLocation("gomod123",
            "textures/hud/potions/instant_damage.png");
    private static final ResourceLocation SATURATION = new ResourceLocation("gomod123",
            "textures/hud/potions/saturation.png");

    /** Source PNG size (all three assets are 180×180). */
    private static final int TEX_W = 180;
    private static final int TEX_H = 180;

    private static final ResourceLocation INVENTORY_TEXTURE = new ResourceLocation(
            "textures/gui/container/inventory.png");

    /** 1px offsets — tinted icon passes form an outline that follows sprite alpha. */
    private static final int[][] POTION_ICON_STROKE_OFFSETS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    private MoreHudPotionIcons() {
    }

    public static ResourceLocation getCustomIcon(Potion potion) {
        if (potion == null) {
            return null;
        }
        return getCustomIcon(potion.id);
    }

    public static ResourceLocation getCustomIcon(int potionId) {
        if (potionId == Potion.heal.id) {
            return INSTANT_HEALTH;
        }
        if (potionId == Potion.harm.id) {
            return INSTANT_DAMAGE;
        }
        if (potionId == Potion.saturation.id) {
            return SATURATION;
        }
        return null;
    }

    public static boolean usesCustomIcon(Potion potion) {
        return getCustomIcon(potion) != null;
    }

    /**
     * Draws a status effect icon: custom PNG when needed, otherwise the vanilla inventory atlas sprite.
     */
    public static void drawPotionIcon(Minecraft mc, Potion potion, int x, int y, int size) {
        drawPotionIconWithStroke(mc, potion, x, y, size, 0, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Draws the icon with a colored outline that follows the sprite shape (not a square box).
     * Pass {@code strokeArgb} with alpha 0 to skip the outline and draw only the foreground tint.
     */
    public static void drawPotionIconWithStroke(Minecraft mc, Potion potion, int x, int y, int size, int strokeArgb,
            float iconR, float iconG, float iconB, float iconA) {
        if (mc == null || potion == null) {
            return;
        }
        try {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            int strokeAlpha = (strokeArgb >> 24) & 0xFF;
            if (strokeAlpha > 0) {
                float sr = ((strokeArgb >> 16) & 0xFF) / 255.0F;
                float sg = ((strokeArgb >> 8) & 0xFF) / 255.0F;
                float sb = (strokeArgb & 0xFF) / 255.0F;
                float sa = strokeAlpha / 255.0F;
                GlStateManager.color(sr, sg, sb, sa);
                for (int[] offset : POTION_ICON_STROKE_OFFSETS) {
                    drawPotionIconAt(mc, potion, x + offset[0], y + offset[1], size);
                }
            }
            GlStateManager.color(iconR, iconG, iconB, iconA);
            drawPotionIconAt(mc, potion, x, y, size);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    /** Draws one icon pass using the current {@link GlStateManager#color} tint. */
    private static void drawPotionIconAt(Minecraft mc, Potion potion, int x, int y, int size) {
        ResourceLocation custom = getCustomIcon(potion);
        if (custom != null) {
            drawCustomIconAt(mc, custom, x, y, size, TEX_W, TEX_H);
            return;
        }
        int iconIndex = potion.getStatusIconIndex();
        if (iconIndex < 0) {
            return;
        }
        int u = iconIndex % 8 * 18;
        int v = 198 + (iconIndex / 8) * 18;
        mc.getTextureManager().bindTexture(INVENTORY_TEXTURE);
        Gui.drawScaledCustomSizeModalRect(x, y, u, v, 18, 18, size, size, 256, 256);
    }

    public static void drawCustomIcon(Minecraft mc, ResourceLocation texture, int x, int y, int size) {
        drawCustomIcon(mc, texture, x, y, size, TEX_W, TEX_H);
    }

    public static void drawCustomIcon(Minecraft mc, ResourceLocation texture, int x, int y, int size, int texW,
            int texH) {
        if (mc == null || texture == null || texW <= 0 || texH <= 0) {
            return;
        }
        try {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            drawCustomIconAt(mc, texture, x, y, size, texW, texH);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    private static void drawCustomIconAt(Minecraft mc, ResourceLocation texture, int x, int y, int size, int texW,
            int texH) {
        GomodClasspathTextures.bindTexture(mc, texture);
        int tw = GomodClasspathTextures.getWidth(texture, texW);
        int th = GomodClasspathTextures.getHeight(texture, texH);
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, tw, th, size, size, tw, th);
    }
}
