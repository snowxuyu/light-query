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
 * Fluent UPDATE builder:
 *
 * <pre>{@code
 * int rows = db.updatable(User.class)
 *     .set(User::getStatus, Status.FROZEN)
 *     .eq(User::getId, 5)
 *     .execute();
 * }</pre>
 *
 * <p>Conditions are ANDed at the top level; {@code and}/{@code or} groups
 * behave like in queries. A logic-delete column automatically restricts the
 * update to non-deleted rows. Executing without any condition requires the
 * explicit {@link #allowFullTable()} escape hatch.</p>
 */
public final class Updatable<T> {

    private final QueryExecutor executor;
    private final QueryModel model;
    private final Where<T> where;
    private final ColumnResolver resolver;
    private final List<SqlBuilder.SetClause> sets = new java.util.ArrayList<>();

    private boolean includeDeleted;
    private boolean allowFullTable;
    private boolean consumed;

    public Updatable(QueryExecutor executor, Class<T> entityClass) {
        this.executor = executor;
        this.model = new QueryModel(entityClass);
        // SET targets the updated entity; conditions may reference joined tables (see join(...))
        this.resolver = new ColumnResolver() {
            @Override
            public ColumnResolver.Resolved resolve(SFunction<?, ?> lambda) {
                Class<?> lambdaClass = LambdaUtils.getImplClass(lambda);
                String property = LambdaUtils.getPropertyName(lambda);
                TableRef table = model.findTable(lambdaClass);
                if (table == null) {
                    throw new SqlBuildException(lambdaClass.getSimpleName()
                            + " is not part of this update. Join it with .join(...) first "
                            + "or use properties of the updated entity "
                            + model.getRoot().getMeta().getEntityClass().getSimpleName() + ".");
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
            public ColumnResolver.Resolved resolve(TableColumn<?> column) {
                throw new SqlBuildException("TableColumn needs a multi-table query — updates are single-table; use the entity lambdas directly");
            }

            @Override
            public com.lightquery.query.model.Expr resolveAggregate(Aggregate aggregate) {
                throw new SqlBuildException("Aggregates are only valid in queryable(...).having(...)");
            }
        };
        this.where = new Where<>(model.getWhere(), resolver);
    }

    // ------------------------------------------------------------------ join

    /**
     * Joins another table for filtering this update (update join). The join
     * is an INNER join; its columns can be referenced from every condition,
     * while {@code set(...)} stays limited to the updated entity. Whether the
     * database supports UPDATE with JOIN is decided by the dialect —
     * MySQL/MariaDB and SQL Server render native join syntax, PostgreSQL
     * uses UPDATE ... FROM, Oracle and H2 do not support it.
     */
    public <J> Updatable<T> join(Class<J> target, Consumer<JoinOn<J, T>> on) {
        ensureOpen();
        TableRef joined = model.addJoin(target);
        ConditionGroup onGroup = new ConditionGroup();
        on.accept(new JoinOn<>(onGroup, resolver));
        model.getJoins().add(new JoinSpec(JoinType.INNER, joined, onGroup));
        return this;
    }

    // ------------------------------------------------------------------ SET

    public <C> Updatable<T> set(SFunction<C, ?> col, Object value) {
        ensureOpen();
        sets.add(SqlBuilder.SetClause.of(columnOf(col), metaOf(col).toDbValue(value)));
        return this;
    }

    public <C> Updatable<T> setNull(SFunction<C, ?> col) {
        ensureOpen();
        sets.add(SqlBuilder.SetClause.of(columnOf(col), null));
        return this;
    }

    /** Numeric self-increment: {@code col = col + delta} (negative delta decrements). */
    public <C> Updatable<T> setIncrement(SFunction<C, ?> col, long delta) {
        ensureOpen();
        sets.add(SqlBuilder.SetClause.increment(columnOf(col), delta));
        return this;
    }

    // ------------------------------------------------------------------ conditions (delegates to Where)

    public <C> Updatable<T> eq(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.eq(col, value);
        return this;
    }

    public <C> Updatable<T> ne(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.ne(col, value);
        return this;
    }

    public <C> Updatable<T> gt(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.gt(col, value);
        return this;
    }

    public <C> Updatable<T> ge(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.ge(col, value);
        return this;
    }

    public <C> Updatable<T> lt(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.lt(col, value);
        return this;
    }

    public <C> Updatable<T> le(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.le(col, value);
        return this;
    }

    /** Prefix match with {code \ % _} escaped. */
    public <C> Updatable<T> startsWith(SFunction<C, ?> col, String prefix) {
        ensureOpen();
        where.startsWith(col, prefix);
        return this;
    }

    /** Suffix match with {code \ % _} escaped. */
    public <C> Updatable<T> endsWith(SFunction<C, ?> col, String suffix) {
        ensureOpen();
        where.endsWith(col, suffix);
        return this;
    }

    public <C> Updatable<T> like(SFunction<C, ?> col, String contains) {
        ensureOpen();
        where.like(col, contains);
        return this;
    }

    public Updatable<T> in(SFunction<?, ?> col, Collection<?> values) {
        ensureOpen();
        where.in(col, values);
        return this;
    }

    @SafeVarargs
    public final <C> Updatable<T> in(SFunction<C, ?> col, Object... values) {
        ensureOpen();
        where.in(col, values);
        return this;
    }

    public Updatable<T> notIn(SFunction<?, ?> col, Collection<?> values) {
        ensureOpen();
        where.notIn(col, values);
        return this;
    }

    public <C> Updatable<T> isNull(SFunction<C, ?> col) {
        ensureOpen();
        where.isNull(col);
        return this;
    }

    public <C> Updatable<T> isNotNull(SFunction<C, ?> col) {
        ensureOpen();
        where.isNotNull(col);
        return this;
    }

    public <C> Updatable<T> between(SFunction<C, ?> col, Object lo, Object hi) {
        ensureOpen();
        where.between(col, lo, hi);
        return this;
    }

    public Updatable<T> and(Consumer<Where<T>> group) {
        ensureOpen();
        where.and(group);
        return this;
    }

    public Updatable<T> or(Consumer<Where<T>> group) {
        ensureOpen();
        where.or(group);
        return this;
    }

    /** Skips the automatic logic-delete restriction for this update. */
    public Updatable<T> includeDeleted() {
        ensureOpen();
        includeDeleted = true;
        return this;
    }

    /** Escape hatch that permits UPDATE without any WHERE condition. */
    public Updatable<T> allowFullTable() {
        ensureOpen();
        allowFullTable = true;
        return this;
    }

    // ------------------------------------------------------------------ terminal

    /** Renders and executes the UPDATE; returns the affected row count. */
    public int execute() {
        ensureOpen();
        return executor.execute(buildUpdate());
    }

    /** Debug rendering of the final UPDATE SQL with its parameters; consumes the builder. */
    public String toSql() {
        ensureOpen();
        SqlFragment fragment = buildUpdate();
        return fragment.sql() + " | params=" + fragment.params();
    }

    // ------------------------------------------------------------------ internals

    private SqlFragment buildUpdate() {
        if (sets.isEmpty()) {
            throw new SqlBuildException("UPDATE without any set(...) call — nothing to update");
        }
        if (model.getWhere().isEmpty() && !allowFullTable) {
            throw new SqlBuildException("UPDATE without conditions would affect the whole table. "
                    + "Add a condition or call allowFullTable() to confirm.");
        }
        consumed = true;
        if (!includeDeleted) {
            ColumnMeta logic = model.getRoot().getMeta().getLogicDeleteColumn();
            if (logic != null) {
                model.getWhere().add(Condition.of(logicColumnRef(), Operator.EQ,
                        List.of(logic.normalValueAsDb())));
            }
        }
        return model.getJoins().isEmpty()
                ? SqlBuilder.update(model, sets, executor.dialect())
                : SqlBuilder.updateWithJoin(model, sets, executor.dialect());
    }

    // ------------------------------------------------------------------ internals

    private String columnOf(SFunction<?, ?> col) {
        ColumnMeta meta = metaOf(col);
        if (meta.isPrimaryKey()) {
            throw new SqlBuildException("set(...) cannot modify primary key column '"
                    + meta.getColumnName() + "'");
        }
        return meta.getColumnName();
    }

    private ColumnMeta metaOf(SFunction<?, ?> col) {
        Class<?> lambdaClass = LambdaUtils.getImplClass(col);
        Class<?> rootClass = model.getRoot().getEntityClass();
        if (!rootClass.isAssignableFrom(lambdaClass)) {
            throw new SqlBuildException("set(...) modifies the updated entity only — '"
                    + lambdaClass.getSimpleName()
                    + "' columns are read-only here. Reference joined tables in conditions instead.");
        }
        String property = LambdaUtils.getPropertyName(col);
        ColumnMeta meta = model.getRoot().getMeta().byProperty(property);
        if (meta == null) {
            throw new SqlBuildException(model.getRoot().getMeta().getEntityClass().getSimpleName()
                    + " has no mapped property '" + property + "'");
        }
        return meta;
    }

    private ColumnRef logicColumnRef() {
        ColumnMeta logic = model.getRoot().getMeta().getLogicDeleteColumn();
        return new ColumnRef(model.getRoot().getEntityClass(), logic.getColumnName());
    }

    private void ensureOpen() {
        if (consumed) {
            throw new SqlBuildException("This Updatable was already executed. "
                    + "Create a new one via db.updatable(...).");
        }
    }
}
