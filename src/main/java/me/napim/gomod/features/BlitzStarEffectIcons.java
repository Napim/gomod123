package me.napim.gomod.features;

import me.napim.gomod.util.GomodClasspathTextures;
import me.napim.gomod.util.HudGuiItemIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Blitz star HUD icons — custom PNGs where vanilla item rendering fails (e.g. Infection),
 * otherwise the same GUI item renderer as other stars.
 */
public final class BlitzStarEffectIcons {

    private static final int GUI_ITEM_PX = 16;
    private static final ResourceLocation INFECTION_ICON =
            new ResourceLocation("gomod123", "textures/hud/blitz/brewing_stand.png");

    private BlitzStarEffectIcons() {
    }

    public static void draw(Minecraft mc, ItemStack stack, int x, int y, int size) {
        draw(mc, stack, x, y, size, null);
    }

    public static void draw(Minecraft mc, ItemStack stack, int x, int y, int size,
            BlitzStarEffectsHudTracker.Effect effect) {
        if (mc == null) {
            return;
        }
        ResourceLocation custom = customTextureFor(effect);
        if (custom != null) {
            drawCustomTexture(mc, custom, x, y, size);
            return;
        }
        if (stack == null || stack.getItem() == null) {
            return;
        }
        int ox = x + Math.max(0, (size - GUI_ITEM_PX) / 2);
        int oy = y + Math.max(0, (size - GUI_ITEM_PX) / 2);
        HudGuiItemIcon.draw(mc, stack, ox, oy);
    }

    private static ResourceLocation customTextureFor(BlitzStarEffectsHudTracker.Effect effect) {
        if (effect == BlitzStarEffectsHudTracker.Effect.INFECTION) {
            return INFECTION_ICON;
        }
        return null;
    }

    private static void drawCustomTexture(Minecraft mc, ResourceLocation rl, int x, int y, int size) {
        try {
            GomodClasspathTextures.bindTexture(mc, rl);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, size, size, size, size);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }
}
