package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.UnexpectedRowsException;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T4 — entity CRUD against H2: insert, keys, update, delete, count. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrudH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("crud");
    }

    @Test
    void insertBackfillsIdentityKey() {
        User user = h2.user("frank", User.Status.ACTIVE, 30, "100.50", "ok", 0);
        User returned = h2.db.insert(user);
        assertNotNull(user.getId());
        assertEquals(user.getId(), returned.getId());

        User loaded = h2.db.queryById(User.class, user.getId());
        assertNotNull(loaded);
        assertEquals("frank", loaded.getName());
        assertEquals(User.Status.ACTIVE, loaded.getStatus());
        assertEquals(new BigDecimal("100.50"), loaded.getBalance());
        assertNull(loaded.getIgnored()); // @Transient never mapped
    }

    @Test
    void insertBatchPersistsAll() {
        List<User> users = List.of(
                h2.user("batch1", User.Status.ACTIVE, 20, null, null, 0),
                h2.user("batch2", User.Status.FROZEN, 21, "1.00", null, 0));
        h2.db.insertBatch(users);
        assertEquals("batch1", h2.db.queryable(User.class)
                .eq(User::getName, "batch1").firstOrNull().getName());
        assertEquals(2, h2.db.queryable(User.class)
                .startsWith(User::getName, "batch").count());
    }

    @Test
    void updateWritesAllColumns() {
        User user = h2.db.insert(h2.user("toUpdate", User.Status.ACTIVE, 30, null, null, 0));
        user.setName("updated");
        user.setAge(31);
        user.setBalance(new BigDecimal("9.99"));
        h2.db.update(user);

        User reloaded = h2.db.queryById(User.class, user.getId());
        assertEquals("updated", reloaded.getName());
        assertEquals(31, reloaded.getAge());
        assertEquals(new BigDecimal("9.99"), reloaded.getBalance());
    }

    @Test
    void updateSelectiveKeepsNullColumns() {
        User user = h2.db.insert(h2.user("toSelect", User.Status.ACTIVE, 30, "5.00", "note", 0));
        user.setAge(40);
        user.setName(null); // null must NOT overwrite the stored name
        user.setBalance(null);
        h2.db.updateSelective(user);

        User reloaded = h2.db.queryById(User.class, user.getId());
        assertEquals(40, reloaded.getAge());
        assertEquals("toSelect", reloaded.getName());
        assertEquals(new BigDecimal("5.00"), reloaded.getBalance());
    }

    @Test
    void deleteRemovesRowAndDetectsStalePrimaryKey() {
        User user = h2.db.insert(h2.user("toDelete", User.Status.ACTIVE, 10, null, null, 0));
        h2.db.delete(user);
        assertNull(h2.db.queryById(User.class, user.getId()));

        User ghost = h2.user("ghost", User.Status.ACTIVE, 1, null, null, 0);
        ghost.setId(999_999L);
        assertThrows(UnexpectedRowsException.class, () -> h2.db.delete(ghost));
    }

    @Test
    void deleteByIdIsIdempotent() {
        User user = h2.db.insert(h2.user("byId", User.Status.ACTIVE, 10, null, null, 0));
        h2.db.deleteById(User.class, user.getId());
        assertNull(h2.db.queryById(User.class, user.getId()));
        // second call affects 0 rows and must not throw
        h2.db.deleteById(User.class, user.getId());
    }

    @Test
    void countAppliesLogicFilter() {
        h2.db.insert(h2.user("cnt-alive", User.Status.ACTIVE, 1, null, null, 0));
        h2.db.insert(h2.user("cnt-dead", User.Status.ACTIVE, 1, null, null, 1)); // deleted
        assertEquals(1, h2.db.queryable(User.class)
                .startsWith(User::getName, "cnt-").count());
    }

    @Test
    void queryByIdRespectsLogicFilter() {
        User user = h2.db.insert(h2.user("gone", User.Status.ACTIVE, 5, null, null, 1));
        assertNull(h2.db.queryById(User.class, user.getId()));
    }
}
