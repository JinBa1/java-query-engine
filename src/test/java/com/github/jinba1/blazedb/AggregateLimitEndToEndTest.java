package com.github.jinba1.blazedb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end tests for the SQL-surface slice: COUNT/AVG/MIN/MAX aggregates and LIMIT.
 * Drives the engine via QueryPlanner.parseStatement + BlazeDB.execute so that both
 * planner-time and runtime QueryExecutionExceptions are observable.
 */
public class AggregateLimitEndToEndTest {

    @TempDir
    Path tempDb;

    @BeforeEach
    public void setUp() {
        DBCatalog.resetDBCatalog();
    }

    private void writeTable(String name, String... lines) throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve(name + ".csv"), List.of(lines));
    }

    /** Sales: region STRING, qty INT. Two regions; east has 2 rows, west has 1. */
    private void writeSales() throws IOException {
        writeTable("Sales",
                "region,qty",
                "east,10",
                "west,7",
                "east,3");
    }

    /** Runs a SQL string end-to-end and returns output lines (header first). */
    private List<String> run(String sql) throws IOException {
        Path query = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(query, sql);
        Path out = tempDb.resolve("out-" + System.nanoTime() + ".csv");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        var root = QueryPlanner.parseStatement(query.toString());
        BlazeDB.execute(root, out.toString());
        return Files.readAllLines(out);
    }

    @Test
    public void planningErrorsPropagateToCaller() throws IOException {
        writeSales();
        Path query = tempDb.resolve("bad.sql");
        Files.writeString(query, "SELECT SUM(*) FROM Sales;");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());

        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> QueryPlanner.parseStatement(query.toString()));
        assertTrue(ex.getMessage().contains("SUM(*)"), ex.getMessage());
    }
}
