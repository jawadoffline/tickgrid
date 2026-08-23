package io.github.tickgrid.bench;

import org.HdrHistogram.Histogram;

import java.util.Locale;

/**
 * Frame intervals and display staleness for one measurement window.
 *
 * <h2>Two numbers, and only one of them is frame time</h2>
 * Frame time says whether the UI is smooth. It does not say whether the UI is <i>current</i> — a
 * grid can hold a perfect 60 fps while showing values from four seconds ago, which for a blotter is
 * a worse failure than a dropped frame. So this also records <b>display staleness</b>: for every
 * visible row on every frame, how old the value being shown actually is, measured from the moment
 * the producer submitted it.
 *
 * <p>That is the honest cost of conflation. Conflation trades staleness for throughput on purpose,
 * and a benchmark that reports only throughput is hiding the side of the trade the reader cares
 * about.
 */
public final class FrameStats {

    private final Histogram frameNanos = new Histogram(1, 10_000_000_000L, 3);
    private final Histogram stalenessNanos = new Histogram(1, 60_000_000_000L, 3);

    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();

    private long lastFrameNanos;
    private long frames;
    private long windowStartNanos;
    private long cpuAtStartNanos;

    public void reset(long nowNanos) {
        frameNanos.reset();
        stalenessNanos.reset();
        lastFrameNanos = 0;
        frames = 0;
        windowStartNanos = nowNanos;
        cpuAtStartNanos = OS.getProcessCpuTime();
    }

    /**
     * Cores consumed over the window: process CPU time divided by wall time.
     *
     * <p>The number frame time cannot show. Two implementations can both hold 60 fps while one does
     * forty times the work — it simply has enough headroom to hide it. On a trading desk running a
     * dozen blotters, that headroom is the whole product.
     */
    public double coresUsed(long nowNanos) {
        double wallSeconds = (nowNanos - windowStartNanos) / 1e9;
        if (wallSeconds <= 0) return 0;
        return (OS.getProcessCpuTime() - cpuAtStartNanos) / 1e9 / wallSeconds;
    }

    /** Call once per pulse. The first call only establishes a baseline. */
    public void recordFrame(long nowNanos) {
        if (lastFrameNanos != 0) {
            frameNanos.recordValue(Math.max(1, nowNanos - lastFrameNanos));
            frames++;
        }
        lastFrameNanos = nowNanos;
    }

    /** Age of one value currently on screen. */
    public void recordStaleness(long nanos) {
        if (nanos > 0) stalenessNanos.recordValue(Math.min(nanos, 60_000_000_000L));
    }

    public double fps(long nowNanos) {
        double seconds = (nowNanos - windowStartNanos) / 1e9;
        return seconds <= 0 ? 0 : frames / seconds;
    }

    public double frameMillis(double percentile) {
        return frameNanos.getValueAtPercentile(percentile) / 1e6;
    }

    public double stalenessMillis(double percentile) {
        return stalenessNanos.getValueAtPercentile(percentile) / 1e6;
    }

    public long frameCount() {
        return frames;
    }

    /** Frames that took longer than one 60 Hz period plus a little slack. */
    public double droppedFramePercent() {
        long total = frameNanos.getTotalCount();
        if (total == 0) return 0;
        long late = total - frameNanos.getCountBetweenValues(1, 20_000_000L);
        return 100.0 * late / total;
    }

    public String row(String label, long nowNanos) {
        return String.format(Locale.ROOT,
                "%-22s %6.1f %8.2f %8.2f %8.2f %7.1f%% %9.1f %9.1f %7.2f",
                label, fps(nowNanos),
                frameMillis(50), frameMillis(99), frameMillis(99.9),
                droppedFramePercent(),
                stalenessMillis(50), stalenessMillis(99),
                coresUsed(nowNanos));
    }

    public static String header() {
        return String.format(Locale.ROOT, "%-22s %6s %8s %8s %8s %8s %9s %9s %7s",
                "implementation", "fps", "p50 ms", "p99 ms", "p99.9", "dropped",
                "stale p50", "stale p99", "cores");
    }
}
