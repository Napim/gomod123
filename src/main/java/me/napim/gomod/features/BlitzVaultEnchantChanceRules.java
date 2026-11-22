package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Configurable vault enchant odds: {@linkplain ConfigManager#isBlitzVaultEnchantChanceExact() exact}
 * vs {@linkplain ConfigManager#isBlitzVaultEnchantChanceExact() at-least} level matching, optional
 * {@linkplain ConfigManager#getBlitzVaultEnchantChanceCombos() combo rows} (AND of multiple rules).
 */
public final class BlitzVaultEnchantChanceRules {

    private static final String[][] RULE_DEFS = {
            {"melee_fire_aspect_1", "Fire Aspect I", "MELEE", "fire_aspect", "1"},
            {"melee_fire_aspect_2", "Fire Aspect II", "MELEE", "fire_aspect", "2"},
            {"melee_sharpness_1", "Sharpness I", "MELEE", "sharpness", "1"},
            {"melee_sharpness_2", "Sharpness II", "MELEE", "sharpness", "2"},
            {"melee_sharpness_3", "Sharpness III", "MELEE", "sharpness", "3"},
            {"melee_knockback_1", "Knockback I", "MELEE", "knockback", "1"},
            {"melee_knockback_2", "Knockback II", "MELEE", "knockback", "2"},
            {"melee_unbreaking_1", "Unbreaking I", "MELEE", "unbreaking", "1"},
            {"melee_unbreaking_2", "Unbreaking II", "MELEE", "unbreaking", "2"},
            {"melee_unbreaking_3", "Unbreaking III", "MELEE", "unbreaking", "3"},
            {"melee_looting_1", "Looting I", "MELEE", "looting", "1"},
            {"melee_looting_2", "Looting II", "MELEE", "looting", "2"},
            {"melee_looting_3", "Looting III", "MELEE", "looting", "3"},
            {"bow_power_1", "Power I", "BOW", "power", "1"},
            {"bow_power_2", "Power II", "BOW", "power", "2"},
            {"bow_power_3", "Power III", "BOW", "power", "3"},
            {"bow_flame", "Flame", "BOW", "flame", "1"},
            {"bow_infinity", "Infinity", "BOW", "infinity", "1"},
            {"bow_punch_1", "Punch I", "BOW", "punch", "1"},
            {"bow_punch_2", "Punch II", "BOW", "punch", "2"},
            {"bow_unbreaking_1", "Unbreaking I", "BOW", "unbreaking", "1"},
            {"bow_unbreaking_2", "Unbreaking II", "BOW", "unbreaking", "2"},
            {"bow_unbreaking_3", "Unbreaking III", "BOW", "unbreaking", "3"},
            {"armor_protection_1", "Protection I", "ARMOR", "protection", "1"},
            {"armor_protection_2", "Protection II", "ARMOR", "protection", "2"},
            {"armor_protection_3", "Protection III", "ARMOR", "protection", "3"},
            {"armor_protection_4", "Protection IV", "ARMOR", "protection", "4"},
            {"armor_unbreaking_1", "Unbreaking I", "ARMOR", "unbreaking", "1"},
            {"armor_unbreaking_2", "Unbreaking II", "ARMOR", "unbreaking", "2"},
            {"armor_unbreaking_3", "Unbreaking III", "ARMOR", "unbreaking", "3"},
            {"armor_feather_falling_1", "Feather Falling I", "ARMOR", "feather_falling", "1"},
            {"armor_feather_falling_2", "Feather Falling II", "ARMOR", "feather_falling", "2"},
            {"armor_feather_falling_3", "Feather Falling III", "ARMOR", "feather_falling", "3"},
            {"armor_feather_falling_4", "Feather Falling IV", "ARMOR", "feather_falling", "4"},
            {"armor_depth_strider_1", "Depth Strider I", "ARMOR", "depth_strider", "1"},
            {"armor_depth_strider_2", "Depth Strider II", "ARMOR", "depth_strider", "2"},
            {"armor_depth_strider_3", "Depth Strider III", "ARMOR", "depth_strider", "3"},
            {"armor_thorns_1", "Thorns I", "ARMOR", "thorns", "1"},
            {"armor_thorns_2", "Thorns II", "ARMOR", "thorns", "2"},
            {"armor_thorns_3", "Thorns III", "ARMOR", "thorns", "3"},
            {"armor_projectile_protection_1", "Projectile Prot. I", "ARMOR", "projectile_protection", "1"},
            {"armor_fire_protection_1", "Fire Prot. I", "ARMOR", "fire_protection", "1"},
            {"armor_blast_protection_1", "Blast Prot. I", "ARMOR", "blast_protection", "1"},
            {"armor_respiration_1", "Respiration I", "ARMOR", "respiration", "1"},
            {"armor_respiration_2", "Respiration II", "ARMOR", "respiration", "2"},
            {"armor_respiration_3", "Respiration III", "ARMOR", "respiration", "3"},
    };

    private static final List<String> DEFAULT_ENABLED_KEYS = Arrays.asList(
            "melee_fire_aspect_1",
            "melee_fire_aspect_2",
            "melee_sharpness_1",
            "melee_sharpness_2",
            "melee_sharpness_3",
            "melee_knockback_1",
            "melee_knockback_2",
            "bow_power_1",
            "bow_power_2",
            "bow_power_3",
            "bow_flame"
    );

    private static final Map<String, RuleDef> RULE_BY_KEY = buildRuleIndex();

    private BlitzVaultEnchantChanceRules() {
    }

    public static List<RuleDef> getAllRules() {
        List<RuleDef> out = new ArrayList<RuleDef>();
        for (String[] def : RULE_DEFS) {
            RuleDef rule = RULE_BY_KEY.get(def[0]);
            if (rule != null) {
                out.add(rule);
            }
        }
        return out;
    }

    public static RuleDef getRule(String key) {
        return key == null ? null : RULE_BY_KEY.get(key);
    }

    public static List<String> resolveEnabledKeys() {
        List<String> configured = ConfigManager.getBlitzVaultEnchantChanceEnabledKeys();
        if (configured == null || configured.isEmpty()) {
            return new ArrayList<String>(DEFAULT_ENABLED_KEYS);
        }
        List<String> out = new ArrayList<String>();
        for (String key : configured) {
            if (key == null || "melee_fire_aspect_any".equals(key)) {
                continue;
            }
            if (RULE_BY_KEY.containsKey(key) && !out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    public static List<BlitzVaultItemsData.VaultEnchantChance> build(List<BlitzVaultItemsData.Entry> loaded,
            int observedValidCaptures, int observedSlotCaptures) {
        return build(loaded, observedValidCaptures, observedSlotCaptures,
                Collections.<String, Integer>emptyMap(), Collections.<String, Integer>emptyMap());
    }

    public static List<BlitzVaultItemsData.VaultEnchantChance> build(List<BlitzVaultItemsData.Entry> loaded,
            int observedValidCaptures, int observedSlotCaptures,
            Map<String, Integer> aggregateChestHits, Map<String, Integer> aggregateSlotHits) {
        if (loaded == null || loaded.isEmpty()) {
            return Collections.emptyList();
        }
        boolean exact = ConfigManager.isBlitzVaultEnchantChanceExact();
        List<BlitzVaultItemsData.VaultEnchantChance> out = new ArrayList<BlitzVaultItemsData.VaultEnchantChance>();
        for (String key : resolveEnabledKeys()) {
            RuleDef rule = RULE_BY_KEY.get(key);
            if (rule == null) {
                continue;
            }
            int[] counts = computeOdds(loaded, rule, exact, observedValidCaptures, observedSlotCaptures,
                    aggregateChestHits, aggregateSlotHits);
            if (counts[0] > 0 || counts[1] > 0) {
                out.add(new BlitzVaultItemsData.VaultEnchantChance(rule.displayLabel(exact), counts[0], counts[1]));
            }
        }
        for (String comboSpec : ConfigManager.getBlitzVaultEnchantChanceCombos()) {
            ComboSpec combo = ComboSpec.parse(comboSpec);
            if (combo == null) {
                continue;
            }
            int[] counts = computeComboOdds(loaded, combo, exact, observedValidCaptures, observedSlotCaptures);
            if (counts[0] > 0 || counts[1] > 0) {
                out.add(new BlitzVaultItemsData.VaultEnchantChance(combo.label, counts[0], counts[1]));
            }
        }
        return out;
    }

    public static boolean matchesEntry(BlitzVaultItemsData.Entry entry, String ruleKey, boolean exact) {
        RuleDef rule = RULE_BY_KEY.get(ruleKey);
        return rule != null && rule.matches(entry, exact);
    }

    public static boolean matchesAllKeys(BlitzVaultItemsData.Entry entry, List<String> ruleKeys, boolean exact) {
        if (entry == null || ruleKeys == null || ruleKeys.isEmpty()) {
            return false;
        }
        for (String key : ruleKeys) {
            if (!matchesEntry(entry, key, exact)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Chest and slot hits from variant stats: sum {@code observed_count} / sum {@code observed_slot_count}
     * among matching variants (capped to observed totals).
     *
     * This intentionally behaves like a "how often do we see this enchant anywhere in a vault"
     * approximation, not "max of any single item pattern".
     */
    private static int[] computeOdds(List<BlitzVaultItemsData.Entry> loaded, RuleDef rule, boolean exact,
            int observedValidCaptures, int observedSlotCaptures,
            Map<String, Integer> aggregateChestHits, Map<String, Integer> aggregateSlotHits) {
        int[] aggregate = computeAggregateOdds(rule, exact, observedValidCaptures, observedSlotCaptures,
                aggregateChestHits, aggregateSlotHits);
        if (aggregate != null) {
            return aggregate;
        }
        int chestSum = 0;
        int slotSum = 0;
        for (BlitzVaultItemsData.Entry entry : loaded) {
            if (entry == null || !rule.matches(entry, exact)) {
                continue;
            }
            chestSum += Math.max(0, entry.observedCount);
            slotSum += Math.max(0, entry.observedSlotCount);
        }
        if (observedValidCaptures > 0) {
            chestSum = Math.min(chestSum, observedValidCaptures);
        }
        if (observedSlotCaptures > 0) {
            slotSum = Math.min(slotSum, observedSlotCaptures);
        }
        return new int[] {chestSum, slotSum};
    }

    private static int[] computeAggregateOdds(RuleDef rule, boolean exact, int observedValidCaptures,
            int observedSlotCaptures, Map<String, Integer> aggregateChestHits, Map<String, Integer> aggregateSlotHits) {
        if (rule == null || aggregateChestHits == null || aggregateSlotHits == null
                || (aggregateChestHits.isEmpty() && aggregateSlotHits.isEmpty())) {
            return null;
        }
        if (!exact && "fire_aspect".equals(rule.stat) && rule.level <= 1) {
            int[] any = aggregateDirect("melee_fire_aspect_any", observedValidCaptures, observedSlotCaptures,
                    aggregateChestHits, aggregateSlotHits);
            if (any != null) {
                return any;
            }
        }
        if (exact || "flame".equals(rule.stat) || "infinity".equals(rule.stat)) {
            return aggregateDirect(rule.key, observedValidCaptures, observedSlotCaptures,
                    aggregateChestHits, aggregateSlotHits);
        }

        String prefix = levelKeyPrefix(rule);
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        boolean found = false;
        int chestMax = 0;
        int slotSum = 0;
        String keyPrefix = prefix + "_";
        for (Map.Entry<String, Integer> row : aggregateSlotHits.entrySet()) {
            String key = row.getKey();
            int level = parseAggregateLevel(key, keyPrefix);
            if (level < rule.level) {
                continue;
            }
            found = true;
            slotSum += Math.max(0, row.getValue() == null ? 0 : row.getValue());
            Integer chest = aggregateChestHits.get(key);
            if (chest != null) {
                chestMax = Math.max(chestMax, Math.max(0, chest));
            }
        }
        if (!found) {
            return null;
        }
        return new int[] {
                capToTotal(chestMax, observedValidCaptures),
                capToTotal(slotSum, observedSlotCaptures)
        };
    }

    private static int[] aggregateDirect(String key, int observedValidCaptures, int observedSlotCaptures,
            Map<String, Integer> aggregateChestHits, Map<String, Integer> aggregateSlotHits) {
        if (key == null || (!aggregateChestHits.containsKey(key) && !aggregateSlotHits.containsKey(key))) {
            return null;
        }
        return new int[] {
                capToTotal(valueOf(aggregateChestHits, key), observedValidCaptures),
                capToTotal(valueOf(aggregateSlotHits, key), observedSlotCaptures)
        };
    }

    private static int valueOf(Map<String, Integer> map, String key) {
        if (map == null || key == null) {
            return 0;
        }
        Integer value = map.get(key);
        return value == null ? 0 : Math.max(0, value);
    }

    private static int capToTotal(int count, int total) {
        count = Math.max(0, count);
        return total > 0 ? Math.min(count, total) : count;
    }

    private static String levelKeyPrefix(RuleDef rule) {
        String suffix = "_" + rule.level;
        if (rule.key != null && rule.key.endsWith(suffix)) {
            return rule.key.substring(0, rule.key.length() - suffix.length());
        }
        return rule.key;
    }

    private static int parseAggregateLevel(String key, String prefix) {
        if (key == null || prefix == null || !key.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(prefix.length()));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int[] computeComboOdds(List<BlitzVaultItemsData.Entry> loaded, ComboSpec combo, boolean exact,
            int observedValidCaptures, int observedSlotCaptures) {
        int chestSum = 0;
        int slotSum = 0;
        for (BlitzVaultItemsData.Entry entry : loaded) {
            if (entry == null || !matchesAllKeys(entry, combo.ruleKeys, exact)) {
                continue;
            }
            chestSum += Math.max(0, entry.observedCount);
            slotSum += Math.max(0, entry.observedSlotCount);
        }
        if (observedValidCaptures > 0) {
            chestSum = Math.min(chestSum, observedValidCaptures);
        }
        if (observedSlotCaptures > 0) {
            slotSum = Math.min(slotSum, observedSlotCaptures);
        }
        return new int[] {chestSum, slotSum};
    }

    private static Map<String, RuleDef> buildRuleIndex() {
        Map<String, RuleDef> out = new LinkedHashMap<String, RuleDef>();
        for (String[] def : RULE_DEFS) {
            out.put(def[0], new RuleDef(def[0], def[1], def[2], def[3], Integer.parseInt(def[4])));
        }
        return out;
    }

    public static final class RuleDef {
        public final String key;
        public final String baseLabel;
        public final Scope scope;
        public final String stat;
        public final int level;

        RuleDef(String key, String baseLabel, String scopeName, String stat, int level) {
            this.key = key;
            this.baseLabel = baseLabel;
            this.scope = Scope.valueOf(scopeName);
            this.stat = stat;
            this.level = level;
        }

        public String displayLabel(boolean exact) {
            if (exact || "flame".equals(stat) || "infinity".equals(stat)) {
                return baseLabel;
            }
            return baseLabel + "+";
        }

        boolean matches(BlitzVaultItemsData.Entry entry, boolean exact) {
            if (entry == null || !scope.applies(entry)) {
                return false;
            }
            int have = readStat(entry, stat);
            if ("flame".equals(stat) || "infinity".equals(stat)) {
                return exact ? have >= level : have >= level;
            }
            if (exact) {
                return have == level;
            }
            return have >= level;
        }
    }

    public enum Scope {
        MELEE, BOW, ARMOR;

        boolean applies(BlitzVaultItemsData.Entry entry) {
            switch (this) {
                case MELEE:
                    return BlitzVaultItemsData.CAT_SWORD.equals(entry.category)
                            || BlitzVaultItemsData.CAT_AXE.equals(entry.category);
                case BOW:
                    return BlitzVaultItemsData.CAT_BOW.equals(entry.category);
                case ARMOR:
                    return BlitzVaultItemsData.CAT_HELMET.equals(entry.category)
                            || BlitzVaultItemsData.CAT_CHESTPLATE.equals(entry.category)
                            || BlitzVaultItemsData.CAT_LEGGINGS.equals(entry.category)
                            || BlitzVaultItemsData.CAT_BOOTS.equals(entry.category);
                default:
                    return false;
            }
        }
    }

    public static final class ComboSpec {
        public final String label;
        public final List<String> ruleKeys;

        ComboSpec(String label, List<String> ruleKeys) {
            this.label = label == null ? "" : label.trim();
            this.ruleKeys = ruleKeys == null ? Collections.<String>emptyList() : ruleKeys;
        }

        /** {@code Label|rule_key,rule_key} */
        public static ComboSpec parse(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            int sep = trimmed.indexOf('|');
            if (sep <= 0 || sep >= trimmed.length() - 1) {
                return null;
            }
            String label = trimmed.substring(0, sep).trim();
            String[] keys = trimmed.substring(sep + 1).split(",");
            List<String> ruleKeys = new ArrayList<String>();
            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                key = key.trim();
                if (!key.isEmpty() && RULE_BY_KEY.containsKey(key) && !ruleKeys.contains(key)) {
                    ruleKeys.add(key);
                }
            }
            if (label.isEmpty() || ruleKeys.size() < 2) {
                return null;
            }
            return new ComboSpec(label, ruleKeys);
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder(label);
            sb.append('|');
            for (int i = 0; i < ruleKeys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ruleKeys.get(i));
            }
            return sb.toString();
        }
    }

    private static int readStat(BlitzVaultItemsData.Entry entry, String stat) {
        switch (stat) {
            case "sharpness":
                return entry.sharpnessLevel;
            case "fire_aspect":
                return entry.fireAspectLevel;
            case "knockback":
                return entry.knockbackLevel;
            case "power":
                return entry.powerLevel;
            case "flame":
                return entry.flameLevel;
            case "punch":
                return entry.punchLevel;
            case "infinity":
                return entry.infinityLevel;
            case "protection":
                return entry.protectionLevel;
            case "depth_strider":
                return entry.depthStriderLevel;
            case "feather_falling":
                return entry.featherFallingLevel;
            case "projectile_protection":
                return entry.projectileProtectionLevel;
            case "fire_protection":
                return entry.fireProtectionLevel;
            case "thorns":
                return entry.thornsLevel;
            case "blast_protection":
                return entry.blastProtectionLevel;
            case "respiration":
                return entry.respirationLevel;
            case "unbreaking":
                return entry.unbreakingLevel;
            case "looting":
                return enchantLevelFromMap(entry, "Looting");
            default:
                return enchantLevelFromMap(entry, stat);
        }
    }

    private static int enchantLevelFromMap(BlitzVaultItemsData.Entry entry, String baseName) {
        if (entry == null || entry.enchantments == null || baseName == null) {
            return 0;
        }
        int best = 0;
        String want = baseName.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (Map.Entry<String, Integer> en : entry.enchantments.entrySet()) {
            String label = stripEnchantBaseName(en.getKey());
            if (label.equalsIgnoreCase(baseName) || label.equalsIgnoreCase(want)) {
                best = Math.max(best, en.getValue() == null ? 0 : en.getValue());
            }
        }
        return best;
    }

    private static String stripEnchantBaseName(String label) {
        if (label == null) {
            return "";
        }
        String text = label.replaceAll("\u00a7.", "").trim();
        int idx = text.lastIndexOf(' ');
        if (idx > 0) {
            String last = text.substring(idx + 1).trim();
            if (isRomanNumeral(last)) {
                return text.substring(0, idx).trim();
            }
        }
        return text;
    }

    private static boolean isRomanNumeral(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != 'I' && c != 'V' && c != 'X') {
                return false;
            }
        }
        return true;
    }

    public static Set<String> defaultEnabledKeySet() {
        return new LinkedHashSet<String>(DEFAULT_ENABLED_KEYS);
    }
}
