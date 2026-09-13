package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T26 — raw SQL escape hatches: sqlHint, selectRaw, whereRaw, groupByRaw, orderByRaw. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RawSqlH2Test {

    private TestDb h2;

    @Test
    void sqlHintPrependsOptimizerComment() {
        h2 = new TestDb("rawhint");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        String sql = LightQuery.queryable(User.class)
                .sqlHint("INDEX(t_user idx_name)")
                .col(User::getName).eq("x")
                .toSql();
        assertTrue(sql.startsWith("SELECT /*+ INDEX(t_user idx_name) */"), sql);
    }

    @Test
    void selectRawAddsVerbatimExpression() {
        h2 = new TestDb("rawsel");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        try (var c = h2.dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("INSERT INTO \"t_user\" (\"user_name\", \"status\", \"deleted\") VALUES ('raw-sel', 'ACTIVE', 0)");
        } catch (Exception e) { throw new RuntimeException(e); }

        var tuples = LightQuery.queryable(User.class)
                .selectRaw("COUNT(*) AS total")
                .select(User::getStatus)
                .groupBy(User::getStatus)
                .toTupleList();
        assertTrue(tuples.size() >= 1);
        // H2 uppercases unquoted aliases; entity column labels stay quoted/lowercase
        assertTrue(tuples.get(0).labels().stream().anyMatch(l -> l.equalsIgnoreCase("total")),
                tuples.get(0).labels().toString());
    }

    @Test
    void whereRawAddsVerbatimCondition() {
        h2 = new TestDb("rawwhere");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        try (var c = h2.dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("INSERT INTO \"t_user\" (\"user_name\", \"status\", \"age\", \"deleted\") VALUES ('raw-where', 'ACTIVE', 50, 0)");
        } catch (Exception e) { throw new RuntimeException(e); }

        var rows = LightQuery.queryable(User.class)
                .whereRaw("\"age\" > ?", 40)
                .toList();
        assertEquals(1, rows.size());
        assertEquals("raw-where", rows.get(0).getName());
    }

    @Test
    void groupByRawWorks() {
        h2 = new TestDb("rawgroup");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        LightQuery.insertBatch(List.of(
                h2.user("raw-group", User.Status.ACTIVE, 30, null, null, 0)));
        var tuples = LightQuery.queryable(User.class)
                .selectRaw("UPPER(\"status\") AS st")
                .groupByRaw("UPPER(\"status\")")
                .toTupleList();
        assertEquals(1, tuples.size());
    }

    @Test
    void orderByRawWorks() {
        h2 = new TestDb("raworder");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        var rows = LightQuery.queryable(User.class)
                .orderByRaw("\"created_at\" DESC")
                .limit(1)
                .toList();
        assertTrue(rows.size() <= 1);
    }
}
