package io.github.tickgrid.bench;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Frame-time and staleness harness, run against one implementation at a ladder of feed rates.
 *
 * <p>The rate ladder matters more than any single number. A p99.9 quoted at a rate where the
 * baseline has already collapsed is a comparison against something broken, and reads as one. What
 * a reader can actually use is the rate at which each implementation stops holding 60 fps — so the
 * harness walks the rates and reports every step, letting the failure point speak for itself.
 *
 * <pre>
 *   -Dbench.impl=tickgrid | tableview-naive | tableview-batched
 *   -Dbench.rows=5000  -Dbench.threads=2
 *   -Dbench.rates=10000,50000,100000,250000,500000
 *   -Dbench.warmupSeconds=3  -Dbench.seconds=6
 * </pre>
 */
public final class BenchApp extends Application {

    private String impl = System.getProperty("bench.impl", "tickgrid");
    private int rows = Integer.getInteger("bench.rows", 5_000);
    private int threads = Integer.getInteger("bench.threads", 2);
    private double warmupSeconds = Double.parseDouble(System.getProperty("bench.warmupSeconds", "3"));
    private double measureSeconds = Double.parseDouble(System.getProperty("bench.seconds", "6"));
    private long[] rates;

    private BenchTarget target;
    private SyntheticFeed feed;
    private final FrameStats stats = new FrameStats();
    private final List<String> results = new ArrayList<>();

    private int rateIndex = -1;
    private long phaseStartNanos;
    private boolean warming;
    private boolean done;
    private AnimationTimer timer;

    @Override
    public void init() {
        String spec = System.getProperty("bench.rates", "10000,50000,100000,250000,500000");
        String[] parts = spec.split(",");
        rates = new long[parts.length];
        for (int i = 0; i < parts.length; i++) rates[i] = Long.parseLong(parts[i].trim());
    }

    @Override
    public void start(Stage stage) {
        target = switch (impl) {
            case "tableview-naive" -> new TableViewTarget(rows, TableViewTarget.Mode.NAIVE);
            case "tableview-batched" -> new TableViewTarget(rows, TableViewTarget.Mode.BATCHED);
            case "tickgrid" -> new TickGridTarget(rows);
            case "nullsink" -> new NullTarget();
            default -> throw new IllegalArgumentException("unknown bench.impl: " + impl);
        };

        Scene scene = new Scene(target.node(), 1180, 720, Color.web("#12181c"));
        stage.setScene(scene);
        stage.setTitle("TickGrid bench — " + impl);
        stage.show();

        System.out.printf(Locale.ROOT, "%n%s — %,d rows, %d feed threads, "
                        + "%.0fs warmup + %.0fs measured per rate%n%n",
                impl, rows, threads, warmupSeconds, measureSeconds);
        System.out.println("  rate  " + FrameStats.header() + "   floor");
        System.out.println("  " + "-".repeat(122));

        nextRate();

        // Platform.exit() does not stop the pulse immediately, so without this guard the final
        // rate gets measured and reported twice.
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (!done) tick(now);
            }
        };
        timer.start();
    }

    private void tick(long now) {
        stats.recordFrame(now);
        target.sample(now, stats);

        final double elapsed = (now - phaseStartNanos) / 1e9;
        final double limit = warming ? warmupSeconds : measureSeconds;

        if (target.hasCollapsed() && !warming) {
            finishPhase(now, true);
            return;
        }
        if (elapsed < limit) return;

        if (warming) {
            warming = false;
            phaseStartNanos = now;
            stats.reset(now);
        } else {
            finishPhase(now, false);
        }
    }

    private void finishPhase(long now, boolean collapsed) {
        final long rate = rates[rateIndex];
        results.add(String.format(Locale.ROOT, "%,7d  %s %8s   %s",
                rate,
                collapsed
                        ? String.format(Locale.ROOT, "%-22s %s", "COLLAPSED", "unbounded queue")
                        : stats.row(impl, now),
                rate == 0 ? "-" : String.format(Locale.ROOT, "%.1f", stalenessFloorMillis(rate)),
                target.note()));
        System.out.println("  " + results.get(results.size() - 1));

        if (feed != null) feed.stop();
        if (collapsed || rateIndex + 1 >= rates.length) {
            finish();
        } else {
            nextRate();
        }
    }

    private void nextRate() {
        rateIndex++;
        final long rate = rates[rateIndex];
        // Rate 0 runs no feed at all, which establishes the machine's own frame jitter. Without it
        // there is no way to tell a grid dropping frames from a desktop compositor doing so.
        feed = rate == 0 ? null : new SyntheticFeed(rows, threads, rate, target);
        if (feed != null) feed.start();
        warming = true;
        phaseStartNanos = System.nanoTime();
        stats.reset(phaseStartNanos);
    }

    /**
     * The lowest staleness any implementation could achieve at this rate: an instrument that ticks
     * every T milliseconds shows a value averaging T/2 old however fast the grid is. Reporting it
     * beside the measurement is what separates "the pipeline is slow" from "the instrument is
     * quiet", and at low rates the second dominates completely.
     */
    private double stalenessFloorMillis(long rate) {
        double ticksPerInstrumentPerSecond = (double) rate / rows;
        return ticksPerInstrumentPerSecond <= 0 ? 0 : 1000.0 / ticksPerInstrumentPerSecond / 2.0;
    }

    private void finish() {
        done = true;
        if (timer != null) timer.stop();
        System.out.println();
        target.shutdown();
        Platform.exit();
    }

    @Override
    public void stop() {
        if (feed != null) feed.stop();
        if (target != null) target.shutdown();
    }
}
