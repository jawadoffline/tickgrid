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
}
