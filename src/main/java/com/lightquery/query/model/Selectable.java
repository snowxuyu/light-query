package com.lightquery.query.model;

/**
 * Anything that can appear as a column-like expression in SELECT / WHERE /
 * GROUP BY / ORDER BY: a resolved column or an aggregate expression.
 * Sealed so the renderer can handle every variant exhaustively.
 */
public sealed interface Selectable permits ColumnRef, Expr {
}
