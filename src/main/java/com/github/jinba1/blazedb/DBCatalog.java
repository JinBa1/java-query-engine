package com.github.jinba1.blazedb;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * The DBCatalog class serves as a central repository for durable database metadata in BlazeDB.
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

    private final Map<String, Path> dbLocations;
    private final Map<String, Map<String, Integer>> dbSchemata;
    private final Map<String, List<ColumnType>> dbColumnTypes;

    /**
     * Private constructor to ensure singleton design.
     */
    private DBCatalog() {
        dbLocations = new java.util.concurrent.ConcurrentHashMap<>();
        dbSchemata = new java.util.concurrent.ConcurrentHashMap<>();
        dbColumnTypes = new java.util.concurrent.ConcurrentHashMap<>();
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
            throw new QueryExecutionException("Data directory not found: " + dataPath);
        }
        try (Stream<Path> files = Files.list(dataPath)) {
            List<Path> csvs = files
                    .filter(f -> f.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
            for (Path csv : csvs) {
                String fileName = csv.getFileName().toString();
                String tableName = fileName.substring(0, fileName.length() - 4);
                loadTable(tableName, csv);
            }
        } catch (IOException e) {
            throw new QueryExecutionException("Error scanning data directory " + dataPath + ": " + e.getMessage());
        }
    }

    private void loadTable(String tableName, Path csv) throws IOException {
        CSVFormat format = CSVFormat.RFC4180.builder()
                .setIgnoreSurroundingSpaces(true)
                .build();
        try (CSVParser parser = CSVParser.parse(csv, StandardCharsets.UTF_8, format)) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                throw new QueryExecutionException(
                        "Table '" + tableName + "' has no header row (" + csv + ")");
            }
            CSVRecord header = it.next();
            Map<String, Integer> columnMap = new HashMap<>();
            int width = header.size();
            for (int i = 0; i < width; i++) {
                String col = header.get(i).trim().toLowerCase();
                if (columnMap.putIfAbsent(col, i) != null) {
                    throw new QueryExecutionException(
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
                    throw new QueryExecutionException("Table '" + tableName + "' row " + rowNum
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

            dbSchemata.put(tableName, Collections.unmodifiableMap(columnMap));
            dbColumnTypes.put(tableName, Collections.unmodifiableList(types));
            dbLocations.put(tableName, csv);
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
     * Returns the file path for a specified table.
     * @param tableName The name of the table
     * @return The Path object representing the table's data file location
     */
    public Path getDBLocation(String tableName) {
        return dbLocations.get(tableName);
    }

    /**
     * Returns the schema mapping for a specified table.
     * The mapping associates column names with their positions in the table.
     * @param tableName The name of the table
     * @return A map from column names to their positions (indices)
     */
    public Map<String, Integer> getDBSchemata(String tableName) {
        return dbSchemata.get(tableName);
    }

    /**
     * Returns the column types for a base table, in column order.
     * @param tableName The name of the table
     * @return A list of ColumnType values in column order, or null if table not found
     */
    public List<ColumnType> getColumnTypes(String tableName) {
        return dbColumnTypes.get(tableName);
    }

    /**
     * Checks if a specified table exists in the database.
     * @param tableName The name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean tableExists(String tableName) {
        return (dbLocations.containsKey(tableName) && dbSchemata.containsKey(tableName));
    }

    /**
     * Checks if a specified column exists in a table.
     * @param tableName The name of the table
     * @param columnName The name of the column
     * @return true if the column exists in the table, false otherwise
     */
    public boolean columnExists(String tableName, String columnName) {
        if (!tableExists(tableName)) {
            return false;
        }
        return dbSchemata.get(tableName).containsKey(columnName.toLowerCase());
    }
}
