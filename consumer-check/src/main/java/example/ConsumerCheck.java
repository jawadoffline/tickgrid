package example;

import io.github.tickgrid.ingress.ConflatingIngress;
import io.github.tickgrid.ingress.RowExtractor;
import io.github.tickgrid.render.GridColumn;
import io.github.tickgrid.render.GridTheme;
import io.github.tickgrid.store.ColumnSpec;
import io.github.tickgrid.store.ColumnStore;
import io.github.tickgrid.store.Schema;
import io.github.tickgrid.view.SortSpec;
import io.github.tickgrid.view.ViewModel;

/**
 * Compiles against every package the library exports and exercises the non-UI half at runtime.
 *
 * <p>Deliberately does not open a window: this runs on CI without a display, and what it is testing
 * is that the coordinate resolves, the module descriptor is satisfiable, and the public API is
 * reachable from outside the project. A JavaFX type appears in the signatures below on purpose --
 * that is what proves JavaFX is published at compile scope rather than runtime.
 */
public final class ConsumerCheck {

    public static void main(String[] args) {
        Schema schema = Schema.builder()
                .add(ColumnSpec.dict("symbol"))
                .add(ColumnSpec.scaled("bid", 2).flashOnChange())
                .add(ColumnSpec.longs("volume"))
                .build();

        ColumnStore store = new ColumnStore(1_000, schema);
        ConflatingIngress<String[], String> ingress =
                new ConflatingIngress<>(1_000, schema.size(), new RowExtractor<>() {
                    @Override public String key(String[] row) {
                        return row[0];
                    }
                    @Override public void extract(String[] row, long[] staging, int base) {
                        staging[base] = store.dictionary().intern(row[0]);
                        staging[base + 1] = Long.parseLong(row[1]);
                        staging[base + 2] = Long.parseLong(row[2]);
                    }
                });

        for (int i = 0; i < 500; i++) {
            ingress.submit(new String[]{"SYM" + (i % 50), String.valueOf(100 + i), "1000"});
        }
        int applied = ingress.drainAll(store.applier());

        ViewModel viewModel = new ViewModel(store);
        viewModel.sortBy(SortSpec.descending(1));
        viewModel.recomputeNow();

        // Compile-time proof that JavaFX types in the public API are reachable.
        GridTheme theme = GridTheme.dark();
        GridColumn column = GridColumn.fixed(1, "BID", 2).width(90).colorBySign();

        if (applied != 50 || viewModel.snapshot().count() != 50) {
            throw new AssertionError("applied=" + applied + " rows=" + viewModel.snapshot().count());
        }
        System.out.printf("consumer-check ok: %d rows, top bid %.2f, theme flash %dms, column %s%n",
                viewModel.snapshot().count(),
                store.getScaled(viewModel.snapshot().slotAt(0), 1),
                theme.flashMillis, column.title());
    }
}
