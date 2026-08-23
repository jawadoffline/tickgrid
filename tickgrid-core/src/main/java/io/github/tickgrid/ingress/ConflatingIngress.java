package io.github.tickgrid.ingress;

import org.jctools.queues.MpscArrayQueue;

import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Conflating, thread-agnostic ingestion.
 *
 * <p>Producers call {@link #submit} from any thread. Each row lands in a per-slot staging area and
 * the slot is queued once while it is dirty, so the drain does work proportional to the number of
 * <b>changed rows</b>, not the number of messages received. A 200k msg/sec burst across 5k
 * instruments costs 5k applications per frame.
 *
 * <h2>The handshake</h2>
 * Two details make this correct, and getting either wrong produces a bug that no throughput
 * benchmark will find.
 *
 * <p><b>1. The dirty flag is cleared before the copy, never after.</b> Clearing after means a
 * producer writing during the copy sees the flag still set, skips the enqueue, and its value is
 * stranded in staging with a clear flag — invisible until that key happens to tick again. Clearing
 * first turns that race into a harmless redundant re-apply. See {@link ClearPolicy}.
 *
 * <p><b>2. Every slot is a seqlock.</b> Without one the drain can read {@code bid} from one message
 * and {@code ask} from the next and render a crossed market that never existed. The writer brackets
 * its stores between an odd and an even version; the reader retries until it sees the same even
 * version on both sides of its copy. See {@link TearProtection}.
 *
 * <h2>Threading contract</h2>
 * {@code submit} is safe from any thread. Writes <b>for a single key</b> must be serialized by the
 * caller — feeds are normally sharded by symbol, so this costs nothing in practice, and it is what
 * makes the single-writer seqlock sound. Concurrent submits for <i>different</i> keys are
 * unrestricted. {@link #drain} is single-consumer and must always be called from the same thread.
 */
public final class ConflatingIngress<T, K> {

    /** When the drain clears a slot's dirty flag. */
    public enum ClearPolicy {
        /** Correct. A producer racing the copy re-enqueues, costing at most a redundant apply. */
        BEFORE_COPY,
        /**
         * Loses updates permanently. Retained only so {@code HandshakeDemo} can demonstrate the
         * failure this class exists to avoid — never select it in production.
         */
        AFTER_COPY
    }

    /** Whether per-slot seqlocks are engaged. */
    public enum TearProtection {
        /** Correct. The drain only ever sees a row as some single producer wrote it. */
        SEQLOCK,
        /**
         * Allows torn rows. Retained only for {@code HandshakeDemo} — never select it in
         * production.
         */
        NONE
    }

    private static final int BUDGET_CHECK_MASK = 63;   // check the clock every 64 applies

    private final KeyIndex<K> index;
    private final RowExtractor<T, K> extractor;
    private final int columnCount;
    private final ClearPolicy clearPolicy;
    private final TearProtection tearProtection;

    /** Flat staging arena: slot * columnCount + column. Written plainly under the seqlock. */
    private final long[] staging;
    /** Per-slot seqlock version. Even = stable, odd = write in progress. */
    private final AtomicIntegerArray seq;
    /** Per-slot dirty flag, 0 or 1. */
    private final AtomicIntegerArray dirty;

    private final MpscArrayQueue<Integer> pending;
    /**
     * Preallocated boxes for slot indices. JCTools' queues are generic, and boxing per submit would
     * allocate on the hot path; a fixed box per slot keeps the enqueue allocation-free.
     */
    private final Integer[] boxedSlots;

    /** Drain-thread scratch for one row. Handed to the applier, never escapes. */
    private final long[] scratch;

    private final LongAdder submitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder queueFull = new LongAdder();
    private final LongAdder tearRetries = new LongAdder();
    private long applied;                              // drain thread only
    private long drainCalls;                           // drain thread only
    private long budgetExpirations;                    // drain thread only

    public ConflatingIngress(int capacity, int columnCount, RowExtractor<T, K> extractor) {
        this(capacity, columnCount, extractor, ClearPolicy.BEFORE_COPY, TearProtection.SEQLOCK);
    }

    public ConflatingIngress(int capacity,
                             int columnCount,
                             RowExtractor<T, K> extractor,
                             ClearPolicy clearPolicy,
                             TearProtection tearProtection) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        if (columnCount < 1) throw new IllegalArgumentException("columnCount must be positive");
        this.index = new KeyIndex<>(capacity);
        this.extractor = extractor;
        this.columnCount = columnCount;
        this.clearPolicy = clearPolicy;
        this.tearProtection = tearProtection;

        this.staging = new long[Math.multiplyExact(capacity, columnCount)];
        this.seq = new AtomicIntegerArray(capacity);
        this.dirty = new AtomicIntegerArray(capacity);
        this.scratch = new long[columnCount];

        // Under BEFORE_COPY a slot can be transiently queued twice: once by the entry the drain is
        // currently copying, once by a producer that raced the clear. Two boxes per slot is the
        // exact bound, so offer() can never fail for capacity reasons.
        this.pending = new MpscArrayQueue<>(nextPowerOfTwo(capacity * 2));

        this.boxedSlots = new Integer[capacity];
        for (int i = 0; i < capacity; i++) {
            boxedSlots[i] = i;                          // outside Integer.valueOf's cache on purpose
        }
    }

    // ------------------------------------------------------------------ produce

    /**
     * Stages {@code row} and queues its slot if it was not already dirty.
     *
     * <p>Lock-free, non-blocking, and allocation-free once the key has been seen. Safe from any
     * thread, subject to the single-writer-per-key contract on this class.
     *
     * @return {@code false} if the row was dropped because key capacity is exhausted
     */
    public boolean submit(T row) {
        final int slot = index.getOrCreate(extractor.key(row));
        if (slot < 0) {
            rejected.increment();
            return false;
        }
        submitted.increment();

        final int base = slot * columnCount;

        if (tearProtection == TearProtection.SEQLOCK) {
            // Single writer per key, so a plain read of our own version is sufficient.
            final int v = seq.getPlain(slot);
            seq.set(slot, v + 1);                       // odd: write in progress
            VarHandle.storeStoreFence();                // ...and it must land before the payload
            extractor.extract(row, staging, base);
            seq.set(slot, v + 2);                       // even: release-publishes the payload
        } else {
            extractor.extract(row, staging, base);
        }

        // Only now is the row coherent, so only now may it become visible to the drain.
        if (dirty.compareAndSet(slot, 0, 1)) {
            if (!pending.offer(boxedSlots[slot])) {
                // Unreachable given the 2x sizing above; counted rather than swallowed so a sizing
                // regression shows up as a number instead of a stale row.
                queueFull.increment();
                dirty.set(slot, 0);
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------- drain

    /**
     * Applies every queued slot, or as many as {@code budgetNanos} allows. Whatever is left stays
     * queued for the next call, so a burst can never overrun a frame.
     *
     * <p>Single-consumer: always call from the same thread.
     *
     * @return the number of rows applied
     */
    public int drain(long budgetNanos, RowApplier applier) {
        final long deadline = System.nanoTime() + budgetNanos;
        int count = 0;

        for (;;) {
            final Integer boxed = pending.poll();
            if (boxed == null) break;
            final int slot = boxed;

            if (clearPolicy == ClearPolicy.BEFORE_COPY) {
                // The correction. A producer writing from here on re-enqueues the slot, so the
                // worst case is applying the same row twice — never losing it.
                dirty.set(slot, 0);
                copyCoherent(slot);
            } else {
                copyCoherent(slot);
                dirty.set(slot, 0);                     // the bug: strands any write that raced us
            }

            applier.apply(slot, scratch, columnCount);
            count++;

            if ((count & BUDGET_CHECK_MASK) == 0 && System.nanoTime() >= deadline) {
                budgetExpirations++;
                break;
            }
        }

        applied += count;
        drainCalls++;
        return count;
    }

    /** Drains everything with no time limit. For tests and shutdown, not for a render pulse. */
    public int drainAll(RowApplier applier) {
        return drain(Long.MAX_VALUE / 2, applier);
    }

    /** Copies one slot into {@link #scratch}, retrying until the row is internally consistent. */
    private void copyCoherent(int slot) {
        final int base = slot * columnCount;

        if (tearProtection != TearProtection.SEQLOCK) {
            System.arraycopy(staging, base, scratch, 0, columnCount);
            return;
        }

        for (;;) {
            final int before = seq.getAcquire(slot);
            if ((before & 1) != 0) {                    // a writer holds the slot
                Thread.onSpinWait();
                continue;
            }
            System.arraycopy(staging, base, scratch, 0, columnCount);
            VarHandle.loadLoadFence();                  // payload reads must precede the recheck
            if (seq.getAcquire(slot) == before) return;
            tearRetries.increment();
        }
    }

    // -------------------------------------------------------------------- stats

    /** Rows currently queued and not yet applied — the staleness signal worth putting on a HUD. */
    public int backlog() {
        return pending.size();
    }

    /**
     * Approximate heap the ingress itself holds, excluding the store it drains into and the key
     * objects the caller allocated.
     *
     * <p>The boxed-slot cache is the line worth watching: JCTools' queues are generic, so a slot
     * index reaches the queue as an {@code Integer}. Preallocating one box per slot keeps the hot
     * path allocation-free, but it costs ~16 bytes of object plus a reference in the queue array.
     * At large capacities that is the strongest argument for hand-rolling a primitive MPSC queue.
     */
    public long footprintBytes() {
        final long capacity = index.capacity();
        final long stagingBytes = (long) staging.length * 8L;
        final long flagBytes = capacity * 8L;                       // seq + dirty, 4 bytes each
        final long boxBytes = capacity * 20L;                       // Integer object + array slot
        final long queueBytes = (long) pending.capacity() * 4L;
        return stagingBytes + flagBytes + boxBytes + queueBytes;
    }

    public long submittedCount()  { return submitted.sum(); }
    public long appliedCount()    { return applied; }
    public long rejectedCount()   { return rejected.sum(); }
    public long queueFullCount()  { return queueFull.sum(); }
    public long tearRetryCount()  { return tearRetries.sum(); }
    public long drainCallCount()  { return drainCalls; }
    public long budgetExpiredCount() { return budgetExpirations; }
    public int  keyCount()        { return index.size(); }
    public KeyIndex<K> keyIndex() { return index; }

    /**
     * Messages received per row applied. This is the number that justifies the architecture — the
     * factor by which the renderer is doing less work than the feed is producing.
     */
    public double conflationRatio() {
        return applied == 0 ? 0 : (double) submitted.sum() / applied;
    }

    private static int nextPowerOfTwo(int v) {
        return Integer.highestOneBit(Math.max(2, v - 1)) * 2;
    }
}
