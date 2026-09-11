package com.lightquery.query;

import com.lightquery.Aggregate;
import com.lightquery.TableColumn;
import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Expr;

/**
 * Resolves an {@code SFunction} lambda (or a self-join {@code TableColumn})
 * to its column and metadata within the current query scope. Implemented by
 * the query builders; the seam that turns API-time references into
 * render-time {@link ColumnRef}s.
 */
interface ColumnResolver {

    /** Resolves a lambda to its column reference and column metadata. */
    Resolved resolve(SFunction<?, ?> lambda);

    /** Resolves a self-join column to its column reference and column metadata. */
    Resolved resolve(TableColumn<?, ? > column);

    /** Resolves an aggregate handle ({@link com.lightquery.Aggregations}) to an expression. */
    Expr resolveAggregate(Aggregate aggregate);

    /** Resolution result: column reference plus metadata for value conversion. */
    record Resolved(ColumnRef ref, ColumnMeta meta) {
    }
}
