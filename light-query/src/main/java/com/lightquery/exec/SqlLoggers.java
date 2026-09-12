package com.lightquery.exec;

import com.lightquery.SqlLogger;

/**
 * Process-wide holder of the registered {@link SqlLogger}. Internal wiring
 * between the static facade ({@code LightQuery.setSqlLogger}) and the JDBC
 * executor; application code should use the facade methods.
 */
public final class SqlLoggers {

    private static volatile SqlLogger logger;

    private SqlLoggers() {
    }

    /** The registered logger, or null when logging is disabled. */
    public static SqlLogger current() {
        return logger;
    }

    /** Registers (or replaces) the logger; null disables logging. */
    public static void set(SqlLogger l) {
        logger = l;
    }
}
