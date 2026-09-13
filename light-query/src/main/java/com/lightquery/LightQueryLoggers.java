package com.lightquery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Ready-made {@link SqlLogger} implementations.
 *
 * <p>{@code slf4j-api} is an <em>optional</em> compile-time dependency and is
 * never on the runtime dependency list: the adapter classes below touch
 * {@code org.slf4j} only when one of the {@code slf4j(...)} factories is
 * actually invoked, so applications without SLF4J pay nothing. When SLF4J is
 * missing the factories fail with an {@link IllegalStateException} explaining
 * how to fix the classpath.</p>
 *
 * <p>The SLF4J adapter is stateless and thread-safe; one shared instance can
 * be registered globally. Log levels: statement and timing at DEBUG, failures
 * at ERROR with the exception attached.</p>
 */
public final class LightQueryLoggers {

    private LightQueryLoggers() {
    }

    /**
     * A {@link SqlLogger} writing to the SLF4J logger named {@code light-query}.
     *
     * @throws IllegalStateException when {@code slf4j-api} is not on the classpath
     */
    public static SqlLogger slf4j() {
        return slf4j("light-query");
    }

    /**
     * A {@link SqlLogger} writing to the SLF4J logger with the given name.
     *
     * @param loggerName SLF4J logger name (usually the owning package or class)
     * @throws IllegalStateException when {@code slf4j-api} is not on the classpath
     */
    public static SqlLogger slf4j(String loggerName) {
        return slf4j(createLogger(loggerName));
    }

    /**
     * A {@link SqlLogger} writing to the given SLF4J logger — handy for
     * class-based names: {@code slf4j(LoggerFactory.getLogger(getClass()))}.
     *
     * @param logger the SLF4J logger to write to
     */
    public static SqlLogger slf4j(Logger logger) {
        return new Slf4jSqlLogger(logger);
    }

    private static Logger createLogger(String loggerName) {
        try {
            return LoggerFactory.getLogger(loggerName);
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException("slf4j-api was not found on the classpath — "
                    + "add org.slf4j:slf4j-api (plus a binding such as logback) to use "
                    + "LightQueryLoggers.slf4j(...), or register your own SqlLogger implementation", e);
        }
    }

    private static final class Slf4jSqlLogger implements SqlLogger {

        private final Logger logger;

        private Slf4jSqlLogger(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void beforeExecute(String sql, List<Object> params) {
            if (logger.isDebugEnabled()) {
                logger.debug("--> " + sql + " | params=" + params);
            }
        }

        @Override
        public void afterExecute(String sql, long elapsedMs) {
            if (logger.isDebugEnabled()) {
                logger.debug("<-- " + elapsedMs + " ms | " + sql);
            }
        }

        @Override
        public void onError(String sql, List<Object> params, Exception e) {
            if (logger.isErrorEnabled()) {
                logger.error("--> " + sql + " | params=" + params + " (failed)", e);
            }
        }
    }
}
