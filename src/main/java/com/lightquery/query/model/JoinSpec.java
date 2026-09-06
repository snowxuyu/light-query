package com.lightquery.query.model;

/**
 * A join clause: the join kind, the joined table and its ON condition group.
 *
 * @param type  join kind
 * @param table the joined table (already registered in the query scope)
 * @param on    the ON conditions, combined with AND
 */
public record JoinSpec(JoinType type, TableRef table, ConditionGroup on) {
}
