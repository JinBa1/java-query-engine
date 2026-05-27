package com.github.jinba1.blazedb;

/**
 * Defines global constants used throughout the BlazeDB system.
 * This class contains application-wide constants to ensure consistency
 * and reduce duplication across the codebase. These include configuration options,
 * file and directory names, schema prefixes, and other string literals used
 * for database operations.
 */
public class Constants {

    /** Controls whether query plan optimization is enabled */
    public static boolean useQueryOptimization = true;

    /** Prefix used to identify intermediate schemas created during query processing */
    public static final String INTERMEDIATE_SCHEMA_PREFIX = "temp_";

    /** Standard filename for database schema definitions */
    public static final String SCHEMA_FILE_NAME = "schema.txt";

    /** Directory name where database data files are stored */
    public static final String DATA_DIRECTORY_NAME = "data";

    /** SQL aggregation function name for sum operations */
    public static final String SUM_FUNCTION_NAME = "SUM";

    /** Regular expression used for splitting schema file entries */
    public static final String SPLITTER_REGEX = "\\s+";
}
