package com.lightquery.query.model;

import com.lightquery.exception.SqlBuildException;

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
    private final Operator operator;
    private final List<Object> values;
    private final Selectable other;
    private final QueryModel subQuery;
    private final String rawSql;

    private Condition(Selectable target, Operator operator, List<Object> values,
                      Selectable other, QueryModel subQuery, String rawSql) {
        this.target = target;
        this.operator = operator;
        this.values = values == null ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(values));
        this.other = other;
        this.subQuery = subQuery;
        this.rawSql = rawSql;
    }

    /** Column compared to literal value(s). */
    public static Condition of(Selectable target, Operator operator, List<Object> values) {
        return new Condition(target, operator, values, null, null, null);
    }

    /** Raw SQL fragment with bound {@code ?} parameters (see {@code whereRaw}). */
    public static Condition raw(String fragment, List<Object> values) {
        if (fragment == null || fragment.isBlank()) {
            throw new SqlBuildException("whereRaw fragment must not be blank");
        }
        return new Condition(null, Operator.RAW, values, null, null, fragment);
    }

    /** Column compared to another column/aggregate (join ON, correlated conditions). */
    public static Condition ofColumns(Selectable target, Operator operator, Selectable other) {
        return new Condition(target, operator, null, other, null, null);
    }

    /** Column compared to a sub-query (IN / NOT IN / scalar comparison). */
    public static Condition ofSubQuery(Selectable target, Operator operator, QueryModel subQuery) {
        return new Condition(target, operator, null, null, subQuery, null);
    }

    /** EXISTS / NOT EXISTS — no target column. */
    public static Condition exists(QueryModel subQuery, boolean negated) {
        return new Condition(null, negated ? Operator.NOT_EXISTS : Operator.EXISTS, null, null, subQuery, null);
    }

    public Selectable getTarget() {
        return target;
    }

    public Operator getOperator() {
        return operator;
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

    /** The raw fragment; only meaningful when the operator is {@code RAW}. */
    public String getRawSql() {
        return rawSql;
    }
}
