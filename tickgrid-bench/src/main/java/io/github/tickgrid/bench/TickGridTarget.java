package io.github.tickgrid.bench;

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
import javafx.scene.Parent;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** TickGrid under the same feed, with the same columns, measured the same way. */
public final class TickGridTarget implements BenchTarget {

    static final int SYMBOL = 0, BID = 1, ASK = 2, LAST = 3, CHANGE = 4, VOLUME = 5, TRADES = 6,
                     SUBMIT_NANOS = 7;

    /** Carries the producer's timestamp through the pipeline so staleness is measurable. */
    static final class Tick {
        String symbol;
        long bid, ask, last, volume, submitNanos;
        int trades;
        double changePct;
    }

    private final ColumnStore store;
    private final ConflatingIngress<Tick, String> ingress;
    private final ViewModel viewModel;
    private final BackgroundRecomputer recomputer;
    private final TickGridView grid;
    private final ThreadLocal<Tick> ticks = ThreadLocal.withInitial(Tick::new);
    private final int instruments;

    public TickGridTarget(int instruments) {
        this.instruments = instruments;

        Schema schema = Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.scaled("bid", 2).flashOnChange())
                .add(ColumnSpec.scaled("ask", 2).flashOnChange())
                .add(ColumnSpec.scaled("last", 2).flashOnChange())
                .add(ColumnSpec.doubles("changePct"))
                .add(ColumnSpec.longs("volume"))
                .add(ColumnSpec.ints("trades"))
                .add(ColumnSpec.longs("submitNanos"))       // carried, never drawn
                .build();

        this.store = new ColumnStore(instruments, schema, Math.max(1024, instruments * 2));
        final ColumnStore bound = store;

        this.ingress = new ConflatingIngress<>(instruments, schema.size(),
                new RowExtractor<Tick, String>() {
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
                        staging[base + TRADES] = row.trades;
                        staging[base + SUBMIT_NANOS] = row.submitNanos;
                    }
                });

        this.viewModel = new ViewModel(store);
        viewModel.setSortPolicy(SortPolicy.throttled(250, TimeUnit.MILLISECONDS));
        this.recomputer = new BackgroundRecomputer(viewModel).start();

        this.grid = new TickGridView(store, viewModel, ingress, GridTheme.dark(), List.of(
                GridColumn.text(SYMBOL, "SYMBOL").width(96).leftAligned(),
                GridColumn.fixed(BID, "BID", 2).width(92),
                GridColumn.fixed(ASK, "ASK", 2).width(92),
                GridColumn.fixed(LAST, "LAST", 2).width(92),
                GridColumn.decimal(CHANGE, "CHG%", 2).width(84).colorBySign(),
                GridColumn.grouped(VOLUME, "VOLUME").width(116),
                GridColumn.grouped(TRADES, "TRADES").width(92)));
        grid.start();
    }

    @Override
    public Parent node() {
        return grid;
    }

    @Override
    public void onTick(int instrument, String symbol, long bid, long ask, long last,
                       double changePct, long volume, int trades, long submitNanos) {
        final Tick t = ticks.get();
        t.symbol = symbol;
        t.bid = bid;
        t.ask = ask;
        t.last = last;
        t.changePct = changePct;
        t.volume = volume;
        t.trades = trades;
        t.submitNanos = submitNanos;
        ingress.submit(t);
    }

    @Override
    public void sample(long nowNanos, FrameStats stats) {
        final int step = Math.max(1, instruments / 200);
        for (int slot = 0; slot < instruments; slot += step) {
            if (!store.isLive(slot)) continue;
            final long stamp = store.get(slot, SUBMIT_NANOS);
            if (stamp != 0) stats.recordStaleness(nowNanos - stamp);
        }
    }

    @Override
    public String note() {
        return String.format(Locale.ROOT, "submitted %,d applied %,d  %.1f:1  backlog %,d",
                ingress.submittedCount(), ingress.appliedCount(),
                ingress.conflationRatio(), ingress.backlog());
    }

    @Override
    public void shutdown() {
        grid.stop();
        try {
            recomputer.closeAndJoin();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
