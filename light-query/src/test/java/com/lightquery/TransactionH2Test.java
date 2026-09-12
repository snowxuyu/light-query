package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T8 — transaction commit / rollback / nesting semantics. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("tx");
    }

    @Test
    void commitPersistsAllWork() {
        Long id = LightQuery.inTransaction(tx -> {
            tx.insert(h2.user("txCommit", User.Status.ACTIVE, 1, null, null, 0));
            tx.updatable(User.class).col(User::getRemark).set("tx").col(User::getName).eq("txCommit").execute();
            return tx.queryable(User.class).col(User::getName).eq("txCommit").firstOrNull().getId();
        });
        assertEquals("tx", LightQuery.queryById(User.class, id).getRemark());
    }

    @Test
    void rollbackRevertsEverythingAndRethrows() {
        assertThrows(IllegalStateException.class, () -> LightQuery.inTransaction(tx -> {
            tx.insert(h2.user("txRollback", User.Status.ACTIVE, 1, null, null, 0));
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, LightQuery.queryable(User.class).col(User::getName).eq("txRollback").count());
    }

    @Test
    void checkedStyleFailuresRollBackToo() {
        assertThrows(RuntimeException.class, () -> LightQuery.inTransaction(tx -> {
            tx.insert(h2.user("txFail", User.Status.ACTIVE, 1, null, null, 0));
            // simulate any failure inside the work
            if (true) {
                throw new com.lightquery.exception.LightQueryException("simulated");
            }
            return null;
        }));
        assertEquals(0, LightQuery.queryable(User.class).col(User::getName).eq("txFail").count());
    }

    @Test
    void nestedTransactionReusesOuterConnection() {
        String name = LightQuery.inTransaction(outer -> outer.inTransaction(inner -> {
            inner.insert(h2.user("nested", User.Status.ACTIVE, 1, null, null, 0));
            return "nested-ok";
        }));
        assertEquals("nested-ok", name);
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).eq("nested").count());
    }

    @Test
    void statementsOutsideTransactionDoNotSeeUncommittedData() throws Exception {
        LightQuery.inTransaction(tx -> {
            tx.insert(h2.user("isolation", User.Status.ACTIVE, 1, null, null, 0));
            // a fresh connection (different transaction) must not see the row yet
            long visible = LightQuery.queryable(User.class).col(User::getName).eq("isolation").count();
            assertEquals(0, visible);
            return null;
        });
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).eq("isolation").count());
        assertTrue(true);
    }
}
