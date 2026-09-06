package com.lightquery.exec;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The single point where light-query obtains JDBC connections. Implement this
 * SPI to integrate with any transaction infrastructure — a Spring starter
 * (v0.2) plugs in here, binding to thread-bound transactions.
 *
 * <p>The default implementations are {@link DataSourceConnectionProvider}
 * (connection-per-operation) and {@link SingleConnectionProvider}
 * (one connection for a whole transaction).</p>
 */
public interface ConnectionProvider extends AutoCloseable {

    /** Obtains a connection for one operation. */
    Connection get() throws SQLException;

    /**
     * Returns the connection after the operation. Implementations backed by
     * a pool close the connection; transaction-scoped ones may ignore this.
     */
    void release(Connection connection);

    /**
     * True when {@link #get()} returns a connection bound to an externally
     * managed transaction (e.g. a Spring {@code TransactionSynchronizationManager}
     * transaction): a session's {@code inTransaction} then neither commits nor
     * rolls back — the external transaction manager owns the outcome.
     */
    default boolean inManagedTransaction() {
        return false;
    }

    /** Releases underlying resources; safe to call more than once. */
    @Override
    default void close() {
    }
}
