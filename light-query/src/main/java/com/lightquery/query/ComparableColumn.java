package com.lightquery.query;

import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

import java.util.Arrays;

/**
 * Range-comparison layer over {@link TypedColumn}, created by
 * {@code cmpCol(Entity::getProperty)}. Available only when the property type
 * is {@link Comparable}, so misuse fails at compile time:
 *
 * <pre>{@code
 * db.queryable(User.class).cmpCol(User::getAge).ge(18)          // ✓
 * db.queryable(User.class).cmpCol(User::getBalance).between(a, b) // ✓
 * }</pre>
 *
 * @param <B> the builder this column was created from
 * @param <V> the comparable value type of the property
 */
public class ComparableColumn<B, V extends Comparable<V>> extends TypedColumn<B, V> {

    protected ComparableColumn(B builder, ConditionGroup group, ColumnRef ref,
                               ColumnMeta meta, ColumnResolver resolver, Runnable onSubQuery) {
        super(builder, group, ref, meta, resolver, onSubQuery);
    }

    public B gt(V value) {
        return add(Operator.GT, value);
    }

    public B ge(V value) {
        return add(Operator.GE, value);
    }

    public B lt(V value) {
        return add(Operator.LT, value);
    }

    public B le(V value) {
        return add(Operator.LE, value);
    }

    /** Inclusive range. */
    public B between(V lo, V hi) {
        group.add(Condition.of(ref, Operator.BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))));
        return builder;
    }

    public B notBetween(V lo, V hi) {
        group.add(Condition.of(ref, Operator.NOT_BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))));
        return builder;
    }

    /** Column-to-column comparison against another property of the same value type. */
    public <C> B gtColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.GT);
    }

    public <C> B geColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.GE);
    }

    public <C> B ltColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.LT);
    }

    public <C> B leColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.LE);
    }
}
