package com.lightquery.sqlgen;

/**
 * Microsoft SQL Server dialect (2012+ OFFSET/FETCH). Identifiers are quoted
 * with brackets; pagination uses {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY},
 * which SQL Server only accepts together with an ORDER BY — when the query
 * has none, a neutral {@code ORDER BY (SELECT NULL)} is appended.
 */
public class SqlServerDialect implements Dialect {

    @Override
    public String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    public String page(String sql, long offset, long limit) {
        if (!sql.toUpperCase().contains(" ORDER BY ")) {
            sql = sql + " ORDER BY (SELECT NULL)";
        }
        return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * SQL Server has no default LIKE escape character, so an explicit ESCAPE
     * clause is emitted for every escaped LIKE.
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
        return "SELECT NEXT VALUE FOR " + quote(sequenceName);
    }

    @Override
    public String updateJoinSql(JoinPieces p) {
        return "UPDATE " + p.alias() + " SET " + p.setClauseQualified() + " FROM "
                + p.rootDefinition() + ", " + p.joinTableList() + p.whereClause();
    }

    @Override
    public String deleteJoinSql(JoinPieces p) {
        return "DELETE " + p.alias() + " FROM " + p.rootDefinition() + ", " + p.joinTableList()
                + p.whereClause();
    }
}
