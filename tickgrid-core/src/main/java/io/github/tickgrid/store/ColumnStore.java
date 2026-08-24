package io.github.tickgrid.store;

import io.github.tickgrid.ingress.RowApplier;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Columnar primitive storage: one chunked array per column, sized to the column's declared kind.
 *
 * <p>Chunking in blocks of 4096 keeps a million-row column off the humongous-object path and lets a
 * sparsely populated store allocate only the blocks it touches. Narrow columns ({@code INT},
 * {@code DICT}) use {@code int[]} and cost half what the wide ones do — see {@link #budgetBytes()},
 * which turns a capacity into a number you can put in a README.
 *
 * <h2>Change tracking</h2>
 * Columns declared with {@link ColumnSpec#flashOnChange()} carry a 4-byte stamp per row recording
 * when they last changed and in which direction. The stamp is written by {@link #apply}, which is
 * the only place that sees the old and new value together, and read at paint time by
 * {@link #flashAgeMillis}. No {@code Timeline}, no animation object, no per-cell listener — the
 * renderer derives alpha from an integer subtraction and the grid goes idle when the market does.
 *
 * <h2>Threading</h2>
 * Written only by the drain thread. Read by the drain thread's own renderer and, for sort and
 * filter, by a background thread. Chunk directories are {@link AtomicReferenceArray}s so a lazily
 * allocated block is safely published to that reader.
 *
 * <p>Chunk <i>contents</i> are racy by design. That is fine for rendering — a cell is at worst one
 * frame stale — and <b>not</b> fine for sorting, where a comparator that sees a value change
 * mid-sort violates transitivity and TimSort throws. Background sorts must go through
 * {@link #snapshotColumn}, which copies the key column into a caller-owned array first.
 *
 * <h2>Removal</h2>
 * {@link #remove} tombstones a slot and wipes its columns. The store itself never decides to reuse
 * a slot: doing that too early would let a published view snapshot point at a slot that now belongs
 * to a different instrument, and the renderer would paint one symbol's prices on another symbol's
 * line.
 *
 * <p>{@link #epoch()} bumps on every removal, and that is what makes reuse decidable. A view
 * snapshot records the epoch it was built at, so a slot removed at epoch {@code E} is provably
 * absent from every snapshot dated {@code E} or later. {@code ConflatingIngress} holds retired
 * slots until that test passes and only then hands them back out — see
 * {@code ConflatingIngress#retire} and {@code ConflatingIngress#reclaim}.
 */
public final class ColumnStore {

    public static final int CHUNK_SHIFT = 12;
    public static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;      // 4096
    static final int CHUNK_MASK = CHUNK_SIZE - 1;

    /** Rolling millisecond clock for flash stamps: 30 bits, wraps every ~12.4 days. */
    static final int CLOCK_MASK = 0x3FFF_FFFF;
    private static final int DIR_DOWN = 1;
    private static final int DIR_UP = 2;

    private final Schema schema;
    private final int capacity;
    private final Column[] columns;
    private final StringDictionary dictionary;
    private final boolean[] live;

    private final long startNanos = System.nanoTime();
    private int nowMillis;                                      // sampled by beginFrame()
    private volatile int epoch;
    private int rowCount;

    public ColumnStore(int capacity, Schema schema) {
        this(capacity, schema, 1 << 16);
    }

    public ColumnStore(int capacity, Schema schema, int dictionaryCapacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.schema = schema;
        this.live = new boolean[capacity];
        this.dictionary = new StringDictionary(dictionaryCapacity);

        final int chunks = (capacity + CHUNK_SIZE - 1) >>> CHUNK_SHIFT;
        this.columns = new Column[schema.size()];
        for (int c = 0; c < schema.size(); c++) {
            ColumnSpec spec = schema.get(c);
            columns[c] = spec.kind().isWide()
                    ? new WideColumn(spec, chunks)
                    : new NarrowColumn(spec, chunks);
        }
        beginFrame();
    }

    /** Convenience: {@code n} plain long columns. Used by benchmarks and tests. */
    public ColumnStore(int capacity, int columnCount) {
        this(capacity, Schema.ofLongs(columnCount));
    }

    // ------------------------------------------------------------------ write

    /** A {@link RowApplier} that writes straight into this store. Bind it to the drain. */
    public RowApplier applier() {
        return applier;
    }

    private final RowApplier applier = new RowApplier() {
        @Override public void apply(int slot, long[] values, int count) {
            ColumnStore.this.apply(slot, values, count);
        }
        @Override public int remove(int slot) {
            ColumnStore.this.remove(slot);
            return epoch;
        }
    };

    /**
     * Writes one row. Drain thread only.
     *
     * <p>Unchanged cells are not rewritten, and flash stamps are only laid down for columns that
     * actually moved — so a row that ticks in one column does not light up the whole line. A row's
     * first appearance never flashes: every column would qualify, and a screen that flashes
     * everything on connect communicates nothing.
     */
    public void apply(int slot, long[] values, int count) {
        final boolean isNew = !live[slot];
        if (isNew) {
            live[slot] = true;
            rowCount++;
        }
        final int stampBase = (nowMillis & CLOCK_MASK) << 2;
        final int n = Math.min(count, columns.length);
        for (int c = 0; c < n; c++) {
            final int dir = columns[c].set(slot, values[c]);
            if (dir != 0 && !isNew) {
                columns[c].stamp(slot, stampBase | dir);
            }
        }
    }

    /**
     * Tombstones a slot and wipes it, so whatever moves in next starts from zero rather than
     * inheriting the previous tenant's prices and flash stamps.
     *
     * <p>Wiping here rather than on reuse is safe because the renderer skips dead rows on
     * {@link #isLive} without reading their values, and it keeps the cost off the path that hands
     * a slot to a new key. See the class note on removal for when the slot may be reused.
     */
    public void remove(int slot) {
        if (live[slot]) {
            live[slot] = false;
            rowCount--;
            epoch++;
            for (Column c : columns) c.clear(slot);
        }
    }

    /**
     * Samples the flash clock. Call once per frame from the drain, before draining — reading the
     * system clock per cell would cost more than the stamps it writes.
     */
    public void beginFrame() {
        nowMillis = (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    // ------------------------------------------------------------------- read

    public long get(int slot, int column) {
        return columns[column].get(slot);
    }

    public double getDouble(int slot, int column) {
        return Double.longBitsToDouble(columns[column].get(slot));
    }

    /** A {@code SCALED} column's value as a double. For display and comparison, not arithmetic. */
    public double getScaled(int slot, int column) {
        return columns[column].get(slot) / Math.pow(10, schema.get(column).scale());
    }

    /** A {@code DICT} column's value, resolved through the dictionary. */
    public String getString(int slot, int column) {
        return dictionary.get((int) columns[column].get(slot));
    }

    /**
     * Milliseconds since this cell last changed, or {@code -1} if it never has or the column does
     * not track changes. Correct across the rolling clock's wrap.
     */
    public int flashAgeMillis(int slot, int column) {
        final int stamp = columns[column].stampOf(slot);
        if (stamp == 0) return -1;
        return ((nowMillis & CLOCK_MASK) - (stamp >>> 2)) & CLOCK_MASK;
    }

    /** {@code +1} if this cell last moved up, {@code -1} down, {@code 0} if never or untracked. */
    public int flashDirection(int slot, int column) {
        final int stamp = columns[column].stampOf(slot);
        if (stamp == 0) return 0;
        return (stamp & 3) == DIR_UP ? 1 : -1;
    }

    public boolean isLive(int slot) {
        return live[slot];
    }

    /** Fills {@code dst} with the live slots in slot order and returns how many there were. */
    public int liveSlots(int[] dst) {
        int n = 0;
        for (int s = 0; s < capacity && n < dst.length; s++) {
            if (live[s]) dst[n++] = s;
        }
        return n;
    }

    /**
     * Copies one column's values for {@code slots[0..count)} into {@code dst}.
     *
     * <p>This is how a background sort must read its key column. Sorting against the live store
     * lets a comparator observe a value changing between two comparisons, which violates
     * transitivity — and TimSort detects that and throws {@code IllegalArgumentException} rather
     * than merely producing a wrong order. One sequential copy makes the contract hold by
     * construction.
     */
    public void snapshotColumn(int column, int[] slots, int count, long[] dst) {
        if (dst.length < count) {
            throw new IllegalArgumentException("dst too small: " + dst.length + " < " + count);
        }
        final Column col = columns[column];
        for (int i = 0; i < count; i++) {
            dst[i] = col.get(slots[i]);
        }
    }

    // -------------------------------------------------------------- accounting

    public Schema schema()               { return schema; }
    public StringDictionary dictionary() { return dictionary; }
    public int rowCount()                { return rowCount; }
    public int capacity()                { return capacity; }
    public int columnCount()             { return columns.length; }
    /** Bumped on every removal, so view snapshots can be dated. */
    public int epoch()                   { return epoch; }

    /** Chunks actually allocated across all columns, flash stamps included. */
    public int allocatedChunks() {
        int n = 0;
        for (Column c : columns) n += c.allocatedChunks();
        return n;
    }

    /** Bytes actually committed so far — grows as chunks are touched. */
    public long allocatedBytes() {
        long n = capacity;                              // the live[] flags
        for (Column c : columns) n += c.allocatedBytes();
        return n;
    }

    /** Bytes this store will occupy at full capacity. The number to put in a README. */
    public long budgetBytes() {
        return capacity * schema.bytesPerRow() + capacity;
    }

    @Override
    public String toString() {
        return String.format("ColumnStore[%,d rows / %,d capacity, %s, %,.1f MB of %,.1f MB]",
                rowCount, capacity, schema, allocatedBytes() / 1e6, budgetBytes() / 1e6);
    }

    // ---------------------------------------------------------------- columns

    /**
     * One column's chunked backing store, plus its optional flash stamps.
     *
     * <p>Two implementations, so the call in {@link #apply} stays bimorphic and inlines. Resist
     * adding a third: a megamorphic call site on the apply path would cost more than the memory a
     * specialised width saves.
     */
    private abstract static class Column {
        final ColumnSpec spec;
        final AtomicReferenceArray<int[]> stamps;      // null when the column is not flash-tracked

        Column(ColumnSpec spec, int chunks) {
            this.spec = spec;
            this.stamps = spec.flashTracked() ? new AtomicReferenceArray<>(chunks) : null;
        }

        /** @return 0 if unchanged, {@link #DIR_DOWN} or {@link #DIR_UP} if it moved */
        abstract int set(int slot, long value);

        abstract long get(int slot);

        abstract int allocatedChunks();

        abstract long allocatedBytes();

        /** Zeroes the value and drops the flash stamp. Untouched chunks stay unallocated. */
        abstract void clear(int slot);

        final void clearStamp(int slot) {
            if (stamps == null) return;
            final int[] chunk = stamps.get(slot >>> CHUNK_SHIFT);
            if (chunk != null) chunk[slot & CHUNK_MASK] = 0;
        }

        final void stamp(int slot, int packed) {
            if (stamps == null) return;
            stampChunk(slot >>> CHUNK_SHIFT)[slot & CHUNK_MASK] = packed;
        }

        final int stampOf(int slot) {
            if (stamps == null) return 0;
            int[] chunk = stamps.get(slot >>> CHUNK_SHIFT);
            return chunk == null ? 0 : chunk[slot & CHUNK_MASK];
        }

        final int[] stampChunk(int chunk) {
            int[] block = stamps.get(chunk);
            if (block == null) {
                block = new int[CHUNK_SIZE];
                if (!stamps.compareAndSet(chunk, null, block)) block = stamps.get(chunk);
            }
            return block;
        }

        final int stampChunks() {
            if (stamps == null) return 0;
            int n = 0;
            for (int i = 0; i < stamps.length(); i++) if (stamps.get(i) != null) n++;
            return n;
        }
    }

    /** 64-bit backing for {@code LONG}, {@code SCALED} and {@code DOUBLE}. */
    private static final class WideColumn extends Column {
        private final AtomicReferenceArray<long[]> chunks;
        private final boolean isDouble;

        WideColumn(ColumnSpec spec, int chunks) {
            super(spec, chunks);
            this.chunks = new AtomicReferenceArray<>(chunks);
            this.isDouble = spec.kind() == ColumnKind.DOUBLE;
        }

        @Override int set(int slot, long value) {
            final long[] block = chunkFor(slot >>> CHUNK_SHIFT);
            final int i = slot & CHUNK_MASK;
            final long old = block[i];
            if (old == value) return 0;
            block[i] = value;
            if (isDouble) {
                // Raw-bit inequality is not numeric ordering: -0.0, NaN and sign both misbehave.
                int cmp = Double.compare(Double.longBitsToDouble(value), Double.longBitsToDouble(old));
                return cmp == 0 ? 0 : cmp > 0 ? DIR_UP : DIR_DOWN;
            }
            return value > old ? DIR_UP : DIR_DOWN;
        }

        @Override void clear(int slot) {
            final long[] block = chunks.get(slot >>> CHUNK_SHIFT);
            if (block != null) block[slot & CHUNK_MASK] = 0L;
            clearStamp(slot);
        }

        @Override long get(int slot) {
            final long[] block = chunks.get(slot >>> CHUNK_SHIFT);
            return block == null ? 0L : block[slot & CHUNK_MASK];
        }

        @Override int allocatedChunks() {
            int n = stampChunks();
            for (int i = 0; i < chunks.length(); i++) if (chunks.get(i) != null) n++;
            return n;
        }

        @Override long allocatedBytes() {
            long n = 0;
            for (int i = 0; i < chunks.length(); i++) if (chunks.get(i) != null) n += CHUNK_SIZE * 8L;
            return n + stampChunks() * (long) CHUNK_SIZE * 4L;
        }

        private long[] chunkFor(int chunk) {
            long[] block = chunks.get(chunk);
            if (block == null) {
                block = new long[CHUNK_SIZE];
                if (!chunks.compareAndSet(chunk, null, block)) block = chunks.get(chunk);
            }
            return block;
        }
    }

    /** 32-bit backing for {@code INT} and {@code DICT} — half the memory of a wide column. */
    private static final class NarrowColumn extends Column {
        private final AtomicReferenceArray<int[]> chunks;

        NarrowColumn(ColumnSpec spec, int chunks) {
            super(spec, chunks);
            this.chunks = new AtomicReferenceArray<>(chunks);
        }

        @Override int set(int slot, long value) {
            final int[] block = chunkFor(slot >>> CHUNK_SHIFT);
            final int i = slot & CHUNK_MASK;
            final int v = (int) value;
            final int old = block[i];
            if (old == v) return 0;
            block[i] = v;
            return v > old ? DIR_UP : DIR_DOWN;
        }

        @Override void clear(int slot) {
            final int[] block = chunks.get(slot >>> CHUNK_SHIFT);
            if (block != null) block[slot & CHUNK_MASK] = 0;
            clearStamp(slot);
        }

        @Override long get(int slot) {
            final int[] block = chunks.get(slot >>> CHUNK_SHIFT);
            return block == null ? 0L : block[slot & CHUNK_MASK];
        }

        @Override int allocatedChunks() {
            int n = stampChunks();
            for (int i = 0; i < chunks.length(); i++) if (chunks.get(i) != null) n++;
            return n;
        }

        @Override long allocatedBytes() {
            long n = 0;
            for (int i = 0; i < chunks.length(); i++) if (chunks.get(i) != null) n += CHUNK_SIZE * 4L;
            return n + stampChunks() * (long) CHUNK_SIZE * 4L;
        }

        private int[] chunkFor(int chunk) {
            int[] block = chunks.get(chunk);
            if (block == null) {
                block = new int[CHUNK_SIZE];
                if (!chunks.compareAndSet(chunk, null, block)) block = chunks.get(chunk);
            }
            return block;
        }
    }
}
