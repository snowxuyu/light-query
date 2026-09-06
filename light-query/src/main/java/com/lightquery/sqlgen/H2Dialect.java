package com.lightquery.sqlgen;

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
}
