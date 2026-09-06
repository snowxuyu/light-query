package com.lightquery.exception;

/**
 * Base runtime exception of light-query. All framework exceptions extend
 * this class; no checked exceptions are ever thrown.
 */
public class LightQueryException extends RuntimeException {

    public LightQueryException(String message) {
        super(message);
    }

    public LightQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
