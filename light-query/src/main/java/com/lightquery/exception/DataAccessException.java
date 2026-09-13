package com.lightquery.exception;

import java.util.ArrayList;
import java.util.Collections;
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
        // bind values legitimately contain nulls; List.copyOf would reject them
        // and mask the real SQL error with a NullPointerException
        this.params = params == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(params));
    }

    public String getSql() {
        return sql;
    }

    public List<Object> getParams() {
        return params;
    }
}
