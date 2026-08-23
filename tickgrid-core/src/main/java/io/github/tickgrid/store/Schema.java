package io.github.tickgrid.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** An ordered, immutable set of {@link ColumnSpec}s, plus the memory arithmetic they imply. */
public final class Schema {

    private final ColumnSpec[] columns;

    private Schema(ColumnSpec[] columns) {
        this.columns = columns;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A schema of {@code n} plain long columns. Convenience for tests and benchmarks. */
    public static Schema ofLongs(int n) {
        Builder b = builder();
        for (int i = 0; i < n; i++) b.add(ColumnSpec.longs("c" + i));
        return b.build();
    }

    public int size() {
        return columns.length;
    }

    public ColumnSpec get(int column) {
        return columns[column];
    }

    /** Index of the column with this name, or {@code -1}. */
    public int indexOf(String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].name().equals(name)) return i;
        }
        return -1;
    }

    /** Bytes of column data per row, flash stamps included. Multiply by capacity for the budget. */
    public long bytesPerRow() {
        long total = 0;
        for (ColumnSpec c : columns) total += c.bytesPerRow();
        return total;
    }

    public List<ColumnSpec> columns() {
        return List.of(columns);
    }

    @Override
    public String toString() {
        return Arrays.toString(columns) + " = " + bytesPerRow() + " B/row";
    }

    public static final class Builder {
        private final List<ColumnSpec> specs = new ArrayList<>();

        public Builder add(ColumnSpec spec) {
            for (ColumnSpec existing : specs) {
                if (existing.name().equals(spec.name())) {
                    throw new IllegalArgumentException("duplicate column name: " + spec.name());
                }
            }
            specs.add(spec);
            return this;
        }

        public Schema build() {
            if (specs.isEmpty()) throw new IllegalStateException("schema has no columns");
            return new Schema(specs.toArray(new ColumnSpec[0]));
        }
    }
}
