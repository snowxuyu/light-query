package com.lightquery.query.model;

/**
 * ORDER BY entry: either a resolved {@link Selectable} or a raw output alias
 * (quoted as an identifier — the only string form accepted, e.g. ordering by
 * an aggregate alias such as {@code cnt}).
 */
public sealed interface OrderBy permits OrderBy.ByExpr, OrderBy.ByAlias, OrderBy.ByRaw {

    boolean asc();

    record ByExpr(Selectable expr, boolean asc) implements OrderBy {
    }

    record ByAlias(String alias, boolean asc) implements OrderBy {
    }

    /** Raw ORDER BY expression rendered verbatim (no ASC/DESC appended). */
    record ByRaw(String sql) implements OrderBy {
        public boolean asc() { return true; }
    }
}
