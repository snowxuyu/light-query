package com.lightquery.exception;

/**
 * Thrown when entity metadata is invalid (missing primary key, duplicate
 * column mapping, unsupported type...) or a lambda cannot be resolved to a
 * mapped property.
 */
public class MappingException extends LightQueryException {

    public MappingException(String message) {
        super(message);
    }

    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
