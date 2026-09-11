package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.sqlgen.Dialects;
import com.lightquery.sqlgen.MySqlDialect;
import com.lightquery.sqlgen.OracleDialect;
import com.lightquery.sqlgen.SqlServerDialect;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T19 — Oracle / SQL Server dialect SQL shapes (snapshot assertions). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DialectShapeTest {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("dialectshape");
    }

    @Test
    void oraclePaginationAndQuoting() {
        String sql = h2.db.dialect(new OracleDialect())
                .queryable(User.class)
                .col(User::getStatus).eq(User.Status.ACTIVE)
                .orderByAsc(User::getId)
                .limit(20)
                .toSql();
        assertTrue(sql.contains("OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY"), sql);
        assertTrue(sql.contains("FROM \"t_user\""), sql);
    }

    @Test
    void oracleSequenceUsesDual() {
        assertEquals("SELECT \"invoice_seq\".NEXTVAL FROM DUAL",
                new OracleDialect().sequenceNextValueSql("invoice_seq"));
        assertTrue(new OracleDialect().supportsSequences());
    }

    @Test
    void sqlServerPaginationAppendsNeutralOrderWhenMissing() {
        String sql = h2.db.dialect(new SqlServerDialect())
                .queryable(User.class)
                .limit(20)
                .toSql();
        assertTrue(sql.contains("ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY"), sql);
    }

    @Test
    void sqlServerPaginationKeepsUserOrderBy() {
        String sql = h2.db.dialect(new SqlServerDialect())
                .queryable(User.class)
                .orderByDesc(User::getAge)
                .limit(20)
                .toSql();
        assertTrue(sql.contains("ORDER BY [age] DESC OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY"), sql);
        assertTrue(sql.contains("FROM [t_user]"), sql);
    }

    @Test
    void sqlServerEscapesLikeAndSupportsSequences() {
        String sql = h2.db.dialect(new SqlServerDialect())
                .queryable(User.class)
                .strCol(User::getName).like("frank")
                .toSql();
        assertTrue(sql.contains("ESCAPE '\\'"), sql);
        assertEquals("SELECT NEXT VALUE FOR [invoice_seq]",
                new SqlServerDialect().sequenceNextValueSql("invoice_seq"));
        assertTrue(new SqlServerDialect().supportsSequences());
    }

    @Test
    void jdbcUrlsResolveNewDialects() {
        assertInstanceOf(OracleDialect.class,
                Dialects.fromJdbcUrl("jdbc:oracle:thin:@host:1521:orcl"));
        assertInstanceOf(SqlServerDialect.class,
                Dialects.fromJdbcUrl("jdbc:sqlserver://host:1433;databaseName=db"));
        assertInstanceOf(MySqlDialect.class, Dialects.fromJdbcUrl("jdbc:mysql://host/db"));
    }
}
