package io.github.tickgrid.demo;

import io.github.tickgrid.store.ColumnSpec;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.store.Schema;
import io.github.tickgrid.view.PrimitiveSort;
import io.github.tickgrid.view.SortSpec;
import io.github.tickgrid.view.ViewModel;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Demonstrates why the view model copies its sort key before sorting.
 *
 * <p>The obvious implementation — box the slots and sort them with a comparator that reads the
 * store — looks fine and works in a unit test. Under a live feed it throws, because a comparator
 * that sees a value change between two comparisons is intransitive, and {@code TimSort} detects
 * that and refuses to continue. It is a crash on a background thread every few minutes, not a
 * cosmetic mis-ordering.
 *
 * <p>Like {@code HandshakeDemo}, this is a {@code main} rather than a test: the naive version fails
 * probabilistically, so asserting the failure would be flaky. The reproduction rate is the output.
 */
public final class SortContractDemo {

    static final int BID = 0;
    static final int ROWS = 20_000;
    static final int ATTEMPTS = 40;

    static final class Flag { volatile boolean value; }

    static Schema schema() {
        return Schema.builder()
                .add(ColumnSpec.scaled("bid", 2))
                .add(ColumnSpec.longs("volume"))
                .build();
    }

    public static void main(String[] args) throws Exception {
        System.out.printf(Locale.ROOT,
                "%nSorting %,d rows %d times while a writer mutates the sort column.%n%n",
                ROWS, ATTEMPTS);

        report("   boxed + live comparator ", runNaive());
        report("   snapshot + merge sort   ", runCorrect());

        System.out.printf(Locale.ROOT, "%n%nCost of the two approaches, uncontended:%n%n");
        measureCost();
        System.out.println();
    }

    record Outcome(int failures, int attempts, String firstError, double seconds) { }

    static void report(String label, Outcome o) {
        System.out.printf(Locale.ROOT, "%-28s failed %2d / %2d sorts   [%.2fs]%s%n",
                label, o.failures(), o.attempts(), o.seconds(),
                o.firstError() == null ? "" : "%n%-28s  -> %s".formatted("", o.firstError()));
    }

    /** Sorts an {@code Integer[]} with a comparator that reads the live store. */
    static Outcome runNaive() throws Exception {
        ColumnStore store = seed();
        Flag done = new Flag();
        Thread writer = startWriter(store, done);
        long t0 = System.nanoTime();

        Integer[] slots = new Integer[ROWS];
        for (int i = 0; i < ROWS; i++) slots[i] = i;
        Comparator<Integer> byBid = Comparator.comparingLong(slot -> store.get(slot, BID));

        int failures = 0;
        String firstError = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            for (int i = 0; i < ROWS; i++) slots[i] = i;
            try {
                Arrays.sort(slots, byBid);
            } catch (IllegalArgumentException e) {
                failures++;
                if (firstError == null) firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        done.value = true;
        writer.join();
        return new Outcome(failures, ATTEMPTS, firstError, (System.nanoTime() - t0) / 1e9);
    }

    /** What the view model actually does: copy the key column, then sort primitives. */
    static Outcome runCorrect() throws Exception {
        ColumnStore store = seed();
        Flag done = new Flag();
        Thread writer = startWriter(store, done);
        long t0 = System.nanoTime();

        ViewModel vm = new ViewModel(store);
        vm.sortBy(SortSpec.ascending(BID));

        int failures = 0;
        String firstError = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                vm.recomputeNow();
            } catch (RuntimeException e) {
                failures++;
                if (firstError == null) firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        done.value = true;
        writer.join();
        return new Outcome(failures, ATTEMPTS, firstError, (System.nanoTime() - t0) / 1e9);
    }

    static ColumnStore seed() {
        ColumnStore store = new ColumnStore(ROWS, schema());
        long[] row = new long[2];
        for (int i = 0; i < ROWS; i++) {
            row[BID] = (i * 7919L) % 100_000;
            store.apply(i, row, 2);
        }
        return store;
    }

    /**
     * Churns the sort column so that rows genuinely change places relative to each other.
     *
     * <p>An earlier version of this shifted every value by the same amount each pass, which left
     * the relative order almost intact and never tripped the detector. A comparator only becomes
     * intransitive when the values move <em>past</em> one another, so each slot gets an unrelated
     * value every pass.
     */
    static Thread startWriter(ColumnStore store, Flag done) {
        Thread writer = new Thread(() -> {
            long[] row = new long[2];
            long rng = 0x9E3779B97F4A7C15L;
            while (!done.value) {
                for (int i = 0; i < ROWS && !done.value; i++) {
                    rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
                    row[BID] = Math.floorMod(rng, 100_000L);
                    row[1] = i;
                    store.apply(i, row, 2);
                }
            }
        }, "writer");
        writer.start();
        return writer;
    }

    /** With no writer running, both produce a correct order — so compare what they cost. */
    static void measureCost() {
        ColumnStore store = seed();
        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        Integer[] boxed = new Integer[ROWS];
        Comparator<Integer> byBid = Comparator.comparingLong(slot -> store.get(slot, BID));
        for (int warm = 0; warm < 20; warm++) {
            for (int i = 0; i < ROWS; i++) boxed[i] = i;
            Arrays.sort(boxed, byBid);
        }
        long before = tmx.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();
        for (int rep = 0; rep < 50; rep++) {
            for (int i = 0; i < ROWS; i++) boxed[i] = i;
            Arrays.sort(boxed, byBid);
        }
        long naiveNanos = (System.nanoTime() - t0) / 50;
        long naiveBytes = (tmx.getCurrentThreadAllocatedBytes() - before) / 50;

        ViewModel vm = new ViewModel(store);
        vm.sortBy(SortSpec.ascending(BID));
        for (int warm = 0; warm < 20; warm++) vm.recomputeNow();
        before = tmx.getCurrentThreadAllocatedBytes();
        t0 = System.nanoTime();
        for (int rep = 0; rep < 50; rep++) vm.recomputeNow();
        long goodNanos = (System.nanoTime() - t0) / 50;
        long goodBytes = (tmx.getCurrentThreadAllocatedBytes() - before) / 50;

        System.out.printf(Locale.ROOT, "%-28s %8.2f ms   %,12d B per sort%n",
                "   boxed + live comparator", naiveNanos / 1e6, naiveBytes);
        System.out.printf(Locale.ROOT, "%-28s %8.2f ms   %,12d B per sort%n",
                "   snapshot + merge sort", goodNanos / 1e6, goodBytes);
        System.out.printf(Locale.ROOT,
                "%n   The correct version also allocates the published snapshot arrays, which the%n"
              + "   naive one does not - so this is not a like-for-like allocation comparison.%n"
              + "   Boxing %,d slots alone is %,d B.%n",
                ROWS, ROWS * 16);
        // Keep the sort utility referenced so the demo documents where it lives.
        assert PrimitiveSort.sortableDoubleBits(0L) == 0L;
    }
}
