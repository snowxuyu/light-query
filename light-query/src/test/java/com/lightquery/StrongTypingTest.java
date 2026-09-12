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

/**
 * Strong-typing smoke coverage: the single {@code col(...)} handle pins the
 * property value type at creation, so every condition built from it is
 * checked at compile time and executes correctly on H2.
 *
 * <p>Negative cases are intentionally documentation-only (they must NOT
 * compile — verified by hand during development):
 * <pre>
 * // expect: incompatible types — V is Integer, pinned by User::getAge
 * LightQuery.queryable(User.class).col(User::getAge).eq("abc");
 * // expect: incompatible types — V is String, pinned by User::getName
 * LightQuery.queryable(User.class).col(User::getName).gt(5);
 * // expect: incompatible types — set() needs the property type
 * LightQuery.updatable(User.class).col(User::getStatus).set(42);
 * // expect: incompatible types — sum() needs a Number property
 * LightQuery.queryable(User.class).sum(User::getName);
 * // expect: no suitable method — JoinOn wildcards were removed
 * LightQuery.queryable(User.class).innerJoin(Order.class, on -> on.eq(User::getId, Order::getUserId));
 * </pre>
 *
 * <p>Deliberately NOT compile-checked: {@code like} / {@code setIncrement}
 * on a wrong-typed column (e.g. {@code col(User::getAge).like("x")}) compiles
 * but is only meaningful on String / numeric columns — the mismatch surfaces
 * at the database, documented on each method.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StrongTypingTest {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("strongtyping");
        LightQuery.insertBatch(List.of(
                h2.user("frank", User.Status.ACTIVE, 30, "100.00", "team-a", 0),
                h2.user("alice", User.Status.FROZEN, 22, "200.00", "team-b", 0)));
        LightQuery.insertBatch(List.of(
                h2.order(1L, "50.00", 1),
                h2.order(1L, "150.00", 2)));
    }

    @Test
    void equalityFamily() {
        assertEquals(1, LightQuery.queryable(User.class).col(User::getStatus).eq(User.Status.ACTIVE).count());
        assertEquals(2, LightQuery.queryable(User.class).col(User::getName).in("frank", "alice").count());
    }

    @Test
    void rangeAndTextFamily() {
        assertEquals(1, LightQuery.queryable(User.class).col(User::getAge).ge(30).count());
        assertEquals(2, LightQuery.queryable(User.class).col(User::getAge).between(20, 35).count());
        assertEquals(1, LightQuery.queryable(User.class)
                .col(User::getBalance).gt(new BigDecimal("150")).count());
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).like("frank").count());
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).startsWith("ali").count());
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).endsWith("k").count());
        // column-to-column via the typed handle
        assertEquals(2, LightQuery.queryable(User.class)
                .leftJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getAge).ge(30).count());
    }

    @Test
    void numericLayerAndAggregations() {
        assertEquals(new BigDecimal("300.00"), LightQuery.queryable(User.class).sum(User::getBalance));
        assertEquals(30, LightQuery.queryable(User.class).max(User::getAge).intValue());
        String sql = LightQuery.queryable(Order.class)
                .select(Aggregations.sum(Order::getAmount).as("total"))
                .groupBy(Order::getStatus)
                .having(w -> w.gt(Aggregations.sum(Order::getAmount), new BigDecimal("10")))
                .toSql();
        assertTrue(sql.contains("HAVING"), sql);
    }

    @Test
    void updatableSingleHandle() {
        // read-then-verify on alice keeps the change relative: every other
        // test stays green no matter which order JUnit runs the methods in
        User alice = LightQuery.queryable(User.class).col(User::getName).eq("alice").firstOrNull();
        int before = alice.getAge();
        int rows = LightQuery.updatable(User.class)
                .col(User::getAge).setIncrement(1)
                .col(User::getName).eq("alice")
                .execute();
        assertEquals(1, rows);
        assertEquals(before + 1, LightQuery.queryById(User.class, alice.getId()).getAge());
    }

    @Test
    void joinOnConstantFamilyIsTyped() {
        List<User> rows = LightQuery.queryable(User.class)
                .innerJoin(Order.class, on -> on.col(Order::getStatus).eq(1))
                .col(User::getName).eq("frank")
                .toList();
        assertEquals(1, rows.size());
    }
}
