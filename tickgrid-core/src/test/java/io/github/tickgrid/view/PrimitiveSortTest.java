package io.github.tickgrid.view;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveSortTest {

    private static void sort(long[] keys, int[] slots, boolean desc) {
        PrimitiveSort.sort(keys, slots, keys.length,
                new long[keys.length], new int[slots.length], desc);
    }

    @Test
    void sortsKeysAndPermutesSlotsTogether() {
        long[] keys = {30, 10, 20};
        int[] slots = {100, 101, 102};
        sort(keys, slots, false);
        assertArrayEquals(new long[]{10, 20, 30}, keys);
        assertArrayEquals(new int[]{101, 102, 100}, slots);
    }

    @Test
    void sortsDescending() {
        long[] keys = {30, 10, 20};
        int[] slots = {100, 101, 102};
        sort(keys, slots, true);
        assertArrayEquals(new long[]{30, 20, 10}, keys);
        assertArrayEquals(new int[]{100, 102, 101}, slots);
    }

    @Test
    void handlesTrivialSizes() {
        long[] empty = {};
        int[] emptySlots = {};
        sort(empty, emptySlots, false);            // must not throw

        long[] one = {5};
        int[] oneSlot = {7};
        sort(one, oneSlot, false);
        assertArrayEquals(new int[]{7}, oneSlot);
    }

    @Test
    void isStableOnTies() {
        long[] keys = {1, 1, 1, 1, 1, 1};
        int[] slots = {5, 3, 9, 1, 8, 2};
        sort(keys, slots, false);
        assertArrayEquals(new int[]{5, 3, 9, 1, 8, 2}, slots, "ties must not be reordered");
    }

    @Test
    void isStableOnTiesWhenDescending() {
        long[] keys = {2, 1, 2, 1};
        int[] slots = {10, 11, 12, 13};
        sort(keys, slots, true);
        assertArrayEquals(new long[]{2, 2, 1, 1}, keys);
        assertArrayEquals(new int[]{10, 12, 11, 13}, slots);
    }

    @Test
    void handlesExtremeValues() {
        long[] keys = {Long.MAX_VALUE, Long.MIN_VALUE, 0, -1, 1};
        int[] slots = {0, 1, 2, 3, 4};
        sort(keys, slots, false);
        assertArrayEquals(new long[]{Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}, keys,
                "descending negation would overflow on MIN_VALUE; the flag must be used instead");
    }

    @Test
    void matchesJdkSortOnRandomInput() {
        Random rnd = new Random(20260823);
        for (int trial = 0; trial < 60; trial++) {
            int n = rnd.nextInt(400) + 1;
            long[] keys = new long[n];
            int[] slots = new int[n];
            for (int i = 0; i < n; i++) {
                keys[i] = rnd.nextInt(50) - 25;    // a small range, so plenty of ties
                slots[i] = i;
            }
            long[] expected = keys.clone();
            Arrays.sort(expected);

            sort(keys, slots, false);
            assertArrayEquals(expected, keys, "trial " + trial);

            // Stability: within a run of equal keys, the original slots must stay ascending,
            // because slots started in ascending order.
            for (int i = 1; i < n; i++) {
                if (keys[i] == keys[i - 1]) {
                    assertTrue(slots[i] > slots[i - 1], "unstable at " + i + " in trial " + trial);
                }
            }
        }
    }

    @Test
    void sortsAtScaleWithoutScratchGrowth() {
        int n = 200_000;
        Random rnd = new Random(7);
        long[] keys = new long[n];
        int[] slots = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = rnd.nextLong();
            slots[i] = i;
        }
        long[] expected = keys.clone();
        Arrays.sort(expected);

        long[] ks = new long[n];
        int[] ss = new int[n];
        PrimitiveSort.sort(keys, slots, n, ks, ss, false);
        assertArrayEquals(expected, keys);
    }

    @Test
    void rejectsUndersizedScratch() {
        assertThrows(IllegalArgumentException.class, () ->
                PrimitiveSort.sort(new long[4], new int[4], 4, new long[2], new int[4], false));
    }

    // ------------------------------------------------------- double ordering

    @Test
    void sortableDoubleBitsOrderMatchesNumericOrder() {
        double[] values = {
                Double.NEGATIVE_INFINITY, -1e300, -1.5, -0.0, 0.0, Double.MIN_VALUE,
                1.5, 1e300, Double.POSITIVE_INFINITY
        };
        long previous = Long.MIN_VALUE;
        for (double d : values) {
            long mapped = PrimitiveSort.sortableDoubleBits(Double.doubleToRawLongBits(d));
            assertTrue(mapped >= previous, "ordering broke at " + d);
            previous = mapped;
        }
    }

    /**
     * Raw bits happen to order a negative below a positive correctly — the sign bit does that on
     * its own. Where they break is <em>among</em> negatives: a larger magnitude means larger bits
     * but a smaller number, so the whole negative range runs backwards. On a change-percent column
     * that puts the worst faller at the top of an ascending sort.
     */
    @Test
    void sortableDoubleBitsFixesTheReversedNegativeRange() {
        long rawMinusOne = Double.doubleToRawLongBits(-1.0);
        long rawMinusTwo = Double.doubleToRawLongBits(-2.0);
        assertTrue(rawMinusTwo > rawMinusOne,
                "raw bits really do order -2.0 above -1.0, which is why the mapping is needed");

        long mappedMinusOne = PrimitiveSort.sortableDoubleBits(rawMinusOne);
        long mappedMinusTwo = PrimitiveSort.sortableDoubleBits(rawMinusTwo);
        assertTrue(mappedMinusTwo < mappedMinusOne, "-2.0 must sort below -1.0");

        long mappedPlusOne = PrimitiveSort.sortableDoubleBits(Double.doubleToRawLongBits(1.0));
        assertTrue(mappedMinusOne < mappedPlusOne, "negatives must stay below positives");
    }

    @Test
    void sortableDoubleBitsAgreesWithJdkOnRandomDoubles() {
        Random rnd = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            double a = rnd.nextBoolean() ? rnd.nextGaussian() * 1e6 : rnd.nextGaussian();
            double b = rnd.nextBoolean() ? rnd.nextGaussian() * 1e6 : rnd.nextGaussian();
            long ma = PrimitiveSort.sortableDoubleBits(Double.doubleToRawLongBits(a));
            long mb = PrimitiveSort.sortableDoubleBits(Double.doubleToRawLongBits(b));
            assertEquals(Math.signum(Double.compare(a, b)), Math.signum(Long.compare(ma, mb)),
                    "disagreed on " + a + " vs " + b);
        }
    }
}
