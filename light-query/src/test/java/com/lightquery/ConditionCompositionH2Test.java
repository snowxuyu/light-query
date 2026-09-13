package com.lightquery;

import com.lightquery.entity.Order;
import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T30 — offline condition composition: Conditions.col + Condition.and/or/not across all builders. */
class ConditionCompositionH2Test {

    /** Fresh database per test: every test gets alice/bob/carol/dave plus two orders. */
    private TestDb freshDb(String name) {
        TestDb h2 = new TestDb(name);
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        LightQuery.insertBatch(List.of(
                h2.user("alice", User.Status.ACTIVE, 30, null, null, 0),
                h2.user("bob", User.Status.ACTIVE, 17, null, null, 0),
                h2.user("carol", User.Status.FROZEN, 25, null, null, 0),
                h2.user("dave", User.Status.FROZEN, 40, null, null, 0)));
        LightQuery.insertBatch(List.of(
                h2.order(1L, "200.00", 1),
                h2.order(3L, "600.00", 2)));
        return h2;
    }

    @Test
    void andOrNotComposeAndExecuteOnH2() {
        TestDb h2 = freshDb("comp_andor");
        com.lightquery.query.Condition spec = com.lightquery.query.Conditions.col(User::getStatus)
                .eq(User.Status.ACTIVE)
                .and(com.lightquery.query.Conditions.col(User::getAge).ge(18));
        // ACTIVE and adult: alice only
        assertEquals(1, LightQuery.queryable(User.class).where(spec).count());

        // "aro" is a literal contains-match (framework escapes wildcards): matches carol only
        com.lightquery.query.Condition namedA = com.lightquery.query.Conditions.col(User::getName).like("aro");
        com.lightquery.query.Condition either = spec.or(namedA);
        // alice or carol
        assertEquals(2, LightQuery.queryable(User.class).where(either).count());

        // NOT the spec: bob, carol, dave
        assertEquals(3, LightQuery.queryable(User.class).where(spec.not()).count());
    }

    @Test
    void sameSpecAttachedToTwoQueriesStaysIndependent() {
        TestDb h2 = freshDb("comp_reuse");
        com.lightquery.query.Condition adult = com.lightquery.query.Conditions.col(User::getAge).ge(18);
        long withNameA = LightQuery.queryable(User.class)
                .where(adult)
                .col(User::getName).like("ali")
                .count();
        long frozenAdults = LightQuery.queryable(User.class)
                .where(adult)
                .col(User::getStatus).eq(User.Status.FROZEN)
                .count();
        // adult+alice = 1; adults frozen = carol+dave = 2; the second attach must not see the first's condition
        assertEquals(1, withNameA);
        assertEquals(2, frozenAdults);
    }

    @Test
    void skippedBooleanConditionComposesAsNothing() {
        TestDb h2 = freshDb("comp_skip");
        com.lightquery.query.Condition spec = com.lightquery.query.Conditions.col(User::getAge)
                .ge(false, 100)
                .and(com.lightquery.query.Conditions.col(User::getStatus).eq(true, User.Status.ACTIVE));
        // only the status condition remains: 2 ACTIVE users
        assertEquals(2, LightQuery.queryable(User.class).where(spec).count());
    }

    @Test
    void specColumnsResolveAgainstJoinedTables() {
        TestDb h2 = freshDb("comp_join");
        com.lightquery.query.Condition bigOrder = com.lightquery.query.Conditions.col(Order::getAmount)
                .gt(new java.math.BigDecimal("500"));
        // users with an order over 500: carol (id 3)
        List<User> rows = LightQuery.queryable(User.class)
                .innerJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .where(bigOrder)
                .toList();
        assertEquals(1, rows.size());
        assertEquals("carol", rows.get(0).getName());
    }

    @Test
    void subQuerySpecTriggersAliasRendering() {
        TestDb h2 = freshDb("comp_sub");
        com.lightquery.query.Condition hasOrder = com.lightquery.query.Conditions.col(User::getId)
                .in(LightQuery.queryable(Order.class).select(Order::getUserId));
        String sql = LightQuery.queryable(User.class).where(hasOrder).toSql();
        // the host model must alias its tables when a sub-query is present
        assertTrue(sql.contains("\"t_user\" \"t0\""), sql);
    }

    @Test
    void attachesToUpdateDeleteAndNestedGroups() {
        TestDb h2 = freshDb("comp_write");
        com.lightquery.query.Condition frozen = com.lightquery.query.Conditions.col(User::getStatus)
                .eq(User.Status.FROZEN);

        int updated = LightQuery.updatable(User.class)
                .where(frozen)
                .col(User::getRemark).set("frozen-seen")
                .execute();
        assertEquals(2, updated);

        int deleted = LightQuery.deletable(User.class)
                .where(frozen)
                .execute();
        assertEquals(2, deleted);

        LightQuery.clearFillListener();
        // inside a parenthesised group via Where
        long n = LightQuery.queryable(User.class)
                .and(w -> w.where(frozen).or().col(User::getAge).lt(18))
                .count();
        // group: FROZEN OR age<18 -> bob2 only
        assertEquals(1, n);
    }

    @Test
    void selfJoinTableColumnConditionsComposeOffline() {
        TestDb h2 = freshDb("comp_self");
        com.lightquery.QueryTable<User> staff = com.lightquery.QueryTable.of(User.class, "u1");
        com.lightquery.query.Condition spec = com.lightquery.query.Conditions.col(staff.col(User::getAge)).ge(18);
        // single-table query rooted at the named occurrence
        List<User> rows = LightQuery.queryable(staff)
                .where(spec)
                .toList();
        // ages 30/17/25/40 -> three adults
        assertEquals(3, rows.size());
    }

    @Test
    void allSkippedSpecDoesNotBypassTheFullTableGuard() {
        TestDb h2 = freshDb("comp_guard");
        // every condition in the spec is skipped -> the spec composes to nothing
        com.lightquery.query.Condition skipped = com.lightquery.query.Conditions.col(User::getAge)
                .ge(false, 100);
        // the guard must still fire: attaching nothing is NOT a condition
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.lightquery.exception.SqlBuildException.class,
                () -> LightQuery.updatable(User.class)
                        .where(skipped)
                        .col(User::getStatus).set(User.Status.FROZEN)
                        .execute());
        assertTrue(ex.getMessage().contains("allowFullTable"), ex.getMessage());

        // an empty spec combined away by and() keeps the live side only
        com.lightquery.query.Condition live = com.lightquery.query.Conditions.col(User::getName).eq("alice");
        assertEquals(1, LightQuery.queryable(User.class).where(live.and(skipped)).count());
    }

    @Test
    void eqNullViaCompositionRendersIsNull() {
        TestDb h2 = freshDb("comp_null");
        LightQuery.insert(h2.user("no-remark", User.Status.ACTIVE, 1, null, null, 0));
        String sql = LightQuery.queryable(User.class)
                .where(com.lightquery.query.Conditions.col(User::getRemark).eq(null))
                .toSql();
        assertTrue(sql.contains("IS NULL"), sql);
        // freshDb seeds 4 users with a null remark + the one inserted here
        assertEquals(5, LightQuery.queryable(User.class)
                .where(com.lightquery.query.Conditions.col(User::getRemark).eq(null))
                .count());
    }
}
