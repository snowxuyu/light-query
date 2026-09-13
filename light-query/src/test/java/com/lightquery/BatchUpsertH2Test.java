package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.sqlgen.MySqlDialect;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T23 — insertBatch batchSize + upsert SQL shape + condition composition. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchUpsertH2Test {

    @Test
    void insertBatchWithBatchSize() {
        TestDb h2 = new TestDb("batchsize");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        var users = new java.util.ArrayList<User>();
        for (int i = 1; i <= 7; i++) {
            users.add(h2.user("bs" + i, User.Status.ACTIVE, i, null, null, 0));
        }
        var result = LightQuery.insertBatch(users, 3);
        assertEquals(7, result.size());
        assertEquals(7, LightQuery.queryable(User.class).col(User::getName).startsWith("bs").count());
    }

    @Test
    void upsertSqlShape() {
        TestDb h2 = new TestDb("upsert_shape");
        String sql = LightQuery.primary(h2.dataSource).dialect(new MySqlDialect())
                .queryable(User.class).col(User::getName).eq("x").toSql();
        // upsert SQL is verified at the dialect level; here we just confirm the facade works
        assertTrue(sql.contains("t_user"));
    }

    @Test
    void upsertSqlShapesPerDialect() {
        List<String> cols = List.of("id", "user_name");
        List<String> keys = List.of("id");
        // H2 2.x rejects ON CONFLICT .. DO UPDATE / ON DUPLICATE KEY UPDATE in regular
        // mode (probed against 2.2.224) — only the native MERGE .. KEY form works
        assertTrue(new com.lightquery.sqlgen.H2Dialect()
                .upsertSql("t_user", cols, keys, 1).startsWith("MERGE INTO \"t_user\""));
        assertTrue(new MySqlDialect()
                .upsertSql("t_user", cols, keys, 1).contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(new com.lightquery.sqlgen.PostgreSqlDialect()
                .upsertSql("t_user", cols, keys, 1).contains("ON CONFLICT"));
    }

    @Test
    void upsertUpdatesRowWhenPkConflicts() {
        TestDb h2 = new TestDb("upsert_conflict");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);

        User first = h2.user("conflict", User.Status.ACTIVE, 30, null, null, 0);
        first.setId(99L);
        LightQuery.upsert(first);

        // second upsert with the same PK must update, not duplicate
        User second = h2.user("conflict", User.Status.FROZEN, 41, null, "bumped", 0);
        second.setId(99L);
        LightQuery.upsert(second);

        List<User> rows = LightQuery.queryable(User.class)
                .col(User::getId).eq(99L)
                .toList();
        assertEquals(1, rows.size());
        assertEquals(User.Status.FROZEN, rows.get(0).getStatus());
        assertEquals(41, rows.get(0).getAge());
    }

    @Test
    void conditionCompositionIsReusable() {
        TestDb h2 = new TestDb("composition");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        for (int i = 1; i <= 5; i++) {
            LightQuery.insert(h2.user("comp" + i,
                    i % 2 == 0 ? User.Status.FROZEN : User.Status.ACTIVE, i, null, null, 0));
        }

        // Reusable condition: capture a Consumer<Where> and apply to multiple queries
        var activeFilter = new java.util.function.Consumer<com.lightquery.query.Where<User>>() {
            public void accept(com.lightquery.query.Where<User> w) {
                w.col(User::getStatus).eq(User.Status.ACTIVE);
            }
        };

        assertEquals(3, LightQuery.queryable(User.class).and(activeFilter).count());
        assertEquals(1, LightQuery.queryable(User.class).and(activeFilter)
                .col(User::getAge).ge(4).count());
    }

    @Test
    void upsertFiresInsertAndUpdateFill() {
        TestDb h2 = new TestDb("upsert_fill");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        List<String> events = new ArrayList<>();
        LightQuery.setFillListener(new com.lightquery.FillListener() {
            @Override
            public void onInsert(Object entity) {
                events.add("insert");
                if (entity instanceof User user) {
                    user.setRemark("filled-in");
                }
            }

            @Override
            public void onUpdate(Object entity) {
                events.add("update");
                if (entity instanceof User user) {
                    user.setRemark("filled-up");
                }
            }
        });
        try {
            // null PK: the statement could never conflict, upsert degenerates
            // to a plain insert — only onInsert fires
            LightQuery.upsert(h2.user("fill-insert", User.Status.ACTIVE, 30, null, null, 0));
            assertEquals(List.of("insert"), events);

            // non-null PK: both callbacks fire, onUpdate last
            events.clear();
            User upsert = h2.user("fill-upsert", User.Status.ACTIVE, 30, null, null, 0);
            upsert.setId(50L);
            LightQuery.upsert(upsert);
            assertEquals(List.of("insert", "update"), events);
            assertEquals("filled-up", LightQuery.queryable(User.class)
                    .col(User::getId).eq(50L)
                    .firstOrNull().getRemark());
        } finally {
            LightQuery.setFillListener(null);
        }
    }
}
