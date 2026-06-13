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
}
