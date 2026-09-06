package com.lightquery.query.model;

import com.lightquery.meta.EntityMeta;
import com.lightquery.meta.EntityMetaCache;

/**
 * One table participating in a query: the entity, its metadata and the
 * physical table name (mutable to support {@code asTable} for sharding).
 */
public final class TableRef {

    private final Class<?> entityClass;
    private final EntityMeta meta;
    private String tableName;

    TableRef(Class<?> entityClass) {
        this.entityClass = entityClass;
        this.meta = EntityMetaCache.of(entityClass);
        this.tableName = meta.getTableName();
    }

    public static TableRef of(Class<?> entityClass) {
        return new TableRef(entityClass);
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    public EntityMeta getMeta() {
        return meta;
    }

    public String getTableName() {
        return tableName;
    }

    /** Renames the physical table for this query only (dynamic table name). */
    public TableRef rename(String physicalName) {
        this.tableName = physicalName;
        return this;
    }
}
