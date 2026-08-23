package io.github.tickgrid.view;

import io.github.tickgrid.store.ColumnKind;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.store.StringDictionary;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns the store into a render order: filter, sort, publish.
 *
 * <p>Recompute runs off the render thread and produces a fresh {@link ViewSnapshot}, which is
 * published by swapping a single volatile reference. The renderer never waits and never sees a
 * half-built order.
 *
 * <h2>Sorting against a moving store</h2>
 * The key column is copied into a scratch array before the sort, via
 * {@link ColumnStore#snapshotColumn}. This is not an optimisation. Comparing against the live store
 * lets a comparator see a value change between two comparisons, which violates transitivity — and
 * a sort that detects that throws rather than merely returning a wrong order. One sequential copy
 * makes the contract hold by construction, and the sort itself is a stable primitive merge sort
 * with no boxing (see {@link PrimitiveSort}).
 *
 * <h2>Freezing</h2>
 * {@link #setFrozen} suspends reordering during an active pointer gesture, so a row cannot move out
 * from under a click. Note that it is deliberately <b>not</b> tied to selection: on a blotter
 * something is nearly always selected, so freezing on selection would mean never sorting again.
 * Selection survives a reorder by being held as a slot and re-located through
 * {@link ViewSnapshot#positionOf} — see {@link #positionOfSlot}.
 *
 * <h2>Threading</h2>
 * {@link #snapshot()} is safe from any thread and is what the renderer calls once per frame.
 * {@link #sortBy}, {@link #setFilter}, {@link #setSortPolicy} and {@link #setFrozen} are safe from
 * any thread. {@link #maybeRecompute} and {@link #recomputeNow} are single-threaded: call them from
 * one recompute thread only (see {@link BackgroundRecomputer}).
 */
public final class ViewModel {

    private final ColumnStore store;

    private volatile ViewSnapshot current = ViewSnapshot.EMPTY;
    private volatile SortSpec sort = SortSpec.none();
    private volatile RowFilter filter = RowFilter.all();
    private volatile SortPolicy policy = SortPolicy.throttled();
    private volatile boolean frozen;
    private final AtomicBoolean dirty = new AtomicBoolean(true);

    // Recompute-thread state. Never touched by readers.
    private final int[] slots;
    private final int[] slotScratch;
    private final long[] keys;
    private final long[] keyScratch;
    private long lastRecomputeNanos = Long.MIN_VALUE / 4;
    private long generation;
    private int[] dictRank = new int[0];
    private int dictRankFor = -1;

    public ViewModel(ColumnStore store) {
        this.store = store;
        final int cap = store.capacity();
        this.slots = new int[cap];
        this.slotScratch = new int[cap];
        this.keys = new long[cap];
        this.keyScratch = new long[cap];
    }

    // ------------------------------------------------------------------ reads

    /** The current render order. Call once per frame and hold it for the whole frame. */
    public ViewSnapshot snapshot() {
        return current;
    }

    /**
     * Where a slot currently sits, or {@code -1}. Convenience over
     * {@code snapshot().positionOf(slot)} for anchoring selection and scroll across a reorder.
     */
    public int positionOfSlot(int slot) {
        return current.positionOf(slot);
    }

    // ----------------------------------------------------------------- writes

    /** Sets the sort and schedules an immediate recompute, whatever the policy. */
    public void sortBy(SortSpec spec) {
        this.sort = spec;
        dirty.set(true);
    }

    /** Cycles this column through ascending, descending, unsorted. For a header click. */
    public SortSpec toggleSort(int column) {
        SortSpec next = sort.toggled(column);
        sortBy(next);
        return next;
    }

    public void setFilter(RowFilter filter) {
        this.filter = filter == null ? RowFilter.all() : filter;
        dirty.set(true);
    }

    public void setSortPolicy(SortPolicy policy) {
        this.policy = policy;
    }

    /**
     * Suspends reordering. Hold this for the duration of a pointer gesture, plus a short dwell
     * afterwards, so a row cannot move between press and release.
     */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public boolean isFrozen()      { return frozen; }
    public SortSpec sort()         { return sort; }
    public SortPolicy sortPolicy() { return policy; }

    // -------------------------------------------------------------- recompute

    /**
     * Recomputes if the policy says it is due and the view is not frozen. Call from the recompute
     * thread; cheap when nothing is due.
     *
     * @return whether a new snapshot was published
     */
    public boolean maybeRecompute(long nowNanos) {
        if (frozen) return false;
        final boolean isDirty = dirty.get();
        if (!policy.isDue(nowNanos, lastRecomputeNanos, isDirty)) return false;
        // Clear before the work, not after: a sort change arriving mid-recompute must survive into
        // the next one rather than being erased by this one finishing. Same shape of bug as the
        // ingress dirty flag.
        dirty.set(false);
        recompute(nowNanos);
        return true;
    }

    /** Recomputes unconditionally, ignoring policy and freeze. For tests and explicit refreshes. */
    public void recomputeNow() {
        dirty.set(false);
        recompute(System.nanoTime());
    }

    private void recompute(long nowNanos) {
        final SortSpec spec = sort;                 // read once: the recompute must be self-consistent
        final RowFilter rowFilter = filter;

        int n = store.liveSlots(slots);
        if (rowFilter != RowFilter.All.INSTANCE) {
            int kept = 0;
            for (int i = 0; i < n; i++) {
                final int slot = slots[i];
                if (rowFilter.test(store, slot)) slots[kept++] = slot;
            }
            n = kept;
        }

        if (spec.isSorted() && n > 1) {
            loadKeys(spec.column(), n);
            PrimitiveSort.sort(keys, slots, n, keyScratch, slotScratch, spec.descending());
        }

        lastRecomputeNanos = nowNanos;

        // A throttled recompute fires on a timer, so most of them reproduce the order they replaced
        // — the values moved but not enough to reorder anything. Publishing an identical order
        // anyway bumps the generation, which the renderer reads as "the view changed" and answers
        // with a full repaint. Comparing first costs one O(n) integer scan against a sort that has
        // already run, and it is what lets a grid watching a closed market drop to zero frames.
        final ViewSnapshot published = current;
        if (published.count() == n && spec.equals(published.sort())
                && published.storeEpoch() == store.epoch() && published.matchesOrder(slots, n)) {
            return;
        }

        final int[] order = Arrays.copyOf(slots, n);
        final int[] positionBySlot = new int[store.capacity()];
        Arrays.fill(positionBySlot, -1);
        for (int i = 0; i < n; i++) {
            positionBySlot[order[i]] = i;
        }

        current = new ViewSnapshot(order, positionBySlot, n, store.epoch(), ++generation, spec);
    }

    /** Copies the sort column into {@link #keys}, mapped so that natural long order is correct. */
    private void loadKeys(int column, int n) {
        store.snapshotColumn(column, slots, n, keys);
        final ColumnKind kind = store.schema().get(column).kind();
        switch (kind) {
            case DOUBLE -> {
                for (int i = 0; i < n; i++) {
                    keys[i] = PrimitiveSort.sortableDoubleBits(keys[i]);
                }
            }
            case DICT -> {
                final int[] rank = dictRanks();
                for (int i = 0; i < n; i++) {
                    final int ordinal = (int) keys[i];
                    keys[i] = ordinal >= 0 && ordinal < rank.length ? rank[ordinal] : Integer.MAX_VALUE;
                }
            }
            default -> {
                // LONG, SCALED and INT already sort correctly as signed longs.
            }
        }
    }

    /**
     * Lexicographic rank per dictionary ordinal.
     *
     * <p>Ordinals are assigned in first-seen order, so sorting a dictionary column by its stored
     * value would order rows by when their venue first appeared — which looks like a bug to anyone
     * who clicks the header. Ranking fixes that. It is rebuilt only when the dictionary has grown,
     * which for the columns dictionary encoding suits — venue, status, side — is a handful of times
     * ever. A dictionary that grows continuously (symbols, say) makes this a poor sort key, and the
     * column should be sorted some other way.
     */
    private int[] dictRanks() {
        final StringDictionary dictionary = store.dictionary();
        final int size = dictionary.size();
        if (dictRankFor == size) return dictRank;

        final Integer[] ordinals = new Integer[size];
        for (int i = 0; i < size; i++) ordinals[i] = i;
        Arrays.sort(ordinals, Comparator.comparing(
                o -> {
                    String s = dictionary.get(o);
                    return s == null ? "" : s;
                }));

        final int[] rank = new int[size];
        for (int r = 0; r < size; r++) rank[ordinals[r]] = r;

        dictRank = rank;
        dictRankFor = size;
        return rank;
    }

    /** Recomputes performed. */
    public long generation() {
        return generation;
    }
}
