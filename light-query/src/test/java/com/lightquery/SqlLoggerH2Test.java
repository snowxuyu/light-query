package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.DataAccessException;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T21 — SqlLogger SPI: observe SQL execution before/after/error. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlLoggerH2Test {

    private TestDb h2;
    private final List<String> events = new ArrayList<>();

    @BeforeAll
    void setUp() {
        h2 = new TestDb("sqllogger");
    }

    @AfterAll
    void tearDown() {
        LightQuery.clearSqlLogger();
    }

    @Test
    void beforeAndAfterFireOnQuery() {
        LightQuery.setSqlLogger(new SqlLogger() {
            public void beforeExecute(String sql, List<Object> params) {
                events.add("before:" + sql);
            }
            public void afterExecute(String sql, long elapsedMs) {
                events.add("after");
            }
        });
        try {
            LightQuery.queryable(User.class).col(User::getName).eq("test-user").toList();
            assertEquals(2, events.size());
            assertTrue(events.get(0).startsWith("before:"), events.get(0));
            assertTrue(events.get(0).contains("t_user"));
            assertEquals("after", events.get(1));
        } finally {
            LightQuery.clearSqlLogger();
        }
    }

    @Test
    void onErrorFiresOnBadSql() {
        List<String> errors = new ArrayList<>();
        LightQuery.setSqlLogger(new SqlLogger() {
            public void onError(String sql, List<Object> params, Exception e) {
                errors.add(e.getMessage());
            }
        });
        try {
            assertThrows(DataAccessException.class, () ->
                    LightQuery.queryable(User.class).asTable("nonexistent_xyz").toList());
            assertEquals(1, errors.size());
        } finally {
            LightQuery.clearSqlLogger();
        }
    }

    @Test
    void noLoggerIsNoOp() {
        LightQuery.clearSqlLogger();
        assertEquals(0, LightQuery.queryable(User.class).col(User::getName).eq("nobody").count());
    }
}
