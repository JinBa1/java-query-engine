package com.github.jinba1.cuckoodb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.jinba1.cuckoodb.operator.Operator;

/**
 * E1: the String-SQL planning entrypoint ({@link QueryPlanner#planSql}) must behave
 * identically to the file overload — same plan, same EXPLAIN rendering, same error
 * classification — because both delegate to one shared parsed-statement core. The
 * file overload itself stays exercised unchanged by SampleQueryRunner / ExplainEndToEndTest.
 */
class QueryPlannerSqlTest {

    private static final String SAMPLE_DB_DIR = "samples/db";

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void initCatalog() {
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
    }

    @AfterAll
    static void resetCatalog() {
        DBCatalog.resetDBCatalog();
    }

    /** Plans the same SQL from a string and from a file; the rendered trees must match. */
    private void assertPlanParity(String sql) throws IOException {
        QueryConfig cfg = QueryConfig.defaults();
        Path file = tempDir.resolve("q.sql");
        Files.writeString(file, sql);

        PlannedQuery fromFile = QueryPlanner.planQuery(file.toString(), cfg);
        PlannedQuery fromSql = QueryPlanner.planSql(sql, cfg);

        assertEquals(PlanPrinter.print(fromFile.root()), PlanPrinter.print(fromSql.root()),
                "string-source plan must render identically to file-source plan: " + sql);
        assertEquals(fromFile.explainText(), fromSql.explainText(),
                "EXPLAIN text must match across sources: " + sql);
    }

    @Test
    void selectStarParity() throws IOException {
        assertPlanParity("SELECT * FROM Student");
    }

    @Test
    void projectionAndWhereParity() throws IOException {
        assertPlanParity("SELECT Student.A, Student.D FROM Student WHERE Student.D > 30");
    }

    @Test
    void joinParity() throws IOException {
        assertPlanParity(
                "SELECT Student.A, Enrolled.H FROM Student, Enrolled WHERE Student.A = Enrolled.A");
    }

    @Test
    void explainParityRendersBeforeAndAfter() throws IOException {
        PlannedQuery planned = QueryPlanner.planSql(
                "EXPLAIN SELECT Student.A FROM Student WHERE Student.D > 30", QueryConfig.defaults());
        assertNotNull(planned.explainText(), "EXPLAIN must populate explainText");
        assertTrue(planned.explainText().contains("Plan (as written)"));
        assertTrue(planned.explainText().contains("Plan (optimized)"));
    }

    @Test
    void badSqlThrowsParseError() {
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.planSql("SELECT FROM WHERE", QueryConfig.defaults()));
        assertEquals(ErrorCode.PARSE_ERROR, e.code());
    }

    @Test
    void nonSelectThrowsUnsupported() {
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.planSql("DELETE FROM Student", QueryConfig.defaults()));
        assertEquals(ErrorCode.UNSUPPORTED_SQL, e.code());
    }

    @Test
    void trailingSemicolonIsAccepted() {
        // A single statement with a trailing ';' is valid SQL — must plan, not error.
        PlannedQuery planned = QueryPlanner.planSql("SELECT * FROM Student;", QueryConfig.defaults());
        assertNotNull(planned.root());
        assertNull(planned.explainText());
    }

    @Test
    void multipleStatementsAreRejected() {
        // Read-only-by-construction: a second statement must never sneak through. JSqlParser's
        // single-statement parse rejects it as a syntax error.
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.planSql("SELECT * FROM Student; SELECT * FROM Course;",
                        QueryConfig.defaults()));
        assertTrue(e.code() == ErrorCode.PARSE_ERROR || e.code() == ErrorCode.UNSUPPORTED_SQL,
                "multi-statement input must be rejected, got " + e.code());
    }
}
