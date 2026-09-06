package com.lightquery.exception;

/**
 * Thrown when an UPDATE/DELETE statement expected to affect exactly one row
 * but affected none or several (e.g. the row was deleted concurrently).
 */
public class UnexpectedRowsException extends LightQueryException {

    private final int affectedRows;

    public UnexpectedRowsException(String message, int affectedRows) {
        super(message);
        this.affectedRows = affectedRows;
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}
