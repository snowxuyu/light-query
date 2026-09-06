package com.lightquery.sqlgen;

import com.lightquery.exception.SqlBuildException;

/**
 * H2 2.x dialect — same quoting, pagination and LIKE escape behaviour as
 * PostgreSQL; sequences use the standard {@code NEXT VALUE FOR} syntax.
 * Kept as its own class so H2 specifics have a clear home.
 */
public class H2Dialect extends PostgreSqlDialect {

    @Override
    public String sequenceNextValueSql(String sequenceName) {
        return "SELECT NEXT VALUE FOR " + quote(sequenceName);
    }

    // H2 has no multi-table UPDATE/DELETE syntax — the PostgreSQL overrides
    // inherited otherwise would render unsupported statements.

    @Override
    public String updateJoinSql(JoinPieces p) {
        throw new SqlBuildException("The H2Dialect dialect does not support UPDATE with JOIN "
                + "— filter with an IN subquery instead");
    }

    @Override
    public String deleteJoinSql(JoinPieces p) {
        throw new SqlBuildException("The H2Dialect dialect does not support DELETE with JOIN "
                + "— filter with an IN subquery instead");
    }
}
