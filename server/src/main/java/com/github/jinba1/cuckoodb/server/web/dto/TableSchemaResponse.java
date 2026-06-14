package com.github.jinba1.cuckoodb.server.web.dto;

import java.util.List;

/**
 * Response body for {@code GET /tables/{name}}: a table's static typed schema. Types come from
 * the catalog, not from a query result, so schema discovery never depends on a non-empty result.
 *
 * @param name    the table name
 * @param columns the columns in column order
 */
public record TableSchemaResponse(String name, List<TableColumnDto> columns) {
}
