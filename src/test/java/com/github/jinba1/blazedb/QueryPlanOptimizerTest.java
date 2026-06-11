package com.github.jinba1.blazedb;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * This extension of BlazeDBTest focuses on testing query plan optimizations.
 * It adds new test methods specifically designed to verify the behavior of
 * QueryPlanOptimizer in removing unnecessary operators.
 * Has to run BlazseDBTest first!!!
 */
public class QueryPlanOptimizerTest extends BlazeDBTest {

    /**
     * Tests the removal of a trivial SELECT with an always-true condition.
     */
    @Test
    public void testRemoveUnnecessarySelect() throws IOException {
        String queryName = "test_unnecessary_select";
        String queryContent = "SELECT * FROM Student WHERE 1 = 1;";

        // This should be equivalent to a simple "SELECT * FROM Student"
        String expectedOutput =
                "a,b,c,d\n" +
                        "1,25,85,30\n" +
                        "2,30,22,40\n" +
                        "3,35,19,20\n" +
                        "4,40,21,40\n" +
                        "5,45,65,30\n" +
                        "6,50,32,10\n";

        runTest(queryName, queryContent, expectedOutput);

        // Verify correct output - this is already done by runTest
        // The key here is that the optimization was applied (would be visible in console output)
    }

    /**
     * Tests the removal of a trivial PROJECT that doesn't eliminate any columns.
     */
    @Test
    public void testRemoveUnnecessaryProject() throws IOException {
        String queryName = "test_unnecessary_project";
        // This query explicitly selects all columns in the original order
        String queryContent = "SELECT Student.A, Student.B, Student.C, Student.D FROM Student;";

        // Should be equivalent to "SELECT * FROM Student"
        String expectedOutput =
                "a,b,c,d\n" +
                        "1,25,85,30\n" +
                        "2,30,22,40\n" +
                        "3,35,19,20\n" +
                        "4,40,21,40\n" +
                        "5,45,65,30\n" +
                        "6,50,32,10\n";

        runTest(queryName, queryContent, expectedOutput);
    }

    /**
     * Tests combining consecutive SELECT operators.
     */
    @Test
    public void testCombineConsecutiveSelects() throws IOException {
        String queryName = "test_combine_selects";
        // In the current implementation, a WHERE clause with AND would be parsed
        // into a single SelectOperator. To test combining consecutive SelectOperators,
        // we need to verify that queries with complex WHERE clauses still work.
        String queryContent = "SELECT Student.A, Student.B FROM Student WHERE Student.D > 30 AND Student.C < 50;";

        String expectedOutput =
                "a,b\n" +
                        "2,30\n" +
                        "4,40\n";

        runTest(queryName, queryContent, expectedOutput);

        // This test verifies functionality rather than the specific optimization,
        // since we can't directly create two SelectOperators in the test
    }

    /**
     * Tests pushing selection before projection.
     */
    @Test
    public void testPushSelectionBeforeProjection() throws IOException {
        String queryName = "test_selection_projection_reordering";
        // This query has a selection that can be pushed before projection
        String queryContent = "SELECT Student.A, Student.B FROM Student WHERE Student.A > 3;";

        String expectedOutput =
                "a,b\n" +
                        "4,40\n" +
                        "5,45\n" +
                        "6,50\n";

        runTest(queryName, queryContent, expectedOutput);
    }

    /**
     * Tests pushing selection into a join's inner child.
     */
    @Test
    public void testPushSelectionToJoinInnerChild() throws IOException {
        String queryName = "test_selection_to_inner_join";
        // Selection on inner table (Course) can be pushed down
        String queryContent = "SELECT Student.A, Course.E FROM Student, Course " +
                "WHERE Student.A = Course.E AND Course.G > 3;";

        // Expected: Students with A = Course.E and Course.G > 3
        // From our test data, courses 104 and 106 have G > 3
        String expectedOutput =
                "a,e\n";

        runTest(queryName, queryContent, expectedOutput);
    }

    /**
     * Tests pushing selection into a join's outer child.
     */
    @Test
    public void testPushSelectionToJoinOuterChild() throws IOException {
        String queryName = "test_selection_to_outer_join";
        // Selection on outer table (Student) can be pushed down
        String queryContent = "SELECT Student.A, Course.E FROM Student, Course " +
                "WHERE Student.A = Course.E AND Student.D > 30;";

        // Expected: Students with D > 30 joined with Courses where E = Student.A
        String expectedOutput =
                "a,e\n";

        runTest(queryName, queryContent, expectedOutput);
    }

    /**
     * Tests combined optimizations with a complex query.
     */
    @Test
    public void testCombinedOptimizations() throws IOException {
        String queryName = "test_combined_optimizations";
        // This query has multiple optimization opportunities:
        // 1. "1 = 1" is a trivial selection
        // 2. Student.D > 20 can be pushed down to the Student table
        // 3. The ordered columns are the same as original schema order
        String queryContent = "SELECT Student.A, Student.B, Student.C, Student.D FROM Student " +
                "WHERE 1 = 1 AND Student.D > 20;";

        String expectedOutput =
                "a,b,c,d\n" +
                        "1,25,85,30\n" +
                        "2,30,22,40\n" +
                        "4,40,21,40\n" +
                        "5,45,65,30\n";

        runTest(queryName, queryContent, expectedOutput);
    }

    /**
     * Tests optimization with multiple joins and conditions.
     */
    @Test
    public void testMultiJoinOptimizations() throws IOException {
        String queryName = "test_multi_join_optimizations";
        // A complex query with joins and conditions that can be pushed down
        String queryContent = "SELECT Student.A, Course.E, Enrolled.K " +
                "FROM Student, Enrolled, Course " +
                "WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E " +
                "AND Student.D > 30 AND Course.G > 3;";

        // Expected: Complex join with optimized selection placement
        // The exact output depends on the test data
        String expectedOutput =
                "a,e,k\n" +
                        "4,104,65\n";

        runTest(queryName, queryContent, expectedOutput, false); // Order might vary
    }

    /**
     * Tests that the optimizer respects the left-deep join tree requirement.
     */
    @Test
    public void testPreserveLeftDeepJoinTree() throws IOException {
        String queryName = "test_preserve_join_order";
        // This query includes multiple tables and conditions
        String queryContent = "SELECT Student.A, Enrolled.J, Course.E " +
                "FROM Student, Enrolled, Course " +
                "WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E;";

        // If join order is preserved, we should get correct results
        // The exact output depends on the test data
        String expectedOutput =
                "a,j,e\n" +
                        "1,101,101\n" +
                        "1,102,102\n" +
                        "2,101,101\n" +
                        "2,103,103\n" +
                        "3,102,102\n" +
                        "4,104,104\n";

        runTest(queryName, queryContent, expectedOutput, false); // Order might vary
    }

    /**
     * Tests optimizing a query with GROUP BY and SUM aggregate.
     */
    @Test
    public void testOptimizationWithGroupByAndSum() throws IOException {
        String queryName = "test_optimize_group_by";
        // Includes a trivial selection (1 = 1) that should be removed
        String queryContent = "SELECT Student.D, SUM(Student.C) FROM Student " +
                "WHERE 1 = 1 GROUP BY Student.D;";

        String expectedOutput =
                "d,sum(student.c)\n" +
                        "20,19\n" +
                        "40,43\n" +
                        "10,32\n" +
                        "30,150\n";

        runTest(queryName, queryContent, expectedOutput, false); // Order might vary
    }

    /**
     * Tests optimization with DISTINCT.
     */
    @Test
    public void testOptimizationWithDistinct() throws IOException {
        String queryName = "test_optimize_distinct";
        // Includes an unnecessary projection of all columns followed by DISTINCT
        String queryContent = "SELECT DISTINCT Student.A, Student.B, Student.C, Student.D FROM Student;";

        String expectedOutput =
                "a,b,c,d\n" +
                        "1,25,85,30\n" +
                        "2,30,22,40\n" +
                        "3,35,19,20\n" +
                        "4,40,21,40\n" +
                        "5,45,65,30\n" +
                        "6,50,32,10\n";

        runTest(queryName, queryContent, expectedOutput, false); // Order might vary
    }

    /**
     * Tests optimization with ORDER BY.
     */
    @Test
    public void testOptimizationWithOrderBy() throws IOException {
        String queryName = "test_optimize_order_by";
        // Includes a trivial selection that should be optimized away
        String queryContent = "SELECT Student.A, Student.D FROM Student " +
                "WHERE 1 = 1 ORDER BY Student.D;";

        String expectedOutput =
                "a,d\n" +
                        "6,10\n" +
                        "3,20\n" +
                        "1,30\n" +
                        "5,30\n" +
                        "2,40\n" +
                        "4,40\n";

        runTest(queryName, queryContent, expectedOutput);
    }
}
