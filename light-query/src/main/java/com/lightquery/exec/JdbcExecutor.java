package com.lightquery.exec;

import com.lightquery.exception.DataAccessException;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.meta.EntityMeta;
import com.lightquery.sqlgen.SqlFragment;
import com.lightquery.Tuple;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin, allocation-lean JDBC runner: binds parameters, executes statements,
 * translates {@link SQLException}s into {@link DataAccessException} and maps
 * result rows. All methods are static; state lives with the caller.
 */
public final class JdbcExecutor {

    private JdbcExecutor() {
    }

    /** Maps one result-set row to a value. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public static <T> List<T> query(ConnectionProvider provider, SqlFragment fragment, RowMapper<T> mapper) {
        return withConnection(fragment, provider, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(fragment.sql())) {
                bind(ps, fragment.params());
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(mapper.map(rs));
                    }
                    return result;
                }
            } catch (SQLException e) {
                throw new DataAccessException(fragment.sql(), fragment.params(), e);
            }
        });
    }

    public static long count(ConnectionProvider provider, SqlFragment fragment) {
        List<Long> counts = query(provider, fragment, rs -> rs.getLong(1));
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    public static boolean exists(ConnectionProvider provider, SqlFragment fragment) {
        List<Boolean> found = query(provider, fragment, rs -> true);
        return !found.isEmpty();
    }

    /** First column of the first row, or null when the result is empty. */
    public static Object scalar(ConnectionProvider provider, SqlFragment fragment) {
        List<Object> values = query(provider, fragment, rs -> rs.getObject(1));
        return values.isEmpty() ? null : values.get(0);
    }

    /** Executes INSERT/UPDATE/DELETE, returning affected rows. */
    public static int execute(ConnectionProvider provider, SqlFragment fragment) {
        return withConnection(fragment, provider, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(fragment.sql())) {
                bind(ps, fragment.params());
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new DataAccessException(fragment.sql(), fragment.params(), e);
            }
        });
    }

    /**
     * Executes an INSERT and writes back a database-generated key
     * (identity column) into the entity.
     */
    public static void insert(ConnectionProvider provider, SqlFragment fragment,
                              Object entity, ColumnMeta generatedKey) {
        withConnection(fragment, provider, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(fragment.sql(),
                    Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, fragment.params());
                ps.executeUpdate();
                if (generatedKey != null) {
                    writeGeneratedKey(ps, entity, generatedKey);
                }
                return null;
            } catch (SQLException e) {
                throw new DataAccessException(fragment.sql(), fragment.params(), e);
            }
        });
    }

    /** Executes a JDBC batch INSERT for same-shaped parameter rows. */
    public static int[] insertBatch(ConnectionProvider provider, SqlFragment fragment,
                                    List<List<Object>> paramRows) {
        return withConnection(fragment, provider, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(fragment.sql())) {
                for (List<Object> row : paramRows) {
                    bind(ps, row);
                    ps.addBatch();
                }
                try {
                    return ps.executeBatch();
                } catch (SQLException e) {
                    throw new DataAccessException(fragment.sql(), paramRows.isEmpty()
                            ? List.of() : paramRows.get(0), e);
                }
            } catch (SQLException e) {
                throw new DataAccessException(fragment.sql(), List.of(), e);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private static void writeGeneratedKey(PreparedStatement ps, Object entity, ColumnMeta key)
            throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                key.writeValue(entity, key.fromDbValue(keys.getObject(1)));
            }
        }
    }

    private static <T> T withConnection(SqlFragment fragment, ConnectionProvider provider,
                                        SqlWork<T> work) {
        com.lightquery.SqlLogger logger = SqlLoggers.current();
        long start = logger != null ? System.nanoTime() : 0;
        if (logger != null) {
            logger.beforeExecute(fragment.sql(), fragment.params());
        }
        Connection connection = null;
        try {
            connection = provider.get();
            T result = work.run(connection);
            if (logger != null) {
                logger.afterExecute(fragment.sql(), (System.nanoTime() - start) / 1_000_000);
            }
            return result;
        } catch (Exception e) {
            if (logger != null) {
                logger.onError(fragment.sql(), fragment.params(), e);
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new DataAccessException("connection acquisition", List.of(), e);
        } finally {
            if (connection != null) {
                provider.release(connection);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    static void closeQuietly(AutoCloseable resource) {
        try {
            if (resource != null) {
                resource.close();
            }
        } catch (Exception ignored) {
            // closing must never mask the original failure
        }
    }

    /** Builds an entity row mapper over the cached {@link EntityMeta}. */
    public static <E> RowMapper<E> entityMapper(EntityMeta meta) {
        return rs -> {
            E entity = meta.newEntity();
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String label = md.getColumnLabel(i);
                ColumnMeta column = meta.byLabel(label);
                if (column != null) {
                    column.writeValue(entity, column.fromDbValue(rs.getObject(i)));
                }
            }
            return entity;
        };
    }

    /** Builds a row mapper for projected results (labels → values). */
    public static RowMapper<Tuple> tupleMapper() {
        return rs -> {
            ResultSetMetaData md = rs.getMetaData();
            int columnCount = md.getColumnCount();
            List<String> labels = new ArrayList<>(columnCount);
            List<Object> values = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                labels.add(md.getColumnLabel(i));
                values.add(rs.getObject(i));
            }
            return new Tuple(labels, values);
        };
    }
}
