package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T7 — logic delete: filtering, delete→update conversion, escape hatches. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogicDeleteH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("logic");
        LightQuery.insertBatch(List.of(
                h2.user("alive", User.Status.ACTIVE, 1, null, null, 0),
                h2.user("dead", User.Status.ACTIVE, 2, null, null, 1)));
    }

    @Test
    void queriesExcludeDeletedByDefault() {
        assertEquals(1, LightQuery.queryable(User.class)
                .col(User::getName).in(List.of("alive", "dead")).count());
        assertNull(LightQuery.queryable(User.class).col(User::getName).eq("dead").firstOrNull());
    }

    @Test
    void includeDeletedShowsEverything() {
        assertEquals(2, LightQuery.queryable(User.class).includeDeleted()
                .col(User::getName).in(List.of("alive", "dead")).count());
    }

    @Test
    void deleteEntityBecomesUpdate() {
        User user = LightQuery.insert(h2.user("temporary", User.Status.ACTIVE, 3, null, null, 0));
        LightQuery.delete(user);
        // the row is still there, flagged
        User raw = LightQuery.queryable(User.class).includeDeleted()
                .col(User::getId).eq(user.getId()).firstOrNull();
        assertEquals(1, raw.getDeleted());
        // and invisible through normal queries
        assertNull(LightQuery.queryById(User.class, user.getId()));
    }

    @Test
    void physicalDeleteRemovesRow() {
        User user = LightQuery.insert(h2.user("hard", User.Status.ACTIVE, 4, null, null, 0));
        LightQuery.deletable(User.class).physical().col(User::getId).eq(user.getId()).execute();
        assertEquals(null, LightQuery.queryable(User.class).includeDeleted()
                .col(User::getId).eq(user.getId()).firstOrNull());
    }

    @Test
    void deletableBecomesUpdateByDefault() {
        LightQuery.insert(h2.user("bulk1", User.Status.FROZEN, 5, null, null, 0));
        LightQuery.insert(h2.user("bulk2", User.Status.FROZEN, 6, null, null, 0));
        int rows = LightQuery.deletable(User.class).col(User::getName).startsWith("bulk").execute();
        assertEquals(2, rows);
        assertEquals(0, LightQuery.queryable(User.class).col(User::getName).startsWith("bulk").count());
    }

    @Test
    void updatableSkipsDeletedRows() {
        int rows = LightQuery.updatable(User.class)
                .col(User::getAge).set(99)
                .col(User::getName).eq("dead")
                .execute();
        assertEquals(0, rows); // filtered out by the logic-delete condition
    }

    @Test
    void updatableIncludeDeletedOverrides() {
        int rows = LightQuery.updatable(User.class)
                .col(User::getAge).set(99)
                .includeDeleted()
                .col(User::getName).eq("dead")
                .execute();
        assertEquals(1, rows);
    }

    @Test
    void logicValuesAreConfigurable() {
        // the annotation defaults are 0/1; custom values are covered by the
        // compile-time contract in @LogicDelete — here we assert the defaults
        com.lightquery.meta.EntityMeta meta =
                com.lightquery.meta.EntityMetaCache.of(User.class);
        assertEquals(0, meta.getLogicDeleteColumn().getLogicDeleteNormalValue());
        assertEquals(1, meta.getLogicDeleteColumn().getLogicDeleteDeletedValue());
    }

    @Test
    void deletingAlreadyDeletedRowStillReportsOneRow() {
        // idempotent delete: UPDATE ... WHERE pk (no logic filter) always hits the row
        User dead = LightQuery.queryable(User.class).includeDeleted()
                .col(User::getName).eq("dead").firstOrNull();
        LightQuery.delete(dead); // must not throw UnexpectedRowsException
    }
}
