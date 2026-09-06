package com.lightquery.query;

import com.lightquery.Agg;
import com.lightquery.lambda.SFunction;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Expr;
import com.lightquery.query.model.Op;

import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Condition collector used by {@code and(...)}/{@code or(...)}/{@code having(...)}
 * lambdas. Carries every comparison operator of the fluent API plus the
 * {@code .or()} continuation that switches the connector of the next
 * condition inside the current group.
 *
 * <p>Top-level conditions of a query are ANDed; use {@link #or(Consumer)}
 * for a parenthesised OR group.</p>
 *
 * @param <T> the entity type the conditions are written against
 */
public final class Where<T> {

    private final ConditionGroup group;
    private final ColumnResolver resolver;

    Where(ConditionGroup group, ColumnResolver resolver) {
        this.group = group;
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------ scalar comparisons

    public <C> Where<T> eq(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.EQ, value);
    }

    public <C> Where<T> ne(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.NE, value);
    }

    public <C> Where<T> gt(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.GT, value);
    }

    public <C> Where<T> ge(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.GE, value);
    }

    public <C> Where<T> lt(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.LT, value);
    }

    public <C> Where<T> le(SFunction<C, ?> col, Object value) {
        return addValue(col, Op.LE, value);
    }

    /** Contains match: escapes {@code \ % _} and wraps with {@code %}. */
    public <C> Where<T> like(SFunction<C, ?> col, String contains) {
        return addValue(col, Op.LIKE, Escape.contains(contains));
    }

    public <C> Where<T> notLike(SFunction<C, ?> col, String contains) {
        return addValue(col, Op.NOT_LIKE, Escape.contains(contains));
    }

    public <C> Where<T> startsWith(SFunction<C, ?> col, String prefix) {
        return addValue(col, Op.LIKE, Escape.prefix(prefix));
    }

    public <C> Where<T> endsWith(SFunction<C, ?> col, String suffix) {
        return addValue(col, Op.LIKE, Escape.suffix(suffix));
    }

    /** Matches any value; an empty collection renders {@code 1 = 0}. */
    public <C> Where<T> in(SFunction<C, ?> col, Collection<?> values) {
        return addValues(col, Op.IN, values);
    }

    /** Matches any value; an empty argument list renders {@code 1 = 0}. */
    @SafeVarargs
    public final <C> Where<T> in(SFunction<C, ?> col, Object... values) {
        return in(col, Arrays.asList(values));
    }

    /** Excludes every value; an empty collection renders {@code 1 = 1}. */
    public <C> Where<T> notIn(SFunction<C, ?> col, Collection<?> values) {
        return addValues(col, Op.NOT_IN, values);
    }

    @SafeVarargs
    public final <C> Where<T> notIn(SFunction<C, ?> col, Object... values) {
        return notIn(col, Arrays.asList(values));
    }

    public <C> Where<T> isNull(SFunction<C, ?> col) {
        return add(Condition.of(ref(col), Op.IS_NULL, null));
    }

    public <C> Where<T> isNotNull(SFunction<C, ?> col) {
        return add(Condition.of(ref(col), Op.IS_NOT_NULL, null));
    }

    /** Inclusive range. */
    public <C> Where<T> between(SFunction<C, ?> col, Object lo, Object hi) {
        return addRange(col, Op.BETWEEN, lo, hi);
    }

    public <C> Where<T> notBetween(SFunction<C, ?> col, Object lo, Object hi) {
        return addRange(col, Op.NOT_BETWEEN, lo, hi);
    }

    // --------------------------------------------- column-to-column (joins, correlated sub-queries)

    public <C, D> Where<T> eqColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Op.EQ, b);
    }

    public <C, D> Where<T> neColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Op.NE, b);
    }

    public <C, D> Where<T> gtColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Op.GT, b);
    }

    public <C, D> Where<T> geColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Op.GE, b);
    }

    public <C, D> Where<T> ltColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Op.LT, b);
    }

    public <C, D> Where<T> leColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Op.LE, b);
    }

    // ------------------------------------------------------------------ aggregate comparisons (HAVING)

    public Where<T> eq(Agg agg, Object value) {
        return addAgg(agg, Op.EQ, value);
    }

    public Where<T> ne(Agg agg, Object value) {
        return addAgg(agg, Op.NE, value);
    }

    public Where<T> gt(Agg agg, Object value) {
        return addAgg(agg, Op.GT, value);
    }

    public Where<T> ge(Agg agg, Object value) {
        return addAgg(agg, Op.GE, value);
    }

    public Where<T> lt(Agg agg, Object value) {
        return addAgg(agg, Op.LT, value);
    }

    public Where<T> le(Agg agg, Object value) {
        return addAgg(agg, Op.LE, value);
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

    private Where<T> addValue(SFunction<?, ?> col, Op op, Object value) {
        ColumnResolver.Resolved r = resolver.resolve(col);
        return add(Condition.of(r.ref(), op, Collections.singletonList(r.meta().toDbValue(value))));
    }

    private Where<T> addValues(SFunction<?, ?> col, Op op, Collection<?> values) {
        ColumnResolver.Resolved r = resolver.resolve(col);
        List<Object> converted = (values == null ? List.of() : values).stream()
                .map(r.meta()::toDbValue)
                .toList();
        return add(Condition.of(r.ref(), op, converted));
    }

    private Where<T> addRange(SFunction<?, ?> col, Op op, Object lo, Object hi) {
        ColumnResolver.Resolved r = resolver.resolve(col);
        return add(Condition.of(r.ref(), op, Arrays.asList(r.meta().toDbValue(lo), r.meta().toDbValue(hi))));
    }

    private Where<T> addColumns(SFunction<?, ?> a, Op op, SFunction<?, ?> b) {
        ColumnRef left = ref(a);
        ColumnRef right = resolver.resolve(b).ref();
        return add(Condition.ofColumns(left, op, right));
    }

    private Where<T> addAgg(Agg agg, Op op, Object value) {
        Expr expr = resolver.resolveAgg(agg);
        return add(Condition.of(expr, op, Collections.singletonList(value)));
    }

    private Where<T> addNested(Consumer<Where<T>> nested, boolean or) {
        ConditionGroup child = new ConditionGroup();
        nested.accept(new Where<>(child, resolver));
        if (or) {
            group.or();
        }
        group.add(child);
        return this;
    }

    private ColumnRef ref(SFunction<?, ?> col) {
        return resolver.resolve(col).ref();
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
