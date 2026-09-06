package com.lightquery;

import com.lightquery.lambda.SFunction;

/**
 * Handle of an aggregate expression created by {@link Aggregations}, e.g.
 * {@code Aggregations.count(User::getId).as("cnt")}. Passed to {@code select} /
 * {@code having} / {@code orderBy}; resolved to a column by the query.
 *
 * @param function  aggregate function name (count/sum/avg/max/min)
 * @param property  the aggregated property, null for {@code count(*)}
 * @param distinct  whether the argument is prefixed with DISTINCT
 * @param alias     output label, set via {@link #as(String)}
 */
public record Aggregate(String function, SFunction<?, ?> property, boolean distinct, String alias) {

    public Aggregate {
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("Aggregate function name must not be blank");
        }
    }

    /** Labels the expression for use in ORDER BY aliases and Tuple lookups. */
    public Aggregate as(String alias) {
        return new Aggregate(function, property, distinct, alias);
    }
}
