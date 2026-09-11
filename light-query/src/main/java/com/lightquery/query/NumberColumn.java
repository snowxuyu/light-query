package com.lightquery.query;

import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.ConditionGroup;

/**
 * Numeric marker layer over {@link ComparableColumn}, created by
 * {@code numCol(Entity::getNumericProperty)}. Carries the full comparison
 * family (inherited) and marks the column as numeric for aggregations and
 * {@code setIncrement} (see the {@code Updatable} number layer).
 *
 * @param <B> the builder this column was created from
 * @param <V> the numeric value type of the property
 */
public class NumberColumn<B, V extends Number & Comparable<V>> extends ComparableColumn<B, V> {

    protected NumberColumn(B builder, ConditionGroup group, ColumnRef ref,
                           ColumnMeta meta, ColumnResolver resolver, Runnable onSubQuery) {
        super(builder, group, ref, meta, resolver, onSubQuery);
    }
}
