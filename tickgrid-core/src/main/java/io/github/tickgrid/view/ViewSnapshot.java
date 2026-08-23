package io.github.tickgrid.view;

import io.github.tickgrid.store.ColumnStore;

/**
 * An immutable render order: which store slot sits at which row position, for one frame.
 *
 * <p>The renderer reads exactly one of these per frame and holds it for the whole frame. Because
 * nothing in it can change, there is no lock, no tearing, and no possibility of the row count
 * shifting between the scrollbar calculation and the last painted row. A recompute produces a new
 * snapshot and swaps the reference; a frame already in flight finishes against the old one.
 *
 * <h2>Why a slot reference stays valid</h2>
 * A snapshot holds slot indices, not rows, so it is only safe while a slot means the same row it
 * meant when the snapshot was built. Two properties guarantee that today: {@code KeyIndex} never
 * releases a slot, and {@link ColumnStore#remove} tombstones rather than recycles. If slot reuse is
 * ever added, this stops being free — which is what {@link #storeEpoch()} is for. Compare it
 * against {@link ColumnStore#epoch()} to know whether any row has been removed since the snapshot
 * was taken, and refuse to reuse a slot until no live snapshot predates its removal.
 *
 * <p>Removal between recomputes is handled without any of that: a tombstoned slot is still listed
 * here, so the renderer checks {@link ColumnStore#isLive} — one boolean array read per visible row
 * — and skips rows that have gone.
 */
public final class ViewSnapshot {

    /** An empty view. Safe to render before the first recompute. */
    public static final ViewSnapshot EMPTY =
            new ViewSnapshot(new int[0], new int[0], 0, 0, 0, SortSpec.none());

    private final int[] order;
    private final int[] positionBySlot;
    private final int count;
    private final int storeEpoch;
    private final long generation;
    private final SortSpec sort;

    ViewSnapshot(int[] order, int[] positionBySlot, int count,
                 int storeEpoch, long generation, SortSpec sort) {
        this.order = order;
        this.positionBySlot = positionBySlot;
        this.count = count;
        this.storeEpoch = storeEpoch;
        this.generation = generation;
        this.sort = sort;
    }

    /** Number of rows in the view — after filtering. */
    public int count() {
        return count;
    }

    /** The store slot to draw at this row position. */
    public int slotAt(int position) {
        if (position < 0 || position >= count) {
            throw new IndexOutOfBoundsException("position " + position + " of " + count);
        }
        return order[position];
    }

    /**
     * Where this slot sits in the view, or {@code -1} if it was filtered out or does not exist.
     *
     * <p>O(1). This is what keeps selection and scroll position stable across a reorder: hold the
     * <em>slot</em>, not the row position, and ask where it went afterwards.
     */
    public int positionOf(int slot) {
        if (slot < 0 || slot >= positionBySlot.length) return -1;
        return positionBySlot[slot];
    }

    public boolean contains(int slot) {
        return positionOf(slot) >= 0;
    }

    /** The store's removal epoch when this snapshot was built. */
    public int storeEpoch() {
        return storeEpoch;
    }

    /** Monotonic recompute counter. Lets a renderer notice the order changed under it. */
    public long generation() {
        return generation;
    }

    public SortSpec sort() {
        return sort;
    }

    /**
     * Whether {@code candidate[0..n)} is the order this snapshot already holds.
     *
     * <p>Lets {@link ViewModel} skip republishing when a recompute reproduces the previous order,
     * which is the common case for a throttled sort: values move without reordering anything.
     */
    boolean matchesOrder(int[] candidate, int n) {
        if (n != count) return false;
        for (int i = 0; i < n; i++) {
            if (order[i] != candidate[i]) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ViewSnapshot[gen=" + generation + ", " + count + " rows, " + sort + "]";
    }
}
