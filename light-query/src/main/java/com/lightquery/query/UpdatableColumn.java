package com.lightquery.query;

import com.lightquery.lambda.SFunction;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

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
 * <p>Range comparisons live on {@link UpdatableComparableColumn} (via
 * {@code cmpCol(...)}), text matches on {@link UpdatableStringColumn} (via
 * {@code strCol(...)}), {@code setIncrement} on {@link UpdatableNumberColumn}
 * (via {@code numCol(...)}). Every method returns the owning {@link Updatable}
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
        return in(java.util.Arrays.asList(values));
    }

    /** Excludes every value; an empty collection renders {@code 1 = 1}. */
    public Updatable<T> notIn(Collection<V> values) {
        group.add(Condition.of(ref, Operator.NOT_IN, converted(values)));
        return updatable;
    }

    @SafeVarargs
    public final Updatable<T> notIn(V... values) {
        return notIn(java.util.Arrays.asList(values));
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
