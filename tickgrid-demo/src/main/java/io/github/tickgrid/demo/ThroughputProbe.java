package io.github.tickgrid.demo;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.RowApplier;
import io.github.tickgrid.ingress.RowExtractor;
import io.github.tickgrid.store.ColumnStore;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * A smoke measurement of the ingestion path against the design's step-2 criterion: six figures of
 * messages per second with near-zero allocation.
 *
 * <p>This is <b>not</b> the JMH benchmark. It has no forking, no blackholes and no statistical
 * rigour; it exists to say whether the premise is in the right order of magnitude before anyone
 * invests in a proper harness. Treat the numbers as indicative and do not publish them.
 *
 * <p>A drain runs concurrently on its own thread at a render-pulse cadence, because measuring
 * {@code submit} with no consumer measures the wrong thing — the conflation hit rate goes to 100%
 * and the queue never cycles.
 */
public final class ThroughputProbe {

    static final int COLUMNS = 12;
    static final int MESSAGES_PER_PRODUCER = 4_000_000;

    static final class Tick {
        String symbol;
        long seq;
    }

    static final class Flag { volatile boolean value; }

    static final RowExtractor<Tick, String> EXTRACTOR = new RowExtractor<>() {
        @Override public String key(Tick row) { return row.symbol; }
        @Override public void extract(Tick row, long[] staging, int base) {
            for (int c = 0; c < COLUMNS; c++) staging[base + c] = row.seq + c;
        }
    };

    public static void main(String[] args) throws Exception {
        System.out.printf(Locale.ROOT,
                "%nIngestion smoke probe - %d columns, %,d msgs per producer, concurrent drain.%n"
              + "Producers are sharded by key, honouring the single-writer-per-key contract.%n"
              + "NOT a JMH benchmark; indicative only.%n%n", COLUMNS, MESSAGES_PER_PRODUCER);

        System.out.printf(Locale.ROOT, "%9s %7s %14s %12s %11s %10s%n",
                "producers", "keys", "msgs/sec", "applies/sec", "conflation", "B/msg");
        System.out.println("-".repeat(70));

        for (int producers : new int[]{1, 2, 4}) {
            for (int keys : new int[]{1_000, 10_000, 100_000}) {
                run(producers, keys, true);           // warm
                run(producers, keys, false);
            }
        }
        pulsePaced();
    }

    // --------------------------------------------- conflation at pulse cadence

    /**
     * The ratios above are near 1:1 only because the drain runs flat out. Conflation is a function
     * of <b>per-key tick rate against frame rate</b>: a key that ticks less than 60 times a second
     * is already drawn every time it changes, and conflation has nothing to collapse. This paces
     * the drain at 60 Hz, which is where the real ratio shows up.
     */
    static void pulsePaced() throws Exception {
        System.out.printf(Locale.ROOT,
                "%nConflation at a 60 Hz drain, 2s per row.%n"
              + "predicted = feed rate / (keys x 60), floored at 1:1.%n%n");
        System.out.printf(Locale.ROOT, "%12s %8s %14s %12s %12s %11s%n",
                "feed rate", "keys", "ticks/key/s", "applies/frame", "conflation", "predicted");
        System.out.println("-".repeat(76));

        for (int rate : new int[]{200_000, 1_000_000}) {
            for (int keys : new int[]{500, 5_000, 50_000}) {
                paced(rate, keys);
            }
        }
        System.out.println();
    }

    static void paced(int targetRate, int keys) throws Exception {
        final long durationNanos = 2_000_000_000L;
        ConflatingIngress<Tick, String> in = new ConflatingIngress<>(keys, COLUMNS, EXTRACTOR);
        ColumnStore store = new ColumnStore(keys, COLUMNS);
        final Flag done = new Flag();
        final long[] frames = new long[1];

        Thread drain = new Thread(() -> {
            RowApplier applier = store.applier();
            while (!done.value) {
                long frameStart = System.nanoTime();
                in.drain(4_000_000, applier);         // 4ms budget, the design's figure
                frames[0]++;
                long until = frameStart + 16_666_667L;
                while (System.nanoTime() < until && !done.value) Thread.onSpinWait();
            }
            in.drainAll(applier);
        }, "drain");
        drain.start();

        String[] symbols = new String[keys];
        for (int k = 0; k < keys; k++) symbols[k] = "SYM" + k;

        Tick t = new Tick();
        final long start = System.nanoTime();
        long sent = 0;
        while (System.nanoTime() - start < durationNanos) {
            for (int b = 0; b < 256; b++) {
                t.symbol = symbols[(int) (sent % keys)];
                t.seq = sent++;
                in.submit(t);
            }
            // pace to the target rate
            long due = start + (long) (sent * (1e9 / targetRate));
            while (System.nanoTime() < due) Thread.onSpinWait();
        }
        done.value = true;
        drain.join();

        double seconds = (System.nanoTime() - start) / 1e9;
        double actualRate = sent / seconds;
        double predicted = Math.max(1.0, actualRate / (keys * 60.0));

        System.out.printf(Locale.ROOT, "%12s %8s %14.0f %12.0f %11.1f:1 %9.1f:1%n",
                compact(targetRate), compact(keys),
                actualRate / keys,
                frames[0] == 0 ? 0 : (double) in.appliedCount() / frames[0],
                in.conflationRatio(), predicted);
    }

    static void run(int producers, int keys, boolean warmup) throws Exception {
        final int messages = warmup ? MESSAGES_PER_PRODUCER / 8 : MESSAGES_PER_PRODUCER;
        ConflatingIngress<Tick, String> in = new ConflatingIngress<>(keys, COLUMNS, EXTRACTOR);
        ColumnStore store = new ColumnStore(keys, COLUMNS);
        final Flag done = new Flag();

        Thread drain = new Thread(() -> {
            RowApplier applier = store.applier();
            while (!done.value) in.drain(4_000_000, applier);   // 4ms, the design's budget
            in.drainAll(applier);
        }, "drain");
        drain.start();

        final long[] allocated = new long[producers];
        Thread[] ts = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int id = p;
            ts[p] = new Thread(() -> {
                com.sun.management.ThreadMXBean tmx =
                        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                // Each producer owns a disjoint key shard: one writer per key.
                int shard = keys / producers;
                String[] symbols = new String[shard];
                for (int i = 0; i < shard; i++) symbols[i] = "SYM" + (id * shard + i);

                Tick t = new Tick();
                long before = tmx.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < messages; i++) {
                    t.symbol = symbols[i % shard];
                    t.seq = i;
                    in.submit(t);
                }
                allocated[id] = tmx.getCurrentThreadAllocatedBytes() - before;
            }, "producer-" + id);
        }

        long t0 = System.nanoTime();
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        long elapsed = System.nanoTime() - t0;
        done.value = true;
        drain.join();

        if (warmup) return;

        long total = (long) messages * producers;
        long allocTotal = 0;
        for (long a : allocated) allocTotal += a;

        System.out.printf(Locale.ROOT, "%9d %7s %14s %12s %10.0f:1 %10.2f%n",
                producers, compact(keys),
                compact((long) (total / (elapsed / 1e9))),
                compact((long) (in.appliedCount() / (elapsed / 1e9))),
                in.conflationRatio(),
                (double) allocTotal / total);
    }

    static String compact(long v) {
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", v / 1e6);
        if (v >= 1_000) return String.format(Locale.ROOT, "%.0fk", v / 1e3);
        return Long.toString(v);
    }
}
