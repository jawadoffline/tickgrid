package io.github.tickgrid.demo;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.RowExtractor;
import io.github.tickgrid.store.ColumnSpec;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.store.Schema;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * What a given capacity actually commits, and whether the store's apply path stays allocation-free
 * now that it also does change detection and flash stamping.
 *
 * <p>The design's example API says {@code .capacity(1_000_000)} without saying what that costs.
 * This prints the number.
 */
public final class StoreProbe {

    /** A realistic top-of-book blotter. */
    static Schema blotter() {
        return Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.dict("venue"))
                .add(ColumnSpec.scaled("bid", 4).flashOnChange())
                .add(ColumnSpec.scaled("ask", 4).flashOnChange())
                .add(ColumnSpec.scaled("last", 4).flashOnChange())
                .add(ColumnSpec.scaled("open", 4))
                .add(ColumnSpec.scaled("high", 4))
                .add(ColumnSpec.scaled("low", 4))
                .add(ColumnSpec.doubles("changePct"))
                .add(ColumnSpec.longs("volume"))
                .add(ColumnSpec.ints("bidSize"))
                .add(ColumnSpec.ints("askSize"))
                .build();
    }

    /** The same blotter written naively: every column a long, every column flash-tracked. */
    static Schema naive() {
        Schema.Builder b = Schema.builder();
        String[] names = {"symbol", "venue", "bid", "ask", "last", "open", "high", "low",
                          "changePct", "volume", "bidSize", "askSize"};
        for (String n : names) b.add(ColumnSpec.longs(n).flashOnChange());
        return b.build();
    }

    public static void main(String[] args) {
        Schema tuned = blotter();
        Schema naive = naive();

        System.out.printf(Locale.ROOT, "%nSchema: %s%n%n", tuned);
        System.out.printf(Locale.ROOT, "%12s %14s %14s %10s%n",
                "capacity", "tuned", "all-long+flash", "saved");
        System.out.println("-".repeat(54));
        for (int cap : new int[]{1_000, 100_000, 1_000_000}) {
            long a = new ColumnStore(cap, tuned).budgetBytes();
            long b = new ColumnStore(cap, naive).budgetBytes();
            System.out.printf(Locale.ROOT, "%,12d %14s %14s %9.0f%%%n",
                    cap, mb(a), mb(b), 100.0 * (b - a) / b);
        }

        System.out.printf(Locale.ROOT, "%n%nFull footprint at 1,000,000 rows (store + ingress):%n%n");
        final int cap = 1_000_000;
        ColumnStore store = new ColumnStore(cap, tuned);
        ConflatingIngress<long[], String> in =
                new ConflatingIngress<>(cap, tuned.size(), new NullExtractor());

        long storeBytes = store.budgetBytes();
        long ingressBytes = in.footprintBytes();
        long keyIndexBytes = cap * 2L * 12L;             // 2x table of a reference + an int
        long keyStringBytes = cap * 48L;                 // a retained interned symbol, roughly

        row("column store", storeBytes);
        row("ingress staging + flags + queue", ingressBytes);
        row("key index table", keyIndexBytes);
        row("retained key strings", keyStringBytes);
        System.out.println("-".repeat(54));
        row("total", storeBytes + ingressBytes + keyIndexBytes + keyStringBytes);

        System.out.printf(Locale.ROOT, "%n%nApply path allocation:%n%n");
        applyAllocation();
        System.out.println();
    }

    static void row(String label, long bytes) {
        System.out.printf(Locale.ROOT, "%-34s %14s%n", label, mb(bytes));
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%,.1f MB", bytes / 1e6);
    }

    /** Confirms the store's write path allocates nothing once its chunks are warm. */
    static void applyAllocation() {
        final int rows = 4096;
        ColumnStore store = new ColumnStore(rows, blotter());
        long[] values = new long[store.columnCount()];

        for (int warm = 0; warm < 200_000; warm++) {
            int slot = warm & (rows - 1);
            for (int c = 0; c < values.length; c++) values[c] = warm + c;
            store.apply(slot, values, values.length);
        }

        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final int n = 2_000_000;
        long before = tmx.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int slot = i & (rows - 1);
            for (int c = 0; c < values.length; c++) values[c] = i + c;
            store.apply(slot, values, values.length);
        }
        long elapsed = System.nanoTime() - t0;
        long bytes = tmx.getCurrentThreadAllocatedBytes() - before;

        System.out.printf(Locale.ROOT, "%-34s %14.2f B%n", "per apply()", (double) bytes / n);
        System.out.printf(Locale.ROOT, "%-34s %,14.0f rows/sec%n", "apply throughput",
                n / (elapsed / 1e9));
        System.out.printf(Locale.ROOT, "%-34s %,14d%n", "columns per row", store.columnCount());
    }

    /** The probe never submits; it only needs the ingress for its footprint arithmetic. */
    static final class NullExtractor implements RowExtractor<long[], String> {
        @Override public String key(long[] row) { return "x"; }
        @Override public void extract(long[] row, long[] staging, int base) { }
    }
}
