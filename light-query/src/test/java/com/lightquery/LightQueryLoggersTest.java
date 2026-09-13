package com.lightquery;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T28 — the SLF4J adapter maps SqlLogger callbacks to log calls with the right levels. */
class LightQueryLoggersTest {

    private record LogCall(String method, Object[] args) {
        private String first() {
            return String.valueOf(args == null || args.length == 0 ? null : args[0]);
        }
    }

    /** A slf4j Logger stub (JDK proxy) recording every call; DEBUG toggleable, ERROR always on. */
    private static Logger recordingLogger(List<LogCall> calls, boolean debugEnabled) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDebugEnabled" -> debugEnabled;
                    case "isErrorEnabled" -> true;
                    case "isTraceEnabled", "isInfoEnabled", "isWarnEnabled" -> false;
                    case "getName" -> "test-logger";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "stub-logger";
                    case "equals" -> proxy == args[0];
                    default -> {
                        if (method.getReturnType() == Void.TYPE) {
                            calls.add(new LogCall(method.getName(), args));
                            yield null;
                        }
                        yield null;
                    }
                });
    }

    @Test
    void beforeAfterAndErrorMapToDebugAndErrorLogs() {
        List<LogCall> calls = new ArrayList<>();
        SqlLogger logger = LightQueryLoggers.slf4j(recordingLogger(calls, true));

        logger.beforeExecute("SELECT 1", List.of(7, "x"));
        logger.afterExecute("SELECT 1", 3);
        RuntimeException boom = new RuntimeException("boom");
        logger.onError("SELECT 1", List.of(7, "x"), boom);

        assertEquals(3, calls.size());
        assertEquals("debug", calls.get(0).method());
        assertTrue(calls.get(0).first().contains("--> SELECT 1 | params=[7, x]"), calls.get(0).first());
        assertEquals("debug", calls.get(1).method());
        assertTrue(calls.get(1).first().contains("<-- 3 ms | SELECT 1"), calls.get(1).first());
        assertEquals("error", calls.get(2).method());
        assertTrue(calls.get(2).first().contains("--> SELECT 1 | params=[7, x] (failed)"), calls.get(2).first());
        assertSame(boom, calls.get(2).args()[1]);
    }

    @Test
    void debugCallsAreSuppressedWhenDebugDisabled() {
        List<LogCall> calls = new ArrayList<>();
        SqlLogger logger = LightQueryLoggers.slf4j(recordingLogger(calls, false));

        logger.beforeExecute("SELECT 1", List.of());
        logger.afterExecute("SELECT 1", 1);
        logger.onError("SELECT 1", List.of(), new RuntimeException("boom"));

        // only the error call goes out when DEBUG is disabled
        assertEquals(1, calls.size());
        assertEquals("error", calls.get(0).method());
    }
}
