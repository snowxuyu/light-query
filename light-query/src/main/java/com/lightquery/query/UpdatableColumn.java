package com.lightquery.query;

import com.lightquery.lambda.SFunction;
import com.lightquery.exception.SqlBuildException;
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
 * The strongly-typed column handle of an {@link Updatable} — carries the SET
 * family plus the equality condition family for this column, both checked
 * against the property type at compile time:
 *
 * <pre>{@code
 * db.updatable(User.class)
 *     .col(User::getStatus).set(Status.FROZEN)
 *     .col(User::getId).eq(5L)
 *     .execute();
 * }</pre>
 *
 * <p>This single layer carries the SET family plus the whole condition
 * family, both checked against the property type at compile time. Text
 * matches and {@code setIncrement} are only meaningful on String / numeric
 * columns — the framework renders them as written and leaves the type
 * mismatch to the database. Every method returns the owning {@link Updatable}
 * so the chain continues with the next column or terminal.</p>
 *
 * @param <T> the updated entity type
 * @param <V> the value type of the property
 */
public class UpdatableColumn<T, V> {

    protected final Updatable<T> updatable;
    protected final ConditionGroup group;
    protected final ColumnRef ref;
    protected final ColumnMeta meta;
    protected final ColumnResolver resolver;
    /** Whether this column belongs to the updated entity (SET targets it); joined columns are condition-only. */
    protected final boolean setTargetsRoot;

    protected UpdatableColumn(Updatable<T> updatable, ConditionGroup group, ColumnRef ref,
                              ColumnMeta meta, ColumnResolver resolver, boolean setTargetsRoot) {
        this.updatable = updatable;
        this.group = group;
        this.ref = ref;
        this.meta = meta;
        this.resolver = resolver;
        this.setTargetsRoot = setTargetsRoot;
    }

    // ------------------------------------------------------------------ SET family

    /** Sets the column to the value (updated entity only; primary keys are rejected). */
    public Updatable<T> set(V value) {
        requireSetAllowed();
        requireNotPrimaryKey();
        updatable.addSet(meta.getColumnName(), meta.toDbValue(value));
        return updatable;
    }

    /** Sets the column to NULL (updated entity only; primary keys are rejected). */
    public Updatable<T> setNull() {
        requireSetAllowed();
        requireNotPrimaryKey();
        updatable.addSet(meta.getColumnName(), null);
        return updatable;
    }

    // ------------------------------------------------------------------ condition family

    /** {@code col = value}; {@code null} renders {@code col IS NULL}. */
    public Updatable<T> eq(V value) {
        return add(Operator.EQ, value);
    }

    /** {@code col <> value}; {@code null} renders {@code col IS NOT NULL}. */
    public Updatable<T> ne(V value) {
        return add(Operator.NE, value);
    }

    /** Matches any value; an empty collection renders {@code 1 = 0}. */
    public Updatable<T> in(Collection<V> values) {
        group.add(Condition.of(ref, Operator.IN, converted(values)));
        return updatable;
    }

    @SafeVarargs
    public final Updatable<T> in(V... values) {
        return in(Arrays.asList(values));
    }

    /** Excludes every value; an empty collection renders {@code 1 = 1}. */
    public Updatable<T> notIn(Collection<V> values) {
        group.add(Condition.of(ref, Operator.NOT_IN, converted(values)));
        return updatable;
    }

    @SafeVarargs
    public final Updatable<T> notIn(V... values) {
        return notIn(Arrays.asList(values));
    }

    /** {@code col IS NULL}. */
    public Updatable<T> isNull() {
        group.add(Condition.of(ref, Operator.IS_NULL, null));
        return updatable;
    }

    /** {@code col IS NOT NULL}. */
    public Updatable<T> isNotNull() {
        group.add(Condition.of(ref, Operator.IS_NOT_NULL, null));
        return updatable;
    }

    /**
     * Column-to-column equality against another property of the same value
     * type.
     */
    public <C> Updatable<T> eqColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.EQ, resolver.resolve(other).ref()));
        return updatable;
    }

    /** {@code col > value}. */
    public Updatable<T> gt(V value) {
        return add(Operator.GT, value);
    }

    /** {@code col >= value}. */
    public Updatable<T> ge(V value) {
        return add(Operator.GE, value);
    }

    /** {@code col < value}. */
    public Updatable<T> lt(V value) {
        return add(Operator.LT, value);
    }

    /** {@code col <= value}. */
    public Updatable<T> le(V value) {
        return add(Operator.LE, value);
    }

    /** Inclusive range. */
    public Updatable<T> between(V lo, V hi) {
        group.add(Condition.of(ref, Operator.BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))));
        return updatable;
    }

    /** Excludes the inclusive range. */
    public Updatable<T> notBetween(V lo, V hi) {
        group.add(Condition.of(ref, Operator.NOT_BETWEEN,
                Arrays.asList(meta.toDbValue(lo), meta.toDbValue(hi))));
        return updatable;
    }

    /** Column-to-column {@code >} against another property of the same value type. */
    public <C> Updatable<T> gtColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.GT, resolver.resolve(other).ref()));
        return updatable;
    }

    /** Column-to-column {@code >=} against another property of the same value type. */
    public <C> Updatable<T> geColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.GE, resolver.resolve(other).ref()));
        return updatable;
    }

    /** Column-to-column {@code <} against another property of the same value type. */
    public <C> Updatable<T> ltColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.LT, resolver.resolve(other).ref()));
        return updatable;
    }

    /** Column-to-column {@code <=} against another property of the same value type. */
    public <C> Updatable<T> leColumn(SFunction<C, V> other) {
        group.add(Condition.ofColumns(ref, Operator.LE, resolver.resolve(other).ref()));
        return updatable;
    }

    /**
     * Contains match with {@code \ % _} escaped, both sides wrapped in
     * {@code %}. Only meaningful on String columns.
     */
    public Updatable<T> like(String contains) {
        group.add(Condition.of(ref, Operator.LIKE,
                Collections.singletonList(Escape.contains(contains))));
        return updatable;
    }

    /** Negated contains match. Only meaningful on String columns. */
    public Updatable<T> notLike(String contains) {
        group.add(Condition.of(ref, Operator.NOT_LIKE,
                Collections.singletonList(Escape.contains(contains))));
        return updatable;
    }

    /** Right-side wildcard. Only meaningful on String columns. */
    public Updatable<T> startsWith(String prefix) {
        group.add(Condition.of(ref, Operator.LIKE,
                Collections.singletonList(Escape.prefix(prefix))));
        return updatable;
    }

    /** Left-side wildcard. Only meaningful on String columns. */
    public Updatable<T> endsWith(String suffix) {
        group.add(Condition.of(ref, Operator.LIKE,
                Collections.singletonList(Escape.suffix(suffix))));
        return updatable;
    }

    /** Numeric self-increment: {@code col = col + delta} (negative delta decrements). Only meaningful on numeric columns. */
    public Updatable<T> setIncrement(long delta) {
        requireSetAllowed();
        requireNotPrimaryKey();
        updatable.addIncrement(meta.getColumnName(), delta);
        return updatable;
    }

    // ---------------------------------------------------------- conditional overloads

    public Updatable<T> eq(boolean condition, V value) { return condition ? eq(value) : updatable; }
    public Updatable<T> ne(boolean condition, V value) { return condition ? ne(value) : updatable; }
    public Updatable<T> gt(boolean condition, V value) { return condition ? gt(value) : updatable; }
    public Updatable<T> ge(boolean condition, V value) { return condition ? ge(value) : updatable; }
    public Updatable<T> lt(boolean condition, V value) { return condition ? lt(value) : updatable; }
    public Updatable<T> le(boolean condition, V value) { return condition ? le(value) : updatable; }
    public Updatable<T> like(boolean condition, String value) { return condition ? like(value) : updatable; }
    public Updatable<T> in(boolean condition, Collection<V> values) { return condition ? in(values) : updatable; }
    public Updatable<T> between(boolean condition, V lo, V hi) { return condition ? between(lo, hi) : updatable; }
    public Updatable<T> notLike(boolean condition, String value) { return condition ? notLike(value) : updatable; }
    public Updatable<T> startsWith(boolean condition, String value) { return condition ? startsWith(value) : updatable; }
    public Updatable<T> endsWith(boolean condition, String value) { return condition ? endsWith(value) : updatable; }
    public Updatable<T> notIn(boolean condition, Collection<V> values) { return condition ? notIn(values) : updatable; }
    public Updatable<T> notBetween(boolean condition, V lo, V hi) { return condition ? notBetween(lo, hi) : updatable; }
    public Updatable<T> isNull(boolean condition) { return condition ? isNull() : updatable; }
    public Updatable<T> isNotNull(boolean condition) { return condition ? isNotNull() : updatable; }
    public Updatable<T> set(boolean condition, V value) { return condition ? set(value) : updatable; }
    public Updatable<T> setNull(boolean condition) { return condition ? setNull() : updatable; }
    public Updatable<T> setIncrement(boolean condition, long delta) { return condition ? setIncrement(delta) : updatable; }

    // ------------------------------------------------------------------ internals

    protected void requireSetAllowed() {
        if (!setTargetsRoot) {
            throw new SqlBuildException(
                    "set(...) modifies the updated entity only — joined table columns "
                            + "are read-only here. Reference joined tables in conditions instead.");
        }
    }

    protected void requireNotPrimaryKey() {
        if (meta.isPrimaryKey()) {
            throw new SqlBuildException(
                    "set(...) cannot modify primary key column '" + meta.getColumnName() + "'");
        }
    }

    protected Updatable<T> add(Operator operator, Object value) {
        group.add(Condition.of(ref, operator, Collections.singletonList(meta.toDbValue(value))));
        return updatable;
    }

    protected List<Object> converted(Collection<V> values) {
        return (values == null ? List.<V>of() : values).stream()
                .map(meta::toDbValue)
                .toList();
    }
}
