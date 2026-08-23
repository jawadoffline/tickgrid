package io.github.tickgrid.render;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Per-character advance widths, measured once for a font.
 *
 * <p>The design says to right-align numerics "by computed width, never by measuring the formatted
 * string each frame", and this is what makes that possible: summing a table of ASCII advances gives
 * the exact width of any string this renderer can emit, with no {@code Text} node and no layout
 * pass per cell.
 *
 * <p>{@link #hasTabularDigits()} reports whether all ten digits share an advance. It is not required
 * for alignment to be exact — summing per-character advances is correct for a proportional font too
 * — but without it the digits <i>inside</i> a right-aligned cell shift horizontally as the value
 * changes, which on a ticking column reads as jitter. JavaFX has no OpenType feature control, so
 * {@code tnum} cannot be requested; the only remedy is choosing a font that has tabular figures.
 */
public final class GlyphWidths {

    private static final int TABLE_SIZE = 128;

    private final double[] advance = new double[TABLE_SIZE];
    private final double fallback;
    private final boolean tabularDigits;
    private final double lineHeight;
    private final double ascent;

    public GlyphWidths(Font font) {
        final Text probe = new Text();
        probe.setFont(font);

        for (int c = 32; c < TABLE_SIZE; c++) {
            probe.setText(String.valueOf((char) c));
            advance[c] = probe.getLayoutBounds().getWidth();
        }
        probe.setText("0");
        this.fallback = probe.getLayoutBounds().getWidth();

        boolean tabular = true;
        for (char d = '1'; d <= '9'; d++) {
            if (Math.abs(advance[d] - advance['0']) > 0.01) {
                tabular = false;
                break;
            }
        }
        this.tabularDigits = tabular;

        probe.setText("Xg");
        this.lineHeight = probe.getLayoutBounds().getHeight();
        this.ascent = probe.getBaselineOffset();
    }

    public double advanceOf(char c) {
        return c < TABLE_SIZE ? advance[c] : fallback;
    }

    /** Width of {@code chars[start..end)}. */
    public double widthOf(char[] chars, int start, int end) {
        double w = 0;
        for (int i = start; i < end; i++) {
            w += advanceOf(chars[i]);
        }
        return w;
    }

    public double widthOf(String s) {
        double w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += advanceOf(s.charAt(i));
        }
        return w;
    }

    /** Width of the widest digit — enough to reserve space for an n-digit value. */
    public double digitWidth() {
        return advance['0'];
    }

    public boolean hasTabularDigits() {
        return tabularDigits;
    }

    public double lineHeight() {
        return lineHeight;
    }

    public double ascent() {
        return ascent;
    }
}
