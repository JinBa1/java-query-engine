package com.github.jinba1.blazedb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.github.jinba1.blazedb.operator.JoinOperator;
import com.github.jinba1.blazedb.operator.Operator;
import com.github.jinba1.blazedb.operator.ScanOperator;
import com.github.jinba1.blazedb.operator.SelectOperator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class JoinOperatorTest {

    private static final String TEST_DB_DIR = "src/test/resources/testdb";
    private static final String SCHEMA_FILE = TEST_DB_DIR + "/schema.txt";
    private static final String DATA_DIR = TEST_DB_DIR + "/data";
    private static final String STUDENTS_TABLE = "Students";
    private static final String COURSES_TABLE = "Courses";
    private static final String ENROLLED_TABLE = "Enrolled";
    private static final String EMPTY_TABLE = "EmptyTable";

    @BeforeEach
    public void setUp() throws IOException {
        // Create test database directory structure
        Files.createDirectories(Paths.get(DATA_DIR));

        // Create schema file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SCHEMA_FILE))) {
            writer.write(STUDENTS_TABLE + " sid name age gpa\n");
            writer.write(COURSES_TABLE + " cid title credits department\n");
            writer.write(ENROLLED_TABLE + " sid cid grade\n");
            writer.write(EMPTY_TABLE + " id name\n");
        }

        // Create Students table data
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + STUDENTS_TABLE + ".csv"))) {
            writer.write("1, 25, 20, 3\n");    // sid=1, name=25, age=20, gpa=3
            writer.write("2, 30, 22, 4\n");    // sid=2, name=30, age=22, gpa=4
            writer.write("3, 35, 19, 2\n");    // sid=3, name=35, age=19, gpa=2
            writer.write("4, 40, 21, 3\n");    // sid=4, name=40, age=21, gpa=3
        }

        // Create Courses table data
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + COURSES_TABLE + ".csv"))) {
            writer.write("101, 50, 3, 10\n");  // cid=101, title=50, credits=3, department=10
            writer.write("102, 55, 4, 10\n");  // cid=102, title=55, credits=4, department=10
            writer.write("103, 60, 3, 20\n");  // cid=103, title=60, credits=3, department=20
            writer.write("104, 65, 5, 20\n");  // cid=104, title=65, credits=5, department=20
        }

        // Create Enrolled table data
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + ENROLLED_TABLE + ".csv"))) {
            writer.write("1, 101, 85\n");      // sid=1, cid=101, grade=85
            writer.write("1, 103, 90\n");      // sid=1, cid=103, grade=90
            writer.write("2, 101, 95\n");      // sid=2, cid=101, grade=95
            writer.write("3, 102, 88\n");      // sid=3, cid=102, grade=88
            writer.write("4, 104, 75\n");      // sid=4, cid=104, grade=75
        }

        // Create empty table file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + EMPTY_TABLE + ".csv"))) {
            // Intentionally left empty
        }

        // Initialize the database catalog
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(TEST_DB_DIR);
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Clean up test files
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + STUDENTS_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + COURSES_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + ENROLLED_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + EMPTY_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(SCHEMA_FILE));
        Files.deleteIfExists(Paths.get(DATA_DIR));
        Files.deleteIfExists(Paths.get(TEST_DB_DIR));
    }

    @Test
    public void testSimpleEquiJoin() throws Exception {
        // Test equi-join: Students.sid = Enrolled.sid
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator enrolledOp = new ScanOperator(ENROLLED_TABLE);

        Expression joinCondition = CCJSqlParserUtil.parseExpression("Students.sid = Enrolled.sid");
        JoinOperator joinOp = new JoinOperator(studentsOp, enrolledOp, joinCondition);

        // Count the number of joined tuples - should match the number of enrollments
        List<Tuple> joinedTuples = new ArrayList<>();
        Tuple tuple;
        while ((tuple = joinOp.getNextTuple()) != null) {
            joinedTuples.add(tuple);
        }

        assertEquals(5, joinedTuples.size(), "Should produce 5 joined tuples");

        // Verify the structure of a joined tuple (4 attributes from Students + 3 attributes from Enrolled)
        assertEquals(7, joinedTuples.get(0).getTuple().size(), "Joined tuple should have 7 attributes");

        // Verify the join condition is met for all tuples
        for (Tuple t : joinedTuples) {
            assertEquals(t.getAttribute(0), t.getAttribute(4), "Student sid should match Enrolled sid");
        }
    }

    @Test
    public void testJoinWithPredicates() throws Exception {
        // First apply selection to both tables, then join
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        Expression studentFilter = CCJSqlParserUtil.parseExpression("Students.gpa > 2");
        SelectOperator filteredStudentsOp = new SelectOperator(studentsOp, studentFilter);

        ScanOperator enrolledOp = new ScanOperator(ENROLLED_TABLE);
        Expression enrolledFilter = CCJSqlParserUtil.parseExpression("Enrolled.grade > 80");
        SelectOperator filteredEnrolledOp = new SelectOperator(enrolledOp, enrolledFilter);

        Expression joinCondition = CCJSqlParserUtil.parseExpression("Students.sid = Enrolled.sid");
        JoinOperator joinOp = new JoinOperator(filteredStudentsOp, filteredEnrolledOp, joinCondition);

        // Count results and verify
        List<Tuple> joinedTuples = new ArrayList<>();
        Tuple tuple;
        while ((tuple = joinOp.getNextTuple()) != null) {
            joinedTuples.add(tuple);
        }

        // Students with gpa > 2 (sid 1, 2, 4) joined with Enrolled with grade > 80 (sid 1, 2, 3)
        // So we should have matches for sid 1 (2 courses) and sid 2 (1 course)
        assertEquals(3, joinedTuples.size(), "Should have 3 matching tuples after filtering");

        // Verify each tuple meets all conditions
        for (Tuple t : joinedTuples) {
            assertEquals(t.getAttribute(0), t.getAttribute(4), "Student sid should match Enrolled sid");
            assertTrue(((IntValue) t.getAttribute(3)).v() > 2, "Student gpa should be > 2");
            assertTrue(((IntValue) t.getAttribute(6)).v() > 80, "Enrolled grade should be > 80");
        }
    }

    @Test
    public void testThreeWayJoin() throws Exception {
        // Test three-way join: Students → Enrolled → Courses
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator enrolledOp = new ScanOperator(ENROLLED_TABLE);

        Expression firstJoinCondition = CCJSqlParserUtil.parseExpression("Students.sid = Enrolled.sid");
        JoinOperator firstJoinOp = new JoinOperator(studentsOp, enrolledOp, firstJoinCondition);

        ScanOperator coursesOp = new ScanOperator(COURSES_TABLE);
        Expression secondJoinCondition = CCJSqlParserUtil.parseExpression("Enrolled.cid = Courses.cid");
        JoinOperator secondJoinOp = new JoinOperator(firstJoinOp, coursesOp, secondJoinCondition);

        // Count results
        List<Tuple> joinedTuples = new ArrayList<>();
        Tuple tuple;
        while ((tuple = secondJoinOp.getNextTuple()) != null) {
            joinedTuples.add(tuple);
        }

        // Should match all enrollments (5 records)
        assertEquals(5, joinedTuples.size(), "Three-way join should have 5 tuples");

        // Verify structure (4 from Students + 3 from Enrolled + 4 from Courses)
        assertEquals(11, joinedTuples.get(0).getTuple().size(), "Three-way joined tuple should have 11 attributes");

        // Verify join conditions
        for (Tuple t : joinedTuples) {
            assertEquals(t.getAttribute(0), t.getAttribute(4), "Student sid should match Enrolled sid");
            assertEquals(t.getAttribute(5), t.getAttribute(7), "Enrolled cid should match Courses cid");
        }
    }

    @Test
    public void testJoinWithEmptyTable() throws Exception {
        // Test join with an empty table
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator emptyOp = new ScanOperator(EMPTY_TABLE);

        // Use a join condition
        Expression joinCondition = CCJSqlParserUtil.parseExpression("1 = 1");
        JoinOperator joinOp = new JoinOperator(studentsOp, emptyOp, joinCondition);

        // Should not produce any tuples
        assertNull(joinOp.getNextTuple(), "Join with empty table should produce null");
    }

    @Test
    public void testNonEquiJoin() throws Exception {
        // Test join with a non-equality condition: Students.gpa > Courses.credits
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator coursesOp = new ScanOperator(COURSES_TABLE);

        Expression joinCondition = CCJSqlParserUtil.parseExpression("Students.gpa > Courses.credits");
        JoinOperator joinOp = new JoinOperator(studentsOp, coursesOp, joinCondition);

        // Count results
        List<Tuple> joinedTuples = new ArrayList<>();
        Tuple tuple;
        while ((tuple = joinOp.getNextTuple()) != null) {
            joinedTuples.add(tuple);
        }

        // Manually calculate expected count:
        // Student 1 (gpa 3): matches courses with credits < 3 (none)
        // Student 2 (gpa 4): matches courses with credits < 4 (101, 103)
        // Student 3 (gpa 2): matches courses with credits < 2 (none)
        // Student 4 (gpa 3): matches courses with credits < 3 (none)
        assertEquals(2, joinedTuples.size(), "Should have correct number of joined tuples");

        // Verify join condition for all tuples
        for (Tuple t : joinedTuples) {
            assertTrue(((IntValue) t.getAttribute(3)).v() > ((IntValue) t.getAttribute(6)).v(), "Student gpa should be > Course credits");
        }
    }

    @Test
    public void testReset() throws Exception {
        // Test reset functionality
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator enrolledOp = new ScanOperator(ENROLLED_TABLE);

        Expression joinCondition = CCJSqlParserUtil.parseExpression("Students.sid = Enrolled.sid");
        JoinOperator joinOp = new JoinOperator(studentsOp, enrolledOp, joinCondition);

        // Get all tuples first time
        List<Tuple> firstRunTuples = new ArrayList<>();
        Tuple tuple;
        while ((tuple = joinOp.getNextTuple()) != null) {
            firstRunTuples.add(tuple);
        }

        // Reset and get tuples again
        joinOp.reset();

        List<Tuple> secondRunTuples = new ArrayList<>();
        while ((tuple = joinOp.getNextTuple()) != null) {
            secondRunTuples.add(tuple);
        }

        // Both runs should return the same number of tuples
        assertEquals(firstRunTuples.size(), secondRunTuples.size(), "Should return same number of tuples after reset");

        // Both runs should return tuples in the same order with identical values
        for (int i = 0; i < firstRunTuples.size(); i++) {
            Tuple firstTuple = firstRunTuples.get(i);
            Tuple secondTuple = secondRunTuples.get(i);

            assertEquals(firstTuple.getTuple().size(), secondTuple.getTuple().size(), "Tuples should have same size after reset");

            for (int j = 0; j < firstTuple.getTuple().size(); j++) {
                assertEquals(firstTuple.getAttribute(j), secondTuple.getAttribute(j), "Attribute values should match after reset");
            }
        }
    }

    @Test
    public void testCrossProduct() throws Exception {
        // Test cross product (join with no condition)
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator coursesOp = new ScanOperator(COURSES_TABLE);

        // Null join condition means cross product
        JoinOperator joinOp = new JoinOperator(studentsOp, coursesOp, null);

        // Count results
        int count = 0;
        Tuple tuple;
        while ((tuple = joinOp.getNextTuple()) != null) {
            count++;
        }

        // Cross product size should be product of table sizes (4 students × 4 courses = 16)
        assertEquals(16, count, "Cross product should produce 16 tuples");
    }

    @Test
    public void testJoinWithComplexCondition() throws Exception {
        // Test join with a more complex condition: Students.sid = Enrolled.sid AND Students.gpa >= 3
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator enrolledOp = new ScanOperator(ENROLLED_TABLE);

        Expression joinCondition = CCJSqlParserUtil.parseExpression(
                "Students.sid = Enrolled.sid AND Students.gpa >= 3");
        JoinOperator joinOp = new JoinOperator(studentsOp, enrolledOp, joinCondition);

        // Count results
        List<Tuple> joinedTuples = new ArrayList<>();
        Tuple tuple;
        while ((tuple = joinOp.getNextTuple()) != null) {
            joinedTuples.add(tuple);
        }

        // Should only return enrollments for students with gpa >= 3 (sid 1, 2, 4)
        assertEquals(4, joinedTuples.size(), "Should have correct number of joined tuples");

        // Verify conditions are met for all tuples
        for (Tuple t : joinedTuples) {
            assertEquals(t.getAttribute(0), t.getAttribute(4), "Student sid should match Enrolled sid");
            assertTrue(((IntValue) t.getAttribute(3)).v() >= 3, "Student gpa should be >= 3");
        }
    }

    @Test
    public void testMultiColumnJoin() throws Exception {
        // Test joining on multiple conditions
        ScanOperator studentsOp = new ScanOperator(STUDENTS_TABLE);
        ScanOperator coursesOp = new ScanOperator(COURSES_TABLE);

        // Create a join condition that matches on department/age criteria
        Expression joinCondition = CCJSqlParserUtil.parseExpression(
                "Students.age = Courses.department AND Students.gpa < Courses.credits");
        JoinOperator joinOp = new JoinOperator(studentsOp, coursesOp, joinCondition);

        // Count results
        List<Tuple> joinedTuples = new ArrayList<>();
        Tuple tuple;
        while ((tuple = joinOp.getNextTuple()) != null) {
            joinedTuples.add(tuple);
        }

        // Verify each tuple meets both join conditions
        for (Tuple t : joinedTuples) {
            assertEquals(t.getAttribute(2), t.getAttribute(7), "Student age should match Course department");
            assertTrue(((IntValue) t.getAttribute(3)).v() < ((IntValue) t.getAttribute(6)).v(), "Student gpa should be < Course credits");
        }
    }

    @Test
    public void testOrdinaryVersusNestedLoopExecution() throws Exception {
        // This test verifies the nested loop execution behavior by comparing:
        // 1. Regular nested loop (retrieving all results at once)
        // 2. Step-by-step retrieval (simulating the iterator pattern)

        ScanOperator studentsOp1 = new ScanOperator(STUDENTS_TABLE);
        ScanOperator enrolledOp1 = new ScanOperator(ENROLLED_TABLE);
        Expression joinCondition1 = CCJSqlParserUtil.parseExpression("Students.sid = Enrolled.sid");
        JoinOperator joinOp1 = new JoinOperator(studentsOp1, enrolledOp1, joinCondition1);

        // Get all results at once using the first operator
        List<Tuple> allResults = new ArrayList<>();
        Tuple tuple;
        while ((tuple = joinOp1.getNextTuple()) != null) {
            allResults.add(tuple);
        }

        // Create new operators for step-by-step comparison
        ScanOperator studentsOp2 = new ScanOperator(STUDENTS_TABLE);
        ScanOperator enrolledOp2 = new ScanOperator(ENROLLED_TABLE);
        Expression joinCondition2 = CCJSqlParserUtil.parseExpression("Students.sid = Enrolled.sid");
        JoinOperator joinOp2 = new JoinOperator(studentsOp2, enrolledOp2, joinCondition2);

        // Get the first three results
        List<Tuple> partialResults = new ArrayList<>();
        for (int i = 0; i < 3 && (tuple = joinOp2.getNextTuple()) != null; i++) {
            partialResults.add(tuple);
        }

        // Verify the partial results match the first part of all results
        assertEquals(3, partialResults.size(), "Partial results size should match");
        for (int i = 0; i < partialResults.size(); i++) {
            Tuple allTuple = allResults.get(i);
            Tuple partialTuple = partialResults.get(i);

            assertEquals(allTuple.getTuple().size(), partialTuple.getTuple().size(), "Tuples should have same size");

            for (int j = 0; j < allTuple.getTuple().size(); j++) {
                assertEquals(allTuple.getAttribute(j), partialTuple.getAttribute(j), "Attribute values should match");
            }
        }

        // Continue retrieving the rest of the results
        List<Tuple> remainingResults = new ArrayList<>();
        while ((tuple = joinOp2.getNextTuple()) != null) {
            remainingResults.add(tuple);
        }

        // Verify we got the expected number of remaining results
        assertEquals(allResults.size() - partialResults.size(), remainingResults.size(), "Remaining results count should match");

        // Verify the combined results match the complete set
        for (int i = 0; i < remainingResults.size(); i++) {
            Tuple allTuple = allResults.get(i + partialResults.size());
            Tuple remainingTuple = remainingResults.get(i);

            assertEquals(allTuple.getTuple().size(), remainingTuple.getTuple().size(), "Tuples should have same size");

            for (int j = 0; j < allTuple.getTuple().size(); j++) {
                assertEquals(allTuple.getAttribute(j), remainingTuple.getAttribute(j), "Attribute values should match");
            }
        }
    }
}
