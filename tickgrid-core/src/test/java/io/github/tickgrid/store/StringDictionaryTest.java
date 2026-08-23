package io.github.tickgrid.store;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StringDictionaryTest {

    @Test
    void internsToStableDenseOrdinals() {
        StringDictionary d = new StringDictionary(64);
        int a = d.intern("XNAS");
        int b = d.intern("XLON");
        assertEquals(a, d.intern("XNAS"));
        assertNotEquals(a, b);
        assertEquals("XNAS", d.get(a));
        assertEquals("XLON", d.get(b));
        assertEquals(2, d.size());
    }

    @Test
    void equalStringsShareAnOrdinalRegardlessOfIdentity() {
        StringDictionary d = new StringDictionary(64);
        String one = "FILLED";
        String two = new StringBuilder("FILL").append("ED").toString();
        assertNotSame(one, two);
        assertEquals(d.intern(one), d.intern(two));
        assertEquals(1, d.size());
    }

    @Test
    void unknownOrdinalsResolveToNull() {
        StringDictionary d = new StringDictionary(8);
        assertNull(d.get(0));
        assertNull(d.get(-1));
        assertNull(d.get(999));
    }

    @Test
    void exhaustionIsReportedNotCorrupted() {
        StringDictionary d = new StringDictionary(4);
        for (int i = 0; i < 4; i++) assertTrue(d.intern("V" + i) >= 0);

        assertEquals(-1, d.intern("OVERFLOW"));
        assertEquals(1, d.rejectedCount());
        for (int i = 0; i < 4; i++) {
            assertEquals("V" + i, d.get(d.intern("V" + i)), "existing entry lost after overflow");
        }
    }

    /**
     * Interning happens on producer threads during extraction, so several may meet a new venue at
     * the same instant. They must agree on the ordinal, and a reader must never see the ordinal
     * before the string behind it.
     */
    @Test
    void concurrentInterningAgreesAndPublishes() throws Exception {
        final int values = 1_000;
        final int threads = 8;
        StringDictionary d = new StringDictionary(values);

        String[] names = new String[values];
        for (int i = 0; i < values; i++) names[i] = "VENUE" + i;

        int[][] seen = new int[threads][values];
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] ts = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < values; i++) {
                        int k = (i + id * 97) % values;
                        int ordinal = d.intern(names[k]);
                        seen[id][k] = ordinal;
                        // The reverse mapping must be usable the moment the ordinal is handed out.
                        assertEquals(names[k], d.get(ordinal));
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertNull(failure.get());

        Set<Integer> distinct = new HashSet<>();
        for (int k = 0; k < values; k++) {
            int expected = seen[0][k];
            distinct.add(expected);
            for (int t = 1; t < threads; t++) {
                assertEquals(expected, seen[t][k], "threads disagreed on the ordinal for " + names[k]);
            }
        }
        assertEquals(values, distinct.size(), "two strings were given the same ordinal");
        assertEquals(values, d.size());
    }
}
