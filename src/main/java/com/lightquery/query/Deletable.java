package com.lightquery.query;

import com.lightquery.Agg;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.lambda.LambdaUtils;
import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.Op;
import com.lightquery.query.model.QueryModel;
import com.lightquery.sqlgen.SqlBuilder;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent DELETE builder:
 *
 * <pre>{@code
 * db.deletable(User.class).eq(User::getStatus, 0).execute();   // logic delete
 * db.deletable(User.class).physical().eq(User::getStatus, 0).execute(); // hard delete
 * }</pre>
 *
 * <p>When the entity declares {@code @LogicDelete}, execute() becomes an
 * UPDATE setting the deleted marker; {@link #physical()} forces a real
 * DELETE. Running without conditions requires {@link #allowFullTable()}.</p>
 */
public final class Deletable<T> {

    private final QueryExecutor executor;
    private final QueryModel model;
    private final Where<T> where;

    private boolean physical;
    private boolean allowFullTable;
    private boolean consumed;

    public Deletable(QueryExecutor executor, Class<T> entityClass) {
        this.executor = executor;
        this.model = new QueryModel(entityClass);
        // deletes are single-table: every lambda must resolve to the root entity
        ColumnResolver resolver = new ColumnResolver() {
            @Override
            public ColumnResolver.Resolved resolve(SFunction<?, ?> fn) {
                String property = LambdaUtils.getPropertyName(fn);
                ColumnMeta meta = Deletable.this.model.getRoot().getMeta().byProperty(property);
                if (meta == null) {
                    throw new SqlBuildException(entityClass.getSimpleName()
                            + " has no mapped property '" + property + "'");
                }
                return new ColumnResolver.Resolved(
                        new ColumnRef(entityClass, meta.getColumnName()), meta);
            }

            @Override
            public com.lightquery.query.model.Expr resolveAgg(Agg agg) {
                throw new SqlBuildException("Aggregates are only valid in queryable(...).having(...)");
            }
        };
        this.where = new Where<>(model.getWhere(), resolver);
    }

    // ------------------------------------------------------------------ behaviour

    /** Forces a physical DELETE even when the entity has a logic-delete column. */
    public Deletable<T> physical() {
        ensureOpen();
        physical = true;
        return this;
    }

    /** Escape hatch that permits DELETE without any WHERE condition. */
    public Deletable<T> allowFullTable() {
        ensureOpen();
        allowFullTable = true;
        return this;
    }

    // ------------------------------------------------------------------ conditions (delegates to Where)

    public <C> Deletable<T> eq(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.eq(col, value);
        return this;
    }

    public <C> Deletable<T> ne(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.ne(col, value);
        return this;
    }

    public <C> Deletable<T> gt(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.gt(col, value);
        return this;
    }

    public <C> Deletable<T> ge(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.ge(col, value);
        return this;
    }

    public <C> Deletable<T> lt(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.lt(col, value);
        return this;
    }

    public <C> Deletable<T> le(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.le(col, value);
        return this;
    }

    /** Prefix match with {code \ % _} escaped. */
    public <C> Deletable<T> startsWith(SFunction<C, ?> col, String prefix) {
        ensureOpen();
        where.startsWith(col, prefix);
        return this;
    }

    /** Suffix match with {code \ % _} escaped. */
    public <C> Deletable<T> endsWith(SFunction<C, ?> col, String suffix) {
        ensureOpen();
        where.endsWith(col, suffix);
        return this;
    }

    public <C> Deletable<T> like(SFunction<C, ?> col, String contains) {
        ensureOpen();
        where.like(col, contains);
        return this;
    }

    public Deletable<T> in(SFunction<?, ?> col, Collection<?> values) {
        ensureOpen();
        where.in(col, values);
        return this;
    }

    @SafeVarargs
    public final <C> Deletable<T> in(SFunction<C, ?> col, Object... values) {
        ensureOpen();
        where.in(col, values);
        return this;
    }

    public Deletable<T> notIn(SFunction<?, ?> col, Collection<?> values) {
        ensureOpen();
        where.notIn(col, values);
        return this;
    }

    public <C> Deletable<T> isNull(SFunction<C, ?> col) {
        ensureOpen();
        where.isNull(col);
        return this;
    }

    public <C> Deletable<T> isNotNull(SFunction<C, ?> col) {
        ensureOpen();
        where.isNotNull(col);
        return this;
    }

    public <C> Deletable<T> between(SFunction<C, ?> col, Object lo, Object hi) {
        ensureOpen();
        where.between(col, lo, hi);
        return this;
    }

    public Deletable<T> and(Consumer<Where<T>> group) {
        ensureOpen();
        where.and(group);
        return this;
    }

    public Deletable<T> or(Consumer<Where<T>> group) {
        ensureOpen();
        where.or(group);
        return this;
    }

    /** Overrides the physical table name for this delete (sharding). */
    public Deletable<T> asTable(String physicalTableName) {
        ensureOpen();
        model.getRoot().rename(physicalTableName);
        return this;
    }

    // ------------------------------------------------------------------ terminal

    public int execute() {
        ensureOpen();
        if (model.getWhere().isEmpty() && !allowFullTable) {
            throw new SqlBuildException("DELETE without conditions would empty the whole table. "
                    + "Add a condition or call allowFullTable() to confirm.");
        }
        consumed = true;
        ColumnMeta logic = model.getRoot().getMeta().getLogicDeleteColumn();
        int rows;
        if (logic != null && !physical) {
            SqlBuilder.SetClause mark = SqlBuilder.SetClause.of(
                    logic.getColumnName(), logic.deletedValueAsDb());
            rows = executor.execute(SqlBuilder.update(model, List.of(mark), executor.dialect()));
        } else {
            rows = executor.execute(SqlBuilder.delete(model, executor.dialect()));
        }
        return rows;
    }

    // ------------------------------------------------------------------ internals

    private void ensureOpen() {
        if (consumed) {
            throw new SqlBuildException("This Deletable was already executed. "
                    + "Create a new one via db.deletable(...).");
        }
    }
}
