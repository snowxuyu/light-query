package com.lightquery.sqlgen;

import java.util.List;

/** PostgreSQL dialect (also usable for most ANSI-compatible databases). */
public class PostgreSqlDialect implements Dialect {

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String page(String sql, long offset, long limit) {
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    /**
     * PostgreSQL has no default LIKE escape character, so an explicit
     * ESCAPE clause is emitted for every escaped LIKE.
     */
    @Override
    public String likeEscapeClause() {
        return " ESCAPE '\\'";
    }

    @Override
    public boolean supportsSequences() {
        return true;
    }

    @Override
    public String sequenceNextValueSql(String sequenceName) {
        return "SELECT nextval('" + sequenceName + "')";
    }

    @Override
    public String updateJoinSql(JoinPieces p) {
        return "UPDATE " + p.rootDefinition() + " SET " + p.setClauseBare()
                + " FROM " + p.joinTableList() + whereWithOn(p);
    }

    @Override
    public String deleteJoinSql(JoinPieces p) {
        return "DELETE FROM " + p.rootDefinition() + " USING " + p.joinTableList() + whereWithOn(p);
    }

    /** PostgreSQL UPDATE FROM / DELETE USING carry the ON conditions inside the WHERE clause. */
    private String whereWithOn(JoinPieces p) {
        // ON conditions are pre-merged into the WHERE clause by SqlBuilder.
        return p.whereClause();
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
        String keys = String.join(", ", keyColumns.stream().map(this::quote).toList());
        StringBuilder updates = new StringBuilder();
        for (String col : columns) {
            if (keyColumns.contains(col)) continue;
            if (updates.length() > 0) updates.append(", ");
            updates.append(quote(col)).append(" = EXCLUDED.").append(quote(col));
        }
        return "INSERT INTO " + quotedTable + " (" + cols + ") VALUES " + placeholders
                + " ON CONFLICT (" + keys + ") DO UPDATE SET " + updates;
    }
}
