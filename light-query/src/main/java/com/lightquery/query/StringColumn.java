package com.lightquery.query;

import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

import java.util.Collections;

/**
 * Text-match layer over {@link ComparableColumn}, created by
 * {@code strCol(Entity::getStringProperty)}. Only {@code String} properties
 * expose these methods, so {@code col(User::getAge).like("x")} does not
 * compile anymore — use {@code strCol(User::getName).like("x")}.
 *
 * @param <B> the builder this column was created from
 */
public class StringColumn<B> extends ComparableColumn<B, String> {

    protected StringColumn(B builder, ConditionGroup group, ColumnRef ref,
                           ColumnMeta meta, ColumnResolver resolver, Runnable onSubQuery) {
        super(builder, group, ref, meta, resolver, onSubQuery);
    }

    /** Text columns only: contains match with {@code \ % _} escaped, both sides wrapped in {@code %}. */
    public B like(String contains) {
        group.add(Condition.of(ref, Operator.LIKE, Collections.singletonList(Escape.contains(contains))));
        return builder;
    }

    /** Text columns only. */
    public B notLike(String contains) {
        group.add(Condition.of(ref, Operator.NOT_LIKE, Collections.singletonList(Escape.contains(contains))));
        return builder;
    }

    /** Text columns only: right-side wildcard. */
    public B startsWith(String prefix) {
        group.add(Condition.of(ref, Operator.LIKE, Collections.singletonList(Escape.prefix(prefix))));
        return builder;
    }

    /** Text columns only: left-side wildcard. */
    public B endsWith(String suffix) {
        group.add(Condition.of(ref, Operator.LIKE, Collections.singletonList(Escape.suffix(suffix))));
        return builder;
    }
}
