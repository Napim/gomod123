package me.napim.gomod.features;

import java.util.*;

/** Route-local snapshot and death tombstones. A delayed poll cannot resurrect a dead assignment. */
public final class ArmorDisguiseState {
    public static final class Entry {
        public final String id, uuid, name;
        public final ArmorDisguiseSpec spec;
        public Entry(String id, String uuid, String name, ArmorDisguiseSpec spec) {
            this.id = id; this.uuid = uuid; this.name = name; this.spec = spec;
        }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> dead = new LinkedHashSet<>();
    private long lastSnapshot = -1;
    private long freshUntil;
    private boolean exhausted;

    public void replace(long serial, Collection<Entry> snapshot, long now) {
        if (exhausted || serial <= lastSnapshot || snapshot.size() > 128) return;
        lastSnapshot = serial;
        entries.clear();
        for (Entry entry : snapshot) if (!dead.contains(entry.id)) entries.put(entry.uuid, entry);
        freshUntil = now + 7000L;
    }
    public Entry find(String uuid, long now) { return now < freshUntil ? entries.get(uuid) : null; }
    public Collection<Entry> entries() { return new ArrayList<>(entries.values()); }
    public List<String> died(String uuid, String name) {
        List<String> ids = new ArrayList<>();
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext();) {
            Entry entry = it.next();
            if ((uuid != null && uuid.equals(entry.uuid)) || (name != null && name.equalsIgnoreCase(entry.name))) {
                it.remove(); ids.add(entry.id); dead.add(entry.id);
            }
        }
        // More than this in one room is implausible; fail closed instead of evicting a tombstone.
        if (dead.size() > 4096) { entries.clear(); freshUntil = 0L; exhausted = true; }
        return ids;
    }
    public void clear() { entries.clear(); dead.clear(); lastSnapshot = -1; freshUntil = 0; exhausted = false; }
}
