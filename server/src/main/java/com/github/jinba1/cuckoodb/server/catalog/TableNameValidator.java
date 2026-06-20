package com.github.jinba1.cuckoodb.server.catalog;

import java.util.regex.Pattern;

/**
 * The single table-name guard shared by every request surface (REST upload and MCP tools). The
 * server is the <em>only</em> guard on a table name — the engine and {@code ScanOperator} use it
 * verbatim to open a file — so every name is validated against this strict charset before it
 * reaches a query or a filesystem path. Blocks path-traversal shapes (dot, slash) and
 * SQL-injection bait (space, semicolon, dash) by construction.
 */
public final class TableNameValidator {

    /** The only table names the server will touch; blocks path traversal and odd characters. */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private TableNameValidator() {
    }

    /**
     * @throws IllegalArgumentException if {@code name} is null or not {@code [A-Za-z0-9_]{1,64}}
     */
    public static void validate(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid table name '" + name + "'; must match [A-Za-z0-9_]{1,64}.");
        }
    }
}
