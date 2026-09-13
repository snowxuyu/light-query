package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T27 — UNION / UNION ALL + saveOrUpdate. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnionAndSaveOrUpdateTest {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("union_su");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        LightQuery.insertBatch(List.of(
                h2.user("alice", User.Status.ACTIVE, 30, null, null, 0),
                h2.user("bob", User.Status.FROZEN, 25, null, null, 0)));
    }

    @Test
    void unionCombinesTwoQueries() {
        // UNION deduplicates: users named alice + users named bob
        var combined = LightQuery.queryable(User.class)
                .col(User::getName).eq("alice")
                .union(LightQuery.queryable(User.class)
                        .col(User::getName).eq("bob"))
                .toList();
        assertEquals(2, combined.size());
    }

    @Test
    void unionAllKeepsDuplicates() {
        // UNION ALL: users with age >= 25 (bob=25, alice=30) + users with age >= 28 (alice=30)
        // alice appears in both → 3 rows total
        var combined = LightQuery.queryable(User.class)
                .col(User::getAge).ge(25)
                .unionAll(LightQuery.queryable(User.class)
                        .col(User::getAge).ge(28))
                .toList();
        assertEquals(3, combined.size());
    }

    @Test
    void saveOrUpdateInsertsWhenPkIsNull() {
        User user = h2.user("save-new", User.Status.ACTIVE, 1, null, null, 0);
        LightQuery.saveOrUpdate(user);
        assertTrue(user.getId() != null, "insert should back-fill identity key");
        assertEquals(1, LightQuery.queryable(User.class)
                .col(User::getName).eq("save-new").count());
    }

    @Test
    void saveOrUpdateUpdatesWhenPkExists() {
        User user = LightQuery.insert(h2.user("su-exist", User.Status.ACTIVE, 20, null, null, 0));
        user.setAge(99);
        user.setStatus(User.Status.FROZEN);
        LightQuery.saveOrUpdate(user);
        User loaded = LightQuery.queryById(User.class, user.getId());
        assertEquals(99, loaded.getAge());
        assertEquals(User.Status.FROZEN, loaded.getStatus());
    }
    @Test
    void unionPartnerWithOrderByRejected() {
        var root = LightQuery.queryable(User.class);
        var partner = LightQuery.queryable(User.class).orderByAsc(User::getId);
        var ex = assertThrows(SqlBuildException.class, () -> root.union(partner));
        assertTrue(ex.getMessage().contains("UNION partner"), ex.getMessage());
    }

    @Test
    void unionPartnerWithLimitRejected() {
        var root = LightQuery.queryable(User.class);
        var partner = LightQuery.queryable(User.class).limit(5);
        var ex = assertThrows(SqlBuildException.class, () -> root.unionAll(partner));
        assertTrue(ex.getMessage().contains("UNION partner"), ex.getMessage());
    }

    @Test
    void unionColumnCountMismatchRejected() {
        var root = LightQuery.queryable(User.class).select(User::getId);
        var partner = LightQuery.queryable(User.class).select(User::getId, User::getName);
        var ex = assertThrows(SqlBuildException.class, () -> root.union(partner));
        assertTrue(ex.getMessage().contains("column count mismatch"), ex.getMessage());
        assertTrue(ex.getMessage().contains("1 column(s)") && ex.getMessage().contains("2 column(s)"),
                ex.getMessage());
    }

    @Test
    void unionWithEqualExplicitWidthsStillWorks() {
        var combined = LightQuery.queryable(User.class)
                .select(User::getName)
                .col(User::getName).eq("alice")
                .union(LightQuery.queryable(User.class)
                        .select(User::getName)
                        .col(User::getName).eq("bob"))
                .toTupleList();
        assertEquals(2, combined.size());
    }
}
