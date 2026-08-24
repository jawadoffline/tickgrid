package io.github.tickgrid.view;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.RowExtractor;
import io.github.tickgrid.store.ColumnSpec;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.store.Schema;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ViewModelTest {

    static final int SYMBOL = 0, BID = 1, CHANGE = 2, VOLUME = 3;

    private static Schema schema() {
        return Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.scaled("bid", 2))
                .add(ColumnSpec.doubles("changePct"))
                .add(ColumnSpec.longs("volume"))
                .build();
    }

    private static ColumnStore store(int capacity) {
        return new ColumnStore(capacity, schema());
    }

    /** An id and a round marker — enough to tell which instrument a slot is currently holding. */
    private static Schema twoColumns() {
        return Schema.builder()
                .add(ColumnSpec.longs("id"))
                .add(ColumnSpec.longs("round"))
                .build();
    }

    private static void put(ColumnStore s, int slot, String symbol, long bid, double chg, long vol) {
        s.apply(slot, new long[]{
                s.dictionary().intern(symbol), bid, Double.doubleToRawLongBits(chg), vol
        }, 4);
    }

    private static long[] bidsInViewOrder(ColumnStore s, ViewSnapshot v) {
        long[] out = new long[v.count()];
        for (int i = 0; i < v.count(); i++) out[i] = s.get(v.slotAt(i), BID);
        return out;
    }

    // -------------------------------------------------------------- ordering

    @Test
    void unsortedViewIsSlotOrder() {
        ColumnStore s = store(10);
        put(s, 2, "C", 300, 0, 0);
        put(s, 0, "A", 100, 0, 0);
        put(s, 1, "B", 200, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.recomputeNow();

        ViewSnapshot v = vm.snapshot();
        assertEquals(3, v.count());
        assertArrayEquals(new long[]{100, 200, 300}, bidsInViewOrder(s, v));
    }

    @Test
    void sortsAscendingAndDescending() {
        ColumnStore s = store(10);
        put(s, 0, "A", 300, 0, 0);
        put(s, 1, "B", 100, 0, 0);
        put(s, 2, "C", 200, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(BID));
        vm.recomputeNow();
        assertArrayEquals(new long[]{100, 200, 300}, bidsInViewOrder(s, vm.snapshot()));

        vm.sortBy(SortSpec.descending(BID));
        vm.recomputeNow();
        assertArrayEquals(new long[]{300, 200, 100}, bidsInViewOrder(s, vm.snapshot()));
    }

    /**
     * Doubles cannot be sorted as raw bits: negatives run backwards and would land above positives.
     * A change-percent column is exactly where a user would notice.
     */
    @Test
    void doubleColumnSortsNumericallyNotByRawBits() {
        ColumnStore s = store(10);
        double[] values = {3.5, -1.0, 0.0, -99.5, 12.25, -0.5};
        for (int i = 0; i < values.length; i++) put(s, i, "S" + i, 0, values[i], 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(CHANGE));
        vm.recomputeNow();

        ViewSnapshot v = vm.snapshot();
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < v.count(); i++) {
            double d = s.getDouble(v.slotAt(i), CHANGE);
            assertTrue(d >= previous, "out of order at " + i + ": " + d + " after " + previous);
            previous = d;
        }
        assertEquals(-99.5, s.getDouble(v.slotAt(0), CHANGE), 1e-9);
        assertEquals(12.25, s.getDouble(v.slotAt(5), CHANGE), 1e-9);
    }

    /**
     * Dictionary ordinals are assigned in first-seen order, so sorting on the stored value would
     * order rows by when a symbol first appeared. Anyone clicking the header expects alphabetical.
     */
    @Test
    void dictionaryColumnSortsAlphabeticallyNotByOrdinal() {
        ColumnStore s = store(10);
        put(s, 0, "ZZZZ", 0, 0, 0);          // interned first, so ordinal 0
        put(s, 1, "AAAA", 0, 0, 0);
        put(s, 2, "MMMM", 0, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(SYMBOL));
        vm.recomputeNow();

        ViewSnapshot v = vm.snapshot();
        assertEquals("AAAA", s.getString(v.slotAt(0), SYMBOL));
        assertEquals("MMMM", s.getString(v.slotAt(1), SYMBOL));
        assertEquals("ZZZZ", s.getString(v.slotAt(2), SYMBOL));
    }

    /**
     * Ties are everywhere on a blotter — a whole column of zero volumes. An unstable sort reshuffles
     * them on every recompute, and at 4 Hz that is a grid that will not sit still under the cursor.
     */
    @Test
    void equalKeysKeepTheirRelativeOrderAcrossRecomputes() {
        ColumnStore s = store(200);
        for (int i = 0; i < 200; i++) put(s, i, "S" + i, 100, 0, 0);   // every bid identical

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(BID));
        vm.recomputeNow();
        int[] first = new int[200];
        for (int i = 0; i < 200; i++) first[i] = vm.snapshot().slotAt(i);

        for (int round = 0; round < 5; round++) {
            vm.recomputeNow();
            for (int i = 0; i < 200; i++) {
                assertEquals(first[i], vm.snapshot().slotAt(i),
                        "tied rows moved between recomputes at position " + i);
            }
        }
    }

    // --------------------------------------------------------------- filters

    @Test
    void filterRemovesRowsAndRenumbersPositions() {
        ColumnStore s = store(10);
        for (int i = 0; i < 6; i++) put(s, i, "S" + i, i * 100, 0, i);

        ViewModel vm = new ViewModel(s);
        vm.setFilter((store, slot) -> store.get(slot, VOLUME) % 2 == 0);
        vm.recomputeNow();

        ViewSnapshot v = vm.snapshot();
        assertEquals(3, v.count());
        assertArrayEquals(new long[]{0, 200, 400}, bidsInViewOrder(s, v));
        assertEquals(0, v.positionOf(0));
        assertEquals(-1, v.positionOf(1), "a filtered-out row must report no position");
    }

    @Test
    void filterAndSortCompose() {
        ColumnStore s = store(10);
        for (int i = 0; i < 6; i++) put(s, i, "S" + i, i * 100, 0, i);

        ViewModel vm = new ViewModel(s);
        vm.setFilter((store, slot) -> store.get(slot, VOLUME) >= 3);
        vm.sortBy(SortSpec.descending(BID));
        vm.recomputeNow();

        assertArrayEquals(new long[]{500, 400, 300}, bidsInViewOrder(s, vm.snapshot()));
    }

    // ------------------------------------------------------------- snapshots

    @Test
    void aPublishedSnapshotDoesNotChangeUnderTheRenderer() {
        ColumnStore s = store(10);
        put(s, 0, "A", 100, 0, 0);
        put(s, 1, "B", 200, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.recomputeNow();
        ViewSnapshot held = vm.snapshot();
        assertEquals(2, held.count());

        put(s, 2, "C", 300, 0, 0);
        vm.recomputeNow();

        assertEquals(2, held.count(), "the frame in flight must not see a new row appear");
        assertEquals(3, vm.snapshot().count());
        assertTrue(vm.snapshot().generation() > held.generation());
    }

    @Test
    void positionOfSurvivesAReorder() {
        ColumnStore s = store(10);
        put(s, 0, "A", 300, 0, 0);
        put(s, 1, "B", 100, 0, 0);
        put(s, 2, "C", 200, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(BID));
        vm.recomputeNow();
        assertEquals(2, vm.positionOfSlot(0));       // bid 300 is last

        vm.sortBy(SortSpec.descending(BID));
        vm.recomputeNow();
        assertEquals(0, vm.positionOfSlot(0),
                "selection held as a slot must be re-locatable after a reorder");
    }

    @Test
    void snapshotRecordsTheStoreEpoch() {
        ColumnStore s = store(10);
        put(s, 0, "A", 100, 0, 0);
        ViewModel vm = new ViewModel(s);
        vm.recomputeNow();
        int before = vm.snapshot().storeEpoch();

        s.remove(0);
        vm.recomputeNow();
        assertTrue(vm.snapshot().storeEpoch() > before,
                "a snapshot must be datable against removals for slot reuse to ever be safe");
    }

    @Test
    void removedRowsLeaveTheViewOnTheNextRecompute() {
        ColumnStore s = store(10);
        put(s, 0, "A", 100, 0, 0);
        put(s, 1, "B", 200, 0, 0);
        ViewModel vm = new ViewModel(s);
        vm.recomputeNow();
        assertEquals(2, vm.snapshot().count());

        s.remove(0);
        vm.recomputeNow();
        assertEquals(1, vm.snapshot().count());
        assertEquals(-1, vm.snapshot().positionOf(0));
    }

    @Test
    void emptySnapshotIsRenderableBeforeTheFirstRecompute() {
        ViewModel vm = new ViewModel(store(10));
        assertEquals(0, vm.snapshot().count());
        assertEquals(-1, vm.snapshot().positionOf(0));
        assertThrows(IndexOutOfBoundsException.class, () -> vm.snapshot().slotAt(0));
    }

    // ---------------------------------------------------------------- policy

    @Test
    void throttledPolicyRateLimitsButNeverDelaysAHeaderClick() {
        ColumnStore s = store(10);
        put(s, 0, "A", 100, 0, 0);
        ViewModel vm = new ViewModel(s);
        vm.setSortPolicy(SortPolicy.throttled(250, TimeUnit.MILLISECONDS));

        long t = 1_000_000_000L;
        assertTrue(vm.maybeRecompute(t), "the first recompute is always due");
        assertFalse(vm.maybeRecompute(t + 1_000_000L), "1ms later is inside the throttle");

        vm.sortBy(SortSpec.ascending(BID));
        assertTrue(vm.maybeRecompute(t + 2_000_000L), "a sort change must not wait for the throttle");

        assertFalse(vm.maybeRecompute(t + 3_000_000L));
        assertTrue(vm.maybeRecompute(t + 260_000_000L), "the interval has passed");
    }

    @Test
    void manualPolicyOnlyReactsToExplicitChanges() {
        ColumnStore s = store(10);
        put(s, 0, "A", 100, 0, 0);
        ViewModel vm = new ViewModel(s);
        vm.setSortPolicy(SortPolicy.manual());

        long t = 1_000_000_000L;
        assertTrue(vm.maybeRecompute(t), "the initial dirty flag still counts");
        assertFalse(vm.maybeRecompute(t + 10_000_000_000L), "ten seconds later, still nothing to do");

        vm.toggleSort(BID);
        assertTrue(vm.maybeRecompute(t + 10_000_000_001L));
    }

    /**
     * A recompute that reproduces the previous order must not republish: the renderer treats a new
     * generation as "the view changed" and answers with a full repaint, so republishing an
     * identical order on a timer keeps an idle grid painting forever.
     */
    @Test
    void anUnchangedOrderIsNotRepublished() {
        ColumnStore s = store(10);
        for (int i = 0; i < 4; i++) put(s, i, "S" + i, i * 100, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(BID));
        vm.recomputeNow();
        ViewSnapshot first = vm.snapshot();

        vm.recomputeNow();
        vm.recomputeNow();
        assertSame(first, vm.snapshot(), "nothing moved, so the snapshot must be the same object");
        assertEquals(first.generation(), vm.snapshot().generation());
    }

    @Test
    void aChangedOrderIsStillPublished() {
        ColumnStore s = store(10);
        for (int i = 0; i < 4; i++) put(s, i, "S" + i, i * 100, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(BID));
        vm.recomputeNow();
        ViewSnapshot first = vm.snapshot();

        put(s, 0, "S0", 9999, 0, 0);           // S0 was cheapest, now dearest
        vm.recomputeNow();
        assertNotSame(first, vm.snapshot());
        assertEquals(3, vm.snapshot().positionOf(0), "S0 should have moved to the end");
    }

    @Test
    void aNewRowIsPublishedEvenWhenTheExistingOrderIsUnchanged() {
        ColumnStore s = store(10);
        put(s, 0, "A", 100, 0, 0);
        ViewModel vm = new ViewModel(s);
        vm.recomputeNow();
        ViewSnapshot first = vm.snapshot();

        put(s, 1, "B", 200, 0, 0);
        vm.recomputeNow();
        assertNotSame(first, vm.snapshot());
        assertEquals(2, vm.snapshot().count());
    }

    @Test
    void continuousPolicyAlwaysRecomputes() {
        ColumnStore s = store(10);
        put(s, 0, "A", 100, 0, 0);
        ViewModel vm = new ViewModel(s);
        vm.setSortPolicy(SortPolicy.continuous());

        // The policy is due every time; whether that produces a new snapshot is a separate
        // question, answered by anUnchangedOrderIsNotRepublished().
        assertTrue(vm.maybeRecompute(1));
        assertTrue(vm.maybeRecompute(2));
        assertTrue(vm.maybeRecompute(3));
    }

    @Test
    void freezingSuspendsReorderingWithoutLosingIt() {
        ColumnStore s = store(10);
        put(s, 0, "A", 300, 0, 0);
        put(s, 1, "B", 100, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.setSortPolicy(SortPolicy.continuous());
        vm.recomputeNow();
        long generation = vm.snapshot().generation();

        vm.setFrozen(true);
        vm.sortBy(SortSpec.ascending(BID));
        assertFalse(vm.maybeRecompute(System.nanoTime()), "a frozen view must not reorder");
        assertEquals(generation, vm.snapshot().generation());

        vm.setFrozen(false);
        assertTrue(vm.maybeRecompute(System.nanoTime()), "the pending sort must survive the freeze");
        assertArrayEquals(new long[]{100, 300}, bidsInViewOrder(s, vm.snapshot()));
    }

    /** Same shape as the ingress dirty-flag bug: clearing after the work erases what arrived during it. */
    @Test
    void aSortChangeArrivingDuringRecomputeIsNotLost() {
        ColumnStore s = store(10);
        for (int i = 0; i < 4; i++) put(s, i, "S" + i, (4 - i) * 100, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.setSortPolicy(SortPolicy.manual());
        vm.setFilter((store, slot) -> {
            // Runs inside recompute; stands in for a header click landing mid-sort.
            vm.sortBy(SortSpec.ascending(BID));
            return true;
        });
        vm.maybeRecompute(1);
        vm.setFilter(RowFilter.all());

        assertTrue(vm.maybeRecompute(2), "the change made during the recompute must still be pending");
    }

    // ----------------------------------------------------------- concurrency

    /**
     * The failure this design avoids: sorting against the live store lets a comparator observe a
     * value changing between comparisons, violating transitivity. {@code Arrays.sort} with a
     * comparator detects that and throws {@code IllegalArgumentException} — a crash on a background
     * thread every few minutes under load. Copying the key column first makes it impossible.
     */
    @Test
    void sortingIsUnaffectedByConcurrentWritesToTheSortColumn() throws Exception {
        final int rows = 5_000;
        ColumnStore s = store(rows);
        for (int i = 0; i < rows; i++) put(s, i, "S" + i, i, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(BID));

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong recomputes = new AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        Thread writer = new Thread(() -> {
            long[] row = new long[4];
            long seq = 0;
            while (running.get()) {
                for (int i = 0; i < rows; i++) {
                    row[BID] = (seq + i * 7919L) % 100_000;
                    row[VOLUME] = seq;
                    s.apply(i, row, 4);
                }
                seq++;
            }
        }, "writer");

        Thread sorter = new Thread(() -> {
            try {
                while (running.get()) {
                    vm.recomputeNow();
                    recomputes.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "sorter");

        writer.start();
        sorter.start();
        Thread.sleep(1_500);
        running.set(false);
        writer.join();
        sorter.join();

        assertNull(failure.get(), "recompute threw under concurrent writes");
        assertTrue(recomputes.get() > 5, "expected several recomputes, got " + recomputes.get());

        // Every published order must be internally consistent against its own key snapshot: the
        // order is correct for the values as they were read, even though they have since moved on.
        ViewSnapshot v = vm.snapshot();
        assertEquals(rows, v.count());
        for (int i = 0; i < v.count(); i++) {
            assertTrue(v.positionOf(v.slotAt(i)) == i, "position index disagrees with order");
        }
    }

    @Test
    void backgroundRecomputerPublishesWithoutTheCallerDrivingIt() throws Exception {
        ColumnStore s = store(100);
        for (int i = 0; i < 50; i++) put(s, i, "S" + i, 50 - i, 0, 0);

        ViewModel vm = new ViewModel(s);
        vm.sortBy(SortSpec.ascending(BID));
        vm.setSortPolicy(SortPolicy.throttled(20, TimeUnit.MILLISECONDS));

        try (BackgroundRecomputer r = new BackgroundRecomputer(vm, 2).start()) {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (vm.snapshot().count() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(50, vm.snapshot().count(), "the recomputer never published");

            // Give it something to publish: an unchanged order is deliberately not republished,
            // so a static grid proves nothing about whether the thread is still running.
            // Slot 49 holds the lowest bid, so it currently sorts first. Raising it above every
            // other row is a change that genuinely reorders -- raising slot 0, which was already
            // last, would not, and would leave this test asserting nothing.
            long first = vm.snapshot().generation();
            put(s, 49, "S49", 99_999, 0, 0);

            deadline = System.nanoTime() + 2_000_000_000L;
            while (vm.snapshot().generation() == first && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(vm.snapshot().generation() > first, "the recomputer stopped after one pass");
            assertEquals(49, vm.snapshot().positionOf(49), "S49 is now the dearest and should be last");
        }
    }

    // ------------------------------------------------------- slot reclamation

    /**
     * The invariant the whole reclamation scheme rests on, checked at the level that matters: a
     * snapshot dated at or after a removal never lists the removed slot, so once such a snapshot is
     * published the slot is provably off screen and can be reissued.
     */
    @Test
    void aSnapshotDatedAfterARemovalNeverListsTheRemovedSlot() {
        ColumnStore store = new ColumnStore(16, twoColumns());
        ViewModel vm = new ViewModel(store);
        vm.setSortPolicy(SortPolicy.manual());

        for (int slot = 0; slot < 4; slot++) store.apply(slot, new long[]{slot, 100 + slot}, 2);
        vm.recomputeNow();

        ViewSnapshot before = vm.snapshot();
        assertTrue(before.contains(2));
        final int epochBefore = before.storeEpoch();

        store.remove(2);
        vm.recomputeNow();

        ViewSnapshot after = vm.snapshot();
        assertTrue(after.storeEpoch() > epochBefore, "a removal must move the epoch on");
        assertFalse(after.contains(2), "the new snapshot must not list the removed slot");
        assertTrue(before.contains(2), "and the old one must be unchanged, still listing it");
    }

    /**
     * The frame loop, without a frame loop: submit, drain, recompute, reclaim, repeat, with the
     * universe turning over completely each round. What is being checked is that no snapshot ever
     * lists a slot that was reissued while that snapshot was the published one — the failure this
     * would show up as on screen is one instrument's prices drawn on another's line.
     */
    @Test
    void reclaimedSlotsAreNeverReissuedUnderAPublishedSnapshot() {
        final int capacity = 8;
        ColumnStore store = new ColumnStore(capacity, twoColumns());
        ViewModel vm = new ViewModel(store);
        vm.setSortPolicy(SortPolicy.manual());

        ConflatingIngress<long[], String> in = new ConflatingIngress<>(
                capacity, 2, new RowExtractor<long[], String>() {
                    @Override public String key(long[] row) {
                        return "K" + row[0];
                    }
                    @Override public void extract(long[] row, long[] staging, int base) {
                        staging[base] = row[0];
                        staging[base + 1] = row[1];
                    }
                });

        long id = 0;
        for (int round = 0; round < 50; round++) {
            final long[] issued = new long[capacity];
            for (int i = 0; i < capacity; i++) {
                issued[i] = id++;
                assertTrue(in.submit(new long[]{issued[i], round}), "rejected in round " + round);
            }
            in.drainAll(store.applier());
            vm.recomputeNow();

            // Whatever the published snapshot lists must be live and must be the instrument it was
            // when the snapshot was built.
            ViewSnapshot view = vm.snapshot();
            assertEquals(capacity, view.count());
            for (int pos = 0; pos < view.count(); pos++) {
                final int slot = view.slotAt(pos);
                assertTrue(store.isLive(slot));
                assertEquals(round, store.get(slot, 1), "slot " + slot + " holds a stale round");
            }

            for (int i = 0; i < capacity; i++) in.retire("K" + issued[i]);
            in.drainAll(store.applier());
            vm.recomputeNow();
            in.reclaim(vm.snapshot().storeEpoch());
        }

        assertEquals(0, in.rejectedCount());
        assertEquals(400, in.reclaimedCount());
    }
}
