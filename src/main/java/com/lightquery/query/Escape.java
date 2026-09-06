package com.lightquery.query;

/**
 * LIKE-pattern escaping. Light-query always escapes {@code \ % _} in LIKE
 * values so user input can never widen the pattern; dialects emit the
 * matching ESCAPE clause where needed.
 */
final class Escape {

    private Escape() {
    }

    static String contains(String value) {
        return "%" + escape(value) + "%";
    }

    static String prefix(String value) {
        return escape(value) + "%";
    }

    static String suffix(String value) {
        return "%" + escape(value);
    }

    static String escape(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
