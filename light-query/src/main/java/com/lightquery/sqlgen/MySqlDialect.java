package com.lightquery.sqlgen;

import java.util.List;

/** MySQL / MariaDB dialect. */
public class MySqlDialect implements Dialect {

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String page(String sql, long offset, long limit) {
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    /**
     * MySQL treats the backslash as the default LIKE escape character, so no
     * explicit ESCAPE clause is needed.
     */
    @Override
    public String likeEscapeClause() {
        return "";
    }

    @Override
    public String updateJoinSql(JoinPieces p) {
        return "UPDATE " + p.rootDefinition() + ", " + p.joinTableList()
                + " SET " + p.setClauseQualified() + p.whereClause();
    }

    @Override
    public String deleteJoinSql(JoinPieces p) {
        return "DELETE " + p.alias() + " FROM " + p.rootDefinition() + ", " + p.joinTableList()
                + p.whereClause();
    }

    @Override
    public boolean supportsUpsert() {
        return true;
    }

    @Override
    public String upsertSql(String table, List<String> columns, List<String> keyColumns, int valueRows) {
        String quotedTable = quote(table);
        String cols = String.join(", ", columns.stream().map(this::quote).toList());
        String placeholders = "";
        for (int r = 0; r < valueRows; r++) {
            if (r > 0) placeholders += ", ";
            placeholders += "(" + "?,".repeat(columns.size() - 1) + "?)";
        }
        StringBuilder updates = new StringBuilder();
        for (String col : columns) {
            if (keyColumns.contains(col)) continue;
            if (updates.length() > 0) updates.append(", ");
            updates.append(quote(col)).append(" = VALUES(").append(quote(col)).append(")");
        }
        return "INSERT INTO " + quotedTable + " (" + cols + ") VALUES " + placeholders
                + " ON DUPLICATE KEY UPDATE " + updates;
    }
}
