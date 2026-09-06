package com.lightquery.sqlgen;

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
        return "UPDATE " + p.rootDefinition() + " " + p.joinedTables()
                + " SET " + p.setClauseQualified() + p.whereClause();
    }

    @Override
    public String deleteJoinSql(JoinPieces p) {
        return "DELETE " + p.alias() + " FROM " + p.rootDefinition() + " " + p.joinedTables()
                + p.whereClause();
    }
}
