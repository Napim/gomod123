package me.napim.gomod.features;



import net.minecraft.client.Minecraft;

import net.minecraft.client.entity.EntityPlayerSP;

import net.minecraft.client.gui.FontRenderer;

import net.minecraft.client.gui.ScaledResolution;

import net.minecraft.client.renderer.GlStateManager;

import net.minecraft.enchantment.Enchantment;

import net.minecraft.enchantment.EnchantmentHelper;

import net.minecraft.init.Items;

import net.minecraft.item.ItemPotion;

import net.minecraft.item.ItemStack;

import net.minecraft.nbt.NBTTagCompound;

import net.minecraft.nbt.NBTTagList;

import net.minecraft.potion.PotionEffect;

import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Locale;

import java.util.Map;



/**

 * Draws held-item info (enchantments, potion durations) above the hotbar item-name popup.

 */

public final class HotbarEnchantText {

    private static final int LINE_GAP = 1;

    private static final int POTION_ROW_GAP = 2;

    private static final float TEXT_SCALE = 0.85f;

    private static final int HELD_POTION_ICON_SIZE = 16;



    /** Combat enchants first, then utility — matches typical Blitz weapon readout order. */

    private static final int[] ENCHANT_DISPLAY_ORDER = new int[]{

            Enchantment.sharpness.effectId,

            Enchantment.smite.effectId,

            Enchantment.baneOfArthropods.effectId,

            Enchantment.knockback.effectId,

            Enchantment.fireAspect.effectId,

            Enchantment.protection.effectId,

            Enchantment.projectileProtection.effectId,

            Enchantment.blastProtection.effectId,

            Enchantment.fireProtection.effectId,

            Enchantment.featherFalling.effectId,

            Enchantment.thorns.effectId,

            Enchantment.power.effectId,

            Enchantment.punch.effectId,

            Enchantment.flame.effectId,

            Enchantment.infinity.effectId,

            Enchantment.efficiency.effectId,

            Enchantment.fortune.effectId,

            Enchantment.silkTouch.effectId,

            Enchantment.looting.effectId,

            Enchantment.lure.effectId,

            Enchantment.respiration.effectId,

            Enchantment.aquaAffinity.effectId,

            Enchantment.depthStrider.effectId,

            Enchantment.unbreaking.effectId,

    };



    private HotbarEnchantText() {

    }



    public static void render(ScaledResolution sr, int remainingHighlightTicks, ItemStack stack) {

        if (!ConfigManager.isHotbarEnchantTextEnabled()) {

            return;

        }

        if (remainingHighlightTicks <= 0 || stack == null) {

            return;

        }



        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.fontRendererObj == null) {

            return;

        }



        int scaledWidth = sr.getScaledWidth();

        int scaledHeight = sr.getScaledHeight();

        int nameY = scaledHeight - 59;

        if (mc.playerController != null && mc.playerController.gameIsSurvivalOrAdventure()) {

            nameY -= 14;

        }



        boolean fontSup = DisguiseRenamedItemMasker.beginRenamedMaskFontSuppression(stack);

        try {
            renderAtNameY(mc, mc.fontRendererObj, scaledWidth, nameY, stack);

        } finally {

            DisguiseRenamedItemMasker.endRenamedMaskFontSuppression(fontSup);

        }

    }

    /** Preview helper for GUI screens: uses the same spacing as the real held-item HUD above the item-name popup. */
    public static void renderPreviewAtNameY(Minecraft mc, int scaledWidth, int nameY, ItemStack stack) {
        if (mc == null || mc.fontRendererObj == null || stack == null) {
            return;
        }
        boolean fontSup = DisguiseRenamedItemMasker.beginRenamedMaskFontSuppression(stack);
        try {
            renderAtNameY(mc, mc.fontRendererObj, scaledWidth, nameY, stack);
        } finally {
            DisguiseRenamedItemMasker.endRenamedMaskFontSuppression(fontSup);
        }
    }

    private static void renderAtNameY(Minecraft mc, FontRenderer fontRenderer, int scaledWidth, int nameY,
            ItemStack stack) {
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.scale(TEXT_SCALE, TEXT_SCALE, 1.0f);

        float gapAboveName = ConfigManager.getHotbarEnchantTextGapAboveName();
        float bottomY = (nameY - gapAboveName) / TEXT_SCALE;

        if (stack.getItem() instanceof ItemPotion) {
            List<PotionEffect> effects = collectPotionEffects(stack);
            if (!effects.isEmpty()) {
                renderPotionEffects(mc, fontRenderer, scaledWidth, bottomY, effects);
            }
        } else {
            List<String> infoLines = buildEnchantInfoLines(stack);
            if (!infoLines.isEmpty()) {
                renderEnchantLines(fontRenderer, scaledWidth, bottomY, infoLines);
            }
        }

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }



    private static void renderEnchantLines(FontRenderer fontRenderer, int scaledWidth, float bottomY,

                                           List<String> infoLines) {
        int textColor = 0xFF000000 | (ConfigManager.getHotbarEnchantTextColor() & 0xFFFFFF);
        boolean shadow = ConfigManager.isHotbarEnchantTextShadow();

        float lineHeight = fontRenderer.FONT_HEIGHT + LINE_GAP;

        for (int i = 0; i < infoLines.size(); i++) {

            String line = toEnchantDisplayLine(infoLines.get(i));

            float y = bottomY - lineHeight * (infoLines.size() - 1 - i);

            float x = scaledWidth / (2.0f * TEXT_SCALE) - fontRenderer.getStringWidth(line) / 2.0f;

            drawHeldInfoText(fontRenderer, line, x, y, textColor, shadow);

        }

    }



    private static void renderPotionEffects(Minecraft mc, FontRenderer fr, int scaledWidth, float bottomY,

                                            List<PotionEffect> effects) {

        int durCol = MoreHudFeature.getPotionsDurationDrawRgb();
        boolean shadow = ConfigManager.isHotbarEnchantTextShadow();
        boolean textOnly = ConfigManager.isHotbarEnchantPotionTextOnly();

        int rowH = Math.max(textOnly ? 0 : HELD_POTION_ICON_SIZE, fr.FONT_HEIGHT * 2 + 2);

        float centerX = scaledWidth / (2.0f * TEXT_SCALE);

        int textColW = 0;

        int rowCount = 0;

        for (PotionEffect effect : effects) {

            if (effect == null) {

                continue;

            }

            rowCount++;

            String namePlain = MoreHudFeature.formatHeldItemPotionEffectName(effect);

            String durPlain = MoreHudFeature.formatHeldItemPotionDurationPlain(effect);

            textColW = Math.max(textColW, Math.max(fr.getStringWidth(namePlain), fr.getStringWidth(durPlain)));

        }

        if (rowCount == 0) {

            return;

        }



        int blockW = textColW + (textOnly ? 0 : HELD_POTION_ICON_SIZE + 4);

        float left = centerX - blockW / 2.0f;

        int iconLeft = Math.round(left);

        int tx = Math.round(left + (textOnly ? 0 : HELD_POTION_ICON_SIZE + 4));



        float totalH = rowH * rowCount + POTION_ROW_GAP * (rowCount - 1);

        float y = bottomY - totalH;



        for (PotionEffect effect : effects) {

            if (effect == null) {

                continue;

            }

            int potionId = effect.getPotionID();

            String namePlain = MoreHudFeature.formatHeldItemPotionEffectName(effect);

            String durPlain = MoreHudFeature.formatHeldItemPotionDurationPlain(effect);

            int nameCol = MoreHudFeature.heldItemPotionNameColor(potionId);



            int iconY = Math.round(y + (rowH - HELD_POTION_ICON_SIZE) / 2.0f);

            int textTop = Math.round(y + (rowH - (fr.FONT_HEIGHT * 2 + 1)) / 2.0f);

            if (!textOnly) {
                MoreHudFeature.drawPotionHudIcon(mc, potionId, iconLeft, iconY, HELD_POTION_ICON_SIZE);
            }

            drawHeldInfoText(fr, namePlain, tx, textTop, nameCol, shadow);

            drawHeldInfoText(fr, durPlain, tx, textTop + fr.FONT_HEIGHT + 1, durCol, shadow);



            y += rowH + POTION_ROW_GAP;

        }

    }



    private static List<PotionEffect> collectPotionEffects(ItemStack stack) {

        List<PotionEffect> lines = new ArrayList<>();

        if (stack == null || !(stack.getItem() instanceof ItemPotion)) {

            return lines;

        }

        try {

            List<PotionEffect> effects = Items.potionitem.getEffects(stack);

            if (effects != null) {

                for (PotionEffect effect : effects) {

                    if (effect != null) {

                        lines.add(effect);

                    }

                }

            }

        } catch (Throwable ignored) {

        }

        return lines;

    }



    private static List<String> buildEnchantInfoLines(ItemStack stack) {

        List<String> lines = new ArrayList<>();

        Map<Integer, Integer> enchantments = collectEnchantmentLevels(stack);

        if (!enchantments.isEmpty()) {

            List<String> formatted = formatEnchantmentLines(enchantments);

            if (!formatted.isEmpty()) {

                lines.addAll(formatted);

            }

        }

        if (lines.isEmpty()) {

            lines.addAll(buildEnchantLinesFromTooltip(stack));

        }

        return lines;

    }



    private static Map<Integer, Integer> collectEnchantmentLevels(ItemStack stack) {

        LinkedHashMap<Integer, Integer> levels = new LinkedHashMap<>();



        for (int enchantId : ENCHANT_DISPLAY_ORDER) {

            int level = EnchantmentHelper.getEnchantmentLevel(enchantId, stack);

            if (level > 0) {

                levels.put(enchantId, level);

            }

        }



        try {

            Map<Integer, Integer> helper = EnchantmentHelper.getEnchantments(stack);

            if (helper != null) {

                for (Map.Entry<Integer, Integer> entry : helper.entrySet()) {

                    if (entry.getValue() != null && entry.getValue() > 0) {

                        levels.put(entry.getKey(), entry.getValue());

                    }

                }

            }

        } catch (Throwable ignored) {

        }



        mergeEnchantmentTagList(levels, stack.getEnchantmentTagList());

        NBTTagCompound tag = stack.getTagCompound();

        if (tag != null && tag.hasKey("StoredEnchantments", 9)) {

            mergeEnchantmentTagList(levels, tag.getTagList("StoredEnchantments", 10));

        }

        return levels;

    }



    private static void mergeEnchantmentTagList(Map<Integer, Integer> levels, NBTTagList enchantments) {

        if (enchantments == null) {

            return;

        }

        for (int i = 0; i < enchantments.tagCount(); i++) {

            NBTTagCompound enchantment = enchantments.getCompoundTagAt(i);

            int id = enchantment.getShort("id") & 0xFFFF;

            int level = enchantment.getShort("lvl") & 0xFFFF;

            if (level > 0) {

                levels.put(id, Math.max(levels.getOrDefault(id, 0), level));

            }

        }

    }



    private static List<String> formatEnchantmentLines(Map<Integer, Integer> enchantments) {

        LinkedHashMap<Integer, Integer> ordered = new LinkedHashMap<>();

        for (int enchantId : ENCHANT_DISPLAY_ORDER) {

            Integer level = enchantments.get(enchantId);

            if (level != null && level > 0) {

                ordered.put(enchantId, level);

            }

        }

        for (Map.Entry<Integer, Integer> entry : enchantments.entrySet()) {

            if (!ordered.containsKey(entry.getKey()) && entry.getValue() != null && entry.getValue() > 0) {

                ordered.put(entry.getKey(), entry.getValue());

            }

        }



        List<String> lines = new ArrayList<>(ordered.size());

        for (Map.Entry<Integer, Integer> entry : ordered.entrySet()) {

            Enchantment enchantment = Enchantment.getEnchantmentById(entry.getKey());

            if (enchantment == null) {

                continue;

            }

            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(

                    enchantment.getTranslatedName(entry.getValue()));

            if (plain == null || plain.isEmpty()) {

                plain = EnumChatFormatting.getTextWithoutFormattingCodes(enchantment.getName())

                        + " "

                        + toRoman(entry.getValue());

            }

            lines.add(plain);

        }

        return lines;

    }



    private static String toEnchantDisplayLine(String line) {
        if (line == null) {
            return "";
        }
        String plain = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(line);
        return plain == null ? "" : plain.trim();

    }

    private static void drawHeldInfoText(FontRenderer fr, String text, float x, float y, int color, boolean shadow) {
        if (shadow) {
            fr.drawStringWithShadow(text, x, y, color);
        } else {
            fr.drawString(text, x, y, color, false);
        }
    }



    private static String toRoman(int level) {

        String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

        if (level >= 1 && level <= roman.length) {

            return roman[level - 1];

        }

        return String.valueOf(level);

    }



    private static List<String> buildEnchantLinesFromTooltip(ItemStack stack) {

        Minecraft mc = Minecraft.getMinecraft();

        EntityPlayerSP player = mc != null ? mc.thePlayer : null;

        if (player == null) {

            return new ArrayList<>();

        }



        List<String> tooltip;

        try {

            tooltip = stack.getTooltip(player, false);

        } catch (Throwable ignored) {

            return new ArrayList<>();

        }

        if (tooltip == null || tooltip.size() <= 1) {

            return new ArrayList<>();

        }



        List<String> lines = new ArrayList<>();

        for (int i = 1; i < tooltip.size(); i++) {

            String line = tooltip.get(i);

            if (line == null || line.isEmpty()) {

                continue;

            }

            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line).trim();

            if (plain.isEmpty() || isNonEnchantTooltipLine(plain)) {

                continue;

            }

            if (looksLikeEnchantLine(plain)) {

                lines.add(plain);

            }

        }

        return lines;

    }



    private static boolean isNonEnchantTooltipLine(String plain) {

        String lower = plain.toLowerCase(Locale.ROOT);

        return lower.startsWith("when in")

                || lower.contains("damage:")

                || lower.contains("durability:")

                || lower.contains("attack damage")

                || lower.contains("armor points")

                || lower.contains("blocks attack");

    }



    private static boolean looksLikeEnchantLine(String plain) {

        if (plain == null || plain.isEmpty()) {

            return false;

        }

        String base = stripTrailingEnchantLevel(plain.trim());

        if (base.isEmpty()) {

            return false;

        }

        String lowerBase = base.toLowerCase(Locale.ROOT);

        for (int enchantId : ENCHANT_DISPLAY_ORDER) {

            if (matchesEnchantmentName(enchantId, lowerBase)) {

                return true;

            }

        }

        for (int id = 0; id < 256; id++) {

            if (matchesEnchantmentName(id, lowerBase)) {

                return true;

            }

        }

        return false;

    }



    private static boolean matchesEnchantmentName(int enchantId, String lowerBase) {

        Enchantment enchantment = Enchantment.getEnchantmentById(enchantId);

        if (enchantment == null) {

            return false;

        }

        String enchantName = EnumChatFormatting.getTextWithoutFormattingCodes(enchantment.getTranslatedName(1));

        if (enchantName == null || enchantName.isEmpty()) {

            return false;

        }

        return lowerBase.equals(enchantName.toLowerCase(Locale.ROOT));

    }



    /** Removes trailing roman or numeric level from tooltip enchant lines (e.g. {@code Sharpness IV}). */

    private static String stripTrailingEnchantLevel(String plain) {

        return plain.replaceAll("\\s+(I{1,3}|IV|VI{0,3}|IX|X|\\d+)\\s*$", "").trim();

    }

}


