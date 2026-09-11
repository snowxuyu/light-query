package com.lightquery.query;

import com.lightquery.Aggregate;
import com.lightquery.TableColumn;
import com.lightquery.lambda.SFunction;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Expr;
import com.lightquery.query.model.Operator;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * Condition collector used by {@code and(...)}/{@code or(...)}/{@code having(...)}
 * lambdas. Conditions are built from strongly-typed column handles:
 * {@code w.col(User::getAge).ge(18)} — the value type is fixed by the
 * property lambda and checked at compile time. The {@code .or()}
 * continuation switches the connector of the next condition inside the
 * current group.
 *
 * <p>Top-level conditions of a query are ANDed; use {@link #or(Consumer)}
 * for a parenthesised OR group. Aggregate comparisons for HAVING stay on
 * the collector itself ({@code w.gt(Aggregations.count(), 2)}).</p>
 *
 * @param <T> the entity type the conditions are written against
 */
public final class Where<T> {

    private final ConditionGroup group;
    private final ColumnResolver resolver;
    private final Runnable onSubQuery;

    Where(ConditionGroup group, ColumnResolver resolver, Runnable onSubQuery) {
        this.group = group;
        this.resolver = resolver;
        this.onSubQuery = onSubQuery;
    }

    // ------------------------------------------------------------------ strongly-typed columns

    /**
     * Starts a strongly-typed condition on a property — the value type of
     * the property lambda drives the condition, e.g.
     * {@code w.col(User::getAge).ge(18)}. The property may belong to any
     * table in scope (root or joined).
     */
    public <C, V> TypedColumn<Where<T>, V> col(SFunction<C, V> col) {
        ColumnResolver.Resolved resolved = resolver.resolve(col);
        return new TypedColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    /** Range condition on a {@link Comparable} property. */
    public <C, V extends Comparable<V>> ComparableColumn<Where<T>, V> cmpCol(SFunction<C, V> col) {
        ColumnResolver.Resolved resolved = resolver.resolve(col);
        return new ComparableColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    /** Text-match condition on a {@code String} property. */
    public <C> StringColumn<Where<T>> strCol(SFunction<C, String> col) {
        ColumnResolver.Resolved resolved = resolver.resolve(col);
        return new StringColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    /** Numeric condition on a {@link Number} property. */
    public <C, V extends Number & Comparable<V>> NumberColumn<Where<T>, V> numCol(SFunction<C, V> col) {
        ColumnResolver.Resolved resolved = resolver.resolve(col);
        return new NumberColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    /** Strongly-typed condition on a self-join occurrence column. */
    public <V> TypedColumn<Where<T>, V> col(TableColumn<?, V> column) {
        ColumnResolver.Resolved resolved = resolver.resolve(column);
        return new TypedColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    /** Range condition on a self-join occurrence column of comparable type. */
    public <V extends Comparable<V>> ComparableColumn<Where<T>, V> cmpCol(TableColumn<?, V> column) {
        ColumnResolver.Resolved resolved = resolver.resolve(column);
        return new ComparableColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    /** Text-match condition on a self-join occurrence column of string type. */
    public StringColumn<Where<T>> strCol(TableColumn<?, String> column) {
        ColumnResolver.Resolved resolved = resolver.resolve(column);
        return new StringColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    /** Numeric condition on a self-join occurrence column of numeric type. */
    public <V extends Number & Comparable<V>> NumberColumn<Where<T>, V> numCol(TableColumn<?, V> column) {
        ColumnResolver.Resolved resolved = resolver.resolve(column);
        return new NumberColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }

    // ------------------------------------------------------------------ aggregate comparisons (HAVING)

    /** HAVING: {@code aggregate = value}. */
    public Where<T> eq(Aggregate aggregate, Number value) {
        return addAggregate(aggregate, Operator.EQ, value);
    }

    /** HAVING: {@code aggregate <> value}. */
    public Where<T> ne(Aggregate aggregate, Number value) {
        return addAggregate(aggregate, Operator.NE, value);
    }

    /** HAVING: {@code aggregate > value}. */
    public Where<T> gt(Aggregate aggregate, Number value) {
        return addAggregate(aggregate, Operator.GT, value);
    }

    /** HAVING: {@code aggregate >= value}. */
    public Where<T> ge(Aggregate aggregate, Number value) {
        return addAggregate(aggregate, Operator.GE, value);
    }

    /** HAVING: {@code aggregate < value}. */
    public Where<T> lt(Aggregate aggregate, Number value) {
        return addAggregate(aggregate, Operator.LT, value);
    }

    /** HAVING: {@code aggregate <= value}. */
    public Where<T> le(Aggregate aggregate, Number value) {
        return addAggregate(aggregate, Operator.LE, value);
    }

    // ------------------------------------------------------------------ grouping & connectors

    /** Switches the connector of the next added condition to OR. */
    public Where<T> or() {
        group.or();
        return this;
    }

    /** Adds a parenthesised AND group. */
    public Where<T> and(Consumer<Where<T>> nested) {
        return addNested(nested, false);
    }

    /** Adds a parenthesised OR group. */
    public Where<T> or(Consumer<Where<T>> nested) {
        return addNested(nested, true);
    }

    // ------------------------------------------------------------------ internals

    private Where<T> addAggregate(Aggregate aggregate, Operator operator, Object value) {
        Expr expr = resolver.resolveAggregate(aggregate);
        group.add(Condition.of(expr, operator, Collections.singletonList(value)));
        return this;
    }

    private Where<T> addNested(Consumer<Where<T>> nested, boolean or) {
        ConditionGroup child = new ConditionGroup();
        nested.accept(new Where<>(child, resolver, onSubQuery));
        if (or) {
            group.or();
        }
        group.add(child);
        return this;
    }

    private Where<T> add(Condition condition) {
        group.add(condition);
        return this;
    }

    /** Appends a condition built by the owning builder (package seam). */
    void addExternal(Condition condition) {
        group.add(condition);
    }

    ConditionGroup group() {
        return group;
    }
}
