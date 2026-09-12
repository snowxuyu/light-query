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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T3 — generated SQL snapshots (dialect-quoted, no database involved). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlSnapshotTest {

    private TestDb h2;
    private LightQuerySession db;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("snapshot");
        db = LightQuery.primary(h2.dataSource).dialect(new MySqlDialect());
    }

    @Test
    void simpleWhereWithLogicDeleteFilter() {
        String sql = db.queryable(User.class)
                .col(User::getName).eq("frank")
                .toSql();
        assertEquals("SELECT * FROM `t_user` WHERE `user_name` = ? AND `deleted` = ?"
                + " | params=[frank, 0]", sql);
    }

    @Test
    void nullEqualsBecomesIsNull() {
        String sql = db.queryable(User.class)
                .col(User::getRemark).eq(null)
                .toSql();
        assertEquals("SELECT * FROM `t_user` WHERE `remark` IS NULL AND `deleted` = ?"
                + " | params=[0]", sql);
    }

    @Test
    void orGroupsAndOrChains() {
        // MP-style: and()/or() choose the connector of the parenthesised group;
        // inside a group the default connector is AND and .or() switches the next one
        String sql = db.queryable(User.class)
                .col(User::getStatus).eq(User.Status.ACTIVE)
                .or(w -> w.col(User::getName).like("f").col(User::getAge).ge(18))
                .and(w -> w.col(User::getName).like("x").or().col(User::getAge).ge(21))
                .toSql();
        assertEquals("SELECT * FROM `t_user` WHERE `status` = ?"
                + " OR (`user_name` LIKE ? AND `age` >= ?)"
                + " AND (`user_name` LIKE ? OR `age` >= ?)"
                + " AND `deleted` = ? | params=[ACTIVE, %f%, 18, %x%, 21, 0]", sql);
    }

    @Test
    void emptyInMatchesNothingAndNotEmptyInMatchesAll() {
        String sql = db.queryable(Order.class)
                .col(Order::getStatus).in(List.of())
                .toSql();
        assertEquals("SELECT * FROM `t_order` WHERE `status` IN (1 = 0) | params=[]", sql);

        String notIn = db.queryable(Order.class)
                .col(Order::getStatus).notIn(List.of())
                .toSql();
        assertEquals("SELECT * FROM `t_order` WHERE `status` NOT IN (1 = 1) | params=[]", notIn);
    }

    @Test
    void inWithValues() {
        String sql = db.queryable(Order.class)
                .col(Order::getUserId).in(1L, 2L, 3L)
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
                .select(Order::getStatus).select(Aggregations.count().as("cnt"))
                .groupBy(Order::getStatus)
                .having(w -> w.gt(Aggregations.count(), 2))
                .orderByDesc("cnt")
                .toSql();
        assertEquals("SELECT `status`, count(*) AS `cnt` FROM `t_order`"
                + " GROUP BY `status` HAVING count(*) > ?"
                + " ORDER BY `cnt` DESC | params=[2]", sql);
    }

    @Test
    void joinWithAliases() {
        String sql = db.queryable(User.class)
                .leftJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getStatus).eq(User.Status.ACTIVE)
                .col(Order::getAmount).gt(new BigDecimal("100"))
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
                .col(User::getId).in(db.queryable(Order.class)
                        .select(Order::getUserId)
                        .col(Order::getAmount).gt(new BigDecimal("100")))
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
                        .col(Order::getUserId).eqColumn(User::getId)
                        .col(Order::getAmount).ge(new BigDecimal("50")))
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
                .col(User::getId).eq(1L)
                .forUpdate()
                .toSql();
        assertEquals("SELECT * FROM `t_user_202401` WHERE `id` = ? AND `deleted` = ?"
                + " FOR UPDATE | params=[1, 0]", sql);
    }

    @Test
    void postgresqlQuotingAndLikeEscape() {
        LightQuerySession pg = LightQuery.primary(h2.dataSource).dialect(new PostgreSqlDialect());
        String sql = pg.queryable(User.class)
                .col(User::getName).like("a%b")
                .toSql();
        assertEquals("SELECT * FROM \"t_user\" WHERE \"user_name\" LIKE ? ESCAPE '\\'"
                + " AND \"deleted\" = ? | params=[%a\\%b%, 0]", sql);
    }

    @Test
    void countIgnoresOrderAndPagination() {
        String sql = db.queryable(User.class)
                .col(User::getStatus).eq(User.Status.ACTIVE)
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
                .having(w -> w.ge(Aggregations.sum(Order::getAmount), new BigDecimal("10")))
                .orderByAsc(Aggregations.count(Order::getId))
                .toSql();
        assertEquals("SELECT `user_id` FROM `t_order`"
                + " GROUP BY `user_id` HAVING sum(`amount`) >= ?"
                + " ORDER BY count(`id`) ASC | params=[10]", sql);
    }
}
