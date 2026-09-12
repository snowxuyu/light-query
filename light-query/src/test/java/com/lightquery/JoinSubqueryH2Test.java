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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6 — joins, sub-queries and correlated conditions against H2. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JoinSubqueryH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("join");
        h2.db.insertBatch(List.of(
                h2.user("withOrders", User.Status.ACTIVE, 30, null, null, 0),
                h2.user("withoutOrders", User.Status.ACTIVE, 25, null, null, 0)));
        h2.db.insertBatch(List.of(
                h2.order(1L, "80.00", 1),
                h2.order(1L, "120.00", 2)));
    }

    @Test
    void innerJoinDropsRowsWithoutMatch() {
        // SQL semantics: one row per matching order (the user has two orders)
        List<User> rows = h2.db.queryable(User.class)
                .innerJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .toList();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(u -> u.getName().equals("withOrders")));
    }

    @Test
    void leftJoinKeepsUnmatchedRows() {
        List<User> rows = h2.db.queryable(User.class)
                .leftJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .toList();
        // 2 matching order rows + 1 unmatched user row
        assertEquals(3, rows.size());
    }

    @Test
    void conditionsCanReferenceJoinedEntities() {
        List<User> rows = h2.db.queryable(User.class)
                .innerJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(Order::getAmount).ge(new BigDecimal("100"))
                .toList();
        assertEquals(1, rows.size());
        assertEquals("withOrders", rows.get(0).getName());
    }

    @Test
    void multipleConditionsJoinEveryTable() {
        List<User> rows = h2.db.queryable(User.class)
                .innerJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(Order::getAmount).gt(new BigDecimal("100"))
                .col(Order::getAmount).ge(new BigDecimal("100"))
                .toList();
        assertEquals(1, rows.size());
    }

    @Test
    void joinOnSupportsConstantConditions() {
        List<User> rows = h2.db.queryable(User.class)
                .innerJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId)
                        .col(Order::getStatus).eq(1))
                .toList();
        assertEquals(1, rows.size());
    }

    @Test
    void tupleProjectionAcrossTables() {
        List<Tuple> rows = h2.db.queryable(User.class)
                .innerJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .select(User::getName).select(Order::getAmount)
                .col(Order::getAmount).ge(new BigDecimal("100"))
                .toTupleList();
        assertEquals(1, rows.size());
        assertEquals("withOrders", rows.get(0).get("user_name"));
        assertEquals(new BigDecimal("120.00"), rows.get(0).get("amount"));
    }

    @Test
    void inSubQuery() {
        List<User> rows = h2.db.queryable(User.class)
                .col(User::getId).in(h2.db.queryable(Order.class).select(Order::getUserId))
                .toList();
        assertEquals(1, rows.size());
        assertEquals("withOrders", rows.get(0).getName());
    }

    @Test
    void notInSubQuery() {
        List<User> rows = h2.db.queryable(User.class)
                .col(User::getId).notIn(h2.db.queryable(Order.class).select(Order::getUserId))
                .toList();
        assertEquals(1, rows.size());
        assertEquals("withoutOrders", rows.get(0).getName());
    }

    @Test
    void correlatedExists() {
        List<User> rows = h2.db.queryable(User.class)
                .whereExists(h2.db.queryable(Order.class)
                        .col(Order::getUserId).eqColumn(User::getId)
                        .col(Order::getAmount).ge(new BigDecimal("100")))
                .toList();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getName().equals("withOrders"));
    }

    @Test
    void correlatedNotExists() {
        List<User> rows = h2.db.queryable(User.class)
                .whereNotExists(h2.db.queryable(Order.class)
                        .col(Order::getUserId).eqColumn(User::getId))
                .toList();
        assertEquals(1, rows.size());
        assertEquals("withoutOrders", rows.get(0).getName());
    }

    @Test
    void scalarSubQueryComparison() {
        List<Order> rows = h2.db.queryable(Order.class)
                .geSubQuery(Order::getAmount, h2.db.queryable(Order.class)
                        .select(Aggregations.avg(Order::getAmount)))
                .toList();
        assertEquals(1, rows.size());
        assertEquals(new BigDecimal("120.00"), rows.get(0).getAmount());
    }

    @Test
    void referencingUnknownEntityFailsWithHelpfulMessage() {
        // Order is not joined — the error must say so and list the scope
        try {
            h2.db.queryable(User.class).col(Order::getAmount).eq(TestDb.decimal("1")).toSql();
            org.junit.jupiter.api.Assertions.fail("expected SqlBuildException");
        } catch (com.lightquery.exception.SqlBuildException e) {
            assertTrue(e.getMessage().contains("Order"), () -> e.getMessage());
            assertTrue(e.getMessage().toLowerCase().contains("join"), () -> e.getMessage());
        }
    }
}
