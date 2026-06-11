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
 * The DBCatalog class serves as a central repository for database metadata in BlazeDB.
 * It implements the singleton pattern to ensure a single, consistent view of database structure
 * across all components of the system.
 * This class maintains information about:
 * 1. Database table locations on disk
 * 2. Table schemas (column names and their positions)
 * 3. Column types inferred from CSV data
 * 4. Intermediate schemas generated during query processing
 * 5. Schema transformation tracking for operations like projection and join
 * The catalog provides methods to register, retrieve, and resolve schema information,
 * supporting the dynamic schema transformations that occur during query execution.
 * It plays a critical role in column name resolution during expression evaluation
 * and is essential for query optimisations.
 */
public class DBCatalog {

    private static DBCatalog instance;

    private final Map<String, Path> dbLocations;
    private final Map<String, Map<String, Integer>> dbSchemata;
    private final Map<String, List<ColumnType>> dbColumnTypes;

    private final Map<String, Map<String, Integer>> intermediateSchemata;

    // Add schema transformation tracking
    private final Map<String, String> schemaParentMap; // child schema ID -> parent schema ID

    private final Map<String, List<String>> schemaMultiParentMap;

    private final Map<String, Map<String, String>> columnOriginMap;

    /**
     * Private constructor to ensure singleton design.
     */
    private DBCatalog() {
        dbLocations = new HashMap<>();
        dbSchemata = new HashMap<>();
        dbColumnTypes = new HashMap<>();
        intermediateSchemata = new HashMap<>();
        schemaParentMap = new HashMap<>();
        schemaMultiParentMap = new HashMap<>();
        columnOriginMap = new HashMap<>();
    }

    /**
     * Returns the singleton instance of DBCatalog.
     * Creates a new instance if one does not already exist.
     * @return The singleton DBCatalog instance
     */
    public static DBCatalog getInstance() {
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
    public static void initDBCatalog(String dBDirectory) {
        if (instance == null) {
            instance = new DBCatalog();
            instance.loadDBCatalog(dBDirectory);
        }
    }

    /**
     * Resets the DBCatalog instance to null, effectively clearing all stored information.
     * Primarily used for testing or when switching database contexts.
     */
    public static void resetDBCatalog() {
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

            dbSchemata.put(tableName, columnMap);
            dbColumnTypes.put(tableName, types);
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

    /**
     * Helper method for registering schema transformation.
     * Registers an intermediate schema created during query processing.
     * Intermediate schemas are used by operators like Project and Join
     * that transform the structure of input data.
     * @param schema A map representing the new schema structure
     * @return A unique identifier for the registered schema
     */
    private String registerIntermediateSchema(Map<String, Integer> schema) {
        String schemaId = Constants.INTERMEDIATE_SCHEMA_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        intermediateSchemata.put(schemaId, schema);
        return schemaId;
    }

    /**
     * Retrieves an intermediate schema by its identifier.
     * @param schemaId The unique identifier of the intermediate schema
     * @return The schema mapping, or null if the schema ID is not found
     */
    public Map<String, Integer> getIntermediateSchema(String schemaId) {
        if (!intermediateSchemata.containsKey(schemaId)) {
            return null;
        }
        return intermediateSchemata.get(schemaId);
    }

    /**
     * Registers a schema with transformation information.
     * This enhanced registration tracks how the schema was derived from parent schemas,
     * which is useful for column resolution and optimization.
     * @param schema The schema mapping
     * @param parentSchemaId The ID of the parent schema, or null if none
     * @param type The type of transformation (projection, join, etc.)
     * @param transformationDetails Details about the transformation
     * @return A unique identifier for the registered schema
     */
    public String registerSchemaWithTransformation(Map<String, Integer> schema,
                                                   String parentSchemaId,
                                                   SchemaTransformationType type,
                                                   Map<String, String> transformationDetails) {
        String schemaId = registerIntermediateSchema(schema);

        // Record parent relationship
        if (parentSchemaId != null) {
            schemaParentMap.put(schemaId, parentSchemaId);
        }

        // Track column origins for the new schema
        Map<String, String> originMap = new HashMap<>();
        columnOriginMap.put(schemaId, originMap);

        // For each column in the new schema, record its origin
        for (Map.Entry<String, String> detail : transformationDetails.entrySet()) {
            if (detail.getKey().contains(".")) {
                originMap.put(detail.getKey(), detail.getKey()); // Self-reference for existing qualified names
            }
        }

        return schemaId;
    }

    /**
     * Returns the parent schema ID for a given schema.
     * @param schemaId The schema ID to look up
     * @return The parent schema ID, or null if none exists
     */
    public String getParentSchemaId(String schemaId) {
        return schemaParentMap.get(schemaId);
    }

    /**
     * Adds a parent schema relationship.
     * Used for operators with multiple inputs like JOIN.
     * @param childSchemaId The child schema ID
     * @param parentSchemaId The parent schema ID to add
     */
    public void addParentSchema(String childSchemaId, String parentSchemaId) {
        schemaMultiParentMap.computeIfAbsent(childSchemaId, k -> new ArrayList<>())
                .add(parentSchemaId);
    }

    /**
     * Returns all parent schemas for a given schema.
     * @param schemaId The schema ID to look up
     * @return A list of parent schema IDs
     */
    public List<String> getAllParentSchemas(String schemaId) {
        return schemaMultiParentMap.getOrDefault(schemaId, Collections.emptyList());
    }

    /**
     * Gets a schema map by ID, handling both base and intermediate schemas.
     * @param schemaId The schema ID to retrieve
     * @return The schema mapping
     */
    private Map<String, Integer> getSchema(String schemaId) {
        if (schemaId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
            return intermediateSchemata.get(schemaId);
        } else {
            return dbSchemata.get(schemaId);
        }
    }

    /**
     * Result-header names for a schema, in column order.
     * Plain columns are bare-ified (qualifier stripped: "student.a" -> "a");
     * aggregate keys (containing '(') are kept whole, lowercased.
     * Schemas may contain alias keys (bare and qualified forms mapping to the
     * same index), so column count is max index + 1, not map size; keys are
     * visited in sorted order so the chosen name is deterministic.
     */
    public List<String> getOrderedColumnNames(String schemaId) {
        Map<String, Integer> schema = getSchema(schemaId);
        if (schema == null) {
            throw new QueryExecutionException("Unknown schema: " + schemaId);
        }
        int width = 0;
        for (Integer idx : schema.values()) {
            if (idx + 1 > width) width = idx + 1;
        }
        String[] names = new String[width];
        List<String> keys = new ArrayList<>(schema.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            int idx = schema.get(key);
            String name = key.toLowerCase();
            if (!name.contains("(")) {
                int dot = name.lastIndexOf('.');
                if (dot >= 0) name = name.substring(dot + 1);
            }
            if (names[idx] == null) {
                names[idx] = name;
            }
        }
        for (int i = 0; i < width; i++) {
            if (names[i] == null) {
                throw new QueryExecutionException(
                        "Schema '" + schemaId + "' has no column name for index " + i);
            }
        }
        return Arrays.asList(names);
    }

    /**
     * Column resolution that considers origin tracking information.
     * This method first tries direct resolution with smartResolveColumnIndex,
     * then attempts resolution through origin tracking, and finally tries
     * parent schemas recursively.
     * @param schemaId The schema ID to start resolution from
     * @param tableName The table name in the column reference
     * @param columnName The column name to resolve
     * @return The resolved column index, or null if not found
     */
    public Integer resolveColumnWithOrigins(String schemaId, String tableName, String columnName) {
        // Try direct resolution first
        Integer directIndex = smartResolveColumnIndex(schemaId, tableName, columnName);
        if (directIndex != null) return directIndex;

        // Try resolving through origin tracking
        Map<String, String> originMap = columnOriginMap.get(schemaId);
        if (originMap != null) {
            String lookupKey = tableName + "." + columnName.toLowerCase();

            // Look for any column that maps to this original name
            for (Map.Entry<String, String> entry : originMap.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(lookupKey)) {
                    return getIntermediateSchema(schemaId).get(entry.getKey());
                }
            }
        }

        // Try parent schemas if needed
        List<String> parents = getAllParentSchemas(schemaId);
        for (String parent : parents) {
            Integer parentResult = resolveColumnWithOrigins(parent, tableName, columnName);
            if (parentResult != null) return parentResult;
        }

        return null;
    }

    /**
     * Helper class for resolveColumnWithOrigins.
     * Resolves a column index in a schema using a simple lookup strategy.
     * First attempts to find the column with a fully qualified name (table.column),
     * then falls back to an unqualified lookup.
     * @param schemaId The schema ID to search in
     * @param tableName The table name in the column reference
     * @param columnName The column name to resolve
     * @return The column index, or null if not found
     */
    private Integer smartResolveColumnIndex(String schemaId, String tableName, String columnName) {
        DBCatalog catalog = DBCatalog.getInstance();
        Map<String, Integer> schema = catalog.getSchema(schemaId);
        if (schema == null) return null;

        // Try qualified name first
        String qualifiedKey = tableName + "." + columnName.toLowerCase();
        Integer index = schema.get(qualifiedKey);

        // If not found, try just the column name
        if (index == null) {
            index = schema.get(columnName.toLowerCase());
        }

        return index;
    }
}
