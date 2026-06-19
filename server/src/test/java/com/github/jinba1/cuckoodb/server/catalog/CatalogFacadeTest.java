package com.github.jinba1.cuckoodb.server.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.server.catalog.CatalogFacade.RegistrationOutcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the catalog seam, focused on the atomic table-count cap. {@code DBCatalog} is a
 * static singleton, so each test resets it; the concurrency test proves the cap is a hard ceiling,
 * not a TOCTOU soft cap that concurrent uploads can overshoot.
 */
class CatalogFacadeTest {

    @TempDir
    Path tmp;

    private final CatalogFacade catalog = new CatalogFacade();

    @BeforeEach
    void freshEmptyCatalog() {
        DBCatalog.resetDBCatalog();
        DBCatalog.getInstance(); // empty, unloaded catalog — no data dir scan
    }

    @AfterEach
    void clearCatalog() {
        DBCatalog.resetDBCatalog();
    }

    private Path csv(String name) throws IOException {
        Path p = tmp.resolve(name + ".csv");
        Files.writeString(p, "x\n1\n");
        return p;
    }

    @Test
    void registersWhenUnderCap() throws IOException {
        assertEquals(RegistrationOutcome.REGISTERED, catalog.registerIfUnderCap("A", csv("A"), 5));
        assertTrue(catalog.exists("A"));
        assertEquals(1, catalog.tableCount());
    }

    @Test
    void reportsNameTakenOnDuplicate() throws IOException {
        assertEquals(RegistrationOutcome.REGISTERED, catalog.registerIfUnderCap("Dup", csv("Dup"), 5));
        assertEquals(RegistrationOutcome.NAME_TAKEN, catalog.registerIfUnderCap("Dup", csv("Dup2"), 5));
        assertEquals(1, catalog.tableCount());
    }

    @Test
    void refusesAtCapWithoutRegistering() throws IOException {
        assertEquals(RegistrationOutcome.REGISTERED, catalog.registerIfUnderCap("One", csv("One"), 1));
        assertEquals(RegistrationOutcome.OVER_CAP, catalog.registerIfUnderCap("Two", csv("Two"), 1));
        assertFalse(catalog.exists("Two"));
        assertEquals(1, catalog.tableCount());
    }

    @Test
    void concurrentUploadsNeverExceedTheCap() throws Exception {
        int cap = 5;
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger registered = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    Path p = tmp.resolve("T" + id + ".csv");
                    Files.writeString(p, "x\n1\n");
                    if (catalog.registerIfUnderCap("T" + id, p, cap) == RegistrationOutcome.REGISTERED) {
                        registered.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // Hard ceiling: distinct-named concurrent winners can never push the count past the cap.
        assertEquals(cap, catalog.tableCount(), "cap must be a hard ceiling");
        assertEquals(cap, registered.get(), "exactly cap registrations should report REGISTERED");
    }
}
