package com.lightquery.exception;

/**
 * Thrown when the query cannot be built: referencing an entity that was not
 * joined, executing a full-table UPDATE/DELETE without the explicit escape
 * hatch, reusing a consumed queryable, a dialect missing a feature, and so on.
 *
 * <p>Messages always state the problem and how to fix it.</p>
 */
public class SqlBuildException extends LightQueryException {

    public SqlBuildException(String message) {
        super(message);
    }
}
