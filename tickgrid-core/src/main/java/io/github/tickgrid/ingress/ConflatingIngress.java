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
 * <h2>Retirement</h2>
 * {@link #retire} takes a key out of the view and frees its slot for the next instrument. The two
 * halves of that are separated on purpose.
 *
 * <p>The removal itself travels through the same queue as updates, so it lands <b>after</b> every
 * update submitted for that key beforehand. Removing out of band instead would let the drain apply
 * a staged row after the removal and resurrect a row that was supposed to be gone.
 *
 * <p>Reissuing the slot waits longer still. A published {@code ViewSnapshot} holds slot indices, so
 * handing a slot to a new instrument while a snapshot listing it is still on screen would paint one
 * symbol's prices on another symbol's line. {@link #reclaim} takes the epoch of the snapshot being
 * rendered and frees only those slots the snapshot already excludes; {@code TickGridView} calls it
 * once per frame.
 *
 * <p><b>Capacity must allow for that delay.</b> A retired slot is unavailable until a recompute has
 * published and a frame has drawn — a few hundred milliseconds under a throttled sort — so
 * {@code capacity} has to cover the peak live key count <i>plus</i> the retirements that can be in
 * flight across that window, not just the peak. Sized exactly to a rotating universe instead, the
 * blotter demo rejected 251,554 submits in fifteen seconds; sixty-four slots of headroom took that
 * to zero.
 *
 * <h2>Threading contract</h2>
 * {@code submit} is safe from any thread. Writes <b>for a single key</b> must be serialized by the
 * caller — feeds are normally sharded by symbol, so this costs nothing in practice, and it is what
 * makes the single-writer seqlock sound. Concurrent submits for <i>different</i> keys are
 * unrestricted. {@link #drain} and {@link #reclaim} are single-consumer and must always be called from
 * the same thread. {@link #retire} follows the same single-writer-per-key rule as {@code submit}:
 * retiring a key while another thread submits it is a caller error.
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
    /**
     * The same, for retirements, encoded as {@code -(slot + 1)} so the drain can tell the two kinds
     * of entry apart without a second queue. One queue is what keeps a removal ordered against the
     * updates that preceded it.
     */
    private final Integer[] boxedRetires;

    /** Drain-thread scratch for one row. Handed to the applier, never escapes. */
    private final long[] scratch;

    /** Slots removed from the store but not yet safe to reissue, with the epoch that excludes each. */
    private final int[] retiredSlots;
    private final int[] retiredEpochs;
    private int retiredCount;                          // drain thread only

    private final LongAdder submitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder queueFull = new LongAdder();
    private final LongAdder tearRetries = new LongAdder();
    private final LongAdder retired = new LongAdder();
    private long removed;                              // drain thread only
    private long reclaimed;                            // drain thread only
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
        // currently copying, once by a producer that raced the clear. A retirement can be queued
        // alongside both. Three per slot is the bound; four keeps the queue a power of two without
        // arithmetic, so offer() can never fail for capacity reasons.
        this.pending = new MpscArrayQueue<>(nextPowerOfTwo(capacity * 4));

        this.boxedSlots = new Integer[capacity];
        this.boxedRetires = new Integer[capacity];
        for (int i = 0; i < capacity; i++) {
            boxedSlots[i] = i;                          // outside Integer.valueOf's cache on purpose
            boxedRetires[i] = -(i + 1);
        }

        this.retiredSlots = new int[capacity];
        this.retiredEpochs = new int[capacity];
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
                // Unreachable given the queue sizing; counted rather than swallowed so a sizing
                // regression shows up as a number instead of a stale row.
                queueFull.increment();
                dirty.set(slot, 0);
                return false;
            }
        }
        return true;
    }

    /**
     * Retires {@code key}: its row leaves the store on the next drain, and its slot is reissued to
     * a later instrument once {@link #reclaim} can show no snapshot still lists it.
     *
     * <p>The key stops resolving immediately, so a submit arriving afterwards is treated as a new
     * instrument and is given a different slot. The removal itself is queued rather than applied
     * here, which is what keeps it behind the updates already staged for this key.
     *
     * <p>Subject to the single-writer-per-key contract: call this from the thread that submits the
     * key, or serialise it with those submits.
     *
     * @return {@code false} if the key held no slot, so there was nothing to retire
     */
    public boolean retire(K key) {
        final int slot = index.retire(key);
        if (slot < 0) return false;
        if (!pending.offer(boxedRetires[slot])) {
            // Unreachable given the queue sizing, and deliberately not recovered from by recycling
            // the slot: the row is still live in the store, so handing the slot straight to another
            // instrument would draw two symbols on one line. Leaking it leaves a stale row on
            // screen instead, which is wrong but not corrupt, and the counter says it happened.
            queueFull.increment();
            return false;
        }
        retired.increment();
        return true;
    }

    /**
     * Frees every retired slot that {@code publishedStoreEpoch} already excludes, and returns how
     * many. Call once per frame from the drain thread, passing the epoch of the snapshot about to
     * be rendered.
     *
     * <p>A slot removed at store epoch {@code E} cannot appear in any snapshot dated {@code E} or
     * later, because the snapshot's row list was taken from the store after the removal. So a
     * published snapshot at or past {@code E} is proof the slot is off screen — provided nothing
     * still holds an <i>older</i> snapshot, which is why this belongs on the render thread,
     * immediately after that thread has read the snapshot it will draw.
     */
    public int reclaim(int publishedStoreEpoch) {
        int kept = 0;
        int freed = 0;
        for (int i = 0; i < retiredCount; i++) {
            // Subtraction rather than >=, so the comparison survives the epoch counter wrapping.
            if (publishedStoreEpoch - retiredEpochs[i] >= 0) {
                index.recycle(retiredSlots[i]);
                freed++;
            } else {
                retiredSlots[kept] = retiredSlots[i];
                retiredEpochs[kept] = retiredEpochs[i];
                kept++;
            }
        }
        retiredCount = kept;
        reclaimed += freed;
        return freed;
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
            final int encoded = boxed;

            if (encoded < 0) {
                applyRetirement(-encoded - 1, applier);
                continue;                               // a removal is not a row applied
            }
            final int slot = encoded;

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

    /**
     * Removes one row and parks its slot until a snapshot proves it is off screen.
     *
     * <p>The dirty flag is cleared here rather than left set. Nothing more can be submitted for
     * this slot — the key stopped resolving to it when it was retired — so the flag would otherwise
     * stay raised forever and the slot would look permanently queued to whatever reissues it.
     */
    private void applyRetirement(int slot, RowApplier applier) {
        dirty.set(slot, 0);
        final int epoch = applier.remove(slot);
        retiredSlots[retiredCount] = slot;
        retiredEpochs[retiredCount] = epoch;
        retiredCount++;
        removed++;
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
     * path allocation-free, but it costs ~16 bytes of object plus a reference in the queue array,
     * and retirement doubles it by needing a second box per slot. At large capacities that is the
     * strongest argument for hand-rolling a primitive MPSC queue.
     */
    public long footprintBytes() {
        final long capacity = index.capacity();
        final long stagingBytes = (long) staging.length * 8L;
        final long flagBytes = capacity * 8L;                       // seq + dirty, 4 bytes each
        final long boxBytes = capacity * 40L;                       // Integer object + array slot, x2
        final long retireBytes = capacity * 8L;                     // parked slot + epoch
        final long queueBytes = (long) pending.capacity() * 4L;
        return stagingBytes + flagBytes + boxBytes + retireBytes + queueBytes;
    }

    public long submittedCount()  { return submitted.sum(); }
    public long appliedCount()    { return applied; }
    public long rejectedCount()   { return rejected.sum(); }
    public long queueFullCount()  { return queueFull.sum(); }
    public long tearRetryCount()  { return tearRetries.sum(); }
    public long drainCallCount()  { return drainCalls; }
    public long budgetExpiredCount() { return budgetExpirations; }
    public int  keyCount()        { return index.size(); }
    /** Keys passed to {@link #retire}. */
    public long retiredCount()    { return retired.sum(); }
    /** Retirements the drain has carried into the store. */
    public long removedCount()    { return removed; }
    /** Slots handed back for reuse. */
    public long reclaimedCount()  { return reclaimed; }
    /** Slots removed but not yet provably off screen. Should sit near zero on a running grid. */
    public int  awaitingReclaim() { return retiredCount; }
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
