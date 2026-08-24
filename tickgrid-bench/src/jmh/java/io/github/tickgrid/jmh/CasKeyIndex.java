package io.github.tickgrid.jmh;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * {@code KeyIndex} as it stood before retirement existed: insertion by bucket CAS, no tombstones,
 * and a reader-side spin covering the window between publishing a key and publishing its slot.
 *
 * <p>Kept so that adding removal can be shown to have cost nothing on the read path. That claim has
 * to be settled inside one JMH run -- run-to-run variance on this machine reached 2.5x on unchanged
 * code, which is how an earlier comparison of this very class reached a conclusion that had to be
 * retracted. See BENCHMARKS.md.
 *
 * <p>Not part of the library, and not correct in the presence of removal: two threads inserting the
 * same key can be given different slots once buckets can be tombstoned, which is the race that made
 * the lock necessary.
 */
final class CasKeyIndex<K> {

    private static final int OVERFLOW = -1;

    private final int capacity;
    private final int mask;
    private final AtomicReferenceArray<K> keys;
    /** 0 = not yet published, OVERFLOW = key accepted but no slot left, else slot + 1. */
    private final AtomicIntegerArray slotPlusOne;
    private final AtomicInteger nextSlot = new AtomicInteger();
    private final LongAdder rejected = new LongAdder();

    /**
     * @param capacity maximum number of distinct keys. The backing table is sized to the next
     *                 power of two at or above {@code 2 * capacity}, so load factor stays at or
     *                 below 0.5 and probe sequences stay short.
     */
    CasKeyIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        int tableSize = Integer.highestOneBit(Math.max(4, capacity * 2 - 1)) * 2;
        this.mask = tableSize - 1;
        this.keys = new AtomicReferenceArray<>(tableSize);
        this.slotPlusOne = new AtomicIntegerArray(tableSize);
    }

    /**
     * Returns the slot for {@code key}, assigning a new one on first sight.
     *
     * <p>Safe to call concurrently from any number of threads. Two threads racing on the same new
     * key always agree on the slot: exactly one wins the bucket CAS and the other reads its result.
     *
     * @return the slot, or {@code -1} if capacity is exhausted (see {@link #rejectedCount()})
     */
    int getOrCreate(K key) {
        final int h = spread(key.hashCode());

        for (int probe = 0; probe <= mask; probe++) {
            final int i = (h + probe) & mask;
            K cur = keys.get(i);

            if (cur == null) {
                if (keys.compareAndSet(i, null, key)) {
                    final int slot = nextSlot.getAndIncrement();
                    if (slot >= capacity) {
                        slotPlusOne.set(i, OVERFLOW);  // publish the rejection, don't strand readers
                        rejected.increment();
                        return -1;
                    }
                    slotPlusOne.set(i, slot + 1);      // volatile write publishes the pair
                    return slot;
                }
                cur = keys.get(i);                     // lost the race; the winner is now visible
            }

            if (key.equals(cur)) {
                return awaitSlot(i);
            }
        }
        rejected.increment();
        return -1;
    }

    /** Returns the slot for {@code key} without creating one, or {@code -1} if absent. */
    int get(K key) {
        final int h = spread(key.hashCode());
        for (int probe = 0; probe <= mask; probe++) {
            final int i = (h + probe) & mask;
            final K cur = keys.get(i);
            if (cur == null) return -1;
            if (key.equals(cur)) return awaitSlot(i);
        }
        return -1;
    }

    /**
     * A key is published before its slot, so a reader can arrive in between. The wait is bounded by
     * two instructions on the winning thread, not by any lock.
     */
    private int awaitSlot(int bucket) {
        int sp;
        while ((sp = slotPlusOne.get(bucket)) == 0) {
            Thread.onSpinWait();
        }
        return sp == OVERFLOW ? -1 : sp - 1;
    }

    /** Number of distinct keys assigned a slot. */
    int size() {
        return Math.min(nextSlot.get(), capacity);
    }

    int capacity() {
        return capacity;
    }

    /** Submissions rejected because capacity was exhausted. */
    long rejectedCount() {
        return rejected.sum();
    }

    /**
     * Mean distance between a key's ideal bucket and where it actually sits — a health metric for
     * the hash spread. Scans the whole table, so it is a diagnostic, never a hot-path call; keeping
     * running counters here would put two atomic increments on every {@code submit}.
     */
    double meanProbeLength() {
        long total = 0;
        int occupied = 0;
        for (int i = 0; i <= mask; i++) {
            final K k = keys.get(i);
            if (k == null) continue;
            occupied++;
            total += (i - (spread(k.hashCode()) & mask)) & mask;
        }
        return occupied == 0 ? 0 : (double) total / occupied;
    }

    /** Fibonacci-style mixing; guards against poor {@code hashCode} low bits with a power-of-two mask. */
    private static int spread(int h) {
        h *= 0x9E3779B9;
        return (h ^ (h >>> 16)) & 0x7FFFFFFF;
    }
}
