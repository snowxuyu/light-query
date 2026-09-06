package com.lightquery.exec;

import com.lightquery.Tuple;
import com.lightquery.exception.MappingException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Maps a projected result row ({@link Tuple}) onto an arbitrary VO class or
 * Java record. Components/properties are matched to result-set labels by
 * name, ignoring case and underscores ({@code userName}, {@code user_name}
 * and {@code USER_NAME} are equivalent), mirroring the entity label matching.
 *
 * <p>Records are created through their canonical constructor; VOs need a
 * no-arg constructor plus setters. Values are coerced to the target type
 * (numbers, strings, enums, temporal types). Plans are cached per type.</p>
 */
public final class RowProjector<R> {

    private static final Map<Class<?>, RowProjector<?>> CACHE = new ConcurrentHashMap<>();

    private final Class<R> type;
    private final Kind kind;
    private final Constructor<?> constructor;
    private final List<Property> properties;

    private RowProjector(Class<R> type, Kind kind, Constructor<?> constructor, List<Property> properties) {
        this.type = type;
        this.kind = kind;
        this.constructor = constructor;
        this.properties = properties;
    }

    @SuppressWarnings("unchecked")
    public static <R> RowProjector<R> of(Class<R> type) {
        Objects.requireNonNull(type, "projectionType");
        return (RowProjector<R>) CACHE.computeIfAbsent(type, RowProjector::create);
    }

    private static <R> RowProjector<R> create(Class<R> type) {
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] componentTypes = new Class<?>[components.length];
            List<Property> properties = new ArrayList<>(components.length);
            for (int i = 0; i < components.length; i++) {
                componentTypes[i] = components[i].getType();
                properties.add(new Property(components[i].getName(), components[i].getType()));
            }
            try {
                Constructor<?> constructor = type.getDeclaredConstructor(componentTypes);
                constructor.setAccessible(true);
                return new RowProjector<>(type, Kind.RECORD, constructor, properties);
            } catch (NoSuchMethodException e) {
                throw new MappingException("Record " + type.getName() + " has no canonical constructor", e);
            }
        }
        if (type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            throw new MappingException("Projection type " + type.getName()
                    + " must be a record or a concrete class with a no-arg constructor and setters");
        }
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new MappingException(type.getName()
                    + " needs a no-arg constructor to be used as a projection type", e);
        }
        Map<String, Class<?>> setters = new java.util.LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            if (method.getName().startsWith("set") && method.getName().length() > 3
                    && method.getParameterCount() == 1) {
                String property = Character.toLowerCase(method.getName().charAt(3))
                        + method.getName().substring(4);
                setters.put(property, method.getParameterTypes()[0]);
            }
        }
        if (setters.isEmpty()) {
            throw new MappingException(type.getName()
                    + " has no setters — a VO projection type needs setters for the selected columns");
        }
        List<Property> properties = new ArrayList<>(setters.size());
        for (Map.Entry<String, Class<?>> entry : setters.entrySet()) {
            properties.add(new Property(entry.getKey(), entry.getValue()));
        }
        return new RowProjector<>(type, Kind.POJO, constructor, properties);
    }

    /** Creates one instance from the projected row. */
    @SuppressWarnings("unchecked")
    public R project(Tuple row) {
        try {
            if (kind == Kind.RECORD) {
                Object[] args = new Object[properties.size()];
                for (int i = 0; i < properties.size(); i++) {
                    Property property = properties.get(i);
                    args[i] = coerce(labelValue(row, property.name()), property.type());
                }
                return (R) constructor.newInstance(args);
            }
            R instance = (R) constructor.newInstance();
            for (Property property : properties) {
                String setter = "set" + Character.toUpperCase(property.name().charAt(0))
                        + property.name().substring(1);
                Method method = type.getMethod(setter, property.type());
                Object value = coerce(labelValue(row, property.name()), property.type());
                method.invoke(instance, value);
            }
            return instance;
        } catch (MappingException e) {
            throw e;
        } catch (Exception e) {
            throw new MappingException("Cannot create projection " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    private Object labelValue(Tuple row, String name) {
        String normalized = normalize(name);
        for (String label : row.labels()) {
            if (normalize(label).equals(normalized)) {
                return row.get(label);
            }
        }
        throw new MappingException("Projection " + type.getName() + " expects a column/alias named '"
                + name + "' but the result set has: " + row.labels()
                + ". Add it to the select list or rename the component.");
    }

    private static String normalize(String name) {
        return name.toLowerCase().replace("_", "");
    }

    /** Converts a raw JDBC value into the projection component type. */
    private static Object coerce(Object value, Class<?> targetType) {
        if (value == null) {
            if (targetType.isPrimitive()) {
                // primitive components stay at their default; mirrors entity mapping
                return defaultValue(targetType);
            }
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (value instanceof Number n) {
            return coerceNumber(n, targetType);
        }
        if (targetType.isEnum()) {
            return coerceEnum(value, targetType);
        }
        if (value instanceof java.sql.Timestamp ts) {
            Object converted = coerceTimestamp(ts, targetType);
            if (converted != null) {
                return converted;
            }
        }
        if (value instanceof java.sql.Date d && targetType == LocalDate.class) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Time t && targetType == LocalTime.class) {
            return t.toLocalTime();
        }
        if (value instanceof Boolean b && (targetType == boolean.class || targetType == Boolean.class)) {
            return b;
        }
        return value;
    }

    private static Object defaultValue(Class<?> primitive) {
        if (primitive == boolean.class) {
            return false;
        }
        if (primitive == long.class) {
            return 0L;
        }
        if (primitive == double.class) {
            return 0d;
        }
        if (primitive == float.class) {
            return 0f;
        }
        if (primitive == short.class) {
            return (short) 0;
        }
        if (primitive == byte.class) {
            return (byte) 0;
        }
        if (primitive == char.class) {
            return '\0';
        }
        return 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerceEnum(Object value, Class<?> targetType) {
        if (value instanceof String s) {
            return Enum.valueOf((Class<? extends Enum>) targetType, s);
        }
        int ordinal = ((Number) value).intValue();
        Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
        return enumType.getEnumConstants()[ordinal];
    }

    private static Object coerceTimestamp(java.sql.Timestamp ts, Class<?> type) {
        if (type == LocalDateTime.class) {
            return ts.toLocalDateTime();
        }
        if (type == Date.class) {
            return new Date(ts.getTime());
        }
        if (type == Instant.class) {
            return ts.toInstant();
        }
        if (type == OffsetDateTime.class) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (type == LocalDate.class) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (type == LocalTime.class) {
            return ts.toLocalDateTime().toLocalTime();
        }
        return null;
    }

    private static Object coerceNumber(Number n, Class<?> type) {
        if (type == Integer.class || type == int.class) {
            return n.intValue();
        }
        if (type == Long.class || type == long.class) {
            return n.longValue();
        }
        if (type == Short.class || type == short.class) {
            return n.shortValue();
        }
        if (type == Byte.class || type == byte.class) {
            return n.byteValue();
        }
        if (type == Double.class || type == double.class) {
            return n.doubleValue();
        }
        if (type == Float.class || type == float.class) {
            return n.floatValue();
        }
        if (type == BigDecimal.class) {
            return n instanceof BigDecimal bd ? bd : BigDecimal.valueOf(n.doubleValue());
        }
        if (type == BigInteger.class) {
            return n instanceof BigInteger bi ? bi : BigInteger.valueOf(n.longValue());
        }
        return n;
    }

    private enum Kind { RECORD, POJO }

    private record Property(String name, Class<?> type) {
    }
}
