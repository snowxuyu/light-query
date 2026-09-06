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
}
