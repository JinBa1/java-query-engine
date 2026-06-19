package com.github.jinba1.cuckoodb.server.web.dto;

import java.util.List;

/**
 * Response body for a successful {@code POST /tables/{name}} (201).
 *
 * @param name     the registered table name
 * @param columns  the inferred typed schema, in column order
 * @param rowCount the number of data rows ingested
 */
public record UploadResponse(String name, List<TableColumnDto> columns, long rowCount) {
}
