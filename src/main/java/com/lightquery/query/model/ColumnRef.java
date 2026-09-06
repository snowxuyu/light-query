package com.lightquery.query.model;

/**
 * A column of a specific entity participating in the query. The owning
 * entity (not its alias) is stored; the renderer resolves the alias through
 * the active table registry.
 *
 * @param entity the entity class the column belongs to
 * @param column the physical column name
 */
public record ColumnRef(Class<?> entity, String column) implements Selectable {
}
