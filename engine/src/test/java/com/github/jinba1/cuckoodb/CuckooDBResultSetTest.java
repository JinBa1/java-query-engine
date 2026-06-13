package com.github.jinba1.cuckoodb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.jinba1.cuckoodb.operator.Operator;

/**
 * E2/E3: in-memory result materialization ({@link CuckooDB#executeToResultSet}) with
 * positional columns, qualified names for join-duplicate disambiguation, best-effort
 * runtime type inference, and truncation/hint parity with the CSV-file path.
 */
class CuckooDBResultSetTest {

    private static final String SAMPLE_DB_DIR = "samples/db";

    @TempDir
    Path tempDir;

    @BeforeEach
    void initCatalog() throws IOException {
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        // A table with one int column and one string column, registered at runtime,
        // so type inference and the empty-result case are exercised on known types.
        Path typed = tempDir.resolve("typed.csv");
        Files.writeString(typed, "n,s\n1,alice\n2,bob\n");
        DBCatalog.getInstance().registerTable("Typed", typed);
    }

    @AfterEach
    void resetCatalog() {
        DBCatalog.resetDBCatalog();
    }

    private QueryResultSet run(String sql) {
        Operator root = QueryPlanner.planSql(sql, QueryConfig.defaults()).root();
        return CuckooDB.executeToResultSet(root);
    }

    @Test
    void joinDuplicateNamesGetDistinctQualifiedNames() {
        QueryResultSet rs = run("SELECT * FROM Student, Enrolled WHERE Student.A = Enrolled.A");

        List<String> names = rs.columns().stream().map(ColumnMeta::name).toList();
        assertEquals(List.of("a", "b", "c", "d", "a", "e", "h"), names,
                "join SELECT * emits duplicate bare 'a' (verified header shape)");

        assertEquals("student.a", rs.columns().get(0).qualifiedName());
        assertEquals("enrolled.a", rs.columns().get(4).qualifiedName());
        assertNotEquals(rs.columns().get(0).qualifiedName(), rs.columns().get(4).qualifiedName(),
                "the two 'a' columns must be distinguishable by qualifiedName");
    }

    @Test
    void typeInferredFromFirstRow() {
        QueryResultSet rs = run("SELECT * FROM Typed");

        assertEquals(2, rs.columns().size());
        assertEquals(ColumnType.INT, rs.columns().get(0).type(), "n column is all-int");
        assertEquals(ColumnType.STRING, rs.columns().get(1).type(), "s column is string");
        assertFalse(rs.truncated());
        assertNull(rs.hint());
        assertEquals(2, rs.rows().size());
    }

    @Test
    void emptyResultKeepsNamesButHasNullTypes() {
        QueryResultSet rs = run("SELECT * FROM Typed WHERE Typed.n > 999");

        assertTrue(rs.rows().isEmpty(), "no rows match");
        List<String> names = rs.columns().stream().map(ColumnMeta::name).toList();
        assertEquals(List.of("n", "s"), names, "column names survive an empty result");
        for (ColumnMeta col : rs.columns()) {
            assertNull(col.type(), "no row means no inferable type");
        }
    }

    @Test
    void truncationAndHintMatchTheFilePath() throws IOException {
        // Student has 6 rows; LIMIT 2 truncates.
        QueryResultSet rs = run("SELECT * FROM Student LIMIT 2");
        assertTrue(rs.truncated());
        assertEquals(2, rs.rows().size());
        assertEquals(QueryResult.truncated(2).hint(), rs.hint(),
                "hint text must reuse the CLI path's wording verbatim");
    }

    @Test
    void limitAtOrAboveSizeIsNotTruncated() {
        QueryResultSet rs = run("SELECT * FROM Student LIMIT 100");
        assertFalse(rs.truncated());
        assertNull(rs.hint());
        assertEquals(6, rs.rows().size());
    }

    @Test
    void resultSetRowsMatchTheCsvFileBytesForSameQuery() throws IOException {
        String sql = "SELECT Student.A, Student.D FROM Student WHERE Student.D > 30";

        // File path
        Path out = tempDir.resolve("out.csv");
        Operator fileRoot = QueryPlanner.planSql(sql, QueryConfig.defaults()).root();
        CuckooDB.execute(fileRoot, out.toString());
        List<String> fileLines = Files.readAllLines(out);
        List<String> fileDataLines = fileLines.subList(1, fileLines.size()); // drop header

        // Result-set path
        QueryResultSet rs = run(sql);
        List<String> rsDataLines = new ArrayList<>();
        for (List<Value> row : rs.rows()) {
            List<String> fields = new ArrayList<>();
            for (Value v : row) {
                fields.add(v.toString());
            }
            rsDataLines.add(String.join(",", fields));
        }

        assertEquals(fileDataLines, rsDataLines,
                "the two drain paths must produce identical row data");
    }

    /**
     * Regression for the review's HIGH finding: a one-sided WHERE filter makes the optimizer
     * push a selection under the join, so the join source is an intermediate (temp_) schema
     * that carries both a base-qualified key (enrolled.a) and an internal temp_<hex>.a key at
     * the same index. qualifiedName must report the real origin, never the internal id.
     */
    @Test
    void qualifiedNameNeverLeaksInternalSchemaIdsAcrossPushdownJoin() {
        QueryResultSet rs = run(
                "SELECT * FROM Student, Enrolled WHERE Student.A = Enrolled.A AND Enrolled.H > 0");

        List<String> names = rs.columns().stream().map(ColumnMeta::name).toList();
        assertEquals(List.of("a", "b", "c", "d", "a", "e", "h"), names);

        List<String> quals = rs.columns().stream().map(ColumnMeta::qualifiedName).toList();
        for (String q : quals) {
            assertFalse(q != null && q.startsWith("temp_"),
                    "qualifiedName must be a real table origin, not an internal id: " + quals);
        }
        assertEquals("student.a", rs.columns().get(0).qualifiedName());
        assertEquals("enrolled.a", rs.columns().get(4).qualifiedName(),
                "the pushed-down side still resolves to its base table origin");
    }

    @Test
    void resultSetCollectionsAreUnmodifiable() {
        // The record is a value handed to the REST layer; callers must not be able to mutate
        // rows or columns after the fact.
        QueryResultSet rs = run("SELECT * FROM Student");
        assertThrows(UnsupportedOperationException.class, () -> rs.rows().add(List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> rs.columns().add(new ColumnMeta("x", null, null)));
    }

    @Test
    void limitZeroIsEmptyButTruncated() {
        // The one state where rows.isEmpty() coincides with truncated=true: LIMIT 0 over a
        // non-empty source. Names survive, every type is null, and the truncation hint is set.
        QueryResultSet rs = run("SELECT * FROM Student LIMIT 0");

        assertTrue(rs.rows().isEmpty());
        assertTrue(rs.truncated());
        assertEquals(QueryResult.truncated(0).hint(), rs.hint());
        List<String> names = rs.columns().stream().map(ColumnMeta::name).toList();
        assertEquals(List.of("a", "b", "c", "d"), names);
        for (ColumnMeta col : rs.columns()) {
            assertNull(col.type(), "no row means no inferable type");
        }
    }

    @Test
    void aggregateColumnsHaveNullQualifiedNameAndInferredTypes() {
        // SUM(Typed.n) / MIN(Typed.s) keys contain a '.', so they pin the '(' exclusion that
        // keeps aggregate columns out of qualifiedName; a no-group aggregate emits exactly one
        // row, so first-row inference still types every column.
        QueryResultSet rs = run("SELECT COUNT(*), SUM(Typed.n), MIN(Typed.s) FROM Typed");

        assertEquals(1, rs.rows().size(), "a no-group aggregate emits exactly one row");
        List<String> names = rs.columns().stream().map(ColumnMeta::name).toList();
        assertEquals(List.of("count(*)", "sum(typed.n)", "min(typed.s)"), names);
        for (ColumnMeta col : rs.columns()) {
            assertNull(col.qualifiedName(), "aggregate columns have no dotted origin: " + col.name());
        }
        assertEquals(ColumnType.INT, rs.columns().get(0).type(), "count(*) is INT");
        assertEquals(ColumnType.INT, rs.columns().get(1).type(), "sum over int is INT");
        assertEquals(ColumnType.STRING, rs.columns().get(2).type(), "min over string is STRING");
    }
}
