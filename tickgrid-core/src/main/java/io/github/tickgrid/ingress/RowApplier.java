package io.github.tickgrid.ingress;

/** Receives one coherent row per changed slot during a drain. Called on the drain thread only. */
@FunctionalInterface
public interface RowApplier {

    /**
     * @param slot   the row's slot index
     * @param values a scratch array holding this row's columns; <b>valid only for this call</b>,
     *               reused for the next row
     * @param count  number of valid entries in {@code values}
     */
    void apply(int slot, long[] values, int count);

    /**
     * Retires a row, and returns the store epoch that now excludes it.
     *
     * <p>Called by the drain when a key has been passed to {@link ConflatingIngress#retire}, in
     * queue order — so it always lands after every update that was submitted for that key before
     * the retirement, and a staged row can never resurrect a removed one.
     *
     * <p>The returned epoch is what makes slot reuse safe. The ingress holds the slot until a
     * published view snapshot carries an epoch at least this high, which is the proof that no live
     * snapshot still lists it. An applier that cannot supply a meaningful epoch cannot support
     * retirement, which is why the default throws rather than returning a number that would let a
     * slot be handed out while it is still being drawn.
     *
     * @return the store's removal epoch after this row was tombstoned
     */
    default int remove(int slot) {
        throw new UnsupportedOperationException(
                "this RowApplier does not support retirement: " + getClass().getName());
    }
}
