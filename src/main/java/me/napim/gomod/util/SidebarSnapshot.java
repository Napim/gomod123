package me.napim.gomod.util;

import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A sorted view of one objective and its team-formatted rows. */
public final class SidebarSnapshot {
    public interface Access {
        long gomod$sidebarRevision();
        SidebarSnapshot gomod$sidebarSnapshot(ScoreObjective objective);
        void gomod$invalidateSidebar();
    }

    private static final SidebarSnapshot EMPTY = new SidebarSnapshot(null, null, 0L);

    private final ScoreObjective objective;
    private final long revision;
    private final List<Score> scores;
    private final Map<Score, String> formatted;
    private final List<String> plain;
    private final List<String> lowercase;

    public SidebarSnapshot(Scoreboard board, ScoreObjective objective, long revision) {
        this.objective = objective;
        this.revision = revision;
        if (board == null || objective == null) {
            scores = Collections.emptyList();
            formatted = Collections.emptyMap();
            plain = Collections.emptyList();
            lowercase = Collections.emptyList();
            return;
        }
        Collection<Score> sorted = board.getSortedScores(objective);
        scores = sorted == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(sorted));
        formatted = new IdentityHashMap<>(scores.size());
        List<String> plainRows = new ArrayList<>(scores.size());
        List<String> lowerRows = new ArrayList<>(scores.size());
        for (Score score : scores) {
            if (score == null) {
                continue;
            }
            String name = score.getPlayerName();
            String raw = ScorePlayerTeam.formatPlayerName(board.getPlayersTeam(name), name);
            formatted.put(score, raw);
            String text = MinecraftFormatting.stripSectionCodes(raw);
            plainRows.add(text);
            if (raw != null) {
                lowerRows.add(text.toLowerCase(Locale.ROOT));
            }
        }
        plain = Collections.unmodifiableList(plainRows);
        lowercase = Collections.unmodifiableList(lowerRows);
    }

    public static SidebarSnapshot get(Scoreboard board, ScoreObjective objective) {
        if (board == null || objective == null) {
            return EMPTY;
        }
        return board instanceof Access ? ((Access) board).gomod$sidebarSnapshot(objective)
                : new SidebarSnapshot(board, objective, 0L);
    }

    public static long revision(Scoreboard board) {
        return board instanceof Access ? ((Access) board).gomod$sidebarRevision() : 0L;
    }

    /** For writers that update a team field directly instead of calling the vanilla setters. */
    public static void invalidate(Scoreboard board) {
        if (board instanceof Access) {
            ((Access) board).gomod$invalidateSidebar();
        }
    }

    public boolean matches(ScoreObjective requested, long currentRevision) {
        return objective == requested && revision == currentRevision;
    }

    public List<Score> scores() {
        return scores;
    }

    public String formatted(Score score) {
        return formatted.get(score);
    }

    public List<String> plain() {
        return plain;
    }

    public List<String> lowercase() {
        return lowercase;
    }
}
