package io.github.tickgrid.render;

import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.view.SortSpec;
import io.github.tickgrid.view.ViewSnapshot;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

/**
 * Paints the grid onto canvases. Owns no state that outlives a frame except its scratch buffer.
 *
 * <p>Three surfaces, painted independently so that the expensive one is not redone for cheap
 * reasons: the <b>body</b> (rows, values, flashes), the <b>header</b> (only on scroll, resize or a
 * sort change), and the <b>overlay</b> (selection, hover, focus — repainted when the pointer moves,
 * which must not cost a body repaint).
 *
 * <p>Only the visible row range is drawn. The spike measured the marginal cost of a text cell at
 * ~1.4 µs and a 16.67 ms budget at roughly 9,000 cells, so a screenful is never close to the limit;
 * virtualization is here because a million-row grid must not <i>attempt</i> a million rows, not
 * because a screenful is expensive.
 */
public final class GridRenderer {

    private final GridTheme theme;
    private final List<GridColumn> columns;
    private final Font font;
    private final GlyphWidths glyphs;

    /** Reused for every value on every frame; see {@link FixedFormat}. */
    private final char[] scratch = new char[FixedFormat.MAX_CHARS];

    /** Set during a body paint if any cell drawn is still inside its flash window. */
    private boolean flashActive;

    public GridRenderer(GridTheme theme, List<GridColumn> columns) {
        this.theme = theme;
        this.columns = List.copyOf(columns);
        this.font = Font.font(theme.fontFamily, theme.fontSize);
        this.glyphs = new GlyphWidths(font);
    }

    public GlyphWidths glyphs()      { return glyphs; }
    public GridTheme theme()         { return theme; }
    public List<GridColumn> columns() { return columns; }

    /** Total width of all columns, for the horizontal scroll range. */
    public double contentWidth() {
        double w = 0;
        for (GridColumn c : columns) w += c.width();
        return w;
    }

    /** Whether the last body paint drew a cell that is still lit, so the next frame is dirty. */
    public boolean isFlashActive() {
        return flashActive;
    }

    /** Column index at a content-relative x, or {@code -1}. */
    public int columnAt(double x, double scrollX) {
        double cursor = -scrollX;
        for (int i = 0; i < columns.size(); i++) {
            double next = cursor + columns.get(i).width();
            if (x >= cursor && x < next) return i;
            cursor = next;
        }
        return -1;
    }

    // ----------------------------------------------------------------- body

    public void paintBody(GraphicsContext gc, ColumnStore store, ViewSnapshot view,
                          Viewport viewport, double scale) {
        flashActive = false;
        gc.setFont(font);

        final double w = viewport.width();
        final double bodyTop = viewport.headerHeight();
        final double bodyH = viewport.bodyHeight();

        gc.setFill(theme.background);
        gc.fillRect(0, bodyTop, w, bodyH);

        // Clip to the body. The row scrolled half under the header would otherwise be painted into
        // the header's band and rely on the header canvas sitting on top to hide it -- correct by
        // accident, and wrong the moment the z-order or the header's opacity changes.
        gc.save();
        gc.beginPath();
        gc.rect(0, bodyTop, w, bodyH);
        gc.clip();

        final int first = viewport.firstVisibleRow();
        final int limit = Math.min(viewport.visibleRowLimit(), view.count());
        final double rowH = viewport.rowHeight();
        final double hairline = PixelSnap.hairlineWidth(scale);

        for (int row = first; row < limit; row++) {
            final int slot = view.slotAt(row);
            // A row removed since the last recompute is still listed; skip it rather than paint a
            // tombstone. One boolean array read per visible row.
            if (!store.isLive(slot)) continue;

            final double yTop = PixelSnap.snap(viewport.rowTop(row), scale);
            final double yBottom = PixelSnap.snap(viewport.rowTop(row) + rowH, scale);
            final double rowHeight = yBottom - yTop;

            gc.setFill((row & 1) == 0 ? theme.rowEven : theme.rowOdd);
            gc.fillRect(0, yTop, w, rowHeight);

            paintRowCells(gc, store, slot, yTop, rowHeight, viewport, scale);
        }

        paintColumnRules(gc, viewport, bodyTop, bodyH, scale, hairline);
        gc.restore();
    }

    private void paintRowCells(GraphicsContext gc, ColumnStore store, int slot,
                               double yTop, double rowHeight, Viewport viewport, double scale) {
        final double baseline = PixelSnap.snap(yTop + rowHeight - theme.cellPadding * 0.6, scale);
        double x = -viewport.scrollX();
        final double viewWidth = viewport.width();

        for (GridColumn column : columns) {
            final double cellWidth = column.width();
            final double right = x + cellWidth;
            // Horizontal virtualization, such as it is: skip anything off either edge.
            if (right < 0 || x > viewWidth) {
                x = right;
                continue;
            }

            final int storeColumn = column.storeColumn();

            final Color flash = theme.flashColor(
                    store.flashAgeMillis(slot, storeColumn),
                    store.flashDirection(slot, storeColumn));
            if (flash != null) {
                flashActive = true;
                gc.setFill(flash);
                gc.fillRect(PixelSnap.snap(x, scale), yTop,
                        PixelSnap.snap(right, scale) - PixelSnap.snap(x, scale), rowHeight);
            }

            paintCellText(gc, store, slot, column, x, right, baseline);
            x = right;
        }
    }

    private void paintCellText(GraphicsContext gc, ColumnStore store, int slot,
                               GridColumn column, double left, double right, double baseline) {
        final int storeColumn = column.storeColumn();
        final String value;
        double signValue = 0;

        switch (column.format()) {
            case TEXT -> {
                String s = store.getString(slot, storeColumn);
                value = s == null ? "" : s;
            }
            case FIXED -> {
                long raw = store.get(slot, storeColumn);
                signValue = raw;
                long shown = FixedFormat.rescale(raw, column.sourceScale(), column.decimals());
                int start = FixedFormat.fixed(scratch, shown, column.decimals());
                value = new String(scratch, start, scratch.length - start);
            }
            case DECIMAL -> {
                double d = store.getDouble(slot, storeColumn);
                signValue = d;
                int start = FixedFormat.decimal(scratch, d, column.decimals());
                value = new String(scratch, start, scratch.length - start);
            }
            case INTEGER -> {
                long raw = store.get(slot, storeColumn);
                signValue = raw;
                int start = FixedFormat.integer(scratch, raw);
                value = new String(scratch, start, scratch.length - start);
            }
            case GROUPED -> {
                long raw = store.get(slot, storeColumn);
                signValue = raw;
                int start = FixedFormat.grouped(scratch, raw);
                value = new String(scratch, start, scratch.length - start);
            }
            default -> value = "";
        }

        if (value.isEmpty()) return;

        if (column.isColorBySign()) {
            gc.setFill(signValue > 0 ? theme.positive : signValue < 0 ? theme.negative : theme.textDim);
        } else {
            gc.setFill(theme.text);
        }

        final double x = column.rightAligned()
                ? right - theme.cellPadding - glyphs.widthOf(value)
                : left + theme.cellPadding;
        gc.fillText(value, x, baseline);
    }

    private void paintColumnRules(GraphicsContext gc, Viewport viewport,
                                  double top, double height, double scale, double hairline) {
        gc.setStroke(theme.gridLine);
        gc.setLineWidth(hairline);
        double x = -viewport.scrollX();
        for (int i = 0; i < columns.size() - 1; i++) {
            x += columns.get(i).width();
            if (x < 0 || x > viewport.width()) continue;
            final double snapped = PixelSnap.snapHairline(x, scale);
            gc.strokeLine(snapped, top, snapped, top + height);
        }
    }

    // --------------------------------------------------------------- header

    public void paintHeader(GraphicsContext gc, Viewport viewport, SortSpec sort, double scale) {
        gc.setFont(font);
        final double w = viewport.width();
        final double h = viewport.headerHeight();

        gc.setFill(theme.headerBackground);
        gc.fillRect(0, 0, w, h);

        final double baseline = PixelSnap.snap(h - theme.cellPadding * 0.9, scale);
        double x = -viewport.scrollX();

        for (int i = 0; i < columns.size(); i++) {
            final GridColumn column = columns.get(i);
            final double right = x + column.width();
            if (right < 0 || x > w) {
                x = right;
                continue;
            }

            final boolean sorted = sort.isSorted() && sort.column() == column.storeColumn();
            gc.setFill(sorted ? theme.focusRing : theme.headerText);

            String title = column.title();
            double textWidth = glyphs.widthOf(title);
            double arrowWidth = sorted ? glyphs.advanceOf('^') + 2 : 0;

            double tx = column.rightAligned()
                    ? right - theme.cellPadding - textWidth - arrowWidth
                    : x + theme.cellPadding;
            gc.fillText(title, PixelSnap.snap(tx, scale), baseline);

            if (sorted) {
                // A caret rather than a glyph from an unknown font: it is always available and
                // always the same width.
                gc.fillText(sort.descending() ? "v" : "^",
                        PixelSnap.snap(tx + textWidth + 2, scale), baseline);
            }
            x = right;
        }

        gc.setStroke(theme.headerRule);
        gc.setLineWidth(PixelSnap.hairlineWidth(scale));
        final double ruleY = PixelSnap.snapHairline(h, scale);
        gc.strokeLine(0, ruleY, w, ruleY);
    }

    // -------------------------------------------------------------- overlay

    /**
     * Selection, hover and focus, on their own canvas.
     *
     * <p>Separate because pointer movement must not cost a body repaint: hover changes at whatever
     * rate the mouse moves, and repainting a screenful of formatted text for it would spend the
     * whole frame budget on a highlight.
     */
    public void paintOverlay(GraphicsContext gc, ViewSnapshot view, Viewport viewport,
                             int selectedSlot, int hoverRow, boolean focused, double scale) {
        gc.clearRect(0, 0, viewport.width(), viewport.height());
        gc.save();
        gc.beginPath();
        gc.rect(0, viewport.headerHeight(), viewport.width(), viewport.bodyHeight());
        gc.clip();

        final int first = viewport.firstVisibleRow();
        final int limit = Math.min(viewport.visibleRowLimit(), view.count());
        final double rowH = viewport.rowHeight();

        if (hoverRow >= first && hoverRow < limit && view.slotAt(hoverRow) != selectedSlot) {
            gc.setFill(theme.hover);
            gc.fillRect(0, PixelSnap.snap(viewport.rowTop(hoverRow), scale), viewport.width(), rowH);
        }

        final int selectedRow = selectedSlot < 0 ? -1 : view.positionOf(selectedSlot);
        if (selectedRow >= first && selectedRow < limit) {
            final double y = PixelSnap.snap(viewport.rowTop(selectedRow), scale);
            gc.setFill(theme.selection);
            gc.fillRect(0, y, viewport.width(), rowH);

            if (focused) {
                gc.setStroke(theme.focusRing);
                gc.setLineWidth(PixelSnap.hairlineWidth(scale));
                final double top = PixelSnap.snapHairline(y, scale);
                final double bottom = PixelSnap.snapHairline(y + rowH, scale);
                gc.strokeLine(0, top, viewport.width(), top);
                gc.strokeLine(0, bottom, viewport.width(), bottom);
            }
        }
        gc.restore();
    }
}
