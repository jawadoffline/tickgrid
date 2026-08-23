package io.github.tickgrid.demo.binance;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.RowExtractor;
import io.github.tickgrid.render.FixedFormat;
import io.github.tickgrid.render.GridColumn;
import io.github.tickgrid.render.GridTheme;
import io.github.tickgrid.render.TickGridView;
import io.github.tickgrid.store.ColumnSpec;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.store.Schema;
import io.github.tickgrid.view.BackgroundRecomputer;
import io.github.tickgrid.view.SortPolicy;
import io.github.tickgrid.view.SortSpec;
import io.github.tickgrid.view.ViewModel;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The grid on live Binance market data.
 *
 * <p>Every symbol on the venue, ranked by 24-hour quote volume, with best bid and offer streaming
 * for the busiest few hundred. Prices go from the wire to a scaled-long column without passing
 * through {@code double}: {@link FixedFormat#parseScaled} reads the venue's decimal string directly
 * off the received buffer, which is the whole reason §3.1 says to store prices as scaled integers.
 *
 * <pre>
 *   ./gradlew :tickgrid-demo:binance                    the 300 busiest symbols
 *   ./gradlew :tickgrid-demo:binance -Ptop=50           fewer streams, gentler on the venue
 * </pre>
 */
public final class BinanceDemo extends Application {

    static final int SYMBOL = 0, BID = 1, ASK = 2, SPREAD_BPS = 3, LAST = 4,
                     CHANGE_PCT = 5, HIGH = 6, LOW = 7, QUOTE_VOL = 8, UPDATES = 9;

    private static final int CAPACITY = 8192;

    private int topSymbols = Integer.getInteger("binance.top", 300);
    private final String quoteAsset = System.getProperty("binance.quote", "USDT");

    private ColumnStore store;
    private ConflatingIngress<Row, String> ingress;
    private ViewModel viewModel;
    private BackgroundRecomputer recomputer;
    private TickGridView grid;
    private BinanceFeed feed;

    private volatile String status = "starting";

    /**
     * The complete current state of one symbol, owned by the feed thread.
     *
     * <p>Two streams supply different halves of a row -- bookTicker the top of book, miniTicker the
     * 24-hour figures -- and the ingress writes a row whole. Keeping the merged state here means the
     * extractor composes from data the producer owns, rather than reading the half it does not have
     * back out of the store. That read would cross from the feed thread to the drain thread's
     * store, which is a race the rest of this project goes to some trouble to avoid; there is no
     * reason to introduce one in the demo that shows it off.
     */
    static final class Row {
        String symbol;
        long bid, ask, last, open, high, low, quoteVol;
        double spreadBps, changePct;
        int updates;
    }

    static Schema schema() {
        return Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.scaled("bid", BinanceFeed.SCALE).flashOnChange())
                .add(ColumnSpec.scaled("ask", BinanceFeed.SCALE).flashOnChange())
                .add(ColumnSpec.doubles("spreadBps"))
                .add(ColumnSpec.scaled("last", BinanceFeed.SCALE).flashOnChange())
                .add(ColumnSpec.doubles("changePct"))
                .add(ColumnSpec.scaled("high", BinanceFeed.SCALE))
                .add(ColumnSpec.scaled("low", BinanceFeed.SCALE))
                .add(ColumnSpec.longs("quoteVol"))
                .add(ColumnSpec.ints("updates"))
                .build();
    }

    static List<GridColumn> columns() {
        // Stored at the venue's eight decimals, shown at six: exact underneath, readable on screen.
        return List.of(
                GridColumn.text(SYMBOL, "SYMBOL").width(108).leftAligned(),
                GridColumn.scaled(BID, "BID", BinanceFeed.SCALE, 6).width(118),
                GridColumn.scaled(ASK, "ASK", BinanceFeed.SCALE, 6).width(118),
                GridColumn.decimal(SPREAD_BPS, "SPRD bp", 1).width(78),
                GridColumn.scaled(LAST, "LAST", BinanceFeed.SCALE, 6).width(118),
                GridColumn.decimal(CHANGE_PCT, "CHG%", 2).width(80).colorBySign(),
                GridColumn.scaled(HIGH, "HIGH", BinanceFeed.SCALE, 6).width(118),
                GridColumn.scaled(LOW, "LOW", BinanceFeed.SCALE, 6).width(118),
                GridColumn.grouped(QUOTE_VOL, "24H VOL (USDT)").width(140),
                GridColumn.grouped(UPDATES, "TICKS").width(80));
    }

    @Override
    public void init() {
        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) topSymbols = Integer.parseInt(args.get(0));
    }

    @Override
    public void start(Stage stage) {
        store = new ColumnStore(CAPACITY, schema(), CAPACITY * 2);
        final ColumnStore bound = store;

        ingress = new ConflatingIngress<>(CAPACITY, schema().size(), new RowExtractor<Row, String>() {
            @Override public String key(Row row) {
                return row.symbol;
            }
            @Override public void extract(Row row, long[] staging, int base) {
                staging[base + SYMBOL] = bound.dictionary().intern(row.symbol);
                staging[base + BID] = row.bid;
                staging[base + ASK] = row.ask;
                staging[base + SPREAD_BPS] = Double.doubleToRawLongBits(row.spreadBps);
                staging[base + LAST] = row.last;
                staging[base + CHANGE_PCT] = Double.doubleToRawLongBits(row.changePct);
                staging[base + HIGH] = row.high;
                staging[base + LOW] = row.low;
                staging[base + QUOTE_VOL] = row.quoteVol;
                staging[base + UPDATES] = row.updates;
            }
        });

        viewModel = new ViewModel(store);
        viewModel.setSortPolicy(SortPolicy.throttled(400, TimeUnit.MILLISECONDS));
        viewModel.sortBy(SortSpec.descending(QUOTE_VOL));
        recomputer = new BackgroundRecomputer(viewModel).start();

        grid = new TickGridView(store, viewModel, ingress, GridTheme.dark(), columns());

        final Label hud = new Label();
        hud.setFont(Font.font("Consolas", 11));
        hud.setTextFill(Color.web("#8b9aa3"));
        final Label statusLabel = new Label();
        statusLabel.setFont(Font.font("Consolas", 11));
        statusLabel.setTextFill(Color.web("#5c6f79"));

        HBox bar = new HBox(20, hud, statusLabel);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.setStyle("-fx-background-color: #1d272d;");

        BorderPane root = new BorderPane();
        root.setCenter(grid);
        root.setBottom(bar);

        Scene scene = new Scene(root, 1280, 780, Color.web("#12181c"));
        stage.setScene(scene);
        stage.setTitle("TickGrid — Binance live");
        stage.show();
        grid.requestFocus();
        grid.start();

        startFeed();
        startHud(hud, statusLabel);
        maybeScreenshot(scene);
    }

    private void startFeed() {
        // Both streams arrive on one connection and therefore one thread, which satisfies the
        // ingress's single-writer-per-key contract for free.
        final java.util.Map<String, Row> merged = new java.util.HashMap<>(CAPACITY);

        feed = new BinanceFeed(new BinanceFeed.Listener() {
            private Row rowFor(CharSequence symbol) {
                final String key = symbol.toString();
                Row r = merged.get(key);
                if (r == null) {
                    r = new Row();
                    r.symbol = key;
                    merged.put(key, r);
                }
                return r;
            }

            @Override
            public void onBookTicker(CharSequence symbol, long bid, long bidQty,
                                     long ask, long askQty) {
                final Row r = rowFor(symbol);
                r.bid = bid;
                r.ask = ask;
                final long mid = (bid + ask) / 2;
                r.spreadBps = mid == 0 ? 0 : (ask - bid) * 10_000.0 / mid;
                r.updates++;
                ingress.submit(r);
            }

            @Override
            public void onMiniTicker(CharSequence symbol, long last, long open, long high,
                                     long low, long quoteVolume) {
                final Row r = rowFor(symbol);
                r.last = last;
                r.open = open;
                r.high = high;
                r.low = low;
                // Quote volume is a notional, not a price: whole currency units are plenty.
                r.quoteVol = FixedFormat.rescale(quoteVolume, BinanceFeed.SCALE, 0);
                r.changePct = open == 0 ? 0 : (last - open) * 100.0 / open;
                ingress.submit(r);
            }

            @Override
            public void onStatus(String message) {
                status = message;
                System.out.println("[binance] " + message);
            }
        }, topSymbols, quoteAsset, 4);

        feed.start();
    }

    private void startHud(Label hud, Label statusLabel) {
        new AnimationTimer() {
            private long lastNanos;
            private long lastSubmitted;
            private long lastApplied;

            @Override public void handle(long now) {
                if (now - lastNanos < 500_000_000L) return;
                final double seconds = lastNanos == 0 ? 1 : (now - lastNanos) / 1e9;
                lastNanos = now;

                final long submitted = ingress.submittedCount();
                final long applied = ingress.appliedCount();
                final double msgRate = (submitted - lastSubmitted) / seconds;
                final double applyRate = (applied - lastApplied) / seconds;
                lastSubmitted = submitted;
                lastApplied = applied;

                hud.setText(String.format(Locale.ROOT,
                        "%,7.0f msg/s  %,7.0f applies/s  %4.1f:1  %,5d symbols  "
                      + "%,10d book  %,4d mini  backlog %,d",
                        msgRate, applyRate,
                        applyRate < 1 ? 1.0 : msgRate / applyRate,
                        viewModel.snapshot().count(),
                        feed.bookTickerCount(), feed.miniTickerCount(), ingress.backlog()));
                statusLabel.setText(status + (feed.reconnectCount() > 0
                        ? "  ·  " + feed.reconnectCount() + " reconnects" : ""));
            }
        }.start();
    }

    private void maybeScreenshot(Scene scene) {
        final String path = System.getProperty("screenshot");
        if (path == null) return;
        final long delay = Long.getLong("screenshot.delay", 20_000);
        Thread shot = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(() -> {
                try {
                    javax.imageio.ImageIO.write(
                            javafx.embed.swing.SwingFXUtils.fromFXImage(scene.snapshot(null), null),
                            "png", new java.io.File(path));
                    System.out.println("screenshot: " + new java.io.File(path).getAbsolutePath());
                } catch (Exception e) {
                    System.err.println("screenshot failed: " + e);
                }
                Platform.exit();
            });
        }, "screenshot");
        shot.setDaemon(true);
        shot.start();
    }

    @Override
    public void stop() throws Exception {
        if (feed != null) feed.close();
        if (grid != null) grid.stop();
        if (recomputer != null) recomputer.closeAndJoin();
    }
}
