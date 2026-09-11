package com.lightquery.query;

import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.ConditionGroup;

/**
 * Numeric layer over {@link UpdatableComparableColumn}, created by
 * {@code numCol(Entity::getNumericProperty)}. Carries
 * {@code setIncrement} in addition to the full comparison family.
 *
 * @param <T> the updated entity type
 * @param <V> the numeric value type of the property
 */
public class UpdatableNumberColumn<T, V extends Number & Comparable<V>> extends UpdatableComparableColumn<T, V> {

    protected UpdatableNumberColumn(Updatable<T> updatable, ConditionGroup group, ColumnRef ref,
                                    ColumnMeta meta, ColumnResolver resolver, boolean setTargetsRoot) {
        super(updatable, group, ref, meta, resolver, setTargetsRoot);
    }

    /** Numeric self-increment: {@code col = col + delta} (negative delta decrements). */
    public Updatable<T> setIncrement(long delta) {
        requireSetAllowed();
        requireNotPrimaryKey();
        updatable.addIncrement(meta.getColumnName(), delta);
        return updatable;
    }
}
