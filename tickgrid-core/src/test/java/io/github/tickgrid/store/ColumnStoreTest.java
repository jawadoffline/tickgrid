package io.github.tickgrid.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColumnStoreTest {

    private static Schema blotterSchema() {
        return Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.scaled("bid", 2).flashOnChange())
                .add(ColumnSpec.scaled("ask", 2).flashOnChange())
                .add(ColumnSpec.doubles("changePct"))
                .add(ColumnSpec.longs("volume"))
                .add(ColumnSpec.ints("trades"))
                .build();
    }

    private static void row(ColumnStore store, int slot, long... values) {
        store.apply(slot, values, values.length);
    }

    // ---------------------------------------------------------------- storage

    @Test
    void roundTripsEveryColumnKind() {
        ColumnStore store = new ColumnStore(100, blotterSchema());
        int sym = store.dictionary().intern("AAPL");

        row(store, 7, sym, 19_025, 19_027, Double.doubleToRawLongBits(-1.25), 4_500_000L, 812);

        assertEquals("AAPL", store.getString(7, 0));
        assertEquals(19_025, store.get(7, 1));
        assertEquals(190.25, store.getScaled(7, 1), 1e-9);
        assertEquals(190.27, store.getScaled(7, 2), 1e-9);
        assertEquals(-1.25, store.getDouble(7, 3), 1e-9);
        assertEquals(4_500_000L, store.get(7, 4));
        assertEquals(812, store.get(7, 5));
        assertTrue(store.isLive(7));
        assertEquals(1, store.rowCount());
    }

    @Test
    void untouchedSlotsReadAsZeroAndAreNotLive() {
        ColumnStore store = new ColumnStore(100, blotterSchema());
        assertEquals(0, store.get(42, 1));
        assertFalse(store.isLive(42));
        assertEquals(0, store.rowCount());
    }

    @Test
    void narrowColumnsCostHalfOfWideOnes() {
        Schema wide = Schema.builder().add(ColumnSpec.longs("a")).build();
        Schema narrow = Schema.builder().add(ColumnSpec.ints("a")).build();
        assertEquals(8, wide.bytesPerRow());
        assertEquals(4, narrow.bytesPerRow());

        // 1 for live[], plus the column itself
        assertEquals(1_000_000 * 9L, new ColumnStore(1_000_000, wide).budgetBytes());
        assertEquals(1_000_000 * 5L, new ColumnStore(1_000_000, narrow).budgetBytes());
    }

    @Test
    void flashTrackingIsOptInAndCostsFourBytes() {
        Schema plain = Schema.builder().add(ColumnSpec.scaled("bid", 2)).build();
        Schema flashing = Schema.builder().add(ColumnSpec.scaled("bid", 2).flashOnChange()).build();
        assertEquals(8, plain.bytesPerRow());
        assertEquals(12, flashing.bytesPerRow());
    }

    @Test
    void chunksAreAllocatedOnlyWhereRowsExist() {
        // 100k capacity is 25 chunks per column; touching two distant slots should touch two.
        Schema one = Schema.builder().add(ColumnSpec.longs("a")).build();
        ColumnStore store = new ColumnStore(100_000, one);
        assertEquals(0, store.allocatedChunks());

        row(store, 0, 1);
        assertEquals(1, store.allocatedChunks());
        row(store, 99_999, 1);
        assertEquals(2, store.allocatedChunks());

        assertTrue(store.allocatedBytes() < store.budgetBytes(),
                "a sparse store must not have committed its full budget");
    }

    // --------------------------------------------------------- change tracking

    @Test
    void aRowsFirstAppearanceDoesNotFlash() {
        ColumnStore store = new ColumnStore(10, blotterSchema());
        row(store, 0, 0, 19_025, 19_027, 0, 0, 0);
        assertEquals(-1, store.flashAgeMillis(0, 1), "a new row must arrive quiet");
        assertEquals(0, store.flashDirection(0, 1));
    }

    @Test
    void onlyColumnsThatMovedAreStamped() {
        ColumnStore store = new ColumnStore(10, blotterSchema());
        row(store, 0, 0, 19_025, 19_027, 0, 0, 0);
        row(store, 0, 0, 19_030, 19_027, 0, 0, 0);      // bid up, ask unchanged

        assertEquals(1, store.flashDirection(0, 1), "bid moved up");
        assertTrue(store.flashAgeMillis(0, 1) >= 0);
        assertEquals(0, store.flashDirection(0, 2), "ask did not move and must not flash");
        assertEquals(-1, store.flashAgeMillis(0, 2));
    }

    @Test
    void directionTracksDownTicks() {
        ColumnStore store = new ColumnStore(10, blotterSchema());
        row(store, 0, 0, 19_025, 0, 0, 0, 0);
        row(store, 0, 0, 19_010, 0, 0, 0, 0);
        assertEquals(-1, store.flashDirection(0, 1));
    }

    @Test
    void untrackedColumnsNeverReportAFlash() {
        ColumnStore store = new ColumnStore(10, blotterSchema());
        row(store, 0, 0, 0, 0, 0, 100, 0);
        row(store, 0, 0, 0, 0, 0, 200, 0);              // volume is not flash-tracked
        assertEquals(-1, store.flashAgeMillis(0, 4));
        assertEquals(0, store.flashDirection(0, 4));
    }

    /**
     * Raw-bit comparison is not numeric ordering for doubles — {@code -0.0} has a different bit
     * pattern from {@code 0.0} but is numerically equal, and negative values compare backwards as
     * raw longs. A double column that flashed green on a fall would be worse than not flashing.
     */
    @Test
    void doubleDirectionUsesNumericOrderNotRawBits() {
        Schema s = Schema.builder().add(ColumnSpec.doubles("chg").flashOnChange()).build();
        ColumnStore store = new ColumnStore(10, s);

        row(store, 0, Double.doubleToRawLongBits(-5.0));
        row(store, 0, Double.doubleToRawLongBits(-1.0));   // -5 -> -1 is a rise
        assertEquals(1, store.flashDirection(0, 0));

        row(store, 0, Double.doubleToRawLongBits(-9.0));   // -1 -> -9 is a fall
        assertEquals(-1, store.flashDirection(0, 0));
    }

    @Test
    void negativeZeroIsNotAChange() {
        Schema s = Schema.builder().add(ColumnSpec.doubles("chg").flashOnChange()).build();
        ColumnStore store = new ColumnStore(10, s);
        row(store, 0, Double.doubleToRawLongBits(1.0));
        row(store, 0, Double.doubleToRawLongBits(0.0));
        row(store, 0, Double.doubleToRawLongBits(-0.0));   // numerically equal to 0.0
        assertEquals(-1, store.flashDirection(0, 0), "0.0 -> -0.0 must not register as a move");
    }

    @Test
    void flashAgeGrowsWithTheFrameClock() throws Exception {
        ColumnStore store = new ColumnStore(10, blotterSchema());
        row(store, 0, 0, 100, 0, 0, 0, 0);
        row(store, 0, 0, 200, 0, 0, 0, 0);
        int first = store.flashAgeMillis(0, 1);

        Thread.sleep(30);
        store.beginFrame();
        assertTrue(store.flashAgeMillis(0, 1) >= first + 25,
                "age must advance with the frame clock, not stay pinned");
    }

    // -------------------------------------------------------------- snapshots

    @Test
    void snapshotColumnCopiesInSlotOrder() {
        ColumnStore store = new ColumnStore(100, blotterSchema());
        row(store, 5, 0, 300, 0, 0, 0, 0);
        row(store, 9, 0, 100, 0, 0, 0, 0);
        row(store, 2, 0, 200, 0, 0, 0, 0);

        int[] slots = {5, 9, 2};
        long[] dst = new long[3];
        store.snapshotColumn(1, slots, 3, dst);
        assertArrayEquals(new long[]{300, 100, 200}, dst);
    }

    /** The snapshot must be immune to later writes — that is the whole point of taking one. */
    @Test
    void snapshotIsDetachedFromLaterWrites() {
        ColumnStore store = new ColumnStore(100, blotterSchema());
        row(store, 0, 0, 111, 0, 0, 0, 0);

        long[] dst = new long[1];
        store.snapshotColumn(1, new int[]{0}, 1, dst);
        row(store, 0, 0, 999, 0, 0, 0, 0);

        assertEquals(111, dst[0], "the sort's key array must not move under it");
        assertEquals(999, store.get(0, 1));
    }

    @Test
    void snapshotRejectsAnUndersizedTarget() {
        ColumnStore store = new ColumnStore(10, blotterSchema());
        assertThrows(IllegalArgumentException.class,
                () -> store.snapshotColumn(1, new int[]{0, 1}, 2, new long[1]));
    }

    @Test
    void liveSlotsListsOnlyPopulatedRows() {
        ColumnStore store = new ColumnStore(100, blotterSchema());
        row(store, 3, 0, 1, 0, 0, 0, 0);
        row(store, 17, 0, 1, 0, 0, 0, 0);

        int[] dst = new int[100];
        assertEquals(2, store.liveSlots(dst));
        assertEquals(3, dst[0]);
        assertEquals(17, dst[1]);
    }

    // --------------------------------------------------------------- removal

    @Test
    void removalTombstonesAndBumpsTheEpoch() {
        ColumnStore store = new ColumnStore(100, blotterSchema());
        row(store, 4, 0, 1, 0, 0, 0, 0);
        int before = store.epoch();

        store.remove(4);
        assertFalse(store.isLive(4));
        assertEquals(0, store.rowCount());
        assertEquals(before + 1, store.epoch(), "a snapshot taken before this must be datable");

        store.remove(4);
        assertEquals(before + 1, store.epoch(), "removing twice must not bump the epoch twice");
    }

    @Test
    void aRemovedSlotIsNeverHandedOutAgain() {
        // Slot assignment belongs to the KeyIndex, which has no removal path at all -- that is the
        // guarantee. This pins the store half: a tombstoned slot keeps its data and stays dead
        // until something explicitly writes it again.
        ColumnStore store = new ColumnStore(100, blotterSchema());
        row(store, 4, 0, 555, 0, 0, 0, 0);
        store.remove(4);
        assertEquals(555, store.get(4, 1), "tombstoning must not scrub the row");
        assertFalse(store.isLive(4));

        int[] dst = new int[100];
        assertEquals(0, store.liveSlots(dst), "a dead slot must not appear in a view");
    }

    // ---------------------------------------------------------------- schema

    @Test
    void schemaRejectsDuplicateNames() {
        Schema.Builder b = Schema.builder().add(ColumnSpec.longs("bid"));
        assertThrows(IllegalArgumentException.class, () -> b.add(ColumnSpec.doubles("bid")));
    }

    @Test
    void schemaLooksUpColumnsByName() {
        Schema s = blotterSchema();
        assertEquals(0, s.indexOf("symbol"));
        assertEquals(2, s.indexOf("ask"));
        assertEquals(-1, s.indexOf("nope"));
    }

    /** The number the review asked for: what does capacity(1_000_000) actually commit? */
    @Test
    void budgetForAMillionRowBlotterIsReportable() {
        Schema s = blotterSchema();
        // dict 4 + scaled 8+4 + scaled 8+4 + double 8 + long 8 + int 4 = 48, plus 1 for live[]
        assertEquals(48, s.bytesPerRow());
        ColumnStore store = new ColumnStore(1_000_000, s);
        assertEquals(49_000_000L, store.budgetBytes());
    }
}
