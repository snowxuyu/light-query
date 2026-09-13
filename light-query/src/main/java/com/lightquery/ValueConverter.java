package com.lightquery;

/**
 * Global type conversion SPI: converts one Java type to its database
 * representation and back. Register at startup via
 * {@link LightQuery#registerConverter(Class, ValueConverter)}; conversions
 * then apply on the full entity path — inserts/updates/upserts, condition
 * bind values and result mapping (everything that flows through the entity's
 * {@code ColumnMeta}, exactly like JPA {@code @Convert}).
 *
 * <p>Precedence: a field-level JPA {@code @Convert} wins over a global
 * converter, which wins over the default JDBC mapping. Matching is
 * <strong>by exact declared type</strong> — a converter registered for a
 * superclass never fires. Converters are only invoked for non-null values;
 * {@code null} passes straight through.</p>
 *
 * <p>Exceptions thrown by a converter are wrapped in
 * {@link com.lightquery.exception.MappingException} with the column and
 * property named.</p>
 *
 * @param <A> the attribute (entity property) type
 * @param <D> the database representation type
 */
public interface ValueConverter<A, D> {

    /** Converts the entity property value into the value bound to JDBC. */
    D toDatabase(A attribute);

    /** Converts a raw JDBC value into the entity property value. */
    A fromDatabase(D dbValue);
}
