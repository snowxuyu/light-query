package com.lightquery.sqlgen;

import com.lightquery.exception.LightQueryException;

/** Picks a {@link Dialect} from a JDBC URL (used by the LightQuery bootstrap). */
public final class Dialects {

    private Dialects() {
    }

    /**
     * Detects the dialect from the JDBC URL sub-protocol.
     *
     * @throws LightQueryException for unknown URLs, telling the user how to
     *         specify one explicitly
     */
    public static Dialect fromJdbcUrl(String url) {
        String urlLower = url == null ? "" : url.toLowerCase();
        if (urlLower.contains(":mysql:") || urlLower.contains(":mariadb:")) {
            return new MySqlDialect();
        }
        if (urlLower.contains(":postgresql:")) {
            return new PostgreSqlDialect();
        }
        if (urlLower.contains(":h2:")) {
            return new H2Dialect();
        }
        if (urlLower.contains(":oracle:")) {
            return new OracleDialect();
        }
        if (urlLower.contains(":sqlserver:")) {
            return new SqlServerDialect();
        }
        throw new LightQueryException("Cannot detect a dialect from JDBC URL '" + url
                + "'. Call LightQuery.of(dataSource, dialect) with an explicit Dialect, "
                + "e.g. new MySqlDialect().");
    }
}
