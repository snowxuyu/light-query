package com.lightquery;

/**
 * Listener SPI for automatic entity field filling: invoked right before the
 * SQL of an entity-level write is built. Implementations set the fields they
 * care about directly on the entity (e.g. audit timestamps):
 *
 * <pre>{@code
 * LightQuery.setFillListener(new FillListener() {
 *     public void onInsert(Object entity) {
 *         if (entity instanceof User user) {
 *             user.setCreatedAt(LocalDateTime.now());
 *         }
 *     }
 *     public void onUpdate(Object entity) {
 *         if (entity instanceof User user) {
 *             user.setUpdatedAt(LocalDateTime.now());
 *         }
 *     }
 * });
 * }</pre>
 *
 * <p>Applies to {@code insert / insertBatch / update / updateSelective} across
 * all registered data sources; fluent {@code Updatable/Deletable} and the
 * delete operations carry no entity and are not called. Exceptions thrown by
 * the listener propagate unchanged and fail the current operation (inside a
 * transaction this rolls back as usual).</p>
 */
public interface FillListener {

    /** Called once per entity before its INSERT is built. */
    default void onInsert(Object entity) {
    }

    /** Called once per entity before its UPDATE is built. */
    default void onUpdate(Object entity) {
    }
}
