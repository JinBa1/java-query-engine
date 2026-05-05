package com.github.jinba1.blazedb;

/**
 * Enum representing the types of schema transformations that can occur during query processing.
 * The schema transformation type is used to track how operator actions change the schema structure.
 * This information helps with column resolution and optimization during query execution.
 */
public enum SchemaTransformationType {

    // projection which select a subset of columns
    PROJECTION,

    // joins which combine multiple schemas
    JOIN,

    // aggregation which group and aggregate data
    AGGREGATION,

    // used by operations that don't change schema structure
    OTHER
}
