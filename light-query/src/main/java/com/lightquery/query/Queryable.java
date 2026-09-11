package com.lightquery.query;

import com.lightquery.Aggregate;
import com.lightquery.PageResult;
import com.lightquery.QueryTable;
import com.lightquery.TableColumn;
import com.lightquery.Tuple;
import com.lightquery.exception.MappingException;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.exec.JdbcExecutor;
import com.lightquery.exec.RowProjector;
import com.lightquery.lambda.LambdaUtils;
import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.meta.EntityMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Expr;
import com.lightquery.query.model.JoinSpec;
import com.lightquery.query.model.JoinType;
import com.lightquery.query.model.Operator;
import com.lightquery.query.model.OrderBy;
import com.lightquery.query.model.QueryModel;
import com.lightquery.query.model.TableRef;
import com.lightquery.sqlgen.SqlBuilder;
import com.lightquery.sqlgen.SqlFragment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent, type-safe query over one entity, e.g.:
 *
 * <pre>{@code
 * List<User> users = LightQuery.queryable(User.class)
 *     .col(User::getStatus).eq(Status.ACTIVE)
 *     .and(w -> w.col(User::getName).like("frank").or().col(User::getAge).ge(18))
 *     .orderByDesc(User::getCreateTime)
 *     .limit(10)
 *     .toList();
 * }</pre>
 *
 * <p>A Queryable is a one-shot builder: every terminal method
 * ({@link #toList()}, {@link #count()}, ...) consumes it; build a new one
 * for the next query. Instances are not thread-safe — the LightQuerySession is.</p>
 *
 * @param <T> the root entity type
 */
public final class Queryable<T> {

    private final QueryExecutor executor;
    private final Class<T> entityClass;
    private final QueryModel model;
    private final Where<T> where;
    private final ColumnResolver resolver;

    private boolean includeDeleted;
    private boolean prepared;
    private boolean consumed;

    public Queryable(QueryExecutor executor, Class<T> entityClass) {
        this(executor, new QueryModel(entityClass), entityClass);
    }

    /** Query rooted at an explicitly named occurrence (self-join root). */
    public Queryable(QueryExecutor executor, QueryTable<T> rootTable) {
        this(executor, new QueryModel(rootTable), rootTable.entityType());
    }

    private Queryable(QueryExecutor executor, QueryModel model, Class<T> entityClass) {
        this.executor = executor;
        this.entityClass = entityClass;
        this.model = model;
        this.resolver = new ColumnResolver() {
            @Override
            public ColumnResolver.Resolved resolve(SFunction<?, ?> lambda) {
                return Queryable.this.resolve(lambda);
            }

            @Override
            public ColumnResolver.Resolved resolve(TableColumn<?, ? > column) {
                return Queryable.this.resolve(column);
            }

            @Override
            public Expr resolveAggregate(Aggregate aggregate) {
                return Queryable.this.resolveAggregate(aggregate);
            }
        };
        this.where = new Where<>(model.getWhere(), resolver, model::markUsesSubQueries);
    }

    // ------------------------------------------------------------------ strongly-typed conditions

    /**
     * Starts a strongly-typed condition on a property — the value type of
     * the property lambda drives the condition, e.g.
     * {@code .col(User::getAge).ge(18)}. The property may belong to any
     * table in scope (root or joined).
     */
    public <C, V> TypedColumn<Queryable<T>, V> col(SFunction<C, V> col) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(col);
        return new TypedColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /**
     * Starts a range condition on a {@link Comparable} property, e.g.
     * {@code .cmpCol(User::getAge).ge(18)}. Non-comparable properties do not
     * compile here — use {@link #col(SFunction)} for equality.
     */
    public <C, V extends Comparable<V>> ComparableColumn<Queryable<T>, V> cmpCol(SFunction<C, V> col) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(col);
        return new ComparableColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /**
     * Starts a text-match condition on a {@code String} property, e.g.
     * {@code .strCol(User::getName).like("frank")}.
     */
    public <C> StringColumn<Queryable<T>> strCol(SFunction<C, String> col) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(col);
        return new StringColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /**
     * Starts a numeric condition on a {@link Number} property, e.g.
     * {@code .numCol(Order::getAmount).gt(new BigDecimal("100"))}.
     */
    public <C, V extends Number & Comparable<V>> NumberColumn<Queryable<T>, V> numCol(SFunction<C, V> col) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(col);
        return new NumberColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /** Strongly-typed condition on a self-join occurrence column. */
    public <V> TypedColumn<Queryable<T>, V> col(TableColumn<?, V> column) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(column);
        return new TypedColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /** Range condition on a self-join occurrence column of comparable type. */
    public <V extends Comparable<V>> ComparableColumn<Queryable<T>, V> cmpCol(TableColumn<?, V> column) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(column);
        return new ComparableColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /** Text-match condition on a self-join occurrence column of string type. */
    public StringColumn<Queryable<T>> strCol(TableColumn<?, String> column) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(column);
        return new StringColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /** Numeric condition on a self-join occurrence column of numeric type. */
    public <V extends Number & Comparable<V>> NumberColumn<Queryable<T>, V> numCol(TableColumn<?, V> column) {
        ensureOpen();
        ColumnResolver.Resolved resolved = resolve(column);
        return new NumberColumn<>(this, model.getWhere(), resolved.ref(), resolved.meta(), resolver, model::markUsesSubQueries);
    }

    /** Adds a parenthesised AND group. */
    public Queryable<T> and(Consumer<Where<T>> group) {
        ensureOpen();
        where.and(group);
        return this;
    }

    /** Adds a parenthesised group joined by OR: {@code ... OR (a AND b)}. */
    public Queryable<T> or(Consumer<Where<T>> group) {
        ensureOpen();
        where.or(group);
        return this;
    }

    // ------------------------------------------------------------------ sub-queries

    /** {@code col IN (SELECT …)} — correlated references to outer entities work. */
    public <C, V> Queryable<T> in(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.IN, subQuery);
    }

    public <C, V> Queryable<T> notIn(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.NOT_IN, subQuery);
    }

    /** Scalar sub-query comparison: {@code col = (SELECT …)}. */
    public <C, V> Queryable<T> eqSubQuery(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.EQ, subQuery);
    }

    public <C, V> Queryable<T> neSubQuery(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.NE, subQuery);
    }

    public <C, V> Queryable<T> gtSubQuery(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.GT, subQuery);
    }

    public <C, V> Queryable<T> geSubQuery(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.GE, subQuery);
    }

    public <C, V> Queryable<T> ltSubQuery(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.LT, subQuery);
    }

    public <C, V> Queryable<T> leSubQuery(SFunction<C, V> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Operator.LE, subQuery);
    }

    /** {@code EXISTS (SELECT …)} — use {@code eqColumn} inside for correlation. */
    public Queryable<T> whereExists(Queryable<?> subQuery) {
        ensureOpen();
        subQuery.ensureOpen();
        model.markUsesSubQueries();
        where.addExternal(Condition.exists(subQuery.model, false));
        return this;
    }

    public Queryable<T> whereNotExists(Queryable<?> subQuery) {
        ensureOpen();
        subQuery.ensureOpen();
        model.markUsesSubQueries();
        where.addExternal(Condition.exists(subQuery.model, true));
        return this;
    }

    private Queryable<T> addSubQueryCondition(SFunction<?, ?> col, Operator operator, Queryable<?> subQuery) {
        ensureOpen();
        subQuery.ensureOpen();
        model.markUsesSubQueries();
        where.addExternal(Condition.ofSubQuery(ref(col), operator, subQuery.model));
        return this;
    }

    // ------------------------------------------------------------------ joins

    public <J> Queryable<T> innerJoin(Class<J> target, Consumer<JoinOn<J, T>> on) {
        return join(JoinType.INNER, target, on);
    }

    public <J> Queryable<T> leftJoin(Class<J> target, Consumer<JoinOn<J, T>> on) {
        return join(JoinType.LEFT, target, on);
    }

    public <J> Queryable<T> rightJoin(Class<J> target, Consumer<JoinOn<J, T>> on) {
        return join(JoinType.RIGHT, target, on);
    }

    /** Joins an explicitly named occurrence — the self-join entry point. */
    public <J> Queryable<T> innerJoin(QueryTable<J> target, Consumer<JoinOn<J, T>> on) {
        return join(JoinType.INNER, target, on);
    }

    public <J> Queryable<T> leftJoin(QueryTable<J> target, Consumer<JoinOn<J, T>> on) {
        return join(JoinType.LEFT, target, on);
    }

    public <J> Queryable<T> rightJoin(QueryTable<J> target, Consumer<JoinOn<J, T>> on) {
        return join(JoinType.RIGHT, target, on);
    }

    private <J> Queryable<T> join(JoinType type, Class<J> target, Consumer<JoinOn<J, T>> on) {
        ensureOpen();
        TableRef joined = model.addJoin(target);
        ConditionGroup onGroup = new ConditionGroup();
        on.accept(new JoinOn<>(onGroup, resolver, model::markUsesSubQueries));
        model.getJoins().add(new JoinSpec(type, joined, onGroup));
        return this;
    }

    private <J> Queryable<T> join(JoinType type, QueryTable<J> target, Consumer<JoinOn<J, T>> on) {
        ensureOpen();
        TableRef joined = model.addJoin(target);
        ConditionGroup onGroup = new ConditionGroup();
        on.accept(new JoinOn<>(onGroup, resolver, model::markUsesSubQueries));
        model.getJoins().add(new JoinSpec(type, joined, onGroup));
        return this;
    }

    // ------------------------------------------------------------------ shape

    /** Restricts the projection; with joins, columns of any joined entity are allowed. */
    @SafeVarargs
    public final <C> Queryable<T> select(SFunction<C, ?>... cols) {
        ensureOpen();
        for (SFunction<?, ?> col : cols) {
            model.getSelectExprs().add(ref(col));
        }
        return this;
    }

    /** Aggregate projection, e.g. {@code select(Aggregations.count().as("cnt"))}. */
    public Queryable<T> select(Aggregate... exprs) {
        ensureOpen();
        for (Aggregate expr : exprs) {
            model.getSelectExprs().add(resolveAggregate(expr));
        }
        return this;
    }

    /** Occurrence-bound projection (self-join), e.g. {@code select(manager.col(Employee::getName))}. */
    public Queryable<T> select(TableColumn<?, ? >... columns) {
        ensureOpen();
        for (TableColumn<?, ? > column : columns) {
            model.getSelectExprs().add(resolve(column).ref());
        }
        return this;
    }

    public Queryable<T> distinct() {
        ensureOpen();
        model.setDistinct(true);
        return this;
    }

    @SafeVarargs
    public final <C> Queryable<T> groupBy(SFunction<C, ?>... cols) {
        ensureOpen();
        for (SFunction<?, ?> col : cols) {
            model.getGroupBys().add(ref(col));
        }
        return this;
    }

    /** Occurrence-bound grouping (self-join). */
    public Queryable<T> groupBy(TableColumn<?, ? >... columns) {
        ensureOpen();
        for (TableColumn<?, ? > column : columns) {
            model.getGroupBys().add(resolve(column).ref());
        }
        return this;
    }

    public Queryable<T> having(Consumer<Where<T>> group) {
        ensureOpen();
        group.accept(new Where<>(model.getHaving(), resolver, model::markUsesSubQueries));
        return this;
    }

    @SafeVarargs
    public final <C> Queryable<T> orderByAsc(SFunction<C, ?>... cols) {
        return orderBy(cols, true);
    }

    @SafeVarargs
    public final <C> Queryable<T> orderByDesc(SFunction<C, ?>... cols) {
        return orderBy(cols, false);
    }

    /** Orders by an aggregate, e.g. {@code orderByDesc(Aggregations.count())}. */
    @SafeVarargs
    public final Queryable<T> orderByAsc(Aggregate... exprs) {
        return orderBy(exprs, true);
    }

    @SafeVarargs
    public final Queryable<T> orderByDesc(Aggregate... exprs) {
        return orderBy(exprs, false);
    }

    /** Occurrence-bound ordering (self-join). */
    @SafeVarargs
    public final Queryable<T> orderByAsc(TableColumn<?, ? >... columns) {
        return orderBy(columns, true);
    }

    @SafeVarargs
    public final Queryable<T> orderByDesc(TableColumn<?, ? >... columns) {
        return orderBy(columns, false);
    }

    /**
     * Orders by an output label — an alias set with {@code Aggregations.xxx().as(...)}
     * or a column name. Rendered as a quoted identifier.
     */
    @SafeVarargs
    public final Queryable<T> orderByAsc(String... outputLabels) {
        return orderByLabels(outputLabels, true);
    }

    @SafeVarargs
    public final Queryable<T> orderByDesc(String... outputLabels) {
        return orderByLabels(outputLabels, false);
    }
    public Queryable<T> limit(long limit) {
        ensureOpen();
        model.setLimit(limit);
        return this;
    }

    public Queryable<T> offset(long offset) {
        ensureOpen();
        model.setOffset(offset);
        return this;
    }

    /** Overrides the physical table name for this query (sharding). */
    public Queryable<T> asTable(String physicalTableName) {
        ensureOpen();
        model.getRoot().rename(physicalTableName);
        return this;
    }

    /** Includes logically deleted rows in this query (default: excluded). */
    public Queryable<T> includeDeleted() {
        ensureOpen();
        includeDeleted = true;
        return this;
    }

    public Queryable<T> forUpdate() {
        ensureOpen();
        model.setForUpdate(true);
        return this;
    }

    // ------------------------------------------------------------------ terminals

    public List<T> toList() {
        ensureOpen();
        prepare();
        return executeList();
    }

    /** First row or null; automatically adds {@code LIMIT 1} when unset. */
    public T firstOrNull() {
        ensureOpen();
        if (model.getLimit() == null) {
            model.setLimit(1L);
        }
        prepare();
        List<T> rows = executeList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count() {
        ensureOpen();
        prepare();
        return executor.count(SqlBuilder.count(model, executor.dialect()));
    }

    public boolean exists() {
        ensureOpen();
        model.setExistsProbe(true);
        if (model.getLimit() == null) {
            model.setLimit(1L);
        }
        prepare();
        return executor.exists(SqlBuilder.select(model, executor.dialect()));
    }

    /** {@code sum(col)}: BigDecimal, {@code 0} when no rows. */
    public <C, V extends Number> Number sum(SFunction<C, V> col) {
        return aggregate("sum", col, true);
    }

    /** {@code avg(col)}: BigDecimal, {@code null} when no rows. */
    public <C, V extends Number> Number avg(SFunction<C, V> col) {
        return aggregate("avg", col, false);
    }

    /** {@code max(col)}: driver-native number, {@code null} when no rows. */
    public <C, V extends Number> Number max(SFunction<C, V> col) {
        return aggregate("max", col, false);
    }

    /** {@code min(col)}: driver-native number, {@code null} when no rows. */
    public <C, V extends Number> Number min(SFunction<C, V> col) {
        return aggregate("min", col, false);
    }

    /** Offset pagination; {@code pageNo} starts at 1. */
    public PageResult<T> toPageResult(long pageNo, long pageSize) {
        ensureOpen();
        if (pageNo < 1 || pageSize < 1) {
            throw new IllegalArgumentException("pageNo and pageSize must be >= 1, got "
                    + pageNo + "/" + pageSize);
        }
        prepare();
        long total = executor.count(SqlBuilder.count(model, executor.dialect()));
        model.setOffset((pageNo - 1) * pageSize);
        model.setLimit(pageSize);
        List<T> rows = executeList();
        return PageResult.of(rows, total, pageNo, pageSize);
    }

    /**
     * Projected result rows mapped onto a VO class or Java record: record
     * components / VO properties are matched to result-set labels by name,
     * ignoring case and underscores. Uses the current select list — without
     * one, all root columns are selected.
     */
    public <R> List<R> toList(Class<R> projectionType) {
        ensureOpen();
        prepare();
        List<Tuple> tuples = executor.query(SqlBuilder.select(model, executor.dialect()),
                JdbcExecutor.tupleMapper());
        RowProjector<R> projector = RowProjector.of(projectionType);
        List<R> rows = new ArrayList<>(tuples.size());
        for (Tuple tuple : tuples) {
            rows.add(projector.project(tuple));
        }
        return rows;
    }

    /**
     * Offset pagination mapped onto a VO class or Java record
     * (see {@link #toList(Class)}); {@code pageNo} starts at 1.
     */
    public <R> PageResult<R> toPageResult(long pageNo, long pageSize, Class<R> projectionType) {
        ensureOpen();
        if (pageNo < 1 || pageSize < 1) {
            throw new IllegalArgumentException("pageNo and pageSize must be >= 1, got "
                    + pageNo + "/" + pageSize);
        }
        prepare();
        long total = executor.count(SqlBuilder.count(model, executor.dialect()));
        model.setOffset((pageNo - 1) * pageSize);
        model.setLimit(pageSize);
        List<Tuple> tuples = executor.query(SqlBuilder.select(model, executor.dialect()),
                JdbcExecutor.tupleMapper());
        RowProjector<R> projector = RowProjector.of(projectionType);
        List<R> rows = new ArrayList<>(tuples.size());
        for (Tuple tuple : tuples) {
            rows.add(projector.project(tuple));
        }
        return PageResult.of(rows, total, pageNo, pageSize);
    }

    /**
     * Keyset (seek) pagination: adds the predicate that matches everything
     * strictly after the given sort-key values, so the next page is fetched
     * with {@code limit(...)} instead of a costly deep {@code offset}. The
     * values correspond 1:1 to the columns of the previous
     * {@code orderByAsc/orderByDesc} call, honouring each column's direction;
     * all values must be non-null. Call it after the order and the user
     * conditions are set, then finish with a terminal method.
     *
     * <pre>{@code
     * // page 1:  orderByAsc(User::getId).limit(20).toList()
     * // page 2:  .orderByAsc(User::getId).seekAfter(lastId).limit(20).toList()
     * }</pre>
     *
     * @throws SqlBuildException without a preceding orderBy, when the value
     *         count does not match the sort columns, when a value is null, or
     *         when the sort uses an aggregate/alias expression
     */
    public Queryable<T> seekAfter(Comparable<?>... values) {
        ensureOpen();
        List<OrderBy> orders = model.getOrderBys();
        if (orders.isEmpty()) {
            throw new SqlBuildException("seekAfter(...) requires an orderByAsc/orderByDesc first — "
                    + "keyset pagination walks an ordered result");
        }
        int expected = orders.size();
        int actual = values == null ? 0 : values.length;
        if (actual != expected) {
            throw new SqlBuildException("seekAfter expects " + expected + " value(s) matching the "
                    + "sort columns, got " + actual);
        }
        ConditionGroup seek = new ConditionGroup();
        for (int i = 0; i < expected; i++) {
            OrderBy order = orders.get(i);
            if (!(order instanceof OrderBy.ByExpr byExpr)
                    || !(byExpr.expr() instanceof ColumnRef ref)) {
                throw new SqlBuildException("seekAfter(...) needs plain column orderBy — aggregates "
                        + "and alias-based ordering cannot be used as a seek key");
            }
            TableRef table = ref.tableAlias() != null
                    ? model.findTableByAlias(ref.tableAlias())
                    : model.findTable(ref.entity());
            if (table == null) {
                throw new SqlBuildException("Column '" + ref.column() + "' is not part of this query "
                        + "and cannot be used as a seek key");
            }
            ColumnMeta meta = table.getMeta().byColumn(ref.column());
            Object dbValue = meta == null ? values[i] : meta.toDbValue(values[i]);
            if (dbValue == null) {
                throw new SqlBuildException("seekAfter value " + i + " must not be null — keyset "
                        + "pagination cannot compare NULL sort keys");
            }
            ConditionGroup prefix = new ConditionGroup();
            for (int j = 0; j < i; j++) {
                ColumnRef earlier = columnRefOf(orders.get(j));
                ColumnMeta earlierMeta = metaOf(earlier);
                Object earlierValue = earlierMeta == null ? values[j] : earlierMeta.toDbValue(values[j]);
                prefix.add(Condition.of(earlier, Operator.EQ, List.of(earlierValue)));
            }
            prefix.add(Condition.of(ref, byExpr.asc() ? Operator.GT : Operator.LT, List.of(dbValue)));
            if (i > 0) {
                seek.or();
            }
            seek.add(prefix);
        }
        model.getWhere().add(seek);
        return this;
    }

    private ColumnRef columnRefOf(OrderBy order) {
        if (order instanceof OrderBy.ByExpr byExpr && byExpr.expr() instanceof ColumnRef ref) {
            return ref;
        }
        throw new SqlBuildException("seekAfter(...) needs plain column orderBy — aggregates "
                + "and alias-based ordering cannot be used as a seek key");
    }

    private ColumnMeta metaOf(ColumnRef ref) {
        TableRef table = ref.tableAlias() != null
                ? model.findTableByAlias(ref.tableAlias())
                : model.findTable(ref.entity());
        return table == null ? null : table.getMeta().byColumn(ref.column());
    }

    /** Projected/grouped result rows (labels: column names or aliases). */
    public List<Tuple> toTupleList() {
        ensureOpen();
        prepare();
        return executor.query(SqlBuilder.select(model, executor.dialect()), JdbcExecutor.tupleMapper());
    }

    /** Debug rendering of the final SQL with its parameters; consumes the query. */
    public String toSql() {
        ensureOpen();
        prepare();
        SqlFragment fragment = SqlBuilder.select(model, executor.dialect());
        return fragment.sql() + " | params=" + fragment.params();
    }

    // ------------------------------------------------------------------ internals

    private Queryable<T> orderBy(SFunction<?, ?>[] cols, boolean asc) {
        ensureOpen();
        for (SFunction<?, ?> col : cols) {
            model.getOrderBys().add(new OrderBy.ByExpr(ref(col), asc));
        }
        return this;
    }

    private Queryable<T> orderBy(Aggregate[] exprs, boolean asc) {
        ensureOpen();
        for (Aggregate expr : exprs) {
            model.getOrderBys().add(new OrderBy.ByExpr(resolveAggregate(expr), asc));
        }
        return this;
    }

    private Queryable<T> orderBy(TableColumn<?, ? >[] columns, boolean asc) {
        ensureOpen();
        for (TableColumn<?, ? > column : columns) {
            model.getOrderBys().add(new OrderBy.ByExpr(resolve(column).ref(), asc));
        }
        return this;
    }

    private Queryable<T> orderByLabels(String[] labels, boolean asc) {
        ensureOpen();
        for (String label : labels) {
            model.getOrderBys().add(new OrderBy.ByAlias(label, asc));
        }
        return this;
    }

    private List<T> executeList() {
        return executor.query(SqlBuilder.select(model, executor.dialect()),
                JdbcExecutor.entityMapper(rootMeta()));
    }

    private Number aggregate(String function, SFunction<?, ?> col, boolean zeroWhenEmpty) {
        ensureOpen();
        prepare();
        model.getSelectExprs().add(new Expr(function, ref(col)));
        Object value = executor.scalar(SqlBuilder.select(model, executor.dialect()));
        if (value == null) {
            return zeroWhenEmpty ? BigDecimal.ZERO : null;
        }
        return value instanceof Number number ? number : new BigDecimal(value.toString());
    }

    /**
     * Applies the logic-delete filter exactly once. Called by every terminal
     * so {@code includeDeleted()} (called before terminals) stays effective.
     */
    private void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        consumed = true;
        if (includeDeleted) {
            return;
        }
        ColumnMeta logic = rootMeta().getLogicDeleteColumn();
        if (logic != null) {
            model.getWhere().add(Condition.of(
                    new ColumnRef(entityClass, logic.getColumnName(), model.getRoot().getExplicitAlias()),
                    Operator.EQ,
                    List.of(logic.normalValueAsDb())));
        }
    }

    private Queryable<T> ensureOpen() {
        if (consumed) {
            throw new SqlBuildException("This Queryable was already consumed by a terminal method. "
                    + "Create a new one via db.queryable(...).");
        }
        return this;
    }

    private ColumnRef ref(SFunction<?, ?> col) {
        return resolver.resolve(col).ref();
    }

    private ColumnResolver.Resolved resolve(SFunction<?, ?> lambda) {
        Class<?> lambdaClass = LambdaUtils.getImplClass(lambda);
        String property = LambdaUtils.getPropertyName(lambda);
        TableRef table = model.findTable(lambdaClass);
        if (table == null) {
            // not registered in this scope — a correlated reference to an
            // outer query table. The property still resolves through the
            // entity's metadata; the alias is resolved by the renderer's
            // scope chain. Unknown entities fail at render time with the
            // full table list.
            return resolveOuter(lambdaClass, property);
        }
        ColumnMeta meta = table.getMeta().byProperty(property);
        if (meta == null) {
            throw new MappingException(lambdaClass.getSimpleName()
                    + " has no mapped property '" + property + "'");
        }
        return new ColumnResolver.Resolved(
                new ColumnRef(table.getEntityClass(), meta.getColumnName()), meta);
    }

    /** Resolves an occurrence-bound column (self-join) via its explicit alias. */
    private ColumnResolver.Resolved resolve(TableColumn<?, ? > column) {
        String alias = column.table().alias();
        TableRef table = model.findTableByAlias(alias);
        if (table == null) {
            throw new SqlBuildException("QueryTable alias '" + alias + "' is not part of this query. "
                    + "Tables in scope: " + model.describeTables() + ". Register the occurrence via "
                    + "queryable(QueryTable) or join(QueryTable, on) first.");
        }
        String property = LambdaUtils.getPropertyName(column.property());
        ColumnMeta meta = table.getMeta().byProperty(property);
        if (meta == null) {
            throw new MappingException(table.getEntityClass().getSimpleName() + "[" + alias
                    + "] has no mapped property '" + property + "'");
        }
        return new ColumnResolver.Resolved(
                new ColumnRef(table.getEntityClass(), meta.getColumnName(), alias, column.outputLabel()),
                meta);
    }

    private ColumnResolver.Resolved resolveOuter(Class<?> lambdaClass, String property) {
        ColumnMeta meta = com.lightquery.meta.EntityMetaCache.of(lambdaClass).byProperty(property);
        if (meta == null) {
            throw new MappingException(lambdaClass.getSimpleName()
                    + " has no mapped property '" + property + "'");
        }
        return new ColumnResolver.Resolved(new ColumnRef(lambdaClass, meta.getColumnName()), meta);
    }

    private Expr resolveAggregate(Aggregate aggregate) {
        if (aggregate.property() == null) {
            if (aggregate.distinct()) {
                throw new SqlBuildException("count(DISTINCT) needs a column — use Aggregations.countDistinct(col)");
            }
            return new Expr(aggregate.function(), null, false, aggregate.alias());
        }
        ColumnRef argument = resolve(aggregate.property()).ref();
        return new Expr(aggregate.function(), argument, aggregate.distinct(), aggregate.alias());
    }

    /** The query model (package seam for sub-query composition). */
    QueryModel model() {
        return model;
    }

    private EntityMeta rootMeta() {
        return model.getRoot().getMeta();
    }
}
