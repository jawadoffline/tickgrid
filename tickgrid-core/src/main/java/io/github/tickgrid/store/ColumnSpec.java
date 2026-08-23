package io.github.tickgrid.store;

/**
 * One column's declaration: what it is called, how it is stored, and whether the store should track
 * when it last changed.
 *
 * <p>Flash tracking is opt-in per column because it costs 4 bytes per row. Declaring it on every
 * column of a million-row grid buys 4 MB per column for an effect the eye only registers on prices.
 */
public final class ColumnSpec {

    private final String name;
    private final ColumnKind kind;
    private final int scale;
    private final boolean flashOnChange;

    private ColumnSpec(String name, ColumnKind kind, int scale, boolean flashOnChange) {
        this.name = name;
        this.kind = kind;
        this.scale = scale;
        this.flashOnChange = flashOnChange;
    }

    public static ColumnSpec longs(String name) {
        return new ColumnSpec(name, ColumnKind.LONG, 0, false);
    }

    /** Fixed-point decimal with {@code decimals} digits after the point, stored as a scaled long. */
    public static ColumnSpec scaled(String name, int decimals) {
        if (decimals < 0 || decimals > 18) {
            throw new IllegalArgumentException("decimals out of range: " + decimals);
        }
        return new ColumnSpec(name, ColumnKind.SCALED, decimals, false);
    }

    public static ColumnSpec doubles(String name) {
        return new ColumnSpec(name, ColumnKind.DOUBLE, 0, false);
    }

    public static ColumnSpec ints(String name) {
        return new ColumnSpec(name, ColumnKind.INT, 0, false);
    }

    /** A dictionary-encoded string column. Values are ordinals into the store's dictionary. */
    public static ColumnSpec dict(String name) {
        return new ColumnSpec(name, ColumnKind.DICT, 0, false);
    }

    /** Returns a copy that tracks its last change time and direction. Costs 4 bytes per row. */
    public ColumnSpec flashOnChange() {
        return new ColumnSpec(name, kind, scale, true);
    }

    public String name()          { return name; }
    public ColumnKind kind()      { return kind; }
    public int scale()            { return scale; }
    public boolean flashTracked() { return flashOnChange; }

    /** Bytes per row, including the flash stamp if this column tracks changes. */
    public int bytesPerRow() {
        return kind.bytes() + (flashOnChange ? 4 : 0);
    }

    @Override
    public String toString() {
        return name + ":" + kind + (kind == ColumnKind.SCALED ? "(" + scale + ")" : "")
                + (flashOnChange ? "+flash" : "");
    }
}
