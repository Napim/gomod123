package me.napim.gomod.features;

import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregate vault loot statistics for the Blitz Vault Items summary panels (armor material mix,
 * enchant counts, plain diamond rate). Recomputed from the loaded catalog whenever captures change.
 */
public final class BlitzVaultLootInsights {

    private static final Pattern ATTACK_DAMAGE_LINE = Pattern.compile(
            "\\+\\s*(\\d+(?:\\.\\d+)?)\\s+attack\\s+damage", Pattern.CASE_INSENSITIVE);
    private static final float[] ATTACK_DAMAGE_THRESHOLDS = {
            4f, 5f, 5.25f, 5.5f, 5.75f, 6f, 6.25f, 6.5f, 6.75f, 7f, 7.25f, 7.5f, 7.75f, 8f, 8.25f, 8.5f, 8.75f
    };

    public static final class Row {
        public final String label;
        /** 0–1 for summary bar fill. */
        public final float fraction;

        public Row(String label, float fraction) {
            this.label = label == null ? "" : label;
            this.fraction = Math.max(0f, Math.min(1f, fraction));
        }
    }

    public static final class Snapshot {
        public final List<Row> materialRows;
        public final List<Row> enchantCountRows;
        public final int armorSlotTotal;
        public final int vaultCaptures;

        public Snapshot(List<Row> materialRows, List<Row> enchantCountRows, int armorSlotTotal, int vaultCaptures) {
            this.materialRows = materialRows == null ? Collections.<Row>emptyList() : materialRows;
            this.enchantCountRows = enchantCountRows == null ? Collections.<Row>emptyList() : enchantCountRows;
            this.armorSlotTotal = Math.max(0, armorSlotTotal);
            this.vaultCaptures = Math.max(0, vaultCaptures);
        }

        public boolean hasData() {
            return vaultCaptures > 0 && armorSlotTotal > 0;
        }
    }

    private static volatile Snapshot cached = new Snapshot(Collections.<Row>emptyList(),
            Collections.<Row>emptyList(), 0, 0);
    private static volatile String cacheKey = "";

    private BlitzVaultLootInsights() {}

    public static Snapshot get() {
        rebuildIfNeeded();
        return cached;
    }

    public static void rebuildIfNeeded() {
        String key = buildCacheKey();
        if (key.equals(cacheKey)) {
            return;
        }
        cacheKey = key;
        cached = compute();
    }

    public static void invalidate() {
        cacheKey = "";
    }

    private static String buildCacheKey() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(BlitzVaultItemsData.getEntryCount()).append('|');
        sb.append(BlitzVaultItemsData.getObservedValidCaptures()).append('|');
        sb.append(BlitzVaultItemsData.getObservedSlotCaptures()).append('|');
        sb.append(BlitzVaultItemsData.getVaultChestCount()).append('|');
        sb.append(BlitzVaultItemsData.getSwordPatterns().size());
        return sb.toString();
    }

    private static void appendVaultPresenceRows(List<Row> materialRows, int vaultCaptures) {
        if (materialRows == null || vaultCaptures <= 0) {
            return;
        }
        int bowVaults = vaultsContainingCategory(BlitzVaultItemsData.CAT_BOW);
        int rodVaults = vaultsContainingCategory(BlitzVaultItemsData.CAT_ROD);
        if (bowVaults > 0) {
            materialRows.add(new Row("Bow in vault", fraction(bowVaults, vaultCaptures)));
        }
        if (rodVaults > 0) {
            materialRows.add(new Row("Fishing rod in vault", fraction(rodVaults, vaultCaptures)));
        }
        appendWeaponVaultRows(materialRows, vaultCaptures);
    }

    private static void appendWeaponVaultRows(List<Row> materialRows, int vaultCaptures) {
        List<BlitzVaultItemsData.VaultChest> chests = BlitzVaultItemsData.getVaultChests();
        if (chests == null || chests.isEmpty()) {
            appendWeaponVaultRowsFromPatterns(materialRows, vaultCaptures);
            return;
        }

        Map<String, BlitzVaultItemsData.Entry> catalog = buildCatalogLookup();
        int diamondSwordVaults = 0;
        int fireAspectOnePlus = 0;
        int fireAspectTwoPlus = 0;
        int[] attackVaultHits = new int[ATTACK_DAMAGE_THRESHOLDS.length];

        for (BlitzVaultItemsData.VaultChest chest : chests) {
            if (chest == null || chest.slots == null) {
                continue;
            }
            boolean plainDiamondSword = false;
            int maxFireAspect = 0;
            float maxAttack = 0f;
            for (BlitzVaultItemsData.VaultSlot slot : chest.slots) {
                if (slot == null || slot.id == null || slot.id.isEmpty()) {
                    continue;
                }
                String category = BlitzVaultItemsData.classifyItemCategory(slot.id);
                boolean sword = BlitzVaultItemsData.CAT_SWORD.equals(category);
                boolean axe = BlitzVaultItemsData.CAT_AXE.equals(category);
                if (!sword && !axe) {
                    continue;
                }
                BlitzVaultItemsData.Entry entry = catalog.get(catalogKey(slot.id, slot.nbtRaw));
                float attack = entry != null ? attackDamage(entry) : attackDamageFromTooltip(slot.fallbackTooltip);
                if (attack > maxAttack) {
                    maxAttack = attack;
                }
                if (sword) {
                    int fa = entry != null ? entry.fireAspectLevel : fireAspectFromNbt(slot.nbtRaw);
                    if (fa > maxFireAspect) {
                        maxFireAspect = fa;
                    }
                    if (isPlainDiamondSword(slot.id, entry, slot.nbtRaw)) {
                        plainDiamondSword = true;
                    }
                }
            }
            if (plainDiamondSword) {
                diamondSwordVaults++;
            }
            if (maxFireAspect >= 1) {
                fireAspectOnePlus++;
            }
            if (maxFireAspect >= 2) {
                fireAspectTwoPlus++;
            }
            for (int i = 0; i < ATTACK_DAMAGE_THRESHOLDS.length; i++) {
                if (maxAttack >= ATTACK_DAMAGE_THRESHOLDS[i]) {
                    attackVaultHits[i]++;
                }
            }
        }

        if (diamondSwordVaults > 0) {
            materialRows.add(new Row("Diamond sword in vault", fraction(diamondSwordVaults, vaultCaptures)));
        }
        if (fireAspectOnePlus > 0) {
            materialRows.add(new Row("Sword: Fire Aspect I+", fraction(fireAspectOnePlus, vaultCaptures)));
        }
        if (fireAspectTwoPlus > 0) {
            materialRows.add(new Row("Sword: Fire Aspect II+", fraction(fireAspectTwoPlus, vaultCaptures)));
        }
        for (int i = 0; i < ATTACK_DAMAGE_THRESHOLDS.length; i++) {
            if (attackVaultHits[i] > 0) {
                materialRows.add(new Row(formatAttackThresholdLabel(ATTACK_DAMAGE_THRESHOLDS[i]),
                        fraction(attackVaultHits[i], vaultCaptures)));
            }
        }
    }

    private static void appendWeaponVaultRowsFromPatterns(List<Row> materialRows, int vaultCaptures) {
        for (BlitzVaultItemsData.SwordPattern pattern : BlitzVaultItemsData.getSwordPatterns()) {
            if (pattern == null || pattern.label == null || pattern.count <= 0) {
                continue;
            }
            if ("Plain".equalsIgnoreCase(pattern.label.trim())) {
                materialRows.add(new Row("Diamond sword in vault", fraction(pattern.count, vaultCaptures)));
                return;
            }
        }
    }

    private static Map<String, BlitzVaultItemsData.Entry> buildCatalogLookup() {
        Map<String, BlitzVaultItemsData.Entry> out = new HashMap<String, BlitzVaultItemsData.Entry>();
        for (BlitzVaultItemsData.Entry entry : BlitzVaultItemsData.getAll()) {
            if (entry == null || entry.id == null) {
                continue;
            }
            out.put(catalogKey(entry.id, entry.nbtRaw), entry);
        }
        return out;
    }

    private static String catalogKey(String id, String nbtRaw) {
        return (id == null ? "" : id) + '\0' + (nbtRaw == null ? "" : nbtRaw);
    }

    private static boolean isPlainDiamondSword(String id, BlitzVaultItemsData.Entry entry, String nbtRaw) {
        if (id == null || !id.equalsIgnoreCase("minecraft:diamond_sword")) {
            return false;
        }
        if (entry != null) {
            return entry.enchantments == null || entry.enchantments.isEmpty();
        }
        return fireAspectFromNbt(nbtRaw) <= 0 && !nbtHasEnchantmentList(nbtRaw);
    }

    private static boolean nbtHasEnchantmentList(String nbtRaw) {
        if (nbtRaw == null || nbtRaw.isEmpty()) {
            return false;
        }
        String lower = nbtRaw.toLowerCase(Locale.US);
        return lower.contains("ench:") || lower.contains("\"ench\"");
    }

    private static int fireAspectFromNbt(String nbtRaw) {
        if (nbtRaw == null || nbtRaw.isEmpty()) {
            return 0;
        }
        int max = 0;
        Matcher chunkMatcher = Pattern.compile("\\d+:\\{([^}]+)\\}").matcher(nbtRaw);
        while (chunkMatcher.find()) {
            String chunk = chunkMatcher.group(1);
            Matcher idMatcher = Pattern.compile("id:(\\d+)s").matcher(chunk);
            Matcher lvlMatcher = Pattern.compile("lvl:(\\d+)s").matcher(chunk);
            if (!idMatcher.find() || !lvlMatcher.find()) {
                continue;
            }
            try {
                int eid = Integer.parseInt(idMatcher.group(1));
                int lvl = Integer.parseInt(lvlMatcher.group(1));
                if (eid == 20 && lvl > max) {
                    max = lvl;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }

    private static float attackDamage(BlitzVaultItemsData.Entry entry) {
        if (entry == null) {
            return 0f;
        }
        return attackDamageFromTooltip(entry.fallbackTooltip);
    }

    private static float attackDamageFromTooltip(List<String> tooltip) {
        if (tooltip == null) {
            return 0f;
        }
        float best = 0f;
        for (String line : tooltip) {
            if (line == null) {
                continue;
            }
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line).trim().toLowerCase(Locale.US);
            Matcher matcher = ATTACK_DAMAGE_LINE.matcher(plain);
            if (matcher.find()) {
                try {
                    best = Math.max(best, Float.parseFloat(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }

    private static String formatAttackThresholdLabel(float threshold) {
        if (Math.abs(threshold - Math.round(threshold)) < 0.001f) {
            return String.format(Locale.US, "+%.0f Attack Damage (sword/axe)", threshold);
        }
        return String.format(Locale.US, "+%.2f Attack Damage (sword/axe)", threshold);
    }

    private static int vaultsContainingCategory(String category) {
        if (category == null || category.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (BlitzVaultItemsData.VaultChest chest : BlitzVaultItemsData.getVaultChests()) {
            if (chest == null || chest.slots == null) {
                continue;
            }
            boolean found = false;
            for (BlitzVaultItemsData.VaultSlot slot : chest.slots) {
                if (slot == null || slot.id == null) {
                    continue;
                }
                if (category.equals(BlitzVaultItemsData.classifyItemCategory(slot.id))) {
                    found = true;
                    break;
                }
            }
            if (found) {
                hits++;
            }
        }
        return hits;
    }

    private static Snapshot compute() {
        int vaultCaptures = BlitzVaultItemsData.getObservedValidCaptures();
        if (vaultCaptures <= 0 || !BlitzVaultItemsData.isReady()) {
            return new Snapshot(Collections.<Row>emptyList(), Collections.<Row>emptyList(), 0, 0);
        }

        Map<String, Integer> tierSlots = new LinkedHashMap<String, Integer>();
        tierSlots.put(BlitzVaultItemsData.TIER_LEATHER, 0);
        tierSlots.put(BlitzVaultItemsData.TIER_CHAIN, 0);
        tierSlots.put(BlitzVaultItemsData.TIER_IRON, 0);
        tierSlots.put(BlitzVaultItemsData.TIER_GOLD, 0);
        tierSlots.put(BlitzVaultItemsData.TIER_DIAMOND, 0);

        int[] enchantSlots = new int[8];
        int plainDiamondVaultSum = 0;
        int plainDiamondPieceCount = 0;

        for (BlitzVaultItemsData.Entry entry : BlitzVaultItemsData.getAll()) {
            if (entry == null || !isArmorEntry(entry)) {
                continue;
            }
            int slots = Math.max(0, entry.observedSlotCount);
            if (slots <= 0) {
                continue;
            }
            String tier = entry.tier == null ? BlitzVaultItemsData.TIER_NONE : entry.tier;
            if (tierSlots.containsKey(tier)) {
                tierSlots.put(tier, tierSlots.get(tier) + slots);
            }
            int enchCount = entry.enchantments == null ? 0 : entry.enchantments.size();
            if (enchCount >= 0 && enchCount < enchantSlots.length) {
                enchantSlots[enchCount] += slots;
            }
            if (BlitzVaultItemsData.TIER_DIAMOND.equals(tier) && enchCount == 0) {
                plainDiamondVaultSum += Math.max(0, entry.observedCount);
                plainDiamondPieceCount++;
            }
        }

        int armorTotal = 0;
        for (Integer n : tierSlots.values()) {
            armorTotal += n == null ? 0 : n;
        }
        if (armorTotal <= 0) {
            return new Snapshot(Collections.<Row>emptyList(), Collections.<Row>emptyList(), 0, vaultCaptures);
        }

        List<Row> materialRows = new ArrayList<Row>();
        materialRows.add(new Row("Leather armor (slot share)", fraction(tierSlots.get(BlitzVaultItemsData.TIER_LEATHER), armorTotal)));
        materialRows.add(new Row("Chainmail armor (slot share)", fraction(tierSlots.get(BlitzVaultItemsData.TIER_CHAIN), armorTotal)));
        materialRows.add(new Row("Iron armor (slot share)", fraction(tierSlots.get(BlitzVaultItemsData.TIER_IRON), armorTotal)));
        materialRows.add(new Row("Golden armor (slot share)", fraction(tierSlots.get(BlitzVaultItemsData.TIER_GOLD), armorTotal)));
        materialRows.add(new Row("Diamond armor (slot share)", fraction(tierSlots.get(BlitzVaultItemsData.TIER_DIAMOND), armorTotal)));

        if (plainDiamondPieceCount > 0) {
            float plainVaultPct = (float) plainDiamondVaultSum / (float) (plainDiamondPieceCount * vaultCaptures);
            materialRows.add(new Row("Plain diamond in vault (avg piece)", plainVaultPct));
        }

        appendVaultPresenceRows(materialRows, vaultCaptures);

        List<Row> enchantRows = new ArrayList<Row>();
        for (int c = 0; c < enchantSlots.length; c++) {
            int n = enchantSlots[c];
            if (n <= 0) {
                continue;
            }
            String label = c == 0 ? "0 enchants (plain diamond)" : (c + " enchant" + (c == 1 ? "" : "s"));
            enchantRows.add(new Row(label, fraction(n, armorTotal)));
        }

        return new Snapshot(materialRows, enchantRows, armorTotal, vaultCaptures);
    }

    private static float fraction(int part, int total) {
        if (part <= 0 || total <= 0) {
            return 0f;
        }
        return (float) part / (float) total;
    }

    private static boolean isArmorEntry(BlitzVaultItemsData.Entry entry) {
        String cat = entry.category;
        return BlitzVaultItemsData.CAT_HELMET.equals(cat)
                || BlitzVaultItemsData.CAT_CHESTPLATE.equals(cat)
                || BlitzVaultItemsData.CAT_LEGGINGS.equals(cat)
                || BlitzVaultItemsData.CAT_BOOTS.equals(cat);
    }

    /** Percent label for GUI rows (no leading space). */
    public static String formatPercent(float fraction) {
        return String.format(Locale.US, "%.1f%%", fraction * 100.0D);
    }
}
