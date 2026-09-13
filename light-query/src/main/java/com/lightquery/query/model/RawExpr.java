package com.lightquery.query.model;

/**
 * A verbatim SQL fragment rendered as-is in the SELECT list, GROUP BY, or
 * ORDER BY clause. Escape hatch for database-specific expressions the
 * type-safe API cannot express (e.g. {@code NOW()}, {@code DATE_FORMAT(...)},
 * PolarDB optimizer hints).
 *
 * <p>The developer is responsible for the SQL content — it is inserted
 * verbatim. Use {@code ?} binding via whereRaw for user-supplied values.</p>
 */
public record RawExpr(String sql, String outputLabel) implements Selectable {

    public RawExpr(String sql) {
        this(sql, null);
    }

    /** A copy labelled with {@code label} for the SELECT list / Tuple key. */
    public RawExpr as(String label) {
        return new RawExpr(sql, label);
    }
}
