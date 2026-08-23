package io.github.tickgrid.store;

import io.github.tickgrid.ingress.KeyIndex;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Interns repeated strings to dense integer ordinals, so a column can hold a 4-byte id instead of
 * an 8-byte reference to a string it shares with thousands of other rows.
 *
 * <p>Symbol, venue, status and side are the obvious candidates: a million-row order blotter might
 * contain five distinct status values. Storing those as references costs 8 MB and gives the GC a
 * million pointers to trace; storing them as ordinals costs 4 MB of {@code int[]} that the GC never
 * looks at.
 *
 * <p>Append-only and never compacted — an ordinal, once handed out, is valid forever. Interning
 * happens on producer threads during extraction, so it is lock-free and allocation-free for a
 * string that has been seen before. It reuses {@link KeyIndex}, which is exactly the same problem:
 * a preallocated, no-rehash, lock-free map from object to dense ordinal.
 */
public final class StringDictionary {

    private final KeyIndex<String> ids;
    private final AtomicReferenceArray<String> byId;

    public StringDictionary(int capacity) {
        this.ids = new KeyIndex<>(capacity);
        this.byId = new AtomicReferenceArray<>(capacity);
    }

    /**
     * Returns the ordinal for {@code value}, assigning one on first sight. Safe from any thread.
     *
     * @return the ordinal, or {@code -1} if the dictionary is full
     */
    public int intern(String value) {
        final int id = ids.getOrCreate(value);
        if (id >= 0 && byId.get(id) == null) {
            byId.set(id, value);      // idempotent: racing threads write the same string
        }
        return id;
    }

    /**
     * Returns the string for an ordinal, or {@code null} if it was never assigned.
     *
     * <p>A reader can arrive between the ordinal being published and the string being stored, so
     * this spins briefly on a known-assigned id. The gap is one instruction on the interning
     * thread.
     */
    public String get(int id) {
        if (id < 0 || id >= ids.size()) return null;
        String s = byId.get(id);
        for (int spin = 0; s == null && spin < 10_000; spin++) {
            Thread.onSpinWait();
            s = byId.get(id);
        }
        return s;
    }

    public int size()            { return ids.size(); }
    public int capacity()        { return ids.capacity(); }
    /** Interning attempts refused because the dictionary was full. */
    public long rejectedCount()  { return ids.rejectedCount(); }
}
