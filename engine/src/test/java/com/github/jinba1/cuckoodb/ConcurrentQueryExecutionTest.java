package com.github.jinba1.cuckoodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves per-query isolation: with the schema registry on PlanContext and a
 * thread-safe durable catalog, N threads can plan and execute different queries
 * concurrently against one catalog. This test fails (or flakes with map races /
 * wrong results) when per-query schema state is global.
 */
public class ConcurrentQueryExecutionTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 5;

    @TempDir
    Path tempOut;

    @BeforeEach
    public void setUp() {
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog("samples/db");
    }

    @Test
    public void concurrentQueriesProduceCorrectResults() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int threadId = t;
                futures.add(pool.submit((Callable<String>) () -> {
                    for (int iter = 0; iter < ITERATIONS; iter++) {
                        int queryNum = ((threadId + iter * THREADS) % 20) + 1; // 1..20
                        String failure = runOne(threadId, iter, queryNum);
                        if (failure != null) {
                            return failure;
                        }
                    }
                    return null;
                }));
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "queries timed out");
            List<String> failures = new ArrayList<>();
            for (Future<String> f : futures) {
                String failure = f.get();
                if (failure != null) {
                    failures.add(failure);
                }
            }
            assertTrue(failures.isEmpty(), "concurrent query failures:\n" + String.join("\n", failures));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs one sample query on this thread; returns null on success, message on mismatch. */
    private String runOne(int threadId, int iter, int queryNum) {
        try {
            String queryFile = "samples/input/query" + queryNum + ".sql";
            PlannedQuery planned = QueryPlanner.planQuery(queryFile, QueryConfig.defaults());
            if (planned.explainText() != null) {
                return planned.explainText().isBlank()
                        ? "query" + queryNum + ": empty explain text" : null;
            }
            if (planned.root() == null) {
                return "query" + queryNum + ": planning failed";
            }
            Path out = tempOut.resolve("t" + threadId + "_i" + iter + "_q" + queryNum + ".csv");
            CuckooDB.execute(planned.root(), out.toString());

            Path expected = Path.of("samples/expected_output/query" + queryNum + ".csv");
            String actualText = normalize(Files.readString(out));
            String expectedText = normalize(Files.readString(expected));
            if (!actualText.equals(expectedText)) {
                return "query" + queryNum + " (thread " + threadId + ", iter " + iter
                        + "): output differs from expected";
            }
            return null;
        } catch (Exception e) {
            return "query" + queryNum + " (thread " + threadId + ", iter " + iter
                    + "): " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Mirrors the normalization used by {@link SampleQueryRunner}: normalize line
     * endings to {@code \n}, strip trailing whitespace per line, and remove any
     * trailing blank lines. This prevents spurious mismatches caused purely by
     * line-ending or trailing-newline conventions.
     */
    private static String normalize(String content) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            if (i > 0) sb.append("\n");
            sb.append(line);
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
