package com.lightquery.exception;

import java.util.List;

/**
 * Wraps a {@link java.sql.SQLException} raised while executing a statement.
 * Carries the SQL text and bind parameters for diagnosis (values may contain
 * sensitive data — do not log them in production).
 */
public class DataAccessException extends LightQueryException {

    private final String sql;
    private final List<Object> params;

    public DataAccessException(String sql, List<Object> params, Throwable cause) {
        super("SQL execution failed: " + sql + " | params=" + params, cause);
        this.sql = sql;
        this.params = List.copyOf(params);
    }

    public String getSql() {
        return sql;
    }

    public List<Object> getParams() {
        return params;
    }
}
