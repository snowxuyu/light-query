package com.lightquery;

import com.lightquery.exec.ConnectionProvider;
import com.lightquery.exec.DataSourceConnectionProvider;
import com.lightquery.exec.EntityOperations;
import com.lightquery.exec.JdbcExecutor;
import com.lightquery.exec.SingleConnectionProvider;
import com.lightquery.exception.DataAccessException;
import com.lightquery.exception.LightQueryException;
import com.lightquery.query.Deletable;
import com.lightquery.query.QueryExecutor;
import com.lightquery.query.Queryable;
import com.lightquery.query.Updatable;
import com.lightquery.sqlgen.Dialect;
import com.lightquery.sqlgen.Dialects;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Entry point of light-query. Create one instance per data source at startup
 * and share it: it is immutable and thread-safe.
 *
 * <pre>{@code
 * LightQuery db = LightQuery.of(dataSource);   // dialect detected from the JDBC URL
 *
 * List<User> users = db.queryable(User.class)
 *     .eq(User::getStatus, Status.ACTIVE)
 *     .orderByDesc(User::getCreateTime)
 *     .toList();
 * }</pre>
 *
 * <p>All CRUD shortcuts and {@link Queryable} share the connection pool
 * behind the given {@link DataSource}; {@link #inTransaction} hands out a
 * {@code LightQuery} bound to one connection with commit/rollback semantics.</p>
 */
public final class LightQuery implements QueryExecutor {

    private final ConnectionProvider connections;
    private final Dialect dialect;

    private LightQuery(ConnectionProvider connections, Dialect dialect) {
        this.connections = connections;
        this.dialect = dialect;
    }

    // ------------------------------------------------------------------ bootstrap

    /** Detects the dialect from the data source's JDBC URL. */
    public static LightQuery of(DataSource dataSource) {
        return of(dataSource, detectDialect(dataSource));
    }

    /** Creates an instance with an explicit dialect (multi-URL drivers etc.). */
    public static LightQuery of(DataSource dataSource, Dialect dialect) {
        return new LightQuery(new DataSourceConnectionProvider(dataSource), dialect);
    }

    /**
     * Returns a copy using another dialect; the shared connection pool of
     * this instance is reused.
     */
    public LightQuery dialect(Dialect newDialect) {
        return new LightQuery(connections, newDialect);
    }

    private static Dialect detectDialect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            return Dialects.fromJdbcUrl(url);
        } catch (SQLException e) {
            throw new DataAccessException("dialect detection", List.of(), e);
        }
    }

    // ------------------------------------------------------------------ builders

    public <T> Queryable<T> queryable(Class<T> entityClass) {
        return new Queryable<>(this, entityClass);
    }

    public <T> Updatable<T> updatable(Class<T> entityClass) {
        return new Updatable<>(this, entityClass);
    }

    public <T> Deletable<T> deletable(Class<T> entityClass) {
        return new Deletable<>(this, entityClass);
    }

    // ------------------------------------------------------------------ entity CRUD

    /** Inserts one entity; an identity primary key is written back to it. */
    public <T> T insert(T entity) {
        return EntityOperations.insert(connections, dialect, entity);
    }

    /** Inserts a collection as one JDBC batch; identity keys are not back-filled. */
    public <T> List<T> insertBatch(Collection<T> entities) {
        return EntityOperations.insertBatch(connections, dialect, entities);
    }

    /** Full update by primary key — null columns are written as NULL. */
    public <T> void update(T entity) {
        EntityOperations.update(connections, dialect, entity);
    }

    /** Update by primary key, writing only non-null properties. */
    public <T> void updateSelective(T entity) {
        EntityOperations.updateSelective(connections, dialect, entity);
    }

    /**
     * Deletes by primary key. With {@code @LogicDelete} this becomes an
     * UPDATE setting the deleted marker.
     *
     * @throws com.lightquery.exception.UnexpectedRowsException when the row
     *         does not exist or was changed concurrently (affected ≠ 1)
     */
    public <T> void delete(T entity) {
        EntityOperations.delete(connections, dialect, entity);
    }

    /**
     * Deletes by primary key values. With {@code @LogicDelete} this becomes
     * an UPDATE setting the deleted marker. Idempotent: returns 0 instead of
     * throwing when the row does not exist.
     */
    public int deleteById(Class<?> entityType, Object... pkValues) {
        return EntityOperations.deleteById(connections, dialect, entityType, pkValues);
    }

    /** Selects one entity by primary key values; null when absent. */
    public <T> T queryById(Class<T> entityType, Object... pkValues) {
        return EntityOperations.queryById(connections, dialect, entityType, pkValues);
    }

    /** Counts all rows of the entity's table (logic-delete filter applies). */
    public long count(Class<?> entityType) {
        return EntityOperations.count(connections, dialect, entityType);
    }

    // ------------------------------------------------------------------ transactions

    /**
     * Runs {@code work} inside one transaction: commit on normal return,
     * rollback + rethrow on any exception. The light-query instance handed
     * to {@code work} is bound to the transaction connection — use it for
     * every statement that must participate.
     *
     * <p>Nested calls reuse the outer connection (no savepoint semantics).</p>
     */
    public <R> R inTransaction(Function<LightQuery, R> work) {
        if (connections instanceof SingleConnectionProvider) {
            // already inside a transaction: reuse it, no nested commit/rollback
            return work.apply(this);
        }
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            SingleConnectionProvider txProvider = new SingleConnectionProvider(connection);
            try {
                R result = work.apply(new LightQuery(txProvider, dialect));
                connection.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw new LightQueryException("Transaction failed and was rolled back", e);
            }
        } catch (SQLException e) {
            throw new DataAccessException("transaction connection management", List.of(), e);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // never mask the original failure
        }
    }

    // ------------------------------------------------------------------ QueryExecutor

    @Override
    public ConnectionProvider connections() {
        return connections;
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }
}
