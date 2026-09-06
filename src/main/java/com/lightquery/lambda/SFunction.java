package com.lightquery.lambda;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A serializable property getter used to reference columns in a type-safe
 * way, e.g. {@code User::getName}. No annotation processor is required —
 * the property name is resolved at runtime from the serialized lambda.
 *
 * @param <T> entity type
 * @param <R> property type
 */
@FunctionalInterface
public interface SFunction<T, R> extends Function<T, R>, Serializable {
}
