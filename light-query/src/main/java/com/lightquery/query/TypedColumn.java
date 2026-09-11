package com.lightquery.query;

import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A strongly-typed column handle created by {@code col(Entity::getProperty)}.
 * The value type {@code V} is fixed by the property lambda at creation time,
 * so every condition built from it is checked at compile time:
 *
 * <pre>{@code
 * db.queryable(User.class)
 *     .col(User::getStatus).eq(Status.ACTIVE)   // ✓ V = Status
 *     .col(User::getAge).eq("abc")              // ✗ does not compile
 * }</pre>
 *
 * <p>This base layer carries only the equality family. Range comparisons live
 * on {@link ComparableColumn} (via {@code cmpCol(...)}), text matches on
 * {@link StringColumn} (via {@code strCol(...)}), numeric markers on
 * {@link NumberColumn} (via {@code numCol(...)}). Every method returns the
 * owning builder, so the fluent chain continues as usual.</p>
 *
 * @param <B> the builder this column was created from
 * @param <V> the value type of the property
 */
public class TypedColumn<B, V> {

    protected final B builder;
    protected final ConditionGroup group;
    protected final ColumnRef ref;
    protected final ColumnMeta meta;
    protected final ColumnResolver resolver;
    /** Marks the owning query model when a sub-query condition is added (alias rendering). */
    protected final Runnable onSubQuery;

    protected TypedColumn(B builder, ConditionGroup group, ColumnRef ref,
                          ColumnMeta meta, ColumnResolver resolver, Runnable onSubQuery) {
        this.builder = builder;
        this.group = group;
        this.ref = ref;
        this.meta = meta;
        this.resolver = resolver;
        this.onSubQuery = onSubQuery;
    }

    /** {@code col = value}; {@code null} renders {@code col IS NULL}. */
    public B eq(V value) {
        return add(Operator.EQ, value);
    }

    /** {@code col <> value}; {@code null} renders {@code col IS NOT NULL}. */
    public B ne(V value) {
        return add(Operator.NE, value);
    }

    /** Matches any value; an empty collection renders {@code 1 = 0}. */
    public B in(Collection<V> values) {
        List<Object> converted = converted(values);
        group.add(Condition.of(ref, Operator.IN, converted));
        return builder;
    }

    /** Matches any value; an empty argument list renders {@code 1 = 0}. */
    @SafeVarargs
    public final B in(V... values) {
        return in(Arrays.asList(values));
    }

    /** Excludes every value; an empty collection renders {@code 1 = 1}. */
    public B notIn(Collection<V> values) {
        List<Object> converted = converted(values);
        group.add(Condition.of(ref, Operator.NOT_IN, converted));
        return builder;
    }

    @SafeVarargs
    public final B notIn(V... values) {
        return notIn(Arrays.asList(values));
    }

    public B isNull() {
        group.add(Condition.of(ref, Operator.IS_NULL, null));
        return builder;
    }

    public B isNotNull() {
        group.add(Condition.of(ref, Operator.IS_NOT_NULL, null));
        return builder;
    }

    /**
     * Column-to-column equality against another property of the same value
     * type (join ON / correlated references), e.g.
     * {@code .col(User::getId).eqColumn(Order::getUserId)}.
     */
    public <C> B eqColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.EQ, resolver.resolve(other).ref()));
        return builder;
    }

    /**
     * Column-to-column inequality against another property of the same value
     * type.
     */
    public <C> B neColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.NE);
    }

    /** {@code col IN (SELECT ...)}; correlated references to outer entities work. */
    public B in(Queryable<?> subQuery) {
        return subQueryCondition(Operator.IN, subQuery);
    }

    /** {@code col NOT IN (SELECT ...)}. */
    public B notIn(Queryable<?> subQuery) {
        return subQueryCondition(Operator.NOT_IN, subQuery);
    }

    /** Scalar sub-query comparison: {@code col = (SELECT ...)}. */
    public B eqSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.EQ, subQuery);
    }

    public B neSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.NE, subQuery);
    }

    public B gtSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.GT, subQuery);
    }

    public B geSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.GE, subQuery);
    }

    public B ltSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.LT, subQuery);
    }

    public B leSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.LE, subQuery);
    }

    protected B subQueryCondition(Operator operator, Queryable<?> subQuery) {
        onSubQuery.run();
        group.add(com.lightquery.query.model.Condition.ofSubQuery(ref, operator, subQuery.model()));
        return builder;
    }

    protected B compareColumn(SFunction<?, V> other, Operator operator) {
        group.add(Condition.ofColumns(ref, operator, resolver.resolve(other).ref()));
        return builder;
    }

    // ------------------------------------------------------------------ internals

    protected B add(Operator operator, Object value) {
        group.add(Condition.of(ref, operator, Collections.singletonList(meta.toDbValue(value))));
        return builder;
    }

    protected List<Object> converted(Collection<V> values) {
        return (values == null ? List.<V>of() : values).stream()
                .map(meta::toDbValue)
                .toList();
    }

}
