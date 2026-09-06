package com.lightquery.query.model;

import com.lightquery.meta.EntityMeta;
import com.lightquery.meta.EntityMetaCache;

/**
 * One table participating in a query: the entity, its metadata, the physical
 * table name (mutable to support {@code asTable} for sharding) and an optional
 * explicit alias (self-join occurrences via {@code QueryTable}).
 */
public final class TableRef {

    private final Class<?> entityClass;
    private final EntityMeta meta;
    private final String explicitAlias;
    private String tableName;

    private TableRef(Class<?> entityClass, String explicitAlias) {
        this.entityClass = entityClass;
        this.meta = EntityMetaCache.of(entityClass);
        this.tableName = meta.getTableName();
        this.explicitAlias = explicitAlias;
    }

    public static TableRef of(Class<?> entityClass) {
        return new TableRef(entityClass, null);
    }

    /** Creates a reference with an explicit alias (self-join occurrences). */
    public static TableRef of(Class<?> entityClass, String explicitAlias) {
        return new TableRef(entityClass, explicitAlias);
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

    /** The user-assigned alias of this occurrence, or null when auto-aliased. */
    public String getExplicitAlias() {
        return explicitAlias;
    }

    /** Renames the physical table for this query only (dynamic table name). */
    public TableRef rename(String physicalName) {
        this.tableName = physicalName;
        return this;
    }
}
