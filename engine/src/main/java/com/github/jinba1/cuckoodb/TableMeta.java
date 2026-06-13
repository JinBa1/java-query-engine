package com.github.jinba1.cuckoodb;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Durable metadata for one base table, held as a single value in {@link DBCatalog}.
 * Merging location, schema, and column types into one record lets the catalog publish
 * a table with a single atomic map write — a table is either fully absent or fully
 * present, with no torn read in any direction. This is the precondition for safe
 * runtime table registration (REST upload) while queries read concurrently.
 *
 * @param path   the CSV file backing the table; {@link com.github.jinba1.cuckoodb.operator.ScanOperator}
 *               re-opens it on every {@code reset()}, so it must outlive the query
 * @param schema column name (lowercased) to position, unmodifiable
 * @param types  column types in column order, unmodifiable
 */
public record TableMeta(Path path, Map<String, Integer> schema, List<ColumnType> types) {
}
