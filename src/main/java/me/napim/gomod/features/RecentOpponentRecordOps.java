package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Pure grouping and ordering rules for the Recent Opponents view. */
final class RecentOpponentRecordOps {
    private RecentOpponentRecordOps() { }

    static List<RecentOpponents.Record> mergeUnresolvedNickSessions(
            List<RecentOpponents.Record> records, Map<String, String> resolvedRealNamesByUuid) {
        Map<String, RecentOpponents.Record> grouped = new LinkedHashMap<String, RecentOpponents.Record>();
        for (RecentOpponents.Record record : records) {
            String normalizedUuid = normalizeUuid(record.uuid);
            boolean unresolvedNick = isGeneratedNickUuid(record.uuid)
                    && (resolvedRealNamesByUuid == null || !resolvedRealNamesByUuid.containsKey(normalizedUuid));
            String key = unresolvedNick
                    ? "unresolved-nick:" + normalizedName(record.name)
                    : recordKey(record.name, normalizedUuid);
            RecentOpponents.Record previous = grouped.get(key);
            grouped.put(key, previous == null ? record : mergeRecords(previous, record));
        }
        return new ArrayList<RecentOpponents.Record>(grouped.values());
    }

    static void sortRecords(List<RecentOpponents.Record> records, final int sortMode) {
        Collections.sort(records, new Comparator<RecentOpponents.Record>() {
            @Override public int compare(RecentOpponents.Record a, RecentOpponents.Record b) {
                int compared;
                if (sortMode == 1) {
                    compared = Integer.compare(b.matches, a.matches);
                } else if (sortMode == 2) {
                    compared = (Double.isNaN(a.getKillDeathRatio()) || Double.isNaN(b.getKillDeathRatio()))
                            ? compareKillDeathRatio(a, b) : -compareKillDeathRatio(a, b);
                } else if (sortMode == 3) {
                    compared = compareKillDeathRatio(a, b);
                } else if (sortMode == 4) {
                    compared = Long.compare(fightsCount(b), fightsCount(a));
                } else {
                    compared = Long.compare(b.lastSeen, a.lastSeen);
                }
                return compared != 0 ? compared : Long.compare(b.lastSeen, a.lastSeen);
            }
        });
    }

    static boolean isGeneratedNickUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return false;
        try {
            return UUID.fromString(uuid.trim()).version() != 4;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static long fightsCount(RecentOpponents.Record record) {
        return (long) record.wins + (long) record.losses;
    }

    private static int compareKillDeathRatio(RecentOpponents.Record a, RecentOpponents.Record b) {
        double left = a.getKillDeathRatio();
        double right = b.getKillDeathRatio();
        if (Double.isNaN(left)) return Double.isNaN(right) ? 0 : 1;
        if (Double.isNaN(right)) return -1;
        return Double.compare(left, right);
    }

    private static String recordKey(String name, String normalizedUuid) {
        if (!normalizedUuid.isEmpty()) return "uuid:" + normalizedUuid;
        return "name:" + normalizedName(name);
    }

    private static String normalizeUuid(String uuid) {
        if (uuid == null) return "";
        String compact = uuid.trim().replace("-", "").toLowerCase(Locale.ROOT);
        if (compact.length() != 32) return "";
        boolean allZeroes = true;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return "";
            if (c != '0') allZeroes = false;
        }
        return allZeroes ? "" : compact;
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static RecentOpponents.Record mergeRecords(RecentOpponents.Record first,
            RecentOpponents.Record second) {
        RecentOpponents.Record newest = second.lastSeen >= first.lastSeen ? second : first;
        return new RecentOpponents.Record(newest.name, newest.uuid,
                first.matches + second.matches, first.wins + second.wins, first.losses + second.losses,
                Math.max(first.lastSeen, second.lastSeen),
                first.legacyUnverified || second.legacyUnverified);
    }
}
