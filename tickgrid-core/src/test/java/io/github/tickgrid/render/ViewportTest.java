package io.github.tickgrid.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewportTest {

    private Viewport v;

    @BeforeEach
    void setUp() {
        v = new Viewport();
        v.setRowHeight(20);
        v.setHeaderHeight(30);
        v.setSize(500, 230);          // 200px of body = exactly 10 rows
        v.setRowCount(1000);
    }

    @Test
    void bodyHeightExcludesTheHeader() {
        assertEquals(200, v.bodyHeight(), 1e-9);
    }

    @Test
    void visibleRangeAtRestIsTheFirstScreenful() {
        assertEquals(0, v.firstVisibleRow());
        assertEquals(10, v.visibleRowLimit());
        assertEquals(10, v.visibleRowCount());
    }

    @Test
    void aPartiallyScrolledRowIsStillVisible() {
        v.setScrollY(10);              // half of row 0 is off the top
        assertEquals(0, v.firstVisibleRow(), "the half-shown row must still be painted");
        assertEquals(11, v.visibleRowLimit(), "and so must the half-shown row at the bottom");
    }

    @Test
    void visibleRangeNeverExceedsTheRowCount() {
        v.setRowCount(4);
        assertEquals(0, v.firstVisibleRow());
        assertEquals(4, v.visibleRowLimit());
    }

    @Test
    void emptyViewHasNoVisibleRows() {
        v.setRowCount(0);
        assertEquals(0, v.visibleRowCount());
        assertEquals(0, v.maxScrollY());
        assertEquals(-1, v.rowAt(100));
    }

    // ---------------------------------------------------------------- scroll

    @Test
    void scrollIsClampedToTheContent() {
        assertEquals(1000 * 20 - 200, v.maxScrollY(), 1e-9);

        v.setScrollY(-50);
        assertEquals(0, v.scrollY(), 1e-9);

        v.setScrollY(1e9);
        assertEquals(v.maxScrollY(), v.scrollY(), 1e-9);
    }

    @Test
    void scrollReportsWhetherItActuallyMoved() {
        assertFalse(v.setScrollY(0), "already at the top");
        assertTrue(v.setScrollY(40));
        assertFalse(v.setScrollY(40), "no movement means no repaint");
    }

    @Test
    void shrinkingTheContentPullsTheViewportBackIntoRange() {
        v.setScrollY(v.maxScrollY());
        double wasAt = v.scrollY();
        assertTrue(wasAt > 0);

        v.setRowCount(5);              // a filter just removed almost everything
        assertEquals(0, v.maxScrollY(), 1e-9);
        assertEquals(0, v.scrollY(), 1e-9,
                "the viewport must not be left scrolled past the end of a shrunken view");
    }

    @Test
    void resizingRecomputesTheScrollRange() {
        v.setScrollY(v.maxScrollY());
        v.setSize(500, 20_030);        // taller than the whole content
        assertEquals(0, v.maxScrollY(), 1e-9);
        assertEquals(0, v.scrollY(), 1e-9);
    }

    // ------------------------------------------------------- ensure visible

    @Test
    void ensureVisibleScrollsTheMinimumDistance() {
        // Row 12 is just below the fold; the viewport should nudge, not recentre.
        assertTrue(v.ensureRowVisible(12));
        assertEquals(20 * 13 - 200, v.scrollY(), 1e-9);
        assertEquals(12, v.visibleRowLimit() - 1);
    }

    @Test
    void ensureVisibleScrollsUpForARowAboveTheFold() {
        v.setScrollY(400);             // showing rows 20..29
        assertTrue(v.ensureRowVisible(15));
        assertEquals(300, v.scrollY(), 1e-9, "the row should land at the top, not the middle");
    }

    @Test
    void ensureVisibleDoesNothingForAVisibleRow() {
        assertFalse(v.ensureRowVisible(5));
        assertEquals(0, v.scrollY(), 1e-9);
    }

    @Test
    void ensureVisibleIgnoresRowsOutsideTheView() {
        assertFalse(v.ensureRowVisible(-1));
        assertFalse(v.ensureRowVisible(1000));
    }

    // ------------------------------------------------------------ hit testing

    /**
     * The invariant that matters: for every point in the body, the row reported by hit testing is
     * the row whose painted band covers that point. Stated over the body rather than over rows,
     * because at a fractional scroll the topmost row's own top edge sits behind the header.
     */
    @Test
    void rowAtAgreesWithTheBandThatWouldBePainted() {
        v.setScrollY(37);
        for (double y = v.headerHeight(); y < v.height(); y += 0.5) {
            int row = v.rowAt(y);
            assertTrue(row >= 0, "no row reported at y=" + y);
            double top = v.rowTop(row);
            assertTrue(y >= top && y < top + v.rowHeight(),
                    "y=" + y + " reported row " + row + " whose band is "
                            + top + ".." + (top + v.rowHeight()));
        }
    }

    @Test
    void aRowScrolledUnderTheHeaderIsStillPaintedButNotClickable() {
        v.setScrollY(37);                   // row 1's top edge sits at y=13, behind the header
        assertEquals(1, v.firstVisibleRow(), "it must still be painted");
        assertEquals(13, v.rowTop(1), 1e-9);
        assertEquals(1, v.rowAt(31), "its visible sliver is still clickable");
        assertEquals(-1, v.rowAt(20), "but a click in the header is not a click on it");
    }

    @Test
    void headerIsNotARow() {
        assertTrue(v.isInHeader(0));
        assertTrue(v.isInHeader(29.9));
        assertFalse(v.isInHeader(30));
        assertEquals(-1, v.rowAt(15), "a click in the header must not select a row");
    }

    @Test
    void hitTestBelowTheLastRowSelectsNothing() {
        v.setRowCount(3);
        assertEquals(2, v.rowAt(30 + 2 * 20 + 1));
        assertEquals(-1, v.rowAt(30 + 3 * 20 + 1), "empty space below the rows is not row 3");
    }

    @Test
    void pageSizeIsWholeRowsOnly() {
        assertEquals(10, v.rowsPerPage());
        v.setSize(500, 30 + 195);       // 9.75 rows
        assertEquals(9, v.rowsPerPage(), "a partly visible row is not a page step");
    }

    @Test
    void pageSizeIsNeverZero() {
        v.setSize(500, 31);             // barely any body at all
        assertTrue(v.rowsPerPage() >= 1, "page down must always advance");
    }

    // ------------------------------------------------------------ horizontal

    @Test
    void horizontalScrollIsClampedToContentWidth() {
        v.setContentWidth(1200);
        assertEquals(700, v.maxScrollX(), 1e-9);
        v.setScrollX(5000);
        assertEquals(700, v.scrollX(), 1e-9);
    }

    @Test
    void contentNarrowerThanTheViewportDoesNotScroll() {
        v.setContentWidth(200);
        assertEquals(0, v.maxScrollX(), 1e-9);
        assertFalse(v.setScrollX(50));
    }
}
