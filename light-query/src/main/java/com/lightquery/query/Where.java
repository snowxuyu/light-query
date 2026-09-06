package com.lightquery.query;

import com.lightquery.Aggregate;
import com.lightquery.TableColumn;
import com.lightquery.lambda.SFunction;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Expr;
import com.lightquery.query.model.Operator;

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
 * for a parenthesised OR group. Columns of a self-join occurrence are
 * addressed with {@code TableColumn}s (see {@link com.lightquery.QueryTable}).</p>
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
        return addValue(col, Operator.EQ, value);
    }

    public <C> Where<T> ne(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.NE, value);
    }

    public <C> Where<T> gt(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.GT, value);
    }

    public <C> Where<T> ge(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.GE, value);
    }

    public <C> Where<T> lt(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.LT, value);
    }

    public <C> Where<T> le(SFunction<C, ?> col, Object value) {
        return addValue(col, Operator.LE, value);
    }

    /** Contains match: escapes {@code \ % _} and wraps with {@code %}. */
    public <C> Where<T> like(SFunction<C, ?> col, String contains) {
        return addValue(col, Operator.LIKE, Escape.contains(contains));
    }

    public <C> Where<T> notLike(SFunction<C, ?> col, String contains) {
        return addValue(col, Operator.NOT_LIKE, Escape.contains(contains));
    }

    public <C> Where<T> startsWith(SFunction<C, ?> col, String prefix) {
        return addValue(col, Operator.LIKE, Escape.prefix(prefix));
    }

    public <C> Where<T> endsWith(SFunction<C, ?> col, String suffix) {
        return addValue(col, Operator.LIKE, Escape.suffix(suffix));
    }

    /** Matches any value; an empty collection renders {@code 1 = 0}. */
    public <C> Where<T> in(SFunction<C, ?> col, Collection<?> values) {
        return addValues(col, Operator.IN, values);
    }

    /** Matches any value; an empty argument list renders {@code 1 = 0}. */
    @SafeVarargs
    public final <C> Where<T> in(SFunction<C, ?> col, Object... values) {
        return in(col, Arrays.asList(values));
    }

    /** Excludes every value; an empty collection renders {@code 1 = 1}. */
    public <C> Where<T> notIn(SFunction<C, ?> col, Collection<?> values) {
        return addValues(col, Operator.NOT_IN, values);
    }

    @SafeVarargs
    public final <C> Where<T> notIn(SFunction<C, ?> col, Object... values) {
        return notIn(col, Arrays.asList(values));
    }

    public <C> Where<T> isNull(SFunction<C, ?> col) {
        return add(Condition.of(ref(col), Operator.IS_NULL, null));
    }

    public <C> Where<T> isNotNull(SFunction<C, ?> col) {
        return add(Condition.of(ref(col), Operator.IS_NOT_NULL, null));
    }

    /** Inclusive range. */
    public <C> Where<T> between(SFunction<C, ?> col, Object lo, Object hi) {
        return addRange(col, Operator.BETWEEN, lo, hi);
    }

    public <C> Where<T> notBetween(SFunction<C, ?> col, Object lo, Object hi) {
        return addRange(col, Operator.NOT_BETWEEN, lo, hi);
    }

    // --------------------------------------------- column-to-column (joins, correlated sub-queries)

    public <C, D> Where<T> eqColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Operator.EQ, b);
    }

    public <C, D> Where<T> neColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Operator.NE, b);
    }

    public <C, D> Where<T> gtColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Operator.GT, b);
    }

    public <C, D> Where<T> geColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Operator.GE, b);
    }

    public <C, D> Where<T> ltColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Operator.LT, b);
    }

    public <C, D> Where<T> leColumn(SFunction<C, ?> a, SFunction<D, ?> b) {
        return addColumns(a, Operator.LE, b);
    }

    // ------------------------------------------------- TableColumn (self-join occurrences)

    public Where<T> eq(TableColumn<?> col, Object value) {
        return addValue(col, Operator.EQ, value);
    }

    public Where<T> ne(TableColumn<?> col, Object value) {
        return addValue(col, Operator.NE, value);
    }

    public Where<T> gt(TableColumn<?> col, Object value) {
        return addValue(col, Operator.GT, value);
    }

    public Where<T> ge(TableColumn<?> col, Object value) {
        return addValue(col, Operator.GE, value);
    }

    public Where<T> lt(TableColumn<?> col, Object value) {
        return addValue(col, Operator.LT, value);
    }

    public Where<T> le(TableColumn<?> col, Object value) {
        return addValue(col, Operator.LE, value);
    }

    public Where<T> like(TableColumn<?> col, String contains) {
        return addValue(col, Operator.LIKE, Escape.contains(contains));
    }

    public Where<T> notLike(TableColumn<?> col, String contains) {
        return addValue(col, Operator.NOT_LIKE, Escape.contains(contains));
    }

    public Where<T> startsWith(TableColumn<?> col, String prefix) {
        return addValue(col, Operator.LIKE, Escape.prefix(prefix));
    }

    public Where<T> endsWith(TableColumn<?> col, String suffix) {
        return addValue(col, Operator.LIKE, Escape.suffix(suffix));
    }

    public Where<T> in(TableColumn<?> col, Collection<?> values) {
        return addValues(col, Operator.IN, values);
    }

    @SafeVarargs
    public final Where<T> in(TableColumn<?> col, Object... values) {
        return in(col, Arrays.asList(values));
    }

    public Where<T> notIn(TableColumn<?> col, Collection<?> values) {
        return addValues(col, Operator.NOT_IN, values);
    }

    @SafeVarargs
    public final Where<T> notIn(TableColumn<?> col, Object... values) {
        return notIn(col, Arrays.asList(values));
    }

    public Where<T> isNull(TableColumn<?> col) {
        return add(Condition.of(ref(col), Operator.IS_NULL, null));
    }

    public Where<T> isNotNull(TableColumn<?> col) {
        return add(Condition.of(ref(col), Operator.IS_NOT_NULL, null));
    }

    public Where<T> between(TableColumn<?> col, Object lo, Object hi) {
        return addRange(col, Operator.BETWEEN, lo, hi);
    }

    public Where<T> notBetween(TableColumn<?> col, Object lo, Object hi) {
        return addRange(col, Operator.NOT_BETWEEN, lo, hi);
    }

    public Where<T> eqColumn(TableColumn<?> a, TableColumn<?> b) {
        return addColumns(a, Operator.EQ, b);
    }

    public Where<T> neColumn(TableColumn<?> a, TableColumn<?> b) {
        return addColumns(a, Operator.NE, b);
    }

    public Where<T> gtColumn(TableColumn<?> a, TableColumn<?> b) {
        return addColumns(a, Operator.GT, b);
    }

    public Where<T> geColumn(TableColumn<?> a, TableColumn<?> b) {
        return addColumns(a, Operator.GE, b);
    }

    public Where<T> ltColumn(TableColumn<?> a, TableColumn<?> b) {
        return addColumns(a, Operator.LT, b);
    }

    public Where<T> leColumn(TableColumn<?> a, TableColumn<?> b) {
        return addColumns(a, Operator.LE, b);
    }

    // ------------------------------------------------------------------ aggregate comparisons (HAVING)

    public Where<T> eq(Aggregate aggregate, Object value) {
        return addAggregate(aggregate, Operator.EQ, value);
    }

    public Where<T> ne(Aggregate aggregate, Object value) {
        return addAggregate(aggregate, Operator.NE, value);
    }

    public Where<T> gt(Aggregate aggregate, Object value) {
        return addAggregate(aggregate, Operator.GT, value);
    }

    public Where<T> ge(Aggregate aggregate, Object value) {
        return addAggregate(aggregate, Operator.GE, value);
    }

    public Where<T> lt(Aggregate aggregate, Object value) {
        return addAggregate(aggregate, Operator.LT, value);
    }

    public Where<T> le(Aggregate aggregate, Object value) {
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

    private Where<T> addValue(SFunction<?, ?> col, Operator operator, Object value) {
        return addResolvedValue(resolver.resolve(col), operator, value);
    }

    private Where<T> addValue(TableColumn<?> col, Operator operator, Object value) {
        return addResolvedValue(resolver.resolve(col), operator, value);
    }

    private Where<T> addResolvedValue(ColumnResolver.Resolved resolved, Operator operator, Object value) {
        return add(Condition.of(resolved.ref(), operator,
                Collections.singletonList(resolved.meta().toDbValue(value))));
    }

    private Where<T> addValues(SFunction<?, ?> col, Operator operator, Collection<?> values) {
        return addResolvedValues(resolver.resolve(col), operator, values);
    }

    private Where<T> addValues(TableColumn<?> col, Operator operator, Collection<?> values) {
        return addResolvedValues(resolver.resolve(col), operator, values);
    }

    private Where<T> addResolvedValues(ColumnResolver.Resolved resolved, Operator operator,
                                       Collection<?> values) {
        List<Object> converted = (values == null ? List.of() : values).stream()
                .map(resolved.meta()::toDbValue)
                .toList();
        return add(Condition.of(resolved.ref(), operator, converted));
    }

    private Where<T> addRange(SFunction<?, ?> col, Operator operator, Object lo, Object hi) {
        return addResolvedRange(resolver.resolve(col), operator, lo, hi);
    }

    private Where<T> addRange(TableColumn<?> col, Operator operator, Object lo, Object hi) {
        return addResolvedRange(resolver.resolve(col), operator, lo, hi);
    }

    private Where<T> addResolvedRange(ColumnResolver.Resolved resolved, Operator operator,
                                      Object lo, Object hi) {
        return add(Condition.of(resolved.ref(), operator,
                Arrays.asList(resolved.meta().toDbValue(lo), resolved.meta().toDbValue(hi))));
    }

    private Where<T> addColumns(SFunction<?, ?> a, Operator operator, SFunction<?, ?> b) {
        return addResolvedColumns(resolver.resolve(a), operator, resolver.resolve(b));
    }

    private Where<T> addColumns(TableColumn<?> a, Operator operator, TableColumn<?> b) {
        return addResolvedColumns(resolver.resolve(a), operator, resolver.resolve(b));
    }

    private Where<T> addResolvedColumns(ColumnResolver.Resolved left, Operator operator,
                                        ColumnResolver.Resolved right) {
        return add(Condition.ofColumns(left.ref(), operator, right.ref()));
    }

    private Where<T> addAggregate(Aggregate aggregate, Operator operator, Object value) {
        Expr expr = resolver.resolveAggregate(aggregate);
        return add(Condition.of(expr, operator, Collections.singletonList(value)));
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

    private ColumnRef ref(TableColumn<?> col) {
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
