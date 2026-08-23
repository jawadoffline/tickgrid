package io.github.tickgrid.store;

/**
 * How a column is stored, and therefore what it costs per row.
 *
 * <p>Every column arrives from the ingress as a {@code long} — that uniformity is what lets the
 * staging arena copy a row without knowing its schema. The kind decides how those bits are
 * interpreted and, more importantly, how wide the backing array is. Narrow columns are half the
 * memory of wide ones, which at a million rows is the difference between a 4 MB column and an 8 MB
 * one; across a dozen columns that is the difference between a comfortable heap and an awkward one.
 */
public enum ColumnKind {

    /** Plain 64-bit integer. Counts, quantities, timestamps. */
    LONG(8),

    /**
     * Fixed-point decimal held as an integer scaled by {@code 10^scale}. The right choice for
     * prices: exact, comparable with integer arithmetic, and formatted at paint time.
     */
    SCALED(8),

    /** IEEE-754 double, carried through the ingress as raw bits. */
    DOUBLE(8),

    /** 32-bit integer. Half the memory of {@link #LONG} when the range allows it. */
    INT(4),

    /**
     * An ordinal into a {@link StringDictionary}. Repeated strings — symbol, venue, status, side —
     * are interned once and stored as a 4-byte id.
     */
    DICT(4);

    private final int bytes;

    ColumnKind(int bytes) {
        this.bytes = bytes;
    }

    /** Bytes per row for this column kind. */
    public int bytes() {
        return bytes;
    }

    /** Whether this kind needs 64-bit backing storage. */
    public boolean isWide() {
        return bytes == 8;
    }
}
