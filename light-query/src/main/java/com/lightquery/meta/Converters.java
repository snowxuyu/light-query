package com.lightquery.meta;

import com.lightquery.ValueConverter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of globally registered {@link ValueConverter}s,
 * keyed by the entity property's exact declared type. Internal wiring
 * between {@code LightQuery.registerConverter(...)} and
 * {@link ColumnMeta}; application code uses the facade methods.
 */
public final class Converters {

    private static final Map<Class<?>, ValueConverter<?, ?>> REGISTRY = new ConcurrentHashMap<>();

    private Converters() {
    }

    /**
     * Registers a converter for the exact attribute type; a duplicate
     * registration for the same type is rejected (clear the registry first
     * to replace it).
     */
    public static void register(Class<?> attributeType, ValueConverter<?, ?> converter) {
        Objects.requireNonNull(attributeType, "attributeType");
        Objects.requireNonNull(converter, "converter");
        if (REGISTRY.putIfAbsent(attributeType, converter) != null) {
            throw new IllegalStateException("A ValueConverter for " + attributeType.getName()
                    + " is already registered — call LightQuery.clearConverters() first "
                    + "to replace registered converters");
        }
    }

    /** Removes every registered converter. */
    public static void clear() {
        REGISTRY.clear();
    }

    /**
     * The converter registered for exactly this attribute type, or null —
     * a hot-path map lookup, deliberately uncached so registration order
     * relative to entity metadata never matters.
     */
    @SuppressWarnings("unchecked")
    public static ValueConverter<Object, Object> forType(Class<?> attributeType) {
        return (ValueConverter<Object, Object>) REGISTRY.get(attributeType);
    }
}
