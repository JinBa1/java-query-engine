package com.github.jinba1.cuckoodb;

/**
 * Defines global constants used throughout the CuckooDB system.
 * This class contains application-wide constants to ensure consistency
 * and reduce duplication across the codebase. These include file and directory names,
 * schema prefixes, and other string literals used for database operations.
 */
public class Constants {

    /** Prefix used to identify intermediate schemas created during query processing */
    public static final String INTERMEDIATE_SCHEMA_PREFIX = "temp_";

    /** Directory name where database data files are stored */
    public static final String DATA_DIRECTORY_NAME = "data";

}
