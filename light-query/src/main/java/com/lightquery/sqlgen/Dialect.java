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

    /**
     * Assembles an UPDATE statement that joins other tables for filtering.
     * The pieces are pre-rendered (quoted, parameters already bound in the
     * fragment context); each dialect orders them per its own syntax, e.g.
     * MySQL: {@code UPDATE a JOIN b ON .. SET ..} — PostgreSQL:
     * {@code UPDATE a SET .. FROM b WHERE ..}.
     *
     * @throws SqlBuildException when the database does not support UPDATE with JOIN
     */
    default String updateJoinSql(JoinPieces pieces) {
        throw new SqlBuildException("The " + getClass().getSimpleName()
                + " dialect does not support UPDATE with JOIN — filter with an IN subquery instead");
    }

    /**
     * Assembles a DELETE statement that joins other tables for filtering,
     * e.g. MySQL: {@code DELETE t0 FROM a t0 JOIN b ON ..} — PostgreSQL:
     * {@code DELETE FROM a USING b WHERE ..}.
     *
     * @throws SqlBuildException when the database does not support DELETE with JOIN
     */
    default String deleteJoinSql(JoinPieces pieces) {
        throw new SqlBuildException("The " + getClass().getSimpleName()
                + " dialect does not support DELETE with JOIN — filter with an IN subquery instead");
    }

    /**
     * Pre-rendered building blocks of an UPDATE/DELETE with joins. Each
     * dialect assembles them in its own order:
     * <ul>
     *   <li>{@code alias} — the target table alias, e.g. {@code t0}</li>
     *   <li>{@code rootDefinition} — target table plus alias, e.g. {@code t_user t0}</li>
     *   <li>{@code joinedTables} — inline joins with ON, e.g. {@code INNER JOIN t_order t1 ON ..}</li>
     *   <li>{@code joinTableList} — comma-separated table+alias list for FROM/USING styles</li>
     *   <li>{@code onConditions} — the join ON conditions as a WHERE fragment</li>
     *   <li>{@code setClauseQualified} — SET entries qualified with the target alias</li>
     *   <li>{@code setClauseBare} — SET entries as bare column names</li>
     *   <li>{@code whereClause} — the user WHERE clause, prefixed with {@code WHERE} or empty</li>
     * </ul>
     */
    record JoinPieces(String alias, String rootDefinition, String joinedTables, String joinTableList,
                      String onConditions, String setClauseQualified, String setClauseBare,
                      String whereClause) {
    }
}
