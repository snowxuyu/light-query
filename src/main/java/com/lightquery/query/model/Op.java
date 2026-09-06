package com.lightquery.query.model;

/**
 * Comparison operators supported in WHERE / HAVING / ON conditions.
 */
public enum Op {
    EQ(" = "), NE(" <> "), GT(" > "), GE(" >= "), LT(" < "), LE(" <= "),
    LIKE(" LIKE "), NOT_LIKE(" NOT LIKE "),
    IN(" IN "), NOT_IN(" NOT IN "),
    IS_NULL(" IS NULL"), IS_NOT_NULL(" IS NOT NULL"),
    BETWEEN(" BETWEEN "), NOT_BETWEEN(" NOT BETWEEN "),
    EXISTS("EXISTS "), NOT_EXISTS("NOT EXISTS ");

    private final String text;

    Op(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
