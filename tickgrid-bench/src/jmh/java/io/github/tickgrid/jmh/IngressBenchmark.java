package io.github.tickgrid.jmh;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.RowApplier;
import io.github.tickgrid.ingress.RowExtractor;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * The ingestion path, which is what §6 asks for: throughput and allocation rate across a range of
 * key counts.
 *
 * <h2>Why there is a drain running</h2>
 * Measuring {@code submit} with nothing consuming measures the wrong thing. The dirty flag stays
 * set after the first message for each key, so every subsequent submit takes the cheap path, the
 * queue never cycles, and the number that comes out is a conflation hit rate rather than a
 * throughput. A drain thread runs alongside at a render-pulse cadence so the flags actually clear
 * and the queue actually turns over.
 *
 * <h2>Why the key count is the interesting axis</h2>
 * It decides both the conflation ratio and the cache behaviour. A thousand keys fit in L2 and are
 * mostly re-dirtying flags that are already set; a hundred thousand keys miss on every staging
 * write and clear their flags between visits. The design's own claim is about work scaling with
 * changed rows, and this is the axis that changes how many rows those are.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class IngressBenchmark {

    /** §6's dimension: 1k, 10k, 100k unique keys. */
    @Param({"1000", "10000", "100000"})
    public int keys;

    @Param({"8"})
    public int columns;

    static final class Tick {
        String symbol;
        long seq;
    }

    private ConflatingIngress<Tick, String> ingress;
    private String[] symbols;
    private Thread drain;
    private volatile boolean draining;

    @Setup(Level.Trial)
    public void setUp() {
        symbols = new String[keys];
        for (int i = 0; i < keys; i++) symbols[i] = "SYM" + i;

        final int cols = columns;
        ingress = new ConflatingIngress<>(keys, cols, new RowExtractor<Tick, String>() {
            @Override public String key(Tick row) {
                return row.symbol;
            }
            @Override public void extract(Tick row, long[] staging, int base) {
                for (int c = 0; c < cols; c++) staging[base + c] = row.seq + c;
            }
        });

        // Touch every key once so the benchmark measures the steady state rather than first sight,
        // which allocates the key entry and is bounded by capacity anyway.
        final Tick warm = new Tick();
        for (int i = 0; i < keys; i++) {
            warm.symbol = symbols[i];
            warm.seq = i;
            ingress.submit(warm);
        }

        draining = true;
        drain = new Thread(() -> {
            final RowApplier sink = (slot, values, count) -> { };
            while (draining) {
                ingress.drain(4_000_000L, sink);      // the design's 4 ms frame budget
                java.util.concurrent.locks.LockSupport.parkNanos(16_666_667L);
            }
        }, "drain");
        drain.setDaemon(true);
        drain.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        draining = false;
        drain.join(2_000);
    }

    /** Per-thread scratch, so the row object is not shared and not allocated per call. */
    @State(Scope.Thread)
    public static class Producer {
        final Tick tick = new Tick();
        long counter;
    }

    /**
     * One producer. This is the number to quote for a single feed handler thread, and the
     * configuration where the single-writer-per-key contract holds trivially.
     */
    @Benchmark
    public void submit(Producer producer) {
        final Tick t = producer.tick;
        t.symbol = symbols[(int) (producer.counter++ % keys)];
        t.seq = producer.counter;
        ingress.submit(t);
    }

    /**
     * Four producers over one shared key space.
     *
     * <p>Note that this deliberately <b>violates</b> the single-writer-per-key contract: threads
     * collide on keys, so the seqlock's assumption does not hold and a torn row is possible. It is
     * measured anyway because the contention it creates on the dirty flags and the MPSC queue is
     * real, and because knowing the cost of the shape the contract forbids is how you know what the
     * contract is buying. Do not read this row as an endorsement of writing it.
     */
    @Benchmark
    @Threads(4)
    public void submitContended(Producer producer) {
        final Tick t = producer.tick;
        t.symbol = symbols[(int) (producer.counter++ % keys)];
        t.seq = producer.counter;
        ingress.submit(t);
    }

    /** The lookup on its own, to separate the map's cost from the staging write. */
    @Benchmark
    public void keyLookup(Producer producer, Blackhole bh) {
        bh.consume(ingress.keyIndex().getOrCreate(symbols[(int) (producer.counter++ % keys)]));
    }
}
