package io.github.tickgrid.render;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ParseScaledTest {

    @Test
    void parsesVenueStyleDecimals() {
        assertEquals(7_733_081_000_000L, FixedFormat.parseScaled("77330.81000000", 8));
        assertEquals(2_535_190_000L, FixedFormat.parseScaled("25.35190000", 8));
        assertEquals(1_119_0000L, FixedFormat.parseScaled("0.11190000", 8));
    }

    @Test
    void padsWhenTheTextHasFewerDecimalsThanTheColumn() {
        assertEquals(15_000, FixedFormat.parseScaled("1.5", 4));
        assertEquals(10_000, FixedFormat.parseScaled("1", 4));
        assertEquals(10_000, FixedFormat.parseScaled("1.", 4));
        assertEquals(0, FixedFormat.parseScaled("0", 4));
    }

    @Test
    void truncatesPrecisionTheColumnDoesNotKeep() {
        // A venue sending more decimals than the column stores means exactly this: the extra
        // digits are not representable, and truncation is what "we keep 2 decimals" means.
        assertEquals(123, FixedFormat.parseScaled("1.239999", 2));
        assertEquals(-123, FixedFormat.parseScaled("-1.239999", 2));
        assertEquals(1, FixedFormat.parseScaled("0.019", 2));
    }

    @Test
    void handlesNegativesAndZero() {
        assertEquals(-2_500, FixedFormat.parseScaled("-0.25", 4));
        assertEquals(0, FixedFormat.parseScaled("0.00000000", 8));
        assertEquals(0, FixedFormat.parseScaled("-0.0", 4));
    }

    @Test
    void parsesASliceWithoutCopying() {
        String payload = "{\"b\":\"11.55400000\",\"a\":\"11.55500000\"}";
        int from = payload.indexOf("\"b\":\"") + 5;
        int to = payload.indexOf('"', from);
        assertEquals(1_155_400_000L, FixedFormat.parseScaled(payload, from, to, 8));
    }

    @Test
    void rejectsThingsThatAreNotNumbers() {
        assertThrows(NumberFormatException.class, () -> FixedFormat.parseScaled("", 2));
        assertThrows(NumberFormatException.class, () -> FixedFormat.parseScaled("abc", 2));
        assertThrows(NumberFormatException.class, () -> FixedFormat.parseScaled("1.2.3", 2));
        assertThrows(NumberFormatException.class, () -> FixedFormat.parseScaled("-", 2));
        assertThrows(NumberFormatException.class, () -> FixedFormat.parseScaled("1e5", 2));
    }

    /**
     * The reason this exists rather than {@code (long)(Double.parseDouble(s) * 1e8)}: a decimal
     * string names a value exactly and binary floating point does not, so the naive conversion
     * lands a cent low on prices a venue sends every second.
     *
     * <p>{@code 0.29} is the smallest case: as a double it is fractionally under 0.29, so
     * multiplying by 100 gives 28.999999999999996 and truncating gives 28. A blotter quoting 0.28
     * for a 0.29 bid is not a rounding nicety.
     */
    @Test
    void isExactWhereDoubleIsNot() {
        String[] brokenByDouble = {"0.29", "0.57", "0.58", "1.13", "1.14", "1.15"};
        for (String text : brokenByDouble) {
            long exact = new BigDecimal(text).movePointRight(2).longValueExact();
            assertEquals(exact, FixedFormat.parseScaled(text, 2), text);
            assertNotEquals(exact, (long) (Double.parseDouble(text) * 100),
                    text + " was supposed to be a case double gets wrong");
        }

        String[] venuePrices = {"77330.81000000", "0.00000001", "123456.78900000"};
        for (String text : venuePrices) {
            assertEquals(new BigDecimal(text).movePointRight(8).longValueExact(),
                    FixedFormat.parseScaled(text, 8), text);
        }
    }

    @Test
    void agreesWithBigDecimalOnRandomInput() {
        Random rnd = new Random(31337);
        for (int i = 0; i < 20_000; i++) {
            int scale = rnd.nextInt(9);
            long units = rnd.nextLong() % 1_000_000_000L;
            int decimals = rnd.nextInt(9);
            BigDecimal value = new BigDecimal(units).movePointLeft(decimals);
            String text = value.toPlainString();
            long expected = value.movePointRight(scale)
                    .setScale(0, java.math.RoundingMode.DOWN).longValueExact();
            assertEquals(expected, FixedFormat.parseScaled(text, scale),
                    "text=" + text + " scale=" + scale);
        }
    }

    // -------------------------------------------------------------- rescale

    @Test
    void rescaleRoundsHalfAwayFromZero() {
        assertEquals(1_234_568, FixedFormat.rescale(123_456_789L, 8, 6) / 1);
        assertEquals(115_540, FixedFormat.rescale(1_155_400_000L, 8, 4));
        assertEquals(2, FixedFormat.rescale(150, 2, 0));
        assertEquals(-2, FixedFormat.rescale(-150, 2, 0), "negatives round away from zero too");
        assertEquals(1, FixedFormat.rescale(149, 2, 0));
    }

    @Test
    void rescaleUpwardsIsExact() {
        assertEquals(150_000, FixedFormat.rescale(15, 1, 5));
        assertEquals(15, FixedFormat.rescale(15, 3, 3));
    }

    @Test
    void rescaleAndParseRoundTripThroughTheFormatter() {
        char[] buf = new char[FixedFormat.MAX_CHARS];
        long stored = FixedFormat.parseScaled("77330.81000000", 8);
        long shown = FixedFormat.rescale(stored, 8, 2);
        int start = FixedFormat.fixed(buf, shown, 2);
        assertEquals("77330.81", new String(buf, start, buf.length - start));
    }
}
