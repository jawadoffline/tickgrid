package io.github.tickgrid.ingress;

/**
 * Pulls the key and the column values out of a producer's row object.
 *
 * <p>Every column is a {@code long}: scaled decimals stay integral, doubles go through
 * {@link Double#doubleToRawLongBits}, and repeated strings become dictionary indices. Keeping one
 * primitive width across the staging arena is what lets the ingress copy a row without knowing
 * anything about its columns.
 *
 * <p>{@link #extract} runs inside the per-slot seqlock write window, so it must do nothing but
 * write the values — no I/O, no locks, no allocation.
 */
public interface RowExtractor<T, K> {

    /** The business key this row belongs to. */
    K key(T row);

    /**
     * Writes this row's column values into {@code staging[base .. base + columnCount)}.
     *
     * @param base absolute offset of column 0 for this row's slot
     */
    void extract(T row, long[] staging, int base);
}
