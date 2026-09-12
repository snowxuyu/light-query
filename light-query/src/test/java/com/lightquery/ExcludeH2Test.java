package com.lightquery;

import com.lightquery.entity.Order;
import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T24 — exclude(): query all columns except the specified ones. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExcludeH2Test {

    @Test
    void excludeRendersExplicitColumns() {
        TestDb h2 = new TestDb("exclude");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        LightQuery.insert(h2.user("ex-user", User.Status.ACTIVE, 30, "100", "note", 0));

        String sql = LightQuery.queryable(User.class)
                .exclude(User::getBalance, User::getRemark)
                .toSql();
        // SELECT * becomes explicit column list minus excluded columns
        assertTrue(sql.contains("SELECT "), sql);
        assertTrue(!sql.contains("*"), sql);
        assertTrue(sql.contains("user_name"), sql);
        assertTrue(!sql.contains("balance"), sql);
        assertTrue(!sql.contains("remark"), sql);
    }

    @Test
    void excludeWorksOnJoinQueries() {
        TestDb h2 = new TestDb("exclude_join");
        LightQuery.reset();
        LightQuery.primary(h2.dataSource);
        String sql = LightQuery.queryable(User.class)
                .leftJoin(com.lightquery.entity.Order.class,
                        on -> on.col(User::getId).eqColumn(Order::getUserId))
                .exclude(User::getBalance)
                .toSql();
        assertTrue(sql.contains("t_user"), sql);
        assertTrue(!sql.contains("balance"), sql);
    }
}
