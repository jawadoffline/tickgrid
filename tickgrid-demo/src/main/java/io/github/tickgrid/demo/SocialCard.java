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
import io.github.tickgrid.view.SortSpec;
import io.github.tickgrid.view.ViewModel;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * Renders the repository's social preview card, at the 1280x640 GitHub expects.
 *
 * <p>The grid draws its own card. A cropped screenshot would be the obvious approach and a worse
 * one: a card is displayed around 600 pixels wide in a Slack unfurl, so 16-pixel rows scaled down
 * become a grey texture. This runs the real renderer with the row height and font size turned up,
 * showing a dozen rows that stay legible after the platform has shrunk them.
 *
 * <pre>./gradlew :tickgrid-demo:socialCard</pre>
 */
public final class SocialCard extends Application {

    static final int SYMBOL = 0, BID = 1, ASK = 2, LAST = 3, CHANGE = 4, VOLUME = 5;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 640;
    /** Sized so the rows fill the card exactly, leaving no scrollbar stub down the right edge. */
    private static final int INSTRUMENTS = 12;

    private static final Color INK = Color.web("#e8f0f4");
    private static final Color DIM = Color.web("#7d8f99");
    private static final Color ACCENT = Color.web("#55c4e6");
    private static final Color BACKDROP = Color.web("#0e1418");

    private ConflatingIngress<Tick, String> ingress;
    private BackgroundRecomputer recomputer;
    private TickGridView grid;
    private volatile boolean feeding = true;

    static final class Tick {
        String symbol;
        long bid, ask, last, volume;
        double changePct;
    }

    /** Bigger rows and a bigger face than the desktop default; everything else is the real theme. */
    static GridTheme cardTheme() {
        GridTheme d = GridTheme.dark();
        return new GridTheme(
                d.background, d.rowEven, d.rowOdd, d.gridLine,
                d.headerBackground, d.headerText, d.headerRule,
                d.text, d.textDim, d.positive, d.negative,
                d.selection, d.hover, d.focusRing,
                34, 34, 10, "Consolas", 19, 320);
    }

    @Override
    public void start(Stage stage) {
        Schema schema = Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.scaled("bid", 2).flashOnChange())
                .add(ColumnSpec.scaled("ask", 2).flashOnChange())
                .add(ColumnSpec.scaled("last", 2).flashOnChange())
                .add(ColumnSpec.doubles("changePct"))
                .add(ColumnSpec.longs("volume"))
                .build();

        ColumnStore store = new ColumnStore(INSTRUMENTS, schema, 512);
        final ColumnStore bound = store;

        ingress = new ConflatingIngress<>(INSTRUMENTS, schema.size(), new RowExtractor<Tick, String>() {
            @Override public String key(Tick row) {
                return row.symbol;
            }
            @Override public void extract(Tick row, long[] staging, int base) {
                staging[base + SYMBOL] = bound.dictionary().intern(row.symbol);
                staging[base + BID] = row.bid;
                staging[base + ASK] = row.ask;
                staging[base + LAST] = row.last;
                staging[base + CHANGE] = Double.doubleToRawLongBits(row.changePct);
                staging[base + VOLUME] = row.volume;
            }
        });

        ViewModel viewModel = new ViewModel(store);
        // Not MANUAL: that recomputes once, and at startup the store is empty, so the view would
        // stay at zero rows however much data arrived afterwards.
        viewModel.setSortPolicy(SortPolicy.throttled(200, java.util.concurrent.TimeUnit.MILLISECONDS));
        viewModel.sortBy(SortSpec.descending(VOLUME));
        recomputer = new BackgroundRecomputer(viewModel).start();

        grid = new TickGridView(store, viewModel, ingress, cardTheme(), List.of(
                GridColumn.text(SYMBOL, "SYMBOL").width(150).leftAligned(),
                GridColumn.fixed(BID, "BID", 2).width(150),
                GridColumn.fixed(ASK, "ASK", 2).width(150),
                GridColumn.fixed(LAST, "LAST", 2).width(150),
                GridColumn.decimal(CHANGE, "CHG%", 2).width(130).colorBySign(),
                GridColumn.grouped(VOLUME, "VOLUME").width(190)));

        Label title = new Label("TickGrid");
        title.setFont(Font.font("Consolas", FontWeight.BOLD, 58));
        title.setTextFill(INK);

        Label tagline = new Label("a canvas data grid for JavaFX, built for real-time market data");
        tagline.setFont(Font.font("Consolas", 21));
        tagline.setTextFill(DIM);

        Label stat = new Label("19M msg/sec   ·   zero allocation   ·   60 fps under load");
        stat.setFont(Font.font("Consolas", FontWeight.BOLD, 20));
        stat.setTextFill(ACCENT);

        VBox header = new VBox(8, title, tagline, stat);
        header.setPadding(new Insets(34, 40, 26, 40));

        BorderPane root = new BorderPane();
        root.setBackground(new Background(new BackgroundFill(BACKDROP, CornerRadii.EMPTY, Insets.EMPTY)));
        root.setTop(header);
        root.setCenter(grid);

        stage.setScene(new Scene(root, WIDTH, HEIGHT, BACKDROP));
        stage.setTitle("TickGrid social card");
        stage.show();
        grid.start();

        startFeed();
        // Long enough for the sort to settle and for a scatter of cells to be mid-flash, short
        // enough that the prices still look like a market rather than a random walk gone wide.
        capture(stage.getScene(), 3_400);
    }

    private void startFeed() {
        Thread t = new Thread(() -> {
            // Real tickers at plausible prices. Generated names (AAPL3, MRK3) and a uniform
            // price ramp both read as test data, which is the one thing a card should not look
            // like: the point is that this is what the grid renders, not what a fixture renders.
            String[] symbols = {"NVDA", "AAPL", "MSFT", "AMZN", "META", "GOOGL",
                                "TSLA", "AVGO", "JPM", "LLY", "XOM", "COST"};
            long[] price = {14_312, 22_847, 43_106, 18_734, 59_218, 17_455,
                            24_089, 17_302, 24_611, 78_940, 11_827, 91_455};
            long[] volume = {52_318_400, 41_772_900, 22_104_600, 19_886_300, 15_402_700, 14_338_100,
                             13_927_500, 9_664_200, 8_115_800, 6_402_100, 5_877_300, 3_218_900};
            long[] open = new long[INSTRUMENTS];
            System.arraycopy(price, 0, open, 0, INSTRUMENTS);

            Tick tick = new Tick();
            long rng = 0x9E3779B97F4A7C15L;
            int i = 0;
            // Paced, not flat out. At full speed every instrument is always inside its flash
            // window and the whole grid is tinted, which reads as a heatmap rather than as ticks.
            // A tick roughly every 80ms per feed pass leaves a third of the rows lit at any moment.
            while (feeding) {
                for (int batch = 0; batch < 1 && feeding; batch++) {
                    rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
                    int k = i++ % INSTRUMENTS;
                    price[k] = Math.max(500, price[k] + Math.floorMod(rng, 9) - 4);
                    volume[k] += Math.floorMod(rng, 400);

                    tick.symbol = symbols[k];
                    tick.last = price[k];
                    tick.bid = price[k] - 2;
                    tick.ask = price[k] + 2;
                    tick.volume = volume[k];
                    tick.changePct = (price[k] - open[k]) * 100.0 / open[k];
                    ingress.submit(tick);
                }
                java.util.concurrent.locks.LockSupport.parkNanos(80_000_000L);
            }
        }, "card-feed");
        t.setDaemon(true);
        t.start();
    }

    private void capture(Scene scene, long delayMillis) {
        Thread shot = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(() -> {
                // Snapshotting in the same pulse that publishes a new order captures the frame
                // before the renderer has drawn it. Let a few pulses go by first.
                new javafx.animation.AnimationTimer() {
                    private int pulses;

                    @Override public void handle(long now) {
                        if (pulses++ < 4) return;
                        stop();
                        write(scene);
                        Platform.exit();
                    }
                }.start();
            });
        }, "card-capture");
        shot.setDaemon(true);
        shot.start();
    }

    private static void write(Scene scene) {
        File out = new File(System.getProperty("card.out", "docs/social-card.png"));
        out.getAbsoluteFile().getParentFile().mkdirs();
        try {
            javafx.scene.image.WritableImage image = scene.snapshot(null);
            java.awt.image.BufferedImage rgba =
                    javafx.embed.swing.SwingFXUtils.fromFXImage(image, null);

            // Written without an alpha channel on purpose. The card is fully opaque anyway, so the
            // alpha band carries nothing, and GitHub's social-preview upload would not serve an
            // RGBA PNG of this card: it accepted the file at the form and then reverted the
            // setting to its auto-generated image. TYPE_INT_RGB composites against the card's own
            // backdrop and emits a three-channel PNG.
            java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(
                    rgba.getWidth(), rgba.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.setColor(new java.awt.Color(0x0e, 0x14, 0x18));
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(rgba, 0, 0, null);
            g.dispose();

            javax.imageio.ImageIO.write(rgb, "png", out);
            System.out.printf("social card: %s  (%dx%d, no alpha)%n", out.getAbsolutePath(),
                    rgb.getWidth(), rgb.getHeight());
        } catch (Exception e) {
            System.err.println("card render failed: " + e);
        }
    }

    @Override
    public void stop() throws Exception {
        feeding = false;
        if (grid != null) grid.stop();
        if (recomputer != null) recomputer.closeAndJoin();
    }
}
