package com.lightquery.query;

import com.lightquery.lambda.SFunction;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Op;

/**
 * ON-condition builder for joins. The primary form compares two columns of
 * the joined entities: {@code on -> on.eq(User::getId, Order::getUserId)}.
 * Conditions against constant values are also supported; {@code .or()}
 * switches the connector of the next condition.
 *
 * @param <A> the entity that is already in the query
 * @param <B> the entity being joined
 */
public final class JoinOn<A, B> {

    private final ConditionGroup group;
    private final ColumnResolver resolver;

    JoinOn(ConditionGroup group, ColumnResolver resolver) {
        this.group = group;
        this.resolver = resolver;
    }

    public <C, D> JoinOn<A, B> eq(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Op.EQ, colB);
    }

    public <C, D> JoinOn<A, B> ne(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Op.NE, colB);
    }

    public <C, D> JoinOn<A, B> gt(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Op.GT, colB);
    }

    public <C, D> JoinOn<A, B> ge(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Op.GE, colB);
    }

    public <C, D> JoinOn<A, B> lt(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Op.LT, colB);
    }

    public <C, D> JoinOn<A, B> le(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Op.LE, colB);
    }

    /** Constant condition, e.g. {@code on.eq(Order::getStatus, 1)}. */
    public <C> JoinOn<A, B> eq(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.EQ, value);
    }

    public <C> JoinOn<A, B> ne(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.NE, value);
    }

    public <C> JoinOn<A, B> gt(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.GT, value);
    }

    public <C> JoinOn<A, B> ge(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.GE, value);
    }

    public <C> JoinOn<A, B> lt(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.LT, value);
    }

    public <C> JoinOn<A, B> le(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.LE, value);
    }

    /** Switches the connector of the next added condition to OR. */
    public JoinOn<A, B> or() {
        group.or();
        return this;
    }

    private JoinOn<A, B> addColumns(SFunction<?, ?> colA, Op op, SFunction<?, ?> colB) {
        group.add(Condition.ofColumns(resolver.resolve(colA).ref(), op, resolver.resolve(colB).ref()));
        return this;
    }

    private JoinOn<A, B> addValue(SFunction<?, ?> col, Op op, Object value) {
        ColumnResolver.Resolved r = resolver.resolve(col);
        group.add(Condition.of(r.ref(), op, java.util.List.of(r.meta().toDbValue(value))));
        return this;
    }

    ConditionGroup group() {
        return group;
    }
}
