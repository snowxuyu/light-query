package com.lightquery;

import com.lightquery.entity.Order;
import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5 — query operator semantics and terminals against H2. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("query");
        LightQuery.insertBatch(List.of(
                h2.user("test-user", User.Status.ACTIVE, 30, "100.00", "team-a", 0),
                h2.user("test%user", User.Status.ACTIVE, 40, "200.00", null, 0),
                h2.user("alice", User.Status.FROZEN, 22, null, "team-b", 0),
                h2.user("bob", User.Status.ACTIVE, 17, "0.00", "team-a", 0)));
        LightQuery.insertBatch(List.of(
                h2.order(1L, "50.00", 1),
                h2.order(1L, "150.00", 2),
                h2.order(3L, "500.00", 1)));
    }

    @Test
    void likeEscapesUserWildcards() {
        // the literal name "test%user" must match exactly, not widen the pattern
        List<User> rows = LightQuery.queryable(User.class).col(User::getName).like("test%").toList();
        assertEquals(1, rows.size());
        assertEquals("test%user", rows.get(0).getName());
    }

    @Test
    void startsWithAndEndsWith() {
        assertEquals(2, LightQuery.queryable(User.class).col(User::getName).startsWith("test").count());
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).endsWith("e").count());
    }

    @Test
    void inAndBetween() {
        assertEquals(2, LightQuery.queryable(User.class).col(User::getName).in("test-user", "alice").count());
        assertEquals(2, LightQuery.queryable(User.class).col(User::getAge).between(20, 35).count());
    }

    @Test
    void nullSemantics() {
        assertEquals(1, LightQuery.queryable(User.class).col(User::getBalance).isNull().count());
        assertEquals(3, LightQuery.queryable(User.class).col(User::getBalance).isNotNull().count());
    }

    @Test
    void nestedGroups() {
        // status=ACTIVE AND (age < 18 OR balance >= 200)
        List<User> rows = LightQuery.queryable(User.class)
                .col(User::getStatus).eq(User.Status.ACTIVE)
                .and(w -> w.col(User::getAge).lt(18).or().col(User::getBalance).ge(new BigDecimal("200")))
                .toList();
        assertEquals(2, rows.size());
    }

    @Test
    void orderByLimitAndFirst() {
        List<User> top2 = LightQuery.queryable(User.class)
                .orderByDesc(User::getAge)
                .limit(2)
                .toList();
        assertEquals(40, top2.get(0).getAge());

        User youngest = LightQuery.queryable(User.class)
                .orderByAsc(User::getAge)
                .firstOrNull();
        assertEquals(17, youngest.getAge());
    }

    @Test
    void paginationTotals() {
        PageResult<User> page = LightQuery.queryable(User.class)
                .orderByAsc(User::getId)
                .toPageResult(2, 3);
        assertEquals(4, page.total());
        assertEquals(1, page.rows().size());
        assertEquals(2, page.pages());
        assertEquals(2, page.pageNo());
    }

    @Test
    void existsAndCount() {
        assertTrue(LightQuery.queryable(User.class).col(User::getName).eq("alice").exists());
        assertFalse(LightQuery.queryable(User.class).col(User::getName).eq("nobody").exists());
        assertEquals(3, LightQuery.queryable(User.class).col(User::getStatus).eq(User.Status.ACTIVE).count());
    }

    @Test
    void aggregateTerminals() {
        assertEquals(new BigDecimal("300.00"), LightQuery.queryable(User.class).sum(User::getBalance));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
                (BigDecimal) LightQuery.queryable(User.class).col(User::getName).eq("nobody").sum(User::getBalance)));
        assertEquals(40, LightQuery.queryable(User.class).max(User::getAge).intValue());
        assertNull(LightQuery.queryable(User.class).col(User::getName).eq("nobody").max(User::getAge));
    }

    @Test
    void groupedTupleQuery() {
        List<Tuple> rows = LightQuery.queryable(Order.class)
                .select(Order::getUserId)
                .select(Aggregations.count().as("cnt"), Aggregations.sum(Order::getAmount).as("total"))
                .groupBy(Order::getUserId)
                .having(w -> w.gt(Aggregations.count(), 1))
                .orderByDesc("cnt")
                .toTupleList();
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("user_id"));
        assertEquals(2L, rows.get(0).get("cnt", Long.class));
        assertEquals(new BigDecimal("200.00"), rows.get(0).get("total"));
    }

    @Test
    void distinctProjection() {
        List<Tuple> rows = LightQuery.queryable(User.class)
                .select(User::getStatus)
                .distinct()
                .toTupleList();
        assertEquals(2, rows.size());
    }

    @Test
    void dynamicTableName() {
        // asTable routes the query to a physical table (sharding scenario)
        assertEquals(0, LightQuery.queryable(User.class).asTable("t_user_202401").count());
    }
}
