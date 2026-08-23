package io.github.tickgrid.bench;

import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The baseline: {@code TableView} over an {@code ObservableList} of property-bearing rows, which is
 * how a JavaFX developer writes this without a specialist library.
 *
 * <p>Two update strategies, because comparing against the worst possible usage would be a strawman.
 *
 * <p><b>{@link Mode#NAIVE}</b> is what the design document describes: one
 * {@code Platform.runLater} per message. It is the obvious code and it is what most people write
 * first.
 *
 * <p><b>{@link Mode#BATCHED}</b> is what a careful developer does once the naive version stalls:
 * park updates in a lock-free queue and apply them on an {@code AnimationTimer}, one batch per
 * pulse. It keeps the FX thread out of the queueing business entirely and is a genuinely reasonable
 * design. It is the honest competitor, and the number worth quoting against.
 *
 * <p>Neither conflates. That is the difference the comparison is measuring: batching bounds how
 * often you touch the scene graph, but the work per pulse still scales with <i>messages</i>, where
 * conflation makes it scale with <i>changed rows</i>.
 */
public final class TableViewTarget implements BenchTarget {

    public enum Mode { NAIVE, BATCHED }

    /** Past this many queued updates the run is meaningless and the heap is in danger. */
    private static final int COLLAPSE_THRESHOLD = 4_000_000;

    /** A row, with the JavaFX properties a TableView needs to observe it. */
    public static final class Quote {
        final SimpleStringProperty symbol = new SimpleStringProperty();
        final SimpleLongProperty bid = new SimpleLongProperty();
        final SimpleLongProperty ask = new SimpleLongProperty();
        final SimpleLongProperty last = new SimpleLongProperty();
        final SimpleDoubleProperty changePct = new SimpleDoubleProperty();
        final SimpleLongProperty volume = new SimpleLongProperty();
        final SimpleIntegerProperty trades = new SimpleIntegerProperty();
        volatile long submitNanos;

        public SimpleStringProperty symbolProperty()     { return symbol; }
        public SimpleLongProperty bidProperty()          { return bid; }
        public SimpleLongProperty askProperty()          { return ask; }
        public SimpleLongProperty lastProperty()         { return last; }
        public SimpleDoubleProperty changePctProperty()  { return changePct; }
        public SimpleLongProperty volumeProperty()       { return volume; }
        public SimpleIntegerProperty tradesProperty()    { return trades; }
    }

    /** One pending update, pre-allocated per instrument so the queue itself does not allocate. */
    private static final class Pending {
        volatile long bid, ask, last, volume, submitNanos;
        volatile int trades;
        volatile double changePct;
        final AtomicInteger queued = new AtomicInteger();
    }

    private final Mode mode;
    private final TableView<Quote> table = new TableView<>();
    private final ObservableList<Quote> rows;
    private final Quote[] quotes;
    private final Pending[] pending;
    private final org.jctools.queues.MpscArrayQueue<Integer> dirty;
    private final Integer[] boxed;
    private final AtomicLong queuedTotal = new AtomicLong();
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicLong appliedTotal = new AtomicLong();
    private volatile boolean collapsed;

    public TableViewTarget(int instruments, Mode mode) {
        this.mode = mode;
        this.quotes = new Quote[instruments];
        this.pending = new Pending[instruments];
        this.boxed = new Integer[instruments];
        // Sized by expected message rate, not by instrument count. Without conflation there is no
        // bound derivable from the data model -- a key can be enqueued once per message -- so the
        // only honest option is to make it generous and see whether the FX thread can drain it.
        // TickGrid's queue is provably 2x the key count for exactly the reason this one cannot be.
        this.dirty = new org.jctools.queues.MpscArrayQueue<>(
                Math.max(1 << 18, Integer.highestOneBit(Math.max(2, instruments * 8 - 1)) * 2));

        for (int i = 0; i < instruments; i++) {
            Quote q = new Quote();
            q.symbol.set(SyntheticFeed.symbol(i));
            quotes[i] = q;
            pending[i] = new Pending();
            boxed[i] = i;
        }
        this.rows = FXCollections.observableArrayList(quotes);

        table.setItems(rows);
        table.setFixedCellSize(16);                 // the fair comparison: no variable-height cells
        table.getColumns().addAll(
                column("SYMBOL", "symbol", 96),
                column("BID", "bid", 92),
                column("ASK", "ask", 92),
                column("LAST", "last", 92),
                column("CHG%", "changePct", 84),
                column("VOLUME", "volume", 116),
                column("TRADES", "trades", 92));

        if (mode == Mode.BATCHED) {
            startBatchPump();
        }
    }

    private static <T> TableColumn<Quote, T> column(String title, String property, double width) {
        TableColumn<Quote, T> c = new TableColumn<>(title);
        c.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>(property));
        c.setPrefWidth(width);
        c.setSortable(true);
        return c;
    }

    @Override
    public Parent node() {
        return table;
    }

    @Override
    public void onTick(int instrument, String symbol, long bid, long ask, long last,
                       double changePct, long volume, int trades, long submitNanos) {
        if (collapsed) return;

        if (mode == Mode.NAIVE) {
            // One runnable per message. The queue is unbounded, which is precisely the failure the
            // design document names -- so it is measured rather than mitigated.
            if (outstanding.get() > COLLAPSE_THRESHOLD) {
                collapsed = true;
                return;
            }
            outstanding.incrementAndGet();
            queuedTotal.incrementAndGet();
            final Quote q = quotes[instrument];
            Platform.runLater(() -> {
                q.bid.set(bid);
                q.ask.set(ask);
                q.last.set(last);
                q.changePct.set(changePct);
                q.volume.set(volume);
                q.trades.set(trades);
                q.submitNanos = submitNanos;
                outstanding.decrementAndGet();
                appliedTotal.incrementAndGet();
            });
            return;
        }

        final Pending p = pending[instrument];
        p.bid = bid;
        p.ask = ask;
        p.last = last;
        p.changePct = changePct;
        p.volume = volume;
        p.trades = trades;
        p.submitNanos = submitNanos;
        queuedTotal.incrementAndGet();
        // Note what this does NOT do: there is no dirty flag, so a hot instrument is enqueued once
        // per message. Batching bounds how often the FX thread wakes; it does not bound how much
        // work it finds when it does.
        if (!dirty.offer(boxed[instrument])) {
            collapsed = true;
        }
    }

    private void startBatchPump() {
        new javafx.animation.AnimationTimer() {
            @Override public void handle(long now) {
                Integer slot;
                int applied = 0;
                while ((slot = dirty.poll()) != null) {
                    final int i = slot;
                    final Pending p = pending[i];
                    final Quote q = quotes[i];
                    q.bid.set(p.bid);
                    q.ask.set(p.ask);
                    q.last.set(p.last);
                    q.changePct.set(p.changePct);
                    q.volume.set(p.volume);
                    q.trades.set(p.trades);
                    q.submitNanos = p.submitNanos;
                    applied++;
                }
                appliedTotal.addAndGet(applied);
            }
        }.start();
    }

    @Override
    public void sample(long nowNanos, FrameStats stats) {
        final int step = Math.max(1, quotes.length / 200);
        for (int i = 0; i < quotes.length; i += step) {
            final long stamp = quotes[i].submitNanos;
            if (stamp != 0) stats.recordStaleness(nowNanos - stamp);
        }
    }

    @Override
    public boolean hasCollapsed() {
        return collapsed;
    }

    @Override
    public String note() {
        return String.format(Locale.ROOT, "queued %,d applied %,d%s",
                queuedTotal.get(), appliedTotal.get(),
                collapsed ? "  COLLAPSED" : "");
    }

    @Override
    public void shutdown() {
        collapsed = true;
    }
}
