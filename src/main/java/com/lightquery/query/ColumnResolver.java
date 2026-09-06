package com.lightquery.query;

import com.lightquery.Agg;
import com.lightquery.lambda.SFunction;
import com.lightquery.meta.ColumnMeta;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Expr;

/**
 * Resolves an {@code SFunction} lambda to its column and metadata within the
 * current query scope. Implemented by the query builders; the seam that
 * turns API-time lambda references into render-time {@link ColumnRef}s.
 */
interface ColumnResolver {

    /** Resolves a lambda to its column reference and column metadata. */
    Resolved resolve(SFunction<?, ?> fn);

    /** Resolves an aggregate handle ({@link com.lightquery.F}) to an expression. */
    Expr resolveAgg(com.lightquery.Agg agg);

    /** Resolution result: column reference plus metadata for value conversion. */
    record Resolved(ColumnRef ref, ColumnMeta meta) {
    }
}
