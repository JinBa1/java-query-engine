package com.github.jinba1.cuckoodb;

import java.util.*;

/**
 * Per-query planning and execution context. Owns the intermediate-schema registry
 * that {@link DBCatalog} used to hold globally, plus the query's {@link QueryConfig}.
 * One instance is created per planned query and dies with the plan, so intermediate
 * schemas no longer accumulate across queries and concurrent queries cannot collide.
 *
 * Base-table lookups (schema IDs without the {@link Constants#INTERMEDIATE_SCHEMA_PREFIX}
 * prefix) delegate to the durable {@link DBCatalog} singleton.
 */
public class PlanContext {

    private final QueryConfig config;

    private final Map<String, Map<String, Integer>> intermediateSchemata = new HashMap<>();
    private final Map<String, String> schemaParentMap = new HashMap<>();
    private final Map<String, List<String>> schemaMultiParentMap = new HashMap<>();
    private final Map<String, Map<String, String>> columnOriginMap = new HashMap<>();

    public PlanContext(QueryConfig config) {
        this.config = Objects.requireNonNull(config, "QueryConfig must not be null");
    }

    public QueryConfig config() {
        return config;
    }

    // ---- registry (ported from DBCatalog.registerIntermediateSchema /
    //      registerSchemaWithTransformation / getIntermediateSchema /
    //      getParentSchemaId / addParentSchema / getAllParentSchemas) ----

    private String registerIntermediateSchema(Map<String, Integer> schema) {
        String schemaId = Constants.INTERMEDIATE_SCHEMA_PREFIX
                + UUID.randomUUID().toString().substring(0, 8);
        intermediateSchemata.put(schemaId, schema);
        return schemaId;
    }

    public String registerSchemaWithTransformation(Map<String, Integer> schema,
                                                   String parentSchemaId,
                                                   SchemaTransformationType type,
                                                   Map<String, String> transformationDetails) {
        String schemaId = registerIntermediateSchema(schema);
        if (parentSchemaId != null) {
            schemaParentMap.put(schemaId, parentSchemaId);
        }
        Map<String, String> originMap = new HashMap<>();
        columnOriginMap.put(schemaId, originMap);
        for (Map.Entry<String, String> detail : transformationDetails.entrySet()) {
            if (detail.getKey().contains(".")) {
                originMap.put(detail.getKey(), detail.getKey());
            }
        }
        return schemaId;
    }

    public Map<String, Integer> getIntermediateSchema(String schemaId) {
        return intermediateSchemata.get(schemaId);
    }

    public String getParentSchemaId(String schemaId) {
        return schemaParentMap.get(schemaId);
    }

    public void addParentSchema(String childSchemaId, String parentSchemaId) {
        schemaMultiParentMap.computeIfAbsent(childSchemaId, k -> new ArrayList<>())
                .add(parentSchemaId);
    }

    public List<String> getAllParentSchemas(String schemaId) {
        return schemaMultiParentMap.getOrDefault(schemaId, Collections.emptyList());
    }

    // ---- lookup / resolution (ported from DBCatalog.getSchema /
    //      smartResolveColumnIndex / resolveColumnWithOrigins / getOrderedColumnNames) ----

    /** Schema map for an ID: intermediate from this context, base table from DBCatalog. */
    public Map<String, Integer> getSchema(String schemaId) {
        if (schemaId.startsWith(Constants.INTERMEDIATE_SCHEMA_PREFIX)) {
            return intermediateSchemata.get(schemaId);
        }
        return DBCatalog.getInstance().getDBSchemata(schemaId);
    }

    public Integer resolveColumnWithOrigins(String schemaId, String tableName, String columnName) {
        Integer directIndex = smartResolveColumnIndex(schemaId, tableName, columnName);
        if (directIndex != null) return directIndex;

        Map<String, String> originMap = columnOriginMap.get(schemaId);
        if (originMap != null) {
            String lookupKey = tableName + "." + columnName.toLowerCase();
            for (Map.Entry<String, String> entry : originMap.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(lookupKey)) {
                    return getIntermediateSchema(schemaId).get(entry.getKey());
                }
            }
        }

        for (String parent : getAllParentSchemas(schemaId)) {
            Integer parentResult = resolveColumnWithOrigins(parent, tableName, columnName);
            if (parentResult != null) return parentResult;
        }
        return null;
    }

    private Integer smartResolveColumnIndex(String schemaId, String tableName, String columnName) {
        Map<String, Integer> schema = getSchema(schemaId);
        if (schema == null) return null;
        String qualifiedKey = tableName + "." + columnName.toLowerCase();
        Integer index = schema.get(qualifiedKey);
        if (index == null) {
            index = schema.get(columnName.toLowerCase());
        }
        return index;
    }

    /**
     * Comma-separated resolvable column names of a schema, in column order — for
     * agent-legible error messages. Alias keys mapping to the same index are all
     * listed, because each is a name the caller may legally write.
     */
    public String availableColumns(String schemaId) {
        return formatColumns(getSchema(schemaId));
    }

    /**
     * Builds the standard unknown-column failure for a schema lookup miss, listing
     * what is available so an agent caller can self-correct. One construction site
     * keeps the wording aligned across projection, sort, aggregate, and evaluation.
     * @param tableName  The table qualifier as written in the query
     * @param columnName The column name that failed to resolve
     * @param schemaId   The schema the lookup ran against
     * @return The exception to throw; never null
     */
    public QueryExecutionException unknownColumn(String tableName, String columnName,
                                                 String schemaId) {
        return new QueryExecutionException(ErrorCode.UNKNOWN_COLUMN,
                "Column '" + tableName + "." + columnName + "' not found. Available: "
                + availableColumns(schemaId) + ".");
    }

    /** Formats a schema map as a comma-separated column list, ordered by index then name. */
    public static String formatColumns(Map<String, Integer> schema) {
        if (schema == null || schema.isEmpty()) {
            return "(none)";
        }
        return schema.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Result-header names for a schema, in column order. Ported verbatim from
     * DBCatalog.getOrderedColumnNames: bare-ifies plain columns, keeps aggregate
     * keys whole, width = max index + 1, sorted-key determinism.
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
}
