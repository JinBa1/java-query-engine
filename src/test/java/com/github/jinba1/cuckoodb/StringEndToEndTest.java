package com.github.jinba1.cuckoodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.github.jinba1.cuckoodb.operator.Operator;

/**
 * End-to-end characterization tests for string-column support.
 *
 * <p>These tests drive the real engine against a {@link org.junit.jupiter.api.io.TempDir}
 * database, mostly through {@link CuckooDB#main}. The two error-path tests drive the engine
 * via {@link QueryPlanner#parseStatement} plus {@link CuckooDB#execute} directly, because the
 * relevant {@link QueryExecutionException}s are thrown at runtime inside {@code getNextTuple()}
 * and propagate cleanly through {@code execute} (which catches only {@code IOException}).
 *
 * <p>Fixture {@code Employees} (name, dept STRING; salary INT), including a quoted value that
 * contains a comma to exercise RFC 4180 round-tripping:
 * <pre>
 * name,dept,salary
 * alice,eng,100
 * bob,sales,80
 * carol,eng,120
 * "smith, dan",sales,90
 * </pre>
 */
public class StringEndToEndTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDb;

    private void writeTable(Path dbDir, String name, String... lines) throws IOException {
        Path data = dbDir.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve(name + ".csv"), List.of(lines));
    }

    private void writeEmployees() throws IOException {
        writeTable(tempDb, "Employees",
                "name,dept,salary",
                "alice,eng,100",
                "bob,sales,80",
                "carol,eng,120",
                "\"smith, dan\",sales,90");
    }

    /**
     * Runs a query string through {@link CuckooDB#main} against the given database directory
     * and returns the output lines.
     */
    private List<String> runMain(Path dbDir, String sql) throws IOException {
        Path query = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(query, sql);
        Path out = tempDb.resolve("out-" + System.nanoTime() + ".csv");
        CuckooDB.main(new String[]{dbDir.toString(), query.toString(), out.toString()});
        return Files.readAllLines(out);
    }

    @BeforeEach
    public void setUp() {
        DBCatalog.resetDBCatalog();
    }

    @Test
    public void whereOnStringColumn() throws IOException {
        writeEmployees();
        List<String> lines = runMain(tempDb,
                "SELECT * FROM Employees WHERE Employees.dept = 'eng';");
        assertEquals(List.of(
                "name,dept,salary",
                "alice,eng,100",
                "carol,eng,120"), lines);
    }

    @Test
    public void orderByStringColumn() throws IOException {
        writeEmployees();
        List<String> lines = runMain(tempDb,
                "SELECT * FROM Employees ORDER BY Employees.name;");
        assertEquals(List.of(
                "name,dept,salary",
                "alice,eng,100",
                "bob,sales,80",
                "carol,eng,120",
                "\"smith, dan\",sales,90"), lines);
    }

    @Test
    public void distinctOnStringColumn() throws IOException {
        writeEmployees();
        List<String> lines = runMain(tempDb,
                "SELECT DISTINCT Employees.dept FROM Employees;");
        assertEquals(List.of(
                "dept",
                "eng",
                "sales"), lines);
    }

    @Test
    public void joinOnStringKey() throws IOException {
        writeEmployees();
        writeTable(tempDb, "Depts",
                "dept,location",
                "eng,floor1",
                "sales,floor2");
        List<String> lines = runMain(tempDb,
                "SELECT * FROM Employees, Depts WHERE Employees.dept = Depts.dept;");

        // header + 4 result rows
        assertEquals(5, lines.size(), "Expected header plus 4 joined rows");
        assertEquals("name,dept,salary,dept,location", lines.get(0));
        assertEquals("alice,eng,100,eng,floor1", lines.get(1));
        assertTrue(lines.contains("\"smith, dan\",sales,90,sales,floor2"),
                "Expected quoted-name employee joined with its dept row; got " + lines);
    }

    @Test
    public void roundTripOutputIsQueryable() throws IOException {
        writeEmployees();

        // (a) first query: salary > 85 keeps alice, carol, "smith, dan"
        Path query1 = tempDb.resolve("q1.sql");
        Files.writeString(query1, "SELECT * FROM Employees WHERE Employees.salary > 85;");
        Path out1 = tempDb.resolve("out1.csv");
        CuckooDB.main(new String[]{tempDb.toString(), query1.toString(), out1.toString()});

        List<String> out1Lines = Files.readAllLines(out1);
        assertEquals(List.of(
                "name,dept,salary",
                "alice,eng,100",
                "carol,eng,120",
                "\"smith, dan\",sales,90"), out1Lines);

        // (b) build a second database whose Result table IS the prior output
        Path db2 = Files.createTempDirectory("cuckoodb-roundtrip");
        Path db2Data = db2.resolve("data");
        Files.createDirectories(db2Data);
        Files.copy(out1, db2Data.resolve("Result.csv"));

        // (c) query the round-tripped table; output must be valid + exact
        DBCatalog.resetDBCatalog();
        Path query2 = tempDb.resolve("q2.sql");
        Files.writeString(query2, "SELECT * FROM Result WHERE Result.salary > 100;");
        Path out2 = tempDb.resolve("out2.csv");
        CuckooDB.main(new String[]{db2.toString(), query2.toString(), out2.toString()});

        List<String> out2Lines = Files.readAllLines(out2);
        assertEquals(List.of(
                "name,dept,salary",
                "carol,eng,120"), out2Lines);
    }

    @Test
    public void sumOnStringColumnFailsWithClearError() throws IOException {
        writeEmployees();
        Path query = tempDb.resolve("sum.sql");
        Files.writeString(query, "SELECT SUM(Employees.name) FROM Employees;");
        Path out = tempDb.resolve("sum-out.csv");

        // Drive parse + execute directly: the SUM type error is thrown at runtime inside
        // AggregateOperator and propagates through execute (which catches only IOException).
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        Operator root = QueryPlanner.parseStatement(query.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> CuckooDB.execute(root, out.toString()));
        assertTrue(ex.getMessage().contains("SUM requires int"),
                "Expected a SUM type error; got: " + ex.getMessage());
    }

    @Test
    public void crossTypeWhereFailsWithClearError() throws IOException {
        writeEmployees();
        Path query = tempDb.resolve("cross.sql");
        Files.writeString(query, "SELECT * FROM Employees WHERE Employees.salary = 'high';");
        Path out = tempDb.resolve("cross-out.csv");

        // The type-mismatch error is thrown at runtime during selection evaluation and
        // propagates through execute (which catches only IOException).
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        Operator root = QueryPlanner.parseStatement(query.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> CuckooDB.execute(root, out.toString()));
        assertTrue(ex.getMessage().contains("Type mismatch"),
                "Expected a type-mismatch error; got: " + ex.getMessage());
    }
}
