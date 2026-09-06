package com.lightquery;

import com.lightquery.lambda.SFunction;

/**
 * Factory for aggregate expressions used in {@code select} / {@code having} /
 * {@code orderBy}, e.g.:
 *
 * <pre>{@code
 * db.queryable(Order.class)
 *   .select(Order::getStatus, F.count().as("cnt"), F.sum(Order::getAmount).as("total"))
 *   .groupBy(Order::getStatus)
 *   .having(w -> w.gt(F.count(), 2))
 *   .toTupleList();
 * }</pre>
 */
public final class F {

    private F() {
    }

    /** {@code count(*)} */
    public static Agg count() {
        return new Agg("count", null, false, null);
    }

    /** {@code count(col)} */
    public static <C> Agg count(SFunction<C, ?> col) {
        return new Agg("count", col, false, null);
    }

    /** {@code count(DISTINCT col)} */
    public static <C> Agg countDistinct(SFunction<C, ?> col) {
        return new Agg("count", col, true, null);
    }

    /** {@code sum(col)} */
    public static <C> Agg sum(SFunction<C, ?> col) {
        return new Agg("sum", col, false, null);
    }

    /** {@code avg(col)} */
    public static <C> Agg avg(SFunction<C, ?> col) {
        return new Agg("avg", col, false, null);
    }

    /** {@code max(col)} */
    public static <C> Agg max(SFunction<C, ?> col) {
        return new Agg("max", col, false, null);
    }

    /** {@code min(col)} */
    public static <C> Agg min(SFunction<C, ?> col) {
        return new Agg("min", col, false, null);
    }
}
