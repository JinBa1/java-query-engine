package com.github.jinba1.blazedb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

import com.github.jinba1.blazedb.operator.ScanOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.github.jinba1.blazedb.DBCatalog;
import com.github.jinba1.blazedb.Tuple;

public class ScanOperatorTest {

    private static final String TEST_DB_DIR = "src/test/resources/testdb";
    private static final String SCHEMA_FILE = TEST_DB_DIR + "/schema.txt";
    private static final String DATA_DIR = TEST_DB_DIR + "/data";
    private static final String TEST_TABLE = "TestTable";
    private static final String EMPTY_TABLE = "EmptyTable";

    @BeforeEach
    public void setUp() throws IOException {
        // Create test database directory structure
        Files.createDirectories(Paths.get(DATA_DIR));

        // Create schema file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SCHEMA_FILE))) {
            writer.write(TEST_TABLE + " A B C D\n");
            writer.write(EMPTY_TABLE + " X Y Z\n");
        }

        // Create test data file with some sample data
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_DIR + "/" + TEST_TABLE + ".csv"))) {
            writer.write("1, 10, 100, 1000\n");
            writer.write("2, 20, 200, 2000\n");
            writer.write("3, 30, 300, 3000\n");
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
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + TEST_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(DATA_DIR + "/" + EMPTY_TABLE + ".csv"));
        Files.deleteIfExists(Paths.get(SCHEMA_FILE));
        Files.deleteIfExists(Paths.get(DATA_DIR));
        Files.deleteIfExists(Paths.get(TEST_DB_DIR));
    }

    @Test
    public void testScanOperator_ReadsAllTuples() {
        // Create scan operator for test table
        ScanOperator scanOp = new ScanOperator(TEST_TABLE);

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
        ScanOperator scanOp = new ScanOperator(TEST_TABLE);

        // Get first tuple and check values
        Tuple tuple = scanOp.getNextTuple();
        assertNotNull(tuple, "First tuple should not be null");

        ArrayList<Integer> expectedValues = new ArrayList<>(Arrays.asList(1, 10, 100, 1000));
        for (int i = 0; i < expectedValues.size(); i++) {
            assertEquals(expectedValues.get(i), tuple.getAttribute(i), "Tuple value at index " + i + " should match");
        }
    }

    @Test
    public void testScanOperator_Reset() {
        // Create scan operator for test table
        ScanOperator scanOp = new ScanOperator(TEST_TABLE);

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
        ScanOperator scanOp = new ScanOperator(EMPTY_TABLE);

        // Try to read - should return null immediately
        Tuple tuple = scanOp.getNextTuple();
        assertNull(tuple, "Tuple from empty table should be null");
    }

    @Test
    public void testScanOperator_ReadAllTuplesTwice() {
        // Create scan operator for test table
        ScanOperator scanOp = new ScanOperator(TEST_TABLE);

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
        ScanOperator scanOp = new ScanOperator(TEST_TABLE);
        scanOp.close();

        // After closing, getNextTuple should gracefully handle the situation
        Tuple tuple = scanOp.getNextTuple();
        assertNull(tuple, "Tuple from closed scanner should be null");
    }
}
