package com.github.jinba1.cuckoodb;

import java.util.List;

/**
 * A fully-materialized query result held in memory, for callers (the REST gateway) that
 * need the rows as data rather than written to a CSV file. The CLI path keeps writing to
 * a file via {@link CuckooDB#execute}; both share one drain helper so they cannot diverge
 * in iteration, row count, or truncation semantics.
 *
 * <p>Rows are positional and aligned with {@code columns} by index — see {@link ColumnMeta}
 * for why callers must not rely on column names being unique.
 *
 * @param columns   one entry per output column, in column order
 * @param rows      the result rows; each row's values align with {@code columns} by index
 * @param truncated true when a LIMIT stopped the query although more rows existed
 * @param hint      how to refine the query when truncated; null otherwise
 */
public record QueryResultSet(List<ColumnMeta> columns, List<List<Value>> rows,
                             boolean truncated, String hint) {
}
