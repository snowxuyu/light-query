package com.lightquery.query.model;

/**
 * A column of a specific entity participating in the query. The owning
 * entity (not its alias) is stored; the renderer resolves the alias through
 * the active table registry. A self-join occurrence is addressed through
 * {@code tableAlias} (from {@code QueryTable}); {@code outputLabel} labels
 * the column in a SELECT list ({@code AS}, doubles as the Tuple key).
 *
 * @param entity      the entity class the column belongs to
 * @param column      the physical column name
 * @param tableAlias  the explicit table occurrence, or null for class lookup
 * @param outputLabel the SELECT-list label, or null to render bare
 */
public record ColumnRef(Class<?> entity, String column, String tableAlias, String outputLabel)
        implements Selectable {

    public ColumnRef(Class<?> entity, String column) {
        this(entity, column, null, null);
    }

    public ColumnRef(Class<?> entity, String column, String tableAlias) {
        this(entity, column, tableAlias, null);
    }

    /** A copy labelled with {@code label} for the SELECT list / Tuple key. */
    public ColumnRef labelled(String label) {
        return new ColumnRef(entity, column, tableAlias, label);
    }
}
