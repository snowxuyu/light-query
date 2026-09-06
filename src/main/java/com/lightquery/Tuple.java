package com.lightquery;

import java.util.List;
import java.util.Map;

/**
 * One row of a projected/grouped query (see {@code Queryable#toTupleList()}).
 * Values are keyed by the label used in the projection: the column name for
 * plain columns, the alias for {@code F.xxx(col).as("alias")} expressions.
 */
public final class Tuple {

    private final List<String> labels;
    private final List<Object> values;
    private final Map<String, Integer> index;

    public Tuple(List<String> labels, List<Object> values, Map<String, Integer> index) {
        this.labels = List.copyOf(labels);
        this.values = List.copyOf(values);
        this.index = Map.copyOf(index);
    }

    /** Raw value by label. */
    public Object get(String label) {
        Integer position = index.get(label);
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
