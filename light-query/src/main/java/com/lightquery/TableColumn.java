package com.lightquery;

import com.lightquery.lambda.SFunction;

/**
 * A column bound to one {@link QueryTable} occurrence — how conditions and
 * projections address a table that participates more than once in a query.
 * Created via {@code queryTable.col(Entity::getProperty)}; {@link #as(String)}
 * labels the column for the SELECT list (the Tuple key).
 *
 * @param <T> the entity type of the occurrence
 * @param <V> the value type of the property (drives the strongly-typed conditions)
 */
public final class TableColumn<T, V> {

    private final QueryTable<T> table;
    private final SFunction<T, V> property;
    private final String outputLabel;

    TableColumn(QueryTable<T> table, SFunction<T, V> property, String outputLabel) {
        this.table = table;
        this.property = property;
        this.outputLabel = outputLabel;
    }

    /** A copy labelled with {@code label} for the SELECT list / Tuple key. */
    public TableColumn<T, V> as(String label) {
        return new TableColumn<>(table, property, label);
    }

    public QueryTable<T> table() {
        return table;
    }

    public SFunction<T, V> property() {
        return property;
    }

    /** The SELECT-list label, or null to use the plain column name. */
    public String outputLabel() {
        return outputLabel;
    }
}
