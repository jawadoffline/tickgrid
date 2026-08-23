package io.github.tickgrid.jmh;

import io.github.tickgrid.render.FixedFormat;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The formatting and parsing claims, measured properly.
 *
 * <p>The canvas spike measured these indirectly, as a difference in frame time across a whole grid:
 * 1.140 ms against 0.141 ms of FX-thread work, and 38 MB/s against 2.6 MB/s of garbage. That was
 * enough to make a decision on and too coarse to quote. This measures the operations themselves.
 *
 * <p>Both directions matter and for different reasons. Formatting runs once per painted cell per
 * frame, so its cost is multiplied by the visible cell count. Parsing runs once per message, so its
 * cost is multiplied by the feed rate — which is three orders of magnitude larger.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class FormatBenchmark {

    private final char[] buffer = new char[FixedFormat.MAX_CHARS];
    private final long[] scaled = new long[1024];
    private final String[] texts = new String[1024];
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        long rng = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < scaled.length; i++) {
            rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
            scaled[i] = Math.floorMod(rng, 100_000_000L);
            texts[i] = (scaled[i] / 100) + "." + String.format(Locale.ROOT, "%02d", scaled[i] % 100);
        }
    }

    private int next() {
        return (cursor = (cursor + 1) & 1023);
    }

    // ------------------------------------------------------------ formatting

    /** The hand-rolled path, including the String the canvas API forces. */
    @Benchmark
    public String fixedFormat() {
        final long v = scaled[next()];
        final int start = FixedFormat.fixed(buffer, v, 2);
        return new String(buffer, start, buffer.length - start);
    }

    /** Formatting into the buffer only, to separate the digits from the unavoidable allocation. */
    @Benchmark
    public int fixedFormatNoString() {
        return FixedFormat.fixed(buffer, scaled[next()], 2);
    }

    @Benchmark
    public String stringFormat() {
        return String.format(Locale.ROOT, "%.2f", scaled[next()] / 100.0);
    }

    @Benchmark
    public String groupedFormat() {
        final int start = FixedFormat.grouped(buffer, scaled[next()]);
        return new String(buffer, start, buffer.length - start);
    }

    @Benchmark
    public String stringFormatGrouped() {
        return String.format(Locale.ROOT, "%,d", scaled[next()]);
    }

    // -------------------------------------------------------------- parsing

    @Benchmark
    public long parseScaled() {
        return FixedFormat.parseScaled(texts[next()], 2);
    }

    /**
     * The conversion this replaces. It is not only slower — it is wrong for values like 0.29, which
     * {@code ParseScaledTest} pins.
     */
    @Benchmark
    public long parseViaDouble() {
        return (long) (Double.parseDouble(texts[next()]) * 100);
    }

    @Benchmark
    public void parseScaledSlice(Blackhole bh) {
        // What the Binance feed actually does: parse straight out of the received buffer.
        final String s = texts[next()];
        bh.consume(FixedFormat.parseScaled(s, 0, s.length(), 2));
    }
}
