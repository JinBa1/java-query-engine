package com.github.jinba1.blazedb;

import java.io.BufferedReader;
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
 * 3. Intermediate schemas generated during query processing
 * 4. Schema transformation tracking for operations like projection and join
 * The catalog provides methods to register, retrieve, and resolve schema information,
 * supporting the dynamic schema transformations that occur during query execution.
 * It plays a critical role in column name resolution during expression evaluation
 * and is essential for query optimisations.
 */
public class DBCatalog {

    private static DBCatalog instance;

    private final Map<String, Path> dbLocations;
    private final Map<String, Map<String, Integer>> dbSchemata;

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
     * @param dBDirectory The directory containing database schema and data files
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
     * Loads database schema and location information from files in the specified directory.
     * Parses the schema.txt file to extract table and column definitions, and verifies
     * the existence of corresponding data files.
     * @param dBDirectory The directory containing database schema and data files
     */
    private void loadDBCatalog(String dBDirectory) {
        try {
            Path dBPath = Paths.get(dBDirectory);
            Path schemaPath = dBPath.resolve(Constants.SCHEMA_FILE_NAME);
            Path dataPath = dBPath.resolve(Constants.DATA_DIRECTORY_NAME);
            try (BufferedReader schemaReader = Files.newBufferedReader(schemaPath)) {
                String line;
                while ((line = schemaReader.readLine()) != null) {
                    String[] parts = line.split(Constants.SPLITTER_REGEX);
                    String tableName = parts[0];

                    Map<String, Integer> columnMap = new HashMap<>();
                    for (int i = 1; i < parts.length; i++) {
                        columnMap.put(parts[i].toLowerCase(), i - 1); //to lower case
                    }

                    dbSchemata.put(tableName, columnMap);

                    Path dataFilePath = dataPath.resolve(tableName + ".csv");
                    dbLocations.put(tableName, dataFilePath);
                }
            }

            //Verify data file exists
            if (Files.exists(dataPath) && Files.isDirectory(dataPath)) {
                try (Stream<Path> files = Files.list(dataPath)) {
                    files.forEach(file -> {
                        String fileName = file.getFileName().toString();
                        if (fileName.endsWith(".csv")) {
                            String tableName = fileName.substring(0, fileName.length() - 4);
                            if (!dbSchemata.containsKey(tableName)) {
                                System.err.println("Warning: Found data file " + fileName +
                                        " but no schema definition");
                            }
                        }
                    });
                }
            }

        } catch (Exception e) {
            System.err.println("Error loading database schema: " + e.getMessage());
            e.printStackTrace();
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

//        if (schemaId == null) {
//            System.err.println("ERROR: Failed to register intermediate schema");
//            return null;
//        }

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
