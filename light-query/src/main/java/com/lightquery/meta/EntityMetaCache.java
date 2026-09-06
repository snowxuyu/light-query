package com.lightquery.meta;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global per-class cache of {@link EntityMeta}. Metadata is immutable, so
 * the cache is a plain concurrent map with computeIfAbsent semantics.
 */
public final class EntityMetaCache {

    private static final Map<Class<?>, EntityMeta> CACHE = new ConcurrentHashMap<>();

    private EntityMetaCache() {
    }

    public static EntityMeta of(Class<?> entityClass) {
        return CACHE.computeIfAbsent(entityClass, EntityMeta::new);
    }
}
