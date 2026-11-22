package me.napim.gomod.features;

import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Expected middle-chest loot totals from {@link BlitzChestItemsData} observed slot rates.
 */
public final class BlitzChestLootEstimator {

    public static final class EstimateLine {
        public final String label;
        public final float expectedTotal;

        public EstimateLine(String label, float expectedTotal) {
            this.label = label == null ? "" : label;
            this.expectedTotal = Math.max(0f, expectedTotal);
        }
    }

    private static final float MIN_EXPECTED = 0.35f;
    private static final int MAX_LINES = 22;

    private BlitzChestLootEstimator() {}

    /**
     * @param middleChestCount number of chests in the detected middle cluster (12–20 typical)
     */
    public static List<EstimateLine> buildEstimates(int middleChestCount) {
        if (middleChestCount <= 0) {
            return Collections.emptyList();
        }
        BlitzChestItemsData.scheduleWarmup();
        if (!BlitzChestItemsData.isReady()) {
            return Collections.singletonList(new EstimateLine(
                    EnumChatFormatting.GRAY + "Loot data loading...", 0f));
        }
        int captures = BlitzChestItemsData.getObservedValidCaptures();
        if (captures <= 0) {
            return Collections.singletonList(new EstimateLine(
                    EnumChatFormatting.GRAY + "No chest stats yet", 0f));
        }

        List<BlitzChestItemsData.Entry> catalog = BlitzChestItemsData.aggregateByItemType(
                BlitzChestItemsData.getAll());
        List<EstimateLine> ranked = new ArrayList<EstimateLine>();
        for (BlitzChestItemsData.Entry entry : catalog) {
            if (entry == null || entry.observedSlotCount <= 0) {
                continue;
            }
            float perChest = entry.observedSlotCount / (float) captures;
            float expected = perChest * middleChestCount;
            if (expected < MIN_EXPECTED) {
                continue;
            }
            String name = shortDisplayName(entry);
            ranked.add(new EstimateLine(name, expected));
        }
        Collections.sort(ranked, new Comparator<EstimateLine>() {
            @Override
            public int compare(EstimateLine a, EstimateLine b) {
                int c = Float.compare(b.expectedTotal, a.expectedTotal);
                return c != 0 ? c : a.label.compareToIgnoreCase(b.label);
            }
        });
        if (ranked.size() > MAX_LINES) {
            ranked = new ArrayList<EstimateLine>(ranked.subList(0, MAX_LINES));
        }
        List<EstimateLine> out = new ArrayList<EstimateLine>(ranked.size() + 1);
        out.add(new EstimateLine(EnumChatFormatting.AQUA + "" + middleChestCount + " middle chests", 0f));
        for (EstimateLine line : ranked) {
            out.add(new EstimateLine(formatAmountLine(line.label, line.expectedTotal), line.expectedTotal));
        }
        return out;
    }

    private static String shortDisplayName(BlitzChestItemsData.Entry entry) {
        String raw = entry.displayName;
        if (raw == null || raw.trim().isEmpty()) {
            raw = BlitzChestItemsData.vanillaItemTypeDisplayName(entry.id, entry.meta);
        }
        raw = EnumChatFormatting.getTextWithoutFormattingCodes(
                BlitzChestItemsData.normalizeFormatting(raw));
        if (raw.length() > 28) {
            raw = raw.substring(0, 26) + "..";
        }
        return EnumChatFormatting.WHITE + raw;
    }

    private static String formatAmountLine(String name, float expected) {
        return name + EnumChatFormatting.GRAY + " ~" + formatExpectedAmount(expected);
    }

    public static String formatExpectedAmount(float expected) {
        if (expected >= 100f) {
            return String.valueOf(Math.round(expected));
        }
        if (expected >= 10f) {
            return String.valueOf(Math.round(expected));
        }
        if (expected >= 1f) {
            float r = Math.round(expected * 10f) / 10f;
            if (Math.abs(r - Math.round(r)) < 0.05f) {
                return String.valueOf(Math.round(r));
            }
            return String.format(Locale.US, "%.1f", r);
        }
        if (expected >= 0.05f) {
            return String.format(Locale.US, "%.1f", expected);
        }
        return "<0.1";
    }
}
