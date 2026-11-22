package me.napim.gomod.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Reuses the wire-format key for player UUIDs frequently looked up during rendering. */
public final class CompactUuid {
    private static final int CACHE_SIZE = 1024;
    private static final AtomicReferenceArray<Entry> RECENT = new AtomicReferenceArray<>(CACHE_SIZE);

    private CompactUuid() {}

    public static String key(UUID uuid) {
        int slot = uuid.hashCode() & (CACHE_SIZE - 1);
        Entry cached = RECENT.get(slot);
        if (cached != null && uuid.equals(cached.uuid)) return cached.key;
        String key = uuid.toString().replace("-", "");
        RECENT.set(slot, new Entry(uuid, key));
        return key;
    }

    private static final class Entry {
        final UUID uuid;
        final String key;

        Entry(UUID uuid, String key) {
            this.uuid = uuid;
            this.key = key;
        }
    }
}
