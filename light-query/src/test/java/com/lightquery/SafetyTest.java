package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T9 — misuse protection and injection safety. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SafetyTest {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("safety");
        LightQuery.insert(h2.user("safe", User.Status.ACTIVE, 1, null, null, 0));
    }

    @Test
    void updateWithoutConditionsIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class,
                () -> LightQuery.updatable(User.class).col(User::getAge).set(1).execute());
        assertTrue(e.getMessage().contains("allowFullTable"));
    }

    @Test
    void deleteWithoutConditionsIsRejected() {
        assertThrows(SqlBuildException.class,
                () -> LightQuery.deletable(User.class).execute());
    }

    @Test
    void allowFullTableIsTheExplicitEscapeHatch() {
        int rows = LightQuery.updatable(User.class)
                .col(User::getRemark).set("bulk")
                .allowFullTable()
                .execute();
        assertTrue(rows >= 1);
    }

    @Test
    void primaryKeysCannotBeModifiedViaSet() {
        assertThrows(SqlBuildException.class,
                () -> LightQuery.updatable(User.class).col(User::getId).set(1L).allowFullTable().execute());
    }

    @Test
    void consumedQueryableIsRejected() {
        var query = LightQuery.queryable(User.class);
        query.count();
        assertThrows(SqlBuildException.class, query::count);
    }

    @Test
    void updateEntityWithoutSetIsRejected() {
        assertThrows(SqlBuildException.class,
                () -> LightQuery.updatable(User.class).allowFullTable().execute());
    }

    @Test
    void injectionPayloadsStayBound() {
        String payload = "x'; DROP TABLE t_user; --";
        LightQuery.insert(h2.user(payload, User.Status.ACTIVE, 1, null, null, 0));
        // value matched literally, table still intact
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).eq(payload).count());
        assertTrue(LightQuery.queryable(User.class).count() > 0);
    }

    @Test
    void likeWildcardsInPayloadCannotWidenPattern() {
        String payload = "100%_match";
        LightQuery.insert(h2.user(payload, User.Status.ACTIVE, 2, null, null, 0));
        assertEquals(1, LightQuery.queryable(User.class).col(User::getName).like("100%_match").count());
    }

    @Test
    void invalidPageParametersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LightQuery.queryable(User.class).toPageResult(0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> LightQuery.queryable(User.class).toPageResult(1, 0));
    }

    @Test
    void unknownPropertyIsRejected() {
        assertThrows(com.lightquery.exception.MappingException.class,
                () -> LightQuery.queryable(User.class).col(User::getIgnored).in(List.of("x")));
    }
}
