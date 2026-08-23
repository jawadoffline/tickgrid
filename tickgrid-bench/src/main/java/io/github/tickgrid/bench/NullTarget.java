package io.github.tickgrid.bench;

import javafx.scene.Parent;
import javafx.scene.layout.Pane;

/**
 * Consumes the feed and does nothing with it.
 *
 * <p>Exists to measure the harness itself. At a million messages a second the two producer threads
 * — generating values, stamping timestamps, spinning to hold the rate — burn most of a core each,
 * and that cost lands in process CPU exactly like the grid's does. Without subtracting this
 * baseline, a CPU comparison at high rates is mostly a comparison of the feed against itself.
 */
public final class NullTarget implements BenchTarget {

    private final Pane pane = new Pane();
    private long ticks;

    @Override public Parent node() { return pane; }

    @Override
    public void onTick(int instrument, String symbol, long bid, long ask, long last,
                       double changePct, long volume, int trades, long submitNanos) {
        ticks++;
    }

    @Override public void sample(long nowNanos, FrameStats stats) { }

    @Override public String note() { return String.format("feed baseline, %,d ticks", ticks); }

    @Override public void shutdown() { }
}
