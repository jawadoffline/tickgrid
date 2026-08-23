package io.github.tickgrid.render;

/** How one store column is drawn: its title, width, alignment and number format. */
public final class GridColumn {

    public enum Format {
        /** Resolved through the store's string dictionary. */
        TEXT,
        /** A {@code SCALED} column, printed with its declared decimals. */
        FIXED,
        /** A {@code DOUBLE} column, printed with a fixed number of decimals. */
        DECIMAL,
        /** A whole number. */
        INTEGER,
        /** A whole number with thousands separators. */
        GROUPED
    }

    private final int storeColumn;
    private final String title;
    private final Format format;
    private double width;
    private int decimals;
    private int sourceScale = -1;
    private boolean rightAligned;
    private boolean colorBySign;

    private GridColumn(int storeColumn, String title, Format format) {
        this.storeColumn = storeColumn;
        this.title = title;
        this.format = format;
        this.rightAligned = format != Format.TEXT;
        this.width = 88;
    }

    public static GridColumn text(int storeColumn, String title) {
        return new GridColumn(storeColumn, title, Format.TEXT);
    }

    public static GridColumn fixed(int storeColumn, String title, int decimals) {
        GridColumn c = new GridColumn(storeColumn, title, Format.FIXED);
        c.decimals = decimals;
        c.sourceScale = decimals;
        return c;
    }

    /**
     * A {@code SCALED} column stored at one precision and shown at another.
     *
     * <p>Crypto is the obvious case: a venue quotes eight decimals for everything, which is exact
     * and correct to store and unreadable to display next to a five-figure BTC price. Storing at
     * {@code sourceScale} keeps the value the venue actually sent; {@code displayDecimals} decides
     * what fits in the column.
     */
    public static GridColumn scaled(int storeColumn, String title, int sourceScale, int displayDecimals) {
        if (displayDecimals > sourceScale) {
            throw new IllegalArgumentException(
                    "cannot display " + displayDecimals + " decimals from a scale-" + sourceScale
                            + " column: the precision was never stored");
        }
        GridColumn c = new GridColumn(storeColumn, title, Format.FIXED);
        c.decimals = displayDecimals;
        c.sourceScale = sourceScale;
        return c;
    }

    public static GridColumn decimal(int storeColumn, String title, int decimals) {
        GridColumn c = new GridColumn(storeColumn, title, Format.DECIMAL);
        c.decimals = decimals;
        return c;
    }

    public static GridColumn integer(int storeColumn, String title) {
        return new GridColumn(storeColumn, title, Format.INTEGER);
    }

    public static GridColumn grouped(int storeColumn, String title) {
        return new GridColumn(storeColumn, title, Format.GROUPED);
    }

    public GridColumn width(double width) {
        this.width = Math.max(8, width);
        return this;
    }

    public GridColumn leftAligned() {
        this.rightAligned = false;
        return this;
    }

    /** Paints the value green when positive and red when negative. */
    public GridColumn colorBySign() {
        this.colorBySign = true;
        return this;
    }

    public int storeColumn()      { return storeColumn; }
    public String title()         { return title; }
    public Format format()        { return format; }
    public double width()         { return width; }
    public int decimals()         { return decimals; }
    /** The scale the value is stored at, which may exceed what is displayed. */
    public int sourceScale()      { return sourceScale < 0 ? decimals : sourceScale; }
    public boolean rightAligned() { return rightAligned; }
    public boolean isColorBySign() { return colorBySign; }
}
