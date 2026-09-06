package com.lightquery.query.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One comparison. The left side is a {@link Selectable}; the right side is
 * one of: literal {@link #values}, another {@link #other} selectable
 * (column-to-column), or a {@link #subQuery} (IN / scalar / EXISTS).
 */
public final class Condition {

    private final Selectable target;
    private final Op op;
    private final List<Object> values;
    private final Selectable other;
    private final QueryModel subQuery;

    private Condition(Selectable target, Op op, List<Object> values, Selectable other, QueryModel subQuery) {
        this.target = target;
        this.op = op;
        this.values = values == null ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(values));
        this.other = other;
        this.subQuery = subQuery;
    }

    /** Column compared to literal value(s). */
    public static Condition of(Selectable target, Op op, List<Object> values) {
        return new Condition(target, op, values, null, null);
    }

    /** Column compared to another column/aggregate (join ON, correlated conditions). */
    public static Condition ofColumns(Selectable target, Op op, Selectable other) {
        return new Condition(target, op, null, other, null);
    }

    /** Column compared to a sub-query (IN / NOT IN / scalar comparison). */
    public static Condition ofSubQuery(Selectable target, Op op, QueryModel subQuery) {
        return new Condition(target, op, null, null, subQuery);
    }

    /** EXISTS / NOT EXISTS — no target column. */
    public static Condition exists(QueryModel subQuery, boolean negated) {
        return new Condition(null, negated ? Op.NOT_EXISTS : Op.EXISTS, null, null, subQuery);
    }

    public Selectable getTarget() {
        return target;
    }

    public Op getOp() {
        return op;
    }

    public List<Object> getValues() {
        return values;
    }

    public Selectable getOther() {
        return other;
    }

    public QueryModel getSubQuery() {
        return subQuery;
    }
}
