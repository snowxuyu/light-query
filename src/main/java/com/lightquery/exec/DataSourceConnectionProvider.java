package com.lightquery.exec;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Default provider: one pooled connection per operation. Thread-safe.
 */
public final class DataSourceConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;

    public DataSourceConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection get() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void release(Connection connection) {
        JdbcExecutor.closeQuietly(connection);
    }
}
