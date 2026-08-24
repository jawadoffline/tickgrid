package io.github.tickgrid.demo;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.RowExtractor;
import io.github.tickgrid.render.GridColumn;
import io.github.tickgrid.render.GridTheme;
import io.github.tickgrid.render.TickGridView;
import io.github.tickgrid.store.ColumnSpec;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.store.Schema;
import io.github.tickgrid.view.BackgroundRecomputer;
import io.github.tickgrid.view.SortPolicy;
import io.github.tickgrid.view.ViewModel;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The whole pipeline running: synthetic feed threads to a conflating ingress, drained on the pulse
 * into a columnar store, ordered by a background view model, painted to canvases.
 *
 * <p>The HUD is not decoration. Feed rate, applies per frame, conflation ratio and drain backlog are
 * the four numbers that tell you whether the architecture is behaving, and the design's own README
 * plan calls for a GIF with a live updates counter — this is that counter.
 *
 * <pre>
 *   ./gradlew :tickgrid-demo:run                          5,000 instruments
 *   ./gradlew :tickgrid-demo:run --args="50000 4"         50,000 across 4 feed threads
 *   ./gradlew :tickgrid-demo:run -Protate=250             instruments retiring and relisting
 * </pre>
 */
public final class BlotterDemo extends Application {

    static final int SYMBOL = 0, BID = 1, ASK = 2, LAST = 3, CHANGE = 4, VOLUME = 5, TRADES = 6;

    private int instruments = 5_000;
    private int feedThreads = 2;
    /** -Dfeed.stopAfter=ms halts the feed, to show the grid going idle when the market does. */
    private final long feedStopAfterMillis = Long.getLong("feed.stopAfter", 0);
    /**
     * -Dfeed.rotate=ms retires one instrument per feed thread on that interval and lists a new one
     * in its place, which is what a session rollover or an expiry looks like. Off by default: it
     * changes what the demo measures, and the throughput figures in BENCHMARKS.md are the static
     * universe. Turn it on to watch slot count stay flat while thousands of instruments pass
     * through — the HUD grows a `rotated` field when it is set.
     */
    private final long rotateEveryMillis = Long.getLong("feed.rotate", 0);
    /**
     * Slots held back for retirements in flight. A retired slot is not reusable the instant it is
     * retired — it waits for a snapshot that excludes it — so a store sized exactly to the live
     * universe rejects the replacement instrument for the few frames in between. Sizing the demo
     * to the universe exactly is how that was found: 251,554 rejected submits in fifteen seconds.
     */
    private static final int ROTATION_HEADROOM = 64;

    private ColumnStore store;
    private ConflatingIngress<Quote, String> ingress;
    private ViewModel viewModel;
    private TickGridView grid;
    private BackgroundRecomputer recomputer;
    private volatile boolean feeding = true;

    /** A producer's row object, reused between submits — the ingress extracts before returning. */
    static final class Quote {
        String symbol;
        long bid, ask, last, volume;
        int trades;
        double changePct;
    }

    static Schema schema() {
        return Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.scaled("bid", 2).flashOnChange())
                .add(ColumnSpec.scaled("ask", 2).flashOnChange())
                .add(ColumnSpec.scaled("last", 2).flashOnChange())
                .add(ColumnSpec.doubles("changePct"))
                .add(ColumnSpec.longs("volume"))
                .add(ColumnSpec.ints("trades"))
                .build();
    }

    static List<GridColumn> columns() {
        return List.of(
                GridColumn.text(SYMBOL, "SYMBOL").width(96).leftAligned(),
                GridColumn.fixed(BID, "BID", 2).width(92),
                GridColumn.fixed(ASK, "ASK", 2).width(92),
                GridColumn.fixed(LAST, "LAST", 2).width(92),
                GridColumn.decimal(CHANGE, "CHG%", 2).width(84).colorBySign(),
                GridColumn.grouped(VOLUME, "VOLUME").width(116),
                GridColumn.grouped(TRADES, "TRADES").width(92));
    }

    @Override
    public void init() {
        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) instruments = Integer.parseInt(args.get(0));
        if (args.size() > 1) feedThreads = Integer.parseInt(args.get(1));
    }

    @Override
    public void start(Stage stage) {
        final int capacity = instruments + (rotateEveryMillis > 0 ? ROTATION_HEADROOM : 0);
        store = new ColumnStore(capacity, schema(), Math.max(1024, instruments * 2));

        final ColumnStore boundStore = store;
        ingress = new ConflatingIngress<>(capacity, schema().size(),
                new RowExtractor<Quote, String>() {
                    @Override public String key(Quote row) {
                        return row.symbol;
                    }
                    @Override public void extract(Quote row, long[] staging, int base) {
                        staging[base + SYMBOL] = boundStore.dictionary().intern(row.symbol);
                        staging[base + BID] = row.bid;
                        staging[base + ASK] = row.ask;
                        staging[base + LAST] = row.last;
                        staging[base + CHANGE] = Double.doubleToRawLongBits(row.changePct);
                        staging[base + VOLUME] = row.volume;
                        staging[base + TRADES] = row.trades;
                    }
                });

        viewModel = new ViewModel(store);
        viewModel.setSortPolicy(SortPolicy.throttled(250, TimeUnit.MILLISECONDS));
        recomputer = new BackgroundRecomputer(viewModel).start();

        grid = new TickGridView(store, viewModel, ingress, GridTheme.dark(), columns());

        final Label hud = new Label();
        hud.setFont(Font.font("Consolas", 11));
        hud.setTextFill(Color.web("#8b9aa3"));
        final Label help = new Label("header to sort · arrows/page/home/end · scroll");
        help.setFont(Font.font("Consolas", 11));
        help.setTextFill(Color.web("#5c6f79"));

        HBox status = new HBox(24, hud, help);
        status.setPadding(new Insets(6, 10, 6, 10));
        status.setStyle("-fx-background-color: #1d272d;");

        BorderPane root = new BorderPane();
        root.setCenter(grid);
        root.setBottom(status);
        BorderPane.setAlignment(grid, javafx.geometry.Pos.TOP_LEFT);
        HBox.setHgrow(hud, Priority.NEVER);

        Scene scene = new Scene(root, 1180, 760, Color.web("#12181c"));
        stage.setScene(scene);
        stage.setTitle("TickGrid — blotter demo");
        stage.show();
        grid.requestFocus();
        grid.start();

        startFeeds();
        startHud(hud);
        maybeStopFeed();
        maybeScreenshot(scene);
    }

    private void maybeStopFeed() {
        if (feedStopAfterMillis <= 0) return;
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(feedStopAfterMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            feeding = false;
            System.out.println("feed stopped after " + feedStopAfterMillis + "ms");
            if (rotateEveryMillis > 0) {
                System.out.printf(
                        "rotation: %,d retired, %,d reclaimed, %,d awaiting, "
                      + "%,d keys live of %,d slots, %,d rejected%n",
                        ingress.retiredCount(), ingress.reclaimedCount(), ingress.awaitingReclaim(),
                        ingress.keyCount(), instruments + ROTATION_HEADROOM,
                        ingress.rejectedCount());
            }
        }, "feed-stopper");
        stopper.setDaemon(true);
        stopper.start();
    }

    /**
     * {@code -Dscreenshot=out.png -Dscreenshot.delay=3000} renders for a while, saves the scene and
     * exits. Canvas output cannot be asserted the way arithmetic can, so this is how a change to the
     * painting gets checked — and it is the hook a golden-image test would hang off.
     */
    private void maybeScreenshot(Scene scene) {
        final String path = System.getProperty("screenshot");
        if (path == null) return;
        final long delay = Long.getLong("screenshot.delay", 3_000);

        Thread shot = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            javafx.application.Platform.runLater(() -> {
                javafx.scene.image.WritableImage image = scene.snapshot(null);
                java.io.File file = new java.io.File(path);
                try {
                    javax.imageio.ImageIO.write(
                            javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", file);
                    System.out.println("screenshot: " + file.getAbsolutePath()
                            + "  (" + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
                } catch (Exception e) {
                    System.err.println("screenshot failed: " + e);
                }
                javafx.application.Platform.exit();
            });
        }, "screenshot");
        shot.setDaemon(true);
        shot.start();
    }

    /**
     * Feed threads, sharded by symbol so each key has exactly one writer — the contract that makes
     * the per-slot seqlock sound.
     */
    private void startFeeds() {
        final int shard = Math.max(1, instruments / feedThreads);
        for (int t = 0; t < feedThreads; t++) {
            final int lo = t * shard;
            final int hi = (t == feedThreads - 1) ? instruments : Math.min(instruments, lo + shard);
            Thread thread = new Thread(() -> feed(lo, hi), "feed-" + t);
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void feed(int lo, int hi) {
        final int count = hi - lo;
        if (count <= 0) return;

        final String[] symbols = new String[count];
        final long[] price = new long[count];
        final long[] volume = new long[count];
        final long[] open = new long[count];
        for (int i = 0; i < count; i++) {
            symbols[i] = symbol(lo + i);
            price[i] = 1_000 + (lo + i) * 37L % 90_000;
            open[i] = price[i];
        }

        final Quote quote = new Quote();
        long rng = 0x9E3779B97F4A7C15L + lo;
        int i = 0;
        // Rotation happens on the feed thread that owns the shard, which is what keeps it inside
        // the single-writer-per-key contract: the thread that submits a key is the thread that
        // retires it, so no serialisation of its own is needed.
        long nextRotateNanos = rotateEveryMillis > 0
                ? System.nanoTime() + rotateEveryMillis * 1_000_000L : Long.MAX_VALUE;
        int generation = 0;
        int rotateAt = 0;

        while (feeding) {
            if (System.nanoTime() >= nextRotateNanos) {
                nextRotateNanos = System.nanoTime() + rotateEveryMillis * 1_000_000L;
                final int k = rotateAt;
                rotateAt = (rotateAt + 1) % count;
                if (rotateAt == 0) generation++;

                ingress.retire(symbols[k]);
                symbols[k] = symbol(lo + k) + "." + (generation + 1);
                price[k] = 1_000 + (lo + k) * 37L % 90_000;
                open[k] = price[k];
                volume[k] = 0;
            }
            for (int batch = 0; batch < 512 && feeding; batch++) {
                rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
                final int k = i++ % count;

                // floorMod, not %: a negative long under % yields [-6,6], which biased the walk
                // downward and marched every price into the floor within seconds.
                long move = Math.floorMod(rng, 7) - 3;
                price[k] = Math.max(100, price[k] + move);
                volume[k] += Math.floorMod(rng, 400);

                quote.symbol = symbols[k];
                quote.last = price[k];
                quote.bid = price[k] - 1;
                quote.ask = price[k] + 1;
                quote.volume = volume[k];
                quote.trades = (int) (volume[k] / 100);
                quote.changePct = (price[k] - open[k]) * 100.0 / open[k];
                ingress.submit(quote);
            }
            // A real feed is not infinitely fast, and a demo that pins every core tells you nothing
            // about the grid. This keeps the load high but leaves the machine usable.
            LockSupportPark.parkMicros(120);
        }
    }

    /** Symbols that look like symbols, so the demo reads like a blotter rather than a test fixture. */
    static String symbol(int index) {
        final String[] roots = {"AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO",
                                "LLY", "JPM", "XOM", "UNH", "COST", "HD", "MRK", "ABBV", "CVX",
                                "CRM", "PEP", "KO", "ADBE", "WMT", "BAC", "MCD", "ACN", "NFLX"};
        String root = roots[index % roots.length];
        int suffix = index / roots.length;
        return suffix == 0 ? root : root + suffix;
    }

    private void startHud(Label hud) {
        new AnimationTimer() {
            private long lastNanos;
            private long lastSubmitted;
            private long lastApplied;
            private long lastPainted;
            private long lastSkipped;

            @Override public void handle(long now) {
                if (now - lastNanos < 250_000_000L) return;
                final double seconds = lastNanos == 0 ? 1 : (now - lastNanos) / 1e9;
                lastNanos = now;

                final long submitted = ingress.submittedCount();
                final long applied = ingress.appliedCount();
                final long painted = grid.framesPainted();
                final long skipped = grid.framesSkipped();

                final double msgRate = (submitted - lastSubmitted) / seconds;
                final double applyRate = (applied - lastApplied) / seconds;
                final double fps = (painted - lastPainted) / seconds;
                final double idle = (skipped - lastSkipped) / seconds;

                lastSubmitted = submitted;
                lastApplied = applied;
                lastPainted = painted;
                lastSkipped = skipped;

                final String rotation = rotateEveryMillis <= 0 ? "" : String.format(Locale.ROOT,
                        "  rotated %,d  slots %,d/%,d",
                        ingress.reclaimedCount(), ingress.keyCount(), instruments);

                hud.setText(String.format(Locale.ROOT,
                        "%,9.0f msg/s  %,9.0f applies/s  %4.1f:1  %,7d rows  "
                      + "%3.0f fps  %3.0f idle  backlog %,d%s",
                        msgRate, applyRate,
                        applyRate < 1 ? 1.0 : msgRate / applyRate,
                        viewModel.snapshot().count(), fps, idle, ingress.backlog(), rotation));
            }
        }.start();
    }

    @Override
    public void stop() throws Exception {
        feeding = false;
        if (grid != null) grid.stop();
        if (recomputer != null) recomputer.closeAndJoin();
    }

    /** {@code Thread.sleep} has millisecond granularity; a feed needs finer pacing than that. */
    static final class LockSupportPark {
        static void parkMicros(long micros) {
            java.util.concurrent.locks.LockSupport.parkNanos(micros * 1_000L);
        }
    }
}
