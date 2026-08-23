package io.github.tickgrid.render;

/**
 * Fixed-decimal and grouped-integer formatting into a caller-owned {@code char[]}.
 *
 * <p>{@code String.format} costs 8-10x the frame time and 15x the garbage of this — measured, on a
 * 900-cell grid: 1.140 ms and 38 MB/s against 0.141 ms and 2.6 MB/s. It builds a {@code Formatter},
 * boxes its varargs, and runs a {@code StringBuilder} per cell per frame.
 *
 * <p>What this cannot avoid is the final {@code String}. {@code GraphicsContext} exposes only
 * {@code fillText(String, double, double)} — there is no {@code char[]} or {@code CharSequence}
 * overload — so one string per painted cell is unavoidable. Measured, that residual is 2.6 MB/s,
 * which is a young-gen collection every half-minute and not worth a glyph atlas to remove.
 *
 * <p>Digits are written backwards from the end of the buffer, which avoids a reversal pass and
 * means the caller reads {@code new String(buf, start, buf.length - start)}.
 */
public final class FixedFormat {

    /** Enough for {@code Long.MIN_VALUE} with grouping and a decimal point. */
    public static final int MAX_CHARS = 32;

    private FixedFormat() {
    }

    /**
     * Writes {@code value} scaled by {@code 10^decimals} as a fixed-point decimal.
     *
     * @return the index in {@code buf} where the text starts
     */
    public static int fixed(char[] buf, long value, int decimals) {
        int p = buf.length;
        final boolean negative = value < 0;
        // Negate into the positive domain via a long that can hold |MIN_VALUE|.
        long x = negative ? -value : value;
        if (value == Long.MIN_VALUE) {
            return fixedSlow(buf, decimals);
        }

        for (int i = 0; i < decimals; i++) {
            buf[--p] = (char) ('0' + (int) (x % 10));
            x /= 10;
        }
        if (decimals > 0) buf[--p] = '.';

        if (x == 0) {
            buf[--p] = '0';
        } else {
            while (x > 0) {
                buf[--p] = (char) ('0' + (int) (x % 10));
                x /= 10;
            }
        }
        if (negative) buf[--p] = '-';
        return p;
    }

    /** Writes {@code value} as an integer with thousands separators. */
    public static int grouped(char[] buf, long value) {
        int p = buf.length;
        final boolean negative = value < 0;
        long x = negative ? -value : value;
        if (value == Long.MIN_VALUE) {
            return groupedSlow(buf);
        }

        if (x == 0) {
            buf[--p] = '0';
        } else {
            int digits = 0;
            while (x > 0) {
                if (digits > 0 && digits % 3 == 0) buf[--p] = ',';
                buf[--p] = (char) ('0' + (int) (x % 10));
                x /= 10;
                digits++;
            }
        }
        if (negative) buf[--p] = '-';
        return p;
    }

    /** Writes a plain integer, no grouping. */
    public static int integer(char[] buf, long value) {
        return fixed(buf, value, 0);
    }

    /**
     * Writes a double with a fixed number of decimals, rounding half away from zero.
     *
     * <p>Prefer a {@code SCALED} column and {@link #fixed}: this exists for columns that genuinely
     * are floating point, and it inherits their rounding surprises.
     */
    public static int decimal(char[] buf, double value, int decimals) {
        if (Double.isNaN(value)) return literal(buf, "NaN");
        if (Double.isInfinite(value)) return literal(buf, value > 0 ? "∞" : "-∞");

        double scale = POW10[Math.min(decimals, POW10.length - 1)];
        double scaled = value * scale;
        if (scaled >= Long.MAX_VALUE || scaled <= Long.MIN_VALUE) {
            return literal(buf, "-");            // out of the range this formatter promises
        }
        long rounded = (long) (scaled + (value < 0 ? -0.5 : 0.5));
        return fixed(buf, rounded, decimals);
    }

    /** Writes a short literal, right-aligned in the buffer like the numeric paths. */
    public static int literal(char[] buf, String text) {
        int p = buf.length;
        for (int i = text.length() - 1; i >= 0; i--) {
            buf[--p] = text.charAt(i);
        }
        return p;
    }

    /**
     * Parses a decimal string into an integer scaled by {@code 10^scale}, without going through
     * {@code double}.
     *
     * <p>The inverse of {@link #fixed}, and the other half of taking §3.1's "prices stored as scaled
     * long" seriously. A feed that sends {@code "77330.81000000"} should not have its price routed
     * through a binary floating-point type on the way to an exact integer column — the round trip is
     * lossy for values a decimal string represents exactly, and the loss shows up as a price that
     * does not match the venue's.
     *
     * <p>Excess precision is truncated rather than rounded, which matches what a venue means when it
     * sends more decimals than the column keeps.
     *
     * @throws NumberFormatException if the text is not a decimal number
     */
    public static long parseScaled(CharSequence text, int from, int to, int scale) {
        if (from >= to) throw new NumberFormatException("empty");
        long value = 0;
        int digitsAfterPoint = -1;
        boolean negative = false;
        boolean any = false;

        for (int i = from; i < to; i++) {
            final char c = text.charAt(i);
            if (c == '-' && i == from) {
                negative = true;
            } else if (c == '.') {
                if (digitsAfterPoint >= 0) throw new NumberFormatException("two points");
                digitsAfterPoint = 0;
            } else if (c >= '0' && c <= '9') {
                any = true;
                if (digitsAfterPoint < scale) {
                    value = value * 10 + (c - '0');
                    if (digitsAfterPoint >= 0) digitsAfterPoint++;
                } else if (digitsAfterPoint >= 0) {
                    digitsAfterPoint++;          // beyond the column's precision; drop it
                }
            } else {
                throw new NumberFormatException("bad character '" + c + "' at " + i);
            }
        }
        if (!any) throw new NumberFormatException("no digits");

        // Pad out a value that had fewer decimals than the column keeps: "1.5" at scale 4 is 15000.
        int have = digitsAfterPoint < 0 ? 0 : Math.min(digitsAfterPoint, scale);
        for (int i = have; i < scale; i++) value *= 10;
        return negative ? -value : value;
    }

    public static long parseScaled(CharSequence text, int scale) {
        return parseScaled(text, 0, text.length(), scale);
    }

    /**
     * Converts a scaled integer from one scale to another, rounding half away from zero.
     *
     * <p>Lets a column be stored at the precision the venue sends and displayed at the precision a
     * person can read: eight decimals of BTC is exact and unreadable.
     */
    public static long rescale(long value, int fromScale, int toScale) {
        if (fromScale == toScale) return value;
        if (fromScale < toScale) {
            long out = value;
            for (int i = fromScale; i < toScale; i++) out *= 10;
            return out;
        }
        long divisor = 1;
        for (int i = toScale; i < fromScale; i++) divisor *= 10;
        long half = divisor / 2;
        return value >= 0 ? (value + half) / divisor : (value - half) / divisor;
    }

    private static final double[] POW10 = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9,
            1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18
    };

    /** {@code Long.MIN_VALUE} has no positive counterpart; it is rare enough to take the slow path. */
    private static int fixedSlow(char[] buf, int decimals) {
        String s = Long.toString(Long.MIN_VALUE);
        if (decimals > 0) {
            int cut = s.length() - decimals;
            s = s.substring(0, cut) + "." + s.substring(cut);
        }
        return literal(buf, s);
    }

    private static int groupedSlow(char[] buf) {
        String digits = Long.toString(Long.MIN_VALUE).substring(1);
        StringBuilder sb = new StringBuilder("-");
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) sb.append(',');
            sb.append(digits.charAt(i));
        }
        return literal(buf, sb.toString());
    }
}
