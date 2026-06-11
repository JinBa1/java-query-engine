package com.github.jinba1.blazedb;

import com.github.jinba1.blazedb.operator.Operator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmark tests that measure tuple processing reduction
 * achieved by query plan optimization.
 *
 * These tests run the same queries with and without optimization enabled,
 * counting tuples processed by each operator to demonstrate the impact
 * of selection pushdown, projection pushdown, and trivial operator removal.
 */
public class QueryOptimizationBenchmarkTest extends BlazeDBTest {

    private static final String TEST_DB_DIR = "src/test/resources/test_integration_db";
    private static final String TEST_QUERIES_DIR = "src/test/resources/test_integration_queries";
    private static final String TEST_OUTPUT_DIR = "src/test/resources/test_integration_output";

    @BeforeEach
    public void setUp() throws IOException {
        super.setUp();
    }

    @AfterEach
    public void tearDown() throws IOException {
        super.tearDown();
    }

    /**
     * Benchmark: Selection pushdown on a single table.
     * Query filters Student table where D > 30 (only 2 out of 6 rows match).
     * With optimization: Scan reads 6 tuples, Select processes 2 tuples.
     * Without optimization: Same (no pushdown possible on single table).
     */
    @Test
    public void benchmarkSelectionPushdownSingleTable() throws IOException {
        String queryName = "bench_select_single_table";
        String queryContent = "SELECT Student.A, Student.B FROM Student WHERE Student.D > 30;";

        // Run with optimization
        long optimizedCount = runQueryWithTupleCount(queryName + "_opt", queryContent, true);

        // Run without optimization
        long unoptimizedCount = runQueryWithTupleCount(queryName + "_unopt", queryContent, false);

        System.out.println("=== Single Table Selection Pushdown ===");
        System.out.println("Optimized tuples:   " + optimizedCount);
        System.out.println("Unoptimized tuples: " + unoptimizedCount);
        System.out.println("Reduction:          " + calculateReduction(optimizedCount, unoptimizedCount) + "%");

        // Both should produce same results (no pushdown possible on single table)
        assertEquals(optimizedCount, unoptimizedCount,
                "Single table selection should have same tuple count with/without optimization");
    }

    /**
     * Benchmark: Selection pushdown through join.
     * Query joins Student and Enrolled, with selective condition on Student (D > 30).
     * With optimization: Selection pushed to Student scan, reducing join input.
     * Without optimization: Full Student table joined, then filtered.
     */
    @Test
    public void benchmarkSelectionPushdownJoin() throws IOException {
        String queryName = "bench_select_pushdown_join";
        String queryContent = "SELECT Student.A, Enrolled.J, Enrolled.K " +
                "FROM Student, Enrolled " +
                "WHERE Student.A = Enrolled.I AND Student.D > 30;";

        // Run with optimization
        long optimizedCount = runQueryWithTupleCount(queryName + "_opt", queryContent, true);

        // Run without optimization
        long unoptimizedCount = runQueryWithTupleCount(queryName + "_unopt", queryContent, false);

        System.out.println("\n=== Join Selection Pushdown ===");
        System.out.println("Optimized tuples:   " + optimizedCount);
        System.out.println("Unoptimized tuples: " + unoptimizedCount);
        System.out.println("Reduction:          " + calculateReduction(optimizedCount, unoptimizedCount) + "%");

        // Optimized should process fewer tuples
        assertTrue(optimizedCount <= unoptimizedCount,
                "Optimized plan should process fewer or equal tuples");
    }

    /**
     * Benchmark: Projection pushdown.
     * Query selects only 2 columns from a 4-column table.
     * With optimization: Project pushed to scan, reducing data movement.
     */
    @Test
    public void benchmarkProjectionPushdown() throws IOException {
        String queryName = "bench_projection_pushdown";
        String queryContent = "SELECT Student.A, Student.D FROM Student;";

        // Run with optimization
        long optimizedCount = runQueryWithTupleCount(queryName + "_opt", queryContent, true);

        // Run without optimization
        long unoptimizedCount = runQueryWithTupleCount(queryName + "_unopt", queryContent, false);

        System.out.println("\n=== Projection Pushdown ===");
        System.out.println("Optimized tuples:   " + optimizedCount);
        System.out.println("Unoptimized tuples: " + unoptimizedCount);
        System.out.println("Reduction:          " + calculateReduction(optimizedCount, unoptimizedCount) + "%");
    }

    /**
     * Benchmark: Complex join with multiple selective conditions.
     * Three-way join with conditions on both Student and Course.
     * With optimization: Both selections pushed to their respective tables.
     */
    @Test
    public void benchmarkComplexJoinOptimization() throws IOException {
        String queryName = "bench_complex_join";
        String queryContent = "SELECT Student.A, Course.F, Enrolled.K " +
                "FROM Student, Enrolled, Course " +
                "WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E " +
                "AND Student.D > 30 AND Course.G > 3;";

        // Run with optimization
        long optimizedCount = runQueryWithTupleCount(queryName + "_opt", queryContent, true);

        // Run without optimization
        long unoptimizedCount = runQueryWithTupleCount(queryName + "_unopt", queryContent, false);

        System.out.println("\n=== Complex Join Optimization ===");
        System.out.println("Optimized tuples:   " + optimizedCount);
        System.out.println("Unoptimized tuples: " + unoptimizedCount);
        System.out.println("Reduction:          " + calculateReduction(optimizedCount, unoptimizedCount) + "%");

        // Significant reduction expected
        assertTrue(optimizedCount < unoptimizedCount,
                "Optimized plan should process significantly fewer tuples for complex join");
    }

    /**
     * Benchmark: Trivial operator removal.
     * Query with always-true condition (1 = 1).
     * With optimization: Trivial SelectOperator removed entirely.
     */
    @Test
    public void benchmarkTrivialOperatorRemoval() throws IOException {
        String queryName = "bench_trivial_removal";
        String queryContent = "SELECT * FROM Student WHERE 1 = 1;";

        // Run with optimization
        long optimizedCount = runQueryWithTupleCount(queryName + "_opt", queryContent, true);

        // Run without optimization
        long unoptimizedCount = runQueryWithTupleCount(queryName + "_unopt", queryContent, false);

        System.out.println("\n=== Trivial Operator Removal ===");
        System.out.println("Optimized tuples:   " + optimizedCount);
        System.out.println("Unoptimized tuples: " + unoptimizedCount);
        System.out.println("Reduction:          " + calculateReduction(optimizedCount, unoptimizedCount) + "%");

        // Optimized should have fewer operator overhead (though same tuples processed)
        assertTrue(optimizedCount <= unoptimizedCount,
                "Optimized plan should process fewer or equal tuples");
    }

    /**
     * Benchmark: Combined optimizations on a realistic query.
     * Join with selection, projection, and aggregation.
     */
    @Test
    public void benchmarkCombinedOptimizations() throws IOException {
        String queryName = "bench_combined";
        String queryContent = "SELECT Student.D, SUM(Enrolled.K) " +
                "FROM Student, Enrolled " +
                "WHERE Student.A = Enrolled.I AND Student.D > 20 " +
                "GROUP BY Student.D;";

        // Run with optimization
        long optimizedCount = runQueryWithTupleCount(queryName + "_opt", queryContent, true);

        // Run without optimization
        long unoptimizedCount = runQueryWithTupleCount(queryName + "_unopt", queryContent, false);

        System.out.println("\n=== Combined Optimizations ===");
        System.out.println("Optimized tuples:   " + optimizedCount);
        System.out.println("Unoptimized tuples: " + unoptimizedCount);
        System.out.println("Reduction:          " + calculateReduction(optimizedCount, unoptimizedCount) + "%");

        assertTrue(optimizedCount <= unoptimizedCount,
                "Optimized plan should process fewer or equal tuples");
    }

    /**
     * Runs a query and returns the total tuple count processed by all operators.
     */
    private long runQueryWithTupleCount(String queryName, String queryContent, boolean optimize) throws IOException {
        boolean savedOptimization = Constants.useQueryOptimization;
        boolean savedHashJoin = Constants.useHashJoin;
        try {
            // Nested-loop join on both paths: this benchmark isolates the optimizer's
            // effect on scan counts; join-algorithm gains are measured by the JMH suite
            Constants.useQueryOptimization = optimize;
            Constants.useHashJoin = false;

            // Reset catalog
            DBCatalog.resetDBCatalog();
            DBCatalog.initDBCatalog(TEST_DB_DIR);

            // Create query file
            String queryFilePath = Paths.get(TEST_QUERIES_DIR, queryName + ".sql").toString();
            Files.write(Paths.get(queryFilePath), queryContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Parse and execute
            Operator rootOp = QueryPlanner.parseStatement(queryFilePath);

            // Reset tuple counters before execution
            resetAllTupleCounters(rootOp);

            // Execute query (write to output to consume all tuples)
            String outputFilePath = Paths.get(TEST_OUTPUT_DIR, queryName + ".csv").toString();
            BlazeDB.execute(rootOp, outputFilePath);

            // Collect scan tuple count (measures actual data read from disk)
            long scanCount = collectScanTupleCount(rootOp);

            return scanCount;
        } finally {
            Constants.useQueryOptimization = savedOptimization;
            Constants.useHashJoin = savedHashJoin;
        }
    }

    /**
     * Recursively resets tuple counters for all operators in the tree.
     */
    private void resetAllTupleCounters(Operator op) {
        if (op == null) return;

        op.resetTupleCount();

        if (op.hasChild()) {
            resetAllTupleCounters(op.getChild());
        }

        if (op instanceof com.github.jinba1.blazedb.operator.JoinOperator) {
            resetAllTupleCounters(((com.github.jinba1.blazedb.operator.JoinOperator) op).getOuterChild());
        }
    }

    /**
     * Recursively collects total tuple count from SCAN operators only.
     * This measures actual data read from disk, which is what optimization reduces.
     */
    private long collectScanTupleCount(Operator op) {
        if (op == null) return 0;

        long count = 0;

        // Only count tuples from ScanOperators (leaf nodes that read from disk)
        if (op instanceof com.github.jinba1.blazedb.operator.ScanOperator) {
            count = op.getTupleCount();
        }

        if (op.hasChild()) {
            count += collectScanTupleCount(op.getChild());
        }

        if (op instanceof com.github.jinba1.blazedb.operator.JoinOperator) {
            count += collectScanTupleCount(((com.github.jinba1.blazedb.operator.JoinOperator) op).getOuterChild());
        }

        return count;
    }

    /**
     * Calculates percentage reduction from unoptimized to optimized.
     */
    private double calculateReduction(long optimized, long unoptimized) {
        if (unoptimized == 0) return 0.0;
        return ((double)(unoptimized - optimized) / unoptimized) * 100.0;
    }
}
