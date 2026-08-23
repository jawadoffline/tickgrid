package io.github.tickgrid.view;

/** Which column the view is ordered by, and in which direction. */
public final class SortSpec {

    private static final SortSpec NONE = new SortSpec(-1, false);

    private final int column;
    private final boolean descending;

    private SortSpec(int column, boolean descending) {
        this.column = column;
        this.descending = descending;
    }

    /** Slot order — the order rows were first seen. Cheapest, and stable by construction. */
    public static SortSpec none() {
        return NONE;
    }

    public static SortSpec ascending(int column) {
        return new SortSpec(column, false);
    }

    public static SortSpec descending(int column) {
        return new SortSpec(column, true);
    }

    /** The next state of a header click cycle: ascending, then descending, then unsorted. */
    public SortSpec toggled(int clickedColumn) {
        if (column != clickedColumn) return ascending(clickedColumn);
        return descending ? none() : descending(clickedColumn);
    }

    public boolean isSorted()   { return column >= 0; }
    public int column()         { return column; }
    public boolean descending() { return descending; }

    @Override
    public boolean equals(Object o) {
        return o instanceof SortSpec s && s.column == column && s.descending == descending;
    }

    @Override
    public int hashCode() {
        return column * 2 + (descending ? 1 : 0);
    }

    @Override
    public String toString() {
        return isSorted() ? "col" + column + (descending ? " desc" : " asc") : "unsorted";
    }
}
