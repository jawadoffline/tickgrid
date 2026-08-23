package io.github.tickgrid.bench;

import javafx.scene.Parent;

/** One grid implementation under test. */
public interface BenchTarget extends SyntheticFeed.Sink {

    /** The node to put on the scene. */
    Parent node();

    /** Called once per pulse, after the implementation's own frame work. Record staleness here. */
    void sample(long nowNanos, FrameStats stats);

    /** A short note appended to the results row — queue depth, drops, whatever is diagnostic. */
    default String note() {
        return "";
    }

    /**
     * Whether the implementation has collapsed past the point where numbers mean anything — an
     * unbounded queue that will exhaust the heap if the run continues. Reporting the collapse is
     * more useful than an OutOfMemoryError, and more honest than quietly dropping work.
     */
    default boolean hasCollapsed() {
        return false;
    }

    void shutdown();
}
