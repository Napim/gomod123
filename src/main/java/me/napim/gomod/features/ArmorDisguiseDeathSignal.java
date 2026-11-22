package me.napim.gomod.features;

import java.util.regex.*;

/** Strict whole-line server death signals, independent of TAB Stats and display disguises. */
public final class ArmorDisguiseDeathSignal {
    private static final String RANK = "(?:\\[[A-Za-z0-9+]{1,16}\\]\\s*){0,2}";
    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final Pattern KILLED = Pattern.compile("^" + RANK + NAME
            + " (?:was|were) (?:killed|slain|shot|blown up|burned to death|frozen to death|electrocuted|eliminated|defeated) by "
            + RANK + "[A-Za-z0-9_]{1,16}(?: [^:\\r\\n<>]{1,96})?[.!]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE = Pattern.compile("^" + RANK + NAME
            + " (?:(?:was|were) killed!|died[.!]|fell to (?:their|his|her) death[.!])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_KILL = Pattern.compile("^You (?:killed|shot|slew|slain|blew up|burned to death|frozen to death|electrocuted|eliminated|defeated) "
            + RANK + NAME + "(?: [^:\\r\\n<>]{1,96})?[.!]?$", Pattern.CASE_INSENSITIVE);
    private ArmorDisguiseDeathSignal() {}
    public static String victim(String raw) {
        if (raw == null || raw.length() > 512) return null;
        String text = raw.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "").trim();
        for (Pattern pattern : new Pattern[]{KILLED, SIMPLE, SELF_KILL}) {
            Matcher match = pattern.matcher(text);
            if (match.matches()) return match.group(1);
        }
        return null;
    }
}
