package io.github.tickgrid.view;

import io.github.tickgrid.store.ColumnStore;

/**
 * Decides whether a row belongs in the view.
 *
 * <p>Evaluated on the recompute thread, against the live store. Those reads are racy — a value may
 * change between the filter seeing it and the frame that draws it — and that is deliberate: a row
 * appearing or leaving the view one recompute late is invisible to a person, and the alternative
 * (snapshotting every column a predicate might touch) costs more than it buys.
 *
 * <p>The contract that does matter: a filter must be <b>pure and total</b>. It runs outside the
 * frame, so an exception kills the recompute rather than one row, and a filter that reads mutable
 * state of its own can make the view flicker between recomputes for no visible reason.
 */
@FunctionalInterface
public interface RowFilter {

    boolean test(ColumnStore store, int slot);

    /** Admits everything. The default, and free — the recompute skips the predicate entirely. */
    static RowFilter all() {
        return All.INSTANCE;
    }

    default RowFilter and(RowFilter other) {
        return (store, slot) -> test(store, slot) && other.test(store, slot);
    }

    default RowFilter negate() {
        return (store, slot) -> !test(store, slot);
    }

    /** Identifiable so {@link ViewModel} can recognise the no-op case and skip the call per row. */
    enum All implements RowFilter {
        INSTANCE;

        @Override public boolean test(ColumnStore store, int slot) {
            return true;
        }
    }
}
