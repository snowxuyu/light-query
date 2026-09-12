package com.lightquery;

import java.util.List;

/**
 * Observes every SQL statement before and after execution. Register via
 * {@link LightQuery#setSqlLogger(SqlLogger)}; set to {@code null} (or call
 * {@link LightQuery#clearSqlLogger()}) to disable.
 *
 * <p>A typical implementation logs to SLF4J, a file, or a monitoring system.
 * light-query itself never logs — the zero-dependency guarantee is preserved
 * because this SPI is inert unless the application provides an implementation.</p>
 */
public interface SqlLogger {

    /** Called immediately before the statement is executed. */
    default void beforeExecute(String sql, List<Object> params) {
    }

    /** Called after the statement completes successfully. */
    default void afterExecute(String sql, long elapsedMs) {
    }

    /** Called when execution throws; the exception is rethrown after this returns. */
    default void onError(String sql, List<Object> params, Exception e) {
    }
}
