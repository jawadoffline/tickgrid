package io.github.tickgrid.view;

/**
 * A stable merge sort over parallel {@code long[]} keys and {@code int[]} slots.
 *
 * <h2>Why not {@code Arrays.sort}</h2>
 * Sorting an {@code int[]} of slots by an external key needs a comparator, and a comparator needs
 * {@code Integer[]} — boxing a million slots on every re-sort. The primitive overload cannot help
 * because the key lives in another array. So the sort permutes both arrays together.
 *
 * <h2>Why merge sort rather than quicksort</h2>
 * Stability is a visible property here, not a theoretical one. A blotter has ties everywhere — a
 * hundred symbols with zero volume, a whole column of identical statuses — and an unstable sort
 * reshuffles those rows on every recompute. At 4 Hz that is a grid that will not sit still under
 * the cursor. Merge sort keeps equal-key rows in their previous relative order, so ties stay put.
 * It also gives a guaranteed O(n log n) rather than a quadratic worst case on adversarial input.
 *
 * <p>The cost is a scratch buffer the size of the input, which the caller owns and reuses.
 */
public final class PrimitiveSort {

    private PrimitiveSort() {
    }

    /**
     * Sorts {@code slots[0..n)} by {@code keys[0..n)}, permuting both. Stable.
     *
     * @param keyScratch  scratch of at least {@code n}, owned by the caller
     * @param slotScratch scratch of at least {@code n}, owned by the caller
     */
    public static void sort(long[] keys, int[] slots, int n,
                            long[] keyScratch, int[] slotScratch, boolean descending) {
        if (n < 2) return;
        if (keyScratch.length < n || slotScratch.length < n) {
            throw new IllegalArgumentException("scratch too small for n=" + n);
        }

        long[] srcK = keys, dstK = keyScratch;
        int[] srcS = slots, dstS = slotScratch;
        boolean inScratch = false;

        for (int width = 1; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += width << 1) {
                final int mid = Math.min(lo + width, n);
                final int hi = Math.min(lo + (width << 1), n);
                merge(srcK, srcS, dstK, dstS, lo, mid, hi, descending);
            }
            long[] tk = srcK; srcK = dstK; dstK = tk;
            int[] ts = srcS; srcS = dstS; dstS = ts;
            inScratch = !inScratch;
        }

        if (inScratch) {
            System.arraycopy(srcK, 0, keys, 0, n);
            System.arraycopy(srcS, 0, slots, 0, n);
        }
    }

    private static void merge(long[] srcK, int[] srcS, long[] dstK, int[] dstS,
                              int lo, int mid, int hi, boolean descending) {
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) {
            // Taking the left run on ties is what makes this stable.
            final boolean takeLeft = descending ? srcK[i] >= srcK[j] : srcK[i] <= srcK[j];
            if (takeLeft) {
                dstK[k] = srcK[i]; dstS[k] = srcS[i]; i++;
            } else {
                dstK[k] = srcK[j]; dstS[k] = srcS[j]; j++;
            }
            k++;
        }
        while (i < mid) { dstK[k] = srcK[i]; dstS[k] = srcS[i]; i++; k++; }
        while (j < hi)  { dstK[k] = srcK[j]; dstS[k] = srcS[j]; j++; k++; }
    }

    /**
     * Maps raw IEEE-754 bits to a signed long whose natural order matches numeric order.
     *
     * <p>Raw double bits do not sort correctly as longs: negatives run backwards, because a larger
     * magnitude means larger bits while meaning a smaller number. Flipping every bit but the sign
     * for negative values reverses that run and leaves them below the positives, where they belong.
     * NaN sorts to the top, which is where an unknown value should sit in a blotter.
     */
    public static long sortableDoubleBits(long rawBits) {
        return rawBits ^ ((rawBits >> 63) & 0x7FFF_FFFF_FFFF_FFFFL);
    }
}
