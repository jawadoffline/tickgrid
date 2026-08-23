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
}
