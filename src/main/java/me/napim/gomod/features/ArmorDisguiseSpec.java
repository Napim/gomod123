package me.napim.gomod.features;

import java.util.Arrays;
import java.util.Locale;

/** Immutable helmet/chestplate/leggings/boots description; contains no real inventory data. */
public final class ArmorDisguiseSpec {
    private final String[] slots;

    private ArmorDisguiseSpec(String[] slots) { this.slots = slots; }

    public static ArmorDisguiseSpec parse(String[] args, int start) {
        int count = args.length - start;
        if (count != 1 && count != 4)
            throw new IllegalArgumentException("Enter one material for a full set, or helmet chestplate leggings boots.");
        String[] slots = new String[4];
        for (int i = 0; i < 4; i++) slots[i] = normalize(args[start + (count == 1 ? 0 : i)]);
        return new ArmorDisguiseSpec(slots);
    }

    public static String normalize(String input) {
        String value = input == null ? "" : input.toLowerCase(Locale.ROOT);
        boolean glint = value.endsWith("+");
        if (glint) value = value.substring(0, value.length() - 1);
        switch (value) {
            case "d": case "diamond": value = "diamond"; break;
            case "i": case "iron": value = "iron"; break;
            case "c": case "chain": case "chainmail": value = "chain"; break;
            case "g": case "gold": case "golden": value = "gold"; break;
            case "l": case "leather": value = "leather"; break;
            case "n": case "none": value = "none"; break;
            case "keep": value = "keep"; break;
            default: throw new IllegalArgumentException("Unknown armor '" + input
                    + "'. Use diamond/d, iron/i, chain/c, gold/g, leather/l, none/n or keep; add + for glint.");
        }
        if (glint && (value.equals("none") || value.equals("keep")))
            throw new IllegalArgumentException("Only an armor material can have +.");
        return value + (glint ? "+" : "");
    }

    public String slot(int headToBoots) { return slots[headToBoots]; }
    public String[] slots() { return slots.clone(); }
    @Override public String toString() { return String.join(" ", slots); }
    @Override public boolean equals(Object other) {
        return other instanceof ArmorDisguiseSpec && Arrays.equals(slots, ((ArmorDisguiseSpec) other).slots);
    }
    @Override public int hashCode() { return Arrays.hashCode(slots); }
}
