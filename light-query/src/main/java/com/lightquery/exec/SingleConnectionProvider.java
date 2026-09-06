package com.lightquery.exec;

import java.sql.Connection;

/**
 * Transaction-scoped provider: hands out the same connection to every
 * operation, so a {@code LightQuerySession} created over it runs inside one
 * transaction. Not thread-safe — never share the transaction instance
 * across threads.
 */
public final class SingleConnectionProvider implements ConnectionProvider {

    private final Connection connection;

    public SingleConnectionProvider(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Connection get() {
        return connection;
    }

    @Override
    public void release(Connection connection) {
        // the owner (inTransaction) manages the connection lifecycle
    }

    @Override
    public void close() {
        JdbcExecutor.closeQuietly(connection);
    }
}
