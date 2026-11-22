package me.napim.gomod.features;

import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slot-weighted enchant <b>level</b> distributions (I / II / III …) per equipment group,
 * for {@link me.napim.gomod.gui.BlitzVaultEnchantLevelGUI}.
 */
public final class BlitzVaultEnchantLevelInsights {

    private static final int MIN_SLOT_OBS = 20;
    private static final Pattern ROMAN_OR_DIGIT_SUFFIX =
            Pattern.compile("\\s+(I{1,3}|IV|V|VI|VII|VIII|IX|X|\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final String[] EQUIP_ORDER = {
            "Armor", "Swords", "Axes", "Bows", "Pickaxes", "Shovels", "Fishing Rods"
    };

    public enum Skew {
        SINGLE_LEVEL,
        EVEN,
        MILD,
        FAVORS_TOP,
        HEAVY_SKEW
    }

    public static final class LevelRow {
        public final String equipGroup;
        public final String enchantName;
        /** level index 1..n → slot observations */
        public final int[] levelSlots;
        public final int totalSlots;
        public final Skew skew;

        LevelRow(String equipGroup, String enchantName, int[] levelSlots, int totalSlots, Skew skew) {
            this.equipGroup = equipGroup == null ? "" : equipGroup;
            this.enchantName = enchantName == null ? "" : enchantName;
            this.levelSlots = levelSlots == null ? new int[0] : levelSlots;
            this.totalSlots = Math.max(0, totalSlots);
            this.skew = skew == null ? Skew.HEAVY_SKEW : skew;
        }

        public int maxLevel() {
            return levelSlots.length - 1;
        }

        public int topLevel() {
            int best = 1;
            int bestN = 0;
            for (int lvl = 1; lvl < levelSlots.length; lvl++) {
                if (levelSlots[lvl] > bestN) {
                    bestN = levelSlots[lvl];
                    best = lvl;
                }
            }
            return best;
        }
    }

    public static final class Snapshot {
        public final List<LevelRow> rows;
        public final List<String> highlightLines;
        public final int vaultCaptures;

        public Snapshot(List<LevelRow> rows, List<String> highlightLines, int vaultCaptures) {
            this.rows = rows == null ? Collections.<LevelRow>emptyList() : rows;
            this.highlightLines = highlightLines == null ? Collections.<String>emptyList() : highlightLines;
            this.vaultCaptures = Math.max(0, vaultCaptures);
        }

        public boolean hasData() {
            return vaultCaptures > 0 && !rows.isEmpty();
        }
    }

    private static volatile Snapshot cached = new Snapshot(Collections.<LevelRow>emptyList(),
            Collections.<String>emptyList(), 0);
    private static volatile String cacheKey = "";

    private BlitzVaultEnchantLevelInsights() {}

    public static Snapshot get() {
        rebuildIfNeeded();
        return cached;
    }

    public static void invalidate() {
        cacheKey = "";
    }

    private static void rebuildIfNeeded() {
        String key = buildCacheKey();
        if (key.equals(cacheKey)) {
            return;
        }
        cacheKey = key;
        cached = compute();
    }

    private static String buildCacheKey() {
        return BlitzVaultItemsData.getEntryCount() + "|"
                + BlitzVaultItemsData.getObservedValidCaptures() + "|"
                + BlitzVaultItemsData.getObservedSlotCaptures();
    }

    private static Snapshot compute() {
        int vaultCaptures = BlitzVaultItemsData.getObservedValidCaptures();
        if (vaultCaptures <= 0 || !BlitzVaultItemsData.isReady()) {
            return new Snapshot(Collections.<LevelRow>emptyList(), Collections.<String>emptyList(), 0);
        }

        Map<String, Map<Integer, Integer>> buckets = new LinkedHashMap<String, Map<Integer, Integer>>();
        Map<String, String> bucketDisplayNames = new LinkedHashMap<String, String>();
        for (BlitzVaultItemsData.Entry entry : BlitzVaultItemsData.getAll()) {
            if (entry == null) {
                continue;
            }
            int slots = entry.observedSlotCount;
            if (slots <= 0) {
                continue;
            }
            if (isPlainDiamondArmor(entry)) {
                continue;
            }
            if (entry.enchantments == null || entry.enchantments.isEmpty()) {
                continue;
            }
            String group = equipGroup(entry.category);
            for (Map.Entry<String, Integer> ench : entry.enchantments.entrySet()) {
                if (ench.getKey() == null || ench.getKey().isEmpty()) {
                    continue;
                }
                int lvl = ench.getValue() == null ? 0 : ench.getValue();
                if (lvl <= 0) {
                    lvl = parseLevelFromSuffix(ench.getKey());
                }
                if (lvl <= 0) {
                    continue;
                }
                String base = normalizeEnchantBaseName(ench.getKey());
                String bucketKey = group + '\0' + base.toLowerCase(Locale.US);
                rememberDisplayName(bucketDisplayNames, bucketKey, base);
                Map<Integer, Integer> levels = buckets.get(bucketKey);
                if (levels == null) {
                    levels = new LinkedHashMap<Integer, Integer>();
                    buckets.put(bucketKey, levels);
                }
                Integer prev = levels.get(lvl);
                levels.put(lvl, (prev == null ? 0 : prev) + slots);
            }
        }

        List<LevelRow> rows = new ArrayList<LevelRow>();
        for (Map.Entry<String, Map<Integer, Integer>> bucket : buckets.entrySet()) {
            int sep = bucket.getKey().indexOf('\0');
            String group = sep >= 0 ? bucket.getKey().substring(0, sep) : "Other";
            String enchant = bucketDisplayNames.get(bucket.getKey());
            if (enchant == null || enchant.isEmpty()) {
                enchant = sep >= 0 ? capitalizeWords(bucket.getKey().substring(sep + 1)) : bucket.getKey();
            }
            Map<Integer, Integer> levels = bucket.getValue();
            int maxLvl = 1;
            for (Integer lvl : levels.keySet()) {
                if (lvl != null && lvl > maxLvl) {
                    maxLvl = lvl;
                }
            }
            int[] levelSlots = new int[maxLvl + 1];
            int total = 0;
            for (Map.Entry<Integer, Integer> row : levels.entrySet()) {
                if (row.getKey() == null || row.getKey() <= 0 || row.getValue() == null) {
                    continue;
                }
                int idx = row.getKey();
                if (idx >= levelSlots.length) {
                    levelSlots = extend(levelSlots, idx + 1);
                }
                levelSlots[idx] += row.getValue();
                total += row.getValue();
            }
            if (total < MIN_SLOT_OBS) {
                continue;
            }
            rows.add(new LevelRow(group, enchant, levelSlots, total, classifySkew(levelSlots, total)));
        }

        Collections.sort(rows, new Comparator<LevelRow>() {
            @Override
            public int compare(LevelRow a, LevelRow b) {
                int g = equipOrder(a.equipGroup) - equipOrder(b.equipGroup);
                if (g != 0) {
                    return g;
                }
                int s = skewOrder(a.skew) - skewOrder(b.skew);
                if (s != 0) {
                    return s;
                }
                return a.enchantName.compareToIgnoreCase(b.enchantName);
            }
        });

        return new Snapshot(rows, buildHighlights(rows), vaultCaptures);
    }

    private static int[] extend(int[] arr, int newLen) {
        int[] out = new int[newLen];
        System.arraycopy(arr, 0, out, 0, arr.length);
        return out;
    }

    private static List<String> buildHighlights(List<LevelRow> rows) {
        List<String> out = new ArrayList<String>();
        if (rows != null && !rows.isEmpty()) {
            out.add(EnumChatFormatting.GRAY + "Slot-weighted — each enchant on a piece counts separately.");
        }
        return out;
    }

    private static void rememberDisplayName(Map<String, String> names, String bucketKey, String candidate) {
        if (bucketKey == null || candidate == null || candidate.isEmpty()) {
            return;
        }
        String prev = names.get(bucketKey);
        if (prev == null || candidate.length() > prev.length()) {
            names.put(bucketKey, candidate);
        }
    }

    static int skewOrder(Skew skew) {
        if (skew == null) {
            return 99;
        }
        switch (skew) {
            case SINGLE_LEVEL:
                return 0;
            case EVEN:
                return 1;
            case MILD:
                return 2;
            case FAVORS_TOP:
                return 3;
            case HEAVY_SKEW:
                return 4;
            default:
                return 99;
        }
    }

    static Skew classifySkew(int[] levelSlots, int total) {
        int distinct = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        int topLvl = 1;
        int topN = 0;
        for (int lvl = 1; lvl < levelSlots.length; lvl++) {
            int n = levelSlots[lvl];
            if (n <= 0) {
                continue;
            }
            distinct++;
            min = Math.min(min, n);
            max = Math.max(max, n);
            if (n > topN) {
                topN = n;
                topLvl = lvl;
            }
        }
        if (distinct <= 1) {
            return Skew.SINGLE_LEVEL;
        }
        float ratio = max / (float) min;
        float topPct = topN / (float) total;
        if (ratio <= 1.25f) {
            return Skew.EVEN;
        }
        if (ratio <= 1.6f) {
            return Skew.MILD;
        }
        if (topLvl >= 3 && topPct >= 0.48f) {
            return topPct >= 0.55f || ratio >= 2.5f ? Skew.HEAVY_SKEW : Skew.FAVORS_TOP;
        }
        return Skew.HEAVY_SKEW;
    }

    public static String skewLabel(Skew skew, int topLevel) {
        switch (skew) {
            case SINGLE_LEVEL:
                return "single level only";
            case EVEN:
                return "even I–III";
            case MILD:
                return "mild skew";
            case FAVORS_TOP:
                return "favors " + roman(topLevel);
            case HEAVY_SKEW:
            default:
                return topLevel >= 3 ? "heavy III skew" : "skewed";
        }
    }

    public static EnumChatFormatting skewColor(Skew skew) {
        switch (skew) {
            case EVEN:
                return EnumChatFormatting.GREEN;
            case MILD:
                return EnumChatFormatting.YELLOW;
            case SINGLE_LEVEL:
                return EnumChatFormatting.GRAY;
            default:
                return EnumChatFormatting.RED;
        }
    }

    public static String formatLevelBreakdown(LevelRow row) {
        return formatLevelBreakdownColored(row);
    }

    /** Level breakdown with % colors matching skew (green even, yellow mild, red on dominant level when skewed). */
    public static String formatLevelBreakdownColored(LevelRow row) {
        if (row == null || row.totalSlots <= 0) {
            return "";
        }
        int topLvl = row.topLevel();
        StringBuilder sb = new StringBuilder();
        for (int lvl = 1; lvl < row.levelSlots.length; lvl++) {
            int n = row.levelSlots[lvl];
            if (n <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(EnumChatFormatting.AQUA).append(roman(lvl)).append(' ')
                    .append(percentFormatting(row.skew, lvl, topLvl))
                    .append(formatPercent(n, row.totalSlots));
        }
        return sb.toString();
    }

    private static EnumChatFormatting percentFormatting(Skew skew, int level, int topLevel) {
        if (skew == null) {
            return EnumChatFormatting.WHITE;
        }
        switch (skew) {
            case EVEN:
                return EnumChatFormatting.GREEN;
            case MILD:
                return EnumChatFormatting.YELLOW;
            case FAVORS_TOP:
            case HEAVY_SKEW:
                return level == topLevel ? EnumChatFormatting.RED : EnumChatFormatting.WHITE;
            case SINGLE_LEVEL:
            default:
                return EnumChatFormatting.GRAY;
        }
    }

    public static String formatPercent(int part, int total) {
        if (part <= 0 || total <= 0) {
            return "0%";
        }
        return String.format(Locale.US, "%.0f%%", 100.0D * part / (double) total);
    }

    public static String roman(int level) {
        switch (level) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            default: return String.valueOf(level);
        }
    }

    public static int equipOrder(String group) {
        if (group == null) {
            return 99;
        }
        for (int i = 0; i < EQUIP_ORDER.length; i++) {
            if (EQUIP_ORDER[i].equalsIgnoreCase(group)) {
                return i;
            }
        }
        return 98;
    }

    public static String[] equipGroupsInOrder() {
        return EQUIP_ORDER.clone();
    }

    private static boolean isPlainDiamondArmor(BlitzVaultItemsData.Entry entry) {
        return BlitzVaultItemsData.TIER_DIAMOND.equals(entry.tier)
                && (entry.enchantments == null || entry.enchantments.isEmpty());
    }

    private static String equipGroup(String category) {
        if (category == null) {
            return "Other";
        }
        if (BlitzVaultItemsData.CAT_HELMET.equals(category)
                || BlitzVaultItemsData.CAT_CHESTPLATE.equals(category)
                || BlitzVaultItemsData.CAT_LEGGINGS.equals(category)
                || BlitzVaultItemsData.CAT_BOOTS.equals(category)) {
            return "Armor";
        }
        if (BlitzVaultItemsData.CAT_SWORD.equals(category)) {
            return "Swords";
        }
        if (BlitzVaultItemsData.CAT_AXE.equals(category)) {
            return "Axes";
        }
        if (BlitzVaultItemsData.CAT_BOW.equals(category)) {
            return "Bows";
        }
        if (BlitzVaultItemsData.CAT_PICKAXE.equals(category)) {
            return "Pickaxes";
        }
        if (BlitzVaultItemsData.CAT_SHOVEL.equals(category)) {
            return "Shovels";
        }
        if (BlitzVaultItemsData.CAT_ROD.equals(category)) {
            return "Fishing Rods";
        }
        return "Other";
    }

    static String normalizeEnchantBaseName(String label) {
        String s = EnumChatFormatting.getTextWithoutFormattingCodes(label == null ? "" : label).trim();
        Matcher m = ROMAN_OR_DIGIT_SUFFIX.matcher(s);
        if (m.find()) {
            s = s.substring(0, m.start()).trim();
        }
        return s.isEmpty() ? label : s;
    }

    private static int parseLevelFromSuffix(String label) {
        String s = EnumChatFormatting.getTextWithoutFormattingCodes(label == null ? "" : label).trim();
        Matcher m = ROMAN_OR_DIGIT_SUFFIX.matcher(s);
        if (!m.find()) {
            return 0;
        }
        String token = m.group(1).toUpperCase(Locale.US);
        if ("I".equals(token)) return 1;
        if ("II".equals(token)) return 2;
        if ("III".equals(token)) return 3;
        if ("IV".equals(token)) return 4;
        if ("V".equals(token)) return 5;
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String capitalizeWords(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] parts = raw.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.US));
            }
        }
        return sb.toString();
    }
}
