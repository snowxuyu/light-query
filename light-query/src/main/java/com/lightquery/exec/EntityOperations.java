package com.lightquery.exec;

import com.lightquery.FillListener;
import com.lightquery.exception.MappingException;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.exception.UnexpectedRowsException;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.meta.EntityMeta;
import com.lightquery.meta.EntityMetaCache;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.Operator;
import com.lightquery.query.model.QueryModel;
import com.lightquery.sqlgen.Dialect;
import com.lightquery.sqlgen.SqlBuilder;
import com.lightquery.sqlgen.SqlFragment;

import jakarta.persistence.OptimisticLockException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Single-table entity CRUD (insert / update by id / delete by id). Builds
 * SQL through {@link SqlBuilder} and enforces the row-count contracts of the
 * DESIGN (affected rows ≠ 1 → {@link UnexpectedRowsException}). Applies the
 * registered {@link FillListener}, SEQUENCE key generation and
 * {@code @Version} optimistic locking for entity-level operations.
 */
public final class EntityOperations {

    private EntityOperations() {
    }

    /** Inserts one entity and back-fills a generated primary key if present. */
    public static <E> E insert(ConnectionProvider connections, Dialect dialect, E entity) {
        EntityMeta meta = EntityMetaCache.of(entity.getClass());
        FillListener listener = FillListeners.current();
        if (listener != null) {
            listener.onInsert(entity);
        }
        fetchSequenceKey(connections, dialect, meta, entity);
        initInsertVersion(meta, entity);
        List<ColumnMeta> columns = meta.getInsertableColumns();
        List<String> names = columns.stream().map(ColumnMeta::getColumnName).toList();
        List<Object> values = columns.stream()
                .map(c -> c.toDbValue(c.readValue(entity)))
                .toList();
        SqlFragment fragment = SqlBuilder.insert(meta.getTableName(), names, values, dialect);
        JdbcExecutor.insert(connections, fragment, entity, identityKey(meta));
        return entity;
    }

    /** Inserts many entities as one JDBC batch; identity keys are not back-filled. */
    public static <E> List<E> insertBatch(ConnectionProvider connections, Dialect dialect,
                                          Collection<E> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        EntityMeta meta = EntityMetaCache.of(entities.iterator().next().getClass());
        FillListener listener = FillListeners.current();
        ColumnMeta sequencePk = meta.getSequencePrimaryKey();
        for (E entity : entities) {
            if (listener != null) {
                listener.onInsert(entity);
            }
            if (sequencePk != null) {
                fetchSequenceKey(connections, dialect, meta, entity);
            }
            initInsertVersion(meta, entity);
        }
        List<ColumnMeta> columns = meta.getInsertableColumns();
        List<String> names = columns.stream().map(ColumnMeta::getColumnName).toList();
        List<List<Object>> rows = new ArrayList<>(entities.size());
        for (E entity : entities) {
            rows.add(columns.stream()
                    .map(c -> c.toDbValue(c.readValue(entity)))
                    .toList());
        }
        SqlFragment fragment = SqlBuilder.insert(meta.getTableName(), names, List.of(), dialect);
        JdbcExecutor.insertBatch(connections, fragment, rows);
        return List.copyOf(entities);
    }

    /** Full update by primary key (null columns are written as NULL). */
    public static <E> void update(ConnectionProvider connections, Dialect dialect, E entity) {
        updateEntity(connections, dialect, entity, false);
    }

    /** Update by primary key, writing only non-null properties. */
    public static <E> void updateSelective(ConnectionProvider connections, Dialect dialect, E entity) {
        updateEntity(connections, dialect, entity, true);
    }

    /** Deletes by primary key; becomes a logic-delete UPDATE when mapped. */
    public static <E> void delete(ConnectionProvider connections, Dialect dialect, E entity) {
        EntityMeta meta = EntityMetaCache.of(entity.getClass());
        ColumnMeta version = meta.getVersionColumn();
        Object currentVersion = requireCurrentVersion(meta, version, entity);
        QueryModel model = new QueryModel(entity.getClass());
        addPkConditions(model, meta, pkValues(meta, entity));
        addVersionCondition(model, meta, version, currentVersion);
        int rows = executeDelete(connections, dialect, model, meta, version, currentVersion);
        requireOneRow(rows, "delete", describePk(meta, pkValues(meta, entity)), version, currentVersion);
        if (version != null) {
            version.writeValue(entity, ((Number) currentVersion).longValue() + 1);
        }
    }

    /**
     * Deletes by primary key values; becomes a logic-delete UPDATE when mapped.
     *
     * @return affected rows (0 when the row does not exist — idempotent)
     */
    public static int deleteById(ConnectionProvider connections, Dialect dialect,
                                 Class<?> entityType, Object... pkValues) {
        EntityMeta meta = EntityMetaCache.of(entityType);
        QueryModel model = new QueryModel(entityType);
        addPkConditions(model, meta, validatedPkValues(meta, pkValues));
        return executeDelete(connections, dialect, model, meta, null, null);
    }

    /** Selects one entity by primary key values (logic-delete filter applies). */
    public static <E> E queryById(ConnectionProvider connections, Dialect dialect,
                                  Class<E> entityType, Object... pkValues) {
        EntityMeta meta = EntityMetaCache.of(entityType);
        QueryModel model = new QueryModel(entityType);
        addPkConditions(model, meta, validatedPkValues(meta, pkValues));
        addLogicFilter(model, meta);
        SqlFragment fragment = SqlBuilder.select(model, dialect);
        List<E> rows = JdbcExecutor.query(connections, fragment, JdbcExecutor.entityMapper(meta));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Counts all rows of a table (logic-delete filter applies). */
    public static long count(ConnectionProvider connections, Dialect dialect, Class<?> entityType) {
        EntityMeta meta = EntityMetaCache.of(entityType);
        QueryModel model = new QueryModel(entityType);
        addLogicFilter(model, meta);
        return JdbcExecutor.count(connections, SqlBuilder.count(model, dialect));
    }

    // ------------------------------------------------------------------ internals

    private static void updateEntity(ConnectionProvider connections, Dialect dialect,
                                     Object entity, boolean selective) {
        EntityMeta meta = EntityMetaCache.of(entity.getClass());
        FillListener listener = FillListeners.current();
        if (listener != null) {
            listener.onUpdate(entity);
        }
        List<Object> pks = pkValues(meta, entity);
        ColumnMeta version = meta.getVersionColumn();
        Object currentVersion = requireCurrentVersion(meta, version, entity);
        List<ColumnMeta> setColumns = meta.getUpdatableColumns().stream()
                .filter(c -> c != version)
                .filter(c -> !selective || c.readValue(entity) != null)
                .toList();
        if (setColumns.isEmpty()) {
            throw new MappingException("Nothing to update for " + meta.getEntityClass().getName()
                    + (selective ? " — every updatable property is null" : ""));
        }
        QueryModel model = new QueryModel(entity.getClass());
        addPkConditions(model, meta, pks);
        // protect logically deleted rows from being modified
        addLogicFilter(model, meta);
        addVersionCondition(model, meta, version, currentVersion);

        List<SqlBuilder.SetClause> sets = new ArrayList<>();
        for (ColumnMeta column : setColumns) {
            sets.add(SqlBuilder.SetClause.of(
                    column.getColumnName(), column.toDbValue(column.readValue(entity))));
        }
        if (version != null) {
            sets.add(SqlBuilder.SetClause.increment(version.getColumnName(), 1));
        }
        int rows = JdbcExecutor.execute(connections, SqlBuilder.update(model, sets, dialect));
        requireOneRow(rows, "update", describePk(meta, pks), version, currentVersion);
        if (version != null) {
            version.writeValue(entity, ((Number) currentVersion).longValue() + 1);
        }
    }

    private static int executeDelete(ConnectionProvider connections, Dialect dialect,
                                     QueryModel model, EntityMeta meta,
                                     ColumnMeta version, Object currentVersion) {
        ColumnMeta logic = meta.getLogicDeleteColumn();
        if (logic != null) {
            // already-deleted rows stay deleted: no logic filter on this UPDATE
            List<SqlBuilder.SetClause> sets = new ArrayList<>();
            sets.add(SqlBuilder.SetClause.of(logic.getColumnName(), logic.deletedValueAsDb()));
            if (version != null) {
                sets.add(SqlBuilder.SetClause.increment(version.getColumnName(), 1));
            }
            return JdbcExecutor.execute(connections, SqlBuilder.update(model, sets, dialect));
        }
        return JdbcExecutor.execute(connections, SqlBuilder.delete(model, dialect));
    }

    /** Fetches the next sequence value and writes it into the entity's key. */
    private static void fetchSequenceKey(ConnectionProvider connections, Dialect dialect,
                                         EntityMeta meta, Object entity) {
        ColumnMeta sequencePk = meta.getSequencePrimaryKey();
        if (sequencePk == null) {
            return;
        }
        if (!dialect.supportsSequences()) {
            throw new SqlBuildException(meta.getEntityClass().getName()
                    + " uses a SEQUENCE primary key but the "
                    + dialect.getClass().getSimpleName() + " dialect does not support sequences");
        }
        SqlFragment nextval = new SqlFragment(
                dialect.sequenceNextValueSql(sequencePk.getSequenceName()), List.of());
        sequencePk.writeValue(entity, JdbcExecutor.scalar(connections, nextval));
    }

    /** Initialises a null version to 0 so the row is updateable afterwards. */
    private static void initInsertVersion(EntityMeta meta, Object entity) {
        ColumnMeta version = meta.getVersionColumn();
        if (version != null && version.readValue(entity) == null) {
            version.writeValue(entity, 0);
        }
    }

    private static Object requireCurrentVersion(EntityMeta meta, ColumnMeta version, Object entity) {
        if (version == null) {
            return null;
        }
        Object current = version.readValue(entity);
        if (current == null) {
            throw new MappingException(meta.getEntityClass().getName()
                    + "." + version.getPropertyName()
                    + " is null — load the entity (queryById) before updating or deleting it");
        }
        return current;
    }

    private static void addVersionCondition(QueryModel model, EntityMeta meta,
                                            ColumnMeta version, Object currentVersion) {
        if (version != null) {
            model.getWhere().add(Condition.of(
                    new ColumnRef(meta.getEntityClass(), version.getColumnName()),
                    Operator.EQ, List.of(version.toDbValue(currentVersion))));
        }
    }

    private static void addPkConditions(QueryModel model, EntityMeta meta, List<Object> pkValues) {
        List<ColumnMeta> pks = meta.getPrimaryKeys();
        for (int i = 0; i < pks.size(); i++) {
            model.getWhere().add(Condition.of(
                    new ColumnRef(meta.getEntityClass(), pks.get(i).getColumnName()),
                    Operator.EQ, List.of(pkValues.get(i))));
        }
    }

    private static void addLogicFilter(QueryModel model, EntityMeta meta) {
        ColumnMeta logic = meta.getLogicDeleteColumn();
        if (logic != null) {
            model.getWhere().add(Condition.of(
                    new ColumnRef(meta.getEntityClass(), logic.getColumnName()),
                    Operator.EQ, List.of(logic.normalValueAsDb())));
        }
    }

    private static List<Object> pkValues(EntityMeta meta, Object entity) {
        return meta.getPrimaryKeys().stream()
                .map(pk -> pk.readValue(entity))
                .toList();
    }

    private static List<Object> validatedPkValues(EntityMeta meta, Object... pkValues) {
        int expected = meta.getPrimaryKeys().size();
        int actual = pkValues == null ? 0 : pkValues.length;
        if (actual != expected) {
            throw new MappingException(meta.getEntityClass().getSimpleName()
                    + " has " + expected + " primary key column(s) but " + actual
                    + " value(s) were passed");
        }
        return List.of(pkValues);
    }

    private static ColumnMeta identityKey(EntityMeta meta) {
        return meta.getPrimaryKeys().stream()
                .filter(ColumnMeta::isIdentity)
                .findFirst()
                .orElse(null);
    }

    private static void requireOneRow(int affected, String operation, String pkDescription,
                                      ColumnMeta version, Object expectedVersion) {
        if (affected == 1) {
            return;
        }
        if (version != null) {
            throw new OptimisticLockException(operation + " on " + pkDescription
                    + " with expected version " + expectedVersion + " affected " + affected
                    + " rows — the row was modified or deleted concurrently");
        }
        throw new UnexpectedRowsException(operation + " by id " + pkDescription
                + " affected " + affected + " rows (expected 1) — the row may have been "
                + "deleted concurrently or the primary key may be stale", affected);
    }

    private static String describePk(EntityMeta meta, List<Object> values) {
        List<String> names = meta.getPrimaryKeys().stream()
                .map(ColumnMeta::getColumnName).toList();
        return names + "=" + values;
    }
}
