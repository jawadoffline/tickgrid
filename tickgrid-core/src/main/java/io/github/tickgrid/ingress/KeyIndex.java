package io.github.tickgrid.ingress;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free business-key to row-slot map, callable from any producer thread on the hot path.
 *
 * <p>Fully preallocated open addressing with linear probing and <b>no rehash</b>. That single
 * constraint is what makes lookup safe without locks: the table never moves, so a reader can never
 * observe a half-migrated state, and there is no growth protocol to get wrong. The cost is that
 * capacity is fixed at construction and the {@code capacity + 1}-th simultaneously live key is
 * rejected.
 *
 * <p>Slots are handed out densely from zero, so callers can use them to index straight into
 * primitive column arrays. That density is the reason this exists rather than a
 * {@code ConcurrentHashMap}: the columnar store needs an integer it can subscript with, and a hard
 * capacity bound it can preallocate against.
 *
 * <h2>Lookup is lock-free; insertion takes a lock</h2>
 * A key that already has a slot resolves with no lock, no CAS and no spin: a volatile read of the
 * bucket, a comparison, and a volatile read of the slot. That is the path a feed actually runs, and
 * it is the one the benchmarks measure.
 *
 * <p>Assigning a slot to a key seen for the first time takes {@code insertLock}. An earlier version
 * did that with a bucket CAS instead, which is fine as long as nothing is ever removed and unsound
 * once removal exists: a thread claiming a tombstoned bucket early in a probe path can race a
 * thread claiming a null bucket later in the same path, and the key ends up with two slots and two
 * rows. Serialising insertion removes the race outright rather than narrowing it.
 *
 * <p>Insertion happens once per distinct key, a few thousand times for a full instrument universe
 * against millions of lookups a second, so the lock is not on a path that matters. It also buys two
 * simplifications: the free list needs no atomics, and the slot can be published <i>before</i> the
 * key, which removes the reader-side spin the CAS version needed to cover the window between the
 * two writes.
 *
 * <p>Measured against the pre-retirement class — {@code CasKeyIndex} in the benchmark source — over
 * four runs, the difference is below this machine's noise floor: at a hundred thousand keys the
 * ratio flips sign between runs while unchanged code varies by 1.5x. See BENCHMARKS.md.
 *
 * <h2>Removal</h2>
 * {@link #retire} replaces a key with a tombstone, so lookups miss it while probe chains that run
 * through the bucket stay intact. The slot does not come back here immediately: it is handed to the
 * caller, which must show that no live view snapshot still references it before returning it
 * through {@link #recycle}. {@link ConflatingIngress} does both.
 *
 * <p>Tombstones are reused by later insertions, a probe path that crosses one claiming it in
 * preference to a fresh bucket, so a churning key set does not consume the table. That keeps
 * tombstones bounded in practice rather than by construction: an insertion whose probe path happens
 * to cross none still takes a fresh bucket. With the load factor at or below 0.5 the drift is slow,
 * and {@link #tombstoneCount()} is exposed so it can be watched rather than assumed.
 *
 * <h2>Two arrays, and why not one array of entries</h2>
 * Keys and slots live in parallel arrays. The obvious objection is that a lookup then touches two
 * regions where a single array of key-and-slot entries would touch one, and that objection was
 * acted on: the entry version is {@code EntryKeyIndex} in the benchmark source, and it is
 * <b>slower</b> -- 40.1M against 47.8M ops/sec at a thousand keys, 25.3M against 33.4M contended at
 * a hundred thousand.
 *
 * <p>The reasoning behind it was backwards. Reading {@code slotPlusOne[i]} is a direct load from an
 * {@code int[]} that the prefetcher handles happily; reading {@code entry.slot} is a pointer chase
 * to an object that has to be dereferenced first. The entry layout <i>added</i> an indirection
 * rather than removing one.
 *
 * <p>This layout is also faster than {@code ConcurrentHashMap} at low key counts and level with it
 * at high ones. That is worth stating explicitly because an earlier version of this comparison
 * concluded the opposite, from numbers taken in two different JMH runs -- see BENCHMARKS.md for
 * what that cost.
 */
public final class KeyIndex<K> {

    /** Marks a bucket whose key was retired. Probes pass through it; insertions may claim it. */
    private static final Object TOMBSTONE = new Object();

    /** Slot value for a key that reached the table after capacity had run out. */
    private static final int OVERFLOW = -1;

    private final int capacity;
    private final int mask;
    /** Each bucket holds {@code null}, a key, or {@link #TOMBSTONE}. */
    private final AtomicReferenceArray<Object> keys;
    /** 0 = no slot, {@link #OVERFLOW} = key accepted but no slot left, else slot + 1. */
    private final AtomicIntegerArray slotPlusOne;
    private final LongAdder rejected = new LongAdder();

    private final Object insertLock = new Object();

    /** Slots returned by {@link #recycle}, reissued before any fresh one. Guarded by the lock. */
    private final int[] freeSlots;
    private int freeCount;                                        // guarded by insertLock
    private final AtomicInteger nextSlot = new AtomicInteger();   // written under insertLock
    private volatile int liveKeys;                                // written under insertLock
    private volatile int tombstones;                              // written under insertLock

    /**
     * @param capacity maximum number of slots outstanding at one time. A retired key holds its slot
     *                 until {@link #recycle}, so on a key set that turns over this must cover the
     *                 live keys <i>and</i> the retirements not yet recycled. The backing table is
     *                 sized to the next power of two at or above {@code 2 * capacity}, so load
     *                 factor stays at or below 0.5 and probe sequences stay short.
     */
    public KeyIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        int tableSize = Integer.highestOneBit(Math.max(4, capacity * 2 - 1)) * 2;
        this.mask = tableSize - 1;
        this.keys = new AtomicReferenceArray<>(tableSize);
        this.slotPlusOne = new AtomicIntegerArray(tableSize);
        this.freeSlots = new int[capacity];
    }

    /**
     * Returns the slot for {@code key}, assigning a new one on first sight.
     *
     * <p>Safe to call concurrently from any number of threads. A key that already holds a slot
     * resolves without taking a lock; a key seen for the first time is assigned one under the
     * insertion lock.
     *
     * @return the slot, or {@code -1} if capacity is exhausted (see {@link #rejectedCount()})
     */
    public int getOrCreate(K key) {
        final int h = spread(key.hashCode());

        for (int probe = 0; probe <= mask; probe++) {
            final int i = (h + probe) & mask;
            final Object cur = keys.get(i);
            if (cur == null) return insert(key, h);    // absent: allocate under the lock
            if (cur == TOMBSTONE) continue;            // a retired key; the chain runs on
            if (key.equals(cur)) return slotOf(i);
        }
        // Every bucket is live or tombstoned. Only the locked path can tell those apart safely.
        return insert(key, h);
    }

    /** Returns the slot for {@code key} without creating one, or {@code -1} if absent. */
    public int get(K key) {
        final int h = spread(key.hashCode());
        for (int probe = 0; probe <= mask; probe++) {
            final int i = (h + probe) & mask;
            final Object cur = keys.get(i);
            if (cur == null) return -1;
            if (cur == TOMBSTONE) continue;
            if (key.equals(cur)) return slotOf(i);
        }
        return -1;
    }

    /**
     * Releases {@code key}, so it no longer resolves and its slot can eventually be reissued.
     *
     * <p>The slot is <b>not</b> free on return. It is handed to the caller, which must not reuse it
     * until it can show that no published view snapshot still lists it, and must then return it
     * through {@link #recycle}.
     *
     * <p>Subject to the same single-writer-per-key contract as {@link ConflatingIngress#submit}:
     * retiring a key concurrently with submitting it is a caller error. Concurrent work on
     * <i>other</i> keys is unaffected, and lookups stay lock-free throughout.
     *
     * @return the retired slot, or {@code -1} if the key held none
     */
    public int retire(K key) {
        final int h = spread(key.hashCode());
        synchronized (insertLock) {
            for (int probe = 0; probe <= mask; probe++) {
                final int i = (h + probe) & mask;
                final Object cur = keys.get(i);
                if (cur == null) return -1;
                if (cur == TOMBSTONE) continue;
                if (key.equals(cur)) {
                    final int sp = slotPlusOne.get(i);
                    // Only the key is cleared. A reader that matched it an instant ago is already
                    // committed to reading slotPlusOne, and leaving the old slot there is harmless
                    // -- that row is about to be tombstoned in the store too. Zeroing it instead
                    // would hand that reader a slot of 0, which is a real row.
                    keys.set(i, TOMBSTONE);
                    tombstones++;
                    if (sp > 0) {
                        liveKeys--;
                        return sp - 1;
                    }
                    return -1;                          // an OVERFLOW bucket; it never held a slot
                }
            }
            return -1;
        }
    }

    /**
     * Returns a slot from {@link #retire} to the free pool, to be reissued to the next new key.
     *
     * <p>Call this only once the slot is provably unreferenced by any live view snapshot. Calling
     * it early is the one way to make this class hand out a slot that something is still drawing.
     */
    public void recycle(int slot) {
        if (slot < 0 || slot >= capacity) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + capacity);
        }
        synchronized (insertLock) {
            if (freeCount < freeSlots.length) freeSlots[freeCount++] = slot;
        }
    }

    /** Assigns a slot to a key not yet in the table. Serialised; see the class note. */
    private int insert(K key, int h) {
        synchronized (insertLock) {
            int firstTombstone = -1;
            for (int probe = 0; probe <= mask; probe++) {
                final int i = (h + probe) & mask;
                final Object cur = keys.get(i);
                if (cur == TOMBSTONE) {
                    if (firstTombstone < 0) firstTombstone = i;
                    continue;
                }
                // A null bucket ends the chain, and that is what proves the key is absent. Only
                // now is it safe to fall back to a tombstone seen earlier: claiming one before the
                // chain has been walked to its end could shadow the key's real bucket.
                if (cur == null) return claim(firstTombstone >= 0 ? firstTombstone : i, key);
                if (key.equals(cur)) return slotOf(i);
            }
            if (firstTombstone >= 0) return claim(firstTombstone, key);
            rejected.increment();
            return -1;
        }
    }

    /** Publishes {@code key} into an empty or tombstoned bucket. Caller holds the insertion lock. */
    private int claim(int bucket, K key) {
        final boolean reused = keys.get(bucket) == TOMBSTONE;
        final int slot = allocateSlot();

        // Slot before key, both volatile. A reader reaches slotPlusOne only after it has seen the
        // key, so publishing in this order means it can never observe a key without its slot --
        // which is what lets the read path drop the spin the CAS version needed.
        slotPlusOne.set(bucket, slot < 0 ? OVERFLOW : slot + 1);
        keys.set(bucket, key);

        if (reused) tombstones--;
        if (slot < 0) {
            rejected.increment();
            return -1;
        }
        liveKeys++;
        return slot;
    }

    /** Caller holds the insertion lock. Recycled slots are reissued before fresh ones. */
    private int allocateSlot() {
        if (freeCount > 0) return freeSlots[--freeCount];
        final int s = nextSlot.get();
        if (s >= capacity) return -1;
        nextSlot.set(s + 1);
        return s;
    }

    private int slotOf(int bucket) {
        final int sp = slotPlusOne.get(bucket);
        return sp <= 0 ? -1 : sp - 1;                  // OVERFLOW, or a bucket with no slot at all
    }

    /** Keys currently holding a slot. */
    public int size() {
        return liveKeys;
    }

    public int capacity() {
        return capacity;
    }

    /** Slots retired and returned, waiting to be reissued. */
    public int freeSlotCount() {
        synchronized (insertLock) {
            return freeCount;
        }
    }

    /**
     * Buckets holding a retired key. Each costs a probe step until an insertion reclaims it, so a
     * steadily rising count on a long-lived process is the signal that the table is drifting
     * towards full even though slots are being reused.
     */
    public int tombstoneCount() {
        return tombstones;
    }

    /** Distinct slots ever issued from fresh. The watermark the store has had to touch. */
    public int slotsIssued() {
        return Math.min(nextSlot.get(), capacity);
    }

    /** Submissions rejected because capacity was exhausted. */
    public long rejectedCount() {
        return rejected.sum();
    }

    /**
     * Mean distance between a key's ideal bucket and where it actually sits, a health metric for
     * the hash spread. Scans the whole table, so it is a diagnostic, never a hot-path call; keeping
     * running counters here would put two atomic increments on every {@code submit}.
     */
    public double meanProbeLength() {
        long total = 0;
        int occupied = 0;
        for (int i = 0; i <= mask; i++) {
            final Object k = keys.get(i);
            if (k == null || k == TOMBSTONE) continue;
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
