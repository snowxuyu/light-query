package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.support.TestDb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T10 — shared LightQuery under concurrency (smoke level). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencySmokeTest {

    private TestDb h2;

    @BeforeAll
    void setUp() {
        h2 = new TestDb("concurrent");
        h2.db.insertBatch(List.of(
                h2.user("u1", User.Status.ACTIVE, 1, null, null, 0),
                h2.user("u2", User.Status.FROZEN, 2, null, null, 0)));
    }

    @Test
    void sharedDbHandlesParallelQueries() throws Exception {
        int threads = 8;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successes = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        long total = h2.db.queryable(User.class).count();
                        if (total < 2) {
                            throw new AssertionError("row went missing under concurrency");
                        }
                        h2.db.insert(h2.user("t" + Thread.currentThread().getId() + "-" + j,
                                User.Status.ACTIVE, 3, null, null, 0));
                        successes.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    throw e;
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "tasks timed out");
        assertEquals(threads * iterations, successes.get());
    }
}
