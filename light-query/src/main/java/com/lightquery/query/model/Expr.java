package com.lightquery.query.model;

import com.lightquery.exception.SqlBuildException;

/**
 * A fully resolved aggregate expression such as {@code count(*)} or
 * {@code sum(t1.amount)}. Built by the API layer from {@link com.lightquery.Aggregations};
 * the alias (if any) is only rendered in the SELECT list and doubles as the
 * {@link com.lightquery.Tuple} label.
 */
public record Expr(String function, ColumnRef argument, boolean distinct, String alias)
        implements Selectable {

    public Expr {
        if (function == null || function.isBlank()) {
            throw new SqlBuildException("Aggregate function name must not be blank");
        }
    }

    public Expr(String function, ColumnRef argument) {
        this(function, argument, false, null);
    }

    /** Returns a copy labelled with {@code alias} (SELECT list / Tuple label). */
    public Expr as(String alias) {
        return new Expr(function, argument, distinct, alias);
    }
}
