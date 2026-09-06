package com.lightquery.sqlgen;

/**
 * Oracle dialect (12c+ row-limiting syntax). Identifiers are quoted with
 * double quotes; pagination uses {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}
 * and sequences are addressed with {@code seq.NEXTVAL FROM DUAL}.
 */
public class OracleDialect implements Dialect {

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String page(String sql, long offset, long limit) {
        return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * Oracle has no default LIKE escape character, so an explicit ESCAPE
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
        return "SELECT " + quote(sequenceName) + ".NEXTVAL FROM DUAL";
    }
}
