package io.github.tickgrid.jmh;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The rejected {@code KeyIndex} layout: one entry object per key, holding the key and its slot
 * together instead of in two parallel arrays.
 *
 * <p>Kept so the comparison stays reproducible, because the reasoning that produced it was wrong in
 * an instructive way. The theory was that two parallel arrays cost two cache misses per lookup and
 * one entry array would cost one. In fact reading {@code slotPlusOne[i]} is a direct load from an
 * {@code int[]}; reading {@code entry.slot} needs the entry dereferenced first. The change added an
 * indirection rather than removing one, and measured slower at every point.
 *
 * <p>It was accepted briefly on the strength of a <i>cross-run</i> comparison, which on this machine
 * is not a measurement at all: {@code ConcurrentHashMap} moved by 2.5x between two runs of code
 * that had not changed. Implementations have to be raced against each other inside one run, which
 * is why this class is here rather than in the history.
 *
 * <p>Not part of the library.
 */
final class EntryKeyIndex<K> {

    private static final int UNSET = -1;
    private static final int OVERFLOW = -2;

    private static final class Entry<K> {
        final K key;
        volatile int slot = UNSET;

        Entry(K key) {
            this.key = key;
        }
    }

    private final int capacity;
    private final int mask;
    private final AtomicReferenceArray<Entry<K>> table;
    private final AtomicInteger nextSlot = new AtomicInteger();

    EntryKeyIndex(int capacity) {
        this.capacity = capacity;
        int tableSize = Integer.highestOneBit(Math.max(4, capacity * 2 - 1)) * 2;
        this.mask = tableSize - 1;
        this.table = new AtomicReferenceArray<>(tableSize);
    }

    int getOrCreate(K key) {
        final int h = spread(key.hashCode());

        for (int probe = 0; probe <= mask; probe++) {
            final int i = (h + probe) & mask;
            Entry<K> e = table.get(i);

            if (e == null) {
                final Entry<K> mine = new Entry<>(key);
                if (table.compareAndSet(i, null, mine)) {
                    final int slot = nextSlot.getAndIncrement();
                    if (slot >= capacity) {
                        mine.slot = OVERFLOW;
                        return -1;
                    }
                    mine.slot = slot;
                    return slot;
                }
                e = table.get(i);
            }

            if (key.equals(e.key)) {
                int slot = e.slot;
                while (slot == UNSET) {
                    Thread.onSpinWait();
                    slot = e.slot;
                }
                return slot == OVERFLOW ? -1 : slot;
            }
        }
        return -1;
    }

    private static int spread(int h) {
        h *= 0x9E3779B9;
        return (h ^ (h >>> 16)) & 0x7FFFFFFF;
    }
}
