package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T15 — FillListener SPI: entity fields are filled before the SQL is built. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FillListenerH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("fill");
        LightQuery.setFillListener(new FillListener() {
            @Override
            public void onInsert(Object entity) {
                if (entity instanceof User user) {
                    user.setRemark("filled-insert");
                }
            }

            @Override
            public void onUpdate(Object entity) {
                if (entity instanceof User user) {
                    user.setRemark("filled-update");
                }
            }
        });
    }

    @AfterAll
    void tearDown() {
        LightQuery.clearFillListener();
    }

    @Test
    void insertFillsFields() {
        User user = h2.db.insert(h2.user("fill-ins", User.Status.ACTIVE, 1, null, null, 0));
        assertEquals("filled-insert", user.getRemark());
        assertEquals("filled-insert", h2.db.queryById(User.class, user.getId()).getRemark());
    }

    @Test
    void updateFillsFields() {
        User user = h2.db.insert(h2.user("fill-upd", User.Status.ACTIVE, 1, null, null, 0));
        user.setRemark(null);
        h2.db.update(user);
        assertEquals("filled-update", h2.db.queryById(User.class, user.getId()).getRemark());
    }

    @Test
    void batchInsertFillsEveryEntity() {
        List<User> users = h2.db.insertBatch(List.of(
                h2.user("fill-b1", User.Status.ACTIVE, 1, null, null, 0),
                h2.user("fill-b2", User.Status.ACTIVE, 1, null, null, 0)));
        for (User user : users) {
            // batch insert does not back-fill identity ids: resolve by name
            User loaded = h2.db.queryable(User.class).eq(User::getName, user.getName()).firstOrNull();
            assertEquals("filled-insert", loaded.getRemark());
        }
    }

    @Test
    void listenerExceptionPropagates() {
        LightQuery.setFillListener(new FillListener() {
            @Override
            public void onInsert(Object entity) {
                throw new IllegalStateException("fill failed");
            }
        });
        try {
            assertThrows(IllegalStateException.class,
                    () -> h2.db.insert(h2.user("fill-fail", User.Status.ACTIVE, 1, null, null, 0)));
            assertEquals(0, h2.db.queryable(User.class).eq(User::getName, "fill-fail").count());
        } finally {
            LightQuery.setFillListener(new FillListener() {
                @Override
                public void onInsert(Object entity) {
                    if (entity instanceof User user) {
                        user.setRemark("filled-insert");
                    }
                }

                @Override
                public void onUpdate(Object entity) {
                    if (entity instanceof User user) {
                        user.setRemark("filled-update");
                    }
                }
            });
        }
    }

    @Test
    void clearStopsFilling() {
        LightQuery.clearFillListener();
        try {
            User user = h2.db.insert(h2.user("fill-none", User.Status.ACTIVE, 1, null, null, 0));
            assertNull(user.getRemark());
        } finally {
            LightQuery.clearFillListener();
        }
    }
}
