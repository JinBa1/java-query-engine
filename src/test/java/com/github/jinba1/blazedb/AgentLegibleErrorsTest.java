package com.github.jinba1.blazedb;

import com.github.jinba1.blazedb.operator.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Errors triggered by user SQL must be QueryExecutionExceptions with a machine-readable
 * ErrorCode and a message an LLM agent can self-correct from: name the offending
 * table/column AND list what is actually available. Previously these paths threw bare
 * RuntimeException/UnsupportedOperationException (raw stack trace, or swallowed into a
 * generic "query could not be planned").
 */
public class AgentLegibleErrorsTest {

    @TempDir
    Path tempDir;

    private Path db;

    @BeforeEach
    public void setUp() throws IOException {
        db = tempDir.resolve("db");
        Path data = db.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("Student.csv"), List.of(
                "sid, cid, age, grade",
                "1, 101, 20, 75",
                "2, 102, 21, 82"));
        Files.write(data.resolve("Course.csv"), List.of(
                "cid, title",
                "101, algebra",
                "102, calculus"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(db.toString());
    }

    private Path query(String sql) throws IOException {
        Path file = tempDir.resolve("q" + sql.hashCode() + ".sql");
        Files.writeString(file, sql);
        return file;
    }

    private QueryExecutionException assertPlanFails(String sql) throws IOException {
        Path file = query(sql);
        return assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.planQuery(file.toString()));
    }

    private QueryExecutionException assertExecutionFails(String sql) throws IOException {
        Path file = query(sql);
        PlannedQuery planned = QueryPlanner.planQuery(file.toString());
        Operator root = planned.root();
        return assertThrows(QueryExecutionException.class, () -> {
            while (root.getNextTuple() != null) {
                // drain
            }
        });
    }

    // ---- column not found ----

    @Test
    public void selectUnknownColumnFailsAtPlanTimeWithAvailableList() throws IOException {
        QueryExecutionException ex = assertPlanFails("SELECT Student.nam FROM Student");
        assertEquals(ErrorCode.UNKNOWN_COLUMN, ex.code());
        assertTrue(ex.getMessage().contains("nam"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Available"), ex.getMessage());
        assertTrue(ex.getMessage().contains("sid"), ex.getMessage());
        assertTrue(ex.getMessage().contains("grade"), ex.getMessage());
    }

    @Test
    public void whereUnknownColumnInJoinQueryFailsAtPlanTime() throws IOException {
        QueryExecutionException ex = assertPlanFails(
                "SELECT * FROM Student, Course WHERE Student.nam = Course.cid");
        assertEquals(ErrorCode.UNKNOWN_COLUMN, ex.code());
        // same single construction site as every other unknown-column path
        assertTrue(ex.getMessage().contains("Column 'Student.nam' not found"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Available"), ex.getMessage());
        assertTrue(ex.getMessage().contains("sid"), ex.getMessage());
    }

    @Test
    public void whereUnknownColumnInSingleTableQueryFailsAtExecution() throws IOException {
        QueryExecutionException ex = assertExecutionFails(
                "SELECT * FROM Student WHERE Student.nam = 5");
        assertEquals(ErrorCode.UNKNOWN_COLUMN, ex.code());
        assertTrue(ex.getMessage().contains("nam"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Available"), ex.getMessage());
        assertTrue(ex.getMessage().contains("sid"), ex.getMessage());
    }

    @Test
    public void orderByUnknownColumnFailsWithAvailableList() throws IOException {
        QueryExecutionException ex = assertExecutionFails(
                "SELECT * FROM Student ORDER BY Student.nam");
        assertEquals(ErrorCode.UNKNOWN_COLUMN, ex.code());
        assertTrue(ex.getMessage().contains("nam"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Available"), ex.getMessage());
    }

    @Test
    public void groupByUnknownColumnFailsAtPlanTime() throws IOException {
        QueryExecutionException ex = assertPlanFails(
                "SELECT Student.nam, COUNT(*) FROM Student GROUP BY Student.nam");
        assertEquals(ErrorCode.UNKNOWN_COLUMN, ex.code());
        assertTrue(ex.getMessage().contains("nam"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Available"), ex.getMessage());
    }

    // ---- table not found ----

    @Test
    public void unknownTableInFromListsAvailableTables() throws IOException {
        QueryExecutionException ex = assertPlanFails("SELECT * FROM Studnt");
        assertEquals(ErrorCode.UNKNOWN_TABLE, ex.code());
        assertTrue(ex.getMessage().contains("Studnt"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Available tables"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Student"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Course"), ex.getMessage());
    }

    @Test
    public void unknownTableInWhereListsAvailableTables() throws IOException {
        QueryExecutionException ex = assertPlanFails(
                "SELECT * FROM Student, Course WHERE Studnt.sid = Course.cid");
        assertEquals(ErrorCode.UNKNOWN_TABLE, ex.code());
        assertTrue(ex.getMessage().contains("Studnt"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Available tables"), ex.getMessage());
    }

    // ---- parse / unsupported SQL ----

    @Test
    public void syntaxErrorSurfacesParserMessage() throws IOException {
        QueryExecutionException ex = assertPlanFails("SELEKT * FROM Student");
        assertEquals(ErrorCode.PARSE_ERROR, ex.code());
        assertTrue(ex.getMessage().contains("SQL syntax error"), ex.getMessage());
    }

    @Test
    public void nonSelectStatementIsUnsupported() throws IOException {
        QueryExecutionException ex = assertPlanFails("UPDATE Student SET age = 5");
        assertEquals(ErrorCode.UNSUPPORTED_SQL, ex.code());
        assertTrue(ex.getMessage().contains("SELECT"), ex.getMessage());
    }

    @Test
    public void unsupportedConditionOperatorIsUnsupportedSql() throws IOException {
        // OR is not supported by the engine's condition handling
        QueryExecutionException ex = assertExecutionFails(
                "SELECT * FROM Student WHERE Student.sid = 1 OR Student.sid = 2");
        assertEquals(ErrorCode.UNSUPPORTED_SQL, ex.code());
    }

    @Test
    public void selectArithmeticItemIsUnsupported() throws IOException {
        QueryExecutionException ex = assertPlanFails("SELECT Student.sid * 2 FROM Student");
        assertEquals(ErrorCode.UNSUPPORTED_SQL, ex.code());
        assertTrue(ex.getMessage().contains("column references"), ex.getMessage());
    }

    @Test
    public void orderByArithmeticItemIsUnsupported() throws IOException {
        QueryExecutionException ex = assertPlanFails(
                "SELECT * FROM Student ORDER BY Student.sid * 2");
        assertEquals(ErrorCode.UNSUPPORTED_SQL, ex.code());
        assertTrue(ex.getMessage().contains("ORDER BY"), ex.getMessage());
    }

    @Test
    public void rowShapeChangedSinceCatalogInitIsDataError() throws IOException {
        // Catalog inferred 4 columns at init; a row appended afterwards with the
        // wrong width must fail as DATA_ERROR when the scan reaches it
        Files.writeString(db.resolve("data").resolve("Student.csv"),
                "3, 103", java.nio.file.StandardOpenOption.APPEND);
        QueryExecutionException ex = assertExecutionFails("SELECT * FROM Student");
        assertEquals(ErrorCode.DATA_ERROR, ex.code());
        assertTrue(ex.getMessage().contains("expected 4 fields"), ex.getMessage());
    }

    // ---- type errors ----

    @Test
    public void comparingIntColumnToStringIsTypeMismatch() throws IOException {
        QueryExecutionException ex = assertExecutionFails(
                "SELECT * FROM Student WHERE Student.sid = 'abc'");
        assertEquals(ErrorCode.TYPE_MISMATCH, ex.code());
    }

    // ---- exception API ----

    @Test
    public void singleArgConstructorDefaultsToInternal() {
        assertEquals(ErrorCode.INTERNAL, new QueryExecutionException("boom").code());
    }

    @Test
    public void budgetExceptionCarriesBudgetExceededCode() {
        QueryBudget budget = new QueryBudget(0L, null);
        QueryBudgetExceededException ex =
                assertThrows(QueryBudgetExceededException.class, budget::charge);
        assertEquals(ErrorCode.BUDGET_EXCEEDED, ex.code());
    }

    @Test
    public void catalogExposesSortedTableNames() {
        assertEquals(List.of("Course", "Student"), DBCatalog.getInstance().getTableNames());
    }

    // ---- CLI behavior ----

    @Test
    public void cliReturnsOneInsteadOfCrashingOnRuntimeColumnMiss() throws IOException {
        // Pre-change this path threw a bare RuntimeException straight through run()
        Path queryFile = query("SELECT * FROM Student ORDER BY Student.nam");
        Path out = tempDir.resolve("out.csv");
        int code = BlazeDB.run(new String[]{db.toString(), queryFile.toString(), out.toString()});
        assertEquals(1, code);
        assertFalse(Files.exists(out), "partial output must be deleted");
    }

    @Test
    public void cliReportsUnreadableQueryFile() {
        Path out = tempDir.resolve("out.csv");
        int code = BlazeDB.run(new String[]{
                db.toString(), tempDir.resolve("missing.sql").toString(), out.toString()});
        assertEquals(1, code);
        assertFalse(Files.exists(out));
    }
}
