package me.napim.gomod.features;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Hypixel Blitz Star usage chat lines and records which player used which star for TAB icons.
 */
public final class BlitzStarTabTracker {
    private static final Pattern STAR_PATTERN_1 = Pattern.compile("^([^:]+)! BLITZ STAR! (.+)!\\s*$");
    private static final Pattern STAR_PATTERN_2 = Pattern.compile("^([^:]+) BLITZ! (.+)$");
    private static final Pattern STAR_PATTERN_FOUND = Pattern.compile("^([^:]+) found the Blitz Star!\\s*$");

    private static final Set<String> SUPPRESSED_STARS = new HashSet<>(Arrays.asList(
            "sweg move", "roulette", "stasis", "stasis star"));

    private BlitzStarTabTracker() {
    }

    public static void onChatUnformatted(String unformatted) {
        if (unformatted == null || unformatted.trim().isEmpty()) {
            return;
        }
        String line = unformatted.replaceAll("\\s+", " ").trim();
        Matcher found = STAR_PATTERN_FOUND.matcher(line);
        if (found.matches()) {
            BlitzStarMatchSummary.registerFinder(toDisplayedLocalDisguiseIgnIfNeeded(extractIgnFromBlitzPrefix(found.group(1))));
            return;
        }
        Matcher m1 = STAR_PATTERN_1.matcher(line);
        if (m1.matches()) {
            String star = normalizeStarName(m1.group(2));
            String userIgn = toDisplayedLocalDisguiseIgnIfNeeded(extractIgnFromBlitzPrefix(m1.group(1)));
            BlitzStarMatchSummary.registerUse(userIgn, star);
            if (!isSuppressed(star)) {
                KillCounter.registerPlayerBlitzStarUse(userIgn, star);
                routeStarToHudOrVampireArm(star, userIgn);
            }
            return;
        }
        Matcher m2 = STAR_PATTERN_2.matcher(line);
        if (m2.matches()) {
            String star = normalizeStarName(m2.group(2));
            if (star.endsWith("!")) {
                star = star.substring(0, star.length() - 1).trim();
            }
            String userIgn = toDisplayedLocalDisguiseIgnIfNeeded(extractIgnFromBlitzPrefix(m2.group(1)));
            BlitzStarMatchSummary.registerUse(userIgn, star);
            if (!isSuppressed(star)) {
                KillCounter.registerPlayerBlitzStarUse(userIgn, star);
                routeStarToHudOrVampireArm(star, userIgn);
            }
        }
    }

    private static void routeStarToHudOrVampireArm(String star, String userIgn) {
        BlitzStarEffectsHudTracker.Effect resolved = BlitzStarEffectsHudTracker.resolve(star);
        if (resolved == BlitzStarEffectsHudTracker.Effect.VAMPIRE) {
            if (userIgn != null && KillCounter.isLocalClientIgn(userIgn)) {
                BlitzVampireEffectActivation.markLocalPlayerReceivedVampireStarChat();
            }
            return;
        }
        BlitzStarEffectsHudTracker.onStarUsed(star, userIgn);
    }

    private static boolean isSuppressed(String starName) {
        if (starName == null) {
            return true;
        }
        return SUPPRESSED_STARS.contains(starName.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalizeStarName(String starName) {
        if (starName == null) {
            return "";
        }
        String clean = stripFormatting(starName).replaceAll("\\s+", " ").trim();
        if (clean.startsWith("(") && clean.endsWith(")") && clean.length() > 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }

    /**
     * Player part is plain text before {@code BLITZ!} / {@code BLITZ STAR!} — last Minecraft-style token.
     */
    private static String extractIgnFromBlitzPrefix(String part) {
        if (part == null) {
            return null;
        }
        String clean = stripFormatting(part).trim();
        if (clean.isEmpty()) {
            return null;
        }
        String[] words = clean.split("\\s+");
        for (int i = words.length - 1; i >= 0; i--) {
            String raw = words[i].replaceAll("[.,!?]", "");
            if (raw.matches("^[A-Za-z0-9_]{3,16}$")) {
                return raw;
            }
        }
        return null;
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "");
    }

    private static String toDisplayedLocalDisguiseIgnIfNeeded(String ign) {
        if (ign == null || ign.trim().isEmpty()) {
            return ign;
        }
        try {
            if (LocalDisguise.isEnabled() && KillCounter.isLocalClientIgn(ign)) {
                String target = LocalDisguise.getTargetTabChatDisplayNamePlain();
                if (target != null && !target.trim().isEmpty()) {
                    return target.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return ign;
    }
}
