package com.lightquery.sqlgen;

import com.lightquery.exception.SqlBuildException;

import java.util.List;

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

    // H2 2.x rejects both PostgreSQL's ON CONFLICT .. DO UPDATE and MySQL's
    // ON DUPLICATE KEY UPDATE in regular mode; the native MERGE .. KEY form
    // is the supported upsert (verified against 2.2.224).

    @Override
    public String upsertSql(String table, List<String> columns, List<String> keyColumns, int valueRows) {
        String quotedTable = quote(table);
        String cols = String.join(", ", columns.stream().map(this::quote).toList());
        String keys = String.join(", ", keyColumns.stream().map(this::quote).toList());
        String placeholders = "";
        for (int r = 0; r < valueRows; r++) {
            if (r > 0) {
                placeholders += ", ";
            }
            placeholders += "(" + "?,".repeat(columns.size() - 1) + "?)";
        }
        return "MERGE INTO " + quotedTable + " (" + cols + ") KEY (" + keys + ") VALUES " + placeholders;
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
