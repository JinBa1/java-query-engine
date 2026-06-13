package com.github.jinba1.blazedb;

/**
 * Machine-readable category for a {@link QueryExecutionException}. Downstream surfaces
 * (CLI today, REST/MCP later) classify failures by code instead of parsing message text:
 * user errors map to 4xx-style handling, {@link #INTERNAL} to 5xx.
 */
public enum ErrorCode {
    /** The SQL text could not be parsed. */
    PARSE_ERROR,
    /** The SQL parsed but uses constructs the engine does not support. */
    UNSUPPORTED_SQL,
    /** A referenced table does not exist in the catalog. */
    UNKNOWN_TABLE,
    /** A referenced column does not exist in the schema searched. */
    UNKNOWN_COLUMN,
    /** Values of incompatible types were compared, joined, or aggregated. */
    TYPE_MISMATCH,
    /** The query exceeded its tuple or time budget. */
    BUDGET_EXCEEDED,
    /** Source-data or I/O problem: unreadable file, malformed row, numeric overflow. */
    DATA_ERROR,
    /** An engine invariant broke — a bug, not a user error. */
    INTERNAL
}
