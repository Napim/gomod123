package me.napim.gomod.gui;

import me.napim.gomod.features.DisguiseBlitzKitSelectorOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Shared cached item icons for Blitz kits in GoStats screens. */
public final class BlitzKitIconRenderer {
    private static final Map<String, ItemStack> ICONS = new HashMap<String, ItemStack>();

    private BlitzKitIconRenderer() {
    }

    public static void draw(String kitName, int x, int y) {
        ItemStack icon = getIcon(kitName);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (icon == null || minecraft == null || minecraft.getRenderItem() == null) return;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableDepth();
            RenderHelper.enableGUIStandardItemLighting();
            minecraft.getRenderItem().renderItemAndEffectIntoGUI(icon, x, y);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    /** Draws a kit icon scaled to fit and centered inside the supplied selector cell. */
    public static void drawCentered(String kitName, int boxX, int boxY,
                                    int boxWidth, int boxHeight, float scale) {
        ItemStack icon = getIcon(kitName);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (icon == null || minecraft == null || minecraft.getRenderItem() == null || scale <= 0.0F) return;

        float iconSize = 16.0F * scale;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableDepth();
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.translate(boxX + (boxWidth - iconSize) / 2.0F,
                    boxY + (boxHeight - iconSize) / 2.0F, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            minecraft.getRenderItem().renderItemAndEffectIntoGUI(icon, 0, 0);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private static ItemStack getIcon(String kitName) {
        if (kitName == null || kitName.trim().isEmpty()) return null;
        String key = kitName.trim().toLowerCase(Locale.ROOT);
        synchronized (ICONS) {
            if (ICONS.containsKey(key)) return ICONS.get(key);
            ItemStack icon = DisguiseBlitzKitSelectorOverride.buildPreviewStack(kitName, 1);
            if (icon != null) ICONS.put(key, icon);
            return icon;
        }
    }
}
