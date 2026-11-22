package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.HotbarEnchantText;
import me.napim.gomod.util.HudGuiItemIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

final class HeldItemInfoPreviewRenderer {

    private static final ResourceLocation WIDGETS_TEXTURE = new ResourceLocation("textures/gui/widgets.png");
    private static final int HOTBAR_WIDTH = 182;
    private static final int HOTBAR_HEIGHT = 22;
    private static final int HOTBAR_SLOT_SIZE = 20;
    private static final int STATUS_ICON_SIZE = 9;
    private static final int STATUS_ICON_STEP = 8;
    private static final int XP_BAR_HEIGHT = 5;
    private static final int SAMPLE_XP_LEVEL = 18;
    private static final float SAMPLE_XP_PROGRESS = 0.72F;

    private HeldItemInfoPreviewRenderer() {
    }

    static void draw(Minecraft mc, FontRenderer fr, int screenWidth, int zoneLeft, int zoneTop, int zoneRight,
            int zoneBottom) {
        if (mc == null || fr == null || zoneRight <= zoneLeft || zoneBottom <= zoneTop) {
            return;
        }
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneBottom, 0x33000000);
        Gui.drawRect(zoneLeft, zoneTop, zoneRight, zoneTop + 1, 0x66AAAAAA);
        Gui.drawRect(zoneLeft, zoneBottom - 1, zoneRight, zoneBottom, 0x66AAAAAA);

        int centerX = (zoneLeft + zoneRight) / 2;
        int baseHotbarX = centerX - HOTBAR_WIDTH / 2;
        int baseHotbarY = zoneBottom - HOTBAR_HEIGHT - 8;
        int baseXpBarY = baseHotbarY - 10;
        int baseNameY = baseHotbarY - 51;

        HeldItemInfoPreviewDebugTuning.Offset hotbar = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.HOTBAR);
        HeldItemInfoPreviewDebugTuning.Offset xpBar = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.XP_BAR);
        HeldItemInfoPreviewDebugTuning.Offset xpLevel = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.XP_LEVEL);
        HeldItemInfoPreviewDebugTuning.Offset armor = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.ARMOR);
        HeldItemInfoPreviewDebugTuning.Offset hearts = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.HEARTS);
        HeldItemInfoPreviewDebugTuning.Offset food = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.FOOD);
        HeldItemInfoPreviewDebugTuning.Offset name = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.NAME);
        HeldItemInfoPreviewDebugTuning.Offset enchants = HeldItemInfoPreviewDebugTuning
                .get(HeldItemInfoPreviewDebugTuning.Part.ENCHANTS);

        int hotbarX = baseHotbarX + hotbar.x;
        int hotbarY = baseHotbarY + hotbar.y;
        int xpBarX = baseHotbarX + xpBar.x;
        int xpBarY = baseXpBarY + xpBar.y;
        int xpLevelCenterX = centerX + xpLevel.x;
        int xpLevelY = (baseXpBarY - 10) + xpLevel.y;
        int armorStartX = centerX - 91 + armor.x;
        int armorY = baseHotbarY - 29 + armor.y;
        int heartsStartX = centerX - 91 + hearts.x;
        int heartsY = baseHotbarY - 19 + hearts.y;
        int foodRightmostX = centerX + 91 - STATUS_ICON_SIZE + food.x;
        int foodY = baseHotbarY - 19 + food.y;
        int nameY = baseNameY + name.y;

        drawHotbarFrame(mc, hotbarX, hotbarY);
        drawXpBar(mc, fr, xpLevelCenterX, xpBarX, xpBarY, xpLevelY);
        drawStatusRows(mc, armorStartX, armorY, heartsStartX, heartsY, foodRightmostX, foodY);

        ItemStack sword = previewSword();
        String itemName = sword.getDisplayName();
        int nameX = centerX - fr.getStringWidth(itemName) / 2 + name.x;
        fr.drawStringWithShadow(itemName, nameX, nameY, 0xFFFFFF);
        GlStateManager.pushMatrix();
        GlStateManager.translate(enchants.x, enchants.y, 0.0F);
        try {
            HotbarEnchantText.renderPreviewAtNameY(mc, screenWidth, baseNameY, sword);
        } finally {
            GlStateManager.popMatrix();
        }

        if (HeldItemInfoPreviewDebugTuning.isOverlayEnabled()) {
            drawGuides(fr, centerX, hotbarX, hotbarY, xpBarX, xpBarY, xpLevelCenterX, xpLevelY,
                    armorStartX, armorY, heartsStartX, heartsY, foodRightmostX, foodY,
                    nameX, nameY, itemName, enchants.x, enchants.y);
        }
    }

    private static ItemStack previewSword() {
        ItemStack sword = new ItemStack(Items.diamond_sword);
        sword.addEnchantment(Enchantment.sharpness, 1);
        return sword;
    }

    private static void drawHotbarFrame(Minecraft mc, int hotbarX, int hotbarY) {
        mc.getTextureManager().bindTexture(WIDGETS_TEXTURE);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Gui.drawScaledCustomSizeModalRect(hotbarX, hotbarY, 0.0F, 0.0F, HOTBAR_WIDTH, HOTBAR_HEIGHT, HOTBAR_WIDTH,
                HOTBAR_HEIGHT, 256.0F, 256.0F);
        Gui.drawScaledCustomSizeModalRect(hotbarX - 1, hotbarY - 1, 0.0F, 22.0F, 24, 22, 24, 22, 256.0F, 256.0F);
        GlStateManager.disableBlend();

        int slotItemX = hotbarX + 3;
        int slotItemY = hotbarY + 3;
        HudGuiItemIcon.drawGuiScreenSlot(mc, previewSword(), slotItemX, slotItemY);
    }

    private static void drawXpBar(Minecraft mc, FontRenderer fr, int levelCenterX, int hotbarX, int xpBarY, int levelY) {
        mc.getTextureManager().bindTexture(Gui.icons);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Gui.drawScaledCustomSizeModalRect(hotbarX, xpBarY, 0.0F, 64.0F, HOTBAR_WIDTH, XP_BAR_HEIGHT, HOTBAR_WIDTH,
                XP_BAR_HEIGHT, 256.0F, 256.0F);
        int fillWidth = Math.max(0, Math.min(HOTBAR_WIDTH, Math.round(SAMPLE_XP_PROGRESS * 183.0F)));
        if (fillWidth > 0) {
            Gui.drawScaledCustomSizeModalRect(hotbarX, xpBarY, 0.0F, 69.0F, fillWidth, XP_BAR_HEIGHT, fillWidth,
                    XP_BAR_HEIGHT, 256.0F, 256.0F);
        }
        GlStateManager.disableBlend();

        String level = Integer.toString(SAMPLE_XP_LEVEL);
        int levelX = levelCenterX - fr.getStringWidth(level) / 2;
        drawBlackOutlineString(fr, level, levelX, levelY, 0x80FF20);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawStatusRows(Minecraft mc, int armorStartX, int armorY, int heartsStartX, int heartsY,
            int foodRightmostX, int foodY) {
        mc.getTextureManager().bindTexture(Gui.icons);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        drawStatusRowLeft(armorStartX, armorY, 16, 34, 25, 9, 20);
        drawStatusRowLeft(heartsStartX, heartsY, 16, 52, 61, 0, 20);
        drawStatusRowRight(foodRightmostX, foodY, 16, 52, 61, 27, 20);
        GlStateManager.disableBlend();
    }

    private static void drawStatusRowLeft(int startX, int y, int emptyU, int fullU, int halfU, int textureV,
            int filledValue) {
        for (int i = 0; i < 10; i++) {
            int x = startX + i * STATUS_ICON_STEP;
            Gui.drawScaledCustomSizeModalRect(x, y, emptyU, textureV, 9, 9, STATUS_ICON_SIZE, STATUS_ICON_SIZE,
                    256.0F, 256.0F);
            int iconValue = filledValue - i * 2;
            if (iconValue >= 2) {
                Gui.drawScaledCustomSizeModalRect(x, y, fullU, textureV, 9, 9, STATUS_ICON_SIZE, STATUS_ICON_SIZE,
                        256.0F, 256.0F);
            } else if (iconValue == 1) {
                Gui.drawScaledCustomSizeModalRect(x, y, halfU, textureV, 9, 9, STATUS_ICON_SIZE, STATUS_ICON_SIZE,
                        256.0F, 256.0F);
            }
        }
    }

    private static void drawStatusRowRight(int rightmostX, int y, int emptyU, int fullU, int halfU, int textureV,
            int filledValue) {
        for (int i = 0; i < 10; i++) {
            int x = rightmostX - i * STATUS_ICON_STEP;
            Gui.drawScaledCustomSizeModalRect(x, y, emptyU, textureV, 9, 9, STATUS_ICON_SIZE, STATUS_ICON_SIZE,
                    256.0F, 256.0F);
            int iconValue = filledValue - i * 2;
            if (iconValue >= 2) {
                Gui.drawScaledCustomSizeModalRect(x, y, fullU, textureV, 9, 9, STATUS_ICON_SIZE, STATUS_ICON_SIZE,
                        256.0F, 256.0F);
            } else if (iconValue == 1) {
                Gui.drawScaledCustomSizeModalRect(x, y, halfU, textureV, 9, 9, STATUS_ICON_SIZE, STATUS_ICON_SIZE,
                        256.0F, 256.0F);
            }
        }
    }

    private static void drawBlackOutlineString(FontRenderer fr, String text, int x, int y, int rgb) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                if (ox == 0 && oy == 0) {
                    continue;
                }
                fr.drawString(text, x + ox, y + oy, 0xFF000000, false);
            }
        }
        fr.drawString(text, x, y, 0xFF000000 | (rgb & 0xFFFFFF), false);
    }

    private static void drawGuides(FontRenderer fr, int centerX, int hotbarX, int hotbarY, int xpBarX, int xpBarY,
            int xpLevelCenterX, int xpLevelY, int armorStartX, int armorY, int heartsStartX, int heartsY,
            int foodRightmostX, int foodY, int nameX, int nameY, String itemName, int enchantDx, int enchantDy) {
        drawGuide(fr, hotbarX, hotbarY, hotbarX + HOTBAR_WIDTH, hotbarY + HOTBAR_HEIGHT, 0x90FFD54F, "hotbar");
        drawGuide(fr, xpBarX, xpBarY, xpBarX + HOTBAR_WIDTH, xpBarY + XP_BAR_HEIGHT, 0x9066BB6A, "xpbar");

        String level = Integer.toString(SAMPLE_XP_LEVEL);
        int levelW = fr.getStringWidth(level);
        drawGuide(fr, xpLevelCenterX - levelW / 2, xpLevelY, xpLevelCenterX + (levelW + 1) / 2,
                xpLevelY + fr.FONT_HEIGHT, 0x904FC3F7, "xplevel");

        int rowWidth = STATUS_ICON_STEP * 9 + STATUS_ICON_SIZE;
        drawGuide(fr, armorStartX, armorY, armorStartX + rowWidth, armorY + STATUS_ICON_SIZE, 0x90BA68C8, "armor");
        drawGuide(fr, heartsStartX, heartsY, heartsStartX + rowWidth, heartsY + STATUS_ICON_SIZE, 0x90EF5350, "hearts");
        drawGuide(fr, foodRightmostX - rowWidth + STATUS_ICON_SIZE, foodY, foodRightmostX + STATUS_ICON_SIZE, foodY + STATUS_ICON_SIZE,
                0x90FFB74D, "food");

        int nameW = fr.getStringWidth(itemName);
        drawGuide(fr, nameX, nameY, nameX + nameW, nameY + fr.FONT_HEIGHT, 0x90FFFFFF, "name");

        String enchant = "Sharpness I";
        int enchantW = Math.round(fr.getStringWidth(enchant) * 0.85F);
        int enchantH = Math.max(1, Math.round(fr.FONT_HEIGHT * 0.85F));
        int enchantX = centerX - enchantW / 2 + enchantDx;
        int enchantY = nameY - 2 + enchantDy;
        drawGuide(fr, enchantX, enchantY, enchantX + enchantW, enchantY + enchantH, 0x9000BCD4, "enchants");
    }

    private static void drawGuide(FontRenderer fr, int left, int top, int right, int bottom, int color, String label) {
        if (right <= left || bottom <= top) {
            return;
        }
        int fill = (color & 0x00FFFFFF) | 0x25000000;
        Gui.drawRect(left, top, right, bottom, fill);
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top + 1, left + 1, bottom - 1, color);
        Gui.drawRect(right - 1, top + 1, right, bottom - 1, color);
        fr.drawStringWithShadow(label, left + 2, top - 9, color & 0xFFFFFF);
    }
}
