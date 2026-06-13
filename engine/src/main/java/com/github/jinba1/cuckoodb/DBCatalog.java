package com.github.jinba1.cuckoodb;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The DBCatalog class serves as a central repository for durable database metadata in CuckooDB.
 * It implements the singleton pattern to ensure a single, consistent view of database structure
 * across all components of the system.
 * This class maintains information about:
 * 1. Database table locations on disk
 * 2. Table schemas (column names and their positions)
 * 3. Column types inferred from CSV data
 * Per-query schema tracking (intermediate schemas, transformation history, column origins)
 * lives in {@link PlanContext}, not here.
 * The durable maps are backed by ConcurrentHashMap so that a future REST server can upload
 * tables at runtime while N queries read concurrently without external locking.
 */
public class DBCatalog {

    private static DBCatalog instance;

    /**
     * One entry per table, each a fully-populated {@link TableMeta} (location + schema +
     * types). A single map means a table is published with one atomic write and read with
     * one atomic lookup — no torn state across what used to be three parallel maps. This is
     * what makes runtime {@link #registerTable} safe against concurrent readers.
     */
    private final Map<String, TableMeta> tables;

    /**
     * Private constructor to ensure singleton design.
     */
    private DBCatalog() {
        tables = new ConcurrentHashMap<>();
    }

    /**
     * Returns the singleton instance of DBCatalog.
     * Creates a new instance if one does not already exist.
     * @return The singleton DBCatalog instance
     */
    public static synchronized DBCatalog getInstance() {
        if (instance == null) {
            instance = new DBCatalog();
            System.out.println("Created DBCatalog, but haven't load content, use initDBCatalog() instead");
        }
        return instance;
    }

    /**
     * Initializes the database catalog with schema information from the specified directory.
     * This method should be called before using the catalog for the first time.
     * @param dBDirectory The directory containing database data files
     */
    public static synchronized void initDBCatalog(String dBDirectory) {
        if (instance == null) {
            instance = new DBCatalog();
            instance.loadDBCatalog(dBDirectory);
        }
    }

    /**
     * Resets the DBCatalog instance to null, effectively clearing all stored information.
     * Primarily used for testing or when switching database contexts.
     */
    public static synchronized void resetDBCatalog() {
        instance = null;
    }

    /**
     * Loads database schema and location information by scanning CSV files in the data directory.
     * Column names are read from the CSV header row; column types are inferred from the data rows.
     * @param dBDirectory The directory containing the data subdirectory with CSV files
     */
    private void loadDBCatalog(String dBDirectory) {
        Path dataPath = Paths.get(dBDirectory).resolve(Constants.DATA_DIRECTORY_NAME);
        if (!Files.isDirectory(dataPath)) {
            throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                    "Data directory not found: " + dataPath);
        }
        try (Stream<Path> files = Files.list(dataPath)) {
            List<Path> csvs = files
                    .filter(f -> f.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
            for (Path csv : csvs) {
                String fileName = csv.getFileName().toString();
                String tableName = fileName.substring(0, fileName.length() - 4);
                tables.put(tableName, parseTable(tableName, csv));
            }
        } catch (IOException e) {
            throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                    "Error scanning data directory " + dataPath + ": " + e.getMessage());
        }
    }

    /**
     * Registers a single table at runtime from a CSV file, for callers (the REST gateway)
     * that add tables after startup. Parses and infers the schema with the same rules as
     * directory load, then publishes the table with one atomic {@code putIfAbsent}.
     * <p>
     * Returns {@code false} (and leaves the existing table untouched) when a table of that
     * name already exists — the caller's 409 signal. Never {@code containsKey}-then-{@code put},
     * which would reintroduce the race this design removes.
     * @param tableName the catalog name to publish under
     * @param csv       the CSV file backing the table; must outlive every query that scans it
     * @return {@code true} if this call registered the table; {@code false} if the name was taken
     * @throws QueryExecutionException with {@link ErrorCode#DATA_ERROR} if the CSV is malformed
     */
    public boolean registerTable(String tableName, Path csv) {
        TableMeta meta;
        try {
            meta = parseTable(tableName, csv);
        } catch (IOException e) {
            throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                    "Could not read table '" + tableName + "' from " + csv + ": " + e.getMessage());
        }
        return tables.putIfAbsent(tableName, meta) == null;
    }

    /**
     * Parses one CSV into table metadata: column names from the header row, types inferred
     * from the data rows. Pure (no map writes) so both directory load and {@link #registerTable}
     * share identical parse-and-infer rules. The returned schema and types are unmodifiable.
     * <p>Type inference starts every column as INT and demotes to STRING on the first
     * non-integer field, so a <em>header-only</em> CSV (no data rows) infers every column as
     * INT — there is no evidence to refute INT. REST callers uploading a schema-only file get
     * an all-INT table until rows arrive.
     */
    private static TableMeta parseTable(String tableName, Path csv) throws IOException {
        CSVFormat format = CSVFormat.RFC4180.builder()
                .setIgnoreSurroundingSpaces(true)
                .build();
        try (CSVParser parser = CSVParser.parse(csv, StandardCharsets.UTF_8, format)) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                        "Table '" + tableName + "' has no header row (" + csv + ")");
            }
            CSVRecord header = it.next();
            Map<String, Integer> columnMap = new HashMap<>();
            int width = header.size();
            for (int i = 0; i < width; i++) {
                String col = header.get(i).trim().toLowerCase();
                if (columnMap.putIfAbsent(col, i) != null) {
                    throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                            "Table '" + tableName + "' has duplicate column '" + col + "' in header");
                }
            }

            boolean[] isInt = new boolean[width];
            Arrays.fill(isInt, true);
            long rowNum = 1;
            while (it.hasNext()) {
                CSVRecord record = it.next();
                rowNum++;
                if (record.size() != width) {
                    throw new QueryExecutionException(ErrorCode.DATA_ERROR,
                            "Table '" + tableName + "' row " + rowNum
                            + ": expected " + width + " fields, found " + record.size());
                }
                for (int i = 0; i < width; i++) {
                    if (isInt[i] && !parsesAsInt(record.get(i))) {
                        isInt[i] = false;
                    }
                }
            }

            List<ColumnType> types = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                types.add(isInt[i] ? ColumnType.INT : ColumnType.STRING);
            }

            return new TableMeta(csv,
                    Collections.unmodifiableMap(columnMap),
                    Collections.unmodifiableList(types));
        }
    }

    private static boolean parsesAsInt(String field) {
        if (field == null || field.isEmpty()) return false;
        try {
            Integer.parseInt(field);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Returns the full metadata for a table in one atomic lookup, or null if absent.
     * Callers that need more than one of location/schema/types (e.g. ScanOperator) use
     * this so the fields they read are guaranteed to come from the same registration.
     * @param tableName The name of the table
     * @return The table's metadata, or null if the table is not in the catalog
     */
    public TableMeta getTableMeta(String tableName) {
        return tables.get(tableName);
    }

    /**
     * Returns the file path for a specified table.
     * @param tableName The name of the table
     * @return The Path object representing the table's data file location
     */
    public Path getDBLocation(String tableName) {
        TableMeta meta = tables.get(tableName);
        return meta == null ? null : meta.path();
    }

    /**
     * Returns the schema mapping for a specified table.
     * The mapping associates column names with their positions in the table.
     * @param tableName The name of the table
     * @return A map from column names to their positions (indices)
     */
    public Map<String, Integer> getDBSchemata(String tableName) {
        TableMeta meta = tables.get(tableName);
        return meta == null ? null : meta.schema();
    }

    /**
     * Returns the column types for a base table, in column order.
     * @param tableName The name of the table
     * @return A list of ColumnType values in column order, or null if table not found
     */
    public List<ColumnType> getColumnTypes(String tableName) {
        TableMeta meta = tables.get(tableName);
        return meta == null ? null : meta.types();
    }

    /**
     * Returns all table names in the catalog, sorted — for agent-legible
     * "Available tables: ..." error messages.
     * @return The sorted list of loaded table names
     */
    public List<String> getTableNames() {
        List<String> names = new ArrayList<>(tables.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * Builds the standard unknown-table failure, listing what is available so an
     * agent caller can self-correct. One construction site keeps the wording from
     * drifting between the FROM-clause and WHERE-clause detection paths.
     * @param tableName The table name that failed to resolve
     * @return The exception to throw; never null
     */
    public QueryExecutionException unknownTable(String tableName) {
        return new QueryExecutionException(ErrorCode.UNKNOWN_TABLE,
                "Table '" + tableName + "' not found. Available tables: "
                + String.join(", ", getTableNames()) + ".");
    }

    /**
     * Checks if a specified table exists in the database.
     * @param tableName The name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean tableExists(String tableName) {
        return tables.containsKey(tableName);
    }

    /**
     * Checks if a specified column exists in a table.
     * @param tableName The name of the table
     * @param columnName The name of the column
     * @return true if the column exists in the table, false otherwise
     */
    public boolean columnExists(String tableName, String columnName) {
        TableMeta meta = tables.get(tableName);
        if (meta == null) {
            return false;
        }
        return meta.schema().containsKey(columnName.toLowerCase());
    }
}
