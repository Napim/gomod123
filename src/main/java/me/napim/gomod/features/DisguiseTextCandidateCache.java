package me.napim.gomod.features;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded negative prefilter results; never stores rewritten text or TAB row objects. */
public final class DisguiseTextCandidateCache {
    private static final int MASK = 1023;
    private final AtomicReferenceArray<Entry> entries = new AtomicReferenceArray<Entry>(MASK + 1);

    private static final class Entry {
        final String text, source, target;
        final long generation;
        Entry(String text, String source, String target, long generation) {
            this.text = text;
            this.source = source;
            this.target = target;
            this.generation = generation;
        }
    }

    private static int slot(String text) {
        int hash = text.hashCode();
        return (hash ^ (hash >>> 16)) & MASK;
    }

    public boolean contains(String text, String source, String target, long generation) {
        if (text == null || text.length() > 2048) return false;
        Entry entry = entries.get(slot(text));
        return entry != null && entry.generation == generation
                && Objects.equals(entry.source, source) && Objects.equals(entry.target, target)
                && entry.text.equals(text);
    }

    public void remember(String text, String source, String target, long generation) {
        if (text != null && text.length() <= 2048) {
            entries.set(slot(text), new Entry(text, source, target, generation));
        }
    }
}
