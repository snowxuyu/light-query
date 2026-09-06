package com.lightquery.query.model;

import com.lightquery.exception.MappingException;
import com.lightquery.exception.SqlBuildException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dialect-independent representation of one SELECT query level. A queryable
 * owns one model; sub-queries own their own models and are referenced from
 * {@link Condition#getSubQuery()}.
 *
 * <p>Alias assignment is deliberately left to the renderer: models stay
 * reusable and alias naming lives in one place ({@code SqlBuilder}).</p>
 */
public final class QueryModel {

    private final TableRef root;
    private final List<TableRef> joinedTables = new ArrayList<>();
    private final List<JoinSpec> joins = new ArrayList<>();
    private final List<Selectable> selectExprs = new ArrayList<>();
    private boolean distinct;
    private final ConditionGroup where = new ConditionGroup();
    private final List<Selectable> groupBys = new ArrayList<>();
    private final ConditionGroup having = new ConditionGroup();
    private final List<OrderBy> orderBys = new ArrayList<>();
    private Long offset;
    private Long limit;
    private boolean forUpdate;
    private boolean usesSubQueries;
    /** Rendered as {@code SELECT 1} (used by the exists() terminal). */
    private boolean existsProbe;

    public QueryModel(Class<?> rootEntity) {
        this.root = TableRef.of(rootEntity);
    }

    // ------------------------------------------------------------------ structure

    public TableRef getRoot() {
        return root;
    }

    public List<JoinSpec> getJoins() {
        return joins;
    }

    /** All tables of this level in registration order (root first). */
    public List<TableRef> getTables() {
        List<TableRef> tables = new ArrayList<>();
        tables.add(root);
        tables.addAll(joinedTables);
        return tables;
    }

    /**
     * Registers a join target table. The caller completes registration by
     * appending the matching {@link JoinSpec} to {@link #getJoins()}.
     */
    public TableRef addJoin(Class<?> targetEntity) {
        if (findTable(targetEntity) != null) {
            throw new SqlBuildException(targetEntity.getSimpleName()
                    + " is already part of this query. Joining the same entity twice "
                    + "(self-join) is not supported in v0.1.");
        }
        TableRef ref = TableRef.of(targetEntity);
        joinedTables.add(ref);
        return ref;
    }

    /**
     * Finds the table of this level owning the given lambda implementation
     * class: exact match first, then assignability (inherited getters).
     */
    public TableRef findTable(Class<?> lambdaClass) {
        for (TableRef ref : getTables()) {
            if (ref.getEntityClass() == lambdaClass) {
                return ref;
            }
        }
        for (TableRef ref : getTables()) {
            if (ref.getEntityClass().isAssignableFrom(lambdaClass)) {
                return ref;
            }
        }
        return null;
    }

    /** Resolves a property of this model's tables to a {@link ColumnRef}. */
    public ColumnRef resolveColumn(Class<?> lambdaClass, String propertyName) {
        TableRef table = findTable(lambdaClass);
        if (table == null) {
            throw new SqlBuildException("Property source " + lambdaClass.getName()
                    + " is not part of this query. Tables in scope: " + tableSummary()
                    + ". Did you forget to join?");
        }
        com.lightquery.meta.ColumnMeta meta = table.getMeta().byProperty(propertyName);
        if (meta == null) {
            throw new MappingException(lambdaClass.getSimpleName() + " has no mapped property '"
                    + propertyName + "'");
        }
        return new ColumnRef(table.getEntityClass(), meta.getColumnName());
    }

    private String tableSummary() {
        return getTables().stream()
                .map(t -> t.getMeta().getEntityClass().getSimpleName())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Same as {@link #tableSummary()} but readable from the query package. */
    public String describeTables() {
        return tableSummary();
    }

    // ------------------------------------------------------------------ flags & parts

    public List<Selectable> getSelectExprs() {
        return selectExprs;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    public ConditionGroup getWhere() {
        return where;
    }

    public List<Selectable> getGroupBys() {
        return groupBys;
    }

    public ConditionGroup getHaving() {
        return having;
    }

    public List<OrderBy> getOrderBys() {
        return orderBys;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public Long getLimit() {
        return limit;
    }

    public void setLimit(Long limit) {
        this.limit = limit;
    }

    public boolean isForUpdate() {
        return forUpdate;
    }

    public void setForUpdate(boolean forUpdate) {
        this.forUpdate = forUpdate;
    }

    public boolean isUsesSubQueries() {
        return usesSubQueries;
    }

    public void markUsesSubQueries() {
        this.usesSubQueries = true;
    }

    public boolean isExistsProbe() {
        return existsProbe;
    }

    public void setExistsProbe(boolean existsProbe) {
        this.existsProbe = existsProbe;
    }

    /** True when column references must be qualified with table aliases. */
    public boolean needsAliases() {
        return !joins.isEmpty() || usesSubQueries;
    }
}
