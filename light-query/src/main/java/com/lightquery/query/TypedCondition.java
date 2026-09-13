package com.lightquery.query;

import com.lightquery.TableColumn;
import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Operator;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Offline strongly-typed condition handle returned by {@link Conditions#col}:
 * the value type {@code V} is fixed by the property lambda, and every
 * terminal returns an immutable {@link Condition} tree (nothing is attached
 * to a query until {@code where(spec)} is called). The terminal family
 * mirrors {@link TypedColumn} — equality, range, IN, BETWEEN, text matches,
 * column-to-column, sub-queries — plus the boolean-first overloads (a skipped
 * condition composes as nothing).
 *
 * @param <V> the value type of the property
 */
public final class TypedCondition<V> {

    private final ColumnRef ref;
    private final ColumnMeta meta;

    TypedCondition(ColumnRef ref, ColumnMeta meta) {
        this.ref = ref;
        this.meta = meta;
    }

    // ------------------------------------------------------------------ equality

    /** {@code col = value}; {@code null} renders {@code col IS NULL}. */
    public Condition eq(V value) {
        return add(Operator.EQ, value);
    }

    /** {@code col <> value}; {@code null} renders {@code col IS NOT NULL}. */
    public Condition ne(V value) {
        return add(Operator.NE, value);
    }

    /** Matches any value; an empty collection renders {@code 1 = 0}. */
    public Condition in(Collection<V> values) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.IN, converted(values)), false);
    }

    /** Matches any value; an empty argument list renders {@code 1 = 0}. */
    @SafeVarargs
    public final Condition in(V... values) {
        return in(Arrays.asList(values));
    }

    /** Excludes every value; an empty collection renders {@code 1 = 1}. */
    public Condition notIn(Collection<V> values) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.NOT_IN, converted(values)), false);
    }

    @SafeVarargs
    public final Condition notIn(V... values) {
        return notIn(Arrays.asList(values));
    }

    /** {@code col IS NULL}. */
    public Condition isNull() {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.IS_NULL, null), false);
    }

    /** {@code col IS NOT NULL}. */
    public Condition isNotNull() {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.IS_NOT_NULL, null), false);
    }

    /** Column-to-column equality against a property of the same value type. */
    public <C> Condition eqColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.EQ);
    }

    /** Column-to-column inequality against a property of the same value type. */
    public <C> Condition neColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.NE);
    }

    // ------------------------------------------------------------------ range

    /** {@code col > value}. */
    public Condition gt(V value) {
        return add(Operator.GT, value);
    }

    /** {@code col >= value}. */
    public Condition ge(V value) {
        return add(Operator.GE, value);
    }

    /** {@code col < value}. */
    public Condition lt(V value) {
        return add(Operator.LT, value);
    }

    /** {@code col <= value}. */
    public Condition le(V value) {
        return add(Operator.LE, value);
    }

    /** Inclusive range. */
    public Condition between(V lo, V hi) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))), false);
    }

    /** Excludes the inclusive range. */
    public Condition notBetween(V lo, V hi) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.NOT_BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))), false);
    }

    /** Column-to-column {@code >} against a property of the same value type. */
    public <C> Condition gtColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.GT);
    }

    /** Column-to-column {@code >=} against a property of the same value type. */
    public <C> Condition geColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.GE);
    }

    /** Column-to-column {@code <} against a property of the same value type. */
    public <C> Condition ltColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.LT);
    }

    /** Column-to-column {@code <=} against a property of the same value type. */
    public <C> Condition leColumn(SFunction<C, V> other) {
        return compareColumn(other, Operator.LE);
    }

    // ------------------------------------------------------------------ text (String columns)

    /** Contains match with {@code \ % _} escaped, both sides wrapped in {@code %}. */
    public Condition like(String contains) {
        return addText(Operator.LIKE, contains);
    }

    /** Negated contains match. */
    public Condition notLike(String contains) {
        return addText(Operator.NOT_LIKE, contains);
    }

    /** Right-side wildcard. */
    public Condition startsWith(String prefix) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.LIKE,
                List.of(Escape.prefix(prefix))), false);
    }

    /** Left-side wildcard. */
    public Condition endsWith(String suffix) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(ref, Operator.LIKE,
                List.of(Escape.suffix(suffix))), false);
    }

    // ------------------------------------------------------------------ sub-queries

    /** {@code col IN (SELECT ...)}; correlated references to outer entities work. */
    public Condition in(Queryable<?> subQuery) {
        return subQueryCondition(Operator.IN, subQuery);
    }

    /** {@code col NOT IN (SELECT ...)}. */
    public Condition notIn(Queryable<?> subQuery) {
        return subQueryCondition(Operator.NOT_IN, subQuery);
    }

    /** Scalar sub-query comparison: {@code col = (SELECT ...)}. */
    public Condition eqSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.EQ, subQuery);
    }

    /** Scalar sub-query comparison: {@code col <> (SELECT ...)}. */
    public Condition neSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.NE, subQuery);
    }

    /** Scalar sub-query comparison: {@code col > (SELECT ...)}. */
    public Condition gtSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.GT, subQuery);
    }

    /** Scalar sub-query comparison: {@code col >= (SELECT ...)}. */
    public Condition geSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.GE, subQuery);
    }

    /** Scalar sub-query comparison: {@code col < (SELECT ...)}. */
    public Condition ltSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.LT, subQuery);
    }

    /** Scalar sub-query comparison: {@code col <= (SELECT ...)}. */
    public Condition leSubQuery(Queryable<?> subQuery) {
        return subQueryCondition(Operator.LE, subQuery);
    }

    // ---------------------------------------------------------- conditional overloads

    /** {@code col = value} only when {@code condition} is true; a skipped condition composes as nothing. */
    public Condition eq(boolean condition, V value) { return condition ? eq(value) : Condition.EMPTY; }
    public Condition ne(boolean condition, V value) { return condition ? ne(value) : Condition.EMPTY; }
    public Condition gt(boolean condition, V value) { return condition ? gt(value) : Condition.EMPTY; }
    public Condition ge(boolean condition, V value) { return condition ? ge(value) : Condition.EMPTY; }
    public Condition lt(boolean condition, V value) { return condition ? lt(value) : Condition.EMPTY; }
    public Condition le(boolean condition, V value) { return condition ? le(value) : Condition.EMPTY; }
    public Condition like(boolean condition, String value) { return condition ? like(value) : Condition.EMPTY; }
    public Condition notLike(boolean condition, String value) { return condition ? notLike(value) : Condition.EMPTY; }
    public Condition startsWith(boolean condition, String value) { return condition ? startsWith(value) : Condition.EMPTY; }
    public Condition endsWith(boolean condition, String value) { return condition ? endsWith(value) : Condition.EMPTY; }
    public Condition in(boolean condition, Collection<V> values) { return condition ? in(values) : Condition.EMPTY; }
    public Condition notIn(boolean condition, Collection<V> values) { return condition ? notIn(values) : Condition.EMPTY; }
    public Condition between(boolean condition, V lo, V hi) { return condition ? between(lo, hi) : Condition.EMPTY; }
    public Condition notBetween(boolean condition, V lo, V hi) { return condition ? notBetween(lo, hi) : Condition.EMPTY; }
    public Condition in(boolean condition, V... values) { return condition ? in(values) : Condition.EMPTY; }
    public Condition notIn(boolean condition, V... values) { return condition ? notIn(values) : Condition.EMPTY; }
    public Condition in(boolean condition, Queryable<?> subQuery) { return condition ? in(subQuery) : Condition.EMPTY; }
    public Condition notIn(boolean condition, Queryable<?> subQuery) { return condition ? notIn(subQuery) : Condition.EMPTY; }
    public Condition eqSubQuery(boolean condition, Queryable<?> subQuery) { return condition ? eqSubQuery(subQuery) : Condition.EMPTY; }
    public Condition neSubQuery(boolean condition, Queryable<?> subQuery) { return condition ? neSubQuery(subQuery) : Condition.EMPTY; }
    public Condition gtSubQuery(boolean condition, Queryable<?> subQuery) { return condition ? gtSubQuery(subQuery) : Condition.EMPTY; }
    public Condition geSubQuery(boolean condition, Queryable<?> subQuery) { return condition ? geSubQuery(subQuery) : Condition.EMPTY; }
    public Condition ltSubQuery(boolean condition, Queryable<?> subQuery) { return condition ? ltSubQuery(subQuery) : Condition.EMPTY; }
    public Condition leSubQuery(boolean condition, Queryable<?> subQuery) { return condition ? leSubQuery(subQuery) : Condition.EMPTY; }
    public Condition isNull(boolean condition) { return condition ? isNull() : Condition.EMPTY; }
    public Condition isNotNull(boolean condition) { return condition ? isNotNull() : Condition.EMPTY; }

    // ------------------------------------------------------------------ internals

    private Condition add(Operator operator, Object value) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(
                ref, operator, List.of(meta.toDbValue(value))), false);
    }

    private Condition addText(Operator operator, String value) {
        return Condition.leaf(com.lightquery.query.model.Condition.of(
                ref, operator, List.of(Escape.contains(value))), false);
    }

    private Condition compareColumn(SFunction<?, V> other, Operator operator) {
        return Condition.leaf(com.lightquery.query.model.Condition.ofColumns(
                ref, operator, Conditions.detachedRef(other)), false);
    }

    private Condition subQueryCondition(Operator operator, Queryable<?> subQuery) {
        return Condition.leaf(com.lightquery.query.model.Condition.ofSubQuery(
                ref, operator, subQuery.model()), true);
    }

    private List<Object> converted(Collection<V> values) {
        return (values == null ? List.<V>of() : values).stream()
                .map(meta::toDbValue)
                .toList();
    }
}
