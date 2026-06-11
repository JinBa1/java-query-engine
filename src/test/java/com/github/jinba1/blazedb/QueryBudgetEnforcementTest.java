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
 * End-to-end budget enforcement through the CLI entry (BlazeDB.run — main minus System.exit).
 */
public class QueryBudgetEnforcementTest {

    @TempDir
    Path tempDb;

    @BeforeEach
    public void setUp() throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("L.csv"), List.of("a", "1", "2", "3", "4", "5"));
        Files.write(data.resolve("R.csv"), List.of("b", "1", "2", "3", "4", "5"));
        DBCatalog.resetDBCatalog();
    }

    private Path writeQuery(String sql) throws IOException {
        Path q = tempDb.resolve("q-" + System.nanoTime() + ".sql");
        Files.writeString(q, sql);
        return q;
    }

    @Test
    public void budgetKillDeletesPartialOutputAndReturnsNonZero() throws IOException {
        Path query = writeQuery("SELECT * FROM L, R;");
        Path out = tempDb.resolve("out.csv");

        int code = BlazeDB.run(new String[]{
                tempDb.toString(), query.toString(), out.toString(), "--max-tuples=10"});

        assertEquals(1, code);
        assertFalse(Files.exists(out), "partial output must be deleted on budget kill");
    }

    @Test
    public void timeBudgetKills() throws IOException {
        Path query = writeQuery("SELECT * FROM L, R;");
        Path out = tempDb.resolve("out-t.csv");

        int code = BlazeDB.run(new String[]{
                tempDb.toString(), query.toString(), out.toString(), "--timeout-ms=0"});

        assertEquals(1, code);
        assertFalse(Files.exists(out));
    }

    @Test
    public void generousBudgetLetsQueryComplete() throws IOException {
        Path query = writeQuery("SELECT * FROM L, R;");
        Path out = tempDb.resolve("out-ok.csv");

        int code = BlazeDB.run(new String[]{
                tempDb.toString(), query.toString(), out.toString(),
                "--max-tuples=1000", "--timeout-ms=60000"});

        assertEquals(0, code);
        assertEquals(26, Files.readAllLines(out).size()); // header + 25 cross-product rows
    }

    @Test
    public void noFlagsMeansUnlimited() throws IOException {
        Path query = writeQuery("SELECT * FROM L, R;");
        Path out = tempDb.resolve("out-free.csv");

        int code = BlazeDB.run(new String[]{tempDb.toString(), query.toString(), out.toString()});

        assertEquals(0, code);
        assertEquals(26, Files.readAllLines(out).size());
    }

    @Test
    public void malformedFlagFailsBeforeExecution() throws IOException {
        Path query = writeQuery("SELECT * FROM L;");
        Path out = tempDb.resolve("out-bad.csv");

        assertEquals(1, BlazeDB.run(new String[]{
                tempDb.toString(), query.toString(), out.toString(), "--max-tuples=abc"}));
        assertEquals(1, BlazeDB.run(new String[]{
                tempDb.toString(), query.toString(), out.toString(), "--frobnicate=1"}));
        assertEquals(1, BlazeDB.run(new String[]{
                tempDb.toString(), query.toString(), out.toString(), "--max-tuples=-5"}));
        assertFalse(Files.exists(out));
    }

    @Test
    public void unparseableQueryFailsGracefully() throws IOException {
        Path query = writeQuery("THIS IS NOT SQL;");
        Path out = tempDb.resolve("out-garbage.csv");

        int code = BlazeDB.run(new String[]{tempDb.toString(), query.toString(), out.toString()});

        assertEquals(1, code);
        assertFalse(Files.exists(out));
    }
}
