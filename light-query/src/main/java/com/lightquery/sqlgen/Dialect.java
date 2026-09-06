package com.lightquery.sqlgen;

import com.lightquery.exception.SqlBuildException;

/**
 * Database dialect: identifier quoting, pagination syntax, LIKE escape
 * clause and sequence support. Every syntax difference between databases
 * lives here — {@code SqlBuilder} must stay dialect-agnostic.
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

    /** Whether the database supports named sequences (SEQUENCE key generation). */
    default boolean supportsSequences() {
        return false;
    }

    /**
     * SQL that returns the next value of a sequence as a single-row,
     * single-column result, e.g. {@code SELECT nextval('order_seq')}.
     *
     * @throws SqlBuildException when the database does not support sequences
     */
    default String sequenceNextValueSql(String sequenceName) {
        throw new SqlBuildException("The " + getClass().getSimpleName()
                + " dialect does not support sequences — use @GeneratedValue(IDENTITY) instead");
    }
}
