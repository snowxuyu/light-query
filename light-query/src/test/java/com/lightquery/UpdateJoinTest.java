package com.lightquery;

import com.lightquery.entity.Order;
import com.lightquery.entity.User;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.sqlgen.MySqlDialect;
import com.lightquery.sqlgen.OracleDialect;
import com.lightquery.sqlgen.PostgreSqlDialect;
import com.lightquery.sqlgen.SqlServerDialect;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T20 — update join / delete join: per-dialect SQL shapes (validated via
 * toSql) plus the guard rails. Behaviour cannot be integration-tested on H2
 * because H2 has no multi-table UPDATE/DELETE syntax.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateJoinTest {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("updatejoin");
    }

    private static final BigDecimal BIG_AMOUNT = new BigDecimal("100");

    @Test
    void mysqlUpdateJoinShape() {
        String sql = LightQuery.primary(h2.dataSource).dialect(new MySqlDialect())
                .updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getStatus).set(User.Status.FROZEN)
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql();
        assertEquals("UPDATE `t_user` `t0`, `t_order` `t1` "
                        + "SET `t0`.`status` = ? "
                        + "WHERE `t1`.`amount` > ? AND `t0`.`deleted` = ? "
                        + "AND `t0`.`id` = `t1`.`user_id`"
                        + " | params=[FROZEN, 100, 0]",
                sql);
    }

    @Test
    void postgresUpdateJoinShape() {
        String sql = LightQuery.primary(h2.dataSource).dialect(new PostgreSqlDialect())
                .updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getStatus).set(User.Status.FROZEN)
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql();
        assertEquals("UPDATE \"t_user\" \"t0\" SET \"status\" = ? FROM \"t_order\" \"t1\" "
                        + "WHERE \"t1\".\"amount\" > ? AND \"t0\".\"deleted\" = ? "
                        + "AND \"t0\".\"id\" = \"t1\".\"user_id\""
                        + " | params=[FROZEN, 100, 0]",
                sql);
    }

    @Test
    void sqlServerUpdateJoinShape() {
        String sql = LightQuery.primary(h2.dataSource).dialect(new SqlServerDialect())
                .updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getStatus).set(User.Status.FROZEN)
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql();
        assertEquals("UPDATE [t0] SET [t0].[status] = ? FROM [t_user] [t0], [t_order] [t1] "
                        + "WHERE [t1].[amount] > ? AND [t0].[deleted] = ? "
                        + "AND [t0].[id] = [t1].[user_id]"
                        + " | params=[FROZEN, 100, 0]",
                sql);
    }

    @Test
    void incrementIsQualifiedInJoinUpdates() {
        String sql = LightQuery.primary(h2.dataSource).dialect(new MySqlDialect())
                .updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getAge).setIncrement(1)
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql();
        assertTrue(sql.contains("SET `t0`.`age` = `t0`.`age` + ?"), sql);
    }

    @Test
    void logicDeleteJoinBecomesUpdateWithJoin() {
        String sql = LightQuery.primary(h2.dataSource).dialect(new MySqlDialect())
                .deletable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql();
        assertEquals("UPDATE `t_user` `t0`, `t_order` `t1` "
                        + "SET `t0`.`deleted` = ? "
                        + "WHERE `t1`.`amount` > ? AND `t0`.`id` = `t1`.`user_id`"
                        + " | params=[1, 100]",
                sql);
    }

    @Test
    void physicalDeleteJoinShapes() {
        String mysql = LightQuery.primary(h2.dataSource).dialect(new MySqlDialect())
                .deletable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .physical()
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql();
        assertEquals("DELETE `t0` FROM `t_user` `t0`, `t_order` `t1` "
                        + "WHERE `t1`.`amount` > ? AND `t0`.`id` = `t1`.`user_id`"
                        + " | params=[100]",
                mysql);

        String pg = LightQuery.primary(h2.dataSource).dialect(new PostgreSqlDialect())
                .deletable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .physical()
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql();
        assertEquals("DELETE FROM \"t_user\" \"t0\" USING \"t_order\" \"t1\" "
                        + "WHERE \"t1\".\"amount\" > ? AND \"t0\".\"id\" = \"t1\".\"user_id\""
                        + " | params=[100]",
                pg);
    }

    @Test
    void h2DoesNotSupportJoinWrites() {
        SqlBuildException updateError = assertThrows(SqlBuildException.class, () -> LightQuery.updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getStatus).set(User.Status.FROZEN)
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .execute());
        assertTrue(updateError.getMessage().contains("does not support UPDATE with JOIN"),
                updateError.getMessage());

        SqlBuildException deleteError = assertThrows(SqlBuildException.class, () -> LightQuery.deletable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .physical()
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .execute());
        assertTrue(deleteError.getMessage().contains("does not support DELETE with JOIN"),
                deleteError.getMessage());
    }

    @Test
    void oracleDoesNotSupportJoinWrites() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () -> LightQuery.primary(h2.dataSource).dialect(new OracleDialect())
                .updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(User::getStatus).set(User.Status.FROZEN)
                .col(Order::getAmount).gt(BIG_AMOUNT)
                .toSql());
        assertTrue(e.getMessage().contains("does not support UPDATE with JOIN"), e.getMessage());
    }

    @Test
    void setOnJoinedEntityIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () -> LightQuery.updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .col(Order::getAmount).set(BIG_AMOUNT));
        assertTrue(e.getMessage().contains("modifies the updated entity only"), e.getMessage());
    }

    @Test
    void conditionOnUnjoinedEntityIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () -> LightQuery.updatable(User.class)
                .col(Order::getAmount).eq(BIG_AMOUNT));
        assertTrue(e.getMessage().contains("is not part of this update"), e.getMessage());
    }

    @Test
    void joiningTheSameEntityTwiceIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () -> LightQuery.updatable(User.class)
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
                .join(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId)));
        assertTrue(e.getMessage().contains("already part of this query"), e.getMessage());
    }
}
