package io.github.tickgrid.ingress;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free business-key to row-slot map, callable from any producer thread on the hot path.
 *
 * <p>Fully preallocated open addressing with linear probing and <b>no rehash</b>. That single
 * constraint is what makes it safe without locks: the table never moves, so a reader can never
 * observe a half-migrated state, and there is no growth protocol to get wrong. The cost is that
 * capacity is fixed at construction and the {@code capacity + 1}-th distinct key is rejected.
 *
 * <p>Slots are handed out densely from zero in first-seen order, so callers can use them to index
 * straight into primitive column arrays. That density is the reason this exists rather than a
 * {@code ConcurrentHashMap}: the columnar store needs an integer it can subscript with, and a hard
 * capacity bound it can preallocate against.
 *
 * <h2>Two arrays, and why not one array of entries</h2>
 * Keys and slots live in parallel arrays. The obvious objection is that a lookup then touches two
 * regions where a single array of key-and-slot entries would touch one, and that objection was
 * acted on: the entry version is {@code EntryKeyIndex} in the benchmark source, and it is
 * <b>slower</b> — 40.1M against 47.8M ops/sec at a thousand keys, 25.3M against 33.4M contended at
 * a hundred thousand.
 *
 * <p>The reasoning behind it was backwards. Reading {@code slotPlusOne[i]} is a direct load from an
 * {@code int[]} that the prefetcher handles happily; reading {@code entry.slot} is a pointer chase
 * to an object that has to be dereferenced first. The entry layout <i>added</i> an indirection
 * rather than removing one.
 *
 * <p>This layout is also faster than {@code ConcurrentHashMap} at low key counts and level with it
 * at high ones. That is worth stating explicitly because an earlier version of this comparison
 * concluded the opposite, from numbers taken in two different JMH runs — see BENCHMARKS.md for what
 * that cost.
 */
public final class KeyIndex<K> {

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
    public KeyIndex(int capacity) {
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
    public int getOrCreate(K key) {
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
    public int get(K key) {
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
    public int size() {
        return Math.min(nextSlot.get(), capacity);
    }

    public int capacity() {
        return capacity;
    }

    /** Submissions rejected because capacity was exhausted. */
    public long rejectedCount() {
        return rejected.sum();
    }

    /**
     * Mean distance between a key's ideal bucket and where it actually sits — a health metric for
     * the hash spread. Scans the whole table, so it is a diagnostic, never a hot-path call; keeping
     * running counters here would put two atomic increments on every {@code submit}.
     */
    public double meanProbeLength() {
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
