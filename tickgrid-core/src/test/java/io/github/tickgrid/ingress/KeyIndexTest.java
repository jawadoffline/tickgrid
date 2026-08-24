package io.github.tickgrid.ingress;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class KeyIndexTest {

    @Test
    void sameKeyAlwaysReturnsSameSlot() {
        KeyIndex<String> index = new KeyIndex<>(64);
        int a = index.getOrCreate("AAPL");
        assertEquals(a, index.getOrCreate("AAPL"));
        assertEquals(a, index.get("AAPL"));
        assertNotEquals(a, index.getOrCreate("MSFT"));
        assertEquals(2, index.size());
    }

    @Test
    void slotsAreDenseFromZero() {
        KeyIndex<String> index = new KeyIndex<>(1000);
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            slots.add(index.getOrCreate("SYM" + i));
        }
        assertEquals(1000, slots.size());
        assertEquals(0, slots.stream().mapToInt(Integer::intValue).min().orElseThrow());
        assertEquals(999, slots.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void absentKeyIsNotCreatedByGet() {
        KeyIndex<String> index = new KeyIndex<>(16);
        assertEquals(-1, index.get("NOPE"));
        assertEquals(0, index.size());
    }

    @Test
    void capacityExhaustionIsRejectedNotCorrupted() {
        KeyIndex<String> index = new KeyIndex<>(8);
        for (int i = 0; i < 8; i++) {
            assertTrue(index.getOrCreate("K" + i) >= 0);
        }
        assertEquals(-1, index.getOrCreate("OVERFLOW"));
        assertEquals(1, index.rejectedCount());

        // The rejection must be stable and must not disturb keys that were already assigned.
        assertEquals(-1, index.getOrCreate("OVERFLOW"));
        for (int i = 0; i < 8; i++) {
            assertTrue(index.get("K" + i) >= 0, "existing key lost after overflow");
        }
        assertEquals(8, index.size());
    }

    /**
     * The race that matters: many threads meeting the same key for the first time. Exactly one may
     * win the bucket, and every other thread must observe that winner's slot — never {@code 0}
     * from an unpublished bucket, never a slot of its own.
     */
    @Test
    void concurrentCreationAgreesOnSlot() throws Exception {
        final int keys = 2_000;
        final int threads = 8;
        KeyIndex<String> index = new KeyIndex<>(keys);

        String[] names = new String[keys];
        for (int i = 0; i < keys; i++) names[i] = "SYM" + i;

        int[][] seen = new int[threads][keys];
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] ts = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < keys; i++) {
                        // walk from a different offset per thread to maximise collisions
                        int k = (i + id * 137) % keys;
                        seen[id][k] = index.getOrCreate(names[k]);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertNull(failure.get());

        for (int k = 0; k < keys; k++) {
            int expected = seen[0][k];
            assertTrue(expected >= 0, "key " + k + " got no slot");
            for (int t = 1; t < threads; t++) {
                assertEquals(expected, seen[t][k],
                        "threads disagreed on the slot for " + names[k]);
            }
        }
        assertEquals(keys, index.size());

        Set<Integer> distinct = new HashSet<>();
        for (int k = 0; k < keys; k++) distinct.add(seen[0][k]);
        assertEquals(keys, distinct.size(), "two keys were given the same slot");
    }

    @Test
    void probeLengthStaysShortAtHalfLoad() {
        KeyIndex<String> index = new KeyIndex<>(10_000);
        for (int i = 0; i < 10_000; i++) index.getOrCreate("SYM" + i);
        assertTrue(index.meanProbeLength() < 1.0,
                "mean probe length " + index.meanProbeLength() + " suggests a bad spread");
    }

    // ------------------------------------------------------------- retirement

    @Test
    void aRetiredKeyStopsResolving() {
        KeyIndex<String> index = new KeyIndex<>(64);
        int slot = index.getOrCreate("AAPL");

        assertEquals(slot, index.retire("AAPL"));
        assertEquals(-1, index.get("AAPL"));
        assertEquals(0, index.size());
        assertEquals(1, index.tombstoneCount());
        assertFalse(index.retire("AAPL") >= 0, "retiring twice must not report a second slot");
    }

    @Test
    void aRetiredSlotIsNotReissuedUntilItIsRecycled() {
        // The gap between the two is the whole safety mechanism: the slot is off the books for
        // lookups but must stay off the free list until the caller says no snapshot references it.
        KeyIndex<String> index = new KeyIndex<>(64);
        int slot = index.getOrCreate("AAPL");
        index.retire("AAPL");

        assertEquals(0, index.freeSlotCount());
        assertNotEquals(slot, index.getOrCreate("MSFT"), "a parked slot must not be handed out");

        index.recycle(slot);
        assertEquals(1, index.freeSlotCount());
        assertEquals(slot, index.getOrCreate("GOOG"), "a recycled slot is reissued first");
        assertEquals(0, index.freeSlotCount());
    }

    @Test
    void aRetiredKeyComingBackGetsAFreshSlot() {
        KeyIndex<String> index = new KeyIndex<>(64);
        int first = index.getOrCreate("AAPL");
        index.retire("AAPL");

        int second = index.getOrCreate("AAPL");
        assertNotEquals(first, second, "the old slot is still parked, so this must be a new row");
        assertEquals(second, index.get("AAPL"));
    }

    /**
     * The point of the whole exercise. A universe that turns over completely, many times over,
     * against a table that could never hold all those keys at once.
     */
    @Test
    void aChurningKeySetRunsIndefinitelyWithinCapacity() {
        final int capacity = 64;
        KeyIndex<String> index = new KeyIndex<>(capacity);

        for (int round = 0; round < 200; round++) {
            for (int i = 0; i < capacity; i++) {
                final String key = "R" + round + "S" + i;
                final int slot = index.getOrCreate(key);
                assertTrue(slot >= 0 && slot < capacity,
                        "round " + round + " key " + i + " got slot " + slot);
            }
            assertEquals(capacity, index.size());
            for (int i = 0; i < capacity; i++) {
                index.recycle(index.retire("R" + round + "S" + i));
            }
            assertEquals(0, index.size());
        }

        assertEquals(0, index.rejectedCount(), "12,800 keys through 64 slots, nothing rejected");
    }

    /**
     * Linear probing means a retired bucket sits in the middle of other keys' probe chains. Nulling
     * it would cut those chains and lose keys that are still live; the tombstone is what keeps them
     * reachable.
     */
    @Test
    void retiringDoesNotBreakTheProbeChainOfAColludingKey() {
        KeyIndex<String> index = new KeyIndex<>(2048);

        // Fill densely enough that collisions are certain, then retire every other key and check
        // that the survivors all still resolve to the slots they were given.
        final int n = 1000;
        int[] slots = new int[n];
        for (int i = 0; i < n; i++) slots[i] = index.getOrCreate("K" + i);

        for (int i = 0; i < n; i += 2) index.retire("K" + i);

        for (int i = 1; i < n; i += 2) {
            assertEquals(slots[i], index.get("K" + i), "K" + i + " went missing behind a tombstone");
        }
        for (int i = 0; i < n; i += 2) {
            assertEquals(-1, index.get("K" + i));
        }
    }

    @Test
    void tombstonesAreReclaimedByLaterInsertions() {
        // Without reuse the table fills with dead buckets and rejects new keys even though every
        // slot is free. This is the check that insertion prefers a tombstone it has walked past.
        final int capacity = 32;
        KeyIndex<String> index = new KeyIndex<>(capacity);

        for (int round = 0; round < 100; round++) {
            for (int i = 0; i < capacity; i++) index.getOrCreate("R" + round + "S" + i);
            for (int i = 0; i < capacity; i++) index.recycle(index.retire("R" + round + "S" + i));
        }

        assertTrue(index.tombstoneCount() <= capacity * 2,
                "tombstones should be reclaimed, not accumulated: " + index.tombstoneCount());
    }

    @Test
    void recycleRejectsASlotOutsideCapacity() {
        KeyIndex<String> index = new KeyIndex<>(8);
        assertThrows(IndexOutOfBoundsException.class, () -> index.recycle(8));
        assertThrows(IndexOutOfBoundsException.class, () -> index.recycle(-1));
    }

    /**
     * Retirement takes the insertion lock; lookups do not. This is the check that the two do not
     * interfere — every live key stays resolvable throughout, and no two live keys ever share a
     * slot.
     */
    @Test
    void lookupsStayCorrectWhileKeysAreRetiredConcurrently() throws Exception {
        final int capacity = 512;
        final KeyIndex<String> index = new KeyIndex<>(capacity);

        // Half the keys are permanent, half churn underneath them.
        final int stable = 128;
        int[] stableSlots = new int[stable];
        for (int i = 0; i < stable; i++) stableSlots[i] = index.getOrCreate("STABLE" + i);

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CyclicBarrier start = new CyclicBarrier(3);

        Thread churn = new Thread(() -> {
            try {
                start.await();
                for (int round = 0; round < 300; round++) {
                    for (int i = 0; i < 64; i++) {
                        final String key = "CHURN" + round + "_" + i;
                        int slot = index.getOrCreate(key);
                        if (slot < 0) throw new AssertionError("rejected at round " + round);
                        index.recycle(index.retire(key));
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int pass = 0; pass < 3000; pass++) {
                    for (int i = 0; i < stable; i++) {
                        if (index.get("STABLE" + i) != stableSlots[i]) {
                            throw new AssertionError("STABLE" + i + " moved or vanished");
                        }
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        churn.start();
        reader.start();
        start.await();
        churn.join();
        reader.join();

        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(stable, index.size());
    }
}
