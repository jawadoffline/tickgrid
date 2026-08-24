package io.github.tickgrid.jmh;

import io.github.tickgrid.ingress.KeyIndex;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The hand-rolled key index against the obvious alternative.
 *
 * <p>The review's argument for writing one was that {@code ConcurrentHashMap<K, Integer>} boxes on
 * every lookup. That half is simply false, and {@code -prof gc} says so: boxing happens on insert,
 * inserts are bounded by capacity, and a steady-state {@code get} returns a reference that already
 * exists. Both allocate nothing per lookup.
 *
 * <p>What justifies keeping the hand-rolled map is the rest — dense slot assignment the columnar
 * store can subscript with, a hard capacity bound, and a table that never rehashes — plus the
 * throughput measured here, which favours it at low key counts and ties at high ones.
 *
 * <p>{@code CasKeyIndex} is the fourth variant: this class as it stood before retirement existed,
 * inserting by bucket CAS with no tombstones to probe past. It is here to answer whether making
 * removal possible cost anything on the path that matters — lookups of keys that already have
 * slots, which is what every one of these benchmarks measures once setup has run.
 *
 * <p>All four variants run in one invocation of this class <b>on purpose</b>. An earlier attempt
 * compared them across separate JMH runs and reached the opposite conclusion, because run-to-run
 * variance on this machine is larger than the effect: {@code ConcurrentHashMap} scored 31.1M and
 * then 77.8M ops/sec at a thousand keys, from identical code.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class KeyIndexBenchmark {

    @Param({"1000", "100000"})
    public int keys;

    private KeyIndex<String> index;
    private CasKeyIndex<String> casInsert;
    private EntryKeyIndex<String> entryLayout;
    private ConcurrentHashMap<String, Integer> chm;
    private String[] symbols;

    @Setup(Level.Trial)
    public void setUp() {
        symbols = new String[keys];
        index = new KeyIndex<>(keys);
        casInsert = new CasKeyIndex<>(keys);
        entryLayout = new EntryKeyIndex<>(keys);
        chm = new ConcurrentHashMap<>(keys * 2);
        for (int i = 0; i < keys; i++) {
            symbols[i] = "SYM" + i;
            index.getOrCreate(symbols[i]);
            casInsert.getOrCreate(symbols[i]);
            entryLayout.getOrCreate(symbols[i]);
            chm.put(symbols[i], i);
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        long at;
    }

    @Benchmark
    public int tickgridKeyIndex(Cursor cursor) {
        return index.getOrCreate(symbols[(int) (cursor.at++ % keys)]);
    }

    /**
     * The pre-retirement version. The difference against {@code tickgridKeyIndex} is the price of
     * being able to remove a key at all: one identity comparison per probe to step over tombstones,
     * against a saved spin-loop check on the slot read.
     */
    @Benchmark
    public int casInsertKeyIndex(Cursor cursor) {
        return casInsert.getOrCreate(symbols[(int) (cursor.at++ % keys)]);
    }

    @Benchmark
    @Threads(4)
    public void casInsertKeyIndexContended(Cursor cursor, Blackhole bh) {
        bh.consume(casInsert.getOrCreate(symbols[(int) (cursor.at++ % keys)]));
    }

    /**
     * The rejected layout: one entry object per key instead of two parallel arrays. Present so the
     * comparison is within one run rather than between two, which on this machine is the difference
     * between a measurement and a coin toss.
     */
    @Benchmark
    public int entryLayoutKeyIndex(Cursor cursor) {
        return entryLayout.getOrCreate(symbols[(int) (cursor.at++ % keys)]);
    }

    @Benchmark
    @Threads(4)
    public void entryLayoutKeyIndexContended(Cursor cursor, Blackhole bh) {
        bh.consume(entryLayout.getOrCreate(symbols[(int) (cursor.at++ % keys)]));
    }

    /**
     * The boxing is the point: {@code get} returns an {@code Integer}, and only the values that
     * happen to fall inside the {@code Integer} cache avoid an allocation on insert. At a hundred
     * thousand keys almost none of them do.
     */
    @Benchmark
    public int concurrentHashMap(Cursor cursor) {
        return chm.get(symbols[(int) (cursor.at++ % keys)]);
    }

    @Benchmark
    @Threads(4)
    public void tickgridKeyIndexContended(Cursor cursor, Blackhole bh) {
        bh.consume(index.getOrCreate(symbols[(int) (cursor.at++ % keys)]));
    }

    @Benchmark
    @Threads(4)
    public void concurrentHashMapContended(Cursor cursor, Blackhole bh) {
        bh.consume(chm.get(symbols[(int) (cursor.at++ % keys)]));
    }
}
