package com.lightquery.spring;

import com.lightquery.exec.ConnectionProvider;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

import java.sql.Connection;

/**
 * ConnectionProvider that participates in Spring-managed transactions: while
 * a Spring transaction is active for the data source, every statement reuses
 * the transaction-bound connection (via {@link DataSourceUtils}) and neither
 * commit nor rollback happen inside light-query — the Spring transaction
 * manager owns the outcome. Without a transaction, connections are taken
 * from the pool per operation as usual.
 */
public final class SpringConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;

    public SpringConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection get() {
        return DataSourceUtils.getConnection(dataSource);
    }

    @Override
    public void release(Connection connection) {
        DataSourceUtils.releaseConnection(connection, dataSource);
    }

    @Override
    public boolean inManagedTransaction() {
        return TransactionSynchronizationManager.hasResource(dataSource);
    }
}
