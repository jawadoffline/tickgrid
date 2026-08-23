package io.github.tickgrid.view;

import java.util.concurrent.TimeUnit;

/**
 * When the view is allowed to reorder itself.
 *
 * <p>This is a usability control, not a performance one. Re-sorting on every tick of a ticking
 * column makes rows leap out from under the cursor mid-click, which is unusable regardless of how
 * fast the sort is. The default throttles to 4 Hz: fast enough that the ordering looks live, slow
 * enough that a row stays under the pointer long enough to be hit.
 */
public final class SortPolicy {

    private enum Kind { CONTINUOUS, THROTTLED, MANUAL }

    private final Kind kind;
    private final long intervalNanos;

    private SortPolicy(Kind kind, long intervalNanos) {
        this.kind = kind;
        this.intervalNanos = intervalNanos;
    }

    /** Reorder on every recompute opportunity. Only sane for small, slow-moving grids. */
    public static SortPolicy continuous() {
        return new SortPolicy(Kind.CONTINUOUS, 0);
    }

    /** Reorder at most this often. The default is 250 ms. */
    public static SortPolicy throttled(long interval, TimeUnit unit) {
        long nanos = unit.toNanos(interval);
        if (nanos <= 0) throw new IllegalArgumentException("interval must be positive");
        return new SortPolicy(Kind.THROTTLED, nanos);
    }

    /** The default: 4 Hz. */
    public static SortPolicy throttled() {
        return throttled(250, TimeUnit.MILLISECONDS);
    }

    /** Reorder only when the sort or filter actually changes — a header click, not a tick. */
    public static SortPolicy manual() {
        return new SortPolicy(Kind.MANUAL, 0);
    }

    /**
     * @param dirty whether the sort or filter changed since the last recompute, which always earns
     *              a recompute — a header click must respond now, whatever the policy
     */
    boolean isDue(long nowNanos, long lastRecomputeNanos, boolean dirty) {
        if (dirty) return true;
        return switch (kind) {
            case CONTINUOUS -> true;
            case THROTTLED -> nowNanos - lastRecomputeNanos >= intervalNanos;
            case MANUAL -> false;
        };
    }

    @Override
    public String toString() {
        return kind == Kind.THROTTLED
                ? "throttled(" + intervalNanos / 1_000_000 + "ms)"
                : kind.name().toLowerCase();
    }
}
