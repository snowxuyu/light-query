package com.lightquery.query.model;

import com.lightquery.exception.SqlBuildException;

/**
 * A fully resolved aggregate expression such as {@code count(*)} or
 * {@code sum(t1.amount)}. Built by the API layer from {@link com.lightquery.F};
 * the alias (if any) is only rendered in the SELECT list and doubles as the
 * {@link com.lightquery.Tuple} label.
 */
public record Expr(String func, ColumnRef arg, boolean distinct, String alias) implements Selectable {

    public Expr {
        if (func == null || func.isBlank()) {
            throw new SqlBuildException("Aggregate function name must not be blank");
        }
    }

    public Expr(String func, ColumnRef arg) {
        this(func, arg, false, null);
    }

    /** Returns a copy labelled with {@code alias} (SELECT list / Tuple label). */
    public Expr as(String alias) {
        return new Expr(func, arg, distinct, alias);
    }
}
