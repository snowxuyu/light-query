package com.lightquery.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as the logic-delete flag. Queries automatically filter out
 * deleted rows and {@code delete} operations become updates that set this
 * column to {@link #deletedValue()}.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogicDelete {
    int normalValue() default 0;
    int deletedValue() default 1;
}
