package com.lightquery;

import com.lightquery.entity.SequencedBad;
import com.lightquery.entity.SequencedInvoice;
import com.lightquery.exception.MappingException;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.meta.EntityMetaCache;
import com.lightquery.sqlgen.H2Dialect;
import com.lightquery.sqlgen.MySqlDialect;
import com.lightquery.sqlgen.PostgreSqlDialect;

import org.h2.jdbcx.JdbcDataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T13 — SEQUENCE key generation: nextval is fetched first and written with the INSERT. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SequenceH2Test {

    private JdbcDataSource dataSource;
    private LightQuerySession db;

    @BeforeAll
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:seqtest;DB_CLOSE_DELAY=-1");
        db = LightQuery.of(dataSource);
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement()) {
            st.execute("DROP SEQUENCE IF EXISTS \"invoice_seq\"");
            st.execute("CREATE SEQUENCE \"invoice_seq\" START WITH 100 INCREMENT BY 1");
            st.execute("DROP TABLE IF EXISTS \"t_sequenced_invoice\"");
            st.execute("""
                    CREATE TABLE "t_sequenced_invoice" (
                        "id" BIGINT PRIMARY KEY,
                        "ref" VARCHAR(100)
                    )""");
        } catch (Exception e) {
            throw new IllegalStateException("cannot prepare H2 schema", e);
        }
    }

    @Test
    void insertFetchesNextvalAndWritesTheId() {
        SequencedInvoice first = db.insert(new SequencedInvoice("inv-1"));
        assertTrue(first.getId() >= 100, "sequence starts at 100, got " + first.getId());
        assertEquals("inv-1", db.queryById(SequencedInvoice.class, first.getId()).getRef());

        SequencedInvoice second = db.insert(new SequencedInvoice("inv-2"));
        assertEquals(first.getId() + 1, second.getId());
    }

    @Test
    void insertBatchFetchesAValuePerEntity() {
        List<SequencedInvoice> batch = db.insertBatch(List.of(
                new SequencedInvoice("batch-1"),
                new SequencedInvoice("batch-2"),
                new SequencedInvoice("batch-3")));
        assertEquals(3, batch.size());
        batch.forEach(invoice -> assertEquals(invoice.getRef(),
                db.queryById(SequencedInvoice.class, invoice.getId()).getRef()));
        batch.stream().map(SequencedInvoice::getId).distinct().count();
        assertEquals(3, batch.stream().map(SequencedInvoice::getId).distinct().count());
    }

    @Test
    void dialectWithoutSequencesIsRejected() {
        LightQuerySession mysql = LightQuery.of(dataSource, new MySqlDialect());
        SqlBuildException e = assertThrows(SqlBuildException.class,
                () -> mysql.insert(new SequencedInvoice("no-seq")));
        assertTrue(e.getMessage().contains("does not support sequences"), e.getMessage());
    }

    @Test
    void sequenceSqlShapes() {
        assertEquals("SELECT NEXT VALUE FOR \"invoice_seq\"",
                new H2Dialect().sequenceNextValueSql("invoice_seq"));
        assertEquals("SELECT nextval('invoice_seq')",
                new PostgreSqlDialect().sequenceNextValueSql("invoice_seq"));
        assertFalse(new MySqlDialect().supportsSequences());
        assertTrue(new H2Dialect().supportsSequences());
    }

    @Test
    void missingGeneratorFailsAtStartup() {
        MappingException e = assertThrows(MappingException.class,
                () -> EntityMetaCache.of(SequencedBad.class));
        assertTrue(e.getMessage().contains("@SequenceGenerator"), e.getMessage());
    }
}
