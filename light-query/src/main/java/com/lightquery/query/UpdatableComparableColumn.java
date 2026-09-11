package com.lightquery.query;

import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

import java.util.Arrays;

/**
 * Range-comparison layer over {@link UpdatableColumn}, created by
 * {@code cmpCol(Entity::getProperty)}.
 *
 * @param <T> the updated entity type
 * @param <V> the comparable value type of the property
 */
public class UpdatableComparableColumn<T, V extends Comparable<V>> extends UpdatableColumn<T, V> {

    protected UpdatableComparableColumn(Updatable<T> updatable, ConditionGroup group, ColumnRef ref,
                                        ColumnMeta meta, ColumnResolver resolver, boolean setTargetsRoot) {
        super(updatable, group, ref, meta, resolver, setTargetsRoot);
    }

    public Updatable<T> gt(V value) {
        return add(Operator.GT, value);
    }

    public Updatable<T> ge(V value) {
        return add(Operator.GE, value);
    }

    public Updatable<T> lt(V value) {
        return add(Operator.LT, value);
    }

    public Updatable<T> le(V value) {
        return add(Operator.LE, value);
    }

    /** Inclusive range. */
    public Updatable<T> between(V lo, V hi) {
        group.add(Condition.of(ref, Operator.BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))));
        return updatable;
    }

    public Updatable<T> notBetween(V lo, V hi) {
        group.add(Condition.of(ref, Operator.NOT_BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))));
        return updatable;
    }

    /** Column-to-column comparison against another property of the same value type. */
    public <C> Updatable<T> gtColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.GT, resolver.resolve(other).ref()));
        return updatable;
    }

    public <C> Updatable<T> geColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.GE, resolver.resolve(other).ref()));
        return updatable;
    }

    public <C> Updatable<T> ltColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.LT, resolver.resolve(other).ref()));
        return updatable;
    }

    public <C> Updatable<T> leColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.LE, resolver.resolve(other).ref()));
        return updatable;
    }
}
