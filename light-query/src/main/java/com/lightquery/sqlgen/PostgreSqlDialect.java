package com.lightquery.sqlgen;

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
        if (p.onConditions().isEmpty()) {
            return p.whereClause();
        }
        return p.whereClause().isEmpty() ? " WHERE " + p.onConditions()
                : p.whereClause() + " AND " + p.onConditions();
    }
}
