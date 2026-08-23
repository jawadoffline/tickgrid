package io.github.tickgrid.bench;

import java.util.concurrent.locks.LockSupport;

/**
 * A rate-limited synthetic market feed, shared by every implementation under test so the comparison
 * is about the grid rather than about the data.
 *
 * <p>Each producer owns a disjoint slice of the instrument range, so every key has exactly one
 * writer — the contract TickGrid's seqlock relies on, and a constraint the {@code TableView}
 * implementations do not need but are not harmed by.
 *
 * <p>Every tick carries the {@code System.nanoTime()} at which it was submitted. That stamp is what
 * makes display staleness measurable end to end.
 */
public final class SyntheticFeed {

    /** Receives one tick. Implementations do whatever their architecture does with it. */
    public interface Sink {
        void onTick(int instrument, String symbol, long bid, long ask, long last,
                    double changePct, long volume, int trades, long submitNanos);
    }

    private final int instruments;
    private final int threads;
    private final long targetRate;
    private final Sink sink;
    private volatile boolean running;
    private Thread[] producers;
    private final java.util.concurrent.atomic.AtomicLong submitted =
            new java.util.concurrent.atomic.AtomicLong();

    public SyntheticFeed(int instruments, int threads, long targetRate, Sink sink) {
        this.instruments = instruments;
        this.threads = Math.max(1, threads);
        this.targetRate = targetRate;
        this.sink = sink;
    }

    public long submittedCount() {
        return submitted.get();
    }

    public void start() {
        running = true;
        producers = new Thread[threads];
        final int shard = Math.max(1, instruments / threads);
        for (int t = 0; t < threads; t++) {
            final int lo = t * shard;
            final int hi = (t == threads - 1) ? instruments : Math.min(instruments, lo + shard);
            producers[t] = new Thread(() -> run(lo, hi), "feed-" + t);
            producers[t].setDaemon(true);
            producers[t].start();
        }
    }

    public void stop() {
        running = false;
        if (producers == null) return;
        for (Thread t : producers) {
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run(int lo, int hi) {
        final int count = hi - lo;
        if (count <= 0) return;

        final String[] symbols = new String[count];
        final long[] price = new long[count];
        final long[] open = new long[count];
        final long[] volume = new long[count];
        for (int i = 0; i < count; i++) {
            symbols[i] = symbol(lo + i);
            price[i] = 1_000 + (long) ((lo + i) * 37L % 90_000);
            open[i] = price[i];
        }

        final long perThreadRate = Math.max(1, targetRate / threads);
        final double nanosPerMessage = 1e9 / perThreadRate;
        final long start = System.nanoTime();

        long rng = 0x9E3779B97F4A7C15L + lo;
        long sent = 0;
        int i = 0;

        while (running) {
            for (int batch = 0; batch < 64 && running; batch++) {
                rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
                final int k = i++ % count;

                price[k] = Math.max(100, price[k] + Math.floorMod(rng, 7) - 3);
                volume[k] += Math.floorMod(rng, 400);

                sink.onTick(lo + k, symbols[k],
                        price[k] - 1, price[k] + 1, price[k],
                        (price[k] - open[k]) * 100.0 / open[k],
                        volume[k], (int) (volume[k] / 100),
                        System.nanoTime());
                sent++;
            }
            submitted.addAndGet(64);

            final long due = start + (long) (sent * nanosPerMessage);
            long wait = due - System.nanoTime();
            while (wait > 0 && running) {
                if (wait > 1_000_000L) {
                    LockSupport.parkNanos(wait - 500_000L);
                } else {
                    Thread.onSpinWait();
                }
                wait = due - System.nanoTime();
            }
        }
    }

    public static String symbol(int index) {
        final String[] roots = {"AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO",
                                "LLY", "JPM", "XOM", "UNH", "COST", "HD", "MRK", "ABBV", "CVX",
                                "CRM", "PEP", "KO", "ADBE", "WMT", "BAC", "MCD", "ACN", "NFLX"};
        String root = roots[index % roots.length];
        int suffix = index / roots.length;
        return suffix == 0 ? root : root + suffix;
    }
}
