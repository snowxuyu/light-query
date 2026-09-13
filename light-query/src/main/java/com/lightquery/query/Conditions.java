package com.lightquery.query;

import com.lightquery.TableColumn;
import com.lightquery.exception.MappingException;
import com.lightquery.lambda.LambdaUtils;
import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.meta.EntityMetaCache;
import com.lightquery.query.model.ColumnRef;

import java.util.Arrays;
import java.util.List;

/**
 * Static entry point of the condition composition API: builds
 * {@link Condition} trees offline (no query involved) and attaches them to
 * any query later. The terminals mirror the strongly-typed
 * {@link TypedColumn} family — the value type is fixed by the property
 * lambda and checked at compile time.
 *
 * <pre>{@code
 * import static com.lightquery.query.Conditions.col;
 *
 * Condition spec = col(User::getStatus).eq(ACTIVE)
 *         .and(col(User::getAge).ge(18).or(col(User::getName).like("a%")));
 * db.queryable(User.class).where(spec).toList();
 * }</pre>
 */
public final class Conditions {

    private Conditions() {
    }

    /**
     * Starts a strongly-typed offline condition on a property. The property's
     * entity must be in the host query's scope when the condition is attached
     * (root, joined, or an outer query for correlated sub-queries).
     */
    public static <C, V> TypedCondition<V> col(SFunction<C, V> property) {
        Class<?> lambdaClass = LambdaUtils.getImplClass(property);
        String name = LambdaUtils.getPropertyName(property);
        ColumnMeta meta = EntityMetaCache.of(lambdaClass).byProperty(name);
        if (meta == null) {
            throw new MappingException(lambdaClass.getSimpleName()
                    + " has no mapped property '" + name + "'");
        }
        return new TypedCondition<>(new ColumnRef(lambdaClass, meta.getColumnName()), meta);
    }

    /** Offline condition on a self-join occurrence column (see {@link QueryTable}). */
    public static <V> TypedCondition<V> col(TableColumn<?, V> column) {
        Class<?> entityType = column.table().entityType();
        String name = LambdaUtils.getPropertyName(column.property());
        ColumnMeta meta = EntityMetaCache.of(entityType).byProperty(name);
        if (meta == null) {
            throw new MappingException(entityType.getSimpleName()
                    + " has no mapped property '" + name + "'");
        }
        return new TypedCondition<>(
                new ColumnRef(entityType, meta.getColumnName(), column.table().alias(), column.outputLabel()),
                meta);
    }

    /**
     * Verbatim SQL fragment with {@code ?}-bound parameters — the offline
     * counterpart of {@code whereRaw}. Placeholder count must match the
     * parameter count (checked at render time).
     */
    public static Condition raw(String fragment, Object... params) {
        return Condition.leaf(com.lightquery.query.model.Condition.raw(
                fragment, params == null ? List.of() : Arrays.asList(params)), false);
    }

    // ------------------------------------------------------------------ internals

    static ColumnRef detachedRef(SFunction<?, ?> property) {
        Class<?> lambdaClass = LambdaUtils.getImplClass(property);
        String name = LambdaUtils.getPropertyName(property);
        ColumnMeta meta = EntityMetaCache.of(lambdaClass).byProperty(name);
        if (meta == null) {
            throw new MappingException(lambdaClass.getSimpleName()
                    + " has no mapped property '" + name + "'");
        }
        return new ColumnRef(lambdaClass, meta.getColumnName());
    }
}
