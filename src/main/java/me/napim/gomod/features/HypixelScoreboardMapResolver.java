package me.napim.gomod.features;

import me.napim.gomod.util.SidebarSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a stable map/arena id from the Hypixel sidebar (Blitz SG, Blitz Teams, etc.).
 */
public final class HypixelScoreboardMapResolver {
    private static final Pattern MAP_LABEL = Pattern.compile("(?i)\\bmap\\s*:\\s*(.+)");
    private static final Pattern ARENA_LABEL = Pattern.compile("(?i)\\barena\\s*:\\s*(.+)");
    private static final Pattern LOCATION_LABEL = Pattern.compile("(?i)\\blocation\\s*:\\s*(.+)");
    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}+");
    private static final Pattern EXTRA_INVISIBLES = Pattern.compile("[\u00ad\u180e\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff\ufff9-\ufffb]");
    private static final Pattern NON_LETTER_NUMBER = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern UNDERSCORES = Pattern.compile("_+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Pure text results only: new scoreboard contents always have a different key. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> NORMALIZED_LINES =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final int MAX_NORMALIZED_LINES = 512;
    private static final int MAX_CACHED_LINE_LENGTH = 512;

    private HypixelScoreboardMapResolver() {
    }

    /**
     * @return slug key for {@link ChestRouteStorage}, or null if unknown
     */
    public static String tryResolveCurrentMapKey(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) {
            return null;
        }
        ScoreObjective obj = board.getObjectiveInDisplaySlot(1);
        if (obj != null) {
            String fromTitle = extractMapSlugFromLine(stripFormatting(obj.getDisplayName()));
            if (fromTitle != null) {
                return fromTitle;
            }
        }
        if (obj == null) {
            return null;
        }
        for (String line : SidebarSnapshot.get(board, obj).plain()) {
            String fromLine = extractMapSlugFromLine(line);
            if (fromLine != null) {
                return fromLine;
            }
        }
        return null;
    }

    /** All visible sidebar text lines (title + each score row), for chest-route autoload matching. */
    public static List<String> collectSidebarTextLines(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (mc == null || mc.theWorld == null) {
            return out;
        }
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) {
            return out;
        }
        ScoreObjective obj = board.getObjectiveInDisplaySlot(1);
        if (obj == null) {
            return out;
        }
        out.add(stripFormatting(obj.getDisplayName()));
        out.addAll(SidebarSnapshot.get(board, obj).plain());
        return out;
    }

    /**
     * If the line looks like {@code Map: Shogun} / {@code Arena: …} / {@code Location: …}, returns the slug; else null.
     */
    public static String extractMapSlugFromLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return null;
        }
        Matcher m = MAP_LABEL.matcher(t);
        if (m.find()) {
            return slugify(m.group(1));
        }
        m = ARENA_LABEL.matcher(t);
        if (m.find()) {
            return slugify(m.group(1));
        }
        m = LOCATION_LABEL.matcher(t);
        if (m.find()) {
            return slugify(m.group(1));
        }
        return null;
    }

    /**
     * Stable file key for map names. Strips color codes, normalizes Unicode (emoji / fancy chars),
     * removes invisible format chars (ZWSP, etc. — Hypixel often breaks names with those), then slugifies.
     * Uses any Unicode letter/digit so "Shroom Valley" stays {@code shroom_valley}, not {@code shroom_va_lley}.
     */
    public static String slugify(String raw) {
        if (raw == null) {
            return "unknown";
        }
        String s = stripFormatting(raw).trim();
        if (s.isEmpty()) {
            return "unknown";
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        // Remove format / invisible characters that split words for regex (e.g. ZWSP, ZWJ, bidi marks)
        s = FORMAT_CHARS.matcher(s).replaceAll("");
        s = EXTRA_INVISIBLES.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT);
        // Letters/digits from any script; everything else (emoji, punctuation, odd symbols) → underscore
        s = NON_LETTER_NUMBER.matcher(s).replaceAll("_");
        s = UNDERSCORES.matcher(s).replaceAll("_");
        s = EDGE_UNDERSCORES.matcher(s).replaceAll("");
        return s.isEmpty() ? "unknown" : s;
    }

    public static String stripFormatting(String value) {
        return me.napim.gomod.util.MinecraftFormatting.stripSectionCodes(value);
    }

    /**
     * True when the sidebar shows the Blitz “Cages Open” countdown line (game about to start).
     * Matches flexible wording: "Cage Open", "Cages Open:", thin/invisible chars, etc.
     */
    public static boolean hasCagesOpenLine(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        try {
            Scoreboard board = mc.theWorld.getScoreboard();
            if (board == null) {
                return false;
            }
            ScoreObjective obj = board.getObjectiveInDisplaySlot(1);
            if (obj == null) {
                return false;
            }
            if (sidebarLineLooksLikeCagesOpen(obj.getDisplayName())) {
                return true;
            }
            SidebarSnapshot sidebar = SidebarSnapshot.get(board, obj);
            for (Score score : sidebar.scores()) {
                if (sidebarLineLooksLikeCagesOpen(sidebar.formatted(score))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Normalizes a scoreboard line for substring checks (Unicode, invisible chars, § codes).
     */
    static String normalizeSidebarLineForMatch(String raw) {
        if (raw == null) {
            return "";
        }
        boolean cacheable = raw.length() <= MAX_CACHED_LINE_LENGTH;
        if (cacheable) {
            String cached = NORMALIZED_LINES.get(raw);
            if (cached != null) return cached;
        }
        String normalized = normalizeSidebarLineUncached(raw);
        if (cacheable) {
            synchronized (NORMALIZED_LINES) {
                if (NORMALIZED_LINES.size() >= MAX_NORMALIZED_LINES) NORMALIZED_LINES.clear();
                NORMALIZED_LINES.put(raw, normalized);
            }
        }
        return normalized;
    }

    private static String normalizeSidebarLineUncached(String raw) {
        String s = stripFormatting(raw).trim();
        if (s.isEmpty()) {
            return "";
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = FORMAT_CHARS.matcher(s).replaceAll("");
        s = EXTRA_INVISIBLES.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT);
        s = s.replace(':', ' ');
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /** True if this looks like the cages countdown (not e.g. a player name containing random words). */
    static boolean sidebarLineLooksLikeCagesOpen(String raw) {
        String n = normalizeSidebarLineForMatch(raw);
        if (n.isEmpty()) {
            return false;
        }
        if (!n.contains("cage")) {
            return false;
        }
        return n.contains("open");
    }
}
