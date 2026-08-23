package io.github.tickgrid.demo.binance;

import io.github.tickgrid.render.FixedFormat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parses payloads captured verbatim from the live stream.
 *
 * <p>The first version of this feed looked for {@code "d":} where the wrapper key is {@code "data"},
 * so every message parsed into nothing and the grid came up empty with no error anywhere. A scanner
 * that returns "no fields found" rather than throwing is exactly the kind of code that needs a test
 * holding a real message, because it fails silently and it fails completely.
 */
class BinanceParseTest {

    /** Captured from wss://data-stream.binance.vision/stream?streams=!miniTicker@arr */
    private static final String BOOK_TICKER = """
            {"stream":"linkusdt@bookTicker","data":{"u":14738549974,"s":"LINKUSDT",\
            "b":"11.55400000","B":"305.20000000","a":"11.55500000","A":"1.83000000"}}""";

    private static final String MINI_TICKER = """
            {"stream":"!miniTicker@arr","data":[\
            {"e":"24hrMiniTicker","E":1787509209008,"s":"REDUSDC","c":"0.11190000","o":"0.11250000",\
            "h":"0.12050000","l":"0.10950000","v":"743549.00000000","q":"84346.56461000"},\
            {"e":"24hrMiniTicker","E":1787509209013,"s":"BTCUSDT","c":"77330.81000000",\
            "o":"76000.00000000","h":"78000.00000000","l":"75500.00000000","v":"1234.50000000",\
            "q":"95000000.12345678"}]}""";

    private static final String SUBSCRIBE_ACK = "{\"result\":null,\"id\":1}";

    /** Captures whatever the feed decodes, so the test asserts on the listener's view. */
    static final class Capture implements BinanceFeed.Listener {
        record Book(String symbol, long bid, long bidQty, long ask, long askQty) { }
        record Mini(String symbol, long last, long open, long high, long low, long quoteVol) { }

        final List<Book> books = new ArrayList<>();
        final List<Mini> minis = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        @Override public void onBookTicker(CharSequence s, long b, long bq, long a, long aq) {
            books.add(new Book(s.toString(), b, bq, a, aq));
        }
        @Override public void onMiniTicker(CharSequence s, long c, long o, long h, long l, long q) {
            minis.add(new Mini(s.toString(), c, o, h, l, q));
        }
        @Override public void onStatus(String message) {
            statuses.add(message);
        }
    }

    /** No quote filter: every symbol in the payload reaches the listener. */
    private static Capture feedAll(String... payloads) {
        return feed("", payloads);
    }

    private static Capture feed(String quoteAsset, String... payloads) {
        Capture capture = new Capture();
        // topSymbols 0 keeps the feed from trying to open a socket to subscribe.
        BinanceFeed feed = new BinanceFeed(capture, 0, quoteAsset, 4);
        for (String p : payloads) {
            feed.acceptForTesting(p);
        }
        return capture;
    }

    @Test
    void readsABookTicker() {
        Capture c = feedAll(BOOK_TICKER);
        assertEquals(1, c.books.size(), "the bookTicker payload produced no callback");

        Capture.Book b = c.books.get(0);
        assertEquals("LINKUSDT", b.symbol());
        assertEquals(FixedFormat.parseScaled("11.55400000", 8), b.bid());
        assertEquals(FixedFormat.parseScaled("11.55500000", 8), b.ask());
        assertEquals(1_155_400_000L, b.bid());
        assertEquals(30_520_000_000L, b.bidQty());
        assertEquals(183_000_000L, b.askQty());
    }

    @Test
    void readsEveryObjectInAMiniTickerArray() {
        Capture c = feedAll(MINI_TICKER);
        assertEquals(2, c.minis.size(), "the array walk stopped early");

        Capture.Mini red = c.minis.get(0);
        assertEquals("REDUSDC", red.symbol());
        assertEquals(11_190_000L, red.last());
        assertEquals(11_250_000L, red.open());
        assertEquals(12_050_000L, red.high());
        assertEquals(10_950_000L, red.low());

        Capture.Mini btc = c.minis.get(1);
        assertEquals("BTCUSDT", btc.symbol());
        assertEquals(7_733_081_000_000L, btc.last());
        assertEquals(9_500_000_012_345_678L, btc.quoteVol());
    }

    @Test
    void doesNotConfuseTheTwoStreams() {
        Capture c = feedAll(BOOK_TICKER, MINI_TICKER);
        assertEquals(1, c.books.size());
        assertEquals(2, c.minis.size());
    }

    @Test
    void ignoresTheSubscribeAcknowledgement() {
        Capture c = feedAll(SUBSCRIBE_ACK);
        assertTrue(c.books.isEmpty());
        assertTrue(c.minis.isEmpty());
        assertTrue(c.statuses.isEmpty(), "an ack is not an error: " + c.statuses);
    }

    @Test
    void survivesTruncatedAndUnexpectedPayloads() {
        Capture c = feedAll("{}", "", "{\"stream\":\"x@bookTicker\",\"data\":{}}", "not json at all");
        assertTrue(c.books.isEmpty(), "a payload with no fields must not invent a row");
        assertTrue(c.minis.isEmpty());
    }

    /** The single-character lookup must not match a longer key that happens to start with it. */
    @Test
    void singleCharacterKeysDoNotMatchLongerOnes() {
        String s = "{\"data\":1,\"d\":2}";
        assertEquals(s.indexOf("\"d\":") + 4, Json.field(s, 0, s.length(), 'd'));
        assertEquals(s.indexOf("\"data\":") + 7, Json.member(s, 0, s.length(), "data"));
    }

    /**
     * 24-hour "quote volume" is denominated in the pair's quote asset, so ranking an IDR pair
     * against a USDT pair compares rupiah with dollars — and puts a handful of rupiah pairs at the
     * top of a volume sort purely because the numbers are bigger. Filtering to one quote asset is
     * what makes the column comparable down the page.
     */
    @Test
    void filtersToOneQuoteAsset() {
        Capture usdt = feed("USDT", MINI_TICKER);
        assertEquals(1, usdt.minis.size(), "REDUSDC should have been filtered out");
        assertEquals("BTCUSDT", usdt.minis.get(0).symbol());

        Capture usdc = feed("USDC", MINI_TICKER);
        assertEquals(1, usdc.minis.size());
        assertEquals("REDUSDC", usdc.minis.get(0).symbol());

        assertEquals(2, feedAll(MINI_TICKER).minis.size(), "an empty filter admits everything");
    }

    @Test
    void theFilterAppliesToBookTickersToo() {
        assertEquals(1, feed("USDT", BOOK_TICKER).books.size());
        assertEquals(0, feed("USDC", BOOK_TICKER).books.size());
    }

    /** A bare quote asset is not a pair: "USDT" must not match the "USDT" filter. */
    @Test
    void theQuoteAssetItselfIsNotAPair() {
        String payload = "{\"stream\":\"!miniTicker@arr\",\"data\":["
                + "{\"e\":\"24hrMiniTicker\",\"s\":\"USDT\",\"c\":\"1.0\",\"o\":\"1.0\","
                + "\"h\":\"1.0\",\"l\":\"1.0\",\"q\":\"1.0\"}]}";
        assertEquals(0, feed("USDT", payload).minis.size());
    }

    @Test
    void memberFindsTheWrapperKeyTheFeedDependsOn() {
        int at = Json.member(BOOK_TICKER, 0, BOOK_TICKER.length(), "data");
        assertTrue(at > 0, "the wrapper key was not found at all");
        assertEquals('{', BOOK_TICKER.charAt(at));
    }
}
