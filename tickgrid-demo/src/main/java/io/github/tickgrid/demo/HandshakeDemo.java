package io.github.tickgrid.demo;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.ConflatingIngress.ClearPolicy;
import io.github.tickgrid.ingress.ConflatingIngress.TearProtection;
import io.github.tickgrid.ingress.RowApplier;
import io.github.tickgrid.ingress.RowExtractor;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demonstrates that the two corrections in {@code ConflatingIngress} are load-bearing, by running
 * the same workloads against the broken variants and counting the damage.
 *
 * <p>A test that passes against both the correct and the incorrect implementation proves nothing.
 * This is deliberately a {@code main} rather than a JUnit test: the broken configurations fail
 * <i>probabilistically</i>, so asserting that they fail would be a flaky test. Here the reproduction
 * rate is the output.
 *
 * <h2>Why the two scenarios use different workloads</h2>
 * The seqlock bug shows up under a <b>hot key</b> — one slot rewritten continuously while the drain
 * copies it. The lost-update bug does not, because it is self-healing for hot keys: a stranded
 * value is picked up by the very next submit, which finds the flag clear and re-enqueues. It only
 * becomes visible when a key <b>goes quiet immediately after losing the race</b> — an illiquid
 * instrument, a resting order, an end-of-session symbol. So scenario 1 runs many keys in short
 * bursts and then abandons each one, which is both the workload that reproduces the bug and the
 * production situation the bug actually describes.
 */
public final class HandshakeDemo {

    static final class Tick {
        String symbol;
        long seq;
    }

    static final class Flag { volatile boolean value; }

    /** Column i holds seq + i, so any row with values[i] - values[0] != i was assembled from two messages. */
    static RowExtractor<Tick, String> extractor(int columns) {
        return new RowExtractor<>() {
            @Override public String key(Tick row) { return row.symbol; }
            @Override public void extract(Tick row, long[] staging, int base) {
                for (int c = 0; c < columns; c++) staging[base + c] = row.seq + c;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        strandedScenario();
        System.out.println();
        tornScenario();
        System.out.println();
    }

    // ------------------------------------------------------ 1. lost updates

    static final int QUIET_KEYS = 200_000;
    static final int QUIET_COLUMNS = 128;   // a wider row means a wider copy window to race
    static final int BURST = 3;

    static void strandedScenario() throws Exception {
        System.out.printf(Locale.ROOT,
                "1. Dirty-flag clear ordering%n"
              + "   %,d keys, each given a %d-message burst and then abandoned; %d columns.%n"
              + "   The producer waits for the drain to go idle between keys, so every burst races%n"
              + "   a drain that polls the slot immediately - the interleaving the bug needs.%n"
              + "   A stranded row is one whose final value never reached the applier.%n%n",
                QUIET_KEYS, BURST, QUIET_COLUMNS);
        runStranded("   BEFORE_COPY  (correct)", ClearPolicy.BEFORE_COPY, TearProtection.SEQLOCK);
        runStranded("   AFTER_COPY   (the bug)", ClearPolicy.AFTER_COPY, TearProtection.SEQLOCK);
        runStranded("   AFTER_COPY,  unmasked ", ClearPolicy.AFTER_COPY, TearProtection.NONE);
        System.out.printf(Locale.ROOT,
                "%n   The seqlock partly masks the clear-order bug: a producer write during the copy%n"
              + "   trips a retry, so the drain re-reads and gets the new value. That shrinks the%n"
              + "   window to the gap between the final recheck and the clear - which makes the bug%n"
              + "   rarer, and therefore harder to diagnose, not less real.%n");
    }

    static void runStranded(String label, ClearPolicy policy, TearProtection tear) throws Exception {
        ConflatingIngress<Tick, String> in = new ConflatingIngress<>(
                QUIET_KEYS, QUIET_COLUMNS, extractor(QUIET_COLUMNS), policy, tear);

        final long[] lastApplied = new long[QUIET_KEYS];
        final Flag done = new Flag();
        final long t0 = System.nanoTime();

        Thread drain = new Thread(() -> {
            RowApplier applier = (slot, values, count) -> lastApplied[slot] = values[0];
            while (!done.value) in.drain(500_000, applier);
            in.drainAll(applier);
        }, "drain");
        drain.start();

        Tick t = new Tick();
        String[] symbols = new String[QUIET_KEYS];
        for (int k = 0; k < QUIET_KEYS; k++) symbols[k] = "SYM" + k;

        for (int k = 0; k < QUIET_KEYS; k++) {
            t.symbol = symbols[k];
            for (int j = 1; j <= BURST; j++) {
                t.seq = j;
                in.submit(t);
            }
            // key k is now quiet forever: nothing will heal a stranded value.
            // Let the drain catch up so the next burst races an idle consumer rather than a backlog.
            for (int spin = 0; in.backlog() > 0 && spin < 1_000_000; spin++) Thread.onSpinWait();
        }
        done.value = true;
        drain.join();

        int stranded = 0;
        for (int k = 0; k < QUIET_KEYS; k++) {
            int slot = in.keyIndex().get(symbols[k]);
            if (slot < 0 || lastApplied[slot] != BURST) stranded++;
        }
        System.out.printf(Locale.ROOT,
                "%-26s stranded rows: %,7d / %,d  (%.3f%%)   [%,d msgs, %,d applies, %.2fs]%n",
                label, stranded, QUIET_KEYS, 100.0 * stranded / QUIET_KEYS,
                in.submittedCount(), in.appliedCount(), (System.nanoTime() - t0) / 1e9);
    }

    // --------------------------------------------------------- 2. torn rows

    static final int TORN_COLUMNS = 16;
    static final int TORN_MESSAGES = 3_000_000;

    static void tornScenario() throws Exception {
        System.out.printf(Locale.ROOT,
                "2. Per-slot seqlock%n"
              + "   One hot key rewritten %,d times while the drain copies it; %d columns.%n"
              + "   A torn row is one whose columns came from two different messages.%n%n",
                TORN_MESSAGES, TORN_COLUMNS);
        runTorn("   SEQLOCK      (correct)", TearProtection.SEQLOCK);
        runTorn("   NONE         (the bug)", TearProtection.NONE);
    }

    static void runTorn(String label, TearProtection tear) throws Exception {
        ConflatingIngress<Tick, String> in = new ConflatingIngress<>(
                4, TORN_COLUMNS, extractor(TORN_COLUMNS), ClearPolicy.BEFORE_COPY, tear);

        final AtomicLong torn = new AtomicLong();
        final AtomicLong applied = new AtomicLong();
        final Flag done = new Flag();
        final long t0 = System.nanoTime();

        Thread drain = new Thread(() -> {
            RowApplier applier = (slot, values, count) -> {
                applied.incrementAndGet();
                for (int c = 1; c < count; c++) {
                    if (values[c] - values[0] != c) { torn.incrementAndGet(); break; }
                }
            };
            while (!done.value) in.drainAll(applier);
            in.drainAll(applier);
        }, "drain");
        drain.start();

        Tick t = new Tick();
        t.symbol = "AAPL";
        for (long seq = 1; seq <= TORN_MESSAGES; seq++) {
            t.seq = seq;
            in.submit(t);
        }
        done.value = true;
        drain.join();

        System.out.printf(Locale.ROOT,
                "%-26s torn rows: %,9d / %,d applied  (%.3f%%)   [%,d msgs, %.2fs]%n",
                label, torn.get(), applied.get(),
                applied.get() == 0 ? 0 : 100.0 * torn.get() / applied.get(),
                in.submittedCount(), (System.nanoTime() - t0) / 1e9);
    }
}
