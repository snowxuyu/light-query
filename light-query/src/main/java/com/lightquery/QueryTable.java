package com.lightquery;

import com.lightquery.lambda.SFunction;

/**
 * One named occurrence of an entity inside a query — the self-join entry
 * point. When the same entity participates more than once, plain lambdas
 * cannot say which occurrence they mean; a {@code QueryTable} names each one
 * and {@link #col(SFunction)} produces columns bound to it:
 *
 * <pre>{@code
 * QueryTable<Employee> manager = QueryTable.of(Employee.class, "mgr");
 * QueryTable<Employee> staff   = QueryTable.of(Employee.class, "staff");
 *
 * LightQuery.queryable(staff)
 *     .leftJoin(manager, on -> on.eqColumn(
 *         staff.col(Employee::getManagerId), manager.col(Employee::getId)))
 *     .eq(manager.col(Employee::getName), "Alice")
 *     .toList();
 * }</pre>
 *
 * <p>The alias must be unique within one query. Conditions referencing an
 * entity that appears more than once require {@code TableColumn}; lambdas
 * still work for entities that appear exactly once.</p>
 */
public final class QueryTable<T> {

    private final Class<T> entityType;
    private final String alias;

    private QueryTable(Class<T> entityType, String alias) {
        this.entityType = entityType;
        this.alias = alias;
    }

    /**
     * Names an occurrence of {@code entityType} inside one query.
     *
     * @throws IllegalArgumentException when {@code alias} is blank or uses a
     *         framework-reserved shape ({@code t0}, {@code s1_0}, …)
     */
    public static <T> QueryTable<T> of(Class<T> entityType, String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("QueryTable alias must not be blank");
        }
        if (alias.matches("[ts]\\d+(_\\d+)?")) {
            throw new IllegalArgumentException("QueryTable alias '" + alias
                    + "' is reserved for generated aliases — choose a descriptive name");
        }
        return new QueryTable<>(entityType, alias);
    }

    /** A column of this occurrence, usable in conditions and projections. */
    public <V> TableColumn<T, V> col(SFunction<T, V> property) {
        return new TableColumn<>(this, property, null);
    }

    public Class<T> entityType() {
        return entityType;
    }

    /** The occurrence name, unique within one query. */
    public String alias() {
        return alias;
    }
}
