package io.github.tickgrid.render;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FixedFormatTest {

    private final char[] buf = new char[FixedFormat.MAX_CHARS];

    private String fixed(long value, int decimals) {
        int start = FixedFormat.fixed(buf, value, decimals);
        return new String(buf, start, buf.length - start);
    }

    private String grouped(long value) {
        int start = FixedFormat.grouped(buf, value);
        return new String(buf, start, buf.length - start);
    }

    private String decimal(double value, int decimals) {
        int start = FixedFormat.decimal(buf, value, decimals);
        return new String(buf, start, buf.length - start);
    }

    // ----------------------------------------------------------------- fixed

    @Test
    void formatsScaledIntegersAsDecimals() {
        assertEquals("190.25", fixed(19_025, 2));
        assertEquals("1.0000", fixed(10_000, 4));
        assertEquals("0.01", fixed(1, 2));
        assertEquals("0.00", fixed(0, 2));
        assertEquals("12345", fixed(12_345, 0));
    }

    @Test
    void keepsLeadingZerosInTheFractionalPart() {
        assertEquals("0.05", fixed(5, 2), "a naive divide-and-append writes 0.5 here");
        assertEquals("1.005", fixed(1005, 3));
        assertEquals("0.0001", fixed(1, 4));
    }

    @Test
    void formatsNegatives() {
        assertEquals("-190.25", fixed(-19_025, 2));
        assertEquals("-0.01", fixed(-1, 2));
        assertEquals("-1", fixed(-1, 0));
    }

    @Test
    void handlesLongExtremes() {
        assertEquals("9223372036854775807", fixed(Long.MAX_VALUE, 0));
        assertEquals("-9223372036854775808", fixed(Long.MIN_VALUE, 0),
                "MIN_VALUE has no positive counterpart and must not overflow into gibberish");
        assertEquals("-92233720368547758.08", fixed(Long.MIN_VALUE, 2));
    }

    @Test
    void agreesWithBigDecimalOnRandomValues() {
        Random rnd = new Random(4242);
        for (int i = 0; i < 20_000; i++) {
            long value = rnd.nextLong() / (1 + rnd.nextInt(1_000_000));
            int decimals = rnd.nextInt(7);
            String expected = new java.math.BigDecimal(java.math.BigInteger.valueOf(value), decimals)
                    .toPlainString();
            assertEquals(expected, fixed(value, decimals),
                    "value=" + value + " decimals=" + decimals);
        }
    }

    // --------------------------------------------------------------- grouped

    @Test
    void groupsThousands() {
        assertEquals("0", grouped(0));
        assertEquals("999", grouped(999));
        assertEquals("1,000", grouped(1_000));
        assertEquals("12,345,678", grouped(12_345_678));
        assertEquals("-1,234", grouped(-1_234));
    }

    @Test
    void groupingBoundariesAreExact() {
        assertEquals("100", grouped(100));
        assertEquals("1,001", grouped(1_001));
        assertEquals("10,000", grouped(10_000));
        assertEquals("100,000", grouped(100_000));
    }

    @Test
    void agreesWithStringFormatOnRandomGrouping() {
        Random rnd = new Random(99);
        for (int i = 0; i < 20_000; i++) {
            long value = rnd.nextLong() / (1 + rnd.nextInt(1_000_000));
            assertEquals(String.format(Locale.ROOT, "%,d", value), grouped(value), "value=" + value);
        }
    }

    @Test
    void groupsLongExtremes() {
        assertEquals(String.format(Locale.ROOT, "%,d", Long.MAX_VALUE), grouped(Long.MAX_VALUE));
        assertEquals(String.format(Locale.ROOT, "%,d", Long.MIN_VALUE), grouped(Long.MIN_VALUE));
    }

    // --------------------------------------------------------------- decimal

    @Test
    void formatsDoublesToFixedDecimals() {
        assertEquals("1.25", decimal(1.25, 2));
        assertEquals("-1.25", decimal(-1.25, 2));
        assertEquals("0.00", decimal(0.0, 2));
        assertEquals("3", decimal(3.4, 0));
    }

    @Test
    void roundsHalfAwayFromZero() {
        assertEquals("1.3", decimal(1.25, 1));
        assertEquals("-1.3", decimal(-1.25, 1),
                "a negative must round away from zero too, not toward it");
    }

    @Test
    void rendersNonFiniteValuesWithoutThrowing() {
        assertEquals("NaN", decimal(Double.NaN, 2));
        assertEquals("∞", decimal(Double.POSITIVE_INFINITY, 2));
        assertEquals("-∞", decimal(Double.NEGATIVE_INFINITY, 2));
        assertEquals("-", decimal(1e30, 2), "out of range must degrade, not corrupt the buffer");
    }

    // -------------------------------------------------------------- buffers

    @Test
    void writesBackwardsAndNeverUnderflowsTheBuffer() {
        // The widest output the formatter can produce: MIN_VALUE, grouped, with a sign.
        int start = FixedFormat.grouped(buf, Long.MIN_VALUE);
        assertTrue(start >= 0, "grouped MIN_VALUE overran the buffer");
        assertTrue(buf.length - start <= FixedFormat.MAX_CHARS);
    }

    @Test
    void reusingOneBufferDoesNotLeakBetweenCalls() {
        assertEquals("12345.6789", fixed(123_456_789, 4));
        assertEquals("1.00", fixed(100, 2), "stale characters from the longer value must not show");
    }
}
