package io.github.tickgrid.ingress;

import io.github.tickgrid.ingress.ConflatingIngress.TearProtection;
import io.github.tickgrid.store.ColumnStore;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConflatingIngressTest {

    static final int COLUMNS = 16;

    /**
     * One mutable row, reused by its producer between submits. That is deliberate: it exercises the
     * contract that {@code submit} fully extracts before returning, so callers may recycle.
     *
     * <p>Column {@code i} always holds {@code seq + i}, which makes a torn row self-evident: any
     * row where {@code values[i] - values[0] != i} was assembled from two different messages.
     */
    static final class Tick {
        String symbol;
        long seq;
    }

    static final RowExtractor<Tick, String> EXTRACTOR = new RowExtractor<>() {
        @Override public String key(Tick row) {
            return row.symbol;
        }
        @Override public void extract(Tick row, long[] staging, int base) {
            for (int c = 0; c < COLUMNS; c++) {
                staging[base + c] = row.seq + c;
            }
        }
    };

    private static ConflatingIngress<Tick, String> ingress(int capacity) {
        return new ConflatingIngress<>(capacity, COLUMNS, EXTRACTOR);
    }

    private static Tick tick(String symbol, long seq) {
        Tick t = new Tick();
        t.symbol = symbol;
        t.seq = seq;
        return t;
    }

    // ------------------------------------------------------------- conflation

    @Test
    void drainCostIsProportionalToChangedRowsNotMessages() {
        ConflatingIngress<Tick, String> in = ingress(1000);
        Tick t = new Tick();

        for (int i = 0; i < 100_000; i++) {
            t.symbol = "SYM" + (i % 500);
            t.seq = i;
            in.submit(t);
        }

        long[] seen = new long[1000];
        int applied = in.drainAll((slot, values, count) -> seen[slot] = values[0]);

        assertEquals(500, applied, "one apply per changed row, not per message");
        assertEquals(100_000, in.submittedCount());
        assertEquals(200.0, in.conflationRatio(), 0.001);
    }

    @Test
    void onlyTheLatestValuePerKeySurvivesConflation() {
        ConflatingIngress<Tick, String> in = ingress(16);
        Tick t = new Tick();
        t.symbol = "AAPL";
        for (long seq = 1; seq <= 1000; seq++) {
            t.seq = seq;
            in.submit(t);
        }

        AtomicLong seen = new AtomicLong();
        assertEquals(1, in.drainAll((slot, values, count) -> seen.set(values[0])));
        assertEquals(1000, seen.get(), "conflation must keep the newest value, not the oldest");
    }

    @Test
    void quietSlotsAreNotRedelivered() {
        ConflatingIngress<Tick, String> in = ingress(16);
        in.submit(tick("AAPL", 1));
        assertEquals(1, in.drainAll((s, v, c) -> { }));
        assertEquals(0, in.drainAll((s, v, c) -> { }), "an unchanged row must not be re-applied");
    }

    // ------------------------------------------------------------ the handshake

    /**
     * The lost-update test, and the one that fails under {@code ClearPolicy.AFTER_COPY}.
     *
     * <p>The bug is self-healing for a hot key: a stranded value is picked up by the very next
     * submit, which finds the flag clear and re-enqueues. It only becomes visible when a key goes
     * <b>quiet immediately after losing the race</b> — an illiquid instrument, a resting order, a
     * symbol at end of session. So this gives each key a short burst and then abandons it, waiting
     * for the drain to go idle in between so that every burst races a consumer that polls the slot
     * at once. That is the interleaving the bug needs, and the situation it actually describes.
     *
     * <p>With the seqlock engaged this only catches the broken policy about 0.001% of the time, so
     * treat it as a property statement rather than a regression net —
     * {@link #quietKeyKeepsFinalValueWithSeqlockMaskingRemoved()} is the one with teeth. See
     * {@code HandshakeDemo} for the side-by-side.
     */
    @Test
    void aKeyThatGoesQuietKeepsItsFinalValue() throws Exception {
        assertEquals(0, quietKeyRun(20_000, TearProtection.SEQLOCK, true));
    }

    /**
     * The same property with the seqlock disengaged, and the test that actually guards it.
     *
     * <p>The seqlock <i>masks</i> the clear-order bug: a producer write that lands during the copy
     * trips a retry, so the drain re-reads and picks up the new value. That shrinks the losing
     * window to the few instructions between the seqlock's final recheck and the clear. Measured,
     * the broken policy strands 15-27% of quiet keys unmasked but only ~0.001% with the seqlock on
     * — so a regression in the clear ordering would slip past the test above almost every run.
     *
     * <p>Removing the masking mechanism isolates the property under test. Column consistency is not
     * asserted here, because without the seqlock torn rows are expected; only convergence is.
     */
    @Test
    void quietKeyKeepsFinalValueWithSeqlockMaskingRemoved() throws Exception {
        assertEquals(0, quietKeyRun(50_000, TearProtection.NONE, false));
    }

    /**
     * Gives each key a short burst and then abandons it, waiting for the drain to go idle in
     * between so every burst races a consumer that polls the slot at once.
     *
     * @return the number of keys whose final value never reached the applier
     */
    private int quietKeyRun(int keys, TearProtection tear, boolean checkColumns) throws Exception {
        final int columns = 128;                      // a wider row is a wider copy window to race
        final int burst = 3;

        RowExtractor<Tick, String> wide = new RowExtractor<>() {
            @Override public String key(Tick row) { return row.symbol; }
            @Override public void extract(Tick row, long[] staging, int base) {
                for (int c = 0; c < columns; c++) staging[base + c] = row.seq + c;
            }
        };
        ConflatingIngress<Tick, String> in = new ConflatingIngress<>(
                keys, columns, wide, ConflatingIngress.ClearPolicy.BEFORE_COPY, tear);

        final long[] lastApplied = new long[keys];
        final AtomicLong torn = new AtomicLong();
        Flag done = new Flag();

        Thread drain = new Thread(() -> {
            RowApplier applier = (slot, values, count) -> {
                if (checkColumns) {
                    for (int c = 1; c < count; c++) {
                        if (values[c] - values[0] != c) torn.incrementAndGet();
                    }
                }
                lastApplied[slot] = values[0];
            };
            while (!done.value) in.drain(500_000, applier);
            in.drainAll(applier);
        }, "drain");
        drain.start();

        String[] symbols = new String[keys];
        for (int k = 0; k < keys; k++) symbols[k] = "SYM" + k;

        Tick t = new Tick();
        for (int k = 0; k < keys; k++) {
            t.symbol = symbols[k];
            for (int j = 1; j <= burst; j++) {
                t.seq = j;
                assertTrue(in.submit(t));
            }
            // Let the drain settle, so the next burst races an idle consumer, not a backlog.
            for (int spin = 0; in.backlog() > 0 && spin < 1_000_000; spin++) Thread.onSpinWait();
        }
        done.value = true;
        drain.join();

        if (checkColumns) {
            assertEquals(0, torn.get(), "seqlock must prevent torn rows");
        }

        int stranded = 0;
        StringBuilder detail = new StringBuilder();
        for (int k = 0; k < keys; k++) {
            int slot = in.keyIndex().get(symbols[k]);
            assertTrue(slot >= 0, symbols[k] + " never got a slot");
            if (lastApplied[slot] != burst) {
                stranded++;
                if (detail.length() < 300) {
                    detail.append("\n  ").append(symbols[k])
                          .append(": applied=").append(lastApplied[slot])
                          .append(" expected=").append(burst);
                }
            }
        }
        if (stranded > 0) {
            fail(stranded + " of " + keys + " quiet rows are stuck at a stale value:" + detail);
        }
        return stranded;
    }

    /**
     * Hot keys under several producers: checks that continuous concurrent submission converges,
     * that no row is ever torn across producers, and that the queue sizing keeps {@code offer}
     * infallible. It does <b>not</b> catch the lost-update bug — hot keys heal it — which is why
     * {@link #aKeyThatGoesQuietKeepsItsFinalValue()} exists.
     */
    @Test
    void hotKeysConvergeUnderConcurrentDrain() throws Exception {
        final int keys = 64;
        final int producers = 4;
        final int perProducer = 150_000;

        ConflatingIngress<Tick, String> in = ingress(keys);
        ColumnStore store = new ColumnStore(keys, COLUMNS);

        final long[] lastSubmitted = new long[keys];      // one writer per index, by construction
        final AtomicLong torn = new AtomicLong();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Flag producersDone = new Flag();

        Thread drain = new Thread(() -> {
            RowApplier applier = (slot, values, count) -> {
                for (int c = 1; c < count; c++) {
                    if (values[c] - values[0] != c) torn.incrementAndGet();
                }
                store.apply(slot, values, count);
            };
            try {
                while (!producersDone.value) {
                    in.drain(500_000, applier);           // 0.5ms budget, like a render pulse
                }
                in.drainAll(applier);                     // quiescent final pass
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }, "drain");
        drain.start();

        Thread[] ts = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int id = p;
            ts[p] = new Thread(() -> {
                Tick t = new Tick();
                try {
                    for (int i = 0; i < perProducer; i++) {
                        int k = id + producers * (i % (keys / producers));
                        t.symbol = "SYM" + k;
                        t.seq = i + 1;
                        assertTrue(in.submit(t));
                        lastSubmitted[k] = i + 1;
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "producer-" + p);
            ts[p].start();
        }

        for (Thread t : ts) t.join();
        producersDone.value = true;
        drain.join();

        assertNull(failure.get());
        assertEquals(0, torn.get(), "seqlock must prevent torn rows");
        assertEquals(0, in.queueFullCount(), "queue sizing must make offer() infallible");

        int stale = 0;
        StringBuilder detail = new StringBuilder();
        for (int k = 0; k < keys; k++) {
            int slot = in.keyIndex().get("SYM" + k);
            assertTrue(slot >= 0, "key SYM" + k + " never got a slot");
            long stored = store.get(slot, 0);
            if (stored != lastSubmitted[k]) {
                stale++;
                if (detail.length() < 400) {
                    detail.append("\n  SYM").append(k)
                          .append(": stored=").append(stored)
                          .append(" lastSubmitted=").append(lastSubmitted[k]);
                }
            }
        }
        assertEquals(0, stale,
                stale + " of " + keys + " rows are stranded at an old value:" + detail);
    }

    /**
     * The torn-row test. This is the one that fails under {@code TearProtection.NONE}.
     *
     * <p>A single producer rewrites one slot as fast as it can while the drain copies it. Every row
     * the drain sees must satisfy the {@code values[i] == values[0] + i} invariant — a row that
     * mixes columns from two messages cannot.
     */
    @Test
    void noRowIsTornUnderConcurrentRewrite() throws Exception {
        ConflatingIngress<Tick, String> in = ingress(4);
        final AtomicLong torn = new AtomicLong();
        final AtomicLong applied = new AtomicLong();
        Flag done = new Flag();

        Thread drain = new Thread(() -> {
            RowApplier applier = (slot, values, count) -> {
                applied.incrementAndGet();
                for (int c = 1; c < count; c++) {
                    if (values[c] - values[0] != c) torn.incrementAndGet();
                }
            };
            while (!done.value) in.drainAll(applier);
            in.drainAll(applier);
        }, "drain");
        drain.start();

        Tick t = new Tick();
        t.symbol = "AAPL";
        for (long seq = 1; seq <= 2_000_000; seq++) {
            t.seq = seq;
            in.submit(t);
        }
        done.value = true;
        drain.join();

        assertTrue(applied.get() > 0, "the drain never observed the slot");
        assertEquals(0, torn.get(), torn.get() + " torn rows reached the applier");
    }

    // ----------------------------------------------------------------- budget

    @Test
    void budgetExhaustionDefersRatherThanDrops() {
        ConflatingIngress<Tick, String> in = ingress(5000);
        Tick t = new Tick();
        for (int i = 0; i < 5000; i++) {
            t.symbol = "SYM" + i;
            t.seq = i;
            in.submit(t);
        }

        long[] seen = new long[5000];
        int firstPass = in.drain(1, (slot, values, count) -> seen[slot] = values[0] + 1);
        assertTrue(firstPass < 5000, "a 1ns budget should not have drained everything");
        assertTrue(in.backlog() > 0, "the remainder must stay queued");

        int total = firstPass;
        while (in.backlog() > 0) {
            total += in.drain(1, (slot, values, count) -> seen[slot] = values[0] + 1);
        }
        assertEquals(5000, total, "every deferred row must eventually be applied");
        for (int i = 0; i < 5000; i++) {
            assertNotEquals(0, seen[i], "row " + i + " was dropped by the budget path");
        }
    }

    // ------------------------------------------------------------- rejection

    @Test
    void capacityExhaustionIsReportedNotSilent() {
        ConflatingIngress<Tick, String> in = ingress(4);
        for (int i = 0; i < 4; i++) assertTrue(in.submit(tick("SYM" + i, i)));

        assertFalse(in.submit(tick("OVERFLOW", 99)));
        assertEquals(1, in.rejectedCount());
        assertEquals(4, in.drainAll((s, v, c) -> { }), "rejected rows must not reach the drain");
    }

    // ------------------------------------------------------------ allocation

    /**
     * The zero-allocation claim, for the steady state the design actually promises: a key that has
     * been seen before. First sight of a key necessarily retains the key object, and that is both
     * bounded and unavoidable.
     */
    @Test
    void steadyStateSubmitDoesNotAllocate() {
        ConflatingIngress<Tick, String> in = ingress(1024);
        Tick t = new Tick();
        String[] symbols = new String[1024];
        for (int i = 0; i < symbols.length; i++) symbols[i] = "SYM" + i;

        for (int round = 0; round < 20; round++) {           // warm up JIT and touch every key
            for (int i = 0; i < 200_000; i++) {
                t.symbol = symbols[i & 1023];
                t.seq = i;
                in.submit(t);
            }
            in.drainAll((s, v, c) -> { });
        }

        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final int n = 1_000_000;
        long before = tmx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < n; i++) {
            t.symbol = symbols[i & 1023];
            t.seq = i;
            in.submit(t);
        }
        long bytes = tmx.getCurrentThreadAllocatedBytes() - before;
        double perSubmit = (double) bytes / n;

        assertTrue(perSubmit < 1.0,
                String.format("submit allocated %.3f bytes/call (%,d total)", perSubmit, bytes));
    }

    /** Tiny mutable holder; avoids the memory-model ambiguity of a captured array element. */
    static final class Flag {
        volatile boolean value;
    }
}
