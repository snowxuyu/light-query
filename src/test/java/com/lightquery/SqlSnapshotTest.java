package com.lightquery;

import com.lightquery.entity.Order;
import com.lightquery.entity.User;
import com.lightquery.sqlgen.MySqlDialect;
import com.lightquery.sqlgen.PostgreSqlDialect;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T3 — generated SQL snapshots (dialect-quoted, no database involved). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlSnapshotTest {

    private TestDb h2;
    private LightQuery db;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("snapshot");
        db = h2.db.dialect(new MySqlDialect());
    }

    @Test
    void simpleWhereWithLogicDeleteFilter() {
        String sql = db.queryable(User.class)
                .eq(User::getName, "frank")
                .toSql();
        assertEquals("SELECT * FROM `t_user` WHERE `user_name` = ? AND `deleted` = ?"
                + " | params=[frank, 0]", sql);
    }

    @Test
    void nullEqualsBecomesIsNull() {
        String sql = db.queryable(User.class)
                .eq(User::getRemark, null)
                .toSql();
        assertEquals("SELECT * FROM `t_user` WHERE `remark` IS NULL AND `deleted` = ?"
                + " | params=[0]", sql);
    }

    @Test
    void orGroupsAndOrChains() {
        // MP-style: and()/or() choose the connector of the parenthesised group;
        // inside a group the default connector is AND and .or() switches the next one
        String sql = db.queryable(User.class)
                .eq(User::getStatus, User.Status.ACTIVE)
                .or(w -> w.like(User::getName, "f").ge(User::getAge, 18))
                .and(w -> w.like(User::getName, "x").or().ge(User::getAge, 21))
                .toSql();
        assertEquals("SELECT * FROM `t_user` WHERE `status` = ?"
                + " OR (`user_name` LIKE ? AND `age` >= ?)"
                + " AND (`user_name` LIKE ? OR `age` >= ?)"
                + " AND `deleted` = ? | params=[ACTIVE, %f%, 18, %x%, 21, 0]", sql);
    }

    @Test
    void emptyInMatchesNothingAndNotEmptyInMatchesAll() {
        String sql = db.queryable(Order.class)
                .in(Order::getStatus, List.of())
                .toSql();
        assertEquals("SELECT * FROM `t_order` WHERE `status` IN (1 = 0) | params=[]", sql);

        String notIn = db.queryable(Order.class)
                .notIn(Order::getStatus, List.of())
                .toSql();
        assertEquals("SELECT * FROM `t_order` WHERE `status` NOT IN (1 = 1) | params=[]", notIn);
    }

    @Test
    void inWithValues() {
        String sql = db.queryable(Order.class)
                .in(Order::getUserId, 1L, 2L, 3L)
                .toSql();
        assertEquals("SELECT * FROM `t_order` WHERE `user_id` IN (?, ?, ?)"
                + " | params=[1, 2, 3]", sql);
    }

    @Test
    void orderByLimitOffset() {
        String sql = db.queryable(User.class)
                .orderByDesc(User::getCreatedAt, User::getId)
                .limit(10)
                .offset(20)
                .toSql();
        assertEquals("SELECT * FROM `t_user`"
                + " WHERE `deleted` = ?"
                + " ORDER BY `created_at` DESC, `id` DESC"
                + " LIMIT 10 OFFSET 20 | params=[0]", sql);
    }

    @Test
    void projectionAndDistinct() {
        String sql = db.queryable(User.class)
                .select(User::getName, User::getAge)
                .distinct()
                .toSql();
        assertEquals("SELECT DISTINCT `user_name`, `age` FROM `t_user`"
                + " WHERE `deleted` = ? | params=[0]", sql);
    }

    @Test
    void groupByHavingOrderByAlias() {
        String sql = db.queryable(Order.class)
                .select(Order::getStatus).select(F.count().as("cnt"))
                .groupBy(Order::getStatus)
                .having(w -> w.gt(F.count(), 2))
                .orderByDesc("cnt")
                .toSql();
        assertEquals("SELECT `status`, count(*) AS `cnt` FROM `t_order`"
                + " GROUP BY `status` HAVING count(*) > ?"
                + " ORDER BY `cnt` DESC | params=[2]", sql);
    }

    @Test
    void joinWithAliases() {
        String sql = db.queryable(User.class)
                .leftJoin(Order.class, on -> on.eq(User::getId, Order::getUserId))
                .eq(User::getStatus, User.Status.ACTIVE)
                .gt(Order::getAmount, new BigDecimal("100"))
                .toSql();
        assertEquals("SELECT `t0`.`id`, `t0`.`user_name`, `t0`.`status`, `t0`.`age`,"
                + " `t0`.`balance`, `t0`.`created_at`, `t0`.`remark`, `t0`.`deleted`"
                + " FROM `t_user` `t0` LEFT JOIN `t_order` `t1`"
                + " ON `t0`.`id` = `t1`.`user_id`"
                + " WHERE `t0`.`status` = ? AND `t1`.`amount` > ? AND `t0`.`deleted` = ?"
                + " | params=[ACTIVE, 100, 0]", sql);
    }

    @Test
    void inSubQuery() {
        String sql = db.queryable(User.class)
                .in(User::getId, db.queryable(Order.class)
                        .select(Order::getUserId)
                        .gt(Order::getAmount, new BigDecimal("100")))
                .toSql();
        assertEquals("SELECT * FROM `t_user` `t0`"
                + " WHERE `t0`.`id` IN (SELECT `s1_0`.`user_id` FROM `t_order` `s1_0`"
                + " WHERE `s1_0`.`amount` > ?)"
                + " AND `t0`.`deleted` = ? | params=[100, 0]", sql);
    }

    @Test
    void correlatedExists() {
        String sql = db.queryable(User.class)
                .whereExists(db.queryable(Order.class)
                        .eqColumn(Order::getUserId, User::getId)
                        .ge(Order::getAmount, new BigDecimal("50")))
                .toSql();
        assertEquals("SELECT * FROM `t_user` `t0`"
                + " WHERE EXISTS (SELECT * FROM `t_order` `s1_0`"
                + " WHERE `s1_0`.`user_id` = `t0`.`id` AND `s1_0`.`amount` >= ?)"
                + " AND `t0`.`deleted` = ? | params=[50, 0]", sql);
    }

    @Test
    void forUpdateAndDynamicTable() {
        String sql = db.queryable(User.class)
                .asTable("t_user_202401")
                .eq(User::getId, 1L)
                .forUpdate()
                .toSql();
        assertEquals("SELECT * FROM `t_user_202401` WHERE `id` = ? AND `deleted` = ?"
                + " FOR UPDATE | params=[1, 0]", sql);
    }

    @Test
    void postgresqlQuotingAndLikeEscape() {
        LightQuery pg = h2.db.dialect(new PostgreSqlDialect());
        String sql = pg.queryable(User.class)
                .like(User::getName, "a%b")
                .toSql();
        assertEquals("SELECT * FROM \"t_user\" WHERE \"user_name\" LIKE ? ESCAPE '\\'"
                + " AND \"deleted\" = ? | params=[%a\\%b%, 0]", sql);
    }

    @Test
    void countIgnoresOrderAndPagination() {
        String sql = db.queryable(User.class)
                .eq(User::getStatus, User.Status.ACTIVE)
                .orderByDesc(User::getId)
                .limit(5)
                .toSql();
        assertEquals("SELECT * FROM `t_user` WHERE `status` = ? AND `deleted` = ?"
                + " ORDER BY `id` DESC LIMIT 5 OFFSET 0 | params=[ACTIVE, 0]", sql);
    }

    @Test
    void groupedCountUsesSubQuery() {
        String sql = db.queryable(Order.class)
                .select(Order::getUserId)
                .groupBy(Order::getUserId)
                .having(w -> w.ge(F.sum(Order::getAmount), new BigDecimal("10")))
                .orderByAsc(F.count(Order::getId))
                .toSql();
        assertEquals("SELECT `user_id` FROM `t_order`"
                + " GROUP BY `user_id` HAVING sum(`amount`) >= ?"
                + " ORDER BY count(`id`) ASC | params=[10]", sql);
    }
}
