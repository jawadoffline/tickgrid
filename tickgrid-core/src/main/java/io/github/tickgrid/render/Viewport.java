package io.github.tickgrid.render;

/**
 * Scroll position and the visible row range — the whole of virtualization, and none of it needs a
 * toolkit to test.
 *
 * <p>Uniform row height is a load-bearing constraint, not an oversight. It is what makes
 * "which rows are on screen" O(1) arithmetic instead of a cumulative-offset search, and what lets
 * a million-row grid answer a scroll event without touching a million rows. Variable row heights
 * would require a Fenwick tree over row offsets and would make every hit test a binary search.
 */
public final class Viewport {

    private double rowHeight = 16;
    private double headerHeight = 22;
    private double width;
    private double height;
    private double scrollY;
    private double scrollX;
    private double contentWidth;
    private int rowCount;

    // ------------------------------------------------------------- geometry

    public void setRowHeight(double rowHeight) {
        if (rowHeight <= 0) throw new IllegalArgumentException("rowHeight must be positive");
        this.rowHeight = rowHeight;
        clamp();
    }

    public void setHeaderHeight(double headerHeight) {
        this.headerHeight = Math.max(0, headerHeight);
        clamp();
    }

    public void setSize(double width, double height) {
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        clamp();
    }

    public void setRowCount(int rowCount) {
        this.rowCount = Math.max(0, rowCount);
        clamp();
    }

    public void setContentWidth(double contentWidth) {
        this.contentWidth = Math.max(0, contentWidth);
        clamp();
    }

    public double rowHeight()    { return rowHeight; }
    public double headerHeight() { return headerHeight; }
    public double width()        { return width; }
    public double height()       { return height; }
    public int rowCount()        { return rowCount; }

    /** Height available for rows, below the header. */
    public double bodyHeight() {
        return Math.max(0, height - headerHeight);
    }

    // --------------------------------------------------------------- scroll

    public double scrollY() { return scrollY; }
    public double scrollX() { return scrollX; }

    public double maxScrollY() {
        return Math.max(0, rowCount * rowHeight - bodyHeight());
    }

    public double maxScrollX() {
        return Math.max(0, contentWidth - width);
    }

    /** @return whether the position actually moved */
    public boolean setScrollY(double y) {
        double clamped = Math.max(0, Math.min(y, maxScrollY()));
        if (clamped == scrollY) return false;
        scrollY = clamped;
        return true;
    }

    public boolean setScrollX(double x) {
        double clamped = Math.max(0, Math.min(x, maxScrollX()));
        if (clamped == scrollX) return false;
        scrollX = clamped;
        return true;
    }

    public boolean scrollByRows(double rows) {
        return setScrollY(scrollY + rows * rowHeight);
    }

    /** Puts this row at the top, as far as the content allows. */
    public boolean scrollToRow(int row) {
        return setScrollY(row * rowHeight);
    }

    /**
     * Scrolls the minimum distance needed to bring a row fully into view. Scrolling further than
     * necessary is disorienting: keyboard navigation should nudge the viewport, not recentre it.
     */
    public boolean ensureRowVisible(int row) {
        if (row < 0 || row >= rowCount) return false;
        final double top = row * rowHeight;
        final double bottom = top + rowHeight;
        if (top < scrollY) return setScrollY(top);
        if (bottom > scrollY + bodyHeight()) return setScrollY(bottom - bodyHeight());
        return false;
    }

    // ------------------------------------------------------- visible range

    /** First row index intersecting the viewport. */
    public int firstVisibleRow() {
        if (rowCount == 0) return 0;
        return Math.max(0, (int) Math.floor(scrollY / rowHeight));
    }

    /** One past the last row intersecting the viewport. */
    public int visibleRowLimit() {
        if (rowCount == 0) return 0;
        int limit = (int) Math.ceil((scrollY + bodyHeight()) / rowHeight);
        return Math.max(firstVisibleRow(), Math.min(rowCount, limit));
    }

    public int visibleRowCount() {
        return visibleRowLimit() - firstVisibleRow();
    }

    /** Whole rows that fit in the body — the step for page up and page down. */
    public int rowsPerPage() {
        return Math.max(1, (int) Math.floor(bodyHeight() / rowHeight));
    }

    /** Y of a row's top edge in control coordinates, header and scroll accounted for. */
    public double rowTop(int row) {
        return headerHeight + row * rowHeight - scrollY;
    }

    /**
     * The row at a control-relative y, or {@code -1} for the header, past the last row, or an empty
     * view.
     */
    public int rowAt(double y) {
        if (y < headerHeight || rowCount == 0) return -1;
        int row = (int) Math.floor((y - headerHeight + scrollY) / rowHeight);
        return row >= 0 && row < rowCount ? row : -1;
    }

    public boolean isInHeader(double y) {
        return y >= 0 && y < headerHeight;
    }

    private void clamp() {
        setScrollY(scrollY);
        setScrollX(scrollX);
    }
}
