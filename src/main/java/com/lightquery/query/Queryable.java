package com.lightquery.query;

import com.lightquery.Agg;
import com.lightquery.PageResult;
import com.lightquery.Tuple;
import com.lightquery.exception.MappingException;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.exec.JdbcExecutor;
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
import com.lightquery.query.model.Op;
import com.lightquery.query.model.OrderBy;
import com.lightquery.query.model.QueryModel;
import com.lightquery.query.model.TableRef;
import com.lightquery.sqlgen.SqlBuilder;
import com.lightquery.sqlgen.SqlFragment;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent, type-safe query over one entity, e.g.:
 *
 * <pre>{@code
 * List<User> users = db.queryable(User.class)
 *     .eq(User::getStatus, Status.ACTIVE)
 *     .and(w -> w.like(User::getName, "frank").or().ge(User::getAge, 18))
 *     .orderByDesc(User::getCreateTime)
 *     .limit(10)
 *     .toList();
 * }</pre>
 *
 * <p>A Queryable is a one-shot builder: every terminal method
 * ({@link #toList()}, {@link #count()}, ...) consumes it; build a new one
 * for the next query. Instances are not thread-safe — {@code db} is.</p>
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
        this.executor = executor;
        this.entityClass = entityClass;
        this.model = new QueryModel(entityClass);
        this.resolver = new ColumnResolver() {
            @Override
            public ColumnResolver.Resolved resolve(SFunction<?, ?> fn) {
                return Queryable.this.resolve(fn);
            }

            @Override
            public Expr resolveAgg(Agg agg) {
                return Queryable.this.resolveAgg(agg);
            }
        };
        this.where = new Where<>(model.getWhere(), resolver);
    }

    // ------------------------------------------------------------------ conditions (top level: ANDed)

    public <C> Queryable<T> eq(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.eq(col, value);
        return this;
    }

    public <C> Queryable<T> ne(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.ne(col, value);
        return this;
    }

    public <C> Queryable<T> gt(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.gt(col, value);
        return this;
    }

    public <C> Queryable<T> ge(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.ge(col, value);
        return this;
    }

    public <C> Queryable<T> lt(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.lt(col, value);
        return this;
    }

    public <C> Queryable<T> le(SFunction<C, ?> col, Object value) {
        ensureOpen();
        where.le(col, value);
        return this;
    }

    /** Contains match with {@code \ % _} escaped. */
    public <C> Queryable<T> like(SFunction<C, ?> col, String contains) {
        ensureOpen();
        where.like(col, contains);
        return this;
    }

    public <C> Queryable<T> notLike(SFunction<C, ?> col, String contains) {
        ensureOpen();
        where.notLike(col, contains);
        return this;
    }

    public <C> Queryable<T> startsWith(SFunction<C, ?> col, String prefix) {
        ensureOpen();
        where.startsWith(col, prefix);
        return this;
    }

    public <C> Queryable<T> endsWith(SFunction<C, ?> col, String suffix) {
        ensureOpen();
        where.endsWith(col, suffix);
        return this;
    }

    /** Matches any value; an empty collection renders {@code 1 = 0}. */
    public <C> Queryable<T> in(SFunction<C, ?> col, Collection<?> values) {
        ensureOpen();
        where.in(col, values);
        return this;
    }

    @SafeVarargs
    public final <C> Queryable<T> in(SFunction<C, ?> col, Object... values) {
        ensureOpen();
        where.in(col, values);
        return this;
    }

    /** Excludes every value; an empty collection renders {@code 1 = 1}. */
    public <C> Queryable<T> notIn(SFunction<C, ?> col, Collection<?> values) {
        ensureOpen();
        where.notIn(col, values);
        return this;
    }

    @SafeVarargs
    public final <C> Queryable<T> notIn(SFunction<C, ?> col, Object... values) {
        ensureOpen();
        where.notIn(col, values);
        return this;
    }

    public <C> Queryable<T> isNull(SFunction<C, ?> col) {
        ensureOpen();
        where.isNull(col);
        return this;
    }

    public <C> Queryable<T> isNotNull(SFunction<C, ?> col) {
        ensureOpen();
        where.isNotNull(col);
        return this;
    }

    /** Inclusive range. */
    public <C> Queryable<T> between(SFunction<C, ?> col, Object lo, Object hi) {
        ensureOpen();
        where.between(col, lo, hi);
        return this;
    }

    public <C> Queryable<T> notBetween(SFunction<C, ?> col, Object lo, Object hi) {
        ensureOpen();
        where.notBetween(col, lo, hi);
        return this;
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

    // --------------------------------------------- column-to-column (joins, correlated sub-queries)

    public <C, D> Queryable<T> eqColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        ensureOpen();
        where.eqColumn(a, b);
        return this;
    }

    public <C, D> Queryable<T> neColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        ensureOpen();
        where.neColumn(a, b);
        return this;
    }

    public <C, D> Queryable<T> gtColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        ensureOpen();
        where.gtColumn(a, b);
        return this;
    }

    public <C, D> Queryable<T> geColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        ensureOpen();
        where.geColumn(a, b);
        return this;
    }

    public <C, D> Queryable<T> ltColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        ensureOpen();
        where.ltColumn(a, b);
        return this;
    }

    public <C, D> Queryable<T> leColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        ensureOpen();
        where.leColumn(a, b);
        return this;
    }

    // ------------------------------------------------------------------ sub-queries

    /** {@code col IN (SELECT …)} — correlated references to outer entities work. */
    public <C> Queryable<T> in(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.IN, subQuery);
    }

    public <C> Queryable<T> notIn(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.NOT_IN, subQuery);
    }

    /** Scalar sub-query comparison: {@code col = (SELECT …)}. */
    public <C> Queryable<T> eqSubQuery(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.EQ, subQuery);
    }

    public <C> Queryable<T> neSubQuery(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.NE, subQuery);
    }

    public <C> Queryable<T> gtSubQuery(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.GT, subQuery);
    }

    public <C> Queryable<T> geSubQuery(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.GE, subQuery);
    }

    public <C> Queryable<T> ltSubQuery(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.LT, subQuery);
    }

    public <C> Queryable<T> leSubQuery(SFunction<C, ?> col, Queryable<?> subQuery) {
        return addSubQueryCondition(col, Op.LE, subQuery);
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

    private Queryable<T> addSubQueryCondition(SFunction<?, ?> col, Op op, Queryable<?> subQuery) {
        ensureOpen();
        subQuery.ensureOpen();
        model.markUsesSubQueries();
        where.addExternal(Condition.ofSubQuery(ref(col), op, subQuery.model));
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

    private <J> Queryable<T> join(JoinType type, Class<J> target, Consumer<JoinOn<J, T>> on) {
        ensureOpen();
        TableRef joined = model.addJoin(target);
        ConditionGroup onGroup = new ConditionGroup();
        on.accept(new JoinOn<>(onGroup, resolver));
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

    /** Aggregate projection, e.g. {@code select(F.count().as("cnt"))}. */
    public Queryable<T> select(Agg... exprs) {
        ensureOpen();
        for (Agg expr : exprs) {
            model.getSelectExprs().add(resolveAgg(expr));
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

    public Queryable<T> having(Consumer<Where<T>> group) {
        ensureOpen();
        group.accept(new Where<>(model.getHaving(), resolver));
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

    /** Orders by an aggregate, e.g. {@code orderByDesc(F.count())}. */
    @SafeVarargs
    public final Queryable<T> orderByAsc(Agg... exprs) {
        return orderBy(exprs, true);
    }

    @SafeVarargs
    public final Queryable<T> orderByDesc(Agg... exprs) {
        return orderBy(exprs, false);
    }

    /**
     * Orders by an output label — an alias set with {@code F.xxx().as(...)}
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
    public <C> Number sum(SFunction<C, ?> col) {
        return aggregate("sum", col, true);
    }

    /** {@code avg(col)}: BigDecimal, {@code null} when no rows. */
    public <C> Number avg(SFunction<C, ?> col) {
        return aggregate("avg", col, false);
    }

    /** {@code max(col)}: driver-native number, {@code null} when no rows. */
    public <C> Number max(SFunction<C, ?> col) {
        return aggregate("max", col, false);
    }

    /** {@code min(col)}: driver-native number, {@code null} when no rows. */
    public <C> Number min(SFunction<C, ?> col) {
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

    private Queryable<T> orderBy(Agg[] exprs, boolean asc) {
        ensureOpen();
        for (Agg expr : exprs) {
            model.getOrderBys().add(new OrderBy.ByExpr(resolveAgg(expr), asc));
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

    private Number aggregate(String func, SFunction<?, ?> col, boolean zeroWhenEmpty) {
        ensureOpen();
        prepare();
        model.getSelectExprs().add(new Expr(func, ref(col)));
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
                    new ColumnRef(entityClass, logic.getColumnName()), Op.EQ,
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

    private ColumnResolver.Resolved resolve(SFunction<?, ?> fn) {
        Class<?> lambdaClass = LambdaUtils.getImplClass(fn);
        String property = LambdaUtils.getPropertyName(fn);
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

    private ColumnResolver.Resolved resolveOuter(Class<?> lambdaClass, String property) {
        ColumnMeta meta = com.lightquery.meta.EntityMetaCache.of(lambdaClass).byProperty(property);
        if (meta == null) {
            throw new MappingException(lambdaClass.getSimpleName()
                    + " has no mapped property '" + property + "'");
        }
        return new ColumnResolver.Resolved(new ColumnRef(lambdaClass, meta.getColumnName()), meta);
    }

    private Expr resolveAgg(Agg agg) {
        if (agg.fn() == null) {
            if (agg.distinct()) {
                throw new SqlBuildException("count(DISTINCT) needs a column — use F.countDistinct(col)");
            }
            return new Expr(agg.func(), null, false, agg.alias());
        }
        ColumnRef arg = resolve(agg.fn()).ref();
        return new Expr(agg.func(), arg, agg.distinct(), agg.alias());
    }

    private EntityMeta rootMeta() {
        return model.getRoot().getMeta();
    }
}
