package com.lightquery.sqlgen;

/**
 * Database dialect: identifier quoting, pagination syntax and LIKE escape
 * clause. Every syntax difference between databases lives here —
 * {@code SqlBuilder} must stay dialect-agnostic.
 */
public interface Dialect {

    /** Wraps an identifier (table/column/alias) with the database quote char. */
    String quote(String identifier);

    /** Appends pagination to a fully built statement. */
    String page(String sql, long offset, long limit);

    /**
     * Clause appended after {@code LIKE ?} when the database needs an explicit
     * escape character (light-query escapes {@code \ % _} in LIKE values).
     */
    default String likeEscapeClause() {
        return "";
    }
}
