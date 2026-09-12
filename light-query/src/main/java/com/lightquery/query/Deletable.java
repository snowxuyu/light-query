package com.lightquery.query;

import com.lightquery.Aggregate;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.lambda.LambdaUtils;
import com.lightquery.TableColumn;
import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.JoinSpec;
import com.lightquery.query.model.JoinType;
import com.lightquery.query.model.Operator;
import com.lightquery.query.model.QueryModel;
import com.lightquery.query.model.TableRef;
import com.lightquery.sqlgen.SqlBuilder;
import com.lightquery.sqlgen.SqlFragment;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent DELETE builder:
 *
 * <pre>{@code
 * db.deletable(User.class).col(User::getStatus).eq(0).execute();   // logic delete
 * db.deletable(User.class).physical().col(User::getStatus).eq(0).execute(); // hard delete
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
    private final ColumnResolver resolver;

    private boolean physical;
    private boolean allowFullTable;
    private boolean consumed;

    public Deletable(QueryExecutor executor, Class<T> entityClass) {
        this.executor = executor;
        this.model = new QueryModel(entityClass);
        // conditions may reference the root entity plus any joined table (see join(...))
        this.resolver = new ColumnResolver() {
            @Override
            public ColumnResolver.Resolved resolve(SFunction<?, ?> lambda) {
                Class<?> lambdaClass = LambdaUtils.getImplClass(lambda);
                String property = LambdaUtils.getPropertyName(lambda);
                TableRef table = model.findTable(lambdaClass);
                if (table == null) {
                    throw new SqlBuildException(lambdaClass.getSimpleName()
                            + " is not part of this delete. Join it with .join(...) first "
                            + "or use properties of the deleted entity "
                            + entityClass.getSimpleName() + ".");
                }
                ColumnMeta meta = table.getMeta().byProperty(property);
                if (meta == null) {
                    throw new SqlBuildException(lambdaClass.getSimpleName()
                            + " has no mapped property '" + property + "'");
                }
                return new ColumnResolver.Resolved(
                        new ColumnRef(table.getEntityClass(), meta.getColumnName()), meta);
            }

            @Override
            public ColumnResolver.Resolved resolve(TableColumn<?, ? > column) {
                throw new SqlBuildException("TableColumn needs a multi-table query — deletes are single-table; use the entity lambdas directly");
            }

            @Override
            public com.lightquery.query.model.Expr resolveAggregate(Aggregate aggregate) {
                throw new SqlBuildException("Aggregates are only valid in queryable(...).having(...)");
            }
        };
        this.where = new Where<>(model.getWhere(), resolver, model::markUsesSubQueries);
    }

    // ------------------------------------------------------------------ behaviour

    /**
     * Joins another table for filtering this delete (delete join). The join
     * is an INNER join; its columns can be referenced from every condition.
     * Whether the database supports DELETE with JOIN is decided by the
     * dialect — MySQL/MariaDB and SQL Server render native multi-table
     * syntax, PostgreSQL uses DELETE ... USING, Oracle and H2 do not support
     * it. Logic delete remains an UPDATE (which then joins as well).
     */
    public <J> Deletable<T> join(Class<J> target, Consumer<JoinOn<J, T>> on) {
        ensureOpen();
        TableRef joined = model.addJoin(target);
        ConditionGroup onGroup = new ConditionGroup();
        on.accept(new JoinOn<>(onGroup, resolver, model::markUsesSubQueries));
        model.getJoins().add(new JoinSpec(JoinType.INNER, joined, onGroup));
        return this;
    }

    /**
     * Starts a strongly-typed condition on a property — the value type of
     * the property lambda drives the condition, e.g.
     * {@code .col(User::getId).eq(5L)}.
     */
    public <C, V> TypedColumn<Deletable<T>, V> col(SFunction<C, V> col) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolver.resolve(col);
        return new TypedColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /** Forces a physical DELETE even when the entity has a logic-delete column. */
    public Deletable<T> physical() {
        ensureOpen();
        physical = true;
        return this;
    }

    /** Adds a parenthesised AND group. */
    public Deletable<T> and(Consumer<Where<T>> group) {
        ensureOpen();
        where.and(group);
        return this;
    }

    /** Adds a parenthesised group joined by OR. */
    public Deletable<T> or(Consumer<Where<T>> group) {
        ensureOpen();
        where.or(group);
        return this;
    }

    /** Escape hatch that permits DELETE without any WHERE condition. */
    public Deletable<T> allowFullTable() {
        ensureOpen();
        allowFullTable = true;
        return this;
    }

    /** Overrides the physical table name for this delete (sharding). */
    public Deletable<T> asTable(String physicalTableName) {
        ensureOpen();
        model.getRoot().rename(physicalTableName);
        return this;
    }

    // ------------------------------------------------------------------ terminal

    /** Renders and executes the DELETE (or the logic-delete UPDATE); returns the affected row count. */
    public int execute() {
        ensureOpen();
        return executor.execute(buildDelete());
    }

    /** Debug rendering of the final DELETE SQL with its parameters; consumes the builder. */
    public String toSql() {
        ensureOpen();
        SqlFragment fragment = buildDelete();
        return fragment.sql() + " | params=" + fragment.params();
    }

    // ------------------------------------------------------------------ internals

    private SqlFragment buildDelete() {
        if (model.getWhere().isEmpty() && !allowFullTable) {
            throw new SqlBuildException("DELETE without conditions would empty the whole table. "
                    + "Add a condition or call allowFullTable() to confirm.");
        }
        consumed = true;
        ColumnMeta logic = model.getRoot().getMeta().getLogicDeleteColumn();
        boolean logicDelete = logic != null && !physical;
        if (model.getJoins().isEmpty()) {
            if (logicDelete) {
                SqlBuilder.SetClause mark = SqlBuilder.SetClause.of(
                        logic.getColumnName(), logic.deletedValueAsDb());
                return SqlBuilder.update(model, List.of(mark), executor.dialect());
            }
            return SqlBuilder.delete(model, executor.dialect());
        }
        if (logicDelete) {
            SqlBuilder.SetClause mark = SqlBuilder.SetClause.of(
                    logic.getColumnName(), logic.deletedValueAsDb());
            return SqlBuilder.updateWithJoin(model, List.of(mark), executor.dialect());
        }
        return SqlBuilder.deleteWithJoin(model, executor.dialect());
    }

    // ------------------------------------------------------------------ internals

    private void ensureOpen() {
        if (consumed) {
            throw new SqlBuildException("This Deletable was already executed. "
                    + "Create a new one via db.deletable(...).");
        }
    }
}
