package io.github.tickgrid.jmh;

import io.github.tickgrid.view.PrimitiveSort;
import org.openjdk.jmh.annotations.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The view model's sort against the boxed comparator it replaced.
 *
 * <p>{@code SortContractDemo} already shows the boxed version <i>throwing</i> under concurrent
 * writes, which is the decisive argument. This measures what it costs when it does not throw, so
 * the choice does not rest on the crash alone — and it separates the two things that differ:
 * boxing a million slots, and running a stable merge rather than a dual-pivot quicksort.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class SortBenchmark {

    @Param({"1000", "50000"})
    public int rows;

    /** Ties are the interesting case: a blotter is full of them, and they are where stability costs. */
    @Param({"full", "few"})
    public String keySpread;

    private long[] keys;
    private int[] slots;
    private long[] keyScratch;
    private int[] slotScratch;
    private Integer[] boxed;
    private long[] keyMaster;
    private int[] slotMaster;

    @Setup(Level.Trial)
    public void setUp() {
        Random rnd = new Random(12345);
        keyMaster = new long[rows];
        slotMaster = new int[rows];
        final int distinct = "few".equals(keySpread) ? 20 : Integer.MAX_VALUE;
        for (int i = 0; i < rows; i++) {
            keyMaster[i] = distinct == Integer.MAX_VALUE ? rnd.nextLong() : rnd.nextInt(distinct);
            slotMaster[i] = i;
        }
        keys = new long[rows];
        slots = new int[rows];
        keyScratch = new long[rows];
        slotScratch = new int[rows];
        boxed = new Integer[rows];
    }

    @Setup(Level.Invocation)
    public void reset() {
        System.arraycopy(keyMaster, 0, keys, 0, rows);
        System.arraycopy(slotMaster, 0, slots, 0, rows);
    }

    @Benchmark
    public int[] primitiveMergeSort() {
        PrimitiveSort.sort(keys, slots, rows, keyScratch, slotScratch, false);
        return slots;
    }

    /** Boxes every slot, then sorts with a comparator that reads the key array. */
    @Benchmark
    public Integer[] boxedComparatorSort() {
        for (int i = 0; i < rows; i++) boxed[i] = slotMaster[i];
        final long[] k = keyMaster;
        Arrays.sort(boxed, Comparator.comparingLong(i -> k[i]));
        return boxed;
    }

    /** The floor: sorting the keys alone, with no slot permutation to carry. */
    @Benchmark
    public long[] arraysSortBaseline() {
        Arrays.sort(keys);
        return keys;
    }
}
