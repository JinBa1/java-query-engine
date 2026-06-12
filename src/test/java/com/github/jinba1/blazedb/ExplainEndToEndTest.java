package com.github.jinba1.blazedb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ExplainEndToEndTest {

    @TempDir
    Path tempDb;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("Sales.csv"), List.of("region,qty", "east,10", "west,7"));
        Files.write(data.resolve("Reps.csv"), List.of("region,name", "east,alice", "west,bob"));
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
    }

    private PlannedQuery planFile(String sql) throws IOException {
        Path q = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(q, sql);
        return QueryPlanner.planQuery(q.toString());
    }

    @Test
    public void normalQueryHasNoExplainText() throws IOException {
        PlannedQuery planned = planFile("SELECT * FROM Sales;");
        assertNull(planned.explainText());
        assertNotNull(planned.root());
    }

    @Test
    public void explainProducesBothSectionsAndShowsPushdown() throws IOException {
        PlannedQuery planned = planFile(
                "EXPLAIN SELECT Reps.name FROM Sales, Reps "
                        + "WHERE Sales.region = Reps.region AND Sales.qty > 5;");
        String text = planned.explainText();
        assertNotNull(text);

        assertTrue(text.startsWith("=== Plan (as written) ===\n"), text);
        assertTrue(text.contains("=== Plan (optimized) ===\n"), text);

        String[] parts = text.split("=== Plan \\(optimized\\) ===\n");
        String before = parts[0];
        String after = parts[1];
        // pushdown: the single-table predicate sits above the join before optimization,
        // below it (or merged) after — the two trees must differ
        assertNotEquals(before.replace("=== Plan (as written) ===\n", "").trim(), after.trim());
        assertTrue(after.contains("Scan[Sales]"), text);
        assertTrue(after.contains("Join["), text);

        // the optimized root is still executable
        assertNotNull(planned.root());
    }

    @Test
    public void parseStatementContractUnchanged() throws IOException {
        Path q = tempDb.resolve("plain.sql");
        Files.writeString(q, "SELECT * FROM Sales;");
        assertNotNull(QueryPlanner.parseStatement(q.toString()));
    }

    @Test
    public void explainOfInvalidQueryStillValidates() throws IOException {
        Path q = tempDb.resolve("bad.sql");
        Files.writeString(q, "EXPLAIN SELECT Sales.region, COUNT(*) FROM Sales;");
        assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.planQuery(q.toString()));
    }

    @Test
    public void cliWritesExplainTextToOutputFile() throws IOException {
        Path q = tempDb.resolve("explain.sql");
        Files.writeString(q, "EXPLAIN SELECT * FROM Sales WHERE Sales.qty > 5;");
        Path out = tempDb.resolve("explain-out.txt");

        int code = BlazeDB.run(new String[]{tempDb.toString(), q.toString(), out.toString()});

        assertEquals(0, code);
        String text = Files.readString(out);
        assertTrue(text.startsWith("=== Plan (as written) ===\n"), text);
        assertTrue(text.contains("=== Plan (optimized) ===\n"), text);
        assertTrue(text.contains("Scan[Sales]"), text);
    }

    @Test
    public void cliCreatesMissingOutputDirectoryForExplain() throws IOException {
        // execute() creates missing parent dirs for data queries; EXPLAIN must match
        Path q = tempDb.resolve("explain.sql");
        Files.writeString(q, "EXPLAIN SELECT * FROM Sales WHERE Sales.qty > 5;");
        Path out = tempDb.resolve("nested").resolve("dir").resolve("explain-out.txt");

        int code = BlazeDB.run(new String[]{tempDb.toString(), q.toString(), out.toString()});

        assertEquals(0, code);
        assertTrue(Files.readString(out).contains("Scan[Sales]"));
    }
}
