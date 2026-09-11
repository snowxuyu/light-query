package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.LightQueryException;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.sqlgen.MySqlDialect;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 — static facade and multi-datasource semantics: the primary data source
 * is the default target of every static call, {@code datasource(...)} names a
 * data source at the call site, transactions stay per data source and the
 * registry validates its input.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiDataSourceH2Test {

    private TestDb primary;
    private TestDb report;

    @BeforeAll
    void setUp() {
        primary = new TestDb("fmds_primary");
        report = new TestDb("fmds_report");
        LightQuery.reset();
        LightQuery.primary(primary.dataSource);
        LightQuery.datasource("report", report.dataSource);
        LightQuery.datasource("reportMySql", report.dataSource, new MySqlDialect());
    }

    @AfterAll
    void tearDown() {
        LightQuery.reset();
    }

    /** Re-establishes the canonical registry after a test reset it. */
    private void restoreRegistry() {
        LightQuery.reset();
        LightQuery.primary(primary.dataSource);
        LightQuery.datasource("report", report.dataSource);
        LightQuery.datasource("reportMySql", report.dataSource, new MySqlDialect());
    }

    @Test
    void defaultOperationsTargetPrimary() {
        LightQuery.insert(primary.user("mdsDefault", User.Status.ACTIVE, 1, null, null, 0));
        assertEquals(1, primary.db.queryable(User.class).col(User::getName).eq("mdsDefault").count());
        assertEquals(0, report.db.queryable(User.class).col(User::getName).eq("mdsDefault").count());
    }

    @Test
    void datasourceSwitchesToNamedDatasource() {
        LightQuery.datasource("report").insert(report.user("mdsSwitched", User.Status.ACTIVE, 1, null, null, 0));
        assertEquals(1, report.db.queryable(User.class).col(User::getName).eq("mdsSwitched").count());
        assertEquals(0, primary.db.queryable(User.class).col(User::getName).eq("mdsSwitched").count());
    }

    @Test
    void datasourceGetOrRegisterSupportsInlineUsage() {
        // the one-liner call-site form: name + datasource, then chain the query
        LightQuery.datasource("inline", report.dataSource)
                .insert(report.user("mdsInline", User.Status.ACTIVE, 1, null, null, 0));
        // same name + same DataSource resolves to the same cached session
        assertSame(LightQuery.datasource("inline"), LightQuery.datasource("inline", report.dataSource));
        assertEquals(1, LightQuery.datasource("inline").queryable(User.class)
                .col(User::getName).eq("mdsInline").count());
        assertEquals(0, primary.db.queryable(User.class).col(User::getName).eq("mdsInline").count());
    }

    @Test
    void unknownNameFailsWithGuidance() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () -> LightQuery.datasource("reprot"));
        assertTrue(e.getMessage().contains("reprot"), e.getMessage());
        assertTrue(e.getMessage().contains("report"), e.getMessage());
        assertTrue(e.getMessage().contains("primary"), e.getMessage());
    }

    @Test
    void queryableWithoutPrimaryFailsWithGuidance() {
        LightQuery.reset();
        try {
            LightQueryException e = assertThrows(LightQueryException.class,
                    () -> LightQuery.queryable(User.class));
            assertTrue(e.getMessage().contains("primary"), e.getMessage());
        } finally {
            restoreRegistry();
        }
    }

    @Test
    void primaryReregistrationConflict() {
        LightQuery.reset();
        try {
            LightQuery.primary(primary.dataSource);
            assertThrows(IllegalStateException.class, () -> LightQuery.primary(report.dataSource));
        } finally {
            restoreRegistry();
        }
    }

    @Test
    void datasourceConflictOnDifferentInstance() {
        LightQuerySession before = LightQuery.datasource("conflict", report.dataSource);
        assertThrows(IllegalStateException.class,
                () -> LightQuery.datasource("conflict", primary.dataSource));
        // the failed registration did not replace the original binding
        assertSame(before, LightQuery.datasource("conflict"));
    }

    @Test
    void datasourceRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> LightQuery.datasource(" ", report.dataSource));
    }

    @Test
    void namedDatasourceUsesItsOwnDialect() {
        String primarySql = LightQuery.queryable(User.class).toSql();
        String switchedSql = LightQuery.datasource("reportMySql").queryable(User.class).toSql();
        assertTrue(primarySql.contains("\"t_user\""), primarySql);
        assertTrue(switchedSql.contains("`t_user`"), switchedSql);
        assertFalse(switchedSql.contains("\"t_user\""), switchedSql);
    }

    @Test
    void switchedDatasourceRunsItsOwnTransactions() {
        assertThrows(IllegalStateException.class, () -> LightQuery.datasource("report").inTransaction(tx -> {
            tx.insert(report.user("mdsTxRollback", User.Status.ACTIVE, 1, null, null, 0));
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, report.db.queryable(User.class).col(User::getName).eq("mdsTxRollback").count());

        LightQuery.datasource("report").inTransaction(tx -> {
            tx.insert(report.user("mdsTxCommit", User.Status.ACTIVE, 1, null, null, 0));
            return null;
        });
        assertEquals(1, report.db.queryable(User.class).col(User::getName).eq("mdsTxCommit").count());
    }

    @Test
    void statementsViaFacadeInsidePrimaryTransactionRunOutsideIt() {
        assertThrows(IllegalStateException.class, () -> LightQuery.inTransaction(tx -> {
            tx.insert(primary.user("mdsInTx", User.Status.ACTIVE, 1, null, null, 0));
            // the named datasource runs on its own connections, outside the transaction
            LightQuery.datasource("report")
                    .insert(report.user("mdsCrossDs", User.Status.ACTIVE, 1, null, null, 0));
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, primary.db.queryable(User.class).col(User::getName).eq("mdsInTx").count());
        assertEquals(1, report.db.queryable(User.class).col(User::getName).eq("mdsCrossDs").count());
    }

    @Test
    void ofCreatesStandaloneSessionWithoutRegistering() {
        LightQuerySession session = LightQuery.of(report.dataSource);
        session.insert(report.user("mdsStandalone", User.Status.ACTIVE, 1, null, null, 0));
        assertEquals(1, report.db.queryable(User.class).col(User::getName).eq("mdsStandalone").count());
        assertEquals(0, primary.db.queryable(User.class).col(User::getName).eq("mdsStandalone").count());
    }
}
