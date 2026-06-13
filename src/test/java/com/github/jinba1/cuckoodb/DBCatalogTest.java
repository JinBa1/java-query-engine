package com.github.jinba1.cuckoodb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DBCatalogTest {

    private static final String SAMPLE_DB_DIR = "samples/db";

    @TempDir
    Path tempDb;

    @BeforeEach
    void setUp() {
        DBCatalog.resetDBCatalog();
    }

    private void writeTable(String name, String... lines) throws IOException {
        Path data = tempDb.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve(name + ".csv"), List.of(lines));
    }

    @Test
    public void discoversTablesAndColumnNamesFromHeaders() throws IOException {
        writeTable("Student", "A,B,C", "1,2,3");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        Map<String, Integer> schema = DBCatalog.getInstance().getDBSchemata("Student");
        assertEquals(Map.of("a", 0, "b", 1, "c", 2), schema);
    }

    @Test
    public void allIntColumnInferredInt() throws IOException {
        writeTable("T", "A,B", "1,x", "2,y");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        assertEquals(List.of(ColumnType.INT, ColumnType.STRING),
                DBCatalog.getInstance().getColumnTypes("T"));
    }

    @Test
    public void mixedOrEmptyFieldForcesString() throws IOException {
        writeTable("T", "A,B", "1,5", "abc,"); // A mixed, B has empty field
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        assertEquals(List.of(ColumnType.STRING, ColumnType.STRING),
                DBCatalog.getInstance().getColumnTypes("T"));
    }

    @Test
    public void headerOnlyTableDefaultsToInt() throws IOException {
        writeTable("T", "A,B");
        DBCatalog.resetDBCatalog();
        DBCatalog.initDBCatalog(tempDb.toString());
        assertEquals(List.of(ColumnType.INT, ColumnType.INT),
                DBCatalog.getInstance().getColumnTypes("T"));
    }

    @Test
    public void duplicateHeaderThrows() throws IOException {
        writeTable("T", "A,A", "1,2");
        DBCatalog.resetDBCatalog();
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> DBCatalog.initDBCatalog(tempDb.toString()));
        assertTrue(e.getMessage().contains("duplicate column"));
    }

    @Test
    public void emptyFileThrows() throws IOException {
        writeTable("T");
        DBCatalog.resetDBCatalog();
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> DBCatalog.initDBCatalog(tempDb.toString()));
        assertTrue(e.getMessage().contains("no header"));
    }

    @Test
    public void raggedRowThrowsAtInit() throws IOException {
        writeTable("T", "A,B", "1,2,3");
        DBCatalog.resetDBCatalog();
        QueryExecutionException e = assertThrows(QueryExecutionException.class,
                () -> DBCatalog.initDBCatalog(tempDb.toString()));
        assertTrue(e.getMessage().contains("expected 2"));
    }

    @Test
    void testInitDBCatalogLoadsSchema() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);

        DBCatalog catalog = DBCatalog.getInstance();
        assertNotNull(catalog, "Instance should exist after init");

        assertTrue(catalog.tableExists("Student"), "Student table should exist");
        assertTrue(catalog.tableExists("Course"), "Course table should exist");
        assertTrue(catalog.tableExists("Enrolled"), "Enrolled table should exist");
    }

    @Test
    void testInitDBCatalogColumnIndices() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog catalog = DBCatalog.getInstance();

        Map<String, Integer> studentSchema = catalog.getDBSchemata("Student");
        assertNotNull(studentSchema, "Student schema should not be null");
        assertEquals(0, studentSchema.get("a"), "Column A → index 0");
        assertEquals(1, studentSchema.get("b"), "Column B → index 1");
        assertEquals(2, studentSchema.get("c"), "Column C → index 2");
        assertEquals(3, studentSchema.get("d"), "Column D → index 3");

        Map<String, Integer> courseSchema = catalog.getDBSchemata("Course");
        assertNotNull(courseSchema);
        assertEquals(0, courseSchema.get("e"));
        assertEquals(1, courseSchema.get("f"));
        assertEquals(2, courseSchema.get("g"));
    }

    @Test
    void testResetDBCatalogClearsState() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        assertNotNull(DBCatalog.getInstance());

        DBCatalog.resetDBCatalog();

        // After reset, getInstance() creates a new empty instance
        DBCatalog fresh = DBCatalog.getInstance();
        assertNotNull(fresh, "getInstance() creates new instance after reset");
        assertFalse(fresh.tableExists("Student"), "New instance should have no tables");
    }

    @Test
    void testColumnLookupByTableAndName() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog catalog = DBCatalog.getInstance();

        assertTrue(catalog.columnExists("Student", "a"));
        assertTrue(catalog.columnExists("Student", "A"), "Lookup should be case-insensitive");
        assertTrue(catalog.columnExists("Student", "b"));
        assertTrue(catalog.columnExists("Course", "e"));
        assertTrue(catalog.columnExists("Enrolled", "h"));
    }

    @Test
    void testInvalidTableNameReturnsFalse() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog catalog = DBCatalog.getInstance();

        assertFalse(catalog.tableExists("NonExistent"), "Unknown table should not exist");
        assertFalse(catalog.columnExists("NonExistent", "a"), "Column lookup on missing table → false");
    }

    @Test
    void testInvalidColumnNameReturnsFalse() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog catalog = DBCatalog.getInstance();

        assertFalse(catalog.columnExists("Student", "nonexistent"), "Unknown column → false");
        assertFalse(catalog.columnExists("Student", "xyz"), "Unknown column → false");
    }

    @Test
    void testGetDBLocation() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog catalog = DBCatalog.getInstance();

        Path studentPath = catalog.getDBLocation("Student");
        assertNotNull(studentPath, "Student data path should not be null");
        assertTrue(studentPath.toString().endsWith("Student.csv"), "Path should end with Student.csv");
        assertTrue(studentPath.toString().contains("data"), "Path should contain data directory");
    }

    @Test
    void testGetDBLocationReturnsNullForMissing() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog catalog = DBCatalog.getInstance();

        assertNull(catalog.getDBLocation("NoSuchTable"), "Missing table → null path");
    }

    @Test
    void testInitDBCatalogCalledTwiceIsNoop() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog first = DBCatalog.getInstance();

        // Second call should be no-op (instance already exists)
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog second = DBCatalog.getInstance();

        assertSame(first, second, "Second init should not replace instance");
    }

    @Test
    void testSchemaColumnCountMatches() {
        DBCatalog.initDBCatalog(SAMPLE_DB_DIR);
        DBCatalog catalog = DBCatalog.getInstance();

        Map<String, Integer> studentSchema = catalog.getDBSchemata("Student");
        assertEquals(4, studentSchema.size(), "Student has 4 columns: A, B, C, D");

        Map<String, Integer> courseSchema = catalog.getDBSchemata("Course");
        assertEquals(3, courseSchema.size(), "Course has 3 columns: E, F, G");

        Map<String, Integer> enrolledSchema = catalog.getDBSchemata("Enrolled");
        assertEquals(3, enrolledSchema.size(), "Enrolled has 3 columns: A, E, H");
    }

    @Test
    public void getInstanceIsRaceFreeAndStable() throws Exception {
        DBCatalog.resetDBCatalog();
        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var barrier = new java.util.concurrent.CyclicBarrier(threads);
            var instances = java.util.Collections.synchronizedSet(
                    new java.util.HashSet<DBCatalog>());
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    instances.add(DBCatalog.getInstance());
                    return null;
                }));
            }
            for (var f : futures) f.get();
            assertEquals(1, instances.size(), "getInstance must return one instance under races");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void loadedTableSchemaIsImmutable() throws IOException {
        DBCatalog.resetDBCatalog();
        Path dataDir = tempDb.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("T.csv"), "a,b\n1,2\n");
        DBCatalog.initDBCatalog(tempDb.toString());
        assertThrows(UnsupportedOperationException.class,
                () -> DBCatalog.getInstance().getDBSchemata("T").put("c", 9));
    }
}
