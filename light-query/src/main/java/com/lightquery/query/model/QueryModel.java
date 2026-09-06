package com.lightquery.query.model;

import com.lightquery.QueryTable;
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

    /** Root with an explicit occurrence alias (self-join root, via QueryTable). */
    public QueryModel(QueryTable<?> rootTable) {
        this.root = TableRef.of(rootTable.entityType(), rootTable.alias());
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
                    + " is already part of this query. To join the same entity twice "
                    + "(self-join), name each occurrence with QueryTable.of(entity, alias) "
                    + "and use the join(QueryTable, on) overloads.");
        }
        TableRef ref = TableRef.of(targetEntity);
        joinedTables.add(ref);
        return ref;
    }

    /**
     * Registers a join to an explicitly named occurrence (self-join). The
     * alias must be unique within the query; the same entity may join several
     * times under different aliases.
     */
    public TableRef addJoin(QueryTable<?> target) {
        TableRef ref = TableRef.of(target.entityType(), target.alias());
        if (findTableByAlias(target.alias()) != null) {
            throw new SqlBuildException("Table alias '" + target.alias()
                    + "' is already used in this query — each QueryTable needs a unique alias");
        }
        joinedTables.add(ref);
        return ref;
    }

    /** Finds the occurrence registered under an explicit alias (self-join). */
    public TableRef findTableByAlias(String alias) {
        for (TableRef ref : getTables()) {
            if (alias.equals(ref.getExplicitAlias())) {
                return ref;
            }
        }
        return null;
    }

    /**
     * Finds the table of this level owning the given lambda implementation
     * class: exact match first, then assignability (inherited getters).
     * Throws when the class owns several occurrences (self-join) — lambda
     * conditions cannot say which one they mean.
     */
    public TableRef findTable(Class<?> lambdaClass) {
        List<TableRef> exact = new ArrayList<>();
        for (TableRef ref : getTables()) {
            if (ref.getEntityClass() == lambdaClass) {
                exact.add(ref);
            }
        }
        if (exact.isEmpty()) {
            for (TableRef ref : getTables()) {
                if (ref.getEntityClass().isAssignableFrom(lambdaClass)) {
                    exact.add(ref);
                }
            }
        }
        if (exact.size() > 1) {
            throw new SqlBuildException(lambdaClass.getSimpleName()
                    + " participates in this query more than once — a plain lambda cannot say "
                    + "which occurrence it means. Use QueryTable.of(...) with table.col(...) "
                    + "(TableColumn) for every reference to it.");
        }
        return exact.isEmpty() ? null : exact.get(0);
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
        return !joins.isEmpty() || usesSubQueries || hasExplicitAliases();
    }

    private boolean hasExplicitAliases() {
        for (TableRef ref : getTables()) {
            if (ref.getExplicitAlias() != null) {
                return true;
            }
        }
        return false;
    }
}
