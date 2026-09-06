package com.lightquery.query;

import com.lightquery.TableColumn;
import com.lightquery.lambda.SFunction;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

/**
 * ON-condition builder for joins. The primary form compares two columns of
 * the joined entities: {@code on -> on.eq(User::getId, Order::getUserId)}.
 * Self-join occurrences are addressed with {@code TableColumn}s:
 * {@code on.eqColumn(staff.col(Employee::getManagerId), manager.col(Employee::getId))}.
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
        return addColumns(colA, Operator.EQ, colB);
    }

    public <C, D> JoinOn<A, B> ne(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Operator.NE, colB);
    }

    public <C, D> JoinOn<A, B> gt(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Operator.GT, colB);
    }

    public <C, D> JoinOn<A, B> ge(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Operator.GE, colB);
    }

    public <C, D> JoinOn<A, B> lt(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Operator.LT, colB);
    }

    public <C, D> JoinOn<A, B> le(SFunction<C, ?> colA, SFunction<D, ?> colB) {
        return addColumns(colA, Operator.LE, colB);
    }

    // --------------------------------------------- TableColumn (self-join occurrences)

    public JoinOn<A, B> eqColumn(TableColumn<?> colA, TableColumn<?> colB) {
        return addColumns(colA, Operator.EQ, colB);
    }

    public JoinOn<A, B> neColumn(TableColumn<?> colA, TableColumn<?> colB) {
        return addColumns(colA, Operator.NE, colB);
    }

    public JoinOn<A, B> gtColumn(TableColumn<?> colA, TableColumn<?> colB) {
        return addColumns(colA, Operator.GT, colB);
    }

    public JoinOn<A, B> geColumn(TableColumn<?> colA, TableColumn<?> colB) {
        return addColumns(colA, Operator.GE, colB);
    }

    public JoinOn<A, B> ltColumn(TableColumn<?> colA, TableColumn<?> colB) {
        return addColumns(colA, Operator.LT, colB);
    }

    public JoinOn<A, B> leColumn(TableColumn<?> colA, TableColumn<?> colB) {
        return addColumns(colA, Operator.LE, colB);
    }

    /** Constant condition, e.g. {@code on.eq(Order::getStatus, 1)}. */
    public <C> JoinOn<A, B> eq(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.EQ, value);
    }

    public <C> JoinOn<A, B> ne(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.NE, value);
    }

    public <C> JoinOn<A, B> gt(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.GT, value);
    }

    public <C> JoinOn<A, B> ge(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.GE, value);
    }

    public <C> JoinOn<A, B> lt(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.LT, value);
    }

    public <C> JoinOn<A, B> le(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.LE, value);
    }

    /** Switches the connector of the next added condition to OR. */
    public JoinOn<A, B> or() {
        group.or();
        return this;
    }

    private JoinOn<A, B> addColumns(SFunction<?, ?> colA, Operator operator, SFunction<?, ?> colB) {
        group.add(Condition.ofColumns(resolver.resolve(colA).ref(), operator, resolver.resolve(colB).ref()));
        return this;
    }

    private JoinOn<A, B> addColumns(TableColumn<?> colA, Operator operator, TableColumn<?> colB) {
        group.add(Condition.ofColumns(resolver.resolve(colA).ref(), operator, resolver.resolve(colB).ref()));
        return this;
    }

    private JoinOn<A, B> addValue(SFunction<?, ?> col, Operator operator, Object value) {
        ColumnResolver.Resolved r = resolver.resolve(col);
        group.add(Condition.of(r.ref(), operator, java.util.List.of(r.meta().toDbValue(value))));
        return this;
    }

    ConditionGroup group() {
        return group;
    }
}
