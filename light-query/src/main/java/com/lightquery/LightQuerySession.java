package com.lightquery;

import com.lightquery.exec.ConnectionProvider;
import com.lightquery.exec.DataSourceConnectionProvider;
import com.lightquery.exec.EntityOperations;
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
 * A light-query entry bound to exactly one data source (or, inside
 * {@link #inTransaction}, to one transaction connection). Immutable and
 * thread-safe — hold one per data source and share it, e.g. as a DI-managed
 * bean when you prefer instances over the static {@link LightQuery} facade:
 *
 * <pre>{@code
 * LightQuerySession db = LightQuery.of(dataSource);   // dialect detected from the JDBC URL
 *
 * List<User> users = db.queryable(User.class)
 *     .col(User::getStatus).eq(Status.ACTIVE)
 *     .orderByDesc(User::getCreateTime)
 *     .toList();
 * }</pre>
 *
 * <p>All CRUD shortcuts and {@link Queryable} share the connection pool
 * behind the bound data source; {@link #inTransaction} hands out a
 * {@code LightQuerySession} bound to one connection with commit/rollback
 * semantics. The static {@link LightQuery} facade delegates every call to a
 * globally registered session.</p>
 */
public final class LightQuerySession implements QueryExecutor {

    private final ConnectionProvider connections;
    private final Dialect dialect;

    /** Creates a session over the data source; the dialect is auto-detected. */
    public LightQuerySession(DataSource dataSource) {
        this(new DataSourceConnectionProvider(dataSource), detectDialect(dataSource));
    }

    /** Creates a session over the data source with an explicit dialect. */
    public LightQuerySession(DataSource dataSource, Dialect dialect) {
        this(new DataSourceConnectionProvider(dataSource), dialect);
    }

    /**
     * Creates a session over a custom connection source — the integration
     * seam for containers (e.g. a Spring-managed provider) that bring their
     * own connection handling. The dialect must be supplied explicitly; use
     * {@link #detectDialect(DataSource)} to derive it from a data source.
     */
    public LightQuerySession(ConnectionProvider connections, Dialect dialect) {
        this.connections = connections;
        this.dialect = dialect;
    }

    // ------------------------------------------------------------------ builders

    public <T> Queryable<T> queryable(Class<T> entityClass) {
        return new Queryable<>(this, entityClass);
    }

    /**
     * Query rooted at an explicitly named occurrence — the self-join entry
     * point (see {@link QueryTable}).
     */
    public <T> Queryable<T> queryable(QueryTable<T> rootTable) {
        return new Queryable<>(this, rootTable);
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

    /**
     * Deletes by a single primary key value (the common case); composite keys
     * use {@link #deleteById(Class, Object...)}.
     */
    public <P> int deleteById(Class<?> entityType, P pkValue) {
        return deleteById(entityType, new Object[]{pkValue});
    }

    /** Selects one entity by primary key values; null when absent. */
    public <T> T queryById(Class<T> entityType, Object... pkValues) {
        return EntityOperations.queryById(connections, dialect, entityType, pkValues);
    }

    /**
     * Selects one entity by a single primary key value (the common case);
     * composite keys use {@link #queryById(Class, Object...)}.
     */
    public <T, P> T queryById(Class<T> entityType, P pkValue) {
        return queryById(entityType, new Object[]{pkValue});
    }

    /** Counts all rows of the entity's table (logic-delete filter applies). */
    public long count(Class<?> entityType) {
        return EntityOperations.count(connections, dialect, entityType);
    }

    // ------------------------------------------------------------------ transactions

    /**
     * Runs {@code work} inside one transaction: commit on normal return,
     * rollback + rethrow on any exception. The session handed to {@code work}
     * is bound to the transaction connection — use it for every statement
     * that must participate.
     *
     * <p>Nested calls reuse the outer connection (no savepoint semantics).
     * When the underlying {@link ConnectionProvider} reports an externally
     * managed transaction ({@link ConnectionProvider#inManagedTransaction()},
     * e.g. a Spring transaction), statements join that transaction and neither
     * commit nor rollback happen here — the external manager owns the outcome.</p>
     */
    public <R> R inTransaction(Function<LightQuerySession, R> work) {
        if (connections instanceof SingleConnectionProvider || connections.inManagedTransaction()) {
            // already inside a transaction: reuse it, no nested commit/rollback
            return work.apply(this);
        }
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            SingleConnectionProvider txProvider = new SingleConnectionProvider(connection);
            try {
                R result = work.apply(new LightQuerySession(txProvider, dialect));
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

    // ------------------------------------------------------------------ configuration

    /**
     * Returns a copy using another dialect; the shared connection pool of
     * this session is reused.
     */
    public LightQuerySession dialect(Dialect newDialect) {
        return new LightQuerySession(connections, newDialect);
    }

    /** Detects the dialect from the data source's JDBC URL. */
    public static Dialect detectDialect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            return Dialects.fromJdbcUrl(url);
        } catch (SQLException e) {
            throw new DataAccessException("dialect detection", List.of(), e);
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
