package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, client-independent interpretation of raw Roulette packet observations. */
public final class BlitzRouletteTrace {
    private static final Pattern IGN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern USE = Pattern.compile("^(?:Picking a random future dead-player\\s+)?([A-Za-z0-9_]{3,16})(?: BLITZ!|! BLITZ STAR!) Roulette!?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLL = Pattern.compile("^ROLLING FOR ([A-Za-z0-9_]{3,16})['\u2019]S ROULETTE BLITZ$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEATH = Pattern.compile("^DIED FROM ([A-Za-z0-9_]{3,16})['\u2019]S ROULETTE BLITZ$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINNER = Pattern.compile("^([A-Za-z0-9_]{3,16}) is the (?:\\(un\\)|un)?lucky winner!$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF = Pattern.compile("^The fool ([A-Za-z0-9_]{3,16}) killed himself!$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_EVENTS = 4096, MAX_CHARS = 600000, MAX_ROUNDS = 16;
    private final List<Event> events = new ArrayList<Event>();
    private final List<Round> rounds = new ArrayList<Round>();
    private Round active;
    private Event lastTitle;
    private int chars;
    public boolean full;

    public static final class Event {
        public final long ms;
        public final String kind, raw;
        public Event(long ms, String kind, String raw) {
            this.ms = ms; this.kind = safe(kind, 32); this.raw = safe(raw, "TAB".equals(kind) ? 8192 : 768);
        }
    }

    public static final class Player {
        public final String name, uuid, mode, team;
        public final Integer kills;
        public final boolean deadHint;
        public final int tabIndex, entityIndex;
        public final Float health;
        public Player(String name, String uuid, String mode, String team, Integer kills,
                      boolean deadHint, int tabIndex, int entityIndex, Float health) {
            this.name = safe(name, 32); this.uuid = safe(uuid, 40); this.mode = safe(mode, 24);
            this.team = safe(team, 128); this.kills = kills; this.deadHint = deadHint;
            this.tabIndex = tabIndex; this.entityIndex = entityIndex; this.health = health;
        }
        boolean possibleLiving() { return !deadHint && !"SPECTATOR".equalsIgnoreCase(mode); }
    }

    public static final class Snapshot {
        public final long ms;
        public final String context, issue;
        public final List<Player> players;
        public Snapshot(long ms, String context, List<Player> players, String issue) {
            this.ms = ms; this.context = safe(context, 1024); this.issue = safe(issue, 256);
            this.players = Collections.unmodifiableList(new ArrayList<Player>(players.subList(0, Math.min(128, players.size()))));
        }
    }

    private static final class Round {
        final int id;
        final long start;
        final List<Event> names = new ArrayList<Event>();
        Snapshot activation, result, firstObservation;
        final String startSignal;
        String activator = "unknown", phase = "rolling", winner, finalTitle, end;
        boolean startObserved, selfKill;
        long resultAt;
        Round(int id, long start, Snapshot snapshot, boolean startObserved, String startSignal) {
            this.id = id; this.start = start; this.firstObservation = snapshot;
            this.activation = startObserved ? snapshot : null; this.startObserved = startObserved; this.startSignal = startSignal;
        }
    }

    public static boolean isRelevantChat(String raw) {
        String text = plain(raw);
        return text.equalsIgnoreCase("Picking a random future dead-player") || USE.matcher(text).matches()
                || WINNER.matcher(text).matches() || SELF.matcher(text).matches();
    }

    public void accept(Event event, Snapshot priorObservation) {
        if (full) return;
        if (events.size() >= MAX_EVENTS || chars + event.raw.length() > MAX_CHARS) { full = true; return; }
        events.add(event); chars += event.raw.length();
        String text = plain(event.raw);
        if ("CLEAR".equals(event.kind) || "RESET".equals(event.kind)) {
            lastTitle = null;
            if (active != null) active.phase = "hud-cleared";
        } else if ("TITLE".equals(event.kind)) {
            lastTitle = event;
            recordName(active, event);
        } else if ("SUBTITLE".equals(event.kind)) {
            Matcher roll = ROLL.matcher(text), death = DEATH.matcher(text);
            if (roll.matches()) {
                Round round = active != null && active.end == null && active.winner != null
                        ? active : begin(event, priorObservation, roll.group(1), true);
                if (round != null) { round.phase = "rolling"; attachPreviousTitle(round, event.ms); }
            } else if (text.equalsIgnoreCase("SELECTED PLAYER WILL DIE")) {
                Round round = begin(event, priorObservation, null, false);
                if (round != null) { round.phase = "selected"; attachPreviousTitle(round, event.ms); }
            } else if (death.matches()) {
                Round round = begin(event, priorObservation, death.group(1), false);
                if (round != null) {
                    round.phase = "death";
                    attachPreviousTitle(round, event.ms);
                    if (lastTitle != null && event.ms - lastTitle.ms <= 2000 && IGN.matcher(plain(lastTitle.raw)).matches()) {
                        round.finalTitle = plain(lastTitle.raw);
                    }
                }
            } else if (!text.isEmpty() && active != null) {
                active.phase = "unrelated-subtitle";
            }
        } else if ("CHAT".equals(event.kind)) {
            Matcher use = USE.matcher(text), winner = WINNER.matcher(text), self = SELF.matcher(text);
            if (text.equalsIgnoreCase("Picking a random future dead-player")) {
                begin(event, priorObservation, null, true);
            } else if (use.matches()) {
                begin(event, priorObservation, use.group(1), true);
            } else if (winner.matches()) {
                Round round = active;
                if (round != null && round.winner != null && round.winner.equalsIgnoreCase(winner.group(1))
                        && event.ms - round.resultAt < 5000) return;
                if (round == null || round.end != null) round = begin(event, null, null, false);
                if (round != null) {
                    round.winner = winner.group(1); round.resultAt = event.ms; round.result = priorObservation;
                }
            } else if (self.matches() && active != null && active.end == null) {
                active.selfKill = true;
                if ("unknown".equals(active.activator)) active.activator = self.group(1);
            }
        }
    }

    private Round begin(Event event, Snapshot snapshot, String activator, boolean observedStart) {
        if (active != null && active.end == null && active.winner == null
                && (activator == null || "unknown".equals(active.activator) || active.activator.equalsIgnoreCase(activator))) {
            if (activator != null && "unknown".equals(active.activator)) active.activator = activator;
            return active;
        }
        if (active != null && active.end == null && active.winner != null && !observedStart) return active;
        if (active != null && active.end == null) active.end = active.winner == null ? "another activation" : "confirmed winner";
        if (rounds.size() >= MAX_ROUNDS) { full = true; return null; }
        // A rolling subtitle may be the first packet we saw after joining mid-roll.
        // Only a use/prelude chat signal supplies an activation observation for rule comparisons.
        active = new Round(rounds.size() + 1, event.ms, snapshot,
                observedStart && "CHAT".equals(event.kind), event.kind);
        if (activator != null) active.activator = activator;
        rounds.add(active);
        return active;
    }

    private void attachPreviousTitle(Round round, long now) {
        if (lastTitle != null && now - lastTitle.ms >= 0 && now - lastTitle.ms <= 1500) recordName(round, lastTitle);
    }

    private void recordName(Round round, Event event) {
        if (round == null || round.end != null || "hud-cleared".equals(round.phase)
                || "unrelated-subtitle".equals(round.phase) || !IGN.matcher(plain(event.raw)).matches()) return;
        if (round.names.isEmpty() || round.names.get(round.names.size() - 1) != event) round.names.add(event);
        if ("death".equals(round.phase)) round.finalTitle = plain(event.raw);
    }

    public void tick(long now) {
        if (active == null || active.end != null) return;
        if (active.winner != null && now - active.resultAt >= 2000) active.end = "confirmed winner";
        else if (active.winner == null && now - active.start >= 45000) active.end = "45s without a winner announcement";
    }

    public void worldChanged(long now, String world) {
        accept(new Event(now, "WORLD", world), null);
        if (active != null && active.end == null) active.end = active.winner == null ? "world changed; incomplete" : "world changed after winner";
        active = null; lastTitle = null;
    }

    public boolean isRolling() { return active != null && active.end == null; }
    public int roundCount() { return rounds.size(); }
    public int finishedCount() { int n = 0; for (Round round : rounds) if (round.end != null) n++; return n; }
    public int winnerCount() { int n = 0; for (Round round : rounds) if (round.winner != null) n++; return n; }

    public String report(String environment, String reason, long now, int dropped) {
        StringBuilder out = new StringBuilder(8192);
        out.append("gomod Roulette debug v1\n").append(environment).append("\nreason=").append(reason)
                .append(" elapsed_ms=").append(now).append(" rounds=").append(rounds.size())
                .append(" confirmed_winners=").append(winnerCount()).append(" dropped_events=").append(dropped)
                .append(" buffer_full=").append(full).append('\n');
        out.append("Evidence: raw inbound packet text captured before gomod's shared display/disguise handlers.\n")
                .append("Server RNG, seed, internal eligible set and selection time are unavailable. A random choice made before animation is still random.\n")
                .append("TAB and loaded-entity orders are client observations, not the server's internal list. UUIDs may be Hypixel nick UUIDs.\n")
                .append("Kills are client-tracked match kills, never lifetime kills; unknown values remain unknown. Alive/dead filtering is a hint.\n")
                .append("Coincidences or a repeating animation do not establish a targeting rule. Several independently observed rounds are needed.\n\n");
        Map<String, int[]> aggregate = new LinkedHashMap<String, int[]>();
        for (Round round : rounds) {
            out.append("ROUND ").append(round.id).append(" start_ms=").append(round.start)
                    .append(" start_signal=").append(round.startSignal)
                    .append(" activation_observed=").append(round.startObserved).append(" activator=").append(round.activator)
                    .append(" winner_chat=").append(round.winner == null ? "not observed" : round.winner)
                    .append(" final_death_title=").append(round.finalTitle == null ? "not observed" : round.finalTitle)
                    .append(" self_kill_line=").append(round.selfKill).append(" end=").append(round.end == null ? "recording" : round.end).append('\n');
            if (round.winner != null && round.finalTitle != null) out.append("winner_vs_final_title=")
                    .append(round.winner.equalsIgnoreCase(round.finalTitle) ? "agree" : "DIFFER (inspect raw timeline)").append('\n');
            snapshot(out, "activation_observation", round.activation, round.start);
            if (round.activation == null && round.firstObservation != null) snapshot(out, "first_signal_observation", round.firstObservation, round.start);
            snapshot(out, "result_observation", round.result, round.resultAt);
            analyse(out, round, aggregate);
            out.append("displayed_names (server packet times; dt includes network delay):\n");
            long previous = -1;
            List<String> changed = new ArrayList<String>();
            for (Event name : round.names) {
                String plain = plain(name.raw);
                out.append("  t=").append(name.ms).append(" dt=").append(previous < 0 ? "n/a" : name.ms - previous)
                        .append(" name=").append(plain).append('\n'); previous = name.ms;
                if (changed.isEmpty() || !changed.get(changed.size() - 1).equalsIgnoreCase(plain)) changed.add(plain);
            }
            out.append("animation_exact_repeat_period=").append(period(changed)).append(" (0=no exact repeat; not a winner predictor)\n\n");
        }
        out.append("HYPOTHESIS COUNTS (fully observed comparisons only; ties count as matches):\n");
        for (Map.Entry<String, int[]> entry : aggregate.entrySet()) out.append("  ").append(entry.getKey()).append(" matches=")
                .append(entry.getValue()[0]).append('/').append(entry.getValue()[1]).append('\n');
        out.append("No probability or fairness conclusion is made from these counts.\n\nRAW TIMELINE:\n");
        for (Event event : events) out.append("t=").append(event.ms).append(" ").append(event.kind).append(' ').append(event.raw).append('\n');
        return out.toString();
    }

    private static void snapshot(StringBuilder out, String label, Snapshot snapshot, long at) {
        if (snapshot == null) { out.append(label).append("=unavailable (activation/result was not observed)\n"); return; }
        out.append(label).append(" t=").append(snapshot.ms).append(" age_ms=").append(at - snapshot.ms)
                .append(" context=").append(snapshot.context).append(" issue=").append(snapshot.issue).append('\n');
        out.append("  name | uuid | mode | dead_hint | tracked_match_kills | client_tab_index | loaded_entity_index | health | client_team_text\n");
        for (Player p : snapshot.players) out.append("  ").append(p.name).append(" | ").append(p.uuid).append(" | ").append(p.mode)
                .append(" | ").append(p.deadHint).append(" | ").append(p.kills == null ? "unknown" : p.kills)
                .append(" | ").append(p.tabIndex).append(" | ").append(p.entityIndex < 0 ? "not loaded" : p.entityIndex)
                .append(" | ").append(p.health == null ? "unknown" : p.health).append(" | ").append(p.team).append('\n');
    }

    private static void analyse(StringBuilder out, Round round, Map<String, int[]> aggregate) {
        if (round.winner == null || round.activation == null) { out.append("rank_comparisons=unavailable; need activation roster and winner chat\n"); return; }
        List<Player> possible = new ArrayList<Player>();
        for (Player p : round.activation.players) if (p.possibleLiving()) possible.add(p);
        out.append("possible_living_pool=").append(possible.size()).append(" (inferred, not a server eligibility guarantee)\n");
        boolean usable = round.activation.issue.isEmpty() && round.start - round.activation.ms <= 1000
                && possible.size() > 1 && unique(possible);
        out.append("aggregate_usable=").append(usable).append(" (requires a recent, complete observation and at least two distinct possible players)\n");
        ranks(out, "possible_living", possible, round.winner, aggregate, usable);
        ranks(out, "all_tab", round.activation.players, round.winner, new LinkedHashMap<String, int[]>(), false);
    }

    private static void ranks(StringBuilder out, String poolName, List<Player> pool, String winner,
                              Map<String, int[]> aggregate, boolean usable) {
        Player target = null;
        for (Player p : pool) if (p.name.equalsIgnoreCase(winner)) {
            if (target != null) { out.append(poolName).append(" ambiguous_duplicate_IGN; no ranks calculated\n"); return; }
            target = p;
        }
        if (target == null) { out.append(poolName).append(" winner_not_in_observed_pool\n"); return; }
        for (int metric = 0; metric < 6; metric++) {
            final int m = metric;
            String label = new String[]{"name_ascending", "uuid_lexical", "uuid_java_signed", "tracked_kills_descending", "client_tab_iteration", "loaded_entity_order"}[metric];
            List<Player> known = new ArrayList<Player>();
            for (Player p : pool) if (known(p, metric)) known.add(p);
            if (!known(target, metric)) { out.append("  ").append(poolName).append(' ').append(label).append(" winner_value_unknown\n"); continue; }
            Comparator<Player> comparator = (a, b) -> compare(a, b, m);
            int before = 0, tied = 0;
            for (Player p : known) { int order = comparator.compare(p, target); if (order < 0) before++; if (order == 0) tied++; }
            boolean complete = known.size() == pool.size();
            out.append("  ").append(poolName).append(' ').append(label).append(" rank=").append(before + 1)
                    .append(" ties=").append(tied).append(" compared=").append(known.size()).append('/').append(pool.size())
                    .append(" complete=").append(complete).append('\n');
            if (complete && usable) {
                tally(aggregate, label + "_first", before == 0);
                tally(aggregate, label + "_last", before + tied == known.size());
            }
        }
    }

    private static boolean known(Player p, int metric) {
        if (metric == 1 || metric == 2) { try { UUID.fromString(p.uuid); return true; } catch (Exception ignored) { return false; } }
        return metric == 3 ? p.kills != null : metric != 5 || p.entityIndex >= 0;
    }
    private static boolean unique(List<Player> players) {
        java.util.Set<String> names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        java.util.Set<UUID> ids = new java.util.HashSet<UUID>();
        for (Player p : players) {
            if (!names.add(p.name)) return false;
            try { if (!ids.add(UUID.fromString(p.uuid))) return false; } catch (IllegalArgumentException ignored) { }
        }
        return true;
    }
    private static int compare(Player a, Player b, int metric) {
        switch (metric) {
            case 1: return a.uuid.compareToIgnoreCase(b.uuid);
            case 2: return UUID.fromString(a.uuid).compareTo(UUID.fromString(b.uuid));
            case 3: return Integer.compare(b.kills, a.kills);
            case 4: return Integer.compare(a.tabIndex, b.tabIndex);
            case 5: return Integer.compare(a.entityIndex, b.entityIndex);
            default: return a.name.compareToIgnoreCase(b.name);
        }
    }
    private static void tally(Map<String, int[]> counts, String rule, boolean hit) {
        int[] count = counts.get(rule); if (count == null) { count = new int[2]; counts.put(rule, count); }
        count[1]++; if (hit) count[0]++;
    }
    private static int period(List<String> names) {
        for (int p = 1; p <= names.size() / 2; p++) {
            boolean same = true;
            for (int i = p; i < names.size(); i++) if (!names.get(i).equalsIgnoreCase(names.get(i % p))) { same = false; break; }
            if (same) return p;
        }
        return 0;
    }
    public static String plain(String text) { return safe(text, 8192).replaceAll("\u00a7.", "").replaceAll("\\s+", " ").trim(); }
    private static String safe(String text, int limit) {
        if (text == null) return "";
        text = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return text.length() <= limit ? text : text.substring(0, limit) + "[truncated]";
    }
}
