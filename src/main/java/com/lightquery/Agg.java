package com.lightquery;

import com.lightquery.lambda.SFunction;

/**
 * Handle of an aggregate expression created by {@link F}, e.g.
 * {@code F.count(User::getId).as("cnt")}. Passed to {@code select} /
 * {@code having} / {@code orderBy}; resolved to a column by the query.
 *
 * @param func     aggregate function name (count/sum/avg/max/min)
 * @param fn       the aggregated property, null for {@code count(*)}
 * @param distinct whether the argument is prefixed with DISTINCT
 * @param alias    output label, set via {@link #as(String)}
 */
public record Agg(String func, SFunction<?, ?> fn, boolean distinct, String alias) {

    public Agg {
        if (func == null || func.isBlank()) {
            throw new IllegalArgumentException("Aggregate function name must not be blank");
        }
    }

    /** Labels the expression for use in ORDER BY aliases and Tuple lookups. */
    public Agg as(String alias) {
        return new Agg(func, fn, distinct, alias);
    }
}
