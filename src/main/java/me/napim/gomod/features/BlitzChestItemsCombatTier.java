package me.napim.gomod.features;

import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.ResourceLocation;

/**
 * Groups Blitz chest catalog rows by vanilla combat stats (armor DR per slot, sword damage).
 */
public final class BlitzChestItemsCombatTier {

    private BlitzChestItemsCombatTier() {
    }

    /**
     * @return {@code armor:<slot>:<dr>} or {@code sword:<damage>}, or null if not tier-groupable
     */
    public static String combatTierGroupKey(BlitzChestItemsData.Entry entry) {
        if (entry == null || entry.stack == null || entry.stack.getItem() == null) {
            return null;
        }
        Item item = entry.stack.getItem();
        if (item instanceof ItemArmor) {
            ItemArmor armor = (ItemArmor) item;
            return "armor:" + armor.armorType + ":" + armor.damageReduceAmount;
        }
        if (item instanceof ItemSword) {
            int damage = swordAttackDamage(item);
            if (damage >= 0) {
                return "sword:" + damage;
            }
        }
        return null;
    }

    /** Vanilla 1.8 sword attack damage (wood and gold both 4). */
    private static int swordAttackDamage(Item item) {
        if (item == null || Item.itemRegistry == null) {
            return -1;
        }
        Object name = Item.itemRegistry.getNameForObject(item);
        if (!(name instanceof ResourceLocation)) {
            return -1;
        }
        String path = ((ResourceLocation) name).getResourcePath();
        if ("wooden_sword".equals(path) || "golden_sword".equals(path)) {
            return 4;
        }
        if ("stone_sword".equals(path)) {
            return 5;
        }
        if ("iron_sword".equals(path)) {
            return 6;
        }
        if ("diamond_sword".equals(path)) {
            return 7;
        }
        return -1;
    }

    public static String formatAggregatedDisplayName(String tierKey) {
        if (tierKey == null || tierKey.isEmpty()) {
            return BlitzChestItemsData.formatCombinationLabel("Item");
        }
        if (tierKey.startsWith("armor:")) {
            String[] parts = tierKey.split(":");
            if (parts.length >= 3) {
                int slot = parseInt(parts[1], 0);
                return BlitzChestItemsData.formatCombinationLabel(armorSlotLabel(slot));
            }
        }
        if (tierKey.startsWith("sword:")) {
            return BlitzChestItemsData.formatCombinationLabel("Sword");
        }
        return BlitzChestItemsData.formatCombinationLabel("Tier");
    }

    /** Tooltip lines for the control pill hover. */
    public static String[] controlPillTooltipLines() {
        return new String[] {
                "Combines armor with the same protection",
                "on that piece (chain / gold / iron helmet,",
                "leather / chain / gold boots, etc.).",
                "Combines swords with the same attack damage",
                "(wood sword and gold sword)."
        };
    }

    public static String describeTierMaterials(java.util.List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getItem() == null) {
                continue;
            }
            String id = Item.itemRegistry.getNameForObject(stack.getItem()).toString();
            names.add(BlitzChestItemsData.vanillaItemTypeDisplayName(id, stack.getMetadata()));
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** {@link net.minecraft.item.ItemArmor#armorType}: 0 helmet, 1 chestplate, 2 leggings, 3 boots. */
    private static String armorSlotLabel(int armorType) {
        switch (armorType) {
            case 0:
                return "Helmet";
            case 1:
                return "Chestplate";
            case 2:
                return "Leggings";
            case 3:
                return "Boots";
            default:
                return "Armor";
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

}
