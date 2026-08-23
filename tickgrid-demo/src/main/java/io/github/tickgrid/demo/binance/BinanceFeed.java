package io.github.tickgrid.demo.binance;

import io.github.tickgrid.render.FixedFormat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A live Binance market-data feed, over the JDK's own WebSocket client.
 *
 * <p>Two streams on one connection. {@code !miniTicker@arr} arrives once a second carrying every
 * symbol on the venue, which gives the grid its universe and its 24-hour figures without a REST
 * bootstrap. From that the feed ranks symbols by quote volume and sends a {@code SUBSCRIBE} for the
 * busiest ones' {@code @bookTicker} streams, which is where the rate comes from: ten symbols alone
 * push around 260 messages a second, so a few hundred is a genuine load.
 *
 * <p>The universe discovering itself is the point. The alternative is pulling a two-megabyte
 * {@code exchangeInfo} document and parsing it, to learn something the stream is about to say
 * anyway.
 *
 * <h2>Public data only</h2>
 * {@code data-stream.binance.vision} is Binance's market-data mirror. No key, no account, nothing
 * to authenticate — this reads the same public prices a browser would.
 */
public final class BinanceFeed implements AutoCloseable {

    /** Price and quantity scale. Binance quotes eight decimals for everything. */
    public static final int SCALE = 8;

    private static final String HOST = "wss://data-stream.binance.vision";
    private static final int MAX_SUBSCRIBE_BATCH = 180;      // the venue's limit is 200 per message
    /** Mini-tickers between top-ups. Each carries only the symbols that traded that second. */
    private static final int TOP_UP_EVERY = 20;

    /** One update, handed to the application. Reused; copy anything you keep. */
    public interface Listener {
        /** Best bid and offer changed. Prices and sizes are scaled by {@code 10^SCALE}. */
        void onBookTicker(CharSequence symbol, long bid, long bidQty, long ask, long askQty);

        /** The once-a-second summary for one symbol. */
        void onMiniTicker(CharSequence symbol, long last, long open, long high, long low,
                          long quoteVolume);

        default void onStatus(String message) {
            System.out.println("[binance] " + message);
        }
    }

    private final Listener listener;
    private final int topSymbols;
    private final String quoteAsset;
    private final int warmupTickers;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicLong bookMessages = new AtomicLong();
    private final AtomicLong miniMessages = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    /** Every symbol seen so far, with its latest quote volume. */
    private final java.util.Map<String, Long> universe = new java.util.HashMap<>(4096);
    /** What we have already asked for, so a top-up does not re-subscribe the same streams. */
    private final java.util.Set<String> streaming = new java.util.HashSet<>(1024);
    private long nextTopUpAt = Long.MAX_VALUE;

    /**
     * @param quoteAsset    only symbols quoted in this asset are reported, e.g. {@code "USDT"}.
     *                      Empty admits everything, which sorts badly: 24-hour "quote volume" is
     *                      denominated in the quote asset, so ranking IDR pairs against USDT pairs
     *                      compares rupiah with dollars and puts the wrong rows on top.
     * @param warmupTickers how many mini-ticker messages to accumulate before choosing what to
     *                      subscribe to. Each message carries only the symbols that traded in that
     *                      second -- roughly a fifth of the venue -- so ranking on the first one
     *                      picks the busiest of an arbitrary slice rather than the busiest overall.
     */
    public BinanceFeed(Listener listener, int topSymbols, String quoteAsset, int warmupTickers) {
        this.listener = listener;
        this.topSymbols = topSymbols;
        this.quoteAsset = quoteAsset == null ? "" : quoteAsset;
        this.warmupTickers = Math.max(1, warmupTickers);
    }

    public BinanceFeed(Listener listener, int topSymbols) {
        this(listener, topSymbols, "USDT", 4);
    }

    public long bookTickerCount() { return bookMessages.get(); }
    public long miniTickerCount() { return miniMessages.get(); }
    public long reconnectCount()  { return reconnects.get(); }
    public boolean isSubscribed() { return subscribed.get(); }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        connect(0);
    }

    private void connect(int attempt) {
        if (!running.get()) return;
        final String url = HOST + "/stream?streams=!miniTicker@arr";
        listener.onStatus(attempt == 0 ? "connecting to " + url
                                       : "reconnecting (attempt " + attempt + ")");

        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), new Handler())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        listener.onStatus("connect failed: " + rootCause(error));
                        scheduleReconnect(attempt + 1);
                    } else {
                        socket.set(ws);
                        subscribed.set(false);
                        listener.onStatus("connected");
                    }
                });
    }

    /**
     * Backs off up to half a minute. A demo that hammers a public endpoint after every failure is
     * the kind of thing that gets an IP range blocked for everyone.
     */
    private void scheduleReconnect(int attempt) {
        if (!running.get()) return;
        reconnects.incrementAndGet();
        final long delay = Math.min(30_000, 1_000L << Math.min(attempt, 5));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            connect(attempt);
        });
    }

    @Override
    public void close() {
        running.set(false);
        final WebSocket ws = socket.getAndSet(null);
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            ws.abort();
        }
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    // ------------------------------------------------------------- the socket

    private final class Handler implements WebSocket.Listener {
        /** Text frames can be split; a payload is only complete when {@code last} is set. */
        private final StringBuilder buffer = new StringBuilder(64 * 1024);

        @Override public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                try {
                    dispatch(buffer);
                } catch (RuntimeException e) {
                    listener.onStatus("parse error, message dropped: " + e);
                }
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            listener.onStatus("closed: " + status + " " + reason);
            scheduleReconnect(1);
            return null;
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            listener.onStatus("socket error: " + rootCause(error));
            scheduleReconnect(1);
        }
    }

    // ------------------------------------------------------------- dispatch

    /**
     * Feeds one payload through the parser without a socket.
     *
     * <p>Package-private so the parse can be tested against payloads captured from the live stream.
     * The alternative is testing the decoding only by running it against the venue, which is how a
     * scanner that silently matches nothing reaches a screenshot.
     */
    void acceptForTesting(CharSequence payload) {
        dispatch(payload);
    }

    private void dispatch(CharSequence payload) {
        final int end = payload.length();
        if (Json.contains(payload, 0, Math.min(64, end), "\"result\"")) {
            return;                                  // the ack for our SUBSCRIBE
        }
        // "data", not "d" -- the single-character lookup that reads the inner fields does not
        // match the wrapper's key, and getting that wrong parses every message into nothing.
        final int data = Json.member(payload, 0, end, "data");
        if (data < 0) return;

        if (Json.contains(payload, 0, Math.min(48, end), "!miniTicker@arr")) {
            miniMessages.incrementAndGet();
            readMiniTickerArray(payload, data, end);
        } else {
            bookMessages.incrementAndGet();
            readBookTicker(payload, data, end);
        }
    }

    private void readBookTicker(CharSequence s, int from, int to) {
        final int symStart = valueStart(s, from, to, 's');
        if (symStart < 0) return;
        final int symEnd = Json.stringEnd(s, symStart, to);
        if (!matchesQuoteAsset(s, symStart, symEnd)) return;

        final long bid = scaled(s, from, to, 'b');
        final long bidQty = scaled(s, from, to, 'B');
        final long ask = scaled(s, from, to, 'a');
        final long askQty = scaled(s, from, to, 'A');
        if (bid < 0 || ask < 0) return;

        listener.onBookTicker(s.subSequence(symStart, symEnd), bid, bidQty, ask, askQty);
    }

    /** The mini-ticker payload is an array of flat objects; walk them by brace. */
    private void readMiniTickerArray(CharSequence s, int from, int to) {

        int cursor = from;
        while (true) {
            final int objStart = Json.nextObject(s, cursor, to);
            if (objStart < 0) break;
            final int objEnd = Json.flatObjectEnd(s, objStart, to);

            final int symStart = valueStart(s, objStart, objEnd, 's');
            if (symStart >= 0) {
                final int symEnd = Json.stringEnd(s, symStart, objEnd);
                if (matchesQuoteAsset(s, symStart, symEnd)) {
                    final long close = scaled(s, objStart, objEnd, 'c');
                    final long open = scaled(s, objStart, objEnd, 'o');
                    final long high = scaled(s, objStart, objEnd, 'h');
                    final long low = scaled(s, objStart, objEnd, 'l');
                    final long quoteVol = scaled(s, objStart, objEnd, 'q');

                    if (close >= 0) {
                        listener.onMiniTicker(s.subSequence(symStart, symEnd),
                                close, open, high, low, quoteVol);
                        universe.put(s.subSequence(symStart, symEnd).toString(), quoteVol);
                    }
                }
            }
            cursor = objEnd;
        }

        // One subscribe after a warm-up, then periodic top-ups. A single message names only the
        // symbols that traded in that second -- about a third of the venue -- so subscribing once
        // and never again leaves later arrivals permanently without a top of book, which shows up
        // as rows quoting zero next to rows quoting properly.
        final long seen = miniMessages.get();
        if (!subscribed.get()) {
            if (seen >= warmupTickers && !universe.isEmpty()) {
                subscribeToBusiest();
                nextTopUpAt = seen + TOP_UP_EVERY;
            }
        } else if (seen >= nextTopUpAt) {
            nextTopUpAt = seen + TOP_UP_EVERY;
            subscribeToBusiest();
        }
    }

    /** Whether a symbol is quoted in the configured asset. Matches on the suffix, as Binance names do. */
    private boolean matchesQuoteAsset(CharSequence s, int from, int to) {
        if (quoteAsset.isEmpty()) return true;
        final int n = quoteAsset.length();
        if (to - from <= n) return false;                 // a bare "USDT" is not a pair
        for (int i = 0; i < n; i++) {
            if (s.charAt(to - n + i) != quoteAsset.charAt(i)) return false;
        }
        return true;
    }

    /** Reads a quoted decimal field as a scaled long, or {@code -1} if the field is absent. */
    private static long scaled(CharSequence s, int from, int to, char key) {
        final int start = valueStart(s, from, to, key);
        if (start < 0) return -1;
        final int end = Json.stringEnd(s, start, to);
        try {
            return FixedFormat.parseScaled(s, start, end, SCALE);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int valueStart(CharSequence s, int from, int to, char key) {
        final int at = Json.field(s, from, to, key);
        if (at < 0 || at >= to) return -1;
        return s.charAt(at) == '"' ? Json.stringStart(at) : at;
    }

    // ------------------------------------------------------------ subscribe

    /**
     * Subscribes to the busiest symbols not already streaming, up to the configured limit.
     *
     * <p>Idempotent by construction: {@link #streaming} records what has been asked for, so a
     * top-up sends only the difference. Re-sending a subscription the venue already has is not an
     * error, but it is rude at a few hundred streams a time.
     */
    private void subscribeToBusiest() {
        final WebSocket ws = socket.get();
        if (ws == null) return;

        record Ranked(String symbol, long volume) { }
        final List<Ranked> candidates = new ArrayList<>(universe.size());
        universe.forEach((symbol, volume) -> {
            if (!streaming.contains(symbol)) candidates.add(new Ranked(symbol, volume));
        });
        if (candidates.isEmpty()) return;

        candidates.sort(Comparator.comparingLong(Ranked::volume).reversed());

        final int room = topSymbols - streaming.size();
        if (room <= 0) return;

        final List<String> params = new ArrayList<>(Math.min(room, candidates.size()));
        for (Ranked r : candidates) {
            if (params.size() >= room) break;
            streaming.add(r.symbol());
            params.add(r.symbol().toLowerCase(java.util.Locale.ROOT) + "@bookTicker");
        }

        final boolean first = subscribed.compareAndSet(false, true);
        listener.onStatus((first ? "subscribing to " : "topping up with ") + params.size()
                + " bookTicker streams (" + streaming.size() + " of " + universe.size() + " "
                + (quoteAsset.isEmpty() ? "symbols" : quoteAsset + " pairs") + ")");

        for (int i = 0; i < params.size(); i += MAX_SUBSCRIBE_BATCH) {
            final List<String> batch = params.subList(i, Math.min(params.size(), i + MAX_SUBSCRIBE_BATCH));
            final StringBuilder request = new StringBuilder(batch.size() * 24 + 48);
            request.append("{\"method\":\"SUBSCRIBE\",\"params\":[");
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0) request.append(',');
                request.append('"').append(batch.get(j)).append('"');
            }
            request.append("],\"id\":").append(streaming.size() + i).append('}');
            ws.sendText(request, true).join();
        }
    }

    /** Streams currently subscribed. */
    public int streamingCount() {
        return streaming.size();
    }
}
