package com.lightquery.query;

import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

import java.util.Collections;

/**
 * Text-match layer over {@link UpdatableComparableColumn}, created by
 * {@code strCol(Entity::getStringProperty)}.
 *
 * @param <T> the updated entity type
 */
public class UpdatableStringColumn<T> extends UpdatableComparableColumn<T, String> {

    protected UpdatableStringColumn(Updatable<T> updatable, ConditionGroup group, ColumnRef ref,
                                    ColumnMeta meta, ColumnResolver resolver, boolean setTargetsRoot) {
        super(updatable, group, ref, meta, resolver, setTargetsRoot);
    }

    /** Text columns only: contains match with {@code \ % _} escaped. */
    public Updatable<T> like(String contains) {
        group.add(Condition.of(ref, Operator.LIKE, Collections.singletonList(Escape.contains(contains))));
        return updatable;
    }

    /** Text columns only: negated contains match with {@code \ % _} escaped. */
    public Updatable<T> notLike(String contains) {
        group.add(Condition.of(ref, Operator.NOT_LIKE, Collections.singletonList(Escape.contains(contains))));
        return updatable;
    }

    /** Text columns only: right-side wildcard. */
    public Updatable<T> startsWith(String prefix) {
        group.add(Condition.of(ref, Operator.LIKE, Collections.singletonList(Escape.prefix(prefix))));
        return updatable;
    }

    /** Text columns only: left-side wildcard. */
    public Updatable<T> endsWith(String suffix) {
        group.add(Condition.of(ref, Operator.LIKE, Collections.singletonList(Escape.suffix(suffix))));
        return updatable;
    }
}
