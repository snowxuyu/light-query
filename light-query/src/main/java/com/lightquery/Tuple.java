package com.lightquery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of a projected/grouped query (see {@code Queryable#toTupleList()}).
 * Values are keyed by the label used in the projection: the column name for
 * plain columns, the alias for {@code Aggregations.xxx(col).as("alias")} expressions.
 */
public final class Tuple {

    private final List<String> labels;
    private final List<Object> values;
    private final Map<String, Integer> positionsByLabel;

    /** Builds a tuple; when a label repeats, the first occurrence wins. */
    public Tuple(List<String> labels, List<Object> values) {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(values, "values");
        this.labels = List.copyOf(labels);
        // values may contain NULL column values — use a null-tolerant list
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        if (this.labels.size() != this.values.size()) {
            throw new IllegalArgumentException("labels and values must have the same size, got "
                    + this.labels.size() + "/" + this.values.size());
        }
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < this.labels.size(); i++) {
            positions.putIfAbsent(this.labels.get(i), i);
        }
        this.positionsByLabel = Map.copyOf(positions);
    }

    /** Raw value by label. */
    public Object get(String label) {
        Integer position = positionsByLabel.get(label);
        if (position == null) {
            throw new IllegalArgumentException("No label '" + label + "' in tuple. Available: " + labels);
        }
        return values.get(position);
    }

    /** Typed value by label — the same check the compiler would do for a getter. */
    public <V> V get(String label, Class<V> type) {
        Object value = get(label);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Label '" + label + "' is " + value.getClass().getName()
                    + ", requested " + type.getName());
        }
        return type.cast(value);
    }

    /** Raw value by position (0-based, SELECT list order). */
    public Object get(int position) {
        return values.get(position);
    }

    public List<String> labels() {
        return labels;
    }

    public int size() {
        return values.size();
    }
}
