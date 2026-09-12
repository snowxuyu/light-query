package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T18 — keyset (seek) pagination: stable, offset-free deep paging. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeekPaginationH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("seek");
        for (int i = 1; i <= 7; i++) {
            LightQuery.insert(h2.user("seek" + i, i % 2 == 0 ? User.Status.FROZEN : User.Status.ACTIVE,
                    20 + i, null, null, 0));
        }
    }

    @Test
    void keysetWalkCoversAllRowsWithoutOverlap() {
        List<Long> seen = new ArrayList<>();
        Long lastId = null;
        while (true) {
            var query = LightQuery.queryable(User.class).orderByAsc(User::getId).limit(3);
            if (lastId != null) {
                query.seekAfter(lastId);
            }
            List<User> rows = query.toList();
            for (User user : rows) {
                seen.add(user.getId());
            }
            if (rows.size() < 3) {
                break;
            }
            lastId = rows.get(rows.size() - 1).getId();
        }
        assertEquals(7, seen.size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), seen);
    }

    @Test
    void keysetWalkMatchesFullOrderingForMixedDirections() {
        List<String> expected = LightQuery.queryable(User.class)
                .orderByAsc(User::getStatus)
                .orderByDesc(User::getAge)
                .toList()
                .stream().map(User::getName).toList();

        List<String> seen = new ArrayList<>();
        User.Status lastStatus = null;
        Integer lastAge = null;
        while (seen.size() < 7) {
            var query = LightQuery.queryable(User.class)
                    .orderByAsc(User::getStatus)
                    .orderByDesc(User::getAge)
                    .limit(3);
            if (lastStatus != null) {
                query.seekAfter(lastStatus, lastAge);
            }
            List<User> rows = query.toList();
            if (rows.isEmpty()) {
                break;
            }
            for (User user : rows) {
                seen.add(user.getName());
            }
            User lastRow = rows.get(rows.size() - 1);
            lastStatus = lastRow.getStatus();
            lastAge = lastRow.getAge();
        }
        assertEquals(expected, seen);
    }

    @Test
    void seekCombinesWithUserConditions() {
        List<Long> seen = new ArrayList<>();
        Integer lastAge = null;
        while (true) {
            var query = LightQuery.queryable(User.class)
                    .col(User::getStatus).eq(User.Status.ACTIVE)
                    .orderByAsc(User::getAge)
                    .limit(2);
            if (lastAge != null) {
                query.seekAfter(lastAge);
            }
            List<User> rows = query.toList();
            for (User user : rows) {
                seen.add(user.getId());
            }
            if (rows.size() < 2) {
                break;
            }
            lastAge = rows.get(rows.size() - 1).getAge();
        }
        assertEquals(4, seen.size());
    }

    @Test
    void seekWithoutOrderByIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class,
                () -> LightQuery.queryable(User.class).seekAfter(1L));
        assertTrue(e.getMessage().contains("orderBy"), e.getMessage());
    }

    @Test
    void seekValueCountMismatchIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () -> LightQuery.queryable(User.class)
                .orderByAsc(User::getId)
                .seekAfter(1L, 2L));
        assertTrue(e.getMessage().contains("expects 1 value(s)"), e.getMessage());
    }

    @Test
    void seekNullValueIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () -> LightQuery.queryable(User.class)
                .orderByAsc(User::getId)
                .seekAfter((Comparable<?>) null));
        assertTrue(e.getMessage().contains("must not be null"), e.getMessage());
    }
}
