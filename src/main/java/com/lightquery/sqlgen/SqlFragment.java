package com.lightquery.sqlgen;

import java.util.Collections;
import java.util.List;

/**
 * A generated SQL statement plus its ordered bind parameters.
 *
 * @param sql    the SQL text with {@code ?} placeholders
 * @param params bind values in placeholder order
 */
public record SqlFragment(String sql, List<Object> params) {

    public SqlFragment {
        params = params == null ? List.of() : Collections.unmodifiableList(params);
    }
}
