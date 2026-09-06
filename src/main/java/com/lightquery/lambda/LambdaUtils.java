package com.lightquery.lambda;

import com.lightquery.exception.MappingException;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the property name behind an {@link SFunction} by reading the
 * serialized form of the lambda (the same technique the JDK itself uses).
 * Results are cached, so the reflection cost is paid once per lambda class.
 */
public final class LambdaUtils {

    private static final ConcurrentMap<Class<?>, Class<?>> IMPL_CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<SFunction<?, ?>, String> PROPERTY_CACHE = new ConcurrentHashMap<>();

    private LambdaUtils() {
    }

    /**
     * Extracts the property name from a lambda getter:
     * {@code User::getName} → {@code "name"}, {@code User::isActive} → {@code "active"}.
     *
     * @throws MappingException if the method reference is not a getter
     */
    public static <C> String getPropertyName(SFunction<C, ?> fn) {
        return PROPERTY_CACHE.computeIfAbsent(fn, f -> {
            String method = serialize(f).getImplMethodName();
            if (method.startsWith("get") && method.length() > 3) {
                return decapitalize(method.substring(3));
            }
            if (method.startsWith("is") && method.length() > 2) {
                return decapitalize(method.substring(2));
            }
            throw new MappingException("Lambda is not a property getter: '" + method
                    + "'. Use a getXxx()/isXxx() method reference such as User::getName.");
        });
    }

    /**
     * Returns the class that declares the lambda's implementation — normally
     * the entity class (or a superclass when the getter is inherited).
     */
    public static <C> Class<?> getImplClass(SFunction<C, ?> fn) {
        return IMPL_CLASS_CACHE.computeIfAbsent(fn.getClass(), k -> {
            String className = serialize(fn).getImplClass().replace('/', '.');
            ClassLoader loader = Thread.currentThread().getContextClassLoader() != null
                    ? Thread.currentThread().getContextClassLoader()
                    : LambdaUtils.class.getClassLoader();
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException e) {
                throw new MappingException("Cannot load lambda implementation class " + className, e);
            }
        });
    }

    private static SerializedLambda serialize(SFunction<?, ?> fn) {
        try {
            Method writeReplace = fn.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            return (SerializedLambda) writeReplace.invoke(fn);
        } catch (Exception e) {
            throw new MappingException("Cannot serialize lambda — make sure it is compiled by a "
                    + "standard javac compiler", e);
        }
    }

    private static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
