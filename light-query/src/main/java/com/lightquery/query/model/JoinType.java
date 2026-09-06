package com.lightquery.query.model;

/** SQL join kinds supported by light-query. */
public enum JoinType {
    INNER("INNER JOIN"), LEFT("LEFT JOIN"), RIGHT("RIGHT JOIN");

    private final String text;

    JoinType(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
