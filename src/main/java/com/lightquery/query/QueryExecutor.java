package com.lightquery.query;

import com.lightquery.exec.ConnectionProvider;
import com.lightquery.exec.JdbcExecutor;
import com.lightquery.sqlgen.Dialect;
import com.lightquery.sqlgen.SqlFragment;

import java.util.List;

/**
 * Execution surface the query builders need. Implemented by
 * {@code LightQuery}; keeps the query package free of any dependency on the
 * entry-point class (and keeps the entry point thin).
 */
public interface QueryExecutor {

    ConnectionProvider connections();

    Dialect dialect();

    default <E> List<E> query(SqlFragment fragment, JdbcExecutor.RowMapper<E> mapper) {
        return JdbcExecutor.query(connections(), fragment, mapper);
    }

    default long count(SqlFragment fragment) {
        return JdbcExecutor.count(connections(), fragment);
    }

    default boolean exists(SqlFragment fragment) {
        return JdbcExecutor.exists(connections(), fragment);
    }

    default Object scalar(SqlFragment fragment) {
        return JdbcExecutor.scalar(connections(), fragment);
    }

    default int execute(SqlFragment fragment) {
        return JdbcExecutor.execute(connections(), fragment);
    }
}
