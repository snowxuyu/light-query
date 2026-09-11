package com.lightquery;

import com.lightquery.lambda.SFunction;

/**
 * Factory for aggregate expressions used in {@code select} / {@code having} /
 * {@code orderBy}, e.g.:
 *
 * <pre>{@code
 * LightQuery.queryable(Order.class)
 *   .select(Order::getStatus, Aggregations.count().as("cnt"),
 *           Aggregations.sum(Order::getAmount).as("total"))
 *   .groupBy(Order::getStatus)
 *   .having(w -> w.gt(Aggregations.count(), 2))
 *   .toTupleList();
 * }</pre>
 */
public final class Aggregations {

    private Aggregations() {
    }

    /** {@code count(*)} */
    public static Aggregate count() {
        return new Aggregate("count", null, false, null);
    }

    /** {@code count(col)} */
    public static <C> Aggregate count(SFunction<C, ?> col) {
        return new Aggregate("count", col, false, null);
    }

    /** {@code count(DISTINCT col)} */
    public static <C> Aggregate countDistinct(SFunction<C, ?> col) {
        return new Aggregate("count", col, true, null);
    }

    /** {@code sum(col)} */
    public static <C, V extends Number> Aggregate sum(SFunction<C, V> col) {
        return new Aggregate("sum", col, false, null);
    }

    /** {@code avg(col)} */
    public static <C, V extends Number> Aggregate avg(SFunction<C, V> col) {
        return new Aggregate("avg", col, false, null);
    }

    /** {@code max(col)} */
    public static <C, V extends Number> Aggregate max(SFunction<C, V> col) {
        return new Aggregate("max", col, false, null);
    }

    /** {@code min(col)} */
    public static <C, V extends Number> Aggregate min(SFunction<C, V> col) {
        return new Aggregate("min", col, false, null);
    }
}
