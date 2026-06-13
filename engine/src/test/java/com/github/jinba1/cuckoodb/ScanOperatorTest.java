package com.github.jinba1.cuckoodb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.jinba1.cuckoodb.operator.ScanOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.jinba1.cuckoodb.DBCatalog;
import com.github.jinba1.cuckoodb.Tuple;

public class ScanOperatorTest {

    @TempDir
    Path tempDb;

    private PlanContext ctx;

    private void writeTable(String name, String... lines) throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve(name + ".csv"), List.of(lines));
    }

    private static final String TEST_DB_DIR = "src/test/resources/testdb";
    private static final String DATA_DIR = TEST_DB_DIR + "/data";
    private static final String TEST_TABLE = "TestTable";
    private static final String EMPTY_TABLE = "EmptyTable";

    @BeforeEach
    public void setUp() throws IOException {
        // Create test database directory structure
        Files.createDirectories(Paths.get(DATA_DIR));

        // Create test data file with some sample data
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + TEST_TABLE + ".csv"))) {
            writer.write("A,B,C,D\n");
            writer.write("1,10,100,1000\n");
            writer.write("2,20,200,2000\n");
            writer.write("3,30,300,3000\n");
        }

        // Create empty table file (header row required by catalog)
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + EMPTY_TABLE + ".csv"))) {
            writer.write("X,Y,Z\n");
        }

        // Initialize the database catalog
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(TEST_DB_DIR);
        ctx = new PlanContext(QueryConfig.defaults());
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Clean up test files
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + TEST_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + EMPTY_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR));
        Files.deleteIfExists(Paths.get(TEST_DB_DIR));
    }

    @Test
    public void testScanOperator_ReadsAllTuples() {
        // Create scan operator for test table
        ScanOperator scanOp = new ScanOperator(ctx, TEST_TABLE);

        // Read all tuples and verify count
        int count = 0;
        Tuple tuple;
        while ((tuple = scanOp.getNextTuple()) != null) {
            count++;
        }

        assertEquals(3, count, "Should read 3 tuples from test table");
    }

    @Test
    public void testScanOperator_TupleValues() {
        // Create scan operator for test table
        ScanOperator scanOp = new ScanOperator(ctx, TEST_TABLE);

        // Get first tuple and check values
        Tuple tuple = scanOp.getNextTuple();
        assertNotNull(tuple, "First tuple should not be null");

        List<Value> expectedValues = TestTuples.ints(1, 10, 100, 1000);
        for (int i = 0; i < expectedValues.size(); i++) {
            assertEquals(expectedValues.get(i), tuple.getAttribute(i), "Tuple value at index " + i + " should match");
        }
    }

    @Test
    public void testScanOperator_Reset() {
        // Create scan operator for test table
        ScanOperator scanOp = new ScanOperator(ctx, TEST_TABLE);

        // Read first tuple
        Tuple firstTuple = scanOp.getNextTuple();
        assertNotNull(firstTuple, "First tuple should not be null");

        // Read second tuple
        Tuple secondTuple = scanOp.getNextTuple();
        assertNotNull(secondTuple, "Second tuple should not be null");

        // Reset scanner
        scanOp.reset();

        // Read tuple again after reset - should be the first tuple
        Tuple afterReset = scanOp.getNextTuple();
        assertNotNull(afterReset, "First tuple after reset should not be null");

        // Compare values of first tuple and tuple after reset
        for (int i = 0; i < firstTuple.getTuple().size(); i++) {
            assertEquals(firstTuple.getAttribute(i), afterReset.getAttribute(i), "Value at index " + i + " should match after reset");
        }
    }

    @Test
    public void testScanOperator_EmptyTable() {
        // Create scan operator for empty table
        ScanOperator scanOp = new ScanOperator(ctx, EMPTY_TABLE);

        // Try to read - should return null immediately
        Tuple tuple = scanOp.getNextTuple();
        assertNull(tuple, "Tuple from empty table should be null");
    }

    @Test
    public void testScanOperator_ReadAllTuplesTwice() {
        // Create scan operator for test table
        ScanOperator scanOp = new ScanOperator(ctx, TEST_TABLE);

        // Read all tuples first time
        int count1 = 0;
        while (scanOp.getNextTuple() != null) {
            count1++;
        }

        // Reset scanner
        scanOp.reset();

        // Read all tuples second time
        int count2 = 0;
        while (scanOp.getNextTuple() != null) {
            count2++;
        }

        // Both counts should be the same
        assertEquals(count1, count2, "Count after reset should match original count");
    }

    @Test
    public void testScanOperator_ResourceClosure() {
        // Create and immediately close scan operator
        ScanOperator scanOp = new ScanOperator(ctx, TEST_TABLE);
        scanOp.close();

        // After closing, getNextTuple should gracefully handle the situation
        Tuple tuple = scanOp.getNextTuple();
        assertNull(tuple, "Tuple from closed scanner should be null");
    }

    @Test
    public void scansTypedValuesSkippingHeader() throws IOException {
        writeTable("People", "id,name", "1,alice", "2,\"smith, bob\"");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        ScanOperator scan = new ScanOperator(ctx, "People");
        assertEquals(new Tuple(List.of(new IntValue(1), new StringValue("alice"))), scan.getNextTuple());
        assertEquals(new Tuple(List.of(new IntValue(2), new StringValue("smith, bob"))), scan.getNextTuple());
        assertNull(scan.getNextTuple());
        scan.close();
    }

    @Test
    public void resetRestartsAfterHeader() throws IOException {
        writeTable("T", "a", "7");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        ScanOperator scan = new ScanOperator(ctx, "T");
        assertEquals(new IntValue(7), scan.getNextTuple().getAttribute(0));
        scan.reset();
        assertEquals(new IntValue(7), scan.getNextTuple().getAttribute(0));
        scan.close();
    }
}
