package com.lightquery;

import com.lightquery.exec.ConnectionProvider;
import com.lightquery.exec.FillListeners;
import com.lightquery.exception.LightQueryException;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.query.Deletable;
import com.lightquery.query.Queryable;
import com.lightquery.query.Updatable;
import com.lightquery.sqlgen.Dialect;

import javax.sql.DataSource;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Static entry point of light-query. The primary data source is the default
 * target of every call; with multiple data sources, each call site names the
 * data source explicitly. No instance management is required:
 *
 * <pre>{@code
 * // startup: register the primary data source (the default target)
 * LightQuery.primary(dataSource);
 *
 * // single data source: use LightQuery directly — nothing to specify
 * List<User> users = LightQuery.queryable(User.class)
 *     .eq(User::getStatus, Status.ACTIVE)
 *     .orderByDesc(User::getCreateTime)
 *     .toList();
 *
 * // multiple data sources: name the datasource at the call site
 * LightQuery.datasource("order", dsOrder)      // registers "order" once, then resolves by name
 *     .queryable(Order.class)
 *     .toList();
 * LightQuery.datasource("order").queryable(Order.class).toList();
 * }</pre>
 *
 * <p>Every method delegates to an immutable, thread-safe
 * {@link LightQuerySession}: the one registered as primary, the named one, or
 * the transaction-scoped one handed to {@code inTransaction} work. Frameworks
 * that prefer DI can bypass this facade entirely and hold
 * {@code LightQuerySession} instances (see {@link #of(DataSource)}).</p>
 *
 * <p>There is no automatic routing (read/write splitting, sharding): naming a
 * data source is always explicit.</p>
 */
public final class LightQuery {

    private static volatile LightQuerySession primarySession;
    private static volatile DataSource primaryDataSource;
    private static final Map<String, RegisteredSource> NAMED = new ConcurrentHashMap<>();

    /** A named datasource registration: its DataSource plus the bound session. */
    private record RegisteredSource(DataSource dataSource, LightQuerySession session) {
    }

    private LightQuery() {
    }

    // ------------------------------------------------------------------ bootstrap

    /**
     * Registers the primary data source: the default target of every static
     * call that does not name a datasource. The dialect is detected from the
     * JDBC URL. Registering the same {@link DataSource} again is idempotent;
     * registering a different one throws.
     *
     * @return the session bound to the primary data source
     * @throws IllegalStateException when a different primary is already registered
     */
    public static LightQuerySession primary(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return registerPrimary(dataSource, () -> new LightQuerySession(dataSource));
    }

    /**
     * Registers the primary data source with an explicit dialect.
     *
     * @return the session bound to the primary data source
     * @throws IllegalStateException when a different primary is already registered
     */
    public static LightQuerySession primary(DataSource dataSource, Dialect dialect) {
        Objects.requireNonNull(dataSource, "dataSource");
        return registerPrimary(dataSource, () -> new LightQuerySession(dataSource, dialect));
    }

    /**
     * Registers the primary data source with a custom connection source —
     * the integration seam for containers (e.g. the Spring starter's
     * transaction-aware provider). The dialect is detected from the JDBC URL.
     *
     * @return the session bound to the primary data source
     * @throws IllegalStateException when a different primary is already registered
     */
    public static LightQuerySession primary(DataSource dataSource, ConnectionProvider connections) {
        Objects.requireNonNull(dataSource, "dataSource");
        return registerPrimary(dataSource,
                () -> new LightQuerySession(connections, LightQuerySession.detectDialect(dataSource)));
    }

    private static LightQuerySession registerPrimary(DataSource dataSource,
                                                     Supplier<LightQuerySession> sessionFactory) {
        synchronized (LightQuery.class) {
            if (primarySession != null) {
                return requireSameDataSource(primarySession, primaryDataSource, dataSource, "primary");
            }
            LightQuerySession session = sessionFactory.get();
            primaryDataSource = dataSource;
            primarySession = session;
            return session;
        }
    }

    /**
     * Registers a named data source and returns the entry bound to it — the
     * switch point for multi-datasource applications. The dialect is detected
     * from the JDBC URL on first registration; later calls with the same name
     * and {@link DataSource} resolve to the same cached session, so this form
     * is safe to use directly at every call site.
     *
     * @param name datasource name, e.g. {@code "order"}
     * @return the session bound to that data source
     * @throws IllegalArgumentException when {@code name} is blank
     * @throws IllegalStateException    when the name is already registered with
     *                                  a different DataSource
     */
    public static LightQuerySession datasource(String name, DataSource dataSource) {
        return registerNamed(name, dataSource, () -> new LightQuerySession(dataSource));
    }

    /**
     * Registers a named data source with an explicit dialect; otherwise the
     * same get-or-register semantics as
     * {@link #datasource(String, DataSource)}.
     *
     * @return the session bound to that data source
     * @throws IllegalArgumentException when {@code name} is blank
     * @throws IllegalStateException    when the name is already registered with
     *                                  a different DataSource
     */
    public static LightQuerySession datasource(String name, DataSource dataSource, Dialect dialect) {
        return registerNamed(name, dataSource,
                () -> new LightQuerySession(dataSource, Objects.requireNonNull(dialect, "dialect")));
    }

    /**
     * Registers a named data source with a custom connection source (e.g. a
     * container-managed provider); otherwise the same get-or-register
     * semantics as {@link #datasource(String, DataSource)}.
     *
     * @return the session bound to that data source
     * @throws IllegalArgumentException when {@code name} is blank
     * @throws IllegalStateException    when the name is already registered with
     *                                  a different DataSource
     */
    public static LightQuerySession datasource(String name, DataSource dataSource,
                                               ConnectionProvider connections) {
        return registerNamed(name, dataSource,
                () -> new LightQuerySession(connections, LightQuerySession.detectDialect(dataSource)));
    }

    private static LightQuerySession registerNamed(String name, DataSource dataSource,
                                                   Supplier<LightQuerySession> sessionFactory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dataSource, "dataSource");
        validateName(name);
        synchronized (LightQuery.class) {
            RegisteredSource existing = NAMED.get(name);
            if (existing != null) {
                return requireSameDataSource(existing.session(), existing.dataSource(), dataSource, name);
            }
            LightQuerySession session = sessionFactory.get();
            NAMED.put(name, new RegisteredSource(dataSource, session));
            return session;
        }
    }

    /**
     * Returns the entry bound to a named data source registered via
     * {@link #datasource(String, DataSource)}.
     *
     * @throws SqlBuildException when the name is not registered; the message
     *                           lists all available names
     */
    public static LightQuerySession datasource(String name) {
        Objects.requireNonNull(name, "name");
        RegisteredSource source = NAMED.get(name);
        if (source == null) {
            throw new SqlBuildException("Unknown datasource '" + name + "'. Available: "
                    + (NAMED.isEmpty() ? "<none>" : NAMED.keySet())
                    + ". Register it via LightQuery.datasource(name, dataSource); the primary "
                    + "datasource is the default target and needs no name.");
        }
        return source.session();
    }

    /**
     * Creates a standalone session without registering it — the seam for
     * DI frameworks that prefer explicit instances over static state.
     * The dialect is detected from the JDBC URL.
     */
    public static LightQuerySession of(DataSource dataSource) {
        return new LightQuerySession(dataSource);
    }

    /** Creates a standalone session with an explicit dialect, without registering it. */
    public static LightQuerySession of(DataSource dataSource, Dialect dialect) {
        return new LightQuerySession(dataSource, dialect);
    }

    /**
     * Clears every registration (primary and named) and the fill listener.
     * Escape hatch for tests and container restarts — production code should
     * register once and never call this.
     */
    public static void reset() {
        synchronized (LightQuery.class) {
            primarySession = null;
            primaryDataSource = null;
            NAMED.clear();
            FillListeners.clear();
        }
    }

    /**
     * Registers the global {@link FillListener} for entity field auto-filling
     * (insert / insertBatch / update / updateSelective). Replaces any
     * previously registered listener; {@code null} clears it.
     */
    public static void setFillListener(FillListener listener) {
        FillListeners.set(listener);
    }

    /** Removes the global {@link FillListener}. */
    public static void clearFillListener() {
        FillListeners.clear();
    }

    private static LightQuerySession requireSameDataSource(LightQuerySession session,
                                                           DataSource registered, DataSource requested,
                                                           String name) {
        if (registered == requested) {
            return session;
        }
        throw new IllegalStateException("Datasource '" + name + "' is already registered with a "
                + "different DataSource. Call LightQuery.reset() first to replace it.");
    }

    private static void validateName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("datasource name must not be blank");
        }
    }

    private static LightQuerySession primary() {
        LightQuerySession session = primarySession;
        if (session == null) {
            throw new LightQueryException("No primary datasource registered. Call "
                    + "LightQuery.primary(dataSource) once at startup, or obtain an explicit session "
                    + "via LightQuery.datasource(name) / LightQuery.of(dataSource).");
        }
        return session;
    }

    // ------------------------------------------------------------------ primary-datasource shortcuts

    /** Same as {@link LightQuerySession#queryable(Class)} on the primary datasource. */
    public static <T> Queryable<T> queryable(Class<T> entityClass) {
        return primary().queryable(entityClass);
    }

    /**
     * Same as {@link LightQuerySession#queryable(QueryTable)} on the primary
     * datasource — the self-join entry point.
     */
    public static <T> Queryable<T> queryable(QueryTable<T> rootTable) {
        return primary().queryable(rootTable);
    }

    /** Same as {@link LightQuerySession#updatable(Class)} on the primary datasource. */
    public static <T> Updatable<T> updatable(Class<T> entityClass) {
        return primary().updatable(entityClass);
    }

    /** Same as {@link LightQuerySession#deletable(Class)} on the primary datasource. */
    public static <T> Deletable<T> deletable(Class<T> entityClass) {
        return primary().deletable(entityClass);
    }

    /** Same as {@link LightQuerySession#insert(Object)} on the primary datasource. */
    public static <T> T insert(T entity) {
        return primary().insert(entity);
    }

    /** Same as {@link LightQuerySession#insertBatch(Collection)} on the primary datasource. */
    public static <T> List<T> insertBatch(Collection<T> entities) {
        return primary().insertBatch(entities);
    }

    /** Same as {@link LightQuerySession#update(Object)} on the primary datasource. */
    public static <T> void update(T entity) {
        primary().update(entity);
    }

    /** Same as {@link LightQuerySession#updateSelective(Object)} on the primary datasource. */
    public static <T> void updateSelective(T entity) {
        primary().updateSelective(entity);
    }

    /** Same as {@link LightQuerySession#delete(Object)} on the primary datasource. */
    public static <T> void delete(T entity) {
        primary().delete(entity);
    }

    /** Same as {@link LightQuerySession#deleteById(Class, Object...)} on the primary datasource. */
    public static int deleteById(Class<?> entityType, Object... pkValues) {
        return primary().deleteById(entityType, pkValues);
    }

    /** Same as {@link LightQuerySession#queryById(Class, Object...)} on the primary datasource. */
    public static <T> T queryById(Class<T> entityType, Object... pkValues) {
        return primary().queryById(entityType, pkValues);
    }

    /** Same as {@link LightQuerySession#count(Class)} on the primary datasource. */
    public static long count(Class<?> entityType) {
        return primary().count(entityType);
    }

    /**
     * Runs {@code work} in one transaction on the primary datasource; the
     * session handed to {@code work} is bound to the transaction connection.
     *
     * @throws LightQueryException when no primary datasource is registered
     */
    public static <R> R inTransaction(Function<LightQuerySession, R> work) {
        return primary().inTransaction(work);
    }
}
