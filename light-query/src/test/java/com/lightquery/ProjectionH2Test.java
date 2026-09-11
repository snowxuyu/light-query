package com.lightquery;

import com.lightquery.entity.Order;
import com.lightquery.entity.User;
import com.lightquery.exception.MappingException;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T17 — VO/record projection: map projected rows onto arbitrary types. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectionH2Test {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("projection");
        h2.db.insertBatch(List.of(
                h2.user("alice", User.Status.ACTIVE, 30, "100", "vp", 0),
                h2.user("bob", User.Status.FROZEN, 40, "200", null, 0),
                h2.user("carol", User.Status.ACTIVE, 50, "300", null, 0)));
        h2.db.insertBatch(List.of(
                h2.order(1L, "10", 1),
                h2.order(1L, "20", 1),
                h2.order(2L, "40", 1)));
    }

    record UserRow(String userName, Integer age) {
    }

    record FullUserRow(Long id, String userName, User.Status status, BigDecimal balance) {
    }

    record OrderStat(Long userId, Long orderCount, BigDecimal totalAmount) {
    }

    /** POJO projection target (no-arg constructor + setters). */
    public static class UserNameVo {
        private String userName;

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getUserName() {
            return userName;
        }
    }

    @Test
    void fullRowProjectsToRecordByName() {
        List<UserRow> rows = h2.db.queryable(User.class)
                .orderByAsc(User::getName)
                .toList(UserRow.class);
        assertEquals(3, rows.size());
        assertEquals("alice", rows.get(0).userName());
        assertEquals(30, rows.get(0).age());
    }

    @Test
    void recordComponentsCoerceEnumsAndNumbers() {
        List<FullUserRow> rows = h2.db.queryable(User.class)
                .col(User::getName).eq("alice")
                .toList(FullUserRow.class);
        assertEquals(1, rows.size());
        assertEquals(User.Status.ACTIVE, rows.get(0).status());
        assertEquals(new BigDecimal("100.00"), rows.get(0).balance());
    }

    @Test
    void aggregateAliasesProjectOntoRecord() {
        List<OrderStat> rows = h2.db.queryable(Order.class)
                .select(Order::getUserId)
                .select(Aggregations.count().as("orderCount"),
                        Aggregations.sum(Order::getAmount).as("totalAmount"))
                .groupBy(Order::getUserId)
                .orderByAsc(Order::getUserId)
                .toList(OrderStat.class);
        assertEquals(2, rows.size());
        assertEquals(1L, rows.get(0).userId());
        assertEquals(2L, rows.get(0).orderCount());
        assertEquals(new BigDecimal("30.00"), rows.get(0).totalAmount());
        assertEquals(new BigDecimal("40.00"), rows.get(1).totalAmount());
    }

    @Test
    void pojoProjectionUsesSetters() {
        List<UserNameVo> rows = h2.db.queryable(User.class)
                .select(User::getName)
                .orderByDesc(User::getName)
                .toList(UserNameVo.class);
        assertEquals(3, rows.size());
        assertEquals("carol", rows.get(0).getUserName());
    }

    @Test
    void projectedPageResultKeepsTotals() {
        PageResult<UserRow> page = h2.db.queryable(User.class)
                .orderByAsc(User::getId)
                .toPageResult(1, 2, UserRow.class);
        assertEquals(3, page.total());
        assertEquals(2, page.rows().size());
        assertEquals(2, page.pages());
    }

    @Test
    void missingComponentColumnFailsWithLabelList() {
        MappingException e = assertThrows(MappingException.class, () -> h2.db.queryable(User.class)
                .select(User::getName)
                .toList(FullUserRow.class));
        assertTrue(e.getMessage().contains("id"), e.getMessage());
        assertTrue(e.getMessage().contains("user_name"), e.getMessage());
    }
}
