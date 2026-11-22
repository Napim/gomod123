package me.napim.gomod.features;

import java.util.Locale;

/** Render-only basic Lunar icon policy; the saved Lunar profile is never edited. */
public enum DisguiserLunarIcon {
    KEEP("keep"), LUNAR("lunar"), NO_LUNAR("nolunar");

    public final String wire;
    DisguiserLunarIcon(String wire) { this.wire = wire; }

    public static DisguiserLunarIcon fromWire(String value) {
        if (value == null || value.isEmpty() || "keep".equals(value)) return KEEP;
        if ("lunar".equals(value)) return LUNAR;
        if ("nolunar".equals(value)) return NO_LUNAR;
        return KEEP;
    }

    public static DisguiserLunarIcon commandOption(String value) {
        String option = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if ("lunar".equals(option)) return LUNAR;
        if ("nolunar".equals(option)) return NO_LUNAR;
        throw new IllegalArgumentException("Icon option must be lunar or nolunar.");
    }
}
