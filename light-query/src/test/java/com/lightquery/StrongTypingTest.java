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
 * Strong-typing smoke coverage: every layered entry ({@code col / cmpCol /
 * strCol / numCol}) compiles against its intended property types and executes
 * correctly on H2.
 *
 * <p>Negative cases are intentionally documentation-only (they must NOT
 * compile — verified by hand during development):
 * <pre>
 * // expect: cannot find symbol — like() is not on the base/Comparable layers
 * db.queryable(User.class).col(User::getAge).like("x");
 * db.queryable(User.class).cmpCol(User::getAge).like("x");
 * // expect: incompatible types — gt() needs the property type
 * db.queryable(User.class).strCol(User::getName).gt(5);
 * // expect: incompatible types — setIncrement() lives on numCol only
 * db.updatable(User.class).col(User::getAge).setIncrement(1);
 * // expect: incompatible types — sum() needs a Number property
 * db.queryable(User.class).sum(User::getName);
 * // expect: no suitable method — JoinOn wildcards were removed
 * db.queryable(User.class).innerJoin(Order.class, on -> on.eq(User::getId, Order::getUserId));
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StrongTypingTest {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("strongtyping");
        h2.db.insertBatch(List.of(
                h2.user("frank", User.Status.ACTIVE, 30, "100.00", "team-a", 0),
                h2.user("alice", User.Status.FROZEN, 22, "200.00", "team-b", 0)));
        h2.db.insertBatch(List.of(
                h2.order(1L, "50.00", 1),
                h2.order(1L, "150.00", 2)));
    }

    @Test
    void baseEqualityLayer() {
        assertEquals(1, h2.db.queryable(User.class).col(User::getStatus).eq(User.Status.ACTIVE).count());
        assertEquals(2, h2.db.queryable(User.class).col(User::getName).in("frank", "alice").count());
    }

    @Test
    void comparableLayer() {
        assertEquals(1, h2.db.queryable(User.class).cmpCol(User::getAge).ge(30).count());
        assertEquals(2, h2.db.queryable(User.class).cmpCol(User::getAge).between(20, 35).count());
        assertEquals(1, h2.db.queryable(User.class)
                .cmpCol(User::getBalance).gt(new BigDecimal("150")).count());
        // column-to-column via the typed handle
        assertEquals(2, h2.db.queryable(User.class)
                .leftJoin(Order.class, on -> on.cmpCol(User::getId).eqColumn(Order::getUserId))
                .cmpCol(User::getAge).ge(30).count());
    }

    @Test
    void stringLayer() {
        assertEquals(1, h2.db.queryable(User.class).strCol(User::getName).like("frank").count());
        assertEquals(1, h2.db.queryable(User.class).strCol(User::getName).startsWith("ali").count());
        assertEquals(1, h2.db.queryable(User.class).strCol(User::getName).endsWith("k").count());
    }

    @Test
    void numericLayerAndAggregations() {
        assertEquals(new BigDecimal("300.00"), h2.db.queryable(User.class).sum(User::getBalance));
        assertEquals(30, h2.db.queryable(User.class).max(User::getAge).intValue());
        String sql = h2.db.queryable(Order.class)
                .select(Aggregations.sum(Order::getAmount).as("total"))
                .groupBy(Order::getStatus)
                .having(w -> w.gt(Aggregations.sum(Order::getAmount), new BigDecimal("10")))
                .toSql();
        assertTrue(sql.contains("HAVING"), sql);
    }

    @Test
    void updatableLayers() {
        // read-then-verify on alice keeps the change relative: every other
        // test stays green no matter which order JUnit runs the methods in
        User alice = h2.db.queryable(User.class).strCol(User::getName).eq("alice").firstOrNull();
        int before = alice.getAge();
        int rows = h2.db.updatable(User.class)
                .numCol(User::getAge).setIncrement(1)
                .strCol(User::getName).eq("alice")
                .execute();
        assertEquals(1, rows);
        assertEquals(before + 1, h2.db.queryById(User.class, alice.getId()).getAge());
    }

    @Test
    void joinOnConstantFamilyIsTyped() {
        List<User> rows = h2.db.queryable(User.class)
                .innerJoin(Order.class, on -> on.eq(Order::getStatus, 1))
                .col(User::getName).eq("frank")
                .toList();
        assertEquals(1, rows.size());
    }
}
