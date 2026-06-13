package com.github.jinba1.cuckoodb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Integration tests for CuckooDB.
 * Tests the program as a whole using the command-line interface.
 *
 * These tests cover all the query types supported by CuckooDB as per the project requirements:
 * - Simple SELECT-FROM-WHERE queries
 * - Joins
 * - DISTINCT
 * - ORDER BY
 * - GROUP BY
 * - SUM aggregates
 * - Various combinations of the above
 *
 * Note: All column names in the test database are single letters as per the specification,
 * and all attribute values are integers. The schema is as follows:
 *
 * Student: A B C D (where A is student ID, B is name code, C is age, D is GPA*10)
 * Course: E F G H (where E is course ID, F is course name code, G is credits, H is department ID)
 * Enrolled: I J K (where I is student ID, J is course ID, K is grade)
 * Department: L M N (where L is department ID, M is department name code, N is building code)
 * Faculty: O P Q (where O is faculty ID, P is name code, Q is department ID)
 */
public class CuckooDBTest {

	@org.junit.jupiter.api.io.TempDir
	Path tempDb;

	private void writeTable(String name, String... lines) throws java.io.IOException {
		Path data = tempDb.resolve("data");
		Files.createDirectories(data);
		Files.write(data.resolve(name + ".csv"), List.of(lines));
	}

	@Test
	public void outputHasHeaderAndRfcCommas() throws Exception {
		writeTable("People", "id,name", "1,alice", "2,\"smith, bob\"");
		Path query = tempDb.resolve("q.sql");
		Files.writeString(query, "SELECT * FROM People;");
		Path out = tempDb.resolve("out.csv");
		CuckooDB.main(new String[]{tempDb.toString(), query.toString(), out.toString()});
		List<String> lines = Files.readAllLines(out);
		assertEquals(List.of("id,name", "1,alice", "2,\"smith, bob\""), lines);
	}

	private static final String TEST_DB_DIR = "src/test/resources/test_integration_db";
	private static final String TEST_QUERIES_DIR = "src/test/resources/test_integration_queries";
	private static final String TEST_OUTPUT_DIR = "src/test/resources/test_integration_output";
	private static final String EXPECTED_OUTPUT_DIR = "src/test/resources/test_integration_expected";

	@BeforeEach
	public void setUp() throws IOException {
		// Create directories if they don't exist
		Files.createDirectories(Paths.get(TEST_DB_DIR));
		Files.createDirectories(Paths.get(TEST_DB_DIR, "data"));
		Files.createDirectories(Paths.get(TEST_QUERIES_DIR));
		Files.createDirectories(Paths.get(TEST_OUTPUT_DIR));
		Files.createDirectories(Paths.get(EXPECTED_OUTPUT_DIR));

		// Create data files (schema is inferred from the CSV header rows)
		createStudentData();
		createCourseData();
		createEnrolledData();
		createDepartmentData();
		createFacultyData();
	}

	@AfterEach
	public void tearDown() throws IOException {
		// Delete test directories and their contents
		// (uncomment if you want cleanup)
        /*
        deleteDirectory(Paths.get(TEST_DB_DIR));
        deleteDirectory(Paths.get(TEST_QUERIES_DIR));
        deleteDirectory(Paths.get(TEST_OUTPUT_DIR));
        deleteDirectory(Paths.get(EXPECTED_OUTPUT_DIR));
        */
	}

	/**
	 * Test a simple SELECT * query with no WHERE clause.
	 */
	@Test
	public void testSimpleSelectAll() throws IOException {
		String queryName = "test_simple_select_all";
		String queryContent = "SELECT * FROM Student;";

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
	 * Test a simple SELECT with specific columns.
	 */
	@Test
	public void testSimpleSelectColumns() throws IOException {
		String queryName = "test_simple_select_columns";
		String queryContent = "SELECT Student.A, Student.D FROM Student;";

		String expectedOutput =
				"a,d\n" +
						"1,30\n" +
						"2,40\n" +
						"3,20\n" +
						"4,40\n" +
						"5,30\n" +
						"6,10\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a SELECT with a WHERE clause.
	 */
	@Test
	public void testSelectWithWhere() throws IOException {
		String queryName = "test_select_with_where";
		String queryContent = "SELECT Student.A, Student.B, Student.D FROM Student WHERE Student.D > 30;";

		String expectedOutput =
				"a,b,d\n" +
						"2,30,40\n" +
						"4,40,40\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a SELECT with a complex WHERE clause using AND.
	 */
	@Test
	public void testSelectWithComplexWhere() throws IOException {
		String queryName = "test_select_with_complex_where";
		String queryContent = "SELECT Student.A, Student.C FROM Student WHERE Student.D >= 30 AND Student.C < 50;";

		String expectedOutput =
				"a,c\n" +
						"2,22\n" +
						"4,21\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a simple join between two tables.
	 */
	@Test
	public void testSimpleJoin() throws IOException {
		String queryName = "test_simple_join";
		String queryContent = "SELECT Student.A, Student.B, Enrolled.J, Enrolled.K FROM Student, Enrolled WHERE Student.A = Enrolled.I;";

		String expectedOutput =
				"a,b,j,k\n" +
						"1,25,101,85\n" +
						"1,25,102,92\n" +
						"2,30,101,91\n" +
						"2,30,103,84\n" +
						"3,35,102,78\n" +
						"4,40,104,65\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a join between three tables.
	 */
	@Test
	public void testThreeWayJoin() throws IOException {
		String queryName = "test_three_way_join";
		String queryContent = "SELECT Student.A, Student.B, Course.F, Enrolled.K FROM Student, Enrolled, Course WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E;";

		String expectedOutput =
				"a,b,f,k\n" +
						"1,25,201,85\n" +
						"1,25,202,92\n" +
						"2,30,201,91\n" +
						"2,30,203,84\n" +
						"3,35,202,78\n" +
						"4,40,204,65\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	@Test
	public void testComplexJoinCondition() throws IOException {
		String queryName = "test_complex_join_condition";

		// This query uses a multi-table join condition that would produce results
		String queryContent = "SELECT Student.A, Student.B, Course.F, Enrolled.K " +
				"FROM Student, Enrolled, Course " +
				"WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E " +
				"AND Student.D = 40 AND Course.H = 3;";

		// Expected: Students with D=40 (Student 2 and 4) joined with Courses with H=3 (Course 103, 104)
		// But only where the Enrolled table connects them
		// Student 2 is enrolled in Course 103 (matching H=3)
		// Student 4 is enrolled in Course 104 (matching H=3)
		String expectedOutput =
				"a,b,f,k\n" +
						"2,30,203,84\n" +
						"4,40,204,65\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a join with a complex WHERE clause.
	 */
	@Test
	public void testJoinWithComplexWhere() throws IOException {
		String queryName = "test_join_with_complex_where";
		String queryContent = "SELECT Student.A, Student.B, Course.F, Enrolled.K FROM Student, Enrolled, Course WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E AND Enrolled.K > 80;";

		String expectedOutput =
				"a,b,f,k\n" +
						"1,25,201,85\n" +
						"1,25,202,92\n" +
						"2,30,201,91\n" +
						"2,30,203,84\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a query with DISTINCT.
	 */
	@Test
	public void testSelectDistinct() throws IOException {
		String queryName = "test_select_distinct";
		String queryContent = "SELECT DISTINCT Student.D FROM Student;";

		String expectedOutput =
				"d\n" +
						"30\n" +
						"40\n" +
						"20\n" +
						"10\n";

		runTest(queryName, queryContent, expectedOutput, false); // Order might vary
	}

	/**
	 * Test a query with ORDER BY.
	 */
	@Test
	public void testSelectWithOrderBy() throws IOException {
		String queryName = "test_select_with_order_by";
		String queryContent = "SELECT Student.A, Student.D FROM Student ORDER BY Student.D;";

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

	/**
	 * Test a query with ORDER BY on multiple columns.
	 */
	@Test
	public void testSelectWithMultipleOrderBy() throws IOException {
		String queryName = "test_select_with_multiple_order_by";
		String queryContent = "SELECT Student.A, Student.D, Student.C FROM Student ORDER BY Student.D, Student.C;";

		String expectedOutput =
				"a,d,c\n" +
						"6,10,32\n" +
						"3,20,19\n" +
						"5,30,65\n" +
						"1,30,85\n" +
						"4,40,21\n" +
						"2,40,22\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a query with GROUP BY and SUM aggregation.
	 */
	@Test
	public void testGroupByWithSum() throws IOException {
		String queryName = "test_group_by_with_sum";
		String queryContent = "SELECT Student.D, SUM(Student.C) FROM Student GROUP BY Student.D;";

		String expectedOutput =
				"d,sum(student.c)\n" +
						"20,19\n" +
						"40,43\n" +
						"10,32\n" +
						"30,150\n";

		runTest(queryName, queryContent, expectedOutput, false); // Order might vary
	}

	/**
	 * Test a query with SUM aggregation but no GROUP BY.
	 */
	@Test
	public void testSumWithoutGroupBy() throws IOException {
		String queryName = "test_sum_without_group_by";
		String queryContent = "SELECT SUM(Student.C) FROM Student;";

		String expectedOutput =
				"sum(student.c)\n" +
						"244\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a query with SUM(1) to count rows.
	 */
	@Test
	public void testSumCount() throws IOException {
		String queryName = "test_sum_count";
		String queryContent = "SELECT SUM(1) FROM Student;";

		String expectedOutput =
				"sum(0)\n" +
						"6\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a query with SUM of product.
	 */
	@Test
	public void testSumProduct() throws IOException {
		String queryName = "test_sum_product";
		String queryContent = "SELECT SUM(Student.C*Student.D) FROM Student;";

		// Calculation:
		// (85*30 + 22*40 + 19*20 + 21*40 + 65*30 + 32*10)
		// = 2550 + 880 + 380 + 840 + 1950 + 320 = 6920
		String expectedOutput =
				"sum(0)\n" +
						"6920\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a query with GROUP BY, SUM and ORDER BY together.
	 */
	@Test
	public void testGroupBySumOrderBy() throws IOException {
		String queryName = "test_group_by_sum_order_by";
		String queryContent = "SELECT Student.D, SUM(Student.C) FROM Student GROUP BY Student.D ORDER BY Student.D;";

		String expectedOutput =
				"d,sum(student.c)\n" +
						"10,32\n" +
						"20,19\n" +
						"30,150\n" +
						"40,43\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a query with DISTINCT and ORDER BY together.
	 */
	@Test
	public void testDistinctOrderBy() throws IOException {
		String queryName = "test_distinct_order_by";
		String queryContent = "SELECT DISTINCT Student.D FROM Student ORDER BY Student.D;";

		String expectedOutput =
				"d\n" +
						"10\n" +
						"20\n" +
						"30\n" +
						"40\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test a GROUP BY query with a join operation.
	 */
	@Test
	public void testGroupByWithJoin() throws IOException {
		String queryName = "test_group_by_with_join";
		String queryContent = "SELECT Course.H, SUM(Enrolled.K) FROM Course, Enrolled WHERE Course.E = Enrolled.J GROUP BY Course.H;";

		// The expected output based on the course and enrollment data:
		// Department 1: sum of grades 85+91 = 176
		// Department 2: sum of grades 92+78 = 170
		// Department 3: sum of grades 84+65 = 149
		String expectedOutput =
				"h,sum(enrolled.k)\n" +
						"1,176\n" +
						"2,170\n" +
						"3,149\n";

		runTest(queryName, queryContent, expectedOutput, false); // Order might vary
	}

	@Test
	public void testSelectionPushdownOptimization() throws IOException {
		// This test demonstrates a query that benefits from selection pushdown
		// We use a query that joins Student and Enrolled with selective conditions on both sides

		String queryName = "test_selection_pushdown";
		String queryContent = "SELECT Student.A, Student.B, Enrolled.J, Enrolled.K FROM Student, Enrolled " +
				"WHERE Student.A = Enrolled.I AND Student.D > 30 AND Enrolled.K > 80;";

		// Expected output: Only students with D > 30 (GPA > 3.0) and enrollments with K > 80 (grade > 80)
		// From our test data this should return:
		// Student 1 (D=30) with enrollments to courses 101 (grade 85) and 102 (grade 92)
		// Student 2 (D=40) with enrollments to courses 101 (grade 91) and 103 (grade 84)
		String expectedOutput =
				"a,b,j,k\n" +
						"2,30,101,91\n" +
						"2,30,103,84\n";

		runTest(queryName, queryContent, expectedOutput);

		// Note: In a real-world scenario, we would measure performance metrics here
		// to show the difference between optimized and unoptimized plans.
		// For simplicity and consistency with your existing tests, we just verify correctness.
	}

	@Test
	public void testSelectPushdownAfterJoin() throws IOException {
		String queryName = "test_select_pushdown_after_join";

		// This query uses a legal predicate that references Student and Enrolled
		String queryContent = "SELECT Student.A, Student.B, Course.F, Enrolled.K " +
				"FROM Student, Enrolled, Course " +
				"WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E " +
				"AND Student.D = 40 AND Enrolled.K > 80;";

		// Expected output: Only students with D=40 (Students 2 and 4)
		// AND enrollments with K>80 (matches for Students 1, 2, 3)
		// The intersection is just Student 2 with grades 91 and 84
		String expectedOutput =
				"a,b,f,k\n" +
						"2,30,201,91\n" +
						"2,30,203,84\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test projection pushdown with a simple query that joins two tables
	 * but only needs a subset of columns.
	 */
	@Test
	public void testProjectionPushdown() throws IOException {
		String queryName = "test_projection_pushdown";
		String queryContent = "SELECT Student.A, Enrolled.K FROM Student, Enrolled WHERE Student.A = Enrolled.I;";

		// Expected output has only the two projected columns (Student.A and Enrolled.K)
		String expectedOutput =
				"a,k\n" +
						"1,85\n" +
						"1,92\n" +
						"2,91\n" +
						"2,84\n" +
						"3,78\n" +
						"4,65\n";

		runTest(queryName, queryContent, expectedOutput);

		// Note: We can't directly verify that projection was pushed down in a unit test,
		// but we can check that the correct results are produced, and the optimization
		// would be visible in console output during execution
	}

	/**
	 * Test projection pushdown with a more complex query involving
	 * selection, join, and a subset of columns from multiple tables.
	 */
	@Test
	public void testProjectionPushdownWithSelection() throws IOException {
		String queryName = "test_projection_pushdown_with_selection";
		String queryContent = "SELECT Student.A, Student.C, Enrolled.K FROM Student, Enrolled " +
				"WHERE Student.A = Enrolled.I AND Student.D > 20 AND Enrolled.K > 80;";

		// Expected output has only Student.A, Student.C, Enrolled.K
		// with the filters Student.D > 20 AND Enrolled.K > 80
		String expectedOutput =
				"a,c,k\n" +
						"1,85,85\n" +
						"1,85,92\n" +
						"2,22,91\n" +
						"2,22,84\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test projection pushdown with a query that includes ORDER BY
	 * on a column that is also in the final projection list.
	 */
	@Test
	public void testProjectionPushdownWithOrderBy() throws IOException {
		String queryName = "test_projection_pushdown_with_order_by";
		String queryContent = "SELECT Student.A, Student.D, Enrolled.K FROM Student, Enrolled " +
				"WHERE Student.A = Enrolled.I ORDER BY Student.D;";

		// Now Student.D is in both the SELECT list and ORDER BY clause
		String expectedOutput =
				"a,d,k\n" +
						"3,20,78\n" +
						"1,30,85\n" +
						"1,30,92\n" +
						"2,40,91\n" +
						"2,40,84\n" +
						"4,40,65\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test projection pushdown with GROUP BY and aggregation
	 */
	@Test
	public void testProjectionPushdownWithGroupByAndSum() throws IOException {
		String queryName = "test_projection_pushdown_with_group_by";
		String queryContent = "SELECT Student.D, SUM(Enrolled.K) FROM Student, Enrolled " +
				"WHERE Student.A = Enrolled.I GROUP BY Student.D;";

		// Student.D is in the projection and GROUP BY
		// Student.A is needed for the join condition but not in final result
		// Enrolled.K is needed for SUM, Enrolled.I for the join condition
		String expectedOutput =
				"d,sum(enrolled.k)\n" +
						"20,78\n" +
						"40,240\n" +
						"30,177\n";

		runTest(queryName, queryContent, expectedOutput, false); // Order might vary
	}

	/**
	 * Test projection pushdown with a three-way join
	 * where many columns can be eliminated early
	 */
	@Test
	public void testProjectionPushdownWithThreeWayJoin() throws IOException {
		String queryName = "test_projection_pushdown_with_three_way_join";
		String queryContent = "SELECT Student.A, Course.F FROM Student, Enrolled, Course " +
				"WHERE Student.A = Enrolled.I AND Enrolled.J = Course.E AND Student.D > 30;";

		// Student.A and Course.F in projection
		// Student.D for selection
		// Enrolled.I and Enrolled.J for join conditions
		// Course.E for join condition
		String expectedOutput =
				"a,f\n" +
						"2,201\n" +
						"2,203\n" +
						"4,204\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test that projection pushdown doesn't drop columns needed for selection
	 */
	@Test
	public void testProjectionPushdownPreservesSelectionColumns() throws IOException {
		String queryName = "test_push_preserves_selection";
		String queryContent = "SELECT Student.A FROM Student WHERE Student.D > 30 AND Student.C < 30;";

		// Student.A is in projection
		// Student.D and Student.C are needed for selection
		String expectedOutput =
				"a\n" +
						"2\n" +
						"4\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test that projection pushdown correctly handles SELECT *
	 */
	@Test
	public void testProjectionPushdownWithSelectStar() throws IOException {
		String queryName = "test_push_with_select_star";
		String queryContent = "SELECT * FROM Student, Enrolled WHERE Student.A = Enrolled.I AND Student.D > 30;";

		// Should keep all columns since it's SELECT *
		String expectedOutput =
				"a,b,c,d,i,j,k\n" +
						"2,30,22,40,2,101,91\n" +
						"2,30,22,40,2,103,84\n" +
						"4,40,21,40,4,104,65\n";

		runTest(queryName, queryContent, expectedOutput);
	}

	/**
	 * Test with SUM aggregation on a calculated expression
	 */
	@Test
	public void testProjectionPushdownWithExpressionAggregation() throws IOException {
		String queryName = "test_push_with_expression_aggregation";
		String queryContent = "SELECT Student.D, SUM(Student.C*Enrolled.K) FROM Student, Enrolled " +
				"WHERE Student.A = Enrolled.I GROUP BY Student.D;";

		// Student.D for GROUP BY and projection
		// Student.C and Enrolled.K for SUM expression
		// Student.A and Enrolled.I for join condition
		String expectedOutput =
				"d,sum(0)\n" +
						"20,1482\n" +
						"40,5215\n" +
						"30,15045\n";    // Student 2: 22 * (91 + 84) + Student 4: 21 * 65 = 2919

		runTest(queryName, queryContent, expectedOutput, false); // Order might vary
	}



	/**
	 * Test all sample queries provided as sample queries.
	 * This test runs all SQL queries in the samples/input directory and
	 * compares the results with the expected outputs in samples/expected_output.
	 */
	@Test
	public void testAllSampleQueries() throws IOException {
		// Get absolute paths to avoid working directory issues
		String currentDir = System.getProperty("user.dir");

		// Paths for input queries, expected outputs, and test outputs
		String samplesDir = currentDir + "/samples";
		String dbDir = samplesDir + "/db";
		String inputDir = samplesDir + "/input";
		String expectedOutputDir = samplesDir + "/expected_output";
		String testOutputDir = "src/test/resources/test_sample_output";

		System.out.println("Using database directory: " + dbDir);
		System.out.println("Using input directory: " + inputDir);

		// Create output directory if it doesn't exist
		Files.createDirectories(Paths.get(testOutputDir));

		// Get all query files
		File inputDirFile = new File(inputDir);
		File[] queryFiles = inputDirFile.listFiles((dir, name) -> name.endsWith(".sql"));

		if (queryFiles == null || queryFiles.length == 0) {
			fail("No query files found in " + inputDir);
		}

		System.out.println("Found " + queryFiles.length + " query files to test");

		// Process each query file
		for (File queryFile : queryFiles) {
			String queryName = queryFile.getName();
			String baseName = queryName.substring(0, queryName.length() - 4); // Remove .sql

			System.out.println("Testing query: " + queryName);

			// Define paths
			String queryPath = queryFile.getAbsolutePath();
			String expectedOutputPath = expectedOutputDir + "/" + baseName + ".csv";
			String outputPath = testOutputDir + "/" + baseName + ".csv";

			// Check if expected output exists
			if (!Files.exists(Paths.get(expectedOutputPath))) {
				System.out.println("Warning: No expected output found for " + queryName + ", skipping");
				continue;
			}

			// Run CuckooDB
			CuckooDB.main(new String[] { dbDir, queryPath, outputPath });

			// Verify the output
			verifyOutputFiles(expectedOutputPath, outputPath, queryName, inputDir);
		}
	}

	/**
	 * Verifies that the actual output file matches the expected output file,
	 * with special handling for ORDER BY queries.
	 */
	private void verifyOutputFiles(String expectedFilePath, String actualFilePath,
								   String queryName, String inputDir) throws IOException {
		// Read the query to check if it has ORDER BY
		String queryPath = inputDir + "/" + queryName;
		String queryContent = new String(Files.readAllBytes(Paths.get(queryPath)));
		boolean hasOrderBy = queryContent.toUpperCase().contains("ORDER BY");

		// Read expected and actual outputs
		List<String> expectedLines = Files.readAllLines(Paths.get(expectedFilePath));
		List<String> actualLines = Files.readAllLines(Paths.get(actualFilePath));

		// Normalize lines (trim whitespace)
		List<String> normalizedExpected = new ArrayList<>();
		List<String> normalizedActual = new ArrayList<>();

		for (String line : expectedLines) {
			normalizedExpected.add(line.trim());
		}

		for (String line : actualLines) {
			normalizedActual.add(line.trim());
		}

		// Check line count
		assertEquals(normalizedExpected.size(), normalizedActual.size(), "Number of lines in output doesn't match expected for " + queryName);

		if (hasOrderBy) {
			// For ORDER BY queries, check exact order
			for (int i = 0; i < normalizedExpected.size(); i++) {
				assertEquals(normalizedExpected.get(i), normalizedActual.get(i), "Line " + (i+1) + " doesn't match for " + queryName);
			}
		} else {
			// For non-ORDER BY queries, sort and then compare
			Collections.sort(normalizedExpected);
			Collections.sort(normalizedActual);

			for (int i = 0; i < normalizedExpected.size(); i++) {
				assertEquals(normalizedExpected.get(i), normalizedActual.get(i), "Line content doesn't match (after sorting) for " + queryName);
			}
		}

		System.out.println("Test passed for " + queryName + "\n\n");
	}

	// Helper methods

	/**
	 * Runs a test with the given query name, query content, and expected output.
	 * Creates the query file, runs CuckooDB, and verifies the output.
	 */
    protected void runTest(String queryName, String queryContent, String expectedOutput) throws IOException {
		runTest(queryName, queryContent, expectedOutput, true);
	}

	/**
	 * Runs a test with the given query name, query content, and expected output.
	 *
	 * @param strictOrder If true, the output order must match exactly. If false,
	 *                    the output is compared regardless of row order.
	 */
    protected void runTest(String queryName, String queryContent, String expectedOutput, boolean strictOrder) throws IOException {
		// Create query file
		String queryFilePath = Paths.get(TEST_QUERIES_DIR, queryName + ".sql").toString();
		Files.write(Paths.get(queryFilePath), queryContent.getBytes(StandardCharsets.UTF_8));

		// Create expected output file
		String expectedFilePath = Paths.get(EXPECTED_OUTPUT_DIR, queryName + ".csv").toString();
		Files.write(Paths.get(expectedFilePath), expectedOutput.getBytes(StandardCharsets.UTF_8));

		// Run CuckooDB
		String outputFilePath = Paths.get(TEST_OUTPUT_DIR, queryName + ".csv").toString();
		CuckooDB.main(new String[] { TEST_DB_DIR, queryFilePath, outputFilePath });

		// Verify output
		if (strictOrder) {
			verifyOutputExactOrder(expectedFilePath, outputFilePath);
		} else {
			verifyOutputAnyOrder(expectedFilePath, outputFilePath);
		}
	}

	/**
	 * Verifies that the output file exactly matches the expected file.
	 */
	private void verifyOutputExactOrder(String expectedFilePath, String outputFilePath) throws IOException {
		List<String> expectedLines = Files.readAllLines(Paths.get(expectedFilePath));
		List<String> actualLines = Files.readAllLines(Paths.get(outputFilePath));

		assertEquals(expectedLines.size(), actualLines.size(), "Number of lines in output doesn't match expected");

		for (int i = 0; i < expectedLines.size(); i++) {
			String expected = expectedLines.get(i).trim();
			String actual = actualLines.get(i).trim();
			assertEquals(expected, actual, "Line " + (i+1) + " doesn't match");
		}
	}

	/**
	 * Verifies that the output file contains the same lines as the expected file,
	 * regardless of order.
	 */
	private void verifyOutputAnyOrder(String expectedFilePath, String outputFilePath) throws IOException {
		List<String> expectedLines = Files.readAllLines(Paths.get(expectedFilePath));
		List<String> actualLines = Files.readAllLines(Paths.get(outputFilePath));

		assertEquals(expectedLines.size(), actualLines.size(), "Number of lines in output doesn't match expected");

		// Normalize and sort the lines for comparison
		List<String> normalizedExpected = new ArrayList<>();
		List<String> normalizedActual = new ArrayList<>();

		for (String line : expectedLines) {
			normalizedExpected.add(line.trim());
		}

		for (String line : actualLines) {
			normalizedActual.add(line.trim());
		}

		Collections.sort(normalizedExpected);
		Collections.sort(normalizedActual);

		for (int i = 0; i < normalizedExpected.size(); i++) {
			assertEquals(normalizedExpected.get(i), normalizedActual.get(i), "Line content doesn't match (after sorting)");
		}
	}

	/**
	 * Recursively deletes a directory and its contents.
	 */
	private void deleteDirectory(Path path) throws IOException {
		if (Files.exists(path)) {
			Files.walk(path)
					.sorted(Comparator.reverseOrder())
					.map(Path::toFile)
					.forEach(File::delete);
		}
	}

	/**
	 * Creates sample Student data.
	 * A: student ID, B: code representing name, C: age, D: GPA*10
	 */
	private void createStudentData() throws IOException {
		String data =
				"A, B, C, D\n" +
						"1, 25, 85, 30\n" +
						"2, 30, 22, 40\n" +
						"3, 35, 19, 20\n" +
						"4, 40, 21, 40\n" +
						"5, 45, 65, 30\n" +
						"6, 50, 32, 10";

		Files.write(Paths.get(TEST_DB_DIR, "data", "Student.csv"), data.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Creates sample Course data.
	 * E: course ID, F: code representing course name, G: credits, H: department ID
	 */
	private void createCourseData() throws IOException {
		String data =
				"E, F, G, H\n" +
						"101, 201, 3, 1\n" +
						"102, 202, 3, 2\n" +
						"103, 203, 3, 3\n" +
						"104, 204, 4, 3\n" +
						"105, 205, 4, 1\n" +
						"106, 206, 4, 2";

		Files.write(Paths.get(TEST_DB_DIR, "data", "Course.csv"), data.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Creates sample Enrolled data.
	 * I: student ID, J: course ID, K: grade
	 */
	private void createEnrolledData() throws IOException {
		String data =
				"I, J, K\n" +
						"1, 101, 85\n" +
						"1, 102, 92\n" +
						"2, 101, 91\n" +
						"2, 103, 84\n" +
						"3, 102, 78\n" +
						"4, 104, 65";

		Files.write(Paths.get(TEST_DB_DIR, "data", "Enrolled.csv"), data.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Creates sample Department data.
	 * L: department ID, M: code representing name, N: code representing building
	 */
	private void createDepartmentData() throws IOException {
		String data =
				"L, M, N\n" +
						"1, 301, 401\n" +
						"2, 302, 402\n" +
						"3, 303, 403";

		Files.write(Paths.get(TEST_DB_DIR, "data", "Department.csv"), data.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Creates sample Faculty data.
	 * O: faculty ID, P: code representing name, Q: department ID
	 */
	private void createFacultyData() throws IOException {
		String data =
				"O, P, Q\n" +
						"1, 501, 1\n" +
						"2, 502, 1\n" +
						"3, 503, 2\n" +
						"4, 504, 2\n" +
						"5, 505, 3\n" +
						"6, 506, 3";

		Files.write(Paths.get(TEST_DB_DIR, "data", "Faculty.csv"), data.getBytes(StandardCharsets.UTF_8));
	}
}